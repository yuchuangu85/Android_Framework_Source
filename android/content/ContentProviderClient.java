/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content;

import static android.content.flags.Flags.FLAG_ENABLE_CONTENT_PROVIDER_CLIENT_ANR_ON_CANCEL;
import static android.content.flags.Flags.enableContentProviderClientAnrOnCancel;

import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetFileDescriptor;
import android.database.CrossProcessCursorWrapper;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import dalvik.system.CloseGuard;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The public interface object used to interact with a specific
 * {@link ContentProvider}.
 * <p>
 * Instances can be obtained by calling
 * {@link ContentResolver#acquireContentProviderClient} or
 * {@link ContentResolver#acquireUnstableContentProviderClient}. Instances must
 * be released using {@link #close()} in order to indicate to the system that
 * the underlying {@link ContentProvider} is no longer needed and can be killed
 * to free up resources.
 * <p>
 * Note that you should generally create a new ContentProviderClient instance
 * for each thread that will be performing operations. Unlike
 * {@link ContentResolver}, the methods here such as {@link #query} and
 * {@link #openFile} are not thread safe -- you must not call {@link #close()}
 * on the ContentProviderClient those calls are made from until you are finished
 * with the data they have returned.
 */
@RavenwoodKeepWholeClass
public class ContentProviderClient implements ContentInterface, AutoCloseable {
    private static final String TAG = "ContentProviderClient";
    private static final long CALL_NOT_CANCELLED_TIMEOUT_MILLIS = 6 * DateUtils.HOUR_IN_MILLIS;

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static Handler sAnrHandler;

    private final ContentResolver mContentResolver;
    @UnsupportedAppUsage
    private final IContentProvider mContentProvider;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final String mPackageName;
    private final @NonNull AttributionSource mAttributionSource;

    private final String mAuthority;
    private final boolean mStable;

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    /**
     * Fixed timeout (in ms) for remote {@link ContentProvider} calls. If the call does not finish
     * in the specified time, the remote provider process is terminated with ANR.
     */
    private long mAnrTimeout;

    /**
     * A Runnable that is executed after {@link #mAnrTimeout} passes, terminating the remote
     * provider
     * with ANR.
     */
    @Nullable
    private NotRespondingRunnable mAnrRunnable;

    /**
     * Similar to mAnrTimeout, but the timeout starts when a {@link CancellationSignal} passed to
     * the remote {@link ContentProvider} call is called. Only applies to calls that take a
     * cancellation signal. If this variable is set to greater than 0, timeout on cancellation will
     * be used instead of fixed timeout. Otherwise mAnrTimeout is used for all calls.
     */
    private long mAnrTimeoutOnCancel;

    /**
     * A Runnable that is executed after {@link #mAnrTimeoutOnCancel} passes, terminating the remote
     * provider with ANR.
     */
    @Nullable
    private NotRespondingRunnable mAnrRunnableOnCancel;

    /**
     * Time (im ms) before which a stalled {@link ContentProvider} call is expected to be cancelled.
     * Reaching this timeout usually indicates a bug in the calling application.
     */
    private long mCallNotCancelledTimeoutMillis = CALL_NOT_CANCELLED_TIMEOUT_MILLIS;

    /**
     * A Runnable that is executed after {@link mCallNotCancelledTimeoutMillis} passes, logging an
     * error.
     */
    @Nullable private CallNotCancelledRunnable mCallNotCancelledRunnable;

    /** @hide */
    @VisibleForTesting
    public ContentProviderClient(ContentResolver contentResolver, IContentProvider contentProvider,
            boolean stable) {
        // Only used for testing, so use a fake authority
        this(contentResolver, contentProvider, "unknown", stable);
    }

    /** @hide */
    public ContentProviderClient(ContentResolver contentResolver, IContentProvider contentProvider,
            String authority, boolean stable) {
        mContentResolver = contentResolver;
        mContentProvider = contentProvider;
        mPackageName = contentResolver.mPackageName;
        mAttributionSource = contentResolver.getAttributionSource();

        mAuthority = authority;
        mStable = stable;

        mCloseGuard.open("ContentProviderClient.close");
    }

    /**
     * Configure this client to automatically detect and kill the remote provider when a provider
     * call blocks longer than the specified amount of time.
     *
     * @param timeoutMillis the duration for which a pending call is allowed block before the remote
     *     provider is considered to be unresponsive. Set to {@code 0} to allow pending calls to
     *     block indefinitely with no action taken.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REMOVE_TASKS)
    public void setDetectNotResponding(@DurationMillisLong long timeoutMillis) {
        if (enableContentProviderClientAnrOnCancel()) {
            setDetectNotRespondingOnCancel(timeoutMillis, /* timeoutOnCancelMillis= */ 0);
            return;
        }
        synchronized (sLock) {
            mAnrTimeout = timeoutMillis;

            if (timeoutMillis > 0) {
                if (mAnrRunnable == null) {
                    mAnrRunnable = new NotRespondingRunnable();
                }
                if (sAnrHandler == null) {
                    sAnrHandler = new Handler(Looper.getMainLooper(), null, true /* async */);
                }

                // If the remote process hangs, we're going to kill it, so we're
                // technically okay doing blocking calls.
                Binder.allowBlocking(mContentProvider.asBinder());
            } else {
                mAnrRunnable = null;

                // If we're no longer watching for hangs, revert back to default
                // blocking behavior.
                Binder.defaultBlocking(mContentProvider.asBinder());
            }
        }
    }

    /**
     * Configure this client to automatically detect and kill the remote provider when a provider
     * call blocks longer than the specified amount of time. This variant configures two timeout
     * values: one for calls that take a {@link android.os.CancellationSignal} and one for calls
     * that do not. For calls that support cancellation, the timeout starts after the cancellation
     * signal is called. For calls that do not, the timeout starts once the call is made, as is with
     * the {@link #setDetectNotResponding(long)}.
     *
     * @param timeoutFixedMillis the duration for which a pending call is allowed block before the
     *     remote provider is considered to be unresponsive. Set to {@code 0} to allow pending calls
     *     to block indefinitely with no action taken.
     * @param timeoutOnCancelMillis the duration for which a pending call is allowed block after
     *     cancellation before the remote provider is considered to be unresponsive. Only applies to
     *     calls that take a {@link CancellationSignal}. If set to greater than {@code 0}, fixed
     *     timeout will not apply to such calls. If set to {@code 0}, fixed timeout will be used for
     *     all calls, regardless if they take a cancellation signal or not.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_ENABLE_CONTENT_PROVIDER_CLIENT_ANR_ON_CANCEL)
    @RequiresPermission(android.Manifest.permission.REMOVE_TASKS)
    public void setDetectNotRespondingOnCancel(
            @DurationMillisLong long timeoutFixedMillis,
            @DurationMillisLong long timeoutOnCancelMillis) {
        synchronized (sLock) {
            mAnrTimeout = timeoutFixedMillis;
            mAnrTimeoutOnCancel = timeoutOnCancelMillis;

            if (sAnrHandler == null) {
                sAnrHandler = new Handler(Looper.getMainLooper(), null, /* async= */ true);
            }

            mAnrRunnable = timeoutFixedMillis > 0 ? new NotRespondingRunnable() : null;
            mAnrRunnableOnCancel = timeoutOnCancelMillis > 0 ? new NotRespondingRunnable() : null;
            mCallNotCancelledRunnable =
                    timeoutOnCancelMillis > 0 ? new CallNotCancelledRunnable() : null;

            if (timeoutFixedMillis > 0 || timeoutOnCancelMillis > 0) {
                Binder.allowBlocking(mContentProvider.asBinder());
            } else {
                Binder.defaultBlocking(mContentProvider.asBinder());
            }
        }
    }

    /** @hide */
    @VisibleForTesting
    public void setCallNotCancelledTimeout(@DurationMillisLong long timeoutMillis) {
        mCallNotCancelledTimeoutMillis = timeoutMillis;
    }

    private void beforeRemote() {
        if (mAnrRunnable != null) {
            sAnrHandler.postDelayed(mAnrRunnable, mAnrTimeout);
        }
    }

    private void beforeRemote(CancellationSignal cancellationSignal) {
        if (!enableContentProviderClientAnrOnCancel()) {
            beforeRemote();
            return;
        }

        if (mCallNotCancelledRunnable != null) {
            sAnrHandler.postDelayed(mCallNotCancelledRunnable, mCallNotCancelledTimeoutMillis);
        }

        if (mAnrRunnable != null) {
            // Apply fixed timeout to calls without cancellation signal, or calls
            // with cancellation signal when timeout on cancel is not set.
            if (cancellationSignal == null || mAnrRunnableOnCancel == null) {
                sAnrHandler.postDelayed(mAnrRunnable, mAnrTimeout);
            }
        }
    }

    private void afterRemote() {
        if (mAnrRunnable != null) {
            sAnrHandler.removeCallbacks(mAnrRunnable);
        }
        if (enableContentProviderClientAnrOnCancel()) {
            if (mAnrRunnableOnCancel != null) {
                sAnrHandler.removeCallbacks(mAnrRunnableOnCancel);
            }
            if (mCallNotCancelledRunnable != null) {
                sAnrHandler.removeCallbacks(mCallNotCancelledRunnable);
            }
        }
    }

    private CancellationSignal maybeWrapNotRespondingSignal(CancellationSignal callerSignal) {
        if (mAnrRunnableOnCancel == null) {
            return callerSignal;
        }
        CancellationSignal innerSignal = new CancellationSignal();
        callerSignal.setOnCancelListener(
                () -> {
                    innerSignal.cancel();
                    sAnrHandler.postDelayed(mAnrRunnableOnCancel, mAnrTimeoutOnCancel);
                });
        return innerSignal;
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public @Nullable Cursor query(@NonNull Uri url, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder) throws RemoteException {
        return query(url, projection, selection, selectionArgs, sortOrder, null);
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder, @Nullable CancellationSignal cancellationSignal)
            throws RemoteException {
        Bundle queryArgs =
                ContentResolver.createSqlQueryBundle(selection, selectionArgs, sortOrder);
        return query(uri, projection, queryArgs, cancellationSignal);
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    @Override
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            Bundle queryArgs, @Nullable CancellationSignal cancellationSignal)
            throws RemoteException {
        Objects.requireNonNull(uri, "url");
        return execute(cancellationSignal, remoteCancellationSignal -> {
            final Cursor cursor = mContentProvider.query(
                    mAttributionSource, uri, projection, queryArgs,
                    remoteCancellationSignal);
            if (cursor == null) {
                return null;
            }
            return new CursorWrapperInner(cursor);
        });
    }

    /** See {@link ContentProvider#getType ContentProvider.getType} */
    @Override
    public @Nullable String getType(@NonNull Uri url) throws RemoteException {
        Objects.requireNonNull(url, "url");
        return execute(() -> mContentProvider.getType(mAttributionSource, url));
    }

    /** See {@link ContentProvider#getStreamTypes ContentProvider.getStreamTypes} */
    @Override
    public @Nullable String[] getStreamTypes(@NonNull Uri url, @NonNull String mimeTypeFilter)
            throws RemoteException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(mimeTypeFilter, "mimeTypeFilter");
        return execute(
                () -> mContentProvider.getStreamTypes(mAttributionSource, url, mimeTypeFilter));
    }

    /** See {@link ContentProvider#canonicalize} */
    @Override
    public final @Nullable Uri canonicalize(@NonNull Uri url) throws RemoteException {
        Objects.requireNonNull(url, "url");
        return execute(() -> mContentProvider.canonicalize(mAttributionSource, url));
    }

    /** See {@link ContentProvider#uncanonicalize} */
    @Override
    public final @Nullable Uri uncanonicalize(@NonNull Uri url) throws RemoteException {
        Objects.requireNonNull(url, "url");
        return execute(() -> mContentProvider.uncanonicalize(mAttributionSource, url));
    }

    /** See {@link ContentProvider#refresh} */
    @Override
    public boolean refresh(Uri url, @Nullable Bundle extras,
            @Nullable CancellationSignal cancellationSignal) throws RemoteException {
        Objects.requireNonNull(url, "url");
        return execute(cancellationSignal, (remoteCancellationSignal) ->
                mContentProvider.refresh(mAttributionSource, url, extras, remoteCancellationSignal)
        );
    }

    /** @hide */
    @Override
    public int checkUriPermission(@NonNull Uri uri, int uid, @Intent.AccessUriMode int modeFlags)
            throws RemoteException {
        Objects.requireNonNull(uri, "uri");
        return execute(() -> mContentProvider.checkUriPermission(mAttributionSource, uri, uid,
                modeFlags));
    }

    /** See {@link ContentProvider#insert ContentProvider.insert} */
    public @Nullable Uri insert(@NonNull Uri url, @Nullable ContentValues initialValues)
            throws RemoteException {
        return insert(url, initialValues, null);
    }

    /** See {@link ContentProvider#insert ContentProvider.insert} */
    @Override
    public @Nullable Uri insert(@NonNull Uri url, @Nullable ContentValues initialValues,
            @Nullable Bundle extras) throws RemoteException {
        Objects.requireNonNull(url, "url");
        return execute(() -> mContentProvider.insert(mAttributionSource, url, initialValues,
                extras));
    }

    /** See {@link ContentProvider#bulkInsert ContentProvider.bulkInsert} */
    @Override
    public int bulkInsert(@NonNull Uri url, @NonNull ContentValues[] initialValues)
            throws RemoteException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(initialValues, "initialValues");
        return execute(() -> mContentProvider.bulkInsert(mAttributionSource, url, initialValues));
    }

    /** See {@link ContentProvider#delete ContentProvider.delete} */
    public int delete(@NonNull Uri url, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException {
        return delete(url, ContentResolver.createSqlQueryBundle(selection, selectionArgs));
    }

    /** See {@link ContentProvider#delete ContentProvider.delete} */
    @Override
    public int delete(@NonNull Uri url, @Nullable Bundle extras) throws RemoteException {
        Objects.requireNonNull(url, "url");
        return execute(() -> mContentProvider.delete(mAttributionSource, url, extras));
    }

    /** See {@link ContentProvider#update ContentProvider.update} */
    public int update(@NonNull Uri url, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException {
        return update(url, values, ContentResolver.createSqlQueryBundle(selection, selectionArgs));
    }

    /** See {@link ContentProvider#update ContentProvider.update} */
    @Override
    public int update(@NonNull Uri url, @Nullable ContentValues values, @Nullable Bundle extras)
            throws RemoteException {
        Objects.requireNonNull(url, "url");
        return execute(() -> mContentProvider.update(mAttributionSource, url, values, extras));
    }

    /**
     * See {@link ContentProvider#openFile ContentProvider.openFile}.  Note that
     * this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openFileDescriptor
     * ContentResolver.openFileDescriptor} API instead.
     */
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri url, @NonNull String mode)
            throws RemoteException, FileNotFoundException {
        return openFile(url, mode, null);
    }

    /**
     * See {@link ContentProvider#openFile ContentProvider.openFile}.  Note that
     * this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openFileDescriptor
     * ContentResolver.openFileDescriptor} API instead.
     */
    @Override
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri url, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(mode, "mode");
        return executeWithFileNotFound(signal, (remoteSignal) ->
                mContentProvider.openFile(mAttributionSource, url, mode, remoteSignal));
    }

    /**
     * See {@link ContentProvider#openAssetFile ContentProvider.openAssetFile}.
     * Note that this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openAssetFileDescriptor
     * ContentResolver.openAssetFileDescriptor} API instead.
     */
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri url, @NonNull String mode)
            throws RemoteException, FileNotFoundException {
        return openAssetFile(url, mode, null);
    }

    /**
     * See {@link ContentProvider#openAssetFile ContentProvider.openAssetFile}.
     * Note that this <em>does not</em>
     * take care of non-content: URIs such as file:.  It is strongly recommended
     * you use the {@link ContentResolver#openAssetFileDescriptor
     * ContentResolver.openAssetFileDescriptor} API instead.
     */
    @Override
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri url, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(mode, "mode");
        return executeWithFileNotFound(signal, (remoteSignal) ->
                mContentProvider.openAssetFile(mAttributionSource, url, mode, remoteSignal));
    }

    /** See {@link ContentProvider#openTypedAssetFile ContentProvider.openTypedAssetFile} */
    public final @Nullable AssetFileDescriptor openTypedAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mimeType, @Nullable Bundle opts)
            throws RemoteException, FileNotFoundException {
        return openTypedAssetFileDescriptor(uri, mimeType, opts, null);
    }

    /** See {@link ContentProvider#openTypedAssetFile ContentProvider.openTypedAssetFile} */
    public final @Nullable AssetFileDescriptor openTypedAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mimeType, @Nullable Bundle opts, @Nullable CancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        return openTypedAssetFile(uri, mimeType, opts, signal);
    }

    @Override
    public final @Nullable AssetFileDescriptor openTypedAssetFile(@NonNull Uri uri,
            @NonNull String mimeTypeFilter, @Nullable Bundle opts,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(mimeTypeFilter, "mimeTypeFilter");
        return executeWithFileNotFound(signal,
                (remoteSignal) -> mContentProvider.openTypedAssetFile(
                        mAttributionSource, uri, mimeTypeFilter, opts, remoteSignal));
    }

    /** See {@link ContentProvider#applyBatch ContentProvider.applyBatch} */
    public @NonNull ContentProviderResult[] applyBatch(
            @NonNull ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        return applyBatch(mAuthority, operations);
    }

    /** See {@link ContentProvider#applyBatch ContentProvider.applyBatch} */
    @Override
    public @NonNull ContentProviderResult[] applyBatch(@NonNull String authority,
            @NonNull ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        Objects.requireNonNull(operations, "operations");

        return executeWithOperationApplicationException(
                () -> mContentProvider.applyBatch(mAttributionSource, authority,
                        operations));
    }

    /** See {@link ContentProvider#call(String, String, Bundle)} */
    public @Nullable Bundle call(@NonNull String method, @Nullable String arg,
            @Nullable Bundle extras) throws RemoteException {
        return call(mAuthority, method, arg, extras);
    }

    /** See {@link ContentProvider#call(String, String, Bundle)} */
    @Override
    public @Nullable Bundle call(@NonNull String authority, @NonNull String method,
            @Nullable String arg, @Nullable Bundle extras) throws RemoteException {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(method, "method");
        return execute(() -> mContentProvider.call(mAttributionSource, authority, method, arg,
                extras));
    }

    /**
     * Closes this client connection, indicating to the system that the
     * underlying {@link ContentProvider} is no longer needed.
     */
    @Override
    public void close() {
        closeInternal();
    }

    /**
     * @deprecated replaced by {@link #close()}.
     */
    @Deprecated
    public boolean release() {
        return closeInternal();
    }

    private boolean closeInternal() {
        mCloseGuard.close();
        if (mClosed.compareAndSet(false, true)) {
            // We can't do ANR checks after we cease to exist! Reset any
            // blocking behavior changes we might have made.
            setDetectNotResponding(0);

            if (mStable) {
                return mContentResolver.releaseProvider(mContentProvider);
            } else {
                return mContentResolver.releaseUnstableProvider(mContentProvider);
            }
        } else {
            return false;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Get a reference to the {@link ContentProvider} that is associated with this
     * client. If the {@link ContentProvider} is running in a different process then
     * null will be returned. This can be used if you know you are running in the same
     * process as a provider, and want to get direct access to its implementation details.
     *
     * @return If the associated {@link ContentProvider} is local, returns it.
     * Otherwise returns null.
     */
    public @Nullable ContentProvider getLocalContentProvider() {
        return ContentProvider.coerceToLocalContentProvider(mContentProvider);
    }

    /** @hide */
    @Deprecated
    public static void closeQuietly(ContentProviderClient client) {
        IoUtils.closeQuietly(client);
    }

    /** @hide */
    @Deprecated
    public static void releaseQuietly(ContentProviderClient client) {
        IoUtils.closeQuietly(client);
    }

    private class NotRespondingRunnable implements Runnable {
        @Override
        public void run() {
            Log.w(TAG, "Detected provider not responding: " + mContentProvider);
            mContentResolver.appNotRespondingViaProvider(mContentProvider);
        }
    }

    /**
     * A functional interface for a remote apply that doesn't take a cancellation signal.
     *
     * @param <T> The return type of the remote apply.
     */
    @FunctionalInterface
    private interface RemoteCall<T> {
        /**
         * Applies this function to the given arguments.
         *
         * @return the function result
         */
        T apply() throws RemoteException;
    }

    /**
     * A functional interface for a remote apply that takes a cancellation signal.
     *
     * @param <T> The return type of the remote apply.
     */
    @FunctionalInterface
    private interface CancellableRemoteCall<T> {
        /**
         * Applies this function to the given arguments.
         *
         * @param signal the cancellation signal
         * @return the function result
         */
        T apply(@Nullable ICancellationSignal signal) throws RemoteException;
    }

    /**
     * Prepares a remote cancellation signal for a cancellable remote operation.
     *
     * @param cancellationSignal The cancellation signal provided by the caller.
     * @return The remote cancellation signal, or {@code null} if the provided signal was
     * {@code null}.
     * @throws RemoteException if the remote content provider is not available.
     */
    private @Nullable ICancellationSignal prepareRemoteCancellationSignal(
            @Nullable CancellationSignal cancellationSignal) throws RemoteException {
        if (cancellationSignal == null) {
            return null;
        }

        cancellationSignal.throwIfCanceled();

        CancellationSignal transport = cancellationSignal;
        if (enableContentProviderClientAnrOnCancel()) {
            transport = maybeWrapNotRespondingSignal(cancellationSignal);
        }

        final ICancellationSignal remote = mContentProvider.createCancellationSignal();
        transport.setRemote(remote);
        return remote;
    }

    /**
     * Executes a remote apply and handles ANR and DeadObjectException.
     *
     * @param remoteCall The remote apply to execute.
     * @param <T>        The return type of the remote apply.
     * @return The result of the remote apply.
     * @throws RemoteException if the remote apply fails.
     */
    private <T> T execute(RemoteCall<T> remoteCall) throws RemoteException {
        beforeRemote(/* cancellationSignal= */ null);
        try {
            return remoteCall.apply();
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /**
     * Executes a cancellable remote apply and handles ANR and DeadObjectException.
     *
     * @param cancellationSignal The cancellation signal for the remote apply.
     * @param remoteCall         The remote apply to execute.
     * @param <T>                The return type of the remote apply.
     * @return The result of the remote apply.
     * @throws RemoteException if the remote apply fails.
     */
    private <T> T execute(
            @Nullable CancellationSignal cancellationSignal,
            CancellableRemoteCall<T> remoteCall) throws RemoteException {
        beforeRemote(cancellationSignal);
        try {
            final ICancellationSignal remoteCancellationSignal =
                    prepareRemoteCancellationSignal(cancellationSignal);
            return remoteCall.apply(remoteCancellationSignal);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /**
     * A remote operation that can be executed and may throw a {@link FileNotFoundException}.
     *
     * @param <T> The type of the result of the operation.
     */
    @FunctionalInterface
    private interface RemoteCallWithFileNotFound<T> {
        /**
         * Applies this function to the given arguments.
         *
         * @return The result of the operation.
         * @throws RemoteException       if a remote error occurs.
         * @throws FileNotFoundException if the file is not found.
         */
        T apply(@Nullable ICancellationSignal signal) throws RemoteException, FileNotFoundException;
    }

    /**
     * Executes a remote operation that may throw a {@link FileNotFoundException} and handles ANR
     * and DeadObjectException.
     *
     * @param remoteCallable The remote operation to execute.
     * @param <T>            The type of the result of the operation.
     * @return The result of the operation.
     * @throws RemoteException       if a remote error occurs.
     * @throws FileNotFoundException if the file is not found.
     */
    private <T> T executeWithFileNotFound(@Nullable CancellationSignal cancellationSignal,
            RemoteCallWithFileNotFound<T> remoteCallable)
            throws RemoteException, FileNotFoundException {
        beforeRemote(cancellationSignal);
        try {
            final ICancellationSignal remoteCancellationSignal =
                    prepareRemoteCancellationSignal(cancellationSignal);
            return remoteCallable.apply(remoteCancellationSignal);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    /**
     * A remote operation that can be executed and may throw an
     * {@link OperationApplicationException}.
     *
     * @param <T> The type of the result of the operation.
     */
    @FunctionalInterface
    private interface RemoteCallWithOperationApplicationException<T> {
        /**
         * Applies this function to the given arguments.
         *
         * @return The result of the operation.
         * @throws RemoteException               if a remote error occurs.
         * @throws OperationApplicationException if the operation fails to apply.
         */
        T apply() throws RemoteException, OperationApplicationException;
    }

    /**
     * Executes a remote operation that may throw an {@link OperationApplicationException} and
     * handles ANR and DeadObjectException.
     *
     * @param remoteCallable The remote operation to execute.
     * @param <T>            The type of the result of the operation.
     * @return The result of the operation.
     * @throws RemoteException               if a remote error occurs.
     * @throws OperationApplicationException if the operation fails to apply.
     */
    private <T> T executeWithOperationApplicationException(
            RemoteCallWithOperationApplicationException<T> remoteCallable)
            throws RemoteException, OperationApplicationException {
        beforeRemote(/* cancellationSignal= */ null);
        try {
            return remoteCallable.apply();
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }

    private final class CallNotCancelledRunnable implements Runnable {
        @Override
        public void run() {
            Log.wtf(TAG, "Provider call is not cancelled: " + mContentProvider);
        }
    }

    /**
     * A specialized {@link CrossProcessCursorWrapper} that adds a {@link CloseGuard} to detect
     * unclosed cursors.
     */
    private static final class CursorWrapperInner extends CrossProcessCursorWrapper {
        private final CloseGuard mCloseGuard = CloseGuard.get();

        CursorWrapperInner(Cursor cursor) {
            super(cursor);
            mCloseGuard.open("CursorWrapperInner.close");
        }

        @Override
        public void close() {
            mCloseGuard.close();
            super.close();
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mCloseGuard != null) {
                    mCloseGuard.warnIfOpen();
                }

                close();
            } finally {
                super.finalize();
            }
        }
    }
}

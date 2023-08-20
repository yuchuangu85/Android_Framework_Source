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

package android.test.mock;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.AttributionSource;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * Mock implementation of ContentProvider.  All methods are non-functional and throw
 * {@link java.lang.UnsupportedOperationException}.  Tests can extend this class to
 * implement behavior needed for tests.
 */
public class MockContentProvider extends ContentProvider {
    /*
     * Note: if you add methods to ContentProvider, you must add similar methods to
     *       MockContentProvider.
     */

    /**
     * IContentProvider that directs all calls to this MockContentProvider.
     */
    private class InversionIContentProvider implements IContentProvider {
        @Override
        public ContentProviderResult[] applyBatch(@NonNull AttributionSource attributionSource,
                String authority, ArrayList<ContentProviderOperation> operations)
                throws RemoteException, OperationApplicationException {
            return MockContentProvider.this.applyBatch(authority, operations);
        }

        @Override
        public int bulkInsert(@NonNull AttributionSource attributionSource, Uri url,
                ContentValues[] initialValues) throws RemoteException {
            return MockContentProvider.this.bulkInsert(url, initialValues);
        }

        @Override
        public int delete(@NonNull AttributionSource attributionSource, Uri url,
                Bundle extras) throws RemoteException {
            return MockContentProvider.this.delete(url, extras);
        }

        @Override
        public String getType(@NonNull AttributionSource attributionSource,
                Uri url) throws RemoteException {
            return MockContentProvider.this.getType(url);
        }

        @Override
        public void getTypeAsync(@NonNull AttributionSource attributionSource,
                Uri uri, RemoteCallback callback) throws RemoteException {
            MockContentProvider.this.getTypeAsync(uri, callback);
        }

        @Override
        public void getTypeAnonymousAsync(Uri uri, RemoteCallback callback) throws RemoteException {
            MockContentProvider.this.getTypeAnonymousAsync(uri, callback);
        }
        @Override
        public Uri insert(@NonNull AttributionSource attributionSource, Uri url,
                ContentValues initialValues, Bundle extras) throws RemoteException {
            return MockContentProvider.this.insert(url, initialValues, extras);
        }

        @Override
        public AssetFileDescriptor openAssetFile(@NonNull AttributionSource attributionSource,
                Uri url, String mode, ICancellationSignal signal)
                throws RemoteException, FileNotFoundException {
            return MockContentProvider.this.openAssetFile(url, mode);
        }

        @Override
        public ParcelFileDescriptor openFile(@NonNull AttributionSource attributionSource,
                Uri url, String mode, ICancellationSignal signal)
                throws RemoteException, FileNotFoundException {
            return MockContentProvider.this.openFile(url, mode);
        }

        @Override
        public Cursor query(@NonNull AttributionSource attributionSource, Uri url,
                @Nullable String[] projection, @Nullable Bundle queryArgs,
                @Nullable ICancellationSignal cancellationSignal) throws RemoteException {
            return MockContentProvider.this.query(url, projection, queryArgs, null);
        }

        @Override
        public int update(@NonNull AttributionSource attributionSource, Uri url,
                ContentValues values, Bundle extras) throws RemoteException {
            return MockContentProvider.this.update(url, values, extras);
        }

        @Override
        public Bundle call(@NonNull AttributionSource attributionSource, String authority,
                String method, String request, Bundle args) throws RemoteException {
            return MockContentProvider.this.call(authority, method, request, args);
        }

        @Override
        public IBinder asBinder() {
            return MockContentProvider.this.getIContentProviderBinder();
        }

        @Override
        public String[] getStreamTypes(AttributionSource attributionSource,
                Uri url, String mimeTypeFilter) throws RemoteException {
            return MockContentProvider.this.getStreamTypes(url, mimeTypeFilter);
        }

        @Override
        public AssetFileDescriptor openTypedAssetFile(
                @NonNull AttributionSource attributionSource, Uri url, String mimeType,
                Bundle opts, ICancellationSignal signal)
                throws RemoteException, FileNotFoundException {
            return MockContentProvider.this.openTypedAssetFile(url, mimeType, opts);
        }

        @Override
        public ICancellationSignal createCancellationSignal() throws RemoteException {
            return null;
        }

        @Override
        public Uri canonicalize(@NonNull AttributionSource attributionSource, Uri uri)
                throws RemoteException {
            return MockContentProvider.this.canonicalize(uri);
        }

        @Override
        public void canonicalizeAsync(@NonNull AttributionSource attributionSource, Uri uri,
                RemoteCallback callback) {
            MockContentProvider.this.canonicalizeAsync(uri, callback);
        }

        @Override
        public Uri uncanonicalize(@NonNull AttributionSource attributionSource, Uri uri)
                throws RemoteException {
            return MockContentProvider.this.uncanonicalize(uri);
        }

        @Override
        public void uncanonicalizeAsync(@NonNull AttributionSource attributionSource, Uri uri,
                RemoteCallback callback) {
            MockContentProvider.this.uncanonicalizeAsync(uri, callback);
        }

        @Override
        public boolean refresh(@NonNull AttributionSource attributionSource, Uri url,
                Bundle args, ICancellationSignal cancellationSignal) throws RemoteException {
            return MockContentProvider.this.refresh(url, args);
        }

        @Override
        public int checkUriPermission(@NonNull AttributionSource attributionSource, Uri uri,
                int uid, int modeFlags) {
            return MockContentProvider.this.checkUriPermission(uri, uid, modeFlags);
        }
    }
    private final InversionIContentProvider mIContentProvider = new InversionIContentProvider();

    /**
     * A constructor using {@link MockContext} instance as a Context in it.
     */
    protected MockContentProvider() {
        super(new MockContext(), "", "", null);
    }

    /**
     * A constructor accepting a Context instance, which is supposed to be the subclasss of
     * {@link MockContext}.
     */
    public MockContentProvider(Context context) {
        super(context, "", "", null);
    }

    /**
     * A constructor which initialize four member variables which
     * {@link android.content.ContentProvider} have internally.
     *
     * @param context A Context object which should be some mock instance (like the
     * instance of {@link android.test.mock.MockContext}).
     * @param readPermission The read permision you want this instance should have in the
     * test, which is available via {@link #getReadPermission()}.
     * @param writePermission The write permission you want this instance should have
     * in the test, which is available via {@link #getWritePermission()}.
     * @param pathPermissions The PathPermissions you want this instance should have
     * in the test, which is available via {@link #getPathPermissions()}.
     */
    public MockContentProvider(Context context,
            String readPermission,
            String writePermission,
            PathPermission[] pathPermissions) {
        super(context, readPermission, writePermission, pathPermissions);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    /**
     * @hide
     */
    @SuppressWarnings("deprecation")
    public void getTypeAsync(Uri uri, RemoteCallback remoteCallback) {
        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            final Bundle bundle = new Bundle();
            bundle.putString(ContentResolver.REMOTE_CALLBACK_RESULT, getType(uri));
            remoteCallback.sendResult(bundle);
        });
    }

    @Override
    public String getTypeAnonymous(Uri uri) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    /**
     * @hide
     */
    @SuppressWarnings("deprecation")
    public void getTypeAnonymousAsync(Uri uri, RemoteCallback remoteCallback) {
        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            final Bundle bundle = new Bundle();
            bundle.putString(ContentResolver.REMOTE_CALLBACK_RESULT, getTypeAnonymous(uri));
            remoteCallback.sendResult(bundle);
        });
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public boolean onCreate() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    /**
     * If you're reluctant to implement this manually, please just call super.bulkInsert().
     */
    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    /**
     * @hide
     */
    @Override
    public Bundle call(String method, String request, Bundle args) {
        throw new UnsupportedOperationException("unimplemented mock method call");
    }

    @Override
    public String[] getStreamTypes(Uri url, String mimeTypeFilter) {
        throw new UnsupportedOperationException("unimplemented mock method call");
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri url, String mimeType, Bundle opts) {
        throw new UnsupportedOperationException("unimplemented mock method call");
    }

    /**
     * @hide
     */
    @SuppressWarnings("deprecation")
    public void canonicalizeAsync(Uri uri, RemoteCallback callback) {
        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            final Bundle bundle = new Bundle();
            bundle.putParcelable(ContentResolver.REMOTE_CALLBACK_RESULT, canonicalize(uri));
            callback.sendResult(bundle);
        });
    }

    /**
     * @hide
     */
    @SuppressWarnings("deprecation")
    public void uncanonicalizeAsync(Uri uri, RemoteCallback callback) {
        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            final Bundle bundle = new Bundle();
            bundle.putParcelable(ContentResolver.REMOTE_CALLBACK_RESULT, uncanonicalize(uri));
            callback.sendResult(bundle);
        });
    }

    /**
     * @hide
     */
    public boolean refresh(Uri url, Bundle args) {
        throw new UnsupportedOperationException("unimplemented mock method call");
    }

    /** {@hide} */
    @Override
    public int checkUriPermission(@NonNull Uri uri, int uid, @Intent.AccessUriMode int modeFlags) {
        throw new UnsupportedOperationException("unimplemented mock method call");
    }

    /**
     * Returns IContentProvider which calls back same methods in this class.
     * By overriding this class, we avoid the mechanism hidden behind ContentProvider
     * (IPC, etc.)
     *
     * @hide
     */
    @Override
    public final IContentProvider getIContentProvider() {
        return mIContentProvider;
    }

    /**
     * @hide
     */
    public IBinder getIContentProviderBinder() {
        return new Binder();
    }

    /**
     * Like {@link #attachInfo(Context, android.content.pm.ProviderInfo)}, but for use
     * when directly instantiating the provider for testing.
     *
     * <p>Provided for use by {@code android.test.ProviderTestCase2} and
     * {@code android.test.RenamingDelegatingContext}.
     *
     * @deprecated Use a mocking framework like <a href="https://github.com/mockito/mockito">Mockito</a>.
     * New tests should be written using the
     * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
     */
    @Deprecated
    public static void attachInfoForTesting(
            ContentProvider provider, Context context, ProviderInfo providerInfo) {
        provider.attachInfoForTesting(context, providerInfo);
    }
}

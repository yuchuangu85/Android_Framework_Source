/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.content.pm.webapp;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.webapp.IWebAppInstallCallback;
import com.android.webapp.IWebAppQueryCallback;
import com.android.webapp.IWebAppService;
import com.android.webapp.flags.Flags;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;

/**
 * System service that manages the installation and querying of Web Apps.
 *
 * <p>This class acts as the entry point for managing Web Apps installed as Android apps.
 *
 * <p>To obtain an instance of this manager, use {@link Context#getSystemService(Class)}:
 *
 * <pre>{@code
 * WebAppManager webAppManager = context.getSystemService(WebAppManager.class);
 * }</pre>
 *
 * @see WebAppInstallRequest
 * @see WebAppQueryRequest
 */
@FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE_V2)
public final class WebAppManager {
    private static final String TAG = "WebAppManager";

    private final Context mContext;
    private Intent mBindIntent;

    /** {@link ExecutorService} to run blocking (e.g. AIDL) methods. */
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

    /**
     * Creates a {@link WebAppManager} object.
     *
     * @hide
     * @param context The context of the calling app.
     */
    WebAppManager(@NonNull Context context) {
        this.mContext = context;
    }

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private IWebAppService mWebAppService = null;

    @GuardedBy("mLock")
    private boolean mIsBinding = false;

    @GuardedBy("mLock")
    private final List<Runnable> mPendingRequests = new ArrayList<>();

    @GuardedBy("mLock")
    private int mActiveRequests = 0;

    private final ServiceConnection mConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mLock) {
                        mWebAppService = IWebAppService.Stub.asInterface(service);
                        mIsBinding = false;
                        // Drain the queue of pending requests
                        for (Runnable request : mPendingRequests) {
                            var ignored = mExecutorService.submit(request);
                        }
                        mPendingRequests.clear();
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    synchronized (mLock) {
                        mWebAppService = null;
                    }
                }
            };

    /** Resolves the intent to bind to the WebAppService system component. */
    @Nullable
    private Intent getBindIntent() {
        if (mBindIntent != null) {
            return mBindIntent;
        }

        Intent intent = new Intent(IWebAppService.class.getName());
        List<ResolveInfo> services =
                mContext.getPackageManager()
                        .queryIntentServices(intent, PackageManager.MATCH_SYSTEM_ONLY);

        if (services.isEmpty()) {
            return null;
        }

        ServiceInfo serviceInfo = null;
        for (ResolveInfo ri : services) {
            if (ri.serviceInfo == null) {
                continue;
            }
            if ((ri.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                if (serviceInfo != null) {
                    // Should not happen as we expect only one system app to handle this.
                    Log.wtf(TAG, "Multiple system apps handle " + intent);
                } else {
                    serviceInfo = ri.serviceInfo;
                }
            }
        }

        if (serviceInfo == null) {
            return null;
        }

        mBindIntent =
                new Intent(intent)
                        .setComponent(new ComponentName(serviceInfo.packageName, serviceInfo.name));
        return mBindIntent;
    }

    /**
     * Internal helper to handle connection logic and queuing.
     *
     * @param serviceTask The consumer to execute when the system is ready. It will always run on a
     *     background thread.
     * @param errorCallback The runnable to execute immediately if the system is unavailable.
     * @throws SecurityException If the caller doesn't have permission to call the service or the
     *     the service doesn't exist.
     */
    private void enqueueRequest(
            @NonNull Consumer<IWebAppService> serviceTask, @NonNull Runnable errorCallback) {
        Runnable taskToRun =
                () -> {
                    IWebAppService localWebAppService;
                    synchronized (mLock) {
                        localWebAppService = mWebAppService;
                    }
                    if (localWebAppService != null) {
                        serviceTask.accept(localWebAppService);
                    } else {
                        Log.e(TAG, "mWebAppService is null while executing request");
                        errorCallback.run();
                    }
                };
        synchronized (mLock) {
            if (mWebAppService != null) {
                var ignored = mExecutorService.submit(taskToRun);
                return;
            }

            // If service is not ready, add to queue
            mPendingRequests.add(taskToRun);

            // If already connecting, just wait (request is now in queue)
            if (mIsBinding) {
                return;
            }

            try {
                Intent bindIntent = getBindIntent();
                if (bindIntent != null) {
                    mIsBinding =
                            mContext.bindService(bindIntent, mConnection, Context.BIND_AUTO_CREATE);
                }
                if (!mIsBinding) {
                    Log.e(TAG, "The connection to WebAppService was not successful.");
                    // Binding failed immediately; remove the request we just added and fail.
                    mPendingRequests.remove(taskToRun);
                    var ignored = mExecutorService.submit(errorCallback);
                }
            } catch (SecurityException e) {
                mPendingRequests.remove(taskToRun);
                throw e;
            }
        }
    }

    private void startRequest() {
        synchronized (mLock) {
            mActiveRequests++;
        }
    }

    private void endRequest() {
        synchronized (mLock) {
            if (mActiveRequests > 0) {
                mActiveRequests--;
                if (mActiveRequests == 0) {
                    // TODO(b/448832874): Don't unbind immediately if the request comes from DPC, so
                    // that the service won't be bound and unbound frequently if many web apps are
                    // installed in the background sequentially.
                    unbindServiceLocked();
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void unbindServiceLocked() {
        if (mWebAppService != null || mIsBinding) {
            mContext.unbindService(mConnection);
            mWebAppService = null;
            mIsBinding = false;
        }
    }

    /**
     * Requests the installation of a Web App.
     *
     * <p>This operation is asynchronous. The request is processed by the system in the background,
     * and the result is delivered via the provided callback.
     *
     * @param request The {@link WebAppInstallRequest} containing the app details (such as title and
     *     manifest URL).
     * @param executor The {@link Executor} on which the callback will be invoked.
     * @param callback The callback to receive the installation result. The {@link String} is the
     *     package name of the installed web app. The int value is one of the result codes defined
     *     in {@link WebAppInstallRequest}. The {@link String} will be {@code null} if the result
     *     code is not {@link WebAppInstallRequest#RESULT_SUCCESS}.
     */
    public void install(
            @NonNull WebAppInstallRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ObjIntConsumer<String> callback) {
        // Track the number of active requests so that we can unbind from the service after all
        // requests are finished.
        startRequest();
        final ObjIntConsumer<String> wrappedCallback =
                (packageName, resultCode) -> {
                    try {
                        callback.accept(packageName, resultCode);
                    } finally {
                        endRequest();
                    }
                };

        try {
            enqueueRequest(
                    service -> {
                        try {
                            service.install(
                                    request.getTitle().toString(),
                                    request.getManifestUrl(),
                                    new IWebAppInstallCallback.Stub() {
                                        @Override
                                        @RequiresNoPermission
                                        public void onInstallResult(
                                                int resultCode, @Nullable String packageName) {
                                            executor.execute(
                                                    () ->
                                                            wrappedCallback.accept(
                                                                    packageName, resultCode));
                                        }
                                    });
                        } catch (RemoteException e) {
                            Log.e(TAG, "RemoteException while calling install() on service.", e);
                            executor.execute(
                                    () ->
                                            wrappedCallback.accept(
                                                    null,
                                                    WebAppInstallRequest.RESULT_INTERNAL_ERROR));
                        }
                    },
                    () ->
                            executor.execute(
                                    () ->
                                            wrappedCallback.accept(
                                                    null,
                                                    WebAppInstallRequest.RESULT_INTERNAL_ERROR)));
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while connecting to the service", e);
            executor.execute(
                    () ->
                            wrappedCallback.accept(
                                    null, WebAppInstallRequest.RESULT_PERMISSION_DENIED));
        }
    }

    /**
     * Queries the status of a specific Web App package.
     *
     * <p>This operation is asynchronous. The system checks if the package is installed by the Web
     * App service and visible to the caller, delivering the result via the provided callback.
     *
     * @param request The {@link WebAppQueryRequest} containing the package name.
     * @param executor The {@link Executor} on which the callback will be invoked.
     * @param callback The callback to receive the query result. The int value is one of the result
     *     codes defined in {@link WebAppQueryRequest}.
     */
    public void query(
            @NonNull WebAppQueryRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull IntConsumer callback) {
        // Track the number of active requests so that we can unbind from the service after all
        // requests are finished.
        startRequest();
        final IntConsumer wrappedCallback =
                resultCode -> {
                    try {
                        callback.accept(resultCode);
                    } finally {
                        endRequest();
                    }
                };

        try {
            enqueueRequest(
                    service -> {
                        try {
                            service.queryPackage(
                                    request.getPackageName(),
                                    new IWebAppQueryCallback.Stub() {
                                        @Override
                                        @RequiresNoPermission
                                        public void onQueryResult(int resultCode) {
                                            executor.execute(
                                                    () -> wrappedCallback.accept(resultCode));
                                        }
                                    });
                        } catch (RemoteException e) {
                            Log.e(
                                    TAG,
                                    "RemoteException while calling queryPackage() on service.",
                                    e);
                            executor.execute(
                                    () ->
                                            wrappedCallback.accept(
                                                    WebAppQueryRequest.RESULT_INTERNAL_ERROR));
                        }
                    },
                    () ->
                            executor.execute(
                                    () ->
                                            wrappedCallback.accept(
                                                    WebAppQueryRequest.RESULT_INTERNAL_ERROR)));
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while connecting to the service", e);
            executor.execute(
                    () -> wrappedCallback.accept(WebAppQueryRequest.RESULT_PERMISSION_DENIED));
        }
    }

    /**
     * Checks if the Web App service is available.
     *
     * @return {@code true} if the service is available, {@code false} otherwise.
     */
    public boolean isAvailable() {
        return Settings.Global.getInt(mContext.getContentResolver(), "enable_webapp_minter", 0)
                == 1;
    }
}

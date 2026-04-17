/*
 * Copyright 2019 The Android Open Source Project
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

package android.media;

import static android.media.RoutingChangeInfo.ENTRY_POINT_LOCAL_ROUTER_UNSPECIFIED;
import static android.media.RoutingChangeInfo.ENTRY_POINT_PROXY_ROUTER_UNSPECIFIED;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.media.flags.Flags.FLAG_ENABLE_GET_TRANSFERABLE_ROUTES;
import static com.android.media.flags.Flags.FLAG_ENABLE_PRIVILEGED_ROUTING_FOR_MEDIA_ROUTING_CONTROL;
import static com.android.media.flags.Flags.FLAG_ENABLE_ROUTE_VISIBILITY_CONTROL_API;
import static com.android.media.flags.Flags.FLAG_ENABLE_SUGGESTED_DEVICE_API;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.media.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * This API is not generally intended for third party application developers. Use the
 * <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/mediarouter/media/package-summary.html">Media Router
 * Library</a> for consistent behavior across all devices.
 *
 * <p>MediaRouter2 allows applications to control the routing of media channels and streams from
 * the current device to remote speakers and devices.
 */
// TODO(b/157873330): Add method names at the beginning of log messages. (e.g. selectRoute)
//       Not only MediaRouter2, but also to service / manager / provider.
// TODO: ensure thread-safe and document it
public final class MediaRouter2 {

    /**
     * The state of a router not requesting route scanning.
     *
     * @hide
     */
    public static final int SCANNING_STATE_NOT_SCANNING = 0;

    /**
     * The state of a router requesting scanning only while the user interacts with its owner app.
     *
     * <p>The device's screen must be on and the app must be in the foreground to trigger scanning
     * under this state.
     *
     * @hide
     */
    public static final int SCANNING_STATE_WHILE_INTERACTIVE = 1;

    /**
     * The state of a router requesting unrestricted scanning.
     *
     * <p>This state triggers scanning regardless of the restrictions required for {@link
     * #SCANNING_STATE_WHILE_INTERACTIVE}.
     *
     * <p>Routers requesting unrestricted scanning must hold {@link
     * Manifest.permission#MEDIA_ROUTING_CONTROL} or {@link
     * Manifest.permission#MEDIA_CONTENT_CONTROL}.
     *
     * @hide
     */
    public static final int SCANNING_STATE_SCANNING_FULL = 2;

    /** @hide */
    @IntDef(
            prefix = "SCANNING_STATE",
            value = {
                SCANNING_STATE_NOT_SCANNING,
                SCANNING_STATE_WHILE_INTERACTIVE,
                SCANNING_STATE_SCANNING_FULL
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanningState {}

    private static final String TAG = "MR2";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Object sSystemRouterLock = new Object();
    private static final Object sRouterLock = new Object();

    // The maximum time for the old routing controller available after transfer.
    private static final int TRANSFER_TIMEOUT_MS = 30_000;
    // The manager request ID representing that no manager is involved.
    private static final long MANAGER_REQUEST_ID_NONE = MediaRouter2Manager.REQUEST_ID_NONE;

    private record InstanceInvalidatedCallbackRecord(Executor executor, Runnable runnable) {}

    @GuardedBy("sSystemRouterLock")
    private static final Map<AppId, MediaRouter2> sAppToProxyRouterMap = new ArrayMap<>();

    @GuardedBy("sRouterLock")
    private static MediaRouter2 sInstance;

    private final Context mContext;
    private final Logger mLog;
    private final IMediaRouterService mMediaRouterService;
    private final Object mLock = new Object();
    private final MediaRouter2Impl mImpl;

    private final CopyOnWriteArrayList<RouteCallbackRecord> mRouteCallbackRecords =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RouteListingPreferenceCallbackRecord>
            mListingPreferenceCallbackRecords = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TransferCallbackRecord> mTransferCallbackRecords =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ControllerCallbackRecord> mControllerCallbackRecords =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DeviceSuggestionsUpdatesCallbackRecord>
            mDeviceSuggestionsUpdatesCallbackRecords = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<ControllerCreationRequest> mControllerCreationRequests =
            new CopyOnWriteArrayList<>();

    /**
     * Stores the latest copy of all routes received from the system server, without any filtering,
     * sorting, or deduplication.
     *
     * <p>Uses {@link MediaRoute2Info#getId()} to set each entry's key.
     */
    @GuardedBy("mLock")
    private final Map<String, MediaRoute2Info> mRoutes = new ArrayMap<>();

    /**
     * Stores the set of route permissions the target package is missing.
     *
     * <p>This is only used for proxy MediaRouter2 instances, where the manager can see the routes,
     * but the target app may not.
     *
     * <p>Clients can use {@link RouteCallback#onMissingPermissionsUpdated(Set)} to be notified of
     * changes.
     */
    @GuardedBy("mLock")
    private final Set<String> mMissingPermissions = new ArraySet<>();

    private final RoutingController mSystemController;

    @GuardedBy("mLock")
    private final Map<String, RoutingController> mNonSystemRoutingControllers = new ArrayMap<>();

    @GuardedBy("mLock")
    private int mScreenOffScanRequestCount = 0;

    @GuardedBy("mLock")
    private int mScreenOnScanRequestCount = 0;

    private final SparseArray<ScanRequest> mScanRequestsMap = new SparseArray<>();
    private final AtomicInteger mNextRequestId = new AtomicInteger(1);
    private final Handler mHandler;

    @GuardedBy("mLock")
    private RouteDiscoveryPreference mDiscoveryPreference = RouteDiscoveryPreference.EMPTY;

    // TODO: Make MediaRouter2 is always connected to the MediaRouterService.
    @GuardedBy("mLock")
    private MediaRouter2Stub mStub;

    @GuardedBy("mLock")
    @Nullable
    private RouteListingPreference mRouteListingPreference;

    @GuardedBy("mLock")
    @Nullable
    private Map<String, List<SuggestedDeviceInfo>> mSuggestedDeviceInfo = new HashMap<>();

    /**
     * Stores an auxiliary copy of {@link #mFilteredRoutes} at the time of the last route callback
     * dispatch. This is only used to determine what callback a route should be assigned to (added,
     * removed, changed) in {@link #dispatchFilteredRoutesUpdatedOnHandler(List)}.
     */
    private volatile ArrayMap<String, MediaRoute2Info> mPreviousFilteredRoutes = new ArrayMap<>();

    private final Map<String, MediaRoute2Info> mPreviousUnfilteredRoutes = new ArrayMap<>();

    /**
     * Stores the latest copy of exposed routes after filtering, sorting, and deduplication. Can be
     * accessed through {@link #getRoutes()}.
     *
     * <p>This list is a copy of {@link #mRoutes} which has undergone filtering, sorting, and
     * deduplication using criteria in {@link #mDiscoveryPreference}.
     *
     * @see #filterRoutesWithCompositePreferenceLocked(List)
     */
    private volatile List<MediaRoute2Info> mFilteredRoutes = Collections.emptyList();
    private volatile OnGetControllerHintsListener mOnGetControllerHintsListener;

    /** Gets an instance of the media router associated with the context. */
    @NonNull
    public static MediaRouter2 getInstance(@NonNull Context context) {
        Objects.requireNonNull(context, "context must not be null");
        synchronized (sRouterLock) {
            if (sInstance == null) {
                sInstance = new MediaRouter2(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    /**
     * Returns a proxy MediaRouter2 instance that allows you to control the routing of an app
     * specified by {@code clientPackageName}. Returns {@code null} if the specified package name
     * does not exist.
     *
     * <p>Proxy MediaRouter2 instances operate differently than regular MediaRouter2 instances:
     *
     * <ul>
     *   <li>
     *       <p>{@link #registerRouteCallback} ignores any {@link RouteDiscoveryPreference discovery
     *       preference} passed by a proxy router. Use {@link RouteDiscoveryPreference#EMPTY} when
     *       setting a route callback.
     *   <li>
     *       <p>Methods returning non-system {@link RoutingController controllers} always return new
     *       instances with the latest data. Do not attempt to compare or store them. Instead, use
     *       {@link #getController(String)} or {@link #getControllers()} to query the most
     *       up-to-date state.
     *   <li>
     *       <p>Calls to {@link #setOnGetControllerHintsListener} are ignored.
     * </ul>
     *
     * <p>Callers that only hold the revocable form of {@link
     * Manifest.permission#MEDIA_ROUTING_CONTROL} must use {@link #getInstance(Context, String,
     * Executor, Runnable)} instead of this method.
     *
     * @param clientPackageName the package name of the app to control
     * @return a proxy MediaRouter2 instance if {@code clientPackageName} exists or {@code null}.
     * @throws IllegalStateException if the caller only holds a revocable version of {@link
     *     Manifest.permission#MEDIA_ROUTING_CONTROL}.
     * @hide
     */
    @SuppressWarnings("RequiresPermission")
    @RequiresPermission(
            anyOf = {
                Manifest.permission.MEDIA_CONTENT_CONTROL,
                Manifest.permission.MEDIA_ROUTING_CONTROL
            })
    @SystemApi
    @Nullable
    public static MediaRouter2 getInstance(
            @NonNull Context context, @NonNull String clientPackageName) {
        // Capturing the IAE here to not break nullability.
        try {
            return findOrCreateProxyInstanceForCallingUser(
                    context,
                    clientPackageName,
                    context.getUser(),
                    /* executor */ null,
                    /* onInstanceInvalidatedListener */ null);
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "Failed to create proxy router for package '" + clientPackageName + "'", ex);
            return null;
        }
    }

    /**
     * Returns a proxy MediaRouter2 instance that allows you to control the routing of an app
     * specified by {@code clientPackageName}.
     *
     * <p>Proxy MediaRouter2 instances operate differently than regular MediaRouter2 instances:
     *
     * <ul>
     *   <li>
     *       <p>{@link #registerRouteCallback} ignores any {@link RouteDiscoveryPreference discovery
     *       preference} passed by a proxy router. Use {@link RouteDiscoveryPreference#EMPTY} when
     *       setting a route callback.
     *   <li>
     *       <p>Methods returning non-system {@link RoutingController controllers} always return new
     *       instances with the latest data. Do not attempt to compare or store them. Instead, use
     *       {@link #getController(String)} or {@link #getControllers()} to query the most
     *       up-to-date state.
     *   <li>
     *       <p>Calls to {@link #setOnGetControllerHintsListener} are ignored.
     * </ul>
     *
     * <p>Use this method when you only hold a revocable version of {@link
     * Manifest.permission#MEDIA_ROUTING_CONTROL} (e.g. acquired via the {@link AppOpsManager}).
     * Otherwise, use {@link #getInstance(Context, String)}.
     *
     * <p>{@code onInstanceInvalidatedListener} is called when the instance is invalidated because
     * the calling app has lost {@link Manifest.permission#MEDIA_ROUTING_CONTROL} and does not hold
     * {@link Manifest.permission#MEDIA_CONTENT_CONTROL}. Do not use the invalidated instance after
     * receiving this callback, as the system will ignore all operations. Call {@link
     * #getInstance(Context, String, Executor, Runnable)} again after reacquiring the relevant
     * permissions.
     *
     * @param context The {@link Context} of the caller.
     * @param clientPackageName The package name of the app you want to control the routing of.
     * @param executor The {@link Executor} on which to invoke {@code
     *     onInstanceInvalidatedListener}.
     * @param onInstanceInvalidatedListener Callback for when the {@link MediaRouter2} instance is
     *     invalidated due to lost permissions.
     * @throws IllegalArgumentException if {@code clientPackageName} does not exist in the calling
     *     user.
     */
    @SuppressWarnings("RequiresPermission")
    @FlaggedApi(FLAG_ENABLE_PRIVILEGED_ROUTING_FOR_MEDIA_ROUTING_CONTROL)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.MEDIA_CONTENT_CONTROL,
                Manifest.permission.MEDIA_ROUTING_CONTROL
            })
    @NonNull
    public static MediaRouter2 getInstance(
            @NonNull Context context,
            @NonNull String clientPackageName,
            @NonNull Executor executor,
            @NonNull Runnable onInstanceInvalidatedListener) {
        Objects.requireNonNull(executor, "Executor must not be null");
        Objects.requireNonNull(
                onInstanceInvalidatedListener, "onInstanceInvalidatedListener must not be null.");

        return findOrCreateProxyInstanceForCallingUser(
                context,
                clientPackageName,
                context.getUser(),
                executor,
                onInstanceInvalidatedListener);
    }

    /**
     * Returns a proxy MediaRouter2 instance that allows you to control the routing of an app
     * specified by {@code clientPackageName} and {@code user}.
     *
     * <p>Proxy MediaRouter2 instances operate differently than regular MediaRouter2 instances:
     *
     * <ul>
     *   <li>
     *       <p>{@link #registerRouteCallback} ignores any {@link RouteDiscoveryPreference discovery
     *       preference} passed by a proxy router. Use a {@link RouteDiscoveryPreference} with empty
     *       {@link RouteDiscoveryPreference.Builder#setPreferredFeatures(List) preferred features}
     *       when setting a route callback.
     *   <li>
     *       <p>Methods returning non-system {@link RoutingController controllers} always return new
     *       instances with the latest data. Do not attempt to compare or store them. Instead, use
     *       {@link #getController(String)} or {@link #getControllers()} to query the most
     *       up-to-date state.
     *   <li>
     *       <p>Calls to {@link #setOnGetControllerHintsListener} are ignored.
     * </ul>
     *
     * @param context The {@link Context} of the caller.
     * @param clientPackageName The package name of the app you want to control the routing of.
     * @param user The {@link UserHandle} of the user running the app for which to get the proxy
     *     router instance. Must match {@link Process#myUserHandle()} if the caller doesn't hold
     *     {@code Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     * @throws SecurityException if {@code user} does not match {@link Process#myUserHandle()} and
     *     the caller does not hold {@code Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     * @throws IllegalArgumentException if {@code clientPackageName} does not exist in {@code user}.
     * @hide
     */
    @RequiresPermission(
            anyOf = {
                Manifest.permission.MEDIA_CONTENT_CONTROL,
                Manifest.permission.MEDIA_ROUTING_CONTROL
            })
    @NonNull
    public static MediaRouter2 getInstance(
            @NonNull Context context, @NonNull String clientPackageName, @NonNull UserHandle user) {
        return findOrCreateProxyInstanceForCallingUser(
                context,
                clientPackageName,
                user,
                /* executor */ null,
                /* onInstanceInvalidatedListener */ null);
    }

    /**
     * Returns the per-process singleton proxy router instance for the {@code clientPackageName} and
     * {@code user} if it exists, or otherwise it creates the appropriate instance.
     *
     * <p>If no instance has been created previously, the method will create an instance via {@link
     * #MediaRouter2(Context, Looper, String, UserHandle)}.
     */
    @NonNull
    private static MediaRouter2 findOrCreateProxyInstanceForCallingUser(
            Context context,
            String clientPackageName,
            UserHandle user,
            @Nullable Executor executor,
            @Nullable Runnable onInstanceInvalidatedListener) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(user, "user must not be null");

        if (TextUtils.isEmpty(clientPackageName)) {
            throw new IllegalArgumentException("clientPackageName must not be null or empty");
        }

        if (executor == null || onInstanceInvalidatedListener == null) {
            if (checkCallerHasOnlyRevocablePermissions(context)) {
                throw new IllegalStateException(
                        "Use getInstance(Context, String, Executor, Runnable) to obtain a proxy"
                                + " MediaRouter2 instance.");
            }
        }

        AppId key = new AppId(clientPackageName, user);

        synchronized (sSystemRouterLock) {
            MediaRouter2 instance = sAppToProxyRouterMap.get(key);
            if (instance == null) {
                instance =
                        new MediaRouter2(context, Looper.getMainLooper(), clientPackageName, user);
                // Register proxy router after instantiation to avoid race condition.
                ((ProxyMediaRouter2Impl) instance.mImpl).registerProxyRouter();
                sAppToProxyRouterMap.put(key, instance);
            }
            ((ProxyMediaRouter2Impl) instance.mImpl)
                    .registerInstanceInvalidatedCallback(executor, onInstanceInvalidatedListener);
            return instance;
        }
    }

    private static boolean checkCallerHasOnlyRevocablePermissions(@NonNull Context context) {
        boolean hasMediaContentControl =
                context.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
                        == PackageManager.PERMISSION_GRANTED;
        boolean hasRegularMediaRoutingControl =
                context.checkSelfPermission(Manifest.permission.MEDIA_ROUTING_CONTROL)
                        == PackageManager.PERMISSION_GRANTED;
        AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        boolean hasAppOpMediaRoutingControl =
                appOpsManager.unsafeCheckOp(
                                AppOpsManager.OPSTR_MEDIA_ROUTING_CONTROL,
                                context.getApplicationInfo().uid,
                                context.getOpPackageName())
                        == AppOpsManager.MODE_ALLOWED;

        return !hasMediaContentControl
                && !hasRegularMediaRoutingControl
                && hasAppOpMediaRoutingControl;
    }

    /**
     * Starts scanning remote routes.
     *
     * <p>Route discovery can happen even when the {@link #startScan()} is not called. This is
     * because the scanning could be started before by other apps. Therefore, calling this method
     * after calling {@link #stopScan()} does not necessarily mean that the routes found before are
     * removed and added again.
     *
     * <p>Use {@link RouteCallback} to get the route related events.
     *
     * <p>Note that calling start/stopScan is applied to all system routers in the same process.
     *
     * <p>This will be no-op for non-system media routers.
     *
     * @see #stopScan()
     * @see #getInstance(Context, String)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void startScan() {
        mImpl.startScan();
    }

    /**
     * Stops scanning remote routes to reduce resource consumption.
     *
     * <p>Route discovery can be continued even after this method is called. This is because the
     * scanning is only turned off when all the apps stop scanning. Therefore, calling this method
     * does not necessarily mean the routes are removed. Also, for the same reason it does not mean
     * that {@link RouteCallback#onRoutesAdded(List)} is not called afterwards.
     *
     * <p>Use {@link RouteCallback} to get the route related events.
     *
     * <p>Note that calling start/stopScan is applied to all system routers in the same process.
     *
     * <p>This will be no-op for non-system media routers.
     *
     * @see #startScan()
     * @see #getInstance(Context, String)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void stopScan() {
        mImpl.stopScan();
    }

    /**
     * Requests the system to actively scan for routes based on the router's {@link
     * RouteDiscoveryPreference route discovery preference}.
     *
     * <p>You must call {@link #cancelScanRequest(ScanToken)} promptly to preserve system resources
     * like battery. Avoid scanning unless there is clear intention from the user to start routing
     * their media.
     *
     * <p>{@code scanRequest} specifies relevant scanning options, like whether the system should
     * scan with the screen off. Screen off scanning requires {@link
     * Manifest.permission#MEDIA_ROUTING_CONTROL} or {@link
     * Manifest.permission#MEDIA_CONTENT_CONTROL}.
     *
     * <p>Proxy routers use the registered {@link RouteDiscoveryPreference} of their target routers.
     *
     * @return A unique {@link ScanToken} that identifies the scan request.
     * @throws SecurityException If a {@link ScanRequest} with {@link
     *     ScanRequest.Builder#setScreenOffScan} true is passed, while not holding {@link
     *     Manifest.permission#MEDIA_ROUTING_CONTROL} or {@link
     *     Manifest.permission#MEDIA_CONTENT_CONTROL}.
     */
    @NonNull
    public ScanToken requestScan(@NonNull ScanRequest scanRequest) {
        Objects.requireNonNull(scanRequest, "scanRequest must not be null.");
        ScanToken token = new ScanToken(mNextRequestId.getAndIncrement());

        synchronized (mLock) {
            boolean shouldUpdate =
                    mScreenOffScanRequestCount == 0
                            && (scanRequest.isScreenOffScan() || mScreenOnScanRequestCount == 0);

            if (shouldUpdate) {
                try {
                    mImpl.updateScanningState(
                            scanRequest.isScreenOffScan()
                                    ? SCANNING_STATE_SCANNING_FULL
                                    : SCANNING_STATE_WHILE_INTERACTIVE);

                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
            }

            if (scanRequest.isScreenOffScan()) {
                mScreenOffScanRequestCount++;
            } else {
                mScreenOnScanRequestCount++;
            }

            mScanRequestsMap.put(token.mId, scanRequest);
            return token;
        }
    }

    /**
     * Releases the active scan request linked to the provided {@link ScanToken}.
     *
     * @see #requestScan(ScanRequest)
     * @param token {@link ScanToken} of the {@link ScanRequest} to release.
     * @throws IllegalArgumentException if the token does not match any active scan request.
     */
    public void cancelScanRequest(@NonNull ScanToken token) {
        Objects.requireNonNull(token, "token must not be null");

        synchronized (mLock) {
            ScanRequest request = mScanRequestsMap.get(token.mId);

            if (request == null) {
                throw new IllegalArgumentException(
                        "The token does not match any active scan request");
            }

            boolean shouldUpdate =
                    request.isScreenOffScan()
                            ? mScreenOffScanRequestCount == 1
                            : mScreenOnScanRequestCount == 1 && mScreenOffScanRequestCount == 0;

            if (shouldUpdate) {
                try {
                    if (!request.isScreenOffScan() || mScreenOnScanRequestCount == 0) {
                        mImpl.updateScanningState(SCANNING_STATE_NOT_SCANNING);
                    } else {
                        mImpl.updateScanningState(SCANNING_STATE_WHILE_INTERACTIVE);
                    }

                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }
            }

            if (request.isScreenOffScan()) {
                mScreenOffScanRequestCount--;
            } else {
                mScreenOnScanRequestCount--;
            }

            mScanRequestsMap.remove(token.mId);
        }
    }

    private MediaRouter2(Context appContext) {
        mContext = appContext;
        mLog = new Logger("local");
        mHandler = new Handler(Looper.getMainLooper());
        mMediaRouterService =
                IMediaRouterService.Stub.asInterface(
                        ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));
        mImpl = new LocalMediaRouter2Impl(mContext.getPackageName());

        loadSystemRoutes(/* isProxyRouter */ false);

        RoutingSessionInfo currentSystemSessionInfo = mImpl.getSystemSessionInfo();
        if (currentSystemSessionInfo == null) {
            throw new RuntimeException("Null currentSystemSessionInfo. Something is wrong.");
        }

        mSystemController = new SystemRoutingController(currentSystemSessionInfo);
    }

    private MediaRouter2(
            Context context, Looper looper, String clientPackageName, UserHandle user) {
        mContext = context;
        mLog = new Logger(TextUtils.formatSimple("proxy-%s", clientPackageName));
        mHandler = new Handler(looper);
        mMediaRouterService =
                IMediaRouterService.Stub.asInterface(
                        ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));

        loadSystemRoutes(/* isProxyRouter */ true);

        mSystemController =
                new SystemRoutingController(
                        ProxyMediaRouter2Impl.getSystemSessionInfoImpl(
                                mMediaRouterService, mContext.getPackageName(), clientPackageName));

        mImpl = new ProxyMediaRouter2Impl(context, clientPackageName, user);
    }

    @GuardedBy("mLock")
    private void loadSystemRoutes(boolean isProxyRouter) {
        List<MediaRoute2Info> currentSystemRoutes = null;
        try {
            currentSystemRoutes = mMediaRouterService.getSystemRoutes(mContext.getPackageName(),
                    isProxyRouter);
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }

        if (currentSystemRoutes == null || currentSystemRoutes.isEmpty()) {
            throw new RuntimeException("Null or empty currentSystemRoutes. Something is wrong.");
        }

        for (MediaRoute2Info route : currentSystemRoutes) {
            mRoutes.put(route.getId(), route);
        }
    }

    /**
     * Gets the client package name of the app which this media router controls.
     *
     * <p>This will return null for non-system media routers.
     *
     * @see #getInstance(Context, String)
     * @hide
     */
    @SystemApi
    @Nullable
    public String getClientPackageName() {
        return mImpl.getClientPackageName();
    }

    /**
     * Registers a callback to discover routes and to receive events when they change.
     *
     * <p>Clients can register multiple callbacks, as long as the {@link RouteCallback} instances
     * are different. Each callback can provide a unique {@link RouteDiscoveryPreference preference}
     * and will only receive updates related to that set preference.
     *
     * <p>If the specified callback is already registered, its registration will be updated for the
     * given {@link Executor executor} and {@link RouteDiscoveryPreference discovery preference}.
     *
     * <p>{@link #getInstance(Context) Local routers} must register a route callback to register in
     * the system and start receiving updates. Otherwise, all operations will be no-ops.
     *
     * <p>Any discovery preference passed by a {@link #getInstance(Context, String) proxy router}
     * will be ignored and will receive route updates based on the preference set by its matching
     * local router.
     */
    public void registerRouteCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull RouteCallback routeCallback,
            @NonNull RouteDiscoveryPreference preference) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(routeCallback, "callback must not be null");
        Objects.requireNonNull(preference, "preference must not be null");

        RouteCallbackRecord record =
                mImpl.createRouteCallbackRecord(executor, routeCallback, preference);

        mRouteCallbackRecords.remove(record);
        // It can fail to add the callback record if another registration with the same callback
        // is happening but it's okay because either this or the other registration should be done.
        mRouteCallbackRecords.addIfAbsent(record);

        mImpl.registerRouteCallback();
    }

    /**
     * Unregisters the given callback. The callback will no longer receive events. If the callback
     * has not been added or been removed already, it is ignored.
     *
     * @param routeCallback the callback to unregister
     * @see #registerRouteCallback
     */
    public void unregisterRouteCallback(@NonNull RouteCallback routeCallback) {
        Objects.requireNonNull(routeCallback, "callback must not be null");

        if (!mRouteCallbackRecords.remove(new RouteCallbackRecord(null, routeCallback, null))) {
            mLog.w("unregisterRouteCallback: Ignoring unknown callback");
            return;
        }

        mImpl.unregisterRouteCallback();
    }

    /**
     * Registers the given callback to be invoked when the {@link RouteListingPreference} of the
     * target router changes.
     *
     * <p>Calls using a previously registered callback will overwrite the previous executor.
     *
     * @see #setRouteListingPreference(RouteListingPreference)
     */
    public void registerRouteListingPreferenceUpdatedCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<RouteListingPreference> routeListingPreferenceCallback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(routeListingPreferenceCallback, "callback must not be null");

        RouteListingPreferenceCallbackRecord record =
                new RouteListingPreferenceCallbackRecord(executor, routeListingPreferenceCallback);

        mListingPreferenceCallbackRecords.remove(record);
        mListingPreferenceCallbackRecords.add(record);
    }

    /**
     * Registers the given callback to be invoked when the {@link SuggestedDeviceInfo} of the target
     * router changes.
     *
     * <p>Calls using a previously registered callback will overwrite the previous executor.
     */
    @FlaggedApi(FLAG_ENABLE_SUGGESTED_DEVICE_API)
    public void registerDeviceSuggestionsUpdatesCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull DeviceSuggestionsUpdatesCallback deviceSuggestionsUpdatesCallback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(deviceSuggestionsUpdatesCallback, "callback must not be null");

        DeviceSuggestionsUpdatesCallbackRecord record =
                new DeviceSuggestionsUpdatesCallbackRecord(
                        executor, deviceSuggestionsUpdatesCallback);

        mDeviceSuggestionsUpdatesCallbackRecords.remove(record);
        mDeviceSuggestionsUpdatesCallbackRecords.add(record);
    }

    /**
     * Unregisters the given callback to not receive {@link RouteListingPreference} change events.
     *
     * @see #registerRouteListingPreferenceUpdatedCallback(Executor, Consumer)
     */
    public void unregisterRouteListingPreferenceUpdatedCallback(
            @NonNull Consumer<RouteListingPreference> callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mListingPreferenceCallbackRecords.remove(
                new RouteListingPreferenceCallbackRecord(/* executor */ null, callback))) {
            mLog.w(
                    "unregisterRouteListingPreferenceUpdatedCallback: Ignoring an unknown"
                            + " callback");
        }
    }

    /**
     * Unregisters the given callback to not receive {@link SuggestedDeviceInfo} change events.
     *
     * @see #registerDeviceSuggestionsUpdatesCallback(Executor, DeviceSuggestionsUpdatesCallback)
     */
    @FlaggedApi(FLAG_ENABLE_SUGGESTED_DEVICE_API)
    public void unregisterDeviceSuggestionsUpdatesCallback(
            @NonNull DeviceSuggestionsUpdatesCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mDeviceSuggestionsUpdatesCallbackRecords.remove(
                new DeviceSuggestionsUpdatesCallbackRecord(/* executor */ null, callback))) {
            mLog.w("unregisterDeviceSuggestionsUpdatesCallback: Ignoring an unknown" + " callback");
        }
    }

    /**
     * Shows the system output switcher dialog.
     *
     * <p>Should only be called when the context of MediaRouter2 is in the foreground and visible on
     * the screen.
     *
     * <p>The appearance and precise behaviour of the system output switcher dialog may vary across
     * different devices, OS versions, and form factors, but the basic functionality stays the same.
     *
     * <p>See <a
     * href="https://developer.android.com/guide/topics/media/media-routing#output-switcher">Output
     * Switcher documentation</a> for more details.
     *
     * @return {@code true} if the output switcher dialog is being shown, or {@code false} if the
     * call is ignored because the app is in the background.
     */
    public boolean showSystemOutputSwitcher() {
        return mImpl.showSystemOutputSwitcher(null);
    }

    /**
     * Shows the system output switcher dialog for a specific {@link MediaSession}.
     *
     * <p>The {@link MediaSession.Token} argument allows the system to identify which
     * {@link RoutingSessionInfo routing session} to display as active. This is done via
     * {@link MediaSession#setPlaybackToRemote} and {@link VolumeProvider#getVolumeControlId} when
     * playing remotely, and {@link MediaSession#setPlaybackToLocal} when playing on this device.
     *
     * <p>This method is preferred over {@link #showSystemOutputSwitcher()} to prevent the system
     * from making wrong inferences about where playback is happening.
     *
     * @return {@code true} if the output switcher dialog is being shown, or {@code false} if the
     * call is ignored because the app is in the background.
     *
     * @throws IllegalArgumentException if the provided {@code sessionToken} is invalid.
     */
    @FlaggedApi(FLAG_ENABLE_ROUTE_VISIBILITY_CONTROL_API)
    public boolean showSystemOutputSwitcher(@NonNull MediaSession.Token sessionToken) {
        Objects.requireNonNull(sessionToken, "sessionToken must not be null");
        return mImpl.showSystemOutputSwitcher(sessionToken);
    }

    /**
     * Sets the {@link RouteListingPreference} of the app associated to this media router.
     *
     * <p>Use this method to inform the system UI of the routes that you would like to list for
     * media routing, via the Output Switcher.
     *
     * <p>You should call this method before {@link #registerRouteCallback registering any route
     * callbacks} and immediately after receiving any {@link RouteCallback#onRoutesUpdated route
     * updates} in order to keep the system UI in a consistent state. You can also call this method
     * at any other point to update the listing preference dynamically.
     *
     * <p>Calling this method on a proxy router instance will throw an {@link
     * UnsupportedOperationException}.
     *
     * <p>Notes:
     *
     * <ol>
     *   <li>You should not include the ids of two or more routes with a match in their {@link
     *       MediaRoute2Info#getDeduplicationIds() deduplication ids}. If you do, the system will
     *       deduplicate them using its own criteria.
     *   <li>You can use this method to rank routes in the output switcher, placing the more
     *       important routes first. The system might override the proposed ranking.
     *   <li>You can use this method to avoid listing routes using dynamic criteria. For example,
     *       you can limit access to a specific type of device according to runtime criteria.
     * </ol>
     *
     * @param routeListingPreference The {@link RouteListingPreference} for the system to use for
     *     route listing. When null, the system uses its default listing criteria.
     */
    public void setRouteListingPreference(@Nullable RouteListingPreference routeListingPreference) {
        mImpl.setRouteListingPreference(routeListingPreference);
    }

    /**
     * Sets a list of {@link SuggestedDeviceInfo device suggestions}.
     *
     * <p>Use this method to tell the system about device suggestions for the current router. The
     * system may use these suggestions to populate system UI such as Output Switcher and media
     * controls.
     *
     * <p>Suggestions will be expired automatically by the system, at which point you should call
     * this method again if the suggestion is still relevant and should still be surfaced.
     *
     * @param suggestedDeviceInfo The {@link SuggestedDeviceInfo} the router suggests should be
     *     provided to the user.
     */
    @FlaggedApi(FLAG_ENABLE_SUGGESTED_DEVICE_API)
    public void setDeviceSuggestions(@NonNull List<SuggestedDeviceInfo> suggestedDeviceInfo) {
        mImpl.setDeviceSuggestions(suggestedDeviceInfo);
    }

    /**
     * Clears the {@link SuggestedDeviceInfo device suggestions} provided by this router.
     *
     * <p>Use this method to tell the system that this router is not currently providing any
     * suggestions.
     *
     * <p>If the router explicitly wants to recommend that no suggestion is appropriate, use {@link
     * setDeviceSuggestions} with an empty list
     */
    @FlaggedApi(FLAG_ENABLE_SUGGESTED_DEVICE_API)
    public void clearDeviceSuggestions() {
        mImpl.setDeviceSuggestions(null);
    }

    /**
     * Gets the current suggested devices.
     *
     * @return the suggested devices, keyed by the package name providing each suggestion list.
     */
    @FlaggedApi(FLAG_ENABLE_SUGGESTED_DEVICE_API)
    @NonNull
    public Map<String, List<SuggestedDeviceInfo>> getDeviceSuggestions() {
        return mImpl.getDeviceSuggestions();
    }

    /**
     * Notifies other suggestion providers that a suggestion has been requested. Calling this method
     * on local routers is a no-op.
     *
     * @hide
     */
    @FlaggedApi(FLAG_ENABLE_SUGGESTED_DEVICE_API)
    public void notifyDeviceSuggestionRequested() {
        mImpl.notifyDeviceSuggestionRequested();
    }

    /**
     * Returns the current {@link RouteListingPreference} of the target router.
     *
     * <p>If this instance was created using {@code #getInstance(Context, String)}, then it returns
     * the last {@link RouteListingPreference} set by the process this router was created for.
     *
     * @see #setRouteListingPreference(RouteListingPreference)
     */
    @Nullable
    public RouteListingPreference getRouteListingPreference() {
        synchronized (mLock) {
            return mRouteListingPreference;
        }
    }

    @GuardedBy("mLock")
    private boolean updateDiscoveryPreferenceIfNeededLocked() {
        RouteDiscoveryPreference newDiscoveryPreference = new RouteDiscoveryPreference.Builder(
                mRouteCallbackRecords.stream().map(record -> record.mPreference).collect(
                        Collectors.toList())).build();

        if (Objects.equals(mDiscoveryPreference, newDiscoveryPreference)) {
            return false;
        }
        mDiscoveryPreference = newDiscoveryPreference;
        updateFilteredRoutesLocked();
        if (Flags.enableRouteVisibilityControlCompatFixes()) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::dispatchFilteredRoutesUpdatedOnHandler,
                            this,
                            mFilteredRoutes));
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::dispatchControllerUpdatedIfNeededOnHandler,
                            this,
                            new HashMap<>(mRoutes)));
        }
        return true;
    }

    /**
     * Gets the list of all discovered routes. This list includes the routes that are not related to
     * the client app.
     *
     * <p>This will return an empty list for non-system media routers.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public List<MediaRoute2Info> getAllRoutes() {
        return mImpl.getAllRoutes();
    }

    /**
     * Gets the unmodifiable list of {@link MediaRoute2Info routes} currently known to the media
     * router.
     *
     * <p>Please note that the list can be changed before callbacks are invoked.
     *
     * <p>This method returns routes available to the routing framework that contain at least one of
     * the {@link RouteDiscoveryPreference#getPreferredFeatures() route features} registered by the
     * application through {@link #registerRouteCallback}. As a result, you need to register at
     * least one {@link RouteCallback} (associated with the features that you are interested in), in
     * order for this method to return a non-empty list.
     *
     * @return the list of routes that contains at least one of the route features in discovery
     *     preferences registered by the application
     */
    @NonNull
    public List<MediaRoute2Info> getRoutes() {
        synchronized (mLock) {
            return mFilteredRoutes;
        }
    }

    /**
     * Get permissions that guard routes, and the target app has requested but was not granted.
     *
     * <p>This is only used for proxy MediaRouter2 instances, where the manager can see the
     * routes, but the target app may not.
     * @hide
     */
    @NonNull
    public Set<String> getMissingPermissions() {
        synchronized (mLock) {
            return new ArraySet<>(mMissingPermissions);
        }
    }

    /**
     * Registers a callback to get the result of {@link #transferTo(MediaRoute2Info)}.
     * If you register the same callback twice or more, it will be ignored.
     *
     * @param executor the executor to execute the callback on
     * @param callback the callback to register
     * @see #unregisterTransferCallback
     */
    public void registerTransferCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull TransferCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        TransferCallbackRecord record = new TransferCallbackRecord(executor, callback);
        if (!mTransferCallbackRecords.addIfAbsent(record)) {
            mLog.w("registerTransferCallback: Ignoring the same callback");
        }
    }

    /**
     * Unregisters the given callback. The callback will no longer receive events.
     * If the callback has not been added or been removed already, it is ignored.
     *
     * @param callback the callback to unregister
     * @see #registerTransferCallback
     */
    public void unregisterTransferCallback(@NonNull TransferCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mTransferCallbackRecords.remove(new TransferCallbackRecord(null, callback))) {
            mLog.w("unregisterTransferCallback: Ignoring an unknown callback");
        }
    }

    /**
     * Registers a {@link ControllerCallback}. If you register the same callback twice or more, it
     * will be ignored.
     *
     * @see #unregisterControllerCallback(ControllerCallback)
     */
    public void registerControllerCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull ControllerCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        ControllerCallbackRecord record = new ControllerCallbackRecord(executor, callback);
        if (!mControllerCallbackRecords.addIfAbsent(record)) {
            mLog.w("registerControllerCallback: Ignoring the same callback");
        }
    }

    /**
     * Unregisters a {@link ControllerCallback}. The callback will no longer receive events.
     * If the callback has not been added or been removed already, it is ignored.
     *
     * @see #registerControllerCallback(Executor, ControllerCallback)
     */
    public void unregisterControllerCallback(@NonNull ControllerCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mControllerCallbackRecords.remove(new ControllerCallbackRecord(null, callback))) {
            mLog.w("unregisterControllerCallback: Ignoring an unknown callback");
        }
    }

    /**
     * Registers a {@link SystemSessionOverridesListener}.
     *
     * <p>Passing the same listener to this method twice updates the associated {@code executor} but
     * does not register the same callback twice.
     *
     * @param executor the executor to execute the listener on
     * @param listener the {@link SystemSessionOverridesListener} to register
     * @throws UnsupportedOperationException if this method is called on a non-proxy instance
     *     (instances created using {@link #getInstance(Context)})
     * @hide
     */
    public void registerSystemSessionOverridesListener(
            @NonNull Executor executor, @NonNull SystemSessionOverridesListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        mImpl.registerSystemSessionOverridesListener(executor, listener);
    }

    /**
     * Unregisters the given {@link SystemSessionOverridesListener}.
     *
     * @param listener the {@link SystemSessionOverridesListener} to unregister
     * @throws UnsupportedOperationException if this method is called on a non-proxy instance
     *     (instances created using {@link #getInstance(Context)})
     * @hide
     */
    public void unregisterSystemSessionOverridesListener(
            @NonNull SystemSessionOverridesListener listener) {
        mImpl.unregisterSystemSessionOverridesListener(listener);
    }

    /**
     * Sets an {@link OnGetControllerHintsListener} to send hints when creating a
     * {@link RoutingController}. To send the hints, listener should be set <em>BEFORE</em> calling
     * {@link #transferTo(MediaRoute2Info)}.
     *
     * @param listener A listener to send optional app-specific hints when creating a controller.
     *     {@code null} for unset.
     */
    public void setOnGetControllerHintsListener(@Nullable OnGetControllerHintsListener listener) {
        mImpl.setOnGetControllerHintsListener(listener);
    }

    /**
     * Transfers the current media to the given route. If it's necessary a new
     * {@link RoutingController} is created or it is handled within the current routing controller.
     *
     * @param route the route you want to transfer the current media to.
     * @see TransferCallback#onTransfer
     * @see TransferCallback#onTransferFailure
     */
    public void transferTo(@NonNull MediaRoute2Info route) {
        mImpl.transferTo(route, /* routingChangeInfo= */ null);
    }

    /**
     * Transfers the current media to the given route. If it's necessary a new {@link
     * RoutingController} is created or it is handled within the current routing controller.
     *
     * @param route the route you want to transfer the current media to.
     * @param routingChangeInfo information about the start of the media routing session. See {@link
     *     RoutingChangeInfo}
     * @see TransferCallback#onTransfer
     * @see TransferCallback#onTransferFailure
     * @hide
     */
    public void transferTo(
            @NonNull MediaRoute2Info route, @NonNull RoutingChangeInfo routingChangeInfo) {
        mImpl.transferTo(route, routingChangeInfo);
    }

    /**
     * Stops the current media routing. If the {@link #getSystemController() system controller}
     * controls the media routing, this method is a no-op.
     */
    public void stop() {
        mImpl.stop();
    }

    /**
     * Transfers the media of a routing controller to the given route.
     *
     * <p>This will be no-op for non-system media routers.
     *
     * @param controller a routing controller controlling media routing.
     * @param route the route you want to transfer the media to.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void transfer(@NonNull RoutingController controller, @NonNull MediaRoute2Info route) {
        mImpl.transfer(controller, route, /* routingChangeInfo= */ null);
    }

    /**
     * Transfers the media of a routing controller to the given route.
     *
     * <p>This will be no-op for non-system media routers.
     *
     * @param controller a routing controller controlling media routing.
     * @param route the route you want to transfer the media to.
     * @param routingChangeInfo information about the start of the media routing session. See {@link
     *     android.media.RoutingChangeInfo}
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void transfer(
            @NonNull RoutingController controller,
            @NonNull MediaRoute2Info route,
            @NonNull RoutingChangeInfo routingChangeInfo) {
        mImpl.transfer(controller, route, routingChangeInfo);
    }

    void requestCreateController(
            @NonNull RoutingController controller,
            @NonNull MediaRoute2Info route,
            long managerRequestId,
            @NonNull RoutingChangeInfo routingChangeInfo) {

        if (route.isSystemRoute()) {
            notifyTransfer(controller, getSystemController());
            controller.release();
            return;
        }

        final int requestId = mNextRequestId.getAndIncrement();

        ControllerCreationRequest request =
                new ControllerCreationRequest(requestId, managerRequestId, route, controller);
        mControllerCreationRequests.add(request);

        OnGetControllerHintsListener listener = mOnGetControllerHintsListener;
        Bundle controllerHints = null;
        if (listener != null) {
            controllerHints = listener.onGetControllerHints(route);
            if (controllerHints != null) {
                controllerHints = new Bundle(controllerHints);
            }
        }

        MediaRouter2Stub stub;
        synchronized (mLock) {
            stub = mStub;
        }
        if (stub != null) {
            try {
                mMediaRouterService.requestCreateSessionWithRouter2(
                        stub,
                        requestId,
                        managerRequestId,
                        controller.getRoutingSessionInfo(),
                        route,
                        routingChangeInfo,
                        controllerHints);
            } catch (RemoteException ex) {
                mLog.e(
                        "createControllerForTransfer: "
                                + "Failed to request for creating a controller.",
                        ex);
                mControllerCreationRequests.remove(request);
                if (managerRequestId == MANAGER_REQUEST_ID_NONE) {
                    notifyTransferFailure(route);
                }
            }
        }
    }

    @NonNull
    private RoutingController getCurrentController() {
        List<RoutingController> controllers = getControllers();
        return controllers.get(controllers.size() - 1);
    }

    /**
     * Gets a {@link RoutingController} which can control the routes provided by system.
     * e.g. Phone speaker, wired headset, Bluetooth, etc.
     *
     * <p>Note: The system controller can't be released. Calling {@link RoutingController#release()}
     * will be ignored.
     *
     * <p>This method always returns the same instance.
     */
    @NonNull
    public RoutingController getSystemController() {
        return mSystemController;
    }

    /**
     * Gets a {@link RoutingController} whose ID is equal to the given ID.
     * Returns {@code null} if there is no matching controller.
     */
    @Nullable
    public RoutingController getController(@NonNull String id) {
        Objects.requireNonNull(id, "id must not be null");
        for (RoutingController controller : getControllers()) {
            if (TextUtils.equals(id, controller.getId())) {
                return controller;
            }
        }
        return null;
    }

    /**
     * Gets the list of currently active {@link RoutingController routing controllers} on which
     * media can be played.
     *
     * <p>Note: The list returned here will never be empty. The first element in the list is
     * always the {@link #getSystemController() system controller}.
     */
    @NonNull
    public List<RoutingController> getControllers() {
        return mImpl.getControllers();
    }

    /**
     * Sets the volume for a specific route.
     *
     * <p>The call may have no effect if the route is currently not selected.
     *
     * <p>This method is only supported by {@link #getInstance(Context, String) proxy MediaRouter2
     * instances}. Use {@link RoutingController#setVolume(int) RoutingController#setVolume(int)}
     * instead for {@link #getInstance(Context) local MediaRouter2 instances}.</p>
     *
     * @param volume The new volume value between 0 and {@link MediaRoute2Info#getVolumeMax}.
     * @throws UnsupportedOperationException If called on a {@link #getInstance(Context) local
     * router instance}.
     */
    @FlaggedApi(FLAG_ENABLE_PRIVILEGED_ROUTING_FOR_MEDIA_ROUTING_CONTROL)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.MEDIA_CONTENT_CONTROL,
                Manifest.permission.MEDIA_ROUTING_CONTROL
            })
    public void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
        Objects.requireNonNull(route, "route must not be null");

        mImpl.setRouteVolume(route, volume);
    }

    /**
     * Returns the set of apps currently affected by a system session override.
     *
     * <p>This method is only supported by proxy routers.
     *
     * @see SystemSessionOverridesListener
     * @hide
     */
    public Set<AppId> getSystemSessionOverridesAppIds() {
        return mImpl.getSystemSessionOverridesAppIds();
    }

    void syncRoutesOnHandler(
            List<MediaRoute2Info> currentRoutes, RoutingSessionInfo currentSystemSessionInfo) {
        if (currentRoutes == null || currentRoutes.isEmpty() || currentSystemSessionInfo == null) {
            mLog.e(
                    "syncRoutesOnHandler: Received wrong data. currentRoutes="
                            + currentRoutes
                            + ", currentSystemSessionInfo="
                            + currentSystemSessionInfo);
            return;
        }

        updateRoutesOnHandler(currentRoutes, /* missingPermissions=*/Collections.emptyList());

        RoutingSessionInfo oldInfo = mSystemController.getRoutingSessionInfo();
        mSystemController.setRoutingSessionInfo(ensureClientPackageNameForSystemSession(
                currentSystemSessionInfo, mContext.getPackageName()));
        if (!oldInfo.equals(currentSystemSessionInfo)) {
            notifyControllerUpdated(mSystemController, /* shouldShowVolumeUi= */ false);
        }
    }

    void dispatchFilteredRoutesUpdatedOnHandler(List<MediaRoute2Info> newRoutes) {
        List<MediaRoute2Info> addedRoutes = new ArrayList<>();
        List<MediaRoute2Info> removedRoutes = new ArrayList<>();
        List<MediaRoute2Info> changedRoutes = new ArrayList<>();

        Set<String> newRouteIds =
                newRoutes.stream().map(MediaRoute2Info::getId).collect(Collectors.toSet());

        for (MediaRoute2Info route : newRoutes) {
            MediaRoute2Info prevRoute = mPreviousFilteredRoutes.get(route.getId());
            if (prevRoute == null) {
                addedRoutes.add(route);
            } else if (!prevRoute.equals(route)) {
                changedRoutes.add(route);
            }
        }

        for (int i = 0; i < mPreviousFilteredRoutes.size(); i++) {
            if (!newRouteIds.contains(mPreviousFilteredRoutes.keyAt(i))) {
                removedRoutes.add(mPreviousFilteredRoutes.valueAt(i));
            }
        }

        // update previous routes
        for (MediaRoute2Info route : removedRoutes) {
            mPreviousFilteredRoutes.remove(route.getId());
        }
        for (MediaRoute2Info route : addedRoutes) {
            mPreviousFilteredRoutes.put(route.getId(), route);
        }
        for (MediaRoute2Info route : changedRoutes) {
            mPreviousFilteredRoutes.put(route.getId(), route);
        }

        if (!addedRoutes.isEmpty()) {
            notifyRoutesAdded(addedRoutes);
        }
        if (!removedRoutes.isEmpty()) {
            notifyRoutesRemoved(removedRoutes);
        }
        if (!changedRoutes.isEmpty()) {
            notifyRoutesChanged(changedRoutes);
        }

        // Note: We don't notify clients of changes in route ordering.
        if (!addedRoutes.isEmpty() || !removedRoutes.isEmpty() || !changedRoutes.isEmpty()) {
            notifyRoutesUpdated(newRoutes);
        }
    }

    void dispatchControllerUpdatedIfNeededOnHandler(Map<String, MediaRoute2Info> routesMap) {
        List<RoutingController> controllers = getControllers();
        for (RoutingController controller : controllers) {

            for (String selectedRoute : controller.getRoutingSessionInfo().getSelectedRoutes()) {
                if (routesMap.containsKey(selectedRoute)
                        && mPreviousUnfilteredRoutes.containsKey(selectedRoute)) {
                    MediaRoute2Info currentRoute = routesMap.get(selectedRoute);
                    MediaRoute2Info oldRoute = mPreviousUnfilteredRoutes.get(selectedRoute);
                    if (!currentRoute.equals(oldRoute)) {
                        notifyControllerUpdated(controller, /* shouldShowVolumeUi= */ false);
                        break;
                    }
                }
            }
        }

        mPreviousUnfilteredRoutes.clear();
        mPreviousUnfilteredRoutes.putAll(routesMap);
    }

    void updateRoutesOnHandler(List<MediaRoute2Info> newRoutes, List<String> missingPermissions) {
        synchronized (mLock) {
            mRoutes.clear();
            for (MediaRoute2Info route : newRoutes) {
                mRoutes.put(route.getId(), route);
            }
            updateMissingPermissionsLocked(missingPermissions);
            updateFilteredRoutesLocked();
            if (Flags.enableRouteVisibilityControlCompatFixes()) {
                dispatchFilteredRoutesUpdatedOnHandler(mFilteredRoutes);
                dispatchControllerUpdatedIfNeededOnHandler(mRoutes);
            }
        }
    }

    /** Updates filtered routes and dispatch callbacks */
    @GuardedBy("mLock")
    void updateFilteredRoutesLocked() {
        mFilteredRoutes =
                Collections.unmodifiableList(
                        filterRoutesWithCompositePreferenceLocked(List.copyOf(mRoutes.values())));
        if (!Flags.enableRouteVisibilityControlCompatFixes()) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::dispatchFilteredRoutesUpdatedOnHandler,
                            this,
                            mFilteredRoutes));
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::dispatchControllerUpdatedIfNeededOnHandler,
                            this,
                            new HashMap<>(mRoutes)));
        }
    }

    @GuardedBy("mLock")
    void updateMissingPermissionsLocked(List<String> permissions) {
        Set<String> newPermissions = new ArraySet<>(permissions);
        if (newPermissions.equals(mMissingPermissions)) {
            return;
        }
        mMissingPermissions.clear();
        mMissingPermissions.addAll(permissions);
        notifyMissingPermissionsUpdated(Collections.unmodifiableSet(newPermissions));
    }

    /**
     * Creates a controller and calls the {@link TransferCallback#onTransfer}. If the controller
     * creation has failed, then it calls {@link TransferCallback#onTransferFailure}.
     *
     * <p>Pass {@code null} to sessionInfo for the failure case.
     */
    void createControllerOnHandler(int requestId, @Nullable RoutingSessionInfo sessionInfo) {
        ControllerCreationRequest matchingRequest = null;
        for (ControllerCreationRequest request : mControllerCreationRequests) {
            if (request.mRequestId == requestId) {
                matchingRequest = request;
                break;
            }
        }

        if (matchingRequest == null) {
            mLog.w("createControllerOnHandler: Ignoring an unknown request.");
            return;
        }

        mControllerCreationRequests.remove(matchingRequest);
        MediaRoute2Info requestedRoute = matchingRequest.mRoute;

        // TODO: Notify the reason for failure.
        if (sessionInfo == null) {
            notifyTransferFailure(requestedRoute);
            return;
        } else if (!TextUtils.equals(requestedRoute.getProviderId(), sessionInfo.getProviderId())) {
            mLog.w(
                    "The session's provider ID does not match the requested route's. "
                            + "(requested route's providerId="
                            + requestedRoute.getProviderId()
                            + ", actual providerId="
                            + sessionInfo.getProviderId()
                            + ")");
            notifyTransferFailure(requestedRoute);
            return;
        }

        RoutingController oldController = matchingRequest.mOldController;
        // When the old controller is released before transferred, treat it as a failure.
        // This could also happen when transfer is requested twice or more.
        if (!oldController.scheduleRelease()) {
            mLog.w(
                    "createControllerOnHandler: "
                            + "Ignoring controller creation for released old controller. "
                            + "oldController="
                            + oldController);
            if (!sessionInfo.isSystemSession()) {
                new RoutingController(sessionInfo).release();
            }
            notifyTransferFailure(requestedRoute);
            return;
        }

        RoutingController newController = addRoutingController(sessionInfo);
        notifyTransfer(oldController, newController);
    }

    @NonNull
    private RoutingController addRoutingController(@NonNull RoutingSessionInfo session) {
        RoutingController controller;
        if (session.isSystemSession()) {
            // mSystemController is never released, so we only need to update its status.
            mSystemController.setRoutingSessionInfo(session);
            controller = mSystemController;
        } else {
            controller = new RoutingController(session);
            synchronized (mLock) {
                mNonSystemRoutingControllers.put(controller.getId(), controller);
            }
        }
        return controller;
    }

    void updateControllerOnHandler(RoutingSessionInfo sessionInfo) {
        if (sessionInfo == null) {
            mLog.w("updateControllerOnHandler: Ignoring null sessionInfo.");
            return;
        }

        RoutingController controller =
                getMatchingController(sessionInfo, /* logPrefix */ "updateControllerOnHandler");
        if (controller != null) {
            if (Flags.enableMirroringInMediaRouter2()) {
                sessionInfo =
                        ensureClientPackageNameForSystemSession(
                                sessionInfo, mImpl.getClientPackageName());
            }
            controller.setRoutingSessionInfo(sessionInfo);
            notifyControllerUpdated(controller, /* shouldShowVolumeUi= */ false);
        }
    }

    void releaseControllerOnHandler(RoutingSessionInfo sessionInfo) {
        if (sessionInfo == null) {
            mLog.w("releaseControllerOnHandler: Ignoring null sessionInfo.");
            return;
        }

        RoutingController matchingController =
                getMatchingController(sessionInfo, /* logPrefix */ "releaseControllerOnHandler");

        if (matchingController != null) {
            matchingController.releaseInternal(/* shouldReleaseSession= */ false);
        }
    }

    @Nullable
    private RoutingController getMatchingController(
            RoutingSessionInfo sessionInfo, String logPrefix) {
        if (sessionInfo.isSystemSession()) {
            return getSystemController();
        } else {
            RoutingController controller;
            synchronized (mLock) {
                controller = mNonSystemRoutingControllers.get(sessionInfo.getId());
            }

            if (controller == null) {
                mLog.w(
                        logPrefix
                                + ": Matching controller not found. uniqueSessionId="
                                + sessionInfo.getId());
                return null;
            }

            RoutingSessionInfo oldInfo = controller.getRoutingSessionInfo();
            if (!TextUtils.equals(oldInfo.getProviderId(), sessionInfo.getProviderId())) {
                mLog.w(
                        logPrefix
                                + ": Provider IDs are not matched. old="
                                + oldInfo.getProviderId()
                                + ", new="
                                + sessionInfo.getProviderId());
                return null;
            }
            return controller;
        }
    }

    void onRequestCreateControllerByManagerOnHandler(
            RoutingSessionInfo oldSession,
            MediaRoute2Info route,
            long managerRequestId,
            RoutingChangeInfo routingChangeInfo) {
        mLog.i(
                TextUtils.formatSimple(
                        "requestCreateSessionByManager | requestId: %d, oldSession: %s, route: %s",
                        managerRequestId, oldSession, route));
        RoutingController controller;
        String oldSessionId = oldSession.getId();
        if (oldSession.isSystemSession()) {
            controller = getSystemController();
        } else {
            synchronized (mLock) {
                controller = mNonSystemRoutingControllers.get(oldSessionId);
            }
        }
        if (controller == null) {
            mLog.w(
                    TextUtils.formatSimple(
                            "Ignoring requestCreateSessionByManager (requestId: %d) because no"
                                    + " controller for old session (id: %s) was found.",
                            managerRequestId, oldSessionId));
            return;
        }
        requestCreateController(controller, route, managerRequestId, routingChangeInfo);
    }

    private List<MediaRoute2Info> getSortedRoutes(
            List<MediaRoute2Info> routes, List<String> packageOrder) {
        if (packageOrder.isEmpty()) {
            return routes;
        }
        Map<String, Integer> packagePriority = new ArrayMap<>();
        int count = packageOrder.size();
        for (int i = 0; i < count; i++) {
            // the last package will have 1 as the priority
            packagePriority.put(packageOrder.get(i), count - i);
        }
        ArrayList<MediaRoute2Info> sortedRoutes = new ArrayList<>(routes);
        // take the negative for descending order
        sortedRoutes.sort(
                Comparator.comparingInt(
                        r -> -packagePriority.getOrDefault(r.getProviderPackageName(), 0)));
        return sortedRoutes;
    }

    @GuardedBy("mLock")
    private List<MediaRoute2Info> filterRoutesWithCompositePreferenceLocked(
            List<MediaRoute2Info> routes) {

        Set<String> deduplicationIdSet = new ArraySet<>();

        List<MediaRoute2Info> filteredRoutes = new ArrayList<>();
        for (MediaRoute2Info route :
                getSortedRoutes(routes, mDiscoveryPreference.getDeduplicationPackageOrder())) {
            if (!route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                continue;
            }
            if (!mDiscoveryPreference.getAllowedPackages().isEmpty()
                    && (route.getProviderPackageName() == null
                            || !mDiscoveryPreference
                                    .getAllowedPackages()
                                    .contains(route.getProviderPackageName()))) {
                continue;
            }
            if (mDiscoveryPreference.shouldRemoveDuplicates()) {
                if (!Collections.disjoint(deduplicationIdSet, route.getDeduplicationIds())) {
                    continue;
                }
                deduplicationIdSet.addAll(route.getDeduplicationIds());
            }
            filteredRoutes.add(route);
        }
        return filteredRoutes;
    }

    @NonNull
    private List<MediaRoute2Info> getRoutesWithIds(@NonNull List<String> routeIds) {
        synchronized (mLock) {
            return routeIds.stream()
                    .map(mRoutes::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    private void notifyRoutesAdded(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes =
                    mImpl.filterRoutesWithIndividualPreference(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(() -> record.mRouteCallback.onRoutesAdded(filteredRoutes));
            }
        }
    }

    private void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes =
                    mImpl.filterRoutesWithIndividualPreference(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesRemoved(filteredRoutes));
            }
        }
    }

    private void notifyRoutesChanged(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes =
                    mImpl.filterRoutesWithIndividualPreference(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesChanged(filteredRoutes));
            }
        }
    }

    private void notifyRoutesUpdated(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes =
                    mImpl.filterRoutesWithIndividualPreference(routes, record.mPreference);
            record.mExecutor.execute(() -> record.mRouteCallback.onRoutesUpdated(filteredRoutes));
        }
    }

    private void notifyMissingPermissionsUpdated(Set<String> permissions) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            record.mExecutor.execute(() ->
                    record.mRouteCallback.onMissingPermissionsUpdated(permissions));
        }
    }

    private void notifyPreferredFeaturesChanged(List<String> features) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mRouteCallback.onPreferredFeaturesChanged(features));
        }
    }

    private void notifyRouteListingPreferenceUpdated(@Nullable RouteListingPreference preference) {
        for (RouteListingPreferenceCallbackRecord record : mListingPreferenceCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mRouteListingPreferenceCallback.accept(preference));
        }
    }

    private void notifyDeviceSuggestionsUpdated(
            @NonNull String suggestingPackageName,
            @Nullable List<SuggestedDeviceInfo> deviceSuggestions) {
        for (DeviceSuggestionsUpdatesCallbackRecord record :
                mDeviceSuggestionsUpdatesCallbackRecords) {
            record.mExecutor.execute(
                    () -> {
                        if (deviceSuggestions != null) {
                            record.mDeviceSuggestionsUpdatesCallback.onSuggestionsUpdated(
                                    suggestingPackageName, deviceSuggestions);
                        } else {
                            record.mDeviceSuggestionsUpdatesCallback.onSuggestionsCleared(
                                    suggestingPackageName);
                        }
                    });
        }
    }

    private void notifyCallbacksDeviceSuggestionRequested() {
        for (DeviceSuggestionsUpdatesCallbackRecord record :
                mDeviceSuggestionsUpdatesCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mDeviceSuggestionsUpdatesCallback.onSuggestionsRequested());
        }
    }

    private void notifyTransfer(RoutingController oldController, RoutingController newController) {
        for (TransferCallbackRecord record : mTransferCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mTransferCallback.onTransfer(oldController, newController));
        }
    }

    private void notifyTransferFailure(MediaRoute2Info route) {
        for (TransferCallbackRecord record : mTransferCallbackRecords) {
            record.mExecutor.execute(() -> record.mTransferCallback.onTransferFailure(route));
        }
    }

    private void notifyRequestFailed(int reason) {
        for (TransferCallbackRecord record : mTransferCallbackRecords) {
            record.mExecutor.execute(() -> record.mTransferCallback.onRequestFailed(reason));
        }
    }

    private void notifyStop(RoutingController controller) {
        for (TransferCallbackRecord record : mTransferCallbackRecords) {
            record.mExecutor.execute(() -> record.mTransferCallback.onStop(controller));
        }
    }

    private void notifyControllerUpdated(RoutingController controller, boolean shouldShowVolumeUi) {
        for (ControllerCallbackRecord record : mControllerCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onControllerUpdated(controller, shouldShowVolumeUi));
        }
    }

    /**
     * Sets the routing session's {@linkplain RoutingSessionInfo#getClientPackageName() client
     * package name} to {@code packageName} if empty and returns the session.
     *
     * <p>This method must only be used for {@linkplain RoutingSessionInfo#isSystemSession()
     * system routing sessions}.
     */
    private static RoutingSessionInfo ensureClientPackageNameForSystemSession(
            @NonNull RoutingSessionInfo sessionInfo, @NonNull String packageName) {
        if (!sessionInfo.isSystemSession()
                || !TextUtils.isEmpty(sessionInfo.getClientPackageName())) {
            return sessionInfo;
        }

        return new RoutingSessionInfo.Builder(sessionInfo)
                .setClientPackageName(packageName)
                .build();
    }

    /** Callback for receiving events about device suggestions */
    @FlaggedApi(FLAG_ENABLE_SUGGESTED_DEVICE_API)
    public interface DeviceSuggestionsUpdatesCallback {

        /**
         * Called when suggestions are updated.
         *
         * @param suggestingPackageName the package that provided the suggestions.
         * @param suggestedDeviceInfo the suggestions provided by the package.
         */
        void onSuggestionsUpdated(
                @NonNull String suggestingPackageName,
                @NonNull List<SuggestedDeviceInfo> suggestedDeviceInfo);

        /**
         * Called when suggestions are cleared.
         *
         * @param suggestingPackageName the package that cleared their provided suggestions.
         */
        void onSuggestionsCleared(@NonNull String suggestingPackageName);

        /** Called when a router requests a suggestion from suggestion providers. */
        void onSuggestionsRequested();
    }

    /** Callback for receiving events about media route discovery. */
    public abstract static class RouteCallback {
        /**
         * Called when routes are added. Whenever you register a callback, this will be invoked with
         * known routes.
         *
         * @param routes the list of routes that have been added. It's never empty.
         * @deprecated Use {@link #onRoutesUpdated(List)} instead.
         */
        @Deprecated
        public void onRoutesAdded(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are removed.
         *
         * @param routes the list of routes that have been removed. It's never empty.
         * @deprecated Use {@link #onRoutesUpdated(List)} instead.
         */
        @Deprecated
        public void onRoutesRemoved(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when the properties of one or more existing routes are changed. For example, it is
         * called when a route's name or volume have changed.
         *
         * @param routes the list of routes that have been changed. It's never empty.
         * @deprecated Use {@link #onRoutesUpdated(List)} instead.
         */
        @Deprecated
        public void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when the route list is updated, which can happen when routes are added, removed,
         * or modified. It will also be called when a route callback is registered.
         *
         * @param routes the updated list of routes filtered by the callback's individual discovery
         *     preferences.
         */
        public void onRoutesUpdated(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when the client app's preferred features are changed. When this is called, it is
         * recommended to {@link #getRoutes()} to get the routes that are currently available to the
         * app.
         *
         * @param preferredFeatures the new preferred features set by the application
         * @hide
         */
        @SystemApi
        public void onPreferredFeaturesChanged(@NonNull List<String> preferredFeatures) {}

        /**
         * Called when permissions to see routes that the target app does not have is updated.
         *
         * @param missingPermissions The list of permissions as per {@link #getMissingPermissions()}
         * @see #getMissingPermissions()
         * @hide
         */
        public void onMissingPermissionsUpdated(@NonNull Set<String> missingPermissions) {}
    }

    /** Callback for receiving events on media transfer. */
    public abstract static class TransferCallback {
        /**
         * Called when a media is transferred between two different routing controllers. This can
         * happen by calling {@link #transferTo(MediaRoute2Info)}.
         *
         * <p>Override this to start playback with {@code newController}. You may want to get the
         * status of the media that is being played with {@code oldController} and resume it
         * continuously with {@code newController}. After this is called, any callbacks with {@code
         * oldController} will not be invoked unless {@code oldController} is the {@link
         * #getSystemController() system controller}. You need to {@link RoutingController#release()
         * release} {@code oldController} before playing the media with {@code newController}.
         *
         * @param oldController the previous controller that controlled routing
         * @param newController the new controller to control routing
         * @see #transferTo(MediaRoute2Info)
         */
        public void onTransfer(
                @NonNull RoutingController oldController,
                @NonNull RoutingController newController) {}

        /**
         * Called when {@link #transferTo(MediaRoute2Info)} failed.
         *
         * @param requestedRoute the route info which was used for the transfer
         */
        public void onTransferFailure(@NonNull MediaRoute2Info requestedRoute) {}

        /**
         * Called when a media routing stops. It can be stopped by a user or a provider. App should
         * not continue playing media locally when this method is called. The {@code controller} is
         * released before this method is called.
         *
         * @param controller the controller that controlled the stopped media routing
         */
        public void onStop(@NonNull RoutingController controller) {}

        /**
         * Called when a routing request fails.
         *
         * @param reason Reason for failure as per {@link
         *     android.media.MediaRoute2ProviderService.Reason}
         * @hide
         */
        public void onRequestFailed(int reason) {}
    }

    /**
     * A listener interface to send optional app-specific hints when creating a {@link
     * RoutingController}.
     */
    public interface OnGetControllerHintsListener {
        /**
         * Called when the {@link MediaRouter2} or the system is about to request a media route
         * provider service to create a controller with the given route. The {@link Bundle} returned
         * here will be sent to media route provider service as a hint.
         *
         * <p>Since controller creation can be requested by the {@link MediaRouter2} and the system,
         * set the listener as soon as possible after acquiring {@link MediaRouter2} instance. The
         * method will be called on the same thread that calls {@link #transferTo(MediaRoute2Info)}
         * or the main thread if it is requested by the system.
         *
         * @param route the route to create a controller with
         * @return An optional bundle of app-specific arguments to send to the provider, or {@code
         *     null} if none. The contents of this bundle may affect the result of controller
         *     creation.
         * @see MediaRoute2ProviderService#onCreateSession(long, String, String, Bundle)
         */
        @Nullable
        Bundle onGetControllerHints(@NonNull MediaRoute2Info route);
    }

    /** Callback for receiving {@link RoutingController} updates. */
    public abstract static class ControllerCallback {
        /**
         * Called when a controller is updated. (e.g., when the selected routes of the controller is
         * changed or when the volume of the controller is changed.)
         *
         * @param controller the updated controller. It may be the {@link #getSystemController()
         *     system controller}.
         * @see #getSystemController()
         */
        public void onControllerUpdated(@NonNull RoutingController controller) {}

        /**
         * Equivalent to {@link #onControllerUpdated(RoutingController)} except it adds {@code
         * shouldShowVolumeUi}, which indicates that a UI affordance should be presented as a result
         * of this controller update. Likely, because the update is the result of a HW volume key
         * press.
         *
         * <p>By default, this method invokes {@link #onControllerUpdated(RoutingController)}, which
         * means it's sufficient to override any of the methods in this class to receive controller
         * update events.
         *
         * @hide
         */
        public void onControllerUpdated(
                @NonNull RoutingController controller, boolean shouldShowVolumeUi) {
            this.onControllerUpdated(controller);
        }
    }

    /**
     * Listens for changes in the list of apps with an overriding system routing session.
     *
     * <p>An overriding system routing session is a system {@link RoutingSessionInfo session} that
     * overrides the global system routing, which applies to all apps (typically describing the
     * audio framework's routing decisions, for example {@link
     * android.media.AudioManager#getPreferredDeviceForStrategy}).
     *
     * <p>By default, applications' system routing session (queried using {@link
     * #getSystemController()}) will be the global session. However, specific apps see a different
     * {@link #getSystemController() system session} when affected by a {@link
     * MediaRoute2ProviderService#onCreateSystemRoutingSession service-managed} system routing
     * session that overrides the global session. This listener reports changes to the set of apps
     * affected by a system session override.
     *
     * <p>The list of apps with overriding system sessions is useful, for example, to display a
     * dedicated volume slider in SysUI when a physical volume rocker switch is pressed.
     *
     * <p>A client interested in specific aspects (for example, volume level) of the overriding
     * system routing session should register a {@link #getInstance(Context, String, UserHandle)
     * proxy router} for the relevant app and listen for {@link #registerControllerCallback}routing
     * session changes.
     *
     * @see #registerSystemSessionOverridesListener
     * @hide
     */
    public interface SystemSessionOverridesListener {

        /** Called when the set of apps with an overriding system session changes. */
        void onSystemSessionOverridesChanged(Set<AppId> appIdsWithOverridingSystemSession);
    }

    /**
     * Represents an active scan request registered in the system.
     *
     * <p>See {@link #requestScan(ScanRequest)} for more information.
     */
    public static final class ScanToken {
        private final int mId;

        private ScanToken(int id) {
            mId = id;
        }
    }

    /**
     * Represents a set of parameters for scanning requests.
     *
     * <p>See {@link #requestScan(ScanRequest)} for more details.
     */
    public static final class ScanRequest {
        private final boolean mIsScreenOffScan;

        private ScanRequest(boolean isScreenOffScan) {
            mIsScreenOffScan = isScreenOffScan;
        }

        /**
         * Returns whether the scan request corresponds to a screen-off scan.
         *
         * @see #requestScan(ScanRequest)
         */
        public boolean isScreenOffScan() {
            return mIsScreenOffScan;
        }

        /**
         * Builder class for {@link ScanRequest}.
         *
         * @see #requestScan(ScanRequest)
         */
        public static final class Builder {
            boolean mIsScreenOffScan;

            /**
             * Creates a builder for a {@link ScanRequest} instance.
             *
             * @see #requestScan(ScanRequest)
             */
            public Builder() {}

            /**
             * Sets whether the app is requesting to scan even while the screen is off, bypassing
             * default scanning restrictions. Only apps holding {@link
             * Manifest.permission#MEDIA_ROUTING_CONTROL} or {@link
             * Manifest.permission#MEDIA_CONTENT_CONTROL} should set this to {@code true}.
             *
             * @see #requestScan(ScanRequest)
             */
            @NonNull
            public Builder setScreenOffScan(boolean isScreenOffScan) {
                mIsScreenOffScan = isScreenOffScan;
                return this;
            }

            /** Returns a new {@link ScanRequest} instance. */
            @NonNull
            public ScanRequest build() {
                return new ScanRequest(mIsScreenOffScan);
            }
        }
    }

    /**
     * Controls a media routing session.
     *
     * <p>Routing controllers wrap a {@link RoutingSessionInfo}, taking care of mapping route ids to
     * {@link MediaRoute2Info} instances. You can still access the underlying session using {@link
     * #getRoutingSessionInfo()}, but keep in mind it can be changed by other threads. Changes to
     * the routing session are notified via {@link ControllerCallback}.
     */
    public class RoutingController {
        private final Object mControllerLock = new Object();

        private static final int CONTROLLER_STATE_UNKNOWN = 0;
        private static final int CONTROLLER_STATE_ACTIVE = 1;
        private static final int CONTROLLER_STATE_RELEASING = 2;
        private static final int CONTROLLER_STATE_RELEASED = 3;

        @GuardedBy("mControllerLock")
        private RoutingSessionInfo mSessionInfo;

        @GuardedBy("mControllerLock")
        private int mState;

        RoutingController(@NonNull RoutingSessionInfo sessionInfo) {
            mSessionInfo = sessionInfo;
            mState = CONTROLLER_STATE_ACTIVE;
        }

        RoutingController(@NonNull RoutingSessionInfo sessionInfo, int state) {
            mSessionInfo = sessionInfo;
            mState = state;
        }

        /**
         * @return the ID of the controller. It is globally unique.
         */
        @NonNull
        public String getId() {
            synchronized (mControllerLock) {
                return mSessionInfo.getId();
            }
        }

        /**
         * Gets the original session ID set by {@link RoutingSessionInfo.Builder#Builder(String,
         * String)}.
         *
         * @hide
         */
        @NonNull
        @TestApi
        public String getOriginalId() {
            synchronized (mControllerLock) {
                return mSessionInfo.getOriginalId();
            }
        }

        /**
         * Gets the control hints used to control routing session if available. It is set by the
         * media route provider.
         */
        @Nullable
        public Bundle getControlHints() {
            synchronized (mControllerLock) {
                return mSessionInfo.getControlHints();
            }
        }

        /**
         * Returns the unmodifiable list of currently selected routes
         *
         * @see RoutingSessionInfo#getSelectedRoutes()
         */
        @NonNull
        public List<MediaRoute2Info> getSelectedRoutes() {
            List<String> selectedRouteIds;
            synchronized (mControllerLock) {
                selectedRouteIds = mSessionInfo.getSelectedRoutes();
            }
            return getRoutesWithIds(selectedRouteIds);
        }

        /**
         * Returns the unmodifiable list of selectable routes for the session.
         *
         * @see RoutingSessionInfo#getSelectableRoutes()
         */
        @NonNull
        public List<MediaRoute2Info> getSelectableRoutes() {
            List<String> selectableRouteIds;
            synchronized (mControllerLock) {
                selectableRouteIds = mSessionInfo.getSelectableRoutes();
            }
            return getRoutesWithIds(selectableRouteIds);
        }

        /**
         * Returns the unmodifiable list of deselectable routes for the session.
         *
         * @see RoutingSessionInfo#getDeselectableRoutes()
         */
        @NonNull
        public List<MediaRoute2Info> getDeselectableRoutes() {
            List<String> deselectableRouteIds;
            synchronized (mControllerLock) {
                deselectableRouteIds = mSessionInfo.getDeselectableRoutes();
            }
            return getRoutesWithIds(deselectableRouteIds);
        }

        /**
         * Returns the unmodifiable list of transferable routes for the session.
         *
         * @see RoutingSessionInfo#getTransferableRoutes()
         */
        @FlaggedApi(FLAG_ENABLE_GET_TRANSFERABLE_ROUTES)
        @NonNull
        public List<MediaRoute2Info> getTransferableRoutes() {
            List<String> transferableRoutes;
            synchronized (mControllerLock) {
                transferableRoutes = mSessionInfo.getTransferableRoutes();
            }
            return getRoutesWithIds(transferableRoutes);
        }

        /**
         * Returns whether the transfer was initiated by the calling app (as determined by comparing
         * {@link UserHandle} and package name).
         */
        public boolean wasTransferInitiatedBySelf() {
            return mImpl.wasTransferredBySelf(getRoutingSessionInfo());
        }

        /**
         * Returns the current {@link RoutingSessionInfo} associated to this controller.
         */
        @NonNull
        public RoutingSessionInfo getRoutingSessionInfo() {
            synchronized (mControllerLock) {
                return mSessionInfo;
            }
        }

        /**
         * Gets the information about how volume is handled on the session.
         *
         * <p>Please note that you may not control the volume of the session even when you can
         * control the volume of each selected route in the session.
         *
         * @return {@link MediaRoute2Info#PLAYBACK_VOLUME_FIXED} or {@link
         *     MediaRoute2Info#PLAYBACK_VOLUME_VARIABLE}
         */
        @MediaRoute2Info.PlaybackVolume
        public int getVolumeHandling() {
            synchronized (mControllerLock) {
                return mSessionInfo.getVolumeHandling();
            }
        }

        /** Gets the maximum volume of the session. */
        public int getVolumeMax() {
            synchronized (mControllerLock) {
                return mSessionInfo.getVolumeMax();
            }
        }

        /**
         * Gets the current volume of the session.
         *
         * <p>When it's available, it represents the volume of routing session, which is a group of
         * selected routes. Use {@link MediaRoute2Info#getVolume()} to get the volume of a route,
         *
         * @see MediaRoute2Info#getVolume()
         */
        public int getVolume() {
            synchronized (mControllerLock) {
                return mSessionInfo.getVolume();
            }
        }

        /**
         * Returns true if this controller is released, false otherwise. If it is released, then all
         * other getters from this instance may return invalid values. Also, any operations to this
         * instance will be ignored once released.
         *
         * @see #release
         */
        public boolean isReleased() {
            synchronized (mControllerLock) {
                return mState == CONTROLLER_STATE_RELEASED;
            }
        }

        /**
         * Selects a route for the remote session. After a route is selected, the media is expected
         * to be played to the all the selected routes. This is different from {@link
         * MediaRouter2#transferTo(MediaRoute2Info) transferring to a route}, where the media is
         * expected to 'move' from one route to another.
         *
         * <p>The given route must satisfy all of the following conditions:
         *
         * <ul>
         *   <li>It should not be included in {@link #getSelectedRoutes()}
         *   <li>It should be included in {@link #getSelectableRoutes()}
         * </ul>
         *
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #deselectRoute(MediaRoute2Info)
         * @see #getSelectedRoutes()
         * @see #getSelectableRoutes()
         * @see ControllerCallback#onControllerUpdated
         */
        public void selectRoute(@NonNull MediaRoute2Info route) {
            selectRoute(route, /* routingChangeInfo= */ null);
        }

        /**
         * Same as {@link #selectRoute(MediaRoute2Info)} but also takes {@link RoutingChangeInfo} as
         * a parameter for logging purposes.
         *
         * @hide
         */
        public void selectRoute(
                @NonNull MediaRoute2Info route, @Nullable RoutingChangeInfo routingChangeInfo) {
            Objects.requireNonNull(route, "route must not be null");
            if (isReleased()) {
                mLog.w("selectRoute: Called on released controller. Ignoring.");
                return;
            }

            List<MediaRoute2Info> selectedRoutes = getSelectedRoutes();
            if (containsRouteInfoWithId(selectedRoutes, route.getId())) {
                mLog.w("Ignoring selecting a route that is already selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> selectableRoutes = getSelectableRoutes();
            if (!containsRouteInfoWithId(selectableRoutes, route.getId())) {
                mLog.w("Ignoring selecting a non-selectable route=" + route);
                return;
            }

            mImpl.selectRoute(route, getRoutingSessionInfo(), routingChangeInfo);
        }

        /**
         * Deselects a route from the remote session. After a route is deselected, the media is
         * expected to be stopped on the deselected route.
         *
         * <p>The given route must satisfy all of the following conditions:
         *
         * <ul>
         *   <li>It should be included in {@link #getSelectedRoutes()}
         *   <li>It should be included in {@link #getDeselectableRoutes()}
         * </ul>
         *
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getDeselectableRoutes()
         * @see ControllerCallback#onControllerUpdated
         */
        public void deselectRoute(@NonNull MediaRoute2Info route) {
            deselectRoute(route, /* routingChangeInfo= */ null);
        }

        /**
         * Same as {@link #deselectRoute(MediaRoute2Info)} but also takes {@link RoutingChangeInfo}
         * as a parameter for logging purposes.
         *
         * @hide
         */
        public void deselectRoute(
                @NonNull MediaRoute2Info route, RoutingChangeInfo routingChangeInfo) {
            Objects.requireNonNull(route, "route must not be null");
            if (isReleased()) {
                mLog.w("deselectRoute: called on released controller. Ignoring.");
                return;
            }

            List<MediaRoute2Info> selectedRoutes = getSelectedRoutes();
            if (!containsRouteInfoWithId(selectedRoutes, route.getId())) {
                mLog.w("Ignoring deselecting a route that is not selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> deselectableRoutes = getDeselectableRoutes();
            if (!containsRouteInfoWithId(deselectableRoutes, route.getId())) {
                mLog.w("Ignoring deselecting a non-deselectable route=" + route);
                return;
            }

            mImpl.deselectRoute(route, getRoutingSessionInfo(), routingChangeInfo);
        }

        /**
         * Attempts a transfer to a {@link RoutingSessionInfo#getTransferableRoutes() transferable
         * route}.
         *
         * <p>Transferring to a transferable route does not require the app to transfer the playback
         * state from one route to the other. The route provider completely manages the transfer. An
         * example of provider-managed transfers are the switches between the system's routes, like
         * the built-in speakers and a BT headset.
         *
         * @return True if the transfer is handled by this controller, or false if a new controller
         *     should be created instead.
         * @see RoutingSessionInfo#getSelectedRoutes()
         * @see RoutingSessionInfo#getTransferableRoutes()
         * @see ControllerCallback#onControllerUpdated
         */
        boolean tryTransferWithinProvider(
                @NonNull MediaRoute2Info route, @NonNull RoutingChangeInfo routingChangeInfo) {
            Objects.requireNonNull(route, "route must not be null");
            Objects.requireNonNull(routingChangeInfo, "routingChangeInfo must not be null");
            synchronized (mControllerLock) {
                if (isReleased()) {
                    mLog.w("tryTransferWithinProvider: Called on released controller. Ignoring.");
                    return true;
                }

                // If this call is trying to transfer to a selected system route, we let them
                // through as a provider driven transfer in order to update the transfer reason and
                // initiator data.
                boolean isSystemRouteReselection =
                        mSessionInfo.isSystemSession()
                                && route.isSystemRoute()
                                && mSessionInfo.getSelectedRoutes().contains(route.getId());
                if (!isSystemRouteReselection
                        && !mSessionInfo.getTransferableRoutes().contains(route.getId())) {
                    mLog.i(
                            "Transferring to a non-transferable route="
                                    + route
                                    + " session= "
                                    + mSessionInfo.getId());
                    return false;
                }
            }

            MediaRouter2Stub stub;
            synchronized (mLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    mMediaRouterService.transferToRouteWithRouter2(
                            stub, getId(), route, routingChangeInfo);
                } catch (RemoteException ex) {
                    mLog.e("Unable to transfer to route for session.", ex);
                }
            }
            return true;
        }

        /**
         * Requests a volume change for the remote session asynchronously.
         *
         * @param volume The new volume value between 0 and {@link RoutingController#getVolumeMax}
         *     (inclusive).
         * @see #getVolume()
         */
        public void setVolume(int volume) {
            if (getVolumeHandling() == MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
                mLog.w("setVolume: The routing session has fixed volume. Ignoring.");
                return;
            }
            if (volume < 0 || volume > getVolumeMax()) {
                mLog.w("setVolume: The target volume is out of range. Ignoring");
                return;
            }

            if (isReleased()) {
                mLog.w("setVolume: Called on released controller. Ignoring.");
                return;
            }

            mImpl.setSessionVolume(volume, getRoutingSessionInfo());
        }

        /**
         * Releases this controller and the corresponding session. Any operations on this controller
         * after calling this method will be ignored. The devices that are playing media will stop
         * playing it.
         */
        public void release() {
            releaseInternal(/* shouldReleaseSession= */ true);
        }

        /**
         * Schedules release of the controller.
         *
         * @return {@code true} if it's successfully scheduled, {@code false} if it's already
         *     scheduled to be released or released.
         */
        boolean scheduleRelease() {
            synchronized (mControllerLock) {
                if (mState != CONTROLLER_STATE_ACTIVE) {
                    return false;
                }
                mState = CONTROLLER_STATE_RELEASING;
            }

            synchronized (mLock) {
                // It could happen if the controller is released by the another thread
                // in between two locks
                if (!mNonSystemRoutingControllers.remove(getId(), this)) {
                    // In that case, onStop isn't called so we return true to call onTransfer.
                    // It's also consistent with that the another thread acquires the lock later.
                    return true;
                }
            }

            mHandler.postDelayed(this::release, TRANSFER_TIMEOUT_MS);

            return true;
        }

        void releaseInternal(boolean shouldReleaseSession) {
            boolean shouldNotifyStop;

            synchronized (mControllerLock) {
                if (mState == CONTROLLER_STATE_RELEASED) {
                    if (DEBUG) {
                        mLog.d("releaseInternal: Called on released controller. Ignoring.");
                    }
                    return;
                }
                shouldNotifyStop = (mState == CONTROLLER_STATE_ACTIVE);
                mState = CONTROLLER_STATE_RELEASED;
            }

            mImpl.releaseSession(shouldReleaseSession, shouldNotifyStop, this);
        }

        @Override
        public String toString() {
            // To prevent logging spam, we only print the ID of each route.
            List<String> selectedRoutes =
                    getSelectedRoutes().stream()
                            .map(MediaRoute2Info::getId)
                            .collect(Collectors.toList());
            List<String> selectableRoutes =
                    getSelectableRoutes().stream()
                            .map(MediaRoute2Info::getId)
                            .collect(Collectors.toList());
            List<String> deselectableRoutes =
                    getDeselectableRoutes().stream()
                            .map(MediaRoute2Info::getId)
                            .collect(Collectors.toList());

            StringBuilder result =
                    new StringBuilder()
                            .append("RoutingController{ ")
                            .append("id=")
                            .append(getId())
                            .append(", selectedRoutes={")
                            .append(selectedRoutes)
                            .append("}")
                            .append(", selectableRoutes={")
                            .append(selectableRoutes)
                            .append("}")
                            .append(", deselectableRoutes={")
                            .append(deselectableRoutes)
                            .append("}")
                            .append(" }");
            return result.toString();
        }

        void setRoutingSessionInfo(@NonNull RoutingSessionInfo info) {
            synchronized (mControllerLock) {
                mSessionInfo = info;
            }
        }

        /** Returns whether any route in {@code routeList} has a same unique ID with given route. */
        private static boolean containsRouteInfoWithId(
                @NonNull List<MediaRoute2Info> routeList, @NonNull String routeId) {
            for (MediaRoute2Info info : routeList) {
                if (TextUtils.equals(routeId, info.getId())) {
                    return true;
                }
            }
            return false;
        }
    }

    class SystemRoutingController extends RoutingController {
        SystemRoutingController(@NonNull RoutingSessionInfo sessionInfo) {
            super(sessionInfo);
        }

        @Override
        public boolean isReleased() {
            // SystemRoutingController will never be released
            return false;
        }

        @Override
        boolean scheduleRelease() {
            // SystemRoutingController can be always transferred
            return true;
        }

        @Override
        void releaseInternal(boolean shouldReleaseSession) {
            // SystemRoutingController will never be released. But in some cases, the session can be
            // released, for example Bluetooth broadcast
            mImpl.releaseSession(
                    shouldReleaseSession,
                    /* shouldNotifyStop= */ false,
                    /* controller= */ this);
        }
    }

    private record SystemSessionOverridesListenerRecord(
            Executor mExecutor, SystemSessionOverridesListener mSystemSessionOverridesListener) {

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SystemSessionOverridesListenerRecord)) {
                return false;
            }
            return mSystemSessionOverridesListener
                    == ((SystemSessionOverridesListenerRecord) obj).mSystemSessionOverridesListener;
        }

        @Override
        public int hashCode() {
            return mSystemSessionOverridesListener.hashCode();
        }
    }

    static final class RouteCallbackRecord {
        public final Executor mExecutor;
        public final RouteCallback mRouteCallback;
        public final RouteDiscoveryPreference mPreference;

        RouteCallbackRecord(
                @Nullable Executor executor,
                @NonNull RouteCallback routeCallback,
                @Nullable RouteDiscoveryPreference preference) {
            mRouteCallback = routeCallback;
            mExecutor = executor;
            mPreference = preference;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RouteCallbackRecord)) {
                return false;
            }
            return mRouteCallback == ((RouteCallbackRecord) obj).mRouteCallback;
        }

        @Override
        public int hashCode() {
            return mRouteCallback.hashCode();
        }
    }

    private static final class RouteListingPreferenceCallbackRecord {
        public final Executor mExecutor;
        public final Consumer<RouteListingPreference> mRouteListingPreferenceCallback;

        /* package */ RouteListingPreferenceCallbackRecord(
                @NonNull Executor executor,
                @NonNull Consumer<RouteListingPreference> routeListingPreferenceCallback) {
            mExecutor = executor;
            mRouteListingPreferenceCallback = routeListingPreferenceCallback;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RouteListingPreferenceCallbackRecord)) {
                return false;
            }
            return mRouteListingPreferenceCallback
                    == ((RouteListingPreferenceCallbackRecord) obj).mRouteListingPreferenceCallback;
        }

        @Override
        public int hashCode() {
            return mRouteListingPreferenceCallback.hashCode();
        }
    }

    private static final class DeviceSuggestionsUpdatesCallbackRecord {
        public final Executor mExecutor;
        public final DeviceSuggestionsUpdatesCallback mDeviceSuggestionsUpdatesCallback;

        /* package */ DeviceSuggestionsUpdatesCallbackRecord(
                @NonNull Executor executor,
                @NonNull DeviceSuggestionsUpdatesCallback deviceSuggestionsUpdatesCallback) {
            mExecutor = executor;
            mDeviceSuggestionsUpdatesCallback = deviceSuggestionsUpdatesCallback;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DeviceSuggestionsUpdatesCallbackRecord)) {
                return false;
            }
            return mDeviceSuggestionsUpdatesCallback
                    == ((DeviceSuggestionsUpdatesCallbackRecord) obj)
                            .mDeviceSuggestionsUpdatesCallback;
        }

        @Override
        public int hashCode() {
            return mDeviceSuggestionsUpdatesCallback.hashCode();
        }
    }

    static final class TransferCallbackRecord {
        public final Executor mExecutor;
        public final TransferCallback mTransferCallback;

        TransferCallbackRecord(
                @NonNull Executor executor, @NonNull TransferCallback transferCallback) {
            mTransferCallback = transferCallback;
            mExecutor = executor;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TransferCallbackRecord)) {
                return false;
            }
            return mTransferCallback == ((TransferCallbackRecord) obj).mTransferCallback;
        }

        @Override
        public int hashCode() {
            return mTransferCallback.hashCode();
        }
    }

    static final class ControllerCallbackRecord {
        public final Executor mExecutor;
        public final ControllerCallback mCallback;

        ControllerCallbackRecord(
                @Nullable Executor executor, @NonNull ControllerCallback callback) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ControllerCallbackRecord)) {
                return false;
            }
            return mCallback == ((ControllerCallbackRecord) obj).mCallback;
        }

        @Override
        public int hashCode() {
            return mCallback.hashCode();
        }
    }

    static final class ControllerCreationRequest {
        public final int mRequestId;
        public final long mManagerRequestId;
        public final MediaRoute2Info mRoute;
        public final RoutingController mOldController;

        ControllerCreationRequest(
                int requestId,
                long managerRequestId,
                @NonNull MediaRoute2Info route,
                @NonNull RoutingController oldController) {
            mRequestId = requestId;
            mManagerRequestId = managerRequestId;
            mRoute = Objects.requireNonNull(route, "route must not be null");
            mOldController =
                    Objects.requireNonNull(oldController, "oldController must not be null");
        }
    }

    /**
     * Tracks an in-progress request of type {@code T} and managers its state and timeout lifecycle.
     *
     * <p>{link RequestTracker} keeps tracking a request and monitors its completion. When a request
     * is set, the tracker starts a timer task to enforce an expiration limit.
     *
     * <p>The {@link RequestTracker} has the following behaviors:
     *
     * <ul>
     *   <li>If the timer expires, the callback is notified of the timeout and the internal state is
     *       cleared.
     *   <li>If the request completes before the timeout, the callback is notified of success and
     *       the internal state is cleared.
     *   <li>If a request is completed but it is not tracked by this tracker, the completion event
     *       is ignored.
     * </ul>
     *
     * @param <T> The type of the request object being tracked.
     */
    private static class RequestTracker<T> {

        /**
         * Callbacks for notifying the lifecycle of a tracked request.
         *
         * @param <T> The type of the request object.
         */
        private static class Callback<T> {
            void onRequestCompleted(@NonNull T request) {}

            void onRequestTimeout(@NonNull T request) {}
        }

        private final int mTimeoutMs;
        @NonNull private final Handler mHandler;
        @NonNull private final Callback<T> mCallback;
        @Nullable private Runnable mRequestTimeoutRunnable;
        @Nullable private T mRequest;

        RequestTracker(int timeoutMs, @NonNull Handler handler, @NonNull Callback callback) {
            mTimeoutMs = timeoutMs;
            mHandler = handler;
            mCallback = callback;
        }

        /** Starts tracking the given request. */
        synchronized void track(@NonNull T request) {
            cancelRequestTimeout();
            mRequest = request;
            mRequestTimeoutRunnable = this::onTimeout;
            mHandler.postDelayed(mRequestTimeoutRunnable, mTimeoutMs);
        }

        /** Marks the tracking request as completed. */
        synchronized void complete() {
            cancelRequestTimeout();
            if (mRequest != null) {
                mCallback.onRequestCompleted(mRequest);
                mRequest = null;
            }
        }

        private void onTimeout() {
            cancelRequestTimeout();
            if (mRequest != null) {
                mCallback.onRequestTimeout(mRequest);
                mRequest = null;
            }
        }

        private void cancelRequestTimeout() {
            if (mRequestTimeoutRunnable != null) {
                mHandler.removeCallbacks(mRequestTimeoutRunnable);
                mRequestTimeoutRunnable = null;
            }
        }
    }

    class MediaRouter2Stub extends IMediaRouter2.Stub {
        @Override
        public void notifyRouterRegistered(
                List<MediaRoute2Info> currentRoutes, RoutingSessionInfo currentSystemSessionInfo) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::syncRoutesOnHandler,
                            MediaRouter2.this,
                            currentRoutes,
                            currentSystemSessionInfo));
        }

        @Override
        public void notifyRoutesUpdated(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(
                    obtainMessage(MediaRouter2::updateRoutesOnHandler, MediaRouter2.this, routes,
                            /* missingPermissions=*/Collections.emptyList()));
        }

        @Override
        public void notifySessionCreated(int requestId, @Nullable RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::createControllerOnHandler,
                            MediaRouter2.this,
                            requestId,
                            sessionInfo));
        }

        @Override
        public void notifySessionInfoChanged(@Nullable RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::updateControllerOnHandler,
                            MediaRouter2.this,
                            sessionInfo));
        }

        @Override
        public void notifySessionReleased(RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::releaseControllerOnHandler,
                            MediaRouter2.this,
                            sessionInfo));
        }

        @Override
        public void notifyDeviceSuggestionsUpdated(
                String suggestingPackageName, @Nullable List<SuggestedDeviceInfo> suggestions) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::notifyDeviceSuggestionsUpdated,
                            MediaRouter2.this,
                            suggestingPackageName,
                            suggestions));
        }

        @Override
        public void notifyDeviceSuggestionRequested() {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::notifyCallbacksDeviceSuggestionRequested,
                            MediaRouter2.this));
        }

        @Override
        public void requestCreateSessionByManager(
                long managerRequestId,
                RoutingSessionInfo oldSession,
                MediaRoute2Info route,
                RoutingChangeInfo routingChangeInfo) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::onRequestCreateControllerByManagerOnHandler,
                            MediaRouter2.this,
                            oldSession,
                            route,
                            managerRequestId,
                            routingChangeInfo));
        }
    }

    /**
     * Provides a common interface for separating {@link LocalMediaRouter2Impl local} and {@link
     * ProxyMediaRouter2Impl proxy} {@link MediaRouter2} instances.
     */
    private interface MediaRouter2Impl {

        void updateScanningState(@ScanningState int scanningState) throws RemoteException;

        void startScan();

        void stopScan();

        String getClientPackageName();

        String getPackageName();

        RoutingSessionInfo getSystemSessionInfo();

        RouteCallbackRecord createRouteCallbackRecord(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull RouteCallback routeCallback,
                @NonNull RouteDiscoveryPreference preference);

        void registerRouteCallback();

        void unregisterRouteCallback();

        void setRouteListingPreference(@Nullable RouteListingPreference preference);

        void setDeviceSuggestions(@Nullable List<SuggestedDeviceInfo> suggestedDeviceInfo);

        @NonNull
        Map<String, List<SuggestedDeviceInfo>> getDeviceSuggestions();

        void notifyDeviceSuggestionRequested();

        boolean showSystemOutputSwitcher(@Nullable MediaSession.Token token);

        List<MediaRoute2Info> getAllRoutes();

        void setOnGetControllerHintsListener(OnGetControllerHintsListener listener);

        void transferTo(MediaRoute2Info route, @Nullable RoutingChangeInfo routingChangeInfo);

        void stop();

        void transfer(
                @NonNull RoutingController controller,
                @NonNull MediaRoute2Info route,
                @Nullable RoutingChangeInfo routingChangeInfo);

        List<RoutingController> getControllers();

        void setRouteVolume(MediaRoute2Info route, int volume);

        List<MediaRoute2Info> filterRoutesWithIndividualPreference(
                List<MediaRoute2Info> routes, RouteDiscoveryPreference discoveryPreference);

        // RoutingController methods.
        void setSessionVolume(int volume, RoutingSessionInfo sessionInfo);

        void selectRoute(
                MediaRoute2Info route,
                RoutingSessionInfo sessionInfo,
                RoutingChangeInfo routingChangeInfo);

        void deselectRoute(
                MediaRoute2Info route,
                RoutingSessionInfo sessionInfo,
                RoutingChangeInfo routingChangeInfo);

        void releaseSession(
                boolean shouldReleaseSession,
                boolean shouldNotifyStop,
                RoutingController controller);

        /**
         * Returns the value of {@link RoutingController#wasTransferInitiatedBySelf()} for the app
         * associated with this router.
         */
        boolean wasTransferredBySelf(RoutingSessionInfo sessionInfo);

        /**
         * Registers a {@link SystemSessionOverridesListener}.
         *
         * <p>Passing the same listener to this method twice updates the associated {@code executor}
         * but does not register the same callback twice.
         *
         * @param executor the executor to execute the listener on
         * @param listener The listener to register.
         */
        void registerSystemSessionOverridesListener(
                Executor executor, SystemSessionOverridesListener listener);

        /**
         * Unregisters a {@link SystemSessionOverridesListener}.
         *
         * @param listener The listener to unregister.
         */
        void unregisterSystemSessionOverridesListener(SystemSessionOverridesListener listener);

        /**
         * Returns the set of apps affected by a system session override.
         *
         * @see SystemSessionOverridesListener
         */
        Set<AppId> getSystemSessionOverridesAppIds();
    }

    /**
     * Implements logic specific to proxy {@link MediaRouter2} instances.
     *
     * <p>A proxy {@link MediaRouter2} instance controls the routing of a different package and can
     * be obtained by calling {@link #getInstance(Context, String)}. This requires {@link
     * Manifest.permission#MEDIA_CONTENT_CONTROL MEDIA_CONTENT_CONTROL} permission.
     *
     * <p>Proxy routers behave differently than local routers. See {@link #getInstance(Context,
     * String)} for more details.
     */
    private class ProxyMediaRouter2Impl implements MediaRouter2Impl {
        // Fields originating from MediaRouter2Manager.
        private final IMediaRouter2Manager.Stub mClient;
        private final CopyOnWriteArrayList<MediaRouter2Manager.TransferRequest>
                mTransferRequests = new CopyOnWriteArrayList<>();
        private final RequestTracker<ControllerCreationRequest> mTransferRequestTracker;

        private final CopyOnWriteArraySet<SystemSessionOverridesListenerRecord>
                mSystemSessionOverridesListenerRecords = new CopyOnWriteArraySet<>();
        private final AtomicInteger mScanRequestCount = new AtomicInteger(/* initialValue= */ 0);

        // Fields originating from MediaRouter2.
        @NonNull private final String mClientPackageName;
        @NonNull private final UserHandle mClientUser;
        private final AtomicBoolean mIsScanning = new AtomicBoolean(/* initialValue= */ false);

        @GuardedBy("mLock")
        private final List<InstanceInvalidatedCallbackRecord> mInstanceInvalidatedCallbackRecords =
                new ArrayList<>();

        /**
         * Holds the last snapshot of ids of apps affected by a system session override.
         *
         * <p>Must hold an immutable set to avoid the need for a copy in {@link
         * #getSystemSessionOverridesAppIds}.
         *
         * @see SystemSessionOverridesListener
         */
        @GuardedBy("mLock")
        private Set<AppId> mLastSystemSessionSessionOverridesLocked = Set.of();

        ProxyMediaRouter2Impl(
                @NonNull Context context,
                @NonNull String clientPackageName,
                @NonNull UserHandle user) {
            mClientUser = user;
            mClientPackageName = clientPackageName;
            mClient = new Client();
            mDiscoveryPreference = RouteDiscoveryPreference.EMPTY;
            mTransferRequestTracker =
                    new RequestTracker<>(
                            TRANSFER_TIMEOUT_MS, mHandler, new TransferRequestTrackerCallback());
        }

        public void registerProxyRouter() {
            try {
                mMediaRouterService.registerProxyRouter(
                        mClient,
                        mContext.getApplicationContext().getPackageName(),
                        mClientPackageName,
                        mClientUser);
                initSystemSessionOverridesSnapshot();
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        private void initSystemSessionOverridesSnapshot() throws RemoteException {
            if (!Flags.enableMirroringInMediaRouter2()) {
                return;
            }
            synchronized (mLock) {
                mLastSystemSessionSessionOverridesLocked =
                        Set.copyOf(mMediaRouterService.getSystemSessionOverridesAppIds(mClient));
            }
        }

        public void registerInstanceInvalidatedCallback(
                @Nullable Executor executor, @Nullable Runnable onInstanceInvalidatedListener) {
            if (executor == null || onInstanceInvalidatedListener == null) {
                return;
            }

            InstanceInvalidatedCallbackRecord record =
                    new InstanceInvalidatedCallbackRecord(executor, onInstanceInvalidatedListener);
            synchronized (mLock) {
                if (!mInstanceInvalidatedCallbackRecords.contains(record)) {
                    mInstanceInvalidatedCallbackRecords.add(record);
                }
            }
        }

        @Override
        public void updateScanningState(int scanningState) throws RemoteException {
            mMediaRouterService.updateScanningState(mClient, scanningState);
        }

        @Override
        public void startScan() {
            if (!mIsScanning.getAndSet(true)) {
                if (mScanRequestCount.getAndIncrement() == 0) {
                    try {
                        mMediaRouterService.updateScanningState(
                                mClient, SCANNING_STATE_WHILE_INTERACTIVE);
                    } catch (RemoteException ex) {
                        throw ex.rethrowFromSystemServer();
                    }
                }
            }
        }

        @Override
        public void stopScan() {
            if (mIsScanning.getAndSet(false)) {
                if (mScanRequestCount.updateAndGet(
                                count -> {
                                    if (count == 0) {
                                        throw new IllegalStateException(
                                                "No active scan requests to unregister.");
                                    } else {
                                        return --count;
                                    }
                                })
                        == 0) {
                    try {
                        mMediaRouterService.updateScanningState(
                                mClient, SCANNING_STATE_NOT_SCANNING);
                    } catch (RemoteException ex) {
                        throw ex.rethrowFromSystemServer();
                    }
                }
            }
        }

        @Override
        public String getClientPackageName() {
            return mClientPackageName;
        }

        /**
         * Returns {@code null}. This refers to the package name of the caller app, which is only
         * relevant for local routers.
         */
        @Override
        public String getPackageName() {
            return null;
        }

        @Override
        public RoutingSessionInfo getSystemSessionInfo() {
            return getSystemSessionInfoImpl(
                    mMediaRouterService, mContext.getPackageName(), mClientPackageName);
        }

        /**
         * {@link RouteDiscoveryPreference Discovery preferences} are ignored for proxy routers, as
         * their callbacks should receive events related to the media app's preferences. This is
         * equivalent to setting {@link RouteDiscoveryPreference#EMPTY empty preferences}.
         */
        @Override
        public RouteCallbackRecord createRouteCallbackRecord(
                Executor executor,
                RouteCallback routeCallback,
                RouteDiscoveryPreference preference) {
            return new RouteCallbackRecord(executor, routeCallback, RouteDiscoveryPreference.EMPTY);
        }

        /**
         * No-op. Only local routers communicate directly with {@link
         * com.android.server.media.MediaRouter2ServiceImpl MediaRouter2ServiceImpl} and modify
         * {@link RouteDiscoveryPreference}. Proxy routers receive callbacks from {@link
         * MediaRouter2Manager}.
         */
        @Override
        public void registerRouteCallback() {
            // Do nothing.
        }

        /** No-op. See {@link ProxyMediaRouter2Impl#registerRouteCallback()}. */
        @Override
        public void unregisterRouteCallback() {
            // Do nothing.
        }

        @Override
        public void setRouteListingPreference(@Nullable RouteListingPreference preference) {
            throw new UnsupportedOperationException(
                    "RouteListingPreference cannot be set by a proxy MediaRouter2 instance.");
        }

        @Override
        public void setDeviceSuggestions(@Nullable List<SuggestedDeviceInfo> suggestedDeviceInfo) {
            synchronized (mLock) {
                try {
                    mMediaRouterService.setDeviceSuggestionsWithManager(
                            mClient, suggestedDeviceInfo);
                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }
            }
        }

        @Override
        @NonNull
        public Map<String, List<SuggestedDeviceInfo>> getDeviceSuggestions() {
            synchronized (mLock) {
                try {
                    return mMediaRouterService.getDeviceSuggestionsWithManager(mClient);
                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public void notifyDeviceSuggestionRequested() {
            try {
                mMediaRouterService.onDeviceSuggestionRequestedWithManager(mClient);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        @Override
        public boolean showSystemOutputSwitcher(MediaSession.Token sessionToken) {
            try {
                return mMediaRouterService.showMediaOutputSwitcherWithProxyRouter(mClient,
                        sessionToken);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /** Gets the list of all discovered routes. */
        @Override
        public List<MediaRoute2Info> getAllRoutes() {
            synchronized (mLock) {
                return new ArrayList<>(mRoutes.values());
            }
        }

        /** No-op. Controller hints can only be provided by the media app through a local router. */
        @Override
        public void setOnGetControllerHintsListener(OnGetControllerHintsListener listener) {
            // Do nothing.
        }

        /**
         * Transfers the current {@link RoutingSessionInfo routing session} associated with the
         * router's {@link #mClientPackageName client package name} to a specified {@link
         * MediaRoute2Info route}.
         *
         * <p>This method is equivalent to {@link #transfer(RoutingController, MediaRoute2Info)},
         * except that the {@link RoutingSessionInfo routing session} is resolved based on the
         * router's {@link #mClientPackageName client package name}.
         *
         * @param route The route to transfer to.
         * @param routingChangeInfo information about the start of the media routing session. See
         *     {@link android.media.RoutingChangeInfo}
         */
        @Override
        public void transferTo(
                MediaRoute2Info route, @Nullable RoutingChangeInfo routingChangeInfo) {
            Objects.requireNonNull(route, "route must not be null");

            List<RoutingSessionInfo> sessionInfos = getRoutingSessions();
            RoutingSessionInfo targetSession = sessionInfos.get(sessionInfos.size() - 1);
            routingChangeInfo =
                    routingChangeInfo == null
                            ? new RoutingChangeInfo(
                                    ENTRY_POINT_PROXY_ROUTER_UNSPECIFIED, /* isSuggested= */ false)
                            : routingChangeInfo;
            RoutingController controller = new RoutingController(targetSession);
            transfer(controller, route, routingChangeInfo);
        }

        @Override
        public void stop() {
            List<RoutingSessionInfo> sessionInfos = getRoutingSessions();
            RoutingSessionInfo sessionToRelease = sessionInfos.get(sessionInfos.size() - 1);
            releaseSession(sessionToRelease);
        }

        /**
         * Transfers a {@link RoutingSessionInfo routing session} to a {@link MediaRoute2Info
         * route}.
         *
         * <p>{@link #onTransferred} is called on success or {@link #onTransferFailed} is called if
         * the request fails.
         *
         * <p>This method will default for in-session transfer if the {@link MediaRoute2Info route}
         * is a {@link RoutingSessionInfo#getTransferableRoutes() transferable route}. Otherwise, it
         * will attempt an out-of-session transfer.
         *
         * @param controller The {@link RoutingController routing controller} to transfer.
         * @param route The {@link MediaRoute2Info route} to transfer to.
         * @param routingChangeInfo information about the start of the media routing session. See
         *     {@link android.media.RoutingChangeInfo}
         * @see #transferToRoute(RoutingController, MediaRoute2Info, UserHandle, String,
         *     RoutingChangeInfo)
         * @see #requestCreateSession(RoutingSessionInfo, MediaRoute2Info, RoutingChangeInfo)
         */
        @Override
        @SuppressWarnings("AndroidFrameworkRequiresPermission")
        public void transfer(
                @NonNull RoutingController controller,
                @NonNull MediaRoute2Info route,
                @Nullable RoutingChangeInfo routingChangeInfo) {
            RoutingSessionInfo sessionInfo = controller.getRoutingSessionInfo();

            Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
            Objects.requireNonNull(route, "route must not be null");

            mLog.v("Transferring routing session. session= " + sessionInfo + ", route=" + route);

            boolean isUnknownRoute;
            synchronized (mLock) {
                isUnknownRoute = !mRoutes.containsKey(route.getId());
            }

            if (isUnknownRoute) {
                mLog.w("transfer: Ignoring an unknown route id=" + route.getId());
                this.onTransferFailed(sessionInfo, route);
                return;
            }

            routingChangeInfo =
                    routingChangeInfo == null
                            ? new RoutingChangeInfo(
                                    ENTRY_POINT_PROXY_ROUTER_UNSPECIFIED, /* isSuggested= */ false)
                            : routingChangeInfo;

            // If this call is trying to transfer from an existing system route to a selected system
            // route, we will handle the transfer through as a provider driven transfer in order to
            // update the transfer reason and initiator data.
            boolean isSystemRouteReselection =
                    Flags.enableBuiltInSpeakerRouteSuitabilityStatuses()
                            && sessionInfo.isSystemSession()
                            && route.isSystemRoute()
                            && sessionInfo.getSelectedRoutes().contains(route.getId());
            if (sessionInfo.getTransferableRoutes().contains(route.getId())
                    || isSystemRouteReselection) {
                transferToRoute(
                        controller, route, mClientUser, mClientPackageName, routingChangeInfo);
            } else {
                RoutingSessionInfo systemSessionInfo = mSystemController.getRoutingSessionInfo();
                boolean isTransferFromUserRouteToUnselectedSystemRoute =
                        !sessionInfo.isSystemSession()
                                && route.isSystemRoute()
                                && !systemSessionInfo.getSelectedRoutes().contains(route.getId());
                if (isTransferFromUserRouteToUnselectedSystemRoute) {
                    // During a transfer from a user route to an unselected system route, the system
                    // session must first be transferred to the target system route. Subsequently,
                    // the user route to system route transfer is processed by releasing the user
                    // route.
                    transferToRoute(
                            mSystemController,
                            route,
                            mClientUser,
                            mClientPackageName,
                            routingChangeInfo);
                }
                requestCreateSession(sessionInfo, route, routingChangeInfo);
            }
        }

        /**
         * Requests an in-session transfer of a {@link RoutingSessionInfo routing session} to a
         * {@link MediaRoute2Info route}.
         *
         * <p>The provided {@link MediaRoute2Info route} must be listed in the {@link
         * RoutingSessionInfo routing session's} {@link RoutingSessionInfo#getTransferableRoutes()
         * transferable routes list}. Otherwise, the request will fail.
         *
         * <p>Use {@link #requestCreateSession(RoutingSessionInfo, MediaRoute2Info,
         * RoutingChangeInfo)} to request an out-of-session transfer.
         *
         * @param controller The {@link RoutingController routing controller} to transfer.
         * @param route The {@link MediaRoute2Info route} to transfer to. Must be one of the {@link
         *     RoutingSessionInfo routing session's} {@link
         *     RoutingSessionInfo#getTransferableRoutes() transferable routes}.
         * @param routingChangeInfo information about the start of the media routing session. See
         *     {@link android.media.RoutingChangeInfo}
         */
        @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
        private void transferToRoute(
                @NonNull RoutingController controller,
                @NonNull MediaRoute2Info route,
                @NonNull UserHandle transferInitiatorUserHandle,
                @NonNull String transferInitiatorPackageName,
                @NonNull RoutingChangeInfo routingChangeInfo) {

            int requestId = 0;
            if (Flags.reduceTwoWayBinderCallsInMediaRouter2()) {
                requestId = mNextRequestId.getAndIncrement();
                ControllerCreationRequest request =
                        new ControllerCreationRequest(
                                requestId, MANAGER_REQUEST_ID_NONE, route, controller);
                mLog.i("transfer request with ID = " + request.mRequestId + " tracking starts");
                mTransferRequestTracker.track(request);
            } else {
                requestId = createTransferRequest(controller.getRoutingSessionInfo(), route);
            }

            try {
                mMediaRouterService.transferToRouteWithManager(
                        mClient,
                        requestId,
                        controller.getRoutingSessionInfo().getId(),
                        route,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName,
                        routingChangeInfo);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Requests an out-of-session transfer of a {@link RoutingSessionInfo routing session} to a
         * {@link MediaRoute2Info route}.
         *
         * <p>This request creates a new {@link RoutingSessionInfo routing session} regardless of
         * whether the {@link MediaRoute2Info route} is one of the {@link RoutingSessionInfo current
         * session's} {@link RoutingSessionInfo#getTransferableRoutes() transferable routes}.
         *
         * <p>Use {@link #transferToRoute(RoutingController, MediaRoute2Info, UserHandle, String,
         * RoutingChangeInfo)} to request an in-session transfer.
         *
         * @param oldSession The {@link RoutingSessionInfo routing session} to transfer.
         * @param route The {@link MediaRoute2Info route} to transfer to.
         * @param routingChangeInfo information about the start of the media routing session. See
         *     {@link android.media.RoutingChangeInfo}
         */
        private void requestCreateSession(
                @NonNull RoutingSessionInfo oldSession,
                @NonNull MediaRoute2Info route,
                @NonNull RoutingChangeInfo routingChangeInfo) {
            if (TextUtils.isEmpty(oldSession.getClientPackageName())) {
                mLog.w("requestCreateSession: Can't create a session without package name.");
                this.onTransferFailed(oldSession, route);
                return;
            }

            int requestId = createTransferRequest(oldSession, route);

            try {
                mMediaRouterService.requestCreateSessionWithManager(
                        mClient, requestId, oldSession, routingChangeInfo, route);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        @Override
        public List<RoutingController> getControllers() {
            List<RoutingController> result = new ArrayList<>();

            /* Unlike local MediaRouter2 instances, controller instances cannot be kept because
            transfer events initiated from other apps will not come through manager.*/
            List<RoutingSessionInfo> sessions = getRoutingSessions();
            for (RoutingSessionInfo session : sessions) {
                RoutingController controller;
                if (session.isSystemSession()) {
                    mSystemController.setRoutingSessionInfo(session);
                    controller = mSystemController;
                } else {
                    controller = new RoutingController(session);
                }
                result.add(controller);
            }
            return result;
        }

        /**
         * Requests a volume change for a {@link MediaRoute2Info route}.
         *
         * <p>It may have no effect if the {@link MediaRoute2Info route} is not currently selected.
         *
         * @param volume The desired volume value between 0 and {@link
         *     MediaRoute2Info#getVolumeMax()} (inclusive).
         */
        @Override
        public void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
            if (route.getVolumeHandling() == MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
                mLog.w("setRouteVolume: the route has fixed volume. Ignoring.");
                return;
            }
            if (volume < 0 || volume > route.getVolumeMax()) {
                mLog.w("setRouteVolume: the target volume is out of range. Ignoring");
                return;
            }

            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.setRouteVolumeWithManager(mClient, requestId, route, volume);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Requests a volume change for a {@link RoutingSessionInfo routing session}.
         *
         * @param volume The desired volume value between 0 and {@link
         *     RoutingSessionInfo#getVolumeMax()} (inclusive).
         */
        @Override
        public void setSessionVolume(int volume, RoutingSessionInfo sessionInfo) {
            Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

            if (sessionInfo.getVolumeHandling() == MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
                mLog.w("setSessionVolume: the route has fixed volume. Ignoring.");
                return;
            }
            if (volume < 0 || volume > sessionInfo.getVolumeMax()) {
                mLog.w("setSessionVolume: the target volume is out of range. Ignoring");
                return;
            }

            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.setSessionVolumeWithManager(
                        mClient, requestId, sessionInfo.getId(), volume);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Returns an exact copy of the routes. Individual {@link RouteDiscoveryPreference
         * preferences} do not apply to proxy routers.
         */
        @Override
        public List<MediaRoute2Info> filterRoutesWithIndividualPreference(
                List<MediaRoute2Info> routes, RouteDiscoveryPreference discoveryPreference) {
            // Individual discovery preferences do not apply for the system router.
            return new ArrayList<>(routes);
        }

        /**
         * Adds a {@linkplain MediaRoute2Info route} to the routing session's {@linkplain
         * RoutingSessionInfo#getSelectedRoutes() selected route list}.
         *
         * <p>Upon success, {@link #onSessionUpdated(RoutingSessionInfo)} is invoked. Failed
         * requests are silently ignored.
         *
         * <p>The {@linkplain RoutingSessionInfo#getSelectedRoutes() selected routes list} of a
         * routing session contains the group of devices playing media for that {@linkplain
         * RoutingSessionInfo session}.
         *
         * <p>The given route must not be already selected and must be listed in the session's
         * {@linkplain RoutingSessionInfo#getSelectableRoutes() selectable routes}. Otherwise, the
         * request will be ignored.
         *
         * <p>This method should not be confused with {@link #transfer(RoutingController,
         * MediaRoute2Info)}.
         *
         * @see RoutingSessionInfo#getSelectedRoutes()
         * @see RoutingSessionInfo#getSelectableRoutes()
         */
        @Override
        public void selectRoute(
                MediaRoute2Info route,
                RoutingSessionInfo sessionInfo,
                RoutingChangeInfo routingChangeInfo) {
            Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
            Objects.requireNonNull(route, "route must not be null");

            if (sessionInfo.getSelectedRoutes().contains(route.getId())) {
                mLog.w("Ignoring selecting a route that is already selected. route=" + route);
                return;
            }

            if (!sessionInfo.getSelectableRoutes().contains(route.getId())) {
                mLog.w("Ignoring selecting a non-selectable route=" + route);
                return;
            }

            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.selectRouteWithManager(
                        mClient, requestId, sessionInfo.getId(), route, routingChangeInfo);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Removes a route from a session's {@linkplain RoutingSessionInfo#getSelectedRoutes()
         * selected routes list}. Calls {@link #onSessionUpdated(RoutingSessionInfo)} on success.
         *
         * <p>The given route must be selected and must be listed in the session's {@linkplain
         * RoutingSessionInfo#getDeselectableRoutes() deselectable route list}. Otherwise, the
         * request will be ignored.
         *
         * @see RoutingSessionInfo#getSelectedRoutes()
         * @see RoutingSessionInfo#getDeselectableRoutes()
         */
        @Override
        public void deselectRoute(
                MediaRoute2Info route,
                RoutingSessionInfo sessionInfo,
                RoutingChangeInfo routingChangeInfo) {
            Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
            Objects.requireNonNull(route, "route must not be null");

            if (!sessionInfo.getSelectedRoutes().contains(route.getId())) {
                mLog.w("Ignoring deselecting a route that is not selected. route=" + route);
                return;
            }

            if (!sessionInfo.getDeselectableRoutes().contains(route.getId())) {
                mLog.w("Ignoring deselecting a non-deselectable route=" + route);
                return;
            }

            try {
                int requestId = mNextRequestId.getAndIncrement();
                if (routingChangeInfo == null) {
                    routingChangeInfo =
                            new RoutingChangeInfo(
                                    ENTRY_POINT_PROXY_ROUTER_UNSPECIFIED, /* isSuggested= */ false);
                }
                mMediaRouterService.deselectRouteWithManager(
                        mClient, requestId, sessionInfo.getId(), route, routingChangeInfo);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        @Override
        public void releaseSession(
                boolean shouldReleaseSession,
                boolean shouldNotifyStop,
                RoutingController controller) {
            releaseSession(controller.getRoutingSessionInfo());
        }

        @Override
        public boolean wasTransferredBySelf(RoutingSessionInfo sessionInfo) {
            UserHandle transferInitiatorUserHandle = sessionInfo.getTransferInitiatorUserHandle();
            String transferInitiatorPackageName = sessionInfo.getTransferInitiatorPackageName();
            return Objects.equals(mClientUser, transferInitiatorUserHandle)
                    && Objects.equals(mClientPackageName, transferInitiatorPackageName);
        }

        @Override
        public void registerSystemSessionOverridesListener(
                Executor executor, SystemSessionOverridesListener listener) {
            var record = new SystemSessionOverridesListenerRecord(executor, listener);
            // We remove it first so as to ensure the latest provided executor for a given listener
            // is used.
            mSystemSessionOverridesListenerRecords.remove(record);
            mSystemSessionOverridesListenerRecords.add(record);
        }

        @Override
        public void unregisterSystemSessionOverridesListener(
                SystemSessionOverridesListener listener) {
            // We use a placeholder executor to keep the field non-nullable, but it will be ignored
            // in equality checks.
            mSystemSessionOverridesListenerRecords.remove(
                    new SystemSessionOverridesListenerRecord(
                            /* executor= */ Runnable::run, listener));
        }

        @Override
        public Set<AppId> getSystemSessionOverridesAppIds() {
            synchronized (mLock) {
                return mLastSystemSessionSessionOverridesLocked;
            }
        }

        /**
         * Retrieves the system session info for the given package.
         *
         * <p>The returned routing session is guaranteed to have a non-null {@link
         * RoutingSessionInfo#getClientPackageName() client package name}.
         *
         * <p>Extracted into a static method to allow calling this from the constructor.
         */
        /* package */ static RoutingSessionInfo getSystemSessionInfoImpl(
                @NonNull IMediaRouterService service,
                @NonNull String callerPackageName,
                @NonNull String clientPackageName) {
            try {
                return service.getSystemSessionInfoForPackage(callerPackageName, clientPackageName);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Requests the release of a {@linkplain RoutingSessionInfo routing session}. Calls {@link
         * #onSessionReleasedOnHandler(RoutingSessionInfo)} on success.
         *
         * <p>Once released, a routing session ignores incoming requests.
         */
        private void releaseSession(@NonNull RoutingSessionInfo sessionInfo) {
            Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.releaseSessionWithManager(
                        mClient, requestId, sessionInfo.getId());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        private int createTransferRequest(
                @NonNull RoutingSessionInfo session, @NonNull MediaRoute2Info route) {
            int requestId = mNextRequestId.getAndIncrement();
            MediaRouter2Manager.TransferRequest transferRequest =
                    new MediaRouter2Manager.TransferRequest(requestId, session, route);
            mTransferRequests.add(transferRequest);

            Message timeoutMessage =
                    obtainMessage(
                            ProxyMediaRouter2Impl::handleTransferTimeout, this, transferRequest);
            mHandler.sendMessageDelayed(timeoutMessage, TRANSFER_TIMEOUT_MS);
            return requestId;
        }

        private void handleTransferTimeout(MediaRouter2Manager.TransferRequest request) {
            boolean removed = mTransferRequests.remove(request);
            if (removed) {
                this.onTransferFailed(request.mOldSessionInfo, request.mTargetRoute);
            }
        }

        /**
         * Returns the {@linkplain RoutingSessionInfo routing sessions} associated with {@link
         * #mClientPackageName}. The first element of the returned list is the system routing
         * session.
         */
        @NonNull
        private List<RoutingSessionInfo> getRoutingSessions() {
            List<RoutingSessionInfo> sessions = new ArrayList<>();
            if (Flags.reduceTwoWayBinderCallsInMediaRouter2()) {
                sessions.add(mSystemController.getRoutingSessionInfo());
            } else {
                sessions.add(getSystemSessionInfo());
            }

            List<RoutingSessionInfo> remoteSessions;
            try {
                remoteSessions = mMediaRouterService.getRemoteSessions(mClient);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }

            for (RoutingSessionInfo sessionInfo : remoteSessions) {
                if (TextUtils.equals(sessionInfo.getClientPackageName(), mClientPackageName)) {
                    sessions.add(sessionInfo);
                }
            }
            return sessions;
        }

        private void onTransferred(
                @NonNull RoutingSessionInfo oldSession, @NonNull RoutingSessionInfo newSession) {
            if (!isSessionRelatedToTargetPackageName(oldSession)
                    || !isSessionRelatedToTargetPackageName(newSession)) {
                return;
            }

            RoutingController oldController;
            if (oldSession.isSystemSession()) {
                mSystemController.setRoutingSessionInfo(
                        ensureClientPackageNameForSystemSession(oldSession, mClientPackageName));
                oldController = mSystemController;
            } else {
                oldController = new RoutingController(oldSession);
            }

            RoutingController newController;
            if (newSession.isSystemSession()) {
                mSystemController.setRoutingSessionInfo(
                        ensureClientPackageNameForSystemSession(newSession, mClientPackageName));
                newController = mSystemController;
            } else {
                newController = new RoutingController(newSession);
            }

            notifyTransfer(oldController, newController);
        }

        private void onTransferFailed(
                @NonNull RoutingSessionInfo session, @NonNull MediaRoute2Info route) {
            if (!isSessionRelatedToTargetPackageName(session)) {
                return;
            }
            notifyTransferFailure(route);
        }

        private void onSessionUpdated(
                @NonNull RoutingSessionInfo session, boolean shouldShowVolumeUi) {
            if (!isSessionRelatedToTargetPackageName(session)) {
                return;
            }

            RoutingController controller;
            if (session.isSystemSession()) {
                mSystemController.setRoutingSessionInfo(
                        ensureClientPackageNameForSystemSession(session, mClientPackageName));
                controller = mSystemController;
            } else {
                controller = new RoutingController(session);
            }
            notifyControllerUpdated(controller, shouldShowVolumeUi);
        }

        /**
         * Returns {@code true} if the session is a system session or if its client package name
         * matches the proxy router's target package name.
         */
        private boolean isSessionRelatedToTargetPackageName(@NonNull RoutingSessionInfo session) {
            return session.isSystemSession()
                    || TextUtils.equals(getClientPackageName(), session.getClientPackageName());
        }

        private void onSessionCreatedOnHandler(
                int requestId, @NonNull RoutingSessionInfo sessionInfo) {
            MediaRouter2Manager.TransferRequest matchingRequest = null;
            for (MediaRouter2Manager.TransferRequest request : mTransferRequests) {
                if (request.mRequestId == requestId) {
                    matchingRequest = request;
                    break;
                }
            }

            if (matchingRequest == null) {
                return;
            }

            mTransferRequests.remove(matchingRequest);

            MediaRoute2Info requestedRoute = matchingRequest.mTargetRoute;

            if (!sessionInfo.getSelectedRoutes().contains(requestedRoute.getId())) {
                mLog.w(
                        "The session does not contain the requested route. "
                                + "(requestedRouteId="
                                + requestedRoute.getId()
                                + ", actualRoutes="
                                + sessionInfo.getSelectedRoutes()
                                + ")");
                this.onTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            } else if (!TextUtils.equals(
                    requestedRoute.getProviderId(), sessionInfo.getProviderId())) {
                mLog.w(
                        "The session's provider ID does not match the requested route's. "
                                + "(requested route's providerId="
                                + requestedRoute.getProviderId()
                                + ", actual providerId="
                                + sessionInfo.getProviderId()
                                + ")");
                this.onTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            } else {
                this.onTransferred(matchingRequest.mOldSessionInfo, sessionInfo);
            }
        }

        private void onSessionUpdatedOnHandler(
                @NonNull RoutingSessionInfo updatedSession, boolean shouldShowVolumeUi) {
            if (Flags.reduceTwoWayBinderCallsInMediaRouter2()) {
                ControllerCreationRequest request = mTransferRequestTracker.mRequest;
                if (request != null) {
                    String sessionId = request.mOldController.getRoutingSessionInfo().getId();
                    if (TextUtils.equals(sessionId, updatedSession.getId())
                            && updatedSession
                                    .getSelectedRoutes()
                                    .contains(request.mRoute.getId())) {
                        mTransferRequestTracker.complete();
                    }
                }
            } else {
                for (MediaRouter2Manager.TransferRequest request : mTransferRequests) {
                    String sessionId = request.mOldSessionInfo.getId();
                    if (!TextUtils.equals(sessionId, updatedSession.getId())) {
                        continue;
                    }

                    if (updatedSession.getSelectedRoutes().contains(request.mTargetRoute.getId())) {
                        mTransferRequests.remove(request);
                        break;
                    }
                }
            }
            this.onSessionUpdated(updatedSession, shouldShowVolumeUi);
        }

        private void onSessionReleasedOnHandler(@NonNull RoutingSessionInfo session) {
            if (session.isSystemSession()) {
                mLog.e("onSessionReleasedOnHandler: Called on system session. Ignoring.");
                return;
            }

            if (!TextUtils.equals(getClientPackageName(), session.getClientPackageName())) {
                return;
            }

            notifyStop(new RoutingController(session, RoutingController.CONTROLLER_STATE_RELEASED));
        }

        private void onDiscoveryPreferenceChangedOnHandler(
                @NonNull String packageName, @Nullable RouteDiscoveryPreference preference) {
            if (!TextUtils.equals(getClientPackageName(), packageName)) {
                return;
            }

            if (preference == null) {
                return;
            }
            synchronized (mLock) {
                if (Objects.equals(preference, mDiscoveryPreference)) {
                    return;
                }
                mDiscoveryPreference = preference;
                updateFilteredRoutesLocked();
            }
            notifyPreferredFeaturesChanged(preference.getPreferredFeatures());
            if (Flags.enableRouteVisibilityControlCompatFixes()) {
                dispatchFilteredRoutesUpdatedOnHandler(mFilteredRoutes);
                dispatchControllerUpdatedIfNeededOnHandler(mRoutes);
            }
        }

        private void onRouteListingPreferenceChangedOnHandler(
                @NonNull String packageName,
                @Nullable RouteListingPreference routeListingPreference) {
            if (!TextUtils.equals(getClientPackageName(), packageName)) {
                return;
            }

            synchronized (mLock) {
                if (Objects.equals(mRouteListingPreference, routeListingPreference)) {
                    return;
                }

                mRouteListingPreference = routeListingPreference;
            }

            notifyRouteListingPreferenceUpdated(routeListingPreference);
        }

        private void onDeviceSuggestionsChangeHandler(
                @NonNull String packageName,
                @NonNull String suggestingPackageName,
                @Nullable List<SuggestedDeviceInfo> suggestedDeviceInfo) {
            if (!TextUtils.equals(getClientPackageName(), packageName)) {
                return;
            }
            synchronized (mLock) {
                if (Objects.equals(
                        mSuggestedDeviceInfo.get(suggestingPackageName), suggestedDeviceInfo)) {
                    return;
                }
                mSuggestedDeviceInfo.put(suggestingPackageName, suggestedDeviceInfo);
            }
            notifyDeviceSuggestionsUpdated(suggestingPackageName, suggestedDeviceInfo);
        }

        private void notifyDeviceSuggestionRequestedHandler() {
            notifyCallbacksDeviceSuggestionRequested();
        }

        private void onRequestFailedOnHandler(int requestId, int reason) {
            MediaRouter2Manager.TransferRequest matchingRequest = null;
            for (MediaRouter2Manager.TransferRequest request : mTransferRequests) {
                if (request.mRequestId == requestId) {
                    matchingRequest = request;
                    break;
                }
            }

            if (matchingRequest != null) {
                mTransferRequests.remove(matchingRequest);
                onTransferFailed(matchingRequest.mOldSessionInfo, matchingRequest.mTargetRoute);
            } else {
                notifyRequestFailed(reason);
            }
        }

        private void onInvalidateInstanceOnHandler() {
            mLog.w(
                    "MEDIA_ROUTING_CONTROL has been revoked for this package. Invalidating"
                            + " instance.");
            // After this block, all following getInstance() calls should throw a SecurityException,
            // so no new onInstanceInvalidatedListeners can be registered to this instance.
            synchronized (sSystemRouterLock) {
                AppId key = new AppId(mClientPackageName, mClientUser);
                sAppToProxyRouterMap.remove(key);
            }

            synchronized (mLock) {
                for (InstanceInvalidatedCallbackRecord record :
                        mInstanceInvalidatedCallbackRecords) {
                    record.executor.execute(record.runnable);
                }
            }
            mRouteCallbackRecords.clear();
            mControllerCallbackRecords.clear();
            mTransferCallbackRecords.clear();
        }

        private void notifySystemSessionOverridesChangedOnHandler(List<AppId> appsWithOverrides) {
            var appsWithOverridesAsSet = Set.copyOf(appsWithOverrides);
            synchronized (mLock) {
                mLastSystemSessionSessionOverridesLocked = appsWithOverridesAsSet;
            }
            for (var record : mSystemSessionOverridesListenerRecords) {
                record.mExecutor.execute(
                        () ->
                                record.mSystemSessionOverridesListener
                                        .onSystemSessionOverridesChanged(appsWithOverridesAsSet));
            }
        }

        private class Client extends IMediaRouter2Manager.Stub {

            @Override
            public void notifySessionCreated(int requestId, RoutingSessionInfo routingSessionInfo) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onSessionCreatedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                requestId,
                                routingSessionInfo));
            }

            @Override
            public void notifySessionUpdated(
                    RoutingSessionInfo routingSessionInfo, boolean shouldShowVolumeUi) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onSessionUpdatedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                routingSessionInfo,
                                shouldShowVolumeUi));
            }

            @Override
            public void notifySessionReleased(RoutingSessionInfo routingSessionInfo) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onSessionReleasedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                routingSessionInfo));
            }

            @Override
            public void notifyDiscoveryPreferenceChanged(
                    String packageName, RouteDiscoveryPreference routeDiscoveryPreference) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onDiscoveryPreferenceChangedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                packageName,
                                routeDiscoveryPreference));
            }

            @Override
            public void notifyRouteListingPreferenceChange(
                    String packageName, RouteListingPreference routeListingPreference) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onRouteListingPreferenceChangedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                packageName,
                                routeListingPreference));
            }

            @Override
            public void notifyDeviceSuggestionsUpdated(
                    String packageName,
                    String suggestingPackageName,
                    @Nullable List<SuggestedDeviceInfo> deviceSuggestions) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onDeviceSuggestionsChangeHandler,
                                ProxyMediaRouter2Impl.this,
                                packageName,
                                suggestingPackageName,
                                deviceSuggestions));
            }

            @Override
            public void notifyDeviceSuggestionRequested() {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::notifyDeviceSuggestionRequestedHandler,
                                ProxyMediaRouter2Impl.this));
            }

            @Override
            public void notifyRoutesUpdated(List<MediaRoute2Info> routes,
                    List<String> missingPermissions) {
                mHandler.sendMessage(
                        obtainMessage(
                                MediaRouter2::updateRoutesOnHandler, MediaRouter2.this, routes,
                                missingPermissions));
            }

            @Override
            public void notifyRequestFailed(int requestId, int reason) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onRequestFailedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                requestId,
                                reason));
            }

            @Override
            public void invalidateInstance() {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onInvalidateInstanceOnHandler,
                                ProxyMediaRouter2Impl.this));
            }

            @Override
            public void notifySystemSessionOverridesChanged(List<AppId> appsWithOverrides) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::notifySystemSessionOverridesChangedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                appsWithOverrides));
            }
        }

        private class TransferRequestTrackerCallback
                extends RequestTracker.Callback<ControllerCreationRequest> {
            @Override
            public void onRequestCompleted(@NonNull ControllerCreationRequest request) {
                mLog.i("transfer request with ID = " + request.mRequestId + " is completed");
            }

            @Override
            public void onRequestTimeout(@NonNull ControllerCreationRequest request) {
                mLog.i("transfer request with ID = " + request.mRequestId + " is timed out");
                onTransferFailed(request.mOldController.getRoutingSessionInfo(), request.mRoute);
            }
        }
    }

    /**
     * Implements logic specific to local {@link MediaRouter2} instances.
     *
     * <p>Local routers allow an app to control its own routing without any special permissions.
     * Apps can obtain an instance by calling {@link #getInstance(Context)}.
     */
    private class LocalMediaRouter2Impl implements MediaRouter2Impl {
        private final String mPackageName;

        LocalMediaRouter2Impl(@NonNull String packageName) {
            mPackageName = packageName;
        }

        /**
         * No-op. Local routers cannot explicitly control route scanning.
         *
         * <p>Local routers can control scanning indirectly through {@link
         * #registerRouteCallback(Executor, RouteCallback, RouteDiscoveryPreference)}.
         */
        @Override
        public void startScan() {
            // Do nothing.
        }

        /**
         * No-op. Local routers cannot explicitly control route scanning.
         *
         * <p>Local routers can control scanning indirectly through {@link
         * #registerRouteCallback(Executor, RouteCallback, RouteDiscoveryPreference)}.
         */
        @Override
        public void stopScan() {
            // Do nothing.
        }

        @Override
        @GuardedBy("mLock")
        public void updateScanningState(int scanningState) throws RemoteException {
            if (scanningState != SCANNING_STATE_NOT_SCANNING) {
                registerRouterStubIfNeededLocked();
            }
            mMediaRouterService.updateScanningStateWithRouter2(mStub, scanningState);
            if (scanningState == SCANNING_STATE_NOT_SCANNING) {
                unregisterRouterStubIfNeededLocked(/* isScanningStopping */ true);
            }
        }

        @Override
        public String getClientPackageName() {
            // TODO: b/362507305 - Merge getPackageName and getClientPackageName so that they both
            // return the package name of the client app, once enableMirroringInMediaRouter2 reaches
            // nextfood.
            return Flags.enableMirroringInMediaRouter2() ? mPackageName : null;
        }

        @Override
        public String getPackageName() {
            return mPackageName;
        }

        @Override
        public RoutingSessionInfo getSystemSessionInfo() {
            RoutingSessionInfo currentSystemSessionInfo = null;
            try {
                currentSystemSessionInfo = ensureClientPackageNameForSystemSession(
                        mMediaRouterService.getSystemSessionInfo(), mContext.getPackageName());
            } catch (RemoteException ex) {
                ex.rethrowFromSystemServer();
            }
            return currentSystemSessionInfo;
        }

        @Override
        public RouteCallbackRecord createRouteCallbackRecord(
                Executor executor,
                RouteCallback routeCallback,
                RouteDiscoveryPreference preference) {
            return new RouteCallbackRecord(executor, routeCallback, preference);
        }

        @Override
        public void registerRouteCallback() {
            synchronized (mLock) {
                try {
                    registerRouterStubIfNeededLocked();

                    if (updateDiscoveryPreferenceIfNeededLocked()) {
                        mMediaRouterService.setDiscoveryRequestWithRouter2(
                                mStub, mDiscoveryPreference);
                    }
                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public void unregisterRouteCallback() {
            synchronized (mLock) {
                if (mStub == null) {
                    return;
                }

                try {
                    if (updateDiscoveryPreferenceIfNeededLocked()) {
                        mMediaRouterService.setDiscoveryRequestWithRouter2(
                                mStub, mDiscoveryPreference);
                    }

                    unregisterRouterStubIfNeededLocked(/* isScanningStopping */ false);

                } catch (RemoteException ex) {
                    mLog.e("unregisterRouteCallback: Unable to set discovery request.", ex);
                }
            }
        }

        @Override
        public void setRouteListingPreference(@Nullable RouteListingPreference preference) {
            synchronized (mLock) {
                if (Objects.equals(mRouteListingPreference, preference)) {
                    // Nothing changed. We return early to save a call to the system server.
                    return;
                }
                mRouteListingPreference = preference;
                try {
                    registerRouterStubIfNeededLocked();
                    mMediaRouterService.setRouteListingPreference(mStub, mRouteListingPreference);
                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }
                notifyRouteListingPreferenceUpdated(preference);
            }
        }

        @Override
        public void setDeviceSuggestions(@Nullable List<SuggestedDeviceInfo> deviceSuggestions) {
            synchronized (mLock) {
                try {
                    registerRouterStubIfNeededLocked();
                    mMediaRouterService.setDeviceSuggestionsWithRouter2(mStub, deviceSuggestions);
                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }
            }
        }

        @Override
        @NonNull
        public Map<String, List<SuggestedDeviceInfo>> getDeviceSuggestions() {
            synchronized (mLock) {
                try {
                    return mMediaRouterService.getDeviceSuggestionsWithRouter2(mStub);
                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public void notifyDeviceSuggestionRequested() {
            // no-op, local routers can not call this method.
        }

        @Override
        public boolean showSystemOutputSwitcher(@Nullable MediaSession.Token sessionToken) {
            synchronized (mLock) {
                try {
                    return mMediaRouterService.showMediaOutputSwitcherWithRouter2(mPackageName,
                            sessionToken);
                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }
            }
            return false;
        }

        /**
         * Returns {@link Collections#emptyList()}. Local routes can only access routes related to
         * their {@link RouteDiscoveryPreference} through {@link #getRoutes()}.
         */
        @Override
        public List<MediaRoute2Info> getAllRoutes() {
            return Collections.emptyList();
        }

        @Override
        public void setOnGetControllerHintsListener(OnGetControllerHintsListener listener) {
            mOnGetControllerHintsListener = listener;
        }

        @Override
        public void transferTo(
                MediaRoute2Info route, @Nullable RoutingChangeInfo routingChangeInfo) {
            mLog.v("Transferring to route: " + route);

            boolean routeFound;
            synchronized (mLock) {
                // TODO: Check thread-safety
                routeFound = mRoutes.containsKey(route.getId());
            }
            if (!routeFound) {
                notifyTransferFailure(route);
                return;
            }

            routingChangeInfo =
                    routingChangeInfo == null
                            ? new RoutingChangeInfo(
                                    ENTRY_POINT_LOCAL_ROUTER_UNSPECIFIED, /* isSuggested= */ false)
                            : routingChangeInfo;
            RoutingController controller = getCurrentController();
            if (!controller.tryTransferWithinProvider(route, routingChangeInfo)) {
                requestCreateController(
                        controller, route, MANAGER_REQUEST_ID_NONE, routingChangeInfo);
            }
        }

        @Override
        public void stop() {
            getCurrentController().release();
        }

        /**
         * No-op. Local routers cannot request transfers of specific {@link RoutingSessionInfo}.
         * This operation is only available to proxy routers.
         *
         * <p>Local routers can only transfer the current {@link RoutingSessionInfo} using {@link
         * #transferTo(MediaRoute2Info)}.
         */
        @Override
        public void transfer(
                @NonNull RoutingController controller,
                @NonNull MediaRoute2Info route,
                @Nullable RoutingChangeInfo routingChangeInfo) {
            // Do nothing.
        }

        @Override
        public List<RoutingController> getControllers() {
            List<RoutingController> result = new ArrayList<>();

            result.add(0, mSystemController);
            synchronized (mLock) {
                result.addAll(mNonSystemRoutingControllers.values());
            }
            return result;
        }

        /** Local routers cannot modify the volume of specific routes. */
        @Override
        public void setRouteVolume(MediaRoute2Info route, int volume) {
            throw new UnsupportedOperationException(
                    "setRouteVolume is only supported by proxy routers. See javadoc.");
            // If this API needs to be public, use IMediaRouterService#setRouteVolumeWithRouter2()
        }

        @Override
        public void setSessionVolume(int volume, RoutingSessionInfo sessionInfo) {
            MediaRouter2Stub stub;
            synchronized (mLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    mMediaRouterService.setSessionVolumeWithRouter2(
                            stub, sessionInfo.getId(), volume);
                } catch (RemoteException ex) {
                    mLog.e("setVolume: Failed to deliver request.", ex);
                }
            }
        }

        @Override
        public List<MediaRoute2Info> filterRoutesWithIndividualPreference(
                List<MediaRoute2Info> routes, RouteDiscoveryPreference discoveryPreference) {
            List<MediaRoute2Info> filteredRoutes = new ArrayList<>();
            for (MediaRoute2Info route : routes) {
                if (!route.hasAnyFeatures(discoveryPreference.getPreferredFeatures())) {
                    continue;
                }
                if (!discoveryPreference.getAllowedPackages().isEmpty()
                        && (route.getProviderPackageName() == null
                                || !discoveryPreference
                                        .getAllowedPackages()
                                        .contains(route.getProviderPackageName()))) {
                    continue;
                }
                filteredRoutes.add(route);
            }
            return filteredRoutes;
        }

        @Override
        public void selectRoute(
                MediaRoute2Info route,
                RoutingSessionInfo sessionInfo,
                RoutingChangeInfo routingChangeInfo) {
            MediaRouter2Stub stub;
            synchronized (mLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    if (routingChangeInfo == null) {
                        routingChangeInfo =
                                new RoutingChangeInfo(
                                        ENTRY_POINT_LOCAL_ROUTER_UNSPECIFIED,
                                        /* isSuggested= */ false);
                    }
                    mMediaRouterService.selectRouteWithRouter2(
                            stub, sessionInfo.getId(), route, routingChangeInfo);
                } catch (RemoteException ex) {
                    mLog.e("Unable to select route for session.", ex);
                }
            }
        }

        @Override
        public void deselectRoute(
                MediaRoute2Info route,
                RoutingSessionInfo sessionInfo,
                RoutingChangeInfo routingChangeInfo) {
            MediaRouter2Stub stub;
            synchronized (mLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    if (routingChangeInfo == null) {
                        routingChangeInfo =
                                new RoutingChangeInfo(
                                        ENTRY_POINT_LOCAL_ROUTER_UNSPECIFIED,
                                        /* isSuggested= */ false);
                    }
                    mMediaRouterService.deselectRouteWithRouter2(
                            stub, sessionInfo.getId(), route, routingChangeInfo);
                } catch (RemoteException ex) {
                    mLog.e("Unable to deselect route from session.", ex);
                }
            }
        }

        @Override
        public void releaseSession(
                boolean shouldReleaseSession,
                boolean shouldNotifyStop,
                RoutingController controller) {
            synchronized (mLock) {
                mNonSystemRoutingControllers.remove(controller.getId(), controller);

                if (shouldReleaseSession && mStub != null) {
                    try {
                        mMediaRouterService.releaseSessionWithRouter2(mStub, controller.getId());
                    } catch (RemoteException ex) {
                        ex.rethrowFromSystemServer();
                    }
                }

                if (shouldNotifyStop) {
                    mHandler.sendMessage(
                            obtainMessage(MediaRouter2::notifyStop, MediaRouter2.this, controller));
                }

                try {
                    unregisterRouterStubIfNeededLocked(/* isScanningStopping */ false);
                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }

            }
        }

        @Override
        public boolean wasTransferredBySelf(RoutingSessionInfo sessionInfo) {
            UserHandle transferInitiatorUserHandle = sessionInfo.getTransferInitiatorUserHandle();
            String transferInitiatorPackageName = sessionInfo.getTransferInitiatorPackageName();
            return Objects.equals(Process.myUserHandle(), transferInitiatorUserHandle)
                    && Objects.equals(mContext.getPackageName(), transferInitiatorPackageName);
        }

        @Override
        public void registerSystemSessionOverridesListener(
                Executor executor, SystemSessionOverridesListener listener) {
            throw new UnsupportedOperationException(
                    "registerSystemSessionOverridesListener is only supported on proxy routers.");
        }

        @Override
        public void unregisterSystemSessionOverridesListener(
                SystemSessionOverridesListener listener) {
            throw new UnsupportedOperationException(
                    "unregisterSystemSessionOverridesListener is only supported on proxy routers.");
        }

        @Override
        public Set<AppId> getSystemSessionOverridesAppIds() {
            throw new UnsupportedOperationException(
                    "getAppsWithSystemSessionOverrides is only supported on proxy routers.");
        }

        @GuardedBy("mLock")
        private void registerRouterStubIfNeededLocked() throws RemoteException {
            if (mStub == null) {
                MediaRouter2Stub stub = new MediaRouter2Stub();
                mMediaRouterService.registerRouter2(stub, mPackageName);
                mStub = stub;
            }
        }

        @GuardedBy("mLock")
        private void unregisterRouterStubIfNeededLocked(boolean isScanningStopping)
                throws RemoteException {
            if (mStub != null
                    && mRouteCallbackRecords.isEmpty()
                    && mNonSystemRoutingControllers.isEmpty()
                    && (mScanRequestsMap.size() == 0 || isScanningStopping)) {
                mMediaRouterService.unregisterRouter2(mStub);
                mStub = null;
            }
        }
    }

    private static class Logger {
        private final String mPrefix;

        private Logger(String prefix) {
            mPrefix = prefix;
        }

        private void e(String message) {
            Log.e(TAG, formatMessage(message));
        }

        private void e(String message, Throwable throwable) {
            Log.e(TAG, formatMessage(message), throwable);
        }

        private void w(String message) {
            Log.w(TAG, formatMessage(message));
        }

        private void i(String message) {
            Log.i(TAG, formatMessage(message));
        }

        private void d(String message) {
            Log.d(TAG, formatMessage(message));
        }

        private void v(String message) {
            Log.v(TAG, formatMessage(message));
        }

        private String formatMessage(String message) {
            return TextUtils.formatSimple("[%s] %s", mPrefix, message);
        }
    }
}

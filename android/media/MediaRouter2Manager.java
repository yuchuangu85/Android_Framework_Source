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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A class that monitors and controls media routing of other apps.
 * {@link android.Manifest.permission#MEDIA_CONTENT_CONTROL} is required to use this class,
 * or {@link SecurityException} will be thrown.
 * @hide
 */
public final class MediaRouter2Manager {
    private static final String TAG = "MR2Manager";
    private static final Object sLock = new Object();
    /**
     * The request ID for requests not asked by this instance.
     * Shouldn't be used for a valid request.
     * @hide
     */
    public static final int REQUEST_ID_NONE = 0;
    /** @hide */
    @VisibleForTesting
    public static final int TRANSFER_TIMEOUT_MS = 30_000;

    @GuardedBy("sLock")
    private static MediaRouter2Manager sInstance;

    private final MediaSessionManager mMediaSessionManager;

    final String mPackageName;

    private final Context mContext;
    @GuardedBy("sLock")
    private Client mClient;
    private final IMediaRouterService mMediaRouterService;
    final Handler mHandler;
    final CopyOnWriteArrayList<CallbackRecord> mCallbackRecords = new CopyOnWriteArrayList<>();

    private final Object mRoutesLock = new Object();
    @GuardedBy("mRoutesLock")
    private final Map<String, MediaRoute2Info> mRoutes = new HashMap<>();
    @NonNull
    final ConcurrentMap<String, List<String>> mPreferredFeaturesMap = new ConcurrentHashMap<>();

    private final AtomicInteger mNextRequestId = new AtomicInteger(1);
    private final CopyOnWriteArrayList<TransferRequest> mTransferRequests =
            new CopyOnWriteArrayList<>();

    /**
     * Gets an instance of media router manager that controls media route of other applications.
     *
     * @return The media router manager instance for the context.
     */
    public static MediaRouter2Manager getInstance(@NonNull Context context) {
        Objects.requireNonNull(context, "context must not be null");
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new MediaRouter2Manager(context);
            }
            return sInstance;
        }
    }

    private MediaRouter2Manager(Context context) {
        mContext = context.getApplicationContext();
        mMediaRouterService = IMediaRouterService.Stub.asInterface(
                ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));
        mMediaSessionManager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        mPackageName = mContext.getPackageName();
        mHandler = new Handler(context.getMainLooper());
        mHandler.post(this::getOrCreateClient);
    }

    /**
     * Registers a callback to listen route info.
     *
     * @param executor the executor that runs the callback
     * @param callback the callback to add
     */
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        CallbackRecord callbackRecord = new CallbackRecord(executor, callback);
        if (!mCallbackRecords.addIfAbsent(callbackRecord)) {
            Log.w(TAG, "Ignoring to register the same callback twice.");
            return;
        }
    }

    /**
     * Unregisters the specified callback.
     *
     * @param callback the callback to unregister
     */
    public void unregisterCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mCallbackRecords.remove(new CallbackRecord(null, callback))) {
            Log.w(TAG, "unregisterCallback: Ignore unknown callback. " + callback);
            return;
        }
    }

    /**
     * Starts scanning remote routes.
     * <p>
     * Route discovery can happen even when the {@link #startScan()} is not called.
     * This is because the scanning could be started before by other apps.
     * Therefore, calling this method after calling {@link #stopScan()} does not necessarily mean
     * that the routes found before are removed and added again.
     * <p>
     * Use {@link Callback} to get the route related events.
     * <p>
     * @see #stopScan()
     */
    public void startScan() {
        Client client = getOrCreateClient();
        if (client != null) {
            try {
                mMediaRouterService.startScan(client);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to get sessions. Service probably died.", ex);
            }
        }
    }

    /**
     * Stops scanning remote routes to reduce resource consumption.
     * <p>
     * Route discovery can be continued even after this method is called.
     * This is because the scanning is only turned off when all the apps stop scanning.
     * Therefore, calling this method does not necessarily mean the routes are removed.
     * Also, for the same reason it does not mean that {@link Callback#onRoutesAdded(List)}
     * is not called afterwards.
     * <p>
     * Use {@link Callback} to get the route related events.
     *
     * @see #startScan()
     */
    public void stopScan() {
        Client client = getOrCreateClient();
        if (client != null) {
            try {
                mMediaRouterService.stopScan(client);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to get sessions. Service probably died.", ex);
            }
        }
    }

    /**
     * Gets a {@link android.media.session.MediaController} associated with the
     * given routing session.
     * If there is no matching media session, {@code null} is returned.
     */
    @Nullable
    public MediaController getMediaControllerForRoutingSession(
            @NonNull RoutingSessionInfo sessionInfo) {
        for (MediaController controller : mMediaSessionManager.getActiveSessions(null)) {
            if (areSessionsMatched(controller, sessionInfo)) {
                return controller;
            }
        }
        return null;
    }

    /**
     * Gets available routes for an application.
     *
     * @param packageName the package name of the application
     */
    @NonNull
    public List<MediaRoute2Info> getAvailableRoutes(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RoutingSessionInfo> sessions = getRoutingSessions(packageName);
        return getAvailableRoutes(sessions.get(sessions.size() - 1));
    }

    /**
     * Gets routes that can be transferable seamlessly for an application.
     *
     * @param packageName the package name of the application
     */
    @NonNull
    public List<MediaRoute2Info> getTransferableRoutes(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RoutingSessionInfo> sessions = getRoutingSessions(packageName);
        return getTransferableRoutes(sessions.get(sessions.size() - 1));
    }


    /**
     * Gets available routes for the given routing session.
     * The returned routes can be passed to
     * {@link #transfer(RoutingSessionInfo, MediaRoute2Info)} for transferring the routing session.
     *
     * @param sessionInfo the routing session that would be transferred
     */
    @NonNull
    public List<MediaRoute2Info> getAvailableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<MediaRoute2Info> routes = new ArrayList<>();

        String packageName = sessionInfo.getClientPackageName();
        List<String> preferredFeatures = mPreferredFeaturesMap.get(packageName);
        if (preferredFeatures == null) {
            preferredFeatures = Collections.emptyList();
        }
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : mRoutes.values()) {
                if (route.hasAnyFeatures(preferredFeatures)
                        || sessionInfo.getSelectedRoutes().contains(route.getId())
                        || sessionInfo.getTransferableRoutes().contains(route.getId())) {
                    routes.add(route);
                }
            }
        }
        return routes;
    }

    /**
     * Gets routes that can be transferable seamlessly for the given routing session.
     * The returned routes can be passed to
     * {@link #transfer(RoutingSessionInfo, MediaRoute2Info)} for transferring the routing session.
     * <p>
     * This includes routes that are {@link RoutingSessionInfo#getTransferableRoutes() transferable}
     * by provider itself and routes that are different playback type (e.g. local/remote)
     * from the given routing session.
     *
     * @param sessionInfo the routing session that would be transferred
     */
    @NonNull
    public List<MediaRoute2Info> getTransferableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<MediaRoute2Info> routes = new ArrayList<>();

        String packageName = sessionInfo.getClientPackageName();
        List<String> preferredFeatures = mPreferredFeaturesMap.get(packageName);
        if (preferredFeatures == null) {
            preferredFeatures = Collections.emptyList();
        }
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : mRoutes.values()) {
                if (sessionInfo.getTransferableRoutes().contains(route.getId())) {
                    routes.add(route);
                    continue;
                }
                // Add Phone -> Cast and Cast -> Phone
                if (route.hasAnyFeatures(preferredFeatures)
                        && (sessionInfo.isSystemSession() ^ route.isSystemRoute())) {
                    routes.add(route);
                }
            }
        }
        return routes;
    }

    /**
     * Returns the preferred features of the specified package name.
     */
    @NonNull
    public List<String> getPreferredFeatures(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<String> preferredFeatures = mPreferredFeaturesMap.get(packageName);
        if (preferredFeatures == null) {
            preferredFeatures = Collections.emptyList();
        }
        return preferredFeatures;
    }

    /**
     * Returns a list of routes which are related to the given package name in the given route list.
     */
    @NonNull
    public List<MediaRoute2Info> filterRoutesForPackage(@NonNull List<MediaRoute2Info> routes,
            @NonNull String packageName) {
        Objects.requireNonNull(routes, "routes must not be null");
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RoutingSessionInfo> sessions = getRoutingSessions(packageName);
        RoutingSessionInfo sessionInfo = sessions.get(sessions.size() - 1);

        List<MediaRoute2Info> result = new ArrayList<>();
        List<String> preferredFeatures = mPreferredFeaturesMap.get(packageName);
        if (preferredFeatures == null) {
            preferredFeatures = Collections.emptyList();
        }

        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                if (route.hasAnyFeatures(preferredFeatures)
                        || sessionInfo.getSelectedRoutes().contains(route.getId())
                        || sessionInfo.getTransferableRoutes().contains(route.getId())) {
                    result.add(route);
                }
            }
        }
        return result;
    }

    /**
     * Gets the system routing session associated with no specific application.
     */
    @NonNull
    public RoutingSessionInfo getSystemRoutingSession() {
        for (RoutingSessionInfo sessionInfo : getActiveSessions()) {
            if (sessionInfo.isSystemSession()) {
                return sessionInfo;
            }
        }
        throw new IllegalStateException("No system routing session");
    }

    /**
     * Gets the routing session of a media session.
     * If the session is using {#link PlaybackInfo#PLAYBACK_TYPE_LOCAL local playback},
     * the system routing session is returned.
     * If the session is using {#link PlaybackInfo#PLAYBACK_TYPE_REMOTE remote playback},
     * it returns the corresponding routing session or {@code null} if it's unavailable.
     */
    @Nullable
    public RoutingSessionInfo getRoutingSessionForMediaController(MediaController mediaController) {
        MediaController.PlaybackInfo playbackInfo = mediaController.getPlaybackInfo();
        if (playbackInfo == null) {
            return null;
        }
        if (playbackInfo.getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            return new RoutingSessionInfo.Builder(getSystemRoutingSession())
                    .setClientPackageName(mediaController.getPackageName())
                    .build();
        }
        for (RoutingSessionInfo sessionInfo : getActiveSessions()) {
            if (!sessionInfo.isSystemSession()
                    && areSessionsMatched(mediaController, sessionInfo)) {
                return sessionInfo;
            }
        }
        return null;
    }

    /**
     * Gets routing sessions of an application with the given package name.
     * The first element of the returned list is the system routing session.
     *
     * @param packageName the package name of the application that is routing.
     * @see #getSystemRoutingSession()
     */
    @NonNull
    public List<RoutingSessionInfo> getRoutingSessions(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RoutingSessionInfo> sessions = new ArrayList<>();

        for (RoutingSessionInfo sessionInfo : getActiveSessions()) {
            if (sessionInfo.isSystemSession()) {
                sessions.add(new RoutingSessionInfo.Builder(sessionInfo)
                        .setClientPackageName(packageName)
                        .build());
            } else if (TextUtils.equals(sessionInfo.getClientPackageName(), packageName)) {
                sessions.add(sessionInfo);
            }
        }
        return sessions;
    }

    /**
     * Gets the list of all active routing sessions.
     * <p>
     * The first element of the list is the system routing session containing
     * phone speakers, wired headset, Bluetooth devices.
     * The system routing session is shared by apps such that controlling it will affect
     * all apps.
     * If you want to transfer media of an application, use {@link #getRoutingSessions(String)}.
     *
     * @see #getRoutingSessions(String)
     * @see #getSystemRoutingSession()
     */
    @NonNull
    public List<RoutingSessionInfo> getActiveSessions() {
        Client client = getOrCreateClient();
        if (client != null) {
            try {
                return mMediaRouterService.getActiveSessions(client);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to get sessions. Service probably died.", ex);
            }
        }
        return Collections.emptyList();
    }

    /**
     * Gets the list of all discovered routes.
     */
    @NonNull
    public List<MediaRoute2Info> getAllRoutes() {
        List<MediaRoute2Info> routes = new ArrayList<>();
        synchronized (mRoutesLock) {
            routes.addAll(mRoutes.values());
        }
        return routes;
    }

    /**
     * Selects media route for the specified package name.
     */
    public void selectRoute(@NonNull String packageName, @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(route, "route must not be null");

        Log.v(TAG, "Selecting route. packageName= " + packageName + ", route=" + route);

        List<RoutingSessionInfo> sessionInfos = getRoutingSessions(packageName);
        RoutingSessionInfo targetSession = sessionInfos.get(sessionInfos.size() - 1);
        transfer(targetSession, route);
    }

    /**
     * Transfers a routing session to a media route.
     * <p>{@link Callback#onTransferred} or {@link Callback#onTransferFailed} will be called
     * depending on the result.
     *
     * @param sessionInfo the routing session info to transfer
     * @param route the route transfer to
     *
     * @see Callback#onTransferred(RoutingSessionInfo, RoutingSessionInfo)
     * @see Callback#onTransferFailed(RoutingSessionInfo, MediaRoute2Info)
     */
    public void transfer(@NonNull RoutingSessionInfo sessionInfo,
            @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        Objects.requireNonNull(route, "route must not be null");

        Log.v(TAG, "Transferring routing session. session= " + sessionInfo + ", route=" + route);

        synchronized (mRoutesLock) {
            if (!mRoutes.containsKey(route.getId())) {
                Log.w(TAG, "transfer: Ignoring an unknown route id=" + route.getId());
                notifyTransferFailed(sessionInfo, route);
                return;
            }
        }

        if (sessionInfo.getTransferableRoutes().contains(route.getId())) {
            transferToRoute(sessionInfo, route);
        } else {
            requestCreateSession(sessionInfo, route);
        }
    }

    /**
     * Requests a volume change for a route asynchronously.
     * <p>
     * It may have no effect if the route is currently not selected.
     * </p>
     *
     * @param volume The new volume value between 0 and {@link MediaRoute2Info#getVolumeMax}
     *               (inclusive).
     */
    public void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
        Objects.requireNonNull(route, "route must not be null");

        if (route.getVolumeHandling() == MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
            Log.w(TAG, "setRouteVolume: the route has fixed volume. Ignoring.");
            return;
        }
        if (volume < 0 || volume > route.getVolumeMax()) {
            Log.w(TAG, "setRouteVolume: the target volume is out of range. Ignoring");
            return;
        }

        Client client = getOrCreateClient();
        if (client != null) {
            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.setRouteVolumeWithManager(client, requestId, route, volume);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to set route volume.", ex);
            }
        }
    }

    /**
     * Requests a volume change for a routing session asynchronously.
     *
     * @param volume The new volume value between 0 and {@link RoutingSessionInfo#getVolumeMax}
     *               (inclusive).
     */
    public void setSessionVolume(@NonNull RoutingSessionInfo sessionInfo, int volume) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        if (sessionInfo.getVolumeHandling() == MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
            Log.w(TAG, "setSessionVolume: the route has fixed volume. Ignoring.");
            return;
        }
        if (volume < 0 || volume > sessionInfo.getVolumeMax()) {
            Log.w(TAG, "setSessionVolume: the target volume is out of range. Ignoring");
            return;
        }

        Client client = getOrCreateClient();
        if (client != null) {
            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.setSessionVolumeWithManager(
                        client, requestId, sessionInfo.getId(), volume);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to set session volume.", ex);
            }
        }
    }

    void addRoutesOnHandler(List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getId(), route);
            }
        }
        if (routes.size() > 0) {
            notifyRoutesAdded(routes);
        }
    }

    void removeRoutesOnHandler(List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.remove(route.getId());
            }
        }
        if (routes.size() > 0) {
            notifyRoutesRemoved(routes);
        }
    }

    void changeRoutesOnHandler(List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getId(), route);
            }
        }
        if (routes.size() > 0) {
            notifyRoutesChanged(routes);
        }
    }

    void createSessionOnHandler(int requestId, RoutingSessionInfo sessionInfo) {
        TransferRequest matchingRequest = null;
        for (TransferRequest request : mTransferRequests) {
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

        if (sessionInfo == null) {
            notifyTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            return;
        } else if (!sessionInfo.getSelectedRoutes().contains(requestedRoute.getId())) {
            Log.w(TAG, "The session does not contain the requested route. "
                    + "(requestedRouteId=" + requestedRoute.getId()
                    + ", actualRoutes=" + sessionInfo.getSelectedRoutes()
                    + ")");
            notifyTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            return;
        } else if (!TextUtils.equals(requestedRoute.getProviderId(),
                sessionInfo.getProviderId())) {
            Log.w(TAG, "The session's provider ID does not match the requested route's. "
                    + "(requested route's providerId=" + requestedRoute.getProviderId()
                    + ", actual providerId=" + sessionInfo.getProviderId()
                    + ")");
            notifyTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            return;
        }
        notifyTransferred(matchingRequest.mOldSessionInfo, sessionInfo);
    }

    void handleFailureOnHandler(int requestId, int reason) {
        TransferRequest matchingRequest = null;
        for (TransferRequest request : mTransferRequests) {
            if (request.mRequestId == requestId) {
                matchingRequest = request;
                break;
            }
        }

        if (matchingRequest != null) {
            mTransferRequests.remove(matchingRequest);
            notifyTransferFailed(matchingRequest.mOldSessionInfo, matchingRequest.mTargetRoute);
            return;
        }
        notifyRequestFailed(reason);
    }

    void handleSessionsUpdatedOnHandler(RoutingSessionInfo sessionInfo) {
        for (TransferRequest request : mTransferRequests) {
            String sessionId = request.mOldSessionInfo.getId();
            if (!TextUtils.equals(sessionId, sessionInfo.getId())) {
                continue;
            }
            if (sessionInfo.getSelectedRoutes().contains(request.mTargetRoute.getId())) {
                mTransferRequests.remove(request);
                notifyTransferred(request.mOldSessionInfo, sessionInfo);
                break;
            }
        }
        notifySessionUpdated(sessionInfo);
    }

    private void notifyRoutesAdded(List<MediaRoute2Info> routes) {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRoutesAdded(routes));
        }
    }

    private void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRoutesRemoved(routes));
        }
    }

    private void notifyRoutesChanged(List<MediaRoute2Info> routes) {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRoutesChanged(routes));
        }
    }

    void notifySessionUpdated(RoutingSessionInfo sessionInfo) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onSessionUpdated(sessionInfo));
        }
    }

    void notifySessionReleased(RoutingSessionInfo session) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onSessionReleased(session));
        }
    }

    void notifyRequestFailed(int reason) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onRequestFailed(reason));
        }
    }

    void notifyTransferred(RoutingSessionInfo oldSession, RoutingSessionInfo newSession) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onTransferred(oldSession, newSession));
        }
    }

    void notifyTransferFailed(RoutingSessionInfo sessionInfo, MediaRoute2Info route) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onTransferFailed(sessionInfo, route));
        }
    }

    void updatePreferredFeatures(String packageName, List<String> preferredFeatures) {
        if (preferredFeatures == null) {
            mPreferredFeaturesMap.remove(packageName);
            return;
        }
        List<String> prevFeatures = mPreferredFeaturesMap.put(packageName, preferredFeatures);
        if ((prevFeatures == null && preferredFeatures.size() == 0)
                || Objects.equals(preferredFeatures, prevFeatures)) {
            return;
        }
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback
                    .onPreferredFeaturesChanged(packageName, preferredFeatures));
        }
    }

    /**
     * Gets the unmodifiable list of selected routes for the session.
     */
    @NonNull
    public List<MediaRoute2Info> getSelectedRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        synchronized (mRoutesLock) {
            return sessionInfo.getSelectedRoutes().stream().map(mRoutes::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Gets the unmodifiable list of selectable routes for the session.
     */
    @NonNull
    public List<MediaRoute2Info> getSelectableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<String> selectedRouteIds = sessionInfo.getSelectedRoutes();

        synchronized (mRoutesLock) {
            return sessionInfo.getSelectableRoutes().stream()
                    .filter(routeId -> !selectedRouteIds.contains(routeId))
                    .map(mRoutes::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Gets the unmodifiable list of deselectable routes for the session.
     */
    @NonNull
    public List<MediaRoute2Info> getDeselectableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<String> selectedRouteIds = sessionInfo.getSelectedRoutes();

        synchronized (mRoutesLock) {
            return sessionInfo.getDeselectableRoutes().stream()
                    .filter(routeId -> selectedRouteIds.contains(routeId))
                    .map(mRoutes::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Selects a route for the remote session. After a route is selected, the media is expected
     * to be played to the all the selected routes. This is different from {@link
     * #transfer(RoutingSessionInfo, MediaRoute2Info)} transferring to a route},
     * where the media is expected to 'move' from one route to another.
     * <p>
     * The given route must satisfy all of the following conditions:
     * <ul>
     * <li>it should not be included in {@link #getSelectedRoutes(RoutingSessionInfo)}</li>
     * <li>it should be included in {@link #getSelectableRoutes(RoutingSessionInfo)}</li>
     * </ul>
     * If the route doesn't meet any of above conditions, it will be ignored.
     *
     * @see #getSelectedRoutes(RoutingSessionInfo)
     * @see #getSelectableRoutes(RoutingSessionInfo)
     * @see Callback#onSessionUpdated(RoutingSessionInfo)
     */
    public void selectRoute(@NonNull RoutingSessionInfo sessionInfo,
            @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        Objects.requireNonNull(route, "route must not be null");

        if (sessionInfo.getSelectedRoutes().contains(route.getId())) {
            Log.w(TAG, "Ignoring selecting a route that is already selected. route=" + route);
            return;
        }

        if (!sessionInfo.getSelectableRoutes().contains(route.getId())) {
            Log.w(TAG, "Ignoring selecting a non-selectable route=" + route);
            return;
        }

        Client client = getOrCreateClient();
        if (client != null) {
            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.selectRouteWithManager(
                        client, requestId, sessionInfo.getId(), route);
            } catch (RemoteException ex) {
                Log.e(TAG, "selectRoute: Failed to send a request.", ex);
            }
        }
    }

    /**
     * Deselects a route from the remote session. After a route is deselected, the media is
     * expected to be stopped on the deselected routes.
     * <p>
     * The given route must satisfy all of the following conditions:
     * <ul>
     * <li>it should be included in {@link #getSelectedRoutes(RoutingSessionInfo)}</li>
     * <li>it should be included in {@link #getDeselectableRoutes(RoutingSessionInfo)}</li>
     * </ul>
     * If the route doesn't meet any of above conditions, it will be ignored.
     *
     * @see #getSelectedRoutes(RoutingSessionInfo)
     * @see #getDeselectableRoutes(RoutingSessionInfo)
     * @see Callback#onSessionUpdated(RoutingSessionInfo)
     */
    public void deselectRoute(@NonNull RoutingSessionInfo sessionInfo,
            @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        Objects.requireNonNull(route, "route must not be null");

        if (!sessionInfo.getSelectedRoutes().contains(route.getId())) {
            Log.w(TAG, "Ignoring deselecting a route that is not selected. route=" + route);
            return;
        }

        if (!sessionInfo.getDeselectableRoutes().contains(route.getId())) {
            Log.w(TAG, "Ignoring deselecting a non-deselectable route=" + route);
            return;
        }

        Client client = getOrCreateClient();
        if (client != null) {
            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.deselectRouteWithManager(
                        client, requestId, sessionInfo.getId(), route);
            } catch (RemoteException ex) {
                Log.e(TAG, "deselectRoute: Failed to send a request.", ex);
            }
        }
    }

    /**
     * Requests releasing a session.
     * <p>
     * If a session is released, any operation on the session will be ignored.
     * {@link Callback#onSessionReleased(RoutingSessionInfo)} will be called
     * when the session is released.
     * </p>
     *
     * @see Callback#onTransferred(RoutingSessionInfo, RoutingSessionInfo)
     */
    public void releaseSession(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        Client client = getOrCreateClient();
        if (client != null) {
            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.releaseSessionWithManager(
                        client, requestId, sessionInfo.getId());
            } catch (RemoteException ex) {
                Log.e(TAG, "releaseSession: Failed to send a request", ex);
            }
        }
    }

    /**
     * Transfers the remote session to the given route.
     *
     * @hide
     */
    private void transferToRoute(@NonNull RoutingSessionInfo session,
            @NonNull MediaRoute2Info route) {
        int requestId = createTransferRequest(session, route);

        Client client = getOrCreateClient();
        if (client != null) {
            try {
                mMediaRouterService.transferToRouteWithManager(
                        client, requestId, session.getId(), route);
            } catch (RemoteException ex) {
                Log.e(TAG, "transferToRoute: Failed to send a request.", ex);
            }
        }
    }

    private void requestCreateSession(RoutingSessionInfo oldSession, MediaRoute2Info route) {
        if (TextUtils.isEmpty(oldSession.getClientPackageName())) {
            Log.w(TAG, "requestCreateSession: Can't create a session without package name.");
            notifyTransferFailed(oldSession, route);
            return;
        }

        int requestId = createTransferRequest(oldSession, route);

        Client client = getOrCreateClient();
        if (client != null) {
            try {
                mMediaRouterService.requestCreateSessionWithManager(
                        client, requestId, oldSession, route);
            } catch (RemoteException ex) {
                Log.e(TAG, "requestCreateSession: Failed to send a request", ex);
            }
        }
    }

    private int createTransferRequest(RoutingSessionInfo session, MediaRoute2Info route) {
        int requestId = mNextRequestId.getAndIncrement();
        TransferRequest transferRequest = new TransferRequest(requestId, session, route);
        mTransferRequests.add(transferRequest);

        Message timeoutMessage =
                obtainMessage(MediaRouter2Manager::handleTransferTimeout, this, transferRequest);
        mHandler.sendMessageDelayed(timeoutMessage, TRANSFER_TIMEOUT_MS);
        return requestId;
    }

    private void handleTransferTimeout(TransferRequest request) {
        boolean removed = mTransferRequests.remove(request);
        if (removed) {
            notifyTransferFailed(request.mOldSessionInfo, request.mTargetRoute);
        }
    }


    private boolean areSessionsMatched(MediaController mediaController,
            RoutingSessionInfo sessionInfo) {
        MediaController.PlaybackInfo playbackInfo = mediaController.getPlaybackInfo();
        if (playbackInfo == null) {
            return false;
        }

        String volumeControlId = playbackInfo.getVolumeControlId();
        if (volumeControlId == null) {
            return false;
        }

        if (TextUtils.equals(volumeControlId, sessionInfo.getId())) {
            return true;
        }
        // Workaround for provider not being able to know the unique session ID.
        return TextUtils.equals(volumeControlId, sessionInfo.getOriginalId())
                && TextUtils.equals(mediaController.getPackageName(),
                sessionInfo.getOwnerPackageName());
    }

    private Client getOrCreateClient() {
        synchronized (sLock) {
            if (mClient != null) {
                return mClient;
            }
            Client client = new Client();
            try {
                mMediaRouterService.registerManager(client, mPackageName);
                mClient = client;
                return client;
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to register media router manager.", ex);
            }
        }
        return null;
    }

    /**
     * Interface for receiving events about media routing changes.
     */
    public interface Callback {
        /**
         * Called when routes are added.
         * @param routes the list of routes that have been added. It's never empty.
         */
        default void onRoutesAdded(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are removed.
         * @param routes the list of routes that have been removed. It's never empty.
         */
        default void onRoutesRemoved(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are changed.
         * @param routes the list of routes that have been changed. It's never empty.
         */
        default void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when a session is changed.
         * @param session the updated session
         */
        default void onSessionUpdated(@NonNull RoutingSessionInfo session) {}

        /**
         * Called when a session is released.
         * @param session the released session.
         * @see #releaseSession(RoutingSessionInfo)
         */
        default void onSessionReleased(@NonNull RoutingSessionInfo session) {}

        /**
         * Called when media is transferred.
         *
         * @param oldSession the previous session
         * @param newSession the new session
         */
        default void onTransferred(@NonNull RoutingSessionInfo oldSession,
                @NonNull RoutingSessionInfo newSession) { }

        /**
         * Called when {@link #transfer(RoutingSessionInfo, MediaRoute2Info)} fails.
         */
        default void onTransferFailed(@NonNull RoutingSessionInfo session,
                @NonNull MediaRoute2Info route) { }

        /**
         * Called when the preferred route features of an app is changed.
         *
         * @param packageName the package name of the application
         * @param preferredFeatures the list of preferred route features set by an application.
         */
        default void onPreferredFeaturesChanged(@NonNull String packageName,
                @NonNull List<String> preferredFeatures) {}

        /**
         * Called when a previous request has failed.
         *
         * @param reason the reason that the request has failed. Can be one of followings:
         *               {@link MediaRoute2ProviderService#REASON_UNKNOWN_ERROR},
         *               {@link MediaRoute2ProviderService#REASON_REJECTED},
         *               {@link MediaRoute2ProviderService#REASON_NETWORK_ERROR},
         *               {@link MediaRoute2ProviderService#REASON_ROUTE_NOT_AVAILABLE},
         *               {@link MediaRoute2ProviderService#REASON_INVALID_COMMAND},
         */
        default void onRequestFailed(int reason) {}
    }

    final class CallbackRecord {
        public final Executor mExecutor;
        public final Callback mCallback;

        CallbackRecord(Executor executor, Callback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CallbackRecord)) {
                return false;
            }
            return mCallback == ((CallbackRecord) obj).mCallback;
        }

        @Override
        public int hashCode() {
            return mCallback.hashCode();
        }
    }

    static final class TransferRequest {
        public final int mRequestId;
        public final RoutingSessionInfo mOldSessionInfo;
        public final MediaRoute2Info mTargetRoute;

        TransferRequest(int requestId, @NonNull RoutingSessionInfo oldSessionInfo,
                @NonNull MediaRoute2Info targetRoute) {
            mRequestId = requestId;
            mOldSessionInfo = oldSessionInfo;
            mTargetRoute = targetRoute;
        }
    }

    class Client extends IMediaRouter2Manager.Stub {
        @Override
        public void notifySessionCreated(int requestId, RoutingSessionInfo session) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::createSessionOnHandler,
                    MediaRouter2Manager.this, requestId, session));
        }

        @Override
        public void notifySessionUpdated(RoutingSessionInfo session) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::handleSessionsUpdatedOnHandler,
                    MediaRouter2Manager.this, session));
        }

        @Override
        public void notifySessionReleased(RoutingSessionInfo session) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::notifySessionReleased,
                    MediaRouter2Manager.this, session));
        }

        @Override
        public void notifyRequestFailed(int requestId, int reason) {
            // Note: requestId is not used.
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::handleFailureOnHandler,
                    MediaRouter2Manager.this, requestId, reason));
        }

        @Override
        public void notifyPreferredFeaturesChanged(String packageName, List<String> features) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::updatePreferredFeatures,
                    MediaRouter2Manager.this, packageName, features));
        }

        @Override
        public void notifyRoutesAdded(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::addRoutesOnHandler,
                    MediaRouter2Manager.this, routes));
        }

        @Override
        public void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::removeRoutesOnHandler,
                    MediaRouter2Manager.this, routes));
        }

        @Override
        public void notifyRoutesChanged(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::changeRoutesOnHandler,
                    MediaRouter2Manager.this, routes));
        }
    }
}

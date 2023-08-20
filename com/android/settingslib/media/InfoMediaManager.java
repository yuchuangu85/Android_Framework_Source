/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.settingslib.media;

import static android.media.MediaRoute2Info.TYPE_BLE_HEADSET;
import static android.media.MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_DOCK;
import static android.media.MediaRoute2Info.TYPE_GROUP;
import static android.media.MediaRoute2Info.TYPE_HDMI;
import static android.media.MediaRoute2Info.TYPE_HEARING_AID;
import static android.media.MediaRoute2Info.TYPE_REMOTE_AUDIO_VIDEO_RECEIVER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_CAR;
import static android.media.MediaRoute2Info.TYPE_REMOTE_COMPUTER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_GAME_CONSOLE;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SMARTWATCH;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TABLET;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TABLET_DOCKED;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TV;
import static android.media.MediaRoute2Info.TYPE_UNKNOWN;
import static android.media.MediaRoute2Info.TYPE_USB_ACCESSORY;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_USB_HEADSET;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;

import static com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_SELECTED;

import android.annotation.Nullable;
import android.annotation.TargetApi;
import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.media.RouteListingPreference;
import android.media.RoutingSessionInfo;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.DoNotInline;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * InfoMediaManager provide interface to get InfoMediaDevice list.
 */
@RequiresApi(Build.VERSION_CODES.R)
public class InfoMediaManager extends MediaManager {

    private static final String TAG = "InfoMediaManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    @VisibleForTesting
    final RouterManagerCallback mMediaRouterCallback = new RouterManagerCallback();
    @VisibleForTesting
    final Executor mExecutor = Executors.newSingleThreadExecutor();
    @VisibleForTesting
    MediaRouter2Manager mRouterManager;
    @VisibleForTesting
    String mPackageName;

    private MediaDevice mCurrentConnectedDevice;
    private LocalBluetoothManager mBluetoothManager;
    private final Map<String, RouteListingPreference.Item> mPreferenceItemMap =
            new ConcurrentHashMap<>();

    public InfoMediaManager(Context context, String packageName, Notification notification,
            LocalBluetoothManager localBluetoothManager) {
        super(context, notification);

        mRouterManager = MediaRouter2Manager.getInstance(context);
        mBluetoothManager = localBluetoothManager;
        if (!TextUtils.isEmpty(packageName)) {
            mPackageName = packageName;
        }
    }

    @Override
    public void startScan() {
        mMediaDevices.clear();
        mRouterManager.registerCallback(mExecutor, mMediaRouterCallback);
        mRouterManager.registerScanRequest();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && !TextUtils.isEmpty(mPackageName)) {
            RouteListingPreference routeListingPreference =
                    mRouterManager.getRouteListingPreference(mPackageName);
            if (routeListingPreference != null) {
                Api34Impl.onRouteListingPreferenceUpdated(null, routeListingPreference,
                        mPreferenceItemMap);
            }
        }
        refreshDevices();
    }

    @Override
    public void stopScan() {
        mRouterManager.unregisterCallback(mMediaRouterCallback);
        mRouterManager.unregisterScanRequest();
    }

    /**
     * Get current device that played media.
     * @return MediaDevice
     */
    MediaDevice getCurrentConnectedDevice() {
        return mCurrentConnectedDevice;
    }

    /**
     * Transfer MediaDevice for media without package name.
     */
    boolean connectDeviceWithoutPackageName(MediaDevice device) {
        boolean isConnected = false;
        final RoutingSessionInfo info = mRouterManager.getSystemRoutingSession(null);
        if (info != null) {
            mRouterManager.transfer(info, device.mRouteInfo);
            isConnected = true;
        }
        return isConnected;
    }

    /**
     * Add a MediaDevice to let it play current media.
     *
     * @param device MediaDevice
     * @return If add device successful return {@code true}, otherwise return {@code false}
     */
    boolean addDeviceToPlayMedia(MediaDevice device) {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "addDeviceToPlayMedia() package name is null or empty!");
            return false;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null && info.getSelectableRoutes().contains(device.mRouteInfo.getId())) {
            mRouterManager.selectRoute(info, device.mRouteInfo);
            return true;
        }

        Log.w(TAG, "addDeviceToPlayMedia() Ignoring selecting a non-selectable device : "
                + device.getName());

        return false;
    }

    private RoutingSessionInfo getRoutingSessionInfo() {
        return getRoutingSessionInfo(mPackageName);
    }

    private RoutingSessionInfo getRoutingSessionInfo(String packageName) {
        final List<RoutingSessionInfo> sessionInfos =
                mRouterManager.getRoutingSessions(packageName);

        if (sessionInfos == null || sessionInfos.isEmpty()) {
            return null;
        }
        return sessionInfos.get(sessionInfos.size() - 1);
    }

    boolean isRoutingSessionAvailableForVolumeControl() {
        List<RoutingSessionInfo> sessions =
                mRouterManager.getRoutingSessions(mPackageName);

        for (RoutingSessionInfo session : sessions) {
            if (!session.isSystemSession()
                    && session.getVolumeHandling() != MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
                return true;
            }
        }

        Log.d(TAG, "No routing session for " + mPackageName);
        return false;
    }

    boolean preferRouteListingOrdering() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && Api34Impl.preferRouteListingOrdering(mRouterManager, mPackageName);
    }

    @Nullable
    ComponentName getLinkedItemComponentName() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null;
        }
        return Api34Impl.getLinkedItemComponentName(mRouterManager, mPackageName);
    }

    /**
     * Remove a {@code device} from current media.
     *
     * @param device MediaDevice
     * @return If device stop successful return {@code true}, otherwise return {@code false}
     */
    boolean removeDeviceFromPlayMedia(MediaDevice device) {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "removeDeviceFromMedia() package name is null or empty!");
            return false;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null && info.getSelectedRoutes().contains(device.mRouteInfo.getId())) {
            mRouterManager.deselectRoute(info, device.mRouteInfo);
            return true;
        }

        Log.w(TAG, "removeDeviceFromMedia() Ignoring deselecting a non-deselectable device : "
                + device.getName());

        return false;
    }

    /**
     * Release session to stop playing media on MediaDevice.
     */
    boolean releaseSession() {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "releaseSession() package name is null or empty!");
            return false;
        }

        final RoutingSessionInfo sessionInfo = getRoutingSessionInfo();

        if (sessionInfo != null) {
            mRouterManager.releaseSession(sessionInfo);
            return true;
        }

        Log.w(TAG, "releaseSession() Ignoring release session : " + mPackageName);

        return false;
    }

    /**
     * Get the MediaDevice list that can be added to current media.
     *
     * @return list of MediaDevice
     */
    List<MediaDevice> getSelectableMediaDevice() {
        final List<MediaDevice> deviceList = new ArrayList<>();
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "getSelectableMediaDevice() package name is null or empty!");
            return deviceList;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            for (MediaRoute2Info route : mRouterManager.getSelectableRoutes(info)) {
                deviceList.add(new InfoMediaDevice(mContext, mRouterManager,
                        route, mPackageName, mPreferenceItemMap.get(route.getId())));
            }
            return deviceList;
        }

        Log.w(TAG, "getSelectableMediaDevice() cannot found selectable MediaDevice from : "
                + mPackageName);

        return deviceList;
    }

    /**
     * Get the MediaDevice list that can be removed from current media session.
     *
     * @return list of MediaDevice
     */
    List<MediaDevice> getDeselectableMediaDevice() {
        final List<MediaDevice> deviceList = new ArrayList<>();
        if (TextUtils.isEmpty(mPackageName)) {
            Log.d(TAG, "getDeselectableMediaDevice() package name is null or empty!");
            return deviceList;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            for (MediaRoute2Info route : mRouterManager.getDeselectableRoutes(info)) {
                deviceList.add(new InfoMediaDevice(mContext, mRouterManager,
                        route, mPackageName, mPreferenceItemMap.get(route.getId())));
                Log.d(TAG, route.getName() + " is deselectable for " + mPackageName);
            }
            return deviceList;
        }
        Log.d(TAG, "getDeselectableMediaDevice() cannot found deselectable MediaDevice from : "
                + mPackageName);

        return deviceList;
    }

    /**
     * Get the MediaDevice list that has been selected to current media.
     *
     * @return list of MediaDevice
     */
    List<MediaDevice> getSelectedMediaDevice() {
        final List<MediaDevice> deviceList = new ArrayList<>();
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "getSelectedMediaDevice() package name is null or empty!");
            return deviceList;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            for (MediaRoute2Info route : mRouterManager.getSelectedRoutes(info)) {
                deviceList.add(new InfoMediaDevice(mContext, mRouterManager,
                        route, mPackageName, mPreferenceItemMap.get(route.getId())));
            }
            return deviceList;
        }

        Log.w(TAG, "getSelectedMediaDevice() cannot found selectable MediaDevice from : "
                + mPackageName);

        return deviceList;
    }

    void adjustSessionVolume(RoutingSessionInfo info, int volume) {
        if (info == null) {
            Log.w(TAG, "Unable to adjust session volume. RoutingSessionInfo is empty");
            return;
        }

        mRouterManager.setSessionVolume(info, volume);
    }

    /**
     * Adjust the volume of {@link android.media.RoutingSessionInfo}.
     *
     * @param volume the value of volume
     */
    void adjustSessionVolume(int volume) {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "adjustSessionVolume() package name is null or empty!");
            return;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            Log.d(TAG, "adjustSessionVolume() adjust volume : " + volume + ", with : "
                    + mPackageName);
            mRouterManager.setSessionVolume(info, volume);
            return;
        }

        Log.w(TAG, "adjustSessionVolume() can't found corresponding RoutingSession with : "
                + mPackageName);
    }

    /**
     * Gets the maximum volume of the {@link android.media.RoutingSessionInfo}.
     *
     * @return  maximum volume of the session, and return -1 if not found.
     */
    public int getSessionVolumeMax() {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "getSessionVolumeMax() package name is null or empty!");
            return -1;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            return info.getVolumeMax();
        }

        Log.w(TAG, "getSessionVolumeMax() can't found corresponding RoutingSession with : "
                + mPackageName);
        return -1;
    }

    /**
     * Gets the current volume of the {@link android.media.RoutingSessionInfo}.
     *
     * @return current volume of the session, and return -1 if not found.
     */
    public int getSessionVolume() {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "getSessionVolume() package name is null or empty!");
            return -1;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            return info.getVolume();
        }

        Log.w(TAG, "getSessionVolume() can't found corresponding RoutingSession with : "
                + mPackageName);
        return -1;
    }

    CharSequence getSessionName() {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "Unable to get session name. The package name is null or empty!");
            return null;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            return info.getName();
        }

        Log.w(TAG, "Unable to get session name for package: " + mPackageName);
        return null;
    }

    boolean shouldDisableMediaOutput(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            Log.w(TAG, "shouldDisableMediaOutput() package name is null or empty!");
            return true;
        }

        // Disable when there is no transferable route
        return mRouterManager.getTransferableRoutes(packageName).isEmpty();
    }

    @TargetApi(Build.VERSION_CODES.R)
    boolean shouldEnableVolumeSeekBar(RoutingSessionInfo sessionInfo) {
        return sessionInfo.isSystemSession() // System sessions are not remote
                || sessionInfo.getVolumeHandling() != MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
    }

    private synchronized void refreshDevices() {
        mMediaDevices.clear();
        mCurrentConnectedDevice = null;
        if (TextUtils.isEmpty(mPackageName)) {
            buildAllRoutes();
        } else {
            buildAvailableRoutes();
        }
        dispatchDeviceListAdded();
    }

    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    private void buildAllRoutes() {
        for (MediaRoute2Info route : mRouterManager.getAllRoutes()) {
            if (DEBUG) {
                Log.d(TAG, "buildAllRoutes() route : " + route.getName() + ", volume : "
                        + route.getVolume() + ", type : " + route.getType());
            }
            if (route.isSystemRoute()) {
                addMediaDevice(route);
            }
        }
    }

    List<RoutingSessionInfo> getActiveMediaSession() {
        List<RoutingSessionInfo> infos = new ArrayList<>();
        infos.add(mRouterManager.getSystemRoutingSession(null));
        infos.addAll(mRouterManager.getRemoteSessions());
        return infos;
    }

    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    private synchronized void buildAvailableRoutes() {
        for (MediaRoute2Info route : getAvailableRoutes(mPackageName)) {
            if (DEBUG) {
                Log.d(TAG, "buildAvailableRoutes() route : " + route.getName() + ", volume : "
                        + route.getVolume() + ", type : " + route.getType());
            }
            addMediaDevice(route);
        }
    }

    private synchronized List<MediaRoute2Info> getAvailableRoutes(String packageName) {
        List<MediaRoute2Info> infos = new ArrayList<>();
        RoutingSessionInfo routingSessionInfo = getRoutingSessionInfo(packageName);
        List<MediaRoute2Info> selectedRouteInfos = new ArrayList<>();
        if (routingSessionInfo != null) {
            selectedRouteInfos = mRouterManager.getSelectedRoutes(routingSessionInfo);
            infos.addAll(selectedRouteInfos);
            infos.addAll(mRouterManager.getSelectableRoutes(routingSessionInfo));
        }
        final List<MediaRoute2Info> transferableRoutes =
                mRouterManager.getTransferableRoutes(packageName);
        for (MediaRoute2Info transferableRoute : transferableRoutes) {
            boolean alreadyAdded = false;
            for (MediaRoute2Info mediaRoute2Info : infos) {
                if (TextUtils.equals(transferableRoute.getId(), mediaRoute2Info.getId())) {
                    alreadyAdded = true;
                    break;
                }
            }
            if (!alreadyAdded) {
                infos.add(transferableRoute);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && !TextUtils.isEmpty(mPackageName)) {
            RouteListingPreference routeListingPreference =
                    mRouterManager.getRouteListingPreference(mPackageName);
            if (routeListingPreference != null) {
                final List<RouteListingPreference.Item> preferenceRouteListing =
                        Api34Impl.composePreferenceRouteListing(
                                routeListingPreference);
                infos = Api34Impl.arrangeRouteListByPreference(selectedRouteInfos,
                                mRouterManager.getAvailableRoutes(packageName),
                                preferenceRouteListing);
            }
            return Api34Impl.filterDuplicatedIds(infos);
        } else {
            return infos;
        }
    }

    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    @VisibleForTesting
    void addMediaDevice(MediaRoute2Info route) {
        final int deviceType = route.getType();
        MediaDevice mediaDevice = null;
        switch (deviceType) {
            case TYPE_UNKNOWN:
            case TYPE_REMOTE_TV:
            case TYPE_REMOTE_SPEAKER:
            case TYPE_GROUP:
            case TYPE_REMOTE_TABLET:
            case TYPE_REMOTE_TABLET_DOCKED:
            case TYPE_REMOTE_COMPUTER:
            case TYPE_REMOTE_GAME_CONSOLE:
            case TYPE_REMOTE_CAR:
            case TYPE_REMOTE_SMARTWATCH:
                mediaDevice = new InfoMediaDevice(mContext, mRouterManager, route,
                        mPackageName, mPreferenceItemMap.get(route.getId()));
                break;
            case TYPE_BUILTIN_SPEAKER:
            case TYPE_USB_DEVICE:
            case TYPE_USB_HEADSET:
            case TYPE_USB_ACCESSORY:
            case TYPE_DOCK:
            case TYPE_HDMI:
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
                mediaDevice =
                        new PhoneMediaDevice(mContext, mRouterManager, route, mPackageName);
                break;
            case TYPE_HEARING_AID:
            case TYPE_BLUETOOTH_A2DP:
            case TYPE_BLE_HEADSET:
                final BluetoothDevice device =
                        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(route.getAddress());
                final CachedBluetoothDevice cachedDevice =
                        mBluetoothManager.getCachedDeviceManager().findDevice(device);
                if (cachedDevice != null) {
                    mediaDevice = new BluetoothMediaDevice(mContext, cachedDevice, mRouterManager,
                            route, mPackageName);
                }
                break;
            case TYPE_REMOTE_AUDIO_VIDEO_RECEIVER:
                mediaDevice = new ComplexMediaDevice(mContext, mRouterManager, route,
                        mPackageName, mPreferenceItemMap.get(route.getId()));
            default:
                Log.w(TAG, "addMediaDevice() unknown device type : " + deviceType);
                break;

        }
        if (mediaDevice != null && !TextUtils.isEmpty(mPackageName)
                && getRoutingSessionInfo().getSelectedRoutes().contains(route.getId())) {
            mediaDevice.setState(STATE_SELECTED);
            if (mCurrentConnectedDevice == null) {
                mCurrentConnectedDevice = mediaDevice;
            }
        }
        if (mediaDevice != null) {
            mMediaDevices.add(mediaDevice);
        }
    }

    class RouterManagerCallback implements MediaRouter2Manager.Callback {

        @Override
        public void onRoutesUpdated() {
            refreshDevices();
        }

        @Override
        public void onPreferredFeaturesChanged(String packageName, List<String> preferredFeatures) {
            if (TextUtils.equals(mPackageName, packageName)) {
                refreshDevices();
            }
        }

        @Override
        public void onTransferred(RoutingSessionInfo oldSession, RoutingSessionInfo newSession) {
            if (DEBUG) {
                Log.d(TAG, "onTransferred() oldSession : " + oldSession.getName()
                        + ", newSession : " + newSession.getName());
            }
            mMediaDevices.clear();
            mCurrentConnectedDevice = null;
            if (TextUtils.isEmpty(mPackageName)) {
                buildAllRoutes();
            } else {
                buildAvailableRoutes();
            }

            final String id = mCurrentConnectedDevice != null
                    ? mCurrentConnectedDevice.getId()
                    : null;
            dispatchConnectedDeviceChanged(id);
        }

        /**
         * Ignore callback here since we'll also receive {@link onRequestFailed} with reason code.
         */
        @Override
        public void onTransferFailed(RoutingSessionInfo session, MediaRoute2Info route) {
        }

        @Override
        public void onRequestFailed(int reason) {
            dispatchOnRequestFailed(reason);
        }

        @Override
        public void onSessionUpdated(RoutingSessionInfo sessionInfo) {
            refreshDevices();
        }

        @Override
        public void onSessionReleased(@NonNull RoutingSessionInfo session) {
            refreshDevices();
        }

        @Override
        public void onRouteListingPreferenceUpdated(
                String packageName,
                RouteListingPreference routeListingPreference) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Api34Impl.onRouteListingPreferenceUpdated(packageName, routeListingPreference,
                        mPreferenceItemMap);
                refreshDevices();
            }
        }
    }

    @RequiresApi(34)
    private static class Api34Impl {
        @DoNotInline
        static List<RouteListingPreference.Item> composePreferenceRouteListing(
                RouteListingPreference routeListingPreference) {
            List<RouteListingPreference.Item> finalizedItemList = new ArrayList<>();
            List<RouteListingPreference.Item> itemList = routeListingPreference.getItems();
            for (RouteListingPreference.Item item : itemList) {
                // Put suggested devices on the top first before further organization
                if ((item.getFlags() & RouteListingPreference.Item.FLAG_SUGGESTED) != 0) {
                    finalizedItemList.add(0, item);
                } else {
                    finalizedItemList.add(item);
                }
            }
            return finalizedItemList;
        }

        @DoNotInline
        static synchronized List<MediaRoute2Info> filterDuplicatedIds(List<MediaRoute2Info> infos) {
            List<MediaRoute2Info> filteredInfos = new ArrayList<>();
            Set<String> foundDeduplicationIds = new HashSet<>();
            for (MediaRoute2Info mediaRoute2Info : infos) {
                if (!Collections.disjoint(mediaRoute2Info.getDeduplicationIds(),
                        foundDeduplicationIds)) {
                    continue;
                }
                filteredInfos.add(mediaRoute2Info);
                foundDeduplicationIds.addAll(mediaRoute2Info.getDeduplicationIds());
            }
            return filteredInfos;
        }

        @DoNotInline
        static List<MediaRoute2Info> arrangeRouteListByPreference(
                List<MediaRoute2Info> selectedRouteInfos, List<MediaRoute2Info> infolist,
                List<RouteListingPreference.Item> preferenceRouteListing) {
            final List<MediaRoute2Info> sortedInfoList = new ArrayList<>(selectedRouteInfos);
            for (RouteListingPreference.Item item : preferenceRouteListing) {
                for (MediaRoute2Info info : infolist) {
                    if (item.getRouteId().equals(info.getId())
                            && !selectedRouteInfos.contains(info)) {
                        sortedInfoList.add(info);
                        break;
                    }
                }
            }
            if (sortedInfoList.size() != infolist.size()) {
                infolist.removeAll(sortedInfoList);
                sortedInfoList.addAll(infolist.stream().filter(
                        MediaRoute2Info::isSystemRoute).collect(Collectors.toList()));
            }
            return sortedInfoList;
        }

        @DoNotInline
        static boolean preferRouteListingOrdering(MediaRouter2Manager mediaRouter2Manager,
                String packageName) {
            if (TextUtils.isEmpty(packageName)) {
                return false;
            }
            RouteListingPreference routeListingPreference =
                    mediaRouter2Manager.getRouteListingPreference(packageName);
            return routeListingPreference != null
                    && !routeListingPreference.getUseSystemOrdering();
        }

        @DoNotInline
        @Nullable
        static ComponentName getLinkedItemComponentName(
                MediaRouter2Manager mediaRouter2Manager, String packageName) {
            if (TextUtils.isEmpty(packageName)) {
                return null;
            }
            RouteListingPreference routeListingPreference =
                    mediaRouter2Manager.getRouteListingPreference(packageName);
            return routeListingPreference == null ? null
                    : routeListingPreference.getLinkedItemComponentName();
        }

        @DoNotInline
        static void onRouteListingPreferenceUpdated(
                String packageName,
                RouteListingPreference routeListingPreference,
                Map<String, RouteListingPreference.Item> preferenceItemMap) {
            preferenceItemMap.clear();
            if (routeListingPreference != null) {
                routeListingPreference.getItems().forEach((item) -> {
                    preferenceItemMap.put(item.getRouteId(), item);
                });
            }
        }
    }
}

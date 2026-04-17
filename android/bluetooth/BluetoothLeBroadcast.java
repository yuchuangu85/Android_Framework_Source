/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothUtils.enforcePermissionInFramework;

import static java.util.Objects.requireNonNull;

import android.annotation.CallbackExecutor;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.content.AttributionSource;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.CloseGuard;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides the public APIs to control the BAP Broadcast Source profile.
 *
 * <p>BluetoothLeBroadcast is a proxy object for controlling the Bluetooth LE Broadcast Source
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get the BluetoothLeBroadcast
 * proxy object.
 */
@Hide
@SystemApi
public final class BluetoothLeBroadcast implements AutoCloseable, BluetoothProfile {
    private static final String TAG = BluetoothLeBroadcast.class.getSimpleName();

    private static final boolean VDBG = Log.isLoggable("bluetooth", Log.VERBOSE);

    private final CloseGuard mCloseGuard;

    private final Context mContext;
    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;

    private IBluetoothLeAudio mService;

    private final CallbackWrapper<Callback, IBluetoothLeAudio> mCallbackWrapper;

    private final IBluetoothLeBroadcastCallback mCallback = new LeBroadcastNotifyCallback();

    private class LeBroadcastNotifyCallback extends IBluetoothLeBroadcastCallback.Stub {

        @Override
        public void onBroadcastStarted(int reason, int broadcastId) {
            mCallbackWrapper.forEach((cb) -> cb.onBroadcastStarted(reason, broadcastId));
        }

        @Override
        public void onBroadcastStartFailed(int reason) {
            mCallbackWrapper.forEach((cb) -> cb.onBroadcastStartFailed(reason));
        }

        @Override
        public void onBroadcastStopped(int reason, int broadcastId) {
            mCallbackWrapper.forEach((cb) -> cb.onBroadcastStopped(reason, broadcastId));
        }

        @Override
        public void onBroadcastStopFailed(int reason) {
            mCallbackWrapper.forEach((cb) -> cb.onBroadcastStopFailed(reason));
        }

        @Override
        public void onPlaybackStarted(int reason, int broadcastId) {
            mCallbackWrapper.forEach((cb) -> cb.onPlaybackStarted(reason, broadcastId));
        }

        @Override
        public void onPlaybackStopped(int reason, int broadcastId) {
            mCallbackWrapper.forEach((cb) -> cb.onPlaybackStopped(reason, broadcastId));
        }

        @Override
        public void onBroadcastUpdated(int reason, int broadcastId) {
            mCallbackWrapper.forEach((cb) -> cb.onBroadcastUpdated(reason, broadcastId));
        }

        @Override
        public void onBroadcastUpdateFailed(int reason, int broadcastId) {
            mCallbackWrapper.forEach((cb) -> cb.onBroadcastUpdateFailed(reason, broadcastId));
        }

        @Override
        public void onBroadcastMetadataChanged(
                int broadcastId, BluetoothLeBroadcastMetadata metadata) {
            mCallbackWrapper.forEach((cb) -> cb.onBroadcastMetadataChanged(broadcastId, metadata));
        }
    }

    /** Interface for receiving events related to Broadcast Source */
    @Hide
    @SystemApi
    public interface Callback {
        @Hide
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                value = {
                    BluetoothStatusCodes.ERROR_UNKNOWN,
                    BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST,
                    BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST,
                    BluetoothStatusCodes.REASON_SYSTEM_POLICY,
                    BluetoothStatusCodes.ERROR_HARDWARE_GENERIC,
                    BluetoothStatusCodes.ERROR_BAD_PARAMETERS,
                    BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES,
                    BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_CODE,
                    BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_BROADCAST_ID,
                    BluetoothStatusCodes.ERROR_LE_CONTENT_METADATA_INVALID_PROGRAM_INFO,
                    BluetoothStatusCodes.ERROR_LE_CONTENT_METADATA_INVALID_LANGUAGE,
                    BluetoothStatusCodes.ERROR_LE_CONTENT_METADATA_INVALID_OTHER,
                })
        @interface Reason {}

        /**
         * Callback invoked when broadcast is started, but audio may not be playing.
         *
         * <p>Caller should wait for {@link #onBroadcastMetadataChanged(int,
         * BluetoothLeBroadcastMetadata)} for the updated metadata
         *
         * @param reason for broadcast start
         * @param broadcastId as defined by the Basic Audio Profile
         */
        @Hide
        @SystemApi
        void onBroadcastStarted(@Reason int reason, int broadcastId);

        /**
         * Callback invoked when broadcast failed to start
         *
         * @param reason for broadcast start failure
         */
        @Hide
        @SystemApi
        void onBroadcastStartFailed(@Reason int reason);

        /**
         * Callback invoked when broadcast is stopped
         *
         * @param reason for broadcast stop
         */
        @Hide
        @SystemApi
        void onBroadcastStopped(@Reason int reason, int broadcastId);

        /**
         * Callback invoked when broadcast failed to stop
         *
         * @param reason for broadcast stop failure
         */
        @Hide
        @SystemApi
        void onBroadcastStopFailed(@Reason int reason);

        /**
         * Callback invoked when broadcast audio is playing
         *
         * @param reason for playback start
         * @param broadcastId as defined by the Basic Audio Profile
         */
        @Hide
        @SystemApi
        void onPlaybackStarted(@Reason int reason, int broadcastId);

        /**
         * Callback invoked when broadcast audio is not playing
         *
         * @param reason for playback stop
         * @param broadcastId as defined by the Basic Audio Profile
         */
        @Hide
        @SystemApi
        void onPlaybackStopped(@Reason int reason, int broadcastId);

        /**
         * Callback invoked when encryption is enabled
         *
         * @param reason for encryption enable
         * @param broadcastId as defined by the Basic Audio Profile
         */
        @Hide
        @SystemApi
        void onBroadcastUpdated(@Reason int reason, int broadcastId);

        /**
         * Callback invoked when Broadcast Source failed to update
         *
         * @param reason for update failure
         * @param broadcastId as defined by the Basic Audio Profile
         */
        @Hide
        @SystemApi
        void onBroadcastUpdateFailed(int reason, int broadcastId);

        /**
         * Callback invoked when Broadcast Source metadata is updated
         *
         * @param metadata updated Broadcast Source metadata
         * @param broadcastId as defined by the Basic Audio Profile
         */
        @Hide
        @SystemApi
        void onBroadcastMetadataChanged(
                int broadcastId, @NonNull BluetoothLeBroadcastMetadata metadata);
    }

    /**
     * Create a BluetoothLeBroadcast proxy object for interacting with the local LE Audio Broadcast
     * Source service.
     *
     * @param context for to operate this API class
     */
    @Hide
    @SuppressWarnings("IncorrectRequiresPermissionPropagation") // This just creates a runnable
    BluetoothLeBroadcast(Context context, BluetoothAdapter adapter) {
        mContext = requireNonNull(context);
        mAdapter = adapter;
        mAttributionSource = mAdapter.getAttributionSource();
        mService = null;

        Consumer<IBluetoothLeAudio> registerConsumer =
                (IBluetoothLeAudio service) -> {
                    try {
                        service.registerLeBroadcastCallback(mCallback, mAttributionSource);
                    } catch (RemoteException e) {
                        Log.e(TAG, e + "\n" + Log.getStackTraceString(new Throwable()));
                    }
                };
        Consumer<IBluetoothLeAudio> unregisterConsumer =
                (IBluetoothLeAudio service) -> {
                    try {
                        service.unregisterLeBroadcastCallback(mCallback, mAttributionSource);
                    } catch (RemoteException e) {
                        Log.e(TAG, e + "\n" + Log.getStackTraceString(new Throwable()));
                    }
                };

        mCallbackWrapper = new CallbackWrapper(registerConsumer, unregisterConsumer);
        mCloseGuard = new CloseGuard();
        mCloseGuard.open("close");
    }

    @Hide
    @SuppressWarnings("Finalize") // TODO(b/314811467)
    protected void finalize() {
        if (mCloseGuard != null) {
            mCloseGuard.warnIfOpen();
        }
        close();
    }

    /** Not supported since LE Audio Broadcasts do not establish a connection. */
    @Hide
    @Override
    @RequiresNoPermission
    public int getConnectionState(@NonNull BluetoothDevice device) {
        throw new UnsupportedOperationException("LE Audio Broadcasts are not connection-oriented.");
    }

    /** Not supported since LE Audio Broadcasts do not establish a connection. */
    @Hide
    @Override
    @RequiresNoPermission
    @NonNull
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(@NonNull int[] states) {
        throw new UnsupportedOperationException("LE Audio Broadcasts are not connection-oriented.");
    }

    /** Not supported since LE Audio Broadcasts do not establish a connection. */
    @Hide
    @Override
    @RequiresNoPermission
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        throw new UnsupportedOperationException("LE Audio Broadcasts are not connection-oriented.");
    }

    /**
     * Register a {@link Callback} that will be invoked during the operation of this profile.
     *
     * <p>Repeated registration of the same <var>callback</var> object after the first call to this
     * method will result with IllegalArgumentException being thrown, even when the
     * <var>executor</var> is different. API caller must call {@link #unregisterCallback(Callback)}
     * with the same callback object before registering it again.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link Callback}
     * @throws NullPointerException if a null executor, or callback is given, or
     *     IllegalArgumentException if the same <var>callback<var> is already registered.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void registerCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull Callback callback) {
        requireNonNull(executor);
        requireNonNull(callback);
        Log.d(TAG, "registerCallback");
        enforcePermissionInFramework(mContext, BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED);
        mCallbackWrapper.registerCallback(getService(), callback, executor);
    }

    /**
     * Unregister the specified {@link Callback}
     *
     * <p>The same {@link Callback} object used when calling {@link #registerCallback(Executor,
     * Callback)} must be used.
     *
     * <p>Callbacks are automatically unregistered when the application process goes away
     *
     * @param callback user implementation of the {@link Callback}
     * @throws NullPointerException when callback is null or IllegalArgumentException when no
     *     callback is registered
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void unregisterCallback(@NonNull Callback callback) {
        requireNonNull(callback);
        Log.d(TAG, "unregisterCallback");
        enforcePermissionInFramework(mContext, BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED);
        mCallbackWrapper.unregisterCallback(getService(), callback);
    }

    /**
     * Start broadcasting to nearby devices using <var>broadcastCode</var> and
     * <var>contentMetadata</var>
     *
     * <p>Encryption will be enabled when <var>broadcastCode</var> is not null.
     *
     * <p>As defined in Volume 3, Part C, Section 3.2.6 of Bluetooth Core Specification, Version
     * 5.3, Broadcast Code is used to encrypt a broadcast audio stream.
     *
     * <p>It must be a UTF-8 string that has at least 4 octets and should not exceed 16 octets.
     *
     * <p>If the provided <var>broadcastCode</var> is non-null and does not meet the above
     * requirements, encryption will fail to enable with reason code {@link
     * BluetoothStatusCodes#ERROR_LE_BROADCAST_INVALID_CODE}
     *
     * <p>Caller can set content metadata such as program information string in
     * <var>contentMetadata</var>
     *
     * <p>On success, {@link Callback#onBroadcastStarted(int, int)} will be invoked with {@link
     * BluetoothStatusCodes#REASON_LOCAL_APP_REQUEST} reason code. On failure, {@link
     * Callback#onBroadcastStartFailed(int)} will be invoked with reason code.
     *
     * <p>In particular, when the number of Broadcast Sources reaches {@link
     * #getMaximumNumberOfBroadcasts()}, this method will fail with {@link
     * BluetoothStatusCodes#ERROR_LOCAL_NOT_ENOUGH_RESOURCES}
     *
     * <p>After broadcast is started, {@link Callback#onBroadcastMetadataChanged(int,
     * BluetoothLeBroadcastMetadata)} will be invoked to expose the latest Broadcast Group metadata
     * that can be shared out of band to set up Broadcast Sink without scanning.
     *
     * <p>Alternatively, one can also get the latest Broadcast Source meta via {@link
     * #getAllBroadcastMetadata()}
     *
     * @param contentMetadata metadata for the default Broadcast subgroup
     * @param broadcastCode Encryption will be enabled when <var>broadcastCode</var> is not null
     * @throws IllegalStateException if callback was not registered
     * @throws NullPointerException if <var>contentMetadata</var> is null
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void startBroadcast(
            @NonNull BluetoothLeAudioContentMetadata contentMetadata,
            @Nullable byte[] broadcastCode) {
        requireNonNull(contentMetadata);
        if (mCallbackWrapper.isEmpty()) {
            throw new IllegalStateException("No callback was ever registered");
        }

        Log.d(TAG, "startBroadcasting");
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                service.startBroadcast(
                        buildBroadcastSettingsFromMetadata(contentMetadata, broadcastCode),
                        mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
    }

    /**
     * Start broadcasting to nearby devices using {@link BluetoothLeBroadcastSettings}.
     *
     * @param broadcastSettings broadcast settings for this broadcast group
     * @throws IllegalStateException if callback was not registered
     * @throws NullPointerException if <var>broadcastSettings</var> is null
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void startBroadcast(@NonNull BluetoothLeBroadcastSettings broadcastSettings) {
        requireNonNull(broadcastSettings);
        if (mCallbackWrapper.isEmpty()) {
            throw new IllegalStateException("No callback was ever registered");
        }

        Log.d(TAG, "startBroadcasting");
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                service.startBroadcast(broadcastSettings, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
    }

    /**
     * Update the broadcast with <var>broadcastId</var> with new <var>contentMetadata</var>
     *
     * <p>On success, {@link Callback#onBroadcastUpdated(int, int)} will be invoked with reason code
     * {@link BluetoothStatusCodes#REASON_LOCAL_APP_REQUEST}. On failure, {@link
     * Callback#onBroadcastUpdateFailed(int, int)} will be invoked with reason code
     *
     * @param broadcastId broadcastId as defined by the Basic Audio Profile
     * @param contentMetadata updated metadata for the default Broadcast subgroup
     * @throws IllegalStateException if callback was not registered
     * @throws NullPointerException if <var>contentMetadata</var> is null
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void updateBroadcast(
            int broadcastId, @NonNull BluetoothLeAudioContentMetadata contentMetadata) {
        requireNonNull(contentMetadata);
        if (mCallbackWrapper.isEmpty()) {
            throw new IllegalStateException("No callback was ever registered");
        }

        Log.d(TAG, "updateBroadcast");
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                service.updateBroadcast(
                        broadcastId,
                        buildBroadcastSettingsFromMetadata(contentMetadata, null),
                        mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
    }

    /**
     * Update the broadcast with <var>broadcastId</var> with <var>BluetoothLeBroadcastSettings</var>
     *
     * <p>On success, {@link Callback#onBroadcastUpdated(int, int)} will be invoked with reason code
     * {@link BluetoothStatusCodes#REASON_LOCAL_APP_REQUEST}. On failure, {@link
     * Callback#onBroadcastUpdateFailed(int, int)} will be invoked with reason code
     *
     * @param broadcastId broadcastId as defined by the Basic Audio Profile
     * @param broadcastSettings broadcast settings for this broadcast group
     * @throws IllegalStateException if callback was not registered
     * @throws NullPointerException if <var>broadcastSettings</var> is null
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void updateBroadcast(
            int broadcastId, @NonNull BluetoothLeBroadcastSettings broadcastSettings) {
        requireNonNull(broadcastSettings);
        if (mCallbackWrapper.isEmpty()) {
            throw new IllegalStateException("No callback was ever registered");
        }

        Log.d(TAG, "updateBroadcast");
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                service.updateBroadcast(broadcastId, broadcastSettings, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
    }

    /**
     * Stop broadcasting.
     *
     * <p>On success, {@link Callback#onBroadcastStopped(int, int)} will be invoked with reason code
     * {@link BluetoothStatusCodes#REASON_LOCAL_APP_REQUEST} and the <var>broadcastId</var> On
     * failure, {@link Callback#onBroadcastStopFailed(int)} will be invoked with reason code
     *
     * @param broadcastId as defined by the Basic Audio Profile
     * @throws IllegalStateException if callback was not registered
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void stopBroadcast(int broadcastId) {
        if (mCallbackWrapper.isEmpty()) {
            throw new IllegalStateException("No callback was ever registered");
        }

        Log.d(TAG, "disableBroadcastMode");
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                service.stopBroadcast(broadcastId, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
    }

    /**
     * Return true if audio is being broadcasted on the Broadcast Source as identified by the
     * <var>broadcastId</var>
     *
     * @param broadcastId as defined in the Basic Audio Profile
     * @return true if audio is being broadcasted
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean isPlaying(int broadcastId) {
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                return service.isPlaying(broadcastId, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Get {@link BluetoothLeBroadcastMetadata} for all Broadcast Groups currently running on this
     * device
     *
     * @return list of {@link BluetoothLeBroadcastMetadata}
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @NonNull List<BluetoothLeBroadcastMetadata> getAllBroadcastMetadata() {
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                return service.getAllBroadcastMetadata(mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return Collections.emptyList();
    }

    /**
     * Get the maximum number of Broadcast Isochronous Group supported on this device
     *
     * @return maximum number of Broadcast Isochronous Group supported on this device
     */
    @Hide
    @SystemApi
    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    public int getMaximumNumberOfBroadcasts() {
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                return service.getMaximumNumberOfBroadcasts();
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return 1;
    }

    /**
     * Get the maximum number of streams per broadcast Single stream means single Audio PCM stream
     *
     * @return maximum number of broadcast streams per broadcast group
     */
    @Hide
    @SystemApi
    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    public int getMaximumStreamsPerBroadcast() {
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                return service.getMaximumStreamsPerBroadcast();
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return 1;
    }

    /**
     * Get the maximum number of subgroups per broadcast Single stream means single Audio PCM
     * stream, one stream could support single or multiple subgroups based on language and audio
     * configuration. e.g. Stream 1 -> 2 subgroups with English and Spanish, Stream 2 -> 1 subgroups
     * with English, Stream 3 -> 2 subgroups with hearing Aids Standard and High Quality
     *
     * @return maximum number of broadcast subgroups per broadcast group
     */
    @Hide
    @SystemApi
    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    public int getMaximumSubgroupsPerBroadcast() {
        final IBluetoothLeAudio service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            Log.d(TAG, Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                return service.getMaximumSubgroupsPerBroadcast();
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return 1;
    }

    /** {@inheritDoc} */
    @Hide
    @Override
    public void close() {
        if (VDBG) Log.d(TAG, "close()");

        mAdapter.closeProfileProxy(this);
    }

    private static BluetoothLeBroadcastSettings buildBroadcastSettingsFromMetadata(
            BluetoothLeAudioContentMetadata contentMetadata, @Nullable byte[] broadcastCode) {
        BluetoothLeBroadcastSubgroupSettings.Builder subgroupBuilder =
                new BluetoothLeBroadcastSubgroupSettings.Builder()
                        .setContentMetadata(contentMetadata);

        BluetoothLeBroadcastSettings.Builder builder =
                new BluetoothLeBroadcastSettings.Builder()
                        .setPublicBroadcast(false)
                        .setBroadcastCode(broadcastCode);
        // builder expect at least one subgroup setting
        builder.addSubgroupSettings(subgroupBuilder.build());
        return builder.build();
    }

    private boolean isEnabled() {
        if (mAdapter.getState() == BluetoothAdapter.STATE_ON) return true;
        return false;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceConnected(IBinder service) {
        mService = IBluetoothLeAudio.Stub.asInterface(service);
        mCallbackWrapper.registerToNewService(mService);
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceDisconnected() {
        mService = null;
    }

    private IBluetoothLeAudio getService() {
        return mService;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public BluetoothAdapter getAdapter() {
        return mAdapter;
    }
}

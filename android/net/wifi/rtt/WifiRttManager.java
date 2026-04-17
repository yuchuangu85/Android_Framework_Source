/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi.rtt;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.Manifest.permission.LOCATION_HARDWARE;
import static android.Manifest.permission.NEARBY_WIFI_DEVICES;
import static android.annotation.RestrictedForEnvironment.ENVIRONMENT_SDK_RUNTIME;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresApi;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.RestrictedForEnvironment;
import android.annotation.SdkConstant;
import android.annotation.Size;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.AttributionSource;
import android.content.Context;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.util.Environment;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.WorkSource;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.modules.utils.build.SdkLevel;
import com.android.wifi.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides the primary API for measuring distance (range) to other devices using the
 * IEEE 802.11mc Wi-Fi Round Trip Time (RTT) technology.
 * <p>
 * The devices which can be ranged include:
 * <li>Access Points (APs)
 * <li>Wi-Fi Aware peers
 * <p>
 * Ranging requests are triggered using
 * {@link #startRanging(RangingRequest, Executor, RangingResultCallback)}. Results (in case of
 * successful operation) are returned in the {@link RangingResultCallback#onRangingResults(List)}
 * callback.
 * <p>
 *     Wi-Fi RTT may not be usable at some points, e.g. when Wi-Fi is disabled. To validate that
 *     the functionality is available use the {@link #isAvailable()} function. To track
 *     changes in RTT usability register for the {@link #ACTION_WIFI_RTT_STATE_CHANGED}
 *     broadcast. Note that this broadcast is not sticky - you should register for it and then
 *     check the above API to avoid a race condition.
 */
@RestrictedForEnvironment(
        environments = ENVIRONMENT_SDK_RUNTIME, from = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@SystemService(Context.WIFI_RTT_RANGING_SERVICE)
public class WifiRttManager {
    private static final String TAG = "WifiRttManager";
    private static final boolean VDBG = false;

    private final Context mContext;
    private final IWifiRttManager mService;
    // Map to store and retrieve different IProximityDetectionMacAddressCallback listeners.
    private static final SparseArray<IProximityDetectionMacAddressCallback>
            sProximityDetectionMacAddressCallbackMap = new SparseArray<>();

    /**
     * Broadcast intent action to indicate that the state of Wi-Fi RTT availability has changed.
     * Use the {@link #isAvailable()} to query the current status.
     * This broadcast is <b>not</b> sticky, use the {@link #isAvailable()} API after registering
     * the broadcast to check the current state of Wi-Fi RTT.
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_WIFI_RTT_STATE_CHANGED =
            "android.net.wifi.rtt.action.WIFI_RTT_STATE_CHANGED";

    /**
     * Bundle key to access if one-sided Wi-Fi RTT is supported. When it is not supported, only
     * two-sided RTT can be performed, which requires responder supports IEEE 802.11mc and this can
     * be determined by the method {@link ScanResult#is80211mcResponder()}
     */
    public static final String CHARACTERISTICS_KEY_BOOLEAN_ONE_SIDED_RTT = "key_one_sided_rtt";
     /**
     * Bundle key to access if getting the Location Configuration Information(LCI) from responder is
      * supported.
     * @see ResponderLocation
     */
    public static final String CHARACTERISTICS_KEY_BOOLEAN_LCI = "key_lci";
    /**
     * Bundle key to access if getting the Location Civic Report(LCR) from responder is supported.
     * @see ResponderLocation
     */
    public static final String CHARACTERISTICS_KEY_BOOLEAN_LCR = "key_lcr";

    /**
     * Bundle key to access if device supports to be a responder in station mode
     */
    public static final String CHARACTERISTICS_KEY_BOOLEAN_STA_RESPONDER = "key_sta_responder";

    /**
     * Bundle key to access if device supports to be a IEEE 802.11az non-trigger based initiator
     */
    @FlaggedApi(Flags.FLAG_ANDROID_V_WIFI_API)
    public static final String CHARACTERISTICS_KEY_BOOLEAN_NTB_INITIATOR = "key_ntb_initiator";

    /**
     * Bundle key to access if device supports secure HE-LTF (High Efficiency Long Training Field).
     * Secure HE-LTF is a critical security enhancement in the IEEE 802.11az standard that aims to
     * protect ranging measurements from spoofing and manipulation.
     */
    @FlaggedApi(Flags.FLAG_SECURE_RANGING)
    public static final String CHARACTERISTICS_KEY_BOOLEAN_SECURE_HE_LTF_SUPPORTED =
            "key_secure_he_ltf_supported";

    /**
     * Bundle key to access if device supports ranging frame protection. IEEE 802.11az introduces
     * Protected Management Frames for FTM (Fine Timing Measurement), adding a layer of encryption
     * and integrity protection to these frames.
     */
    @FlaggedApi(Flags.FLAG_SECURE_RANGING)
    public static final String CHARACTERISTICS_KEY_BOOLEAN_RANGING_FRAME_PROTECTION_SUPPORTED =
            "key_rnm_mfp_supported";

    /**
     * Bundle key to access the maximum supported secure HE-LTF protocol version.
     */
    @FlaggedApi(Flags.FLAG_SECURE_RANGING)
    public static final String CHARACTERISTICS_KEY_INT_MAX_SUPPORTED_SECURE_HE_LTF_PROTO_VERSION =
            "key_max_supported_secure_he_ltf_proto_ver";

    /** @hide */
    @StringDef(prefix = { "CHARACTERISTICS_KEY_"}, value = {
            CHARACTERISTICS_KEY_BOOLEAN_ONE_SIDED_RTT,
            CHARACTERISTICS_KEY_BOOLEAN_LCI,
            CHARACTERISTICS_KEY_BOOLEAN_LCR,
            CHARACTERISTICS_KEY_BOOLEAN_STA_RESPONDER,
            CHARACTERISTICS_KEY_BOOLEAN_NTB_INITIATOR,
            CHARACTERISTICS_KEY_BOOLEAN_SECURE_HE_LTF_SUPPORTED,
            CHARACTERISTICS_KEY_BOOLEAN_RANGING_FRAME_PROTECTION_SUPPORTED,
            CHARACTERISTICS_KEY_INT_MAX_SUPPORTED_SECURE_HE_LTF_PROTO_VERSION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RttCharacteristicsKey {}

    /** @hide */
    public WifiRttManager(@NonNull Context context, @NonNull IWifiRttManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns the current status of RTT API: whether or not RTT is available. To track
     * changes in the state of RTT API register for the
     * {@link #ACTION_WIFI_RTT_STATE_CHANGED} broadcast.
     * <p>Note: availability of RTT does not mean that the app can use the API. The app's
     * permissions and platform Location Mode are validated at run-time.
     *
     * @return A boolean indicating whether the app can use the RTT API at this time (true) or
     * not (false).
     */
    public boolean isAvailable() {
        try {
            return mService.isAvailable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Initiate a request to range to a set of devices specified in the {@link RangingRequest}.
     * Results will be returned in the {@link RangingResultCallback} set of callbacks.
     * <p>
     * Ranging request with only Wifi Aware peers can be performed with either
     * {@link android.Manifest.permission#NEARBY_WIFI_DEVICES} with
     * android:usesPermissionFlags="neverForLocation", or
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION}. All other types of ranging requests
     * require {@link android.Manifest.permission#ACCESS_FINE_LOCATION}.
     *
     * @param request  A request specifying a set of devices whose distance measurements are
     *                 requested.
     * @param executor The Executor on which to run the callback.
     * @param callback A callback for the result of the ranging request.
     */
    @RequiresPermission(allOf = {ACCESS_FINE_LOCATION, CHANGE_WIFI_STATE, ACCESS_WIFI_STATE,
            NEARBY_WIFI_DEVICES})
    public void startRanging(@NonNull RangingRequest request,
            @NonNull @CallbackExecutor Executor executor, @NonNull RangingResultCallback callback) {
        startRanging(null, request, executor, callback);
    }

    /**
     * Initiate a request to range to a set of devices specified in the {@link RangingRequest}.
     * Results will be returned in the {@link RangingResultCallback} set of callbacks.
     * <p>
     * Ranging request with only Wifi Aware peers can be performed with either
     * {@link android.Manifest.permission#NEARBY_WIFI_DEVICES} with
     * android:usesPermissionFlags="neverForLocation", or
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION}. All other types of ranging requests
     * require {@link android.Manifest.permission#ACCESS_FINE_LOCATION}.
     *
     * @param workSource A mechanism to specify an alternative work-source for the request.
     * @param request  A request specifying a set of devices whose distance measurements are
     *                 requested.
     * @param executor The Executor on which to run the callback.
     * @param callback A callback for the result of the ranging request.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {LOCATION_HARDWARE, ACCESS_FINE_LOCATION, CHANGE_WIFI_STATE,
            ACCESS_WIFI_STATE, NEARBY_WIFI_DEVICES}, conditional = true)
    public void startRanging(@Nullable WorkSource workSource, @NonNull RangingRequest request,
            @NonNull @CallbackExecutor Executor executor, @NonNull RangingResultCallback callback) {
        if (VDBG) {
            Log.v(TAG, "startRanging: workSource=" + workSource + ", request=" + request
                    + ", callback=" + callback + ", executor=" + executor);
        }

        if (executor == null) {
            throw new IllegalArgumentException("Null executor provided");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Null callback provided");
        }

        Binder binder = new Binder();
        try {
            Bundle extras = new Bundle();
            if (SdkLevel.isAtLeastS()) {
                extras.putParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE,
                        getAttributionSourceInternal());
            }
            mService.startRanging(binder, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), workSource, request, new IRttCallback.Stub() {
                        @Override
                        public void onRangingFailure(int status) throws RemoteException {
                            clearCallingIdentity();
                            executor.execute(() -> callback.onRangingFailure(status));
                        }

                        @Override
                        public void onRangingResults(List<RangingResult> results)
                                throws RemoteException {
                            clearCallingIdentity();
                            executor.execute(() -> callback.onRangingResults(results));
                        }
                    }, extras);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private AttributionSource getAttributionSourceInternal() {
        return SdkLevel.isAtLeastU()
                ? mContext.createDeviceContext(Context.DEVICE_ID_DEFAULT).getAttributionSource()
                : mContext.getAttributionSource();
    }

    /**
     * Cancel all ranging requests for the specified work sources. The requests have been requested
     * using {@link #startRanging(WorkSource, RangingRequest, Executor, RangingResultCallback)}.
     *
     * @param workSource The work-sources of the requesters.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {LOCATION_HARDWARE})
    public void cancelRanging(@Nullable WorkSource workSource) {
        if (VDBG) {
            Log.v(TAG, "cancelRanging: workSource=" + workSource);
        }

        try {
            mService.cancelRanging(workSource);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a Bundle which represents the characteristics of the Wi-Fi RTT interface: a set of
     * parameters which specify feature support. Each parameter can be accessed by the specified
     * Bundle key, one of the {@code CHARACTERISTICS_KEY_*} value.
     * <p>
     * May return an empty Bundle if the Wi-Fi RTT service is not initialized.
     *
     * @return A Bundle specifying feature support of RTT.
     */
    @RequiresPermission(ACCESS_WIFI_STATE)
    @NonNull
    public Bundle getRttCharacteristics() {
        try {
            return mService.getRttCharacteristics();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the characteristics of the Proximity Detection feature, if available.
     *
     * <p>This method provides a {@link ProximityDetectionCharacteristics} object which contains
     * various parameters and capabilities of the device's Proximity Detection implementation.
     *
     * <p>For this feature to be available, Wi-Fi must be enabled (see
     * {@link WifiManager#isWifiEnabled()}) and general Wi-Fi RTT must be available (see
     * {@link #isAvailable()}).
     *
     * <p>To check if Proximity Detection is supported by the hardware and software, call this
     * method and verify that the result is not {@code null}. A {@code null} return value
     * indicates that the feature is unavailable, either because it is not supported or because
     * one of the prerequisites is not met.
     *
     * @return A {@link ProximityDetectionCharacteristics} object if the feature is available,
     *         or {@code null} if the feature is not supported or currently unavailable.
     *
     * @throws UnsupportedOperationException if the API is not supported on this SDK version.
     * @hide
     */
    @SystemApi
    @RequiresApi(37)
    @FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
    @RequiresPermission(android.Manifest.permission.NETWORK_STACK)
    @Nullable
    public ProximityDetectionCharacteristics getProximityDetectionCharacteristics() {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        if (VDBG) {
            Log.v(TAG, "getProximityDetectionCharacteristics() ");
        }
        try {
            return mService.getProximityDetectionCharacteristics();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set proximity detection device name.
     *
     * <p>
     * This name will be added in the proximity ranging capability attribute contained in the
     * USD discovery frames. The device name set in the device can be obtained via the
     * proximity detection characteristics, WifiRttManager#getProximityDetectionCharacteristics().
     * The device name may be shared Out-Of-Band to identify the device discovered via Out-Of-Band
     * discovery channel.
     *
     * @param deviceName A friendly name of the proximity detection device. The name must be a
     * *                   UTF-8 string, and its byte representation must not exceed 32 bytes.
     *
     * @throws UnsupportedOperationException if the API is not supported on this SDK version.
     * @throws IllegalArgumentException if the {@code deviceName} is null, empty, or longer than
     *                                  32 characters.
     *
     * @hide
     */
    @SystemApi
    @RequiresApi(37)
    @FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
    @RequiresPermission(android.Manifest.permission.NETWORK_STACK)
    public void setProximityDetectionDeviceName(
            @NonNull @Size(min = 1, max = 32) String deviceName) {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        if (TextUtils.isEmpty(deviceName)) {
            throw new IllegalArgumentException("deviceName must not be null or empty");
        }
        if (deviceName.getBytes(StandardCharsets.UTF_8).length > 32) {
            throw new IllegalArgumentException(
                    "deviceName must not exceed 32 bytes in UTF-8 encoding");
        }
        if (VDBG) {
            Log.v(TAG, "setProximityDetectionDeviceName : " + deviceName);
        }
        try {
            mService.setProximityDetectionDeviceName(deviceName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the randomized MAC address currently used by the device for Proximity Detection
     * operations. This address is used to enhance privacy by preventing tracking of the device's
     * real hardware MAC address during Proximity Detection procedures.
     * <p>
     * The randomized MAC address may change over time. Call this method before initiating an
     * out-of-band discovery process where the MAC address needs to be shared with a peer.
     * <p>
     * Register a callback using
     * {@link #registerProximityDetectionMacAddressCallback(Executor, Consumer)} to
     * receive updates on the randomized MAC address.
     *
     * @return The current randomized {@link MacAddress} for proximity detection operations.
     * Returns {@code null} if Proximity Detection is not supported, Wi-Fi is not currently active,
     * or if an error occurs.
     *
     * @throws UnsupportedOperationException if the API is not supported on this SDK version.
     *
     * @hide
     */
    @SystemApi
    @RequiresApi(37)
    @FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
    @RequiresPermission(android.Manifest.permission.NETWORK_STACK)
    @Nullable
    public MacAddress getProximityDetectionRandomizedMacAddress() {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        if (VDBG) {
            Log.v(TAG, "getProximityDetectionRandomizedMacAddress() ");
        }
        Bundle extras = new Bundle();
        extras.putParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE,
                getAttributionSourceInternal());
        try {
            return mService.getProximityDetectionRandomizedMacAddress();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a callback to be notified of changes to the proximity detection randomized
     * MAC address. The callback will be immediately invoked with the current MAC address upon
     * successful registration.
     * <p>
     * This registration is essential for applications that share the device's MAC address
     * out-of-band (e.g., via Bluetooth or a cloud service) for peer discovery. To enhance
     * privacy, the randomized MAC address is not static and may change periodically.
     * <p>
     * If an application retrieves the MAC address only once using
     * {@link #getProximityDetectionRandomizedMacAddress()} and that address subsequently changes,
     * any peer attempting to use the old, stale address will fail to range. By
     * registering this callback, an application is notified of the new MAC address as soon as it
     * changes. The application should then invalidate the old MAC address and update any ongoing
     * operations or re-share the new address with its peers as needed.
     *
     * @param executor The {@link Executor} on which to invoke the callback.
     * @param callback A {@link Consumer} that will be invoked with the new {@link MacAddress}.
     *
     * @throws UnsupportedOperationException if the API is not supported on this SDK version.
     * @throws IllegalArgumentException if the callback or executor is null.
     * @hide
     */
    @RequiresApi(37)
    @SystemApi
    @FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
    @RequiresPermission(android.Manifest.permission.NETWORK_STACK)
    public void registerProximityDetectionMacAddressCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<MacAddress> callback) {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        Objects.requireNonNull(callback, "Callback cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");
        Bundle extras = new Bundle();
        extras.putParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE,
                getAttributionSourceInternal());
        if (VDBG) {
            Log.v(TAG, "registerProximityDetectionMacAddressCallback: executor=" + executor
                    + ", callback=" + callback);
        }
        final int callbackIdentifier = System.identityHashCode(callback);
        synchronized (sProximityDetectionMacAddressCallbackMap) {
            try {
                if (sProximityDetectionMacAddressCallbackMap.contains(callbackIdentifier)) {
                    Log.w(TAG, "Same callback already registered");
                    return;
                }
                IProximityDetectionMacAddressCallback callbackProxy =
                        new IProximityDetectionMacAddressCallback.Stub() {
                            @Override
                            @RequiresNoPermission
                            public void onResult(@NonNull MacAddress newMacAddress)
                                    throws RemoteException {
                                Binder.clearCallingIdentity();
                                executor.execute(() -> callback.accept(newMacAddress));
                            }
                        };
                sProximityDetectionMacAddressCallbackMap.put(callbackIdentifier, callbackProxy);
                mService.registerProximityDetectionMacAddressCallback(callbackProxy);
            } catch (RemoteException e) {
                sProximityDetectionMacAddressCallbackMap.remove(callbackIdentifier);
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters a previously registered proximity detection MAC address callback.
     *
     * @param callback The {@link Consumer} to unregister.
     *
     * @throws UnsupportedOperationException if the API is not supported on this SDK version.
     * @throws IllegalArgumentException if the callback is null.
     * @hide
     */
    @RequiresApi(37)
    @SystemApi
    @FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
    @RequiresPermission(android.Manifest.permission.NETWORK_STACK)
    public void unregisterProximityDetectionMacAddressCallback(
            @NonNull Consumer<MacAddress> callback) {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        Objects.requireNonNull(callback, "Callback cannot be null");
        Bundle extras = new Bundle();
        extras.putParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE,
                getAttributionSourceInternal());
        if (VDBG) {
            Log.v(TAG, "unregisterProximityDetectionMacAddressCallback: callback="
                    + callback);
        }
        final int callbackIdentifier = System.identityHashCode(callback);
        synchronized (sProximityDetectionMacAddressCallbackMap) {
            try {
                if (!sProximityDetectionMacAddressCallbackMap.contains(callbackIdentifier)) {
                    Log.w(TAG, "Unknown external callback " + callbackIdentifier);
                    return;
                }
                mService.unregisterProximityDetectionMacAddressCallback(
                        sProximityDetectionMacAddressCallbackMap.get(callbackIdentifier));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } finally {
                sProximityDetectionMacAddressCallbackMap.remove(callbackIdentifier);
            }
        }
    }

    /**
     * Initiate a continuous/periodic request to range to a set of devices specified in the
     * {@link RangingRequest}.
     * Results will be returned in the {@link ContinuousRangingResultCallback} set of callbacks.
     * <p>
     * Only one continuous ranging session can be active at a time. If an ongoing session
     * exists, this request will fail and an {@code onRangingFailure()} callback with
     * {@code STATUS_BUSY} will be delivered. The existing session must be explicitly
     * terminated by calling {@link #stopContinuousRanging(WorkSource)}.
     *
     * @param workSource A mechanism to specify an alternative work-source for the request.
     * @param request  A request specifying a set of devices whose distance measurements are
     *                 requested.
     * @param executor The Executor on which to run the callback.
     * @param callback A callback for the result of the ranging request.
     *
     * @hide
     */
    @SystemApi
    @RequiresApi(37)
    @FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
    @RequiresPermission(android.Manifest.permission.NETWORK_STACK)
    public void startContinuousRanging(@Nullable WorkSource workSource,
            @NonNull RangingRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ContinuousRangingResultCallback callback) {
        if (VDBG) {
            Log.v(TAG, "startContinuousRanging: workSource=" + workSource + ", request=" + request
                    + ", callback=" + callback + ", executor=" + executor);
        }
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        Binder binder = new Binder();
        try {
            Bundle extras = new Bundle();
            extras.putParcelable(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE,
                    getAttributionSourceInternal());
            mService.startContinuousRanging(binder, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), workSource, request,
                    new IContinuousRangingResultCallback.Stub() {
                        @Override
                        @RequiresNoPermission
                        public void onRangingFailure(int code) {
                            clearCallingIdentity();
                            executor.execute(() -> callback.onRangingFailure(code));
                        }

                        @Override
                        @RequiresNoPermission
                        public void onRangingResults(List<RangingResult> results) {
                            clearCallingIdentity();
                            executor.execute(() -> callback.onRangingResults(results));
                        }

                        @Override
                        @RequiresNoPermission
                        public void onRangingStopped(int reason) {
                            clearCallingIdentity();
                            executor.execute(() -> callback.onRangingStopped(reason));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop all continuous ranging requests for the specified work sources.
     * The requests have been requested using {@link #startContinuousRanging(WorkSource,
     * RangingRequest, Executor, ContinuousRangingResultCallback)}. This method will cause the
     * {@link ContinuousRangingResultCallback#onRangingStopped(int)} method to be invoked.
     * <p> Calling this when no continuous session is active has no effect.
     *
     * @param workSource The work-sources of the requesters.
     *
     * @hide
     */
    @SystemApi
    @RequiresApi(37)
    @FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
    @RequiresPermission(android.Manifest.permission.NETWORK_STACK)
    public void stopContinuousRanging(@Nullable WorkSource workSource) {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        if (VDBG) {
            Log.v(TAG, "stopContinuousRanging: workSource=" + workSource);
        }
        try {
            mService.stopContinuousRanging(workSource);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}

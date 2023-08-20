/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.media.INativeSpatializerCallback;
import android.media.ISpatializer;
import android.media.ISpatializerCallback;
import android.media.ISpatializerHeadToSoundStagePoseCallback;
import android.media.ISpatializerHeadTrackerAvailableCallback;
import android.media.ISpatializerHeadTrackingCallback;
import android.media.ISpatializerHeadTrackingModeCallback;
import android.media.ISpatializerOutputCallback;
import android.media.MediaMetrics;
import android.media.SpatializationLevel;
import android.media.SpatializationMode;
import android.media.Spatializer;
import android.media.SpatializerHeadTrackingMode;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A helper class to manage Spatializer related functionality
 */
public class SpatializerHelper {

    private static final String TAG = "AS.SpatializerHelper";
    private static final boolean DEBUG = true;
    private static final boolean DEBUG_MORE = false;

    private static void logd(String s) {
        if (DEBUG) {
            Log.i(TAG, s);
        }
    }

    private final @NonNull AudioSystemAdapter mASA;
    private final @NonNull AudioService mAudioService;
    private @Nullable SensorManager mSensorManager;

    //------------------------------------------------------------

    private static final SparseIntArray SPAT_MODE_FOR_DEVICE_TYPE = new SparseIntArray(14) {
        {
            append(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_WIRED_HEADSET, SpatializationMode.SPATIALIZER_BINAURAL);
            append(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, SpatializationMode.SPATIALIZER_BINAURAL);
            // assumption for A2DP: mostly headsets
            append(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, SpatializationMode.SPATIALIZER_BINAURAL);
            append(AudioDeviceInfo.TYPE_DOCK, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_USB_ACCESSORY, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_USB_DEVICE, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_USB_HEADSET, SpatializationMode.SPATIALIZER_BINAURAL);
            append(AudioDeviceInfo.TYPE_LINE_ANALOG, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_LINE_DIGITAL, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_AUX_LINE, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_BLE_HEADSET, SpatializationMode.SPATIALIZER_BINAURAL);
            append(AudioDeviceInfo.TYPE_BLE_SPEAKER, SpatializationMode.SPATIALIZER_TRANSAURAL);
            // assumption that BLE broadcast would be mostly consumed on headsets
            append(AudioDeviceInfo.TYPE_BLE_BROADCAST, SpatializationMode.SPATIALIZER_BINAURAL);
        }
    };

    private static final int[] WIRELESS_TYPES = { AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
    };

    // Spatializer state machine
    /*package*/ static final int STATE_UNINITIALIZED = 0;
    /*package*/ static final int STATE_NOT_SUPPORTED = 1;
    /*package*/ static final int STATE_DISABLED_UNAVAILABLE = 3;
    /*package*/ static final int STATE_ENABLED_UNAVAILABLE = 4;
    /*package*/ static final int STATE_ENABLED_AVAILABLE = 5;
    /*package*/ static final int STATE_DISABLED_AVAILABLE = 6;
    private int mState = STATE_UNINITIALIZED;

    private boolean mFeatureEnabled = false;
    /** current level as reported by native Spatializer in callback */
    private int mSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    private int mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    private boolean mTransauralSupported = false;
    private boolean mBinauralSupported = false;
    private boolean mIsHeadTrackingSupported = false;
    private int[] mSupportedHeadTrackingModes = new int[0];
    private int mActualHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
    private int mDesiredHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD;
    private boolean mHeadTrackerAvailable = false;
    /**
     *  The desired head tracking mode when enabling head tracking, tracks mDesiredHeadTrackingMode,
     *  except when head tracking gets disabled through setting the desired mode to
     *  {@link Spatializer#HEAD_TRACKING_MODE_DISABLED}.
     */
    private int mDesiredHeadTrackingModeWhenEnabled = Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD;
    private int mSpatOutput = 0;
    private @Nullable ISpatializer mSpat;
    private @Nullable SpatializerCallback mSpatCallback;
    private @Nullable SpatializerHeadTrackingCallback mSpatHeadTrackingCallback =
            new SpatializerHeadTrackingCallback();
    private @Nullable HelperDynamicSensorCallback mDynSensorCallback;

    // default attributes and format that determine basic availability of spatialization
    private static final AudioAttributes DEFAULT_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build();
    private static final AudioFormat DEFAULT_FORMAT = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .build();
    // device array to store the routing for the default attributes and format, initialized to
    // an empty list as routing hasn't been established yet
    private static ArrayList<AudioDeviceAttributes> sRoutingDevices = new ArrayList<>(0);

    //---------------------------------------------------------------
    // audio device compatibility / enabled
    /**
     * List of device types that can be used on this device with Spatial Audio.
     * It is initialized based on the transaural/binaural capabilities
     * of the effect.
     */
    private final ArrayList<Integer> mSACapableDeviceTypes = new ArrayList<>(0);

    /**
     * List of devices where Spatial Audio is possible. Each device can be enabled or disabled
     * (== user choice to use or not)
     */
    @GuardedBy("this")
    private final ArrayList<SADeviceState> mSADevices = new ArrayList<>(0);

    //------------------------------------------------------
    // initialization
    @SuppressWarnings("StaticAssignmentInConstructor")
    SpatializerHelper(@NonNull AudioService mother, @NonNull AudioSystemAdapter asa,
            boolean binauralEnabledDefault,
            boolean transauralEnabledDefault,
            boolean headTrackingEnabledDefault) {
        mAudioService = mother;
        mASA = asa;
        // "StaticAssignmentInConstructor" warning is suppressed as the SpatializerHelper being
        // constructed here is the factory for SADeviceState, thus SADeviceState and its
        // private static field sHeadTrackingEnabledDefault should never be accessed directly.
        SADeviceState.sBinauralEnabledDefault = binauralEnabledDefault;
        SADeviceState.sTransauralEnabledDefault = transauralEnabledDefault;
        SADeviceState.sHeadTrackingEnabledDefault = headTrackingEnabledDefault;
    }

    synchronized void init(boolean effectExpected, @Nullable String settings) {
        loglogi("init effectExpected=" + effectExpected);
        if (!effectExpected) {
            loglogi("init(): setting state to STATE_NOT_SUPPORTED due to effect not expected");
            mState = STATE_NOT_SUPPORTED;
            return;
        }
        if (mState != STATE_UNINITIALIZED) {
            throw new IllegalStateException(logloge("init() called in state " + mState));
        }
        // is there a spatializer?
        mSpatCallback = new SpatializerCallback();
        final ISpatializer spat = AudioSystem.getSpatializer(mSpatCallback);
        if (spat == null) {
            loglogi("init(): No Spatializer found");
            mState = STATE_NOT_SUPPORTED;
            return;
        }
        // capabilities of spatializer?
        resetCapabilities();

        try {
            byte[] levels = spat.getSupportedLevels();
            if (levels == null
                    || levels.length == 0
                    || (levels.length == 1
                    && levels[0] == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE)) {
                logloge("init(): found Spatializer is useless");
                mState = STATE_NOT_SUPPORTED;
                return;
            }
            for (byte level : levels) {
                loglogi("init(): found support for level: " + level);
                if (level == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL) {
                    loglogi("init(): setting capable level to LEVEL_MULTICHANNEL");
                    mCapableSpatLevel = level;
                    break;
                }
            }

            // Note: head tracking support must be initialized before spatialization modes as
            // addCompatibleAudioDevice() calls onRoutingUpdated() which will initialize the
            // sensors according to mIsHeadTrackingSupported.
            mIsHeadTrackingSupported = spat.isHeadTrackingSupported();
            if (mIsHeadTrackingSupported) {
                final byte[] values = spat.getSupportedHeadTrackingModes();
                ArrayList<Integer> list = new ArrayList<>(0);
                for (byte value : values) {
                    switch (value) {
                        case SpatializerHeadTrackingMode.OTHER:
                        case SpatializerHeadTrackingMode.DISABLED:
                            // not expected here, skip
                            break;
                        case SpatializerHeadTrackingMode.RELATIVE_WORLD:
                        case SpatializerHeadTrackingMode.RELATIVE_SCREEN:
                            list.add(headTrackingModeTypeToSpatializerInt(value));
                            break;
                        default:
                            Log.e(TAG, "Unexpected head tracking mode:" + value,
                                    new IllegalArgumentException("invalid mode"));
                            break;
                    }
                }
                mSupportedHeadTrackingModes = new int[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    mSupportedHeadTrackingModes[i] = list.get(i);
                }
                mActualHeadTrackingMode =
                        headTrackingModeTypeToSpatializerInt(spat.getActualHeadTrackingMode());
            } else {
                mDesiredHeadTrackingModeWhenEnabled = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
                mDesiredHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
            }

            byte[] spatModes = spat.getSupportedModes();
            for (byte mode : spatModes) {
                switch (mode) {
                    case SpatializationMode.SPATIALIZER_BINAURAL:
                        mBinauralSupported = true;
                        break;
                    case SpatializationMode.SPATIALIZER_TRANSAURAL:
                        mTransauralSupported = true;
                        break;
                    default:
                        logloge("init(): Spatializer reports unknown supported mode:" + mode);
                        break;
                }
            }
            // if neither transaural nor binaural is supported, bail
            if (!mBinauralSupported && !mTransauralSupported) {
                mState = STATE_NOT_SUPPORTED;
                return;
            }

            // initialize list of compatible devices
            for (int i = 0; i < SPAT_MODE_FOR_DEVICE_TYPE.size(); i++) {
                int mode = SPAT_MODE_FOR_DEVICE_TYPE.valueAt(i);
                if ((mode == (int) SpatializationMode.SPATIALIZER_BINAURAL && mBinauralSupported)
                        || (mode == (int) SpatializationMode.SPATIALIZER_TRANSAURAL
                            && mTransauralSupported)) {
                    mSACapableDeviceTypes.add(SPAT_MODE_FOR_DEVICE_TYPE.keyAt(i));
                }
            }

            // When initialized from AudioService, the settings string will be non-null.
            // Saved settings need to be applied after spatialization support is initialized above.
            if (settings != null) {
                setSADeviceSettings(settings);
            }

            // for both transaural / binaural, we are not forcing enablement as the init() method
            // could have been called another time after boot in case of audioserver restart
            addCompatibleAudioDevice(
                    new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_SPEAKER, ""),
                            false /*forceEnable*/);
            // not force-enabling as this device might already be in the device list
            addCompatibleAudioDevice(
                    new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE, ""),
                            false /*forceEnable*/);
        } catch (RemoteException e) {
            resetCapabilities();
        } finally {
            if (spat != null) {
                try {
                    spat.release();
                } catch (RemoteException e) { /* capable level remains at NONE*/ }
            }
        }
        if (mCapableSpatLevel == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
            mState = STATE_NOT_SUPPORTED;
            return;
        }
        mState = STATE_DISABLED_UNAVAILABLE;
        sRoutingDevices = getRoutingDevices(DEFAULT_ATTRIBUTES);
        // note at this point mSpat is still not instantiated
    }

    /**
     * Like init() but resets the state and spatializer levels
     * @param featureEnabled
     */
    synchronized void reset(boolean featureEnabled) {
        loglogi("Resetting featureEnabled=" + featureEnabled);
        releaseSpat();
        mState = STATE_UNINITIALIZED;
        mSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        mActualHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
        init(true, null /* settings */);
        setSpatializerEnabledInt(featureEnabled);
    }

    private void resetCapabilities() {
        mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        mBinauralSupported = false;
        mTransauralSupported = false;
        mIsHeadTrackingSupported = false;
        mSupportedHeadTrackingModes = new int[0];
    }

    //------------------------------------------------------
    // routing monitoring
    synchronized void onRoutingUpdated() {
        if (!mFeatureEnabled) {
            return;
        }
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                return;
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_ENABLED_AVAILABLE:
            case STATE_DISABLED_AVAILABLE:
                break;
        }

        sRoutingDevices = getRoutingDevices(DEFAULT_ATTRIBUTES);

        // check validity of routing information
        if (sRoutingDevices.isEmpty()) {
            logloge("onRoutingUpdated: no device, no Spatial Audio");
            setDispatchAvailableState(false);
            // not changing the spatializer level as this is likely a transient state
            return;
        }
        final AudioDeviceAttributes currentDevice = sRoutingDevices.get(0);

        // is media routed to a new device?
        if (isWireless(currentDevice.getType())) {
            addWirelessDeviceIfNew(currentDevice);
        }

        // find if media device enabled / available
        final Pair<Boolean, Boolean> enabledAvailable = evaluateState(currentDevice);

        boolean able = false;
        if (enabledAvailable.second) {
            // available for Spatial audio, check w/ effect
            able = canBeSpatializedOnDevice(DEFAULT_ATTRIBUTES, DEFAULT_FORMAT, sRoutingDevices);
            loglogi("onRoutingUpdated: can spatialize media 5.1:" + able
                    + " on device:" + currentDevice);
            setDispatchAvailableState(able);
        } else {
            loglogi("onRoutingUpdated: device:" + currentDevice
                    + " not available for Spatial Audio");
            setDispatchAvailableState(false);
        }

        boolean enabled = able && enabledAvailable.first;
        if (enabled) {
            loglogi("Enabling Spatial Audio since enabled for media device:"
                    + currentDevice);
        } else {
            loglogi("Disabling Spatial Audio since disabled for media device:"
                    + currentDevice);
        }
        if (mSpat != null) {
            byte level = enabled ? (byte) Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL
                    : (byte) Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
            loglogi("Setting spatialization level to: " + level);
            try {
                mSpat.setLevel(level);
            } catch (RemoteException e) {
                Log.e(TAG, "onRoutingUpdated() Can't set spatializer level", e);
                // try to recover by resetting the native spatializer state
                postReset();
                return;
            }
        }

        setDispatchFeatureEnabledState(enabled, "onRoutingUpdated");

        if (mDesiredHeadTrackingMode != Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED
                && mDesiredHeadTrackingMode != Spatializer.HEAD_TRACKING_MODE_DISABLED) {
            postInitSensors();
        }
    }

    private void postReset() {
        mAudioService.postResetSpatializer();
    }

    //------------------------------------------------------
    // spatializer callback from native
    private final class SpatializerCallback extends INativeSpatializerCallback.Stub {

        public void onLevelChanged(byte level) {
            loglogi("SpatializerCallback.onLevelChanged level:" + level);
            synchronized (SpatializerHelper.this) {
                mSpatLevel = spatializationLevelToSpatializerInt(level);
            }
            // TODO use reported spat level to change state

            // init sensors
            postInitSensors();
        }

        public void onOutputChanged(int output) {
            loglogi("SpatializerCallback.onOutputChanged output:" + output);
            int oldOutput;
            synchronized (SpatializerHelper.this) {
                oldOutput = mSpatOutput;
                mSpatOutput = output;
            }
            if (oldOutput != output) {
                dispatchOutputUpdate(output);
            }
        }
    };

    //------------------------------------------------------
    // spatializer head tracking callback from native
    private final class SpatializerHeadTrackingCallback
            extends ISpatializerHeadTrackingCallback.Stub {
        public void onHeadTrackingModeChanged(byte mode) {
            int oldMode, newMode;
            synchronized (this) {
                oldMode = mActualHeadTrackingMode;
                mActualHeadTrackingMode = headTrackingModeTypeToSpatializerInt(mode);
                newMode = mActualHeadTrackingMode;
            }
            loglogi("SpatializerHeadTrackingCallback.onHeadTrackingModeChanged mode:"
                    + Spatializer.headtrackingModeToString(newMode));
            if (oldMode != newMode) {
                dispatchActualHeadTrackingMode(newMode);
            }
        }

        public void onHeadToSoundStagePoseUpdated(float[] headToStage) {
            if (headToStage == null) {
                Log.e(TAG, "SpatializerHeadTrackingCallback.onHeadToStagePoseUpdated"
                        + "null transform");
                return;
            }
            if (headToStage.length != 6) {
                Log.e(TAG, "SpatializerHeadTrackingCallback.onHeadToStagePoseUpdated"
                        + " invalid transform length" + headToStage.length);
                return;
            }
            if (DEBUG_MORE) {
                // 6 values * (4 digits + 1 dot + 2 brackets) = 42 characters
                StringBuilder t = new StringBuilder(42);
                for (float val : headToStage) {
                    t.append("[").append(String.format(Locale.ENGLISH, "%.3f", val)).append("]");
                }
                loglogi("SpatializerHeadTrackingCallback.onHeadToStagePoseUpdated headToStage:"
                        + t);
            }
            dispatchPoseUpdate(headToStage);
        }
    };

    //------------------------------------------------------
    // dynamic sensor callback
    private final class HelperDynamicSensorCallback extends SensorManager.DynamicSensorCallback {
        @Override
        public void onDynamicSensorConnected(Sensor sensor) {
            postInitSensors();
        }

        @Override
        public void onDynamicSensorDisconnected(Sensor sensor) {
            postInitSensors();
        }
    }

    //------------------------------------------------------
    // compatible devices
    /**
     * Return the list of compatible devices, which reflects the device compatible with the
     * spatializer effect, and those that have been explicitly enabled or disabled
     * @return the list of compatible audio devices
     */
    synchronized @NonNull List<AudioDeviceAttributes> getCompatibleAudioDevices() {
        // build unionOf(mCompatibleAudioDevices, mEnabledDevice) - mDisabledAudioDevices
        ArrayList<AudioDeviceAttributes> compatList = new ArrayList<>();
        for (SADeviceState deviceState : mSADevices) {
            if (deviceState.mEnabled) {
                compatList.add(deviceState.getAudioDeviceAttributes());
            }
        }
        return compatList;
    }

    synchronized void addCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        addCompatibleAudioDevice(ada, true /*forceEnable*/);
    }

    /**
     * Add the given device to the list of devices for which spatial audio will be available
     * (== possible).
     * @param ada the compatible device
     * @param forceEnable if true, spatial audio is enabled for this device, regardless of whether
     *                    this device was already in the list. If false, the enabled field is only
     *                    set to true if the device is added to the list, otherwise, if already
     *                    present, the setting is left untouched.
     */
    @GuardedBy("this")
    private void addCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada,
            boolean forceEnable) {
        if (!isDeviceCompatibleWithSpatializationModes(ada)) {
            return;
        }
        loglogi("addCompatibleAudioDevice: dev=" + ada);
        final SADeviceState deviceState = findDeviceStateForAudioDeviceAttributes(ada);
        SADeviceState deviceUpdated = null; // non-null on update.
        if (deviceState != null) {
            if (forceEnable && !deviceState.mEnabled) {
                deviceUpdated = deviceState;
                deviceUpdated.mEnabled = true;
            }
        } else {
            // When adding, force the device type to be a canonical one.
            final int canonicalDeviceType = getCanonicalDeviceType(ada.getType());
            if (canonicalDeviceType == AudioDeviceInfo.TYPE_UNKNOWN) {
                Log.e(TAG, "addCompatibleAudioDevice with incompatible AudioDeviceAttributes "
                        + ada);
                return;
            }
            deviceUpdated = new SADeviceState(canonicalDeviceType, ada.getAddress());
            mSADevices.add(deviceUpdated);
        }
        if (deviceUpdated != null) {
            onRoutingUpdated();
            mAudioService.persistSpatialAudioDeviceSettings();
            logDeviceState(deviceUpdated, "addCompatibleAudioDevice");
        }
    }

    private static final String METRICS_DEVICE_PREFIX = "audio.spatializer.device.";

    // Device logging is accomplished in the Java Audio Service level.
    // (System capabilities is done in the Native AudioPolicyManager level).
    //
    // There may be different devices with the same device type (aliasing).
    // We always send the full device state info on each change.
    private void logDeviceState(SADeviceState deviceState, String event) {
        final int deviceType = AudioDeviceInfo.convertDeviceTypeToInternalDevice(
                deviceState.mDeviceType);
        final String deviceName = AudioSystem.getDeviceName(deviceType);
        new MediaMetrics.Item(METRICS_DEVICE_PREFIX + deviceName)
            .set(MediaMetrics.Property.ADDRESS, deviceState.mDeviceAddress)
            .set(MediaMetrics.Property.ENABLED, deviceState.mEnabled ? "true" : "false")
            .set(MediaMetrics.Property.EVENT, TextUtils.emptyIfNull(event))
            .set(MediaMetrics.Property.HAS_HEAD_TRACKER,
                    deviceState.mHasHeadTracker ? "true" : "false") // this may be updated later.
            .set(MediaMetrics.Property.HEAD_TRACKER_ENABLED,
                    deviceState.mHeadTrackerEnabled ? "true" : "false")
            .record();
    }

    synchronized void removeCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        loglogi("removeCompatibleAudioDevice: dev=" + ada);

        final SADeviceState deviceState = findDeviceStateForAudioDeviceAttributes(ada);
        if (deviceState != null && deviceState.mEnabled) {
            deviceState.mEnabled = false;
            onRoutingUpdated();
            mAudioService.persistSpatialAudioDeviceSettings();
            logDeviceState(deviceState, "removeCompatibleAudioDevice");
        }
    }

    /**
     * Returns a possibly aliased device type which is used
     * for spatial audio settings (or TYPE_UNKNOWN  if it doesn't exist).
     */
    private static @AudioDeviceInfo.AudioDeviceType int getCanonicalDeviceType(int deviceType) {
        if (isWireless(deviceType)) return deviceType;

        final int spatMode = SPAT_MODE_FOR_DEVICE_TYPE.get(deviceType, Integer.MIN_VALUE);
        if (spatMode == SpatializationMode.SPATIALIZER_TRANSAURAL) {
            return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        } else if (spatMode == SpatializationMode.SPATIALIZER_BINAURAL) {
            return AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
        }
        return AudioDeviceInfo.TYPE_UNKNOWN;
    }

    /**
     * Returns the Spatial Audio device state for an audio device attributes
     * or null if it does not exist.
     */
    @GuardedBy("this")
    @Nullable
    private SADeviceState findDeviceStateForAudioDeviceAttributes(AudioDeviceAttributes ada) {
        final int deviceType = ada.getType();
        final boolean isWireless = isWireless(deviceType);
        final int canonicalDeviceType = getCanonicalDeviceType(deviceType);

        for (SADeviceState deviceState : mSADevices) {
            if (deviceState.mDeviceType == canonicalDeviceType
                    && (!isWireless || ada.getAddress().equals(deviceState.mDeviceAddress))) {
                return deviceState;
            }
        }
        return null;
    }

    /**
     * Return if Spatial Audio is enabled and available for the given device
     * @param ada
     * @return a pair of boolean, 1/ enabled? 2/ available?
     */
    private synchronized Pair<Boolean, Boolean> evaluateState(AudioDeviceAttributes ada) {
        final @AudioDeviceInfo.AudioDeviceType int deviceType = ada.getType();
        // is the device type capable of doing SA?
        if (!mSACapableDeviceTypes.contains(deviceType)) {
            Log.i(TAG, "Device incompatible with Spatial Audio dev:" + ada);
            return new Pair<>(false, false);
        }
        // what spatialization mode to use for this device?
        final int spatMode = SPAT_MODE_FOR_DEVICE_TYPE.get(deviceType, Integer.MIN_VALUE);
        if (spatMode == Integer.MIN_VALUE) {
            // error case, device not found
            Log.e(TAG, "no spatialization mode found for device type:" + deviceType);
            return new Pair<>(false, false);
        }
        final SADeviceState deviceState = findDeviceStateForAudioDeviceAttributes(ada);
        if (deviceState == null) {
            // no matching device state?
            Log.i(TAG, "no spatialization device state found for Spatial Audio device:" + ada);
            return new Pair<>(false, false);
        }
        // found the matching device state.
        return new Pair<>(deviceState.mEnabled, true /* available */);
    }

    private synchronized void addWirelessDeviceIfNew(@NonNull AudioDeviceAttributes ada) {
        if (!isDeviceCompatibleWithSpatializationModes(ada)) {
            return;
        }
        if (findDeviceStateForAudioDeviceAttributes(ada) == null) {
            // wireless device types should be canonical, but we translate to be sure.
            final int canonicalDeviceType = getCanonicalDeviceType((ada.getType()));
            if (canonicalDeviceType == AudioDeviceInfo.TYPE_UNKNOWN) {
                Log.e(TAG, "addWirelessDeviceIfNew with incompatible AudioDeviceAttributes "
                        + ada);
                return;
            }
            final SADeviceState deviceState =
                    new SADeviceState(canonicalDeviceType, ada.getAddress());
            mSADevices.add(deviceState);
            mAudioService.persistSpatialAudioDeviceSettings();
            logDeviceState(deviceState, "addWirelessDeviceIfNew"); // may be updated later.
        }
    }

    //------------------------------------------------------
    // states

    synchronized boolean isEnabled() {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
                return false;
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_ENABLED_AVAILABLE:
            default:
                return true;
        }
    }

    synchronized boolean isAvailable() {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
                return false;
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
            default:
                return true;
        }
    }

    synchronized boolean isAvailableForDevice(@NonNull AudioDeviceAttributes ada) {
        if (ada.getRole() != AudioDeviceAttributes.ROLE_OUTPUT) {
            return false;
        }
        return findDeviceStateForAudioDeviceAttributes(ada) != null;
    }

    private synchronized boolean canBeSpatializedOnDevice(@NonNull AudioAttributes attributes,
            @NonNull AudioFormat format, @NonNull ArrayList<AudioDeviceAttributes> devices) {
        if (devices.isEmpty()) {
            return false;
        }
        if (isDeviceCompatibleWithSpatializationModes(devices.get(0))) {
            AudioDeviceAttributes[] devArray = new AudioDeviceAttributes[devices.size()];
            return AudioSystem.canBeSpatialized(attributes, format, devices.toArray(devArray));
        }
        return false;
    }

    private boolean isDeviceCompatibleWithSpatializationModes(@NonNull AudioDeviceAttributes ada) {
        // modeForDevice will be neither transaural or binaural for devices that do not support
        // spatial audio. For instance mono devices like earpiece, speaker safe or sco must
        // not be included.
        final byte modeForDevice = (byte) SPAT_MODE_FOR_DEVICE_TYPE.get(ada.getType(),
                /*default when type not found*/ -1);
        if ((modeForDevice == SpatializationMode.SPATIALIZER_BINAURAL && mBinauralSupported)
                || (modeForDevice == SpatializationMode.SPATIALIZER_TRANSAURAL
                        && mTransauralSupported)) {
            return true;
        }
        return false;
    }

    synchronized void setFeatureEnabled(boolean enabled) {
        loglogi("setFeatureEnabled(" + enabled + ") was featureEnabled:" + mFeatureEnabled);
        if (mFeatureEnabled == enabled) {
            return;
        }
        mFeatureEnabled = enabled;
        if (mFeatureEnabled) {
            if (mState == STATE_NOT_SUPPORTED) {
                Log.e(TAG, "Can't enabled Spatial Audio, unsupported");
                return;
            }
            if (mState == STATE_UNINITIALIZED) {
                init(true, null /* settings */);
            }
            setSpatializerEnabledInt(true);
        } else {
            setSpatializerEnabledInt(false);
        }
    }

    synchronized void setSpatializerEnabledInt(boolean enabled) {
        switch (mState) {
            case STATE_UNINITIALIZED:
                if (enabled) {
                    throw (new IllegalStateException("Can't enable when uninitialized"));
                }
                break;
            case STATE_NOT_SUPPORTED:
                if (enabled) {
                    Log.e(TAG, "Can't enable when unsupported");
                }
                break;
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
                if (enabled) {
                    createSpat();
                    onRoutingUpdated();
                    // onRoutingUpdated() can update the "enabled" state based on context
                    // and will call setDispatchFeatureEnabledState().
                } // else { nothing to do as already disabled }
                break;
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (!enabled) {
                    releaseSpat();
                    setDispatchFeatureEnabledState(false, "setSpatializerEnabledInt");
                } // else { nothing to do as already enabled }
                break;
        }
    }

    synchronized int getCapableImmersiveAudioLevel() {
        return mCapableSpatLevel;
    }

    final RemoteCallbackList<ISpatializerCallback> mStateCallbacks =
            new RemoteCallbackList<ISpatializerCallback>();

    synchronized void registerStateCallback(
            @NonNull ISpatializerCallback callback) {
        mStateCallbacks.register(callback);
    }

    synchronized void unregisterStateCallback(
            @NonNull ISpatializerCallback callback) {
        mStateCallbacks.unregister(callback);
    }

    /**
     * Update the feature state, no-op if no change
     * @param featureEnabled
     */
    private synchronized void setDispatchFeatureEnabledState(boolean featureEnabled, String source)
    {
        if (featureEnabled) {
            switch (mState) {
                case STATE_DISABLED_UNAVAILABLE:
                    mState = STATE_ENABLED_UNAVAILABLE;
                    break;
                case STATE_DISABLED_AVAILABLE:
                    mState = STATE_ENABLED_AVAILABLE;
                    break;
                case STATE_ENABLED_AVAILABLE:
                case STATE_ENABLED_UNAVAILABLE:
                    // already enabled: no-op
                    loglogi("setDispatchFeatureEnabledState(" + featureEnabled
                            + ") no dispatch: mState:"
                            + spatStateString(mState) + " src:" + source);
                    return;
                default:
                    throw (new IllegalStateException("Invalid mState:" + mState
                            + " for enabled true"));
            }
        } else {
            switch (mState) {
                case STATE_ENABLED_UNAVAILABLE:
                    mState = STATE_DISABLED_UNAVAILABLE;
                    break;
                case STATE_ENABLED_AVAILABLE:
                    mState = STATE_DISABLED_AVAILABLE;
                    break;
                case STATE_DISABLED_AVAILABLE:
                case STATE_DISABLED_UNAVAILABLE:
                    // already disabled: no-op
                    loglogi("setDispatchFeatureEnabledState(" + featureEnabled
                            + ") no dispatch: mState:" + spatStateString(mState)
                            + " src:" + source);
                    return;
                default:
                    throw (new IllegalStateException("Invalid mState:" + mState
                            + " for enabled false"));
            }
        }
        loglogi("setDispatchFeatureEnabledState(" + featureEnabled
                + ") mState:" + spatStateString(mState));
        final int nbCallbacks = mStateCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mStateCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerEnabledChanged(featureEnabled);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerEnabledChanged", e);
            }
        }
        mStateCallbacks.finishBroadcast();
    }

    private synchronized void setDispatchAvailableState(boolean available) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                throw (new IllegalStateException(
                        "Should not update available state in state:" + mState));
            case STATE_DISABLED_UNAVAILABLE:
                if (available) {
                    mState = STATE_DISABLED_AVAILABLE;
                    break;
                } else {
                    // already in unavailable state
                    loglogi("setDispatchAvailableState(" + available
                            + ") no dispatch: mState:" + spatStateString(mState));
                    return;
                }
            case STATE_ENABLED_UNAVAILABLE:
                if (available) {
                    mState = STATE_ENABLED_AVAILABLE;
                    break;
                } else {
                    // already in unavailable state
                    loglogi("setDispatchAvailableState(" + available
                            + ") no dispatch: mState:" + spatStateString(mState));
                    return;
                }
            case STATE_DISABLED_AVAILABLE:
                if (available) {
                    // already in available state
                    loglogi("setDispatchAvailableState(" + available
                            + ") no dispatch: mState:" + spatStateString(mState));
                    return;
                } else {
                    mState = STATE_DISABLED_UNAVAILABLE;
                    break;
                }
            case STATE_ENABLED_AVAILABLE:
                if (available) {
                    // already in available state
                    loglogi("setDispatchAvailableState(" + available
                            + ") no dispatch: mState:" + spatStateString(mState));
                    return;
                } else {
                    mState = STATE_ENABLED_UNAVAILABLE;
                    break;
                }
        }
        loglogi("setDispatchAvailableState(" + available + ") mState:" + spatStateString(mState));
        final int nbCallbacks = mStateCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mStateCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerAvailableChanged(available);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerEnabledChanged", e);
            }
        }
        mStateCallbacks.finishBroadcast();
    }

    //------------------------------------------------------
    // native Spatializer management

    /**
     * precondition: mState == STATE_DISABLED_*
     */
    private void createSpat() {
        if (mSpat == null) {
            mSpatCallback = new SpatializerCallback();
            mSpat = AudioSystem.getSpatializer(mSpatCallback);
            try {
                //TODO: register heatracking callback only when sensors are registered
                if (mIsHeadTrackingSupported) {
                    mActualHeadTrackingMode =
                            headTrackingModeTypeToSpatializerInt(mSpat.getActualHeadTrackingMode());
                    mSpat.registerHeadTrackingCallback(mSpatHeadTrackingCallback);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Can't configure head tracking", e);
                mState = STATE_NOT_SUPPORTED;
                mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
                mActualHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
            }
        }
    }

    /**
     * precondition: mState == STATE_ENABLED_*
     */
    private void releaseSpat() {
        if (mSpat != null) {
            mSpatCallback = null;
            try {
                if (mIsHeadTrackingSupported) {
                    mSpat.registerHeadTrackingCallback(null);
                }
                mHeadTrackerAvailable = false;
                mSpat.release();
            } catch (RemoteException e) {
                Log.e(TAG, "Can't set release spatializer cleanly", e);
            }
            mSpat = null;
        }
    }

    //------------------------------------------------------
    // virtualization capabilities
    synchronized boolean canBeSpatialized(
            @NonNull AudioAttributes attributes, @NonNull AudioFormat format) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
                logd("canBeSpatialized false due to state:" + mState);
                return false;
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                break;
        }

        // filter on AudioAttributes usage
        switch (attributes.getUsage()) {
            case AudioAttributes.USAGE_MEDIA:
            case AudioAttributes.USAGE_GAME:
                break;
            default:
                logd("canBeSpatialized false due to usage:" + attributes.getUsage());
                return false;
        }

        // going through adapter to take advantage of routing cache
        final ArrayList<AudioDeviceAttributes> devices = getRoutingDevices(attributes);
        if (devices.isEmpty()) {
            logloge("canBeSpatialized got no device for " + attributes);
            return false;
        }
        final boolean able = canBeSpatializedOnDevice(attributes, format, devices);
        logd("canBeSpatialized usage:" + attributes.getUsage()
                + " format:" + format.toLogFriendlyString() + " returning " + able);
        return able;
    }

    //------------------------------------------------------
    // head tracking
    final RemoteCallbackList<ISpatializerHeadTrackingModeCallback> mHeadTrackingModeCallbacks =
            new RemoteCallbackList<ISpatializerHeadTrackingModeCallback>();

    synchronized void registerHeadTrackingModeCallback(
            @NonNull ISpatializerHeadTrackingModeCallback callback) {
        mHeadTrackingModeCallbacks.register(callback);
    }

    synchronized void unregisterHeadTrackingModeCallback(
            @NonNull ISpatializerHeadTrackingModeCallback callback) {
        mHeadTrackingModeCallbacks.unregister(callback);
    }

    final RemoteCallbackList<ISpatializerHeadTrackerAvailableCallback> mHeadTrackerCallbacks =
            new RemoteCallbackList<>();

    synchronized void registerHeadTrackerAvailableCallback(
            @NonNull ISpatializerHeadTrackerAvailableCallback cb, boolean register) {
        if (register) {
            mHeadTrackerCallbacks.register(cb);
        } else {
            mHeadTrackerCallbacks.unregister(cb);
        }
    }

    synchronized int[] getSupportedHeadTrackingModes() {
        return mSupportedHeadTrackingModes;
    }

    synchronized int getActualHeadTrackingMode() {
        return mActualHeadTrackingMode;
    }

    synchronized int getDesiredHeadTrackingMode() {
        return mDesiredHeadTrackingMode;
    }

    synchronized void setGlobalTransform(@NonNull float[] transform) {
        if (transform.length != 6) {
            throw new IllegalArgumentException("invalid array size" + transform.length);
        }
        if (!checkSpatializerForHeadTracking("setGlobalTransform")) {
            return;
        }
        try {
            mSpat.setGlobalTransform(transform);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setGlobalTransform", e);
        }
    }

    synchronized void recenterHeadTracker() {
        if (!checkSpatializerForHeadTracking("recenterHeadTracker")) {
            return;
        }
        try {
            mSpat.recenterHeadTracker();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling recenterHeadTracker", e);
        }
    }

    synchronized void setDisplayOrientation(float displayOrientation) {
        if (!checkSpatializer("setDisplayOrientation")) {
            return;
        }
        try {
            mSpat.setDisplayOrientation(displayOrientation);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setDisplayOrientation", e);
        }
    }

    synchronized void setFoldState(boolean folded) {
        if (!checkSpatializer("setFoldState")) {
            return;
        }
        try {
            mSpat.setFoldState(folded);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setFoldState", e);
        }
    }

    synchronized void setDesiredHeadTrackingMode(@Spatializer.HeadTrackingModeSet int mode) {
        if (!checkSpatializerForHeadTracking("setDesiredHeadTrackingMode")) {
            return;
        }
        if (mode != Spatializer.HEAD_TRACKING_MODE_DISABLED) {
            mDesiredHeadTrackingModeWhenEnabled = mode;
        }
        try {
            if (mDesiredHeadTrackingMode != mode) {
                mDesiredHeadTrackingMode = mode;
                dispatchDesiredHeadTrackingMode(mode);
            }
            Log.i(TAG, "setDesiredHeadTrackingMode("
                    + Spatializer.headtrackingModeToString(mode) + ")");
            mSpat.setDesiredHeadTrackingMode(spatializerIntToHeadTrackingModeType(mode));
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setDesiredHeadTrackingMode", e);
        }
    }

    synchronized void setHeadTrackerEnabled(boolean enabled, @NonNull AudioDeviceAttributes ada) {
        if (!mIsHeadTrackingSupported) {
            Log.v(TAG, "no headtracking support, ignoring setHeadTrackerEnabled to " + enabled
                    + " for " + ada);
        }
        final SADeviceState deviceState = findDeviceStateForAudioDeviceAttributes(ada);
        if (deviceState == null) return;
        if (!deviceState.mHasHeadTracker) {
            Log.e(TAG, "Called setHeadTrackerEnabled enabled:" + enabled
                    + " device:" + ada + " on a device without headtracker");
            return;
        }
        Log.i(TAG, "setHeadTrackerEnabled enabled:" + enabled + " device:" + ada);
        deviceState.mHeadTrackerEnabled = enabled;
        mAudioService.persistSpatialAudioDeviceSettings();
        logDeviceState(deviceState, "setHeadTrackerEnabled");

        // check current routing to see if it affects the headtracking mode
        if (sRoutingDevices.isEmpty()) {
            logloge("setHeadTrackerEnabled: no device, bailing");
            return;
        }
        final AudioDeviceAttributes currentDevice = sRoutingDevices.get(0);
        if (currentDevice.getType() == ada.getType()
                && currentDevice.getAddress().equals(ada.getAddress())) {
            setDesiredHeadTrackingMode(enabled ? mDesiredHeadTrackingModeWhenEnabled
                    : Spatializer.HEAD_TRACKING_MODE_DISABLED);
            if (enabled && !mHeadTrackerAvailable) {
                postInitSensors();
            }
        }
    }

    synchronized boolean hasHeadTracker(@NonNull AudioDeviceAttributes ada) {
        if (!mIsHeadTrackingSupported) {
            Log.v(TAG, "no headtracking support, hasHeadTracker always false for " + ada);
            return false;
        }
        final SADeviceState deviceState = findDeviceStateForAudioDeviceAttributes(ada);
        return deviceState != null && deviceState.mHasHeadTracker;
    }

    /**
     * Configures device in list as having a head tracker
     * @param ada
     * @return true if the head tracker is enabled, false otherwise or if device not found
     */
    synchronized boolean setHasHeadTracker(@NonNull AudioDeviceAttributes ada) {
        if (!mIsHeadTrackingSupported) {
            Log.v(TAG, "no headtracking support, setHasHeadTracker always false for " + ada);
            return false;
        }
        final SADeviceState deviceState = findDeviceStateForAudioDeviceAttributes(ada);
        if (deviceState != null) {
            if (!deviceState.mHasHeadTracker) {
                deviceState.mHasHeadTracker = true;
                mAudioService.persistSpatialAudioDeviceSettings();
                logDeviceState(deviceState, "setHasHeadTracker");
            }
            return deviceState.mHeadTrackerEnabled;
        }
        Log.e(TAG, "setHasHeadTracker: device not found for:" + ada);
        return false;
    }

    synchronized boolean isHeadTrackerEnabled(@NonNull AudioDeviceAttributes ada) {
        if (!mIsHeadTrackingSupported) {
            Log.v(TAG, "no headtracking support, isHeadTrackerEnabled always false for " + ada);
            return false;
        }
        final SADeviceState deviceState = findDeviceStateForAudioDeviceAttributes(ada);
        return deviceState != null
                && deviceState.mHasHeadTracker && deviceState.mHeadTrackerEnabled;
    }

    synchronized boolean isHeadTrackerAvailable() {
        return mHeadTrackerAvailable;
    }

    private boolean checkSpatializer(String funcName) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                return false;
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    // try to recover by resetting the native spatializer state
                    Log.e(TAG, "checkSpatializer(): called from " + funcName
                            + "(), native spatializer should not be null in state: " + mState);
                    postReset();
                    return false;
                }
                break;
        }
        return true;
    }

    private boolean checkSpatializerForHeadTracking(String funcName) {
        return checkSpatializer(funcName) && mIsHeadTrackingSupported;
    }

    private void dispatchActualHeadTrackingMode(int newMode) {
        final int nbCallbacks = mHeadTrackingModeCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadTrackingModeCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerActualHeadTrackingModeChanged(newMode);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerActualHeadTrackingModeChanged("
                        + newMode + ")", e);
            }
        }
        mHeadTrackingModeCallbacks.finishBroadcast();
    }

    private void dispatchDesiredHeadTrackingMode(int newMode) {
        final int nbCallbacks = mHeadTrackingModeCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadTrackingModeCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerDesiredHeadTrackingModeChanged(newMode);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerDesiredHeadTrackingModeChanged("
                        + newMode + ")", e);
            }
        }
        mHeadTrackingModeCallbacks.finishBroadcast();
    }

    private void dispatchHeadTrackerAvailable(boolean available) {
        final int nbCallbacks = mHeadTrackerCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadTrackerCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerHeadTrackerAvailable(available);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerHeadTrackerAvailable("
                        + available + ")", e);
            }
        }
        mHeadTrackerCallbacks.finishBroadcast();
    }

    //------------------------------------------------------
    // head pose
    final RemoteCallbackList<ISpatializerHeadToSoundStagePoseCallback> mHeadPoseCallbacks =
            new RemoteCallbackList<ISpatializerHeadToSoundStagePoseCallback>();

    synchronized void registerHeadToSoundstagePoseCallback(
            @NonNull ISpatializerHeadToSoundStagePoseCallback callback) {
        mHeadPoseCallbacks.register(callback);
    }

    synchronized void unregisterHeadToSoundstagePoseCallback(
            @NonNull ISpatializerHeadToSoundStagePoseCallback callback) {
        mHeadPoseCallbacks.unregister(callback);
    }

    private void dispatchPoseUpdate(float[] pose) {
        final int nbCallbacks = mHeadPoseCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadPoseCallbacks.getBroadcastItem(i)
                        .dispatchPoseChanged(pose);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchPoseChanged", e);
            }
        }
        mHeadPoseCallbacks.finishBroadcast();
    }

    //------------------------------------------------------
    // vendor parameters
    synchronized void setEffectParameter(int key, @NonNull byte[] value) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                throw (new IllegalStateException(
                        "Can't set parameter key:" + key + " without a spatializer"));
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    Log.e(TAG, "setParameter(" + key + "): null spatializer in state: " + mState);
                    return;
                }
                break;
        }
        // mSpat != null
        try {
            mSpat.setParameter(key, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in setParameter for key:" + key, e);
        }
    }

    synchronized void getEffectParameter(int key, @NonNull byte[] value) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                throw (new IllegalStateException(
                        "Can't get parameter key:" + key + " without a spatializer"));
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    Log.e(TAG, "getParameter(" + key + "): null spatializer in state: " + mState);
                    return;
                }
                break;
        }
        // mSpat != null
        try {
            mSpat.getParameter(key, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in getParameter for key:" + key, e);
        }
    }

    //------------------------------------------------------
    // output

    /** @see Spatializer#getOutput */
    synchronized int getOutput() {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                throw (new IllegalStateException(
                        "Can't get output without a spatializer"));
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    throw (new IllegalStateException(
                            "null Spatializer for getOutput"));
                }
                break;
        }
        // mSpat != null
        try {
            return mSpat.getOutput();
        } catch (RemoteException e) {
            Log.e(TAG, "Error in getOutput", e);
            return 0;
        }
    }

    final RemoteCallbackList<ISpatializerOutputCallback> mOutputCallbacks =
            new RemoteCallbackList<ISpatializerOutputCallback>();

    synchronized void registerSpatializerOutputCallback(
            @NonNull ISpatializerOutputCallback callback) {
        mOutputCallbacks.register(callback);
    }

    synchronized void unregisterSpatializerOutputCallback(
            @NonNull ISpatializerOutputCallback callback) {
        mOutputCallbacks.unregister(callback);
    }

    private void dispatchOutputUpdate(int output) {
        final int nbCallbacks = mOutputCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mOutputCallbacks.getBroadcastItem(i).dispatchSpatializerOutputChanged(output);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchOutputUpdate", e);
            }
        }
        mOutputCallbacks.finishBroadcast();
    }

    //------------------------------------------------------
    // sensors
    private void postInitSensors() {
        mAudioService.postInitSpatializerHeadTrackingSensors();
    }

    synchronized void onInitSensors() {
        final boolean init = mFeatureEnabled && (mSpatLevel != SpatializationLevel.NONE);
        final String action = init ? "initializing" : "releasing";
        if (mSpat == null) {
            logloge("not " + action + " sensors, null spatializer");
            return;
        }
        if (!mIsHeadTrackingSupported) {
            logloge("not " + action + " sensors, spatializer doesn't support headtracking");
            return;
        }
        int headHandle = -1;
        int screenHandle = -1;
        if (init) {
            if (mSensorManager == null) {
                try {
                    mSensorManager = (SensorManager)
                            mAudioService.mContext.getSystemService(Context.SENSOR_SERVICE);
                    mDynSensorCallback = new HelperDynamicSensorCallback();
                    mSensorManager.registerDynamicSensorCallback(mDynSensorCallback);
                } catch (Exception e) {
                    Log.e(TAG, "Error with SensorManager, can't initialize sensors", e);
                    mSensorManager = null;
                    mDynSensorCallback = null;
                    return;
                }
            }
            // initialize sensor handles
            // TODO check risk of race condition for updating the association of a head tracker
            //  and an audio device:
            //     does this happen before routing is updated?
            //     avoid by supporting adding device here AND in onRoutingUpdated()
            headHandle = getHeadSensorHandleUpdateTracker();
            loglogi("head tracker sensor handle initialized to " + headHandle);
            screenHandle = getScreenSensorHandle();
            Log.i(TAG, "found screen sensor handle initialized to " + screenHandle);
        } else {
            if (mSensorManager != null && mDynSensorCallback != null) {
                mSensorManager.unregisterDynamicSensorCallback(mDynSensorCallback);
                mSensorManager = null;
                mDynSensorCallback = null;
            }
            // -1 is disable value for both screen and head tracker handles
        }
        try {
            Log.i(TAG, "setScreenSensor:" + screenHandle);
            mSpat.setScreenSensor(screenHandle);
        } catch (Exception e) {
            Log.e(TAG, "Error calling setScreenSensor:" + screenHandle, e);
        }
        try {
            Log.i(TAG, "setHeadSensor:" + headHandle);
            mSpat.setHeadSensor(headHandle);
            if (mHeadTrackerAvailable != (headHandle != -1)) {
                mHeadTrackerAvailable = (headHandle != -1);
                dispatchHeadTrackerAvailable(mHeadTrackerAvailable);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calling setHeadSensor:" + headHandle, e);
        }
        setDesiredHeadTrackingMode(mDesiredHeadTrackingMode);
    }

    //------------------------------------------------------
    // SDK <-> AIDL converters
    private static int headTrackingModeTypeToSpatializerInt(byte mode) {
        switch (mode) {
            case SpatializerHeadTrackingMode.OTHER:
                return Spatializer.HEAD_TRACKING_MODE_OTHER;
            case SpatializerHeadTrackingMode.DISABLED:
                return Spatializer.HEAD_TRACKING_MODE_DISABLED;
            case SpatializerHeadTrackingMode.RELATIVE_WORLD:
                return Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD;
            case SpatializerHeadTrackingMode.RELATIVE_SCREEN:
                return Spatializer.HEAD_TRACKING_MODE_RELATIVE_DEVICE;
            default:
                throw (new IllegalArgumentException("Unexpected head tracking mode:" + mode));
        }
    }

    private static byte spatializerIntToHeadTrackingModeType(int sdkMode) {
        switch (sdkMode) {
            case Spatializer.HEAD_TRACKING_MODE_OTHER:
                return SpatializerHeadTrackingMode.OTHER;
            case Spatializer.HEAD_TRACKING_MODE_DISABLED:
                return SpatializerHeadTrackingMode.DISABLED;
            case Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD:
                return SpatializerHeadTrackingMode.RELATIVE_WORLD;
            case Spatializer.HEAD_TRACKING_MODE_RELATIVE_DEVICE:
                return SpatializerHeadTrackingMode.RELATIVE_SCREEN;
            default:
                throw (new IllegalArgumentException("Unexpected head tracking mode:" + sdkMode));
        }
    }

    private static int spatializationLevelToSpatializerInt(byte level) {
        switch (level) {
            case SpatializationLevel.NONE:
                return Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
            case SpatializationLevel.SPATIALIZER_MULTICHANNEL:
                return Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL;
            case SpatializationLevel.SPATIALIZER_MCHAN_BED_PLUS_OBJECTS:
                return Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MCHAN_BED_PLUS_OBJECTS;
            default:
                throw (new IllegalArgumentException("Unexpected spatializer level:" + level));
        }
    }

    void dump(PrintWriter pw) {
        pw.println("SpatializerHelper:");
        pw.println("\tmState:" + mState);
        pw.println("\tmSpatLevel:" + mSpatLevel);
        pw.println("\tmCapableSpatLevel:" + mCapableSpatLevel);
        pw.println("\tmIsHeadTrackingSupported:" + mIsHeadTrackingSupported);
        StringBuilder modesString = new StringBuilder();
        for (int mode : mSupportedHeadTrackingModes) {
            modesString.append(Spatializer.headtrackingModeToString(mode)).append(" ");
        }
        pw.println("\tsupported head tracking modes:" + modesString);
        pw.println("\tmDesiredHeadTrackingMode:"
                + Spatializer.headtrackingModeToString(mDesiredHeadTrackingMode));
        pw.println("\tmActualHeadTrackingMode:"
                + Spatializer.headtrackingModeToString(mActualHeadTrackingMode));
        pw.println("\theadtracker available:" + mHeadTrackerAvailable);
        pw.println("\tsupports binaural:" + mBinauralSupported + " / transaural:"
                + mTransauralSupported);
        pw.println("\tmSpatOutput:" + mSpatOutput);
        pw.println("\tdevices:");
        for (SADeviceState device : mSADevices) {
            pw.println("\t\t" + device);
        }
    }

    /*package*/ static final class SADeviceState {
        private static boolean sBinauralEnabledDefault = true;
        private static boolean sTransauralEnabledDefault = true;
        private static boolean sHeadTrackingEnabledDefault = false;
        final @AudioDeviceInfo.AudioDeviceType int mDeviceType;
        final @NonNull String mDeviceAddress;
        boolean mEnabled;
        boolean mHasHeadTracker = false;
        boolean mHeadTrackerEnabled;
        static final String SETTING_FIELD_SEPARATOR = ",";
        static final String SETTING_DEVICE_SEPARATOR_CHAR = "|";
        static final String SETTING_DEVICE_SEPARATOR = "\\|";

        /**
         * Constructor
         * @param deviceType
         * @param address must be non-null for wireless devices
         * @throws NullPointerException if a null address is passed for a wireless device
         */
        SADeviceState(@AudioDeviceInfo.AudioDeviceType int deviceType, @Nullable String address) {
            mDeviceType = deviceType;
            mDeviceAddress = isWireless(deviceType) ? Objects.requireNonNull(address) : "";
            final int spatMode = SPAT_MODE_FOR_DEVICE_TYPE.get(deviceType, Integer.MIN_VALUE);
            mEnabled = spatMode == SpatializationMode.SPATIALIZER_BINAURAL
                    ? sBinauralEnabledDefault
                    : spatMode == SpatializationMode.SPATIALIZER_TRANSAURAL
                            ? sTransauralEnabledDefault
                            : false;
            mHeadTrackerEnabled = sHeadTrackingEnabledDefault;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            // type check and cast
            if (getClass() != obj.getClass()) {
                return false;
            }
            final SADeviceState sads = (SADeviceState) obj;
            return mDeviceType == sads.mDeviceType
                    && mDeviceAddress.equals(sads.mDeviceAddress)
                    && mEnabled == sads.mEnabled
                    && mHasHeadTracker == sads.mHasHeadTracker
                    && mHeadTrackerEnabled == sads.mHeadTrackerEnabled;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDeviceType, mDeviceAddress, mEnabled, mHasHeadTracker,
                    mHeadTrackerEnabled);
        }

        @Override
        public String toString() {
            return "type: " + mDeviceType + " addr: " + mDeviceAddress + " enabled: " + mEnabled
                    + " HT: " + mHasHeadTracker + " HTenabled: " + mHeadTrackerEnabled;
        }

        String toPersistableString() {
            return (new StringBuilder().append(mDeviceType)
                    .append(SETTING_FIELD_SEPARATOR).append(mDeviceAddress)
                    .append(SETTING_FIELD_SEPARATOR).append(mEnabled ? "1" : "0")
                    .append(SETTING_FIELD_SEPARATOR).append(mHasHeadTracker ? "1" : "0")
                    .append(SETTING_FIELD_SEPARATOR).append(mHeadTrackerEnabled ? "1" : "0")
                    .toString());
        }

        static @Nullable SADeviceState fromPersistedString(@Nullable String persistedString) {
            if (persistedString == null) {
                return null;
            }
            if (persistedString.isEmpty()) {
                return null;
            }
            String[] fields = TextUtils.split(persistedString, SETTING_FIELD_SEPARATOR);
            if (fields.length != 5) {
                // expecting all fields, fewer may mean corruption, ignore those settings
                return null;
            }
            try {
                final int deviceType = Integer.parseInt(fields[0]);
                final SADeviceState deviceState = new SADeviceState(deviceType, fields[1]);
                deviceState.mEnabled = Integer.parseInt(fields[2]) == 1;
                deviceState.mHasHeadTracker = Integer.parseInt(fields[3]) == 1;
                deviceState.mHeadTrackerEnabled = Integer.parseInt(fields[4]) == 1;
                return deviceState;
            } catch (NumberFormatException e) {
                Log.e(TAG, "unable to parse setting for SADeviceState: " + persistedString, e);
                return null;
            }
        }

        public AudioDeviceAttributes getAudioDeviceAttributes() {
            return new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_OUTPUT,
                    mDeviceType, mDeviceAddress == null ? "" : mDeviceAddress);
        }

    }

    /*package*/ synchronized String getSADeviceSettings() {
        // expected max size of each String for each SADeviceState is 25 (accounting for separator)
        final StringBuilder settingsBuilder = new StringBuilder(mSADevices.size() * 25);
        for (int i = 0; i < mSADevices.size(); i++) {
            settingsBuilder.append(mSADevices.get(i).toPersistableString());
            if (i != mSADevices.size() - 1) {
                settingsBuilder.append(SADeviceState.SETTING_DEVICE_SEPARATOR_CHAR);
            }
        }
        return settingsBuilder.toString();
    }

    /*package*/ synchronized void setSADeviceSettings(@NonNull String persistedSettings) {
        String[] devSettings = TextUtils.split(Objects.requireNonNull(persistedSettings),
                SADeviceState.SETTING_DEVICE_SEPARATOR);
        // small list, not worth overhead of Arrays.stream(devSettings)
        for (String setting : devSettings) {
            SADeviceState devState = SADeviceState.fromPersistedString(setting);
            // Note if the device is not compatible with spatialization mode
            // or the device type is not canonical, it is ignored.
            if (devState != null
                    && devState.mDeviceType == getCanonicalDeviceType(devState.mDeviceType)
                    && isDeviceCompatibleWithSpatializationModes(
                            devState.getAudioDeviceAttributes())) {
                mSADevices.add(devState);
                logDeviceState(devState, "setSADeviceSettings");
            }
        }
    }

    private static String spatStateString(int state) {
        switch (state) {
            case STATE_UNINITIALIZED:
                return "STATE_UNINITIALIZED";
            case STATE_NOT_SUPPORTED:
                return "STATE_NOT_SUPPORTED";
            case STATE_DISABLED_UNAVAILABLE:
                return "STATE_DISABLED_UNAVAILABLE";
            case STATE_ENABLED_UNAVAILABLE:
                return "STATE_ENABLED_UNAVAILABLE";
            case STATE_ENABLED_AVAILABLE:
                return "STATE_ENABLED_AVAILABLE";
            case STATE_DISABLED_AVAILABLE:
                return "STATE_DISABLED_AVAILABLE";
            default:
                return "invalid state";
        }
    }

    private static boolean isWireless(@AudioDeviceInfo.AudioDeviceType int deviceType) {
        for (int type : WIRELESS_TYPES) {
            if (type == deviceType) {
                return true;
            }
        }
        return false;
    }

    private int getHeadSensorHandleUpdateTracker() {
        int headHandle = -1;
        if (sRoutingDevices.isEmpty()) {
            logloge("getHeadSensorHandleUpdateTracker: no device, no head tracker");
            return headHandle;
        }
        final AudioDeviceAttributes currentDevice = sRoutingDevices.get(0);
        UUID routingDeviceUuid = mAudioService.getDeviceSensorUuid(currentDevice);
        // We limit only to Sensor.TYPE_HEAD_TRACKER here to avoid confusion
        // with gaming sensors. (Note that Sensor.TYPE_ROTATION_VECTOR
        // and Sensor.TYPE_GAME_ROTATION_VECTOR are supported internally by
        // SensorPoseProvider).
        // Note: this is a dynamic sensor list right now.
        List<Sensor> sensors = mSensorManager.getDynamicSensorList(Sensor.TYPE_HEAD_TRACKER);
        for (Sensor sensor : sensors) {
            final UUID uuid = sensor.getUuid();
            if (uuid.equals(routingDeviceUuid)) {
                headHandle = sensor.getHandle();
                if (!setHasHeadTracker(currentDevice)) {
                    headHandle = -1;
                }
                break;
            }
            if (uuid.equals(UuidUtils.STANDALONE_UUID)) {
                headHandle = sensor.getHandle();
                // we do not break, perhaps we find a head tracker on device.
            }
        }
        return headHandle;
    }

    private int getScreenSensorHandle() {
        int screenHandle = -1;
        Sensor screenSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (screenSensor != null) {
            screenHandle = screenSensor.getHandle();
        }
        return screenHandle;
    }

    /**
     * Returns routing for the given attributes
     * @param aa AudioAttributes whose routing is being queried
     * @return a non-null never-empty list of devices. If the routing query failed, the list
     *     will contain null.
     */
    private @NonNull ArrayList<AudioDeviceAttributes> getRoutingDevices(AudioAttributes aa) {
        final ArrayList<AudioDeviceAttributes> devices = mASA.getDevicesForAttributes(
                aa, false /* forVolume */);
        for (AudioDeviceAttributes ada : devices) {
            if (ada == null) {
                // invalid entry, reject this routing query by returning an empty list
                return new ArrayList<>(0);
            }
        }
        return devices;
    }

    private static void loglogi(String msg) {
        AudioService.sSpatialLogger.enqueueAndLog(msg, EventLogger.Event.ALOGI, TAG);
    }

    private static String logloge(String msg) {
        AudioService.sSpatialLogger.enqueueAndLog(msg, EventLogger.Event.ALOGE, TAG);
        return msg;
    }

    //------------------------------------------------
    // for testing purposes only

    /*package*/ void clearSADevices() {
        mSADevices.clear();
    }

    /*package*/ synchronized void forceStateForTest(int state) {
        mState = state;
    }

    /*package*/ synchronized void initForTest(boolean hasBinaural, boolean hasTransaural) {
        mBinauralSupported = hasBinaural;
        mTransauralSupported = hasTransaural;
    }
}

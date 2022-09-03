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
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.media.INativeSpatializerCallback;
import android.media.ISpatializer;
import android.media.ISpatializerCallback;
import android.media.ISpatializerHeadToSoundStagePoseCallback;
import android.media.ISpatializerHeadTrackingCallback;
import android.media.ISpatializerHeadTrackingModeCallback;
import android.media.ISpatializerOutputCallback;
import android.media.SpatializationLevel;
import android.media.Spatializer;
import android.media.SpatializerHeadTrackingMode;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    /** head tracker sensor name */
    // TODO: replace with generic head tracker sensor name.
    //       the current implementation refers to the "google" namespace but will be replaced
    //       by an android name at the next API level revision, it is not Google-specific.
    //       Also see "TODO-HT" in onInitSensors() method
    private static final String HEADTRACKER_SENSOR =
            "com.google.hardware.sensor.hid_dynamic.headtracker";

    // Spatializer state machine
    private static final int STATE_UNINITIALIZED = 0;
    private static final int STATE_NOT_SUPPORTED = 1;
    private static final int STATE_DISABLED_UNAVAILABLE = 3;
    private static final int STATE_ENABLED_UNAVAILABLE = 4;
    private static final int STATE_ENABLED_AVAILABLE = 5;
    private static final int STATE_DISABLED_AVAILABLE = 6;
    private int mState = STATE_UNINITIALIZED;

    /** current level as reported by native Spatializer in callback */
    private int mSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    private int mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    private int mActualHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
    private int mDesiredHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
    private int mSpatOutput = 0;
    private @Nullable ISpatializer mSpat;
    private @Nullable SpatializerCallback mSpatCallback;
    private @Nullable SpatializerHeadTrackingCallback mSpatHeadTrackingCallback;
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
    // device array to store the routing for the default attributes and format, size 1 because
    // media is never expected to be duplicated
    private static final AudioDeviceAttributes[] ROUTING_DEVICES = new AudioDeviceAttributes[1];

    //---------------------------------------------------------------
    // audio device compatibility / enabled

    private final ArrayList<AudioDeviceAttributes> mCompatibleAudioDevices = new ArrayList<>(0);

    //------------------------------------------------------
    // initialization
    SpatializerHelper(@NonNull AudioService mother, @NonNull AudioSystemAdapter asa) {
        mAudioService = mother;
        mASA = asa;
    }

    synchronized void init(boolean effectExpected) {
        Log.i(TAG, "Initializing");
        if (!effectExpected) {
            Log.i(TAG, "Setting state to STATE_NOT_SUPPORTED due to effect not expected");
            mState = STATE_NOT_SUPPORTED;
            return;
        }
        if (mState != STATE_UNINITIALIZED) {
            throw new IllegalStateException(("init() called in state:" + mState));
        }
        // is there a spatializer?
        mSpatCallback = new SpatializerCallback();
        final ISpatializer spat = AudioSystem.getSpatializer(mSpatCallback);
        if (spat == null) {
            Log.i(TAG, "init(): No Spatializer found");
            mState = STATE_NOT_SUPPORTED;
            return;
        }
        // capabilities of spatializer?
        try {
            byte[] levels = spat.getSupportedLevels();
            if (levels == null
                    || levels.length == 0
                    || (levels.length == 1
                    && levels[0] == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE)) {
                Log.e(TAG, "Spatializer is useless");
                mState = STATE_NOT_SUPPORTED;
                return;
            }
            for (byte level : levels) {
                logd("found support for level: " + level);
                if (level == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL) {
                    logd("Setting capable level to LEVEL_MULTICHANNEL");
                    mCapableSpatLevel = level;
                    break;
                }
            }
        } catch (RemoteException e) {
            /* capable level remains at NONE*/
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
        // note at this point mSpat is still not instantiated
    }

    /**
     * Like init() but resets the state and spatializer levels
     * @param featureEnabled
     */
    synchronized void reset(boolean featureEnabled) {
        Log.i(TAG, "Resetting");
        mState = STATE_UNINITIALIZED;
        mSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        mActualHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
        init(true);
        setFeatureEnabled(featureEnabled);
    }

    //------------------------------------------------------
    // routing monitoring
    void onRoutingUpdated() {
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
        mASA.getDevicesForAttributes(DEFAULT_ATTRIBUTES).toArray(ROUTING_DEVICES);
        final boolean able =
                AudioSystem.canBeSpatialized(DEFAULT_ATTRIBUTES, DEFAULT_FORMAT, ROUTING_DEVICES);
        logd("onRoutingUpdated: can spatialize media 5.1:" + able
                + " on device:" + ROUTING_DEVICES[0]);
        setDispatchAvailableState(able);
    }

    //------------------------------------------------------
    // spatializer callback from native
    private final class SpatializerCallback extends INativeSpatializerCallback.Stub {

        public void onLevelChanged(byte level) {
            logd("SpatializerCallback.onLevelChanged level:" + level);
            synchronized (SpatializerHelper.this) {
                mSpatLevel = spatializationLevelToSpatializerInt(level);
            }
            // TODO use reported spat level to change state

            // init sensors
            postInitSensors();
        }

        public void onOutputChanged(int output) {
            logd("SpatializerCallback.onOutputChanged output:" + output);
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
        public void onHeadTrackingModeChanged(byte mode)  {
            logd("SpatializerHeadTrackingCallback.onHeadTrackingModeChanged mode:" + mode);
            int oldMode, newMode;
            synchronized (this) {
                oldMode = mActualHeadTrackingMode;
                mActualHeadTrackingMode = headTrackingModeTypeToSpatializerInt(mode);
                newMode = mActualHeadTrackingMode;
            }
            if (oldMode != newMode) {
                dispatchActualHeadTrackingMode(newMode);
            }
        }

        public void onHeadToSoundStagePoseUpdated(float[] headToStage)  {
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
                logd("SpatializerHeadTrackingCallback.onHeadToStagePoseUpdated headToStage:" + t);
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
     * @return a shallow copy of the list of compatible audio devices
     */
    synchronized @NonNull List<AudioDeviceAttributes> getCompatibleAudioDevices() {
        return (List<AudioDeviceAttributes>) mCompatibleAudioDevices.clone();
    }

    synchronized void addCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        if (!mCompatibleAudioDevices.contains(ada)) {
            mCompatibleAudioDevices.add(ada);
        }
    }

    synchronized void removeCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        mCompatibleAudioDevices.remove(ada);
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

    synchronized void setFeatureEnabled(boolean enabled) {
        switch (mState) {
            case STATE_UNINITIALIZED:
                if (enabled) {
                    throw(new IllegalStateException("Can't enable when uninitialized"));
                }
                return;
            case STATE_NOT_SUPPORTED:
                if (enabled) {
                    Log.e(TAG, "Can't enable when unsupported");
                }
                return;
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
                if (enabled) {
                    createSpat();
                    break;
                } else {
                    // already in disabled state
                    return;
                }
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (!enabled) {
                    releaseSpat();
                    break;
                } else {
                    // already in enabled state
                    return;
                }
        }
        setDispatchFeatureEnabledState(enabled);
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
     * precondition: mState = STATE_*
     *               isFeatureEnabled() != featureEnabled
     * @param featureEnabled
     */
    private synchronized void setDispatchFeatureEnabledState(boolean featureEnabled) {
        if (featureEnabled) {
            switch (mState) {
                case STATE_DISABLED_UNAVAILABLE:
                    mState = STATE_ENABLED_UNAVAILABLE;
                    break;
                case STATE_DISABLED_AVAILABLE:
                    mState = STATE_ENABLED_AVAILABLE;
                    break;
                default:
                    throw(new IllegalStateException("Invalid mState:" + mState
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
                default:
                    throw (new IllegalStateException("Invalid mState:" + mState
                            + " for enabled false"));
            }
        }
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
        mAudioService.persistSpatialAudioEnabled(featureEnabled);
    }

    private synchronized void setDispatchAvailableState(boolean available) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                throw(new IllegalStateException(
                        "Should not update available state in state:" + mState));
            case STATE_DISABLED_UNAVAILABLE:
                if (available) {
                    mState = STATE_DISABLED_AVAILABLE;
                    break;
                } else {
                    // already in unavailable state
                    return;
                }
            case STATE_ENABLED_UNAVAILABLE:
                if (available) {
                    mState = STATE_ENABLED_AVAILABLE;
                    break;
                } else {
                    // already in unavailable state
                    return;
                }
            case STATE_DISABLED_AVAILABLE:
                if (available) {
                    // already in available state
                    return;
                } else {
                    mState = STATE_DISABLED_UNAVAILABLE;
                    break;
                }
            case STATE_ENABLED_AVAILABLE:
                if (available) {
                    // already in available state
                    return;
                } else {
                    mState = STATE_ENABLED_UNAVAILABLE;
                    break;
                }
        }
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
            mSpatHeadTrackingCallback = new SpatializerHeadTrackingCallback();
            mSpat = AudioSystem.getSpatializer(mSpatCallback);
            try {
                mSpat.setLevel((byte)  Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL);
                //TODO: register heatracking callback only when sensors are registered
                if (mSpat.isHeadTrackingSupported()) {
                    mSpat.registerHeadTrackingCallback(mSpatHeadTrackingCallback);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Can't set spatializer level", e);
                mState = STATE_NOT_SUPPORTED;
                mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
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
                mSpat.registerHeadTrackingCallback(null);
                mSpat.release();
                mSpat = null;
            } catch (RemoteException e) {
                Log.e(TAG, "Can't set release spatializer cleanly", e);
            }
        }
    }

    //------------------------------------------------------
    // virtualization capabilities
    synchronized boolean canBeSpatialized(
            @NonNull AudioAttributes attributes, @NonNull AudioFormat format) {
        logd("canBeSpatialized usage:" + attributes.getUsage()
                + " format:" + format.toLogFriendlyString());
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
        AudioDeviceAttributes[] devices = new AudioDeviceAttributes[1];
        // going through adapter to take advantage of routing cache
        mASA.getDevicesForAttributes(attributes).toArray(devices);
        final boolean able = AudioSystem.canBeSpatialized(attributes, format, devices);
        logd("canBeSpatialized returning " + able);
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

    synchronized int[] getSupportedHeadTrackingModes() {
        switch (mState) {
            case STATE_UNINITIALIZED:
                return new int[0];
            case STATE_NOT_SUPPORTED:
                // return an empty list when Spatializer functionality is not supported
                // because the list of head tracking modes you can set is actually empty
                // as defined in {@link Spatializer#getSupportedHeadTrackingModes()}
                return new int[0];
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    return new int[0];
                }
                break;
        }
        // mSpat != null
        try {
            final byte[] values = mSpat.getSupportedHeadTrackingModes();
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
            int[] modes = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                modes[i] = list.get(i);
            }
            return modes;
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getSupportedHeadTrackingModes", e);
            return new int[] { Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED };
        }
    }

    synchronized int getActualHeadTrackingMode() {
        switch (mState) {
            case STATE_UNINITIALIZED:
                return Spatializer.HEAD_TRACKING_MODE_DISABLED;
            case STATE_NOT_SUPPORTED:
                return Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    return Spatializer.HEAD_TRACKING_MODE_DISABLED;
                }
                break;
        }
        // mSpat != null
        try {
            return headTrackingModeTypeToSpatializerInt(mSpat.getActualHeadTrackingMode());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getActualHeadTrackingMode", e);
            return Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
        }
    }

    synchronized int getDesiredHeadTrackingMode() {
        return mDesiredHeadTrackingMode;
    }

    synchronized void setGlobalTransform(@NonNull float[] transform) {
        if (transform.length != 6) {
            throw new IllegalArgumentException("invalid array size" + transform.length);
        }
        if (!checkSpatForHeadTracking("setGlobalTransform")) {
            return;
        }
        try {
            mSpat.setGlobalTransform(transform);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setGlobalTransform", e);
        }
    }

    synchronized void recenterHeadTracker() {
        if (!checkSpatForHeadTracking("recenterHeadTracker")) {
            return;
        }
        try {
            mSpat.recenterHeadTracker();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling recenterHeadTracker", e);
        }
    }

    synchronized void setDesiredHeadTrackingMode(@Spatializer.HeadTrackingModeSet int mode) {
        if (!checkSpatForHeadTracking("setDesiredHeadTrackingMode")) {
            return;
        }
        try {
            if (mode != mDesiredHeadTrackingMode) {
                mSpat.setDesiredHeadTrackingMode(spatializerIntToHeadTrackingModeType(mode));
                mDesiredHeadTrackingMode = mode;
                dispatchDesiredHeadTrackingMode(mode);
            }

        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setDesiredHeadTrackingMode", e);
        }
    }

    private boolean checkSpatForHeadTracking(String funcName) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                return false;
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    throw (new IllegalStateException(
                            "null Spatializer when calling " + funcName));
                }
                break;
        }
        return true;
    }

    private void dispatchActualHeadTrackingMode(int newMode) {
        final int nbCallbacks = mHeadTrackingModeCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadTrackingModeCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerActualHeadTrackingModeChanged(newMode);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerActualHeadTrackingModeChanged", e);
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
                Log.e(TAG, "Error in dispatchSpatializerDesiredHeadTrackingModeChanged", e);
            }
        }
        mHeadTrackingModeCallbacks.finishBroadcast();
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
                    throw (new IllegalStateException(
                            "null Spatializer for setParameter for key:" + key));
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
                    throw (new IllegalStateException(
                            "null Spatializer for getParameter for key:" + key));
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
        final boolean init = (mSpatLevel != SpatializationLevel.NONE);
        final String action = init ? "initializing" : "releasing";
        if (mSpat == null) {
            Log.e(TAG, "not " + action + " sensors, null spatializer");
            return;
        }
        try {
            if (!mSpat.isHeadTrackingSupported()) {
                Log.e(TAG, "not " + action + " sensors, spatializer doesn't support headtracking");
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "not " + action + " sensors, error querying headtracking", e);
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
            // TODO-HT update to non-private sensor once head tracker sensor is defined
            for (Sensor sensor : mSensorManager.getDynamicSensorList(
                    Sensor.TYPE_DEVICE_PRIVATE_BASE)) {
                if (sensor.getStringType().equals(HEADTRACKER_SENSOR)) {
                    headHandle = sensor.getHandle();
                    break;
                }
            }
            Sensor screenSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            screenHandle = screenSensor.getHandle();
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
        } catch (Exception e) {
            Log.e(TAG, "Error calling setHeadSensor:" + headHandle, e);
        }
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
                throw(new IllegalArgumentException("Unexpected head tracking mode:" + mode));
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
                throw(new IllegalArgumentException("Unexpected head tracking mode:" + sdkMode));
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
                throw(new IllegalArgumentException("Unexpected spatializer level:" + level));
        }
    }
}

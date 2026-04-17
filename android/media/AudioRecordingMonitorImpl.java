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

package android.media;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.AudioManager.AudioRecordingCallback;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Implementation of AudioRecordingMonitor interface.
 * @hide
 */
public class AudioRecordingMonitorImpl implements AudioRecordingMonitor {

    private static final String TAG = "android.media.AudioRecordingMonitor";

    private static IAudioService sService; //lazy initialization, use getService()
    private final AudioRecordingMonitorClient mClient;

    private final Object mRecordCallbackLock = new Object();

    @GuardedBy("mRecordCallbackLock")
    private List<AudioRecordingCallbackInfo> mRecordCallbackList =
            new ArrayList<AudioRecordingCallbackInfo>();

    private final IRecordingConfigDispatcher mRecordingCallback =
            new IRecordingConfigDispatcher.Stub() {
                @Override
                public void dispatchRecordingConfigChange(
                        List<AudioRecordingConfiguration> configs) {
                    AudioRecordingConfiguration config = getMyConfig(configs);
                    if (config != null) {
                        configs.removeIf(c -> c != config);
                        handleCallback(configs);
                    }
                }
            };

    AudioRecordingMonitorImpl(@NonNull AudioRecordingMonitorClient client) {
        mClient = client;
    }

    /**
     * Register a callback to be notified of audio capture changes via a
     * {@link AudioManager.AudioRecordingCallback}. A callback is received when the capture path
     * configuration changes (pre-processing, format, sampling rate...) or capture is
     * silenced/unsilenced by the system.
     * @param executor {@link Executor} to handle the callbacks.
     * @param cb non-null callback to register
     */
    public void registerAudioRecordingCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull AudioRecordingCallback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Illegal null Executor");
        }
        synchronized (mRecordCallbackLock) {
            // check if eventCallback already in list
            for (AudioRecordingCallbackInfo arci : mRecordCallbackList) {
                if (arci.cb == cb) {
                    throw new IllegalArgumentException(
                            "AudioRecordingCallback already registered");
                }
            }
            if (mRecordCallbackList.isEmpty()) {
                try {
                    getService().registerRecordingCallback(mRecordingCallback);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            mRecordCallbackList.add(new AudioRecordingCallbackInfo(cb, executor));
        }
    }

    /**
     * Unregister an audio recording callback previously registered with
     * {@link #registerAudioRecordingCallback(Executor, AudioManager.AudioRecordingCallback)}.
     * @param cb non-null callback to unregister
     */
    public void unregisterAudioRecordingCallback(@NonNull AudioRecordingCallback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
        }
        synchronized (mRecordCallbackLock) {
            if (mRecordCallbackList.removeIf((arci) -> arci.cb == cb)) {
                if (mRecordCallbackList.isEmpty()) {
                    try {
                        getService().unregisterRecordingCallback(mRecordingCallback);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            } else {
                throw new IllegalArgumentException("AudioRecordingCallback was not registered");
            }
        }
    }

    /**
     * Returns the current active audio recording for this audio recorder.
     * @return a valid {@link AudioRecordingConfiguration} if this recorder is active
     * or null otherwise.
     * @see AudioRecordingConfiguration
     */
    public @Nullable AudioRecordingConfiguration getActiveRecordingConfiguration() {
        final IAudioService service = getService();
        try {
            List<AudioRecordingConfiguration> configs = service.getActiveRecordingConfigurations();
            return getMyConfig(configs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static record AudioRecordingCallbackInfo(AudioRecordingCallback cb, Executor executor) {
        void dispatch(List<AudioRecordingConfiguration> configs) {
            executor.execute(() -> cb.onRecordingConfigChanged(configs));
        }
    }

    private void handleCallback(List<AudioRecordingConfiguration> configs) {
        List<AudioRecordingCallbackInfo> cbInfoList;
        synchronized (mRecordCallbackLock) {
            if (mRecordCallbackList.isEmpty()) {
                return;
            }
            cbInfoList = new ArrayList<AudioRecordingCallbackInfo>(mRecordCallbackList);
        }
        Binder.withCleanCallingIdentity(() -> cbInfoList.forEach(cbi -> cbi.dispatch(configs)));
    }

    // Package private for cleanup from AudioRecord#release
    void endRecordingCallbackHandling() {
        synchronized (mRecordCallbackLock) {
            if (!mRecordCallbackList.isEmpty()) {
                try {
                    getService().unregisterRecordingCallback(mRecordingCallback);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                mRecordCallbackList.clear();
            }
        }
    }

    AudioRecordingConfiguration getMyConfig(List<AudioRecordingConfiguration> configs) {
        int portId = mClient.getPortId();
        for (AudioRecordingConfiguration config : configs) {
            if (config.getClientPortId() == portId) {
                return config;
            }
        }
        return null;
    }

    private static IAudioService getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }
}

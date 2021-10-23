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

package com.android.server.display;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Saves brightness to a persistent data store, enabling each logical display to have its own
 * brightness.
 */
public class BrightnessSetting {
    private static final String TAG = "BrightnessSetting";

    private static final int MSG_BRIGHTNESS_CHANGED = 1;

    private final PersistentDataStore mPersistentDataStore;
    private final DisplayManagerService.SyncRoot mSyncRoot;

    private final LogicalDisplay mLogicalDisplay;

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_BRIGHTNESS_CHANGED) {
                float brightnessVal = Float.intBitsToFloat(msg.arg1);
                notifyListeners(brightnessVal);
            }
        }
    };

    private final CopyOnWriteArraySet<BrightnessSettingListener> mListeners =
            new CopyOnWriteArraySet<>();

    @GuardedBy("mSyncRoot")
    private float mBrightness;

    BrightnessSetting(@NonNull PersistentDataStore persistentDataStore,
            @NonNull LogicalDisplay logicalDisplay,
            DisplayManagerService.SyncRoot syncRoot) {
        mPersistentDataStore = persistentDataStore;
        mLogicalDisplay = logicalDisplay;
        mBrightness = mPersistentDataStore.getBrightness(
                mLogicalDisplay.getPrimaryDisplayDeviceLocked());
        mSyncRoot = syncRoot;
    }

    /**
     * Returns the brightness from the brightness setting
     *
     * @return brightness for the current display
     */
    public float getBrightness() {
        synchronized (mSyncRoot) {
            return mBrightness;
        }
    }

    /**
     * Registers listener for brightness setting change events.
     */
    public void registerListener(BrightnessSettingListener l) {
        if (mListeners.contains(l)) {
            Slog.wtf(TAG, "Duplicate Listener added");
        }
        mListeners.add(l);
    }

    /**
     * Unregisters listener for brightness setting change events.
     *
     * @param l listener
     */
    public void unregisterListener(BrightnessSettingListener l) {
        mListeners.remove(l);
    }

    void setBrightness(float brightness) {
        if (Float.isNaN(brightness)) {
            Slog.w(TAG, "Attempting to set invalid brightness");
            return;
        }
        synchronized (mSyncRoot) {
            if (brightness == mBrightness) {
                return;
            }

            mBrightness = brightness;
            mPersistentDataStore.setBrightness(mLogicalDisplay.getPrimaryDisplayDeviceLocked(),
                    brightness);
            int toSend = Float.floatToIntBits(mBrightness);
            Message msg = mHandler.obtainMessage(MSG_BRIGHTNESS_CHANGED, toSend, 0);
            mHandler.sendMessage(msg);
        }
    }

    private void notifyListeners(float brightness) {
        for (BrightnessSettingListener l : mListeners) {
            l.onBrightnessChanged(brightness);
        }
    }

    /**
     * Listener for changes to system brightness.
     */
    public interface BrightnessSettingListener {

        /**
         * Notify that the brightness has changed.
         */
        void onBrightnessChanged(float brightness);
    }
}

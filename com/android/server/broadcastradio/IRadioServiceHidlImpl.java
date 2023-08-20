/**
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.broadcastradio;

import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.IRadioService;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.os.RemoteException;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.broadcastradio.hal2.AnnouncementAggregator;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Wrapper for HIDL interface for BroadcastRadio HAL
 */
final class IRadioServiceHidlImpl extends IRadioService.Stub {
    private static final String TAG = "BcRadioSrvHidl";

    private final com.android.server.broadcastradio.hal1.BroadcastRadioService mHal1;
    private final com.android.server.broadcastradio.hal2.BroadcastRadioService mHal2;

    private final Object mLock = new Object();

    private final BroadcastRadioService mService;

    @GuardedBy("mLock")
    private final List<RadioManager.ModuleProperties> mV1Modules;

    IRadioServiceHidlImpl(BroadcastRadioService service) {
        mService = Objects.requireNonNull(service, "broadcast radio service cannot be null");
        mHal1 = new com.android.server.broadcastradio.hal1.BroadcastRadioService();
        mV1Modules = mHal1.loadModules();
        OptionalInt max = mV1Modules.stream().mapToInt(RadioManager.ModuleProperties::getId).max();
        mHal2 = new com.android.server.broadcastradio.hal2.BroadcastRadioService(
                max.isPresent() ? max.getAsInt() + 1 : 0);
    }

    @VisibleForTesting
    IRadioServiceHidlImpl(BroadcastRadioService service,
            com.android.server.broadcastradio.hal1.BroadcastRadioService hal1,
            com.android.server.broadcastradio.hal2.BroadcastRadioService hal2) {
        mService = Objects.requireNonNull(service, "Broadcast radio service cannot be null");
        mHal1 = Objects.requireNonNull(hal1,
                "Broadcast radio service implementation for HIDL 1 HAL cannot be null");
        mV1Modules = mHal1.loadModules();
        mHal2 = Objects.requireNonNull(hal2,
                "Broadcast radio service implementation for HIDL 2 HAL cannot be null");
    }

    @Override
    public List<RadioManager.ModuleProperties> listModules() {
        mService.enforcePolicyAccess();
        Collection<RadioManager.ModuleProperties> v2Modules = mHal2.listModules();
        List<RadioManager.ModuleProperties> modules;
        synchronized (mLock) {
            modules = new ArrayList<>(mV1Modules.size() + v2Modules.size());
            modules.addAll(mV1Modules);
        }
        modules.addAll(v2Modules);
        return modules;
    }

    @Override
    public ITuner openTuner(int moduleId, RadioManager.BandConfig bandConfig,
            boolean withAudio, ITunerCallback callback) throws RemoteException {
        if (isDebugEnabled()) {
            Slog.d(TAG, "Opening module " + moduleId);
        }
        mService.enforcePolicyAccess();
        Objects.requireNonNull(callback, "Callback must not be null");
        synchronized (mLock) {
            if (mHal2.hasModule(moduleId)) {
                return mHal2.openSession(moduleId, bandConfig, withAudio, callback);
            } else {
                return mHal1.openTuner(moduleId, bandConfig, withAudio, callback);
            }
        }
    }

    @Override
    public ICloseHandle addAnnouncementListener(int[] enabledTypes,
            IAnnouncementListener listener) {
        if (isDebugEnabled()) {
            Slog.d(TAG, "Adding announcement listener for " + Arrays.toString(enabledTypes));
        }
        Objects.requireNonNull(enabledTypes, "Enabled announcement types cannot be null");
        Objects.requireNonNull(listener, "Announcement listener cannot be null");
        mService.enforcePolicyAccess();

        synchronized (mLock) {
            if (!mHal2.hasAnyModules()) {
                Slog.w(TAG, "There are no HAL 2.0 modules registered");
                return new AnnouncementAggregator(listener, mLock);
            }

            return mHal2.addAnnouncementListener(enabledTypes, listener);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        IndentingPrintWriter radioPw = new IndentingPrintWriter(pw);
        radioPw.printf("BroadcastRadioService\n");

        radioPw.increaseIndent();
        radioPw.printf("HAL1: %s\n", mHal1);

        radioPw.increaseIndent();
        synchronized (mLock) {
            radioPw.printf("Modules of HAL1: %s\n", mV1Modules);
        }
        radioPw.decreaseIndent();

        radioPw.printf("HAL2:\n");

        radioPw.increaseIndent();
        mHal2.dumpInfo(radioPw);
        radioPw.decreaseIndent();

        radioPw.decreaseIndent();
    }


    private static boolean isDebugEnabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }
}

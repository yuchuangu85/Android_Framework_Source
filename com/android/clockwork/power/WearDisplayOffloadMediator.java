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

package com.android.clockwork.power;

import static android.os.PowerManager.WAKE_REASON_APPLICATION;

import static com.android.clockwork.power.IWearPowerService.OFFLOAD_BACKEND_TYPE_DISPLAYOFFLOAD;
import static com.android.clockwork.power.IWearPowerService.OFFLOAD_BACKEND_TYPE_NA;
import static com.android.clockwork.power.IWearPowerService.OFFLOAD_BACKEND_TYPE_SIDEKICK;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import com.android.internal.util.IndentingPrintWriter;

import com.google.android.clockwork.ambient.offload.IDisplayOffloadService;
import com.google.android.clockwork.sidekick.ISidekickService;
import com.google.android.clockwork.sidekick.SidekickServiceConstants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Mediator that handles different offload backends.
 */
public class WearDisplayOffloadMediator {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                    OFFLOAD_BACKEND_TYPE_NA,
                    OFFLOAD_BACKEND_TYPE_SIDEKICK,
                    OFFLOAD_BACKEND_TYPE_DISPLAYOFFLOAD
            })
    public @interface OffloadBackendType {
    }

    private static final boolean DEBUG = Build.IS_DEBUGGABLE;
    private static final String TAG = "WearDisplayOffloadMediator";

    private final @Nullable
    IDisplayOffloadService mDisplayOffloadService;
    private final @Nullable
    ISidekickService mSidekickService;
    private final IActivityTaskManager mActivityTaskManagerService;

    private @OffloadBackendType
    int mOffloadBackendType = OFFLOAD_BACKEND_TYPE_NA;

    private PowerManager mPowerManager;
    private WallpaperManager mWallpaperManager;
    private DisplayManager mDisplayManager;

    private final String mSysUiPackageName;

    public WearDisplayOffloadMediator(Context context) {
        // Find DisplayOffloadService.
        mDisplayOffloadService = IDisplayOffloadService.Stub.asInterface(
                ServiceManager.getService(IDisplayOffloadService.NAME));
        if (mDisplayOffloadService != null) {
            Log.i(TAG, "Found DisplayOffloadService");
        }

        // Find SidekickService and check if HAL is up.
        ISidekickService sidekickService = ISidekickService.Stub.asInterface(
                ServiceManager.getService(SidekickServiceConstants.NAME));
        if (sidekickService != null) {
            try {
                if (!sidekickService.sidekickExists()) {
                    sidekickService = null;
                } else {
                    Log.i(TAG, "Found SidekickService");
                }
            } catch (RemoteException e) {
                Log.w(TAG, "ISidekickService threw " + e);
                sidekickService = null;
            }
        }
        mSidekickService = sidekickService;

        mActivityTaskManagerService = IActivityTaskManager.Stub.asInterface(
                ServiceManager.getService(Context.ACTIVITY_TASK_SERVICE));

        mSysUiPackageName =
                Settings.Global.getString(context.getContentResolver(),
                                          Settings.Global.Wearable.CLOCKWORK_SYSUI_PACKAGE);

        // Prioritize using DisplayOffload
        if (mDisplayOffloadService != null) {
            mOffloadBackendType = OFFLOAD_BACKEND_TYPE_DISPLAYOFFLOAD;
        } else if (mSidekickService != null) {
            mOffloadBackendType = OFFLOAD_BACKEND_TYPE_SIDEKICK;
        } else {
            mOffloadBackendType = OFFLOAD_BACKEND_TYPE_NA;
            return;
        }

        mPowerManager = context.getSystemService(PowerManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mWallpaperManager = context.getSystemService(WallpaperManager.class);

        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        mWallpaperManager.addOnColorsChangedListener(
                (WallpaperColors colors, int which) -> {
                    ComponentName topActivity = null;
                    try {
                        ActivityTaskManager.RootTaskInfo stackInfo =
                                mActivityTaskManagerService.getFocusedRootTaskInfo();
                        topActivity = stackInfo == null ? null : stackInfo.topActivity;
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to load top stack info");
                    }
                    String topActivityPackageName =
                            topActivity == null ? null : topActivity.getPackageName();
                    if (mSysUiPackageName.equals(topActivityPackageName) &&
                            display.getState() == Display.STATE_DOZE_SUSPEND) {
                        mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                                WAKE_REASON_APPLICATION,
                                "WearDisplayOffloadMediator: new style in ambient");
                    }
                }, null);
    }

    public int offloadBackendGetType() {
        return mOffloadBackendType;
    }

    public void setShouldControlDisplay(boolean shouldControlDisplay) {
        if (DEBUG) {
            Log.i(TAG, "setShouldControlDisplay: should=" + shouldControlDisplay);
        }

        // Only SidekickService is using this call
        if (mOffloadBackendType == OFFLOAD_BACKEND_TYPE_SIDEKICK) {
            try {
                mSidekickService.setShouldControlDisplay(shouldControlDisplay);
            } catch (RemoteException e) {
                Log.w(TAG, "setShouldControlDisplay: ISidekickService threw " + e);
            }
        } else {
            if (DEBUG) {
                Log.i(TAG, "setShouldControlDisplay: Sidekick is not available, do nothing.");
            }
        }
    }

    public boolean offloadBackendReadyToDisplay() {
        if (mOffloadBackendType == OFFLOAD_BACKEND_TYPE_SIDEKICK) {
            try {
                return mSidekickService.readyToDisplay();
            } catch (RemoteException e) {
                Log.w(TAG, "offloadBackendReadyToDisplay: ISidekickService threw " + e);
            }
        } else if (mOffloadBackendType == OFFLOAD_BACKEND_TYPE_DISPLAYOFFLOAD) {
            try {
                return mDisplayOffloadService.readyToDisplay();
            } catch (RemoteException e) {
                Log.w(TAG, "offloadBackendReadyToDisplay: IDisplayOffloadService threw " + e);
            }
        }

        if (DEBUG) {
            Log.i(TAG, "offloadBackendReadyToDisplay: no backend is ready to display.");
        }
        return false;
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("======== WearDisplayOffloadMediator ========");
        ipw.println();

        ipw.increaseIndent();

        ipw.print("Offload Backends Available:");
        if (mSidekickService != null) ipw.print(" Sidekick");
        if (mOffloadBackendType == OFFLOAD_BACKEND_TYPE_SIDEKICK) ipw.print("[In Use]");

        if (mDisplayOffloadService != null) ipw.print(" DisplayOffload");
        if (mOffloadBackendType == OFFLOAD_BACKEND_TYPE_DISPLAYOFFLOAD) ipw.print("[In Use]");

        ipw.println();

        ipw.decreaseIndent();

        ipw.println();
    }
}

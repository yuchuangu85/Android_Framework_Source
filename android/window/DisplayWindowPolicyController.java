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

package android.window;

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.util.ArraySet;

import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

/**
 * Abstract class to control the policies of the windows that can be displayed on the virtual
 * display.
 *
 * @hide
 */
public abstract class DisplayWindowPolicyController {
    /**
     * The window flags that we are interested in.
     * @see android.view.WindowManager.LayoutParams
     * @see #keepActivityOnWindowFlagsChanged
     */
    private int mWindowFlags;

    /**
     * The system window flags that we are interested in.
     * @see android.view.WindowManager.LayoutParams
     * @see #keepActivityOnWindowFlagsChanged
     */
    private int mSystemWindowFlags;

    /**
     * The set of windowing mode that are supported in this display.
     * @see android.app.WindowConfiguration.WindowingMode
     */
    private final Set<Integer> mSupportedWindowingModes = new ArraySet<>();

    /**
     * A controller to control the policies of the windows that can be displayed on the virtual
     * display.
     */
    public DisplayWindowPolicyController() {
        synchronized (mSupportedWindowingModes) {
            mSupportedWindowingModes.add(WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        }
    }

    /**
     * Returns {@code true} if the given window flags contain the flags that we're interested in.
     */
    public final boolean isInterestedWindowFlags(int windowFlags, int systemWindowFlags) {
        return (mWindowFlags & windowFlags) != 0 || (mSystemWindowFlags & systemWindowFlags) != 0;
    }

    /**
     * Sets the window flags that we’re interested in and expected
     * #keepActivityOnWindowFlagsChanged to be called if any changes.
     */
    public final void setInterestedWindowFlags(int windowFlags, int systemWindowFlags) {
        mWindowFlags = windowFlags;
        mSystemWindowFlags = systemWindowFlags;
    }

    /**
     * Returns {@code true} if the given windowing mode is supported in this display.
     */
    public final boolean isWindowingModeSupported(
            @WindowConfiguration.WindowingMode int windowingMode) {
        synchronized (mSupportedWindowingModes) {
            return mSupportedWindowingModes.contains(windowingMode);
        }
    }

    /**
     * Sets the windowing modes are supported in this display.
     *
     * @param supportedWindowingModes The set of
     * {@link android.app.WindowConfiguration.WindowingMode}.
     */
    public final void setSupportedWindowingModes(Set<Integer> supportedWindowingModes) {
        synchronized (mSupportedWindowingModes) {
            mSupportedWindowingModes.clear();
            mSupportedWindowingModes.addAll(supportedWindowingModes);
        }
    }

    /**
     * Returns {@code true} if the given activities can be displayed on this virtual display and
     * the windowing mode is supported.
     */
    public abstract boolean canContainActivities(@NonNull List<ActivityInfo> activities,
            @WindowConfiguration.WindowingMode int windowingMode);

    /**
     * Returns {@code true} if the given new task can be launched on this virtual display.
     */
    public abstract boolean canActivityBeLaunched(@NonNull ActivityInfo activityInfo, Intent intent,
            @WindowConfiguration.WindowingMode int windowingMode, int launchingFromDisplayId,
            boolean isNewTask);

    /**
     * Called when an Activity window is layouted with the new changes where contains the
     * window flags that we’re interested in.
     * Returns {@code false} if the Activity cannot remain on the display and the activity task will
     * be moved back to default display.
     */
    public abstract boolean keepActivityOnWindowFlagsChanged(
            ActivityInfo activityInfo, int windowFlags, int systemWindowFlags);

    /**
     * Returns {@code true} if the tasks which is on this virtual display can be showed in the
     * host device of the recently launched activities list.
     */
    public abstract boolean canShowTasksInHostDeviceRecents();

    /**
     * This is called when the top activity of the display is changed.
     */
    public void onTopActivityChanged(ComponentName topActivity, int uid, @UserIdInt int userId) {}

    /**
     * This is called when the apps that contains running activities on the display has changed.
     * The running activities refer to the non-finishing activities regardless of they are running
     * in a process.
     */
    public void onRunningAppsChanged(ArraySet<Integer> runningUids) {}

    /**
     * This is called when an Activity is entering PIP.
     * Returns {@code true} if the Activity is allowed to enter PIP.
     */
    public boolean isEnteringPipAllowed(int uid) {
        return isWindowingModeSupported(WINDOWING_MODE_PINNED);
    }

    /** Dump debug data */
    public void dump(String prefix, final PrintWriter pw) {
        pw.println(prefix + "DisplayWindowPolicyController{" + super.toString() + "}");
        pw.println(prefix + "  mWindowFlags=" + mWindowFlags);
        pw.println(prefix + "  mSystemWindowFlags=" + mSystemWindowFlags);
    }
}

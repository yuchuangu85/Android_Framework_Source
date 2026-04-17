/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app;

import android.compat.Compatibility;
import android.os.Process;

import com.android.internal.compat.CompatibilityRules;
import com.android.internal.compat.ChangeReporter;

import java.util.Arrays;

/**
 * App process implementation of the {@link Compatibility} API.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class AppCompatCallbacks implements Compatibility.BehaviorChangeDelegate {
    private final long[] mDisabledChanges;
    private final long[] mEnabledChanges;
    private final long[] mLoggableChanges;
    private final int mTargetSdkVersion;
    private final ChangeReporter mChangeReporter;
    private boolean mLogChangeChecksToStatsD = false;

    /**
     * Install this class into the current process using the disabled and loggable changes lists.
     *
     * @param disabledChangesSorted Sorted set of compatibility changes that are disabled for
     * this process. MUST be sorted.
     * @param loggableChangesSorted Sorted set of compatibility changes that we want to log.
     * MUST be sorted.
     */
    public static void install(long[] disabledChangesSorted, long[] loggableChangesSorted) {
        Compatibility.setBehaviorChangeDelegate(
                new AppCompatCallbacks(disabledChangesSorted, null, loggableChangesSorted,
                        false, -1));
    }

    /**
     * Install this class into the current process using the disabled and loggable changes lists.
     *
     * @param disabledChangesSorted Sorted set of compatibility changes that are disabled for
     * this process. MUST be sorted.
     * @param loggableChangesSorted Sorted set of compatibility changes that we want to log.
     * MUST be sorted.
     * @param logChangeChecksToStatsD Whether to log change checks to statsd.
     */
    public static void install(long[] disabledChangesSorted, long[] loggableChangesSorted,
            boolean logChangeChecksToStatsD) {
        Compatibility.setBehaviorChangeDelegate(
                new AppCompatCallbacks(
                        disabledChangesSorted,
                        null,
                        loggableChangesSorted,
                        logChangeChecksToStatsD,
                        -1));
    }

    /**
     * Install this class into the current process.
     *
     * @param disabledChangesSorted Sorted set of compatibility changes that are disabled for
     * this process. MUST be sorted.
     * @param enabledChangesSorted Sorted set of compatibility changes that are enabled for
     * this process (overrides). MUST be sorted.
     * @param loggableChangesSorted Sorted set of compatibility changes that we want to log.
     * MUST be sorted.
     * @param logChangeChecksToStatsD Whether to log change checks to statsd.
     * @param targetSdkVersion The target SDK version of the app.
     */
    public static void install(long[] disabledChangesSorted, long[] enabledChangesSorted,
            long[] loggableChangesSorted, boolean logChangeChecksToStatsD, int targetSdkVersion) {
        Compatibility.setBehaviorChangeDelegate(
                new AppCompatCallbacks(disabledChangesSorted, enabledChangesSorted,
                        loggableChangesSorted, logChangeChecksToStatsD, targetSdkVersion));
    }

    private AppCompatCallbacks(long[] disabledChangesSorted, long[] enabledChangesSorted,
            long[] loggableChangesSorted, boolean logChangeChecksToStatsD, int targetSdkVersion) {
        mDisabledChanges = disabledChangesSorted;
        mEnabledChanges = enabledChangesSorted;
        mLoggableChanges = loggableChangesSorted;
        mChangeReporter = new ChangeReporter(ChangeReporter.SOURCE_APP_PROCESS);
        mLogChangeChecksToStatsD = logChangeChecksToStatsD;
        mTargetSdkVersion = targetSdkVersion;
    }

    /**
     * Helper to determine if a list contains a changeId.
     *
     * @param list to search through
     * @param changeId for which to search in the list
     * @return true if the given changeId is found in the provided array.
     */
    private boolean changeIdInChangeList(long[] list, long changeId) {
        if (list == null) {
            return false;
        }
        return Arrays.binarySearch(list, changeId) >= 0;
    }

    public void onChangeReported(long changeId) {
        isChangeEnabledAndReport(changeId, true);
    }

    public boolean isChangeEnabled(long changeId) {
        boolean isLoggable = mLogChangeChecksToStatsD ||
            changeIdInChangeList(mLoggableChanges, changeId);
        if (!isLoggable) {
            return isChangeEnabledInternal(changeId);
        }
        return isChangeEnabledAndReport(changeId, mLogChangeChecksToStatsD);
    }

    private boolean isChangeEnabledInternal(long changeId) {
        // Evaluation hierarchy:
        // 1. Process-level Disabled Overrides (explicitly disabled via command-line)
        // 2. Process-level Enabled Overrides (explicitly enabled via command-line/debugging)
        // 3. Preloaded System Rules (based on targetSdkVersion/static XMLs)
        // 4. Fallback to Enabled (unknown changes are typically permitted)
        if (changeIdInChangeList(mDisabledChanges, changeId)) {
            return false;
        }
        if (mEnabledChanges != null && changeIdInChangeList(mEnabledChanges, changeId)) {
            return true;
        }
        if (mTargetSdkVersion != -1) {
            return CompatibilityRules.isChangeEnabled(changeId, mTargetSdkVersion);
        }
        return true;
    }

    private boolean isChangeEnabledAndReport(long changeId, boolean doStatsLog) {
        boolean isEnabled = isChangeEnabledInternal(changeId);
        if (isEnabled) {
            reportChange(changeId, ChangeReporter.STATE_ENABLED, doStatsLog);
            return true;
        }
        reportChange(changeId, ChangeReporter.STATE_DISABLED, doStatsLog);
        return false;
    }

    private void reportChange(long changeId, int state, boolean doStatsLog) {
        int uid = Process.myUid();
        mChangeReporter.reportChange(uid, changeId, state, false, doStatsLog);
    }

}

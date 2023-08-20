/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.power.stats;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.SystemClock;
import android.os.UidBatteryConsumer;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.PowerProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Uses accumulated battery stats data and PowerCalculators to produce power
 * usage data attributed to subsystems and UIDs.
 */
public class BatteryUsageStatsProvider {
    private static final String TAG = "BatteryUsageStatsProv";
    private final Context mContext;
    private final BatteryStats mStats;
    private final BatteryUsageStatsStore mBatteryUsageStatsStore;
    private final PowerProfile mPowerProfile;
    private final Object mLock = new Object();
    private List<PowerCalculator> mPowerCalculators;

    public BatteryUsageStatsProvider(Context context, BatteryStats stats) {
        this(context, stats, null);
    }

    @VisibleForTesting
    public BatteryUsageStatsProvider(Context context, BatteryStats stats,
            BatteryUsageStatsStore batteryUsageStatsStore) {
        mContext = context;
        mStats = stats;
        mBatteryUsageStatsStore = batteryUsageStatsStore;
        mPowerProfile = stats instanceof BatteryStatsImpl
                ? ((BatteryStatsImpl) stats).getPowerProfile()
                : new PowerProfile(context);
    }

    private List<PowerCalculator> getPowerCalculators() {
        synchronized (mLock) {
            if (mPowerCalculators == null) {
                mPowerCalculators = new ArrayList<>();

                // Power calculators are applied in the order of registration
                mPowerCalculators.add(new BatteryChargeCalculator());
                mPowerCalculators.add(new CpuPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new MemoryPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new WakelockPowerCalculator(mPowerProfile));
                if (!BatteryStats.checkWifiOnly(mContext)) {
                    mPowerCalculators.add(new MobileRadioPowerCalculator(mPowerProfile));
                }
                mPowerCalculators.add(new WifiPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new BluetoothPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new SensorPowerCalculator(
                        mContext.getSystemService(SensorManager.class)));
                mPowerCalculators.add(new GnssPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new CameraPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new FlashlightPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new AudioPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new VideoPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new PhonePowerCalculator(mPowerProfile));
                mPowerCalculators.add(new ScreenPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new AmbientDisplayPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new IdlePowerCalculator(mPowerProfile));
                mPowerCalculators.add(new CustomEnergyConsumerPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new UserPowerCalculator());

                // It is important that SystemServicePowerCalculator be applied last,
                // because it re-attributes some of the power estimated by the other
                // calculators.
                mPowerCalculators.add(new SystemServicePowerCalculator(mPowerProfile));
            }
        }
        return mPowerCalculators;
    }

    /**
     * Returns true if the last update was too long ago for the tolerances specified
     * by the supplied queries.
     */
    public boolean shouldUpdateStats(List<BatteryUsageStatsQuery> queries,
            long lastUpdateTimeStampMs) {
        long allowableStatsAge = Long.MAX_VALUE;
        for (int i = queries.size() - 1; i >= 0; i--) {
            BatteryUsageStatsQuery query = queries.get(i);
            allowableStatsAge = Math.min(allowableStatsAge, query.getMaxStatsAge());
        }

        return elapsedRealtime() - lastUpdateTimeStampMs > allowableStatsAge;
    }

    /**
     * Returns snapshots of battery attribution data, one per supplied query.
     */
    public List<BatteryUsageStats> getBatteryUsageStats(List<BatteryUsageStatsQuery> queries) {
        ArrayList<BatteryUsageStats> results = new ArrayList<>(queries.size());
        synchronized (mStats) {
            mStats.prepareForDumpLocked();
            final long currentTimeMillis = currentTimeMillis();
            for (int i = 0; i < queries.size(); i++) {
                results.add(getBatteryUsageStats(queries.get(i), currentTimeMillis));
            }
        }
        return results;
    }

    /**
     * Returns a snapshot of battery attribution data.
     */
    @VisibleForTesting
    public BatteryUsageStats getBatteryUsageStats(BatteryUsageStatsQuery query) {
        synchronized (mStats) {
            return getBatteryUsageStats(query, currentTimeMillis());
        }
    }

    @GuardedBy("mStats")
    private BatteryUsageStats getBatteryUsageStats(BatteryUsageStatsQuery query,
            long currentTimeMs) {
        if (query.getToTimestamp() == 0) {
            return getCurrentBatteryUsageStats(query, currentTimeMs);
        } else {
            return getAggregatedBatteryUsageStats(query);
        }
    }

    @GuardedBy("mStats")
    private BatteryUsageStats getCurrentBatteryUsageStats(BatteryUsageStatsQuery query,
            long currentTimeMs) {
        final long realtimeUs = elapsedRealtime() * 1000;
        final long uptimeUs = uptimeMillis() * 1000;

        final boolean includePowerModels = (query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_POWER_MODELS) != 0;
        final boolean includeProcessStateData = ((query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_PROCESS_STATE_DATA) != 0)
                && mStats.isProcessStateDataAvailable();
        final boolean includeVirtualUids =  ((query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_VIRTUAL_UIDS) != 0);

        final BatteryUsageStats.Builder batteryUsageStatsBuilder = new BatteryUsageStats.Builder(
                mStats.getCustomEnergyConsumerNames(), includePowerModels,
                includeProcessStateData);
        // TODO(b/188068523): use a monotonic clock to ensure resilience of order and duration
        // of stats sessions to wall-clock adjustments
        batteryUsageStatsBuilder.setStatsStartTimestamp(mStats.getStartClockTime());
        batteryUsageStatsBuilder.setStatsEndTimestamp(currentTimeMs);

        SparseArray<? extends BatteryStats.Uid> uidStats = mStats.getUidStats();
        for (int i = uidStats.size() - 1; i >= 0; i--) {
            final BatteryStats.Uid uid = uidStats.valueAt(i);
            if (!includeVirtualUids && uid.getUid() == Process.SDK_SANDBOX_VIRTUAL_UID) {
                continue;
            }

            batteryUsageStatsBuilder.getOrCreateUidBatteryConsumerBuilder(uid)
                    .setTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND,
                            getProcessBackgroundTimeMs(uid, realtimeUs))
                    .setTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND,
                            getProcessForegroundTimeMs(uid, realtimeUs));
        }

        final int[] powerComponents = query.getPowerComponents();
        final List<PowerCalculator> powerCalculators = getPowerCalculators();
        for (int i = 0, count = powerCalculators.size(); i < count; i++) {
            PowerCalculator powerCalculator = powerCalculators.get(i);
            if (powerComponents != null) {
                boolean include = false;
                for (int j = 0; j < powerComponents.length; j++) {
                    if (powerCalculator.isPowerComponentSupported(powerComponents[j])) {
                        include = true;
                        break;
                    }
                }
                if (!include) {
                    continue;
                }
            }
            powerCalculator.calculate(batteryUsageStatsBuilder, mStats, realtimeUs, uptimeUs,
                    query);
        }

        if ((query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_HISTORY) != 0) {
            if (!(mStats instanceof BatteryStatsImpl)) {
                throw new UnsupportedOperationException(
                        "History cannot be included for " + getClass().getName());
            }

            BatteryStatsImpl batteryStatsImpl = (BatteryStatsImpl) mStats;
            batteryUsageStatsBuilder.setBatteryHistory(batteryStatsImpl.copyHistory());
        }

        BatteryUsageStats stats = batteryUsageStatsBuilder.build();
        if (includeProcessStateData) {
            verify(stats);
        }
        return stats;
    }

    // STOPSHIP(b/229906525): remove verification before shipping
    private static boolean sErrorReported;
    private void verify(BatteryUsageStats stats) {
        if (sErrorReported) {
            return;
        }

        final double precision = 2.0;   // Allow rounding errors up to 2 mAh
        final int[] components =
                {BatteryConsumer.POWER_COMPONENT_CPU,
                        BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                        BatteryConsumer.POWER_COMPONENT_WIFI,
                        BatteryConsumer.POWER_COMPONENT_BLUETOOTH};
        final int[] states =
                {BatteryConsumer.PROCESS_STATE_FOREGROUND,
                        BatteryConsumer.PROCESS_STATE_BACKGROUND,
                        BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE,
                        BatteryConsumer.PROCESS_STATE_CACHED};
        for (UidBatteryConsumer ubc : stats.getUidBatteryConsumers()) {
            for (int component : components) {
                double consumedPower = ubc.getConsumedPower(ubc.getKey(component));
                double sumStates = 0;
                for (int state : states) {
                    sumStates += ubc.getConsumedPower(ubc.getKey(component, state));
                }
                if (sumStates > consumedPower + precision) {
                    String error = "Sum of states exceeds total. UID = " + ubc.getUid() + " "
                            + BatteryConsumer.powerComponentIdToString(component)
                            + " total = " + consumedPower + " states = " + sumStates;
                    if (!sErrorReported) {
                        Slog.wtf(TAG, error);
                        sErrorReported = true;
                    } else {
                        Slog.e(TAG, error);
                    }
                    return;
                }
            }
        }
    }

    private long getProcessForegroundTimeMs(BatteryStats.Uid uid, long realtimeUs) {
        final long topStateDurationUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_TOP,
                realtimeUs, BatteryStats.STATS_SINCE_CHARGED);
        long foregroundActivityDurationUs = 0;
        final BatteryStats.Timer foregroundActivityTimer = uid.getForegroundActivityTimer();
        if (foregroundActivityTimer != null) {
            foregroundActivityDurationUs = foregroundActivityTimer.getTotalTimeLocked(realtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED);
        }

        // Use the min value of STATE_TOP time and foreground activity time, since both of these
        // times are imprecise
        long totalForegroundDurationUs = Math.min(topStateDurationUs, foregroundActivityDurationUs);

        totalForegroundDurationUs += uid.getProcessStateTime(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, realtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);

        return totalForegroundDurationUs / 1000;
    }

    private long getProcessBackgroundTimeMs(BatteryStats.Uid uid, long realtimeUs) {
        return (uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_BACKGROUND,
                realtimeUs, BatteryStats.STATS_SINCE_CHARGED)
                + uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_FOREGROUND_SERVICE,
                realtimeUs, BatteryStats.STATS_SINCE_CHARGED))
                / 1000;
    }

    private BatteryUsageStats getAggregatedBatteryUsageStats(BatteryUsageStatsQuery query) {
        final boolean includePowerModels = (query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_POWER_MODELS) != 0;
        final boolean includeProcessStateData = ((query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_PROCESS_STATE_DATA) != 0)
                && mStats.isProcessStateDataAvailable();

        final String[] customEnergyConsumerNames = mStats.getCustomEnergyConsumerNames();
        final BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(
                customEnergyConsumerNames, includePowerModels, includeProcessStateData);
        if (mBatteryUsageStatsStore == null) {
            Log.e(TAG, "BatteryUsageStatsStore is unavailable");
            return builder.build();
        }

        final long[] timestamps = mBatteryUsageStatsStore.listBatteryUsageStatsTimestamps();
        for (long timestamp : timestamps) {
            if (timestamp > query.getFromTimestamp() && timestamp <= query.getToTimestamp()) {
                final BatteryUsageStats snapshot =
                        mBatteryUsageStatsStore.loadBatteryUsageStats(timestamp);
                if (snapshot == null) {
                    continue;
                }

                if (!Arrays.equals(snapshot.getCustomPowerComponentNames(),
                        customEnergyConsumerNames)) {
                    Log.w(TAG, "Ignoring older BatteryUsageStats snapshot, which has different "
                            + "custom power components: "
                            + Arrays.toString(snapshot.getCustomPowerComponentNames()));
                    continue;
                }

                if (includeProcessStateData && !snapshot.isProcessStateDataIncluded()) {
                    Log.w(TAG, "Ignoring older BatteryUsageStats snapshot, which "
                            + " does not include process state data");
                    continue;
                }

                builder.add(snapshot);
            }
        }
        return builder.build();
    }

    private long elapsedRealtime() {
        if (mStats instanceof BatteryStatsImpl) {
            return ((BatteryStatsImpl) mStats).mClock.elapsedRealtime();
        } else {
            return SystemClock.elapsedRealtime();
        }
    }

    private long uptimeMillis() {
        if (mStats instanceof BatteryStatsImpl) {
            return ((BatteryStatsImpl) mStats).mClock.uptimeMillis();
        } else {
            return SystemClock.uptimeMillis();
        }
    }

    private long currentTimeMillis() {
        if (mStats instanceof BatteryStatsImpl) {
            return ((BatteryStatsImpl) mStats).mClock.currentTimeMillis();
        } else {
            return System.currentTimeMillis();
        }
    }
}

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

package com.android.internal.telephony.metrics;

import static com.android.internal.telephony.metrics.TelephonyMetrics.toCallQualityProto;

import android.os.Build;
import android.telephony.CallQuality;
import android.telephony.CellInfo;
import android.telephony.CellSignalStrengthLte;
import android.telephony.Rlog;
import android.telephony.SignalStrength;
import android.util.Pair;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.nano.TelephonyProto.TelephonyCallSession;

import java.util.ArrayList;

/**
 * CallQualityMetrics is a utility for tracking the CallQuality during an ongoing call session. It
 * processes snapshots throughout the call to keep track of info like the best and worst
 * ServiceStates, durations of good and bad quality, and other summary statistics.
 */
public class CallQualityMetrics {

    private static final String TAG = CallQualityMetrics.class.getSimpleName();

    // certain metrics are only logged on userdebug
    private static final boolean USERDEBUG_MODE = Build.IS_USERDEBUG;

    // We only log the first MAX_SNAPSHOTS changes to CallQuality
    private static final int MAX_SNAPSHOTS = 5;

    // value of mCallQualityState which means the CallQuality is EXCELLENT/GOOD/FAIR
    private static final int GOOD_QUALITY = 0;

    // value of mCallQualityState which means the CallQuality is BAD/POOR
    private static final int BAD_QUALITY = 1;

    private Phone mPhone;

    /** Snapshots of the call quality and SignalStrength (LTE-SNR for IMS calls) */
    // mUlSnapshots holds snapshots from uplink call quality changes. We log take snapshots of the
    // first MAX_SNAPSHOTS transitions between good and bad quality
    private ArrayList<Pair<CallQuality, Integer>> mUlSnapshots = new ArrayList<>();
    // mDlSnapshots holds snapshots from downlink call quality changes. We log take snapshots of
    // the first MAX_SNAPSHOTS transitions between good and bad quality
    private ArrayList<Pair<CallQuality, Integer>> mDlSnapshots = new ArrayList<>();

    // Current downlink call quality
    private int mDlCallQualityState = GOOD_QUALITY;

    // Current uplink call quality
    private int mUlCallQualityState = GOOD_QUALITY;

    // The last logged CallQuality
    private CallQuality mLastCallQuality;

    /** Snapshots taken at best and worst SignalStrengths*/
    private Pair<CallQuality, Integer> mWorstSsWithGoodDlQuality;
    private Pair<CallQuality, Integer> mBestSsWithGoodDlQuality;
    private Pair<CallQuality, Integer> mWorstSsWithBadDlQuality;
    private Pair<CallQuality, Integer> mBestSsWithBadDlQuality;
    private Pair<CallQuality, Integer> mWorstSsWithGoodUlQuality;
    private Pair<CallQuality, Integer> mBestSsWithGoodUlQuality;
    private Pair<CallQuality, Integer> mWorstSsWithBadUlQuality;
    private Pair<CallQuality, Integer> mBestSsWithBadUlQuality;

    /** Total durations of good and bad quality time for uplink and downlink */
    private int mTotalDlGoodQualityTimeMs = 0;
    private int mTotalDlBadQualityTimeMs = 0;
    private int mTotalUlGoodQualityTimeMs = 0;
    private int mTotalUlBadQualityTimeMs = 0;

    /**
     * Construct a CallQualityMetrics object to be used to keep track of call quality for a single
     * call session.
     */
    public CallQualityMetrics(Phone phone) {
        mPhone = phone;
        mLastCallQuality = new CallQuality();
    }

    /**
     * Called when call quality changes.
     */
    public void saveCallQuality(CallQuality cq) {
        if (cq.getUplinkCallQualityLevel() == CallQuality.CALL_QUALITY_NOT_AVAILABLE
                || cq.getDownlinkCallQualityLevel() == CallQuality.CALL_QUALITY_NOT_AVAILABLE) {
            return;
        }

        // uplink and downlink call quality are tracked separately
        int newUlCallQualityState = BAD_QUALITY;
        int newDlCallQualityState = BAD_QUALITY;
        if (isGoodQuality(cq.getUplinkCallQualityLevel())) {
            newUlCallQualityState = GOOD_QUALITY;
        }
        if (isGoodQuality(cq.getDownlinkCallQualityLevel())) {
            newDlCallQualityState = GOOD_QUALITY;
        }

        if (USERDEBUG_MODE) {
            if (newUlCallQualityState != mUlCallQualityState) {
                mUlSnapshots = addSnapshot(cq, mUlSnapshots);
            }
            if (newDlCallQualityState != mDlCallQualityState) {
                mDlSnapshots = addSnapshot(cq, mDlSnapshots);
            }
        }

        updateTotalDurations(newDlCallQualityState, newUlCallQualityState, cq);

        updateMinAndMaxSignalStrengthSnapshots(newDlCallQualityState, newUlCallQualityState, cq);

        mUlCallQualityState = newUlCallQualityState;
        mDlCallQualityState = newDlCallQualityState;
        mLastCallQuality = cq;
    }

    private static boolean isGoodQuality(int callQualityLevel) {
        return callQualityLevel < CallQuality.CALL_QUALITY_BAD;
    }

    /**
     * Save a snapshot of the call quality and signal strength. This can be called with uplink or
     * downlink call quality level.
     */
    private ArrayList<Pair<CallQuality, Integer>> addSnapshot(CallQuality cq,
            ArrayList<Pair<CallQuality, Integer>> snapshots) {
        if (snapshots.size() < MAX_SNAPSHOTS) {
            Integer ss = getLteSnr();
            snapshots.add(Pair.create(cq, ss));
        }
        return snapshots;
    }

    /**
     * Updates the running total duration of good and bad call quality for uplink and downlink.
     */
    private void updateTotalDurations(int newDlCallQualityState, int newUlCallQualityState,
            CallQuality cq) {
        int timePassed = cq.getCallDuration() - mLastCallQuality.getCallDuration();
        if (newDlCallQualityState == GOOD_QUALITY) {
            mTotalDlGoodQualityTimeMs += timePassed;
        } else {
            mTotalDlBadQualityTimeMs += timePassed;
        }

        if (newUlCallQualityState == GOOD_QUALITY) {
            mTotalUlGoodQualityTimeMs += timePassed;
        } else {
            mTotalUlBadQualityTimeMs += timePassed;
        }
    }

    /**
     * Updates the snapshots saved when signal strength is highest and lowest while the call quality
     * is good and bad for both uplink and downlink call quality.
     * <p>
     * At the end of the call we should have:
     *  - for both UL and DL:
     *     - snapshot of the best signal strength with bad call quality
     *     - snapshot of the worst signal strength with bad call quality
     *     - snapshot of the best signal strength with good call quality
     *     - snapshot of the worst signal strength with good call quality
     */
    private void updateMinAndMaxSignalStrengthSnapshots(int newDlCallQualityState,
            int newUlCallQualityState, CallQuality cq) {
        Integer ss = getLteSnr();
        if (ss.equals(CellInfo.UNAVAILABLE)) {
            return;
        }

        // downlink
        if (newDlCallQualityState == GOOD_QUALITY) {
            if (mWorstSsWithGoodDlQuality == null || ss < mWorstSsWithGoodDlQuality.second) {
                mWorstSsWithGoodDlQuality = Pair.create(cq, ss);
            }
            if (mBestSsWithGoodDlQuality == null || ss > mBestSsWithGoodDlQuality.second) {
                mBestSsWithGoodDlQuality = Pair.create(cq, ss);
            }
        } else {
            if (mWorstSsWithBadDlQuality == null || ss < mWorstSsWithBadDlQuality.second) {
                mWorstSsWithBadDlQuality = Pair.create(cq, ss);
            }
            if (mBestSsWithBadDlQuality == null || ss > mBestSsWithBadDlQuality.second) {
                mBestSsWithBadDlQuality = Pair.create(cq, ss);
            }
        }

        // uplink
        if (newUlCallQualityState == GOOD_QUALITY) {
            if (mWorstSsWithGoodUlQuality == null || ss < mWorstSsWithGoodUlQuality.second) {
                mWorstSsWithGoodUlQuality = Pair.create(cq, ss);
            }
            if (mBestSsWithGoodUlQuality == null || ss > mBestSsWithGoodUlQuality.second) {
                mBestSsWithGoodUlQuality = Pair.create(cq, ss);
            }
        } else {
            if (mWorstSsWithBadUlQuality == null || ss < mWorstSsWithBadUlQuality.second) {
                mWorstSsWithBadUlQuality = Pair.create(cq, ss);
            }
            if (mBestSsWithBadUlQuality == null || ss > mBestSsWithBadUlQuality.second) {
                mBestSsWithBadUlQuality = Pair.create(cq, ss);
            }
        }
    }

    // Returns the LTE signal to noise ratio, or 0 if unavailable
    private Integer getLteSnr() {
        ServiceStateTracker sst = mPhone.getDefaultPhone().getServiceStateTracker();
        if (sst == null) {
            Rlog.e(TAG, "getLteSnr: unable to get SST for phone " + mPhone.getPhoneId());
            return CellInfo.UNAVAILABLE;
        }

        SignalStrength ss = sst.getSignalStrength();
        if (ss == null) {
            Rlog.e(TAG, "getLteSnr: unable to get SignalStrength for phone " + mPhone.getPhoneId());
            return CellInfo.UNAVAILABLE;
        }

        // There may be multiple CellSignalStrengthLte, so try to use one with available SNR
        for (CellSignalStrengthLte lteSs : ss.getCellSignalStrengths(CellSignalStrengthLte.class)) {
            int snr = lteSs.getRssnr();
            if (snr != CellInfo.UNAVAILABLE) {
                return snr;
            }
        }

        return CellInfo.UNAVAILABLE;
    }

    private static TelephonyCallSession.Event.SignalStrength toProto(int ss) {
        TelephonyCallSession.Event.SignalStrength ret =
                new TelephonyCallSession.Event.SignalStrength();
        ret.lteSnr = ss;
        return ret;
    }

    /**
     * Return the full downlink CallQualitySummary using the saved CallQuality records.
     */
    public TelephonyCallSession.Event.CallQualitySummary getCallQualitySummaryDl() {
        TelephonyCallSession.Event.CallQualitySummary summary =
                new TelephonyCallSession.Event.CallQualitySummary();
        summary.totalGoodQualityDurationInSeconds = mTotalDlGoodQualityTimeMs / 1000;
        summary.totalBadQualityDurationInSeconds = mTotalDlBadQualityTimeMs / 1000;
        // This value could be different from mLastCallQuality.getCallDuration if we support
        // handover from IMS->CS->IMS, but this is currently not possible
        // TODO(b/130302396) this also may be possible when we put a call on hold and continue with
        // another call
        summary.totalDurationWithQualityInformationInSeconds =
                mLastCallQuality.getCallDuration() / 1000;
        if (mWorstSsWithGoodDlQuality != null) {
            summary.snapshotOfWorstSsWithGoodQuality =
                    toCallQualityProto(mWorstSsWithGoodDlQuality.first);
            summary.worstSsWithGoodQuality = toProto(mWorstSsWithGoodDlQuality.second);
        }
        if (mBestSsWithGoodDlQuality != null) {
            summary.snapshotOfBestSsWithGoodQuality =
                    toCallQualityProto(mBestSsWithGoodDlQuality.first);
            summary.bestSsWithGoodQuality = toProto(mBestSsWithGoodDlQuality.second);
        }
        if (mWorstSsWithBadDlQuality != null) {
            summary.snapshotOfWorstSsWithBadQuality =
                    toCallQualityProto(mWorstSsWithBadDlQuality.first);
            summary.worstSsWithBadQuality = toProto(mWorstSsWithBadDlQuality.second);
        }
        if (mBestSsWithBadDlQuality != null) {
            summary.snapshotOfBestSsWithBadQuality =
                    toCallQualityProto(mBestSsWithBadDlQuality.first);
            summary.bestSsWithBadQuality = toProto(mBestSsWithBadDlQuality.second);
        }
        summary.snapshotOfEnd = toCallQualityProto(mLastCallQuality);
        return summary;
    }

    /**
     * Return the full uplink CallQualitySummary using the saved CallQuality records.
     */
    public TelephonyCallSession.Event.CallQualitySummary getCallQualitySummaryUl() {
        TelephonyCallSession.Event.CallQualitySummary summary =
                new TelephonyCallSession.Event.CallQualitySummary();
        summary.totalGoodQualityDurationInSeconds = mTotalUlGoodQualityTimeMs / 1000;
        summary.totalBadQualityDurationInSeconds = mTotalUlBadQualityTimeMs / 1000;
        // This value could be different from mLastCallQuality.getCallDuration if we support
        // handover from IMS->CS->IMS, but this is currently not possible
        // TODO(b/130302396) this also may be possible when we put a call on hold and continue with
        // another call
        summary.totalDurationWithQualityInformationInSeconds =
                mLastCallQuality.getCallDuration() / 1000;
        if (mWorstSsWithGoodUlQuality != null) {
            summary.snapshotOfWorstSsWithGoodQuality =
                    toCallQualityProto(mWorstSsWithGoodUlQuality.first);
            summary.worstSsWithGoodQuality = toProto(mWorstSsWithGoodUlQuality.second);
        }
        if (mBestSsWithGoodUlQuality != null) {
            summary.snapshotOfBestSsWithGoodQuality =
                    toCallQualityProto(mBestSsWithGoodUlQuality.first);
            summary.bestSsWithGoodQuality = toProto(mBestSsWithGoodUlQuality.second);
        }
        if (mWorstSsWithBadUlQuality != null) {
            summary.snapshotOfWorstSsWithBadQuality =
                    toCallQualityProto(mWorstSsWithBadUlQuality.first);
            summary.worstSsWithBadQuality = toProto(mWorstSsWithBadUlQuality.second);
        }
        if (mBestSsWithBadUlQuality != null) {
            summary.snapshotOfBestSsWithBadQuality =
                    toCallQualityProto(mBestSsWithBadUlQuality.first);
            summary.bestSsWithBadQuality = toProto(mBestSsWithBadUlQuality.second);
        }
        summary.snapshotOfEnd = toCallQualityProto(mLastCallQuality);
        return summary;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[CallQualityMetrics phone ");
        sb.append(mPhone.getPhoneId());
        sb.append(" mUlSnapshots: {");
        for (Pair<CallQuality, Integer> snapshot : mUlSnapshots) {
            sb.append(" {cq=");
            sb.append(snapshot.first);
            sb.append(" ss=");
            sb.append(snapshot.second);
            sb.append("}");
        }
        sb.append("}");
        sb.append(" mDlSnapshots:{");
        for (Pair<CallQuality, Integer> snapshot : mDlSnapshots) {
            sb.append(" {cq=");
            sb.append(snapshot.first);
            sb.append(" ss=");
            sb.append(snapshot.second);
            sb.append("}");
        }
        sb.append("}");
        sb.append(" ");
        sb.append(" mTotalDlGoodQualityTimeMs: ");
        sb.append(mTotalDlGoodQualityTimeMs);
        sb.append(" mTotalDlBadQualityTimeMs: ");
        sb.append(mTotalDlBadQualityTimeMs);
        sb.append(" mTotalUlGoodQualityTimeMs: ");
        sb.append(mTotalUlGoodQualityTimeMs);
        sb.append(" mTotalUlBadQualityTimeMs: ");
        sb.append(mTotalUlBadQualityTimeMs);
        sb.append("]");
        return sb.toString();
    }
}

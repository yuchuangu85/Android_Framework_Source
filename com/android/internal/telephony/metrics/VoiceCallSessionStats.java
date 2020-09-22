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

package com.android.internal.telephony.metrics;

import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_CS;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_IMS;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_UNKNOWN;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__DIRECTION__CALL_DIRECTION_MO;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__DIRECTION__CALL_DIRECTION_MT;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_EXTREMELY_FAST;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_EXTREMELY_SLOW;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_FAST;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_NORMAL;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_SLOW;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_ULTRA_FAST;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_ULTRA_SLOW;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_UNKNOWN;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_VERY_FAST;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_VERY_SLOW;

import android.annotation.Nullable;
import android.os.SystemClock;
import android.telephony.Annotation.NetworkType;
import android.telephony.DisconnectCause;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.GsmCdmaConnection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import com.android.internal.telephony.nano.PersistAtomsProto.VoiceCallSession;
import com.android.internal.telephony.nano.TelephonyProto.TelephonyCallSession.Event.AudioCodec;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccSlot;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Collects voice call events per phone ID for the pulled atom. */
public class VoiceCallSessionStats {
    private static final String TAG = VoiceCallSessionStats.class.getSimpleName();

    /** Bitmask value of unknown audio codecs. */
    private static final long AUDIO_CODEC_UNKNOWN = 1L << AudioCodec.AUDIO_CODEC_UNKNOWN;

    /**
     * Value denoting the carrier ID being unknown.
     *
     * <p>NOTE: 0 is unused in {@code carrier_list.textpb} (it starts from 1).
     */
    private static final int CARRIER_ID_UNKNOWN = 0;

    /** Holds the audio codec bitmask value for CS calls. */
    private static final SparseLongArray CS_CODEC_MAP = buildGsmCdmaCodecMap();

    /** Holds the audio codec bitmask value for IMS calls. */
    private static final SparseLongArray IMS_CODEC_MAP = buildImsCodecMap();

    /** Holds setup duration buckets with keys as their lower bounds in milliseconds. */
    private static final SparseIntArray CALL_SETUP_DURATION_MAP = buildCallSetupDurationMap();

    /**
     * Tracks statistics for each call connection, indexed with ID returned by {@link
     * #getConnectionId}.
     */
    private final SparseArray<VoiceCallSession> mCallProtos = new SparseArray<>();

    /**
     * Tracks call RAT usage.
     *
     * <p>RAT usage is mainly tied to phones rather than calls, since each phone can have multiple
     * concurrent calls, and we do not want to count the RAT duration multiple times.
     */
    private final VoiceCallRatTracker mRatUsage = new VoiceCallRatTracker();

    private final int mPhoneId;
    private final Phone mPhone;
    private int mCarrierId = CARRIER_ID_UNKNOWN;

    private final PersistAtomsStorage mAtomsStorage =
            PhoneFactory.getMetricsCollector().getAtomsStorage();
    private final UiccController mUiccController = UiccController.getInstance();

    public VoiceCallSessionStats(int phoneId, Phone phone) {
        mPhoneId = phoneId;
        mPhone = phone;
    }

    /* CS calls */

    /** Updates internal states when previous CS calls are accepted to track MT call setup time. */
    public synchronized void onRilAcceptCall(List<Connection> connections) {
        for (Connection conn : connections) {
            addCall(conn);
        }
    }

    /** Updates internal states when a CS MO call is created. */
    public synchronized void onRilDial(Connection conn) {
        addCall(conn);
    }

    /**
     * Updates internal states when CS calls are created or terminated, or CS call state is changed.
     */
    public synchronized void onRilCallListChanged(List<GsmCdmaConnection> connections) {
        for (Connection conn : connections) {
            int id = getConnectionId(conn);
            if (!mCallProtos.contains(id)) {
                // handle new connections
                if (conn.getDisconnectCause() == DisconnectCause.NOT_DISCONNECTED) {
                    addCall(conn);
                    checkCallSetup(conn, mCallProtos.get(id));
                } else {
                    logd("onRilCallListChanged: skip adding disconnected connection");
                }
            } else {
                VoiceCallSession proto = mCallProtos.get(id);
                // handle call state change
                checkCallSetup(conn, proto);
                // handle terminated connections
                if (conn.getDisconnectCause() != DisconnectCause.NOT_DISCONNECTED) {
                    proto.bearerAtEnd = getBearer(conn); // should be CS
                    proto.disconnectReasonCode = conn.getDisconnectCause();
                    proto.disconnectExtraCode = conn.getPreciseDisconnectCause();
                    proto.disconnectExtraMessage = conn.getVendorDisconnectCause();
                    finishCall(id);
                }
            }
        }
        // NOTE: we cannot check stray connections (CS call in our list but not in RIL), as
        // GsmCdmaCallTracker can call this with a partial list
    }

    /* IMS calls */

    /** Updates internal states when an IMS MO call is created. */
    public synchronized void onImsDial(ImsPhoneConnection conn) {
        addCall(conn);
        if (conn.hasRttTextStream()) {
            setRttStarted(conn);
        }
    }

    /** Updates internal states when an IMS MT call is created. */
    public synchronized void onImsCallReceived(ImsPhoneConnection conn) {
        addCall(conn);
        if (conn.hasRttTextStream()) {
            setRttStarted(conn);
        }
    }

    /** Updates internal states when previous IMS calls are accepted to track MT call setup time. */
    public synchronized void onImsAcceptCall(List<Connection> connections) {
        for (Connection conn : connections) {
            addCall(conn);
        }
    }

    /** Updates internal states when an IMS call is terminated. */
    public synchronized void onImsCallTerminated(
            @Nullable ImsPhoneConnection conn, ImsReasonInfo reasonInfo) {
        if (conn == null) {
            List<Integer> imsConnIds = getImsConnectionIds();
            if (imsConnIds.size() == 1) {
                loge("onImsCallTerminated: ending IMS call w/ conn=null");
                finishImsCall(imsConnIds.get(0), reasonInfo);
            } else {
                loge("onImsCallTerminated: %d IMS calls w/ conn=null", imsConnIds.size());
            }
        } else {
            int id = getConnectionId(conn);
            if (mCallProtos.contains(id)) {
                finishImsCall(id, reasonInfo);
            } else {
                loge("onImsCallTerminated: untracked connection");
                // fake a call so at least some info can be tracked
                addCall(conn);
                finishImsCall(id, reasonInfo);
            }
        }
    }

    /** Updates internal states when RTT is started on an IMS call. */
    public synchronized void onRttStarted(ImsPhoneConnection conn) {
        setRttStarted(conn);
    }

    /* general & misc. */

    /** Updates internal states when carrier changes. */
    public synchronized void onActiveSubscriptionInfoChanged(List<SubscriptionInfo> subInfos) {
        int slotId = getSimSlotId();
        if (subInfos != null) {
            for (SubscriptionInfo subInfo : subInfos) {
                if (subInfo.getSimSlotIndex() == slotId) {
                    mCarrierId = subInfo.getCarrierId();
                }
            }
        }
    }

    /** Updates internal states when audio codec for a call is changed. */
    public synchronized void onAudioCodecChanged(Connection conn, int audioQuality) {
        VoiceCallSession proto = mCallProtos.get(getConnectionId(conn));
        if (proto == null) {
            loge("onAudioCodecChanged: untracked connection");
            return;
        }
        proto.codecBitmask |= audioQualityToCodecBitmask(proto.bearerAtEnd, audioQuality);
    }

    /**
     * Updates internal states when a call changes state to track setup time and status.
     *
     * <p>This is currently mainly used by IMS since CS call states are updated through {@link
     * #onRilCallListChanged}.
     */
    public synchronized void onCallStateChanged(Call call) {
        for (Connection conn : call.getConnections()) {
            VoiceCallSession proto = mCallProtos.get(getConnectionId(conn));
            if (proto != null) {
                checkCallSetup(conn, proto);
            } else {
                loge("onCallStateChanged: untracked connection");
            }
        }
    }

    /** Updates internal states when an IMS call is handover to a CS call. */
    public synchronized void onRilSrvccStateChanged(int state) {
        List<Connection> handoverConnections = null;
        if (mPhone.getImsPhone() != null) {
            loge("onRilSrvccStateChanged: ImsPhone is null");
        } else {
            handoverConnections = mPhone.getImsPhone().getHandoverConnection();
        }
        List<Integer> imsConnIds;
        if (handoverConnections == null) {
            imsConnIds = getImsConnectionIds();
            loge("onRilSrvccStateChanged: ImsPhone has no handover, we have %d", imsConnIds.size());
        } else {
            imsConnIds =
                    handoverConnections.stream()
                            .map(VoiceCallSessionStats::getConnectionId)
                            .collect(Collectors.toList());
        }
        switch (state) {
            case TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED:
                // connection will now be CS
                for (int id : imsConnIds) {
                    VoiceCallSession proto = mCallProtos.get(id);
                    proto.srvccCompleted = true;
                    proto.bearerAtEnd = VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_CS;
                }
                break;
            case TelephonyManager.SRVCC_STATE_HANDOVER_FAILED:
                for (int id : imsConnIds) {
                    mCallProtos.get(id).srvccFailureCount++;
                }
                break;
            case TelephonyManager.SRVCC_STATE_HANDOVER_CANCELED:
                for (int id : imsConnIds) {
                    mCallProtos.get(id).srvccCancellationCount++;
                }
                break;
            default: // including STARTED and NONE, do nothing
        }
    }

    /** Updates internal states when RAT changes. */
    public synchronized void onServiceStateChanged(ServiceState state) {
        if (hasCalls()) {
            updateRatTracker(state);
        }
    }

    /* internal */

    /**
     * Adds a call connection.
     *
     * <p>Should be called when the call is created, and when setup begins (upon {@code
     * RilRequest.RIL_REQUEST_ANSWER} or {@code ImsCommand.IMS_CMD_ACCEPT}).
     */
    private void addCall(Connection conn) {
        int id = getConnectionId(conn);
        if (mCallProtos.contains(id)) {
            // mostly handles ringing MT call getting accepted (MT call setup begins)
            logd("addCall: resetting setup info");
            VoiceCallSession proto = mCallProtos.get(id);
            proto.setupBeginMillis = getTimeMillis();
            proto.setupDuration = VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_UNKNOWN;
        } else {
            int bearer = getBearer(conn);
            ServiceState serviceState = getServiceState();
            int rat = getRat(serviceState);

            VoiceCallSession proto = new VoiceCallSession();

            proto.bearerAtStart = bearer;
            proto.bearerAtEnd = bearer;
            proto.direction = getDirection(conn);
            proto.setupDuration = VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_UNKNOWN;
            proto.setupFailed = true;
            proto.disconnectReasonCode = conn.getDisconnectCause();
            proto.disconnectExtraCode = conn.getPreciseDisconnectCause();
            proto.disconnectExtraMessage = conn.getVendorDisconnectCause();
            proto.ratAtStart = rat;
            proto.ratAtEnd = rat;
            proto.ratSwitchCount = 0L;
            proto.codecBitmask = 0L;
            proto.simSlotIndex = getSimSlotId();
            proto.isMultiSim = SimSlotState.getCurrentState().numActiveSims > 1;
            proto.isEsim = isEsim();
            proto.carrierId = mCarrierId;
            proto.srvccCompleted = false;
            proto.srvccFailureCount = 0L;
            proto.srvccCancellationCount = 0L;
            proto.rttEnabled = false;
            proto.isEmergency = conn.isEmergencyCall();
            proto.isRoaming = serviceState != null ? serviceState.getVoiceRoaming() : false;

            // internal fields for tracking
            proto.setupBeginMillis = getTimeMillis();

            proto.concurrentCallCountAtStart = mCallProtos.size();
            mCallProtos.put(id, proto);

            // RAT call count needs to be updated
            updateRatTracker(serviceState);
        }
    }

    /** Sends the call metrics to persist storage when it is finished. */
    private void finishCall(int connectionId) {
        VoiceCallSession proto = mCallProtos.get(connectionId);
        if (proto == null) {
            loge("finishCall: could not find call to be removed");
            return;
        }
        mCallProtos.delete(connectionId);
        proto.concurrentCallCountAtEnd = mCallProtos.size();

        // ensure internal fields are cleared
        proto.setupBeginMillis = 0L;

        // sanitize for javanano & StatsEvent
        if (proto.disconnectExtraMessage == null) {
            proto.disconnectExtraMessage = "";
        }

        mAtomsStorage.addVoiceCallSession(proto);

        // merge RAT usages to PersistPullers when the call session ends (i.e. no more active calls)
        if (!hasCalls()) {
            mRatUsage.conclude(getTimeMillis());
            mAtomsStorage.addVoiceCallRatUsage(mRatUsage);
            mRatUsage.clear();
        }
    }

    private void setRttStarted(ImsPhoneConnection conn) {
        VoiceCallSession proto = mCallProtos.get(getConnectionId(conn));
        if (proto == null) {
            loge("onRttStarted: untracked connection");
            return;
        }
        // should be IMS w/o SRVCC
        if (proto.bearerAtStart != getBearer(conn) || proto.bearerAtEnd != getBearer(conn)) {
            loge("onRttStarted: connection bearer mismatch but proceeding");
        }
        proto.rttEnabled = true;
    }

    /** Returns a {@link Set} of Connection IDs so RAT usage can be correctly tracked. */
    private Set<Integer> getConnectionIds() {
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < mCallProtos.size(); i++) {
            ids.add(mCallProtos.keyAt(i));
        }
        return ids;
    }

    private List<Integer> getImsConnectionIds() {
        List<Integer> imsConnIds = new ArrayList<>(mCallProtos.size());
        for (int i = 0; i < mCallProtos.size(); i++) {
            if (mCallProtos.valueAt(i).bearerAtEnd
                    == VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_IMS) {
                imsConnIds.add(mCallProtos.keyAt(i));
            }
        }
        return imsConnIds;
    }

    private boolean hasCalls() {
        return mCallProtos.size() > 0;
    }

    private void checkCallSetup(Connection conn, VoiceCallSession proto) {
        if (proto.setupBeginMillis != 0L && isSetupFinished(conn.getCall())) {
            proto.setupDuration = classifySetupDuration(getTimeMillis() - proto.setupBeginMillis);
            proto.setupBeginMillis = 0L;
        }
        // clear setupFailed if call now active, but otherwise leave it unchanged
        if (conn.getState() == Call.State.ACTIVE) {
            proto.setupFailed = false;
        }
    }

    private void updateRatTracker(ServiceState state) {
        int rat = getRat(state);
        mRatUsage.add(mCarrierId, rat, getTimeMillis(), getConnectionIds());
        for (int i = 0; i < mCallProtos.size(); i++) {
            VoiceCallSession proto = mCallProtos.valueAt(i);
            if (proto.ratAtEnd != rat) {
                proto.ratSwitchCount++;
                proto.ratAtEnd = rat;
            }
            // assuming that SIM carrier ID does not change during the call
        }
    }

    private void finishImsCall(int id, ImsReasonInfo reasonInfo) {
        VoiceCallSession proto = mCallProtos.get(id);
        proto.bearerAtEnd = VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_IMS;
        proto.disconnectReasonCode = reasonInfo.mCode;
        proto.disconnectExtraCode = reasonInfo.mExtraCode;
        proto.disconnectExtraMessage = reasonInfo.mExtraMessage;
        finishCall(id);
    }

    private boolean isEsim() {
        int slotId = getSimSlotId();
        UiccSlot slot = mUiccController.getUiccSlot(slotId);
        if (slot != null) {
            return slot.isEuicc();
        } else {
            // should not happen, but assume we are not using eSIM
            loge("isEsim: slot %d is null", slotId);
            return false;
        }
    }

    private int getSimSlotId() {
        // NOTE: UiccController's mapping hasn't be initialized when Phone was created
        return mUiccController.getSlotIdFromPhoneId(mPhoneId);
    }

    private @Nullable ServiceState getServiceState() {
        ServiceStateTracker tracker = mPhone.getServiceStateTracker();
        return tracker != null ? tracker.getServiceState() : null;
    }

    private static int getDirection(Connection conn) {
        return conn.isIncoming()
                ? VOICE_CALL_SESSION__DIRECTION__CALL_DIRECTION_MT
                : VOICE_CALL_SESSION__DIRECTION__CALL_DIRECTION_MO;
    }

    private static int getBearer(Connection conn) {
        int phoneType = conn.getPhoneType();
        switch (phoneType) {
            case PhoneConstants.PHONE_TYPE_GSM:
            case PhoneConstants.PHONE_TYPE_CDMA:
                return VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_CS;
            case PhoneConstants.PHONE_TYPE_IMS:
                return VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_IMS;
            default:
                loge("getBearer: unknown phoneType=%d", phoneType);
                return VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_UNKNOWN;
        }
    }

    private @NetworkType int getRat(@Nullable ServiceState state) {
        if (state == null) {
            return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
        boolean isWifiCall =
                mPhone.getImsPhone() != null
                && mPhone.getImsPhone().isWifiCallingEnabled()
                && state.getDataNetworkType() == TelephonyManager.NETWORK_TYPE_IWLAN;
        return isWifiCall ? TelephonyManager.NETWORK_TYPE_IWLAN : state.getVoiceNetworkType();
    }

    // NOTE: when setup is finished for MO calls, it is not successful yet.
    private static boolean isSetupFinished(@Nullable Call call) {
        if (call != null) {
            switch (call.getState()) {
                case ACTIVE: // MT setup: accepted to ACTIVE
                case ALERTING: // MO setup: dial to ALERTING
                    return true;
                default: // do nothing
            }
        }
        return false;
    }

    private static long audioQualityToCodecBitmask(int bearer, int audioQuality) {
        switch (bearer) {
            case VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_CS:
                return CS_CODEC_MAP.get(audioQuality, AUDIO_CODEC_UNKNOWN);
            case VOICE_CALL_SESSION__BEARER_AT_END__CALL_BEARER_IMS:
                return IMS_CODEC_MAP.get(audioQuality, AUDIO_CODEC_UNKNOWN);
            default:
                loge("audioQualityToCodecBitmask: unknown bearer %d", bearer);
                return AUDIO_CODEC_UNKNOWN;
        }
    }

    private static int classifySetupDuration(long durationMillis) {
        for (int i = 0; i < CALL_SETUP_DURATION_MAP.size(); i++) {
            if (durationMillis < CALL_SETUP_DURATION_MAP.keyAt(i)) {
                return CALL_SETUP_DURATION_MAP.valueAt(i);
            }
        }
        return VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_EXTREMELY_SLOW;
    }

    /**
     * Generates an ID for each connection, which should be the same for IMS and CS connections
     * involved in the same SRVCC.
     *
     * <p>Among the fields copied from ImsPhoneConnection to GsmCdmaConnection during SRVCC, the
     * Connection's create time seems to be the best choice for ID (assuming no multiple calls in a
     * millisecond). The 64-bit time is truncated to 32-bit so it can be used as an index in various
     * data structures, which is good for calls shorter than 49 days.
     */
    private static int getConnectionId(Connection conn) {
        return conn == null ? 0 : (int) conn.getCreateTime();
    }

    @VisibleForTesting
    protected long getTimeMillis() {
        return SystemClock.elapsedRealtime();
    }

    private static void logd(String format, Object... args) {
        Rlog.d(TAG, String.format(format, args));
    }

    private static void loge(String format, Object... args) {
        Rlog.e(TAG, String.format(format, args));
    }

    private static SparseLongArray buildGsmCdmaCodecMap() {
        SparseLongArray map = new SparseLongArray();

        map.put(DriverCall.AUDIO_QUALITY_AMR, 1L << AudioCodec.AUDIO_CODEC_AMR);
        map.put(DriverCall.AUDIO_QUALITY_AMR_WB, 1L << AudioCodec.AUDIO_CODEC_AMR_WB);
        map.put(DriverCall.AUDIO_QUALITY_GSM_EFR, 1L << AudioCodec.AUDIO_CODEC_GSM_EFR);
        map.put(DriverCall.AUDIO_QUALITY_GSM_FR, 1L << AudioCodec.AUDIO_CODEC_GSM_FR);
        map.put(DriverCall.AUDIO_QUALITY_GSM_HR, 1L << AudioCodec.AUDIO_CODEC_GSM_HR);
        map.put(DriverCall.AUDIO_QUALITY_EVRC, 1L << AudioCodec.AUDIO_CODEC_EVRC);
        map.put(DriverCall.AUDIO_QUALITY_EVRC_B, 1L << AudioCodec.AUDIO_CODEC_EVRC_B);
        map.put(DriverCall.AUDIO_QUALITY_EVRC_WB, 1L << AudioCodec.AUDIO_CODEC_EVRC_WB);
        map.put(DriverCall.AUDIO_QUALITY_EVRC_NW, 1L << AudioCodec.AUDIO_CODEC_EVRC_NW);

        return map;
    }

    private static SparseLongArray buildImsCodecMap() {
        SparseLongArray map = new SparseLongArray();

        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_AMR, 1L << AudioCodec.AUDIO_CODEC_AMR);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB, 1L << AudioCodec.AUDIO_CODEC_AMR_WB);
        map.put(
                ImsStreamMediaProfile.AUDIO_QUALITY_QCELP13K,
                1L << AudioCodec.AUDIO_CODEC_QCELP13K);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_EVRC, 1L << AudioCodec.AUDIO_CODEC_EVRC);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_EVRC_B, 1L << AudioCodec.AUDIO_CODEC_EVRC_B);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_EVRC_WB, 1L << AudioCodec.AUDIO_CODEC_EVRC_WB);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_EVRC_NW, 1L << AudioCodec.AUDIO_CODEC_EVRC_NW);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_GSM_EFR, 1L << AudioCodec.AUDIO_CODEC_GSM_EFR);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_GSM_FR, 1L << AudioCodec.AUDIO_CODEC_GSM_FR);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_GSM_HR, 1L << AudioCodec.AUDIO_CODEC_GSM_HR);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_G711U, 1L << AudioCodec.AUDIO_CODEC_G711U);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_G723, 1L << AudioCodec.AUDIO_CODEC_G723);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_G711A, 1L << AudioCodec.AUDIO_CODEC_G711A);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_G722, 1L << AudioCodec.AUDIO_CODEC_G722);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_G711AB, 1L << AudioCodec.AUDIO_CODEC_G711AB);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_G729, 1L << AudioCodec.AUDIO_CODEC_G729);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_EVS_NB, 1L << AudioCodec.AUDIO_CODEC_EVS_NB);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_EVS_WB, 1L << AudioCodec.AUDIO_CODEC_EVS_WB);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_EVS_SWB, 1L << AudioCodec.AUDIO_CODEC_EVS_SWB);
        map.put(ImsStreamMediaProfile.AUDIO_QUALITY_EVS_FB, 1L << AudioCodec.AUDIO_CODEC_EVS_FB);

        return map;
    }

    private static SparseIntArray buildCallSetupDurationMap() {
        SparseIntArray map = new SparseIntArray();

        map.put(0, VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_UNKNOWN);
        map.put(60, VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_EXTREMELY_FAST);
        map.put(100, VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_ULTRA_FAST);
        map.put(300, VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_VERY_FAST);
        map.put(600, VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_FAST);
        map.put(1000, VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_NORMAL);
        map.put(3000, VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_SLOW);
        map.put(6000, VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_VERY_SLOW);
        map.put(10000, VOICE_CALL_SESSION__SETUP_DURATION__CALL_SETUP_DURATION_ULTRA_SLOW);
        // anything above would be CALL_SETUP_DURATION_EXTREMELY_SLOW

        return map;
    }
}

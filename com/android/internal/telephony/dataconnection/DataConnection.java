/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.dataconnection;

import static android.net.NetworkPolicyManager.OVERRIDE_CONGESTED;
import static android.net.NetworkPolicyManager.OVERRIDE_UNMETERED;

import android.app.PendingIntent;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.PacketKeepalive;
import android.net.KeepalivePacketData;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StringNetworkSpecifier;
import android.os.AsyncResult;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.telephony.AccessNetworkConstants;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.data.DataCallResponse;
import android.telephony.data.DataProfile;
import android.telephony.data.DataService;
import android.telephony.data.DataServiceCallback;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Pair;
import android.util.SparseArray;
import android.util.StatsLog;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CarrierSignalAgent;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.LinkCapacityEstimate;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.RetryManager;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@hide}
 *
 * DataConnection StateMachine.
 *
 * This a class for representing a single data connection, with instances of this
 * class representing a connection via the cellular network. There may be multiple
 * data connections and all of them are managed by the <code>DataConnectionTracker</code>.
 *
 * NOTE: All DataConnection objects must be running on the same looper, which is the default
 * as the coordinator has members which are used without synchronization.
 */
public class DataConnection extends StateMachine {
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    private static final String NETWORK_TYPE = "MOBILE";

    // The data connection controller
    private DcController mDcController;

    // The Tester for failing all bringup's
    private DcTesterFailBringUpAll mDcTesterFailBringUpAll;

    private static AtomicInteger mInstanceNumber = new AtomicInteger(0);
    private AsyncChannel mAc;

    // The DCT that's talking to us, we only support one!
    private DcTracker mDct = null;

    protected String[] mPcscfAddr;

    /**
     * Used internally for saving connecting parameters.
     */
    public static class ConnectionParams {
        int mTag;
        ApnContext mApnContext;
        int mProfileId;
        int mRilRat;
        final boolean mUnmeteredUseOnly;
        Message mOnCompletedMsg;
        final int mConnectionGeneration;

        ConnectionParams(ApnContext apnContext, int profileId, int rilRadioTechnology,
                         boolean unmeteredUseOnly,  Message onCompletedMsg,
                         int connectionGeneration) {
            mApnContext = apnContext;
            mProfileId = profileId;
            mRilRat = rilRadioTechnology;
            mUnmeteredUseOnly = unmeteredUseOnly;
            mOnCompletedMsg = onCompletedMsg;
            mConnectionGeneration = connectionGeneration;
        }

        @Override
        public String toString() {
            return "{mTag=" + mTag + " mApnContext=" + mApnContext
                    + " mProfileId=" + mProfileId
                    + " mRat=" + mRilRat
                    + " mUnmeteredUseOnly=" + mUnmeteredUseOnly
                    + " mOnCompletedMsg=" + msgToString(mOnCompletedMsg) + "}";
        }
    }

    /**
     * Used internally for saving disconnecting parameters.
     */
    public static class DisconnectParams {
        int mTag;
        public ApnContext mApnContext;
        String mReason;
        Message mOnCompletedMsg;

        DisconnectParams(ApnContext apnContext, String reason, Message onCompletedMsg) {
            mApnContext = apnContext;
            mReason = reason;
            mOnCompletedMsg = onCompletedMsg;
        }

        @Override
        public String toString() {
            return "{mTag=" + mTag + " mApnContext=" + mApnContext
                    + " mReason=" + mReason
                    + " mOnCompletedMsg=" + msgToString(mOnCompletedMsg) + "}";
        }
    }

    private ApnSetting mApnSetting;
    private ConnectionParams mConnectionParams;
    private DisconnectParams mDisconnectParams;
    private DcFailCause mDcFailCause;

    private Phone mPhone;
    private DataServiceManager mDataServiceManager;
    private LinkProperties mLinkProperties = new LinkProperties();
    private long mCreateTime;
    private long mLastFailTime;
    private DcFailCause mLastFailCause;
    private static final String NULL_IP = "0.0.0.0";
    private Object mUserData;
    private int mSubscriptionOverride;
    private int mRilRat = Integer.MAX_VALUE;
    private int mDataRegState = Integer.MAX_VALUE;
    private NetworkInfo mNetworkInfo;
    private DcNetworkAgent mNetworkAgent;
    private LocalLog mNetCapsLocalLog = new LocalLog(50);

    int mTag;
    public int mCid;
    public HashMap<ApnContext, ConnectionParams> mApnContexts = null;
    PendingIntent mReconnectIntent = null;


    // ***** Event codes for driving the state machine, package visible for Dcc
    static final int BASE = Protocol.BASE_DATA_CONNECTION;
    static final int EVENT_CONNECT = BASE + 0;
    static final int EVENT_SETUP_DATA_CONNECTION_DONE = BASE + 1;
    static final int EVENT_DEACTIVATE_DONE = BASE + 3;
    static final int EVENT_DISCONNECT = BASE + 4;
    static final int EVENT_RIL_CONNECTED = BASE + 5;
    static final int EVENT_DISCONNECT_ALL = BASE + 6;
    static final int EVENT_DATA_STATE_CHANGED = BASE + 7;
    static final int EVENT_TEAR_DOWN_NOW = BASE + 8;
    static final int EVENT_LOST_CONNECTION = BASE + 9;
    static final int EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED = BASE + 11;
    static final int EVENT_DATA_CONNECTION_ROAM_ON = BASE + 12;
    static final int EVENT_DATA_CONNECTION_ROAM_OFF = BASE + 13;
    static final int EVENT_BW_REFRESH_RESPONSE = BASE + 14;
    static final int EVENT_DATA_CONNECTION_VOICE_CALL_STARTED = BASE + 15;
    static final int EVENT_DATA_CONNECTION_VOICE_CALL_ENDED = BASE + 16;
    static final int EVENT_DATA_CONNECTION_OVERRIDE_CHANGED = BASE + 17;
    static final int EVENT_KEEPALIVE_STATUS = BASE + 18;
    static final int EVENT_KEEPALIVE_STARTED = BASE + 19;
    static final int EVENT_KEEPALIVE_STOPPED = BASE + 20;
    static final int EVENT_KEEPALIVE_START_REQUEST = BASE + 21;
    static final int EVENT_KEEPALIVE_STOP_REQUEST = BASE + 22;
    static final int EVENT_LINK_CAPACITY_CHANGED = BASE + 23;

    private static final int CMD_TO_STRING_COUNT =
            EVENT_LINK_CAPACITY_CHANGED - BASE + 1;

    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[EVENT_CONNECT - BASE] = "EVENT_CONNECT";
        sCmdToString[EVENT_SETUP_DATA_CONNECTION_DONE - BASE] =
                "EVENT_SETUP_DATA_CONNECTION_DONE";
        sCmdToString[EVENT_DEACTIVATE_DONE - BASE] = "EVENT_DEACTIVATE_DONE";
        sCmdToString[EVENT_DISCONNECT - BASE] = "EVENT_DISCONNECT";
        sCmdToString[EVENT_RIL_CONNECTED - BASE] = "EVENT_RIL_CONNECTED";
        sCmdToString[EVENT_DISCONNECT_ALL - BASE] = "EVENT_DISCONNECT_ALL";
        sCmdToString[EVENT_DATA_STATE_CHANGED - BASE] = "EVENT_DATA_STATE_CHANGED";
        sCmdToString[EVENT_TEAR_DOWN_NOW - BASE] = "EVENT_TEAR_DOWN_NOW";
        sCmdToString[EVENT_LOST_CONNECTION - BASE] = "EVENT_LOST_CONNECTION";
        sCmdToString[EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED - BASE] =
                "EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED";
        sCmdToString[EVENT_DATA_CONNECTION_ROAM_ON - BASE] = "EVENT_DATA_CONNECTION_ROAM_ON";
        sCmdToString[EVENT_DATA_CONNECTION_ROAM_OFF - BASE] = "EVENT_DATA_CONNECTION_ROAM_OFF";
        sCmdToString[EVENT_BW_REFRESH_RESPONSE - BASE] = "EVENT_BW_REFRESH_RESPONSE";
        sCmdToString[EVENT_DATA_CONNECTION_VOICE_CALL_STARTED - BASE] =
                "EVENT_DATA_CONNECTION_VOICE_CALL_STARTED";
        sCmdToString[EVENT_DATA_CONNECTION_VOICE_CALL_ENDED - BASE] =
                "EVENT_DATA_CONNECTION_VOICE_CALL_ENDED";
        sCmdToString[EVENT_DATA_CONNECTION_OVERRIDE_CHANGED - BASE] =
                "EVENT_DATA_CONNECTION_OVERRIDE_CHANGED";
        sCmdToString[EVENT_KEEPALIVE_STATUS - BASE] = "EVENT_KEEPALIVE_STATUS";
        sCmdToString[EVENT_KEEPALIVE_STARTED - BASE] = "EVENT_KEEPALIVE_STARTED";
        sCmdToString[EVENT_KEEPALIVE_STOPPED - BASE] = "EVENT_KEEPALIVE_STOPPED";
        sCmdToString[EVENT_KEEPALIVE_START_REQUEST - BASE] = "EVENT_KEEPALIVE_START_REQUEST";
        sCmdToString[EVENT_KEEPALIVE_STOP_REQUEST - BASE] = "EVENT_KEEPALIVE_STOP_REQUEST";
        sCmdToString[EVENT_LINK_CAPACITY_CHANGED - BASE] = "EVENT_LINK_CAPACITY_CHANGED";
    }
    // Convert cmd to string or null if unknown
    static String cmdToString(int cmd) {
        String value;
        cmd -= BASE;
        if ((cmd >= 0) && (cmd < sCmdToString.length)) {
            value = sCmdToString[cmd];
        } else {
            value = DcAsyncChannel.cmdToString(cmd + BASE);
        }
        if (value == null) {
            value = "0x" + Integer.toHexString(cmd + BASE);
        }
        return value;
    }

    /**
     * Create the connection object
     *
     * @param phone the Phone
     * @param id the connection id
     * @return DataConnection that was created.
     */
    public static DataConnection makeDataConnection(Phone phone, int id, DcTracker dct,
                                                    DataServiceManager dataServiceManager,
                                                    DcTesterFailBringUpAll failBringUpAll,
                                                    DcController dcc) {
        DataConnection dc = new DataConnection(phone,
                "DC-" + mInstanceNumber.incrementAndGet(), id, dct, dataServiceManager,
                failBringUpAll, dcc);
        dc.start();
        if (DBG) dc.log("Made " + dc.getName());
        return dc;
    }

    void dispose() {
        log("dispose: call quiteNow()");
        quitNow();
    }

    /* Getter functions */

    LinkProperties getCopyLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    boolean isInactive() {
        return getCurrentState() == mInactiveState;
    }

    boolean isDisconnecting() {
        return getCurrentState() == mDisconnectingState;
    }

    boolean isActive() {
        return getCurrentState() == mActiveState;
    }

    boolean isActivating() {
        return getCurrentState() == mActivatingState;
    }

    int getCid() {
        return mCid;
    }

    ApnSetting getApnSetting() {
        return mApnSetting;
    }

    void setLinkPropertiesHttpProxy(ProxyInfo proxy) {
        mLinkProperties.setHttpProxy(proxy);
    }

    public static class UpdateLinkPropertyResult {
        public SetupResult setupResult = SetupResult.SUCCESS;
        public LinkProperties oldLp;
        public LinkProperties newLp;
        public UpdateLinkPropertyResult(LinkProperties curLp) {
            oldLp = curLp;
            newLp = curLp;
        }
    }

    /**
     * Class returned by onSetupConnectionCompleted.
     */
    public enum SetupResult {
        SUCCESS,
        ERROR_RADIO_NOT_AVAILABLE,
        ERROR_INVALID_ARG,
        ERROR_STALE,
        ERROR_DATA_SERVICE_SPECIFIC_ERROR;

        public DcFailCause mFailCause;

        SetupResult() {
            mFailCause = DcFailCause.fromInt(0);
        }

        @Override
        public String toString() {
            return name() + "  SetupResult.mFailCause=" + mFailCause;
        }
    }

    public boolean isIpv4Connected() {
        boolean ret = false;
        Collection <InetAddress> addresses = mLinkProperties.getAddresses();

        for (InetAddress addr: addresses) {
            if (addr instanceof java.net.Inet4Address) {
                java.net.Inet4Address i4addr = (java.net.Inet4Address) addr;
                if (!i4addr.isAnyLocalAddress() && !i4addr.isLinkLocalAddress() &&
                        !i4addr.isLoopbackAddress() && !i4addr.isMulticastAddress()) {
                    ret = true;
                    break;
                }
            }
        }
        return ret;
    }

    public boolean isIpv6Connected() {
        boolean ret = false;
        Collection <InetAddress> addresses = mLinkProperties.getAddresses();

        for (InetAddress addr: addresses) {
            if (addr instanceof java.net.Inet6Address) {
                java.net.Inet6Address i6addr = (java.net.Inet6Address) addr;
                if (!i6addr.isAnyLocalAddress() && !i6addr.isLinkLocalAddress() &&
                        !i6addr.isLoopbackAddress() && !i6addr.isMulticastAddress()) {
                    ret = true;
                    break;
                }
            }
        }
        return ret;
    }

    @VisibleForTesting
    public UpdateLinkPropertyResult updateLinkProperty(DataCallResponse newState) {
        UpdateLinkPropertyResult result = new UpdateLinkPropertyResult(mLinkProperties);

        if (newState == null) return result;

        result.newLp = new LinkProperties();

        // set link properties based on data call response
        result.setupResult = setLinkProperties(newState, result.newLp);
        if (result.setupResult != SetupResult.SUCCESS) {
            if (DBG) log("updateLinkProperty failed : " + result.setupResult);
            return result;
        }
        // copy HTTP proxy as it is not part DataCallResponse.
        result.newLp.setHttpProxy(mLinkProperties.getHttpProxy());

        checkSetMtu(mApnSetting, result.newLp);

        mLinkProperties = result.newLp;

        updateTcpBufferSizes(mRilRat);

        if (DBG && (! result.oldLp.equals(result.newLp))) {
            log("updateLinkProperty old LP=" + result.oldLp);
            log("updateLinkProperty new LP=" + result.newLp);
        }

        if (result.newLp.equals(result.oldLp) == false &&
                mNetworkAgent != null) {
            mNetworkAgent.sendLinkProperties(mLinkProperties);
        }

        return result;
    }

    /**
     * Read the MTU value from link properties where it can be set from network. In case
     * not set by the network, set it again using the mtu szie value defined in the APN
     * database for the connected APN
     */
    private void checkSetMtu(ApnSetting apn, LinkProperties lp) {
        if (lp == null) return;

        if (apn == null || lp == null) return;

        if (lp.getMtu() != PhoneConstants.UNSET_MTU) {
            if (DBG) log("MTU set by call response to: " + lp.getMtu());
            return;
        }

        if (apn != null && apn.mtu != PhoneConstants.UNSET_MTU) {
            lp.setMtu(apn.mtu);
            if (DBG) log("MTU set by APN to: " + apn.mtu);
            return;
        }

        int mtu = mPhone.getContext().getResources().getInteger(
                com.android.internal.R.integer.config_mobile_mtu);
        if (mtu != PhoneConstants.UNSET_MTU) {
            lp.setMtu(mtu);
            if (DBG) log("MTU set by config resource to: " + mtu);
        }
    }

    //***** Constructor (NOTE: uses dcc.getHandler() as its Handler)
    private DataConnection(Phone phone, String name, int id,
                           DcTracker dct, DataServiceManager dataServiceManager,
                           DcTesterFailBringUpAll failBringUpAll,
                DcController dcc) {
        super(name, dcc.getHandler());
        setLogRecSize(300);
        setLogOnlyTransitions(true);
        if (DBG) log("DataConnection created");

        mPhone = phone;
        mDct = dct;
        mDataServiceManager = dataServiceManager;
        mDcTesterFailBringUpAll = failBringUpAll;
        mDcController = dcc;
        mId = id;
        mCid = -1;
        ServiceState ss = mPhone.getServiceState();
        mRilRat = ss.getRilDataRadioTechnology();
        mDataRegState = mPhone.getServiceState().getDataRegState();
        int networkType = ss.getDataNetworkType();
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_MOBILE,
                networkType, NETWORK_TYPE, TelephonyManager.getNetworkTypeName(networkType));
        mNetworkInfo.setRoaming(ss.getDataRoaming());
        mNetworkInfo.setIsAvailable(true);

        addState(mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mActivatingState, mDefaultState);
            addState(mActiveState, mDefaultState);
            addState(mDisconnectingState, mDefaultState);
            addState(mDisconnectingErrorCreatingConnection, mDefaultState);
        setInitialState(mInactiveState);

        mApnContexts = new HashMap<>();
    }

    /**
     * Begin setting up a data connection, calls setupDataCall
     * and the ConnectionParams will be returned with the
     * EVENT_SETUP_DATA_CONNECTION_DONE
     *
     * @param cp is the connection parameters
     */
    private void onConnect(ConnectionParams cp) {
        if (DBG) log("onConnect: carrier='" + mApnSetting.carrier
                + "' APN='" + mApnSetting.apn
                + "' proxy='" + mApnSetting.proxy + "' port='" + mApnSetting.port + "'");
        if (cp.mApnContext != null) cp.mApnContext.requestLog("DataConnection.onConnect");

        // Check if we should fake an error.
        if (mDcTesterFailBringUpAll.getDcFailBringUp().mCounter  > 0) {
            DataCallResponse response = new DataCallResponse(
                    mDcTesterFailBringUpAll.getDcFailBringUp().mFailCause.getErrorCode(),
                    mDcTesterFailBringUpAll.getDcFailBringUp().mSuggestedRetryTime, 0, 0, "", "",
                    null, null, null, null, PhoneConstants.UNSET_MTU);

            Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp);
            AsyncResult.forMessage(msg, response, null);
            sendMessage(msg);
            if (DBG) {
                log("onConnect: FailBringUpAll=" + mDcTesterFailBringUpAll.getDcFailBringUp()
                        + " send error response=" + response);
            }
            mDcTesterFailBringUpAll.getDcFailBringUp().mCounter -= 1;
            return;
        }

        mCreateTime = -1;
        mLastFailTime = -1;
        mLastFailCause = DcFailCause.NONE;

        Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp);
        msg.obj = cp;

        DataProfile dp = DcTracker.createDataProfile(mApnSetting, cp.mProfileId);

        // We need to use the actual modem roaming state instead of the framework roaming state
        // here. This flag is only passed down to ril_service for picking the correct protocol (for
        // old modem backward compatibility).
        boolean isModemRoaming = mPhone.getServiceState().getDataRoamingFromRegistration();

        // Set this flag to true if the user turns on data roaming. Or if we override the roaming
        // state in framework, we should set this flag to true as well so the modem will not reject
        // the data call setup (because the modem actually thinks the device is roaming).
        boolean allowRoaming = mPhone.getDataRoamingEnabled()
                || (isModemRoaming && !mPhone.getServiceState().getDataRoaming());

        mDataServiceManager.setupDataCall(
                ServiceState.rilRadioTechnologyToAccessNetworkType(cp.mRilRat), dp, isModemRoaming,
                allowRoaming, DataService.REQUEST_REASON_NORMAL, null, msg);
        TelephonyMetrics.getInstance().writeSetupDataCall(mPhone.getPhoneId(), cp.mRilRat,
                dp.getProfileId(), dp.getApn(), dp.getProtocol());
    }

    public void onSubscriptionOverride(int overrideMask, int overrideValue) {
        mSubscriptionOverride = (mSubscriptionOverride & ~overrideMask)
                | (overrideValue & overrideMask);
        sendMessage(obtainMessage(EVENT_DATA_CONNECTION_OVERRIDE_CHANGED));
    }

    /**
     * TearDown the data connection when the deactivation is complete a Message with
     * msg.what == EVENT_DEACTIVATE_DONE
     *
     * @param o is the object returned in the AsyncResult.obj.
     */
    private void tearDownData(Object o) {
        int discReason = DataService.REQUEST_REASON_NORMAL;
        ApnContext apnContext = null;
        if ((o != null) && (o instanceof DisconnectParams)) {
            DisconnectParams dp = (DisconnectParams) o;
            apnContext = dp.mApnContext;
            if (TextUtils.equals(dp.mReason, Phone.REASON_RADIO_TURNED_OFF)
                    || TextUtils.equals(dp.mReason, Phone.REASON_PDP_RESET)) {
                discReason = DataService.REQUEST_REASON_SHUTDOWN;
            }
        }

        String str = "tearDownData. mCid=" + mCid + ", reason=" + discReason;
        if (DBG) log(str);
        if (apnContext != null) apnContext.requestLog(str);
        mDataServiceManager.deactivateDataCall(mCid, discReason,
                obtainMessage(EVENT_DEACTIVATE_DONE, mTag, 0, o));
    }

    private void notifyAllWithEvent(ApnContext alreadySent, int event, String reason) {
        mNetworkInfo.setDetailedState(mNetworkInfo.getDetailedState(), reason,
                mNetworkInfo.getExtraInfo());
        for (ConnectionParams cp : mApnContexts.values()) {
            ApnContext apnContext = cp.mApnContext;
            if (apnContext == alreadySent) continue;
            if (reason != null) apnContext.setReason(reason);
            Pair<ApnContext, Integer> pair =
                    new Pair<ApnContext, Integer>(apnContext, cp.mConnectionGeneration);
            Message msg = mDct.obtainMessage(event, pair);
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
    }

    private void notifyAllOfConnected(String reason) {
        notifyAllWithEvent(null, DctConstants.EVENT_DATA_SETUP_COMPLETE, reason);
    }

    private void notifyAllOfDisconnectDcRetrying(String reason) {
        notifyAllWithEvent(null, DctConstants.EVENT_DISCONNECT_DC_RETRYING, reason);
    }
    private void notifyAllDisconnectCompleted(DcFailCause cause) {
        notifyAllWithEvent(null, DctConstants.EVENT_DISCONNECT_DONE, cause.toString());
    }


    /**
     * Send the connectionCompletedMsg.
     *
     * @param cp is the ConnectionParams
     * @param cause and if no error the cause is DcFailCause.NONE
     * @param sendAll is true if all contexts are to be notified
     */
    private void notifyConnectCompleted(ConnectionParams cp, DcFailCause cause, boolean sendAll) {
        ApnContext alreadySent = null;

        if (cp != null && cp.mOnCompletedMsg != null) {
            // Get the completed message but only use it once
            Message connectionCompletedMsg = cp.mOnCompletedMsg;
            cp.mOnCompletedMsg = null;
            alreadySent = cp.mApnContext;

            long timeStamp = System.currentTimeMillis();
            connectionCompletedMsg.arg1 = mCid;

            if (cause == DcFailCause.NONE) {
                mCreateTime = timeStamp;
                AsyncResult.forMessage(connectionCompletedMsg);
            } else {
                mLastFailCause = cause;
                mLastFailTime = timeStamp;

                // Return message with a Throwable exception to signify an error.
                if (cause == null) cause = DcFailCause.UNKNOWN;
                AsyncResult.forMessage(connectionCompletedMsg, cause,
                        new Throwable(cause.toString()));
            }
            if (DBG) {
                log("notifyConnectCompleted at " + timeStamp + " cause=" + cause
                        + " connectionCompletedMsg=" + msgToString(connectionCompletedMsg));
            }

            connectionCompletedMsg.sendToTarget();
        }
        if (sendAll) {
            log("Send to all. " + alreadySent + " " + cause.toString());
            notifyAllWithEvent(alreadySent, DctConstants.EVENT_DATA_SETUP_COMPLETE_ERROR,
                    cause.toString());
        }
    }

    /**
     * Send ar.userObj if its a message, which is should be back to originator.
     *
     * @param dp is the DisconnectParams.
     */
    private void notifyDisconnectCompleted(DisconnectParams dp, boolean sendAll) {
        if (VDBG) log("NotifyDisconnectCompleted");

        ApnContext alreadySent = null;
        String reason = null;

        if (dp != null && dp.mOnCompletedMsg != null) {
            // Get the completed message but only use it once
            Message msg = dp.mOnCompletedMsg;
            dp.mOnCompletedMsg = null;
            if (msg.obj instanceof ApnContext) {
                alreadySent = (ApnContext)msg.obj;
            }
            reason = dp.mReason;
            if (VDBG) {
                log(String.format("msg=%s msg.obj=%s", msg.toString(),
                    ((msg.obj instanceof String) ? (String) msg.obj : "<no-reason>")));
            }
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
        if (sendAll) {
            if (reason == null) {
                reason = DcFailCause.UNKNOWN.toString();
            }
            notifyAllWithEvent(alreadySent, DctConstants.EVENT_DISCONNECT_DONE, reason);
        }
        if (DBG) log("NotifyDisconnectCompleted DisconnectParams=" + dp);
    }

    /*
     * **************************************************************************
     * Begin Members and methods owned by DataConnectionTracker but stored
     * in a DataConnection because there is one per connection.
     * **************************************************************************
     */

    /*
     * The id is owned by DataConnectionTracker.
     */
    private int mId;

    /**
     * Get the DataConnection ID
     */
    public int getDataConnectionId() {
        return mId;
    }

    /*
     * **************************************************************************
     * End members owned by DataConnectionTracker
     * **************************************************************************
     */

    /**
     * Clear all settings called when entering mInactiveState.
     */
    private void clearSettings() {
        if (DBG) log("clearSettings");

        mCreateTime = -1;
        mLastFailTime = -1;
        mLastFailCause = DcFailCause.NONE;
        mCid = -1;

        mPcscfAddr = new String[5];

        mLinkProperties = new LinkProperties();
        mApnContexts.clear();
        mApnSetting = null;
        mDcFailCause = null;
    }

    /**
     * Process setup data completion result from data service
     *
     * @param resultCode The result code returned by data service
     * @param response Data call setup response from data service
     * @param cp The original connection params used for data call setup
     * @return Setup result
     */
    private SetupResult onSetupConnectionCompleted(@DataServiceCallback.ResultCode int resultCode,
                                                   DataCallResponse response,
                                                   ConnectionParams cp) {
        SetupResult result;

        if (cp.mTag != mTag) {
            if (DBG) {
                log("onSetupConnectionCompleted stale cp.tag=" + cp.mTag + ", mtag=" + mTag);
            }
            result = SetupResult.ERROR_STALE;
        } else if (resultCode == DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE) {
            result = SetupResult.ERROR_RADIO_NOT_AVAILABLE;
            result.mFailCause = DcFailCause.RADIO_NOT_AVAILABLE;
        } else if (response.getStatus() != 0) {
            if (response.getStatus() == DcFailCause.RADIO_NOT_AVAILABLE.getErrorCode()) {
                result = SetupResult.ERROR_RADIO_NOT_AVAILABLE;
                result.mFailCause = DcFailCause.RADIO_NOT_AVAILABLE;
            } else {
                result = SetupResult.ERROR_DATA_SERVICE_SPECIFIC_ERROR;
                result.mFailCause = DcFailCause.fromInt(response.getStatus());
            }
        } else {
            if (DBG) log("onSetupConnectionCompleted received successful DataCallResponse");
            mCid = response.getCallId();

            mPcscfAddr = response.getPcscfs().toArray(new String[response.getPcscfs().size()]);

            result = updateLinkProperty(response).setupResult;
        }

        return result;
    }

    private boolean isDnsOk(String[] domainNameServers) {
        if (NULL_IP.equals(domainNameServers[0]) && NULL_IP.equals(domainNameServers[1])
                && !mPhone.isDnsCheckDisabled()) {
            // Work around a race condition where QMI does not fill in DNS:
            // Deactivate PDP and let DataConnectionTracker retry.
            // Do not apply the race condition workaround for MMS APN
            // if Proxy is an IP-address.
            // Otherwise, the default APN will not be restored anymore.
            if (!mApnSetting.types[0].equals(PhoneConstants.APN_TYPE_MMS)
                || !isIpAddress(mApnSetting.mmsProxy)) {
                log(String.format(
                        "isDnsOk: return false apn.types[0]=%s APN_TYPE_MMS=%s isIpAddress(%s)=%s",
                        mApnSetting.types[0], PhoneConstants.APN_TYPE_MMS, mApnSetting.mmsProxy,
                        isIpAddress(mApnSetting.mmsProxy)));
                return false;
            }
        }
        return true;
    }

    private static final String TCP_BUFFER_SIZES_GPRS = "4092,8760,48000,4096,8760,48000";
    private static final String TCP_BUFFER_SIZES_EDGE = "4093,26280,70800,4096,16384,70800";
    private static final String TCP_BUFFER_SIZES_UMTS = "58254,349525,1048576,58254,349525,1048576";
    private static final String TCP_BUFFER_SIZES_1XRTT= "16384,32768,131072,4096,16384,102400";
    private static final String TCP_BUFFER_SIZES_EVDO = "4094,87380,262144,4096,16384,262144";
    private static final String TCP_BUFFER_SIZES_EHRPD= "131072,262144,1048576,4096,16384,524288";
    private static final String TCP_BUFFER_SIZES_HSDPA= "61167,367002,1101005,8738,52429,262114";
    private static final String TCP_BUFFER_SIZES_HSPA = "40778,244668,734003,16777,100663,301990";
    private static final String TCP_BUFFER_SIZES_LTE  =
            "524288,1048576,2097152,262144,524288,1048576";
    private static final String TCP_BUFFER_SIZES_HSPAP= "122334,734003,2202010,32040,192239,576717";

    private void updateTcpBufferSizes(int rilRat) {
        String sizes = null;
        if (rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA) {
            // for now treat CA as LTE.  Plan to surface the extra bandwith in a more
            // precise manner which should affect buffer sizes
            rilRat = ServiceState.RIL_RADIO_TECHNOLOGY_LTE;
        }
        String ratName = ServiceState.rilRadioTechnologyToString(rilRat).toLowerCase(Locale.ROOT);
        // ServiceState gives slightly different names for EVDO tech ("evdo-rev.0" for ex)
        // - patch it up:
        if (rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0 ||
                rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A ||
                rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B) {
            ratName = "evdo";
        }

        // in the form: "ratname:rmem_min,rmem_def,rmem_max,wmem_min,wmem_def,wmem_max"
        String[] configOverride = mPhone.getContext().getResources().getStringArray(
                com.android.internal.R.array.config_mobile_tcp_buffers);
        for (int i = 0; i < configOverride.length; i++) {
            String[] split = configOverride[i].split(":");
            if (ratName.equals(split[0]) && split.length == 2) {
                sizes = split[1];
                break;
            }
        }

        if (sizes == null) {
            // no override - use telephony defaults
            // doing it this way allows device or carrier to just override the types they
            // care about and inherit the defaults for the others.
            switch (rilRat) {
                case ServiceState.RIL_RADIO_TECHNOLOGY_GPRS:
                    sizes = TCP_BUFFER_SIZES_GPRS;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_EDGE:
                    sizes = TCP_BUFFER_SIZES_EDGE;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_UMTS:
                    sizes = TCP_BUFFER_SIZES_UMTS;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT:
                    sizes = TCP_BUFFER_SIZES_1XRTT;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0:
                case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A:
                case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B:
                    sizes = TCP_BUFFER_SIZES_EVDO;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD:
                    sizes = TCP_BUFFER_SIZES_EHRPD;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA:
                    sizes = TCP_BUFFER_SIZES_HSDPA;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSPA:
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA:
                    sizes = TCP_BUFFER_SIZES_HSPA;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_LTE:
                case ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA:
                    sizes = TCP_BUFFER_SIZES_LTE;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP:
                    sizes = TCP_BUFFER_SIZES_HSPAP;
                    break;
                default:
                    // Leave empty - this will let ConnectivityService use the system default.
                    break;
            }
        }
        mLinkProperties.setTcpBufferSizes(sizes);
    }

    /**
     * Indicates if when this connection was established we had a restricted/privileged
     * NetworkRequest and needed it to overcome data-enabled limitations.
     *
     * This gets set once per connection setup and is based on conditions at that time.
     * We could theoretically have dynamic capabilities but now is not a good time to
     * experiment with that.
     *
     * This flag overrides the APN-based restriction capability, restricting the network
     * based on both having a NetworkRequest with restricted AND needing a restricted
     * bit to overcome user-disabled status.  This allows us to handle the common case
     * of having both restricted requests and unrestricted requests for the same apn:
     * if conditions require a restricted network to overcome user-disabled then it must
     * be restricted, otherwise it is unrestricted (or restricted based on APN type).
     *
     * Because we're not supporting dynamic capabilities, if conditions change and we go from
     * data-enabled to not or vice-versa we will need to tear down networks to deal with it
     * at connection setup time with the new state.
     *
     * This supports a privileged app bringing up a network without general apps having access
     * to it when the network is otherwise unavailable (hipri).  The first use case is
     * pre-paid SIM reprovisioning over internet, where the carrier insists on no traffic
     * other than from the privileged carrier-app.
     */
    private boolean mRestrictedNetworkOverride = false;

    // Should be called once when the call goes active to examine the state of things and
    // declare the restriction override for the life of the connection
    private void setNetworkRestriction() {
        mRestrictedNetworkOverride = false;
        // first, if we have no restricted requests, this override can stay FALSE:
        boolean noRestrictedRequests = true;
        for (ApnContext apnContext : mApnContexts.keySet()) {
            noRestrictedRequests &= apnContext.hasNoRestrictedRequests(true /* exclude DUN */);
        }
        if (noRestrictedRequests) {
            return;
        }

        // Do we need a restricted network to satisfy the request?
        // Is this network metered?  If not, then don't add restricted
        if (!mApnSetting.isMetered(mPhone)) {
            return;
        }

        // Is data disabled?
        mRestrictedNetworkOverride = !mDct.isDataEnabled();
    }

    NetworkCapabilities getNetworkCapabilities() {
        NetworkCapabilities result = new NetworkCapabilities();
        result.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

        if (mApnSetting != null) {
            for (String type : mApnSetting.types) {
                if (!mRestrictedNetworkOverride
                        && (mConnectionParams != null && mConnectionParams.mUnmeteredUseOnly)
                        && ApnSetting.isMeteredApnType(type, mPhone)) {
                    log("Dropped the metered " + type + " for the unmetered data call.");
                    continue;
                }
                switch (type) {
                    case PhoneConstants.APN_TYPE_ALL: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_DEFAULT: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_MMS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_SUPL: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_DUN: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_FOTA: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_IMS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_CBS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_IA: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_EMERGENCY: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
                        break;
                    }
                    default:
                }
            }

            // Mark NOT_METERED in the following cases,
            // 1. All APNs in APN settings are unmetered.
            // 2. The non-restricted data and is intended for unmetered use only.
            if (((mConnectionParams != null && mConnectionParams.mUnmeteredUseOnly)
                    && !mRestrictedNetworkOverride)
                    || !mApnSetting.isMetered(mPhone)) {
                result.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            } else {
                result.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }

            result.maybeMarkCapabilitiesRestricted();
        }
        if (mRestrictedNetworkOverride) {
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
            // don't use dun on restriction-overriden networks.
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
        }

        int up = 14;
        int down = 14;
        switch (mRilRat) {
            case ServiceState.RIL_RADIO_TECHNOLOGY_GPRS: up = 80; down = 80; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EDGE: up = 59; down = 236; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_UMTS: up = 384; down = 384; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_IS95A: // fall through
            case ServiceState.RIL_RADIO_TECHNOLOGY_IS95B: up = 14; down = 14; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0: up = 153; down = 2457; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A: up = 1843; down = 3174; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT: up = 100; down = 100; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA: up = 2048; down = 14336; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA: up = 5898; down = 14336; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSPA: up = 5898; down = 14336; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B: up = 1843; down = 5017; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_LTE: up = 51200; down = 102400; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA: up = 51200; down = 102400; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD: up = 153; down = 2516; break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP: up = 11264; down = 43008; break;
            default:
        }
        result.setLinkUpstreamBandwidthKbps(up);
        result.setLinkDownstreamBandwidthKbps(down);

        result.setNetworkSpecifier(new StringNetworkSpecifier(Integer.toString(mPhone.getSubId())));

        result.setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
                !mPhone.getServiceState().getDataRoaming());

        result.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);

        // Override values set above when requested by policy
        if ((mSubscriptionOverride & OVERRIDE_UNMETERED) != 0) {
            result.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }
        if ((mSubscriptionOverride & OVERRIDE_CONGESTED) != 0) {
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);
        }

        return result;
    }

    /**
     * @return {@code} true iff. {@code address} is a literal IPv4 or IPv6 address.
     */
    @VisibleForTesting
    public static boolean isIpAddress(String address) {
        if (address == null) return false;

        return InetAddress.isNumeric(address);
    }

    private SetupResult setLinkProperties(DataCallResponse response,
            LinkProperties linkProperties) {
        // Check if system property dns usable
        String propertyPrefix = "net." + response.getIfname() + ".";
        String dnsServers[] = new String[2];
        dnsServers[0] = SystemProperties.get(propertyPrefix + "dns1");
        dnsServers[1] = SystemProperties.get(propertyPrefix + "dns2");
        boolean okToUseSystemPropertyDns = isDnsOk(dnsServers);

        SetupResult result;

        // Start with clean network properties and if we have
        // a failure we'll clear again at the bottom of this code.
        linkProperties.clear();

        if (response.getStatus() == DcFailCause.NONE.getErrorCode()) {
            try {
                // set interface name
                linkProperties.setInterfaceName(response.getIfname());

                // set link addresses
                if (response.getAddresses().size() > 0) {
                    for (LinkAddress la : response.getAddresses()) {
                        if (!la.getAddress().isAnyLocalAddress()) {
                            if (DBG) {
                                log("addr/pl=" + la.getAddress() + "/"
                                        + la.getNetworkPrefixLength());
                            }
                            linkProperties.addLinkAddress(la);
                        }
                    }
                } else {
                    throw new UnknownHostException("no address for ifname=" + response.getIfname());
                }

                // set dns servers
                if (response.getDnses().size() > 0) {
                    for (InetAddress dns : response.getDnses()) {
                        if (!dns.isAnyLocalAddress()) {
                            linkProperties.addDnsServer(dns);
                        }
                    }
                } else if (okToUseSystemPropertyDns) {
                    for (String dnsAddr : dnsServers) {
                        dnsAddr = dnsAddr.trim();
                        if (dnsAddr.isEmpty()) continue;
                        InetAddress ia;
                        try {
                            ia = NetworkUtils.numericToInetAddress(dnsAddr);
                        } catch (IllegalArgumentException e) {
                            throw new UnknownHostException("Non-numeric dns addr=" + dnsAddr);
                        }
                        if (!ia.isAnyLocalAddress()) {
                            linkProperties.addDnsServer(ia);
                        }
                    }
                } else {
                    throw new UnknownHostException("Empty dns response and no system default dns");
                }

                for (InetAddress gateway : response.getGateways()) {
                    // Allow 0.0.0.0 or :: as a gateway;
                    // this indicates a point-to-point interface.
                    linkProperties.addRoute(new RouteInfo(gateway));
                }

                // set interface MTU
                // this may clobber the setting read from the APN db, but that's ok
                linkProperties.setMtu(response.getMtu());

                result = SetupResult.SUCCESS;
            } catch (UnknownHostException e) {
                log("setLinkProperties: UnknownHostException " + e);
                result = SetupResult.ERROR_INVALID_ARG;
            }
        } else {
            result = SetupResult.ERROR_DATA_SERVICE_SPECIFIC_ERROR;
        }

        // An error occurred so clear properties
        if (result != SetupResult.SUCCESS) {
            if (DBG) {
                log("setLinkProperties: error clearing LinkProperties status="
                        + response.getStatus() + " result=" + result);
            }
            linkProperties.clear();
        }

        return result;
    }

    /**
     * Initialize connection, this will fail if the
     * apnSettings are not compatible.
     *
     * @param cp the Connection parameters
     * @return true if initialization was successful.
     */
    private boolean initConnection(ConnectionParams cp) {
        ApnContext apnContext = cp.mApnContext;
        if (mApnSetting == null) {
            // Only change apn setting if it isn't set, it will
            // only NOT be set only if we're in DcInactiveState.
            mApnSetting = apnContext.getApnSetting();
        }
        if (mApnSetting == null || !mApnSetting.canHandleType(apnContext.getApnType())) {
            if (DBG) {
                log("initConnection: incompatible apnSetting in ConnectionParams cp=" + cp
                        + " dc=" + DataConnection.this);
            }
            return false;
        }
        mTag += 1;
        mConnectionParams = cp;
        mConnectionParams.mTag = mTag;

        // always update the ConnectionParams with the latest or the
        // connectionGeneration gets stale
        mApnContexts.put(apnContext, cp);

        if (DBG) {
            log("initConnection: "
                    + " RefCount=" + mApnContexts.size()
                    + " mApnList=" + mApnContexts
                    + " mConnectionParams=" + mConnectionParams);
        }
        return true;
    }

    /**
     * The parent state for all other states.
     */
    private class DcDefaultState extends State {
        @Override
        public void enter() {
            if (DBG) log("DcDefaultState: enter");

            // Register for DRS or RAT change
            mPhone.getServiceStateTracker().registerForDataRegStateOrRatChanged(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED, null);

            mPhone.getServiceStateTracker().registerForDataRoamingOn(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_ROAM_ON, null);
            mPhone.getServiceStateTracker().registerForDataRoamingOff(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_ROAM_OFF, null, true);

            // Add ourselves to the list of data connections
            mDcController.addDc(DataConnection.this);
        }
        @Override
        public void exit() {
            if (DBG) log("DcDefaultState: exit");

            // Unregister for DRS or RAT change.
            mPhone.getServiceStateTracker().unregisterForDataRegStateOrRatChanged(getHandler());

            mPhone.getServiceStateTracker().unregisterForDataRoamingOn(getHandler());
            mPhone.getServiceStateTracker().unregisterForDataRoamingOff(getHandler());

            // Remove ourselves from the DC lists
            mDcController.removeDc(DataConnection.this);

            if (mAc != null) {
                mAc.disconnected();
                mAc = null;
            }
            mApnContexts = null;
            mReconnectIntent = null;
            mDct = null;
            mApnSetting = null;
            mPhone = null;
            mDataServiceManager = null;
            mLinkProperties = null;
            mLastFailCause = null;
            mUserData = null;
            mDcController = null;
            mDcTesterFailBringUpAll = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = HANDLED;

            if (VDBG) {
                log("DcDefault msg=" + getWhatToString(msg.what)
                        + " RefCount=" + mApnContexts.size());
            }
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAc != null) {
                        if (VDBG) log("Disconnecting to previous connection mAc=" + mAc);
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        if (VDBG) log("DcDefaultState: FULL_CONNECTION reply connected");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (DBG) {
                        log("DcDefault: CMD_CHANNEL_DISCONNECTED before quiting call dump");
                        dumpToLog();
                    }

                    quit();
                    break;
                }
                case DcAsyncChannel.REQ_IS_INACTIVE: {
                    boolean val = isInactive();
                    if (VDBG) log("REQ_IS_INACTIVE  isInactive=" + val);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_IS_INACTIVE, val ? 1 : 0);
                    break;
                }
                case DcAsyncChannel.REQ_GET_CID: {
                    int cid = getCid();
                    if (VDBG) log("REQ_GET_CID  cid=" + cid);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_CID, cid);
                    break;
                }
                case DcAsyncChannel.REQ_GET_APNSETTING: {
                    ApnSetting apnSetting = getApnSetting();
                    if (VDBG) log("REQ_GET_APNSETTING  mApnSetting=" + apnSetting);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_APNSETTING, apnSetting);
                    break;
                }
                case DcAsyncChannel.REQ_GET_LINK_PROPERTIES: {
                    LinkProperties lp = getCopyLinkProperties();
                    if (VDBG) log("REQ_GET_LINK_PROPERTIES linkProperties" + lp);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_LINK_PROPERTIES, lp);
                    break;
                }
                case DcAsyncChannel.REQ_SET_LINK_PROPERTIES_HTTP_PROXY: {
                    ProxyInfo proxy = (ProxyInfo) msg.obj;
                    if (VDBG) log("REQ_SET_LINK_PROPERTIES_HTTP_PROXY proxy=" + proxy);
                    setLinkPropertiesHttpProxy(proxy);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_SET_LINK_PROPERTIES_HTTP_PROXY);
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendLinkProperties(mLinkProperties);
                    }
                    break;
                }
                case DcAsyncChannel.REQ_GET_NETWORK_CAPABILITIES: {
                    NetworkCapabilities nc = getNetworkCapabilities();
                    if (VDBG) log("REQ_GET_NETWORK_CAPABILITIES networkCapabilities" + nc);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_NETWORK_CAPABILITIES, nc);
                    break;
                }
                case DcAsyncChannel.REQ_RESET:
                    if (VDBG) log("DcDefaultState: msg.what=REQ_RESET");
                    transitionTo(mInactiveState);
                    break;
                case EVENT_CONNECT:
                    if (DBG) log("DcDefaultState: msg.what=EVENT_CONNECT, fail not expected");
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    notifyConnectCompleted(cp, DcFailCause.UNKNOWN, false);
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT RefCount="
                                + mApnContexts.size());
                    }
                    deferMessage(msg);
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT_ALL RefCount="
                                + mApnContexts.size());
                    }
                    deferMessage(msg);
                    break;
                case EVENT_TEAR_DOWN_NOW:
                    if (DBG) log("DcDefaultState EVENT_TEAR_DOWN_NOW");
                    mDataServiceManager.deactivateDataCall(mCid, DataService.REQUEST_REASON_NORMAL,
                            null);
                    break;
                case EVENT_LOST_CONNECTION:
                    if (DBG) {
                        String s = "DcDefaultState ignore EVENT_LOST_CONNECTION"
                                + " tag=" + msg.arg1 + ":mTag=" + mTag;
                        logAndAddLogRec(s);
                    }
                    break;
                case EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED:
                    AsyncResult ar = (AsyncResult)msg.obj;
                    Pair<Integer, Integer> drsRatPair = (Pair<Integer, Integer>)ar.result;
                    mDataRegState = drsRatPair.first;
                    if (mRilRat != drsRatPair.second) {
                        updateTcpBufferSizes(drsRatPair.second);
                    }
                    mRilRat = drsRatPair.second;
                    if (DBG) {
                        log("DcDefaultState: EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED"
                                + " drs=" + mDataRegState
                                + " mRilRat=" + mRilRat);
                    }
                    updateNetworkInfo();
                    updateNetworkInfoSuspendState();
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities());
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                        mNetworkAgent.sendLinkProperties(mLinkProperties);
                    }
                    break;
                case EVENT_DATA_CONNECTION_ROAM_ON:
                case EVENT_DATA_CONNECTION_ROAM_OFF:
                case EVENT_DATA_CONNECTION_OVERRIDE_CHANGED:
                    updateNetworkInfo();
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities());
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                    }
                    break;
                case EVENT_KEEPALIVE_START_REQUEST:
                case EVENT_KEEPALIVE_STOP_REQUEST:
                    if (mNetworkAgent != null) {
                        mNetworkAgent.onPacketKeepaliveEvent(
                                msg.arg1,
                                ConnectivityManager.PacketKeepalive.ERROR_INVALID_NETWORK);
                    }
                    break;
                default:
                    if (DBG) {
                        log("DcDefaultState: shouldn't happen but ignore msg.what="
                                + getWhatToString(msg.what));
                    }
                    break;
            }

            return retVal;
        }
    }

    private void updateNetworkInfo() {
        final ServiceState state = mPhone.getServiceState();
        final int subtype = state.getDataNetworkType();
        mNetworkInfo.setSubtype(subtype, TelephonyManager.getNetworkTypeName(subtype));
        mNetworkInfo.setRoaming(state.getDataRoaming());
    }

    private void updateNetworkInfoSuspendState() {
        // this is only called when we are either connected or suspended.  Decide which.
        if (mNetworkAgent == null) {
            Rlog.e(getName(), "Setting suspend state without a NetworkAgent");
        }

        // if we are not in-service change to SUSPENDED
        final ServiceStateTracker sst = mPhone.getServiceStateTracker();
        if (sst.getCurrentDataConnectionState() != ServiceState.STATE_IN_SERVICE) {
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.SUSPENDED, null,
                    mNetworkInfo.getExtraInfo());
        } else {
            // check for voice call and concurrency issues
            if (sst.isConcurrentVoiceAndDataAllowed() == false) {
                final CallTracker ct = mPhone.getCallTracker();
                if (ct.getState() != PhoneConstants.State.IDLE) {
                    mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.SUSPENDED, null,
                            mNetworkInfo.getExtraInfo());
                    return;
                }
            }
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null,
                    mNetworkInfo.getExtraInfo());
        }
    }

    private DcDefaultState mDefaultState = new DcDefaultState();

    /**
     * The state machine is inactive and expects a EVENT_CONNECT.
     */
    private class DcInactiveState extends State {
        // Inform all contexts we've failed connecting
        public void setEnterNotificationParams(ConnectionParams cp, DcFailCause cause) {
            if (VDBG) log("DcInactiveState: setEnterNotificationParams cp,cause");
            mConnectionParams = cp;
            mDisconnectParams = null;
            mDcFailCause = cause;
        }

        // Inform all contexts we've failed disconnected
        public void setEnterNotificationParams(DisconnectParams dp) {
            if (VDBG) log("DcInactiveState: setEnterNotificationParams dp");
            mConnectionParams = null;
            mDisconnectParams = dp;
            mDcFailCause = DcFailCause.NONE;
        }

        // Inform all contexts of the failure cause
        public void setEnterNotificationParams(DcFailCause cause) {
            mConnectionParams = null;
            mDisconnectParams = null;
            mDcFailCause = cause;
        }

        @Override
        public void enter() {
            mTag += 1;
            if (DBG) log("DcInactiveState: enter() mTag=" + mTag);
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__INACTIVE,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.typesBitmap : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(PhoneConstants.APN_TYPE_DEFAULT) : false);

            if (mConnectionParams != null) {
                if (DBG) {
                    log("DcInactiveState: enter notifyConnectCompleted +ALL failCause="
                            + mDcFailCause);
                }
                notifyConnectCompleted(mConnectionParams, mDcFailCause, true);
            }
            if (mDisconnectParams != null) {
                if (DBG) {
                    log("DcInactiveState: enter notifyDisconnectCompleted +ALL failCause="
                            + mDcFailCause);
                }
                notifyDisconnectCompleted(mDisconnectParams, true);
            }
            if (mDisconnectParams == null && mConnectionParams == null && mDcFailCause != null) {
                if (DBG) {
                    log("DcInactiveState: enter notifyAllDisconnectCompleted failCause="
                            + mDcFailCause);
                }
                notifyAllDisconnectCompleted(mDcFailCause);
            }

            // Remove ourselves from cid mapping, before clearSettings
            mDcController.removeActiveDcByCid(DataConnection.this);

            clearSettings();
        }

        @Override
        public void exit() {
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcAsyncChannel.REQ_RESET:
                    if (DBG) {
                        log("DcInactiveState: msg.what=RSP_RESET, ignore we're already reset");
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_CONNECT:
                    if (DBG) log("DcInactiveState: mag.what=EVENT_CONNECT");
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    if (initConnection(cp)) {
                        onConnect(mConnectionParams);
                        transitionTo(mActivatingState);
                    } else {
                        if (DBG) {
                            log("DcInactiveState: msg.what=EVENT_CONNECT initConnection failed");
                        }
                        notifyConnectCompleted(cp, DcFailCause.UNACCEPTABLE_NETWORK_PARAMETER,
                                false);
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT_ALL");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcInactiveState nothandled msg.what=" + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcInactiveState mInactiveState = new DcInactiveState();

    /**
     * The state machine is activating a connection.
     */
    private class DcActivatingState extends State {
        @Override
        public void enter() {
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__ACTIVATING,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.typesBitmap : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(PhoneConstants.APN_TYPE_DEFAULT) : false);
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            AsyncResult ar;
            ConnectionParams cp;

            if (DBG) log("DcActivatingState: msg=" + msgToString(msg));
            switch (msg.what) {
                case EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED:
                case EVENT_CONNECT:
                    // Activating can't process until we're done.
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_SETUP_DATA_CONNECTION_DONE:
                    cp = (ConnectionParams) msg.obj;

                    DataCallResponse dataCallResponse =
                            msg.getData().getParcelable(DataServiceManager.DATA_CALL_RESPONSE);
                    SetupResult result = onSetupConnectionCompleted(msg.arg1, dataCallResponse, cp);
                    if (result != SetupResult.ERROR_STALE) {
                        if (mConnectionParams != cp) {
                            loge("DcActivatingState: WEIRD mConnectionsParams:"+ mConnectionParams
                                    + " != cp:" + cp);
                        }
                    }
                    if (DBG) {
                        log("DcActivatingState onSetupConnectionCompleted result=" + result
                                + " dc=" + DataConnection.this);
                    }
                    if (cp.mApnContext != null) {
                        cp.mApnContext.requestLog("onSetupConnectionCompleted result=" + result);
                    }
                    switch (result) {
                        case SUCCESS:
                            // All is well
                            mDcFailCause = DcFailCause.NONE;
                            transitionTo(mActiveState);
                            break;
                        case ERROR_RADIO_NOT_AVAILABLE:
                            // Vendor ril rejected the command and didn't connect.
                            // Transition to inactive but send notifications after
                            // we've entered the mInactive state.
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause);
                            transitionTo(mInactiveState);
                            break;
                        case ERROR_INVALID_ARG:
                            // The addresses given from the RIL are bad
                            tearDownData(cp);
                            transitionTo(mDisconnectingErrorCreatingConnection);
                            break;
                        case ERROR_DATA_SERVICE_SPECIFIC_ERROR:

                            // Retrieve the suggested retry delay from the modem and save it.
                            // If the modem want us to retry the current APN again, it will
                            // suggest a positive delay value (in milliseconds). Otherwise we'll get
                            // NO_SUGGESTED_RETRY_DELAY here.

                            long delay = getSuggestedRetryDelay(dataCallResponse);
                            cp.mApnContext.setModemSuggestedDelay(delay);

                            String str = "DcActivatingState: ERROR_DATA_SERVICE_SPECIFIC_ERROR "
                                    + " delay=" + delay
                                    + " result=" + result
                                    + " result.isRestartRadioFail=" +
                                    result.mFailCause.isRestartRadioFail(mPhone.getContext(),
                                            mPhone.getSubId())
                                    + " isPermanentFailure=" +
                                    mDct.isPermanentFailure(result.mFailCause);
                            if (DBG) log(str);
                            if (cp.mApnContext != null) cp.mApnContext.requestLog(str);

                            // Save the cause. DcTracker.onDataSetupComplete will check this
                            // failure cause and determine if we need to retry this APN later
                            // or not.
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause);
                            transitionTo(mInactiveState);
                            break;
                        case ERROR_STALE:
                            loge("DcActivatingState: stale EVENT_SETUP_DATA_CONNECTION_DONE"
                                    + " tag:" + cp.mTag + " != mTag:" + mTag);
                            break;
                        default:
                            throw new RuntimeException("Unknown SetupResult, should not happen");
                    }
                    retVal = HANDLED;
                    break;
                default:
                    if (VDBG) {
                        log("DcActivatingState not handled msg.what=" +
                                getWhatToString(msg.what) + " RefCount=" + mApnContexts.size());
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActivatingState mActivatingState = new DcActivatingState();

    /**
     * The state machine is connected, expecting an EVENT_DISCONNECT.
     */
    private class DcActiveState extends State {

        @Override public void enter() {
            if (DBG) log("DcActiveState: enter dc=" + DataConnection.this);
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__ACTIVE,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.typesBitmap : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(PhoneConstants.APN_TYPE_DEFAULT) : false);

            updateNetworkInfo();

            // If we were retrying there maybe more than one, otherwise they'll only be one.
            notifyAllOfConnected(Phone.REASON_CONNECTED);

            mPhone.getCallTracker().registerForVoiceCallStarted(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_VOICE_CALL_STARTED, null);
            mPhone.getCallTracker().registerForVoiceCallEnded(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_VOICE_CALL_ENDED, null);

            // If the EVENT_CONNECT set the current max retry restore it here
            // if it didn't then this is effectively a NOP.
            mDcController.addActiveDcByCid(DataConnection.this);

            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED,
                    mNetworkInfo.getReason(), null);
            mNetworkInfo.setExtraInfo(mApnSetting.apn);
            updateTcpBufferSizes(mRilRat);

            final NetworkMisc misc = new NetworkMisc();
            final CarrierSignalAgent carrierSignalAgent = mPhone.getCarrierSignalAgent();
            if (carrierSignalAgent.hasRegisteredReceivers(TelephonyIntents
                    .ACTION_CARRIER_SIGNAL_REDIRECTED)) {
                // carrierSignal Receivers will place the carrier-specific provisioning notification
                misc.provisioningNotificationDisabled = true;
            }
            misc.subscriberId = mPhone.getSubscriberId();

            setNetworkRestriction();
            if (DBG) log("mRestrictedNetworkOverride = " + mRestrictedNetworkOverride);
            mNetworkAgent = new DcNetworkAgent(getHandler().getLooper(), mPhone.getContext(),
                    "DcNetworkAgent", mNetworkInfo, getNetworkCapabilities(), mLinkProperties,
                    50, misc);
            mPhone.mCi.registerForNattKeepaliveStatus(
                    getHandler(), DataConnection.EVENT_KEEPALIVE_STATUS, null);
            mPhone.mCi.registerForLceInfo(
                    getHandler(), DataConnection.EVENT_LINK_CAPACITY_CHANGED, null);
        }

        @Override
        public void exit() {
            if (DBG) log("DcActiveState: exit dc=" + this);
            String reason = mNetworkInfo.getReason();
            if(mDcController.isExecutingCarrierChange()) {
                reason = Phone.REASON_CARRIER_CHANGE;
            } else if (mDisconnectParams != null && mDisconnectParams.mReason != null) {
                reason = mDisconnectParams.mReason;
            } else if (mDcFailCause != null) {
                reason = mDcFailCause.toString();
            }
            mPhone.getCallTracker().unregisterForVoiceCallStarted(getHandler());
            mPhone.getCallTracker().unregisterForVoiceCallEnded(getHandler());

            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED,
                    reason, mNetworkInfo.getExtraInfo());
            mPhone.mCi.unregisterForNattKeepaliveStatus(getHandler());
            mPhone.mCi.unregisterForLceInfo(getHandler());
            if (mNetworkAgent != null) {
                mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                mNetworkAgent = null;
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT: {
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    // either add this new apn context to our set or
                    // update the existing cp with the latest connection generation number
                    mApnContexts.put(cp.mApnContext, cp);
                    if (DBG) {
                        log("DcActiveState: EVENT_CONNECT cp=" + cp + " dc=" + DataConnection.this);
                    }
                    notifyConnectCompleted(cp, DcFailCause.NONE, false);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT: {
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    if (DBG) {
                        log("DcActiveState: EVENT_DISCONNECT dp=" + dp
                                + " dc=" + DataConnection.this);
                    }
                    if (mApnContexts.containsKey(dp.mApnContext)) {
                        if (DBG) {
                            log("DcActiveState msg.what=EVENT_DISCONNECT RefCount="
                                    + mApnContexts.size());
                        }

                        if (mApnContexts.size() == 1) {
                            mApnContexts.clear();
                            mDisconnectParams = dp;
                            mConnectionParams = null;
                            dp.mTag = mTag;
                            tearDownData(dp);
                            transitionTo(mDisconnectingState);
                        } else {
                            mApnContexts.remove(dp.mApnContext);
                            notifyDisconnectCompleted(dp, false);
                        }
                    } else {
                        log("DcActiveState ERROR no such apnContext=" + dp.mApnContext
                                + " in this dc=" + DataConnection.this);
                        notifyDisconnectCompleted(dp, false);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT_ALL: {
                    if (DBG) {
                        log("DcActiveState EVENT_DISCONNECT clearing apn contexts,"
                                + " dc=" + DataConnection.this);
                    }
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    mDisconnectParams = dp;
                    mConnectionParams = null;
                    dp.mTag = mTag;
                    tearDownData(dp);
                    transitionTo(mDisconnectingState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_LOST_CONNECTION: {
                    if (DBG) {
                        log("DcActiveState EVENT_LOST_CONNECTION dc=" + DataConnection.this);
                    }

                    mInactiveState.setEnterNotificationParams(DcFailCause.LOST_CONNECTION);
                    transitionTo(mInactiveState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DATA_CONNECTION_ROAM_ON:
                case EVENT_DATA_CONNECTION_ROAM_OFF:
                case EVENT_DATA_CONNECTION_OVERRIDE_CHANGED: {
                    updateNetworkInfo();
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities());
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_BW_REFRESH_RESPONSE: {
                    AsyncResult ar = (AsyncResult)msg.obj;
                    if (ar.exception != null) {
                        log("EVENT_BW_REFRESH_RESPONSE: error ignoring, e=" + ar.exception);
                    } else {
                        final LinkCapacityEstimate lce = (LinkCapacityEstimate) ar.result;
                        NetworkCapabilities nc = getNetworkCapabilities();
                        if (mPhone.getLceStatus() == RILConstants.LCE_ACTIVE) {
                            nc.setLinkDownstreamBandwidthKbps(lce.downlinkCapacityKbps);
                            if (mNetworkAgent != null) {
                                mNetworkAgent.sendNetworkCapabilities(nc);
                            }
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DATA_CONNECTION_VOICE_CALL_STARTED:
                case EVENT_DATA_CONNECTION_VOICE_CALL_ENDED: {
                    updateNetworkInfo();
                    updateNetworkInfoSuspendState();
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(getNetworkCapabilities());
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_START_REQUEST: {
                    KeepalivePacketData pkt = (KeepalivePacketData) msg.obj;
                    int slotId = msg.arg1;
                    int intervalMillis = msg.arg2 * 1000;
                    if (mDataServiceManager.getTransportType()
                            == AccessNetworkConstants.TransportType.WWAN) {
                        mPhone.mCi.startNattKeepalive(
                                DataConnection.this.mCid, pkt, intervalMillis,
                                DataConnection.this.obtainMessage(
                                        EVENT_KEEPALIVE_STARTED, slotId, 0, null));
                    } else {
                        // We currently do not support NATT Keepalive requests using the
                        // DataService API, so unless the request is WWAN (always bound via
                        // the CommandsInterface), the request cannot be honored.
                        //
                        // TODO: b/72331356 to add support for Keepalive to the DataService
                        // so that keepalive requests can be handled (if supported) by the
                        // underlying transport.
                        if (mNetworkAgent != null) {
                            mNetworkAgent.onPacketKeepaliveEvent(
                                    msg.arg1,
                                    ConnectivityManager.PacketKeepalive.ERROR_INVALID_NETWORK);
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_STOP_REQUEST: {
                    int slotId = msg.arg1;
                    int handle = mNetworkAgent.keepaliveTracker.getHandleForSlot(slotId);
                    if (handle < 0) {
                        loge("No slot found for stopPacketKeepalive! " + slotId);
                        retVal = HANDLED;
                        break;
                    } else {
                        logd("Stopping keepalive with handle: " + handle);
                    }

                    mPhone.mCi.stopNattKeepalive(
                            handle, DataConnection.this.obtainMessage(
                                    EVENT_KEEPALIVE_STOPPED, handle, slotId, null));
                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_STARTED: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    final int slot = msg.arg1;
                    if (ar.exception != null || ar.result == null) {
                        loge("EVENT_KEEPALIVE_STARTED: error starting keepalive, e="
                                + ar.exception);
                        mNetworkAgent.onPacketKeepaliveEvent(
                                slot, ConnectivityManager.PacketKeepalive.ERROR_HARDWARE_ERROR);
                    } else {
                        KeepaliveStatus ks = (KeepaliveStatus) ar.result;
                        if (ks == null) {
                            loge("Null KeepaliveStatus received!");
                        } else {
                            mNetworkAgent.keepaliveTracker.handleKeepaliveStarted(slot, ks);
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_STATUS: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        loge("EVENT_KEEPALIVE_STATUS: error in keepalive, e=" + ar.exception);
                        // We have no way to notify connectivity in this case.
                    }
                    if (ar.result != null) {
                        KeepaliveStatus ks = (KeepaliveStatus) ar.result;
                        mNetworkAgent.keepaliveTracker.handleKeepaliveStatus(ks);
                    }

                    retVal = HANDLED;
                    break;
                }
                case EVENT_KEEPALIVE_STOPPED: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    final int handle = msg.arg1;
                    final int slotId = msg.arg2;

                    if (ar.exception != null) {
                        loge("EVENT_KEEPALIVE_STOPPED: error stopping keepalive for handle="
                                + handle + " e=" + ar.exception);
                        mNetworkAgent.keepaliveTracker.handleKeepaliveStatus(
                                new KeepaliveStatus(KeepaliveStatus.ERROR_UNKNOWN));
                    } else {
                        log("Keepalive Stop Requested for handle=" + handle);
                        mNetworkAgent.keepaliveTracker.handleKeepaliveStatus(
                                new KeepaliveStatus(handle, KeepaliveStatus.STATUS_INACTIVE));
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_LINK_CAPACITY_CHANGED: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        loge("EVENT_LINK_CAPACITY_CHANGED e=" + ar.exception);
                    } else {
                        LinkCapacityEstimate lce = (LinkCapacityEstimate) ar.result;
                        NetworkCapabilities nc = getNetworkCapabilities();
                        if (lce.downlinkCapacityKbps != LinkCapacityEstimate.INVALID) {
                            nc.setLinkDownstreamBandwidthKbps(lce.downlinkCapacityKbps);
                        }
                        if (lce.uplinkCapacityKbps != LinkCapacityEstimate.INVALID) {
                            nc.setLinkUpstreamBandwidthKbps(lce.uplinkCapacityKbps);
                        }
                        if (mNetworkAgent != null) {
                            mNetworkAgent.sendNetworkCapabilities(nc);
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("DcActiveState not handled msg.what=" + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActiveState mActiveState = new DcActiveState();

    /**
     * The state machine is disconnecting.
     */
    private class DcDisconnectingState extends State {
        @Override
        public void enter() {
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__DISCONNECTING,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.typesBitmap : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(PhoneConstants.APN_TYPE_DEFAULT) : false);
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT:
                    if (DBG) log("DcDisconnectingState msg.what=EVENT_CONNECT. Defer. RefCount = "
                            + mApnContexts.size());
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_DEACTIVATE_DONE:
                    DisconnectParams dp = (DisconnectParams) msg.obj;

                    String str = "DcDisconnectingState msg.what=EVENT_DEACTIVATE_DONE RefCount="
                            + mApnContexts.size();
                    if (DBG) log(str);
                    if (dp.mApnContext != null) dp.mApnContext.requestLog(str);

                    if (dp.mTag == mTag) {
                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(dp);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) log("DcDisconnectState stale EVENT_DEACTIVATE_DONE"
                                + " dp.tag=" + dp.mTag + " mTag=" + mTag);
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectingState not handled msg.what="
                                + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectingState mDisconnectingState = new DcDisconnectingState();

    /**
     * The state machine is disconnecting after an creating a connection.
     */
    private class DcDisconnectionErrorCreatingConnection extends State {
        @Override
        public void enter() {
            StatsLog.write(StatsLog.MOBILE_CONNECTION_STATE_CHANGED,
                    StatsLog.MOBILE_CONNECTION_STATE_CHANGED__STATE__DISCONNECTION_ERROR_CREATING_CONNECTION,
                    mPhone.getPhoneId(), mId,
                    mApnSetting != null ? (long) mApnSetting.typesBitmap : 0L,
                    mApnSetting != null
                        ? mApnSetting.canHandleType(PhoneConstants.APN_TYPE_DEFAULT) : false);
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_DEACTIVATE_DONE:
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    if (cp.mTag == mTag) {
                        String str = "DcDisconnectionErrorCreatingConnection" +
                                " msg.what=EVENT_DEACTIVATE_DONE";
                        if (DBG) log(str);
                        if (cp.mApnContext != null) cp.mApnContext.requestLog(str);

                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(cp,
                                DcFailCause.UNACCEPTABLE_NETWORK_PARAMETER);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) {
                            log("DcDisconnectionErrorCreatingConnection stale EVENT_DEACTIVATE_DONE"
                                    + " dp.tag=" + cp.mTag + ", mTag=" + mTag);
                        }
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectionErrorCreatingConnection not handled msg.what="
                                + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectionErrorCreatingConnection mDisconnectingErrorCreatingConnection =
                new DcDisconnectionErrorCreatingConnection();


    private class DcNetworkAgent extends NetworkAgent {

        private NetworkCapabilities mNetworkCapabilities;

        public final DcKeepaliveTracker keepaliveTracker = new DcKeepaliveTracker();

        public DcNetworkAgent(Looper l, Context c, String TAG, NetworkInfo ni,
                NetworkCapabilities nc, LinkProperties lp, int score, NetworkMisc misc) {
            super(l, c, TAG, ni, nc, lp, score, misc);
            mNetCapsLocalLog.log("New network agent created. capabilities=" + nc);
            mNetworkCapabilities = nc;
        }

        @Override
        protected void unwanted() {
            if (mNetworkAgent != this) {
                log("DcNetworkAgent: unwanted found mNetworkAgent=" + mNetworkAgent +
                        ", which isn't me.  Aborting unwanted");
                return;
            }
            // this can only happen if our exit has been called - we're already disconnected
            if (mApnContexts == null) return;
            for (ConnectionParams cp : mApnContexts.values()) {
                final ApnContext apnContext = cp.mApnContext;
                final Pair<ApnContext, Integer> pair =
                        new Pair<ApnContext, Integer>(apnContext, cp.mConnectionGeneration);
                log("DcNetworkAgent: [unwanted]: disconnect apnContext=" + apnContext);
                Message msg = mDct.obtainMessage(DctConstants.EVENT_DISCONNECT_DONE, pair);
                DisconnectParams dp = new DisconnectParams(apnContext, apnContext.getReason(), msg);
                DataConnection.this.sendMessage(DataConnection.this.
                        obtainMessage(EVENT_DISCONNECT, dp));
            }
        }

        @Override
        protected void pollLceData() {
            if(mPhone.getLceStatus() == RILConstants.LCE_ACTIVE) {  // active LCE service
                mPhone.mCi.pullLceData(DataConnection.this.obtainMessage(EVENT_BW_REFRESH_RESPONSE));
            }
        }

        @Override
        protected void networkStatus(int status, String redirectUrl) {
            if(!TextUtils.isEmpty(redirectUrl)) {
                log("validation status: " + status + " with redirection URL: " + redirectUrl);
                /* its possible that we have multiple DataConnection with INTERNET_CAPABILITY
                   all fail the validation with the same redirection url, send CMD back to DCTracker
                   and let DcTracker to make the decision */
                Message msg = mDct.obtainMessage(DctConstants.EVENT_REDIRECTION_DETECTED,
                        redirectUrl);
                msg.sendToTarget();
            }
        }

        @Override
        public void sendNetworkCapabilities(NetworkCapabilities networkCapabilities) {
            if (!networkCapabilities.equals(mNetworkCapabilities)) {
                String logStr = "Changed from " + mNetworkCapabilities + " to "
                        + networkCapabilities + ", Data RAT="
                        + mPhone.getServiceState().getRilDataRadioTechnology()
                        + ", mApnSetting=" + mApnSetting;
                mNetCapsLocalLog.log(logStr);
                log(logStr);
                mNetworkCapabilities = networkCapabilities;
            }
            super.sendNetworkCapabilities(networkCapabilities);
        }

        @Override
        protected void startPacketKeepalive(Message msg) {
            DataConnection.this.obtainMessage(EVENT_KEEPALIVE_START_REQUEST,
                    msg.arg1, msg.arg2, msg.obj).sendToTarget();
        }

        @Override
        protected void stopPacketKeepalive(Message msg) {
            DataConnection.this.obtainMessage(EVENT_KEEPALIVE_STOP_REQUEST,
                    msg.arg1, msg.arg2, msg.obj).sendToTarget();
        }

        private class DcKeepaliveTracker {
            private class KeepaliveRecord {
                public int slotId;
                public int currentStatus;

                KeepaliveRecord(int slotId, int status) {
                    this.slotId = slotId;
                    this.currentStatus = status;
                }
            };

            private final SparseArray<KeepaliveRecord> mKeepalives = new SparseArray();

            int getHandleForSlot(int slotId) {
                for (int i = 0; i < mKeepalives.size(); i++) {
                    KeepaliveRecord kr = mKeepalives.valueAt(i);
                    if (kr.slotId == slotId) return mKeepalives.keyAt(i);
                }
                return -1;
            }

            int keepaliveStatusErrorToPacketKeepaliveError(int error) {
                switch(error) {
                    case KeepaliveStatus.ERROR_NONE:
                        return PacketKeepalive.SUCCESS;
                    case KeepaliveStatus.ERROR_UNSUPPORTED:
                        return PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED;
                    case KeepaliveStatus.ERROR_NO_RESOURCES:
                    case KeepaliveStatus.ERROR_UNKNOWN:
                    default:
                        return PacketKeepalive.ERROR_HARDWARE_ERROR;
                }
            }

            void handleKeepaliveStarted(final int slot, KeepaliveStatus ks) {
                switch (ks.statusCode) {
                    case KeepaliveStatus.STATUS_INACTIVE:
                        DcNetworkAgent.this.onPacketKeepaliveEvent(slot,
                                keepaliveStatusErrorToPacketKeepaliveError(ks.errorCode));
                        break;
                    case KeepaliveStatus.STATUS_ACTIVE:
                        DcNetworkAgent.this.onPacketKeepaliveEvent(
                                slot, PacketKeepalive.SUCCESS);
                        // fall through to add record
                    case KeepaliveStatus.STATUS_PENDING:
                        log("Adding keepalive handle="
                                + ks.sessionHandle + " slot = " + slot);
                        mKeepalives.put(ks.sessionHandle,
                                new KeepaliveRecord(
                                        slot, ks.statusCode));
                        break;
                    default:
                        loge("Invalid KeepaliveStatus Code: " + ks.statusCode);
                        break;
                }
            }

            void handleKeepaliveStatus(KeepaliveStatus ks) {
                final KeepaliveRecord kr;
                kr = mKeepalives.get(ks.sessionHandle);

                if (kr == null) {
                    // If there is no slot for the session handle, we received an event
                    // for a different data connection. This is not an error because the
                    // keepalive session events are broadcast to all listeners.
                    log("Discarding keepalive event for different data connection:" + ks);
                    return;
                }
                // Switch on the current state, to see what we do with the status update
                switch (kr.currentStatus) {
                    case KeepaliveStatus.STATUS_INACTIVE:
                        loge("Inactive Keepalive received status!");
                        DcNetworkAgent.this.onPacketKeepaliveEvent(
                                kr.slotId, PacketKeepalive.ERROR_HARDWARE_ERROR);
                        break;
                    case KeepaliveStatus.STATUS_PENDING:
                        switch (ks.statusCode) {
                            case KeepaliveStatus.STATUS_INACTIVE:
                                DcNetworkAgent.this.onPacketKeepaliveEvent(kr.slotId,
                                        keepaliveStatusErrorToPacketKeepaliveError(ks.errorCode));
                                kr.currentStatus = KeepaliveStatus.STATUS_INACTIVE;
                                mKeepalives.remove(ks.sessionHandle);
                                break;
                            case KeepaliveStatus.STATUS_ACTIVE:
                                log("Pending Keepalive received active status!");
                                kr.currentStatus = KeepaliveStatus.STATUS_ACTIVE;
                                DcNetworkAgent.this.onPacketKeepaliveEvent(
                                        kr.slotId, PacketKeepalive.SUCCESS);
                                break;
                            case KeepaliveStatus.STATUS_PENDING:
                                loge("Invalid unsolicied Keepalive Pending Status!");
                                break;
                            default:
                                loge("Invalid Keepalive Status received, " + ks.statusCode);
                        }
                        break;
                    case KeepaliveStatus.STATUS_ACTIVE:
                        switch (ks.statusCode) {
                            case KeepaliveStatus.STATUS_INACTIVE:
                                loge("Keepalive received stopped status!");
                                DcNetworkAgent.this.onPacketKeepaliveEvent(
                                        kr.slotId, PacketKeepalive.SUCCESS);
                                kr.currentStatus = KeepaliveStatus.STATUS_INACTIVE;
                                mKeepalives.remove(ks.sessionHandle);
                                break;
                            case KeepaliveStatus.STATUS_PENDING:
                            case KeepaliveStatus.STATUS_ACTIVE:
                                loge("Active Keepalive received invalid status!");
                                break;
                            default:
                                loge("Invalid Keepalive Status received, " + ks.statusCode);
                        }
                        break;
                    default:
                        loge("Invalid Keepalive Status received, " + kr.currentStatus);
                }
            }
        };
    }

    // ******* "public" interface

    /**
     * Used for testing purposes.
     */
    /* package */ void tearDownNow() {
        if (DBG) log("tearDownNow()");
        sendMessage(obtainMessage(EVENT_TEAR_DOWN_NOW));
    }

    /**
     * Using the result of the SETUP_DATA_CALL determine the retry delay.
     *
     * @param response The response from setup data call
     * @return NO_SUGGESTED_RETRY_DELAY if no retry is needed otherwise the delay to the
     *         next SETUP_DATA_CALL
     */
    private long getSuggestedRetryDelay(DataCallResponse response) {
        /** According to ril.h
         * The value < 0 means no value is suggested
         * The value 0 means retry should be done ASAP.
         * The value of Integer.MAX_VALUE(0x7fffffff) means no retry.
         */

        // The value < 0 means no value is suggested
        if (response.getSuggestedRetryTime() < 0) {
            if (DBG) log("No suggested retry delay.");
            return RetryManager.NO_SUGGESTED_RETRY_DELAY;
        }
        // The value of Integer.MAX_VALUE(0x7fffffff) means no retry.
        else if (response.getSuggestedRetryTime() == Integer.MAX_VALUE) {
            if (DBG) log("Modem suggested not retrying.");
            return RetryManager.NO_RETRY;
        }

        // We need to cast it to long because the value returned from RIL is a 32-bit integer,
        // but the time values used in AlarmManager are all 64-bit long.
        return (long) response.getSuggestedRetryTime();
    }

    /**
     * @return the string for msg.what as our info.
     */
    @Override
    protected String getWhatToString(int what) {
        return cmdToString(what);
    }

    private static String msgToString(Message msg) {
        String retVal;
        if (msg == null) {
            retVal = "null";
        } else {
            StringBuilder   b = new StringBuilder();

            b.append("{what=");
            b.append(cmdToString(msg.what));

            b.append(" when=");
            TimeUtils.formatDuration(msg.getWhen() - SystemClock.uptimeMillis(), b);

            if (msg.arg1 != 0) {
                b.append(" arg1=");
                b.append(msg.arg1);
            }

            if (msg.arg2 != 0) {
                b.append(" arg2=");
                b.append(msg.arg2);
            }

            if (msg.obj != null) {
                b.append(" obj=");
                b.append(msg.obj);
            }

            b.append(" target=");
            b.append(msg.getTarget());

            b.append(" replyTo=");
            b.append(msg.replyTo);

            b.append("}");

            retVal = b.toString();
        }
        return retVal;
    }

    static void slog(String s) {
        Rlog.d("DC", s);
    }

    /**
     * Log with debug
     *
     * @param s is string log
     */
    @Override
    protected void log(String s) {
        Rlog.d(getName(), s);
    }

    /**
     * Log with debug attribute
     *
     * @param s is string log
     */
    @Override
    protected void logd(String s) {
        Rlog.d(getName(), s);
    }

    /**
     * Log with verbose attribute
     *
     * @param s is string log
     */
    @Override
    protected void logv(String s) {
        Rlog.v(getName(), s);
    }

    /**
     * Log with info attribute
     *
     * @param s is string log
     */
    @Override
    protected void logi(String s) {
        Rlog.i(getName(), s);
    }

    /**
     * Log with warning attribute
     *
     * @param s is string log
     */
    @Override
    protected void logw(String s) {
        Rlog.w(getName(), s);
    }

    /**
     * Log with error attribute
     *
     * @param s is string log
     */
    @Override
    protected void loge(String s) {
        Rlog.e(getName(), s);
    }

    /**
     * Log with error attribute
     *
     * @param s is string log
     * @param e is a Throwable which logs additional information.
     */
    @Override
    protected void loge(String s, Throwable e) {
        Rlog.e(getName(), s, e);
    }

    /** Doesn't print mApnList of ApnContext's which would be recursive */
    public String toStringSimple() {
        return getName() + ": State=" + getCurrentState().getName()
                + " mApnSetting=" + mApnSetting + " RefCount=" + mApnContexts.size()
                + " mCid=" + mCid + " mCreateTime=" + mCreateTime
                + " mLastastFailTime=" + mLastFailTime
                + " mLastFailCause=" + mLastFailCause
                + " mTag=" + mTag
                + " mLinkProperties=" + mLinkProperties
                + " linkCapabilities=" + getNetworkCapabilities()
                + " mRestrictedNetworkOverride=" + mRestrictedNetworkOverride;
    }

    @Override
    public String toString() {
        return "{" + toStringSimple() + " mApnContexts=" + mApnContexts + "}";
    }

    private void dumpToLog() {
        dump(null, new PrintWriter(new StringWriter(0)) {
            @Override
            public void println(String s) {
                DataConnection.this.logd(s);
            }

            @Override
            public void flush() {
            }
        }, null);
    }

    /**
     * Dump the current state.
     *
     * @param fd
     * @param pw
     * @param args
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, " ");
        pw.print("DataConnection ");
        super.dump(fd, pw, args);
        pw.flush();
        pw.increaseIndent();
        pw.println("mApnContexts.size=" + mApnContexts.size());
        pw.println("mApnContexts=" + mApnContexts);
        pw.println("mDataConnectionTracker=" + mDct);
        pw.println("mApnSetting=" + mApnSetting);
        pw.println("mTag=" + mTag);
        pw.println("mCid=" + mCid);
        pw.println("mConnectionParams=" + mConnectionParams);
        pw.println("mDisconnectParams=" + mDisconnectParams);
        pw.println("mDcFailCause=" + mDcFailCause);
        pw.println("mPhone=" + mPhone);
        pw.println("mLinkProperties=" + mLinkProperties);
        pw.flush();
        pw.println("mDataRegState=" + mDataRegState);
        pw.println("mRilRat=" + mRilRat);
        pw.println("mNetworkCapabilities=" + getNetworkCapabilities());
        pw.println("mCreateTime=" + TimeUtils.logTimeOfDay(mCreateTime));
        pw.println("mLastFailTime=" + TimeUtils.logTimeOfDay(mLastFailTime));
        pw.println("mLastFailCause=" + mLastFailCause);
        pw.println("mUserData=" + mUserData);
        pw.println("mSubscriptionOverride=" + Integer.toHexString(mSubscriptionOverride));
        pw.println("mInstanceNumber=" + mInstanceNumber);
        pw.println("mAc=" + mAc);
        pw.println("Network capabilities changed history:");
        pw.increaseIndent();
        mNetCapsLocalLog.dump(fd, pw, args);
        pw.decreaseIndent();
        pw.decreaseIndent();
        pw.println();
        pw.flush();
    }
}


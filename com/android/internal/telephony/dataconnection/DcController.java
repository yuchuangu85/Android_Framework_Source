/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;
import android.hardware.radio.V1_4.DataConnActiveStatus;
import android.net.INetworkPolicyListener;
import android.net.LinkAddress;
import android.net.LinkProperties.CompareResult;
import android.net.NetworkPolicyManager;
import android.net.NetworkUtils;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.telephony.AccessNetworkConstants;
import android.telephony.DataFailCause;
import android.telephony.PhoneStateListener;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.telephony.data.DataCallResponse;

import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.dataconnection.DataConnection.UpdateLinkPropertyResult;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Data Connection Controller which is a package visible class and controls
 * multiple data connections. For instance listening for unsolicited messages
 * and then demultiplexing them to the appropriate DC.
 */
public class DcController extends StateMachine {
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private final Phone mPhone;
    private final DcTracker mDct;
    private final DataServiceManager mDataServiceManager;
    private final DcTesterDeactivateAll mDcTesterDeactivateAll;

    // package as its used by Testing code
    // @GuardedBy("mDcListAll")
    final ArrayList<DataConnection> mDcListAll = new ArrayList<>();
    // @GuardedBy("mDcListAll")
    private final HashMap<Integer, DataConnection> mDcListActiveByCid = new HashMap<>();

    private DccDefaultState mDccDefaultState = new DccDefaultState();

    final TelephonyManager mTelephonyManager;
    final NetworkPolicyManager mNetworkPolicyManager;

    private PhoneStateListener mPhoneStateListener;

    //mExecutingCarrierChange tracks whether the phone is currently executing
    //carrier network change
    private volatile boolean mExecutingCarrierChange;

    /**
     * Constructor.
     *
     * @param name to be used for the Controller
     * @param phone the phone associated with Dcc and Dct
     * @param dct the DataConnectionTracker associated with Dcc
     * @param dataServiceManager the data service manager that manages data services
     * @param handler defines the thread/looper to be used with Dcc
     */
    private DcController(String name, Phone phone, DcTracker dct,
                         DataServiceManager dataServiceManager, Handler handler) {
        super(name, handler);
        setLogRecSize(300);
        log("E ctor");
        mPhone = phone;
        mDct = dct;
        mDataServiceManager = dataServiceManager;
        addState(mDccDefaultState);
        setInitialState(mDccDefaultState);
        log("X ctor");

        mPhoneStateListener = new PhoneStateListener(handler.getLooper()) {
            @Override
            public void onCarrierNetworkChange(boolean active) {
                mExecutingCarrierChange = active;
            }
        };

        mTelephonyManager = (TelephonyManager) phone.getContext()
                .getSystemService(Context.TELEPHONY_SERVICE);
        mNetworkPolicyManager = (NetworkPolicyManager) phone.getContext()
                .getSystemService(Context.NETWORK_POLICY_SERVICE);

        mDcTesterDeactivateAll = (Build.IS_DEBUGGABLE)
                ? new DcTesterDeactivateAll(mPhone, DcController.this, getHandler())
                : null;

        if (mTelephonyManager != null) {
            mTelephonyManager.listen(mPhoneStateListener,
                    PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE);
        }
    }

    public static DcController makeDcc(Phone phone, DcTracker dct,
                                       DataServiceManager dataServiceManager, Handler handler,
                                       String tagSuffix) {
        return new DcController("Dcc" + tagSuffix, phone, dct, dataServiceManager, handler);
    }

    void dispose() {
        log("dispose: call quiteNow()");
        if(mTelephonyManager != null) mTelephonyManager.listen(mPhoneStateListener, 0);
        quitNow();
    }

    void addDc(DataConnection dc) {
        synchronized (mDcListAll) {
            mDcListAll.add(dc);
        }
    }

    void removeDc(DataConnection dc) {
        synchronized (mDcListAll) {
            mDcListActiveByCid.remove(dc.mCid);
            mDcListAll.remove(dc);
        }
    }

    public void addActiveDcByCid(DataConnection dc) {
        if (DBG && dc.mCid < 0) {
            log("addActiveDcByCid dc.mCid < 0 dc=" + dc);
        }
        synchronized (mDcListAll) {
            mDcListActiveByCid.put(dc.mCid, dc);
        }
    }

    public DataConnection getActiveDcByCid(int cid) {
        synchronized (mDcListAll) {
            return mDcListActiveByCid.get(cid);
        }
    }

    void removeActiveDcByCid(DataConnection dc) {
        synchronized (mDcListAll) {
            DataConnection removedDc = mDcListActiveByCid.remove(dc.mCid);
            if (DBG && removedDc == null) {
                log("removeActiveDcByCid removedDc=null dc=" + dc);
            }
        }
    }

    boolean isExecutingCarrierChange() {
        return mExecutingCarrierChange;
    }

    private final INetworkPolicyListener mListener = new NetworkPolicyManager.Listener() {
        @Override
        public void onSubscriptionOverride(int subId, int overrideMask, int overrideValue) {
            if (mPhone == null || mPhone.getSubId() != subId) return;

            final HashMap<Integer, DataConnection> dcListActiveByCid;
            synchronized (mDcListAll) {
                dcListActiveByCid = new HashMap<>(mDcListActiveByCid);
            }
            for (DataConnection dc : dcListActiveByCid.values()) {
                dc.onSubscriptionOverride(overrideMask, overrideValue);
            }
        }
    };

    private class DccDefaultState extends State {
        @Override
        public void enter() {
            if (mPhone != null && mDataServiceManager.getTransportType()
                    == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                mPhone.mCi.registerForRilConnected(getHandler(),
                        DataConnection.EVENT_RIL_CONNECTED, null);
            }

            mDataServiceManager.registerForDataCallListChanged(getHandler(),
                    DataConnection.EVENT_DATA_STATE_CHANGED);

            if (mNetworkPolicyManager != null) {
                mNetworkPolicyManager.registerListener(mListener);
            }
        }

        @Override
        public void exit() {
            if (mPhone != null & mDataServiceManager.getTransportType()
                    == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                mPhone.mCi.unregisterForRilConnected(getHandler());
            }
            mDataServiceManager.unregisterForDataCallListChanged(getHandler());

            if (mDcTesterDeactivateAll != null) {
                mDcTesterDeactivateAll.dispose();
            }
            if (mNetworkPolicyManager != null) {
                mNetworkPolicyManager.unregisterListener(mListener);
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case DataConnection.EVENT_RIL_CONNECTED:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        if (DBG) {
                            log("DccDefaultState: msg.what=EVENT_RIL_CONNECTED mRilVersion=" +
                                ar.result);
                        }
                    } else {
                        log("DccDefaultState: Unexpected exception on EVENT_RIL_CONNECTED");
                    }
                    break;

                case DataConnection.EVENT_DATA_STATE_CHANGED:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        onDataStateChanged((ArrayList<DataCallResponse>)ar.result);
                    } else {
                        log("DccDefaultState: EVENT_DATA_STATE_CHANGED:" +
                                    " exception; likely radio not available, ignore");
                    }
                    break;
            }
            return HANDLED;
        }

        /**
         * Process the new list of "known" Data Calls
         * @param dcsList as sent by RIL_UNSOL_DATA_CALL_LIST_CHANGED
         */
        private void onDataStateChanged(ArrayList<DataCallResponse> dcsList) {
            final ArrayList<DataConnection> dcListAll;
            final HashMap<Integer, DataConnection> dcListActiveByCid;
            synchronized (mDcListAll) {
                dcListAll = new ArrayList<>(mDcListAll);
                dcListActiveByCid = new HashMap<>(mDcListActiveByCid);
            }

            if (DBG) {
                lr("onDataStateChanged: dcsList=" + dcsList
                        + " dcListActiveByCid=" + dcListActiveByCid);
            }
            if (VDBG) {
                log("onDataStateChanged: mDcListAll=" + dcListAll);
            }

            // Create hashmap of cid to DataCallResponse
            HashMap<Integer, DataCallResponse> dataCallResponseListByCid =
                    new HashMap<Integer, DataCallResponse>();
            for (DataCallResponse dcs : dcsList) {
                dataCallResponseListByCid.put(dcs.getId(), dcs);
            }

            // Add a DC that is active but not in the
            // dcsList to the list of DC's to retry
            ArrayList<DataConnection> dcsToRetry = new ArrayList<DataConnection>();
            for (DataConnection dc : dcListActiveByCid.values()) {
                if (dataCallResponseListByCid.get(dc.mCid) == null) {
                    if (DBG) log("onDataStateChanged: add to retry dc=" + dc);
                    dcsToRetry.add(dc);
                }
            }
            if (DBG) log("onDataStateChanged: dcsToRetry=" + dcsToRetry);

            // Find which connections have changed state and send a notification or cleanup
            // and any that are in active need to be retried.
            ArrayList<ApnContext> apnsToCleanup = new ArrayList<ApnContext>();

            boolean isAnyDataCallDormant = false;
            boolean isAnyDataCallActive = false;

            for (DataCallResponse newState : dcsList) {

                DataConnection dc = dcListActiveByCid.get(newState.getId());
                if (dc == null) {
                    // UNSOL_DATA_CALL_LIST_CHANGED arrived before SETUP_DATA_CALL completed.
                    loge("onDataStateChanged: no associated DC yet, ignore");
                    continue;
                }

                List<ApnContext> apnContexts = dc.getApnContexts();
                if (apnContexts.size() == 0) {
                    if (DBG) loge("onDataStateChanged: no connected apns, ignore");
                } else {
                    // Determine if the connection/apnContext should be cleaned up
                    // or just a notification should be sent out.
                    if (DBG) {
                        log("onDataStateChanged: Found ConnId=" + newState.getId()
                                + " newState=" + newState.toString());
                    }
                    if (newState.getLinkStatus() == DataConnActiveStatus.INACTIVE) {
                        if (mDct.isCleanupRequired.get()) {
                            apnsToCleanup.addAll(apnContexts);
                            mDct.isCleanupRequired.set(false);
                        } else {
                            int failCause = DataFailCause.getFailCause(newState.getCause());
                            if (DataFailCause.isRadioRestartFailure(mPhone.getContext(), failCause,
                                        mPhone.getSubId())) {
                                if (DBG) {
                                    log("onDataStateChanged: X restart radio, failCause="
                                            + failCause);
                                }
                                mDct.sendRestartRadio();
                            } else if (mDct.isPermanentFailure(failCause)) {
                                if (DBG) {
                                    log("onDataStateChanged: inactive, add to cleanup list. "
                                            + "failCause=" + failCause);
                                }
                                apnsToCleanup.addAll(apnContexts);
                            } else {
                                if (DBG) {
                                    log("onDataStateChanged: inactive, add to retry list. "
                                            + "failCause=" + failCause);
                                }
                                dcsToRetry.add(dc);
                            }
                        }
                    } else {
                        // Its active so update the DataConnections link properties
                        UpdateLinkPropertyResult result = dc.updateLinkProperty(newState);
                        if (result.oldLp.equals(result.newLp)) {
                            if (DBG) log("onDataStateChanged: no change");
                        } else {
                            if (result.oldLp.isIdenticalInterfaceName(result.newLp)) {
                                if (! result.oldLp.isIdenticalDnses(result.newLp) ||
                                        ! result.oldLp.isIdenticalRoutes(result.newLp) ||
                                        ! result.oldLp.isIdenticalHttpProxy(result.newLp) ||
                                        ! result.oldLp.isIdenticalAddresses(result.newLp)) {
                                    // If the same address type was removed and
                                    // added we need to cleanup
                                    CompareResult<LinkAddress> car =
                                        result.oldLp.compareAddresses(result.newLp);
                                    if (DBG) {
                                        log("onDataStateChanged: oldLp=" + result.oldLp +
                                                " newLp=" + result.newLp + " car=" + car);
                                    }
                                    boolean needToClean = false;
                                    for (LinkAddress added : car.added) {
                                        for (LinkAddress removed : car.removed) {
                                            if (NetworkUtils.addressTypeMatches(
                                                    removed.getAddress(),
                                                    added.getAddress())) {
                                                needToClean = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (needToClean) {
                                        if (DBG) {
                                            log("onDataStateChanged: addr change,"
                                                    + " cleanup apns=" + apnContexts
                                                    + " oldLp=" + result.oldLp
                                                    + " newLp=" + result.newLp);
                                        }
                                        apnsToCleanup.addAll(apnContexts);
                                    } else {
                                        if (DBG) log("onDataStateChanged: simple change");

                                        for (ApnContext apnContext : apnContexts) {
                                            mPhone.notifyDataConnection(apnContext.getApnType());
                                        }
                                    }
                                } else {
                                    if (DBG) {
                                        log("onDataStateChanged: no changes");
                                    }
                                }
                            } else {
                                apnsToCleanup.addAll(apnContexts);
                                if (DBG) {
                                    log("onDataStateChanged: interface change, cleanup apns="
                                            + apnContexts);
                                }
                            }
                        }
                    }
                }

                if (newState.getLinkStatus() == DataConnActiveStatus.ACTIVE) {
                    isAnyDataCallActive = true;
                }
                if (newState.getLinkStatus() == DataConnActiveStatus.DORMANT) {
                    isAnyDataCallDormant = true;
                }
            }

            if (isAnyDataCallDormant && !isAnyDataCallActive) {
                // There is no way to indicate link activity per APN right now. So
                // Link Activity will be considered dormant only when all data calls
                // are dormant.
                // If a single data call is in dormant state and none of the data
                // calls are active broadcast overall link state as dormant.
                if (DBG) {
                    log("onDataStateChanged: Data Activity updated to DORMANT. stopNetStatePoll");
                }
                mDct.sendStopNetStatPoll(DctConstants.Activity.DORMANT);
            } else {
                if (DBG) {
                    log("onDataStateChanged: Data Activity updated to NONE. " +
                            "isAnyDataCallActive = " + isAnyDataCallActive +
                            " isAnyDataCallDormant = " + isAnyDataCallDormant);
                }
                if (isAnyDataCallActive) {
                    mDct.sendStartNetStatPoll(DctConstants.Activity.NONE);
                }
            }

            if (DBG) {
                lr("onDataStateChanged: dcsToRetry=" + dcsToRetry
                        + " apnsToCleanup=" + apnsToCleanup);
            }

            // Cleanup connections that have changed
            for (ApnContext apnContext : apnsToCleanup) {
                mDct.cleanUpConnection(apnContext);
            }

            // Retry connections that have disappeared
            for (DataConnection dc : dcsToRetry) {
                if (DBG) log("onDataStateChanged: send EVENT_LOST_CONNECTION dc.mTag=" + dc.mTag);
                dc.sendMessage(DataConnection.EVENT_LOST_CONNECTION, dc.mTag);
            }

            if (VDBG) log("onDataStateChanged: X");
        }
    }

    /**
     * lr is short name for logAndAddLogRec
     * @param s
     */
    private void lr(String s) {
        logAndAddLogRec(s);
    }

    @Override
    protected void log(String s) {
        Rlog.d(getName(), s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(getName(), s);
    }

    /**
     * @return the string for msg.what as our info.
     */
    @Override
    protected String getWhatToString(int what) {
        String info = null;
        info = DataConnection.cmdToString(what);
        return info;
    }

    @Override
    public String toString() {
        synchronized (mDcListAll) {
            return "mDcListAll=" + mDcListAll + " mDcListActiveByCid=" + mDcListActiveByCid;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println(" mPhone=" + mPhone);
        synchronized (mDcListAll) {
            pw.println(" mDcListAll=" + mDcListAll);
            pw.println(" mDcListActiveByCid=" + mDcListActiveByCid);
        }
    }
}

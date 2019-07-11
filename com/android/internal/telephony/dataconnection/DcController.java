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

import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.LinkProperties.CompareResult;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.Rlog;

import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.dataconnection.DataConnection.UpdateLinkPropertyResult;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Data Connection Controller which is a package visible class and controls
 * multiple data connections. For instance listening for unsolicited messages
 * and then demultiplexing them to the appropriate DC.
 */
class DcController extends StateMachine {
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private PhoneBase mPhone;
    private DcTrackerBase mDct;
    private DcTesterDeactivateAll mDcTesterDeactivateAll;

    // package as its used by Testing code
    ArrayList<DataConnection> mDcListAll = new ArrayList<DataConnection>();
    private HashMap<Integer, DataConnection> mDcListActiveByCid =
            new HashMap<Integer, DataConnection>();

    /**
     * Constants for the data connection activity:
     * physical link down/up
     *
     * TODO: Move to RILConstants.java
     */
    static final int DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE = 0;
    static final int DATA_CONNECTION_ACTIVE_PH_LINK_DORMANT = 1;
    static final int DATA_CONNECTION_ACTIVE_PH_LINK_UP = 2;
    static final int DATA_CONNECTION_ACTIVE_UNKNOWN = Integer.MAX_VALUE;

    // One of the DATA_CONNECTION_ACTIVE_XXX values
    int mOverallDataConnectionActiveState = DATA_CONNECTION_ACTIVE_UNKNOWN;

    private DccDefaultState mDccDefaultState = new DccDefaultState();

    /**
     * Constructor.
     *
     * @param name to be used for the Controller
     * @param phone the phone associated with Dcc and Dct
     * @param dct the DataConnectionTracker associated with Dcc
     * @param handler defines the thread/looper to be used with Dcc
     */
    private DcController(String name, PhoneBase phone, DcTrackerBase dct,
            Handler handler) {
        super(name, handler);
        setLogRecSize(300);
        log("E ctor");
        mPhone = phone;
        mDct = dct;
        addState(mDccDefaultState);
        setInitialState(mDccDefaultState);
        log("X ctor");
    }

    static DcController makeDcc(PhoneBase phone, DcTrackerBase dct, Handler handler) {
        DcController dcc = new DcController("Dcc", phone, dct, handler);
        dcc.start();
        return dcc;
    }

    void dispose() {
        log("dispose: call quiteNow()");
        quitNow();
    }

    void addDc(DataConnection dc) {
        mDcListAll.add(dc);
    }

    void removeDc(DataConnection dc) {
        mDcListActiveByCid.remove(dc.mCid);
        mDcListAll.remove(dc);
    }

    void addActiveDcByCid(DataConnection dc) {
        if (DBG && dc.mCid < 0) {
            log("addActiveDcByCid dc.mCid < 0 dc=" + dc);
        }
        mDcListActiveByCid.put(dc.mCid, dc);
    }

    void removeActiveDcByCid(DataConnection dc) {
        DataConnection removedDc = mDcListActiveByCid.remove(dc.mCid);
        if (DBG && removedDc == null) {
            log("removeActiveDcByCid removedDc=null dc=" + dc);
        }
    }

    private class DccDefaultState extends State {
        @Override
        public void enter() {
            mPhone.mCi.registerForRilConnected(getHandler(),
                    DataConnection.EVENT_RIL_CONNECTED, null);
            mPhone.mCi.registerForDataNetworkStateChanged(getHandler(),
                    DataConnection.EVENT_DATA_STATE_CHANGED, null);
            if (Build.IS_DEBUGGABLE) {
                mDcTesterDeactivateAll =
                        new DcTesterDeactivateAll(mPhone, DcController.this, getHandler());
            }
        }

        @Override
        public void exit() {
            if (mPhone != null) {
                mPhone.mCi.unregisterForRilConnected(getHandler());
                mPhone.mCi.unregisterForDataNetworkStateChanged(getHandler());
            }
            if (mDcTesterDeactivateAll != null) {
                mDcTesterDeactivateAll.dispose();
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
            if (DBG) {
                lr("onDataStateChanged: dcsList=" + dcsList
                        + " mDcListActiveByCid=" + mDcListActiveByCid);
            }
            if (VDBG) {
                log("onDataStateChanged: mDcListAll=" + mDcListAll);
            }

            // Create hashmap of cid to DataCallResponse
            HashMap<Integer, DataCallResponse> dataCallResponseListByCid =
                    new HashMap<Integer, DataCallResponse>();
            for (DataCallResponse dcs : dcsList) {
                dataCallResponseListByCid.put(dcs.cid, dcs);
            }

            // Add a DC that is active but not in the
            // dcsList to the list of DC's to retry
            ArrayList<DataConnection> dcsToRetry = new ArrayList<DataConnection>();
            for (DataConnection dc : mDcListActiveByCid.values()) {
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

                DataConnection dc = mDcListActiveByCid.get(newState.cid);
                if (dc == null) {
                    // UNSOL_DATA_CALL_LIST_CHANGED arrived before SETUP_DATA_CALL completed.
                    loge("onDataStateChanged: no associated DC yet, ignore");
                    continue;
                }

                if (dc.mApnContexts.size() == 0) {
                    if (DBG) loge("onDataStateChanged: no connected apns, ignore");
                } else {
                    // Determine if the connection/apnContext should be cleaned up
                    // or just a notification should be sent out.
                    if (DBG) log("onDataStateChanged: Found ConnId=" + newState.cid
                            + " newState=" + newState.toString());
                    if (newState.active == DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE) {
                        if (mDct.mIsCleanupRequired) {
                            apnsToCleanup.addAll(dc.mApnContexts);
                            mDct.mIsCleanupRequired = false;
                        } else {
                            DcFailCause failCause = DcFailCause.fromInt(newState.status);
                            if (DBG) log("onDataStateChanged: inactive failCause=" + failCause);
                            if (failCause.isRestartRadioFail()) {
                                if (DBG) log("onDataStateChanged: X restart radio");
                                mDct.sendRestartRadio();
                            } else if (mDct.isPermanentFail(failCause)) {
                                if (DBG) log("onDataStateChanged: inactive, add to cleanup list");
                                apnsToCleanup.addAll(dc.mApnContexts);
                            } else {
                                if (DBG) log("onDataStateChanged: inactive, add to retry list");
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
                                            log("onDataStateChanged: addr change," +
                                                    " cleanup apns=" + dc.mApnContexts +
                                                    " oldLp=" + result.oldLp +
                                                    " newLp=" + result.newLp);
                                        }
                                        apnsToCleanup.addAll(dc.mApnContexts);
                                    } else {
                                        if (DBG) log("onDataStateChanged: simple change");

                                        for (ApnContext apnContext : dc.mApnContexts) {
                                             mPhone.notifyDataConnection(
                                                 PhoneConstants.REASON_LINK_PROPERTIES_CHANGED,
                                                 apnContext.getApnType());
                                        }
                                    }
                                } else {
                                    if (DBG) {
                                        log("onDataStateChanged: no changes");
                                    }
                                }
                            } else {
                                apnsToCleanup.addAll(dc.mApnContexts);
                                if (DBG) {
                                    log("onDataStateChanged: interface change, cleanup apns="
                                            + dc.mApnContexts);
                                }
                            }
                        }
                    }
                }

                if (newState.active == DATA_CONNECTION_ACTIVE_PH_LINK_UP) {
                    isAnyDataCallActive = true;
                }
                if (newState.active == DATA_CONNECTION_ACTIVE_PH_LINK_DORMANT) {
                    isAnyDataCallDormant = true;
                }
            }

            int newOverallDataConnectionActiveState = mOverallDataConnectionActiveState;

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
                newOverallDataConnectionActiveState = DATA_CONNECTION_ACTIVE_PH_LINK_DORMANT;
            } else {
                if (DBG) {
                    log("onDataStateChanged: Data Activity updated to NONE. " +
                            "isAnyDataCallActive = " + isAnyDataCallActive +
                            " isAnyDataCallDormant = " + isAnyDataCallDormant);
                }
                if (isAnyDataCallActive) {
                    newOverallDataConnectionActiveState = DATA_CONNECTION_ACTIVE_PH_LINK_UP;
                    mDct.sendStartNetStatPoll(DctConstants.Activity.NONE);
                } else {
                    newOverallDataConnectionActiveState = DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE;
                }
            }

            // Temporary notification until RIL implementation is complete.
            if (mOverallDataConnectionActiveState != newOverallDataConnectionActiveState) {
                mOverallDataConnectionActiveState = newOverallDataConnectionActiveState;
                long time = SystemClock.elapsedRealtimeNanos();
                int dcPowerState;
                switch (mOverallDataConnectionActiveState) {
                    case DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE:
                    case DATA_CONNECTION_ACTIVE_PH_LINK_DORMANT:
                        dcPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
                        break;
                    case DATA_CONNECTION_ACTIVE_PH_LINK_UP:
                        dcPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH;
                        break;
                    default:
                        dcPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_UNKNOWN;
                        break;
                }
                DataConnectionRealTimeInfo dcRtInfo =
                        new DataConnectionRealTimeInfo(time , dcPowerState);
                log("onDataStateChanged: notify DcRtInfo changed dcRtInfo=" + dcRtInfo);
                mPhone.notifyDataConnectionRealTimeInfo(dcRtInfo);
            }

            if (DBG) {
                lr("onDataStateChanged: dcsToRetry=" + dcsToRetry
                        + " apnsToCleanup=" + apnsToCleanup);
            }

            // Cleanup connections that have changed
            for (ApnContext apnContext : apnsToCleanup) {
               mDct.sendCleanUpConnection(true, apnContext);
            }

            // Retry connections that have disappeared
            for (DataConnection dc : dcsToRetry) {
                if (DBG) log("onDataStateChanged: send EVENT_LOST_CONNECTION dc.mTag=" + dc.mTag);
                dc.sendMessage(DataConnection.EVENT_LOST_CONNECTION, dc.mTag);
            }

            if (DBG) log("onDataStateChanged: X");
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
        if (info == null) {
            info = DcAsyncChannel.cmdToString(what);
        }
        return info;
    }

    @Override
    public String toString() {
        return "mDcListAll=" + mDcListAll + " mDcListActiveByCid=" + mDcListActiveByCid;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println(" mPhone=" + mPhone);
        pw.println(" mDcListAll=" + mDcListAll);
        pw.println(" mDcListActiveByCid=" + mDcListActiveByCid);
    }
}

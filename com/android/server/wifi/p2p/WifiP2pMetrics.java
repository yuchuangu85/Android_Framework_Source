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

package com.android.server.wifi.p2p;

import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pGroupList;
import android.util.Log;

import com.android.server.wifi.Clock;
import com.android.server.wifi.nano.WifiMetricsProto.GroupEvent;
import com.android.server.wifi.nano.WifiMetricsProto.P2pConnectionEvent;
import com.android.server.wifi.nano.WifiMetricsProto.WifiP2pStats;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;

/**
 * Provides storage for wireless connectivity P2p metrics, as they are generated.
 * Metrics logged by this class include:
 *   Aggregated connection stats (num of connections, num of failures, ...)
 *   Discrete connection event stats (time, duration, failure codes, ...)
 */
public class WifiP2pMetrics {
    private static final String TAG = "WifiP2pMetrics";
    private static final boolean DBG = false;

    private static final int MAX_CONNECTION_EVENTS = 256;
    private static final int MAX_GROUP_EVENTS = 256;

    private Clock mClock;
    private final Object mLock = new Object();

    /**
     * Metrics are stored within an instance of the WifiP2pStats proto during runtime,
     * The P2pConnectionEvent and GroupEvent metrics are stored during runtime in member
     * lists of this WifiP2pMetrics class, with the final WifiLog proto being pieced
     * together at dump-time
     */
    private final WifiP2pStats mWifiP2pStatsProto =
            new WifiP2pStats();

    /**
     * Connection information that gets logged for every P2P connection attempt.
     */
    private final List<P2pConnectionEvent> mConnectionEventList =
            new ArrayList<>();

    /**
     * The latest started (but un-ended) connection attempt
     */
    private P2pConnectionEvent mCurrentConnectionEvent;

    /**
     * The latest started (but un-ended) connection attempt start time
     */
    private long mCurrentConnectionEventStartTime;

    /**
     * Group Session information that gets logged for every formed group.
     */
    private final List<GroupEvent> mGroupEventList =
            new ArrayList<>();

    /**
     * The latest started (but un-ended) group
     */
    private GroupEvent mCurrentGroupEvent;

    /**
     * The latest started (but un-ended) group start time
     */
    private long mCurrentGroupEventStartTime;

    /**
     * The latest started (but un-ended) group idle start time.
     * The group is idle if there is no connected client.
     */
    private long mCurrentGroupEventIdleStartTime;

    /**
     * The current number of persistent groups.
     * This should be persisted after a dump.
     */
    private int mNumPersistentGroup;

    public WifiP2pMetrics(Clock clock) {
        mClock = clock;

        mNumPersistentGroup = 0;
    }

    /**
     * Clear all WifiP2pMetrics, except for currentConnectionEvent.
     */
    public void clear() {
        synchronized (mLock) {
            mConnectionEventList.clear();
            if (mCurrentConnectionEvent != null) {
                mConnectionEventList.add(mCurrentConnectionEvent);
            }
            mGroupEventList.clear();
            if (mCurrentGroupEvent != null) {
                mGroupEventList.add(mCurrentGroupEvent);
            }
            mWifiP2pStatsProto.clear();
        }
    }

    /**
     * Put all metrics that were being tracked separately into mWifiP2pStatsProto
     */
    public WifiP2pStats consolidateProto() {
        synchronized (mLock) {
            mWifiP2pStatsProto.numPersistentGroup = mNumPersistentGroup;
            int connectionEventCount = mConnectionEventList.size();
            if (mCurrentConnectionEvent != null) {
                connectionEventCount--;
            }
            mWifiP2pStatsProto.connectionEvent =
                    new P2pConnectionEvent[connectionEventCount];
            for (int i = 0; i < connectionEventCount; i++) {
                mWifiP2pStatsProto.connectionEvent[i] = mConnectionEventList.get(i);
            }

            int groupEventCount = mGroupEventList.size();
            if (mCurrentGroupEvent != null) {
                groupEventCount--;
            }
            mWifiP2pStatsProto.groupEvent =
                    new GroupEvent[groupEventCount];
            for (int i = 0; i < groupEventCount; i++) {
                mWifiP2pStatsProto.groupEvent[i] = mGroupEventList.get(i);
            }
            return mWifiP2pStatsProto;
        }
    }

    /**
     * Dump all WifiP2pMetrics. Collects some metrics at this time.
     *
     * @param pw PrintWriter for writing dump to
     */
    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("WifiP2pMetrics:");
            pw.println("mConnectionEvents:");
            for (P2pConnectionEvent event : mConnectionEventList) {
                StringBuilder sb = new StringBuilder();
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(event.startTimeMillis);
                sb.append("startTime=");
                sb.append(event.startTimeMillis == 0 ? "            <null>" :
                        String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
                sb.append(", connectionType=");
                switch (event.connectionType) {
                    case P2pConnectionEvent.CONNECTION_FRESH:
                        sb.append("FRESH");
                        break;
                    case P2pConnectionEvent.CONNECTION_REINVOKE:
                        sb.append("REINVOKE");
                        break;
                    case P2pConnectionEvent.CONNECTION_LOCAL:
                        sb.append("LOCAL");
                        break;
                    case P2pConnectionEvent.CONNECTION_FAST:
                        sb.append("FAST");
                        break;
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", wpsMethod=");
                switch (event.wpsMethod) {
                    case P2pConnectionEvent.WPS_NA:
                        sb.append("NA");
                        break;
                    case P2pConnectionEvent.WPS_PBC:
                        sb.append("PBC");
                        break;
                    case P2pConnectionEvent.WPS_DISPLAY:
                        sb.append("DISPLAY");
                        break;
                    case P2pConnectionEvent.WPS_KEYPAD:
                        sb.append("KEYPAD");
                        break;
                    case P2pConnectionEvent.WPS_LABEL:
                        sb.append("LABLE");
                        break;
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", durationTakenToConnectMillis=");
                sb.append(event.durationTakenToConnectMillis);
                sb.append(", connectivityLevelFailureCode=");
                switch (event.connectivityLevelFailureCode) {
                    case P2pConnectionEvent.CLF_NONE:
                        sb.append("NONE");
                        break;
                    case P2pConnectionEvent.CLF_TIMEOUT:
                        sb.append("TIMEOUT");
                        break;
                    case P2pConnectionEvent.CLF_CANCEL:
                        sb.append("CANCEL");
                        break;
                    case P2pConnectionEvent.CLF_PROV_DISC_FAIL:
                        sb.append("PROV_DISC_FAIL");
                        break;
                    case P2pConnectionEvent.CLF_INVITATION_FAIL:
                        sb.append("INVITATION_FAIL");
                        break;
                    case P2pConnectionEvent.CLF_USER_REJECT:
                        sb.append("USER_REJECT");
                        break;
                    case P2pConnectionEvent.CLF_NEW_CONNECTION_ATTEMPT:
                        sb.append("NEW_CONNECTION_ATTEMPT");
                        break;
                    case P2pConnectionEvent.CLF_UNKNOWN:
                    default:
                        sb.append("UNKNOWN");
                        break;
                }

                if (event == mCurrentConnectionEvent) {
                    sb.append(" CURRENTLY OPEN EVENT");
                }
                pw.println(sb.toString());
            }
            pw.println("mGroupEvents:");
            for (GroupEvent event : mGroupEventList) {
                StringBuilder sb = new StringBuilder();
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(event.startTimeMillis);
                sb.append("netId=");
                sb.append(event.netId);
                sb.append(", startTime=");
                sb.append(event.startTimeMillis == 0 ? "            <null>" :
                        String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
                sb.append(", channelFrequency=");
                sb.append(event.channelFrequency);
                sb.append(", groupRole=");
                switch (event.groupRole) {
                    case GroupEvent.GROUP_CLIENT:
                        sb.append("GroupClient");
                        break;
                    case GroupEvent.GROUP_OWNER:
                    default:
                        sb.append("GroupOwner");
                        break;
                }
                sb.append(", numConnectedClients=");
                sb.append(event.numConnectedClients);
                sb.append(", numCumulativeClients=");
                sb.append(event.numCumulativeClients);
                sb.append(", sessionDurationMillis=");
                sb.append(event.sessionDurationMillis);
                sb.append(", idleDurationMillis=");
                sb.append(event.idleDurationMillis);

                if (event == mCurrentGroupEvent) {
                    sb.append(" CURRENTLY OPEN EVENT");
                }
                pw.println(sb.toString());
            }
            pw.println("mWifiP2pStatsProto.numPersistentGroup="
                    + mNumPersistentGroup);
            pw.println("mWifiP2pStatsProto.numTotalPeerScans="
                    + mWifiP2pStatsProto.numTotalPeerScans);
            pw.println("mWifiP2pStatsProto.numTotalServiceScans="
                    + mWifiP2pStatsProto.numTotalServiceScans);
        }
    }

    /** Increment total number of peer scans */
    public void incrementPeerScans() {
        synchronized (mLock) {
            mWifiP2pStatsProto.numTotalPeerScans++;
        }
    }

    /** Increment total number of service scans */
    public void incrementServiceScans() {
        synchronized (mLock) {
            mWifiP2pStatsProto.numTotalServiceScans++;
        }
    }

    /** Set the number of saved persistent group */
    public void updatePersistentGroup(WifiP2pGroupList groups) {
        synchronized (mLock) {
            final Collection<WifiP2pGroup> list = groups.getGroupList();
            mNumPersistentGroup = list.size();
        }
    }

    /**
     * Create a new connection event. Call when p2p attmpts to make a new connection to
     * another peer. If there is a current 'un-ended' connection event, it will be ended with
     * P2pConnectionEvent.CLF_NEW_CONNNECTION_ATTEMPT.
     *
     * @param connectionType indicate this connection is fresh or reinvoke.
     * @param config configuration used for this connection.
     */
    public void startConnectionEvent(int connectionType, WifiP2pConfig config) {
        synchronized (mLock) {
            // handle overlapping connection event first.
            if (mCurrentConnectionEvent != null) {
                endConnectionEvent(P2pConnectionEvent.CLF_NEW_CONNECTION_ATTEMPT);
            }

            while (mConnectionEventList.size() >= MAX_CONNECTION_EVENTS) {
                mConnectionEventList.remove(0);
            }
            mCurrentConnectionEventStartTime = mClock.getElapsedSinceBootMillis();

            mCurrentConnectionEvent = new P2pConnectionEvent();
            mCurrentConnectionEvent.startTimeMillis = mClock.getWallClockMillis();
            mCurrentConnectionEvent.connectionType = connectionType;
            if (config != null) {
                mCurrentConnectionEvent.wpsMethod = config.wps.setup;
            }

            mConnectionEventList.add(mCurrentConnectionEvent);
        }
    }

    /**
     * End a Connection event record. Call when p2p connection attempt succeeds or fails.
     * If a Connection event has not been started when .end is called,
     * a new one is created with zero duration.
     *
     * @param failure indicate the failure with WifiMetricsProto.P2pConnectionEvent.CLF_X.
     */
    public void endConnectionEvent(int failure) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent == null) {
                // Reinvoking a group with invitation will be handled in supplicant.
                // There won't be a connection starting event in framework.
                // THe framework only get the connection ending event in GroupStarted state.
                startConnectionEvent(P2pConnectionEvent.CONNECTION_REINVOKE, null);
            }

            mCurrentConnectionEvent.durationTakenToConnectMillis = (int)
                    (mClock.getElapsedSinceBootMillis()
                    - mCurrentConnectionEventStartTime);
            mCurrentConnectionEvent.connectivityLevelFailureCode = failure;

            mCurrentConnectionEvent = null;
        }
    }

    /**
     * Create a new group event.
     *
     * @param group the information of started group.
     */
    public void startGroupEvent(WifiP2pGroup group) {
        if (group == null) {
            if (DBG) Log.d(TAG, "Cannot start group event due to null group");
            return;
        }
        synchronized (mLock) {
            // handle overlapping group event first.
            if (mCurrentGroupEvent != null) {
                if (DBG) Log.d(TAG, "Overlapping group event!");
                endGroupEvent();
            }

            while (mGroupEventList.size() >= MAX_GROUP_EVENTS) {
                mGroupEventList.remove(0);
            }
            mCurrentGroupEventStartTime = mClock.getElapsedSinceBootMillis();
            if (group.getClientList().size() == 0) {
                mCurrentGroupEventIdleStartTime = mClock.getElapsedSinceBootMillis();
            } else {
                mCurrentGroupEventIdleStartTime = 0;
            }

            mCurrentGroupEvent = new GroupEvent();
            mCurrentGroupEvent.netId = group.getNetworkId();
            mCurrentGroupEvent.startTimeMillis = mClock.getWallClockMillis();
            mCurrentGroupEvent.numConnectedClients = group.getClientList().size();
            mCurrentGroupEvent.channelFrequency = group.getFrequency();
            mCurrentGroupEvent.groupRole = group.isGroupOwner()
                    ? GroupEvent.GROUP_OWNER
                    : GroupEvent.GROUP_CLIENT;
            mGroupEventList.add(mCurrentGroupEvent);
        }
    }

    /**
     * Update the information of started group.
     */
    public void updateGroupEvent(WifiP2pGroup group) {
        if (group == null) {
            if (DBG) Log.d(TAG, "Cannot update group event due to null group.");
            return;
        }
        synchronized (mLock) {
            if (mCurrentGroupEvent == null) {
                Log.w(TAG, "Cannot update group event due to no current group.");
                return;
            }

            if (mCurrentGroupEvent.netId != group.getNetworkId()) {
                Log.w(TAG, "Updating group id " + group.getNetworkId()
                        + " is different from current group id " + mCurrentGroupEvent.netId
                        + ".");
                return;
            }

            int delta = group.getClientList().size() - mCurrentGroupEvent.numConnectedClients;
            mCurrentGroupEvent.numConnectedClients = group.getClientList().size();
            if (delta > 0) {
                mCurrentGroupEvent.numCumulativeClients += delta;
            }

            // if new client comes during idle period, cumulate idle duration and reset idle timer.
            // if the last client disconnected during non-idle period, start idle timer.
            if (mCurrentGroupEventIdleStartTime > 0) {
                if (group.getClientList().size() > 0) {
                    mCurrentGroupEvent.idleDurationMillis +=
                            (mClock.getElapsedSinceBootMillis()
                            - mCurrentGroupEventIdleStartTime);
                    mCurrentGroupEventIdleStartTime = 0;
                }
            } else {
                if (group.getClientList().size() == 0) {
                    mCurrentGroupEventIdleStartTime = mClock.getElapsedSinceBootMillis();
                }
            }
        }
    }

    /**
     * End a group event.
     */
    public void endGroupEvent() {
        synchronized (mLock) {
            if (mCurrentGroupEvent != null) {
                mCurrentGroupEvent.sessionDurationMillis = (int)
                        (mClock.getElapsedSinceBootMillis()
                        - mCurrentGroupEventStartTime);
                if (mCurrentGroupEventIdleStartTime > 0) {
                    mCurrentGroupEvent.idleDurationMillis +=
                            (mClock.getElapsedSinceBootMillis()
                            - mCurrentGroupEventIdleStartTime);
                    mCurrentGroupEventIdleStartTime = 0;
                }
            } else {
                Log.e(TAG, "No current group!");
            }
            mCurrentGroupEvent = null;
        }
    }

    /* Log Metrics */
}

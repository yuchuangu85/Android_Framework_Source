/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.Clock;
import com.android.server.wifi.hotspot2.anqp.Constants;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class for managing sending of ANQP requests.  This manager will ignore ANQP requests for a
 * period of time (hold off time) to a specified AP if the previous request to that AP goes
 * unanswered or failed.  The hold off time will increase exponentially until the max is reached.
 */
public class ANQPRequestManager {
    private static final String TAG = "ANQPRequestManager";

    private final PasspointEventHandler mPasspointHandler;
    private final Clock mClock;

    /**
     * List of pending ANQP request associated with an AP (BSSID).
     */
    private final Map<Long, ANQPNetworkKey> mPendingQueries;

    /**
     * List of hold off time information associated with APs specified by their BSSID.
     * Used to determine when an ANQP request can be send to the corresponding AP after the
     * previous request goes unanswered or failed.
     */
    private final Map<Long, HoldOffInfo> mHoldOffInfo;

    /**
     * Minimum number of milliseconds to wait for before attempting ANQP queries to the same AP
     * after previous request goes unanswered or failed.
     */
    @VisibleForTesting
    public static final int BASE_HOLDOFF_TIME_MILLISECONDS = 10000;

    /**
     * Max value for the hold off counter for unanswered/failed queries.  This limits the maximum
     * hold off time to:
     * BASE_HOLDOFF_TIME_MILLISECONDS * 2^MAX_HOLDOFF_COUNT
     * which is 640 seconds.
     */
    @VisibleForTesting
    public static final int MAX_HOLDOFF_COUNT = 6;

    private static final List<Constants.ANQPElementType> R1_ANQP_BASE_SET = Arrays.asList(
            Constants.ANQPElementType.ANQPVenueName,
            Constants.ANQPElementType.ANQPIPAddrAvailability,
            Constants.ANQPElementType.ANQPNAIRealm,
            Constants.ANQPElementType.ANQP3GPPNetwork,
            Constants.ANQPElementType.ANQPDomName,
            Constants.ANQPElementType.HSFriendlyName,
            Constants.ANQPElementType.HSWANMetrics,
            Constants.ANQPElementType.HSConnCapability);

    private static final List<Constants.ANQPElementType> R2_ANQP_BASE_SET = Arrays.asList(
            Constants.ANQPElementType.HSOSUProviders);

    /**
     * Class to keep track of AP status for ANQP requests.
     */
    private class HoldOffInfo {
        /**
         * Current hold off count.  Will max out at {@link #MAX_HOLDOFF_COUNT}.
         */
        public int holdOffCount;
        /**
         * The time stamp in milliseconds when we're allow to send ANQP request to the
         * corresponding AP.
         */
        public long holdOffExpirationTime;
    }

    public ANQPRequestManager(PasspointEventHandler handler, Clock clock) {
        mPasspointHandler = handler;
        mClock = clock;
        mPendingQueries = new HashMap<>();
        mHoldOffInfo = new HashMap<>();
    }

    /**
     * Request ANQP elements from the specified AP.  This will request the basic Release 1 ANQP
     * elements {@link #R1_ANQP_BASE_SET}.  Additional elements will be requested based on the
     * information provided in the Information Element (Roaming Consortium OI count and the
     * supported Hotspot 2.0 release version).
     *
     * @param bssid The BSSID of the AP
     * @param anqpNetworkKey The unique network key associated with this request
     * @param rcOIs Flag indicating the inclusion of roaming consortium OIs. When set to true,
     *              Roaming Consortium ANQP element will be requested
     * @param hsReleaseVer Indicates Hotspot 2.0 Release version. When set to R2 or higher,
     *              the Release 2 ANQP elements {@link #R2_ANQP_BASE_SET} will be requested
     * @return true if a request was sent successfully
     */
    public boolean requestANQPElements(long bssid, ANQPNetworkKey anqpNetworkKey, boolean rcOIs,
            NetworkDetail.HSRelease hsReleaseVer) {
        // Check if we are allow to send the request now.
        if (!canSendRequestNow(bssid)) {
            return false;
        }

        // No need to hold off future requests for send failures.
        if (!mPasspointHandler.requestANQP(bssid, getRequestElementIDs(rcOIs, hsReleaseVer))) {
            return false;
        }

        // Update hold off info on when we are allowed to send the next ANQP request to
        // the given AP.
        updateHoldOffInfo(bssid);

        mPendingQueries.put(bssid, anqpNetworkKey);
        return true;
    }

    /**
     * Notification of the completion of an ANQP request.
     *
     * @param bssid The BSSID of the AP
     * @param success Flag indicating the result of the query
     * @return {@link ANQPNetworkKey} associated with the completed request
     */
    public ANQPNetworkKey onRequestCompleted(long bssid, boolean success) {
        if (success) {
            // Query succeeded.  No need to hold off request to the given AP.
            mHoldOffInfo.remove(bssid);
        }
        return mPendingQueries.remove(bssid);
    }

    /**
     * Check if we are allowed to send ANQP request to the specified AP now.
     *
     * @param bssid The BSSID of an AP
     * @return true if we are allowed to send the request now
     */
    private boolean canSendRequestNow(long bssid) {
        long currentTime = mClock.getElapsedSinceBootMillis();
        HoldOffInfo info = mHoldOffInfo.get(bssid);
        if (info != null && info.holdOffExpirationTime > currentTime) {
            Log.d(TAG, "Not allowed to send ANQP request to " + Utils.macToString(bssid)
                    + " for another " + (info.holdOffExpirationTime - currentTime) / 1000
                    + " seconds");
            return false;
        }

        return true;
    }

    /**
     * Update the ANQP request hold off info associated with the given AP.
     *
     * @param bssid The BSSID of an AP
     */
    private void updateHoldOffInfo(long bssid) {
        HoldOffInfo info = mHoldOffInfo.get(bssid);
        if (info == null) {
            info = new HoldOffInfo();
            mHoldOffInfo.put(bssid, info);
        }
        info.holdOffExpirationTime = mClock.getElapsedSinceBootMillis()
                + BASE_HOLDOFF_TIME_MILLISECONDS * (1 << info.holdOffCount);
        if (info.holdOffCount < MAX_HOLDOFF_COUNT) {
            info.holdOffCount++;
        }
    }

    /**
     * Get the list of ANQP element IDs to request based on the Hotspot 2.0 release number
     * and the ANQP OI count indicated in the Information Element.
     *
     * @param rcOIs Flag indicating the inclusion of roaming consortium OIs
     * @param hsRelease Hotspot 2.0 Release version of the AP
     * @return List of ANQP Element ID
     */
    private static List<Constants.ANQPElementType> getRequestElementIDs(boolean rcOIs,
            NetworkDetail.HSRelease hsRelease) {
        List<Constants.ANQPElementType> requestList = new ArrayList<>();
        requestList.addAll(R1_ANQP_BASE_SET);
        if (rcOIs) {
            requestList.add(Constants.ANQPElementType.ANQPRoamingConsortium);
        }

        if (hsRelease == NetworkDetail.HSRelease.R1) {
            // Return R1 ANQP request list
            return requestList;
        }

        requestList.addAll(R2_ANQP_BASE_SET);

        // Return R2+ ANQP request list. This also includes the Unknown version, which may imply
        // a future version.
        return requestList;
    }

    /**
     * Dump the current state of ANQPRequestManager to the provided output stream.
     *
     * @param pw The output stream to write to
     */
    public void dump(PrintWriter pw) {
        pw.println("ANQPRequestManager - Begin ---");
        for (Map.Entry<Long, HoldOffInfo> holdOffInfo : mHoldOffInfo.entrySet()) {
            long bssid = holdOffInfo.getKey();
            pw.println("For BBSID: " + Utils.macToString(bssid));
            pw.println("holdOffCount: " + holdOffInfo.getValue().holdOffCount);
            pw.println("Not allowed to send ANQP request for another "
                    + (holdOffInfo.getValue().holdOffExpirationTime
                    - mClock.getElapsedSinceBootMillis()) / 1000 + " seconds");
        }
        pw.println("ANQPRequestManager - End ---");
    }

    /**
     * Clear all pending ANQP requests
     */
    public void clear() {
        mPendingQueries.clear();
        mHoldOffInfo.clear();
    }
}

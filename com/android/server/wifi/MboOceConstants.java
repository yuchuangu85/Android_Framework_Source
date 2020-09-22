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

package com.android.server.wifi;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * MBO-OCE related constants
 */
public class MboOceConstants {

    public static final int MBO_OCE_ATTRIBUTE_NOT_PRESENT = -1;

    /** MBO-OCE attribute Ids */
    public static final int MBO_OCE_AID_MBO_AP_CAPABILITY_INDICATION = 0x01;
    public static final int MBO_OCE_AID_NON_PREFERRED_CHANNEL_REPORT = 0x02;
    public static final int MBO_OCE_AID_CELLULAR_DATA_CAPABILITIES = 0x03;
    public static final int MBO_OCE_AID_ASSOCIATION_DISALLOWED = 0x04;
    public static final int MBO_OCE_AID_CELLULAR_DATA_CONNECTION_PREFERENCE = 0x05;
    public static final int MBO_OCE_AID_TRANSITION_REASON_CODE = 0x06;
    public static final int MBO_OCE_AID_TRANSITION_REJECTION_REASON_CODE = 0x07;
    public static final int MBO_OCE_AID_ASSOCIATION_RETRY_DELAY = 0x08;
    public static final int MBO_OCE_AID_OCE_AP_CAPABILITY_INDICATION = 0x65;
    public static final int MBO_OCE_AID_RSSI_BASED_ASSOCIATION_REJECTION = 0x66;
    public static final int MBO_OCE_AID_REDUCED_WAN_METRICS = 0x67;
    public static final int MBO_OCE_AID_RNR_COMPLETENESS = 0x68;
    public static final int MBO_OCE_AID_PROBE_SUPPRESSION_BSSIDS = 0x69;
    public static final int MBO_OCE_AID_PROBE_SUPPRESSION_SSIDS = 0x6A;

    @IntDef(prefix = { "MBO_OCE_AID_" }, value = {
            MBO_OCE_AID_MBO_AP_CAPABILITY_INDICATION,
            MBO_OCE_AID_NON_PREFERRED_CHANNEL_REPORT,
            MBO_OCE_AID_CELLULAR_DATA_CAPABILITIES,
            MBO_OCE_AID_ASSOCIATION_DISALLOWED,
            MBO_OCE_AID_CELLULAR_DATA_CONNECTION_PREFERENCE,
            MBO_OCE_AID_TRANSITION_REASON_CODE,
            MBO_OCE_AID_TRANSITION_REJECTION_REASON_CODE,
            MBO_OCE_AID_ASSOCIATION_RETRY_DELAY,
            MBO_OCE_AID_OCE_AP_CAPABILITY_INDICATION,
            MBO_OCE_AID_RSSI_BASED_ASSOCIATION_REJECTION,
            MBO_OCE_AID_REDUCED_WAN_METRICS,
            MBO_OCE_AID_RNR_COMPLETENESS,
            MBO_OCE_AID_PROBE_SUPPRESSION_BSSIDS,
            MBO_OCE_AID_PROBE_SUPPRESSION_SSIDS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MboOceAid{}

    /** MBO spec v1.2, 4.2.1 Table 7: MBO AP Capability indication - Cellular data aware */
    public static final int MBO_AP_CAP_IND_ATTR_CELL_DATA_AWARE = 0x40;

    /**
     * IEEE Std 802.11-2016 - Table 9-357.
     * BTM status code filled in BSS transition management response frame.
     */
    public static final int BTM_RESPONSE_STATUS_INVALID = -1;
    public static final int BTM_RESPONSE_STATUS_ACCEPT = 0;
    public static final int BTM_RESPONSE_STATUS_REJECT_UNSPECIFIED = 1;
    public static final int BTM_RESPONSE_STATUS_REJECT_INSUFFICIENT_BEACON = 2;
    public static final int BTM_RESPONSE_STATUS_REJECT_INSUFFICIENT_CAPABITY = 3;
    public static final int BTM_RESPONSE_STATUS_REJECT_BSS_TERMINATION_UNDESIRED = 4;
    public static final int BTM_RESPONSE_STATUS_REJECT_BSS_TERMINATION_DELAY_REQUEST = 5;
    public static final int BTM_RESPONSE_STATUS_REJECT_STA_CANDIDATE_LIST_PROVIDED = 6;
    public static final int BTM_RESPONSE_STATUS_REJECT_NO_SUITABLE_CANDIDATES = 7;
    public static final int BTM_RESPONSE_STATUS_REJECT_LEAVING_ESS = 8;
    public static final int BTM_RESPONSE_STATUS_REJECT_RESERVED = 254;

    @IntDef(prefix = { "BTM_RESPONSE_STATUS_" }, value = {
            BTM_RESPONSE_STATUS_INVALID,
            BTM_RESPONSE_STATUS_ACCEPT,
            BTM_RESPONSE_STATUS_REJECT_UNSPECIFIED,
            BTM_RESPONSE_STATUS_REJECT_INSUFFICIENT_BEACON,
            BTM_RESPONSE_STATUS_REJECT_INSUFFICIENT_CAPABITY,
            BTM_RESPONSE_STATUS_REJECT_BSS_TERMINATION_UNDESIRED,
            BTM_RESPONSE_STATUS_REJECT_BSS_TERMINATION_DELAY_REQUEST,
            BTM_RESPONSE_STATUS_REJECT_STA_CANDIDATE_LIST_PROVIDED,
            BTM_RESPONSE_STATUS_REJECT_NO_SUITABLE_CANDIDATES,
            BTM_RESPONSE_STATUS_REJECT_LEAVING_ESS,
            BTM_RESPONSE_STATUS_REJECT_RESERVED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface BtmResponseStatus{}

    /** WNM request mode: Preferred candidate list included */
    public static final int BTM_DATA_FLAG_PREFERRED_CANDIDATE_LIST_INCLUDED = 1 << 0;
    /** WNM request mode: Abridged */
    public static final int BTM_DATA_FLAG_MODE_ABRIDGED = 1 << 1;
    /** WNM request mode: Disassociation Imminent */
    public static final int BTM_DATA_FLAG_DISASSOCIATION_IMMINENT = 1 << 2;
    /** WNM request mode: BSS termination included */
    public static final int BTM_DATA_FLAG_BSS_TERMINATION_INCLUDED = 1 << 3;
    /** WNM request mode: ESS Disassociation Imminent */
    public static final int BTM_DATA_FLAG_ESS_DISASSOCIATION_IMMINENT = 1 << 4;
    /** MBO transition reason code included */
    public static final int BTM_DATA_FLAG_MBO_TRANSITION_REASON_CODE_INCLUDED = 1 << 5;
    /** MBO transition reason code included */
    public static final int BTM_DATA_FLAG_MBO_ASSOC_RETRY_DELAY_INCLUDED = 1 << 6;
    /** MBO cellular data connection preference value included */
    public static final int BTM_DATA_FLAG_MBO_CELL_DATA_CONNECTION_PREFERENCE_INCLUDED = 1 << 7;

    @IntDef(flag = true, prefix = { "BTM_DATA_FLAG_" }, value = {
            BTM_DATA_FLAG_PREFERRED_CANDIDATE_LIST_INCLUDED,
            BTM_DATA_FLAG_MODE_ABRIDGED,
            BTM_DATA_FLAG_DISASSOCIATION_IMMINENT,
            BTM_DATA_FLAG_BSS_TERMINATION_INCLUDED,
            BTM_DATA_FLAG_ESS_DISASSOCIATION_IMMINENT,
            BTM_DATA_FLAG_MBO_TRANSITION_REASON_CODE_INCLUDED,
            BTM_DATA_FLAG_MBO_ASSOC_RETRY_DELAY_INCLUDED,
            BTM_DATA_FLAG_MBO_CELL_DATA_CONNECTION_PREFERENCE_INCLUDED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface BtmDataFlag{}

    /** MBO spec v1.2, 4.2.6 Table 18: MBO transition reason attribute */
    public static final int MBO_TRANSITION_REASON_INVALID = -1;
    public static final int MBO_TRANSITION_REASON_UNSPECIFIED = 0;
    public static final int MBO_TRANSITION_REASON_EXCESSIVE_FRAME_LOSS = 1;
    public static final int MBO_TRANSITION_REASON_EXCESSIVE_TRAFFIC_DELAY = 2;
    public static final int MBO_TRANSITION_REASON_INSUFFICIENT_BANDWIDTH = 3;
    public static final int MBO_TRANSITION_REASON_LOAD_BALANCING = 4;
    public static final int MBO_TRANSITION_REASON_LOW_RSSI = 5;
    public static final int MBO_TRANSITION_REASON_RX_EXCESSIVE_RETRIES = 6;
    public static final int MBO_TRANSITION_REASON_HIGH_INTERFERENCE = 7;
    public static final int MBO_TRANSITION_REASON_GRAY_ZONE = 8;
    public static final int MBO_TRANSITION_REASON_TRANSITION_TO_PREMIUM_AP = 9;
    public static final int MBO_TRANSITION_REASON_RESERVED = 254;

    @IntDef(prefix = { "MBO_TRANSITION_REASON_" }, value = {
            MBO_TRANSITION_REASON_INVALID,
            MBO_TRANSITION_REASON_UNSPECIFIED,
            MBO_TRANSITION_REASON_EXCESSIVE_FRAME_LOSS,
            MBO_TRANSITION_REASON_EXCESSIVE_TRAFFIC_DELAY,
            MBO_TRANSITION_REASON_INSUFFICIENT_BANDWIDTH,
            MBO_TRANSITION_REASON_LOAD_BALANCING,
            MBO_TRANSITION_REASON_LOW_RSSI,
            MBO_TRANSITION_REASON_RX_EXCESSIVE_RETRIES,
            MBO_TRANSITION_REASON_HIGH_INTERFERENCE,
            MBO_TRANSITION_REASON_GRAY_ZONE,
            MBO_TRANSITION_REASON_TRANSITION_TO_PREMIUM_AP,
            MBO_TRANSITION_REASON_RESERVED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MboTransitionReason{}

    /** MBO spec v1.2, 4.2.5 Table 16: MBO Cellular data connection preference attribute values */
    public static final int MBO_CELLULAR_DATA_CONNECTION_INVALID = -1;
    public static final int MBO_CELLULAR_DATA_CONNECTION_EXCLUDED = 0;
    public static final int MBO_CELLULAR_DATA_CONNECTION_NOT_PREFERRED = 1;
    public static final int MBO_CELLULAR_DATA_CONNECTION_RESERVED = 254;
    public static final int MBO_CELLULAR_DATA_CONNECTION_PREFERRED = 255;

    @IntDef(prefix = { "MBO_CELLULAR_DATA_CONNECTION_" }, value = {
            MBO_CELLULAR_DATA_CONNECTION_INVALID,
            MBO_CELLULAR_DATA_CONNECTION_EXCLUDED,
            MBO_CELLULAR_DATA_CONNECTION_NOT_PREFERRED,
            MBO_CELLULAR_DATA_CONNECTION_RESERVED,
            MBO_CELLULAR_DATA_CONNECTION_PREFERRED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MboCellularDataConnectionPreference{}

    /** default Blacklist duration when AP doesn't advertise it */
    public static final long DEFAULT_BLACKLIST_DURATION_MS = 300_000; // 5 minutes

}

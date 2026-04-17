/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.telephony;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;
import android.telephony.ServiceState;
import android.telephony.ServiceState.RilRadioTechnology;

/**
 * A single occurrence capturing a network security event detected by the modem.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_NETWORK_SECURITY_EVENT_INDICATIONS)
public final class NetworkSecurityEvent implements Parcelable {
    private static final String TAG = "NetworkSecurityEvent";

    /** Default, unspecified. */
    public static final int ALERT_CATEGORY_UNSPECIFIED = 0;

    /** Forced downgrade, general. */
    public static final int ALERT_CATEGORY_DOWNGRADE = 1;

    /** Forced downgrade from 3G to 2G. */
    public static final int ALERT_CATEGORY_DOWNGRADE_2G = 2;

    /** Forced downgrade from 4G to 3G. */
    public static final int ALERT_CATEGORY_DOWNGRADE_3G = 3;

    /** Forced downgrade from 5G to 4G. */
    public static final int ALERT_CATEGORY_DOWNGRADE_4G = 4;

    /** Attempt to lock UE to a cell. */
    public static final int ALERT_CATEGORY_IMPRISONMENT = 5;

    /** Network Denial of Service. */
    public static final int ALERT_CATEGORY_DOS_NETWORK = 6;

    /** Suspiciously attractive cell. */
    public static final int ALERT_CATEGORY_ATTRACTIVE_CELL = 7;

    /** RF Jamming detected. */
    public static final int ALERT_CATEGORY_JAMMING = 8;

    /** Suspicious location tracking attempts. */
    public static final int ALERT_CATEGORY_LOCATION_TRACKING = 9;

    /** Network element passed authentication. */
    public static final int ALERT_CATEGORY_AUTH_PASSED = 10;

    /** Unauthenticated SMS. */
    public static final int ALERT_CATEGORY_UNAUTH_SMS = 11;

    /** Unauthenticated emergency message. */
    public static final int ALERT_CATEGORY_UNAUTH_EMERGENCY_MSG = 12;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"ALERT_CATEGORY_"},
            value = {
                ALERT_CATEGORY_UNSPECIFIED,
                ALERT_CATEGORY_DOWNGRADE,
                ALERT_CATEGORY_DOWNGRADE_2G,
                ALERT_CATEGORY_DOWNGRADE_3G,
                ALERT_CATEGORY_DOWNGRADE_4G,
                ALERT_CATEGORY_IMPRISONMENT,
                ALERT_CATEGORY_DOS_NETWORK,
                ALERT_CATEGORY_ATTRACTIVE_CELL,
                ALERT_CATEGORY_JAMMING,
                ALERT_CATEGORY_LOCATION_TRACKING,
                ALERT_CATEGORY_AUTH_PASSED,
                ALERT_CATEGORY_UNAUTH_SMS,
                ALERT_CATEGORY_UNAUTH_EMERGENCY_MSG
            })
    public @interface AlertCategory {}

    /** Default unspecified. To be used if the modem does not support the alert. */
    public static final int ALERT_STATUS_UNSPECIFIED = 0;

    /** * Modem is not detecting this alert. */
    public static final int ALERT_STATUS_NOT_DETECTED = 1;

    /** Modem detected a threat. */
    public static final int ALERT_STATUS_DETECTED = 2;

    /** Modem detected and blocked cell. */
    public static final int ALERT_STATUS_MITIGATED_CELL_BARRED = 3;

    /** Modem detected and deprioritized. */
    public static final int ALERT_STATUS_MITIGATED_CELL_DEPRIORITIZED = 4;

    /** Modem detected and took unspecified action. */
    public static final int ALERT_STATUS_MITIGATED_UNSPECIFIED = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"ALERT_STATUS_"},
            value = {
                ALERT_STATUS_UNSPECIFIED,
                ALERT_STATUS_NOT_DETECTED,
                ALERT_STATUS_DETECTED,
                ALERT_STATUS_MITIGATED_CELL_BARRED,
                ALERT_STATUS_MITIGATED_CELL_DEPRIORITIZED,
                ALERT_STATUS_MITIGATED_UNSPECIFIED
            })
    public @interface AlertStatus {}

    /** * Reason not specified. */
    public static final int REASON_CODE_UNSPECIFIED = 0;

    /** For {@link AlertCategory#DOWNGRADE} Network only offered old/weak encryption. */
    public static final int REASON_CODE_DOWNGRADE_WEAK_CIPHER_SUITES_OFFERED = 1;

    /** For {@link AlertCategory#DOWNGRADE} Attempts to connect to a higher RAT were rejected. */
    public static final int REASON_CODE_DOWNGRADE_HIGHER_RAT_REJECTED = 2;

    /** For {@link AlertCategory#DOWNGRADE} Unusual signal strength differences between RATs. */
    public static final int REASON_CODE_DOWNGRADE_SIGNAL_STRENGTH_ANOMALY = 3;

    /**
     * For {@link AlertCategory#DOWNGRADE} Network unexpectedly forced a handover to a lower
     * generation network.
     */
    public static final int REASON_CODE_DOWNGRADE_FORCED_HANDOVER = 4;

    /** For {@link AlertCategory#IMPRISONMENT} Device unable to reselect to any other cell. */
    public static final int REASON_CODE_IMPRISONMENT_CELL_RESELECTION_FAILURE = 5;

    /** For {@link AlertCategory#IMPRISONMENT} Serving cell provided no valid neighbor list. */
    public static final int REASON_CODE_IMPRISONMENT_NEIGHBOR_LIST_EMPTY_OR_INVALID = 6;

    /** For {@link AlertCategory#IMPRISONMENT} Other potential cells are indicated as barred. */
    public static final int REASON_CODE_IMPRISONMENT_BARRING_OF_OTHER_CELLS = 7;

    /** For {@link AlertCategory#IMPRISONMENT} Attempts to move to neighbor cells are rejected. */
    public static final int REASON_CODE_IMPRISONMENT_REJECTED_FROM_NEIGHBORS = 8;

    /**
     * For {@link AlertCategory#DOS_NETWORK} Device is being paged at an abnormally high frequency.
     */
    public static final int REASON_CODE_DOS_EXCESSIVE_PAGING_RATE = 9;

    /** For {@link AlertCategory#DOS_NETWORK} Repeated failures and retries in connection setup. */
    public static final int REASON_CODE_DOS_CONNECTION_SETUP_FAIL_LOOP = 10;

    /**
     * For {@link AlertCategory#DOS_NETWORK} Flooded with authentication requests from the network.
     */
    public static final int REASON_CODE_DOS_AUTHENTICATION_REQUEST_FLOOD = 11;

    /** For {@link AlertCategory#DOS_NETWORK} Network forcing rapid detach and attach procedures. */
    public static final int REASON_CODE_DOS_DETACH_ATTACH_CYCLE = 12;

    /** For {@link AlertCategory#ATTRACTIVE_CELL} Received signal strength is suspiciously high. */
    public static final int REASON_CODE_ATTRACTIVE_CELL_VERY_HIGH_RX_LEVEL = 13;

    /**
     * For {@link AlertCategory#ATTRACTIVE_CELL} Cell broadcasting an unexpected PLMN (Network ID).
     */
    public static final int REASON_CODE_ATTRACTIVE_CELL_UNEXPECTED_PLMN_ID = 14;

    /**
     * For {@link AlertCategory#ATTRACTIVE_CELL} Cell does not broadcast any neighbor cell
     * information.
     */
    public static final int REASON_CODE_ATTRACTIVE_CELL_MISSING_NEIGHBOR_INFO = 15;

    /**
     * For {@link AlertCategory#ATTRACTIVE_CELL} Cell parameters match known IMSI catcher
     * signatures.
     */
    public static final int REASON_CODE_ATTRACTIVE_CELL_IMSI_CATCHER_PARAMETERS = 16;

    /**
     * For {@link AlertCategory#JAMMING} High noise levels detected across a wide range of
     * frequencies.
     */
    public static final int REASON_CODE_JAMMING_WIDEBAND_INTERFERENCE = 17;

    /** For {@link AlertCategory#JAMMING} Strong interference on specific operating frequencies. */
    public static final int REASON_CODE_JAMMING_NARROWBAND_INTERFERENCE = 18;

    /** For {@link AlertCategory#JAMMING} Sudden and significant drop in Signal-to-Noise Ratio. */
    public static final int REASON_CODE_JAMMING_SNR_DEGRADATION = 19;

    /**
     * For {@link AlertCategory#LOCATION_TRACKING} Network requesting location/tracking area updates
     * too frequently.
     */
    public static final int REASON_CODE_LOCATION_FREQUENT_TRACKING_AREA_UPDATES = 20;

    /**
     * For {@link AlertCategory#LOCATION_TRACKING} Detection of non-displayed SMS (potential
     * location ping).
     */
    public static final int REASON_CODE_LOCATION_SILENT_SMS_DETECTED = 21;

    /**
     * For {@link AlertCategory#LOCATION_TRACKING} Frequent paging without subsequent call/SMS/data.
     */
    public static final int REASON_CODE_LOCATION_PAGING_WITHOUT_FOLLOWUP = 22;

    /** For {@link AlertCategory#UNAUTH_SMS} Message failed an integrity check. */
    public static final int REASON_CODE_UNAUTH_SMS_INTEGRITY_CHECK_FAILED = 23;

    /**
     * For {@link AlertCategory#UNAUTH_SMS} Expected security elements in SMS transport are missing.
     */
    public static final int REASON_CODE_UNAUTH_SMS_MISSING_SECURITY_HEADERS = 24;

    /**
     * For {@link AlertCategory#UNAUTH_SMS} SMS originated from an untrusted Short Message Entity.
     */
    public static final int REASON_CODE_UNAUTH_SMS_UNTRUSTED_SME = 25;

    /** For {@link AlertCategory#UNAUTH_SMS} Matches a known signature of SMS spoofing. */
    public static final int REASON_CODE_UNAUTH_SMS_KNOWN_SPOOFING_METHOD = 26;

    /**
     * For {@link AlertCategory#UNAUTH_EMERGENCY_MSG} Emergency message from a cell that isn't
     * authenticated.
     */
    public static final int REASON_CODE_UNAUTH_EMERGENCY_SOURCE_CELL_NOT_AUTHENTICATED = 27;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"REASON_CODE_"},
            value = {
                REASON_CODE_UNSPECIFIED,
                REASON_CODE_DOWNGRADE_WEAK_CIPHER_SUITES_OFFERED,
                REASON_CODE_DOWNGRADE_HIGHER_RAT_REJECTED,
                REASON_CODE_DOWNGRADE_SIGNAL_STRENGTH_ANOMALY,
                REASON_CODE_DOWNGRADE_FORCED_HANDOVER,
                REASON_CODE_IMPRISONMENT_CELL_RESELECTION_FAILURE,
                REASON_CODE_IMPRISONMENT_NEIGHBOR_LIST_EMPTY_OR_INVALID,
                REASON_CODE_IMPRISONMENT_BARRING_OF_OTHER_CELLS,
                REASON_CODE_IMPRISONMENT_REJECTED_FROM_NEIGHBORS,
                REASON_CODE_DOS_EXCESSIVE_PAGING_RATE,
                REASON_CODE_DOS_CONNECTION_SETUP_FAIL_LOOP,
                REASON_CODE_DOS_AUTHENTICATION_REQUEST_FLOOD,
                REASON_CODE_DOS_DETACH_ATTACH_CYCLE,
                REASON_CODE_ATTRACTIVE_CELL_VERY_HIGH_RX_LEVEL,
                REASON_CODE_ATTRACTIVE_CELL_UNEXPECTED_PLMN_ID,
                REASON_CODE_ATTRACTIVE_CELL_MISSING_NEIGHBOR_INFO,
                REASON_CODE_ATTRACTIVE_CELL_IMSI_CATCHER_PARAMETERS,
                REASON_CODE_JAMMING_WIDEBAND_INTERFERENCE,
                REASON_CODE_JAMMING_NARROWBAND_INTERFERENCE,
                REASON_CODE_JAMMING_SNR_DEGRADATION,
                REASON_CODE_LOCATION_FREQUENT_TRACKING_AREA_UPDATES,
                REASON_CODE_LOCATION_SILENT_SMS_DETECTED,
                REASON_CODE_LOCATION_PAGING_WITHOUT_FOLLOWUP,
                REASON_CODE_UNAUTH_SMS_INTEGRITY_CHECK_FAILED,
                REASON_CODE_UNAUTH_SMS_MISSING_SECURITY_HEADERS,
                REASON_CODE_UNAUTH_SMS_UNTRUSTED_SME,
                REASON_CODE_UNAUTH_SMS_KNOWN_SPOOFING_METHOD,
                REASON_CODE_UNAUTH_EMERGENCY_SOURCE_CELL_NOT_AUTHENTICATED
            })
    public @interface ReasonCode {}

    /**
     * RIL Radio Annotation
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"RIL_RADIO_TECHNOLOGY_"},
            value = {
                ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN,
                ServiceState.RIL_RADIO_TECHNOLOGY_GPRS,
                ServiceState.RIL_RADIO_TECHNOLOGY_EDGE,
                ServiceState.RIL_RADIO_TECHNOLOGY_UMTS,
                ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA,
                ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA,
                ServiceState.RIL_RADIO_TECHNOLOGY_HSPA,
                ServiceState.RIL_RADIO_TECHNOLOGY_LTE,
                ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP,
                ServiceState.RIL_RADIO_TECHNOLOGY_GSM,
                ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN,
                ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA,
                ServiceState.RIL_RADIO_TECHNOLOGY_NR
            })
    public @interface RilRadioTechnology {}

    private final @AlertCategory int mAlertCategory;
    private final @AlertStatus int mAlertStatus;
    private final @NonNull @ReasonCode int[] mReasonCodes;
    private final long mCellId;
    private final int mPhysicalCellId;
    private final int mArfcn;
    private final @NonNull String mPlmn;
    private final @RilRadioTechnology int mRat;
    private final boolean mIsEmergency;

    /**
     * Constructor for new NetworkSecurityEvent instances.
     *
     * @hide
     */
    @TestApi
    public NetworkSecurityEvent(
            @AlertCategory int alertCategory,
            @AlertStatus int alertStatus,
            @NonNull @ReasonCode int[] reasonCodes,
            long cellId,
            int physicalCellId,
            int arfcn,
            @NonNull String plmn,
            @RilRadioTechnology int rat,
            boolean isEmergency) {
        mAlertCategory = alertCategory;
        mAlertStatus = alertStatus;
        mReasonCodes = reasonCodes;
        mCellId = cellId;
        mPhysicalCellId = physicalCellId;
        mArfcn = arfcn;
        mPlmn = plmn;
        mRat = rat;
        mIsEmergency = isEmergency;
    }

    private NetworkSecurityEvent(Parcel in) {
        mAlertCategory = in.readInt();
        mAlertStatus = in.readInt();
        mReasonCodes = in.createIntArray();
        mCellId = in.readLong();
        mPhysicalCellId = in.readInt();
        mArfcn = in.readInt();
        mPlmn = in.readString8();
        mRat = in.readInt();
        mIsEmergency = in.readBoolean();
    }

    /**
     * @return The category of the security alert.
     */
    public @AlertCategory int getAlertCategory() {
        return mAlertCategory;
    }

    /**
     * @return The status of the security alert.
     */
    public @AlertStatus int getAlertStatus() {
        return mAlertStatus;
    }

    /**
     * @return The reason codes providing more detail about the event.
     */
    public @NonNull @ReasonCode int[] getReasonCodes() {
        return mReasonCodes.clone();
    }

    /**
     * @return The cell ID where the event occurred.
     */
    public long getCellId() {
        return mCellId;
    }

    /**
     * @return The physical cell ID where the event occurred.
     */
    public int getPhysicalCellId() {
        return mPhysicalCellId;
    }

    /**
     * @return The Absolute Radio Frequency Channel Number of the cell.
     */
    public int getArfcn() {
        return mArfcn;
    }

    /**
     * @return The PLMN ID of the network.
     */
    @NonNull
    public String getPlmn() {
        return mPlmn;
    }

    /**
     * @return The Radio Access Technology on which the event was detected.
     */
    public @RilRadioTechnology int getRat() {
        return mRat;
    }

    /**
     * @return {@code true} if the event is associated with an emergency service session,
     *     {@code false} otherwise.
     */
    public boolean isEmergency() {
        return mIsEmergency;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mAlertCategory);
        out.writeInt(mAlertStatus);
        out.writeIntArray(mReasonCodes);
        out.writeLong(mCellId);
        out.writeInt(mPhysicalCellId);
        out.writeInt(mArfcn);
        out.writeString8(mPlmn);
        out.writeInt(mRat);
        out.writeBoolean(mIsEmergency);
    }

    public static final @NonNull Parcelable.Creator<NetworkSecurityEvent> CREATOR =
            new Parcelable.Creator<NetworkSecurityEvent>() {
                public NetworkSecurityEvent createFromParcel(Parcel in) {
                    return new NetworkSecurityEvent(in);
                }

                public NetworkSecurityEvent[] newArray(int size) {
                    return new NetworkSecurityEvent[size];
                }
            };

    @Override
    public String toString() {
        return TAG
                + ":{"
                + "mAlertCategory="
                + mAlertCategory
                + ", mAlertStatus="
                + mAlertStatus
                + ", mReasonCodes="
                + Arrays.toString(mReasonCodes)
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NetworkSecurityEvent that = (NetworkSecurityEvent) o;
        return mAlertCategory == that.mAlertCategory
                && mAlertStatus == that.mAlertStatus
                && mCellId == that.mCellId
                && mPhysicalCellId == that.mPhysicalCellId
                && mArfcn == that.mArfcn
                && mPlmn.equals(that.mPlmn)
                && mRat == that.mRat
                && mIsEmergency == that.mIsEmergency
                && Arrays.equals(mReasonCodes, that.mReasonCodes);
    }

    @Override
    public int hashCode() {
        int result =
                Objects.hash(
                        mAlertCategory,
                        mAlertStatus,
                        mCellId,
                        mPhysicalCellId,
                        mArfcn,
                        mPlmn,
                        mRat,
                        mIsEmergency);
        result = 31 * result + Arrays.hashCode(mReasonCodes);
        return result;
    }
}

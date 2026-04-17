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

package android.net.wifi.rtt;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.wifi.WifiAnnotations;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.wifi.flags.Flags;

/**
 * The characteristics of the Proximity Detection feature implementation.
 * Refer Wi-Fi Alliance Proximity Ranging specification section 3.3
 * "P2P Proximity Ranging Attributes" for the details.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
public final class ProximityDetectionCharacteristics implements Parcelable {
    private final Bundle mCharacteristics;

    /** @hide : should not be created by apps */
    public ProximityDetectionCharacteristics(Bundle characteristics) {
        mCharacteristics = characteristics;
    }

    private ProximityDetectionCharacteristics(@NonNull Parcel in) {
        mCharacteristics = in.readBundle(getClass().getClassLoader());
    }

    @NonNull
    public static final Creator<ProximityDetectionCharacteristics> CREATOR =
            new Creator<>() {
                @Override
                public ProximityDetectionCharacteristics createFromParcel(Parcel in) {
                    return new ProximityDetectionCharacteristics(in);
                }

                @Override
                public ProximityDetectionCharacteristics[] newArray(int size) {
                    return new ProximityDetectionCharacteristics[size];
                }
            };

    /** @hide */
    public static final String KEY_INT_MAX_NUM_CONTINUOUS_RANGING_SEEKER_SESSIONS =
            "key_max_num_continuous_ranging_seeker_session";
    /** @hide */
    public static final String KEY_INT_MAX_NUM_CONTINUOUS_RANGING_ADVERTISER_SESSIONS =
            "key_max_num_continuous_ranging_advertiser_session";

    /** @hide */
    public static final String KEY_BOOLEAN_CONCURRENT_ISTA_RSTA_OPERATION_SUPPORTED =
            "key_concurrent_ista_rsta_operation_supported";

    /** @hide */
    public static final String KEY_INT_MIN_ALLOWED_RANGING_INTERVAL_80211MC_MS =
            "key_min_min_allowed_ranging_interval_80211mc";

    /** @hide */
    public static final String KEY_INT_MIN_ALLOWED_RANGING_INTERVAL_NTB_MS =
            "key_min_min_allowed_ranging_interval_NTB";

    /** @hide */
    public static final String KEY_STRING_PROXIMITY_DETECTION_DEVICE_NAME =
            "key_proximity_detection_device_name";
    /** @hide */
    public static final String KEY_BOOLEAN_80211MC_BASED_RANGING_SUPPORTED =
            "key_80211mc_based_ranging_supported";
    /** @hide */
    public static final String KEY_BOOLEAN_NTB_SECURE_HE_LTF_RANGING_SUPPORTED =
            "key_ntb_secure_he_ltf_ranging_supported";
    /** @hide */
    public static final String KEY_BOOLEAN_NTB_NON_SECURE_HE_LTF_RANGING_SUPPORTED =
            "key_ntb_non_secure_he_ltf_ranging_supported";
    /** @hide */
    public static final String KEY_BOOLEAN_80211MC_BASED_ISTA_ROLE =
            "key_80211mc_based_ista_role";
    /** @hide */
    public static final String KEY_BOOLEAN_80211MC_BASED_RSTA_ROLE =
            "key_80211mc_based_rsta_role";
    /** @hide */
    public static final String KEY_BOOLEAN_NTB_ISTA_ROLE = "key_ntb_based_ista_role";
    /** @hide */
    public static final String KEY_BOOLEAN_NTB_RSTA_ROLE = "key_ntb_rsta_role";
    /** @hide */
    public static final String KEY_INT_MAX_SUPPORTED_PACKET_WIDTH_80211MC_BASED =
            "key_max_supported_packet_width_80211mc_based";
    /** @hide */
    public static final String KEY_INT_MAX_SUPPORTED_PREAMBLE_80211MC_BASED =
            "key_max_supported_preamble_80211mc_based";
    /** @hide */
    public static final String KEY_INT_MAX_SUPPORTED_PACKET_WIDTH_NTB =
            "key_max_supported_packet_width_ntb";
    /** @hide */
    public static final String KEY_INT_MAX_SUPPORTED_PREAMBLE_NTB =
            "key_max_supported_preamble_ntb";
    /** @hide */
    public static final String KEY_BOOLEAN_UNAUTHENTICATED_PASN = "key_unauthenticated_pasn";
    /** @hide */
    public static final String KEY_BOOLEAN_AUTHENTICATED_PASN = "key_authenticated_pasn";

    /**
     * Returns the maximum number of simultaneous continuous Proximity Detection
     * sessions the device can handle (act as a seeker/initiator).
     * @return the maximum number of sessions.
     */
    @IntRange(from = 1)
    public int getMaxNumContinuousRangingSeekerSessions() {
        return mCharacteristics.getInt(KEY_INT_MAX_NUM_CONTINUOUS_RANGING_SEEKER_SESSIONS);
    }

    /**
     * Returns the maximum number of simultaneous continuous Proximity Detection sessions the
     * device can handle (act as an advertiser/responder).
     * @return the maximum number of sessions.
     */
    @IntRange(from = 1)
    public int getMaxNumContinuousRangingAdvertiserSessions() {
        return mCharacteristics.getInt(KEY_INT_MAX_NUM_CONTINUOUS_RANGING_ADVERTISER_SESSIONS);
    }

    /**
     * Returns true if the device can support ranging initiator station (ISTA) and ranging
     * responder station (RSTA) operation concurrently.
     * @return true if supported, false otherwise.
     */
    public boolean isConcurrentIstaRstaOperationSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_CONCURRENT_ISTA_RSTA_OPERATION_SUPPORTED);
    }

    /**
     * Returns the minimum allowed Proximity Detection ranging interval the device can handle
     * in IEEE80211MC based ranging.
     * @return the minimum allowed ranging interval in milliseconds.
     */
    public int getMinAllowedRangingInterval80211mcMillis() {
        return mCharacteristics.getInt(KEY_INT_MIN_ALLOWED_RANGING_INTERVAL_80211MC_MS);
    }

    /**
     * Returns the minimum allowed Proximity Detection ranging interval the device can handle
     * in Non trigger based ranging.
     * @return the minimum allowed ranging interval in milliseconds.
     */
    public int getMinAllowedRangingIntervalNtbMillis() {
        return mCharacteristics.getInt(KEY_INT_MIN_ALLOWED_RANGING_INTERVAL_NTB_MS);
    }


    /**
     * Get the friendly name of the Proximity Detection device.
     * @return The friendly name of the device, or null if not available.
     */
    @Nullable
    public String getProximityDetectionDeviceName() {
        return mCharacteristics.getString(
                KEY_STRING_PROXIMITY_DETECTION_DEVICE_NAME);
    }

    /**
     * Returns true if the device supports IEEE80211MC based ranging.
     * @return true if supported, false otherwise.
     */
    public boolean is80211mcBasedRangingSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_80211MC_BASED_RANGING_SUPPORTED);
    }

    /**
     * Returns true if the device supports NTB (Non-Trigger-Based) ranging with secure
     * Long Training Field (LTF).
     * @return true if supported, false otherwise.
     */
    public boolean isNtbSecureLtfRangingSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_NTB_SECURE_HE_LTF_RANGING_SUPPORTED);
    }

    /**
     * Returns true if the device supports NTB (Non-Trigger-Based) ranging with a non-secure
     * Long Training Field (LTF).
     * @return true if supported, false otherwise.
     */
    public boolean isNtbNonSecureLtfRangingSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_NTB_NON_SECURE_HE_LTF_RANGING_SUPPORTED);
    }

    /**
     * Returns true if the device supports the Initiating Station (iSTA) role for
     * IEEE80211MC-based ranging. The iSTA role is the device that initiates the
     * ranging measurement.
     * @return true if supported, false otherwise.
     */
    public boolean is80211mcBasedIstaRoleSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_80211MC_BASED_ISTA_ROLE);
    }

    /**
     * Returns true if the device supports the Responding Station (rSTA) role for
     * IEEE80211MC based ranging. The rSTA role is the device that responds to the ranging request.
     * @return true if supported, false otherwise.
     */
    public boolean is80211mcBasedRstaRoleSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_80211MC_BASED_RSTA_ROLE);
    }

    /**
     * Returns true if the device supports the Initiating Station (iSTA) role for
     * Non trigger based ranging.
     * @return true if supported, false otherwise.
     */
    public boolean isNtbIstaRoleSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_NTB_ISTA_ROLE);
    }

    /**
     * Returns true if the device supports the Responding Station (rSTA) role for
     * Non trigger based ranging.
     * @return true if supported, false otherwise.
     */
    public boolean isNtbRstaRoleSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_NTB_RSTA_ROLE);
    }

    /**
     * The maximum supported packet bandwidth for
     * IEEE80211MC based ranging.
     * @return the maximum supported packet bandwidth
     */
    public @WifiAnnotations.ChannelWidth  int getMaxSupportedPacketWidth80211mcBased() {
        return mCharacteristics.getInt(KEY_INT_MAX_SUPPORTED_PACKET_WIDTH_80211MC_BASED);
    }

    /**
     * The maximum supported preamble or format for
     * IEEE80211MC based ranging.
     * @return the maximum supported preamble
     */
    public @WifiAnnotations.PreambleType int getMaxSupportedPreamble80211mcBased() {
        return mCharacteristics.getInt(KEY_INT_MAX_SUPPORTED_PREAMBLE_80211MC_BASED);
    }

    /**
     * The maximum supported packet bandwidth for
     * NTB ranging.
     * @return the maximum supported packet bandwidth
     */
    public @WifiAnnotations.ChannelWidth int getMaxSupportedPacketWidthNtb() {
        return mCharacteristics.getInt(KEY_INT_MAX_SUPPORTED_PACKET_WIDTH_NTB);
    }

    /**
     * The maximum supported preamble or format for
     * NTB ranging.
     * @return the maximum supported preamble
     */
    public @WifiAnnotations.PreambleType  int getMaxSupportedPreambleNtb() {
        return mCharacteristics.getInt(KEY_INT_MAX_SUPPORTED_PREAMBLE_NTB);

    }

    /**
     * Returns true if the device supports unauthenticated PASN mode.
     * i.e., when there are no authentication credentials
     * (no Password and no PMK).
     * @return true if supported, false otherwise.
     */
    public boolean isUnauthenticatedPasnModeSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_UNAUTHENTICATED_PASN);
    }

    /**
     * Returns true if the device supports authenticated PASN mode.
     * i.e., when both devices share a Password or PMK that is
     * coupled with DevIK is used as the authentication
     * credentials
     * @return true if supported, false otherwise.
     */
    public boolean isAuthenticatedPasnModeSupported() {
        return mCharacteristics.getBoolean(
                KEY_BOOLEAN_AUTHENTICATED_PASN);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this object in to a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(mCharacteristics);
    }

}

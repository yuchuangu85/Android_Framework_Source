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

package android.hardware.usb;

import android.annotation.CheckResult;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.usb.flags.Flags;
import android.hardware.usb.PowerProfileMatchInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PowerProfileInfo
 * <p>
 * PowerProfileInfo describes the USB power sink and power sourcing capabilities
 * of a USB port, motivated in part by the <a href="https://www.usb.org/document-library/usb-power-delivery">USB Power Delivery</a>
 * specification (USB PD) and the <a href="https://www.usb.org/usb-type-cr-cable-and-connector-specification">USB Type-C Cable and Connector</a>
 * specification.
 * </p>
 * <p>
 * In USB PD, the USB power sink and the USB power source negotiate power contracts using Power
 * Data Objects (PDOs) which define capabilities depending on the type of USB power supply is being
 * used between the port and its partner. The metrics are common across the different types -
 * minimum and maximum voltage, current, and power, so PowerProfileInfo can provide a general API
 * framework for any USB PD type.
 * </p>
 * <p>
 * The USB Type-C Cable and Connector specification covers the power source and sink limitations
 * when USB PD is not supported by both the USB port and its port partner; PowerProfileInfo can
 * fully describe these capabilities. Some vendors may decide to implement a proprietary charging
 * standard over USB, so PowerProfileInfo contains all power metrics necessary to describe
 * any charging standard's capabilities. In a similar way some chargers may be non-compliant with
 * any specification, so PowerProfileInfo also covers the metrics necessary to describe
 * the charging capabilities of these devices if reported.
 * </p>
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
@SystemApi
public final class PowerProfileInfo implements Parcelable {
    /**
     * Used to describe a PowerProfileInfo of type POWER_PROFILE_TYPE_VENDOR
     */
    private final String mName;
    private List<PowerProfileInfo> mMatchingProfiles;
    private List<PowerProfileMatchInfo> mMatchResults;
    private final @PowerProfileType int mPowerProfileType;
    private final int mMinVoltageMv;
    private final int mMaxVoltageMv;
    private final int mMinCurrentMa;
    private final int mMaxCurrentMa;
    private final int mMaxPowerMw;

    /**
     * When returned from a request for a PowerProfileInfo power metric query,
     * indicates that the request is invalid for a reason not covered by
     * other 'POWER_PROFILE_ERROR' values.
     */
    public static final int POWER_PROFILE_ERROR_OTHER = -1;

    /**
     * When returned from a request for a PowerProfileInfo power metric query,
     * indicates that the request is invalid because the PowerProfileType does
     * not support the power metric being requested.
     * <p>
     * This value is also used as the default value for PowerProfileInfo power
     * metrics.
     * </p>
     */
    public static final int POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED = -2;

    /**
     * When returned from a request for a matching PowerProfileInfo power metric
     * query, indicates that the request is invalid because the PowerProfile being
     * compared against is not a valid match.
     */
    public static final int POWER_PROFILE_ERROR_NO_MATCH = -3;

    /** @hide */
    @IntDef(prefix = { "POWER_PROFILE_ERROR_" }, value = {
            POWER_PROFILE_ERROR_OTHER,
            POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED,
            POWER_PROFILE_ERROR_NO_MATCH,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface PowerProfileError{}

    /**
     * Indicates that an invalid power profile is being described. Queries involving a
     * {@link #PowerProfileInfo} object of this type will always return
     * {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED}.
     */
    public static final int POWER_PROFILE_TYPE_NONE = 0;

    /**
     * Indicates that the power profile type is introduced after the current target sdk.
     */
    public static final int POWER_PROFILE_TYPE_OTHER = 1;

    /**
     * Indicates that the power profile is vendor defined, and implementation
     * is not standardized
     */
    public static final int POWER_PROFILE_TYPE_VENDOR = 2;

    /**
     * Indicates that the power profile describes the power provided by a source
     * advertising USB Type-C Current at Default as described in USB Type-C® Cable and
     * Connector Specification.
     * <p>
     * {@link #getMaxVoltageMv()} and {@link #getMaxCurrentMa()} are supported
     * </p>
     */
    public static final int POWER_PROFILE_TYPE_RP_DEFAULT = 3;

    /**
     * Indicates that the power profile describes the power provided by a source
     * advertising USB Type-C Current at 1.5A as described in USB Type-C® Cable and
     * Connector Specification.
     * <p>
     * {@link #getMaxVoltageMv()} and {@link #getMaxCurrentMa()} are supported
     * </p>
     */
    public static final int POWER_PROFILE_TYPE_RP_1_5A = 4;

    /**
     * Indicates that the power profile describes the power provided by a source
     * advertising USB Type-C Current at 3.0A as described in USB Type-C® Cable and
     * Connector Specification.
     * <p>
     * {@link #getMaxVoltageMv()} and {@link #getMaxCurrentMa()} are supported
     * </p>
     */
    public static final int POWER_PROFILE_TYPE_RP_3_0A = 5;

    /**
     * Indicates that the power profile describes a fixed voltage supply as described
     * in the USB Power Delivery specification.
     * <p>
     * {@link #getMaxVoltageMv()} and {@link #getMaxCurrentMa()} are supported
     * </p>
     */
    public static final int POWER_PROFILE_TYPE_FIXED = 6;

    /**
     * Indicates that the power profile describes a battery that connects directly
     * to the port as described in the USB Power Delivery Specification.
     * <p>
     * {@link #getMaxVoltageMv()}, {@link #getMinVoltageMv()} {@link #getMaxPowerMw()}
     * are supported
     * </p>
     */
    public static final int POWER_PROFILE_TYPE_BATTERY = 7;

    /**
     * Indicates that the power profile describes a variable (non-battery) supply
     * as described in the USB Power Delivery Specification.
     * <p>
     * {@link #getMaxVoltageMv()}, {@link #getMinVoltageMv()} {@link #getMaxCurrentMa()}
     * are supported
     * </p>
     */
    public static final int POWER_PROFILE_TYPE_VARIABLE = 8;

    /**
     * Indicates that the power profile describes a Programmable Power Supply as
     * described in the USB Power Delivery Specification where the output voltage
     * can be adjusted programmatically over a defined range.
     * <p>
     * {@link #getMaxVoltageMv()}, {@link #getMinVoltageMv()} {@link #getMaxCurrentMa()}
     * are supported
     * </p>
     */
    public static final int POWER_PROFILE_TYPE_SPR_PPS = 9;

    /**
     * Indicates that the power profile describes a Adjustable Voltage Supply as
     * described in the USB Power Delivery Specification where the output voltage
     * can be adjusted programmatically over a defined range.
     * <p>
     * {@link #getMaxVoltageMv()} and {@link #getMaxCurrentMa()} are supported
     * </p>
     * <p>
     * Because the APDO for AVS describes max current limits for the 9V-15V and
     * 15V-20V range, one AVS PowerProfileInfo describes the 9V-15V or the 15V-20V
     * range.
     * </p>
     */
    public static final int POWER_PROFILE_TYPE_SPR_AVS = 10;

    /** @hide */
    @IntDef(prefix = { "POWER_PROFILE_TYPE_" }, value = {
            POWER_PROFILE_TYPE_NONE,
            POWER_PROFILE_TYPE_OTHER,
            POWER_PROFILE_TYPE_VENDOR,
            POWER_PROFILE_TYPE_RP_DEFAULT,
            POWER_PROFILE_TYPE_RP_1_5A,
            POWER_PROFILE_TYPE_RP_3_0A,
            POWER_PROFILE_TYPE_FIXED,
            POWER_PROFILE_TYPE_BATTERY,
            POWER_PROFILE_TYPE_VARIABLE,
            POWER_PROFILE_TYPE_SPR_PPS,
            POWER_PROFILE_TYPE_SPR_AVS,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface PowerProfileType{}

    private String powerProfileTypeToString(@PowerProfileType int type) {
        switch (type) {
            case POWER_PROFILE_TYPE_OTHER:
                return "other";
            case POWER_PROFILE_TYPE_VENDOR:
                return "vendor";
            case POWER_PROFILE_TYPE_RP_DEFAULT:
                return "rp default";
            case POWER_PROFILE_TYPE_RP_1_5A:
                return "rp 1.5A";
            case POWER_PROFILE_TYPE_RP_3_0A:
                return "rp 3.0A";
            case POWER_PROFILE_TYPE_FIXED:
                return "fixed";
            case POWER_PROFILE_TYPE_BATTERY:
                return "battery";
            case POWER_PROFILE_TYPE_VARIABLE:
                return "variable";
            case POWER_PROFILE_TYPE_SPR_PPS:
                return "spr pps";
            case POWER_PROFILE_TYPE_SPR_AVS:
                return "spr avs";
            default:
                return "none";
        }
    }

    /** @hide */
    public PowerProfileInfo(Builder builder) {
        mName = builder.mName;
        mMatchingProfiles = new ArrayList<>();
        mMatchResults = new ArrayList<>();
        mPowerProfileType = builder.mPowerProfileType;
        mMinVoltageMv = builder.mMinVoltageMv;
        mMaxVoltageMv = builder.mMaxVoltageMv;
        mMinCurrentMa = builder.mMinCurrentMa;
        mMaxCurrentMa = builder.mMaxCurrentMa;
        mMaxPowerMw = builder.mMaxPowerMw;
    }

    /**
     * Get the name of the Power Profile type. The naming convention is non-standardized and is
     * defined by the device manufacturer.
     *
     * @return a {@link #String} when {@link getPowerProfileType()} is equal to
     * {@link #POWER_PROFILE_TYPE_VENDOR}, {@code null} otherwise.
     */
    @Nullable
    public String getName() {
        return mName;
    }

    /**
     * Return the Power Profile type
     *
     * @return a value of {@link #PowerProfileType}
     */
    @CheckResult
    public @PowerProfileType int getPowerProfileType() {
        return mPowerProfileType;
    }

    /**
     * Returns this power profile's matching power profiles belonging to the port partner
     * of the port this power profile belongs to.
     * <p>
     * A local port sink/source PowerProfileInfo will return a list of remote port source/sink
     * PowerProfileInfo objects, while a remote port source/sink PowerProfile will return a
     * list of matching local port sink/source PowerProfileInfo objects.
     *
     * @return a list of PowerProfileInfo objects. The list will be empty if this PowerProfileInfo
     * object does not have any valid matches.
     */
    @NonNull
    public List<PowerProfileInfo> getMatchingPartnerProfiles() {
        return Collections.unmodifiableList(mMatchingProfiles);
    }

    /**
     * Adds a matching PowerProfileInfo and it's corresponding PowerProfileMatchInfo. This is used
     * by the UsbPortStatus after all PowerProfileInfo and PowerProfileMatchInfo objects are
     * created.
     *
     * @hide
     */
    public void addMatchingPowerProfile(PowerProfileInfo matchingProfile,
            PowerProfileMatchInfo matchInfo) {
        mMatchingProfiles.add(matchingProfile);
        mMatchResults.add(matchInfo);
    }

    /**
     * Returns the power profile's minimum voltage in mV
     *
     * @return the minimum voltage supported by the power profile, where a valid value is
     * 0 or greater. If this power metric is not supported by this power profile's
     * {@link #PowerProfileType}, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will
     * be returned instead.
     */
    @CheckResult
    public int getMinVoltageMv() {
        if (mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }
        return mMinVoltageMv;
    }

    /**
     * Returns the power profile's maximum voltage in mV
     *
     * @return the maximum voltage supported by the power profile, where a valid value is
     * 0 or greater. If this power metric is not supported by this power profile's
     * {@link #PowerProfileType}, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will
     * be returned instead.
     */
    @CheckResult
    public int getMaxVoltageMv() {
        if (mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }
        return mMaxVoltageMv;
    }

    /**
     * Returns the power profile's minimum current in mA
     *
     * @return the minimum current supported by the power profile, where a valid value is
     * 0 or greater. If this power metric is not supported by this power profile's
     * {@link #PowerProfileType}, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will
     * be returned instead.
     */
    @CheckResult
    public int getMinCurrentMa() {
        if (mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }
        return mMinCurrentMa;
    }

    /**
     * Returns the power profile's maximum current in mA
     *
     * @return the maximum current supported by the power profile, where a valid value is
     * 0 or greater. If this power metric is not supported by this power profile's
     * {@link #PowerProfileType}, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will
     * be returned instead.
     */
    @CheckResult
    public int getMaxCurrentMa() {
        if (mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }
        return mMaxCurrentMa;
    }

    /**
     * Returns the power profile's maximum power in mW
     *
     * @return the maximum power supported by the power profile, where a valid value is
     * 0 or greater. If this power metric is not supported by this power profile's
     * {@link #PowerProfileType}, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will
     * be returned instead.
     */
    @CheckResult
    public int getMaxPowerMw() {
        if (mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }
        return mMaxPowerMw;
    }

    private PowerProfileMatchInfo getPowerProfileMatchInfo(PowerProfileInfo matchingProfile) {
        int idx = mMatchingProfiles.indexOf(matchingProfile);

        if (idx != -1) {
            return mMatchResults.get(idx);
        }

        return null;
    }

    /**
     * Returns the effective minimum voltage in mV from the matching power capabilities between
     * this PowerProfileInfo and the one given in {@code profile}.
     * <p>
     * For type {@link #POWER_PROFILE_TYPE_BATTERY}, {@link #POWER_PROFILE_TYPE_VARIABLE}, and
     * {@link #POWER_PROFILE_TYPE_SPR_PPS}, the power sink's minimum voltage is expected to be
     * greater than or equal to the power source's minimum voltage. The minimum voltage will then
     * be equal to the sink's minimum voltage.
     * </p>
     * <p>
     * When at least one power profile in a match is of type {@link #POWER_PROFILE_TYPE_VENDOR},
     * the effective matching value may not follow the rules described above and will be defined
     * by the Android device manufacturer.
     * </p>
     *
     * @return the effective minimum voltage supported by this power profile and {@code profile},
     * where a valid value is 0 or greater. If this metric is not supported by the match between
     * two profiles, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will be returned.
     * If the provided power profile does not match with this one, then
     * {@link #POWER_PROFILE_ERROR_NO_MATCH} will be returned.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    @CheckResult
    public int getMatchingMinVoltageMv(@NonNull PowerProfileInfo profile) {
        PowerProfileMatchInfo profileMatchInfo = getPowerProfileMatchInfo(profile);

        if (profile.getPowerProfileType() == POWER_PROFILE_TYPE_NONE ||
                mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }

        if (profileMatchInfo != null) {
            return profileMatchInfo.getMinVoltageMv();
        } else {
            return POWER_PROFILE_ERROR_NO_MATCH;
        }
    }

    /**
     * Returns the effective maximum voltage in mV from the matching power capabilities between
     * this PowerProfileInfo and the one given in {@code profile}.
     * <p>
     * For type {@link #POWER_PROFILE_TYPE_BATTERY}, {@link #POWER_PROFILE_TYPE_VARIABLE}, and
     * {@link #POWER_PROFILE_TYPE_SPR_PPS}, the power sink's maximum voltage is expected to be
     * less than or equal to the power source's maximum voltage. The maximum voltage will then
     * be equal to the sink's maximum voltage.
     * </p>
     * <p>
     * For type {@link #POWER_PROFILE_TYPE_RP_DEFAULT}, {@link #POWER_PROFILE_TYPE_RP_1_5A},
     * {@link #POWER_PROFILE_TYPE_RP_3_0A}, {@link #POWER_PROFILE_TYPE_FIXED}, and
     * {@link #POWER_PROFILE_TYPE_SPR_AVS}, the maximum voltage is expected to be equal between
     * the source and the sink power profiles.
     * </p>
     * <p>
     * When at least one power profile in a match is of type {@link #POWER_PROFILE_TYPE_VENDOR},
     * the effective matching value may not follow the rules described above and will be defined
     * by the Android device manufacturer.
     * </p>
     *
     * @return the effective minimum voltage supported by this power profile and {@code profile},
     * where a valid value is 0 or greater. If this metric is not supported by the match between
     * two profiles, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will be returned.
     * If the provided power profile does not match with this one, then
     * {@link #POWER_PROFILE_ERROR_NO_MATCH} will be returned.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    @CheckResult
    public int getMatchingMaxVoltageMv(@NonNull PowerProfileInfo profile) {
        PowerProfileMatchInfo profileMatchInfo = getPowerProfileMatchInfo(profile);

        if (profile.getPowerProfileType() == POWER_PROFILE_TYPE_NONE ||
                mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }

        if (profileMatchInfo != null) {
            return profileMatchInfo.getMaxVoltageMv();
        } else {
            return POWER_PROFILE_ERROR_NO_MATCH;
        }
    }

    /**
     * Returns the effective minimum current in mA from the matching power capabilities between
     * this PowerProfileInfo and the one given in {@code profile}.
     * <p>
     * No standardized power profile type currently reports a minimum current.
     * </p>
     * <p>
     * When at least one power profile in a match is of type {@link #POWER_PROFILE_TYPE_VENDOR},
     * the effective matching value may not follow the rules described above and will be defined
     * by the Android device manufacturer.
     * </p>
     *
     * @return the effective minimum voltage supported by this power profile and {@code profile},
     * where a valid value is 0 or greater. If this metric is not supported by the match between
     * two profiles, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will be returned.
     * If the provided power profile does not match with this one, then
     * {@link #POWER_PROFILE_ERROR_NO_MATCH} will be returned.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    @CheckResult
    public int getMatchingMinCurrentMa(@NonNull PowerProfileInfo profile) {
        PowerProfileMatchInfo profileMatchInfo = getPowerProfileMatchInfo(profile);

        if (profile.getPowerProfileType() == POWER_PROFILE_TYPE_NONE ||
                mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }

        if (profileMatchInfo != null) {
            return profileMatchInfo.getMinCurrentMa();
        } else {
            return POWER_PROFILE_ERROR_NO_MATCH;
        }
    }

    /**
     * Returns the effective maximum current in mA from the matching power capabilities between
     * this PowerProfileInfo and the one given in {@code profile}.
     * <p>
     * For type {@link #POWER_PROFILE_TYPE_VARIABLE}, {@link #POWER_PROFILE_TYPE_SPR_PPS}, and
     * {@link #POWER_PROFILE_TYPE_SPR_AVS}, the power sink's maximum current is expected to be
     * less than or equal to the power source's maximum current. The maximum current will then
     * be equal to the sink's maximum current.
     * </p>
     * <p>
     * For type {@link #POWER_PROFILE_TYPE_RP_DEFAULT}, {@link #POWER_PROFILE_TYPE_RP_1_5A},
     * {@link #POWER_PROFILE_TYPE_RP_3_0A}, and {@link #POWER_PROFILE_TYPE_FIXED},
     * the maximum current is expected to be the lesser between the source and the sink power
     * profiles.
     * </p>
     * <p>
     * When at least one power profile in a match is of type {@link #POWER_PROFILE_TYPE_VENDOR},
     * the effective matching value may not follow the rules described above and will be defined
     * by the Android device manufacturer.
     * </p>
     *
     * @return the effective minimum voltage supported by this power profile and {@code profile},
     * where a valid value is 0 or greater. If this metric is not supported by the match between
     * two profiles, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will be returned.
     * If the provided power profile does not match with this one, then
     * {@link #POWER_PROFILE_ERROR_NO_MATCH} will be returned.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    @CheckResult
    public int getMatchingMaxCurrentMa(@NonNull PowerProfileInfo profile) {
        PowerProfileMatchInfo profileMatchInfo = getPowerProfileMatchInfo(profile);

        if (profile.getPowerProfileType() == POWER_PROFILE_TYPE_NONE ||
                mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }

        if (profileMatchInfo != null) {
            return profileMatchInfo.getMaxCurrentMa();
        } else {
            return POWER_PROFILE_ERROR_NO_MATCH;
        }
    }

    /**
     * Returns the effective maximum power in mW from the matching power capabilities between
     * this PowerProfileInfo and the one given in {@code profile}.
     * <p>
     * For type {@link #POWER_PROFILE_TYPE_BATTERY}, the power sink's maximum power is expected to
     * be less than or equal to the power source's maximum power. The maximum power is expected to
     * be equal to the power sink's maximum power.
     * </p>
     * <p>
     * When at least one power profile in a match is of type {@link #POWER_PROFILE_TYPE_VENDOR},
     * the effective matching value may not follow the rules described above and will be defined
     * by the Android device manufacturer.
     * </p>
     *
     * @return the effective minimum voltage supported by this power profile and {@code profile},
     * where a valid value is 0 or greater. If this metric is not supported by the match between
     * two profiles, then {@link #POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED} will be returned.
     * If the provided power profile does not match with this one, then
     * {@link #POWER_PROFILE_ERROR_NO_MATCH} will be returned.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    @CheckResult
    public int getMatchingMaxPowerMw(@NonNull PowerProfileInfo profile) {
        PowerProfileMatchInfo profileMatchInfo = getPowerProfileMatchInfo(profile);

        if (profile.getPowerProfileType() == POWER_PROFILE_TYPE_NONE ||
                mPowerProfileType == POWER_PROFILE_TYPE_NONE) {
            return POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }

        if (profileMatchInfo != null) {
            return profileMatchInfo.getMaxPowerMw();
        } else {
            return POWER_PROFILE_ERROR_NO_MATCH;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeInt(mPowerProfileType);
        dest.writeInt(mMinVoltageMv);
        dest.writeInt(mMaxVoltageMv);
        dest.writeInt(mMinCurrentMa);
        dest.writeInt(mMaxCurrentMa);
        dest.writeInt(mMaxPowerMw);
    }

    public static final @NonNull Parcelable.Creator<PowerProfileInfo> CREATOR =
            new Parcelable.Creator<PowerProfileInfo>() {
        @Override
        public PowerProfileInfo createFromParcel(Parcel in) {
            PowerProfileInfo.Builder builder = new PowerProfileInfo.Builder();
            builder.setName(in.readString());
            builder.setPowerProfileType(in.readInt());
            builder.setMinVoltageMv(in.readInt());
            builder.setMaxVoltageMv(in.readInt());
            builder.setMinCurrentMa(in.readInt());
            builder.setMaxCurrentMa(in.readInt());
            builder.setMaxPowerMw(in.readInt());

            return new PowerProfileInfo(builder);
        }

        @Override
        public PowerProfileInfo[] newArray(int size) {
            return new PowerProfileInfo[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        StringBuilder mString = new StringBuilder("PowerProfileInfo{type="
                + powerProfileTypeToString(mPowerProfileType) + ", ");
        switch (mPowerProfileType) {
            case POWER_PROFILE_TYPE_VENDOR:
                mString.append("name=" + mName + ", minVoltageMv=" + mMinVoltageMv
                        + ", maxVoltageMv=" + mMaxVoltageMv + ", minCurrentMa=" + mMinCurrentMa
                        + ", maxCurrentMa=" + mMaxCurrentMa + ", maxPowerMw=" + mMaxPowerMw);
                break;
            case POWER_PROFILE_TYPE_RP_DEFAULT:
                mString.append("maxVoltageMv=5000, maxCurrentMa=" + mMaxCurrentMa);
                break;
            case POWER_PROFILE_TYPE_RP_1_5A:
            case POWER_PROFILE_TYPE_RP_3_0A:
                mString.append("maxVoltageMv=5000, maxCurrentMa=" + mMaxCurrentMa);
                break;
            case POWER_PROFILE_TYPE_FIXED:
                mString.append(", maxVoltageMv=" + mMaxVoltageMv + ", maxCurrentMa="
                        + mMaxCurrentMa);
                break;
            case POWER_PROFILE_TYPE_BATTERY:
                mString.append(", minVoltageMv=" + mMinVoltageMv + ", maxVoltageMv="
                        + mMaxVoltageMv + ", maxPowerMw=" + mMaxPowerMw);
                break;
            case POWER_PROFILE_TYPE_VARIABLE:
                mString.append(", minVoltageMv=" + mMinVoltageMv + ", maxVoltageMv="
                        + mMaxVoltageMv + ", maxCurrentMa=" + mMaxCurrentMa);
                break;
            case POWER_PROFILE_TYPE_SPR_PPS:
                mString.append(", minVoltageMv=" + mMinVoltageMv + ", maxVoltageMv="
                        + mMaxVoltageMv + ", maxCurrentMa=" + mMaxCurrentMa);
                break;
            case POWER_PROFILE_TYPE_SPR_AVS:
                mString.append(", maxVoltageMv=" + mMaxVoltageMv + ", maxCurrentMa="
                        + mMaxCurrentMa);
                break;
            default:
                break;
        }
        mString.append("}");
        return mString.toString();
    }

    /** @hide */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PowerProfileInfo profile = (PowerProfileInfo) obj;
        return mName == profile.mName &&
                mPowerProfileType == profile.mPowerProfileType &&
                mMinVoltageMv == profile.mMinVoltageMv &&
                mMaxVoltageMv == profile.mMaxVoltageMv &&
                mMinCurrentMa == profile.mMinCurrentMa &&
                mMaxCurrentMa == profile.mMaxCurrentMa &&
                mMaxPowerMw == profile.mMaxPowerMw;
    }

    /** @hide */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(mName, mPowerProfileType, mMinVoltageMv, mMaxVoltageMv,
                mMinCurrentMa, mMaxCurrentMa, mMaxPowerMw);
    }

    /** @hide */
    public static final class Builder {
        private String mName;
        private @PowerProfileType int mPowerProfileType;
        private int mMinVoltageMv;
        private int mMaxVoltageMv;
        private int mMinCurrentMa;
        private int mMaxCurrentMa;
        private int mMaxPowerMw;

        public Builder() {
            mName = null;
            mPowerProfileType = POWER_PROFILE_TYPE_NONE;
            mMinVoltageMv = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
            mMaxVoltageMv = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
            mMinCurrentMa = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
            mMaxCurrentMa = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
            mMaxPowerMw = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }

        /**
         * Sets the profile name of {@link PowerProfileInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setName(@NonNull String name) {
            mName = name;
            return this;
        }

        /**
         * Sets the power profile type of {@link PowerProfileInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setPowerProfileType(@PowerProfileType int type) {
            mPowerProfileType = type;
            return this;
        }

        /**
         * Sets the min voltage of {@link PowerProfileInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMinVoltageMv(int voltageMv) {
            mMinVoltageMv = voltageMv;
            return this;
        }

        /**
         * Sets the max voltage of {@link PowerProfileInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMaxVoltageMv(int voltageMv) {
            mMaxVoltageMv = voltageMv;
            return this;
        }

        /**
         * Sets the min current of {@link PowerProfileInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMinCurrentMa(int currentMa) {
            mMinCurrentMa = currentMa;
            return this;
        }

        /**
         * Sets the max current of {@link PowerProfileInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMaxCurrentMa(int currentMa) {
            mMaxCurrentMa = currentMa;
            return this;
        }

        /**
         * Sets the max power of {@link PowerProfileInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMaxPowerMw(int powerMw) {
            mMaxPowerMw = powerMw;
            return this;
        }

        /**
         * Creates the {@link PowerProfileInfo} object.
         */
        @NonNull
        public PowerProfileInfo build() {
            return new PowerProfileInfo(this);
        }

    }
}

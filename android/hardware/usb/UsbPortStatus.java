/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.Immutable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes the status of a USB port.
 *
 * @hide
 */
@Immutable
@SystemApi
public final class UsbPortStatus implements Parcelable {
    private static final String TAG = "UsbPortStatus";
    private final int mCurrentMode;
    private final @UsbPowerRole int mCurrentPowerRole;
    private final @UsbDataRole int mCurrentDataRole;
    private final int mSupportedRoleCombinations;
    private final @ContaminantProtectionStatus int mContaminantProtectionStatus;
    private final @ContaminantDetectionStatus int mContaminantDetectionStatus;
    private final boolean mPowerTransferLimited;
    private final @UsbDataStatus int mUsbDataStatus;
    private final @PowerBrickConnectionStatus int mPowerBrickConnectionStatus;
    private final @NonNull @ComplianceWarning int[] mComplianceWarnings;
    private final @PlugState int mPlugState;
    /**
     * Holds the DisplayPort Alt Mode info for the Port. This field
     * is null if the device does not support DisplayPort Alt Mode.
     */
    private final @Nullable DisplayPortAltModeInfo mDisplayPortAltModeInfo;
    private final @Bc12Type int mPartnerBc12Type;
    private final @NonNull List<PowerProfileInfo> mPortSinkPowerProfiles;
    private final @NonNull List<PowerProfileInfo> mPortSourcePowerProfiles;
    private final @NonNull List<PowerProfileInfo> mPartnerSinkPowerProfiles;
    private final @NonNull List<PowerProfileInfo> mPartnerSourcePowerProfiles;
    private final @NonNull List<PowerProfileMatchInfo> mPortSinkPowerProfileMatches;
    private final @NonNull List<PowerProfileMatchInfo> mPortSourcePowerProfileMatches;

    /**
     * Power role: This USB port does not have a power role.
     */
    public static final int POWER_ROLE_NONE = 0;

    /**
     * Power role: This USB port can act as a source (provide power).
     */
    public static final int POWER_ROLE_SOURCE = 1;

    /**
     * Power role: This USB port can act as a sink (receive power).
     */
    public static final int POWER_ROLE_SINK = 2;

    @IntDef(prefix = { "POWER_ROLE_" }, value = {
            POWER_ROLE_NONE,
            POWER_ROLE_SOURCE,
            POWER_ROLE_SINK
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface UsbPowerRole{}

    /**
     * Power role: This USB port does not have a data role.
     */
    public static final int DATA_ROLE_NONE = 0;

    /**
     * Data role: This USB port can act as a host (access data services).
     */
    public static final int DATA_ROLE_HOST = 1;

    /**
     * Data role: This USB port can act as a device (offer data services).
     */
    public static final int DATA_ROLE_DEVICE = 2;

    @IntDef(prefix = { "DATA_ROLE_" }, value = {
            DATA_ROLE_NONE,
            DATA_ROLE_HOST,
            DATA_ROLE_DEVICE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface UsbDataRole{}

    /**
     * There is currently nothing connected to this USB port.
     */
    public static final int MODE_NONE = 0;

    /**
     * This USB port can act as an upstream facing port (device).
     *
     * <p> Implies that the port supports the {@link #POWER_ROLE_SINK} and
     * {@link #DATA_ROLE_DEVICE} combination of roles (and possibly others as well).
     */
    public static final int MODE_UFP = 1 << 0;

    /**
     * This USB port can act as a downstream facing port (host).
     *
     * <p> Implies that the port supports the {@link #POWER_ROLE_SOURCE} and
     * {@link #DATA_ROLE_HOST} combination of roles (and possibly others as well).
     */
    public static final int MODE_DFP = 1 << 1;

    /**
     * This USB port can act either as an downstream facing port (host) or as
     * an upstream facing port (device).
     *
     * <p> Implies that the port supports the {@link #POWER_ROLE_SOURCE} and
     * {@link #DATA_ROLE_HOST} combination of roles and the {@link #POWER_ROLE_SINK} and
     * {@link #DATA_ROLE_DEVICE} combination of roles (and possibly others as well).
     *
     * @hide
     */
    public static final int MODE_DUAL = MODE_UFP | MODE_DFP;

    /**
     * This USB port can support USB Type-C Audio accessory.
     */
    public static final int MODE_AUDIO_ACCESSORY = 1 << 2;

    /**
     * This USB port can support USB Type-C debug accessory.
     */
    public static final int MODE_DEBUG_ACCESSORY = 1 << 3;

   /**
     * Contaminant presence detection not supported by the device.
     * @hide
     */
    public static final int CONTAMINANT_DETECTION_NOT_SUPPORTED = 0;

    /**
     * Contaminant presence detection supported but disabled.
     * @hide
     */
    public static final int CONTAMINANT_DETECTION_DISABLED = 1;

    /**
     * Contaminant presence enabled but not detected.
     * @hide
     */
    public static final int CONTAMINANT_DETECTION_NOT_DETECTED = 2;

    /**
     * Contaminant presence enabled and detected.
     * @hide
     */
    public static final int CONTAMINANT_DETECTION_DETECTED = 3;

    /**
     * Contaminant protection - No action performed upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_NONE = 0;

    /**
     * Contaminant protection - Port is forced to sink upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_SINK = 1 << 0;

    /**
     * Contaminant protection - Port is forced to source upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_SOURCE = 1 << 1;

    /**
     * Contaminant protection - Port is disabled upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_FORCE_DISABLE = 1 << 2;

    /**
     * Contaminant protection - Port is disabled upon detection of
     * contaminant presence.
     * @hide
     */
    public static final int CONTAMINANT_PROTECTION_DISABLED = 1 << 3;

    /**
     * USB data status is not known.
     */
    public static final int DATA_STATUS_UNKNOWN = 0;

    /**
     * USB data is enabled.
     */
    public static final int DATA_STATUS_ENABLED = 1 << 0;

    /**
     * USB data is disabled as the port is too hot.
     */
    public static final int DATA_STATUS_DISABLED_OVERHEAT = 1 << 1;

    /**
     * USB data is disabled due to contaminated port.
     */
    public static final int DATA_STATUS_DISABLED_CONTAMINANT = 1 << 2;

    /**
     * This flag indicates that some or all data modes are disabled
     * due to docking event, and the specific sub-statuses viz.,
     * {@link #DATA_STATUS_DISABLED_DOCK_HOST_MODE},
     * {@link #DATA_STATUS_DISABLED_DOCK_DEVICE_MODE}
     * can be checked for individual modes.
     */
    public static final int DATA_STATUS_DISABLED_DOCK = 1 << 3;

    /**
     * USB data is disabled by
     * {@link UsbPort#enableUsbData UsbPort.enableUsbData}.
     */
    public static final int DATA_STATUS_DISABLED_FORCE = 1 << 4;

    /**
     * USB data is disabled for debug.
     */
    public static final int DATA_STATUS_DISABLED_DEBUG = 1 << 5;

    /**
     * USB host mode is disabled due to docking event.
     * {@link #DATA_STATUS_DISABLED_DOCK} will be set as well.
     */
    public static final int DATA_STATUS_DISABLED_DOCK_HOST_MODE = 1 << 6;

    /**
     * USB device mode is disabled due to docking event.
     * {@link #DATA_STATUS_DISABLED_DOCK} will be set as well.
     */
    public static final int DATA_STATUS_DISABLED_DOCK_DEVICE_MODE = 1 << 7;

    /**
     * Unknown whether a power brick is connected.
     */
    public static final int POWER_BRICK_STATUS_UNKNOWN = 0;

    /**
     * The connected device is a power brick.
     */
    public static final int POWER_BRICK_STATUS_CONNECTED = 1;

    /**
     * The connected device is not power brick.
     */
    public static final int POWER_BRICK_STATUS_DISCONNECTED = 2;

    /**
     * Used to indicate attached sources/cables/accessories/ports
     * that do not match the other warnings below and do not meet the
     * requirements of specifications including but not limited to
     * USB Type-C Cable and Connector, Universal Serial Bus
     * Power Delivery, and Universal Serial Bus 1.x/2.0/3.x/4.0.
     * In addition, constants introduced after the target sdk will be
     * remapped into COMPLIANCE_WARNING_OTHER.
     */
    public static final int COMPLIANCE_WARNING_OTHER = 1;

    /**
     * Used to indicate Type-C port partner
     * (cable/accessory/source) that identifies itself as debug
     * accessory source as defined in USB Type-C Cable and
     * Connector Specification. However, the specification states
     * that this is meant for debug only and shall not be used for
     * with commercial products.
     */
    public static final int COMPLIANCE_WARNING_DEBUG_ACCESSORY = 2;

    /**
     * Used to indicate USB ports that does not
     * identify itself as one of the charging port types (SDP/CDP
     * DCP etc) as defined by Battery Charging v1.2 Specification.
     */
    public static final int COMPLIANCE_WARNING_BC_1_2 = 3;

    /**
     * Used to indicate Type-C sources/cables that are missing pull
     * up resistors on the CC pins as required by USB Type-C Cable
     * and Connector Specification.
     */
    public static final int COMPLIANCE_WARNING_MISSING_RP = 4;

    /**
     * Used to indicate the charging setups on the USB ports are unable to
     * deliver negotiated power. Introduced in Android V (API level 35)
     * and client applicantions that target API levels lower than 35 will
     * receive {@link #COMPLIANCE_WARNING_OTHER} instead.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_USB_DATA_COMPLIANCE_WARNING)
    public static final int COMPLIANCE_WARNING_INPUT_POWER_LIMITED = 5;

    /**
     * Used to indicate the cable/connector on the USB ports are missing
     * the required wires on the data pins to make data transfer.
     * Introduced in Android V (API level 35) and client applicantions that
     * target API levels lower than 35 will receive
     * {@link #COMPLIANCE_WARNING_OTHER} instead.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_USB_DATA_COMPLIANCE_WARNING)
    public static final int COMPLIANCE_WARNING_MISSING_DATA_LINES = 6;

    /**
     * Used to indicate enumeration failures on the USB ports, potentially due to
     * signal integrity issues or other causes. Introduced in Android V
     * (API level 35) and client applicantions that target API levels lower
     * than 35 will receive {@link #COMPLIANCE_WARNING_OTHER} instead.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_USB_DATA_COMPLIANCE_WARNING)
    public static final int COMPLIANCE_WARNING_ENUMERATION_FAIL = 7;

    /**
     * Used to indicate unexpected data disconnection on the USB ports,
     * potentially due to signal integrity issues or other causes.
     * Introduced in Android V (API level 35) and client applicantions that
     * target API levels lower than 35 will receive
     * {@link #COMPLIANCE_WARNING_OTHER} instead.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_USB_DATA_COMPLIANCE_WARNING)
    public static final int COMPLIANCE_WARNING_FLAKY_CONNECTION = 8;

    /**
     * Used to indicate unreliable or slow data transfer on the USB ports,
     * potentially due to signal integrity issues or other causes.
     * Introduced in Android V (API level 35) and client applicantions that
     * target API levels lower than 35 will receive
     * {@link #COMPLIANCE_WARNING_OTHER} instead.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_USB_DATA_COMPLIANCE_WARNING)
    public static final int COMPLIANCE_WARNING_UNRELIABLE_IO = 9;

    /**
     * Indicates that the Type-C plug orientation cannot be
     * determined because the connected state of the device is unknown.
     */
    public static final int PLUG_STATE_UNKNOWN = 0;

    /**
     * Indicates no Type-C plug is inserted into the device.
     */
    public static final int PLUG_STATE_UNPLUGGED = 1;

    /**
     * Indicates a Type-C plug is inserted into the device, but
     * the orientation cannot be determined.
     */
    public static final int PLUG_STATE_PLUGGED_ORIENTATION_UNKNOWN = 2;

    /**
     * Indicates that the connected plug uses its CC1
     * pin to manage the Source-to-Sink connection.
     */
    public static final int PLUG_STATE_PLUGGED_ORIENTATION_NORMAL = 3;

    /**
     * Indicates that the connected plug uses its CC2
     * pin to manage the Source-to-Sink connection.
     */
    public static final int PLUG_STATE_PLUGGED_ORIENTATION_FLIPPED = 4;

    /**
     * Indicates that the type of charger as defined by the Battery Charging Specification,
     * Revision 1.2 (BC 1.2) has not been identified.
     *
     * <b><Note:</b> The BC 1.2 specification can be found <a href="https://www.usb.org/document-library/battery-charging-v12-spec-and-adopters-agreement">here</a>
     *
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    public static final int BC12_TYPE_UNKNOWN = 0;

    /**
     * Indicates that the BC 1.2 charger type is a Standard Downstream Port (SDP) as defined by
     * the BC 1.2 specification.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    public static final int BC12_TYPE_SDP = 1;

    /**
     * Indicates that the BC 1.2 charger type is Charging Downstream Port (CDP) as defined by the
     * BC 1.2 specification.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    public static final int BC12_TYPE_CDP = 2;

    /**
     * Indicates that the BC 1.2 charger type is Dedicated Charging Port (DCP) as defined by the
     * BC 1.2 specification.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    public static final int BC12_TYPE_DCP = 3;

    @IntDef(prefix = { "CONTAMINANT_DETECTION_" }, value = {
            CONTAMINANT_DETECTION_NOT_SUPPORTED,
            CONTAMINANT_DETECTION_DISABLED,
            CONTAMINANT_DETECTION_NOT_DETECTED,
            CONTAMINANT_DETECTION_DETECTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ContaminantDetectionStatus{}

    @IntDef(prefix = { "CONTAMINANT_PROTECTION_" }, flag = true, value = {
            CONTAMINANT_PROTECTION_NONE,
            CONTAMINANT_PROTECTION_SINK,
            CONTAMINANT_PROTECTION_SOURCE,
            CONTAMINANT_PROTECTION_FORCE_DISABLE,
            CONTAMINANT_PROTECTION_DISABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ContaminantProtectionStatus{}

    @IntDef(prefix = { "MODE_" }, value = {
            MODE_NONE,
            MODE_DFP,
            MODE_UFP,
            MODE_AUDIO_ACCESSORY,
            MODE_DEBUG_ACCESSORY,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface UsbPortMode{}

    @IntDef(prefix = { "COMPLIANCE_WARNING_" }, value = {
            COMPLIANCE_WARNING_OTHER,
            COMPLIANCE_WARNING_DEBUG_ACCESSORY,
            COMPLIANCE_WARNING_BC_1_2,
            COMPLIANCE_WARNING_MISSING_RP,
            COMPLIANCE_WARNING_INPUT_POWER_LIMITED,
            COMPLIANCE_WARNING_MISSING_DATA_LINES,
            COMPLIANCE_WARNING_ENUMERATION_FAIL,
            COMPLIANCE_WARNING_FLAKY_CONNECTION,
            COMPLIANCE_WARNING_UNRELIABLE_IO,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ComplianceWarning{}

    @IntDef(prefix = { "PLUG_STATE_" }, value = {
            PLUG_STATE_UNKNOWN,
            PLUG_STATE_UNPLUGGED,
            PLUG_STATE_PLUGGED_ORIENTATION_UNKNOWN,
            PLUG_STATE_PLUGGED_ORIENTATION_NORMAL,
            PLUG_STATE_PLUGGED_ORIENTATION_FLIPPED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface PlugState{}

    /** @hide */
    @IntDef(prefix = { "DATA_STATUS_" }, flag = true, value = {
            DATA_STATUS_UNKNOWN,
            DATA_STATUS_ENABLED,
            DATA_STATUS_DISABLED_OVERHEAT,
            DATA_STATUS_DISABLED_CONTAMINANT,
            DATA_STATUS_DISABLED_DOCK,
            DATA_STATUS_DISABLED_DOCK_HOST_MODE,
            DATA_STATUS_DISABLED_DOCK_DEVICE_MODE,
            DATA_STATUS_DISABLED_FORCE,
            DATA_STATUS_DISABLED_DEBUG,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface UsbDataStatus{}

    /** @hide */
    @IntDef(prefix = { "POWER_BRICK_STATUS_" }, value = {
            POWER_BRICK_STATUS_UNKNOWN,
            POWER_BRICK_STATUS_DISCONNECTED,
            POWER_BRICK_STATUS_CONNECTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface PowerBrickConnectionStatus{}

    @IntDef(prefix = { "BC12_TYPE_" }, value = {
            BC12_TYPE_UNKNOWN,
            BC12_TYPE_SDP,
            BC12_TYPE_CDP,
            BC12_TYPE_DCP,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Bc12Type{}

    /** @hide */
    public UsbPortStatus(Builder builder) {
        int portIndex;
        int partnerIndex;

        mCurrentMode = builder.mCurrentMode;
        mCurrentPowerRole = builder.mCurrentPowerRole;
        mCurrentDataRole = builder.mCurrentDataRole;
        mSupportedRoleCombinations = builder.mSupportedRoleCombinations;
        mContaminantProtectionStatus = builder.mContaminantProtectionStatus;
        mContaminantDetectionStatus = builder.mContaminantDetectionStatus;
        mPowerTransferLimited = builder.mPowerTransferLimited;
        mUsbDataStatus = builder.mUsbDataStatus;
        mPowerBrickConnectionStatus = builder.mPowerBrickConnectionStatus;
        mComplianceWarnings = builder.mComplianceWarnings;
        mPlugState = builder.mPlugState;
        mDisplayPortAltModeInfo = builder.mDisplayPortAltModeInfo;
        mPartnerBc12Type = builder.mPartnerBc12Type;
        mPortSinkPowerProfiles = builder.mPortSinkPowerProfiles;
        mPortSourcePowerProfiles = builder.mPortSourcePowerProfiles;
        mPartnerSinkPowerProfiles = builder.mPartnerSinkPowerProfiles;
        mPartnerSourcePowerProfiles = builder.mPartnerSourcePowerProfiles;
        mPortSinkPowerProfileMatches = builder.mPortSinkPowerProfileMatches;
        mPortSourcePowerProfileMatches = builder.mPortSourcePowerProfileMatches;

        // Assign Matching Profiles to Power Profiles + Match Info
        for (PowerProfileMatchInfo matchInfo : mPortSinkPowerProfileMatches) {
            portIndex = matchInfo.getPortIndex();
            partnerIndex = matchInfo.getPartnerIndex();
            mPortSinkPowerProfiles.get(portIndex).addMatchingPowerProfile(
                mPartnerSourcePowerProfiles.get(partnerIndex), matchInfo);
            mPartnerSourcePowerProfiles.get(partnerIndex).addMatchingPowerProfile(
                mPortSinkPowerProfiles.get(portIndex), matchInfo);
        }

        for (PowerProfileMatchInfo matchInfo : mPortSourcePowerProfileMatches) {
            portIndex = matchInfo.getPortIndex();
            partnerIndex = matchInfo.getPartnerIndex();
            mPortSourcePowerProfiles.get(portIndex).addMatchingPowerProfile(
                mPartnerSinkPowerProfiles.get(partnerIndex), matchInfo);
            mPartnerSinkPowerProfiles.get(partnerIndex).addMatchingPowerProfile(
                mPortSourcePowerProfiles.get(portIndex), matchInfo);
        }
    }

    /** @hide */
    public UsbPortStatus(int currentMode, int currentPowerRole, int currentDataRole,
            int supportedRoleCombinations, int contaminantProtectionStatus,
            int contaminantDetectionStatus, @UsbDataStatus int usbDataStatus,
            boolean powerTransferLimited,
            @PowerBrickConnectionStatus int powerBrickConnectionStatus,
            @NonNull @ComplianceWarning int[] complianceWarnings,
            int plugState,
            @Nullable DisplayPortAltModeInfo displayPortAltModeInfo) {
        mCurrentMode = currentMode;
        mCurrentPowerRole = currentPowerRole;
        mCurrentDataRole = currentDataRole;
        mSupportedRoleCombinations = supportedRoleCombinations;
        mContaminantProtectionStatus = contaminantProtectionStatus;
        mContaminantDetectionStatus = contaminantDetectionStatus;

        // Older implementations that only set the DISABLED_DOCK_MODE will have the other two
        // set at the HAL interface level, so the "dock mode only" state shouldn't be visible here.
        // But the semantics are ensured here.
        int disabledDockModes = (usbDataStatus &
            (DATA_STATUS_DISABLED_DOCK_HOST_MODE | DATA_STATUS_DISABLED_DOCK_DEVICE_MODE));
        if (disabledDockModes != 0) {
            // Set DATA_STATUS_DISABLED_DOCK when one of DATA_STATUS_DISABLED_DOCK_*_MODE is set
            usbDataStatus |= DATA_STATUS_DISABLED_DOCK;
        } else {
            // Clear DATA_STATUS_DISABLED_DOCK when none of DATA_STATUS_DISABLED_DOCK_*_MODE is set
            usbDataStatus &= ~DATA_STATUS_DISABLED_DOCK;
        }

        mUsbDataStatus = usbDataStatus;
        mPowerTransferLimited = powerTransferLimited;
        mPowerBrickConnectionStatus = powerBrickConnectionStatus;
        mComplianceWarnings = complianceWarnings;
        mPlugState = plugState;
        mDisplayPortAltModeInfo = displayPortAltModeInfo;
        mPartnerBc12Type = BC12_TYPE_UNKNOWN;
        mPortSinkPowerProfiles = Collections.emptyList();
        mPortSourcePowerProfiles = Collections.emptyList();
        mPartnerSinkPowerProfiles = Collections.emptyList();
        mPartnerSourcePowerProfiles = Collections.emptyList();
        mPortSinkPowerProfileMatches = Collections.emptyList();
        mPortSourcePowerProfileMatches = Collections.emptyList();
    }

    /** @hide */
    public UsbPortStatus(int currentMode, int currentPowerRole, int currentDataRole,
            int supportedRoleCombinations, int contaminantProtectionStatus,
            int contaminantDetectionStatus, @UsbDataStatus int usbDataStatus,
            boolean powerTransferLimited,
            @PowerBrickConnectionStatus int powerBrickConnectionStatus) {
        this(currentMode, currentPowerRole, currentDataRole, supportedRoleCombinations,
                contaminantProtectionStatus, contaminantDetectionStatus,
                usbDataStatus, powerTransferLimited, powerBrickConnectionStatus,
                new int[] {}, PLUG_STATE_UNKNOWN, null);
    }

    /** @hide */
    public UsbPortStatus(int currentMode, int currentPowerRole, int currentDataRole,
            int supportedRoleCombinations, int contaminantProtectionStatus,
            int contaminantDetectionStatus) {
        this(currentMode, currentPowerRole, currentDataRole, supportedRoleCombinations,
                contaminantProtectionStatus, contaminantDetectionStatus,
                DATA_STATUS_UNKNOWN, false, POWER_BRICK_STATUS_UNKNOWN,
                new int[] {}, PLUG_STATE_UNKNOWN, null);
    }

    /**
     * Returns true if there is anything connected to the port.
     *
     * @return {@code true} iff there is anything connected to the port.
     */
    public boolean isConnected() {
        return mCurrentMode != 0;
    }

    /**
     * Gets the current mode of the port.
     *
     * @return The current mode: {@link #MODE_DFP}, {@link #MODE_UFP},
     * {@link #MODE_AUDIO_ACCESSORY}, {@link #MODE_DEBUG_ACCESSORY}, or {@link #MODE_NONE} if
     * nothing is connected.
     */
    public @UsbPortMode int getCurrentMode() {
        return mCurrentMode;
    }

    /**
     * Gets the current power role of the port.
     *
     * @return The current power role: {@link #POWER_ROLE_SOURCE}, {@link #POWER_ROLE_SINK}, or
     * {@link #POWER_ROLE_NONE} if nothing is connected.
     */
    public @UsbPowerRole int getCurrentPowerRole() {
        return mCurrentPowerRole;
    }

    /**
     * Gets the current data role of the port.
     *
     * @return The current data role: {@link #DATA_ROLE_HOST}, {@link #DATA_ROLE_DEVICE}, or
     * {@link #DATA_ROLE_NONE} if nothing is connected.
     */
    public @UsbDataRole int getCurrentDataRole() {
        return mCurrentDataRole;
    }

    /**
     * Returns true if the specified power and data role combination is supported
     * given what is currently connected to the port.
     *
     * @param powerRole The power role to check: {@link #POWER_ROLE_SOURCE}  or
     *                  {@link #POWER_ROLE_SINK}, or {@link #POWER_ROLE_NONE} if no power role.
     * @param dataRole  The data role to check: either {@link #DATA_ROLE_HOST} or
     *                  {@link #DATA_ROLE_DEVICE}, or {@link #DATA_ROLE_NONE} if no data role.
     */
    public boolean isRoleCombinationSupported(@UsbPowerRole int powerRole,
            @UsbDataRole int dataRole) {
        return (mSupportedRoleCombinations &
                UsbPort.combineRolesAsBit(powerRole, dataRole)) != 0;
    }

    /**
     * This function checks if the port is USB Power Delivery (PD) compliant -
     * https://www.usb.org/usb-charger-pd. All of the power and data roles must be supported for a
     * port to be PD compliant.
     *
     * @return true if the port is PD compliant.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_IS_PD_COMPLIANT_API)
    public boolean isPdCompliant() {
        return isRoleCombinationSupported(POWER_ROLE_SINK, DATA_ROLE_DEVICE)
                && isRoleCombinationSupported(POWER_ROLE_SINK, DATA_ROLE_HOST)
                && isRoleCombinationSupported(POWER_ROLE_SOURCE, DATA_ROLE_DEVICE)
                && isRoleCombinationSupported(POWER_ROLE_SOURCE, DATA_ROLE_HOST);
    }

    /**
     * Get the supported role combinations.
     */
    public int getSupportedRoleCombinations() {
        return mSupportedRoleCombinations;
    }

    /**
     * Returns contaminant detection status.
     *
     * @hide
     */
    public @ContaminantDetectionStatus int getContaminantDetectionStatus() {
        return mContaminantDetectionStatus;
    }

    /**
     * Returns contamiant protection status.
     *
     * @hide
     */
    public @ContaminantProtectionStatus int getContaminantProtectionStatus() {
        return mContaminantProtectionStatus;
    }

    /**
     * Returns UsbData status.
     *
     * @return Current USB data status of the port with one or more of the following values
     *         {@link #DATA_STATUS_UNKNOWN}, {@link #DATA_STATUS_ENABLED},
     *         {@link #DATA_STATUS_DISABLED_OVERHEAT}, {@link #DATA_STATUS_DISABLED_CONTAMINANT},
     *         {@link #DATA_STATUS_DISABLED_DOCK}, {@link #DATA_STATUS_DISABLED_FORCE},
     *         {@link #DATA_STATUS_DISABLED_DEBUG}, {@link #DATA_STATUS_DISABLED_DOCK_HOST_MODE},
     *         {@link #DATA_STATUS_DISABLED_DOCK_DEVICE_MODE}
     */
    public @UsbDataStatus int getUsbDataStatus() {
        return mUsbDataStatus;
    }

    /**
     * Returns whether power transfer is limited.
     *
     * @return true when power transfer is limited.
     *         false otherwise.
     */
    public boolean isPowerTransferLimited() {
        return mPowerTransferLimited;
    }

    /**
     * Returns the connection status of the power brick.
     *
     * @return {@link #POWER_BRICK_STATUS_UNKNOWN}
     *         or {@link #POWER_BRICK_STATUS_CONNECTED}
     *         or {@link #POWER_BRICK_STATUS_DISCONNECTED}
     */
    public @PowerBrickConnectionStatus int getPowerBrickConnectionStatus() {
        return mPowerBrickConnectionStatus;
    }

    /**
     * Returns non compliant reasons, if any, for the connected
     * charger/cable/accessory/USB port.
     *
     * @return array including {@link #COMPLIANCE_WARNING_OTHER},
     *         {@link #COMPLIANCE_WARNING_DEBUG_ACCESSORY},
     *         {@link #COMPLIANCE_WARNING_BC_1_2},
     *         {@link #COMPLIANCE_WARNING_MISSING_RP}.
     */
    @CheckResult
    @NonNull
    public @ComplianceWarning int[] getComplianceWarnings() {
        return mComplianceWarnings;
    }

    /**
     * Returns the plug state of the attached cable/adapter.
     *
     */
    public @PlugState int getPlugState() {
        return mPlugState;
    }

    /**
     * Returns the DisplayPortInfo of the USB Port, if applicable.
     *
     * @return an instance of type DisplayPortInfo
     *         or null if not applicable.
     */
    @Nullable
    public DisplayPortAltModeInfo getDisplayPortAltModeInfo() {
        return (mDisplayPortAltModeInfo == null) ? null : mDisplayPortAltModeInfo;
    }

    /**
     * Returns the BC 1.2 Type of the connected port partner.
     *
     * @return a value of {@link #BC12_TYPE_UNKNOWN}, {@link #BC12_TYPE_SDP},
     *          {@link #BC12_TYPE_CDP}, or {@link #BC12_TYPE_DCP} when
     *          the USB port is capable of reporting the port partner BC 1.2 type,
     *          {@link #BC12_TYPE_UNKNOWN} otherwise.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    public @Bc12Type int getPartnerBc12Type() {
        return mPartnerBc12Type;
    }

    /**
     * Returns the port sink power profiles.
     *
     * @return a {@link #List} containing {@link #PowerProfileInfo} objects that describe the
     * USB power sink capabilities for the {@link #UsbPort} that this {@link #UsbPortStatus}
     * corresponds to. The list will be empty if the USB port does not support reporting
     * power profiles, or if the USB port does not sink power over USB.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    @NonNull
    public List<PowerProfileInfo> getPortSinkPowerProfiles() {
        return Collections.unmodifiableList(mPortSinkPowerProfiles);
    }

    /**
     * Returns the port source power profiles
     *
     * @return a {@link #List} containing {@link #PowerProfileInfo} objects that describe the
     * USB power source capabilities for the {@link #UsbPort} that this {@link #UsbPortStatus}
     * corresponds to. The list will be empty if the USB port does not support reporting
     * power profiles, or if the USB port does not source power over USB.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    @NonNull
    public List<PowerProfileInfo> getPortSourcePowerProfiles() {
        return Collections.unmodifiableList(mPortSourcePowerProfiles);
    }

    /**
     * Returns the partner sink power profiles
     *
     * @return a {@link #List} containing {@link #PowerProfileInfo} objects that describe the
     * USB power sink capabilities for the port partner of the {@link #UsbPort}
     * that this {@link #UsbPortStatus} corresponds to. The list will be empty if the USB port
     * does not support reporting power profiles, or if the USB port partner does not sink
     * power over USB.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    @NonNull
    public List<PowerProfileInfo> getPartnerSinkPowerProfiles() {
        return Collections.unmodifiableList(mPartnerSinkPowerProfiles);
    }

    /**
     * Returns the partner source power profiles
     *
     * @return a {@link #List} containing {@link #PowerProfileInfo} objects that describe the
     * USB power source capabilities for the port partner of the {@link #UsbPort}
     * that this {@link #UsbPortStatus} corresponds to. The list will be empty if the USB port
     * does not support reporting power profiles, or if the USB port partner does not source
     * power over USB.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
    @NonNull
    public List<PowerProfileInfo> getPartnerSourcePowerProfiles() {
        return Collections.unmodifiableList(mPartnerSourcePowerProfiles);
    }

    /** @hide */
    @NonNull
    public String getPowerProfileInfoString() {
        StringBuilder mString = new StringBuilder(" mPortSinkPowerProfiles={");
        for (PowerProfileInfo profile : mPortSinkPowerProfiles) {
            mString.append(profile.toString());
            mString.append(", ");
        }
        mString.append("} , mPortSourcePowerProfiles={");
        for (PowerProfileInfo profile : mPortSourcePowerProfiles) {
            mString.append(profile.toString());
            mString.append(", ");
        }
        mString.append("} , mPartnerSinkPowerProfiles={");
        for (PowerProfileInfo profile : mPartnerSinkPowerProfiles) {
            mString.append(profile.toString());
            mString.append(", ");
        }
        mString.append("} , mPartnerSourcePowerProfiles={");
        for (PowerProfileInfo profile : mPartnerSourcePowerProfiles) {
            mString.append(profile.toString());
            mString.append(", ");
        }
        mString.append("} , mPortSinkPowerProfileMatches={");
        for (PowerProfileMatchInfo matchInfo : mPortSinkPowerProfileMatches) {
            mString.append(matchInfo.toString());
            mString.append(", ");
        }
        mString.append("} , mPortSourcePowerProfileMatches={");
        for (PowerProfileMatchInfo matchInfo : mPortSourcePowerProfileMatches) {
            mString.append(matchInfo.toString());
            mString.append(", ");
        }
        mString.append("}");

        return mString.toString();
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder mString = new StringBuilder("UsbPortStatus{connected=" + isConnected()
                + ", currentMode=" + UsbPort.modeToString(mCurrentMode)
                + ", currentPowerRole=" + UsbPort.powerRoleToString(mCurrentPowerRole)
                + ", currentDataRole=" + UsbPort.dataRoleToString(mCurrentDataRole)
                + ", supportedRoleCombinations="
                        + UsbPort.roleCombinationsToString(mSupportedRoleCombinations)
                + ", contaminantDetectionStatus="
                        + getContaminantDetectionStatus()
                + ", contaminantProtectionStatus="
                        + getContaminantProtectionStatus()
                + ", usbDataStatus="
                        + UsbPort.usbDataStatusToString(getUsbDataStatus())
                + ", isPowerTransferLimited="
                        + isPowerTransferLimited()
                + ", powerBrickConnectionStatus="
                        + UsbPort
                            .powerBrickConnectionStatusToString(getPowerBrickConnectionStatus())
                + ", complianceWarnings="
                        + UsbPort.complianceWarningsToString(getComplianceWarnings())
                + ", plugState="
                        + getPlugState()
                + ", displayPortAltModeInfo="
                        + mDisplayPortAltModeInfo
                + ", bc12Type="
                        + UsbPort.bc12TypeToString(mPartnerBc12Type)
                + ", "
                        + getPowerProfileInfoString());
        return mString.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCurrentMode);
        dest.writeInt(mCurrentPowerRole);
        dest.writeInt(mCurrentDataRole);
        dest.writeInt(mSupportedRoleCombinations);
        dest.writeInt(mContaminantProtectionStatus);
        dest.writeInt(mContaminantDetectionStatus);
        dest.writeInt(mUsbDataStatus);
        dest.writeBoolean(mPowerTransferLimited);
        dest.writeInt(mPowerBrickConnectionStatus);
        dest.writeIntArray(mComplianceWarnings);
        dest.writeInt(mPlugState);
        if (mDisplayPortAltModeInfo == null) {
            dest.writeBoolean(false);
        } else {
            dest.writeBoolean(true);
            mDisplayPortAltModeInfo.writeToParcel(dest, 0);
        }
        dest.writeInt(mPartnerBc12Type);
        dest.writeParcelableList(mPortSinkPowerProfiles, 0);
        dest.writeParcelableList(mPortSourcePowerProfiles, 0);
        dest.writeParcelableList(mPartnerSinkPowerProfiles, 0);
        dest.writeParcelableList(mPartnerSourcePowerProfiles, 0);
        dest.writeParcelableList(mPortSinkPowerProfileMatches, 0);
        dest.writeParcelableList(mPortSourcePowerProfileMatches, 0);
    }

    public static final @NonNull Parcelable.Creator<UsbPortStatus> CREATOR =
            new Parcelable.Creator<UsbPortStatus>() {
        @Override
        public UsbPortStatus createFromParcel(Parcel in) {
            UsbPortStatus.Builder builder = new UsbPortStatus.Builder();
            int currentMode = in.readInt();
            int currentPowerRole = in.readInt();
            int currentDataRole = in.readInt();
            int supportedRoleCombinations = in.readInt();
            int contaminantProtectionStatus = in.readInt();
            int contaminantDetectionStatus = in.readInt();
            int usbDataStatus = in.readInt();
            boolean powerTransferLimited = in.readBoolean();
            int powerBrickConnectionStatus = in.readInt();
            @ComplianceWarning int[] complianceWarnings = in.createIntArray();
            int plugState = in.readInt();
            boolean supportsDisplayPortAltMode = in.readBoolean();
            DisplayPortAltModeInfo displayPortAltModeInfo;
            if (supportsDisplayPortAltMode) {
                displayPortAltModeInfo = DisplayPortAltModeInfo.CREATOR.createFromParcel(in);
            } else {
                displayPortAltModeInfo = null;
            }
            int bc12Type = in.readInt();
            List<PowerProfileInfo> portSinkPowerProfiles = new ArrayList<>();
            List<PowerProfileInfo> portSourcePowerProfiles = new ArrayList<>();
            List<PowerProfileInfo> partnerSinkPowerProfiles = new ArrayList<>();
            List<PowerProfileInfo> partnerSourcePowerProfiles = new ArrayList<>();
            List<PowerProfileMatchInfo> portSinkMatches = new ArrayList<>();
            List<PowerProfileMatchInfo> portSourceMatches = new ArrayList<>();
            in.readParcelableList(portSinkPowerProfiles, ClassLoader.getSystemClassLoader(),
                    PowerProfileInfo.class);
            in.readParcelableList(portSourcePowerProfiles, ClassLoader.getSystemClassLoader(),
                    PowerProfileInfo.class);
            in.readParcelableList(partnerSinkPowerProfiles, ClassLoader.getSystemClassLoader(),
                    PowerProfileInfo.class);
            in.readParcelableList(partnerSourcePowerProfiles, ClassLoader.getSystemClassLoader(),
                    PowerProfileInfo.class);
            in.readParcelableList(portSinkMatches, ClassLoader.getSystemClassLoader(),
                    PowerProfileMatchInfo.class);
            in.readParcelableList(portSourceMatches, ClassLoader.getSystemClassLoader(),
                    PowerProfileMatchInfo.class);

            builder.setCurrentMode(currentMode);
            builder.setCurrentRoles(currentPowerRole, currentDataRole);
            builder.setSupportedRoleCombinations(supportedRoleCombinations);
            builder.setContaminantStatus(contaminantProtectionStatus,
                    contaminantDetectionStatus);
            builder.setPowerTransferLimited(powerTransferLimited);
            builder.setUsbDataStatus(usbDataStatus);
            builder.setPowerBrickConnectionStatus(powerBrickConnectionStatus);
            builder.setComplianceWarnings(complianceWarnings);
            builder.setPlugState(plugState);
            builder.setDisplayPortAltModeInfo(displayPortAltModeInfo);
            builder.setPartnerBc12Type(bc12Type);
            builder.setPortPowerProfiles(portSinkPowerProfiles, portSourcePowerProfiles);
            builder.setPartnerPowerProfiles(partnerSinkPowerProfiles, partnerSourcePowerProfiles);
            builder.setPowerProfileMatchInfo(portSinkMatches, portSourceMatches);

            return new UsbPortStatus(builder);
        }

        @Override
        public UsbPortStatus[] newArray(int size) {
            return new UsbPortStatus[size];
        }
    };

    /**
     * Builder is used to create {@link UsbPortStatus} objects.
     *
     * @hide
     */
    public static final class Builder {
        private @UsbPortMode int mCurrentMode;
        private @UsbPowerRole int mCurrentPowerRole;
        private @UsbDataRole int mCurrentDataRole;
        private int mSupportedRoleCombinations;
        private @ContaminantProtectionStatus int mContaminantProtectionStatus;
        private @ContaminantDetectionStatus int mContaminantDetectionStatus;
        private boolean mPowerTransferLimited;
        private @UsbDataStatus int mUsbDataStatus;
        private @PowerBrickConnectionStatus int mPowerBrickConnectionStatus;
        private @ComplianceWarning int[] mComplianceWarnings;
        private @PlugState int mPlugState;
        private @Nullable DisplayPortAltModeInfo mDisplayPortAltModeInfo;
        private @Bc12Type int mPartnerBc12Type;
        private List<PowerProfileInfo> mPortSinkPowerProfiles;
        private List<PowerProfileInfo> mPortSourcePowerProfiles;
        private List<PowerProfileInfo> mPartnerSinkPowerProfiles;
        private List<PowerProfileInfo> mPartnerSourcePowerProfiles;
        private List<PowerProfileMatchInfo> mPortSinkPowerProfileMatches;
        private List<PowerProfileMatchInfo> mPortSourcePowerProfileMatches;

        public Builder() {
            mCurrentMode = MODE_NONE;
            mCurrentPowerRole = POWER_ROLE_NONE;
            mCurrentDataRole = DATA_ROLE_NONE;
            mContaminantProtectionStatus = CONTAMINANT_PROTECTION_NONE;
            mContaminantDetectionStatus = CONTAMINANT_DETECTION_NOT_SUPPORTED;
            mUsbDataStatus = DATA_STATUS_UNKNOWN;
            mPowerBrickConnectionStatus = POWER_BRICK_STATUS_UNKNOWN;
            mComplianceWarnings = new int[] {};
            mPlugState = PLUG_STATE_UNKNOWN;
            mDisplayPortAltModeInfo = null;
            mPartnerBc12Type = BC12_TYPE_UNKNOWN;
            mPortSinkPowerProfiles = Collections.emptyList();
            mPortSourcePowerProfiles = Collections.emptyList();
            mPartnerSinkPowerProfiles = Collections.emptyList();
            mPartnerSourcePowerProfiles = Collections.emptyList();
            mPortSinkPowerProfileMatches = Collections.emptyList();
            mPortSourcePowerProfileMatches = Collections.emptyList();
        }

        /**
         * Sets the current mode of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setCurrentMode(@UsbPortMode int currentMode) {
            mCurrentMode = currentMode;
            return this;
        }

        /**
         * Sets the current power role and data role of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setCurrentRoles(@UsbPowerRole int currentPowerRole,
                @UsbDataRole int currentDataRole) {
            mCurrentPowerRole = currentPowerRole;
            mCurrentDataRole = currentDataRole;
            return this;
        }

        /**
         * Sets supported role combinations of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setSupportedRoleCombinations(int supportedRoleCombinations) {
            mSupportedRoleCombinations = supportedRoleCombinations;
            return this;
        }

        /**
         * Sets current contaminant status of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setContaminantStatus(
                @ContaminantProtectionStatus int contaminantProtectionStatus,
                @ContaminantDetectionStatus int contaminantDetectionStatus) {
            mContaminantProtectionStatus = contaminantProtectionStatus;
            mContaminantDetectionStatus = contaminantDetectionStatus;
            return this;
        }

        /**
         * Sets power limit power transfer of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setPowerTransferLimited(boolean powerTransferLimited) {
            mPowerTransferLimited = powerTransferLimited;
            return this;
        }

        /**
         * Sets the USB data status of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setUsbDataStatus(@UsbDataStatus int usbDataStatus) {
            mUsbDataStatus = usbDataStatus;
            return this;
        }

        /**
         * Sets the power brick connection status of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setPowerBrickConnectionStatus(
                @PowerBrickConnectionStatus int powerBrickConnectionStatus) {
            mPowerBrickConnectionStatus = powerBrickConnectionStatus;
            return this;
        }

        /**
         * Sets the non-compliant charger reasons of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setComplianceWarnings(
                @NonNull int[] complianceWarnings) {
            mComplianceWarnings = complianceWarnings == null ? new int[] {} :
                    complianceWarnings;
            return this;
        }

        /**
         * Sets the plug orientation of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setPlugState(int plugState) {
            mPlugState = plugState;
            return this;
        }

        /**
         * Sets the plug orientation of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setDisplayPortAltModeInfo(
                @Nullable DisplayPortAltModeInfo displayPortAltModeInfo) {
            mDisplayPortAltModeInfo = displayPortAltModeInfo;
            return this;
        }

        /**
         * Sets the BC 1.2 Type of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setPartnerBc12Type(@Bc12Type int bc12Type) {
            mPartnerBc12Type = bc12Type;
            return this;
        }

        /**
         * Sets the port power profiles of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setPortPowerProfiles(@NonNull List<PowerProfileInfo> sinkPowerProfiles,
                                            @NonNull List<PowerProfileInfo> sourcePowerProfiles) {
            mPortSinkPowerProfiles = sinkPowerProfiles;
            mPortSourcePowerProfiles = sourcePowerProfiles;
            return this;
        }

        /**
         * Sets the partner power profiles of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setPartnerPowerProfiles(@NonNull List<PowerProfileInfo> sinkPowerProfiles,
                @NonNull List<PowerProfileInfo> sourcePowerProfiles) {
            mPartnerSinkPowerProfiles = sinkPowerProfiles;
            mPartnerSourcePowerProfiles = sourcePowerProfiles;
            return this;
        }

        /**
         * Sets the power profile match info of {@link UsbPortStatus}
         *
         * @return Instance of {@link Builder}
         */
        public Builder setPowerProfileMatchInfo(
                @NonNull List<PowerProfileMatchInfo> portSinkMatches,
                @NonNull List<PowerProfileMatchInfo> portSourceMatches) {
            mPortSinkPowerProfileMatches = portSinkMatches;
            mPortSourcePowerProfileMatches = portSourceMatches;
            return this;
        }

        /**
         * Creates the {@link UsbPortStatus} object.
         */
        @NonNull
        public UsbPortStatus build() {
            return new UsbPortStatus(this);
        }
    };
}

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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.Immutable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
     * USB data is disabled due to docking event.
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

    /** @hide */
    @IntDef(prefix = { "DATA_STATUS_" }, flag = true, value = {
            DATA_STATUS_UNKNOWN,
            DATA_STATUS_ENABLED,
            DATA_STATUS_DISABLED_OVERHEAT,
            DATA_STATUS_DISABLED_CONTAMINANT,
            DATA_STATUS_DISABLED_DOCK,
            DATA_STATUS_DISABLED_FORCE,
            DATA_STATUS_DISABLED_DEBUG
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

    /** @hide */
    public UsbPortStatus(int currentMode, int currentPowerRole, int currentDataRole,
            int supportedRoleCombinations, int contaminantProtectionStatus,
            int contaminantDetectionStatus, @UsbDataStatus int usbDataStatus,
            boolean powerTransferLimited,
            @PowerBrickConnectionStatus int powerBrickConnectionStatus) {
        mCurrentMode = currentMode;
        mCurrentPowerRole = currentPowerRole;
        mCurrentDataRole = currentDataRole;
        mSupportedRoleCombinations = supportedRoleCombinations;
        mContaminantProtectionStatus = contaminantProtectionStatus;
        mContaminantDetectionStatus = contaminantDetectionStatus;
        mUsbDataStatus = usbDataStatus;
        mPowerTransferLimited = powerTransferLimited;
        mPowerBrickConnectionStatus = powerBrickConnectionStatus;
    }

    /** @hide */
    public UsbPortStatus(int currentMode, int currentPowerRole, int currentDataRole,
            int supportedRoleCombinations, int contaminantProtectionStatus,
            int contaminantDetectionStatus) {
        mCurrentMode = currentMode;
        mCurrentPowerRole = currentPowerRole;
        mCurrentDataRole = currentDataRole;
        mSupportedRoleCombinations = supportedRoleCombinations;
        mContaminantProtectionStatus = contaminantProtectionStatus;
        mContaminantDetectionStatus = contaminantDetectionStatus;
        mUsbDataStatus = DATA_STATUS_UNKNOWN;
        mPowerBrickConnectionStatus = POWER_BRICK_STATUS_UNKNOWN;
        mPowerTransferLimited = false;
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
     * {@link #MODE_AUDIO_ACCESSORY}, {@link #MODE_DEBUG_ACCESSORY}, or {@link {@link #MODE_NONE} if
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
     *         {@link #DATA_STATUS_DISABLED_DEBUG}
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

    @NonNull
    @Override
    public String toString() {
        return "UsbPortStatus{connected=" + isConnected()
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
                +", powerBrickConnectionStatus="
                        + UsbPort
                            .powerBrickConnectionStatusToString(getPowerBrickConnectionStatus())
                + "}";
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
    }

    public static final @NonNull Parcelable.Creator<UsbPortStatus> CREATOR =
            new Parcelable.Creator<UsbPortStatus>() {
        @Override
        public UsbPortStatus createFromParcel(Parcel in) {
            int currentMode = in.readInt();
            int currentPowerRole = in.readInt();
            int currentDataRole = in.readInt();
            int supportedRoleCombinations = in.readInt();
            int contaminantProtectionStatus = in.readInt();
            int contaminantDetectionStatus = in.readInt();
            int usbDataStatus = in.readInt();
            boolean powerTransferLimited = in.readBoolean();
            int powerBrickConnectionStatus = in.readInt();
            return new UsbPortStatus(currentMode, currentPowerRole, currentDataRole,
                    supportedRoleCombinations, contaminantProtectionStatus,
                    contaminantDetectionStatus, usbDataStatus, powerTransferLimited,
                    powerBrickConnectionStatus);
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

        public Builder() {
            mCurrentMode = MODE_NONE;
            mCurrentPowerRole = POWER_ROLE_NONE;
            mCurrentDataRole = DATA_ROLE_NONE;
            mContaminantProtectionStatus = CONTAMINANT_PROTECTION_NONE;
            mContaminantDetectionStatus = CONTAMINANT_DETECTION_NOT_SUPPORTED;
            mUsbDataStatus = DATA_STATUS_UNKNOWN;
            mPowerBrickConnectionStatus = POWER_BRICK_STATUS_UNKNOWN;
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
         * Creates the {@link UsbPortStatus} object.
         */
        @NonNull
        public UsbPortStatus build() {
            UsbPortStatus status = new UsbPortStatus(mCurrentMode, mCurrentPowerRole,
                    mCurrentDataRole, mSupportedRoleCombinations, mContaminantProtectionStatus,
                    mContaminantDetectionStatus, mUsbDataStatus, mPowerTransferLimited,
                    mPowerBrickConnectionStatus);
            return status;
        }
    };
}

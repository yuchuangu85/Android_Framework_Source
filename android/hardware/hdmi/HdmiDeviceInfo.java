/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.hdmi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.hdmi.HdmiControlManager.HdmiCecVersion;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class to encapsulate device information for HDMI devices including CEC and MHL. In terms of
 * CEC, this container includes basic information such as logical address, physical address and
 * device type, and additional information like vendor id and osd name. In terms of MHL device, this
 * container includes adopter id and device type. Otherwise, it keeps the information of other type
 * devices for which only port ID, physical address are meaningful.
 *
 * @hide
 */
@SystemApi
public class HdmiDeviceInfo implements Parcelable {

    /** TV device type. */
    public static final int DEVICE_TV = 0;

    /** Recording device type. */
    public static final int DEVICE_RECORDER = 1;

    /** Device type reserved for future usage. */
    public static final int DEVICE_RESERVED = 2;

    /** Tuner device type. */
    public static final int DEVICE_TUNER = 3;

    /** Playback device type. */
    public static final int DEVICE_PLAYBACK = 4;

    /** Audio system device type. */
    public static final int DEVICE_AUDIO_SYSTEM = 5;

    /** @hide Pure CEC switch device type. */
    public static final int DEVICE_PURE_CEC_SWITCH = 6;

    /** @hide Video processor device type. */
    public static final int DEVICE_VIDEO_PROCESSOR = 7;

    // Value indicating the device is not an active source.
    public static final int DEVICE_INACTIVE = -1;

    /**
     * Logical address used to indicate the source comes from internal device. The logical address
     * of TV(0) is used.
     */
    public static final int ADDR_INTERNAL = 0;

    /**
     * Physical address used to indicate the source comes from internal device. The physical address
     * of TV(0) is used.
     */
    public static final int PATH_INTERNAL = 0x0000;

    /** Invalid physical address (routing path) */
    public static final int PATH_INVALID = 0xFFFF;

    /** Invalid port ID */
    public static final int PORT_INVALID = -1;

    /** Invalid device ID */
    public static final int ID_INVALID = 0xFFFF;

    /** Device info used to indicate an inactivated device. */
    public static final HdmiDeviceInfo INACTIVE_DEVICE = new HdmiDeviceInfo();

    private static final int HDMI_DEVICE_TYPE_CEC = 0;
    private static final int HDMI_DEVICE_TYPE_MHL = 1;
    private static final int HDMI_DEVICE_TYPE_HARDWARE = 2;

    // Type used to indicate the device that has relinquished its active source status.
    private static final int HDMI_DEVICE_TYPE_INACTIVE = 100;

    // Offset used for id value. MHL devices, for instance, will be assigned the value from
    // ID_OFFSET_MHL.
    private static final int ID_OFFSET_CEC = 0x0;
    private static final int ID_OFFSET_MHL = 0x80;
    private static final int ID_OFFSET_HARDWARE = 0xC0;

    // Common parameters for all device.
    private final int mId;
    private final int mHdmiDeviceType;
    private final int mPhysicalAddress;
    private final int mPortId;

    // CEC only parameters.
    private final int mLogicalAddress;
    private final int mDeviceType;
    @HdmiCecVersion
    private final int mHdmiCecVersion;
    private final int mVendorId;
    private final String mDisplayName;
    private final int mDevicePowerStatus;

    // MHL only parameters.
    private final int mDeviceId;
    private final int mAdopterId;

    /**
     * A helper class to deserialize {@link HdmiDeviceInfo} for a parcel.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<HdmiDeviceInfo> CREATOR =
            new Parcelable.Creator<HdmiDeviceInfo>() {
                @Override
                public HdmiDeviceInfo createFromParcel(Parcel source) {
                    int hdmiDeviceType = source.readInt();
                    int physicalAddress = source.readInt();
                    int portId = source.readInt();

                    switch (hdmiDeviceType) {
                        case HDMI_DEVICE_TYPE_CEC:
                            int logicalAddress = source.readInt();
                            int deviceType = source.readInt();
                            int vendorId = source.readInt();
                            int powerStatus = source.readInt();
                            String displayName = source.readString();
                            int cecVersion = source.readInt();
                            return new HdmiDeviceInfo(logicalAddress, physicalAddress, portId,
                                    deviceType, vendorId, displayName, powerStatus, cecVersion);
                        case HDMI_DEVICE_TYPE_MHL:
                            int deviceId = source.readInt();
                            int adopterId = source.readInt();
                            return new HdmiDeviceInfo(physicalAddress, portId, adopterId, deviceId);
                        case HDMI_DEVICE_TYPE_HARDWARE:
                            return new HdmiDeviceInfo(physicalAddress, portId);
                        case HDMI_DEVICE_TYPE_INACTIVE:
                            return HdmiDeviceInfo.INACTIVE_DEVICE;
                        default:
                            return null;
                    }
                }

                @Override
                public HdmiDeviceInfo[] newArray(int size) {
                    return new HdmiDeviceInfo[size];
                }
            };

    /**
     * Constructor. Used to initialize the instance for CEC device.
     *
     * @param logicalAddress logical address of HDMI-CEC device
     * @param physicalAddress physical address of HDMI-CEC device
     * @param portId HDMI port ID (1 for HDMI1)
     * @param deviceType type of device
     * @param vendorId vendor id of device. Used for vendor specific command.
     * @param displayName name of device
     * @param powerStatus device power status
     * @hide
     */
    public HdmiDeviceInfo(int logicalAddress, int physicalAddress, int portId, int deviceType,
            int vendorId, String displayName, int powerStatus, int hdmiCecVersion) {
        mHdmiDeviceType = HDMI_DEVICE_TYPE_CEC;
        mPhysicalAddress = physicalAddress;
        mPortId = portId;

        mId = idForCecDevice(logicalAddress);
        mLogicalAddress = logicalAddress;
        mDeviceType = deviceType;
        mHdmiCecVersion = hdmiCecVersion;
        mVendorId = vendorId;
        mDevicePowerStatus = powerStatus;
        mDisplayName = displayName;

        mDeviceId = -1;
        mAdopterId = -1;
    }

    /**
     * Constructor. Used to initialize the instance for CEC device.
     *
     * @param logicalAddress logical address of HDMI-CEC device
     * @param physicalAddress physical address of HDMI-CEC device
     * @param portId HDMI port ID (1 for HDMI1)
     * @param deviceType type of device
     * @param vendorId vendor id of device. Used for vendor specific command.
     * @param displayName name of device
     * @param powerStatus device power status
     * @hide
     */
    public HdmiDeviceInfo(int logicalAddress, int physicalAddress, int portId, int deviceType,
            int vendorId, String displayName, int powerStatus) {
        this(logicalAddress, physicalAddress, portId, deviceType,
                vendorId, displayName, powerStatus, HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    /**
     * Constructor. Used to initialize the instance for CEC device.
     *
     * @param logicalAddress  logical address of HDMI-CEC device
     * @param physicalAddress physical address of HDMI-CEC device
     * @param portId          HDMI port ID (1 for HDMI1)
     * @param deviceType      type of device
     * @param vendorId        vendor id of device. Used for vendor specific command.
     * @param displayName     name of device
     * @hide
     */
    public HdmiDeviceInfo(int logicalAddress, int physicalAddress, int portId, int deviceType,
            int vendorId, String displayName) {
        this(logicalAddress, physicalAddress, portId, deviceType,
                vendorId, displayName, HdmiControlManager.POWER_STATUS_UNKNOWN,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    /**
     * Constructor. Used to initialize the instance for device representing hardware port.
     *
     * @param physicalAddress physical address of the port
     * @param portId HDMI port ID (1 for HDMI1)
     * @hide
     */
    public HdmiDeviceInfo(int physicalAddress, int portId) {
        mHdmiDeviceType = HDMI_DEVICE_TYPE_HARDWARE;
        mPhysicalAddress = physicalAddress;
        mPortId = portId;

        mId = idForHardware(portId);
        mLogicalAddress = -1;
        mDeviceType = DEVICE_RESERVED;
        mHdmiCecVersion = HdmiControlManager.HDMI_CEC_VERSION_1_4_B;
        mVendorId = 0;
        mDevicePowerStatus = HdmiControlManager.POWER_STATUS_UNKNOWN;
        mDisplayName = "HDMI" + portId;

        mDeviceId = -1;
        mAdopterId = -1;
    }

    /**
     * Constructor. Used to initialize the instance for MHL device.
     *
     * @param physicalAddress physical address of HDMI device
     * @param portId portId HDMI port ID (1 for HDMI1)
     * @param adopterId adopter id of MHL
     * @param deviceId device id of MHL
     * @hide
     */
    public HdmiDeviceInfo(int physicalAddress, int portId, int adopterId, int deviceId) {
        mHdmiDeviceType = HDMI_DEVICE_TYPE_MHL;
        mPhysicalAddress = physicalAddress;
        mPortId = portId;

        mId = idForMhlDevice(portId);
        mLogicalAddress = -1;
        mDeviceType = DEVICE_RESERVED;
        mHdmiCecVersion = HdmiControlManager.HDMI_CEC_VERSION_1_4_B;
        mVendorId = 0;
        mDevicePowerStatus = HdmiControlManager.POWER_STATUS_UNKNOWN;
        mDisplayName = "Mobile";

        mDeviceId = adopterId;
        mAdopterId = deviceId;
    }

    /**
     * Constructor. Used to initialize the instance representing an inactivated device.
     * Can be passed input change listener to indicate the active source yielded
     * its status, hence the listener should take an appropriate action such as
     * switching to other input.
     */
    public HdmiDeviceInfo() {
        mHdmiDeviceType = HDMI_DEVICE_TYPE_INACTIVE;
        mPhysicalAddress = PATH_INVALID;
        mId = ID_INVALID;

        mLogicalAddress = -1;
        mDeviceType = DEVICE_INACTIVE;
        mHdmiCecVersion = HdmiControlManager.HDMI_CEC_VERSION_1_4_B;
        mPortId = PORT_INVALID;
        mDevicePowerStatus = HdmiControlManager.POWER_STATUS_UNKNOWN;
        mDisplayName = "Inactive";
        mVendorId = 0;

        mDeviceId = -1;
        mAdopterId = -1;
    }

    /**
     * Returns the id of the device.
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the id to be used for CEC device.
     *
     * @param address logical address of CEC device
     * @return id for CEC device
     */
    public static int idForCecDevice(int address) {
        // The id is generated based on the logical address.
        return ID_OFFSET_CEC + address;
    }

    /**
     * Returns the id to be used for MHL device.
     *
     * @param portId port which the MHL device is connected to
     * @return id for MHL device
     */
    public static int idForMhlDevice(int portId) {
        // The id is generated based on the port id since there can be only one MHL device per port.
        return ID_OFFSET_MHL + portId;
    }

    /**
     * Returns the id to be used for hardware port.
     *
     * @param portId port id
     * @return id for hardware port
     */
    public static int idForHardware(int portId) {
        return ID_OFFSET_HARDWARE + portId;
    }

    /**
     * Returns the CEC logical address of the device.
     */
    public int getLogicalAddress() {
        return mLogicalAddress;
    }

    /**
     * Returns the physical address of the device.
     */
    public int getPhysicalAddress() {
        return mPhysicalAddress;
    }

    /**
     * Returns the port ID.
     */
    public int getPortId() {
        return mPortId;
    }

    /**
     * Returns CEC type of the device. For more details, refer constants between {@link #DEVICE_TV}
     * and {@link #DEVICE_INACTIVE}.
     */
    public int getDeviceType() {
        return mDeviceType;
    }

    /**
     * Returns the CEC version the device supports.
     *
     * @hide
     */
    @HdmiCecVersion
    public int getCecVersion() {
        return mHdmiCecVersion;
    }

    /**
     * Returns device's power status. It should be one of the following values.
     * <ul>
     * <li>{@link HdmiControlManager#POWER_STATUS_ON}
     * <li>{@link HdmiControlManager#POWER_STATUS_STANDBY}
     * <li>{@link HdmiControlManager#POWER_STATUS_TRANSIENT_TO_ON}
     * <li>{@link HdmiControlManager#POWER_STATUS_TRANSIENT_TO_STANDBY}
     * <li>{@link HdmiControlManager#POWER_STATUS_UNKNOWN}
     * </ul>
     */
    public int getDevicePowerStatus() {
        return mDevicePowerStatus;
    }

    /**
     * Returns MHL device id. Return -1 for non-MHL device.
     */
    public int getDeviceId() {
        return mDeviceId;
    }

    /**
     * Returns MHL adopter id. Return -1 for non-MHL device.
     */
    public int getAdopterId() {
        return mAdopterId;
    }

    /**
     * Returns {@code true} if the device is of a type that can be an input source.
     */
    public boolean isSourceType() {
        if (isCecDevice()) {
            return mDeviceType == DEVICE_PLAYBACK
                    || mDeviceType == DEVICE_RECORDER
                    || mDeviceType == DEVICE_TUNER;
        } else if (isMhlDevice()) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Returns {@code true} if the device represents an HDMI-CEC device. {@code false} if the device
     * is either MHL or other device.
     */
    public boolean isCecDevice() {
        return mHdmiDeviceType == HDMI_DEVICE_TYPE_CEC;
    }

    /**
     * Returns {@code true} if the device represents an MHL device. {@code false} if the device is
     * either CEC or other device.
     */
    public boolean isMhlDevice() {
        return mHdmiDeviceType == HDMI_DEVICE_TYPE_MHL;
    }

    /**
     * Return {@code true} if the device represents an inactivated device that relinquishes
     * its status as active source by &lt;Active Source&gt; (HDMI-CEC) or Content-off (MHL).
     */
    public boolean isInactivated() {
        return mHdmiDeviceType == HDMI_DEVICE_TYPE_INACTIVE;
    }

    /**
     * Returns display (OSD) name of the device.
     */
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Returns vendor id of the device. Vendor id is used to distinguish devices built by other
     * manufactures. This is required for vendor-specific command on CEC standard.
     */
    public int getVendorId() {
        return mVendorId;
    }

    /**
     * Describes the kinds of special objects contained in this Parcelable's marshalled
     * representation.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Serializes this object into a {@link Parcel}.
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written. May be 0 or
     *            {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mHdmiDeviceType);
        dest.writeInt(mPhysicalAddress);
        dest.writeInt(mPortId);
        switch (mHdmiDeviceType) {
            case HDMI_DEVICE_TYPE_CEC:
                dest.writeInt(mLogicalAddress);
                dest.writeInt(mDeviceType);
                dest.writeInt(mVendorId);
                dest.writeInt(mDevicePowerStatus);
                dest.writeString(mDisplayName);
                dest.writeInt(mHdmiCecVersion);
                break;
            case HDMI_DEVICE_TYPE_MHL:
                dest.writeInt(mDeviceId);
                dest.writeInt(mAdopterId);
                break;
            case HDMI_DEVICE_TYPE_INACTIVE:
                // flow through
            default:
                // no-op
        }
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        switch (mHdmiDeviceType) {
            case HDMI_DEVICE_TYPE_CEC:
                s.append("CEC: ");
                s.append("logical_address: ").append(String.format("0x%02X", mLogicalAddress));
                s.append(" ");
                s.append("device_type: ").append(mDeviceType).append(" ");
                s.append("cec_version: ").append(mHdmiCecVersion).append(" ");
                s.append("vendor_id: ").append(mVendorId).append(" ");
                s.append("display_name: ").append(mDisplayName).append(" ");
                s.append("power_status: ").append(mDevicePowerStatus).append(" ");
                break;
            case HDMI_DEVICE_TYPE_MHL:
                s.append("MHL: ");
                s.append("device_id: ").append(String.format("0x%04X", mDeviceId)).append(" ");
                s.append("adopter_id: ").append(String.format("0x%04X", mAdopterId)).append(" ");
                break;

            case HDMI_DEVICE_TYPE_HARDWARE:
                s.append("Hardware: ");
                break;
            case HDMI_DEVICE_TYPE_INACTIVE:
                s.append("Inactivated: ");
                break;
            default:
                return "";
        }
        s.append("physical_address: ").append(String.format("0x%04X", mPhysicalAddress));
        s.append(" ");
        s.append("port_id: ").append(mPortId);
        return s.toString();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof HdmiDeviceInfo)) {
            return false;
        }

        HdmiDeviceInfo other = (HdmiDeviceInfo) obj;
        return mHdmiDeviceType == other.mHdmiDeviceType
                && mPhysicalAddress == other.mPhysicalAddress
                && mPortId == other.mPortId
                && mLogicalAddress == other.mLogicalAddress
                && mDeviceType == other.mDeviceType
                && mHdmiCecVersion == other.mHdmiCecVersion
                && mVendorId == other.mVendorId
                && mDevicePowerStatus == other.mDevicePowerStatus
                && mDisplayName.equals(other.mDisplayName)
                && mDeviceId == other.mDeviceId
                && mAdopterId == other.mAdopterId;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                mHdmiDeviceType,
                mPhysicalAddress,
                mPortId,
                mLogicalAddress,
                mDeviceType,
                mHdmiCecVersion,
                mVendorId,
                mDevicePowerStatus,
                mDisplayName,
                mDeviceId,
                mAdopterId);
    }
}

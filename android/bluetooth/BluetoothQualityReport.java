/*
 * Copyright (C) 2019 The Linux Foundation
 * Copyright (C) 2023 The Android Open Source Project
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

package android.bluetooth;

import static java.util.Objects.requireNonNull;

import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.bluetooth.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * This class provides the System APIs to access the data of BQR event reported from firmware side.
 * Currently it supports five event types: Quality monitor event, Approaching LSTO event, A2DP
 * choppy event, SCO choppy event and Connect fail event. To know which kind of event is wrapped in
 * this {@link BluetoothQualityReport} object, you need to call {@link #getQualityReportId}.
 *
 * <ul>
 *   <li>For Quality monitor event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object.
 *   <li>For Approaching LSTO event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrEvent} to get a {@link
 *       BluetoothQualityReport.BqrVsLsto} object.
 *   <li>For A2DP choppy event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrEvent} to get a {@link
 *       BluetoothQualityReport.BqrVsA2dpChoppy} object.
 *   <li>For SCO choppy event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrEvent} to get a {@link
 *       BluetoothQualityReport.BqrVsScoChoppy} object.
 *   <li>For Connect fail event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrEvent} to get a {@link
 *       BluetoothQualityReport.BqrConnectFail} object.
 *   <li>For Energy monitor event, you can call {@link #getBqrEvent} to get a {@link
 *       BluetoothQualityReport.BqrEnergyMonitor} object.
 *   <li>For RF stats event, you can call {@link #getBqrEvent} to get a {@link
 *       BluetoothQualityReport.BqrRfStats} object.
 *   <li>For LE Audio choppy event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object.
 * </ul>
 */
@Hide
@SystemApi
public final class BluetoothQualityReport implements Parcelable {
    private static final String TAG = BluetoothQualityReport.class.getSimpleName();

    /** Quality report ID: Monitor. */
    @Hide @SystemApi public static final int QUALITY_REPORT_ID_MONITOR = 0x01;

    /** Quality report ID: Approaching LSTO. */
    @Hide @SystemApi public static final int QUALITY_REPORT_ID_APPROACH_LSTO = 0x02;

    /** Quality report ID: A2DP choppy. */
    @Hide @SystemApi public static final int QUALITY_REPORT_ID_A2DP_CHOPPY = 0x03;

    /** Quality report ID: SCO choppy. */
    @Hide @SystemApi public static final int QUALITY_REPORT_ID_SCO_CHOPPY = 0x04;

    // Report ID 0x05 is reserved for Root inflammation event,
    // which indicates a fatal error in the Bluetooth HAL or controller.
    // This event requires a Bluetooth stack restart and is not passed
    // to the framework. It is not explicitly defined as a System API
    // because it is handled internally within the Bluetooth stack.

    /** Quality report ID: Energy Monitor. */
    @Hide @SystemApi public static final int QUALITY_REPORT_ID_ENERGY_MONITOR = 0x06;

    /** Quality report ID: LE Audio Choppy. */
    @FlaggedApi(Flags.FLAG_BLUETOOTH_QUALITY_REPORT_V8)
    @Hide
    @SystemApi
    public static final int QUALITY_REPORT_ID_LEA_CHOPPY = 0x07;

    /** Quality report ID: Connect Fail. */
    @Hide @SystemApi public static final int QUALITY_REPORT_ID_CONN_FAIL = 0x08;

    /** Quality report ID: RF Stats. */
    @Hide @SystemApi public static final int QUALITY_REPORT_ID_RF_STATS = 0x09;

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"QUALITY_REPORT_ID"},
            value = {
                QUALITY_REPORT_ID_MONITOR,
                QUALITY_REPORT_ID_APPROACH_LSTO,
                QUALITY_REPORT_ID_A2DP_CHOPPY,
                QUALITY_REPORT_ID_SCO_CHOPPY,
                QUALITY_REPORT_ID_ENERGY_MONITOR,
                QUALITY_REPORT_ID_LEA_CHOPPY,
                QUALITY_REPORT_ID_CONN_FAIL,
                QUALITY_REPORT_ID_RF_STATS,
            })
    public @interface QualityReportId {}

    private final String mAddr;
    private final int mLmpVer;
    private final int mLmpSubVer;
    private final int mManufacturerId;
    private final String mName;
    private final BluetoothClass mBluetoothClass;

    private final BqrCommon mBqrCommon;
    private BqrVsLsto mBqrVsLsto;
    private BqrVsA2dpChoppy mBqrVsA2dpChoppy;
    private BqrVsScoChoppy mBqrVsScoChoppy;
    private BqrConnectFail mBqrConnectFail;
    private BqrEnergyMonitor mBqrEnergyMonitor;
    private BqrRfStats mBqrRfStats;

    enum PacketType {
        INVALID,
        TYPE_ID,
        TYPE_NULL,
        TYPE_POLL,
        TYPE_FHS,
        TYPE_HV1,
        TYPE_HV2,
        TYPE_HV3,
        TYPE_DV,
        TYPE_EV3,
        TYPE_EV4,
        TYPE_EV5,
        TYPE_2EV3,
        TYPE_2EV5,
        TYPE_3EV3,
        TYPE_3EV5,
        TYPE_DM1,
        TYPE_DH1,
        TYPE_DM3,
        TYPE_DH3,
        TYPE_DM5,
        TYPE_DH5,
        TYPE_AUX1,
        TYPE_2DH1,
        TYPE_2DH3,
        TYPE_2DH5,
        TYPE_3DH1,
        TYPE_3DH3,
        TYPE_3DH5;

        private static final PacketType[] sAllValues = values();

        static PacketType fromOrdinal(int n) {
            if (n < sAllValues.length) {
                return sAllValues[n];
            }
            return INVALID;
        }
    }

    enum ConnState {
        CONN_IDLE(0x00),
        CONN_ACTIVE(0x81),
        CONN_HOLD(0x02),
        CONN_SNIFF_IDLE(0x03),
        CONN_SNIFF_ACTIVE(0x84),
        CONN_SNIFF_MASTER_TRANSITION(0x85),
        CONN_PARK(0x06),
        CONN_PARK_PEND(0x47),
        CONN_UNPARK_PEND(0x08),
        CONN_UNPARK_ACTIVE(0x89),
        CONN_DISCONNECT_PENDING(0x4A),
        CONN_PAGING(0x0B),
        CONN_PAGE_SCAN(0x0C),
        CONN_LOCAL_LOOPBACK(0x0D),
        CONN_LE_ACTIVE(0x0E),
        CONN_ANT_ACTIVE(0x0F),
        CONN_TRIGGER_SCAN(0x10),
        CONN_RECONNECTING(0x11),
        CONN_SEMI_CONN(0x12);

        private final int mValue;
        private static final ConnState[] sAllStates = values();

        ConnState(int val) {
            mValue = val;
        }

        public static String toString(int val) {
            for (ConnState state : sAllStates) {
                if (state.mValue == val) {
                    return state.toString();
                }
            }
            return "INVALID";
        }
    }

    enum LinkQuality {
        ULTRA_HIGH,
        HIGH,
        STANDARD,
        MEDIUM,
        LOW,
        INVALID;

        private static final LinkQuality[] sAllValues = values();

        static LinkQuality fromOrdinal(int n) {
            if (n < sAllValues.length - 1) {
                return sAllValues[n];
            }
            return INVALID;
        }
    }

    enum AirMode {
        uLaw,
        aLaw,
        CVSD,
        transparent_msbc,
        INVALID;

        private static final AirMode[] sAllValues = values();

        static AirMode fromOrdinal(int n) {
            if (n < sAllValues.length - 1) {
                return sAllValues[n];
            }
            return INVALID;
        }
    }

    /**
     * Constructs a {@link BluetoothQualityReport} from raw byte data.
     *
     * <p>This constructor is intended for testing and internal use. It should not be used directly
     * in application code.
     */
    private BluetoothQualityReport(
            String remoteAddr,
            int lmpVer,
            int lmpSubVer,
            int manufacturerId,
            String remoteName,
            BluetoothClass bluetoothClass,
            byte[] rawData) {
        mAddr = remoteAddr;
        mLmpVer = lmpVer;
        mLmpSubVer = lmpSubVer;
        mManufacturerId = manufacturerId;
        mName = remoteName;
        mBluetoothClass = bluetoothClass;

        mBqrCommon = new BqrCommon(rawData, 0);
        int id = mBqrCommon.getQualityReportId();
        if (id == QUALITY_REPORT_ID_MONITOR) return;

        int vsPartOffset = BqrCommon.BQR_COMMON_LEN;
        if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto = new BqrVsLsto(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy = new BqrVsA2dpChoppy(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy = new BqrVsScoChoppy(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrConnectFail = new BqrConnectFail(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_ENERGY_MONITOR) {
            mBqrEnergyMonitor = new BqrEnergyMonitor(rawData, 1);
        } else if (id == QUALITY_REPORT_ID_RF_STATS) {
            mBqrRfStats = new BqrRfStats(rawData, 1);
        } else {
            if (Flags.bluetoothQualityReportV8()) {
                if (id == QUALITY_REPORT_ID_LEA_CHOPPY) {
                    return;
                }
            }
            throw new IllegalArgumentException(TAG + ": unknown quality report id:" + id);
        }
    }

    private BluetoothQualityReport(Parcel in) {
        mAddr = in.readString();
        mLmpVer = in.readInt();
        mLmpSubVer = in.readInt();
        mManufacturerId = in.readInt();
        mName = in.readString();
        mBluetoothClass = new BluetoothClass(in.readInt());

        mBqrCommon = new BqrCommon(in);
        int id = mBqrCommon.getQualityReportId();
        if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto = new BqrVsLsto(in);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy = new BqrVsA2dpChoppy(in);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy = new BqrVsScoChoppy(in);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrConnectFail = new BqrConnectFail(in);
        } else if (id == QUALITY_REPORT_ID_ENERGY_MONITOR) {
            mBqrEnergyMonitor = new BqrEnergyMonitor(in);
        } else if (id == QUALITY_REPORT_ID_RF_STATS) {
            mBqrRfStats = new BqrRfStats(in);
        }
    }

    /** Get the quality report id. */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @QualityReportId int getQualityReportId() {
        return mBqrCommon.getQualityReportId();
    }

    /**
     * Get the string of the quality report id.
     *
     * @return the string of the id
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public static @NonNull String qualityReportIdToString(@QualityReportId int id) {
        return BqrCommon.qualityReportIdToString(id);
    }

    /**
     * Get bluetooth address of remote device in this report.
     *
     * @return bluetooth address of remote device
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @Nullable String getRemoteAddress() {
        return mAddr;
    }

    /**
     * Get LMP version of remote device in this report.
     *
     * @return LMP version of remote device
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public int getLmpVersion() {
        return mLmpVer;
    }

    /**
     * Get LMP subVersion of remote device in this report.
     *
     * @return LMP subVersion of remote device
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public int getLmpSubVersion() {
        return mLmpSubVer;
    }

    /**
     * Get manufacturer id of remote device in this report.
     *
     * @return manufacturer id of remote device
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public int getManufacturerId() {
        return mManufacturerId;
    }

    /**
     * Get the name of remote device in this report.
     *
     * @return the name of remote device
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @Nullable String getRemoteName() {
        return mName;
    }

    /**
     * Get the class of remote device in this report.
     *
     * @return the class of remote device
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @NonNull BluetoothClass getBluetoothClass() {
        return mBluetoothClass;
    }

    /**
     * Get the {@link BluetoothQualityReport.BqrCommon} object.
     *
     * @return the {@link BluetoothQualityReport.BqrCommon} object.
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @Nullable BqrCommon getBqrCommon() {
        return mBqrCommon;
    }

    /**
     * Get the event data object based on current Quality Report Id. If the report id is {@link
     * #QUALITY_REPORT_ID_MONITOR}, this returns a {@link BluetoothQualityReport.BqrCommon} object.
     * If the report id is {@link #QUALITY_REPORT_ID_APPROACH_LSTO}, this returns a {@link
     * BluetoothQualityReport.BqrVsLsto} object. If the report id is {@link
     * #QUALITY_REPORT_ID_A2DP_CHOPPY}, this returns a {@link
     * BluetoothQualityReport.BqrVsA2dpChoppy} object. If the report id is {@link
     * #QUALITY_REPORT_ID_SCO_CHOPPY}, this returns a {@link BluetoothQualityReport.BqrVsScoChoppy}
     * object. If the report id is {@link #QUALITY_REPORT_ID_CONN_FAIL}, this returns a {@link
     * BluetoothQualityReport.BqrConnectFail} object. If the report id is none of the above, this
     * returns {@code null}.
     *
     * @return the event data object based on the quality report id
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @Nullable Parcelable getBqrEvent() {
        if (mBqrCommon == null) {
            return null;
        }
        return switch (mBqrCommon.getQualityReportId()) {
            case QUALITY_REPORT_ID_MONITOR -> mBqrCommon;
            case QUALITY_REPORT_ID_APPROACH_LSTO -> mBqrVsLsto;
            case QUALITY_REPORT_ID_A2DP_CHOPPY -> mBqrVsA2dpChoppy;
            case QUALITY_REPORT_ID_SCO_CHOPPY -> mBqrVsScoChoppy;
            case QUALITY_REPORT_ID_CONN_FAIL -> mBqrConnectFail;
            case QUALITY_REPORT_ID_ENERGY_MONITOR -> mBqrEnergyMonitor;
            case QUALITY_REPORT_ID_RF_STATS -> mBqrRfStats;
            default -> null;
        };
    }

    @Hide @SystemApi
    public static final @NonNull Parcelable.Creator<BluetoothQualityReport> CREATOR =
            new Parcelable.Creator<BluetoothQualityReport>() {
                public BluetoothQualityReport createFromParcel(Parcel in) {
                    return new BluetoothQualityReport(in);
                }

                public BluetoothQualityReport[] newArray(int size) {
                    return new BluetoothQualityReport[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /** Write BluetoothQualityReport to parcel. */
    @Hide
    @SystemApi
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        BluetoothUtils.writeStringToParcel(out, mAddr);
        out.writeInt(mLmpVer);
        out.writeInt(mLmpSubVer);
        out.writeInt(mManufacturerId);
        BluetoothUtils.writeStringToParcel(out, mName);
        out.writeInt(mBluetoothClass.getClassOfDevice());
        mBqrCommon.writeToParcel(out, flags);
        int id = mBqrCommon.getQualityReportId();
        if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrConnectFail.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_ENERGY_MONITOR) {
            mBqrEnergyMonitor.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_RF_STATS) {
            mBqrRfStats.writeToParcel(out, flags);
        }
    }

    /** BluetoothQualityReport to String. */
    @Override
    public String toString() {
        String str;
        str =
                "BQR: {\n"
                        + ("  mAddr: " + mAddr)
                        + (", mLmpVer: " + String.format("0x%02X", mLmpVer))
                        + (", mLmpSubVer: " + String.format("0x%04X", mLmpSubVer))
                        + (", mManufacturerId: " + String.format("0x%04X", mManufacturerId))
                        + (", mName: " + mName)
                        + (", mBluetoothClass: " + mBluetoothClass.toString())
                        + ",\n"
                        + mBqrCommon
                        + "\n";

        int id = mBqrCommon.getQualityReportId();
        if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            str = str + mBqrVsLsto + "\n}";
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            str = str + mBqrVsA2dpChoppy + "\n}";
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            str = str + mBqrVsScoChoppy + "\n}";
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            str = str + mBqrConnectFail + "\n}";
        } else if (id == QUALITY_REPORT_ID_MONITOR) {
            str = str + "}";
        } else if (id == QUALITY_REPORT_ID_ENERGY_MONITOR) {
            str = str + mBqrEnergyMonitor + "\n}";
        } else if (id == QUALITY_REPORT_ID_RF_STATS) {
            str = str + mBqrRfStats + "\n}";
        }

        return str;
    }

    /** Builder for new instances of {@link BluetoothQualityReport}. */
    @Hide
    @SystemApi
    public static final class Builder {
        private String remoteAddr = "00:00:00:00:00:00";
        private int lmpVer;
        private int lmpSubVer;
        private int manufacturerId;
        private String remoteName = "";
        private BluetoothClass bluetoothClass = new BluetoothClass(0);
        private final byte[] rawData;

        /**
         * Creates a new instance of {@link Builder}.
         *
         * @return The new instance
         * @throws NullPointerException if rawData is null
         */
        @Hide
        @SystemApi
        public Builder(@NonNull byte[] rawData) {
            this.rawData = requireNonNull(rawData);
        }

        /**
         * Sets the Remote Device Address (big-endian) attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param remoteAddr the Remote Device Address (big-endian) attribute
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setRemoteAddress(@Nullable String remoteAddr) {
            if (!BluetoothAdapter.checkBluetoothAddress(remoteAddr)) {
                Log.d(TAG, "remote address is not a valid bluetooth address: " + remoteAddr);
            } else {
                this.remoteAddr = remoteAddr;
            }
            return this;
        }

        /**
         * Sets the Link Manager Protocol Version attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param lmpVer the Link Manager Protocol Version attribute
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setLmpVersion(int lmpVer) {
            this.lmpVer = lmpVer;
            return this;
        }

        /**
         * Sets the Link Manager Protocol SubVersion attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param lmpSubVer the Link Manager Protocol SubVersion attribute
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setLmpSubVersion(int lmpSubVer) {
            this.lmpSubVer = lmpSubVer;
            return this;
        }

        /**
         * Sets the Manufacturer Id attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param manufacturerId the Manufacturer Id attribute
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setManufacturerId(int manufacturerId) {
            this.manufacturerId = manufacturerId;
            return this;
        }

        /**
         * Sets the Remote Device Name attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param remoteName the Remote Device Name attribute
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setRemoteName(@Nullable String remoteName) {
            if (remoteName == null) {
                Log.d(TAG, "remote name is null");
            } else {
                this.remoteName = remoteName;
            }
            return this;
        }

        /**
         * Sets the Bluetooth Class of Remote Device attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param bluetoothClass the Remote Class of Device attribute
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setBluetoothClass(@Nullable BluetoothClass bluetoothClass) {
            if (bluetoothClass == null) {
                Log.d(TAG, "remote bluetooth class is null");
            } else {
                this.bluetoothClass = bluetoothClass;
            }
            return this;
        }

        /**
         * Creates a new instance of {@link BluetoothQualityReport}.
         *
         * @return The new instance
         * @throws IllegalArgumentException Unsupported Quality Report Id or invalid raw data
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull BluetoothQualityReport build() {
            return new BluetoothQualityReport(
                    remoteAddr,
                    lmpVer,
                    lmpSubVer,
                    manufacturerId,
                    remoteName,
                    bluetoothClass,
                    rawData);
        }
    }

    /** This class provides the System APIs to access the common part of BQR event. */
    @Hide
    @SystemApi
    public static final class BqrCommon implements Parcelable {
        private static final String TAG =
                BluetoothQualityReport.TAG + "." + BqrCommon.class.getSimpleName();

        static final int BQR_COMMON_LEN = 85;

        private final @QualityReportId int mQualityReportId;
        private int mPacketType;
        private int mConnectionHandle;
        private int mConnectionRole;
        private int mTxPowerLevel;
        private int mRssi;
        private int mSnr;
        private int mUnusedAfhChannelCount;
        private int mAfhSelectUnidealChannelCount;
        private int mLsto;
        private long mPiconetClock;
        private long mRetransmissionCount;
        private long mNoRxCount;
        private long mNakCount;
        private long mLastTxAckTimestamp;
        private long mFlowOffCount;
        private long mLastFlowOnTimestamp;
        private long mOverflowCount;
        private long mUnderflowCount;
        private String mAddr;
        private int mCalFailedItemCount;
        private long mTxTotalPackets;
        private long mTxUnackPackets;
        private long mTxFlushPackets;
        private long mTxLastSubeventPackets;
        private long mCrcErrorPackets;
        private long mRxDupPackets;
        private long mRxUnRecvPackets;
        private int mCoexInfoMask;

        private BqrCommon(byte[] rawData, int offset) {
            mQualityReportId = rawData[0] & 0xFF;
            if ((mQualityReportId == QUALITY_REPORT_ID_ENERGY_MONITOR)
                    || (mQualityReportId == QUALITY_REPORT_ID_RF_STATS)) {
                return;
            }

            if (rawData == null || rawData.length < offset + BQR_COMMON_LEN) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            bqrBuf.get();
            mPacketType = bqrBuf.get() & 0xFF;
            mConnectionHandle = bqrBuf.getShort() & 0xFFFF;
            mConnectionRole = bqrBuf.get() & 0xFF;
            mTxPowerLevel = bqrBuf.get() & 0xFF;
            mRssi = bqrBuf.get();
            mSnr = bqrBuf.get();
            mUnusedAfhChannelCount = bqrBuf.get() & 0xFF;
            mAfhSelectUnidealChannelCount = bqrBuf.get() & 0xFF;
            mLsto = bqrBuf.getShort() & 0xFFFF;
            mPiconetClock = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRetransmissionCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mNoRxCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mNakCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastTxAckTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
            mFlowOffCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastFlowOnTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
            mOverflowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mUnderflowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            int currentOffset = bqrBuf.position();
            mAddr =
                    String.format(
                            "%02X:%02X:%02X:%02X:%02X:%02X",
                            bqrBuf.get(currentOffset + 5),
                            bqrBuf.get(currentOffset + 4),
                            bqrBuf.get(currentOffset + 3),
                            bqrBuf.get(currentOffset + 2),
                            bqrBuf.get(currentOffset + 1),
                            bqrBuf.get(currentOffset + 0));
            bqrBuf.position(currentOffset + 6);
            mCalFailedItemCount = bqrBuf.get() & 0xFF;
            mTxTotalPackets = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxUnackPackets = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxFlushPackets = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxLastSubeventPackets = bqrBuf.getInt() & 0xFFFFFFFFL;
            mCrcErrorPackets = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRxDupPackets = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRxUnRecvPackets = bqrBuf.getInt() & 0xFFFFFFFFL;
            mCoexInfoMask = bqrBuf.getShort() & 0xFFFF;
        }

        private BqrCommon(Parcel in) {
            mQualityReportId = in.readInt();
            mPacketType = in.readInt();
            mConnectionHandle = in.readInt();
            mConnectionRole = in.readInt();
            mTxPowerLevel = in.readInt();
            mRssi = in.readInt();
            mSnr = in.readInt();
            mUnusedAfhChannelCount = in.readInt();
            mAfhSelectUnidealChannelCount = in.readInt();
            mLsto = in.readInt();
            mPiconetClock = in.readLong();
            mRetransmissionCount = in.readLong();
            mNoRxCount = in.readLong();
            mNakCount = in.readLong();
            mLastTxAckTimestamp = in.readLong();
            mFlowOffCount = in.readLong();
            mLastFlowOnTimestamp = in.readLong();
            mOverflowCount = in.readLong();
            mUnderflowCount = in.readLong();
            mAddr = in.readString();
            mCalFailedItemCount = in.readInt();
            mTxTotalPackets = in.readLong();
            mTxUnackPackets = in.readLong();
            mTxFlushPackets = in.readLong();
            mTxLastSubeventPackets = in.readLong();
            mCrcErrorPackets = in.readLong();
            mRxDupPackets = in.readLong();
            mRxUnRecvPackets = in.readLong();
            mCoexInfoMask = in.readInt();
        }

        @QualityReportId
        int getQualityReportId() {
            return mQualityReportId;
        }

        static String qualityReportIdToString(@QualityReportId int id) {
            if (Flags.bluetoothQualityReportV8()) {
                if (QUALITY_REPORT_ID_LEA_CHOPPY == id) {
                    return "LEA choppy";
                }
            }

            return switch (id) {
                case QUALITY_REPORT_ID_MONITOR -> "Quality monitor";
                case QUALITY_REPORT_ID_APPROACH_LSTO -> "Approaching LSTO";
                case QUALITY_REPORT_ID_A2DP_CHOPPY -> "A2DP choppy";
                case QUALITY_REPORT_ID_SCO_CHOPPY -> "SCO choppy";
                case QUALITY_REPORT_ID_CONN_FAIL -> "Connect fail";
                case QUALITY_REPORT_ID_ENERGY_MONITOR -> "Energy Monitor";
                case QUALITY_REPORT_ID_RF_STATS -> "RF Stats";
                default -> "INVALID";
            };
        }

        /**
         * Get the packet type of the connection.
         *
         * @return the packet type
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getPacketType() {
            return mPacketType;
        }

        /**
         * Get the string of packet type.
         *
         * @param packetType packet type of the connection
         * @return the string of packet type
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public static @Nullable String packetTypeToString(int packetType) {
            PacketType type = PacketType.fromOrdinal(packetType);
            return type.toString();
        }

        /**
         * Get the connection handle of the connection.
         *
         * @return the connection handle
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getConnectionHandle() {
            return mConnectionHandle;
        }

        /** Connection role: central. */
        @Hide @SystemApi public static final int CONNECTION_ROLE_CENTRAL = 0;

        /** Connection role: peripheral. */
        @Hide @SystemApi public static final int CONNECTION_ROLE_PERIPHERAL = 1;

        @Hide
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"CONNECTION_ROLE"},
                value = {
                    CONNECTION_ROLE_CENTRAL,
                    CONNECTION_ROLE_PERIPHERAL,
                })
        public @interface ConnectionRole {}

        /**
         * Get the connection Role of the connection.
         *
         * @return the connection Role
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @ConnectionRole int getConnectionRole() {
            return mConnectionRole;
        }

        /**
         * Get the connection Role of the connection, "Central" or "Peripheral".
         *
         * @param connectionRole connection Role of the connection
         * @return the connection Role String
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public static @NonNull String connectionRoleToString(int connectionRole) {
            if (connectionRole == CONNECTION_ROLE_CENTRAL) {
                return "Central";
            } else if (connectionRole == CONNECTION_ROLE_PERIPHERAL) {
                return "Peripheral";
            } else {
                return "INVALID:" + connectionRole;
            }
        }

        /**
         * Get the current transmit power level for the connection.
         *
         * @return the TX power level
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getTxPowerLevel() {
            return mTxPowerLevel;
        }

        /**
         * Get the Received Signal Strength Indication (RSSI) value for the connection.
         *
         * @return the RSSI
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getRssi() {
            return mRssi;
        }

        /**
         * Get the Signal-to-Noise Ratio (SNR) value for the connection.
         *
         * @return the SNR
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getSnr() {
            return mSnr;
        }

        /**
         * Get the number of unused channels in AFH_channel_map.
         *
         * @return the number of unused channels
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getUnusedAfhChannelCount() {
            return mUnusedAfhChannelCount;
        }

        /**
         * Get the number of the channels which are interfered and quality is bad but are still
         * selected for AFH.
         *
         * @return the number of the selected unideal channels
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getAfhSelectUnidealChannelCount() {
            return mAfhSelectUnidealChannelCount;
        }

        /**
         * Get the current link supervision timeout setting. time_ms: N * 0.625 ms (1 slot).
         *
         * @return link supervision timeout value
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getLsto() {
            return mLsto;
        }

        /**
         * Get the piconet clock for the specified Connection_Handle. time_ms: N * 0.3125 ms (1
         * Bluetooth Clock).
         *
         * @return the piconet clock
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPiconetClock() {
            return mPiconetClock;
        }

        /**
         * Get the count of retransmission.
         *
         * @return the count of retransmission
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getRetransmissionCount() {
            return mRetransmissionCount;
        }

        /**
         * Get the count of no RX.
         *
         * @return the count of no RX
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getNoRxCount() {
            return mNoRxCount;
        }

        /**
         * Get the count of NAK(Negative Acknowledge).
         *
         * @return the count of NAK
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getNakCount() {
            return mNakCount;
        }

        /**
         * Get the timestamp of last TX ACK. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp of last TX ACK
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getLastTxAckTimestamp() {
            return mLastTxAckTimestamp;
        }

        /**
         * Get the count of flow-off.
         *
         * @return the count of flow-off
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getFlowOffCount() {
            return mFlowOffCount;
        }

        /**
         * Get the timestamp of last flow-on.
         *
         * @return the timestamp of last flow-on
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getLastFlowOnTimestamp() {
            return mLastFlowOnTimestamp;
        }

        /**
         * Get the buffer overflow count (how many bytes of TX data are dropped) since the last
         * event.
         *
         * @return the buffer overflow count
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getOverflowCount() {
            return mOverflowCount;
        }

        /**
         * Get the buffer underflow count (in byte).
         *
         * @return the buffer underflow count
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getUnderflowCount() {
            return mUnderflowCount;
        }

        /**
         * Get the count of calibration failed items.
         *
         * @return the count of calibration failure
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getCalFailedItemCount() {
            return mCalFailedItemCount;
        }

        /**
         * Gets the total number of packets transmitted.
         *
         * @return the total number of transmitted packets
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getTxTotalPackets() {
            return mTxTotalPackets;
        }

        /**
         * Gets the number of transmitted packets that did not receive an acknowledgment.
         *
         * @return the number of unacknowledged packets
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getTxUnackPackets() {
            return mTxUnackPackets;
        }

        /**
         * Gets the number of packets that were not sent out by their flush point.
         *
         * @return the number of packets not sent due to flush
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getTxFlushPackets() {
            return mTxFlushPackets;
        }

        /**
         * Gets the number of CIS (Connected Isochronous Stream) Data PDUs transmitted by the Link
         * Layer in the last subevent of a CIS event.
         *
         * @return the number of CIS Data PDUs transmitted in the last subevent
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getTxLastSubeventPackets() {
            return mTxLastSubeventPackets;
        }

        /**
         * Gets the number of received packets with CRC (Cyclic Redundancy Check) errors since the
         * last event.
         *
         * @return the number of packets received with CRC errors
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getCrcErrorPackets() {
            return mCrcErrorPackets;
        }

        /**
         * Gets the number of duplicate (retransmitted) packets received since the last event.
         *
         * @return the number of duplicate packets received
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getRxDupPackets() {
            return mRxDupPackets;
        }

        /**
         * Gets the number of unreceived packets. This value corresponds to the parameter of the LE
         * Read ISO Link Quality command.
         *
         * @return the number of unreceived packets
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getRxUnRecvPackets() {
            return mRxUnRecvPackets;
        }

        /**
         * Gets the coexistence information mask.
         *
         * @return the coexistence information mask value
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getCoexInfoMask() {
            return mCoexInfoMask;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /** Write BqrCommon to parcel. */
        @Hide
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mQualityReportId);
            dest.writeInt(mPacketType);
            dest.writeInt(mConnectionHandle);
            dest.writeInt(mConnectionRole);
            dest.writeInt(mTxPowerLevel);
            dest.writeInt(mRssi);
            dest.writeInt(mSnr);
            dest.writeInt(mUnusedAfhChannelCount);
            dest.writeInt(mAfhSelectUnidealChannelCount);
            dest.writeInt(mLsto);
            dest.writeLong(mPiconetClock);
            dest.writeLong(mRetransmissionCount);
            dest.writeLong(mNoRxCount);
            dest.writeLong(mNakCount);
            dest.writeLong(mLastTxAckTimestamp);
            dest.writeLong(mFlowOffCount);
            dest.writeLong(mLastFlowOnTimestamp);
            dest.writeLong(mOverflowCount);
            dest.writeLong(mUnderflowCount);
            BluetoothUtils.writeStringToParcel(dest, mAddr);
            dest.writeInt(mCalFailedItemCount);
            dest.writeLong(mTxTotalPackets);
            dest.writeLong(mTxUnackPackets);
            dest.writeLong(mTxFlushPackets);
            dest.writeLong(mTxLastSubeventPackets);
            dest.writeLong(mCrcErrorPackets);
            dest.writeLong(mRxDupPackets);
            dest.writeLong(mRxUnRecvPackets);
            dest.writeInt(mCoexInfoMask);
        }

        @Hide @SystemApi
        public static final @NonNull Parcelable.Creator<BqrCommon> CREATOR =
                new Parcelable.Creator<BqrCommon>() {
                    public BqrCommon createFromParcel(Parcel in) {
                        return new BqrCommon(in);
                    }

                    public BqrCommon[] newArray(int size) {
                        return new BqrCommon[size];
                    }
                };

        /** BqrCommon to String. */
        @Override
        public String toString() {
            String str;
            str =
                    "  BqrCommon: {\n"
                            + "    mQualityReportId: "
                            + qualityReportIdToString(getQualityReportId())
                            + "("
                            + String.format("0x%02X", mQualityReportId)
                            + ")"
                            + ", mPacketType: "
                            + packetTypeToString(mPacketType)
                            + "("
                            + String.format("0x%02X", mPacketType)
                            + ")"
                            + ", mConnectionHandle: "
                            + String.format("0x%04X", mConnectionHandle)
                            + ", mConnectionRole: "
                            + getConnectionRole()
                            + "("
                            + mConnectionRole
                            + ")"
                            + ", mTxPowerLevel: "
                            + mTxPowerLevel
                            + ", mRssi: "
                            + mRssi
                            + ", mSnr: "
                            + mSnr
                            + ", mUnusedAfhChannelCount: "
                            + mUnusedAfhChannelCount
                            + ",\n"
                            + "    mAfhSelectUnidealChannelCount: "
                            + mAfhSelectUnidealChannelCount
                            + ", mLsto: "
                            + mLsto
                            + ", mPiconetClock: "
                            + String.format("0x%08X", mPiconetClock)
                            + ", mRetransmissionCount: "
                            + mRetransmissionCount
                            + ", mNoRxCount: "
                            + mNoRxCount
                            + ", mNakCount: "
                            + mNakCount
                            + ", mLastTxAckTimestamp: "
                            + String.format("0x%08X", mLastTxAckTimestamp)
                            + ", mFlowOffCount: "
                            + mFlowOffCount
                            + ",\n"
                            + "    mLastFlowOnTimestamp: "
                            + String.format("0x%08X", mLastFlowOnTimestamp)
                            + ", mOverflowCount: "
                            + mOverflowCount
                            + ", mUnderflowCount: "
                            + mUnderflowCount
                            + ", mAddr: "
                            + mAddr
                            + ", mCalFailedItemCount: "
                            + mCalFailedItemCount
                            + ",\n"
                            + "    mTxTotalPackets: "
                            + mTxTotalPackets
                            + ", mTxUnackPackets: "
                            + mTxUnackPackets
                            + ", mTxFlushPackets: "
                            + mTxFlushPackets
                            + ", mTxLastSubeventPackets: "
                            + mTxLastSubeventPackets
                            + ", mCrcErrorPackets: "
                            + mCrcErrorPackets
                            + ", mRxDupPackets: "
                            + mRxDupPackets
                            + ", mRxUnRecvPackets: "
                            + mRxUnRecvPackets
                            + ", mCoexInfoMask: "
                            + mCoexInfoMask
                            + "\n  }";

            return str;
        }
    }

    /**
     * This class provides the System APIs to access the vendor specific part of Approaching LSTO
     * event.
     */
    @Hide
    @SystemApi
    public static final class BqrVsLsto implements Parcelable {
        private static final String TAG =
                BluetoothQualityReport.TAG + "." + BqrVsLsto.class.getSimpleName();

        private final int mConnState;
        private final long mBasebandStats;
        private final long mSlotsUsed;
        private final int mCxmDenials;
        private final int mTxSkipped;
        private final int mRfLoss;
        private final long mNativeClock;
        private final long mLastTxAckTimestamp;

        private BqrVsLsto(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mConnState = bqrBuf.get() & 0xFF;
            mBasebandStats = bqrBuf.getInt() & 0xFFFFFFFFL;
            mSlotsUsed = bqrBuf.getInt() & 0xFFFFFFFFL;
            mCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mTxSkipped = bqrBuf.getShort() & 0xFFFF;
            mRfLoss = bqrBuf.getShort() & 0xFFFF;
            mNativeClock = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastTxAckTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
        }

        private BqrVsLsto(Parcel in) {
            mConnState = in.readInt();
            mBasebandStats = in.readLong();
            mSlotsUsed = in.readLong();
            mCxmDenials = in.readInt();
            mTxSkipped = in.readInt();
            mRfLoss = in.readInt();
            mNativeClock = in.readLong();
            mLastTxAckTimestamp = in.readLong();
        }

        /**
         * Get the conn state of sco.
         *
         * @return the conn state
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getConnState() {
            return mConnState;
        }

        /**
         * Get the string of conn state of sco.
         *
         * @param connectionState connection state of sco
         * @return the string of conn state
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public static @Nullable String connStateToString(int connectionState) {
            return ConnState.toString(connectionState);
        }

        /**
         * Get the baseband statistics.
         *
         * @return the baseband statistics
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getBasebandStats() {
            return mBasebandStats;
        }

        /**
         * Get the count of slots allocated for current connection.
         *
         * @return the count of slots allocated for current connection
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getSlotsUsed() {
            return mSlotsUsed;
        }

        /**
         * Get the count of Coex denials.
         *
         * @return the count of CXM denials
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getCxmDenials() {
            return mCxmDenials;
        }

        /**
         * Get the count of TX skipped when no poll from remote device.
         *
         * @return the count of TX skipped
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getTxSkipped() {
            return mTxSkipped;
        }

        /**
         * Get the count of RF loss.
         *
         * @return the count of RF loss
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getRfLoss() {
            return mRfLoss;
        }

        /**
         * Get the timestamp when issue happened. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp when issue happened
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getNativeClock() {
            return mNativeClock;
        }

        /**
         * Get the timestamp of last TX ACK. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp of last TX ACK
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getLastTxAckTimestamp() {
            return mLastTxAckTimestamp;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /** Write BqrVsLsto to parcel. */
        @Hide
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mConnState);
            dest.writeLong(mBasebandStats);
            dest.writeLong(mSlotsUsed);
            dest.writeInt(mCxmDenials);
            dest.writeInt(mTxSkipped);
            dest.writeInt(mRfLoss);
            dest.writeLong(mNativeClock);
            dest.writeLong(mLastTxAckTimestamp);
        }

        @Hide @SystemApi
        public static final @NonNull Parcelable.Creator<BqrVsLsto> CREATOR =
                new Parcelable.Creator<BqrVsLsto>() {
                    public BqrVsLsto createFromParcel(Parcel in) {
                        return new BqrVsLsto(in);
                    }

                    public BqrVsLsto[] newArray(int size) {
                        return new BqrVsLsto[size];
                    }
                };

        /** BqrVsLsto to String. */
        @Override
        public String toString() {
            String str;
            str =
                    "  BqrVsLsto: {\n"
                            + "    mConnState: "
                            + connStateToString(getConnState())
                            + "("
                            + String.format("0x%02X", mConnState)
                            + ")"
                            + ", mBasebandStats: "
                            + String.format("0x%08X", mBasebandStats)
                            + ", mSlotsUsed: "
                            + mSlotsUsed
                            + ", mCxmDenials: "
                            + mCxmDenials
                            + ", mTxSkipped: "
                            + mTxSkipped
                            + ", mRfLoss: "
                            + mRfLoss
                            + ", mNativeClock: "
                            + String.format("0x%08X", mNativeClock)
                            + ", mLastTxAckTimestamp: "
                            + String.format("0x%08X", mLastTxAckTimestamp)
                            + "\n  }";

            return str;
        }
    }

    /**
     * This class provides the System APIs to access the vendor specific part of A2dp choppy event.
     */
    @Hide
    @SystemApi
    public static final class BqrVsA2dpChoppy implements Parcelable {
        private static final String TAG =
                BluetoothQualityReport.TAG + "." + BqrVsA2dpChoppy.class.getSimpleName();

        private final long mArrivalTime;
        private final long mScheduleTime;
        private final int mGlitchCount;
        private final int mTxCxmDenials;
        private final int mRxCxmDenials;
        private final int mAclTxQueueLength;
        private final int mLinkQuality;

        private BqrVsA2dpChoppy(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mArrivalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mScheduleTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mGlitchCount = bqrBuf.getShort() & 0xFFFF;
            mTxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mRxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mAclTxQueueLength = bqrBuf.get() & 0xFF;
            mLinkQuality = bqrBuf.get() & 0xFF;
        }

        private BqrVsA2dpChoppy(Parcel in) {
            mArrivalTime = in.readLong();
            mScheduleTime = in.readLong();
            mGlitchCount = in.readInt();
            mTxCxmDenials = in.readInt();
            mRxCxmDenials = in.readInt();
            mAclTxQueueLength = in.readInt();
            mLinkQuality = in.readInt();
        }

        /**
         * Get the timestamp of a2dp packet arrived. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp of a2dp packet arrived
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getArrivalTime() {
            return mArrivalTime;
        }

        /**
         * Get the timestamp of a2dp packet scheduled. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp of a2dp packet scheduled
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getScheduleTime() {
            return mScheduleTime;
        }

        /**
         * Get the a2dp glitch count since the last event.
         *
         * @return the a2dp glitch count
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getGlitchCount() {
            return mGlitchCount;
        }

        /**
         * Get the count of Coex TX denials.
         *
         * @return the count of Coex TX denials
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getTxCxmDenials() {
            return mTxCxmDenials;
        }

        /**
         * Get the count of Coex RX denials.
         *
         * @return the count of Coex RX denials
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getRxCxmDenials() {
            return mRxCxmDenials;
        }

        /**
         * Get the ACL queue length which are pending TX in FW.
         *
         * @return the ACL queue length
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getAclTxQueueLength() {
            return mAclTxQueueLength;
        }

        /**
         * Get the link quality for the current connection.
         *
         * @return the link quality
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getLinkQuality() {
            return mLinkQuality;
        }

        /**
         * Get the string of link quality for the current connection.
         *
         * @param linkQuality link quality for the current connection
         * @return the string of link quality
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public static @Nullable String linkQualityToString(int linkQuality) {
            LinkQuality q = LinkQuality.fromOrdinal(linkQuality);
            return q.toString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /** Write BqrVsA2dpChoppy to parcel. */
        @Hide
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeLong(mArrivalTime);
            dest.writeLong(mScheduleTime);
            dest.writeInt(mGlitchCount);
            dest.writeInt(mTxCxmDenials);
            dest.writeInt(mRxCxmDenials);
            dest.writeInt(mAclTxQueueLength);
            dest.writeInt(mLinkQuality);
        }

        @Hide @SystemApi
        public static final @NonNull Parcelable.Creator<BqrVsA2dpChoppy> CREATOR =
                new Parcelable.Creator<BqrVsA2dpChoppy>() {
                    public BqrVsA2dpChoppy createFromParcel(Parcel in) {
                        return new BqrVsA2dpChoppy(in);
                    }

                    public BqrVsA2dpChoppy[] newArray(int size) {
                        return new BqrVsA2dpChoppy[size];
                    }
                };

        /** BqrVsA2dpChoppy to String. */
        @Override
        public String toString() {
            String str;
            str =
                    "  BqrVsA2dpChoppy: {\n"
                            + "    mArrivalTime: "
                            + String.format("0x%08X", mArrivalTime)
                            + ", mScheduleTime: "
                            + String.format("0x%08X", mScheduleTime)
                            + ", mGlitchCount: "
                            + mGlitchCount
                            + ", mTxCxmDenials: "
                            + mTxCxmDenials
                            + ", mRxCxmDenials: "
                            + mRxCxmDenials
                            + ", mAclTxQueueLength: "
                            + mAclTxQueueLength
                            + ", mLinkQuality: "
                            + linkQualityToString(mLinkQuality)
                            + "("
                            + String.format("0x%02X", mLinkQuality)
                            + ")"
                            + "\n  }";

            return str;
        }
    }

    /**
     * This class provides the System APIs to access the vendor specific part of SCO choppy event.
     */
    @Hide
    @SystemApi
    public static final class BqrVsScoChoppy implements Parcelable {
        private static final String TAG =
                BluetoothQualityReport.TAG + "." + BqrVsScoChoppy.class.getSimpleName();

        private final int mGlitchCount;
        private final int mIntervalEsco;
        private final int mWindowEsco;
        private final int mAirFormat;
        private final int mInstanceCount;
        private final int mTxCxmDenials;
        private final int mRxCxmDenials;
        private final int mTxAbortCount;
        private final int mLateDispatch;
        private final int mMicIntrMiss;
        private final int mLpaIntrMiss;
        private final int mSprIntrMiss;
        private final int mPlcFillCount;
        private final int mPlcDiscardCount;
        private final int mMissedInstanceCount;
        private final int mTxRetransmitSlotCount;
        private final int mRxRetransmitSlotCount;
        private final int mGoodRxFrameCount;

        private BqrVsScoChoppy(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mGlitchCount = bqrBuf.getShort() & 0xFFFF;
            mIntervalEsco = bqrBuf.get() & 0xFF;
            mWindowEsco = bqrBuf.get() & 0xFF;
            mAirFormat = bqrBuf.get() & 0xFF;
            mInstanceCount = bqrBuf.getShort() & 0xFFFF;
            mTxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mRxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mTxAbortCount = bqrBuf.getShort() & 0xFFFF;
            mLateDispatch = bqrBuf.getShort() & 0xFFFF;
            mMicIntrMiss = bqrBuf.getShort() & 0xFFFF;
            mLpaIntrMiss = bqrBuf.getShort() & 0xFFFF;
            mSprIntrMiss = bqrBuf.getShort() & 0xFFFF;
            mPlcFillCount = bqrBuf.getShort() & 0xFFFF;
            mPlcDiscardCount = bqrBuf.getShort() & 0xFFFF;
            mMissedInstanceCount = bqrBuf.getShort() & 0xFFFF;
            mTxRetransmitSlotCount = bqrBuf.getShort() & 0xFFFF;
            mRxRetransmitSlotCount = bqrBuf.getShort() & 0xFFFF;
            mGoodRxFrameCount = bqrBuf.getShort() & 0xFFFF;
        }

        private BqrVsScoChoppy(Parcel in) {
            mGlitchCount = in.readInt();
            mIntervalEsco = in.readInt();
            mWindowEsco = in.readInt();
            mAirFormat = in.readInt();
            mInstanceCount = in.readInt();
            mTxCxmDenials = in.readInt();
            mRxCxmDenials = in.readInt();
            mTxAbortCount = in.readInt();
            mLateDispatch = in.readInt();
            mMicIntrMiss = in.readInt();
            mLpaIntrMiss = in.readInt();
            mSprIntrMiss = in.readInt();
            mPlcFillCount = in.readInt();
            mPlcDiscardCount = in.readInt();
            mMissedInstanceCount = in.readInt();
            mTxRetransmitSlotCount = in.readInt();
            mRxRetransmitSlotCount = in.readInt();
            mGoodRxFrameCount = in.readInt();
        }

        /**
         * Get the sco glitch count since the last event.
         *
         * @return the sco glitch count
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getGlitchCount() {
            return mGlitchCount;
        }

        /**
         * Get ESCO interval in slots. It is the value of Transmission_Interval parameter in
         * Synchronous Connection Complete event.
         *
         * @return ESCO interval in slots
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getIntervalEsco() {
            return mIntervalEsco;
        }

        /**
         * Get ESCO window in slots. It is the value of Retransmission Window parameter in
         * Synchronous Connection Complete event.
         *
         * @return ESCO window in slots
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getWindowEsco() {
            return mWindowEsco;
        }

        /**
         * Get the air mode. It is the value of Air Mode parameter in Synchronous Connection
         * Complete event.
         *
         * @return the air mode
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getAirFormat() {
            return mAirFormat;
        }

        /**
         * Get the string of air mode.
         *
         * @param airFormat the value of Air Mode parameter in Synchronous Connection Complete event
         * @return the string of air mode
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public static @Nullable String airFormatToString(int airFormat) {
            AirMode m = AirMode.fromOrdinal(airFormat);
            return m.toString();
        }

        /**
         * Get the xSCO instance count.
         *
         * @return the xSCO instance count
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getInstanceCount() {
            return mInstanceCount;
        }

        /**
         * Get the count of Coex TX denials.
         *
         * @return the count of Coex TX denials
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getTxCxmDenials() {
            return mTxCxmDenials;
        }

        /**
         * Get the count of Coex RX denials.
         *
         * @return the count of Coex RX denials
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getRxCxmDenials() {
            return mRxCxmDenials;
        }

        /**
         * Get the count of sco packets aborted.
         *
         * @return the count of sco packets aborted
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getTxAbortCount() {
            return mTxAbortCount;
        }

        /**
         * Get the count of sco packets dispatched late.
         *
         * @return the count of sco packets dispatched late
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getLateDispatch() {
            return mLateDispatch;
        }

        /**
         * Get the count of missed Mic interrupts.
         *
         * @return the count of missed Mic interrupts
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getMicIntrMiss() {
            return mMicIntrMiss;
        }

        /**
         * Get the count of missed LPA interrupts.
         *
         * @return the count of missed LPA interrupts
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getLpaIntrMiss() {
            return mLpaIntrMiss;
        }

        /**
         * Get the count of missed Speaker interrupts.
         *
         * @return the count of missed Speaker interrupts
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getSprIntrMiss() {
            return mSprIntrMiss;
        }

        /**
         * Get the count of packet loss concealment filled.
         *
         * @return the count of packet loss concealment filled
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getPlcFillCount() {
            return mPlcFillCount;
        }

        /**
         * Get the count of packet loss concealment discarded.
         *
         * @return the count of packet loss concealment discarded
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getPlcDiscardCount() {
            return mPlcDiscardCount;
        }

        /**
         * Get the count of sco instances missed.
         *
         * @return the count of sco instances missed
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getMissedInstanceCount() {
            return mMissedInstanceCount;
        }

        /**
         * Get the count of slots for Tx retransmission.
         *
         * @return the count of slots for Tx retransmission
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getTxRetransmitSlotCount() {
            return mTxRetransmitSlotCount;
        }

        /**
         * Get the count of slots for Rx retransmission.
         *
         * @return the count of slots for Rx retransmission
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getRxRetransmitSlotCount() {
            return mRxRetransmitSlotCount;
        }

        /**
         * Get the count of Rx good packets
         *
         * @return the count of Rx good packets
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getGoodRxFrameCount() {
            return mGoodRxFrameCount;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /** Write BqrVsScoChoppy to parcel. */
        @Hide
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mGlitchCount);
            dest.writeInt(mIntervalEsco);
            dest.writeInt(mWindowEsco);
            dest.writeInt(mAirFormat);
            dest.writeInt(mInstanceCount);
            dest.writeInt(mTxCxmDenials);
            dest.writeInt(mRxCxmDenials);
            dest.writeInt(mTxAbortCount);
            dest.writeInt(mLateDispatch);
            dest.writeInt(mMicIntrMiss);
            dest.writeInt(mLpaIntrMiss);
            dest.writeInt(mSprIntrMiss);
            dest.writeInt(mPlcFillCount);
            dest.writeInt(mPlcDiscardCount);
            dest.writeInt(mMissedInstanceCount);
            dest.writeInt(mTxRetransmitSlotCount);
            dest.writeInt(mRxRetransmitSlotCount);
            dest.writeInt(mGoodRxFrameCount);
        }

        @Hide @SystemApi
        public static final @NonNull Parcelable.Creator<BqrVsScoChoppy> CREATOR =
                new Parcelable.Creator<BqrVsScoChoppy>() {
                    public BqrVsScoChoppy createFromParcel(Parcel in) {
                        return new BqrVsScoChoppy(in);
                    }

                    public BqrVsScoChoppy[] newArray(int size) {
                        return new BqrVsScoChoppy[size];
                    }
                };

        /** BqrVsScoChoppy to String. */
        @Override
        public String toString() {
            String str;
            str =
                    "  BqrVsScoChoppy: {\n"
                            + "    mGlitchCount: "
                            + mGlitchCount
                            + ", mIntervalEsco: "
                            + mIntervalEsco
                            + ", mWindowEsco: "
                            + mWindowEsco
                            + ", mAirFormat: "
                            + airFormatToString(mAirFormat)
                            + "("
                            + String.format("0x%02X", mAirFormat)
                            + ")"
                            + ", mInstanceCount: "
                            + mInstanceCount
                            + ", mTxCxmDenials: "
                            + mTxCxmDenials
                            + ", mRxCxmDenials: "
                            + mRxCxmDenials
                            + ", mTxAbortCount: "
                            + mTxAbortCount
                            + ",\n"
                            + "    mLateDispatch: "
                            + mLateDispatch
                            + ", mMicIntrMiss: "
                            + mMicIntrMiss
                            + ", mLpaIntrMiss: "
                            + mLpaIntrMiss
                            + ", mSprIntrMiss: "
                            + mSprIntrMiss
                            + ", mPlcFillCount: "
                            + mPlcFillCount
                            + ", mPlcDiscardCount: "
                            + mPlcDiscardCount
                            + ", mMissedInstanceCount: "
                            + mMissedInstanceCount
                            + ", mTxRetransmitSlotCount: "
                            + mTxRetransmitSlotCount
                            + ",\n"
                            + "    mRxRetransmitSlotCount: "
                            + mRxRetransmitSlotCount
                            + ", mGoodRxFrameCount: "
                            + mGoodRxFrameCount
                            + "\n  }";

            return str;
        }
    }

    /** This class provides the System APIs to access the Connect fail event. */
    @Hide
    @SystemApi
    public static final class BqrConnectFail implements Parcelable {
        private static final String TAG =
                BluetoothQualityReport.TAG + "." + BqrConnectFail.class.getSimpleName();

        /** Connect Fail reason: No error. */
        @Hide @SystemApi public static final int CONNECT_FAIL_ID_NO_ERROR = 0x00;

        /** Connect Fail reason: Page timeout. */
        @Hide @SystemApi public static final int CONNECT_FAIL_ID_PAGE_TIMEOUT = 0x04;

        /** Connect Fail reason: Connection timeout. */
        @Hide @SystemApi public static final int CONNECT_FAIL_ID_CONNECTION_TIMEOUT = 0x08;

        /** Connect Fail reason: ACL already exists. */
        @Hide @SystemApi public static final int CONNECT_FAIL_ID_ACL_ALREADY_EXIST = 0x0b;

        /** Connect Fail reason: Controller busy. */
        @Hide @SystemApi public static final int CONNECT_FAIL_ID_CONTROLLER_BUSY = 0x3a;

        @Hide
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"CONNECT_FAIL_ID"},
                value = {
                    CONNECT_FAIL_ID_NO_ERROR,
                    CONNECT_FAIL_ID_PAGE_TIMEOUT,
                    CONNECT_FAIL_ID_CONNECTION_TIMEOUT,
                    CONNECT_FAIL_ID_ACL_ALREADY_EXIST,
                    CONNECT_FAIL_ID_CONTROLLER_BUSY,
                })
        public @interface ConnectFailId {}

        private final int mFailReason;

        private BqrConnectFail(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mFailReason = bqrBuf.get() & 0xFF;
        }

        private BqrConnectFail(Parcel in) {
            mFailReason = in.readInt();
        }

        /**
         * Get the fail reason.
         *
         * @return the fail reason
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @ConnectFailId int getFailReason() {
            return mFailReason;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /** Write BqrConnectFail to parcel. */
        @Hide
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mFailReason);
        }

        @Hide @SystemApi
        public static final @NonNull Parcelable.Creator<BqrConnectFail> CREATOR =
                new Parcelable.Creator<BqrConnectFail>() {
                    public BqrConnectFail createFromParcel(Parcel in) {
                        return new BqrConnectFail(in);
                    }

                    public BqrConnectFail[] newArray(int size) {
                        return new BqrConnectFail[size];
                    }
                };

        /**
         * Get the string of the Connect Fail ID.
         *
         * @param id the connect fail reason
         * @return the string of the id
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public static @NonNull String connectFailIdToString(@ConnectFailId int id) {
            return switch (id) {
                case CONNECT_FAIL_ID_NO_ERROR -> "No error";
                case CONNECT_FAIL_ID_PAGE_TIMEOUT -> "Page Timeout";
                case CONNECT_FAIL_ID_CONNECTION_TIMEOUT -> "Connection Timeout";
                case CONNECT_FAIL_ID_ACL_ALREADY_EXIST -> "ACL already exists";
                case CONNECT_FAIL_ID_CONTROLLER_BUSY -> "Controller busy";
                default -> "INVALID";
            };
        }

        /** BqrConnectFail to String. */
        @Override
        public String toString() {
            String str;
            str =
                    "  BqrConnectFail: {\n"
                            + "    mFailReason: "
                            + connectFailIdToString(mFailReason)
                            + " ("
                            + String.format("0x%02X", mFailReason)
                            + ")"
                            + "\n  }";

            return str;
        }
    }

    /**
     * This class provides APIs to access the Energy Monitoring events from the Bluetooth Quality
     * Report. It includes detailed information about the energy consumption of various Bluetooth
     * operations, such as transmit and receive times for both BR/EDR and LE connections, as well as
     * time spent in different power states.
     */
    @Hide
    @SystemApi
    public static final class BqrEnergyMonitor implements Parcelable {
        private static final String TAG =
                BluetoothQualityReport.TAG + "." + BqrEnergyMonitor.class.getSimpleName();

        private final int mAvgCurrentConsume;
        private final long mIdleTotalTime;
        private final long mIdleStateEnterCount;
        private final long mActiveTotalTime;
        private final long mActiveStateEnterCount;
        private final long mBredrTxTotalTime;
        private final long mBredrTxStateEnterCount;
        private final int mBredrTxAvgPowerLevel;
        private final long mBredrRxTotalTime;
        private final long mBredrRxStateEnterCount;
        private final long mLeTxTotalTime;
        private final long mLeTxStateEnterCount;
        private final int mLeTxAvgPowerLevel;
        private final long mLeRxTotalTime;
        private final long mLeRxStateEnterCount;
        private final long mReportTotalTime;
        private final long mRxActiveOneChainTime;
        private final long mRxActiveTwoChainTime;
        private final long mTxiPaActiveOneChainTime;
        private final long mTxiPaActiveTwoChainTime;
        private final long mTxePaActiveOneChainTime;
        private final long mTxePaActiveTwoChainTime;

        private BqrEnergyMonitor(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(
                        TAG + ": BQR EnergyMonitor raw data length is abnormal.");
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mAvgCurrentConsume = bqrBuf.getShort() & 0xFFFF;
            mIdleTotalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mIdleStateEnterCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mActiveTotalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mActiveStateEnterCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mBredrTxTotalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mBredrTxStateEnterCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mBredrTxAvgPowerLevel = bqrBuf.get() & 0xFF;
            mBredrRxTotalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mBredrRxStateEnterCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLeTxTotalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLeTxStateEnterCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLeTxAvgPowerLevel = bqrBuf.get() & 0xFF;
            mLeRxTotalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLeRxStateEnterCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mReportTotalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRxActiveOneChainTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRxActiveTwoChainTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxiPaActiveOneChainTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxiPaActiveTwoChainTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxePaActiveOneChainTime = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxePaActiveTwoChainTime = bqrBuf.getInt() & 0xFFFFFFFFL;
        }

        private BqrEnergyMonitor(Parcel in) {
            mAvgCurrentConsume = in.readInt();
            mIdleTotalTime = in.readLong();
            mIdleStateEnterCount = in.readLong();
            mActiveTotalTime = in.readLong();
            mActiveStateEnterCount = in.readLong();
            mBredrTxTotalTime = in.readLong();
            mBredrTxStateEnterCount = in.readLong();
            mBredrTxAvgPowerLevel = in.readInt();
            mBredrRxTotalTime = in.readLong();
            mBredrRxStateEnterCount = in.readLong();
            mLeTxTotalTime = in.readLong();
            mLeTxStateEnterCount = in.readLong();
            mLeTxAvgPowerLevel = in.readInt();
            mLeRxTotalTime = in.readLong();
            mLeRxStateEnterCount = in.readLong();
            mReportTotalTime = in.readLong();
            mRxActiveOneChainTime = in.readLong();
            mRxActiveTwoChainTime = in.readLong();
            mTxiPaActiveOneChainTime = in.readLong();
            mTxiPaActiveTwoChainTime = in.readLong();
            mTxePaActiveOneChainTime = in.readLong();
            mTxePaActiveTwoChainTime = in.readLong();
        }

        /**
         * Gets the average current consumption of all activities consumed by the controller in
         * microamps.
         *
         * @return the average current consumption in microamps
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getAverageCurrentConsumptionMicroamps() {
            return mAvgCurrentConsume;
        }

        /**
         * Gets the total time the controller has spent in the idle state (low power states, sleep)
         * in milliseconds.
         *
         * @return the total time in the idle state in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getIdleStateTotalTimeMillis() {
            return mIdleTotalTime;
        }

        /**
         * Gets the number of times the controller has entered the idle state.
         *
         * @return the number of times the controller has entered the idle state
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getIdleStateEnterCount() {
            return mIdleStateEnterCount;
        }

        /**
         * Gets the total time the controller has spent in the active state (inquiring, paging,
         * ACL/SCO/eSCO/BIS/CIS traffic, processing any task) in milliseconds.
         *
         * @return the total time in the active state in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getActiveStateTotalTimeMillis() {
            return mActiveTotalTime;
        }

        /**
         * Gets the number of times the controller has entered the active state.
         *
         * @return the number of times the controller has entered the active state
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getActiveStateEnterCount() {
            return mActiveStateEnterCount;
        }

        /**
         * Gets the total time the controller has spent transmitting BR/EDR data (ACL/SCO/eSCO
         * traffic) in milliseconds.
         *
         * @return the total time spent in the BR/EDR transmit state in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getBredrTxTotalTimeMillis() {
            return mBredrTxTotalTime;
        }

        /**
         * Gets the number of times the controller has entered the BR/EDR transmit state.
         *
         * @return the number of times the controller has entered the BR/EDR transmit state
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getBredrTxStateEnterCount() {
            return mBredrTxStateEnterCount;
        }

        /**
         * Gets the average transmit power level of all BR/EDR links in dBm.
         *
         * @return the average transmit power level of all BR/EDR links in dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getBredrAverageTxPowerLeveldBm() {
            return mBredrTxAvgPowerLevel;
        }

        /**
         * Gets the total time the controller has spent receiving BR/EDR data (ACL/SCO/eSCO traffic)
         * in milliseconds.
         *
         * @return the total time spent in the BR/EDR receive state in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getBredrRxTotalTimeMillis() {
            return mBredrRxTotalTime;
        }

        /**
         * Gets the number of times the controller has entered the BR/EDR receive state.
         *
         * @return the number of times the controller has entered the BR/EDR receive state
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getBredrRxStateEnterCount() {
            return mBredrRxStateEnterCount;
        }

        /**
         * Gets the total time the controller has spent transmitting LE data (ACL/BIS/CIS or LE
         * advertising traffic) in milliseconds.
         *
         * @return the total time spent in the LE transmit state in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getLeTsTotalTimeMillis() {
            return mLeTxTotalTime;
        }

        /**
         * Gets the number of times the controller has entered the LE transmit state.
         *
         * @return the number of times the controller has entered the LE transmit state
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getLeTxStateEnterCount() {
            return mLeTxStateEnterCount;
        }

        /**
         * Gets the average transmit power level of all LE links in dBm.
         *
         * @return the average transmit power level of all LE links in dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getLeAverageTxPowerLeveldBm() {
            return mLeTxAvgPowerLevel;
        }

        /**
         * Gets the total time the controller has spent receiving LE data (ACL/BIS/CIS or LE
         * scanning traffic) in milliseconds.
         *
         * @return the total time spent in the LE receive state in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getLeRxTotalTimeMillis() {
            return mLeRxTotalTime;
        }

        /**
         * Gets the number of times the controller has entered the LE receive state.
         *
         * @return the number of times the controller has entered the LE receive state
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getLeRxStateEnterCount() {
            return mLeRxStateEnterCount;
        }

        /**
         * Gets the total time duration over which power-related information has been collected in
         * milliseconds.
         *
         * @return the total time duration for power data collection in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getPowerDataTotalTimeMillis() {
            return mReportTotalTime;
        }

        /**
         * Gets the time duration of the receiver being active with one antenna chain in
         * milliseconds.
         *
         * @return the time duration of single-chain receiver activity in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getRxSingleChainActiveDurationMillis() {
            return mRxActiveOneChainTime;
        }

        /**
         * Gets the time duration of the receiver being active with two antenna chains in
         * milliseconds.
         *
         * @return the time duration of dual-chain receiver activity in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getRxDualChainActiveDurationMillis() {
            return mRxActiveTwoChainTime;
        }

        /**
         * Gets the time duration of the internal transmitter being active with one antenna chain in
         * milliseconds.
         *
         * @return the time duration of single-chain internal transmitter activity in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getTxInternalPaSingleChainActiveDurationMillis() {
            return mTxiPaActiveOneChainTime;
        }

        /**
         * Gets the time duration of the internal transmitter being active with two antenna chains
         * in milliseconds.
         *
         * @return the time duration of dual-chain internal transmitter activity in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getTxInternalPaDualChainActiveDurationMillis() {
            return mTxiPaActiveTwoChainTime;
        }

        /**
         * Gets the time duration of the external transmitter being active with one antenna chain in
         * milliseconds.
         *
         * @return the time duration of single-chain external transmitter activity in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getTxExternalPaSingleChainActiveDurationMillis() {
            return mTxePaActiveOneChainTime;
        }

        /**
         * Gets the time duration of the external transmitter being active with two antenna chains
         * in milliseconds.
         *
         * @return the time duration of dual-chain external transmitter activity in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getTxExternalPaDualChainActiveDurationMillis() {
            return mTxePaActiveTwoChainTime;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /** Write BqrEnergyMonitor to parcel. */
        @Hide
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mAvgCurrentConsume);
            dest.writeLong(mIdleTotalTime);
            dest.writeLong(mIdleStateEnterCount);
            dest.writeLong(mActiveTotalTime);
            dest.writeLong(mActiveStateEnterCount);
            dest.writeLong(mBredrTxTotalTime);
            dest.writeLong(mBredrTxStateEnterCount);
            dest.writeInt(mBredrTxAvgPowerLevel);
            dest.writeLong(mBredrRxTotalTime);
            dest.writeLong(mBredrRxStateEnterCount);
            dest.writeLong(mLeTxTotalTime);
            dest.writeLong(mLeTxStateEnterCount);
            dest.writeInt(mLeTxAvgPowerLevel);
            dest.writeLong(mLeRxTotalTime);
            dest.writeLong(mLeRxStateEnterCount);
            dest.writeLong(mReportTotalTime);
            dest.writeLong(mRxActiveOneChainTime);
            dest.writeLong(mRxActiveTwoChainTime);
            dest.writeLong(mTxiPaActiveOneChainTime);
            dest.writeLong(mTxiPaActiveTwoChainTime);
            dest.writeLong(mTxePaActiveOneChainTime);
            dest.writeLong(mTxePaActiveTwoChainTime);
        }

        @Hide @SystemApi
        public static final @NonNull Parcelable.Creator<BqrEnergyMonitor> CREATOR =
                new Parcelable.Creator<BqrEnergyMonitor>() {
                    public BqrEnergyMonitor createFromParcel(Parcel in) {
                        return new BqrEnergyMonitor(in);
                    }

                    public BqrEnergyMonitor[] newArray(int size) {
                        return new BqrEnergyMonitor[size];
                    }
                };

        /** BqrVsLsto to String. */
        @Override
        public String toString() {
            return "  BqrEnergyMonitor: {\n"
                    + "    AvgCurrentConsume: "
                    + mAvgCurrentConsume
                    + ", mIdleTotalTime: "
                    + mIdleTotalTime
                    + ", mIdleStateEnterCount: "
                    + mIdleStateEnterCount
                    + ", mActiveTotalTime: "
                    + mActiveTotalTime
                    + ", mActiveStateEnterCount: "
                    + mActiveStateEnterCount
                    + ",\n"
                    + "    mBredrTxTotalTime: "
                    + mBredrTxTotalTime
                    + ", mBredrTxStateEnterCount: "
                    + mBredrTxStateEnterCount
                    + ", mBredrTxAvgPowerLevel: "
                    + mBredrTxAvgPowerLevel
                    + ", mBredrRxTotalTime: "
                    + mBredrRxTotalTime
                    + ", mBredrRxStateEnterCount: "
                    + mBredrRxStateEnterCount
                    + ", mLeTxTotalTime: "
                    + mLeTxTotalTime
                    + ", mLeTxStateEnterCount: "
                    + mLeTxStateEnterCount
                    + ", mLeTxAvgPowerLevel: "
                    + mLeTxAvgPowerLevel
                    + ", mLeRxTotalTime: "
                    + mLeRxTotalTime
                    + ", mLeRxStateEnterCount: "
                    + mLeRxStateEnterCount
                    + ", mReportTotalTime: "
                    + mReportTotalTime
                    + ", mRxActiveOneChainTime: "
                    + mRxActiveOneChainTime
                    + ", mRxActiveTwoChainTime: "
                    + mRxActiveTwoChainTime
                    + ", mTxiPaActiveOneChainTime: "
                    + mTxiPaActiveOneChainTime
                    + ", mTxiPaActiveTwoChainTime: "
                    + mTxiPaActiveTwoChainTime
                    + ", mTxePaActiveOneChainTime: "
                    + mTxePaActiveOneChainTime
                    + ", mTxePaActiveTwoChainTime: "
                    + mTxePaActiveTwoChainTime
                    + "\n  }";
        }
    }

    /**
     * This class provides APIs to access RF statistics events from the Bluetooth Quality Report. It
     * includes detailed information about received signal strength (RSSI) across different antenna
     * chains, transmit power levels, and packet counts for various transmission and reception
     * scenarios. This data can be used to analyze RF performance and identify potential issues in
     * Bluetooth connections.
     */
    @Hide
    @SystemApi
    public static final class BqrRfStats implements Parcelable {
        private static final String TAG =
                BluetoothQualityReport.TAG + "." + BqrRfStats.class.getSimpleName();

        private final int mExtensionInfo;
        private final long mReportTimePeriod;
        private final long mTxPoweriPaBf;
        private final long mTxPowerePaBf;
        private final long mTxPoweriPaDiv;
        private final long mTxPowerePaDiv;
        private final long mRssiChainOver50;
        private final long mRssiChain50To55;
        private final long mRssiChain55To60;
        private final long mRssiChain60To65;
        private final long mRssiChain65To70;
        private final long mRssiChain70To75;
        private final long mRssiChain75To80;
        private final long mRssiChain80To85;
        private final long mRssiChain85To90;
        private final long mRssiChainUnder90;
        private final long mRssiDeltaUnder2;
        private final long mRssiDelta2To5;
        private final long mRssiDelta5To8;
        private final long mRssiDelta8To11;
        private final long mRssiDeltaOver11;

        private BqrRfStats(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(
                        TAG + ": BQR RF Stats raw data length is abnormal.");
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mExtensionInfo = bqrBuf.get() & 0xFF;
            mReportTimePeriod = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxPoweriPaBf = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxPowerePaBf = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxPoweriPaDiv = bqrBuf.getInt() & 0xFFFFFFFFL;
            mTxPowerePaDiv = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChainOver50 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChain50To55 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChain55To60 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChain60To65 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChain65To70 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChain70To75 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChain75To80 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChain80To85 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChain85To90 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiChainUnder90 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiDeltaUnder2 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiDelta2To5 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiDelta5To8 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiDelta8To11 = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRssiDeltaOver11 = bqrBuf.getInt() & 0xFFFFFFFFL;
        }

        private BqrRfStats(Parcel in) {
            mExtensionInfo = in.readInt();
            mReportTimePeriod = in.readLong();
            mTxPoweriPaBf = in.readLong();
            mTxPowerePaBf = in.readLong();
            mTxPoweriPaDiv = in.readLong();
            mTxPowerePaDiv = in.readLong();
            mRssiChainOver50 = in.readLong();
            mRssiChain50To55 = in.readLong();
            mRssiChain55To60 = in.readLong();
            mRssiChain60To65 = in.readLong();
            mRssiChain65To70 = in.readLong();
            mRssiChain70To75 = in.readLong();
            mRssiChain75To80 = in.readLong();
            mRssiChain80To85 = in.readLong();
            mRssiChain85To90 = in.readLong();
            mRssiChainUnder90 = in.readLong();
            mRssiDeltaUnder2 = in.readLong();
            mRssiDelta2To5 = in.readLong();
            mRssiDelta5To8 = in.readLong();
            mRssiDelta8To11 = in.readLong();
            mRssiDeltaOver11 = in.readLong();
        }

        /**
         * Gets the extension Info for the RF stats event.
         *
         * @return the extension information for the RF stats event
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public int getExtensionInfo() {
            return mExtensionInfo;
        }

        /**
         * Gets the time duration over which performance information has been collected in
         * milliseconds.
         *
         * @return the time duration for performance data collection in milliseconds
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @DurationMillisLong long getPerformanceDurationMillis() {
            return mReportTimePeriod;
        }

        /**
         * Gets the packet count for transmissions using internal PA (iPA) beamforming.
         *
         * @return the packet count for iPA beamforming transmissions
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getTxPowerInternalPaBeamformingCount() {
            return mTxPoweriPaBf;
        }

        /**
         * Gets the packet count for transmissions using external PA (ePA) beamforming.
         *
         * @return the packet count for ePA beamforming transmissions
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getTxPowerExternalPaBeamformingCount() {
            return mTxPowerePaBf;
        }

        /**
         * Gets the packet count for transmissions using internal PA (iPA) diversity.
         *
         * @return the packet count for iPA diversity transmissions
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getTxPowerInternalPaDiversityCount() {
            return mTxPoweriPaDiv;
        }

        /**
         * Gets the packet count for transmissions using external PA (ePA) diversity.
         *
         * @return the packet count for ePA diversity transmissions
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getTxPowerExternalPaDiversityCount() {
            return mTxPowerePaDiv;
        }

        /**
         * Gets the packet count for received signals with an RSSI (Received Signal Strength
         * Indicator) greater than -50 dBm on any antenna chain.
         *
         * @return the packet count for RSSI stronger than -50 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssiAboveMinus50dBm() {
            return mRssiChainOver50;
        }

        /**
         * Gets the packet count for received signals with an RSSI between -50 dBm and -55 dBm on
         * any antenna chain.
         *
         * @return the packet count for RSSI between -50 dBm and -55 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssi50To55dBm() {
            return mRssiChain50To55;
        }

        /**
         * Gets the packet count for received signals with an RSSI between -55 dBm and -60 dBm on
         * any antenna chain.
         *
         * @return the packet count for RSSI between -55 dBm and -60 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssi55To60dBm() {
            return mRssiChain55To60;
        }

        /**
         * Gets the packet count for received signals with an RSSI between -60 dBm and -65 dBm on
         * any antenna chain.
         *
         * @return the packet count for RSSI between -60 dBm and -65 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssi60To65dBm() {
            return mRssiChain60To65;
        }

        /**
         * Gets the packet count for received signals with an RSSI between -65 dBm and -70 dBm on
         * any antenna chain.
         *
         * @return the packet count for RSSI between -65 dBm and -70 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssi65To70dBm() {
            return mRssiChain65To70;
        }

        /**
         * Gets the packet count for received signals with an RSSI between -70 dBm and -75 dBm on
         * any antenna chain.
         *
         * @return the packet count for RSSI between -70 dBm and -75 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssi70To75dBm() {
            return mRssiChain70To75;
        }

        /**
         * Gets the packet count for received signals with an RSSI between -75 dBm and -80 dBm on
         * any antenna chain.
         *
         * @return the packet count for RSSI between -75 dBm and -80 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssi75To80dBm() {
            return mRssiChain75To80;
        }

        /**
         * Gets the packet count for received signals with an RSSI between -80 dBm and -85 dBm on
         * any antenna chain.
         *
         * @return the packet count for RSSI between -80 dBm and -85 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssi80To85dBm() {
            return mRssiChain80To85;
        }

        /**
         * Gets the packet count for received signals with an RSSI between -85 dBm and -90 dBm on
         * any antenna chain.
         *
         * @return the packet count for RSSI between -85 dBm and -90 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssi85To90dBm() {
            return mRssiChain85To90;
        }

        /**
         * Gets the packet count for received signals with an RSSI (Received Signal Strength
         * Indicator) weaker than -90 dBm on any antenna chain.
         *
         * @return the packet count for RSSI weaker than -90 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssiBelowMinus90dBm() {
            return mRssiChainUnder90;
        }

        /**
         * Gets the packet count where the difference in RSSI between antenna chains is less than 2
         * dBm.
         *
         * @return the packet count for RSSI delta less than 2 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssiDeltaBelow2dBm() {
            return mRssiDeltaUnder2;
        }

        /**
         * Gets the packet count where the difference in RSSI between antenna chains is between 2
         * dBm and 5 dBm.
         *
         * @return the packet count for RSSI delta between 2 dBm and 5 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssiDelta2To5dBm() {
            return mRssiDelta2To5;
        }

        /**
         * Gets the packet count where the difference in RSSI between antenna chains is between 5
         * dBm and 8 dBm.
         *
         * @return the packet count for RSSI delta between 5 dBm and 8 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssiDelta5To8dBm() {
            return mRssiDelta5To8;
        }

        /**
         * Gets the packet count where the difference in RSSI between antenna chains is greater than
         * 11 dBm.
         *
         * @return the packet count for RSSI delta greater than 11 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssiDelta8To11dBm() {
            return mRssiDelta8To11;
        }

        /**
         * Get the Packet counter of RSSI delta are larger than 11 dBm
         *
         * @return the packet counter of RSSI delta are larger than 11 dBm
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public long getPacketsWithRssiDeltaAbove11dBm() {
            return mRssiDeltaOver11;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /** Write BqrRfStats to parcel. */
        @Hide
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mExtensionInfo);
            dest.writeLong(mReportTimePeriod);
            dest.writeLong(mTxPoweriPaBf);
            dest.writeLong(mTxPowerePaBf);
            dest.writeLong(mTxPoweriPaDiv);
            dest.writeLong(mTxPowerePaDiv);
            dest.writeLong(mRssiChainOver50);
            dest.writeLong(mRssiChain50To55);
            dest.writeLong(mRssiChain55To60);
            dest.writeLong(mRssiChain60To65);
            dest.writeLong(mRssiChain65To70);
            dest.writeLong(mRssiChain70To75);
            dest.writeLong(mRssiChain75To80);
            dest.writeLong(mRssiChain80To85);
            dest.writeLong(mRssiChain85To90);
            dest.writeLong(mRssiChainUnder90);
            dest.writeLong(mRssiDeltaUnder2);
            dest.writeLong(mRssiDelta2To5);
            dest.writeLong(mRssiDelta5To8);
            dest.writeLong(mRssiDelta8To11);
            dest.writeLong(mRssiDeltaOver11);
        }

        @Hide @SystemApi
        public static final @NonNull Parcelable.Creator<BqrRfStats> CREATOR =
                new Parcelable.Creator<BqrRfStats>() {
                    public BqrRfStats createFromParcel(Parcel in) {
                        return new BqrRfStats(in);
                    }

                    public BqrRfStats[] newArray(int size) {
                        return new BqrRfStats[size];
                    }
                };

        /** BqrVsLsto to String. */
        @Override
        public String toString() {
            return "  BqrRfStats: {\n"
                    + "    mExtensionInfo: "
                    + mExtensionInfo
                    + ", mReportTimePeriod: "
                    + mReportTimePeriod
                    + ", mTxPoweriPaBf: "
                    + mTxPoweriPaBf
                    + ", mTxPowerePaBf: "
                    + mTxPowerePaBf
                    + ", mTxPoweriPaDiv: "
                    + mTxPoweriPaDiv
                    + ", mTxPowerePaDiv: "
                    + mTxPowerePaDiv
                    + ",\n"
                    + "    mRssiChainOver50: "
                    + mRssiChainOver50
                    + ", mRssiChain50To55: "
                    + mRssiChain50To55
                    + ", mRssiChain55To60: "
                    + mRssiChain55To60
                    + ", mRssiChain60To65: "
                    + mRssiChain60To65
                    + ", mRssiChain65To70: "
                    + mRssiChain65To70
                    + ", mRssiChain70To75: "
                    + mRssiChain70To75
                    + ", mRssiChain75To80: "
                    + mRssiChain75To80
                    + ", mRssiChain80To85: "
                    + mRssiChain80To85
                    + ", mRssiChain85To90: "
                    + mRssiChain85To90
                    + ", mRssiChainUnder90: "
                    + mRssiChainUnder90
                    + ",\n"
                    + "    mRssiDeltaUnder2: "
                    + mRssiDeltaUnder2
                    + ", mRssiDelta2To5: "
                    + mRssiDelta2To5
                    + ", mRssiDelta5To8: "
                    + mRssiDelta5To8
                    + ", mRssiDelta8To11: "
                    + mRssiDelta8To11
                    + ", mRssiDeltaOver11: "
                    + mRssiDeltaOver11
                    + "\n  }";
        }
    }
}

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

package android.uwb;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.IntDef;

import com.android.uwb.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents the parameters for the LOGICAL_LINK_CREATE_CMD, which is used by a FiRa Controller to
 * establish a data connection with a remote device.
 *
 * Implements {@link Parcelable} for IPC transactions.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_UWB_FIRA_3_0_25Q4)
public final class LogicalLinkCreationParams implements Parcelable {
    private final byte[] mDestinationAddress;
    private final int mLogicalLinkClassLength;
    private int mMaxSduSizeValue;

    // Table 39: Link Layer Mode Selector values
    /**
     * @hide
     */
    @IntDef(
        value = {
            LINK_LAYER_MODE_CONNECTIONLESS_NON_SECURE,
            LINK_LAYER_MODE_CONNECTIONLESS_SECURE,
            LINK_LAYER_MODE_CONNECTION_ORIENTED_NON_SECURE,
            LINK_LAYER_MODE_CONNECTION_ORIENTED_SECURE,
            LINK_LAYER_MODE_CONNECTIONLESS_UWBS_TO_UWBS,
            LINK_LAYER_MODE_CONNECTION_ORIENTED_UWBS_UWBS,
        })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LinkLayerMode {}

    /**
     * Connectionless mode with no security.
     */
    public static final int LINK_LAYER_MODE_CONNECTIONLESS_NON_SECURE = 0x00;

    /**
     * Connectionless mode with security enabled.
     */
    public static final int LINK_LAYER_MODE_CONNECTIONLESS_SECURE = 0x01;

    /**
     * Connection-oriented mode with no security.
     */
    public static final int LINK_LAYER_MODE_CONNECTION_ORIENTED_NON_SECURE = 0x02;

    /**
     * Connection-oriented mode with security enabled.
     */
    public static final int LINK_LAYER_MODE_CONNECTION_ORIENTED_SECURE = 0x03;

    /**
     * Connectionless mode for UWBS-to-UWBS communication.
     */
    public static final int LINK_LAYER_MODE_CONNECTIONLESS_UWBS_TO_UWBS = 0x04;

    /**
     * Connection-oriented mode for UWBS-to-UWBS communication.
     */
    public static final int LINK_LAYER_MODE_CONNECTION_ORIENTED_UWBS_UWBS = 0x05;

    @LinkLayerMode private final int mLinkLayerModeSelector;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            LOGICAL_LINK_STATUS_OK,
            LOGICAL_LINK_STATUS_FAILED,
    })
    @interface LogicalLinkStatusCode {}

    /**
     * Indicates that the logical link creation was successful.
     */
    public static final int LOGICAL_LINK_STATUS_OK = 0;

    /**
     * Indicates that the logical link creation failed.
     */
    public static final int LOGICAL_LINK_STATUS_FAILED = 1;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            SDU_SIZE_64_BYTES,
            SDU_SIZE_128_BYTES,
            SDU_SIZE_192_BYTES,
            SDU_SIZE_256_BYTES,
            SDU_SIZE_384_BYTES,
            SDU_SIZE_512_BYTES,
            SDU_SIZE_760_BYTES,
            SDU_SIZE_1024_BYTES,
            SDU_SIZE_1536_BYTES,
            SDU_SIZE_2048_BYTES,
            SDU_SIZE_4096_BYTES,
            SDU_SIZE_8192_BYTES,
            SDU_SIZE_16384_BYTES,
            SDU_SIZE_32768_BYTES,
    })
    @interface SduSizeIndex {}

    /** Encoded SDU size index representing 64 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_64_BYTES = 0;

    /** Encoded SDU size index representing 128 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_128_BYTES = 1;

    /** Encoded SDU size index representing 192 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_192_BYTES = 2;

    /** Encoded SDU size index representing 256 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_256_BYTES = 3;

    /** Encoded SDU size index representing 384 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_384_BYTES = 4;

    /** Encoded SDU size index representing 512 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_512_BYTES = 5;

    /** Encoded SDU size index representing 760 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_760_BYTES = 6;

    /** Encoded SDU size index representing 1024 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_1024_BYTES = 7;

    /** Encoded SDU size index representing 1536 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_1536_BYTES = 8;

    /** Encoded SDU size index representing 2048 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_2048_BYTES = 9;

    /** Encoded SDU size index representing 4096 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_4096_BYTES = 10;

    /** Encoded SDU size index representing 8192 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_8192_BYTES = 11;

    /** Encoded SDU size index representing 16384 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_16384_BYTES = 12;

    /** Encoded SDU size index representing 32768 bytes. */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int SDU_SIZE_32768_BYTES = 13;

    /**
     * Reason for a Logical Link closure as indicated in Table 47.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
        LOGICAL_LINK_CLOSE_REASON_REMOTE,
        LOGICAL_LINK_CLOSE_REASON_TIMEOUT,
        LOGICAL_LINK_CLOSE_REASON_TRANSMISSION_ERROR,
        LOGICAL_LINK_CLOSE_REASON_SECURE_COMPONENT,
        LOGICAL_LINK_CLOSE_REASON_UNKNOWN,
        LOGICAL_LINK_CLOSE_REASON_HOST,
        LOGICAL_LINK_CLOSE_RX_SDU_TOO_LARGE,
    })
    @interface LogicalLinkClosureReason {}

    /**
     * The logical link was terminated by the remote device.
     */
    public static final int LOGICAL_LINK_CLOSE_REASON_REMOTE = 0x00;

    /**
     * The logical link was inactive for longer than the configured timeout period.
     */
    public static final int LOGICAL_LINK_CLOSE_REASON_TIMEOUT = 0x01;

    /**
     * The logical link was terminated due to unrecoverable transmission errors.
     */
    public static final int LOGICAL_LINK_CLOSE_REASON_TRANSMISSION_ERROR = 0x02;

    /**
     * The logical link was terminated by the secure component.
     */
    public static final int LOGICAL_LINK_CLOSE_REASON_SECURE_COMPONENT = 0x03;

    /**
     * The logical link was terminated due to an unknown reason.
     */
    public static final int LOGICAL_LINK_CLOSE_REASON_UNKNOWN = 0x04;

    /**
     * The logical link was explicitly terminated by the host.
     */
    public static final int LOGICAL_LINK_CLOSE_REASON_HOST = 0x05;

    /**
     * The logical link was explicitly terminted due to received Service Data Unit exceeds max
     * Service Data Unit capabilities.
     */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final int LOGICAL_LINK_CLOSE_RX_SDU_TOO_LARGE = 0x06;


    /** Indicates that no specific Logical Link Connection ID is provided. */
    public static final int CONNECT_ID_UNSPECIFIED = -1;

    private LogicalLinkCreationParams(Builder builder) {
        mLinkLayerModeSelector = builder.mLinkLayerModeSelector;
        mDestinationAddress = builder.mDestinationAddress;
        mLogicalLinkClassLength = builder.mLogicalLinkClassLength;
        mMaxSduSizeValue = builder.mMaxSduSizeValue;
    }

    /**
     * Returns the selected Link Layer Mode for the logical link.
     *
     * @return The link layer mode selector {@link LinkLayerMode}
     */
    @LinkLayerMode
    public int getLinkLayerModeSelector() {
        return mLinkLayerModeSelector;
    }

    /**
     * Returns the destination MAC address for the logical link.
     *
     * <p>This address identifies the peer device involved in the logical link communication.</p>
     *
     * @return A byte array representing the destination MAC address. Must not be null.
     */
    @NonNull
    public byte[] getDestinationAddress() {
        return mDestinationAddress;
    }

    /**
    * Returns the logical link class length.
    * <p> In FiRa 3.0, this value represents the class length (in bytes) associated with the logical
    * link.</p>
    *
    * <p>In FiRa 4.0 and later, this value represents the length (in bytes) of the Max Service Data
    * Unit Size field for the logical link.</p>
    *
    * @return The logical link class length value.
    */
    public int getLogicalLinkClassLength() {
        return mLogicalLinkClassLength;
    }

    /**
     * Returns the logical link max Service Data Unit size.
     *
     * <p>This value represents the max Service Data Unit size associated with the logical link.</p>
     *
     * @return Maximum Service Data Unit(SDU) transceive size
     */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public int getMaxSduSizeValue() {
        return mMaxSduSizeValue;
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof LogicalLinkCreationParams) {
            LogicalLinkCreationParams other = (LogicalLinkCreationParams) obj;
            return mLinkLayerModeSelector == other.mLinkLayerModeSelector
                    && Arrays.equals(mDestinationAddress, other.mDestinationAddress)
                    && mLogicalLinkClassLength == other.mLogicalLinkClassLength
                    && mMaxSduSizeValue == other.mMaxSduSizeValue;
        }
        return false;
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mLinkLayerModeSelector, Arrays.hashCode(mDestinationAddress),
                mLogicalLinkClassLength, mMaxSduSizeValue);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    @Override
    public String toString() {
        return "LogicalLinkCreationParams{"
                + "linkLayerModeSelector=" + mLinkLayerModeSelector
                + ", destinationAddress=" + UwbAddress.fromBytes(mDestinationAddress)
                + ", logicalLinkClassLength=" + mLogicalLinkClassLength
                + ", maxSduSizeValue=" + mMaxSduSizeValue
                + '}';
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mLinkLayerModeSelector);
        dest.writeByteArray(mDestinationAddress);
        dest.writeInt(mLogicalLinkClassLength);
        dest.writeInt(mMaxSduSizeValue);
    }

    public static final @NonNull Creator<LogicalLinkCreationParams> CREATOR =
            new Creator<LogicalLinkCreationParams>() {
                @Override
                public LogicalLinkCreationParams createFromParcel(Parcel in) {
                    int linkLayerModeSelector = in.readInt();
                    UwbAddress destAddr = UwbAddress.fromBytes(in.createByteArray());
                    int logicalLinkClassLen = in.readInt();
                    int packedMaxSduSize = in.readInt();

                    int txSduSize = packedMaxSduSize & 0x0F;
                    int rxSduSize = (packedMaxSduSize >> 4) & 0x0F;

                    return new Builder(linkLayerModeSelector, destAddr)
                            .setLogicalLinkClassLength(logicalLinkClassLen)
                            .setMaxSduTransmitSize(txSduSize)
                            .setMaxSduReceiveSize(rxSduSize)
                            .build();
                }

                @Override
                public LogicalLinkCreationParams[] newArray(int size) {
                    return new LogicalLinkCreationParams[size];
                }
            };

    /**
     * Builder for {@link LogicalLinkCreationParams} object
    */
    public static final class Builder {
        @LinkLayerMode
        private int mLinkLayerModeSelector;
        private byte[] mDestinationAddress = new byte[UwbAddress.EXTENDED_ADDRESS_BYTE_LENGTH];
        private int mLogicalLinkClassLength = 0;
        private int mMaxSduSizeValue = 0;

        /**
         * Constructor for the {@link Builder} class.
         *
         * @param linkLayerModeSelector The mode selector value.
         * @param destinationAddress The destination address, must be a valid {@link UwbAddress}.
         */
        public Builder(@LinkLayerMode int linkLayerModeSelector,
                @NonNull UwbAddress destinationAddress) {
            mLinkLayerModeSelector = linkLayerModeSelector;
            byte[] addressBytes = destinationAddress.toBytes();

            if (addressBytes.length == UwbAddress.SHORT_ADDRESS_BYTE_LENGTH) {
                // Ensure octets 2-7 are set to 0x00 for short addresses
                byte[] extendedAddress = new byte[UwbAddress.EXTENDED_ADDRESS_BYTE_LENGTH];
                System.arraycopy(addressBytes, 0, extendedAddress, 0,
                        UwbAddress.SHORT_ADDRESS_BYTE_LENGTH);
                mDestinationAddress = extendedAddress;
            } else {
                mDestinationAddress = addressBytes;
            }
        }

        /**
         * Sets the Logical Link Class length.
         *
         * <p>This parameter is originally defined in FiRa 3.0 to represent the Logical Link Class
         * field length.
         *
         * <p><strong>FiRa 4.0 note:</strong> Starting from FiRa 4.0, this field additionally
         * represents the Logical Link Max Service Data Unit Size(SDU) field length.
         *
         * <p>Default value is 0 if not explicitly set.
         *
         * @param logicalLinkClassLength The length value to be applied according to the FiRa spec
         *                               version in use.
         * @return The {@link Builder} instance for method chaining.
         */
        @NonNull
        public Builder setLogicalLinkClassLength(int logicalLinkClassLength) {
            mLogicalLinkClassLength = logicalLinkClassLength;
            return this;
        }

        /**
         * Sets the logical link max Service Data Unit(SDU) transmit size.
         *
         * @param maxSduTransmitSize The logical link max Service Data Unit(SDU) transmit size.
         * @return The {@link Builder} instance for method chaining.
         */
        @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
        @NonNull
        public Builder setMaxSduTransmitSize(@SduSizeIndex int maxSduTransmitSize) {
            mMaxSduSizeValue = (mMaxSduSizeValue & 0xF0) | (maxSduTransmitSize & 0x0F);
            return this;
        }

        /**
         * Sets the logical link max Service Data Unit(SDU) receive size.
         *
         * @param maxSduReceiveSize The logical link max Service Data Unit(SDU) receive size.
         * @return The {@link Builder} instance for method chaining.
         */
        @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
        @NonNull
        public Builder setMaxSduReceiveSize(@SduSizeIndex int maxSduReceiveSize) {
            mMaxSduSizeValue = (mMaxSduSizeValue & 0x0F) | ((maxSduReceiveSize & 0x0F) << 4);
            return this;
        }

        @NonNull
        public LogicalLinkCreationParams build() {
            return new LogicalLinkCreationParams(this);
        }
    }
}

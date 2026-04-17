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
import android.uwb.LogicalLinkCreationParams.LinkLayerMode;
import android.uwb.LogicalLinkCreationParams.SduSizeIndex;

import androidx.annotation.IntRange;

import com.android.uwb.flags.Flags;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents the parameters for the LOGICAL_LINK_UWBS_CREATE_NTF, which is used
 * by a FiRa Controller to notify the Host about an incoming Logical Link connection
 * from a remote device.
 *
 * This object contains the Connect ID, Link Layer Mode, and source address of
 * the initiating remote controller.
 *
 * Implements {@link Parcelable} for IPC transactions.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_UWB_FIRA_3_0_25Q4)
public final class LogicalLinkConnectionRequest implements Parcelable {
    private final int mConnectId;
    @LogicalLinkCreationParams.LinkLayerMode
    private final int mLinkLayerModeSelector;
    private final byte[] mSourceAddress;
    private final int mMaxSduSizeLength;
    private final int mMaxSduSizeValue;

    private LogicalLinkConnectionRequest(Builder builder) {
        mConnectId = builder.mConnectId;
        mLinkLayerModeSelector = builder.mLinkLayerModeSelector;
        mSourceAddress = builder.mSourceAddress;
        mMaxSduSizeLength = builder.mMaxSduSizeLength;
        mMaxSduSizeValue = builder.mMaxSduSizeValue;
    }

    /**
     * Returns the Logical Link Connect ID associated with this request.
     *
     * @return Logical Link Connect ID.
     */
    public int getConnectId() {
        return mConnectId;
    }

    /**
     * Returns the Link Layer Mode Selector value for this connection request.
     * <p>
     * The value corresponds to a mode such as connection-oriented or connectionless,
     * secure or non-secure.
     *
     * @return Link Layer Mode Selector value.
     */
    @LinkLayerMode
    public int getLinkLayerModeSelector() {
        return mLinkLayerModeSelector;
    }

    /**
     * Returns the source address (UWB address) of the remote controller
     * initiating the logical link.
     *
     * @return A {@link UwbAddress} representing the source address of the remote device.
     */
    @NonNull
    public UwbAddress getSourceAddress() {
        return UwbAddress.fromBytes(mSourceAddress);
    }

    /**
     * Returns the length of max sdu size for the logical link created
     *
     * @return length of max sdu size field for logical link. If it is 0, ignore maxSduSizeValue.
     */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public int getMaxSduSizeLength() {
        return mMaxSduSizeLength;
    }

    /**
     * Returns the maximum Logical Link transmit Service Data Unit(SDU) size index.
     *
     * <p>The value is extracted from bits <b>0–3</b> of the mMaxSduSizeValue. The returned
     * value is an {@link SduSizeIndex} constant, which corresponds to a predefined SDU size in
     * <b>bytes</b> (for example, {@link #SDU_SIZE_512_BYTES} = 512 bytes).
     *
     * @return SDU size index for the maximum transmit SDU.
     * @throws IllegalStateException if the mMaxSduSizeValue parameter is not present.
     */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    @SduSizeIndex
    public int getMaxTransmitSduSize() {

        if (getMaxSduSizeLength() == 0) {
            throw new IllegalStateException("mMaxSduSizeValue is not present");
        }

        return mMaxSduSizeValue & 0x0F;
    }

    /**
     * Returns the maximum Logical Link receive Service Data Unit(SDU) size index.
     *
     * <p>The value is extracted from bits <b>4–7</b> of the mMaxSduSizeValue. The returned
     * value is an {@link SduSizeIndex} constant, which corresponds to a predefined SDU size in
     * <b>bytes</b> (for example, {@link #SDU_SIZE_512_BYTES} = 512 bytes).
     *
     * @return SDU size index for the maximum receive SDU.
     * @throws IllegalStateException if the mMaxSduSizeValue parameter is not present.
     */
    @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    @SduSizeIndex
    public int getMaxReceiveSduSize() {

        if (getMaxSduSizeLength() == 0) {
            throw new IllegalStateException("mMaxSduSizeValue is not present");
        }

        return (mMaxSduSizeValue >> 4) & 0x0F;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeByteArray(mSourceAddress);
        dest.writeInt(mConnectId);
        dest.writeInt(mLinkLayerModeSelector);
        dest.writeInt(mMaxSduSizeLength);
        dest.writeInt(mMaxSduSizeValue);
    }

    public static final @NonNull Creator<LogicalLinkConnectionRequest> CREATOR =
            new Creator<>() {
                @Override
                public LogicalLinkConnectionRequest createFromParcel(Parcel in) {
                    byte[] sourceAddress = in.createByteArray();
                    if (sourceAddress == null) {
                        throw new IllegalArgumentException("sourceAddress in parcel is null");
                    }
                    return new Builder(
                            in.readInt(),
                            in.readInt(),
                            UwbAddress.fromBytes(sourceAddress))
                            .setMaxSduSizeLength(in.readInt())
                            .setMaxSduSizeValue(in.readInt())
                            .build();
                }

                @Override
                public LogicalLinkConnectionRequest[] newArray(int size) {
                    return new LogicalLinkConnectionRequest[size];
                }
            };

    /**
     * Builder for {@link LogicalLinkConnectionRequest} object.
     */
    public static final class Builder {
        private byte[] mSourceAddress = new byte[UwbAddress.EXTENDED_ADDRESS_BYTE_LENGTH];
        private int mConnectId;
        @LinkLayerMode
        private int mLinkLayerModeSelector;
        private int mMaxSduSizeLength = 0;
        private int mMaxSduSizeValue = -1;

        /**
         *  Creates a new {@link Builder} for constructing a {@link LogicalLinkConnectionRequest}.
         *
         * @param connectId The Logical Link Connection ID associated with the request.
         * @param linkLayerModeSelector The link layer mode indicating the type and security of the
         *          logical link. Must be one of the values defined in {@link LinkLayerMode}.
         * @param sourceAddress The UWB source address of the remote controller initiating the
         *          logical link.
         */
        public Builder(int connectId, @LinkLayerMode int linkLayerModeSelector,
                       @NonNull UwbAddress sourceAddress) {
            this.mConnectId = connectId;
            this.mLinkLayerModeSelector = linkLayerModeSelector;
            this.mSourceAddress = sourceAddress.toBytes();
        }

        /**
         * Sets the length of the Max SDU Size field for the Logical Link.
         *
         * <p>This parameter is introduced as part of the FiRa 4.0 specification to support
         * negotiation of the maximum SDU size for Logical Link data transfer.</p>
         *
         * <p>If {@code maxSduSizeLength} is set to {@code 0}, the Max SDU Size value
         * (see {@link #setMaxSduSizeValue(int)}) will be ignored, indicating that the
         * parameter is not used.</p>
         *
         * @param maxSduSizeLength the length of the Max SDU Size field as defined by FiRa 4.0.
         * @return this {@link Builder} instance.
         */
        @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
        @NonNull
        public Builder setMaxSduSizeLength(@IntRange(from = 0) int maxSduSizeLength) {
            this.mMaxSduSizeLength = maxSduSizeLength;
            return this;
        }

        /**
         * Sets the Max SDU Size value for the Logical Link.
         *
         * <p>This configuration is valid only when used with the FiRa 4.0 specification.
         * The value is applied only if {@code maxSduSizeLength} (see
         * {@link #setMaxSduSizeLength(int)}) is non-zero.</p>
         *
         * @param maxSduSizeValue the maximum Logical Link SDU size value, applicable for FiRa 4.0.
         * @return this {@link Builder} instance.
         */
        @FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
        @NonNull
        public Builder setMaxSduSizeValue(@IntRange(from = 0) int maxSduSizeValue) {
            this.mMaxSduSizeValue = maxSduSizeValue;
            return this;
        }

        /**
         * Builds the {@link LogicalLinkConnectionRequest} instance.
         */
        @NonNull
        public LogicalLinkConnectionRequest build() {
            return new LogicalLinkConnectionRequest(this);
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LogicalLinkConnectionRequest)) return false;
        LogicalLinkConnectionRequest other = (LogicalLinkConnectionRequest) obj;
        return mConnectId == other.mConnectId
                && mLinkLayerModeSelector == other.mLinkLayerModeSelector
                && Arrays.equals(mSourceAddress, other.mSourceAddress)
                && mMaxSduSizeLength == other.mMaxSduSizeLength
                && mMaxSduSizeValue == other.mMaxSduSizeValue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(mSourceAddress), mConnectId, mLinkLayerModeSelector,
                mMaxSduSizeLength, mMaxSduSizeValue);
    }

    @Override
    public String toString() {
        return "LogicalLinkConnectionRequest{"
                + "sourceAddress=" + UwbAddress.fromBytes(mSourceAddress)
                + ", connectId=" + mConnectId
                + ", linkLayerModeSelector=" + mLinkLayerModeSelector
                + ", maxSduSizeLength=" + mMaxSduSizeLength
                + ", maxSduSizeValue=" + mMaxSduSizeValue
                + '}';
    }
}

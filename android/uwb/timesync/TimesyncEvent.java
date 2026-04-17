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

package android.uwb.timesync;

import android.annotation.DurationMicrosLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.bluetooth.BluetoothDevice.BluetoothAddress;
import android.os.Parcel;
import android.os.Parcelable;
import android.uwb.UwbManager.AddressType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
@SystemApi
@FlaggedApi(com.android.ranging.flags.Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class TimesyncEvent implements Parcelable {

    private String mMacAddress;
    private int mAddressType;
    @BleLmpEvent private int mEvent;
    @Direction private int mDirection;
    private long mUwbTimestamp;
    private long mDeviceTimeUncertainty;
    private int mMaxClockSkewPpm;
    private int mEventCounter;

    private TimesyncEvent(Builder builder) {
        mMacAddress = builder.mMacAddress;
        mAddressType = builder.mAddressType;
        mEvent = builder.mEvent;
        mDirection = builder.mDirection;
        mUwbTimestamp = builder.mUwbTimestamp;
        mDeviceTimeUncertainty = builder.mDeviceTimeUncertainty;
        mMaxClockSkewPpm = builder.mMaxClockSkewPpm;
        mEventCounter = builder.mEventCounter;
    }

    /**
     * LMP event id to be monitored BLE_LMP_EVENT_CONNECT_IND indicator for initiating connection,
     * timestamp will be at the anchor point BLE_LMP_EVENT_LL_PHY_UPDATE_IND indicator for PHY
     * update.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({BLE_LMP_EVENT_CONNECT_IND, BLE_LMP_EVENT_LL_PHY_UPDATE_IND})
    private @interface BleLmpEvent {}
    public static final int BLE_LMP_EVENT_CONNECT_IND = 0;
    public static final int BLE_LMP_EVENT_LL_PHY_UPDATE_IND = 1;

    /** Direction of the LMP event */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DIRECTION_RX, DIRECTION_TX})
    private @interface Direction {}
    public static final int DIRECTION_RX = 0;
    public static final int DIRECTION_TX = 1;

    /**
     * Returns the {@link BluetoothAddress#getAddress()} from the client device.
     *
     * @return bluetooth MAC address
     */
    @NonNull public String getMacAddress() {
        return mMacAddress;
    }

    /**
     * Returns the {@link BluetoothAddress#getAddressType()} address type from the client device.
     *
     * @return {@link AddressType}
     */
    @AddressType public int getAddressType() {
        return mAddressType;
    }

    /**
     * Returns the type of BleLmp event.
     *
     * @return ble lmp event {@link BleLmpEvent}
     */
    @BleLmpEvent public int getEvent() {
        return mEvent;
    }

    /**
     * Returns direction of the event either
     * {@link TimesyncEvent#DIRECTION_RX} or {@link TimesyncEvent#DIRECTION_TX})
     *
     * @return direction
     */
    @Direction public int getDirection() {
        return mDirection;
    }

    /**
     * Returns the timestamp of the event in the UWBS time domain.
     *
     * @return uwb timestamp in microseconds.
     */
    public @ElapsedRealtimeLong long getUwbTimestampUs() {
        return mUwbTimestamp;
    }

    /**
     * Returns the uncertainty of the device time.
     *
     * @return device time uncertainty in microseconds.
     */
    public @DurationMicrosLong long getDeviceTimeUncertaintyUs() {
        return mDeviceTimeUncertainty;
    }

    /**
     * Returns the maximum clock skew between the devices in parts per million (PPM).
     *
     * @return max clock skew
     */
    public int getMaxClockSkewPpm() {
        return mMaxClockSkewPpm;
    }

    /**
     * Returns the Bluetooth Low Energy (BLE) event counter, a 16-bit value that increments for each
     * connection event since the connection was established. This is used for accurately
     * correlating time synchronization data between the local device and peer device.
     *
     * @return 16-bit event counter, ranging from 0 to 65535
     */
    @IntRange(from = 0, to = 65535)
    public int getEventCounter() {
        return mEventCounter;
    }

    @NonNull
    public static final Creator<TimesyncEvent> CREATOR =
            new Creator<TimesyncEvent>() {
                @Override
                public TimesyncEvent createFromParcel(Parcel in) {
                    Builder builder = new Builder(
                            in.readString(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readLong(),
                            in.readLong(),
                            in.readInt(),
                            in.readInt());

                    return builder.build();
                }

                @Override
                public TimesyncEvent[] newArray(int size) {
                    return new TimesyncEvent[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mMacAddress);
        dest.writeInt(mAddressType);
        dest.writeInt(mEvent);
        dest.writeInt(mDirection);
        dest.writeLong(mUwbTimestamp);
        dest.writeLong(mDeviceTimeUncertainty);
        dest.writeInt(mMaxClockSkewPpm);
        dest.writeInt(mEventCounter);
    }

    public int describeContents() {
        return 0;
    }
    /**
     * @hide
     */
    @Hide
    public static final class Builder {
        private String mMacAddress;
        private int mAddressType;
        @BleLmpEvent private int mEvent;
        @Direction private int mDirection;
        private long mUwbTimestamp;
        private long mDeviceTimeUncertainty;
        private int mMaxClockSkewPpm;
        private int mEventCounter;

        public Builder(
                String macAddress,
                int addressType,
                @BleLmpEvent int event,
                @Direction int direction,
                long uwbTimestamp,
                long deviceTimeUncertainty,
                int maxClockSkewPpm,
                int eventCounter) {
            mMacAddress = macAddress;
            mAddressType = addressType;
            mEvent = event;
            mDirection = direction;
            mUwbTimestamp = uwbTimestamp;
            mDeviceTimeUncertainty = deviceTimeUncertainty;
            mMaxClockSkewPpm = maxClockSkewPpm;
            mEventCounter = eventCounter;
        }

        @NonNull
        public TimesyncEvent build() {
            return new TimesyncEvent(this);
        }
    }
}

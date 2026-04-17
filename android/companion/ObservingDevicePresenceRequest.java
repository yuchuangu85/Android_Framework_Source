/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.companion;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A request for setting the types of device for observing device presence.
 *
 * <p>Only supports association id or ParcelUuid and calling app must declare uses-permission
 * {@link android.Manifest.permission#REQUEST_OBSERVE_DEVICE_UUID_PRESENCE} if using
 * {@link Builder#setUuid(ParcelUuid)}.</p>
 *
 * Calling apps must use either ObservingDevicePresenceRequest.Builder#setUuid(ParcelUuid) or
 * ObservingDevicePresenceRequest.Builder#setAssociationId(int), but not both.
 *
 * @see Builder#setUuid(ParcelUuid)
 * @see Builder#setAssociationId(int)
 * @see CompanionDeviceManager#startObservingDevicePresence(ObservingDevicePresenceRequest)
 */
// TODO(b/371198526): Update the javadoc once the 25Q4 is released.
public final class ObservingDevicePresenceRequest implements Parcelable {
    private final int mAssociationId;
    @Nullable private final ParcelUuid mUuid;

    @Nullable private final DeviceId mDeviceId;

    private static final int PARCEL_UUID_NULL = 0;

    private static final int PARCEL_UUID_NOT_NULL = 1;

    private ObservingDevicePresenceRequest(Builder builder) {
        mAssociationId = builder.mAssociationId;
        mUuid = builder.mUuid;
        mDeviceId = builder.mDeviceId;
    }

    private ObservingDevicePresenceRequest(@NonNull Parcel in) {
        mAssociationId = in.readInt();
        if (in.readInt() == PARCEL_UUID_NULL) {
            mUuid = null;
        } else {
            mUuid = ParcelUuid.CREATOR.createFromParcel(in);
        }

        if (in.readInt() == 1 && Flags.associationVerification()) {
            mDeviceId = DeviceId.CREATOR.createFromParcel(in);
        } else {
            mDeviceId = null;
        }
    }

    /**
     * @return the association id for observing device presence. It will return
     * {@link DevicePresenceEvent#NO_ASSOCIATION} if using
     * {@link Builder#setUuid(ParcelUuid)}.
     */
    public int getAssociationId() {
        return mAssociationId;
    }

    /**
     * @return the ParcelUuid for observing device presence.
     */
    @Nullable
    public ParcelUuid getUuid() {
        return mUuid;
    }

    /**
     * @return the device id for observing device presence.
     * @hide
     */
    @Nullable
    @SystemApi
    @FlaggedApi(Flags.FLAG_ASSOCIATION_VERIFICATION)
    public DeviceId getDeviceId() {
        return mDeviceId;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAssociationId);
        if (mUuid == null) {
            // Write 0 to the parcel to indicate the ParcelUuid is null.
            dest.writeInt(PARCEL_UUID_NULL);
        } else {
            dest.writeInt(PARCEL_UUID_NOT_NULL);
            mUuid.writeToParcel(dest, flags);
        }

        if (Flags.associationVerification() && mDeviceId != null) {
            dest.writeInt(1);
            mDeviceId.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<ObservingDevicePresenceRequest> CREATOR =
            new Parcelable.Creator<ObservingDevicePresenceRequest>() {
                @Override
                public ObservingDevicePresenceRequest[] newArray(int size) {
                    return new ObservingDevicePresenceRequest[size];
                }

                @Override
                public ObservingDevicePresenceRequest createFromParcel(@NonNull Parcel in) {
                    return new ObservingDevicePresenceRequest(in);
                }
            };

    @Override
    public String toString() {
        return "ObservingDevicePresenceRequest { "
                + "Association Id= " + mAssociationId + ","
                + "ParcelUuid= " + mUuid + ","
                + "Device id= " + mDeviceId + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof ObservingDevicePresenceRequest that)) return false;

        if (Flags.associationVerification()) {
            return Objects.equals(mUuid, that.mUuid)
                    && mAssociationId == that.mAssociationId
                    && Objects.equals(mDeviceId, that.mDeviceId);
        }

        return Objects.equals(mUuid, that.mUuid)
                && mAssociationId == that.mAssociationId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAssociationId, mUuid);
    }

    /**
     * A builder for {@link ObservingDevicePresenceRequest}
     */
    public static final class Builder {
        // Initial the association id to {@link DevicePresenceEvent.NO_ASSOCIATION}
        // to indicate the value is not set yet.
        private int mAssociationId = DevicePresenceEvent.NO_ASSOCIATION;
        private ParcelUuid mUuid;
        private DeviceId mDeviceId;

        public Builder() {}

        /**
         * Set the association id to be observed for device presence.
         *
         * <p>The provided device must be {@link CompanionDeviceManager#associate associated}
         * with the calling app before calling this method if using this API.
         *
         * Caller must implement a single {@link CompanionDeviceService} which will be bound to and
         * receive callbacks to
         * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)}.</p>
         *
         * <p>Calling apps must use either {@link #setUuid(ParcelUuid)}
         * or this API, but not both.</p>
         *
         * @param associationId The association id for observing device presence.
         */
        @NonNull
        public Builder setAssociationId(int associationId) {
            mAssociationId = associationId;
            return this;
        }

        /**
         * Set the ParcelUuid to be observed for device presence.
         *
         * <p>It does not require to create the association before calling this API.
         * This only supports classic Bluetooth scan and caller must implement
         * a single {@link CompanionDeviceService} which will be bound to and receive callbacks to
         * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)}.</p>
         *
         * <p>The Uuid should be matching one of the ParcelUuid form
         * {@link android.bluetooth.BluetoothDevice#getUuids()}</p>
         *
         * <p>Calling apps must use either this API or {@link #setAssociationId(int)},
         * but not both.</p>
         *
         * <p>Calling app must hold the
         * {@link AssociationRequest#DEVICE_PROFILE_AUTOMOTIVE_PROJECTION} profile.</p>
         *
         * @param uuid The ParcelUuid for observing device presence.
         */
        @NonNull
        @RequiresPermission(allOf = {
                android.Manifest.permission.REQUEST_OBSERVE_DEVICE_UUID_PRESENCE,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
        })
        public Builder setUuid(@NonNull ParcelUuid uuid) {
            mUuid = uuid;
            return this;
        }

        /**
         * Sets the device id for observing device presence events.
         *
         * <p>It allows the requester app to observe device presence of the devices managed by other
         * apps, but this requires to the requester app to obtain the device id with the 128 bit key
         * from the managing app.
         *
         * @param deviceId A device id represents a device identifier managed by the companion app.
         * @see AssociationInfo#getDeviceId()
         * @see AssociationInfo#getId()
         * @see CompanionDeviceManager#createAndSetDeviceId(int, DeviceId)
         * @hide
         */
        @NonNull
        @SystemApi
        @RequiresPermission(android.Manifest.permission.ACCESS_COMPANION_INFO)
        @FlaggedApi(Flags.FLAG_ASSOCIATION_VERIFICATION)
        public Builder setDeviceId(@NonNull DeviceId deviceId) {
            mDeviceId = deviceId;
            return this;
        }

        @NonNull
        public ObservingDevicePresenceRequest build() {
            int providedCount = 0;
            if (mDeviceId != null) {
                providedCount++;
            }
            if (mUuid != null) {
                providedCount++;
            }
            if (mAssociationId != DevicePresenceEvent.NO_ASSOCIATION) {
                providedCount++;
            }

            if (providedCount != 1) {
                throw new IllegalStateException("Must provide exactly one of device id, "
                        + "parcel uuid, or association id to observe device presence.");
            }

            return new ObservingDevicePresenceRequest(this);
        }
    }
}

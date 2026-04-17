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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Event for observing device presence.
 *
 * @see CompanionDeviceManager#startObservingDevicePresence(ObservingDevicePresenceRequest)
 * @see ObservingDevicePresenceRequest.Builder#setUuid(ParcelUuid)
 * @see ObservingDevicePresenceRequest.Builder#setAssociationId(int)
 */
public final class DevicePresenceEvent implements Parcelable {

    /** @hide */
    @IntDef(prefix = {"EVENT"}, value = {
            EVENT_BLE_APPEARED,
            EVENT_BLE_DISAPPEARED,
            EVENT_BT_CONNECTED,
            EVENT_BT_DISCONNECTED,
            EVENT_SELF_MANAGED_APPEARED,
            EVENT_SELF_MANAGED_DISAPPEARED,
            EVENT_ASSOCIATION_REMOVED,
            EVENT_SELF_MANAGED_NEARBY,
            EVENT_SELF_MANAGED_NOT_NEARBY
    })

    @Retention(RetentionPolicy.SOURCE)
    public @interface Event {}

    /**
     * Indicate observing device presence base on the ParcelUuid but not association id.
     */
    public static final int NO_ASSOCIATION = -1;

    /**
     * Companion app receives
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} callback
     * with this event if the device comes into BLE range.
     */
    public static final int EVENT_BLE_APPEARED = 0;

    /**
     * Companion app receives
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} callback
     * with this event if the device is no longer in BLE range.
     */
    public static final int EVENT_BLE_DISAPPEARED = 1;

    /**
     * Companion app receives
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} callback
     * with this event when the bluetooth device is connected.
     */
    public static final int EVENT_BT_CONNECTED = 2;

    /**
     * Companion app receives
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} callback
     * with this event if the bluetooth device is disconnected.
     */
    public static final int EVENT_BT_DISCONNECTED = 3;

    /**
     * A companion app for a self-managed device will receive the callback
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)}
     * if it reports that a device has appeared on its
     * own.
     */
    public static final int EVENT_SELF_MANAGED_APPEARED = 4;

    /**
     * A companion app for a self-managed device will receive the callback
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)} if it reports
     * that a device has disappeared on its own.
     */
    public static final int EVENT_SELF_MANAGED_DISAPPEARED = 5;

    /**
     * A companion app will receives the callback
     * {@link CompanionDeviceService#onDevicePresenceEvent(DevicePresenceEvent)}
     * with this event when the {@link AssociationInfo} is removed.
     */
    @FlaggedApi(Flags.FLAG_NOTIFY_ASSOCIATION_REMOVED)
    public static final int EVENT_ASSOCIATION_REMOVED = 6;

    /**
     * Event reported by a self-managed companion app to indicate the device is nearby.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
    public static final int EVENT_SELF_MANAGED_NEARBY = 7;

    /**
     * Event reported by a self-managed companion app to indicate the device is not nearby.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
    public static final int EVENT_SELF_MANAGED_NOT_NEARBY = 8;

    private final int mAssociationId;
    private final int mEvent;
    @Nullable
    private final ParcelUuid mUuid;

    private static final int PARCEL_UUID_NULL = 0;

    private static final int PARCEL_UUID_NOT_NULL = 1;

    /**
     * Create a new DevicePresenceEvent.
     * @deprecated Third party apps should not construct this API.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
    @Deprecated
    public DevicePresenceEvent(
            int associationId, @Event int event, @Nullable ParcelUuid uuid) {
        mAssociationId = associationId;
        mEvent = event;
        mUuid = uuid;
    }

    private DevicePresenceEvent(Builder builder) {
        mAssociationId = builder.mAssociationId != null ? builder.mAssociationId : NO_ASSOCIATION;
        mEvent = builder.mEvent;
        mUuid = builder.mUuid;
    }

    /**
     * @return The association id has been used to observe device presence.
     *
     * Caller will receive the valid association id if only if using
     * {@link ObservingDevicePresenceRequest.Builder#setAssociationId(int)}, otherwise
     * return {@link #NO_ASSOCIATION}.
     *
     * @see ObservingDevicePresenceRequest.Builder#setAssociationId(int)
     */
    public int getAssociationId() {
        return mAssociationId;
    }

    /**
     * @return Associated companion device's event.
     */
    public int getEvent() {
        return mEvent;
    }

    /**
     * @return The ParcelUuid has been used to observe device presence.
     *
     * Caller will receive the ParcelUuid if only if using
     * {@link ObservingDevicePresenceRequest.Builder#setUuid(ParcelUuid)}, otherwise return null.
     *
     * @see ObservingDevicePresenceRequest.Builder#setUuid(ParcelUuid)
     */

    @Nullable
    public ParcelUuid getUuid() {
        return mUuid;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAssociationId);
        dest.writeInt(mEvent);
        if (mUuid == null) {
            // Write 0 to the parcel to indicate the ParcelUuid is null.
            dest.writeInt(PARCEL_UUID_NULL);
        } else {
            dest.writeInt(PARCEL_UUID_NOT_NULL);
            mUuid.writeToParcel(dest, flags);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof DevicePresenceEvent that)) return false;

        return Objects.equals(mUuid, that.mUuid)
                && mAssociationId == that.mAssociationId
                && mEvent == that.mEvent;
    }

    @Override
    public String toString() {
        return "DevicePresenceEvent { "
                + "Association Id= " + mAssociationId + ","
                + "ParcelUuid= " + mUuid + ","
                + "Event= " + mEvent + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAssociationId, mEvent, mUuid);
    }

    @NonNull
    public static final Parcelable.Creator<DevicePresenceEvent> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public DevicePresenceEvent[] newArray(int size) {
                    return new DevicePresenceEvent[size];
                }

                @Override
                public DevicePresenceEvent createFromParcel(@NonNull Parcel in) {
                    return new DevicePresenceEvent(in);
                }
            };

    private DevicePresenceEvent(@NonNull Parcel in) {
        mAssociationId = in.readInt();
        mEvent = in.readInt();
        if (in.readInt() == PARCEL_UUID_NULL) {
            mUuid = null;
        } else {
            mUuid = ParcelUuid.CREATOR.createFromParcel(in);
        }
    }

    /**
     * Builder for creating a {@link DevicePresenceEvent}.
     * <p>
     * An event must be identified by either an association ID or a UUID, but not both.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
    @SystemApi
    public static final class Builder {
        private Integer mAssociationId;
        private Integer mEvent;
        private ParcelUuid mUuid;

        /**
         * Creates a new Builder.
         * <p>
         * The event type must be set via {@link #setEvent(int)}, and the event must be identified
         * by setting either {@link #setAssociationId(int)} or {@link #setUuid(ParcelUuid)} before
         * calling {@link #build()}.
         */
        public Builder() {}

        /**
         * Set the association ID for this event.
         * <p>
         * An event cannot be identified by both an association ID and a UUID.
         *
         * @param associationId The association ID.
         * @return This builder.
         */
        @NonNull
        public Builder setAssociationId(int associationId) {
            this.mAssociationId = associationId;
            return this;
        }

        /**
         * Sets the type of presence event that occurred.
         *
         * @param event The type of presence event.
         * @return This builder.
         */
        @NonNull
        public Builder setEvent(@Event int event) {
            this.mEvent = event;
            return this;
        }

        /**
         * Sets the ParcelUuid to identify the device for this event.
         * <p>
         * An event cannot be identified by both an association ID and a UUID.
         *
         * @param uuid The ParcelUuid to set.
         * @return This builder.
         */
        @NonNull
        public Builder setUuid(@NonNull ParcelUuid uuid) {
            this.mUuid = uuid;
            return this;
        }

        /**
         * Builds the {@link DevicePresenceEvent} object.
         *
         * @return The constructed {@link DevicePresenceEvent}.
         * @throws IllegalStateException if required fields are not set or if conflicting
         *                               identifiers (association ID and UUID) are provided.
         */
        @NonNull
        public DevicePresenceEvent build() {
            if (mEvent == null) {
                throw new IllegalStateException("Event must be set.");
            }

            final boolean hasAssociationId = mAssociationId != null;
            final boolean hasUuid = mUuid != null;

            // Throw if both identifiers are set or if neither is set.
            if ((hasAssociationId && hasUuid) || (!hasAssociationId && !hasUuid)) {
                throw new IllegalStateException(
                        "Exactly one of associationId or UUID must be set.");
            }

            return new DevicePresenceEvent(this);
        }
    }
}

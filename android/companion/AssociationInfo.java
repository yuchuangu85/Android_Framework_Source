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
package android.companion;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.companion.CompanionDeviceManager.FeatureName;
import android.graphics.drawable.Icon;
import android.net.MacAddress;
import android.os.BaseBundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Details for a specific "association" that has been established between an app and companion
 * device.
 * <p>
 * An association gives an app the ability to interact with a companion device without needing to
 * acquire broader runtime permissions. An association only exists after the user has confirmed that
 * an app should have access to a companion device.
 */
public final class AssociationInfo implements Parcelable {
    /**
     * A String indicates the selfManaged device is not connected.
     */
    private static final String LAST_TIME_CONNECTED_NONE = "None";

    /**
     * Key for the reception timestamp of the metadata.
     * @hide
     */
    public static final String METADATA_TIMESTAMP = "_timestamp_";

    /**
     * A unique ID of this Association record.
     * Disclosed to the clients (i.e. companion applications) for referring to this record (e.g. in
     * {@code disassociate()} API call).
     */
    private final int mId;
    @UserIdInt
    private final int mUserId;
    @NonNull
    private final String mPackageName;
    @Nullable
    private final MacAddress mDeviceMacAddress;
    @Nullable
    private final CharSequence mDisplayName;
    @Nullable
    private final String mDeviceProfile;
    @Nullable
    private final AssociatedDevice mAssociatedDevice;
    private final boolean mSelfManaged;
    private final boolean mNotifyOnDeviceNearby;
    /**
     * Indicates that the association has been revoked (removed), but we keep the association
     * record for final clean up (e.g. removing the app from the list of the role holders).
     *
     * @see CompanionDeviceManager#disassociate(int)
     */
    private final boolean mRevoked;
    /**
     * Indicates that the association is waiting for its corresponding companion app to be installed
     * before it can be added to CDM. This is likely because it was restored onto the device from a
     * backup.
     */
    private final boolean mPending;
    /**
     * Indicates that the association has been verified as a trusted device.
     */
    private final boolean mTrusted;
    private final long mTimeApprovedMs;
    /**
     * A long value indicates the last time connected reported by selfManaged devices
     * Default value is Long.MAX_VALUE.
     */
    private final long mLastTimeConnectedMs;
    private final int mSystemDataSyncFlags;
    private final int mTransportFlags;
    @Nullable
    private final DeviceId mDeviceId;
    @Nullable
    private final List<String> mPackagesToNotify;
    /**
     * A map of metadata describing the device's data sync policies for each feature client.
     */
    @NonNull
    private final PersistableBundle mMetadata;
    private final long mTimeMetadataSentMs;

    /**
     * A device icon displayed on a selfManaged association dialog.
     */
    private final Icon mDeviceIcon;

    /**
     * The set of extra permissions requested by the application during the
     * association request.
     */
    @NonNull
    private final Set<String> mExtraPermissions;

    private final boolean mRemoteAiAgentSupported;

    /**
     * Creates a new Association.
     *
     * @hide
     */
    private AssociationInfo(Builder builder) {
        if (builder.mId <= 0) {
            throw new IllegalArgumentException("Association ID should be greater than 0");
        }
        if (builder.mDeviceMacAddress == null && builder.mDisplayName == null) {
            throw new IllegalArgumentException("MAC address and the Display Name must NOT be null "
                    + "at the same time");
        }

        mId = builder.mId;
        mUserId = builder.mUserId;
        mPackageName = builder.mPackageName;
        mDeviceMacAddress = builder.mDeviceMacAddress;
        mDisplayName = builder.mDisplayName;
        mDeviceProfile = builder.mDeviceProfile;
        mAssociatedDevice = builder.mAssociatedDevice;
        mSelfManaged = builder.mSelfManaged;
        mNotifyOnDeviceNearby = builder.mNotifyOnDeviceNearby;
        mRevoked = builder.mRevoked;
        mPending = builder.mPending;
        mTrusted = builder.mTrusted;
        mTimeApprovedMs = builder.mTimeApprovedMs;
        mLastTimeConnectedMs = builder.mLastTimeConnectedMs;
        mSystemDataSyncFlags = builder.mSystemDataSyncFlags;
        mTransportFlags = builder.mTransportFlags;
        mDeviceIcon = builder.mDeviceIcon;
        mDeviceId = builder.mDeviceId;
        mPackagesToNotify = builder.mPackagesToNotify;
        mMetadata = builder.mMetadata;
        mTimeMetadataSentMs = builder.mTimeMetadataSentMs;
        mExtraPermissions = builder.mExtraPermissions;
        mRemoteAiAgentSupported = builder.mRemoteAiAgentSupported;
    }

    /**
     * @return the unique ID of this association record.
     */
    public int getId() {
        return mId;
    }

    /**
     * @return the ID of the user who "owns" this association.
     * @hide
     */
    @UserIdInt
    public int getUserId() {
        return mUserId;
    }

    /**
     * @return the package name of the app which this association refers to.
     * @hide
     */
    @SystemApi
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * @return the {@link DeviceId} of this association.
     * @see CompanionDeviceManager#setDeviceId(int, DeviceId)
     */
    @Nullable
    public DeviceId getDeviceId() {
        return mDeviceId;
    }

    /**
     * @return the MAC address of the device.
     */
    @Nullable
    public MacAddress getDeviceMacAddress() {
        return mDeviceMacAddress;
    }

    /** @hide */
    @Nullable
    public String getDeviceMacAddressAsString() {
        return mDeviceMacAddress != null ? mDeviceMacAddress.toString().toUpperCase() : null;
    }

    /**
     * @return the display name of the companion device (optionally) provided by the companion
     * application.
     * @see AssociationRequest.Builder#setDisplayName(CharSequence)
     */
    @Nullable
    public CharSequence getDisplayName() {
        return mDisplayName;
    }

    /**
     * @return the companion device profile used when establishing this
     * association, or {@code null} if no specific profile was used.
     * @see AssociationRequest.Builder#setDeviceProfile(String)
     */
    @Nullable
    public String getDeviceProfile() {
        return mDeviceProfile;
    }

    /**
     * Companion device that was associated. Note that this field is not persisted across sessions.
     * Device can be one of the following types:
     *
     * <ul>
     *     <li>for classic Bluetooth - {@link AssociatedDevice#getBluetoothDevice()}</li>
     *     <li>for Bluetooth LE - {@link AssociatedDevice#getBleDevice()}</li>
     *     <li>for WiFi - {@link AssociatedDevice#getWifiDevice()}</li>
     * </ul>
     *
     * @return the companion device that was associated, or {@code null} if the device is
     * self-managed or this association info was retrieved from persistent storage.
     */
    @Nullable
    public AssociatedDevice getAssociatedDevice() {
        return mAssociatedDevice;
    }

    /**
     * @return whether the association is managed by the companion application it belongs to.
     * @see AssociationRequest.Builder#setSelfManaged(boolean)
     */
    @SuppressLint("UnflaggedApi") // promoting from @SystemApi
    public boolean isSelfManaged() {
        return mSelfManaged;
    }

    /** @hide */
    public boolean isNotifyOnDeviceNearby() {
        return mNotifyOnDeviceNearby;
    }

    /** @hide */
    public long getTimeApprovedMs() {
        return mTimeApprovedMs;
    }

    /** @hide */
    public boolean belongsToPackage(@UserIdInt int userId, String packageName) {
        return mUserId == userId && Objects.equals(mPackageName, packageName);
    }

    /**
     * @return if the association has been revoked (removed).
     * @hide
     */
    public boolean isRevoked() {
        return mRevoked;
    }

    /**
     * @return true if the association is waiting for its corresponding app to be installed
     * before it can be added to CDM.
     * @hide
     */
    public boolean isPending() {
        return mPending;
    }

    /**
     * <p>Returns whether an association is a trusted device.</p>
     *
     * <p>A trusted device is a device that has been verified as user-owned via a combination of
     * successful secure connection establishment as well as an internal handshake involving a
     * direct user confirmation (e.g. Displayed PIN comparison).</p>
     *
     * @return True if the device had successfully verified its connection. False otherwise.
     * @hide
     */
    public boolean isTrusted() {
        return mTrusted;
    }

    /**
     * @return true if the association is not revoked nor pending
     * @hide
     */
    public boolean isActive() {
        return !mRevoked && !mPending;
    }

    /**
     * @return the last time self reported disconnected for selfManaged only.
     * @hide
     */
    public long getLastTimeConnectedMs() {
        return mLastTimeConnectedMs;
    }

    /**
     * @return Enabled system data sync flags set via
     * {@link CompanionDeviceManager#enableSystemDataSyncForTypes(int, int)} (int, int)} and
     * {@link CompanionDeviceManager#disableSystemDataSyncForTypes(int, int)} (int, int)}.
     */
    public int getSystemDataSyncFlags() {
        return mSystemDataSyncFlags;
    }

    /**
     * @return Flags to be used when attaching a new transport for this association.
     * @hide
     */
    public int getTransportFlags() {
        return mTransportFlags;
    }

    /**
     * @return A non-null, possibly empty, set of extra permissions.
     */
    @FlaggedApi(Flags.FLAG_ASSOCIATION_EXTRA_PERMISSION)
    @NonNull
    public Set<String> getExtraPermissions() {
        return mExtraPermissions;
    }

    /**
     * Get the device icon of the associated device. The device icon represents the device type.
     *
     * @return the device icon with size 24dp x 24dp.
     * If the associated device has no icon set, it returns {@code null}.
     * @see AssociationRequest.Builder#setDeviceIcon(Icon)
     */
    @FlaggedApi(Flags.FLAG_ASSOCIATION_DEVICE_ICON)
    @Nullable
    public Icon getDeviceIcon() {
        return mDeviceIcon;
    }

    /**
     * @return a list of packages that need to notify for device presence.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_ASSOCIATION_VERIFICATION)
    @Nullable
    public List<String> getPackagesToNotify() {
        return mPackagesToNotify;
    }

    /**
     * @return the metadata of the association.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
    @NonNull
    public PersistableBundle getMetadata() {
        return mMetadata;
    }

    /**
     * @return the metadata of the association for a given feature name.
     * If the metadata is not available, it returns a new empty bundle.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
    @NonNull
    public PersistableBundle getMetadata(@NonNull @FeatureName String feature) {
        if (METADATA_TIMESTAMP.equals(feature)) {
            throw new IllegalArgumentException("Cannot get metadata for timestamp. "
                    + "Use getMetadataTimestamp() instead to get the timestamp.");
        }

        PersistableBundle bundle = mMetadata.getPersistableBundle(feature);
        if (bundle == null) {
            return new PersistableBundle();
        }
        return bundle;
    }

    /**
     * @return the timestamp at which the metadata was last received from the remote device.
     * If the metadata was never set, then it returns 0.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
    public long getMetadataTimestamp() {
        return mMetadata.getLong(METADATA_TIMESTAMP, 0L);
    }

    /**
     * @return the timestamp at which the local metadata was last sent to the remote device.
     * If the local metadata was never sent, then it returns 0.
     *
     * The metadata that was sent is not necessarily the same as the current local metadata. The
     * metadata can be updated without sync if transports are not available.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
    public long getMetadataSentTimestamp() {
        return mTimeMetadataSentMs;
    }

    /**
     * @see AssociationRequest.Builder#setRemoteAiAgentSupported(boolean)
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_AI_AGENT)
    public boolean isRemoteAiAgentSupported() {
        return mRemoteAiAgentSupported;
    }

    /**
     * Utility method for checking if the association represents a device with the given MAC
     * address.
     *
     * @return {@code false} if the association is "self-managed".
     * {@code false} if the {@code addr} is {@code null} or is not a valid MAC address.
     * Otherwise - the result of {@link MacAddress#equals(Object)}
     * @hide
     */
    public boolean isLinkedTo(@Nullable String addr) {
        if (mSelfManaged) return false;

        if (addr == null) return false;

        final MacAddress macAddress;
        try {
            macAddress = MacAddress.fromString(addr);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return macAddress.equals(mDeviceMacAddress);
    }

    /**
     * Utility method to be used by CdmService only.
     *
     * @return whether CdmService should bind the companion application that "owns" this association
     * when the device is present.
     * @hide
     */
    public boolean shouldBindWhenPresent() {
        return mNotifyOnDeviceNearby || mSelfManaged;
    }

    /** @hide */
    @NonNull
    public String toShortString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("id=").append(mId);
        if (mDeviceMacAddress != null) {
            sb.append(", addr=").append(getDeviceMacAddressAsString());
        }
        if (mSelfManaged) {
            sb.append(", self-managed");
        }
        sb.append(", pkg=u").append(mUserId).append('/').append(mPackageName);
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Association{"
                + "mId=" + mId
                + ", mUserId=" + mUserId
                + ", mPackageName='" + mPackageName + '\''
                + ", mDeviceMacAddress=" + mDeviceMacAddress
                + ", mDisplayName='" + mDisplayName + '\''
                + ", mDeviceProfile='" + mDeviceProfile + '\''
                + ", mSelfManaged=" + mSelfManaged
                + ", mAssociatedDevice=" + mAssociatedDevice
                + ", mNotifyOnDeviceNearby=" + mNotifyOnDeviceNearby
                + ", mRevoked=" + mRevoked
                + ", mPending=" + mPending
                + ", mTrusted=" + mTrusted
                + ", mTimeApprovedMs=" + new Date(mTimeApprovedMs)
                + ", mLastTimeConnectedMs=" + (
                mLastTimeConnectedMs == Long.MAX_VALUE
                        ? LAST_TIME_CONNECTED_NONE : new Date(mLastTimeConnectedMs))
                + ", mSystemDataSyncFlags=" + mSystemDataSyncFlags
                + ", mTransportFlags=" + mTransportFlags
                + ", mDeviceId=" + mDeviceId
                + ", mPackagesToNotify=" + mPackagesToNotify
                + ", mMetadata=" + mMetadata
                + ", mTimeMetadataSentMs=" + new Date(mTimeMetadataSentMs)
                + ", mExtraPermissions=" + mExtraPermissions
                + ", mRemoteAiAgentSupported=" + mRemoteAiAgentSupported
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AssociationInfo)) return false;
        final AssociationInfo that = (AssociationInfo) o;

        return mId == that.mId
                && mUserId == that.mUserId
                && mSelfManaged == that.mSelfManaged
                && mNotifyOnDeviceNearby == that.mNotifyOnDeviceNearby
                && mRevoked == that.mRevoked
                && mPending == that.mPending
                && mTrusted == that.mTrusted
                && mTimeApprovedMs == that.mTimeApprovedMs
                && mLastTimeConnectedMs == that.mLastTimeConnectedMs
                && Objects.equals(mPackageName, that.mPackageName)
                && Objects.equals(mDeviceMacAddress, that.mDeviceMacAddress)
                && Objects.equals(mDisplayName, that.mDisplayName)
                && Objects.equals(mDeviceProfile, that.mDeviceProfile)
                && Objects.equals(mAssociatedDevice, that.mAssociatedDevice)
                && mSystemDataSyncFlags == that.mSystemDataSyncFlags
                && mTransportFlags == that.mTransportFlags
                && isSameIcon(mDeviceIcon, that.mDeviceIcon)
                && Objects.equals(mDeviceId, that.mDeviceId)
                && Objects.equals(mPackagesToNotify, that.mPackagesToNotify)
                && BaseBundle.kindofEquals(mMetadata, that.mMetadata)
                && mTimeMetadataSentMs == that.mTimeMetadataSentMs
                && Objects.equals(mExtraPermissions, that.mExtraPermissions)
                && mRemoteAiAgentSupported == that.mRemoteAiAgentSupported;
    }

    private boolean isSameIcon(Icon iconA, Icon iconB) {
        // Because we've already rescaled and converted both icons to bitmaps,
        // we can now directly compare them by bitmap.
        return (iconA == null && iconB == null)
                || (iconA != null && iconB != null && iconA.getBitmap().sameAs(iconB.getBitmap()));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mUserId, mPackageName, mDeviceMacAddress, mDisplayName,
                mDeviceProfile, mAssociatedDevice, mSelfManaged, mNotifyOnDeviceNearby, mRevoked,
                mPending, mTrusted, mTimeApprovedMs, mLastTimeConnectedMs, mSystemDataSyncFlags,
                mTransportFlags, mDeviceIcon, mDeviceId, mPackagesToNotify, mMetadata,
                mExtraPermissions, mRemoteAiAgentSupported);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mUserId);
        dest.writeString(mPackageName);
        dest.writeTypedObject(mDeviceMacAddress, 0);
        dest.writeCharSequence(mDisplayName);
        dest.writeString(mDeviceProfile);
        dest.writeTypedObject(mAssociatedDevice, 0);
        dest.writeBoolean(mSelfManaged);
        dest.writeBoolean(mNotifyOnDeviceNearby);
        dest.writeBoolean(mRevoked);
        dest.writeBoolean(mPending);
        dest.writeBoolean(mTrusted);
        dest.writeLong(mTimeApprovedMs);
        dest.writeLong(mLastTimeConnectedMs);
        dest.writeInt(mSystemDataSyncFlags);
        dest.writeInt(mTransportFlags);
        if (Flags.associationDeviceIcon() && mDeviceIcon != null) {
            dest.writeInt(1);
            mDeviceIcon.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }

        if (mDeviceId != null) {
            dest.writeInt(1);
            dest.writeTypedObject(mDeviceId, flags);
        } else {
            dest.writeInt(0);
        }

        dest.writeStringList(mPackagesToNotify);
        dest.writePersistableBundle(mMetadata);
        dest.writeLong(mTimeMetadataSentMs);
        dest.writeStringList(new ArrayList<>(mExtraPermissions));
        dest.writeBoolean(mRemoteAiAgentSupported);
    }

    private AssociationInfo(@NonNull Parcel in) {
        mId = in.readInt();
        mUserId = in.readInt();
        mPackageName = in.readString();
        mDeviceMacAddress = in.readTypedObject(MacAddress.CREATOR);
        mDisplayName = in.readCharSequence();
        mDeviceProfile = in.readString();
        mAssociatedDevice = in.readTypedObject(AssociatedDevice.CREATOR);
        mSelfManaged = in.readBoolean();
        mNotifyOnDeviceNearby = in.readBoolean();
        mRevoked = in.readBoolean();
        mPending = in.readBoolean();
        mTrusted = in.readBoolean();
        mTimeApprovedMs = in.readLong();
        mLastTimeConnectedMs = in.readLong();
        mSystemDataSyncFlags = in.readInt();
        mTransportFlags = in.readInt();
        int deviceIcon = in.readInt();
        if (Flags.associationDeviceIcon() && deviceIcon == 1) {
            mDeviceIcon = Icon.CREATOR.createFromParcel(in);
        } else {
            mDeviceIcon = null;
        }
        int deviceId = in.readInt();
        if (deviceId == 1) {
            mDeviceId = in.readTypedObject(DeviceId.CREATOR);
        } else {
            mDeviceId = null;
        }
        mPackagesToNotify = in.createStringArrayList();
        mMetadata = in.readPersistableBundle();
        mTimeMetadataSentMs = in.readLong();
        mExtraPermissions = new HashSet<>(in.createStringArrayList());
        mRemoteAiAgentSupported = in.readBoolean();
    }

    @NonNull
    public static final Parcelable.Creator<AssociationInfo> CREATOR =
            new Parcelable.Creator<AssociationInfo>() {
                @Override
                public AssociationInfo[] newArray(int size) {
                    return new AssociationInfo[size];
                }

                @Override
                public AssociationInfo createFromParcel(@NonNull Parcel in) {
                    return new AssociationInfo(in);
                }
            };

    /**
     * Builder for {@link AssociationInfo}
     *
     * @hide
     */
    @TestApi
    public static final class Builder {
        private final int mId;
        private final int mUserId;
        private final String mPackageName;
        private MacAddress mDeviceMacAddress;
        private CharSequence mDisplayName;
        private String mDeviceProfile;
        private AssociatedDevice mAssociatedDevice;
        private boolean mSelfManaged;
        private boolean mNotifyOnDeviceNearby;
        private boolean mRevoked;
        private boolean mPending;
        private boolean mTrusted;
        private long mTimeApprovedMs = System.currentTimeMillis();
        private long mLastTimeConnectedMs = Long.MAX_VALUE; // Never connected.
        // By default, only call metadata sync is enabled.
        private int mSystemDataSyncFlags = CompanionDeviceManager.FLAG_CALL_METADATA;
        private int mTransportFlags;
        private Icon mDeviceIcon;
        private DeviceId mDeviceId;
        private List<String> mPackagesToNotify;
        private PersistableBundle mMetadata = new PersistableBundle(); // Empty bundle by default.
        private long mTimeMetadataSentMs;
        private Set<String> mExtraPermissions = new HashSet<>();
        private boolean mRemoteAiAgentSupported;

        /** @hide */
        @TestApi
        public Builder(int id, int userId, @NonNull String packageName) {
            mId = id;
            mUserId = userId;
            mPackageName = packageName;
        }

        /** @hide */
        @TestApi
        public Builder(@NonNull AssociationInfo info) {
            this(info.mId, info.mUserId, info.mPackageName, info);
        }

        /**
         * This builder is used specifically to create a new association to be restored to a device
         * that is potentially using a different user ID from the backed-up device.
         *
         * @hide
         */
        public Builder(int id, int userId, @NonNull String packageName, AssociationInfo info) {
            mId = id;
            mUserId = userId;
            mPackageName = packageName;
            mDeviceMacAddress = info.mDeviceMacAddress;
            mDisplayName = info.mDisplayName;
            mDeviceProfile = info.mDeviceProfile;
            mAssociatedDevice = info.mAssociatedDevice;
            mSelfManaged = info.mSelfManaged;
            mNotifyOnDeviceNearby = info.mNotifyOnDeviceNearby;
            mRevoked = info.mRevoked;
            mPending = info.mPending;
            mTrusted = info.mTrusted;
            mTimeApprovedMs = info.mTimeApprovedMs;
            mLastTimeConnectedMs = info.mLastTimeConnectedMs;
            mSystemDataSyncFlags = info.mSystemDataSyncFlags;
            mTransportFlags = info.mTransportFlags;
            mDeviceIcon = info.mDeviceIcon;
            mDeviceId = info.mDeviceId;
            mPackagesToNotify = info.mPackagesToNotify;
            mMetadata = info.mMetadata;
            mTimeMetadataSentMs = info.mTimeMetadataSentMs;
            mExtraPermissions = info.mExtraPermissions;
            mRemoteAiAgentSupported = info.mRemoteAiAgentSupported;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setDeviceId(@Nullable DeviceId deviceId) {
            mDeviceId = deviceId;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setDeviceMacAddress(@Nullable MacAddress deviceMacAddress) {
            mDeviceMacAddress = deviceMacAddress;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setDisplayName(@Nullable CharSequence displayName) {
            mDisplayName = displayName;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setDeviceProfile(@Nullable String deviceProfile) {
            mDeviceProfile = deviceProfile;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setAssociatedDevice(@Nullable AssociatedDevice associatedDevice) {
            mAssociatedDevice = associatedDevice;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setSelfManaged(boolean selfManaged) {
            mSelfManaged = selfManaged;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setNotifyOnDeviceNearby(boolean notifyOnDeviceNearby) {
            mNotifyOnDeviceNearby = notifyOnDeviceNearby;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setRevoked(boolean revoked) {
            mRevoked = revoked;
            return this;
        }

        /** @hide */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setPending(boolean pending) {
            mPending = pending;
            return this;
        }

        /** @hide */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setTrusted(boolean trusted) {
            mTrusted = trusted;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setTimeApproved(long timeApprovedMs) {
            if (timeApprovedMs < 0) {
                throw new IllegalArgumentException("timeApprovedMs must be positive. Was given ("
                        + timeApprovedMs + ")");
            }
            mTimeApprovedMs = timeApprovedMs;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setLastTimeConnected(long lastTimeConnectedMs) {
            if (lastTimeConnectedMs < 0) {
                throw new IllegalArgumentException(
                        "lastTimeConnectedMs must not be negative! (Given " + lastTimeConnectedMs
                                + " )");
            }
            mLastTimeConnectedMs = lastTimeConnectedMs;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public Builder setSystemDataSyncFlags(int flags) {
            mSystemDataSyncFlags = flags;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @SuppressWarnings("UnflaggedApi")
        public Builder setTransportFlags(int flags) {
            mTransportFlags = flags;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @FlaggedApi(Flags.FLAG_ASSOCIATION_DEVICE_ICON)
        public Builder setDeviceIcon(@Nullable Icon deviceIcon) {
            mDeviceIcon = deviceIcon;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @FlaggedApi(Flags.FLAG_ASSOCIATION_VERIFICATION)
        public Builder setPackagesToNotify(@Nullable List<String> packagesToNotify) {
            mPackagesToNotify = packagesToNotify;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
        public Builder setMetadata(@NonNull PersistableBundle metadata) {
            mMetadata = metadata;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
        public Builder setTimeMetadataSent(long timestamp) {
            mTimeMetadataSentMs = timestamp;
            return this;
        }

        /**
         * Sets the set of extra permissions to be requested during the association.
         * @hide
         * @param extraPermissions A set of Android permission strings to request.
         * @return This {@code Builder} instance for method chaining.
         */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @FlaggedApi(Flags.FLAG_ASSOCIATION_EXTRA_PERMISSION)
        public Builder setExtraPermissions(@NonNull Set<String> extraPermissions) {
            mExtraPermissions = extraPermissions;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @FlaggedApi(Flags.FLAG_SUPPORT_AI_AGENT)
        public Builder setRemoteAiAgentSupported(boolean remoteAiAgentSupported) {
            mRemoteAiAgentSupported = remoteAiAgentSupported;
            return this;
        }

        /** @hide */
        @TestApi
        @NonNull
        public AssociationInfo build() {
            if (mId <= 0) {
                throw new IllegalArgumentException("Association ID should be greater than 0");
            }
            if (mDeviceMacAddress == null && mDisplayName == null) {
                throw new IllegalArgumentException("MAC address and the display name must NOT be "
                        + "null at the same time");
            }
            if (mMetadata == null) {
                throw new IllegalArgumentException("Association metadata cannot be null");
            }
            return new AssociationInfo(this);
        }
    }
}

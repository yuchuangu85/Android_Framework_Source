/*
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

package android.health.connect.internal.datatypes;

import static android.health.connect.Constants.DEFAULT_INT;
import static android.health.connect.Constants.DEFAULT_LONG;

import static com.android.healthfitness.flags.AconfigFlagHelper.isDeviceUdiEnabled;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.Identifier;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.internal.PackageNameMasker;
import android.health.connect.internal.PackageNameUnmasker;
import android.os.Parcel;

import com.android.healthfitness.flags.Flags;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Base class for all health connect datatype records.
 *
 * @param <T> The record type.
 * @hide
 */
public abstract class RecordInternal<T extends Record>
        implements PackageNameMasker<RecordInternal<T>>, PackageNameUnmasker<RecordInternal<T>> {
    private final int mRecordIdentifier;
    @Nullable private UUID mUuid;
    @Nullable private String mPackageName;
    @Nullable private String mAppName;
    private long mLastModifiedTime = DEFAULT_LONG;
    @Nullable private String mClientRecordId;
    private long mClientRecordVersion = DEFAULT_LONG;
    @Nullable private String mManufacturer;
    @Nullable private String mModel;
    private int mDeviceType;
    private long mDeviceInfoId = DEFAULT_LONG;
    private long mAppInfoId = DEFAULT_LONG;
    private int mRowId = DEFAULT_INT;
    @Nullable private String mDisplayName;
    private long mDeviceDataProviderId = DEFAULT_LONG;
    @Nullable private String mUdi;

    @Metadata.RecordingMethod private int mRecordingMethod;

    RecordInternal() {
        mRecordIdentifier = constructRecordIdentifier();
    }

    /**
     * Populates self with the data present in {@code parcel}. Reads should be in the same order as
     * write. Subclasses should add a constructor which extends this.
     */
    RecordInternal(Parcel parcel) {
        mRecordIdentifier = constructRecordIdentifier();
        String uuidString = parcel.readString();
        if (uuidString != null && !uuidString.isEmpty()) {
            mUuid = UUID.fromString(uuidString);
        }
        mPackageName = parcel.readString();
        mAppName = parcel.readString();
        mLastModifiedTime = parcel.readLong();
        mClientRecordId = parcel.readString();
        mClientRecordVersion = parcel.readLong();
        mManufacturer = parcel.readString();
        mModel = parcel.readString();
        mDeviceType = parcel.readInt();
        mRecordingMethod = parcel.readInt();
        mDisplayName = parcel.readString();
        if (isDeviceUdiEnabled()) {
            mUdi = parcel.readString();
        }
    }

    @NonNull
    @Override
    public RecordInternal<T> toMasked(@NonNull Function<String, String> packageMasker) {
        if (Objects.equals(null, mPackageName)) {
            return this;
        }

        return this.setPackageName(packageMasker.apply(mPackageName));
    }

    @NonNull
    @Override
    public RecordInternal<T> toUnmasked(@NonNull Function<String, String> packageUnmasker) {
        if (Objects.equals(null, mPackageName)) {
            return this;
        }

        return this.setPackageName(packageUnmasker.apply(mPackageName));
    }

    /** Extract the record identifier from the annotations. */
    private int constructRecordIdentifier() {
        Identifier annotation = this.getClass().getAnnotation(Identifier.class);
        return Objects.requireNonNull(annotation).recordIdentifier();
    }

    @RecordTypeIdentifier.RecordType
    public int getRecordType() {
        return mRecordIdentifier;
    }

    /**
     * Populates {@code parcel} with the self information, required to reconstruct this object
     * during IPC
     */
    @NonNull
    public final void writeToParcel(@NonNull Parcel parcel) {
        parcel.writeString(mUuid == null ? "" : mUuid.toString());
        parcel.writeString(mPackageName);
        parcel.writeString(mAppName);
        parcel.writeLong(mLastModifiedTime);
        parcel.writeString(mClientRecordId);
        parcel.writeLong(mClientRecordVersion);
        parcel.writeString(mManufacturer);
        parcel.writeString(mModel);
        parcel.writeInt(mDeviceType);
        parcel.writeInt(mRecordingMethod);
        parcel.writeString(mDisplayName);
        if (isDeviceUdiEnabled()) {
            parcel.writeString(mUdi);
        }

        populateRecordTo(parcel);
    }

    @Nullable
    public UUID getUuid() {
        return mUuid;
    }

    @NonNull
    public RecordInternal<T> setUuid(@Nullable UUID uuid) {
        this.mUuid = uuid;
        return this;
    }

    @NonNull
    public RecordInternal<T> setUuid(@Nullable String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            mUuid = null;
            return this;
        }

        mUuid = UUID.fromString(uuid);
        return this;
    }

    @Nullable
    public String getPackageName() {
        return mPackageName;
    }

    @NonNull
    public RecordInternal<T> setPackageName(String packageName) {
        this.mPackageName = packageName;
        return this;
    }

    /** Gets row id of this record. */
    public int getRowId() {
        return mRowId;
    }

    /** Sets the row id for this record. */
    public RecordInternal<T> setRowId(int rowId) {
        mRowId = rowId;
        return this;
    }

    /**
     * Returns an application name associated with this record. Currently, it is used for AppInfo
     * generation when inserting a record. May be {@code null}, in which case the app name may be
     * missing in AppInfo.
     */
    @Nullable
    public String getAppName() {
        return mAppName;
    }

    /** Sets the application name for this record. */
    @NonNull
    public RecordInternal<T> setAppName(@Nullable String appName) {
        mAppName = appName;
        return this;
    }

    public long getLastModifiedTime() {
        return mLastModifiedTime;
    }

    @NonNull
    public RecordInternal<T> setLastModifiedTime(long lastModifiedTime) {
        this.mLastModifiedTime = lastModifiedTime;
        return this;
    }

    @Nullable
    public String getClientRecordId() {
        return mClientRecordId;
    }

    @NonNull
    public RecordInternal<T> setClientRecordId(@Nullable String clientRecordId) {
        this.mClientRecordId = clientRecordId;
        return this;
    }

    public long getClientRecordVersion() {
        return mClientRecordVersion;
    }

    @NonNull
    public RecordInternal<T> setClientRecordVersion(long clientRecordVersion) {
        this.mClientRecordVersion = clientRecordVersion;
        return this;
    }

    @Nullable
    public String getManufacturer() {
        return mManufacturer;
    }

    @NonNull
    public RecordInternal<T> setManufacturer(@Nullable String manufacturer) {
        this.mManufacturer = manufacturer;
        return this;
    }

    @Nullable
    public String getModel() {
        return mModel;
    }

    @NonNull
    public RecordInternal<T> setModel(@Nullable String model) {
        this.mModel = model;
        return this;
    }

    @Device.DeviceType
    public int getDeviceType() {
        return mDeviceType;
    }

    @NonNull
    public RecordInternal<T> setDeviceType(@Device.DeviceType int deviceType) {
        this.mDeviceType = deviceType;
        return this;
    }

    public long getDeviceInfoId() {
        return mDeviceInfoId;
    }

    @NonNull
    public RecordInternal<T> setDeviceInfoId(long deviceInfoId) {
        this.mDeviceInfoId = deviceInfoId;
        return this;
    }

    public long getAppInfoId() {
        return mAppInfoId;
    }

    @NonNull
    public RecordInternal<T> setAppInfoId(long appInfoId) {
        this.mAppInfoId = appInfoId;
        return this;
    }

    @Nullable
    public String getDisplayName() {
        return mDisplayName;
    }

    /** Sets the device display name. */
    @NonNull
    public RecordInternal<T> setDisplayName(@Nullable String displayName) {
        mDisplayName = displayName;
        return this;
    }

    /** Returns recording method which indicates how data was recorded for the {@link Record} */
    @Metadata.RecordingMethod
    public int getRecordingMethod() {
        return mRecordingMethod;
    }

    /** Sets Recording method to know how data was recorded for the {@link Record} */
    @NonNull
    public RecordInternal<T> setRecordingMethod(@Metadata.RecordingMethod int recordingMethod) {
        this.mRecordingMethod = recordingMethod;
        return this;
    }

    /**
     * Returns the device data provider package name which indicates which provider inserted the
     * {@link Record}
     */
    public long getDeviceDataProviderId() {
        return mDeviceDataProviderId;
    }

    /**
     * Sets the device data provider package name which indicates which provider inserted the {@link
     * Record}
     */
    @NonNull
    public RecordInternal<T> setDeviceDataProviderId(long deviceDataProviderId) {
        this.mDeviceDataProviderId = deviceDataProviderId;
        return this;
    }

    /**
     * @return The device UDI if set, null otherwise
     */
    @Nullable
    public String getUdi() {
        if (!isDeviceUdiEnabled()) {
            throw new UnsupportedOperationException("Device UDI flag off");
        }
        return mUdi;
    }

    /** Sets the device UDI. */
    @NonNull
    public RecordInternal<T> setUdi(@Nullable String udi) {
        if (!isDeviceUdiEnabled()) {
            throw new UnsupportedOperationException("Device UDI flag off");
        }
        mUdi = udi;
        return this;
    }

    /** Child class must implement this method and return an external record for this record */
    public abstract T toExternalRecord();

    @NonNull
    Metadata buildMetaData() {
        @SuppressWarnings("NullAway") // TODO(b/317029272): fix this suppression
        DataOrigin dataOrigin = new DataOrigin.Builder().setPackageName(getPackageName()).build();

        Device.Builder deviceBuilder =
                new Device.Builder()
                        .setManufacturer(getManufacturer())
                        .setType(getDeviceType())
                        .setModel(getModel());
        if (Flags.deviceDataProvidersApi()) {
            deviceBuilder.setDisplayName(getDisplayName());
        }
        if (isDeviceUdiEnabled()) {
            deviceBuilder.setUdi(getUdi());
        }
        Metadata.Builder builder =
                new Metadata.Builder()
                        .setClientRecordId(getClientRecordId())
                        .setClientRecordVersion(getClientRecordVersion())
                        .setDataOrigin(dataOrigin)
                        .setLastModifiedTime(Instant.ofEpochMilli(getLastModifiedTime()))
                        .setRecordingMethod(getRecordingMethod())
                        .setDevice(deviceBuilder.build());
        UUID id = getUuid();
        if (id != null) {
            builder.setId(id.toString());
        }
        return builder.build();
    }

    /** Sets the fields for meta data for internal records */
    @NonNull
    public RecordInternal<T> setMetaData(Metadata metaData) {
        this.setUuid(metaData.getId())
                .setPackageName(metaData.getDataOrigin().getPackageName())
                .setLastModifiedTime(metaData.getLastModifiedTime().toEpochMilli())
                .setClientRecordId(metaData.getClientRecordId())
                .setClientRecordVersion(metaData.getClientRecordVersion())
                .setManufacturer(metaData.getDevice().getManufacturer())
                .setModel(metaData.getDevice().getModel())
                .setDeviceType(metaData.getDevice().getType())
                .setRecordingMethod(metaData.getRecordingMethod());
        if (Flags.deviceDataProvidersApi()) {
            this.setDisplayName(metaData.getDevice().getDisplayName());
        }
        if (isDeviceUdiEnabled()) {
            this.setUdi(metaData.getDevice().getUdi());
        }
        return this;
    }

    /**
     * @return the {@link LocalDate} object of this activity start time.
     */
    public abstract LocalDate getLocalDate();

    /**
     * @return the time at which the record ended. This matches the end time for an InstantRecord
     *     and time for IntervalRecord.
     */
    public abstract long getRecordTime();

    /**
     * Populate {@code bundle} with the data required to un-bundle self. This is used during IPC
     * transmissions
     */
    abstract void populateRecordTo(@NonNull Parcel bundle);
}

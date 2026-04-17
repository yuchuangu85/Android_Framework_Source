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

package android.health.connect.backuprestore;

import static com.android.healthfitness.flags.Flags.FLAG_CLOUD_BACKUP_AND_RESTORE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Response for a {@link android.health.connect.HealthConnectManager#getLatestMetadataForBackup}
 * call, which contains the latest metadata for a backup.
 *
 * @hide
 */
@FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE)
@SystemApi
public final class GetLatestMetadataForBackupResponse implements Parcelable {

    // Version how the data was encoded.
    private final int mCurrentVersion;

    @NonNull private final BackupMetadata mMetadata;

    /**
     * @param currentVersion The version of the metadata contained in the response, with which the
     *     metadata is serialized.
     * @param metadata The metadata to be backed up.
     */
    public GetLatestMetadataForBackupResponse(
            int currentVersion, @NonNull BackupMetadata metadata) {
        mCurrentVersion = currentVersion;
        mMetadata = metadata;
    }

    private GetLatestMetadataForBackupResponse(Parcel in) {
        mCurrentVersion = in.readInt();
        mMetadata = in.readParcelable(BackupMetadata.class.getClassLoader());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GetLatestMetadataForBackupResponse that)) return false;
        return mCurrentVersion == that.mCurrentVersion && mMetadata.equals(that.mMetadata);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mCurrentVersion);
        result = 31 * result + Objects.hash(mMetadata);
        return result;
    }

    @NonNull
    public static final Creator<GetLatestMetadataForBackupResponse> CREATOR =
            new Creator<GetLatestMetadataForBackupResponse>() {
                @Override
                public GetLatestMetadataForBackupResponse createFromParcel(Parcel in) {
                    return new GetLatestMetadataForBackupResponse(in);
                }

                @Override
                public GetLatestMetadataForBackupResponse[] newArray(int size) {
                    return new GetLatestMetadataForBackupResponse[size];
                }
            };

    /**
     * The returned value should be passed to the {@link
     * android.health.connect.HealthConnectManager#canRestore} call during restore.
     *
     * @return the current version with which the metadata is serialized.
     */
    @NonNull
    public int getCurrentVersion() {
        return mCurrentVersion;
    }

    @NonNull
    public BackupMetadata getMetadata() {
        return mMetadata;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mCurrentVersion);
        dest.writeParcelable(mMetadata, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}

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

import java.util.Arrays;

/**
 * Metadata to be backed up or restored.
 *
 * @hide
 */
@FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE)
@SystemApi
public final class BackupMetadata implements Parcelable {

    @NonNull private final byte[] mData;

    /**
     * @param data The metadata to be backed up.
     */
    public BackupMetadata(@NonNull byte[] data) {
        mData = data;
    }

    private BackupMetadata(Parcel in) {
        mData = in.readBlob();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BackupMetadata that)) return false;
        return Arrays.equals(mData, that.mData);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mData);
    }

    @NonNull
    public static final Creator<BackupMetadata> CREATOR =
            new Creator<BackupMetadata>() {
                @Override
                public BackupMetadata createFromParcel(Parcel in) {
                    return new BackupMetadata(in);
                }

                @Override
                public BackupMetadata[] newArray(int size) {
                    return new BackupMetadata[size];
                }
            };

    /** Returns the metadata to be backed up or restored. */
    @NonNull
    public byte[] getData() {
        return mData;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBlob(mData);
    }
}

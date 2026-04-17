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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a change in Health Connect to be backed up.
 *
 * @hide
 */
@FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE)
@SystemApi
public final class BackupChange implements Parcelable {

    // A change ID that uniquely identifies the specific data point this change refers to.
    @NonNull private final String mChangeId;

    // Only present if the change is an upsertion, rather than a deletion.
    // The data is a byte array to keep the data opaque from the client.
    // As long as the client doesn't parse the data, it doesn't know what type of data this is.
    @Nullable private final byte[] mData;

    /**
     * Creates a new BackupChange of a deletion. This indicates that the previously backed up change
     * with the id equal to {@code changeId} should be deleted from the backup history.
     *
     * @param changeId A change ID that uniquely identifies the specific data point this deletion
     *     change refers to.
     * @return A new {@code BackupChange} of a deletion.
     */
    @NonNull
    public static BackupChange ofDeletion(@NonNull String changeId) {
        return new BackupChange(changeId, /* data= */ null);
    }

    /**
     * Creates a new BackupChange of an insertion or an update. This indicates that either a new
     * change with the {@code changeId} should be inserted or the previously backed up change with
     * the id equal to {@code changeId} should be modified in the backup history.
     *
     * @param changeId A change ID that uniquely identifies the specific data point this upsertion
     *     change refers to.
     * @param data The data to be backed up.
     * @return A new {@code BackupChange} of an insertion or an update.
     */
    @NonNull
    public static BackupChange ofUpsertion(@NonNull String changeId, @NonNull byte[] data) {
        return new BackupChange(changeId, data);
    }

    /**
     * @param changeId A change ID that uniquely identifies the specific data point this change
     *     refers to.
     * @param data The data to be backed up. Null if the change is a deletion.
     */
    private BackupChange(@NonNull String changeId, @Nullable byte[] data) {
        mChangeId = changeId;
        mData = data;
    }

    @Override
    public boolean equals(Object o) {
        return this == o
                || ((o instanceof BackupChange that)
                        && mChangeId.equals(that.mChangeId)
                        && Arrays.equals(mData, that.mData));
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mChangeId);
        result = 31 * result + Arrays.hashCode(mData);
        return result;
    }

    private BackupChange(Parcel in) {
        mChangeId = in.readString();
        var unused = in.readByte();
        mData = in.readBlob();
    }

    @NonNull
    public static final Creator<BackupChange> CREATOR =
            new Creator<>() {
                @Override
                public BackupChange createFromParcel(Parcel in) {
                    return new BackupChange(in);
                }

                @Override
                public BackupChange[] newArray(int size) {
                    return new BackupChange[size];
                }
            };

    /**
     * @return A change ID that uniquely identifies the specific data point this change refers to.
     */
    @NonNull
    public String getChangeId() {
        return mChangeId;
    }

    /**
     * @return whether this change is a deletion. The caller should delete its copy of a data point
     *     if a deletion change is received.
     */
    public boolean isDeletion() {
        return mData == null;
    }

    /**
     * The data is a byte array to keep the itself opaque from the client. The valid format of the
     * data is a serialized record, though the caller shouldn't make any assumptions about the
     * format of the data.
     *
     * @return the data to be backed up. Only present if isDeletion is false.
     */
    @Nullable
    public byte[] getData() {
        return mData;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mChangeId);
        dest.writeByte((byte) (isDeletion() ? 1 : 0));
        dest.writeBlob(mData);
    }
}

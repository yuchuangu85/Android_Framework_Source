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

package android.health.connect.aidl;

import static com.android.healthfitness.flags.Flags.FLAG_PHR_CHANGE_LOGS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.health.connect.MedicalResourceId;
import android.health.connect.changelog.ChangeLogsResponse.DeletedMedicalResource;
import android.health.connect.internal.ParcelUtils;
import android.os.Parcel;
import android.os.Parcelable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Parcelable} that reads and writes {@link DeletedMedicalResource}s.
 *
 * @hide
 */
@FlaggedApi(FLAG_PHR_CHANGE_LOGS)
public final class DeletedMedicalResourcesParcel implements Parcelable {

    @NonNull
    public static final Creator<DeletedMedicalResourcesParcel> CREATOR =
            new Creator<>() {
                @Override
                public DeletedMedicalResourcesParcel createFromParcel(Parcel in) {
                    return new DeletedMedicalResourcesParcel(in);
                }

                @Override
                public DeletedMedicalResourcesParcel[] newArray(int size) {
                    return new DeletedMedicalResourcesParcel[size];
                }
            };

    private final List<DeletedMedicalResource> mDeletedMedicalResources;

    public DeletedMedicalResourcesParcel(
            @NonNull List<DeletedMedicalResource> deletedMedicalResources) {
        mDeletedMedicalResources = deletedMedicalResources;
    }

    private DeletedMedicalResourcesParcel(@NonNull Parcel in) {
        in = ParcelUtils.getParcelForSharedMemoryIfRequired(in);
        int size = in.readInt();
        mDeletedMedicalResources = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            var id =
                    in.readParcelable(
                            MedicalResourceId.class.getClassLoader(), MedicalResourceId.class);
            long time = in.readLong();
            mDeletedMedicalResources.add(
                    new DeletedMedicalResource(id, Instant.ofEpochMilli(time)));
        }
    }

    @NonNull
    public List<DeletedMedicalResource> getDeletedMedicalResources() {
        return mDeletedMedicalResources;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        ParcelUtils.putToRequiredMemory(dest, flags, this::writeToParcelInternal);
    }

    private void writeToParcelInternal(@NonNull Parcel dest) {
        dest.writeInt(mDeletedMedicalResources.size());
        for (DeletedMedicalResource deletedMedicalResource : mDeletedMedicalResources) {
            dest.writeParcelable(deletedMedicalResource.getDeletedMedicalResourceId(), 0);
            dest.writeLong(deletedMedicalResource.getDeletedTime().toEpochMilli());
        }
    }
}

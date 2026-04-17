/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.app.privatecompute;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Result of a migration request initiated by a PCC component.
 */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
public final class MigrationRequestResult implements Parcelable {

    /** The non-PCC process has accepted the request and will begin migration. */
    public static final int MIGRATION_REQUEST_ACCEPTED = 1;

    /** The non-PCC process has rejected the request. */
    public static final int MIGRATION_REQUEST_REJECTED = 2;

    /** @hide */
    @IntDef(prefix = { "MIGRATION_REQUEST_" }, value = {
            MIGRATION_REQUEST_ACCEPTED,
            MIGRATION_REQUEST_REJECTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MigrationStatus {}

    private final @MigrationStatus int mStatus;
    private final PersistableBundle mExtras;

    public MigrationRequestResult(@MigrationStatus int status, @Nullable PersistableBundle extras) {
        mStatus = status;
        mExtras = extras != null ? new PersistableBundle(extras) : PersistableBundle.EMPTY;
    }

    private MigrationRequestResult(Parcel in) {
        mStatus = in.readInt();
        mExtras = in.readPersistableBundle(getClass().getClassLoader());
    }

    @MigrationStatus
    public int getStatus() {
        return mStatus;
    }

    @NonNull
    public PersistableBundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writePersistableBundle(mExtras);
    }

    @NonNull
    public static final Creator<MigrationRequestResult> CREATOR =
            new Creator<MigrationRequestResult>() {
                @Override
                public MigrationRequestResult createFromParcel(Parcel in) {
                    return new MigrationRequestResult(in);
                }

                @Override
                public MigrationRequestResult[] newArray(int size) {
                    return new MigrationRequestResult[size];
                }
            };
}

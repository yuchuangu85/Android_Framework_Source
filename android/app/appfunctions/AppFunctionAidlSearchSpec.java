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

package android.app.appfunctions;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * An internal {@link AppFunctionSearchSpec} that provides additional caller information.
 *
 * @hide
 */
public final class AppFunctionAidlSearchSpec implements Parcelable {
    public static final Creator<AppFunctionAidlSearchSpec> CREATOR =
            new Creator<AppFunctionAidlSearchSpec>() {
                @Override
                public AppFunctionAidlSearchSpec createFromParcel(Parcel in) {
                    return new AppFunctionAidlSearchSpec(in);
                }

                @Override
                public AppFunctionAidlSearchSpec[] newArray(int size) {
                    return new AppFunctionAidlSearchSpec[size];
                }
            };

    @NonNull private final String mCallingPackageName;

    @NonNull private final AppFunctionSearchSpec mClientSearchSpec;

    private final int mTargetUserId;

    public AppFunctionAidlSearchSpec(
            @NonNull String callingPackageName,
            @NonNull AppFunctionSearchSpec clientSearchSpec,
            int targetUserId) {
        mCallingPackageName = Objects.requireNonNull(callingPackageName);
        mClientSearchSpec = Objects.requireNonNull(clientSearchSpec);
        mTargetUserId = targetUserId;
    }

    private AppFunctionAidlSearchSpec(@NonNull Parcel in) {
        this(
                Objects.requireNonNull(in.readString8()),
                Objects.requireNonNull(in.readTypedObject(AppFunctionSearchSpec.CREATOR)),
                in.readInt());
    }

    /** Gets the calling package name. */
    @NonNull
    public String getCallingPackageName() {
        return mCallingPackageName;
    }

    /** Gets the client {@link AppFunctionSearchSpec}. */
    @NonNull
    public AppFunctionSearchSpec getClientSearchSpec() {
        return mClientSearchSpec;
    }

    /** Gets the target user id. */
    public int getTargetUserId() {
        return mTargetUserId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mCallingPackageName);
        dest.writeTypedObject(mClientSearchSpec, flags);
        dest.writeInt(mTargetUserId);
    }
}

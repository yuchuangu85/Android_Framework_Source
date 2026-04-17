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

package android.os.storage.operations.targets;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import com.android.internal.util.Preconditions;

/**
 * Wrapper for parceling/unparceling {@link OperationTarget}.
 *
 * @hide
 */
@RavenwoodKeepWholeClass
public final class OperationTargetWrapper implements Parcelable {

    private final @NonNull OperationTarget mOperationTarget;

    public OperationTargetWrapper(@NonNull OperationTarget target) {
        Preconditions.checkNotNull(target);
        mOperationTarget = target;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @NonNull
    public OperationTarget getWrappedTarget() {
        return mOperationTarget;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(mOperationTarget.getDataBundle());
    }

    public static final @NonNull Creator<OperationTargetWrapper> CREATOR =
            new Creator<OperationTargetWrapper>() {
                @Override
                public OperationTargetWrapper createFromParcel(@NonNull Parcel source) {
                    return new OperationTargetWrapper(
                            OperationTarget.createTargetFromBundle(source.readBundle()));
                }

                @Override
                public OperationTargetWrapper[] newArray(int size) {
                    return new OperationTargetWrapper[size];
                }
            };
}

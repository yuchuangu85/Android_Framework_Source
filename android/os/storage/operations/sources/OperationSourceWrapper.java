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

package android.os.storage.operations.sources;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import com.android.internal.util.Preconditions;

/**
 * Wrapper for parceling/unparceling {@link OperationSource}.
 *
 * @hide
 */
@RavenwoodKeepWholeClass
public final class OperationSourceWrapper implements Parcelable {

    private final @NonNull OperationSource mOperationSource;

    public OperationSourceWrapper(@NonNull OperationSource source) {
        Preconditions.checkNotNull(source);
        mOperationSource = source;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public OperationSource getWrappedSource() {
        return mOperationSource;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(mOperationSource.getDataBundle());
    }

    public static final @NonNull Creator<OperationSourceWrapper> CREATOR =
            new Creator<OperationSourceWrapper>() {
                @Override
                public OperationSourceWrapper createFromParcel(@NonNull Parcel source) {
                    return new OperationSourceWrapper(
                            OperationSource.createSourceFromBundle(source.readBundle()));
                }

                @Override
                public OperationSourceWrapper[] newArray(int size) {
                    return new OperationSourceWrapper[size];
                }
            };
}

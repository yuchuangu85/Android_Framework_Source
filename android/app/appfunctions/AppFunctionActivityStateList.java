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

package android.app.appfunctions;

import android.annotation.NonNull;
import android.content.pm.ParceledListSlice;
import android.os.ParcelDedupHelper;
import android.os.Parcel;
import android.os.Parcel.ReadWriteHelper;
import android.os.Parcelable.Creator;
import java.util.List;

/**
 * A list of {@link AppFunctionAppFunctionActivityState}s that can be parcelled.
 *
 * <p>This class is used to parcel an unbounded list of {@link AppFunctionActivityState}s. which can
 * be technically over the IPC size limit.
 *
 * @hide
 */
public final class AppFunctionActivityStateList
        extends ParceledListSlice<AppFunctionActivityState> {
    public AppFunctionActivityStateList(List<AppFunctionActivityState> list) {
        super(list);
    }

    public AppFunctionActivityStateList(Parcel p) {
        super(p, AppFunctionActivityStateList.class.getClassLoader());
    }

    @Override
    protected ReadWriteHelper createReadWriteHelper() {
        return new ParcelDedupHelper.Builder().dedupString8(true).build();
    }

    @NonNull
    public static final Creator<AppFunctionActivityStateList> CREATOR =
            new Creator<AppFunctionActivityStateList>() {
                @Override
                public AppFunctionActivityStateList createFromParcel(Parcel in) {
                    return new AppFunctionActivityStateList(in);
                }

                @Override
                public AppFunctionActivityStateList[] newArray(int size) {
                    return new AppFunctionActivityStateList[size];
                }
            };
}

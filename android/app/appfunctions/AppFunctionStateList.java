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
import android.os.Parcel;
import android.os.ParcelDedupHelper;
import android.os.Parcel.ReadWriteHelper;
import android.os.Parcelable.Creator;
import java.util.List;

/**
 * A list of {@link AppFunctionState}s that can be parcelled.
 *
 * <p>This class is used to parcel a list of {@link AppFunctionState}s efficiently, deduping {@link
 * AppFunctionActivityId}s that are reused across functions and paginating a list that is too large.
 *
 * @hide
 */
public final class AppFunctionStateList extends ParceledListSlice<AppFunctionState> {
    public AppFunctionStateList(List<AppFunctionState> list) {
        super(list);
    }

    public AppFunctionStateList(Parcel p) {
        super(p, AppFunctionStateList.class.getClassLoader());
    }

    @Override
    protected ReadWriteHelper createReadWriteHelper() {
        return new ParcelDedupHelper.Builder().dedupBinders(true).dedupString8(true).build();
    }

    @NonNull
    public static final Creator<AppFunctionStateList> CREATOR =
            new Creator<AppFunctionStateList>() {
                @Override
                public AppFunctionStateList createFromParcel(Parcel in) {
                    return new AppFunctionStateList(in);
                }

                @Override
                public AppFunctionStateList[] newArray(int size) {
                    return new AppFunctionStateList[size];
                }
            };
}

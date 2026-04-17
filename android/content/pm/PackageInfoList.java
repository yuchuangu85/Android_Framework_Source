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

package android.content.pm;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcel.ReadWriteHelper;
import android.os.ParcelDedupHelper;

import java.util.Collections;
import java.util.List;

/**
 * A list of {@link PackageInfo}s that can be parcelled.
 *
 * <p>This class is used to parcel a list of {@link PackageInfo}s efficiently, deduping common
 * strings that are reused across packages and paginating a list that is too large.
 *
 * @hide
 */
public final class PackageInfoList extends ParceledListSlice<PackageInfo> {
    private static final String TAG = "PackageInfoList";

    /**
     * Returns an empty {@link PackageInfoList}.
     */
    public static PackageInfoList emptyList() {
        return new PackageInfoList(Collections.emptyList());
    }

    /**
     * Creates a {@link PackageInfoList} with the given list of {@link PackageInfo}s.
     */
    public PackageInfoList(List<PackageInfo> list) {
        super(list);
    }

    /**
     * Creates a {@link PackageInfoList} from a {@link Parcel}.
     */
    public PackageInfoList(Parcel p) {
        super(p, PackageInfoList.class.getClassLoader());
    }

    @Override
    protected ReadWriteHelper createReadWriteHelper() {
        return new ParcelDedupHelper.Builder()
                .dedupBinders(true)
                .dedupString8(true)
                .dedupString16(true)
                .build();
    }

    /**
     * Creator for {@link PackageInfoList}.
     */
    @NonNull
    public static final Creator<PackageInfoList> CREATOR =
            new Creator<PackageInfoList>() {
                @Override
                public PackageInfoList createFromParcel(Parcel in) {
                    return new PackageInfoList(in);
                }

                @Override
                public PackageInfoList[] newArray(int size) {
                    return new PackageInfoList[size];
                }
            };
}

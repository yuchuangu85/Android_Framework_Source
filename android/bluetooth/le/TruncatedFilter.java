/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.le;

import android.annotation.Hide;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.util.Log;

import java.util.List;

/**
 * A special scan filter that lets the client decide how the scan record should be stored.
 *
 * @deprecated this is not used anywhere
 */
@Hide
@Deprecated
@SystemApi
public final class TruncatedFilter {
    private static final String TAG = TruncatedFilter.class.getSimpleName();
    private static final String MESSAGE = " is deprecated and not supported; Will be removed soon";
    private final ScanFilter mFilter;
    private final List<ResultStorageDescriptor> mStorageDescriptors;

    /**
     * Constructor for {@link TruncatedFilter}.
     *
     * @param filter Scan filter of the truncated filter.
     * @param storageDescriptors Describes how the scan should be stored.
     */
    public TruncatedFilter(ScanFilter filter, List<ResultStorageDescriptor> storageDescriptors) {
        Log.wtf(TAG, MESSAGE);
        mFilter = filter;
        mStorageDescriptors = storageDescriptors;
    }

    /** Returns the scan filter. */
    @RequiresNoPermission
    public ScanFilter getFilter() {
        return mFilter;
    }

    /** Returns a list of descriptor for scan result storage. */
    @RequiresNoPermission
    public List<ResultStorageDescriptor> getStorageDescriptors() {
        return mStorageDescriptors;
    }
}

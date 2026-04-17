/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.health.connect.internal;

import android.annotation.NonNull;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/** @hide */
public final class ParcelUtils {
    private static final String TAG = "HealthConnectParcelUtils";

    @VisibleForTesting public static final int USING_SHARED_MEMORY = 0;
    @VisibleForTesting public static final int USING_PARCEL = 1;

    @VisibleForTesting
    public static final int IPC_PARCEL_LIMIT = IBinder.getSuggestedMaxIpcSizeBytes() / 2;

    public interface IPutToParcelRunnable {
        void writeToParcel(Parcel dest);
    }

    @NonNull
    public static Parcel getParcelForSharedMemoryIfRequired(Parcel in) {
        int parcelType = in.readInt();
        if (parcelType == USING_SHARED_MEMORY) {
            try (SharedMemory memory = SharedMemory.CREATOR.createFromParcel(in)) {
                Parcel dataParcel = Parcel.obtain();
                ByteBuffer buffer = memory.mapReadOnly();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                        && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                    dataParcel.unmarshall(buffer);
                } else {
                    byte[] payload = new byte[buffer.limit()];
                    buffer.get(payload);
                    dataParcel.unmarshall(payload, 0, payload.length);
                }
                dataParcel.setDataPosition(0);
                return dataParcel;
            } catch (ErrnoException e) {
                throw new RuntimeException(e);
            }
        }
        return in;
    }

    public static SharedMemory getSharedMemoryForParcel(Parcel dataParcel) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                    && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                SharedMemory sharedMemory =
                        SharedMemory.create("RecordsParcelSharedMemory", dataParcel.dataSize());
                ByteBuffer buffer = sharedMemory.mapReadWrite();
                try {
                    dataParcel.marshall(buffer);
                    return sharedMemory;
                } catch (BufferOverflowException e) {
                    Log.wtf(TAG, "Failed to marshall directly into ByteBuffer", e);
                    sharedMemory.close();
                    // Fallback to marshalling to an array first.
                }
            }

            // Marshall first to ensure the correct size is allocated.
            byte[] data = dataParcel.marshall();
            SharedMemory sharedMemory =
                    SharedMemory.create("RecordsParcelSharedMemory", data.length);
            ByteBuffer buffer = sharedMemory.mapReadWrite();
            buffer.put(data, 0, data.length);
            return sharedMemory;
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Determines which memory to use and puts the {@code parcel} in it, and details of it in {@code
     * dest}
     */
    public static void putToRequiredMemory(
            Parcel dest, int flags, IPutToParcelRunnable parcelRunnable) {
        final Parcel dataParcel = Parcel.obtain();
        try {
            parcelRunnable.writeToParcel(dataParcel);
            final int dataParcelSize = dataParcel.dataSize();
            // Strictly speaking the if statement below is incorrect. An OEM is free to change the
            // implementation of Parcel.marshall() so the length of the resulting data is
            // significantly longer than dataSize(). If they did this, AND
            // dataSize() < IPC_PARCEL_LIMIT (which is the recommended max size / 2) AND
            // the marshalled length was greater than the maximum IPC size we could get a crash.
            // In practice, I think the chance of any OEM changing the marshall() code to have
            // such a big disparity is 0. for the default implementation the are guaranteed to be
            // equal, and it is such a core part of Android I think most people won't touch it.
            // And doing the check like this saves two unmarshall call which saves CPU and RAM. So
            // the code is being left as it is.
            if (dataParcelSize > IPC_PARCEL_LIMIT) {
                try (SharedMemory sharedMemory = ParcelUtils.getSharedMemoryForParcel(dataParcel)) {
                    dest.writeInt(USING_SHARED_MEMORY);
                    sharedMemory.writeToParcel(dest, flags);
                }
            } else {
                dest.writeInt(USING_PARCEL);
                parcelRunnable.writeToParcel(dest);
            }
        } finally {
            dataParcel.recycle();
        }
    }
}

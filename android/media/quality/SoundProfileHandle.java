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

package android.media.quality;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.tv.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A type-safe handle to a sound profile.
 *
 * A sound profile represents a collection of parameters used to configure sound processing
 * to enhance the quality of graphic buffers.
 *
 * @see SoundProfile.getHandle
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
public final class SoundProfileHandle implements Parcelable {
    /** A handle that represents no sound configuration. */
    public static final @NonNull SoundProfileHandle NONE = new SoundProfileHandle(0);

    private final long mId;

    /** @hide */
    public SoundProfileHandle(long id) {
        mId = id;
    }

    /**
     * An ID that uniquely identifies the sound profile across the system.
     *
     * Note: These IDs are generated randomly and are not stable across reboots.
     *
     */
    public long getId() {
        return mId;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<SoundProfileHandle> CREATOR =
            new Creator<SoundProfileHandle>() {
                @Override
                public SoundProfileHandle createFromParcel(Parcel in) {
                    return new SoundProfileHandle(in);
                }

                @Override
                public SoundProfileHandle[] newArray(int size) {
                    return new SoundProfileHandle[size];
                }
            };

    private SoundProfileHandle(@NonNull Parcel in) {
        mId = in.readLong();
    }
}

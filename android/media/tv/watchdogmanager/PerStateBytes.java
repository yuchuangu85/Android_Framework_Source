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

package android.media.tv.watchdogmanager;

import android.annotation.FlaggedApi;
import android.media.tv.flags.Flags;
import android.os.Parcelable;

/**
 * Number of bytes attributed to each application or system state.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_TV_WATCHDOG_EMMC_PROTECTION)
public final class PerStateBytes implements Parcelable {
    /** Number of bytes attributed to the application foreground mode. */
    private long mForegroundModeBytes;

    /** Number of bytes attributed to the application background mode. */
    private long mBackgroundModeBytes;

    /** Number of bytes attributed to the system garage mode. */
    private long mGarageModeBytes;

    /**
     * Creates a new PerStateBytes.
     *
     * @param foregroundModeBytes Number of bytes attributed to the application foreground mode.
     * @param backgroundModeBytes Number of bytes attributed to the application background mode.
     * @param garageModeBytes Number of bytes attributed to the system garage mode.
     */
    public PerStateBytes(long foregroundModeBytes, long backgroundModeBytes, long garageModeBytes) {
        this.mForegroundModeBytes = foregroundModeBytes;
        this.mBackgroundModeBytes = backgroundModeBytes;
        this.mGarageModeBytes = garageModeBytes;
    }

    /** Number of bytes attributed to the application foreground mode. */
    public long getForegroundModeBytes() {
        return mForegroundModeBytes;
    }

    /** Number of bytes attributed to the application background mode. */
    public long getBackgroundModeBytes() {
        return mBackgroundModeBytes;
    }

    /** Number of bytes attributed to the system garage mode. */
    public long getGarageModeBytes() {
        return mGarageModeBytes;
    }

    @Override
    public String toString() {
        return "PerStateBytes { "
                + "foregroundModeBytes = "
                + mForegroundModeBytes
                + ", "
                + "backgroundModeBytes = "
                + mBackgroundModeBytes
                + ", "
                + "garageModeBytes = "
                + mGarageModeBytes
                + " }";
    }

    @Override
    public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
        dest.writeLong(mForegroundModeBytes);
        dest.writeLong(mBackgroundModeBytes);
        dest.writeLong(mGarageModeBytes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    /* package-private */ PerStateBytes(@android.annotation.NonNull android.os.Parcel in) {
        long foregroundModeBytes = in.readLong();
        long backgroundModeBytes = in.readLong();
        long garageModeBytes = in.readLong();

        this.mForegroundModeBytes = foregroundModeBytes;
        this.mBackgroundModeBytes = backgroundModeBytes;
        this.mGarageModeBytes = garageModeBytes;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<PerStateBytes> CREATOR =
            new Parcelable.Creator<PerStateBytes>() {
                @Override
                public PerStateBytes[] newArray(int size) {
                    return new PerStateBytes[size];
                }

                @Override
                public PerStateBytes createFromParcel(
                        @android.annotation.NonNull android.os.Parcel in) {
                    return new PerStateBytes(in);
                }
            };
}

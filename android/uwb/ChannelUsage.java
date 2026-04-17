/*
 * Copyright 2025 The Android Open Source Project
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

package android.uwb;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * @hide
 */
public final class ChannelUsage implements Parcelable {
    public final int mChannel;
    public final boolean mUsage;

    public ChannelUsage(int channel, boolean usage) {
        mChannel = channel;
        mUsage = usage;
    }

    private ChannelUsage(Parcel in) {
        mChannel = in.readInt();
        mUsage = in.readByte() != 0;
    }

    public static final Creator<ChannelUsage> CREATOR = new Creator<ChannelUsage>() {
        @Override
        public ChannelUsage createFromParcel(Parcel in) {
            return new ChannelUsage(in);
        }

        @Override
        public ChannelUsage[] newArray(int size) {
            return new ChannelUsage[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mChannel);
        dest.writeByte((byte) (mUsage ? 1 : 0));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ChannelUsage other = (ChannelUsage) obj;
        return mChannel == other.mChannel && mUsage == other.mUsage;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mChannel, mUsage);
    }

    @Override
    public String toString() {
        return "{"
                + "Channel=" + mChannel
                + ", InUse=" + mUsage
                + "}";
    }
}

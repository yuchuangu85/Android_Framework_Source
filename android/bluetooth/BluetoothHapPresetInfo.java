/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package android.bluetooth;

import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/** Represents the Hearing Access Profile preset. */
@Hide
@SystemApi
public final class BluetoothHapPresetInfo implements Parcelable {
    private final int mIndex;
    private final String mName;
    private final boolean mIsWritable;
    private final boolean mIsAvailable;

    /**
     * HapPresetInfo constructor
     *
     * @param index Preset index
     * @param name Preset Name
     * @param isWritable Is writable flag
     * @param isAvailable Is available flag
     */
    BluetoothHapPresetInfo(
            int index, @NonNull String name, boolean isWritable, boolean isAvailable) {
        this.mIndex = index;
        this.mName = name;
        this.mIsWritable = isWritable;
        this.mIsAvailable = isAvailable;
    }

    /**
     * HapPresetInfo constructor
     *
     * @param in HapPresetInfo parcel
     */
    private BluetoothHapPresetInfo(@NonNull Parcel in) {
        mIndex = in.readInt();
        mName = in.readString();
        mIsWritable = in.readBoolean();
        mIsAvailable = in.readBoolean();
    }

    /**
     * HapPresetInfo preset index
     *
     * @return Preset index
     */
    @RequiresNoPermission
    public int getIndex() {
        return mIndex;
    }

    /**
     * HapPresetInfo preset name
     *
     * @return Preset name
     */
    @RequiresNoPermission
    public @NonNull String getName() {
        return mName;
    }

    /**
     * HapPresetInfo preset writability
     *
     * @return If preset is writable
     */
    @RequiresNoPermission
    public boolean isWritable() {
        return mIsWritable;
    }

    /**
     * HapPresetInfo availability
     *
     * @return If preset is available
     */
    @RequiresNoPermission
    public boolean isAvailable() {
        return mIsAvailable;
    }

    @Override
    public String toString() {
        return (getClass().getSimpleName()
                        + "@"
                        + Integer.toHexString(System.identityHashCode(this)))
                + (" {mIndex=" + mIndex)
                + (", mIsWritable=" + mIsWritable)
                + (", mIsAvailable=" + mIsAvailable)
                + (", mName=" + mName + "}");
    }

    /** HapPresetInfo array creator */
    public static final @NonNull Creator<BluetoothHapPresetInfo> CREATOR =
            new Creator<BluetoothHapPresetInfo>() {
                public BluetoothHapPresetInfo createFromParcel(@NonNull Parcel in) {
                    return new BluetoothHapPresetInfo(in);
                }

                public BluetoothHapPresetInfo[] newArray(int size) {
                    return new BluetoothHapPresetInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mIndex);
        BluetoothUtils.writeStringToParcel(dest, mName);
        dest.writeBoolean(mIsWritable);
        dest.writeBoolean(mIsAvailable);
    }

    /**
     * Builder for {@link BluetoothHapPresetInfo}.
     *
     * <p>By default, the preset index will be set to {@link
     * BluetoothHapClient#PRESET_INDEX_UNAVAILABLE}, the name to an empty string, writability and
     * availability both to false.
     */
    @Hide
    public static final class Builder {
        private int mIndex = BluetoothHapClient.PRESET_INDEX_UNAVAILABLE;
        private String mName = "";
        private boolean mIsWritable = false;
        private boolean mIsAvailable = false;

        /**
         * Creates a new builder.
         *
         * @param index The preset index for HAP preset info
         * @param name The preset name for HAP preset info
         */
        public Builder(int index, @NonNull String name) {
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException(
                        "The size of the preset name for HAP shall be at"
                                + " least one character long.");
            }
            if (index < 0) {
                throw new IllegalArgumentException(
                        "Preset index for HAP shall be a non-negative value.");
            }

            mIndex = index;
            mName = name;
        }

        /**
         * Set preset writability for HAP preset info.
         *
         * @param isWritable whether preset is writable
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setWritable(boolean isWritable) {
            mIsWritable = isWritable;
            return this;
        }

        /**
         * Set preset availability for HAP preset info.
         *
         * @param isAvailable whether preset is currently available to select
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setAvailable(boolean isAvailable) {
            mIsAvailable = isAvailable;
            return this;
        }

        /**
         * Build {@link BluetoothHapPresetInfo}.
         *
         * @return new BluetoothHapPresetInfo built
         */
        @RequiresNoPermission
        public @NonNull BluetoothHapPresetInfo build() {
            return new BluetoothHapPresetInfo(mIndex, mName, mIsWritable, mIsAvailable);
        }
    }
}

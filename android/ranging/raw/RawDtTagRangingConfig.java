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

package android.ranging.raw;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.RangingConfig;
import com.android.ranging.flags.Flags;
import java.util.Objects;

 /* Defines the configuration for a DL-TDOA Tag ranging session.
 *
 * <p>This configuration is used to specify the parameters for a ranging session where the local
 * device is acting as a Uwb DL-TDOA tag.
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class RawDtTagRangingConfig extends RangingConfig implements Parcelable {
    private final RawRangingDevice mTagDevice;

    private RawDtTagRangingConfig(Builder builder) {
        mTagDevice = builder.mTagDevice;
    }

    private RawDtTagRangingConfig(Parcel in) {
        mTagDevice = in.readParcelable(RawRangingDevice.class.getClassLoader(),
                RawRangingDevice.class);
    }

    /**
     * Returns the {@link RawRangingDevice} representing the tag's configuration.
     */
    @NonNull
    public RawRangingDevice getDtTag() {
        return mTagDevice;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mTagDevice, flags);
    }

    @NonNull
    public static final Creator<RawDtTagRangingConfig> CREATOR =
            new Creator<RawDtTagRangingConfig>() {
        @Override
        public RawDtTagRangingConfig createFromParcel(Parcel in) {
            return new RawDtTagRangingConfig(in);
        }

        @Override
        public RawDtTagRangingConfig[] newArray(int size) {
            return new RawDtTagRangingConfig[size];
        }
    };

    /**
     * Builder for {@link RawDtTagRangingConfig}.
     */
    public static final class Builder {
        private final RawRangingDevice mTagDevice;

        /**
         * Creates a new Builder for {@link RawDtTagRangingConfig}.
         *
         * @param tagDevice The {@link RawRangingDevice} representing the tag.
         *                  This device must have {@link DlTdoaRangingParams} set.
         * @throws IllegalArgumentException if the tagDevice does not contain DlTdoaRangingParams.
         */
        public Builder(@NonNull RawRangingDevice tagDevice) {
            Objects.requireNonNull(tagDevice, "tagDevice cannot be null");
            if (tagDevice.getDlTdoaRangingParams() == null) {
                throw new IllegalArgumentException("Tag device must have DL-TDOA parameters.");
            }
            mTagDevice = tagDevice;
        }

        /**
         * Builds the {@link RawDtTagRangingConfig} instance.
         *
         * @return A new {@link RawDtTagRangingConfig} instance.
         */
        @NonNull
        public RawDtTagRangingConfig build() {
            return new RawDtTagRangingConfig(this);
        }
    }
}

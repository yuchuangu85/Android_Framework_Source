/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
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

import static java.util.Objects.requireNonNull;

import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/** This class contains the subgroup settings information for this Broadcast Subgroup. */
@Hide
@SystemApi
public final class BluetoothLeBroadcastSubgroupSettings implements Parcelable {
    private final @Quality int mPreferredQuality;
    private final BluetoothLeAudioContentMetadata mContentMetadata;

    /**
     * Audio quality for this broadcast subgroup
     *
     * <p>Audio quality for this broadcast subgroup.
     */
    @Hide
    @IntDef(
            prefix = "QUALITY_",
            value = {
                QUALITY_STANDARD,
                QUALITY_HIGH,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Quality {}

    /** Indicates standard quality for this subgroup audio configuration. */
    @Hide @SystemApi public static final int QUALITY_STANDARD = 0;

    /** Indicates high quality for this subgroup audio configuration. */
    @Hide @SystemApi public static final int QUALITY_HIGH = 1;

    private BluetoothLeBroadcastSubgroupSettings(
            int preferredQuality, BluetoothLeAudioContentMetadata contentMetadata) {
        mPreferredQuality = preferredQuality;
        mContentMetadata = contentMetadata;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof BluetoothLeBroadcastSubgroupSettings)) {
            return false;
        }
        final BluetoothLeBroadcastSubgroupSettings other = (BluetoothLeBroadcastSubgroupSettings) o;
        return mPreferredQuality == other.getPreferredQuality()
                && mContentMetadata.equals(other.getContentMetadata());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPreferredQuality, mContentMetadata);
    }

    /**
     * Get content metadata for this Broadcast Source subgroup.
     *
     * @return content metadata for this Broadcast Source subgroup
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @NonNull BluetoothLeAudioContentMetadata getContentMetadata() {
        return mContentMetadata;
    }

    /**
     * Get preferred audio quality for this Broadcast Source subgroup.
     *
     * @return preferred audio quality for this Broadcast Source subgroup
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @Quality int getPreferredQuality() {
        return mPreferredQuality;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mPreferredQuality);
        out.writeTypedObject(mContentMetadata, 0);
    }

    /**
     * A {@link Parcelable.Creator} to create {@link BluetoothLeBroadcastSubgroupSettings} from
     * parcel.
     */
    @Hide @SystemApi @NonNull
    public static final Creator<BluetoothLeBroadcastSubgroupSettings> CREATOR =
            new Creator<>() {
                public @NonNull BluetoothLeBroadcastSubgroupSettings createFromParcel(
                        @NonNull Parcel in) {
                    Builder builder = new Builder();
                    builder.setPreferredQuality(in.readInt());
                    builder.setContentMetadata(
                            in.readTypedObject(BluetoothLeAudioContentMetadata.CREATOR));
                    return builder.build();
                }

                public @NonNull BluetoothLeBroadcastSubgroupSettings[] newArray(int size) {
                    return new BluetoothLeBroadcastSubgroupSettings[size];
                }
            };

    /** Builder for {@link BluetoothLeBroadcastSubgroupSettings}. */
    @Hide
    @SystemApi
    public static final class Builder {
        private BluetoothLeAudioContentMetadata mContentMetadata = null;
        private @Quality int mPreferredQuality = QUALITY_STANDARD;

        /** Create an empty constructor. */
        @Hide
        @SystemApi
        public Builder() {}

        /**
         * Create a builder with copies of information from original object.
         *
         * @param original original object
         */
        @Hide
        @SystemApi
        public Builder(@NonNull BluetoothLeBroadcastSubgroupSettings original) {
            mPreferredQuality = original.getPreferredQuality();
            mContentMetadata = original.getContentMetadata();
        }

        /**
         * Set preferred audio quality for this Broadcast Source subgroup.
         *
         * @param preferredQuality audio quality for this Broadcast Source subgroup
         * @return this builder
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setPreferredQuality(@Quality int preferredQuality) {
            mPreferredQuality = preferredQuality;
            return this;
        }

        /**
         * Set content metadata for this Broadcast Source subgroup.
         *
         * @param contentMetadata content metadata for this Broadcast Source subgroup
         * @throws NullPointerException if contentMetadata is null
         * @return this builder
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setContentMetadata(
                @NonNull BluetoothLeAudioContentMetadata contentMetadata) {
            requireNonNull(contentMetadata);
            mContentMetadata = contentMetadata;
            return this;
        }

        /**
         * Build {@link BluetoothLeBroadcastSubgroupSettings}.
         *
         * @return constructed {@link BluetoothLeBroadcastSubgroupSettings}
         * @throws NullPointerException if {@link NonNull} items are null
         * @throws IllegalArgumentException if the object cannot be built
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull BluetoothLeBroadcastSubgroupSettings build() {
            requireNonNull(mContentMetadata);
            if (mPreferredQuality != QUALITY_STANDARD && mPreferredQuality != QUALITY_HIGH) {
                throw new IllegalArgumentException(
                        "Must set audio quality to either Standard or High");
            }
            return new BluetoothLeBroadcastSubgroupSettings(mPreferredQuality, mContentMetadata);
        }
    }
}

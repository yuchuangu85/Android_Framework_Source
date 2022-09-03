/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.audiopolicy.AudioVolumeGroup;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.List;
import java.util.Objects;

/**
 * @hide
 * A class to represent type of volume information.
 * Can be used to represent volume associated with a stream type or {@link AudioVolumeGroup}.
 * Volume index is optional when used to represent a category of volume.
 * Index ranges are supported too, making the representation of volume changes agnostic to the
 * range (e.g. can be used to map BT A2DP absolute volume range to internal range).
 *
 * Note: this class is not yet part of the SystemApi but is intended to be gradually introduced
 *       particularly in parts of the audio framework that suffer from code ambiguity when
 *       dealing with different volume ranges / units.
 */
public final class VolumeInfo implements Parcelable {
    private static final String TAG = "VolumeInfo";

    private final boolean mUsesStreamType; // false implies AudioVolumeGroup is used
    private final boolean mIsMuted;
    private final int mVolIndex;
    private final int mMinVolIndex;
    private final int mMaxVolIndex;
    private final int mVolGroupId;
    private final int mStreamType;

    private static IAudioService sService;
    private static VolumeInfo sDefaultVolumeInfo;

    private VolumeInfo(boolean usesStreamType, boolean isMuted, int volIndex,
            int minVolIndex, int maxVolIndex,
            int volGroupId, int streamType) {
        mUsesStreamType = usesStreamType;
        mIsMuted = isMuted;
        mVolIndex = volIndex;
        mMinVolIndex = minVolIndex;
        mMaxVolIndex = maxVolIndex;
        mVolGroupId = volGroupId;
        mStreamType = streamType;
    }

    /**
     * Indicates whether this instance has a stream type associated to it.
     * Note this method returning true implies {@link #hasVolumeGroup()} returns false.
     * (e.g. {@link AudioManager#STREAM_MUSIC}).
     * @return true if it has stream type information
     */
    public boolean hasStreamType() {
        return mUsesStreamType;
    }

    /**
     * Returns the associated stream type, or will throw if {@link #hasStreamType()} returned false.
     * @return a stream type value, see AudioManager.STREAM_*
     */
    public int getStreamType() {
        if (!mUsesStreamType) {
            throw new IllegalStateException("VolumeInfo doesn't use stream types");
        }
        return mStreamType;
    }

    /**
     * Indicates whether this instance has a {@link AudioVolumeGroup} associated to it.
     * Note this method returning true implies {@link #hasStreamType()} returns false.
     * @return true if it has volume group information
     */
    public boolean hasVolumeGroup() {
        return !mUsesStreamType;
    }

    /**
     * Returns the associated volume group, or will throw if {@link #hasVolumeGroup()} returned
     * false.
     * @return the volume group corresponding to this VolumeInfo, or null if an error occurred
     * in the volume group management
     */
    public @Nullable AudioVolumeGroup getVolumeGroup() {
        if (mUsesStreamType) {
            throw new IllegalStateException("VolumeInfo doesn't use AudioVolumeGroup");
        }
        List<AudioVolumeGroup> volGroups = AudioVolumeGroup.getAudioVolumeGroups();
        for (AudioVolumeGroup group : volGroups) {
            if (group.getId() == mVolGroupId) {
                return group;
            }
        }
        return null;
    }

    /**
     * Returns whether this instance is conveying a mute state.
     * @return true if the volume state is muted
     */
    public boolean isMuted() {
        return mIsMuted;
    }

    /**
     * A value used to express no volume index has been set.
     */
    public static final int INDEX_NOT_SET = -100;

    /**
     * Returns the volume index.
     * @return a volume index, or {@link #INDEX_NOT_SET} if no index was set, in which case this
     *      instance is used to express a volume representation type (stream vs group) and
     *      optionally its volume range
     */
    public int getVolumeIndex() {
        return mVolIndex;
    }

    /**
     * Returns the minimum volume index.
     * @return the minimum volume index, or {@link #INDEX_NOT_SET} if no minimum index was set.
     */
    public int getMinVolumeIndex() {
        return mMinVolIndex;
    }

    /**
     * Returns the maximum volume index.
     * @return the maximum volume index, or {@link #INDEX_NOT_SET} if no maximum index was
     *      set.
     */
    public int getMaxVolumeIndex() {
        return mMaxVolIndex;
    }

    /**
     * Returns the default info for the platform, typically initialized
     * to STREAM_MUSIC with min/max initialized to the associated range
     * @return the default VolumeInfo for the device
     */
    public static @NonNull VolumeInfo getDefaultVolumeInfo() {
        if (sService == null) {
            IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
            sService = IAudioService.Stub.asInterface(b);
        }
        if (sDefaultVolumeInfo == null) {
            try {
                sDefaultVolumeInfo = sService.getDefaultVolumeInfo();
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling getDefaultVolumeInfo", e);
                // return a valid value, but don't cache it
                return new VolumeInfo.Builder(AudioManager.STREAM_MUSIC).build();
            }
        }
        return sDefaultVolumeInfo;
    }

    /**
     * The builder class for creating and initializing, or copying and modifying VolumeInfo
     * instances
     */
    public static final class Builder {
        private boolean mUsesStreamType = true; // false implies AudioVolumeGroup is used
        private int mStreamType = AudioManager.STREAM_MUSIC;
        private boolean mIsMuted = false;
        private int mVolIndex = INDEX_NOT_SET;
        private int mMinVolIndex = INDEX_NOT_SET;
        private int mMaxVolIndex = INDEX_NOT_SET;
        private int mVolGroupId = -Integer.MIN_VALUE;

        /**
         * Builder constructor for stream type-based VolumeInfo
         */
        public Builder(int streamType) {
            // TODO validate stream type
            mUsesStreamType = true;
            mStreamType = streamType;
        }

        /**
         * Builder constructor for volume group-based VolumeInfo
         */
        public Builder(@NonNull AudioVolumeGroup volGroup) {
            Objects.requireNonNull(volGroup);
            mUsesStreamType = false;
            mStreamType = -Integer.MIN_VALUE;
            mVolGroupId = volGroup.getId();
        }

        /**
         * Builder constructor to copy a given VolumeInfo.
         * Note you can't change the stream type or volume group later.
         */
        public Builder(@NonNull VolumeInfo info) {
            Objects.requireNonNull(info);
            mUsesStreamType = info.mUsesStreamType;
            mStreamType = info.mStreamType;
            mIsMuted = info.mIsMuted;
            mVolIndex = info.mVolIndex;
            mMinVolIndex = info.mMinVolIndex;
            mMaxVolIndex = info.mMaxVolIndex;
            mVolGroupId = info.mVolGroupId;
        }

        /**
         * Sets whether the volume is in a muted state
         * @param isMuted
         * @return the same builder instance
         */
        public @NonNull Builder setMuted(boolean isMuted) {
            mIsMuted = isMuted;
            return this;
        }

        /**
         * Sets the volume index
         * @param volIndex a 0 or greater value, or {@link #INDEX_NOT_SET} if unknown
         * @return the same builder instance
         */
        // TODO should we allow muted true + volume index set? (useful when toggling mute on/off?)
        public @NonNull Builder setVolumeIndex(int volIndex) {
            if (volIndex != INDEX_NOT_SET && volIndex < 0) {
                throw new IllegalArgumentException("Volume index cannot be negative");
            }
            mVolIndex = volIndex;
            return this;
        }

        /**
         * Sets the minimum volume index
         * @param minIndex a 0 or greater value, or {@link #INDEX_NOT_SET} if unknown
         * @return the same builder instance
         */
        public @NonNull Builder setMinVolumeIndex(int minIndex) {
            if (minIndex != INDEX_NOT_SET && minIndex < 0) {
                throw new IllegalArgumentException("Min volume index cannot be negative");
            }
            mMinVolIndex = minIndex;
            return this;
        }

        /**
         * Sets the maximum volume index
         * @param maxIndex a 0 or greater value, or {@link #INDEX_NOT_SET} if unknown
         * @return the same builder instance
         */
        public @NonNull Builder setMaxVolumeIndex(int maxIndex) {
            if (maxIndex != INDEX_NOT_SET && maxIndex < 0) {
                throw new IllegalArgumentException("Max volume index cannot be negative");
            }
            mMaxVolIndex = maxIndex;
            return this;
        }

        /**
         * Builds the VolumeInfo with the data given to the builder
         * @return the new VolumeInfo instance
         */
        public @NonNull VolumeInfo build() {
            if (mVolIndex != INDEX_NOT_SET) {
                if (mMinVolIndex != INDEX_NOT_SET && mVolIndex < mMinVolIndex) {
                    throw new IllegalArgumentException("Volume index:" + mVolIndex
                            + " lower than min index:" + mMinVolIndex);
                }
                if (mMaxVolIndex != INDEX_NOT_SET && mVolIndex > mMaxVolIndex) {
                    throw new IllegalArgumentException("Volume index:" + mVolIndex
                            + " greater than max index:" + mMaxVolIndex);
                }
            }
            if (mMinVolIndex != INDEX_NOT_SET && mMaxVolIndex != INDEX_NOT_SET
                    && mMinVolIndex > mMaxVolIndex) {
                throw new IllegalArgumentException("Min volume index:" + mMinVolIndex
                        + " greater than max index:" + mMaxVolIndex);
            }
            return new VolumeInfo(mUsesStreamType, mIsMuted,
                    mVolIndex, mMinVolIndex, mMaxVolIndex,
                    mVolGroupId, mStreamType);
        }
    }

    //-----------------------------------------------
    // Parcelable
    @Override
    public int hashCode() {
        return Objects.hash(mUsesStreamType, mStreamType, mIsMuted,
                mVolIndex, mMinVolIndex, mMaxVolIndex, mVolGroupId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        VolumeInfo that = (VolumeInfo) o;
        return ((mUsesStreamType == that.mUsesStreamType)
                && (mStreamType == that.mStreamType)
            && (mIsMuted == that.mIsMuted)
            && (mVolIndex == that.mVolIndex)
            && (mMinVolIndex == that.mMinVolIndex)
            && (mMaxVolIndex == that.mMaxVolIndex)
            && (mVolGroupId == that.mVolGroupId));
    }

    @Override
    public String toString() {
        return new String("VolumeInfo:"
                + (mUsesStreamType ? (" streamType:" + mStreamType)
                    : (" volGroupId" + mVolGroupId))
                + " muted:" + mIsMuted
                + ((mVolIndex != INDEX_NOT_SET) ? (" volIndex:" + mVolIndex) : "")
                + ((mMinVolIndex != INDEX_NOT_SET) ? (" min:" + mMinVolIndex) : "")
                + ((mMaxVolIndex != INDEX_NOT_SET) ? (" max:" + mMaxVolIndex) : ""));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mUsesStreamType);
        dest.writeInt(mStreamType);
        dest.writeBoolean(mIsMuted);
        dest.writeInt(mVolIndex);
        dest.writeInt(mMinVolIndex);
        dest.writeInt(mMaxVolIndex);
        dest.writeInt(mVolGroupId);
    }

    private VolumeInfo(@NonNull Parcel in) {
        mUsesStreamType = in.readBoolean();
        mStreamType = in.readInt();
        mIsMuted = in.readBoolean();
        mVolIndex = in.readInt();
        mMinVolIndex = in.readInt();
        mMaxVolIndex = in.readInt();
        mVolGroupId = in.readInt();
    }

    public static final @NonNull Parcelable.Creator<VolumeInfo> CREATOR =
            new Parcelable.Creator<VolumeInfo>() {
                /**
                 * Rebuilds a VolumeInfo previously stored with writeToParcel().
                 * @param p Parcel object to read the VolumeInfo from
                 * @return a new VolumeInfo created from the data in the parcel
                 */
                public VolumeInfo createFromParcel(Parcel p) {
                    return new VolumeInfo(p);
                }

                public VolumeInfo[] newArray(int size) {
                    return new VolumeInfo[size];
                }
            };
}

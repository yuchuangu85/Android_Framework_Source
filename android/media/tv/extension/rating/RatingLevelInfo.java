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

package android.media.tv.extension.rating;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a single rating level within a rating dimension.
 * <p>
 * This class holds the full text description, the abbreviation and the block status to reflect the
 * user's parental control.
 * </p>
 * @hide
 */
public final class RatingLevelInfo implements Parcelable {
    private String mLevelText;
    private String mLevelAbbrText;
    private boolean mIsBlocked;

    public RatingLevelInfo(String levelText, String levelAbbrText, boolean isBlocked) {
        this.mLevelText = levelText;
        this.mLevelAbbrText = levelAbbrText;
        this.mIsBlocked = isBlocked;
    }

    /**
     * Gets the full text description of the rating level.
     *
     * @return The display text.
     */
    @NonNull
    public String getLevelText() {
        if (mLevelText == null) {
            return "";
        }
        return mLevelText;
    }

    /**
     * Sets the full text description of the rating level.
     *
     * @param levelText The display text to set.
     */
    public void setLevelText(@NonNull String levelText) {
        this.mLevelText = levelText;
    }

    /**
     * Gets the abbreviated text of the rating level.
     *
     * @return The abbreviation string.
     */
    @NonNull
    public String getLevelAbbrText() {
        if (mLevelAbbrText == null) {
            return "";
        }
        return mLevelAbbrText;
    }

    /**
     * Sets the abbreviated text of the rating level.
     *
     * @param levelAbbrText The abbreviation string to set.
     */
    public void setLevelAbbrText(@NonNull String levelAbbrText) {
        this.mLevelAbbrText = levelAbbrText;
    }

    /**
     * Checks if this rating level is blocked by the user.
     *
     * @return true if the content is blocked, false otherwise.
     */
    public boolean isBlocked() {
        return mIsBlocked;
    }

    /**
     * Sets the blocked status for this rating level.
     *
     * @param blocked true to block this content, false to allow it.
     */
    public void setBlocked(boolean blocked) {
        mIsBlocked = blocked;
    }

    private RatingLevelInfo(Parcel in) {
        mLevelText = in.readString8();
        mLevelAbbrText = in.readString8();
        mIsBlocked = in.readByte() != 0; // Reading boolean
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString8(mLevelText);
        dest.writeString8(mLevelAbbrText);
        dest.writeByte((byte) (mIsBlocked ? 1 : 0)); // Writing boolean
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RatingLevelInfo> CREATOR = new Creator<RatingLevelInfo>() {
        @Override
        public RatingLevelInfo createFromParcel(Parcel in) {
            return new RatingLevelInfo(in);
        }

        @Override
        public RatingLevelInfo[] newArray(int size) {
            return new RatingLevelInfo[size];
        }
    };
}

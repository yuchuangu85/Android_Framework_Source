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

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a rating dimension in the Regional Rating Table.
 * <p>
 * A dimension defines a specific criteria for rating and contain a list of {@link RatingLevelInfo}
 * objects defining the specific rating levels.
 * </p>
 * @hide
 */
public final class RatingDimInfo implements Parcelable {
    private int mRatingLevelNumber;
    private boolean mIsDimGrad;
    private String mDimText;
    private List<RatingLevelInfo> mRatingLevelList;

    public RatingDimInfo(int ratingLevelNumber, boolean isDimGrad, @NonNull String dimText,
            @NonNull List<RatingLevelInfo> ratingLevelList) {
        this.mRatingLevelNumber = ratingLevelNumber;
        this.mIsDimGrad = isDimGrad;
        this.mDimText = dimText;
        this.mRatingLevelList = ratingLevelList;
    }

    /**
     * Gets the number of rating levels in this dimension.
     *
     * @return The count of levels.
     */
    public int getRatingLevelNumber() {
        return mRatingLevelNumber;
    }

    /**
     * Sets the number of rating levels.
     *
     * @param ratingLevelNumber The count to set.
     */
    public void setRatingLevelNumber(int ratingLevelNumber) {
        this.mRatingLevelNumber = ratingLevelNumber;
    }

    /**
     * Checks if the dimension is graduated.
     *
     * @return true if graduated, false otherwise.
     */
    public boolean isDimGrad() {
        return mIsDimGrad;
    }

    /**
     * Sets whether the dimension is graduated.
     *
     * @param dimGrad true if graduated.
     */
    public void setDimGrad(boolean dimGrad) {
        mIsDimGrad = dimGrad;
    }

    /**
     * Gets the display text for this dimension.
     *
     * @return The dimension name.
     */
    @NonNull
    public String getDimText() {
        if (mDimText == null) {
            return "";
        }
        return mDimText;
    }

    /**
     * Sets the display text for this dimension.
     *
     * @param dimText The dimension name to set.
     */
    public void setDimText(@NonNull String dimText) {
        this.mDimText = dimText;
    }

    /**
     * Gets the list of rating levels associated with this dimension.
     *
     * @return A list of {@link RatingLevelInfo}.
     */
    @NonNull
    public List<RatingLevelInfo> getRatingLevelList() {
        return new ArrayList<>(mRatingLevelList);
    }

    /**
     * Sets the list of rating levels.
     *
     * @param ratingLevelList The list of levels to set.
     */
    public void setRatingLevelList(@NonNull List<RatingLevelInfo> ratingLevelList) {
        this.mRatingLevelList = new ArrayList<>(ratingLevelList);
    }

    private RatingDimInfo(Parcel in) {
        mRatingLevelNumber = in.readInt();
        mIsDimGrad = in.readByte() != 0;
        mDimText = in.readString8();
        mRatingLevelList = in.createTypedArrayList(RatingLevelInfo.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRatingLevelNumber);
        dest.writeByte((byte) (mIsDimGrad ? 1 : 0));
        dest.writeString8(mDimText);
        dest.writeTypedList(mRatingLevelList);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RatingDimInfo> CREATOR = new Creator<RatingDimInfo>() {
        @Override
        public RatingDimInfo createFromParcel(Parcel in) {
            return new RatingDimInfo(in);
        }

        @Override
        public RatingDimInfo[] newArray(int size) {
            return new RatingDimInfo[size];
        }
    };
}

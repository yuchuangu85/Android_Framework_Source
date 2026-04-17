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
 * Represents a Regional Rating Table (RRT) region information.
 * <p>
 * A region contains metadata (ID, Text, Version) and a list of dimensions
 * {@link RatingDimInfo} that define the rating structure for that region.
 * </p>
 * @hide
 */
public final class RatingRegionInfo implements Parcelable {
    private int mRatingRegionId;
    private String mRatingRegionText;
    private int mRatingRegionVersion;
    private int mRatingDimNumber;
    private List<RatingDimInfo> mRatingDimInfoList;

    public RatingRegionInfo(int ratingRegionId, String ratingRegionText, int ratingRegionVersion,
            int ratingDimNumber, List<RatingDimInfo> ratingDimInfoList) {
        this.mRatingRegionId = ratingRegionId;
        this.mRatingRegionText = ratingRegionText;
        this.mRatingRegionVersion = ratingRegionVersion;
        this.mRatingDimNumber = ratingDimNumber;
        this.mRatingDimInfoList = ratingDimInfoList;
    }

    /**
     * Gets the unique ID of the rating region.
     *
     * @return The region ID.
     */
    public int getRatingRegionId() {
        return mRatingRegionId;
    }

    /**
     * Sets the unique ID of the rating region.
     *
     * @param ratingRegionId The region ID to set.
     */
    public void setRatingRegionId(int ratingRegionId) {
        this.mRatingRegionId = ratingRegionId;
    }

    /**
     * Gets the display text for the rating region.
     *
     * @return The region name.
     */
    @NonNull
    public String getRatingRegionText() {
        return mRatingRegionText;
    }

    /**
     * Sets the display text for the rating region.
     *
     * @param ratingRegionText The region name to set.
     */
    public void setRatingRegionText(@NonNull String ratingRegionText) {
        this.mRatingRegionText = ratingRegionText;
    }

    /**
     * Gets the version of the rating region table.
     *
     * @return The version number.
     */
    public int getRatingRegionVersion() {
        return mRatingRegionVersion;
    }

    /**
     * Sets the version of the rating region table.
     * @param ratingRegionVersion The version number to set.
     */
    public void setRatingRegionVersion(int ratingRegionVersion) {
        this.mRatingRegionVersion = ratingRegionVersion;
    }

    /**
     * Gets the count of dimensions in this region.
     *
     * @return The number of dimensions.
     */
    public int getRatingDimNumber() {
        return mRatingDimNumber;
    }

    /**
     * Sets the count of dimensions in this region.
     *
     * @param ratingDimNumber The number of dimensions to set.
     */
    public void setRatingDimNumber(int ratingDimNumber) {
        this.mRatingDimNumber = ratingDimNumber;
    }

    /**
     * Gets the list of rating dimensions for this region.
     *
     * @return A list of {@link RatingDimInfo}.
     */
    @NonNull
    public List<RatingDimInfo> getRatingDimInfoList() {
        return new ArrayList<>(mRatingDimInfoList);
    }

    /**
     * Sets the list of rating dimensions for this region.
     *
     * @param ratingDimInfoList The list of dimensions to set.
     */
    public void setRatingDimInfoList(@NonNull List<RatingDimInfo> ratingDimInfoList) {
        this.mRatingDimInfoList = new ArrayList<>(ratingDimInfoList);
    }

    private RatingRegionInfo(Parcel in) {
        mRatingRegionId = in.readInt();
        mRatingRegionText = in.readString8();
        mRatingRegionVersion = in.readInt();
        mRatingDimNumber = in.readInt();
        mRatingDimInfoList = in.createTypedArrayList(RatingDimInfo.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRatingRegionId);
        dest.writeString8(mRatingRegionText);
        dest.writeInt(mRatingRegionVersion);
        dest.writeInt(mRatingDimNumber);
        dest.writeTypedList(mRatingDimInfoList);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RatingRegionInfo> CREATOR = new Creator<RatingRegionInfo>() {
        @Override
        public RatingRegionInfo createFromParcel(Parcel in) {
            return new RatingRegionInfo(in);
        }

        @Override
        public RatingRegionInfo[] newArray(int size) {
            return new RatingRegionInfo[size];
        }
    };
}

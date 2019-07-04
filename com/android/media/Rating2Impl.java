/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.media;

import static android.media.Rating2.*;

import android.content.Context;
import android.media.Rating2;
import android.media.Rating2.Style;
import android.media.update.Rating2Provider;
import android.os.Bundle;
import android.util.Log;

import java.util.Objects;

public final class Rating2Impl implements Rating2Provider {
    private static final String TAG = "Rating2";

    private static final String KEY_STYLE = "android.media.rating2.style";
    private static final String KEY_VALUE = "android.media.rating2.value";

    private final static float RATING_NOT_RATED = -1.0f;

    private final Rating2 mInstance;
    private final int mRatingStyle;
    private final float mRatingValue;

    private Rating2Impl(@Style int ratingStyle, float rating) {
        mRatingStyle = ratingStyle;
        mRatingValue = rating;
        mInstance = new Rating2(this);
    }

    @Override
    public String toString_impl() {
        return "Rating2:style=" + mRatingStyle + " rating="
                + (mRatingValue < 0.0f ? "unrated" : String.valueOf(mRatingValue));
    }

    @Override
    public boolean equals_impl(Object obj) {
        if (!(obj instanceof Rating2)) {
            return false;
        }
        Rating2Impl other = (Rating2Impl) ((Rating2) obj).getProvider();
        return mRatingStyle == other.mRatingStyle
                && mRatingValue == other.mRatingValue;
    }

    @Override
    public int hashCode_impl() {
        return Objects.hash(mRatingStyle, mRatingValue);
    }

    Rating2 getInstance() {
        return mInstance;
    }

    public static Rating2 fromBundle_impl(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        return new Rating2Impl(bundle.getInt(KEY_STYLE), bundle.getFloat(KEY_VALUE)).getInstance();
    }

    public Bundle toBundle_impl() {
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_STYLE, mRatingStyle);
        bundle.putFloat(KEY_VALUE, mRatingValue);
        return bundle;
    }

    public static Rating2 newUnratedRating_impl(@Style int ratingStyle) {
        switch(ratingStyle) {
            case RATING_HEART:
            case RATING_THUMB_UP_DOWN:
            case RATING_3_STARS:
            case RATING_4_STARS:
            case RATING_5_STARS:
            case RATING_PERCENTAGE:
                return new Rating2Impl(ratingStyle, RATING_NOT_RATED).getInstance();
            default:
                return null;
        }
    }

    public static Rating2 newHeartRating_impl(boolean hasHeart) {
        return new Rating2Impl(RATING_HEART, hasHeart ? 1.0f : 0.0f).getInstance();
    }

    public static Rating2 newThumbRating_impl(boolean thumbIsUp) {
        return new Rating2Impl(RATING_THUMB_UP_DOWN, thumbIsUp ? 1.0f : 0.0f).getInstance();
    }

    public static Rating2 newStarRating_impl(int starRatingStyle, float starRating) {
        float maxRating = RATING_NOT_RATED;
        switch(starRatingStyle) {
            case RATING_3_STARS:
                maxRating = 3.0f;
                break;
            case RATING_4_STARS:
                maxRating = 4.0f;
                break;
            case RATING_5_STARS:
                maxRating = 5.0f;
                break;
            default:
                Log.e(TAG, "Invalid rating style (" + starRatingStyle + ") for a star rating");
                return null;
        }
        if ((starRating < 0.0f) || (starRating > maxRating)) {
            Log.e(TAG, "Trying to set out of range star-based rating");
            return null;
        }
        return new Rating2Impl(starRatingStyle, starRating).getInstance();
    }

    public static Rating2 newPercentageRating_impl(float percent) {
        if ((percent < 0.0f) || (percent > 100.0f)) {
            Log.e(TAG, "Invalid percentage-based rating value");
            return null;
        } else {
            return new Rating2Impl(RATING_PERCENTAGE, percent).getInstance();
        }
    }

    @Override
    public boolean isRated_impl() {
        return mRatingValue >= 0.0f;
    }

    @Override
    public int getRatingStyle_impl() {
        return mRatingStyle;
    }

    @Override
    public boolean hasHeart_impl() {
        if (mRatingStyle != RATING_HEART) {
            return false;
        } else {
            return (mRatingValue == 1.0f);
        }
    }

    @Override
    public boolean isThumbUp_impl() {
        if (mRatingStyle != RATING_THUMB_UP_DOWN) {
            return false;
        } else {
            return (mRatingValue == 1.0f);
        }
    }

    @Override
    public float getStarRating_impl() {
        switch (mRatingStyle) {
            case RATING_3_STARS:
            case RATING_4_STARS:
            case RATING_5_STARS:
                if (mInstance.isRated()) {
                    return mRatingValue;
                }
            default:
                return -1.0f;
        }
    }

    @Override
    public float getPercentRating_impl() {
        if ((mRatingStyle != RATING_PERCENTAGE) || !mInstance.isRated()) {
            return -1.0f;
        } else {
            return mRatingValue;
        }
    }
}

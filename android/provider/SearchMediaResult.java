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

package android.provider;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.database.CursorWindow;

import com.android.providers.media.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents a single media item returned from a search query via
 * {@link SearchMediaService}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_MEDIA_SEARCH)
@SystemApi
public final class SearchMediaResult {

    /**
     * The total number of columns this object writes to a {@link CursorWindow}.
     */
    public static final int CURSOR_WINDOW_COLUMN_COUNT = 4;

    /**
     * The column index for the {@link #mId} field in a {@link CursorWindow}.
     */
    public static final int INDEX_COLUMN_ID = 0;

    /**
     * The column index for the {@link #mDateTaken} field in a {@link CursorWindow}.
     */
    public static final int INDEX_COLUMN_DATE_TAKEN = 1;

    /**
     * The column index for the {@link #mScore} field in a {@link CursorWindow}.
     */
    public static final int INDEX_COLUMN_SCORE = 2;

    /**
     * The column index for the {@link #mMediaType} field in a {@link CursorWindow}.
     */
    public static final int INDEX_COLUMN_MEDIA_TYPE = 3;

    /**
     * Defines the possible column index values for CursorWindow operations.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            INDEX_COLUMN_ID,
            INDEX_COLUMN_DATE_TAKEN,
            INDEX_COLUMN_SCORE,
            INDEX_COLUMN_MEDIA_TYPE
    })
    public @interface ColumnIndex {}

    /**
     * The ID of the media item, as defined in {@link MediaStore.MediaColumns#_ID}.
     */
    private final long mId;

    /**
     * The date the media item was taken, in milliseconds since the Unix epoch.
     * As defined in {@link MediaStore.MediaColumns#DATE_TAKEN}.
     */
    private final long mDateTaken;

    /**
     * The score associated with this media result for the given search query.
     * <p>
     * A higher score implies a more relevant result.
     */
    private final double mScore;

    /**
     * The media type of the item.
     * As defined in {@link android.provider.MediaStore.Files.FileColumns#MEDIA_TYPE}.
     */
    private final long mMediaType;

    /**
     * Creates a new search result.
     *
     * @param id The stable ID of the media item.
     * @param dateTaken The date the media item was taken, in milliseconds.
     * @param score The score associated for this result.
     * @param mediaType The media type of the item.
     */
    public SearchMediaResult(long id, long dateTaken, double score,
            long mediaType) {
        this.mId = id;
        this.mDateTaken = dateTaken;
        this.mScore = score;
        this.mMediaType = mediaType;
    }

    public long getId() {
        return mId;
    }

    public long getDateTaken() {
        return mDateTaken;
    }

    public double getScore() {
        return mScore;
    }

    public long getMediaType() {
        return mMediaType;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        SearchMediaResult result = (SearchMediaResult) o;
        return mId == result.mId && mDateTaken == result.mDateTaken && Double.compare(mScore,
                result.mScore) == 0 && mMediaType == result.mMediaType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mDateTaken, mScore, mMediaType);
    }

    @Override
    public String toString() {
        return "SearchMediaResult{"
                + "id=" + mId
                + ", dateTaken=" + mDateTaken
                + ", score=" + mScore
                + ", mediaType=" + mMediaType
                + '}';
    }
}

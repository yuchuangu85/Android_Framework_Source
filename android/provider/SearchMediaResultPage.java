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

import static android.provider.SearchMediaResult.CURSOR_WINDOW_COLUMN_COUNT;
import static android.provider.SearchMediaResult.INDEX_COLUMN_DATE_TAKEN;
import static android.provider.SearchMediaResult.INDEX_COLUMN_ID;
import static android.provider.SearchMediaResult.INDEX_COLUMN_MEDIA_TYPE;
import static android.provider.SearchMediaResult.INDEX_COLUMN_SCORE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.database.CursorWindow;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.providers.media.flags.Flags;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single page of media search results returned from {@link SearchMediaService}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_MEDIA_SEARCH)
@SystemApi
public final class SearchMediaResultPage implements Parcelable {
    private static final String CURSOR_WINDOW_NAME = "search_media_cursor_window";

    /**
     * The unique ID that identifies the search request this page belongs to.
     */
    @NonNull
    private final String mSearchId;

    /**
     * The {@link SearchMediaResult} having search results for this page.
     */
    @NonNull
    private final List<SearchMediaResult> mSearchResults;

    /**
     * A {@link Bundle} containing additional metadata about the results.
     *
     */
    @NonNull
    private final Bundle mExtras;


    /**
     * Creates a new page of search results.
     *
     * <p>
     * <b>Expected keys for the {@code extras} Bundle:</b>
     * <ul>
     * <li><b>{@code EXTRA_NEXT_PAGE_TOKEN}</b> ({@code String}): A token required for
     * fetching the next page of search results. The caller should pass this token
     * as-is in the {@code searchParams} when querying for the next page.</li>
     * </ul>
     * </p>
     *
     * @param searchId The unique ID of the search request.
     * @param searchResults The list of search results for this page.
     * @param extras A Bundle containing extra metadata
     */
    public SearchMediaResultPage(@NonNull String searchId,
            @NonNull List<SearchMediaResult> searchResults, @NonNull Bundle extras) {
        mSearchId = Objects.requireNonNull(searchId);
        mSearchResults = Objects.requireNonNull(searchResults);
        mExtras = Objects.requireNonNull(extras);
    }

    private SearchMediaResultPage(Parcel in) {
        mSearchId = in.readString();
        try (CursorWindow cursorWindow = in.readParcelable(CursorWindow.class.getClassLoader())) {
            mSearchResults = convertToSearchMediaResultList(cursorWindow);
        }
        mExtras = in.readBundle();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mSearchId);
        try (CursorWindow cursorWindow = convertToCursorWindow(mSearchResults)) {
            dest.writeParcelable(cursorWindow, flags);
        }
        dest.writeBundle(mExtras);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<SearchMediaResultPage> CREATOR =
            new Creator<SearchMediaResultPage>() {
                @Override
                public SearchMediaResultPage createFromParcel(Parcel in) {
                    return new SearchMediaResultPage(in);
                }

                @Override
                public SearchMediaResultPage[] newArray(int size) {
                    return new SearchMediaResultPage[size];
                }
            };

    /**
     * Returns the unique ID that identifies the search request this page belongs to.
     */
    @NonNull
    public String getSearchId() {
        return mSearchId;
    }


    /**
     * Returns the list of search results for this page.
     */
    @NonNull
    public List<SearchMediaResult> getSearchResults() {
        return mSearchResults;
    }


    /**
     * Returns a {@link Bundle} containing additional metadata about the results.
     *
     * <p>
     * <b>Expected keys for the {@code extras} Bundle:</b>
     * <ul>
     * <li><b>{@code EXTRA_NEXT_PAGE_TOKEN}</b> ({@code String}): A token required for
     * fetching the next page of search results. The caller should pass this token
     * as-is in the {@code searchParams} when querying for the next page.</li>
     * </ul>
     * </p>
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Utility method to convert list of {@link SearchMediaResult} to {@link CursorWindow}
     */
    @NonNull
    private static CursorWindow convertToCursorWindow(@NonNull List<SearchMediaResult>
            searchResults) {
        CursorWindow cursorWindow = new CursorWindow(CURSOR_WINDOW_NAME);
        cursorWindow.setNumColumns(CURSOR_WINDOW_COLUMN_COUNT);

        for (int i = 0; i < searchResults.size(); i++) {
            SearchMediaResult result = searchResults.get(i);
            if (result == null) {
                continue;
            }
            cursorWindow.allocRow();
            writeToCursorWindow(cursorWindow, i, result);
        }

        return cursorWindow;
    }

    @NonNull
    private static List<SearchMediaResult> convertToSearchMediaResultList(CursorWindow window) {
        List<SearchMediaResult> searchResults = new ArrayList<>();

        int numRowsInWindow = window.getNumRows();
        for (int row = 0; row < numRowsInWindow; row++) {
            long id = window.getLong(row, INDEX_COLUMN_ID);
            long dateTaken = window.getLong(row, INDEX_COLUMN_DATE_TAKEN);
            double score = window.getDouble(row, INDEX_COLUMN_SCORE);
            long mediaType = window.getLong(row, INDEX_COLUMN_MEDIA_TYPE);
            searchResults.add(new SearchMediaResult(id, dateTaken, score, mediaType));
        }

        return searchResults;
    }

    /**
     * Writes the contents of this object to a row in a {@link CursorWindow}.
     *
     * @param window The {@link CursorWindow} to write to.
     * @param row The target row index.
     * @hide
     */
    private static void writeToCursorWindow(@NonNull CursorWindow window, int row,
            SearchMediaResult searchMediaResult) {
        window.putLong(searchMediaResult.getId(), row, INDEX_COLUMN_ID);
        window.putLong(searchMediaResult.getDateTaken(), row, INDEX_COLUMN_DATE_TAKEN);
        window.putDouble(searchMediaResult.getScore(), row, INDEX_COLUMN_SCORE);
        window.putLong(searchMediaResult.getMediaType(), row, INDEX_COLUMN_MEDIA_TYPE);
    }
}

/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.service.dreams;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the state of allowed dreams and the currently active dream.
 *
 * @hide
 */
public final class DreamPlaylist implements Parcelable {
    /** The index used when no dream is currently active. */
    public static final int NO_ACTIVE_DREAM_INDEX = -1;

    /**
     * An empty playlist.
     *
     * @hide
     */
    public static final DreamPlaylist EMPTY =
            new DreamPlaylist(Collections.emptyList(), NO_ACTIVE_DREAM_INDEX);

    private final List<DreamItem> mDreams;
    private final int mActiveIndex;

    /**
     * Creates a new DreamPlaylist.
     *
     * @param dreams The list of allowed dream components.
     * @param activeIndex The index of the currently active dream within the list. If no dream is
     *     active or the list is empty, pass {@link #NO_ACTIVE_DREAM_INDEX}.
     */
    public DreamPlaylist(@NonNull List<DreamItem> dreams, int activeIndex) {
        mDreams = new ArrayList<>(Objects.requireNonNull(dreams));
        for (DreamItem dream : mDreams) {
            Objects.requireNonNull(dream, "Dream list cannot contain null elements");
        }
        if (activeIndex != NO_ACTIVE_DREAM_INDEX) {
            Preconditions.checkArgumentInRange(
                    activeIndex,
                    0,
                    dreams.size() - 1,
                    "activeIndex must be within the bounds of the dreams list (dreams.size="
                            + dreams.size()
                            + ", activeIndex="
                            + activeIndex
                            + ")");
        }
        mActiveIndex = activeIndex;
    }

    private DreamPlaylist(Parcel in) {
        mDreams = in.createTypedArrayList(DreamItem.CREATOR);
        mActiveIndex = in.readInt();
    }

    /** Returns the currently active dream item, or null if none is active. */
    @Nullable
    public DreamItem getActiveDream() {
        if (mActiveIndex >= 0 && mActiveIndex < mDreams.size()) {
            return mDreams.get(mActiveIndex);
        }
        return null;
    }

    /**
     * Returns the next dream item in the playlist, wrapping around to the beginning. Returns null
     * if the playlist is empty.
     */
    @Nullable
    public DreamItem getNextDream() {
        if (mDreams.isEmpty()) {
            return null;
        }
        if (mActiveIndex == NO_ACTIVE_DREAM_INDEX) {
            return mDreams.get(0);
        }
        final int nextIndex = (mActiveIndex + 1) % mDreams.size();
        return mDreams.get(nextIndex);
    }

    /**
     * Returns the previous dream item in the playlist, wrapping around to the end. Returns null if
     * the playlist is empty.
     */
    @Nullable
    public DreamItem getPreviousDream() {
        if (mDreams.isEmpty()) {
            return null;
        }
        if (mActiveIndex == NO_ACTIVE_DREAM_INDEX) {
            return mDreams.get(mDreams.size() - 1);
        }
        final int prevIndex = (mActiveIndex - 1 + mDreams.size()) % mDreams.size();
        return mDreams.get(prevIndex);
    }

    /** Returns the list of all allowed dream items. */
    @NonNull
    public List<DreamItem> getDreams() {
        return Collections.unmodifiableList(mDreams);
    }

    /**
     * Checks if the playlist contains the given component name.
     *
     * @param componentName the component name to check
     * @return true if the playlist contains the component
     */
    public boolean contains(@Nullable ComponentName componentName) {
        if (componentName == null) {
            return false;
        }
        for (DreamItem item : mDreams) {
            if (item.componentName.equals(componentName)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the index of the currently active dream. */
    public int getActiveIndex() {
        return mActiveIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DreamPlaylist)) return false;
        DreamPlaylist that = (DreamPlaylist) o;
        return mActiveIndex == that.mActiveIndex && Objects.equals(mDreams, that.mDreams);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDreams, mActiveIndex);
    }

    @Override
    public String toString() {
        return "DreamPlaylist{" + "mDreams=" + mDreams + ", mActiveIndex=" + mActiveIndex + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes the list of dreams and the index of the currently active dream to the parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mDreams);
        dest.writeInt(mActiveIndex);
    }

    public static final @NonNull Creator<DreamPlaylist> CREATOR =
            new Creator<DreamPlaylist>() {
                @Override
                public DreamPlaylist createFromParcel(Parcel in) {
                    return new DreamPlaylist(in);
                }

                @Override
                public DreamPlaylist[] newArray(int size) {
                    return new DreamPlaylist[size];
                }
            };
}

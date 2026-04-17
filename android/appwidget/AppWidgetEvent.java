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

package android.appwidget;

import static android.appwidget.flags.Flags.FLAG_ENGAGEMENT_METRICS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArraySet;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * An immutable class that describes the event data for an app widget interaction event.
 */
@FlaggedApi(FLAG_ENGAGEMENT_METRICS)
public final class AppWidgetEvent implements Parcelable {
    /**
     * Max number of clicked and scrolled IDs stored per event.
     * @hide
     */
    public static final int MAX_NUM_ITEMS = 10;

    private final int mAppWidgetId;
    @NonNull
    private final Duration mVisibleDuration;
    @NonNull
    private final Instant mStart;
    @NonNull
    private final Instant mEnd;
    @Nullable
    private final Rect mPosition;
    @Nullable
    private final int[] mClickedIds;
    @Nullable
    private final int[] mScrolledIds;

    /**
     * The app widget ID of the widget that generated this event.
     *
     * @see AppWidgetManager#getAppWidgetInfo(int)
     */
    public int getAppWidgetId() {
        return mAppWidgetId;
    }

    /**
     * Describes the total duration of time during which the widget was visible. This may be
     * different than the event time range (between {@link #getStart()} and {@link #getEnd()} if the
     * widget was hidden and shown multiple times during the event time range.
     */
    @NonNull
    public Duration getVisibleDuration() {
        return mVisibleDuration;
    }

    /**
     * Describes the start of the time range that this event contains data for.
     */
    @NonNull
    public Instant getStart() {
        return mStart;
    }

    /**
     * Describes the end of the time range that this event contains data for.
     */
    @NonNull
    public Instant getEnd() {
        return mEnd;
    }

    /**
     * This rect with describes the global coordinates of the widget at the end of the event time
     * range.
     */
    @Nullable
    public Rect getPosition() {
        return mPosition;
    }

    /**
     * This returns the set of View IDs of the views which have been clicked during the event time
     * range. Use {@link android.widget.RemoteViews#setAppWidgetEventTag(int, int)} to set a custom
     * integer tag on a view for reporting clicks. If the tag is set, it will be used here instead
     * of the View ID.
     */
    @Nullable
    public int[] getClickedIds() {
        return mClickedIds;
    }

    /**
     * This returns the set of View IDs of the views which have been scrolled during the event time
     * range. Use {@link android.widget.RemoteViews#setAppWidgetEventTag(int, int)} to set a custom
     * integer tag on a view for reporting scrolls. If the tag is set, it will be used here instead
     * of the View ID.
     */
    @Nullable
    public int[] getScrolledIds() {
        return mScrolledIds;
    }

    private AppWidgetEvent(int appWidgetId, long visibleDurationMillis, long start, long end,
            @Nullable Rect position, @Nullable int[] clickedIds, @Nullable int[] scrolledIds) {
        mAppWidgetId = appWidgetId;
        mVisibleDuration = Duration.ofMillis(visibleDurationMillis);
        mStart = Instant.ofEpochMilli(start);
        mEnd = Instant.ofEpochMilli(end);
        mPosition = position;
        mClickedIds = clickedIds;
        mScrolledIds = scrolledIds;
    }

    /**
     * Unflatten the AppWidgetEvent from a parcel.
     */
    private AppWidgetEvent(@NonNull Parcel in) {
        mAppWidgetId = in.readInt();
        mVisibleDuration = Duration.ofMillis(in.readLong());
        mStart = Instant.ofEpochMilli(in.readLong());
        mEnd = Instant.ofEpochMilli(in.readLong());
        mPosition = in.readTypedObject(Rect.CREATOR);
        mClickedIds = in.createIntArray();
        mScrolledIds = in.createIntArray();
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mAppWidgetId);
        out.writeLong(mVisibleDuration.toMillis());
        out.writeLong(mStart.toEpochMilli());
        out.writeLong(mEnd.toEpochMilli());
        out.writeTypedObject(mPosition, flags);
        out.writeIntArray(mClickedIds);
        out.writeIntArray(mScrolledIds);
    }

    /**
     * Parcelable.Creator that instantiates AppWidgetEvent objects
     */
    public static final @android.annotation.NonNull Parcelable.Creator<AppWidgetEvent> CREATOR =
            new Parcelable.Creator<>() {
                public AppWidgetEvent createFromParcel(Parcel parcel) {
                    return new AppWidgetEvent(parcel);
                }

                public AppWidgetEvent[] newArray(int size) {
                    return new AppWidgetEvent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Create a PersistableBundle that represents this event.
     * @hide
     */
    @NonNull
    public PersistableBundle toBundle() {
        PersistableBundle extras = new PersistableBundle();
        extras.putString(UsageStatsManager.EXTRA_EVENT_ACTION,
                AppWidgetManager.EVENT_TYPE_WIDGET_INTERACTION);
        extras.putString(UsageStatsManager.EXTRA_EVENT_CATEGORY,
                AppWidgetManager.EVENT_CATEGORY_APPWIDGET);
        extras.putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
        extras.putLong(AppWidgetManager.EXTRA_EVENT_DURATION_MS, mVisibleDuration.toMillis());
        extras.putLong(AppWidgetManager.EXTRA_EVENT_START, mStart.toEpochMilli());
        extras.putLong(AppWidgetManager.EXTRA_EVENT_END, mEnd.toEpochMilli());
        if (mPosition != null) {
            extras.putIntArray(AppWidgetManager.EXTRA_EVENT_POSITION_RECT,
                new int[]{mPosition.left, mPosition.top, mPosition.right, mPosition.bottom});
        }
        if (mClickedIds != null && mClickedIds.length > 0) {
            extras.putIntArray(AppWidgetManager.EXTRA_EVENT_CLICKED_VIEWS, mClickedIds);
        }
        if (mScrolledIds != null && mScrolledIds.length > 0) {
            extras.putIntArray(AppWidgetManager.EXTRA_EVENT_SCROLLED_VIEWS, mScrolledIds);
        }
        return extras;
    }

    /**
     * Create an AppWidgetEvent from a {@link UsageEvents.Event}.
     * @hide
     */
    @NonNull
    public static AppWidgetEvent fromUsageEvent(@NonNull UsageEvents.Event usageEvent) {
        PersistableBundle extras = usageEvent.getExtras();
        int appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        long durationMillis = extras.getLong(AppWidgetManager.EXTRA_EVENT_DURATION_MS, 0L);
        long start = extras.getLong(AppWidgetManager.EXTRA_EVENT_START, 0L);
        long end = extras.getLong(AppWidgetManager.EXTRA_EVENT_END, 0L);
        Rect position = null;
        int[] clickedIds = null;
        int[] scrolledIds = null;
        if (extras.containsKey(AppWidgetManager.EXTRA_EVENT_POSITION_RECT)) {
            int[] positionArray = extras.getIntArray(AppWidgetManager.EXTRA_EVENT_POSITION_RECT);
            if (positionArray != null && positionArray.length == 4) {
                position = new Rect(positionArray[0], positionArray[1], positionArray[2],
                        positionArray[3]);
            }
        }
        if (extras.containsKey(AppWidgetManager.EXTRA_EVENT_CLICKED_VIEWS)) {
            clickedIds = extras.getIntArray(AppWidgetManager.EXTRA_EVENT_CLICKED_VIEWS);
        }
        if (extras.containsKey(AppWidgetManager.EXTRA_EVENT_SCROLLED_VIEWS)) {
            scrolledIds = extras.getIntArray(AppWidgetManager.EXTRA_EVENT_SCROLLED_VIEWS);
        }
        return new AppWidgetEvent(appWidgetId, durationMillis, start, end, position, clickedIds,
            scrolledIds);
    }

    @Override
    public String toString() {
        return TextUtils.formatSimple("AppWidgetEvent(appWidgetId=%d, duration=%s, start=%s, "
                + "end=%s position=%s, clickedIds=%s, scrolledIds=%s)", mAppWidgetId,
            mVisibleDuration, mStart, mEnd, mPosition, Arrays.toString(mClickedIds),
            Arrays.toString(mScrolledIds));
    }

    /**
     * Returns true if the given {@link UsageEvents.Event} contains an app widget interaction event.
     * @hide
     */
    public static boolean isAppWidgetEvent(@NonNull UsageEvents.Event event) {
        return event.getEventType() == UsageEvents.Event.USER_INTERACTION
            && event.getExtras().getString(UsageStatsManager.EXTRA_EVENT_ACTION).equals(
                AppWidgetManager.EVENT_TYPE_WIDGET_INTERACTION)
            && event.getExtras().containsKey(AppWidgetManager.EXTRA_APPWIDGET_ID);
    }

    /**
     * Builder class to construct AppWidgetEvent objects.
     *
     * @hide
     */
    public static class Builder {
        @NonNull
        private final ArraySet<Integer> mClickedIds = new ArraySet<>(MAX_NUM_ITEMS);
        @NonNull
        private final ArraySet<Integer> mScrolledIds = new ArraySet<>(MAX_NUM_ITEMS);
        private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        private long mStart = Long.MAX_VALUE;
        private long mEnd = Long.MIN_VALUE;
        private long mDurationMillis = 0L;
        private long mLastVisibilityChangeMillis = 0L;
        @Nullable
        private Rect mPosition = null;

        public Builder() {
        }

        public Builder setAppWidgetId(int appWidgetId) {
            mAppWidgetId = appWidgetId;
            return this;
        }

        /**
         * Start a new visibility duration for this event.
         */
        public Builder startVisibility() {
            long now = System.currentTimeMillis();
            if (now < mStart) {
                mStart = now;
            }
            mLastVisibilityChangeMillis = SystemClock.uptimeMillis();
            return this;
        }

        /**
         * End the visibility duration, and add the duration to this event's total duration.
         */
        public Builder endVisibility() {
            long now = System.currentTimeMillis();
            if (now > mEnd) {
                mEnd = now;
            }
            mDurationMillis += SystemClock.uptimeMillis() - mLastVisibilityChangeMillis;
            return this;
        }

        public Builder setPosition(@Nullable Rect position) {
            mPosition = position;
            return this;
        }

        public Builder addClickedId(int id) {
            if (mClickedIds.size() < MAX_NUM_ITEMS) {
                mClickedIds.add(id);
            }
            return this;
        }

        public Builder addScrolledId(int id) {
            if (mScrolledIds.size() < MAX_NUM_ITEMS) {
                mScrolledIds.add(id);
            }
            return this;
        }

        /**
         * Merge the given event's data into this event's data.
         */
        public void merge(@Nullable AppWidgetEvent event) {
            if (event == null) {
                return;
            }

            if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                setAppWidgetId(event.getAppWidgetId());
            } else if (mAppWidgetId != event.getAppWidgetId()) {
                throw new IllegalArgumentException("Trying to merge events with different app "
                    + "widget IDs: " + mAppWidgetId + " != " + event.getAppWidgetId());
            }
            if (event.getStart().toEpochMilli() < mStart) {
                mStart = event.getStart().toEpochMilli();
            }
            if (event.getEnd().toEpochMilli() > mEnd) {
                mEnd = event.getEnd().toEpochMilli();
            }
            mDurationMillis += event.getVisibleDuration().toMillis();
            setPosition(event.getPosition());
            addAllUntilMax(mClickedIds, event.getClickedIds());
            addAllUntilMax(mScrolledIds, event.getScrolledIds());
        }

        /**
         * Returns true if the app widget ID has not been set, or if no data has been added to this
         * event yet.
         */
        public boolean isEmpty() {
            return mAppWidgetId <= 0 || mDurationMillis == 0;
        }

        /**
         * Resets the event data fields.
         */
        public void clear() {
            mDurationMillis = 0L;
            mStart = Long.MAX_VALUE;
            mEnd = Long.MIN_VALUE;
            mPosition = null;
            mClickedIds.clear();
            mScrolledIds.clear();
        }

        public AppWidgetEvent build() {
            return new AppWidgetEvent(mAppWidgetId, mDurationMillis, mStart, mEnd, mPosition,
                    toIntArray(mClickedIds), toIntArray(mScrolledIds));
        }

        private static void addAllUntilMax(@NonNull ArraySet<Integer> set, @Nullable int[] toAdd) {
            if (toAdd == null) {
                return;
            }
            for (int i = 0; i < toAdd.length && set.size() < MAX_NUM_ITEMS; i++) {
                set.add(toAdd[i]);
            }
        }

        @Nullable
        private static int[] toIntArray(@NonNull ArraySet<Integer> set) {
            if (set.isEmpty()) return null;
            int[] array = new int[set.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = set.valueAt(i);
            }
            return array;
        }
    }
}

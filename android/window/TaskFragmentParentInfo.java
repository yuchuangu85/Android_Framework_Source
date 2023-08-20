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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The information about the parent Task of a particular TaskFragment
 * @hide
 */
public class TaskFragmentParentInfo implements Parcelable {
    @NonNull
    private final Configuration mConfiguration = new Configuration();

    private final int mDisplayId;

    private final boolean mVisible;

    public TaskFragmentParentInfo(@NonNull Configuration configuration, int displayId,
            boolean visible) {
        mConfiguration.setTo(configuration);
        mDisplayId = displayId;
        mVisible = visible;
    }

    public TaskFragmentParentInfo(@NonNull TaskFragmentParentInfo info) {
        mConfiguration.setTo(info.getConfiguration());
        mDisplayId = info.mDisplayId;
        mVisible = info.mVisible;
    }

    /** The {@link Configuration} of the parent Task */
    @NonNull
    public Configuration getConfiguration() {
        return mConfiguration;
    }

    /**
     * The display ID of the parent Task. {@link android.view.Display#INVALID_DISPLAY} means the
     * Task is detached from previously associated display.
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    /** Whether the parent Task is visible or not */
    public boolean isVisible() {
        return mVisible;
    }

    /**
     * Returns {@code true} if the parameters which are important for task fragment
     * organizers are equal between this {@link TaskFragmentParentInfo} and {@code that}.
     * Note that this method is usually called with
     * {@link com.android.server.wm.WindowOrganizerController#configurationsAreEqualForOrganizer(
     * Configuration, Configuration)} to determine if this {@link TaskFragmentParentInfo} should
     * be dispatched to the client.
     */
    public boolean equalsForTaskFragmentOrganizer(@Nullable TaskFragmentParentInfo that) {
        if (that == null) {
            return false;
        }
        return getWindowingMode() == that.getWindowingMode() && mDisplayId == that.mDisplayId
                && mVisible == that.mVisible;
    }

    @WindowConfiguration.WindowingMode
    private int getWindowingMode() {
        return mConfiguration.windowConfiguration.getWindowingMode();
    }

    @Override
    public String toString() {
        return TaskFragmentParentInfo.class.getSimpleName() + ":{"
                + "config=" + mConfiguration
                + ", displayId=" + mDisplayId
                + ", visible=" + mVisible
                + "}";
    }

    /**
     * Indicates that whether this {@link TaskFragmentParentInfo} equals to {@code obj}.
     * Note that {@link #equalsForTaskFragmentOrganizer(TaskFragmentParentInfo)} should be used
     * for most cases because not all {@link Configuration} properties are interested for
     * {@link TaskFragmentOrganizer}.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof TaskFragmentParentInfo)) {
            return false;
        }
        final TaskFragmentParentInfo that = (TaskFragmentParentInfo) obj;
        return mConfiguration.equals(that.mConfiguration)
                && mDisplayId == that.mDisplayId
                && mVisible == that.mVisible;
    }

    @Override
    public int hashCode() {
        int result = mConfiguration.hashCode();
        result = 31 * result + mDisplayId;
        result = 31 * result + (mVisible ? 1 : 0);
        return result;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mConfiguration.writeToParcel(dest, flags);
        dest.writeInt(mDisplayId);
        dest.writeBoolean(mVisible);
    }

    private TaskFragmentParentInfo(Parcel in) {
        mConfiguration.readFromParcel(in);
        mDisplayId = in.readInt();
        mVisible = in.readBoolean();
    }

    public static final Creator<TaskFragmentParentInfo> CREATOR =
            new Creator<TaskFragmentParentInfo>() {
                @Override
                public TaskFragmentParentInfo createFromParcel(Parcel in) {
                    return new TaskFragmentParentInfo(in);
                }

                @Override
                public TaskFragmentParentInfo[] newArray(int size) {
                    return new TaskFragmentParentInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }
}

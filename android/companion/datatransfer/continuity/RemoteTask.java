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

package android.companion.datatransfer.continuity;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/**
 * This represents a task currently on another device owned by the user. This is returned by the
 * {@link TaskContinuityManager#getRemoteTasks()} method.
 *
 * <p>Consumers should use this class to display remote tasks to the user in an interface (such as
 * the device's launcher). When the user wishes to hand off this task to the current device, this
 * object can be passed back to {@link TaskContinuityManager#requestHandoff}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(android.companion.Flags.FLAG_TASK_CONTINUITY)
public final class RemoteTask implements Parcelable {

    private final int mTaskId;
    private final int mCompanionDeviceAssociationId;
    @Nullable private final String mLabel;
    private final boolean mIsHandoffEnabled;
    @Nullable private final String mAssociationDisplayName;
    private final long mLastUsedTimestampMillis;
    @Nullable private final Icon mIcon;
    private final boolean mIsTaskInForeground;
    @Nullable private final String mPackageName;

    public static final @NonNull Parcelable.Creator<RemoteTask> CREATOR =
            new Parcelable.Creator<RemoteTask>() {
                @Override
                public RemoteTask createFromParcel(Parcel in) {
                    return new RemoteTask(in);
                }

                @Override
                public RemoteTask[] newArray(int size) {
                    return new RemoteTask[size];
                }
            };

    RemoteTask(@NonNull Builder builder) {
        mTaskId = builder.mTaskId;
        mCompanionDeviceAssociationId = builder.mCompanionDeviceAssociationId;
        mLabel = builder.mLabel;
        mIcon = builder.mIcon;
        mIsHandoffEnabled = builder.mIsHandoffEnabled;
        mAssociationDisplayName = builder.mAssociationDisplayName;
        mLastUsedTimestampMillis = builder.mLastUsedTimestampMillis;
        mIsTaskInForeground = builder.mIsTaskInForeground;
        mPackageName = builder.mPackageName;
    }

    RemoteTask(@NonNull Parcel in) {
        Objects.requireNonNull(in);

        mTaskId = in.readInt();
        mCompanionDeviceAssociationId = in.readInt();
        if (in.readInt() != 0) {
            mLabel = in.readString();
        } else {
            mLabel = null;
        }

        mIsHandoffEnabled = in.readBoolean();
        if (in.readInt() != 0) {
            mAssociationDisplayName = in.readString();
        } else {
            mAssociationDisplayName = null;
        }

        mLastUsedTimestampMillis = in.readLong();
        if (in.readInt() != 0) {
            mIcon =
                    in.readParcelable(
                            Icon.class.getClassLoader(), android.graphics.drawable.Icon.class);
        } else {
            mIcon = null;
        }
        mIsTaskInForeground = in.readBoolean();
        if (in.readInt() != 0) {
            mPackageName = in.readString();
        } else {
            mPackageName = null;
        }
    }

    /**
     * Returns the ID of this task on the remote device. This is the same ID provided by {@link
     * ActivityManager.RunningTaskInfo#taskId} on the remote device.
     */
    public int getTaskId() {
        return mTaskId;
    }

    /** Returns the ID of the device association where this task is located. */
    public int getCompanionDeviceAssociationId() {
        return mCompanionDeviceAssociationId;
    }

    /**
     * Returns the label of this task on the remote device. This is the name of the {@link Activity}
     * represented by {@link ActivityTaskManager.RunningTaskInfo#baseActivity}
     */
    @Nullable
    public String getLabel() {
        return mLabel;
    }

    /**
     * Returns the icon of this task on the remote device. This is the icon of the {@link Activity}
     * represented by {@link ActivityTaskManager.RunningTaskInfo#baseActivity}
     */
    @Nullable
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * Returns if this task is eligible to be handed off to the current device. This indicates the
     * topmost activity of the task on the remote device has enabled Handoff via {@link
     * Activity.setHandoffEnabled}.
     */
    public boolean isHandoffEnabled() {
        return mIsHandoffEnabled;
    }

    /** Returns if this task is currently in the foreground on the remote device. */
    public boolean isTaskInForeground() {
        return mIsTaskInForeground;
    }

    /** Returns the package name of the top activity of the task on the remote device. */
    @Nullable
    public String getPackageName() {
        return mPackageName;
    }

    /** Returns the display name associated with this association. */
    @Nullable
    public String getAssociationDisplayName() {
        return mAssociationDisplayName;
    }

    /** Returns the last used timestamp of the task. */
    public long getLastUsedTimestampMillis() {
        return mLastUsedTimestampMillis;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mTaskId,
                mCompanionDeviceAssociationId,
                mLabel,
                mIcon,
                mIsHandoffEnabled,
                mAssociationDisplayName,
                mLastUsedTimestampMillis,
                mIsTaskInForeground,
                mPackageName);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof RemoteTask) {
            RemoteTask other = (RemoteTask) o;
            return mTaskId == other.mTaskId
                    && mCompanionDeviceAssociationId == other.mCompanionDeviceAssociationId
                    && Objects.equals(mLabel, other.mLabel)
                    && (mIcon == null ? other.mIcon == null : mIcon.sameAs(other.mIcon))
                    && mIsHandoffEnabled == other.mIsHandoffEnabled
                    && mLastUsedTimestampMillis == other.mLastUsedTimestampMillis
                    && Objects.equals(mAssociationDisplayName, other.mAssociationDisplayName)
                    && mIsTaskInForeground == other.mIsTaskInForeground
                    && Objects.equals(mPackageName, other.mPackageName);
        }

        return false;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        Objects.requireNonNull(dest);

        dest.writeInt(mTaskId);
        dest.writeInt(mCompanionDeviceAssociationId);
        if (mLabel != null) {
            dest.writeInt(1);
            dest.writeString(mLabel);
        } else {
            dest.writeInt(0);
        }
        dest.writeBoolean(mIsHandoffEnabled);
        if (mAssociationDisplayName != null) {
            dest.writeInt(1);
            dest.writeString(mAssociationDisplayName);
        } else {
            dest.writeInt(0);
        }
        dest.writeLong(mLastUsedTimestampMillis);
        if (mIcon != null) {
            dest.writeInt(1);
            dest.writeParcelable(mIcon, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeBoolean(mIsTaskInForeground);
        if (mPackageName != null) {
            dest.writeInt(1);
            dest.writeString(mPackageName);
        } else {
            dest.writeInt(0);
        }
    }

    /** Builder for {@link RemoteTask}. */
    public static final class Builder {
        private int mTaskId = 0;
        private int mCompanionDeviceAssociationId = 0;
        @Nullable private String mPackageName = null;
        @Nullable private String mLabel = null;
        @Nullable private Icon mIcon = null;
        @Nullable private String mAssociationDisplayName = null;
        private boolean mIsHandoffEnabled = false;
        private long mLastUsedTimestampMillis = 0;
        private boolean mIsTaskInForeground = false;

        /**
         * Creates a new builder for a remote task with the given ID.
         *
         * @param companionDeviceAssociationId The ID of the device association where this task is
         *     located.
         * @param taskId The ID of the task.
         */
        public Builder(int companionDeviceAssociationId, int taskId) {
            mCompanionDeviceAssociationId = companionDeviceAssociationId;
            mTaskId = taskId;
        }

        /**
         * Sets if the task is currently in the foreground on the remote device.
         *
         * @param isTaskInForeground Whether the task is currently in the foreground on the remote
         *     device.
         */
        @NonNull
        public Builder setTaskInForeground(boolean isTaskInForeground) {
            mIsTaskInForeground = isTaskInForeground;
            return this;
        }

        /**
         * Sets the label of the task.
         *
         * @param label The label of the task.
         */
        @NonNull
        public Builder setLabel(@Nullable String label) {
            mLabel = label;
            return this;
        }

        /**
         * Sets the icon of the task.
         *
         * @param icon The icon of the task.
         */
        @NonNull
        public Builder setIcon(@Nullable Icon icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets if the task is eligible to be handed off to the current device.
         *
         * @param isHandoffEnabled Whether the task is eligible to be handed off to the current
         *     device.
         */
        @NonNull
        public Builder setHandoffEnabled(boolean isHandoffEnabled) {
            mIsHandoffEnabled = isHandoffEnabled;
            return this;
        }

        /**
         * Sets the name of the association, if applicable.
         *
         * @param associationDisplayName The name of the source device.
         */
        @NonNull
        public Builder setAssociationDisplayName(@Nullable String associationDisplayName) {
            mAssociationDisplayName = associationDisplayName;
            return this;
        }

        /**
         * Sets the package name of the top activity of the task on the remote device.
         *
         * @param packageName The package name of the top activity of the task on the remote device.
         */
        @NonNull
        public Builder setPackageName(@Nullable String packageName) {
            mPackageName = packageName;
            return this;
        }

        /**
         * Sets the last used timestamp of the task.
         *
         * @param lastUsedTimestampMillis The last used timestamp of the remote task.
         */
        @NonNull
        public Builder setLastUsedTimestampMillis(long lastUsedTimestampMillis) {
            mLastUsedTimestampMillis = lastUsedTimestampMillis;
            return this;
        }

        /**
         * Builds the {@link RemoteTask} from the builder.
         *
         * @return The {@link RemoteTask} from the builder.
         */
        @NonNull
        public RemoteTask build() {
            return new RemoteTask(this);
        }
    }
}

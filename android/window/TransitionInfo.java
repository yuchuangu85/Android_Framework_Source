/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.transitTypeToString;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Used to communicate information about what is changing during a transition to a TransitionPlayer.
 * @hide
 */
public final class TransitionInfo implements Parcelable {

    /**
     * Modes are only a sub-set of all the transit-types since they are per-container
     * @hide
     */
    @IntDef(prefix = { "TRANSIT_" }, value = {
            TRANSIT_NONE,
            TRANSIT_OPEN,
            TRANSIT_CLOSE,
            // Note: to_front/to_back really mean show/hide respectively at the container level.
            TRANSIT_TO_FRONT,
            TRANSIT_TO_BACK,
            TRANSIT_CHANGE
    })
    public @interface TransitionMode {}

    /** No flags */
    public static final int FLAG_NONE = 0;

    /** The container shows the wallpaper behind it. */
    public static final int FLAG_SHOW_WALLPAPER = 1;

    /** The container IS the wallpaper. */
    public static final int FLAG_IS_WALLPAPER = 1 << 1;

    /** The container is translucent. */
    public static final int FLAG_TRANSLUCENT = 1 << 2;

    // TODO: remove when starting-window is moved to Task
    /** The container is the recipient of a transferred starting-window */
    public static final int FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT = 1 << 3;

    /** The container has voice session. */
    public static final int FLAG_IS_VOICE_INTERACTION = 1 << 4;

    /** The first unused bit. This can be used by remotes to attach custom flags to this change. */
    public static final int FLAG_FIRST_CUSTOM = 1 << 5;

    /** @hide */
    @IntDef(prefix = { "FLAG_" }, value = {
            FLAG_NONE,
            FLAG_SHOW_WALLPAPER,
            FLAG_IS_WALLPAPER,
            FLAG_TRANSLUCENT,
            FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT,
            FLAG_IS_VOICE_INTERACTION,
            FLAG_FIRST_CUSTOM
    })
    public @interface ChangeFlags {}

    private final @WindowManager.TransitionOldType int mType;
    private final @WindowManager.TransitionFlags int mFlags;
    private final ArrayList<Change> mChanges = new ArrayList<>();

    private SurfaceControl mRootLeash;
    private final Point mRootOffset = new Point();

    /** @hide */
    public TransitionInfo(@WindowManager.TransitionOldType int type,
            @WindowManager.TransitionFlags int flags) {
        mType = type;
        mFlags = flags;
    }

    private TransitionInfo(Parcel in) {
        mType = in.readInt();
        mFlags = in.readInt();
        in.readList(mChanges, null /* classLoader */);
        mRootLeash = new SurfaceControl();
        mRootLeash.readFromParcel(in);
        mRootOffset.readFromParcel(in);
    }

    @Override
    /** @hide */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mFlags);
        dest.writeList(mChanges);
        mRootLeash.writeToParcel(dest, flags);
        mRootOffset.writeToParcel(dest, flags);
    }

    @NonNull
    public static final Creator<TransitionInfo> CREATOR =
            new Creator<TransitionInfo>() {
                @Override
                public TransitionInfo createFromParcel(Parcel in) {
                    return new TransitionInfo(in);
                }

                @Override
                public TransitionInfo[] newArray(int size) {
                    return new TransitionInfo[size];
                }
            };

    @Override
    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @see #getRootLeash() */
    public void setRootLeash(@NonNull SurfaceControl leash, int offsetLeft, int offsetTop) {
        mRootLeash = leash;
        mRootOffset.set(offsetLeft, offsetTop);
    }

    public int getType() {
        return mType;
    }

    public int getFlags() {
        return mFlags;
    }

    /**
     * @return a surfacecontrol that can serve as a parent surfacecontrol for all the changing
     * participants to animate within. This will generally be placed at the highest-z-order
     * shared ancestor of all participants. While this is non-null, it's possible for the rootleash
     * to be invalid if the transition is a no-op.
     */
    @NonNull
    public SurfaceControl getRootLeash() {
        if (mRootLeash == null) {
            throw new IllegalStateException("Trying to get a leash which wasn't set");
        }
        return mRootLeash;
    }

    /** @return the offset (relative to the screen) of the root leash. */
    @NonNull
    public Point getRootOffset() {
        return mRootOffset;
    }

    @NonNull
    public List<Change> getChanges() {
        return mChanges;
    }

    /**
     * @return the Change that a window is undergoing or {@code null} if not directly
     * represented.
     */
    @Nullable
    public Change getChange(@NonNull WindowContainerToken token) {
        for (int i = mChanges.size() - 1; i >= 0; --i) {
            if (token.equals(mChanges.get(i).mContainer)) {
                return mChanges.get(i);
            }
        }
        return null;
    }

    /**
     * Add a {@link Change} to this transition.
     */
    public void addChange(@NonNull Change change) {
        mChanges.add(change);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{t=" + transitTypeToString(mType) + " f=" + Integer.toHexString(mFlags)
                + " ro=" + mRootOffset + " c=[");
        for (int i = 0; i < mChanges.size(); ++i) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(mChanges.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Converts a transition mode/action to its string representation. */
    @NonNull
    public static String modeToString(@TransitionMode int mode) {
        switch(mode) {
            case TRANSIT_NONE: return "NONE";
            case TRANSIT_OPEN: return "OPEN";
            case TRANSIT_CLOSE: return "CLOSE";
            case TRANSIT_TO_FRONT: return "SHOW";
            case TRANSIT_TO_BACK: return "HIDE";
            case TRANSIT_CHANGE: return "CHANGE";
            default: return "<unknown:" + mode + ">";
        }
    }

    /** Converts change flags into a string representation. */
    @NonNull
    public static String flagsToString(@ChangeFlags int flags) {
        if (flags == 0) return "NONE";
        final StringBuilder sb = new StringBuilder();
        if ((flags & FLAG_SHOW_WALLPAPER) != 0) {
            sb.append("SHOW_WALLPAPER");
        }
        if ((flags & FLAG_IS_WALLPAPER) != 0) {
            sb.append("IS_WALLPAPER");
        }
        if ((flags & FLAG_TRANSLUCENT) != 0) {
            sb.append((sb.length() == 0 ? "" : "|") + "TRANSLUCENT");
        }
        if ((flags & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0) {
            sb.append((sb.length() == 0 ? "" : "|") + "STARTING_WINDOW_TRANSFER");
        }
        if ((flags & FLAG_IS_VOICE_INTERACTION) != 0) {
            sb.append((sb.length() == 0 ? "" : "|") + "IS_VOICE_INTERACTION");
        }
        if ((flags & FLAG_FIRST_CUSTOM) != 0) {
            sb.append((sb.length() == 0 ? "" : "|") + "FIRST_CUSTOM");
        }
        return sb.toString();
    }

    /**
     * Indication that `change` is independent of parents (ie. it has a different type of
     * transition vs. "going along for the ride")
     */
    public static boolean isIndependent(@NonNull TransitionInfo.Change change,
            @NonNull TransitionInfo info) {
        // If the change has no parent (it is root), then it is independent
        if (change.getParent() == null) return true;

        // non-visibility changes will just be folded into the parent change, so they aren't
        // independent either.
        if (change.getMode() == TRANSIT_CHANGE) return false;

        TransitionInfo.Change parentChg = info.getChange(change.getParent());
        while (parentChg != null) {
            // If the parent is a visibility change, it will include the results of all child
            // changes into itself, so none of its children can be independent.
            if (parentChg.getMode() != TRANSIT_CHANGE) return false;

            // If there are no more parents left, then all the parents, so far, have not been
            // visibility changes which means this change is indpendent.
            if (parentChg.getParent() == null) return true;

            parentChg = info.getChange(parentChg.getParent());
        }
        return false;
    }

    /** Represents the change a WindowContainer undergoes during a transition */
    public static final class Change implements Parcelable {
        private final WindowContainerToken mContainer;
        private WindowContainerToken mParent;
        private final SurfaceControl mLeash;
        private @TransitionMode int mMode = TRANSIT_NONE;
        private @ChangeFlags int mFlags = FLAG_NONE;
        private final Rect mStartAbsBounds = new Rect();
        private final Rect mEndAbsBounds = new Rect();
        private final Point mEndRelOffset = new Point();
        private ActivityManager.RunningTaskInfo mTaskInfo = null;
        private int mStartRotation = ROTATION_UNDEFINED;
        private int mEndRotation = ROTATION_UNDEFINED;

        public Change(@Nullable WindowContainerToken container, @NonNull SurfaceControl leash) {
            mContainer = container;
            mLeash = leash;
        }

        private Change(Parcel in) {
            mContainer = in.readTypedObject(WindowContainerToken.CREATOR);
            mParent = in.readTypedObject(WindowContainerToken.CREATOR);
            mLeash = new SurfaceControl();
            mLeash.readFromParcel(in);
            mMode = in.readInt();
            mFlags = in.readInt();
            mStartAbsBounds.readFromParcel(in);
            mEndAbsBounds.readFromParcel(in);
            mEndRelOffset.readFromParcel(in);
            mTaskInfo = in.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
            mStartRotation = in.readInt();
            mEndRotation = in.readInt();
        }

        /** Sets the parent of this change's container. The parent must be a participant or null. */
        public void setParent(@Nullable WindowContainerToken parent) {
            mParent = parent;
        }

        /** Sets the transition mode for this change */
        public void setMode(@TransitionMode int mode) {
            mMode = mode;
        }

        /** Sets the flags for this change */
        public void setFlags(@ChangeFlags int flags) {
            mFlags = flags;
        }

        /** Sets the bounds this container occupied before the change in screen space */
        public void setStartAbsBounds(@Nullable Rect rect) {
            mStartAbsBounds.set(rect);
        }

        /** Sets the bounds this container will occupy after the change in screen space */
        public void setEndAbsBounds(@Nullable Rect rect) {
            mEndAbsBounds.set(rect);
        }

        /** Sets the offset of this container from its parent surface */
        public void setEndRelOffset(int left, int top) {
            mEndRelOffset.set(left, top);
        }

        /**
         * Sets the taskinfo of this container if this is a task. WARNING: this takes the
         * reference, so don't modify it afterwards.
         */
        public void setTaskInfo(@Nullable ActivityManager.RunningTaskInfo taskInfo) {
            mTaskInfo = taskInfo;
        }

        /** Sets the start and end rotation of this container. */
        public void setRotation(@Surface.Rotation int start, @Surface.Rotation int end) {
            mStartRotation = start;
            mEndRotation = end;
        }

        /** @return the container that is changing. May be null if non-remotable (eg. activity) */
        @Nullable
        public WindowContainerToken getContainer() {
            return mContainer;
        }

        /**
         * @return the parent of the changing container. This is the parent within the participants,
         * not necessarily the actual parent.
         */
        @Nullable
        public WindowContainerToken getParent() {
            return mParent;
        }

        /** @return which action this change represents. */
        public @TransitionMode int getMode() {
            return mMode;
        }

        /** @return the flags for this change. */
        public @ChangeFlags int getFlags() {
            return mFlags;
        }

        /**
         * @return the bounds of the container before the change. It may be empty if the container
         * is coming into existence.
         */
        @NonNull
        public Rect getStartAbsBounds() {
            return mStartAbsBounds;
        }

        /**
         * @return the bounds of the container after the change. It may be empty if the container
         * is disappearing.
         */
        @NonNull
        public Rect getEndAbsBounds() {
            return mEndAbsBounds;
        }

        /**
         * @return the offset of the container's surface from its parent surface after the change.
         */
        @NonNull
        public Point getEndRelOffset() {
            return mEndRelOffset;
        }

        /** @return the leash or surface to animate for this container */
        @NonNull
        public SurfaceControl getLeash() {
            return mLeash;
        }

        /** @return the task info or null if this isn't a task */
        @NonNull
        public ActivityManager.RunningTaskInfo getTaskInfo() {
            return mTaskInfo;
        }

        public int getStartRotation() {
            return mStartRotation;
        }

        public int getEndRotation() {
            return mEndRotation;
        }

        /** @hide */
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeTypedObject(mContainer, flags);
            dest.writeTypedObject(mParent, flags);
            mLeash.writeToParcel(dest, flags);
            dest.writeInt(mMode);
            dest.writeInt(mFlags);
            mStartAbsBounds.writeToParcel(dest, flags);
            mEndAbsBounds.writeToParcel(dest, flags);
            mEndRelOffset.writeToParcel(dest, flags);
            dest.writeTypedObject(mTaskInfo, flags);
            dest.writeInt(mStartRotation);
            dest.writeInt(mEndRotation);
        }

        @NonNull
        public static final Creator<Change> CREATOR =
                new Creator<Change>() {
                    @Override
                    public Change createFromParcel(Parcel in) {
                        return new Change(in);
                    }

                    @Override
                    public Change[] newArray(int size) {
                        return new Change[size];
                    }
                };

        /** @hide */
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public String toString() {
            return "{" + mContainer + "(" + mParent + ") leash=" + mLeash
                    + " m=" + modeToString(mMode) + " f=" + flagsToString(mFlags) + " sb="
                    + mStartAbsBounds + " eb=" + mEndAbsBounds + " eo=" + mEndRelOffset + " r="
                    + mStartRotation + "->" + mEndRotation + "}";
        }
    }
}

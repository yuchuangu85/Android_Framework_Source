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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowInsetsController;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.TransitionAnimation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/**
 * Represents a task snapshot.
 * @hide
 */
public class TaskSnapshot implements Parcelable {
    private static final String TAG = "TaskSnapshot";
    // Identifier of this snapshot
    private final long mId;
    // The elapsed real time (in nanoseconds) when this snapshot was captured or loaded from disk
    // since boot.
    private final long mCaptureTime;
    // Top activity in task when snapshot was taken
    private final ComponentName mTopActivityComponent;
    private final HardwareBuffer mSnapshot;
    /** Indicates whether task was in landscape or portrait */
    @Configuration.Orientation
    private final int mOrientation;
    /** See {@link android.view.Surface.Rotation} */
    @Surface.Rotation
    private final int mRotation;
    /** The size of the snapshot before scaling */
    private final Point mTaskSize;
    private final Rect mContentInsets;
    private final Rect mLetterboxInsets;
    // Whether this snapshot is a down-sampled version of the high resolution snapshot, used
    // mainly for loading snapshots quickly from disk when user is flinging fast
    private final boolean mIsLowResolution;
    // Whether or not the snapshot is a real snapshot or an app-theme generated snapshot due to
    // the task having a secure window or having previews disabled
    private final boolean mIsRealSnapshot;
    private final int mWindowingMode;
    private final @WindowInsetsController.Appearance
    int mAppearance;
    private final boolean mIsTranslucent;
    private final boolean mHasImeSurface;
    private final int mUiMode;
    private final int mDensityDpi;
    // Must be one of the named color spaces, otherwise, always use SRGB color space.
    private final ColorSpace mColorSpace;
    private int mInternalReferences;
    private int mWriteToParcelCount;
    private Consumer<HardwareBuffer> mSafeSnapshotReleaser;
    private WeakReference<TaskSnapshotManager.SnapshotTracker> mSnapshotTracker;

    /** Keep in cache, doesn't need reference. */
    public static final int REFERENCE_NONE = 0;
    /** This snapshot object is being broadcast. */
    public static final int REFERENCE_BROADCAST = 1;
    /** This snapshot object is in the cache. */
    public static final int REFERENCE_CACHE = 1 << 1;
    /** This snapshot object is being persistent. */
    public static final int REFERENCE_PERSIST = 1 << 2;
    /** This snapshot object is being used for content suggestion. */
    public static final int REFERENCE_CONTENT_SUGGESTION = 1 << 3;
    /** This snapshot object will be passing to external process. Keep the snapshot reference after
     * writeToParcel*/
    public static final int REFERENCE_WRITE_TO_PARCEL = 1 << 4;
    /** This snapshot object is being used to convert resolution . */
    public static final int REFERENCE_CONVERT_RESOLUTION = 1 << 5;
    /** This snapshot object should be update to cache at some point. */
    public static final int REFERENCE_WILL_UPDATE_TO_CACHE = 1 << 6;

    @IntDef(flag = true, prefix = { "REFERENCE_" }, value = {
            REFERENCE_NONE,
            REFERENCE_BROADCAST,
            REFERENCE_CACHE,
            REFERENCE_PERSIST,
            REFERENCE_CONTENT_SUGGESTION,
            REFERENCE_WRITE_TO_PARCEL,
            REFERENCE_CONVERT_RESOLUTION,
            REFERENCE_WILL_UPDATE_TO_CACHE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReferenceFlags {}

    public TaskSnapshot(long id, long captureTime,
            @NonNull ComponentName topActivityComponent, HardwareBuffer snapshot,
            @NonNull ColorSpace colorSpace, int orientation, int rotation, Point taskSize,
            Rect contentInsets, Rect letterboxInsets, boolean isLowResolution,
            boolean isRealSnapshot, int windowingMode,
            @WindowInsetsController.Appearance int appearance, boolean isTranslucent,
            boolean hasImeSurface, int uiMode, @IntRange(from = 1) int densityDpi) {
        mId = id;
        mCaptureTime = captureTime;
        mTopActivityComponent = topActivityComponent;
        mSnapshot = snapshot;
        mColorSpace = colorSpace.getId() < 0
                ? ColorSpace.get(ColorSpace.Named.SRGB) : colorSpace;
        mOrientation = orientation;
        mRotation = rotation;
        mTaskSize = new Point(taskSize);
        mContentInsets = new Rect(contentInsets);
        mLetterboxInsets = new Rect(letterboxInsets);
        mIsLowResolution = isLowResolution;
        mIsRealSnapshot = isRealSnapshot;
        mWindowingMode = windowingMode;
        mAppearance = appearance;
        mIsTranslucent = isTranslucent;
        mHasImeSurface = hasImeSurface;
        mUiMode = uiMode;
        mDensityDpi = densityDpi;
    }

    private TaskSnapshot(Parcel source) {
        mId = source.readLong();
        mTopActivityComponent = ComponentName.readFromParcel(source);
        mSnapshot = source.readTypedObject(HardwareBuffer.CREATOR);
        int colorSpaceId = source.readInt();
        mColorSpace = colorSpaceId >= 0 && colorSpaceId < ColorSpace.Named.values().length
                ? ColorSpace.get(ColorSpace.Named.values()[colorSpaceId])
                : ColorSpace.get(ColorSpace.Named.SRGB);
        mOrientation = source.readInt();
        mRotation = source.readInt();
        mTaskSize = source.readTypedObject(Point.CREATOR);
        mContentInsets = source.readTypedObject(Rect.CREATOR);
        mLetterboxInsets = source.readTypedObject(Rect.CREATOR);
        mIsLowResolution = source.readBoolean();
        mIsRealSnapshot = source.readBoolean();
        mWindowingMode = source.readInt();
        mAppearance = source.readInt();
        mIsTranslucent = source.readBoolean();
        mHasImeSurface = source.readBoolean();
        mUiMode = source.readInt();
        int densityDpi = source.readInt();
        mDensityDpi = densityDpi > 0 ? densityDpi : DisplayMetrics.DENSITY_DEVICE_STABLE;
        mCaptureTime = source.readLong();
    }

    /**
     * @return Identifier of this snapshot.
     */
    public long getId() {
        return mId;
    }

    /**
     * @return The elapsed real time (in nanoseconds) when this snapshot was captured. This time is
     * only valid in the process where this snapshot was taken.
     */
    public long getCaptureTime() {
        return mCaptureTime;
    }

    /**
     * @return The top activity component for the task at the point this snapshot was taken.
     */
    public ComponentName getTopActivityComponent() {
        return mTopActivityComponent;
    }

    /**
     * @return The hardware buffer representing the screenshot.
     * @deprecated Do not access hardware buffer directly.
     */
    @Deprecated
    public HardwareBuffer getHardwareBuffer() {
        Log.e(TAG, "getHardwareBuffer is deprecated!");
        return null;
    }

    /**
     * Returns the width from the hardware buffer.
     */
    public int getHardwareBufferWidth() {
        return mSnapshot.getWidth();
    }

    /**
     * Returns the height from the hardware buffer.
     */
    public int getHardwareBufferHeight() {
        return mSnapshot.getHeight();
    }

    /**
     * Returns the format from the hardware buffer.
     */
    public @HardwareBuffer.Format int getHardwareBufferFormat() {
        return mSnapshot.getFormat();
    }

    /**
     * Sets hardware buffer to a SurfaceControl.
     */
    public void setBufferToSurface(SurfaceControl.Transaction t,
            SurfaceControl surface) {
        if (!isBufferValid()) {
            return;
        }
        t.setBuffer(surface, mSnapshot);
    }

    /**
     * Creates a bitmap from the hardware buffer, this can return null if the hardware buffer is
     * closed or not exists.
     */
    public Bitmap wrapToBitmap() {
        if (!isBufferValid()) {
            return null;
        }
        return Bitmap.wrapHardwareBuffer(mSnapshot, mColorSpace);
    }

    /**
     * Creates a bitmap from the hardware buffer with specific ColorSpace. This can return null if
     * the hardware buffer is closed or not exists.
     */
    public Bitmap wrapToBitmap(@Nullable ColorSpace colorSpace) {
        if (!isBufferValid()) {
            return null;
        }
        return Bitmap.wrapHardwareBuffer(mSnapshot, colorSpace);
    }

    /**
     * Actively close hardware buffer.
     */
    public void closeBuffer() {
        if (isBufferValid()) {
            if (mSnapshotTracker != null) {
                final TaskSnapshotManager.SnapshotTracker tracker = mSnapshotTracker.get();
                if (tracker != null) {
                    TaskSnapshotManager.getInstance().removeTracker(tracker);
                }
            } else {
                mSnapshot.close();
            }
        }
    }

    /**
     * Returns whether the hardware buffer is valid.
     */
    public boolean isBufferValid() {
        return mSnapshot != null && !mSnapshot.isClosed();
    }

    /**
     * Returns whether the hardware buffer has protected content.
     */
    public boolean hasProtectedContent() {
        if (!isBufferValid()) {
            return false;
        }
        return TransitionAnimation.hasProtectedContent(mSnapshot);
    }

    /**
     * Attach the hardware buffer and color space to a Surface.
     */
    public void attachAndQueueBufferWithColorSpace(@NonNull Surface surface) {
        if (!isBufferValid()) {
            return;
        }
        surface.attachAndQueueBufferWithColorSpace(mSnapshot, mColorSpace);
    }

    /**
     * Test only
     */
    @VisibleForTesting
    public boolean isSameHardwareBuffer(@NonNull HardwareBuffer buffer) {
        return buffer == mSnapshot;
    }

    /**
     * @return The color space of hardware buffer representing the screenshot.
     */
    public ColorSpace getColorSpace() {
        return mColorSpace;
    }

    /**
     * @return The screen orientation the screenshot was taken in.
     */
    @UnsupportedAppUsage
    public int getOrientation() {
        return mOrientation;
    }

    /**
     * @return The screen rotation the screenshot was taken in.
     */
    public int getRotation() {
        return mRotation;
    }

    /**
     * @return The size of the task at the point this snapshot was taken.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public Point getTaskSize() {
        return mTaskSize;
    }

    /**
     * @return The system/content insets on the snapshot. These can be clipped off in order to
     *         remove any areas behind system bars in the snapshot.
     */
    @UnsupportedAppUsage
    public Rect getContentInsets() {
        return mContentInsets;
    }

    /**
     * @return The letterbox insets on the snapshot. These can be clipped off in order to
     *         remove any letterbox areas in the snapshot.
     */
    public Rect getLetterboxInsets() {
        return mLetterboxInsets;
    }

    /**
     * @return Whether this snapshot is a down-sampled version of the full resolution.
     */
    @UnsupportedAppUsage
    public boolean isLowResolution() {
        return mIsLowResolution;
    }

    /**
     * @return Whether or not the snapshot is a real snapshot or an app-theme generated snapshot
     * due to the task having a secure window or having previews disabled.
     */
    @UnsupportedAppUsage
    public boolean isRealSnapshot() {
        return mIsRealSnapshot;
    }

    /**
     * @return Whether or not the snapshot is of a translucent app window (non-fullscreen or has
     * a non-opaque pixel format).
     */
    public boolean isTranslucent() {
        return mIsTranslucent;
    }

    /**
     * @return Whether or not the snapshot has the IME surface.
     */
    public boolean hasImeSurface() {
        return mHasImeSurface;
    }

    /**
     * @return The windowing mode of the task when this snapshot was taken.
     */
    public int getWindowingMode() {
        return mWindowingMode;
    }

    /**
     * @return The {@link WindowInsetsController.Appearance} flags for the top most visible
     *         fullscreen window at the time that the snapshot was taken.
     */
    public @WindowInsetsController.Appearance
    int getAppearance() {
        return mAppearance;
    }

    /**
     * @return The uiMode the screenshot was taken in.
     */
    public int getUiMode() {
        return mUiMode;
    }

    /**
     * @return The pixel density the screenshot was taken in.
     */
    public int getDensityDpi() {
        return mDensityDpi;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mId);
        ComponentName.writeToParcel(mTopActivityComponent, dest);
        dest.writeTypedObject(mSnapshot != null && !mSnapshot.isClosed() ? mSnapshot : null, 0);
        dest.writeInt(mColorSpace.getId());
        dest.writeInt(mOrientation);
        dest.writeInt(mRotation);
        dest.writeTypedObject(mTaskSize, 0);
        dest.writeTypedObject(mContentInsets, 0);
        dest.writeTypedObject(mLetterboxInsets, 0);
        dest.writeBoolean(mIsLowResolution);
        dest.writeBoolean(mIsRealSnapshot);
        dest.writeInt(mWindowingMode);
        dest.writeInt(mAppearance);
        dest.writeBoolean(mIsTranslucent);
        dest.writeBoolean(mHasImeSurface);
        dest.writeInt(mUiMode);
        dest.writeInt(mDensityDpi);
        dest.writeLong(mCaptureTime);
        synchronized (this) {
            if ((mInternalReferences & REFERENCE_WRITE_TO_PARCEL) != 0) {
                mWriteToParcelCount--;
                if (mWriteToParcelCount == 0) {
                    removeReference(REFERENCE_WRITE_TO_PARCEL);
                }
            }
        }
    }

    @Override
    public String toString() {
        final String snapshotString;
        if (mSnapshot == null) {
            snapshotString = "null";
        } else if (mSnapshot.isClosed()) {
            snapshotString = "closed";
        } else {
            snapshotString = mSnapshot + " (" + mSnapshot.getWidth() + "x" + mSnapshot.getHeight()
                    + ")";
        }
        return "TaskSnapshot{"
                + " mId=" + mId
                + " mCaptureTime=" + mCaptureTime
                + " mTopActivityComponent=" + mTopActivityComponent.flattenToShortString()
                + " mSnapshot=" + snapshotString
                + " mColorSpace=" + mColorSpace.toString()
                + " mOrientation=" + mOrientation
                + " mRotation=" + mRotation
                + " mTaskSize=" + mTaskSize.toString()
                + " mContentInsets=" + mContentInsets.toShortString()
                + " mLetterboxInsets=" + mLetterboxInsets.toShortString()
                + " mIsLowResolution=" + mIsLowResolution
                + " mIsRealSnapshot=" + mIsRealSnapshot
                + " mWindowingMode=" + mWindowingMode
                + " mAppearance=" + mAppearance
                + " mIsTranslucent=" + mIsTranslucent
                + " mHasImeSurface=" + mHasImeSurface
                + " mInternalReferences=" + mInternalReferences
                + " mWriteToParcelCount=" + mWriteToParcelCount
                + " mUiMode=" + Integer.toHexString(mUiMode)
                + " mDensityDpi=" + mDensityDpi;
    }

    void setSnapshotTracker(TaskSnapshotManager.SnapshotTracker tracker) {
        if (tracker == null) {
            mSnapshotTracker = null;
        } else {
            mSnapshotTracker = new WeakReference<>(tracker);
        }
    }

    /**
     * Adds a reference when the object is held somewhere.
     * Only used in core.
     */
    public synchronized void addReference(@ReferenceFlags int usage) {
        if (usage == REFERENCE_WRITE_TO_PARCEL) {
            mWriteToParcelCount++;
        }
        mInternalReferences |= usage;
        if (usage == REFERENCE_CACHE) {
            mInternalReferences &= ~REFERENCE_WILL_UPDATE_TO_CACHE;
        }
    }

    /**
     * Removes a reference when the object is not held from somewhere. The snapshot will be closed
     * once the reference becomes zero.
     * Only used in core.
     */
    public synchronized void removeReference(@ReferenceFlags int usage) {
        mInternalReferences &= ~usage;
        if (mInternalReferences == 0 && mSnapshot != null && !mSnapshot.isClosed()) {
            if (mSafeSnapshotReleaser != null) {
                mSafeSnapshotReleaser.accept(mSnapshot);
            } else {
                mSnapshot.close();
            }
        }
    }

    /**
     * Register a safe release callback, instead of immediately closing the hardware buffer when
     * no more reference, to let the system server decide when to close it.
     * Only used in core.
     */
    public synchronized void setSafeRelease(Consumer<HardwareBuffer> releaser) {
        mSafeSnapshotReleaser = releaser;
    }

    public static final @NonNull Creator<TaskSnapshot> CREATOR = new Creator<TaskSnapshot>() {
        public TaskSnapshot createFromParcel(Parcel source) {
            return new TaskSnapshot(source);
        }
        public TaskSnapshot[] newArray(int size) {
            return new TaskSnapshot[size];
        }
    };

    /** Builder for a {@link TaskSnapshot} object */
    public static final class Builder {
        private long mId;
        private long mCaptureTime;
        private ComponentName mTopActivity;
        private HardwareBuffer mSnapshot;
        private ColorSpace mColorSpace;
        private int mOrientation;
        private int mRotation;
        private Point mTaskSize;
        private Rect mContentInsets;
        private Rect mLetterboxInsets;
        private boolean mIsRealSnapshot;
        private int mWindowingMode;
        private @WindowInsetsController.Appearance
        int mAppearance;
        private boolean mIsTranslucent;
        private boolean mHasImeSurface;
        private int mPixelFormat;
        private int mUiMode;
        private int mDensityDpi = DisplayMetrics.DENSITY_DEVICE_STABLE;

        public Builder setId(long id) {
            mId = id;
            return this;
        }

        public Builder setCaptureTime(long captureTime) {
            mCaptureTime = captureTime;
            return this;
        }

        public Builder setTopActivityComponent(ComponentName name) {
            mTopActivity = name;
            return this;
        }

        public Builder setSnapshot(HardwareBuffer buffer) {
            mSnapshot = buffer;
            return this;
        }

        public Builder setColorSpace(ColorSpace colorSpace) {
            mColorSpace = colorSpace;
            return this;
        }

        public Builder setOrientation(int orientation) {
            mOrientation = orientation;
            return this;
        }

        public Builder setRotation(int rotation) {
            mRotation = rotation;
            return this;
        }

        /**
         * Sets the original size of the task
         */
        public Builder setTaskSize(Point size) {
            mTaskSize = size;
            return this;
        }

        public Builder setContentInsets(Rect contentInsets) {
            mContentInsets = contentInsets;
            return this;
        }

        public Builder setLetterboxInsets(Rect letterboxInsets) {
            mLetterboxInsets = letterboxInsets;
            return this;
        }

        public Builder setIsRealSnapshot(boolean realSnapshot) {
            mIsRealSnapshot = realSnapshot;
            return this;
        }

        public Builder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        public Builder setAppearance(@WindowInsetsController.Appearance int appearance) {
            mAppearance = appearance;
            return this;
        }

        public Builder setIsTranslucent(boolean isTranslucent) {
            mIsTranslucent = isTranslucent;
            return this;
        }

        /**
         * Sets the IME visibility when taking the snapshot of the task.
         */
        public Builder setHasImeSurface(boolean hasImeSurface) {
            mHasImeSurface = hasImeSurface;
            return this;
        }

        /**
         * Sets the original uiMode while capturing
         */
        public Builder setUiMode(int uiMode) {
            mUiMode = uiMode;
            return this;
        }

        /**
         * Sets the original density while capturing. Throws IllegalArgumentException if
         * densityDpi is outside the range (0,100000) (exclusive).
         */
        public Builder setDensityDpi(@IntRange(from = 1) int densityDpi) {
            mDensityDpi = densityDpi;
            return this;
        }

        public int getPixelFormat() {
            return mPixelFormat;
        }

        public Builder setPixelFormat(int pixelFormat) {
            mPixelFormat = pixelFormat;
            return this;
        }

        public TaskSnapshot build() {
            return new TaskSnapshot(
                    mId,
                    mCaptureTime,
                    mTopActivity,
                    mSnapshot,
                    mColorSpace,
                    mOrientation,
                    mRotation,
                    mTaskSize,
                    mContentInsets,
                    mLetterboxInsets,
                    // When building a TaskSnapshot with the Builder class, isLowResolution
                    // is always false. Low-res snapshots are only created when loading from
                    // disk.
                    false /* isLowResolution */,
                    mIsRealSnapshot,
                    mWindowingMode,
                    mAppearance,
                    mIsTranslucent,
                    mHasImeSurface,
                    mUiMode,
                    mDensityDpi);

        }
    }
}

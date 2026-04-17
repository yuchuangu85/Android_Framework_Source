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

package android.view;

import static android.view.Surface.ROTATION_0;

import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.Path;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayUtils;
import android.util.PathParser;
import android.util.RotationUtils;

import androidx.annotation.NonNull;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * A class representing the shape of a display. It provides a {@link Path} of the display shape of
 * the display shape.
 *
 * {@link DisplayShape} is immutable.
 */
public final class DisplayShape implements Parcelable {

    private static final int TYPE_SPEC = 1;
    private static final int TYPE_RESOURCES = 2;
    private static final int TYPE_DEFAULT = 3;
    private static final int TYPE_NONE = 4;

    /** @hide */
    public static final DisplayShape NONE = new DisplayShape(TYPE_NONE, null /* displayUniqueId */,
            0 /* physicalDisplayWidth */, 0 /* physicalDisplayHeight */,
            false /* isRound */, null /* spec */, 0f /* specRatio */, 0 /* displayWidth */,
            0 /* displayHeight */, 0 /* rotation */, 0 /* offsetX */, 0 /* offsetY */,
            1f /* scale */);

    // For identifying the shape
    private final int mType;
    // For TYPE_RESOURCES
    private final String mDisplayUniqueId;
    private final int mPhysicalDisplayWidth;
    private final int mPhysicalDisplayHeight;
    // For TYPE_DEFAULT
    private final boolean mIsRound;
    // For TYPE_SPEC
    private final String mSpec;
    private final float mSpecRatio;

    // Common geometry
    private final int mDisplayWidth;
    private final int mDisplayHeight;

    // Transformations
    private final int mRotation;
    private final int mOffsetX;
    private final int mOffsetY;
    private final float mScale;

    // Lazily initialized.
    @GuardedBy("this")
    private boolean mInitialized;

    private float mPhysicalPixelDisplaySizeRatio;

    @GuardedBy("this")
    private Path mPath;

    private DisplayShape(int type, String displayUniqueId, int physicalDisplayWidth,
            int physicalDisplayHeight, boolean isRound, String spec, float specRatio,
            int displayWidth, int displayHeight, int rotation, int offsetX, int offsetY,
            float scale) {
        mType = type;
        mDisplayUniqueId = displayUniqueId;
        mPhysicalDisplayWidth = physicalDisplayWidth;
        mPhysicalDisplayHeight = physicalDisplayHeight;
        mIsRound = isRound;
        mSpec = spec;
        mSpecRatio = specRatio;
        mDisplayWidth = displayWidth;
        mDisplayHeight = displayHeight;
        mRotation = rotation;
        mOffsetX = offsetX;
        mOffsetY = offsetY;
        mScale = scale;
    }

    private synchronized void initializeIfNeeded() {
        if (mInitialized) {
            return;
        }

        switch (mType) {
            case TYPE_RESOURCES: {
                final Resources res = Resources.getSystem();
                final String spec = getSpecString(res, mDisplayUniqueId);
                if (spec == null || spec.isEmpty()) {
                    mPhysicalPixelDisplaySizeRatio = 1f;
                } else {
                    mPhysicalPixelDisplaySizeRatio = DisplayUtils.getPhysicalPixelDisplaySizeRatio(
                            mPhysicalDisplayWidth, mPhysicalDisplayHeight,
                            mDisplayWidth, mDisplayHeight);
                }
                break;
            }
            case TYPE_DEFAULT: {
                mPhysicalPixelDisplaySizeRatio = 1f;
                break;
            }
            case TYPE_SPEC: {
                mPhysicalPixelDisplaySizeRatio = mSpecRatio;
                break;
            }
            case TYPE_NONE: {
                // Nothing to do
                break;
            }
        }
        mInitialized = true;
    }

    /**
     * @hide
     */
    @NonNull
    public static DisplayShape fromResources(
            @NonNull String displayUniqueId, int physicalDisplayWidth,
            int physicalDisplayHeight, int displayWidth, int displayHeight) {
        return Cache.getDisplayShape(displayUniqueId, physicalDisplayWidth, physicalDisplayHeight,
                displayWidth, displayHeight);
    }

    /**
     * @hide
     */
    @NonNull
    public static DisplayShape createDefaultDisplayShape(
            int displayWidth, int displayHeight, boolean isScreenRound) {
        return Cache.getDisplayShape(displayWidth, displayHeight, isScreenRound);
    }

    /**
     * @hide
     */
    @TestApi
    @NonNull
    public static DisplayShape fromSpecString(@NonNull String spec,
            float physicalPixelDisplaySizeRatio, int displayWidth, int displayHeight) {
        return Cache.getDisplayShape(spec, physicalPixelDisplaySizeRatio, displayWidth,
                displayHeight);
    }

    private static String createDefaultSpecString(int displayWidth, int displayHeight,
            boolean isCircular) {
        final String spec;
        if (isCircular) {
            final float xRadius = displayWidth / 2f;
            final float yRadius = displayHeight / 2f;
            // Draw a circular display shape.
            spec = "M0," + yRadius
                    // Draw upper half circle with arcTo command.
                    + " A" + xRadius + "," + yRadius + " 0 1,1 " + displayWidth + "," + yRadius
                    // Draw lower half circle with arcTo command.
                    + " A" + xRadius + "," + yRadius + " 0 1,1 0," + yRadius + " Z";
        } else {
            // Draw a rectangular display shape.
            spec = "M0,0"
                    // Draw top edge.
                    + " L" + displayWidth + ",0"
                    // Draw right edge.
                    + " L" + displayWidth + "," + displayHeight
                    // Draw bottom edge.
                    + " L0," + displayHeight
                    // Draw left edge by close command which draws a line from current position to
                    // the initial points (0,0).
                    + " Z";
        }
        return spec;
    }

    /**
     * Gets the display shape svg spec string of a display which is determined by the given display
     * unique id.
     *
     * Loads the default config {@link R.string#config_mainDisplayShape} if
     * {@link R.array#config_displayUniqueIdArray} is not set.
     *
     * @hide
     */
    public static String getSpecString(Resources res, String displayUniqueId) {
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final TypedArray array = res.obtainTypedArray(R.array.config_displayShapeArray);
        final String spec;
        if (index >= 0 && index < array.length()) {
            spec = array.getString(index);
        } else {
            spec = res.getString(R.string.config_mainDisplayShape);
        }
        array.recycle();
        return spec;
    }

    /**
     * @hide
     */
    public DisplayShape setRotation(int rotation) {
        return new DisplayShape(mType, mDisplayUniqueId, mPhysicalDisplayWidth,
                mPhysicalDisplayHeight, mIsRound, mSpec, mSpecRatio, mDisplayWidth, mDisplayHeight,
                rotation, mOffsetX, mOffsetY, mScale);
    }

    /**
     * @hide
     */
    public DisplayShape setOffset(int offsetX, int offsetY) {
        return new DisplayShape(mType, mDisplayUniqueId, mPhysicalDisplayWidth,
                mPhysicalDisplayHeight, mIsRound, mSpec, mSpecRatio, mDisplayWidth, mDisplayHeight,
                mRotation, offsetX, offsetY, mScale);
    }

    /**
     * @hide
     */
    public DisplayShape setScale(float scale) {
        return new DisplayShape(mType, mDisplayUniqueId, mPhysicalDisplayWidth,
                mPhysicalDisplayHeight, mIsRound, mSpec, mSpecRatio, mDisplayWidth, mDisplayHeight,
                mRotation, mOffsetX, mOffsetY, scale);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mDisplayUniqueId, mPhysicalDisplayWidth, mPhysicalDisplayHeight,
                mIsRound, mSpec, mSpecRatio, mDisplayWidth, mDisplayHeight);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof DisplayShape) {
            DisplayShape ds = (DisplayShape) o;
            return mType == ds.mType
                    && Objects.equals(mDisplayUniqueId, ds.mDisplayUniqueId)
                    && mPhysicalDisplayWidth == ds.mPhysicalDisplayWidth
                    && mPhysicalDisplayHeight == ds.mPhysicalDisplayHeight
                    && mIsRound == ds.mIsRound
                    && Objects.equals(mSpec, ds.mSpec)
                    && mSpecRatio == ds.mSpecRatio
                    && mDisplayWidth == ds.mDisplayWidth && mDisplayHeight == ds.mDisplayHeight
                    && mRotation == ds.mRotation && mOffsetX == ds.mOffsetX
                    && mOffsetY == ds.mOffsetY && mScale == ds.mScale;
        }
        return false;
    }

    @Override
    public String toString() {
        initializeIfNeeded();
        return "DisplayShape{"
                + "type=" + mType
                + (mType == TYPE_SPEC ? (", spec=" + mSpec.hashCode()) : "")
                + ", displayWidth=" + mDisplayWidth
                + ", displayHeight=" + mDisplayHeight
                + ", physicalPixelDisplaySizeRatio=" + mPhysicalPixelDisplaySizeRatio
                + ", rotation=" + mRotation
                + ", offsetX=" + mOffsetX
                + ", offsetY=" + mOffsetY
                + ", scale=" + mScale + "}";
    }

    /**
     * Returns a {@link Path} of the display shape.
     *
     * @return a {@link Path} of the display shape.
     */
    @NonNull
    public Path getPath() {
        initializeIfNeeded();
        synchronized (this) {
            if (mPath != null) {
                return mPath;
            }

            final String displayShapeSpec;
            switch (mType) {
                case TYPE_RESOURCES: {
                    final Resources res = Resources.getSystem();
                    String spec = getSpecString(res, mDisplayUniqueId);
                    if (spec == null || spec.isEmpty()) {
                        final boolean isScreenRound = RoundedCorners.getBuiltInDisplayIsRound(
                                res, mDisplayUniqueId);
                        spec = createDefaultSpecString(mDisplayWidth, mDisplayHeight,
                                isScreenRound);
                    }
                    displayShapeSpec = spec;
                    break;
                }
                case TYPE_DEFAULT: {
                    displayShapeSpec = createDefaultSpecString(mDisplayWidth, mDisplayHeight,
                            mIsRound);
                    break;
                }
                case TYPE_SPEC: {
                    displayShapeSpec = mSpec;
                    break;
                }
                case TYPE_NONE:
                default: {
                    displayShapeSpec = "";
                    break;
                }
            }
            final Path path = PathParser.createPathFromPathData(displayShapeSpec);

            if (!path.isEmpty()) {
                final Matrix matrix = new Matrix();
                if (mRotation != ROTATION_0) {
                    RotationUtils.transformPhysicalToLogicalCoordinates(
                            mRotation, mDisplayWidth, mDisplayHeight, matrix);
                }
                if (mPhysicalPixelDisplaySizeRatio != 1f) {
                    matrix.preScale(mPhysicalPixelDisplaySizeRatio,
                            mPhysicalPixelDisplaySizeRatio);
                }
                if (mOffsetX != 0 || mOffsetY != 0) {
                    matrix.postTranslate(mOffsetX, mOffsetY);
                }
                if (mScale != 1f) {
                    matrix.postScale(mScale, mScale);
                }
                path.transform(matrix);
            }
            mPath = path;
            return mPath;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeString8(mDisplayUniqueId);
        dest.writeInt(mPhysicalDisplayWidth);
        dest.writeInt(mPhysicalDisplayHeight);
        dest.writeBoolean(mIsRound);
        dest.writeString8(mSpec);
        dest.writeFloat(mSpecRatio);
        dest.writeInt(mDisplayWidth);
        dest.writeInt(mDisplayHeight);
        dest.writeInt(mRotation);
        dest.writeInt(mOffsetX);
        dest.writeInt(mOffsetY);
        dest.writeFloat(mScale);
    }

    public static final @NonNull Creator<DisplayShape> CREATOR = new Creator<DisplayShape>() {
        @Override
        public DisplayShape createFromParcel(Parcel in) {
            final int type = in.readInt();
            final String displayUniqueId = in.readString8();
            final int physicalDisplayWidth = in.readInt();
            final int physicalDisplayHeight = in.readInt();
            final boolean isRound = in.readBoolean();
            final String spec = in.readString8();
            final float specRatio = in.readFloat();
            final int displayWidth = in.readInt();
            final int displayHeight = in.readInt();
            final int rotation = in.readInt();
            final int offsetX = in.readInt();
            final int offsetY = in.readInt();
            final float scale = in.readFloat();

            DisplayShape shape;
            switch (type) {
                case TYPE_RESOURCES:
                    shape = fromResources(displayUniqueId,
                            physicalDisplayWidth, physicalDisplayHeight, displayWidth,
                            displayHeight);
                    break;
                case TYPE_DEFAULT:
                    shape = createDefaultDisplayShape(displayWidth, displayHeight, isRound);
                    break;
                case TYPE_SPEC:
                    shape = fromSpecString(spec, specRatio, displayWidth, displayHeight);
                    break;
                case TYPE_NONE:
                default:
                    shape = NONE;
                    break;
            }

            if (rotation != ROTATION_0) {
                shape = shape.setRotation(rotation);
            }
            if (offsetX != 0 || offsetY != 0) {
                shape = shape.setOffset(offsetX, offsetY);
            }
            if (scale != 1f) {
                shape = shape.setScale(scale);
            }
            return shape;
        }

        @Override
        public DisplayShape[] newArray(int size) {
            return new DisplayShape[size];
        }
    };

    private static final class Cache {
        @GuardedBy("Cache.class")
        private static String sCachedSpec;
        @GuardedBy("Cache.class")
        private static int sCachedSpecDisplayWidth;
        @GuardedBy("Cache.class")
        private static int sCachedSpecDisplayHeight;
        @GuardedBy("Cache.class")
        private static float sCachedPhysicalPixelDisplaySizeRatio;
        @GuardedBy("Cache.class")
        private static DisplayShape sCachedDisplayShapeFromSpec;

        @GuardedBy("Cache.class")
        private static String sCachedDisplayUniqueId;
        @GuardedBy("Cache.class")
        private static int sCachedPhysicalDisplayWidth;
        @GuardedBy("Cache.class")
        private static int sCachedPhysicalDisplayHeight;
        @GuardedBy("Cache.class")
        private static int sCachedResDisplayWidth;
        @GuardedBy("Cache.class")
        private static int sCachedResDisplayHeight;
        @GuardedBy("Cache.class")
        private static DisplayShape sCachedDisplayShapeFromResources;

        @GuardedBy("Cache.class")
        private static int sCachedDefaultDisplayWidth;
        @GuardedBy("Cache.class")
        private static int sCachedDefaultDisplayHeight;
        @GuardedBy("Cache.class")
        private static boolean sCachedIsRound;
        @GuardedBy("Cache.class")
        private static DisplayShape sCachedDisplayShapeFromDefault;

        static synchronized DisplayShape getDisplayShape(String spec,
                float physicalPixelDisplaySizeRatio, int displayWidth, int displayHeight) {
            if (spec.equals(sCachedSpec)
                    && sCachedSpecDisplayWidth == displayWidth
                    && sCachedSpecDisplayHeight == displayHeight
                    && sCachedPhysicalPixelDisplaySizeRatio == physicalPixelDisplaySizeRatio) {
                return sCachedDisplayShapeFromSpec;
            }

            final DisplayShape shape = new DisplayShape(TYPE_SPEC, null /* displayUniqueId */,
                    0 /* physicalDisplayWidth */, 0 /* physicalDisplayHeight */,
                    false /* isRound */, spec, physicalPixelDisplaySizeRatio, displayWidth,
                    displayHeight, ROTATION_0, 0, 0, 1f);

            sCachedSpec = spec;
            sCachedSpecDisplayWidth = displayWidth;
            sCachedSpecDisplayHeight = displayHeight;
            sCachedPhysicalPixelDisplaySizeRatio = physicalPixelDisplaySizeRatio;
            sCachedDisplayShapeFromSpec = shape;
            return shape;
        }

        static synchronized DisplayShape getDisplayShape(String displayUniqueId,
                int physicalDisplayWidth, int physicalDisplayHeight, int displayWidth,
                int displayHeight) {
            if (Objects.equals(sCachedDisplayUniqueId, displayUniqueId)
                    && sCachedPhysicalDisplayWidth == physicalDisplayWidth
                    && sCachedPhysicalDisplayHeight == physicalDisplayHeight
                    && sCachedResDisplayWidth == displayWidth
                    && sCachedResDisplayHeight == displayHeight) {
                return sCachedDisplayShapeFromResources;
            }

            final DisplayShape shape = new DisplayShape(TYPE_RESOURCES, displayUniqueId,
                    physicalDisplayWidth, physicalDisplayHeight, false /* isRound */,
                    null /* spec */, 0f /* specRatio */, displayWidth, displayHeight, ROTATION_0, 0,
                    0, 1f);

            sCachedDisplayUniqueId = displayUniqueId;
            sCachedPhysicalDisplayWidth = physicalDisplayWidth;
            sCachedPhysicalDisplayHeight = physicalDisplayHeight;
            sCachedResDisplayWidth = displayWidth;
            sCachedResDisplayHeight = displayHeight;
            sCachedDisplayShapeFromResources = shape;
            return shape;
        }

        static synchronized DisplayShape getDisplayShape(int displayWidth, int displayHeight,
                boolean isScreenRound) {
            if (sCachedDisplayShapeFromDefault != null
                    && sCachedDefaultDisplayWidth == displayWidth
                    && sCachedDefaultDisplayHeight == displayHeight
                    && sCachedIsRound == isScreenRound) {
                return sCachedDisplayShapeFromDefault;
            }
            final DisplayShape shape = new DisplayShape(TYPE_DEFAULT, null /* displayUniqueId */,
                    0 /* physicalDisplayWidth */, 0 /* physicalDisplayHeight */, isScreenRound,
                    null /* spec */, 0f /* specRatio */, displayWidth, displayHeight,
                    ROTATION_0, 0, 0, 1f);
            sCachedDefaultDisplayWidth = displayWidth;
            sCachedDefaultDisplayHeight = displayHeight;
            sCachedIsRound = isScreenRound;
            sCachedDisplayShapeFromDefault = shape;
            return shape;
        }
    }
}

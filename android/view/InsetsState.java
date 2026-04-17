/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.internal.perfetto.protos.Insetsstate.InsetsStateProto.DISPLAY_CUTOUT;
import static android.internal.perfetto.protos.Insetsstate.InsetsStateProto.DISPLAY_FRAME;
import static android.internal.perfetto.protos.Insetsstate.InsetsStateProto.SOURCES;
import static android.util.SequenceUtils.getInitSeq;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR;
import static android.view.InsetsSource.FLAG_INSETS_ROUNDED_CORNER;
import static android.view.InsetsSource.FLAG_INVALID;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.WindowInsets.Type.TYPES;
import static android.view.WindowInsets.Type.captionBar;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.indexOf;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.window.DesktopModeFlags.ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration.ActivityType;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource.InternalInsetsSide;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager.LayoutParams.Flags;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.WindowManager.LayoutParams.WindowType;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Holder for state of system windows that cause window insets for all other windows in the system.
 * @hide
 */
public class InsetsState implements Parcelable {

    @NonNull
    private final SparseArray<InsetsSource> mSources;

    /**
     * The frame of the display these sources are relative to.
     */
    @NonNull
    private final Rect mDisplayFrame = new Rect();

    /** The area cut from the display. */
    @NonNull
    private final DisplayCutout.ParcelableWrapper mDisplayCutout =
            new DisplayCutout.ParcelableWrapper();

    /**
     * The frame that rounded corners are relative to.
     *
     * <p>There are 2 cases that will draw fake rounded corners:
     * <ol>
     *   <li>In split-screen mode
     *   <li>Devices with a task bar
     * </ol>
     * <p>We need to report these fake rounded corners to apps by re-calculating based on this
     * frame.
     */
    @NonNull
    private final Rect mRoundedCornerFrame = new Rect();

    /** The rounded corners on the display */
    @NonNull
    private RoundedCorners mRoundedCorners = RoundedCorners.NO_ROUNDED_CORNERS;

    /** The bounds of the Privacy Indicator */
    @NonNull
    private PrivacyIndicatorBounds mPrivacyIndicatorBounds =
            new PrivacyIndicatorBounds();

    /** The display shape */
    @NonNull
    private DisplayShape mDisplayShape = DisplayShape.NONE;

    /** To make sure the info update between client and system server is in order. */
    private int mSeq = getInitSeq();

    public InsetsState() {
        mSources = new SparseArray<>();
    }

    public InsetsState(@NonNull InsetsState copy) {
        this(copy, false /* copySources */);
    }

    public InsetsState(@NonNull InsetsState copy, boolean copySources) {
        mSources = new SparseArray<>(copy.mSources.size());
        set(copy, copySources);
    }

    /**
     * Calculates {@link WindowInsets} based on the current source configuration.
     *
     * @param frame The frame to calculate the insets relative to.
     * @param ignoringVisibilityState {@link InsetsState} used to calculate
     *        {@link WindowInsets#getInsetsIgnoringVisibility(int)} information, or pass
     *        {@code null} to use this state to calculate that information.
     * @return The calculated insets.
     */
    @NonNull
    public WindowInsets calculateInsets(@NonNull Rect frame, @Nullable Rect hostBounds,
            @Nullable InsetsState ignoringVisibilityState, boolean isScreenRound,
            int legacySoftInputMode, int legacyWindowFlags, int legacySystemUiFlags,
            @WindowType int windowType, @ActivityType int activityType,
            @Nullable @InternalInsetsSide SparseIntArray idSideMap) {
        final Insets[] typeInsetsMap = new Insets[TYPES.length];
        final Insets[] typeMaxInsetsMap = new Insets[TYPES.length];
        final boolean[] typeVisibilityMap = new boolean[TYPES.length];
        final Rect relativeFrame = new Rect(frame);
        final Rect relativeFrameMax = new Rect(frame);
        @InsetsType
        int forceConsumingTypes = 0;
        boolean forceConsumingOpaqueCaptionBar = false;
        @InsetsType
        int suppressScrimTypes = 0;
        final Rect[][] typeBoundingRectsMap = new Rect[TYPES.length][];
        final Rect[][] typeMaxBoundingRectsMap = new Rect[TYPES.length][];
        for (int i = mSources.size() - 1; i >= 0; i--) {
            final InsetsSource source = mSources.valueAt(i);
            @InsetsType
            final int type = source.getType();
            @InsetsSource.Flags
            final int flags = source.getFlags();

            if ((flags & InsetsSource.FLAG_FORCE_CONSUMING) != 0) {
                forceConsumingTypes |= type;
            }

            if (ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS.isTrue()
                    && (flags & FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR) != 0) {
                forceConsumingOpaqueCaptionBar = true;
            }

            if ((flags & InsetsSource.FLAG_SUPPRESS_SCRIM) != 0) {
                suppressScrimTypes |= type;
            }

            processSource(source, relativeFrame, hostBounds, false /* ignoreVisibility */,
                    typeInsetsMap, idSideMap, typeVisibilityMap, typeBoundingRectsMap);

            // IME won't be reported in max insets as the size depends on the EditorInfo of the IME
            // target.
            if (type != WindowInsets.Type.ime()) {
                InsetsSource ignoringVisibilitySource = ignoringVisibilityState != null
                        ? ignoringVisibilityState.peekSource(source.getId())
                        : source;
                if (ignoringVisibilitySource == null) {
                    continue;
                }
                processSource(ignoringVisibilitySource, relativeFrameMax, hostBounds,
                        true /* ignoreVisibility */, typeMaxInsetsMap, null /* idSideMap */,
                        null /* typeVisibilityMap */, typeMaxBoundingRectsMap);
            }
        }
        final int softInputAdjustMode = legacySoftInputMode & SOFT_INPUT_MASK_ADJUST;

        @InsetsType
        int compatInsetsTypes = systemBars() | displayCutout();
        if (softInputAdjustMode == SOFT_INPUT_ADJUST_RESIZE) {
            compatInsetsTypes |= ime();
        }
        if ((legacyWindowFlags & FLAG_FULLSCREEN) != 0) {
            compatInsetsTypes &= ~statusBars();
        }
        if (clearsCompatInsets(windowType, legacyWindowFlags, activityType, forceConsumingTypes)) {
            compatInsetsTypes = 0;
        }

        return new WindowInsets(typeInsetsMap, typeMaxInsetsMap, typeVisibilityMap, isScreenRound,
                forceConsumingTypes, forceConsumingOpaqueCaptionBar, suppressScrimTypes,
                calculateRelativeCutout(frame),
                calculateRelativeRoundedCorners(frame, hostBounds),
                calculateRelativePrivacyIndicatorBounds(frame),
                calculateRelativeDisplayShape(frame),
                compatInsetsTypes, (legacySystemUiFlags & SYSTEM_UI_FLAG_LAYOUT_STABLE) != 0,
                typeBoundingRectsMap, typeMaxBoundingRectsMap, frame.width(), frame.height());
    }

    @NonNull
    private DisplayCutout calculateRelativeCutout(@Nullable Rect frame) {
        final DisplayCutout raw = mDisplayCutout.get();
        if (mDisplayFrame.equals(frame)) {
            return raw;
        }
        if (frame == null) {
            return DisplayCutout.NO_CUTOUT;
        }
        final int insetLeft = frame.left - mDisplayFrame.left;
        final int insetTop = frame.top - mDisplayFrame.top;
        final int insetRight = mDisplayFrame.right - frame.right;
        final int insetBottom = mDisplayFrame.bottom - frame.bottom;
        if (insetLeft >= raw.getSafeInsetLeft()
                && insetTop >= raw.getSafeInsetTop()
                && insetRight >= raw.getSafeInsetRight()
                && insetBottom >= raw.getSafeInsetBottom()) {
            return DisplayCutout.NO_CUTOUT;
        }
        return raw.inset(insetLeft, insetTop, insetRight, insetBottom);
    }

    @NonNull
    private RoundedCorners calculateRelativeRoundedCorners(@Nullable Rect frame,
            @Nullable Rect hostBounds) {
        if (frame == null) {
            return RoundedCorners.NO_ROUNDED_CORNERS;
        }
        // If mRoundedCornerFrame is set, we should calculate the new RoundedCorners based on this
        // frame.
        final Rect roundedCornerFrame = new Rect(mRoundedCornerFrame);
        for (int i = mSources.size() - 1; i >= 0; i--) {
            final InsetsSource source = mSources.valueAt(i);
            if (source.hasFlags(FLAG_INSETS_ROUNDED_CORNER)) {
                final Insets insets = source.calculateInsets(roundedCornerFrame, hostBounds,
                        false /* ignoreVisibility */);
                roundedCornerFrame.inset(insets);
            }
        }
        if (!roundedCornerFrame.isEmpty() && !roundedCornerFrame.equals(mDisplayFrame)) {
            return mRoundedCorners.insetWithFrame(frame, roundedCornerFrame);
        }
        if (mDisplayFrame.equals(frame)) {
            return mRoundedCorners;
        }
        final int insetLeft = frame.left - mDisplayFrame.left;
        final int insetTop = frame.top - mDisplayFrame.top;
        final int insetRight = mDisplayFrame.right - frame.right;
        final int insetBottom = mDisplayFrame.bottom - frame.bottom;
        return mRoundedCorners.inset(insetLeft, insetTop, insetRight, insetBottom);
    }

    @Nullable
    private PrivacyIndicatorBounds calculateRelativePrivacyIndicatorBounds(@Nullable Rect frame) {
        if (mDisplayFrame.equals(frame)) {
            return mPrivacyIndicatorBounds;
        }
        if (frame == null) {
            return null;
        }
        final int insetLeft = frame.left - mDisplayFrame.left;
        final int insetTop = frame.top - mDisplayFrame.top;
        final int insetRight = mDisplayFrame.right - frame.right;
        final int insetBottom = mDisplayFrame.bottom - frame.bottom;
        return mPrivacyIndicatorBounds.inset(insetLeft, insetTop, insetRight, insetBottom);
    }

    @NonNull
    private DisplayShape calculateRelativeDisplayShape(@Nullable Rect frame) {
        if (mDisplayFrame.equals(frame)) {
            return mDisplayShape;
        }
        if (frame == null) {
            return DisplayShape.NONE;
        }
        return mDisplayShape.setOffset(-frame.left, -frame.top);
    }

    @NonNull
    public Insets calculateInsets(@NonNull Rect frame, @Nullable Rect hostBounds,
            @InsetsType int types, boolean ignoreVisibility) {
        Insets insets = Insets.NONE;
        for (int i = mSources.size() - 1; i >= 0; i--) {
            final InsetsSource source = mSources.valueAt(i);
            if ((source.getType() & types) == 0) {
                continue;
            }
            insets = Insets.max(source.calculateInsets(frame, hostBounds, ignoreVisibility),
                    insets);
        }
        return insets;
    }

    @NonNull
    public Insets calculateInsets(@NonNull Rect frame, @Nullable Rect hostBounds,
            @InsetsType int types, @InsetsType int requestedVisibleTypes) {
        Insets insets = Insets.NONE;
        for (int i = mSources.size() - 1; i >= 0; i--) {
            final InsetsSource source = mSources.valueAt(i);
            if ((source.getType() & types & requestedVisibleTypes) == 0) {
                continue;
            }
            insets = Insets.max(source.calculateInsets(frame, hostBounds, true), insets);
        }
        return insets;
    }

    @NonNull
    public Insets calculateVisibleInsets(@NonNull Rect frame, @Nullable Rect hostBounds,
            @WindowType int windowType, @ActivityType int activityType,
            @SoftInputModeFlags int softInputMode, @Flags int windowFlags,
            @InsetsType int ignoringTypes) {
        final int softInputAdjustMode = softInputMode & SOFT_INPUT_MASK_ADJUST;
        final int visibleInsetsTypes = softInputAdjustMode != SOFT_INPUT_ADJUST_NOTHING
                ? systemBars() | displayCutout() | ime()
                : systemBars() | displayCutout();
        @InsetsType
        int forceConsumingTypes = 0;
        Insets insets = Insets.NONE;
        for (int i = mSources.size() - 1; i >= 0; i--) {
            final InsetsSource source = mSources.valueAt(i);
            if ((source.getType() & visibleInsetsTypes) == 0) {
                continue;
            }
            if ((source.getType() & ignoringTypes) != 0) {
                continue;
            }
            if (source.hasFlags(FLAG_FORCE_CONSUMING)) {
                forceConsumingTypes |= source.getType();
            }
            insets = Insets.max(source.calculateVisibleInsets(frame, hostBounds), insets);
        }
        return clearsCompatInsets(windowType, windowFlags, activityType, forceConsumingTypes)
                ? Insets.NONE
                : insets;
    }

    /**
     * Calculate which insets *cannot* be controlled, because the frame does not cover the
     * respective side of the inset.
     *
     * <p>If the frame of our window doesn't cover the entire inset, the control API makes very
     * little sense, as we don't deal with negative insets.
     */
    @InsetsType
    public int calculateUncontrollableInsetsFromFrame(@NonNull Rect frame,
            @Nullable Rect hostBounds) {
        int blocked = 0;
        for (int i = mSources.size() - 1; i >= 0; i--) {
            final InsetsSource source = mSources.valueAt(i);
            if (!canControlSource(frame, hostBounds, source)) {
                blocked |= source.getType();
            }
        }
        return blocked;
    }

    private static boolean canControlSource(@NonNull Rect frame, @Nullable Rect hostBounds,
            @NonNull InsetsSource source) {
        final Insets insets = source.calculateInsets(frame, hostBounds,
                true /* ignoreVisibility */);
        final Rect sourceFrame = source.getFrame();
        final int sourceWidth = sourceFrame.width();
        final int sourceHeight = sourceFrame.height();
        return insets.left == sourceWidth || insets.right == sourceWidth
                || insets.top == sourceHeight || insets.bottom == sourceHeight;
    }

    private void processSource(@NonNull InsetsSource source, @NonNull Rect relativeFrame,
            @Nullable Rect hostBounds, boolean ignoreVisibility, @NonNull Insets[] typeInsetsMap,
            @Nullable @InternalInsetsSide SparseIntArray idSideMap,
            @Nullable boolean[] typeVisibilityMap, Rect[][] typeBoundingRectsMap) {
        final Insets insets = source.calculateInsets(relativeFrame, hostBounds, ignoreVisibility);
        final Rect[] boundingRects = source.calculateBoundingRects(relativeFrame, hostBounds,
                ignoreVisibility);

        final int type = source.getType();
        processSourceAsPublicType(source, typeInsetsMap, idSideMap, typeVisibilityMap,
                typeBoundingRectsMap, insets, boundingRects, type);

        if (type == Type.MANDATORY_SYSTEM_GESTURES) {
            // Mandatory system gestures are also system gestures.
            // TODO: find a way to express this more generally. One option would be to define
            //       Type.systemGestureInsets() as NORMAL | MANDATORY, but then we lose the
            //       ability to set systemGestureInsets() independently from
            //       mandatorySystemGestureInsets() in the Builder.
            processSourceAsPublicType(source, typeInsetsMap, idSideMap, typeVisibilityMap,
                    typeBoundingRectsMap, insets, boundingRects, Type.SYSTEM_GESTURES);
        }
        if (type == Type.CAPTION_BAR) {
            // Caption should also be gesture and tappable elements. This should not be needed when
            // the caption is added from the shell, as the shell can add other types at the same
            // time.
            processSourceAsPublicType(source, typeInsetsMap, idSideMap, typeVisibilityMap,
                    typeBoundingRectsMap, insets, boundingRects, Type.SYSTEM_GESTURES);
            processSourceAsPublicType(source, typeInsetsMap, idSideMap, typeVisibilityMap,
                    typeBoundingRectsMap, insets, boundingRects, Type.MANDATORY_SYSTEM_GESTURES);
            processSourceAsPublicType(source, typeInsetsMap, idSideMap, typeVisibilityMap,
                    typeBoundingRectsMap, insets, boundingRects, Type.TAPPABLE_ELEMENT);
        }
    }

    private void processSourceAsPublicType(@NonNull InsetsSource source, Insets[] typeInsetsMap,
            @InternalInsetsSide @Nullable SparseIntArray idSideMap,
            @Nullable boolean[] typeVisibilityMap, @Nullable Rect[][] typeBoundingRectsMap,
            @NonNull Insets insets, @NonNull Rect[] boundingRects, @InsetsType int type) {
        final int index = indexOf(type);

        // Don't put Insets.NONE into typeInsetsMap. Otherwise, two WindowInsets can be considered
        // as non-equal while they provide the same insets of each type from WindowInsets#getInsets
        // if one WindowInsets has Insets.NONE for a type and the other has null for the same type.
        if (!Insets.NONE.equals(insets)) {
            Insets existing = typeInsetsMap[index];
            if (existing == null) {
                typeInsetsMap[index] = insets;
            } else {
                typeInsetsMap[index] = Insets.max(existing, insets);
            }
        }

        if (typeVisibilityMap != null) {
            typeVisibilityMap[index] = source.isVisible();
        }

        if (idSideMap != null) {
            @InternalInsetsSide
            int insetSide = InsetsSource.getInsetSide(insets);
            if (insetSide != InsetsSource.SIDE_UNKNOWN) {
                idSideMap.put(source.getId(), insetSide);
            }
        }

        if (typeBoundingRectsMap != null && boundingRects.length > 0) {
            final Rect[] existing = typeBoundingRectsMap[index];
            if (existing == null) {
                typeBoundingRectsMap[index] = boundingRects;
            } else {
                typeBoundingRectsMap[index] = concatenate(existing, boundingRects);
            }
        }
    }

    @NonNull
    private static Rect[] concatenate(@NonNull Rect[] a, @NonNull Rect[] b) {
        final Rect[] c = new Rect[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    /**
     * Gets the source mapped from the ID, or creates one if no such mapping has been made.
     */
    @NonNull
    public InsetsSource getOrCreateSource(int id, @InsetsType int type) {
        InsetsSource source = mSources.get(id);
        if (source != null) {
            return source;
        }
        source = new InsetsSource(id, type);
        mSources.put(id, source);
        return source;
    }

    /**
     * Gets the source mapped from the ID, or <code>null</code> if no such mapping has been made.
     */
    @Nullable
    public InsetsSource peekSource(int id) {
        return mSources.get(id);
    }

    /**
     * Given an index in the range <code>0...sourceSize()-1</code>, returns the source ID from the
     * <code>index</code>th ID-source mapping that this state stores.
     */
    public int sourceIdAt(int index) {
        return mSources.keyAt(index);
    }

    /**
     * Given an index in the range <code>0...sourceSize()-1</code>, returns the source from the
     * <code>index</code>th ID-source mapping that this state stores.
     */
    public InsetsSource sourceAt(int index) {
        return mSources.valueAt(index);
    }

    /**
     * Returns the amount of the sources.
     */
    public int sourceSize() {
        return mSources.size();
    }

    /**
     * Returns if the source is visible or the type is default visible and the source doesn't exist.
     *
     * @param id The ID of the source.
     * @param type The {@link InsetsType} to see if it is default visible.
     * @return {@code true} if the source is visible or the type is default visible and the source
     *         doesn't exist.
     */
    public boolean isSourceOrDefaultVisible(int id, @InsetsType int type) {
        final InsetsSource source = mSources.get(id);
        return source != null ? source.isVisible() : (type & Type.defaultVisible()) != 0;
    }

    public void setDisplayFrame(@NonNull Rect frame) {
        mDisplayFrame.set(frame);
    }

    @NonNull
    public Rect getDisplayFrame() {
        return mDisplayFrame;
    }

    public void setDisplayCutout(@NonNull DisplayCutout cutout) {
        mDisplayCutout.set(cutout);
    }

    @NonNull
    public DisplayCutout getDisplayCutout() {
        return mDisplayCutout.get();
    }

    public void getDisplayCutoutSafe(@NonNull Rect outBounds) {
        outBounds.set(
                WindowLayout.MIN_X, WindowLayout.MIN_Y, WindowLayout.MAX_X, WindowLayout.MAX_Y);
        final DisplayCutout cutout = mDisplayCutout.get();
        final Rect displayFrame = mDisplayFrame;
        if (!cutout.isEmpty()) {
            if (cutout.getSafeInsetLeft() > 0) {
                outBounds.left = displayFrame.left + cutout.getSafeInsetLeft();
            }
            if (cutout.getSafeInsetTop() > 0) {
                outBounds.top = displayFrame.top + cutout.getSafeInsetTop();
            }
            if (cutout.getSafeInsetRight() > 0) {
                outBounds.right = displayFrame.right - cutout.getSafeInsetRight();
            }
            if (cutout.getSafeInsetBottom() > 0) {
                outBounds.bottom = displayFrame.bottom - cutout.getSafeInsetBottom();
            }
        }
    }

    public void setRoundedCorners(@NonNull RoundedCorners roundedCorners) {
        mRoundedCorners = roundedCorners;
    }

    @NonNull
    public RoundedCorners getRoundedCorners() {
        return mRoundedCorners;
    }

    /**
     * Set the frame that will be used to calculate the rounded corners.
     *
     * @see #mRoundedCornerFrame
     */
    public void setRoundedCornerFrame(@NonNull Rect frame) {
        mRoundedCornerFrame.set(frame);
    }

    public void setPrivacyIndicatorBounds(@NonNull PrivacyIndicatorBounds bounds) {
        mPrivacyIndicatorBounds = bounds;
    }

    @NonNull
    public PrivacyIndicatorBounds getPrivacyIndicatorBounds() {
        return mPrivacyIndicatorBounds;
    }

    public void setDisplayShape(@NonNull DisplayShape displayShape) {
        mDisplayShape = displayShape;
    }

    @NonNull
    public DisplayShape getDisplayShape() {
        return mDisplayShape;
    }

    /**
     * Removes the source which has the ID from this state, if there was any.
     *
     * @param id The ID of the source to remove.
     */
    public void removeSource(int id) {
        mSources.delete(id);
    }

    /**
     * Removes the source at the specified index.
     *
     * @param index The index of the source to remove.
     */
    public void removeSourceAt(int index) {
        mSources.removeAt(index);
    }

    /**
     * A shortcut for setting the visibility of the source.
     *
     * @param id The ID of the source to set the visibility
     * @param visible {@code true} for visible
     */
    public void setSourceVisible(int id, boolean visible) {
        final InsetsSource source = mSources.get(id);
        if (source != null) {
            source.setVisible(visible);
        }
    }

    /**
     * Scales the frame and the visible frame (if there is one) of each source.
     *
     * @param scale the scale to be applied
     */
    public void scale(float scale) {
        mDisplayFrame.scale(scale);
        mDisplayCutout.scale(scale);
        mRoundedCorners = mRoundedCorners.scale(scale);
        mRoundedCornerFrame.scale(scale);
        mPrivacyIndicatorBounds = mPrivacyIndicatorBounds.scale(scale);
        mDisplayShape = mDisplayShape.setScale(scale);
        for (int i = mSources.size() - 1; i >= 0; i--) {
            mSources.valueAt(i).scale(scale);
        }
    }

    public int getSeq() {
        return mSeq;
    }

    public void setSeq(int seq) {
        mSeq = seq;
    }

    public void set(@NonNull InsetsState other) {
        set(other, false /* copySources */);
    }

    public void set(@NonNull InsetsState other, boolean copySources) {
        if (this != other) {
            mDisplayFrame.set(other.mDisplayFrame);
            mDisplayCutout.set(other.mDisplayCutout);
            mRoundedCorners = other.getRoundedCorners();
            mRoundedCornerFrame.set(other.mRoundedCornerFrame);
            mPrivacyIndicatorBounds = other.getPrivacyIndicatorBounds();
            mDisplayShape = other.getDisplayShape();
            mSeq = other.mSeq;
            mSources.clear();
            for (int i = 0, size = other.mSources.size(); i < size; i++) {
                final InsetsSource otherSource = other.mSources.valueAt(i);
                mSources.append(otherSource.getId(), copySources
                        ? new InsetsSource(otherSource)
                        : otherSource);
            }
        } else if (copySources) {
            for (int i = 0, size = mSources.size(); i < size; i++) {
                mSources.setValueAt(i, new InsetsSource(mSources.valueAt(i)));
            }
        }
    }

    /**
     * Sets the values from the other InsetsState. But for sources, only specific types of source
     * would be set.
     *
     * @param other the other InsetsState.
     * @param types the only types of sources would be set.
     */
    public void set(@NonNull InsetsState other, @InsetsType int types) {
        if (this == other) {
            return;
        }
        mDisplayFrame.set(other.mDisplayFrame);
        mDisplayCutout.set(other.mDisplayCutout);
        mRoundedCorners = other.getRoundedCorners();
        mRoundedCornerFrame.set(other.mRoundedCornerFrame);
        mPrivacyIndicatorBounds = other.getPrivacyIndicatorBounds();
        mDisplayShape = other.getDisplayShape();
        mSeq = other.mSeq;
        if (types == 0) {
            return;
        }
        for (int i = mSources.size() - 1; i >= 0; i--) {
            final InsetsSource source = mSources.valueAt(i);
            if ((source.getType() & types) != 0) {
                mSources.removeAt(i);
            }
        }
        for (int i = other.mSources.size() - 1; i >= 0; i--) {
            final InsetsSource otherSource = other.mSources.valueAt(i);
            if ((otherSource.getType() & types) != 0) {
                mSources.put(otherSource.getId(), otherSource);
            }
        }
    }

    public void addSource(@NonNull InsetsSource source) {
        mSources.put(source.getId(), source);
    }

    public static boolean clearsCompatInsets(@WindowType int windowType, @Flags int windowFlags,
            @ActivityType int activityType, @InsetsType int forceConsumingTypes) {
        return (windowFlags & FLAG_LAYOUT_NO_LIMITS) != 0
                // For compatibility reasons, this excludes the wallpaper, the system error windows,
                // and the app windows while any system bar is forcibly consumed.
                && windowType != TYPE_WALLPAPER && windowType != TYPE_SYSTEM_ERROR
                // This ensures the app content won't be obscured by compat insets even if the app
                // has FLAG_LAYOUT_NO_LIMITS.
                && (forceConsumingTypes == 0 || activityType != ACTIVITY_TYPE_STANDARD);
    }

    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        final String newPrefix = prefix + "  ";
        pw.println(prefix + "InsetsState");
        pw.println(newPrefix + "mDisplayFrame=" + mDisplayFrame);
        pw.println(newPrefix + "mDisplayCutout=" + mDisplayCutout.get());
        pw.println(newPrefix + "mRoundedCorners=" + mRoundedCorners);
        pw.println(newPrefix + "mRoundedCornerFrame=" + mRoundedCornerFrame);
        pw.println(newPrefix + "mPrivacyIndicatorBounds=" + mPrivacyIndicatorBounds);
        pw.println(newPrefix + "mDisplayShape=" + mDisplayShape);
        for (int i = 0, size = mSources.size(); i < size; i++) {
            mSources.valueAt(i).dump(newPrefix + "  ", pw);
        }
    }

    /**
     * Write to a protocol buffer output stream.
     * Protocol buffer message definition at {@link InsetsStateProto}
     *
     * @param proto Stream to write the InsetsState object to.
     * @param fieldId Field Id of the InsetsState as defined in the parent message.
     */
    public void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        for (int i = 0, size = mSources.size(); i < size; i++) {
            mSources.valueAt(i).dumpDebug(proto, SOURCES);
        }
        mDisplayFrame.dumpDebug(proto, DISPLAY_FRAME);
        mDisplayCutout.get().dumpDebug(proto, DISPLAY_CUTOUT);
        proto.end(token);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return equals(o, false, false, false);
    }

    /**
     * An equals method can exclude the caption insets. This is useful because we assemble the
     * caption insets information on the client side, and when we communicate with server, it's
     * excluded.
     *
     * @param excludesCaptionBar   If {@link Type#captionBar()}} should be ignored.
     * @param excludesInvisibleIme If {@link Type#ime()} should be ignored when IME is
     *                             not visible.
     * @param excludesInvalidSource If a source should be ignored if it has
     *                              {@link InsetsSource#FLAG_INVALID}.
     * @return {@code true} if the two InsetsState objects are equal, {@code false} otherwise.
     */
    public boolean equals(@Nullable Object o, boolean excludesCaptionBar,
            boolean excludesInvisibleIme, boolean excludesInvalidSource) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }

        InsetsState state = (InsetsState) o;

        if (!mDisplayFrame.equals(state.mDisplayFrame)
                || !mDisplayCutout.equals(state.mDisplayCutout)
                || !mRoundedCorners.equals(state.mRoundedCorners)
                || !mRoundedCornerFrame.equals(state.mRoundedCornerFrame)
                || !mPrivacyIndicatorBounds.equals(state.mPrivacyIndicatorBounds)
                || !mDisplayShape.equals(state.mDisplayShape)) {
            // mSeq is for internal bookkeeping only.
            return false;
        }

        final SparseArray<InsetsSource> thisSources = mSources;
        final SparseArray<InsetsSource> thatSources = state.mSources;
        if (!excludesCaptionBar && !excludesInvisibleIme && !excludesInvalidSource) {
            return thisSources.contentEquals(thatSources);
        } else {
            final int thisSize = thisSources.size();
            final int thatSize = thatSources.size();
            int thisIndex = 0;
            int thatIndex = 0;
            while (thisIndex < thisSize || thatIndex < thatSize) {
                InsetsSource thisSource = thisIndex < thisSize
                        ? thisSources.valueAt(thisIndex)
                        : null;

                // Seek to the next non-excluding source of ours.
                while (thisSource != null
                        && ((excludesCaptionBar && thisSource.getType() == captionBar())
                                || (excludesInvisibleIme && thisSource.getType() == ime()
                                        && !thisSource.isVisible())
                                || (excludesInvalidSource
                                        && thisSource.hasFlags(FLAG_INVALID)))) {
                    thisIndex++;
                    thisSource = thisIndex < thisSize ? thisSources.valueAt(thisIndex) : null;
                }

                InsetsSource thatSource = thatIndex < thatSize
                        ? thatSources.valueAt(thatIndex)
                        : null;

                // Seek to the next non-excluding source of theirs.
                while (thatSource != null
                        && ((excludesCaptionBar && thatSource.getType() == captionBar())
                                || (excludesInvisibleIme && thatSource.getType() == ime()
                                        && !thatSource.isVisible())
                                || (excludesInvalidSource
                                        && thatSource.hasFlags(FLAG_INVALID)))) {
                    thatIndex++;
                    thatSource = thatIndex < thatSize ? thatSources.valueAt(thatIndex) : null;
                }

                if (!Objects.equals(thisSource, thatSource)) {
                    return false;
                }

                thisIndex++;
                thatIndex++;
            }
            return true;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDisplayFrame, mDisplayCutout, mSources.contentHashCode(),
                mRoundedCorners, mPrivacyIndicatorBounds, mRoundedCornerFrame, mDisplayShape);
    }

    public InsetsState(@NonNull Parcel in) {
        mSources = readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mDisplayFrame.writeToParcel(dest, flags);
        mDisplayCutout.writeToParcel(dest, flags);
        dest.writeTypedObject(mRoundedCorners, flags);
        mRoundedCornerFrame.writeToParcel(dest, flags);
        dest.writeTypedObject(mPrivacyIndicatorBounds, flags);
        dest.writeTypedObject(mDisplayShape, flags);
        dest.writeInt(mSeq);
        final int size = mSources.size();
        dest.writeInt(size);
        for (int i = 0; i < size; i++) {
            dest.writeTypedObject(mSources.valueAt(i), flags);
        }
    }

    @NonNull
    public static final Creator<InsetsState> CREATOR = new Creator<>() {

        public InsetsState createFromParcel(Parcel in) {
            return new InsetsState(in);
        }

        public InsetsState[] newArray(int size) {
            return new InsetsState[size];
        }
    };

    @NonNull
    public SparseArray<InsetsSource> readFromParcel(@NonNull Parcel in) {
        mDisplayFrame.readFromParcel(in);
        mDisplayCutout.readFromParcel(in);
        mRoundedCorners = in.readTypedObject(RoundedCorners.CREATOR);
        mRoundedCornerFrame.readFromParcel(in);
        mPrivacyIndicatorBounds = in.readTypedObject(PrivacyIndicatorBounds.CREATOR);
        mDisplayShape = in.readTypedObject(DisplayShape.CREATOR);
        mSeq = in.readInt();
        final int size = in.readInt();
        final SparseArray<InsetsSource> sources;
        if (mSources == null) {
            // We are constructing this InsetsState.
            sources = new SparseArray<>(size);
        } else {
            sources = mSources;
            sources.clear();
        }
        for (int i = 0; i < size; i++) {
            final InsetsSource source = in.readTypedObject(InsetsSource.CREATOR);
            sources.append(source.getId(), source);
        }
        return sources;
    }

    @Override
    public String toString() {
        final StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0, size = mSources.size(); i < size; i++) {
            joiner.add(mSources.valueAt(i).toString());
        }
        return "InsetsState: {"
                + "mDisplayFrame=" + mDisplayFrame
                + ", mDisplayCutout=" + mDisplayCutout
                + ", mRoundedCorners=" + mRoundedCorners
                + "  mRoundedCornerFrame=" + mRoundedCornerFrame
                + ", mPrivacyIndicatorBounds=" + mPrivacyIndicatorBounds
                + ", mDisplayShape=" + mDisplayShape
                + ", mSources= { " + joiner
                + " }";
    }

    /**
     * Traverses sources in two {@link InsetsState}s and calls back when events defined in
     * {@link OnTraverseCallbacks} happen. This is optimized for {@link SparseArray} that we avoid
     * triggering the binary search while getting the key or the value.
     *
     * <p>This can be used to copy attributes of sources from one InsetsState to the other one, or
     * to remove sources existing in one InsetsState but not in the other one.
     *
     * @param state1 The first {@link InsetsState} to be traversed.
     * @param state2 The second {@link InsetsState} to be traversed.
     * @param cb The {@link OnTraverseCallbacks} to call back to the caller.
     */
    public static void traverse(@NonNull InsetsState state1, @NonNull InsetsState state2,
            @NonNull OnTraverseCallbacks cb) {
        cb.onStart(state1, state2);
        final int size1 = state1.sourceSize();
        final int size2 = state2.sourceSize();
        int index1 = 0;
        int index2 = 0;
        while (index1 < size1 && index2 < size2) {
            int id1 = state1.sourceIdAt(index1);
            int id2 = state2.sourceIdAt(index2);
            while (id1 != id2) {
                if (id1 < id2) {
                    cb.onIdNotFoundInState2(index1, state1.sourceAt(index1));
                    index1++;
                    if (index1 < size1) {
                        id1 = state1.sourceIdAt(index1);
                    } else {
                        break;
                    }
                } else {
                    cb.onIdNotFoundInState1(index2, state2.sourceAt(index2));
                    index2++;
                    if (index2 < size2) {
                        id2 = state2.sourceIdAt(index2);
                    } else {
                        break;
                    }
                }
            }
            if (index1 >= size1 || index2 >= size2) {
                break;
            }
            final InsetsSource source1 = state1.sourceAt(index1);
            final InsetsSource source2 = state2.sourceAt(index2);
            cb.onIdMatch(source1, source2);
            index1++;
            index2++;
        }
        while (index2 < size2) {
            cb.onIdNotFoundInState1(index2, state2.sourceAt(index2));
            index2++;
        }
        while (index1 < size1) {
            cb.onIdNotFoundInState2(index1, state1.sourceAt(index1));
            index1++;
        }
        cb.onFinish(state1, state2);
    }

    /**
     * Used with {@link #traverse(InsetsState, InsetsState, OnTraverseCallbacks)} to call back when
     * certain events happen.
     */
    public interface OnTraverseCallbacks {

        /**
         * Called at the beginning of the traverse.
         *
         * @param state1 same as the state1 supplied to {@link #traverse}
         * @param state2 same as the state2 supplied to {@link #traverse}
         */
        default void onStart(@NonNull InsetsState state1, @NonNull InsetsState state2) { }

        /**
         * Called when finding two IDs from two InsetsStates are the same.
         *
         * @param source1 the source in state1.
         * @param source2 the source in state2.
         */
        default void onIdMatch(@NonNull InsetsSource source1, @NonNull InsetsSource source2) { }

        /**
         * Called when finding an ID in state2 but not in state1.
         *
         * @param index2 the index of the ID in state2.
         * @param source2 the source which has the ID in state2.
         */
        default void onIdNotFoundInState1(int index2, @NonNull InsetsSource source2) { }

        /**
         * Called when finding an ID in state1 but not in state2.
         *
         * @param index1 the index of the ID in state1.
         * @param source1 the source which has the ID in state1.
         */
        default void onIdNotFoundInState2(int index1, @NonNull InsetsSource source1) { }

        /**
         * Called at the end of the traverse.
         *
         * @param state1 same as the state1 supplied to {@link #traverse}
         * @param state2 same as the state2 supplied to {@link #traverse}
         */
        default void onFinish(@NonNull InsetsState state1, @NonNull InsetsState state2) { }
    }
}


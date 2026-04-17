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

package com.android.internal.policy;

import static com.android.internal.policy.DesktopModeLaunchBoundsUtils.CascadingDirection.BOTTOM_LEFT;
import static com.android.internal.policy.DesktopModeLaunchBoundsUtils.CascadingDirection.BOTTOM_RIGHT;
import static com.android.internal.policy.DesktopModeLaunchBoundsUtils.CascadingDirection.NONE;
import static com.android.internal.policy.DesktopModeLaunchBoundsUtils.CascadingDirection.TOP_LEFT;
import static com.android.internal.policy.DesktopModeLaunchBoundsUtils.CascadingDirection.TOP_RIGHT;

import android.annotation.NonNull;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.Size;

import com.android.internal.R;

import java.util.List;

/**
 * Utility class for calculating launch bounds for desktop mode tasks.
 */
public final class DesktopModeLaunchBoundsUtils {
    /**
     * Proportion of window height top offset with respect to bottom offset, used for central task
     * positioning.
     */
    public static final float WINDOW_HEIGHT_PROPORTION = 0.375f;

    /**
     * Represents the diagonal direction in which a window is cascaded.
     */
    public enum CascadingDirection {
        NONE,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    /**
     * Inverts the horizontal component of the given cascading direction.
     */
    private static CascadingDirection inverseHorizontally(CascadingDirection direction) {
        return switch (direction) {
            case TOP_LEFT -> TOP_RIGHT;
            case TOP_RIGHT -> TOP_LEFT;
            case BOTTOM_LEFT -> BOTTOM_RIGHT;
            case BOTTOM_RIGHT -> BOTTOM_LEFT;
            default -> NONE;
        };
    }

    /**
     * Inverts the vertical component of the given cascading direction.
     */
    private static CascadingDirection inverseVertically(CascadingDirection direction) {
        return switch (direction) {
            case TOP_LEFT -> BOTTOM_LEFT;
            case TOP_RIGHT -> BOTTOM_RIGHT;
            case BOTTOM_LEFT -> TOP_LEFT;
            case BOTTOM_RIGHT -> TOP_RIGHT;
            default -> NONE;
        };
    }

    /**
     * Returns the horizontal pixel offset for the given cascading direction.
     */
    private static int dx(CascadingDirection direction, int offset) {
        return switch (direction) {
            case TOP_LEFT, BOTTOM_LEFT -> -offset;
            case TOP_RIGHT, BOTTOM_RIGHT -> offset;
            default -> 0;
        };
    }

    /**
     * Returns the vertical pixel offset for the given cascading direction.
     */
    private static int dy(CascadingDirection direction, int offset) {
        return switch (direction) {
            case TOP_LEFT, TOP_RIGHT -> -offset;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> offset;
            default -> 0;
        };
    }

    /**
     * Adjusts bounds to be positioned in the middle of the screen.
     *
     * @param desiredSize the size of the window to be centered
     * @param screenBounds the bounds of the screen
     * @return the centered bounds
     */
    @NonNull
    public static Rect centerInScreen(@NonNull Size desiredSize,
            @NonNull Rect screenBounds) {
        final int heightOffset = (int)
                ((screenBounds.height() - desiredSize.getHeight()) * WINDOW_HEIGHT_PROPORTION);
        final int widthOffset = (screenBounds.width() - desiredSize.getWidth()) / 2;
        final Rect resultBounds = new Rect(0, 0,
                desiredSize.getWidth(), desiredSize.getHeight());
        resultBounds.offset(screenBounds.left + widthOffset, screenBounds.top + heightOffset);
        return resultBounds;
    }

    /**
     * Checks whether two rects are close enough to be considered same bounds.
     *
     * @param cascadingOffset the offset used for cascading windows
     * @param a the first rect
     * @param b the second rect
     * @return {@code true} if the two rects are close enough, {@code false} otherwise
     */
    public static boolean haveSameBoundsWithThreshold(int cascadingOffset, Rect a, Rect b) {
        // The threshold is set this way as this is especially useful for checking whether the
        // bounds of the new window is closer to the bounds of the previous window or the cascaded
        // bounds.
        int thresholdPx = cascadingOffset / 2;

        boolean leftClose = Math.abs(b.left - a.left) < thresholdPx;
        boolean topClose = Math.abs(b.top - a.top) < thresholdPx;
        boolean rightClose = Math.abs(b.right - a.right) < thresholdPx;
        boolean bottomClose = Math.abs(b.bottom - a.bottom) < thresholdPx;

        return leftClose && topClose && rightClose && bottomClose;
    }

    /**
     * Determines the next cascading direction by forward-tracing the cascading sequence
     * starting from the center (or remembered) bounds up to the focused window.
     *
     * @param frame the bounds of the display/screen frame
     * @param prevBoundsList the list of existing task bounds, ordered from newest to oldest
     * @param offset the cascading offset
     * @return the determined {@link CascadingDirection} for the next window, or {@code NONE}
     *         if the sequence is broken
     */
    private static CascadingDirection getCascadingDirection(@NonNull Rect frame,
            @NonNull List<Rect> prevBoundsList, @NonNull Rect startBounds, int offset) {

        // 1. Find the most recent window has {@link startBounds}.
        int centerIdx = -1;
        for (int i = 0; i < prevBoundsList.size(); i++) {
            if (haveSameBoundsWithThreshold(offset, prevBoundsList.get(i), startBounds)) {
                centerIdx = i;
                break;
            }
        }

        if (centerIdx == -1) {
            return NONE;
        }

        // 2. Forward-trace the cascading sequence from the center window to the newest window.
        Rect expectedBounds = new Rect(prevBoundsList.get(centerIdx));
        CascadingDirection currentDir = BOTTOM_RIGHT;

        for (int i = centerIdx - 1; i >= 0; i--) {
            currentDir = cascadeOneStep(expectedBounds, frame, currentDir, offset);

            if (!haveSameBoundsWithThreshold(offset, expectedBounds, prevBoundsList.get(i))) {
                return NONE;
            }
            // Snap to the actual window bounds to prevent error accumulation.
            expectedBounds.set(prevBoundsList.get(i));
        }

        return currentDir;
    }

    /**
     * Checks whether the given bounds are maximized or snapped to the left or right edge of the
     * frame.
     *
     * @param frame the bounds of the display/screen frame
     * @param bounds the bounds to check
     * @return {@code true} if the bounds are maximized or snapped, {@code false} otherwise
     */
    public static boolean isMaximizedOrSnapped(@NonNull Rect frame, @NonNull Rect bounds) {
        if (frame.equals(bounds)) {
            return true;
        }
        if (frame.height() == bounds.height() && frame.top == bounds.top) {
            return frame.left == bounds.left || frame.right == bounds.right;
        }
        return false;
    }

    /**
     * Calculates the stepped cascading bounds for a new window.
     * If the existing windows form a valid cascading sequence, the destination bounds
     * will be offset by one step from the newest window.
     *
     * @param frame the bounds of the display/screen frame
     * @param dest the rect object to be populated with the calculated launch bounds
     * @param prevBoundsList the list of existing task bounds, ordered from newest to oldest
     * @param res the resources object used to query the offset value
     */
    public static void cascadeWindowStepped(@NonNull Rect frame, @NonNull Rect dest,
            @NonNull List<Rect> prevBoundsList, @NonNull Resources res) {

        if (isMaximizedOrSnapped(frame, dest)) {
            return;
        }

        if (prevBoundsList.isEmpty()) {
            return;
        }

        int offset = res.getDimensionPixelSize(R.dimen.desktop_mode_cascading_offset);
        final CascadingDirection direction =
                getCascadingDirection(frame, prevBoundsList, dest, offset);

        if (direction != NONE) {
            // Sequence is valid: cascade one step from the newest window.
            dest.set(prevBoundsList.get(0));
            cascadeOneStep(dest, frame, direction, offset);
        }
    }

    /**
     * Advances the given bounds by one cascade step in the specified direction.
     * If the bounds exceed the frame boundaries, they bounce back (billiard style)
     * by twice the overflow distance, and the corresponding direction is inverted.
     *
     * @param bounds the window bounds to be offset
     * @param frame the bounds of the display/screen frame constraints
     * @param direction the current cascading direction
     * @param offset the cascading offset
     * @return the updated cascading direction, which may be inverted if a bounce occurred
     */
    public static CascadingDirection cascadeOneStep(
            @NonNull Rect bounds, @NonNull Rect frame, CascadingDirection direction, int offset) {
        final int dx = dx(direction, offset);
        final int dy = dy(direction, offset);
        bounds.offset(dx, dy);

        CascadingDirection nextDirection = direction;

        // 2. Check X-axis (left/right) screen boundaries and bounce back if necessary.
        if (bounds.right > frame.right) {
            bounds.offset(-2 * (bounds.right - frame.right), 0);
            nextDirection = inverseHorizontally(nextDirection);
        } else if (bounds.left < frame.left) {
            bounds.offset(2 * (frame.left - bounds.left), 0);
            nextDirection = inverseHorizontally(nextDirection);
        }

        // 3. Check Y-axis (top/bottom) screen boundaries and bounce back if necessary.
        if (bounds.bottom > frame.bottom) {
            bounds.offset(0, -2 * (bounds.bottom - frame.bottom));
            nextDirection = inverseVertically(nextDirection);
        } else if (bounds.top < frame.top) {
            bounds.offset(0, 2 * (frame.top - bounds.top));
            nextDirection = inverseVertically(nextDirection);
        }

        return nextDirection;
    }
}

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

package android.app;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.graphics.Rect;

/**
 * Represents location of an {@link ActivityManager.AppTask} that consists of the host display
 * identifier and rectangular bounds in the pixel-based coordinate system relative to host display.
 */
@FlaggedApi(com.android.window.flags.Flags.FLAG_ENABLE_WINDOW_REPOSITIONING_API)
public class TaskLocation {
    private final int mDisplayId;
    private final Rect mBounds;

    /**
     * Creates a {@link TaskLocation} with the given display ID and bounds.
     */
    public TaskLocation(int displayId, @NonNull Rect bounds) {
        mDisplayId = displayId;
        mBounds = bounds;
    }

    /**
     * Gets ID of the display.
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * Gets the bounds in the display, in the local coordinates of the display in pixels.
     */
    @NonNull public Rect getBounds() {
        return mBounds;
    }
}

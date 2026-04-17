/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.graphics.RecordingCanvas;

/**
 * These callbacks are used to communicate window configuration changes while the user is performing
 * window changes.
 * Note: Note that at the time of onWindowDragResizeStart the content size isn't known. A consumer
 * should therfore not draw anything before the additional onContentDraw call has arrived.
 * @hide
 */
public interface WindowCallbacks {

    /**
     * Called when a drag resize starts.
     */
    void onWindowDragResizeStart();

    /**
     * Called when a drag resize ends.
     */
    void onWindowDragResizeEnd();

    /**
     * The content will now be drawn to these bounds. Returns true if
     * a draw should be requested after the next content draw.
     */
    boolean onContentDrawn(int offsetX, int offsetY, int sizeX, int sizeY);

    /**
     * Called to request the window to draw one frame.
     * @param reportNextDraw Whether it should report when the requested draw finishes.
     */
    void onRequestDraw(boolean reportNextDraw);

    /**
     * Called after all the content has drawn and the callback now has the ability to draw something
     * on top of everything. Call {@link ViewRootImpl#requestInvalidateRootRenderNode} when this
     * content needs to be redrawn.
     *
     * @param canvas The canvas to draw on.
     */
    void onPostDraw(RecordingCanvas canvas);
}

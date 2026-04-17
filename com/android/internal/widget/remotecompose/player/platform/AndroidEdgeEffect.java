/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.internal.widget.remotecompose.player.platform;

import android.annotation.NonNull;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.widget.EdgeEffect;

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.ScrollingEdgeEffect;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;

/** Implement a scrolling edge effect on Android */
class AndroidEdgeEffect implements ScrollingEdgeEffect {
    EdgeEffect mEffect;
    int mDirection;

    RenderNode mNode;
    RecordingCanvas mRecordingCanvas;

    AndroidEdgeEffect(EdgeEffect effect, int direction) {
        mEffect = effect;
        mDirection = direction;
    }

    @Override
    public void reset() {
        mEffect.onAbsorb(0);
    }

    @Override
    public void pull(float value, float distance) {
        float delta = value / distance; // normalize
        mEffect.onPull(Math.abs(delta), 0.5f);
    }

    @Override
    public void release() {
        mEffect.onRelease();
        mNode = null;
        mRecordingCanvas = null;
    }

    @Override
    public void setSize(float width, float height) {
        if (mDirection == LEFT || mDirection == RIGHT) {
            mEffect.setSize((int) height, (int) width);
        } else {
            mEffect.setSize((int) width, (int) height);
        }
    }

    @Override
    public void apply(
            @NonNull PaintContext context,
            @NonNull Component component,
            float contentDimension,
            int phase) {
        if (!mEffect.isFinished()) {
            AndroidPaintContext paintContext = (AndroidPaintContext) context;
            if (phase == PRE_DRAW) {

                float distance = mEffect.getDistance() / 10f;
                if (distance > 0) {
                    switch (mDirection) {
                        case TOP:
                            context.matrixScale(1f, 1f + distance, component.getWidth() / 2f, 0f);
                            break;
                        case BOTTOM:
                            context.matrixScale(
                                    1f, 1f + distance, component.getWidth() / 2f, contentDimension);
                            break;
                        case LEFT:
                            context.matrixScale(1f + distance, 1f, 0f, component.getHeight() / 2f);
                            break;
                        case RIGHT:
                            context.matrixScale(
                                    1f + distance,
                                    1f,
                                    contentDimension,
                                    component.getHeight() / 2f);
                            break;
                    }
                    // if we switch to render nodes for scroll areas
                    // we could pass the canvas in paintContext, as EdgeEffect can directly
                    // apply a stretch to the corresponding render node
                    if (mNode == null) {
                        mNode = new RenderNode("empty");
                    }
                    if (mRecordingCanvas == null) {
                        mRecordingCanvas = mNode.beginRecording();
                    }
                    mEffect.draw(mRecordingCanvas);
                    context.needsRepaint();
                }
            }
        }
    }
}

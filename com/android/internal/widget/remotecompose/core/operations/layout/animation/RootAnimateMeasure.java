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
package com.android.internal.widget.remotecompose.core.operations.layout.animation;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;

/**
 * Specialized AnimateMeasure for root components that handles document origin transitions.
 */
public class RootAnimateMeasure extends AnimateMeasure {
    protected float mOriginalOriginX;
    protected float mOriginalOriginY;
    protected float mTargetOriginX;
    protected float mTargetOriginY;
    private float mRootInterpolatedX = 0f;
    private float mRootInterpolatedY = 0f;

    public RootAnimateMeasure(
            long startTime,
            @NonNull Component component,
            @NonNull ComponentMeasure original,
            @NonNull ComponentMeasure target,
            float originalOriginX,
            float originalOriginY,
            float targetOriginX,
            float targetOriginY,
            float duration,
            float durationVisibilityChange,
            @NonNull AnimationSpec.ANIMATION enterAnimation,
            @NonNull AnimationSpec.ANIMATION exitAnimation,
            int motionEasingType,
            int visibilityEasingType) {
        super(startTime, component, original, target,
                duration, durationVisibilityChange,
                enterAnimation, exitAnimation,
                motionEasingType, visibilityEasingType);
        this.mOriginalOriginX = originalOriginX;
        this.mOriginalOriginY = originalOriginY;
        this.mTargetOriginX = targetOriginX;
        this.mTargetOriginY = targetOriginY;
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        super.apply(context);
        float targetX = context.getDocument().getOriginX();
        float targetY = context.getDocument().getOriginY();
        mRootInterpolatedX = (mOriginalOriginX - targetX) * (1 - mP);
        mRootInterpolatedY = (mOriginalOriginY - targetY) * (1 - mP);
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        context.save();
        context.translate(mRootInterpolatedX, mRootInterpolatedY);
        super.paint(context);
        context.restore();
    }

    @Override
    public void updateTarget(@NonNull RemoteContext context, @NonNull ComponentMeasure measure,
            long currentTime) {
        float currentOriginX = mOriginalOriginX * (1 - mP) + mTargetOriginX * mP;
        float currentOriginY = mOriginalOriginY * (1 - mP) + mTargetOriginY * mP;

        super.updateTarget(context, measure, currentTime);

        mOriginalOriginX = currentOriginX;
        mOriginalOriginY = currentOriginY;

        float targetOriginX = context.getDocument().getOriginX();
        float targetOriginY = context.getDocument().getOriginY();

        if (mTargetOriginX != targetOriginX || mTargetOriginY != targetOriginY) {
            mTargetOriginX = targetOriginX;
            mTargetOriginY = targetOriginY;
            mStartTime = currentTime;
        }
    }
}

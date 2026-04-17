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

package com.android.internal.app;

import android.content.Context;
import android.util.AttributeSet;

import com.android.internal.widget.RecyclerView;

/**
 * A {@link RecyclerView} that respects a maximum height constraint during measurement.
 */
public class AppLockMaxHeightRecyclerView extends RecyclerView {
    private int mMaxHeight;

    public AppLockMaxHeightRecyclerView(Context context) {
        this(context, null);
    }

    public AppLockMaxHeightRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Load the max height from your dimension resource automatically
        mMaxHeight = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.app_lock_permission_review_app_list_expanded_height);
    }

    public AppLockMaxHeightRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (mMaxHeight > 0) {
            // MeasureSpec.AT_MOST ensures it wraps content until it hits mMaxHeight
            heightSpec = MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}

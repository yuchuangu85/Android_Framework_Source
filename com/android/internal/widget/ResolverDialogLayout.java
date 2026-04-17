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


package com.android.internal.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/*
 * A dialog container for ResolverActivity. This is drop-in replacement for ResolverDrawerLayout.
 *
 * This can be used by alternative layouts (e.g. applied via RRO) to display ResolverActivity in a
 * modal dialog instead of a bottom sheet.
 */
public class ResolverDialogLayout extends LinearLayout implements DismissableView {
    public ResolverDialogLayout(Context context) {
        super(context);
    }

    public ResolverDialogLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ResolverDialogLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ResolverDialogLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void setOnDismissListener(OnDismissListener listener) {
        setOnTouchListener((v, event) -> {
            // Any clicks outside the dialog dialog is on the shadow box. Clicks on the shadow
            // box should dismiss the dialog.
            if (!findChildUnder(event.getX(), event.getY())) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    listener.onDismiss();
                    return true;
                }
                // Handle ACTION_DOWN as well, otherwise the event will be cancelled and won't get
                // ACTION_UP.
                return event.getActionMasked() == MotionEvent.ACTION_DOWN;
            }
            return false;
        });

    }

    private boolean findChildUnder(float x, float y) {
        final int childCount = getChildCount();
        Rect hitRect = new Rect();
        for (int i = childCount - 1; i >= 0; i--) {
            getChildAt(i).getHitRect(hitRect);
            if (hitRect.contains(Math.round(x), Math.round(y))) {
                return true;
            }
        }
        return false;
    }
}

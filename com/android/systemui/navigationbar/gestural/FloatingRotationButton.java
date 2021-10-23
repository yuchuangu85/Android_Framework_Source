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

package com.android.systemui.navigationbar.gestural;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.navigationbar.RotationButton;
import com.android.systemui.navigationbar.RotationButtonController;
import com.android.systemui.navigationbar.buttons.KeyButtonDrawable;
import com.android.systemui.navigationbar.buttons.KeyButtonView;

import java.util.function.Consumer;

/** Containing logic for the rotation button on the physical left bottom corner of the screen. */
public class FloatingRotationButton implements RotationButton {

    private static final float BACKGROUND_ALPHA = 0.92f;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final KeyButtonView mKeyButtonView;
    private final int mDiameter;
    private final int mMargin;
    private KeyButtonDrawable mKeyButtonDrawable;
    private boolean mIsShowing;
    private boolean mCanShow = true;

    private RotationButtonController mRotationButtonController;
    private Consumer<Boolean> mVisibilityChangedCallback;

    public FloatingRotationButton(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mKeyButtonView = (KeyButtonView) LayoutInflater.from(mContext).inflate(
                R.layout.rotate_suggestion, null);
        mKeyButtonView.setVisibility(View.VISIBLE);

        Resources res = mContext.getResources();
        mDiameter = res.getDimensionPixelSize(R.dimen.floating_rotation_button_diameter);
        mMargin = Math.max(res.getDimensionPixelSize(R.dimen.floating_rotation_button_min_margin),
                res.getDimensionPixelSize(R.dimen.rounded_corner_content_padding));
    }

    @Override
    public void setRotationButtonController(RotationButtonController rotationButtonController) {
        mRotationButtonController = rotationButtonController;
        updateIcon(mRotationButtonController.getLightIconColor(),
                mRotationButtonController.getDarkIconColor());
    }

    @Override
    public void setVisibilityChangedCallback(Consumer<Boolean> visibilityChangedCallback) {
        mVisibilityChangedCallback = visibilityChangedCallback;
    }

    @Override
    public View getCurrentView() {
        return mKeyButtonView;
    }

    @Override
    public boolean show() {
        if (!mCanShow || mIsShowing) {
            return false;
        }
        mIsShowing = true;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(mDiameter, mDiameter,
                mMargin, mMargin, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, flags,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("FloatingRotationButton");
        lp.setFitInsetsTypes(0 /*types */);
        switch (mWindowManager.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_0:
                lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
                break;
            case Surface.ROTATION_90:
                lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                break;
            case Surface.ROTATION_180:
                lp.gravity = Gravity.TOP | Gravity.RIGHT;
                break;
            case Surface.ROTATION_270:
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                break;
            default:
                break;
        }
        mWindowManager.addView(mKeyButtonView, lp);
        if (mKeyButtonDrawable != null && mKeyButtonDrawable.canAnimate()) {
            mKeyButtonDrawable.resetAnimation();
            mKeyButtonDrawable.startAnimation();
        }
        mKeyButtonView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int i, int i1, int i2, int i3, int i4, int i5,
                    int i6, int i7) {
                if (mIsShowing && mVisibilityChangedCallback != null) {
                    mVisibilityChangedCallback.accept(true);
                }
                mKeyButtonView.removeOnLayoutChangeListener(this);
            }
        });
        return true;
    }

    @Override
    public boolean hide() {
        if (!mIsShowing) {
            return false;
        }
        mWindowManager.removeViewImmediate(mKeyButtonView);
        mIsShowing = false;
        if (mVisibilityChangedCallback != null) {
            mVisibilityChangedCallback.accept(false);
        }
        return true;
    }

    @Override
    public boolean isVisible() {
        return mIsShowing;
    }

    @Override
    public void updateIcon(int lightIconColor, int darkIconColor) {
        Color ovalBackgroundColor = Color.valueOf(Color.red(darkIconColor),
                Color.green(darkIconColor), Color.blue(darkIconColor), BACKGROUND_ALPHA);
        mKeyButtonDrawable = KeyButtonDrawable.create(mRotationButtonController.getContext(),
                lightIconColor, darkIconColor, mRotationButtonController.getIconResId(),
                false /* shadow */, ovalBackgroundColor);
        mKeyButtonView.setImageDrawable(mKeyButtonDrawable);
    }

    @Override
    public void setOnClickListener(View.OnClickListener onClickListener) {
        mKeyButtonView.setOnClickListener(onClickListener);
    }

    @Override
    public void setOnHoverListener(View.OnHoverListener onHoverListener) {
        mKeyButtonView.setOnHoverListener(onHoverListener);
    }

    @Override
    public KeyButtonDrawable getImageDrawable() {
        return mKeyButtonDrawable;
    }

    @Override
    public void setDarkIntensity(float darkIntensity) {
        mKeyButtonView.setDarkIntensity(darkIntensity);
    }

    @Override
    public void setCanShowRotationButton(boolean canShow) {
        mCanShow = canShow;
        if (!mCanShow) {
            hide();
        }
    }
}

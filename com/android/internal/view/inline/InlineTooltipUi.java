/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.internal.view.inline;

import static android.view.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.transition.Transition;
import android.util.Slog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.inline.InlineContentView;

import java.io.PrintWriter;

/**
 * UI container for the inline suggestion tooltip.
 */
public final class InlineTooltipUi extends PopupWindow implements AutoCloseable {
    private static final String TAG = "InlineTooltipUi";

    private final WindowManager mWm;
    private final ViewGroup mContentContainer;

    private boolean mShowing;

    private WindowManager.LayoutParams mWindowLayoutParams;

    private final View.OnAttachStateChangeListener mAnchorOnAttachStateChangeListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    /* ignore - handled by the super class */
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    dismiss();
                }
            };

    private final View.OnLayoutChangeListener mAnchoredOnLayoutChangeListener =
            new View.OnLayoutChangeListener() {
                int mHeight;
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (mHeight != bottom - top) {
                        mHeight = bottom - top;
                        adjustPosition();
                    }
                }
            };

    public InlineTooltipUi(@NonNull Context context) {
        mContentContainer = new LinearLayout(new ContextWrapper(context));
        mWm = context.getSystemService(WindowManager.class);

        setTouchModal(false);
        setOutsideTouchable(true);
        setInputMethodMode(INPUT_METHOD_NOT_NEEDED);
        setFocusable(false);
    }

    /**
     * Sets the content view for inline suggestions tooltip
     * @param v the content view of {@link android.widget.inline.InlineContentView}
     */
    public void setTooltipView(@NonNull InlineContentView v) {
        mContentContainer.removeAllViews();
        mContentContainer.addView(v);
        mContentContainer.setVisibility(View.VISIBLE);
    }

    @Override
    public void close() {
        hide();
    }

    @Override
    protected boolean hasContentView() {
        return true;
    }

    @Override
    protected boolean hasDecorView() {
        return true;
    }

    @Override
    protected WindowManager.LayoutParams getDecorViewLayoutParams() {
        return mWindowLayoutParams;
    }

    /**
     * The effective {@code update} method that should be called by its clients.
     */
    public void update(View anchor) {
        // set to the application type with the highest z-order
        setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL);

        // The first time to show up, the height of tooltip is zero,
        // so set the offset Y to 2 * anchor height.
        final int achoredHeight = mContentContainer.getHeight();
        final int offsetY = (achoredHeight == 0)
                ? -anchor.getHeight() << 1 : -anchor.getHeight() - achoredHeight;
        if (!isShowing()) {
            setWidth(WindowManager.LayoutParams.WRAP_CONTENT);
            setHeight(WindowManager.LayoutParams.WRAP_CONTENT);
            showAsDropDown(anchor, 0 , offsetY, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        } else {
            update(anchor, 0 , offsetY, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    protected void update(View anchor, WindowManager.LayoutParams params) {
        // update content view for the anchor is scrolling
        if (anchor.isVisibleToUser()) {
            show(params);
        } else {
            hide();
        }
    }

    @Override
    public void showAsDropDown(View anchor, int xoff, int yoff, int gravity) {
        if (isShowing()) {
            return;
        }

        setShowing(true);
        setDropDown(true);
        attachToAnchor(anchor, xoff, yoff, gravity);
        final WindowManager.LayoutParams p = mWindowLayoutParams = createPopupLayoutParams(
                anchor.getWindowToken());
        final boolean aboveAnchor = findDropDownPosition(anchor, p, xoff, yoff,
                p.width, p.height, gravity, getAllowScrollingAnchorParent());
        updateAboveAnchor(aboveAnchor);
        p.accessibilityIdOfAnchor = anchor.getAccessibilityViewId();
        p.packageName = anchor.getContext().getPackageName();
        show(p);
    }

    @Override
    protected void attachToAnchor(View anchor, int xoff, int yoff, int gravity) {
        super.attachToAnchor(anchor, xoff, yoff, gravity);
        anchor.addOnAttachStateChangeListener(mAnchorOnAttachStateChangeListener);
    }

    @Override
    protected void detachFromAnchor() {
        final View anchor = getAnchor();
        if (anchor != null) {
            anchor.removeOnAttachStateChangeListener(mAnchorOnAttachStateChangeListener);
        }
        super.detachFromAnchor();
    }

    @Override
    public void dismiss() {
        if (!isShowing() || isTransitioningToDismiss()) {
            return;
        }

        setShowing(false);
        setTransitioningToDismiss(true);

        hide();
        detachFromAnchor();
        if (getOnDismissListener() != null) {
            getOnDismissListener().onDismiss();
        }
    }

    private void adjustPosition() {
        View anchor = getAnchor();
        if (anchor == null) return;
        update(anchor);
    }

    private void show(WindowManager.LayoutParams params) {
        if (sVerbose) {
            Slog.v(TAG, "show()");
        }
        mWindowLayoutParams = params;

        try {
            params.packageName = "android";
            params.setTitle("Autofill Inline Tooltip"); // Title is set for debugging purposes
            if (!mShowing) {
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                params.privateFlags |=
                        WindowManager.LayoutParams.PRIVATE_FLAG_NOT_MAGNIFIABLE;
                mContentContainer.addOnLayoutChangeListener(mAnchoredOnLayoutChangeListener);
                mWm.addView(mContentContainer, params);
                mShowing = true;
            } else {
                mWm.updateViewLayout(mContentContainer, params);
            }
        } catch (WindowManager.BadTokenException e) {
            Slog.d(TAG, "Failed with token " + params.token + " gone.");
        } catch (IllegalStateException e) {
            // WM throws an ISE if mContentView was added twice; this should never happen -
            // since show() and hide() are always called in the UIThread - but when it does,
            // it should not crash the system.
            Slog.wtf(TAG, "Exception showing window " + params, e);
        }
    }

    private void hide() {
        if (sVerbose) {
            Slog.v(TAG, "hide()");
        }
        try {
            if (mShowing) {
                mContentContainer.removeOnLayoutChangeListener(mAnchoredOnLayoutChangeListener);
                mWm.removeView(mContentContainer);
                mShowing = false;
            }
        } catch (IllegalStateException e) {
            // WM might thrown an ISE when removing the mContentView; this should never
            // happen - since show() and hide() are always called in the UIThread - but if it
            // does, it should not crash the system.
            Slog.e(TAG, "Exception hiding window ", e);
        }
    }

    @Override
    public int getAnimationStyle() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public Drawable getBackground() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public View getContentView() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public float getElevation() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public Transition getEnterTransition() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public Transition getExitTransition() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setBackgroundDrawable(Drawable background) {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setContentView(View contentView) {
        if (contentView != null) {
            throw new IllegalStateException("You can't call this!");
        }
    }

    @Override
    public void setElevation(float elevation) {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setEnterTransition(Transition enterTransition) {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setExitTransition(Transition exitTransition) {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setTouchInterceptor(View.OnTouchListener l) {
        throw new IllegalStateException("You can't call this!");
    }

    /**
     * Dumps status
     */
    public void dump(@NonNull PrintWriter pw, @Nullable String prefix) {

        pw.print(prefix);

        if (mContentContainer != null) {
            pw.print(prefix); pw.print("Window: ");
            final String prefix2 = prefix + "  ";
            pw.println();
            pw.print(prefix2); pw.print("showing: "); pw.println(mShowing);
            pw.print(prefix2); pw.print("view: "); pw.println(mContentContainer);
            if (mWindowLayoutParams != null) {
                pw.print(prefix2); pw.print("params: "); pw.println(mWindowLayoutParams);
            }
            pw.print(prefix2); pw.print("screen coordinates: ");
            if (mContentContainer == null) {
                pw.println("N/A");
            } else {
                final int[] coordinates = mContentContainer.getLocationOnScreen();
                pw.print(coordinates[0]); pw.print("x"); pw.println(coordinates[1]);
            }
        }
    }
}

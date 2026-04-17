/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.internal.policy;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.IdRes;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ActionMode.Callback;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.PopupWindow;

import com.android.internal.R;
import com.android.internal.view.FloatingActionMode;
import com.android.internal.view.StandaloneActionMode;
import com.android.internal.widget.ActionBarContextView;
import com.android.internal.widget.floatingtoolbar.FloatingToolbar;

/**
 * Class to create and show views for an {@link ActionMode}.
 *
 * <p>Normally this is controlled by a {@link DecorView}.
 */
public class ActionModeController {
    private static final String TAG = "ActionMode";

    private final ViewGroup mContainerView;
    private final Context mContext;

    @IdRes
    private final int mStubViewId;

    @Nullable
    private PhoneWindow mWindow;

    private ActionMode mPrimaryActionMode;
    private ActionMode mFloatingActionMode;
    private ActionBarContextView mPrimaryActionModeView;
    private PopupWindow mPrimaryActionModePopup;
    private Runnable mShowPrimaryActionModePopup;
    private ObjectAnimator mFadeAnim;
    private FloatingToolbar mFloatingToolbar;
    private View mFloatingActionModeOriginatingView;
    private ViewTreeObserver.OnPreDrawListener mFloatingToolbarPreDrawListener;

    // Paddings loaded from R.attr.actionModeStyle.
    private int mActionModeViewInternalPaddingLeft;
    private int mActionModeViewInternalPaddingTop;
    private int mActionModeViewInternalPaddingRight;
    private int mActionModeViewInternalPaddingBottom;

    private Rect mTempRect;

    public ActionModeController(ViewGroup containerView, Context context, @IdRes int stubViewId) {
        mContainerView = containerView;
        mContext = context;
        mStubViewId = stubViewId;
    }

    public void setWindow(PhoneWindow window) {
        mWindow = window;
    }

    /**
     * Handles the dispatch of KeyEvents to check for Action Mode dismissal (Back key).
     * @return true if the event was handled by the Action Mode logic.
     */
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Give priority to closing action modes if applicable.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            final int action = event.getAction();
            // Back cancels action modes first.
            if (mPrimaryActionMode != null) {
                if (action == KeyEvent.ACTION_UP) {
                    mPrimaryActionMode.finish();
                }
                return true;
            }
        }
        return false;
    }

    public boolean hasPrimaryActionMode() {
        return mPrimaryActionMode != null;
    }

    @Nullable
    private Window.Callback getWindowCallback() {
        if (mWindow == null || mWindow.isDestroyed()) {
            return null;
        }
        return mWindow.getCallback();
    }

    public ActionMode startActionMode(View originatingView, Callback callback, int type) {
        ActionMode.Callback2 wrappedCallback = new ActionModeCallback2Wrapper(callback);
        ActionMode mode = null;

        final var windowCallback = getWindowCallback();
        if (windowCallback != null) {
            try {
                mode = windowCallback.onWindowStartingActionMode(wrappedCallback, type);
            } catch (AbstractMethodError ame) {
                // Older apps might not implement the typed version of this method.
                if (type == ActionMode.TYPE_PRIMARY) {
                    try {
                        mode = windowCallback.onWindowStartingActionMode(wrappedCallback);
                    } catch (AbstractMethodError ame2) {
                        // Older apps might not implement this callback method at all.
                    }
                }
            }
        }
        if (mode != null) {
            if (mode.getType() == ActionMode.TYPE_PRIMARY) {
                cleanupPrimaryActionMode();
                mPrimaryActionMode = mode;
            } else if (mode.getType() == ActionMode.TYPE_FLOATING) {
                if (mFloatingActionMode != null) {
                    mFloatingActionMode.finish();
                }
                mFloatingActionMode = mode;
            }
        } else {
            mode = createActionMode(type, wrappedCallback, originatingView);
            if (mode != null && wrappedCallback.onCreateActionMode(mode, mode.getMenu())) {
                setHandledActionMode(mode);
            } else {
                mode = null;
            }
        }
        if (mode != null && windowCallback != null) {
            try {
                windowCallback.onActionModeStarted(mode);
            } catch (AbstractMethodError ame) {
                // Older apps might not implement this callback method.
            }
        }
        return mode;
    }

    /**
     * Updates the insets for the Primary Action Mode view.
     */
    public WindowInsets updateActionModeInsets(WindowInsets insets) {
        if (mPrimaryActionModeView != null) {
            if (mPrimaryActionModeView.getLayoutParams() instanceof MarginLayoutParams mlp) {
                boolean mlpChanged = false;
                if (mPrimaryActionModeView.isShown()) {
                    if (mTempRect == null) {
                        mTempRect = new Rect();
                    }
                    final Rect rect = mTempRect;
                    // Apply the insets that have not been applied by the contentParent yet.
                    final WindowInsets innerInsets =
                            mWindow.mContentParent.computeSystemWindowInsets(insets, rect);
                    final boolean consumesSystemWindowInsetsTop;
                    final Insets systemWindowInsets = innerInsets.getSystemWindowInsets();
                    final Insets newMargin = innerInsets.getInsets(
                            WindowInsets.Type.navigationBars());

                    // Don't extend into navigation bar area so the width can align with status
                    // bar color view.
                    if (mlp.leftMargin != newMargin.left
                            || mlp.rightMargin != newMargin.right) {
                        mlpChanged = true;
                        mlp.leftMargin = newMargin.left;
                        mlp.rightMargin = newMargin.right;
                    }

                    mPrimaryActionModeView.setPadding(
                            mActionModeViewInternalPaddingLeft + systemWindowInsets.left
                                    - newMargin.left,
                            mActionModeViewInternalPaddingTop + systemWindowInsets.top,
                            mActionModeViewInternalPaddingRight + systemWindowInsets.right
                                    - newMargin.right,
                            mActionModeViewInternalPaddingBottom);
                    consumesSystemWindowInsetsTop = systemWindowInsets.top > 0;

                    // We only need to consume the insets if the action
                    // mode is overlaid on the app content.
                    final boolean nonOverlay = mWindow == null || (mWindow.getLocalFeaturesPrivate()
                            & (1 << Window.FEATURE_ACTION_MODE_OVERLAY)) == 0;
                    if (nonOverlay && consumesSystemWindowInsetsTop) {
                        insets = insets.inset(0, insets.getSystemWindowInsetTop(), 0, 0);
                    }
                }
                if (mlpChanged) {
                    mPrimaryActionModeView.setLayoutParams(mlp);
                }
            }
        }
        return insets;
    }

    private ActionMode createActionMode(
            int type, ActionMode.Callback2 callback, View originatingView) {
        switch (type) {
            case ActionMode.TYPE_PRIMARY:
            default:
                return createStandaloneActionMode(callback);
            case ActionMode.TYPE_FLOATING:
                return createFloatingActionMode(originatingView, callback);
        }
    }

    private void setHandledActionMode(ActionMode mode) {
        if (mode.getType() == ActionMode.TYPE_PRIMARY) {
            setHandledPrimaryActionMode(mode);
        } else if (mode.getType() == ActionMode.TYPE_FLOATING) {
            setHandledFloatingActionMode(mode);
        }
    }

    private ActionMode createStandaloneActionMode(Callback callback) {
        endOnGoingFadeAnimation();
        cleanupPrimaryActionMode();
        // We want to create new mPrimaryActionModeView in two cases: if there is no existing
        // instance at all, or if there is one, but it is detached from window. The latter case
        // might happen when app is resized in multi-window mode and decor view is preserved
        // along with the main app window. Keeping mPrimaryActionModeView reference doesn't cause
        // app memory leaks because killMode() is called when the dismiss animation ends and from
        // cleanupPrimaryActionMode() invocation above.
        if (mPrimaryActionModeView == null || !mPrimaryActionModeView.isAttachedToWindow()) {
            if ((mWindow != null && mWindow.isFloating()) || mStubViewId == View.NO_ID) {
                // Use the action bar theme.
                final TypedValue outValue = new TypedValue();
                final Resources.Theme baseTheme = mContext.getTheme();
                baseTheme.resolveAttribute(R.attr.actionBarTheme, outValue, true);

                final Context actionBarContext;
                if (outValue.resourceId != 0) {
                    final Resources.Theme actionBarTheme = mContext.getResources().newTheme();
                    actionBarTheme.setTo(baseTheme);
                    actionBarTheme.applyStyle(outValue.resourceId, true);

                    actionBarContext = new ContextThemeWrapper(mContext, 0);
                    actionBarContext.getTheme().setTo(actionBarTheme);
                } else {
                    actionBarContext = mContext;
                }

                mPrimaryActionModeView = new ActionBarContextView(actionBarContext);
                initializeActionModeViewInternalPadding();
                mPrimaryActionModePopup = new PopupWindow(actionBarContext, null,
                        R.attr.actionModePopupWindowStyle);
                mPrimaryActionModePopup.setWindowLayoutType(
                        WindowManager.LayoutParams.TYPE_APPLICATION);
                mPrimaryActionModePopup.setContentView(mPrimaryActionModeView);
                mPrimaryActionModePopup.setWidth(MATCH_PARENT);

                actionBarContext.getTheme().resolveAttribute(
                        R.attr.actionBarSize, outValue, true);
                final int height = TypedValue.complexToDimensionPixelSize(outValue.data,
                        actionBarContext.getResources().getDisplayMetrics());
                mPrimaryActionModeView.setContentHeight(height);
                mPrimaryActionModePopup.setHeight(WRAP_CONTENT);
                mShowPrimaryActionModePopup = new Runnable() {
                    public void run() {
                        mPrimaryActionModePopup.showAtLocation(
                                mPrimaryActionModeView.getApplicationWindowToken(),
                                Gravity.TOP | Gravity.FILL_HORIZONTAL, 0, 0);
                        endOnGoingFadeAnimation();

                        if (shouldAnimatePrimaryActionModeView()) {
                            mFadeAnim = ObjectAnimator.ofFloat(mPrimaryActionModeView, View.ALPHA,
                                    0f, 1f);
                            mFadeAnim.addListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationStart(Animator animation) {
                                    mPrimaryActionModeView.setVisibility(VISIBLE);
                                }

                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    mPrimaryActionModeView.setAlpha(1f);
                                    mFadeAnim = null;
                                }
                            });
                            mFadeAnim.start();
                        } else {
                            mPrimaryActionModeView.setAlpha(1f);
                            mPrimaryActionModeView.setVisibility(VISIBLE);
                        }
                    }
                };
            } else {
                ViewStub stub = mContainerView.findViewById(mStubViewId);
                if (stub != null) {
                    mPrimaryActionModeView = (ActionBarContextView) stub.inflate();
                    initializeActionModeViewInternalPadding();
                    mPrimaryActionModePopup = null;
                }
            }
        }
        if (mPrimaryActionModeView != null) {
            mPrimaryActionModeView.killMode();
            return new StandaloneActionMode(
                    mPrimaryActionModeView.getContext(), mPrimaryActionModeView,
                    callback, mPrimaryActionModePopup == null);
        }
        return null;
    }

    private void initializeActionModeViewInternalPadding() {
        mActionModeViewInternalPaddingLeft = mPrimaryActionModeView.getPaddingLeft();
        mActionModeViewInternalPaddingTop = mPrimaryActionModeView.getPaddingTop();
        mActionModeViewInternalPaddingRight = mPrimaryActionModeView.getPaddingRight();
        mActionModeViewInternalPaddingBottom = mPrimaryActionModeView.getPaddingBottom();
    }

    private void endOnGoingFadeAnimation() {
        if (mFadeAnim != null) {
            mFadeAnim.end();
        }
    }

    private void setHandledPrimaryActionMode(ActionMode mode) {
        endOnGoingFadeAnimation();
        mPrimaryActionMode = mode;
        mPrimaryActionMode.invalidate();
        mPrimaryActionModeView.initForMode(mPrimaryActionMode);
        if (mPrimaryActionModePopup != null) {
            mContainerView.post(mShowPrimaryActionModePopup);
        } else {
            if (shouldAnimatePrimaryActionModeView()) {
                mFadeAnim = ObjectAnimator.ofFloat(mPrimaryActionModeView, View.ALPHA, 0f, 1f);
                mFadeAnim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        mPrimaryActionModeView.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPrimaryActionModeView.setAlpha(1f);
                        mFadeAnim = null;
                    }
                });
                mFadeAnim.start();
            } else {
                mPrimaryActionModeView.setAlpha(1f);
                mPrimaryActionModeView.setVisibility(View.VISIBLE);
            }
        }
        mPrimaryActionModeView.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    private boolean shouldAnimatePrimaryActionModeView() {
        return mContainerView.isLaidOut();
    }

    private ActionMode createFloatingActionMode(
            View originatingView, ActionMode.Callback2 callback) {
        if (mFloatingActionMode != null) {
            mFloatingActionMode.finish();
        }
        cleanupFloatingActionModeViews();
        mFloatingToolbar = new FloatingToolbar(mContainerView);
        final FloatingActionMode mode =
                new FloatingActionMode(mContext, callback, originatingView, mFloatingToolbar);
        mFloatingActionModeOriginatingView = originatingView;
        mFloatingToolbarPreDrawListener =
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        mode.updateViewLocationInWindow();
                        return true;
                    }
                };
        return mode;
    }

    private void setHandledFloatingActionMode(ActionMode mode) {
        mFloatingActionMode = mode;
        mFloatingActionMode.invalidate(); // Will show the floating toolbar if necessary.
        mFloatingActionModeOriginatingView.getViewTreeObserver()
                .addOnPreDrawListener(mFloatingToolbarPreDrawListener);
    }

    private void cleanupPrimaryActionMode() {
        if (mPrimaryActionMode != null) {
            mPrimaryActionMode.finish();
            mPrimaryActionMode = null;
        }
        if (mPrimaryActionModeView != null) {
            mPrimaryActionModeView.killMode();
        }
    }

    private void cleanupFloatingActionModeViews() {
        if (mFloatingToolbar != null) {
            mFloatingToolbar.dismiss();
            mFloatingToolbar = null;
        }
        if (mFloatingActionModeOriginatingView != null) {
            if (mFloatingToolbarPreDrawListener != null) {
                mFloatingActionModeOriginatingView.getViewTreeObserver()
                        .removeOnPreDrawListener(mFloatingToolbarPreDrawListener);
                mFloatingToolbarPreDrawListener = null;
            }
            mFloatingActionModeOriginatingView = null;
        }
    }

    public void onWindowFocusChanged(boolean hasWindowFocus) {
        if (mPrimaryActionMode != null) {
            mPrimaryActionMode.onWindowFocusChanged(hasWindowFocus);
        }
        if (mFloatingActionMode != null) {
            mFloatingActionMode.onWindowFocusChanged(hasWindowFocus);
        }
    }

    public void onDetachedFromWindow() {
        if (mPrimaryActionModePopup != null) {
            mContainerView.removeCallbacks(mShowPrimaryActionModePopup);
            if (mPrimaryActionModePopup.isShowing()) {
                mPrimaryActionModePopup.dismiss();
            }
            mPrimaryActionModePopup = null;
        }
        if (mFloatingToolbar != null) {
            mFloatingToolbar.dismiss();
            mFloatingToolbar = null;
        }
    }

    /**
     * Clears out internal references when the action mode is destroyed.
     */
    private class ActionModeCallback2Wrapper extends ActionMode.Callback2 {
        private final Callback mWrapped;

        public ActionModeCallback2Wrapper(Callback wrapped) {
            mWrapped = wrapped;
        }

        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            return mWrapped.onCreateActionMode(mode, menu);
        }

        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            mContainerView.requestFitSystemWindows();
            return mWrapped.onPrepareActionMode(mode, menu);
        }

        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return mWrapped.onActionItemClicked(mode, item);
        }

        public void onDestroyActionMode(ActionMode mode) {
            mWrapped.onDestroyActionMode(mode);
            final boolean isMncApp = mContext.getApplicationInfo().targetSdkVersion
                    >= android.os.Build.VERSION_CODES.M;
            final boolean isPrimary;
            final boolean isFloating;
            if (isMncApp) {
                isPrimary = mode == mPrimaryActionMode;
                isFloating = mode == mFloatingActionMode;
                if (!isPrimary && mode.getType() == ActionMode.TYPE_PRIMARY) {
                    Log.e(TAG, "Destroying unexpected ActionMode instance of TYPE_PRIMARY; "
                            + mode + " was not the current primary action mode! Expected "
                            + mPrimaryActionMode);
                }
                if (!isFloating && mode.getType() == ActionMode.TYPE_FLOATING) {
                    Log.e(TAG, "Destroying unexpected ActionMode instance of TYPE_FLOATING; "
                            + mode + " was not the current floating action mode! Expected "
                            + mFloatingActionMode);
                }
            } else {
                isPrimary = mode.getType() == ActionMode.TYPE_PRIMARY;
                isFloating = mode.getType() == ActionMode.TYPE_FLOATING;
            }
            if (isPrimary) {
                if (mPrimaryActionModePopup != null) {
                    mContainerView.removeCallbacks(mShowPrimaryActionModePopup);
                }
                if (mPrimaryActionModeView != null) {
                    endOnGoingFadeAnimation();
                    // Store action mode view reference, so we can access it safely when animation
                    // ends. mPrimaryActionModePopup is set together with mPrimaryActionModeView,
                    // so no need to store reference to it in separate variable.
                    final ActionBarContextView lastActionModeView = mPrimaryActionModeView;
                    mFadeAnim = ObjectAnimator.ofFloat(mPrimaryActionModeView, View.ALPHA,
                            1f, 0f);
                    mFadeAnim.addListener(new Animator.AnimatorListener() {

                        @Override
                        public void onAnimationStart(Animator animation) {
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (lastActionModeView == mPrimaryActionModeView) {
                                lastActionModeView.setVisibility(GONE);
                                if (mPrimaryActionModePopup != null) {
                                    mPrimaryActionModePopup.dismiss();
                                }
                                lastActionModeView.killMode();
                                mFadeAnim = null;
                                mContainerView.requestApplyInsets();
                            }
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                        }

                        @Override
                        public void onAnimationRepeat(Animator animation) {
                        }
                    });
                    mFadeAnim.start();
                }

                mPrimaryActionMode = null;
            } else if (isFloating) {
                cleanupFloatingActionModeViews();
                mFloatingActionMode = null;
            }
            final var windowCallback = getWindowCallback();
            if (windowCallback != null) {
                try {
                    windowCallback.onActionModeFinished(mode);
                } catch (AbstractMethodError ame) {
                    // Older apps might not implement this callback method.
                }
            }
            mContainerView.requestFitSystemWindows();
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (mWrapped instanceof ActionMode.Callback2) {
                ((ActionMode.Callback2) mWrapped).onGetContentRect(mode, view, outRect);
            } else {
                super.onGetContentRect(mode, view, outRect);
            }
        }
    }
}

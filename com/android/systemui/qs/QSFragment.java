/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.media.dagger.MediaModule.QS_PANEL;
import static com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL;
import static com.android.systemui.statusbar.DisableFlagsLogger.DisableState;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout.LayoutParams;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.animation.ShadeInterpolation;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSContainerController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSFragmentComponent;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.util.LifecycleFragment;
import com.android.systemui.util.Utils;

import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

public class QSFragment extends LifecycleFragment implements QS, CommandQueue.Callbacks,
        StatusBarStateController.StateListener {
    private static final String TAG = "QS";
    private static final boolean DEBUG = false;
    private static final String EXTRA_EXPANDED = "expanded";
    private static final String EXTRA_LISTENING = "listening";

    private final Rect mQsBounds = new Rect();
    private final StatusBarStateController mStatusBarStateController;
    private final FalsingManager mFalsingManager;
    private final KeyguardBypassController mBypassController;
    private boolean mQsExpanded;
    private boolean mHeaderAnimating;
    private boolean mStackScrollerOverscrolling;

    private long mDelay;

    private QSAnimator mQSAnimator;
    private HeightListener mPanelView;
    private QSSquishinessController mQSSquishinessController;
    protected QuickStatusBarHeader mHeader;
    protected NonInterceptingScrollView mQSPanelScrollView;
    private QSDetail mQSDetail;
    private boolean mListening;
    private QSContainerImpl mContainer;
    private int mLayoutDirection;
    private QSFooter mFooter;
    private float mLastQSExpansion = -1;
    private float mLastPanelFraction;
    private float mSquishinessFraction = 1;
    private boolean mQsDisabled;

    private final RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;
    private final CommandQueue mCommandQueue;
    private final QSDetailDisplayer mQsDetailDisplayer;
    private final MediaHost mQsMediaHost;
    private final MediaHost mQqsMediaHost;
    private final QSFragmentComponent.Factory mQsComponentFactory;
    private final QSFragmentDisableFlagsLogger mQsFragmentDisableFlagsLogger;
    private final QSTileHost mHost;
    private boolean mShowCollapsedOnKeyguard;
    private boolean mLastKeyguardAndExpanded;
    /**
     * The last received state from the controller. This should not be used directly to check if
     * we're on keyguard but use {@link #isKeyguardState()} instead since that is more accurate
     * during state transitions which often call into us.
     */
    private int mState;
    private QSContainerImplController mQSContainerImplController;
    private int[] mTmpLocation = new int[2];
    private int mLastViewHeight;
    private float mLastHeaderTranslation;
    private QSPanelController mQSPanelController;
    private QuickQSPanelController mQuickQSPanelController;
    private QSCustomizerController mQSCustomizerController;
    private ScrollListener mScrollListener;
    /**
     * When true, QS will translate from outside the screen. It will be clipped with parallax
     * otherwise.
     */
    private boolean mInSplitShade;
    private boolean mPulseExpanding;

    /**
     * Are we currently transitioning from lockscreen to the full shade?
     */
    private boolean mTransitioningToFullShade;

    /**
     * Whether the next Quick settings
     */
    private boolean mAnimateNextQsUpdate;

    private DumpManager mDumpManager;

    /**
     * Progress of pull down from the center of the lock screen.
     * @see com.android.systemui.statusbar.LockscreenShadeTransitionController
     */
    private float mFullShadeProgress;

    @Inject
    public QSFragment(RemoteInputQuickSettingsDisabler remoteInputQsDisabler,
            QSTileHost qsTileHost,
            StatusBarStateController statusBarStateController, CommandQueue commandQueue,
            QSDetailDisplayer qsDetailDisplayer, @Named(QS_PANEL) MediaHost qsMediaHost,
            @Named(QUICK_QS_PANEL) MediaHost qqsMediaHost,
            KeyguardBypassController keyguardBypassController,
            QSFragmentComponent.Factory qsComponentFactory,
            QSFragmentDisableFlagsLogger qsFragmentDisableFlagsLogger,
            FalsingManager falsingManager, DumpManager dumpManager) {
        mRemoteInputQuickSettingsDisabler = remoteInputQsDisabler;
        mCommandQueue = commandQueue;
        mQsDetailDisplayer = qsDetailDisplayer;
        mQsMediaHost = qsMediaHost;
        mQqsMediaHost = qqsMediaHost;
        mQsComponentFactory = qsComponentFactory;
        mQsFragmentDisableFlagsLogger = qsFragmentDisableFlagsLogger;
        commandQueue.observe(getLifecycle(), this);
        mHost = qsTileHost;
        mFalsingManager = falsingManager;
        mBypassController = keyguardBypassController;
        mStatusBarStateController = statusBarStateController;
        mDumpManager = dumpManager;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        inflater = inflater.cloneInContext(new ContextThemeWrapper(getContext(),
                R.style.Theme_SystemUI_QuickSettings));
        return inflater.inflate(R.layout.qs_panel, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        QSFragmentComponent qsFragmentComponent = mQsComponentFactory.create(this);
        mQSPanelController = qsFragmentComponent.getQSPanelController();
        mQuickQSPanelController = qsFragmentComponent.getQuickQSPanelController();

        mQSPanelController.init();
        mQuickQSPanelController.init();

        mQSPanelScrollView = view.findViewById(R.id.expanded_qs_scroll_view);
        mQSPanelScrollView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    updateQsBounds();
                });
        mQSPanelScrollView.setOnScrollChangeListener(
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    // Lazily update animators whenever the scrolling changes
                    mQSAnimator.requestAnimatorUpdate();
                    mHeader.setExpandedScrollAmount(scrollY);
                    if (mScrollListener != null) {
                        mScrollListener.onQsPanelScrollChanged(scrollY);
                    }
        });
        mQSDetail = view.findViewById(R.id.qs_detail);
        mHeader = view.findViewById(R.id.header);
        mQSPanelController.setHeaderContainer(view.findViewById(R.id.header_text_container));
        mFooter = qsFragmentComponent.getQSFooter();

        mQsDetailDisplayer.setQsPanelController(mQSPanelController);

        mQSContainerImplController = qsFragmentComponent.getQSContainerImplController();
        mQSContainerImplController.init();
        mContainer = mQSContainerImplController.getView();
        mDumpManager.registerDumpable(mContainer.getClass().getName(), mContainer);

        mQSDetail.setQsPanel(mQSPanelController, mHeader, mFooter, mFalsingManager);
        mQSAnimator = qsFragmentComponent.getQSAnimator();
        mQSSquishinessController = qsFragmentComponent.getQSSquishinessController();

        mQSCustomizerController = qsFragmentComponent.getQSCustomizerController();
        mQSCustomizerController.init();
        mQSCustomizerController.setQs(this);
        if (savedInstanceState != null) {
            setExpanded(savedInstanceState.getBoolean(EXTRA_EXPANDED));
            setListening(savedInstanceState.getBoolean(EXTRA_LISTENING));
            setEditLocation(view);
            mQSCustomizerController.restoreInstanceState(savedInstanceState);
            if (mQsExpanded) {
                mQSPanelController.getTileLayout().restoreInstanceState(savedInstanceState);
            }
        }
        setHost(mHost);
        mStatusBarStateController.addCallback(this);
        onStateChanged(mStatusBarStateController.getState());
        view.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    boolean sizeChanged = (oldTop - oldBottom) != (top - bottom);
                    if (sizeChanged) {
                        setQsExpansion(mLastQSExpansion, mLastPanelFraction,
                                mLastHeaderTranslation, mSquishinessFraction);
                    }
                });
        mQSPanelController.setUsingHorizontalLayoutChangeListener(
                () -> {
                    // The hostview may be faded out in the horizontal layout. Let's make sure to
                    // reset the alpha when switching layouts. This is fine since the animator will
                    // update the alpha if it's not supposed to be 1.0f
                    mQSPanelController.getMediaHost().getHostView().setAlpha(1.0f);
                    mQSAnimator.requestAnimatorUpdate();
                });
    }

    @Override
    public void setScrollListener(ScrollListener listener) {
        mScrollListener = listener;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStatusBarStateController.removeCallback(this);
        if (mListening) {
            setListening(false);
        }
        mQSCustomizerController.setQs(null);
        mQsDetailDisplayer.setQsPanelController(null);
        mScrollListener = null;
        mDumpManager.unregisterDumpable(mContainer.getClass().getName());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(EXTRA_EXPANDED, mQsExpanded);
        outState.putBoolean(EXTRA_LISTENING, mListening);
        mQSCustomizerController.saveInstanceState(outState);
        if (mQsExpanded) {
            mQSPanelController.getTileLayout().saveInstanceState(outState);
        }
    }

    @VisibleForTesting
    boolean isListening() {
        return mListening;
    }

    @VisibleForTesting
    boolean isExpanded() {
        return mQsExpanded;
    }

    @Override
    public View getHeader() {
        return mHeader;
    }

    @Override
    public void setHasNotifications(boolean hasNotifications) {
    }

    @Override
    public void setPanelView(HeightListener panelView) {
        mPanelView = panelView;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setEditLocation(getView());
        if (newConfig.getLayoutDirection() != mLayoutDirection) {
            mLayoutDirection = newConfig.getLayoutDirection();
            if (mQSAnimator != null) {
                mQSAnimator.onRtlChanged();
            }
        }
        updateQsState();
    }

    @Override
    public void setFancyClipping(int top, int bottom, int cornerRadius, boolean visible) {
        if (getView() instanceof QSContainerImpl) {
            ((QSContainerImpl) getView()).setFancyClipping(top, bottom, cornerRadius, visible);
        }
    }

    @Override
    public boolean isFullyCollapsed() {
        return mLastQSExpansion == 0.0f || mLastQSExpansion == -1;
    }

    @Override
    public void setCollapsedMediaVisibilityChangedListener(Consumer<Boolean> listener) {
        mQuickQSPanelController.setMediaVisibilityChangedListener(listener);
    }

    private void setEditLocation(View view) {
        View edit = view.findViewById(android.R.id.edit);
        int[] loc = edit.getLocationOnScreen();
        int x = loc[0] + edit.getWidth() / 2;
        int y = loc[1] + edit.getHeight() / 2;
        mQSCustomizerController.setEditLocation(x, y);
    }

    @Override
    public void setContainerController(QSContainerController controller) {
        mQSCustomizerController.setContainerController(controller);
        mQSDetail.setContainerController(controller);
    }

    @Override
    public boolean isCustomizing() {
        return mQSCustomizerController.isCustomizing();
    }

    public void setHost(QSTileHost qsh) {
        mQSDetail.setHost(qsh);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getContext().getDisplayId()) {
            return;
        }
        int state2BeforeAdjustment = state2;
        state2 = mRemoteInputQuickSettingsDisabler.adjustDisableFlags(state2);

        mQsFragmentDisableFlagsLogger.logDisableFlagChange(
                /* new= */ new DisableState(state1, state2BeforeAdjustment),
                /* newAfterLocalModification= */ new DisableState(state1, state2)
        );

        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mContainer.disable(state1, state2, animate);
        mHeader.disable(state1, state2, animate);
        mFooter.disable(state1, state2, animate);
        updateQsState();
    }

    private void updateQsState() {
        final boolean expanded = mQsExpanded || mInSplitShade;
        final boolean expandVisually = expanded || mStackScrollerOverscrolling
                || mHeaderAnimating;
        mQSPanelController.setExpanded(expanded);
        mQSDetail.setExpanded(expanded);
        boolean keyguardShowing = isKeyguardState();
        mHeader.setVisibility((expanded || !keyguardShowing || mHeaderAnimating
                || mShowCollapsedOnKeyguard)
                ? View.VISIBLE
                : View.INVISIBLE);
        mHeader.setExpanded((keyguardShowing && !mHeaderAnimating && !mShowCollapsedOnKeyguard)
                || (expanded && !mStackScrollerOverscrolling), mQuickQSPanelController);
        mFooter.setVisibility(!mQsDisabled && (expanded || !keyguardShowing || mHeaderAnimating
                || mShowCollapsedOnKeyguard)
                ? View.VISIBLE
                : View.INVISIBLE);
        mFooter.setExpanded((keyguardShowing && !mHeaderAnimating && !mShowCollapsedOnKeyguard)
                || (expanded && !mStackScrollerOverscrolling));
        mQSPanelController.setVisibility(
                !mQsDisabled && expandVisually ? View.VISIBLE : View.INVISIBLE);
    }

    private boolean isKeyguardState() {
        // We want the freshest state here since otherwise we'll have some weirdness if earlier
        // listeners trigger updates
        return mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
    }

    private void updateShowCollapsedOnKeyguard() {
        boolean showCollapsed = mBypassController.getBypassEnabled()
                || (mTransitioningToFullShade && !mInSplitShade);
        if (showCollapsed != mShowCollapsedOnKeyguard) {
            mShowCollapsedOnKeyguard = showCollapsed;
            updateQsState();
            if (mQSAnimator != null) {
                mQSAnimator.setShowCollapsedOnKeyguard(showCollapsed);
            }
            if (!showCollapsed && isKeyguardState()) {
                setQsExpansion(mLastQSExpansion, mLastPanelFraction, 0,
                        mSquishinessFraction);
            }
        }
    }

    public QSPanelController getQSPanelController() {
        return mQSPanelController;
    }

    public void setBrightnessMirrorController(
            BrightnessMirrorController brightnessMirrorController) {
        mQSPanelController.setBrightnessMirror(brightnessMirrorController);
        mQuickQSPanelController.setBrightnessMirror(brightnessMirrorController);
    }

    @Override
    public boolean isShowingDetail() {
        return mQSCustomizerController.isCustomizing() || mQSDetail.isShowingDetail();
    }

    @Override
    public void setHeaderClickable(boolean clickable) {
        if (DEBUG) Log.d(TAG, "setHeaderClickable " + clickable);
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (DEBUG) Log.d(TAG, "setExpanded " + expanded);
        mQsExpanded = expanded;
        mQSPanelController.setListening(mListening, mQsExpanded);
        updateQsState();
    }

    private void setKeyguardShowing(boolean keyguardShowing) {
        if (DEBUG) Log.d(TAG, "setKeyguardShowing " + keyguardShowing);
        mLastQSExpansion = -1;

        if (mQSAnimator != null) {
            mQSAnimator.setOnKeyguard(keyguardShowing);
        }

        mFooter.setKeyguardShowing(keyguardShowing);
        updateQsState();
    }

    @Override
    public void setOverscrolling(boolean stackScrollerOverscrolling) {
        if (DEBUG) Log.d(TAG, "setOverscrolling " + stackScrollerOverscrolling);
        mStackScrollerOverscrolling = stackScrollerOverscrolling;
        updateQsState();
    }

    @Override
    public void setListening(boolean listening) {
        if (DEBUG) Log.d(TAG, "setListening " + listening);
        mListening = listening;
        mQSContainerImplController.setListening(listening);
        mFooter.setListening(listening);
        mQSPanelController.setListening(mListening, mQsExpanded);
    }

    @Override
    public void setHeaderListening(boolean listening) {
        mQSContainerImplController.setListening(listening);
        mFooter.setListening(listening);
    }

    @Override
    public void setInSplitShade(boolean inSplitShade) {
        mInSplitShade = inSplitShade;
        mQSAnimator.setTranslateWhileExpanding(inSplitShade);
        updateShowCollapsedOnKeyguard();
        updateQsState();
    }

    @Override
    public void setTransitionToFullShadeAmount(float pxAmount, float progress) {
        boolean isTransitioningToFullShade = pxAmount > 0;
        if (isTransitioningToFullShade != mTransitioningToFullShade) {
            mTransitioningToFullShade = isTransitioningToFullShade;
            updateShowCollapsedOnKeyguard();
        }
        mFullShadeProgress = progress;
        setQsExpansion(mLastQSExpansion, mLastPanelFraction, mLastHeaderTranslation,
                isTransitioningToFullShade ? progress : mSquishinessFraction);
    }

    @Override
    public void setQsExpansion(float expansion, float panelExpansionFraction,
            float proposedTranslation, float squishinessFraction) {
        float headerTranslation = mTransitioningToFullShade ? 0 : proposedTranslation;
        float progress = mTransitioningToFullShade || mState == StatusBarState.KEYGUARD
                ? mFullShadeProgress : panelExpansionFraction;
        setAlphaAnimationProgress(mInSplitShade ? progress : 1);
        mContainer.setExpansion(expansion);
        final float translationScaleY = (mInSplitShade
                ? 1 : QSAnimator.SHORT_PARALLAX_AMOUNT) * (expansion - 1);
        boolean onKeyguardAndExpanded = isKeyguardState() && !mShowCollapsedOnKeyguard;
        if (!mHeaderAnimating && !headerWillBeAnimating()) {
            getView().setTranslationY(
                    onKeyguardAndExpanded
                            ? translationScaleY * mHeader.getHeight()
                            : headerTranslation);
        }
        int currentHeight = getView().getHeight();
        if (expansion == mLastQSExpansion
                && mLastKeyguardAndExpanded == onKeyguardAndExpanded
                && mLastViewHeight == currentHeight
                && mLastHeaderTranslation == headerTranslation
                && mSquishinessFraction == squishinessFraction) {
            return;
        }
        mLastHeaderTranslation = headerTranslation;
        mLastPanelFraction = panelExpansionFraction;
        mSquishinessFraction = squishinessFraction;
        mLastQSExpansion = expansion;
        mLastKeyguardAndExpanded = onKeyguardAndExpanded;
        mLastViewHeight = currentHeight;

        boolean fullyExpanded = expansion == 1;
        boolean fullyCollapsed = expansion == 0.0f;
        int heightDiff = mQSPanelScrollView.getBottom() - mHeader.getBottom()
                + mHeader.getPaddingBottom();
        float panelTranslationY = translationScaleY * heightDiff;

        // Let the views animate their contents correctly by giving them the necessary context.
        mHeader.setExpansion(onKeyguardAndExpanded, expansion, panelTranslationY);
        if (expansion < 1 && expansion > 0.99) {
            if (mQuickQSPanelController.switchTileLayout(false)) {
                mHeader.updateResources();
            }
        }
        mFooter.setExpansion(onKeyguardAndExpanded ? 1 : expansion);
        mQSPanelController.setRevealExpansion(expansion);
        mQSPanelController.getTileLayout().setExpansion(expansion, proposedTranslation);
        mQuickQSPanelController.getTileLayout().setExpansion(expansion, proposedTranslation);
        mQSPanelScrollView.setTranslationY(translationScaleY * heightDiff);
        if (fullyCollapsed) {
            mQSPanelScrollView.setScrollY(0);
        }
        mQSDetail.setFullyExpanded(fullyExpanded);

        if (!fullyExpanded) {
            // Set bounds on the QS panel so it doesn't run over the header when animating.
            mQsBounds.top = (int) -mQSPanelScrollView.getTranslationY();
            mQsBounds.right = mQSPanelScrollView.getWidth();
            mQsBounds.bottom = mQSPanelScrollView.getHeight();
        }
        updateQsBounds();

        if (mQSSquishinessController != null) {
            mQSSquishinessController.setSquishiness(mSquishinessFraction);
        }
        if (mQSAnimator != null) {
            mQSAnimator.setPosition(expansion);
        }
        updateMediaPositions();
    }

    private void setAlphaAnimationProgress(float progress) {
        final View view = getView();
        if (progress == 0 && view.getVisibility() != View.INVISIBLE) {
            view.setVisibility(View.INVISIBLE);
        } else if (progress > 0 && view.getVisibility() != View.VISIBLE) {
            view.setVisibility((View.VISIBLE));
        }
        float alpha = ShadeInterpolation.getContentAlpha(progress);
        view.setAlpha(alpha);
    }

    private void updateQsBounds() {
        if (mLastQSExpansion == 1.0f) {
            // Fully expanded, let's set the layout bounds as clip bounds. This is necessary because
            // it's a scrollview and otherwise wouldn't be clipped. However, we set the horizontal
            // bounds so the pages go to the ends of QSContainerImpl
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mQSPanelScrollView.getLayoutParams();
            mQsBounds.set(-lp.leftMargin, 0, mQSPanelScrollView.getWidth() + lp.rightMargin,
                    mQSPanelScrollView.getHeight());
        }
        mQSPanelScrollView.setClipBounds(mQsBounds);
    }

    private void updateMediaPositions() {
        if (Utils.useQsMediaPlayer(getContext())) {
            mContainer.getLocationOnScreen(mTmpLocation);
            float absoluteBottomPosition = mTmpLocation[1] + mContainer.getHeight();
            // The Media can be scrolled off screen by default, let's offset it
            float expandedMediaPosition = absoluteBottomPosition - mQSPanelScrollView.getScrollY()
                    + mQSPanelScrollView.getScrollRange();
            pinToBottom(expandedMediaPosition, mQsMediaHost, true /* expanded */);
            // The expanded media host should never move above the laid out position
            pinToBottom(absoluteBottomPosition, mQqsMediaHost, false /* expanded */);
        }
    }

    private void pinToBottom(float absoluteBottomPosition, MediaHost mediaHost, boolean expanded) {
        View hostView = mediaHost.getHostView();
        // On keyguard we cross-fade to expanded, so no need to pin it.
        // If the collapsed qs isn't visible, we also just keep it at the laid out position.
        if (mLastQSExpansion > 0 && !isKeyguardState() && mQqsMediaHost.getVisible()) {
            float targetPosition = absoluteBottomPosition - getTotalBottomMargin(hostView)
                    - hostView.getHeight();
            float currentPosition = mediaHost.getCurrentBounds().top
                    - hostView.getTranslationY();
            float translationY = targetPosition - currentPosition;
            if (expanded) {
                // Never go below the laid out position. This is necessary since the qs panel can
                // change in height and we don't want to ever go below it's position
                translationY = Math.min(translationY, 0);
            } else {
                translationY = Math.max(translationY, 0);
            }
            hostView.setTranslationY(translationY);
        } else {
            hostView.setTranslationY(0);
        }
    }

    private float getTotalBottomMargin(View startView) {
        int result = 0;
        View child = startView;
        View parent = (View) startView.getParent();
        while (!(parent instanceof QSContainerImpl) && parent != null) {
            result += parent.getHeight() - child.getBottom();
            child = parent;
            parent = (View) parent.getParent();
        }
        return result;
    }

    private boolean headerWillBeAnimating() {
        return mState == StatusBarState.KEYGUARD && mShowCollapsedOnKeyguard
                && !isKeyguardState();
    }

    @Override
    public void animateHeaderSlidingOut() {
        if (DEBUG) Log.d(TAG, "animateHeaderSlidingOut");
        if (getView().getY() == -mHeader.getHeight()) {
            return;
        }
        mHeaderAnimating = true;
        getView().animate().y(-mHeader.getHeight())
                .setStartDelay(0)
                .setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (getView() != null) {
                            // The view could be destroyed before the animation completes when
                            // switching users.
                            getView().animate().setListener(null);
                        }
                        mHeaderAnimating = false;
                        updateQsState();
                    }
                })
                .start();
    }

    @Override
    public void setExpandClickListener(OnClickListener onClickListener) {
        mFooter.setExpandClickListener(onClickListener);
    }

    @Override
    public void closeDetail() {
        mQSPanelController.closeDetail();
    }

    @Override
    public void closeCustomizer() {
        mQSCustomizerController.hide();
    }

    public void notifyCustomizeChanged() {
        // The customize state changed, so our height changed.
        mContainer.updateExpansion();
        boolean customizing = isCustomizing();
        mQSPanelScrollView.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        mFooter.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        mHeader.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        // Let the panel know the position changed and it needs to update where notifications
        // and whatnot are.
        mPanelView.onQsHeightChanged();
    }

    /**
     * The height this view wants to be. This is different from {@link View#getMeasuredHeight} such
     * that during closing the detail panel, this already returns the smaller height.
     */
    @Override
    public int getDesiredHeight() {
        if (mQSCustomizerController.isCustomizing()) {
            return getView().getHeight();
        }
        if (mQSDetail.isClosingDetail()) {
            LayoutParams layoutParams = (LayoutParams) mQSPanelScrollView.getLayoutParams();
            int panelHeight = layoutParams.topMargin + layoutParams.bottomMargin +
                    + mQSPanelScrollView.getMeasuredHeight();
            return panelHeight + getView().getPaddingBottom();
        } else {
            return getView().getMeasuredHeight();
        }
    }

    @Override
    public void setHeightOverride(int desiredHeight) {
        mContainer.setHeightOverride(desiredHeight);
    }

    @Override
    public int getQsMinExpansionHeight() {
        return mHeader.getHeight();
    }

    @Override
    public void hideImmediately() {
        getView().animate().cancel();
        getView().setY(-mHeader.getHeight());
    }

    private final ViewTreeObserver.OnPreDrawListener mStartHeaderSlidingIn
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            getView().getViewTreeObserver().removeOnPreDrawListener(this);
            getView().animate()
                    .translationY(0f)
                    .setStartDelay(mDelay)
                    .setDuration(StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .setListener(mAnimateHeaderSlidingInListener)
                    .start();
            return true;
        }
    };

    private final Animator.AnimatorListener mAnimateHeaderSlidingInListener
            = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mHeaderAnimating = false;
            updateQsState();
            // Unset the listener, otherwise this may persist for another view property animation
            getView().animate().setListener(null);
        }
    };

    @Override
    public void onStateChanged(int newState) {
        mState = newState;
        setKeyguardShowing(newState == StatusBarState.KEYGUARD);
        updateShowCollapsedOnKeyguard();
    }
}

/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static android.view.WindowInsets.Type.ime;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;

import static androidx.core.view.WindowInsetsCompat.Type;

import static com.android.internal.accessibility.AccessibilityShortcutController.ACCESSIBILITY_BUTTON_COMPONENT_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType.INVISIBLE_TOGGLE;
import static com.android.internal.accessibility.util.AccessibilityUtils.getAccessibilityServiceFragmentType;
import static com.android.internal.accessibility.util.AccessibilityUtils.setAccessibilityServiceState;
import static com.android.systemui.accessibility.floatingmenu.MenuMessageView.Index;
import static com.android.systemui.util.PluralMessageFormaterKt.icuMessageFormat;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.IntDef;
import android.annotation.StringDef;
import android.annotation.SuppressLint;
import android.content.ComponentCallbacks;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.util.settings.SecureSettings;
import com.android.wm.shell.bubbles.DismissView;
import com.android.wm.shell.common.magnetictarget.MagnetizedObject;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Optional;

/**
 * The basic interactions with the child views {@link MenuView}, {@link DismissView}, and
 * {@link MenuMessageView}. When dragging the menu view, the dismissed view would be shown at the
 * same time. If the menu view overlaps on the dismissed circle view and drops out, the menu
 * message view would be shown and allowed users to undo it.
 */
@SuppressLint("ViewConstructor")
class MenuViewLayer extends FrameLayout implements
        ViewTreeObserver.OnComputeInternalInsetsListener, View.OnClickListener, ComponentCallbacks {
    private static final int SHOW_MESSAGE_DELAY_MS = 3000;

    private final WindowManager mWindowManager;
    private final MenuView mMenuView;
    private final MenuListViewTouchHandler mMenuListViewTouchHandler;
    private final MenuMessageView mMessageView;
    private final DismissView mDismissView;
    private final MenuViewAppearance mMenuViewAppearance;
    private final MenuAnimationController mMenuAnimationController;
    private final AccessibilityManager mAccessibilityManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final IAccessibilityFloatingMenu mFloatingMenu;
    private final SecureSettings mSecureSettings;
    private final DismissAnimationController mDismissAnimationController;
    private final MenuViewModel mMenuViewModel;
    private final Observer<Boolean> mDockTooltipObserver =
            this::onDockTooltipVisibilityChanged;
    private final Observer<Boolean> mMigrationTooltipObserver =
            this::onMigrationTooltipVisibilityChanged;
    private final Rect mImeInsetsRect = new Rect();
    private boolean mIsMigrationTooltipShowing;
    private boolean mShouldShowDockTooltip;
    private Optional<MenuEduTooltipView> mEduTooltipView = Optional.empty();

    @IntDef({
            LayerIndex.MENU_VIEW,
            LayerIndex.DISMISS_VIEW,
            LayerIndex.MESSAGE_VIEW,
            LayerIndex.TOOLTIP_VIEW,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface LayerIndex {
        int MENU_VIEW = 0;
        int DISMISS_VIEW = 1;
        int MESSAGE_VIEW = 2;
        int TOOLTIP_VIEW = 3;
    }

    @StringDef({
            TooltipType.MIGRATION,
            TooltipType.DOCK,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TooltipType {
        String MIGRATION = "migration";
        String DOCK = "dock";
    }

    @VisibleForTesting
    final Runnable mDismissMenuAction = new Runnable() {
        @Override
        public void run() {
            mSecureSettings.putStringForUser(
                    Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, /* value= */ "",
                    UserHandle.USER_CURRENT);

            final List<ComponentName> hardwareKeyShortcutComponents =
                    mAccessibilityManager.getAccessibilityShortcutTargets(
                                    ACCESSIBILITY_SHORTCUT_KEY)
                            .stream()
                            .map(ComponentName::unflattenFromString)
                            .toList();

            // Should disable the corresponding service when the fragment type is
            // INVISIBLE_TOGGLE, which will enable service when the shortcut is on.
            final List<AccessibilityServiceInfo> serviceInfoList =
                    mAccessibilityManager.getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            serviceInfoList.forEach(info -> {
                if (getAccessibilityServiceFragmentType(info) != INVISIBLE_TOGGLE) {
                    return;
                }

                final ComponentName serviceComponentName = info.getComponentName();
                if (hardwareKeyShortcutComponents.contains(serviceComponentName)) {
                    return;
                }

                setAccessibilityServiceState(getContext(), serviceComponentName, /* enabled= */
                        false);
            });

            mFloatingMenu.hide();
        }
    };

    MenuViewLayer(@NonNull Context context, WindowManager windowManager,
            AccessibilityManager accessibilityManager, IAccessibilityFloatingMenu floatingMenu,
            SecureSettings secureSettings) {
        super(context);

        // Simplifies the translation positioning and animations
        setLayoutDirection(LAYOUT_DIRECTION_LTR);

        mWindowManager = windowManager;
        mAccessibilityManager = accessibilityManager;
        mFloatingMenu = floatingMenu;
        mSecureSettings = secureSettings;

        mMenuViewModel = new MenuViewModel(context, accessibilityManager, secureSettings);
        mMenuViewAppearance = new MenuViewAppearance(context, windowManager);
        mMenuView = new MenuView(context, mMenuViewModel, mMenuViewAppearance);
        mMenuAnimationController = mMenuView.getMenuAnimationController();
        mMenuAnimationController.setDismissCallback(this::hideMenuAndShowMessage);
        mMenuAnimationController.setSpringAnimationsEndAction(this::onSpringAnimationsEndAction);
        mDismissView = new DismissView(context);
        mDismissAnimationController = new DismissAnimationController(mDismissView, mMenuView);
        mDismissAnimationController.setMagnetListener(new MagnetizedObject.MagnetListener() {
            @Override
            public void onStuckToTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                mDismissAnimationController.animateDismissMenu(/* scaleUp= */ true);
            }

            @Override
            public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                    float velocityX, float velocityY, boolean wasFlungOut) {
                mDismissAnimationController.animateDismissMenu(/* scaleUp= */ false);
            }

            @Override
            public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                hideMenuAndShowMessage();
                mDismissView.hide();
                mDismissAnimationController.animateDismissMenu(/* scaleUp= */ false);
            }
        });

        mMenuListViewTouchHandler = new MenuListViewTouchHandler(mMenuAnimationController,
                mDismissAnimationController);
        mMenuView.addOnItemTouchListenerToList(mMenuListViewTouchHandler);

        mMessageView = new MenuMessageView(context);

        mMenuView.setOnTargetFeaturesChangeListener(newTargetFeatures -> {
            if (newTargetFeatures.size() < 1) {
                return;
            }

            // During the undo action period, the pending action will be canceled and undo back
            // to the previous state if users did any action related to the accessibility features.
            if (mMessageView.getVisibility() == VISIBLE) {
                undo();
            }

            final TextView messageText = (TextView) mMessageView.getChildAt(Index.TEXT_VIEW);
            messageText.setText(getMessageText(newTargetFeatures));
        });

        addView(mMenuView, LayerIndex.MENU_VIEW);
        addView(mDismissView, LayerIndex.DISMISS_VIEW);
        addView(mMessageView, LayerIndex.MESSAGE_VIEW);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        mDismissView.updateResources();
        mDismissAnimationController.updateResources();
    }

    @Override
    public void onLowMemory() {
        // Do nothing.
    }

    private String getMessageText(List<AccessibilityTarget> newTargetFeatures) {
        Preconditions.checkArgument(newTargetFeatures.size() > 0,
                "The list should at least have one feature.");

        final int featuresSize = newTargetFeatures.size();
        final Resources resources = getResources();
        if (featuresSize == 1) {
            return resources.getString(
                    R.string.accessibility_floating_button_undo_message_label_text,
                    newTargetFeatures.get(0).getLabel());
        }

        return icuMessageFormat(resources,
                R.string.accessibility_floating_button_undo_message_number_text, featuresSize);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mMenuView.maybeMoveOutEdgeAndShow((int) event.getX(), (int) event.getY())) {
            return true;
        }

        return super.onInterceptTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mMenuView.show();
        setOnClickListener(this);
        setOnApplyWindowInsetsListener((view, insets) -> onWindowInsetsApplied(insets));
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
        mMenuViewModel.getDockTooltipVisibilityData().observeForever(mDockTooltipObserver);
        mMenuViewModel.getMigrationTooltipVisibilityData().observeForever(
                mMigrationTooltipObserver);
        mMessageView.setUndoListener(view -> undo());
        getContext().registerComponentCallbacks(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mMenuView.hide();
        setOnClickListener(null);
        setOnApplyWindowInsetsListener(null);
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        mMenuViewModel.getDockTooltipVisibilityData().removeObserver(mDockTooltipObserver);
        mMenuViewModel.getMigrationTooltipVisibilityData().removeObserver(
                mMigrationTooltipObserver);
        mHandler.removeCallbacksAndMessages(/* token= */ null);
        getContext().unregisterComponentCallbacks(this);
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);

        if (mEduTooltipView.isPresent()) {
            final int x = (int) getX();
            final int y = (int) getY();
            inoutInfo.touchableRegion.union(new Rect(x, y, x + getWidth(), y + getHeight()));
        }
    }

    @Override
    public void onClick(View v) {
        mEduTooltipView.ifPresent(this::removeTooltip);
    }

    private WindowInsets onWindowInsetsApplied(WindowInsets insets) {
        final WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        final WindowInsets windowInsets = windowMetrics.getWindowInsets();
        final Rect imeInsetsRect = windowInsets.getInsets(ime()).toRect();
        if (!imeInsetsRect.equals(mImeInsetsRect)) {
            final Rect windowBounds = new Rect(windowMetrics.getBounds());
            final Rect systemBarsAndDisplayCutoutInsetsRect =
                    windowInsets.getInsetsIgnoringVisibility(
                            Type.systemBars() | Type.displayCutout()).toRect();
            final float imeTop =
                    windowBounds.height() - systemBarsAndDisplayCutoutInsetsRect.top
                            - imeInsetsRect.bottom;

            mMenuViewAppearance.onImeVisibilityChanged(windowInsets.isVisible(ime()), imeTop);

            mMenuView.onEdgeChanged();
            mMenuView.onPositionChanged();

            mImeInsetsRect.set(imeInsetsRect);
        }

        return insets;
    }

    private void onMigrationTooltipVisibilityChanged(boolean visible) {
        mIsMigrationTooltipShowing = visible;

        if (mIsMigrationTooltipShowing) {
            mEduTooltipView = Optional.of(new MenuEduTooltipView(mContext, mMenuViewAppearance));
            mEduTooltipView.ifPresent(
                    view -> addTooltipView(view, getMigrationMessage(), TooltipType.MIGRATION));
        }
    }

    private void onDockTooltipVisibilityChanged(boolean hasSeenTooltip) {
        mShouldShowDockTooltip = !hasSeenTooltip;
    }

    private void onSpringAnimationsEndAction() {
        if (mShouldShowDockTooltip) {
            mEduTooltipView = Optional.of(new MenuEduTooltipView(mContext, mMenuViewAppearance));
            mEduTooltipView.ifPresent(view -> addTooltipView(view,
                    getContext().getText(R.string.accessibility_floating_button_docking_tooltip),
                    TooltipType.DOCK));

            mMenuAnimationController.startTuckedAnimationPreview();
        }
    }

    private CharSequence getMigrationMessage() {
        final Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME,
                ACCESSIBILITY_BUTTON_COMPONENT_NAME.flattenToShortString());

        final AnnotationLinkSpan.LinkInfo linkInfo = new AnnotationLinkSpan.LinkInfo(
                AnnotationLinkSpan.LinkInfo.DEFAULT_ANNOTATION,
                v -> {
                    getContext().startActivity(intent);
                    mEduTooltipView.ifPresent(this::removeTooltip);
                });

        final int textResId = R.string.accessibility_floating_button_migration_tooltip;

        return AnnotationLinkSpan.linkify(getContext().getText(textResId), linkInfo);
    }

    private void addTooltipView(MenuEduTooltipView tooltipView, CharSequence message,
            CharSequence tag) {
        addView(tooltipView, LayerIndex.TOOLTIP_VIEW);

        tooltipView.show(message);
        tooltipView.setTag(tag);

        mMenuListViewTouchHandler.setOnActionDownEndListener(
                () -> mEduTooltipView.ifPresent(this::removeTooltip));
    }

    private void removeTooltip(View tooltipView) {
        if (tooltipView.getTag().equals(TooltipType.MIGRATION)) {
            mMenuViewModel.updateMigrationTooltipVisibility(/* visible= */ false);
            mIsMigrationTooltipShowing = false;
        }

        if (tooltipView.getTag().equals(TooltipType.DOCK)) {
            mMenuViewModel.updateDockTooltipVisibility(/* hasSeen= */ true);
            mMenuView.clearAnimation();
            mShouldShowDockTooltip = false;
        }

        removeView(tooltipView);

        mMenuListViewTouchHandler.setOnActionDownEndListener(null);
        mEduTooltipView = Optional.empty();
    }

    private void hideMenuAndShowMessage() {
        final int delayTime = mAccessibilityManager.getRecommendedTimeoutMillis(
                SHOW_MESSAGE_DELAY_MS,
                AccessibilityManager.FLAG_CONTENT_TEXT
                        | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        mHandler.postDelayed(mDismissMenuAction, delayTime);
        mMessageView.setVisibility(VISIBLE);
        mMenuAnimationController.startShrinkAnimation(() -> mMenuView.setVisibility(GONE));
    }

    private void undo() {
        mHandler.removeCallbacksAndMessages(/* token= */ null);
        mMessageView.setVisibility(GONE);
        mMenuView.onEdgeChanged();
        mMenuView.onPositionChanged();
        mMenuView.setVisibility(VISIBLE);
        mMenuAnimationController.startGrowAnimation();
    }
}

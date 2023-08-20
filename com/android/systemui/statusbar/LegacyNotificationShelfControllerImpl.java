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

package com.android.systemui.statusbar;

import android.view.View;

import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationViewController;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowScope;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;

import javax.inject.Inject;

/**
 * Controller class for {@link NotificationShelf}.
 */
@NotificationRowScope
public class LegacyNotificationShelfControllerImpl implements NotificationShelfController {
    private final NotificationShelf mView;
    private final ActivatableNotificationViewController mActivatableNotificationViewController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final View.OnAttachStateChangeListener mOnAttachStateChangeListener;
    private AmbientState mAmbientState;

    @Inject
    public LegacyNotificationShelfControllerImpl(
            NotificationShelf notificationShelf,
            ActivatableNotificationViewController activatableNotificationViewController,
            KeyguardBypassController keyguardBypassController,
            SysuiStatusBarStateController statusBarStateController,
            FeatureFlags featureFlags) {
        mView = notificationShelf;
        mActivatableNotificationViewController = activatableNotificationViewController;
        mKeyguardBypassController = keyguardBypassController;
        mStatusBarStateController = statusBarStateController;
        mView.setSensitiveRevealAnimEndabled(featureFlags.isEnabled(Flags.SENSITIVE_REVEAL_ANIM));
        mOnAttachStateChangeListener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                mStatusBarStateController.addCallback(
                        mView, SysuiStatusBarStateController.RANK_SHELF);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                mStatusBarStateController.removeCallback(mView);
            }
        };
    }

    public void init() {
        mActivatableNotificationViewController.init();
        mView.setController(this);
        mView.addOnAttachStateChangeListener(mOnAttachStateChangeListener);
        if (mView.isAttachedToWindow()) {
            mOnAttachStateChangeListener.onViewAttachedToWindow(mView);
        }
    }

    @Override
    public NotificationShelf getView() {
        return mView;
    }

    @Override
    public boolean canModifyColorOfNotifications() {
        return mAmbientState.isShadeExpanded()
                && !(mAmbientState.isOnKeyguard() && mKeyguardBypassController.getBypassEnabled());
    }

    @Override
    public NotificationIconContainer getShelfIcons() {
        return mView.getShelfIcons();
    }

    @Override
    public void bind(AmbientState ambientState,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController) {
        mView.bind(ambientState, notificationStackScrollLayoutController);
        mAmbientState = ambientState;
    }

    @Override
    public int getIntrinsicHeight() {
        return mView.getIntrinsicHeight();
    }

    @Override
    public void setOnClickListener(View.OnClickListener onClickListener) {
        mView.setOnClickListener(onClickListener);
    }

}

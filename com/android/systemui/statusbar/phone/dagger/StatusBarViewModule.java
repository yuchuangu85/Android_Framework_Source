/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone.dagger;

import android.view.LayoutInflater;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.NotificationPanelView;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.NotificationsQuickSettingsContainer;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.LegacyNotificationShelfControllerImpl;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.row.dagger.NotificationShelfComponent;
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ActivatableNotificationViewModelModule;
import com.android.systemui.statusbar.notification.shelf.ui.viewbinder.NotificationShelfViewBinderWrapperControllerImpl;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModelModule;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.StatusBarBoundsProvider;
import com.android.systemui.statusbar.phone.StatusBarHideIconsForBouncerManager;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher;
import com.android.systemui.statusbar.phone.SystemBarAttributesListener;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragmentLogger;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.pipeline.shared.ui.binder.CollapsedStatusBarViewBinder;
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.CollapsedStatusBarViewModel;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.settings.SecureSettings;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

import java.util.concurrent.Executor;

import javax.inject.Named;
import javax.inject.Provider;

@Module(subcomponents = StatusBarFragmentComponent.class,
        includes = {
                ActivatableNotificationViewModelModule.class,
                NotificationListViewModelModule.class,
        })
public abstract class StatusBarViewModule {

    public static final String STATUS_BAR_FRAGMENT = "status_bar_fragment";

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static NotificationShelf providesNotificationShelf(LayoutInflater layoutInflater,
            NotificationStackScrollLayout notificationStackScrollLayout) {
        NotificationShelf view = (NotificationShelf) layoutInflater.inflate(
                R.layout.status_bar_notification_shelf, notificationStackScrollLayout, false);

        if (view == null) {
            throw new IllegalStateException(
                    "R.layout.status_bar_notification_shelf could not be properly inflated");
        }
        return view;
    }

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static NotificationShelfController providesStatusBarWindowView(
            FeatureFlags featureFlags,
            Provider<NotificationShelfViewBinderWrapperControllerImpl> newImpl,
            NotificationShelfComponent.Builder notificationShelfComponentBuilder,
            NotificationShelf notificationShelf) {
        if (featureFlags.isEnabled(Flags.NOTIFICATION_SHELF_REFACTOR)) {
            return newImpl.get();
        } else {
            NotificationShelfComponent component = notificationShelfComponentBuilder
                    .notificationShelf(notificationShelf)
                    .build();
            LegacyNotificationShelfControllerImpl notificationShelfController =
                    component.getNotificationShelfController();
            notificationShelfController.init();

            return notificationShelfController;
        }
    }

    /** */
    @Binds
    @CentralSurfacesComponent.CentralSurfacesScope
    abstract ShadeViewController bindsShadeViewController(
            NotificationPanelViewController notificationPanelViewController);

    /** */
    @Provides
    @CentralSurfacesComponent.CentralSurfacesScope
    public static NotificationsQuickSettingsContainer getNotificationsQuickSettingsContainer(
            NotificationShadeWindowView notificationShadeWindowView) {
        return notificationShadeWindowView.findViewById(R.id.notification_container_parent);
    }

    @Binds
    @IntoSet
    abstract StatusBarBoundsProvider.BoundsChangeListener sysBarAttrsListenerAsBoundsListener(
            SystemBarAttributesListener systemBarAttributesListener);

    /**
     * Creates a new {@link CollapsedStatusBarFragment}.
     *
     * **IMPORTANT**: This method intentionally does not have
     * {@link CentralSurfacesComponent.CentralSurfacesScope}, which means a new fragment *will* be
     * created each time this method is called. This is intentional because we need fragments to
     * re-created in certain lifecycle scenarios.
     *
     * This provider is {@link Named} such that it does not conflict with the provider inside of
     * {@link StatusBarFragmentComponent}.
     */
    @Provides
    @Named(STATUS_BAR_FRAGMENT)
    public static CollapsedStatusBarFragment createCollapsedStatusBarFragment(
            StatusBarFragmentComponent.Factory statusBarFragmentComponentFactory,
            OngoingCallController ongoingCallController,
            SystemStatusAnimationScheduler animationScheduler,
            StatusBarLocationPublisher locationPublisher,
            NotificationIconAreaController notificationIconAreaController,
            ShadeExpansionStateManager shadeExpansionStateManager,
            FeatureFlags featureFlags,
            StatusBarIconController statusBarIconController,
            StatusBarIconController.DarkIconManager.Factory darkIconManagerFactory,
            CollapsedStatusBarViewModel collapsedStatusBarViewModel,
            CollapsedStatusBarViewBinder collapsedStatusBarViewBinder,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            KeyguardStateController keyguardStateController,
            ShadeViewController shadeViewController,
            StatusBarStateController statusBarStateController,
            CommandQueue commandQueue,
            CarrierConfigTracker carrierConfigTracker,
            CollapsedStatusBarFragmentLogger collapsedStatusBarFragmentLogger,
            OperatorNameViewController.Factory operatorNameViewControllerFactory,
            SecureSettings secureSettings,
            @Main Executor mainExecutor,
            DumpManager dumpManager,
            StatusBarWindowStateController statusBarWindowStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor
    ) {
        return new CollapsedStatusBarFragment(statusBarFragmentComponentFactory,
                ongoingCallController,
                animationScheduler,
                locationPublisher,
                notificationIconAreaController,
                shadeExpansionStateManager,
                featureFlags,
                statusBarIconController,
                darkIconManagerFactory,
                collapsedStatusBarViewModel,
                collapsedStatusBarViewBinder,
                statusBarHideIconsForBouncerManager,
                keyguardStateController,
                shadeViewController,
                statusBarStateController,
                commandQueue,
                carrierConfigTracker,
                collapsedStatusBarFragmentLogger,
                operatorNameViewControllerFactory,
                secureSettings,
                mainExecutor,
                dumpManager,
                statusBarWindowStateController,
                keyguardUpdateMonitor);
    }

    /**
     * Constructs a new, unattached {@link KeyguardBottomAreaView}.
     *
     * Note that this is explicitly _not_ a singleton, as we want to be able to reinflate it
     */
    @Provides
    public static KeyguardBottomAreaView providesKeyguardBottomAreaView(
            NotificationPanelView npv, LayoutInflater layoutInflater) {
        return (KeyguardBottomAreaView) layoutInflater.inflate(R
                .layout.keyguard_bottom_area, npv, false);
    }

}

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

package com.android.systemui.statusbar.phone.fragment.dagger;

import android.view.View;

import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.dagger.qualifiers.RootView;
import com.android.systemui.statusbar.HeadsUpStatusBarView;
import com.android.systemui.statusbar.phone.PhoneStatusBarTransitions;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController;
import com.android.systemui.statusbar.phone.StatusBarBoundsProvider;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.window.StatusBarWindowController;

import java.util.Optional;
import java.util.Set;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;

/** Dagger module for {@link StatusBarFragmentComponent}. */
@Module
public interface StatusBarFragmentModule {

    String LIGHTS_OUT_NOTIF_VIEW = "lights_out_notif_view";
    String OPERATOR_NAME_VIEW = "operator_name_view";
    String OPERATOR_NAME_FRAME_VIEW = "operator_name_frame_view";
    String START_SIDE_CONTENT = "start_side_content";
    String END_SIDE_CONTENT = "end_side_content";

    /** */
    @Provides
    @RootView
    @StatusBarFragmentScope
    static PhoneStatusBarView providePhoneStatusBarView(
            CollapsedStatusBarFragment collapsedStatusBarFragment) {
        return (PhoneStatusBarView) collapsedStatusBarFragment.getView();
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    static BatteryMeterView provideBatteryMeterView(@RootView PhoneStatusBarView view) {
        return view.findViewById(R.id.battery);
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    @Named(START_SIDE_CONTENT)
    static View startSideContent(@RootView PhoneStatusBarView view) {
        return view.findViewById(R.id.status_bar_start_side_content);
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    @Named(END_SIDE_CONTENT)
    static View endSideContent(@RootView PhoneStatusBarView view) {
        return view.findViewById(R.id.status_bar_end_side_content);
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    @Named(LIGHTS_OUT_NOTIF_VIEW)
    static View provideLightsOutNotifView(@RootView PhoneStatusBarView view) {
        return view.findViewById(R.id.notification_lights_out);
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    @Named(OPERATOR_NAME_VIEW)
    static View provideOperatorNameView(@RootView PhoneStatusBarView view) {
        return view.findViewById(R.id.operator_name);
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    @Named(OPERATOR_NAME_FRAME_VIEW)
    static Optional<View> provideOperatorFrameNameView(@RootView PhoneStatusBarView view) {
        return Optional.ofNullable(view.findViewById(R.id.operator_name_frame));
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    static Clock provideClock(@RootView PhoneStatusBarView view) {
        return view.findViewById(R.id.clock);
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    static StatusBarUserSwitcherContainer provideStatusBarUserSwitcherContainer(
            @RootView PhoneStatusBarView view) {
        return view.findViewById(R.id.user_switcher_container);
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    static PhoneStatusBarViewController providePhoneStatusBarViewController(
            PhoneStatusBarViewController.Factory phoneStatusBarViewControllerFactory,
            @RootView PhoneStatusBarView phoneStatusBarView) {
        return phoneStatusBarViewControllerFactory.create(
                phoneStatusBarView);
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    static PhoneStatusBarTransitions providePhoneStatusBarTransitions(
            @RootView PhoneStatusBarView view,
            StatusBarWindowController statusBarWindowController
    ) {
        return new PhoneStatusBarTransitions(view, statusBarWindowController.getBackgroundView());
    }

    /** */
    @Provides
    @StatusBarFragmentScope
    static HeadsUpStatusBarView providesHeasdUpStatusBarView(@RootView PhoneStatusBarView view) {
        return view.findViewById(R.id.heads_up_status_bar_view);
    }

    /** */
    @Multibinds
    Set<StatusBarBoundsProvider.BoundsChangeListener> boundsChangeListeners();
}

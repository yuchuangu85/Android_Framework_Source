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

package com.android.keyguard.dagger;

import android.view.ViewGroup;

import com.android.keyguard.KeyguardSecurityContainerController;
import com.android.systemui.dagger.qualifiers.RootView;
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Dagger Subcomponent for the {@link PrimaryBouncerInteractor}.
 */
@Subcomponent(modules = {KeyguardBouncerModule.class})
@KeyguardBouncerScope
public interface KeyguardBouncerComponent {
    /** Simple factory for {@link KeyguardBouncerComponent}. */
    @Subcomponent.Factory
    interface Factory {
        KeyguardBouncerComponent create(@BindsInstance @RootView ViewGroup bouncerContainer);
    }

    /** Returns a {@link KeyguardSecurityContainerController}. */
    KeyguardSecurityContainerController getSecurityContainerController();
}

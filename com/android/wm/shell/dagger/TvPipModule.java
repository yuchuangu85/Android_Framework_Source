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

package com.android.wm.shell.dagger;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipAppOpsListener;
import com.android.wm.shell.pip.PipDisplayLayoutState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipParamsChangedForwarder;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipTransitionState;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PipSizeSpecHandler;
import com.android.wm.shell.pip.tv.TvPipBoundsAlgorithm;
import com.android.wm.shell.pip.tv.TvPipBoundsController;
import com.android.wm.shell.pip.tv.TvPipBoundsState;
import com.android.wm.shell.pip.tv.TvPipController;
import com.android.wm.shell.pip.tv.TvPipMenuController;
import com.android.wm.shell.pip.tv.TvPipNotificationController;
import com.android.wm.shell.pip.tv.TvPipTaskOrganizer;
import com.android.wm.shell.pip.tv.TvPipTransition;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import dagger.Module;
import dagger.Provides;

import java.util.Optional;

/**
 * Provides TV specific dependencies for Pip.
 */
@Module(includes = {WMShellBaseModule.class})
public abstract class TvPipModule {
    @WMSingleton
    @Provides
    static Optional<Pip> providePip(
            Context context,
            ShellInit shellInit,
            ShellController shellController,
            TvPipBoundsState tvPipBoundsState,
            PipDisplayLayoutState pipDisplayLayoutState,
            TvPipBoundsAlgorithm tvPipBoundsAlgorithm,
            TvPipBoundsController tvPipBoundsController,
            PipAppOpsListener pipAppOpsListener,
            PipTaskOrganizer pipTaskOrganizer,
            TvPipMenuController tvPipMenuController,
            PipMediaController pipMediaController,
            PipTransitionController pipTransitionController,
            TvPipNotificationController tvPipNotificationController,
            TaskStackListenerImpl taskStackListener,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            DisplayController displayController,
            WindowManagerShellWrapper windowManagerShellWrapper,
            @ShellMainThread Handler mainHandler, // needed for registerReceiverForAllUsers()
            @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.of(
                TvPipController.create(
                        context,
                        shellInit,
                        shellController,
                        tvPipBoundsState,
                        pipDisplayLayoutState,
                        tvPipBoundsAlgorithm,
                        tvPipBoundsController,
                        pipAppOpsListener,
                        pipTaskOrganizer,
                        pipTransitionController,
                        tvPipMenuController,
                        pipMediaController,
                        tvPipNotificationController,
                        taskStackListener,
                        pipParamsChangedForwarder,
                        displayController,
                        windowManagerShellWrapper,
                        mainHandler,
                        mainExecutor));
    }

    @WMSingleton
    @Provides
    static TvPipBoundsController provideTvPipBoundsController(
            Context context,
            @ShellMainThread Handler mainHandler,
            TvPipBoundsState tvPipBoundsState,
            TvPipBoundsAlgorithm tvPipBoundsAlgorithm) {
        return new TvPipBoundsController(
                context,
                SystemClock::uptimeMillis,
                mainHandler,
                tvPipBoundsState,
                tvPipBoundsAlgorithm);
    }

    @WMSingleton
    @Provides
    static PipSnapAlgorithm providePipSnapAlgorithm() {
        return new PipSnapAlgorithm();
    }

    @WMSingleton
    @Provides
    static TvPipBoundsAlgorithm provideTvPipBoundsAlgorithm(Context context,
            TvPipBoundsState tvPipBoundsState, PipSnapAlgorithm pipSnapAlgorithm,
            PipSizeSpecHandler pipSizeSpecHandler) {
        return new TvPipBoundsAlgorithm(context, tvPipBoundsState, pipSnapAlgorithm,
                pipSizeSpecHandler);
    }

    @WMSingleton
    @Provides
    static TvPipBoundsState provideTvPipBoundsState(Context context,
            PipSizeSpecHandler pipSizeSpecHandler, PipDisplayLayoutState pipDisplayLayoutState) {
        return new TvPipBoundsState(context, pipSizeSpecHandler, pipDisplayLayoutState);
    }

    @WMSingleton
    @Provides
    static PipSizeSpecHandler providePipSizeSpecHelper(Context context,
            PipDisplayLayoutState pipDisplayLayoutState) {
        return new PipSizeSpecHandler(context, pipDisplayLayoutState);
    }

    // Handler needed for loadDrawableAsync() in PipControlsViewController
    @WMSingleton
    @Provides
    static PipTransitionController provideTvPipTransition(
            Context context,
            ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            Transitions transitions,
            TvPipBoundsState tvPipBoundsState,
            PipDisplayLayoutState pipDisplayLayoutState,
            PipTransitionState pipTransitionState,
            TvPipMenuController pipMenuController,
            TvPipBoundsAlgorithm tvPipBoundsAlgorithm,
            PipAnimationController pipAnimationController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper) {
        return new TvPipTransition(context, shellInit, shellTaskOrganizer, transitions,
                tvPipBoundsState, pipDisplayLayoutState, pipTransitionState, pipMenuController,
                tvPipBoundsAlgorithm, pipAnimationController, pipSurfaceTransactionHelper,
                Optional.empty());
    }

    @WMSingleton
    @Provides
    static TvPipMenuController providesTvPipMenuController(
            Context context,
            TvPipBoundsState tvPipBoundsState,
            SystemWindows systemWindows,
            @ShellMainThread Handler mainHandler) {
        return new TvPipMenuController(context, tvPipBoundsState, systemWindows, mainHandler);
    }

    @WMSingleton
    @Provides
    static TvPipNotificationController provideTvPipNotificationController(Context context,
            PipMediaController pipMediaController,
            PipParamsChangedForwarder pipParamsChangedForwarder) {
        return new TvPipNotificationController(context, pipMediaController,
                pipParamsChangedForwarder);
    }

    @WMSingleton
    @Provides
    static PipAnimationController providePipAnimationController(PipSurfaceTransactionHelper
            pipSurfaceTransactionHelper) {
        return new PipAnimationController(pipSurfaceTransactionHelper);
    }

    @WMSingleton
    @Provides
    static PipTransitionState providePipTransitionState() {
        return new PipTransitionState();
    }

    @WMSingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            TvPipMenuController tvPipMenuController,
            SyncTransactionQueue syncTransactionQueue,
            TvPipBoundsState tvPipBoundsState,
            PipDisplayLayoutState pipDisplayLayoutState,
            PipTransitionState pipTransitionState,
            TvPipBoundsAlgorithm tvPipBoundsAlgorithm,
            PipAnimationController pipAnimationController,
            PipTransitionController pipTransitionController,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreenController> splitScreenControllerOptional,
            DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new TvPipTaskOrganizer(context,
                syncTransactionQueue, pipTransitionState, tvPipBoundsState, pipDisplayLayoutState,
                tvPipBoundsAlgorithm, tvPipMenuController, pipAnimationController,
                pipSurfaceTransactionHelper, pipTransitionController, pipParamsChangedForwarder,
                splitScreenControllerOptional, displayController, pipUiEventLogger,
                shellTaskOrganizer, mainExecutor);
    }

    @WMSingleton
    @Provides
    static PipParamsChangedForwarder providePipParamsChangedForwarder() {
        return new PipParamsChangedForwarder();
    }

    @WMSingleton
    @Provides
    static PipAppOpsListener providePipAppOpsListener(Context context,
            PipTaskOrganizer pipTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipAppOpsListener(context, pipTaskOrganizer::removePip, mainExecutor);
    }
}

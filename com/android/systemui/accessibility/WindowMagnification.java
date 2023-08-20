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

package com.android.systemui.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;

import static com.android.systemui.accessibility.AccessibilityLogger.MagnificationSettingsEvent;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_MAGNIFICATION_OVERLAP;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.IWindowMagnificationConnection;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Class to handle the interaction with
 * {@link com.android.server.accessibility.AccessibilityManagerService}. It invokes
 * {@link AccessibilityManager#setWindowMagnificationConnection(IWindowMagnificationConnection)}
 * when {@code IStatusBar#requestWindowMagnificationConnection(boolean)} is called.
 */
@SysUISingleton
public class WindowMagnification implements CoreStartable, CommandQueue.Callbacks {
    private static final String TAG = "WindowMagnification";

    private final ModeSwitchesController mModeSwitchesController;
    private final Context mContext;
    private final Handler mHandler;
    private final AccessibilityManager mAccessibilityManager;
    private final CommandQueue mCommandQueue;
    private final OverviewProxyService mOverviewProxyService;
    private final DisplayTracker mDisplayTracker;
    private final AccessibilityLogger mA11yLogger;

    private WindowMagnificationConnectionImpl mWindowMagnificationConnectionImpl;
    private SysUiState mSysUiState;

    private static class ControllerSupplier extends
            DisplayIdIndexSupplier<WindowMagnificationController> {

        private final Context mContext;
        private final Handler mHandler;
        private final WindowMagnifierCallback mWindowMagnifierCallback;
        private final SysUiState mSysUiState;
        private final SecureSettings mSecureSettings;

        ControllerSupplier(Context context, Handler handler,
                WindowMagnifierCallback windowMagnifierCallback,
                DisplayManager displayManager, SysUiState sysUiState,
                SecureSettings secureSettings) {
            super(displayManager);
            mContext = context;
            mHandler = handler;
            mWindowMagnifierCallback = windowMagnifierCallback;
            mSysUiState = sysUiState;
            mSecureSettings = secureSettings;
        }

        @Override
        protected WindowMagnificationController createInstance(Display display) {
            final Context windowContext = mContext.createWindowContext(display,
                    TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY, /* options */ null);
            windowContext.setTheme(com.android.systemui.R.style.Theme_SystemUI);
            return new WindowMagnificationController(
                    windowContext,
                    mHandler,
                    new WindowMagnificationAnimationController(windowContext),
                    new SfVsyncFrameCallbackProvider(),
                    null,
                    new SurfaceControl.Transaction(),
                    mWindowMagnifierCallback,
                    mSysUiState,
                    WindowManagerGlobal::getWindowSession,
                    mSecureSettings);
        }
    }

    @VisibleForTesting
    DisplayIdIndexSupplier<WindowMagnificationController> mMagnificationControllerSupplier;

    private static class SettingsSupplier extends
            DisplayIdIndexSupplier<MagnificationSettingsController> {

        private final Context mContext;
        private final MagnificationSettingsController.Callback mSettingsControllerCallback;
        private final SecureSettings mSecureSettings;

        SettingsSupplier(Context context,
                MagnificationSettingsController.Callback settingsControllerCallback,
                DisplayManager displayManager,
                SecureSettings secureSettings) {
            super(displayManager);
            mContext = context;
            mSettingsControllerCallback = settingsControllerCallback;
            mSecureSettings = secureSettings;
        }

        @Override
        protected MagnificationSettingsController createInstance(Display display) {
            final Context windowContext = mContext.createWindowContext(display,
                    TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY, /* options */ null);
            windowContext.setTheme(com.android.systemui.R.style.Theme_SystemUI);
            return new MagnificationSettingsController(
                    windowContext,
                    new SfVsyncFrameCallbackProvider(),
                    mSettingsControllerCallback,
                    mSecureSettings);
        }
    }

    @VisibleForTesting
    DisplayIdIndexSupplier<MagnificationSettingsController> mMagnificationSettingsSupplier;

    @Inject
    public WindowMagnification(Context context, @Main Handler mainHandler,
            CommandQueue commandQueue, ModeSwitchesController modeSwitchesController,
            SysUiState sysUiState, OverviewProxyService overviewProxyService,
            SecureSettings secureSettings, DisplayTracker displayTracker,
            DisplayManager displayManager, AccessibilityLogger a11yLogger) {
        mContext = context;
        mHandler = mainHandler;
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mCommandQueue = commandQueue;
        mModeSwitchesController = modeSwitchesController;
        mSysUiState = sysUiState;
        mOverviewProxyService = overviewProxyService;
        mDisplayTracker = displayTracker;
        mA11yLogger = a11yLogger;
        mMagnificationControllerSupplier = new ControllerSupplier(context,
                mHandler, mWindowMagnifierCallback,
                displayManager, sysUiState, secureSettings);
        mMagnificationSettingsSupplier = new SettingsSupplier(context,
                mMagnificationSettingsControllerCallback, displayManager, secureSettings);

        mModeSwitchesController.setClickListenerDelegate(
                displayId -> mHandler.post(() -> {
                    showMagnificationSettingsPanel(displayId);
                }));
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
        mOverviewProxyService.addCallback(new OverviewProxyService.OverviewProxyListener() {
            @Override
            public void onConnectionChanged(boolean isConnected) {
                if (isConnected) {
                    updateSysUiStateFlag();
                }
            }
        });
    }

    private void updateSysUiStateFlag() {
        //TODO(b/187510533): support multi-display once SysuiState supports it.
        final WindowMagnificationController controller =
                mMagnificationControllerSupplier.valueAt(mDisplayTracker.getDefaultDisplayId());
        if (controller != null) {
            controller.updateSysUIStateFlag();
        } else {
            // The instance is initialized when there is an IPC request. Considering
            // self-crash cases, we need to reset the flag in such situation.
            mSysUiState.setFlag(SYSUI_STATE_MAGNIFICATION_OVERLAP, false)
                    .commitUpdate(mDisplayTracker.getDefaultDisplayId());
        }
    }

    @MainThread
    void enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
            float magnificationFrameOffsetRatioX, float magnificationFrameOffsetRatioY,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.enableWindowMagnification(scale, centerX, centerY,
                    magnificationFrameOffsetRatioX, magnificationFrameOffsetRatioY, callback);
        }
    }

    @MainThread
    void setScale(int displayId, float scale) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.setScale(scale);
        }
    }

    @MainThread
    void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
        final WindowMagnificationController windowMagnificationcontroller =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationcontroller != null) {
            windowMagnificationcontroller.moveWindowMagnifier(offsetX, offsetY);
        }
    }

    @MainThread
    void moveWindowMagnifierToPositionInternal(int displayId, float positionX, float positionY,
            IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.moveWindowMagnifierToPosition(positionX, positionY,
                    callback);
        }
    }

    @MainThread
    void disableWindowMagnification(int displayId,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.deleteWindowMagnification(callback);
        }
    }

    @MainThread
    void showMagnificationSettingsPanel(int displayId) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            magnificationSettingsController.showMagnificationSettings();
        }
    }

    @MainThread
    void hideMagnificationSettingsPanel(int displayId) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            magnificationSettingsController.closeMagnificationSettings();
        }
    }

    boolean isMagnificationSettingsPanelShowing(int displayId) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            return magnificationSettingsController.isMagnificationSettingsShowing();
        }
        return false;
    }

    @MainThread
    void showMagnificationButton(int displayId, int magnificationMode) {
        // not to show mode switch button if settings panel is already showing to
        // prevent settings panel be covered by the button.
        if (isMagnificationSettingsPanelShowing(displayId)) {
            return;
        }
        mModeSwitchesController.showButton(displayId, magnificationMode);
    }

    @MainThread
    void removeMagnificationButton(int displayId) {
        mModeSwitchesController.removeButton(displayId);
    }

    @VisibleForTesting
    final WindowMagnifierCallback mWindowMagnifierCallback = new WindowMagnifierCallback() {
        @Override
        public void onWindowMagnifierBoundsChanged(int displayId, Rect frame) {
            if (mWindowMagnificationConnectionImpl != null) {
                mWindowMagnificationConnectionImpl.onWindowMagnifierBoundsChanged(displayId, frame);
            }
        }

        @Override
        public void onSourceBoundsChanged(int displayId, Rect sourceBounds) {
            if (mWindowMagnificationConnectionImpl != null) {
                mWindowMagnificationConnectionImpl.onSourceBoundsChanged(displayId, sourceBounds);
            }
        }

        @Override
        public void onPerformScaleAction(int displayId, float scale) {
            if (mWindowMagnificationConnectionImpl != null) {
                mWindowMagnificationConnectionImpl.onPerformScaleAction(displayId, scale);
            }
        }

        @Override
        public void onAccessibilityActionPerformed(int displayId) {
            if (mWindowMagnificationConnectionImpl != null) {
                mWindowMagnificationConnectionImpl.onAccessibilityActionPerformed(displayId);
            }
        }

        @Override
        public void onMove(int displayId) {
            if (mWindowMagnificationConnectionImpl != null) {
                mWindowMagnificationConnectionImpl.onMove(displayId);
            }
        }

        @Override
        public void onClickSettingsButton(int displayId) {
            mHandler.post(() -> {
                showMagnificationSettingsPanel(displayId);
            });
        }
    };

    @VisibleForTesting
    final MagnificationSettingsController.Callback mMagnificationSettingsControllerCallback =
            new MagnificationSettingsController.Callback() {
        @Override
        public void onSetMagnifierSize(int displayId, int index) {
            mHandler.post(() -> onSetMagnifierSizeInternal(displayId, index));
            mA11yLogger.log(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_WINDOW_SIZE_SELECTED);
        }

        @Override
        public void onSetDiagonalScrolling(int displayId, boolean enable) {
            mHandler.post(() -> onSetDiagonalScrollingInternal(displayId, enable));
        }

        @Override
        public void onEditMagnifierSizeMode(int displayId, boolean enable) {
            mHandler.post(() -> onEditMagnifierSizeModeInternal(displayId, enable));
            mA11yLogger.log(enable
                    ? MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_SIZE_EDITING_ACTIVATED
                    : MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_SIZE_EDITING_DEACTIVATED);
        }

        @Override
        public void onMagnifierScale(int displayId, float scale) {
            if (mWindowMagnificationConnectionImpl != null) {
                mWindowMagnificationConnectionImpl.onPerformScaleAction(displayId, scale);
            }
        }

        @Override
        public void onModeSwitch(int displayId, int newMode) {
            mHandler.post(() -> onModeSwitchInternal(displayId, newMode));
        }

        @Override
        public void onSettingsPanelVisibilityChanged(int displayId, boolean shown) {
            mHandler.post(() -> onSettingsPanelVisibilityChangedInternal(displayId, shown));
            mA11yLogger.log(shown
                    ? MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_OPENED
                    : MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_CLOSED);
        }
    };

    @MainThread
    private void onSetMagnifierSizeInternal(int displayId, int index) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.changeMagnificationSize(index);
        }
    }

    @MainThread
    private void onSetDiagonalScrollingInternal(int displayId, boolean enable) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.setDiagonalScrolling(enable);
        }
    }

    @MainThread
    private void onEditMagnifierSizeModeInternal(int displayId, boolean enable) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null && windowMagnificationController.isActivated()) {
            windowMagnificationController.setEditMagnifierSizeMode(enable);
        }
    }

    @MainThread
    private void onModeSwitchInternal(int displayId, int newMode) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        final boolean isWindowMagnifierActivated = windowMagnificationController.isActivated();
        final boolean isSwitchToWindowMode = (newMode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        final boolean changed = isSwitchToWindowMode ^ isWindowMagnifierActivated;
        if (changed) {
            final MagnificationSettingsController magnificationSettingsController =
                    mMagnificationSettingsSupplier.get(displayId);
            if (magnificationSettingsController != null) {
                magnificationSettingsController.closeMagnificationSettings();
            }
            if (mWindowMagnificationConnectionImpl != null) {
                mWindowMagnificationConnectionImpl.onChangeMagnificationMode(displayId, newMode);
            }
        }
    }

    @MainThread
    private void onSettingsPanelVisibilityChangedInternal(int displayId, boolean shown) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null && windowMagnificationController.isActivated()) {
            windowMagnificationController.updateDragHandleResourcesIfNeeded(shown);
        }
    }

    @Override
    public void requestWindowMagnificationConnection(boolean connect) {
        if (connect) {
            setWindowMagnificationConnection();
        } else {
            clearWindowMagnificationConnection();
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(TAG);
        mMagnificationControllerSupplier.forEach(
                magnificationController -> magnificationController.dump(pw));
    }

    private void setWindowMagnificationConnection() {
        if (mWindowMagnificationConnectionImpl == null) {
            mWindowMagnificationConnectionImpl = new WindowMagnificationConnectionImpl(this,
                    mHandler);
        }
        mAccessibilityManager.setWindowMagnificationConnection(
                mWindowMagnificationConnectionImpl);
    }

    private void clearWindowMagnificationConnection() {
        mAccessibilityManager.setWindowMagnificationConnection(null);
        //TODO: destroy controllers.
    }
}

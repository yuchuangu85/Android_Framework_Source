/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.view;

import static com.android.server.display.feature.flags.Flags.FLAG_ENABLE_DISPLAY_CONTENT_MODE_MANAGEMENT;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.RequiresPermission;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Region;
import android.os.IBinder;
import android.os.Looper;
import android.window.InputTransferToken;
import android.window.TaskFpsCallback;
import android.window.TrustedPresentationThresholds;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.window.flags.Flags;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A wrapper that wraps a base {@link WindowManager}.
 * <p>
 * Similar to {@link android.content.ContextWrapper}, it enables to customize behavior without
 * touching the original {@link WindowManager}.
 *
 * @hide
 */
public class WindowManagerWrapper implements WindowManager {

    @NonNull
    private final WindowManager mBase;

    public WindowManagerWrapper(@NonNull WindowManager base) {
        mBase = base;
    }

    @Override
    public void addView(View view, ViewGroup.LayoutParams params) {
        mBase.addView(view, params);
    }

    @Override
    public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
        mBase.updateViewLayout(view, params);
    }

    @Override
    public void removeView(View view) {
        mBase.removeView(view);
    }

    @Deprecated
    @Override
    public Display getDefaultDisplay() {
        return mBase.getDefaultDisplay();
    }

    @Override
    public void removeViewImmediate(View view) {
        mBase.removeViewImmediate(view);
    }

    @NonNull
    @Override
    public WindowMetrics getCurrentWindowMetrics() {
        return mBase.getCurrentWindowMetrics();
    }

    @NonNull
    @Override
    public WindowMetrics getMaximumWindowMetrics() {
        return mBase.getMaximumWindowMetrics();
    }

    @NonNull
    @Override
    public Set<WindowMetrics> getPossibleMaximumWindowMetrics(int displayId) {
        return mBase.getPossibleMaximumWindowMetrics(displayId);
    }

    @Override
    public void requestAppKeyboardShortcuts(KeyboardShortcutsReceiver receiver,
            int deviceId) {
        mBase.requestAppKeyboardShortcuts(receiver, deviceId);
    }

    @Override
    public KeyboardShortcutGroup getApplicationLaunchKeyboardShortcuts(int deviceId) {
        return mBase.getApplicationLaunchKeyboardShortcuts(deviceId);
    }

    @Override
    public void requestImeKeyboardShortcuts(KeyboardShortcutsReceiver receiver, int deviceId) {
        mBase.requestImeKeyboardShortcuts(receiver, deviceId);
    }

    @RequiresPermission(android.Manifest.permission.RESTRICTED_VR_ACCESS)
    @Override
    public Region getCurrentImeTouchRegion() {
        return mBase.getCurrentImeTouchRegion();
    }

    @Override
    public void setShouldShowWithInsecureKeyguard(int displayId, boolean shouldShow) {
        mBase.setShouldShowWithInsecureKeyguard(displayId, shouldShow);
    }

    @Override
    public boolean shouldShowSystemDecors(int displayId) {
        return mBase.shouldShowSystemDecors(displayId);
    }

    @Override
    public void setDisplayImePolicy(int displayId, int imePolicy) {
        mBase.setDisplayImePolicy(displayId, imePolicy);
    }

    @FlaggedApi(FLAG_ENABLE_DISPLAY_CONTENT_MODE_MANAGEMENT)
    @Override
    public boolean isEligibleForDesktopMode(int displayId) {
        return mBase.isEligibleForDesktopMode(displayId);
    }

    @Override
    public int getDisplayImePolicy(int displayId) {
        return mBase.getDisplayImePolicy(displayId);
    }

    @Override
    public boolean isGlobalKey(int keyCode) {
        return mBase.isGlobalKey(keyCode);
    }

    @Override
    public boolean isCrossWindowBlurEnabled() {
        return mBase.isCrossWindowBlurEnabled();
    }

    @Override
    public void addCrossWindowBlurEnabledListener(@NonNull Consumer<Boolean> listener) {
        mBase.addCrossWindowBlurEnabledListener(listener);
    }

    @Override
    public void addCrossWindowBlurEnabledListener(@NonNull Executor executor,
            @NonNull Consumer<Boolean> listener) {
        mBase.addCrossWindowBlurEnabledListener(executor, listener);
    }

    @Override
    public void removeCrossWindowBlurEnabledListener(@NonNull Consumer<Boolean> listener) {
        mBase.removeCrossWindowBlurEnabledListener(listener);
    }

    @Override
    public void addProposedRotationListener(@NonNull Executor executor,
            @NonNull IntConsumer listener) {
        mBase.addProposedRotationListener(executor, listener);
    }

    @Override
    public void removeProposedRotationListener(@NonNull IntConsumer listener) {
        mBase.removeProposedRotationListener(listener);
    }

    @Override
    public void holdLock(IBinder token, int durationMs) {
        mBase.holdLock(token, durationMs);
    }

    @Override
    public boolean isTaskSnapshotSupported() {
        return mBase.isTaskSnapshotSupported();
    }

    @Override
    public void registerTaskFpsCallback(int taskId, @NonNull Executor executor,
            @NonNull TaskFpsCallback callback) {
        mBase.registerTaskFpsCallback(taskId, executor, callback);
    }

    @Override
    public void unregisterTaskFpsCallback(@NonNull TaskFpsCallback callback) {
        mBase.unregisterTaskFpsCallback(callback);
    }

    @Nullable
    @Override
    public Bitmap snapshotTaskForRecents(int taskId) {
        return mBase.snapshotTaskForRecents(taskId);
    }

    @NonNull
    @Override
    public List<ComponentName> notifyScreenshotListeners(int displayId) {
        return mBase.notifyScreenshotListeners(displayId);
    }

    @RequiresPermission(Manifest.permission.ACCESS_SURFACE_FLINGER)
    @Override
    public boolean replaceContentOnDisplayWithMirror(int displayId, @NonNull Window window) {
        return mBase.replaceContentOnDisplayWithMirror(displayId, window);
    }

    @RequiresPermission(Manifest.permission.ACCESS_SURFACE_FLINGER)
    @Override
    public boolean replaceContentOnDisplayWithSc(int displayId, @NonNull SurfaceControl sc) {
        return mBase.replaceContentOnDisplayWithSc(displayId, sc);
    }

    @Override
    public void registerTrustedPresentationListener(@NonNull IBinder window,
            @NonNull TrustedPresentationThresholds thresholds, @NonNull Executor executor,
            @NonNull Consumer<Boolean> listener) {
        mBase.registerTrustedPresentationListener(window, thresholds, executor, listener);
    }

    @Override
    public void unregisterTrustedPresentationListener(@NonNull Consumer<Boolean> listener) {
        mBase.unregisterTrustedPresentationListener(listener);
    }

    @NonNull
    @Override
    public InputTransferToken registerBatchedSurfaceControlInputReceiver(
            @NonNull InputTransferToken hostInputTransferToken,
            @NonNull SurfaceControl surfaceControl, @NonNull Choreographer choreographer,
            @NonNull SurfaceControlInputReceiver receiver) {
        return mBase.registerBatchedSurfaceControlInputReceiver(
                hostInputTransferToken, surfaceControl, choreographer, receiver);
    }

    @NonNull
    @Override
    public InputTransferToken registerUnbatchedSurfaceControlInputReceiver(
            @NonNull InputTransferToken hostInputTransferToken,
            @NonNull SurfaceControl surfaceControl, @NonNull Looper looper,
            @NonNull SurfaceControlInputReceiver receiver) {
        return mBase.registerUnbatchedSurfaceControlInputReceiver(
                hostInputTransferToken, surfaceControl, looper, receiver);
    }

    @Override
    public void unregisterSurfaceControlInputReceiver(@NonNull SurfaceControl surfaceControl) {
        mBase.unregisterSurfaceControlInputReceiver(surfaceControl);
    }

    @Nullable
    @Override
    public IBinder getSurfaceControlInputClientToken(@NonNull SurfaceControl surfaceControl) {
        return mBase.getSurfaceControlInputClientToken(surfaceControl);
    }

    @Override
    public boolean transferTouchGesture(@NonNull InputTransferToken transferFromToken,
            @NonNull InputTransferToken transferToToken) {
        return mBase.transferTouchGesture(transferFromToken, transferToToken);
    }

    @NonNull
    @Override
    public IBinder getDefaultToken() {
        return mBase.getDefaultToken();
    }

    @FlaggedApi(Flags.FLAG_SCREEN_RECORDING_CALLBACKS)
    @RequiresPermission(Manifest.permission.DETECT_SCREEN_RECORDING)
    @Override
    public @ScreenRecordingState int addScreenRecordingCallback(@NonNull Executor executor,
            @NonNull Consumer<@ScreenRecordingState Integer> callback) {
        return mBase.addScreenRecordingCallback(executor, callback);
    }

    @FlaggedApi(Flags.FLAG_SCREEN_RECORDING_CALLBACKS)
    @RequiresPermission(Manifest.permission.DETECT_SCREEN_RECORDING)
    @Override
    public void removeScreenRecordingCallback(
            @NonNull Consumer<@ScreenRecordingState Integer> callback) {
        mBase.removeScreenRecordingCallback(callback);
    }

    @FlaggedApi(com.android.window.flags.Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    @RequiresPermission(Manifest.permission.MANAGE_DISPLAYS)
    @Override
    public void setDisplayEngagementMode(
            int displayId, @EngagementModeFlags int engagementModeFlags) {
        mBase.setDisplayEngagementMode(displayId, engagementModeFlags);
    }

    @FlaggedApi(com.android.window.flags.Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    @Override
    @EngagementModeFlags
    public int getDisplayEngagementMode(int displayId) {
        return mBase.getDisplayEngagementMode(displayId);
    }

    @FlaggedApi(com.android.window.flags.Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    @Override
    public void registerDisplayEngagementModeCallback(@NonNull Executor executor,
            @NonNull Consumer<DisplayEngagementModeState> callback) {
        mBase.registerDisplayEngagementModeCallback(executor, callback);
    }

    @FlaggedApi(com.android.window.flags.Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    @Override
    public void unregisterDisplayEngagementModeCallback(
            @NonNull Consumer<DisplayEngagementModeState> callback) {
        mBase.unregisterDisplayEngagementModeCallback(callback);
    }

    @Override
    public WindowManager createLocalWindowManager(@NonNull Window parentWindow) {
        final WindowManager newBase = mBase.createLocalWindowManager(parentWindow);
        return new WindowManagerWrapper(newBase);
    }

    @Override
    public void setParentWindow(@NonNull Window parentWindow) {
        mBase.setParentWindow(parentWindow);
    }
}

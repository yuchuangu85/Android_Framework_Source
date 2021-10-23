/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManagerInternal;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;
import android.view.IInputFilter;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IWindow;
import android.view.InputChannel;
import android.view.MagnificationSpec;
import android.view.RemoteAnimationTarget;
import android.view.WindowInfo;
import android.view.WindowManager.DisplayImePolicy;

import com.android.internal.policy.KeyInterceptionInfo;
import com.android.server.input.InputManagerService;
import com.android.server.policy.WindowManagerPolicy;

import java.util.List;

/**
 * Window manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class WindowManagerInternal {

    /**
     * Interface for accessibility features implemented by AccessibilityController inside
     * WindowManager.
     */
    public interface AccessibilityControllerInternal {
        /**
         * Enable the accessibility trace logging.
         */
        void startTrace();

        /**
         * Disable the accessibility trace logging.
         */
        void stopTrace();

        /**
         * Is trace enabled or not.
         */
        boolean isAccessibilityTracingEnabled();

        /**
         * Add an accessibility trace entry.
         *
         * @param where A string to identify this log entry, which can be used to filter/search
         *        through the tracing file.
         * @param callingParams The parameters for the method to be logged.
         * @param a11yDump The proto byte array for a11y state when the entry is generated.
         * @param callingUid The calling uid.
         * @param stackTrace The stack trace, null if not needed.
         */
        void logTrace(
                String where, String callingParams, byte[] a11yDump, int callingUid,
                StackTraceElement[] stackTrace);

        /**
         * Add an accessibility trace entry.
         *
         * @param where A string to identify this log entry, which can be used to filter/search
         *        through the tracing file.
         * @param callingParams The parameters for the method to be logged.
         * @param a11yDump The proto byte array for a11y state when the entry is generated.
         * @param callingUid The calling uid.
         * @param callStack The call stack of the method to be logged.
         * @param timeStamp The time when the method to be logged is called.
         * @param processId The calling process Id.
         * @param threadId The calling thread Id.
         */
        void logTrace(String where, String callingParams, byte[] a11yDump, int callingUid,
                StackTraceElement[] callStack, long timeStamp, int processId, long threadId);
    }

    /**
     * Interface to receive a callback when the windows reported for
     * accessibility changed.
     */
    public interface WindowsForAccessibilityCallback {

        /**
         * Called when the windows for accessibility changed.
         *
         * @param forceSend Send the windows for accessibility even if they haven't changed.
         * @param topFocusedDisplayId The display Id which has the top focused window.
         * @param topFocusedWindowToken The window token of top focused window.
         * @param windows The windows for accessibility.
         */
        void onWindowsForAccessibilityChanged(boolean forceSend, int topFocusedDisplayId,
                IBinder topFocusedWindowToken, @NonNull List<WindowInfo> windows);
    }

    /**
     * Callbacks for contextual changes that affect the screen magnification
     * feature.
     */
    public interface MagnificationCallbacks {

        /**
         * Called when the region where magnification operates changes. Note that this isn't the
         * entire screen. For example, IMEs are not magnified.
         *
         * @param magnificationRegion the current magnification region
         */
        void onMagnificationRegionChanged(Region magnificationRegion);

        /**
         * Called when an application requests a rectangle on the screen to allow
         * the client to apply the appropriate pan and scale.
         *
         * @param left The rectangle left.
         * @param top The rectangle top.
         * @param right The rectangle right.
         * @param bottom The rectangle bottom.
         */
        void onRectangleOnScreenRequested(int left, int top, int right, int bottom);

        /**
         * Notifies that the rotation changed.
         *
         * @param rotation The current rotation.
         */
        void onRotationChanged(int rotation);

        /**
         * Notifies that the context of the user changed. For example, an application
         * was started.
         */
        void onUserContextChanged();

        /**
         * Notifies that the IME window visibility changed.
         * @param shown {@code true} means the IME window shows on the screen. Otherwise it's
         *                           hidden.
         */
        void onImeWindowVisibilityChanged(boolean shown);
    }

    /**
     * Abstract class to be notified about {@link com.android.server.wm.AppTransition} events. Held
     * as an abstract class so a listener only needs to implement the methods of its interest.
     */
    public static abstract class AppTransitionListener {

        /**
         * Called when an app transition is being setup and about to be executed.
         */
        public void onAppTransitionPendingLocked() {}

        /**
         * Called when a pending app transition gets cancelled.
         *
         * @param keyguardGoingAway true if keyguard going away transition transition got cancelled.
         */
        public void onAppTransitionCancelledLocked(boolean keyguardGoingAway) {}

        /**
         * Called when an app transition is timed out.
         */
        public void onAppTransitionTimeoutLocked() {}

        /**
         * Called when an app transition gets started
         *
         * @param keyguardGoingAway true if keyguard going away transition is started.
         * @param duration the total duration of the transition
         * @param statusBarAnimationStartTime the desired start time for all visual animations in
         *        the status bar caused by this app transition in uptime millis
         * @param statusBarAnimationDuration the duration for all visual animations in the status
         *        bar caused by this app transition in millis
         *
         * @return Return any bit set of {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_LAYOUT},
         * {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_CONFIG},
         * {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_WALLPAPER},
         * or {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_ANIM}.
         */
        public int onAppTransitionStartingLocked(boolean keyguardGoingAway, long duration,
                long statusBarAnimationStartTime, long statusBarAnimationDuration) {
            return 0;
        }

        /**
         * Called when an app transition is finished running.
         *
         * @param token the token for app whose transition has finished
         */
        public void onAppTransitionFinishedLocked(IBinder token) {}
    }

    /**
     * An interface to be notified when keyguard exit animation should start.
     */
    public interface KeyguardExitAnimationStartListener {
        /**
         * Called when keyguard exit animation should start.
         * @param apps The list of apps to animate.
         * @param wallpapers The list of wallpapers to animate.
         * @param finishedCallback The callback to invoke when the animation is finished.
         */
        void onAnimationStart(RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers,
                IRemoteAnimationFinishedCallback finishedCallback);
    }

    /**
      * An interface to be notified about hardware keyboard status.
      */
    public interface OnHardKeyboardStatusChangeListener {
        public void onHardKeyboardStatusChange(boolean available);
    }

    /**
     * An interface to customize drag and drop behaviors.
     */
    public interface IDragDropCallback {
        default boolean registerInputChannel(
                DragState state, Display display, InputManagerService service,
                InputChannel source) {
            state.register(display);
            return service.transferTouchFocus(source, state.getInputChannel(),
                    true /* isDragDrop */);
        }

        /**
         * Called when drag operation is starting.
         */
        default boolean prePerformDrag(IWindow window, IBinder dragToken,
                int touchSource, float touchX, float touchY, float thumbCenterX, float thumbCenterY,
                ClipData data) {
            return true;
        }

        /**
         * Called when drag operation is started.
         */
        default void postPerformDrag() {}

        /**
         * Called when drop result is being reported.
         */
        default void preReportDropResult(IWindow window, boolean consumed) {}

        /**
         * Called when drop result was reported.
         */
        default void postReportDropResult() {}

        /**
         * Called when drag operation is being cancelled.
         */
        default void preCancelDragAndDrop(IBinder dragToken) {}

        /**
         * Called when drag operation was cancelled.
         */
        default void postCancelDragAndDrop() {}
    }

    /**
     * Request the interface to access features implemented by AccessibilityController.
     */
    public abstract AccessibilityControllerInternal getAccessibilityController();

    /**
     * Request that the window manager call
     * {@link DisplayManagerInternal#performTraversalInTransactionFromWindowManager}
     * within a surface transaction at a later time.
     */
    public abstract void requestTraversalFromDisplayManager();

    /**
     * Set by the accessibility layer to observe changes in the magnified region,
     * rotation, and other window transformations related to display magnification
     * as the window manager is responsible for doing the actual magnification
     * and has access to the raw window data while the accessibility layer serves
     * as a controller.
     *
     * @param displayId The logical display id.
     * @param callbacks The callbacks to invoke.
     * @return {@code false} if display id is not valid or an embedded display.
     */
    public abstract boolean setMagnificationCallbacks(int displayId,
            @Nullable MagnificationCallbacks callbacks);

    /**
     * Set by the accessibility layer to specify the magnification and panning to
     * be applied to all windows that should be magnified.
     *
     * @param displayId The logical display id.
     * @param spec The MagnficationSpec to set.
     *
     * @see #setMagnificationCallbacks(int, MagnificationCallbacks)
     */
    public abstract void setMagnificationSpec(int displayId, MagnificationSpec spec);

    /**
     * Set by the accessibility framework to indicate whether the magnifiable regions of the display
     * should be shown.
     *
     * @param displayId The logical display id.
     * @param show {@code true} to show magnifiable region bounds, {@code false} to hide
     */
    public abstract void setForceShowMagnifiableBounds(int displayId, boolean show);

    /**
     * Obtains the magnification regions.
     *
     * @param displayId The logical display id.
     * @param magnificationRegion the current magnification region
     */
    public abstract void getMagnificationRegion(int displayId, @NonNull Region magnificationRegion);

    /**
     * Gets the magnification and translation applied to a window given its token.
     * Not all windows are magnified and the window manager policy determines which
     * windows are magnified. The returned result also takes into account the compat
     * scale if necessary.
     *
     * @param windowToken The window's token.
     *
     * @return The magnification spec for the window.
     *
     * @see #setMagnificationCallbacks(int, MagnificationCallbacks)
     */
    public abstract MagnificationSpec getCompatibleMagnificationSpecForWindow(
            IBinder windowToken);

    /**
     * Sets a callback for observing which windows are touchable for the purposes
     * of accessibility on specified display.
     *
     * @param displayId The logical display id.
     * @param callback The callback.
     * @return {@code false} if display id is not valid.
     */
    public abstract boolean setWindowsForAccessibilityCallback(int displayId,
            WindowsForAccessibilityCallback callback);

    /**
     * Sets a filter for manipulating the input event stream.
     *
     * @param filter The filter implementation.
     */
    public abstract void setInputFilter(IInputFilter filter);

    /**
     * Gets the token of the window that has input focus.
     *
     * @return The token.
     */
    public abstract IBinder getFocusedWindowToken();

    /**
     * @return Whether the keyguard is engaged.
     */
    public abstract boolean isKeyguardLocked();

    /**
    * @return Whether the keyguard is showing and not occluded.
    */
    public abstract boolean isKeyguardShowingAndNotOccluded();

    /**
     * Gets the frame of a window given its token.
     *
     * @param token The token.
     * @param outBounds The frame to populate.
     */
    public abstract void getWindowFrame(IBinder token, Rect outBounds);

    /**
     * Opens the global actions dialog.
     */
    public abstract void showGlobalActions();

    /**
     * Invalidate all visible windows on a given display, and report back on the callback when all
     * windows have redrawn.
     *
     * @param callback reporting callback to be called when all windows have redrawn.
     * @param timeout calls the callback anyway after the timeout.
     * @param displayId waits for the windows on the given display, INVALID_DISPLAY to wait for all
     *                  windows on all displays.
     */
    public abstract void waitForAllWindowsDrawn(Runnable callback, long timeout, int displayId);

    /**
     * Overrides the display size.
     *
     * @param displayId The display to override the display size.
     * @param width The width to override.
     * @param height The height to override.
     */
    public abstract void setForcedDisplaySize(int displayId, int width, int height);

    /**
     * Recover the display size to real display size.
     *
     * @param displayId The display to recover the display size.
     */
    public abstract void clearForcedDisplaySize(int displayId);

    /**
     * Adds a window token for a given window type.
     *
     * @param token The token to add.
     * @param type The window type.
     * @param displayId The display to add the token to.
     * @param options A bundle used to pass window-related options.
     */
    public abstract void addWindowToken(@NonNull android.os.IBinder token, int type, int displayId,
            @Nullable Bundle options);

    /**
     * Removes a window token.
     *
     * @param token The toke to remove.
     * @param removeWindows Whether to also remove the windows associated with the token.
     * @param displayId The display to remove the token from.
     */
    public abstract void removeWindowToken(android.os.IBinder token, boolean removeWindows,
            int displayId);

    /**
     * Registers a listener to be notified about app transition events.
     *
     * @param listener The listener to register.
     */
    public abstract void registerAppTransitionListener(AppTransitionListener listener);

    /**
     * Registers a listener to be notified to start the keyguard exit animation.
     *
     * @param listener The listener to register.
     */
    public abstract void registerKeyguardExitAnimationStartListener(
            KeyguardExitAnimationStartListener listener);

    /**
     * Reports that the password for the given user has changed.
     */
    public abstract void reportPasswordChanged(int userId);

    /**
     * Retrieves a height of input method window for given display.
     */
    public abstract int getInputMethodWindowVisibleHeight(int displayId);

    /**
     * Notifies WindowManagerService that the expected back-button behavior might have changed.
     *
     * <p>Only {@link com.android.server.inputmethod.InputMethodManagerService} is the expected and
     * tested caller of this method.</p>
     *
     * @param dismissImeOnBackKeyPressed {@code true} if the software keyboard is shown and the back
     *                                   key is expected to dismiss the software keyboard.
     */
    public abstract void setDismissImeOnBackKeyPressed(boolean dismissImeOnBackKeyPressed);

    /**
     * Notifies WindowManagerService that the current IME window status is being changed.
     *
     * <p>Only {@link com.android.server.inputmethod.InputMethodManagerService} is the expected and
     * tested caller of this method.</p>
     *
     * @param imeToken token to track the active input method. Corresponding IME windows can be
     *                 identified by checking {@link android.view.WindowManager.LayoutParams#token}.
     *                 Note that there is no guarantee that the corresponding window is already
     *                 created
     * @param imeTargetWindowToken token to identify the target window that the IME is associated
     *                             with
     */
    public abstract void updateInputMethodTargetWindow(@NonNull IBinder imeToken,
            @NonNull IBinder imeTargetWindowToken);

    /**
      * Returns true when the hardware keyboard is available.
      */
    public abstract boolean isHardKeyboardAvailable();

    /**
      * Sets the callback listener for hardware keyboard status changes.
      *
      * @param listener The listener to set.
      */
    public abstract void setOnHardKeyboardStatusChangeListener(
        OnHardKeyboardStatusChangeListener listener);

    /**
     * Requests the window manager to resend the windows for accessibility on specified display.
     *
     * @param displayId Display ID to be computed its windows for accessibility
     */
    public abstract void computeWindowsForAccessibility(int displayId);

    /**
     * Called after virtual display Id is updated by
     * {@link com.android.server.vr.Vr2dDisplay} with a specific
     * {@param vr2dDisplayId}.
     */
    public abstract void setVr2dDisplayId(int vr2dDisplayId);

    /**
     * Sets callback to DragDropController.
     */
    public abstract void registerDragDropControllerCallback(IDragDropCallback callback);

    /**
     * @see android.view.IWindowManager#lockNow
     */
    public abstract void lockNow();

    /**
     * Return the user that owns the given window, {@link android.os.UserHandle#USER_NULL} if
     * the window token is not found.
     */
    public abstract int getWindowOwnerUserId(IBinder windowToken);

    /**
     * Returns {@code true} if a Window owned by {@code uid} has focus.
     */
    public abstract boolean isUidFocused(int uid);

    /**
     * Checks whether the specified IME client has IME focus or not.
     *
     * @param uid UID of the process to be queried
     * @param pid PID of the process to be queried
     * @param displayId Display ID reported from the client. Note that this method also verifies
     *                  whether the specified process is allowed to access to this display or not
     * @return {@code true} if the IME client specified with {@code uid}, {@code pid}, and
     *         {@code displayId} has IME focus
     */
    public abstract boolean isInputMethodClientFocus(int uid, int pid, int displayId);

    /**
     * Checks whether the given {@code uid} is allowed to use the given {@code displayId} or not.
     *
     * @param displayId Display ID to be checked
     * @param uid UID to be checked.
     * @return {@code true} if the given {@code uid} is allowed to use the given {@code displayId}
     */
    public abstract boolean isUidAllowedOnDisplay(int displayId, int uid);

    /**
     * Return the display Id for given window.
     */
    public abstract int getDisplayIdForWindow(IBinder windowToken);

    /**
     * @return The top focused display ID.
     */
    public abstract int getTopFocusedDisplayId();

    /**
     * @return The UI context of top focused display.
     */
    public abstract Context getTopFocusedDisplayUiContext();

    /**
     * Checks if this display is configured and allowed to show system decorations.
     */
    public abstract boolean shouldShowSystemDecorOnDisplay(int displayId);

    /**
     * Indicates the policy for how the display should show IME.
     *
     * @param displayId The id of the display.
     * @return The policy for how the display should show IME.
     */
    public abstract @DisplayImePolicy int getDisplayImePolicy(int displayId);

    /**
     * Show IME on imeTargetWindow once IME has finished layout.
     *
     * @param imeTargetWindowToken token of the (IME target) window on which IME should be shown.
     */
    public abstract void showImePostLayout(IBinder imeTargetWindowToken);

    /**
     * Hide IME using imeTargetWindow when requested.
     *
     * @param imeTargetWindowToken token of the (IME target) window on which IME should be hidden.
     * @param displayId the id of the display the IME is on.
     */
    public abstract void hideIme(IBinder imeTargetWindowToken, int displayId);

    /**
     * Tell window manager about a package that should not be running with high refresh rate
     * setting until removeNonHighRefreshRatePackage is called for the same package.
     *
     * This must not be called again for the same package.
     */
    public abstract void addNonHighRefreshRatePackage(@NonNull String packageName);

    /**
     * Tell window manager to stop constraining refresh rate for the given package.
     */
    public abstract void removeNonHighRefreshRatePackage(@NonNull String packageName);

    /**
     * Checks if the device supports touch or faketouch.
     */
    public abstract boolean isTouchOrFaketouchDevice();

    /**
     * Returns the info associated with the input token used to determine if a key should be
     * intercepted. This info can be accessed without holding the global wm lock.
     */
    public abstract @Nullable KeyInterceptionInfo
            getKeyInterceptionInfoFromToken(IBinder inputToken);

    /**
     * Clears the snapshot cache of running activities so they show the splash-screen
     * the next time the activities are opened.
     */
    public abstract void clearSnapshotCache();

    /**
     * Assigns accessibility ID a window surface as a layer metadata.
     */
    public abstract void setAccessibilityIdToSurfaceMetadata(
            IBinder windowToken, int accessibilityWindowId);

    /**
     *
     * Returns the window name associated to the given binder.
     *
     * @param binder The {@link IBinder} object
     * @return The corresponding {@link WindowState#getName()}
     */
    public abstract String getWindowName(@NonNull IBinder binder);

    /**
     * Return the window name of IME Insets control target.
     *
     * @param displayId The ID of the display which input method is currently focused.
     * @return The corresponding {@link WindowState#getName()}
     */
    public abstract @Nullable String getImeControlTargetNameForLogging(int displayId);

    /**
     * Return the current window name of the input method is on top of.
     *
     * Note that the concept of this window is only reparent the target window behind the input
     * method window, it may different with the window which reported by
     * {@code InputMethodManagerService#reportStartInput} which has input connection.
     *
     * @param displayId The ID of the display which input method is currently focused.
     * @return The corresponding {@link WindowState#getName()}
     */
    public abstract @Nullable String getImeTargetNameForLogging(int displayId);

    /**
     * Moves the {@link WindowToken} {@code binder} to the display specified by {@code displayId}.
     */
    public abstract void moveWindowTokenToDisplay(IBinder binder, int displayId);

    /**
     * Checks whether the given window should restore the last IME visibility.
     *
     * @param imeTargetWindowToken The token of the (IME target) window
     * @return {@code true} when the system allows to restore the IME visibility,
     *         {@code false} otherwise.
     */
    public abstract boolean shouldRestoreImeVisibility(IBinder imeTargetWindowToken);
}

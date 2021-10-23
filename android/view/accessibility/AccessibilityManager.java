/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.view.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityServiceInfo.FeedbackType;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.view.IWindow;
import android.view.View;
import android.view.accessibility.AccessibilityEvent.EventType;

import java.util.Collections;
import java.util.List;

/**
 * System level service that serves as an event dispatch for {@link AccessibilityEvent}s.
 * Such events are generated when something notable happens in the user interface,
 * for example an {@link android.app.Activity} starts, the focus or selection of a
 * {@link android.view.View} changes etc. Parties interested in handling accessibility
 * events implement and register an accessibility service which extends
 * {@code android.accessibilityservice.AccessibilityService}.
 *
 * @see AccessibilityEvent
 * @see android.content.Context#getSystemService
 */
@SuppressWarnings("UnusedDeclaration")
public final class AccessibilityManager {

    private static AccessibilityManager sInstance = new AccessibilityManager(null, null, 0);


    /**
     * Listener for the accessibility state.
     */
    public interface AccessibilityStateChangeListener {

        /**
         * Called back on change in the accessibility state.
         *
         * @param enabled Whether accessibility is enabled.
         */
        public void onAccessibilityStateChanged(boolean enabled);
    }

    /**
     * Listener for the system touch exploration state. To listen for changes to
     * the touch exploration state on the device, implement this interface and
     * register it with the system by calling
     * {@link #addTouchExplorationStateChangeListener}.
     */
    public interface TouchExplorationStateChangeListener {

        /**
         * Called when the touch exploration enabled state changes.
         *
         * @param enabled Whether touch exploration is enabled.
         */
        public void onTouchExplorationStateChanged(boolean enabled);
    }

    /**
     * Listener for the system high text contrast state. To listen for changes to
     * the high text contrast state on the device, implement this interface and
     * register it with the system by calling
     * {@link #addHighTextContrastStateChangeListener}.
     */
    public interface HighTextContrastChangeListener {

        /**
         * Called when the high text contrast enabled state changes.
         *
         * @param enabled Whether high text contrast is enabled.
         */
        public void onHighTextContrastStateChanged(boolean enabled);
    }

    /**
     * Policy to inject behavior into the accessibility manager.
     *
     * @hide
     */
    public interface AccessibilityPolicy {
        /**
         * Checks whether accessibility is enabled.
         *
         * @param accessibilityEnabled Whether the accessibility layer is enabled.
         * @return whether accessibility is enabled.
         */
        boolean isEnabled(boolean accessibilityEnabled);

        /**
         * Notifies the policy for an accessibility event.
         *
         * @param event The event.
         * @param accessibilityEnabled Whether the accessibility layer is enabled.
         * @param relevantEventTypes The events relevant events.
         * @return The event to dispatch or null.
         */
        @Nullable AccessibilityEvent onAccessibilityEvent(@NonNull AccessibilityEvent event,
                boolean accessibilityEnabled, @EventType int relevantEventTypes);

        /**
         * Gets the list of relevant events.
         *
         * @param relevantEventTypes The relevant events.
         * @return The relevant events to report.
         */
        @EventType int getRelevantEventTypes(@EventType int relevantEventTypes);

        /**
         * Gets the list of installed services to report.
         *
         * @param installedService The installed services.
         * @return The services to report.
         */
        @NonNull List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(
                @Nullable List<AccessibilityServiceInfo> installedService);

        /**
         * Gets the list of enabled accessibility services.
         *
         * @param feedbackTypeFlags The feedback type to query for.
         * @param enabledService The enabled services.
         * @return The services to report.
         */
        @Nullable List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(
                @FeedbackType int feedbackTypeFlags,
                @Nullable List<AccessibilityServiceInfo> enabledService);
    }

    private final IAccessibilityManagerClient.Stub mClient =
            new IAccessibilityManagerClient.Stub() {
                public void setState(int state) {
                }

                public void notifyServicesStateChanged(long updatedUiTimeout) {
                }

                public void setRelevantEventTypes(int eventTypes) {
                }

                public void setFocusAppearance(int strokeWidth, int color) {
                }
            };

    /**
     * Get an AccessibilityManager instance (create one if necessary).
     *
     */
    public static AccessibilityManager getInstance(Context context) {
        return sInstance;
    }

    /**
     * Create an instance.
     *
     * @param context A {@link Context}.
     */
    public AccessibilityManager(Context context, IAccessibilityManager service, int userId) {
    }

    public IAccessibilityManagerClient getClient() {
        return mClient;
    }

    /**
     * Returns if the {@link AccessibilityManager} is enabled.
     *
     * @return True if this {@link AccessibilityManager} is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return false;
    }

    /**
     * Returns if the touch exploration in the system is enabled.
     *
     * @return True if touch exploration is enabled, false otherwise.
     */
    public boolean isTouchExplorationEnabled() {
        return true;
    }

    /**
     * Returns if the high text contrast in the system is enabled.
     * <p>
     * <strong>Note:</strong> You need to query this only if you application is
     * doing its own rendering and does not rely on the platform rendering pipeline.
     * </p>
     *
     */
    public boolean isHighTextContrastEnabled() {
        return false;
    }

    /**
     * Sends an {@link AccessibilityEvent}.
     */
    public void sendAccessibilityEvent(AccessibilityEvent event) {
    }

    /**
     * Returns whether there are observers registered for this event type. If
     * this method returns false you shuold not generate events of this type
     * to conserve resources.
     *
     * @param type The event type.
     * @return Whether the event is being observed.
     */
    public boolean isObservedEventType(@AccessibilityEvent.EventType int type) {
        return false;
    }

    /**
     * Requests interruption of the accessibility feedback from all accessibility services.
     */
    public void interrupt() {
    }

    /**
     * Returns the {@link ServiceInfo}s of the installed accessibility services.
     *
     * @return An unmodifiable list with {@link ServiceInfo}s.
     */
    @Deprecated
    public List<ServiceInfo> getAccessibilityServiceList() {
        return Collections.emptyList();
    }

    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList() {
        return Collections.emptyList();
    }

    /**
     * Returns the {@link AccessibilityServiceInfo}s of the enabled accessibility services
     * for a given feedback type.
     *
     * @param feedbackTypeFlags The feedback type flags.
     * @return An unmodifiable list with {@link AccessibilityServiceInfo}s.
     *
     * @see AccessibilityServiceInfo#FEEDBACK_AUDIBLE
     * @see AccessibilityServiceInfo#FEEDBACK_GENERIC
     * @see AccessibilityServiceInfo#FEEDBACK_HAPTIC
     * @see AccessibilityServiceInfo#FEEDBACK_SPOKEN
     * @see AccessibilityServiceInfo#FEEDBACK_VISUAL
     */
    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(
            int feedbackTypeFlags) {
        return Collections.emptyList();
    }

    /**
     * Registers an {@link AccessibilityStateChangeListener} for changes in
     * the global accessibility state of the system.
     *
     * @param listener The listener.
     * @return True if successfully registered.
     */
    public boolean addAccessibilityStateChangeListener(
            AccessibilityStateChangeListener listener) {
        return true;
    }

    /**
     * Registers an {@link AccessibilityStateChangeListener} for changes in
     * the global accessibility state of the system. If the listener has already been registered,
     * the handler used to call it back is updated.
     *
     * @param listener The listener.
     * @param handler The handler on which the listener should be called back, or {@code null}
     *                for a callback on the process's main handler.
     */
    public void addAccessibilityStateChangeListener(
            @NonNull AccessibilityStateChangeListener listener, @Nullable Handler handler) {}

    public boolean removeAccessibilityStateChangeListener(
            AccessibilityStateChangeListener listener) {
        return true;
    }

    /**
     * Registers a {@link TouchExplorationStateChangeListener} for changes in
     * the global touch exploration state of the system.
     *
     * @param listener The listener.
     * @return True if successfully registered.
     */
    public boolean addTouchExplorationStateChangeListener(
            @NonNull TouchExplorationStateChangeListener listener) {
        return true;
    }

    /**
     * Registers an {@link TouchExplorationStateChangeListener} for changes in
     * the global touch exploration state of the system. If the listener has already been
     * registered, the handler used to call it back is updated.
     *
     * @param listener The listener.
     * @param handler The handler on which the listener should be called back, or {@code null}
     *                for a callback on the process's main handler.
     */
    public void addTouchExplorationStateChangeListener(
            @NonNull TouchExplorationStateChangeListener listener, @Nullable Handler handler) {}

    /**
     * Unregisters a {@link TouchExplorationStateChangeListener}.
     *
     * @param listener The listener.
     * @return True if successfully unregistered.
     */
    public boolean removeTouchExplorationStateChangeListener(
            @NonNull TouchExplorationStateChangeListener listener) {
        return true;
    }

    /**
     * Registers a {@link HighTextContrastChangeListener} for changes in
     * the global high text contrast state of the system.
     *
     * @param listener The listener.
     *
     * @hide
     */
    public void addHighTextContrastStateChangeListener(
            @NonNull HighTextContrastChangeListener listener, @Nullable Handler handler) {}

    /**
     * Unregisters a {@link HighTextContrastChangeListener}.
     *
     * @param listener The listener.
     *
     * @hide
     */
    public void removeHighTextContrastStateChangeListener(
            @NonNull HighTextContrastChangeListener listener) {}

    /**
     * Sets the current state and notifies listeners, if necessary.
     *
     * @param stateFlags The state flags.
     */
    private void setStateLocked(int stateFlags) {
    }

    public int addAccessibilityInteractionConnection(IWindow windowToken,
            IAccessibilityInteractionConnection connection) {
        return View.NO_ID;
    }

    public void removeAccessibilityInteractionConnection(IWindow windowToken) {
    }

}

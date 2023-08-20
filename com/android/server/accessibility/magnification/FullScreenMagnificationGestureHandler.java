/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;

import static com.android.internal.accessibility.util.AccessibilityStatsLogUtils.logMagnificationTripleTap;
import static com.android.server.accessibility.gestures.GestureUtils.distance;
import static com.android.server.accessibility.gestures.GestureUtils.distanceClosestPointerToPoint;

import static java.lang.Math.abs;
import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;

import android.accessibilityservice.MagnificationConfig;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PointF;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.view.ViewConfiguration;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.gestures.GestureUtils;

/**
 * This class handles full screen magnification in response to touch events.
 *
 * The behavior is as follows:
 *
 * 1. Triple tap toggles permanent screen magnification which is magnifying
 *    the area around the location of the triple tap. One can think of the
 *    location of the triple tap as the center of the magnified viewport.
 *    For example, a triple tap when not magnified would magnify the screen
 *    and leave it in a magnified state. A triple tapping when magnified would
 *    clear magnification and leave the screen in a not magnified state.
 *
 * 2. Triple tap and hold would magnify the screen if not magnified and enable
 *    viewport dragging mode until the finger goes up. One can think of this
 *    mode as a way to move the magnified viewport since the area around the
 *    moving finger will be magnified to fit the screen. For example, if the
 *    screen was not magnified and the user triple taps and holds the screen
 *    would magnify and the viewport will follow the user's finger. When the
 *    finger goes up the screen will zoom out. If the same user interaction
 *    is performed when the screen is magnified, the viewport movement will
 *    be the same but when the finger goes up the screen will stay magnified.
 *    In other words, the initial magnified state is sticky.
 *
 * 3. Magnification can optionally be "triggered" by some external shortcut
 *    affordance. When this occurs via {@link #notifyShortcutTriggered()} a
 *    subsequent tap in a magnifiable region will engage permanent screen
 *    magnification as described in #1. Alternatively, a subsequent long-press
 *    or drag will engage magnification with viewport dragging as described in
 *    #2. Once magnified, all following behaviors apply whether magnification
 *    was engaged via a triple-tap or by a triggered shortcut.
 *
 * 4. Pinching with any number of additional fingers when viewport dragging
 *    is enabled, i.e. the user triple tapped and holds, would adjust the
 *    magnification scale which will become the current default magnification
 *    scale. The next time the user magnifies the same magnification scale
 *    would be used.
 *
 * 5. When in a permanent magnified state the user can use two or more fingers
 *    to pan the viewport. Note that in this mode the content is panned as
 *    opposed to the viewport dragging mode in which the viewport is moved.
 *
 * 6. When in a permanent magnified state the user can use two or more
 *    fingers to change the magnification scale which will become the current
 *    default magnification scale. The next time the user magnifies the same
 *    magnification scale would be used.
 *
 * 7. The magnification scale will be persisted in settings and in the cloud.
 */
@SuppressWarnings("WeakerAccess")
public class FullScreenMagnificationGestureHandler extends MagnificationGestureHandler {

    private static final boolean DEBUG_STATE_TRANSITIONS = false | DEBUG_ALL;
    private static final boolean DEBUG_DETECTING = false | DEBUG_ALL;
    private static final boolean DEBUG_PANNING_SCALING = false | DEBUG_ALL;

    // The MIN_SCALE is different from MagnificationScaleProvider.MIN_SCALE due
    // to AccessibilityService.MagnificationController#setScale() has
    // different scale range
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = MagnificationScaleProvider.MAX_SCALE;

    @VisibleForTesting final FullScreenMagnificationController mFullScreenMagnificationController;

    private final FullScreenMagnificationController.MagnificationInfoChangedCallback
            mMagnificationInfoChangedCallback;
    @VisibleForTesting final DelegatingState mDelegatingState;
    @VisibleForTesting final DetectingState mDetectingState;
    @VisibleForTesting final PanningScalingState mPanningScalingState;
    @VisibleForTesting final ViewportDraggingState mViewportDraggingState;

    private final ScreenStateReceiver mScreenStateReceiver;
    private final WindowMagnificationPromptController mPromptController;

    @VisibleForTesting State mCurrentState;
    @VisibleForTesting State mPreviousState;

    private PointerCoords[] mTempPointerCoords;
    private PointerProperties[] mTempPointerProperties;

    public FullScreenMagnificationGestureHandler(@UiContext Context context,
            FullScreenMagnificationController fullScreenMagnificationController,
            AccessibilityTraceManager trace,
            Callback callback,
            boolean detectTripleTap,
            boolean detectShortcutTrigger,
            @NonNull WindowMagnificationPromptController promptController,
            int displayId) {
        super(displayId, detectTripleTap, detectShortcutTrigger, trace, callback);
        if (DEBUG_ALL) {
            Log.i(mLogTag,
                    "FullScreenMagnificationGestureHandler(detectTripleTap = " + detectTripleTap
                            + ", detectShortcutTrigger = " + detectShortcutTrigger + ")");
        }
        mFullScreenMagnificationController = fullScreenMagnificationController;
        mMagnificationInfoChangedCallback =
                new FullScreenMagnificationController.MagnificationInfoChangedCallback() {
                    @Override
                    public void onRequestMagnificationSpec(int displayId, int serviceId) {
                        return;
                    }

                    @Override
                    public void onFullScreenMagnificationActivationState(int displayId,
                            boolean activated) {
                        if (displayId != mDisplayId) {
                            return;
                        }

                        if (!activated) {
                            clearAndTransitionToStateDetecting();
                        }
                    }

                    @Override
                    public void onImeWindowVisibilityChanged(int displayId, boolean shown) {
                        return;
                    }

                    @Override
                    public void onFullScreenMagnificationChanged(int displayId,
                            @NonNull Region region,
                            @NonNull MagnificationConfig config) {
                        return;
                    }
                };
        mFullScreenMagnificationController.addInfoChangedCallback(
                mMagnificationInfoChangedCallback);

        mPromptController = promptController;

        mDelegatingState = new DelegatingState();
        mDetectingState = new DetectingState(context);
        mViewportDraggingState = new ViewportDraggingState();
        mPanningScalingState = new PanningScalingState(context);

        if (mDetectShortcutTrigger) {
            mScreenStateReceiver = new ScreenStateReceiver(context, this);
            mScreenStateReceiver.register();
        } else {
            mScreenStateReceiver = null;
        }

        transitionTo(mDetectingState);
    }

    @Override
    void onMotionEventInternal(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        handleEventWith(mCurrentState, event, rawEvent, policyFlags);
    }

    private void handleEventWith(State stateHandler,
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // To keep InputEventConsistencyVerifiers within GestureDetectors happy
        mPanningScalingState.mScrollGestureDetector.onTouchEvent(event);
        mPanningScalingState.mScaleGestureDetector.onTouchEvent(event);

        try {
            stateHandler.onMotionEvent(event, rawEvent, policyFlags);
        } catch (GestureException e) {
            Slog.e(mLogTag, "Error processing motion event", e);
            clearAndTransitionToStateDetecting();
        }
    }

    @Override
    public void clearEvents(int inputSource) {
        if (inputSource == SOURCE_TOUCHSCREEN) {
            clearAndTransitionToStateDetecting();
        }

        super.clearEvents(inputSource);
    }

    @Override
    public void onDestroy() {
        if (DEBUG_STATE_TRANSITIONS) {
            Slog.i(mLogTag, "onDestroy(); delayed = "
                    + MotionEventInfo.toString(mDetectingState.mDelayedEventQueue));
        }

        if (mScreenStateReceiver != null) {
            mScreenStateReceiver.unregister();
        }
        mPromptController.onDestroy();
        // Check if need to reset when MagnificationGestureHandler is the last magnifying service.
        mFullScreenMagnificationController.resetIfNeeded(
                mDisplayId, AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
        mFullScreenMagnificationController.removeInfoChangedCallback(
                mMagnificationInfoChangedCallback);
        clearAndTransitionToStateDetecting();
    }

    @Override
    public void handleShortcutTriggered() {
        final boolean isActivated = mFullScreenMagnificationController.isActivated(mDisplayId);

        if (isActivated) {
            zoomOff();
            clearAndTransitionToStateDetecting();
        } else {
            mDetectingState.toggleShortcutTriggered();
        }

        if (mDetectingState.isShortcutTriggered()) {
            mPromptController.showNotificationIfNeeded();
            zoomToScale(1.0f, Float.NaN, Float.NaN);
        }
    }

    @Override
    public int getMode() {
        return Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
    }

    void clearAndTransitionToStateDetecting() {
        mCurrentState = mDetectingState;
        mDetectingState.clear();
        mViewportDraggingState.clear();
        mPanningScalingState.clear();
    }

    private PointerCoords[] getTempPointerCoordsWithMinSize(int size) {
        final int oldSize = (mTempPointerCoords != null) ? mTempPointerCoords.length : 0;
        if (oldSize < size) {
            PointerCoords[] oldTempPointerCoords = mTempPointerCoords;
            mTempPointerCoords = new PointerCoords[size];
            if (oldTempPointerCoords != null) {
                System.arraycopy(oldTempPointerCoords, 0, mTempPointerCoords, 0, oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerCoords[i] = new PointerCoords();
        }
        return mTempPointerCoords;
    }

    private PointerProperties[] getTempPointerPropertiesWithMinSize(int size) {
        final int oldSize = (mTempPointerProperties != null) ? mTempPointerProperties.length
                : 0;
        if (oldSize < size) {
            PointerProperties[] oldTempPointerProperties = mTempPointerProperties;
            mTempPointerProperties = new PointerProperties[size];
            if (oldTempPointerProperties != null) {
                System.arraycopy(oldTempPointerProperties, 0, mTempPointerProperties, 0,
                        oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerProperties[i] = new PointerProperties();
        }
        return mTempPointerProperties;
    }

    @VisibleForTesting
    void transitionTo(State state) {
        if (DEBUG_STATE_TRANSITIONS) {
            Slog.i(mLogTag,
                    (State.nameOf(mCurrentState) + " -> " + State.nameOf(state)
                    + " at " + asList(copyOfRange(new RuntimeException().getStackTrace(), 1, 5)))
                    .replace(getClass().getName(), ""));
        }
        mPreviousState = mCurrentState;
        if (state == mPanningScalingState) {
            mPanningScalingState.prepareForState();
        }
        mCurrentState = state;
    }

    interface State {
        void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags)
                throws GestureException;

        default void clear() {}

        default String name() {
            return getClass().getSimpleName();
        }

        static String nameOf(@Nullable State s) {
            return s != null ? s.name() : "null";
        }
    }

    /**
     * This class determines if the user is performing a scale or pan gesture.
     *
     * Unlike when {@link ViewportDraggingState dragging the viewport}, in panning mode the viewport
     * moves in the same direction as the fingers, and allows to easily and precisely scale the
     * magnification level.
     * This makes it the preferred mode for one-off adjustments, due to its precision and ease of
     * triggering.
     */
    final class PanningScalingState extends SimpleOnGestureListener
            implements OnScaleGestureListener, State {

        private final Context mContext;
        private final ScaleGestureDetector mScaleGestureDetector;
        private final GestureDetector mScrollGestureDetector;
        final float mScalingThreshold;

        float mInitialScaleFactor = -1;
        @VisibleForTesting boolean mScaling;

        /**
         * Whether it needs to detect the target scale passes
         * {@link FullScreenMagnificationController#getPersistedScale} during panning scale.
         */
        @VisibleForTesting boolean mDetectingPassPersistedScale;

        // The threshold for relative difference from given scale to persisted scale. If the
        // difference >= threshold, we can start detecting if the scale passes the persisted
        // scale during panning.
        @VisibleForTesting static final float CHECK_DETECTING_PASS_PERSISTED_SCALE_THRESHOLD = 0.2f;
        // The threshold for relative difference from given scale to persisted scale. If the
        // difference < threshold, we can decide that the scale passes the persisted scale.
        @VisibleForTesting static final float PASSING_PERSISTED_SCALE_THRESHOLD = 0.01f;

        PanningScalingState(Context context) {
            final TypedValue scaleValue = new TypedValue();
            context.getResources().getValue(
                    R.dimen.config_screen_magnification_scaling_threshold,
                    scaleValue, false);
            mContext = context;
            mScalingThreshold = scaleValue.getFloat();
            mScaleGestureDetector = new ScaleGestureDetector(context, this, Handler.getMain());
            mScaleGestureDetector.setQuickScaleEnabled(false);
            mScrollGestureDetector = new GestureDetector(context, this, Handler.getMain());
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            int action = event.getActionMasked();

            if (action == ACTION_POINTER_UP
                    && event.getPointerCount() == 2 // includes the pointer currently being released
                    && mPreviousState == mViewportDraggingState) {

                persistScaleAndTransitionTo(mViewportDraggingState);

            } else if (action == ACTION_UP || action == ACTION_CANCEL) {

                persistScaleAndTransitionTo(mDetectingState);
            }
        }


        void prepareForState() {
            checkShouldDetectPassPersistedScale();
        }

        private void checkShouldDetectPassPersistedScale() {
            if (mDetectingPassPersistedScale) {
                return;
            }

            final float currentScale =
                    mFullScreenMagnificationController.getScale(mDisplayId);
            final float persistedScale =
                    mFullScreenMagnificationController.getPersistedScale(mDisplayId);

            mDetectingPassPersistedScale =
                    (abs(currentScale - persistedScale) / persistedScale)
                            >= CHECK_DETECTING_PASS_PERSISTED_SCALE_THRESHOLD;
        }

        public void persistScaleAndTransitionTo(State state) {
            mFullScreenMagnificationController.persistScale(mDisplayId);
            clear();
            transitionTo(state);
        }

        @VisibleForTesting
        void setScaleAndClearIfNeeded(float scale, float pivotX, float pivotY) {
            if (mDetectingPassPersistedScale) {
                final float persistedScale =
                        mFullScreenMagnificationController.getPersistedScale(mDisplayId);
                // If the scale passes the persisted scale during panning, perform a vibration
                // feedback to user. Also, call {@link clear} to create a buffer zone so that
                // user needs to panning more than {@link mScalingThreshold} to change scale again.
                if (abs(scale - persistedScale) / persistedScale
                        < PASSING_PERSISTED_SCALE_THRESHOLD) {
                    scale = persistedScale;
                    final Vibrator vibrator = mContext.getSystemService(Vibrator.class);
                    if (vibrator != null) {
                        vibrator.vibrate(
                                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
                    }
                    clear();
                }
            }

            if (DEBUG_PANNING_SCALING) Slog.i(mLogTag, "Scaled content to: " + scale + "x");
            mFullScreenMagnificationController.setScale(mDisplayId, scale, pivotX, pivotY, false,
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);

            checkShouldDetectPassPersistedScale();
        }

        @Override
        public boolean onScroll(MotionEvent first, MotionEvent second,
                float distanceX, float distanceY) {
            if (mCurrentState != mPanningScalingState) {
                return true;
            }
            if (DEBUG_PANNING_SCALING) {
                Slog.i(mLogTag, "Panned content by scrollX: " + distanceX
                        + " scrollY: " + distanceY);
            }
            mFullScreenMagnificationController.offsetMagnifiedRegion(mDisplayId, distanceX,
                    distanceY, AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            return /* event consumed: */ true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (!mScaling) {
                if (mInitialScaleFactor < 0) {
                    mInitialScaleFactor = detector.getScaleFactor();
                    return false;
                }
                final float deltaScale = detector.getScaleFactor() - mInitialScaleFactor;
                mScaling = abs(deltaScale) > mScalingThreshold;
                return mScaling;
            }
            final float initialScale = mFullScreenMagnificationController.getScale(mDisplayId);
            final float targetScale = initialScale * detector.getScaleFactor();

            // Don't allow a gesture to move the user further outside the
            // desired bounds for gesture-controlled scaling.
            final float scale;
            if (targetScale > MAX_SCALE && targetScale > initialScale) {
                // The target scale is too big and getting bigger.
                scale = MAX_SCALE;
            } else if (targetScale < MIN_SCALE && targetScale < initialScale) {
                // The target scale is too small and getting smaller.
                scale = MIN_SCALE;
            } else {
                // The target scale may be outside our bounds, but at least
                // it's moving in the right direction. This avoids a "jump" if
                // we're at odds with some other service's desired bounds.
                scale = targetScale;
            }

            setScaleAndClearIfNeeded(scale, detector.getFocusX(), detector.getFocusY());
            return /* handled: */ true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return /* continue recognizing: */ (mCurrentState == mPanningScalingState);
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            clear();
        }

        @Override
        public void clear() {
            mInitialScaleFactor = -1;
            mScaling = false;
            mDetectingPassPersistedScale = false;
        }

        @Override
        public String toString() {
            return "PanningScalingState{" + "mInitialScaleFactor=" + mInitialScaleFactor
                    + ", mScaling=" + mScaling
                    + '}';
        }
    }

    /**
     * This class handles motion events when the event dispatcher has
     * determined that the user is performing a single-finger drag of the
     * magnification viewport.
     *
     * Unlike when {@link PanningScalingState panning}, the viewport moves in the opposite direction
     * of the finger, and any part of the screen is reachable without lifting the finger.
     * This makes it the preferable mode for tasks like reading text spanning full screen width.
     */
    final class ViewportDraggingState implements State {

        /**
         * The cached scale for recovering after dragging ends.
         * If the scale >= 1.0, the magnifier needs to recover to scale.
         * Otherwise, the magnifier should be disabled.
         */
        @VisibleForTesting float mScaleToRecoverAfterDraggingEnd = Float.NaN;

        private boolean mLastMoveOutsideMagnifiedRegion;

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags)
                throws GestureException {
            final int action = event.getActionMasked();
            switch (action) {
                case ACTION_POINTER_DOWN: {
                    clearAndTransitToPanningScalingState();
                }
                break;
                case ACTION_MOVE: {
                    if (event.getPointerCount() != 1) {
                        throw new GestureException("Should have one pointer down.");
                    }
                    final float eventX = event.getX();
                    final float eventY = event.getY();
                    if (mFullScreenMagnificationController.magnificationRegionContains(
                            mDisplayId, eventX, eventY)) {
                        mFullScreenMagnificationController.setCenter(mDisplayId, eventX, eventY,
                                /* animate */ mLastMoveOutsideMagnifiedRegion,
                                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
                        mLastMoveOutsideMagnifiedRegion = false;
                    } else {
                        mLastMoveOutsideMagnifiedRegion = true;
                    }
                }
                break;

                case ACTION_UP:
                case ACTION_CANCEL: {
                    // If mScaleToRecoverAfterDraggingEnd >= 1.0, the dragging state is triggered
                    // by zoom in temporary, and the magnifier needs to recover to original scale
                    // after exiting dragging state.
                    // Otherwise, the magnifier should be disabled.
                    if (mScaleToRecoverAfterDraggingEnd >= 1.0f) {
                        zoomToScale(mScaleToRecoverAfterDraggingEnd, event.getX(),
                                event.getY());
                    } else {
                        zoomOff();
                    }
                    clear();
                    mScaleToRecoverAfterDraggingEnd = Float.NaN;
                    transitionTo(mDetectingState);
                }
                    break;

                case ACTION_DOWN:
                case ACTION_POINTER_UP: {
                    throw new GestureException(
                            "Unexpected event type: " + MotionEvent.actionToString(action));
                }
            }
        }

        private boolean isAlwaysOnMagnificationEnabled() {
            return mFullScreenMagnificationController.isAlwaysOnMagnificationEnabled();
        }

        public void prepareForZoomInTemporary(boolean shortcutTriggered) {
            boolean shouldRecoverAfterDraggingEnd;
            if (mFullScreenMagnificationController.isActivated(mDisplayId)) {
                // For b/267210808, if always-on feature is not enabled, we keep the expected
                // behavior. If users tap shortcut and then tap-and-hold to zoom in temporary,
                // the magnifier should be disabled after release.
                // If always-on feature is enabled, in the same scenario the magnifier would
                // zoom to 1.0 and keep activated.
                if (shortcutTriggered) {
                    shouldRecoverAfterDraggingEnd = isAlwaysOnMagnificationEnabled();
                } else {
                    shouldRecoverAfterDraggingEnd = true;
                }
            } else {
                shouldRecoverAfterDraggingEnd = false;
            }

            mScaleToRecoverAfterDraggingEnd = shouldRecoverAfterDraggingEnd
                    ? mFullScreenMagnificationController.getScale(mDisplayId) : Float.NaN;
        }

        private void clearAndTransitToPanningScalingState() {
            final float scaleToRecovery = mScaleToRecoverAfterDraggingEnd;
            clear();
            mScaleToRecoverAfterDraggingEnd = scaleToRecovery;
            transitionTo(mPanningScalingState);
        }

        @Override
        public void clear() {
            mLastMoveOutsideMagnifiedRegion = false;

            mScaleToRecoverAfterDraggingEnd = Float.NaN;
        }

        @Override
        public String toString() {
            return "ViewportDraggingState{"
                    + "mScaleToRecoverAfterDraggingEnd=" + mScaleToRecoverAfterDraggingEnd
                    + ", mLastMoveOutsideMagnifiedRegion=" + mLastMoveOutsideMagnifiedRegion
                    + '}';
        }
    }

    final class DelegatingState implements State {
        /**
         * Time of last {@link MotionEvent#ACTION_DOWN} while in {@link DelegatingState}
         */
        public long mLastDelegatedDownEventTime;

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {

            // Ensures that the state at the end of delegation is consistent with the last delegated
            // UP/DOWN event in queue: still delegating if pointer is down, detecting otherwise
            switch (event.getActionMasked()) {
                case ACTION_UP:
                case ACTION_CANCEL: {
                    transitionTo(mDetectingState);
                }
                    break;

                case ACTION_DOWN: {
                    transitionTo(mDelegatingState);
                    mLastDelegatedDownEventTime = event.getDownTime();
                } break;
            }

            if (getNext() != null) {
                // We cache some events to see if the user wants to trigger magnification.
                // If no magnification is triggered we inject these events with adjusted
                // time and down time to prevent subsequent transformations being confused
                // by stale events. After the cached events, which always have a down, are
                // injected we need to also update the down time of all subsequent non cached
                // events. All delegated events cached and non-cached are delivered here.
                event.setDownTime(mLastDelegatedDownEventTime);
                dispatchTransformedEvent(event, rawEvent, policyFlags);
            }
        }
    }

    /**
     * This class handles motion events when the event dispatch has not yet
     * determined what the user is doing. It watches for various tap events.
     */
    final class DetectingState implements State, Handler.Callback {

        private static final int MESSAGE_ON_TRIPLE_TAP_AND_HOLD = 1;
        private static final int MESSAGE_TRANSITION_TO_DELEGATING_STATE = 2;
        private static final int MESSAGE_TRANSITION_TO_PANNINGSCALING_STATE = 3;

        final int mLongTapMinDelay;
        final int mSwipeMinDistance;
        final int mMultiTapMaxDelay;
        final int mMultiTapMaxDistance;

        private MotionEventInfo mDelayedEventQueue;
        MotionEvent mLastDown;
        private MotionEvent mPreLastDown;
        private MotionEvent mLastUp;
        private MotionEvent mPreLastUp;
        private PointF mSecondPointerDownLocation = new PointF(Float.NaN, Float.NaN);

        private long mLastDetectingDownEventTime;

        @VisibleForTesting boolean mShortcutTriggered;

        @VisibleForTesting Handler mHandler = new Handler(Looper.getMainLooper(), this);

        DetectingState(Context context) {
            mLongTapMinDelay = ViewConfiguration.getLongPressTimeout();
            mMultiTapMaxDelay = ViewConfiguration.getDoubleTapTimeout()
                    + context.getResources().getInteger(
                    R.integer.config_screen_magnification_multi_tap_adjustment);
            mSwipeMinDistance = ViewConfiguration.get(context).getScaledTouchSlop();
            mMultiTapMaxDistance = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        }

        @Override
        public boolean handleMessage(Message message) {
            final int type = message.what;
            switch (type) {
                case MESSAGE_ON_TRIPLE_TAP_AND_HOLD: {
                    MotionEvent down = (MotionEvent) message.obj;
                    transitionToViewportDraggingStateAndClear(down);
                    down.recycle();
                }
                break;
                case MESSAGE_TRANSITION_TO_DELEGATING_STATE: {
                    transitionToDelegatingStateAndClear();
                }
                break;
                case MESSAGE_TRANSITION_TO_PANNINGSCALING_STATE: {
                    transitToPanningScalingStateAndClear();
                }
                break;
                default: {
                    throw new IllegalArgumentException("Unknown message type: " + type);
                }
            }
            return true;
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            cacheDelayedMotionEvent(event, rawEvent, policyFlags);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {

                    mLastDetectingDownEventTime = event.getDownTime();
                    mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);

                    if (!mFullScreenMagnificationController.magnificationRegionContains(
                            mDisplayId, event.getX(), event.getY())) {

                        transitionToDelegatingStateAndClear();

                    } else if (isMultiTapTriggered(2 /* taps */)) {

                        // 3tap and hold
                        afterLongTapTimeoutTransitionToDraggingState(event);

                    } else if (isTapOutOfDistanceSlop()) {

                        transitionToDelegatingStateAndClear();

                    } else if (mDetectTripleTap
                            // If activated, delay an ACTION_DOWN for mMultiTapMaxDelay
                            // to ensure reachability of
                            // STATE_PANNING_SCALING(triggerable with ACTION_POINTER_DOWN)
                            || isActivated()) {

                        afterMultiTapTimeoutTransitionToDelegatingState();

                    } else {

                        // Delegate pending events without delay
                        transitionToDelegatingStateAndClear();
                    }
                }
                break;
                case ACTION_POINTER_DOWN: {
                    if (isActivated() && event.getPointerCount() == 2) {
                        storeSecondPointerDownLocation(event);
                        mHandler.sendEmptyMessageDelayed(MESSAGE_TRANSITION_TO_PANNINGSCALING_STATE,
                                ViewConfiguration.getTapTimeout());
                    } else {
                        transitionToDelegatingStateAndClear();
                    }
                }
                break;
                case ACTION_POINTER_UP: {
                    transitionToDelegatingStateAndClear();
                }
                break;
                case ACTION_MOVE: {
                    if (isFingerDown()
                            && distance(mLastDown, /* move */ event) > mSwipeMinDistance) {

                        // Swipe detected - transition immediately

                        // For convenience, viewport dragging takes precedence
                        // over insta-delegating on 3tap&swipe
                        // (which is a rare combo to be used aside from magnification)
                        if (isMultiTapTriggered(2 /* taps */) && event.getPointerCount() == 1) {
                            transitionToViewportDraggingStateAndClear(event);
                        } else if (isActivated() && event.getPointerCount() == 2) {
                            //Primary pointer is swiping, so transit to PanningScalingState
                            transitToPanningScalingStateAndClear();
                        } else {
                            transitionToDelegatingStateAndClear();
                        }
                    } else if (isActivated() && secondPointerDownValid()
                            && distanceClosestPointerToPoint(
                            mSecondPointerDownLocation, /* move */ event) > mSwipeMinDistance) {
                        //Second pointer is swiping, so transit to PanningScalingState
                        transitToPanningScalingStateAndClear();
                    }
                }
                break;
                case ACTION_UP: {

                    mHandler.removeMessages(MESSAGE_ON_TRIPLE_TAP_AND_HOLD);

                    if (!mFullScreenMagnificationController.magnificationRegionContains(
                            mDisplayId, event.getX(), event.getY())) {

                        transitionToDelegatingStateAndClear();

                    } else if (isMultiTapTriggered(3 /* taps */)) {

                        onTripleTap(/* up */ event);

                    } else if (
                            // Possible to be false on: 3tap&drag -> scale -> PTR_UP -> UP
                            isFingerDown()
                            //TODO long tap should never happen here
                            && ((timeBetween(mLastDown, mLastUp) >= mLongTapMinDelay)
                                    || (distance(mLastDown, mLastUp) >= mSwipeMinDistance))) {

                        transitionToDelegatingStateAndClear();

                    }
                }
                break;
            }
        }

        private void storeSecondPointerDownLocation(MotionEvent event) {
            final int index = event.getActionIndex();
            mSecondPointerDownLocation.set(event.getX(index), event.getY(index));
        }

        private boolean secondPointerDownValid() {
            return !(Float.isNaN(mSecondPointerDownLocation.x) && Float.isNaN(
                    mSecondPointerDownLocation.y));
        }

        private void transitToPanningScalingStateAndClear() {
            transitionTo(mPanningScalingState);
            clear();
        }

        public boolean isMultiTapTriggered(int numTaps) {

            // Shortcut acts as the 2 initial taps
            if (mShortcutTriggered) return tapCount() + 2 >= numTaps;

            final boolean multitapTriggered = mDetectTripleTap
                    && tapCount() >= numTaps
                    && isMultiTap(mPreLastDown, mLastDown)
                    && isMultiTap(mPreLastUp, mLastUp);

            // Only log the triple tap event, use numTaps to filter.
            if (multitapTriggered && numTaps > 2) {
                final boolean enabled = isActivated();
                logMagnificationTripleTap(enabled);
            }
            return multitapTriggered;
        }

        private boolean isMultiTap(MotionEvent first, MotionEvent second) {
            return GestureUtils.isMultiTap(first, second, mMultiTapMaxDelay, mMultiTapMaxDistance);
        }

        public boolean isFingerDown() {
            return mLastDown != null;
        }

        private long timeBetween(@Nullable MotionEvent a, @Nullable MotionEvent b) {
            if (a == null && b == null) return 0;
            return abs(timeOf(a) - timeOf(b));
        }

        /**
         * Nullsafe {@link MotionEvent#getEventTime} that interprets null event as something that
         * has happened long enough ago to be gone from the event queue.
         * Thus the time for a null event is a small number, that is below any other non-null
         * event's time.
         *
         * @return {@link MotionEvent#getEventTime}, or {@link Long#MIN_VALUE} if the event is null
         */
        private long timeOf(@Nullable MotionEvent event) {
            return event != null ? event.getEventTime() : Long.MIN_VALUE;
        }

        public int tapCount() {
            return MotionEventInfo.countOf(mDelayedEventQueue, ACTION_UP);
        }

        /** -> {@link DelegatingState} */
        public void afterMultiTapTimeoutTransitionToDelegatingState() {
            mHandler.sendEmptyMessageDelayed(
                    MESSAGE_TRANSITION_TO_DELEGATING_STATE,
                    mMultiTapMaxDelay);
        }

        /** -> {@link ViewportDraggingState} */
        public void afterLongTapTimeoutTransitionToDraggingState(MotionEvent event) {
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(MESSAGE_ON_TRIPLE_TAP_AND_HOLD,
                            MotionEvent.obtain(event)),
                    ViewConfiguration.getLongPressTimeout());
        }

        @Override
        public void clear() {
            setShortcutTriggered(false);
            removePendingDelayedMessages();
            clearDelayedMotionEvents();
            mSecondPointerDownLocation.set(Float.NaN, Float.NaN);
        }

        private void removePendingDelayedMessages() {
            mHandler.removeMessages(MESSAGE_ON_TRIPLE_TAP_AND_HOLD);
            mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);
            mHandler.removeMessages(MESSAGE_TRANSITION_TO_PANNINGSCALING_STATE);
        }

        private void cacheDelayedMotionEvent(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            if (event.getActionMasked() == ACTION_DOWN) {
                mPreLastDown = mLastDown;
                mLastDown = MotionEvent.obtain(event);
            } else if (event.getActionMasked() == ACTION_UP) {
                mPreLastUp = mLastUp;
                mLastUp = MotionEvent.obtain(event);
            }

            MotionEventInfo info = MotionEventInfo.obtain(event, rawEvent,
                    policyFlags);
            if (mDelayedEventQueue == null) {
                mDelayedEventQueue = info;
            } else {
                MotionEventInfo tail = mDelayedEventQueue;
                while (tail.mNext != null) {
                    tail = tail.mNext;
                }
                tail.mNext = info;
            }
        }

        private void sendDelayedMotionEvents() {
            if (mDelayedEventQueue == null) {
                return;
            }

            // Adjust down time to prevent subsequent modules being misleading, and also limit
            // the maximum offset to mMultiTapMaxDelay to prevent the down time of 2nd tap is
            // in the future when multi-tap happens.
            final long offset = Math.min(
                    SystemClock.uptimeMillis() - mLastDetectingDownEventTime, mMultiTapMaxDelay);

            do {
                MotionEventInfo info = mDelayedEventQueue;
                mDelayedEventQueue = info.mNext;

                info.event.setDownTime(info.event.getDownTime() + offset);
                handleEventWith(mDelegatingState, info.event, info.rawEvent, info.policyFlags);

                info.recycle();
            } while (mDelayedEventQueue != null);
        }

        private void clearDelayedMotionEvents() {
            while (mDelayedEventQueue != null) {
                MotionEventInfo info = mDelayedEventQueue;
                mDelayedEventQueue = info.mNext;
                info.recycle();
            }
            mPreLastDown = null;
            mPreLastUp = null;
            mLastDown = null;
            mLastUp = null;
        }

        void transitionToDelegatingStateAndClear() {
            transitionTo(mDelegatingState);
            sendDelayedMotionEvents();
            removePendingDelayedMessages();
            mSecondPointerDownLocation.set(Float.NaN, Float.NaN);
        }

        /**
         * This method could be triggered by both 2 cases.
         *      1. direct three tap gesture
         *      2. one tap while shortcut triggered (it counts as two taps).
         */
        private void onTripleTap(MotionEvent up) {
            if (DEBUG_DETECTING) {
                Slog.i(mLogTag, "onTripleTap(); delayed: "
                        + MotionEventInfo.toString(mDelayedEventQueue));
            }

            // We put mShortcutTriggered into conditions.
            // The reason is when the shortcut is triggered,
            //   the magnifier is activated and keeps in scale 1.0,
            //   and in this case, we still want to zoom on the magnifier.
            if (!isActivated() || mShortcutTriggered) {
                mPromptController.showNotificationIfNeeded();
                zoomOn(up.getX(), up.getY());
            } else {
                zoomOff();
            }

            clear();
        }

        private boolean isActivated() {
            return mFullScreenMagnificationController.isActivated(mDisplayId);
        }

        void transitionToViewportDraggingStateAndClear(MotionEvent down) {

            if (DEBUG_DETECTING) Slog.i(mLogTag, "onTripleTapAndHold()");
            final boolean shortcutTriggered = mShortcutTriggered;
            clear();

            // Triple tap and hold also belongs to triple tap event.
            final boolean enabled = !isActivated();
            logMagnificationTripleTap(enabled);

            mViewportDraggingState.prepareForZoomInTemporary(shortcutTriggered);

            zoomInTemporary(down.getX(), down.getY());

            transitionTo(mViewportDraggingState);
        }

        @Override
        public String toString() {
            return "DetectingState{"
                    + "tapCount()=" + tapCount()
                    + ", mShortcutTriggered=" + mShortcutTriggered
                    + ", mDelayedEventQueue=" + MotionEventInfo.toString(mDelayedEventQueue)
                    + '}';
        }

        void toggleShortcutTriggered() {
            setShortcutTriggered(!mShortcutTriggered);
        }

        void setShortcutTriggered(boolean state) {
            if (mShortcutTriggered == state) {
                return;
            }
            if (DEBUG_DETECTING) Slog.i(mLogTag, "setShortcutTriggered(" + state + ")");

            mShortcutTriggered = state;
        }

        private boolean isShortcutTriggered() {
            return mShortcutTriggered;
        }

        /**
         * Detects if last action down is out of distance slop between with previous
         * one, when triple tap is enabled.
         *
         * @return true if tap is out of distance slop
         */
        boolean isTapOutOfDistanceSlop() {
            if (!mDetectTripleTap) return false;
            if (mPreLastDown == null || mLastDown == null) {
                return false;
            }
            final boolean outOfDistanceSlop =
                    GestureUtils.distance(mPreLastDown, mLastDown) > mMultiTapMaxDistance;
            if (tapCount() > 0) {
                return outOfDistanceSlop;
            }
            // There's no tap in the queue here. We still need to check if this is the case that
            // user tap screen quickly and out of distance slop.
            if (outOfDistanceSlop
                    && !GestureUtils.isTimedOut(mPreLastDown, mLastDown, mMultiTapMaxDelay)) {
                return true;
            }
            return false;
        }
    }

    private void zoomInTemporary(float centerX, float centerY) {
        final float currentScale = mFullScreenMagnificationController.getScale(mDisplayId);
        final float persistedScale = MathUtils.constrain(
                mFullScreenMagnificationController.getPersistedScale(mDisplayId),
                MIN_SCALE, MAX_SCALE);

        final boolean isActivated = mFullScreenMagnificationController.isActivated(mDisplayId);
        final float scale = isActivated ? (currentScale + 1.0f) : persistedScale;
        zoomToScale(scale, centerX, centerY);
    }

    private void zoomOn(float centerX, float centerY) {
        if (DEBUG_DETECTING) Slog.i(mLogTag, "zoomOn(" + centerX + ", " + centerY + ")");

        final float scale = MathUtils.constrain(
                mFullScreenMagnificationController.getPersistedScale(mDisplayId),
                MIN_SCALE, MAX_SCALE);
        zoomToScale(scale, centerX, centerY);
    }

    private void zoomToScale(float scale, float centerX, float centerY) {
        scale = MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);
        mFullScreenMagnificationController.setScaleAndCenter(mDisplayId,
                scale, centerX, centerY,
                /* animate */ true,
                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
    }

    private void zoomOff() {
        if (DEBUG_DETECTING) Slog.i(mLogTag, "zoomOff()");
        mFullScreenMagnificationController.reset(mDisplayId, /* animate */ true);
    }

    private static MotionEvent recycleAndNullify(@Nullable MotionEvent event) {
        if (event != null) {
            event.recycle();
        }
        return null;
    }

    @Override
    public String toString() {
        return "MagnificationGesture{"
                + "mDetectingState=" + mDetectingState
                + ", mDelegatingState=" + mDelegatingState
                + ", mMagnifiedInteractionState=" + mPanningScalingState
                + ", mViewportDraggingState=" + mViewportDraggingState
                + ", mDetectTripleTap=" + mDetectTripleTap
                + ", mDetectShortcutTrigger=" + mDetectShortcutTrigger
                + ", mCurrentState=" + State.nameOf(mCurrentState)
                + ", mPreviousState=" + State.nameOf(mPreviousState)
                + ", mMagnificationController=" + mFullScreenMagnificationController
                + ", mDisplayId=" + mDisplayId
                + '}';
    }

    private static final class MotionEventInfo {

        private static final int MAX_POOL_SIZE = 10;
        private static final Object sLock = new Object();
        private static MotionEventInfo sPool;
        private static int sPoolSize;

        private MotionEventInfo mNext;
        private boolean mInPool;

        public MotionEvent event;
        public MotionEvent rawEvent;
        public int policyFlags;

        public static MotionEventInfo obtain(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            synchronized (sLock) {
                MotionEventInfo info = obtainInternal();
                info.initialize(event, rawEvent, policyFlags);
                return info;
            }
        }

        @NonNull
        private static MotionEventInfo obtainInternal() {
            MotionEventInfo info;
            if (sPoolSize > 0) {
                sPoolSize--;
                info = sPool;
                sPool = info.mNext;
                info.mNext = null;
                info.mInPool = false;
            } else {
                info = new MotionEventInfo();
            }
            return info;
        }

        private void initialize(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            this.event = MotionEvent.obtain(event);
            this.rawEvent = MotionEvent.obtain(rawEvent);
            this.policyFlags = policyFlags;
        }

        public void recycle() {
            synchronized (sLock) {
                if (mInPool) {
                    throw new IllegalStateException("Already recycled.");
                }
                clear();
                if (sPoolSize < MAX_POOL_SIZE) {
                    sPoolSize++;
                    mNext = sPool;
                    sPool = this;
                    mInPool = true;
                }
            }
        }

        private void clear() {
            event = recycleAndNullify(event);
            rawEvent = recycleAndNullify(rawEvent);
            policyFlags = 0;
        }

        static int countOf(MotionEventInfo info, int eventType) {
            if (info == null) return 0;
            return (info.event.getAction() == eventType ? 1 : 0)
                    + countOf(info.mNext, eventType);
        }

        public static String toString(MotionEventInfo info) {
            return info == null
                    ? ""
                    : MotionEvent.actionToString(info.event.getAction()).replace("ACTION_", "")
                            + " " + MotionEventInfo.toString(info.mNext);
        }
    }

    /**
     * BroadcastReceiver used to cancel the magnification shortcut when the screen turns off
     */
    private static class ScreenStateReceiver extends BroadcastReceiver {
        private final Context mContext;
        private final FullScreenMagnificationGestureHandler mGestureHandler;

        ScreenStateReceiver(Context context,
                FullScreenMagnificationGestureHandler gestureHandler) {
            mContext = context;
            mGestureHandler = gestureHandler;
        }

        public void register() {
            mContext.registerReceiver(this, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }

        public void unregister() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mGestureHandler.mDetectingState.setShortcutTriggered(false);
        }
    }

    /**
     * Indicates an error with a gesture handler or state.
     */
    private static class GestureException extends Exception {
        GestureException(String message) {
            super(message);
        }
    }
}

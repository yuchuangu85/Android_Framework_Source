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

package com.android.server.wm;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.Task.TAG_VISIBILITY;

import android.annotation.Nullable;
import android.util.Slog;

/** Helper class to ensure activities are in the right visible state for a container. */
class EnsureActivitiesVisibleHelper {
    private final Task mTask;
    private ActivityRecord mTop;
    private ActivityRecord mStarting;
    private boolean mAboveTop;
    private boolean mContainerShouldBeVisible;
    private boolean mBehindFullscreenActivity;
    private int mConfigChanges;
    private boolean mPreserveWindows;
    private boolean mNotifyClients;

    EnsureActivitiesVisibleHelper(Task container) {
        mTask = container;
    }

    /**
     * Update all attributes except {@link mTask} to use in subsequent calculations.
     *
     * @param starting The activity that is being started
     * @param configChanges Parts of the configuration that changed for this activity for evaluating
     *                      if the screen should be frozen.
     * @param preserveWindows Flag indicating whether windows should be preserved when updating.
     * @param notifyClients Flag indicating whether the configuration and visibility changes shoulc
     *                      be sent to the clients.
     */
    void reset(ActivityRecord starting, int configChanges, boolean preserveWindows,
            boolean notifyClients) {
        mStarting = starting;
        mTop = mTask.topRunningActivity();
        // If the top activity is not fullscreen, then we need to make sure any activities under it
        // are now visible.
        mAboveTop = mTop != null;
        mContainerShouldBeVisible = mTask.shouldBeVisible(mStarting);
        mBehindFullscreenActivity = !mContainerShouldBeVisible;
        mConfigChanges = configChanges;
        mPreserveWindows = preserveWindows;
        mNotifyClients = notifyClients;
    }

    /**
     * Update and commit visibility with an option to also update the configuration of visible
     * activities.
     * @see Task#ensureActivitiesVisible(ActivityRecord, int, boolean)
     * @see RootWindowContainer#ensureActivitiesVisible(ActivityRecord, int, boolean)
     * @param starting The top most activity in the task.
     *                 The activity is either starting or resuming.
     *                 Caller should ensure starting activity is visible.
     *
     * @param configChanges Parts of the configuration that changed for this activity for evaluating
     *                      if the screen should be frozen.
     * @param preserveWindows Flag indicating whether windows should be preserved when updating.
     * @param notifyClients Flag indicating whether the configuration and visibility changes shoulc
     *                      be sent to the clients.
     */
    void process(@Nullable ActivityRecord starting, int configChanges, boolean preserveWindows,
            boolean notifyClients) {
        reset(starting, configChanges, preserveWindows, notifyClients);

        if (DEBUG_VISIBILITY) {
            Slog.v(TAG_VISIBILITY, "ensureActivitiesVisible behind " + mTop
                    + " configChanges=0x" + Integer.toHexString(configChanges));
        }
        if (mTop != null) {
            mTask.checkTranslucentActivityWaiting(mTop);
        }

        // We should not resume activities that being launched behind because these
        // activities are actually behind other fullscreen activities, but still required
        // to be visible (such as performing Recents animation).
        final boolean resumeTopActivity = mTop != null && !mTop.mLaunchTaskBehind
                && mTask.isTopActivityFocusable()
                && (starting == null || !starting.isDescendantOf(mTask));

        mTask.forAllActivities(a -> {
            setActivityVisibilityState(a, starting, resumeTopActivity);
        });
        if (mTask.mAtmService.getTransitionController().getTransitionPlayer() != null) {
            mTask.getDisplayContent().mWallpaperController.adjustWallpaperWindows();
        }
    }

    private void setActivityVisibilityState(ActivityRecord r, ActivityRecord starting,
            final boolean resumeTopActivity) {
        final boolean isTop = r == mTop;
        if (mAboveTop && !isTop) {
            return;
        }
        mAboveTop = false;

        r.updateVisibilityIgnoringKeyguard(mBehindFullscreenActivity);
        final boolean reallyVisible = r.shouldBeVisibleUnchecked();

        // Check whether activity should be visible without Keyguard influence
        if (r.visibleIgnoringKeyguard) {
            if (r.occludesParent()) {
                // At this point, nothing else needs to be shown in this task.
                if (DEBUG_VISIBILITY) {
                    Slog.v(TAG_VISIBILITY, "Fullscreen: at " + r
                            + " containerVisible=" + mContainerShouldBeVisible
                            + " behindFullscreen=" + mBehindFullscreenActivity);
                }
                mBehindFullscreenActivity = true;
            } else {
                mBehindFullscreenActivity = false;
            }
        }

        if (reallyVisible) {
            if (r.finishing) {
                return;
            }
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Make visible? " + r
                        + " finishing=" + r.finishing + " state=" + r.getState());
            }
            // First: if this is not the current activity being started, make
            // sure it matches the current configuration.
            if (r != mStarting && mNotifyClients) {
                r.ensureActivityConfiguration(0 /* globalChanges */, mPreserveWindows,
                        true /* ignoreVisibility */);
            }

            if (!r.attachedToProcess()) {
                makeVisibleAndRestartIfNeeded(mStarting, mConfigChanges, isTop,
                        resumeTopActivity && isTop, r);
            } else if (r.mVisibleRequested) {
                // If this activity is already visible, then there is nothing to do here.
                if (DEBUG_VISIBILITY) {
                    Slog.v(TAG_VISIBILITY, "Skipping: already visible at " + r);
                }

                if (r.mClientVisibilityDeferred && mNotifyClients) {
                    r.makeActiveIfNeeded(r.mClientVisibilityDeferred ? null : starting);
                    r.mClientVisibilityDeferred = false;
                }

                r.handleAlreadyVisible();
                if (mNotifyClients) {
                    r.makeActiveIfNeeded(mStarting);
                }
            } else {
                r.makeVisibleIfNeeded(mStarting, mNotifyClients);
            }
            // Aggregate current change flags.
            mConfigChanges |= r.configChangeFlags;
        } else {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Make invisible? " + r
                        + " finishing=" + r.finishing + " state=" + r.getState()
                        + " containerShouldBeVisible=" + mContainerShouldBeVisible
                        + " behindFullscreenActivity=" + mBehindFullscreenActivity
                        + " mLaunchTaskBehind=" + r.mLaunchTaskBehind);
            }
            r.makeInvisible();
        }

        if (!mBehindFullscreenActivity && mTask.isActivityTypeHome() && r.isRootOfTask()) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Home task: at " + mTask
                        + " containerShouldBeVisible=" + mContainerShouldBeVisible
                        + " behindFullscreenActivity=" + mBehindFullscreenActivity);
            }
            // No other task in the root home task should be visible behind the home activity.
            // Home activities is usually a translucent activity with the wallpaper behind
            // them. However, when they don't have the wallpaper behind them, we want to
            // show activities in the next application root task behind them vs. another
            // task in the root home task like recents.
            mBehindFullscreenActivity = true;
        }
    }

    private void makeVisibleAndRestartIfNeeded(ActivityRecord starting, int configChanges,
            boolean isTop, boolean andResume, ActivityRecord r) {
        // We need to make sure the app is running if it's the top, or it is just made visible from
        // invisible. If the app is already visible, it must have died while it was visible. In this
        // case, we'll show the dead window but will not restart the app. Otherwise we could end up
        // thrashing.
        if (!isTop && r.mVisibleRequested) {
            return;
        }

        // This activity needs to be visible, but isn't even running...
        // get it started and resume if no other root task in this root task is resumed.
        if (DEBUG_VISIBILITY) {
            Slog.v(TAG_VISIBILITY, "Start and freeze screen for " + r);
        }
        if (r != starting) {
            r.startFreezingScreenLocked(configChanges);
        }
        if (!r.mVisibleRequested || r.mLaunchTaskBehind) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Starting and making visible: " + r);
            }
            r.setVisibility(true);
        }
        if (r != starting) {
            mTask.mTaskSupervisor.startSpecificActivity(r, andResume, true /* checkConfig */);
        }
    }
}

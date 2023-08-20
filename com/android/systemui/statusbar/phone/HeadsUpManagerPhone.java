/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Region;
import android.os.Handler;
import android.util.Pools;

import androidx.collection.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.OnReorderingAllowedListener;
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.HeadsUpManagerLogger;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Stack;

/**
 * A implementation of HeadsUpManager for phone and car.
 */
public class HeadsUpManagerPhone extends HeadsUpManager implements Dumpable,
        OnHeadsUpChangedListener {
    private static final String TAG = "HeadsUpManagerPhone";

    @VisibleForTesting
    final int mExtensionTime;
    private final KeyguardBypassController mBypassController;
    private final GroupMembershipManager mGroupMembershipManager;
    private final List<OnHeadsUpPhoneListenerChange> mHeadsUpPhoneListeners = new ArrayList<>();
    private final VisualStabilityProvider mVisualStabilityProvider;
    private boolean mReleaseOnExpandFinish;

    private boolean mTrackingHeadsUp;
    private final HashSet<String> mSwipedOutKeys = new HashSet<>();
    private final HashSet<NotificationEntry> mEntriesToRemoveAfterExpand = new HashSet<>();
    private final ArraySet<NotificationEntry> mEntriesToRemoveWhenReorderingAllowed
            = new ArraySet<>();
    private boolean mIsExpanded;
    private boolean mHeadsUpGoingAway;
    private int mStatusBarState;
    private AnimationStateHandler mAnimationStateHandler;
    private int mHeadsUpInset;

    // Used for determining the region for touch interaction
    private final Region mTouchableRegion = new Region();

    private final Pools.Pool<HeadsUpEntryPhone> mEntryPool = new Pools.Pool<HeadsUpEntryPhone>() {
        private Stack<HeadsUpEntryPhone> mPoolObjects = new Stack<>();

        @Override
        public HeadsUpEntryPhone acquire() {
            if (!mPoolObjects.isEmpty()) {
                return mPoolObjects.pop();
            }
            return new HeadsUpEntryPhone();
        }

        @Override
        public boolean release(@NonNull HeadsUpEntryPhone instance) {
            mPoolObjects.push(instance);
            return true;
        }
    };

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Constructor:

    public HeadsUpManagerPhone(@NonNull final Context context,
            HeadsUpManagerLogger logger,
            StatusBarStateController statusBarStateController,
            KeyguardBypassController bypassController,
            GroupMembershipManager groupMembershipManager,
            VisualStabilityProvider visualStabilityProvider,
            ConfigurationController configurationController,
            @Main Handler handler,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            UiEventLogger uiEventLogger,
            ShadeExpansionStateManager shadeExpansionStateManager) {
        super(context, logger, handler, accessibilityManagerWrapper, uiEventLogger);
        Resources resources = mContext.getResources();
        mExtensionTime = resources.getInteger(R.integer.ambient_notification_extension_time);
        statusBarStateController.addCallback(mStatusBarStateListener);
        mBypassController = bypassController;
        mGroupMembershipManager = groupMembershipManager;
        mVisualStabilityProvider = visualStabilityProvider;

        updateResources();
        configurationController.addCallback(new ConfigurationController.ConfigurationListener() {
            @Override
            public void onDensityOrFontScaleChanged() {
                updateResources();
            }

            @Override
            public void onThemeChanged() {
                updateResources();
            }
        });

        shadeExpansionStateManager.addFullExpansionListener(this::onShadeExpansionFullyChanged);
    }

    public void setAnimationStateHandler(AnimationStateHandler handler) {
        mAnimationStateHandler = handler;
    }

    private void updateResources() {
        Resources resources = mContext.getResources();
        mHeadsUpInset = SystemBarUtils.getStatusBarHeight(mContext)
                + resources.getDimensionPixelSize(R.dimen.heads_up_status_bar_padding);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Public methods:

    /**
     * Add a listener to receive callbacks onHeadsUpGoingAway
     */
    void addHeadsUpPhoneListener(OnHeadsUpPhoneListenerChange listener) {
        mHeadsUpPhoneListeners.add(listener);
    }

    /**
     * Gets the touchable region needed for heads up notifications. Returns null if no touchable
     * region is required (ie: no heads up notification currently exists).
     */
    @Nullable Region getTouchableRegion() {
        NotificationEntry topEntry = getTopEntry();

        // This call could be made in an inconsistent state while the pinnedMode hasn't been
        // updated yet, but callbacks leading out of the headsUp manager, querying it. Let's
        // therefore also check if the topEntry is null.
        if (!hasPinnedHeadsUp() || topEntry == null) {
            return null;
        } else {
            if (topEntry.isChildInGroup()) {
                final NotificationEntry groupSummary =
                        mGroupMembershipManager.getGroupSummary(topEntry);
                if (groupSummary != null) {
                    topEntry = groupSummary;
                }
            }
            ExpandableNotificationRow topRow = topEntry.getRow();
            int[] tmpArray = new int[2];
            topRow.getLocationOnScreen(tmpArray);
            int minX = tmpArray[0];
            int maxX = tmpArray[0] + topRow.getWidth();
            int height = topRow.getIntrinsicHeight();
            final boolean stretchToTop = tmpArray[1] <= mHeadsUpInset;
            mTouchableRegion.set(minX, stretchToTop ? 0 : tmpArray[1], maxX, tmpArray[1] + height);
            return mTouchableRegion;
        }
    }

    /**
     * Decides whether a click is invalid for a notification, i.e it has not been shown long enough
     * that a user might have consciously clicked on it.
     *
     * @param key the key of the touched notification
     * @return whether the touch is invalid and should be discarded
     */
    boolean shouldSwallowClick(@NonNull String key) {
        HeadsUpManager.HeadsUpEntry entry = getHeadsUpEntry(key);
        return entry != null && mClock.currentTimeMillis() < entry.mPostTime;
    }

    public void onExpandingFinished() {
        if (mReleaseOnExpandFinish) {
            releaseAllImmediately();
            mReleaseOnExpandFinish = false;
        } else {
            for (NotificationEntry entry : mEntriesToRemoveAfterExpand) {
                if (isAlerting(entry.getKey())) {
                    // Maybe the heads-up was removed already
                    removeAlertEntry(entry.getKey());
                }
            }
        }
        mEntriesToRemoveAfterExpand.clear();
    }

    /**
     * Sets the tracking-heads-up flag. If the flag is true, HeadsUpManager doesn't remove the entry
     * from the list even after a Heads Up Notification is gone.
     */
    public void setTrackingHeadsUp(boolean trackingHeadsUp) {
        mTrackingHeadsUp = trackingHeadsUp;
    }

    private void onShadeExpansionFullyChanged(Boolean isExpanded) {
        if (isExpanded != mIsExpanded) {
            mIsExpanded = isExpanded;
            if (isExpanded) {
                mHeadsUpGoingAway = false;
            }
        }
    }

    /**
     * Set that we are exiting the headsUp pinned mode, but some notifications might still be
     * animating out. This is used to keep the touchable regions in a reasonable state.
     */
    void setHeadsUpGoingAway(boolean headsUpGoingAway) {
        if (headsUpGoingAway != mHeadsUpGoingAway) {
            mHeadsUpGoingAway = headsUpGoingAway;
            for (OnHeadsUpPhoneListenerChange listener : mHeadsUpPhoneListeners) {
                listener.onHeadsUpGoingAwayStateChanged(headsUpGoingAway);
            }
        }
    }

    boolean isHeadsUpGoingAway() {
        return mHeadsUpGoingAway;
    }

    /**
     * Notifies that a remote input textbox in notification gets active or inactive.
     *
     * @param entry             The entry of the target notification.
     * @param remoteInputActive True to notify active, False to notify inactive.
     */
    public void setRemoteInputActive(
            @NonNull NotificationEntry entry, boolean remoteInputActive) {
        HeadsUpEntryPhone headsUpEntry = getHeadsUpEntryPhone(entry.getKey());
        if (headsUpEntry != null && headsUpEntry.remoteInputActive != remoteInputActive) {
            headsUpEntry.remoteInputActive = remoteInputActive;
            if (remoteInputActive) {
                headsUpEntry.removeAutoRemovalCallbacks();
            } else {
                headsUpEntry.updateEntry(false /* updatePostTime */);
            }
        }
    }

    /**
     * Sets whether an entry's guts are exposed and therefore it should stick in the heads up
     * area if it's pinned until it's hidden again.
     */
    public void setGutsShown(@NonNull NotificationEntry entry, boolean gutsShown) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(entry.getKey());
        if (!(headsUpEntry instanceof HeadsUpEntryPhone)) return;
        HeadsUpEntryPhone headsUpEntryPhone = (HeadsUpEntryPhone)headsUpEntry;
        if (entry.isRowPinned() || !gutsShown) {
            headsUpEntryPhone.setGutsShownPinned(gutsShown);
        }
    }

    /**
     * Extends the lifetime of the currently showing pulsing notification so that the pulse lasts
     * longer.
     */
    public void extendHeadsUp() {
        HeadsUpEntryPhone topEntry = getTopHeadsUpEntryPhone();
        if (topEntry == null) {
            return;
        }
        topEntry.extendPulse();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpManager public methods overrides and overloads:

    @Override
    public boolean isTrackingHeadsUp() {
        return mTrackingHeadsUp;
    }

    @Override
    public void snooze() {
        super.snooze();
        mReleaseOnExpandFinish = true;
    }

    public void addSwipedOutNotification(@NonNull String key) {
        mSwipedOutKeys.add(key);
    }

    public boolean removeNotification(@NonNull String key, boolean releaseImmediately,
            boolean animate) {
        if (animate) {
            return removeNotification(key, releaseImmediately);
        } else {
            mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(false);
            boolean removed = removeNotification(key, releaseImmediately);
            mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(true);
            return removed;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Dumpable overrides:

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("HeadsUpManagerPhone state:");
        dumpInternal(pw, args);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  OnReorderingAllowedListener:

    private final OnReorderingAllowedListener mOnReorderingAllowedListener = () -> {
        mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(false);
        for (NotificationEntry entry : mEntriesToRemoveWhenReorderingAllowed) {
            if (isAlerting(entry.getKey())) {
                // Maybe the heads-up was removed already
                removeAlertEntry(entry.getKey());
            }
        }
        mEntriesToRemoveWhenReorderingAllowed.clear();
        mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(true);
    };

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpManager utility (protected) methods overrides:

    @Override
    protected HeadsUpEntry createAlertEntry() {
        return mEntryPool.acquire();
    }

    @Override
    protected void onAlertEntryRemoved(AlertEntry alertEntry) {
        super.onAlertEntryRemoved(alertEntry);
        mEntryPool.release((HeadsUpEntryPhone) alertEntry);
    }

    @Override
    protected boolean shouldHeadsUpBecomePinned(NotificationEntry entry) {
        boolean pin = mStatusBarState == StatusBarState.SHADE && !mIsExpanded;
        if (mBypassController.getBypassEnabled()) {
            pin |= mStatusBarState == StatusBarState.KEYGUARD;
        }
        return pin || super.shouldHeadsUpBecomePinned(entry);
    }

    @Override
    protected void dumpInternal(PrintWriter pw, String[] args) {
        super.dumpInternal(pw, args);
        pw.print("  mBarState=");
        pw.println(mStatusBarState);
        pw.print("  mTouchableRegion=");
        pw.println(mTouchableRegion);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Private utility methods:

    @Nullable
    private HeadsUpEntryPhone getHeadsUpEntryPhone(@NonNull String key) {
        return (HeadsUpEntryPhone) mAlertEntries.get(key);
    }

    @Nullable
    private HeadsUpEntryPhone getTopHeadsUpEntryPhone() {
        return (HeadsUpEntryPhone) getTopHeadsUpEntry();
    }

    @Override
    public boolean canRemoveImmediately(@NonNull String key) {
        if (mSwipedOutKeys.contains(key)) {
            // We always instantly dismiss views being manually swiped out.
            mSwipedOutKeys.remove(key);
            return true;
        }

        HeadsUpEntryPhone headsUpEntry = getHeadsUpEntryPhone(key);
        HeadsUpEntryPhone topEntry = getTopHeadsUpEntryPhone();

        return headsUpEntry == null || headsUpEntry != topEntry || super.canRemoveImmediately(key);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpEntryPhone:

    protected class HeadsUpEntryPhone extends HeadsUpManager.HeadsUpEntry {

        private boolean mGutsShownPinned;

        /**
         * If the time this entry has been on was extended
         */
        private boolean extended;


        @Override
        public boolean isSticky() {
            return super.isSticky() || mGutsShownPinned;
        }

        public void setEntry(@NonNull final NotificationEntry entry) {
            Runnable removeHeadsUpRunnable = () -> {
                if (!mVisualStabilityProvider.isReorderingAllowed()
                        // We don't want to allow reordering while pulsing, but headsup need to
                        // time out anyway
                        && !entry.showingPulsing()) {
                    mEntriesToRemoveWhenReorderingAllowed.add(entry);
                    mVisualStabilityProvider.addTemporaryReorderingAllowedListener(
                            mOnReorderingAllowedListener);
                } else if (mTrackingHeadsUp) {
                    mEntriesToRemoveAfterExpand.add(entry);
                } else {
                    removeAlertEntry(entry.getKey());
                }
            };

            setEntry(entry, removeHeadsUpRunnable);
        }

        @Override
        public void updateEntry(boolean updatePostTime) {
            super.updateEntry(updatePostTime);

            if (mEntriesToRemoveAfterExpand.contains(mEntry)) {
                mEntriesToRemoveAfterExpand.remove(mEntry);
            }
            if (mEntriesToRemoveWhenReorderingAllowed.contains(mEntry)) {
                mEntriesToRemoveWhenReorderingAllowed.remove(mEntry);
            }
        }

        @Override
        public void setExpanded(boolean expanded) {
            if (this.expanded == expanded) {
                return;
            }

            this.expanded = expanded;
            if (expanded) {
                removeAutoRemovalCallbacks();
            } else {
                updateEntry(false /* updatePostTime */);
            }
        }

        public void setGutsShownPinned(boolean gutsShownPinned) {
            if (mGutsShownPinned == gutsShownPinned) {
                return;
            }

            mGutsShownPinned = gutsShownPinned;
            if (gutsShownPinned) {
                removeAutoRemovalCallbacks();
            } else {
                updateEntry(false /* updatePostTime */);
            }
        }

        @Override
        public void reset() {
            super.reset();
            mGutsShownPinned = false;
            extended = false;
        }

        private void extendPulse() {
            if (!extended) {
                extended = true;
                updateEntry(false);
            }
        }

        @Override
        protected long calculateFinishTime() {
            return super.calculateFinishTime() + (extended ? mExtensionTime : 0);
        }
    }

    public interface AnimationStateHandler {
        void setHeadsUpGoingAwayAnimationsAllowed(boolean allowed);
    }

    /**
     * Listener to register for HeadsUpNotification Phone changes.
     */
    public interface OnHeadsUpPhoneListenerChange {
        /**
         * Called when a heads up notification is 'going away' or no longer 'going away'.
         * See {@link HeadsUpManagerPhone#setHeadsUpGoingAway}.
         */
        void onHeadsUpGoingAwayStateChanged(boolean headsUpGoingAway);
    }

    private final StateListener mStatusBarStateListener = new StateListener() {
        @Override
        public void onStateChanged(int newState) {
            boolean wasKeyguard = mStatusBarState == StatusBarState.KEYGUARD;
            boolean isKeyguard = newState == StatusBarState.KEYGUARD;
            mStatusBarState = newState;
            if (wasKeyguard && !isKeyguard && mBypassController.getBypassEnabled()) {
                ArrayList<String> keysToRemove = new ArrayList<>();
                for (AlertEntry entry : mAlertEntries.values()) {
                    if (entry.mEntry != null && entry.mEntry.isBubble() && !entry.isSticky()) {
                        keysToRemove.add(entry.mEntry.getKey());
                    }
                }
                for (String key : keysToRemove) {
                    removeAlertEntry(key);
                }
            }
        }

        @Override
        public void onDozingChanged(boolean isDozing) {
            if (!isDozing) {
                // Let's make sure all huns we got while dozing time out within the normal timeout
                // duration. Otherwise they could get stuck for a very long time
                for (AlertEntry entry : mAlertEntries.values()) {
                    entry.updateEntry(true /* updatePostTime */);
                }
            }
        }
    };
}

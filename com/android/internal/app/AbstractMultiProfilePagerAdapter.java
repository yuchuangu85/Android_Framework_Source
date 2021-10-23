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
package com.android.internal.app;

import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.AppGlobals;
import android.app.admin.DevicePolicyEventLogger;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.os.UserManager;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.PagerAdapter;
import com.android.internal.widget.ViewPager;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Skeletal {@link PagerAdapter} implementation of a work or personal profile page for
 * intent resolution (including share sheet).
 */
public abstract class AbstractMultiProfilePagerAdapter extends PagerAdapter {

    private static final String TAG = "AbstractMultiProfilePagerAdapter";
    static final int PROFILE_PERSONAL = 0;
    static final int PROFILE_WORK = 1;

    @IntDef({PROFILE_PERSONAL, PROFILE_WORK})
    @interface Profile {}

    private final Context mContext;
    private int mCurrentPage;
    private OnProfileSelectedListener mOnProfileSelectedListener;
    private OnSwitchOnWorkSelectedListener mOnSwitchOnWorkSelectedListener;
    private Set<Integer> mLoadedPages;
    private final UserHandle mPersonalProfileUserHandle;
    private final UserHandle mWorkProfileUserHandle;
    private Injector mInjector;
    private boolean mIsWaitingToEnableWorkProfile;

    AbstractMultiProfilePagerAdapter(Context context, int currentPage,
            UserHandle personalProfileUserHandle,
            UserHandle workProfileUserHandle) {
        mContext = Objects.requireNonNull(context);
        mCurrentPage = currentPage;
        mLoadedPages = new HashSet<>();
        mPersonalProfileUserHandle = personalProfileUserHandle;
        mWorkProfileUserHandle = workProfileUserHandle;
        UserManager userManager = context.getSystemService(UserManager.class);
        mInjector = new Injector() {
            @Override
            public boolean hasCrossProfileIntents(List<Intent> intents, int sourceUserId,
                    int targetUserId) {
                return AbstractMultiProfilePagerAdapter.this
                        .hasCrossProfileIntents(intents, sourceUserId, targetUserId);
            }

            @Override
            public boolean isQuietModeEnabled(UserHandle workProfileUserHandle) {
                return userManager.isQuietModeEnabled(workProfileUserHandle);
            }

            @Override
            public void requestQuietModeEnabled(boolean enabled, UserHandle workProfileUserHandle) {
                AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
                    userManager.requestQuietModeEnabled(enabled, workProfileUserHandle);
                });
                mIsWaitingToEnableWorkProfile = true;
            }
        };
    }

    protected void markWorkProfileEnabledBroadcastReceived() {
        mIsWaitingToEnableWorkProfile = false;
    }

    protected boolean isWaitingToEnableWorkProfile() {
        return mIsWaitingToEnableWorkProfile;
    }

    /**
     * Overrides the default {@link Injector} for testing purposes.
     */
    @VisibleForTesting
    public void setInjector(Injector injector) {
        mInjector = injector;
    }

    protected boolean isQuietModeEnabled(UserHandle workProfileUserHandle) {
        return mInjector.isQuietModeEnabled(workProfileUserHandle);
    }

    void setOnProfileSelectedListener(OnProfileSelectedListener listener) {
        mOnProfileSelectedListener = listener;
    }

    void setOnSwitchOnWorkSelectedListener(OnSwitchOnWorkSelectedListener listener) {
        mOnSwitchOnWorkSelectedListener = listener;
    }

    Context getContext() {
        return mContext;
    }

    /**
     * Sets this instance of this class as {@link ViewPager}'s {@link PagerAdapter} and sets
     * an {@link ViewPager.OnPageChangeListener} where it keeps track of the currently displayed
     * page and rebuilds the list.
     */
    void setupViewPager(ViewPager viewPager) {
        viewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mCurrentPage = position;
                if (!mLoadedPages.contains(position)) {
                    rebuildActiveTab(true);
                    mLoadedPages.add(position);
                }
                if (mOnProfileSelectedListener != null) {
                    mOnProfileSelectedListener.onProfileSelected(position);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (mOnProfileSelectedListener != null) {
                    mOnProfileSelectedListener.onProfilePageStateChanged(state);
                }
            }
        });
        viewPager.setAdapter(this);
        viewPager.setCurrentItem(mCurrentPage);
        mLoadedPages.add(mCurrentPage);
    }

    void clearInactiveProfileCache() {
        if (mLoadedPages.size() == 1) {
            return;
        }
        mLoadedPages.remove(1 - mCurrentPage);
    }

    @Override
    public ViewGroup instantiateItem(ViewGroup container, int position) {
        final ProfileDescriptor profileDescriptor = getItem(position);
        container.addView(profileDescriptor.rootView);
        return profileDescriptor.rootView;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object view) {
        container.removeView((View) view);
    }

    @Override
    public int getCount() {
        return getItemCount();
    }

    protected int getCurrentPage() {
        return mCurrentPage;
    }

    @VisibleForTesting
    public UserHandle getCurrentUserHandle() {
        return getActiveListAdapter().mResolverListController.getUserHandle();
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return null;
    }

    /**
     * Returns the {@link ProfileDescriptor} relevant to the given <code>pageIndex</code>.
     * <ul>
     * <li>For a device with only one user, <code>pageIndex</code> value of
     * <code>0</code> would return the personal profile {@link ProfileDescriptor}.</li>
     * <li>For a device with a work profile, <code>pageIndex</code> value of <code>0</code> would
     * return the personal profile {@link ProfileDescriptor}, and <code>pageIndex</code> value of
     * <code>1</code> would return the work profile {@link ProfileDescriptor}.</li>
     * </ul>
     */
    abstract ProfileDescriptor getItem(int pageIndex);

    /**
     * Returns the number of {@link ProfileDescriptor} objects.
     * <p>For a normal consumer device with only one user returns <code>1</code>.
     * <p>For a device with a work profile returns <code>2</code>.
     */
    abstract int getItemCount();

    /**
     * Performs view-related initialization procedures for the adapter specified
     * by <code>pageIndex</code>.
     */
    abstract void setupListAdapter(int pageIndex);

    /**
     * Returns the adapter of the list view for the relevant page specified by
     * <code>pageIndex</code>.
     * <p>This method is meant to be implemented with an implementation-specific return type
     * depending on the adapter type.
     */
    @VisibleForTesting
    public abstract Object getAdapterForIndex(int pageIndex);

    /**
     * Returns the {@link ResolverListAdapter} instance of the profile that represents
     * <code>userHandle</code>. If there is no such adapter for the specified
     * <code>userHandle</code>, returns {@code null}.
     * <p>For example, if there is a work profile on the device with user id 10, calling this method
     * with <code>UserHandle.of(10)</code> returns the work profile {@link ResolverListAdapter}.
     */
    @Nullable
    abstract ResolverListAdapter getListAdapterForUserHandle(UserHandle userHandle);

    /**
     * Returns the {@link ResolverListAdapter} instance of the profile that is currently visible
     * to the user.
     * <p>For example, if the user is viewing the work tab in the share sheet, this method returns
     * the work profile {@link ResolverListAdapter}.
     * @see #getInactiveListAdapter()
     */
    @VisibleForTesting
    public abstract ResolverListAdapter getActiveListAdapter();

    /**
     * If this is a device with a work profile, returns the {@link ResolverListAdapter} instance
     * of the profile that is <b><i>not</i></b> currently visible to the user. Otherwise returns
     * {@code null}.
     * <p>For example, if the user is viewing the work tab in the share sheet, this method returns
     * the personal profile {@link ResolverListAdapter}.
     * @see #getActiveListAdapter()
     */
    @VisibleForTesting
    public abstract @Nullable ResolverListAdapter getInactiveListAdapter();

    public abstract ResolverListAdapter getPersonalListAdapter();

    public abstract @Nullable ResolverListAdapter getWorkListAdapter();

    abstract Object getCurrentRootAdapter();

    abstract ViewGroup getActiveAdapterView();

    abstract @Nullable ViewGroup getInactiveAdapterView();

    abstract String getMetricsCategory();

    /**
     * Rebuilds the tab that is currently visible to the user.
     * <p>Returns {@code true} if rebuild has completed.
     */
    boolean rebuildActiveTab(boolean doPostProcessing) {
        return rebuildTab(getActiveListAdapter(), doPostProcessing);
    }

    /**
     * Rebuilds the tab that is not currently visible to the user, if such one exists.
     * <p>Returns {@code true} if rebuild has completed.
     */
    boolean rebuildInactiveTab(boolean doPostProcessing) {
        if (getItemCount() == 1) {
            return false;
        }
        return rebuildTab(getInactiveListAdapter(), doPostProcessing);
    }

    private int userHandleToPageIndex(UserHandle userHandle) {
        if (userHandle.equals(getPersonalListAdapter().mResolverListController.getUserHandle())) {
            return PROFILE_PERSONAL;
        } else {
            return PROFILE_WORK;
        }
    }

    private boolean rebuildTab(ResolverListAdapter activeListAdapter, boolean doPostProcessing) {
        if (shouldShowNoCrossProfileIntentsEmptyState(activeListAdapter)) {
            activeListAdapter.postListReadyRunnable(doPostProcessing, /* rebuildCompleted */ true);
            return false;
        }
        return activeListAdapter.rebuildList(doPostProcessing);
    }

    private boolean shouldShowNoCrossProfileIntentsEmptyState(
            ResolverListAdapter activeListAdapter) {
        UserHandle listUserHandle = activeListAdapter.getUserHandle();
        return UserHandle.myUserId() != listUserHandle.getIdentifier()
                && allowShowNoCrossProfileIntentsEmptyState()
                && !mInjector.hasCrossProfileIntents(activeListAdapter.getIntents(),
                        UserHandle.myUserId(), listUserHandle.getIdentifier());
    }

    boolean allowShowNoCrossProfileIntentsEmptyState() {
        return true;
    }

    protected abstract void showWorkProfileOffEmptyState(
            ResolverListAdapter activeListAdapter, View.OnClickListener listener);

    protected abstract void showNoPersonalToWorkIntentsEmptyState(
            ResolverListAdapter activeListAdapter);

    protected abstract void showNoPersonalAppsAvailableEmptyState(
            ResolverListAdapter activeListAdapter);

    protected abstract void showNoWorkAppsAvailableEmptyState(
            ResolverListAdapter activeListAdapter);

    protected abstract void showNoWorkToPersonalIntentsEmptyState(
            ResolverListAdapter activeListAdapter);

    /**
     * Updates padding and visibilities as a result of an orientation change.
     * <p>They are not updated automatically, because the view is cached when created.
     * <p>When overridden, make sure to always call the super method.
     */
    void updateAfterConfigChange() {
        for (int i = 0; i < getItemCount(); i++) {
            ViewGroup emptyStateView = getItem(i).getEmptyStateView();
            ImageView icon = emptyStateView.findViewById(R.id.resolver_empty_state_icon);
            updateIconVisibility(icon, emptyStateView);
        }
    }

    private void updateIconVisibility(ImageView icon, ViewGroup emptyStateView) {
        if (isSpinnerShowing(emptyStateView)) {
            icon.setVisibility(View.INVISIBLE);
        } else if (mWorkProfileUserHandle != null
                && !getContext().getResources().getBoolean(R.bool.resolver_landscape_phone)) {
            icon.setVisibility(View.VISIBLE);
        } else {
            icon.setVisibility(View.GONE);
        }
    }

    /**
     * The empty state screens are shown according to their priority:
     * <ol>
     * <li>(highest priority) cross-profile disabled by policy (handled in
     * {@link #rebuildTab(ResolverListAdapter, boolean)})</li>
     * <li>no apps available</li>
     * <li>(least priority) work is off</li>
     * </ol>
     *
     * The intention is to prevent the user from having to turn
     * the work profile on if there will not be any apps resolved
     * anyway.
     */
    void showEmptyResolverListEmptyState(ResolverListAdapter listAdapter) {
        if (maybeShowNoCrossProfileIntentsEmptyState(listAdapter)) {
            return;
        }
        if (maybeShowWorkProfileOffEmptyState(listAdapter)) {
            return;
        }
        maybeShowNoAppsAvailableEmptyState(listAdapter);
    }

    private boolean maybeShowNoCrossProfileIntentsEmptyState(ResolverListAdapter listAdapter) {
        if (!shouldShowNoCrossProfileIntentsEmptyState(listAdapter)) {
            return false;
        }
        if (listAdapter.getUserHandle().equals(mPersonalProfileUserHandle)) {
            DevicePolicyEventLogger.createEvent(
                    DevicePolicyEnums.RESOLVER_EMPTY_STATE_NO_SHARING_TO_PERSONAL)
                    .setStrings(getMetricsCategory())
                    .write();
            showNoWorkToPersonalIntentsEmptyState(listAdapter);
        } else {
            DevicePolicyEventLogger.createEvent(
                    DevicePolicyEnums.RESOLVER_EMPTY_STATE_NO_SHARING_TO_WORK)
                    .setStrings(getMetricsCategory())
                    .write();
            showNoPersonalToWorkIntentsEmptyState(listAdapter);
        }
        return true;
    }

    /**
     * Returns {@code true} if the work profile off empty state screen is shown.
     */
    private boolean maybeShowWorkProfileOffEmptyState(ResolverListAdapter listAdapter) {
        UserHandle listUserHandle = listAdapter.getUserHandle();
        if (!listUserHandle.equals(mWorkProfileUserHandle)
                || !mInjector.isQuietModeEnabled(mWorkProfileUserHandle)
                || listAdapter.getCount() == 0) {
            return false;
        }
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.RESOLVER_EMPTY_STATE_WORK_APPS_DISABLED)
                .setStrings(getMetricsCategory())
                .write();
        showWorkProfileOffEmptyState(listAdapter,
                v -> {
                    ProfileDescriptor descriptor = getItem(
                            userHandleToPageIndex(listAdapter.getUserHandle()));
                    showSpinner(descriptor.getEmptyStateView());
                    if (mOnSwitchOnWorkSelectedListener != null) {
                        mOnSwitchOnWorkSelectedListener.onSwitchOnWorkSelected();
                    }
                    mInjector.requestQuietModeEnabled(false, mWorkProfileUserHandle);
                });
        return true;
    }

    private void maybeShowNoAppsAvailableEmptyState(ResolverListAdapter listAdapter) {
        UserHandle listUserHandle = listAdapter.getUserHandle();
        if (mWorkProfileUserHandle != null
                && (UserHandle.myUserId() == listUserHandle.getIdentifier()
                        || !hasAppsInOtherProfile(listAdapter))) {
            DevicePolicyEventLogger.createEvent(
                    DevicePolicyEnums.RESOLVER_EMPTY_STATE_NO_APPS_RESOLVED)
                    .setStrings(getMetricsCategory())
                    .setBoolean(/*isPersonalProfile*/ listUserHandle == mPersonalProfileUserHandle)
                    .write();
            if (listUserHandle == mPersonalProfileUserHandle) {
                showNoPersonalAppsAvailableEmptyState(listAdapter);
            } else {
                showNoWorkAppsAvailableEmptyState(listAdapter);
            }
        } else if (mWorkProfileUserHandle == null) {
            showConsumerUserNoAppsAvailableEmptyState(listAdapter);
        }
    }

    protected void showEmptyState(ResolverListAdapter activeListAdapter,
            @DrawableRes int iconRes, @StringRes int titleRes, @StringRes int subtitleRes) {
        showEmptyState(activeListAdapter, iconRes, titleRes, subtitleRes, /* buttonOnClick */ null);
    }

    protected void showEmptyState(ResolverListAdapter activeListAdapter,
            @DrawableRes int iconRes, @StringRes int titleRes, @StringRes int subtitleRes,
            View.OnClickListener buttonOnClick) {
        ProfileDescriptor descriptor = getItem(
                userHandleToPageIndex(activeListAdapter.getUserHandle()));
        descriptor.rootView.findViewById(R.id.resolver_list).setVisibility(View.GONE);
        ViewGroup emptyStateView = descriptor.getEmptyStateView();
        resetViewVisibilitiesForWorkProfileEmptyState(emptyStateView);
        emptyStateView.setVisibility(View.VISIBLE);

        View container = emptyStateView.findViewById(R.id.resolver_empty_state_container);
        setupContainerPadding(container);

        TextView title = emptyStateView.findViewById(R.id.resolver_empty_state_title);
        title.setText(titleRes);

        TextView subtitle = emptyStateView.findViewById(R.id.resolver_empty_state_subtitle);
        if (subtitleRes != 0) {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(subtitleRes);
        } else {
            subtitle.setVisibility(View.GONE);
        }

        Button button = emptyStateView.findViewById(R.id.resolver_empty_state_button);
        button.setVisibility(buttonOnClick != null ? View.VISIBLE : View.GONE);
        button.setOnClickListener(buttonOnClick);

        ImageView icon = emptyStateView.findViewById(R.id.resolver_empty_state_icon);
        icon.setImageResource(iconRes);
        updateIconVisibility(icon, emptyStateView);

        activeListAdapter.markTabLoaded();
    }

    /**
     * Sets up the padding of the view containing the empty state screens.
     * <p>This method is meant to be overridden so that subclasses can customize the padding.
     */
    protected void setupContainerPadding(View container) {}

    private void showConsumerUserNoAppsAvailableEmptyState(ResolverListAdapter activeListAdapter) {
        ProfileDescriptor descriptor = getItem(
                userHandleToPageIndex(activeListAdapter.getUserHandle()));
        descriptor.rootView.findViewById(R.id.resolver_list).setVisibility(View.GONE);
        View emptyStateView = descriptor.getEmptyStateView();
        resetViewVisibilitiesForConsumerUserEmptyState(emptyStateView);
        emptyStateView.setVisibility(View.VISIBLE);

        activeListAdapter.markTabLoaded();
    }

    private boolean isSpinnerShowing(View emptyStateView) {
        return emptyStateView.findViewById(R.id.resolver_empty_state_progress).getVisibility()
                == View.VISIBLE;
    }

    private void showSpinner(View emptyStateView) {
        emptyStateView.findViewById(R.id.resolver_empty_state_icon).setVisibility(View.INVISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_title).setVisibility(View.INVISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_button).setVisibility(View.INVISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_progress).setVisibility(View.VISIBLE);
        emptyStateView.findViewById(R.id.empty).setVisibility(View.GONE);
    }

    private void resetViewVisibilitiesForWorkProfileEmptyState(View emptyStateView) {
        emptyStateView.findViewById(R.id.resolver_empty_state_icon).setVisibility(View.VISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_title).setVisibility(View.VISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_subtitle).setVisibility(View.VISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_button).setVisibility(View.INVISIBLE);
        emptyStateView.findViewById(R.id.resolver_empty_state_progress).setVisibility(View.GONE);
        emptyStateView.findViewById(R.id.empty).setVisibility(View.GONE);
    }

    private void resetViewVisibilitiesForConsumerUserEmptyState(View emptyStateView) {
        emptyStateView.findViewById(R.id.resolver_empty_state_icon).setVisibility(View.GONE);
        emptyStateView.findViewById(R.id.resolver_empty_state_title).setVisibility(View.GONE);
        emptyStateView.findViewById(R.id.resolver_empty_state_subtitle).setVisibility(View.GONE);
        emptyStateView.findViewById(R.id.resolver_empty_state_button).setVisibility(View.GONE);
        emptyStateView.findViewById(R.id.resolver_empty_state_progress).setVisibility(View.GONE);
        emptyStateView.findViewById(R.id.empty).setVisibility(View.VISIBLE);
    }

    protected void showListView(ResolverListAdapter activeListAdapter) {
        ProfileDescriptor descriptor = getItem(
                userHandleToPageIndex(activeListAdapter.getUserHandle()));
        descriptor.rootView.findViewById(R.id.resolver_list).setVisibility(View.VISIBLE);
        View emptyStateView = descriptor.rootView.findViewById(R.id.resolver_empty_state);
        emptyStateView.setVisibility(View.GONE);
    }

    private boolean hasCrossProfileIntents(List<Intent> intents, int source, int target) {
        IPackageManager packageManager = AppGlobals.getPackageManager();
        ContentResolver contentResolver = mContext.getContentResolver();
        for (Intent intent : intents) {
            if (IntentForwarderActivity.canForward(intent, source, target, packageManager,
                    contentResolver) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAppsInOtherProfile(ResolverListAdapter adapter) {
        if (mWorkProfileUserHandle == null) {
            return false;
        }
        List<ResolverActivity.ResolvedComponentInfo> resolversForIntent =
                adapter.getResolversForUser(UserHandle.of(UserHandle.myUserId()));
        for (ResolverActivity.ResolvedComponentInfo info : resolversForIntent) {
            ResolveInfo resolveInfo = info.getResolveInfoAt(0);
            if (resolveInfo.targetUserId != UserHandle.USER_CURRENT) {
                return true;
            }
        }
        return false;
    }

    boolean shouldShowEmptyStateScreen(ResolverListAdapter listAdapter) {
        int count = listAdapter.getUnfilteredCount();
        return (count == 0 && listAdapter.getPlaceholderCount() == 0)
                || (listAdapter.getUserHandle().equals(mWorkProfileUserHandle)
                    && isQuietModeEnabled(mWorkProfileUserHandle));
    }

    protected class ProfileDescriptor {
        final ViewGroup rootView;
        private final ViewGroup mEmptyStateView;
        ProfileDescriptor(ViewGroup rootView) {
            this.rootView = rootView;
            mEmptyStateView = rootView.findViewById(R.id.resolver_empty_state);
        }

        protected ViewGroup getEmptyStateView() {
            return mEmptyStateView;
        }
    }

    public interface OnProfileSelectedListener {
        /**
         * Callback for when the user changes the active tab from personal to work or vice versa.
         * <p>This callback is only called when the intent resolver or share sheet shows
         * the work and personal profiles.
         * @param profileIndex {@link #PROFILE_PERSONAL} if the personal profile was selected or
         * {@link #PROFILE_WORK} if the work profile was selected.
         */
        void onProfileSelected(int profileIndex);


        /**
         * Callback for when the scroll state changes. Useful for discovering when the user begins
         * dragging, when the pager is automatically settling to the current page, or when it is
         * fully stopped/idle.
         * @param state {@link ViewPager#SCROLL_STATE_IDLE}, {@link ViewPager#SCROLL_STATE_DRAGGING}
         *              or {@link ViewPager#SCROLL_STATE_SETTLING}
         * @see ViewPager.OnPageChangeListener#onPageScrollStateChanged
         */
        void onProfilePageStateChanged(int state);
    }

    /**
     * Listener for when the user switches on the work profile from the work tab.
     */
    interface OnSwitchOnWorkSelectedListener {
        /**
         * Callback for when the user switches on the work profile from the work tab.
         */
        void onSwitchOnWorkSelected();
    }

    /**
     * Describes an injector to be used for cross profile functionality. Overridable for testing.
     */
    @VisibleForTesting
    public interface Injector {
        /**
         * Returns {@code true} if at least one of the provided {@code intents} can be forwarded
         * from {@code sourceUserId} to {@code targetUserId}.
         */
        boolean hasCrossProfileIntents(List<Intent> intents, int sourceUserId, int targetUserId);

        /**
         * Returns whether the given profile is in quiet mode or not.
         */
        boolean isQuietModeEnabled(UserHandle workProfileUserHandle);

        /**
         * Enables or disables quiet mode for a managed profile.
         */
        void requestQuietModeEnabled(boolean enabled, UserHandle workProfileUserHandle);
    }
}
/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.search;

import android.annotation.NonNull;
import android.app.ISearchManager;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * The search manager service handles the search UI, and maintains a registry of
 * searchable activities.
 */
public class SearchManagerService extends ISearchManager.Stub {
    private static final String TAG = "SearchManagerService";
    final Handler mHandler;

    public static class Lifecycle extends SystemService {
        private SearchManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new SearchManagerService(getContext());
            publishBinderService(Context.SEARCH_SERVICE, mService);
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mService.mHandler.post(() -> mService.onUnlockUser(user.getUserIdentifier()));
        }

        @Override
        public void onUserStopped(@NonNull TargetUser user) {
            mService.onCleanupUser(user.getUserIdentifier());
        }
    }

    // Context that the service is running in.
    private final Context mContext;

    // This field is initialized lazily in getSearchables(), and then never modified.
    @GuardedBy("mSearchables")
    private final SparseArray<Searchables> mSearchables = new SparseArray<>();

    /**
     * Initializes the Search Manager service in the provided system context.
     * Only one instance of this object should be created!
     *
     * @param context to use for accessing DB, window manager, etc.
     */
    public SearchManagerService(Context context)  {
        mContext = context;
        new MyPackageMonitor().register(context, null, UserHandle.ALL, true);
        new GlobalSearchProviderObserver(context.getContentResolver());
        mHandler = BackgroundThread.getHandler();
    }

    private Searchables getSearchables(int userId) {
        return getSearchables(userId, false);
    }

    private Searchables getSearchables(int userId, boolean forceUpdate) {
        final long token = Binder.clearCallingIdentity();
        try {
            final UserManager um = mContext.getSystemService(UserManager.class);
            if (um.getUserInfo(userId) == null) {
                throw new IllegalStateException("User " + userId + " doesn't exist");
            }
            if (!um.isUserUnlockingOrUnlocked(userId)) {
                throw new IllegalStateException("User " + userId + " isn't unlocked");
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        synchronized (mSearchables) {
            Searchables searchables = mSearchables.get(userId);
            if (searchables == null) {
                searchables = new Searchables(mContext, userId);
                searchables.updateSearchableList();
                mSearchables.append(userId, searchables);
            } else if (forceUpdate) {
                searchables.updateSearchableList();
            }
            return searchables;
        }
    }

    private void onUnlockUser(int userId) {
        try {
            getSearchables(userId, true);
        } catch (IllegalStateException ignored) {
            // We're just trying to warm a cache, so we don't mind if the user
            // was stopped or destroyed before we got here.
        }
    }

    private void onCleanupUser(int userId) {
        synchronized (mSearchables) {
            mSearchables.remove(userId);
        }
    }

    /**
     * Refreshes the "searchables" list when packages are added/removed.
     */
    class MyPackageMonitor extends PackageMonitor {

        @Override
        public void onSomePackagesChanged() {
            updateSearchables();
        }

        @Override
        public void onPackageModified(String pkg) {
            updateSearchables();
        }

        private void updateSearchables() {
            final int changingUserId = getChangingUserId();
            synchronized (mSearchables) {
                // Update list of searchable activities
                for (int i = 0; i < mSearchables.size(); i++) {
                    if (changingUserId == mSearchables.keyAt(i)) {
                        mSearchables.valueAt(i).updateSearchableList();
                        break;
                    }
                }
            }
            // Inform all listeners that the list of searchables has been updated.
            Intent intent = new Intent(SearchManager.INTENT_ACTION_SEARCHABLES_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                    | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcastAsUser(intent, new UserHandle(changingUserId));
        }
    }

    class GlobalSearchProviderObserver extends ContentObserver {
        private final ContentResolver mResolver;

        public GlobalSearchProviderObserver(ContentResolver resolver) {
            super(null);
            mResolver = resolver;
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.SEARCH_GLOBAL_SEARCH_ACTIVITY),
                    false /* notifyDescendants */,
                    this);
        }

        @Override
        public void onChange(boolean selfChange) {
            synchronized (mSearchables) {
                for (int i = 0; i < mSearchables.size(); i++) {
                    mSearchables.valueAt(i).updateSearchableList();
                }
            }
            Intent intent = new Intent(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    //
    // Searchable activities API
    //

    /**
     * Returns the SearchableInfo for a given activity.
     *
     * @param launchActivity The activity from which we're launching this search.
     * @return Returns a SearchableInfo record describing the parameters of the search,
     * or null if no searchable metadata was available.
     */
    @Override
    public SearchableInfo getSearchableInfo(final ComponentName launchActivity) {
        if (launchActivity == null) {
            Log.e(TAG, "getSearchableInfo(), activity == null");
            return null;
        }
        return getSearchables(UserHandle.getCallingUserId()).getSearchableInfo(launchActivity);
    }

    /**
     * Returns a list of the searchable activities that can be included in global search.
     */
    @Override
    public List<SearchableInfo> getSearchablesInGlobalSearch() {
        return getSearchables(UserHandle.getCallingUserId()).getSearchablesInGlobalSearchList();
    }

    @Override
    public List<ResolveInfo> getGlobalSearchActivities() {
        return getSearchables(UserHandle.getCallingUserId()).getGlobalSearchActivities();
    }

    /**
     * Gets the name of the global search activity.
     */
    @Override
    public ComponentName getGlobalSearchActivity() {
        return getSearchables(UserHandle.getCallingUserId()).getGlobalSearchActivity();
    }

    /**
     * Gets the name of the web search activity.
     */
    @Override
    public ComponentName getWebSearchActivity() {
        return getSearchables(UserHandle.getCallingUserId()).getWebSearchActivity();
    }

    @Override
    public void launchAssist(int userHandle, Bundle args) {
        StatusBarManagerInternal statusBarManager =
                LocalServices.getService(StatusBarManagerInternal.class);
        if (statusBarManager != null) {
            statusBarManager.startAssist(args);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        synchronized (mSearchables) {
            for (int i = 0; i < mSearchables.size(); i++) {
                ipw.print("\nUser: "); ipw.println(mSearchables.keyAt(i));
                ipw.increaseIndent();
                mSearchables.valueAt(i).dump(fd, ipw, args);
                ipw.decreaseIndent();
            }
        }
    }
}

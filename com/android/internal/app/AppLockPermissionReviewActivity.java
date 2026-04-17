/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.Manifest;
import android.annotation.IntDef;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.transition.TransitionManager;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.widget.ResolverDrawerLayout;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * An activity that reviews app permissions for photos and files access during the App Lock setup.
 *
 * <p>This activity is launched by {@link AppLockActivity} using
 * {@link android.app.Activity#startActivityForResult(android.content.Intent, int)} with the
 * request code {@code REQUEST_CODE_APP_PERMISSION_REVIEW_DIALOG}. It is shown when a user is
 * locking an app that can access specific data, to make them aware of other applications that hold
 * the permission to access the same data on their device.
 *
 * <p>Upon completion, this activity returns {@link android.app.Activity#RESULT_OK} if the user
 * chooses to continue with the process, or {@link android.app.Activity#RESULT_CANCELED} if they
 * cancel. This result is then handled by
 * {@link AppLockActivity#onActivityResult(int, int, Intent)}.
 */
public class AppLockPermissionReviewActivity extends Activity {

    private static final String TAG = "AppLockPermissionReview";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.DEBUG);

    public static final String EXTRA_PERMISSION_REVIEW_TYPE =
            "android.intent.extra.app_lock_permission_review_type";
    public static final int REVIEW_TYPE_INVALID = -1;
    public static final int REVIEW_TYPE_PHOTOS = 0;
    public static final int REVIEW_TYPE_FILES = 1;

    @IntDef(prefix = {"REVIEW_TYPE_"}, value = {
            REVIEW_TYPE_PHOTOS,
            REVIEW_TYPE_FILES
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReviewType {}

    private static final SparseArray<ReviewTypeConfig> REVIEW_CONFIGS = new SparseArray<>();
    static {
        REVIEW_CONFIGS.put(REVIEW_TYPE_PHOTOS, new ReviewTypeConfig(
                new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                        Manifest.permission.MANAGE_EXTERNAL_STORAGE
                },
                R.string.app_lock_permission_review_dialog_title_photos,
                R.string.app_lock_permission_review_dialog_subtitle_photos
        ));

        REVIEW_CONFIGS.put(REVIEW_TYPE_FILES, new ReviewTypeConfig(
                new String[]{Manifest.permission.MANAGE_EXTERNAL_STORAGE},
                R.string.app_lock_permission_review_dialog_title_files,
                R.string.app_lock_permission_review_dialog_subtitle_files
        ));
    }

    private final ExecutorService mBackgroundExecutor = Executors.newSingleThreadExecutor();

    /**
     * Creates an {@link Intent} to launch {@link AppLockPermissionReviewActivity} to show a
     * permission review sheet that lists applications which can access the data within the target
     * app before the user confirms enabling App Lock for the provided package.
     *
     * @param packageName The package name of the application the user intends to lock.
     * @param reviewType The category of data access to review.
     */
    static Intent createIntent(Context context, String packageName, @ReviewType int reviewType) {
        final Intent appLockPermissionReviewIntent = new Intent(context,
                AppLockPermissionReviewActivity.class);
        appLockPermissionReviewIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
        appLockPermissionReviewIntent.putExtra(EXTRA_PERMISSION_REVIEW_TYPE, reviewType);
        return appLockPermissionReviewIntent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        if (savedInstanceState != null) {
            return;
        }
        @ReviewType
        final int reviewType = getIntent().getIntExtra(EXTRA_PERMISSION_REVIEW_TYPE,
                REVIEW_TYPE_INVALID);
        final ReviewTypeConfig config = REVIEW_CONFIGS.get(reviewType);

        if (config == null) {
            if (DEBUG) {
                Slog.d(TAG, "Invalid review type or no config found for: " + reviewType);
            }
            finish();
            return;
        }
        setContentView(R.layout.app_lock_permission_review_sheet);

        // Updates the dialog title and subtitle based on the review type.
        ((TextView) findViewById(R.id.app_lock_permission_review_dialog_title))
                .setText(config.mTitleResId);
        ((TextView) findViewById(R.id.app_lock_permission_review_dialog_subtitle))
                .setText(config.mSubtitleResId);

        // Dismiss App Lock flow when the transparent background is tapped.
        final View rootLayout = findViewById(R.id.app_lock_activity_layout);
        if (rootLayout instanceof ResolverDrawerLayout) {
            ((ResolverDrawerLayout) rootLayout).setOnDismissListener(this::onBackPressed);
        } else {
            rootLayout.setOnClickListener(v -> onBackPressed());
        }

        // Set up the recycler view and its adapter.
        final AppLockMaxHeightRecyclerView recyclerView = findViewById(
                R.id.app_lock_permission_review_app_list_recycler_view);
        final AppLockPermissionReviewAdapter appLockPermissionReviewAdapter =
                new AppLockPermissionReviewAdapter();
        recyclerView.setAdapter(appLockPermissionReviewAdapter);

        findViewById(R.id.app_lock_permission_review_btn_cancel).setOnClickListener(v -> {
            onBackPressed();
        });

        findViewById(R.id.app_lock_permission_review_btn_continue).setOnClickListener(v -> {
            setResult(Activity.RESULT_OK);
            finish();
        });
        populateAppsList(appLockPermissionReviewAdapter, config);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Finish the activity if it is being stopped for reasons other than a config change (e.g.,
        // Home button).
        if (!isChangingConfigurations()) {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        setResult(Activity.RESULT_CANCELED);

        // Use finishAffinity() to dismiss the entire AppLockActivity stack as a single unit.
        // This ensures a clean task-level exit transition and prevents the parent
        // activity from momentarily appearing (flickering) during dismissal.
        finishAffinity();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mBackgroundExecutor.shutdownNow();
    }

    /** Populates the list of apps that hold the permissions currently being reviewed. */
    protected void populateAppsList(AppLockPermissionReviewAdapter appLockPermissionReviewAdapter,
            ReviewTypeConfig config) {
        mBackgroundExecutor.execute(() -> {
            final List<AppWithPermissionInfo> appsWithPermissions =
                    getAppsWithPermissions(config);
            runOnUiThread(() -> updateUiWithAppList(appsWithPermissions,
                    appLockPermissionReviewAdapter));
        });
    }

    /** Returns a list of user-facing apps that hold the permissions currently being reviewed.  */
    protected List<AppWithPermissionInfo> getAppsWithPermissions(ReviewTypeConfig config) {
        final PackageManager packageManager = getPackageManager();
        final List<AppWithPermissionInfo> appsWithPermissions = new ArrayList<>();

        final List<PackageInfo> packages = packageManager
                .getPackagesHoldingPermissions(config.mPermissions, /* flags= */ 0);
        for (PackageInfo packageInfo : packages) {
            if (!isUserFacingApp(packageManager, packageInfo.packageName)) {
                continue;
            }

            final ApplicationInfo appInfo = packageInfo.applicationInfo;
            if (appInfo != null) {
                final CharSequence packageLabel = packageManager.getApplicationLabel(appInfo);
                final Drawable appIcon = appInfo.loadIcon(packageManager);
                appsWithPermissions.add(new AppWithPermissionInfo(packageLabel, appIcon,
                        packageInfo.packageName));
            }
        }
        return appsWithPermissions;
    }

    /** Updates the RecyclerView with the list of apps and configures the UI. */
    protected void updateUiWithAppList(List<AppWithPermissionInfo> appsWithPermissions,
            AppLockPermissionReviewAdapter appLockPermissionReviewAdapter) {

        // If there are no apps with the permission being reviewed, we can just finish and return
        // RESULT_OK to continue the app lock setup.
        if (appsWithPermissions.isEmpty()) {
            setResult(Activity.RESULT_OK);
            if (DEBUG) {
                Slog.d(TAG, "No app found holding the permission, skipping the permission review.");
            }
            finish();
            return;
        }
        final AppLockMaxHeightRecyclerView recyclerView = findViewById(
                R.id.app_lock_permission_review_app_list_recycler_view);
        final View viewAllButton = findViewById(R.id.app_lock_permission_review_btn_view_all);

        // Show a collapsed list with a "View all" button if there are more than 3 apps.
        if (appsWithPermissions.size() <= 3) {
            appLockPermissionReviewAdapter.updateData(appsWithPermissions);
            viewAllButton.setVisibility(View.GONE);
            return;
        }
        appLockPermissionReviewAdapter.updateData(appsWithPermissions.subList(0, 3));
        viewAllButton.setVisibility(View.VISIBLE);
        viewAllButton.setOnClickListener(v -> {

            if (recyclerView.getParent() instanceof ViewGroup) {
                TransitionManager.beginDelayedTransition((ViewGroup) recyclerView.getParent());
            }
            appLockPermissionReviewAdapter.updateData(appsWithPermissions);
            findViewById(R.id.app_lock_permission_review_bottom_sheet_divider)
                    .setVisibility(View.VISIBLE);
            viewAllButton.setVisibility(View.GONE);
        });
    }

    /** Checks if an app is user-facing and should be shown in the list. */
    private boolean isUserFacingApp(PackageManager packageManager, String packageName) {
        // Exclude the application that is being App Lock enabled from the review list.
        final String packageToLock = getIntent().getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (packageToLock != null && packageToLock.equals(packageName)) {
            return false;
        }

        // Filter out background services, widget-only apps, etc.
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setPackage(packageName);
        final List<ResolveInfo> activities = packageManager.queryIntentActivities(intent,
                /* flags= */ 0);
        return activities != null && !activities.isEmpty();
    }

    /**
     * Data class that holds information for an application that holds the permissions currently
     * being reviewed. This information (label, icon, and package name) is used to populate the list
     * in the permission review sheet.
     */
    static class AppWithPermissionInfo {
        final CharSequence mAppName;
        final Drawable mAppIcon;
        final String mPackageName;

        AppWithPermissionInfo(CharSequence name, Drawable icon, String pkgName) {
            this.mAppName = name;
            this.mAppIcon = icon;
            this.mPackageName = pkgName;
        }
    }

    /**
     * Data class that holds configuration for a specific type of permission review.
     * This information (required permissions and UI resource IDs) is used to filter the application
     * list and populate the strings in the permission review sheet.
     */
    static final class ReviewTypeConfig {
        final String[] mPermissions;
        final int mTitleResId;
        final int mSubtitleResId;

        ReviewTypeConfig(String[] permissions, int titleResId, int subtitleResId) {
            this.mPermissions = permissions;
            this.mTitleResId = titleResId;
            this.mSubtitleResId = subtitleResId;
        }
    }
}

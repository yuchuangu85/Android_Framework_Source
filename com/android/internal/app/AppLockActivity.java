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

package com.android.internal.app;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.android.internal.app.LockedAppActivity.convertDrawableToBitmap;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.Objects;

/**
 * Activity that allows the user to enable or disable App Lock for a specific application.
 *
 * <p>This activity is launched by an {@link Intent} with action
 * {@link PackageManager#ACTION_SET_APP_LOCK}. The intent must include
 * {@link Intent#EXTRA_PACKAGE_NAME} and {@link PackageManager#EXTRA_APP_LOCK_NEW_STATE}.
 *
 * <h3>Enabling App Lock</h3>
 * When enabling App Lock ({@link PackageManager#EXTRA_APP_LOCK_NEW_STATE} is {@code true}):
 * <ol>
 *     <li>If a screen lock is not set, a dialog prompts the user to set one. This launches the
 *         system's screen lock setup activity with the
 *         {@link android.app.admin.DevicePolicyManager#ACTION_SET_NEW_PASSWORD} action and the
 *         request code {@code REQUEST_CODE_SET_SCREEN_LOCK}.</li>
 *     <li>After a screen lock is set (or if one already exists), a user education dialog activity
 *         with the request code {@code REQUEST_CODE_USER_EDUCATION_DIALOG} is shown.</li>
 *     <li>If the target app is a Photos or Files app, a permission review dialog activity with the
 *         request code {@code REQUEST_CODE_APP_PERMISSION_REVIEW_DIALOG} will be shown.
 *         <ul>
 *             <li><b>Photos App:</b> Identified by its ability to handle an
 *                 {@link android.content.Intent#ACTION_VIEW} intent with {@code image/*} or
 *                 {@code video/*} MIME types.</li>
 *             <li><b>Files App:</b> Identified by its ability to handle the
 *                 {@link DownloadManager#ACTION_VIEW_DOWNLOADS} intent.</li>
 *         </ul>
 *     </li>
 *     <li>Finally, a biometric prompt is displayed to authenticate the user before enabling App
 *         Lock.</li>
 * </ol>
 *
 * <h3>Disabling App Lock</h3>
 * When disabling App Lock ({@link PackageManager#EXTRA_APP_LOCK_NEW_STATE} is {@code false}):
 * <ol>
 *     <li>A biometric prompt is displayed to authenticate the user before disabling App Lock.</li>
 * </ol>
 *
 * <p>Note: This activity runs in a separate process. To accommodate testing with
 * {@link androidx.test.core.app.ActivityScenario}, which does not support testing activities in a
 * separate process, this class is not declared {@code final}. This allows
 * {@link AppLockActivityTest} to use a test-specific subclass.
 *
 * <p>Note: Sub-activities launched from this activity should use
 * {@link android.app.Activity#finishAffinity()} when the user cancels or navigates back. This
 * ensures the entire task stack is dismissed simultaneously, preventing a visible UI flash that
 * occurs if the parent activity is briefly re-displayed before it can finish itself.
 */
// TODO(b/436380342): Finish AppLockActivity UI.
// TODO(b/469727319): Revisit custom XML drawables, 'app_lock_btn_pill_backaground.xml' and
// 'app_lock_btn_pill_outline.xml'
public class AppLockActivity extends Activity {

    private static final String TAG = "AppLockActivity";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.DEBUG);

    private static final String SYSTEM_PACKAGE_NAME = "android";
    private static final int APP_LOCK_STATE_CHANGE_RESULT_TOAST_DURATION = Toast.LENGTH_SHORT;

    // Request codes used to identify the originating request.
    private static final int REQUEST_CODE_SET_SCREEN_LOCK = 1;
    private static final int REQUEST_CODE_USER_EDUCATION_DIALOG = 2;
    private static final int REQUEST_CODE_APP_PERMISSION_REVIEW_DIALOG = 3;

    @Nullable
    private static Injector sInjector;

    private final Injector mInjector = sInjector == null ? new Injector() : sInjector;

    private KeyguardManager mKeyguardManager;

    private AlertDialog mScreenLockDialog;

    private CharSequence mPackageLabel;
    private Drawable mPackageLogo;

    /**
     * Creates an {@link Intent} to launch {@link AppLockActivity} to enable or disable App Lock on
     * the provided package.
     */
    public static Intent createAppLockActivityIntent(@NonNull String packageName,
            boolean newAppLockState) {
        Objects.requireNonNull(packageName);

        return new Intent(PackageManager.ACTION_SET_APP_LOCK)
                .setClassName(SYSTEM_PACKAGE_NAME, AppLockActivity.class.getName())
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(PackageManager.EXTRA_APP_LOCK_NEW_STATE, newAppLockState)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    /**
     * Sets the {@link Injector} for testing purposes.
     *
     * <p>This method allows replacing the default injector with a mock implementation to facilitate
     * testing of this activity. This should only be used in debuggable builds.
     */
    @VisibleForTesting
    public static void setInjectorForTesting(@Nullable Injector injector) {
        if (!Build.IS_DEBUGGABLE) {
            throw new SecurityException("Injector should only be set in debuggable builds.");
        }
        sInjector = injector;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        // Precondition: Check App Lock feature flags.
        if (!android.security.Flags.appLockApis() || !android.security.Flags.appLockCore()) {
            Slog.w(TAG, "App Lock implementation is not enabled, finishing");
            finish();
            return;
        }

        if (savedInstanceState != null) {
            return;
        }

        // Check for valid Intent.
        final Intent intent = getIntent();
        final String packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (packageName == null) {
            Slog.w(TAG, "Package name is null, finishing");
            finish();
            return;
        }
        if (!intent.hasExtra(PackageManager.EXTRA_APP_LOCK_NEW_STATE)) {
            Slog.w(TAG, "No new App Lock state, finishing");
            finish();
            return;
        }
        final boolean newAppLockEnabled = intent.getBooleanExtra(
                PackageManager.EXTRA_APP_LOCK_NEW_STATE, false);

        // Retrieve the provided package's ApplicationInfo with App Lock information.
        final PackageManager packageManager = getPackageManager();
        final ApplicationInfo applicationInfo;
        try {
            applicationInfo = packageManager.getApplicationInfo(packageName,
                    ApplicationInfoFlags.of(PackageManager.GET_APP_LOCK_INFO));
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Package " + packageName + " not found", e);
            finish();
            return;
        }

        // Check that the provided package supports App Lock.
        if (!applicationInfo.isAppLockSupported) {
            Slog.w(TAG, packageName + " does not support App Lock, finishing");
            finish();
            return;
        }

        // Check that the new App Lock state is different from the current state.
        if (applicationInfo.isAppLockEnabled == newAppLockEnabled) {
            Slog.w(TAG, "The App Lock state for " + packageName + " is already "
                    + newAppLockEnabled + ", finishing");
            finish();
            return;
        }

        // Retrieve the package label.
        final CharSequence packageLabel = applicationInfo.loadLabel(packageManager);

        mKeyguardManager = getSystemService(KeyguardManager.class);
        final boolean isDeviceSecure = mKeyguardManager != null && mKeyguardManager.isDeviceSecure(
                UserHandle.myUserId());
        if (applicationInfo.isAppLockEnabled) {
            // Disable App Lock.
            if (isDeviceSecure) {
                showBiometricPrompt(packageName, packageLabel, newAppLockEnabled);
            } else {
                // This is an unexpected state. When the device becomes insecure, App Lock's
                // implementation in the system is responsible for disabling App Lock for all apps.
                Slog.e(TAG, "Device is not secure, cannot disable App Lock for " + packageName);
                finish();
            }
        } else {
            // Enable App Lock.
            if (!isDeviceSecure) {
                showSetupScreenLockDialog(packageName);
            } else {
                showUserEducationDialog(packageName, packageLabel);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mScreenLockDialog != null && mScreenLockDialog.isShowing()) {
            mScreenLockDialog.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mScreenLockDialog != null && mScreenLockDialog.isShowing()) {
            mScreenLockDialog.dismiss();
        }
    }

    /** Displays a dialog prompting the user to set up a secure screen lock. */
    private void showSetupScreenLockDialog(String packageName) {
        if (DEBUG) {
            Slog.d(TAG, "showSetupScreenLockDialog called for " + packageName);
        }
        final View dialogView = (View) LayoutInflater.from(this)
                .inflate(R.layout.app_lock_add_screen_lock_dialog, null);
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        mScreenLockDialog = builder.create();

        final View.OnClickListener addScreenLockClickListener = v -> {
            final Intent setLockIntent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
            startActivityForResult(setLockIntent, REQUEST_CODE_SET_SCREEN_LOCK);
        };
        final Button addScreenLockButton = dialogView.findViewById(
                R.id.app_lock_dialog_add_screen_lock_button);
        addScreenLockButton.setOnClickListener(addScreenLockClickListener);

        final View.OnClickListener cancelButtonClickListener = v -> {
            if (mScreenLockDialog != null) {
                mScreenLockDialog.dismiss();
            }
            finish();
        };
        final Button cancelButton = dialogView.findViewById(R.id.app_lock_dialog_cancel_button);
        cancelButton.setOnClickListener(cancelButtonClickListener);

        mScreenLockDialog.setOnCancelListener(dialog -> {
            finish();
        });
        mScreenLockDialog.show();
    }

    /** Displays the biometric authentication prompt to the user to confirm App Lock state change.*/
    @SuppressLint("AndroidFrameworkRequiresPermission")
    protected void showBiometricPrompt(String packageName, CharSequence packageLabel,
            boolean newAppLockEnabled) {
        if (DEBUG) {
            Slog.d(TAG, "showBiometricPrompt called for " + packageName);
        }
        final BiometricPrompt.AuthenticationCallback authenticationCallback =
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        if (DEBUG) {
                            Slog.d(TAG, "Authentication succeeded");
                        }
                        final boolean success = getPackageManager()
                                .setPackageAppLockEnabled(packageName, newAppLockEnabled);
                        showResultToast(success, packageName, packageLabel, newAppLockEnabled);
                        finish();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        Slog.w(TAG, "Authentication error: " + errorCode + " " + errString);
                        showResultToast(/* success= */ false, packageName, packageLabel,
                                newAppLockEnabled);
                        finish();
                    }
                };

        final String biometricPromptSubtitle = newAppLockEnabled
                ? getString(R.string.enable_app_lock_biometric_prompt_subtitle, packageLabel)
                : getString(R.string.disable_app_lock_biometric_prompt_subtitle, packageLabel);
        final BiometricPrompt.Builder biometricPromptBuilder =
                mInjector.getBiometricPromptBuilder(this)
                .setTitle(getString(R.string.biometric_dialog_default_title))
                .setSubtitle(biometricPromptSubtitle)
                .setLogoDescription(packageLabel.toString())
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG
                       | Authenticators.DEVICE_CREDENTIAL);

        // Retrieve package logo and set it as biometric prompt's logo.
        final Bitmap packageLogo = convertDrawableToBitmap(getPackageLogoAsDrawable(packageName));
        if (packageLogo != null) {
            biometricPromptBuilder.setLogoBitmap(packageLogo);
        }

        final BiometricPrompt biometricPrompt = biometricPromptBuilder.build();
        biometricPrompt.authenticate(new CancellationSignal(), getMainExecutor(),
                authenticationCallback);
    }

    /** Displays a user education dialog to the user which informs about the App Lock feature. */
    private void showUserEducationDialog(String packageName, CharSequence packageLabel) {
        if (DEBUG) {
            Slog.d(TAG, "showUserEducationDialog called for " + packageName);
        }
        final Intent userEducationIntent = AppLockUserEducationActivity.createIntent(this,
                packageName, packageLabel);
        startActivityForResult(userEducationIntent, REQUEST_CODE_USER_EDUCATION_DIALOG);
    }

    /** Displays a permission review dialog while adding App Lock. */
    private void showPermissionReviewDialog(String packageName,
            @AppLockPermissionReviewActivity.ReviewType int reviewType) {
        if (DEBUG) {
            Slog.d(TAG, "showPermissionReviewDialog called for " + packageName);
        }

        final Intent appLockPermissionReviewActivityIntent =
                AppLockPermissionReviewActivity.createIntent(this, packageName, reviewType);

        startActivityForResult(appLockPermissionReviewActivityIntent,
                REQUEST_CODE_APP_PERMISSION_REVIEW_DIALOG);
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // The following fails fast on non-OK results, except for REQUEST_CODE_SET_SCREEN_LOCK,
        // because SetNewPasswordActivity (launched via ACTION_SET_NEW_PASSWORD) doesn't reliably
        // return a result code. REQUEST_CODE_SET_SCREEN_LOCK is handled in the switch statement.
        if (requestCode != REQUEST_CODE_SET_SCREEN_LOCK && resultCode != Activity.RESULT_OK) {
            Slog.e(TAG, "onActivityResult failed for request code: " + requestCode
                    + ", result code: " + resultCode);
            finish();
            return;
        }

        // The original validation of the intent and its extras happened in onCreate().
        final Intent originalIntent = getIntent();
        final String packageName = originalIntent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        final boolean newAppLockEnabled = originalIntent.getBooleanExtra(
                PackageManager.EXTRA_APP_LOCK_NEW_STATE, false);
        if (packageName == null) {
            finish();
            return;
        }
        final CharSequence packageLabel = getPackageLabel(packageName);
        switch (requestCode) {
            case REQUEST_CODE_SET_SCREEN_LOCK -> {
                if (mScreenLockDialog != null && mScreenLockDialog.isShowing()) {
                    mScreenLockDialog.dismiss();
                }
                if (mKeyguardManager != null
                        && mKeyguardManager.isDeviceSecure(UserHandle.myUserId())) {
                    showUserEducationDialog(packageName, packageLabel);
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, "Screen lock not set after setup attempt, finishing.");
                    }
                    finish();
                }
            }
            case REQUEST_CODE_USER_EDUCATION_DIALOG -> {
                if (isPhotoApp(packageName)) {
                    showPermissionReviewDialog(packageName,
                            AppLockPermissionReviewActivity.REVIEW_TYPE_PHOTOS);
                } else if (isFilesApp(packageName)) {
                    showPermissionReviewDialog(packageName,
                            AppLockPermissionReviewActivity.REVIEW_TYPE_FILES);
                } else {
                    showBiometricPrompt(packageName, packageLabel, newAppLockEnabled);
                }
            }
            case REQUEST_CODE_APP_PERMISSION_REVIEW_DIALOG -> {
                showBiometricPrompt(packageName, packageLabel, newAppLockEnabled);
            }
            default -> {
                Slog.w(TAG, "Received unknown request code: " + requestCode);
            }
        }
    }

    /** Returns the package label for the provided application. */
    private CharSequence getPackageLabel(String packageName) {
        try {
            if (mPackageLabel == null) {
                mPackageLabel = getPackageManager().getApplicationInfo(packageName,
                        ApplicationInfoFlags.of(0)).loadLabel(getPackageManager());
            }
            return mPackageLabel;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Package " + packageName + " not found", e);
            return null;
        }
    }

    /** Returns the application icon for the provided application. */
    private Drawable getPackageLogoAsDrawable(String packageName) {
        try {
            if (mPackageLogo == null) {
                mPackageLogo = getPackageManager().getApplicationIcon(packageName);
            }
            return mPackageLogo;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Package " + packageName + " not found", e);
            return null;
        }
    }

    /**
     * Determines if an app is a "Files app" by checking if it handles the
     * {@link DownloadManager#ACTION_VIEW_DOWNLOADS} intent.
     */
    private boolean isFilesApp(String packageName) {
        final Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
        intent.setPackage(packageName);
        return getPackageManager().resolveActivity(intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY)) != null;
    }

    /**
     * Determines if an app is a "photo app" by checking if it can handle image or video MIME
     * types.
     */
    private boolean isPhotoApp(String packageName) {
        return canPackageHandleMimeType(packageName, "image/*")
            || canPackageHandleMimeType(packageName, "video/*");
    }

    private boolean canPackageHandleMimeType(String packageName, String mimeType) {
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setPackage(packageName);
            intent.setType(mimeType);

            List<ResolveInfo> activities = getPackageManager().queryIntentActivities(intent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            return !activities.isEmpty();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to query intent activities for " + packageName + " with "
                    + mimeType, e);
            return false;
        }
    }

    /* Informs the user via a toast whether the App Lock state was changed successfully. */
    private void showResultToast(boolean success, String packageName, CharSequence packageLabel,
            boolean newAppLockEnabled) {
        if (DEBUG) {
            Slog.d(TAG, "showResultToast called for " + packageName + ", success: " + success);
        }

        // A failed disablement of App Lock is not reported, as this state is considered
        // impossible and indicates an implementation error.
        if (!newAppLockEnabled && !success) {
            Slog.w(TAG, "Failed to disable App Lock for " + packageName
                    + ", which should not be possible.");
            return;
        }
        final int toastTextResId;
        if (newAppLockEnabled) {
            toastTextResId = success ? R.string.enable_app_lock_success_toast_message
                    : R.string.enable_app_lock_failure_toast_message;
        } else {
            toastTextResId = R.string.disable_app_lock_success_toast_message;
        }
        final CharSequence toastText = getString(toastTextResId, packageLabel);
        showToast(toastText, APP_LOCK_STATE_CHANGE_RESULT_TOAST_DURATION);
    }

    @VisibleForTesting
    protected void showToast(CharSequence toastText, int duration) {
        if (DEBUG) {
            Slog.d(TAG, "showToast called with text: " + toastText);
        }
        Toast.makeText(this, toastText, duration).show();
    }

    /**
     * An injector class for dependency injection, allowing for easier testing of AppLockActivity.
     */
    @VisibleForTesting
    public static class Injector {
        /** Returns a new {@link BiometricPrompt.Builder} for the given activity. */
        public BiometricPrompt.Builder getBiometricPromptBuilder(Activity activity) {
            return new BiometricPrompt.Builder(activity);
        }
    }
}

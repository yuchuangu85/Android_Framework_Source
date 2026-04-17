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

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.ResolverDrawerLayout;

/**
 * An activity that educates the user about the App Lock feature and prompts for its enablement.
 *
 * <p>This activity is launched by {@link AppLockActivity} using
 * {@link android.app.Activity#startActivityForResult(android.content.Intent, int)} with the
 * request code {@code REQUEST_CODE_USER_EDUCATION_DIALOG}. It should only be started after
 * {@link AppLockActivity} has confirmed that a screen lock is set on the device.
 *
 * <p>Upon completion, this activity returns {@link android.app.Activity#RESULT_OK} if the user
 * chooses to lock the app. This result is then handled by
 * {@link AppLockActivity#onActivityResult(int, int, Intent)}, which proceeds to show the biometric
 * prompt to finalize the setup.
 */
public class AppLockUserEducationActivity extends Activity {
    private static final String TAG = "AppLockUserEducation";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.DEBUG);

    private final int mUserId = UserHandle.myUserId();

    @Nullable
    private static Injector sInjector;

    private final Injector mInjector = sInjector == null ? new Injector() : sInjector;

    private View mAppLockLayout;

    /**
     * Creates an {@link Intent} to launch {@link AppLockUserEducationActivity} to show the user
     * education flow before enabling App Lock.
     */
    static Intent createIntent(Context context, String packageName, CharSequence packageLabel) {
        final Intent userEducationIntent = new Intent(context, AppLockUserEducationActivity.class);
        userEducationIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
        userEducationIntent.putExtra(Intent.EXTRA_TITLE, packageLabel);
        return userEducationIntent;
    }

    /**
     * Sets the {@link Injector} for testing purposes.
     *
     * <p>This method allows replacing the default injector with a mock implementation to facilitate
     * testing of this activity. This should only be used in debugg
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

        final Intent intent = getIntent();
        final String packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        final CharSequence packageLabel = intent.getCharSequenceExtra(Intent.EXTRA_TITLE);

        // Required package data must be present to show the dialog.
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(packageLabel)) {
            Slog.w(TAG, "Missing package name or label, finishing.");
            finish();
            return;
        }
        setupUiAndShowDialog(packageLabel);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final CharSequence packageLabel = getIntent().getCharSequenceExtra(Intent.EXTRA_TITLE);
        setupUiAndShowDialog(packageLabel);
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

    /** Sets up the UI and displays the App Lock education dialog. */
    private void setupUiAndShowDialog(CharSequence packageLabel) {
        if (DEBUG) {
            Slog.d(TAG, "setupUiAndShowDialog called with label: " + packageLabel);
        }
        setContentView(R.layout.app_lock_edu_activity);
        mAppLockLayout = findViewById(R.id.app_lock_edu_dialog);
        final TextView titleView = mAppLockLayout.findViewById(R.id.app_lock_edu_dialog_title);
        titleView.setText(getString(R.string.app_lock_edu_dialog_enable_app_lock_title,
                packageLabel));

        // Customize description based on available authentication methods.
        final TextView descriptionView = mAppLockLayout.findViewById(R.id.app_lock_edu_dialog_desc);
        final String credentialType = getCredentialTypeString();
        final String biometricDescription = getBiometricDescription();
        if (credentialType == null) {
            Slog.e(TAG, "Credential type is null, finishing.");
            finish();
            return;
        }
        if (biometricDescription == null) {
            descriptionView.setText(getString(
                    R.string.app_lock_edu_dialog_description_template_no_biometrics,
                    credentialType));
        } else {
            descriptionView.setText(getString(
                    R.string.app_lock_edu_dialog_description_template, biometricDescription,
                    credentialType));
        }

        final TextView aiDisclaimerView = mAppLockLayout.findViewById(
                R.id.app_lock_edu_dialog_info_ai_text);
        if (aiDisclaimerView != null) {
            aiDisclaimerView.setText(getString(R.string.app_lock_edu_dialog_info_ai_disclaimer,
                    packageLabel));
        }

        final Button lockButton = mAppLockLayout.findViewById(
                R.id.app_lock_edu_dialog_btn_lock_app);
        lockButton.setOnClickListener(v -> {
            setResult(Activity.RESULT_OK);
            finish();
        });

        final Button cancelButton = mAppLockLayout.findViewById(
                R.id.app_lock_edu_dialog_btn_cancel);
        cancelButton.setOnClickListener(v -> {
            onBackPressed();
        });

        // Dismiss App Lock flow when the transparent background is tapped.
        final View rootLayout = findViewById(R.id.app_lock_edu_layout);
        if (rootLayout instanceof ResolverDrawerLayout) {
            ((ResolverDrawerLayout) rootLayout).setOnDismissListener(this::onBackPressed);
        } else {
            rootLayout.setOnClickListener(v -> onBackPressed());
        }
    }

    /** Returns the current user's screen lock type as a string (e.g., PIN, Password). */
    @VisibleForTesting
    protected String getCredentialTypeString() {
        if (DEBUG) {
            Slog.d(TAG, "getCredentialTypeString called");
        }
        final int credentialType = mInjector.getLockPatternUtils(this).getCredentialTypeForUser(
                mUserId);

        final int resId;
        switch (credentialType) {
            case LockPatternUtils.CREDENTIAL_TYPE_PATTERN -> {
                resId = R.string.app_lock_edu_dialog_lockscreen_pattern_name;
            }
            case LockPatternUtils.CREDENTIAL_TYPE_PIN -> {
                resId = R.string.app_lock_edu_dialog_lockscreen_pin_name;
            }
            case LockPatternUtils.CREDENTIAL_TYPE_PASSWORD -> {
                resId = R.string.app_lock_edu_dialog_lockscreen_password_name;
            }
            default -> {
                return null;
            }
        }
        return getString(resId);
    }

    /** Returns the current user's enrolled biometrics as a string (e.g., "Fingerprint Unlock"). */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    @VisibleForTesting
    protected String getBiometricDescription() {
        if (DEBUG) {
            Slog.d(TAG, "getBiometricDescription called");
        }
        // Query user's fingerprint enrollment status.
        boolean hasFingerprint = false;
        try {
            final FingerprintManager fingerprintManager = mInjector.getFingerprintManager(this);
            if (fingerprintManager != null && fingerprintManager.hasEnrolledFingerprints(mUserId)) {
                hasFingerprint = true;
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "Exception while checking for fingerprints", e);
        }

        // Query user's face enrollment status.
        boolean hasFace = false;
        try {
            final FaceManager faceManager = mInjector.getFaceManager(this);
            if (faceManager != null && faceManager.hasEnrolledTemplates(mUserId)) {
                hasFace = true;
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "Exception while checking for face templates", e);
        }

        // Return the appropriate combined string based on enrolled biometrics.
        final int resId;
        if (hasFingerprint && hasFace) {
            resId = R.string.app_lock_edu_dialog_biometric_description_has_fingerprint_and_face;
        } else if (hasFingerprint) {
            resId = R.string.app_lock_edu_dialog_biometric_description_has_fingerprint;
        } else if (hasFace) {
            resId = R.string.app_lock_edu_dialog_biometric_description_has_face;
        } else {
            return null;
        }
        return getString(resId);
    }

    /**
     * An injector class for dependency injection, allowing for easier testing.
     */
    public static class Injector {
        /** Returns a new {@link LockPatternUtils} for the given context. */
        public LockPatternUtils getLockPatternUtils(Context context) {
            return new LockPatternUtils(context);
        }

        /** Returns the {@link FingerprintManager} for the given context. */
        public FingerprintManager getFingerprintManager(Context context) {
            return context.getSystemService(FingerprintManager.class);
        }

        /** Returns the {@link FaceManager} for the given context. */
        public FaceManager getFaceManager(Context context) {
            return context.getSystemService(FaceManager.class);
        }
    }
}

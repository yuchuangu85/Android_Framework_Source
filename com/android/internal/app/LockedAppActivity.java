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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AppLockInternal;
import android.app.AppLockInternal.PackageLockedStateListener;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.View;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An activity that presents a {@link BiometricPrompt} to the user for authenticating actions
 * related to a package locked by App Lock.
 *
 * <p>This activity is launched when the user attempts to interact with a package locked by App Lock
 * , such as launching a locked app or attempting to uninstall it. It handles the full
 * authentication lifecycle, including showing the {@link BiometricPrompt}, processing the result,
 * and performing the requested action upon success.
 *
 * <p>The activity operates in three primary modes, determined by the launching intent:
 * <ul>
 *     <li><b>Intercept Mode (target exists):</b> The activity acts as a transparent overlay that
 *         intercepts the launch of a locked app. It immediately shows a {@link BiometricPrompt},
 *         and upon successful authentication, it launches the original target intent. This mode is
 *         identified by {@link Intent#EXTRA_INTENT} being non {@code null}.
 *     <li><b>Locked Task Mode (target is {@code null}):</b> The activity provides a default UI that
 *         acts as a locked task overlay. It automatically shows a {@link BiometricPrompt} on
 *         creation and when resumed, and allows the user to re-trigger the prompt by tapping the
 *         background. The back button is disabled in this mode. This mode is identified by
 *         {@link Intent#EXTRA_INTENT} being {@code null}.
 *     <li><b>Uninstall Mode:</b> The activity acts as a transparent overlay that intercepts the
 *         uninstallation of an App Lock enabled app. It immediately shows a {@link BiometricPrompt}
 *         and upon successful authentication, it initiates the uninstallation. This mode is
 *         identified by {@link #EXTRA_IS_UNINSTALL} being true.
 * </ul>
 *
 * <p>The activity is designed to be transient and will finish itself under several conditions:
 * <ul>
 *     <li>If the App Lock feature is disabled.</li>
 *     <li>If the initiating intent is missing required data (package name or user ID).</li>
 *     <li>If the target package is already unlocked when the activity starts or becomes unlocked
 *         while the activity is visible (this does not apply to uninstall mode, which always
 *         requires user confirmation).</li>
 *     <li>If critical UI components cannot be initialized.</li>
 *     <li>After a successful authentication.</li>
 * </ul>
 *
 * <p>This activity should only be started by the system.
 *
 * @hide
 */
public final class LockedAppActivity extends Activity {

    private static final String TAG = "LockedAppActivity";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.DEBUG);
    private static final String SYSTEM_PACKAGE_NAME = "android";

    public static final String EXTRA_IS_UNINSTALL = "com.android.internal.app.extra.IS_UNINSTALL";
    public static final String EXTRA_VERSIONED_PACKAGE =
            "com.android.internal.app.extra.VERSIONED_PACKAGE";
    public static final String EXTRA_UNINSTALL_FLAGS =
            "com.android.internal.app.extra.UNINSTALL_FLAGS";
    public static final String EXTRA_STATUS_RECEIVER =
            "com.android.internal.app.extra.STATUS_RECEIVER";

    @Nullable
    private static Injector sInjector;

    private final Injector mInjector = sInjector == null ? new Injector() : sInjector;

    private final AppLockInternal mAppLockInternal = LocalServices.getService(
            AppLockInternal.class);
    private final OnBackInvokedCallback mOnBackInvokedCallback = () -> {
        // Do nothing.
    };
    private final BiometricPrompt.AuthenticationCallback mAuthenticationCallback =
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    if (DEBUG) {
                        Slog.d(TAG, "Authentication succeeded");
                    }
                    mIsBiometricPromptShowing = false;
                    if (mIsUninstall) {
                        mInjector.getPackageManager(LockedAppActivity.this).getPackageInstaller()
                                .uninstall(mUninstallVersionedPackage, mUninstallFlags,
                                mUninstallStatusReceiver);
                        finish();
                        return;
                    }
                    mAppLockInternal.setAppLockEnabledPackageSuccessfullyAuthenticated(
                            mPackageName, mUserId);
                    completeUnlockAndFinish();
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    Slog.w(TAG, "Authentication error: " + errorCode + " " + errString);
                    mIsBiometricPromptShowing = false;
                    // In uninstall mode, send uninstall aborted status and finish.
                    if (mIsUninstall) {
                        sendUninstallFailure(PackageInstaller.STATUS_FAILURE_ABORTED,
                                "App lock user authentication failed");
                        finish();
                    }

                    // In intercept mode, finish the activity to unblock the UI.
                    if (isInterceptMode()) {
                        finish();
                    }
                }
            };

    private CancellationSignal mCancellationSignal;
    private AppLockLockedStateListener mPackageLockedStateListener;

    private boolean mIsPackageUnlocked;
    private boolean mIsBiometricPromptShowing;
    /**
     * Tracks whether a request to show the biometric prompt is waiting for window focus. This flag
     * is set by lifecycle events (e.g., onResume) or user interaction (e.g., clicking the
     * background) and is cleared once the window gains focus and the prompt is shown.
     */
    private boolean mIsPromptPending;
    /**
     * Tracks whether the target intent has already been sent in intercept mode to avoid duplicate
     * launches.
     */
    private final AtomicBoolean mTargetIntentSent = new AtomicBoolean(false);

    // Member variables initialized in onCreate for performance.
    private String mPackageName;
    private int mUserId;
    private CharSequence mPackageLabel;
    @Nullable
    private IntentSender mTarget;
    @Nullable
    private Bitmap mPackageLogo;
    private boolean mIsUninstall;
    private VersionedPackage mUninstallVersionedPackage;
    private int mUninstallFlags;
    private IntentSender mUninstallStatusReceiver;

    /**
     * Creates an {@link Intent} to launch {@link LockedAppActivity}.
     *
     * <p>This activity operates in two modes, determined by the {@code target} parameter:
     * <ul>
     *     <li><b>Intercept Mode:</b> If a non-null {@code target} is provided, the activity acts as
     *         a transparent overlay to intercept the launch of a locked app. Upon successful
     *         authentication, it sends the {@code target} intent.
     *     <li><b>Locked Task Mode:</b> If {@code target} is {@code null}, the activity provides a
     *         default UI and is intended for use as a locked task overlay.
     * </ul>
     *
     * @param packageName the name of the package to unlock.
     * @param userId      the user ID for which the package is locked.
     * @param target      the {@link IntentSender} to be triggered after successful authentication.
     *                    If non-null, the activity runs in intercept mode. If null, it runs in
     *                    locked task mode.
     * @return a configured {@link Intent} to start {@link LockedAppActivity}.
     */
    public static Intent createLockedAppActivityIntent(@NonNull String packageName,
            int userId, @Nullable IntentSender target) {
        Objects.requireNonNull(packageName);

        return new Intent()
                .setClassName(SYSTEM_PACKAGE_NAME, LockedAppActivity.class.getName())
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(Intent.EXTRA_INTENT, target)
                .setFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    /**
     * Creates an {@link Intent} to launch {@link LockedAppActivity} for the purpose of
     * authenticating an uninstall request.
     *
     * Upon successful authentication, the activity will trigger the uninstallation using the
     * provided flags and status receiver.
     *
     * @param versionedPackage The package to be uninstalled.
     * @param userId           The user ID for which the package is installed.
     * @param uninstallFlags   The flags to be used for the uninstallation.
     * @param statusReceiver   The {@link IntentSender} to receive the uninstallation status.
     * @return a configured {@link Intent} to start {@link LockedAppActivity}.
     */
    public static Intent createLockedAppActivityUninstallIntent(
            @NonNull VersionedPackage versionedPackage, int userId, int uninstallFlags,
            @NonNull IntentSender statusReceiver) {
        Objects.requireNonNull(versionedPackage);
        Objects.requireNonNull(statusReceiver);

        return createLockedAppActivityIntent(
                versionedPackage.getPackageName(), userId, /* target= */ null)
                .putExtra(EXTRA_IS_UNINSTALL, true)
                .putExtra(EXTRA_VERSIONED_PACKAGE, versionedPackage)
                .putExtra(EXTRA_UNINSTALL_FLAGS, uninstallFlags)
                .putExtra(EXTRA_STATUS_RECEIVER, statusReceiver);
    }

    /**
     * Sets the {@link Injector} for testing purposes.
     *
     * <p>This method allows replacing the default injector with a mock implementation to facilitate
     * testing of this activity. This should only be used in debuggable builds.
     *
     * @param injector the {@link Injector} to use for testing, or {@code null} to reset to the
     *                 default.
     * @throws SecurityException if called in a non-debuggable build.
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
        // Initialize member variables from the intent first.
        initStateFromIntent();

        // The theme must be applied before super.onCreate() to ensure the window is created with
        // the correct style.
        applyTheme();

        super.onCreate(savedInstanceState);

        // Set up the UI based on the activity's mode.
        if (!setupUi()) {
            return;
        }

        // Precondition: Check App Lock feature flags.
        if (!android.security.Flags.appLockApis() || !android.security.Flags.appLockCore()) {
            Slog.w(TAG, "App Lock implementation is not enabled, finishing");
            finish();
            return;
        }

        // Check for valid Intent.
        if (mPackageName == null) {
            Slog.w(TAG, "Package name is null, finishing");
            finish();
            return;
        }
        if (mUserId == UserHandle.USER_NULL) {
            Slog.w(TAG, "No userId, finishing");
            finish();
            return;
        }

        // In uninstall mode, we always want to confirm with the user.
        if (!mIsUninstall && finishIfUnlocked(mPackageName, mUserId)) {
            return;
        }

        mPackageLabel = getPackageLabel(mPackageName);
        if (mPackageLabel == null) {
            Slog.e(TAG, "Package label for " + mPackageName + " is null, finishing");
            finish();
            return;
        }
        mPackageLogo = convertDrawableToBitmap(getPackageLogo(mPackageName));

        if (!mIsUninstall) {
            // Register locked state listener. This is unnecessary for the uninstall flow
            // because we do not unlock the package upon successful authentication.
            mPackageLockedStateListener = new AppLockLockedStateListener(this, mPackageName,
                    mUserId, mTarget);
            mAppLockInternal.registerPackageLockedStateListener(mPackageLockedStateListener);
        }

        if (isInterceptMode() || mIsUninstall) {
            // In intercept and uninstall mode, show the BiometricPrompt immediately.
            showBiometricPrompt();
        } else {
            // In locked task mode, disable the back button to prevent bypassing the
            // BiometricPrompt. The BiometricPrompt will appear when the activity resumes in
            // onResume().
            mInjector.getOnBackInvokedDispatcher(this).registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, mOnBackInvokedCallback);
        }
    }

    /**
     * Initializes member variables by parsing the launching intent. This method should be called at
     * the beginning of {@link #onCreate(Bundle)}.
     */
    private void initStateFromIntent() {
        final Intent intent = getIntent();
        mPackageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        mUserId = intent.getIntExtra(Intent.EXTRA_USER_ID, UserHandle.USER_NULL);
        mTarget = mInjector.getIntentSender(intent);

        mIsUninstall = intent.getBooleanExtra(EXTRA_IS_UNINSTALL, /* default= */ false);
        if (mIsUninstall) {
            mUninstallVersionedPackage = intent.getParcelableExtra(EXTRA_VERSIONED_PACKAGE,
                    VersionedPackage.class);
            mUninstallFlags = intent.getIntExtra(EXTRA_UNINSTALL_FLAGS, /* default= */ 0);
            mUninstallStatusReceiver = mInjector.getUninstallStatusReceiver(intent);

            if (mUninstallVersionedPackage == null || mUninstallStatusReceiver == null) {
                Slog.e(TAG, "Missing extras for uninstall: EXTRA_VERSIONED_PACKAGE or"
                        + " EXTRA_STATUS_RECEIVER, finishing");
                finish();
                return;
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "In " + (mIsUninstall ? "uninstall"
                    : (isInterceptMode() ? "intercept" : "locked task")) + " mode");
        }
    }

    /**
     * Determines and applies the appropriate theme for the activity based on its mode. This must be
     * called before {@code super.onCreate()}.
     * <ul>
     *     <li>In intercept and uninstall mode, a transparent panel theme is used to overlay the
     * locked app.</li>
     *     <li>In locked task mode, a standard theme with no action bar is used.</li>
     * </ul>
     */
    private void applyTheme() {
        mInjector.setTheme(this, (isInterceptMode() || mIsUninstall)
                ? android.R.style.Theme_DeviceDefault_Panel
                : android.R.style.Theme_DeviceDefault_NoActionBar);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The UI must be re-initialized on configuration changes, such as when moving to a
        // different display.
        setupUi();
    }

    /**
     * Configures the user interface based on the current mode (intercept, uninstall or locked task)
     * . This must be called after {@code super.onCreate()} and in
     * {@code super.onConfigurationChanged()}.
     *
     * @return {@code true} if the UI was successfully configured, {@code false} otherwise.
     */
    private boolean setupUi() {
        if (isInterceptMode() || mIsUninstall) {
            // In intercept and uninstall mode, ensure the activity is translucent.
            mInjector.setTranslucent(this, true);
            return true;
        }
        return setupLockedTaskModeUi();
    }

    private boolean setupLockedTaskModeUi() {
        // Show a default UI.
        mInjector.setContentView(this, R.layout.locked_app_activity_layout);

        // Set up the root view click listener for the BiometricPrompt.
        final View rootView = mInjector.findViewById(this, android.R.id.content);
        if (rootView == null) {
            Slog.e(TAG, "Root view not found in locked task mode, finishing");
            finish();
            return false;
        }
        rootView.setOnClickListener(v -> {
            if (DEBUG) {
                Slog.d(TAG, "Root view clicked in locked task mode, requesting to show biometric"
                        + " prompt if needed");
            }
            requestShowBiometricPromptForLockedTask();
        });

        // Set up the external display message.
        final int displayId = mInjector.getDisplayId(this);
        // An external display is any valid display that is not the primary one.
        final boolean isDisplayedOnExternalDisplay = displayId != Display.INVALID_DISPLAY
                && displayId != Display.DEFAULT_DISPLAY;
        final TextView externalDisplayMessage = (TextView) mInjector.findViewById(this,
                R.id.locked_app_activity_external_display_message_id);
        if (externalDisplayMessage != null) {
            externalDisplayMessage.setVisibility(isDisplayedOnExternalDisplay
                    ? View.VISIBLE : View.GONE);
        }
        return true;
    }

    // TODO(b/479140664): Refactor modes into constants and use a dedicated field mCurrentMode.
    private boolean isInterceptMode() {
        return mTarget != null;
    }

    /**
     * Shows the biometric prompt when the activity resumes in Locked Task Mode, ensuring it's
     * presented when the user returns to this overlay.
     */
    @Override
    public void onResume() {
        super.onResume();
        if (DEBUG) {
            Slog.d(TAG, "onResume: " + (isInterceptMode() ? "intercept" : "locked task") + " mode");
        }

        if (!isInterceptMode() && !mIsUninstall) {
            if (DEBUG) {
                Slog.d(TAG, "onResume: requesting to show biometric prompt in locked task mode");
            }
            requestShowBiometricPromptForLockedTask();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // In uninstall mode, always require user confirmation. Otherwise, finish if the package is
        // no longer locked upon focus change. The BiometricPrompt should only be shown if the
        // package is locked and the activity has window focus.
        if (mPackageName == null) {
            Slog.w(TAG, "Package name is null in onWindowFocusChanged, finishing");
            finish();
            return;
        }

        if (!mIsUninstall && finishIfUnlocked(mPackageName, mUserId)) {
            return;
        }

        if (hasFocus) {
            maybeShowBiometricPromptForLockedTask();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Clean up listeners to prevent memory leaks.
        if (mPackageLockedStateListener != null) {
            mAppLockInternal.unregisterPackageLockedStateListener(mPackageLockedStateListener);
        }
        // In locked task mode, unregister the back button listener.
        if (!isInterceptMode()) {
            mInjector.getOnBackInvokedDispatcher(this).unregisterOnBackInvokedCallback(
                    mOnBackInvokedCallback);
        }
    }

    /**
     * Checks if the package is currently locked. If it is not locked or if an error occurs, this
     * method will finish the activity.
     *
     * @return {@code true} if the activity is finishing, {@code false} otherwise.
     */
    private boolean finishIfUnlocked(String packageName, int userId) {
        if (mIsPackageUnlocked || !mAppLockInternal.isPackageLocked(packageName, userId)) {
            if (DEBUG) {
                Slog.d(TAG, "Package is unlocked, finishing");
            }
            mIsPackageUnlocked = true;
            finish();
            return true;
        }

        return false;
    }

    /**
     * Sets a pending request to show the biometric prompt for the locked task mode and then
     * attempts to show it.
     *
     * <p>This method is called from lifecycle events (e.g., {@link #onResume()}) or user
     * interactions (e.g., clicking the background) that should trigger the biometric prompt. It
     * marks that a prompt is pending and then calls
     * {@link #maybeShowBiometricPromptForLockedTask()} to check if the conditions are currently met
     * to show it.
     */
    private void requestShowBiometricPromptForLockedTask() {
        mIsPromptPending = true;
        maybeShowBiometricPromptForLockedTask();
    }

    /**
     * Attempts to show the biometric prompt for the locked task mode if a request is pending and
     * the activity is in a state where it can reliably interact with the user (resumed and
     * focused).
     *
     * <p>This method acts as a gatekeeper to ensure that the prompt is only presented when the
     * activity is fully visible and ready for interaction, preventing it from appearing during
     * transient states like device sleep transitions.
     */
    private void maybeShowBiometricPromptForLockedTask() {
        if (isInterceptMode() || mIsUninstall) {
            if (DEBUG) {
                Slog.d(TAG, "maybeShowBiometricPromptForLockedTask: intercept or uninstall mode,"
                        + " returning");
            }
            return;
        }

        if (!mIsPromptPending || !isResumed() || !mInjector.hasWindowFocus(this)) {
            if (DEBUG) {
                Slog.d(TAG, "maybeShowBiometricPromptForLockedTask: not ready for prompt,"
                        + " returning");
            }
            return;
        }

        mIsPromptPending = false;
        if (mIsBiometricPromptShowing) {
            if (DEBUG) {
                Slog.d(TAG, "maybeShowBiometricPromptForLockedTask: prompt is already shown,"
                        + " returning");
            }
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "maybeShowBiometricPromptForLockedTask: showing biometric prompt");
        }
        showBiometricPrompt();
    }

    /**
     * Builds and displays the {@link BiometricPrompt} to the user.
     *
     * <p>The {@link BiometricPrompt} is configured with the application's label and icon.
     */
    private void showBiometricPrompt() {
        // TODO(b/459376236): Handle coexistence with Identity Check.
        final BiometricPrompt.Builder biometricPromptBuilder = mInjector.getBiometricPromptBuilder(
                        this)
                .setTitle(getString(R.string.biometric_dialog_default_title))
                .setDescription(getString(R.string.locked_app_biometric_prompt_description))
                .setLogoDescription(mPackageLabel.toString())
                .setAllowedAuthenticators(
                        Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL);
        if (mPackageLogo != null) {
            biometricPromptBuilder.setLogoBitmap(mPackageLogo);
        }
        final BiometricPrompt biometricPrompt = biometricPromptBuilder.build();
        mCancellationSignal = new CancellationSignal();

        biometricPrompt.authenticate(mCancellationSignal, getMainExecutor(),
                mAuthenticationCallback);
        mIsBiometricPromptShowing = true;
    }

    /**
     * Retrieves the user-facing label for the given package.
     *
     * @return the application label, or {@code null} if the package is not found.
     */
    private CharSequence getPackageLabel(String packageName) {
        try {
            final PackageManager packageManager = mInjector.getPackageManager(this);
            return packageManager.getApplicationInfo(packageName, /* flags= */ 0)
                    .loadLabel(packageManager);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Package " + packageName + " not found", e);
            return null;
        }
    }

    /**
     * Retrieves the icon for the provided application.
     *
     * @return the application icon, or {@code null} if it cannot be found.
     */
    private Drawable getPackageLogo(String packageName) {
        try {
            final PackageManager packageManager = mInjector.getPackageManager(this);
            return packageManager.getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Package " + packageName + " not found", e);
            return null;
        }
    }

    private void sendUninstallFailure(int status, String message) {
        if (mUninstallStatusReceiver == null) {
            return;
        }
        try {
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS, status);
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message);
            mUninstallStatusReceiver.sendIntent(this, /* code= */ 0, fillIn,
                    /* onFinished= */ null, /* handler= */ null);
        } catch (IntentSender.SendIntentException e) {
            Slog.e(TAG, "Unable to send uninstall failure status", e);
        }
    }


    /**
     * A {@link PackageLockedStateListener} that finishes the {@link LockedAppActivity} if the
     * target package becomes unlocked while the activity is active.
     */
    private static class AppLockLockedStateListener implements PackageLockedStateListener {
        private final LockedAppActivity mActivity;
        private final String mPackageName;
        private final int mUserId;
        private final IntentSender mTarget;

        AppLockLockedStateListener(LockedAppActivity activity, String packageName, int userId,
                IntentSender target) {
            mActivity = activity;
            mPackageName = packageName;
            mUserId = userId;
            mTarget = target;
        }

        @Override
        public void onPackageLockedStateChanged(@NonNull String packageName, int userId,
                boolean locked) {
            if (!locked && mPackageName.equals(packageName) && mUserId == userId) {
                if (DEBUG) {
                    Slog.d(TAG, "onPackageLockedStateChanged: package was unlocked, so finishing");
                }
                if (mActivity.mCancellationSignal != null) {
                    mActivity.mCancellationSignal.cancel();
                }
                mActivity.completeUnlockAndFinish();
            }
        }
    }

    /**
     * Finishes the activity and, if in intercept mode, sends the original target intent.
     * This method ensures that the target intent is sent only once, even if called multiple times
     * (e.g., from both authentication success and a locked state listener).
     */
    private void completeUnlockAndFinish() {
        if (isInterceptMode()) {
            // compareAndSet atomically checks if the value is currently 'false' (expected) and, if
            // so, updates it to 'true' (update). It returns 'true' if the update was successful,
            // ensuring the intent is sent only once.
            if (mTargetIntentSent.compareAndSet(/* expectedValue= */ false, /* newValue= */ true)) {
                mInjector.sendTargetIntent(this, mTarget);
            }
        }
        finish();
    }

    /**
     * Converts a {@link Drawable} to a {@link Bitmap}.
     *
     * <p>This method handles both {@link BitmapDrawable} and other {@link Drawable} types. If the
     * provided {@link Drawable} is null, this method returns null.
     *
     * <p>Copied from {@link BiometricPrompt#convertDrawableToBitmap(Drawable)}.
     *
     * @param drawable the {@link Drawable} to convert.
     * @return the converted {@link Bitmap}, or {@code null} if the input was null.
     */
    @VisibleForTesting
    public static Bitmap convertDrawableToBitmap(@Nullable Drawable drawable) {
        if (drawable == null) {
            return null;
        }

        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        Bitmap bitmap;
        if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    /**
     * An injector class for dependency injection, allowing for easier testing of LockedAppActivity.
     */
    @VisibleForTesting
    public static class Injector {
        /**
         * Default constructor for the injector.
         */
        @VisibleForTesting
        public Injector() {
            // For testing only.
        }

        /**
         * Sets the theme for the given activity.
         *
         * @param activity the activity to set the theme for.
         * @param resId    the resource ID of the theme to set.
         */
        public void setTheme(Activity activity, int resId) {
            activity.setTheme(resId);
        }

        /**
         * Sets the translucent property for the given activity.
         *
         * @param activity    the activity to set the translucent property for.
         * @param translucent whether the activity should be translucent.
         */
        public void setTranslucent(Activity activity, boolean translucent) {
            activity.setTranslucent(translucent);
        }

        /**
         * Sets the content view for the given activity.
         *
         * @param activity    the activity to set the content view for.
         * @param layoutResId the resource ID of the layout to set.
         */
        public void setContentView(Activity activity, int layoutResId) {
            activity.setContentView(layoutResId);
        }

        /**
         * Returns whether the activity's window currently has focus.
         *
         * @param activity the activity to check for window focus.
         * @return {@code true} if the window has focus, {@code false} otherwise.
         */
        public boolean hasWindowFocus(Activity activity) {
            return activity.hasWindowFocus();
        }

        /**
         * Returns the view with the given ID for the given activity.
         *
         * @param activity the activity to get the view from.
         * @param id       the ID of the view to find.
         * @return the view, or {@code null} if it cannot be found.
         */
        @Nullable
        public View findViewById(Activity activity, int id) {
            return activity.findViewById(id);
        }

        /**
         * Returns the ID of the {@link Display} for the given activity.
         *
         * @param activity the activity to get the display from.
         * @return the display ID, or {@link Display#INVALID_DISPLAY} if there is no display.
         */
        public int getDisplayId(Activity activity) {
            final Display display = activity.getDisplay();
            return display != null ? display.getDisplayId() : Display.INVALID_DISPLAY;
        }

        /**
         * Returns the {@link PackageManager} for the given activity.
         *
         * @param activity the activity to get the package manager from.
         * @return the package manager.
         */
        public PackageManager getPackageManager(Activity activity) {
            return activity.getPackageManager();
        }

        /**
         * Returns the {@link OnBackInvokedDispatcher} for the given activity.
         *
         * @param activity the activity to get the dispatcher from.
         * @return the on-back-invoked dispatcher.
         */
        public OnBackInvokedDispatcher getOnBackInvokedDispatcher(Activity activity) {
            return activity.getOnBackInvokedDispatcher();
        }

        /**
         * Returns a new {@link BiometricPrompt.Builder} for the given activity.
         *
         * @param activity the activity to create the builder for.
         * @return a new biometric prompt builder.
         */
        public BiometricPrompt.Builder getBiometricPromptBuilder(Activity activity) {
            return new BiometricPrompt.Builder(activity);
        }

        /**
         * Retrieves the {@link IntentSender} from the given intent.
         *
         * @param intent the intent to extract the sender from.
         * @return the intent sender, or {@code null} if not present.
         */
        public IntentSender getIntentSender(Intent intent) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, IntentSender.class);
        }

        /**
         * Retrieves the {@link IntentSender} from the given Intent to send uninstall status.
         *
         * @param intent the intent to extract the sender from.
         * @return the intent sender, or {@code null} if not present.
         */
        public IntentSender getUninstallStatusReceiver(Intent intent) {
            return intent.getParcelableExtra(EXTRA_STATUS_RECEIVER, IntentSender.class);
        }

        /**
         * Sends the target intent after successful authentication.
         *
         * @param activity the activity from which to send the intent.
         * @param target   the {@link IntentSender} to trigger.
         */
        public void sendTargetIntent(Activity activity, @NonNull IntentSender target) {
            Objects.requireNonNull(target);

            if (DEBUG) {
                Slog.d(TAG, "Sending target intent: " + target);
            }
            try {
                // Use MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE to allow PendingIntents to
                // be launched even if the creator app is in the background.
                final ActivityOptions activityOptions = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE);
                activity.startIntentSenderForResult(target, /* requestCode= */ -1,
                        /* fillIntIntent= */ null, /* flagsMask= */ 0, /* flagsValues= */ 0,
                        /* extraFlags= */ 0, activityOptions.toBundle());
            } catch (IntentSender.SendIntentException e) {
                Slog.w(TAG, "Unable to send intent", e);
            }
        }
    }
}

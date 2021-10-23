/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.accessibility;

import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;

import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getTargets;
import static com.android.internal.util.ArrayUtils.convertToLongArray;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.internal.util.function.pooled.PooledLambda;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Class to help manage the accessibility shortcut key
 */
public class AccessibilityShortcutController {
    private static final String TAG = "AccessibilityShortcutController";

    // Placeholder component names for framework features
    public static final ComponentName COLOR_INVERSION_COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "ColorInversion");
    public static final ComponentName DALTONIZER_COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "Daltonizer");
    // TODO(b/147990389): Use MAGNIFICATION_COMPONENT_NAME to replace.
    public static final String MAGNIFICATION_CONTROLLER_NAME =
            "com.android.server.accessibility.MagnificationController";
    public static final ComponentName MAGNIFICATION_COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "Magnification");
    public static final ComponentName ONE_HANDED_COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "OneHandedMode");
    public static final ComponentName REDUCE_BRIGHT_COLORS_COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "ReduceBrightColors");

    // The component name for the sub setting of Accessibility button in Accessibility settings
    public static final ComponentName ACCESSIBILITY_BUTTON_COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "AccessibilityButton");


    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .build();
    private static Map<ComponentName, ToggleableFrameworkFeatureInfo> sFrameworkShortcutFeaturesMap;

    private final Context mContext;
    private final Handler mHandler;
    private AlertDialog mAlertDialog;
    private boolean mIsShortcutEnabled;
    private boolean mEnabledOnLockScreen;
    private int mUserId;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DialogStaus.NOT_SHOWN,
            DialogStaus.SHOWN,
    })
    /** Denotes the user shortcut type. */
    private @interface DialogStaus {
        int NOT_SHOWN = 0;
        int SHOWN  = 1;
    }

    // Visible for testing
    public FrameworkObjectProvider mFrameworkObjectProvider = new FrameworkObjectProvider();

    /**
     * @return An immutable map from placeholder component names to feature
     *         info for toggling a framework feature
     */
    public static Map<ComponentName, ToggleableFrameworkFeatureInfo>
        getFrameworkShortcutFeaturesMap() {
        if (sFrameworkShortcutFeaturesMap == null) {
            Map<ComponentName, ToggleableFrameworkFeatureInfo> featuresMap = new ArrayMap<>(4);
            featuresMap.put(COLOR_INVERSION_COMPONENT_NAME,
                    new ToggleableFrameworkFeatureInfo(
                            Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
                            "1" /* Value to enable */, "0" /* Value to disable */,
                            R.string.color_inversion_feature_name));
            featuresMap.put(DALTONIZER_COMPONENT_NAME,
                    new ToggleableFrameworkFeatureInfo(
                            Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED,
                            "1" /* Value to enable */, "0" /* Value to disable */,
                            R.string.color_correction_feature_name));
            featuresMap.put(ONE_HANDED_COMPONENT_NAME,
                    new ToggleableFrameworkFeatureInfo(
                            Settings.Secure.ONE_HANDED_MODE_ACTIVATED,
                            "1" /* Value to enable */, "0" /* Value to disable */,
                            R.string.one_handed_mode_feature_name));
            featuresMap.put(REDUCE_BRIGHT_COLORS_COMPONENT_NAME,
                    new ToggleableFrameworkFeatureInfo(
                            Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                            "1" /* Value to enable */, "0" /* Value to disable */,
                            R.string.reduce_bright_colors_feature_name));
            sFrameworkShortcutFeaturesMap = Collections.unmodifiableMap(featuresMap);
        }
        return sFrameworkShortcutFeaturesMap;
    }

    public AccessibilityShortcutController(Context context, Handler handler, int initialUserId) {
        mContext = context;
        mHandler = handler;
        mUserId = initialUserId;

        // Keep track of state of shortcut settings
        final ContentObserver co = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags, int userId) {
                if (userId == mUserId) {
                    onSettingsChanged();
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE),
                false, co, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN),
                false, co, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN),
                false, co, UserHandle.USER_ALL);
        setCurrentUser(mUserId);
    }

    public void setCurrentUser(int currentUserId) {
        mUserId = currentUserId;
        onSettingsChanged();
    }

    /**
     * Check if the shortcut is available.
     *
     * @param phoneLocked Whether or not the phone is currently locked.
     *
     * @return {@code true} if the shortcut is available
     */
    public boolean isAccessibilityShortcutAvailable(boolean phoneLocked) {
        return mIsShortcutEnabled && (!phoneLocked || mEnabledOnLockScreen);
    }

    public void onSettingsChanged() {
        final boolean hasShortcutTarget = hasShortcutTarget();
        final ContentResolver cr = mContext.getContentResolver();
        // Enable the shortcut from the lockscreen by default if the dialog has been shown
        final int dialogAlreadyShown = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, DialogStaus.NOT_SHOWN,
                mUserId);
        mEnabledOnLockScreen = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN,
                dialogAlreadyShown, mUserId) == 1;
        mIsShortcutEnabled = hasShortcutTarget;
    }

    /**
     * Called when the accessibility shortcut is activated
     */
    public void performAccessibilityShortcut() {
        Slog.d(TAG, "Accessibility shortcut activated");
        final ContentResolver cr = mContext.getContentResolver();
        final int userId = ActivityManager.getCurrentUser();
        final int dialogAlreadyShown = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, DialogStaus.NOT_SHOWN,
                userId);
        // Play a notification vibration
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if ((vibrator != null) && vibrator.hasVibrator()) {
            // Don't check if haptics are disabled, as we need to alert the user that their
            // way of interacting with the phone may change if they activate the shortcut
            long[] vibePattern = convertToLongArray(
                    mContext.getResources().getIntArray(R.array.config_longPressVibePattern));
            vibrator.vibrate(vibePattern, -1, VIBRATION_ATTRIBUTES);
        }

        if (dialogAlreadyShown == 0) {
            // The first time, we show a warning rather than toggle the service to give the user a
            // chance to turn off this feature before stuff gets enabled.
            mAlertDialog = createShortcutWarningDialog(userId);
            if (mAlertDialog == null) {
                return;
            }
            if (!performTtsPrompt(mAlertDialog)) {
                playNotificationTone();
            }
            Window w = mAlertDialog.getWindow();
            WindowManager.LayoutParams attr = w.getAttributes();
            attr.type = TYPE_KEYGUARD_DIALOG;
            w.setAttributes(attr);
            mAlertDialog.show();
            Settings.Secure.putIntForUser(
                    cr, Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, DialogStaus.SHOWN,
                    userId);
        } else {
            playNotificationTone();
            if (mAlertDialog != null) {
                mAlertDialog.dismiss();
                mAlertDialog = null;
            }
            showToast();
            mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext)
                    .performAccessibilityShortcut();
        }
    }

    /**
     * Show toast to alert the user that the accessibility shortcut turned on or off an
     * accessibility service.
     */
    private void showToast() {
        final AccessibilityServiceInfo serviceInfo = getInfoForTargetService();
        if (serviceInfo == null) {
            return;
        }
        final String serviceName = getShortcutFeatureDescription(/* no summary */ false);
        if (serviceName == null) {
            return;
        }
        final boolean requestA11yButton = (serviceInfo.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
        final boolean isServiceEnabled = isServiceEnabled(serviceInfo);
        if (serviceInfo.getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion
                > Build.VERSION_CODES.Q && requestA11yButton && isServiceEnabled) {
            // An accessibility button callback is sent to the target accessibility service.
            // No need to show up a toast in this case.
            return;
        }
        // For accessibility services, show a toast explaining what we're doing.
        String toastMessageFormatString = mContext.getString(isServiceEnabled
                ? R.string.accessibility_shortcut_disabling_service
                : R.string.accessibility_shortcut_enabling_service);
        String toastMessage = String.format(toastMessageFormatString, serviceName);
        Toast warningToast = mFrameworkObjectProvider.makeToastFromText(
                mContext, toastMessage, Toast.LENGTH_LONG);
        warningToast.show();
    }

    private AlertDialog createShortcutWarningDialog(int userId) {
        List<AccessibilityTarget> targets = getTargets(mContext, ACCESSIBILITY_SHORTCUT_KEY);
        if (targets.size() == 0) {
            return null;
        }

        // Avoid non-a11y users accidentally turning shortcut on without reading this carefully.
        // Put "don't turn on" as the primary action.
        final AlertDialog alertDialog = mFrameworkObjectProvider.getAlertDialogBuilder(
                // Use SystemUI context so we pick up any theme set in a vendor overlay
                mFrameworkObjectProvider.getSystemUiContext())
                .setTitle(getShortcutWarningTitle(targets))
                .setMessage(getShortcutWarningMessage(targets))
                .setCancelable(false)
                .setNegativeButton(R.string.accessibility_shortcut_on, null)
                .setPositiveButton(R.string.accessibility_shortcut_off,
                        (DialogInterface d, int which) -> {
                            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                                    Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, "",
                                    userId);

                            // If canceled, treat as if the dialog has never been shown
                            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                    Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN,
                                    DialogStaus.NOT_SHOWN, userId);
                        })
                .setOnCancelListener((DialogInterface d) -> {
                    // If canceled, treat as if the dialog has never been shown
                    Settings.Secure.putIntForUser(mContext.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN,
                            DialogStaus.NOT_SHOWN, userId);
                })
                .create();
        return alertDialog;
    }

    private String getShortcutWarningTitle(List<AccessibilityTarget> targets) {
        if (targets.size() == 1) {
            return mContext.getString(
                    R.string.accessibility_shortcut_single_service_warning_title,
                    targets.get(0).getLabel());
        }
        return mContext.getString(
                R.string.accessibility_shortcut_multiple_service_warning_title);
    }

    private String getShortcutWarningMessage(List<AccessibilityTarget> targets) {
        if (targets.size() == 1) {
            return mContext.getString(
                    R.string.accessibility_shortcut_single_service_warning,
                    targets.get(0).getLabel());
        }

        final StringBuilder sb = new StringBuilder();
        for (AccessibilityTarget target : targets) {
            sb.append(mContext.getString(R.string.accessibility_shortcut_multiple_service_list,
                    target.getLabel()));
        }
        return mContext.getString(R.string.accessibility_shortcut_multiple_service_warning,
                sb.toString());
    }

    private AccessibilityServiceInfo getInfoForTargetService() {
        final ComponentName targetComponentName = getShortcutTargetComponentName();
        if (targetComponentName == null) {
            return null;
        }
        AccessibilityManager accessibilityManager =
                mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext);
        return accessibilityManager.getInstalledServiceInfoWithComponentName(
                targetComponentName);
    }

    private String getShortcutFeatureDescription(boolean includeSummary) {
        final ComponentName targetComponentName = getShortcutTargetComponentName();
        if (targetComponentName == null) {
            return null;
        }
        final ToggleableFrameworkFeatureInfo frameworkFeatureInfo =
                getFrameworkShortcutFeaturesMap().get(targetComponentName);
        if (frameworkFeatureInfo != null) {
            return frameworkFeatureInfo.getLabel(mContext);
        }
        final AccessibilityServiceInfo serviceInfo = mFrameworkObjectProvider
                .getAccessibilityManagerInstance(mContext).getInstalledServiceInfoWithComponentName(
                        targetComponentName);
        if (serviceInfo == null) {
            return null;
        }
        final PackageManager pm = mContext.getPackageManager();
        String label = serviceInfo.getResolveInfo().loadLabel(pm).toString();
        CharSequence summary = serviceInfo.loadSummary(pm);
        if (!includeSummary || TextUtils.isEmpty(summary)) {
            return label;
        }
        return String.format("%s\n%s", label, summary);
    }

    private boolean isServiceEnabled(AccessibilityServiceInfo serviceInfo) {
        AccessibilityManager accessibilityManager =
                mFrameworkObjectProvider.getAccessibilityManagerInstance(mContext);
        return accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK).contains(serviceInfo);
    }

    private boolean hasFeatureLeanback() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private void playNotificationTone() {
        // Use USAGE_ASSISTANCE_ACCESSIBILITY for TVs to ensure that TVs play the ringtone as they
        // have less ways of providing feedback like vibration.
        final int audioAttributesUsage = hasFeatureLeanback()
                ? AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                : AudioAttributes.USAGE_NOTIFICATION_EVENT;

        // Play a notification tone
        final Ringtone tone = mFrameworkObjectProvider.getRingtone(mContext,
                Settings.System.DEFAULT_NOTIFICATION_URI);
        if (tone != null) {
            tone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(audioAttributesUsage)
                    .build());
            tone.play();
        }
    }

    private boolean performTtsPrompt(AlertDialog alertDialog) {
        final String serviceName = getShortcutFeatureDescription(false /* no summary */);
        final AccessibilityServiceInfo serviceInfo = getInfoForTargetService();
        if (TextUtils.isEmpty(serviceName) || serviceInfo == null) {
            return false;
        }
        if ((serviceInfo.flags & AccessibilityServiceInfo
                .FLAG_REQUEST_SHORTCUT_WARNING_DIALOG_SPOKEN_FEEDBACK) == 0) {
            return false;
        }
        final TtsPrompt tts = new TtsPrompt(serviceName);
        alertDialog.setOnDismissListener(dialog -> tts.dismiss());
        return true;
    }

    /**
     * Returns {@code true} if any shortcut targets were assigned to accessibility shortcut key.
     */
    private boolean hasShortcutTarget() {
        // AccessibilityShortcutController is initialized earlier than AccessibilityManagerService.
        // AccessibilityManager#getAccessibilityShortcutTargets may not return correct shortcut
        // targets during boot. Needs to read settings directly here.
        String shortcutTargets = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, mUserId);
        // A11y warning dialog updates settings to empty string, when user disables a11y shortcut.
        // Only fallback to default a11y service, when setting is never updated.
        if (shortcutTargets == null) {
            shortcutTargets = mContext.getString(R.string.config_defaultAccessibilityService);
        }
        return !TextUtils.isEmpty(shortcutTargets);
    }

    /**
     * Gets the component name of the shortcut target.
     *
     * @return The component name, or null if it's assigned by multiple targets.
     */
    private ComponentName getShortcutTargetComponentName() {
        final List<String> shortcutTargets = mFrameworkObjectProvider
                .getAccessibilityManagerInstance(mContext)
                .getAccessibilityShortcutTargets(ACCESSIBILITY_SHORTCUT_KEY);
        if (shortcutTargets.size() != 1) {
            return null;
        }
        return ComponentName.unflattenFromString(shortcutTargets.get(0));
    }

    /**
     * Class to wrap TextToSpeech for shortcut dialog spoken feedback.
     */
    private class TtsPrompt implements TextToSpeech.OnInitListener {
        private static final int RETRY_MILLIS = 1000;

        private final CharSequence mText;

        private int mRetryCount = 3;
        private boolean mDismiss;
        private boolean mLanguageReady = false;
        private TextToSpeech mTts;

        TtsPrompt(String serviceName) {
            mText = mContext.getString(R.string.accessibility_shortcut_spoken_feedback,
                    serviceName);
            mTts = mFrameworkObjectProvider.getTextToSpeech(mContext, this);
        }

        /**
         * Releases the resources used by the TextToSpeech, when dialog dismiss.
         */
        public void dismiss() {
            mDismiss = true;
            mHandler.sendMessage(PooledLambda.obtainMessage(TextToSpeech::shutdown, mTts));
        }

        @Override
        public void onInit(int status) {
            if (status != TextToSpeech.SUCCESS) {
                Slog.d(TAG, "Tts init fail, status=" + Integer.toString(status));
                playNotificationTone();
                return;
            }
            mHandler.sendMessage(PooledLambda.obtainMessage(
                    TtsPrompt::waitForTtsReady, this));
        }

        private void play() {
            if (mDismiss) {
                return;
            }
            final int status = mTts.speak(mText, TextToSpeech.QUEUE_FLUSH, null, null);
            if (status != TextToSpeech.SUCCESS) {
                Slog.d(TAG, "Tts play fail");
                playNotificationTone();
            }
        }

        /**
         * Waiting for tts is ready to speak. Trying again if tts language pack is not available
         * or tts voice data is not installed yet.
         */
        private void waitForTtsReady() {
            if (mDismiss) {
                return;
            }
            if (!mLanguageReady) {
                final int status = mTts.setLanguage(Locale.getDefault());
                // True if language is available and TTS#loadVoice has called once
                // that trigger TTS service to start initialization.
                mLanguageReady = status != TextToSpeech.LANG_MISSING_DATA
                    && status != TextToSpeech.LANG_NOT_SUPPORTED;
            }
            if (mLanguageReady) {
                final Voice voice = mTts.getVoice();
                final boolean voiceDataInstalled = voice != null
                        && voice.getFeatures() != null
                        && !voice.getFeatures().contains(
                                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
                if (voiceDataInstalled) {
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            TtsPrompt::play, this));
                    return;
                }
            }

            if (mRetryCount == 0) {
                Slog.d(TAG, "Tts not ready to speak.");
                playNotificationTone();
                return;
            }
            // Retry if TTS service not ready yet.
            mRetryCount -= 1;
            mHandler.sendMessageDelayed(PooledLambda.obtainMessage(
                    TtsPrompt::waitForTtsReady, this), RETRY_MILLIS);
        }
    }

    /**
     * Immutable class to hold info about framework features that can be controlled by shortcut
     */
    public static class ToggleableFrameworkFeatureInfo {
        private final String mSettingKey;
        private final String mSettingOnValue;
        private final String mSettingOffValue;
        private final int mLabelStringResourceId;
        // These go to the settings wrapper
        private int mIconDrawableId;

        ToggleableFrameworkFeatureInfo(String settingKey, String settingOnValue,
                String settingOffValue, int labelStringResourceId) {
            mSettingKey = settingKey;
            mSettingOnValue = settingOnValue;
            mSettingOffValue = settingOffValue;
            mLabelStringResourceId = labelStringResourceId;
        }

        /**
         * @return The settings key to toggle between two values
         */
        public String getSettingKey() {
            return mSettingKey;
        }

        /**
         * @return The value to write to settings to turn the feature on
         */
        public String getSettingOnValue() {
            return mSettingOnValue;
        }

        /**
         * @return The value to write to settings to turn the feature off
         */
        public String getSettingOffValue() {
            return mSettingOffValue;
        }

        public String getLabel(Context context) {
            return context.getString(mLabelStringResourceId);
        }
    }

    // Class to allow mocking of static framework calls
    public static class FrameworkObjectProvider {
        public AccessibilityManager getAccessibilityManagerInstance(Context context) {
            return AccessibilityManager.getInstance(context);
        }

        public AlertDialog.Builder getAlertDialogBuilder(Context context) {
            final boolean inNightMode = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            final int themeId = inNightMode ? R.style.Theme_DeviceDefault_Dialog_Alert :
                    R.style.Theme_DeviceDefault_Light_Dialog_Alert;
            return new AlertDialog.Builder(context, themeId);
        }

        public Toast makeToastFromText(Context context, CharSequence charSequence, int duration) {
            return Toast.makeText(context, charSequence, duration);
        }

        public Context getSystemUiContext() {
            return ActivityThread.currentActivityThread().getSystemUiContext();
        }

        /**
         * @param ctx A context for TextToSpeech
         * @param listener TextToSpeech initialization callback
         * @return TextToSpeech instance
         */
        public TextToSpeech getTextToSpeech(Context ctx, TextToSpeech.OnInitListener listener) {
            return new TextToSpeech(ctx, listener);
        }

        /**
         * @param ctx context for ringtone
         * @param uri ringtone uri
         * @return Ringtone instance
         */
        public Ringtone getRingtone(Context ctx, Uri uri) {
            return RingtoneManager.getRingtone(ctx, uri);
        }
    }
}

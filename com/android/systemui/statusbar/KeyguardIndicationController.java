/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.android.systemui.DejankUtils.whitelistIpcs;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.IMPORTANT_MSG_MIN_DURATION;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_ALIGNMENT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BATTERY;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_DISCLOSURE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_LOGOUT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_OWNER_INFO;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_RESTING;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_TRUST;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_USER_LOCKED;
import static com.android.systemui.plugins.FalsingManager.LOW_PENALTY;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.ViewClippingUtil;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.Utils;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.KeyguardIndication;
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.NumberFormat;

import javax.inject.Inject;

/**
 * Controls the indications and error messages shown on the Keyguard
 *
 * On AoD, only one message shows with the following priorities:
 *   1. Biometric
 *   2. Transient
 *   3. Charging alignment
 *   4. Battery information
 *
 * On the lock screen, message rotate through different message types.
 *   See {@link KeyguardIndicationRotateTextViewController.IndicationType} for the list of types.
 */
@SysUISingleton
public class KeyguardIndicationController {

    private static final String TAG = "KeyguardIndication";
    private static final boolean DEBUG_CHARGING_SPEED = false;

    private static final int MSG_HIDE_TRANSIENT = 1;
    private static final int MSG_SHOW_ACTION_TO_UNLOCK = 2;
    private static final int MSG_HIDE_BIOMETRIC_MESSAGE = 3;
    private static final long TRANSIENT_BIOMETRIC_ERROR_TIMEOUT = 1300;
    private static final float BOUNCE_ANIMATION_FINAL_Y = 0f;

    private final Context mContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final KeyguardStateController mKeyguardStateController;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private ViewGroup mIndicationArea;
    private KeyguardIndicationTextView mTopIndicationView;
    private KeyguardIndicationTextView mLockScreenIndicationView;
    private final IBatteryStats mBatteryInfo;
    private final SettableWakeLock mWakeLock;
    private final DockManager mDockManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final UserManager mUserManager;
    private final @Main DelayableExecutor mExecutor;
    private final LockPatternUtils mLockPatternUtils;
    private final IActivityManager mIActivityManager;
    private final FalsingManager mFalsingManager;
    private final KeyguardBypassController mKeyguardBypassController;

    protected KeyguardIndicationRotateTextViewController mRotateTextViewController;
    private BroadcastReceiver mBroadcastReceiver;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    private String mRestingIndication;
    private String mAlignmentIndication;
    private CharSequence mTransientIndication;
    private CharSequence mBiometricMessage;
    protected ColorStateList mInitialTextColorState;
    private boolean mVisible;

    private boolean mPowerPluggedIn;
    private boolean mPowerPluggedInWired;
    private boolean mPowerPluggedInWireless;
    private boolean mPowerCharged;
    private boolean mBatteryOverheated;
    private boolean mEnableBatteryDefender;
    private int mChargingSpeed;
    private int mChargingWattage;
    private int mBatteryLevel;
    private boolean mBatteryPresent = true;
    private long mChargingTimeRemaining;
    private String mMessageToShowOnScreenOn;
    private boolean mInited;

    private KeyguardUpdateMonitorCallback mUpdateMonitorCallback;

    private boolean mDozing;
    private final ViewClippingUtil.ClippingParameters mClippingParams =
            new ViewClippingUtil.ClippingParameters() {
                @Override
                public boolean shouldFinish(View view) {
                    return view == mIndicationArea;
                }
            };

    /**
     * Creates a new KeyguardIndicationController and registers callbacks.
     */
    @Inject
    public KeyguardIndicationController(Context context,
            WakeLock.Builder wakeLockBuilder,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DockManager dockManager,
            BroadcastDispatcher broadcastDispatcher,
            DevicePolicyManager devicePolicyManager,
            IBatteryStats iBatteryStats,
            UserManager userManager,
            @Main DelayableExecutor executor,
            FalsingManager falsingManager,
            LockPatternUtils lockPatternUtils,
            IActivityManager iActivityManager,
            KeyguardBypassController keyguardBypassController) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mDevicePolicyManager = devicePolicyManager;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDockManager = dockManager;
        mWakeLock = new SettableWakeLock(
                wakeLockBuilder.setTag("Doze:KeyguardIndication").build(), TAG);
        mBatteryInfo = iBatteryStats;
        mUserManager = userManager;
        mExecutor = executor;
        mLockPatternUtils = lockPatternUtils;
        mIActivityManager = iActivityManager;
        mFalsingManager = falsingManager;
        mKeyguardBypassController = keyguardBypassController;

    }

    /** Call this after construction to finish setting up the instance. */
    public void init() {
        if (mInited) {
            return;
        }
        mInited = true;

        mDockManager.addAlignmentStateListener(
                alignState -> mHandler.post(() -> handleAlignStateChanged(alignState)));
        mKeyguardUpdateMonitor.registerCallback(getKeyguardCallback());
        mKeyguardUpdateMonitor.registerCallback(mTickReceiver);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);

        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());
    }

    public void setIndicationArea(ViewGroup indicationArea) {
        mIndicationArea = indicationArea;
        mTopIndicationView = indicationArea.findViewById(R.id.keyguard_indication_text);
        mLockScreenIndicationView = indicationArea.findViewById(
            R.id.keyguard_indication_text_bottom);
        mInitialTextColorState = mTopIndicationView != null
                ? mTopIndicationView.getTextColors() : ColorStateList.valueOf(Color.WHITE);
        mRotateTextViewController = new KeyguardIndicationRotateTextViewController(
            mLockScreenIndicationView,
            mExecutor,
            mStatusBarStateController);
        updateIndication(false /* animate */);
        updateDisclosure();
        if (mBroadcastReceiver == null) {
            // Update the disclosure proactively to avoid IPC on the critical path.
            mBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateDisclosure();
                }
            };
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_USER_REMOVED);
            mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, intentFilter);
        }
    }

    private void handleAlignStateChanged(int alignState) {
        String alignmentIndication = "";
        if (alignState == DockManager.ALIGN_STATE_POOR) {
            alignmentIndication =
                    mContext.getResources().getString(R.string.dock_alignment_slow_charging);
        } else if (alignState == DockManager.ALIGN_STATE_TERRIBLE) {
            alignmentIndication =
                    mContext.getResources().getString(R.string.dock_alignment_not_charging);
        }
        if (!alignmentIndication.equals(mAlignmentIndication)) {
            mAlignmentIndication = alignmentIndication;
            updateIndication(false);
        }
    }

    /**
     * Gets the {@link KeyguardUpdateMonitorCallback} instance associated with this
     * {@link KeyguardIndicationController}.
     *
     * <p>Subclasses may override this method to extend or change the callback behavior by extending
     * the {@link BaseKeyguardCallback}.
     *
     * @return A KeyguardUpdateMonitorCallback. Multiple calls to this method <b>must</b> return the
     * same instance.
     */
    protected KeyguardUpdateMonitorCallback getKeyguardCallback() {
        if (mUpdateMonitorCallback == null) {
            mUpdateMonitorCallback = new BaseKeyguardCallback();
        }
        return mUpdateMonitorCallback;
    }

    /**
     * Doesn't include disclosure (also a persistent indication) which gets triggered separately.
     *
     * This method also doesn't update transient messages like biometrics since those messages
     * are also updated separately.
     */
    private void updatePersistentIndications(boolean animate, int userId) {
        updateOwnerInfo();
        updateBattery(animate);
        updateUserLocked(userId);
        updateTrust(userId, getTrustGrantedIndication(), getTrustManagedIndication());
        updateAlignment();
        updateLogoutView();
        updateResting();
    }

    private void updateDisclosure() {
        // avoid calling this method since it has an IPC
        if (whitelistIpcs(this::isOrganizationOwnedDevice)) {
            final CharSequence organizationName = getOrganizationOwnedDeviceOrganizationName();
            final CharSequence disclosure = getDisclosureText(organizationName);
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_DISCLOSURE,
                    new KeyguardIndication.Builder()
                            .setMessage(disclosure)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    /* updateImmediately */ false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_DISCLOSURE);
        }

        updateResting();
    }

    private CharSequence getDisclosureText(@Nullable CharSequence organizationName) {
        final Resources packageResources = mContext.getResources();
        if (organizationName == null) {
            return packageResources.getText(R.string.do_disclosure_generic);
        } else if (mDevicePolicyManager.isDeviceManaged()
                && mDevicePolicyManager.getDeviceOwnerType(
                mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                == DEVICE_OWNER_TYPE_FINANCED) {
            return packageResources.getString(R.string.do_financed_disclosure_with_name,
                    organizationName);
        } else {
            return packageResources.getString(R.string.do_disclosure_with_name,
                    organizationName);
        }
    }

    private void updateOwnerInfo() {
        String info = mLockPatternUtils.getDeviceOwnerInfo();
        if (info == null) {
            // Use the current user owner information if enabled.
            final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                    KeyguardUpdateMonitor.getCurrentUser());
            if (ownerInfoEnabled) {
                info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
            }
        }
        if (!TextUtils.isEmpty(info)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_OWNER_INFO,
                    new KeyguardIndication.Builder()
                            .setMessage(info)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_OWNER_INFO);
        }
    }

    private void updateBattery(boolean animate) {
        if (mPowerPluggedIn || mEnableBatteryDefender) {
            String powerIndication = computePowerIndication();
            if (DEBUG_CHARGING_SPEED) {
                powerIndication += ",  " + (mChargingWattage / 1000) + " mW";
            }

            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_BATTERY,
                    new KeyguardIndication.Builder()
                            .setMessage(powerIndication)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    animate);
        } else {
            // don't show the charging information if device isn't plugged in
            mRotateTextViewController.hideIndication(INDICATION_TYPE_BATTERY);
        }
    }

    private void updateUserLocked(int userId) {
        if (!mKeyguardUpdateMonitor.isUserUnlocked(userId)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_USER_LOCKED,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getResources().getText(
                                    com.android.internal.R.string.lockscreen_storage_locked))
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_USER_LOCKED);
        }
    }

    private void updateBiometricMessage() {
        if (!TextUtils.isEmpty(mBiometricMessage)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_BIOMETRIC_MESSAGE,
                    new KeyguardIndication.Builder()
                            .setMessage(mBiometricMessage)
                            .setMinVisibilityMillis(IMPORTANT_MSG_MIN_DURATION)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    true
            );
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_BIOMETRIC_MESSAGE);
        }

        if (mDozing) {
            updateIndication(false);
        }
    }

    private void updateTransient() {
        if (!TextUtils.isEmpty(mTransientIndication)) {
            mRotateTextViewController.showTransient(mTransientIndication);
        } else {
            mRotateTextViewController.hideTransient();
        }

        if (mDozing) {
            updateIndication(false);
        }
    }

    private void updateTrust(int userId, CharSequence trustGrantedIndication,
            CharSequence trustManagedIndication) {
        if (!TextUtils.isEmpty(trustGrantedIndication)
                && mKeyguardUpdateMonitor.getUserHasTrust(userId)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_TRUST,
                    new KeyguardIndication.Builder()
                            .setMessage(trustGrantedIndication)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    false);
        } else if (!TextUtils.isEmpty(trustManagedIndication)
                && mKeyguardUpdateMonitor.getUserTrustIsManaged(userId)
                && !mKeyguardUpdateMonitor.getUserHasTrust(userId)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_TRUST,
                    new KeyguardIndication.Builder()
                            .setMessage(trustManagedIndication)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_TRUST);
        }
    }

    private void updateAlignment() {
        if (!TextUtils.isEmpty(mAlignmentIndication)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_ALIGNMENT,
                    new KeyguardIndication.Builder()
                            .setMessage(mAlignmentIndication)
                            .setTextColor(ColorStateList.valueOf(
                                    mContext.getColor(R.color.misalignment_text_color)))
                            .build(),
                    true);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_ALIGNMENT);
        }
    }

    private void updateResting() {
        if (!TextUtils.isEmpty(mRestingIndication)
                && !mRotateTextViewController.hasIndications()) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_RESTING,
                    new KeyguardIndication.Builder()
                            .setMessage(mRestingIndication)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_RESTING);
        }
    }

    private void updateLogoutView() {
        final boolean shouldShowLogout = mKeyguardUpdateMonitor.isLogoutEnabled()
                && KeyguardUpdateMonitor.getCurrentUser() != UserHandle.USER_SYSTEM;
        if (shouldShowLogout) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_LOGOUT,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getResources().getString(
                                    com.android.internal.R.string.global_action_logout))
                            .setTextColor(Utils.getColorAttr(
                                    mContext, com.android.internal.R.attr.textColorOnAccent))
                            .setBackground(mContext.getDrawable(
                                    com.android.systemui.R.drawable.logout_button_background))
                            .setClickListener((view) -> {
                                if (mFalsingManager.isFalseTap(LOW_PENALTY)) {
                                    return;
                                }
                                int currentUserId = KeyguardUpdateMonitor.getCurrentUser();
                                try {
                                    mIActivityManager.switchUser(UserHandle.USER_SYSTEM);
                                    mIActivityManager.stopUser(currentUserId, true /* force */,
                                            null);
                                } catch (RemoteException re) {
                                    Log.e(TAG, "Failed to logout user", re);
                                }
                            })
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_LOGOUT);
        }
    }

    private boolean isOrganizationOwnedDevice() {
        return mDevicePolicyManager.isDeviceManaged()
                || mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile();
    }

    @Nullable
    private CharSequence getOrganizationOwnedDeviceOrganizationName() {
        if (mDevicePolicyManager.isDeviceManaged()) {
            return mDevicePolicyManager.getDeviceOwnerOrganizationName();
        } else if (mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile()) {
            return getWorkProfileOrganizationName();
        }
        return null;
    }

    private CharSequence getWorkProfileOrganizationName() {
        final int profileId = getWorkProfileUserId(UserHandle.myUserId());
        if (profileId == UserHandle.USER_NULL) {
            return null;
        }
        return mDevicePolicyManager.getOrganizationNameForUser(profileId);
    }

    private int getWorkProfileUserId(int userId) {
        for (final UserInfo userInfo : mUserManager.getProfiles(userId)) {
            if (userInfo.isManagedProfile()) {
                return userInfo.id;
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * Sets the visibility of keyguard bottom area, and if the indications are updatable.
     *
     * @param visible true to make the area visible and update the indication, false otherwise.
     */
    public void setVisible(boolean visible) {
        mVisible = visible;
        mIndicationArea.setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            // If this is called after an error message was already shown, we should not clear it.
            // Otherwise the error message won't be shown
            if (!mHandler.hasMessages(MSG_HIDE_TRANSIENT)) {
                hideTransientIndication();
            }
            updateIndication(false);
        } else if (!visible) {
            // If we unlock and return to keyguard quickly, previous error should not be shown
            hideTransientIndication();
        }
    }

    /**
     * Sets the indication that is shown if nothing else is showing.
     */
    public void setRestingIndication(String restingIndication) {
        mRestingIndication = restingIndication;
        updateIndication(false);
    }

    /**
     * Returns the indication text indicating that trust has been granted.
     *
     * @return {@code null} or an empty string if a trust indication text should not be shown.
     */
    @VisibleForTesting
    String getTrustGrantedIndication() {
        return mContext.getString(R.string.keyguard_indication_trust_unlocked);
    }

    /**
     * Sets if the device is plugged in
     */
    @VisibleForTesting
    void setPowerPluggedIn(boolean plugged) {
        mPowerPluggedIn = plugged;
    }

    /**
     * Returns the indication text indicating that trust is currently being managed.
     *
     * @return {@code null} or an empty string if a trust managed text should not be shown.
     */
    private String getTrustManagedIndication() {
        return null;
    }

    /**
     * Hides transient indication in {@param delayMs}.
     */
    public void hideTransientIndicationDelayed(long delayMs) {
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_HIDE_TRANSIENT), delayMs);
    }

    /**
     * Hides biometric indication in {@param delayMs}.
     */
    public void hideBiometricMessageDelayed(long delayMs) {
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_HIDE_BIOMETRIC_MESSAGE), delayMs);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(int transientIndication) {
        showTransientIndication(mContext.getResources().getString(transientIndication));
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    private void showTransientIndication(CharSequence transientIndication) {
        mTransientIndication = transientIndication;
        mHandler.removeMessages(MSG_HIDE_TRANSIENT);
        hideTransientIndicationDelayed(BaseKeyguardCallback.HIDE_DELAY_MS);

        updateTransient();
    }

    /**
     * Shows {@param biometricMessage} until it is hidden by {@link #hideBiometricMessage}.
     */
    public void showBiometricMessage(int biometricMessage) {
        showBiometricMessage(mContext.getResources().getString(biometricMessage));
    }

    /**
     * Shows {@param biometricMessage} until it is hidden by {@link #hideBiometricMessage}.
     */
    private void showBiometricMessage(CharSequence biometricMessage) {
        mBiometricMessage = biometricMessage;

        mHandler.removeMessages(MSG_SHOW_ACTION_TO_UNLOCK);
        mHandler.removeMessages(MSG_HIDE_BIOMETRIC_MESSAGE);
        hideBiometricMessageDelayed(BaseKeyguardCallback.HIDE_DELAY_MS);

        updateBiometricMessage();
    }

    private void hideBiometricMessage() {
        if (mBiometricMessage != null) {
            mBiometricMessage = null;
            mHandler.removeMessages(MSG_HIDE_BIOMETRIC_MESSAGE);
            updateBiometricMessage();
        }
    }

    /**
     * Hides transient indication.
     */
    public void hideTransientIndication() {
        if (mTransientIndication != null) {
            mTransientIndication = null;
            mHandler.removeMessages(MSG_HIDE_TRANSIENT);
            updateTransient();
        }
    }

    protected final void updateIndication(boolean animate) {
        if (!mVisible) {
            return;
        }

        // A few places might need to hide the indication, so always start by making it visible
        mIndicationArea.setVisibility(VISIBLE);

        // Walk down a precedence-ordered list of what indication
        // should be shown based on user or device state
        // AoD
        if (mDozing) {
            mLockScreenIndicationView.setVisibility(View.GONE);
            mTopIndicationView.setVisibility(VISIBLE);
            // When dozing we ignore any text color and use white instead, because
            // colors can be hard to read in low brightness.
            mTopIndicationView.setTextColor(Color.WHITE);
            if (!TextUtils.isEmpty(mBiometricMessage)) {
                mWakeLock.setAcquired(true);
                mTopIndicationView.switchIndication(mBiometricMessage, null,
                        true, () -> mWakeLock.setAcquired(false));
            } else if (!TextUtils.isEmpty(mTransientIndication)) {
                mWakeLock.setAcquired(true);
                mTopIndicationView.switchIndication(mTransientIndication, null,
                        true, () -> mWakeLock.setAcquired(false));
            } else if (!mBatteryPresent) {
                // If there is no battery detected, hide the indication and bail
                mIndicationArea.setVisibility(GONE);
            } else if (!TextUtils.isEmpty(mAlignmentIndication)) {
                mTopIndicationView.switchIndication(mAlignmentIndication, null,
                        false /* animate */, null /* onAnimationEndCallback */);
                mTopIndicationView.setTextColor(mContext.getColor(R.color.misalignment_text_color));
            } else if (mPowerPluggedIn || mEnableBatteryDefender) {
                String indication = computePowerIndication();
                if (animate) {
                    mWakeLock.setAcquired(true);
                    mTopIndicationView.switchIndication(indication, null, true /* animate */,
                            () -> mWakeLock.setAcquired(false));
                } else {
                    mTopIndicationView.switchIndication(indication, null, false /* animate */,
                            null /* onAnimationEndCallback */);
                }
            } else {
                String percentage = NumberFormat.getPercentInstance()
                        .format(mBatteryLevel / 100f);
                mTopIndicationView.switchIndication(percentage, null /* indication */,
                        false /* animate */, null /* onAnimationEnd*/);
            }
            return;
        }

        // LOCK SCREEN
        mTopIndicationView.setVisibility(GONE);
        mTopIndicationView.setText(null);
        mLockScreenIndicationView.setVisibility(View.VISIBLE);
        updatePersistentIndications(animate, KeyguardUpdateMonitor.getCurrentUser());
    }

    // animates textView - textView moves up and bounces down
    private void animateText(KeyguardIndicationTextView textView, String indication) {
        int yTranslation = mContext.getResources().getInteger(
                R.integer.wired_charging_keyguard_text_animation_distance);
        int animateUpDuration = mContext.getResources().getInteger(
                R.integer.wired_charging_keyguard_text_animation_duration_up);
        int animateDownDuration = mContext.getResources().getInteger(
                R.integer.wired_charging_keyguard_text_animation_duration_down);
        textView.animate().cancel();
        ViewClippingUtil.setClippingDeactivated(textView, true, mClippingParams);
        textView.animate()
                .translationYBy(yTranslation)
                .setInterpolator(Interpolators.LINEAR)
                .setDuration(animateUpDuration)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean mCancelled;

                    @Override
                    public void onAnimationStart(Animator animation) {
                        textView.switchIndication(indication, null);
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        textView.setTranslationY(BOUNCE_ANIMATION_FINAL_Y);
                        mCancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mCancelled) {
                            ViewClippingUtil.setClippingDeactivated(textView, false,
                                    mClippingParams);
                            return;
                        }
                        textView.animate()
                                .setDuration(animateDownDuration)
                                .setInterpolator(Interpolators.BOUNCE)
                                .translationY(BOUNCE_ANIMATION_FINAL_Y)
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        textView.setTranslationY(BOUNCE_ANIMATION_FINAL_Y);
                                        ViewClippingUtil.setClippingDeactivated(textView, false,
                                                mClippingParams);
                                        // Unset the listener, otherwise this may persist for
                                        // another view property animation
                                        textView.animate().setListener(null);
                                    }
                                });
                    }
                });
    }

    protected String computePowerIndication() {
        int chargingId;
        if (mBatteryOverheated) {
            chargingId = R.string.keyguard_plugged_in_charging_limited;
            String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100f);
            return mContext.getResources().getString(chargingId, percentage);
        } else if (mPowerCharged) {
            return mContext.getResources().getString(R.string.keyguard_charged);
        }

        final boolean hasChargingTime = mChargingTimeRemaining > 0;
        if (mPowerPluggedInWired) {
            switch (mChargingSpeed) {
                case BatteryStatus.CHARGING_FAST:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_fast
                            : R.string.keyguard_plugged_in_charging_fast;
                    break;
                case BatteryStatus.CHARGING_SLOWLY:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_slowly
                            : R.string.keyguard_plugged_in_charging_slowly;
                    break;
                default:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time
                            : R.string.keyguard_plugged_in;
                    break;
            }
        } else if (mPowerPluggedInWireless) {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time_wireless
                    : R.string.keyguard_plugged_in_wireless;
        } else {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time
                    : R.string.keyguard_plugged_in;
        }

        String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100f);
        if (hasChargingTime) {
            String chargingTimeFormatted = Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                    mContext, mChargingTimeRemaining);
            return mContext.getResources().getString(chargingId, chargingTimeFormatted,
                    percentage);
        } else {
            return mContext.getResources().getString(chargingId, percentage);
        }
    }

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    private final KeyguardUpdateMonitorCallback mTickReceiver =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onTimeChanged() {
                    if (mVisible) {
                        updateIndication(false /* animate */);
                    }
                }
            };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_HIDE_TRANSIENT) {
                hideTransientIndication();
            } else if (msg.what == MSG_SHOW_ACTION_TO_UNLOCK) {
                showActionToUnlock();
            } else if (msg.what == MSG_HIDE_BIOMETRIC_MESSAGE) {
                hideBiometricMessage();
            }
        }
    };

    /**
     * Show message on the keyguard for how the user can unlock/enter their device.
     */
    public void showActionToUnlock() {
        if (mDozing
                && !mKeyguardUpdateMonitor.getUserCanSkipBouncer(
                        KeyguardUpdateMonitor.getCurrentUser())) {
            return;
        }

        if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
            if (mStatusBarKeyguardViewManager.isShowingAlternateAuth()) {
                return; // udfps affordance is highlighted, no need to show action to unlock
            } else if (mKeyguardUpdateMonitor.isFaceEnrolled()) {
                String message = mContext.getString(R.string.keyguard_retry);
                mStatusBarKeyguardViewManager.showBouncerMessage(message, mInitialTextColorState);
            }
        } else {
            if (mKeyguardUpdateMonitor.isUdfpsSupported()
                    && mKeyguardUpdateMonitor.getUserCanSkipBouncer(
                    KeyguardUpdateMonitor.getCurrentUser())) {
                showBiometricMessage(mContext.getString(R.string.keyguard_unlock_press));
            } else {
                showBiometricMessage(mContext.getString(R.string.keyguard_unlock));
            }
        }
    }

    private void showTryFingerprintMsg(int msgId, String a11yString) {
        if (mKeyguardUpdateMonitor.isUdfpsSupported()) {
            // if udfps available, there will always be a tappable affordance to unlock
            // For example, the lock icon
            if (mKeyguardBypassController.getUserHasDeviceEntryIntent()) {
                showBiometricMessage(R.string.keyguard_unlock_press);
            } else if (msgId == FaceManager.FACE_ERROR_LOCKOUT_PERMANENT) {
                // since face is locked out, simply show "try fingerprint"
                showBiometricMessage(R.string.keyguard_try_fingerprint);
            } else {
                showBiometricMessage(R.string.keyguard_face_failed_use_fp);
            }
        } else {
            showBiometricMessage(R.string.keyguard_try_fingerprint);
        }

        // Although we suppress face auth errors visually, we still announce them for a11y
        if (!TextUtils.isEmpty(a11yString)) {
            mLockScreenIndicationView.announceForAccessibility(a11yString);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardIndicationController:");
        pw.println("  mInitialTextColorState: " + mInitialTextColorState);
        pw.println("  mPowerPluggedInWired: " + mPowerPluggedInWired);
        pw.println("  mPowerPluggedIn: " + mPowerPluggedIn);
        pw.println("  mPowerCharged: " + mPowerCharged);
        pw.println("  mChargingSpeed: " + mChargingSpeed);
        pw.println("  mChargingWattage: " + mChargingWattage);
        pw.println("  mMessageToShowOnScreenOn: " + mMessageToShowOnScreenOn);
        pw.println("  mDozing: " + mDozing);
        pw.println("  mTransientIndication: " + mTransientIndication);
        pw.println("  mBiometricMessage: " + mBiometricMessage);
        pw.println("  mBatteryLevel: " + mBatteryLevel);
        pw.println("  mBatteryPresent: " + mBatteryPresent);
        pw.println("  mTextView.getText(): " + (
                mTopIndicationView == null ? null : mTopIndicationView.getText()));
        pw.println("  computePowerIndication(): " + computePowerIndication());
        mRotateTextViewController.dump(fd, pw, args);
    }

    protected class BaseKeyguardCallback extends KeyguardUpdateMonitorCallback {
        public static final int HIDE_DELAY_MS = 5000;

        @Override
        public void onRefreshBatteryInfo(BatteryStatus status) {
            boolean isChargingOrFull = status.status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status.isCharged();
            boolean wasPluggedIn = mPowerPluggedIn;
            mPowerPluggedInWired = status.isPluggedInWired() && isChargingOrFull;
            mPowerPluggedInWireless = status.isPluggedInWireless() && isChargingOrFull;
            mPowerPluggedIn = status.isPluggedIn() && isChargingOrFull;
            mPowerCharged = status.isCharged();
            mChargingWattage = status.maxChargingWattage;
            mChargingSpeed = status.getChargingSpeed(mContext);
            mBatteryLevel = status.level;
            mBatteryPresent = status.present;
            mBatteryOverheated = status.isOverheated();
            mEnableBatteryDefender = mBatteryOverheated && status.isPluggedIn();
            try {
                mChargingTimeRemaining = mPowerPluggedIn
                        ? mBatteryInfo.computeChargeTimeRemaining() : -1;
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling IBatteryStats: ", e);
                mChargingTimeRemaining = -1;
            }
            updateIndication(!wasPluggedIn && mPowerPluggedInWired);
            if (mDozing) {
                if (!wasPluggedIn && mPowerPluggedIn) {
                    showTransientIndication(computePowerIndication());
                } else if (wasPluggedIn && !mPowerPluggedIn) {
                    hideTransientIndication();
                }
            }
        }

        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            // TODO(b/141025588): refactor to reduce repetition of code/comments
            // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
            // as long as primary auth, i.e. PIN/pattern/password, is not required), so it's ok to
            // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
            // check of whether non-strong biometric is allowed
            if (!mKeyguardUpdateMonitor
                    .isUnlockingWithBiometricAllowed(true /* isStrongBiometric */)) {
                return;
            }
            boolean showActionToUnlock =
                    msgId == KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_RECOGNIZED;
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(helpString,
                        mInitialTextColorState);
            } else if (mKeyguardUpdateMonitor.isScreenOn()) {
                if (biometricSourceType == BiometricSourceType.FACE
                        && shouldSuppressFaceMsgAndShowTryFingerprintMsg()) {
                    showTryFingerprintMsg(msgId, helpString);
                    return;
                }
                showBiometricMessage(helpString);
            } else if (showActionToUnlock) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SHOW_ACTION_TO_UNLOCK),
                        TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
            }
        }

        @Override
        public void onBiometricError(int msgId, String errString,
                BiometricSourceType biometricSourceType) {
            if (shouldSuppressBiometricError(msgId, biometricSourceType, mKeyguardUpdateMonitor)) {
                return;
            }
            if (biometricSourceType == BiometricSourceType.FACE
                    && shouldSuppressFaceMsgAndShowTryFingerprintMsg()
                    && !mStatusBarKeyguardViewManager.isBouncerShowing()
                    && mKeyguardUpdateMonitor.isScreenOn()) {
                showTryFingerprintMsg(msgId, errString);
                return;
            }
            if (msgId == FaceManager.FACE_ERROR_TIMEOUT) {
                // The face timeout message is not very actionable, let's ask the user to
                // manually retry.
                if (!mStatusBarKeyguardViewManager.isBouncerShowing()
                        && mKeyguardUpdateMonitor.isUdfpsEnrolled()
                        && mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
                    showTryFingerprintMsg(msgId, errString);
                } else if (mStatusBarKeyguardViewManager.isShowingAlternateAuth()) {
                    mStatusBarKeyguardViewManager.showBouncerMessage(
                            mContext.getResources().getString(R.string.keyguard_unlock_press),
                            mInitialTextColorState
                    );
                } else {
                    // suggest swiping up to unlock (try face auth again or swipe up to bouncer)
                    showActionToUnlock();
                }
            } else if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(errString, mInitialTextColorState);
            } else if (mKeyguardUpdateMonitor.isScreenOn()) {
                showBiometricMessage(errString);
            } else {
                mMessageToShowOnScreenOn = errString;
            }
        }

        private boolean shouldSuppressBiometricError(int msgId,
                BiometricSourceType biometricSourceType, KeyguardUpdateMonitor updateMonitor) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT)
                return shouldSuppressFingerprintError(msgId, updateMonitor);
            if (biometricSourceType == BiometricSourceType.FACE)
                return shouldSuppressFaceError(msgId, updateMonitor);
            return false;
        }

        private boolean shouldSuppressFingerprintError(int msgId,
                KeyguardUpdateMonitor updateMonitor) {
            // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
            // as long as primary auth, i.e. PIN/pattern/password, is not required), so it's ok to
            // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
            // check of whether non-strong biometric is allowed
            return ((!updateMonitor.isUnlockingWithBiometricAllowed(true /* isStrongBiometric */)
                    && msgId != FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT)
                    || msgId == FingerprintManager.FINGERPRINT_ERROR_CANCELED
                    || msgId == FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED);
        }

        private boolean shouldSuppressFaceMsgAndShowTryFingerprintMsg() {
            // For dual biometric, don't show face auth messages
            return mKeyguardUpdateMonitor.isFingerprintDetectionRunning()
                    && mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                            true /* isStrongBiometric */);
        }

        private boolean shouldSuppressFaceError(int msgId, KeyguardUpdateMonitor updateMonitor) {
            // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
            // as long as primary auth, i.e. PIN/pattern/password, is not required), so it's ok to
            // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
            // check of whether non-strong biometric is allowed
            return ((!updateMonitor.isUnlockingWithBiometricAllowed(true /* isStrongBiometric */)
                    && msgId != FaceManager.FACE_ERROR_LOCKOUT_PERMANENT)
                    || msgId == FaceManager.FACE_ERROR_CANCELED);
        }

        @Override
        public void onTrustAgentErrorMessage(CharSequence message) {
            showBiometricMessage(message);
        }

        @Override
        public void onScreenTurnedOn() {
            if (mMessageToShowOnScreenOn != null) {
                showBiometricMessage(mMessageToShowOnScreenOn);
                // We want to keep this message around in case the screen was off
                hideBiometricMessageDelayed(HIDE_DELAY_MS);
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            if (running && biometricSourceType == BiometricSourceType.FACE) {
                // Let's hide any previous messages when authentication starts, otherwise
                // multiple auth attempts would overlap.
                hideBiometricMessage();
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
                boolean isStrongBiometric) {
            super.onBiometricAuthenticated(userId, biometricSourceType, isStrongBiometric);
            hideBiometricMessage();

            if (biometricSourceType == BiometricSourceType.FACE
                    && !mKeyguardBypassController.canBypass()) {
                showActionToUnlock();
            }
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            if (mVisible) {
                updateIndication(false);
            }
        }

        @Override
        public void onUserUnlocked() {
            if (mVisible) {
                updateIndication(false);
            }
        }

        @Override
        public void onLogoutEnabledChanged() {
            if (mVisible) {
                updateIndication(false);
            }
        }

        @Override
        public void onRequireUnlockForNfc() {
            showTransientIndication(mContext.getString(R.string.require_unlock_for_nfc));
            hideTransientIndicationDelayed(HIDE_DELAY_MS);
        }
    }

    private StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {
            setVisible(newState == StatusBarState.KEYGUARD);
        }

        @Override
        public void onDozingChanged(boolean dozing) {
            if (mDozing == dozing) {
                return;
            }
            mDozing = dozing;

            if (mDozing) {
                hideBiometricMessage();
            }
            updateIndication(false);
        }
    };

    private KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            updateIndication(false);
        }

        @Override
        public void onKeyguardShowingChanged() {
            if (!mKeyguardStateController.isShowing()) {
                mTopIndicationView.clearMessages();
                mRotateTextViewController.clearMessages();
            }
        }
    };
}

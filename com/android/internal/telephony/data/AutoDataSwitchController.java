/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.telephony.data;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.telephony.CarrierConfigManager.KEY_OPP_AUTO_DATA_SWITCH_AVAILABILITY_STABILITY_MILLIS_LONG;
import static android.telephony.CarrierConfigManager.KEY_OPP_AUTO_DATA_SWITCH_AVAILABILITY_SWITCHBACK_MILLIS_LONG;
import static android.telephony.CarrierConfigManager.KEY_OPP_AUTO_DATA_SWITCH_PERFORMANCE_STABILITY_MILLIS_LONG;
import static android.telephony.CarrierConfigManager.KEY_OPP_AUTO_DATA_SWITCH_PING_BEFORE_SWITCH_BOOL;
import static android.telephony.CarrierConfigManager.KEY_OPP_AUTO_DATA_SWITCH_POLICY_INT;
import static android.telephony.CarrierConfigManager.KEY_OPP_AUTO_DATA_SWITCH_VALIDATION_MAX_RETRIES_INT;
import static android.telephony.CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_BITMASK_AVAILABILITY;
import static android.telephony.CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_BITMASK_PERFORMANCE;
import static android.telephony.CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_DISABLED;
import static android.telephony.CarrierConfigManager.OPP_AUTO_DATA_SWITCH_POLICY_FOLLOW_SYSTEM;
import static android.telephony.CarrierConfigManager.OpportunisticNetworkSwitchPolicy;
import static android.telephony.SubscriptionManager.DEFAULT_PHONE_INDEX;
import static android.telephony.SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
import static android.telephony.SubscriptionManager.INVALID_PHONE_INDEX;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.NetworkRegistrationInfo.RegistrationState;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyDisplayInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.flags.FeatureFlagsImpl;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.internal.telephony.util.NotificationChannelController;
import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Recommend a data phone to use based on its availability.
 */
public class AutoDataSwitchController extends Handler {
    /** Registration state changed. */
    public static final int EVALUATION_REASON_REGISTRATION_STATE_CHANGED = 1;
    /** Telephony Display Info changed. */
    public static final int EVALUATION_REASON_DISPLAY_INFO_CHANGED = 2;
    /** Signal Strength changed. */
    public static final int EVALUATION_REASON_SIGNAL_STRENGTH_CHANGED = 3;
    /** Default network capabilities changed or lost. */
    public static final int EVALUATION_REASON_DEFAULT_NETWORK_CHANGED = 4;
    /** Data enabled settings changed. */
    public static final int EVALUATION_REASON_DATA_SETTINGS_CHANGED = 5;
    /** Retry due to previous validation failed. */
    public static final int EVALUATION_REASON_RETRY_VALIDATION = 6;
    /** Sim loaded which means slot mapping became available. */
    public static final int EVALUATION_REASON_SIM_LOADED = 7;
    /** Voice call ended. */
    public static final int EVALUATION_REASON_VOICE_CALL_END = 8;
    /** Carrier configuration changed */
    public static final int EVALUATION_REASON_CARRIER_CONFIG_CHANGED = 9;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "EVALUATION_REASON_",
            value = {EVALUATION_REASON_REGISTRATION_STATE_CHANGED,
                    EVALUATION_REASON_DISPLAY_INFO_CHANGED,
                    EVALUATION_REASON_SIGNAL_STRENGTH_CHANGED,
                    EVALUATION_REASON_DEFAULT_NETWORK_CHANGED,
                    EVALUATION_REASON_DATA_SETTINGS_CHANGED,
                    EVALUATION_REASON_RETRY_VALIDATION,
                    EVALUATION_REASON_SIM_LOADED,
                    EVALUATION_REASON_VOICE_CALL_END,
                    EVALUATION_REASON_CARRIER_CONFIG_CHANGED})
    public @interface AutoDataSwitchEvaluationReason {}

    /**
     * Defines the switch type for considering a subscription as out of service before switching
     * data, in milliseconds.
     * If one SIM has service while the other is out of service for this duration,
     * data will be switched to the SIM with service.
     */
    private static final int STABILITY_CHECK_AVAILABILITY_SWITCH = 0;
    /**
     * Defines the switch type for considering the RAT and signal strength advantage of a
     * subscription to be stable before switching data, in milliseconds.
     * Each RAT and signal strength is assigned a score. If one SIM's score is higher
     * than the other SIM's score for this duration, data will be switched to that SIM.
     */
    private static final int STABILITY_CHECK_PERFORMANCE_SWITCH = 1;
    /**
     * Defines the switch type for switching data back to the default SIM when both SIMs are out of
     * service, in milliseconds.
     * If the current data is on the backup SIM and both SIMs remain out of service,
     * data will be switched back to the default SIM.
     */
    private static final int STABILITY_CHECK_AVAILABILITY_SWITCH_BACK = 2;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "STABILITY_CHECK_",
            value = {STABILITY_CHECK_AVAILABILITY_SWITCH,
                    STABILITY_CHECK_PERFORMANCE_SWITCH,
                    STABILITY_CHECK_AVAILABILITY_SWITCH_BACK,
            })
    public @interface PreSwitchStabilityCheckType {}

    /** stability check type to timer in milliseconds. */
    private static final Map<Integer, Long> STABILITY_CHECK_TIMER_MAP = new ArrayMap<>();

    private static final String LOG_TAG = "ADSC";

    /** Event for service state changed. */
    private static final int EVENT_SERVICE_STATE_CHANGED = 1;
    /** Event for display info changed. This is for getting 5G NSA or mmwave information. */
    private static final int EVENT_DISPLAY_INFO_CHANGED = 2;
    /** Event for evaluate auto data switch opportunity. */
    private static final int EVENT_EVALUATE_AUTO_SWITCH = 3;
    /** Event for signal strength changed. */
    private static final int EVENT_SIGNAL_STRENGTH_CHANGED = 4;
    /** Event indicates the switch state is stable, proceed to validation as the next step. */
    private static final int EVENT_STABILITY_CHECK_PASSED = 5;
    /** Event when subscriptions changed. */
    private static final int EVENT_SUBSCRIPTIONS_CHANGED = 6;

    /** Fragment "key" argument passed thru {@link #SETTINGS_EXTRA_SHOW_FRAGMENT_ARGUMENTS} */
    private static final String SETTINGS_EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";
    /**
     * When starting this activity, this extra can also be specified to supply a Bundle of arguments
     * to pass to that fragment when it is instantiated during the initial creation of the activity.
     */
    private static final String SETTINGS_EXTRA_SHOW_FRAGMENT_ARGUMENTS =
            ":settings:show_fragment_args";
    /** The resource ID of the auto data switch fragment in settings. **/
    private static final String AUTO_DATA_SWITCH_SETTING_R_ID = "auto_data_switch";
    /** Notification tag **/
    private static final String AUTO_DATA_SWITCH_NOTIFICATION_TAG = "auto_data_switch";
    /** Notification ID **/
    private static final int AUTO_DATA_SWITCH_NOTIFICATION_ID = 1;

    /**
     * The threshold of long timer, longer than or equal to which we use alarm manager to schedule
     * instead of handler.
     */
    private static final long RETRY_LONG_DELAY_TIMER_THRESHOLD_MILLIS = TimeUnit
            .MINUTES.toMillis(1);

    // TODO(b/453655423): Manage all carrier configs in a single class
    /**
     * The carrier config keys that are used by auto data switch controller.
     * Note that this is not the complete list. For example,
     * {@link CarrierConfigManager#KEY_AUTO_DATA_SWITCH_RAT_SIGNAL_SCORE_BUNDLE} is handled
     * by DataConfigManager at present.
     */
    private static final String[] OPP_AUTO_DATA_SWITCH_CARRIER_CONFIG_KEYS = {
            KEY_OPP_AUTO_DATA_SWITCH_POLICY_INT,
            KEY_OPP_AUTO_DATA_SWITCH_AVAILABILITY_STABILITY_MILLIS_LONG,
            KEY_OPP_AUTO_DATA_SWITCH_PERFORMANCE_STABILITY_MILLIS_LONG,
            KEY_OPP_AUTO_DATA_SWITCH_AVAILABILITY_SWITCHBACK_MILLIS_LONG,
            KEY_OPP_AUTO_DATA_SWITCH_PING_BEFORE_SWITCH_BOOL,
            KEY_OPP_AUTO_DATA_SWITCH_VALIDATION_MAX_RETRIES_INT
    };

    @NonNull
    private final LocalLog mLocalLog = new LocalLog(128);
    @NonNull
    private final Context mContext;
    @NonNull
    private static FeatureFlags sFeatureFlags = new FeatureFlagsImpl();
    @NonNull
    private final SubscriptionManagerService mSubscriptionManagerService;
    @NonNull
    private final PhoneSwitcher mPhoneSwitcher;
    @NonNull
    private final AutoDataSwitchControllerCallback mPhoneSwitcherCallback;
    @NonNull
    private final AlarmManager mAlarmManager;
    // TODO(b/441307439): fix issue that ADSC is created with null CCM on cellular-less devices
    @NonNull
    private final CarrierConfigManager mCarrierConfigManager;
    /** A map of a scheduled event to its associated extra for action when the event fires off. */
    @NonNull
    private final Map<Integer, Object> mScheduledEventsToExtras;
    /** A map of an event to its associated alarm listener callback for when the event fires off. */
    @NonNull
    private final Map<Integer, AlarmManager.OnAlarmListener> mEventsToAlarmListener;
    /**
     * Event extras for checking environment stability.
     * @param targetPhoneId The target phone Id to switch to when the stability check pass.
     * @param switchType Whether the switch is due to OOS, RAT/signal strength performance, or
     *                   switch back.
     * @param needValidation Whether ping test needs to pass.
     */
    private record StabilityEventExtra(int targetPhoneId,
                                       @PreSwitchStabilityCheckType int switchType,
                                       boolean needValidation) {
        @Override
        public String toString() {
            return "StabilityEventExtra{"
                    + "targetPhoneId=" + targetPhoneId
                    + ", switchType=" + switchTypeToString(switchType)
                    + ", needValidation=" + needValidation
                    + "}";
        }
    }

    /**
     * Event extras for evaluating switch environment.
     * @param evaluateReason The reason that triggers the evaluation.
     */
    private record EvaluateEventExtra(@AutoDataSwitchEvaluationReason int evaluateReason) {}
    private boolean mDefaultNetworkIsOnNonCellular = false;
    /** {@code true} if we've displayed the notification the first time auto switch occurs **/
    private boolean mDisplayedNotification = false;
    /**
     * The tolerated gap of score for auto data switch decision, larger than which the device will
     * switch to the SIM with higher score. If 0, the device will always switch to the higher score
     * SIM. If < 0, the network type and signal strength based auto switch is disabled.
     */
    private int mScoreTolerance = -1;
    /**
     * {@code true} if requires ping test before switching preferred data modem; otherwise, switch
     * even if ping test fails.
     */
    private boolean mRequirePingTestBeforeSwitch = true;
    /** The count of consecutive auto switch validation failure **/
    private int mAutoSwitchValidationFailedCount = 0;
    /**
     * The maximum number of retries when a validation for switching failed.
     */
    private int mAutoDataSwitchValidationMaxRetry;

    /** The signal status of phones, where index corresponds to phone Id. */
    @NonNull
    private PhoneSignalStatus[] mPhonesSignalStatus;
    /**
     * The phone Id of the pending switching phone. Used for pruning frequent switch evaluation.
     */
    private int mSelectedTargetPhoneId = INVALID_PHONE_INDEX;

    /**
     * To track the signal status of a phone in order to evaluate whether it's a good candidate to
     * switch to.
     */
    private static class PhoneSignalStatus {
        /**
         * How preferred the current phone is.
         */
        enum UsableState {
            HOME(2),
            ROAMING_ENABLED(1),
            NON_TERRESTRIAL(0),
            NOT_USABLE(-1);
            /**
             * The higher the score, the more preferred.
             * HOME is preferred over ROAMING assuming roaming is metered.
             */
            final int mScore;
            UsableState(int score) {
                this.mScore = score;
            }
        }
        /** The phone */
        @NonNull private final Phone mPhone;
        /** Data registration state of the phone */
        @RegistrationState private int mDataRegState;
        /** Current Telephony display info of the phone */
        @NonNull private TelephonyDisplayInfo mDisplayInfo;
        /** Signal strength of the phone */
        @NonNull private SignalStrength mSignalStrength;
        /** {@code true} if this slot is listening for events. */
        private boolean mListeningForEvents;
        private PhoneSignalStatus(@NonNull Phone phone) {
            this.mPhone = phone;
            this.mDataRegState = phone.getServiceState().getNetworkRegistrationInfo(
                            NetworkRegistrationInfo.DOMAIN_PS,
                            AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                    .getRegistrationState();
            this.mDisplayInfo = phone.getDisplayInfoController().getTelephonyDisplayInfo();
            this.mSignalStrength = phone.getSignalStrength();
        }

        /**
         * @return the current score of this phone. 0 indicates out of service and it will never be
         * selected as the secondary data candidate.
         */
        private int getRatSignalScore() {
            return isInService(mDataRegState)
                    ? mPhone.getDataNetworkController().getDataConfigManager()
                    .getAutoDataSwitchScore(mDisplayInfo, mSignalStrength) : 0;
        }

        /**
         * @return The current usable state of the phone.
         */
        private UsableState getUsableState() {
            ServiceState serviceState = mPhone.getServiceState();
            boolean isUsingNonTerrestrialNetwork =
                    (serviceState != null) && serviceState.isUsingNonTerrestrialNetwork();

            return switch (mDataRegState) {
                case NetworkRegistrationInfo.REGISTRATION_STATE_HOME ->
                        isUsingNonTerrestrialNetwork
                                ? UsableState.NON_TERRESTRIAL : UsableState.HOME;
                case NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING -> {
                    // Satellite may bypass User's roaming settings
                    if (isUsingNonTerrestrialNetwork) {
                        boolean byPassRoamingSettings = mPhone.getDataNetworkController()
                                .getDataConfigManager().isDataRoamingAllowedOnSatellite();
                        if (byPassRoamingSettings) yield UsableState.NON_TERRESTRIAL;
                    }
                    if (mPhone.getDataRoamingEnabled()) {
                        yield isUsingNonTerrestrialNetwork
                                ? UsableState.NON_TERRESTRIAL : UsableState.ROAMING_ENABLED;
                    }
                    yield UsableState.NOT_USABLE;
                }
                default -> UsableState.NOT_USABLE;
            };
        }

        @Override
        public String toString() {
            return "{phone " + mPhone.getPhoneId()
                    + " score=" + getRatSignalScore() + " dataRegState="
                    + NetworkRegistrationInfo.registrationStateToString(mDataRegState)
                    + " " + getUsableState() + " " + mDisplayInfo
                    + " signalStrength=" + mSignalStrength.getLevel()
                    + " listeningForEvents=" + mListeningForEvents
                    + "}";

        }
    }

    /**
     * This is the callback used for listening events from {@link AutoDataSwitchController}.
     */
    public abstract static class AutoDataSwitchControllerCallback {
        /**
         * Called when a target data phone is recommended by the controller.
         * @param targetPhoneId The target phone Id.
         * @param needValidation {@code true} if need a ping test to pass before switching.
         */
        public abstract void onRequireValidation(int targetPhoneId, boolean needValidation);

        /**
         * Called when a target data phone is demanded by the controller.
         * @param targetPhoneId The target phone Id.
         * @param reason The reason for the demand.
         */
        public abstract void onRequireImmediatelySwitchToPhone(int targetPhoneId,
                @AutoDataSwitchEvaluationReason int reason);

        /**
         * Called when the controller asks to cancel any pending validation attempts because the
         * environment is no longer suited for switching.
         */
        public abstract void onRequireCancelAnyPendingAutoSwitchValidation();
    }

    /**
     * @param context Context.
     * @param looper Main looper.
     * @param phoneSwitcher Phone switcher.
     * @param phoneSwitcherCallback Callback for phone switcher to execute.
     */
    public AutoDataSwitchController(@NonNull Context context, @NonNull Looper looper,
            @NonNull PhoneSwitcher phoneSwitcher, @NonNull FeatureFlags featureFlags,
            @NonNull AutoDataSwitchControllerCallback phoneSwitcherCallback) {
        super(looper);
        mContext = context;
        sFeatureFlags = featureFlags;
        mPhoneSwitcherCallback = phoneSwitcherCallback;
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        if (mCarrierConfigManager != null) {
            mCarrierConfigManager.registerCarrierConfigChangeListener(this::post,
                    (logicalSlotIndex, subId, carrierId, specificCarrierId) -> {
                        // Carrier config change is only used from primary sub to detect OPPT switch
                        if (!shouldExcludeOpportunisticForSwitch() && isActiveVisibleSubId(subId)) {
                            logl("onCarrierConfigChanged: slot=" + logicalSlotIndex + ", sub="
                                    + subId + ", carrierId=" + carrierId);
                            evaluateAutoDataSwitch(EVALUATION_REASON_CARRIER_CONFIG_CHANGED);
                        }
                    });
        }
        mScheduledEventsToExtras = new HashMap<>();
        mEventsToAlarmListener = new HashMap<>();
        mSubscriptionManagerService = SubscriptionManagerService.getInstance();
        mPhoneSwitcher = phoneSwitcher;
        readDeviceResourceAndCarrierConfigIfNeeded();
        int numActiveModems = PhoneFactory.getPhones().length;
        mPhonesSignalStatus = new PhoneSignalStatus[numActiveModems];
        // Listening on all slots on boot up to make sure nothing missed. Later the tracking is
        // pruned upon subscriptions changed.
        for (int phoneId = 0; phoneId < numActiveModems; phoneId++) {
            registerAllEventsForPhone(phoneId);
        }
    }

    /**
     * Called when active modem count changed, update all tracking events.
     * @param numActiveModems The current number of active modems.
     */
    public synchronized void onMultiSimConfigChanged(int numActiveModems) {
        int oldActiveModems = mPhonesSignalStatus.length;
        if (oldActiveModems == numActiveModems) return;
        // Dual -> Single
        for (int phoneId = numActiveModems; phoneId < oldActiveModems; phoneId++) {
            unregisterAllEventsForPhone(phoneId);
        }
        mPhonesSignalStatus = Arrays.copyOf(mPhonesSignalStatus, numActiveModems);
        // Signal -> Dual
        for (int phoneId = oldActiveModems; phoneId < numActiveModems; phoneId++) {
            registerAllEventsForPhone(phoneId);
        }
        logl("onMultiSimConfigChanged: " + Arrays.toString(mPhonesSignalStatus));
    }

    /** Notify subscriptions changed. */
    public void notifySubscriptionsMappingChanged() {
        sendEmptyMessage(EVENT_SUBSCRIPTIONS_CHANGED);
    }

    /**
     * On subscription changed, register/unregister events on phone Id slot that has active/inactive
     * sub to reduce unnecessary tracking.
     */
    private void onSubscriptionsChanged() {
        boolean changed = updateListenerRegistrations();
        if (changed) logl("onSubscriptionChanged: " + Arrays.toString(mPhonesSignalStatus));
    }

    /**
     * Updates network event listener registrations based on conditions.
     *
     * Unregisters listeners for *all* phones if:
     * 1. <2 active subscriptions (auto-switching requires 2+).
     * 2. Non-cellular default network (auto-switching not relevant).
     * 3. Default data disabled (no need to monitor for switching).
     * 4. No eligible auto-switch candidates (all other phones' data
     *    disabled, preventing switching).
     *
     * Registers listeners if none of the above apply and a phone's
     * listeners are currently unregistered.
     *
     * @return `true` if any registration changed; `false` otherwise.
     */
    private boolean updateListenerRegistrations() {
        boolean shouldUnregister = false;
        String reason = "";

        if (mSubscriptionManagerService.getActiveSubIdList(
                shouldExcludeOpportunisticForSwitch()).length < 2) {
            shouldUnregister = true;
            reason = "only have one active subscription";
        } else if (mDefaultNetworkIsOnNonCellular) {
            shouldUnregister = true;
            reason = "default network is on non-cellular network";
        } else {
            int defaultDataPhoneId = mSubscriptionManagerService.getPhoneId(
                    mSubscriptionManagerService.getDefaultDataSubId());
            Phone defaultDataPhone = PhoneFactory.getPhone(defaultDataPhoneId);

            if (defaultDataPhone != null && !defaultDataPhone.isUserDataEnabled()) {
                shouldUnregister = true;
                reason = "default data is disabled";
            } else {
                boolean anyCandidateEnabled = false;
                for (int phoneId = 0; phoneId < mPhonesSignalStatus.length; phoneId++) {
                    if (phoneId != defaultDataPhoneId) {
                        Phone phone = PhoneFactory.getPhone(phoneId);
                        if (phone != null && phone.getDataSettingsManager().isDataEnabled()) {
                            anyCandidateEnabled = true;
                            break;
                        }
                    }
                }

                if (!anyCandidateEnabled) {
                    shouldUnregister = true;
                    reason = "no candidate enabled auto data switch feature";
                }
            }
        }

        if (shouldUnregister) {
            logl("updateListenerRegistrations: " + reason);
        }

        // Register or unregister as needed
        boolean changed = false;
        for (int phoneId = 0; phoneId < mPhonesSignalStatus.length; phoneId++) {
            if (shouldUnregister && mPhonesSignalStatus[phoneId].mListeningForEvents) {
                unregisterAllEventsForPhone(phoneId);
                changed = true;
            } else if (!shouldUnregister && !mPhonesSignalStatus[phoneId].mListeningForEvents) {
                registerAllEventsForPhone(phoneId);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Register all tracking events for a phone.
     * @param phoneId The phone to register for all events.
     */
    private void registerAllEventsForPhone(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null && isActiveModemPhone(phoneId)) {
            mPhonesSignalStatus[phoneId] = new PhoneSignalStatus(phone);
            phone.getDisplayInfoController().registerForTelephonyDisplayInfoChanged(
                    this, EVENT_DISPLAY_INFO_CHANGED, phoneId);
            phone.getSignalStrengthController().registerForSignalStrengthChanged(
                    this, EVENT_SIGNAL_STRENGTH_CHANGED, phoneId);
            phone.getServiceStateTracker().registerForServiceStateChanged(this,
                    EVENT_SERVICE_STATE_CHANGED, phoneId);
            mPhonesSignalStatus[phoneId].mListeningForEvents = true;
            logl("registerAllEventsForPhone: registered listeners for phone " + phoneId);
        } else {
            logle("Unexpected null phone " + phoneId + " when register all events");
        }
    }

    /**
     * Unregister all tracking events for a phone.
     * @param phoneId The phone to unregister for all events.
     */
    private void unregisterAllEventsForPhone(int phoneId) {
        if (isActiveModemPhone(phoneId)) {
            Phone phone = mPhonesSignalStatus[phoneId].mPhone;
            phone.getDisplayInfoController().unregisterForTelephonyDisplayInfoChanged(this);
            phone.getSignalStrengthController().unregisterForSignalStrengthChanged(this);
            phone.getServiceStateTracker().unregisterForServiceStateChanged(this);
            mPhonesSignalStatus[phoneId].mListeningForEvents = false;
            logl("unregisterAllEventsForPhone: unregistered listeners for phone " + phoneId);
        } else {
            logle("Unexpected out of bound phone " + phoneId + " when unregister all events");
        }
    }

    /**
     * Read the default device config from any default phone because the resource config are per
     * device. No need to register callback for the same reason.
     */
    private void readDeviceResourceConfig() {
        Phone phone = PhoneFactory.getDefaultPhone();
        DataConfigManager dataConfig = phone.getDataNetworkController().getDataConfigManager();
        mScoreTolerance = dataConfig.getAutoDataSwitchScoreTolerance();
        mRequirePingTestBeforeSwitch = dataConfig.isPingTestBeforeAutoDataSwitchRequired();
        STABILITY_CHECK_TIMER_MAP.put(STABILITY_CHECK_AVAILABILITY_SWITCH,
                dataConfig.getAutoDataSwitchAvailabilityStabilityTimeThreshold());
        STABILITY_CHECK_TIMER_MAP.put(STABILITY_CHECK_PERFORMANCE_SWITCH,
                dataConfig.getAutoDataSwitchPerformanceStabilityTimeThreshold());
        STABILITY_CHECK_TIMER_MAP.put(STABILITY_CHECK_AVAILABILITY_SWITCH_BACK,
                dataConfig.getAutoDataSwitchAvailabilitySwitchbackStabilityTimeThreshold() >= 0
                        ? dataConfig.getAutoDataSwitchAvailabilitySwitchbackStabilityTimeThreshold()
                        : dataConfig.getAutoDataSwitchAvailabilityStabilityTimeThreshold());
        mAutoDataSwitchValidationMaxRetry = dataConfig.getAutoDataSwitchValidationMaxRetry();
    }

    /**
     * Read carrier config and OVERRIDE device config when fulfill all the conditions below:
     * - In a qualified OPPT switch context
     * - Carrier config from primary sub is overridden with non-default value
     */
    private void readCarrierConfigIfNeeded() {
        if (shouldExcludeOpportunisticForSwitch()) {
            logl("OPPT switch excluded, ignore carrier config.");
            return;
        }

        PersistableBundle primaryConfig = getCarrierConfigForPrimaryPhone();
        mRequirePingTestBeforeSwitch =
                primaryConfig.getBoolean(
                        KEY_OPP_AUTO_DATA_SWITCH_PING_BEFORE_SWITCH_BOOL,
                        mRequirePingTestBeforeSwitch);
        STABILITY_CHECK_TIMER_MAP.put(
                STABILITY_CHECK_AVAILABILITY_SWITCH,
                primaryConfig.getLong(
                        KEY_OPP_AUTO_DATA_SWITCH_AVAILABILITY_STABILITY_MILLIS_LONG,
                        STABILITY_CHECK_TIMER_MAP.get(STABILITY_CHECK_AVAILABILITY_SWITCH)));
        STABILITY_CHECK_TIMER_MAP.put(
                STABILITY_CHECK_PERFORMANCE_SWITCH,
                primaryConfig.getLong(
                        KEY_OPP_AUTO_DATA_SWITCH_PERFORMANCE_STABILITY_MILLIS_LONG,
                        STABILITY_CHECK_TIMER_MAP.get(STABILITY_CHECK_PERFORMANCE_SWITCH)));
        STABILITY_CHECK_TIMER_MAP.put(
                STABILITY_CHECK_AVAILABILITY_SWITCH_BACK,
                primaryConfig.getLong(
                        KEY_OPP_AUTO_DATA_SWITCH_AVAILABILITY_SWITCHBACK_MILLIS_LONG,
                        STABILITY_CHECK_TIMER_MAP.get(STABILITY_CHECK_AVAILABILITY_SWITCH_BACK)));
        mAutoDataSwitchValidationMaxRetry =
                primaryConfig.getInt(
                        KEY_OPP_AUTO_DATA_SWITCH_VALIDATION_MAX_RETRIES_INT,
                        mAutoDataSwitchValidationMaxRetry);
        logl(
                "Parameters loaded after carrier config: PING_TEST_BEFORE_SWITCH="
                        + mRequirePingTestBeforeSwitch
                        + ", AVAILABILITY_STABILITY_TIME_THRESHOLD="
                        + STABILITY_CHECK_TIMER_MAP.get(STABILITY_CHECK_AVAILABILITY_SWITCH)
                        + ", PERFORMANCE_STABILITY_TIME_THRESHOLD="
                        + STABILITY_CHECK_TIMER_MAP.get(STABILITY_CHECK_PERFORMANCE_SWITCH)
                        + ", SWITCHBACK_STABILITY_TIME_THRESHOLD="
                        + STABILITY_CHECK_TIMER_MAP.get(STABILITY_CHECK_AVAILABILITY_SWITCH_BACK)
                        + ", SWITCH_VALIDATION_MAX_RETRY=" + mAutoDataSwitchValidationMaxRetry);
    }

    /**
     * Read device config, then read carrier config for overridden if needed
     */
    private void readDeviceResourceAndCarrierConfigIfNeeded() {
        // Always read device resource config which is used both for primary networks switch and
        // OPPT networks switch.
        readDeviceResourceConfig();
        if (sFeatureFlags.exposeOpptAutoDataSwitchPolicies()) {
            readCarrierConfigIfNeeded();
        }
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        AsyncResult ar;
        Object obj;
        int phoneId;
        switch (msg.what) {
            case EVENT_SERVICE_STATE_CHANGED -> {
                ar = (AsyncResult) msg.obj;
                phoneId = (int) ar.userObj;
                onServiceStateChanged(phoneId);
            }
            case EVENT_DISPLAY_INFO_CHANGED -> {
                ar = (AsyncResult) msg.obj;
                phoneId = (int) ar.userObj;
                onDisplayInfoChanged(phoneId);
            }
            case EVENT_SIGNAL_STRENGTH_CHANGED -> {
                ar = (AsyncResult) msg.obj;
                phoneId = (int) ar.userObj;
                onSignalStrengthChanged(phoneId);
            }
            case EVENT_EVALUATE_AUTO_SWITCH -> {
                obj = mScheduledEventsToExtras.get(EVENT_EVALUATE_AUTO_SWITCH);
                if (obj instanceof EvaluateEventExtra extra) {
                    mScheduledEventsToExtras.remove(EVENT_EVALUATE_AUTO_SWITCH);
                    onEvaluateAutoDataSwitch(extra.evaluateReason);
                }
            }
            case EVENT_STABILITY_CHECK_PASSED -> {
                obj = mScheduledEventsToExtras.get(EVENT_STABILITY_CHECK_PASSED);
                if (obj instanceof StabilityEventExtra extra) {
                    int targetPhoneId = extra.targetPhoneId;
                    boolean needValidation = extra.needValidation;
                    logl("require validation on phone " + targetPhoneId
                            + (needValidation ? "" : " no") + " need to pass");
                    mScheduledEventsToExtras.remove(EVENT_STABILITY_CHECK_PASSED);
                    mPhoneSwitcherCallback.onRequireValidation(targetPhoneId, needValidation);
                }
            }
            case EVENT_SUBSCRIPTIONS_CHANGED -> onSubscriptionsChanged();
            default -> loge("Unexpected event " + msg.what);
        }
    }

    /**
     * Called when registration state changed.
     */
    private void onServiceStateChanged(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null && isActiveModemPhone(phoneId)) {
            int oldRegState = mPhonesSignalStatus[phoneId].mDataRegState;
            int newRegState = phone.getServiceState()
                    .getNetworkRegistrationInfo(
                            NetworkRegistrationInfo.DOMAIN_PS,
                            AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                    .getRegistrationState();
            if (newRegState != oldRegState) {
                mPhonesSignalStatus[phoneId].mDataRegState = newRegState;
                if (isInService(oldRegState) != isInService(newRegState)
                        || isHomeService(oldRegState) != isHomeService(newRegState)) {
                    logl("onServiceStateChanged: phone " + phoneId + " "
                            + NetworkRegistrationInfo.registrationStateToString(oldRegState)
                            + " -> "
                            + NetworkRegistrationInfo.registrationStateToString(newRegState));
                    evaluateAutoDataSwitch(EVALUATION_REASON_REGISTRATION_STATE_CHANGED);
                }
            }
        } else {
            logle("Unexpected null phone " + phoneId + " upon its registration state changed");
        }
    }

    /** @return {@code true} if the phone state is considered in service. */
    private static boolean isInService(@RegistrationState int dataRegState) {
        return dataRegState == NetworkRegistrationInfo.REGISTRATION_STATE_HOME
                || dataRegState == NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING;
    }

    /** @return {@code true} if the phone state is in home service. */
    private static boolean isHomeService(@RegistrationState int dataRegState) {
        return dataRegState == NetworkRegistrationInfo.REGISTRATION_STATE_HOME;
    }

    /**
     * Called when {@link TelephonyDisplayInfo} changed. This can happen when network types or
     * override network types (5G NSA, 5G MMWAVE) change.
     * @param phoneId The phone that changed.
     */
    private void onDisplayInfoChanged(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null && isActiveModemPhone(phoneId)) {
            TelephonyDisplayInfo displayInfo = phone.getDisplayInfoController()
                    .getTelephonyDisplayInfo();
            mPhonesSignalStatus[phoneId].mDisplayInfo = displayInfo;
            if (getBetterCandidatePhoneIdBasedOnScore() != mSelectedTargetPhoneId) {
                logl("onDisplayInfoChanged: phone " + phoneId + " " + displayInfo);
                evaluateAutoDataSwitch(EVALUATION_REASON_DISPLAY_INFO_CHANGED);
            }
        } else {
            logle("Unexpected null phone " + phoneId + " upon its display info changed");
        }
    }

    /**
     * Called when {@link SignalStrength} changed.
     * @param phoneId The phone that changed.
     */
    private void onSignalStrengthChanged(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null && isActiveModemPhone(phoneId)) {
            SignalStrength newSignalStrength = phone.getSignalStrength();
            SignalStrength oldSignalStrength = mPhonesSignalStatus[phoneId].mSignalStrength;
            if (oldSignalStrength.getLevel() != newSignalStrength.getLevel()) {
                mPhonesSignalStatus[phoneId].mSignalStrength = newSignalStrength;
                if (getBetterCandidatePhoneIdBasedOnScore() != mSelectedTargetPhoneId) {
                    logl("onSignalStrengthChanged: phone " + phoneId + " "
                            + oldSignalStrength.getLevel() + "->" + newSignalStrength.getLevel());
                    evaluateAutoDataSwitch(EVALUATION_REASON_SIGNAL_STRENGTH_CHANGED);
                }
            }
        } else {
            logle("Unexpected null phone " + phoneId + " upon its signal strength changed");
        }
    }

    /**
     * Checks for a better data phone candidate based on signal strength.Compares the preferred data
     * phone and DDS, potentially switching to another active phone with a significantly better
     * signal or reverting to DDS if the preferred phone isn't significantly better.
     *
     * @return A better candidate phone ID, the DDS phone ID,
     * or {@link android.telephony.SubscriptionManager#INVALID_PHONE_INDEX}.
     */
    private int getBetterCandidatePhoneIdBasedOnScore() {
        int preferredPhoneId = mPhoneSwitcher.getPreferredDataPhoneId();
        int ddsPhoneId = mSubscriptionManagerService.getPhoneId(
                mSubscriptionManagerService.getDefaultDataSubId());

        if (!isActiveModemPhone(preferredPhoneId) || !isActiveModemPhone(ddsPhoneId)) {
            return INVALID_PHONE_INDEX;
        }

        int defaultScore = mPhonesSignalStatus[ddsPhoneId].getRatSignalScore();
        int preferredScore = mPhonesSignalStatus[preferredPhoneId].getRatSignalScore();

        // Currently on default data subscription
        if (ddsPhoneId == preferredPhoneId) {
            // Find any candidate with significantly better score
            for (int backupId = 0; backupId < mPhonesSignalStatus.length; backupId++) {
                // Skip current phone and non-home phones
                if (backupId == preferredPhoneId
                        || !isHomeService(mPhonesSignalStatus[backupId].mDataRegState)) {
                    continue;
                }

                int candidateScore = mPhonesSignalStatus[backupId].getRatSignalScore();
                if (candidateScore - defaultScore > mScoreTolerance) {
                    return backupId;
                }
            }
        } else if (preferredScore - defaultScore <= mScoreTolerance) { // Currently on backup
            // Switch back to dds if preferred isn't significantly better
            return ddsPhoneId;
        }

        return INVALID_PHONE_INDEX;
    }

    /**
     * Schedule for auto data switch evaluation.
     * @param reason The reason for the evaluation.
     */
    public void evaluateAutoDataSwitch(@AutoDataSwitchEvaluationReason int reason) {
        // Always refresh parameters according to latest status
        readDeviceResourceAndCarrierConfigIfNeeded();
        long delayMs = reason == EVALUATION_REASON_RETRY_VALIDATION
                ? STABILITY_CHECK_TIMER_MAP.get(STABILITY_CHECK_AVAILABILITY_SWITCH)
                << mAutoSwitchValidationFailedCount
                : 0;
        if (reason == EVALUATION_REASON_DATA_SETTINGS_CHANGED
                || reason == EVALUATION_REASON_DEFAULT_NETWORK_CHANGED
                || reason == EVALUATION_REASON_CARRIER_CONFIG_CHANGED) {
            // In some conditions, listeners are paused to reduce unnecessary tracking.
            updateListenerRegistrations();
            // Always reevaluate with those critical condition change.
            scheduleEventWithTimer(EVENT_EVALUATE_AUTO_SWITCH, new EvaluateEventExtra(reason),
                    delayMs);
        } else if (!mScheduledEventsToExtras.containsKey(EVENT_EVALUATE_AUTO_SWITCH)) {
            scheduleEventWithTimer(EVENT_EVALUATE_AUTO_SWITCH, new EvaluateEventExtra(reason),
                    delayMs);
        }
    }

    /**
     * Evaluate for auto data switch opportunity.
     * If suitable to switch, check that the suitable state is stable(or switch immediately if user
     * turned off settings).
     * @param reason The reason for the evaluation.
     */
    private void onEvaluateAutoDataSwitch(@AutoDataSwitchEvaluationReason int reason) {
        if (reason == EVALUATION_REASON_CARRIER_CONFIG_CHANGED
                && shouldExcludeOpportunisticForSwitch()
                && mScheduledEventsToExtras.containsKey(EVENT_STABILITY_CHECK_PASSED)) {
            log("onEvaluateAutoDataSwitch: opportunistic policy disabled, cancelling pending "
                    + "switch");
            cancelAnyPendingSwitch();
            return;
        }
        // auto data switch feature is disabled.
        if (!isAvailabilityBasedSwitchEnabled()) {
            logle("onEvaluateAutoDataSwitch: auto data switch feature is disabled.");
            return;
        }
        int defaultDataSubId = mSubscriptionManagerService.getDefaultDataSubId();
        // check is valid DSDS
        if (mSubscriptionManagerService.getActiveSubIdList(
                shouldExcludeOpportunisticForSwitch()).length < 2) {
            logle("onEvaluateAutoDataSwitch: switch requires two active subscriptions.");
            return;
        }
        int defaultDataPhoneId = mSubscriptionManagerService.getPhoneId(defaultDataSubId);
        Phone defaultDataPhone = PhoneFactory.getPhone(defaultDataPhoneId);
        if (defaultDataPhone == null) {
            logle("onEvaluateAutoDataSwitch: cannot find the phone associated with default data"
                    + " subscription " + defaultDataSubId);
            return;
        }

        int currentPreferredPhoneId = mPhoneSwitcher.getPreferredDataPhoneId();
        int opportunisticSetDataSubId = mPhoneSwitcher.getOpportunisticSetDataSubId();
        int opportunisticSetDataPhoneId = opportunisticSetDataSubId == DEFAULT_SUBSCRIPTION_ID
                ? INVALID_PHONE_INDEX
                : mSubscriptionManagerService.getPhoneId(opportunisticSetDataSubId);
        int stickyTargetPhoneId = isActiveModemPhone(opportunisticSetDataPhoneId)
                ? opportunisticSetDataPhoneId : defaultDataPhoneId;

        StringBuilder debugMessage = new StringBuilder("onEvaluateAutoDataSwitch:");
        debugMessage.append(" defaultPhoneId: ").append(defaultDataPhoneId)
                .append(" currentPreferredPhoneId: ").append(currentPreferredPhoneId)
                .append(" stickyTargetPhoneId: ").append(stickyTargetPhoneId)
                .append(", reason: ").append(evaluationReasonToString(reason));

        if (currentPreferredPhoneId == stickyTargetPhoneId) {
            onEvaluateAutoDataSwitchOutOfTarget(stickyTargetPhoneId, defaultDataPhoneId,
                    debugMessage);
        } else {
            onEvaluateAutoDataSwitchToTarget(stickyTargetPhoneId, currentPreferredPhoneId,
                    defaultDataPhoneId, debugMessage);
        }
    }

    private void onEvaluateAutoDataSwitchOutOfTarget(int stickyTargetPhoneId,
            int defaultDataPhoneId, StringBuilder debugMessage) {
        // on target data sub
        StabilityEventExtra res = evaluateSwitchOutOfTarget(stickyTargetPhoneId, debugMessage);
        logl(debugMessage.toString());
        if (res.targetPhoneId != INVALID_PHONE_INDEX) {
            int phoneIdToReport = (res.targetPhoneId == defaultDataPhoneId)
                    ? DEFAULT_PHONE_INDEX : res.targetPhoneId;
            mSelectedTargetPhoneId = res.targetPhoneId;
            startStabilityCheck(phoneIdToReport, res.switchType, res.needValidation);
        } else {
            cancelAnyPendingSwitch();
        }
    }

    private void onEvaluateAutoDataSwitchToTarget(int stickyTargetPhoneId,
            int currentPreferredPhoneId, int defaultDataPhoneId, StringBuilder debugMessage) {
        // on backup data sub
        Phone backupDataPhone = PhoneFactory.getPhone(currentPreferredPhoneId);
        if (backupDataPhone == null || !isActiveModemPhone(currentPreferredPhoneId)) {
            logle(debugMessage.append(" Unexpected null phone ").append(currentPreferredPhoneId)
                    .append(" as the current active data phone").toString());
            return;
        }

        DataEvaluation internetEvaluation;
        if (!PhoneFactory.getPhone(defaultDataPhoneId).isUserDataEnabled()) {
            mSelectedTargetPhoneId = INVALID_PHONE_INDEX;
            // Switch back to default immediately if default data is disabled
            mPhoneSwitcherCallback.onRequireImmediatelySwitchToPhone(DEFAULT_PHONE_INDEX,
                    EVALUATION_REASON_DATA_SETTINGS_CHANGED);

            cancelAnyPendingSwitch();
            logl(debugMessage.append(
                    ", immediately back to default as user turns off default data").toString());
            return;
        } else if (!(internetEvaluation = getInternetEvaluation(backupDataPhone))
                .isSubsetOf(DataEvaluation.DataDisallowedReason.NOT_IN_SERVICE)) {
            mSelectedTargetPhoneId = INVALID_PHONE_INDEX;
            int phoneIdToReport = (stickyTargetPhoneId == defaultDataPhoneId)
                    ? DEFAULT_PHONE_INDEX : stickyTargetPhoneId;
            mPhoneSwitcherCallback.onRequireImmediatelySwitchToPhone(
                    phoneIdToReport, EVALUATION_REASON_DATA_SETTINGS_CHANGED);
            cancelAnyPendingSwitch();
            logl(debugMessage.append(
                            ", immediately back to target because backup ")
                    .append(internetEvaluation).toString());
            return;
        }

        StabilityEventExtra eventExtra = evaluateSwitchToTarget(stickyTargetPhoneId,
                currentPreferredPhoneId, debugMessage);

        logl(debugMessage.toString());
        if (eventExtra != null) {
            int phoneIdToReport = (stickyTargetPhoneId == defaultDataPhoneId)
                    ? DEFAULT_PHONE_INDEX : stickyTargetPhoneId;
            mSelectedTargetPhoneId = stickyTargetPhoneId;
            startStabilityCheck(phoneIdToReport, eventExtra.switchType, eventExtra.needValidation);
        } else {
            // cancel any previous attempts of switching back to target phone
            cancelAnyPendingSwitch();
        }
    }

    private StabilityEventExtra evaluateSwitchToTarget(int targetPhoneId,
            int currentPhoneId, StringBuilder debugMessage) {
        if (mDefaultNetworkIsOnNonCellular) {
            debugMessage.append(", back to target as default network")
                    .append(" is active on nonCellular transport");
            return new StabilityEventExtra(targetPhoneId, STABILITY_CHECK_AVAILABILITY_SWITCH,
                    false);
        }

        PhoneSignalStatus.UsableState targetUsableState =
                mPhonesSignalStatus[targetPhoneId].getUsableState();
        PhoneSignalStatus.UsableState currentUsableState =
                mPhonesSignalStatus[currentPhoneId].getUsableState();

        debugMessage.append(", target phone ").append(targetPhoneId)
                .append(" : ").append(targetUsableState)
                .append(" , current phone: ").append(currentUsableState);

        int comparison = comparePhones(currentPhoneId, targetPhoneId, debugMessage);

        if (comparison > 0) {
            // Current is better (meaning Candidate/Current > Target)
            return null;
        }

        boolean isCurrentUsable = currentUsableState.mScore
                > PhoneSignalStatus.UsableState.NOT_USABLE.mScore;

        if (comparison < 0) {
            // Target is strictly better (Target > Current)
            // Require validation if the current preferred phone is usable.
            return new StabilityEventExtra(targetPhoneId, STABILITY_CHECK_AVAILABILITY_SWITCH,
                    isCurrentUsable && mRequirePingTestBeforeSwitch);
        }

        // Comparison == 0. Both phones are in the same usable state and within score tolerance.
        if (!isCurrentUsable) {
            debugMessage.append(", back to target as both phones are unusable.");
            return new StabilityEventExtra(targetPhoneId,
                    STABILITY_CHECK_AVAILABILITY_SWITCH_BACK, false);
        }

        // Both phones are usable.
        // Check if it was a performance comparison (meaning both are HOME and feature enabled)
        if (isRatSignalStrengthBaseSwitchQualified(currentUsableState, targetUsableState)) {
            // It was a score comparison, and they are within tolerance.
            // Bias towards target (switch back).
            return new StabilityEventExtra(targetPhoneId,
                    STABILITY_CHECK_PERFORMANCE_SWITCH, mRequirePingTestBeforeSwitch);
        } else {
            // Usability was equal (e.g. both ROAMING, or feature disabled), not a score comparison.
            // Bias towards target.
            debugMessage.append(", back to target as it's usable. ");
            return new StabilityEventExtra(targetPhoneId,
                    STABILITY_CHECK_AVAILABILITY_SWITCH, mRequirePingTestBeforeSwitch);
        }
    }

    /**
     * Called when consider switching from sticky target sub to another data sub.
     * @param stickyTargetPhoneId The default/target data phone
     * @param debugMessage Debug message.
     * @return StabilityEventExtra As evaluation result.
     */
    @NonNull private StabilityEventExtra evaluateSwitchOutOfTarget(int stickyTargetPhoneId,
            @NonNull StringBuilder debugMessage) {
        Phone targetDataPhone = PhoneFactory.getPhone(stickyTargetPhoneId);
        int switchType = STABILITY_CHECK_AVAILABILITY_SWITCH;
        StabilityEventExtra invalidResult = new StabilityEventExtra(INVALID_PHONE_INDEX,
                switchType, mRequirePingTestBeforeSwitch);

        if (targetDataPhone == null) {
            debugMessage.append(", no candidate as no sim loaded");
            return invalidResult;
        }

        if (!targetDataPhone.isUserDataEnabled()) {
            debugMessage.append(", no candidate as user disabled mobile data");
            return invalidResult;
        }

        if (mDefaultNetworkIsOnNonCellular) {
            debugMessage.append(", no candidate as default network is active")
                    .append(" on non-cellular transport");
            return invalidResult;
        }

        // check whether primary and secondary signal status are worth switching
        if (!isRatSignalStrengthBasedSwitchEnabled()
                && isHomeService(mPhonesSignalStatus[stickyTargetPhoneId].mDataRegState)) {
            debugMessage.append(", no candidate as target phone is in HOME service");
            return invalidResult;
        }

        for (int phoneId = 0; phoneId < mPhonesSignalStatus.length; phoneId++) {
            if (phoneId == stickyTargetPhoneId) continue;

            Phone secondaryDataPhone = null;
            debugMessage.append(", found phone ").append(phoneId);

            int comparison = comparePhones(phoneId, stickyTargetPhoneId, debugMessage);

            if (comparison > 0) {
                // Candidate is better
                secondaryDataPhone = PhoneFactory.getPhone(phoneId);
                // Determine switch type based on usability or performance
                PhoneSignalStatus.UsableState currentUsableState =
                        mPhonesSignalStatus[stickyTargetPhoneId].getUsableState();
                PhoneSignalStatus.UsableState candidateUsableState =
                        mPhonesSignalStatus[phoneId].getUsableState();
                if (isRatSignalStrengthBaseSwitchQualified(currentUsableState,
                        candidateUsableState)) {
                    switchType = STABILITY_CHECK_PERFORMANCE_SWITCH;
                } else {
                    switchType = STABILITY_CHECK_AVAILABILITY_SWITCH;
                }
            } else {
                debugMessage.append(" not better than target");
            }

            if (secondaryDataPhone != null) {
                DataEvaluation evaluation = getInternetEvaluation(secondaryDataPhone);
                // check internet data is allowed on the candidate
                if (!evaluation.containsDisallowedReasons()) {
                    return new StabilityEventExtra(phoneId,
                            switchType, mRequirePingTestBeforeSwitch);
                } else {
                    debugMessage.append(", but candidate's data is not allowed ")
                            .append(evaluation);
                }
            }
        }
        debugMessage.append(", found no qualified candidate.");
        return invalidResult;
    }

    /**
     * Compares two phones to determine which one is "better".
     *
     * @param candidateId The phone ID of the candidate.
     * @param baselineId The phone ID of the baseline (e.g. current default or preferred).
     * @param debugMessage StringBuilder for debug logs.
     * @return 1 if candidate is better, -1 if baseline is better, 0 if equal (within tolerance).
     */
    private int comparePhones(int candidateId, int baselineId, StringBuilder debugMessage) {
        PhoneSignalStatus.UsableState candidateState =
                mPhonesSignalStatus[candidateId].getUsableState();
        PhoneSignalStatus.UsableState baselineState =
                mPhonesSignalStatus[baselineId].getUsableState();

        if (candidateState.mScore > baselineState.mScore) {
            return 1;
        } else if (candidateState.mScore < baselineState.mScore) {
            return -1;
        }

        // Usable states are equal.
        if (isRatSignalStrengthBaseSwitchQualified(baselineState, candidateState)) {
            int baselineScore = mPhonesSignalStatus[baselineId].getRatSignalScore();
            int candidateScore = mPhonesSignalStatus[candidateId].getRatSignalScore();
            int diff = candidateScore - baselineScore;

            if (diff > mScoreTolerance) {
                debugMessage.append(" candidate score ").append(candidateScore)
                        .append(" > baseline ").append(baselineScore);
                return 1;
            } else if (diff < -mScoreTolerance) {
                // If candidate is significantly worse (baseline significantly better)
                debugMessage.append(" candidate score ").append(candidateScore)
                        .append(" < baseline ").append(baselineScore);
                return -1;
            } else {
                debugMessage.append(" candidate score ").append(candidateScore)
                        .append(" within tolerance of baseline ").append(baselineScore);
                return 0;
            }
        }

        return 0;
    }

    /**
     * Get internet evaluation base on phone's satellite/terrestrial env.
     * @param phone the target phone
     * @return internet evaluation.
     */
    @NonNull
    private DataEvaluation getInternetEvaluation(@NonNull Phone phone) {
        NetworkRequest.Builder reqBuilder = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        if (phone.getServiceState().isUsingNonTerrestrialNetwork()) {
            // When satellite, RCS requests are restricted.
            reqBuilder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        }

        return phone.getDataNetworkController().evaluateNetworkRequest(
                new TelephonyNetworkRequest(reqBuilder.build(), phone, sFeatureFlags),
                DataEvaluation.DataEvaluationReason.EXTERNAL_QUERY);
    }

    /**
     * @return {@code true} If the availability based switching is enabled.
     */
    private boolean isAvailabilityBasedSwitchEnabled() {
        final boolean enabledByDeviceConfig = STABILITY_CHECK_TIMER_MAP.get(
                STABILITY_CHECK_AVAILABILITY_SWITCH) >= 0;

        // Switch between primary networks only controlled by device config
        if (!enabledByDeviceConfig) return false;

        // Switch between primary and OPPT networks is controlled by both device and carrier configs
        return shouldExcludeOpportunisticForSwitch() || isAvailabilityBasedSwitchEnabledForOppt();
    }

    /**
     * @return {@code true} If the feature of switching base on RAT and signal strength is enabled.
     */
    private boolean isRatSignalStrengthBasedSwitchEnabled() {
        final boolean enabledByDeviceConfig = mScoreTolerance >= 0
                && STABILITY_CHECK_TIMER_MAP.get(STABILITY_CHECK_PERFORMANCE_SWITCH) >= 0;

        // Switch between primary networks only controlled by device config
        if (!enabledByDeviceConfig) return false;

        // Switch between primary and OPPT networks is controlled by both device and carrier configs
        return shouldExcludeOpportunisticForSwitch() || isPerformanceBasedSwitchEnabledForOppt();
    }

    private boolean isRatSignalStrengthBaseSwitchQualified(
            PhoneSignalStatus.UsableState currentUsableState,
            PhoneSignalStatus.UsableState candidateUsableState) {
        return isRatSignalStrengthBasedSwitchEnabled()
                && currentUsableState == PhoneSignalStatus.UsableState.HOME
                && candidateUsableState == PhoneSignalStatus.UsableState.HOME;
    }

    /**
     * Called when the current environment suits auto data switch.
     * Start pre-switch validation if the current environment suits auto data switch for
     * {@link #STABILITY_CHECK_TIMER_MAP} MS.
     * @param targetPhoneId the target phone Id.
     * @param switchType {@code true} determines stability check timer.
     * @param needValidation {@code true} if validation is needed.
     */
    private void startStabilityCheck(int targetPhoneId, @PreSwitchStabilityCheckType int switchType,
            boolean needValidation) {
        StabilityEventExtra eventExtras = (StabilityEventExtra)
                mScheduledEventsToExtras.getOrDefault(EVENT_STABILITY_CHECK_PASSED,
                        new StabilityEventExtra(INVALID_PHONE_INDEX, -1 /*invalid switch type*/,
                                false /*isForPerformance*/));
        long delayMs = -1;
        // Check if already scheduled one with that combination of extras.
        if (eventExtras.targetPhoneId != targetPhoneId
                || eventExtras.needValidation != needValidation
                || eventExtras.switchType != switchType) {
            eventExtras =
                    new StabilityEventExtra(targetPhoneId, switchType, needValidation);

            // Reset with new timer.
            delayMs = STABILITY_CHECK_TIMER_MAP.get(switchType);
            scheduleEventWithTimer(EVENT_STABILITY_CHECK_PASSED, eventExtras, delayMs);
        }
        logl("startStabilityCheck: "
                + (delayMs != -1 ? "scheduling " : "already scheduled ")
                + eventExtras);
    }

    /**
     * Use when need to schedule with timer. Short timer uses handler, while the longer timer uses
     * alarm manager to account for real time elapse.
     *
     * @param event The event.
     * @param extras Any extra data associated with the event.
     * @param delayMs The delayed interval in ms.
     */
    private void scheduleEventWithTimer(int event, @NonNull Object extras, long delayMs) {
        // Get singleton alarm listener.
        mEventsToAlarmListener.putIfAbsent(event, () -> sendEmptyMessage(event));
        AlarmManager.OnAlarmListener listener = mEventsToAlarmListener.get(event);

        // Cancel any existing.
        removeMessages(event);
        mAlarmManager.cancel(listener);
        // Override with new extras.
        mScheduledEventsToExtras.put(event, extras);
        // Reset timer, longer than or equal to which we use alarm manager to schedule.
        if (delayMs < RETRY_LONG_DELAY_TIMER_THRESHOLD_MILLIS) {
            // Use handler for short timer.
            sendEmptyMessageDelayed(event, delayMs);
        } else {
            // Not using setWhileIdle because it can wait util the next time the device wakes up to
            // save power.
            // If another evaluation is processed before the alarm fires,
            // this timer is restarted (AlarmManager using the same listener resets the
            // timer).
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + delayMs,
                    LOG_TAG /*debug tag*/, listener, this);
        }
    }

    /** Auto data switch evaluation reason to string. */
    @NonNull
    public static String evaluationReasonToString(
            @AutoDataSwitchEvaluationReason int reason) {
        return switch (reason) {
            case EVALUATION_REASON_REGISTRATION_STATE_CHANGED -> "REGISTRATION_STATE_CHANGED";
            case EVALUATION_REASON_DISPLAY_INFO_CHANGED -> "DISPLAY_INFO_CHANGED";
            case EVALUATION_REASON_SIGNAL_STRENGTH_CHANGED -> "SIGNAL_STRENGTH_CHANGED";
            case EVALUATION_REASON_DEFAULT_NETWORK_CHANGED -> "DEFAULT_NETWORK_CHANGED";
            case EVALUATION_REASON_DATA_SETTINGS_CHANGED -> "DATA_SETTINGS_CHANGED";
            case EVALUATION_REASON_RETRY_VALIDATION -> "RETRY_VALIDATION";
            case EVALUATION_REASON_SIM_LOADED -> "SIM_LOADED";
            case EVALUATION_REASON_VOICE_CALL_END -> "VOICE_CALL_END";
            case EVALUATION_REASON_CARRIER_CONFIG_CHANGED -> "CARRIER_CONFIG_CHANGED";
            default -> "Unknown(" + reason + ")";
        };
    }

    /** Opportunistic network switch policy to string. */
    @NonNull
    public static String opportunisticNetworkSwitchPolicyToString(
            @OpportunisticNetworkSwitchPolicy int policy) {
        return switch (policy) {
            case OPP_AUTO_DATA_SWITCH_POLICY_DISABLED -> "DISABLED";
            case OPP_AUTO_DATA_SWITCH_POLICY_FOLLOW_SYSTEM -> "FOLLOW_SYSTEM";
            case OPP_AUTO_DATA_SWITCH_POLICY_BITMASK_AVAILABILITY -> "AVAILABILITY";
            case OPP_AUTO_DATA_SWITCH_POLICY_BITMASK_PERFORMANCE -> "PERFORMANCE";
            default -> "Unknown(" + policy + ")";
        };
    }

    /** @return {@code true} if the sub is active. */
    private boolean isActiveSubId(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionManagerService
                .getSubscriptionInfoInternal(subId);
        return subInfo != null && subInfo.isActive();
    }

    /** @return {@code true} if the sub is active and visible. */
    private boolean isActiveVisibleSubId(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionManagerService
                .getSubscriptionInfoInternal(subId);
        return subInfo != null && subInfo.isActive() && subInfo.isVisible();
    }

    /**
     * Called when default network capabilities changed. If default network is active on
     * non-cellular, switch back to the default data phone. If default network is lost, try to find
     * another sub to switch to.
     * @param networkCapabilities {@code null} indicates default network lost.
     */
    public void updateDefaultNetworkCapabilities(
            @Nullable NetworkCapabilities networkCapabilities) {
        if (networkCapabilities != null) {
            // Exists default network
            mDefaultNetworkIsOnNonCellular = !networkCapabilities.hasTransport(TRANSPORT_CELLULAR);
            if (mDefaultNetworkIsOnNonCellular
                    && isActiveSubId(mPhoneSwitcher.getAutoSelectedDataSubId())) {
                logl("default network is active on non cellular, switch back to default");
                evaluateAutoDataSwitch(EVALUATION_REASON_DEFAULT_NETWORK_CHANGED);
            }
        } else {
            logl("default network is lost, try to find another active sub to switch to");
            mDefaultNetworkIsOnNonCellular = false;
            evaluateAutoDataSwitch(EVALUATION_REASON_DEFAULT_NETWORK_CHANGED);
        }
    }

    /**
     * Cancel any auto switch attempts when the current environment is not suitable for auto switch.
     */
    private void cancelAnyPendingSwitch() {
        mSelectedTargetPhoneId = INVALID_PHONE_INDEX;
        resetFailedCount();
        if (mScheduledEventsToExtras.containsKey(EVENT_STABILITY_CHECK_PASSED)) {
            if (mEventsToAlarmListener.containsKey(EVENT_STABILITY_CHECK_PASSED)) {
                mAlarmManager.cancel(mEventsToAlarmListener.get(EVENT_STABILITY_CHECK_PASSED));
            } else {
                logle("cancelAnyPendingSwitch: EVENT_STABILITY_CHECK_PASSED listener is null");
            }
            removeMessages(EVENT_STABILITY_CHECK_PASSED);
            mScheduledEventsToExtras.remove(EVENT_STABILITY_CHECK_PASSED);
        }
        mPhoneSwitcherCallback.onRequireCancelAnyPendingAutoSwitchValidation();
    }

    /**
     * Display a notification the first time auto data switch occurs.
     * @param phoneId The phone Id of the current preferred phone.
     * @param isDueToAutoSwitch {@code true} if the switch was due to auto data switch feature.
     */
    public void displayAutoDataSwitchNotification(int phoneId, boolean isDueToAutoSwitch) {
        // Don't display notification when networks switched between primary and opportunistic
        if (!shouldExcludeOpportunisticForSwitch()) return;

        NotificationManager notificationManager = mContext.getSystemService(
                NotificationManager.class);
        if (notificationManager == null) return;
        if (mDisplayedNotification) {
            // cancel posted notification if any exist
            notificationManager.cancel(AUTO_DATA_SWITCH_NOTIFICATION_TAG,
                    AUTO_DATA_SWITCH_NOTIFICATION_ID);
            return;
        }
        // proceed only the first time auto data switch occurs, which includes data during call
        if (!isDueToAutoSwitch) {
            return;
        }
        SubscriptionInfo subInfo = mSubscriptionManagerService
                .getSubscriptionInfo(mSubscriptionManagerService.getSubId(phoneId));
        if (subInfo == null || subInfo.isOpportunistic()) {
            logle("displayAutoDataSwitchNotification: phoneId="
                    + phoneId + " unexpected subInfo " + subInfo);
            return;
        }
        int subId = subInfo.getSubscriptionId();
        logl("displayAutoDataSwitchNotification: display for subId=" + subId);
        // "Mobile network settings" screen / dialog
        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS);
        final Bundle fragmentArgs = new Bundle();
        // Special contract for Settings to highlight permission row
        fragmentArgs.putString(SETTINGS_EXTRA_FRAGMENT_ARG_KEY, AUTO_DATA_SWITCH_SETTING_R_ID);
        intent.putExtra(Settings.EXTRA_SUB_ID, subId);
        intent.putExtra(SETTINGS_EXTRA_SHOW_FRAGMENT_ARGUMENTS, fragmentArgs);
        PendingIntent contentIntent = PendingIntent.getActivity(
                mContext, subId, intent, PendingIntent.FLAG_IMMUTABLE);

        CharSequence activeCarrierName = subInfo.getDisplayName();
        CharSequence contentTitle = mContext.getString(
                com.android.internal.R.string.auto_data_switch_title, activeCarrierName);
        CharSequence contentText = mContext.getText(
                com.android.internal.R.string.auto_data_switch_content);

        final Notification notif = new Notification.Builder(mContext)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setChannelId(NotificationChannelController.CHANNEL_ID_MOBILE_DATA_STATUS)
                .setContentIntent(contentIntent)
                .setStyle(new Notification.BigTextStyle().bigText(contentText))
                .build();
        notificationManager.notify(AUTO_DATA_SWITCH_NOTIFICATION_TAG,
                AUTO_DATA_SWITCH_NOTIFICATION_ID, notif);
        mDisplayedNotification = true;
    }

    /** Enable future switch retry again. Called when switch condition changed. */
    public void resetFailedCount() {
        mAutoSwitchValidationFailedCount = 0;
    }

    /**
     * Called when skipped switch due to validation failed. Schedule retry to switch again.
     */
    public void evaluateRetryOnValidationFailed() {
        if (mAutoSwitchValidationFailedCount < mAutoDataSwitchValidationMaxRetry) {
            evaluateAutoDataSwitch(EVALUATION_REASON_RETRY_VALIDATION);
            mAutoSwitchValidationFailedCount++;
        } else {
            logl("evaluateRetryOnValidationFailed: reached max auto switch retry count "
                    + mAutoDataSwitchValidationMaxRetry);
            mAutoSwitchValidationFailedCount = 0;
        }
    }

    /**
     * @param phoneId The phone Id to check.
     * @return {@code true} if the phone Id is an active modem.
     */
    private boolean isActiveModemPhone(int phoneId) {
        return phoneId >= 0 && phoneId < mPhonesSignalStatus.length;
    }

    /** Auto data switch stability check type to string. */
    @NonNull
    public static String switchTypeToString(@PreSwitchStabilityCheckType int switchType) {
        return switch (switchType) {
            case STABILITY_CHECK_AVAILABILITY_SWITCH -> "AVAILABILITY_SWITCH";
            case STABILITY_CHECK_PERFORMANCE_SWITCH -> "PERFORMANCE_SWITCH";
            case STABILITY_CHECK_AVAILABILITY_SWITCH_BACK -> "AVAILABILITY_SWITCH_BACK";
            default -> "Unknown(" + switchType + ")";
        };
    }

    /**
     * Exclude opportunistic profiles for switch when ANY of condition below is fulfilled:
     * - Feature flag is disabled
     * - No active primary (visible) and opportunistic profiles in the same group
     * - Primary profile doesn't override carrier config to enable the feature
     */
    private boolean shouldExcludeOpportunisticForSwitch() {
        if (!sFeatureFlags.macroBasedOpportunisticNetworks()) {
            log("OPPT switch excluded: feature flag is disabled!");
            return true;
        }

        if (getOpptSwitchPolicyForPrimaryPhone() == OPP_AUTO_DATA_SWITCH_POLICY_DISABLED) {
            log("OPPT switch excluded: primary phone doesn't enable the feature!");
            return true;
        }

        final List<SubscriptionInfo> infos =
                mSubscriptionManagerService.getActiveSubscriptionInfoList(
                        mContext.getOpPackageName(),
                        mContext.getAttributionTag(), true /* mIsForAllUserProfiles */);
        Set<ParcelUuid> primarySubs = new ArraySet<>();
        Set<ParcelUuid> oppSubs = new ArraySet<>();
        for (SubscriptionInfo si : infos) {
            if (si.getGroupUuid() == null) continue;
            if (si.isOpportunistic()) {
                oppSubs.add(si.getGroupUuid());
            } else {
                primarySubs.add(si.getGroupUuid());
            }
        }
        primarySubs.retainAll(oppSubs);
        return primarySubs.isEmpty();
    }

    /**
     * Get carrier config bundle for the primary phone.
     */
    private PersistableBundle getCarrierConfigForPrimaryPhone() {
        int[] activeSubs = mSubscriptionManagerService.getActiveSubIdList(true /*visibleOnly*/);
        // TODO(b/453655423): improve logic when managing CC for primary network switch
        if (mCarrierConfigManager == null || activeSubs.length != 1) {
            return new PersistableBundle();
        }
        return CarrierConfigManager.getCarrierConfigSubset(
                mContext, activeSubs[0], OPP_AUTO_DATA_SWITCH_CARRIER_CONFIG_KEYS);
    }

    /**
     * @return switch policy from carrier config for primary subscription.
     */
    @OpportunisticNetworkSwitchPolicy
    private int getOpptSwitchPolicyForPrimaryPhone() {
        return getCarrierConfigForPrimaryPhone()
                .getInt(
                        KEY_OPP_AUTO_DATA_SWITCH_POLICY_INT,
                        OPP_AUTO_DATA_SWITCH_POLICY_DISABLED);
    }

    /**
     * @return {@code true} if the opportunistic network switch policy is enabled for the given
     * bitmask.
     */
    private boolean isOpptSwitchPolicyEnabled(
            @CarrierConfigManager.OpportunisticNetworkSwitchPolicyBitmask int policyBitmask) {
        final int policy = getOpptSwitchPolicyForPrimaryPhone();
        return (policy & policyBitmask) != 0;
    }

    private boolean isAvailabilityBasedSwitchEnabledForOppt() {
        return isOpptSwitchPolicyEnabled(OPP_AUTO_DATA_SWITCH_POLICY_BITMASK_AVAILABILITY);
    }

    private boolean isPerformanceBasedSwitchEnabledForOppt() {
        return isOpptSwitchPolicyEnabled(OPP_AUTO_DATA_SWITCH_POLICY_BITMASK_PERFORMANCE);
    }

    /**
     * Log debug messages.
     * @param s debug messages
     */
    private void log(@NonNull String s) {
        Rlog.d(LOG_TAG, s);
    }

    /**
     * Log error messages.
     * @param s error messages
     */
    private void loge(@NonNull String s) {
        Rlog.e(LOG_TAG, s);
    }

    /**
     * Log debug messages and also log into the local log.
     * @param s debug messages
     */
    private void logl(@NonNull String s) {
        log(s);
        mLocalLog.log(s);
    }

    /**
     * Log error messages and also log into the local log.
     * @param s error messages
     */
    private void logle(@NonNull String s) {
        loge(s);
        mLocalLog.log(s);
    }

    /**
     * Dump the state of DataNetworkController
     *
     * @param fd File descriptor
     * @param printWriter Print writer
     * @param args Arguments
     */
    public void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        pw.println("AutoDataSwitchController:");
        pw.increaseIndent();
        pw.println("mScoreTolerance=" + mScoreTolerance);
        pw.println("mAutoDataSwitchValidationMaxRetry=" + mAutoDataSwitchValidationMaxRetry
                + " mAutoSwitchValidationFailedCount=" + mAutoSwitchValidationFailedCount);
        pw.println("mRequirePingTestBeforeDataSwitch=" + mRequirePingTestBeforeSwitch);
        pw.println("STABILITY_CHECK_TIMER_MAP:");
        STABILITY_CHECK_TIMER_MAP.forEach((key, value)
                -> pw.println(switchTypeToString(key) + ": " + value));
        pw.println("mSelectedTargetPhoneId=" + mSelectedTargetPhoneId);
        pw.println("autoDataSwitchPolicyForOppt=" + opportunisticNetworkSwitchPolicyToString(
                getOpptSwitchPolicyForPrimaryPhone()));
        pw.increaseIndent();
        for (PhoneSignalStatus status: mPhonesSignalStatus) {
            pw.println(status);
        }
        pw.decreaseIndent();
        mLocalLog.dump(fd, pw, args);
        pw.decreaseIndent();
    }
}

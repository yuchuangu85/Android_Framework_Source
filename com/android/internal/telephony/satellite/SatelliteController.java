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

package com.android.internal.telephony.satellite;

import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY;
import static android.provider.Settings.ACTION_SATELLITE_SETTING;
import static android.telephony.CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC;
import static android.telephony.CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_HYBRID;
import static android.telephony.CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL;
import static android.telephony.CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_TYPE;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_CONFIG_APPLIED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_NTN_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_INT;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_PROVIDER_PER_COUNTRY_BUNDLE;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_REDIRECTION_DESTINATION_STRING;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_SUPPORTED_SATELLITE_NOTIFICATION_HYSTERESIS_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE;
import static android.telephony.CarrierConfigManager.KEY_EMERGENCY_CALL_TO_SATELLITE_T911_HANDOVER_TIMEOUT_MILLIS_INT;
import static android.telephony.CarrierConfigManager.KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_REGIONAL_SATELLITE_EARFCN_BUNDLE;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ATTACH_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_CONFIGS_PER_PLMN_BUNDLE;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_CONNECTED_NOTIFICATION_THROTTLE_MILLIS_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_DATA_SUPPORT_MODE_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_DISPLAY_NAME_STRING;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ESOS_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_NIDD_APN_NAME_STRING;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_ESOS_INACTIVITY_TIMEOUT_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_P2P_SMS_INACTIVITY_TIMEOUT_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_P2P_SMS_SUPPORTED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_SCREEN_OFF_INACTIVITY_TIMEOUT_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_ROAMING_TURN_OFF_SESSION_FOR_EMERGENCY_CALL_BOOL;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_SOS_MAX_DATAGRAM_SIZE_BYTES_INT;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_SUPPORTED_DISASTER_PLMN_STRING_ARRAY;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_SUPPORTED_EMERGENCY_PLMN_STRING_ARRAY;
import static android.telephony.CarrierConfigManager.KEY_SATELLITE_SUPPORTED_MSG_APPS_STRING_ARRAY;
import static android.telephony.SubscriptionManager.SATELLITE_ATTACH_ENABLED_FOR_CARRIER;
import static android.telephony.SubscriptionManager.SATELLITE_ENTITLEMENT_STATUS;
import static android.telephony.SubscriptionManager.isValidSubscriptionId;
import static android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID;
import static android.telephony.satellite.NtnSignalStrength.NTN_SIGNAL_STRENGTH_NONE;
import static android.telephony.satellite.SatelliteManager.EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_SOS;
import static android.telephony.satellite.SatelliteManager.EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_T911;
import static android.telephony.satellite.SatelliteManager.KEY_NTN_SIGNAL_STRENGTH;
import static android.telephony.satellite.SatelliteManager.SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT;
import static android.telephony.satellite.SatelliteManager.SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_INVALID_ARGUMENTS;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_MODEM_ERROR;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_MODEM_TIMEOUT;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_NO_VALID_SATELLITE_SUBSCRIPTION;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_REQUEST_ABORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_SUCCESS;

import static com.android.internal.telephony.configupdate.ConfigProviderAdaptor.DOMAIN_SATELLITE;

import android.annotation.ArrayRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.DisplayManager;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.AnomalyReporter;
import android.telephony.CarrierConfigManager;
import android.telephony.DropBoxManagerLoggerBackend;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PersistentLogger;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyRegistryManager;
import android.telephony.satellite.EnableRequestAttributes;
import android.telephony.satellite.INtnSignalStrengthCallback;
import android.telephony.satellite.ISatelliteCapabilitiesCallback;
import android.telephony.satellite.ISatelliteDatagramCallback;
import android.telephony.satellite.ISatelliteModemStateCallback;
import android.telephony.satellite.ISatelliteProvisionStateCallback;
import android.telephony.satellite.ISatelliteTransmissionUpdateCallback;
import android.telephony.satellite.ISelectedNbIotSatelliteSubscriptionCallback;
import android.telephony.satellite.NtnSignalStrength;
import android.telephony.satellite.PlmnSatelliteConfig;
import android.telephony.satellite.PointingUiAppLaunchIntentAttributes;
import android.telephony.satellite.SatelliteAccessConfiguration;
import android.telephony.satellite.SatelliteCapabilities;
import android.telephony.satellite.SatelliteCommunicationAccessStateCallback;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.SatelliteModemEnableRequestAttributes;
import android.telephony.satellite.SatelliteSubscriberInfo;
import android.telephony.satellite.SatelliteSubscriberProvisionStatus;
import android.telephony.satellite.SatelliteSubscriptionInfo;
import android.telephony.satellite.SystemSelectionSpecifier;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.uwb.UwbManager;
import android.view.Display;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.DeviceStateMonitor;
import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyCountryDetector;
import com.android.internal.telephony.configupdate.ConfigParser;
import com.android.internal.telephony.configupdate.ConfigProviderAdaptor;
import com.android.internal.telephony.configupdate.TelephonyConfigUpdateInstallReceiver;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.metrics.CarrierRoamingSatelliteControllerStats;
import com.android.internal.telephony.satellite.metrics.CarrierRoamingSatelliteSessionStats;
import com.android.internal.telephony.satellite.metrics.ControllerMetricsStats;
import com.android.internal.telephony.satellite.metrics.ProvisionMetricsStats;
import com.android.internal.telephony.satellite.metrics.SessionMetricsStats;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.internal.telephony.util.ArrayUtils;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.internal.telephony.util.WorkerThread;
import com.android.internal.util.FunctionalUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Satellite controller is the backend service of
 * {@link android.telephony.satellite.SatelliteManager}.
 */
public class SatelliteController extends Handler {
    private static final String TAG = "SatelliteController";
    /** Whether enabling verbose debugging message or not. */
    private static final boolean DBG = false;
    private static final String ALLOW_MOCK_MODEM_PROPERTY = "persist.radio.allow_mock_modem";
    private static final boolean DEBUG = !"user".equals(Build.TYPE);
    /** File used to store shared preferences related to satellite. */
    public static final String SATELLITE_SHARED_PREF = "satellite_shared_pref";
    public static final String SATELLITE_SUBSCRIPTION_ID = "satellite_subscription_id";
    /** Value to pass for the setting key SATELLITE_MODE_ENABLED, enabled = 1, disabled = 0 */
    public static final int SATELLITE_MODE_ENABLED_TRUE = 1;
    public static final int SATELLITE_MODE_ENABLED_FALSE = 0;
    public static final int INVALID_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE = -1;
    /**
     * This is used by CTS to override the timeout duration to wait for the response of the request
     * to enable satellite.
     */
    public static final int TIMEOUT_TYPE_WAIT_FOR_SATELLITE_ENABLING_RESPONSE = 1;
    /** This is used by CTS to override demo pointing aligned duration. */
    public static final int TIMEOUT_TYPE_DEMO_POINTING_ALIGNED_DURATION_MILLIS = 2;
    /** This is used by CTS to override demo pointing not aligned duration. */
    public static final int TIMEOUT_TYPE_DEMO_POINTING_NOT_ALIGNED_DURATION_MILLIS = 3;
    /** This is used by CTS to override evaluate esos profiles prioritization duration. */
    public static final int TIMEOUT_TYPE_EVALUATE_ESOS_PROFILES_PRIORITIZATION_DURATION_MILLIS = 4;
    /** This is used by CTS to override evaluate carrier roaming ntn eligibility change duration. */
    public static final int TIMEOUT_TYPE_EMERGENCY_CALL_MONITORING_DURATION_MILLIS = 5;
    /** This is used by CTS to override last emergency call time. */
    public static final int TIMEOUT_TYPE_LAST_EMERGENCY_CALL_TIME = 6;
    /** Key used to read/write OEM-enabled satellite provision status in shared preferences. */
    private static final String OEM_ENABLED_SATELLITE_PROVISION_STATUS_KEY =
            "oem_enabled_satellite_provision_status_key";
    /** Key used to read/write default messages application NTN SMS support
     * in shared preferences. */
    @VisibleForTesting(visibility =  VisibleForTesting.Visibility.PRIVATE)
    public static final String NTN_SMS_SUPPORTED_BY_MESSAGES_APP_KEY =
            "ntn_sms_supported_by_messages_app_key";
    public static final String CARRIER_ROAMING_NTN_ALL_SATELLITE_PLMN_SET_KEY =
            "carrier_roaming_ntn_all_satellite_plmn_set_key";

    public static final long DEFAULT_CARRIER_EMERGENCY_CALL_WAIT_FOR_CONNECTION_TIMEOUT_MILLIS =
            TimeUnit.SECONDS.toMillis(30);

    /** Sets general metrics report cool down to 23 hours to help enforcing privacy requirement.*/
    private static final long REGULAR_METRIC_REPORTING_INTERVAL_MILLIS =
            TimeUnit.HOURS.toMillis(23);

    /**
     * Delay SatelliteEnable request when network selection auto. current RIL not verified to
     * response right after network selection auto changed. Some RIL has delay for waiting in-svc
     * with Automatic selection request.
     */
    private static final long DELAY_WAITING_SET_NETWORK_SELECTION_AUTO_MILLIS =
            TimeUnit.SECONDS.toMillis(1);

    /**
     * Delay used to debounce package change events to avoid redundant evaluations for a single
     * user action.
     */
    private static final long DEBOUNCE_DELAY_MILLIS = 100;

    /** Message codes used in handleMessage() */
    //TODO: Move the Commands and events related to position updates to PointingAppController
    private static final int CMD_START_SATELLITE_TRANSMISSION_UPDATES = 1;
    private static final int EVENT_START_SATELLITE_TRANSMISSION_UPDATES_DONE = 2;
    private static final int CMD_STOP_SATELLITE_TRANSMISSION_UPDATES = 3;
    private static final int EVENT_STOP_SATELLITE_TRANSMISSION_UPDATES_DONE = 4;
    private static final int CMD_PROVISION_SATELLITE_SERVICE = 7;
    private static final int EVENT_PROVISION_SATELLITE_SERVICE_DONE = 8;
    private static final int CMD_DEPROVISION_SATELLITE_SERVICE = 9;
    private static final int EVENT_DEPROVISION_SATELLITE_SERVICE_DONE = 10;
    private static final int CMD_SET_SATELLITE_ENABLED = 11;
    private static final int EVENT_SET_SATELLITE_ENABLED_DONE = 12;
    private static final int CMD_IS_SATELLITE_ENABLED = 13;
    private static final int EVENT_IS_SATELLITE_ENABLED_DONE = 14;
    private static final int CMD_IS_SATELLITE_SUPPORTED = 15;
    private static final int EVENT_IS_SATELLITE_SUPPORTED_DONE = 16;
    private static final int CMD_GET_SATELLITE_CAPABILITIES = 17;
    private static final int EVENT_GET_SATELLITE_CAPABILITIES_DONE = 18;
    private static final int CMD_GET_TIME_SATELLITE_NEXT_VISIBLE = 21;
    private static final int EVENT_GET_TIME_SATELLITE_NEXT_VISIBLE_DONE = 22;
    private static final int EVENT_RADIO_STATE_CHANGED = 23;
    private static final int CMD_IS_SATELLITE_PROVISIONED = 24;
    private static final int EVENT_IS_SATELLITE_PROVISIONED_DONE = 25;
    private static final int EVENT_PENDING_DATAGRAMS = 27;
    private static final int EVENT_SATELLITE_MODEM_STATE_CHANGED = 28;
    private static final int EVENT_SET_SATELLITE_PLMN_INFO_DONE = 29;
    private static final int CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE = 30;
    private static final int EVENT_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE_DONE = 31;
    private static final int CMD_REQUEST_NTN_SIGNAL_STRENGTH = 32;
    private static final int EVENT_REQUEST_NTN_SIGNAL_STRENGTH_DONE = 33;
    private static final int EVENT_NTN_SIGNAL_STRENGTH_CHANGED = 34;
    private static final int CMD_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING = 35;
    private static final int EVENT_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING_DONE = 36;
    private static final int EVENT_SERVICE_STATE_CHANGED = 37;
    private static final int EVENT_SATELLITE_CAPABILITIES_CHANGED = 38;
    protected static final int EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT = 39;
    private static final int EVENT_SATELLITE_CONFIG_DATA_UPDATED = 40;
    private static final int EVENT_SATELLITE_SUPPORTED_STATE_CHANGED = 41;
    private static final int EVENT_NOTIFY_NTN_HYSTERESIS_TIMED_OUT = 42;
    private static final int CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION = 43;
    private static final int CMD_UPDATE_PROVISION_SATELLITE_TOKEN = 44;
    private static final int EVENT_UPDATE_PROVISION_SATELLITE_TOKEN_DONE = 45;
    private static final int EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT = 46;
    private static final int EVENT_WIFI_CONNECTIVITY_STATE_CHANGED = 47;
    protected static final int EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT = 49;
    private static final int CMD_UPDATE_SATELLITE_ENABLE_ATTRIBUTES = 50;
    private static final int EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE = 51;
    protected static final int
            EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT = 52;
    private static final int EVENT_WAIT_FOR_REGULAR_METRICS_REPORT_HYSTERESIS_TIMED_OUT = 53;
    protected static final int EVENT_SATELLITE_REGISTRATION_FAILURE = 54;
    private static final int EVENT_TERRESTRIAL_NETWORK_AVAILABLE_CHANGED = 55;
    private static final int EVENT_SET_NETWORK_SELECTION_AUTO_DONE = 56;
    private static final int EVENT_SIGNAL_STRENGTH_CHANGED = 57;
    private static final int CMD_UPDATE_SYSTEM_SELECTION_CHANNELS = 58;
    private static final int EVENT_UPDATE_SYSTEM_SELECTION_CHANNELS_DONE = 59;
    private static final int EVENT_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_CHANGED = 60;
    private static final int CMD_EVALUATE_CARRIER_ROAMING_NTN_ELIGIBILITY_CHANGE = 61;
    private static final int CMD_LOCATION_SERVICE_STATE_CHANGED = 62;
    protected static final int
        EVENT_WAIT_FOR_UPDATE_SYSTEM_SELECTION_CHANNELS_RESPONSE_TIMED_OUT = 63;
    private static final int CMD_GET_SATELLITE_ENABLED_FOR_CARRIER = 64;
    private static final int EVENT_GET_SATELLITE_ENABLED_FOR_CARRIER_DONE = 65;
    private static final int REQUEST_SATELLITE_ENABLED = 66;
    private static final int REQUEST_IS_SATELLITE_ENABLED = 67;
    private static final int REQUEST_IS_DEMO_MODE_ENABLED = 68;
    private static final int REQUEST_IS_EMERGENCY_MODE_ENABLED = 69;
    private static final int REQUEST_IS_SATELLITE_SUPPORTED = 70;
    private static final int REQUEST_SATELLITE_CAPABILITIES = 71;
    private static final int REQUEST_START_SATELLITE_TRANSMISSION_UPDATES = 72;
    private static final int REQUEST_STOP_SATELLITE_TRANSMISSION_UPDATES = 73;
    private static final int REQUEST_IS_SATELLITE_PROVISIONED = 74;
    private static final int REQUEST_POLL_PENDING_DATAGRAMS = 75;
    private static final int REQUEST_SEND_DATAGRAM = 76;
    private static final int REQUEST_TIME_FOR_NEXT_SATELLITE_VISIBILITY = 77;
    private static final int REQUEST_SATELLITE_DISPLAY_NAME = 78;
    private static final int REQUEST_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_ID = 79;
    private static final int REQUEST_SET_DEVICE_ALIGNED_WITH_SATELLITE = 80;
    private static final int REQUEST_ADD_ATTACH_RESTRICTION_FOR_CARRIER = 81;
    private static final int REQUEST_REMOVE_ATTACH_RESTRICTION_FOR_CARRIER = 82;
    private static final int REQUEST_NTN_SIGNAL_STRENGTH = 83;
    private static final int REQUEST_SATELLITE_SUBSCRIBER_PROVISION_STATUS = 84;
    private static final int REQUEST_SET_NTN_SMS_SUPPORTED_BY_MESSAGES_APP = 85;
    private static final int REQUEST_PROVISION_SATELLITE = 86;
    private static final int REQUEST_DEPROVISION_SATELLITE = 87;
    private static final int EVENT_SATELLITE_ACCESS_ALLOWED_STATE_CHANGED = 88;
    protected static final int EVENT_SATELLITE_ACCESS_CONFIGURATION_CHANGED = 89;
    private static final int EVENT_BT_WIFI_NFC_STATE_CHANGED = 90;
    private static final int EVENT_UWB_STATE_CHANGED = 91;
    private static final int EVENT_CARRIER_CONFIG_CHANGED = 92;
    private static final int EVENT_SATELLITE_ENTILEMENT_STATUS_UPDATED = 93;
    private static final int EVENT_PACKAGE_CHANGED = 94;
    private static final int EVENT_SCREEN_STATE_CHANGED = 95;
    private static final int EVENT_SET_SATELLITE_NETWORK_INFO_DONE = 96;
    private static final int REQUEST_POINTING_UI_APP_LAUNCH_INTENT = 97;

    private static final int TRUE = 1;
    private static final int FALSE = 0;

    private static final List<Integer> DTC_SATELLITE_TECHNOLOGY_LIST =
            List.of(SatelliteManager.NT_RADIO_TECHNOLOGY_LTE_DTC,
                    SatelliteManager.NT_RADIO_TECHNOLOGY_NR_DTC);

    @NonNull private static SatelliteController sInstance;
    @NonNull private final Context mContext;
    @NonNull private final SatelliteModemInterface mSatelliteModemInterface;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull protected SatelliteSessionController mSatelliteSessionController;
    @NonNull private final PointingAppController mPointingAppController;
    @NonNull private final DatagramController mDatagramController;
    @NonNull private final ControllerMetricsStats mControllerMetricsStats;
    @NonNull private final ProvisionMetricsStats mProvisionMetricsStats;
    @NonNull private SessionMetricsStats mSessionMetricsStats;
    @NonNull private CarrierRoamingSatelliteControllerStats mCarrierRoamingSatelliteControllerStats;

    @NonNull private final SubscriptionManagerService mSubscriptionManagerService;
    @NonNull private final TelephonyCountryDetector mCountryDetector;
    @NonNull private final TelecomManager mTelecomManager;
    private final CommandsInterface mCi;
    private ContentResolver mContentResolver;
    private final DeviceStateMonitor mDSM;
    private SatelliteOptimizedApplicationsTracker mSatelliteOptimizedApplicationsTracker;

    /** All the atomic variables are declared here. */
    // Flags to indicate whether the respective radio is enabled
    private AtomicBoolean mBTStateEnabled = new AtomicBoolean(false);
    private AtomicBoolean mNfcStateEnabled = new AtomicBoolean(false);
    private AtomicBoolean mUwbStateEnabled = new AtomicBoolean(false);
    private AtomicBoolean mWifiStateEnabled = new AtomicBoolean(false);
    // Flags to indicate that respective radios need to be disabled when satellite is enabled
    private AtomicBoolean mDisableBTOnSatelliteEnabled = new AtomicBoolean(false);
    private AtomicBoolean mDisableNFCOnSatelliteEnabled = new AtomicBoolean(false);
    private AtomicBoolean mDisableUWBOnSatelliteEnabled = new AtomicBoolean(false);
    private AtomicBoolean mDisableWifiOnSatelliteEnabled = new AtomicBoolean(false);
    protected AtomicBoolean mIsSatelliteSupported = null;
    private AtomicBoolean mNeedsSatellitePointing = new AtomicBoolean(false);
    private AtomicBoolean mIsDemoModeEnabled = new AtomicBoolean(false);
    private AtomicBoolean mIsEmergency = new AtomicBoolean(false);
    private AtomicBoolean mIsSatelliteEnabled = null;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected AtomicBoolean mIsRadioOn = new AtomicBoolean(false);
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected AtomicBoolean mRadioOffRequested = new AtomicBoolean(false);
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected AtomicBoolean mIsDeviceProvisioned = null;
    private AtomicBoolean mOverriddenIsSatelliteViaOemProvisioned = null;
    private AtomicBoolean mIgnorePlmnListFromStorage = new AtomicBoolean(false);
    private AtomicBoolean mNtnSmsSupportedByMessagesApp = null;
    private AtomicBoolean mIsWifiConnected = new AtomicBoolean(false);
    private AtomicBoolean mHasSentBroadcast = new AtomicBoolean(false);
    @SatelliteManager.SatelliteModemState
    private AtomicInteger mSatelliteModemState = new AtomicInteger(
            SatelliteManager.SATELLITE_MODEM_STATE_UNKNOWN);
    /** Flag to indicate that satellite is enabled successfully
     * and waiting for all the radios to be disabled so that success can be sent to callback
     */
    private AtomicBoolean mWaitingForRadioDisabled = new AtomicBoolean(false);
    private AtomicBoolean mWaitingForDisableSatelliteModemResponse = new AtomicBoolean(false);
    private AtomicBoolean mWaitingForSatelliteModemOff = new AtomicBoolean(false);
    /**
     * Boolean set to {@code true} when device is eligible to connect to carrier roaming
     * non-terrestrial network else set to {@code false}.
     */
    private AtomicBoolean mLastNotifiedNtnEligibility = null;
    // For satellite CTS test which to configure intent component with the necessary values.
    private AtomicBoolean mChangeIntentComponent = new AtomicBoolean(false);
    private AtomicBoolean mIsNotificationShowing = new AtomicBoolean(false);
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected AtomicBoolean mSatelliteAccessAllowed = new AtomicBoolean(false);
    private AtomicBoolean mOverrideNtnEligibility;
    private AtomicBoolean mOverriddenDisableSatelliteWhileEnableInProgressSupported = null;
    private AtomicLong mWaitTimeForSatelliteEnablingResponse = new AtomicLong(0);
    private AtomicLong mDemoPointingAlignedDurationMillis = new AtomicLong(0);
    private AtomicLong mDemoPointingNotAlignedDurationMillis = new AtomicLong(0);
    private AtomicLong mEvaluateEsosProfilesPrioritizationDurationMillis = new AtomicLong(0);
    private AtomicLong mLastEmergencyCallTime = new AtomicLong(0);
    private AtomicLong mSatelliteEmergencyModeDurationMillis = new AtomicLong(0);
    private AtomicLong mEmergencyCallMonitoringDurationMillisForCtsTest = new AtomicLong(0);
    private AtomicLong mSessionStartTimeStamp = new AtomicLong(0);
    private AtomicLong mSessionProcessingTimeStamp = new AtomicLong(0);
    private static AtomicLong sNextSatelliteEnableRequestId = new AtomicLong(0);
    private static AtomicLong sNextSystemSelectionChannelsUpdateRequestId = new AtomicLong(0);
    /**
     * This is used for testing only. When mEnforcedEmergencyCallToSatelliteHandoverType is valid,
     * Telephony will ignore the IMS registration status and cellular availability, and always send
     * the connection event EVENT_DISPLAY_EMERGENCY_MESSAGE to Dialer.
     */
    private AtomicInteger mEnforcedEmergencyCallToSatelliteHandoverType =
            new AtomicInteger(INVALID_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE);
    private AtomicInteger mDelayInSendingEventDisplayEmergencyMessage = new AtomicInteger(0);
    private AtomicInteger mSimSlotIdForLaunchingT911ConversationThread = new AtomicInteger(0);
    private AtomicInteger mMaxAllowedDataModeForCtsTest = new AtomicInteger(-1);
    private AtomicBoolean mUncapMaxAllowedDataMode = new AtomicBoolean(false);
    // The ID of the satellite subscription that has highest priority and is provisioned.
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected AtomicInteger mSelectedSatelliteSubId = new AtomicInteger(
            SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    protected AtomicInteger mResultReceiverTotalCount = new AtomicInteger(0);

    /** All the variables that require lock are declared here. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected final Object mSatellitePhoneLock = new Object();
    @GuardedBy("mSatellitePhoneLock")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected Phone mSatellitePhone = null;
    private final Object mSatelliteCapabilitiesLock = new Object();
    @GuardedBy("mSatelliteCapabilitiesLock")
    private SatelliteCapabilities mSatelliteCapabilities;
    private final Object mNtnSignalsStrengthLock = new Object();
    @GuardedBy("mNtnSignalsStrengthLock")
    private NtnSignalStrength mNtnSignalStrength =
            new NtnSignalStrength(NTN_SIGNAL_STRENGTH_NONE);
    private final Object mCarrierRoamingNtnAllSatellitePlmnSetLock = new Object();
    @GuardedBy("mCarrierRoamingNtnAllSatellitePlmnSetLock")
    private Set<String> mCarrierRoamingNtnAllSatellitePlmnSet = null;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected final Object mSatelliteAccessConfigLock = new Object();
    @GuardedBy("mSatelliteAccessConfigLock")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected List<Integer> mCurrentLocationTagIds = new ArrayList();
    @GuardedBy("mSatelliteAccessConfigLock")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull
    protected List<Integer> mCurrentLocationCarrierIds = new ArrayList();
    private final Object mSatelliteEnabledRequestLock = new Object();
    /* This variable is used to store the first enable request that framework has received in the
     * current session.
     */
    @GuardedBy("mSatelliteEnabledRequestLock")
    private RequestSatelliteEnabledArgument mSatelliteEnabledRequest = null;
    /* This variable is used to store a disable request that framework has received.
     */
    @GuardedBy("mSatelliteEnabledRequestLock")
    private RequestSatelliteEnabledArgument mSatelliteDisabledRequest = null;
    /* This variable is used to store an enable request that updates the enable attributes of an
     * existing satellite session.
     */
    @GuardedBy("mSatelliteEnabledRequestLock")
    private RequestSatelliteEnabledArgument mSatelliteEnableAttributesUpdateRequest = null;
    @NonNull protected final Object mSatelliteTokenProvisionedLock = new Object();
    // key : priority, low value is high, value : List<SubscriptionInfo>
    @GuardedBy("mSatelliteTokenProvisionedLock")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected TreeMap<Integer, List<SubscriptionInfo>> mSubsInfoListPerPriority = new TreeMap<>();
    // List of subscriber information and status at the time of last evaluation
    @GuardedBy("mSatelliteTokenProvisionedLock")
    private List<SatelliteSubscriberProvisionStatus> mLastEvaluatedSubscriberProvisionStatus =
            new ArrayList<>();
    // The last ICC ID that framework configured to modem.
    @GuardedBy("mSatelliteTokenProvisionedLock")
    private String mLastConfiguredIccId;

    private final AtomicBoolean mRegisteredForPendingDatagramCountWithSatelliteService =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteModemStateChangedWithSatelliteService =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForNtnSignalStrengthChanged = new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteCapabilitiesChanged =
            new AtomicBoolean(false);
    private final AtomicBoolean mIsModemEnabledReportingNtnSignalStrength =
            new AtomicBoolean(false);
    private final AtomicBoolean mLatestRequestedStateForNtnSignalStrengthReport =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteSupportedStateChanged =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteRegistrationFailure =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForTerrestrialNetworkAvailableChanged =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteCommunicationAccessStateChanged =
        new AtomicBoolean(false);

    /**
     * Map key: subId, value: PersistableBundle
     */
    @NonNull private final ConcurrentHashMap<Integer, PersistableBundle> mCarrierConfigArray =
            new ConcurrentHashMap<>();
    /**
     * Map key: subId, value: callback to get error code of the provision request.
     */
    private final ConcurrentHashMap<Integer, Consumer<Integer>> mSatelliteProvisionCallbacks =
            new ConcurrentHashMap<>();

    /**
     * Map key: binder of the callback, value: callback to receive provision state changed events.
     */
    private final ConcurrentHashMap<IBinder, ISatelliteProvisionStateCallback>
            mSatelliteProvisionStateChangedListeners = new ConcurrentHashMap<>();
    /**
     * Map key: binder of the callback, value: callback to receive non-terrestrial signal strength
     * state changed events.
     */
    private final ConcurrentHashMap<IBinder, INtnSignalStrengthCallback>
            mNtnSignalStrengthChangedListeners = new ConcurrentHashMap<>();
    /**
     * Map key: binder of the callback, value: callback to receive satellite capabilities changed
     * events.
     */
    private final ConcurrentHashMap<IBinder, ISatelliteCapabilitiesCallback>
            mSatelliteCapabilitiesChangedListeners = new ConcurrentHashMap<>();
    /**
     * Map key: binder of the callback, value: callback to receive supported state changed events.
     */
    private final ConcurrentHashMap<IBinder, IBooleanConsumer>
            mSatelliteSupportedStateChangedListeners = new ConcurrentHashMap<>();

    /**
     * Map key: binder of the callback, value: callback to satellite registration failure
     */
    private final ConcurrentHashMap<IBinder, ISatelliteModemStateCallback>
            mSatelliteRegistrationFailureListeners = new ConcurrentHashMap<>();
    /**
     * Map key: binder of the callback, value: callback to receive terrestrial network
     * available changed
     */
    private final ConcurrentHashMap<IBinder, ISatelliteModemStateCallback>
            mTerrestrialNetworkAvailableChangedListeners = new ConcurrentHashMap<>();
    /**
     * Map key: binder of the callback, value: callback to receive selected NB IOT satellite
     * subscription changed
     */
    private final ConcurrentHashMap<IBinder, ISelectedNbIotSatelliteSubscriptionCallback>
            mSelectedNbIotSatelliteSubscriptionChangedListeners = new ConcurrentHashMap<>();

    protected ConcurrentHashMap<String, Integer> mResultReceiverCountPerMethodMap =
            new ConcurrentHashMap<>();
    /** Key: subId, value: (key: PLMN, value: set of
     * {@link android.telephony.NetworkRegistrationInfo.ServiceType})
     */
    @NonNull private final ConcurrentHashMap<Integer, Map<String, Set<Integer>>>
            mSatelliteServicesSupportedByCarriersFromConfig = new ConcurrentHashMap<>();

    /** Key: subId, value: list of emergency PLMNs */
    @NonNull private final ConcurrentHashMap<Integer, List<String>>
            mSupportedEmergencyPlmnsPerCarrierFromConfig = new ConcurrentHashMap<>();

    /** Key: subId, value: list of disaster PLMNs */
    @NonNull private final ConcurrentHashMap<Integer, List<String>>
            mSupportedDisasterPlmnsPerCarrierFromConfig = new ConcurrentHashMap<>();

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull protected final ConcurrentHashMap<Integer, CarrierRoamingSatelliteSessionStats>
            mCarrierRoamingSatelliteSessionStatsMap = new ConcurrentHashMap<>();
    /**
     * Key: Subscription ID; Value: set of
     * {@link android.telephony.NetworkRegistrationInfo.ServiceType}
     */
    @NonNull private final ConcurrentHashMap<Integer, List<Integer>>
            mSatModeCapabilitiesForCarrierRoaming = new ConcurrentHashMap<>();

    @NonNull private final List<String> mSatellitePlmnListFromOverlayConfig;
    @NonNull private final CarrierConfigManager mCarrierConfigManager;
    @NonNull private DisplayManager mDisplayManager;
    @NonNull private final CarrierConfigManager.CarrierConfigChangeListener
            mCarrierConfigChangeListener;
    @NonNull private final ConfigProviderAdaptor.Callback mConfigDataUpdatedCallback;
    @NonNull
    private final SubscriptionManager.OnSubscriptionsChangedListener mSubscriptionsChangedListener;

    /** Key: Subscription ID, value: set of restriction reasons for satellite communication.*/
    @NonNull private final ConcurrentHashMap<Integer, Set<Integer>>
            mSatelliteAttachRestrictionForCarrierArray = new ConcurrentHashMap<>();
    /** Key: Subscription ID, value: the actual satellite enabled state in the modem -
     * {@code true} for enabled and {@code false} for disabled. */
    @NonNull private final ConcurrentHashMap<Integer, Boolean>
            mIsSatelliteAttachEnabledForCarrierArrayPerSub = new ConcurrentHashMap<>();
    /** Key: subId, value: (key: Regional satellite config Id string, value: Integer
     * arrays of earfcns in the corresponding regions.)
     */
    @NonNull private final ConcurrentHashMap<Integer, Map<String, Set<Integer>>>
            mRegionalSatelliteEarfcns = new ConcurrentHashMap<>();
    // key : subscriberId, value : provisioned or not.
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected ConcurrentHashMap<String, Boolean> mProvisionedSubscriberId =
            new ConcurrentHashMap<>();
    // key : subscriberId, value : subId
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected ConcurrentHashMap<String, Integer> mSubscriberIdPerSub = new ConcurrentHashMap<>();

    @NonNull private final FeatureFlags mFeatureFlags;
    /** Key: Subscription ID; Value: Last satellite connected time */
    @NonNull private final ConcurrentHashMap<Integer, Long> mLastSatelliteDisconnectedTimesMillis =
            new ConcurrentHashMap<>();
    /**
     * Key: Subscription ID; Value: {@code true} if satellite was just connected,
     * {@code false} otherwise.
     */
    @NonNull private final ConcurrentHashMap<Integer, Boolean>
            mWasSatelliteConnectedViaCarrier = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<Integer, Boolean> mLastNotifiedNtnMode =
            new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<Integer, Boolean> mInitialized =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, NtnSignalStrength>
            mLastNotifiedCarrierRoamingNtnSignalStrength = new ConcurrentHashMap<>();
    @NonNull private SharedPreferences mSharedPreferences = null;

    @Nullable private PersistentLogger mPersistentLogger = null;

    /**
     * SatellitePerPlmnConfiguration holds the state of the satellite configurations for a specific
     * PLMN.
     */
    public static class SatellitePerPlmnConfiguration {
        // The PLMN of the satellite configurations.
        public String plmn = "";

        // The handover type for emergency calls on this PLMN.
        public int handoverType =
            SatelliteManager.EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_UNKNOWN;

        // The connect type for this PLMN.
        public int connectType = CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_UNKNOWN;

        // The supported satellite technology list for this PLMN
        public List<Integer> supportedSatelliteTechs = new ArrayList<>();

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof SatellitePerPlmnConfiguration other)) {
                return false;
            }
            return plmn.equals(other.plmn)
                    && handoverType == other.handoverType
                    && connectType == other.connectType
                    && supportedSatelliteTechs.equals(other.supportedSatelliteTechs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(plmn, handoverType, connectType, supportedSatelliteTechs);
        }

        @Override
        public String toString() {
            return "SatellitePerPlmnConfiguration: {"
                    + "plmn='"
                    + plmn
                    + '\''
                    + ", handoverType='"
                    + handoverType
                    + '\''
                    + ", connectType='"
                    + connectType
                    + '\''
                    + ", supportedSatelliteTechs='"
                    + supportedSatelliteTechs
                    + '\''
                    + '}';
        }
    }

    // Key: Subscription ID, Value: SatellitePerPlmnConfiguration of the current or last connected
    // PLMN.
    // This cache holds the configurations of the current satellite context.
    // It is updated on service state changes.
    @NonNull
    private final ConcurrentHashMap<Integer, SatellitePerPlmnConfiguration>
            mCurrentSatellitePerPlmnConfigurations = new ConcurrentHashMap<>();

    // Key: Subscription ID,
    // Value: Map of <Key: Plmn, Value: List of supported satellite technologies per PLMN> of
    // all active subscriptions. It is updated on carrier config changes.
    @NonNull
    private final ConcurrentHashMap<Integer, Map<String, List<Integer>>>
            mSatelliteTechPerPlmnForActiveSubId = new ConcurrentHashMap<>();

    /**
     * Key : Subscription ID, Value: {@code true} if the EntitlementStatus is enabled,
     * {@code false} otherwise.
     */
    private ConcurrentHashMap<Integer, Boolean> mSatelliteEntitlementStatusPerCarrier =
            new ConcurrentHashMap<>();
    /** Key Subscription ID, value : PLMN allowed list from entitlement. */
    private ConcurrentHashMap<Integer, List<String>> mEntitlementPlmnListPerCarrier =
            new ConcurrentHashMap<>();
    /** Key Subscription ID, value : PLMN barred list from entitlement. */
    private ConcurrentHashMap<Integer, List<String>> mEntitlementBarredPlmnListPerCarrier =
            new ConcurrentHashMap<>();
    /**
     * Key : Subscription ID, Value : If there is an entitlementPlmnList, use it. Otherwise, use the
     * carrierPlmnList. */
    private final ConcurrentHashMap<Integer, List<String>> mMergedPlmnListPerCarrier =
            new ConcurrentHashMap<>();
    /** Key Subscription ID, value : map to plmn info with related data plan. */
    ConcurrentHashMap<Integer, Map<String, Integer>> mEntitlementDataPlanMapPerCarrier =
            new ConcurrentHashMap<>();
    /** Key Subscription ID, value : map to plmn info with related service type. */
    ConcurrentHashMap<Integer, Map<String, List<Integer>>> mEntitlementServiceTypeMapPerCarrier =
            new ConcurrentHashMap<>();
    /** Key Subscription ID, value : map to plmn info with related service policy for data service */
    ConcurrentHashMap<Integer, Map<String, Integer>> mEntitlementDataServicePolicyMapPerCarrier =
            new ConcurrentHashMap<>();
    /** Key Subscription ID, value : map to plmn info with related service policy for voice service */
    ConcurrentHashMap<Integer, Map<String, Integer>> mEntitlementVoiceServicePolicyMapPerCarrier =
            new ConcurrentHashMap<>();
    /** Key : Subscription ID, value : satellite eligibility source. */
    ConcurrentHashMap<Integer, Integer> mSatelliteEligibilitySource = new ConcurrentHashMap<>();
    private static final int DEFAULT_SATELLITE_EMERGENCY_MODE_DURATION_SECONDS = 300;
    private AlertDialog mNetworkSelectionModeAutoDialog = null;

    /** Key used to read/write satellite system notification done in shared preferences. */
    private static final String SATELLITE_SYSTEM_NOTIFICATION_DONE_KEY =
            "satellite_system_notification_done_key";
    private static final String SATELLITE_SYSTEM_NOTIFICATION_TIME =
            "satellite_system_notification_time";
    // The notification tag used when showing a notification. The combination of notification tag
    // and notification id should be unique within the phone app.
    private static final String NOTIFICATION_TAG = "SatelliteController";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL = "satelliteChannel";
    private static final String NOTIFICATION_CHANNEL_ID = "satellite";

    private final RegistrantList mSatelliteConfigUpdateChangedRegistrants = new RegistrantList();
    private final RegistrantList mSatelliteSubIdChangedRegistrants = new RegistrantList();
    private final BTWifiNFCStateReceiver mBTWifiNFCSateReceiver;
    private final UwbAdapterStateCallback mUwbAdapterStateCallback;
    @Nullable private List<Integer> mCtsSatelliteAccessAllowedSubIds = null;

    // Variable for backup and restore device's screen rotation settings.
    private String mDeviceRotationLockToBackupAndRestore = null;
    // This is used for testing only. Context#getSystemService is a final API and cannot be
    // mocked. Using this to inject a mock SubscriptionManager to work around this limitation.
    private SubscriptionManager mInjectSubscriptionManager = null;
    private String mConfigSatelliteGatewayServicePackage = "";
    private String mConfigSatelliteCarrierRoamingEsosProvisionedClass = "";
    private static final String OPEN_MESSAGE_BUTTON = "open_message_button";
    private static final String HOW_IT_WORKS_BUTTON = "how_it_works_button";
    private static final String ACTION_NOTIFICATION_CLICK = "action_notification_click";
    private static final String ACTION_NOTIFICATION_DISMISS = "action_notification_dismiss";
    private static final String ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL =
            "android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL";
    private String mSatelliteGatewayServicePackageName = "";
    private String mOverriddenSatelliteGatewayServicePackageName = "";

    // Data Plan types at entitlement for the plmn allowed
    public static final int SATELLITE_DATA_PLAN_METERED = 0;
    public static final int SATELLITE_DATA_PLAN_UNMETERED = 1;
    @IntDef(prefix = {"SATELLITE_DATA_PLAN_"}, value = {
            SATELLITE_DATA_PLAN_METERED,
            SATELLITE_DATA_PLAN_UNMETERED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteDataPlan {}

    private BroadcastReceiver
            mDefaultSmsSubscriptionChangedBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction().equals(
                            SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED)) {
                        plogd("Default SMS subscription changed");
                        sendRequestAsync(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION, null, null);
                    }
                }
            };

    private BroadcastReceiver mPackageStateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String defaultSmsPackageName = Telephony.Sms.getDefaultSmsPackage(mContext);
            mSatelliteGatewayServicePackageName = getConfigSatelliteGatewayServicePackage();
            final String action = intent.getAction();
            boolean shouldReevaluate = false;
            if (ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL.equals(action)) {
                // This is a general signal that the default SMS app has changed.
                plogd("Default SMS package changed internally, re-evaluating services.");
                shouldReevaluate = true;
            } else {
                String schemeSpecificPart = intent.getData().getSchemeSpecificPart();
                plogd("packageStateChanged: " + intent.getData().toString()
                        + " defaultSmsPackageName: " + defaultSmsPackageName
                        + " satelliteGatewayServicePackageName: "
                        + mSatelliteGatewayServicePackageName);

                if (schemeSpecificPart.equals(mSatelliteGatewayServicePackageName)
                        || schemeSpecificPart.equals(defaultSmsPackageName)) {
                    plogd("Changed SMS or SatelliteGateway package");
                    shouldReevaluate = true;
                }
            }
            if (shouldReevaluate) {
                if (!hasMessages(EVENT_PACKAGE_CHANGED)) {
                    sendMessageDelayed(obtainMessage(EVENT_PACKAGE_CHANGED),
                            DEBOUNCE_DELAY_MILLIS);
                }
            }
        }
    };

    private void handlePackageChangeEvent() {
        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds != null) {
            for (int activeSubId : activeSubIds) {
                plogd("mPackageStateChangedReceiver: activeSubId= " + activeSubId);
                handleCarrierRoamingNtnAvailableServicesChanged(activeSubId);
            }
        } else {
            ploge("mPackageStateChangedReceiver: activeSubIds is null");
        }
    }

    protected BroadcastReceiver mLocationServiceStateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check whether user has turned on/off location manager from settings menu
            if (intent.getAction().equals(LocationManager.MODE_CHANGED_ACTION)) {
                plogd("mLocationServiceStateChangedReceiver");
                sendRequestAsync(CMD_LOCATION_SERVICE_STATE_CHANGED, null, null);
            }
        }
    };

    // List of device states returned from DeviceStateManager to determine if running on a foldable
    // device.
    private List<DeviceState> mDeviceStates = new ArrayList();

    @Nullable
    private AlarmManager mAlarmManager;
    @NonNull
    private final AlarmManager.OnAlarmListener mRegularMetricReportAlarmListener =
            () -> {
                plogd("onAlarm: regular metric report hysteresis timer expired");
                Message msg = SatelliteController.getInstance().obtainMessage(
                        EVENT_WAIT_FOR_REGULAR_METRICS_REPORT_HYSTERESIS_TIMED_OUT, null);
                msg.sendToTarget();
            };

    public static final int RESULT_RECEIVER_COUNT_ANOMALY_THRESHOLD = 500;

    // Satellite anomaly uuid -- ResultReceiver count threshold exceeded
    private final UUID mAnomalyUnexpectedResultReceiverCountUUID =
            UUID.fromString("e268f22d-9bba-4d27-b76a-1c7f5b42e241");

    private UUID generateAnomalyUnexpectedResultReceiverCountUUID(int error, int errorCode) {
        long lerror = error;
        long lerrorCode = errorCode;
        return new UUID(mAnomalyUnexpectedResultReceiverCountUUID.getMostSignificantBits(),
                mAnomalyUnexpectedResultReceiverCountUUID.getLeastSignificantBits()
                        + ((lerrorCode << 32) + lerror));
    }

    /**
     * Increments the ResultReceiver count and logs the caller information.
     * If the count exceeds the threshold, it reports an anomaly via AnomalyReporter.
     *
     * @param caller The caller information that created the ResultReceiver
     *               (e.g., class name and method name)
     */
    public void incrementResultReceiverCount(String caller) {
        int resultReceiverTotalCount = mResultReceiverTotalCount.incrementAndGet();
        logd("[incrementResultReceiverCount] : " + caller
                + " | ResultReceiver total count= " + resultReceiverTotalCount);
        mResultReceiverCountPerMethodMap.compute(caller,
                (k, v) -> v == null ? 1 : v + 1);

        if (resultReceiverTotalCount > RESULT_RECEIVER_COUNT_ANOMALY_THRESHOLD) {
            loge("[mResultReceiverTotalCount] is exceeds limits : " + resultReceiverTotalCount);
            loge("[incrementResultReceiverCount] mResultReceiverCountPerMethodMap is "
                    + mResultReceiverCountPerMethodMap);
            AnomalyReporter.reportAnomaly(
                    generateAnomalyUnexpectedResultReceiverCountUUID(0, 0),
                    "Satellite ResultReceiver total count= "
                            + resultReceiverTotalCount + " exceeds limit.");
        }
    }

    /**
     * Decrements the ResultReceiver count and logs the caller information.
     * Prevents the count from going below zero.
     *
     * @param caller The caller information that released the ResultReceiver
     *               (e.g., class name and method name)
     */
    public void decrementResultReceiverCount(String caller) {
        if (mResultReceiverTotalCount.get() > 0) {
            mResultReceiverTotalCount.decrementAndGet();
        }
        logd("[decrementResultReceiverCount] : " + caller
                + " | ResultReceiver total count=" + mResultReceiverTotalCount.get());
        mResultReceiverCountPerMethodMap.computeIfPresent(caller,
                (k, v) -> v > 0 ? v - 1 : v);
    }

    /**
     * @return The singleton instance of SatelliteController.
     */
    public static SatelliteController getInstance() {
        if (sInstance == null) {
            loge("SatelliteController was not yet initialized.");
        }
        return sInstance;
    }

    /**
     * Create the SatelliteController singleton instance.
     * @param context The Context to use to create the SatelliteController.
     * @param featureFlags The feature flag.
     */
    public static void make(@NonNull Context context, @NonNull FeatureFlags featureFlags) {
        if (sInstance == null) {
            sInstance = new SatelliteController(
                    context, WorkerThread.get().getLooper(), featureFlags);
        }
    }

    /**
     * Create a SatelliteController to act as a backend service of
     * {@link android.telephony.satellite.SatelliteManager}
     *
     * @param context The Context for the SatelliteController.
     * @param looper The looper for the handler. It does not run on main thread.
     * @param featureFlags The feature flag.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public SatelliteController(
            @NonNull Context context, @NonNull Looper looper, @NonNull FeatureFlags featureFlags) {
        super(looper);

        mPersistentLogger = SatelliteServiceUtils.getPersistentLogger(context);
        mContext = context;
        mFeatureFlags = featureFlags;
        Phone phone = SatelliteServiceUtils.getPhone();
        setSatellitePhone(phone);
        mCi = phone.mCi;
        mDSM = phone.getDeviceStateMonitor();
        // Create the SatelliteModemInterface singleton, which is used to manage connections
        // to the satellite service and HAL interface.
        mSatelliteModemInterface = SatelliteModemInterface.make(
                mContext, this, mFeatureFlags);
        mCountryDetector = TelephonyCountryDetector.getInstance(context, mFeatureFlags);
        mCountryDetector.registerForWifiConnectivityStateChanged(this,
                EVENT_WIFI_CONNECTIVITY_STATE_CHANGED, null);
        mTelecomManager = mContext.getSystemService(TelecomManager.class);

        // Create the PointingUIController singleton,
        // which is used to manage interactions with PointingUI app.
        mPointingAppController = PointingAppController.make(mContext, mFeatureFlags);

        // Create the SatelliteControllerMetrics to report controller metrics
        // should be called before making DatagramController
        mControllerMetricsStats = ControllerMetricsStats.make(mContext);
        mProvisionMetricsStats = ProvisionMetricsStats.getOrCreateInstance();
        mSessionMetricsStats = SessionMetricsStats.getInstance();
        mCarrierRoamingSatelliteControllerStats =
                CarrierRoamingSatelliteControllerStats.getOrCreateInstance();
        mSubscriptionManagerService = SubscriptionManagerService.getInstance();

        // Create the DatagramController singleton,
        // which is used to send and receive satellite datagrams.
        mDatagramController = DatagramController.make(
                mContext, looper, mFeatureFlags, mPointingAppController);

        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);
        mIsRadioOn.set(phone.isRadioOn());

        registerForPendingDatagramCount();
        registerForSatelliteModemStateChanged();
        registerForServiceStateChanged();
        registerForSignalStrengthChanged();
        registerForSatelliteCommunicationAccessStateChanged();
        mContentResolver = mContext.getContentResolver();
        mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);

        mBTWifiNFCSateReceiver = new BTWifiNFCStateReceiver();
        mUwbAdapterStateCallback = new UwbAdapterStateCallback();
        initializeSatelliteModeRadios();

        ContentObserver satelliteModeRadiosContentObserver = new ContentObserver(this) {
            @Override
            public void onChange(boolean selfChange) {
                initializeSatelliteModeRadios();
            }
        };
        if (mContentResolver != null) {
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.SATELLITE_MODE_RADIOS),
                    false, satelliteModeRadiosContentObserver);
        }

        mSatellitePlmnListFromOverlayConfig = readSatellitePlmnsFromOverlayConfig();

        registerApplicationStateChanged();
        registerLocationServiceStateChanged();
        updateSupportedSatelliteServicesForActiveSubscriptions();
        mCarrierConfigChangeListener = (slotIndex, subId, carrierId, specificCarrierId) -> {
            if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = slotIndex;
                args.arg2 = subId;
                args.arg3 = carrierId;
                args.arg4 = specificCarrierId;
                sendMessage(obtainMessage(EVENT_CARRIER_CONFIG_CHANGED, args));
                return;
            }

            handleCarrierConfigChanged(slotIndex, subId, carrierId, specificCarrierId);
        };

        if (mCarrierConfigManager != null) {
            mCarrierConfigManager.registerCarrierConfigChangeListener(
                    new HandlerExecutor(new Handler(looper)), mCarrierConfigChangeListener);
        }

        mConfigDataUpdatedCallback = new ConfigProviderAdaptor.Callback() {
            @Override
            public void onChanged(@Nullable ConfigParser config) {
                SatelliteControllerHandlerRequest request =
                        new SatelliteControllerHandlerRequest(true,
                                SatelliteServiceUtils.getPhone());
                sendRequestAsync(EVENT_SATELLITE_CONFIG_DATA_UPDATED, request, null);
            }
        };
        TelephonyConfigUpdateInstallReceiver.getInstance()
                .registerCallback(Executors.newSingleThreadExecutor(), mConfigDataUpdatedCallback);

        mDSM.registerForSignalStrengthReportDecision(this, CMD_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING,
                null);
        if (mFeatureFlags.satelliteMetricsEnhancement()) {
            mDSM.registerForScreenStateChanged(this, EVENT_SCREEN_STATE_CHANGED, null);
        }

        loadSatelliteSharedPreferences();
        if (mSharedPreferences != null) {
            try {
                setNtnSmsSupportedByMessagesAppCache(mSharedPreferences.getBoolean(
                        NTN_SMS_SUPPORTED_BY_MESSAGES_APP_KEY, false));
                setCarrierRoamingNtnAllSatellitePlmnSet(mSharedPreferences.getStringSet(
                        CARRIER_ROAMING_NTN_ALL_SATELLITE_PLMN_SET_KEY, new HashSet<>()));
            } catch (Exception ex) {
                plogd("SatelliteController constructor: "
                        + "cannot get default shared preferences. e" + ex);
            }
        }

        mWaitTimeForSatelliteEnablingResponse.set(
                getWaitForSatelliteEnablingResponseTimeoutMillis());
        mDemoPointingAlignedDurationMillis.set(getDemoPointingAlignedDurationMillisFromResources());
        mDemoPointingNotAlignedDurationMillis.set(
                getDemoPointingNotAlignedDurationMillisFromResources());
        mSatelliteEmergencyModeDurationMillis.set(
                getSatelliteEmergencyModeDurationFromOverlayConfig(context));
        mEvaluateEsosProfilesPrioritizationDurationMillis.set(
                getEvaluateEsosProfilesPrioritizationDurationMillis());
        sendMessageDelayed(obtainMessage(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION),
                mEvaluateEsosProfilesPrioritizationDurationMillis.get());

        SubscriptionManager subscriptionManager = mContext.getSystemService(
                SubscriptionManager.class);
        mSubscriptionsChangedListener = new SatelliteSubscriptionsChangedListener();
        if (subscriptionManager != null) {
            subscriptionManager.addOnSubscriptionsChangedListener(
                    new HandlerExecutor(new Handler(looper)), mSubscriptionsChangedListener);
        }
        registerDefaultSmsSubscriptionChangedBroadcastReceiver();
        updateSatelliteProvisionedStatePerSubscriberId();
        if (android.hardware.devicestate.feature.flags.Flags.deviceStatePropertyMigration()) {
            mDeviceStates = getSupportedDeviceStates();
        }

        mSatelliteOptimizedApplicationsTracker = new SatelliteOptimizedApplicationsTracker(
                getLooper(), mContext
        );

        for (Phone phoneToSendRequest : PhoneFactory.getPhones()) {
            sendRequestAsync(CMD_GET_SATELLITE_ENABLED_FOR_CARRIER, null, phoneToSendRequest);
        }

        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        scheduleRegularMetricReportTimer();
        logd("Satellite Tracker is created");
    }

    class SatelliteSubscriptionsChangedListener
            extends SubscriptionManager.OnSubscriptionsChangedListener {

        /**
         * Callback invoked when there is any change to any SubscriptionInfo.
         */
        @Override
        public void onSubscriptionsChanged() {
            handleSubscriptionsChanged();
        }
    }

    /**
     * Register a callback to get a updated satellite config data.
     * @param h Handler to notify
     * @param what msg.what when the message is delivered
     * @param obj AsyncResult.userObj when the message is delivered
     */
    public void registerForConfigUpdateChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSatelliteConfigUpdateChangedRegistrants.add(r);
    }

    /**
     * Unregister a callback to get a updated satellite config data.
     * @param h Handler to notify
     */
    public void unregisterForConfigUpdateChanged(Handler h) {
        mSatelliteConfigUpdateChangedRegistrants.remove(h);
    }

    /**
     * Register a callback to change the satelliteSubId.
     * @param h Handler to notify
     * @param what msg.what when the message is delivered
     * @param obj AsyncResult.userObj when the message is delivered
     */
    public void registerForSatelliteSubIdChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSatelliteSubIdChangedRegistrants.add(r);
    }

    /**
     * Unregister a callback to get a updated satellite config data.
     * @param h Handler to notify
     */
    public void unregisterForSatelliteSubIdChanged(Handler h) {
        mSatelliteSubIdChangedRegistrants.remove(h);
    }
    /**
     * Get satelliteConfig from SatelliteConfigParser
     */
    @Nullable
    public SatelliteConfig getSatelliteConfig() {
        SatelliteConfigParser satelliteConfigParser = getSatelliteConfigParser();
        if (satelliteConfigParser == null) {
            Log.d(TAG, "satelliteConfigParser is not ready");
            return null;
        }
        SatelliteConfig satelliteConfig = satelliteConfigParser.getConfig();
        logd("getSatelliteConfig: satelliteConfig: " + satelliteConfig);
        return satelliteConfig;
    }

    /**
     * This API should be used by only CTS/unit tests to reset the telephony configs set through
     * config updater
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void cleanUpTelephonyConfigs() {
        TelephonyConfigUpdateInstallReceiver.getInstance().cleanUpTelephonyConfigs();
    }

    /**
     * Get SatelliteConfigParser from TelephonyConfigUpdateInstallReceiver
     */
    @Nullable
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public SatelliteConfigParser getSatelliteConfigParser() {
        return (SatelliteConfigParser) TelephonyConfigUpdateInstallReceiver
                .getInstance().getConfigParser(DOMAIN_SATELLITE);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void initializeSatelliteModeRadios() {
        if (mContentResolver != null) {
            IntentFilter radioStateIntentFilter = new IntentFilter();

            // Initialize radio states to default value
            mDisableBTOnSatelliteEnabled.set(false);
            mDisableNFCOnSatelliteEnabled.set(false);
            mDisableWifiOnSatelliteEnabled.set(false);
            mDisableUWBOnSatelliteEnabled.set(false);

            mBTStateEnabled.set(false);
            mNfcStateEnabled.set(false);
            mWifiStateEnabled.set(false);
            mUwbStateEnabled.set(false);

            // Read satellite mode radios from settings
            String satelliteModeRadios = Settings.Global.getString(mContentResolver,
                    Settings.Global.SATELLITE_MODE_RADIOS);
            if (satelliteModeRadios == null) {
                ploge("initializeSatelliteModeRadios: satelliteModeRadios is null");
                return;
            }
            plogd("Radios To be checked when satellite is on: " + satelliteModeRadios);

            if (satelliteModeRadios.contains(Settings.Global.RADIO_BLUETOOTH)) {
                BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter != null) {
                    mDisableBTOnSatelliteEnabled.set(true);
                    mBTStateEnabled.set(bluetoothAdapter.isEnabled());
                    radioStateIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
                }
            }

            if (satelliteModeRadios.contains(Settings.Global.RADIO_NFC)) {
                Context applicationContext = mContext.getApplicationContext();
                NfcAdapter nfcAdapter = null;
                if (applicationContext != null) {
                    nfcAdapter = NfcAdapter.getDefaultAdapter(mContext.getApplicationContext());
                }
                if (nfcAdapter != null) {
                    mDisableNFCOnSatelliteEnabled.set(true);
                    mNfcStateEnabled.set(nfcAdapter.isEnabled());
                    radioStateIntentFilter.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
                }
            }

            if (satelliteModeRadios.contains(Settings.Global.RADIO_WIFI)) {
                WifiManager wifiManager = mContext.getSystemService(WifiManager.class);
                if (wifiManager != null) {
                    mDisableWifiOnSatelliteEnabled.set(true);
                    mWifiStateEnabled.set(wifiManager.isWifiEnabled());
                    radioStateIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
                }
            }

            try {
                // Unregister receiver before registering it.
                mContext.unregisterReceiver(mBTWifiNFCSateReceiver);
            } catch (IllegalArgumentException e) {
                plogd("initializeSatelliteModeRadios: unregisterReceiver, e=" + e);
            }
            mContext.registerReceiver(mBTWifiNFCSateReceiver, radioStateIntentFilter);

            if (satelliteModeRadios.contains(Settings.Global.RADIO_UWB)) {
                UwbManager uwbManager = mContext.getSystemService(UwbManager.class);
                if (uwbManager != null) {
                    mDisableUWBOnSatelliteEnabled.set(true);
                    mUwbStateEnabled.set(uwbManager.isUwbEnabled());
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        // Unregister callback before registering it.
                        uwbManager.unregisterAdapterStateCallback(mUwbAdapterStateCallback);
                        uwbManager.registerAdapterStateCallback(mContext.getMainExecutor(),
                                mUwbAdapterStateCallback);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }

            plogd("mDisableBTOnSatelliteEnabled: " + mDisableBTOnSatelliteEnabled.get()
                    + " mDisableNFCOnSatelliteEnabled: " + mDisableNFCOnSatelliteEnabled.get()
                    + " mDisableWifiOnSatelliteEnabled: " + mDisableWifiOnSatelliteEnabled.get()
                    + " mDisableUWBOnSatelliteEnabled: " + mDisableUWBOnSatelliteEnabled.get());

            plogd("mBTStateEnabled: " + mBTStateEnabled.get()
                    + " mNfcStateEnabled: " + mNfcStateEnabled.get()
                    + " mWifiStateEnabled: " + mWifiStateEnabled.get()
                    + " mUwbStateEnabled: " + mUwbStateEnabled.get());
        }
    }

    protected class UwbAdapterStateCallback implements UwbManager.AdapterStateCallback {

        public String toString(int state) {
            switch (state) {
                case UwbManager.AdapterStateCallback.STATE_DISABLED:
                    return "Disabled";

                case UwbManager.AdapterStateCallback.STATE_ENABLED_INACTIVE:
                    return "Inactive";

                case UwbManager.AdapterStateCallback.STATE_ENABLED_ACTIVE:
                    return "Active";

                default:
                    return "";
            }
        }

        @Override
        public void onStateChanged(int state, int reason) {
            plogd("UwbAdapterStateCallback#onStateChanged() called, state = " + toString(state));
            plogd("Adapter state changed reason " + String.valueOf(reason));
            if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
                sendMessage(obtainMessage(EVENT_UWB_STATE_CHANGED, state));
                return;
            }

            handleEventUwbStateChanged(state);
        }
    }

    private void handleEventUwbStateChanged(int state) {
        if (state == UwbManager.AdapterStateCallback.STATE_DISABLED) {
            setUwbEnabledState(false);
            evaluateToSendSatelliteEnabledSuccess();
        } else {
            setUwbEnabledState(true);
        }
        plogd("mUwbStateEnabled: " + getUwbEnabledState());
    }

    protected class BTWifiNFCStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
                sendMessage(obtainMessage(EVENT_BT_WIFI_NFC_STATE_CHANGED, intent));
                return;
            }

            handleEventBtWifiNfcStateChanged(intent);
        }
    }

    private void handleEventBtWifiNfcStateChanged(@NonNull Intent intent) {
        final String action = intent.getAction();
        if (action == null) {
            plogd("BTWifiNFCStateReceiver NULL action for intent " + intent);
            return;
        }

        plogd("handleEventBtWifiNfcStateChanged: action=" + action);

        switch (action) {
            case BluetoothAdapter.ACTION_STATE_CHANGED:
                int btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                boolean currentBTStateEnabled = getBTEnabledState();
                if (btState == BluetoothAdapter.STATE_OFF) {
                    setBTEnabledState(false);
                    evaluateToSendSatelliteEnabledSuccess();
                } else if (btState == BluetoothAdapter.STATE_ON) {
                    setBTEnabledState(true);
                }

                if (currentBTStateEnabled != getBTEnabledState()) {
                    plogd("mBTStateEnabled=" + getBTEnabledState());
                }
                break;

            case NfcAdapter.ACTION_ADAPTER_STATE_CHANGED:
                int nfcState = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, -1);
                boolean currentNfcStateEnabled = getNfcEnabledState();
                if (nfcState == NfcAdapter.STATE_ON) {
                    setNfcEnabledState(true);
                } else if (nfcState == NfcAdapter.STATE_OFF) {
                    setNfcEnabledState(false);
                    evaluateToSendSatelliteEnabledSuccess();
                }

                if (currentNfcStateEnabled != getNfcEnabledState()) {
                    plogd("mNfcStateEnabled=" + getNfcEnabledState());
                }
                break;

            case WifiManager.WIFI_STATE_CHANGED_ACTION:
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                boolean currentWifiStateEnabled = getWifiEnabledState();
                if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                    setWifiEnabledState(true);
                } else if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                    setWifiEnabledState(false);
                    evaluateToSendSatelliteEnabledSuccess();
                }

                if (currentWifiStateEnabled != getWifiEnabledState()) {
                    plogd("mWifiStateEnabled=" + getWifiEnabledState());
                }
                break;
            default:
                break;
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public static final class SatelliteControllerHandlerRequest {
        /** The argument to use for the request */
        public @NonNull Object argument;
        /** The caller needs to specify the phone to be used for the request */
        public @NonNull Phone phone;
        /** The result of the request that is run on the main thread */
        public @Nullable Object result;

        public SatelliteControllerHandlerRequest(Object argument, Phone phone) {
            this.argument = argument;
            this.phone = phone;
        }
    }

    private static final class RequestSatelliteEnabledArgument {
        public boolean enableSatellite;
        public boolean enableDemoMode;
        public boolean isEmergency;
        @NonNull public Consumer<Integer> callback;
        public long requestId;

        RequestSatelliteEnabledArgument(boolean enableSatellite, boolean enableDemoMode,
                boolean isEmergency, Consumer<Integer> callback) {
            this.enableSatellite = enableSatellite;
            this.enableDemoMode = enableDemoMode;
            this.isEmergency = isEmergency;
            this.callback = callback;
            this.requestId = sNextSatelliteEnableRequestId.getAndUpdate(
                    n -> ((n + 1) % Long.MAX_VALUE));
        }
    }

    private static final class RequestHandleSatelliteAttachRestrictionForCarrierArgument {
        public int subId;
        @SatelliteManager.SatelliteCommunicationRestrictionReason
        public int reason;
        @NonNull public Consumer<Integer> callback;

        RequestHandleSatelliteAttachRestrictionForCarrierArgument(int subId,
                @SatelliteManager.SatelliteCommunicationRestrictionReason int reason,
                Consumer<Integer> callback) {
            this.subId = subId;
            this.reason = reason;
            this.callback = callback;
        }
    }

    private static final class ProvisionSatelliteServiceArgument {
        @NonNull public String token;
        @NonNull public byte[] provisionData;
        @NonNull public Consumer<Integer> callback;
        public int subId;

        ProvisionSatelliteServiceArgument(String token, byte[] provisionData,
                Consumer<Integer> callback, int subId) {
            this.token = token;
            this.provisionData = provisionData;
            this.callback = callback;
            this.subId = subId;
        }
    }

    private static final class UpdateSystemSelectionChannelsArgument {
        public @NonNull List<SystemSelectionSpecifier> systemSelectionSpecifiers;
        public @NonNull ResultReceiver result;
        public long requestId;

        UpdateSystemSelectionChannelsArgument(
                @NonNull List<SystemSelectionSpecifier> systemSelectionSpecifiers,
                @NonNull ResultReceiver result) {
            this.systemSelectionSpecifiers = systemSelectionSpecifiers;
            this.result = result;
            this.requestId = sNextSystemSelectionChannelsUpdateRequestId.getAndUpdate(
                    n -> ((n + 1) % Long.MAX_VALUE));
        }
    }

    /**
     * Arguments to send to SatelliteTransmissionUpdate registrants
     */
    public static final class SatelliteTransmissionUpdateArgument {
        @NonNull public Consumer<Integer> errorCallback;
        @NonNull public ISatelliteTransmissionUpdateCallback callback;
        public int subId;

        SatelliteTransmissionUpdateArgument(Consumer<Integer> errorCallback,
                ISatelliteTransmissionUpdateCallback callback, int subId) {
            this.errorCallback = errorCallback;
            this.callback = callback;
            this.subId = subId;
        }
    }

    @Override
    public void handleMessage(Message msg) {
        SatelliteControllerHandlerRequest request;
        Message onCompleted;
        AsyncResult ar;

        switch(msg.what) {
            case CMD_START_SATELLITE_TRANSMISSION_UPDATES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted =
                        obtainMessage(EVENT_START_SATELLITE_TRANSMISSION_UPDATES_DONE, request);
                mPointingAppController.startSatelliteTransmissionUpdates(onCompleted);
                break;
            }

            case EVENT_START_SATELLITE_TRANSMISSION_UPDATES_DONE: {
                handleStartSatelliteTransmissionUpdatesDone((AsyncResult) msg.obj);
                break;
            }

            case CMD_STOP_SATELLITE_TRANSMISSION_UPDATES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted =
                        obtainMessage(EVENT_STOP_SATELLITE_TRANSMISSION_UPDATES_DONE, request);
                mPointingAppController.stopSatelliteTransmissionUpdates(onCompleted);
                break;
            }

            case EVENT_STOP_SATELLITE_TRANSMISSION_UPDATES_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "stopSatelliteTransmissionUpdates");
                ((Consumer<Integer>) request.argument).accept(error);
                break;
            }

            case CMD_PROVISION_SATELLITE_SERVICE: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                ProvisionSatelliteServiceArgument argument =
                        (ProvisionSatelliteServiceArgument) request.argument;
                if (mSatelliteProvisionCallbacks.containsKey(argument.subId)) {
                    argument.callback.accept(
                            SatelliteManager.SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS);
                    notifyRequester(request);
                    break;
                }
                mSatelliteProvisionCallbacks.put(argument.subId, argument.callback);
                // Log the current time for provision triggered
                mProvisionMetricsStats.setProvisioningStartTime();
                Message provisionSatelliteServiceDoneEvent = this.obtainMessage(
                        EVENT_PROVISION_SATELLITE_SERVICE_DONE,
                        new AsyncResult(request, SATELLITE_RESULT_SUCCESS, null));
                provisionSatelliteServiceDoneEvent.sendToTarget();
                break;
            }

            case EVENT_PROVISION_SATELLITE_SERVICE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "provisionSatelliteService");
                handleEventProvisionSatelliteServiceDone(
                        (ProvisionSatelliteServiceArgument) request.argument, errorCode);
                notifyRequester(request);
                break;
            }

            case CMD_DEPROVISION_SATELLITE_SERVICE: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                ProvisionSatelliteServiceArgument argument =
                        (ProvisionSatelliteServiceArgument) request.argument;
                if (argument.callback != null) {
                    mProvisionMetricsStats.setProvisioningStartTime();
                }
                Message deprovisionSatelliteServiceDoneEvent = this.obtainMessage(
                        EVENT_DEPROVISION_SATELLITE_SERVICE_DONE,
                        new AsyncResult(request, SATELLITE_RESULT_SUCCESS, null));
                deprovisionSatelliteServiceDoneEvent.sendToTarget();
                break;
            }

            case EVENT_DEPROVISION_SATELLITE_SERVICE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "deprovisionSatelliteService");
                handleEventDeprovisionSatelliteServiceDone(
                        (ProvisionSatelliteServiceArgument) request.argument, errorCode);
                break;
            }

            case CMD_SET_SATELLITE_ENABLED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                handleSatelliteEnabled(request);
                break;
            }

            case EVENT_SET_SATELLITE_ENABLED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) request.argument;
                int error =  SatelliteServiceUtils.getSatelliteError(ar, "setSatelliteEnabled");
                plogd("EVENT_SET_SATELLITE_ENABLED_DONE = " + error);

                /*
                 * The timer to wait for EVENT_SET_SATELLITE_ENABLED_DONE might have expired and
                 * thus the request resources might have been cleaned up.
                 */
                if (!shouldProcessEventSetSatelliteEnabledDone(argument)) {
                    plogw("The request ID=" + argument.requestId + ", enableSatellite="
                            + argument.enableSatellite + " was already processed");
                    return;
                }
                if (shouldStopWaitForEnableResponseTimer(argument)) {
                    stopWaitForSatelliteEnablingResponseTimer(argument);
                } else {
                    plogd("Still waiting for the OFF state from modem");
                }

                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (argument.enableSatellite) {
                        mWaitingForRadioDisabled.set(true);
                        setDemoModeEnabled(argument.enableDemoMode);
                        // TODO (b/361139260): Start a timer to wait for other radios off
                        setSettingsKeyForSatelliteMode(SATELLITE_MODE_ENABLED_TRUE);
                        setSettingsKeyToAllowDeviceRotation(SATELLITE_MODE_ENABLED_TRUE);
                        evaluateToSendSatelliteEnabledSuccess();
                    } else {
                        // Unregister importance listener for PointingUI when satellite is disabled
                        if (mNeedsSatellitePointing.get()) {
                            mPointingAppController.removeListenerForPointingUI();
                        }

                        if (!isWaitingForSatelliteModemOff()) {
                            moveSatelliteToOffStateAndCleanUpResources(SATELLITE_RESULT_SUCCESS);
                        }

                        mWaitingForDisableSatelliteModemResponse.set(false);
                    }
                    // Request NTN signal strength report when satellite enabled or disabled done.
                    mLatestRequestedStateForNtnSignalStrengthReport.set(argument.enableSatellite);
                    updateNtnSignalStrengthReporting(argument.enableSatellite);
                } else {
                    if (argument.enableSatellite) {
                        /* Framework need to abort the enable attributes update request if any since
                         * modem failed to enable satellite.
                         */
                        abortSatelliteEnableAttributesUpdateRequest(
                                SATELLITE_RESULT_REQUEST_ABORTED);
                        resetSatelliteEnabledRequest();
                    } else {
                        resetSatelliteDisabledRequest();
                    }
                    notifyEnablementFailedToSatelliteSessionController(argument.enableSatellite);
                    // If Satellite enable/disable request returned Error, no need to wait for radio
                    argument.callback.accept(error);
                }
                if (argument.enableSatellite) {
                    mSessionMetricsStats.resetSessionStatsShadowCounters();
                    mSessionMetricsStats.setInitializationResult(error)
                            .setSatelliteTechnology(getSupportedNtnRadioTechnology())
                            .setInitializationProcessingTime(
                                    getElapsedRealtime() - mSessionProcessingTimeStamp.get())
                            .setIsDemoMode(mIsDemoModeEnabled.get())
                            .setCarrierId(getSatelliteCarrierId())
                            .setSupportedConnectionMode(getSupportedConnectTypeMetrics())
                            .setSessionConnectionMode(getSessionConnectTypeMetrics())
                            .setIsNtnOnlyCarrier(isNtnOnlyCarrier())
                            .setIsEmergency(argument.isEmergency);
                    mSessionProcessingTimeStamp.set(0);

                    if (error == SATELLITE_RESULT_SUCCESS) {
                        mControllerMetricsStats.onSatelliteEnabled();
                        mControllerMetricsStats.reportServiceEnablementSuccessCount(
                                argument.enableDemoMode);
                    } else {
                        mSessionMetricsStats.reportSessionMetrics();
                        mSessionStartTimeStamp.set(0);
                        mControllerMetricsStats.reportServiceEnablementFailCount(
                                argument.enableDemoMode);
                    }
                } else {
                    mSessionMetricsStats.setTerminationResult(error)
                            .setTerminationProcessingTime(getElapsedRealtime()
                                    - mSessionProcessingTimeStamp.get())
                            .setSessionDurationSec(calculateSessionDurationTimeSec())
                            .reportSessionMetrics();
                    mSessionStartTimeStamp.set(0);
                    mSessionProcessingTimeStamp.set(0);
                    mControllerMetricsStats.onSatelliteDisabled();
                    handlePersistentLoggingOnSessionEnd(mIsEmergency.get());
                    mWaitingForDisableSatelliteModemResponse.set(false);
                }
                break;
            }

            case EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT: {
                handleEventWaitForSatelliteEnablingResponseTimedOut(
                        (RequestSatelliteEnabledArgument) msg.obj);
                break;
            }

            case CMD_UPDATE_SATELLITE_ENABLE_ATTRIBUTES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) request.argument;

                if (getSatelliteEnabledRequest() != null) {
                    plogd("UpdateEnableAttributes: Satellite is being enabled. Need to "
                            + "wait until enable complete before updating attributes");
                    break;
                }
                if (isSatelliteBeingDisabled()) {
                    plogd("UpdateEnableAttributes: Satellite is being disabled. Aborting the "
                            + "enable attributes update request");
                    setSatelliteEnableAttributesUpdateRequest(null);
                    argument.callback.accept(SATELLITE_RESULT_REQUEST_ABORTED);
                    break;
                }
                onCompleted = obtainMessage(EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE, request);
                SatelliteModemEnableRequestAttributes enableRequestAttributes =
                    createModemEnableRequest(argument);
                if (enableRequestAttributes == null) {
                    plogw("UpdateEnableAttributes: enableRequestAttributes is null");
                    sendErrorAndReportSessionMetrics(
                        SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE,
                        argument.callback);
                    setSatelliteEnableAttributesUpdateRequest(null);
                    break;
                }
                mSatelliteModemInterface.requestSatelliteEnabled(
                        enableRequestAttributes, onCompleted);
                startWaitForUpdateSatelliteEnableAttributesResponseTimer(argument);
                break;
            }

            case EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) request.argument;
                int error =  SatelliteServiceUtils.getSatelliteError(
                        ar, "updateSatelliteEnableAttributes");
                plogd("EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE = " + error);

                /*
                 * The timer to wait for EVENT_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_DONE might have
                 * expired and thus the request resources might have been cleaned up.
                 */
                if (!shouldProcessEventUpdateSatelliteEnableAttributesDone(argument)) {
                    plogw("The update request ID=" + argument.requestId + " was already processed");
                    return;
                }
                stopWaitForUpdateSatelliteEnableAttributesResponseTimer(argument);

                if (error == SATELLITE_RESULT_SUCCESS) {
                    setDemoModeEnabled(argument.enableDemoMode);
                    setEmergencyMode(argument.isEmergency);
                }
                setSatelliteEnableAttributesUpdateRequest(null);
                argument.callback.accept(error);
                break;
            }

            case EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT: {
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) msg.obj;
                plogw("Timed out to wait for the response from the modem for the request to "
                        + "update satellite enable attributes, request ID = " + argument.requestId);
                setSatelliteEnableAttributesUpdateRequest(null);
                argument.callback.accept(SATELLITE_RESULT_MODEM_TIMEOUT);
                break;
            }

            case CMD_IS_SATELLITE_ENABLED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_IS_SATELLITE_ENABLED_DONE, request);
                mSatelliteModemInterface.requestIsSatelliteEnabled(onCompleted);
                break;
            }

            case EVENT_IS_SATELLITE_ENABLED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "isSatelliteEnabled");
                Bundle bundle = new Bundle();
                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (ar.result == null) {
                        ploge("isSatelliteEnabled: result is null");
                        error = SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
                    } else {
                        boolean enabled = ((int[]) ar.result)[0] == 1;
                        if (DBG) plogd("isSatelliteEnabled: " + enabled);
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_ENABLED, enabled);
                        updateSatelliteEnabledState(enabled, "EVENT_IS_SATELLITE_ENABLED_DONE");
                    }
                } else if (error == SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED) {
                    updateSatelliteSupportedState(false);
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                decrementResultReceiverCount("SC:requestIsSatelliteEnabled");
                break;
            }

            case CMD_IS_SATELLITE_SUPPORTED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_IS_SATELLITE_SUPPORTED_DONE, request);
                mSatelliteModemInterface.requestIsSatelliteSupported(onCompleted);
                break;
            }

            case EVENT_IS_SATELLITE_SUPPORTED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar, "isSatelliteSupported");
                Bundle bundle = new Bundle();
                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (ar.result == null) {
                        ploge("isSatelliteSupported: result is null");
                        error = SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
                    } else {
                        boolean supported = (boolean) ar.result;
                        plogd("isSatelliteSupported: " + supported);
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_SUPPORTED, supported);
                        updateSatelliteSupportedState(supported);
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case CMD_GET_SATELLITE_CAPABILITIES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_GET_SATELLITE_CAPABILITIES_DONE, request);
                mSatelliteModemInterface.requestSatelliteCapabilities(onCompleted);
                break;
            }

            case EVENT_GET_SATELLITE_CAPABILITIES_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "getSatelliteCapabilities");
                Bundle bundle = new Bundle();
                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (ar.result == null) {
                        ploge("getSatelliteCapabilities: result is null");
                        error = SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
                    } else {
                        SatelliteCapabilities capabilities = (SatelliteCapabilities) ar.result;
                        mNeedsSatellitePointing.set(capabilities.isPointingRequired());
                        setSatelliteCapabilities(capabilities);
                        overrideSatelliteCapabilitiesIfApplicable();
                        if (DBG) plogd("getSatelliteCapabilities: " + getSatelliteCapabilities());
                        bundle.putParcelable(SatelliteManager.KEY_SATELLITE_CAPABILITIES,
                                getSatelliteCapabilities());
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case CMD_GET_TIME_SATELLITE_NEXT_VISIBLE: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_GET_TIME_SATELLITE_NEXT_VISIBLE_DONE,
                        request);
                mSatelliteModemInterface.requestTimeForNextSatelliteVisibility(onCompleted);
                break;
            }

            case EVENT_GET_TIME_SATELLITE_NEXT_VISIBLE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error = SatelliteServiceUtils.getSatelliteError(ar,
                        "requestTimeForNextSatelliteVisibility");
                Bundle bundle = new Bundle();
                if (error == SATELLITE_RESULT_SUCCESS) {
                    if (ar.result == null) {
                        ploge("requestTimeForNextSatelliteVisibility: result is null");
                        error = SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
                    } else {
                        int nextVisibilityDuration = ((int[]) ar.result)[0];
                        if (DBG) {
                            plogd("requestTimeForNextSatelliteVisibility: "
                                    + nextVisibilityDuration);
                        }
                        bundle.putInt(SatelliteManager.KEY_SATELLITE_NEXT_VISIBILITY,
                                nextVisibilityDuration);
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                decrementResultReceiverCount("SC:requestTimeForNextSatelliteVisibility");
                break;
            }

            case EVENT_RADIO_STATE_CHANGED: {
                logd("EVENT_RADIO_STATE_CHANGED: radioState=" + mCi.getRadioState());
                if (mCi.getRadioState() == TelephonyManager.RADIO_POWER_ON) {
                    mIsRadioOn.set(true);
                } else if (mCi.getRadioState() == TelephonyManager.RADIO_POWER_OFF) {
                    resetCarrierRoamingSatelliteModeParams();
                    if (mRadioOffRequested.get()) {
                        logd("EVENT_RADIO_STATE_CHANGED: set mIsRadioOn to false");
                        stopWaitForCellularModemOffTimer();
                        mIsRadioOn.set(false);
                        mRadioOffRequested.set(false);
                    }
                }

                if (mCi.getRadioState() != TelephonyManager.RADIO_POWER_UNAVAILABLE) {
                    if (mSatelliteModemInterface.isSatelliteServiceConnected()) {
                        Boolean isSatelliteSupported = getIsSatelliteSupported();
                        if (isSatelliteSupported == null || !isSatelliteSupported) {
                            final String caller = "SC:CMD_IS_SATELLITE_SUPPORTED";
                            ResultReceiver receiver = new ResultReceiver(this) {
                                @Override
                                protected void onReceiveResult(
                                        int resultCode, Bundle resultData) {
                                    decrementResultReceiverCount(caller);
                                    plogd("onRadioStateChanged.requestIsSatelliteSupported: "
                                            + "resultCode=" + resultCode
                                            + ", resultData=" + resultData);
                                }
                            };
                            sendRequestAsync(CMD_IS_SATELLITE_SUPPORTED, receiver, null);
                            incrementResultReceiverCount(caller);
                        }
                    }
                }
                break;
            }

            case CMD_IS_SATELLITE_PROVISIONED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                Message isProvisionedDoneEvent = this.obtainMessage(
                        EVENT_IS_SATELLITE_PROVISIONED_DONE,
                        new AsyncResult(request, SATELLITE_RESULT_SUCCESS, null));
                isProvisionedDoneEvent.sendToTarget();
                break;
            }

            case EVENT_IS_SATELLITE_PROVISIONED_DONE: {
                handleIsSatelliteProvisionedDoneEvent((AsyncResult) msg.obj);
                break;
            }

            case EVENT_PENDING_DATAGRAMS:
                plogd("Received EVENT_PENDING_DATAGRAMS");
                IIntegerConsumer internalCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        plogd("pollPendingSatelliteDatagram result: " + result);
                    }
                };
                pollPendingDatagrams(internalCallback);
                break;

            case EVENT_SATELLITE_MODEM_STATE_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    ploge("EVENT_SATELLITE_MODEM_STATE_CHANGED: result is null");
                } else {
                    handleEventSatelliteModemStateChanged((int) ar.result);
                    updateLastNotifiedCarrierRoamingNtnSignalStrengthAndNotify(getSatellitePhone());
                }
                break;

            case EVENT_SET_SATELLITE_NETWORK_INFO_DONE:
                handleSetSatellitePlmnNetworkInfoDoneEvent(msg);
                break;

            case EVENT_SET_SATELLITE_PLMN_INFO_DONE:
                handleSetSatellitePlmnInfoDoneEvent(msg);
                break;

            case CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE: {
                plogd("CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE");
                request = (SatelliteControllerHandlerRequest) msg.obj;
                handleRequestSatelliteAttachRestrictionForCarrierCmd(request);
                break;
            }

            case EVENT_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                RequestHandleSatelliteAttachRestrictionForCarrierArgument argument =
                        (RequestHandleSatelliteAttachRestrictionForCarrierArgument)
                                request.argument;
                int subId = argument.subId;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "requestSetSatelliteEnabledForCarrier");

                plogd("EVENT_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE_DONE: subId="
                        + subId + " error:" + error);
                if (error == SATELLITE_RESULT_SUCCESS) {
                    boolean enableSatellite = mSatelliteAttachRestrictionForCarrierArray
                            .getOrDefault(argument.subId, Collections.emptySet()).isEmpty();
                    plogd("EVENT_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE_DONE: "
                            + "satelliteAttachEnabledForCarrier=" + enableSatellite);
                    mIsSatelliteAttachEnabledForCarrierArrayPerSub.put(subId, enableSatellite);
                } else {
                    mIsSatelliteAttachEnabledForCarrierArrayPerSub.remove(subId);
                }
                argument.callback.accept(error);
                // The restriction reasons have been updated, which might affect the list of PLMNs
                // need to be configured to modem, so we need to reconfigure the satellite PLMNs.
                configureSatellitePlmnForCarrier(subId);
                break;
            }

            case CMD_REQUEST_NTN_SIGNAL_STRENGTH: {
                plogd("CMD_REQUEST_NTN_SIGNAL_STRENGTH");
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_REQUEST_NTN_SIGNAL_STRENGTH_DONE, request);
                mSatelliteModemInterface.requestNtnSignalStrength(onCompleted);
                break;
            }

            case EVENT_REQUEST_NTN_SIGNAL_STRENGTH_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                ResultReceiver result = (ResultReceiver) request.argument;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "requestNtnSignalStrength");

                if (errorCode == SATELLITE_RESULT_SUCCESS) {
                    NtnSignalStrength ntnSignalStrength = (NtnSignalStrength) ar.result;
                    if (ntnSignalStrength != null) {
                        setNtnSignalStrength(ntnSignalStrength);
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(KEY_NTN_SIGNAL_STRENGTH, ntnSignalStrength);
                        result.send(SATELLITE_RESULT_SUCCESS, bundle);
                    } else {
                        if (getNtnSignalStrength().getLevel() != NTN_SIGNAL_STRENGTH_NONE) {
                            setNtnSignalStrength(new NtnSignalStrength(NTN_SIGNAL_STRENGTH_NONE));
                        }
                        ploge("EVENT_REQUEST_NTN_SIGNAL_STRENGTH_DONE: ntnSignalStrength is null");
                        result.send(SatelliteManager.SATELLITE_RESULT_REQUEST_FAILED, null);
                    }
                } else {
                    if (getNtnSignalStrength().getLevel() != NTN_SIGNAL_STRENGTH_NONE) {
                        setNtnSignalStrength(new NtnSignalStrength(NTN_SIGNAL_STRENGTH_NONE));
                    }
                    result.send(errorCode, null);
                }
                decrementResultReceiverCount("SC:requestNtnSignalStrength");
                break;
            }

            case EVENT_NTN_SIGNAL_STRENGTH_CHANGED: {
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    ploge("EVENT_NTN_SIGNAL_STRENGTH_CHANGED: result is null");
                } else {
                    handleEventNtnSignalStrengthChanged((NtnSignalStrength) ar.result);
                    updateLastNotifiedCarrierRoamingNtnSignalStrengthAndNotify(getSatellitePhone());
                }
                break;
            }

            case CMD_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING: {
                ar = (AsyncResult) msg.obj;
                boolean shouldReport = (boolean) ar.result;
                if (DBG) {
                    plogd("CMD_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING: shouldReport=" + shouldReport);
                }
                handleCmdUpdateNtnSignalStrengthReporting(shouldReport);
                break;
            }

            case EVENT_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                boolean shouldReport = (boolean) request.argument;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "EVENT_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING_DONE: shouldReport="
                                + shouldReport);
                if (errorCode == SATELLITE_RESULT_SUCCESS) {
                    mIsModemEnabledReportingNtnSignalStrength.set(shouldReport);
                    if (mLatestRequestedStateForNtnSignalStrengthReport.get()
                            != mIsModemEnabledReportingNtnSignalStrength.get()) {
                        logd("mLatestRequestedStateForNtnSignalStrengthReport does not match with "
                                + "mIsModemEnabledReportingNtnSignalStrength");
                        updateNtnSignalStrengthReporting(
                                mLatestRequestedStateForNtnSignalStrengthReport.get());
                    }
                } else {
                    loge(((boolean) request.argument ? "startSendingNtnSignalStrength"
                            : "stopSendingNtnSignalStrength") + "returns " + errorCode);
                }
                break;
            }

            case EVENT_SERVICE_STATE_CHANGED: {
                handleEventServiceStateChanged();
                break;
            }

            case EVENT_SATELLITE_CAPABILITIES_CHANGED: {
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    ploge("EVENT_SATELLITE_CAPABILITIES_CHANGED: result is null");
                } else {
                    handleEventSatelliteCapabilitiesChanged((SatelliteCapabilities) ar.result);
                }
                break;
            }

            case EVENT_SATELLITE_SUPPORTED_STATE_CHANGED: {
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    ploge("EVENT_SATELLITE_SUPPORTED_STATE_CHANGED: result is null");
                } else {
                    handleEventSatelliteSupportedStateChanged((boolean) ar.result);
                }
                break;
            }

            case EVENT_SATELLITE_CONFIG_DATA_UPDATED: {
                handleEventConfigDataUpdated();
                mSatelliteConfigUpdateChangedRegistrants.notifyRegistrants();
                break;
            }

            case EVENT_NOTIFY_NTN_HYSTERESIS_TIMED_OUT: {
                int phoneId = (int) msg.obj;
                Phone phone = PhoneFactory.getPhone(phoneId);
                updateLastNotifiedNtnModeAndNotify(phone);
                break;
            }

            case EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT: {
                boolean eligible = isCarrierRoamingNtnEligible(getSatellitePhone());
                plogd("EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT:"
                        + " isCarrierRoamingNtnEligible=" + eligible);
                updateLastNotifiedNtnEligibilityAndNotify(eligible);
                break;
            }

            case CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION: {
                evaluateESOSProfilesPrioritization();
                break;
            }

            case CMD_UPDATE_PROVISION_SATELLITE_TOKEN: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                RequestProvisionSatelliteArgument argument =
                        (RequestProvisionSatelliteArgument) request.argument;
                onCompleted = obtainMessage(EVENT_UPDATE_PROVISION_SATELLITE_TOKEN_DONE, request);
                boolean provisionChanged = updateSatelliteSubscriptionProvisionState(
                        argument.mSatelliteSubscriberInfoList, argument.mProvisioned);
                selectBindingSatelliteSubscription(false);
                int subId = getSelectedSatelliteSubId();
                SubscriptionInfo subscriptionInfo =
                    mSubscriptionManagerService.getSubscriptionInfo(subId);
                if (subscriptionInfo == null) {
                    logw("updateSatelliteToken subId=" + subId + " is not found");
                } else {
                    String iccId = subscriptionInfo.getIccId();
                    argument.setIccId(iccId);
                    if (!iccId.equals(getLastConfiguredIccId())) {
                        logd("updateSatelliteSubscription subId=" + subId
                                + ", iccId=" + iccId + " to modem");
                        mSatelliteModemInterface.updateSatelliteSubscription(
                                iccId, onCompleted);
                    }
                }
                if (provisionChanged) {
                    handleEventSatelliteSubscriptionProvisionStateChanged();
                }

                // The response is sent immediately because the ICCID has already been
                // delivered to the modem.
                Bundle bundle = new Bundle();
                bundle.putBoolean(
                        argument.mProvisioned ? SatelliteManager.KEY_PROVISION_SATELLITE_TOKENS
                                : SatelliteManager.KEY_DEPROVISION_SATELLITE_TOKENS, true);
                argument.mResult.send(SATELLITE_RESULT_SUCCESS, bundle);
                decrementResultReceiverCount("SC:provisionSatellite");
                break;
            }

            case EVENT_UPDATE_PROVISION_SATELLITE_TOKEN_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                RequestProvisionSatelliteArgument argument =
                        (RequestProvisionSatelliteArgument) request.argument;
                int error = SatelliteServiceUtils.getSatelliteError(ar,
                        "updateSatelliteSubscription");
                if (error == SATELLITE_RESULT_SUCCESS) {
                    setLastConfiguredIccId(argument.getIccId());
                }
                mProvisionMetricsStats.setResultCode(error)
                        .setIsProvisionRequest(argument.mProvisioned)
                        .setCarrierId(getSatelliteCarrierId())
                        .setSupportedConnectionMode(getSupportedConnectTypeMetrics())
                        .setIsNtnOnlyCarrier(isNtnOnlyCarrier())
                        .reportProvisionMetrics();
                if (argument.mProvisioned) {
                    mControllerMetricsStats.reportProvisionCount(error);
                } else {
                    mControllerMetricsStats.reportDeprovisionCount(error);
                }
                logd("updateSatelliteSubscription result=" + error);
                break;
            }

            case EVENT_WIFI_CONNECTIVITY_STATE_CHANGED: {
                ar = (AsyncResult) msg.obj;
                boolean isWifiConnected = (boolean) ar.result;
                mIsWifiConnected.set(isWifiConnected);
                plogd("EVENT_WIFI_CONNECTIVITY_STATE_CHANGED: mIsWifiConnected="
                        + isWifiConnected);
                evaluateCarrierRoamingNtnEligibilityChange();
                handleEventWifiConnectivityStateChanged(isWifiConnected);
                break;
            }

            case EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT: {
                plogw("Timed out to wait for cellular modem OFF state");
                mRadioOffRequested.set(false);
                break;
            }

            case EVENT_WAIT_FOR_REGULAR_METRICS_REPORT_HYSTERESIS_TIMED_OUT: {
                handleEntireEntitlementMetricReport();
                handleEntireProvisionMetricReport();
                handleCarrierRoamingConfigVersionReport();
                handleMaxAllowedDataMetricsReport();
                scheduleRegularMetricReportTimer();
                handleEntireEligibilityMetricReport();
                break;
            }

            case EVENT_SATELLITE_REGISTRATION_FAILURE:
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    loge("EVENT_SATELLITE_REGISTRATION_FAILURE: result is null");
                } else {
                    handleEventSatelliteRegistrationFailure((int) ar.result);
                }
                break;

            case EVENT_TERRESTRIAL_NETWORK_AVAILABLE_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    loge("EVENT_TERRESTRIAL_NETWORK_AVAILABLE_CHANGED: result is null");
                } else {
                    handleEventTerrestrialNetworkAvailableChanged((boolean) ar.result);
                }
                break;

            case EVENT_SET_NETWORK_SELECTION_AUTO_DONE: {
                logd("EVENT_SET_NETWORK_SELECTION_AUTO_DONE");
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) msg.obj;
                sendRequestAsync(CMD_SET_SATELLITE_ENABLED, argument, null);
                break;
            }

            case EVENT_SIGNAL_STRENGTH_CHANGED: {
                ar = (AsyncResult) msg.obj;
                int phoneId = (int) ar.userObj;
                updateLastNotifiedCarrierRoamingNtnSignalStrengthAndNotify(
                        PhoneFactory.getPhone(phoneId));
                break;
            }

            case CMD_UPDATE_SYSTEM_SELECTION_CHANNELS: {
                plogd("CMD_UPDATE_SYSTEM_SELECTION_CHANNELS");
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted =
                    obtainMessage(EVENT_UPDATE_SYSTEM_SELECTION_CHANNELS_DONE, request);
                UpdateSystemSelectionChannelsArgument argument =
                        (UpdateSystemSelectionChannelsArgument) request.argument;
                mSatelliteModemInterface.updateSystemSelectionChannels(
                        argument.systemSelectionSpecifiers, onCompleted);
                startWaitForUpdateSystemSelectionChannelsResponseTimer(argument);
                break;
            }

            case EVENT_UPDATE_SYSTEM_SELECTION_CHANNELS_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(
                        ar, "updateSystemSelectionChannel");
                plogd("EVENT_UPDATE_SYSTEM_SELECTION_CHANNELS_DONE = " + error);
                UpdateSystemSelectionChannelsArgument argument =
                        (UpdateSystemSelectionChannelsArgument) request.argument;
                if (shouldProcessEventUpdateSystemSelectionChannelsDone(argument)) {
                    argument.result.send(error, null);
                    stopWaitForUpdateSystemSelectionChannelsResponseTimer(argument);
                } else {
                    plogd("EVENT_UPDATE_SYSTEM_SELECTION_CHANNELS_DONE: the timer of "
                              + "the request ID " + argument.requestId
                              + " has already expired.");
                }
                break;
            }

            case EVENT_WAIT_FOR_UPDATE_SYSTEM_SELECTION_CHANNELS_RESPONSE_TIMED_OUT: {
                UpdateSystemSelectionChannelsArgument argument =
                        (UpdateSystemSelectionChannelsArgument) msg.obj;
                argument.result.send(SATELLITE_RESULT_MODEM_TIMEOUT, null);
                plogd("Timed out to wait for response of the system selection channels"
                          + " update request ID " + argument.requestId);
                break;
            }

            case EVENT_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_CHANGED: {
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    loge("EVENT_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_CHANGED: result is null");
                } else {
                    handleEventSelectedNbIotSatelliteSubscriptionChanged((int) ar.result);
                }
                break;
            }

            case CMD_LOCATION_SERVICE_STATE_CHANGED:
                plogd("CMD_LOCATION_SERVICE_STATE_CHANGED");
                // Fall through
            case CMD_EVALUATE_CARRIER_ROAMING_NTN_ELIGIBILITY_CHANGE: {
                plogd("CMD_EVALUATE_CARRIER_ROAMING_NTN_ELIGIBILITY_CHANGE");
                evaluateCarrierRoamingNtnEligibilityChange();
                boolean eligible = isCarrierRoamingNtnEligible(getSatellitePhone());
                plogd("CMD_EVALUATE_CARRIER_ROAMING_NTN_ELIGIBILITY_CHANGE: eligible=" + eligible);
                int selectedSatelliteSubId = getSelectedSatelliteSubId();
                Phone phone = SatelliteServiceUtils.getPhone(selectedSatelliteSubId);
                if (eligible) {
                    setLastNotifiedNtnEligibility(eligible);
                    phone.notifyCarrierRoamingNtnEligibleStateChanged(eligible);
                }
                break;
            }

            case CMD_GET_SATELLITE_ENABLED_FOR_CARRIER: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                Phone phone = request.phone;
                int subId = phone.getSubId();
                onCompleted = obtainMessage(EVENT_GET_SATELLITE_ENABLED_FOR_CARRIER_DONE,
                        subId);
                int simSlot = SubscriptionManager.getSlotIndex(subId);
                plogd("CMD_GET_SATELLITE_ENABLED_FOR_CARRIER: subId=" + subId);
                phone.isSatelliteEnabledForCarrier(simSlot, onCompleted);
                break;
            }

            case EVENT_GET_SATELLITE_ENABLED_FOR_CARRIER_DONE: {
                ar = (AsyncResult) msg.obj;

                if (ar.result == null) {
                    loge("EVENT_GET_SATELLITE_ENABLED_FOR_CARRIER_DONE: result is null");
                } else {
                    int subId = (int) ar.userObj;
                    int error = SatelliteServiceUtils.getSatelliteError(
                            ar, "isSatelliteEnabledForCarrier");
                    boolean satelliteEnabled = (Boolean) ar.result;
                    plogd("EVENT_GET_SATELLITE_ENABLED_FOR_CARRIER_DONE: subId=" + subId
                            + " error=" + error + " satelliteEnabled=" + satelliteEnabled);

                    if (error == SATELLITE_RESULT_SUCCESS) {
                        mIsSatelliteAttachEnabledForCarrierArrayPerSub.put(
                                subId, satelliteEnabled);
                        evaluateEnablingSatelliteForCarrier(subId,
                                SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER, null);
                    }
                }
                break;
            }

            case REQUEST_SATELLITE_ENABLED: {
                plogd("REQUEST_SATELLITE_ENABLED");
                SomeArgs args = (SomeArgs) msg.obj;
                boolean enableSatellite = (boolean) args.arg1;
                boolean enableDemoMode = (boolean) args.arg2;
                boolean isEmergency = (boolean) args.arg3;
                IIntegerConsumer callback = (IIntegerConsumer) args.arg4;
                try {
                    handleRequestSatelliteEnabled(
                            enableSatellite, enableDemoMode, isEmergency, callback);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_IS_SATELLITE_ENABLED: {
                plogd("REQUEST_IS_SATELLITE_ENABLED");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestIsSatelliteEnabled(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_IS_DEMO_MODE_ENABLED: {
                plogd("REQUEST_IS_DEMO_MODE_ENABLED");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestIsDemoModeEnabled(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_IS_EMERGENCY_MODE_ENABLED: {
                plogd("REQUEST_IS_EMERGENCY_MODE_ENABLED");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestIsEmergencyModeEnabled(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_IS_SATELLITE_SUPPORTED: {
                plogd("REQUEST_IS_SATELLITE_SUPPORTED");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestIsSatelliteSupported(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_SATELLITE_CAPABILITIES: {
                plogd("REQUEST_SATELLITE_CAPABILITIES");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestSatelliteCapabilities(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_START_SATELLITE_TRANSMISSION_UPDATES: {
                plogd("REQUEST_START_SATELLITE_TRANSMISSION_UPDATES");
                SomeArgs args = (SomeArgs) msg.obj;
                IIntegerConsumer errorCallback = (IIntegerConsumer) args.arg1;
                ISatelliteTransmissionUpdateCallback callback =
                        (ISatelliteTransmissionUpdateCallback) args.arg2;
                try {
                    handleRequestStartSatelliteTransmissionUpdates(errorCallback, callback);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_STOP_SATELLITE_TRANSMISSION_UPDATES: {
                plogd("REQUEST_STOP_SATELLITE_TRANSMISSION_UPDATES");
                SomeArgs args = (SomeArgs) msg.obj;
                IIntegerConsumer errorCallback = (IIntegerConsumer) args.arg1;
                ISatelliteTransmissionUpdateCallback callback =
                        (ISatelliteTransmissionUpdateCallback) args.arg2;
                try {
                    handleRequestStopSatelliteTransmissionUpdates(errorCallback, callback);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_IS_SATELLITE_PROVISIONED: {
                plogd("REQUEST_IS_SATELLITE_PROVISIONED");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestIsSatelliteProvisioned(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_POLL_PENDING_DATAGRAMS: {
                plogd("REQUEST_POLL_PENDING_DATAGRAMS");
                SomeArgs args = (SomeArgs) msg.obj;
                IIntegerConsumer callback = (IIntegerConsumer) args.arg1;
                try {
                    handleRequestPollPendingDatagrams(callback);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_SEND_DATAGRAM: {
                plogd("REQUEST_SEND_DATAGRAM");
                SomeArgs args = (SomeArgs) msg.obj;
                int datagramType = (int) args.arg1;
                SatelliteDatagram datagram = (SatelliteDatagram) args.arg2;
                boolean needFullScreenPointingUI = (boolean) args.arg3;
                IIntegerConsumer callback = (IIntegerConsumer) args.arg4;
                try {
                    handleRequestSendDatagram(
                            datagramType, datagram, needFullScreenPointingUI, callback);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_TIME_FOR_NEXT_SATELLITE_VISIBILITY: {
                plogd("REQUEST_TIME_FOR_NEXT_SATELLITE_VISIBILITY");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestTimeForNextSatelliteVisibility(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_SATELLITE_DISPLAY_NAME: {
                plogd("REQUEST_SATELLITE_DISPLAY_NAME");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestSatelliteDisplayName(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_ID: {
                plogd("REQUEST_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_ID");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestSelectedNbIotSatelliteSubscriptionId(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_SET_DEVICE_ALIGNED_WITH_SATELLITE: {
                plogd("REQUEST_SET_DEVICE_ALIGNED_WITH_SATELLITE");
                SomeArgs args = (SomeArgs) msg.obj;
                boolean isAligned = (boolean) args.arg1;
                try {
                    handleRequestSetDeviceAlignedWithSatellite(isAligned);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_ADD_ATTACH_RESTRICTION_FOR_CARRIER: {
                plogd("REQUEST_ADD_ATTACH_RESTRICTION_FOR_CARRIER");
                SomeArgs args = (SomeArgs) msg.obj;
                int subId = (int) args.arg1;
                int reason = (int) args.arg2;
                IIntegerConsumer callback = (IIntegerConsumer) args.arg3;
                try {
                    handleRequestAddAttachRestrictionForCarrier(subId, reason, callback);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_REMOVE_ATTACH_RESTRICTION_FOR_CARRIER: {
                plogd("REQUEST_REMOVE_ATTACH_RESTRICTION_FOR_CARRIER");
                SomeArgs args = (SomeArgs) msg.obj;
                int subId = (int) args.arg1;
                int reason = (int) args.arg2;
                IIntegerConsumer callback = (IIntegerConsumer) args.arg3;
                try {
                    handleRequestRemoveAttachRestrictionForCarrier(subId, reason, callback);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_NTN_SIGNAL_STRENGTH: {
                plogd("REQUEST_NTN_SIGNAL_STRENGTH");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestNtnSignalStrength(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_SATELLITE_SUBSCRIBER_PROVISION_STATUS: {
                plogd("REQUEST_SATELLITE_SUBSCRIBER_PROVISION_STATUS");
                SomeArgs args = (SomeArgs) msg.obj;
                ResultReceiver result = (ResultReceiver) args.arg1;
                try {
                    handleRequestSatelliteSubscriberProvisionStatus(result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_SET_NTN_SMS_SUPPORTED_BY_MESSAGES_APP: {
                plogd("REQUEST_SET_NTN_SMS_SUPPORTED_BY_MESSAGES_APP");
                SomeArgs args = (SomeArgs) msg.obj;
                boolean ntnSmsSupported = (boolean) args.arg1;
                try {
                    handleRequestSetNtnSmsSupportedByMessagesApp(ntnSmsSupported);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_PROVISION_SATELLITE: {
                plogd("REQUEST_PROVISION_SATELLITE");
                SomeArgs args = (SomeArgs) msg.obj;
                List<SatelliteSubscriberInfo> list = (List<SatelliteSubscriberInfo>) args.arg1;
                ResultReceiver result = (ResultReceiver) args.arg2;
                try {
                    handleRequestProvisionSatellite(list, result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case REQUEST_DEPROVISION_SATELLITE: {
                plogd("REQUEST_DEPROVISION_SATELLITE");
                SomeArgs args = (SomeArgs) msg.obj;
                List<SatelliteSubscriberInfo> list = (List<SatelliteSubscriberInfo>) args.arg1;
                ResultReceiver result = (ResultReceiver) args.arg2;
                try {
                    handleRequestDeprovisionSatellite(list, result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case EVENT_SATELLITE_ACCESS_ALLOWED_STATE_CHANGED: {
                plogd("EVENT_SATELLITE_ACCESS_ALLOWED_STATE_CHANGED");
                handleSatelliteAccessAllowedStateChanged((boolean) msg.obj);
                break;
            }

            case EVENT_SATELLITE_ACCESS_CONFIGURATION_CHANGED: {
                plogd("EVENT_SATELLITE_ACCESS_CONFIGURATION_CHANGED");
                handleSatelliteAccessConfigUpdateResult((SatelliteAccessConfiguration) msg.obj);
                break;
            }

            case EVENT_BT_WIFI_NFC_STATE_CHANGED: {
                plogd("EVENT_BT_WIFI_NFC_STATE_CHANGED");
                handleEventBtWifiNfcStateChanged((Intent) msg.obj);
                break;
            }

            case EVENT_UWB_STATE_CHANGED: {
                plogd("EVENT_UWB_STATE_CHANGED");
                handleEventUwbStateChanged((int) msg.obj);
                break;
            }

            case EVENT_CARRIER_CONFIG_CHANGED: {
                plogd("EVENT_CARRIER_CONFIG_CHANGED");
                SomeArgs args = (SomeArgs) msg.obj;
                int slotIndex = (int) args.arg1;
                int subId = (int) args.arg2;
                int carrierId = (int) args.arg3;
                int specificCarrierId = (int) args.arg4;
                try {
                    handleCarrierConfigChanged(slotIndex, subId, carrierId, specificCarrierId);
                } finally {
                    args.recycle();
                }
                break;
            }

            case EVENT_SATELLITE_ENTILEMENT_STATUS_UPDATED: {
                plogd("EVENT_SATELLITE_ENTILEMENT_STATUS_UPDATED");
                SomeArgs args = (SomeArgs) msg.obj;
                int subId = args.argi1;
                boolean entitlementEnabled = args.argi2 == TRUE ? true : false;
                List<String> allowedPlmnList = (List<String>) args.arg1;
                List<String> barredPlmnList = (List<String>) args.arg2;
                Map<String, Integer> plmnDataPlanMap = (Map<String, Integer>) args.arg3;
                Map<String, List<Integer>> plmnServiceTypeMap =
                    (Map<String, List<Integer>>) args.arg4;
                Map<String, Integer> plmnDataServicePolicyMap = (Map<String, Integer>) args.arg5;
                Map<String, Integer> plmnVoiceServicePolicyMap = (Map<String, Integer>) args.arg6;
                IIntegerConsumer callback = (IIntegerConsumer) args.arg7;
                try {
                    handleSatelliteEntitlementStatusUpdated(subId, entitlementEnabled,
                        allowedPlmnList, barredPlmnList, plmnDataPlanMap, plmnServiceTypeMap,
                        plmnDataServicePolicyMap, plmnVoiceServicePolicyMap, callback);
                } finally {
                    args.recycle();
                }
                break;
            }

            case EVENT_PACKAGE_CHANGED:
                handlePackageChangeEvent();
                break;

            case REQUEST_POINTING_UI_APP_LAUNCH_INTENT: {
                plogd("REQUEST_POINTING_UI_APP_LAUNCH_INTENT");
                SomeArgs args = (SomeArgs) msg.obj;
                PointingUiAppLaunchIntentAttributes launchIntentAttributes =
                        (PointingUiAppLaunchIntentAttributes) args.arg1;
                ResultReceiver result = (ResultReceiver) args.arg2;
                try {
                    handleRequestPointingUiAppLaunchIntent(launchIntentAttributes, result);
                } finally {
                    args.recycle();
                }
                break;
            }

            case EVENT_SCREEN_STATE_CHANGED:
                handleEventScreenStateChanged((AsyncResult) msg.obj);
                break;

            default:
                Log.w(TAG, "SatelliteControllerHandler: unexpected message code: " +
                        msg.what);
                break;
        }
    }

    private static final class RequestProvisionSatelliteArgument {
        public List<SatelliteSubscriberInfo> mSatelliteSubscriberInfoList;
        @NonNull
        public ResultReceiver mResult;
        public long mRequestId;
        @NonNull public String mIccId;
        public boolean mProvisioned;

        RequestProvisionSatelliteArgument(List<SatelliteSubscriberInfo> satelliteSubscriberInfoList,
                ResultReceiver result, boolean provisioned) {
            this.mSatelliteSubscriberInfoList = satelliteSubscriberInfoList;
            this.mResult = result;
            this.mProvisioned = provisioned;
            this.mRequestId = sNextSatelliteEnableRequestId.getAndUpdate(
                    n -> ((n + 1) % Long.MAX_VALUE));
        }

        public void setIccId(@NonNull String iccId) {
            mIccId = iccId;
        }

        @NonNull
        public String getIccId() {
            return mIccId;
        }
    }

    private void handleEventConfigDataUpdated() {
        updateSupportedSatelliteServicesForActiveSubscriptions();
        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds != null) {
            for (int subId : activeSubIds) {
                processNewCarrierConfigData(subId);
            }
        } else {
            ploge("updateSupportedSatelliteServicesForActiveSubscriptions: "
                    + "activeSubIds is null");
        }
    }

    private void notifyRequester(SatelliteControllerHandlerRequest request) {
        synchronized (request) {
            request.notifyAll();
        }
    }

    /**
     * Request to enable or disable the satellite modem and demo mode. If the satellite modem is
     * enabled, this will also disable the cellular modem, and if the satellite modem is disabled,
     * this will also re-enable the cellular modem.
     *
     * @param enableSatellite {@code true} to enable the satellite modem and
     *                        {@code false} to disable.
     * @param enableDemoMode {@code true} to enable demo mode and {@code false} to disable.
     * @param isEmergency {@code true} to enable emergency mode, {@code false} otherwise.
     * @param callback The callback to get the error code of the request.
     */
    public void requestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
            boolean isEmergency, @NonNull IIntegerConsumer callback) {
        plogd("requestSatelliteEnabled enableSatellite: " + enableSatellite
                + " enableDemoMode: " + enableDemoMode + " isEmergency: " + isEmergency);
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = enableSatellite;
            args.arg2 = enableDemoMode;
            args.arg3 = isEmergency;
            args.arg4 = callback;
            sendMessage(obtainMessage(REQUEST_SATELLITE_ENABLED, args));
            return;
        }

        handleRequestSatelliteEnabled(enableSatellite, enableDemoMode, isEmergency, callback);
    }

    private void handleRequestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
            boolean isEmergency, @NonNull IIntegerConsumer callback) {
        plogd("handleRequestSatelliteEnabled: enableSatellite: " + enableSatellite
                + " enableDemoMode: " + enableDemoMode + " isEmergency: " + isEmergency);

        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            sendErrorAndReportSessionMetrics(error, result);
            return;
        }

        if (enableSatellite) {
            if (!mIsRadioOn.get()) {
                ploge("Radio is not on, can not enable satellite");
                sendErrorAndReportSessionMetrics(
                        SatelliteManager.SATELLITE_RESULT_INVALID_MODEM_STATE, result);
                return;
            }
            if (mRadioOffRequested.get()) {
                ploge("Radio is being powering off, can not enable satellite");
                sendErrorAndReportSessionMetrics(
                        SatelliteManager.SATELLITE_RESULT_INVALID_MODEM_STATE, result);
                return;
            }

            if (mTelecomManager.isInEmergencyCall()) {
                plogd("requestSatelliteEnabled: reject as emergency call is ongoing.");
                sendErrorAndReportSessionMetrics(
                        SatelliteManager.SATELLITE_RESULT_EMERGENCY_CALL_IN_PROGRESS, result);
                return;
            }
        } else {
            /* if disable satellite, always assume demo is also disabled */
            enableDemoMode = false;
        }

        RequestSatelliteEnabledArgument request =
                new RequestSatelliteEnabledArgument(enableSatellite, enableDemoMode, isEmergency,
                        result);
        /**
         * Multiple satellite enabled requests are handled as below:
         * 1. If there are no ongoing requests, store current request in mSatelliteEnabledRequest
         * 2. If there is a ongoing request, then:
         *      1. ongoing request = enable, current request = enable: return IN_PROGRESS error
         *      2. ongoing request = disable, current request = disable: return IN_PROGRESS error
         *      3. ongoing request = disable, current request = enable: return
         *      SATELLITE_RESULT_ERROR error
         *      4. ongoing request = enable, current request = disable: send request to modem
         */
        Boolean isSatelliteEnabled = getIsSatelliteEnabled();
        RequestSatelliteEnabledArgument enabledRequest = getSatelliteEnabledRequest();
        if (enabledRequest != null && mNetworkSelectionModeAutoDialog != null
                && mNetworkSelectionModeAutoDialog.isShowing()
                && request.isEmergency && request.enableSatellite) {
            sendErrorAndReportSessionMetrics(
                    SatelliteManager.SATELLITE_RESULT_ILLEGAL_STATE,
                    FunctionalUtils.ignoreRemoteException(enabledRequest.callback::accept));
            setSatelliteEnabledRequest(null);
            mNetworkSelectionModeAutoDialog.dismiss();
            mNetworkSelectionModeAutoDialog = null;
        }

        if (!isSatelliteEnabledRequestInProgress()) {
            if (isSatelliteEnabled != null && isSatelliteEnabled == enableSatellite) {
                evaluateToUpdateSatelliteEnabledAttributes(result,
                        SatelliteManager.SATELLITE_RESULT_SUCCESS, request,
                        mIsDemoModeEnabled.get(), mIsEmergency.get());
                return;
            }

            if (enableSatellite) {
                setSatelliteEnabledRequest(request);
            } else {
                setSatelliteDisabledRequest(request);
            }
        } else if (isSatelliteBeingDisabled()) {
            int resultCode = SatelliteManager.SATELLITE_RESULT_REQUEST_IN_PROGRESS;
            if (enableSatellite) {
                plogw("requestSatelliteEnabled: The enable request cannot be "
                        + "processed since disable satellite is in progress.");
                resultCode = SatelliteManager.SATELLITE_RESULT_DISABLE_IN_PROGRESS;
            } else {
                plogd("requestSatelliteEnabled: Disable is already in progress.");
            }
            sendErrorAndReportSessionMetrics(resultCode, result);
            return;
        } else {
            // Satellite is being enabled or satellite enable attributes are being updated
            if (enableSatellite) {
                if (getSatelliteEnableAttributesUpdateRequest() == null) {
                    /* Satellite is being enabled and framework receive a new enable request to
                     * update the enable attributes.
                     */
                    evaluateToUpdateSatelliteEnabledAttributes(result,
                            SatelliteManager.SATELLITE_RESULT_REQUEST_IN_PROGRESS,
                            request, getSatelliteEnabledRequest().enableDemoMode,
                            getSatelliteEnabledRequest().isEmergency);
                } else {
                    /* The enable attributes update request is already being processed.
                     * Framework can't handle one more request to update enable attributes.
                     */
                    plogd("requestSatelliteEnabled: enable attributes update request is already"
                            + " in progress.");
                    sendErrorAndReportSessionMetrics(
                            SatelliteManager.SATELLITE_RESULT_REQUEST_IN_PROGRESS, result);
                }
                return;
            } else {
                /* Users might want to end the satellite session while it is being enabled, or
                 * the satellite session need to be disabled for an emergency call. Note: some
                 * carriers want to disable satellite for prioritizing emergency calls. Thus,
                 * we need to push the disable request to modem while enable is in progress.
                 */
                if (!isDisableSatelliteWhileEnableInProgressSupported()) {
                    plogd("requestSatelliteEnabled: disable satellite while enable in progress"
                            + " is not supported");
                    sendErrorAndReportSessionMetrics(
                            SatelliteManager.SATELLITE_RESULT_ENABLE_IN_PROGRESS, result);
                    return;
                }
                setSatelliteDisabledRequest(request);
            }
        }

        Phone satellitePhone = getSatellitePhone();
        if (enableSatellite && satellitePhone != null
                && satellitePhone.getServiceStateTracker() != null
                && satellitePhone.getServiceStateTracker().getServiceState()
                .getIsManualSelection()) {
            checkNetworkSelectionModeAuto(request);
        } else {
            sendRequestAsync(CMD_SET_SATELLITE_ENABLED, request, null);
        }
    }

    /**
     * Request to enable or disable the satellite.
     *
     * @param attributes The attributes of the enable request.
     */
    public void requestEnableSatellite(int subId, @NonNull EnableRequestAttributes attributes,
            @NonNull IIntegerConsumer callback) {
        plogd("requestEnableSatellite: " + attributes);

        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        plogd("requestEnableSatellite: not yet supported");
        result.accept(SATELLITE_RESULT_SUCCESS);

        if (!mFeatureFlags.satelliteUpsell()) {
            plogd("requestEnableSatellite: satellite upsell is not enabled");
            return;
        }

        // TODO: Support subId, connectType based satellite enablement.
        plogd("requestEnableSatellite: falling back to manual satellite enablement");
        handleRequestSatelliteEnabled(attributes.isEnabled(), attributes.isDemoMode(),
                attributes.isEmergencyMode(), callback);
    }

    private boolean isDisableSatelliteWhileEnableInProgressSupported() {
        if (mOverriddenDisableSatelliteWhileEnableInProgressSupported != null) {
            return mOverriddenDisableSatelliteWhileEnableInProgressSupported.get();
        }
        return mContext.getResources().getBoolean(
            R.bool.config_support_disable_satellite_while_enable_in_progress);
    }

    private void checkNetworkSelectionModeAuto(RequestSatelliteEnabledArgument argument) {
        plogd("checkNetworkSelectionModeAuto");
        if (argument.isEmergency) {
            // ESOS
            getSatellitePhone().setNetworkSelectionModeAutomatic(null);
            sendMessageDelayed(obtainMessage(EVENT_SET_NETWORK_SELECTION_AUTO_DONE, argument),
                    DELAY_WAITING_SET_NETWORK_SELECTION_AUTO_MILLIS);
        } else {
            // P2P
            if (mNetworkSelectionModeAutoDialog != null
                    && mNetworkSelectionModeAutoDialog.isShowing()) {
                logd("requestSatelliteEnabled: already auto network selection mode popup showing");
                sendErrorAndReportSessionMetrics(
                        SatelliteManager.SATELLITE_RESULT_REQUEST_IN_PROGRESS,
                        FunctionalUtils.ignoreRemoteException(argument.callback::accept));
                return;
            }
            logd("requestSatelliteEnabled: auto network selection mode popup");
            Configuration configuration = Resources.getSystem().getConfiguration();
            boolean nightMode = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;

            AlertDialog.Builder builder = new AlertDialog.Builder(mContext, nightMode
                    ? AlertDialog.THEME_DEVICE_DEFAULT_DARK
                    : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);

            String title = mContext.getResources().getString(
                    R.string.satellite_manual_selection_state_popup_title);
            String message = mContext.getResources().getString(
                    R.string.satellite_manual_selection_state_popup_message);
            String ok = mContext.getResources().getString(
                    R.string.satellite_manual_selection_state_popup_ok);
            String cancel = mContext.getResources().getString(
                    R.string.satellite_manual_selection_state_popup_cancel);

            builder.setTitle(title).setMessage(message)
                    .setPositiveButton(ok, (dialog, which) -> {
                        logd("checkNetworkSelectionModeAuto: setPositiveButton");
                        getSatellitePhone().setNetworkSelectionModeAutomatic(null);
                        sendMessageDelayed(obtainMessage(EVENT_SET_NETWORK_SELECTION_AUTO_DONE,
                                argument), DELAY_WAITING_SET_NETWORK_SELECTION_AUTO_MILLIS);
                    })
                    .setNegativeButton(cancel, (dialog, which) -> {
                        logd("checkNetworkSelectionModeAuto: setNegativeButton");
                        setSatelliteEnabledRequest(null);
                        sendErrorAndReportSessionMetrics(
                                SatelliteManager.SATELLITE_RESULT_ILLEGAL_STATE,
                                FunctionalUtils.ignoreRemoteException(argument.callback::accept));
                    })
                    .setOnCancelListener(dialog -> {
                        logd("checkNetworkSelectionModeAuto: setOnCancelListener");
                        setSatelliteEnabledRequest(null);
                        sendErrorAndReportSessionMetrics(
                                SatelliteManager.SATELLITE_RESULT_ILLEGAL_STATE,
                                FunctionalUtils.ignoreRemoteException(argument.callback::accept));
                    });
            mNetworkSelectionModeAutoDialog = builder.create();
            mNetworkSelectionModeAutoDialog.getWindow()
                    .setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            mNetworkSelectionModeAutoDialog.show();
        }
    }

    /**
     * Validate the newly-received enable attributes against the current ones. If the new attributes
     * are valid and different from the current ones, framework will send a request to update the
     * enable attributes to modem. Otherwise, framework will return
     * {@code SATELLITE_RESULT_INVALID_ARGUMENTS} to the requesting clients.
     *
     * @param result The callback that returns the result to the requesting client.
     * @param resultCode The result code to send back to the requesting client when framework does
     *                   not need to reconfigure modem.
     * @param enableRequest The new enable request to update satellite enable attributes.
     * @param currentDemoMode The current demo mode at framework.
     * @param currentEmergencyMode The current emergency mode at framework.
     */
    private void evaluateToUpdateSatelliteEnabledAttributes(@NonNull Consumer<Integer> result,
            @SatelliteManager.SatelliteResult int resultCode,
            @NonNull RequestSatelliteEnabledArgument enableRequest, boolean currentDemoMode,
            boolean currentEmergencyMode) {
        boolean needToReconfigureModem = false;
        if (enableRequest.enableDemoMode != currentDemoMode) {
            if (enableRequest.enableDemoMode) {
                ploge("Moving from real mode to demo mode is rejected");
                sendErrorAndReportSessionMetrics(SATELLITE_RESULT_INVALID_ARGUMENTS, result);
                return;
            } else {
                plogd("Moving from demo mode to real mode. Need to reconfigure"
                        + " modem with real mode");
                needToReconfigureModem = true;
            }
        } else if (enableRequest.isEmergency != currentEmergencyMode) {
            if (enableRequest.isEmergency) {
                plogd("Moving from non-emergency to emergency mode. Need to "
                        + "reconfigure modem");
                needToReconfigureModem = true;
            } else {
                plogd("Non-emergency requests can be served during an emergency"
                        + " satellite session. No need to reconfigure modem.");
            }
        }

        if (needToReconfigureModem) {
            setSatelliteEnableAttributesUpdateRequest(enableRequest);
            sendRequestAsync(
                    CMD_UPDATE_SATELLITE_ENABLE_ATTRIBUTES, enableRequest, null);
        } else {
            if (resultCode != SatelliteManager.SATELLITE_RESULT_SUCCESS) {
                plogd("requestSatelliteEnabled enable satellite is already in progress.");
            }
            result.accept(resultCode);
        }
    }

    /**
     * @return {@code true} when either enable request, disable request, or enable attributes update
     * request is in progress, {@code false} otherwise.
     */
    private boolean isSatelliteEnabledRequestInProgress() {
        RequestSatelliteEnabledArgument enabledRequest = getSatelliteEnabledRequest();
        RequestSatelliteEnabledArgument disabledRequest = getSatelliteDisabledRequest();
        RequestSatelliteEnabledArgument enableAttributesUpdateRequest =
                getSatelliteEnableAttributesUpdateRequest();
        plogd("mSatelliteEnabledRequest: " + (enabledRequest != null)
                + ", mSatelliteDisabledRequest: " + (disabledRequest != null)
                + ", mSatelliteEnableAttributesUpdateRequest: "
                + (enableAttributesUpdateRequest != null));
        return (enabledRequest != null || disabledRequest != null
                || enableAttributesUpdateRequest != null);
    }

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param result The result receiver that returns whether the satellite modem is enabled
     *               if the request is successful or an error code if the request failed.
     */
    public void requestIsSatelliteEnabled(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_IS_SATELLITE_ENABLED, args));
            return;
        }

        handleRequestIsSatelliteEnabled(result);
    }

    private void handleRequestIsSatelliteEnabled(@NonNull ResultReceiver result) {
        plogd("handleRequestIsSatelliteEnabled");
        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        Boolean isSatelliteEnabled = getIsSatelliteEnabled();
        if (isSatelliteEnabled != null) {
            /* We have already successfully queried the satellite modem. */
            Bundle bundle = new Bundle();
            bundle.putBoolean(SatelliteManager.KEY_SATELLITE_ENABLED, isSatelliteEnabled);
            result.send(SATELLITE_RESULT_SUCCESS, bundle);
            return;
        }

        sendRequestAsync(CMD_IS_SATELLITE_ENABLED, result, null);
        incrementResultReceiverCount("SC:requestIsSatelliteEnabled");
    }

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param result The result receiver that returns whether the satellite modem is enabled
     *               if the request is successful or an error code if the request failed.
     */
    public void requestEnableSatelliteStatus(int subId,
            @CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_TYPE int connectType,
            @NonNull ResultReceiver result) {
        plogd("requestEnableSatelliteStatus: subId: " + subId + ", connectType: " + connectType);

        if (!mFeatureFlags.satelliteUpsell()) {
            plogd("requestEnableSatellite: satellite upsell is not enabled");
            result.send(SATELLITE_RESULT_SUCCESS, null);
            return;
        }

        // TODO: Support subId, connectType based enablement status requests.
        plogd("requestEnableSatelliteStatus not yet supported");
        result.send(SATELLITE_RESULT_SUCCESS, null);
    }

    /**
     * Get whether the satellite modem is enabled.
     * This will return the cached value instead of querying the satellite modem.
     *
     * @return {@code true} if the satellite modem is enabled and {@code false} otherwise.
     */
    public boolean isSatelliteEnabled() {
        if (mIsSatelliteEnabled == null) return false;
        return mIsSatelliteEnabled.get();
    }

    /**
     * Get whether satellite modem is being enabled.
     *
     * @return {@code true} if the satellite modem is being enabled and {@code false} otherwise.
     */
    public boolean isSatelliteBeingEnabled() {
        if (mSatelliteSessionController != null
                && mSatelliteSessionController.isInEnablingState()) {
            return true;
        }

        return (getSatelliteEnabledRequest() != null);
    }

    /**
     * Get whether the satellite modem is enabled or being enabled.
     * This will return the cached value instead of querying the satellite modem.
     *
     * @return {@code true} if the satellite modem is enabled or being enabled, {@code false}
     * otherwise.
     */
    public boolean isSatelliteEnabledOrBeingEnabled() {
        return isSatelliteEnabled() || isSatelliteBeingEnabled();
    }

    /**
     * Get whether satellite modem is being disabled.
     *
     * @return {@code true} if the satellite modem is being disabled and {@code false} otherwise.
     */
    public boolean isSatelliteBeingDisabled() {
        if (mSatelliteSessionController != null
                && mSatelliteSessionController.isInDisablingState()) {
            return true;
        }

        return (getSatelliteDisabledRequest() != null);
    }

    /**
     * Request to get whether the satellite service demo mode is enabled.
     *
     * @param result The result receiver that returns whether the satellite demo mode is enabled
     *               if the request is successful or an error code if the request failed.
     */
    public void requestIsDemoModeEnabled(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_IS_DEMO_MODE_ENABLED, args));
            return;
        }

        handleRequestIsDemoModeEnabled(result);
    }

    private void handleRequestIsDemoModeEnabled(@NonNull ResultReceiver result) {
        plogd("handleRequestIsDemoModeEnabled");
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        final Bundle bundle = new Bundle();
        bundle.putBoolean(SatelliteManager.KEY_DEMO_MODE_ENABLED, mIsDemoModeEnabled.get());
        result.send(SATELLITE_RESULT_SUCCESS, bundle);
    }

    /**
     * Get whether the satellite service demo mode is enabled.
     *
     * @return {@code true} if the satellite demo mode is enabled and {@code false} otherwise.
     */
    public boolean isDemoModeEnabled() {
        return mIsDemoModeEnabled.get();
    }

    /**
     * Request to get whether the satellite enabled request is for emergency or not.
     *
     * @param result The result receiver that returns whether the request is for emergency
     *               if the request is successful or an error code if the request failed.
     */
    public void requestIsEmergencyModeEnabled(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_IS_EMERGENCY_MODE_ENABLED, args));
            return;
        }

        handleRequestIsEmergencyModeEnabled(result);
    }

    private void handleRequestIsEmergencyModeEnabled(@NonNull ResultReceiver result) {
        plogd("handleRequestIsEmergencyModeEnabled");
        Bundle bundle = new Bundle();
        bundle.putBoolean(SatelliteManager.KEY_EMERGENCY_MODE_ENABLED,
                getRequestIsEmergency());
        result.send(SATELLITE_RESULT_SUCCESS, bundle);
    }

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * @param result The result receiver that returns whether the satellite service is supported on
     *               the device if the request is successful or an error code if the request failed.
     */
    public void requestIsSatelliteSupported(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_IS_SATELLITE_SUPPORTED, args));
            return;
        }

        handleRequestIsSatelliteSupported(result);
    }

    private void handleRequestIsSatelliteSupported(@NonNull ResultReceiver result) {
        plogd("handleRequestIsSatelliteSupported");
        int subId = getSelectedSatelliteSubId();
        Boolean isSatelliteSupported = getIsSatelliteSupported();
        if (isSatelliteSupported != null) {
            /* We have already successfully queried the satellite modem. */
            Bundle bundle = new Bundle();
            bundle.putBoolean(SatelliteManager.KEY_SATELLITE_SUPPORTED, isSatelliteSupported);
            bundle.putInt(SATELLITE_SUBSCRIPTION_ID, subId);
            result.send(SATELLITE_RESULT_SUCCESS, bundle);
            return;
        }

        sendRequestAsync(CMD_IS_SATELLITE_SUPPORTED, result, null);
    }

    /**
     * Request to get the {@link SatelliteCapabilities} of the satellite service.
     *
     * @param result The result receiver that returns the {@link SatelliteCapabilities}
     *               if the request is successful or an error code if the request failed.
     */
    public void requestSatelliteCapabilities(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_SATELLITE_CAPABILITIES, args));
            return;
        }

        handleRequestSatelliteCapabilities(result);
    }

    private void handleRequestSatelliteCapabilities(@NonNull ResultReceiver result) {
        plogd("handleRequestSatelliteCapabilities");
        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        if (getSatelliteCapabilities() != null) {
            Bundle bundle = new Bundle();
            overrideSatelliteCapabilitiesIfApplicable();
            bundle.putParcelable(SatelliteManager.KEY_SATELLITE_CAPABILITIES,
                    getSatelliteCapabilities());
            result.send(SATELLITE_RESULT_SUCCESS, bundle);
            return;
        }

        sendRequestAsync(CMD_GET_SATELLITE_CAPABILITIES, result, null);
    }

    /**
     * Start receiving satellite transmission updates.
     * This can be called by the pointing UI when the user starts pointing to the satellite.
     * Modem should continue to report the pointing input as the device or satellite moves.
     *
     * @param errorCallback The callback to get the error code of the request.
     * @param callback The callback to notify of satellite transmission updates.
     */
    public void startSatelliteTransmissionUpdates(
            @NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteTransmissionUpdateCallback callback) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = errorCallback;
            args.arg2 = callback;
            sendMessage(obtainMessage(REQUEST_START_SATELLITE_TRANSMISSION_UPDATES, args));
            return;
        }

        handleRequestStartSatelliteTransmissionUpdates(errorCallback, callback);
    }

    private void handleRequestStartSatelliteTransmissionUpdates(
            @NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteTransmissionUpdateCallback callback) {
        plogd("handleRequestStartSatelliteTransmissionUpdates");
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(errorCallback::accept);
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.accept(error);
            return;
        }

        final int validSubId = getSelectedSatelliteSubId();
        mPointingAppController.registerForSatelliteTransmissionUpdates(validSubId, callback);
        sendRequestAsync(CMD_START_SATELLITE_TRANSMISSION_UPDATES,
                new SatelliteTransmissionUpdateArgument(result, callback, validSubId), null);
    }

    /**
     * Stop receiving satellite transmission updates.
     * This can be called by the pointing UI when the user stops pointing to the satellite.
     *
     * @param errorCallback The callback to get the error code of the request.
     * @param callback The callback that was passed to {@link #startSatelliteTransmissionUpdates(
     *                 int, IIntegerConsumer, ISatelliteTransmissionUpdateCallback)}.
     */
    public void stopSatelliteTransmissionUpdates(@NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteTransmissionUpdateCallback callback) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = errorCallback;
            args.arg2 = callback;
            sendMessage(obtainMessage(REQUEST_STOP_SATELLITE_TRANSMISSION_UPDATES, args));
            return;
        }

        handleRequestStopSatelliteTransmissionUpdates(errorCallback, callback);
    }

    private void handleRequestStopSatelliteTransmissionUpdates(
            @NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteTransmissionUpdateCallback callback) {
        plogd("handleRequestStopSatelliteTransmissionUpdates");
        Consumer<Integer> internalResult = new Consumer<Integer>() {
            @Override
            public void accept(Integer result) {
                plogd("handleRequestStopSatelliteTransmissionUpdates: "
                        + "unregisterForSatelliteTransmissionUpdates result=" + result);
            }
        };
        mPointingAppController.unregisterForSatelliteTransmissionUpdates(
                getSelectedSatelliteSubId(), internalResult, callback);

        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(errorCallback::accept);
        // Even if handler is null - which means there are no listeners, the modem command to stop
        // satellite transmission updates might have failed. The callers might want to retry
        // sending the command. Thus, we always need to send this command to the modem.
        sendRequestAsync(CMD_STOP_SATELLITE_TRANSMISSION_UPDATES, result, null);
    }

    /**
     * Register the subscription with a satellite provider.
     * This is needed to register the subscription if the provider allows dynamic registration.
     *
     * @param token The token to be used as a unique identifier for provisioning with satellite
     *              gateway.
     * @param provisionData Data from the provisioning app that can be used by provisioning server
     * @param callback The callback to get the error code of the request.
     *
     * @return The signal transport used by the caller to cancel the provision request,
     *         or {@code null} if the request failed.
     */
    @Nullable public ICancellationSignal provisionSatelliteService(
            @NonNull String token, @NonNull byte[] provisionData,
            @NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        List<SatelliteSubscriberInfo> subscriberInfoList =
                getNtnOnlySatelliteSubscriberInfoList(result);
        if (subscriberInfoList == null) {
            return null;
        }
        ResultReceiver internalReceiver = new ResultReceiver(this) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                plogd("provisionSatelliteService: resultCode=" + resultCode
                          + ", resultData=" + resultData);
                result.accept(resultCode);
            }
        };
        provisionSatellite(subscriberInfoList, internalReceiver);

        ICancellationSignal cancelTransport = CancellationSignal.createTransport();
        CancellationSignal.fromTransport(cancelTransport).setOnCancelListener(() -> {
            deprovisionSatellite(subscriberInfoList, internalReceiver);
            mProvisionMetricsStats.setIsCanceled(true);
        });
        return cancelTransport;
    }

    /**
     * Unregister the device/subscription with the satellite provider.
     * This is needed if the provider allows dynamic registration. Once deprovisioned,
     * {@link android.telephony.satellite.SatelliteProvisionStateCallback
     * #onSatelliteProvisionStateChanged(boolean)}
     * should report as deprovisioned.
     *
     * @param token The token of the device/subscription to be deprovisioned.
     * @param callback The callback to get the error code of the request.
     */
    public void deprovisionSatelliteService(
            @NonNull String token, @NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        List<SatelliteSubscriberInfo> subscriberInfoList =
                getNtnOnlySatelliteSubscriberInfoList(result);
        if (subscriberInfoList == null) {
            return;
        }
        ResultReceiver internalReceiver = new ResultReceiver(this) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                plogd("deprovisionSatelliteService: resultCode=" + resultCode
                          + ", resultData=" + resultData);
                result.accept(resultCode);
            }
        };
        deprovisionSatellite(subscriberInfoList, internalReceiver);
    }

    /**
     * Registers for the satellite provision state changed.
     *
     * @param callback The callback to handle the satellite provision state changed event.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForSatelliteProvisionStateChanged(
            @NonNull ISatelliteProvisionStateCallback callback) {
        mSatelliteProvisionStateChangedListeners.put(callback.asBinder(), callback);

        boolean isProvisioned = Boolean.TRUE.equals(isDeviceProvisioned());
        try {
            callback.onSatelliteProvisionStateChanged(isProvisioned);
        } catch (RemoteException ex) {
            loge("registerForSatelliteProvisionStateChanged: " + ex);
        }
        plogd("registerForSatelliteProvisionStateChanged: report current provisioned "
                + "state, state=" + isProvisioned);
        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for the satellite provision state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteProvisionStateChanged(int, ISatelliteProvisionStateCallback)}.
     */
    public void unregisterForSatelliteProvisionStateChanged(
            @NonNull ISatelliteProvisionStateCallback callback) {
        mSatelliteProvisionStateChangedListeners.remove(callback.asBinder());
    }

    /**
     * Request to get whether the device is provisioned with a satellite provider.
     *
     * @param result The result receiver that returns whether the device is provisioned with a
     *               satellite provider if the request is successful or an error code if the
     *               request failed.
     */
    public void requestIsSatelliteProvisioned(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_IS_SATELLITE_PROVISIONED, args));
            return;
        }

        handleRequestIsSatelliteProvisioned(result);
    }

    private void handleRequestIsSatelliteProvisioned(@NonNull ResultReceiver result) {
        plogd("handleRequestIsSatelliteProvisioned");
        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        if (mIsDeviceProvisioned != null) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(SatelliteManager.KEY_SATELLITE_PROVISIONED,
                    mIsDeviceProvisioned.get());
            result.send(SATELLITE_RESULT_SUCCESS, bundle);
            return;
        }

        sendRequestAsync(CMD_IS_SATELLITE_PROVISIONED, result, null);
        incrementResultReceiverCount("SC:requestIsSatelliteProvisioned");
    }

    /**
     * Registers for modem state changed from satellite modem.
     *
     * @param callback The callback to handle the satellite modem state changed event.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForSatelliteModemStateChanged(
            @NonNull ISatelliteModemStateCallback callback) {
        plogd("registerForSatelliteModemStateChanged: add Listeners for ModemState");
        mSatelliteRegistrationFailureListeners.put(callback.asBinder(), callback);
        mTerrestrialNetworkAvailableChangedListeners.put(callback.asBinder(), callback);

        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.registerForSatelliteModemStateChanged(callback);
        } else {
            ploge("registerForSatelliteModemStateChanged: mSatelliteSessionController"
                    + " is not initialized yet");
            return SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
        }
        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for modem state changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteModemStateChanged(int, ISatelliteModemStateCallback)}.
     */
    public void unregisterForModemStateChanged(
            @NonNull ISatelliteModemStateCallback callback) {
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.unregisterForSatelliteModemStateChanged(callback);
        } else {
            ploge("unregisterForModemStateChanged: mSatelliteSessionController"
                    + " is not initialized yet");
        }
        plogd("unregisterForModemStateChanged: remove Listeners for ModemState");
        mSatelliteRegistrationFailureListeners.remove(callback.asBinder());
        mTerrestrialNetworkAvailableChangedListeners.remove(callback.asBinder());
    }

    /**
     * Register to receive incoming datagrams over satellite.
     *
     * @param callback The callback to handle incoming datagrams over satellite.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForIncomingDatagram(
            @NonNull ISatelliteDatagramCallback callback) {
        if (!mSatelliteModemInterface.isSatelliteServiceSupported()) {
            return SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED;
        }
        plogd("registerForIncomingDatagram: callback=" + callback);
        return mDatagramController.registerForSatelliteDatagram(
                getSelectedSatelliteSubId(), callback);
    }

    /**
     * Unregister to stop receiving incoming datagrams over satellite.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     *                 {@link #registerForIncomingDatagram(int, ISatelliteDatagramCallback)}.
     */
    public void unregisterForIncomingDatagram(
            @NonNull ISatelliteDatagramCallback callback) {
        if (!mSatelliteModemInterface.isSatelliteServiceSupported()) {
            return;
        }
        plogd("unregisterForIncomingDatagram: callback=" + callback);
        mDatagramController.unregisterForSatelliteDatagram(
                getSelectedSatelliteSubId(), callback);
    }

    /**
     * Poll pending satellite datagrams over satellite.
     *
     * This method requests modem to check if there are any pending datagrams to be received over
     * satellite. If there are any incoming datagrams, they will be received via
     * {@link android.telephony.satellite.SatelliteDatagramCallback#onSatelliteDatagramReceived(
     * long, SatelliteDatagram, int, Consumer)}
     *
     * @param callback The callback to get {@link SatelliteManager.SatelliteResult} of the request.
     */
    public void pollPendingDatagrams(@NonNull IIntegerConsumer callback) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callback;
            sendMessage(obtainMessage(REQUEST_POLL_PENDING_DATAGRAMS, args));
            return;
        }

        handleRequestPollPendingDatagrams(callback);
    }

    private void handleRequestPollPendingDatagrams(@NonNull IIntegerConsumer callback) {
        plogd("handleRequestPollPendingDatagrams");
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.accept(error);
            return;
        }

        mDatagramController.pollPendingSatelliteDatagrams(
                getSelectedSatelliteSubId(), result);
    }

    /**
     * Send datagram over satellite.
     *
     * Gateway encodes SOS message or location sharing message into a datagram and passes it as
     * input to this method. Datagram received here will be passed down to modem without any
     * encoding or encryption.
     *
     * @param datagramType datagram type indicating whether the datagram is of type
     *                     SOS_SMS or LOCATION_SHARING.
     * @param datagram encoded gateway datagram which is encrypted by the caller.
     *                 Datagram will be passed down to modem without any encoding or encryption.
     * @param needFullScreenPointingUI this is used to indicate pointingUI app to open in
     *                                 full screen mode.
     * @param callback The callback to get {@link SatelliteManager.SatelliteResult} of the request.
     */
    public void sendDatagram(@SatelliteManager.DatagramType int datagramType,
            SatelliteDatagram datagram, boolean needFullScreenPointingUI,
            @NonNull IIntegerConsumer callback) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = datagramType;
            args.arg2 = datagram;
            args.arg3 = needFullScreenPointingUI;
            args.arg4 = callback;
            sendMessage(obtainMessage(REQUEST_SEND_DATAGRAM, args));
            return;
        }

        handleRequestSendDatagram(datagramType, datagram, needFullScreenPointingUI, callback);
    }

    private void handleRequestSendDatagram(@SatelliteManager.DatagramType int datagramType,
            SatelliteDatagram datagram, boolean needFullScreenPointingUI,
            @NonNull IIntegerConsumer callback) {
        plogd("sendSatelliteDatagram: datagramType: " + datagramType
                + " needFullScreenPointingUI: " + needFullScreenPointingUI);

        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.accept(error);
            return;
        }

        /**
         * TODO for NTN-based satellites: Check if satellite is acquired.
         */
        if (mNeedsSatellitePointing.get()) {
            mPointingAppController.startPointingUI(needFullScreenPointingUI,
                    mIsDemoModeEnabled.get(), mIsEmergency.get());
        }

        mDatagramController.sendSatelliteDatagram(getSelectedSatelliteSubId(), datagramType,
                datagram, needFullScreenPointingUI, result);
    }

    /**
     * Request to get the time after which the satellite will be visible.
     *
     * @param result The result receiver that returns the time after which the satellite will
     *               be visible if the request is successful or an error code if the request failed.
     */
    public void requestTimeForNextSatelliteVisibility(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_TIME_FOR_NEXT_SATELLITE_VISIBILITY, args));
            return;
        }

        handleRequestTimeForNextSatelliteVisibility(result);
    }

    private void handleRequestTimeForNextSatelliteVisibility(@NonNull ResultReceiver result) {
        plogd("handleRequestTimeForNextSatelliteVisibility");
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        sendRequestAsync(CMD_GET_TIME_SATELLITE_NEXT_VISIBLE, result, null);
        incrementResultReceiverCount("SC:requestTimeForNextSatelliteVisibility");
    }

    /**
     * Inform whether the device is aligned with the satellite in both real and demo mode.
     *
     * @param isAligned {@true} means device is aligned with the satellite, otherwise {@false}.
     */
    public void setDeviceAlignedWithSatellite(@NonNull boolean isAligned) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = isAligned;
            sendMessage(obtainMessage(REQUEST_SET_DEVICE_ALIGNED_WITH_SATELLITE, args));
            return;
        }

        handleRequestSetDeviceAlignedWithSatellite(isAligned);
    }

    private void handleRequestSetDeviceAlignedWithSatellite(boolean isAligned) {
        plogd("handleSetDeviceAlignedWithSatellite: isAligned=" + isAligned);
        DemoSimulator.getInstance().setDeviceAlignedWithSatellite(isAligned);
        mDatagramController.setDeviceAlignedWithSatellite(isAligned);
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.setDeviceAlignedWithSatellite(isAligned);
        } else {
            ploge("setDeviceAlignedWithSatellite: mSatelliteSessionController"
                    + " is not initialized yet");
        }
    }

    /**
     * Add a restriction reason for disallowing carrier supported satellite plmn scan and attach
     * by modem. After updating restriction list, evaluate if satellite should be enabled/disabled,
     * and request modem to enable/disable satellite accordingly if the desired state does not match
     * the current state.
     *
     * @param subId The subId of the subscription to request for.
     * @param reason Reason for disallowing satellite communication for carrier.
     * @param callback The callback to get the result of the request.
     */
    public void addAttachRestrictionForCarrier(int subId,
            @SatelliteManager.SatelliteCommunicationRestrictionReason int reason,
            @NonNull IIntegerConsumer callback) {
        logd("addAttachRestrictionForCarrier(" + subId + ", " + reason + ")");
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = subId;
            args.arg2 = reason;
            args.arg3 = callback;
            sendMessage(obtainMessage(REQUEST_ADD_ATTACH_RESTRICTION_FOR_CARRIER, args));
            return;
        }

        handleRequestAddAttachRestrictionForCarrier(subId, reason, callback);
    }

    private void handleRequestAddAttachRestrictionForCarrier(int subId,
            @SatelliteManager.SatelliteCommunicationRestrictionReason int reason,
            @NonNull IIntegerConsumer callback) {
        plogd("handleRequestAddAttachRestrictionForCarrier: subId=" + subId + " reason=" + reason);
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);

        if (mSatelliteAttachRestrictionForCarrierArray.getOrDefault(
                subId, Collections.emptySet()).isEmpty()) {
            mSatelliteAttachRestrictionForCarrierArray.put(subId, new HashSet<>());
        } else if (mSatelliteAttachRestrictionForCarrierArray.get(subId).contains(reason)) {
            result.accept(SATELLITE_RESULT_SUCCESS);
            return;
        }
        mSatelliteAttachRestrictionForCarrierArray.get(subId).add(reason);
        plogd("handleRequestAddAttachRestrictionForCarrier: subId=" + subId
                + ", reasons=" + mSatelliteAttachRestrictionForCarrierArray.get(subId));

        RequestHandleSatelliteAttachRestrictionForCarrierArgument request =
                new RequestHandleSatelliteAttachRestrictionForCarrierArgument(subId, reason,
                        result);
        sendRequestAsync(CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE, request,
                SatelliteServiceUtils.getPhone(subId));
    }

    /**
     * Remove a restriction reason for disallowing carrier supported satellite plmn scan and attach
     * by modem. After updating restriction list, evaluate if satellite should be enabled/disabled,
     * and request modem to enable/disable satellite accordingly if the desired state does not match
     * the current state.
     *
     * @param subId The subId of the subscription to request for.
     * @param reason Reason for disallowing satellite communication.
     * @param callback The callback to get the result of the request.
     */
    public void removeAttachRestrictionForCarrier(int subId,
            @SatelliteManager.SatelliteCommunicationRestrictionReason int reason,
            @NonNull IIntegerConsumer callback) {
        logd("removeAttachRestrictionForCarrier(" + subId + ", " + reason + ")");
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = subId;
            args.arg2 = reason;
            args.arg3 = callback;
            sendMessage(obtainMessage(REQUEST_REMOVE_ATTACH_RESTRICTION_FOR_CARRIER, args));
            return;
        }

        handleRequestRemoveAttachRestrictionForCarrier(subId, reason, callback);
    }

    private void handleRequestRemoveAttachRestrictionForCarrier(int subId,
            @SatelliteManager.SatelliteCommunicationRestrictionReason int reason,
            @NonNull IIntegerConsumer callback) {
        plogd("handleRequestRemoveAttachRestrictionForCarrier: subId=" + subId
                + " reason=" + reason);
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);

        if (mSatelliteAttachRestrictionForCarrierArray.getOrDefault(
                subId, Collections.emptySet()).isEmpty()
                || !mSatelliteAttachRestrictionForCarrierArray.get(subId).contains(reason)) {
            result.accept(SATELLITE_RESULT_SUCCESS);
            return;
        }
        mSatelliteAttachRestrictionForCarrierArray.get(subId).remove(reason);
        plogd("handleRequestRemoveAttachRestrictionForCarrier: subId=" + subId
                + ", reasons=" + mSatelliteAttachRestrictionForCarrierArray.get(subId));

        RequestHandleSatelliteAttachRestrictionForCarrierArgument request =
                new RequestHandleSatelliteAttachRestrictionForCarrierArgument(subId, reason,
                        result);
        sendRequestAsync(CMD_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE, request,
                SatelliteServiceUtils.getPhone(subId));
    }

    /**
     * Get reasons for disallowing satellite communication, as requested by
     * {@link #addAttachRestrictionForCarrier(int, int, IIntegerConsumer)}.
     *
     * @param subId The subId of the subscription to request for.
     *
     * @return Set of reasons for disallowing satellite attach for carrier.
     */
    @NonNull public Set<Integer> getAttachRestrictionReasonsForCarrier(int subId) {
        Set<Integer> resultSet =
                mSatelliteAttachRestrictionForCarrierArray.get(subId);
        if (resultSet == null) {
            return new HashSet<>();
        }
        return new HashSet<>(resultSet);
    }

    /**
     * Request to get the signal strength of the satellite connection.
     *
     * @param result Result receiver to get the error code of the request and the current signal
     * strength of the satellite connection.
     */
    public void requestNtnSignalStrength(@NonNull ResultReceiver result) {
        if (DBG) plogd("requestNtnSignalStrength()");

        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_NTN_SIGNAL_STRENGTH, args));
            return;
        }

        handleRequestNtnSignalStrength(result);
    }

    private void handleRequestNtnSignalStrength(@NonNull ResultReceiver result) {
        plogd("handleRequestNtnSignalStrength");
        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error != SATELLITE_RESULT_SUCCESS) {
            result.send(error, null);
            return;
        }

        /* In case cache is available, it is not needed to request non-terrestrial signal strength
        to modem */
        NtnSignalStrength ntnSignalStrength = getNtnSignalStrength();
        if (ntnSignalStrength.getLevel() != NTN_SIGNAL_STRENGTH_NONE) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(KEY_NTN_SIGNAL_STRENGTH, ntnSignalStrength);
            result.send(SATELLITE_RESULT_SUCCESS, bundle);
            return;
        }

        Phone phone = SatelliteServiceUtils.getPhone();
        sendRequestAsync(CMD_REQUEST_NTN_SIGNAL_STRENGTH, result, phone);
        incrementResultReceiverCount("SC:requestNtnSignalStrength");
    }

    /**
     * Registers for NTN signal strength changed from satellite modem. If the registration operation
     * is not successful, a {@link ServiceSpecificException} that contains
     * {@link SatelliteManager.SatelliteResult} will be thrown.
     *
     * @param callback The callback to handle the NTN signal strength changed event. If the
     * operation is successful, {@link INtnSignalStrengthCallback#onNtnSignalStrengthChanged(
     * NtnSignalStrength)} will return an instance of {@link NtnSignalStrength} with a value of
     * {@link NtnSignalStrength.NtnSignalStrengthLevel} when the signal strength of non-terrestrial
     * network has changed.
     *
     * @throws ServiceSpecificException If the callback registration operation fails.
     */
    public void registerForNtnSignalStrengthChanged(
            @NonNull INtnSignalStrengthCallback callback) throws RemoteException {
        if (DBG) plogd("registerForNtnSignalStrengthChanged()");

        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error == SATELLITE_RESULT_SUCCESS) {
            mNtnSignalStrengthChangedListeners.put(callback.asBinder(), callback);
            NtnSignalStrength ntnSignalStrength = getNtnSignalStrength();
            try {
                callback.onNtnSignalStrengthChanged(ntnSignalStrength);
                plogd("registerForNtnSignalStrengthChanged: " + ntnSignalStrength);
            } catch (RemoteException ex) {
                ploge("registerForNtnSignalStrengthChanged: RemoteException ex="
                        + ex);
            }
        } else {
            throw new IllegalStateException("registration fails: " + error);
        }
    }

    /**
     * Unregisters for NTN signal strength changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * changed event.
     * @param callback The callback that was passed to
     * {@link #registerForNtnSignalStrengthChanged(int, INtnSignalStrengthCallback)}
     */
    public void unregisterForNtnSignalStrengthChanged(
            @NonNull INtnSignalStrengthCallback callback) {
        if (DBG) plogd("unregisterForNtnSignalStrengthChanged()");
        mNtnSignalStrengthChangedListeners.remove(callback.asBinder());
    }

    /**
     * Registers for satellite capabilities change event from the satellite service.
     *
     * @param callback The callback to handle the satellite capabilities changed event.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForCapabilitiesChanged(
            @NonNull ISatelliteCapabilitiesCallback callback) {
        if (DBG) plogd("registerForCapabilitiesChanged()");

        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) return error;

        mSatelliteCapabilitiesChangedListeners.put(callback.asBinder(), callback);
        SatelliteCapabilities capabilities = getSatelliteCapabilities();
        logd("registerForCapabilitiesChanged: capabilities=" + capabilities);
        try {
            if (capabilities != null) {
                callback.onSatelliteCapabilitiesChanged(capabilities);
            } else {
                logd("registerForCapabilitiesChanged: capabilities is null");
            }
        } catch (RemoteException ex) {
            ploge("registerForCapabilitiesChanged: RemoteException ex=" + ex);
        }
        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for satellite capabilities change event from the satellite service.
     * If callback was not registered before, the request will be ignored.
     *
     * changed event.
     * @param callback The callback that was passed to
     * {@link #registerForCapabilitiesChanged(int, ISatelliteCapabilitiesCallback)}
     */
    public void unregisterForCapabilitiesChanged(
            @NonNull ISatelliteCapabilitiesCallback callback) {
        if (DBG) plogd("unregisterForCapabilitiesChanged()");
        mSatelliteCapabilitiesChangedListeners.remove(callback.asBinder());
    }

    /**
     * Registers for the satellite supported state changed.
     *
     * @param callback The callback to handle the satellite supported state changed event.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult public int registerForSatelliteSupportedStateChanged(
            @NonNull IBooleanConsumer callback) {
        mSatelliteSupportedStateChangedListeners.put(callback.asBinder(), callback);
        Boolean isSatelliteSupported = getIsSatelliteSupported();
        if (isSatelliteSupported != null) {
            final boolean supported = isSatelliteSupported;
            post(() -> {
                try {
                    callback.accept(supported);
                } catch (RemoteException ex) {
                    ploge("registerForSatelliteSupportedStateChanged: RemoteException ex=" + ex);
                }
            });
        } else {
            logd("registerForSatelliteSupportedStateChanged: cached supported state is null");
        }
        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for the satellite supported state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     *                 {@link #registerForSatelliteSupportedStateChanged(IBooleanConsumer)}
     */
    public void unregisterForSatelliteSupportedStateChanged(
            @NonNull IBooleanConsumer callback) {
        mSatelliteSupportedStateChangedListeners.remove(callback.asBinder());
    }

    /**
     * Registers for selected satellite subscription changed event.
     *
     * @param callback The callback to handle the selected satellite subscription changed event.
     *
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult
    public int registerForSelectedNbIotSatelliteSubscriptionChanged(
            @NonNull ISelectedNbIotSatelliteSubscriptionCallback callback) {
        if (DBG) plogd("registerForSelectedNbIotSatelliteSubscriptionChanged()");

        int error = evaluateOemSatelliteRequestAllowed(false);
        if (error != SATELLITE_RESULT_SUCCESS) return error;

        mSelectedNbIotSatelliteSubscriptionChangedListeners.put(callback.asBinder(), callback);
        try {
            callback.onSelectedNbIotSatelliteSubscriptionChanged(getSelectedSatelliteSubId());
        } catch (RemoteException ex) {
            ploge("registerForSelectedNbIotSatelliteSubscriptionChanged: RemoteException ex="
                    + ex);
        }
        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for the selected satellite subscription changed event.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to {@link
     *     #registerForSelectedNbIotSatelliteSubscriptionChanged(
     *     ISelectedNbIotSatelliteSubscriptionCallback)}.
     */
    public void unregisterForSelectedNbIotSatelliteSubscriptionChanged(
            @NonNull ISelectedNbIotSatelliteSubscriptionCallback callback) {
        if (DBG) plogd("unregisterForSelectedNbIotSatelliteSubscriptionChanged()");

        int error = evaluateOemSatelliteRequestAllowed(true);
        if (error == SATELLITE_RESULT_SUCCESS) {
            mSelectedNbIotSatelliteSubscriptionChangedListeners.remove(callback.asBinder());
        }
    }

    /**
     * This API can be used by only CTS to update satellite vendor service package name.
     *
     * @param servicePackageName The package name of the satellite vendor service.
     * @param provisioned          Whether satellite should be provisioned or not.
     * @return {@code true} if the satellite vendor service is set successfully,
     * {@code false} otherwise.
     */
    public boolean setSatelliteServicePackageName(@Nullable String servicePackageName,
            String provisioned) {
        if (!isMockModemAllowed()) {
            plogd("setSatelliteServicePackageName: mock modem not allowed");
            return false;
        }

        // Cached states need to be cleared whenever switching satellite vendor services.
        plogd("setSatelliteServicePackageName: Resetting cached states, provisioned="
                + provisioned);
        mIsSatelliteSupported = null;
        mIsSatelliteEnabled = null;
        setSatelliteCapabilities(null);
        mSatelliteModemInterface.setSatelliteServicePackageName(servicePackageName);
        return true;
    }

    /**
     * This API can be used by only CTS to update the timeout duration in milliseconds that
     * satellite should stay at listening mode to wait for the next incoming page before disabling
     * listening mode.
     *
     * @param timeoutMillis The timeout duration in millisecond.
     * @return {@code true} if the timeout duration is set successfully, {@code false} otherwise.
     */
    public boolean setSatelliteListeningTimeoutDuration(long timeoutMillis) {
        if (mSatelliteSessionController == null) {
            ploge("mSatelliteSessionController is not initialized yet");
            return false;
        }
        return mSatelliteSessionController.setSatelliteListeningTimeoutDuration(timeoutMillis);
    }

    /**
     * This API can be used by only CTS to override TN scanning support.
     *
     * @param concurrentTnScanningSupported Whether concurrent TN scanning is supported.
     * @param tnScanningDuringSatelliteSessionAllowed Whether TN scanning is allowed during
     * a satellite session.
     * @return {@code true} if the TN scanning support is set successfully,
     * {@code false} otherwise.
     */
    public boolean setTnScanningSupport(boolean reset, boolean concurrentTnScanningSupported,
        boolean tnScanningDuringSatelliteSessionAllowed) {
        if (mSatelliteSessionController == null) {
            ploge("setTnScanningSupport: mSatelliteSessionController is not initialized yet");
            return false;
        }
        return mSatelliteSessionController.setTnScanningSupport(reset,
                concurrentTnScanningSupported, tnScanningDuringSatelliteSessionAllowed);
    }

    /**
     * This API can be used by only CTS to control ingoring cellular service state event.
     *
     * @param enabled Whether to enable boolean config.
     * @return {@code true} if the value is set successfully, {@code false} otherwise.
     */
    public boolean setSatelliteIgnoreCellularServiceState(boolean enabled) {
        plogd("setSatelliteIgnoreCellularServiceState - " + enabled);
        if (mSatelliteSessionController == null) {
            ploge("setSatelliteIgnoreCellularServiceState is not initialized yet");
            return false;
        }
        return mSatelliteSessionController.setSatelliteIgnoreCellularServiceState(enabled);
    }

    /**
     * This API can be used by only CTS to control the feature
     * {@code config_support_disable_satellite_while_enable_in_progress}.
     *
     * @param reset Whether to reset the override.
     * @param supported Whether to support the feature.
     * @return {@code true} if the value is set successfully, {@code false} otherwise.
     */
    public boolean setSupportDisableSatelliteWhileEnableInProgress(
        boolean reset, boolean supported) {
        if (!isMockModemAllowed()) {
            plogd("setSupportDisableSatelliteWhileEnableInProgress: mock modem not allowed");
            return false;
        }

        plogd("setSupportDisableSatelliteWhileEnableInProgress - reset=" + reset
                  + ", supported=" + supported);
        if (reset) {
            mOverriddenDisableSatelliteWhileEnableInProgressSupported = null;
        } else {
            mOverriddenDisableSatelliteWhileEnableInProgressSupported =
                    new AtomicBoolean(supported);
        }
        return true;
    }

    /**
     * This API can be used by only CTS to control the max allowed data mode.
     *
     * @param maxAllowedDataMode The max allowed data mode.
     * @return {@code true} if the value is set successfully, {@code false} otherwise.
     */
    public boolean setMaxAllowedDataModeForCtsTest(int maxAllowedDataMode) {
        if (!isMockModemAllowed()) {
            plogd("setMaxAllowedDataModeForCtsTest: mock modem not allowed");
            return false;
        }

        plogd("setMaxAllowedDataModeForCtsTest - maxAllowedDataMode=" + maxAllowedDataMode);
        mMaxAllowedDataModeForCtsTest.set(maxAllowedDataMode);
        return true;
    }

    /**
     * This API can be used for testing purposes to uncap the max allowed data mode.
     *
     * @return {@code true} if the max allowed data mode is uncapped successfully,
     * {@code false} otherwise.
     */
    public boolean uncapMaxAllowedDataMode() {
        if (!DEBUG) {
            plogd("uncapMaxAllowedDataMode: Cannot uncap max allowed data mode on non debug"
                    + " builds");
            return false;
        }
        plogd("uncapMaxAllowedDataMode: Uncapping max allowed data mode");
        mUncapMaxAllowedDataMode.set(true);
        return true;
    }

    /**
     * This API can be used by only CTS to override timeout durations used by DatagramController
     * module.
     *
     * @param timeoutMillis The timeout duration in millisecond.
     * @return {@code true} if the timeout duration is set successfully, {@code false} otherwise.
     */
    public boolean setDatagramControllerTimeoutDuration(
            boolean reset, int timeoutType, long timeoutMillis) {
        plogd("setDatagramControllerTimeoutDuration: reset=" + reset + ", timeoutType="
                + timeoutType + ", timeoutMillis=" + timeoutMillis);
        return mDatagramController.setDatagramControllerTimeoutDuration(
                reset, timeoutType, timeoutMillis);
    }

    /**
     * This API can be used by only CTS to override the boolean configs used by the
     * DatagramController module.
     *
     * @param enable Whether to enable or disable boolean config.
     * @return {@code true} if the boolean config is set successfully, {@code false} otherwise.
     */
    public boolean setDatagramControllerBooleanConfig(
            boolean reset, int booleanType, boolean enable) {
        logd("setDatagramControllerBooleanConfig: reset=" + reset + ", booleanType="
                + booleanType + ", enable=" + enable);
        return mDatagramController.setDatagramControllerBooleanConfig(
                reset, booleanType, enable);
    }

    /**
     * This API can be used by only CTS to override timeout durations used by SatelliteController
     * module.
     *
     * @param timeoutMillis The timeout duration in millisecond.
     * @return {@code true} if the timeout duration is set successfully, {@code false} otherwise.
     */
    public boolean setSatelliteControllerTimeoutDuration(
            boolean reset, int timeoutType, long timeoutMillis) {
        if (!isMockModemAllowed()) {
            plogd("setSatelliteControllerTimeoutDuration: mock modem is not allowed");
            return false;
        }
        plogd("setSatelliteControllerTimeoutDuration: reset=" + reset + ", timeoutType="
                + timeoutType + ", timeoutMillis=" + timeoutMillis);
        if (timeoutType == TIMEOUT_TYPE_WAIT_FOR_SATELLITE_ENABLING_RESPONSE) {
            if (reset) {
                mWaitTimeForSatelliteEnablingResponse.set(
                        getWaitForSatelliteEnablingResponseTimeoutMillis());
            } else {
                mWaitTimeForSatelliteEnablingResponse.set(timeoutMillis);
            }
            plogd("mWaitTimeForSatelliteEnablingResponse="
                    + mWaitTimeForSatelliteEnablingResponse.get());
        } else if (timeoutType == TIMEOUT_TYPE_DEMO_POINTING_ALIGNED_DURATION_MILLIS) {
            if (reset) {
                mDemoPointingAlignedDurationMillis.set(
                        getDemoPointingAlignedDurationMillisFromResources());
            } else {
                mDemoPointingAlignedDurationMillis.set(timeoutMillis);
            }
        } else if (timeoutType == TIMEOUT_TYPE_DEMO_POINTING_NOT_ALIGNED_DURATION_MILLIS) {
            if (reset) {
                mDemoPointingNotAlignedDurationMillis.set(
                        getDemoPointingNotAlignedDurationMillisFromResources());
            } else {
                mDemoPointingNotAlignedDurationMillis.set(timeoutMillis);
            }
        } else if (timeoutType
                == TIMEOUT_TYPE_EVALUATE_ESOS_PROFILES_PRIORITIZATION_DURATION_MILLIS) {
            if (reset) {
                mEvaluateEsosProfilesPrioritizationDurationMillis.set(
                        getEvaluateEsosProfilesPrioritizationDurationMillis());
            } else {
                mEvaluateEsosProfilesPrioritizationDurationMillis.set(timeoutMillis);
            }
        } else if (timeoutType == TIMEOUT_TYPE_EMERGENCY_CALL_MONITORING_DURATION_MILLIS) {
            if (reset) {
                mEmergencyCallMonitoringDurationMillisForCtsTest.set(0);
            } else {
                mEmergencyCallMonitoringDurationMillisForCtsTest.set(timeoutMillis);
            }
        } else if (timeoutType == TIMEOUT_TYPE_LAST_EMERGENCY_CALL_TIME) {
            if (reset) {
                mLastEmergencyCallTime.set(0);
            } else {
                mLastEmergencyCallTime.set(timeoutMillis);
            }
        } else {
            plogw("Invalid timeoutType=" + timeoutType);
            return false;
        }
        return true;
    }

    /**
     * This API can be used by only CTS to update satellite gateway service package name.
     *
     * @param servicePackageName The package name of the satellite gateway service.
     * @return {@code true} if the satellite gateway service is set successfully,
     * {@code false} otherwise.
     */
    public boolean setSatelliteGatewayServicePackageName(@Nullable String servicePackageName) {
        if (!isMockModemAllowed()) {
            plogd("setSatelliteGatewayServicePackageName: mock modem is not allowed");
            return false;
        }
        if (mSatelliteSessionController == null) {
            ploge("mSatelliteSessionController is not initialized yet");
            return false;
        }

        if (servicePackageName == null || servicePackageName.equals("null")) {
            mOverriddenSatelliteGatewayServicePackageName = null;
        } else {
            mOverriddenSatelliteGatewayServicePackageName = servicePackageName;
        }
        plogd("setSatelliteGatewayServicePackageName: "
                + "mOverriddenSatelliteGatewayServicePackageName="
                + mOverriddenSatelliteGatewayServicePackageName);

        return mSatelliteSessionController.setSatelliteGatewayServicePackageName(
                servicePackageName);
    }

    /**
     * This API can be used by only CTS to update satellite pointing UI app package and class names.
     *
     * @param packageName The package name of the satellite pointing UI app.
     * @param className The class name of the satellite pointing UI app.
     * @return {@code true} if the satellite pointing UI app package and class is set successfully,
     * {@code false} otherwise.
     */
    public boolean setSatellitePointingUiClassName(
            @Nullable String packageName, @Nullable String className) {
        return mPointingAppController.setSatellitePointingUiClassName(packageName, className);
    }

    /**
     * This API can be used in only testing to override connectivity status in monitoring emergency
     * calls and sending EVENT_DISPLAY_EMERGENCY_MESSAGE to Dialer.
     *
     * @param handoverType The type of handover from emergency call to satellite messaging. Use one
     *                     of the following values to enable the override:
     *                     0 - EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_SOS
     *                     1 - EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_T911
     *                     To disable the override, use -1 for handoverType.
     * @param delaySeconds The event EVENT_DISPLAY_EMERGENCY_MESSAGE will be sent to Dialer
     *                     delaySeconds after the emergency call starts.
     * @param simSlotId The SIM slot ID to use for loading T911 conversation thread.
     * @return {@code true} if the handover type is set successfully, {@code false} otherwise.
     */
    public boolean setEmergencyCallToSatelliteHandoverType(
        int handoverType, int delaySeconds, int simSlotId) {
        if (!isMockModemAllowed()) {
            ploge("setEmergencyCallToSatelliteHandoverType: mock modem not allowed");
            return false;
        }
        if (isHandoverTypeValid(handoverType)) {
            mEnforcedEmergencyCallToSatelliteHandoverType.set(handoverType);
            mDelayInSendingEventDisplayEmergencyMessage.set(delaySeconds > 0 ? delaySeconds : 0);
            mSimSlotIdForLaunchingT911ConversationThread.set(simSlotId);
        } else {
            mEnforcedEmergencyCallToSatelliteHandoverType.set(
                    INVALID_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE);
            mDelayInSendingEventDisplayEmergencyMessage.set(0);
            mSimSlotIdForLaunchingT911ConversationThread.set(0);
        }
        return true;
    }

    /**
     * This API can be used in only testing to override oem-enabled satellite provision status.
     *
     * @param reset {@code true} mean the overriding status should not be used, {@code false}
     *              otherwise.
     * @param isProvisioned The overriding provision status.
     * @return {@code true} if the provision status is set successfully, {@code false} otherwise.
     */
    public boolean setOemEnabledSatelliteProvisionStatus(boolean reset, boolean isProvisioned) {
        if (!isMockModemAllowed()) {
            ploge("setOemEnabledSatelliteProvisionStatus: mock modem not allowed");
            return false;
        }
        if (reset) {
            mOverriddenIsSatelliteViaOemProvisioned = null;
        } else {
            mOverriddenIsSatelliteViaOemProvisioned = new AtomicBoolean(isProvisioned);
        }
        return true;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected int getEnforcedEmergencyCallToSatelliteHandoverType() {
        return mEnforcedEmergencyCallToSatelliteHandoverType.get();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected int getDelayInSendingEventDisplayEmergencyMessage() {
        return mDelayInSendingEventDisplayEmergencyMessage.get();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected int getSimSlotIdForLaunchingT911ConversationThread() {
        return mSimSlotIdForLaunchingT911ConversationThread.get();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected long getEmergencyCallMonitoringDurationMillisForCtsTests() {
        return mEmergencyCallMonitoringDurationMillisForCtsTest.get();
    }

    private boolean isHandoverTypeValid(int handoverType) {
        if (handoverType == EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_SOS
                || handoverType == EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_T911) {
            return true;
        }
        return false;
    }

    /**
     * This function is used by {@link SatelliteModemInterface} to notify
     * {@link SatelliteController} that the satellite vendor service was just connected.
     * <p>
     * {@link SatelliteController} will send requests to satellite modem to check whether it support
     * satellite and whether it is provisioned. {@link SatelliteController} will use these cached
     * values to serve requests from its clients.
     * <p>
     * Because satellite vendor service might have just come back from a crash, we need to disable
     * the satellite modem so that resources will be cleaned up and internal states will be reset.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void onSatelliteServiceConnected() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            plogd("onSatelliteServiceConnected");
            // Vendor service might have just come back from a crash
            moveSatelliteToOffStateAndCleanUpResources(SATELLITE_RESULT_MODEM_ERROR);
            final String caller = "SC:onSatelliteServiceConnected";
            ResultReceiver receiver = new ResultReceiver(this) {
                @Override
                protected void onReceiveResult(
                        int resultCode, Bundle resultData) {
                    decrementResultReceiverCount(caller);
                    plogd("onSatelliteServiceConnected.requestIsSatelliteSupported:"
                            + " resultCode=" + resultCode);
                }
            };
            requestIsSatelliteSupported(receiver);
            incrementResultReceiverCount(caller);
        } else {
            plogd("onSatelliteServiceConnected: Satellite vendor service is not supported."
                    + " Ignored the event");
        }
    }

    /**
     * This function is used by {@link com.android.internal.telephony.ServiceStateTracker} to notify
     * {@link SatelliteController} that it has received a request to power on or off the cellular
     * radio modem.
     *
     * @param powerOn {@code true} means cellular radio is about to be powered on, {@code false}
     *                 means cellular modem is about to be powered off.
     */
    public void onSetCellularRadioPowerStateRequested(boolean powerOn) {
        logd("onSetCellularRadioPowerStateRequested: powerOn=" + powerOn);

        mRadioOffRequested.set(!powerOn);
        if (powerOn) {
            stopWaitForCellularModemOffTimer();
        } else {
            requestSatelliteEnabled(
                    false /* enableSatellite */, false /* enableDemoMode */,
                    false /* isEmergency */,
                    new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int result) {
                            plogd("onSetCellularRadioPowerStateRequested: requestSatelliteEnabled"
                                    + " result=" + result);
                        }
                    });
            startWaitForCellularModemOffTimer();
        }
    }

    /**
     * This function is used by {@link com.android.internal.telephony.ServiceStateTracker} to notify
     * {@link SatelliteController} that the request to power off the cellular radio modem has
     * failed.
     */
    public void onPowerOffCellularRadioFailed() {
        logd("onPowerOffCellularRadioFailed");
        mRadioOffRequested.set(false);
        stopWaitForCellularModemOffTimer();
    }

    /**
     * Notify SMS received.
     *
     * @param subId The subId of the subscription used to receive SMS
     */
    public void onSmsReceived(int subId) {
        if (!isSatelliteEnabled()) {
            logd("onSmsReceived: satellite is not enabled");
            return;
        }

        int satelliteSubId = getSelectedSatelliteSubId();
        if (subId != satelliteSubId) {
            logd("onSmsReceived: SMS received " + subId
                    + ", but not satellite subscription " + satelliteSubId);
            return;
        }

        if (mDatagramController != null) {
            mDatagramController.onSmsReceived(subId);
        } else {
            logd("onSmsReceived: DatagramController is not initialized");
        }

        mControllerMetricsStats.reportIncomingNtnSmsCount(
                SatelliteManager.SATELLITE_RESULT_SUCCESS);
    }

    /**
     * @return {@code true} if satellite is supported via OEM on the device,
     * {@code  false} otherwise.
     */
    public boolean isSatelliteSupportedViaOem() {
        Boolean supported = isSatelliteSupportedViaOemInternal();
        return (supported != null ? supported : false);
    }

    /**
     * @param subId Subscription ID.
     * @return The list of satellite PLMNs used for connecting to satellite networks.
     */
    @NonNull
    public List<String> getSatellitePlmnsForCarrier(int subId) {
        if (!isSatelliteSupportedViaCarrier(subId)) {
            logd("Satellite for carrier is not supported.");
            return new ArrayList<>();
        }

        return getCarrierPlmnList(subId);
    }

    private int getCarrierRoamingNtnConnectTypeForPlmn(int subId, String plmn) {
        plogd("getCarrierRoamingNtnConnectTypeForPlmn: subId=" + subId + ", plmn=" + plmn);
        PersistableBundle perPlmnConfigs = getPersistableBundle(subId).getPersistableBundle(
            CarrierConfigManager.KEY_SATELLITE_CONFIGS_PER_PLMN_BUNDLE);
        if (perPlmnConfigs == null) {
            plogd("getCarrierRoamingNtnConnectTypeForPlmn: perPlmnConfigs is null");
            return CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_UNKNOWN;
        }
        PersistableBundle plmnSpecificConfig = perPlmnConfigs.getPersistableBundle(plmn);
        if (plmnSpecificConfig == null) {
            plogd("getCarrierRoamingNtnConnectTypeForPlmn: plmnSpecificConfig is null");
            return CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_UNKNOWN;
        }
        return plmnSpecificConfig.getInt(
                        CarrierConfigManager.KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT,
                        CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_UNKNOWN);
    }

    /**
     * @param subId Subscription ID.
     * @return The list of satellite PLMNs used for connecting to satellite networks.
     * <p>
     * <li>If the carrier roaming NTN connect type is
     * {@link CarrierConfigManager#CARRIER_ROAMING_NTN_CONNECT_MANUAL}, returns all carrier
     * satellite PLMNs.
     * <li>If the connect type is {@link CarrierConfigManager#CARRIER_ROAMING_NTN_CONNECT_HYBRID},
     * returns PLMNs that are configured as manual connect or support
     * {@link SatelliteManager#NT_RADIO_TECHNOLOGY_NB_IOT_NTN}.
     */
    @NonNull
    public List<String> getManualConnectSatellitePlmnsForCarrier(int subId) {
        List<String> result = new ArrayList<>();
        if (!mFeatureFlags.systemSelectionSpecifierEnhancement()) {
            logd("getManualConnectSatellitePlmnsForCarrier: system selection specifier enhancement"
                    + " is not enabled");
            return result;
        }
        List<String> allCarrierPlmns = getSatellitePlmnsForCarrier(subId);
        int carrierRoamingNtnConnectType = getCarrierRoamingNtnConnectType(subId);
        if (carrierRoamingNtnConnectType == CARRIER_ROAMING_NTN_CONNECT_MANUAL) {
            return allCarrierPlmns;
        } else if (carrierRoamingNtnConnectType == CARRIER_ROAMING_NTN_CONNECT_HYBRID) {
            for (String plmn : allCarrierPlmns) {
                if ((getCarrierRoamingNtnConnectTypeForPlmn(subId, plmn)
                        == CARRIER_ROAMING_NTN_CONNECT_MANUAL)
                        || getSupportedSatelliteTechnologies(subId, plmn).contains(
                        SatelliteManager.NT_RADIO_TECHNOLOGY_NB_IOT_NTN)) {
                    result.add(plmn);
                }
            }
        }
        return result;
    }

    /**
     *  checks if data service is allowed, to add part of list of services supported by satellite
     *  plmn, when data supported mode
     *  {@link CarrierConfigManager#KEY_SATELLITE_DATA_SUPPORT_MODE_INT} is restricted mode and no
     *  data service is included at allowed service info at the entitlement and when allowed service
     *  info field is present at the entitlement.
     *
     * @param subId subscription id
     * @param plmn  The satellite plmn
     * @param allowedServiceValues allowed services info supported by entitlement
     * @return {@code true} is supports data service else {@code false}
     */
    private boolean isDataServiceUpdateRequired(int subId, String plmn,
            List<Integer> allowedServiceValues) {
        if (!allowedServiceValues.contains(NetworkRegistrationInfo.SERVICE_TYPE_DATA)
                && getCarrierSatelliteDataSupportedModeFromConfig(subId)
                == CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED) {
            return getSatelliteSupportedServicesFromConfig(subId, plmn)
                    .contains(NetworkRegistrationInfo.SERVICE_TYPE_DATA);
        }
        return false;
    }

    /**
     *  checks if mms service is allowed, to add part of list of services supported by satellite
     *  plmn, when no mms service is included at allowed services
     *
     * @param subId subscription id
     * @param plmn  The satellite plmn
     * @param allowedServiceValues allowed services info supported by entitlement
     * @return {@code true} is supports data service else {@code false}
     */
    private boolean isMmsServiceUpdateRequired(int subId, String plmn,
            List<Integer> allowedServiceValues) {
        if (!allowedServiceValues.contains(NetworkRegistrationInfo.SERVICE_TYPE_MMS)) {
            return getSatelliteSupportedServicesFromConfig(subId, plmn)
                    .contains(NetworkRegistrationInfo.SERVICE_TYPE_MMS);
        }
        return false;
    }

    /**
     * Gives the list of satellite services associated with
     * {@link CarrierConfigManager#KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE}.
     * Note: If this config not found, fallback to
     * {@link CarrierConfigManager#KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY}.
     *
     * @param subId subsctiption id
     * @param plmn The satellite plmn
     * @return The list of services supported by the carrier associated with the
     */
    private List<Integer> getSatelliteSupportedServicesFromConfig(int subId, String plmn) {
        if (plmn != null && !plmn.isEmpty()) {
            if (mSatelliteServicesSupportedByCarriersFromConfig.containsKey(subId)) {
                Map<String, Set<Integer>> supportedServices =
                        mSatelliteServicesSupportedByCarriersFromConfig.get(subId);
                if (supportedServices != null && supportedServices.containsKey(plmn)) {
                    logd("getSupportedSatelliteServices: returning supported services from config "
                            + supportedServices.get(plmn));
                    return new ArrayList<>(supportedServices.get(plmn));
                } else {
                    loge("getSupportedSatelliteServices: subId=" + subId
                            + ", supportedServices "
                            + "does not contain key plmn=" + plmn);
                }
            } else {
                loge("getSupportedSatelliteServices: "
                        + "mSatelliteServicesSupportedByCarriersFromConfig does not contain"
                        + " key subId=" + subId);
            }
        }

        /* Returns default capabilities when carrier config does not contain service capabilities
         for the given plmn */
        PersistableBundle config = getPersistableBundle(subId);
        int [] defaultCapabilities = config.getIntArray(
                KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY);
        if (defaultCapabilities == null) {
            logd("getSupportedSatelliteServices: defaultCapabilities is null");
            return new ArrayList<>();
        }
        List<Integer> capabilitiesList = Arrays.stream(
                defaultCapabilities).boxed().collect(Collectors.toList());
        logd("getSupportedSatelliteServices: subId=" + subId
                + ", supportedServices does not contain key plmn=" + plmn
                + ", return default values " + capabilitiesList);
        return capabilitiesList;
    }

    /**
     * @param subId Subscription ID.
     * @param plmn The satellite plmn.
     * @return The list of services supported by the carrier associated with the {@code subId} for
     * the satellite network {@code plmn}. Returns empty list at invalid sub id.
     *
     */
    @NonNull
    public List<Integer> getSupportedSatelliteServicesForPlmn(int subId, String plmn) {

        if (!isValidSubscriptionId(subId)) {
            logd("getSupportedSatelliteServices: invalid sub id");
            return new ArrayList<>();
        }
        if (plmn != null && !plmn.isEmpty()) {
            Map<String, List<Integer>> allowedServicesList =
                    mEntitlementServiceTypeMapPerCarrier.get(subId);
            if (allowedServicesList != null && allowedServicesList.containsKey(plmn)) {
                List<Integer> allowedServiceValues = new ArrayList<>(
                        allowedServicesList.get(plmn));
                if (allowedServiceValues != null && !allowedServiceValues.isEmpty()) {
                    if (isDataServiceUpdateRequired(subId, plmn, allowedServiceValues)) {
                        logd("getSupportedSatelliteServices: data service added to satellite"
                                + " plmn");
                        allowedServiceValues.add(NetworkRegistrationInfo.SERVICE_TYPE_DATA);
                    }
                    if (allowedServiceValues.contains(NetworkRegistrationInfo.SERVICE_TYPE_DATA)
                            && isMmsServiceUpdateRequired(subId, plmn, allowedServiceValues)) {
                        allowedServiceValues.add(NetworkRegistrationInfo.SERVICE_TYPE_MMS);
                    }
                    logd("getSupportedSatelliteServices: allowed services=" + allowedServiceValues);
                    return allowedServiceValues;
                }
            }
        }

        return getSatelliteSupportedServicesFromConfig(subId, plmn);
    }

    /**
     * Check whether satellite modem has to attach to a satellite network before sending/receiving
     * datagrams.
     *
     * @return {@code true} if satellite attach is required, {@code false} otherwise.
     */
    public boolean isSatelliteAttachRequired() {
        SatelliteCapabilities satelliteCapabilities = getSatelliteCapabilities();
        if (satelliteCapabilities == null) {
            ploge("isSatelliteAttachRequired: mSatelliteCapabilities is null");
            return false;
        }
        if (satelliteCapabilities.getSupportedRadioTechnologies().contains(
                SatelliteManager.NT_RADIO_TECHNOLOGY_NB_IOT_NTN)) {
            return true;
        }
        return false;
    }

    /**
     * @return {@code true} if satellite is supported via carrier by any subscription on the device,
     * {@code false} otherwise.
     */
    public boolean isSatelliteSupportedViaCarrier() {
        for (Phone phone : PhoneFactory.getPhones()) {
            if (isSatelliteSupportedViaCarrier(phone.getSubId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} if satellite emergency messaging is supported via carrier by any
     * subscription on the device, {@code false} otherwise.
     */
    public boolean isSatelliteEmergencyMessagingSupportedViaCarrier() {
        for (Phone phone : PhoneFactory.getPhones()) {
            if (isSatelliteEmergencyMessagingSupportedViaCarrier(phone.getSubId())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSatelliteEmergencyMessagingSupportedViaCarrier(int subId) {
        if (!isSatelliteSupportedViaCarrier(subId)) {
            return false;
        }
        PersistableBundle config = getPersistableBundle(subId);
        if (!config.getBoolean(KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL)) {
            return false;
        }

        return isSatelliteEmergencyMessagingProviderSupportedInCurrentRegion(subId);
    }

    /**
     * @return {@code Pair<true, subscription ID>} if any subscription on the device is connected to
     * satellite, {@code Pair<false, null>} otherwise.
     */
    public Pair<Boolean, Integer> isUsingNonTerrestrialNetworkViaCarrier() {
        for (Phone phone : PhoneFactory.getPhones()) {
            ServiceState serviceState = phone.getServiceState();
            if (serviceState != null && serviceState.isUsingNonTerrestrialNetwork()) {
                logd("isUsingNonTerrestrialNetworkViaCarrier: " + phone.getSubId() + " using ntn "
                        + "via carrier");
                return new Pair<>(true, phone.getSubId());
            }
        }
        return new Pair<>(false, null);
    }

    /**
     * @return {@code true} and the corresponding subId if the device is connected to
     * satellite via any carrier within the
     * {@link CarrierConfigManager#KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT}
     * duration, {@code false} and null otherwise.
     */
    public Pair<Boolean, Integer> isSatelliteConnectedViaCarrierWithinHysteresisTime() {
        Pair<Boolean, Integer> ntnConnectedState = isUsingNonTerrestrialNetworkViaCarrier();
        if (ntnConnectedState.first) {
            return ntnConnectedState;
        }
        for (Phone phone : PhoneFactory.getPhones()) {
            if (isInSatelliteModeForCarrierRoaming(phone)) {
                logd("isSatelliteConnectedViaCarrierWithinHysteresisTime: "
                        + "subId:" + phone.getSubId()
                        + " is connected to satellite within hysteresis time");
                return new Pair<>(true, phone.getSubId());
            }
        }
        return new Pair<>(false, null);
    }

    /**
     * Get whether device is connected to satellite via carrier, either manually or automatically.
     * In case of automatic connection, it checks if the device is connected to satellite within the
     * {@link CarrierConfigManager#KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT} duration, {@code
     * false} otherwise.
     *
     * @param phone phone object
     * @return {@code true} if the device is connected to satellite using the phone within the
     *     {@link CarrierConfigManager#KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT} duration, {@code
     *     false} otherwise.
     */
    public boolean isInSatelliteModeForCarrierRoaming(@Nullable Phone phone) {
        if (phone == null) {
            return false;
        }

        int subId = phone.getSubId();
        int connectType = getCarrierRoamingNtnConnectType(subId);
        if (connectType == CARRIER_ROAMING_NTN_CONNECT_MANUAL
                || (mFeatureFlags.vzwAstSkyloFallback()
                        && connectType == CARRIER_ROAMING_NTN_CONNECT_HYBRID)) {
            return isInCarrierRoamingNbIotNtn(phone);
        }

        if (!isSatelliteSupportedViaCarrier(subId)) {
            return false;
        }

        ServiceState serviceState = phone.getServiceState();
        if (serviceState == null) {
            return false;
        }

        if (serviceState.isUsingNonTerrestrialNetwork()) {
            return true;
        }

        if (getWwanIsInService(serviceState)
                || serviceState.getState() == ServiceState.STATE_POWER_OFF) {
            // Device is connected to terrestrial network which has coverage or radio is turned off
            resetCarrierRoamingSatelliteModeParams(subId);
            return false;
        }

        Long lastDisconnectedTime = mLastSatelliteDisconnectedTimesMillis.get(subId);
        long satelliteConnectionHysteresisTime =
                getSatelliteConnectionHysteresisTimeMillis(subId);
        if (lastDisconnectedTime != null
                && (getElapsedRealtime() - lastDisconnectedTime)
                <= satelliteConnectionHysteresisTime) {
            logd("isInSatelliteModeForCarrierRoaming: " + "subId:" + subId
                    + " is connected to satellite within hysteresis time");
            return true;
        } else {
            logd(
                    "isInSatelliteModeForCarrierRoaming: "
                            + "subId:"
                            + subId
                            + " is not connected to satellite within hysteresis time."
                            + " Resetting carrier roaming satellite mode params");
            resetCarrierRoamingSatelliteModeParams(subId);
            return false;
        }
    }

    /**
     * @return {@code true} if should exit satellite mode unless already sent a datagram in this
     * esos session.
     */
    public boolean shouldTurnOffCarrierSatelliteForEmergencyCall() {
        return !mDatagramController.isEmergencyCommunicationEstablished()
                && getConfigForSubId(getSelectedSatelliteSubId()).getBoolean(
                KEY_SATELLITE_ROAMING_TURN_OFF_SESSION_FOR_EMERGENCY_CALL_BOOL);
    }

    /**
     * Return whether the satellite request is for an emergency or not.
     *
     * @return {@code true} if the satellite request is for an emergency and
     *                      {@code false} otherwise.
     */
    public boolean getRequestIsEmergency() {
        return mIsEmergency.get();
    }

    /**
     * Checks if device is in manual carrier roaming nb iot ntn mode.
     *
     * @return {@code true} if device is in manual carrier roaming nb iot ntn mode, else {@return
     *     false}
     */
    public boolean isInCarrierRoamingNbIotNtn() {
        return isInCarrierRoamingNbIotNtn(getSatellitePhone());
    }

    /**
     * Checks if phone is in manual carrier roaming nb iot ntn mode.
     *
     * @return {@code true} if phone is in manual carrier roaming nb iot ntn mode, else {@return
     *     false}
     */
    public boolean isInCarrierRoamingNbIotNtn(@Nullable Phone phone) {
        if (!isSatelliteEnabled()) {
            plogd("isInCarrierRoamingNbIotNtn: satellite is disabled");
            return false;
        }

        if (phone == null) {
            plogd("isInCarrierRoamingNbIotNtn: phone is null");
            return false;
        }

        int subId = phone.getSubId();
        if (!isSatelliteSupportedViaCarrier(subId)) {
            plogd("isInCarrierRoamingNbIotNtn[phoneId=" + phone.getPhoneId()
                    + "]: satellite is not supported via carrier");
            return false;
        }

        int connectType = getCarrierRoamingNtnConnectType(subId);
        if (connectType != CARRIER_ROAMING_NTN_CONNECT_MANUAL
                && (mFeatureFlags.vzwAstSkyloFallback()
                        && connectType != CARRIER_ROAMING_NTN_CONNECT_HYBRID)) {
            plogd(
                    "isInCarrierRoamingNbIotNtn[phoneId="
                            + phone.getPhoneId()
                            + "]: not manual "
                            + "nor hybrid connect. connectType = "
                            + connectType);
            return false;
        }

        if (subId != getSelectedSatelliteSubId()) {
            plogd("isInCarrierRoamingNbIotNtn: subId=" + subId
                    + " does not match satellite subId=" + getSelectedSatelliteSubId());
            return false;
        }

        plogd("isInCarrierRoamingNbIotNtn: carrier roaming ntn eligible for phone"
                  + " associated with subId " + phone.getSubId());
        return true;
    }

    /**
     * Return capabilities of carrier roaming satellite network.
     *
     * @param phone phone object
     * @return The list of services supported by the carrier associated with the {@code subId}
     */
    @NonNull
    public List<Integer> getCapabilitiesForCarrierRoamingSatelliteMode(Phone phone) {
        int subId = phone.getSubId();
        if (mSatModeCapabilitiesForCarrierRoaming.containsKey(subId)) {
            return mSatModeCapabilitiesForCarrierRoaming.get(subId);
        }

        return new ArrayList<>();
    }

    /**
     * Request to get the {@link SatelliteSessionStats} of the satellite service.
     *
     * @param subId The subId of the subscription to the satellite session stats for.
     * @param result The result receiver that returns the {@link SatelliteSessionStats}
     *               if the request is successful or an error code if the request failed.
     */
    public void requestSatelliteSessionStats(int subId, @NonNull ResultReceiver result) {
        mSessionMetricsStats.requestSatelliteSessionStats(subId, result);
    }

    /**
     * Get the carrier-enabled emergency call wait for connection timeout millis
     */
    public long getCarrierEmergencyCallWaitForConnectionTimeoutMillis() {
        long maxTimeoutMillis = 0;
        for (Phone phone : PhoneFactory.getPhones()) {
            if (!isSatelliteEmergencyMessagingSupportedViaCarrier(phone.getSubId())) {
                continue;
            }

            int timeoutMillis =
                    getCarrierEmergencyCallWaitForConnectionTimeoutMillis(phone.getSubId());
            // Prioritize getting the timeout duration from the phone that is in satellite mode
            // with carrier roaming
            if (isInSatelliteModeForCarrierRoaming(phone)) {
                return timeoutMillis;
            }
            if (maxTimeoutMillis < timeoutMillis) {
                maxTimeoutMillis = timeoutMillis;
            }
        }
        if (maxTimeoutMillis != 0) {
            return maxTimeoutMillis;
        }
        return DEFAULT_CARRIER_EMERGENCY_CALL_WAIT_FOR_CONNECTION_TIMEOUT_MILLIS;
    }

    public int getCarrierEmergencyCallWaitForConnectionTimeoutMillis(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        return config.getInt(KEY_EMERGENCY_CALL_TO_SATELLITE_T911_HANDOVER_TIMEOUT_MILLIS_INT);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected long getElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * Register the handler for SIM Refresh notifications.
     * @param handler Handler for notification message.
     * @param what User-defined message code.
     */
    public void registerIccRefresh(Handler handler, int what) {
        for (Phone phone : PhoneFactory.getPhones()) {
            CommandsInterface ci = phone.mCi;
            ci.registerForIccRefresh(handler, what, null);
        }
    }

    /**
     * Unregister the handler for SIM Refresh notifications.
     * @param handler Handler for notification message.
     */
    public void unRegisterIccRefresh(Handler handler) {
        for (Phone phone : PhoneFactory.getPhones()) {
            CommandsInterface ci = phone.mCi;
            ci.unregisterForIccRefresh(handler);
        }
    }

    /**
     * To use the satellite service, update the EntitlementStatus, PlmnAllowedList, barred plmn list
     * data plan, service type, data service policy and voice service policy after receiving the
     * satellite configuration from the entitlement server. If satellite entitlement is enabled,
     * enable satellite for the carrier. Otherwise, disable satellite.
     *
     * @param subId                     Subscription ID
     * @param entitlementEnabled        {@code true} enables Satellite entitlement service.
     * @param allowedPlmnList           Plmn [MCC+MNC] list of codes to determine if satellite
     *                                  communication is allowed. Ex : "123123,12310".
     * @param barredPlmnList            Plmn [MCC+MNC] list of codes to determine if satellite
     *                                  communication is not allowed.  Ex : "123123,12310".
     * @param plmnDataPlanMap           data plan per plmn of type {@link SatelliteDataPlan}.
     *                                  Ex : {"302820":0, "31026":1}.
     * @param plmnServiceTypeMap        list of supported services per plmn of type
     *                                  {@link NetworkRegistrationInfo.ServiceType}).
     *                                  Ex : {"302820":[1,3],"31026":[2,3]}.
     * @param plmnDataServicePolicyMap  data support mode per plmn map of types
     *                                  {@link CarrierConfigManager.SATELLITE_DATA_SUPPORT_MODE}.
     *                                  Ex : {"302820":2, "31026":1}.
     * @param plmnVoiceServicePolicyMap voice support mode per plmn map of types
     *                                  {@link CarrierConfigManager.SATELLITE_DATA_SUPPORT_MODE}.
     *                                  Ex : {"302820":2, "31026":1}.
     * @param callback                  callback for accept.
     */
    public void onSatelliteEntitlementStatusUpdated(int subId, boolean entitlementEnabled,
            @Nullable List<String> allowedPlmnList, @Nullable List<String> barredPlmnList,
            @Nullable Map<String, Integer> plmnDataPlanMap,
            @Nullable Map<String, List<Integer>> plmnServiceTypeMap,
            @Nullable Map<String, Integer> plmnDataServicePolicyMap,
            @Nullable Map<String, Integer> plmnVoiceServicePolicyMap,
            @Nullable IIntegerConsumer callback) {
        plogd("onSatelliteEntitlementStatusUpdated: subId=" + subId
                + ", entitlementEnabled=" + entitlementEnabled);
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = subId;
            args.argi2 = entitlementEnabled ? TRUE : FALSE;
            args.arg1 = allowedPlmnList;
            args.arg2 = barredPlmnList;
            args.arg3 = plmnDataPlanMap;
            args.arg4 = plmnServiceTypeMap;
            args.arg5 = plmnDataServicePolicyMap;
            args.arg6 = plmnVoiceServicePolicyMap;
            args.arg7 = callback;
            sendMessage(obtainMessage(EVENT_SATELLITE_ENTILEMENT_STATUS_UPDATED, args));
            return;
        }

        handleSatelliteEntitlementStatusUpdated(subId, entitlementEnabled, allowedPlmnList,
                barredPlmnList, plmnDataPlanMap, plmnServiceTypeMap, plmnDataServicePolicyMap,
                plmnVoiceServicePolicyMap, callback);
    }

    private void handleSatelliteEntitlementStatusUpdated(int subId, boolean entitlementEnabled,
            @Nullable List<String> allowedPlmnList, @Nullable List<String> barredPlmnList,
            @Nullable Map<String, Integer> plmnDataPlanMap,
            @Nullable Map<String, List<Integer>> plmnServiceTypeMap,
            @Nullable Map<String, Integer> plmnDataServicePolicyMap,
            @Nullable Map<String, Integer> plmnVoiceServicePolicyMap,
            @Nullable IIntegerConsumer callback) {
        if (callback == null) {
            callback = new IIntegerConsumer.Stub() {
                @Override
                public void accept(int result) {
                    logd("updateSatelliteEntitlementStatus:" + result);
                }
            };
        }
        if (allowedPlmnList == null) {
            allowedPlmnList = new ArrayList<>();
        }
        if (barredPlmnList == null) {
            barredPlmnList = new ArrayList<>();
        }
        if (plmnDataPlanMap == null) {
            plmnDataPlanMap = new HashMap<>();
        }
        if (plmnServiceTypeMap == null) {
            plmnServiceTypeMap = new HashMap<>();
        }
        if (plmnDataServicePolicyMap == null) {
            plmnDataServicePolicyMap = new HashMap<>();
        }
        if (plmnVoiceServicePolicyMap == null) {
            plmnVoiceServicePolicyMap = new HashMap<>();
        }
        logd("handleSatelliteEntitlementStatusUpdated: "
                + "subId=" + subId + ", entitlementEnabled="
                + entitlementEnabled + ", allowedPlmnList=["
                + String.join(",", allowedPlmnList) + "]" + ", barredPlmnList=["
                + String.join(",", barredPlmnList) + "]"
                + ", plmnDataPlanMap =" + plmnDataPlanMap.toString()
                + ", plmnServiceTypeMap =" + plmnServiceTypeMap.toString()
                + ", plmnDataServicePolicyMap=" + plmnDataServicePolicyMap.toString()
                + ", plmnVoiceServicePolicyMap=" + plmnVoiceServicePolicyMap.toString());

        if (mSatelliteEntitlementStatusPerCarrier.computeIfAbsent(
                subId, k -> false) != entitlementEnabled) {
            logd("update the carrier satellite enabled to " + entitlementEnabled);
            handleIndividualEntitlementMetricReport(subId, entitlementEnabled);
            try {
                mSubscriptionManagerService.setSubscriptionProperty(subId,
                        SATELLITE_ENTITLEMENT_STATUS, entitlementEnabled ? "1" : "0");
            } catch (IllegalArgumentException | SecurityException e) {
                loge("handleSatelliteEntitlementStatusUpdated: setSubscriptionProperty, e=" + e);
            }
        }

        if (isValidPlmnList(allowedPlmnList) && isValidPlmnList(barredPlmnList)) {
            mMergedPlmnListPerCarrier.remove(subId);
            mEntitlementPlmnListPerCarrier.put(subId, allowedPlmnList);
            mEntitlementBarredPlmnListPerCarrier.put(subId, barredPlmnList);
            mEntitlementDataPlanMapPerCarrier.put(subId, plmnDataPlanMap);
            mEntitlementDataServicePolicyMapPerCarrier.put(subId, plmnDataServicePolicyMap);
            mEntitlementVoiceServicePolicyMapPerCarrier.put(subId, plmnVoiceServicePolicyMap);
            updateAndNotifyChangesInCarrierRoamingNtnAvailableServices(subId,
                    plmnServiceTypeMap);
            updatePlmnListPerCarrier(subId);

            configureSatellitePlmnForCarrier(subId);
            evaluateEnablingSatelliteForCarrier(subId,
                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER, null);
            mSubscriptionManagerService.setSatelliteEntitlementInfo(subId, allowedPlmnList,
                    barredPlmnList, plmnDataPlanMap, plmnServiceTypeMap,
                    plmnDataServicePolicyMap, plmnVoiceServicePolicyMap);
        } else {
            loge("handleSatelliteEntitlementStatusUpdated: either invalid allowedPlmnList "
                    + "or invalid barredPlmnList");
        }

        if (mSatelliteEntitlementStatusPerCarrier.computeIfAbsent(subId, k -> false)) {
            removeAttachRestrictionForCarrier(subId,
                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT, callback);
        } else {
            addAttachRestrictionForCarrier(subId,
                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT, callback);
        }
    }

    /**
     * A list of PLMNs is considered valid if either the list is empty or all PLMNs in the list
     * are valid.
     */
    private boolean isValidPlmnList(@NonNull List<String> plmnList) {
        for (String plmn : plmnList) {
            if (!TelephonyUtils.isValidPlmn(plmn)) {
                ploge("Invalid PLMN = " + plmn);
                return false;
            }
        }
        return true;
    }

    /**
     * If we have not successfully queried the satellite modem for its satellite service support,
     * we will retry the query one more time. Otherwise, we will return the cached result.
     */
    private Boolean isSatelliteSupportedViaOemInternal() {
        Boolean isSatelliteSupported = getIsSatelliteSupported();
        if (isSatelliteSupported != null) {
            /* We have already successfully queried the satellite modem. */
            return isSatelliteSupported;
        }

        /**
         * We have not successfully checked whether the modem supports satellite service.
         * Thus, we need to retry it now.
         */
        requestIsSatelliteSupported(
                new ResultReceiver(this) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        decrementResultReceiverCount(
                                "SC:isSatelliteSupportedViaOemInternal");
                        plogd("isSatelliteSupportedViaOemInternal.requestIsSatelliteSupported:"
                                + " resultCode=" + resultCode);
                    }
                });
        incrementResultReceiverCount("SC:isSatelliteSupportedViaOemInternal");
        return null;
    }

    private void handleEventProvisionSatelliteServiceDone(
            @NonNull ProvisionSatelliteServiceArgument arg,
            @SatelliteManager.SatelliteResult int result) {
        plogd("handleEventProvisionSatelliteServiceDone: result="
                + result + ", subId=" + arg.subId);

        Consumer<Integer> callback = mSatelliteProvisionCallbacks.remove(arg.subId);
        if (callback == null) {
            ploge("handleEventProvisionSatelliteServiceDone: callback is null for subId="
                    + arg.subId);
            mProvisionMetricsStats
                    .setResultCode(SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE)
                    .setIsProvisionRequest(true)
                    .setCarrierId(getSatelliteCarrierId())
                    .setSupportedConnectionMode(getSupportedConnectTypeMetrics())
                    .setIsNtnOnlyCarrier(isNtnOnlyCarrier())
                    .reportProvisionMetrics();
            mControllerMetricsStats.reportProvisionCount(
                    SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE);
            return;
        }
        if (result == SATELLITE_RESULT_SUCCESS
                || result == SATELLITE_RESULT_REQUEST_NOT_SUPPORTED) {
            persistOemEnabledSatelliteProvisionStatus(true);
            callback.accept(SATELLITE_RESULT_SUCCESS);
            updateCachedDeviceProvisionStatus();
        } else {
            callback.accept(result);
        }
        mProvisionMetricsStats.setResultCode(result)
                .setIsProvisionRequest(true)
                .setCarrierId(getSatelliteCarrierId())
                .setSupportedConnectionMode(getSupportedConnectTypeMetrics())
                .setIsNtnOnlyCarrier(isNtnOnlyCarrier())
                .reportProvisionMetrics();
        mControllerMetricsStats.reportProvisionCount(result);
    }

    private void handleEventDeprovisionSatelliteServiceDone(
            @NonNull ProvisionSatelliteServiceArgument arg,
            @SatelliteManager.SatelliteResult int result) {
        if (arg == null) {
            ploge("handleEventDeprovisionSatelliteServiceDone: arg is null");
            return;
        }
        plogd("handleEventDeprovisionSatelliteServiceDone: result="
                + result + ", subId=" + arg.subId);

        if (result == SATELLITE_RESULT_SUCCESS
                || result == SATELLITE_RESULT_REQUEST_NOT_SUPPORTED) {
            persistOemEnabledSatelliteProvisionStatus(false);
            if (arg.callback != null) {
                arg.callback.accept(SATELLITE_RESULT_SUCCESS);
            }
            updateCachedDeviceProvisionStatus();
        } else if (arg.callback != null) {
            arg.callback.accept(result);
        }
        mProvisionMetricsStats.setResultCode(result)
                .setIsProvisionRequest(false)
                .setCarrierId(getSatelliteCarrierId())
                .setSupportedConnectionMode(getSupportedConnectTypeMetrics())
                .setIsNtnOnlyCarrier(isNtnOnlyCarrier())
                .reportProvisionMetrics();
        mControllerMetricsStats.reportDeprovisionCount(result);
    }

    private void handleStartSatelliteTransmissionUpdatesDone(@NonNull AsyncResult ar) {
        SatelliteControllerHandlerRequest request = (SatelliteControllerHandlerRequest) ar.userObj;
        SatelliteTransmissionUpdateArgument arg =
                (SatelliteTransmissionUpdateArgument) request.argument;
        int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                "handleStartSatelliteTransmissionUpdatesDone");
        arg.errorCallback.accept(errorCode);

        if (errorCode != SATELLITE_RESULT_SUCCESS) {
            mPointingAppController.setStartedSatelliteTransmissionUpdates(false);
            // We need to remove the callback from our listener list since the caller might not call
            // stopSatelliteTransmissionUpdates to unregister the callback in case of failure.
            Consumer<Integer> internalResult = new Consumer<Integer>() {
                @Override
                public void accept(Integer result) {
                    plogd("handleStartSatelliteTransmissionUpdatesDone: "
                            + "unregisterForSatelliteTransmissionUpdates result=" + result);
                }
            };
            mPointingAppController.unregisterForSatelliteTransmissionUpdates(arg.subId,
                    internalResult, arg.callback);
        } else {
            mPointingAppController.setStartedSatelliteTransmissionUpdates(true);
        }
    }

    /**
     * Posts the specified command to be executed on the main thread and returns immediately.
     *
     * @param command command to be executed on the main thread
     * @param argument additional parameters required to perform of the operation
     * @param phone phone object used to perform the operation.
     */
    private void sendRequestAsync(int command, @NonNull Object argument, @Nullable Phone phone) {
        SatelliteControllerHandlerRequest request = new SatelliteControllerHandlerRequest(
                argument, phone);
        Message msg = this.obtainMessage(command, request);
        msg.sendToTarget();
    }

    /**
     * Check if satellite is provisioned for the device.
     * @return {@code true} if device is provisioned for satellite else return {@code false}.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Nullable
    protected Boolean isDeviceProvisioned() {
        if (mOverriddenIsSatelliteViaOemProvisioned != null) {
            return mOverriddenIsSatelliteViaOemProvisioned.get();
        }

        if (mIsDeviceProvisioned == null) {
            mIsDeviceProvisioned = new AtomicBoolean(getPersistedDeviceProvisionStatus());
        }
        return mIsDeviceProvisioned.get();
    }

    private void handleSatelliteEnabled(SatelliteControllerHandlerRequest request) {
        RequestSatelliteEnabledArgument argument =
                (RequestSatelliteEnabledArgument) request.argument;
        handlePersistentLoggingOnSessionStart(argument);
        selectBindingSatelliteSubscription(false);
        SatelliteModemEnableRequestAttributes enableRequestAttributes =
                    createModemEnableRequest(argument);
        if (enableRequestAttributes == null) {
            plogw("handleSatelliteEnabled: enableRequestAttributes is null");
            sendErrorAndReportSessionMetrics(
                    SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE, argument.callback);
            if (argument.enableSatellite) {
                setSatelliteEnabledRequest(null);
            } else {
                setSatelliteDisabledRequest(null);
            }
            return;
        }

        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.onSatelliteEnablementStarted(argument.enableSatellite);
        } else {
            ploge("handleSatelliteEnabled: mSatelliteSessionController is not initialized yet");
        }

        /* Framework will send back the disable result to the requesting client only after receiving
         * both confirmation for the disable request from modem, and OFF state from modem if the
         * modem is not in OFF state.
         */
        if (!argument.enableSatellite && mSatelliteModemInterface.isSatelliteServiceSupported()) {
            mWaitingForDisableSatelliteModemResponse.set(true);
            if (!isSatelliteDisabled()) mWaitingForSatelliteModemOff.set(true);
        }

        Message onCompleted = obtainMessage(EVENT_SET_SATELLITE_ENABLED_DONE, request);
        mSatelliteModemInterface.requestSatelliteEnabled(
                enableRequestAttributes, onCompleted);
        startWaitForSatelliteEnablingResponseTimer(argument);
        // Logs satellite session timestamps for session metrics
        if (argument.enableSatellite) {
            mSessionStartTimeStamp.set(getElapsedRealtime());
        }
        mSessionProcessingTimeStamp.set(getElapsedRealtime());
    }

    /** Get the request attributes that modem needs to enable/disable satellite */
    @Nullable private SatelliteModemEnableRequestAttributes createModemEnableRequest(
            @NonNull RequestSatelliteEnabledArgument arg) {
        int subId = getSelectedSatelliteSubId();
        SubscriptionInfo subInfo = mSubscriptionManagerService.getSubscriptionInfo(subId);
        if (subInfo == null) {
            loge("createModemEnableRequest: no SubscriptionInfo found for subId=" + subId);
            return null;
        }
        String iccid = subInfo.getIccId();
        String apn = getNiddApnName(subId);
        return new SatelliteModemEnableRequestAttributes(
                arg.enableSatellite, arg.enableDemoMode, arg.isEmergency,
                new SatelliteSubscriptionInfo(iccid, apn));
    }

    @NonNull private String getNiddApnName(int subId) {
        if (SatelliteServiceUtils.isNtnOnlySubscriptionId(subId)) {
            String apn = mContext.getResources().getString(R.string.config_satellite_nidd_apn_name);
            if (!TextUtils.isEmpty(apn)) {
                return apn;
            }
        }
        return getConfigForSubId(subId).getString(KEY_SATELLITE_NIDD_APN_NAME_STRING, "");
    }

    private void handleRequestSatelliteAttachRestrictionForCarrierCmd(
            SatelliteControllerHandlerRequest request) {
        RequestHandleSatelliteAttachRestrictionForCarrierArgument argument =
                (RequestHandleSatelliteAttachRestrictionForCarrierArgument) request.argument;

        if (argument.reason == SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER) {
            if (!persistSatelliteAttachEnabledForCarrierSetting(argument.subId)) {
                argument.callback.accept(SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE);
                return;
            }
        }

        evaluateEnablingSatelliteForCarrier(argument.subId, argument.reason, argument.callback);
    }

    /**
     * Updates the satellite supported state. If the satellite supported state is already the same
     * as the new state, this method will be no-op and return {@code false}. Otherwise, it will
     * update the satellite supported state and return {@code true}.
     * @return {@code true} if the satellite supported state is updated, {@code false} otherwise
     */
    private boolean updateSatelliteSupportedState(boolean supported) {
        Boolean isSatelliteSupported = getIsSatelliteSupported();
        if (isSatelliteSupported != null && isSatelliteSupported == supported) {
            plogd("updateSatelliteSupportedState: current satellite support state and new "
                    + "supported state are matched, ignore update.");
            return false;
        }

        setIsSatelliteSupported(supported);
        mSatelliteSessionController = SatelliteSessionController.make(
                mContext, getLooper(), mFeatureFlags, supported);
        plogd("updateSatelliteSupportedState: create a new SatelliteSessionController because "
                + "satellite supported state has changed to " + supported);

        if (supported) {
            registerForPendingDatagramCount();
            registerForSatelliteModemStateChanged();
            registerForNtnSignalStrengthChanged();
            registerForCapabilitiesChanged();
            registerForSatelliteRegistrationFailure();
            registerForTerrestrialNetworkAvailableChanged();

            requestIsSatelliteProvisioned(
                    new ResultReceiver(this) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            plogd("updateSatelliteSupportedState.requestIsSatelliteProvisioned: "
                                    + "resultCode=" + resultCode + ", resultData=" + resultData);
                            decrementResultReceiverCount("SC:requestIsSatelliteProvisioned");
                            requestSatelliteEnabled(false, false, false,
                                    new IIntegerConsumer.Stub() {
                                        @Override
                                        public void accept(int result) {
                                            plogd("updateSatelliteSupportedState."
                                                    + "requestSatelliteEnabled: result=" + result);
                                        }
                                    });
                        }
                    });
            incrementResultReceiverCount("SC:requestIsSatelliteProvisioned");

            requestSatelliteCapabilities(
                    new ResultReceiver(this) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            plogd("updateSatelliteSupportedState.requestSatelliteCapabilities: "
                                    + "resultCode=" + resultCode + ", resultData=" + resultData);
                            decrementResultReceiverCount("SC:requestSatelliteCapabilities");
                        }
                    });
            incrementResultReceiverCount("SC:requestSatelliteCapabilities");
        }
        registerForSatelliteSupportedStateChanged();
        selectBindingSatelliteSubscription(false);
        notifySatelliteSupportedStateChanged(supported);
        return true;
    }

    private void updateSatelliteEnabledState(boolean enabled, String caller) {
        setIsSatelliteEnabled(enabled);
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.onSatelliteEnabledStateChanged(enabled);
            mSatelliteSessionController.setDemoMode(mIsDemoModeEnabled.get());
        } else {
            ploge(caller + ": mSatelliteSessionController is not initialized yet");
        }
        if (!enabled) {
            mIsModemEnabledReportingNtnSignalStrength.set(false);
        }
        notifyEnabledStateChanged(enabled);
    }

    private void registerForPendingDatagramCount() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForPendingDatagramCountWithSatelliteService.get()) {
                mSatelliteModemInterface.registerForPendingDatagrams(
                        this, EVENT_PENDING_DATAGRAMS, null);
                mRegisteredForPendingDatagramCountWithSatelliteService.set(true);
            }
        }
    }

    private void registerForSatelliteModemStateChanged() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForSatelliteModemStateChangedWithSatelliteService.get()) {
                mSatelliteModemInterface.registerForSatelliteModemStateChanged(
                        this, EVENT_SATELLITE_MODEM_STATE_CHANGED, null);
                mRegisteredForSatelliteModemStateChangedWithSatelliteService.set(true);
            }
        }
    }

    private void registerForNtnSignalStrengthChanged() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForNtnSignalStrengthChanged.get()) {
                mSatelliteModemInterface.registerForNtnSignalStrengthChanged(
                        this, EVENT_NTN_SIGNAL_STRENGTH_CHANGED, null);
                mRegisteredForNtnSignalStrengthChanged.set(true);
            }
        }
    }

    private void registerForCapabilitiesChanged() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForSatelliteCapabilitiesChanged.get()) {
                mSatelliteModemInterface.registerForSatelliteCapabilitiesChanged(
                        this, EVENT_SATELLITE_CAPABILITIES_CHANGED, null);
                mRegisteredForSatelliteCapabilitiesChanged.set(true);
            }
        }
    }

    private void registerForSatelliteSupportedStateChanged() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForSatelliteSupportedStateChanged.get()) {
                mSatelliteModemInterface.registerForSatelliteSupportedStateChanged(
                        this, EVENT_SATELLITE_SUPPORTED_STATE_CHANGED, null);
                mRegisteredForSatelliteSupportedStateChanged.set(true);
            }
        }
    }

    private void registerForSatelliteRegistrationFailure() {
        if (!mRegisteredForSatelliteRegistrationFailure.get()) {
            mSatelliteModemInterface.registerForSatelliteRegistrationFailure(this,
                    EVENT_SATELLITE_REGISTRATION_FAILURE, null);
            mRegisteredForSatelliteRegistrationFailure.set(true);
        }
    }

    private void registerForTerrestrialNetworkAvailableChanged() {
        if (!mRegisteredForTerrestrialNetworkAvailableChanged.get()) {
            mSatelliteModemInterface.registerForTerrestrialNetworkAvailableChanged(this,
                    EVENT_TERRESTRIAL_NETWORK_AVAILABLE_CHANGED, null);
            mRegisteredForTerrestrialNetworkAvailableChanged.set(true);
        }
    }

    private void notifyDeviceProvisionStateChanged(boolean provisioned) {
        List<ISatelliteProvisionStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteProvisionStateChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteProvisionStateChanged(provisioned);
            } catch (RemoteException e) {
                plogd("notifyDeviceProvisionStateChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteProvisionStateChangedListeners.remove(listener.asBinder());
        });
    }

    private boolean updateSatelliteSubscriptionProvisionState(List<SatelliteSubscriberInfo> newList,
            boolean provisioned) {
        logd("updateSatelliteSubscriptionProvisionState: List=" + newList + " , provisioned="
                + provisioned);
        boolean provisionChanged = false;
        for (SatelliteSubscriberInfo subscriberInfo : newList) {

            int subId = subscriberInfo.getSubscriptionId();
            Boolean currentProvisioned =
                    mProvisionedSubscriberId.get(subscriberInfo.getSubscriberId());
            if (currentProvisioned == null) {
                currentProvisioned = false;
            }

            Boolean isProvisionedInPersistentDb = false;
            try {
                isProvisionedInPersistentDb = mSubscriptionManagerService
                        .isSatelliteProvisionedForNonIpDatagram(subId);
                if (isProvisionedInPersistentDb == null) {
                    isProvisionedInPersistentDb = false;
                }
            } catch (IllegalArgumentException | SecurityException ex) {
                ploge("isSatelliteProvisionedForNonIpDatagram: subId=" + subId + ", ex="
                        + ex);
            }
            if (currentProvisioned == provisioned
                    && isProvisionedInPersistentDb == provisioned) {
                continue;
            }
            provisionChanged = true;
            mProvisionedSubscriberId.put(subscriberInfo.getSubscriberId(), provisioned);
            try {
                mSubscriptionManagerService.setIsSatelliteProvisionedForNonIpDatagram(subId,
                        provisioned);
                plogd("updateSatelliteSubscriptionProvisionState: set Provision state to db "
                        + "subId=" + subId);
            } catch (IllegalArgumentException | SecurityException ex) {
                ploge("setIsSatelliteProvisionedForNonIpDatagram: subId=" + subId + ", ex="
                        + ex);
            }
        }
        return provisionChanged;
    }

    private void handleEventSatelliteSubscriptionProvisionStateChanged() {
        List<SatelliteSubscriberProvisionStatus> informList =
                getPrioritizedSatelliteSubscriberProvisionStatusList();
        plogd("handleEventSatelliteSubscriptionProvisionStateChanged: " + informList);
        notifySatelliteSubscriptionProvisionStateChanged(informList);
        updateCachedDeviceProvisionStatus();
        // Report updated provisioned status to metrics.
        handleEntireProvisionMetricReport();
        scheduleRegularMetricReportTimer();
        selectBindingSatelliteSubscription(false);
        handleCarrierRoamingNtnAvailableServicesChanged();
    }

    private void updateCachedDeviceProvisionStatus() {
        boolean isProvisioned = getPersistedDeviceProvisionStatus();
        plogd("updateCachedDeviceProvisionStatus: isProvisioned=" + isProvisioned);
        if (mIsDeviceProvisioned == null) {
            mIsDeviceProvisioned = new AtomicBoolean(isProvisioned);
            notifyDeviceProvisionStateChanged(isProvisioned);
        } else if (mIsDeviceProvisioned.get() != isProvisioned) {
            mIsDeviceProvisioned.set(isProvisioned);
            notifyDeviceProvisionStateChanged(isProvisioned);
        }
    }

    private void notifySatelliteSubscriptionProvisionStateChanged(
            @NonNull List<SatelliteSubscriberProvisionStatus> list) {
        List<ISatelliteProvisionStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteProvisionStateChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteSubscriptionProvisionStateChanged(list);
            } catch (RemoteException e) {
                plogd("notifySatelliteSubscriptionProvisionStateChanged: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteProvisionStateChangedListeners.remove(listener.asBinder());
        });
    }

    private void handleEventSatelliteModemStateChanged(
            @SatelliteManager.SatelliteModemState int state) {
        plogd("handleEventSatelliteModemStateChanged: state=" + state);
        mSatelliteModemState.set(state);
        if (state == SatelliteManager.SATELLITE_MODEM_STATE_UNAVAILABLE
                || state == SatelliteManager.SATELLITE_MODEM_STATE_OFF) {
            if (!isWaitingForDisableSatelliteModemResponse()) {
                moveSatelliteToOffStateAndCleanUpResources(SATELLITE_RESULT_SUCCESS);
            } else {
                notifyModemStateChangedToSessionController(
                        SatelliteManager.SATELLITE_MODEM_STATE_OFF);
            }
            mWaitingForSatelliteModemOff.set(false);
        } else {
            if (isSatelliteEnabledOrBeingEnabled() || isSatelliteBeingDisabled()) {
                notifyModemStateChangedToSessionController(state);
            } else {
                // Telephony framework and modem are out of sync. We need to disable
                plogw("Satellite modem is in a bad state. Disabling satellite modem now ...");
                Consumer<Integer> result = integer -> plogd(
                        "handleEventSatelliteModemStateChanged: disabling satellite result="
                                + integer);
                RequestSatelliteEnabledArgument disabledRequest =
                        new RequestSatelliteEnabledArgument(false /* enableSatellite */,
                                false /* enableDemoMode */, false /* isEmergency */, result);
                setSatelliteDisabledRequest(disabledRequest);
                sendRequestAsync(CMD_SET_SATELLITE_ENABLED, disabledRequest, null);
            }
        }
    }

    private void notifyModemStateChangedToSessionController(
            @SatelliteManager.SatelliteModemState int state) {
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.onSatelliteModemStateChanged(state);
        } else {
            ploge("notifyModemStateChangedToSessionController: mSatelliteSessionController is "
                    + "null");
        }
    }

    private void handleEventNtnSignalStrengthChanged(@NonNull NtnSignalStrength ntnSignalStrength) {
        logd("handleEventNtnSignalStrengthChanged: ntnSignalStrength=" + ntnSignalStrength);
        setNtnSignalStrength(ntnSignalStrength);
        mSessionMetricsStats.updateMaxNtnSignalStrengthLevel(ntnSignalStrength.getLevel());

        List<INtnSignalStrengthCallback> deadCallersList = new ArrayList<>();
        mNtnSignalStrengthChangedListeners.values().forEach(listener -> {
            try {
                listener.onNtnSignalStrengthChanged(ntnSignalStrength);
            } catch (RemoteException e) {
                plogd("handleEventNtnSignalStrengthChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mNtnSignalStrengthChangedListeners.remove(listener.asBinder());
        });
    }

    private void handleEventSatelliteCapabilitiesChanged(SatelliteCapabilities capabilities) {
        plogd("handleEventSatelliteCapabilitiesChanged()");

        setSatelliteCapabilities(capabilities);
        overrideSatelliteCapabilitiesIfApplicable();

        SatelliteCapabilities satelliteCapabilities = getSatelliteCapabilities();
        List<ISatelliteCapabilitiesCallback> deadCallersList = new ArrayList<>();
        mSatelliteCapabilitiesChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteCapabilitiesChanged(satelliteCapabilities);
            } catch (RemoteException e) {
                plogd("handleEventSatelliteCapabilitiesChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteCapabilitiesChangedListeners.remove(listener.asBinder());
        });
    }

    private void handleEventSatelliteSupportedStateChanged(boolean supported) {
        plogd("handleSatelliteSupportedStateChangedEvent: supported=" + supported);
        if (!updateSatelliteSupportedState(supported)) {
            plogd("handleSatelliteSupportedStateChangedEvent: supported state does not change");
            return;
        }
        Boolean isSatelliteEnabled = getIsSatelliteEnabled();
         /* In case satellite has been reported as not support from modem, but satellite is
               enabled, request disable satellite. */
        if (!supported && isSatelliteEnabled != null && isSatelliteEnabled) {
            plogd("Invoke requestSatelliteEnabled(), supported=false, "
                    + "mIsSatelliteEnabled=true");
            requestSatelliteEnabled(false /* enableSatellite */, false /* enableDemoMode */,
                    false /* isEmergency */,
                    new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int result) {
                            plogd("handleSatelliteSupportedStateChangedEvent: request "
                                    + "satellite disable, result=" + result);
                        }
                    });

        }
    }

    private void handleEventSelectedNbIotSatelliteSubscriptionChanged(int selectedSubId) {
        plogd("handleEventSelectedNbIotSatelliteSubscriptionChanged: " + selectedSubId);

        List<ISelectedNbIotSatelliteSubscriptionCallback> deadCallersList = new ArrayList<>();
        mSelectedNbIotSatelliteSubscriptionChangedListeners.values().forEach(listener -> {
            try {
                listener.onSelectedNbIotSatelliteSubscriptionChanged(selectedSubId);
            } catch (RemoteException e) {
                logd("handleEventSelectedNbIotSatelliteSubscriptionChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSelectedNbIotSatelliteSubscriptionChangedListeners.remove(listener.asBinder());
        });
    }

    private void notifySatelliteSupportedStateChanged(boolean supported) {
        List<IBooleanConsumer> deadCallersList = new ArrayList<>();
        mSatelliteSupportedStateChangedListeners.values().forEach(listener -> {
            try {
                listener.accept(supported);
            } catch (RemoteException e) {
                plogd("handleSatelliteSupportedStateChangedEvent RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteSupportedStateChangedListeners.remove(listener.asBinder());
        });
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void setSettingsKeyForSatelliteMode(int val) {
        plogd("setSettingsKeyForSatelliteMode val: " + val);
        Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.SATELLITE_MODE_ENABLED, val);
    }

    /**
     * Allow screen rotation temporary in rotation locked foldable device.
     * <p>
     * Temporarily allow screen rotation user to catch satellite signals properly by UI guide in
     * emergency situations. Unlock the setting value so that the screen rotation is not locked, and
     * return it to the original value when the satellite service is finished.
     * <p>
     * Note that, only the unfolded screen will be temporarily allowed screen rotation.
     *
     * @param val {@link SATELLITE_MODE_ENABLED_TRUE} if satellite mode is enabled,
     *     {@link SATELLITE_MODE_ENABLED_FALSE} satellite mode is not enabled.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void setSettingsKeyToAllowDeviceRotation(int val) {
        // Only allows on a foldable device type.
        if (!isFoldable(mContext, mDeviceStates)) {
            logd("setSettingsKeyToAllowDeviceRotation(" + val + "), device was not a foldable");
            return;
        }

        switch (val) {
            case SATELLITE_MODE_ENABLED_TRUE:
                mDeviceRotationLockToBackupAndRestore =
                        Settings.Secure.getString(mContentResolver,
                                Settings.Secure.DEVICE_STATE_ROTATION_LOCK);
                String unlockedRotationSettings = replaceDeviceRotationValue(
                        mDeviceRotationLockToBackupAndRestore == null
                                ? "" : mDeviceRotationLockToBackupAndRestore,
                        Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNFOLDED,
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
                Settings.Secure.putString(mContentResolver,
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK, unlockedRotationSettings);
                logd("setSettingsKeyToAllowDeviceRotation(TRUE), RotationSettings is changed"
                        + " from " + mDeviceRotationLockToBackupAndRestore
                        + " to " + unlockedRotationSettings);
                break;
            case SATELLITE_MODE_ENABLED_FALSE:
                if (mDeviceRotationLockToBackupAndRestore == null) {
                    break;
                }
                Settings.Secure.putString(mContentResolver,
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        mDeviceRotationLockToBackupAndRestore);
                logd("setSettingsKeyToAllowDeviceRotation(FALSE), RotationSettings is restored to"
                        + mDeviceRotationLockToBackupAndRestore);
                mDeviceRotationLockToBackupAndRestore = "";
                break;
            default:
                loge("setSettingsKeyToAllowDeviceRotation(" + val + "), never reach here.");
                break;
        }
    }

    /**
     * If the device type is foldable.
     *
     * @param context context
     * @param deviceStates list of {@link DeviceState}s provided from {@link DeviceStateManager}
     * @return {@code true} if device type is foldable. {@code false} for otherwise.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean isFoldable(Context context, List<DeviceState> deviceStates) {
        if (android.hardware.devicestate.feature.flags.Flags.deviceStatePropertyMigration()) {
            return deviceStates.stream().anyMatch(deviceState -> deviceState.hasProperty(
                    PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY)
                    || deviceState.hasProperty(
                    PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY));
        } else {
            return context.getResources().getIntArray(R.array.config_foldedDeviceStates).length > 0;
        }
    }

    /**
     * Replaces a value of given a target key with a new value in a string of key-value pairs.
     * <p>
     * Replaces the value corresponding to the target key with a new value. If the key value is not
     * found in the device rotation information, it is not replaced.
     *
     * @param deviceRotationValue Device rotation key values separated by colon(':').
     * @param targetKey The key of the new item caller wants to add.
     * @param newValue  The value of the new item caller want to add.
     * @return A new string where all the key-value pairs.
     */
    private static String replaceDeviceRotationValue(
            @NonNull String deviceRotationValue, int targetKey, int newValue) {
        // Use list of Key-Value pair
        List<Pair<Integer, Integer>> keyValuePairs = new ArrayList<>();

        String[] pairs = deviceRotationValue.split(":");
        if (pairs.length % 2 != 0) {
            // Return without modifying. The key-value may be incorrect if length is an odd number.
            loge("The length of key-value pair do not match. Return without modification.");
            return deviceRotationValue;
        }

        // collect into keyValuePairs
        for (int i = 0; i < pairs.length; i += 2) {
            try {
                int key = Integer.parseInt(pairs[i]);
                int value = Integer.parseInt(pairs[i + 1]);
                keyValuePairs.add(new Pair<>(key, key == targetKey ? newValue : value));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                // Return without modifying if got exception.
                loge("got error while parsing key-value. Return without modification. e:" + e);
                return deviceRotationValue;
            }
        }

        return keyValuePairs.stream()
                .map(pair -> pair.first + ":" + pair.second) // Convert to "key:value" format
                .collect(Collectors.joining(":")); // Join pairs with colons
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean areAllRadiosDisabled() {
        if ((mDisableBTOnSatelliteEnabled.get() && mBTStateEnabled.get())
                || (mDisableNFCOnSatelliteEnabled.get() && mNfcStateEnabled.get())
                || (mDisableWifiOnSatelliteEnabled.get() && mWifiStateEnabled.get())
                || (mDisableUWBOnSatelliteEnabled.get() && mUwbStateEnabled.get())) {
            plogd("All radios are not disabled yet.");
            return false;
        }
        plogd("All radios are disabled.");
        return true;
    }

    private void evaluateToSendSatelliteEnabledSuccess() {
        plogd("evaluateToSendSatelliteEnabledSuccess");
        RequestSatelliteEnabledArgument satelliteEnabledRequest = getSatelliteEnabledRequest();
        if (areAllRadiosDisabled() && (satelliteEnabledRequest != null)
                && mWaitingForRadioDisabled.get()) {
            plogd("Sending success to callback that sent enable satellite request");
            setIsSatelliteEnabled(satelliteEnabledRequest.enableSatellite);
            setEmergencyMode(satelliteEnabledRequest.isEmergency);
            updateSatelliteEnabledState(satelliteEnabledRequest.enableSatellite,
                    "EVENT_SET_SATELLITE_ENABLED_DONE");
            satelliteEnabledRequest.callback.accept(SATELLITE_RESULT_SUCCESS);
            if (satelliteEnabledRequest.enableSatellite
                    && !satelliteEnabledRequest.isEmergency) {
                plogd("Starting pointingUI needFullscreenPointingUI=" + true
                        + "mIsDemoModeEnabled=" + mIsDemoModeEnabled.get() + ", isEmergency="
                        + satelliteEnabledRequest.isEmergency);
                mPointingAppController.startPointingUI(true, mIsDemoModeEnabled.get(), false);
            }
            setSatelliteEnabledRequest(null);
            mWaitingForRadioDisabled.set(false);

            if (getSatelliteEnableAttributesUpdateRequest() != null) {
                sendRequestAsync(CMD_UPDATE_SATELLITE_ENABLE_ATTRIBUTES,
                        getSatelliteEnableAttributesUpdateRequest(), null);
            }
            updateLastNotifiedNtnModeAndNotify(getSatellitePhone());

            if (mFeatureFlags.satelliteExitP2pSessionOutsideGeofence()) {
                evaluateDisablingP2pSession();
            }
        }
    }

    private void resetSatelliteEnabledRequest() {
        plogd("resetSatelliteEnabledRequest");
        setSatelliteEnabledRequest(null);
        mWaitingForRadioDisabled.set(false);
    }

    private void resetSatelliteDisabledRequest() {
        plogd("resetSatelliteDisabledRequest");
        setSatelliteDisabledRequest(null);
        mWaitingForDisableSatelliteModemResponse.set(false);
        mWaitingForSatelliteModemOff.set(false);
    }

    /**
     * Move to OFF state and clean up resources.
     *
     * @param resultCode The result code will be returned to requesting clients.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void moveSatelliteToOffStateAndCleanUpResources(
            @SatelliteManager.SatelliteResult int resultCode) {
        plogd("moveSatelliteToOffStateAndCleanUpResources");
        setDemoModeEnabled(false);
        handlePersistentLoggingOnSessionEnd(mIsEmergency.get());
        setEmergencyMode(false);
        setIsSatelliteEnabled(false);
        setSettingsKeyForSatelliteMode(SATELLITE_MODE_ENABLED_FALSE);
        setSettingsKeyToAllowDeviceRotation(SATELLITE_MODE_ENABLED_FALSE);
        abortSatelliteDisableRequest(resultCode);
        abortSatelliteEnableRequest(resultCode);
        abortSatelliteEnableAttributesUpdateRequest(resultCode);
        resetSatelliteEnabledRequest();
        resetSatelliteDisabledRequest();
        // TODO (b/361139260): Stop timer to wait for other radios off
        updateSatelliteEnabledState(
                false, "moveSatelliteToOffStateAndCleanUpResources");
        selectBindingSatelliteSubscription(false);
        updateLastNotifiedNtnModeAndNotify(getSatellitePhone());

        sendMessage(obtainMessage(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION));
        // Evaluate eligibility after satellite session is disabled
        sendMessage(obtainMessage(CMD_EVALUATE_CARRIER_ROAMING_NTN_ELIGIBILITY_CHANGE));
    }

    private void setDemoModeEnabled(boolean enabled) {
        mIsDemoModeEnabled.set(enabled);
        mDatagramController.setDemoMode(enabled);
        plogd("setDemoModeEnabled: mIsDemoModeEnabled=" + enabled);
    }

    private void setEmergencyMode(boolean isEmergency) {
        plogd("setEmergencyMode: mIsEmergency=" + mIsEmergency.get()
                + ", isEmergency=" + isEmergency);
        if (mIsEmergency.get() != isEmergency) {
            mIsEmergency.set(isEmergency);
            if (mSatelliteSessionController != null) {
                mSatelliteSessionController.onEmergencyModeChanged(isEmergency);
            } else {
                plogw("setEmergencyMode: mSatelliteSessionController is null");
            }
        }
    }

    private boolean isMockModemAllowed() {
        return (DEBUG || SystemProperties.getBoolean(ALLOW_MOCK_MODEM_PROPERTY, false));
    }

    private void configureSatellitePlmnForCarrier(int subId) {
        logd("configureSatellitePlmnForCarrier");
        Phone phone = SatelliteServiceUtils.getPhone(subId);
        if (phone == null) {
            ploge("configureSatellitePlmnForCarrier: phone is null for subId=" + subId);
            return;
        }

        List<String> allPlmnList = new ArrayList<>(getAllPlmnSet());
         List<String> allowedPlmns = getAllowedCarrierPlmnListForModem(subId);
        phone.setSatellitePlmn(phone.getPhoneId(), allowedPlmns,
                allPlmnList, obtainMessage(EVENT_SET_SATELLITE_PLMN_INFO_DONE));

        if (mFeatureFlags.nrNtn()) {
            Set<String> allowedPlmnsSet = new HashSet<>(allowedPlmns);
            List<String> disallowedPlmns = allPlmnList.stream()
                    .filter(plmn -> !allowedPlmnsSet.contains(plmn))
                    .collect(Collectors.toList());
            SatellitePlmnNetworkInfo satellitePlmnNetworkInfo =
                    SatellitePlmnNetworkInfo.fromPlmn(subId, allowedPlmns, disallowedPlmns);
            plogd("configureSatellitePlmnForCarrier: satellitePlmnNetworkInfo="
                    + satellitePlmnNetworkInfo);
            phone.setSatelliteNetworkInfo(phone.getPhoneId(),
                    satellitePlmnNetworkInfo.toHalSatelliteNetworkInfo(),
                    obtainMessage(EVENT_SET_SATELLITE_NETWORK_INFO_DONE));
        }
    }

    /**
     * Retrieves a list of satellite PLMNs from the configuration updater.
     * Returns an empty list if the configuration is not available.
     */
    @NonNull
    private List<String> getDeviceSatellitePlmnListFromConfigUpdater() {
        if (mFeatureFlags.updateDeviceSatellitePlmnByConfigupdater()) {
            SatelliteConfig satelliteConfig = getSatelliteConfig();
            if (satelliteConfig != null) {
                plogd("getDeviceSatellitePlmnListFromConfigUpdater: return = "
                        + String.join(",", satelliteConfig.getDeviceSatelliteProviderList()));
                return satelliteConfig.getDeviceSatelliteProviderList();
            }
        }
        plogd("getDeviceSatellitePlmnListFromConfigUpdater: return empty list");
        return new ArrayList<>();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public Set<String> getAllPlmnSet() {
        Set<String> allPlmnSetFromSubInfo = new HashSet<>();
        int[] activeSubIdArray = mSubscriptionManagerService.getActiveSubIdList(true);
        for (int activeSubId : activeSubIdArray) {
            allPlmnSetFromSubInfo.addAll(getCarrierPlmnList(activeSubId));
            allPlmnSetFromSubInfo.addAll(getBarredPlmnList(activeSubId));
        }
        allPlmnSetFromSubInfo.addAll(mSatellitePlmnListFromOverlayConfig);
        allPlmnSetFromSubInfo.addAll(getDeviceSatellitePlmnListFromConfigUpdater());

        if (mIgnorePlmnListFromStorage.get()) {
            // Do not use PLMN list from storage
            plogd("getAllPlmnList: allPlmnSetFromSubInfo=" + allPlmnSetFromSubInfo);
            return allPlmnSetFromSubInfo;
        }

        Set<String> allPlmnListFromStorage = getCarrierRoamingNtnAllSatellitePlmnSetFromStorage();
        if (!allPlmnListFromStorage.containsAll(allPlmnSetFromSubInfo)) {
            allPlmnListFromStorage.addAll(allPlmnSetFromSubInfo);
            persistCarrierRoamingNtnAllSatellitePlmnSet(allPlmnListFromStorage);
        }

        plogd("getAllPlmnList: " + allPlmnListFromStorage);
        return allPlmnListFromStorage;
    }

    /**
     * Retrieves a list of satellite PLMNs for the given subscription ID.
     * Returns an empty list if the configuration is not available.
     *
     * @param subId The subscription ID to retrieve the satellite PLMNs for.
     * @return A list of satellite PLMNs for the given subscription ID.
     */
    public List<String> getCarrierPlmnList(int subId) {
        return mMergedPlmnListPerCarrier.computeIfAbsent(
                subId, k -> new ArrayList<>()).stream().toList();
    }

    private List<String> getBarredPlmnList(int subId) {
        return mEntitlementBarredPlmnListPerCarrier.computeIfAbsent(
                subId, k -> new ArrayList<>()).stream().toList();
    }

    private void persistCarrierRoamingNtnAllSatellitePlmnSet(Set<String> allSatellitePlmnSet) {
        plogd("persistCarrierRoamingNtnAllSatellitePlmnSet");
        if (!loadSatelliteSharedPreferences()) return;

        if (mSharedPreferences == null) {
            ploge("persistCarrierRoamingNtnAllSatellitePlmnSet: mSharedPreferences is null");
        } else {
            try {
                mSharedPreferences.edit().putStringSet(
                        CARRIER_ROAMING_NTN_ALL_SATELLITE_PLMN_SET_KEY, allSatellitePlmnSet)
                        .apply();
            } catch (Exception ex) {
                plogd("persistCarrierRoamingNtnAllSatellitePlmnSet: ex=" + ex);
            }

            setCarrierRoamingNtnAllSatellitePlmnSet(allSatellitePlmnSet);
        }
    }

    private void setCarrierRoamingNtnAllSatellitePlmnSet(Set<String> allSatellitePlmnSet) {
        synchronized (mCarrierRoamingNtnAllSatellitePlmnSetLock) {
            mCarrierRoamingNtnAllSatellitePlmnSet = allSatellitePlmnSet;
        }
    }

    private Set<String> getCarrierRoamingNtnAllSatellitePlmnSetFromStorage() {
        synchronized (mCarrierRoamingNtnAllSatellitePlmnSetLock) {
            if (mCarrierRoamingNtnAllSatellitePlmnSet != null) {
                plogd("getCarrierRoamingNtnAllSatellitePlmnSetFromStorage: "
                        + mCarrierRoamingNtnAllSatellitePlmnSet);
                return mCarrierRoamingNtnAllSatellitePlmnSet;
            }
        }

        if (!loadSatelliteSharedPreferences()) return new HashSet<>();

        if (mSharedPreferences == null) {
            ploge("getCarrierRoamingNtnAllSatellitePlmnSetFromStorage: mSharedPreferences is null");
            return new HashSet<>();
        } else {
            Set<String> allSatellitePlmnSet = new HashSet<>();
            try {
                allSatellitePlmnSet = mSharedPreferences.getStringSet(
                        CARRIER_ROAMING_NTN_ALL_SATELLITE_PLMN_SET_KEY, new HashSet<>());
            } catch (Exception ex) {
                plogd("getCarrierRoamingNtnAllSatellitePlmnSetFromStorage: ex=" + ex);
            }

            setCarrierRoamingNtnAllSatellitePlmnSet(allSatellitePlmnSet);
            plogd("getCarrierRoamingNtnAllSatellitePlmnSetFromStorage: " + allSatellitePlmnSet);
            return allSatellitePlmnSet;
        }
    }

    private void handleSetSatellitePlmnNetworkInfoDoneEvent(Message msg) {
        plogd("handleSetSatellitePlmnNetworkInfoDoneEvent");
        AsyncResult ar = (AsyncResult) msg.obj;
        SatelliteServiceUtils.getSatelliteError(ar, "handleSetSatellitePlmnNetworkInfoCmd");
    }

    private void handleSetSatellitePlmnInfoDoneEvent(Message msg) {
        plogd("handleSetSatellitePlmnInfoDoneEvent");
        AsyncResult ar = (AsyncResult) msg.obj;
        SatelliteServiceUtils.getSatelliteError(ar, "handleSetSatellitePlmnInfoCmd");
    }

    private void updateSupportedSatelliteServicesForActiveSubscriptions() {
        plogd("updateSupportedSatelliteServicesForActiveSubscriptions");
        mSatelliteServicesSupportedByCarriersFromConfig.clear();
        mMergedPlmnListPerCarrier.clear();
        mSupportedEmergencyPlmnsPerCarrierFromConfig.clear();
        mSupportedDisasterPlmnsPerCarrierFromConfig.clear();
        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds != null) {
            for (int subId : activeSubIds) {
                updateSupportedSatelliteServices(subId);
                updateSupportedEmergencyAndDisasterPlmns(subId);
                handleCarrierRoamingNtnAvailableServicesChanged(subId);
            }
        } else {
            loge("updateSupportedSatelliteServicesForActiveSubscriptions: "
                    + "activeSubIds is null");
        }
    }

    /**
     * If the entitlementPlmnList exist then used it.
     * Otherwise, If the carrierPlmnList exist then used it.
     */
    private void updatePlmnListPerCarrier(int subId) {
        plogd("updatePlmnListPerCarrier: subId=" + subId);
        List<String> carrierPlmnList, entitlementPlmnList;
        if (getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                false)) {
            entitlementPlmnList = mEntitlementPlmnListPerCarrier.computeIfAbsent(subId,
                    k -> new ArrayList<>()).stream().toList();
            plogd("updatePlmnListPerCarrier: entitlementPlmnList="
                    + String.join(",", entitlementPlmnList)
                    + " size=" + entitlementPlmnList.size());
            if (!entitlementPlmnList.isEmpty()) {
                mMergedPlmnListPerCarrier.put(subId, entitlementPlmnList);
                plogd("mMergedPlmnListPerCarrier is updated by Entitlement");
                mCarrierRoamingSatelliteControllerStats.reportConfigDataSource(subId,
                        SatelliteConstants.CONFIG_DATA_SOURCE_ENTITLEMENT);
                // update service data policy configured for the device
                int dataPolicy = mapDataPolicyForMetrics(getSatelliteDataSupportMode(subId));
                plogd("supported satellite data mode reported: " + dataPolicy);
                mCarrierRoamingSatelliteControllerStats.reportServiceDataPolicy(
                        subId, dataPolicy);
                return;
            }
        }

        SatelliteConfig satelliteConfig = getSatelliteConfig();
        if (satelliteConfig != null) {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            int carrierId = tm.createForSubscriptionId(subId).getSimCarrierId();
            List<String> plmnList = satelliteConfig.getAllSatellitePlmnsForCarrier(carrierId);
            if (!plmnList.isEmpty()) {
                plogd("mMergedPlmnListPerCarrier is updated by ConfigUpdater : "
                        + String.join(",", plmnList));
                mMergedPlmnListPerCarrier.put(subId, plmnList);
                mCarrierRoamingSatelliteControllerStats.reportConfigDataSource(subId,
                        SatelliteConstants.CONFIG_DATA_SOURCE_CONFIG_UPDATER);
                // update service data policy configured for the device
                int dataPolicy = mapDataPolicyForMetrics(getSatelliteDataSupportMode(subId));
                plogd("supported satellite data mode reported: " + dataPolicy);
                mCarrierRoamingSatelliteControllerStats.reportServiceDataPolicy(
                        subId, dataPolicy);
                return;
            }
        }

        if (mSatelliteServicesSupportedByCarriersFromConfig.containsKey(subId)
                && mSatelliteServicesSupportedByCarriersFromConfig.get(subId) != null) {
            carrierPlmnList =
                    mSatelliteServicesSupportedByCarriersFromConfig.get(subId).keySet()
                            .stream().toList();
            plogd("mMergedPlmnListPerCarrier is updated by carrier config: "
                    + String.join(",", carrierPlmnList));
            mCarrierRoamingSatelliteControllerStats.reportConfigDataSource(subId,
                    SatelliteConstants.CONFIG_DATA_SOURCE_CARRIER_CONFIG);
            // update service data policy configured for the device
            int dataPolicy = mapDataPolicyForMetrics(getSatelliteDataSupportMode(subId));
            plogd("supported satellite data mode reported: " + dataPolicy);
            mCarrierRoamingSatelliteControllerStats.reportServiceDataPolicy(
                    subId, dataPolicy);
        } else {
            carrierPlmnList = new ArrayList<>();
            plogd("Empty mMergedPlmnListPerCarrier");
        }
        mMergedPlmnListPerCarrier.put(subId, carrierPlmnList);
    }

    private void updateSupportedSatelliteServices(int subId) {
        plogd("updateSupportedSatelliteServices with subId " + subId);
        SatelliteConfig satelliteConfig = getSatelliteConfig();

        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        int carrierId = tm.createForSubscriptionId(subId).getSimCarrierId();

        if (satelliteConfig != null) {
            Map<String, Set<Integer>> supportedServicesPerPlmn =
                    satelliteConfig.getSupportedSatelliteServices(carrierId);
            if (!supportedServicesPerPlmn.isEmpty()) {
                mSatelliteServicesSupportedByCarriersFromConfig.put(subId,
                        supportedServicesPerPlmn);
                plogd("updateSupportedSatelliteServices using ConfigUpdater, "
                        + "PLMN list = " + supportedServicesPerPlmn.keySet());
                updatePlmnListPerCarrier(subId);
                return;
            } else {
                plogd("supportedServicesPerPlmn is empty");
            }
        }

        Map<String, Set<Integer>> supportedServicesPerCarrier =
                readSupportedSatelliteServicesFromCarrierConfig(subId);
        mSatelliteServicesSupportedByCarriersFromConfig.put(subId, supportedServicesPerCarrier);
        updatePlmnListPerCarrier(subId);
        plogd("updateSupportedSatelliteServices using carrier config, "
                + "PLMN list = " + supportedServicesPerCarrier.keySet());
    }

    private void updateSupportedEmergencyAndDisasterPlmns(int subId) {
        List<String> emergencyPlmns = readSupportedEmergencyPlmnsFromCarrierConfig(subId);
        List<String> disasterPlmns = readSupportedDisasterPlmnsFromCarrierConfig(subId);
        mSupportedEmergencyPlmnsPerCarrierFromConfig.put(subId, emergencyPlmns);
        mSupportedDisasterPlmnsPerCarrierFromConfig.put(subId, disasterPlmns);
        plogd("updateSupportedEmergencyAndDisasterPlmns: subId=" + subId
                + ", emergencyPlmns=" + String.join(",", emergencyPlmns)
                + ", disasterPlmns=" + String.join(",", disasterPlmns));
    }

    @NonNull
    private int getMaxAllowedDataModeDeviceConfigOverlay() {
        int maxAllowedDataMode = CarrierConfigManager.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED;
        try {
            maxAllowedDataMode =
                    mContext.getResources().getInteger(R.integer.max_allowed_data_mode);
        } catch (Resources.NotFoundException ex) {
            ploge(
                    "getMaxAllowedDataModeDeviceConfigOverlay: max_allowed_data_mode resource not"
                            + " found. ex="
                            + ex);
        }
        logd("getMaxAllowedDataModeDeviceConfigOverlay: " + maxAllowedDataMode);
        return maxAllowedDataMode;
    }

    /**
     * @return An integer representing the maximum allowed data mode. This will be the value from
     *     the configupdater configuration if available and valid, otherwise the value from the
     *     device configuration overlay.
     */
    public int getMaxAllowedDataMode() {
        if (mMaxAllowedDataModeForCtsTest.get() >= 0) {
            logd("getMaxAllowedDataMode: using the overridden value for CTS test="
                + mMaxAllowedDataModeForCtsTest.get());
            return mMaxAllowedDataModeForCtsTest.get();
        }
        if (mUncapMaxAllowedDataMode.get()) {
            logd("getMaxAllowedDataMode: max allowed data mode is uncapped, so return "
                    + CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL);
            return CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL;
        }
        int maxAllowedDataMode = getMaxAllowedDataModeDeviceConfigOverlay();
        logd("getMaxAllowedDataMode: device config=" + maxAllowedDataMode);

        SatelliteConfig satelliteConfig = getSatelliteConfig();
        if (satelliteConfig == null) {
            logd(
                    "getMaxAllowedDataMode: satelliteConfig is null: "
                            + "return "
                            + maxAllowedDataMode);
            return maxAllowedDataMode;
        }

        if (satelliteConfig.getSatelliteMaxAllowedDataMode() == null) {
            logd(
                    "getMaxAllowedDataMode: getSatelliteMaxAllowedDataMode() is null: "
                            + "return "
                            + maxAllowedDataMode);
            return maxAllowedDataMode;
        }

        maxAllowedDataMode = satelliteConfig.getSatelliteMaxAllowedDataMode();
        logd(
                "getMaxAllowedDataMode: "
                        + "satelliteConfig.getSatelliteMaxAllowedDataMode()="
                        + maxAllowedDataMode);
        return maxAllowedDataMode;
    }

    @NonNull
    private List<String> readSatellitePlmnsFromOverlayConfig() {
        String[] devicePlmns = readStringArrayFromOverlayConfig(
                R.array.config_satellite_providers);
        return Arrays.stream(devicePlmns).toList();
    }

    @NonNull
    private Map<String, Set<Integer>> readSupportedSatelliteServicesFromCarrierConfig(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        return SatelliteServiceUtils.parseSupportedSatelliteServices(
                config.getPersistableBundle(
                        KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE));
    }

    @NonNull
    private List<String> readSupportedEmergencyPlmnsFromCarrierConfig(int subId) {
        String[] emergencyPlmns = getConfigForSubId(subId)
                .getStringArray(KEY_SATELLITE_SUPPORTED_EMERGENCY_PLMN_STRING_ARRAY);
        return emergencyPlmns != null ? List.of(emergencyPlmns) : Collections.emptyList();
    }

    @NonNull
    private List<String> readSupportedDisasterPlmnsFromCarrierConfig(int subId) {
        String[] disasterPlmns = getConfigForSubId(subId)
                .getStringArray(KEY_SATELLITE_SUPPORTED_DISASTER_PLMN_STRING_ARRAY);
        return disasterPlmns != null ? List.of(disasterPlmns) : Collections.emptyList();
    }

    @NonNull
    private Map<String, Set<Integer>> readRegionalSatelliteEarfcnsFromCarrierConfig(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        return SatelliteServiceUtils.parseRegionalSatelliteEarfcns(
                config.getPersistableBundle(KEY_REGIONAL_SATELLITE_EARFCN_BUNDLE));
    }

    @NonNull private PersistableBundle getConfigForSubId(int subId) {
        PersistableBundle config = null;
        if (mCarrierConfigManager != null) {
            try {
                config = mCarrierConfigManager.getConfigForSubId(subId,
                        KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE,
                        KEY_SATELLITE_ATTACH_SUPPORTED_BOOL,
                        KEY_SATELLITE_DISPLAY_NAME_STRING,
                        KEY_SATELLITE_ROAMING_TURN_OFF_SESSION_FOR_EMERGENCY_CALL_BOOL,
                        KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT,
                        KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL,
                        KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY,
                        KEY_EMERGENCY_MESSAGING_SUPPORTED_BOOL,
                        KEY_EMERGENCY_CALL_TO_SATELLITE_T911_HANDOVER_TIMEOUT_MILLIS_INT,
                        KEY_SATELLITE_ESOS_SUPPORTED_BOOL,
                        KEY_SATELLITE_ROAMING_P2P_SMS_SUPPORTED_BOOL,
                        KEY_SATELLITE_NIDD_APN_NAME_STRING,
                        KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT,
                        KEY_CARRIER_SUPPORTED_SATELLITE_NOTIFICATION_HYSTERESIS_SEC_INT,
                        KEY_CARRIER_ROAMING_NTN_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_INT,
                        KEY_SATELLITE_ROAMING_SCREEN_OFF_INACTIVITY_TIMEOUT_SEC_INT,
                        KEY_SATELLITE_ROAMING_P2P_SMS_INACTIVITY_TIMEOUT_SEC_INT,
                        KEY_SATELLITE_ROAMING_ESOS_INACTIVITY_TIMEOUT_SEC_INT,
                        KEY_SATELLITE_SOS_MAX_DATAGRAM_SIZE_BYTES_INT,
                        KEY_SATELLITE_SUPPORTED_MSG_APPS_STRING_ARRAY,
                        KEY_REGIONAL_SATELLITE_EARFCN_BUNDLE,
                        KEY_SATELLITE_DATA_SUPPORT_MODE_INT,
                        KEY_SATELLITE_CONNECTED_NOTIFICATION_THROTTLE_MILLIS_INT,
                        KEY_SATELLITE_CONFIGS_PER_PLMN_BUNDLE,
                        KEY_SATELLITE_SUPPORTED_EMERGENCY_PLMN_STRING_ARRAY,
                        KEY_SATELLITE_SUPPORTED_DISASTER_PLMN_STRING_ARRAY,
                        KEY_CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_PROVIDER_PER_COUNTRY_BUNDLE,
                        KEY_CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_REDIRECTION_DESTINATION_STRING
                );
            } catch (Exception e) {
                logw("getConfigForSubId: " + e);
            }
        }
        if (config == null || config.isEmpty()) {
            config = CarrierConfigManager.getDefaultConfig();
        }
        return config;
    }

    private void handleCarrierConfigChanged(int slotIndex, int subId, int carrierId,
            int specificCarrierId) {
        plogd("handleCarrierConfigChanged(): slotIndex(" + slotIndex + "), subId("
                + subId + "), carrierId(" + carrierId + "), specificCarrierId("
                + specificCarrierId + ")");
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return;
        }

        getSatelliteEnabledForCarrierAtModem(subId);
        updateCarrierConfig(subId);
        updateSatelliteESOSSupported(subId);
        updateSatelliteProvisionedStatePerSubscriberId();
        updateEntitlementPlmnListPerCarrier(subId);
        updateSupportedSatelliteServicesForActiveSubscriptions();
        processNewCarrierConfigData(subId);
        resetCarrierRoamingSatelliteModeParams(subId);
        evaluateCarrierRoamingNtnEligibilityChange();
        sendMessageDelayed(obtainMessage(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION),
                mEvaluateEsosProfilesPrioritizationDurationMillis.get());
        updateRegionalSatelliteEarfcns(subId);
        updateAndReportEligibilitySource(subId);
    }

    private void getSatelliteEnabledForCarrierAtModem(int subId) {
        Phone phone = SatelliteServiceUtils.getPhone(subId);
        if (!mIsSatelliteAttachEnabledForCarrierArrayPerSub.containsKey(subId)) {
            // Get enabled status from modem for new subscription
            sendRequestAsync(CMD_GET_SATELLITE_ENABLED_FOR_CARRIER, null, phone);
        }
    }

    // imsi, msisdn, default sms subId change
    private void handleSubscriptionsChanged() {
        sendMessageDelayed(obtainMessage(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION),
                mEvaluateEsosProfilesPrioritizationDurationMillis.get());
    }

    private void processNewCarrierConfigData(int subId) {
        updateRestrictReasonForEntitlementPerCarrier(subId);
        updateSatelliteTechPerPlmnForActiveSubscriptions();
        configureSatellitePlmnForCarrier(subId);
        evaluateEnablingSatelliteForCarrier(subId,
                SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER, null);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void updateCarrierConfig(int subId) {
        mCarrierConfigArray.put(subId, getConfigForSubId(subId));
    }

    /**
     * If there is no cached entitlement plmn list, read it from the db and use it if it is not an
     * empty list.
     */
    private void updateEntitlementPlmnListPerCarrier(int subId) {
        if (!getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false)) {
            plogd("don't support entitlement");
            return;
        }

        if (!mEntitlementPlmnListPerCarrier.containsKey(subId)) {
            plogd("updateEntitlementPlmnListPerCarrier: no correspondent cache, load from "
                    + "persist storage");
            List<String> entitlementPlmnList =
                    mSubscriptionManagerService.getSatelliteEntitlementPlmnList(subId);
            if (entitlementPlmnList.isEmpty()) {
                plogd("updateEntitlementPlmnListPerCarrier: read empty list");
                return;
            }
            plogd("updateEntitlementPlmnListPerCarrier: entitlementPlmnList=" + String.join(",",
                    entitlementPlmnList));
            mEntitlementPlmnListPerCarrier.put(subId, entitlementPlmnList);
        }

        if (!mEntitlementBarredPlmnListPerCarrier.containsKey(subId)) {
            plogd("updateEntitlementBarredPlmnList: no correspondent cache, load from "
                    + "persist storage");
            List<String> entitlementBarredPlmnList =
                    mSubscriptionManagerService.getSatelliteEntitlementBarredPlmnList(subId);
            if (entitlementBarredPlmnList.isEmpty()) {
                plogd("updateEntitlementBarredPlmnList: read empty list");
            }
            plogd("updateEntitlementBarredPlmnList: entitlementBarredPlmnList=" + String.join(
                    ",", entitlementBarredPlmnList));
            mEntitlementBarredPlmnListPerCarrier.put(subId, entitlementBarredPlmnList);
        }

        if (!mEntitlementDataPlanMapPerCarrier.containsKey(subId)) {
            plogd("updateEntitlementDataPlanForPlmns: no correspondent cache, load from "
                    + "persist storage");
            Map<String, Integer> entitlementDataPlanForPlmns =
                    mSubscriptionManagerService.getSatelliteEntitlementDataPlanForPlmns(subId);
            if (entitlementDataPlanForPlmns.isEmpty()) {
                plogd("updateEntitlementBarredPlmnList: read empty list");
            }
            plogd("updateEntitlementDataPlanForPlmns: entitlementDataPlanForPlmns="
                    + entitlementDataPlanForPlmns);
            mEntitlementDataPlanMapPerCarrier.put(subId, entitlementDataPlanForPlmns);
        }

        if (!mEntitlementServiceTypeMapPerCarrier.containsKey(subId)) {
            plogd("updateEntitlementTypeMapPerCarrier: no correspondent cache, load from "
                    + "persist storage");
            Map<String, List<Integer>> entitlementTypeMapPerCarrier =
                    mSubscriptionManagerService.getSatelliteEntitlementPlmnServiceTypeMap(
                            subId);
            if (entitlementTypeMapPerCarrier.isEmpty()) {
                plogd("updateEntitlementTypeMapPerCarrier: read empty list");
            }
            plogd("updateEntitlementTypeMapPerCarrier: entitlementTypeMapPerCarrier="
                    + entitlementTypeMapPerCarrier);
            mEntitlementServiceTypeMapPerCarrier.put(subId, entitlementTypeMapPerCarrier);
        }

        if (!mEntitlementDataServicePolicyMapPerCarrier.containsKey(subId)) {
            plogd("updateEntitlementDataServicePolicy: no correspondent cache, load from "
                    + "persist storage");
            Map<String, Integer> entitlementDataServicePolicy =
                    mSubscriptionManagerService.getSatelliteEntitlementPlmnDataServicePolicy(
                            subId);
            if (entitlementDataServicePolicy.isEmpty()) {
                plogd("updateEntitlementDataServicePolicy: read empty list");
            }
            plogd("updateEntitlementDataServicePolicy: entitlementDataServicePolicy="
                    + entitlementDataServicePolicy);
            mEntitlementDataServicePolicyMapPerCarrier.put(subId, entitlementDataServicePolicy);
        }

        if (!mEntitlementVoiceServicePolicyMapPerCarrier.containsKey(subId)) {
            plogd("updateEntitlementVoiceServicePolicy: no correspondent cache, load from "
                    + "persist storage");
            Map<String, Integer> entitlementVoiceServicePolicy =
                    mSubscriptionManagerService.getSatelliteEntitlementPlmnVoiceServicePolicy(
                            subId);
            if (entitlementVoiceServicePolicy.isEmpty()) {
                plogd("updateEntitlementVoiceServicePolicy: read empty list");
            }
            plogd("updateEntitlementVoiceServicePolicy: entitlementVoiceServicePolicy="
                    + entitlementVoiceServicePolicy);
            mEntitlementVoiceServicePolicyMapPerCarrier.put(subId,
                    entitlementVoiceServicePolicy);
        }
    }

    /**
     * Update the value of SimInfo.COLUMN_SATELLITE_ESOS_SUPPORTED stored in the database based
     * on the value in the carrier config.
     */
    private void updateSatelliteESOSSupported(int subId) {
        boolean isSatelliteESosSupportedFromDB =
                mSubscriptionManagerService.getSatelliteESOSSupported(subId);
        boolean isSatelliteESosSupportedFromCarrierConfig = getConfigForSubId(subId).getBoolean(
                KEY_SATELLITE_ESOS_SUPPORTED_BOOL, false);
        if (isSatelliteESosSupportedFromDB != isSatelliteESosSupportedFromCarrierConfig) {
            mSubscriptionManagerService.setSatelliteESOSSupported(subId,
                    isSatelliteESosSupportedFromCarrierConfig);
            logd("updateSatelliteESOSSupported: " + isSatelliteESosSupportedFromCarrierConfig);
        }
    }

    /** If the provision state per subscriberId for the cached is not exist, check the database for
     * the corresponding value and use it. */
    protected void updateSatelliteProvisionedStatePerSubscriberId() {
        List<SubscriptionInfo> allSubInfos = mSubscriptionManagerService.getAllSubInfoList(
                mContext.getOpPackageName(), mContext.getAttributionTag());
        for (SubscriptionInfo info : allSubInfos) {
            int subId = info.getSubscriptionId();
            Pair<String, Integer> subscriberIdPair = getSubscriberIdAndType(
                    mSubscriptionManagerService.getSubscriptionInfo(subId));
            String subscriberId = subscriberIdPair.first;
            if (mProvisionedSubscriberId.get(subscriberId) == null) {
                boolean Provisioned = mSubscriptionManagerService
                        .isSatelliteProvisionedForNonIpDatagram(subId);
                if (Provisioned) {
                    mProvisionedSubscriberId.put(subscriberId, true);
                    logd("updateSatelliteProvisionStatePerSubscriberId: "
                            + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, subscriberId)
                            + " set true");
                }
            }
        }
        // Need to update the provision status of the device
        updateCachedDeviceProvisionStatus();
    }

    @NonNull
    private String[] readStringArrayFromOverlayConfig(@ArrayRes int id) {
        String[] strArray = null;
        try {
            strArray = mContext.getResources().getStringArray(id);
        } catch (Resources.NotFoundException ex) {
            ploge("readStringArrayFromOverlayConfig: id= " + id + ", ex=" + ex);
        }
        if (strArray == null) {
            strArray = new String[0];
        }
        return strArray;
    }

    boolean isSatelliteSupportedViaCarrier(int subId) {
        return getConfigForSubId(subId)
                .getBoolean(KEY_SATELLITE_ATTACH_SUPPORTED_BOOL);
    }

    private boolean isSatelliteEntitlementEnabled(int subId) {
        return !mSatelliteAttachRestrictionForCarrierArray
                .getOrDefault(subId, Collections.emptySet()).contains(
                        SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT);
    }

    private boolean isOnlyEmergencyServiceSupported(int subId) {
        if (getCarrierRoamingNtnConnectType(subId) == CARRIER_ROAMING_NTN_CONNECT_MANUAL) {
            plogd("isEmergencyServiceSupported: connect type of subId: " + subId
                    + " is manual, returning false");
            return false;
        }
        if (isSatelliteEntitlementEnabled(subId)) {
            plogd("isEmergencyServiceSupported: entitlement enabled for subId: " + subId
                    + ", returning false");
            return false;
        }

        if (getEmergencyPlmnList(subId).isEmpty()) {
            plogd("isEmergencyServiceSupported: plmnList is empty for subId: " + subId
                    + ", returning false");
            return false;
        }

        plogd("isEmergencyServiceSupported: return true, subId=" + subId);
        return true;
    }

    private boolean isDisasterServiceSupported(int subId) {
        if (getCarrierRoamingNtnConnectType(subId) == CARRIER_ROAMING_NTN_CONNECT_MANUAL) {
            plogd("isDisasterServiceSupported: connect type of subId: " + subId
                    + " is manual, returning false");
            return false;
        }

        if (getDisasterPlmnList(subId).isEmpty()) {
            plogd("isDisasterServiceSupported: plmnList is empty for subId: " + subId
                    + ", returning false");
            return false;
        }

        plogd("isDisasterServiceSupported: return true, subId=" + subId);
        return true;
    }

    @NonNull
    private List<String> getEmergencyPlmnList(int subId) {
        return mSupportedEmergencyPlmnsPerCarrierFromConfig.getOrDefault(subId, new ArrayList<>());
    }

    @NonNull
    private List<String> getDisasterPlmnList(int subId) {
        return mSupportedDisasterPlmnsPerCarrierFromConfig.getOrDefault(subId, new ArrayList<>());
    }

    /**
     * Return the list of PLMNs that Telephony should send to the modem as a list of carrier PLMNs
     * for the given subscription ID.
     *
     * @param subId Associated subscription ID
     */
    private List<String> getAllowedCarrierPlmnListForModem(int subId) {
        List<String> plmnList = new ArrayList<>();
        boolean entitlementSupported = getConfigForSubId(subId).getBoolean(
                        KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL);
        boolean isEntitled = mSatelliteEntitlementStatusPerCarrier.getOrDefault(subId, false);
        plogd("getAllowedCarrierPlmnListForModem: entitlementSupported=" + entitlementSupported
                + ", isEntitled=" + isEntitled + ", subId=" + subId);
        if (isOnlyEmergencyServiceSupported(subId)) {
            plmnList.addAll(getEmergencyPlmnList(subId));
            plmnList.addAll(getDisasterPlmnList(subId));
        } else if (!entitlementSupported || isEntitled) {
            plmnList.addAll(getCarrierPlmnList(subId));
            plmnList.removeAll(getEmergencyPlmnList(subId));
        }

        plogd("getAllowedCarrierPlmnListForModem: plmnList=" + String.join(",", plmnList));
        return plmnList;
    }

    /**
     * Return whether the device support P2P SMS mode from carrier config.
     *
     * @param subId Associated subscription ID
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean isSatelliteRoamingP2pSmSSupported(int subId) {
        return getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ROAMING_P2P_SMS_SUPPORTED_BOOL);
    }

    /**
     * Return whether the device support ESOS mode from carrier config.
     *
     * @param subId Associated subscription ID
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean isSatelliteEsosSupported(int subId) {
        return getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ESOS_SUPPORTED_BOOL);
    }

    /**
     * Return whether the device allows to turn off satellite session for emergency call.
     *
     * @param subId Associated subscription ID
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean turnOffSatelliteSessionForEmergencyCall(int subId) {
        return getConfigForSubId(subId).getBoolean(
                KEY_SATELLITE_ROAMING_TURN_OFF_SESSION_FOR_EMERGENCY_CALL_BOOL);
    }

    private int getCarrierRoamingNtnConnectType(int subId) {
        if (mFeatureFlags.vzwAstSkyloFallback()) {
            plogd("getCarrierRoamingNtnConnectType: Checking connect "
                        + "type from PLMN config for subId: " + subId);
            SatellitePerPlmnConfiguration config = getSatellitePerPlmnConfiguration(subId);
            plogd("getCarrierRoamingNtnConnectType: config: " + config);
            if (config != null
                && config.connectType != CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_UNKNOWN) {
                plogd("getCarrierRoamingNtnConnectType: Connect type from"
                        + " config: " + config.connectType);
                return config.connectType;
            }
        }
        int connectType = getConfigForSubId(subId).getInt(
                KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT);
        plogd("getCarrierRoamingNtnConnectType: Falling back to global "
                    + "carrier config connect type: " + connectType);
        return connectType;
    }

    /** Return the global connect type of the binding satellite subscription. */
    public int getSupportedConnectTypeMetrics() {
        int subId = mSelectedSatelliteSubId.get();
        return getSupportedConnectTypeMetrics(subId);
    }

    /**
     * Return the global connect type of the binding satellite subscription.
     * @param subId : subscription Id.
     */
    public int getSupportedConnectTypeMetrics(int subId) {
        // Return UNKNOWN if the subId is invalid OR if satellite attach is not supported by the
        // carrier
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || !isSatelliteSupportedViaCarrier(
                subId)) {
            return SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
        }

        int globalNtnConnectType = getConfigForSubId(subId).getInt(
                KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT);
        return SatelliteServiceUtils.fromSupportedConnectionMode(globalNtnConnectType);
    }

    /** Return the session connect type of the binding satellite subscription. */
    public int getSessionConnectTypeMetrics() {
        int subId = mSelectedSatelliteSubId.get();
        return getSessionConnectTypeMetrics(subId);
    }

    /**
     * Return the session connect type of the binding satellite subscription.
     * @param subId : subscription Id.
     */
    public int getSessionConnectTypeMetrics(int subId) {
        // Return UNKNOWN if the subId is invalid OR if satellite attach is not supported by the
        // carrier
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || !isSatelliteSupportedViaCarrier(
                subId)) {
            return SatelliteConstants.SESSION_NTN_CONNECT_TYPE_UNKNOWN;
        }

        int connectType = getCarrierRoamingNtnConnectType(subId);
        return SatelliteServiceUtils.fromSessionConnectionMode(connectType);
    }


    protected int getCarrierRoamingNtnEmergencyCallToSatelliteHandoverType(int subId) {
        if (mFeatureFlags.vzwAstSkyloFallback()) {
            plogd("getCarrierRoamingNtnEmergencyCallToSatelliteHandoverType: Checking handover "
                        + "type from PLMN config for subId: " + subId);
            SatellitePerPlmnConfiguration config = getSatellitePerPlmnConfiguration(subId);
            plogd("getCarrierRoamingNtnEmergencyCallToSatelliteHandoverType: config: " + config);
            if (config != null && config.handoverType
                != SatelliteManager.EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_UNKNOWN) {
                plogd("getCarrierRoamingNtnEmergencyCallToSatelliteHandoverType: Handover type from"
                        + " config: " + config.handoverType);
                return config.handoverType;
            }
        }
        int handoverType = getConfigForSubId(subId).getInt(
                KEY_CARRIER_ROAMING_NTN_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_INT);
        plogd("getCarrierRoamingNtnEmergencyCallToSatelliteHandoverType: Falling back to global "
                    + "carrier config handover type: "
                + handoverType);
        return handoverType;
    }

    @CarrierConfigManager.SATELLITE_DATA_SUPPORT_MODE
    private int getCarrierSatelliteDataSupportedModeFromConfig(int subId) {
        return getConfigForSubId(subId).getInt(KEY_SATELLITE_DATA_SUPPORT_MODE_INT);
    }

    /**
     * Satellite notification display restriction timeout. Default value is 7 days in millis.
     * @param subId : subscription Id.
     * @return : Notification throttle timeout in millis.
     */
    private long getNotificationDisplayThrottleTimeout(int subId) {
        return getConfigForSubId(subId).getLong(
                KEY_SATELLITE_CONNECTED_NOTIFICATION_THROTTLE_MILLIS_INT);
    }

    /**
     * Check if satellite attach is enabled by user for the carrier associated with the
     * {@code subId}.
     *
     * @param subId Subscription ID.
     *
     * @return Returns {@code true} if satellite attach for carrier is enabled by user,
     * {@code false} otherwise.
     */
    private boolean isSatelliteAttachEnabledForCarrierByUser(int subId) {
        Set<Integer> cachedRestrictionSet =
                mSatelliteAttachRestrictionForCarrierArray.get(subId);
        if (cachedRestrictionSet != null) {
            return !cachedRestrictionSet.contains(
                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER);
        } else {
            plogd("isSatelliteAttachEnabledForCarrierByUser() no correspondent cache, "
                    + "load from persist storage");
            try {
                String enabled =
                        mSubscriptionManagerService.getSubscriptionProperty(subId,
                                SATELLITE_ATTACH_ENABLED_FOR_CARRIER,
                                mContext.getOpPackageName(), mContext.getAttributionTag());

                if (enabled == null) {
                    ploge("isSatelliteAttachEnabledForCarrierByUser: invalid subId, subId="
                            + subId);
                    return false;
                }

                if (enabled.isEmpty()) {
                    ploge("isSatelliteAttachEnabledForCarrierByUser: no data for subId(" + subId
                            + ")");
                    return false;
                }

                boolean result = enabled.equals("1");
                if (!result) {
                    mSatelliteAttachRestrictionForCarrierArray.put(subId, new HashSet<>());
                    mSatelliteAttachRestrictionForCarrierArray.get(subId).add(
                            SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER);
                }
                return result;
            } catch (IllegalArgumentException | SecurityException ex) {
                ploge("isSatelliteAttachEnabledForCarrierByUser: ex=" + ex);
                return false;
            }
        }
    }

    /**
     * Check whether there is any reason to restrict satellite communication for the carrier
     * associated with the {@code subId}.
     *
     * @param subId Subscription ID
     * @return {@code true} when there is at least on reason, {@code false} otherwise.
     */
    private boolean hasReasonToRestrictSatelliteCommunicationForCarrier(int subId) {
        return !mSatelliteAttachRestrictionForCarrierArray
                .getOrDefault(subId, Collections.emptySet()).isEmpty();
    }

    private void updateRestrictReasonForEntitlementPerCarrier(int subId) {
        if (!getConfigForSubId(subId).getBoolean(KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false)) {
            plogd("don't support entitlement");
            return;
        }

        IIntegerConsumer callback = new IIntegerConsumer.Stub() {
            @Override
            public void accept(int result) {
                plogd("updateRestrictReasonForEntitlementPerCarrier:" + result);
            }
        };
        if (!mSatelliteEntitlementStatusPerCarrier.containsKey(subId)) {
            plogd("updateRestrictReasonForEntitlementPerCarrier: no correspondent cache, "
                    + "load from persist storage");
            String entitlementStatus = null;
            try {
                entitlementStatus =
                        mSubscriptionManagerService.getSubscriptionProperty(subId,
                                SATELLITE_ENTITLEMENT_STATUS, mContext.getOpPackageName(),
                                mContext.getAttributionTag());
            } catch (IllegalArgumentException | SecurityException e) {
                ploge("updateRestrictReasonForEntitlementPerCarrier, e=" + e);
            }

            if (entitlementStatus == null) {
                ploge("updateRestrictReasonForEntitlementPerCarrier: invalid subId, subId="
                        + subId + " set to default value");
                entitlementStatus = "0";
            }

            if (entitlementStatus.isEmpty()) {
                ploge("updateRestrictReasonForEntitlementPerCarrier: no data for subId(" + subId
                        + "). set to default value");
                entitlementStatus = "0";
            }
            boolean result = entitlementStatus.equals("1");
            handleIndividualEntitlementMetricReport(subId, result);
        }

        if (!mSatelliteEntitlementStatusPerCarrier.computeIfAbsent(subId, k -> false)) {
            addAttachRestrictionForCarrier(subId,
                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT, callback);
        }
    }

    /**
     * Save user setting for enabling satellite attach for the carrier associated with the
     * {@code subId} to persistent storage.
     *
     * @param subId Subscription ID.
     *
     * @return {@code true} if persist successful, {@code false} otherwise.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean persistSatelliteAttachEnabledForCarrierSetting(int subId) {
        plogd("persistSatelliteAttachEnabledForCarrierSetting");
        if (!isValidSubscriptionId(subId)) {
            ploge("persistSatelliteAttachEnabledForCarrierSetting: subId is not valid,"
                    + " subId=" + subId);
            return false;
        }

        try {
            mSubscriptionManagerService.setSubscriptionProperty(subId,
                    SATELLITE_ATTACH_ENABLED_FOR_CARRIER,
                    mSatelliteAttachRestrictionForCarrierArray.get(subId)
                            .contains(SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER)
                            ? "0" : "1");
        } catch (IllegalArgumentException | SecurityException ex) {
            ploge("persistSatelliteAttachEnabledForCarrierSetting, ex=" + ex);
            return false;
        }
        return true;
    }

    /**
     * Evaluate whether satellite attach for carrier should be restricted.
     *
     * @param subId Subscription Id to evaluate for.
     * @return {@code true} satellite attach is restricted, {@code false} otherwise.
     */
    private boolean isSatelliteRestrictedForCarrier(int subId) {
        return !isSatelliteAttachEnabledForCarrierByUser(subId)
                || hasReasonToRestrictSatelliteCommunicationForCarrier(subId);
    }

    /**
     * Check whether satellite is enabled for carrier at modem.
     *
     * @param subId subscription ID
     * @return {@code true} if satellite modem is enabled, {@code false} otherwise.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean isSatelliteEnabledForCarrierAtModem(int subId) {
        return mIsSatelliteAttachEnabledForCarrierArrayPerSub.getOrDefault(subId, false);
    }

    /**
     * Evaluate whether satellite modem for carrier should be enabled or not.
     * <p>
     * Satellite will be enabled only when the following conditions are met:
     * <ul>
     * <li>Users want to enable it.</li>
     * <li>There is no satellite communication restriction, which is added by
     * {@link #addAttachRestrictionForCarrier(int, int, IIntegerConsumer)}</li>
     * <li>The carrier config {@link
     * android.telephony.CarrierConfigManager#KEY_SATELLITE_ATTACH_SUPPORTED_BOOL} is set to
     * {@code true}.</li>
     * </ul>
     *
     * @param subId Subscription Id for evaluate for.
     * @param callback The callback for getting the result of enabling satellite.
     */
    private void evaluateEnablingSatelliteForCarrier(int subId, int reason,
            @Nullable Consumer<Integer> callback) {
        if (callback == null) {
            callback = errorCode -> plogd("evaluateEnablingSatelliteForCarrier: "
                    + "SetSatelliteAttachEnableForCarrier error code =" + errorCode);
        }

        Phone phone = SatelliteServiceUtils.getPhone(subId);
        if (phone == null) {
            ploge("evaluateEnablingSatelliteForCarrier: phone is null for subId=" + subId);
            callback.accept(SATELLITE_RESULT_INVALID_TELEPHONY_STATE);
            return;
        }

        /* Request to enable or disable the satellite in the cellular modem. */
        boolean isSatelliteExpectedToBeEnabled = (!isSatelliteRestrictedForCarrier(subId)
                || isOnlyEmergencyServiceSupported(subId) || isDisasterServiceSupported(subId))
                && isSatelliteSupportedViaCarrier(subId)
                && getCarrierRoamingNtnConnectType(subId)
                == CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC;
        boolean isSatelliteEnabledForCarrierAtModem = isSatelliteEnabledForCarrierAtModem(
                phone.getSubId());
        plogd("evaluateEnablingSatelliteForCarrier: subId=" + subId + " reason=" + reason
                + " isSatelliteExpectedToBeEnabled=" + isSatelliteExpectedToBeEnabled
                + " isSatelliteEnabledForCarrierAtModem=" + isSatelliteEnabledForCarrierAtModem);

        int simSlot = SubscriptionManager.getSlotIndex(subId);
        RequestHandleSatelliteAttachRestrictionForCarrierArgument argument =
                new RequestHandleSatelliteAttachRestrictionForCarrierArgument(subId,
                        reason, callback);
        SatelliteControllerHandlerRequest request =
                new SatelliteControllerHandlerRequest(argument,
                        SatelliteServiceUtils.getPhone(subId));
        Message onCompleted = obtainMessage(
                EVENT_EVALUATE_SATELLITE_ATTACH_RESTRICTION_CHANGE_DONE, request);
        phone.setSatelliteEnabledForCarrier(simSlot,
                isSatelliteExpectedToBeEnabled, onCompleted);
    }

    @SatelliteManager.SatelliteResult private int evaluateOemSatelliteRequestAllowed(
            boolean isProvisionRequired) {
        if (!mSatelliteModemInterface.isSatelliteServiceSupported()) {
            plogd("evaluateOemSatelliteRequestAllowed: satellite service is not supported");
            return SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
        }

        Boolean satelliteSupported = isSatelliteSupportedViaOemInternal();
        if (satelliteSupported == null) {
            plogd("evaluateOemSatelliteRequestAllowed: satelliteSupported is null");
            return SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
        }
        if (!satelliteSupported) {
            return SatelliteManager.SATELLITE_RESULT_NOT_SUPPORTED;
        }

        if (isProvisionRequired) {
            Boolean satelliteProvisioned = isDeviceProvisioned();
            if (satelliteProvisioned == null) {
                plogd("evaluateOemSatelliteRequestAllowed: satelliteProvisioned is null");
                return SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE;
            }
            if (!satelliteProvisioned) {
                plogd("evaluateOemSatelliteRequestAllowed: satellite service is not provisioned");
                return SatelliteManager.SATELLITE_RESULT_SERVICE_NOT_PROVISIONED;
            }
        }

        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Returns the non-terrestrial network radio technology that the satellite modem currently
     * supports. If multiple technologies are available, returns the first supported technology.
     */
    @VisibleForTesting
    protected @SatelliteManager.NTRadioTechnology int getSupportedNtnRadioTechnology() {
        SatelliteCapabilities satelliteCapabilities = getSatelliteCapabilities();
        if (satelliteCapabilities != null) {
            return satelliteCapabilities.getSupportedRadioTechnologies()
                    .stream().findFirst().orElse(SatelliteManager.NT_RADIO_TECHNOLOGY_UNKNOWN);
        }
        return SatelliteManager.NT_RADIO_TECHNOLOGY_UNKNOWN;
    }

    /**
     * Returns a list of messaging apps that support satellite.
     */
    @NonNull public List<String> getSatelliteSupportedMsgApps(int subId) {
        String[] satelliteSupportedMsgApps = getConfigForSubId(subId)
                .getStringArray(KEY_SATELLITE_SUPPORTED_MSG_APPS_STRING_ARRAY);

        return satelliteSupportedMsgApps != null
                ? List.of(satelliteSupportedMsgApps) : Collections.emptyList();
    }

    private void sendErrorAndReportSessionMetrics(@SatelliteManager.SatelliteResult int error,
            Consumer<Integer> result) {
        result.accept(error);
        mSessionMetricsStats.setInitializationResult(error)
                .setSatelliteTechnology(getSupportedNtnRadioTechnology())
                .setIsDemoMode(mIsDemoModeEnabled.get())
                .setCarrierId(getSatelliteCarrierId())
                .setSupportedConnectionMode(getSupportedConnectTypeMetrics())
                .setSessionConnectionMode(getSessionConnectTypeMetrics())
                .setIsNtnOnlyCarrier(isNtnOnlyCarrier())
                .reportSessionMetrics();
        mSessionStartTimeStamp.set(0);
        mSessionProcessingTimeStamp.set(0);
    }

    public boolean isNtnOnlyCarrier() {
        int selectedSatelliteSubId = mSelectedSatelliteSubId.get();
        if (selectedSatelliteSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return false;
        }
        return selectedSatelliteSubId == getNtnOnlySubscriptionId();
    }

    private void registerForServiceStateChanged() {
        for (Phone phone : PhoneFactory.getPhones()) {
            phone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
        }
    }

    private void registerForSignalStrengthChanged() {
        for (Phone phone : PhoneFactory.getPhones()) {
            phone.getSignalStrengthController().registerForSignalStrengthChanged(this,
                    EVENT_SIGNAL_STRENGTH_CHANGED, phone.getPhoneId());
        }
    }

    private void handleEventServiceStateChanged() {
        evaluateCarrierRoamingNtnEligibilityChange();
        handleServiceStateForSatelliteConnectionViaCarrier();
    }

    private void handleServiceStateForSatelliteConnectionViaCarrier() {
        for (Phone phone : PhoneFactory.getPhones()) {
            int subId = phone.getSubId();
            ServiceState serviceState = phone.getServiceState();
            if (serviceState == null || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                continue;
            }

            CarrierRoamingSatelliteSessionStats sessionStats =
                    mCarrierRoamingSatelliteSessionStatsMap.get(subId);
            if (DEBUG) {
                plogd("handleServiceStateForSatelliteConnectionViaCarrier : SubId = " + subId
                        + "  isUsingNonTerrestrialNetwork = "
                        + serviceState.isUsingNonTerrestrialNetwork());
            }
            if (serviceState.isUsingNonTerrestrialNetwork()) {
                if (sessionStats != null) {
                    sessionStats.onSignalStrength(phone);
                    if (!mWasSatelliteConnectedViaCarrier.computeIfAbsent(subId, k -> false)) {
                        // Log satellite connection start
                        sessionStats.onConnectionStart(phone);
                    }
                }

                resetCarrierRoamingSatelliteModeParams(subId);
                mWasSatelliteConnectedViaCarrier.put(subId, true);

                for (NetworkRegistrationInfo nri
                        : serviceState.getNetworkRegistrationInfoList()) {
                    if (nri.isNonTerrestrialNetwork()) {
                        mSatModeCapabilitiesForCarrierRoaming.put(subId,
                                nri.getAvailableServices());
                    }
                }
                updateCurrentSatellitePerPlmnConfiguration(
                    subId, serviceState.getOperatorNumeric());

            } else {
                Boolean wasSatelliteConnectedViaCarrier = mWasSatelliteConnectedViaCarrier
                        .computeIfAbsent(subId, k -> false);
                if (getWwanIsInService(serviceState)
                        || serviceState.getState() == ServiceState.STATE_POWER_OFF) {
                    plogd(
                            "handleServiceStateForSatelliteConnectionViaCarrier:"
                                + " resetCarrierRoamingSatelliteModeParams: device is connected to"
                                + " terrestrial service or radio is off");
                    resetCarrierRoamingSatelliteModeParams(subId);
                    if (mFeatureFlags.vzwAstSkyloFallback()) {
                        plogd("handleServiceStateForSatelliteConnectionViaCarrier: clearing "
                                + " current satellite per plmn configs for subId: " + subId);
                        mCurrentSatellitePerPlmnConfigurations.remove(subId);
                    }
                } else if (wasSatelliteConnectedViaCarrier != null
                               && wasSatelliteConnectedViaCarrier) {
                    // The device just got disconnected from a satellite network
                    // and is not connected to any terrestrial network that  has coverage
                    mLastSatelliteDisconnectedTimesMillis.put(subId, getElapsedRealtime());

                    plogd(
                            "handleServiceStateForSatelliteConnectionViaCarrier: "
                                    + "sendMessageDelayed subId:"
                                    + subId
                                    + " phoneId:"
                                    + phone.getPhoneId()
                                    + " time:"
                                    + getSatelliteConnectionHysteresisTimeMillis(subId));
                    sendMessageDelayed(obtainMessage(EVENT_NOTIFY_NTN_HYSTERESIS_TIMED_OUT,
                                    phone.getPhoneId()),
                            getSatelliteConnectionHysteresisTimeMillis(subId));

                    if (sessionStats != null) {
                        // Log satellite connection end
                        sessionStats.onConnectionEnd();
                    }
                }
                mWasSatelliteConnectedViaCarrier.put(subId, false);
            }
            updateLastNotifiedNtnModeAndNotify(phone);
            updateLastNotifiedCarrierRoamingNtnSignalStrengthAndNotify(phone);
        }
        determineAutoConnectSystemNotification();
    }

    /**
     * Returns {@code true} if the satellite provider supports either
     * {@code SatelliteManager.NT_RADIO_TECHNOLOGY_LTE_DTC} or
     * {@code SatelliteManager.NT_RADIO_TECHNOLOGY_NR_DTC}.
     *
     * @param subId The subscription ID to get the config for.
     * @param plmn  The PLMN to look up the technology for.
     * @return {@code true} if any DTC technology is supported or if the supported technology list
     * is null or empty (for legacy device support); {@code false} otherwise.
     */
    public boolean isDtcSatelliteTechnologySupported(int subId, @NonNull String plmn) {
        logd("isDtcSatelliteTechnologySupported: subId=" + subId + ", plmn=" + plmn);

        Set<String> satelliteProviderSet = getAllPlmnSet();
        if (!satelliteProviderSet.contains(plmn)) {
            logd("isDtcSatelliteTechnologySupported: the plmn=" + plmn
                    + " is not contained in satellite provider");
            return false;
        }

        List<Integer> supportedSatelliteTechList = getSupportedSatelliteTechnologies(subId, plmn);
        if (supportedSatelliteTechList == null || supportedSatelliteTechList.isEmpty()) {
            logd("isDtcSatelliteTechnologySupported: supportedSatelliteTechList="
                    + supportedSatelliteTechList + " null or empty, return true.");
            return true;
        }
        logd("isDtcSatelliteTechnologySupported: supportedSatelliteTechList="
                + supportedSatelliteTechList);
        return !Collections.disjoint(supportedSatelliteTechList, DTC_SATELLITE_TECHNOLOGY_LIST);
    }

    /**
     * Get the list of supported satellite technologies of a given PLMN and subId
     *
     * @param subId The subscription ID for which to get the satellite technology.
     * @param plmn The PLMN (Public Land Mobile Network) identifier.
     * @return A list of satellite technology types. Returns a list containing
     *         {@link SatelliteManager#NT_RADIO_TECHNOLOGY_UNKNOWN} if no configuration is found
     *         for the given PLMN or the map is empty.
     */
    @NonNull
    public List<Integer> getSupportedSatelliteTechnologies(int subId, @NonNull String plmn) {
        Map<String, List<Integer>> plmnSatelliteTechMap = getPlmnSatelliteTechForCarrier(subId);
        if (plmnSatelliteTechMap == null || plmnSatelliteTechMap.isEmpty()) {
            plogd("getSupportedSatelliteTechnologies: plmnSatelliteTechMap is empty");
            return new ArrayList<>();
        }
        List<Integer> satelliteTechList =
                plmnSatelliteTechMap.getOrDefault(plmn, new ArrayList<>());
        logd("getSupportedSatelliteTechnologies: subId=" + subId + ", plmn=" + plmn
                + ", satelliteTechList=" + satelliteTechList);
        return satelliteTechList;
    }

    /**
     * Updates the internal mapping of supported satellite technologies per PLMN for all active
     * subscriptions using carrier configurations.
     */
    private void updateSatelliteTechPerPlmnForActiveSubscriptions() {
        if (!mFeatureFlags.nrNtn()) {
            plogd("updateSatelliteTechPerPlmnForActiveSubscriptions: nrNtn feature is disabled");
            return;
        }

        mSatelliteTechPerPlmnForActiveSubId.clear();
        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds == null) {
            plogd("updateSatelliteTechPerPlmnForActiveSubscriptions: activeSubIds is null.");
            return;
        }

        for (int subId: activeSubIds) {
            PersistableBundle allConfigPerSubId = getPersistableBundle(subId);
            if (allConfigPerSubId == null) {
                logd("updateSatelliteTechPerPlmnForActiveSubscriptions: "
                        + "no carrier config found for subId: " + subId);
                continue;
            }

            PersistableBundle satellitePlmnBundle = allConfigPerSubId.getPersistableBundle(
                    CarrierConfigManager.KEY_SATELLITE_CONFIGS_PER_PLMN_BUNDLE);
            if (satellitePlmnBundle == null || satellitePlmnBundle.isEmpty()) {
                logd("updateSatelliteTechPerPlmnForActiveSubscriptions: "
                        + "no carrier config found for KEY_SATELLITE_CONFIGS_PER_PLMN_BUNDLE");
                continue;
            }

            final Map<String, List<Integer>> plmnTechMap = new HashMap<>();
            for (String plmn : satellitePlmnBundle.keySet()) {
                PersistableBundle plmnConfig = satellitePlmnBundle.getPersistableBundle(plmn);
                if (plmnConfig != null) {
                    int[] satelliteTechs = plmnConfig.getIntArray(
                            CarrierConfigManager.KEY_SATELLITE_TECHNOLOGY_INT_ARRAY);

                    if (satelliteTechs != null) {
                        List<Integer> supportedSatTechList = new ArrayList<>();
                        for (int satTech : satelliteTechs) {
                            if (SatelliteServiceUtils.isSatelliteTechSupported(satTech)) {
                                supportedSatTechList.add(satTech);
                            } else {
                                logw("updateSatelliteTechPerPlmnForActiveSubscriptions:"
                                        + " unsupported satellite tech=" + satTech);
                            }
                        }
                        if (!supportedSatTechList.isEmpty()) {
                            plmnTechMap.put(plmn, supportedSatTechList);
                        }
                    } else {
                        logw("updateSatelliteTechPerPlmnForActiveSubscriptions: "
                                + "satelliteTechs is null");
                    }
                }
            }
            logd("updateSatelliteTechPerPlmnForActiveSubscriptions: subId=" + subId
                    + ", plmnTechMap=" + plmnTechMap);
            mSatelliteTechPerPlmnForActiveSubId.put(subId, plmnTechMap);
        }
    }

    /**
     * Gets the map of PLMNs to their satellite technology type list for a given subscription ID.
     *
     * @param subId The subscription ID to look up.
     * @return A non-null map where keys are PLMN strings and values are satellite technology types
     *         list. Returns an empty map if no configurations are found for the given subId.
     */
    @NonNull
    private Map<String, List<Integer>> getPlmnSatelliteTechForCarrier(int subId) {
        return mSatelliteTechPerPlmnForActiveSubId.getOrDefault(subId, new HashMap<>());
    }

    /**
     * Populate the satellite configs for the given PLMN
     *
     * @param subId The subscription ID.
     * @param plmn The PLMN string.
     */
    private void updateCurrentSatellitePerPlmnConfiguration(int subId, String plmn) {
        if (!mFeatureFlags.vzwAstSkyloFallback()) {
            plogd(
                    "updateCurrentSatellitePerPlmnConfiguration: vzwAstSkyloFallback isn't enabled."
                            + " So notpopulating satellite configs for plmn: "
                            + plmn);
            return;
        }

        plogd("updateCurrentSatellitePerPlmnConfiguration: device is in NTN mode, with subId: "
            + subId + " with plmn: " + plmn);

        SatellitePerPlmnConfiguration config = new SatellitePerPlmnConfiguration();
        config.plmn = plmn;

        PersistableBundle perPlmnConfigs = getPersistableBundle(subId).getPersistableBundle(
            CarrierConfigManager.KEY_SATELLITE_CONFIGS_PER_PLMN_BUNDLE);
        PersistableBundle plmnSpecificConfig = perPlmnConfigs.getPersistableBundle(plmn);
        if (plmnSpecificConfig == null) {
            plogd("updateCurrentSatellitePerPlmnConfiguration: plmnSpecificConfig is null");
            mCurrentSatellitePerPlmnConfigurations.put(subId, config);
            return;
        }

        config.handoverType =
                plmnSpecificConfig.getInt(
                        CarrierConfigManager
                            .KEY_CARRIER_ROAMING_NTN_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_INT,
                        SatelliteManager.EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_UNKNOWN);
        config.connectType =
                plmnSpecificConfig.getInt(
                        CarrierConfigManager.KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT,
                        CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_UNKNOWN);

        int[] supportedSatelliteTechs = plmnSpecificConfig.getIntArray(
                CarrierConfigManager.KEY_SATELLITE_TECHNOLOGY_INT_ARRAY);
        if (supportedSatelliteTechs != null) {
            for (int satelliteTech : supportedSatelliteTechs) {
                if (SatelliteServiceUtils.isSatelliteTechSupported(satelliteTech)) {
                    config.supportedSatelliteTechs.add(satelliteTech);
                }
            }
        }

        mCurrentSatellitePerPlmnConfigurations.put(subId, config);
        plogd(
                "updateCurrentSatellitePerPlmnConfiguration: set up satellite configs for subId: "
                        + subId
                        + " plmn: "
                        + plmn
                        + ", configs: "
                        + config);
    }

    private void updateLastNotifiedNtnModeAndNotify(@Nullable Phone phone) {
        if (phone == null) {
            plogd("updateLastNotifiedNtnModeAndNotify: phone is null");
            return;
        }

        int subId = phone.getSubId();
        boolean initialized = mInitialized.computeIfAbsent(subId, k -> false);
        boolean lastNotifiedNtnMode = mLastNotifiedNtnMode.computeIfAbsent(subId, k -> false);
        boolean currNtnMode = isInSatelliteModeForCarrierRoaming(phone);
        plogd("updateLastNotifiedNtnModeAndNotify: subId=" + subId
                + " initialized=" + initialized
                + " lastNotifiedNtnMode=" + lastNotifiedNtnMode
                + " currNtnMode=" + currNtnMode);
        // If not in NTN mode, we should invalidate current per plmn satellite configs.
        if (!currNtnMode) {
            if (mFeatureFlags.vzwAstSkyloFallback()) {
                plogd("updateLastNotifiedNtnModeAndNotify: not in NTN mode, clear per plmn "
                        + "configs");
                mCurrentSatellitePerPlmnConfigurations.remove(subId);
            }
        }
        if (!initialized || lastNotifiedNtnMode != currNtnMode) {
            if (!initialized) mInitialized.put(subId, true);
            mLastNotifiedNtnMode.put(subId, currNtnMode);
            phone.notifyCarrierRoamingNtnModeChanged(currNtnMode);
            updateLastNotifiedCarrierRoamingNtnSignalStrengthAndNotify(phone);
            logCarrierRoamingSatelliteSessionStats(phone, lastNotifiedNtnMode, currNtnMode);
            if (mIsNotificationShowing.get() && !currNtnMode) {
                dismissSatelliteNotification();
            }
        }
    }

    /**
     * map data policy to support unknown case at metrics
     * @param dataPolicy data support mode for the service type
     * @return corresponding value from {@link SatelliteConstants.SatelliteEntitlementServicePolicy}
     *
     */
    @SatelliteConstants.SatelliteEntitlementServicePolicy
    public int mapDataPolicyForMetrics(int dataPolicy) {
        switch (dataPolicy) {
            case CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED -> {
                return SatelliteConstants.SATELLITE_ENTITLEMENT_SERVICE_POLICY_RESTRICTED;
            }
            case CarrierConfigManager.SATELLITE_DATA_SUPPORT_BANDWIDTH_CONSTRAINED -> {
                return SatelliteConstants.SATELLITE_ENTITLEMENT_SERVICE_POLICY_CONSTRAINED;
            }
            case CarrierConfigManager.SATELLITE_DATA_SUPPORT_ALL -> {
                return SatelliteConstants.SATELLITE_ENTITLEMENT_SERVICE_POLICY_UNCONSTRAINED;
            }
        }
        return SatelliteConstants.SATELLITE_ENTITLEMENT_SERVICE_POLICY_UNKNOWN;
    }

    private int[] getSupportedSatelliteServicesOnSessionStart(List<Integer> supportedServices) {
        if (supportedServices == null || supportedServices.isEmpty()) {
            return new int[0];
        }

        return supportedServices.stream().mapToInt(Integer::intValue).toArray();
    }

    private void logCarrierRoamingSatelliteSessionStats(@NonNull Phone phone,
            boolean lastNotifiedNtnMode, boolean currNtnMode) {
        if (mIsDemoModeEnabled.get()) {
            plogd("logCarrierRoamingSatelliteSessionStats: return, demo mode is enabled");
            return;
        }
        int subId = phone.getSubId();
        int userId = Binder.getCallingUserHandle().getIdentifier();
        final long identity = Binder.clearCallingIdentity();
        List<String> satelliteApps = List.of();
        if (!lastNotifiedNtnMode && currNtnMode) {
            // Log satellite session start
            CarrierRoamingSatelliteSessionStats sessionStats =
                    CarrierRoamingSatelliteSessionStats.getInstance(subId);
            String satellitePlmn = Optional.ofNullable(phone)
                    .map(Phone::getServiceState)
                    .map(ServiceState::getOperatorNumeric)
                    .orElse("");
            int[] supported_satellite_services =
                    getSupportedSatelliteServicesOnSessionStart(
                            getSupportedSatelliteServicesForPlmn(subId,
                                    satellitePlmn));
            int dataPolicy = mapDataPolicyForMetrics(getSatelliteDataServicePolicyForPlmn(subId,
                    satellitePlmn));
            satelliteApps = getSatelliteDataOptimizedApps(userId);

            sessionStats.onSessionStart(phone.getCarrierId(), phone,
                    supported_satellite_services, dataPolicy, satelliteApps,
                    getSupportedConnectTypeMetrics(subId), getSessionConnectTypeMetrics(subId),
                    satellitePlmn, mFeatureFlags, isScreenOn(), mIsWifiConnected.get());
            mCarrierRoamingSatelliteSessionStatsMap.put(subId, sessionStats);
            mCarrierRoamingSatelliteControllerStats.onSessionStart(subId);
        } else if (lastNotifiedNtnMode && !currNtnMode) {
            // Log satellite session end
            CarrierRoamingSatelliteSessionStats sessionStats =
                    mCarrierRoamingSatelliteSessionStatsMap.get(subId);
            if (sessionStats != null) {
                satelliteApps = getSatelliteDataOptimizedApps(userId);
                sessionStats.onSessionEnd(subId, satelliteApps);
                mCarrierRoamingSatelliteSessionStatsMap.remove(subId);
            } else {
                plogd("logCarrierRoamingSatelliteSessionStats: sessionStats is null");
            }
            mCarrierRoamingSatelliteControllerStats.onSessionEnd(subId);
        }
        Binder.restoreCallingIdentity(identity);
    }

    private void evaluateCarrierRoamingNtnEligibilityChange() {
        registerForSatelliteCommunicationAccessStateChanged();

        if (isSatelliteEnabledOrBeingEnabled()) {
            plogd("evaluateCarrierRoamingNtnEligibilityChange: "
                    + "Skip eligibility check as satellite is enabled or being enabled");
            return;
        }

        boolean eligible = isCarrierRoamingNtnEligible(getSatellitePhone());
        plogd("evaluateCarrierRoamingNtnEligibilityChange: "
                + "isCarrierRoamingNtnEligible=" + eligible);

        if (eligible) {
            if (shouldStartNtnEligibilityHysteresisTimer(eligible)) {
                startNtnEligibilityHysteresisTimer();
            }
        } else {
            stopNtnEligibilityHysteresisTimer();
            updateLastNotifiedNtnEligibilityAndNotify(false);
        }
    }

    private boolean shouldStartNtnEligibilityHysteresisTimer(boolean eligible) {
        if (!eligible) {
            return false;
        }

        if (hasMessages(EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT)) {
            plogd("shouldStartNtnEligibilityHysteresisTimer: Timer is already running.");
            return false;
        }

        Boolean lastNotifiedNtnEligibility = getLastNotifiedNtnEligibility();
        if (lastNotifiedNtnEligibility != null && lastNotifiedNtnEligibility) {
            return false;
        }

        return true;
    }

    private void startNtnEligibilityHysteresisTimer() {
        Phone satellitePhone = getSatellitePhone();
        if (satellitePhone == null) {
            ploge("startNtnEligibilityHysteresisTimer: mSatellitePhone is null.");
            return;
        }

        int subId = getSelectedSatelliteSubId();
        long timeout = getCarrierSupportedSatelliteNotificationHysteresisTimeMillis(subId);
        plogd("startNtnEligibilityHysteresisTimer: sendMessageDelayed subId=" + subId
                    + ", phoneId=" + satellitePhone.getPhoneId() + ", timeout=" + timeout);
        sendMessageDelayed(obtainMessage(EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT),
                timeout);

    }

    private void stopNtnEligibilityHysteresisTimer() {
        if (hasMessages(EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT)) {
            removeMessages(EVENT_NOTIFY_NTN_ELIGIBILITY_HYSTERESIS_TIMED_OUT);
        }
    }

    private void updateLastNotifiedNtnEligibilityAndNotify(boolean currentNtnEligibility) {
        Phone satellitePhone = getSatellitePhone();
        if (satellitePhone == null) {
            ploge("notifyNtnEligibility: mSatellitePhone is null");
            return;
        }

        if (mOverrideNtnEligibility != null) {
            satellitePhone.notifyCarrierRoamingNtnEligibleStateChanged(currentNtnEligibility);
            return;
        }

        int selectedSatelliteSubId = getSelectedSatelliteSubId();
        plogd("notifyNtnEligibility: phoneId=" + satellitePhone.getPhoneId()
                + " currentNtnEligibility=" + currentNtnEligibility);
        Boolean lastNotifiedNtnEligibility = getLastNotifiedNtnEligibility();
        if (lastNotifiedNtnEligibility == null
                || lastNotifiedNtnEligibility != currentNtnEligibility) {
            setLastNotifiedNtnEligibility(currentNtnEligibility);
            satellitePhone.notifyCarrierRoamingNtnEligibleStateChanged(currentNtnEligibility);
            updateSatelliteSystemNotification(
                    selectedSatelliteSubId,
                    CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL,
                    currentNtnEligibility);
        }
    }

    /** Return last notified ntn eligibility. */
    public boolean getLastNotifiedNtnEligibility(@NonNull Phone phone) {
        int selectedSatelliteSubId = getSelectedSatelliteSubId();
        int subId = phone.getSubId();
        if (subId != selectedSatelliteSubId) {
            plogd(
                    "getLastNotifiedNtnEligibility: subId="
                            + subId
                            + " does not match selectedSatelliteSubId="
                            + selectedSatelliteSubId);
            return false;
        }

        Boolean lastNotifiedNtnEligibility = getLastNotifiedNtnEligibility();
        plogd("getLastNotifiedNtnEligibility: return " + lastNotifiedNtnEligibility);
        if (lastNotifiedNtnEligibility == null) {
            return false;
        }
        return lastNotifiedNtnEligibility;
    }

    private void setLastNotifiedNtnEligibility(boolean eligibility) {
        if (mLastNotifiedNtnEligibility == null) {
            mLastNotifiedNtnEligibility = new AtomicBoolean(eligibility);
        } else {
            mLastNotifiedNtnEligibility.set(eligibility);
        }
    }

    private Boolean getLastNotifiedNtnEligibility() {
        if (mLastNotifiedNtnEligibility == null) {
            return null;
        }
        return mLastNotifiedNtnEligibility.get();
    }

    private long getSatelliteConnectionHysteresisTimeMillis(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        return (config.getInt(
                KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT) * 1000L);
    }

    private long getCarrierSupportedSatelliteNotificationHysteresisTimeMillis(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        return (config.getInt(
                KEY_CARRIER_SUPPORTED_SATELLITE_NOTIFICATION_HYSTERESIS_SEC_INT) * 1000L);
    }

    private void persistOemEnabledSatelliteProvisionStatus(boolean isProvisioned) {
        plogd("persistOemEnabledSatelliteProvisionStatus: isProvisioned=" + isProvisioned);
        int subId = getNtnOnlySubscriptionId();
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            try {
                mSubscriptionManagerService.setIsSatelliteProvisionedForNonIpDatagram(subId,
                        isProvisioned);
                plogd("persistOemEnabledSatelliteProvisionStatus: subId=" + subId);
            } catch (IllegalArgumentException | SecurityException ex) {
                ploge("setIsSatelliteProvisionedForNonIpDatagram: subId=" + subId + ", ex="
                        + ex);
            }
        } else {
            plogd("persistOemEnabledSatelliteProvisionStatus: INVALID_SUBSCRIPTION_ID");
        }
    }

    @Nullable
    private boolean getPersistedDeviceProvisionStatus() {
        plogd("getPersistedDeviceProvisionStatus");
        int subId = getNtnOnlySubscriptionId();
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (mSubscriptionManagerService.isSatelliteProvisionedForNonIpDatagram(subId)) {
                return true;
            }
        }

        List<SubscriptionInfo> activeSubscriptionInfoList =
                mSubscriptionManagerService.getActiveSubscriptionInfoList(
                        mContext.getOpPackageName(), mContext.getAttributionTag(), true);
        for (SubscriptionInfo info : activeSubscriptionInfoList) {
            if (info.isSatelliteESOSSupported()) {
                if (mSubscriptionManagerService.isSatelliteProvisionedForNonIpDatagram(
                        info.getSubscriptionId())) {
                    Pair<String, Integer> subscriberIdPair = getSubscriberIdAndType(
                            mSubscriptionManagerService.getSubscriptionInfo(subId));
                    String subscriberId = subscriberIdPair.first;
                    mProvisionedSubscriberId.put(subscriberId, true);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean loadSatelliteSharedPreferences() {
        if (mSharedPreferences == null) {
            try {
                mSharedPreferences =
                        mContext.getSharedPreferences(SATELLITE_SHARED_PREF,
                                Context.MODE_PRIVATE);
            } catch (Exception e) {
                ploge("loadSatelliteSharedPreferences: Cannot get default "
                        + "shared preferences, e=" + e);
                return false;
            }
        }
        return true;
    }

    private void handleIsSatelliteProvisionedDoneEvent(@NonNull AsyncResult ar) {
        logd("handleIsSatelliteProvisionedDoneEvent:");
        SatelliteControllerHandlerRequest request = (SatelliteControllerHandlerRequest) ar.userObj;

        Bundle bundle = new Bundle();
        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_PROVISIONED,
                Boolean.TRUE.equals(isDeviceProvisioned()));
        ((ResultReceiver) request.argument).send(SATELLITE_RESULT_SUCCESS, bundle);
        decrementResultReceiverCount("SC:requestIsSatelliteProvisioned");
    }

    private long getWaitForSatelliteEnablingResponseTimeoutMillis() {
        return mContext.getResources().getInteger(
                R.integer.config_wait_for_satellite_enabling_response_timeout_millis);
    }

    private long getWaitForCellularModemOffTimeoutMillis() {
        return mContext.getResources().getInteger(
                R.integer.config_satellite_wait_for_cellular_modem_off_timeout_millis);
    }

    private void startWaitForCellularModemOffTimer() {
        if (hasMessages(EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT)) {
            plogd("startWaitForCellularModemOffTimer: the timer was already started");
            return;
        }
        long timeoutMillis = getWaitForCellularModemOffTimeoutMillis();
        plogd("Start timer to wait for cellular modem OFF state, timeoutMillis="
                + timeoutMillis);
        sendMessageDelayed(obtainMessage(EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT),
                timeoutMillis);
    }

    private void stopWaitForCellularModemOffTimer() {
        plogd("Stop timer to wait for cellular modem OFF state");
        removeMessages(EVENT_WAIT_FOR_CELLULAR_MODEM_OFF_TIMED_OUT);
    }

    private void startWaitForSatelliteEnablingResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        if (hasMessages(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT, argument)) {
            plogd("WaitForSatelliteEnablingResponseTimer of request ID "
                    + argument.requestId + " was already started");
            return;
        }
        plogd("Start timer to wait for response of the satellite enabling request ID="
                + argument.requestId + ", enableSatellite=" + argument.enableSatellite
                + ", mWaitTimeForSatelliteEnablingResponse="
                + mWaitTimeForSatelliteEnablingResponse.get());
        sendMessageDelayed(obtainMessage(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT,
                argument), mWaitTimeForSatelliteEnablingResponse.get());
    }

    private void stopWaitForSatelliteEnablingResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        plogd("Stop timer to wait for response of the satellite enabling request ID="
                + argument.requestId + ", enableSatellite=" + argument.enableSatellite);
        removeMessages(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT, argument);
    }

    private boolean shouldProcessEventSetSatelliteEnabledDone(
            @NonNull RequestSatelliteEnabledArgument argument) {
        if (hasMessages(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT, argument)) {
            return true;
        }
        return false;
    }

    private void startWaitForUpdateSystemSelectionChannelsResponseTimer(
            @NonNull UpdateSystemSelectionChannelsArgument argument) {
        if (hasMessages(
                EVENT_WAIT_FOR_UPDATE_SYSTEM_SELECTION_CHANNELS_RESPONSE_TIMED_OUT, argument)) {
            plogd("WaitForUpdateSystemSelectionChannelsResponseTimer of request ID "
                    + argument.requestId + " was already started");
            return;
        }
        plogd("Start timer to wait for response of the system selection channels update request"
                + " ID=" + argument.requestId + ", mWaitTimeForSatelliteEnablingResponse="
                + mWaitTimeForSatelliteEnablingResponse.get());
        sendMessageDelayed(
                obtainMessage(EVENT_WAIT_FOR_UPDATE_SYSTEM_SELECTION_CHANNELS_RESPONSE_TIMED_OUT,
                        argument), mWaitTimeForSatelliteEnablingResponse.get());
    }

    private void stopWaitForUpdateSystemSelectionChannelsResponseTimer(
            @NonNull UpdateSystemSelectionChannelsArgument argument) {
        plogd("Stop timer to wait for response of the system selection channels"
                + " update request ID=" + argument.requestId);
        removeMessages(
                EVENT_WAIT_FOR_UPDATE_SYSTEM_SELECTION_CHANNELS_RESPONSE_TIMED_OUT, argument);
    }

    private boolean shouldProcessEventUpdateSystemSelectionChannelsDone(
            @NonNull UpdateSystemSelectionChannelsArgument argument) {
        if (hasMessages(
                EVENT_WAIT_FOR_UPDATE_SYSTEM_SELECTION_CHANNELS_RESPONSE_TIMED_OUT, argument)) {
            return true;
        }
        return false;
    }

    private void startWaitForUpdateSatelliteEnableAttributesResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        if (hasMessages(EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT,
                argument)) {
            plogd("WaitForUpdateSatelliteEnableAttributesResponseTimer of request ID "
                    + argument.requestId + " was already started");
            return;
        }
        plogd("Start timer to wait for response of the update satellite enable attributes"
                + " request ID=" + argument.requestId
                + ", enableSatellite=" + argument.enableSatellite
                + ", mWaitTimeForSatelliteEnablingResponse="
                + mWaitTimeForSatelliteEnablingResponse.get());
        sendMessageDelayed(obtainMessage(
                EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT,
                argument), mWaitTimeForSatelliteEnablingResponse.get());
    }

    private void stopWaitForUpdateSatelliteEnableAttributesResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        plogd("Stop timer to wait for response of the enable attributes update request ID="
                + argument.requestId + ", enableSatellite=" + argument.enableSatellite);
        removeMessages(
                EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT, argument);
    }

    private boolean shouldProcessEventUpdateSatelliteEnableAttributesDone(
            @NonNull RequestSatelliteEnabledArgument argument) {
        if (hasMessages(EVENT_WAIT_FOR_UPDATE_SATELLITE_ENABLE_ATTRIBUTES_RESPONSE_TIMED_OUT,
                argument)) {
            return true;
        }
        return false;
    }

    private void handleEventWaitForSatelliteEnablingResponseTimedOut(
            @NonNull RequestSatelliteEnabledArgument argument) {
        plogw("Timed out to wait for response of the satellite enabling request ID="
                + argument.requestId + ", enableSatellite=" + argument.enableSatellite);

        argument.callback.accept(SATELLITE_RESULT_MODEM_TIMEOUT);
        if (argument.enableSatellite) {
            resetSatelliteEnabledRequest();
            abortSatelliteEnableAttributesUpdateRequest(SATELLITE_RESULT_REQUEST_ABORTED);
            if (getSatelliteDisabledRequest() == null) {
                IIntegerConsumer callback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        plogd("handleEventWaitForSatelliteEnablingResponseTimedOut: "
                                + "disable satellite result=" + result);
                    }
                };
                Consumer<Integer> result =
                        FunctionalUtils.ignoreRemoteException(callback::accept);

                RequestSatelliteEnabledArgument request = new RequestSatelliteEnabledArgument(
                        false, false, false, result);
                setSatelliteDisabledRequest(request);
                sendRequestAsync(CMD_SET_SATELLITE_ENABLED, request, null);
            }

            mControllerMetricsStats.reportServiceEnablementFailCount(argument.enableDemoMode);
            mSessionMetricsStats.setInitializationResult(SATELLITE_RESULT_MODEM_TIMEOUT)
                    .setSatelliteTechnology(getSupportedNtnRadioTechnology())
                    .setInitializationProcessingTime(
                            getElapsedRealtime() - mSessionProcessingTimeStamp.get())
                    .setIsDemoMode(mIsDemoModeEnabled.get())
                    .setCarrierId(getSatelliteCarrierId())
                    .setSupportedConnectionMode(getSupportedConnectTypeMetrics())
                    .setSessionConnectionMode(getSessionConnectTypeMetrics())
                    .setIsNtnOnlyCarrier(isNtnOnlyCarrier())
                    .reportSessionMetrics();
        } else {
            resetSatelliteDisabledRequest();
            mControllerMetricsStats.onSatelliteDisabled();
            mSessionMetricsStats.setTerminationResult(SATELLITE_RESULT_MODEM_TIMEOUT)
                    .setSatelliteTechnology(getSupportedNtnRadioTechnology())
                    .setTerminationProcessingTime(
                            getElapsedRealtime() - mSessionProcessingTimeStamp.get())
                    .setSessionDurationSec(calculateSessionDurationTimeSec())
                    .reportSessionMetrics();
        }
        notifyEnablementFailedToSatelliteSessionController(argument.enableSatellite);
        mSessionStartTimeStamp.set(0);
        mSessionProcessingTimeStamp.set(0);
    }

    private void handleCmdUpdateNtnSignalStrengthReporting(boolean shouldReport) {
        if (!isSatelliteEnabledOrBeingEnabled()) {
            plogd("handleCmdUpdateNtnSignalStrengthReporting: ignore request, satellite is "
                    + "disabled");
            return;
        }

        mLatestRequestedStateForNtnSignalStrengthReport.set(shouldReport);
        if (mIsModemEnabledReportingNtnSignalStrength.get() == shouldReport) {
            plogd("handleCmdUpdateNtnSignalStrengthReporting: ignore request. "
                    + "mIsModemEnabledReportingNtnSignalStrength="
                    + mIsModemEnabledReportingNtnSignalStrength.get());
            return;
        }

        updateNtnSignalStrengthReporting(shouldReport);
    }

    private void updateNtnSignalStrengthReporting(boolean shouldReport) {
        SatelliteControllerHandlerRequest request = new SatelliteControllerHandlerRequest(
                shouldReport, SatelliteServiceUtils.getPhone());
        Message onCompleted = obtainMessage(EVENT_UPDATE_NTN_SIGNAL_STRENGTH_REPORTING_DONE,
                request);
        if (shouldReport) {
            plogd("updateNtnSignalStrengthReporting: startSendingNtnSignalStrength");
            mSatelliteModemInterface.startSendingNtnSignalStrength(onCompleted);
        } else {
            plogd("updateNtnSignalStrengthReporting: stopSendingNtnSignalStrength");
            mSatelliteModemInterface.stopSendingNtnSignalStrength(onCompleted);
        }
    }

    /**
     * This API can be used by only CTS to override the cached value for the device overlay config
     * value : config_send_satellite_datagram_to_modem_in_demo_mode, which determines whether
     * outgoing satellite datagrams should be sent to modem in demo mode.
     *
     * @param shouldSendToModemInDemoMode Whether send datagram in demo mode should be sent to
     * satellite modem or not.
     *
     * @return {@code true} if the operation is successful, {@code false} otherwise.
     */
    public boolean setShouldSendDatagramToModemInDemoMode(boolean shouldSendToModemInDemoMode) {
        if (!isMockModemAllowed()) {
            plogd("setShouldSendDatagramToModemInDemoMode: mock modem not allowed.");
            return false;
        }

        mDatagramController.setShouldSendDatagramToModemInDemoMode(shouldSendToModemInDemoMode);
        return true;
    }

    private void determineAutoConnectSystemNotification() {
        Pair<Boolean, Integer> isNtn = isUsingNonTerrestrialNetworkViaCarrier();
        boolean suppressSatelliteNotification = mSharedPreferences.getBoolean(
                SATELLITE_SYSTEM_NOTIFICATION_DONE_KEY, false);
        if (suppressSatelliteNotification) {
            // System already displayed the notification and user interacted with it.
            // System will show notification again after 30 days.
            long lastSetTimestamp = mSharedPreferences.getLong(
                    SATELLITE_SYSTEM_NOTIFICATION_TIME, 0L);
            logv("determineAutoConnectSystemNotification lastSetTimestamp = " + lastSetTimestamp);
            long currentTime = System.currentTimeMillis();
            int subId = getSelectedSatelliteSubId();
            long throttleTime = getNotificationDisplayThrottleTimeout(subId);
            if (lastSetTimestamp == 0L || currentTime - lastSetTimestamp >= throttleTime) {
                // Reset the flag and update the timestamp
                logd("determineAutoConnectSystemNotification: reset preference data");
                suppressSatelliteNotification = false;
                mSharedPreferences.edit().putBoolean(SATELLITE_SYSTEM_NOTIFICATION_DONE_KEY,
                        false).remove(SATELLITE_SYSTEM_NOTIFICATION_TIME).apply();
            }
        }
        if (DEBUG) {
            logd("determineAutoConnectSystemNotification: isNtn.first = " + isNtn.first
                    + " IsNotiToShow = " + !suppressSatelliteNotification
                    + " mIsNotificationShowing = " + mIsNotificationShowing.get());
        }
        if (isNtn.first) {
            if (!suppressSatelliteNotification
                    && getCarrierRoamingNtnConnectType(isNtn.second)
                    == CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC) {
                logd("determineAutoConnectSystemNotification: updating notification as we are in"
                        + "CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC session");
                updateSatelliteSystemNotification(
                        isNtn.second,
                        CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC,
                        /*visible*/ true);
            }
        } else if (mIsNotificationShowing.get()
                && !isSatelliteConnectedViaCarrierWithinHysteresisTime().first) {
            // Dismiss the notification if it is still displaying.
            dismissSatelliteNotification();
        }
    }

    private void dismissSatelliteNotification() {
        mIsNotificationShowing.set(false);
        updateSatelliteSystemNotification(-1, -1, /*visible*/ false);
    }

    /**
     * Checks if satellite system notifications are enabled
     *
     * @param connectType the connect type of the session. Note that it
     * should be either {@link CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC} or {@link
     * CARRIER_ROAMING_NTN_CONNECT_MANUAL} only.
     */
    public boolean isSatelliteSystemNotificationsEnabled(int connectType) {
        if (connectType == CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC) {
            logd("isSatelliteSystemNotificationsEnabled: automatic connect type. "
                    + "Therefore, notification is enabled.");
            return true;
        }
        boolean notifySatelliteAvailabilityEnabled =
            mContext.getResources().getBoolean(R.bool.config_satellite_should_notify_availability);
        Boolean isSatelliteSupported = getIsSatelliteSupported();
        if (isSatelliteSupported == null) {
            return false;
        }
        int subId = getSelectedSatelliteSubId();
        SubscriptionInfo subInfo = mSubscriptionManagerService.getSubscriptionInfo(subId);
        logd("isSatelliteSystemNotificationsEnabled: SatelliteSubId = " + subId);
        return notifySatelliteAvailabilityEnabled
                && isSatelliteSupported
                && isValidSubscriptionId(subId)
                && ((isSatelliteSupportedViaCarrier(subId)
                && ((getCarrierRoamingNtnConnectType(subId) == CARRIER_ROAMING_NTN_CONNECT_MANUAL
                || (mFeatureFlags.vzwAstSkyloFallback()
                && getCarrierRoamingNtnConnectType(subId) == CARRIER_ROAMING_NTN_CONNECT_HYBRID))))
                || subInfo.isOnlyNonTerrestrialNetwork());
    }

    private boolean isDataServiceSupported(int subId) {
        int[] services = getSupportedServicesOnCarrierRoamingNtn(subId);
        return ArrayUtils.contains(services, NetworkRegistrationInfo.SERVICE_TYPE_DATA);
    }

    /**
     * Update the system notification to reflect the current satellite status, that's either already
     * connected OR needs to be manually enabled. The device should only display one notification at
     * a time to prevent confusing the user, so the same NOTIFICATION_CHANNEL and NOTIFICATION_ID
     * are used.
     *
     * @param subId The subId that provides the satellite connection.
     * @param carrierRoamingNtnConnectType {@link CarrierConfigManager
     *     .CARRIER_ROAMING_NTN_CONNECT_TYPE}
     * @param visible {@code true} to show the notification, {@code false} to cancel it.
     */
    private void updateSatelliteSystemNotification(
            int subId,
            @CARRIER_ROAMING_NTN_CONNECT_TYPE int carrierRoamingNtnConnectType,
            boolean visible) {
        if (carrierRoamingNtnConnectType != -1
                && !isSatelliteSystemNotificationsEnabled(carrierRoamingNtnConnectType)) {
            plogd("updateSatelliteSystemNotification: satellite notifications are not enabled.");
            return;
        }

        plogd(
                "updateSatelliteSystemNotification subId="
                        + subId
                        + ", carrierRoamingNtnConnectType="
                        + SatelliteServiceUtils.carrierRoamingNtnConnectTypeToString(
                                carrierRoamingNtnConnectType)
                        + ", visible="
                        + visible);
        final NotificationChannel notificationChannel =
                new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        NOTIFICATION_CHANNEL,
                        NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel.setSound(null, null);
        NotificationManager notificationManager =
                mContext.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            ploge("updateSatelliteSystemNotification: notificationManager is null");
            return;
        }
        if (!visible) { // Cancel if any.
            notificationManager.cancelAsUser(NOTIFICATION_TAG, NOTIFICATION_ID, UserHandle.ALL);
            return;
        }
        notificationManager.createNotificationChannel(notificationChannel);

        int title = R.string.satellite_notification_title;
        int summary = R.string.satellite_notification_summary;
        if (carrierRoamingNtnConnectType == CARRIER_ROAMING_NTN_CONNECT_MANUAL) {
            plogd("updateSatelliteSystemNotification: carrierRoamingNtnConnectType is manual");
            title = R.string.satellite_notification_manual_title;
            summary = R.string.satellite_notification_manual_summary;
        } else if (carrierRoamingNtnConnectType == CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC
                && isDataServiceSupported(subId)) {
            plogd("updateSatelliteSystemNotification: carrierRoamingNtnConnectType is automatic"
                    + " and data service is supported");
            // In Auto Connected mode, if data services supported, show data supported summary
            summary = R.string.satellite_notification_summary_with_data;
        }

        Notification.Builder notificationBuilder = new Notification.Builder(mContext,
                NOTIFICATION_CHANNEL_ID)
                .setContentTitle(mContext.getResources().getString(title))
                .setContentText(mContext.getResources().getString(summary))
                .setSmallIcon(R.drawable.ic_android_satellite_24px)
                .setAutoCancel(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        // Intent for `Open Messages` [Button 1]
        Intent openMessageIntent = new Intent();
        openMessageIntent.setAction(OPEN_MESSAGE_BUTTON);
        PendingIntent openMessagePendingIntent = PendingIntent.getBroadcast(mContext, 0,
                openMessageIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Action actionOpenMessage = new Notification.Action.Builder(0,
                mContext.getResources().getString(R.string.satellite_notification_open_message),
                openMessagePendingIntent)
                .setAuthenticationRequired(true)
                .build();
        notificationBuilder.addAction(actionOpenMessage);   // Handle `Open Messages` button

        // Button for `How it works` [Button 2]
        Intent howItWorksIntent = new Intent();
        howItWorksIntent.setAction(HOW_IT_WORKS_BUTTON);
        howItWorksIntent.putExtra("SUBID", subId);
        PendingIntent howItWorksPendingIntent = PendingIntent.getBroadcast(mContext, 0,
                howItWorksIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Action actionHowItWorks = new Notification.Action.Builder(0,
                mContext.getResources().getString(R.string.satellite_notification_how_it_works),
                howItWorksPendingIntent)
                .setAuthenticationRequired(true)
                .build();
        notificationBuilder.addAction(actionHowItWorks);    // Handle `How it works` button

        // Intent for clicking the main notification body
        Intent notificationClickIntent = new Intent(ACTION_NOTIFICATION_CLICK);
        PendingIntent notificationClickPendingIntent = PendingIntent.getBroadcast(mContext, 0,
                notificationClickIntent, PendingIntent.FLAG_IMMUTABLE);
        notificationBuilder.setContentIntent(
                notificationClickPendingIntent); // Handle notification body click

        // Intent for dismissing/swiping the notification
        Intent deleteIntent = new Intent(ACTION_NOTIFICATION_DISMISS);
        PendingIntent deletePendingIntent = PendingIntent.getBroadcast(mContext, 0, deleteIntent,
                PendingIntent.FLAG_IMMUTABLE);
        notificationBuilder.setDeleteIntent(
                deletePendingIntent);  // Handle notification swipe/dismiss

        notificationManager.notifyAsUser(NOTIFICATION_TAG, NOTIFICATION_ID,
                notificationBuilder.build(), UserHandle.ALL);

        // The Intent filter is to receive the above four events.
        IntentFilter filter = new IntentFilter();
        filter.addAction(OPEN_MESSAGE_BUTTON);
        filter.addAction(HOW_IT_WORKS_BUTTON);
        filter.addAction(ACTION_NOTIFICATION_CLICK);
        filter.addAction(ACTION_NOTIFICATION_DISMISS);
        mContext.registerReceiver(mNotificationInteractionBroadcastReceiver, filter,
                Context.RECEIVER_EXPORTED);

        mIsNotificationShowing.set(true);
        mCarrierRoamingSatelliteControllerStats.reportCountOfSatelliteNotificationDisplayed(subId);
        mCarrierRoamingSatelliteControllerStats.reportCarrierId(subId);
        mSessionMetricsStats.addCountOfSatelliteNotificationDisplayed();
    }

    private final BroadcastReceiver mNotificationInteractionBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent receivedIntent) {
                    String intentAction = receivedIntent.getAction();
                    if (TextUtils.isEmpty(intentAction)) {
                        loge("Received empty action from the notification");
                        return;
                    }
                    if (DBG) {
                        plogd("Notification Broadcast recvd action = "
                                + receivedIntent.getAction());
                    }
                    boolean closeStatusBar = true;
                    switch (intentAction) {
                        case OPEN_MESSAGE_BUTTON -> {
                            // Add action to invoke message application.
                            // getDefaultSmsPackage and getLaunchIntentForPackage are nullable.
                            Optional<Intent> nullableIntent = Optional.ofNullable(
                                    Telephony.Sms.getDefaultSmsPackage(context)).flatMap(
                                    packageName -> {
                                        PackageManager pm = context.getPackageManager();
                                        return Optional.ofNullable(
                                                pm.getLaunchIntentForPackage(packageName));
                                    });
                            // If nullableIntent is null, create new Intent for most common way to
                            // invoke
                            // message app.
                            Intent finalIntent = nullableIntent.map(intent -> {
                                // Invoke the home screen of default message application.
                                intent.setAction(Intent.ACTION_MAIN);
                                intent.addCategory(Intent.CATEGORY_HOME);
                                return intent;
                            }).orElseGet(() -> {
                                ploge("showSatelliteSystemNotification: no default sms package "
                                        + "name, Invoke default sms compose window instead");
                                Intent newIntent = new Intent(Intent.ACTION_VIEW);
                                newIntent.setData(Uri.parse("sms:"));
                                return newIntent;
                            });
                            context.startActivity(finalIntent);
                        }
                        case HOW_IT_WORKS_BUTTON -> {
                            int subId = receivedIntent.getIntExtra("SUBID", -1);
                            Intent intentSatelliteSetting = new Intent(ACTION_SATELLITE_SETTING);
                            intentSatelliteSetting.putExtra("sub_id", subId);
                            intentSatelliteSetting.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(intentSatelliteSetting);

                        }
                        case ACTION_NOTIFICATION_DISMISS -> closeStatusBar = false;
                    }
                    // Note : ACTION_NOTIFICATION_DISMISS is not required to handled
                    dismissNotificationAndUpdatePref(closeStatusBar);
                }
            };

    private void dismissNotificationAndUpdatePref(boolean closeStatusBar) {
        dismissSatelliteNotification();
        if (closeStatusBar) {
            // Collapse the status bar once user interact with notification.
            StatusBarManager statusBarManager = mContext.getSystemService(StatusBarManager.class);
            if (statusBarManager != null) {
                statusBarManager.collapsePanels();
            }
        }
        // update the sharedpref only when user interacted with the notification.
        mSharedPreferences.edit().putBoolean(SATELLITE_SYSTEM_NOTIFICATION_DONE_KEY, true).apply();
        mSharedPreferences.edit().putLong(SATELLITE_SYSTEM_NOTIFICATION_TIME,
                System.currentTimeMillis()).apply();
        mContext.unregisterReceiver(mNotificationInteractionBroadcastReceiver);
    }

    private void resetCarrierRoamingSatelliteModeParams() {
        for (Phone phone : PhoneFactory.getPhones()) {
            resetCarrierRoamingSatelliteModeParams(phone.getSubId());
        }
    }

    private void resetCarrierRoamingSatelliteModeParams(int subId) {
        mLastSatelliteDisconnectedTimesMillis.remove(subId);
        mSatModeCapabilitiesForCarrierRoaming.remove(subId);
        mWasSatelliteConnectedViaCarrier.put(subId, false);
    }

    /**
     * Read carrier config items for satellite
     *
     * @param subId Associated subscription ID
     * @return PersistableBundle including carrier config values
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @NonNull
    protected PersistableBundle getPersistableBundle(int subId) {
        PersistableBundle config = mCarrierConfigArray.get(subId);
        if (config == null) {
            config = getConfigForSubId(subId);
            mCarrierConfigArray.put(subId, config);
        }
        return config;
    }

    // Should be invoked only when session termination done or session termination failed.
    private int calculateSessionDurationTimeSec() {
        return (int) (
                (getElapsedRealtime() - mSessionStartTimeStamp.get()
                - mSessionMetricsStats.getSessionInitializationProcessingTimeMillis()
                - mSessionMetricsStats.getSessionTerminationProcessingTimeMillis()) / 1000);
    }

    private void notifyEnablementFailedToSatelliteSessionController(boolean enabled) {
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.onSatelliteEnablementFailed(enabled);
        } else {
            ploge("notifyEnablementFailedToSatelliteSessionController: mSatelliteSessionController"
                    + " is not initialized yet");
        }
    }

    private void abortSatelliteEnableRequest(@SatelliteManager.SatelliteResult int resultCode) {
        RequestSatelliteEnabledArgument satelliteEnabledRequest = getSatelliteEnabledRequest();
        if (satelliteEnabledRequest != null) {
            plogw("abortSatelliteEnableRequest");
            if (resultCode == SATELLITE_RESULT_SUCCESS) {
                resultCode = SATELLITE_RESULT_REQUEST_ABORTED;
            }
            satelliteEnabledRequest.callback.accept(resultCode);
            stopWaitForSatelliteEnablingResponseTimer(satelliteEnabledRequest);
            setSatelliteEnabledRequest(null);
        }
    }

    private void abortSatelliteDisableRequest(@SatelliteManager.SatelliteResult int resultCode) {
        RequestSatelliteEnabledArgument satelliteDisabledRequest = getSatelliteDisabledRequest();
        if (satelliteDisabledRequest != null) {
            plogd("abortSatelliteDisableRequest");
            satelliteDisabledRequest.callback.accept(resultCode);
            stopWaitForSatelliteEnablingResponseTimer(satelliteDisabledRequest);
            setSatelliteDisabledRequest(null);
        }
    }

    private void abortSatelliteEnableAttributesUpdateRequest(
            @SatelliteManager.SatelliteResult int resultCode) {
        RequestSatelliteEnabledArgument satelliteEnableAttributesUpdateRequest =
                getSatelliteEnableAttributesUpdateRequest();
        if (satelliteEnableAttributesUpdateRequest != null) {
            plogd("abortSatelliteEnableAttributesUpdateRequest");
            if (resultCode == SATELLITE_RESULT_SUCCESS) {
                resultCode = SATELLITE_RESULT_REQUEST_ABORTED;
            }
            satelliteEnableAttributesUpdateRequest.callback.accept(resultCode);
            stopWaitForUpdateSatelliteEnableAttributesResponseTimer(
                    satelliteEnableAttributesUpdateRequest);
            setSatelliteEnableAttributesUpdateRequest(null);
        }
    }

    private void stopWaitForEnableResponseTimers() {
        plogd("stopWaitForEnableResponseTimers");
        removeMessages(EVENT_WAIT_FOR_SATELLITE_ENABLING_RESPONSE_TIMED_OUT);
    }

    private long getDemoPointingAlignedDurationMillisFromResources() {
        long durationMillis = 15000L;
        try {
            durationMillis = mContext.getResources().getInteger(
                    R.integer.config_demo_pointing_aligned_duration_millis);
        } catch (Resources.NotFoundException ex) {
            loge("getPointingAlignedDurationMillis: ex=" + ex);
        }

        return durationMillis;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public long getDemoPointingAlignedDurationMillis() {
        return mDemoPointingAlignedDurationMillis.get();
    }

    private long getDemoPointingNotAlignedDurationMillisFromResources() {
        long durationMillis = 30000L;
        try {
            durationMillis = mContext.getResources().getInteger(
                    R.integer.config_demo_pointing_not_aligned_duration_millis);
        } catch (Resources.NotFoundException ex) {
            loge("getPointingNotAlignedDurationMillis: ex=" + ex);
        }

        return durationMillis;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public long getDemoPointingNotAlignedDurationMillis() {
        return mDemoPointingNotAlignedDurationMillis.get();
    }

    /** Returns {@code true} if WWAN is in service, else {@code false}.*/
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean getWwanIsInService(@NonNull ServiceState serviceState) {
        List<NetworkRegistrationInfo> nriList = serviceState
                .getNetworkRegistrationInfoListForTransportType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        for (NetworkRegistrationInfo nri : nriList) {
            if (nri.isInService()) {
                logv("getWwanIsInService: return true");
                return true;
            }
        }

        logv("getWwanIsInService: return false");
        return false;
    }

    private static void logv(@NonNull String log) {
        Log.v(TAG, log);
    }

    private static void logd(@NonNull String log) {
        Log.d(TAG, log);
    }

    private static void logw(@NonNull String log) {
        Log.w(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Log.e(TAG, log);
    }

    private void plogd(@NonNull String log) {
        Log.d(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.debug(TAG, log);
        }
    }

    private void plogw(@NonNull String log) {
        Log.w(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.warn(TAG, log);
        }
    }

    private void ploge(@NonNull String log) {
        Log.e(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.error(TAG, log);
        }
    }

    private void plogv(@NonNull String log) {
        Log.v(TAG, log);
        if (mPersistentLogger != null) {
            mPersistentLogger.debug(TAG, log);
        }
    }

    private void handlePersistentLoggingOnSessionStart(RequestSatelliteEnabledArgument argument) {
        if (mPersistentLogger == null) {
            return;
        }
        if (argument.isEmergency) {
            DropBoxManagerLoggerBackend.getInstance(mContext).setLoggingEnabled(true);
        }
    }

    private void handlePersistentLoggingOnSessionEnd(boolean isEmergency) {
        if (mPersistentLogger == null) {
            return;
        }
        DropBoxManagerLoggerBackend loggerBackend =
                DropBoxManagerLoggerBackend.getInstance(mContext);
        // Flush persistent satellite logs on eSOS session end
        if (isEmergency) {
            loggerBackend.flushAsync();
        }
        // Also turn off persisted logging until new session is started
        loggerBackend.setLoggingEnabled(false);
    }

    /**
     * Set last emergency call time to the current time.
     */
    public void setLastEmergencyCallTime() {
        mLastEmergencyCallTime.set(getElapsedRealtime());
        plogd("mLastEmergencyCallTime=" + mLastEmergencyCallTime.get());
    }

    /**
     * Check if satellite is in emergency mode.
     */
    public boolean isInEmergencyMode() {
        if (mLastEmergencyCallTime.get() == 0) {
            plogd("mLastEmergencyCallTime is 0, isInEmergencyMode() return false");
            return false;
        }

        long currentTime = getElapsedRealtime();
        if ((currentTime - mLastEmergencyCallTime.get())
                <= mSatelliteEmergencyModeDurationMillis.get()) {
            plogd("Satellite is in emergency mode");
            return true;
        }

        plogd("isInEmergencyMode() return false");
        return false;
    }

    private long getSatelliteEmergencyModeDurationFromOverlayConfig(@NonNull Context context) {
        Integer duration = DEFAULT_SATELLITE_EMERGENCY_MODE_DURATION_SECONDS;
        try {
            duration = context.getResources().getInteger(com.android.internal.R.integer
                    .config_satellite_emergency_mode_duration);
        } catch (Resources.NotFoundException ex) {
            ploge("getSatelliteEmergencyModeDurationFromOverlayConfig: got ex=" + ex);
        }
        return TimeUnit.SECONDS.toMillis(duration);
    }

    private long getEvaluateEsosProfilesPrioritizationDurationMillis() {
        return TimeUnit.MINUTES.toMillis(1);
    }

    /**
     * Calculate priority
     * 1. Active eSOS profiles are higher priority than inactive eSOS profiles.
     * 2. Carrier Enabled eSOS profile is higher priority than OEM enabled eSOS profile.
     * 3. Among active carrier eSOS profiles user selected(default SMS SIM) eSOS profile will be
     * the highest priority.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected void evaluateESOSProfilesPrioritization() {
        if (isSatelliteEnabledOrBeingEnabled()) {
            plogd("evaluateESOSProfilesPrioritization: Skip evaluation as satellite is enabled "
                    + "or being enabled");
            return;
        }

        boolean isChanged = false;
        List<SubscriptionInfo> allSubInfos = mSubscriptionManagerService.getAllSubInfoList(
                mContext.getOpPackageName(), mContext.getAttributionTag());
        // Key : priority - lower value has higher priority; Value : List<SubscriptionInfo>
        TreeMap<Integer, List<SubscriptionInfo>> newSubsInfoListPerPriority = new TreeMap<>();
        plogd("evaluateESOSProfilesPrioritization: allSubInfos.size()=" + allSubInfos.size());
        for (SubscriptionInfo info : allSubInfos) {
            int subId = info.getSubscriptionId();
            boolean isActive = info.isActive();
            boolean isDefaultSmsSubId =
                    mSubscriptionManagerService.getDefaultSmsSubId() == subId;
            boolean isNtnOnly = info.isOnlyNonTerrestrialNetwork();
            boolean isESOSSupported = info.isSatelliteESOSSupported();
            boolean isCarrierSatelliteHigherPriority =
                    isCarrierSatelliteHigherPriority(info);
            if (!isNtnOnly && !isESOSSupported) {
                continue;
            }
            if (!isActive && !isNtnOnly) {
                continue;
            }
            if (!isNtnOnly && !isCarrierConfigLoaded(subId)) {
                // Skip to add priority list if the carrier config is not loaded properly
                // for the given carrier subscription.
                continue;
            }

            int keyPriority = (isESOSSupported && isActive && isDefaultSmsSubId
                    && isCarrierSatelliteHigherPriority)
                    ? 0 : (isESOSSupported && isActive &&
                    isCarrierSatelliteHigherPriority)
                    ? 1 : (isNtnOnly)
                    ? 2 : (isESOSSupported)
                    ? 3 : -1;
            if (keyPriority != -1) {
                newSubsInfoListPerPriority.computeIfAbsent(keyPriority,
                        k -> new ArrayList<>()).add(info);
            } else {
                plogw("evaluateESOSProfilesPrioritization: Got -1 keyPriority for subId="
                        + info.getSubscriptionId());
            }

            Pair<String, Integer> subscriberIdPair = getSubscriberIdAndType(info);
            String newSubscriberId = subscriberIdPair.first;
            Optional<String> oldSubscriberId = mSubscriberIdPerSub.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(subId))
                    .map(Map.Entry::getKey).findFirst();

            if (oldSubscriberId.isPresent()
                    && !newSubscriberId.equals(oldSubscriberId.get())) {
                mSubscriberIdPerSub.remove(oldSubscriberId.get());
                mProvisionedSubscriberId.remove(oldSubscriberId.get());
                plogw("Old phone number is removed: id = " + subId + ", oldSubscriberId = "
                        + oldSubscriberId.get() + ", newSubscriberId = " + newSubscriberId);
                isChanged = true;
                // The provision state of the subId in the DB might be true right now. We need to
                // set it to false so that it is consistent with the cached value.
                if (mFeatureFlags.fixSatelliteProvisionStateOutOfSync()) {
                    try {
                        mSubscriptionManagerService.setIsSatelliteProvisionedForNonIpDatagram(subId,
                                false);
                        plogd("evaluateESOSProfilesPrioritization: clear provision state for "
                                + "subId " + subId + " from DB");
                    } catch (IllegalArgumentException | SecurityException ex) {
                        ploge("setIsSatelliteProvisionedForNonIpDatagram: subId=" + subId
                                + ", ex=" + ex);
                    }
                }
            }
            if (!newSubscriberId.isEmpty()) {
                mSubscriberIdPerSub.put(newSubscriberId, subId);
            }
        }
        plogd("evaluateESOSProfilesPrioritization: newSubsInfoListPerPriority.size()="
                  + newSubsInfoListPerPriority.size());

        if (!mHasSentBroadcast.get() && newSubsInfoListPerPriority.size() == 0) {
            logd("evaluateESOSProfilesPrioritization: no satellite subscription available");
            return;
        }

        // If priority has changed, send broadcast for provisioned ESOS subs IDs
        List<SatelliteSubscriberProvisionStatus> newEvaluatedSubscriberProvisionStatus =
                getPrioritizedSatelliteSubscriberProvisionStatusList(
                        newSubsInfoListPerPriority);
        if (isPriorityChanged(getSubsInfoListPerPriority(), newSubsInfoListPerPriority)
                || isSubscriberContentChanged(getLastEvaluatedSubscriberProvisionStatus(),
                newEvaluatedSubscriberProvisionStatus)
                || isChanged) {
            setSubsInfoListPerPriority(newSubsInfoListPerPriority);
            setLastEvaluatedSubscriberProvisionStatus(newEvaluatedSubscriberProvisionStatus);
            sendBroadCastForProvisionedESOSSubs();
            mHasSentBroadcast.set(true);
            selectBindingSatelliteSubscription(false);
        }
    }

    // to check if the contents of carrier config is loaded properly
    private Boolean isCarrierConfigLoaded(int subId) {
        PersistableBundle carrierConfig = mCarrierConfigManager
                .getConfigForSubId(subId, KEY_CARRIER_CONFIG_APPLIED_BOOL);
        return carrierConfig != null ? carrierConfig.getBoolean(
                CarrierConfigManager.KEY_CARRIER_CONFIG_APPLIED_BOOL) : false;
    }

    // The subscriberId for ntnOnly SIMs is the Iccid, whereas for ESOS supported SIMs, the
    // subscriberId is the Imsi prefix 6 digit + phone number.
    private Pair<String, Integer> getSubscriberIdAndType(@Nullable SubscriptionInfo info) {
        String subscriberId = "";
        @SatelliteSubscriberInfo.SubscriberIdType int subscriberIdType =
                SatelliteSubscriberInfo.SUBSCRIBER_ID_TYPE_ICCID;
        if (info == null) {
            logd("getSubscriberIdAndType: subscription info is null");
            return new Pair<>(subscriberId, subscriberIdType);
        }
        if (info.isOnlyNonTerrestrialNetwork()) {
            subscriberId = info.getIccId();
        } else if (info.isSatelliteESOSSupported()) {
            subscriberId = getPhoneNumberBasedCarrier(info.getSubscriptionId());
            subscriberIdType = SatelliteSubscriberInfo.SUBSCRIBER_ID_TYPE_IMSI_MSISDN;
        }
        logd("getSubscriberIdAndType: subscriberId="
                + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, subscriberId)
                + ", subscriberIdType=" + subscriberIdType);
        return new Pair<>(subscriberId, subscriberIdType);
    }

    /** Get subscriberId from phone number and carrier information. */
    @VisibleForTesting(visibility =  VisibleForTesting.Visibility.PRIVATE)
    public String getPhoneNumberBasedCarrier(int subId) {
        String subscriberId = "";
        SubscriptionInfoInternal internal = mSubscriptionManagerService.getSubscriptionInfoInternal(
                subId);
        if (internal == null) {
            plogd("getPhoneNumberBasedCarrier: subscriptionInfoInternal is null.");
            return subscriberId;
        }

        SubscriptionManager subscriptionManager = mContext.getSystemService(
                SubscriptionManager.class);
        if (mInjectSubscriptionManager != null) {
            plogd("getPhoneNumberBasedCarrier: InjectSubscriptionManager");
            subscriptionManager = mInjectSubscriptionManager;
        }

        if (subscriptionManager == null) {
            plogd("getPhoneNumberBasedCarrier: subscriptionManager is null");
            return subscriberId;
        }

        String phoneNumber = subscriptionManager.getLastKnownPhoneNumber(subId);
        if (TextUtils.isEmpty(phoneNumber)) {
            plogd("getPhoneNumberBasedCarrier: phoneNumber is empty.");
            return subscriberId;
        }

        String imsi = internal.getImsi();
        if (TextUtils.isEmpty(imsi)) {
            plogd("getPhoneNumberBasedCarrier: imsi is empty");
            return subscriberId;
        }

        if (imsi.length() < 6) {
            plogd("getPhoneNumberBasedCarrier: imsi length is less than 6");
            return subscriberId;
        }

        subscriberId = internal.getImsi().substring(0, 6)
                + phoneNumber.replaceFirst("^\\+", "");
        plogd("getPhoneNumberBasedCarrier: subscriberId="
                + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, subscriberId));
        return subscriberId;
    }

    private boolean isPriorityChanged(Map<Integer, List<SubscriptionInfo>> currentMap,
            Map<Integer, List<SubscriptionInfo>> newMap) {
        if (currentMap.size() == 0 || currentMap.size() != newMap.size()) {
            return true;
        }

        for (Map.Entry<Integer, List<SubscriptionInfo>> entry : currentMap.entrySet()) {
            List<SubscriptionInfo> currentList = entry.getValue();
            List<SubscriptionInfo> newList = newMap.get(entry.getKey());
            if (newList == null || currentList == null || currentList.size() != newList.size()) {
                return true;
            }
            for (int i = 0; i < currentList.size(); i++) {
                if (currentList.get(i).getSubscriptionId() != newList.get(i).getSubscriptionId()) {
                    logd("isPriorityChanged: cur=" + currentList.get(i) + " , new=" + newList.get(
                            i));
                    return true;
                }
            }
        }
        return false;
    }

    // Checks if there are any changes between subscriberInfos. return false if the same.
    // Note that, Use lists with the same priority so we can compare contents properly.
    private boolean isSubscriberContentChanged(List<SatelliteSubscriberProvisionStatus> currentList,
            List<SatelliteSubscriberProvisionStatus> newList) {
        if (currentList.size() != newList.size()) {
            return true;
        }
        for (int i = 0; i < currentList.size(); i++) {
            SatelliteSubscriberProvisionStatus curSub = currentList.get(i);
            SatelliteSubscriberProvisionStatus newSub = newList.get(i);
            if (!curSub.getSatelliteSubscriberInfo().equals(newSub.getSatelliteSubscriberInfo())) {
                logd("isSubscriberContentChanged: cur=" + curSub + " , new=" + newSub);
                return true;
            }
        }
        return false;
    }

    private void sendBroadCastForProvisionedESOSSubs() {
        String packageName = getConfigSatelliteGatewayServicePackage();
        String className = getConfigSatelliteCarrierRoamingEsosProvisionedClass();
        if (packageName == null || className == null || packageName.isEmpty()
                || className.isEmpty()) {
            logd("sendBroadCastForProvisionedESOSSubs: packageName or className is null or empty.");
            return;
        }
        String action = SatelliteManager.ACTION_SATELLITE_SUBSCRIBER_ID_LIST_CHANGED;

        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(packageName, className));
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        logd("sendBroadCastForProvisionedESOSSubs" + intent);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected String getStringFromOverlayConfig(int resourceId) {
        String name;
        try {
            name = mContext.getResources().getString(resourceId);
        } catch (Resources.NotFoundException ex) {
            loge("getStringFromOverlayConfig: ex=" + ex);
            name = null;
        }
        return name;
    }

    /**
     * Request to get the name to display for Satellite.
     *
     * @param result The result receiver that returns the name to display for the satellite
     *               or an error code if the request failed.
     */
    public void requestSatelliteDisplayName(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_SATELLITE_DISPLAY_NAME, args));
            return;
        }

        handleRequestSatelliteDisplayName(result);
    }

    private void handleRequestSatelliteDisplayName(@NonNull ResultReceiver result) {
        plogd("handleRequestSatelliteDisplayName");

        int subId = getSelectedSatelliteSubId();
        String displayName = getConfigForSubId(subId).getString(
                KEY_SATELLITE_DISPLAY_NAME_STRING, "Satellite");

        plogd("requestSatelliteDisplayName: " + displayName);
        Bundle bundle = new Bundle();
        bundle.putString(SatelliteManager.KEY_SATELLITE_DISPLAY_NAME, displayName);
        result.send(SATELLITE_RESULT_SUCCESS, bundle);
    }

    /**
     * Request to get the PendingIntent to launch the PointingUI app.
     *
     * @param launchIntentAttributes The attributes to create the launch intent.
     * @param result The result receiver that returns the {@link PendingIntent} to launch the
     * PointingUI app if the request is successful or an error code if the request failed.
     */
    public void requestPointingUiAppLaunchIntent(
            @NonNull PointingUiAppLaunchIntentAttributes launchIntentAttributes,
            @NonNull ResultReceiver result) {
        logd("requestPointingUiAppLaunchIntent: launchIntentAttributes=" + launchIntentAttributes);
        if (!mFeatureFlags.systemSelectionSpecifierEnhancement()) {
            logd("requestPointingUiAppLaunchIntent: systemSelectionSpecifierEnhancement flag"
                    +" is disabled");
            result.send(SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, null);
            return;
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = launchIntentAttributes;
        args.arg2 = result;
        sendMessage(obtainMessage(REQUEST_POINTING_UI_APP_LAUNCH_INTENT, args));
    }

    /**
     * Request to get list of prioritized satellite tokens to be used for provision.
     *
     * @param result The result receiver, which returns the list of prioritized satellite tokens
     * to be used for provision if the request is successful or an error code if the request failed.
     */
    public void requestSatelliteSubscriberProvisionStatus(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_SATELLITE_SUBSCRIBER_PROVISION_STATUS, args));
        }

        handleRequestSatelliteSubscriberProvisionStatus(result);
    }

    private void handleRequestSatelliteSubscriberProvisionStatus(@NonNull ResultReceiver result) {
        List<SatelliteSubscriberProvisionStatus> list =
                getPrioritizedSatelliteSubscriberProvisionStatusList();
        logd("requestSatelliteSubscriberProvisionStatus: " + list);
        final Bundle bundle = new Bundle();
        bundle.putParcelableList(SatelliteManager.KEY_REQUEST_PROVISION_SUBSCRIBER_ID_TOKEN, list);
        result.send(SATELLITE_RESULT_SUCCESS, bundle);
    }

    private List<SatelliteSubscriberProvisionStatus>
            getPrioritizedSatelliteSubscriberProvisionStatusList() {
        return getPrioritizedSatelliteSubscriberProvisionStatusList(getSubsInfoListPerPriority());
    }

    @NonNull
    private List<SatelliteSubscriberProvisionStatus>
            getPrioritizedSatelliteSubscriberProvisionStatusList(
                    Map<Integer, List<SubscriptionInfo>> subsInfoListPerPriority) {
        List<SatelliteSubscriberProvisionStatus> list = new ArrayList<>();
        for (int priority : subsInfoListPerPriority.keySet()) {
            List<SubscriptionInfo> infoList = subsInfoListPerPriority.get(priority);
            if (infoList == null) {
                logd("getPrioritySatelliteSubscriberProvisionStatusList: no exist this "
                        + "priority " + priority);
                continue;
            }
            for (SubscriptionInfo info : infoList) {
                Pair<String, Integer> subscriberIdPair = getSubscriberIdAndType(info);
                String subscriberId = subscriberIdPair.first;
                int carrierId = info.getCarrierId();
                String apn = getConfigForSubId(info.getSubscriptionId())
                        .getString(KEY_SATELLITE_NIDD_APN_NAME_STRING, "");
                logd("getPrioritySatelliteSubscriberProvisionStatusList:"
                        + " subscriberId:"
                        + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, subscriberId)
                        + " , carrierId=" + carrierId + " , apn=" + apn);
                if (subscriberId.isEmpty()) {
                    logd("getPrioritySatelliteSubscriberProvisionStatusList: getSubscriberId "
                            + "failed skip this subscriberId.");
                    continue;
                }
                SatelliteSubscriberInfo satelliteSubscriberInfo =
                        new SatelliteSubscriberInfo.Builder().setSubscriberId(subscriberId)
                                .setCarrierId(carrierId).setNiddApn(apn)
                                .setSubscriptionId(info.getSubscriptionId())
                                .setSubscriberIdType(subscriberIdPair.second)
                                .build();
                boolean provisioned = mProvisionedSubscriberId.getOrDefault(subscriberId,
                        false);
                logd("getPrioritySatelliteSubscriberProvisionStatusList: "
                        + "satelliteSubscriberInfo=" + satelliteSubscriberInfo
                        + ", provisioned=" + provisioned);
                list.add(new SatelliteSubscriberProvisionStatus.Builder()
                        .setSatelliteSubscriberInfo(satelliteSubscriberInfo)
                        .setProvisioned(provisioned).build());
                mSubscriberIdPerSub.put(subscriberId, info.getSubscriptionId());
            }
        }
        return list;
    }

    public int getSelectedSatelliteSubId() {
        plogd("getSelectedSatelliteSubId: subId=" + mSelectedSatelliteSubId.get());
        return mSelectedSatelliteSubId.get();
    }

    /**
     * Request to get the currently selected satellite subscription id.
     *
     * @param result The result receiver that returns the currently selected satellite subscription
     *               id if the request is successful or an error code if the request failed.
     */
    public void requestSelectedNbIotSatelliteSubscriptionId(@NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = result;
            sendMessage(obtainMessage(REQUEST_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_ID, args));
            return;
        }

        handleRequestSelectedNbIotSatelliteSubscriptionId(result);
    }

    private void handleRequestSelectedNbIotSatelliteSubscriptionId(@NonNull ResultReceiver result) {
        plogd("handleRequestSelectedNbIotSatelliteSubscriptionId");
        int selectedSatelliteSubId = getSelectedSatelliteSubId();
        plogd("requestSelectedNbIotSatelliteSubscriptionId: " + selectedSatelliteSubId);
        if (selectedSatelliteSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            result.send(SATELLITE_RESULT_NO_VALID_SATELLITE_SUBSCRIPTION, null);
            logd("requestSelectedNbIotSatelliteSubscriptionId: "
                    + "selectedSatelliteSubId is invalid");
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putInt(SatelliteManager.KEY_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_ID,
                selectedSatelliteSubId);
        result.send(SATELLITE_RESULT_SUCCESS, bundle);
    }

    private void selectBindingSatelliteSubscription(boolean shouldIgnoreEnabledState) {
        int preSelectedSatelliteSubId = getSelectedSatelliteSubId();
        plogd("selectBindingSatelliteSubscription: preSelectedSatelliteSubId="
                + preSelectedSatelliteSubId);

        if (preSelectedSatelliteSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            plogd("selectBindingSatelliteSubscription: binding satellite subscription is"
                    + " not selected yet");
        } else if ((isSatelliteEnabled() || isSatelliteBeingEnabled())
                       && !shouldIgnoreEnabledState) {
            plogd("selectBindingSatelliteSubscription: satellite subscription will be selected "
                    + "once the satellite session ends");
            return;
        }

        int selectedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        List<SatelliteSubscriberProvisionStatus> satelliteSubscribers =
                getPrioritizedSatelliteSubscriberProvisionStatusList();

        for (SatelliteSubscriberProvisionStatus status : satelliteSubscribers) {
            int subId = getSubIdFromSubscriberId(
                    status.getSatelliteSubscriberInfo().getSubscriberId());

            if (status.isProvisioned() && isActiveSubId(subId) &&
                isSatelliteAvailableAtCurrentLocation(
                    mSubscriptionManagerService.getSubscriptionInfo(subId))) {
                selectedSubId = subId;
                break;
            }
        }

        if (selectedSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                && isSatelliteSupportedViaOem()) {
            selectedSubId = getNtnOnlySubscriptionId();
        }

        setSelectedSatelliteSubId(selectedSubId);
        if (preSelectedSatelliteSubId != getSelectedSatelliteSubId()) {
            plogd("selectBindingSatelliteSubscription: SelectedSatelliteSubId changed");
            mSatelliteSubIdChangedRegistrants.notifyRegistrants();
            handleEventSelectedNbIotSatelliteSubscriptionChanged(selectedSubId);
            handleCarrierRoamingNtnAvailableServicesChanged();
            evaluateCarrierRoamingNtnEligibilityChange();
        }

        setSatellitePhone(selectedSubId);
        if (selectedSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            mControllerMetricsStats.setCarrierIdInfo(getSatelliteCarrierId(), isNtnOnlyCarrier(),
                    getSupportedConnectTypeMetrics(selectedSubId));
        }
        plogd("selectBindingSatelliteSubscription: SelectedSatelliteSubId=" + selectedSubId);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected boolean isCarrierSatelliteHigherPriority(SubscriptionInfo info) {
        if(!isSatelliteAccessAllowedAtCurrentLocation()) {
            return true;
        }
        if(isSatelliteAvailableAtCurrentLocation(info)) {
            return true;
        }
        return false;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected boolean isSatelliteAvailableAtCurrentLocation(@Nullable SubscriptionInfo info) {
        if(info == null) {
            plogd("isSatelliteAvailableAtCurrentLocation: subscriptionInfo is null");
            return false;
        }
        if (mCtsSatelliteAccessAllowedSubIds != null) {
            if (mCtsSatelliteAccessAllowedSubIds.contains(info.getSubscriptionId())) {
                plogd("isSatelliteAvailableAtCurrentLocation: subscriptionId="
                        + info.getSubscriptionId() + " is allowed for CTS testing");
                return true;
            } else {
                plogd("isSatelliteAvailableAtCurrentLocation: subscriptionId="
                        + info.getSubscriptionId() + " is not allowed for CTS testing");
                return false;
            }
        }
        if (!isSatelliteAccessAllowedAtCurrentLocation()) {
            plogd("isSatelliteAvailableAtCurrentLocation: satellite access is not allowed at " +
                    "current location");
            return false;
        }
        if(info.isOnlyNonTerrestrialNetwork()) {
            return true;
        }

        if (mFeatureFlags.supportCarrierIdsInGeofence()) {
            synchronized(mSatelliteAccessConfigLock) {
                if (!mCurrentLocationCarrierIds.isEmpty()) {
                    plogd("isSatelliteAvailableAtCurrentLocation: mCurrentLocationCarrierIds="
                            + mCurrentLocationCarrierIds.stream().map(String::valueOf)
                                .collect(Collectors.joining(", "))
                            + " info.getCarrierId()=" + info.getCarrierId());
                    return mCurrentLocationCarrierIds.contains(info.getCarrierId());
                }
            }
        }

        int[] carrierTagIdsArray = mContext.getResources().getIntArray(
            R.array.config_verizon_satellite_enabled_tagids);
        List<Integer> carrierTagIds = null;

        if(carrierTagIdsArray != null && carrierTagIdsArray.length > 0) {
            carrierTagIds = Arrays.stream(carrierTagIdsArray)
                .boxed()
                .collect(Collectors.toList());
        }

        if(carrierTagIds == null) {
            String satelliteAccessConfigFile =
                getSatelliteAccessConfigurationFileFromOverlayConfig();
            if (TextUtils.isEmpty(satelliteAccessConfigFile)) {
                plogd("isSatelliteAvailableAtCurrentLocation: device does not support"
                          + " custom satellite access configuration per location");
                return true;
            } else {
                plogd("isSatelliteAvailableAtCurrentLocation: tagids for carrier "
                          + info.getCarrierName() + ", subId=" + info.getSubscriptionId()
                          + " are not available");
                return false;
            }
        }

        return isCarrierSatelliteAvailableAtCurrentLocation(carrierTagIds);
    }

    @Nullable
    private String getSatelliteAccessConfigurationFileFromOverlayConfig() {
        String satelliteAccessConfigFile = null;
        try {
            satelliteAccessConfigFile = mContext.getResources().getString(
                    com.android.internal.R.string.satellite_access_config_file);
        } catch (Resources.NotFoundException ex) {
            loge("getSatelliteAccessConfigurationFileFromOverlayConfig: got ex=" + ex);
        }

        logd("satelliteAccessConfigFile =" + satelliteAccessConfigFile);
        return satelliteAccessConfigFile;
    }

    /**
     * Compares tagIds and determine if
     * carrier satellite is available at current location while selecting highest priority profile.
     *
     * @param carrierTagIds a list of integer tagIds representing regions where carrier satellite
     * coverage is available.
     * @return {@code true} if the carrier satellite is available at current location,
     *      {@code false} otherwise.
     */
    private boolean isCarrierSatelliteAvailableAtCurrentLocation(
        List<Integer> carrierTagIds) {
        return !Collections.disjoint(carrierTagIds, getCurrentLocationTagIds());
    }

    private int getSubIdFromSubscriberId(String subscriberId) {
        return mSubscriberIdPerSub.getOrDefault(subscriberId,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    private boolean isActiveSubId(int subId) {
        SubscriptionInfo subInfo = mSubscriptionManagerService.getSubscriptionInfo(subId);
        if (subInfo == null) {
            logd("isActiveSubId: subscription associated with subId=" + subId + " not found");
            return false;
        }
        return subInfo.isActive();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean isSubscriptionProvisioned(int subId) {
        plogd("isSubscriptionProvisioned: subId=" + subId);
        String subscriberId = getSubscriberIdAndType(
                mSubscriptionManagerService.getSubscriptionInfo(subId)).first;
        if (subscriberId.isEmpty()) {
            plogd("isSubscriptionProvisioned: subId=" + subId + " subscriberId is empty.");
            return false;
        }

        return mProvisionedSubscriberId.getOrDefault(subscriberId, false);
    }

    /**
     * Deliver the list of provisioned satellite subscriber ids.
     *
     * @param list List of provisioned satellite subscriber ids.
     * @param result The result receiver that returns whether deliver success or fail.
     */
    public void provisionSatellite(@NonNull List<SatelliteSubscriberInfo> list,
            @NonNull ResultReceiver result) {

        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = list;
            args.arg2 = result;
            sendMessage(obtainMessage(REQUEST_PROVISION_SATELLITE, args));
        }

        handleRequestProvisionSatellite(list, result);
    }

    private void handleRequestProvisionSatellite(@NonNull List<SatelliteSubscriberInfo> list,
            @NonNull ResultReceiver result) {
        if (list.isEmpty()) {
            result.send(SATELLITE_RESULT_INVALID_ARGUMENTS, null);
            logd("provisionSatellite: SatelliteSubscriberInfo list is empty");
            return;
        }

        logd("provisionSatellite:" + list);
        RequestProvisionSatelliteArgument request = new RequestProvisionSatelliteArgument(list,
                result, true);
        sendRequestAsync(CMD_UPDATE_PROVISION_SATELLITE_TOKEN, request, null);
        incrementResultReceiverCount("SC:provisionSatellite");
    }

    /**
     * Request to update system selection channels.
     *
     * @param result The result receiver that returns if the request is successful or
     *               an error code if the request failed.
     */
    public void updateSystemSelectionChannels(
            @NonNull List<SystemSelectionSpecifier> selectionSpecifiers,
            @NonNull ResultReceiver result) {
        sendRequestAsync(CMD_UPDATE_SYSTEM_SELECTION_CHANNELS,
                new UpdateSystemSelectionChannelsArgument(selectionSpecifiers, result), null);
    }

    /**
     * @param subId Subscription ID.
     * @return The The map of earfcns with key: regional satellite config Id,
     * value: set of earfcns in the corresponding regions associated with the {@code subId}.
     */
    @NonNull
    public Map<String, Set<Integer>> getRegionalSatelliteEarfcns(int subId) {
        if (mRegionalSatelliteEarfcns.containsKey(subId)) {
            return mRegionalSatelliteEarfcns.get(subId);
        } else {
            logd("getRegionalSatelliteEarfcns: Earfcns for subId: " + subId + " not found");
            return new HashMap<>();
        }
    }

    /**
     * Update regional satellite earfcn information from carrier config.
     */
    public void updateRegionalSatelliteEarfcns(int subId) {
        plogd("updateRegionalSatelliteEarfcns with subId " + subId);
        mRegionalSatelliteEarfcns.put(subId,
                readRegionalSatelliteEarfcnsFromCarrierConfig(subId));
    }

    /**
     * Deliver the list of deprovisioned satellite subscriber ids.
     *
     * @param list List of deprovisioned satellite subscriber ids.
     * @param result The result receiver that returns whether deliver success or fail.
     */
    public void deprovisionSatellite(@NonNull List<SatelliteSubscriberInfo> list,
            @NonNull ResultReceiver result) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = list;
            args.arg2 = result;
            sendMessage(obtainMessage(REQUEST_DEPROVISION_SATELLITE, args));
        }

        handleRequestDeprovisionSatellite(list, result);
    }

    private void handleRequestDeprovisionSatellite(@NonNull List<SatelliteSubscriberInfo> list,
            @NonNull ResultReceiver result) {
        if (list.isEmpty()) {
            result.send(SATELLITE_RESULT_INVALID_ARGUMENTS, null);
            logd("deprovisionSatellite: SatelliteSubscriberInfo list is empty");
            return;
        }

        logd("deprovisionSatellite:" + list);
        RequestProvisionSatelliteArgument request = new RequestProvisionSatelliteArgument(list,
                result, false);
        sendRequestAsync(CMD_UPDATE_PROVISION_SATELLITE_TOKEN, request, null);
        incrementResultReceiverCount("SC:provisionSatellite");
    }

    /**
     * Inform whether application supports NTN SMS in satellite mode.
     *
     * This method is used by default messaging application to inform framework whether it supports
     * NTN SMS or not.
     *
     * @param ntnSmsSupported {@code true} If application supports NTN SMS, else {@code false}.
     */
    public void setNtnSmsSupportedByMessagesApp(boolean ntnSmsSupported) {
        if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = ntnSmsSupported;
            sendMessage(obtainMessage(REQUEST_SET_NTN_SMS_SUPPORTED_BY_MESSAGES_APP, args));
            return;
        }

        handleRequestSetNtnSmsSupportedByMessagesApp(ntnSmsSupported);
    }

    private void handleRequestSetNtnSmsSupportedByMessagesApp(boolean ntnSmsSupported) {
        persistNtnSmsSupportedByMessagesApp(ntnSmsSupported);
        handleCarrierRoamingNtnAvailableServicesChanged();
    }

    private void persistNtnSmsSupportedByMessagesApp(boolean ntnSmsSupported) {
        plogd("persistNtnSmsSupportedByMessagesApp: ntnSmsSupported=" + ntnSmsSupported);
        if (!loadSatelliteSharedPreferences()) return;

        if (mSharedPreferences == null) {
            ploge("persistNtnSmsSupportedByMessagesApp: mSharedPreferences is null");
        } else {
            mSharedPreferences.edit().putBoolean(
                    NTN_SMS_SUPPORTED_BY_MESSAGES_APP_KEY, ntnSmsSupported).apply();
            setNtnSmsSupportedByMessagesAppCache(ntnSmsSupported);
        }
    }

    private void setNtnSmsSupportedByMessagesAppCache(boolean ntnSmsSupported) {
        if (mNtnSmsSupportedByMessagesApp == null) {
            mNtnSmsSupportedByMessagesApp = new AtomicBoolean(ntnSmsSupported);
        } else {
            mNtnSmsSupportedByMessagesApp.set(ntnSmsSupported);
        }
    }

    private boolean isNtnSmsSupportedByMessagesApp() {
        if (mNtnSmsSupportedByMessagesApp != null) {
            plogd("isNtnSmsSupportedByMessagesApp:" + mNtnSmsSupportedByMessagesApp.get());
            return mNtnSmsSupportedByMessagesApp.get();
        }

        if (!loadSatelliteSharedPreferences()) return false;

        if (mSharedPreferences == null) {
            ploge("isNtnSmsSupportedByMessagesApp: mSharedPreferences is null");
            return false;
        } else {
            boolean ntnSmsSupported = mSharedPreferences.getBoolean(
                    NTN_SMS_SUPPORTED_BY_MESSAGES_APP_KEY, false);
            setNtnSmsSupportedByMessagesAppCache(ntnSmsSupported);
            plogd("isNtnSmsSupportedByMessagesApp:" + ntnSmsSupported);
            return ntnSmsSupported;
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void setSatellitePhone(int subId) {
        Phone phone = SatelliteServiceUtils.getPhone(subId);
        setSatellitePhone(phone);
        plogd("setSatellitePhone: phoneId=" + (phone != null
                ? phone.getPhoneId() : "null") + ", subId=" + subId);
    }

    /** set satellite phone */
    private void setSatellitePhone(@Nullable Phone phone) {
        synchronized (mSatellitePhoneLock) {
            mSatellitePhone = phone;
        }
    }

    /** return satellite phone */
    @Nullable
    public Phone getSatellitePhone() {
        synchronized (mSatellitePhoneLock) {
            return mSatellitePhone;
        }
    }

    /**
     * return Satellite plmn value, empty string if satellite phone not available
     */
    public String getSatellitePlmnForMetrics() {
        String satellitePlmn = Optional.ofNullable(getSatellitePhone())
                .map(Phone::getServiceState)
                .map(ServiceState::getOperatorNumeric)
                .orElse(SatelliteConstants.DEFAULT_PLMN);
        return satellitePlmn;
    }

    /**
     * return plmn value if phone available, otherwise empty string
     */
    public String getSatellitePlmnForMetrics(@Nullable Phone phone) {
        String satellitePlmn = Optional.ofNullable(phone)
                .map(Phone::getServiceState)
                .map(ServiceState::getOperatorNumeric)
                .orElse(SatelliteConstants.DEFAULT_PLMN);
        return satellitePlmn;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void setSelectedSatelliteSubId(int subId) {
        plogd("setSelectedSatelliteSubId: subId=" + subId);
        mSelectedSatelliteSubId.set(subId);
    }

    /** Return the carrier ID of the binding satellite subscription. */
    public int getSatelliteCarrierId() {
        SubscriptionInfo subInfo = mSubscriptionManagerService.getSubscriptionInfo(
            mSelectedSatelliteSubId.get());
        if (subInfo == null) {
            logd("getSatelliteCarrierId: returns UNKNOWN_CARRIER_ID");
            return UNKNOWN_CARRIER_ID;
        }
        return subInfo.getCarrierId();
    }

    /**
     * Get whether phone is eligible to manually connect to carrier roaming non-terrestrial network.
     *
     * @param phone phone object return {@code true} when the subscription is eligible for satellite
     *     communication if all the following conditions are met:
     *     <ul>
     *       <li>Subscription supports manual-connect P2P satellite messaging which is defined by
     *           {@link CarrierConfigManager#KEY_SATELLITE_ATTACH_SUPPORTED_BOOL}
     *       <li>{@link CarrierConfigManager#KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT} set to {@link
     *           CarrierConfigManager#CARRIER_ROAMING_NTN_CONNECT_MANUAL} or {@link
     *           CarrierConfigManager#CARRIER_ROAMING_NTN_CONNECT_HYBRID}
     *       <li>The device is in {@link ServiceState#STATE_OUT_OF_SERVICE}, not connected to Wi-Fi.
     *     </ul>
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean isCarrierRoamingNtnEligible(@Nullable Phone phone) {
        if (!mIsRadioOn.get()) {
            plogd("isCarrierRoamingNtnEligible: radio is off");
            return false;
        }

        boolean isSatelliteAccessAllowed = isSatelliteAccessAllowedAtCurrentLocation();
        if (!isSatelliteAccessAllowed) {
            plogd("isCarrierRoamingNtnEligible: satellite access is not allowed");
            return false;
        }

        // Even if Location service is off, isSatelliteAccessAllowed can be true
        // when the device is in emergency call and the allowed cache is valid.
        if (!isLocationServiceEnabled()) {
            plogd("isCarrierRoamingNtnEligible: Location service is off");
            return false;
        }

        if (phone == null) {
            plogd("isCarrierRoamingNtnEligible: phone is null");
            return false;
        }

        int subId = getSelectedSatelliteSubId();
        if (!isSatelliteRoamingP2pSmSSupported(subId)) {
            plogd("isCarrierRoamingNtnEligible(" + subId
                    + "): doesn't support manual-connect P2P SMS");
            return false;
        }

        if (!isSatelliteSupportedViaCarrier(subId)) {
            plogd(
                    "isCarrierRoamingNtnEligible[phoneId="
                            + phone.getPhoneId()
                            + "]: satellite is not supported via carrier");
            return false;
        }

        if (!isSubscriptionProvisioned(subId)) {
            plogd(
                    "isCarrierRoamingNtnEligible[phoneId="
                            + phone.getPhoneId()
                            + "]: subscription is not provisioned to use satellite.");
            return false;
        }

        int[] services = getSupportedServicesOnCarrierRoamingNtn(subId);
        if (!ArrayUtils.contains(services, NetworkRegistrationInfo.SERVICE_TYPE_SMS)) {
            plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId()
                    + "]: SMS is not supported by carrier");
            return false;
        }

        int connectType = getCarrierRoamingNtnConnectType(subId);
        if (connectType != CARRIER_ROAMING_NTN_CONNECT_MANUAL
                && (mFeatureFlags.vzwAstSkyloFallback()
                        && connectType != CARRIER_ROAMING_NTN_CONNECT_HYBRID)) {
            plogd(
                    "isCarrierRoamingNtnEligible[phoneId="
                            + phone.getPhoneId()
                            + "]: not manual "
                            + " nor hybrid connect."
                            + " connectType = "
                            + connectType);
            return false;
        }

        if (isInCarrierRoamingNbIotNtn(phone)
                && getCarrierRoamingNtnConnectType(subId)
                == CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC) {
            plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId()
                    + "]: in NTN mode with automatic connection.");
            return false;
        }

        if (mOverrideNtnEligibility != null) {
            // TODO need to send the value from `mOverrideNtnEligibility` or simply true ?
            return true;
        }

        if (SatelliteServiceUtils.isCellularAvailable()) {
            plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId()
                    + "]: cellular is available");
            return false;
        }

        if (mIsWifiConnected.get()) {
            plogd("isCarrierRoamingNtnEligible[phoneId=" + phone.getPhoneId()
                    + "]: Wi-Fi is connected");
            return false;
        }

        return true;
    }

    /**
     * Checks if the satellite service is supported by the carrier for the specified subscription ID
     * and servicetype.
     *
     * @param subId The subscription id.
     * @param serviceType The type of service to check
     */
    public boolean isSatelliteServiceSupportedByCarrier(
            int subId, @NetworkRegistrationInfo.ServiceType int serviceType) {
        List<String> satellitePlmnList = getSatellitePlmnsForCarrier(subId);
        for (String satellitePlmn : satellitePlmnList) {
            if (getSupportedSatelliteServicesForPlmn(subId, satellitePlmn).contains(serviceType)) {
                return true;
            }
        }
        return false;
    }

    /** Start PointingUI if it is required. */
    public void startPointingUI() {
        plogd("startPointingUI: mNeedsSatellitePointing=" + mNeedsSatellitePointing.get()
                + ", mIsDemoModeEnabled=" + mIsDemoModeEnabled.get()
                + ", mIsEmergency=" + mIsEmergency.get());
        if (mNeedsSatellitePointing.get()) {
            mPointingAppController.startPointingUI(false /*needFullScreenPointingUI*/,
                    mIsDemoModeEnabled.get(), mIsEmergency.get());
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void registerForSatelliteCommunicationAccessStateChanged() {
        if (mRegisteredForSatelliteCommunicationAccessStateChanged.get()) {
            if (DEBUG) {
                plogd("registerForSatelliteCommunicationAccessStateChanged: already registered.");
            }
            return;
        }

        SatelliteManager satelliteManager = mContext.getSystemService(SatelliteManager.class);
        if (satelliteManager == null) {
            ploge("registerForSatelliteCommunicationAccessStateChanged: SatelliteManager is null");
            return;
        }

        SatelliteCommunicationAccessStateCallback accessStateCallback =
            new SatelliteCommunicationAccessStateCallback() {
                @Override
                public void onAccessAllowedStateChanged(boolean isAllowed) {
                    plogd("onAccessStateChanged: isAllowed=" + isAllowed);
                    if (mFeatureFlags.satelliteExitP2pSessionOutsideGeofence()) {
                        sendMessage(obtainMessage(EVENT_SATELLITE_ACCESS_ALLOWED_STATE_CHANGED,
                            isAllowed));
                    } else {
                        mSatelliteAccessAllowed.set(isAllowed);
                        evaluateESOSProfilesPrioritization();
                        evaluateCarrierRoamingNtnEligibilityChange();
                        handleCarrierRoamingNtnAvailableServicesChanged();
                    }
                }

                @Override
                public void onAccessConfigurationChanged(
                    SatelliteAccessConfiguration satelliteAccessConfiguration) {
                    plogd("onAccessConfigurationChanged: satelliteAccessConfiguration="
                        + satelliteAccessConfiguration);
                    if (mFeatureFlags.satelliteImproveMultiThreadDesign()) {
                        sendMessage(obtainMessage(EVENT_SATELLITE_ACCESS_CONFIGURATION_CHANGED,
                                satelliteAccessConfiguration));
                        return;
                    }

                    handleSatelliteAccessConfigUpdateResult(satelliteAccessConfiguration);
                }
            };
        try {
            satelliteManager.registerForCommunicationAccessStateChanged(
                    this::post, accessStateCallback);
        } catch(RuntimeException e) {
            plogd("registerForSatelliteCommunicationAccessStateChanged: "
                    + "satelliteManager.registerForCommunicationAccessStateChanged() failed, "
                    + "e=" + e);
            return;
        }
        mRegisteredForSatelliteCommunicationAccessStateChanged.set(true);
    }

    /** Handle access allowed state changes. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void handleSatelliteAccessAllowedStateChanged(boolean isAllowed) {
        plogd("handleSatelliteAccessAllowedStateChanged: isAllowed=" + isAllowed);
        mSatelliteAccessAllowed.set(isAllowed);
        evaluateESOSProfilesPrioritization();
        selectBindingSatelliteSubscription(false);
        evaluateCarrierRoamingNtnEligibilityChange();
        handleCarrierRoamingNtnAvailableServicesChanged();
        evaluateDisablingP2pSession();
    }

    private void evaluateDisablingP2pSession() {
        boolean isAllowed = isSatelliteAccessAllowedAtCurrentLocation();
        boolean isSatelliteEnabled = isSatelliteEnabled();
        boolean isEmergency = getRequestIsEmergency();

        plogd("evaluateDisablingP2pSession: isAllowed=" + isAllowed
                + " isEmergency=" + isEmergency
                + " isSatelliteEnabled:" + isSatelliteEnabled);
        if (!isAllowed && isSatelliteEnabled && !isEmergency) {
            // Disable P2P session if satellite is not allowed in current location
            disableSatelliteSession(isEmergency);
        }
    }

    private void disableSatelliteSession(boolean isEmergency) {
        plogd("disableSatelliteSession: isEmergency=" + isEmergency);
        requestSatelliteEnabled(false /* enableSatellite */,
                false /* enableDemoMode */, isEmergency,
                new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        plogd("disableSatelliteSession:"
                                + " requestSatelliteEnabled result=" + result);
                    }
                });
    }

    private void handleSatelliteAccessConfigUpdateResult(
        SatelliteAccessConfiguration satelliteAccessConfig) {
        plogd("handleSatelliteAccessConfigUpdateResult:" + " satelliteAccessConfig="
                    + satelliteAccessConfig);

        List<Integer> tagIds;
        List<Integer> carrierIds;
        if(satelliteAccessConfig != null) {
            tagIds = satelliteAccessConfig.getTagIds();
            carrierIds = satelliteAccessConfig.getCarrierIds();
        } else {
            tagIds = new ArrayList<>();
            carrierIds = new ArrayList<>();
        }

        if (mFeatureFlags.supportCarrierIdsInGeofence()) {
            if (!getCurrentLocationCarrierIds().equals(carrierIds)
                    || !getCurrentLocationTagIds().equals(tagIds)) {
                setCurrentLocationCarrierIds(carrierIds);
                setCurrentLocationTagIds(tagIds);
                selectBindingSatelliteSubscription(false);
                sendMessageDelayed(obtainMessage(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION),
                        mEvaluateEsosProfilesPrioritizationDurationMillis.get());
            } else {
                plogd("handleSatelliteAccessConfigUpdateResult: "
                        + "carrierIds and tagIds do not change");
            }
        } else if (!getCurrentLocationTagIds().equals(tagIds)) {
            setCurrentLocationTagIds(tagIds);
            selectBindingSatelliteSubscription(false);
            sendMessageDelayed(obtainMessage(CMD_EVALUATE_ESOS_PROFILES_PRIORITIZATION),
                    mEvaluateEsosProfilesPrioritizationDurationMillis.get());
        }
    }

    private void setCurrentLocationTagIds(@NonNull List<Integer> currentLocationTagIds) {
        synchronized (mSatelliteAccessConfigLock) {
            mCurrentLocationTagIds = currentLocationTagIds;
        }
    }

    private void setCurrentLocationCarrierIds(@NonNull List<Integer> currentLocationCarrierIds) {
        synchronized (mSatelliteAccessConfigLock) {
            mCurrentLocationCarrierIds = currentLocationCarrierIds;
        }
    }

    @NonNull
    private List<Integer> getCurrentLocationTagIds() {
        synchronized (mSatelliteAccessConfigLock) {
            return mCurrentLocationTagIds;
        }
    }

    @NonNull
    private List<Integer> getCurrentLocationCarrierIds() {
        synchronized (mSatelliteAccessConfigLock) {
            return mCurrentLocationCarrierIds;
        }
    }

    private void handleEventSatelliteRegistrationFailure(int causeCode) {
        plogd("handleEventSatelliteRegistrationFailure: " + causeCode);

        List<ISatelliteModemStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteRegistrationFailureListeners.values().forEach(listener -> {
            try {
                listener.onRegistrationFailure(causeCode);
            } catch (RemoteException e) {
                logd("handleEventSatelliteRegistrationFailure RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteRegistrationFailureListeners.remove(listener.asBinder());
        });
    }

    private void handleEventTerrestrialNetworkAvailableChanged(boolean isAvailable) {
        plogd("handleEventTerrestrialNetworkAvailableChanged: " + isAvailable);

        List<ISatelliteModemStateCallback> deadCallersList = new ArrayList<>();
        mTerrestrialNetworkAvailableChangedListeners.values().forEach(listener -> {
            try {
                listener.onTerrestrialNetworkAvailableChanged(isAvailable);
            } catch (RemoteException e) {
                logd("handleEventTerrestrialNetworkAvailableChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mTerrestrialNetworkAvailableChangedListeners.remove(listener.asBinder());
        });

        if (isAvailable && !mIsEmergency.get()) {
            requestSatelliteEnabled(
                    false /* enableSatellite */, false /* enableDemoMode */,
                    false /* isEmergency */,
                    new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int result) {
                            plogd("handleEventTerrestrialNetworkAvailableChanged:"
                                    + " requestSatelliteEnabled result=" + result);
                        }
                    });
        }
    }

    /**
     * This API can be used by only CTS to override the cached value for the device overlay config
     * value :
     * config_satellite_gateway_service_package and
     * config_satellite_carrier_roaming_esos_provisioned_class.
     * These values are set before sending an intent to broadcast there are any change to list of
     * subscriber informations.
     *
     * @param name the name is one of the following that constitute an intent.
     *             component package name, or component class name.
     * @return {@code true} if the setting is successful, {@code false} otherwise.
     */
    public boolean setSatelliteSubscriberIdListChangedIntentComponent(String name) {
        if (!isMockModemAllowed()) {
            logd("setSatelliteSubscriberIdListChangedIntentComponent: mock modem is not allowed");
            return false;
        }
        logd("setSatelliteSubscriberIdListChangedIntentComponent:" + name);

        if (name.contains("/")) {
            mChangeIntentComponent.set(true);
        } else {
            mChangeIntentComponent.set(false);
            return true;
        }
        boolean result = true;
        String[] cmdPart = name.split("/");
        switch (cmdPart[0]) {
            case "-p": {
                mConfigSatelliteGatewayServicePackage = cmdPart[1];
                break;
            }
            case "-c": {
                mConfigSatelliteCarrierRoamingEsosProvisionedClass = cmdPart[1];
                break;
            }
            default:
                logd("setSatelliteSubscriberIdListChangedIntentComponent: invalid name " + name);
                result = false;
                break;
        }
        return result;
    }

    /**
     * This API can be used by only CTS to override the satellite access allowed state for
     * a list of subscription IDs.
     *
     * @param reset {@code true} mean the overridden configs should not be used, {@code false}
     *              otherwise.
     * @param subIdListStr The string representation of the list of subscription IDs,
     *                     which are numbers separated by comma.
     * @return {@code true} if the satellite access allowed state is set successfully,
     * {@code false} otherwise.
     */
    public boolean setSatelliteAccessAllowedForSubscriptions(
        boolean reset, @Nullable String subIdListStr) {
        if (!isMockModemAllowed()) {
            plogd("setSatelliteAccessAllowedForSubscriptions: mock modem not allowed");
            return false;
        }

        plogd("setSatelliteAccessAllowedForSubscriptions: subIdListStr=" + subIdListStr
                  + " reset=" + reset);
        boolean result = true;
        if (reset) {
            mCtsSatelliteAccessAllowedSubIds = null;
        } else {
            mCtsSatelliteAccessAllowedSubIds = new ArrayList<>();
            if (!TextUtils.isEmpty(subIdListStr)) {
                for (String subIdStr : subIdListStr.split(",")) {
                    try {
                        mCtsSatelliteAccessAllowedSubIds.add(Integer.parseInt(subIdStr));
                    } catch (NumberFormatException e) {
                        plogd("setSatelliteAccessAllowedForSubscriptions: invalid subIdStr="
                                + subIdStr);
                        mCtsSatelliteAccessAllowedSubIds = null;
                        result = false;
                    }
                }
            }
        }
        boolean isAllowed = mSatelliteAccessAllowed.get();
        plogd("setSatelliteAccessAllowedForSubscriptions: isAllowed=" + isAllowed);
        sendMessage(obtainMessage(EVENT_SATELLITE_ACCESS_ALLOWED_STATE_CHANGED, isAllowed));
        return result;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected String getConfigSatelliteGatewayServicePackage() {
        if (!mChangeIntentComponent.get()) {
            return getStringFromOverlayConfig(
                    R.string.config_satellite_gateway_service_package);
        }
        logd("getConfigSatelliteGatewayServicePackage: " + mConfigSatelliteGatewayServicePackage);
        return mConfigSatelliteGatewayServicePackage;
    }

    private String getConfigSatelliteCarrierRoamingEsosProvisionedClass() {
        if (!mChangeIntentComponent.get()) {
            return getStringFromOverlayConfig(
                    R.string.config_satellite_carrier_roaming_esos_provisioned_class);
        }
        logd("getConfigSatelliteCarrierRoamingEsosProvisionedClass: "
                + mConfigSatelliteCarrierRoamingEsosProvisionedClass);
        return mConfigSatelliteCarrierRoamingEsosProvisionedClass;
    }

    private void registerDefaultSmsSubscriptionChangedBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED);
        mContext.registerReceiver(mDefaultSmsSubscriptionChangedBroadcastReceiver, intentFilter);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected List<DeviceState> getSupportedDeviceStates() {
        return mContext.getSystemService(DeviceStateManager.class).getSupportedDeviceStates();
    }

    FeatureFlags getFeatureFlags() {
        return mFeatureFlags;
    }

    /**
     * Get whether satellite modem is disabled.
     *
     * @return {@code true} if the satellite modem is disabled and {@code false} otherwise.
     */
    public boolean isSatelliteDisabled() {
        return ((mIsSatelliteEnabled != null) && !mIsSatelliteEnabled.get());
    }

    private boolean shouldStopWaitForEnableResponseTimer(
            @NonNull RequestSatelliteEnabledArgument argument) {
        if (argument.enableSatellite) return true;
        return !mWaitingForSatelliteModemOff.get();
    }

    /**
     * Method to override the Carrier roaming Non-terrestrial network eligibility check
     *
     * @param state         flag to enable or disable the Ntn eligibility check.
     * @param resetRequired reset overriding the check with adb command.
     */
    public boolean overrideCarrierRoamingNtnEligibilityChanged(boolean state,
            boolean resetRequired) {
        Log.d(TAG, "overrideCarrierRoamingNtnEligibilityChanged state = " + state
                + "  resetRequired = " + resetRequired);
        if (resetRequired) {
            mOverrideNtnEligibility = null;
        } else {
            if (mOverrideNtnEligibility == null) {
                mOverrideNtnEligibility = new AtomicBoolean(state);
            } else {
                mOverrideNtnEligibility.set(state);
            }
            Phone satellitePhone = getSatellitePhone();
            if (satellitePhone != null) {
                updateLastNotifiedNtnEligibilityAndNotify(state);
            }
        }
        return true;
    }

    /**
     * This method check for the key KEY_SATELLITE_MAX_DATAGRAM_SIZE in carrier config. If
     * available it fetches the value and override the same in SatelliteCapabilities. Otherwise it
     * uses the value in the existed mSatelliteCapabilities.
     */
    private void overrideSatelliteCapabilitiesIfApplicable() {
        int subId = getSelectedSatelliteSubId();
        PersistableBundle config = getPersistableBundle(subId);
        if (config.containsKey(KEY_SATELLITE_SOS_MAX_DATAGRAM_SIZE_BYTES_INT)) {
            int datagramSize = config.getInt(KEY_SATELLITE_SOS_MAX_DATAGRAM_SIZE_BYTES_INT);
            SubscriptionInfo subInfo = mSubscriptionManagerService.getSubscriptionInfo(subId);
            if (!(subInfo == null || subInfo.isOnlyNonTerrestrialNetwork())) {
                synchronized (mSatelliteCapabilitiesLock) {
                    this.mSatelliteCapabilities.setMaxBytesPerOutgoingDatagram(datagramSize);
                }
            }
        }
    }

    /**
     * This method returns subscription id for supporting Ntn Only
     */
    public int getNtnOnlySubscriptionId() {
        List<SubscriptionInfo> infoList = mSubscriptionManagerService.getAllSubInfoList(
                        mContext.getOpPackageName(), mContext.getAttributionTag());
        int subId = infoList.stream()
                .filter(info -> info.isOnlyNonTerrestrialNetwork())
                .mapToInt(SubscriptionInfo::getSubscriptionId)
                .findFirst()
                .orElse(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        logd("getNtnOnlySubscriptionId: subId=" + subId);
        return subId;
    }

    @Nullable
    private List<SatelliteSubscriberInfo> getNtnOnlySatelliteSubscriberInfoList(
            Consumer<Integer> result) {
        SatelliteSubscriberInfo satelliteSubscriberInfo = getNtnOnlySatelliteSubscriberInfo();
        if (satelliteSubscriberInfo == null) {
            result.accept(SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED);
            return null;
        }
        List<SatelliteSubscriberInfo> satelliteSubscriberInfoList = new ArrayList<>();
        satelliteSubscriberInfoList.add(satelliteSubscriberInfo);

        return satelliteSubscriberInfoList;
    }

    @Nullable private SatelliteSubscriberInfo getNtnOnlySatelliteSubscriberInfo() {
        int ntnOnlySubId = getNtnOnlySubscriptionId();
        if (ntnOnlySubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            logw("getNtnOnlySatelliteSubscriberInfo: no ntn only subscription found");
            return null;
        }
        SubscriptionInfo subInfo = mSubscriptionManagerService.getSubscriptionInfo(ntnOnlySubId);
        if (subInfo == null) {
            logw("getNtnOnlySatelliteSubscriberInfo: no subscription info found for subId="
                    + ntnOnlySubId);
            return null;
        }
        return getSatelliteSubscriberInfo(subInfo);
    }

    @Nullable private SatelliteSubscriberInfo getSatelliteSubscriberInfo(
        @NonNull SubscriptionInfo subInfo) {
        Pair<String, Integer> subscriberIdPair = getSubscriberIdAndType(subInfo);
        String subscriberId = subscriberIdPair.first;
        int carrierId = subInfo.getCarrierId();
        String apn = getConfigForSubId(subInfo.getSubscriptionId())
                .getString(KEY_SATELLITE_NIDD_APN_NAME_STRING, "");
        logd("getSatelliteSubscriberInfo: subInfo: " + subInfo
                + ", subscriberId:" + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, subscriberId)
                + " , carrierId=" + carrierId + " , apn=" + apn);
        if (subscriberId.isEmpty()) {
            logw("getSatelliteSubscriberInfo: not a satellite subscription.");
            return null;
        }
        return new SatelliteSubscriberInfo.Builder().setSubscriberId(subscriberId)
                        .setCarrierId(carrierId).setNiddApn(apn)
                        .setSubscriptionId(subInfo.getSubscriptionId())
                        .setSubscriberIdType(subscriberIdPair.second)
                        .build();
    }

    /**
     * The method will notify the change in the services update the
     * mEntitlementServiceTypeMapPerCarrier.
     *
     * @param subId              : SubscriptionId
     * @param plmnServiceTypeMap : entitlement service map.
     */
    private void updateAndNotifyChangesInCarrierRoamingNtnAvailableServices(int subId,
            Map<String, List<Integer>> plmnServiceTypeMap) {
        // If a service list is already cached, check it for changes
        int[] existingServices = getSupportedServicesOnCarrierRoamingNtn(subId);
        mEntitlementServiceTypeMapPerCarrier.put(subId, plmnServiceTypeMap);
        int[] updatedServices = getSupportedServicesOnCarrierRoamingNtn(subId);
        if (existingServices.length > 0 && Arrays.equals(existingServices, updatedServices)) {
            plogd("No change in Entitlement service support data");
            return;
        }
        updateLastNotifiedNtnAvailableServicesAndNotify(subId);
        evaluateCarrierRoamingNtnEligibilityChange();
    }

    private void handleCarrierRoamingNtnAvailableServicesChanged() {
        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds == null) {
            plogd("handleCarrierRoamingNtnAvailableServicesChanged: activeSubIds is null.");
            return;
        }

        plogd("handleCarrierRoamingNtnAvailableServicesChanged: activeSubIds size="
                + activeSubIds.length);
        for (int subId: activeSubIds) {
            handleCarrierRoamingNtnAvailableServicesChanged(subId);
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void handleCarrierRoamingNtnAvailableServicesChanged(int subId) {
        plogd("handleCarrierRoamingNtnAvailableServicesChanged: subId=" + subId);
        updateLastNotifiedNtnAvailableServicesAndNotify(subId);
        evaluateCarrierRoamingNtnEligibilityChange();
    }

    private void updateLastNotifiedNtnAvailableServicesAndNotify(int subId) {
        Phone phone = SatelliteServiceUtils.getPhone(subId);
        if (phone == null) {
            plogd("notifyNtnAvailableServices: phone is null.");
            return;
        }
        plogd("updateLastNotifiedNtnAvailableServicesAndNotify: phoneId= " + phone.getPhoneId());
        int[] services = getSupportedServicesOnCarrierRoamingNtn(subId);
        phone.notifyCarrierRoamingNtnAvailableServicesChanged(services);
    }

    private int[] getAvailableServicesWithEntitlementForSubId(int subId) {
        Map<String, List<Integer>> allowedServicesList =
                mEntitlementServiceTypeMapPerCarrier.get(subId);
        if (allowedServicesList != null && !allowedServicesList.isEmpty()) {
            Set<Integer> serviceTypes = new HashSet<>();
            for (List<Integer> values : allowedServicesList.values()) {
                serviceTypes.addAll(values);
            }

            int[] result = new int[serviceTypes.size()];
            int i = 0;
            for (int value : serviceTypes) {
                result[i++] = value;
            }
            return result;
        } else {
            return new int[0]; // Return an empty array if the map is null or empty
        }
    }

    private int[] getSupportedSatelliteServicesFromConfig(int subId) {
        Map<String, Set<Integer>> supportedServicesList =
                mSatelliteServicesSupportedByCarriersFromConfig.get(subId);
        if (supportedServicesList == null || supportedServicesList.isEmpty()) {
            return new int[0];
        }

        Set<Integer> serviceTypesSet = new HashSet<>();
        for (Set<Integer> values : supportedServicesList.values()) {
            serviceTypesSet.addAll(values);
        }

        return serviceTypesSet.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Given a subscription ID, this returns the carriers' supported services on
     * non-terrestrial networks.
     *
     * @param subId Associated subscription ID.
     * return supported services at entitlement for the available carriers.
     *        The data source used for supported services is as follows:
     *              1. Return supported services from entitlement info if available
     *              2. Return supported services provided by config updater if available
     *              3. Return services from {@link CarrierConfigManager
     *              #KEY_CARRIER_SUPPORTED_SATELLITE_SERVICES_PER_PROVIDER_BUNDLE}
     *              4. Else return default services present in {@link CarrierConfigManager
     *              #KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY}
     */
    public int[] getSupportedServicesOnCarrierRoamingNtn(int subId) {
        if (isValidSubscriptionId(subId) && isSatelliteSupportedViaCarrier(subId)) {
            // check available services supported at entitlement for sub id
            int[] services = getAvailableServicesWithEntitlementForSubId(subId);
            plogd("getSupportedServicesOnCarrierRoamingNtn[DataSource=Entitlement]: subId=" + subId
                    + " services=" + Arrays.toString(services));

            if (services.length == 0) {
                services = getSupportedSatelliteServicesFromConfig(subId);
                plogd("getSupportedServicesOnCarrierRoamingNtn[DataSource=Config]: subId=" + subId
                        + " services=" + Arrays.toString(services));
            }

            if (services.length == 0) {
                services = getSatelliteDefaultServicesFromCarrierConfig(subId);
                plogd("getSupportedServicesOnCarrierRoamingNtn[DataSource=Default Services]: "
                        + "subId=" + subId + " services=" + Arrays.toString(services));
            }

            if (isP2PSmsDisallowedOnCarrierRoamingNtn(subId)) {
                services = Arrays.stream(services).filter(
                        value -> value != NetworkRegistrationInfo.SERVICE_TYPE_SMS).toArray();
            }
            return services;
        }
        return new int[0];
    }

    /**
     * Returns the current satellite config for the given subscription ID.
     *
     * @param subId The subscription ID of the carrier.
     * @return The {@link SatellitePerPlmnConfiguration} object containing the current satellite
     *         config.
     */
    @Nullable
    public SatellitePerPlmnConfiguration getSatellitePerPlmnConfiguration(int subId) {
        if (!isValidSubscriptionId(subId) || !isSatelliteSupportedViaCarrier(subId)) {
            plogd("getSatellitePerPlmnConfiguration: invalid subId or not supported via carrier.");
            return null;
        }
        SatellitePerPlmnConfiguration config = mCurrentSatellitePerPlmnConfigurations
                .getOrDefault(subId, null);
        plogd("getSatellitePerPlmnConfiguration: subId=" + subId + " return " + config);
        return config;
    }

    /**
     * Whether the P2P SMS over carrier roaming satellite is disallowed or not.
     *
     * @param subId Associated subscription ID
     * return {@code true} when the phone does not support P2P SMS over carrier roaming satellite
     *        {@code false} otherwise
     */
    public boolean isP2PSmsDisallowedOnCarrierRoamingNtn(int subId) {
        int connectType = getCarrierRoamingNtnConnectType(subId);
        if (connectType == CARRIER_ROAMING_NTN_CONNECT_MANUAL
                || (mFeatureFlags.vzwAstSkyloFallback()
                        && connectType == CARRIER_ROAMING_NTN_CONNECT_HYBRID)) {
            // Manual Connected
            plogd(
                    "isP2PSmsDisallowedOnCarrierRoamingNtn: manual connect or hybrid."
                            + " connectType="
                            + connectType);
            if (!isNtnSmsSupportedByMessagesApp()
                    || !isApplicationSupportsP2P(getSatelliteGatewayServicePackageName())) {
                plogd("isP2PSmsDisallowedOnCarrierRoamingNtn: APKs do not supports P2P");
                return true;
            }

            if (!isSubscriptionProvisioned(subId)) {
                plogd("isP2PSmsDisallowedOnCarrierRoamingNtn: subId=" + subId
                        + " is not provisioned.");
                return true;
            }
        }
        plogd("isP2PSmsDisallowedOnCarrierRoamingNtn: P2P is supported");
        return false;
    }

    private String getSatelliteGatewayServicePackageName() {
        if (!TextUtils.isEmpty(mOverriddenSatelliteGatewayServicePackageName)) {
            logd("getSatelliteGatewayServicePackageName: return overridden package name"
                    + " for CTS test " + mOverriddenSatelliteGatewayServicePackageName);
            return mOverriddenSatelliteGatewayServicePackageName;
        }
        return mSatelliteGatewayServicePackageName;
    }

    @NonNull
    private int[] getSatelliteDefaultServicesFromCarrierConfig(int subId) {
        PersistableBundle config = getPersistableBundle(subId);
        int[] availableServices =
                config.getIntArray(KEY_CARRIER_ROAMING_SATELLITE_DEFAULT_SERVICES_INT_ARRAY);
        if (availableServices == null) {
            logd("getSatelliteDefaultServicesFromCarrierConfig: defaultCapabilities is null");
            return new int[0];
        }
        logd("getSatelliteDefaultServicesFromCarrierConfig: subId=" + subId
                + ", return default values " + Arrays.toString(availableServices));
        return availableServices;
    }

    /**
     * Whether application supports the P2P SMS to connect to carrier roaming non-terrestrial
     * network.
     *
     * @param packageName application's default package name
     * return {@code true} when the application supports P2P SMS over the roaming satellite
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean isApplicationSupportsP2P(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo applicationInfo = new ApplicationInfo();
        try {
            applicationInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            logd("isApplicationSupportsP2P pkgName: " + packageName + " is not installed.");
            return false;
        }
        if (applicationInfo == null || applicationInfo.metaData == null) {
            logd("isApplicationSupportsP2P pkgName: " + packageName + " meta-data info is empty.");
            return false;
        }
        return applicationInfo.metaData.getBoolean(
                SatelliteManager.METADATA_SATELLITE_MANUAL_CONNECT_P2P_SUPPORT);
    }

    /**
     * Registers for the applications state changed.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void registerApplicationStateChanged() {
        mSatelliteGatewayServicePackageName = getConfigSatelliteGatewayServicePackage();

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiver(mPackageStateChangedReceiver, packageFilter,
                mContext.RECEIVER_NOT_EXPORTED);

        IntentFilter smsFilter = new IntentFilter();
        smsFilter.addAction(ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL);
        mContext.registerReceiver(mPackageStateChangedReceiver, smsFilter,
                mContext.RECEIVER_NOT_EXPORTED);
    }

    private void registerLocationServiceStateChanged() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(LocationManager.MODE_CHANGED_ACTION);
        mContext.registerReceiver(mLocationServiceStateChangedReceiver, intentFilter);
    }


    private void notifyEnabledStateChanged(boolean isEnabled) {
        TelephonyRegistryManager trm = mContext.getSystemService(TelephonyRegistryManager.class);
        if (trm == null) {
            loge("Telephony registry service is down!");
            return;
        }

        trm.notifySatelliteStateChanged(isEnabled);
        logd("notifyEnabledStateChanged to " + isEnabled);
    }

    private void setNtnSignalStrength(@NonNull NtnSignalStrength ntnSignalStrength) {
        synchronized (mNtnSignalsStrengthLock) {
            mNtnSignalStrength = ntnSignalStrength;
        }
    }

    @NonNull
    private NtnSignalStrength getNtnSignalStrength() {
        synchronized (mNtnSignalsStrengthLock) {
            return mNtnSignalStrength;
        }
    }

    private NtnSignalStrength getCarrierRoamingNtnSignalStrength(@NonNull Phone phone) {
        NtnSignalStrength carrierRoamingNtnSignalStrength = new NtnSignalStrength(
                NTN_SIGNAL_STRENGTH_NONE);

        if (isInCarrierRoamingNbIotNtn(phone)) {
            if (isInConnectedState()) {
                carrierRoamingNtnSignalStrength = getNtnSignalStrength();
                plogd("getCarrierRoamingNtnSignalStrength[phoneId=" + phone.getPhoneId()
                        + "]: in carrier roaming nb iot ntn mode.");
            }
        } else if (isInSatelliteModeForCarrierRoaming(phone)) {
            ServiceState serviceState = phone.getServiceState();
            if (serviceState.getState() != ServiceState.STATE_OUT_OF_SERVICE
                    || serviceState.getDataRegState() != ServiceState.STATE_OUT_OF_SERVICE) {
                carrierRoamingNtnSignalStrength = new NtnSignalStrength(
                        phone.getSignalStrength().getLevel());
                plogd("getCarrierRoamingNtnSignalStrength[phoneId=" + phone.getPhoneId()
                        + "]: is in satellite mode for carrier roaming.");
            }
        }

        return carrierRoamingNtnSignalStrength;
    }

    /**
     * Returns satellite connected state from modem, return true if connected.
     */
    public boolean isInConnectedState() {
        switch (mSatelliteModemState.get()) {
            case SatelliteManager.SATELLITE_MODEM_STATE_CONNECTED: //fallthrough
            case SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING: //fallthrough
            case SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_RETRYING: //fallthrough
            case SatelliteManager.SATELLITE_MODEM_STATE_IDLE:
                plogd("isInConnectedState: return true");
                return true;
            default:
                plogd("isInConnectedState: return false");
                return false;
        }
    }

    protected void updateLastNotifiedCarrierRoamingNtnSignalStrengthAndNotify(
            @Nullable Phone phone) {
        if (phone == null) {
            return;
        }

        NtnSignalStrength currSignalStrength = getCarrierRoamingNtnSignalStrength(phone);
        int subId = phone.getSubId();
        NtnSignalStrength lastNotifiedSignalStrength =
                mLastNotifiedCarrierRoamingNtnSignalStrength.get(subId);
        plogd("updateLastNotifiedCarrierRoamingNtnSignalStrengthAndNotify(" + subId + ")");
        if (lastNotifiedSignalStrength != null) {
            plogd(
                    "updateLastNotifiedCarrierRoamingNtnSignalStrengthAndNotify("
                            + subId
                            + ")"
                            + " lastNotifiedSignalStrength level: "
                            + lastNotifiedSignalStrength.getLevel());
        }
        if (currSignalStrength != null) {
            plogd(
                    "updateLastNotifiedCarrierRoamingNtnSignalStrengthAndNotify("
                            + subId
                            + ")"
                            + " currSignalStrength level: "
                            + currSignalStrength.getLevel());
        }
        if (lastNotifiedSignalStrength == null
                || lastNotifiedSignalStrength.getLevel() != currSignalStrength.getLevel()) {
            mLastNotifiedCarrierRoamingNtnSignalStrength.put(subId, currSignalStrength);
            phone.notifyCarrierRoamingNtnSignalStrengthChanged(currSignalStrength);
        }
    }

    /** Returns whether to send SMS to DatagramDispatcher or not. */
    public boolean shouldSendSmsToDatagramDispatcher(@Nullable Phone phone) {
        final long identity = Binder.clearCallingIdentity();
        try {
            if (!isInCarrierRoamingNbIotNtn(phone)) {
                return false;
            }

            if (isDemoModeEnabled()) {
                return false;
            }

            int[] services = getSupportedServicesOnCarrierRoamingNtn(phone.getSubId());
            return ArrayUtils.contains(services, NetworkRegistrationInfo.SERVICE_TYPE_SMS);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /** Returns whether to drop SMS or not. */
    public boolean shouldDropSms(@Nullable Phone phone) {
        final long identity = Binder.clearCallingIdentity();
        try {
            if (!isInCarrierRoamingNbIotNtn(phone)) {
                return false;
            }

            int[] services = getSupportedServicesOnCarrierRoamingNtn(phone.getSubId());
            return !ArrayUtils.contains(services, NetworkRegistrationInfo.SERVICE_TYPE_SMS);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean isWaitingForSatelliteModemOff() {
        return mWaitingForSatelliteModemOff.get();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected void setIsSatelliteSupported(boolean isSupported) {
        if (mIsSatelliteSupported == null) {
            mIsSatelliteSupported = new AtomicBoolean(isSupported);
        } else {
            mIsSatelliteSupported.set(isSupported);
        }
    }

    @Nullable
    private Boolean getIsSatelliteSupported() {
        if (mIsSatelliteSupported == null) {
            return null;
        }
        return mIsSatelliteSupported.get();
    }

    private boolean isWaitingForDisableSatelliteModemResponse() {
        return mWaitingForDisableSatelliteModemResponse.get();
    }

    private boolean isSatelliteAccessAllowedAtCurrentLocation() {
        return mSatelliteAccessAllowed.get();
    }

    private void setIsSatelliteEnabled(boolean enabled) {
        if (mIsSatelliteEnabled == null) {
            mIsSatelliteEnabled = new AtomicBoolean(enabled);
        } else {
            mIsSatelliteEnabled.set(enabled);
        }
    }

    @Nullable
    private Boolean getIsSatelliteEnabled() {
        if (mIsSatelliteEnabled == null) {
            return null;
        }
        return mIsSatelliteEnabled.get();
    }

    private void setSatelliteCapabilities(@Nullable SatelliteCapabilities capabilities) {
        synchronized (mSatelliteCapabilitiesLock) {
            mSatelliteCapabilities = capabilities;
        }
    }

    @Nullable
    private SatelliteCapabilities getSatelliteCapabilities() {
        synchronized (mSatelliteCapabilitiesLock) {
            return mSatelliteCapabilities;
        }
    }

    private void setBTEnabledState(boolean enabled) {
        mBTStateEnabled.set(enabled);
    }

    private boolean getBTEnabledState() {
        return mBTStateEnabled.get();
    }

    private void setNfcEnabledState(boolean enabled) {
        mNfcStateEnabled.set(enabled);
    }

    private boolean getNfcEnabledState() {
        return mNfcStateEnabled.get();
    }

    private void setUwbEnabledState(boolean enabled) {
        mUwbStateEnabled.set(enabled);
    }

    private boolean getUwbEnabledState() {
        return mUwbStateEnabled.get();
    }

    private void setWifiEnabledState(boolean enabled) {
        mWifiStateEnabled.set(enabled);
    }

    private boolean getWifiEnabledState() {
        return mWifiStateEnabled.get();
    }

    private void handleCarrierRoamingConfigVersionReport() {
        logd("handleCarrierRoamingConfigVersionReport");
        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds != null && activeSubIds.length > 0) {
            for (int subId : activeSubIds) {
                SatelliteConfig satelliteConfig = getSatelliteConfig();
                if (satelliteConfig != null) {
                    int carrierId = SatelliteServiceUtils.getCarrierIdFromSubscription(subId);
                    mControllerMetricsStats.reportCurrentVersionOfCarrierRoamingSatelliteConfig(
                            carrierId, satelliteConfig.getSatelliteConfigDataVersion(),
                            getSupportedConnectTypeMetrics(subId));
                } else {
                    loge("handleCarrierRoamingConfigVersionReport: "
                            + "no satellite config by configupdater");
                }
            }
        } else {
            loge("handleMaxAllowedDataMetricsReport: no active subId");
        }
    }

    private void handleMaxAllowedDataMetricsReport() {
        logd("handleMaxAllowedDataMetricsReport");
        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds != null && activeSubIds.length > 0) {
            for (int subId : activeSubIds) {
                int maxAllowedDataMode = getMaxAllowedDataMode();
                int carrierId = SatelliteServiceUtils.getCarrierIdFromSubscription(subId);
                mControllerMetricsStats
                        .reportCurrentMaxAllowedDataMode(carrierId, maxAllowedDataMode,
                                getSupportedConnectTypeMetrics(subId));
            }
        } else {
            loge("handleMaxAllowedDataMetricsReport: no active subId");
        }
    }

    private void handleEntireEntitlementMetricReport() {
        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds != null && activeSubIds.length > 0) {
            for (int subId : activeSubIds) {
                boolean isSubIdEntitled = mSatelliteEntitlementStatusPerCarrier.computeIfAbsent(
                        subId, k -> false);
                plogd("handleEntitlementMetricReport: subId=" + subId + ", isSubEntitled="
                        + isSubIdEntitled);
                reportEntitlementStatusAndEligibility(subId, isSubIdEntitled);
            }
        } else {
            loge("handleEntireEntitlementMetricReport: no active subId");
        }
    }

    private void handleIndividualEntitlementMetricReport(int subId,
            boolean isSubIdEntitled) {
        mSatelliteEntitlementStatusPerCarrier.put(subId, isSubIdEntitled);
        reportEntitlementStatusAndEligibility(subId, isSubIdEntitled);
    }

    private void reportEntitlementStatusAndEligibility(int subId, boolean isSubIdEntitled) {
        mCarrierRoamingSatelliteControllerStats.reportIsDeviceEntitled(subId, isSubIdEntitled);
        updateAndReportEligibilitySource(subId);
    }

    private void handleEntireProvisionMetricReport() {
        logd("handleEntireProvisionMetricReport:");
        // Hold the final aggregated status for each carrierId.
        Map<Integer, CarrierReportInfo> reportDataPerCarrier = new HashMap<>();
        // Aggregate provision status and isNtnOnlyCarrier info per carrierId
        List<SubscriptionInfo> allSubInfos = mSubscriptionManagerService.getAllSubInfoList(
                mContext.getOpPackageName(), mContext.getAttributionTag());
        for (SubscriptionInfo info : allSubInfos) {
            int subId = info.getSubscriptionId();
            boolean isNtnOnlySubId = info.isOnlyNonTerrestrialNetwork();
            boolean isActiveSubId = info.isActive();

            if (!isNtnOnlySubId && !isActiveSubId) {
                plogd("handleEntireProvisionMetricReport: subId=" + subId
                        + " is neither NTN-only nor active. Skipping.");
                continue;
            }

            int carrierId = SatelliteServiceUtils.getCarrierIdFromSubscription(subId);
            if (carrierId == TelephonyManager.UNKNOWN_CARRIER_ID && !isNtnOnlySubId) {
                plogd("handleEntireProvisionMetricReport: neither valid carrierId "
                        + "nor NTN-only, subId=" + subId + ". Skipping.");
                continue;
            }

            String subscriberId = getSubscriberIdAndType(info).first;
            boolean isProvisioned = mProvisionedSubscriberId.getOrDefault(subscriberId, false);

            CarrierReportInfo carrierInfo = reportDataPerCarrier.computeIfAbsent(
                    carrierId, key -> new CarrierReportInfo());
            carrierInfo.aggregate(isProvisioned, isNtnOnlySubId,
                    getSupportedConnectTypeMetrics(subId));
        }

        // Report the aggregated status for each carrierId
        if (reportDataPerCarrier.isEmpty()) {
            plogd("handleEntireProvisionMetricReport: No reportable data found");
        } else {
            plogd("handleEntireProvisionMetricReport: Reporting final aggregated status for "
                    + reportDataPerCarrier.size() + " carrier(s).");
            for (Map.Entry<Integer, CarrierReportInfo> reportEntry :
                    reportDataPerCarrier.entrySet()) {
                int carrierId = reportEntry.getKey();
                CarrierReportInfo info = reportEntry.getValue();

                plogd("handleEntireProvisionMetricReport: Final report for carrierId="
                        + carrierId + ", isProvisioned=" + info.mIsAnySubProvisioned
                        + ", isNtnOnlyCarrier=" + info.mIsNtnOnlyCarrier);

                mControllerMetricsStats.setIsProvisioned(
                        carrierId,
                        info.mIsAnySubProvisioned,
                        info.mIsNtnOnlyCarrier,
                        info.mSupportedConnectionMode);
            }
        }
    }

    /**
     * Periodically reports the current eligibility source for all active subscriptions.
     * This is typically called once a day to ensure metrics are up-to-date.
     */
    private void handleEntireEligibilityMetricReport() {
        if (!mFeatureFlags.satelliteMetricsEnhancement()) {
            logd("handleEntireEligibilityMetricReport: satelliteMetricsEnhancement is not enabled"
                    + ". ignore.");
            return;
        }

        int[] activeSubIds = mSubscriptionManagerService.getActiveSubIdList(true);
        if (activeSubIds.length > 0) {
            for (int subId : activeSubIds) {
                updateAndReportEligibilitySource(subId);
            }
        } else {
            loge("handleEntireEligibilityMetricReport: no active subId");
        }
    }

    /**
     * Determines and reports the satellite eligibility source for the given subId
     * based on the carrier configuration and its supported eligibility mechanism.
     */
    private void updateAndReportEligibilitySource(int subId) {
        if (!mFeatureFlags.satelliteMetricsEnhancement()) {
            logd("updateAndReportEligibilitySource: satelliteMetricsEnhancement is not enabled"
                    + ". ignore.");
            return;
        }

        if (!isValidSubscriptionId(subId)) {
            plogw("updateAndReportEligibilitySource: Invalid subId=" + subId);
            return;
        }

        int newSource = SatelliteConstants.SATELLITE_ELIGIBILITY_SOURCE_UNKNOWN;
        boolean attachSupported = isSatelliteSupportedViaCarrier(subId);
        boolean entitlementSupportedByCarrier = getConfigForSubId(subId).getBoolean(
                KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
        if (attachSupported) {
            if (entitlementSupportedByCarrier) {
                newSource = SatelliteConstants.SATELLITE_ELIGIBILITY_SOURCE_ENTITLEMENT;
            } else {
                newSource = SatelliteConstants.SATELLITE_ELIGIBILITY_SOURCE_CARRIER_CONFIG;
            }
        }

        mSatelliteEligibilitySource.put(subId, newSource);
        mCarrierRoamingSatelliteControllerStats.reportDeviceEligibilitySource(subId,
                attachSupported, newSource);
        plogd("updateEligibilitySource: subId=" + subId + ", attachSupported=" + attachSupported
                + ", entitlementSupported=" + entitlementSupportedByCarrier
                + ", reportedSource=" + newSource);
    }

    // Helper class to store aggregated information per carrierId.
    private static class CarrierReportInfo {
        boolean mIsAnySubProvisioned = false;
        boolean mIsNtnOnlyCarrier = false;
        int mSupportedConnectionMode = SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;

        void aggregate(boolean isProvisioned, boolean isNtnOnlyCarrier,
                int supportedConnectionMode) {
            // if any subId for the carrier was provisioned, this carrier is provisioned
            if (isProvisioned) {
                this.mIsAnySubProvisioned = true;
            }
            this.mIsNtnOnlyCarrier = isNtnOnlyCarrier;
            this.mSupportedConnectionMode = supportedConnectionMode;
        }
    }

    private void scheduleRegularMetricReportTimer() {
        if (mAlarmManager == null) {
            plogd("scheduleRegularMetricReportTimer: AlarmManager is null");
            return;
        }
        mAlarmManager.cancel(mRegularMetricReportAlarmListener);
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                getElapsedRealtime() + REGULAR_METRIC_REPORTING_INTERVAL_MILLIS,
                TAG, new HandlerExecutor(this), new WorkSource(),
                mRegularMetricReportAlarmListener);
    }

    /**
     * Uses this function to set AlarmManager object for testing.
     *
     * @param alarmManager The instance of AlarmManager.
     */
    @VisibleForTesting
    public void setAlarmManager(AlarmManager alarmManager) {
        mAlarmManager = alarmManager;
    }

    /**
     * Method to return the current data plan for the registered plmn based on entitlement
     * provisioning information. Note: If no information at
     * provisioning is supported this is overridden with operator carrier config information.
     *
     * @param subId current subscription id
     * @param plmn current registered plmn information
     *
     * @return Data supported modes {@link SatelliteController#SATELLITE_DATA_PLAN_METERED}
     */
    public int getSatelliteDataPlanForPlmn(int subId, String plmn) {
        if (plmn != null) {
            Map<String, Integer> dataplanMap = mEntitlementDataPlanMapPerCarrier.get(subId);
            logd("data plan available for sub id:" + dataplanMap);
            if (dataplanMap != null && dataplanMap.containsKey(plmn)) {
                return dataplanMap.get(plmn);
            }
        }
        // TODO (Override with carrier config value when configuration defined)
        return SATELLITE_DATA_PLAN_METERED;
    }

    /**
     * Check if the available satellite services support
     * {@link NetworkRegistrationInfo#SERVICE_TYPE_DATA} service or not.
     *
     * @return {@code true} if data services is supported, otherwise {@code false}.
     */
    private boolean isSatelliteDataServicesAllowed(int subId, String plmn) {
        // validate is available services support data, for satellite internet bringup
        List<Integer> availableServices = getSupportedSatelliteServicesForPlmn(subId, plmn);
        return availableServices.stream().anyMatch(num -> num
                == NetworkRegistrationInfo.SERVICE_TYPE_DATA);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected boolean isLocationServiceEnabled() {
        LocationManager lm = mContext.createAttributionContext("telephony")
                .getSystemService(LocationManager.class);
        if (lm != null) {
            return lm.isLocationEnabled();
        }

        return true;
    }

    /**
     * Method to return the current satellite data service policy supported mode for the registered
     * plmn based on entitlement provisioning information. Note: If no information at provisioning
     * is supported this is overridden with operator carrier config information if available
     * satellite services support data else data service policy is marked as restricted. Note that
     * the data service policy is capped at maxAllowedDataMode which is configured by OEMs through
     * {@link R.integer.max_allowed_data_mode} or through {@link SatelliteConfig}
     *
     * @param subId current subscription id
     * @param plmn current registered plmn information
     * @return Supported modes {@link CarrierConfigManager.SATELLITE_DATA_SUPPORT_MODE}
     */
    public int getSatelliteDataServicePolicyForPlmn(int subId, String plmn) {
        plogd("getSatelliteDataServicePolicyForPlmn: subId=" + subId + " plmn=" + plmn);
        int maxAllowedDataMode = getMaxAllowedDataMode();
        plogd("getSatelliteDataServicePolicyForPlmn: maxAllowedDataMode=" + maxAllowedDataMode);

        if (maxAllowedDataMode == CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED) {
            plogd(
                    "maxAllowedDataMode is "
                            + CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED
                            + ", the least possible value. Data service policy cannot go below"
                            + " that. Therefore, returning this");
            return maxAllowedDataMode;
        }

        if (isValidSubscriptionId(subId)) {
            Map<String, Integer> dataServicePolicy = getConfigForSubId(subId).getBoolean(
                    KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false)
                    ? mEntitlementDataServicePolicyMapPerCarrier.get(subId)
                    : null;
            plogd("getSatelliteDataServicePolicyForPlmn: dataServicePolicy=" + dataServicePolicy);

            if (dataServicePolicy != null && !dataServicePolicy.isEmpty()) {
                if (!TextUtils.isEmpty(plmn) && dataServicePolicy.containsKey(plmn)) {
                    plogd("getSatelliteDataServicePolicyForPlmn: "
                            + "return policy using dataServicePolicy map");
                    if (dataServicePolicy.get(plmn) > maxAllowedDataMode) {
                        plogd(
                                "getSatelliteDataServicePolicyForPlmn: dataServicePolicy shouldn't "
                                        + "be bigger than maxAllowedDataMode: "
                                        + maxAllowedDataMode);
                        return maxAllowedDataMode;
                    }

                    plogd(
                            "getSatelliteDataServicePolicyForPlmn: "
                                    + "return policy using dataServicePolicy map: "
                                    + dataServicePolicy.get(plmn));
                    return dataServicePolicy.get(plmn);
                } else if (TextUtils.isEmpty(plmn)) {
                    int preferredPolicy =
                            CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED;
                    for (String plmnKey : dataServicePolicy.keySet()) {
                        int policy = dataServicePolicy.get(plmnKey);
                        // higher value has higher preference
                        if (policy > preferredPolicy) {
                            preferredPolicy = policy;
                        }
                    }

                    // when invoked getSatelliteDataServicePolicyPlmn() with empty plmn and data
                    // service policy not provisioned i.e.data service policy info is empty with
                    // or without plmn key, then ignore setting preferred data supported mode policy
                    // as restricted and fallback to carrier configured data supported mode for the
                    // subscription id.
                    if (preferredPolicy
                            > CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED) {
                        plogd(
                                "getSatelliteDataServicePolicyForPlmn: "
                                        + "preferredPolicy="
                                        + preferredPolicy);

                        if (preferredPolicy > maxAllowedDataMode) {
                            plogd(
                                    "getSatelliteDataServicePolicyForPlmn: preferredPolicy"
                                            + " shouldn't be bigger than maxAllowedDataMode: "
                                            + maxAllowedDataMode);
                            return maxAllowedDataMode;
                        }

                        plogd(
                                "getSatelliteDataServicePolicyForPlmn: "
                                        + "returning preferredPolicy="
                                        + preferredPolicy);
                        return preferredPolicy;
                    }
                }
            }

            if (isSatelliteDataServicesAllowed(subId, plmn)) {
                plogd("getSatelliteDataServicePolicyForPlmn: return data support mode from config");
                int carrierSatelliteDataSupportedMode =
                        getCarrierSatelliteDataSupportedModeFromConfig(subId);
                if (carrierSatelliteDataSupportedMode > maxAllowedDataMode) {
                    plogd(
                            "getSatelliteDataServicePolicyForPlmn:"
                                    + " carrierSatelliteDataSupportedMode shouldn't be bigger than"
                                    + " maxAllowedDataMode: "
                                    + maxAllowedDataMode);
                    return maxAllowedDataMode;
                }
                plogd(
                        "getSatelliteDataServicePolicyForPlmn: return data support mode from"
                                + " config: "
                                + carrierSatelliteDataSupportedMode);
                return carrierSatelliteDataSupportedMode;
            }
        }

        plogd("getSatelliteDataServicePolicyForPlmn: return data support only restricted");
        return CarrierConfigManager.SATELLITE_DATA_SUPPORT_ONLY_RESTRICTED;
    }

    /**
     * Method to return the current satellite voice service policy supported mode for the registered
     * plmn based on entitlement provisioning information. Note: If no information at
     * provisioning is supported this is overridden with operator carrier config information.
     *
     * @param subId current subscription id
     * @param plmn current registered plmn information
     *
     * @return Supported modes {@link CarrierConfigManager.SATELLITE_DATA_SUPPORT_MODE}
     */
    public int getSatelliteVoiceServicePolicyForPlmn(int subId, String plmn) {
        if (plmn != null) {
            Map<String, Integer> voiceServicePolicy =
                    mEntitlementVoiceServicePolicyMapPerCarrier.get(
                            subId);
            logd("voice policy available for sub id:" + voiceServicePolicy);
            if (voiceServicePolicy != null && voiceServicePolicy.containsKey(plmn)
                    && !plmn.isEmpty()) {
                return voiceServicePolicy.get(plmn);
            }
        }
        // TODO (Replace below code with related enum value, when voice service policy support mode
        // is added)
        return 0; // Restricted
    }

    /**
     * Get list of applications that are optimized for low bandwidth satellite data.
     *
     * @param userId is Identifier of user
     *
     * @return List of Application Name with data optimized network property.
     * {@link #PROPERTY_SATELLITE_DATA_OPTIMIZED}
     */
    public List<String> getSatelliteDataOptimizedApps(int userId) {
        return mSatelliteOptimizedApplicationsTracker.getSatelliteOptimizedApplications(userId);

    }

    /**
     * Method to return the current satellite data service policy supported mode for the
     * subscription id based on carrier config.
     *
     * @param subId current subscription id.
     *
     * @return Supported modes {@link SatelliteManager#SatelliteDataSupportMode}
     *
     * @hide
     */
    @SatelliteManager.SatelliteDataSupportMode
    public int getSatelliteDataSupportMode(int subId) {
        return getSatelliteDataServicePolicyForPlmn(subId, "");
    }


    private void handleEventScreenStateChanged(@Nullable AsyncResult asyncResult) {
        if (!mFeatureFlags.satelliteMetricsEnhancement()) {
            logd("handleEventScreenStateChanged: satelliteMetricsEnhancement is not enabled, "
                    + "ignore.");
            return;
        }

        if (asyncResult == null) {
            ploge("handleEventScreenStateChanged: asyncResult is null");
            return;
        }
        boolean isScreenOn = (boolean) asyncResult.result;
        plogd("handleEventScreenStateChanged: " + isScreenOn);
        mCarrierRoamingSatelliteSessionStatsMap.values().forEach(stats -> {
            stats.onScreenStateChanged(isScreenOn);
        });
    }

    private void handleEventWifiConnectivityStateChanged(boolean isWifiConnected) {
        mCarrierRoamingSatelliteSessionStatsMap.values().forEach(stats -> {
            stats.onWifiConnectivityStateChanged(isWifiConnected);
        });
    }

    @VisibleForTesting(visibility =  VisibleForTesting.Visibility.PRIVATE)
    protected boolean isScreenOn() {
        if (mDisplayManager == null) {
            mDisplayManager = mContext.getSystemService(DisplayManager.class);
        }

        Display[] displays = mDisplayManager.getDisplays();
        if (displays != null) {
            for (Display display : displays) {
                if (display.getState() == Display.STATE_ON) {
                    plogd("isScreenOn: Screen on for display=" + display);
                    return true;
                }
            }
            plogd("isScreenOn: Screens all off");
            return false;
        }

        plogd("No displays found");
        return false;
    }

    /**
     * Get the satellite configuration for the given PLMN.
     *
     * @param subId current subscription id.
     * @param plmn PLMN for which the satellite configuration is requested.
     * @return {@link PlmnSatelliteConfig} object containing the satellite configuration for the
     * given PLMN.
     *
     * @hide
     */
    @NonNull
    public PlmnSatelliteConfig getPlmnSatelliteConfig(int subId, String plmn) {
        PlmnSatelliteConfig plmnSatelliteConfig = new PlmnSatelliteConfig(new HashSet<>(
                    getSupportedSatelliteServicesForPlmn(subId, plmn)));
        return plmnSatelliteConfig;
    }

    /**
     * This API can be used by only CTS to make the function {@link #getAllPlmnSet()} to exclude the
     * PLMN list from storage from the returned result.
     *
     * @param enabled Whether to enable boolean config.
     * @return {@code true} if the value is set successfully, {@code false} otherwise.
     */
    public boolean setSatelliteIgnorePlmnListFromStorage(boolean enabled) {
        plogd("setSatelliteIgnorePlmnListFromStorage - " + enabled);
        mIgnorePlmnListFromStorage.set(enabled);
        return true;
    }

    private void setSatelliteEnabledRequest(@Nullable RequestSatelliteEnabledArgument argument) {
        synchronized (mSatelliteEnabledRequestLock) {
            mSatelliteEnabledRequest = argument;
        }
    }

    @Nullable
    private RequestSatelliteEnabledArgument getSatelliteEnabledRequest() {
        synchronized (mSatelliteEnabledRequestLock) {
            return mSatelliteEnabledRequest;
        }
    }

    private void setSatelliteDisabledRequest(@Nullable RequestSatelliteEnabledArgument argument) {
        synchronized (mSatelliteEnabledRequestLock) {
            mSatelliteDisabledRequest = argument;
        }
    }

    @Nullable
    private RequestSatelliteEnabledArgument getSatelliteDisabledRequest() {
        synchronized (mSatelliteEnabledRequestLock) {
            return mSatelliteDisabledRequest;
        }
    }

    private void setSatelliteEnableAttributesUpdateRequest(
            @Nullable RequestSatelliteEnabledArgument argument) {
        synchronized (mSatelliteEnabledRequestLock) {
            mSatelliteEnableAttributesUpdateRequest = argument;
        }
    }

    @Nullable
    private RequestSatelliteEnabledArgument getSatelliteEnableAttributesUpdateRequest() {
        synchronized (mSatelliteEnabledRequestLock) {
            return mSatelliteEnableAttributesUpdateRequest;
        }
    }

    private void setSubsInfoListPerPriority(
            @NonNull TreeMap<Integer, List<SubscriptionInfo>> subsInfoListPerPriority) {
        synchronized (mSatelliteTokenProvisionedLock) {
            mSubsInfoListPerPriority = subsInfoListPerPriority;
        }
    }

    @NonNull
    private TreeMap<Integer, List<SubscriptionInfo>> getSubsInfoListPerPriority() {
        synchronized (mSatelliteTokenProvisionedLock) {
            return mSubsInfoListPerPriority;
        }
    }

    private void setLastEvaluatedSubscriberProvisionStatus(
            @NonNull List<SatelliteSubscriberProvisionStatus> subscriberProvisionStatus) {
        synchronized (mSatelliteTokenProvisionedLock) {
            mLastEvaluatedSubscriberProvisionStatus = subscriberProvisionStatus;
        }
    }

    @NonNull
    private List<SatelliteSubscriberProvisionStatus> getLastEvaluatedSubscriberProvisionStatus() {
        synchronized (mSatelliteTokenProvisionedLock) {
            return mLastEvaluatedSubscriberProvisionStatus;
        }
    }

    private void setLastConfiguredIccId(@NonNull String lastConfiguredIccId) {
        synchronized (mSatelliteTokenProvisionedLock) {
            mLastConfiguredIccId = lastConfiguredIccId;
        }
    }

    @NonNull
    private String getLastConfiguredIccId() {
        synchronized (mSatelliteTokenProvisionedLock) {
            return mLastConfiguredIccId;
        }
    }

    @VisibleForTesting
    public boolean isWifiConnected() {
        return mIsWifiConnected.get();
    }

    /** Returns whether the device is entitled for given subscription. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean isDeviceEntitledForSubscription(int subId) {
        PersistableBundle config = getConfigForSubId(subId);
        boolean attachSupported = config.getBoolean(KEY_SATELLITE_ATTACH_SUPPORTED_BOOL);
        if (!attachSupported) {
            logd("isDeviceEntitledForSubscription: subId=" + subId
                    + ", satellite attach not supported, returning false");
            return false;
        }

        boolean entitlementSupportedByCarrier = config.getBoolean(
                KEY_SATELLITE_ENTITLEMENT_SUPPORTED_BOOL, false);
        if (entitlementSupportedByCarrier) {
            boolean isEntitled = mSatelliteEntitlementStatusPerCarrier.getOrDefault(subId,
                    false);
            logd("isDeviceEntitledForSubscription: subId=" + subId
                    + ", entitlement supported, returning map status=" + isEntitled);
            return isEntitled;
        }

        logd("isDeviceEntitledForSubscription: subId=" + subId
                + ", entitlement not required, returning true");
        return true;
    }

    /** Returns whether the satellite eligibility source for given subscription. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public @SatelliteConstants.SatelliteEligibilitySource int getSatelliteEligibilitySource(
            int subId) {
        if (!mFeatureFlags.satelliteMetricsEnhancement()) {
            logd("getSatelliteEligibilitySource: satelliteMetricsEnhancement is not "
                    + "enabled. ignore.");
            return SatelliteConstants.SATELLITE_ELIGIBILITY_SOURCE_UNKNOWN;
        }

        return mSatelliteEligibilitySource.getOrDefault(subId,
                SatelliteConstants.SATELLITE_ELIGIBILITY_SOURCE_UNKNOWN);
    }

    private void handleRequestPointingUiAppLaunchIntent(
            @NonNull PointingUiAppLaunchIntentAttributes launchIntentAttributes,
            @NonNull ResultReceiver result) {
        plogd("handleRequestPointingUiAppLaunchIntent: launchIntentAttributes="
                + launchIntentAttributes);
        PendingIntent pendingIntent =
                mPointingAppController.createPointingUiAppPendingIntent(launchIntentAttributes);

        Bundle bundle = new Bundle();
        if (pendingIntent != null) {
            bundle.putParcelable(SatelliteManager.KEY_POINTING_UI_APP_LAUNCH_INTENT, pendingIntent);
            result.send(SATELLITE_RESULT_SUCCESS, bundle);
        } else {
            result.send(SatelliteManager.SATELLITE_RESULT_REQUEST_FAILED, null);
        }
    }

    /**
     * Get the carrier roaming satellite emergency messaging redirection number for the given
     * subscription ID.
     *
     * @param subId current subscription id.
     * @return the carrier roaming satellite emergency messaging redirection number.
     */
    @NonNull
    protected String getCarrierRoamingSatelliteEmergencyMessagingRedirectionDestination(int subId) {
        if (!mFeatureFlags.emergencyMessagingRoutingForInternationalRoaming()) {
            plogd("getCarrierRoamingSatelliteEmergencyMessagingRedirectionDestination: "
                + "flag disabled");
            return "";
        }

        String destination = getConfigForSubId(subId).getString(
            KEY_CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_REDIRECTION_DESTINATION_STRING, "");
        plogd("getCarrierRoamingSatelliteEmergencyMessagingRedirectionDestination: "
            + "destination=" + destination);
        return destination;
    }

    private boolean isSatelliteEmergencyMessagingProviderSupportedInCurrentRegion(int subId) {
        if (!mFeatureFlags.emergencyMessagingRoutingForInternationalRoaming()) {
            plogd("isSatelliteEmergencyMessagingProviderSupportedInCurrentRegion: "
                + "flag disabled - return true");
            return true;
        }

        int emergencyMessagingProvider =
            getCarrierRoamingSatelliteEmergencyMessagingProviderForCurrentRegion(subId);
        boolean supported = emergencyMessagingProvider
            != SatelliteManager.CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_PROVIDER_UNSUPPORTED;
        plogd("isSatelliteEmergencyMessagingProviderSupportedInCurrentRegion: "
            + "supported=" + supported);
        return supported;
    }

    /**
     * Get the carrier roaming satellite emergency messaging provider for the given subscription ID
     * in current country.
     *
     * @param subId current subscription id.
     * @return the carrier roaming satellite emergency messaging provider.
     */
    @SatelliteManager.CarrierRoamingSatelliteEmergencyMessagingProvider
    protected int getCarrierRoamingSatelliteEmergencyMessagingProviderForCurrentRegion(int subId) {
        if (!mFeatureFlags.emergencyMessagingRoutingForInternationalRoaming()) {
            plogd("getCarrierRoamingSatelliteEmergencyMessagingProvider: flag disabled");
            return SatelliteManager.CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_PROVIDER_UNKNOWN;
        }

        SatellitePerPlmnConfiguration config = getSatellitePerPlmnConfiguration(subId);
        if (config == null) {
            plogd("getCarrierRoamingSatelliteEmergencyMessagingProvider: perPlmnConfig is null");
            return SatelliteManager.CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_PROVIDER_UNKNOWN;
        }

        String plmn = config.plmn;
        if (TextUtils.isEmpty(plmn) || plmn.length() < 3) {
            plogd("getCarrierRoamingSatelliteEmergencyMessagingProvider: plmn is empty "
                + "or too short. plmn=" + plmn);
            return SatelliteManager.CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_PROVIDER_UNKNOWN;
        }

        String networkMcc = plmn.substring(0, 3);
        PersistableBundle providerBundle = getConfigForSubId(subId).getPersistableBundle(
                KEY_CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_PROVIDER_PER_COUNTRY_BUNDLE);
        int provider = providerBundle.getInt(networkMcc,
            SatelliteManager.CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_PROVIDER_UNKNOWN);
        plogd("getCarrierRoamingSatelliteEmergencyMessagingProvider: mcc=" + networkMcc
            + ", provider=" + provider);

        return provider;
    }

    /**
     * Request to enable or disable satellite for a specific subscription.
     *
     * @param subId The subscription ID to evaluate enablement for.
     * @param reason The restriction reason to evaluate (e.g.,
     *               {@link SatelliteManager#SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER}).
     * @param callback The callback used to return the result of the evaluation.
     */
    // TODO(b/323046234): Migrate to use Auto Satellite Enablement.
    public void requestEnableSatelliteForCarrier(int subId, boolean enable,
            @SatelliteManager.SatelliteCommunicationRestrictionReason int reason,
            @NonNull IIntegerConsumer callback) {
        plogd("requestEnableSatelliteForCarrier: subId=" + subId + ", enable=" + enable
                + ", reason=" + reason);
        if (enable) {
            plogd("requestEnableSatelliteForCarrier: enabling satellite for carrier, removing "
                    + "restriction: " + reason + " for subId=" + subId);
            removeAttachRestrictionForCarrier(subId, reason, callback);
        } else {
            plogd("requestEnableSatelliteForCarrier: disabling satellite for carrier, adding "
                    + "restriction: " + reason + " for subId=" + subId);
            addAttachRestrictionForCarrier(subId, reason, callback);
        }
    }
}

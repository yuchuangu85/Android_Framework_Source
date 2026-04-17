/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.internal.telephony.subscription;

import static android.content.pm.PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.ColorInt;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.compat.CompatChanges;
import android.app.privatecompute.flags.Flags;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.TelephonyServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Telephony.SimInfo;
import android.service.carrier.CarrierIdentifier;
import android.service.euicc.EuiccProfileInfo;
import android.service.euicc.EuiccService;
import android.service.euicc.GetEuiccProfileInfoListResult;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.AnomalyReporter;
import android.telephony.CarrierConfigManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.RadioAccessFamily;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.DataRoamingMode;
import android.telephony.SubscriptionManager.DeviceToDeviceStatusSharingPreference;
import android.telephony.SubscriptionManager.PhoneNumberSource;
import android.telephony.SubscriptionManager.SimDisplayNameSource;
import android.telephony.SubscriptionManager.SubscriptionType;
import android.telephony.SubscriptionManager.UsageSetting;
import android.telephony.SubscriptionPlan;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.SimState;
import android.telephony.TelephonyRegistryManager;
import android.telephony.UiccAccessRule;
import android.telephony.euicc.EuiccManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;
import android.util.Log;
import android.util.RecurrenceRule;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.CarrierResolver;
import com.android.internal.telephony.ISetOpportunisticDataCallback;
import com.android.internal.telephony.ISub;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.MultiSimSettingController;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.ProxyController;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.telephony.data.PhoneSwitcher;
import com.android.internal.telephony.euicc.EuiccController;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.subscription.SubscriptionDatabaseManager.SubscriptionDatabaseManagerCallback;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccPort;
import com.android.internal.telephony.uicc.UiccSlot;
import com.android.internal.telephony.util.ArrayUtils;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.internal.telephony.util.WorkerThread;
import com.android.modules.utils.BinaryXmlPullParser;
import com.android.modules.utils.BinaryXmlSerializer;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.telephony.Rlog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The subscription manager service is the backend service of {@link SubscriptionManager}.
 * The service handles all SIM subscription related requests from clients.
 */
public class SubscriptionManagerService extends ISub.Stub {
    private static final String LOG_TAG = "SMSVC";
    private static final String ALLOW_MOCK_MODEM_PROPERTY = "persist.radio.allow_mock_modem";
    private static final String BOOT_ALLOW_MOCK_MODEM_PROPERTY = "ro.boot.radio.allow_mock_modem";
    private static final String PRIVATE_NETWORK_MCC = "999";

    private static final int CHECK_BOOTSTRAP_TIMER_IN_MS = 20 * 60 * 1000; // 20 minutes
    private static CountDownTimer bootstrapProvisioningTimer;

    /** Whether enabling verbose debugging message or not. */
    private static final boolean VDBG = false;

    // Compile-time debug flag for controlling worker thread behavior
    private static final boolean USE_WORKER_THREAD = false;

    /**
     * The columns in {@link SimInfo} table that can be directly accessed through
     * {@link #getSubscriptionProperty(int, String, String, String)} or
     * {@link #setSubscriptionProperty(int, String, String)}. Usually those fields are not
     * sensitive. Mostly they are related to user settings, for example, wifi calling
     * user settings, cross sim calling user settings, etc...Those fields are protected with
     * {@link Manifest.permission#READ_PHONE_STATE} permission only.
     *
     * For sensitive fields, they usually requires special methods to access. For example,
     * {@link #getSubscriptionUserHandle(int)} or {@link #getPhoneNumber(int, int, String, String)}
     * that requires higher permission to access.
     */
    private static final Set<String> DIRECT_ACCESS_SUBSCRIPTION_COLUMNS = Set.of(
            SimInfo.COLUMN_CB_EXTREME_THREAT_ALERT,
            SimInfo.COLUMN_CB_SEVERE_THREAT_ALERT,
            SimInfo.COLUMN_CB_AMBER_ALERT,
            SimInfo.COLUMN_CB_EMERGENCY_ALERT,
            SimInfo.COLUMN_CB_ALERT_SOUND_DURATION,
            SimInfo.COLUMN_CB_ALERT_REMINDER_INTERVAL,
            SimInfo.COLUMN_CB_ALERT_VIBRATE,
            SimInfo.COLUMN_CB_ALERT_SPEECH,
            SimInfo.COLUMN_CB_ETWS_TEST_ALERT,
            SimInfo.COLUMN_CB_CHANNEL_50_ALERT,
            SimInfo.COLUMN_CB_CMAS_TEST_ALERT,
            SimInfo.COLUMN_CB_OPT_OUT_DIALOG,
            SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED,
            SimInfo.COLUMN_VT_IMS_ENABLED,
            SimInfo.COLUMN_WFC_IMS_ENABLED,
            SimInfo.COLUMN_WFC_IMS_MODE,
            SimInfo.COLUMN_WFC_IMS_ROAMING_MODE,
            SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED,
            SimInfo.COLUMN_ENABLED_MOBILE_DATA_POLICIES,
            SimInfo.COLUMN_IMS_RCS_UCE_ENABLED,
            SimInfo.COLUMN_CROSS_SIM_CALLING_ENABLED,
            SimInfo.COLUMN_RCS_CONFIG,
            SimInfo.COLUMN_ALLOWED_NETWORK_TYPES_FOR_REASONS,
            SimInfo.COLUMN_D2D_STATUS_SHARING,
            SimInfo.COLUMN_VOIMS_OPT_IN_STATUS,
            SimInfo.COLUMN_D2D_STATUS_SHARING_SELECTED_CONTACTS,
            SimInfo.COLUMN_NR_ADVANCED_CALLING_ENABLED,
            SimInfo.COLUMN_SATELLITE_ENABLED,
            SimInfo.COLUMN_SATELLITE_ATTACH_ENABLED_FOR_CARRIER,
            SimInfo.COLUMN_IS_ONLY_NTN,
            SimInfo.COLUMN_SATELLITE_ENTITLEMENT_STATUS,
            SimInfo.COLUMN_SATELLITE_ENTITLEMENT_PLMNS,
            SimInfo.COLUMN_SATELLITE_ESOS_SUPPORTED,
            SimInfo.COLUMN_IS_SATELLITE_PROVISIONED_FOR_NON_IP_DATAGRAM,
            SimInfo.COLUMN_SATELLITE_ENTITLEMENT_BARRED_PLMNS,
            SimInfo.COLUMN_SATELLITE_ENTITLEMENT_DATA_PLAN_PLMNS,
            SimInfo.COLUMN_SATELLITE_ENTITLEMENT_SERVICE_TYPE_MAP,
            SimInfo.COLUMN_SATELLITE_ENTITLEMENT_DATA_SERVICE_POLICY,
            SimInfo.COLUMN_SATELLITE_ENTITLEMENT_VOICE_SERVICE_POLICY,
            SimInfo.COLUMN_STREAMING_APP_MAX_DOWNLINK_KBPS,
            SimInfo.COLUMN_STREAMING_APP_MAX_UPLINK_KBPS
    );

    /**
     * Apps targeting on Android T and beyond will get exception if there is no access to device
     * identifiers nor has carrier privileges when calling
     * {@link SubscriptionManager#getSubscriptionsInGroup}.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long REQUIRE_DEVICE_IDENTIFIERS_FOR_GROUP_UUID = 213902861L;

    /**
     * Apps targeting on Android V and beyond can only see subscriptions accessible by them
     * according to its user Id.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static final long FILTER_ACCESSIBLE_SUBS_BY_USER = 296076674L;

    /** Wrap Binder methods for testing. */
    @NonNull
    // Non-final: overwritten in SubscriptionManagerServiceTest.
    private static BinderWrapper BINDER_WRAPPER = new BinderWrapper();

    /** Regular expression to determine if a string is in MAC address format. */
    private static final Pattern MAC_ADDRESS_PATTERN = Pattern.compile(
            // Matches formats like 00:1B:44:11:3A:B7 or 00-1B-44-11-3A-B7
            "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");

    /** Instance of subscription manager service. */
    @NonNull
    private static SubscriptionManagerService sInstance;

    /** File name to persist enrollable subscription plans. */
    private static final String ENROLLABLE_PLANS_FILE = "enrollable-plans.xml";

    /** The root element tag of the enrollable plans XML file. */
    private static final String TAG_ENROLLABLE_PLANS = "enrollable-plans";

    /**
     * Tag for a group of plans associated with a specific subscription ID. Contains attributes for
     * metadata (subId, owner, expiration) and child {@link #TAG_PLAN} tags.
     */
    private static final String TAG_SUB_PLANS = "sub-plans";

    /** Tag representing a single {@link SubscriptionPlan} entry. */
    private static final String TAG_PLAN = "plan";

    /**
     * Attribute for {@link #TAG_SUB_PLANS}: The subscription ID (integer) these plans belong to.
     */
    private static final String ATTR_SUB_ID = "subId";

    /**
     * Attribute for {@link #TAG_SUB_PLANS}: The package name of the app that owns/created these
     * plans.
     */
    private static final String ATTR_OWNER = "owner";

    /**
     * Attribute for {@link #TAG_SUB_PLANS}: The absolute expiration time in milliseconds. If the
     * current time exceeds this value, the plans are considered expired.
     */
    private static final String ATTR_EXPIRATION_TIME = "expirationTime";

    /**
     * Attribute for {@link #TAG_PLAN}: The start time of the billing cycle (formatted ZonedDateTime
     * string).
     */
    private static final String ATTR_CYCLE_START = "cycleStart";

    /**
     * Attribute for {@link #TAG_PLAN}: The end time of the billing cycle (formatted ZonedDateTime
     * string).
     */
    private static final String ATTR_CYCLE_END = "cycleEnd";

    /**
     * Attribute for {@link #TAG_PLAN}: The recurrence period of the billing cycle (formatted Period
     * string).
     */
    private static final String ATTR_CYCLE_PERIOD = "cyclePeriod";

    /** Attribute for {@link #TAG_PLAN}: The user-visible title of the plan. */
    private static final String ATTR_TITLE = "title";

    /** Attribute for {@link #TAG_PLAN}: A brief summary of the plan. */
    private static final String ATTR_SUMMARY = "summary";

    /** Attribute for {@link #TAG_PLAN}: The data limit in bytes. */
    private static final String ATTR_LIMIT_BYTES = "limitBytes";

    /**
     * Attribute for {@link #TAG_PLAN}: The behavior when the data limit is reached (e.g., disabled,
     * throttled).
     */
    private static final String ATTR_LIMIT_BEHAVIOR = "limitBehavior";

    /** Attribute for {@link #TAG_PLAN}: The currently used data amount in bytes. */
    private static final String ATTR_USAGE_BYTES = "usageBytes";

    /**
     * Attribute for {@link #TAG_PLAN}: The timestamp (epoch millis) when the data usage was
     * measured.
     */
    private static final String ATTR_USAGE_TIME = "usageTime";

    /**
     * Attribute for {@link #TAG_PLAN}: A comma-separated list of network types this plan applies
     * to.
     */
    private static final String ATTR_NETWORK_TYPES = "networkTypes";

    /**
     * Attribute for {@link #TAG_PLAN}: The status of the subscription (e.g., active, suspended).
     */
    private static final String ATTR_SUBSCRIPTION_STATUS = "subscriptionStatus";

    /** Attribute for {@link #TAG_PLAN}: The unique integer plan ID. */
    private static final String ATTR_PLAN_ID = "planId";

    /** Attribute for {@link #TAG_PLAN}: Comma-separated list of plan type integers. */
    private static final String ATTR_PLAN_TYPES = "planTypes";

    /** Attribute for {@link #TAG_PLAN}: The data usage reset time (ZonedDateTime string). */
    private static final String ATTR_RESET_TIME = "resetTime";

    /** Attribute for {@link #TAG_PLAN}: Max streaming downlink speed in Kbps. */
    private static final String ATTR_DOWNLINK_KBPS = "downlinkKbps";

    /** Attribute for {@link #TAG_PLAN}: Max streaming uplink speed in Kbps. */
    private static final String ATTR_UPLINK_KBPS = "uplinkKbps";

    /** The context */
    @NonNull
    private final Context mContext;

    /** Feature flags */
    @NonNull
    private final FeatureFlags mFeatureFlags;

    /** App Ops manager instance. */
    @NonNull
    private final AppOpsManager mAppOpsManager;

    /** Telephony manager instance. */
    @NonNull
    private final TelephonyManager mTelephonyManager;

    /** Subscription manager instance. */
    @NonNull
    private final SubscriptionManager mSubscriptionManager;

    /**
     * Euicc manager instance. Will be null if the device does not support
     * {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @Nullable
    private final EuiccManager mEuiccManager;

    /** Uicc controller instance. */
    @NonNull
    private final UiccController mUiccController;

    /**
     * Euicc controller instance. Will be null if the device does not support
     * {@link PackageManager#FEATURE_TELEPHONY_EUICC}.
     */
    @Nullable
    private EuiccController mEuiccController;

    /** Package manager instance. */
    @NonNull
    private final PackageManager mPackageManager;

    /**
     * The main handler of subscription manager service. This is running on phone process's main
     * thread.
     */
    @NonNull
    private final Handler mHandler;

    /**
     * The background handler. This is running on a separate thread.
     */
    @NonNull
    private final Handler mBackgroundHandler;

    /** Local log for most important debug messages. */
    @NonNull
    private final LocalLog mLocalLog = new LocalLog(256);

    /** The subscription database manager. */
    @NonNull
    private final SubscriptionDatabaseManager mSubscriptionDatabaseManager;

    /**
     * The slot index to subscription ID map for local SIMs. This map should only contain
     * subscriptions of type {@link SubscriptionManager#SUBSCRIPTION_TYPE_LOCAL_SIM}.
     *
     * <p>Key is the physical slot index (0-indexed), and the value is the subscription ID.
     *
     * <p>Remote SIMs ({@link SubscriptionManager#SUBSCRIPTION_TYPE_REMOTE_SIM}) are not associated
     * with a physical slot index on the device and are tracked separately in
     * {@link #mRemoteSubIds}.
     */
    @NonNull
    private final SubscriptionMap<Integer, Integer> mSlotIndexToSubId = new SubscriptionMap<>();

    /**
     * The subscription ID set for remote SIMs
     * ({@link SubscriptionManager#SUBSCRIPTION_TYPE_REMOTE_SIM}).
     *
     * <p>Local SIMs ({@link SubscriptionManager#SUBSCRIPTION_TYPE_LOCAL_SIM}) are tracked in
     * {@link #mSlotIndexToSubId}.
     */
    @NonNull
    private final SubscriptionSet<Integer> mRemoteSubIds = new SubscriptionSet<>();

    /** Subscription manager service callbacks. */
    @NonNull
    private final Set<SubscriptionManagerServiceCallback> mSubscriptionManagerServiceCallbacks =
            new ArraySet<>();

    /**
     * Default sub id. Derived from {@link #mDefaultVoiceSubId} and {@link #mDefaultDataSubId},
     * depending on device capability.
     */
    @NonNull
    private final WatchedInt mDefaultSubId;

    /** Default voice subscription id. */
    @NonNull
    private final WatchedInt mDefaultVoiceSubId;

    /** Default data subscription id. */
    @NonNull
    private final WatchedInt mDefaultDataSubId;

    /** Default sms subscription id. */
    @NonNull
    private final WatchedInt mDefaultSmsSubId;

    /** Sim state per logical SIM slot index. */
    @NonNull
    private final int[] mSimState;

    /** Vendor API level from system property. */
    private final int mVendorApiLevel;

    /**
     * {@code true} if a user profile can only see the SIMs associated with it, unless it possesses
     * no SIMs on the device.
     */
    private Map<Integer, List<Integer>> mUserIdToAvailableSubs = new ConcurrentHashMap<>();

    /**
     * Tracks whether the phone number for the current IMS session was successfully parsed.
     */
    private final Map<Integer, Boolean> mImsNumberUpdateStatus = new ConcurrentHashMap<>();

    /**
     * Maps subscription ID to the list of enrollable subscription plans.
     * <p>
     * These plans represent purchasable offers or available subscriptions provided by the carrier
     * application. The data is stored in memory.
     * <p>
     * The key is the subscription ID ({@link Integer}),
     * <p>
     * The value is an array of {@link SubscriptionPlan} objects.
     */
    @NonNull
    private final Map<Integer, SubscriptionPlan[]> mEnrollableSubscriptionPlans =
            new ConcurrentHashMap<>();

    /**
     * Maps subscription ID to the package name of the app that owns the enrollable plans.
     * <p>
     * The key is the subscription ID ({@link Integer}),
     * <p>
     * The value is a string of package name that set enrollable subscription plans.
     */
    @NonNull
    private final Map<Integer, String> mEnrollableSubscriptionPlansOwner =
            new ConcurrentHashMap<>();

    /**
     * Maps subscription ID to the pending runnable responsible for expiring the enrollable plans.
     *
     * <p>The key is the subscription ID ({@link Integer}),
     *
     * <p>The value is the Runnable that clears the plans
     */
    @NonNull
    private final Map<Integer, Runnable> mEnrollablePlanExpirationRunnables =
            new ConcurrentHashMap<>();

    /**
     * Maps subscription ID to the expiration time (absolute time in millis). Used for persistence.
     */
    @NonNull
    private final Map<Integer, Long> mEnrollablePlanExpirationTime = new ConcurrentHashMap<>();

    /** File to persist enrollable subscription plans. */
    private final AtomicFile mEnrollablePlansFile;

    /**
     * Slot index/subscription map that automatically invalidates caches in
     * {@link SubscriptionManager}.
     *
     * @param <K> The type of the key.
     * @param <V> The type of the value.
     */
    @VisibleForTesting
    public static class SubscriptionMap<K, V> extends ConcurrentHashMap<K, V> {
        @Override
        public void clear() {
            super.clear();
            SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
        }

        @Override
        public V put(K key, V value) {
            V oldValue = super.put(key, value);
            if (!Objects.equals(oldValue, value)) {
                SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
            }
            return oldValue;
        }

        @Override
        public V remove(Object key) {
            V oldValue = super.remove(key);
            if (oldValue != null) {
                SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
            }
            return oldValue;
        }
    }

    /**
     * Subscription ID set for remote SIMs that automatically invalidates caches in
     * {@link SubscriptionManager}.
     *
     * @param <T> The type of the element.
     */
    @VisibleForTesting
    public static class SubscriptionSet<T extends Comparable<? super T>> extends AbstractSet<T> {
        private final Set<T> mBackingSet = ConcurrentHashMap.newKeySet();

        @Override
        public void clear() {
            mBackingSet.clear();
            SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
        }

        @Override
        public boolean add(T subId) {
            boolean changed = mBackingSet.add(subId);
            if (changed) {
                SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
            }
            return changed;
        }

        @Override
        public boolean remove(Object o) {
            boolean changed = mBackingSet.remove(o);
            if (changed) {
                SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
            }
            return changed;
        }

        @Override
        public boolean addAll(Collection<? extends T> c) {
            boolean changed = mBackingSet.addAll(c);
            if (changed) {
                SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
            }
            return changed;
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            boolean changed = mBackingSet.removeAll(c);
            if (changed) {
                SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
            }
            return changed;
        }

        @Override
        public int size() {
            return mBackingSet.size();
        }

        @Override
        public boolean isEmpty() {
            return mBackingSet.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return mBackingSet.contains(o);
        }

        private Stream<T> sortedStream() {
            return mBackingSet.stream().sorted();
        }

        /**
         * Returns an iterator over the elements in this set in ascending order of subId.
         * <p>
         * The iterator provides a snapshot of the set at the time the iterator is created.
         * It does not reflect subsequent modifications to the set. The iterator does
         * NOT support the {@code remove} operation.
         */
        @Override
        public Iterator<T> iterator() {
            return sortedStream().iterator();
        }

        @Override
        public Object[] toArray() {
            return sortedStream().toArray();
        }

        @Override
        public <t> t[] toArray(t[] a) {
            return sortedStream().toList().toArray(a);
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return mBackingSet.containsAll(c);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            boolean changed = mBackingSet.retainAll(c);
            if (changed) {
                SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
            }
            return changed;
        }

        public T getLargest() {
            return mBackingSet.stream().max(Comparator.naturalOrder()).orElse(null);
        }
    }

    /**
     * Watched integer.
     */
    public static class WatchedInt {
        protected int mValue;

        /**
         * Constructor.
         *
         * @param initialValue The initial value.
         */
        public WatchedInt(int initialValue) {
            mValue = initialValue;
        }

        /**
         * @return The value.
         */
        public int get() {
            return mValue;
        }

        /**
         * Set the value.
         *
         * @param newValue The new value.
         *
         * @return {@code true} if {@code newValue} is different from the existing value.
         */
        public boolean set(int newValue) {
            if (mValue != newValue) {
                mValue = newValue;
                SubscriptionManager.invalidateSubscriptionManagerServiceCaches();
                return true;
            }
            return false;
        }
    }

    /** Binder Wrapper for test mocking. */
    @VisibleForTesting
    public static class BinderWrapper {
        @NonNull public UserHandle getCallingUserHandle() {
            return Binder.getCallingUserHandle();
        }
        @NonNull public int getCallingUid() {
            return Binder.getCallingUid();
        }
    }

    /**
     * This is the callback used for listening events from {@link SubscriptionManagerService}.
     */
    public static class SubscriptionManagerServiceCallback {
        /** The executor of the callback. */
        @NonNull
        private final Executor mExecutor;

        /**
         * Constructor
         *
         * @param executor The executor of the callback.
         */
        public SubscriptionManagerServiceCallback(@NonNull @CallbackExecutor Executor executor) {
            mExecutor = executor;
        }

        /**
         * @return The executor of the callback.
         */
        @NonNull
        @VisibleForTesting
        public Executor getExecutor() {
            return mExecutor;
        }

        /**
         * Invoke the callback from executor.
         *
         * @param runnable The callback method to invoke.
         */
        public void invokeFromExecutor(@NonNull Runnable runnable) {
            mExecutor.execute(runnable);
        }

        /**
         * Called when subscription changed.
         *
         * @param subId The subscription id.
         */
        public void onSubscriptionChanged(int subId) {}

        /**
         * Called when {@link SubscriptionInfoInternal#areUiccApplicationsEnabled()} changed.
         *
         * @param subId The subscription id.
         */
        public void onUiccApplicationsEnabledChanged(int subId) {}

        /**
         * Called when {@link #getDefaultDataSubId()} changed.
         *
         * @param subId The subscription id.
         */
        public void onDefaultDataSubscriptionChanged(int subId) {}
    }

    /** Initialize the singleton instance and register to TelephonyServiceManager */
    public static SubscriptionManagerService init(@NonNull Context context, @NonNull Looper looper,
            @NonNull FeatureFlags featureFlags) {
        synchronized (SubscriptionManagerService.class) {
            if (sInstance == null) {
                sInstance = new SubscriptionManagerService(context, looper, featureFlags);
                TelephonyServiceManager.ServiceRegisterer serviceRegisterer =
                        TelephonyFrameworkInitializer.getTelephonyServiceManager()
                                .getSubscriptionServiceRegisterer();
                if (serviceRegisterer.get() == null) {
                    serviceRegisterer.register(sInstance);
                }
            } else {
                Log.wtf(LOG_TAG, "SubscriptionManagerService is already initialized.");
            }
        }
        return sInstance;
    }

    /**
     * The constructor
     *
     * @param context The context
     * @param looper The looper for the handler.
     */
    public SubscriptionManagerService(@NonNull Context context, @NonNull Looper looper,
            @NonNull FeatureFlags featureFlags) {
        logl("Created SubscriptionManagerService");
        sInstance = this;
        mContext = context;
        mFeatureFlags = featureFlags;
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        mEuiccManager = context.getSystemService(EuiccManager.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mPackageManager = context.getPackageManager();
        mVendorApiLevel = SystemProperties.getInt(
                "ro.vendor.api_level", Build.VERSION.DEVICE_INITIAL_SDK_INT);

        mUiccController = UiccController.getInstance();
        mHandler = new Handler(looper);
        mBackgroundHandler = new Handler(WorkerThread.get().getLooper());

        mDefaultVoiceSubId = new WatchedInt(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID)) {
            @Override
            public boolean set(int newValue) {
                int oldValue = mValue;
                if (super.set(newValue)) {
                    logl("Default voice subId changed from " + oldValue + " to " + newValue);
                    Settings.Global.putInt(mContext.getContentResolver(),
                            Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION, newValue);
                    return true;
                }
                return false;
            }
        };

        mDefaultDataSubId = new WatchedInt(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID)) {
            @Override
            public boolean set(int newValue) {
                int oldValue = mValue;
                if (super.set(newValue)) {
                    logl("Default data subId changed from " + oldValue + " to " + newValue);
                    Settings.Global.putInt(mContext.getContentResolver(),
                            Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION, newValue);
                    return true;
                }
                return false;
            }
        };

        mDefaultSmsSubId = new WatchedInt(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID)) {
            @Override
            public boolean set(int newValue) {
                int oldValue = mValue;
                if (super.set(newValue)) {
                    logl("Default SMS subId changed from " + oldValue + " to " + newValue);
                    Settings.Global.putInt(mContext.getContentResolver(),
                            Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION, newValue);
                    return true;
                }
                return false;
            }
        };

        mDefaultSubId = new WatchedInt(SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        mSimState = new int[mTelephonyManager.getSupportedModemCount()];
        Arrays.fill(mSimState, TelephonyManager.SIM_STATE_UNKNOWN);

        Looper dbLooper = null;

        if (SubscriptionManagerService.USE_WORKER_THREAD) {
            dbLooper = WorkerThread.get().getLooper();
        } else {
            // Create a separate thread for subscription database manager.
            // The database will be updated from a different thread.
            HandlerThread handlerThread = new HandlerThread(LOG_TAG);
            handlerThread.start();
            dbLooper = handlerThread.getLooper();
        }

        mSubscriptionDatabaseManager = new SubscriptionDatabaseManager(
                context,
                dbLooper,
                mFeatureFlags,
                new SubscriptionDatabaseManagerCallback(mHandler::post) {
                    /**
                     * Called when database has been loaded into the cache.
                     */
                    @Override
                    public void onInitialized() {
                        log("Subscription database has been initialized.");
                        for (int phoneId = 0; phoneId < mTelephonyManager.getSupportedModemCount()
                                ; phoneId++) {
                            markSubscriptionsInactive(phoneId);
                        }
                    }

                    /**
                     * Called when subscription changed.
                     *
                     * @param subId The subscription id.
                     */
                    @Override
                    public void onSubscriptionChanged(int subId) {
                        updateUserIdToAvailableSubs();

                        mSubscriptionManagerServiceCallbacks.forEach(
                                callback -> callback.invokeFromExecutor(
                                        () -> callback.onSubscriptionChanged(subId)));

                        MultiSimSettingController.getInstance().notifySubscriptionInfoChanged();

                        TelephonyRegistryManager telephonyRegistryManager =
                                mContext.getSystemService(TelephonyRegistryManager.class);
                        if (telephonyRegistryManager != null) {
                            telephonyRegistryManager.notifySubscriptionInfoChanged();
                        }

                        SubscriptionInfoInternal subInfo =
                                mSubscriptionDatabaseManager.getSubscriptionInfoInternal(subId);
                        if (subInfo != null && subInfo.isOpportunistic()
                                && telephonyRegistryManager != null) {
                            telephonyRegistryManager.notifyOpportunisticSubscriptionInfoChanged();
                        }
                    }
                });

        // Broadcast sub Id on service initialized.
        broadcastSubId(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED,
                getDefaultDataSubId());
        mSubscriptionManagerServiceCallbacks.forEach(
                callback -> callback.invokeFromExecutor(
                        () -> callback.onDefaultDataSubscriptionChanged(
                                getDefaultDataSubId())));

        broadcastSubId(TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED,
                getDefaultVoiceSubId());
        broadcastSubId(SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED,
                getDefaultSmsSubId());
        updateDefaultSubId();

        mHandler.post(() -> {
            // EuiccController is created after SubscriptionManagerService. So we need to get
            // the instance later in the handler.
            if (mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TELEPHONY_EUICC)) {
                mEuiccController = EuiccController.get();
            }
        });

        // Initialize AtomicFile for persistence
        File storageDir = getProtectedStorageDir();
        if (storageDir != null) {
            mEnrollablePlansFile = new AtomicFile(new File(storageDir, ENROLLABLE_PLANS_FILE));
        } else {
            mEnrollablePlansFile = null;
        }
        mHandler.post(this::readEnrollableSubscriptionPlans);
        // Register for time change for plan expiration.
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mHandler.post(() -> rescheduleAllPlanExpirations());
            }
        }, new IntentFilter(Intent.ACTION_TIME_CHANGED));

        SubscriptionManager.invalidateSubscriptionManagerServiceCaches();

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateEmbeddedSubscriptions();
            }
        }, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        logl("Registered iSub service");
    }

    /**
     * @return The singleton instance of {@link SubscriptionManagerService}.
     */
    @NonNull
    public static SubscriptionManagerService getInstance() {
        return sInstance;
    }

    /**
     * Check if the calling package can manage the subscription group.
     *
     * @param groupUuid a UUID assigned to the subscription group.
     * @param callingPackage the package making the IPC.
     *
     * @return {@code true} if calling package is the owner of or has carrier privileges for all
     * subscriptions in the group.
     */
    private boolean canPackageManageGroup(@NonNull ParcelUuid groupUuid,
            @NonNull String callingPackage) {
        if (groupUuid == null) {
            throw new IllegalArgumentException("Invalid groupUuid");
        }

        if (TextUtils.isEmpty(callingPackage)) {
            throw new IllegalArgumentException("Empty callingPackage");
        }

        List<SubscriptionInfo> infoList;

        // Getting all subscriptions in the group.
        infoList = mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                .filter(subInfo -> subInfo.getGroupUuid().equals(groupUuid.toString()))
                .map(SubscriptionInfoInternal::toSubscriptionInfo)
                .collect(Collectors.toList());

        // If the group does not exist, then by default the UUID is up for grabs so no need to
        // restrict management of a group (that someone may be attempting to create).
        if (ArrayUtils.isEmpty(infoList)) {
            return true;
        }

        // If the calling package is the group owner, skip carrier permission check and return
        // true as it was done before.
        if (callingPackage.equals(infoList.get(0).getGroupOwner())) return true;

        // Check carrier privilege for all subscriptions in the group.
        return (checkCarrierPrivilegeOnSubList(infoList.stream()
                .mapToInt(SubscriptionInfo::getSubscriptionId).toArray(), callingPackage));
    }

    /**
     * Helper function to check if the caller has carrier privilege permissions on a list of subId.
     * The check can either be processed against access rules on currently active SIM cards, or
     * the access rules we keep in our database for currently inactive SIMs.
     *
     * @param subIdList List of subscription ids.
     * @param callingPackage The package making the call.
     *
     * @throws IllegalArgumentException if the some subId is invalid or doesn't exist.
     *
     * @return {@code true} if checking passes on all subId, {@code false} otherwise.
     */
    private boolean checkCarrierPrivilegeOnSubList(@NonNull int[] subIdList,
            @NonNull String callingPackage) {
        for (int subId : subIdList) {
            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternal(subId);
            if (subInfo == null) {
                loge("checkCarrierPrivilegeOnSubList: subId " + subId + " does not exist.");
                return false;
            }

            if (subInfo.isActive()) {
                if (!mTelephonyManager.hasCarrierPrivileges(subId)) {
                    loge("checkCarrierPrivilegeOnSubList: Does not have carrier privilege on sub "
                            + subId);
                    return false;
                }
            } else {
                if (!canManageSubscription(subInfo.toSubscriptionInfo(),
                        callingPackage)) {
                    loge("checkCarrierPrivilegeOnSubList: cannot manage sub " + subId);
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Sync the settings from specified subscription to all grouped subscriptions.
     *
     * @param subId The subscription id of the referenced subscription.
     */
    public void syncGroupedSetting(int subId) {
        mHandler.post(() -> {
            SubscriptionInfoInternal reference = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternal(subId);
            if (reference == null) {
                loge("syncSettings: Can't find subscription info for sub " + subId);
                return;
            }

            mSubscriptionDatabaseManager.syncToGroup(subId);
        });
    }

    /**
     * Check whether the {@code callingPackage} has access to the phone number on the specified
     * {@code subId} or not.
     *
     * @param subId The subscription id.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @param message Message to include in the exception or NoteOp.
     *
     * @return {@code true} if the caller has phone number access.
     */
    private boolean hasPhoneNumberAccess(int subId, @NonNull String callingPackage,
            @Nullable String callingFeatureId, @Nullable String message) {
        try {
            return TelephonyPermissions.checkCallingOrSelfReadPhoneNumber(mContext, subId,
                    callingPackage, callingFeatureId, message);
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * Check whether the {@code callingPackage} has access to subscriber identifiers on the
     * specified {@code subId} or not.
     *
     * @param subId The subscription id.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @param message Message to include in the exception or NoteOp.
     * @param reportFailure Indicates if failure should be reported.
     *
     * @return {@code true} if the caller has identifier access.
     */
    private boolean hasSubscriberIdentifierAccess(int subId, @NonNull String callingPackage,
            @Nullable String callingFeatureId, @Nullable String message, boolean reportFailure) {
        try {
            return TelephonyPermissions.checkCallingOrSelfReadSubscriberIdentifiers(mContext, subId,
                    callingPackage, callingFeatureId, message, reportFailure);
        } catch (SecurityException e) {
            // A SecurityException indicates that the calling package is targeting at least the
            // minimum level that enforces identifier access restrictions and the new access
            // requirements are not met.
            return false;
        }
    }

    /**
     * Conditionally removes identifiers from the provided {@link SubscriptionInfo} if the {@code
     * callingPackage} does not meet the access requirements for identifiers and returns the
     * potentially modified object.
     *
     * <p>
     * If the caller does not have {@link Manifest.permission#READ_PHONE_NUMBERS} permission,
     * {@link SubscriptionInfo#getNumber()} will return empty string.
     * If the caller does not have {@link Manifest.permission#USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER},
     * {@link SubscriptionInfo#getIccId()} and {@link SubscriptionInfo#getCardString()} will return
     * empty string, and {@link SubscriptionInfo#getGroupUuid()} will return {@code null}.
     *
     * @param subInfo The subscription info.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @param message Message to include in the exception or NoteOp.
     *
     * @return The modified {@link SubscriptionInfo} depending on caller's permission.
     */
    @NonNull
    private SubscriptionInfo conditionallyRemoveIdentifiers(@NonNull SubscriptionInfo subInfo,
            @NonNull String callingPackage, @Nullable String callingFeatureId,
            @Nullable String message) {
        int subId = subInfo.getSubscriptionId();
        boolean hasIdentifierAccess = hasSubscriberIdentifierAccess(subId, callingPackage,
                callingFeatureId, message, true);
        boolean hasPhoneNumberAccess = hasPhoneNumberAccess(subId, callingPackage,
                callingFeatureId, message);

        if (hasIdentifierAccess && hasPhoneNumberAccess) {
            return subInfo;
        }

        SubscriptionInfo.Builder result = new SubscriptionInfo.Builder(subInfo);
        if (!hasIdentifierAccess) {
            result.setIccId(null);
            result.setCardString(null);
            result.setGroupUuid(null);
        }

        if (!hasPhoneNumberAccess) {
            result.setNumber(null);
        }
        return result.build();
    }

    /**
     * Get the stripped ICCID, which sometimes ended with 'F'. Also not doing this if the ICCID
     * is MAC address (used by texting through bluetooth).
     *
     * @param iccId The original ICCID.
     * @return The fixed ICCID.
     */
    @VisibleForTesting
    @NonNull
    public static String getStrippedIccid(@NonNull String iccId) {
        Matcher matcher = MAC_ADDRESS_PATTERN.matcher(iccId);
        if (matcher.matches()) return iccId;

        return TextUtils.emptyIfNull(IccUtils.stripTrailingFs(iccId));
    }

    /**
     * @return The list of ICCIDs from the inserted physical SIMs.
     */
    @NonNull
    private List<String> getIccIdsOfInsertedPhysicalSims() {
        List<String> iccidList = new ArrayList<>();
        UiccSlot[] uiccSlots = mUiccController.getUiccSlots();
        if (uiccSlots == null) return iccidList;

        for (UiccSlot uiccSlot : uiccSlots) {
            if (uiccSlot != null && uiccSlot.getCardState() != null
                    && uiccSlot.getCardState().isCardPresent() && !uiccSlot.isEuicc()) {
                // Non euicc slots will have single port, so use default port index.
                String iccId = uiccSlot.getIccId(TelephonyManager.DEFAULT_PORT_INDEX);
                if (!TextUtils.isEmpty(iccId)) {
                    iccidList.add(getStrippedIccid(iccId));
                }
            }
        }

        return iccidList;
    }

    /**
     * Set the subscription carrier id.
     *
     * @param subId Subscription id.
     * @param carrierId The carrier id.
     *
     * @throws IllegalArgumentException if {@code subId} is invalid or the subscription does not
     * exist.
     *
     * @see TelephonyManager#getSimCarrierId()
     */
    public void setCarrierId(int subId, int carrierId) {
        // This can throw IllegalArgumentException if the subscription does not exist.
        try {
            mSubscriptionDatabaseManager.setCarrierId(subId, carrierId);
        } catch (IllegalArgumentException e) {
            loge("setCarrierId: invalid subId=" + subId);
        }
    }

    /**
     * Set MCC/MNC by subscription id.
     *
     * @param mccMnc MCC/MNC associated with the subscription.
     * @param subId The subscription id.
     */
    public void setMccMnc(int subId, @NonNull String mccMnc) {
        // This can throw IllegalArgumentException if the subscription does not exist.
        try {
            mSubscriptionDatabaseManager.setMcc(subId, mccMnc.substring(0, 3));
            mSubscriptionDatabaseManager.setMnc(subId, mccMnc.substring(3));
        } catch (IllegalArgumentException e) {
            loge("setMccMnc: invalid subId=" + subId);
        }
    }

    /**
     * Set whether the subscription ID supports oem satellite or not.
     *
     * @param subId The subscription ID.
     * @param isNtn {@code true} Requested subscription ID supports oem satellite service,
     * {@code false} otherwise.
     */
    public void setNtn(int subId, boolean isNtn) {
        // This can throw IllegalArgumentException if the subscription does not exist.
        try {
            mSubscriptionDatabaseManager.setNtn(subId, (isNtn ? 1 : 0));
        } catch (IllegalArgumentException e) {
            loge("setOnlyNonTerrestrialNetwork: invalid subId=" + subId);
        }
    }

    /**
     * Set ISO country code by subscription id.
     *
     * @param iso ISO country code associated with the subscription.
     * @param subId The subscription id.
     */
    public void setCountryIso(int subId, @NonNull String iso) {
        logl("setCountryIso: subId=" + subId + ", iso=" + iso);

        // This can throw IllegalArgumentException if the subscription does not exist.
        try {
            mSubscriptionDatabaseManager.setCountryIso(subId, iso);
        } catch (IllegalArgumentException e) {
            loge("setCountryIso: invalid subId=" + subId);
        }
    }

    /**
     * Set the name displayed to the user that identifies subscription provider name. This name
     * is the SPN displayed in status bar and many other places. Can't be renamed by the user.
     *
     * @param subId Subscription id.
     * @param carrierName The carrier name.
     */
    public void setCarrierName(int subId, @NonNull String carrierName) {
        logl("setCarrierName: subId=" + subId + ", carrierName=" + carrierName);

        // This can throw IllegalArgumentException if the subscription does not exist.
        try {
            mSubscriptionDatabaseManager.setCarrierName(subId, carrierName);
        } catch (IllegalArgumentException e) {
            loge("setCarrierName: invalid subId=" + subId);
        }
    }

    /**
     * Set the group owner on the subscription
     *
     * <p> Note: This only sets the group owner field and doesn't update other relevant fields.
     * Prefer to call {@link #addSubscriptionsIntoGroup}.
     *
     * @param subId Subscription id.
     * @param groupOwner The group owner to assign to the subscription
     *
     * @throws SecurityException if the caller does not have required permissions.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setGroupOwner(int subId, @NonNull String groupOwner) {
        enforcePermissions("setGroupOwner", Manifest.permission.MODIFY_PHONE_STATE);
        try {
            mSubscriptionDatabaseManager.setGroupOwner(
                    subId,
                    groupOwner);
        } catch (IllegalArgumentException e) {
            loge("setManaged: invalid subId=" + subId);
        }
    }

    /**
     * Set last used TP message reference.
     *
     * @param subId Subscription id.
     * @param lastUsedTPMessageReference Last used TP message reference.
     */
    public void setLastUsedTPMessageReference(int subId, int lastUsedTPMessageReference) {
        // This can throw IllegalArgumentException if the subscription does not exist.
        try {
            mSubscriptionDatabaseManager.setLastUsedTPMessageReference(
                    subId, lastUsedTPMessageReference);
        } catch (IllegalArgumentException e) {
            loge("setLastUsedTPMessageReference: invalid subId=" + subId);
        }
    }

    /**
     * Set the enabled mobile data policies.
     *
     * @param subId Subscription id.
     * @param enabledMobileDataPolicies The enabled mobile data policies.
     */
    public void setEnabledMobileDataPolicies(int subId, @NonNull String enabledMobileDataPolicies) {
        // This can throw IllegalArgumentException if the subscription does not exist.
        try {
            mSubscriptionDatabaseManager.setEnabledMobileDataPolicies(
                    subId, enabledMobileDataPolicies);
        } catch (IllegalArgumentException e) {
            loge("setEnabledMobileDataPolicies: invalid subId=" + subId);
        }
    }

    /**
     * Set the phone number retrieved from IMS.
     *
     * @param subId Subscription id.
     * @param numberFromIms The phone number retrieved from IMS.
     */
    public void setNumberFromIms(int subId, @NonNull String numberFromIms) {
        logl("setNumberFromIms: subId=" + subId + ", number="
                + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, numberFromIms));

        // This can throw IllegalArgumentException if the subscription does not exist.
        try {
            mSubscriptionDatabaseManager.setNumberFromIms(subId, numberFromIms);
        } catch (IllegalArgumentException e) {
            loge("setNumberFromIms: invalid subId=" + subId);
        }
    }

    /**
     * Mark all subscriptions on this SIM slot index inactive.
     *
     * @param simSlotIndex The logical SIM slot index (i.e. phone id).
     */
    public void markSubscriptionsInactive(int simSlotIndex) {
        logl("markSubscriptionsInactive: slot " + simSlotIndex);
        mSlotIndexToSubId.remove(simSlotIndex);
        mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                .filter(subInfo -> subInfo.getSimSlotIndex() == simSlotIndex)
                .forEach(subInfo -> {
                    mSubscriptionDatabaseManager.setSimSlotIndex(subInfo.getSubscriptionId(),
                            SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                    // Sometime even though slot-port is inactive, proper iccid will be present,
                    // hence retry the port index from UiccSlot. (Pre-U behavior)
                    mSubscriptionDatabaseManager.setPortIndex(subInfo.getSubscriptionId(),
                            getPortIndex(subInfo.getIccId()));
                });
        updateGroupDisabled();
        logl("markSubscriptionsInactive: current mapping " + slotMappingToString());
    }

    /**
     * This is only for internal use and the returned priority is arbitrary. The idea is to give a
     * higher value to name source that has higher priority to override other name sources.
     *
     * @param nameSource Source of display name.
     *
     * @return The priority. Higher value means higher priority.
     */
    private static int getNameSourcePriority(@SimDisplayNameSource int nameSource) {
        int index = Arrays.asList(
                SubscriptionManager.NAME_SOURCE_UNKNOWN,
                SubscriptionManager.NAME_SOURCE_CARRIER_ID,
                SubscriptionManager.NAME_SOURCE_SIM_PNN,
                SubscriptionManager.NAME_SOURCE_SIM_SPN,
                SubscriptionManager.NAME_SOURCE_CARRIER,
                SubscriptionManager.NAME_SOURCE_USER_INPUT // user has highest priority.
        ).indexOf(nameSource);
        return Math.max(0, index);
    }

    /**
     * Randomly pick a color from {@link R.array#sim_colors}.
     *
     * @return The selected color for the subscription.
     */
    private int getColor() {
        int[] colors = mContext.getResources().getIntArray(com.android.internal.R.array.sim_colors);
        if (colors.length == 0) return 0xFFFFFFFF; // white
        Random rand = new Random();
        return colors[rand.nextInt(colors.length)];
    }

    /**
     * Get the port index by ICCID.
     *
     * @param iccId The ICCID.
     * @return The port index.
     */
    private int getPortIndex(@NonNull String iccId) {
        UiccSlot[] slots = mUiccController.getUiccSlots();
        for (UiccSlot slot : slots) {
            if (slot != null) {
                int portIndex = slot.getPortIndexFromIccId(iccId);
                if (portIndex != TelephonyManager.INVALID_PORT_INDEX) {
                    return portIndex;
                }
            }
        }
        return TelephonyManager.INVALID_PORT_INDEX;
    }

    /**
     * Insert a new subscription into the database.
     *
     * @param iccId The ICCID.
     * @param slotIndex The logical SIM slot index (i.e. phone id).
     * @param displayName The display name.
     * @param subscriptionType The subscription type.
     *
     * @return The subscription id.
     */
    private int insertSubscriptionInfo(@NonNull String iccId, int slotIndex,
            @Nullable String displayName, @SubscriptionType int subscriptionType) {
        String defaultAllowNetworkTypes = Phone.convertAllowedNetworkTypeMapIndexToDbName(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER) + "="
                + RadioAccessFamily.getRafFromNetworkType(RILConstants.PREFERRED_NETWORK_MODE);
        SubscriptionInfoInternal.Builder builder = new SubscriptionInfoInternal.Builder()
                .setIccId(iccId)
                .setCardString(iccId)
                .setSimSlotIndex(slotIndex)
                .setType(subscriptionType)
                .setIconTint(getColor())
                .setAllowedNetworkTypesForReasons(defaultAllowNetworkTypes);
        if (displayName != null) {
            builder.setDisplayName(displayName);
        }

        int subId = mSubscriptionDatabaseManager.insertSubscriptionInfo(builder.build());
        logl("insertSubscriptionInfo: Inserted a new subscription. subId=" + subId
                + ", slotIndex=" + slotIndex + ", iccId=" + SubscriptionInfo.getPrintableId(iccId)
                + ", displayName=" + displayName + ", type="
                + SubscriptionManager.subscriptionTypeToString(subscriptionType));
        return subId;
    }

    /**
     * Pull the embedded subscription from {@link EuiccController} for the eUICC with the given list
     * of card IDs {@code cardIds}.
     *
     * @param cardIds The card ids of the embedded subscriptions.
     * @param callback Callback to be called upon completion.
     */
    public void updateEmbeddedSubscriptions(@NonNull List<Integer> cardIds,
            @Nullable Runnable callback) {
        // Run this on a background thread.
        mBackgroundHandler.post(() -> {
            // Do nothing if eUICCs are disabled. (Previous entries may remain in the cache, but
            // they are filtered out of list calls as long as EuiccManager.isEnabled returns false).
            if (mEuiccManager == null || !mEuiccManager.isEnabled() || mEuiccController == null) {
                loge("updateEmbeddedSubscriptions: eUICC not enabled");
                if (callback != null) {
                    callback.run();
                }
                return;
            }

            Set<Integer> embeddedSubs = new ArraySet<>();
            log("updateEmbeddedSubscriptions: start to get euicc profiles.");

            for (UiccSlot slot : mUiccController.getUiccSlots()) {
                if (slot != null) {
                    log("  " + slot);
                }
            }

            // The flag indicating getting successful result from EuiccController.
            boolean isProfileUpdateSuccessful = false;

            for (int cardId : cardIds) {
                GetEuiccProfileInfoListResult result = mEuiccController
                        .blockingGetEuiccProfileInfoList(cardId);
                logl("updateEmbeddedSubscriptions: cardId=" + cardId + ", result=" + result);
                if (result == null) {
                    //TODO: Add back-off retry in the future if needed.
                    loge("Failed to get euicc profiles.");
                    continue;
                }

                if (result.getResult() != EuiccService.RESULT_OK) {
                    loge("Failed to get euicc profile info. result="
                            + EuiccService.resultToString(result.getResult()));
                    continue;
                }

                isProfileUpdateSuccessful = true;

                if (result.getProfiles() == null || result.getProfiles().isEmpty()) {
                    loge("No profiles returned.");
                    continue;
                }

                final boolean isRemovable = result.getIsRemovable();

                for (EuiccProfileInfo embeddedProfile : result.getProfiles()) {
                    SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                            .getSubscriptionInfoInternalByIccId(embeddedProfile.getIccid());

                    // The subscription does not exist in the database. Insert a new one here.
                    if (subInfo == null) {
                        int subId = insertSubscriptionInfo(embeddedProfile.getIccid(),
                                SubscriptionManager.INVALID_SIM_SLOT_INDEX,
                                null, SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
                        mSubscriptionDatabaseManager.setDisplayName(subId, mContext.getResources()
                                .getString(R.string.default_card_name, getCardNumber(subId)));
                        subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(subId);
                    }

                    int nameSource = subInfo.getDisplayNameSource();
                    int carrierId = subInfo.getCarrierId();

                    SubscriptionInfoInternal.Builder builder = new SubscriptionInfoInternal
                            .Builder(subInfo);

                    builder.setEmbedded(1);

                    List<UiccAccessRule> ruleList = embeddedProfile.getUiccAccessRules();
                    if (ruleList != null && !ruleList.isEmpty()) {
                        builder.setNativeAccessRules(embeddedProfile.getUiccAccessRules());
                    }
                    builder.setRemovableEmbedded(isRemovable);

                    // override DISPLAY_NAME if the priority of existing nameSource is <= carrier
                    String nickName = embeddedProfile.getNickname();
                    if (nickName != null
                            && getNameSourcePriority(nameSource) <= getNameSourcePriority(
                                    SubscriptionManager.NAME_SOURCE_CARRIER)) {
                        builder.setDisplayName(nickName);
                        builder.setDisplayNameSource(SubscriptionManager.NAME_SOURCE_CARRIER);
                    }

                    boolean isSatelliteSpn = false;
                    if (isSatelliteSpn(embeddedProfile.getServiceProviderName())) {
                        isSatelliteSpn = true;
                        builder.setOnlyNonTerrestrialNetwork(1);
                    }

                    builder.setProfileClass(embeddedProfile.getProfileClass());
                    builder.setPortIndex(getPortIndex(embeddedProfile.getIccid()));

                    CarrierIdentifier cid = embeddedProfile.getCarrierIdentifier();
                    if (cid != null) {
                        // Due to the limited subscription information, carrier id identified here
                        // might not be accurate compared with CarrierResolver. Only update carrier
                        // id if there is no valid carrier id present.
                        if (carrierId == TelephonyManager.UNKNOWN_CARRIER_ID) {
                            builder.setCarrierId(CarrierResolver
                                    .getCarrierIdFromIdentifier(mContext, cid));
                        }
                        String mcc = cid.getMcc();
                        String mnc = cid.getMnc();
                        builder.setMcc(mcc);
                        builder.setMnc(mnc);
                        if (!isSatelliteSpn) {
                            builder.setOnlyNonTerrestrialNetwork(
                                    isSatellitePlmn(mcc + mnc) ? 1 : 0);
                        }
                    }
                    // If cardId = unsupported or un-initialized, we have no reason to update DB.
                    // Additionally, if the device does not support cardId for default eUICC, the
                    // CARD_ID field should not contain the EID
                    if (cardId >= 0 && mUiccController.getCardIdForDefaultEuicc()
                            != TelephonyManager.UNSUPPORTED_CARD_ID) {
                        builder.setCardId(cardId);
                        builder.setCardString(mUiccController.convertToCardString(cardId));
                    }

                    if (mFeatureFlags.supportPsimToEsimConversion()) {
                        builder.setTransferStatus(subInfo.getTransferStatus());
                    }
                    embeddedSubs.add(subInfo.getSubscriptionId());

                    subInfo = builder.build();
                    log("updateEmbeddedSubscriptions: update subscription " + subInfo);
                    mSubscriptionDatabaseManager.updateSubscription(subInfo);
                }
            }

            // Marked the previous embedded subscriptions non-embedded if the latest profiles do
            // not include them anymore.
            if (isProfileUpdateSuccessful) {
                // embeddedSubs contains all the existing embedded subs queried from EuiccManager,
                // including active or inactive. If there are any embedded subscription in the
                // database that is not in embeddedSubs, mark them as non-embedded. These were
                // deleted embedded subscriptions, so we treated them as non-embedded (pre-U
                // behavior) and they don't show up in Settings SIM page.
                mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                        .filter(SubscriptionInfoInternal::isEmbedded)
                        .filter(subInfo -> !embeddedSubs.contains(subInfo.getSubscriptionId()))
                        .forEach(subInfo -> {
                            logl("updateEmbeddedSubscriptions: Mark the deleted sub "
                                    + subInfo.getSubscriptionId() + " as non-embedded.");
                            mSubscriptionDatabaseManager.setEmbedded(
                                    subInfo.getSubscriptionId(), false);
                        });
                if (mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                        .anyMatch(subInfo -> subInfo.isEmbedded()
                                && subInfo.isActive()
                                && subInfo.getPortIndex()
                                == TelephonyManager.INVALID_PORT_INDEX
                                && mSimState[subInfo.getSimSlotIndex()]
                                == TelephonyManager.SIM_STATE_LOADED)) {
                    //Report Anomaly if invalid portIndex is updated in Active subscriptions
                    AnomalyReporter.reportAnomaly(
                            UUID.fromString("38fdf63c-3bd9-4fc2-ad33-a20246a32fa7"),
                            "SubscriptionManagerService: Found Invalid portIndex"
                                    + " in active subscriptions");
                }
            } else {
                loge("The eSIM profiles update was not successful.");
            }
            log("updateEmbeddedSubscriptions: Finished embedded subscription update.");
            // The runnable will be executed in the main thread. Pre Android-U behavior.
            mHandler.post(() -> {
                if (callback != null) {
                    callback.run();
                }
            });
        });
    }

    /**
     * Update embedded subscriptions from {@link EuiccController}.
     */
    private void updateEmbeddedSubscriptions() {
        UiccSlot[] uiccSlots = mUiccController.getUiccSlots();
        if (uiccSlots != null) {
            List<Integer> cardIds = new ArrayList<>();
            for (UiccSlot uiccSlot : uiccSlots) {
                if (uiccSlot != null && uiccSlot.isEuicc() && uiccSlot.getUiccCard() != null) {
                    int cardId = mUiccController.convertToPublicCardId(
                            uiccSlot.getUiccCard().getCardId());
                    cardIds.add(cardId);
                }
            }
            if (!cardIds.isEmpty()) {
                updateEmbeddedSubscriptions(cardIds, null);
            }
        }
    }

    /**
     * Check if the SIM application is enabled on the card or not.
     *
     * @param phoneId The phone id.
     *
     * @return {@code true} if the application is enabled.
     */
    public boolean areUiccAppsEnabledOnCard(int phoneId) {
        // When uicc apps are disabled(supported in IRadio 1.5), we will still get IccId from
        // cardStatus (since IRadio 1.2). And upon cardStatus change we'll receive another
        // handleSimNotReady so this will be evaluated again.
        UiccSlot slot = mUiccController.getUiccSlotForPhone(phoneId);
        if (slot == null) return false;
        UiccPort port = mUiccController.getUiccPort(phoneId);
        String iccId = (port == null) ? null : port.getIccId();
        if (iccId == null) {
            return false;
        }

        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                .getSubscriptionInfoInternalByIccId(getStrippedIccid(iccId));
        return subInfo != null && subInfo.areUiccApplicationsEnabled();
    }

    /**
     * Get ICCID by phone id.
     *
     * @param phoneId The phone id (i.e. Logical SIM slot index.)
     *
     * @return The ICCID. Empty string if not available.
     */
    @NonNull
    private String getIccId(int phoneId) {
        UiccPort port = mUiccController.getUiccPort(phoneId);
        return (port == null) ? "" : getStrippedIccid(port.getIccId());
    }

    /**
     * @return {@code true} if all the need-to-be-loaded subscriptions from SIM slots are already
     * loaded. {@code false} if more than one are still being loaded.
     */
    private boolean areAllSubscriptionsLoaded() {
        for (int phoneId = 0; phoneId < mTelephonyManager.getActiveModemCount(); phoneId++) {
            UiccSlot slot = mUiccController.getUiccSlotForPhone(phoneId);
            if (slot == null) {
                log("areAllSubscriptionsLoaded: slot is null. phoneId=" + phoneId);
                return false;
            }
            if (!slot.isActive()) {
                log("areAllSubscriptionsLoaded: slot is inactive. phoneId=" + phoneId);
                return false;
            }
            if (slot.isEuicc() && mUiccController.getUiccPort(phoneId) == null) {
                log("Wait for port corresponding to phone " + phoneId + " to be active, portIndex "
                        + "is " + slot.getPortIndexFromPhoneId(phoneId));
                return false;
            }

            if (mSimState[phoneId] == TelephonyManager.SIM_STATE_NOT_READY) {
                // Check if this is the final state.
                IccCard iccCard = PhoneFactory.getPhone(phoneId).getIccCard();
                if (!iccCard.isEmptyProfile() && areUiccAppsEnabledOnCard(phoneId)) {
                    log("areAllSubscriptionsLoaded: NOT_READY is not a final state.");
                    return false;
                }
            }

            if (mSimState[phoneId] == TelephonyManager.SIM_STATE_UNKNOWN) {
                log("areAllSubscriptionsLoaded: SIM " + phoneId + " state is still unknown.");
                return false;
            }
        }

        return true;
    }

    /**
     * Update the subscription on the logical SIM slot index (i.e. phone id).
     *
     * @param phoneId The phone id (i.e. Logical SIM slot index)
     */
    private void updateSubscription(int phoneId) {
        int simState = mSimState[phoneId];
        log("updateSubscription: phoneId=" + phoneId + ", simState="
                + TelephonyManager.simStateToString(simState));
        for (UiccSlot slot : mUiccController.getUiccSlots()) {
            if (slot != null) {
                log("  " + slot);
            }
        }

        if (simState == TelephonyManager.SIM_STATE_ABSENT) {
            SatelliteController satelliteController = SatelliteController.getInstance();
            boolean isSatelliteEnabledOrBeingEnabled = false;
            if (satelliteController != null) {
                isSatelliteEnabledOrBeingEnabled =
                        satelliteController.isSatelliteEnabledOrBeingEnabled();
            }

            if (!isSatelliteEnabledOrBeingEnabled) {
                // Re-enable the pSIM when it's removed, so it will be in enabled state when it gets
                // re-inserted again. (pre-U behavior)
                List<String> iccIds = getIccIdsOfInsertedPhysicalSims();
                mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                        .filter(subInfo -> !iccIds.contains(subInfo.getIccId())
                                && !subInfo.isEmbedded())
                        .forEach(subInfo -> {
                            int subId = subInfo.getSubscriptionId();
                            log("updateSubscription: Re-enable Uicc application on sub " + subId);
                            mSubscriptionDatabaseManager.setUiccApplicationsEnabled(subId, true);
                            // When sim is absent, set the port index to invalid port index.
                            // (pre-U behavior)
                            mSubscriptionDatabaseManager.setPortIndex(subId,
                                    TelephonyManager.INVALID_PORT_INDEX);
                        });
            }

            if (mSlotIndexToSubId.containsKey(phoneId)) {
                markSubscriptionsInactive(phoneId);
            }
        } else if (simState == TelephonyManager.SIM_STATE_NOT_READY) {
            // Check if this is the final state. Only update the subscription if NOT_READY is a
            // final state.
            IccCard iccCard = PhoneFactory.getPhone(phoneId).getIccCard();
            if (iccCard.isEmptyProfile()) log("updateSubscription: iccCard has empty profile.");
            if (!iccCard.isEmptyProfile() && areUiccAppsEnabledOnCard(phoneId)) {
                log("updateSubscription: SIM_STATE_NOT_READY is not a final state. Will update "
                        + "subscription later.");
                return;
            } else {
                logl("updateSubscription: UICC app disabled on slot " + phoneId);
                markSubscriptionsInactive(phoneId);
            }
        } else {
            String iccId = getIccId(phoneId);
            log("updateSubscription: Found iccId=" + SubscriptionInfo.getPrintableId(iccId)
                    + " on phone " + phoneId);

            // For eSIM switching, SIM absent will not happen. Below is to exam if we find ICCID
            // mismatch on the SIM slot. If that's the case, we need to mark all subscriptions on
            // that logical slot invalid first. The correct subscription will be assigned the
            // correct slot later.
            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager.getAllSubscriptions()
                    .stream()
                    .filter(sub -> sub.getSimSlotIndex() == phoneId && !iccId.equals(
                            sub.getIccId()))
                    .findFirst()
                    .orElse(null);
            if (subInfo != null) {
                log("updateSubscription: Found previous active sub " + subInfo.getSubscriptionId()
                        + " that doesn't match current iccid on slot " + phoneId + ".");
                markSubscriptionsInactive(phoneId);
            }

            if (!TextUtils.isEmpty(iccId)) {
                // Check if the subscription already existed.
                subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternalByIccId(iccId);
                int subId;
                if (subInfo == null) {
                    // This is a new SIM card. Insert a new record.
                    subId = insertSubscriptionInfo(iccId, phoneId, null,
                            SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
                    mSubscriptionDatabaseManager.setDisplayName(subId,
                            mContext.getResources().getString(R.string.default_card_name,
                                    getCardNumber(subId)));
                } else {
                    subId = subInfo.getSubscriptionId();
                    log("updateSubscription: Found existing subscription. subId= " + subId
                            + ", phoneId=" + phoneId);
                }

                subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(subId);
                if (subInfo != null && subInfo.areUiccApplicationsEnabled()) {
                    mSlotIndexToSubId.put(phoneId, subId);
                    // Update the SIM slot index. This will make the subscription active.
                    mSubscriptionDatabaseManager.setSimSlotIndex(subId, phoneId);
                    logl("updateSubscription: current mapping " + slotMappingToString());
                }

                // Update the card id.
                UiccCard card = mUiccController.getUiccCardForPhone(phoneId);
                if (card != null) {
                    String cardId = card.getCardId();
                    if (cardId != null) {
                        mSubscriptionDatabaseManager.setCardString(subId, cardId);
                    }
                }

                // Update the port index.
                mSubscriptionDatabaseManager.setPortIndex(subId, getPortIndex(iccId));

                if (simState == TelephonyManager.SIM_STATE_LOADED) {
                    String mccMnc = mTelephonyManager.getSimOperatorNumeric(subId);
                    if (!TextUtils.isEmpty(mccMnc)) {
                        if (subId == getDefaultSubId()) {
                            MccTable.updateMccMncConfiguration(mContext, mccMnc);
                        }
                        setMccMnc(subId, mccMnc);
                        if (isSatelliteSpn(subInfo.getDisplayName()) || isSatellitePlmn(mccMnc)) {
                            setNtn(subId, true);
                        }
                    } else {
                        loge("updateSubscription: mcc/mnc is empty");
                    }

                    String iso = TelephonyManager.getSimCountryIsoForPhone(phoneId);

                    if (!TextUtils.isEmpty(iso)) {
                        setCountryIso(subId, iso);
                    } else {
                        loge("updateSubscription: sim country iso is null");
                    }

                    String msisdn = PhoneFactory.getPhone(phoneId).getLine1Number();
                    if (!TextUtils.isEmpty(msisdn)) {
                        setDisplayNumber(msisdn, subId);
                    }

                    String imsi = mTelephonyManager.createForSubscriptionId(
                            subId).getSubscriberId();
                    if (imsi != null) {
                        mSubscriptionDatabaseManager.setImsi(subId, imsi);
                    }

                    IccCard iccCard = PhoneFactory.getPhone(phoneId).getIccCard();
                    if (iccCard != null) {
                        IccRecords records = iccCard.getIccRecords();
                        if (records != null) {
                            String[] ehplmns = records.getEhplmns();
                            if (ehplmns != null) {
                                mSubscriptionDatabaseManager.setEhplmns(subId, ehplmns);
                            }
                            String[] hplmns = records.getPlmnsFromHplmnActRecord();
                            if (hplmns != null) {
                                mSubscriptionDatabaseManager.setHplmns(subId, hplmns);
                            }
                        } else {
                            loge("updateSubscription: ICC records are not available.");
                        }
                    } else {
                        loge("updateSubscription: ICC card is not available.");
                    }

                    // Attempt to restore SIM specific settings when SIM is loaded.
                    Bundle result = mContext.getContentResolver().call(
                            SubscriptionManager.SIM_INFO_BACKUP_AND_RESTORE_CONTENT_URI,
                            SubscriptionManager.RESTORE_SIM_SPECIFIC_SETTINGS_METHOD_NAME,
                            iccId, null);
                    if (result != null && result.getBoolean(
                            SubscriptionManager.RESTORE_SIM_SPECIFIC_SETTINGS_DATABASE_UPDATED)) {
                        logl("Sim specific settings changed the database.");
                        mSubscriptionDatabaseManager.reloadDatabaseSync();
                        PhoneFactory.getPhone(phoneId)
                                .loadAllowedNetworksFromSubscriptionDatabase();
                    }
                }

                log("updateSubscription: " + mSubscriptionDatabaseManager
                        .getSubscriptionInfoInternal(subId));
            } else {
                log("updateSubscription: No ICCID available for phone " + phoneId);
                mSlotIndexToSubId.remove(phoneId);
                logl("updateSubscription: current mapping " + slotMappingToString());
            }
        }

        if (areAllSubscriptionsLoaded()) {
            log("Notify all subscriptions loaded.");
            MultiSimSettingController.getInstance().notifyAllSubscriptionLoaded();
        }

        updateGroupDisabled();
        updateDefaultSubId();

        if (mSlotIndexToSubId.containsKey(phoneId) &&
                isEsimBootStrapProvisioningActiveForSubId(mSlotIndexToSubId.get(phoneId))) {
            startEsimBootstrapTimer();
        } else {
            cancelEsimBootstrapTimer();
        }
    }

    private void cancelEsimBootstrapTimer() {
        if (bootstrapProvisioningTimer != null) {
            bootstrapProvisioningTimer.cancel();
            bootstrapProvisioningTimer = null;
            log("bootstrapProvisioningTimer timer cancelled.");
        }
    }

    private void startEsimBootstrapTimer() {
        if (bootstrapProvisioningTimer == null) {
            bootstrapProvisioningTimer = new CountDownTimer(CHECK_BOOTSTRAP_TIMER_IN_MS,
                    CHECK_BOOTSTRAP_TIMER_IN_MS) {
                @Override
                public void onTick(long millisUntilFinished) {
                    // Do nothing
                }

                @Override
                public void onFinish() {
                    AnomalyReporter.reportAnomaly(UUID.fromString("40587b0f-27c9-4b39-b94d"
                                    + "-71fc9771f354"), "eSim bootstrap has been active for too "
                            + "long.");
                    log("bootstrapProvisioningTimer: timer finished esim was not disabled.");
                    cancelEsimBootstrapTimer();
                }
            }.start();
            log("bootstrapProvisioningTimer timer started.");
        }
    }

    /**
     * Calculate the usage setting based on the carrier request.
     *
     * @param currentUsageSetting the current setting in the subscription DB.
     * @param preferredUsageSetting provided by the carrier config.
     *
     * @return the calculated usage setting.
     */
    @VisibleForTesting
    @UsageSetting public int calculateUsageSetting(@UsageSetting int currentUsageSetting,
            @UsageSetting int preferredUsageSetting) {
        int[] supportedUsageSettings;

        //  Load the resources to provide the device capability
        try {
            supportedUsageSettings = mContext.getResources().getIntArray(
                    com.android.internal.R.array.config_supported_cellular_usage_settings);
            // If usage settings are not supported, return the default setting, which is UNKNOWN.
            if (supportedUsageSettings == null
                    || supportedUsageSettings.length < 1) return currentUsageSetting;
        } catch (Resources.NotFoundException nfe) {
            loge("calculateUsageSetting: Failed to load usage setting resources!");
            return currentUsageSetting;
        }

        // If the current setting is invalid, including the first time the value is set,
        // update it to default (this will trigger a change in the DB).
        if (currentUsageSetting < SubscriptionManager.USAGE_SETTING_DEFAULT
                || currentUsageSetting > SubscriptionManager.USAGE_SETTING_DATA_CENTRIC) {
            log("calculateUsageSetting: Updating usage setting for current subscription");
            currentUsageSetting = SubscriptionManager.USAGE_SETTING_DEFAULT;
        }

        // Range check the inputs, and on failure, make no changes
        if (preferredUsageSetting < SubscriptionManager.USAGE_SETTING_DEFAULT
                || preferredUsageSetting > SubscriptionManager.USAGE_SETTING_DATA_CENTRIC) {
            loge("calculateUsageSetting: Invalid usage setting!" + preferredUsageSetting);
            return currentUsageSetting;
        }

        // Default is always allowed
        if (preferredUsageSetting == SubscriptionManager.USAGE_SETTING_DEFAULT) {
            return preferredUsageSetting;
        }

        // Forced setting must be explicitly supported
        for (int supportedUsageSetting : supportedUsageSettings) {
            if (preferredUsageSetting == supportedUsageSetting) return preferredUsageSetting;
        }

        // If the preferred setting is not possible, just keep the current setting.
        return currentUsageSetting;
    }

    /**
     * Called by CarrierConfigLoader to update the subscription before sending a broadcast.
     */
    public void updateSubscriptionByCarrierConfig(int phoneId, @NonNull String configPackageName,
            @NonNull PersistableBundle config, @NonNull Runnable callback) {
        mHandler.post(() -> {
            updateSubscriptionByCarrierConfigInternal(phoneId, configPackageName, config);
            callback.run();
        });
    }

    private void updateSubscriptionByCarrierConfigInternal(int phoneId,
            @NonNull String configPackageName, @NonNull PersistableBundle config) {
        log("updateSubscriptionByCarrierConfig: phoneId=" + phoneId + ", configPackageName="
                + configPackageName);
        if (!SubscriptionManager.isValidPhoneId(phoneId)
                || TextUtils.isEmpty(configPackageName) || config == null) {
            loge("updateSubscriptionByCarrierConfig: Failed to update the subscription. phoneId="
                    + phoneId + " configPackageName=" + configPackageName + " config="
                        + ((config == null) ? "null" : config.hashCode()));
            return;
        }

        if (!mSlotIndexToSubId.containsKey(phoneId)) {
            log("updateSubscriptionByCarrierConfig: No subscription is active for phone being "
                    + "updated.");
            return;
        }

        int subId = mSlotIndexToSubId.get(phoneId);

        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                .getSubscriptionInfoInternal(subId);
        if (subInfo == null) {
            loge("updateSubscriptionByCarrierConfig: Couldn't retrieve subscription info for "
                    + "current subscription. subId=" + subId);
            return;
        }

        ParcelUuid groupUuid;

        // carrier certificates are not subscription-specific, so we want to load them even if
        // this current package is not a CarrierServicePackage
        String[] certs = config.getStringArray(
                CarrierConfigManager.KEY_CARRIER_CERTIFICATE_STRING_ARRAY);
        UiccAccessRule[] carrierConfigAccessRules = UiccAccessRule.decodeRulesFromCarrierConfig(
                certs);
        if (carrierConfigAccessRules != null) {
            mSubscriptionDatabaseManager.setCarrierConfigAccessRules(
                    subId, carrierConfigAccessRules);
        }

        boolean isOpportunistic = config.getBoolean(
                CarrierConfigManager.KEY_IS_OPPORTUNISTIC_SUBSCRIPTION_BOOL,
                subInfo.isOpportunistic());
        mSubscriptionDatabaseManager.setOpportunistic(subId, isOpportunistic);

        String groupUuidString = config.getString(
                CarrierConfigManager.KEY_SUBSCRIPTION_GROUP_UUID_STRING, "");
        String oldGroupUuidString = subInfo.getGroupUuid();
        if (!TextUtils.isEmpty(groupUuidString)) {
            try {
                // Update via a UUID Structure to ensure consistent formatting
                groupUuid = ParcelUuid.fromString(groupUuidString);
                if (groupUuidString.equals(CarrierConfigManager.REMOVE_GROUP_UUID_STRING)) {
                    // Remove the group UUID.
                    mSubscriptionDatabaseManager.setGroupUuid(subId, "");
                } else if (canPackageManageGroup(groupUuid, configPackageName)) {
                    mSubscriptionDatabaseManager.setGroupUuid(subId, groupUuidString);
                    mSubscriptionDatabaseManager.setGroupOwner(subId, configPackageName);
                    log("updateSubscriptionByCarrierConfig: Group added for sub " + subId);
                } else {
                    loge("updateSubscriptionByCarrierConfig: configPackageName "
                            + configPackageName + " doesn't own groupUuid " + groupUuid);
                }

                if (!groupUuidString.equals(oldGroupUuidString)) {
                    MultiSimSettingController.getInstance()
                            .notifySubscriptionGroupChanged(groupUuid);
                }
            } catch (IllegalArgumentException e) {
                loge("updateSubscriptionByCarrierConfig: Invalid Group UUID="
                        + groupUuidString);
            }
        }

        updateGroupDisabled();

        final int preferredUsageSetting = config.getInt(
                CarrierConfigManager.KEY_CELLULAR_USAGE_SETTING_INT,
                SubscriptionManager.USAGE_SETTING_UNKNOWN);

        int newUsageSetting = calculateUsageSetting(
                subInfo.getUsageSetting(), preferredUsageSetting);

        if (newUsageSetting != subInfo.getUsageSetting()) {
            mSubscriptionDatabaseManager.setUsageSetting(subId, newUsageSetting);
            log("updateSubscriptionByCarrierConfig: UsageSetting changed,"
                    + " oldSetting=" + SubscriptionManager.usageSettingToString(
                            subInfo.getUsageSetting())
                    + " preferredSetting=" + SubscriptionManager.usageSettingToString(
                            preferredUsageSetting)
                    + " newSetting=" + SubscriptionManager.usageSettingToString(newUsageSetting));
        }

        final int[] servicesFromCarrierConfig =
                config.getIntArray(
                        CarrierConfigManager.KEY_CELLULAR_SERVICE_CAPABILITIES_INT_ARRAY);
        int serviceBitmasks = 0;
        boolean allServicesAreValid = true;
        // Check if all services from carrier config are valid before setting to db
        if (servicesFromCarrierConfig == null) {
            allServicesAreValid = false;
        } else {
            for (int service : servicesFromCarrierConfig) {
                if (service < SubscriptionManager.SERVICE_CAPABILITY_VOICE
                        || service > SubscriptionManager.SERVICE_CAPABILITY_MAX) {
                    allServicesAreValid = false;
                    break;
                } else {
                    serviceBitmasks |= SubscriptionManager.serviceCapabilityToBitmask(service);
                }
            }
        }
        // In case we get invalid service override, fall back to default value.
        // DO NOT throw exception which will crash phone process.
        if (!allServicesAreValid) {
            serviceBitmasks = SubscriptionManager.getAllServiceCapabilityBitmasks();
        }

        if (serviceBitmasks != subInfo.getServiceCapabilities()) {
            log("updateSubscriptionByCarrierConfig: serviceCapabilities updated from "
                    + subInfo.getServiceCapabilities() + " to " + serviceBitmasks);
            mSubscriptionDatabaseManager.setServiceCapabilities(subId, serviceBitmasks);
        }

        if (mFeatureFlags.enableIsPrivateNetworkApi()) {
            boolean isPrivateNetwork = PRIVATE_NETWORK_MCC.equals(subInfo.getMcc()) || config
                    .getBoolean(CarrierConfigManager.KEY_IS_PRIVATE_NETWORK_BOOL, false);
            mSubscriptionDatabaseManager.setIsPrivateNetwork(subId, isPrivateNetwork ? 1 : 0);
        }
    }

    /**
     * Get all subscription info records from SIMs visible to the calling user that are inserted now
     * or previously inserted.
     *
     * <p>
     * If the caller does not have {@link Manifest.permission#READ_PHONE_NUMBERS} permission,
     * {@link SubscriptionInfo#getNumber()} will return empty string.
     * If the caller does not have {@link Manifest.permission#USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER},
     * {@link SubscriptionInfo#getIccId()} and {@link SubscriptionInfo#getCardString()} will return
     * empty string, and {@link SubscriptionInfo#getGroupUuid()} will return {@code null}.
     *
     * <p>
     * The carrier app will only get the list of subscriptions that it has carrier privilege on,
     * but will have non-stripped {@link SubscriptionInfo} in the list.
     *
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return List of all {@link SubscriptionInfo} records from SIMs that are inserted or
     * previously inserted. Sorted by {@link SubscriptionInfo#getSimSlotIndex()}, then
     * {@link SubscriptionInfo#getSubscriptionId()}.
     *
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @NonNull
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public List<SubscriptionInfo> getAllSubInfoList(@NonNull String callingPackage,
            @Nullable String callingFeatureId) {
        // Check if the caller has READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or carrier
        // privilege on any active subscription. The carrier app will get full subscription infos
        // on the subs it has carrier privilege.
        if (!TelephonyPermissions.checkReadPhoneStateOnAnyActiveSub(mContext,
                Binder.getCallingPid(), Binder.getCallingUid(), callingPackage, callingFeatureId,
                "getAllSubInfoList")) {
            throw new SecurityException("Need READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or "
                    + "carrier privilege");
        }

        if (!mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(callingPackage, "getAllSubInfoList");
        }

        return getSubscriptionInfoStreamAsUser(BINDER_WRAPPER.getCallingUserHandle())
                // callers have READ_PHONE_STATE or READ_PRIVILEGED_PHONE_STATE can get a full
                // list. Carrier apps can only get the subscriptions they have privileged.
                .filter(subInfo -> TelephonyPermissions.checkCallingOrSelfReadPhoneStateNoThrow(
                        mContext, subInfo.getSubscriptionId(), callingPackage, callingFeatureId,
                        "getAllSubInfoList"))
                // Remove the identifier if the caller does not have sufficient permission.
                // carrier apps will get full subscription info on the subscriptions associated
                // to them.
                .map(subInfo -> conditionallyRemoveIdentifiers(subInfo.toSubscriptionInfo(),
                        callingPackage, callingFeatureId, "getAllSubInfoList"))
                .sorted(Comparator.comparing(SubscriptionInfo::getSimSlotIndex)
                        .thenComparing(SubscriptionInfo::getSubscriptionId))
                .collect(Collectors.toList());
    }

    /**
     * Get the active {@link SubscriptionInfo} with the subscription id key.
     *
     * @param subId The unique {@link SubscriptionInfo} key in database
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return The subscription info.
     *
     * @throws SecurityException if the caller does not have required permissions.
     */
    @Override
    @Nullable
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public SubscriptionInfo getActiveSubscriptionInfo(int subId, @NonNull String callingPackage,
            @Nullable String callingFeatureId) {
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(mContext, subId, callingPackage,
                callingFeatureId, "getActiveSubscriptionInfo")) {
            throw new SecurityException("Need READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or "
                    + "carrier privilege");
        }

        if (!mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(callingPackage, "getActiveSubscriptionInfo");
        }

        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                .getSubscriptionInfoInternal(subId);
        if (subInfo != null && subInfo.isActive()) {
            return conditionallyRemoveIdentifiers(subInfo.toSubscriptionInfo(), callingPackage,
                    callingFeatureId, "getActiveSubscriptionInfo");
        }
        return null;
    }

    /**
     * Get the active {@link SubscriptionInfo} associated with the iccId.
     *
     * @param iccId the IccId of SIM card
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return The subscription info.
     *
     * @throws SecurityException if the caller does not have required permissions.
     */
    @Override
    @Nullable
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public SubscriptionInfo getActiveSubscriptionInfoForIccId(@NonNull String iccId,
            @NonNull String callingPackage, @Nullable String callingFeatureId) {
        enforcePermissions("getActiveSubscriptionInfoForIccId",
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);

        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(callingPackage,
                    "getActiveSubscriptionInfoForIccId");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            iccId = getStrippedIccid(iccId);
            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternalByIccId(iccId);

            return (subInfo != null && subInfo.isActive()) ? subInfo.toSubscriptionInfo() : null;

        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Get the active {@link SubscriptionInfo} associated with the logical SIM slot index.
     *
     * @param slotIndex the logical SIM slot index which the subscription is inserted.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return {@link SubscriptionInfo}, null for Remote-SIMs or non-active logical SIM slot index.
     *
     * @throws SecurityException if the caller does not have required permissions.
     */
    @Override
    @Nullable
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIndex,
            @NonNull String callingPackage, @Nullable String callingFeatureId) {
        int subId = mSlotIndexToSubId.getOrDefault(slotIndex,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(mContext, subId,
                callingPackage, callingFeatureId,
                "getActiveSubscriptionInfoForSimSlotIndex")) {
            throw new SecurityException("Need READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or "
                    + "carrier privilege");

        }

        enforceTelephonyFeatureWithException(callingPackage,
                "getActiveSubscriptionInfoForSimSlotIndex");

        if (!SubscriptionManager.isValidSlotIndex(slotIndex)) {
            throw new IllegalArgumentException("Invalid slot index " + slotIndex);
        }

        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                .getSubscriptionInfoInternal(subId);
        if (subInfo != null && subInfo.isActive()) {
            return conditionallyRemoveIdentifiers(subInfo.toSubscriptionInfo(), callingPackage,
                    callingFeatureId, "getActiveSubscriptionInfoForSimSlotIndex");
        }

        return null;
    }

    /**
     * Get the SubscriptionInfo(s) of the active subscriptions for calling user. The records will be
     * sorted by {@link SubscriptionInfo#getSimSlotIndex} then by
     * {@link SubscriptionInfo#getSubscriptionId}.
     *
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @param isForAllProfiles whether the caller intends to see all subscriptions regardless
     *                      association.
     *
     * @return Sorted list of the currently {@link SubscriptionInfo} records available on the
     * device.
     */
    @Override
    @NonNull
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public List<SubscriptionInfo> getActiveSubscriptionInfoList(@NonNull String callingPackage,
            @Nullable String callingFeatureId, boolean isForAllProfiles) {
        // Check if the caller has READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or carrier
        // privilege on any active subscription. The carrier app will get full subscription infos
        // on the subs it has carrier privilege.
        if (!TelephonyPermissions.checkReadPhoneStateOnAnyActiveSub(mContext,
                Binder.getCallingPid(), Binder.getCallingUid(), callingPackage, callingFeatureId,
                "getActiveSubscriptionInfoList")) {
            // Ideally we should avoid silent failure, but since this API has already been used by
            // many apps and they do not expect the security exception, we return an empty list
            // here so it's consistent with pre-U behavior.
            loge("getActiveSubscriptionInfoList: " + callingPackage + " does not have enough "
                    + "permission. Returning empty list here.");
            return Collections.emptyList();
        }

        if (!mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(callingPackage, "getActiveSubscriptionInfoList");
        }

        if (isForAllProfiles) {
            enforcePermissionAccessAllUserProfiles();
        }
        return getSubscriptionInfoStreamAsUser(isForAllProfiles
                ? UserHandle.ALL : BINDER_WRAPPER.getCallingUserHandle())
                .filter(SubscriptionInfoInternal::isActive)
                // Remove the identifier if the caller does not have sufficient permission.
                // carrier apps will get full subscription info on the subscriptions associated
                // to them.
                .map(subInfo -> conditionallyRemoveIdentifiers(subInfo.toSubscriptionInfo(),
                        callingPackage, callingFeatureId, "getActiveSubscriptionInfoList"))
                .sorted(Comparator.comparing(SubscriptionInfo::getSimSlotIndex)
                        .thenComparing(SubscriptionInfo::getSubscriptionId))
                .collect(Collectors.toList());
    }

    /**
     * Get the number of active {@link SubscriptionInfo}.
     *
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @param isForAllProfiles whether the caller intends to see all subscriptions regardless
     *                        association.
     *
     * @return the number of active subscriptions.
     *
     * @throws SecurityException if the caller does not have required permissions.
     */
    @Override
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public int getActiveSubInfoCount(@NonNull String callingPackage,
            @Nullable String callingFeatureId, boolean isForAllProfiles) {
        if (!TelephonyPermissions.checkReadPhoneStateOnAnyActiveSub(mContext,
                Binder.getCallingPid(), Binder.getCallingUid(), callingPackage, callingFeatureId,
                "getAllSubInfoList")) {
            throw new SecurityException("Need READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or "
                    + "carrier privilege");
        }
        if (isForAllProfiles) {
            enforcePermissionAccessAllUserProfiles();
        }

        enforceTelephonyFeatureWithException(callingPackage, "getActiveSubInfoCount");

        return getActiveSubIdListAsUser(false, isForAllProfiles
                ? UserHandle.ALL : BINDER_WRAPPER.getCallingUserHandle()).length;
    }

    /** @throws SecurityException if caller doesn't have one of the requested permissions. */
    private void enforcePermissionAccessAllUserProfiles() {
        if (!mFeatureFlags.enforceSubscriptionUserFilter()) return;
        enforcePermissions("To access across profiles",
                Manifest.permission.INTERACT_ACROSS_USERS,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                Manifest.permission.INTERACT_ACROSS_PROFILES);
    }

    /**
     * @return the maximum number of subscriptions this device will support at any one time.
     */
    @Override
    public int getActiveSubInfoCountMax() {
        return mTelephonyManager.getActiveModemCount();
    }

    /**
     * Gets the SubscriptionInfo(s) of all available subscriptions, if any.
     *
     * Available subscriptions include active ones (those with a non-negative
     * {@link SubscriptionInfo#getSimSlotIndex()}) as well as inactive but installed embedded
     * subscriptions.
     *
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return The available subscription info.
     *
     * @throws SecurityException if the caller does not have required permissions.
     */
    @Override
    @NonNull
    public List<SubscriptionInfo> getAvailableSubscriptionInfoList(@NonNull String callingPackage,
            @Nullable String callingFeatureId) {
        enforcePermissions("getAvailableSubscriptionInfoList",
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);

        if (!mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(callingPackage,
                    "getAvailableSubscriptionInfoList");
        }

        return getAvailableSubscriptionsInternalStream()
                .sorted(Comparator.comparing(SubscriptionInfoInternal::getSimSlotIndex)
                        .thenComparing(SubscriptionInfoInternal::getSubscriptionId))
                .map(SubscriptionInfoInternal::toSubscriptionInfo)
                .collect(Collectors.toList());

    }

    /**
     * @return all the subscriptions visible to user on the device.
     */
    private Stream<SubscriptionInfoInternal> getAvailableSubscriptionsInternalStream() {
        // Available eSIM profiles are reported by EuiccManager. However for physical SIMs if
        // they are in inactive slot or programmatically disabled, they are still considered
        // available. In this case we get their iccid from slot info and include their
        // subscriptionInfos.
        List<String> iccIds = getIccIdsOfInsertedPhysicalSims();

        return mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                .filter(subInfo -> subInfo.isActive() || iccIds.contains(subInfo.getIccId())
                        || (mEuiccManager != null && mEuiccManager.isEnabled()
                        && subInfo.isEmbedded()));
    }

    /**
     * Tracks for each user Id, a list of subscriptions associated with it.
     * A profile is barred from seeing unassociated subscriptions if it has its own subscription
     * which is available to choose from the device.
     */
    private void updateUserIdToAvailableSubs() {
        mUserIdToAvailableSubs = getAvailableSubscriptionsInternalStream()
                .collect(Collectors.groupingBy(
                        SubscriptionInfoInternal::getUserId,
                        Collectors.mapping(SubscriptionInfoInternal::getSubscriptionId,
                                Collectors.toList())));
        log("updateUserIdToAvailableSubs: " + mUserIdToAvailableSubs);
    }

    /**
     * Gets the SubscriptionInfo(s) of all embedded subscriptions accessible to the calling app, if
     * any.
     *
     * <p>Only those subscriptions for which the calling app has carrier privileges per the
     * subscription metadata, if any, will be included in the returned list.
     *
     * <p>The records will be sorted by {@link SubscriptionInfo#getSimSlotIndex} then by
     * {@link SubscriptionInfo#getSubscriptionId}.
     *
     * @return Sorted list of the current embedded {@link SubscriptionInfo} records available on the
     * device which are accessible to the caller.
     * <ul>
     * <li>
     *
     * if the list is non-empty the list is sorted by {@link SubscriptionInfo#getSimSlotIndex}
     * then by {@link SubscriptionInfo#getSubscriptionId}.
     * </ul>
     *
     * @param callingPackage The package making the call.
     *
     * @throws SecurityException if the caller does not have required permissions.
     */
    @Override
    public List<SubscriptionInfo> getAccessibleSubscriptionInfoList(
            @NonNull String callingPackage) {
        if (mEuiccManager == null || !mEuiccManager.isEnabled()) {
            return null;
        }

        // Verify that the callingPackage belongs to the calling UID
        mAppOpsManager.checkPackage(Binder.getCallingUid(), callingPackage);
        return getSubscriptionInfoStreamAsUser(BINDER_WRAPPER.getCallingUserHandle())
                .map(SubscriptionInfoInternal::toSubscriptionInfo)
                .filter(subInfo -> subInfo.isEmbedded()
                        && canManageSubscription(subInfo, callingPackage))
                .sorted(Comparator.comparing(SubscriptionInfo::getSimSlotIndex)
                        .thenComparing(SubscriptionInfo::getSubscriptionId))
                .collect(Collectors.toList());
    }

    /**
     * @see SubscriptionManager#requestEmbeddedSubscriptionInfoListRefresh
     */
    @Override
    public void requestEmbeddedSubscriptionInfoListRefresh(int cardId) {
        enforcePermissions("requestEmbeddedSubscriptionInfoListRefresh",
                Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS);
        updateEmbeddedSubscriptions(List.of(cardId), null);
    }

    /**
     * Add a new subscription info record, if needed. This should be only used for remote SIM.
     *
     * @param iccId ICCID of the SIM card.
     * @param displayName human-readable name of the device the subscription corresponds to.
     * @param slotIndex the logical SIM slot index assigned to this device.
     * @param subscriptionType the type of subscription to be added
     *
     * @return 0 if success, < 0 on error
     *
     * @throws SecurityException if the caller does not have required permissions.
     * @throws IllegalArgumentException if {@code slotIndex} is invalid.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int addSubInfo(@NonNull String iccId, @NonNull String displayName, int slotIndex,
            @SubscriptionType int subscriptionType) {
        enforcePermissions("addSubInfo", Manifest.permission.MODIFY_PHONE_STATE);
        logl("addSubInfo: iccId=" + SubscriptionInfo.getPrintableId(iccId) + ", slotIndex="
                + slotIndex + ", displayName=" + displayName + ", type="
                + SubscriptionManager.subscriptionTypeToString(subscriptionType) + ", "
                + getCallingPackage());

        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(getCurrentPackageName(), "addSubInfo");
        }

        if (subscriptionType == SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM) {
            if (!SubscriptionManager.isValidSlotIndex(slotIndex)) {
                throw new IllegalArgumentException("Invalid slot index " + slotIndex
                        + " for local SIM");
            }
        } else if (subscriptionType == SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM) {
            // All SIM subscriptions – local and remote – are stored in the same database, so remote
            // SIMs must be assigned a SIM slot index. They are distinguished through a subscription
            // type column.
            slotIndex = SubscriptionManager.SLOT_INDEX_FOR_REMOTE_SIM_SUB;
        } else {
            throw new IllegalArgumentException("Invalid subscription type " + subscriptionType);
        }

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            if (TextUtils.isEmpty(iccId)) {
                loge("addSubInfo: null or empty iccId");
                return -1;
            }

            iccId = getStrippedIccid(iccId);
            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternalByIccId(iccId);

            // Check if the record exists or not.
            if (subInfo == null) {
                // Record does not exist.
                if (subscriptionType == SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM
                        && mSlotIndexToSubId.containsKey(slotIndex)) {
                    loge("Already a subscription on slot " + slotIndex);
                    return -1;
                }

                int subId = insertSubscriptionInfo(iccId, slotIndex, displayName, subscriptionType);
                updateGroupDisabled();
                if (mFeatureFlags.remoteSimSubIdSet()
                        && subscriptionType == SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM) {
                    mRemoteSubIds.add(subId);
                } else {
                    mSlotIndexToSubId.put(slotIndex, subId);
                }
                logl("addSubInfo: current mapping " + slotMappingToString());
            } else {
                // Record already exists.
                loge("Subscription record already existed.");
                return -1;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return 0;
    }

    /**
     * Remove subscription info record from the subscription database.
     *
     * @param uniqueId This is the unique identifier for the subscription within the specific
     * subscription type.
     * @param subscriptionType the type of subscription to be removed.
     *
     * @return {@code true} if succeeded, otherwise {@code false}.
     *
     * @throws NullPointerException if {@code uniqueId} is {@code null}.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public boolean removeSubInfo(@NonNull String uniqueId, int subscriptionType) {
        enforcePermissions("removeSubInfo", Manifest.permission.MODIFY_PHONE_STATE);

        logl("removeSubInfo: uniqueId=" + SubscriptionInfo.getPrintableId(uniqueId) + ", "
                + SubscriptionManager.subscriptionTypeToString(subscriptionType) + ", "
                + getCallingPackage());

        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(getCurrentPackageName(), "removeSubInfo");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternalByIccId(uniqueId);
            if (subInfo == null) {
                loge("Cannot find subscription with uniqueId " + uniqueId);
                return false;
            }
            if (subInfo.getSubscriptionType() != subscriptionType) {
                loge("The subscription type does not match.");
                return false;
            }
            if (mFeatureFlags.remoteSimSubIdSet()
                    && subscriptionType == SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM) {
                mRemoteSubIds.remove(subInfo.getSubscriptionId());
            } else {
                mSlotIndexToSubId.remove(subInfo.getSimSlotIndex());
            }
            mSubscriptionDatabaseManager.removeSubscriptionInfo(subInfo.getSubscriptionId());
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set SIM icon tint color by simInfo index.
     *
     * @param subId the unique subscription index in database
     * @param tint the icon tint color of the SIM
     *
     * @return the number of records updated
     *
     * @throws IllegalArgumentException if {@code subId} is invalid or the subscription does not
     * exist.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int setIconTint(int subId, @ColorInt int tint) {
        enforcePermissions("setIconTint", Manifest.permission.MODIFY_PHONE_STATE);

        final long identity = Binder.clearCallingIdentity();
        try {
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                throw new IllegalArgumentException("Invalid sub id passed as parameter");
            }

            mSubscriptionDatabaseManager.setIconTint(subId, tint);
            return 1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set display name of a subscription.
     *
     * @param displayName The display name of SIM card.
     * @param subId The subscription id.
     * @param nameSource The display name source.
     *
     * @return the number of records updated
     *
     * @throws IllegalArgumentException if {@code nameSource} is invalid, or {@code subId} is
     * invalid.
     * @throws NullPointerException if {@code displayName} is {@code null}.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int setDisplayNameUsingSrc(@NonNull String displayName, int subId,
            @SimDisplayNameSource int nameSource) {
        enforcePermissions("setDisplayNameUsingSrc", Manifest.permission.MODIFY_PHONE_STATE);

        String callingPackage = getCallingPackage();
        final long identity = Binder.clearCallingIdentity();
        try {
            Objects.requireNonNull(displayName, "setDisplayNameUsingSrc");

            if (nameSource < SubscriptionManager.NAME_SOURCE_CARRIER_ID
                    || nameSource > SubscriptionManager.NAME_SOURCE_SIM_PNN) {
                throw new IllegalArgumentException("illegal name source " + nameSource);
            }

            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternal(subId);

            if (subInfo == null) {
                throw new IllegalArgumentException("Cannot find subscription info with sub id "
                        + subId);
            }

            if (getNameSourcePriority(subInfo.getDisplayNameSource())
                    > getNameSourcePriority(nameSource)
                    || (getNameSourcePriority(subInfo.getDisplayNameSource())
                    == getNameSourcePriority(nameSource))
                    && (TextUtils.equals(displayName, subInfo.getDisplayName()))) {
                log("No need to update the display name. nameSource="
                        + SubscriptionManager.displayNameSourceToString(nameSource)
                        + ", existing name=" + subInfo.getDisplayName() + ", source="
                        + SubscriptionManager.displayNameSourceToString(
                                subInfo.getDisplayNameSource()));
                return 0;
            }

            String nameToSet;
            if (TextUtils.isEmpty(displayName) || displayName.trim().length() == 0) {
                nameToSet = mTelephonyManager.getSimOperatorName(subId);
                if (TextUtils.isEmpty(nameToSet)) {
                    if (nameSource == SubscriptionManager.NAME_SOURCE_USER_INPUT
                            && SubscriptionManager.isValidSlotIndex(getSlotIndex(subId))) {
                        Resources r = Resources.getSystem();
                        nameToSet = r.getString(R.string.default_card_name,
                                (getSlotIndex(subId) + 1));
                    } else {
                        nameToSet = mContext.getString(SubscriptionManager.DEFAULT_NAME_RES);
                    }
                }
            } else {
                nameToSet = displayName;
            }

            logl("setDisplayNameUsingSrc: subId=" + subId + ", name=" + nameToSet
                    + ", nameSource=" + SubscriptionManager.displayNameSourceToString(nameSource)
                    + ", calling package=" + callingPackage);
            mSubscriptionDatabaseManager.setDisplayName(subId, nameToSet);
            mSubscriptionDatabaseManager.setDisplayNameSource(subId, nameSource);

            // Update the nickname on the eUICC chip if it's an embedded subscription.
            SubscriptionInfo sub = getSubscriptionInfo(subId);
            if (sub != null && sub.isEmbedded()) {
                int cardId = sub.getCardId();
                log("Updating embedded sub nickname on cardId: " + cardId);
                mEuiccManager.updateSubscriptionNickname(subId, nameToSet,
                        // This PendingIntent simply fulfills the requirement to pass in a callback;
                        // we don't care about the result (hence 0 requestCode and no action
                        // specified on the intent).
                        PendingIntent.getService(mContext, 0 /* requestCode */, new Intent(),
                                PendingIntent.FLAG_IMMUTABLE /* flags */));
            }

            return 1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set phone number by subscription id.
     *
     * @param number the phone number of the SIM.
     * @param subId the unique SubscriptionInfo index in database.
     *
     * @return the number of records updated.
     *
     * @throws SecurityException if callers do not hold the required permission.
     * @throws NullPointerException if {@code number} is {@code null}.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int setDisplayNumber(@NonNull String number, int subId) {
        enforcePermissions("setDisplayNumber", Manifest.permission.MODIFY_PHONE_STATE);
        logl("setDisplayNumber: subId=" + subId + ", number="
                + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, number)
                + ", calling package=" + getCallingPackage());
        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            mSubscriptionDatabaseManager.setNumber(subId, number);
            return 1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set data roaming by simInfo index
     *
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubscriptionInfo index in database
     *
     * @return the number of records updated
     *
     * @throws IllegalArgumentException if {@code subId} or {@code roaming} is not valid.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int setDataRoaming(@DataRoamingMode int roaming, int subId) {
        enforcePermissions("setDataRoaming", Manifest.permission.MODIFY_PHONE_STATE);

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            if (roaming < 0) {
                throw new IllegalArgumentException("Invalid roaming value " + roaming);
            }

            mSubscriptionDatabaseManager.setDataRoaming(subId, roaming);
            return 1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Switch to a certain subscription.
     *
     * @param opportunistic whether it’s opportunistic subscription
     * @param subId the unique SubscriptionInfo index in database
     * @param callingPackage The package making the call
     *
     * @return the number of records updated
     *
     * @throws IllegalArgumentException if {@code subId} is invalid.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_PHONE_STATE,
            "carrier privileges",
    })
    public int setOpportunistic(boolean opportunistic, int subId, @NonNull String callingPackage) {
        TelephonyPermissions.enforceAnyPermissionGrantedOrCarrierPrivileges(
                mContext, subId, Binder.getCallingUid(), true, "setOpportunistic",
                Manifest.permission.MODIFY_PHONE_STATE);

        enforceTelephonyFeatureWithException(callingPackage, "setOpportunistic");

        long token = Binder.clearCallingIdentity();
        try {
            mSubscriptionDatabaseManager.setOpportunistic(subId, opportunistic);
            return 1;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Inform SubscriptionManager that subscriptions in the list are bundled as a group. Typically
     * it's a primary subscription and an opportunistic subscription. It should only affect
     * multi-SIM scenarios where primary and opportunistic subscriptions can be activated together.
     *
     * Being in the same group means they might be activated or deactivated together, some of them
     * may be invisible to the users, etc.
     *
     * Caller will either have {@link Manifest.permission#MODIFY_PHONE_STATE} permission or
     * can manage all subscriptions in the list, according to their access rules.
     *
     * @param subIdList list of subId that will be in the same group.
     * @param callingPackage The package making the call.
     *
     * @return groupUUID a UUID assigned to the subscription group. It returns null if fails.
     *
     * @throws IllegalArgumentException if {@code subId} is invalid.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_PHONE_STATE,
            "carrier privileges",
    })
    public ParcelUuid createSubscriptionGroup(@NonNull int[] subIdList,
            @NonNull String callingPackage) {
        // Verify that the callingPackage belongs to the calling UID
        mAppOpsManager.checkPackage(Binder.getCallingUid(), callingPackage);

        Objects.requireNonNull(subIdList, "createSubscriptionGroup");
        if (subIdList.length == 0) {
            throw new IllegalArgumentException("Invalid subIdList " + Arrays.toString(subIdList));
        }

        // If it doesn't have modify phone state permission, or carrier privilege permission,
        // a SecurityException will be thrown.
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED && !checkCarrierPrivilegeOnSubList(
                        subIdList, callingPackage)) {
            throw new SecurityException("CreateSubscriptionGroup needs MODIFY_PHONE_STATE or"
                    + " carrier privilege permission on all specified subscriptions");
        }

        enforceTelephonyFeatureWithException(callingPackage, "createSubscriptionGroup");

        long identity = Binder.clearCallingIdentity();

        try {
            // Generate a UUID.
            ParcelUuid groupUUID = new ParcelUuid(UUID.randomUUID());
            String uuidString = groupUUID.toString();

            for (int subId : subIdList) {
                mSubscriptionDatabaseManager.setGroupUuid(subId, uuidString);
                mSubscriptionDatabaseManager.setGroupOwner(subId, callingPackage);
            }
            updateGroupDisabled();

            MultiSimSettingController.getInstance().notifySubscriptionGroupChanged(groupUUID);
            return groupUUID;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set which subscription is preferred for cellular data. It's designed to overwrite default
     * data subscription temporarily.
     *
     * @param subId which subscription is preferred to for cellular data
     * @param needValidation whether validation is needed before switching
     * @param callback callback upon request completion
     *
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setPreferredDataSubscriptionId(int subId, boolean needValidation,
            @Nullable ISetOpportunisticDataCallback callback) {
        enforcePermissions("setPreferredDataSubscriptionId",
                Manifest.permission.MODIFY_PHONE_STATE);

        enforceTelephonyFeatureWithException(getCurrentPackageName(),
                "setPreferredDataSubscriptionId");

        final long token = Binder.clearCallingIdentity();

        try {
            PhoneSwitcher phoneSwitcher = PhoneSwitcher.getInstance();
            if (phoneSwitcher == null) {
                loge("Set preferred data sub: phoneSwitcher is null.");
                if (callback != null) {
                    try {
                        callback.onComplete(
                                TelephonyManager.SET_OPPORTUNISTIC_SUB_REMOTE_SERVICE_EXCEPTION);
                    } catch (RemoteException exception) {
                        loge("RemoteException " + exception);
                    }
                }
                return;
            }

            phoneSwitcher.trySetOpportunisticDataSubscription(subId, needValidation, callback);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * @return The subscription id of preferred subscription for cellular data. This reflects
     * the active modem which can serve large amount of cellular data.
     *
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getPreferredDataSubscriptionId() {
        enforcePermissions("getPreferredDataSubscriptionId",
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        final long token = Binder.clearCallingIdentity();

        try {
            PhoneSwitcher phoneSwitcher = PhoneSwitcher.getInstance();
            if (phoneSwitcher == null) {
                loge("getPreferredDataSubscriptionId: PhoneSwitcher not available. Return the "
                        + "default data sub " + getDefaultDataSubId());
                return getDefaultDataSubId();
            }

            return phoneSwitcher.getAutoSelectedDataSubId();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Get the opportunistic subscriptions.
     *
     * Callers with {@link Manifest.permission#READ_PHONE_STATE} or
     * {@link Manifest.permission#READ_PRIVILEGED_PHONE_STATE} will have a full list of
     * opportunistic subscriptions. Subscriptions that the carrier app has no privilege will be
     * excluded from the list.
     *
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return The list of opportunistic subscription info that can be accessed by the callers.
     */
    @Override
    @NonNull
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public List<SubscriptionInfo> getOpportunisticSubscriptions(@NonNull String callingPackage,
            @Nullable String callingFeatureId) {
        // Check if the caller has READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or carrier
        // privilege on any active subscription. The carrier app will get full subscription infos
        // on the subs it has carrier privilege.
        if (!TelephonyPermissions.checkReadPhoneStateOnAnyActiveSub(mContext,
                Binder.getCallingPid(), Binder.getCallingUid(), callingPackage, callingFeatureId,
                "getOpportunisticSubscriptions")) {
            // Ideally we should avoid silent failure, but since this API has already been used by
            // many apps and they do not expect the security exception, we return an empty list
            // here so it's consistent with pre-U behavior.
            loge("getOpportunisticSubscriptions: " + callingPackage + " does not have enough "
                    + "permission. Returning empty list here.");
            return Collections.emptyList();
        }

        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(callingPackage, "getOpportunisticSubscriptions");
        }

        return mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                // callers have READ_PHONE_STATE or READ_PRIVILEGED_PHONE_STATE can get a full
                // list. Carrier apps can only get the subscriptions they have privileged.
                .filter(subInfo -> subInfo.isOpportunistic()
                        && TelephonyPermissions.checkCallingOrSelfReadPhoneStateNoThrow(
                        mContext, subInfo.getSubscriptionId(), callingPackage,
                        callingFeatureId, "getOpportunisticSubscriptions"))
                // Remove the identifier if the caller does not have sufficient permission.
                // carrier apps will get full subscription info on the subscriptions associated
                // to them.
                .map(subInfo -> conditionallyRemoveIdentifiers(subInfo.toSubscriptionInfo(),
                        callingPackage, callingFeatureId, "getOpportunisticSubscriptions"))
                .sorted(Comparator.comparing(SubscriptionInfo::getSimSlotIndex)
                        .thenComparing(SubscriptionInfo::getSubscriptionId))
                .collect(Collectors.toList());
    }

    /**
     * Remove a list of subscriptions from their subscription group.
     *
     * @param subIdList list of subId that need removing from their groups.
     * @param groupUuid The UUID of the subscription group.
     * @param callingPackage The package making the call.
     *
     * @throws SecurityException if the caller doesn't meet the requirements outlined above.
     * @throws IllegalArgumentException if the some subscriptions in the list doesn't belong the
     * specified group.
     *
     * @see SubscriptionManager#createSubscriptionGroup(List)
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void removeSubscriptionsFromGroup(@NonNull int[] subIdList,
            @NonNull ParcelUuid groupUuid, @NonNull String callingPackage) {
        // If it doesn't have modify phone state permission, or carrier privilege permission,
        // a SecurityException will be thrown. If it's due to invalid parameter or internal state,
        // it will return null.
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
                && !(checkCarrierPrivilegeOnSubList(subIdList, callingPackage)
                && canPackageManageGroup(groupUuid, callingPackage))) {
            throw new SecurityException("removeSubscriptionsFromGroup needs MODIFY_PHONE_STATE or"
                    + " carrier privilege permission on all specified subscriptions.");
        }

        Objects.requireNonNull(subIdList);
        Objects.requireNonNull(groupUuid);

        if (subIdList.length == 0) {
            throw new IllegalArgumentException("subIdList is empty.");
        }

        enforceTelephonyFeatureWithException(callingPackage, "removeSubscriptionsFromGroup");

        long identity = Binder.clearCallingIdentity();

        try {
            for (int subId : subIdList) {
                SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                        .getSubscriptionInfoInternal(subId);
                if (subInfo == null) {
                    throw new IllegalArgumentException("The provided sub id " + subId
                            + " is not valid.");
                }
                if (!groupUuid.toString().equals(subInfo.getGroupUuid())) {
                    throw new IllegalArgumentException("Subscription " + subInfo.getSubscriptionId()
                            + " doesn't belong to group " + groupUuid);
                }
            }

            for (SubscriptionInfoInternal subInfo :
                    mSubscriptionDatabaseManager.getAllSubscriptions()) {
                if (IntStream.of(subIdList).anyMatch(
                        subId -> subId == subInfo.getSubscriptionId())) {
                    mSubscriptionDatabaseManager.setGroupUuid(subInfo.getSubscriptionId(), "");
                    mSubscriptionDatabaseManager.setGroupOwner(subInfo.getSubscriptionId(), "");
                } else if (subInfo.getGroupUuid().equals(groupUuid.toString())) {
                    // Pre-T behavior. If there are still subscriptions having the same UUID, update
                    // to the new owner.
                    mSubscriptionDatabaseManager.setGroupOwner(
                            subInfo.getSubscriptionId(), callingPackage);
                }
            }

            updateGroupDisabled();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Add a list of subscriptions into a group.
     *
     * Caller should either have {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission or had carrier privilege permission on the subscriptions.
     *
     * @param subIdList list of subId that need adding into the group
     * @param groupUuid the groupUuid the subscriptions are being added to.
     * @param callingPackage The package making the call.
     *
     * @throws SecurityException if the caller doesn't meet the requirements outlined above.
     * @throws IllegalArgumentException if the some subscriptions in the list doesn't exist.
     *
     * @see SubscriptionManager#createSubscriptionGroup(List)
     */
    @Override
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_PHONE_STATE,
            "carrier privileges",
    })
    public void addSubscriptionsIntoGroup(@NonNull int[] subIdList, @NonNull ParcelUuid groupUuid,
            @NonNull String callingPackage) {
        Objects.requireNonNull(subIdList, "subIdList");
        if (subIdList.length == 0) {
            throw new IllegalArgumentException("Invalid subId list");
        }

        Objects.requireNonNull(groupUuid, "groupUuid");
        String groupUuidString = groupUuid.toString();
        if (groupUuidString.equals(CarrierConfigManager.REMOVE_GROUP_UUID_STRING)) {
            throw new IllegalArgumentException("Invalid groupUuid");
        }

        // Verify that the callingPackage belongs to the calling UID
        mAppOpsManager.checkPackage(Binder.getCallingUid(), callingPackage);

        // If it doesn't have modify phone state permission, or carrier privilege permission,
        // a SecurityException will be thrown.
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
                && !(checkCarrierPrivilegeOnSubList(subIdList, callingPackage)
                && canPackageManageGroup(groupUuid, callingPackage))) {
            throw new SecurityException("Requires MODIFY_PHONE_STATE or carrier privilege"
                    + " permissions on subscriptions and the group.");
        }

        enforceTelephonyFeatureWithException(callingPackage, "addSubscriptionsIntoGroup");

        long identity = Binder.clearCallingIdentity();

        try {
            for (int subId : subIdList) {
                mSubscriptionDatabaseManager.setGroupUuid(subId, groupUuidString);
                mSubscriptionDatabaseManager.setGroupOwner(subId, callingPackage);
            }

            updateGroupDisabled();
            MultiSimSettingController.getInstance().notifySubscriptionGroupChanged(groupUuid);
            logl("addSubscriptionsIntoGroup: add subs " + Arrays.toString(subIdList)
                    + " to the group.");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Get subscriptionInfo list of subscriptions that are in the same group of given subId.
     * See {@link #createSubscriptionGroup(int[], String)} for more details.
     *
     * Caller must have {@link android.Manifest.permission#READ_PHONE_STATE}
     * or carrier privilege permission on the subscription.
     *
     * <p>Starting with API level 33, the caller also needs permission to access device identifiers
     * to get the list of subscriptions associated with a group UUID.
     * This method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the app has carrier privilege permission.
     *     {@link TelephonyManager#hasCarrierPrivileges()}
     *     <li>If the app has {@link android.Manifest.permission#READ_PHONE_STATE} permission and
     *     access to device identifiers.
     * </ul>
     *
     * @param groupUuid of which list of subInfo will be returned.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return List of {@link SubscriptionInfo} that belong to the same group, including the given
     * subscription itself. It will return an empty list if no subscription belongs to the group.
     *
     * @throws SecurityException if the caller doesn't meet the requirements outlined above.
     */
    @Override
    @NonNull
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public List<SubscriptionInfo> getSubscriptionsInGroup(@NonNull ParcelUuid groupUuid,
            @NonNull String callingPackage, @Nullable String callingFeatureId) {
        // If the calling app neither has carrier privileges nor READ_PHONE_STATE and access to
        // device identifiers, it will throw a SecurityException.
        if (CompatChanges.isChangeEnabled(REQUIRE_DEVICE_IDENTIFIERS_FOR_GROUP_UUID,
                Binder.getCallingUid())) {
            try {
                if (!TelephonyPermissions.checkCallingOrSelfReadDeviceIdentifiers(mContext,
                        callingPackage, callingFeatureId, "getSubscriptionsInGroup")) {
                    EventLog.writeEvent(0x534e4554, "213902861", Binder.getCallingUid());
                    throw new SecurityException("Need to have carrier privileges or access to "
                            + "device identifiers to call getSubscriptionsInGroup");
                }
            } catch (SecurityException e) {
                EventLog.writeEvent(0x534e4554, "213902861", Binder.getCallingUid());
                throw e;
            }
        }

        enforceTelephonyFeatureWithException(callingPackage, "getSubscriptionsInGroup");

        return mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                .map(SubscriptionInfoInternal::toSubscriptionInfo)
                .filter(info -> groupUuid.equals(info.getGroupUuid())
                        && (canManageSubscription(info, callingPackage)
                        || TelephonyPermissions.checkCallingOrSelfReadPhoneStateNoThrow(
                                mContext, info.getSubscriptionId(), callingPackage,
                        callingFeatureId, "getSubscriptionsInGroup")))
                .map(subscriptionInfo -> conditionallyRemoveIdentifiers(subscriptionInfo,
                        callingPackage, callingFeatureId, "getSubscriptionsInGroup"))
                .collect(Collectors.toList());
    }

    /**
     * Get slot index associated with the subscription.
     *
     * @param subId The subscription id.
     *
     * @return Logical slot index (i.e. phone id) as a positive integer or
     * {@link SubscriptionManager#INVALID_SIM_SLOT_INDEX} if the supplied {@code subId} doesn't have
     * an associated slot index.
     */
    @Override
    public int getSlotIndex(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = getDefaultSubId();
        }

        if (mFeatureFlags.remoteSimSubIdSet() && mRemoteSubIds.contains(subId)) {
            return SubscriptionManager.SLOT_INDEX_FOR_REMOTE_SIM_SUB;
        }

        for (Map.Entry<Integer, Integer> entry : mSlotIndexToSubId.entrySet()) {
            if (entry.getValue() == subId) return entry.getKey();
        }

        return SubscriptionManager.INVALID_SIM_SLOT_INDEX;
    }

    /**
     * Get the subscription id for specified slot index.
     *
     * @param slotIndex Logical SIM slot index.
     * @return The subscription id. {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} if SIM is
     * absent. If slotIndex is {@link SubscriptionManager#SLOT_INDEX_FOR_REMOTE_SIM_SUB}, the last
     * inserted remote SIM subscription id will be returned.
     */
    @Override
    public int getSubId(int slotIndex) {
        if (slotIndex == SubscriptionManager.DEFAULT_SIM_SLOT_INDEX) {
            slotIndex = getSlotIndex(getDefaultSubId());
        }

        if (mFeatureFlags.remoteSimSubIdSet()) {
            if (slotIndex == SubscriptionManager.SLOT_INDEX_FOR_REMOTE_SIM_SUB) {
                // The last inserted remote SIM subscription has the largest sub ID, due to the
                // database auto-increment
                return !mRemoteSubIds.isEmpty() ? mRemoteSubIds.getLargest()
                        : SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            }
            if (!SubscriptionManager.isValidSlotIndex(slotIndex)) {
                return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            }
        } else {
            // Check that we have a valid slotIndex or the slotIndex is for a remote SIM (remote SIM
            // uses special slot index that may be invalid otherwise)
            if (slotIndex != SubscriptionManager.SLOT_INDEX_FOR_REMOTE_SIM_SUB
                    && !SubscriptionManager.isValidSlotIndex(slotIndex)) {
                return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            }
        }

        return mSlotIndexToSubId.getOrDefault(slotIndex,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    /**
     * Update default sub id.
     */
    private void updateDefaultSubId() {
        int subId;
        boolean isVoiceCapable = mTelephonyManager.isVoiceCapable();

        if (isVoiceCapable) {
            subId = getDefaultVoiceSubId();
        } else {
            subId = getDefaultDataSubId();
        }

        if (mFeatureFlags.remoteSimSubIdSet()) {
            // Check whether the subId is active
            if (!mSlotIndexToSubId.containsValue(subId) && !mRemoteSubIds.contains(subId)) {
                int[] activeLocalSubIds = getActiveLocalSubIdList();
                int[] activeRemoteSubIds = getActiveRemoteSubIdList();
                if (activeLocalSubIds.length > 0) {
                    // If the subId is not active, use the first active local subscription's subId.
                    subId = activeLocalSubIds[0];
                    log("updateDefaultSubId: First available active sub = " + subId
                            + ", type = local");
                } else if (activeRemoteSubIds.length > 0) {
                    // Otherwise, use the first active remote subscription's subId.
                    subId = activeRemoteSubIds[0];
                    log("updateDefaultSubId: First available active sub = " + subId
                            + ", type = remote");
                } else {
                    subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
                }
            }
        } else {
            // If the subId is not active, use the first active subscription's subId.
            if (!mSlotIndexToSubId.containsValue(subId)) {
                int[] activeSubIds = getActiveSubIdList(true);
                if (activeSubIds.length > 0) {
                    subId = activeSubIds[0];
                    log("updateDefaultSubId: First available active sub = " + subId);
                } else {
                    subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
                }
            }
        }

        if (mDefaultSubId.get() != subId) {
            int phoneId = getPhoneId(subId);
            logl("updateDefaultSubId: Default sub id updated from " + mDefaultSubId.get() + " to "
                    + subId + ", phoneId=" + phoneId);
            mDefaultSubId.set(subId);
            String mccMnc = mTelephonyManager.getSimOperatorNumeric(subId);
            MccTable.updateMccMncConfiguration(mContext, mccMnc);

            Intent intent = new Intent(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, phoneId, subId);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    /**
     * @return The default subscription id.
     * @deprecated Use {@link #getDefaultSubIdAsUser}.
     */
    @Override
    public int getDefaultSubId() {
        return getDefaultSubIdAsUser(BINDER_WRAPPER.getCallingUserHandle().getIdentifier());
    }

    /**
     * @param userId The given user Id to check.
     * @return The default subscription id.
     */
    @Override
    public int getDefaultSubIdAsUser(@UserIdInt int userId) {
        enforceTelephonyFeatureWithException(getCurrentPackageName(),
                "getDefaultSubIdAsUser");

        return getDefaultAsUser(userId, mDefaultSubId.get());
    }

    /**
     * Get the default subscription visible to the caller.
     * @param userId The calling user Id.
     * @param defaultValue Useful if the user owns more than one subscription.
     * @return The subscription Id default to use.
     */
    private int getDefaultAsUser(@UserIdInt int userId, int defaultValue) {
        // TODO: Not using mFlags.enforceSubscriptionUserFilter because this affects U CTS.
        if (mFeatureFlags.workProfileApiSplit()) {
            List<SubscriptionInfoInternal> subInfos =
                    getSubscriptionInfoStreamAsUser(UserHandle.of(userId))
                            .filter(SubscriptionInfoInternal::isActive)
                            .toList();
            if (subInfos.size() == 1) {
                return subInfos.get(0).getSubscriptionId();
            }
        }
        return defaultValue;
    }

    /**
     * Get phone id from the subscription id. In the implementation, the logical SIM slot index
     * is equivalent to phone id. So this method is same as {@link #getSlotIndex(int)}.
     *
     * @param subId The subscription id.
     *
     * @return The phone id.
     */
    @Override
    public int getPhoneId(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = getDefaultSubId();
        }

        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return SubscriptionManager.INVALID_PHONE_INDEX;
        }

        // slot index and phone id are equivalent in the current implementation.
        int slotIndex = getSlotIndex(subId);
        if (SubscriptionManager.isValidSlotIndex(slotIndex)) {
            return slotIndex;
        }

        return SubscriptionManager.DEFAULT_PHONE_INDEX;
    }

    /**
     * @return Subscription id of the default cellular data. This reflects the user's default data
     * choice, which might be a little bit different than the active one returned by
     * {@link #getPreferredDataSubscriptionId()}.
     */
    @Override
    public int getDefaultDataSubId() {
        return mDefaultDataSubId.get();
    }

    /**
     * Set the default data subscription id.
     *
     * @param subId The default data subscription id.
     *
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setDefaultDataSubId(int subId) {
        enforcePermissions("setDefaultDataSubId", Manifest.permission.MODIFY_PHONE_STATE);

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultDataSubId called with DEFAULT_SUBSCRIPTION_ID");
        }

        enforceTelephonyFeatureWithException(getCurrentPackageName(), "setDefaultDataSubId");

        final long token = Binder.clearCallingIdentity();
        try {
            if (mDefaultDataSubId.set(subId)) {
                remapRafIfApplicable();

                MultiSimSettingController.getInstance().notifyDefaultDataSubChanged();

                broadcastSubId(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED,
                        subId);
                mSubscriptionManagerServiceCallbacks.forEach(
                        callback -> callback.invokeFromExecutor(
                                () -> callback.onDefaultDataSubscriptionChanged(subId)));

                updateDefaultSubId();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Remap Radio Access Family if needed.
     */
    private void remapRafIfApplicable() {
        boolean applicable = mSlotIndexToSubId.containsValue(getDefaultDataSubId());
        if (!applicable) return;
        ProxyController proxyController = ProxyController.getInstance();
        RadioAccessFamily[] rafs = new RadioAccessFamily[mTelephonyManager.getActiveModemCount()];
        for (int phoneId = 0; phoneId < rafs.length; phoneId++) {
            int raf = mSlotIndexToSubId.getOrDefault(phoneId,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID) == getDefaultDataSubId()
                    ? proxyController.getMaxRafSupported() : proxyController.getMinRafSupported();
            rafs[phoneId] = new RadioAccessFamily(phoneId, raf);
        }
        proxyController.setRadioCapability(rafs);
    }

    /**
     * @return The default subscription id for voice.
     * @deprecated Use {@link #getDefaultVoiceSubIdAsUser}.
     */
    @Override
    public int getDefaultVoiceSubId() {
        return getDefaultVoiceSubIdAsUser(BINDER_WRAPPER.getCallingUserHandle().getIdentifier());
    }

    /**
     * @param userId The calling user Id.
     * @return The default voice subscription id.
     */
    @Override
    public int getDefaultVoiceSubIdAsUser(@UserIdInt int userId) {
        return getDefaultAsUser(userId, mDefaultVoiceSubId.get());
    }

    /**
     * Set the default voice subscription id.
     *
     * @param subId The default SMS subscription id.
     *
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setDefaultVoiceSubId(int subId) {
        enforcePermissions("setDefaultVoiceSubId", Manifest.permission.MODIFY_PHONE_STATE);

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultVoiceSubId called with DEFAULT_SUB_ID");
        }

        enforceTelephonyFeatureWithException(getCurrentPackageName(), "setDefaultVoiceSubId");

        final long token = Binder.clearCallingIdentity();
        try {
            if (mDefaultVoiceSubId.set(subId)) {
                broadcastSubId(TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED,
                        subId);

                PhoneAccountHandle newHandle = subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        ? null : mTelephonyManager.getPhoneAccountHandleForSubscriptionId(subId);

                TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
                if (telecomManager != null) {
                    telecomManager.setUserSelectedOutgoingPhoneAccount(newHandle);
                }

                updateDefaultSubId();
            }

        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * @return The default subscription id for SMS.
     * @deprecated Use {@link #getDefaultSmsSubIdAsUser}.
     */
    @Override
    public int getDefaultSmsSubId() {
        return getDefaultSmsSubIdAsUser(BINDER_WRAPPER.getCallingUserHandle().getIdentifier());
    }

    /**
     * Get the default sms subscription id associated with the user. When a subscription is
     * associated with personal profile or work profile, the default sms subscription id will be
     * always the subscription it is associated with.
     *
     * @param userId The given user Id to check.
     * @return The default voice id.
     */
    @Override
    public int getDefaultSmsSubIdAsUser(@UserIdInt int userId) {
        return getDefaultAsUser(userId, mDefaultSmsSubId.get());
    }

    /**
     * Set the default SMS subscription id.
     *
     * @param subId The default SMS subscription id.
     *
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setDefaultSmsSubId(int subId) {
        enforcePermissions("setDefaultSmsSubId", Manifest.permission.MODIFY_PHONE_STATE);

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultSmsSubId called with DEFAULT_SUB_ID");
        }

        enforceTelephonyFeatureWithException(getCurrentPackageName(), "setDefaultSmsSubId");

        final long token = Binder.clearCallingIdentity();
        try {
            if (mDefaultSmsSubId.set(subId)) {
                broadcastSubId(SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED,
                        subId);
            }

        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Broadcast a sub Id with the given action.
     * @param action The intent action.
     * @param newSubId The sub Id to broadcast.
     */
    private void broadcastSubId(@NonNull String action, int newSubId) {
        Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        SubscriptionManager.putSubscriptionIdExtra(intent, newSubId);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        log("broadcastSubId action: " + action + " subId= " + newSubId);
    }

    /**
     * Get the active subscription id list.
     *
     * @param visibleOnly {@code true} if only includes user visible subscription's sub id.
     *
     * @return Array of the active subscription ids.
     *
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @NonNull public int[] getActiveSubIdList(boolean visibleOnly) {
        enforcePermissions("getActiveSubIdList", Manifest.permission.READ_PRIVILEGED_PHONE_STATE);

        if (!mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(getCurrentPackageName(), "getActiveSubIdList");
        }

        // UserHandle.ALL because this API is exposed as system API.
        return getActiveSubIdListAsUser(visibleOnly, UserHandle.ALL);
    }

    /**
     * Get the active local subscription id list.
     *
     * @return Array of the active local subscription ids.
     */
    @NonNull private int[] getActiveLocalSubIdList() {
        if (!mFeatureFlags.remoteSimSubIdSet()) {
            // This method was added with and is only used with mFeatureFlags.remoteSimSubIdSet()
            return new int[]{};
        }
        return filterSubIdStreamVisibilityAsUser(
                mSlotIndexToSubId.values().stream(),
                true,
                UserHandle.ALL);
    }

    /**
     * Get the active remote subscription id list.
     *
     * @return Array of the active remote subscription ids.
     */
    @NonNull private int[] getActiveRemoteSubIdList() {
        if (!mFeatureFlags.remoteSimSubIdSet()) {
            // This method was added with and is only used with mFeatureFlags.remoteSimSubIdSet()
            return new int[]{};
        }
        return filterSubIdStreamVisibilityAsUser(
                mRemoteSubIds.stream(),
                true,
                UserHandle.ALL);
    }

    /**
     * Filter a subscription id stream for visibility as user.
     *
     * @param subIdStream Stream of subscription ids.
     * @param visibleOnly {@code true} if only includes user visible subscription's sub id.
     * @param user The user handle used to judge which subscriptions are accessible.
     * @return Array of the filtered subscription ids.
     */
    @NonNull private int[] filterSubIdStreamVisibilityAsUser(
            Stream<Integer> subIdStream, boolean visibleOnly, @NonNull final UserHandle user) {
        return subIdStream.filter(subId -> {
            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                            .getSubscriptionInfoInternal(subId);
            return subInfo != null && (!visibleOnly || subInfo.isVisible())
                            && isSubscriptionAssociatedWithUserInternal(
                            subInfo, user.getIdentifier());
        }).mapToInt(x -> x).toArray();
    }

    /**
     * Get the active subscription id list as user.
     * Must be used before clear Binder identity.
     *
     * @param visibleOnly {@code true} if only includes user visible subscription's sub id.
     * @param user The user handle used to judge which subscriptions are accessible.
     * @return Array of the active subscription ids.
     */
    @NonNull private int[] getActiveSubIdListAsUser(
            boolean visibleOnly, @NonNull final UserHandle user) {
        Stream<Integer> activeSubIdStream;
        if (mFeatureFlags.remoteSimSubIdSet()) {
            activeSubIdStream = Stream.concat(
                    mSlotIndexToSubId.values().stream(),
                    mRemoteSubIds.stream());
        } else {
            // Only includes the most recently inserted remote SIM, which might actually be missing
            // due to a bug that was fixed with mFeatureFlags.remoteSimSubIdSet()
            activeSubIdStream = mSlotIndexToSubId.values().stream();
        }
        return filterSubIdStreamVisibilityAsUser(activeSubIdStream, visibleOnly, user);
    }

    /**
     * Set a field in the subscription database. Note not all fields are supported.
     *
     * @param subId Subscription Id of Subscription.
     * @param columnName Column name in the database. Note not all fields are supported.
     * @param value Value to store in the database.
     *
     * @throws IllegalArgumentException if {@code subscriptionId} is invalid, or the field is not
     * exposed.
     * @throws SecurityException if callers do not hold the required permission.
     *
     * @see #getSubscriptionProperty(int, String, String, String)
     * @see SimInfo for all the columns.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setSubscriptionProperty(int subId, @NonNull String columnName,
            @NonNull String value) {
        enforcePermissions("setSubscriptionProperty", Manifest.permission.MODIFY_PHONE_STATE);

        final long token = Binder.clearCallingIdentity();
        try {
            logl("setSubscriptionProperty: subId=" + subId + ", columnName=" + columnName
                    + ", value=" + value + ", calling package=" + getCallingPackage());

            if (!SimInfo.getAllColumns().contains(columnName)) {
                throw new IllegalArgumentException("Invalid column name " + columnName);
            }

            // Check if the columns are allowed to be accessed through the generic
            // getSubscriptionProperty method.
            if (!DIRECT_ACCESS_SUBSCRIPTION_COLUMNS.contains(columnName)) {
                throw new SecurityException("Column " + columnName + " is not allowed be directly "
                        + "accessed through setSubscriptionProperty.");
            }

            mSubscriptionDatabaseManager.setSubscriptionProperty(subId, columnName, value);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Get specific field in string format from the subscription info database.
     *
     * @param subId Subscription id of the subscription.
     * @param columnName Column name in subscription database.
     *
     * @return Value in string format associated with {@code subscriptionId} and {@code columnName}
     * from the database. {@code null} if the {@code subscriptionId} is invalid (for backward
     * compatible).
     *
     * @throws IllegalArgumentException if the field is not exposed.
     * @throws SecurityException if callers do not hold the required permission.
     *
     * @see SimInfo for all the columns.
     */
    @Override
    @Nullable
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public String getSubscriptionProperty(int subId, @NonNull String columnName,
            @NonNull String callingPackage, @Nullable String callingFeatureId) {
        Objects.requireNonNull(columnName);
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(mContext, subId,
                callingPackage, callingFeatureId,
                "getSubscriptionProperty")) {
            throw new SecurityException("Need READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or "
                    + "carrier privilege");
        }

        if (!SimInfo.getAllColumns().contains(columnName)) {
            throw new IllegalArgumentException("Invalid column name " + columnName);
        }

        // Check if the columns are allowed to be accessed through the generic
        // getSubscriptionProperty method.
        if (!DIRECT_ACCESS_SUBSCRIPTION_COLUMNS.contains(columnName)) {
            throw new SecurityException("Column " + columnName + " is not allowed be directly "
                    + "accessed through getSubscriptionProperty.");
        }

        enforceTelephonyFeatureWithException(callingPackage, "getSubscriptionProperty");

        final long token = Binder.clearCallingIdentity();
        try {
            Object value = mSubscriptionDatabaseManager.getSubscriptionProperty(subId, columnName);
            // The raw types of subscription database should only have 3 different types.
            if (value instanceof Integer || value instanceof Long) {
                return String.valueOf(value);
            } else if (value instanceof String) {
                return (String) value;
            } else if (value instanceof byte[]) {
                return Base64.encodeToString((byte[]) value, Base64.DEFAULT);
            } else {
                // This should not happen unless SubscriptionDatabaseManager.getSubscriptionProperty
                // did not implement correctly.
                throw new RuntimeException("Unexpected type " + value.getClass().getTypeName()
                        + " was returned from SubscriptionDatabaseManager for column "
                        + columnName);
            }
        } catch (IllegalArgumentException e) {
            loge("getSubscriptionProperty: Invalid subId " + subId + ", columnName=" + columnName);
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Check if a subscription is active.
     *
     * @param subId The subscription id to check.
     *
     * @return {@code true} if the subscription is active.
     *
     * @throws IllegalArgumentException if the provided slot index is invalid.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isSubscriptionEnabled(int subId) {
        enforcePermissions("isSubscriptionEnabled",
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription id " + subId);
        }

        enforceTelephonyFeatureWithException(getCurrentPackageName(), "isSubscriptionEnabled");

        final long identity = Binder.clearCallingIdentity();
        try {
            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternal(subId);
            return subInfo != null && subInfo.isActive();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Get the active subscription id by logical SIM slot index.
     *
     * @param slotIndex The logical SIM slot index.
     * @return The active subscription id.
     *
     * @throws IllegalArgumentException if the provided slot index is invalid.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getEnabledSubscriptionId(int slotIndex) {
        enforcePermissions("getEnabledSubscriptionId",
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);

        if (!SubscriptionManager.isValidSlotIndex(slotIndex)) {
            throw new IllegalArgumentException("Invalid slot index " + slotIndex);
        }

        enforceTelephonyFeatureWithException(getCurrentPackageName(), "getEnabledSubscriptionId");

        final long identity = Binder.clearCallingIdentity();
        try {
            return mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                    .filter(subInfo -> subInfo.isActive() && subInfo.getSimSlotIndex() == slotIndex)
                    .mapToInt(SubscriptionInfoInternal::getSubscriptionId)
                    .findFirst()
                    .orElse(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Check if a subscription is active.
     *
     * @param subId The subscription id.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return {@code true} if the subscription is active.
     *
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public boolean isActiveSubId(int subId, @NonNull String callingPackage,
            @Nullable String callingFeatureId) {
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(mContext, subId, callingPackage,
                callingFeatureId, "isActiveSubId")) {
            throw new SecurityException("Need READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or "
                    + "carrier privilege");
        }

        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_force_phone_globals_creation)) {
            enforceTelephonyFeatureWithException(callingPackage, "isActiveSubId");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternal(subId);
            return subInfo != null && subInfo.isActive();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Get active data subscription id. Active data subscription refers to the subscription
     * currently chosen to provide cellular internet connection to the user. This may be
     * different from getDefaultDataSubscriptionId().
     *
     * @return Active data subscription id if any is chosen, or
     * SubscriptionManager.INVALID_SUBSCRIPTION_ID if not.
     *
     * @see android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
     */
    @Override
    public int getActiveDataSubscriptionId() {
        final long token = Binder.clearCallingIdentity();
        try {
            PhoneSwitcher phoneSwitcher = PhoneSwitcher.getInstance();
            if (phoneSwitcher != null) {
                int activeDataSubId = phoneSwitcher.getActiveDataSubId();
                if (SubscriptionManager.isUsableSubscriptionId(activeDataSubId)) {
                    return activeDataSubId;
                }
            }
            // If phone switcher isn't ready, or active data sub id is not available, use default
            // sub id from settings.
            return getDefaultDataSubId();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Whether it's supported to disable / re-enable a subscription on a physical (non-euicc) SIM.
     *
     * Physical SIM refers non-euicc, or aka non-programmable SIM.
     *
     * It provides whether a physical SIM card can be disabled without taking it out, which is done
     * via {@link SubscriptionManager#setSubscriptionEnabled(int, boolean)} API.
     *
     * @return whether can disable subscriptions on physical SIMs.
     *
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean canDisablePhysicalSubscription() {
        enforcePermissions("canDisablePhysicalSubscription",
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);

        enforceTelephonyFeatureWithException(getCurrentPackageName(),
                "canDisablePhysicalSubscription");

        final long identity = Binder.clearCallingIdentity();
        try {
            Phone phone = PhoneFactory.getDefaultPhone();
            return phone != null && phone.canDisablePhysicalSubscription();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set uicc applications being enabled or disabled.
     *
     * The value will be remembered on the subscription and will be applied whenever it's present.
     * If the subscription in currently present, it will also apply the setting to modem
     * immediately (the setting in the modem will not change until the modem receives and responds
     * to the request, but typically this should only take a few seconds. The user visible setting
     * available from {@link SubscriptionInfo#areUiccApplicationsEnabled()} will be updated
     * immediately.)
     *
     * @param enabled whether uicc applications are enabled or disabled.
     * @param subId which subscription to operate on.
     *
     * @throws IllegalArgumentException if the subscription does not exist.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setUiccApplicationsEnabled(boolean enabled, int subId) {
        enforcePermissions("setUiccApplicationsEnabled",
                Manifest.permission.MODIFY_PHONE_STATE);
        logl("setUiccApplicationsEnabled: subId=" + subId + ", enabled=" + enabled
                + ", calling package=" + getCallingPackage());

        enforceTelephonyFeatureWithException(getCurrentPackageName(),
                "setUiccApplicationsEnabled");

        final long identity = Binder.clearCallingIdentity();
        try {

            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternal(subId);
            if (subInfo == null) {
                throw new IllegalArgumentException("setUiccApplicationsEnabled: Subscription "
                        + "doesn't exist. subId=" + subId);
            }

            if (subInfo.areUiccApplicationsEnabled() != enabled) {
                mSubscriptionDatabaseManager.setUiccApplicationsEnabled(subId, enabled);
                mSubscriptionManagerServiceCallbacks.forEach(
                        callback -> callback.invokeFromExecutor(
                                () -> callback.onUiccApplicationsEnabledChanged(subId)));
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set the device to device status sharing user preference for a subscription ID. The setting
     * app uses this method to indicate with whom they wish to share device to device status
     * information.
     *
     * @param sharing the status sharing preference.
     * @param subId the unique Subscription ID in database.
     *
     * @return the number of records updated.
     *
     * @throws IllegalArgumentException if the subscription does not exist, or the sharing
     * preference is invalid.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int setDeviceToDeviceStatusSharing(@DeviceToDeviceStatusSharingPreference int sharing,
            int subId) {
        enforcePermissions("setDeviceToDeviceStatusSharing",
                Manifest.permission.MODIFY_PHONE_STATE);

        enforceTelephonyFeatureWithException(getCurrentPackageName(),
                "setDeviceToDeviceStatusSharing");

        final long identity = Binder.clearCallingIdentity();
        try {
            if (sharing < SubscriptionManager.D2D_SHARING_DISABLED
                    || sharing > SubscriptionManager.D2D_SHARING_ALL) {
                throw new IllegalArgumentException("invalid sharing " + sharing);
            }

            mSubscriptionDatabaseManager.setDeviceToDeviceStatusSharingPreference(subId, sharing);
            return 1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set the list of contacts that allow device to device status sharing for a subscription ID.
     * The setting app uses this method to indicate with whom they wish to share device to device
     * status information.
     *
     * @param contacts The list of contacts that allow device to device status sharing
     * @param subId The unique Subscription ID in database.
     *
     * @throws IllegalArgumentException if {@code subId} is invalid.
     * @throws NullPointerException if {@code contacts} is {@code null}.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int setDeviceToDeviceStatusSharingContacts(@NonNull String contacts, int subId) {
        enforcePermissions("setDeviceToDeviceStatusSharingContacts",
                Manifest.permission.MODIFY_PHONE_STATE);

        enforceTelephonyFeatureWithException(getCurrentPackageName(),
                "setDeviceToDeviceStatusSharingContacts");

        final long identity = Binder.clearCallingIdentity();
        try {
            Objects.requireNonNull(contacts, "contacts");
            mSubscriptionDatabaseManager.setDeviceToDeviceStatusSharingContacts(subId, contacts);
            return 1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns the phone number for the given {@code subscriptionId} and {@code source},
     * or an empty string if not available.
     *
     * <p>General apps that need to know the phone number should use
     * {@link SubscriptionManager#getPhoneNumber(int)} instead. This API may be suitable specific
     * apps that needs to know the phone number from a specific source. For example, a carrier app
     * needs to know exactly what's on {@link SubscriptionManager#PHONE_NUMBER_SOURCE_UICC UICC} and
     * decide if the previously set phone number of source
     * {@link SubscriptionManager#PHONE_NUMBER_SOURCE_CARRIER carrier} should be updated.
     *
     * <p>The API provides no guarantees of what format the number is in: the format can vary
     * depending on the {@code source} and the network etc. Programmatic parsing should be done
     * cautiously, for example, after formatting the number to a consistent format with
     * {@link android.telephony.PhoneNumberUtils#formatNumberToE164(String, String)}.
     *
     * <p>Note the assumption is that one subscription (which usually means one SIM) has
     * only one phone number. The multiple sources backup each other so hopefully at least one
     * is available. For example, for a carrier that doesn't typically set phone numbers
     * on {@link SubscriptionManager#PHONE_NUMBER_SOURCE_UICC UICC}, the source
     * {@link SubscriptionManager#PHONE_NUMBER_SOURCE_IMS IMS} may provide one. Or, a carrier may
     * decide to provide the phone number via source
     * {@link SubscriptionManager#PHONE_NUMBER_SOURCE_CARRIER carrier} if neither source UICC nor
     * IMS is available.
     *
     * <p>The availability and correctness of the phone number depends on the underlying source
     * and the network etc. Additional verification is needed to use this number for
     * security-related or other sensitive scenarios.
     *
     * @param subId The subscription ID.
     * @param source The source of the phone number.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return The phone number, or an empty string if not available.
     *
     * @throws IllegalArgumentException if {@code source} is invalid.
     * @throws SecurityException if the caller doesn't have permissions required.
     *
     * @see SubscriptionManager#PHONE_NUMBER_SOURCE_UICC
     * @see SubscriptionManager#PHONE_NUMBER_SOURCE_CARRIER
     * @see SubscriptionManager#PHONE_NUMBER_SOURCE_IMS
     * @see SubscriptionManager#PHONE_NUMBER_SOURCE_TS43
     */
    @Override
    @NonNull
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public String getPhoneNumber(int subId, @PhoneNumberSource int source,
            @NonNull String callingPackage, @Nullable String callingFeatureId /* unused */) {

        if (mFeatureFlags.getPhoneNumberTs43Api()
                && source == SubscriptionManager.PHONE_NUMBER_SOURCE_TS43) {
            if (!TelephonyPermissions.isSystemOrPhone(BINDER_WRAPPER.getCallingUid())
                    && !TelephonyPermissions.checkCarrierPrivilegeForSubId(mContext, subId)) {
                throw new SecurityException(
                        "getPhoneNumber(TS43) is restricted to privileged system components or"
                                + " carrier privileged apps.");
            }
        }

        TelephonyPermissions.enforceAnyPermissionGrantedOrCarrierPrivileges(
                mContext, subId, Binder.getCallingUid(), "getPhoneNumber",
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        enforceTelephonyFeatureWithException(callingPackage, "getPhoneNumber");

        checkPhoneNumberSource(source);
        subId = checkAndGetSubId(subId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return "";

        final long identity = Binder.clearCallingIdentity();
        try {
            return getPhoneNumberFromSourceInternal(subId, source, true);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

    }

    /**
     * Get a resolved subId based on what the user passed in.
     *
     * Only use this before clearing the calling binder. Used for compatibility (only).
     * Do not use this behavior for new methods.
     *
     * @param subId the subId passed in by the user.
     */
    private int checkAndGetSubId(int subId) {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            // for historical reasons, INVALID_SUB_ID fails gracefully
            return subId;
        } else if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            return getDefaultSubId();
        } else if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid SubId=" + subId);
        } else {
            return subId;
        }
    }

    private void checkPhoneNumberSource(int source) {
        if (source == SubscriptionManager.PHONE_NUMBER_SOURCE_UICC
                || source == SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER
                || source == SubscriptionManager.PHONE_NUMBER_SOURCE_IMS
                || (mFeatureFlags.getPhoneNumberTs43Api()
                && source == SubscriptionManager.PHONE_NUMBER_SOURCE_TS43)) {
            return;
        }

        throw new IllegalArgumentException("Invalid number source " + source);
    }

    private @NonNull String getPhoneNumberFromSourceInternal(
            int subId,
            @PhoneNumberSource int source, boolean checkForImsRegistration) {

        final SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                .getSubscriptionInfoInternal(subId);

        if (subInfo == null) {
            loge("No SubscriptionInfo found for subId=" + subId);
            return "";
        }

        switch(source) {
            case SubscriptionManager.PHONE_NUMBER_SOURCE_UICC:
                final Phone phone = PhoneFactory.getPhone(getSlotIndex(subId));
                if (phone != null) {
                    return TextUtils.emptyIfNull(phone.getLine1Number());
                } else {
                    return subInfo.getNumber();
                }
            case SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER:
                return subInfo.getNumberFromCarrier();
            case SubscriptionManager.PHONE_NUMBER_SOURCE_IMS:
                if (checkForImsRegistration) {
                    TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
                    if (tm == null || !tm.isImsRegistered()) {
                        return "";
                    }
                    // Check if the number for the current IMS session was successfully parsed.
                    // If the state is not true, return empty string to reflect parsing failure.
                    if (!mImsNumberUpdateStatus.getOrDefault(subId, false)) {
                        return "";
                    }
                }
                return subInfo.getNumberFromIms();
            case SubscriptionManager.PHONE_NUMBER_SOURCE_TS43:
                return subInfo.getNumberFromTs43();
            default:
                loge("No SubscriptionInfo found for subId=" + subId);
                return "";
        }
    }

    /**
     * Get phone number from first available source. The order would be
     * {@link SubscriptionManager#PHONE_NUMBER_SOURCE_CARRIER},
     * {@link SubscriptionManager#PHONE_NUMBER_SOURCE_UICC},
     * {@link SubscriptionManager#PHONE_NUMBER_SOURCE_TS43}, then
     * {@link SubscriptionManager#PHONE_NUMBER_SOURCE_IMS}.
     *
     * @param subId The subscription ID.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @return The phone number from the first available source.
     *
     * @throws IllegalArgumentException if {@code subId} is invalid.
     * @throws SecurityException if callers do not hold the required permission.
     */
    @Override
    @NonNull
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public String getPhoneNumberFromFirstAvailableSource(int subId,
            @NonNull String callingPackage, @Nullable String callingFeatureId) {
        TelephonyPermissions.enforceAnyPermissionGrantedOrCarrierPrivileges(
                mContext, subId, Binder.getCallingUid(), "getPhoneNumberFromFirstAvailableSource",
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        int callingUid = BINDER_WRAPPER.getCallingUid();

        enforceTelephonyFeatureWithException(callingPackage,
                "getPhoneNumberFromFirstAvailableSource");

        subId = checkAndGetSubId(subId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return "";
        boolean isCarrierPrivileged =
                TelephonyPermissions.checkCarrierPrivilegeForSubId(mContext, subId);

        final long identity = Binder.clearCallingIdentity();
        try {
            String number;
            number = getPhoneNumberFromSourceInternal(
                    subId,
                    SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER, false);
            if (!TextUtils.isEmpty(number)) return number;

            number = getPhoneNumberFromSourceInternal(
                    subId,
                    SubscriptionManager.PHONE_NUMBER_SOURCE_UICC, false);
            if (!TextUtils.isEmpty(number)) return number;

            if (mFeatureFlags.getPhoneNumberTs43Api()
                    && (TelephonyPermissions.isSystemOrPhone(callingUid)
                    || isCarrierPrivileged)) {
                number = getPhoneNumberFromSourceInternal(
                        subId,
                        SubscriptionManager.PHONE_NUMBER_SOURCE_TS43, false);
                if (!TextUtils.isEmpty(number)) return number;
            }

            number = getPhoneNumberFromSourceInternal(
                    subId,
                    SubscriptionManager.PHONE_NUMBER_SOURCE_IMS, true);
            return TextUtils.emptyIfNull(number);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Gets the last known phone number from the first available source, bypassing
     * certain liveness checks like IMS registration status.
     * <p>
     * This API is similar to {@link #getPhoneNumberFromFirstAvailableSource(int, String, String)}
     * but returns a cached value even if IMS is not currently registered.
     * It is intended for internal system use-cases like satellite services that require
     * a phone number even if it has low confidence.
     * <p>
     * The sources are checked in the following order:
     * <ol>
     *   <li>{@link SubscriptionManager#PHONE_NUMBER_SOURCE_CARRIER}
     *   <li>{@link SubscriptionManager#PHONE_NUMBER_SOURCE_UICC}
     *   <li>{@link SubscriptionManager#PHONE_NUMBER_SOURCE_TS43}
     *   <li>{@link SubscriptionManager#PHONE_NUMBER_SOURCE_IMS}
     * </ol>
     *
     * @param subId The subscription ID.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     * @return The last known phone number from the first available source, or an empty string
     *         if not available.
     * @hide
     */
    @Override
    @NonNull
    @RequiresPermission(anyOf = {
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            "carrier privileges",
    })
    public String getLastKnownPhoneNumberFromFirstAvailableSource(int subId,
            @NonNull String callingPackage, @Nullable String callingFeatureId) {
        TelephonyPermissions.enforceAnyPermissionGrantedOrCarrierPrivileges(
                mContext, subId, Binder.getCallingUid(),
                "getLastKnownPhoneNumberFromFirstAvailableSource",
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        int callingUid = BINDER_WRAPPER.getCallingUid();

        enforceTelephonyFeatureWithException(callingPackage,
                "getLastKnownPhoneNumberFromFirstAvailableSource");

        subId = checkAndGetSubId(subId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return "";
        boolean isCarrierPrivileged =
                TelephonyPermissions.checkCarrierPrivilegeForSubId(mContext, subId);
        final long identity = Binder.clearCallingIdentity();
        try {
            String number;
            number = getPhoneNumberFromSourceInternal(subId,
                    SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER, false);
            if (!TextUtils.isEmpty(number)) return number;

            number = getPhoneNumberFromSourceInternal(subId,
                    SubscriptionManager.PHONE_NUMBER_SOURCE_UICC, false);
            if (!TextUtils.isEmpty(number)) return number;

            if (mFeatureFlags.getPhoneNumberTs43Api()
                    && (TelephonyPermissions.isSystemOrPhone(callingUid)
                    || isCarrierPrivileged)) {
                number = getPhoneNumberFromSourceInternal(subId,
                        SubscriptionManager.PHONE_NUMBER_SOURCE_TS43, false);
                if (!TextUtils.isEmpty(number)) return number;
            }

            number = getPhoneNumberFromSourceInternal(subId,
                    SubscriptionManager.PHONE_NUMBER_SOURCE_IMS, false);
            return TextUtils.emptyIfNull(number);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * For internal use by ImsPhone to report the status of the phone number update
     * for the current IMS session.
     */
    public void setImsNumberUpdateStatus(int subId, boolean success) {
        mImsNumberUpdateStatus.put(subId, success);
    }

    /**
     * Clears the IMS number update state for a given subscription ID.
     * This is called at the start of a new IMS registration attempt to clear  parsing status
     * from a previous session.
     */
    public void clearImsNumberUpdateStatus(int subId) {
        mImsNumberUpdateStatus.remove(subId);
    }

    /**
     * Set the phone number of the subscription.
     *
     * @param subId The subscription id.
     * @param source The phone number source.
     * @param number The phone number.
     * @param callingPackage The package making the call.
     * @param callingFeatureId The feature in the package.
     *
     * @throws IllegalArgumentException {@code subId} is invalid, or {@code source} is not
     * {@link SubscriptionManager#PHONE_NUMBER_SOURCE_CARRIER}
     * and {@link SubscriptionManager#PHONE_NUMBER_SOURCE_TS43}.
     * @throws NullPointerException if {@code number} is {@code null}.
     */
    @Override
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_PHONE_STATE,
            "carrier privileges"
    })
    public void setPhoneNumber(int subId, @PhoneNumberSource int source, @NonNull String number,
            @NonNull String callingPackage, @Nullable String callingFeatureId) {
        logl("setPhoneNumber: subId=" + subId + ", source=" + source + ", number="
                + Rlog.pii(TelephonyUtils.IS_DEBUGGABLE, number)
                + ", calling package=" + callingPackage);
        if (source == SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER
                && !TelephonyPermissions.checkCarrierPrivilegeForSubId(mContext, subId)) {
            throw new SecurityException("setPhoneNumber for CARRIER needs carrier privilege.");
        }

        if (mFeatureFlags.getPhoneNumberTs43Api()
                && source == SubscriptionManager.PHONE_NUMBER_SOURCE_TS43) {
            enforcePermissions("setPhoneNumber", Manifest.permission.MODIFY_PHONE_STATE);
        }

        if (source != SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER
                && !(mFeatureFlags.getPhoneNumberTs43Api()
                && source == SubscriptionManager.PHONE_NUMBER_SOURCE_TS43)) {
            throw new IllegalArgumentException("setPhoneNumber doesn't accept source "
                    + SubscriptionManager.phoneNumberSourceToString(source));
        }

        enforceTelephonyFeatureWithException(callingPackage, "setPhoneNumber");

        Objects.requireNonNull(number, "number");

        final long identity = Binder.clearCallingIdentity();
        try {
            if (source == SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER) {
                mSubscriptionDatabaseManager.setNumberFromCarrier(subId, number);
            } else if (mFeatureFlags.getPhoneNumberTs43Api()
                    && source == SubscriptionManager.PHONE_NUMBER_SOURCE_TS43) {
                mSubscriptionDatabaseManager.setNumberFromTs43(subId, number);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set the Usage Setting for this subscription.
     *
     * @param usageSetting the usage setting for this subscription
     * @param subId the unique SubscriptionInfo index in database
     * @param callingPackage The package making the IPC.
     *
     * @throws IllegalArgumentException if the subscription does not exist, or {@code usageSetting}
     * is invalid.
     * @throws SecurityException if doesn't have MODIFY_PHONE_STATE or Carrier Privileges
     */
    @Override
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_PHONE_STATE,
            "carrier privileges",
    })
    public int setUsageSetting(@UsageSetting int usageSetting, int subId,
            @NonNull String callingPackage) {
        TelephonyPermissions.enforceAnyPermissionGrantedOrCarrierPrivileges(
                mContext, Binder.getCallingUid(), subId, true, "setUsageSetting",
                Manifest.permission.MODIFY_PHONE_STATE);

        if (usageSetting < SubscriptionManager.USAGE_SETTING_DEFAULT
                || usageSetting > SubscriptionManager.USAGE_SETTING_DATA_CENTRIC) {
            throw new IllegalArgumentException("setUsageSetting: Invalid usage setting: "
                    + usageSetting);
        }

        final long token = Binder.clearCallingIdentity();
        try {
            mSubscriptionDatabaseManager.setUsageSetting(subId, usageSetting);
            return 1;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Set UserHandle for this subscription.
     *
     * @param userHandle the userHandle associated with the subscription
     * Pass {@code null} user handle to clear the association
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated.
     *
     * @throws SecurityException if callers do not hold the required permission.
     * @throws IllegalArgumentException if {@code subId} is invalid.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION)
    public int setSubscriptionUserHandle(@Nullable UserHandle userHandle, int subId) {
        enforcePermissions("setSubscriptionUserHandle",
                Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION);

        if (userHandle == null) {
            userHandle = UserHandle.of(UserHandle.USER_NULL);
        }

        long token = Binder.clearCallingIdentity();
        try {
            // This can throw IllegalArgumentException if the subscription does not exist.
            mSubscriptionDatabaseManager.setUserId(subId, userHandle.getIdentifier());
            return 1;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Get UserHandle of this subscription.
     *
     * @param subId the unique SubscriptionInfo index in database
     * @return userHandle associated with this subscription
     * or {@code null} if subscription is not associated with any user
     * or {code null} if subscripiton is not available on the device.
     *
     * @throws SecurityException if doesn't have required permission.
     */
    @Override
    @Nullable
    @RequiresPermission(Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION)
    public UserHandle getSubscriptionUserHandle(int subId) {
        enforcePermissions("getSubscriptionUserHandle",
                Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION);
        long token = Binder.clearCallingIdentity();
        try {
            SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                    .getSubscriptionInfoInternal(subId);
            if (subInfo == null) {
                return null;
            }

            UserHandle userHandle = UserHandle.of(subInfo.getUserId());
            if (userHandle.getIdentifier() == UserHandle.USER_NULL) {
                return null;
            }
            return userHandle;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns whether the given subscription is associated with the calling user.
     *
     * @param subscriptionId the subscription ID of the subscription
     * @param callingPackage The package making the call
     * @param callingFeatureId The feature in the package
     *
     * @return {@code true} if the subscription is associated with the user that the calling process
     *         is running in; {@code false} otherwise.
     *
     * @throws IllegalArgumentException if subscription doesn't exist.
     * @throws SecurityException if the caller doesn't have permissions required.
     */
    @Override
    public boolean isSubscriptionAssociatedWithCallingUser(int subscriptionId,
            @NonNull String callingPackage, @Nullable String callingFeatureId) {
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(mContext, subscriptionId,
                callingPackage, callingFeatureId, "isSubscriptionAssociatedWithCallingUser")) {
            throw new SecurityException("Need READ_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, or "
                    + "carrier privilege");
        }

        UserHandle myUserHandle = UserHandle.of(UserHandle.getCallingUserId());
        return isSubscriptionAssociatedWithUserNoCheck(subscriptionId, myUserHandle);
    }

    /**
     * Check if subscription and user are associated with each other.
     *
     * @param subscriptionId the subId of the subscription
     * @param userHandle user handle of the user
     * @return {@code true} if subscription is associated with user
     * else {@code false} if subscription is not associated with user.
     *
     * @throws SecurityException if the caller doesn't have permissions required.
     * @throws IllegalArgumentException if the subscription has no records on device.
     */
    @Override
    public boolean isSubscriptionAssociatedWithUser(int subscriptionId,
            @NonNull UserHandle userHandle) {
        enforcePermissions("isSubscriptionAssociatedWithUser",
                Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION);

        return isSubscriptionAssociatedWithUserNoCheck(subscriptionId, userHandle);
    }

    private boolean isSubscriptionAssociatedWithUserNoCheck(int subscriptionId,
            @NonNull UserHandle userHandle) {
        SubscriptionInfoInternal subInfoInternal = mSubscriptionDatabaseManager
                .getSubscriptionInfoInternal(subscriptionId);
        // Throw IAE if no record of the sub's association state.
        if (subInfoInternal == null) {
            throw new IllegalArgumentException(
                    "[isSubscriptionAssociatedWithUser]: Subscription doesn't exist: "
                            + subscriptionId);
        }

        if (mFeatureFlags.enforceSubscriptionUserFilter()) {
            return isSubscriptionAssociatedWithUserInternal(
                    subInfoInternal, userHandle.getIdentifier());
        }

        long token = Binder.clearCallingIdentity();
        try {
            // Get list of subscriptions associated with this user.
            List<SubscriptionInfo> associatedSubscriptionsList =
                    getSubscriptionInfoListAssociatedWithUser(userHandle);
            // Return true if required subscription is present in associated subscriptions list.
            for (SubscriptionInfo subInfo: associatedSubscriptionsList) {
                if (subInfo.getSubscriptionId() == subscriptionId) {
                    return true;
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * @param subInfo The subscription info to check.
     * @param userId The caller user Id.
     * @return {@code true} if the given user Id is allowed to access to the given subscription.
     */
    private boolean isSubscriptionAssociatedWithUserInternal(
            @NonNull SubscriptionInfoInternal subInfo, @UserIdInt int userId) {
        if (!mFeatureFlags.enforceSubscriptionUserFilter()
                || !CompatChanges.isChangeEnabled(FILTER_ACCESSIBLE_SUBS_BY_USER,
                Binder.getCallingUid())) {
            return true;
        }
        // Can access the unassociated sub if the user doesn't have its own.
        return (subInfo.getUserId() == UserHandle.USER_NULL
                && mUserIdToAvailableSubs.get(userId) == null)
                || userId == subInfo.getUserId()
                || userId == UserHandle.USER_ALL;
    }

    /**
     * Get list of subscriptions associated with user.
     *
     * If user handle is associated with some subscriptions, return subscriptionsAssociatedWithUser
     * else return all the subscriptions which are not associated with any user.
     *
     * @param userHandle user handle of the user
     * @return list of subscriptionInfo associated with the user.
     *
     * @throws SecurityException if the caller doesn't have permissions required.
     *
     */
    @Override
    @NonNull
    public List<SubscriptionInfo> getSubscriptionInfoListAssociatedWithUser(
            @NonNull UserHandle userHandle) {
        enforcePermissions("getSubscriptionInfoListAssociatedWithUser",
                Manifest.permission.MANAGE_SUBSCRIPTION_USER_ASSOCIATION);

        if (mFeatureFlags.enforceSubscriptionUserFilter()) {
            return getSubscriptionInfoStreamAsUser(userHandle)
                    .map(SubscriptionInfoInternal::toSubscriptionInfo)
                    .collect(Collectors.toList());
        }

        long token = Binder.clearCallingIdentity();
        try {
            List<SubscriptionInfoInternal> subInfoList =  mSubscriptionDatabaseManager
                    .getAllSubscriptions();
            if (subInfoList.isEmpty()) {
                return new ArrayList<>();
            }

            List<SubscriptionInfo> subscriptionsAssociatedWithUser = new ArrayList<>();
            List<SubscriptionInfo> subscriptionsWithNoAssociation = new ArrayList<>();
            for (SubscriptionInfoInternal subInfo : subInfoList) {
                if (subInfo.getUserId() == userHandle.getIdentifier()) {
                    // Store subscriptions whose user handle matches with required user handle.
                    subscriptionsAssociatedWithUser.add(subInfo.toSubscriptionInfo());
                } else if (subInfo.getUserId() == UserHandle.USER_NULL) {
                    // Store subscriptions whose user handle is set to null.
                    subscriptionsWithNoAssociation.add(subInfo.toSubscriptionInfo());
                }
            }

            UserManager userManager = mContext.getSystemService(UserManager.class);
            if ((userManager != null)
                    && (userManager.isManagedProfile(userHandle.getIdentifier()))) {
                // For work profile, return subscriptions associated only with work profile even
                // if it's empty.
                return subscriptionsAssociatedWithUser;
            }

            // For all other profiles, if subscriptionsAssociatedWithUser is empty return all
            // the subscriptionsWithNoAssociation.
            return subscriptionsAssociatedWithUser.isEmpty()
                    ? subscriptionsWithNoAssociation : subscriptionsAssociatedWithUser;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Get subscriptions accessible to the caller user.
     *
     * @param user The user to check.
     * @return a stream of accessible internal subscriptions.
     */
    @NonNull
    private Stream<SubscriptionInfoInternal> getSubscriptionInfoStreamAsUser(
            @NonNull final UserHandle user) {
        return mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                .filter(info -> isSubscriptionAssociatedWithUserInternal(
                        info, user.getIdentifier()));
    }

    /**
     * Called during setup wizard restore flow to attempt to restore the backed up sim-specific
     * configs to device for all existing SIMs in the subscription database {@link SimInfo}.
     * Internally, it will store the backup data in an internal file. This file will persist on
     * device for device's lifetime and will be used later on when a SIM is inserted to restore that
     * specific SIM's settings. End result is subscription database is modified to match any backed
     * up configs for the appropriate inserted SIMs.
     *
     * <p>
     * The {@link Uri} {@link SubscriptionManager#SIM_INFO_BACKUP_AND_RESTORE_CONTENT_URI} is
     * notified if any {@link SimInfo} entry is updated as the result of this method call.
     *
     * @param data with the sim specific configs to be backed up.
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    @Override
    public void restoreAllSimSpecificSettingsFromBackup(@NonNull byte[] data) {
        enforcePermissions("restoreAllSimSpecificSettingsFromBackup",
                Manifest.permission.MODIFY_PHONE_STATE);

        enforceTelephonyFeatureWithException(getCurrentPackageName(),
                "restoreAllSimSpecificSettingsFromBackup");

        long token = Binder.clearCallingIdentity();
        try {
            Bundle bundle = new Bundle();
            bundle.putByteArray(SubscriptionManager.KEY_SIM_SPECIFIC_SETTINGS_DATA, data);
            logl("restoreAllSimSpecificSettingsFromBackup");
            Bundle result = mContext.getContentResolver().call(
                    SubscriptionManager.SIM_INFO_BACKUP_AND_RESTORE_CONTENT_URI,
                    SubscriptionManager.RESTORE_SIM_SPECIFIC_SETTINGS_METHOD_NAME,
                    null, bundle);

            if (result != null && result.getBoolean(
                    SubscriptionManager.RESTORE_SIM_SPECIFIC_SETTINGS_DATABASE_UPDATED)) {
                logl("Sim specific settings changed the database.");
                mSubscriptionDatabaseManager.reloadDatabaseSync();
                Arrays.stream(PhoneFactory.getPhones())
                        .forEach(Phone::loadAllowedNetworksFromSubscriptionDatabase);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Register the callback for receiving information from {@link SubscriptionManagerService}.
     *
     * @param callback The callback.
     */
    public void registerCallback(@NonNull SubscriptionManagerServiceCallback callback) {
        mSubscriptionManagerServiceCallbacks.add(callback);
    }

    /**
     * Unregister the previously registered {@link SubscriptionManagerServiceCallback}.
     *
     * @param callback The callback to unregister.
     */
    public void unregisterCallback(@NonNull SubscriptionManagerServiceCallback callback) {
        mSubscriptionManagerServiceCallbacks.remove(callback);
    }

    /**
     * Enforce callers have any of the provided permissions.
     *
     * @param message Message to include in the exception.
     * @param permissions The permissions to enforce.
     *
     * @throws SecurityException if the caller does not have any permissions.
     */
    private void enforcePermissions(@Nullable String message, @NonNull String ...permissions) {
        if (!hasPermissions(permissions)) {
            throw new SecurityException(
                    message + ". Does not have any of the following permissions. "
                            + Arrays.toString(permissions));
        }
    }

    /**
     * Check have any of the permissions
     * @param permissions The permissions to check.
     * @return {@code true} if the caller has one of the given permissions.
     */
    private boolean hasPermissions(@NonNull String ...permissions) {
        for (String permission : permissions) {
            if (mContext.checkCallingOrSelfPermission(permission)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the {@link SubscriptionInfoInternal} by subscription id.
     *
     * @param subId The subscription id.
     *
     * @return The subscription info. {@code null} if not found.
     */
    @Nullable
    public SubscriptionInfoInternal getSubscriptionInfoInternal(int subId) {
        return mSubscriptionDatabaseManager.getSubscriptionInfoInternal(subId);
    }

    /**
     * Get the {@link SubscriptionInfo} by subscription id.
     *
     * @param subId The subscription id.
     *
     * @return The subscription info. {@code null} if not found.
     */
    @Nullable
    public SubscriptionInfo getSubscriptionInfo(int subId) {
        SubscriptionInfoInternal infoInternal = getSubscriptionInfoInternal(subId);
        return infoInternal != null ? infoInternal.toSubscriptionInfo() : null;
    }

    /**
     * Called when SIM becomes inactive.
     *
     * @param slotIndex The logical SIM slot index.
     * @param iccId iccId of the SIM in inactivate slot.
     */
    public void updateSimStateForInactivePort(int slotIndex, @NonNull String iccId) {
        mHandler.post(() -> {
            logl("updateSimStateForInactivePort: slotIndex=" + slotIndex + ", iccId="
                    + SubscriptionInfo.getPrintableId(iccId));
            if (mSlotIndexToSubId.containsKey(slotIndex)) {
                // Re-enable the UICC application , so it will be in enabled state when it becomes
                // active again. (Pre-U behavior)
                mSubscriptionDatabaseManager.setUiccApplicationsEnabled(
                        mSlotIndexToSubId.get(slotIndex), true);
                updateSubscription(slotIndex);
            }
            if (!TextUtils.isEmpty(iccId)) {
                // When port is inactive, sometimes valid iccid is present in the slot status,
                // hence update the portIndex. (Pre-U behavior)
                SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager
                        .getSubscriptionInfoInternalByIccId(getStrippedIccid(iccId));
                int subId;
                if (subInfo != null) {
                    subId = subInfo.getSubscriptionId();
                    log("updateSimStateForInactivePort: Found existing subscription. subId="
                            + subId);
                } else {
                    // If iccId is new, add a subscription record in the database so it can be
                    // activated later. (Pre-U behavior)
                    subId = insertSubscriptionInfo(getStrippedIccid(iccId),
                            SubscriptionManager.INVALID_SIM_SLOT_INDEX, "",
                            SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
                    mSubscriptionDatabaseManager.setDisplayName(subId,
                            mContext.getResources().getString(R.string.default_card_name, subId));
                    log("updateSimStateForInactivePort: Insert a new subscription for inactive SIM."
                            + " subId=" + subId);
                }
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    mSubscriptionDatabaseManager.setPortIndex(subId, getPortIndex(iccId));
                }
            }
        });
    }

    /**
     * Update SIM state. This method is supposed to be called by {@link UiccController} only.
     *
     * @param slotIndex The logical SIM slot index.
     * @param simState SIM state.
     * @param executor The executor to execute the callback.
     * @param updateCompleteCallback The callback to call when subscription manager service
     * completes subscription update. SIM state changed event will be broadcasted by
     * {@link UiccController} upon receiving callback.
     */
    public void updateSimState(int slotIndex, @SimState int simState,
            @Nullable @CallbackExecutor Executor executor,
            @Nullable Runnable updateCompleteCallback) {
        mHandler.post(() -> {
            mSimState[slotIndex] = simState;
            logl("updateSimState: slot " + slotIndex + " "
                    + TelephonyManager.simStateToString(simState));
            switch (simState) {
                case TelephonyManager.SIM_STATE_ABSENT:
                case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                case TelephonyManager.SIM_STATE_PERM_DISABLED:
                case TelephonyManager.SIM_STATE_CARD_IO_ERROR:
                case TelephonyManager.SIM_STATE_LOADED:
                    updateSubscription(slotIndex);
                    break;
                case TelephonyManager.SIM_STATE_NOT_READY:
                case TelephonyManager.SIM_STATE_READY:
                    updateEmbeddedSubscriptions();
                    updateSubscription(slotIndex);
                    break;
                case TelephonyManager.SIM_STATE_CARD_RESTRICTED:
                default:
                    // No specific things needed to be done. Just return and broadcast the SIM
                    // states.
                    break;
            }
            if (executor != null && updateCompleteCallback != null) {
                executor.execute(updateCompleteCallback);
            }
        });
    }

    /**
     * Get the calling package(s).
     *
     * @return The calling package(s).
     */
    @NonNull
    private String getCallingPackage() {
        if (UserHandle.isSameApp(Binder.getCallingUid(), Process.PHONE_UID)) {
            // Too many packages running with phone uid. Just return one here.
            return "com.android.phone";
        }
        return Arrays.toString(mContext.createContextAsUser(Binder.getCallingUserHandle(), 0)
                .getPackageManager().getPackagesForUid(Binder.getCallingUid()));
    }

    /**
     * Update the {@link SubscriptionInfo#isGroupDisabled()} bit for the opportunistic
     * subscriptions.
     *
     * If all primary (non-opportunistic) subscriptions in the group are deactivated
     * (unplugged pSIM or deactivated eSIM profile), we should disable this opportunistic
     * subscriptions.
     */
    @VisibleForTesting
    public void updateGroupDisabled() {
        List<SubscriptionInfo> activeSubscriptions = mSubscriptionDatabaseManager
                .getAllSubscriptions().stream()
                .filter(SubscriptionInfoInternal::isActive)
                .map(SubscriptionInfoInternal::toSubscriptionInfo)
                .collect(Collectors.toList());
        for (SubscriptionInfo oppSubInfo : getOpportunisticSubscriptions(
                mContext.getOpPackageName(), mContext.getFeatureId())) {
            boolean groupDisabled;
            if (mFeatureFlags.enableIsPrivateNetworkApi()) {
                groupDisabled = oppSubInfo.getGroupUuid() != null
                        && activeSubscriptions.stream().noneMatch(subInfo ->
                        !subInfo.isOpportunistic() && Objects.equals(oppSubInfo.getGroupUuid(),
                                subInfo.getGroupUuid()));
            } else {
                groupDisabled = activeSubscriptions.stream()
                        .noneMatch(subInfo -> !subInfo.isOpportunistic()
                                && Objects.equals(oppSubInfo.getGroupUuid(),
                                subInfo.getGroupUuid()));
            }
            mSubscriptionDatabaseManager.setGroupDisabled(
                    oppSubInfo.getSubscriptionId(), groupDisabled);
        }
    }



    /**
     * Set the transfer status of the subscriptionInfo that corresponds to subId.
     * @param subId The unique SubscriptionInfo key in database.
     * @param status The transfer status to change. This value must be one of the following.
     * {@link SubscriptionManager#TRANSFER_STATUS_NONE},
     * {@link SubscriptionManager#TRANSFER_STATUS_TRANSFERRED_OUT} or
     * {@link SubscriptionManager#TRANSFER_STATUS_CONVERTED}
     *
     */
    @Override
    @EnforcePermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void setTransferStatus(int subId, int status) {
        setTransferStatus_enforcePermission();
        if (mContext.checkCallingOrSelfPermission(
                Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must have WRITE_EMBEDDED_SUBSCRIPTIONS to"
                    + "setTransferStatus");
        }
        long token = Binder.clearCallingIdentity();
        try {
            mSubscriptionDatabaseManager.setTransferStatus(subId, status);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Set the enrollable subscription plans for a specific subscription.
     *
     * @param subId the subscriber this relationship applies to.
     * @param plans the array of the SubscriptionPlans.
     * @param expirationDurationMillis the duration after which the plans will be automatically
     *                                 cleared.
     * @param callingPackage the package name that called this function
     */
    @Override
    public void setEnrollableSubscriptionPlans(int subId, @NonNull SubscriptionPlan[] plans,
            long expirationDurationMillis, @NonNull String callingPackage) {
        // Check permissions (Modify Phone State or Carrier Privilege)
        enforceEnrollableSubscriptionPlanAccess(subId, Binder.getCallingUid(), callingPackage,
                "setEnrollableSubscriptionPlans", true /* isWrite */);

        // Verify plans are not null and valid.
        for (SubscriptionPlan plan : plans) {
            Objects.requireNonNull(plan);
        }

        // 2. Post to Handler (Run on Main/Worker Thread)
        mHandler.post(() -> {
            setEnrollableSubscriptionPlansInternal(subId, plans, expirationDurationMillis,
                    callingPackage);
        });
    }

    /**
     * Internal method to update enrollable plans, running on the handler thread.
     */
    private void setEnrollableSubscriptionPlansInternal(int subId,
            @NonNull SubscriptionPlan[] plans, long expirationDurationMillis,
            @NonNull String callingPackage) {
        long expirationTime =
                (expirationDurationMillis > 0)
                        ? System.currentTimeMillis() + expirationDurationMillis
                        : 0;

        log("set enrollable SubscriptionPlans for subid:"
                + subId + " duration:" + expirationDurationMillis + "ms");

        // 1. Store the plans, owner and expiration time.
        mEnrollableSubscriptionPlans.put(subId, plans);
        mEnrollableSubscriptionPlansOwner.put(subId, callingPackage);
        mEnrollablePlanExpirationTime.put(subId, expirationTime);

        // Set a timer and perform expired data cleanup.
        rescheduleAllPlanExpirations();

        // Write plans to persist the state to disk.
        writeEnrollableSubscriptionPlans();

        // 3. Notify Listeners
        broadcastEnrollableSubscriptionPlansChanged(subId);
    }

    /**
     * Get the enrollable subscription plans for the given subscription id.
     *
     * @param subId the subscriber to get the subscription plans for.
     * @param callingPackage the name of the package making the call.
     * @return the array of enrollable subscription plans, or null if not found or access denied.
     */
    @Override
    @Nullable
    public SubscriptionPlan[] getEnrollableSubscriptionPlans(
            int subId, @NonNull String callingPackage) {
        // Check permissions
        enforceEnrollableSubscriptionPlanAccess(subId, Binder.getCallingUid(), callingPackage,
                "getEnrollableSubscriptionPlans", false /* isWrite */);

        return mEnrollableSubscriptionPlans.get(subId);
    }

    /**
     * Get the package name of the app that owns the enrollable subscription plans.
     *
     * @param subId the subscriber to get the owner for.
     * @return the package name of the app that owns the enrollable plans, or null if not found.
     */
    @Override
    @Nullable
    public String getEnrollableSubscriptionPlansOwner(int subId) {
        if (UserHandle.getCallingAppId() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException();
        }
        return mEnrollableSubscriptionPlansOwner.get(subId);
    }

    /**
     * Enforce permissions for accessing enrollable plans.
     * <p>
     * Access is granted if the caller meets ANY of the following criteria:
     * <ol>
     *     <li>Has Carrier Privileges for the subscription.</li>
     *     <li>Is the delegated Carrier Service for the subscription.</li>
     *     <li>Is the default Carrier Service for the device.</li>
     *     <li>Is the current owner of the plans (i.e., the app that set them).</li>
     *     <li>Holds the {@code MANAGE_SUBSCRIPTION_PLANS} permission.</li>
     * </ol>
     *
     * @param subId The subscription ID.
     * @param callingUid The UID of the caller.
     * @param callingPackage The package name of the caller.
     * @param message The message to include in any security exception.
     */
    private void enforceEnrollableSubscriptionPlanAccess(int subId, int callingUid,
            @NonNull String callingPackage, @NonNull String message, boolean isWrite) {
        // 0. Allow System and Phone (Radio) explicitly.
        // They are trusted components and should always have access.
        int appId = UserHandle.getAppId(callingUid);
        if (appId == Process.SYSTEM_UID || appId == Process.PHONE_UID) {
            return;
        }

        // 1. Verify the caller's package name matches their UID.
        try {
            int packageUid = mPackageManager.getPackageUid(callingPackage, 0);
            // Use isSameApp to handle multi-user scenarios (ignores user ID, checks app ID)
            if (!isSameAppIncludingPccUid(packageUid, callingUid)) {
                throw new SecurityException("Package " + callingPackage + " does not belong to uid "
                        + callingUid);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException("Package " + callingPackage + " not found");
        }

        // 2. Check Carrier Privileges, Delegated Access, and Default Carrier Service.
        long token = Binder.clearCallingIdentity();
        try {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class)
                    .createForSubscriptionId(subId);

            // 2a. Check Carrier Privileges
            if (tm != null && tm.checkCarrierPrivilegesForPackage(callingPackage)
                    == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return;
            }

            CarrierConfigManager configManager =
                    mContext.getSystemService(CarrierConfigManager.class);
            PersistableBundle config = (configManager != null)
                    ? configManager.getConfigForSubId(
                            subId, CarrierConfigManager.KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING)
                    : null;

            if (config != null) {
                // 2b. Check Delegated Access
                String overridePackage = config.getString(
                        CarrierConfigManager.KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING, null);
                if (!TextUtils.isEmpty(overridePackage)
                        && Objects.equals(overridePackage, callingPackage)) {
                    return;
                }
            }

            // 2c. Check Default Carrier Service
            if (configManager != null) {
                String defaultPackage = configManager.getDefaultCarrierServicePackageName();
                if (!TextUtils.isEmpty(defaultPackage)
                        && Objects.equals(defaultPackage, callingPackage)) {
                    return;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // 3. Check if the caller is the owner of the plans (if they exist).
        String ownerPackage = mEnrollableSubscriptionPlansOwner.get(subId);
        if (ownerPackage != null && ownerPackage.equals(callingPackage)) {
            return;
        }

        // 4. Permission Checks
        // MANAGE_SUBSCRIPTION_PLANS allows for read or write.
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_SUBSCRIPTION_PLANS)
                        == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // READ_SUBSCRIPTION_PLANS allows only for read.
        if (!isWrite && mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.READ_SUBSCRIPTION_PLANS)
                        == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        throw new SecurityException(message + ": Caller " + callingPackage
                + " does not meet required permissions (Carrier Privilege, Plan Owner, or "
                + "MANAGE_SUBSCRIPTION_PLANS)");
    }

    /**
     * helper method that compares the uid1 to uid2.
     * <p>
     * returns true if the uid1 matches the uid2 including pcc uids.
     */
    private boolean isSameAppIncludingPccUid(int uid1, int uid2) {
        int appUid1 = uid1;
        if (Flags.enablePccFrameworkSupport() && Process.isPrivateComputeCoreUid(uid1)) {
            appUid1 = mPackageManager.getAppUidForPrivateComputeCoreUid(uid1);
        }
        int appUid2 = uid2;
        if (Flags.enablePccFrameworkSupport() && Process.isPrivateComputeCoreUid(uid2)) {
            appUid2 = mPackageManager.getAppUidForPrivateComputeCoreUid(uid2);
        }
        return UserHandle.isSameApp(appUid1, appUid2);
    }

    /**
     * Helper to broadcast ACTION_ENROLLABLE_SUBSCRIPTION_PLANS_CHANGED.
     */
    private void broadcastEnrollableSubscriptionPlansChanged(int subId) {
        Intent intent = new Intent(
                SubscriptionManager.ACTION_ENROLLABLE_SUBSCRIPTION_PLANS_CHANGED);
        SubscriptionManager.putSubscriptionIdExtra(intent, subId);
        log("broadcastEnrollableSubscriptionPlansChanged for subId:" + subId);
        mContext.sendBroadcast(intent, android.Manifest.permission.READ_SUBSCRIPTION_PLANS);
    }

    /**
     * Reschedules expiration timers based on the current system time.
     * Uses mEnrollableSubscriptionPlans as the source of truth to avoid synchronization issues.
     */
    private void rescheduleAllPlanExpirations() {
        log("reschedule all enrollable SubscriptionPlans");

        long currentTime = System.currentTimeMillis();
        List<Integer> expiredSubIds = new ArrayList<>();

        // Cancel all existing timers and clear the map. (Clean Slate)
        // Prevent zombie timers (timers that run without a plan) from occurring.
        for (Runnable r : mEnrollablePlanExpirationRunnables.values()) {
            mHandler.removeCallbacks(r);
        }
        mEnrollablePlanExpirationRunnables.clear();

        // Reset the timer by iterating based on the SubscriptionPlans.
        for (int subId : mEnrollableSubscriptionPlans.keySet()) {
            // Skip if it is a permanent plan with no expiration time
            Long expirationTime = mEnrollablePlanExpirationTime.get(subId);
            if (expirationTime == null || expirationTime == 0) {
                continue;
            }

            long remainingDuration = expirationTime - currentTime;
            if (remainingDuration <= 0) {
                // Already expired -> get rid of plans
                logl("Plan for subId=" + subId + " expired (duration=" + remainingDuration + ")");
                removeEnrollablePlansInMemory(subId);
                expiredSubIds.add(subId);
            } else {
                // Still valid -> Register a new timer
                logl("Rescheduling expiration for subId="
                        + subId + " in " + remainingDuration + "ms");
                Runnable newRunnable = () -> handlePlanExpiration(subId);
                mEnrollablePlanExpirationRunnables.put(subId, newRunnable);
                mHandler.postDelayed(newRunnable, remainingDuration);
            }
        }

        // If found expired plans, update xml and broadcast them.
        if (!expiredSubIds.isEmpty()) {
            writeEnrollableSubscriptionPlans();

            // Broadcast 전송
            for (int subId : expiredSubIds) {
                broadcastEnrollableSubscriptionPlansChanged(subId);
            }
        }
    }

    /**
     * Handles the expiration of plans for a single subscription.
     * Invoked by the Handler when the expiration time is reached.
     */
    private void handlePlanExpiration(int subId) {
        if (removeEnrollablePlansInMemory(subId)) {
            logl("Plan for subId=" + subId + " expired.");
            writeEnrollableSubscriptionPlans();
            broadcastEnrollableSubscriptionPlansChanged(subId);
        }
    }

    /**
     * Removes enrollable plans from memory maps and cancels the timer.
     * <p>This method does NOT write to disk or send broadcasts.
     *
     * @return {@code true} if plans existed and were removed, {@code false} otherwise.
     */
    private boolean removeEnrollablePlansInMemory(int subId) {
        if (!mEnrollableSubscriptionPlans.containsKey(subId)) {
            return false;
        }
        logl("removing enrollable SubscriptionPlans for subId=" + subId);
        mEnrollableSubscriptionPlans.remove(subId);
        mEnrollableSubscriptionPlansOwner.remove(subId);
        mEnrollablePlanExpirationTime.remove(subId);
        Runnable runnable = mEnrollablePlanExpirationRunnables.remove(subId);
        if (runnable != null) {
            mHandler.removeCallbacks(runnable);
        }
        return true;
    }

    /**
     * Returns the device protected storage directory.
     *
     * <p>This directory is used to store configuration files that need to persist across reboots.
     *
     * @return The directory file object.
     */
    @Nullable
    private File getProtectedStorageDir() {
        Context deContext = mContext.createDeviceProtectedStorageContext();
        return deContext == null ? null : deContext.getFilesDir();
    }

    /**
     * Reads enrollable SubscriptionPlans from the XML file into memory.
     *
     * <p>This method is typically called during the service initialization (boot time). It parses
     * the XML file and populates {@link #mEnrollableSubscriptionPlans}, {@link
     * #mEnrollableSubscriptionPlansOwner}, and {@link #mEnrollablePlanExpirationTime}.
     */
    private void readEnrollableSubscriptionPlans() {
        if (!mFeatureFlags.subscriptionPlanEnhancement()) {
            return;
        }
        if (mEnrollablePlansFile == null) {
            loge("can't read enrollableSubscriptionPlans. File is null");
            return;
        }
        synchronized (mEnrollablePlansFile) {
            if (!mEnrollablePlansFile.exists()) {
                logl("No persisted enrollable plans file found.");
                return;
            }

            logl("read EnrollableSubscriptionPlans");

            // Clear existing in-memory data before loading from disk.
            mEnrollableSubscriptionPlans.clear();
            mEnrollableSubscriptionPlansOwner.clear();
            mEnrollablePlanExpirationTime.clear();

            // Load enrollable subscription plans from XML
            try (FileInputStream fis = mEnrollablePlansFile.openRead()) {
                TypedXmlPullParser in = new BinaryXmlPullParser(); // 직접 생성
                in.setInput(fis, null);

                int type;
                while ((type = in.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG) continue;

                    final String tag = in.getName();
                    if (TAG_SUB_PLANS.equals(tag)) {
                        readEnrollableSubscriptionPlansForSubscriptionLocked(in);
                    }
                }
            } catch (Exception e) {
                loge("Failed to read enrollable plans: " + e);
            }

            // After all loads are complete, set a timer and perform expired data cleanup. If any of
            // the read data is expired, it will be automatically deleted and the file updated.
            rescheduleAllPlanExpirations();
        }
    }

    /**
     * Parses a single {@code <sub-plans>} block from the XML, which contains all enrollable plans
     * for a single subscription id.
     *
     * <p>This method reads the attributes of the group (subId, owner, expiration) and then iterates
     * through the child {@code <plan>} tags to build the full list of plans. Finally, it populates
     * the internal memory caches with the parsed data.
     *
     * @param in The XML parser positioned at the start of the {@code <sub-plans>} tag.
     * @throws IOException If an I/O error occurs.
     * @throws XmlPullParserException If an XML parsing error occurs.
     */
    private void readEnrollableSubscriptionPlansForSubscriptionLocked(TypedXmlPullParser in)
            throws IOException, XmlPullParserException {
        long currentTime = System.currentTimeMillis();
        int subId = in.getAttributeInt(null, ATTR_SUB_ID);
        String owner = in.getAttributeValue(null, ATTR_OWNER);
        long expirationTime = in.getAttributeLong(null, ATTR_EXPIRATION_TIME, 0);
        long duration = expirationTime - currentTime;

        // Check for expiration.
        if (duration <= 0) {
            logl("Skipping reading expired/volatile plans for subId=" + subId);
            return;
        }

        List<SubscriptionPlan> plansList = new ArrayList<>();
        int type;
        int outerDepth = in.getDepth();
        // Loop until the end of the current <sub-plans> tag
        while ((type = in.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || in.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) continue;

            if (TAG_PLAN.equals(in.getName())) {
                SubscriptionPlan plan = readSingleSubscriptionPlan(in);
                if (plan != null) {
                    plansList.add(plan);
                }
            }
        }

        // If valid plans were found, restore them to the in-memory maps.
        if (!plansList.isEmpty()) {
            mEnrollableSubscriptionPlans.put(subId, plansList.toArray(new SubscriptionPlan[0]));
            if (owner != null) mEnrollableSubscriptionPlansOwner.put(subId, owner);

            if (expirationTime > 0) {
                // Runnable registration will be done in the reschedule function.
                mEnrollablePlanExpirationTime.put(subId, expirationTime);
            }
        }
    }

    /** Placeholder: Read SubscriptionPlan fields from XML attributes. */
    @Nullable
    private SubscriptionPlan readSingleSubscriptionPlan(TypedXmlPullParser in) {
        try {
            SubscriptionPlan.Builder builder;

            // Read Cycle Rule (RecurrenceRule)
            String cycleStart = in.getAttributeValue(null, ATTR_CYCLE_START);
            String cycleEnd = in.getAttributeValue(null, ATTR_CYCLE_END);
            String cyclePeriod = in.getAttributeValue(null, ATTR_CYCLE_PERIOD);

            // Parse ZonedDateTime and Period using RecurrenceRule helpers or standard parsing
            ZonedDateTime start =
                    (cycleStart != null) ? RecurrenceRule.convertZonedDateTime(cycleStart) : null;
            ZonedDateTime end =
                    (cycleEnd != null) ? RecurrenceRule.convertZonedDateTime(cycleEnd) : null;
            Period period =
                    (cyclePeriod != null) ? RecurrenceRule.convertPeriod(cyclePeriod) : null;

            if (period != null) {
                builder = SubscriptionPlan.Builder.createRecurring(start, period);
            } else {
                builder = SubscriptionPlan.Builder.createNonrecurring(start, end);
            }

            // Read Title & Summary
            String title = in.getAttributeValue(null, ATTR_TITLE);
            if (title != null) builder.setTitle(title);

            String summary = in.getAttributeValue(null, ATTR_SUMMARY);
            if (summary != null) builder.setSummary(summary);

            // Read Data Limit & Usage
            long limitBytes =
                    in.getAttributeLong(null, ATTR_LIMIT_BYTES, SubscriptionPlan.BYTES_UNKNOWN);
            int limitBehavior =
                    in.getAttributeInt(
                            null, ATTR_LIMIT_BEHAVIOR, SubscriptionPlan.LIMIT_BEHAVIOR_UNKNOWN);
            if (limitBytes != SubscriptionPlan.BYTES_UNKNOWN
                    || limitBehavior != SubscriptionPlan.LIMIT_BEHAVIOR_UNKNOWN) {
                builder.setDataLimit(limitBytes, limitBehavior);
            }

            long usageBytes =
                    in.getAttributeLong(null, ATTR_USAGE_BYTES, SubscriptionPlan.BYTES_UNKNOWN);
            long usageTime =
                    in.getAttributeLong(null, ATTR_USAGE_TIME, SubscriptionPlan.TIME_UNKNOWN);
            if (usageBytes != SubscriptionPlan.BYTES_UNKNOWN
                    || usageTime != SubscriptionPlan.TIME_UNKNOWN) {
                builder.setDataUsage(usageBytes, usageTime);
            }

            // Read Network Types (Comma separated integers)
            String networkTypesStr = in.getAttributeValue(null, ATTR_NETWORK_TYPES);
            if (networkTypesStr != null && !networkTypesStr.isEmpty()) {
                try {
                    int[] networkTypes =
                            Arrays.stream(networkTypesStr.split(","))
                                    .mapToInt(Integer::parseInt)
                                    .toArray();
                    builder.setNetworkTypes(networkTypes);
                } catch (NumberFormatException e) {
                    loge("Failed to parse network types: " + networkTypesStr);
                }
            }

            // Read Subscription Status (New field)
            int status =
                    in.getAttributeInt(
                            null,
                            ATTR_SUBSCRIPTION_STATUS,
                            SubscriptionPlan.SUBSCRIPTION_STATUS_UNKNOWN);
            if (status != SubscriptionPlan.SUBSCRIPTION_STATUS_UNKNOWN) {
                builder.setSubscriptionStatus(status);
            }

            // Read Plan ID
            int planId = in.getAttributeInt(null, ATTR_PLAN_ID, SubscriptionPlan.UNSPECIFIED_ID);
            if (planId != SubscriptionPlan.UNSPECIFIED_ID) {
                builder.setId(planId);
            }

            // Read Plan Types
            String planTypesStr = in.getAttributeValue(null, ATTR_PLAN_TYPES);
            if (planTypesStr != null && !planTypesStr.isEmpty()) {
                try {
                    int[] types = Arrays.stream(planTypesStr.split(","))
                            .mapToInt(Integer::parseInt)
                            .toArray();
                    builder.setTypes(types);
                } catch (NumberFormatException e) {
                    loge("Failed to parse plan types: " + planTypesStr);
                }
            }

            // Read Data Usage Reset Time
            String resetTimeStr = in.getAttributeValue(null, ATTR_RESET_TIME);
            if (resetTimeStr != null) {
                builder.setDataUsageResetTime(RecurrenceRule.convertZonedDateTime(resetTimeStr));
            }

            // Read Streaming Bandwidth
            long downlink = in.getAttributeLong(null, ATTR_DOWNLINK_KBPS,
                    SubscriptionPlan.BITRATE_UNKNOWN);
            if (downlink != SubscriptionPlan.BITRATE_UNKNOWN) {
                builder.setStreamingAppMaxDownlinkKbps(downlink);
            }
            long uplink = in.getAttributeLong(null, ATTR_UPLINK_KBPS,
                    SubscriptionPlan.BITRATE_UNKNOWN);
            if (uplink != SubscriptionPlan.BITRATE_UNKNOWN) {
                builder.setStreamingAppMaxUplinkKbps(uplink);
            }

            return builder.build();

        } catch (Exception e) {
            loge("Failed to reconstruct SubscriptionPlan from XML: " + e);
            return null;
        }
    }

    /**
     * Writes the current in-memory enrollable SubscriptionPlans to the XML file.
     *
     * <p>This method persists the state of {@link #mEnrollableSubscriptionPlans}, including the
     * owner and expiration time for each subscription.
     */
    private void writeEnrollableSubscriptionPlans() {
        if (!mFeatureFlags.subscriptionPlanEnhancement()) {
            return;
        }
        if (mEnrollablePlansFile == null) {
            loge("can't write enrollableSubscriptionPlans. File is null");
            return;
        }
        synchronized (mEnrollablePlansFile) {
            logl("write EnrollableSubscriptionPlans");

            FileOutputStream fos = null;
            try {
                fos = mEnrollablePlansFile.startWrite();
                TypedXmlSerializer out = new BinaryXmlSerializer();
                out.setOutput(fos, "utf-8");

                out.startDocument(null, true);
                out.startTag(null, TAG_ENROLLABLE_PLANS);

                for (Integer subId : mEnrollableSubscriptionPlans.keySet()) {
                    SubscriptionPlan[] plans = mEnrollableSubscriptionPlans.get(subId);
                    String owner = mEnrollableSubscriptionPlansOwner.get(subId);
                    Long expirationTime = mEnrollablePlanExpirationTime.get(subId);

                    if (expirationTime != null && expirationTime == 0) {
                        continue; // skip writing volatile enrollable plans into the XML
                    }

                    if (plans != null && plans.length > 0) {
                        out.startTag(null, TAG_SUB_PLANS);
                        out.attributeInt(null, ATTR_SUB_ID, subId);
                        if (owner != null) {
                            out.attribute(null, ATTR_OWNER, owner);
                        }
                        if (expirationTime != null) {
                            out.attributeLong(null, ATTR_EXPIRATION_TIME, expirationTime);
                        }

                        for (SubscriptionPlan plan : plans) {
                            writeSingleSubscriptionPlan(out, plan);
                        }
                        out.endTag(null, TAG_SUB_PLANS);
                    }
                }

                out.endTag(null, TAG_ENROLLABLE_PLANS);
                out.endDocument();
                mEnrollablePlansFile.finishWrite(fos);
            } catch (Exception e) {
                loge("Failed to write enrollable plans: " + e);
                if (fos != null) mEnrollablePlansFile.failWrite(fos);
            }
        }
    }

    /**
     * Writes a single SubscriptionPlan to the XML serializer.
     *
     * <p>Serializes all fields of the plan into attributes of the {@link #TAG_PLAN} tag.
     *
     * @param out The XML serializer.
     * @param plan The SubscriptionPlan object to write.
     */
    private void writeSingleSubscriptionPlan(TypedXmlSerializer out, SubscriptionPlan plan)
            throws IOException {
        out.startTag(null, TAG_PLAN);

        // 1. Write Cycle Rule
        RecurrenceRule cycleRule = plan.getCycleRule();
        if (cycleRule.start != null) {
            out.attribute(
                    null, ATTR_CYCLE_START, RecurrenceRule.convertZonedDateTime(cycleRule.start));
        }
        if (cycleRule.end != null) {
            out.attribute(null, ATTR_CYCLE_END, RecurrenceRule.convertZonedDateTime(cycleRule.end));
        }
        if (cycleRule.period != null) {
            out.attribute(null, ATTR_CYCLE_PERIOD, RecurrenceRule.convertPeriod(cycleRule.period));
        }

        // 2. Write Title & Summary
        if (plan.getTitle() != null) {
            out.attribute(null, ATTR_TITLE, plan.getTitle().toString());
        }
        if (plan.getSummary() != null) {
            out.attribute(null, ATTR_SUMMARY, plan.getSummary().toString());
        }

        // 3. Write Limit & Usage
        if (plan.getDataLimitBytes() != SubscriptionPlan.BYTES_UNKNOWN) {
            out.attributeLong(null, ATTR_LIMIT_BYTES, plan.getDataLimitBytes());
        }
        if (plan.getDataLimitBehavior() != SubscriptionPlan.LIMIT_BEHAVIOR_UNKNOWN) {
            out.attributeInt(null, ATTR_LIMIT_BEHAVIOR, plan.getDataLimitBehavior());
        }
        if (plan.getDataUsageBytes() != SubscriptionPlan.BYTES_UNKNOWN) {
            out.attributeLong(null, ATTR_USAGE_BYTES, plan.getDataUsageBytes());
        }
        if (plan.getDataUsageTime() != SubscriptionPlan.TIME_UNKNOWN) {
            out.attributeLong(null, ATTR_USAGE_TIME, plan.getDataUsageTime());
        }

        // 4. Write Network Types (as Comma Separated String for simplicity in single tag)
        int[] networkTypes = plan.getNetworkTypes();
        if (networkTypes != null && networkTypes.length > 0) {
            // e.g., "13,20"
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < networkTypes.length; i++) {
                sb.append(networkTypes[i]);
                if (i < networkTypes.length - 1) sb.append(",");
            }
            out.attribute(null, ATTR_NETWORK_TYPES, sb.toString());
        }

        // 5. Write Subscription Status
        if (plan.getSubscriptionStatus() != SubscriptionPlan.SUBSCRIPTION_STATUS_UNKNOWN) {
            out.attributeInt(null, ATTR_SUBSCRIPTION_STATUS, plan.getSubscriptionStatus());
        }

        // 6. Write Plan ID
        if (plan.getId() != SubscriptionPlan.UNSPECIFIED_ID) {
            out.attributeInt(null, ATTR_PLAN_ID, plan.getId());
        }

        // 7. Write Plan Types
        Set<Integer> types = plan.getTypes();
        out.attribute(null, ATTR_PLAN_TYPES, TextUtils.join(",", types));

        // 8. Write Data Usage Reset Time
        if (plan.getDataUsageResetTime() != null) {
            out.attribute(null, ATTR_RESET_TIME,
                    RecurrenceRule.convertZonedDateTime(plan.getDataUsageResetTime()));
        }

        // 9. Write Streaming Bandwidth
        if (plan.getStreamingAppMaxDownlinkKbps() != SubscriptionPlan.BITRATE_UNKNOWN) {
            out.attributeLong(null, ATTR_DOWNLINK_KBPS, plan.getStreamingAppMaxDownlinkKbps());
        }
        if (plan.getStreamingAppMaxUplinkKbps() != SubscriptionPlan.BITRATE_UNKNOWN) {
            out.attributeLong(null, ATTR_UPLINK_KBPS, plan.getStreamingAppMaxUplinkKbps());
        }

        out.endTag(null, TAG_PLAN);
    }

    /**
     * Save the satellite entitlement Info in the subscription database.
     *
     * @param subId                     Subscription ID.
     * @param allowedPlmnList           Plmn [MCC+MNC] list of codes to determine if satellite
     *                                  communication is allowed. Ex : "123123,12310".
     * @param barredPlmnList            Plmn [MCC+MNC] list of codes to determine if satellite
     *                                  communication is not allowed.  Ex : "123123,12310".
     * @param plmnDataPlanMap           data plan per plmn of type
     *                                  {@link SatelliteController.SatelliteDataPlan}.
     *                                  Ex : {"302820":0, "31026":1}
     * @param plmnServiceTypeMap        list of supported services per plmn of type
     *                                  {@link NetworkRegistrationInfo.ServiceType}).
     *                                  Ex : {"302820":[1,3],"31026":[2,3]}
     * @param plmnDataServicePolicyMap  data support mode per plmn map of types
     *                                  {@link CarrierConfigManager.SATELLITE_DATA_SUPPORT_MODE}.
     *                                  Ex : {"302820":2, "31026":1}
     * @param plmnVoiceServicePolicyMap voice support mode per plmn map of types
     *                                  {@link CarrierConfigManager.SATELLITE_DATA_SUPPORT_MODE}.
     *                                  Ex : {"302820":2, "31026":1}.
     */
    public void setSatelliteEntitlementInfo(int subId,
            @NonNull List<String> allowedPlmnList,
            @Nullable List<String> barredPlmnList,
            @Nullable Map<String, Integer> plmnDataPlanMap,
            @Nullable Map<String, List<Integer>> plmnServiceTypeMap,
            @Nullable Map<String, Integer> plmnDataServicePolicyMap,
            @Nullable Map<String, Integer> plmnVoiceServicePolicyMap) {
        try {
            mSubscriptionDatabaseManager.setSatelliteEntitlementInfo(
                    subId, allowedPlmnList, barredPlmnList, plmnDataPlanMap, plmnServiceTypeMap,
                    plmnDataServicePolicyMap, plmnVoiceServicePolicyMap);
        } catch (IllegalArgumentException e) {
            loge("setSatelliteEntitlementPlmnList: invalid subId=" + subId);
        }
    }

    /**
     * Get the satellite entitlement plmn list value from the subscription database.
     *
     * @param subId subscription id.
     * @return satellite entitlement plmn list
     */
    @NonNull
    public List<String> getSatelliteEntitlementPlmnList(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(
                subId);

        return Optional.ofNullable(subInfo)
                .map(SubscriptionInfoInternal::getSatelliteEntitlementPlmns)
                .filter(s -> !s.isEmpty())
                .map(s -> Arrays.stream(s.split(",")).collect(Collectors.toList()))
                .orElse(new ArrayList<>());
    }

    /**
     * Set the satellite ESOS supported value in the subscription database.
     *
     * @param subId subscription id.
     * @param isSatelliteESOSSupported {@code true} satellite ESOS supported true.
     */
    public void setSatelliteESOSSupported(int subId, @NonNull boolean isSatelliteESOSSupported) {
        try {
            mSubscriptionDatabaseManager.setSatelliteESOSSupported(subId,
                    isSatelliteESOSSupported ? 1 : 0);
        } catch (IllegalArgumentException e) {
            loge("setSatelliteESOSSupported: invalid subId=" + subId);
        }
    }

    /**
     * Set whether the subscription is provisioned for OEM-enabled or carrier roaming NB-IOT
     * satellite service or not.
     *
     * @param subId subscription id.
     * @param isSatelliteProvisionedForNonIpDatagram {@code true} if subId is provisioned.
     * {@code false} otherwise.
     */
    public void setIsSatelliteProvisionedForNonIpDatagram(int subId,
            boolean isSatelliteProvisionedForNonIpDatagram) {
        try {
            mSubscriptionDatabaseManager.setIsSatelliteProvisionedForNonIpDatagram(
                    subId, (isSatelliteProvisionedForNonIpDatagram ? 1 : 0));
        } catch (IllegalArgumentException e) {
            loge("setIsSatelliteProvisionedForNonIpDatagram: invalid subId=" + subId);
        }
    }

    /**
     * Get the satellite ESOS supported value in the subscription database.
     *
     * @param subId subscription id.
     * @return the satellite ESOS supported true or false.
     */
    public boolean getSatelliteESOSSupported(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(
                subId);
        if (subInfo == null) {
            return false;
        }

        return subInfo.getSatelliteESOSSupported() == 1;
    }

    /**
     * Get the satellite entitlement barred plmn list value from the subscription database.
     *
     * @param subId subscription id.
     * @return satellite entitlement plmn list. Ex : "123123,12310".
     */
    @NonNull
    public List<String> getSatelliteEntitlementBarredPlmnList(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(
                subId);

        return Optional.ofNullable(subInfo).map(
                SubscriptionInfoInternal::getSatelliteEntitlementBarredPlmnsList).filter(
                s -> !s.isEmpty()).map(
                s -> Arrays.stream(s.split(",")).collect(Collectors.toList())).orElse(
                new ArrayList<>());
    }

    /**
     * Get the satellite entitlement data plan for the plmns value from the subscription database.
     *
     * @param subId subscription id.
     * @return satellite entitlement data plan map. Ex : {"302820":0, "31026":1}.
     */
    @NonNull
    public Map<String, Integer> getSatelliteEntitlementDataPlanForPlmns(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(
                subId);
        if (subInfo == null) {
            return new HashMap<>();
        }
        String plmnDataPlanJson = subInfo.getSatelliteEntitlementDataPlanForPlmns();
        return deSerializeCVToMap(plmnDataPlanJson);
    }

    /**
     * Get the satellite entitlement services for the plmns value from the subscription database.
     *
     * @param subId subscription id.
     * @return satellite entitlement services map. Ex : {"302820":[1,3],"31026":[2,3]}.
     */
    @NonNull
    public Map<String, List<Integer>> getSatelliteEntitlementPlmnServiceTypeMap(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(
                subId);
        if (subInfo == null) {
            return new HashMap<>();
        }
        String plmnDataPlanJson = subInfo.getSatelliteEntitlementPlmnsServiceTypes();
        return deSerializeCVToMapList(plmnDataPlanJson);
    }

    /**
     * Get the satellite entitlement data service policy for plmns  value from the subscription
     * database.
     *
     * @param subId subscription id.
     * @return satellite entitlement data service policy map. Ex : {"302820":2, "31026":1}.
     */
    @NonNull
    public Map<String, Integer> getSatelliteEntitlementPlmnDataServicePolicy(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(
                subId);
        if (subInfo == null) {
            return new HashMap<>();
        }
        String plmnDataPolicyJson = subInfo.getSatellitePlmnsDataServicePolicy();
        return deSerializeCVToMap(plmnDataPolicyJson);
    }

    /**
     * Get the satellite entitlement voice service policy for plmns  value from the subscription
     * database.
     *
     * @param subId subscription id.
     * @return satellite entitlement voice service policy map. Ex : {"302820":2, "31026":1}.
     */
    @NonNull
    public Map<String, Integer> getSatelliteEntitlementPlmnVoiceServicePolicy(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(
                subId);
        if (subInfo == null) {
            return new HashMap<>();
        }
        String plmnVoicePolicyJson = subInfo.getSatellitePlmnsVoiceServicePolicy();
        return deSerializeCVToMap(plmnVoicePolicyJson);
    }

    /**
     * Get whether the subscription is provisioned for OEM-enabled or carrier roaming NB-IOT
     * satellite service or not.
     *
     * @param subId subscription id.
     * @return {@code true} if it is provisioned for oem satellite service. {@code false} otherwise.
     */
    public boolean isSatelliteProvisionedForNonIpDatagram(int subId) {
        SubscriptionInfoInternal subInfo = mSubscriptionDatabaseManager.getSubscriptionInfoInternal(
                subId);
        if (subInfo == null) {
            return false;
        }

        return subInfo.getIsSatelliteProvisionedForNonIpDatagram() == 1;
    }

    /**
     * checks whether esim bootstrap is activated for any of the available active subscription info
     * list.
     *
     * @return {@code true} if esim bootstrap is activated for any of the active subscription,
     * else {@code false}
     *
     */
    public boolean isEsimBootStrapProvisioningActivated() {
        List<SubscriptionInfo> activeSubInfos =
                getActiveSubscriptionInfoList(mContext.getOpPackageName(),
                        mContext.getAttributionTag(), true/*isForAllProfile*/);

        return activeSubInfos.stream().anyMatch(subInfo -> subInfo != null
                && subInfo.getProfileClass() == SubscriptionManager.PROFILE_CLASS_PROVISIONING);
    }

    /**
     * checks whether esim bootstrap is activated for the subscription.
     *
     * @return {@code true} if esim bootstrap is activated for sub id else {@code false}
     *
     */
    public boolean isEsimBootStrapProvisioningActiveForSubId(int subId) {
        SubscriptionInfoInternal subInfo = getSubscriptionInfoInternal(subId);
        return subInfo != null
                && subInfo.getProfileClass() == SubscriptionManager.PROFILE_CLASS_PROVISIONING;
    }

    /**
     * Get the current calling package name.
     * @return the current calling package name
     */
    @Nullable
    private String getCurrentPackageName() {
        PackageManager pm = mContext.createContextAsUser(Binder.getCallingUserHandle(), 0)
                .getPackageManager();
        if (pm == null) return null;
        String[] callingPackageNames = pm.getPackagesForUid(Binder.getCallingUid());
        return (callingPackageNames == null) ? null : callingPackageNames[0];
    }

    /**
     * Make sure the device has required telephony feature
     *
     * @throws UnsupportedOperationException if the device does not have required telephony feature
     */
    private void enforceTelephonyFeatureWithException(@Nullable String callingPackage,
            @NonNull String methodName) {
        TelephonyUtils.enforceTelephonyFeatureWithException(callingPackage, mPackageManager,
                mVendorApiLevel, FEATURE_TELEPHONY_SUBSCRIPTION, methodName);
    }

    /**
     * @return The logical SIM slot/sub mapping to string.
     */
    @NonNull
    private String slotMappingToString() {
        return "[" + mSlotIndexToSubId.entrySet().stream()
                .map(e -> "slot " + e.getKey() + ": subId=" + e.getValue())
                .collect(Collectors.joining(", ")) + "]";
    }

    /**
     * @param mccMnc MccMnc value to check whether it supports non-terrestrial network or not.
     * @return {@code true} if MCC/MNC is matched with in the device overlay key
     * "config_satellite_sim_plmn_identifier", {@code false} otherwise.
     */
    private boolean isSatellitePlmn(@NonNull String mccMnc) {
        final int id = R.string.config_satellite_sim_plmn_identifier;
        String overlayMccMnc = null;
        try {
            overlayMccMnc = mContext.getResources().getString(id);
        } catch (Resources.NotFoundException ex) {
            loge("isSatellitePlmn: id= " + id + ", ex=" + ex);
        }
        if (TextUtils.isEmpty(overlayMccMnc) && isMockModemAllowed()) {
            log("isSatellitePlmn: Read config_satellite_sim_plmn_identifier from device config");
            overlayMccMnc = DeviceConfig.getString(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_satellite_sim_plmn_identifier", "");
        }
        log("isSatellitePlmn: overlayMccMnc=" + overlayMccMnc + ", mccMnc=" + mccMnc);
        return TextUtils.equals(mccMnc, overlayMccMnc);
    }

    /**
     * Checks and matches the service provider name (spn) with the device overlay config to
     * determine whether non-terrestrial networks are supported.
     * @param spn service provider name of the profile.
     * @return {@code true} if the given spn is matched with the overlay key.
     * "config_satellite_sim_spn_identifier", {@code false} otherwise.
     */
    private boolean isSatelliteSpn(@NonNull String spn) {
        final int id = R.string.config_satellite_sim_spn_identifier;
        String overlaySpn = null;
        try {
            overlaySpn = mContext.getResources().getString(id);
        } catch (Resources.NotFoundException ex) {
            loge("isSatelliteSpn: id= " + id + ", ex=" + ex);
        }
        if (TextUtils.isEmpty(overlaySpn) && isMockModemAllowed()) {
            log("isSatelliteSpn: Read config_satellite_sim_spn_identifier from device config");
            overlaySpn = DeviceConfig.getString(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_satellite_sim_spn_identifier", "");
        }
        log("isSatelliteSpn: overlaySpn=" + overlaySpn + ", spn=" + spn);

        if (TextUtils.isEmpty(spn) || TextUtils.isEmpty(overlaySpn)) {
            return false;
        }

        return TextUtils.equals(spn, overlaySpn);
    }

    @Override
    @EnforcePermission(Manifest.permission.CONTROL_SIM_AUTO_PIN_MANAGEMENT)
    public byte[] getAllPlatformManagedPinsForBackup() {
        getAllPlatformManagedPinsForBackup_enforcePermission();

        return mUiccController.getPinStorage().getPlatformManagedPinsForBackup();
    }

    @Override
    @EnforcePermission(Manifest.permission.CONTROL_SIM_AUTO_PIN_MANAGEMENT)
    public void restorePlatformManagedSimPins(byte[] data) {
        restorePlatformManagedSimPins_enforcePermission();

        mUiccController.getPinStorage().restorePlatformManagedPinsFromBackup(data);
    }

    private boolean isMockModemAllowed() {
        boolean isAllowed = SystemProperties.getBoolean(ALLOW_MOCK_MODEM_PROPERTY, false);
        return (SystemProperties.getBoolean(ALLOW_MOCK_MODEM_PROPERTY, false)
                || SystemProperties.getBoolean(BOOT_ALLOW_MOCK_MODEM_PROPERTY, false));
    }

    /**
     * Iterates through previously subscribed SIMs to excludes subscriptions that are not visible
     * to the users to provide a more appropriate number to describe the current SIM.
     * @param subId current subscription id.
     * @return cardNumber subId excluding invisible subscriptions.
     */
    private int getCardNumber(int subId) {
        int cardNumber = subId; // Initialize with the potential card number
        for (int i = subId - 1; i > 0; i--) {
            SubscriptionInfoInternal subInfo = getSubscriptionInfoInternal(i);
            if (subInfo != null && !subInfo.isVisible()) {
                cardNumber--;
            }
        }

        return cardNumber;
    }

    private boolean canManageSubscription(SubscriptionInfo subInfo, String packageName) {
        if (!mFeatureFlags.downloadableSubscriptionIncludeCarrierIdentifierInternal()) {
            if (UserManager.isHeadlessSystemUserMode()) {
                return mSubscriptionManager.canManageSubscriptionAsUser(subInfo, packageName,
                    UserHandle.of(ActivityManager.getCurrentUser()));
            } else {
                return mSubscriptionManager.canManageSubscription(subInfo, packageName);
            }
        } else {
            UserHandle user = BINDER_WRAPPER.getCallingUserHandle();
            // Callers really should already take care of this, but there are too many to
            // make a change easily.
            return Binder.withCleanCallingIdentity(() ->
                    canManageSubscriptionAsUserInternal(subInfo, packageName, user));
        }
    }

    @Override
    public boolean canManageSubscriptionAsUser(@NonNull SubscriptionInfo subInfo,
            @NonNull String packageName, @NonNull UserHandle user) {
        Objects.requireNonNull(subInfo);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(user);

        // The caller either needs to be privileged in order to check for other packages, OR
        // the packageName passed in must be "self".
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
                        != PackageManager.PERMISSION_GRANTED) {
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
        }
        return Binder.withCleanCallingIdentity(() ->
                canManageSubscriptionAsUserInternal(subInfo, packageName, user));
    }

    private boolean canManageSubscriptionAsUserInternal(@NonNull SubscriptionInfo subInfo,
            @NonNull String packageName, @NonNull UserHandle user) {
        // iterate through the active "visible" subs, looking for one that matches
        for (int subId : getActiveSubIdListAsUser(false, user)) {
            TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);

            final boolean hasCarrierPrivilegesOnSub =
                    tm.checkCarrierPrivilegesForPackage(packageName)
                            == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;

            if (hasCarrierPrivilegesOnSub) {
                // Package is already carrier privileged on the target subscription
                if (subInfo.getSubscriptionId() == subId) return true;

                // Package is currently carrier privileged on a different sub owned by the same
                // Carrier as the target subscription
                if (subInfo.getCarrierId() != TelephonyManager.UNKNOWN_CARRIER_ID
                        && tm.getSimCarrierId() == subInfo.getCarrierId()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * DeSerialize the given Json model string to Map<String, Integer>.
     *
     * @param jsonString Json in string format.
     * @return Map of type Map<String, Integer>.
     */
    public Map<String, Integer> deSerializeCVToMap(String jsonString) {
        Map<String, Integer> map = new HashMap<>();
        try {
            if (TextUtils.isEmpty(jsonString)) {
                return map;
            }
            JSONObject json = new JSONObject(jsonString);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, json.getInt(key));
            }
        } catch (JSONException e) {
            loge("deSerializeCVToMap JSON error: " + e.getMessage());
        }
        return map;
    }

    /**
     * DeSerialize the given Json model string to Map<String, List<Integer>>.
     *
     * @param jsonString Json in string format.
     * @return Map of type Map<String, List<Integer>>.
     */
    public Map<String, List<Integer>> deSerializeCVToMapList(String jsonString) {
        Map<String, List<Integer>> map = new HashMap<>();
        try {
            if (TextUtils.isEmpty(jsonString)) {
                return map;
            }
            JSONObject json = new JSONObject(jsonString);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONArray jsonArray = json.getJSONArray(key);
                List<Integer> values = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    values.add(jsonArray.getInt(i));
                }
                map.put(key, values);
            }
        } catch (JSONException e) {
            loge("deSerializeCVToMapList JSON error: " + e.getMessage());
        }
        return map;
    }
     /**
     * Log debug messages.
     *
     * @param s debug messages
     */
    private void log(@NonNull String s) {
        Rlog.d(LOG_TAG, s);
    }

    /**
     * Log error messages.
     *
     * @param s error messages
     */
    private void loge(@NonNull String s) {
        Rlog.e(LOG_TAG, s);
    }

    /**
     * Log debug messages and also log into the local log.
     *
     * @param s debug messages
     */
    private void logl(@NonNull String s) {
        log(s);
        mLocalLog.log(s);
    }

    /**
     * Dump the state of {@link SubscriptionManagerService}.
     *
     * @param fd File descriptor
     * @param printWriter Print writer
     * @param args Arguments
     */
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter printWriter,
            @NonNull String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP,
                "Requires android.Manifest.permission.DUMP");
        final long token = Binder.clearCallingIdentity();
        try {
            IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
            pw.println(SubscriptionManagerService.class.getSimpleName() + ":");
            pw.println("Active modem count=" + mTelephonyManager.getActiveModemCount());
            pw.println("Logical SIM slot sub id mapping:");
            pw.increaseIndent();
            mSlotIndexToSubId.forEach((slotIndex, subId)
                    -> pw.println("Logical SIM slot " + slotIndex + ": subId=" + subId));
            pw.decreaseIndent();
            if (mFeatureFlags.remoteSimSubIdSet()) {
                pw.println("Remote SIM sub IDs: " + mRemoteSubIds);
            }
            pw.println("ICCID:");
            pw.increaseIndent();
            for (int i = 0; i < mTelephonyManager.getActiveModemCount(); i++) {
                pw.println("slot " + i + ": " + SubscriptionInfo.getPrintableId(getIccId(i)));
            }
            pw.decreaseIndent();
            pw.println();
            pw.println("defaultSubId=" + getDefaultSubId());
            pw.println("defaultVoiceSubId=" + getDefaultVoiceSubId());
            pw.println("defaultDataSubId=" + getDefaultDataSubId());
            pw.println("activeDataSubId=" + getActiveDataSubscriptionId());
            pw.println("defaultSmsSubId=" + getDefaultSmsSubId());
            pw.println("areAllSubscriptionsLoaded=" + areAllSubscriptionsLoaded());
            pw.println("mUserIdToAvailableSubs=" + mUserIdToAvailableSubs);
            pw.println();
            for (int i = 0; i < mSimState.length; i++) {
                pw.println("mSimState[" + i + "]="
                        + TelephonyManager.simStateToString(mSimState[i]));
            }

            pw.println();
            pw.println("Active subscriptions:");
            pw.increaseIndent();
            mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                    .filter(SubscriptionInfoInternal::isActive).forEach(pw::println);
            pw.decreaseIndent();

            pw.println();
            pw.println("All subscriptions:");
            pw.increaseIndent();
            mSubscriptionDatabaseManager.getAllSubscriptions().forEach(pw::println);
            pw.decreaseIndent();
            pw.println();

            pw.print("Embedded subscriptions: [");
            pw.println(mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                    .filter(SubscriptionInfoInternal::isEmbedded)
                    .map(subInfo -> String.valueOf(subInfo.getSubscriptionId()))
                    .collect(Collectors.joining(", ")) + "]");

            pw.print("Opportunistic subscriptions: [");
            pw.println(mSubscriptionDatabaseManager.getAllSubscriptions().stream()
                    .filter(SubscriptionInfoInternal::isOpportunistic)
                    .map(subInfo -> String.valueOf(subInfo.getSubscriptionId()))
                    .collect(Collectors.joining(", ")) + "]");

            pw.print("getAvailableSubscriptionInfoList: [");
            pw.println(getAvailableSubscriptionInfoList(
                    mContext.getOpPackageName(), mContext.getFeatureId()).stream()
                    .map(subInfo -> String.valueOf(subInfo.getSubscriptionId()))
                    .collect(Collectors.joining(", ")) + "]");

            pw.print("getSelectableSubscriptionInfoList: [");
            pw.println(mSubscriptionManager.getSelectableSubscriptionInfoList().stream()
                    .map(subInfo -> String.valueOf(subInfo.getSubscriptionId()))
                    .collect(Collectors.joining(", ")) + "]");

            if (mEuiccManager != null) {
                pw.println("Euicc enabled=" + mEuiccManager.isEnabled());
            }

            pw.println();
            pw.println("Enrollable Subscription Plans:");
            pw.increaseIndent();
            if (mEnrollableSubscriptionPlans.isEmpty()) {
                pw.println("None");
            } else {
                for (Map.Entry<Integer, SubscriptionPlan[]> entry :
                        mEnrollableSubscriptionPlans.entrySet()) {
                    int subId = entry.getKey();
                    pw.println("SubId: " + subId);
                    pw.increaseIndent();

                    String owner = mEnrollableSubscriptionPlansOwner.get(subId);
                    pw.println("Owner: " + (owner != null ? owner : "null"));

                    Long expirationTime = mEnrollablePlanExpirationTime.get(subId);
                    if (expirationTime != null) {
                        pw.println(
                                "Expiration: "
                                        + expirationTime
                                        + " ("
                                        + java.time.Instant.ofEpochMilli(expirationTime)
                                        + ")");
                    } else {
                        pw.println("Expiration: Never");
                    }

                    SubscriptionPlan[] plans = entry.getValue();
                    if (plans != null) {
                        for (SubscriptionPlan plan : plans) {
                            pw.println(plan);
                        }
                    } else {
                        pw.println("Plans: null");
                    }
                    pw.decreaseIndent();
                }
            }
            pw.decreaseIndent();

            pw.println();
            pw.println("Local log:");
            pw.increaseIndent();
            mLocalLog.dump(fd, pw, args);
            pw.decreaseIndent();
            pw.decreaseIndent();
            pw.println();
            mSubscriptionDatabaseManager.dump(fd, pw, args);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}

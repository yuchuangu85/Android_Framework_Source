/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSession;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsService;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.telephony.ims.aidl.IImsSmsListener;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsConfigImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsUt;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.util.HandlerExecutor;
import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Provides APIs for IMS services, such as initiating IMS calls, and provides access to
 * the operator's IMS network. This class is the starting point for any IMS actions.
 * You can acquire an instance of it with {@link #getInstance getInstance()}.</p>
 * For internal use ONLY! Use {@link ImsMmTelManager} instead.
 * @hide
 */
public class ImsManager implements IFeatureConnector {

    /*
     * Debug flag to override configuration flag
     */
    public static final String PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE = "persist.dbg.volte_avail_ovr";
    public static final int PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE_DEFAULT = 0;
    public static final String PROPERTY_DBG_VT_AVAIL_OVERRIDE = "persist.dbg.vt_avail_ovr";
    public static final int PROPERTY_DBG_VT_AVAIL_OVERRIDE_DEFAULT = 0;
    public static final String PROPERTY_DBG_WFC_AVAIL_OVERRIDE = "persist.dbg.wfc_avail_ovr";
    public static final int PROPERTY_DBG_WFC_AVAIL_OVERRIDE_DEFAULT = 0;
    public static final String PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE = "persist.dbg.allow_ims_off";
    public static final int PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE_DEFAULT = 0;

    /**
     * The result code to be sent back with the incoming call {@link PendingIntent}.
     * @see #open(MmTelFeature.Listener)
     */
    public static final int INCOMING_CALL_RESULT_CODE = 101;

    /**
     * Key to retrieve the call ID from an incoming call intent. No longer used, see
     * {@link ImsCallSessionImplBase#getCallId()}.
     * @deprecated Not used in the framework, keeping around symbol to not break old vendor
     * components.
     */
    @Deprecated
    public static final String EXTRA_CALL_ID = "android:imsCallID";

    /**
     * Action to broadcast when ImsService is up.
     * Internal use only.
     * @deprecated
     * @hide
     */
    public static final String ACTION_IMS_SERVICE_UP =
            "com.android.ims.IMS_SERVICE_UP";

    /**
     * Action to broadcast when ImsService is down.
     * Internal use only.
     * @deprecated
     * @hide
     */
    public static final String ACTION_IMS_SERVICE_DOWN =
            "com.android.ims.IMS_SERVICE_DOWN";

    /**
     * Action to broadcast when ImsService registration fails.
     * Internal use only.
     * @hide
     * @deprecated use {@link android.telephony.ims.ImsManager#ACTION_WFC_IMS_REGISTRATION_ERROR}
     * instead.
     */
    @Deprecated
    public static final String ACTION_IMS_REGISTRATION_ERROR =
            android.telephony.ims.ImsManager.ACTION_WFC_IMS_REGISTRATION_ERROR;

    /**
     * Part of the ACTION_IMS_SERVICE_UP or _DOWN intents.
     * A long value; the phone ID corresponding to the IMS service coming up or down.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_PHONE_ID = "android:phone_id";

    /**
     * Action for the incoming call intent for the Phone app.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_IMS_INCOMING_CALL =
            "com.android.ims.IMS_INCOMING_CALL";

    /**
     * Part of the ACTION_IMS_INCOMING_CALL intents.
     * An integer value; service identifier obtained from {@link ImsManager#open}.
     * Internal use only.
     * @hide
     * @deprecated Not used in the system, keeping around to not break old vendor components.
     */
    @Deprecated
    public static final String EXTRA_SERVICE_ID = "android:imsServiceId";

    /**
     * Part of the ACTION_IMS_INCOMING_CALL intents.
     * An boolean value; Flag to indicate that the incoming call is a normal call or call for USSD.
     * The value "true" indicates that the incoming call is for USSD.
     * Internal use only.
     * @deprecated Keeping around to not break old vendor components. Use
     * {@link MmTelFeature#EXTRA_USSD} instead.
     * @hide
     */
    public static final String EXTRA_USSD = "android:ussd";

    /**
     * Part of the ACTION_IMS_INCOMING_CALL intents.
     * A boolean value; Flag to indicate whether the call is an unknown
     * dialing call. Such calls are originated by sending commands (like
     * AT commands) directly to modem without Android involvement.
     * Even though they are not incoming calls, they are propagated
     * to Phone app using same ACTION_IMS_INCOMING_CALL intent.
     * Internal use only.
     * @deprecated Keeping around to not break old vendor components. Use
     * {@link MmTelFeature#EXTRA_IS_UNKNOWN_CALL} instead.
     * @hide
     */
    public static final String EXTRA_IS_UNKNOWN_CALL = "android:isUnknown";

    private static final int SUBINFO_PROPERTY_FALSE = 0;

    private static final int SYSTEM_PROPERTY_NOT_SET = -1;

    // -1 indicates a subscriptionProperty value that is never set.
    private static final int SUB_PROPERTY_NOT_INITIALIZED = -1;

    private static final String TAG = "ImsManager";
    private static final boolean DBG = true;

    private static final int RESPONSE_WAIT_TIME_MS = 3000;

    @VisibleForTesting
    public interface ExecutorFactory {
        void executeRunnable(Runnable runnable);
    }

    @VisibleForTesting
    public static class ImsExecutorFactory implements ExecutorFactory {

        private final HandlerThread mThreadHandler;
        private final Handler mHandler;

        public ImsExecutorFactory() {
            mThreadHandler = new HandlerThread("ImsHandlerThread");
            mThreadHandler.start();
            mHandler = new Handler(mThreadHandler.getLooper());
        }

        @Override
        public void executeRunnable(Runnable runnable) {
            mHandler.post(runnable);
        }

        public void destroy() {
            mThreadHandler.quit();
        }
    }

    // Replaced with single-threaded executor for testing.
    @VisibleForTesting
    public ExecutorFactory mExecutorFactory = new ImsExecutorFactory();

    private static HashMap<Integer, ImsManager> sImsManagerInstances =
            new HashMap<Integer, ImsManager>();

    private Context mContext;
    private CarrierConfigManager mConfigManager;
    private int mPhoneId;
    private @Nullable MmTelFeatureConnection mMmTelFeatureConnection = null;
    private boolean mConfigUpdated = false;

    private ImsConfigListener mImsConfigListener;

    //TODO: Move these caches into the MmTelFeature Connection and restrict their lifetimes to the
    // lifetime of the MmTelFeature.
    // Ut interface for the supplementary service configuration
    private ImsUt mUt = null;
    // ECBM interface
    private ImsEcbm mEcbm = null;
    private ImsMultiEndpoint mMultiEndpoint = null;

    private Set<FeatureConnection.IFeatureUpdate> mStatusCallbacks = new CopyOnWriteArraySet<>();

    public static final String TRUE = "true";
    public static final String FALSE = "false";

    // mRecentDisconnectReasons stores the last 16 disconnect reasons
    private static final int MAX_RECENT_DISCONNECT_REASONS = 16;
    private ConcurrentLinkedDeque<ImsReasonInfo> mRecentDisconnectReasons =
            new ConcurrentLinkedDeque<>();

    /**
     * Gets a manager instance.
     *
     * @param context application context for creating the manager object
     * @param phoneId the phone ID for the IMS Service
     * @return the manager instance corresponding to the phoneId
     */
    @UnsupportedAppUsage
    public static ImsManager getInstance(Context context, int phoneId) {
        synchronized (sImsManagerInstances) {
            if (sImsManagerInstances.containsKey(phoneId)) {
                ImsManager m = sImsManagerInstances.get(phoneId);
                // May be null for some tests
                if (m != null) {
                    m.connectIfServiceIsAvailable();
                }
                return m;
            }

            ImsManager mgr = new ImsManager(context, phoneId);
            sImsManagerInstances.put(phoneId, mgr);

            return mgr;
        }
    }

    public static boolean isImsSupportedOnDevice(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_IMS);
    }

    /**
     * Returns the user configuration of Enhanced 4G LTE Mode setting.
     *
     * @deprecated Doesn't support MSIM devices. Use
     * {@link #isEnhanced4gLteModeSettingEnabledByUser()} instead.
     */
    @UnsupportedAppUsage
    public static boolean isEnhanced4gLteModeSettingEnabledByUser(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isEnhanced4gLteModeSettingEnabledByUser();
        }
        Rlog.e(TAG, "isEnhanced4gLteModeSettingEnabledByUser: ImsManager null, returning default"
                + " value.");
        return false;
    }

    /**
     * Returns the user configuration of Enhanced 4G LTE Mode setting for slot. If the option is
     * not editable ({@link CarrierConfigManager#KEY_EDITABLE_ENHANCED_4G_LTE_BOOL} is false),
     * hidden ({@link CarrierConfigManager#KEY_HIDE_ENHANCED_4G_LTE_BOOL} is true), or
     * the setting is not initialized, this method will return default value specified by
     * {@link CarrierConfigManager#KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL}.
     *
     * Note that even if the setting was set, it may no longer be editable. If this is the case we
     * return the default value.
     */
    public boolean isEnhanced4gLteModeSettingEnabledByUser() {
        int setting = SubscriptionManager.getIntegerSubscriptionProperty(
                getSubId(), SubscriptionManager.ENHANCED_4G_MODE_ENABLED,
                SUB_PROPERTY_NOT_INITIALIZED, mContext);
        boolean onByDefault = getBooleanCarrierConfig(
                CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL);

        // If Enhanced 4G LTE Mode is uneditable, hidden or not initialized, we use the default
        // value
        if (!getBooleanCarrierConfig(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL)
                || getBooleanCarrierConfig(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL)
                || setting == SUB_PROPERTY_NOT_INITIALIZED) {
            return onByDefault;
        } else {
            return (setting == ProvisioningManager.PROVISIONING_VALUE_ENABLED);
        }
    }

    /**
     * Change persistent Enhanced 4G LTE Mode setting.
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #setEnhanced4gLteModeSetting(boolean)}
     * instead.
     */
    public static void setEnhanced4gLteModeSetting(Context context, boolean enabled) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            mgr.setEnhanced4gLteModeSetting(enabled);
        }
        Rlog.e(TAG, "setEnhanced4gLteModeSetting: ImsManager null, value not set.");
    }

    /**
     * Change persistent Enhanced 4G LTE Mode setting. If the option is not editable
     * ({@link CarrierConfigManager#KEY_EDITABLE_ENHANCED_4G_LTE_BOOL} is false)
     * or hidden ({@link CarrierConfigManager#KEY_HIDE_ENHANCED_4G_LTE_BOOL} is true),
     * this method will set the setting to the default value specified by
     * {@link CarrierConfigManager#KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL}.
     */
    public void setEnhanced4gLteModeSetting(boolean enabled) {
        if (enabled && !isVolteProvisionedOnDevice()) {
            log("setEnhanced4gLteModeSetting: Not possible to enable VoLTE due to provisioning.");
            return;
        }
        int subId = getSubId();
        // If editable=false or hidden=true, we must keep default advanced 4G mode.
        if (!getBooleanCarrierConfig(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL) ||
                getBooleanCarrierConfig(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL)) {
            enabled = getBooleanCarrierConfig(
                    CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL);
        }

        int prevSetting = SubscriptionManager.getIntegerSubscriptionProperty(subId,
                SubscriptionManager.ENHANCED_4G_MODE_ENABLED, SUB_PROPERTY_NOT_INITIALIZED,
                mContext);

        if (prevSetting != (enabled ?
                ProvisioningManager.PROVISIONING_VALUE_ENABLED :
                ProvisioningManager.PROVISIONING_VALUE_DISABLED)) {
            if (isSubIdValid(subId)) {
                SubscriptionManager.setSubscriptionProperty(subId,
                        SubscriptionManager.ENHANCED_4G_MODE_ENABLED,
                        booleanToPropertyString(enabled));
            } else {
                loge("setEnhanced4gLteModeSetting: invalid sub id, can not set property in " +
                        " siminfo db; subId=" + subId);
            }
            if (isNonTtyOrTtyOnVolteEnabled()) {
                try {
                    setAdvanced4GMode(enabled);
                } catch (ImsException ie) {
                    // do nothing
                }
            }
        }
    }

    /**
     * Indicates whether the call is non-TTY or if TTY - whether TTY on VoLTE is
     * supported.
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isNonTtyOrTtyOnVolteEnabled()} instead.
     */
    @UnsupportedAppUsage
    public static boolean isNonTtyOrTtyOnVolteEnabled(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isNonTtyOrTtyOnVolteEnabled();
        }
        Rlog.e(TAG, "isNonTtyOrTtyOnVolteEnabled: ImsManager null, returning default value.");
        return false;
    }

    /**
     * Indicates whether the call is non-TTY or if TTY - whether TTY on VoLTE is
     * supported on a per slot basis.
     */
    public boolean isNonTtyOrTtyOnVolteEnabled() {
        if (isTtyOnVoLteCapable()) {
            return true;
        }

        TelecomManager tm = (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) {
            logw("isNonTtyOrTtyOnVolteEnabled: telecom not available");
            return true;
        }
        return tm.getCurrentTtyMode() == TelecomManager.TTY_MODE_OFF;
    }

    public boolean isTtyOnVoLteCapable() {
        return getBooleanCarrierConfig(CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL);
    }

    /**
     * Returns a platform configuration for VoLTE which may override the user setting.
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVolteEnabledByPlatform()} instead.
     */
    @UnsupportedAppUsage
    public static boolean isVolteEnabledByPlatform(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isVolteEnabledByPlatform();
        }
        Rlog.e(TAG, "isVolteEnabledByPlatform: ImsManager null, returning default value.");
        return false;
    }

    /**
     * Asynchronous call to ImsService to determine whether or not a specific MmTel capability is
     * supported.
     */
    public void isSupported(int capability, int transportType, Consumer<Boolean> result) {
        mExecutorFactory.executeRunnable(() -> {
            switch(transportType) {
                case (AccessNetworkConstants.TRANSPORT_TYPE_WWAN): {
                    switch (capability) {
                        case (MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE): {
                            result.accept(isVolteEnabledByPlatform());
                            return;
                        } case (MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO): {
                            result.accept(isVtEnabledByPlatform());
                            return;
                        }case (MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT): {
                            result.accept(isSuppServicesOverUtEnabledByPlatform());
                            return;
                        } case (MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_SMS): {
                            // There is currently no carrier config defined for this.
                            result.accept(true);
                            return;
                        }
                    }
                    break;
                } case (AccessNetworkConstants.TRANSPORT_TYPE_WLAN): {
                    switch (capability) {
                        case (MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE) : {
                            result.accept(isWfcEnabledByPlatform());
                            return;
                        } case (MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO) : {
                            // This is not transport dependent at this time.
                            result.accept(isVtEnabledByPlatform());
                            return;
                        } case (MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT) : {
                            // This is not transport dependent at this time.
                            result.accept(isSuppServicesOverUtEnabledByPlatform());
                            return;
                        } case (MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_SMS) : {
                            // There is currently no carrier config defined for this.
                            result.accept(true);
                            return;
                        }
                    }
                    break;
                }
            }
            // false for unknown capability/transport types.
            result.accept(false);
        });

    }

    /**
     * Returns a platform configuration for VoLTE which may override the user setting on a per Slot
     * basis.
     */
    public boolean isVolteEnabledByPlatform() {
        // We first read the per slot value. If doesn't exist, we read the general value. If still
        // doesn't exist, we use the hardcoded default value.
        if (SystemProperties.getInt(
                PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE + Integer.toString(mPhoneId),
                SYSTEM_PROPERTY_NOT_SET) == 1 ||
                SystemProperties.getInt(PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE,
                        SYSTEM_PROPERTY_NOT_SET) == 1) {
            return true;
        }

        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_device_volte_available)
                && getBooleanCarrierConfig(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)
                && isGbaValid();
    }

    /**
     * Indicates whether VoLTE is provisioned on device.
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVolteProvisionedOnDevice()} instead.
     */
    public static boolean isVolteProvisionedOnDevice(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isVolteProvisionedOnDevice();
        }
        Rlog.e(TAG, "isVolteProvisionedOnDevice: ImsManager null, returning default value.");
        return true;
    }

    /**
     * Indicates whether VoLTE is provisioned on this slot.
     */
    public boolean isVolteProvisionedOnDevice() {
        if (getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {
            return isVolteProvisioned();
        }

        return true;
    }

    /**
     * Indicates whether EAB is provisioned on this slot.
     */
    public boolean isEabProvisionedOnDevice() {
        if (getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_RCS_PROVISIONING_REQUIRED_BOOL)) {
            return isEabProvisioned();
        }

        return true;
    }

    /**
     * Indicates whether VoWifi is provisioned on device.
     *
     * When CarrierConfig KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL is true, and VoLTE is not
     * provisioned on device, this method returns false.
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isWfcProvisionedOnDevice()} instead.
     */
    public static boolean isWfcProvisionedOnDevice(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isWfcProvisionedOnDevice();
        }
        Rlog.e(TAG, "isWfcProvisionedOnDevice: ImsManager null, returning default value.");
        return true;
    }

    /**
     * Indicates whether VoWifi is provisioned on slot.
     *
     * When CarrierConfig KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL is true, and VoLTE is not
     * provisioned on device, this method returns false.
     */
    public boolean isWfcProvisionedOnDevice() {
        if (getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL)) {
            if (!isVolteProvisionedOnDevice()) {
                return false;
            }
        }

        if (getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {
            return isWfcProvisioned();
        }

        return true;
    }

    /**
     * Indicates whether VT is provisioned on device
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVtProvisionedOnDevice()} instead.
     */
    public static boolean isVtProvisionedOnDevice(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isVtProvisionedOnDevice();
        }
        Rlog.e(TAG, "isVtProvisionedOnDevice: ImsManager null, returning default value.");
        return true;
    }

    /**
     * Indicates whether VT is provisioned on slot.
     */
    public boolean isVtProvisionedOnDevice() {
        if (getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {
            return isVtProvisioned();
        }

        return true;
    }

    /**
     * Returns a platform configuration for VT which may override the user setting.
     *
     * Note: VT presumes that VoLTE is enabled (these are configuration settings
     * which must be done correctly).
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVtEnabledByPlatform()} instead.
     */
    public static boolean isVtEnabledByPlatform(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isVtEnabledByPlatform();
        }
        Rlog.e(TAG, "isVtEnabledByPlatform: ImsManager null, returning default value.");
        return false;
    }

    /**
     * Returns a platform configuration for VT which may override the user setting.
     *
     * Note: VT presumes that VoLTE is enabled (these are configuration settings
     * which must be done correctly).
     */
    public boolean isVtEnabledByPlatform() {
        // We first read the per slot value. If doesn't exist, we read the general value. If still
        // doesn't exist, we use the hardcoded default value.
        if (SystemProperties.getInt(PROPERTY_DBG_VT_AVAIL_OVERRIDE +
                Integer.toString(mPhoneId), SYSTEM_PROPERTY_NOT_SET) == 1  ||
                SystemProperties.getInt(
                        PROPERTY_DBG_VT_AVAIL_OVERRIDE, SYSTEM_PROPERTY_NOT_SET) == 1) {
            return true;
        }

        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_device_vt_available) &&
                getBooleanCarrierConfig(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL) &&
                isGbaValid();
    }

    /**
     * Returns the user configuration of VT setting
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVtEnabledByUser()} instead.
     */
    public static boolean isVtEnabledByUser(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isVtEnabledByUser();
        }
        Rlog.e(TAG, "isVtEnabledByUser: ImsManager null, returning default value.");
        return false;
    }

    /**
     * Returns the user configuration of VT setting per slot. If not set, it
     * returns true as default value.
     */
    public boolean isVtEnabledByUser() {
        int setting = SubscriptionManager.getIntegerSubscriptionProperty(
                getSubId(), SubscriptionManager.VT_IMS_ENABLED,
                SUB_PROPERTY_NOT_INITIALIZED, mContext);

        // If it's never set, by default we return true.
        return (setting == SUB_PROPERTY_NOT_INITIALIZED
                || setting == ProvisioningManager.PROVISIONING_VALUE_ENABLED);
    }

    /**
     * Change persistent VT enabled setting
     *
     * @deprecated Does not support MSIM devices. Please use {@link #setVtSetting(boolean)} instead.
     */
    public static void setVtSetting(Context context, boolean enabled) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            mgr.setVtSetting(enabled);
        }
        Rlog.e(TAG, "setVtSetting: ImsManager null, can not set value.");
    }

    /**
     * Change persistent VT enabled setting for slot.
     */
    public void setVtSetting(boolean enabled) {
        if (enabled && !isVtProvisionedOnDevice()) {
            log("setVtSetting: Not possible to enable Vt due to provisioning.");
            return;
        }

        int subId = getSubId();
        if (isSubIdValid(subId)) {
            SubscriptionManager.setSubscriptionProperty(subId, SubscriptionManager.VT_IMS_ENABLED,
                    booleanToPropertyString(enabled));
        } else {
            loge("setVtSetting: sub id invalid, skip modifying vt state in subinfo db; subId="
                    + subId);
        }

        try {
            changeMmTelCapability(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE, enabled);

            if (enabled) {
                log("setVtSetting(b) : turnOnIms");
                turnOnIms();
            } else if (isTurnOffImsAllowedByPlatform()
                    && (!isVolteEnabledByPlatform()
                    || !isEnhanced4gLteModeSettingEnabledByUser())) {
                log("setVtSetting(b) : imsServiceAllowTurnOff -> turnOffIms");
                turnOffIms();
            }
        } catch (ImsException e) {
            // The ImsService is down. Since the SubscriptionManager already recorded the user's
            // preference, it will be resent in updateImsServiceConfig when the ImsPhoneCallTracker
            // reconnects.
            loge("setVtSetting(b): ", e);
        }
    }

    /**
     * Returns whether turning off ims is allowed by platform.
     * The platform property may override the carrier config.
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isTurnOffImsAllowedByPlatform()} instead.
     */
    private static boolean isTurnOffImsAllowedByPlatform(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isTurnOffImsAllowedByPlatform();
        }
        Rlog.e(TAG, "isTurnOffImsAllowedByPlatform: ImsManager null, returning default value.");
        return true;
    }

    /**
     * Returns whether turning off ims is allowed by platform.
     * The platform property may override the carrier config.
     */
    private boolean isTurnOffImsAllowedByPlatform() {
        // We first read the per slot value. If doesn't exist, we read the general value. If still
        // doesn't exist, we use the hardcoded default value.
        if (SystemProperties.getInt(PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE +
                Integer.toString(mPhoneId), SYSTEM_PROPERTY_NOT_SET) == 1  ||
                SystemProperties.getInt(
                        PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE, SYSTEM_PROPERTY_NOT_SET) == 1) {
            return true;
        }

        return getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_ALLOW_TURNOFF_IMS_BOOL);
    }

    /**
     * Returns the user configuration of WFC setting
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isWfcEnabledByUser()} instead.
     */
    public static boolean isWfcEnabledByUser(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isWfcEnabledByUser();
        }
        Rlog.e(TAG, "isWfcEnabledByUser: ImsManager null, returning default value.");
        return true;
    }

    /**
     * Returns the user configuration of WFC setting for slot. If not set, it
     * queries CarrierConfig value as default.
     */
    public boolean isWfcEnabledByUser() {
        int setting = SubscriptionManager.getIntegerSubscriptionProperty(
                getSubId(), SubscriptionManager.WFC_IMS_ENABLED,
                SUB_PROPERTY_NOT_INITIALIZED, mContext);

        // SUB_PROPERTY_NOT_INITIALIZED indicates it's never set in sub db.
        if (setting == SUB_PROPERTY_NOT_INITIALIZED) {
            return getBooleanCarrierConfig(
                    CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL);
        } else {
            return setting == ProvisioningManager.PROVISIONING_VALUE_ENABLED;
        }
    }

    /**
     * Change persistent WFC enabled setting.
     * @deprecated Does not support MSIM devices. Please use
     * {@link #setWfcSetting} instead.
     */
    public static void setWfcSetting(Context context, boolean enabled) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            mgr.setWfcSetting(enabled);
        }
        Rlog.e(TAG, "setWfcSetting: ImsManager null, can not set value.");
    }

    /**
     * Change persistent WFC enabled setting for slot.
     */
    public void setWfcSetting(boolean enabled) {
        if (enabled && !isWfcProvisionedOnDevice()) {
            log("setWfcSetting: Not possible to enable WFC due to provisioning.");
            return;
        }

        int subId = getSubId();
        if (isSubIdValid(subId)) {
            SubscriptionManager.setSubscriptionProperty(subId, SubscriptionManager.WFC_IMS_ENABLED,
                    booleanToPropertyString(enabled));
        } else {
            loge("setWfcSetting: invalid sub id, can not set WFC setting in siminfo db; subId="
                    + subId);
        }

        TelephonyManager tm = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        boolean isRoaming = tm.isNetworkRoaming(subId);
        setWfcNonPersistent(enabled, getWfcMode(isRoaming));
    }

    /**
     * Non-persistently change WFC enabled setting and WFC mode for slot
     *
     * @param enabled If true, WFC and WFC while roaming will be enabled for the associated
     *                subscription, if supported by the carrier. If false, WFC will be disabled for
     *                the associated subscription.
     * @param wfcMode The WFC preference if WFC is enabled
     */
    public void setWfcNonPersistent(boolean enabled, int wfcMode) {
        // Force IMS to register over LTE when turning off WFC
        int imsWfcModeFeatureValue =
                enabled ? wfcMode : ImsMmTelManager.WIFI_MODE_CELLULAR_PREFERRED;

        try {
            changeMmTelCapability(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN, enabled);

            // Set the mode and roaming enabled settings before turning on IMS
            setWfcModeInternal(imsWfcModeFeatureValue);
            // If enabled is false, shortcut to false because of the ImsService
            // implementation for WFC roaming, otherwise use the correct user's setting.
            setWfcRoamingSettingInternal(enabled && isWfcRoamingEnabledByUser());

            if (enabled) {
                log("setWfcSetting() : turnOnIms");
                turnOnIms();
            } else if (isTurnOffImsAllowedByPlatform()
                    && (!isVolteEnabledByPlatform()
                    || !isEnhanced4gLteModeSettingEnabledByUser())) {
                log("setWfcSetting() : imsServiceAllowTurnOff -> turnOffIms");
                turnOffIms();
            }
        } catch (ImsException e) {
            loge("setWfcSetting(): ", e);
        }
    }

    /**
     * Returns the user configuration of WFC preference setting.
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #getWfcMode(boolean roaming)} instead.
     */
    public static int getWfcMode(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.getWfcMode();
        }
        Rlog.e(TAG, "getWfcMode: ImsManager null, returning default value.");
        return ImsMmTelManager.WIFI_MODE_WIFI_ONLY;
    }

    /**
     * Returns the user configuration of WFC preference setting
     * @deprecated. Use {@link #getWfcMode(boolean roaming)} instead.
     */
    public int getWfcMode() {
        return getWfcMode(false);
    }

    /**
     * Change persistent WFC preference setting.
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #setWfcMode(int)} instead.
     */
    public static void setWfcMode(Context context, int wfcMode) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            mgr.setWfcMode(wfcMode);
        }
        Rlog.e(TAG, "setWfcMode: ImsManager null, can not set value.");
    }

    /**
     * Change persistent WFC preference setting for slot when not roaming.
     * @deprecated Use {@link #setWfcMode(int, boolean)} instead.
     */
    public void setWfcMode(int wfcMode) {
        setWfcMode(wfcMode, false /*isRoaming*/);
    }

    /**
     * Returns the user configuration of WFC preference setting
     *
     * @param roaming {@code false} for home network setting, {@code true} for roaming  setting
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #getWfcMode(boolean)} instead.
     */
    public static int getWfcMode(Context context, boolean roaming) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.getWfcMode(roaming);
        }
        Rlog.e(TAG, "getWfcMode: ImsManager null, returning default value.");
        return ImsMmTelManager.WIFI_MODE_WIFI_ONLY;
    }

    /**
     * Returns the user configuration of WFC preference setting for slot. If not set, it
     * queries CarrierConfig value as default.
     *
     * @param roaming {@code false} for home network setting, {@code true} for roaming  setting
     */
    public int getWfcMode(boolean roaming) {
        int setting;
        if (!roaming) {
            // The WFC mode is not editable, return the default setting in the CarrierConfig, not
            // the user set value.
            if (!getBooleanCarrierConfig(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL)) {
                setting = getIntCarrierConfig(
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT);

            } else {
                setting = getSettingFromSubscriptionManager(SubscriptionManager.WFC_IMS_MODE,
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT);
            }
            if (DBG) log("getWfcMode - setting=" + setting);
        } else {
            if (getBooleanCarrierConfig(
                    CarrierConfigManager.KEY_USE_WFC_HOME_NETWORK_MODE_IN_ROAMING_NETWORK_BOOL)) {
                setting = getWfcMode(false);
            } else if (!getBooleanCarrierConfig(
                    CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL)) {
                setting = getIntCarrierConfig(
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT);
            } else {
                setting = getSettingFromSubscriptionManager(
                        SubscriptionManager.WFC_IMS_ROAMING_MODE,
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT);
            }
            if (DBG) log("getWfcMode (roaming) - setting=" + setting);
        }
        return setting;
    }

    /**
     * Returns the SubscriptionManager setting for the subSetting string. If it is not set, default
     * to the default CarrierConfig value for defaultConfigKey.
     */
    private int getSettingFromSubscriptionManager(String subSetting, String defaultConfigKey) {
        int result;
        result = SubscriptionManager.getIntegerSubscriptionProperty(getSubId(), subSetting,
                SUB_PROPERTY_NOT_INITIALIZED, mContext);

        // SUB_PROPERTY_NOT_INITIALIZED indicates it's never set in sub db.
        if (result == SUB_PROPERTY_NOT_INITIALIZED) {
            result = getIntCarrierConfig(defaultConfigKey);
        }
        return result;
    }

    /**
     * Change persistent WFC preference setting
     *
     * @param roaming {@code false} for home network setting, {@code true} for roaming setting
     *
     * @deprecated Doesn't support MSIM devices. Please use {@link #setWfcMode(int, boolean)}
     * instead.
     */
    public static void setWfcMode(Context context, int wfcMode, boolean roaming) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            mgr.setWfcMode(wfcMode, roaming);
        }
        Rlog.e(TAG, "setWfcMode: ImsManager null, can not set value.");
    }

    /**
     * Change persistent WFC preference setting
     *
     * @param roaming {@code false} for home network setting, {@code true} for roaming setting
     */
    public void setWfcMode(int wfcMode, boolean roaming) {
        int subId = getSubId();
        if (isSubIdValid(subId)) {
            if (!roaming) {
                if (DBG) log("setWfcMode(i,b) - setting=" + wfcMode);
                SubscriptionManager.setSubscriptionProperty(subId, SubscriptionManager.WFC_IMS_MODE,
                        Integer.toString(wfcMode));
            } else {
                if (DBG) log("setWfcMode(i,b) (roaming) - setting=" + wfcMode);
                SubscriptionManager.setSubscriptionProperty(subId,
                        SubscriptionManager.WFC_IMS_ROAMING_MODE, Integer.toString(wfcMode));
            }
        } else {
            loge("setWfcMode(i,b): invalid sub id, skip setting setting in siminfo db; subId="
                    + subId);
        }

        TelephonyManager tm = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        // Unfortunately, the WFC mode is the same for home/roaming (we do not have separate
        // config keys), so we have to change the WFC mode when moving home<->roaming. So, only
        // call setWfcModeInternal when roaming == telephony roaming status. Otherwise, ignore.
        if (roaming == tm.isNetworkRoaming(getSubId())) {
            setWfcModeInternal(wfcMode);
        }
    }

    private int getSubId() {
        int[] subIds = SubscriptionManager.getSubId(mPhoneId);
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (subIds != null && subIds.length >= 1) {
            subId = subIds[0];
        }
        return subId;
    }

    private void setWfcModeInternal(int wfcMode) {
        final int value = wfcMode;
        mExecutorFactory.executeRunnable(() -> {
            try {
                getConfigInterface().setConfig(
                        ProvisioningManager.KEY_VOICE_OVER_WIFI_MODE_OVERRIDE, value);
            } catch (ImsException e) {
                // do nothing
            }
        });
    }

    /**
     * Returns the user configuration of WFC roaming setting
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isWfcRoamingEnabledByUser()} instead.
     */
    public static boolean isWfcRoamingEnabledByUser(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isWfcRoamingEnabledByUser();
        }
        Rlog.e(TAG, "isWfcRoamingEnabledByUser: ImsManager null, returning default value.");
        return false;
    }

    /**
     * Returns the user configuration of WFC roaming setting for slot. If not set, it
     * queries CarrierConfig value as default.
     */
    public boolean isWfcRoamingEnabledByUser() {
        int setting =  SubscriptionManager.getIntegerSubscriptionProperty(
                getSubId(), SubscriptionManager.WFC_IMS_ROAMING_ENABLED,
                SUB_PROPERTY_NOT_INITIALIZED, mContext);
        if (setting == SUB_PROPERTY_NOT_INITIALIZED) {
            return getBooleanCarrierConfig(
                            CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL);
        } else {
            return setting == ProvisioningManager.PROVISIONING_VALUE_ENABLED;
        }
    }

    /**
     * Change persistent WFC roaming enabled setting
     */
    public static void setWfcRoamingSetting(Context context, boolean enabled) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            mgr.setWfcRoamingSetting(enabled);
        }
        Rlog.e(TAG, "setWfcRoamingSetting: ImsManager null, value not set.");
    }

    /**
     * Change persistent WFC roaming enabled setting
     */
    public void setWfcRoamingSetting(boolean enabled) {
        SubscriptionManager.setSubscriptionProperty(getSubId(),
                SubscriptionManager.WFC_IMS_ROAMING_ENABLED, booleanToPropertyString(enabled)
        );

        setWfcRoamingSettingInternal(enabled);
    }

    private void setWfcRoamingSettingInternal(boolean enabled) {
        final int value = enabled
                ? ProvisioningManager.PROVISIONING_VALUE_ENABLED
                : ProvisioningManager.PROVISIONING_VALUE_DISABLED;
        mExecutorFactory.executeRunnable(() -> {
            try {
                getConfigInterface().setConfig(
                        ProvisioningManager.KEY_VOICE_OVER_WIFI_ROAMING_ENABLED_OVERRIDE, value);
            } catch (ImsException e) {
                // do nothing
            }
        });
    }

    /**
     * Returns a platform configuration for WFC which may override the user
     * setting. Note: WFC presumes that VoLTE is enabled (these are
     * configuration settings which must be done correctly).
     *
     * @deprecated Doesn't work for MSIM devices. Use {@link #isWfcEnabledByPlatform()}
     * instead.
     */
    public static boolean isWfcEnabledByPlatform(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            return mgr.isWfcEnabledByPlatform();
        }
        Rlog.e(TAG, "isWfcEnabledByPlatform: ImsManager null, returning default value.");
        return false;
    }

    /**
     * Returns a platform configuration for WFC which may override the user
     * setting per slot. Note: WFC presumes that VoLTE is enabled (these are
     * configuration settings which must be done correctly).
     */
    public boolean isWfcEnabledByPlatform() {
        // We first read the per slot value. If doesn't exist, we read the general value. If still
        // doesn't exist, we use the hardcoded default value.
        if (SystemProperties.getInt(PROPERTY_DBG_WFC_AVAIL_OVERRIDE +
                Integer.toString(mPhoneId), SYSTEM_PROPERTY_NOT_SET) == 1  ||
                SystemProperties.getInt(
                        PROPERTY_DBG_WFC_AVAIL_OVERRIDE, SYSTEM_PROPERTY_NOT_SET) == 1) {
            return true;
        }

        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_device_wfc_ims_available) &&
                getBooleanCarrierConfig(
                        CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL) &&
                isGbaValid();
    }

    public boolean isSuppServicesOverUtEnabledByPlatform() {
        TelephonyManager manager = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        int cardState = manager.getSimState(mPhoneId);
        if (cardState != TelephonyManager.SIM_STATE_READY) {
            // Do not report enabled until we actually have an active subscription.
            return false;
        }
        return getBooleanCarrierConfig(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL) &&
                isGbaValid();
    }

    /**
     * If carrier requires that IMS is only available if GBA capable SIM is used,
     * then this function checks GBA bit in EF IST.
     *
     * Format of EF IST is defined in 3GPP TS 31.103 (Section 4.2.7).
     */
    private boolean isGbaValid() {
        if (getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_IMS_GBA_REQUIRED_BOOL)) {
            final TelephonyManager telephonyManager = new TelephonyManager(mContext, getSubId());
            String efIst = telephonyManager.getIsimIst();
            if (efIst == null) {
                loge("isGbaValid - ISF is NULL");
                return true;
            }
            boolean result = efIst != null && efIst.length() > 1 &&
                    (0x02 & (byte)efIst.charAt(1)) != 0;
            if (DBG) log("isGbaValid - GBA capable=" + result + ", ISF=" + efIst);
            return result;
        }
        return true;
    }

    /**
     * Will return with config value or throw an ImsException if we receive an error from
     * ImsConfig for that value.
     */
    private boolean getProvisionedBool(ImsConfig config, int item) throws ImsException {
        int value = config.getProvisionedValue(item);
        if (value == ImsConfigImplBase.CONFIG_RESULT_UNKNOWN) {
            throw new ImsException("getProvisionedBool failed with error for item: " + item,
                    ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR);
        }
        return value == ProvisioningManager.PROVISIONING_VALUE_ENABLED;
    }

    /**
     * Will set config value or throw an ImsException if we receive an error from ImsConfig for that
     * value.
     */
    private void setProvisionedBool(ImsConfig config, int item, int value) throws ImsException {
        int result = config.setConfig(item, value);
        if (result != ImsConfigImplBase.CONFIG_RESULT_SUCCESS) {
            throw new ImsException("setProvisionedBool failed with error for item: " + item,
                    ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR);
        }
    }

    /**
     * Will return with config value or return false if we receive an error from
     * ImsConfigImplBase implementation for that value.
     */
    private boolean getProvisionedBoolNoException(int item) {
        try {
            ImsConfig config = getConfigInterface();
            return getProvisionedBool(config, item);
        } catch (ImsException ex) {
            logw("getProvisionedBoolNoException: operation failed for item=" + item
                    + ". Exception:" + ex.getMessage() + ". Returning false.");
            return false;
        }
    }

    /**
     * Will return with config value or return false if we receive an error from
     * ImsConfigImplBase implementation for that value.
     */
    private boolean setProvisionedBoolNoException(int item, int value) {
        try {
            ImsConfig config = getConfigInterface();
            setProvisionedBool(config, item, value);
        } catch (ImsException ex) {
            logw("setProvisionedBoolNoException: operation failed for item=" + item
                    + ", value=" + value + ". Exception:" + ex.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Sync carrier config and user settings with ImsConfigImplBase implementation.
     *
     * @param context for the manager object
     * @param phoneId phone id
     * @param force update
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #updateImsServiceConfig(boolean)}
     * instead.
     */
    public static void updateImsServiceConfig(Context context, int phoneId, boolean force) {
        ImsManager mgr = ImsManager.getInstance(context, phoneId);
        if (mgr != null) {
            mgr.updateImsServiceConfig(force);
        }
        Rlog.e(TAG, "updateImsServiceConfig: ImsManager null, returning without update.");
    }

    /**
     * Sync carrier config and user settings with ImsConfigImplBase implementation.
     *
     * @param force update
     */
    public void updateImsServiceConfig(boolean force) {
        if (!force) {
            TelephonyManager tm = new TelephonyManager(mContext, getSubId());
            if (tm.getSimState() != TelephonyManager.SIM_STATE_READY) {
                log("updateImsServiceConfig: SIM not ready");
                // Don't disable IMS if SIM is not ready
                return;
            }
        }

        if (!mConfigUpdated || force) {
            try {
                PersistableBundle imsCarrierConfigs =
                        mConfigManager.getConfigByComponentForSubId(
                                CarrierConfigManager.Ims.KEY_PREFIX, getSubId());

                updateImsCarrierConfigs(imsCarrierConfigs);

                // Note: currently the order of updates is set to produce different order of
                // changeEnabledCapabilities() function calls from setAdvanced4GMode(). This is done
                // to differentiate this code path from vendor code perspective.
                CapabilityChangeRequest request = new CapabilityChangeRequest();
                updateVolteFeatureValue(request);
                updateWfcFeatureAndProvisionedValues(request);
                updateVideoCallFeatureValue(request);
                // Only turn on IMS for RTT if there's an active subscription present. If not, the
                // modem will be in emergency-call-only mode and will use separate signaling to
                // establish an RTT emergency call.
                boolean isImsNeededForRtt = updateRttConfigValue() && isActiveSubscriptionPresent();
                // Supplementary services over UT do not require IMS registration. Do not alter IMS
                // registration based on UT.
                updateUtFeatureValue(request);

                // Send the batched request to the modem.
                changeMmTelCapability(request);

                if (isImsNeededForRtt || !isTurnOffImsAllowedByPlatform() || isImsNeeded(request)) {
                    // Turn on IMS if it is used.
                    // Also, if turning off is not allowed for current carrier,
                    // we need to turn IMS on because it might be turned off before
                    // phone switched to current carrier.
                    log("updateImsServiceConfig: turnOnIms");
                    turnOnIms();
                } else {
                    // Turn off IMS if it is not used AND turning off is allowed for carrier.
                    log("updateImsServiceConfig: turnOffIms");
                    turnOffIms();
                }

                mConfigUpdated = true;
            } catch (ImsException e) {
                loge("updateImsServiceConfig: ", e);
                mConfigUpdated = false;
            }
        }
    }

    private boolean isImsNeeded(CapabilityChangeRequest r) {
        // IMS is not needed for UT, so only enabled IMS if any other capability is enabled.
        return r.getCapabilitiesToEnable().stream()
                .anyMatch((c) ->
                        (c.getCapability() != MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT));
    }

    /**
     * Update VoLTE config
     */
    private void updateVolteFeatureValue(CapabilityChangeRequest request) {
        boolean available = isVolteEnabledByPlatform();
        boolean enabled = isEnhanced4gLteModeSettingEnabledByUser();
        boolean isNonTty = isNonTtyOrTtyOnVolteEnabled();
        boolean isProvisioned = isVolteProvisionedOnDevice();
        boolean isFeatureOn = available && enabled && isNonTty && isProvisioned;

        log("updateVolteFeatureValue: available = " + available
                + ", enabled = " + enabled
                + ", nonTTY = " + isNonTty
                + ", provisioned = " + isProvisioned
                + ", isFeatureOn = " + isFeatureOn);

        if (isFeatureOn) {
            request.addCapabilitiesToEnableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        } else {
            request.addCapabilitiesToDisableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        }
    }

    /**
     * Update video call over LTE config
     */
    private void updateVideoCallFeatureValue(CapabilityChangeRequest request) {
        boolean available = isVtEnabledByPlatform();
        boolean enabled = isVtEnabledByUser();
        boolean isNonTty = isNonTtyOrTtyOnVolteEnabled();
        boolean isDataEnabled = isDataEnabled();
        boolean ignoreDataEnabledChanged = getBooleanCarrierConfig(
                CarrierConfigManager.KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS);
        boolean isProvisioned = isVtProvisionedOnDevice();
        boolean isFeatureOn = available && enabled && isNonTty && isProvisioned
                && (ignoreDataEnabledChanged || isDataEnabled);

        log("updateVideoCallFeatureValue: available = " + available
                + ", enabled = " + enabled
                + ", nonTTY = " + isNonTty
                + ", data enabled = " + isDataEnabled
                + ", provisioned = " + isProvisioned
                + ", isFeatureOn = " + isFeatureOn);

        if (isFeatureOn) {
            request.addCapabilitiesToEnableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        } else {
            request.addCapabilitiesToDisableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        }
    }

    /**
     * Update WFC config
     */
    private void updateWfcFeatureAndProvisionedValues(CapabilityChangeRequest request) {
        TelephonyManager tm = new TelephonyManager(mContext, getSubId());
        boolean isNetworkRoaming = tm.isNetworkRoaming();
        boolean available = isWfcEnabledByPlatform();
        boolean enabled = isWfcEnabledByUser();
        boolean isProvisioned = isWfcProvisionedOnDevice();
        int mode = getWfcMode(isNetworkRoaming);
        boolean roaming = isWfcRoamingEnabledByUser();
        boolean isFeatureOn = available && enabled && isProvisioned;

        log("updateWfcFeatureAndProvisionedValues: available = " + available
                + ", enabled = " + enabled
                + ", mode = " + mode
                + ", provisioned = " + isProvisioned
                + ", roaming = " + roaming
                + ", isFeatureOn = " + isFeatureOn);

        if (isFeatureOn) {
            request.addCapabilitiesToEnableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
        } else {
            request.addCapabilitiesToDisableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
        }

        if (!isFeatureOn) {
            mode = ImsMmTelManager.WIFI_MODE_CELLULAR_PREFERRED;
            roaming = false;
        }
        setWfcModeInternal(mode);
        setWfcRoamingSettingInternal(roaming);
    }


    private void updateUtFeatureValue(CapabilityChangeRequest request) {
        boolean isCarrierSupported = isSuppServicesOverUtEnabledByPlatform();
        boolean requiresProvisioning = getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_UT_PROVISIONING_REQUIRED_BOOL);
        // Count as "provisioned" if we do not require provisioning.
        boolean isProvisioned = true;
        if (requiresProvisioning) {
            ITelephony telephony = ITelephony.Stub.asInterface(
                    TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .getTelephonyServiceRegisterer()
                            .get());
            // Only track UT over LTE, since we do not differentiate between UT over LTE and IWLAN
            // currently.
            try {
                if (telephony != null) {
                    isProvisioned = telephony.isMmTelCapabilityProvisionedInCache(getSubId(),
                            MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT,
                            ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
                }
            } catch (RemoteException e) {
                loge("updateUtFeatureValue: couldn't reach telephony! returning provisioned");
            }
        }
        boolean isFeatureOn = isCarrierSupported && isProvisioned;

        log("updateUtFeatureValue: available = " + isCarrierSupported
                + ", isProvisioned = " + isProvisioned
                + ", enabled = " + isFeatureOn);

        if (isFeatureOn) {
            request.addCapabilitiesToEnableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        } else {
            request.addCapabilitiesToDisableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        }
    }

    /**
     * Do NOT use this directly, instead use {@link #getInstance(Context, int)}.
     */
    @VisibleForTesting
    public ImsManager(Context context, int phoneId) {
        mContext = context;
        mPhoneId = phoneId;
        mConfigManager = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        createImsService();
    }

    /*
     * Returns a flag indicating whether the IMS service is available. If it is not available or
     * busy, it will try to connect before reporting failure.
     */
    public boolean isServiceAvailable() {
        connectIfServiceIsAvailable();
        // mImsServiceProxy will always create an ImsServiceProxy.
        return mMmTelFeatureConnection.isBinderAlive();
    }

    /*
     * Returns a flag indicating whether the IMS service is ready to send requests to lower layers.
     */
    public boolean isServiceReady() {
        connectIfServiceIsAvailable();
        return mMmTelFeatureConnection.isBinderReady();
    }

    /**
     * If the service is available, try to reconnect.
     */
    public void connectIfServiceIsAvailable() {
        if (mMmTelFeatureConnection == null || !mMmTelFeatureConnection.isBinderAlive()) {
            createImsService();
        }
    }

    public void setConfigListener(ImsConfigListener listener) {
        mImsConfigListener = listener;
    }


    /**
     * Adds a callback for status changed events if the binder is already available. If it is not,
     * this method will throw an ImsException.
     */
    @Override
    @VisibleForTesting
    public void addNotifyStatusChangedCallbackIfAvailable(FeatureConnection.IFeatureUpdate c)
            throws android.telephony.ims.ImsException {
        if (!mMmTelFeatureConnection.isBinderAlive()) {
            throw new android.telephony.ims.ImsException("Can not connect to ImsService",
                    android.telephony.ims.ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
        if (c != null) {
            mStatusCallbacks.add(c);
        }
    }

    @Override
    public void removeNotifyStatusChangedCallback(FeatureConnection.IFeatureUpdate c) {
        if (c != null) {
            mStatusCallbacks.remove(c);
        } else {
            logw("removeNotifyStatusChangedCallback: callback is null!");
        }
    }

    /**
     * Opens the IMS service for making calls and/or receiving generic IMS calls.
     * The caller may make subsequent calls through {@link #makeCall}.
     * The IMS service will register the device to the operator's network with the credentials
     * (from ISIM) periodically in order to receive calls from the operator's network.
     * When the IMS service receives a new call, it will call
     * {@link MmTelFeature.Listener#onIncomingCall}
     * @param listener A {@link MmTelFeature.Listener}, which is the interface the
     * {@link MmTelFeature} uses to notify the framework of updates
     * @throws NullPointerException if {@code listener} is null
     * @throws ImsException if calling the IMS service results in an error
     */
    public void open(MmTelFeature.Listener listener) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        if (listener == null) {
            throw new NullPointerException("listener can't be null");
        }

        try {
            mMmTelFeatureConnection.openConnection(listener);
        } catch (RemoteException e) {
            throw new ImsException("open()", e, ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Adds registration listener to the IMS service.
     *
     * @param serviceClass a service class specified in {@link ImsServiceClass}
     *      For VoLTE service, it MUST be a {@link ImsServiceClass#MMTEL}.
     * @param listener To listen to IMS registration events; It cannot be null
     * @throws NullPointerException if {@code listener} is null
     * @throws ImsException if calling the IMS service results in an error
     *
     * @deprecated Use {@link #addRegistrationListener(ImsConnectionStateListener)} instead.
     */
    public void addRegistrationListener(int serviceClass, ImsConnectionStateListener listener)
            throws ImsException {
        addRegistrationListener(listener);
    }

    /**
     * Adds registration listener to the IMS service.
     *
     * @param listener To listen to IMS registration events; It cannot be null
     * @throws NullPointerException if {@code listener} is null
     * @throws ImsException if calling the IMS service results in an error
     * @deprecated use {@link #addRegistrationCallback(RegistrationManager.RegistrationCallback)}
     * instead.
     */
    public void addRegistrationListener(ImsConnectionStateListener listener) throws ImsException {
        if (listener == null) {
            throw new NullPointerException("listener can't be null");
        }
        addRegistrationCallback(listener);
        // connect the ImsConnectionStateListener to the new CapabilityCallback.
        addCapabilitiesCallback(new ImsMmTelManager.CapabilityCallback() {
            @Override
            public void onCapabilitiesStatusChanged(
                    MmTelFeature.MmTelCapabilities capabilities) {
                listener.onFeatureCapabilityChangedAdapter(getRegistrationTech(), capabilities);
            }
        });
        log("Registration Callback registered.");
    }

    /**
     * Adds a callback that gets called when IMS registration has changed for the slot ID
     * associated with this ImsManager.
     * @param callback A {@link RegistrationManager.RegistrationCallback} that will notify the
     *                 caller when IMS registration status has changed.
     * @throws ImsException when the ImsService connection is not available.
     */
    public void addRegistrationCallback(RegistrationManager.RegistrationCallback callback)
            throws ImsException {
        if (callback == null) {
            throw new NullPointerException("registration callback can't be null");
        }

        try {
            callback.setExecutor(getThreadExecutor());
            mMmTelFeatureConnection.addRegistrationCallback(callback.getBinder());
            log("Registration Callback registered.");
            // Only record if there isn't a RemoteException.
        } catch (IllegalStateException e) {
            throw new ImsException("addRegistrationCallback(IRIB)", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Removes a previously added registration callback that was added via
     * {@link #addRegistrationCallback(RegistrationManager.RegistrationCallback)} .
     * @param callback A {@link RegistrationManager.RegistrationCallback} that was previously added.
     */
    public void removeRegistrationListener(RegistrationManager.RegistrationCallback callback) {
        if (callback == null) {
            throw new NullPointerException("registration callback can't be null");
        }

        mMmTelFeatureConnection.removeRegistrationCallback(callback.getBinder());
        log("Registration callback removed.");
    }

    /**
     * Adds a callback that gets called when IMS registration has changed for a specific
     * subscription.
     *
     * @param callback A {@link RegistrationManager.RegistrationCallback} that will notify the
     *                 caller when IMS registration status has changed.
     * @param subId The subscription ID to register this registration callback for.
     * @throws RemoteException when the ImsService connection is not available.
     */
    public void addRegistrationCallbackForSubscription(IImsRegistrationCallback callback, int subId)
            throws RemoteException {
        if (callback == null) {
            throw new IllegalArgumentException("registration callback can't be null");
        }
        mMmTelFeatureConnection.addRegistrationCallbackForSubscription(callback, subId);
        log("Registration Callback registered.");
        // Only record if there isn't a RemoteException.
    }

    /**
     * Removes a previously registered {@link RegistrationManager.RegistrationCallback} callback
     * that is associated with a specific subscription.
     */
    public void removeRegistrationCallbackForSubscription(IImsRegistrationCallback callback,
            int subId) {
        if (callback == null) {
            throw new IllegalArgumentException("registration callback can't be null");
        }

        mMmTelFeatureConnection.removeRegistrationCallbackForSubscription(callback, subId);
    }

    /**
     * Adds a callback that gets called when MMTel capability status has changed, for example when
     * Voice over IMS or VT over IMS is not available currently.
     * @param callback A {@link ImsMmTelManager.CapabilityCallback} that will notify the caller when
     *                 MMTel capability status has changed.
     * @throws ImsException when the ImsService connection is not available.
     */
    public void addCapabilitiesCallback(ImsMmTelManager.CapabilityCallback callback)
            throws ImsException {
        if (callback == null) {
            throw new NullPointerException("capabilities callback can't be null");
        }

        checkAndThrowExceptionIfServiceUnavailable();
        try {
            callback.setExecutor(getThreadExecutor());
            mMmTelFeatureConnection.addCapabilityCallback(callback.getBinder());
            log("Capability Callback registered.");
            // Only record if there isn't a RemoteException.
        } catch (IllegalStateException e) {
            throw new ImsException("addCapabilitiesCallback(IF)", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Removes a previously registered {@link ImsMmTelManager.CapabilityCallback} callback.
     * @throws ImsException when the ImsService connection is not available.
     */
    public void removeCapabilitiesCallback(ImsMmTelManager.CapabilityCallback callback)
            throws ImsException {
        if (callback == null) {
            throw new NullPointerException("capabilities callback can't be null");
        }

        checkAndThrowExceptionIfServiceUnavailable();
        mMmTelFeatureConnection.removeCapabilityCallback(callback.getBinder());
    }

    /**
     * Adds a callback that gets called when IMS capabilities have changed for a specified
     * subscription.
     * @param callback A {@link ImsMmTelManager.CapabilityCallback} that will notify the caller
     *                 when the IMS Capabilities have changed.
     * @param subId The subscription that is associated with the callback.
     * @throws RemoteException when the ImsService connection is not available.
     */
    public void addCapabilitiesCallbackForSubscription(IImsCapabilityCallback callback, int subId)
            throws RemoteException {
        if (callback == null) {
            throw new IllegalArgumentException("registration callback can't be null");
        }

        mMmTelFeatureConnection.addCapabilityCallbackForSubscription(callback, subId);
        log("Capability Callback registered for subscription.");
    }

    /**
     * Removes a previously registered {@link ImsMmTelManager.CapabilityCallback} that was
     * associated with a specific subscription.
     */
    public void removeCapabilitiesCallbackForSubscription(IImsCapabilityCallback callback,
            int subId) {
        if (callback == null) {
            throw new IllegalArgumentException("capabilities callback can't be null");
        }

        mMmTelFeatureConnection.removeCapabilityCallbackForSubscription(callback, subId);
    }

    /**
     * Removes the registration listener from the IMS service.
     *
     * @param listener Previously registered listener that will be removed. Can not be null.
     * @throws NullPointerException if {@code listener} is null
     * @throws ImsException if calling the IMS service results in an error
     * instead.
     */
    public void removeRegistrationListener(ImsConnectionStateListener listener)
            throws ImsException {
        if (listener == null) {
            throw new NullPointerException("listener can't be null");
        }

        checkAndThrowExceptionIfServiceUnavailable();
        mMmTelFeatureConnection.removeRegistrationCallback(listener.getBinder());
        log("Registration Callback/Listener registered.");
        // Only record if there isn't a RemoteException.
    }

    /**
     * Adds a callback that gets called when Provisioning has changed for a specified subscription.
     * @param callback A {@link ProvisioningManager.Callback} that will notify the caller when
     *                 provisioning has changed.
     * @param subId The subscription that is associated with the callback.
     * @throws IllegalStateException when the {@link ImsService} connection is not available.
     * @throws IllegalArgumentException when the {@link IImsConfigCallback} argument is null.
     */
    public void addProvisioningCallbackForSubscription(IImsConfigCallback callback, int subId) {
        if (callback == null) {
            throw new IllegalArgumentException("provisioning callback can't be null");
        }

        mMmTelFeatureConnection.addProvisioningCallbackForSubscription(callback, subId);
        log("Capability Callback registered for subscription.");
    }

    /**
     * Removes a previously registered {@link ProvisioningManager.Callback} that was associated with
     * a specific subscription.
     * @throws IllegalStateException when the {@link ImsService} connection is not available.
     * @throws IllegalArgumentException when the {@link IImsConfigCallback} argument is null.
     */
    public void removeProvisioningCallbackForSubscription(IImsConfigCallback callback, int subId) {
        if (callback == null) {
            throw new IllegalArgumentException("provisioning callback can't be null");
        }

        mMmTelFeatureConnection.removeProvisioningCallbackForSubscription(callback, subId);
    }

    public @ImsRegistrationImplBase.ImsRegistrationTech int getRegistrationTech() {
        try {
            return mMmTelFeatureConnection.getRegistrationTech();
        } catch (RemoteException e) {
            logw("getRegistrationTech: no connection to ImsService.");
            return ImsRegistrationImplBase.REGISTRATION_TECH_NONE;
        }
    }

    public void getRegistrationTech(Consumer<Integer> callback) {
        mExecutorFactory.executeRunnable(() -> {
            try {
                int tech = mMmTelFeatureConnection.getRegistrationTech();
                callback.accept(tech);
            } catch (RemoteException e) {
                logw("getRegistrationTech(C): no connection to ImsService.");
                callback.accept(ImsRegistrationImplBase.REGISTRATION_TECH_NONE);
            }
        });
    }

    /**
     * Closes the connection and removes all active callbacks.
     * All the resources that were allocated to the service are also released.
     */
    public void close() {
        if (mMmTelFeatureConnection != null) {
            mMmTelFeatureConnection.closeConnection();
        }
        mUt = null;
        mEcbm = null;
        mMultiEndpoint = null;
    }

    /**
     * Gets the configuration interface to provision / withdraw the supplementary service settings.
     *
     * @return the Ut interface instance
     * @throws ImsException if getting the Ut interface results in an error
     */
    public ImsUtInterface getSupplementaryServiceConfiguration() throws ImsException {
        // FIXME: manage the multiple Ut interfaces based on the session id
        if (mUt != null && mUt.isBinderAlive()) {
            return mUt;
        }

        checkAndThrowExceptionIfServiceUnavailable();
        try {
            IImsUt iUt = mMmTelFeatureConnection.getUtInterface();

            if (iUt == null) {
                throw new ImsException("getSupplementaryServiceConfiguration()",
                        ImsReasonInfo.CODE_UT_NOT_SUPPORTED);
            }

            mUt = new ImsUt(iUt);
        } catch (RemoteException e) {
            throw new ImsException("getSupplementaryServiceConfiguration()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
        return mUt;
    }

    /**
     * Creates a {@link ImsCallProfile} from the service capabilities & IMS registration state.
     *
     * @param serviceType a service type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#SERVICE_TYPE_NONE}
     *        {@link ImsCallProfile#SERVICE_TYPE_NORMAL}
     *        {@link ImsCallProfile#SERVICE_TYPE_EMERGENCY}
     * @param callType a call type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE}
     *        {@link ImsCallProfile#CALL_TYPE_VT}
     *        {@link ImsCallProfile#CALL_TYPE_VT_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_RX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_NODIR}
     *        {@link ImsCallProfile#CALL_TYPE_VS}
     *        {@link ImsCallProfile#CALL_TYPE_VS_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VS_RX}
     * @return a {@link ImsCallProfile} object
     * @throws ImsException if calling the IMS service results in an error
     */
    public ImsCallProfile createCallProfile(int serviceType, int callType) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            return mMmTelFeatureConnection.createCallProfile(serviceType, callType);
        } catch (RemoteException e) {
            throw new ImsException("createCallProfile()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Creates a {@link ImsCall} to make a call.
     *
     * @param profile a call profile to make the call
     *      (it contains service type, call type, media information, etc.)
     * @param callees participants to invite the conference call
     * @param listener listen to the call events from {@link ImsCall}
     * @return a {@link ImsCall} object
     * @throws ImsException if calling the IMS service results in an error
     */
    public ImsCall makeCall(ImsCallProfile profile, String[] callees,
            ImsCall.Listener listener) throws ImsException {
        if (DBG) {
            log("makeCall :: profile=" + profile);
        }

        checkAndThrowExceptionIfServiceUnavailable();

        ImsCall call = new ImsCall(mContext, profile);

        call.setListener(listener);
        ImsCallSession session = createCallSession(profile);

        if ((callees != null) && (callees.length == 1) && !(session.isMultiparty())) {
            call.start(session, callees[0]);
        } else {
            call.start(session, callees);
        }

        return call;
    }

    /**
     * Creates a {@link ImsCall} to take an incoming call.
     *
     * @param listener to listen to the call events from {@link ImsCall}
     * @return a {@link ImsCall} object
     * @throws ImsException if calling the IMS service results in an error
     */
    public ImsCall takeCall(IImsCallSession session, ImsCall.Listener listener)
            throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();
        try {
            if (session == null) {
                throw new ImsException("No pending session for the call",
                        ImsReasonInfo.CODE_LOCAL_NO_PENDING_CALL);
            }

            ImsCall call = new ImsCall(mContext, session.getCallProfile());

            call.attachSession(new ImsCallSession(session));
            call.setListener(listener);

            return call;
        } catch (Throwable t) {
            loge("takeCall caught: ", t);
            throw new ImsException("takeCall()", t, ImsReasonInfo.CODE_UNSPECIFIED);
        }
    }

    /**
     * Gets the config interface to get/set service/capability parameters.
     *
     * @return the ImsConfig instance.
     * @throws ImsException if getting the setting interface results in an error.
     */
    @UnsupportedAppUsage
    public ImsConfig getConfigInterface() throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        IImsConfig config = mMmTelFeatureConnection.getConfigInterface();
        if (config == null) {
            throw new ImsException("getConfigInterface()",
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        return new ImsConfig(config);
    }

    public void changeMmTelCapability(
            @MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech,
            boolean isEnabled) throws ImsException {

        CapabilityChangeRequest request = new CapabilityChangeRequest();
        if (isEnabled) {
            request.addCapabilitiesToEnableForTech(capability, radioTech);
        } else {
            request.addCapabilitiesToDisableForTech(capability, radioTech);
        }
        changeMmTelCapability(request);
    }

    public void changeMmTelCapability(CapabilityChangeRequest r) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();
        try {
            logi("changeMmTelCapability: changing capabilities for sub: " + getSubId()
                    + ", request: " + r);
            mMmTelFeatureConnection.changeEnabledCapabilities(r, null);
            if (mImsConfigListener == null) {
                return;
            }
            for (CapabilityChangeRequest.CapabilityPair enabledCaps : r.getCapabilitiesToEnable()) {
                mImsConfigListener.onSetFeatureResponse(enabledCaps.getCapability(),
                        enabledCaps.getRadioTech(),
                        ProvisioningManager.PROVISIONING_VALUE_ENABLED, -1);
            }
            for (CapabilityChangeRequest.CapabilityPair disabledCaps :
                    r.getCapabilitiesToDisable()) {
                mImsConfigListener.onSetFeatureResponse(disabledCaps.getCapability(),
                        disabledCaps.getRadioTech(),
                        ProvisioningManager.PROVISIONING_VALUE_DISABLED, -1);
            }
        } catch (RemoteException e) {
            throw new ImsException("changeMmTelCapability(CCR)", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    public boolean updateRttConfigValue() {
        // If there's no active sub anywhere on the device, enable RTT on the modem so that
        // the device can make an emergency call.

        boolean isActiveSubscriptionPresent = isActiveSubscriptionPresent();
        boolean isCarrierSupported =
                getBooleanCarrierConfig(CarrierConfigManager.KEY_RTT_SUPPORTED_BOOL)
                || !isActiveSubscriptionPresent;

        boolean isRttUiSettingEnabled = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.RTT_CALLING_MODE, 0) != 0;
        boolean isRttAlwaysOnCarrierConfig = getBooleanCarrierConfig(
                CarrierConfigManager.KEY_IGNORE_RTT_MODE_SETTING_BOOL);

        boolean shouldImsRttBeOn = isRttUiSettingEnabled || isRttAlwaysOnCarrierConfig;
        logi("update RTT: settings value: " + isRttUiSettingEnabled + " always-on carrierconfig: "
                + isRttAlwaysOnCarrierConfig
                + "isActiveSubscriptionPresent: " + isActiveSubscriptionPresent);

        if (isCarrierSupported) {
            setRttConfig(shouldImsRttBeOn);
        } else {
            setRttConfig(false);
        }
        return isCarrierSupported && shouldImsRttBeOn;
    }

    private void setRttConfig(boolean enabled) {
        final int value = enabled ? ProvisioningManager.PROVISIONING_VALUE_ENABLED :
                ProvisioningManager.PROVISIONING_VALUE_DISABLED;
        mExecutorFactory.executeRunnable(() -> {
            try {
                logi("Setting RTT enabled to " + enabled);
                getConfigInterface().setProvisionedValue(
                        ImsConfig.ConfigConstants.RTT_SETTING_ENABLED, value);
            } catch (ImsException e) {
                loge("Unable to set RTT value enabled to " + enabled + ": " + e);
            }
        });
    }

    public boolean queryMmTelCapability(
            @MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        BlockingQueue<Boolean> result = new LinkedBlockingDeque<>(1);

        try {
            mMmTelFeatureConnection.queryEnabledCapabilities(capability, radioTech,
                    new IImsCapabilityCallback.Stub() {
                        @Override
                        public void onQueryCapabilityConfiguration(int resCap, int resTech,
                                boolean enabled) {
                            if (resCap == capability && resTech == radioTech) {
                                result.offer(enabled);
                            }
                        }

                        @Override
                        public void onChangeCapabilityConfigurationError(int capability,
                                int radioTech, int reason) {

                        }

                        @Override
                        public void onCapabilitiesStatusChanged(int config) {

                        }
                    });
        } catch (RemoteException e) {
            throw new ImsException("queryMmTelCapability()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }

        try {
            return result.poll(RESPONSE_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            logw("queryMmTelCapability: interrupted while waiting for response");
        }
        return false;
    }

    public boolean queryMmTelCapabilityStatus(
            @MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        if (getRegistrationTech() != radioTech)
            return false;

        try {

            MmTelFeature.MmTelCapabilities capabilities =
                    mMmTelFeatureConnection.queryCapabilityStatus();

            return capabilities.isCapable(capability);
        } catch (RemoteException e) {
            throw new ImsException("queryMmTelCapabilityStatus()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    public void setRttEnabled(boolean enabled) {
        try {
            if (enabled) {
                setEnhanced4gLteModeSetting(enabled);
            } else {
                setAdvanced4GMode(enabled || isEnhanced4gLteModeSettingEnabledByUser());
            }
            setRttConfig(enabled);
        } catch (ImsException e) {
            loge("Unable to set RTT enabled to " + enabled + ": " + e);
        }
    }

    /**
     * Set the TTY mode. This is the actual tty mode (varies depending on peripheral status)
     */
    public void setTtyMode(int ttyMode) throws ImsException {
        if (!getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL)) {
            setAdvanced4GMode((ttyMode == TelecomManager.TTY_MODE_OFF) &&
                    isEnhanced4gLteModeSettingEnabledByUser());
        }
    }

    /**
     * Sets the UI TTY mode. This is the preferred TTY mode that the user sets in the call
     * settings screen.
     * @param uiTtyMode TTY Mode, valid options are:
     *         - {@link com.android.internal.telephony.Phone#TTY_MODE_OFF}
     *         - {@link com.android.internal.telephony.Phone#TTY_MODE_FULL}
     *         - {@link com.android.internal.telephony.Phone#TTY_MODE_HCO}
     *         - {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     * @param onComplete A Message that will be called by the ImsService when it has completed this
     *           operation or null if not waiting for an async response. The Message must contain a
     *           valid {@link Message#replyTo} {@link android.os.Messenger}, since it will be passed
     *           through Binder to another process.
     */
    public void setUiTTYMode(Context context, int uiTtyMode, Message onComplete)
            throws ImsException {

        checkAndThrowExceptionIfServiceUnavailable();

        try {
            mMmTelFeatureConnection.setUiTTYMode(uiTtyMode, onComplete);
        } catch (RemoteException e) {
            throw new ImsException("setTTYMode()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    private ImsReasonInfo makeACopy(ImsReasonInfo imsReasonInfo) {
        Parcel p = Parcel.obtain();
        imsReasonInfo.writeToParcel(p, 0);
        p.setDataPosition(0);
        ImsReasonInfo clonedReasonInfo = ImsReasonInfo.CREATOR.createFromParcel(p);
        p.recycle();
        return clonedReasonInfo;
    }

    /**
     * Get Recent IMS Disconnect Reasons.
     *
     * @return ArrayList of ImsReasonInfo objects. MAX size of the arraylist
     * is MAX_RECENT_DISCONNECT_REASONS. The objects are in the
     * chronological order.
     */
    public ArrayList<ImsReasonInfo> getRecentImsDisconnectReasons() {
        ArrayList<ImsReasonInfo> disconnectReasons = new ArrayList<>();

        for (ImsReasonInfo reason : mRecentDisconnectReasons) {
            disconnectReasons.add(makeACopy(reason));
        }
        return disconnectReasons;
    }

    @Override
    public int getImsServiceState() throws ImsException {
        return mMmTelFeatureConnection.getFeatureState();
    }

    public void getImsServiceState(Consumer<Integer> result) {
        mExecutorFactory.executeRunnable(() -> {
            try {
                result.accept(getImsServiceState());
            } catch (ImsException e) {
                // In the case that the ImsService is not available, report unavailable.
                result.accept(ImsFeature.STATE_UNAVAILABLE);
            }
        });
    }

    private Executor getThreadExecutor() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        return new HandlerExecutor(new Handler(Looper.myLooper()));
    }

    /**
     * Get the boolean config from carrier config manager.
     *
     * @param key config key defined in CarrierConfigManager
     * @return boolean value of corresponding key.
     */
    private boolean getBooleanCarrierConfig(String key) {
        PersistableBundle b = null;
        if (mConfigManager != null) {
            // If an invalid subId is used, this bundle will contain default values.
            b = mConfigManager.getConfigForSubId(getSubId());
        }
        if (b != null) {
            return b.getBoolean(key);
        } else {
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getBoolean(key);
        }
    }

    /**
     * Get the int config from carrier config manager.
     *
     * @param key config key defined in CarrierConfigManager
     * @return integer value of corresponding key.
     */
    private int getIntCarrierConfig(String key) {
        PersistableBundle b = null;
        if (mConfigManager != null) {
            // If an invalid subId is used, this bundle will contain default values.
            b = mConfigManager.getConfigForSubId(getSubId());
        }
        if (b != null) {
            return b.getInt(key);
        } else {
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getInt(key);
        }
    }

    /**
     * Checks to see if the ImsService Binder is connected. If it is not, we try to create the
     * connection again.
     */
    private void checkAndThrowExceptionIfServiceUnavailable()
            throws ImsException {
        if (!isImsSupportedOnDevice(mContext)) {
            throw new ImsException("IMS not supported on device.",
                    ImsReasonInfo.CODE_LOCAL_IMS_NOT_SUPPORTED_ON_DEVICE);
        }
        if (mMmTelFeatureConnection == null || !mMmTelFeatureConnection.isBinderAlive()) {
            createImsService();

            if (mMmTelFeatureConnection == null) {
                throw new ImsException("Service is unavailable",
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }
    }

    /**
     * Creates a connection to the ImsService associated with this slot.
     */
    private void createImsService() {
        mMmTelFeatureConnection = MmTelFeatureConnection.create(mContext, mPhoneId);

        // Forwarding interface to tell mStatusCallbacks that the Proxy is unavailable.
        mMmTelFeatureConnection.setStatusCallback(new FeatureConnection.IFeatureUpdate() {
            @Override
            public void notifyStateChanged() {
                mStatusCallbacks.forEach(FeatureConnection.IFeatureUpdate::notifyStateChanged);
            }

            @Override
            public void notifyUnavailable() {
                mStatusCallbacks.forEach(FeatureConnection.IFeatureUpdate::notifyUnavailable);
            }
        });
    }

    /**
     * Creates a {@link ImsCallSession} with the specified call profile.
     * Use other methods, if applicable, instead of interacting with
     * {@link ImsCallSession} directly.
     *
     * @param profile a call profile to make the call
     */
    private ImsCallSession createCallSession(ImsCallProfile profile) throws ImsException {
        try {
            // Throws an exception if the ImsService Feature is not ready to accept commands.
            return new ImsCallSession(mMmTelFeatureConnection.createCallSession(profile));
        } catch (RemoteException e) {
            logw("CreateCallSession: Error, remote exception: " + e.getMessage());
            throw new ImsException("createCallSession()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);

        }
    }

    private void log(String s) {
        Rlog.d(TAG + " [" + mPhoneId + "]", s);
    }

    private void logi(String s) {
        Rlog.i(TAG + " [" + mPhoneId + "]", s);
    }
    
    private void logw(String s) {
        Rlog.w(TAG + " [" + mPhoneId + "]", s);
    }

    private void loge(String s) {
        Rlog.e(TAG + " [" + mPhoneId + "]", s);
    }

    private void loge(String s, Throwable t) {
        Rlog.e(TAG + " [" + mPhoneId + "]", s, t);
    }

    /**
     * Used for turning on IMS.if its off already
     */
    private void turnOnIms() throws ImsException {
        TelephonyManager tm = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        tm.enableIms(mPhoneId);
    }

    private boolean isImsTurnOffAllowed() {
        return isTurnOffImsAllowedByPlatform()
                && (!isWfcEnabledByPlatform()
                || !isWfcEnabledByUser());
    }

    private void setLteFeatureValues(boolean turnOn) {
        log("setLteFeatureValues: " + turnOn);
        CapabilityChangeRequest request = new CapabilityChangeRequest();
        if (turnOn) {
            request.addCapabilitiesToEnableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        } else {
            request.addCapabilitiesToDisableForTech(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        }

        if (isVtEnabledByPlatform()) {
            boolean ignoreDataEnabledChanged = getBooleanCarrierConfig(
                    CarrierConfigManager.KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS);
            boolean enableViLte = turnOn && isVtEnabledByUser() &&
                    (ignoreDataEnabledChanged || isDataEnabled());
            if (enableViLte) {
                request.addCapabilitiesToEnableForTech(
                        MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO,
                        ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
            } else {
                request.addCapabilitiesToDisableForTech(
                        MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO,
                        ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
            }
        }
        try {
            mMmTelFeatureConnection.changeEnabledCapabilities(request, null);
        } catch (RemoteException e) {
            loge("setLteFeatureValues: Exception: " + e.getMessage());
        }
    }

    private void setAdvanced4GMode(boolean turnOn) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        // if turnOn: first set feature values then call turnOnIms()
        // if turnOff: only set feature values if IMS turn off is not allowed. If turn off is
        // allowed, first call turnOffIms() then set feature values
        if (turnOn) {
            setLteFeatureValues(turnOn);
            log("setAdvanced4GMode: turnOnIms");
            turnOnIms();
        } else {
            if (isImsTurnOffAllowed()) {
                log("setAdvanced4GMode: turnOffIms");
                turnOffIms();
            }
            setLteFeatureValues(turnOn);
        }
    }

    /**
     * Used for turning off IMS completely in order to make the device CSFB'ed.
     * Once turned off, all calls will be over CS.
     */
    private void turnOffIms() throws ImsException {
        TelephonyManager tm = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        tm.disableIms(mPhoneId);
    }

    private void addToRecentDisconnectReasons(ImsReasonInfo reason) {
        if (reason == null) return;
        while (mRecentDisconnectReasons.size() >= MAX_RECENT_DISCONNECT_REASONS) {
            mRecentDisconnectReasons.removeFirst();
        }
        mRecentDisconnectReasons.addLast(reason);
    }

    /**
     * Gets the ECBM interface to request ECBM exit.
     *
     * @return the ECBM interface instance
     * @throws ImsException if getting the ECBM interface results in an error
     */
    public ImsEcbm getEcbmInterface() throws ImsException {
        if (mEcbm != null && mEcbm.isBinderAlive()) {
            return mEcbm;
        }

        checkAndThrowExceptionIfServiceUnavailable();
        try {
            IImsEcbm iEcbm = mMmTelFeatureConnection.getEcbmInterface();

            if (iEcbm == null) {
                throw new ImsException("getEcbmInterface()",
                        ImsReasonInfo.CODE_ECBM_NOT_SUPPORTED);
            }
            mEcbm = new ImsEcbm(iEcbm);
        } catch (RemoteException e) {
            throw new ImsException("getEcbmInterface()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
        return mEcbm;
    }

    public void sendSms(int token, int messageRef, String format, String smsc, boolean isRetry,
            byte[] pdu) throws ImsException {
        try {
            mMmTelFeatureConnection.sendSms(token, messageRef, format, smsc, isRetry, pdu);
        } catch (RemoteException e) {
            throw new ImsException("sendSms()", e, ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    public void acknowledgeSms(int token, int messageRef, int result) throws ImsException {
        try {
            mMmTelFeatureConnection.acknowledgeSms(token, messageRef, result);
        } catch (RemoteException e) {
            throw new ImsException("acknowledgeSms()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    public void acknowledgeSmsReport(int token, int messageRef, int result) throws  ImsException{
        try {
            mMmTelFeatureConnection.acknowledgeSmsReport(token, messageRef, result);
        } catch (RemoteException e) {
            throw new ImsException("acknowledgeSmsReport()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    public String getSmsFormat() throws ImsException{
        try {
            return mMmTelFeatureConnection.getSmsFormat();
        } catch (RemoteException e) {
            throw new ImsException("getSmsFormat()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    public void setSmsListener(IImsSmsListener listener) throws ImsException {
        try {
            mMmTelFeatureConnection.setSmsListener(listener);
        } catch (RemoteException e) {
            throw new ImsException("setSmsListener()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    public void onSmsReady() throws ImsException {
        try {
            mMmTelFeatureConnection.onSmsReady();
        } catch (RemoteException e) {
            throw new ImsException("onSmsReady()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Determines whether or not a call with the specified numbers should be placed over IMS or over
     * CSFB.
     * @param isEmergency is at least one call an emergency number.
     * @param numbers A {@link String} array containing the numbers in the call being placed. Can
     *         be multiple numbers in the case of dialing out a conference.
     * @return The result of the query, one of the following values:
     *         - {@link MmTelFeature#PROCESS_CALL_IMS}
     *         - {@link MmTelFeature#PROCESS_CALL_CSFB}
     * @throws ImsException if the ImsService is not available. In this case, we should fall back
     * to CSFB anyway.
     */
    public @MmTelFeature.ProcessCallResult int shouldProcessCall(boolean isEmergency,
            String[] numbers) throws ImsException {
        try {
            return mMmTelFeatureConnection.shouldProcessCall(isEmergency, numbers);
        } catch (RemoteException e) {
            throw new ImsException("shouldProcessCall()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Gets the Multi-Endpoint interface to subscribe to multi-enpoint notifications..
     *
     * @return the multi-endpoint interface instance
     * @throws ImsException if getting the multi-endpoint interface results in an error
     */
    public ImsMultiEndpoint getMultiEndpointInterface() throws ImsException {
        if (mMultiEndpoint != null && mMultiEndpoint.isBinderAlive()) {
            return mMultiEndpoint;
        }

        checkAndThrowExceptionIfServiceUnavailable();
        try {
            IImsMultiEndpoint iImsMultiEndpoint = mMmTelFeatureConnection.getMultiEndpointInterface();

            if (iImsMultiEndpoint == null) {
                throw new ImsException("getMultiEndpointInterface()",
                        ImsReasonInfo.CODE_MULTIENDPOINT_NOT_SUPPORTED);
            }
            mMultiEndpoint = new ImsMultiEndpoint(iImsMultiEndpoint);
        } catch (RemoteException e) {
            throw new ImsException("getMultiEndpointInterface()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }

        return mMultiEndpoint;
    }

    /**
     * Resets ImsManager settings back to factory defaults.
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #factoryReset()} instead.
     *
     * @hide
     */
    public static void factoryReset(Context context) {
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (mgr != null) {
            mgr.factoryReset();
        }
        Rlog.e(TAG, "factoryReset: ImsManager null.");
    }

    /**
     * Resets ImsManager settings back to factory defaults.
     *
     * @hide
     */
    public void factoryReset() {
        int subId = getSubId();
        if (isSubIdValid(subId)) {
            // Set VoLTE to default
            SubscriptionManager.setSubscriptionProperty(subId,
                    SubscriptionManager.ENHANCED_4G_MODE_ENABLED,
                    Integer.toString(SUB_PROPERTY_NOT_INITIALIZED));

            // Set VoWiFi to default
            SubscriptionManager.setSubscriptionProperty(subId,
                    SubscriptionManager.WFC_IMS_ENABLED,
                    Integer.toString(SUB_PROPERTY_NOT_INITIALIZED));

            // Set VoWiFi mode to default
            SubscriptionManager.setSubscriptionProperty(subId,
                    SubscriptionManager.WFC_IMS_MODE,
                    Integer.toString(SUB_PROPERTY_NOT_INITIALIZED));

            // Set VoWiFi roaming to default
            SubscriptionManager.setSubscriptionProperty(subId,
                    SubscriptionManager.WFC_IMS_ROAMING_ENABLED,
                    Integer.toString(SUB_PROPERTY_NOT_INITIALIZED));

            // Set VoWiFi roaming mode to default
            SubscriptionManager.setSubscriptionProperty(subId,
                    SubscriptionManager.WFC_IMS_ROAMING_MODE,
                    Integer.toString(SUB_PROPERTY_NOT_INITIALIZED));


            // Set VT to default
            SubscriptionManager.setSubscriptionProperty(subId,
                    SubscriptionManager.VT_IMS_ENABLED,
                    Integer.toString(SUB_PROPERTY_NOT_INITIALIZED));

            // Set RCS UCE to default
            SubscriptionManager.setSubscriptionProperty(subId,
                    SubscriptionManager.IMS_RCS_UCE_ENABLED, Integer.toString(
                            SUBINFO_PROPERTY_FALSE));
        } else {
            loge("factoryReset: invalid sub id, can not reset siminfo db settings; subId=" + subId);
        }

        // Push settings to ImsConfig
        updateImsServiceConfig(true);
    }

    public void setVolteProvisioned(boolean isProvisioned) {
        int provisionStatus = isProvisioned ? ProvisioningManager.PROVISIONING_VALUE_ENABLED :
                ProvisioningManager.PROVISIONING_VALUE_DISABLED;
        setProvisionedBoolNoException(ImsConfig.ConfigConstants.VLT_SETTING_ENABLED,
                provisionStatus);
    }

    public void setWfcProvisioned(boolean isProvisioned) {
        int provisionStatus = isProvisioned ? ProvisioningManager.PROVISIONING_VALUE_ENABLED :
                ProvisioningManager.PROVISIONING_VALUE_DISABLED;
        setProvisionedBoolNoException(
                ImsConfig.ConfigConstants.VOICE_OVER_WIFI_SETTING_ENABLED, provisionStatus);
    }

    public void setVtProvisioned(boolean isProvisioned) {
        int provisionStatus = isProvisioned ? ProvisioningManager.PROVISIONING_VALUE_ENABLED :
                ProvisioningManager.PROVISIONING_VALUE_DISABLED;
        setProvisionedBoolNoException(ImsConfig.ConfigConstants.LVC_SETTING_ENABLED,
                provisionStatus);
    }

    public void setEabProvisioned(boolean isProvisioned) {
        int provisionStatus = isProvisioned ? ProvisioningManager.PROVISIONING_VALUE_ENABLED :
                ProvisioningManager.PROVISIONING_VALUE_DISABLED;
        setProvisionedBoolNoException(ImsConfig.ConfigConstants.EAB_SETTING_ENABLED,
                provisionStatus);
    }

    private boolean isDataEnabled() {
        return new TelephonyManager(mContext, getSubId()).isDataConnectionAllowed();
    }

    private boolean isVolteProvisioned() {
        return getProvisionedBoolNoException(
                ImsConfig.ConfigConstants.VLT_SETTING_ENABLED);
    }

    private boolean isEabProvisioned() {
        return getProvisionedBoolNoException(
                ImsConfig.ConfigConstants.EAB_SETTING_ENABLED);
    }

    private boolean isWfcProvisioned() {
        return getProvisionedBoolNoException(
                ImsConfig.ConfigConstants.VOICE_OVER_WIFI_SETTING_ENABLED);
    }

    private boolean isVtProvisioned() {
        return getProvisionedBoolNoException(
                ImsConfig.ConfigConstants.LVC_SETTING_ENABLED);
    }

    private static String booleanToPropertyString(boolean bool) {
        return bool ? "1" : "0";
    }


    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ImsManager:");
        pw.println("  device supports IMS = " + isImsSupportedOnDevice(mContext));
        pw.println("  mPhoneId = " + mPhoneId);
        pw.println("  mConfigUpdated = " + mConfigUpdated);
        pw.println("  mImsServiceProxy = " + mMmTelFeatureConnection);
        pw.println("  mDataEnabled = " + isDataEnabled());
        pw.println("  ignoreDataEnabledChanged = " + getBooleanCarrierConfig(
                CarrierConfigManager.KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS));

        pw.println("  isGbaValid = " + isGbaValid());
        pw.println("  isImsTurnOffAllowed = " + isImsTurnOffAllowed());
        pw.println("  isNonTtyOrTtyOnVolteEnabled = " + isNonTtyOrTtyOnVolteEnabled());

        pw.println("  isVolteEnabledByPlatform = " + isVolteEnabledByPlatform());
        pw.println("  isVolteProvisionedOnDevice = " + isVolteProvisionedOnDevice());
        pw.println("  isEnhanced4gLteModeSettingEnabledByUser = " +
                isEnhanced4gLteModeSettingEnabledByUser());
        pw.println("  isVtEnabledByPlatform = " + isVtEnabledByPlatform());
        pw.println("  isVtEnabledByUser = " + isVtEnabledByUser());

        pw.println("  isWfcEnabledByPlatform = " + isWfcEnabledByPlatform());
        pw.println("  isWfcEnabledByUser = " + isWfcEnabledByUser());
        pw.println("  getWfcMode(non-roaming) = " + getWfcMode(false));
        pw.println("  getWfcMode(roaming) = " + getWfcMode(true));
        pw.println("  isWfcRoamingEnabledByUser = " + isWfcRoamingEnabledByUser());

        pw.println("  isVtProvisionedOnDevice = " + isVtProvisionedOnDevice());
        pw.println("  isWfcProvisionedOnDevice = " + isWfcProvisionedOnDevice());
        pw.flush();
    }

    /**
     * Determines if a sub id is valid.
     * Mimics the logic in SubscriptionController.validateSubId.
     * @param subId The sub id to check.
     * @return {@code true} if valid, {@code false} otherwise.
     */
    private boolean isSubIdValid(int subId) {
        return SubscriptionManager.isValidSubscriptionId(subId) &&
                subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
    }

    private boolean isActiveSubscriptionPresent() {
        SubscriptionManager sm = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        return sm.getActiveSubscriptionIdList().length > 0;
    }

    private void updateImsCarrierConfigs(PersistableBundle configs) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        IImsConfig config = mMmTelFeatureConnection.getConfigInterface();
        if (config == null) {
            throw new ImsException("getConfigInterface()",
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        try {
            config.updateImsCarrierConfigs(configs);
        } catch (RemoteException e) {
            throw new ImsException("updateImsCarrierConfigs()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }
}

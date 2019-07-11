/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.dataconnection;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RegistrantList;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.telephony.Rlog;

import com.android.internal.telephony.cdma.CDMALTEPhone;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.ArrayUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Objects;

import android.provider.Settings;

import com.android.internal.telephony.ServiceStateTracker;
/**
 * {@hide}
 */
public final class DcTracker extends DcTrackerBase {
    protected final String LOG_TAG = "DCT";

    /**
     * List of messages that are waiting to be posted, when data call disconnect
     * is complete
     */
    private ArrayList<Message> mDisconnectAllCompleteMsgList = new ArrayList<Message>();

    private RegistrantList mAllDataDisconnectedRegistrants = new RegistrantList();

    protected int mDisconnectPendingCount = 0;

    /**
     * Handles changes to the APN db.
     */
    private class ApnChangeObserver extends ContentObserver {
        public ApnChangeObserver () {
            super(mDataConnectionTracker);
        }

        @Override
        public void onChange(boolean selfChange) {
            sendMessage(obtainMessage(DctConstants.EVENT_APN_CHANGED));
        }
    }

    //***** Instance Variables

    private boolean mReregisterOnReconnectFailure = false;


    //***** Constants

    // Used by puppetmaster/*/radio_stress.py
    private static final String PUPPET_MASTER_RADIO_STRESS_TEST = "gsm.defaultpdpcontext.active";

    private static final int POLL_PDP_MILLIS = 5 * 1000;

    static final Uri PREFERAPN_NO_UPDATE_URI =
                        Uri.parse("content://telephony/carriers/preferapn_no_update");
    static final String APN_ID = "apn_id";

    private boolean mCanSetPreferApn = false;

    private AtomicBoolean mAttached = new AtomicBoolean(false);

    /** Watches for changes to the APN db. */
    private ApnChangeObserver mApnObserver;

    private final String mProvisionActionName;
    private BroadcastReceiver mProvisionBroadcastReceiver;

    /** Used to send us NetworkRequests from ConnectivityService.  Remeber it so we can
     * unregister on dispose. */
    private Messenger mNetworkFactoryMessenger;
    private NetworkFactory mNetworkFactory;
    private NetworkCapabilities mNetworkFilter;

    public boolean mImsRegistrationState = false;
    private ApnContext mWaitCleanUpApnContext = null;
    private boolean mDeregistrationAlarmState = false;
    private PendingIntent mImsDeregistrationDelayIntent = null;

    //***** Constructor
    public DcTracker(PhoneBase p) {
        super(p);
        if (DBG) log("GsmDCT.constructor");

        mDataConnectionTracker = this;
        update();
        mApnObserver = new ApnChangeObserver();
        p.getContext().getContentResolver().registerContentObserver(
                Telephony.Carriers.CONTENT_URI, true, mApnObserver);

        initApnContexts();

        for (ApnContext apnContext : mApnContexts.values()) {
            // Register the reconnect and restart actions.
            IntentFilter filter = new IntentFilter();
            filter.addAction(INTENT_RECONNECT_ALARM + '.' + apnContext.getApnType());
            filter.addAction(INTENT_RESTART_TRYSETUP_ALARM + '.' + apnContext.getApnType());
            mPhone.getContext().registerReceiver(mIntentReceiver, filter, null, mPhone);
        }

        ConnectivityManager cm = (ConnectivityManager)p.getContext().getSystemService(
                Context.CONNECTIVITY_SERVICE);

        mNetworkFilter = new NetworkCapabilities();
        mNetworkFilter.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_RCS);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mNetworkFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        mNetworkFactory = new TelephonyNetworkFactory(this.getLooper(), p.getContext(),
                "TelephonyNetworkFactory", mNetworkFilter);
        mNetworkFactory.setScoreFilter(50);
        mNetworkFactoryMessenger = new Messenger(mNetworkFactory);
        cm.registerNetworkFactory(mNetworkFactoryMessenger, "Telephony");

        // Add Emergency APN to APN setting list by default to support EPDN in sim absent cases
        initEmergencyApnSetting();
        addEmergencyApnSetting();

        mProvisionActionName = "com.android.internal.telephony.PROVISION" + p.getPhoneId();
    }

    protected void registerForAllEvents() {
        mPhone.mCi.registerForAvailable(this, DctConstants.EVENT_RADIO_AVAILABLE, null);
        mPhone.mCi.registerForOffOrNotAvailable(this,
               DctConstants.EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mPhone.mCi.registerForDataNetworkStateChanged(this,
               DctConstants.EVENT_DATA_STATE_CHANGED, null);
        mPhone.getCallTracker().registerForVoiceCallEnded (this,
               DctConstants.EVENT_VOICE_CALL_ENDED, null);
        mPhone.getCallTracker().registerForVoiceCallStarted (this,
               DctConstants.EVENT_VOICE_CALL_STARTED, null);
        mPhone.getServiceStateTracker().registerForDataConnectionAttached(this,
               DctConstants.EVENT_DATA_CONNECTION_ATTACHED, null);
        mPhone.getServiceStateTracker().registerForDataConnectionDetached(this,
               DctConstants.EVENT_DATA_CONNECTION_DETACHED, null);
        mPhone.getServiceStateTracker().registerForRoamingOn(this,
               DctConstants.EVENT_ROAMING_ON, null);
        mPhone.getServiceStateTracker().registerForRoamingOff(this,
               DctConstants.EVENT_ROAMING_OFF, null);
        mPhone.getServiceStateTracker().registerForPsRestrictedEnabled(this,
                DctConstants.EVENT_PS_RESTRICT_ENABLED, null);
        mPhone.getServiceStateTracker().registerForPsRestrictedDisabled(this,
                DctConstants.EVENT_PS_RESTRICT_DISABLED, null);
     //   SubscriptionManager.registerForDdsSwitch(this,
     //          DctConstants.EVENT_CLEAN_UP_ALL_CONNECTIONS, null);
        mPhone.getServiceStateTracker().registerForDataRegStateOrRatChanged(this,
                DctConstants.EVENT_DATA_RAT_CHANGED, null);
    }
    @Override
    public void dispose() {
        if (DBG) log("GsmDCT.dispose");

        if (mProvisionBroadcastReceiver != null) {
            mPhone.getContext().unregisterReceiver(mProvisionBroadcastReceiver);
            mProvisionBroadcastReceiver = null;
        }

        ConnectivityManager cm = (ConnectivityManager)mPhone.getContext().getSystemService(
                Context.CONNECTIVITY_SERVICE);
        cm.unregisterNetworkFactory(mNetworkFactoryMessenger);
        mNetworkFactoryMessenger = null;

        cleanUpAllConnections(true, null);

        super.dispose();

        mPhone.getContext().getContentResolver().unregisterContentObserver(mApnObserver);
        mApnContexts.clear();
        mPrioritySortedApnContexts.clear();

        destroyDataConnections();
    }
    protected void unregisterForAllEvents() {
         //Unregister for all events
        mPhone.mCi.unregisterForAvailable(this);
        mPhone.mCi.unregisterForOffOrNotAvailable(this);
        IccRecords r = mIccRecords.get();
        if (r != null) {
            r.unregisterForRecordsLoaded(this);
            mIccRecords.set(null);
        }
        mPhone.mCi.unregisterForDataNetworkStateChanged(this);
        mPhone.getCallTracker().unregisterForVoiceCallEnded(this);
        mPhone.getCallTracker().unregisterForVoiceCallStarted(this);
        mPhone.getServiceStateTracker().unregisterForDataConnectionAttached(this);
        mPhone.getServiceStateTracker().unregisterForDataConnectionDetached(this);
        mPhone.getServiceStateTracker().unregisterForRoamingOn(this);
        mPhone.getServiceStateTracker().unregisterForRoamingOff(this);
        mPhone.getServiceStateTracker().unregisterForPsRestrictedEnabled(this);
        mPhone.getServiceStateTracker().unregisterForPsRestrictedDisabled(this);
        //SubscriptionManager.unregisterForDdsSwitch(this);
    }

    private class TelephonyNetworkFactory extends NetworkFactory {
        public TelephonyNetworkFactory(Looper l, Context c, String TAG, NetworkCapabilities nc) {
            super(l, c, TAG, nc);
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            // figure out the apn type and enable it
            if (DBG) log("Cellular needs Network for " + networkRequest);
            ApnContext apnContext = apnContextForNetworkRequest(networkRequest);
            if (apnContext != null) apnContext.incRefCount();
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            if (DBG) log("Cellular releasing Network for " + networkRequest);
            ApnContext apnContext = apnContextForNetworkRequest(networkRequest);
            if (apnContext != null) apnContext.decRefCount();
        }
    }

    private ApnContext apnContextForNetworkRequest(NetworkRequest nr) {
        NetworkCapabilities nc = nr.networkCapabilities;
        // for now, ignore the bandwidth stuff
        if (nc.getTransportTypes().length > 0 &&
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == false) {
            return null;
        }

        // in the near term just do 1-1 matches.
        // TODO - actually try to match the set of capabilities
        int type = -1;
        String name = null;

        boolean error = false;
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DEFAULT;
            type = ConnectivityManager.TYPE_MOBILE;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_MMS;
            type = ConnectivityManager.TYPE_MOBILE_MMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_SUPL)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_SUPL;
            type = ConnectivityManager.TYPE_MOBILE_SUPL;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DUN;
            type = ConnectivityManager.TYPE_MOBILE_DUN;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOTA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_FOTA;
            type = ConnectivityManager.TYPE_MOBILE_FOTA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IMS;
            type = ConnectivityManager.TYPE_MOBILE_IMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CBS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_CBS;
            type = ConnectivityManager.TYPE_MOBILE_CBS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IA;
            type = ConnectivityManager.TYPE_MOBILE_IA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCS)) {
            if (name != null) error = true;
            name = null;
            loge("RCS APN type not yet supported");
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)) {
            if (name != null) error = true;
            name = null;
            loge("XCAP APN type not yet supported");
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) {
            if (name != null) error = true;
            name = null;
            loge("EIMS APN type not yet supported");
        }
        if (error) {
            loge("Multiple apn types specified in request - result is unspecified!");
        }
        if (type == -1 || name == null) {
            loge("Unsupported NetworkRequest in Telephony: " + nr);
            return null;
        }
        ApnContext apnContext = mApnContexts.get(name);
        if (apnContext == null) {
            loge("Request for unsupported mobile type: " + type);
        }
        return apnContext;
    }

    // Turn telephony radio on or off.
    private void setRadio(boolean on) {
        final ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
        try {
            phone.setRadio(on);
        } catch (Exception e) {
            // Ignore.
        }
    }

    // Class to handle Intent dispatched with user selects the "Sign-in to network"
    // notification.
    private class ProvisionNotificationBroadcastReceiver extends BroadcastReceiver {
        // Mobile provisioning URL.  Valid while provisioning notification is up.
        // Set prior to notification being posted as URL contains ICCID which
        // disappears when radio is off (which is the case when notification is up).
        private final String mProvisionUrl;

        public ProvisionNotificationBroadcastReceiver(String provisionUrl) {
            mProvisionUrl = provisionUrl;
        }

        private void setEnableFailFastMobileData(int enabled) {
            sendMessage(obtainMessage(DctConstants.CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA, enabled, 0));
        }

        private void enableMobileProvisioning() {
            final Message msg = obtainMessage(DctConstants.CMD_ENABLE_MOBILE_PROVISIONING);
            msg.setData(Bundle.forPair(DctConstants.PROVISIONING_URL_KEY, mProvisionUrl));
            sendMessage(msg);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            // This code is almost identical to the old
            // ConnectivityService.handleMobileProvisioningAction code.
            setRadio(true);
            setEnableFailFastMobileData(DctConstants.ENABLED);
            enableMobileProvisioning();
        }
    }

    @Override
    public boolean isApnTypeActive(String type) {
        ApnContext apnContext = mApnContexts.get(type);
        if (apnContext == null) return false;

        return (apnContext.getDcAc() != null);
    }

    @Override
    public boolean isDataPossible(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext == null) {
            return false;
        }
        boolean apnContextIsEnabled = apnContext.isEnabled();
        DctConstants.State apnContextState = apnContext.getState();
        boolean apnTypePossible = !(apnContextIsEnabled &&
                (apnContextState == DctConstants.State.FAILED));
        boolean isEmergencyApn = apnContext.getApnType().equals(PhoneConstants.APN_TYPE_EMERGENCY);
        // Set the emergency APN availability status as TRUE irrespective of conditions checked in
        // isDataAllowed() like IN_SERVICE, MOBILE DATA status etc.
        boolean dataAllowed = isEmergencyApn || isDataAllowed();
        boolean possible = dataAllowed && apnTypePossible;

        if (VDBG) {
            log(String.format("isDataPossible(%s): possible=%b isDataAllowed=%b " +
                    "apnTypePossible=%b apnContextisEnabled=%b apnContextState()=%s",
                    apnType, possible, dataAllowed, apnTypePossible,
                    apnContextIsEnabled, apnContextState));
        }
        return possible;
    }

    @Override
    protected void finalize() {
        if(DBG) log("finalize");
    }

    protected void supplyMessenger() {
       // Supply the data connection tracker messenger only if
       // this is corresponding to the current DDS.
       if (!isActiveDataSubscription()) {
           return;
       }

        ConnectivityManager cm = (ConnectivityManager)mPhone.getContext().getSystemService(
                Context.CONNECTIVITY_SERVICE);
        cm.supplyMessenger(ConnectivityManager.TYPE_MOBILE, new Messenger(this));
        cm.supplyMessenger(ConnectivityManager.TYPE_MOBILE_MMS, new Messenger(this));
        cm.supplyMessenger(ConnectivityManager.TYPE_MOBILE_SUPL, new Messenger(this));
        cm.supplyMessenger(ConnectivityManager.TYPE_MOBILE_DUN, new Messenger(this));
        cm.supplyMessenger(ConnectivityManager.TYPE_MOBILE_HIPRI, new Messenger(this));
        cm.supplyMessenger(ConnectivityManager.TYPE_MOBILE_FOTA, new Messenger(this));
        cm.supplyMessenger(ConnectivityManager.TYPE_MOBILE_IMS, new Messenger(this));
        cm.supplyMessenger(ConnectivityManager.TYPE_MOBILE_CBS, new Messenger(this));
        cm.supplyMessenger(ConnectivityManager.TYPE_MOBILE_EMERGENCY, new Messenger(this));
    }

    private ApnContext addApnContext(String type, NetworkConfig networkConfig) {
        ApnContext apnContext = new ApnContext(mPhone.getContext(), type, LOG_TAG, networkConfig,
                this);
        mApnContexts.put(type, apnContext);
        mPrioritySortedApnContexts.add(apnContext);
        return apnContext;
    }

    protected void initApnContexts() {
        log("initApnContexts: E");
        // Load device network attributes from resources
        String[] networkConfigStrings = mPhone.getContext().getResources().getStringArray(
                com.android.internal.R.array.networkAttributes);
        for (String networkConfigString : networkConfigStrings) {
            NetworkConfig networkConfig = new NetworkConfig(networkConfigString);
            ApnContext apnContext = null;

            switch (networkConfig.type) {
            case ConnectivityManager.TYPE_MOBILE:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_DEFAULT, networkConfig);
                break;
            case ConnectivityManager.TYPE_MOBILE_MMS:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_MMS, networkConfig);
                break;
            case ConnectivityManager.TYPE_MOBILE_SUPL:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_SUPL, networkConfig);
                break;
            case ConnectivityManager.TYPE_MOBILE_DUN:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_DUN, networkConfig);
                break;
            case ConnectivityManager.TYPE_MOBILE_HIPRI:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_HIPRI, networkConfig);
                break;
            case ConnectivityManager.TYPE_MOBILE_FOTA:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_FOTA, networkConfig);
                break;
            case ConnectivityManager.TYPE_MOBILE_IMS:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_IMS, networkConfig);
                break;
            case ConnectivityManager.TYPE_MOBILE_CBS:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_CBS, networkConfig);
                break;
            case ConnectivityManager.TYPE_MOBILE_IA:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_IA, networkConfig);
                break;
            case ConnectivityManager.TYPE_MOBILE_EMERGENCY:
                apnContext = addApnContext(PhoneConstants.APN_TYPE_EMERGENCY, networkConfig);
                break;
            default:
                log("initApnContexts: skipping unknown type=" + networkConfig.type);
                continue;
            }
            log("initApnContexts: apnContext=" + apnContext);
        }
        log("initApnContexts: X mApnContexts=" + mApnContexts);
    }

    @Override
    public LinkProperties getLinkProperties(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            DcAsyncChannel dcac = apnContext.getDcAc();
            if (dcac != null) {
                if (DBG) log("return link properites for " + apnType);
                return dcac.getLinkPropertiesSync();
            }
        }
        if (DBG) log("return new LinkProperties");
        return new LinkProperties();
    }

    @Override
    public NetworkCapabilities getNetworkCapabilities(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext!=null) {
            DcAsyncChannel dataConnectionAc = apnContext.getDcAc();
            if (dataConnectionAc != null) {
                if (DBG) {
                    log("get active pdp is not null, return NetworkCapabilities for " + apnType);
                }
                return dataConnectionAc.getNetworkCapabilitiesSync();
            }
        }
        if (DBG) log("return new NetworkCapabilities");
        return new NetworkCapabilities();
    }

    @Override
    // Return all active apn types
    public String[] getActiveApnTypes() {
        if (DBG) log("get all active apn types");
        ArrayList<String> result = new ArrayList<String>();

        for (ApnContext apnContext : mApnContexts.values()) {
            if (mAttached.get() && apnContext.isReady()) {
                result.add(apnContext.getApnType());
            }
        }

        return result.toArray(new String[0]);
    }

    @Override
    // Return active apn of specific apn type
    public String getActiveApnString(String apnType) {
        if (VDBG) log( "get active apn string for type:" + apnType);
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            ApnSetting apnSetting = apnContext.getApnSetting();
            if (apnSetting != null) {
                return apnSetting.apn;
            }
        }
        return null;
    }

    @Override
    public boolean isApnTypeEnabled(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext == null) {
            return false;
        }
        return apnContext.isEnabled();
    }

    @Override
    protected void setState(DctConstants.State s) {
        if (DBG) log("setState should not be used in GSM" + s);
    }

    // Return state of specific apn type
    @Override
    public DctConstants.State getState(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            return apnContext.getState();
        }
        return DctConstants.State.FAILED;
    }

    // Return if apn type is a provisioning apn.
    @Override
    protected boolean isProvisioningApn(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            return apnContext.isProvisioningApn();
        }
        return false;
    }

    // Return state of overall
    @Override
    public DctConstants.State getOverallState() {
        boolean isConnecting = false;
        boolean isFailed = true; // All enabled Apns should be FAILED.
        boolean isAnyEnabled = false;

        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.isEnabled()) {
                isAnyEnabled = true;
                switch (apnContext.getState()) {
                case CONNECTED:
                case DISCONNECTING:
                    if (DBG) log("overall state is CONNECTED");
                    return DctConstants.State.CONNECTED;
                case RETRYING:
                case CONNECTING:
                    isConnecting = true;
                    isFailed = false;
                    break;
                case IDLE:
                case SCANNING:
                    isFailed = false;
                    break;
                default:
                    isAnyEnabled = true;
                    break;
                }
            }
        }

        if (!isAnyEnabled) { // Nothing enabled. return IDLE.
            if (DBG) log( "overall state is IDLE");
            return DctConstants.State.IDLE;
        }

        if (isConnecting) {
            if (DBG) log( "overall state is CONNECTING");
            return DctConstants.State.CONNECTING;
        } else if (!isFailed) {
            if (DBG) log( "overall state is IDLE");
            return DctConstants.State.IDLE;
        } else {
            if (DBG) log( "overall state is FAILED");
            return DctConstants.State.FAILED;
        }
    }

    @Override
    protected boolean isApnTypeAvailable(String type) {
        if (type.equals(PhoneConstants.APN_TYPE_DUN) && fetchDunApn() != null) {
            return true;
        }

        if (mAllApnSettings != null) {
            for (ApnSetting apn : mAllApnSettings) {
                if (apn.canHandleType(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Report on whether data connectivity is enabled for any APN.
     * @return {@code false} if data connectivity has been explicitly disabled,
     * {@code true} otherwise.
     */
    @Override
    public boolean getAnyDataEnabled() {
        synchronized (mDataEnabledLock) {
            if (!(mInternalDataEnabled && mUserDataEnabled && sPolicyDataEnabled)) return false;
            for (ApnContext apnContext : mApnContexts.values()) {
                // Make sure we don't have a context that is going down
                // and is explicitly disabled.
                if (isDataAllowed(apnContext)) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean getAnyDataEnabled(boolean checkUserDataEnabled) {
        synchronized (mDataEnabledLock) {
            if (!(mInternalDataEnabled && (!checkUserDataEnabled || mUserDataEnabled)
                        && (!checkUserDataEnabled || sPolicyDataEnabled)))
                return false;

            for (ApnContext apnContext : mApnContexts.values()) {
                // Make sure we dont have a context that going down
                // and is explicitly disabled.
                if (isDataAllowed(apnContext)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean isDataAllowed(ApnContext apnContext) {
        return apnContext.isReady() && isDataAllowed();
    }

    //****** Called from ServiceStateTracker
    /**
     * Invoked when ServiceStateTracker observes a transition from GPRS
     * attach to detach.
     */
    protected void onDataConnectionDetached() {
        /*
         * We presently believe it is unnecessary to tear down the PDP context
         * when GPRS detaches, but we should stop the network polling.
         */
        if (DBG) log ("onDataConnectionDetached: stop polling and notify detached");
        stopNetStatPoll();
        stopDataStallAlarm();
        notifyDataConnection(Phone.REASON_DATA_DETACHED);
        mAttached.set(false);
    }

    private void onDataConnectionAttached() {
        if (DBG) log("onDataConnectionAttached");
        mAttached.set(true);
        if (getOverallState() == DctConstants.State.CONNECTED) {
            if (DBG) log("onDataConnectionAttached: start polling notify attached");
            startNetStatPoll();
            startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
            notifyDataConnection(Phone.REASON_DATA_ATTACHED);
        } else {
            // update APN availability so that APN can be enabled.
            notifyOffApnsOfAvailability(Phone.REASON_DATA_ATTACHED);
        }
        if (mAutoAttachOnCreationConfig) {
            mAutoAttachOnCreation = true;
        }
        setupDataOnConnectableApns(Phone.REASON_DATA_ATTACHED);
    }

    @Override
    protected boolean isDataAllowed() {
        final boolean internalDataEnabled;
        synchronized (mDataEnabledLock) {
            internalDataEnabled = mInternalDataEnabled;
        }

        boolean attachedState = mAttached.get();
        boolean desiredPowerState = mPhone.getServiceStateTracker().getDesiredPowerState();
        IccRecords r = mIccRecords.get();
        boolean recordsLoaded = (r != null) ? r.getRecordsLoaded() : false;

        //FIXME always attach
        boolean psRestricted = mIsPsRestricted;
        int phoneNum = TelephonyManager.getDefault().getPhoneCount();
        if (phoneNum > 1) {
            attachedState = true;
            psRestricted = false;
        }

        boolean allowed =
                    (attachedState || mAutoAttachOnCreation) &&
                    recordsLoaded &&
                    (mPhone.getState() == PhoneConstants.State.IDLE ||
                     mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) &&
                    internalDataEnabled &&
                    (!mPhone.getServiceState().getRoaming() || getDataOnRoamingEnabled()) &&
                    //!mIsPsRestricted &&
                    !psRestricted &&
                    desiredPowerState;
        if (!allowed && DBG) {
            String reason = "";
            if (!(attachedState || mAutoAttachOnCreation)) {
                reason += " - Attached= " + attachedState;
            }
            if (!recordsLoaded) reason += " - SIM not loaded";
            if (mPhone.getState() != PhoneConstants.State.IDLE &&
                    !mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
                reason += " - PhoneState= " + mPhone.getState();
                reason += " - Concurrent voice and data not allowed";
            }
            if (!internalDataEnabled) reason += " - mInternalDataEnabled= false";
            if (mPhone.getServiceState().getRoaming() && !getDataOnRoamingEnabled()) {
                reason += " - Roaming and data roaming not enabled";
            }
            if (mIsPsRestricted) reason += " - mIsPsRestricted= true";
            if (!desiredPowerState) reason += " - desiredPowerState= false";
            if (DBG) log("isDataAllowed: not allowed due to" + reason);
        }
        return allowed;
    }

    private void setupDataOnConnectableApns(String reason) {
        if (DBG) log("setupDataOnConnectableApns: " + reason);

        for (ApnContext apnContext : mPrioritySortedApnContexts) {
            if (DBG) log("setupDataOnConnectableApns: apnContext " + apnContext);
            if (apnContext.getState() == DctConstants.State.FAILED) {
                apnContext.setState(DctConstants.State.IDLE);
            }
            if (apnContext.isConnectable()) {
                log("setupDataOnConnectableApns: isConnectable() call trySetupData");
                apnContext.setReason(reason);
                trySetupData(apnContext);
            }
        }
    }

    private boolean trySetupData(ApnContext apnContext) {
        if (DBG) {
            log("trySetupData for type:" + apnContext.getApnType() +
                    " due to " + apnContext.getReason() + " apnContext=" + apnContext);
            log("trySetupData with mIsPsRestricted=" + mIsPsRestricted);
        }

        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            apnContext.setState(DctConstants.State.CONNECTED);
            mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());

            log("trySetupData: X We're on the simulator; assuming connected retValue=true");
            return true;
        }

        // Allow SETUP_DATA request for E-APN to be completed during emergency call
        // and MOBILE DATA On/Off cases as well.
        boolean isEmergencyApn = apnContext.getApnType().equals(PhoneConstants.APN_TYPE_EMERGENCY);
        boolean desiredPowerState = mPhone.getServiceStateTracker().getDesiredPowerState();
        boolean checkUserDataEnabled =
                    !(apnContext.getApnType().equals(PhoneConstants.APN_TYPE_IMS));

        if (apnContext.isConnectable() && (isEmergencyApn ||
                (isDataAllowed(apnContext) &&
                getAnyDataEnabled(checkUserDataEnabled) && !isEmergency()))) {
            if (apnContext.getState() == DctConstants.State.FAILED) {
                if (DBG) log("trySetupData: make a FAILED ApnContext IDLE so its reusable");
                apnContext.setState(DctConstants.State.IDLE);
            }
            int radioTech = mPhone.getServiceState().getRilDataRadioTechnology();
            if (apnContext.getState() == DctConstants.State.IDLE) {

                ArrayList<ApnSetting> waitingApns = buildWaitingApns(apnContext.getApnType(),
                        radioTech);
                if (waitingApns.isEmpty()) {
                    notifyNoData(DcFailCause.MISSING_UNKNOWN_APN, apnContext);
                    notifyOffApnsOfAvailability(apnContext.getReason());
                    if (DBG) log("trySetupData: X No APN found retValue=false");
                    return false;
                } else {
                    apnContext.setWaitingApns(waitingApns);
                    if (DBG) {
                        log ("trySetupData: Create from mAllApnSettings : "
                                    + apnListToString(mAllApnSettings));
                    }
                }
            }

            if (DBG) {
                log("trySetupData: call setupData, waitingApns : "
                        + apnListToString(apnContext.getWaitingApns()));
            }
            boolean retValue = setupData(apnContext, radioTech);
            notifyOffApnsOfAvailability(apnContext.getReason());

            if (DBG) log("trySetupData: X retValue=" + retValue);
            return retValue;
        } else {
            if (!apnContext.getApnType().equals(PhoneConstants.APN_TYPE_DEFAULT)
                    && apnContext.isConnectable()) {
                mPhone.notifyDataConnectionFailed(apnContext.getReason(), apnContext.getApnType());
            }
            notifyOffApnsOfAvailability(apnContext.getReason());
            if (DBG) log ("trySetupData: X apnContext not 'ready' retValue=false");
            return false;
        }
    }

    @Override
    // Disabled apn's still need avail/unavail notificiations - send them out
    protected void notifyOffApnsOfAvailability(String reason) {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (!mAttached.get() || !apnContext.isReady()) {
                if (VDBG) log("notifyOffApnOfAvailability type:" + apnContext.getApnType());
                mPhone.notifyDataConnection(reason != null ? reason : apnContext.getReason(),
                                            apnContext.getApnType(),
                                            PhoneConstants.DataState.DISCONNECTED);
            } else {
                if (VDBG) {
                    log("notifyOffApnsOfAvailability skipped apn due to attached && isReady " +
                            apnContext.toString());
                }
            }
        }
    }

    /**
     * If tearDown is true, this only tears down a CONNECTED session. Presently,
     * there is no mechanism for abandoning an CONNECTING session,
     * but would likely involve cancelling pending async requests or
     * setting a flag or new state to ignore them when they came in
     * @param tearDown true if the underlying DataConnection should be
     * disconnected.
     * @param reason reason for the clean up.
     * @return boolean - true if we did cleanup any connections, false if they
     *                   were already all disconnected.
     */
    protected boolean cleanUpAllConnections(boolean tearDown, String reason) {
        if (DBG) log("cleanUpAllConnections: tearDown=" + tearDown + " reason=" + reason);
        boolean didDisconnect = false;
        boolean specificdisable = false;

        if (!TextUtils.isEmpty(reason)) {
            specificdisable = reason.equals(Phone.REASON_DATA_SPECIFIC_DISABLED);
        }

        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.isDisconnected() == false) didDisconnect = true;
            if (specificdisable) {
                if (!apnContext.getApnType().equals(PhoneConstants.APN_TYPE_IMS)) {
                    if (DBG) log("ApnConextType: " + apnContext.getApnType());
                    apnContext.setReason(reason);
                    cleanUpConnection(tearDown, apnContext);
                }
            } else {
                // TODO - only do cleanup if not disconnected
                apnContext.setReason(reason);
                cleanUpConnection(tearDown, apnContext);
            }
        }

        stopNetStatPoll();
        stopDataStallAlarm();

        // TODO: Do we need mRequestedApnType?
        mRequestedApnType = PhoneConstants.APN_TYPE_DEFAULT;

        log("cleanUpConnection: mDisconnectPendingCount = " + mDisconnectPendingCount);
        if (tearDown && mDisconnectPendingCount == 0) {
            notifyDataDisconnectComplete();
            notifyAllDataDisconnected();
        }

        return didDisconnect;
    }

    /**
     * Cleanup all connections.
     *
     * TODO: Cleanup only a specified connection passed as a parameter.
     *       Also, make sure when you clean up a conn, if it is last apply
     *       logic as though it is cleanupAllConnections
     *
     * @param cause for the clean up.
     */

    @Override
    protected void onCleanUpAllConnections(String cause) {
        cleanUpAllConnections(true, cause);
    }

    protected void cleanUpConnection(boolean tearDown, ApnContext apnContext) {

        if (apnContext == null) {
            if (DBG) log("cleanUpConnection: apn context is null");
            return;
        }

        DcAsyncChannel dcac = apnContext.getDcAc();
        if (DBG) {
            log("cleanUpConnection: E tearDown=" + tearDown + " reason=" + apnContext.getReason() +
                    " apnContext=" + apnContext);
        }
        if (tearDown) {
            if (apnContext.isDisconnected()) {
                // The request is tearDown and but ApnContext is not connected.
                // If apnContext is not enabled anymore, break the linkage to the DCAC/DC.
                apnContext.setState(DctConstants.State.IDLE);
                if (!apnContext.isReady()) {
                    if (dcac != null) {
                        dcac.tearDown(apnContext, "", null);
                    }
                    apnContext.setDataConnectionAc(null);
                }
            } else {
                // Connection is still there. Try to clean up.
                if (dcac != null) {
                    if (apnContext.getState() != DctConstants.State.DISCONNECTING) {
                        boolean disconnectAll = false;
                        if (PhoneConstants.APN_TYPE_DUN.equals(apnContext.getApnType())) {
                            // CAF_MSIM is this below condition required.
                            // if (PhoneConstants.APN_TYPE_DUN.equals(PhoneConstants.APN_TYPE_DEFAULT)) {
                            if (teardownForDun()) {
                                if (DBG) log("tearing down dedicated DUN connection");
                                // we need to tear it down - we brought it up just for dun and
                                // other people are camped on it and now dun is done.  We need
                                // to stop using it and let the normal apn list get used to find
                                // connections for the remaining desired connections
                                disconnectAll = true;
                            }
                        }
                        if (DBG) {
                            log("cleanUpConnection: tearing down" + (disconnectAll ? " all" :""));
                        }
                        Message msg = obtainMessage(DctConstants.EVENT_DISCONNECT_DONE, apnContext);
                        if (disconnectAll) {
                            apnContext.getDcAc().tearDownAll(apnContext.getReason(), msg);
                        } else {
                            apnContext.getDcAc()
                                .tearDown(apnContext, apnContext.getReason(), msg);
                        }
                        apnContext.setState(DctConstants.State.DISCONNECTING);
                        mDisconnectPendingCount++;
                    }
                } else {
                    // apn is connected but no reference to dcac.
                    // Should not be happen, but reset the state in case.
                    apnContext.setState(DctConstants.State.IDLE);
                    mPhone.notifyDataConnection(apnContext.getReason(),
                                                apnContext.getApnType());
                }
            }
        } else {
            // force clean up the data connection.
            if (dcac != null) dcac.reqReset();
            apnContext.setState(DctConstants.State.IDLE);
            mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
            apnContext.setDataConnectionAc(null);
        }

        // Make sure reconnection alarm is cleaned up if there is no ApnContext
        // associated to the connection.
        if (dcac != null) {
            cancelReconnectAlarm(apnContext);
        }
        if (DBG) {
            log("cleanUpConnection: X tearDown=" + tearDown + " reason=" + apnContext.getReason() +
                    " apnContext=" + apnContext + " dcac=" + apnContext.getDcAc());
        }
    }

    /**
     * Determine if DUN connection is special and we need to teardown on start/stop
     */
    private boolean teardownForDun() {
        // CDMA always needs to do this the profile id is correct
        final int rilRat = mPhone.getServiceState().getRilDataRadioTechnology();
        if (ServiceState.isCdma(rilRat)) return true;

        return (fetchDunApn() != null);
    }

    /**
     * Cancels the alarm associated with apnContext.
     *
     * @param apnContext on which the alarm should be stopped.
     */
    private void cancelReconnectAlarm(ApnContext apnContext) {
        if (apnContext == null) return;

        PendingIntent intent = apnContext.getReconnectIntent();

        if (intent != null) {
                AlarmManager am =
                    (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
                am.cancel(intent);
                apnContext.setReconnectIntent(null);
        }
    }

    /**
     * @param types comma delimited list of APN types
     * @return array of APN types
     */
    private String[] parseTypes(String types) {
        String[] result;
        // If unset, set to DEFAULT.
        if (types == null || types.equals("")) {
            result = new String[1];
            result[0] = PhoneConstants.APN_TYPE_ALL;
        } else {
            result = types.split(",");
        }
        return result;
    }

   private boolean imsiMatches(String imsiDB, String imsiSIM) {
        // Note: imsiDB value has digit number or 'x' character for seperating USIM information
        // for MVNO operator. And then digit number is matched at same order and 'x' character
        // could replace by any digit number.
        // ex) if imsiDB inserted '310260x10xxxxxx' for GG Operator,
        //     that means first 6 digits, 8th and 9th digit
        //     should be set in USIM for GG Operator.
        int len = imsiDB.length();
        int idxCompare = 0;

        if (len <= 0) return false;
        if (len > imsiSIM.length()) return false;

        for (int idx=0; idx<len; idx++) {
            char c = imsiDB.charAt(idx);
            if ((c == 'x') || (c == 'X') || (c == imsiSIM.charAt(idx))) {
                continue;
            } else {
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean mvnoMatches(IccRecords r, String mvnoType, String mvnoMatchData) {
        if (mvnoType.equalsIgnoreCase("spn")) {
            if ((r.getServiceProviderName() != null) &&
                    r.getServiceProviderName().equalsIgnoreCase(mvnoMatchData)) {
                return true;
            }
        } else if (mvnoType.equalsIgnoreCase("imsi")) {
            String imsiSIM = r.getIMSI();
            if ((imsiSIM != null) && imsiMatches(mvnoMatchData, imsiSIM)) {
                return true;
            }
        } else if (mvnoType.equalsIgnoreCase("gid")) {
            String gid1 = r.getGid1();
            int mvno_match_data_length = mvnoMatchData.length();
            if ((gid1 != null) && (gid1.length() >= mvno_match_data_length) &&
                    gid1.substring(0, mvno_match_data_length).equalsIgnoreCase(mvnoMatchData)) {
                return true;
            }
        }
        return false;
    }

    private ApnSetting makeApnSetting(Cursor cursor) {
        String[] types = parseTypes(
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
        ApnSetting apn = new ApnSetting(
                cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NUMERIC)),
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)),
                NetworkUtils.trimV4AddrZeros(
                        cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY))),
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT)),
                NetworkUtils.trimV4AddrZeros(
                        cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC))),
                NetworkUtils.trimV4AddrZeros(
                        cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY))),
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT)),
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)),
                types,
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROTOCOL)),
                cursor.getString(cursor.getColumnIndexOrThrow(
                        Telephony.Carriers.ROAMING_PROTOCOL)),
                cursor.getInt(cursor.getColumnIndexOrThrow(
                        Telephony.Carriers.CARRIER_ENABLED)) == 1,
                cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.BEARER)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROFILE_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(
                        Telephony.Carriers.MODEM_COGNITIVE)) == 1,
                cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.MAX_CONNS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(
                        Telephony.Carriers.WAIT_TIME)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.MAX_CONNS_TIME)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.MTU)),
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MVNO_TYPE)),
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MVNO_MATCH_DATA)));
        return apn;
    }

    private ArrayList<ApnSetting> createApnList(Cursor cursor) {
        ArrayList<ApnSetting> mnoApns = new ArrayList<ApnSetting>();
        ArrayList<ApnSetting> mvnoApns = new ArrayList<ApnSetting>();
        IccRecords r = mIccRecords.get();

        if (cursor.moveToFirst()) {
            do {
                ApnSetting apn = makeApnSetting(cursor);
                if (apn == null) {
                    continue;
                }

                if (apn.hasMvnoParams()) {
                    if (r != null && mvnoMatches(r, apn.mvnoType, apn.mvnoMatchData)) {
                        mvnoApns.add(apn);
                    }
                } else {
                    mnoApns.add(apn);
                }
            } while (cursor.moveToNext());
        }

        ArrayList<ApnSetting> result = mvnoApns.isEmpty() ? mnoApns : mvnoApns;
        if (DBG) log("createApnList: X result=" + result);
        return result;
    }

    private boolean dataConnectionNotInUse(DcAsyncChannel dcac) {
        if (DBG) log("dataConnectionNotInUse: check if dcac is inuse dcac=" + dcac);
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.getDcAc() == dcac) {
                if (DBG) log("dataConnectionNotInUse: in use by apnContext=" + apnContext);
                return false;
            }
        }
        // TODO: Fix retry handling so free DataConnections have empty apnlists.
        // Probably move retry handling into DataConnections and reduce complexity
        // of DCT.
        if (DBG) log("dataConnectionNotInUse: tearDownAll");
        dcac.tearDownAll("No connection", null);
        if (DBG) log("dataConnectionNotInUse: not in use return true");
        return true;
    }

    private DcAsyncChannel findFreeDataConnection() {
        for (DcAsyncChannel dcac : mDataConnectionAcHashMap.values()) {
            if (dcac.isInactiveSync() && dataConnectionNotInUse(dcac)) {
                if (DBG) {
                    log("findFreeDataConnection: found free DataConnection=" +
                        " dcac=" + dcac);
                }
                return dcac;
            }
        }
        log("findFreeDataConnection: NO free DataConnection");
        return null;
    }

    private boolean setupData(ApnContext apnContext, int radioTech) {
        if (DBG) log("setupData: apnContext=" + apnContext);
        ApnSetting apnSetting;
        DcAsyncChannel dcac = null;

        apnSetting = apnContext.getNextWaitingApn();
        if (apnSetting == null) {
            if (DBG) log("setupData: return for no apn found!");
            return false;
        }

        int profileId = apnSetting.profileId;
        if (profileId == 0) {
            profileId = getApnProfileID(apnContext.getApnType());
        }

        // On CDMA, if we're explicitly asking for DUN, we need have
        // a dun-profiled connection so we can't share an existing one
        // On GSM/LTE we can share existing apn connections provided they support
        // this type.
        if (apnContext.getApnType() != PhoneConstants.APN_TYPE_DUN ||
                teardownForDun() == false) {
            dcac = checkForCompatibleConnectedApnContext(apnContext);
            if (dcac != null) {
                // Get the dcacApnSetting for the connection we want to share.
                ApnSetting dcacApnSetting = dcac.getApnSettingSync();
                if (dcacApnSetting != null) {
                    // Setting is good, so use it.
                    apnSetting = dcacApnSetting;
                }
            }
        }
        if (dcac == null) {
            if (isOnlySingleDcAllowed(radioTech)) {
                if (isHigherPriorityApnContextActive(apnContext)) {
                    if (DBG) {
                        log("setupData: Higher priority ApnContext active.  Ignoring call");
                    }
                    return false;
                }

                // Only lower priority calls left.  Disconnect them all in this single PDP case
                // so that we can bring up the requested higher priority call (once we receive
                // repsonse for deactivate request for the calls we are about to disconnect
                if (cleanUpAllConnections(true, Phone.REASON_SINGLE_PDN_ARBITRATION)) {
                    // If any call actually requested to be disconnected, means we can't
                    // bring up this connection yet as we need to wait for those data calls
                    // to be disconnected.
                    if (DBG) log("setupData: Some calls are disconnecting first.  Wait and retry");
                    return false;
                }

                // No other calls are active, so proceed
                if (DBG) log("setupData: Single pdp. Continue setting up data call.");
            }

            dcac = findFreeDataConnection();

            if (dcac == null) {
                dcac = createDataConnection();
            }

            if (dcac == null) {
                if (DBG) log("setupData: No free DataConnection and couldn't create one, WEIRD");
                return false;
            }
        }
        if (DBG) log("setupData: dcac=" + dcac + " apnSetting=" + apnSetting);

        apnContext.setDataConnectionAc(dcac);
        apnContext.setApnSetting(apnSetting);
        apnContext.setState(DctConstants.State.CONNECTING);
        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());

        Message msg = obtainMessage();
        msg.what = DctConstants.EVENT_DATA_SETUP_COMPLETE;
        msg.obj = apnContext;
        dcac.bringUp(apnContext, getInitialMaxRetry(), profileId, radioTech, mAutoAttachOnCreation,
                msg);

        if (DBG) log("setupData: initing!");
        return true;
    }

    /**
     * Handles changes to the APN database.
     */
    private void onApnChanged() {
        DctConstants.State overallState = getOverallState();
        boolean isDisconnected = (overallState == DctConstants.State.IDLE ||
                overallState == DctConstants.State.FAILED);

        if (mPhone instanceof GSMPhone) {
            // The "current" may no longer be valid.  MMS depends on this to send properly. TBD
            ((GSMPhone)mPhone).updateCurrentCarrierInProvider();
        }

        // TODO: It'd be nice to only do this if the changed entrie(s)
        // match the current operator.
        if (DBG) log("onApnChanged: createAllApnList and cleanUpAllConnections");
        createAllApnList();
        setInitialAttachApn();
        cleanUpAllConnections(!isDisconnected, Phone.REASON_APN_CHANGED);
        setupDataOnConnectableApns(Phone.REASON_APN_CHANGED);
    }

    /**
     * @param cid Connection id provided from RIL.
     * @return DataConnectionAc associated with specified cid.
     */
    private DcAsyncChannel findDataConnectionAcByCid(int cid) {
        for (DcAsyncChannel dcac : mDataConnectionAcHashMap.values()) {
            if (dcac.getCidSync() == cid) {
                return dcac;
            }
        }
        return null;
    }

    // TODO: For multiple Active APNs not exactly sure how to do this.
    @Override
    protected void gotoIdleAndNotifyDataConnection(String reason) {
        if (DBG) log("gotoIdleAndNotifyDataConnection: reason=" + reason);
        notifyDataConnection(reason);
        mActiveApn = null;
    }

    /**
     * "Active" here means ApnContext isEnabled() and not in FAILED state
     * @param apnContext to compare with
     * @return true if higher priority active apn found
     */
    private boolean isHigherPriorityApnContextActive(ApnContext apnContext) {
        for (ApnContext otherContext : mPrioritySortedApnContexts) {
            if (apnContext.getApnType().equalsIgnoreCase(otherContext.getApnType())) return false;
            if (otherContext.isEnabled() && otherContext.getState() != DctConstants.State.FAILED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports if we support multiple connections or not.
     * This is a combination of factors, based on carrier and RAT.
     * @param rilRadioTech the RIL Radio Tech currently in use
     * @return true if only single DataConnection is allowed
     */
    private boolean isOnlySingleDcAllowed(int rilRadioTech) {
        int[] singleDcRats = mPhone.getContext().getResources().getIntArray(
                com.android.internal.R.array.config_onlySingleDcAllowed);
        boolean onlySingleDcAllowed = false;
        if (Build.IS_DEBUGGABLE &&
                SystemProperties.getBoolean("persist.telephony.test.singleDc", false)) {
            onlySingleDcAllowed = true;
        }
        if (singleDcRats != null) {
            for (int i=0; i < singleDcRats.length && onlySingleDcAllowed == false; i++) {
                if (rilRadioTech == singleDcRats[i]) onlySingleDcAllowed = true;
            }
        }

        if (DBG) log("isOnlySingleDcAllowed(" + rilRadioTech + "): " + onlySingleDcAllowed);
        return onlySingleDcAllowed;
    }

    @Override
    protected void restartRadio() {
        if (DBG) log("restartRadio: ************TURN OFF RADIO**************");
        cleanUpAllConnections(true, Phone.REASON_RADIO_TURNED_OFF);
        mPhone.getServiceStateTracker().powerOffRadioSafely(this);
        /* Note: no need to call setRadioPower(true).  Assuming the desired
         * radio power state is still ON (as tracked by ServiceStateTracker),
         * ServiceStateTracker will call setRadioPower when it receives the
         * RADIO_STATE_CHANGED notification for the power off.  And if the
         * desired power state has changed in the interim, we don't want to
         * override it with an unconditional power on.
         */

        int reset = Integer.parseInt(SystemProperties.get("net.ppp.reset-by-timeout", "0"));
        SystemProperties.set("net.ppp.reset-by-timeout", String.valueOf(reset+1));
    }

    /**
     * Return true if data connection need to be setup after disconnected due to
     * reason.
     *
     * @param reason the reason why data is disconnected
     * @return true if try setup data connection is need for this reason
     */
    private boolean retryAfterDisconnected(ApnContext apnContext) {
        boolean retry = true;
        String reason = apnContext.getReason();

        if ( Phone.REASON_RADIO_TURNED_OFF.equals(reason) ||
                (isOnlySingleDcAllowed(mPhone.getServiceState().getRilDataRadioTechnology())
                 && isHigherPriorityApnContextActive(apnContext))) {
            retry = false;
        }
        return retry;
    }

    private void startAlarmForReconnect(int delay, ApnContext apnContext) {
        String apnType = apnContext.getApnType();

        Intent intent = new Intent(INTENT_RECONNECT_ALARM + "." + apnType);
        intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, apnContext.getReason());
        intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_TYPE, apnType);

        // Get current sub id.
        long subId = SubscriptionManager.getDefaultDataSubId();
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);

        if (DBG) {
            log("startAlarmForReconnect: delay=" + delay + " action=" + intent.getAction()
                    + " apn=" + apnContext);
        }

        PendingIntent alarmIntent = PendingIntent.getBroadcast (mPhone.getContext(), 0,
                                        intent, PendingIntent.FLAG_UPDATE_CURRENT);
        apnContext.setReconnectIntent(alarmIntent);
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay, alarmIntent);
    }

    private void startAlarmForRestartTrySetup(int delay, ApnContext apnContext) {
        String apnType = apnContext.getApnType();
        Intent intent = new Intent(INTENT_RESTART_TRYSETUP_ALARM + "." + apnType);
        intent.putExtra(INTENT_RESTART_TRYSETUP_ALARM_EXTRA_TYPE, apnType);

        if (DBG) {
            log("startAlarmForRestartTrySetup: delay=" + delay + " action=" + intent.getAction()
                    + " apn=" + apnContext);
        }
        PendingIntent alarmIntent = PendingIntent.getBroadcast (mPhone.getContext(), 0,
                                        intent, PendingIntent.FLAG_UPDATE_CURRENT);
        apnContext.setReconnectIntent(alarmIntent);
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay, alarmIntent);
    }

    private void notifyNoData(DcFailCause lastFailCauseCode,
                              ApnContext apnContext) {
        if (DBG) log( "notifyNoData: type=" + apnContext.getApnType());
        if (lastFailCauseCode.isPermanentFail()
            && (!apnContext.getApnType().equals(PhoneConstants.APN_TYPE_DEFAULT))) {
            mPhone.notifyDataConnectionFailed(apnContext.getReason(), apnContext.getApnType());
        }
    }

    private void onRecordsLoaded() {
        if (DBG) log("onRecordsLoaded: createAllApnList");
        mAutoAttachOnCreationConfig = mPhone.getContext().getResources()
                .getBoolean(com.android.internal.R.bool.config_auto_attach_data_on_creation);
        createAllApnList();
        setInitialAttachApn();
        if (mPhone.mCi.getRadioState().isOn()) {
            if (DBG) log("onRecordsLoaded: notifying data availability");
            notifyOffApnsOfAvailability(Phone.REASON_SIM_LOADED);
        }
        setupDataOnConnectableApns(Phone.REASON_SIM_LOADED);
    }

    @Override
    protected void onSetDependencyMet(String apnType, boolean met) {
        // don't allow users to tweak hipri to work around default dependency not met
        if (PhoneConstants.APN_TYPE_HIPRI.equals(apnType)) return;

        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext == null) {
            loge("onSetDependencyMet: ApnContext not found in onSetDependencyMet(" +
                    apnType + ", " + met + ")");
            return;
        }
        applyNewState(apnContext, apnContext.isEnabled(), met);
        if (PhoneConstants.APN_TYPE_DEFAULT.equals(apnType)) {
            // tie actions on default to similar actions on HIPRI regarding dependencyMet
            apnContext = mApnContexts.get(PhoneConstants.APN_TYPE_HIPRI);
            if (apnContext != null) applyNewState(apnContext, apnContext.isEnabled(), met);
        }
    }

    private void applyNewState(ApnContext apnContext, boolean enabled, boolean met) {
        boolean cleanup = false;
        boolean trySetup = false;
        if (DBG) {
            log("applyNewState(" + apnContext.getApnType() + ", " + enabled +
                    "(" + apnContext.isEnabled() + "), " + met + "(" +
                    apnContext.getDependencyMet() +"))");
        }
        if (apnContext.isReady()) {
            cleanup = true;
            if (enabled && met) {
                DctConstants.State state = apnContext.getState();
                switch(state) {
                    case CONNECTING:
                    case SCANNING:
                    case CONNECTED:
                    case DISCONNECTING:
                        // We're "READY" and active so just return
                        if (DBG) log("applyNewState: 'ready' so return");
                        return;
                    case IDLE:
                        // fall through: this is unexpected but if it happens cleanup and try setup
                    case FAILED:
                    case RETRYING: {
                        // We're "READY" but not active so disconnect (cleanup = true) and
                        // connect (trySetup = true) to be sure we retry the connection.
                        trySetup = true;
                        apnContext.setReason(Phone.REASON_DATA_ENABLED);
                        break;
                    }
                }
            } else if (met) {
                apnContext.setReason(Phone.REASON_DATA_DISABLED);
                // If ConnectivityService has disabled this network, stop trying to bring
                // it up, but do not tear it down - ConnectivityService will do that
                // directly by talking with the DataConnection.
                //
                // This doesn't apply to DUN, however.  Those connections have special
                // requirements from carriers and we need stop using them when the dun
                // request goes away.  This applies to both CDMA and GSM because they both
                // can declare the DUN APN sharable by default traffic, thus still satisfying
                // those requests and not torn down organically.
                if (apnContext.getApnType() == PhoneConstants.APN_TYPE_DUN && teardownForDun()) {
                    cleanup = true;
                } else {
                    cleanup = false;
                }
            } else {
                apnContext.setReason(Phone.REASON_DATA_DEPENDENCY_UNMET);
            }
        } else {
            if (enabled && met) {
                if (apnContext.isEnabled()) {
                    apnContext.setReason(Phone.REASON_DATA_DEPENDENCY_MET);
                } else {
                    apnContext.setReason(Phone.REASON_DATA_ENABLED);
                }
                if (apnContext.getState() == DctConstants.State.FAILED) {
                    apnContext.setState(DctConstants.State.IDLE);
                }
                trySetup = true;
            }
        }
        apnContext.setEnabled(enabled);
        apnContext.setDependencyMet(met);
        if (cleanup) cleanUpConnection(true, apnContext);
        if (trySetup) trySetupData(apnContext);
    }

    private DcAsyncChannel checkForCompatibleConnectedApnContext(ApnContext apnContext) {
        String apnType = apnContext.getApnType();
        ApnSetting dunSetting = null;

        if (PhoneConstants.APN_TYPE_DUN.equals(apnType)) {
            dunSetting = fetchDunApn();
        }
        if (DBG) {
            log("checkForCompatibleConnectedApnContext: apnContext=" + apnContext );
        }

        DcAsyncChannel potentialDcac = null;
        ApnContext potentialApnCtx = null;
        for (ApnContext curApnCtx : mApnContexts.values()) {
            DcAsyncChannel curDcac = curApnCtx.getDcAc();
            log("curDcac: " + curDcac);
            if (curDcac != null) {
                ApnSetting apnSetting = curApnCtx.getApnSetting();
                log("apnSetting: " + apnSetting);
                if (dunSetting != null) {
                    if (dunSetting.equals(apnSetting)) {
                        switch (curApnCtx.getState()) {
                            case CONNECTED:
                                if (DBG) {
                                    log("checkForCompatibleConnectedApnContext:"
                                            + " found dun conn=" + curDcac
                                            + " curApnCtx=" + curApnCtx);
                                }
                                return curDcac;
                            case RETRYING:
                            case CONNECTING:
                                potentialDcac = curDcac;
                                potentialApnCtx = curApnCtx;
                            default:
                                // Not connected, potential unchanged
                                break;
                        }
                    }
                } else if (apnSetting != null && apnSetting.canHandleType(apnType)) {
                    switch (curApnCtx.getState()) {
                        case CONNECTED:
                            if (DBG) {
                                log("checkForCompatibleConnectedApnContext:"
                                        + " found canHandle conn=" + curDcac
                                        + " curApnCtx=" + curApnCtx);
                            }
                            return curDcac;
                        case RETRYING:
                        case CONNECTING:
                            potentialDcac = curDcac;
                            potentialApnCtx = curApnCtx;
                        default:
                            // Not connected, potential unchanged
                            break;
                    }
                }
            } else {
                if (VDBG) {
                    log("checkForCompatibleConnectedApnContext: not conn curApnCtx=" + curApnCtx);
                }
            }
        }
        if (potentialDcac != null) {
            if (DBG) {
                log("checkForCompatibleConnectedApnContext: found potential conn=" + potentialDcac
                        + " curApnCtx=" + potentialApnCtx);
            }
            return potentialDcac;
        }

        if (DBG) log("checkForCompatibleConnectedApnContext: NO conn apnContext=" + apnContext);
        return null;
    }

    @Override
    protected void onEnableApn(int apnId, int enabled) {
        ApnContext apnContext = mApnContexts.get(apnIdToType(apnId));
        if (apnContext == null) {
            loge("onEnableApn(" + apnId + ", " + enabled + "): NO ApnContext");
            return;
        }
        // TODO change our retry manager to use the appropriate numbers for the new APN
        if (DBG) log("onEnableApn: apnContext=" + apnContext + " call applyNewState");
        applyNewState(apnContext, enabled == DctConstants.ENABLED, apnContext.getDependencyMet());
    }

    @Override
    // TODO: We shouldnt need this.
    protected boolean onTrySetupData(String reason) {
        if (DBG) log("onTrySetupData: reason=" + reason);
        setupDataOnConnectableApns(reason);
        return true;
    }

    protected boolean onTrySetupData(ApnContext apnContext) {
        if (DBG) log("onTrySetupData: apnContext=" + apnContext);
        return trySetupData(apnContext);
    }

    @Override
    protected void onRoamingOff() {
        if (DBG) log("onRoamingOff");

        if (mUserDataEnabled == false) return;

        if (getDataOnRoamingEnabled() == false) {
            notifyOffApnsOfAvailability(Phone.REASON_ROAMING_OFF);
            setupDataOnConnectableApns(Phone.REASON_ROAMING_OFF);
        } else {
            notifyDataConnection(Phone.REASON_ROAMING_OFF);
        }
    }

    @Override
    protected void onRoamingOn() {
        if (mUserDataEnabled == false) return;

        if (getDataOnRoamingEnabled()) {
            if (DBG) log("onRoamingOn: setup data on roaming");
            setupDataOnConnectableApns(Phone.REASON_ROAMING_ON);
            notifyDataConnection(Phone.REASON_ROAMING_ON);
        } else {
            if (DBG) log("onRoamingOn: Tear down data connection on roaming.");
            cleanUpAllConnections(true, Phone.REASON_ROAMING_ON);
            notifyOffApnsOfAvailability(Phone.REASON_ROAMING_ON);
        }
    }

    @Override
    protected void onRadioAvailable() {
        if (DBG) log("onRadioAvailable");
        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            // setState(DctConstants.State.CONNECTED);
            notifyDataConnection(null);

            log("onRadioAvailable: We're on the simulator; assuming data is connected");
        }

        IccRecords r = mIccRecords.get();
        if (r != null && r.getRecordsLoaded()) {
            notifyOffApnsOfAvailability(null);
        }

        if (getOverallState() != DctConstants.State.IDLE) {
            cleanUpConnection(true, null);
        }
    }

    @Override
    protected void onRadioOffOrNotAvailable() {
        // Make sure our reconnect delay starts at the initial value
        // next time the radio comes on

        mReregisterOnReconnectFailure = false;

        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            log("We're on the simulator; assuming radio off is meaningless");
        } else {
            if (DBG) log("onRadioOffOrNotAvailable: is off and clean up all connections");
            cleanUpAllConnections(false, Phone.REASON_RADIO_TURNED_OFF);
        }
        notifyOffApnsOfAvailability(null);
    }

    @Override
    protected void completeConnection(ApnContext apnContext) {
        boolean isProvApn = apnContext.isProvisioningApn();

        if (DBG) log("completeConnection: successful, notify the world apnContext=" + apnContext);

        if (mIsProvisioning && !TextUtils.isEmpty(mProvisioningUrl)) {
            if (DBG) {
                log("completeConnection: MOBILE_PROVISIONING_ACTION url="
                        + mProvisioningUrl);
            }
            Intent newIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,
                    Intent.CATEGORY_APP_BROWSER);
            newIntent.setData(Uri.parse(mProvisioningUrl));
            newIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT |
                    Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mPhone.getContext().startActivity(newIntent);
            } catch (ActivityNotFoundException e) {
                loge("completeConnection: startActivityAsUser failed" + e);
            }
        }
        mIsProvisioning = false;
        mProvisioningUrl = null;

        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
        startNetStatPoll();
        startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
    }

    /**
     * A SETUP (aka bringUp) has completed, possibly with an error. If
     * there is an error this method will call {@link #onDataSetupCompleteError}.
     */
    @Override
    protected void onDataSetupComplete(AsyncResult ar) {

        DcFailCause cause = DcFailCause.UNKNOWN;
        boolean handleError = false;
        ApnContext apnContext = null;

        if(ar.userObj instanceof ApnContext){
            apnContext = (ApnContext)ar.userObj;
        } else {
            throw new RuntimeException("onDataSetupComplete: No apnContext");
        }

        if (ar.exception == null) {
            DcAsyncChannel dcac = apnContext.getDcAc();

            if (RADIO_TESTS) {
                // Note: To change radio.test.onDSC.null.dcac from command line you need to
                // adb root and adb remount and from the command line you can only change the
                // value to 1 once. To change it a second time you can reboot or execute
                // adb shell stop and then adb shell start. The command line to set the value is:
                // adb shell sqlite3 /data/data/com.android.providers.settings/databases/settings.db "insert into system (name,value) values ('radio.test.onDSC.null.dcac', '1');"
                ContentResolver cr = mPhone.getContext().getContentResolver();
                String radioTestProperty = "radio.test.onDSC.null.dcac";
                if (Settings.System.getInt(cr, radioTestProperty, 0) == 1) {
                    log("onDataSetupComplete: " + radioTestProperty +
                            " is true, set dcac to null and reset property to false");
                    dcac = null;
                    Settings.System.putInt(cr, radioTestProperty, 0);
                    log("onDataSetupComplete: " + radioTestProperty + "=" +
                            Settings.System.getInt(mPhone.getContext().getContentResolver(),
                                    radioTestProperty, -1));
                }
            }
            if (dcac == null) {
                log("onDataSetupComplete: no connection to DC, handle as error");
                cause = DcFailCause.CONNECTION_TO_DATACONNECTIONAC_BROKEN;
                handleError = true;
            } else {
                ApnSetting apn = apnContext.getApnSetting();
                if (DBG) {
                    log("onDataSetupComplete: success apn=" + (apn == null ? "unknown" : apn.apn));
                }
                if (apn != null && apn.proxy != null && apn.proxy.length() != 0) {
                    try {
                        String port = apn.port;
                        if (TextUtils.isEmpty(port)) port = "8080";
                        ProxyInfo proxy = new ProxyInfo(apn.proxy,
                                Integer.parseInt(port), null);
                        dcac.setLinkPropertiesHttpProxySync(proxy);
                    } catch (NumberFormatException e) {
                        loge("onDataSetupComplete: NumberFormatException making ProxyProperties (" +
                                apn.port + "): " + e);
                    }
                }

                // everything is setup
                if(TextUtils.equals(apnContext.getApnType(),PhoneConstants.APN_TYPE_DEFAULT)) {
                    SystemProperties.set(PUPPET_MASTER_RADIO_STRESS_TEST, "true");
                    if (mCanSetPreferApn && mPreferredApn == null) {
                        if (DBG) log("onDataSetupComplete: PREFERED APN is null");
                        mPreferredApn = apn;
                        if (mPreferredApn != null) {
                            setPreferredApn(mPreferredApn.id);
                        }
                    }
                } else {
                    SystemProperties.set(PUPPET_MASTER_RADIO_STRESS_TEST, "false");
                }

                // A connection is setup
                apnContext.setState(DctConstants.State.CONNECTED);
                boolean isProvApn = apnContext.isProvisioningApn();
                final ConnectivityManager cm = ConnectivityManager.from(mPhone.getContext());
                if (mProvisionBroadcastReceiver != null) {
                    mPhone.getContext().unregisterReceiver(mProvisionBroadcastReceiver);
                    mProvisionBroadcastReceiver = null;
                }
                if ((!isProvApn) || mIsProvisioning) {
                    // Hide any provisioning notification.
                    cm.setProvisioningNotificationVisible(false, ConnectivityManager.TYPE_MOBILE,
                            mProvisionActionName);
                    // Complete the connection normally notifying the world we're connected.
                    // We do this if this isn't a special provisioning apn or if we've been
                    // told its time to provision.
                    completeConnection(apnContext);
                } else {
                    // This is a provisioning APN that we're reporting as connected. Later
                    // when the user desires to upgrade this to a "default" connection,
                    // mIsProvisioning == true, we'll go through the code path above.
                    // mIsProvisioning becomes true when CMD_ENABLE_MOBILE_PROVISIONING
                    // is sent to the DCT.
                    if (DBG) {
                        log("onDataSetupComplete: successful, BUT send connected to prov apn as"
                                + " mIsProvisioning:" + mIsProvisioning + " == false"
                                + " && (isProvisioningApn:" + isProvApn + " == true");
                    }

                    // While radio is up, grab provisioning URL.  The URL contains ICCID which
                    // disappears when radio is off.
                    mProvisionBroadcastReceiver = new ProvisionNotificationBroadcastReceiver(
                            cm.getMobileProvisioningUrl());
                    mPhone.getContext().registerReceiver(mProvisionBroadcastReceiver,
                            new IntentFilter(mProvisionActionName));
                    // Put up user notification that sign-in is required.
                    cm.setProvisioningNotificationVisible(true, ConnectivityManager.TYPE_MOBILE,
                            mProvisionActionName);
                    // Turn off radio to save battery and avoid wasting carrier resources.
                    // The network isn't usable and network validation will just fail anyhow.
                    setRadio(false);

                    Intent intent = new Intent(
                            TelephonyIntents.ACTION_DATA_CONNECTION_CONNECTED_TO_PROVISIONING_APN);
                    intent.putExtra(PhoneConstants.DATA_APN_KEY, apnContext.getApnSetting().apn);
                    intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnContext.getApnType());

                    String apnType = apnContext.getApnType();
                    LinkProperties linkProperties = getLinkProperties(apnType);
                    if (linkProperties != null) {
                        intent.putExtra(PhoneConstants.DATA_LINK_PROPERTIES_KEY, linkProperties);
                        String iface = linkProperties.getInterfaceName();
                        if (iface != null) {
                            intent.putExtra(PhoneConstants.DATA_IFACE_NAME_KEY, iface);
                        }
                    }
                    NetworkCapabilities networkCapabilities = getNetworkCapabilities(apnType);
                    if (networkCapabilities != null) {
                        intent.putExtra(PhoneConstants.DATA_NETWORK_CAPABILITIES_KEY,
                                networkCapabilities);
                    }

                    mPhone.getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
                }
                if (DBG) {
                    log("onDataSetupComplete: SETUP complete type=" + apnContext.getApnType()
                        + ", reason:" + apnContext.getReason());
                }
            }
        } else {
            cause = (DcFailCause) (ar.result);
            if (DBG) {
                ApnSetting apn = apnContext.getApnSetting();
                log(String.format("onDataSetupComplete: error apn=%s cause=%s",
                        (apn == null ? "unknown" : apn.apn), cause));
            }
            if (cause.isEventLoggable()) {
                // Log this failure to the Event Logs.
                int cid = getCellLocationId();
                EventLog.writeEvent(EventLogTags.PDP_SETUP_FAIL,
                        cause.ordinal(), cid, TelephonyManager.getDefault().getNetworkType());
            }
            ApnSetting apn = apnContext.getApnSetting();
            mPhone.notifyPreciseDataConnectionFailed(apnContext.getReason(),
                    apnContext.getApnType(), apn != null ? apn.apn : "unknown", cause.toString());

            // Count permanent failures and remove the APN we just tried
            if (cause.isPermanentFail()) apnContext.decWaitingApnsPermFailCount();

            apnContext.removeWaitingApn(apnContext.getApnSetting());
            if (DBG) {
                log(String.format("onDataSetupComplete: WaitingApns.size=%d" +
                        " WaitingApnsPermFailureCountDown=%d",
                        apnContext.getWaitingApns().size(),
                        apnContext.getWaitingApnsPermFailCount()));
            }
            handleError = true;
        }

        if (handleError) {
            onDataSetupCompleteError(ar);
        }

        /* If flag is set to false after SETUP_DATA_CALL is invoked, we need
         * to clean data connections.
         */
        if (!mInternalDataEnabled) {
            cleanUpAllConnections(null);
        }

    }

    /**
     * @return number of milli-seconds to delay between trying apns'
     */
    private int getApnDelay() {
        if (mFailFast) {
            return SystemProperties.getInt("persist.radio.apn_ff_delay",
                    APN_FAIL_FAST_DELAY_DEFAULT_MILLIS);
        } else {
            return SystemProperties.getInt("persist.radio.apn_delay", APN_DELAY_DEFAULT_MILLIS);
        }
    }

    /**
     * Error has occurred during the SETUP {aka bringUP} request and the DCT
     * should either try the next waiting APN or start over from the
     * beginning if the list is empty. Between each SETUP request there will
     * be a delay defined by {@link #getApnDelay()}.
     */
    @Override
    protected void onDataSetupCompleteError(AsyncResult ar) {
        String reason = "";
        ApnContext apnContext = null;

        if(ar.userObj instanceof ApnContext){
            apnContext = (ApnContext)ar.userObj;
        } else {
            throw new RuntimeException("onDataSetupCompleteError: No apnContext");
        }

        // See if there are more APN's to try
        if (apnContext.getWaitingApns().isEmpty()) {
            apnContext.setState(DctConstants.State.FAILED);
            mPhone.notifyDataConnection(Phone.REASON_APN_FAILED, apnContext.getApnType());

            apnContext.setDataConnectionAc(null);

            if (apnContext.getWaitingApnsPermFailCount() == 0) {
                if (DBG) {
                    log("onDataSetupCompleteError: All APN's had permanent failures, stop retrying");
                }
            } else {
                int delay = getApnDelay();
                if (DBG) {
                    log("onDataSetupCompleteError: Not all APN's had permanent failures delay="
                            + delay);
                }
                startAlarmForRestartTrySetup(delay, apnContext);
            }
        } else {
            if (DBG) log("onDataSetupCompleteError: Try next APN");
            apnContext.setState(DctConstants.State.SCANNING);
            // Wait a bit before trying the next APN, so that
            // we're not tying up the RIL command channel
            startAlarmForReconnect(getApnDelay(), apnContext);
        }
    }

    /**
     * Called when EVENT_DISCONNECT_DONE is received.
     */
    @Override
    protected void onDisconnectDone(int connId, AsyncResult ar) {
        ApnContext apnContext = null;

        if (ar.userObj instanceof ApnContext) {
            apnContext = (ApnContext) ar.userObj;
        } else {
            loge("onDisconnectDone: Invalid ar in onDisconnectDone, ignore");
            return;
        }

        if(DBG) log("onDisconnectDone: EVENT_DISCONNECT_DONE apnContext=" + apnContext);
        apnContext.setState(DctConstants.State.IDLE);

        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());

        // if all data connection are gone, check whether Airplane mode request was
        // pending.
        if (isDisconnected()) {
            if (mPhone.getServiceStateTracker().processPendingRadioPowerOffAfterDataOff()) {
                if(DBG) log("onDisconnectDone: radio will be turned off, no retries");
                // Radio will be turned off. No need to retry data setup
                apnContext.setApnSetting(null);
                apnContext.setDataConnectionAc(null);

                // Need to notify disconnect as well, in the case of switching Airplane mode.
                // Otherwise, it would cause 30s delayed to turn on Airplane mode.
                if (mDisconnectPendingCount > 0)
                    mDisconnectPendingCount--;

                if (mDisconnectPendingCount == 0) {
                    notifyDataDisconnectComplete();
                    notifyAllDataDisconnected();
                }
                return;
            }
        }

        // If APN is still enabled, try to bring it back up automatically
        if (mAttached.get() && apnContext.isReady() && retryAfterDisconnected(apnContext)) {
            SystemProperties.set(PUPPET_MASTER_RADIO_STRESS_TEST, "false");
            // Wait a bit before trying the next APN, so that
            // we're not tying up the RIL command channel.
            // This also helps in any external dependency to turn off the context.
            if(DBG) log("onDisconnectDone: attached, ready and retry after disconnect");
            startAlarmForReconnect(getApnDelay(), apnContext);
        } else {
            boolean restartRadioAfterProvisioning = mPhone.getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_restartRadioAfterProvisioning);

            if (apnContext.isProvisioningApn() && restartRadioAfterProvisioning) {
                log("onDisconnectDone: restartRadio after provisioning");
                restartRadio();
            }
            apnContext.setApnSetting(null);
            apnContext.setDataConnectionAc(null);
            if (isOnlySingleDcAllowed(mPhone.getServiceState().getRilDataRadioTechnology())) {
                if(DBG) log("onDisconnectDone: isOnlySigneDcAllowed true so setup single apn");
                setupDataOnConnectableApns(Phone.REASON_SINGLE_PDN_ARBITRATION);
            } else {
                if(DBG) log("onDisconnectDone: not retrying");
            }
        }

        if (mDisconnectPendingCount > 0)
            mDisconnectPendingCount--;

        if (mDisconnectPendingCount == 0) {
            notifyDataDisconnectComplete();
            notifyAllDataDisconnected();
        }

    }

    /**
     * Called when EVENT_DISCONNECT_DC_RETRYING is received.
     */
    @Override
    protected void onDisconnectDcRetrying(int connId, AsyncResult ar) {
        // We could just do this in DC!!!
        ApnContext apnContext = null;

        if (ar.userObj instanceof ApnContext) {
            apnContext = (ApnContext) ar.userObj;
        } else {
            loge("onDisconnectDcRetrying: Invalid ar in onDisconnectDone, ignore");
            return;
        }

        apnContext.setState(DctConstants.State.RETRYING);
        if(DBG) log("onDisconnectDcRetrying: apnContext=" + apnContext);

        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
    }


    @Override
    protected void onVoiceCallStarted() {
        if (DBG) log("onVoiceCallStarted");
        mInVoiceCall = true;
        if (isConnected() && ! mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
            if (DBG) log("onVoiceCallStarted stop polling");
            stopNetStatPoll();
            stopDataStallAlarm();
            notifyDataConnection(Phone.REASON_VOICE_CALL_STARTED);
        }
    }

    @Override
    protected void onVoiceCallEnded() {
        if (DBG) log("onVoiceCallEnded");
        mInVoiceCall = false;
        if (isConnected()) {
            if (!mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
                startNetStatPoll();
                startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
                notifyDataConnection(Phone.REASON_VOICE_CALL_ENDED);
            } else {
                // clean slate after call end.
                resetPollStats();
            }
        }
        // reset reconnect timer
        setupDataOnConnectableApns(Phone.REASON_VOICE_CALL_ENDED);
    }

    @Override
    protected void onCleanUpConnection(boolean tearDown, int apnId, String reason) {
        if (DBG) log("onCleanUpConnection");
        ApnContext apnContext = mApnContexts.get(apnIdToType(apnId));
        if (apnContext != null) {
            apnContext.setReason(reason);
            cleanUpConnection(tearDown, apnContext);
        }
    }

    @Override
    protected boolean isConnected() {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.getState() == DctConstants.State.CONNECTED) {
                // At least one context is connected, return true
                return true;
            }
        }
        // There are not any contexts connected, return false
        return false;
    }

    @Override
    public boolean isDisconnected() {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (!apnContext.isDisconnected()) {
                // At least one context was not disconnected return false
                return false;
            }
        }
        // All contexts were disconnected so return true
        return true;
    }

    @Override
    protected void notifyDataConnection(String reason) {
        if (DBG) log("notifyDataConnection: reason=" + reason);
        for (ApnContext apnContext : mApnContexts.values()) {
            if (mAttached.get() && apnContext.isReady()) {
                if (DBG) log("notifyDataConnection: type:" + apnContext.getApnType());
                mPhone.notifyDataConnection(reason != null ? reason : apnContext.getReason(),
                        apnContext.getApnType());
            }
        }
        notifyOffApnsOfAvailability(reason);
    }

    /**
     * Based on the sim operator numeric, create a list for all possible
     * Data Connections and setup the preferredApn.
     */
    private void createAllApnList() {
        mAllApnSettings = new ArrayList<ApnSetting>();
        IccRecords r = mIccRecords.get();
        String operator = (r != null) ? r.getOperatorNumeric() : "";
        if (operator != null) {
            String selection = "numeric = '" + operator + "'";
            // query only enabled apn.
            // carrier_enabled : 1 means enabled apn, 0 disabled apn.
            // selection += " and carrier_enabled = 1";
            if (DBG) log("createAllApnList: selection=" + selection);

            Cursor cursor = mPhone.getContext().getContentResolver().query(
                    Telephony.Carriers.CONTENT_URI, null, selection, null, null);

            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    mAllApnSettings = createApnList(cursor);
                }
                cursor.close();
            }
        }

        addEmergencyApnSetting();

        dedupeApnSettings();

        if (mAllApnSettings.isEmpty()) {
            if (DBG) log("createAllApnList: No APN found for carrier: " + operator);
            mPreferredApn = null;
            // TODO: What is the right behavior?
            //notifyNoData(DataConnection.FailCause.MISSING_UNKNOWN_APN);
        } else {
            mPreferredApn = getPreferredApn();
            if (mPreferredApn != null && !mPreferredApn.numeric.equals(operator)) {
                mPreferredApn = null;
                setPreferredApn(-1);
            }
            if (DBG) log("createAllApnList: mPreferredApn=" + mPreferredApn);
        }
        if (DBG) log("createAllApnList: X mAllApnSettings=" + mAllApnSettings);

        setDataProfilesAsNeeded();
    }

    private void dedupeApnSettings() {
        ArrayList<ApnSetting> resultApns = new ArrayList<ApnSetting>();

        // coalesce APNs if they are similar enough to prevent
        // us from bringing up two data calls with the same interface
        int i = 0;
        while (i < mAllApnSettings.size() - 1) {
            ApnSetting first = mAllApnSettings.get(i);
            ApnSetting second = null;
            int j = i + 1;
            while (j < mAllApnSettings.size()) {
                second = mAllApnSettings.get(j);
                if (apnsSimilar(first, second)) {
                    ApnSetting newApn = mergeApns(first, second);
                    mAllApnSettings.set(i, newApn);
                    first = newApn;
                    mAllApnSettings.remove(j);
                } else {
                    j++;
                }
            }
            i++;
        }
    }

    // Check if neither mention DUN and are substantially similar
    private boolean apnsSimilar(ApnSetting first, ApnSetting second) {
        return (first.canHandleType(PhoneConstants.APN_TYPE_DUN) == false &&
                second.canHandleType(PhoneConstants.APN_TYPE_DUN) == false &&
                Objects.equals(first.apn, second.apn) &&
                Objects.equals(first.proxy, second.proxy) &&
                Objects.equals(first.port, second.port) &&
                Objects.equals(first.protocol, second.protocol) &&
                Objects.equals(first.roamingProtocol, second.roamingProtocol) &&
                first.carrierEnabled == second.carrierEnabled &&
                first.bearer == second.bearer &&
                first.profileId == second.profileId &&
                Objects.equals(first.mvnoType, second.mvnoType) &&
                Objects.equals(first.mvnoMatchData, second.mvnoMatchData) &&
                xorEquals(first.mmsc, second.mmsc) &&
                xorEquals(first.mmsProxy, second.mmsProxy) &&
                xorEquals(first.mmsPort, second.mmsPort));
    }

    // equal or one is not specified
    private boolean xorEquals(String first, String second) {
        return (Objects.equals(first, second) ||
                TextUtils.isEmpty(first) ||
                TextUtils.isEmpty(second));
    }

    private ApnSetting mergeApns(ApnSetting dest, ApnSetting src) {
        ArrayList<String> resultTypes = new ArrayList<String>();
        resultTypes.addAll(Arrays.asList(dest.types));
        for (String srcType : src.types) {
            if (resultTypes.contains(srcType) == false) resultTypes.add(srcType);
        }
        String mmsc = (TextUtils.isEmpty(dest.mmsc) ? src.mmsc : dest.mmsc);
        String mmsProxy = (TextUtils.isEmpty(dest.mmsProxy) ? src.mmsProxy : dest.mmsProxy);
        String mmsPort = (TextUtils.isEmpty(dest.mmsPort) ? src.mmsPort : dest.mmsPort);


        return new ApnSetting(dest.id, dest.numeric, dest.carrier, dest.apn,
                dest.proxy, dest.port, mmsc, mmsProxy, mmsPort, dest.user, dest.password,
                dest.authType, resultTypes.toArray(new String[0]), dest.protocol,
                dest.roamingProtocol, dest.carrierEnabled, dest.bearer, dest.profileId,
                (dest.modemCognitive || src.modemCognitive), dest.maxConns, dest.waitTime,
                dest.maxConnsTime, dest.mtu, dest.mvnoType, dest.mvnoMatchData);
    }

    /** Return the DC AsyncChannel for the new data connection */
    private DcAsyncChannel createDataConnection() {
        if (DBG) log("createDataConnection E");

        int id = mUniqueIdGenerator.getAndIncrement();
        DataConnection conn = DataConnection.makeDataConnection(mPhone, id,
                                                this, mDcTesterFailBringUpAll, mDcc);
        mDataConnections.put(id, conn);
        DcAsyncChannel dcac = new DcAsyncChannel(conn, LOG_TAG);
        int status = dcac.fullyConnectSync(mPhone.getContext(), this, conn.getHandler());
        if (status == AsyncChannel.STATUS_SUCCESSFUL) {
            mDataConnectionAcHashMap.put(dcac.getDataConnectionIdSync(), dcac);
        } else {
            loge("createDataConnection: Could not connect to dcac=" + dcac + " status=" + status);
        }

        if (DBG) log("createDataConnection() X id=" + id + " dc=" + conn);
        return dcac;
    }

    private void destroyDataConnections() {
        if(mDataConnections != null) {
            if (DBG) log("destroyDataConnections: clear mDataConnectionList");
            mDataConnections.clear();
        } else {
            if (DBG) log("destroyDataConnections: mDataConnecitonList is empty, ignore");
        }
    }

    /**
     * Build a list of APNs to be used to create PDP's.
     *
     * @param requestedApnType
     * @return waitingApns list to be used to create PDP
     *          error when waitingApns.isEmpty()
     */
    private ArrayList<ApnSetting> buildWaitingApns(String requestedApnType, int radioTech) {
        if (DBG) log("buildWaitingApns: E requestedApnType=" + requestedApnType);
        ArrayList<ApnSetting> apnList = new ArrayList<ApnSetting>();

        if (requestedApnType.equals(PhoneConstants.APN_TYPE_DUN)) {
            ApnSetting dun = fetchDunApn();
            if (dun != null) {
                apnList.add(dun);
                if (DBG) log("buildWaitingApns: X added APN_TYPE_DUN apnList=" + apnList);
                return apnList;
            }
        }

        IccRecords r = mIccRecords.get();
        String operator = (r != null) ? r.getOperatorNumeric() : "";

        // This is a workaround for a bug (7305641) where we don't failover to other
        // suitable APNs if our preferred APN fails.  On prepaid ATT sims we need to
        // failover to a provisioning APN, but once we've used their default data
        // connection we are locked to it for life.  This change allows ATT devices
        // to say they don't want to use preferred at all.
        boolean usePreferred = true;
        try {
            usePreferred = ! mPhone.getContext().getResources().getBoolean(com.android.
                    internal.R.bool.config_dontPreferApn);
        } catch (Resources.NotFoundException e) {
            if (DBG) log("buildWaitingApns: usePreferred NotFoundException set to true");
            usePreferred = true;
        }
        if (DBG) {
            log("buildWaitingApns: usePreferred=" + usePreferred
                    + " canSetPreferApn=" + mCanSetPreferApn
                    + " mPreferredApn=" + mPreferredApn
                    + " operator=" + operator + " radioTech=" + radioTech
                    + " IccRecords r=" + r);
        }

        if (usePreferred && mCanSetPreferApn && mPreferredApn != null &&
                mPreferredApn.canHandleType(requestedApnType)) {
            if (DBG) {
                log("buildWaitingApns: Preferred APN:" + operator + ":"
                        + mPreferredApn.numeric + ":" + mPreferredApn);
            }
            if (mPreferredApn.numeric.equals(operator)) {
                if (mPreferredApn.bearer == 0 || mPreferredApn.bearer == radioTech) {
                    apnList.add(mPreferredApn);
                    if (DBG) log("buildWaitingApns: X added preferred apnList=" + apnList);
                    return apnList;
                } else {
                    if (DBG) log("buildWaitingApns: no preferred APN");
                    setPreferredApn(-1);
                    mPreferredApn = null;
                }
            } else {
                if (DBG) log("buildWaitingApns: no preferred APN");
                setPreferredApn(-1);
                mPreferredApn = null;
            }
        }
        if (mAllApnSettings != null) {
            if (DBG) log("buildWaitingApns: mAllApnSettings=" + mAllApnSettings);
            for (ApnSetting apn : mAllApnSettings) {
                if (DBG) log("buildWaitingApns: apn=" + apn);
                if (apn.canHandleType(requestedApnType)) {
                    if (apn.bearer == 0 || apn.bearer == radioTech) {
                        if (DBG) log("buildWaitingApns: adding apn=" + apn.toString());
                        apnList.add(apn);
                    } else {
                        if (DBG) {
                            log("buildWaitingApns: bearer:" + apn.bearer + " != "
                                    + "radioTech:" + radioTech);
                        }
                    }
                } else {
                if (DBG) {
                    log("buildWaitingApns: couldn't handle requesedApnType="
                            + requestedApnType);
                }
            }
            }
        } else {
            loge("mAllApnSettings is empty!");
        }
        if (DBG) log("buildWaitingApns: X apnList=" + apnList);
        return apnList;
    }

    private String apnListToString (ArrayList<ApnSetting> apns) {
        StringBuilder result = new StringBuilder();
        for (int i = 0, size = apns.size(); i < size; i++) {
            result.append('[')
                  .append(apns.get(i).toString())
                  .append(']');
        }
        return result.toString();
    }

    private void setPreferredApn(int pos) {
        if (!mCanSetPreferApn) {
            log("setPreferredApn: X !canSEtPreferApn");
            return;
        }

        log("setPreferredApn: delete");
        ContentResolver resolver = mPhone.getContext().getContentResolver();
        resolver.delete(PREFERAPN_NO_UPDATE_URI, null, null);

        if (pos >= 0) {
            log("setPreferredApn: insert");
            ContentValues values = new ContentValues();
            values.put(APN_ID, pos);
            resolver.insert(PREFERAPN_NO_UPDATE_URI, values);
        }
    }

    private ApnSetting getPreferredApn() {
        if (mAllApnSettings.isEmpty()) {
            log("getPreferredApn: X not found mAllApnSettings.isEmpty");
            return null;
        }

        Cursor cursor = mPhone.getContext().getContentResolver().query(
                PREFERAPN_NO_UPDATE_URI, new String[] { "_id", "name", "apn" },
                null, null, Telephony.Carriers.DEFAULT_SORT_ORDER);

        if (cursor != null) {
            mCanSetPreferApn = true;
        } else {
            mCanSetPreferApn = false;
        }
        log("getPreferredApn: mRequestedApnType=" + mRequestedApnType + " cursor=" + cursor
                + " cursor.count=" + ((cursor != null) ? cursor.getCount() : 0));

        if (mCanSetPreferApn && cursor.getCount() > 0) {
            int pos;
            cursor.moveToFirst();
            pos = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID));
            for(ApnSetting p : mAllApnSettings) {
                log("getPreferredApn: apnSetting=" + p);
                if (p.id == pos && p.canHandleType(mRequestedApnType)) {
                    log("getPreferredApn: X found apnSetting" + p);
                    cursor.close();
                    return p;
                }
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        log("getPreferredApn: X not found");
        return null;
    }

    @Override
    public void handleMessage (Message msg) {
        if (DBG) log("handleMessage msg=" + msg);

        if (!mPhone.mIsTheCurrentActivePhone || mIsDisposed) {
            loge("handleMessage: Ignore GSM msgs since GSM phone is inactive");
            return;
        }

        if (!isActiveDataSubscription()) {
            loge("Ignore msgs since phone is not the current DDS");
            return;
        }

        switch (msg.what) {
            case DctConstants.EVENT_RECORDS_LOADED:
                onRecordsLoaded();
                break;

            case DctConstants.EVENT_DATA_CONNECTION_DETACHED:
                onDataConnectionDetached();
                break;

            case DctConstants.EVENT_DATA_CONNECTION_ATTACHED:
                onDataConnectionAttached();
                break;

            case DctConstants.EVENT_DO_RECOVERY:
                doRecovery();
                break;

            case DctConstants.EVENT_APN_CHANGED:
                onApnChanged();
                break;

            case DctConstants.EVENT_PS_RESTRICT_ENABLED:
                /**
                 * We don't need to explicitly to tear down the PDP context
                 * when PS restricted is enabled. The base band will deactive
                 * PDP context and notify us with PDP_CONTEXT_CHANGED.
                 * But we should stop the network polling and prevent reset PDP.
                 */
                if (DBG) log("EVENT_PS_RESTRICT_ENABLED " + mIsPsRestricted);
                stopNetStatPoll();
                stopDataStallAlarm();
                mIsPsRestricted = true;
                break;

            case DctConstants.EVENT_PS_RESTRICT_DISABLED:
                /**
                 * When PS restrict is removed, we need setup PDP connection if
                 * PDP connection is down.
                 */
                if (DBG) log("EVENT_PS_RESTRICT_DISABLED " + mIsPsRestricted);
                mIsPsRestricted  = false;
                if (isConnected()) {
                    startNetStatPoll();
                    startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
                } else {
                    // TODO: Should all PDN states be checked to fail?
                    if (mState == DctConstants.State.FAILED) {
                        cleanUpAllConnections(false, Phone.REASON_PS_RESTRICT_ENABLED);
                        mReregisterOnReconnectFailure = false;
                    }
                    ApnContext apnContext = mApnContexts.get(PhoneConstants.APN_TYPE_DEFAULT);
                    if (apnContext != null) {
                        apnContext.setReason(Phone.REASON_PS_RESTRICT_ENABLED);
                        trySetupData(apnContext);
                    } else {
                        loge("**** Default ApnContext not found ****");
                        if (Build.IS_DEBUGGABLE) {
                            throw new RuntimeException("Default ApnContext not found");
                        }
                    }
                }
                break;

            case DctConstants.EVENT_TRY_SETUP_DATA:
                if (msg.obj instanceof ApnContext) {
                    onTrySetupData((ApnContext)msg.obj);
                } else if (msg.obj instanceof String) {
                    onTrySetupData((String)msg.obj);
                } else {
                    loge("EVENT_TRY_SETUP request w/o apnContext or String");
                }
                break;

            case DctConstants.EVENT_CLEAN_UP_CONNECTION:
                boolean tearDown = (msg.arg1 == 0) ? false : true;
                if (DBG) log("EVENT_CLEAN_UP_CONNECTION tearDown=" + tearDown);
                if (msg.obj instanceof ApnContext) {
                    cleanUpConnection(tearDown, (ApnContext)msg.obj);
                } else {
                    loge("EVENT_CLEAN_UP_CONNECTION request w/o apn context, call super");
                    super.handleMessage(msg);
                }
                break;
            case DctConstants.EVENT_SET_INTERNAL_DATA_ENABLE:
                boolean enabled = (msg.arg1 == DctConstants.ENABLED) ? true : false;
                onSetInternalDataEnabled(enabled, (Message) msg.obj);
                break;

            case DctConstants.EVENT_CLEAN_UP_ALL_CONNECTIONS:
                Message mCause = obtainMessage(DctConstants.EVENT_CLEAN_UP_ALL_CONNECTIONS, null);
                if ((msg.obj != null) && (msg.obj instanceof String)) {
                    mCause.obj = msg.obj;
                }
                super.handleMessage(mCause);
                break;

            case DctConstants.EVENT_DATA_RAT_CHANGED:
                //May new Network allow setupData, so try it here
                setupDataOnConnectableApns(Phone.REASON_NW_TYPE_CHANGED);
                break;

            default:
                // handle the message in the super class DataConnectionTracker
                super.handleMessage(msg);
                break;
        }
    }

    protected int getApnProfileID(String apnType) {
        if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_IMS)) {
            return RILConstants.DATA_PROFILE_IMS;
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_FOTA)) {
            return RILConstants.DATA_PROFILE_FOTA;
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_CBS)) {
            return RILConstants.DATA_PROFILE_CBS;
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_IA)) {
            return RILConstants.DATA_PROFILE_DEFAULT; // DEFAULT for now
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_DUN)) {
            return RILConstants.DATA_PROFILE_TETHERED;
        } else {
            return RILConstants.DATA_PROFILE_DEFAULT;
        }
    }

    private int getCellLocationId() {
        int cid = -1;
        CellLocation loc = mPhone.getCellLocation();

        if (loc != null) {
            if (loc instanceof GsmCellLocation) {
                cid = ((GsmCellLocation)loc).getCid();
            } else if (loc instanceof CdmaCellLocation) {
                cid = ((CdmaCellLocation)loc).getBaseStationId();
            }
        }
        return cid;
    }

    private IccRecords getUiccRecords(int appFamily) {
        return mUiccController.getIccRecords(mPhone.getPhoneId(), appFamily);
    }


    @Override
    protected void onUpdateIcc() {
        if (mUiccController == null ) {
            return;
        }

        IccRecords newIccRecords = getUiccRecords(UiccController.APP_FAM_3GPP);

        IccRecords r = mIccRecords.get();
        if (r != newIccRecords) {
            if (r != null) {
                log("Removing stale icc objects.");
                r.unregisterForRecordsLoaded(this);
                mIccRecords.set(null);
            }
            if (newIccRecords != null) {
                log("New records found");
                mIccRecords.set(newIccRecords);
                newIccRecords.registerForRecordsLoaded(
                        this, DctConstants.EVENT_RECORDS_LOADED, null);
            }
        }
    }

    // setAsCurrentDataConnectionTracker
    public void update() {
        log("update sub = " + mPhone.getSubId());
        if (isActiveDataSubscription()) {
            log("update(): Active DDS, register for all events now!");
            registerForAllEvents();
            onUpdateIcc();

            mUserDataEnabled = Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                    Settings.Global.MOBILE_DATA, 1) == 1;

            if (mPhone instanceof CDMALTEPhone) {
                ((CDMALTEPhone)mPhone).updateCurrentCarrierInProvider();
                supplyMessenger();
            } else if (mPhone instanceof GSMPhone) {
                ((GSMPhone)mPhone).updateCurrentCarrierInProvider();
                supplyMessenger();
            } else {
                log("Phone object is not MultiSim. This should not hit!!!!");
            }

        } else {
            unregisterForAllEvents();
            log("update(): NOT the active DDS, unregister for all events!");
        }
    }

    @Override
    public void cleanUpAllConnections(String cause) {
        cleanUpAllConnections(cause, null);
    }

    public void updateRecords() {
        if (isActiveDataSubscription()) {
            onUpdateIcc();
        }
    }

    public void cleanUpAllConnections(String cause, Message disconnectAllCompleteMsg) {
        log("cleanUpAllConnections");
        if (disconnectAllCompleteMsg != null) {
            mDisconnectAllCompleteMsgList.add(disconnectAllCompleteMsg);
        }

        Message msg = obtainMessage(DctConstants.EVENT_CLEAN_UP_ALL_CONNECTIONS);
        msg.obj = cause;
        sendMessage(msg);
    }

    protected void notifyDataDisconnectComplete() {
        log("notifyDataDisconnectComplete");
        for (Message m: mDisconnectAllCompleteMsgList) {
            m.sendToTarget();
        }
        mDisconnectAllCompleteMsgList.clear();
    }


    protected void notifyAllDataDisconnected() {
        sEnableFailFastRefCounter = 0;
        mFailFast = false;
        mAllDataDisconnectedRegistrants.notifyRegistrants();
    }

    public void registerForAllDataDisconnected(Handler h, int what, Object obj) {
        mAllDataDisconnectedRegistrants.addUnique(h, what, obj);

        if (isDisconnected()) {
            log("notify All Data Disconnected");
            notifyAllDataDisconnected();
        }
    }

    public void unregisterForAllDataDisconnected(Handler h) {
        mAllDataDisconnectedRegistrants.remove(h);
    }


    @Override
    protected void onSetInternalDataEnabled(boolean enable) {
        onSetInternalDataEnabled(enable, null);
    }

    protected void onSetInternalDataEnabled(boolean enabled, Message onCompleteMsg) {
        boolean sendOnComplete = true;

        synchronized (mDataEnabledLock) {
            mInternalDataEnabled = enabled;
            if (enabled) {
                log("onSetInternalDataEnabled: changed to enabled, try to setup data call");
                onTrySetupData(Phone.REASON_DATA_ENABLED);
            } else {
                sendOnComplete = false;
                log("onSetInternalDataEnabled: changed to disabled, cleanUpAllConnections");
                cleanUpAllConnections(null, onCompleteMsg);
            }
        }

        if (sendOnComplete) {
            if (onCompleteMsg != null) {
                onCompleteMsg.sendToTarget();
            }
        }
    }

    public boolean setInternalDataEnabledFlag(boolean enable) {
        if (DBG)
            log("setInternalDataEnabledFlag(" + enable + ")");

        if (mInternalDataEnabled != enable) {
            mInternalDataEnabled = enable;
        }
        return true;
    }

    @Override
    public boolean setInternalDataEnabled(boolean enable) {
        return setInternalDataEnabled(enable, null);
    }

    public boolean setInternalDataEnabled(boolean enable, Message onCompleteMsg) {
        if (DBG)
            log("setInternalDataEnabled(" + enable + ")");

        Message msg = obtainMessage(DctConstants.EVENT_SET_INTERNAL_DATA_ENABLE, onCompleteMsg);
        msg.arg1 = (enable ? DctConstants.ENABLED : DctConstants.DISABLED);
        sendMessage(msg);
        return true;
    }

    /** Returns true if this is current DDS. */
    protected boolean isActiveDataSubscription() {
        // FIXME This should have code like
        // return (mPhone.getSubId() == SubscriptionManager.getDefaultDataSubId());
        return true;
    }

    public void setDataAllowed(boolean enable, Message response) {
         mIsCleanupRequired = !enable;
         mPhone.mCi.setDataAllowed(enable, response);
         mInternalDataEnabled = enable;
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[" + mPhone.getPhoneId() + "]" + s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[" + mPhone.getPhoneId() + "]" + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("DataConnectionTracker extends:");
        super.dump(fd, pw, args);
        pw.println(" mReregisterOnReconnectFailure=" + mReregisterOnReconnectFailure);
        pw.println(" canSetPreferApn=" + mCanSetPreferApn);
        pw.println(" mApnObserver=" + mApnObserver);
        pw.println(" getOverallState=" + getOverallState());
        pw.println(" mDataConnectionAsyncChannels=%s\n" + mDataConnectionAcHashMap);
        pw.println(" mAttached=" + mAttached.get());
    }

    @Override
    public String[] getPcscfAddress(String apnType) {
        log("getPcscfAddress()");
        ApnContext apnContext = null;

        if(apnType == null){
            log("apnType is null, return null");
            return null;
        }

        if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_EMERGENCY)) {
            apnContext = mApnContexts.get(PhoneConstants.APN_TYPE_EMERGENCY);
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_IMS)) {
            apnContext = mApnContexts.get(PhoneConstants.APN_TYPE_IMS);
        } else {
            log("apnType is invalid, return null");
            return null;
        }

        if (apnContext == null) {
            log("apnContext is null, return null");
            return null;
        }

        DcAsyncChannel dcac = apnContext.getDcAc();
        String[] result = null;

        if (dcac != null) {
            result = dcac.getPcscfAddr();

            for (int i = 0; i < result.length; i++) {
                log("Pcscf[" + i + "]: " + result[i]);
            }
            return result;
        }
        return null;
    }

    @Override
    public void setImsRegistrationState(boolean registered) {
        log("setImsRegistrationState - mImsRegistrationState(before): "+ mImsRegistrationState
                + ", registered(current) : " + registered);

        if (mPhone == null) return;

        ServiceStateTracker sst = mPhone.getServiceStateTracker();
        if (sst == null) return;

        sst.setImsRegistrationState(registered);
    }

    /**
     * Read APN configuration from Telephony.db for Emergency APN
     * All opertors recognize the connection request for EPDN based on APN type
     * PLMN name,APN name are not mandatory parameters
     */
    private void initEmergencyApnSetting() {
        // Operator Numeric is not available when sim records are not loaded.
        // Query Telephony.db with APN type as EPDN request does not
        // require APN name, plmn and all operators support same APN config.
        // DB will contain only one entry for Emergency APN
        String selection = "type=\"emergency\"";
        Cursor cursor = mPhone.getContext().getContentResolver().query(
                Telephony.Carriers.CONTENT_URI, null, selection, null, null);

        if (cursor != null) {
            if (cursor.getCount() > 0) {
                if (cursor.moveToFirst()) {
                    mEmergencyApn = makeApnSetting(cursor);
                }
            }
            cursor.close();
        }
    }

    /**
     * Add the Emergency APN settings to APN settings list
     */
    private void addEmergencyApnSetting() {
        if(mEmergencyApn != null) {
            if(mAllApnSettings == null) {
                mAllApnSettings = new ArrayList<ApnSetting>();
            } else {
                boolean hasEmergencyApn = false;
                for (ApnSetting apn : mAllApnSettings) {
                    if (ArrayUtils.contains(apn.types, PhoneConstants.APN_TYPE_EMERGENCY)) {
                        hasEmergencyApn = true;
                        break;
                    }
                }

                if(hasEmergencyApn == false) {
                    mAllApnSettings.add(mEmergencyApn);
                } else {
                    log("addEmergencyApnSetting - E-APN setting is already present");
                }
            }
        }
    }
}

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

package com.android.internal.telephony;

import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.os.Looper;
import android.os.ServiceManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.AnomalyReporter;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.LocalLog;

import com.android.internal.os.BackgroundThread;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.dataconnection.TelephonyNetworkFactory;
import com.android.internal.telephony.euicc.EuiccCardController;
import com.android.internal.telephony.euicc.EuiccController;
import com.android.internal.telephony.ims.ImsResolver;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneFactory;
import com.android.internal.telephony.sip.SipPhone;
import com.android.internal.telephony.sip.SipPhoneFactory;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.util.NotificationChannelController;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * {@hide}
 */
public class PhoneFactory {
    static final String LOG_TAG = "PhoneFactory";
    static final int SOCKET_OPEN_RETRY_MILLIS = 2 * 1000;
    static final int SOCKET_OPEN_MAX_RETRY = 3;
    static final boolean DBG = false;

    //***** Class Variables

    // lock sLockProxyPhones protects sPhones, sPhone and sTelephonyNetworkFactories
    final static Object sLockProxyPhones = new Object();
    static private Phone[] sPhones = null;
    static private Phone sPhone = null;

    static private CommandsInterface[] sCommandsInterfaces = null;

    static private ProxyController sProxyController;
    static private UiccController sUiccController;
    private static IntentBroadcaster sIntentBroadcaster;
    private static @Nullable EuiccController sEuiccController;
    private static @Nullable EuiccCardController sEuiccCardController;

    @UnsupportedAppUsage
    static private CommandsInterface sCommandsInterface = null;
    static private SubscriptionInfoUpdater sSubInfoRecordUpdater = null;

    @UnsupportedAppUsage
    static private boolean sMadeDefaults = false;
    @UnsupportedAppUsage
    static private PhoneNotifier sPhoneNotifier;
    @UnsupportedAppUsage
    static private Context sContext;
    static private PhoneConfigurationManager sPhoneConfigurationManager;
    static private PhoneSwitcher sPhoneSwitcher;
    static private SubscriptionMonitor sSubscriptionMonitor;
    static private TelephonyNetworkFactory[] sTelephonyNetworkFactories;
    static private ImsResolver sImsResolver;
    static private NotificationChannelController sNotificationChannelController;
    static private CellularNetworkValidator sCellularNetworkValidator;

    static private final HashMap<String, LocalLog>sLocalLogs = new HashMap<String, LocalLog>();

    //***** Class Methods

    public static void makeDefaultPhones(Context context) {
        makeDefaultPhone(context);
    }

    /**
     * FIXME replace this with some other way of making these
     * instances
     */
    @UnsupportedAppUsage
    public static void makeDefaultPhone(Context context) {
        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                sContext = context;
                // create the telephony device controller.
                TelephonyDevController.create();

                int retryCount = 0;
                for(;;) {
                    boolean hasException = false;
                    retryCount ++;

                    try {
                        // use UNIX domain socket to
                        // prevent subsequent initialization
                        new LocalServerSocket("com.android.internal.telephony");
                    } catch (java.io.IOException ex) {
                        hasException = true;
                    }

                    if ( !hasException ) {
                        break;
                    } else if (retryCount > SOCKET_OPEN_MAX_RETRY) {
                        throw new RuntimeException("PhoneFactory probably already running");
                    } else {
                        try {
                            Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                        } catch (InterruptedException er) {
                        }
                    }
                }

                sPhoneNotifier = new DefaultPhoneNotifier();

                int cdmaSubscription = CdmaSubscriptionSourceManager.getDefault(context);
                Rlog.i(LOG_TAG, "Cdma Subscription set to " + cdmaSubscription);

                /* In case of multi SIM mode two instances of Phone, RIL are created,
                   where as in single SIM mode only instance. isMultiSimEnabled() function checks
                   whether it is single SIM or multi SIM mode */
                TelephonyManager tm = (TelephonyManager) context.getSystemService(
                        Context.TELEPHONY_SERVICE);
                int numPhones = tm.getPhoneCount();

                int[] networkModes = new int[numPhones];
                sPhones = new Phone[numPhones];
                sCommandsInterfaces = new RIL[numPhones];
                sTelephonyNetworkFactories = new TelephonyNetworkFactory[numPhones];

                for (int i = 0; i < numPhones; i++) {
                    // reads the system properties and makes commandsinterface
                    // Get preferred network type.
                    networkModes[i] = RILConstants.PREFERRED_NETWORK_MODE;

                    Rlog.i(LOG_TAG, "Network Mode set to " + Integer.toString(networkModes[i]));
                    sCommandsInterfaces[i] = new RIL(context, networkModes[i],
                            cdmaSubscription, i);
                }

                // Instantiate UiccController so that all other classes can just
                // call getInstance()
                sUiccController = UiccController.make(context, sCommandsInterfaces);

                Rlog.i(LOG_TAG, "Creating SubscriptionController");
                SubscriptionController.init(context, sCommandsInterfaces);
                MultiSimSettingController.init(context, SubscriptionController.getInstance());

                if (context.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_TELEPHONY_EUICC)) {
                    sEuiccController = EuiccController.init(context);
                    sEuiccCardController = EuiccCardController.init(context);
                }

                for (int i = 0; i < numPhones; i++) {
                    Phone phone = null;
                    int phoneType = TelephonyManager.getPhoneType(networkModes[i]);
                    if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                        phone = new GsmCdmaPhone(context,
                                sCommandsInterfaces[i], sPhoneNotifier, i,
                                PhoneConstants.PHONE_TYPE_GSM,
                                TelephonyComponentFactory.getInstance());
                    } else if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                        phone = new GsmCdmaPhone(context,
                                sCommandsInterfaces[i], sPhoneNotifier, i,
                                PhoneConstants.PHONE_TYPE_CDMA_LTE,
                                TelephonyComponentFactory.getInstance());
                    }
                    Rlog.i(LOG_TAG, "Creating Phone with type = " + phoneType + " sub = " + i);

                    sPhones[i] = phone;
                }

                // Set the default phone in base class.
                // FIXME: This is a first best guess at what the defaults will be. It
                // FIXME: needs to be done in a more controlled manner in the future.
                if (numPhones > 0) {
                    sPhone = sPhones[0];
                    sCommandsInterface = sCommandsInterfaces[0];
                }

                // Ensure that we have a default SMS app. Requesting the app with
                // updateIfNeeded set to true is enough to configure a default SMS app.
                ComponentName componentName =
                        SmsApplication.getDefaultSmsApplication(context, true /* updateIfNeeded */);
                String packageName = "NONE";
                if (componentName != null) {
                    packageName = componentName.getPackageName();
                }
                Rlog.i(LOG_TAG, "defaultSmsApplication: " + packageName);

                // Set up monitor to watch for changes to SMS packages
                SmsApplication.initSmsPackageMonitor(context);

                sMadeDefaults = true;

                Rlog.i(LOG_TAG, "Creating SubInfoRecordUpdater ");
                sSubInfoRecordUpdater = new SubscriptionInfoUpdater(
                        BackgroundThread.get().getLooper(), context, sPhones, sCommandsInterfaces);
                SubscriptionController.getInstance().updatePhonesAvailability(sPhones);


                // Only bring up IMS if the device supports having an IMS stack.
                if (context.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_TELEPHONY_IMS)) {
                    // Return whether or not the device should use dynamic binding or the static
                    // implementation (deprecated)
                    boolean isDynamicBinding = sContext.getResources().getBoolean(
                            com.android.internal.R.bool.config_dynamic_bind_ims);
                    // Get the package name of the default IMS implementation.
                    String defaultImsPackage = sContext.getResources().getString(
                            com.android.internal.R.string.config_ims_package);
                    // Start ImsResolver and bind to ImsServices.
                    Rlog.i(LOG_TAG, "ImsResolver: defaultImsPackage: " + defaultImsPackage);
                    sImsResolver = new ImsResolver(sContext, defaultImsPackage, numPhones,
                            isDynamicBinding);
                    sImsResolver.initPopulateCacheAndStartBind();
                    // Start monitoring after defaults have been made.
                    // Default phone must be ready before ImsPhone is created because ImsService
                    // might need it when it is being opened. This should initialize multiple
                    // ImsPhones for ImsResolver implementations of ImsService.
                    for (int i = 0; i < numPhones; i++) {
                        sPhones[i].startMonitoringImsService();
                    }
                } else {
                    Rlog.i(LOG_TAG, "IMS is not supported on this device, skipping ImsResolver.");
                }

                ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(
                        ServiceManager.getService("telephony.registry"));
                SubscriptionController sc = SubscriptionController.getInstance();

                sSubscriptionMonitor = new SubscriptionMonitor(tr, sContext, sc, numPhones);

                sPhoneConfigurationManager = PhoneConfigurationManager.init(sContext);

                sCellularNetworkValidator = CellularNetworkValidator.make(sContext);

                int maxActivePhones = sPhoneConfigurationManager
                        .getNumberOfModemsWithSimultaneousDataConnections();

                sPhoneSwitcher = PhoneSwitcher.make(maxActivePhones, numPhones,
                        sContext, sc, Looper.myLooper(), tr, sCommandsInterfaces,
                        sPhones);

                sProxyController = ProxyController.getInstance(context, sPhones,
                        sUiccController, sCommandsInterfaces, sPhoneSwitcher);

                sIntentBroadcaster = IntentBroadcaster.getInstance(context);

                sNotificationChannelController = new NotificationChannelController(context);

                sTelephonyNetworkFactories = new TelephonyNetworkFactory[numPhones];
                for (int i = 0; i < numPhones; i++) {
                    sTelephonyNetworkFactories[i] = new TelephonyNetworkFactory(
                            sSubscriptionMonitor, Looper.myLooper(), sPhones[i]);
                }
            }
        }
    }

    @UnsupportedAppUsage
    public static Phone getDefaultPhone() {
        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                throw new IllegalStateException("Default phones haven't been made yet!");
            }
            return sPhone;
        }
    }

    @UnsupportedAppUsage
    public static Phone getPhone(int phoneId) {
        Phone phone;
        String dbgInfo = "";

        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                throw new IllegalStateException("Default phones haven't been made yet!");
                // CAF_MSIM FIXME need to introduce default phone id ?
            } else if (phoneId == SubscriptionManager.DEFAULT_PHONE_INDEX) {
                if (DBG) {
                    dbgInfo = "phoneId == DEFAULT_PHONE_ID return sPhone";
                }
                phone = sPhone;
            } else {
                if (DBG) {
                    dbgInfo = "phoneId != DEFAULT_PHONE_ID return sPhones[phoneId]";
                }
                phone = (phoneId >= 0 && phoneId < sPhones.length)
                            ? sPhones[phoneId] : null;
            }
            if (DBG) {
                Rlog.d(LOG_TAG, "getPhone:- " + dbgInfo + " phoneId=" + phoneId +
                        " phone=" + phone);
            }
            return phone;
        }
    }

    @UnsupportedAppUsage
    public static Phone[] getPhones() {
        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                throw new IllegalStateException("Default phones haven't been made yet!");
            }
            return sPhones;
        }
    }

    public static SubscriptionInfoUpdater getSubscriptionInfoUpdater() {
        return sSubInfoRecordUpdater;
    }

    /**
     * @return The ImsResolver instance or null if IMS is not supported
     * (FEATURE_TELEPHONY_IMS is not defined).
     */
    public static @Nullable ImsResolver getImsResolver() {
        return sImsResolver;
    }

    /**
     * Get the network factory associated with a given phone ID.
     * @param phoneId the phone id
     * @return a factory for this phone ID, or null if none.
     */
    public static TelephonyNetworkFactory getNetworkFactory(int phoneId) {
        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                throw new IllegalStateException("Default phones haven't been made yet!");
            }
            final String dbgInfo;
            if (phoneId == SubscriptionManager.DEFAULT_PHONE_INDEX) {
                dbgInfo = "getNetworkFactory with DEFAULT_PHONE_ID => factory for sPhone";
                phoneId = sPhone.getSubId();
            } else {
                dbgInfo = "getNetworkFactory with non-default, return factory for passed id";
            }
            // sTelephonyNetworkFactories is null in tests because in tests makeDefaultPhones()
            // is not called.
            final TelephonyNetworkFactory factory = (sTelephonyNetworkFactories != null
                            && (phoneId >= 0 && phoneId < sTelephonyNetworkFactories.length))
                            ? sTelephonyNetworkFactories[phoneId] : null;
            if (DBG) {
                Rlog.d(LOG_TAG, "getNetworkFactory:-" + dbgInfo + " phoneId=" + phoneId
                        + " factory=" + factory);
            }
            return factory;
        }
    }

    /**
     * Makes a {@link SipPhone} object.
     * @param sipUri the local SIP URI the phone runs on
     * @return the {@code SipPhone} object or null if the SIP URI is not valid
     */
    public static SipPhone makeSipPhone(String sipUri) {
        return SipPhoneFactory.makePhone(sipUri, sContext, sPhoneNotifier);
    }

    /**
     * Returns the preferred network type that should be set in the modem.
     *
     * @param context The current {@link Context}.
     * @return the preferred network mode that should be set.
     */
    // TODO: Fix when we "properly" have TelephonyDevController/SubscriptionController ..
    @UnsupportedAppUsage
    public static int calculatePreferredNetworkType(Context context, int phoneSubId) {
        int networkType = android.provider.Settings.Global.getInt(context.getContentResolver(),
                android.provider.Settings.Global.PREFERRED_NETWORK_MODE + phoneSubId,
                -1 /* invalid network mode */);
        Rlog.d(LOG_TAG, "calculatePreferredNetworkType: phoneSubId = " + phoneSubId +
                " networkType = " + networkType);

        if (networkType == -1) {
            networkType = RILConstants.PREFERRED_NETWORK_MODE;
            try {
                networkType = TelephonyManager.getIntAtIndex(context.getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        SubscriptionController.getInstance().getPhoneId(phoneSubId));
            } catch (SettingNotFoundException retrySnfe) {
                Rlog.e(LOG_TAG, "Settings Exception Reading Value At Index for "
                        + "Settings.Global.PREFERRED_NETWORK_MODE");
            }
        }

        return networkType;
    }

    /* Gets the default subscription */
    @UnsupportedAppUsage
    public static int getDefaultSubscription() {
        return SubscriptionController.getInstance().getDefaultSubId();
    }

    /* Returns User SMS Prompt property,  enabled or not */
    public static boolean isSMSPromptEnabled() {
        boolean prompt = false;
        int value = 0;
        try {
            value = Settings.Global.getInt(sContext.getContentResolver(),
                    Settings.Global.MULTI_SIM_SMS_PROMPT);
        } catch (SettingNotFoundException snfe) {
            Rlog.e(LOG_TAG, "Settings Exception Reading Dual Sim SMS Prompt Values");
        }
        prompt = (value == 0) ? false : true ;
        Rlog.d(LOG_TAG, "SMS Prompt option:" + prompt);

       return prompt;
    }

    /**
     * Makes a {@link ImsPhone} object.
     * @return the {@code ImsPhone} object or null if the exception occured
     */
    public static Phone makeImsPhone(PhoneNotifier phoneNotifier, Phone defaultPhone) {
        return ImsPhoneFactory.makePhone(sContext, phoneNotifier, defaultPhone);
    }

    /**
     * Request a refresh of the embedded subscription list.
     *
     * @param cardId the card ID of the eUICC.
     * @param callback Optional callback to execute after the refresh completes. Must terminate
     *     quickly as it will be called from SubscriptionInfoUpdater's handler thread.
     */
    public static void requestEmbeddedSubscriptionInfoListRefresh(
            int cardId, @Nullable Runnable callback) {
        sSubInfoRecordUpdater.requestEmbeddedSubscriptionInfoListRefresh(cardId, callback);
    }

    /**
     * Get a the SmsController.
     */
    public static SmsController getSmsController() {
        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                throw new IllegalStateException("Default phones haven't been made yet!");
            }
            return sProxyController.getSmsController();
        }
    }

    /**
     * Adds a local log category.
     *
     * Only used within the telephony process.  Use localLog to add log entries.
     *
     * TODO - is there a better way to do this?  Think about design when we have a minute.
     *
     * @param key the name of the category - will be the header in the service dump.
     * @param size the number of lines to maintain in this category
     */
    public static void addLocalLog(String key, int size) {
        synchronized(sLocalLogs) {
            if (sLocalLogs.containsKey(key)) {
                throw new IllegalArgumentException("key " + key + " already present");
            }
            sLocalLogs.put(key, new LocalLog(size));
        }
    }

    /**
     * Add a line to the named Local Log.
     *
     * This will appear in the TelephonyDebugService dump.
     *
     * @param key the name of the log category to put this in.  Must be created
     *            via addLocalLog.
     * @param log the string to add to the log.
     */
    public static void localLog(String key, String log) {
        synchronized(sLocalLogs) {
            if (sLocalLogs.containsKey(key) == false) {
                throw new IllegalArgumentException("key " + key + " not found");
            }
            sLocalLogs.get(key).log(log);
        }
    }

    public static void dump(FileDescriptor fd, PrintWriter printwriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printwriter, "  ");
        pw.println("PhoneFactory:");
        pw.println(" sMadeDefaults=" + sMadeDefaults);

        sPhoneSwitcher.dump(fd, pw, args);
        pw.println();

        Phone[] phones = (Phone[])PhoneFactory.getPhones();
        for (int i = 0; i < phones.length; i++) {
            pw.increaseIndent();
            Phone phone = phones[i];

            try {
                phone.dump(fd, pw, args);
            } catch (Exception e) {
                pw.println("Telephony DebugService: Could not get Phone[" + i + "] e=" + e);
                continue;
            }

            pw.flush();
            pw.println("++++++++++++++++++++++++++++++++");

            sTelephonyNetworkFactories[i].dump(fd, pw, args);

            pw.flush();
            pw.decreaseIndent();
            pw.println("++++++++++++++++++++++++++++++++");
        }

        pw.println("SubscriptionMonitor:");
        pw.increaseIndent();
        try {
            sSubscriptionMonitor.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.decreaseIndent();
        pw.println("++++++++++++++++++++++++++++++++");

        pw.println("UiccController:");
        pw.increaseIndent();
        try {
            sUiccController.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.decreaseIndent();
        pw.println("++++++++++++++++++++++++++++++++");

        if (sEuiccController != null) {
            pw.println("EuiccController:");
            pw.increaseIndent();
            try {
                sEuiccController.dump(fd, pw, args);
                sEuiccCardController.dump(fd, pw, args);
            } catch (Exception e) {
                e.printStackTrace();
            }
            pw.flush();
            pw.decreaseIndent();
            pw.println("++++++++++++++++++++++++++++++++");
        }

        pw.println("SubscriptionController:");
        pw.increaseIndent();
        try {
            SubscriptionController.getInstance().dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.decreaseIndent();
        pw.println("++++++++++++++++++++++++++++++++");

        pw.println("SubInfoRecordUpdater:");
        pw.increaseIndent();
        try {
            sSubInfoRecordUpdater.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.decreaseIndent();
        pw.println("++++++++++++++++++++++++++++++++");

        pw.println("LocalLogs:");
        pw.increaseIndent();
        synchronized (sLocalLogs) {
            for (String key : sLocalLogs.keySet()) {
                pw.println(key);
                pw.increaseIndent();
                sLocalLogs.get(key).dump(fd, pw, args);
                pw.decreaseIndent();
            }
            pw.flush();
        }
        pw.decreaseIndent();
        pw.println("++++++++++++++++++++++++++++++++");

        pw.println("SharedPreferences:");
        pw.increaseIndent();
        try {
            if (sContext != null) {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(sContext);
                Map spValues = sp.getAll();
                for (Object key : spValues.keySet()) {
                    pw.println(key + " : " + spValues.get(key));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.decreaseIndent();
        pw.println("++++++++++++++++++++++++++++++++");
        pw.println("DebugEvents:");
        pw.increaseIndent();
        try {
            AnomalyReporter.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }

        pw.flush();
        pw.decreaseIndent();
    }
}

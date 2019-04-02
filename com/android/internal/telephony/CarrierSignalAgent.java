/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static android.telephony.CarrierConfigManager.KEY_CARRIER_APP_WAKE_SIGNAL_CONFIG_STRING_ARRAY;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_APP_NO_WAKE_SIGNAL_CONFIG_STRING_ARRAY;

/**
 * This class act as an CarrierSignalling Agent.
 * it load registered carrier signalling receivers from carrier config, cache the result to avoid
 * repeated polling and send the intent to the interested receivers.
 * Each CarrierSignalAgent is associated with a phone object.
 */
public class CarrierSignalAgent extends Handler {

    private static final String LOG_TAG = CarrierSignalAgent.class.getSimpleName();
    private static final boolean DBG = true;
    private static final boolean VDBG = Rlog.isLoggable(LOG_TAG, Log.VERBOSE);
    private static final boolean WAKE = true;
    private static final boolean NO_WAKE = false;

    /** delimiters for parsing config of the form: pakName./receiverName : signal1, signal2,..*/
    private static final String COMPONENT_NAME_DELIMITER = "\\s*:\\s*";
    private static final String CARRIER_SIGNAL_DELIMITER = "\\s*,\\s*";

    /** Member variables */
    private final Phone mPhone;
    private boolean mDefaultNetworkAvail;

    /**
     * This is a map of intent action -> set of component name of statically registered
     * carrier signal receivers(wakeup receivers).
     * Those intents are declared in the Manifest files, aiming to wakeup broadcast receivers.
     * Carrier apps should be careful when configuring the wake signal list to avoid unnecessary
     * wakeup. Note we use Set as the entry value to compare config directly regardless of element
     * order.
     * @see CarrierConfigManager#KEY_CARRIER_APP_WAKE_SIGNAL_CONFIG_STRING_ARRAY
     */
    private Map<String, Set<ComponentName>> mCachedWakeSignalConfigs = new HashMap<>();

    /**
     * This is a map of intent action -> set of component name of dynamically registered
     * carrier signal receivers(non-wakeup receivers). Those intents will not wake up the apps.
     * Note Carrier apps should avoid configuring no wake signals in there Manifest files.
     * Note we use Set as the entry value to compare config directly regardless of element order.
     * @see CarrierConfigManager#KEY_CARRIER_APP_NO_WAKE_SIGNAL_CONFIG_STRING_ARRAY
     */
    private Map<String, Set<ComponentName>> mCachedNoWakeSignalConfigs = new HashMap<>();

    private static final int EVENT_REGISTER_DEFAULT_NETWORK_AVAIL = 0;

    /**
     * This is a list of supported signals from CarrierSignalAgent
     */
    private final Set<String> mCarrierSignalList = new HashSet<>(Arrays.asList(
            TelephonyIntents.ACTION_CARRIER_SIGNAL_PCO_VALUE,
            TelephonyIntents.ACTION_CARRIER_SIGNAL_REDIRECTED,
            TelephonyIntents.ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED,
            TelephonyIntents.ACTION_CARRIER_SIGNAL_RESET,
            TelephonyIntents.ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE));

    private final LocalLog mErrorLocalLog = new LocalLog(20);

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DBG) log("CarrierSignalAgent receiver action: " + action);
            if (action.equals(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)) {
                loadCarrierConfig();
            }
        }
    };

    private ConnectivityManager.NetworkCallback mNetworkCallback;

    /** Constructor */
    public CarrierSignalAgent(Phone phone) {
        mPhone = phone;
        loadCarrierConfig();
        // reload configurations on CARRIER_CONFIG_CHANGED
        mPhone.getContext().registerReceiver(mReceiver,
                new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
        mPhone.getCarrierActionAgent().registerForCarrierAction(
                CarrierActionAgent.CARRIER_ACTION_REPORT_DEFAULT_NETWORK_STATUS, this,
                EVENT_REGISTER_DEFAULT_NETWORK_AVAIL, null, false);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_REGISTER_DEFAULT_NETWORK_AVAIL:
                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    Rlog.e(LOG_TAG, "Register default network exception: " + ar.exception);
                    return;
                }
                final ConnectivityManager connectivityMgr =  ConnectivityManager
                        .from(mPhone.getContext());
                if ((boolean) ar.result) {
                    mNetworkCallback = new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            // an optimization to avoid signaling on every default network switch.
                            if (!mDefaultNetworkAvail) {
                                if (DBG) log("Default network available: " + network);
                                Intent intent = new Intent(TelephonyIntents
                                        .ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE);
                                intent.putExtra(
                                        TelephonyIntents.EXTRA_DEFAULT_NETWORK_AVAILABLE_KEY, true);
                                notifyCarrierSignalReceivers(intent);
                                mDefaultNetworkAvail = true;
                            }
                        }
                        @Override
                        public void onLost(Network network) {
                            if (DBG) log("Default network lost: " + network);
                            Intent intent = new Intent(TelephonyIntents
                                    .ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE);
                            intent.putExtra(
                                    TelephonyIntents.EXTRA_DEFAULT_NETWORK_AVAILABLE_KEY, false);
                            notifyCarrierSignalReceivers(intent);
                            mDefaultNetworkAvail = false;
                        }
                    };
                    connectivityMgr.registerDefaultNetworkCallback(mNetworkCallback, mPhone);
                    log("Register default network");

                } else if (mNetworkCallback != null) {
                    connectivityMgr.unregisterNetworkCallback(mNetworkCallback);
                    mNetworkCallback = null;
                    mDefaultNetworkAvail = false;
                    log("unregister default network");
                }
                break;
            default:
                break;
        }
    }

    /**
     * load carrier config and cached the results into a hashMap action -> array list of components.
     */
    private void loadCarrierConfig() {
        CarrierConfigManager configManager = (CarrierConfigManager) mPhone.getContext()
                .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle b = null;
        if (configManager != null) {
            b = configManager.getConfig();
        }
        if (b != null) {
            synchronized (mCachedWakeSignalConfigs) {
                log("Loading carrier config: " + KEY_CARRIER_APP_WAKE_SIGNAL_CONFIG_STRING_ARRAY);
                Map<String, Set<ComponentName>> config = parseAndCache(
                        b.getStringArray(KEY_CARRIER_APP_WAKE_SIGNAL_CONFIG_STRING_ARRAY));
                // In some rare cases, up-to-date config could be fetched with delay and all signals
                // have already been delivered the receivers from the default carrier config.
                // To handle this raciness, we should notify those receivers (from old configs)
                // and reset carrier actions. This should be done before cached Config got purged
                // and written with the up-to-date value, Otherwise those receivers from the
                // old config might lingers without properly clean-up.
                if (!mCachedWakeSignalConfigs.isEmpty()
                        && !config.equals(mCachedWakeSignalConfigs)) {
                    if (VDBG) log("carrier config changed, reset receivers from old config");
                    mPhone.getCarrierActionAgent().sendEmptyMessage(
                            CarrierActionAgent.CARRIER_ACTION_RESET);
                }
                mCachedWakeSignalConfigs = config;
            }

            synchronized (mCachedNoWakeSignalConfigs) {
                log("Loading carrier config: "
                        + KEY_CARRIER_APP_NO_WAKE_SIGNAL_CONFIG_STRING_ARRAY);
                Map<String, Set<ComponentName>> config = parseAndCache(
                        b.getStringArray(KEY_CARRIER_APP_NO_WAKE_SIGNAL_CONFIG_STRING_ARRAY));
                if (!mCachedNoWakeSignalConfigs.isEmpty()
                        && !config.equals(mCachedNoWakeSignalConfigs)) {
                    if (VDBG) log("carrier config changed, reset receivers from old config");
                    mPhone.getCarrierActionAgent().sendEmptyMessage(
                            CarrierActionAgent.CARRIER_ACTION_RESET);
                }
                mCachedNoWakeSignalConfigs = config;
            }
        }
    }

    /**
     * Parse each config with the form {pakName./receiverName : signal1, signal2,.} and cached the
     * result internally to avoid repeated polling
     * @see #CARRIER_SIGNAL_DELIMITER
     * @see #COMPONENT_NAME_DELIMITER
     * @param configs raw information from carrier config
     */
    private Map<String, Set<ComponentName>> parseAndCache(String[] configs) {
        Map<String, Set<ComponentName>> newCachedWakeSignalConfigs = new HashMap<>();
        if (!ArrayUtils.isEmpty(configs)) {
            for (String config : configs) {
                if (!TextUtils.isEmpty(config)) {
                    String[] splitStr = config.trim().split(COMPONENT_NAME_DELIMITER, 2);
                    if (splitStr.length == 2) {
                        ComponentName componentName = ComponentName
                                .unflattenFromString(splitStr[0]);
                        if (componentName == null) {
                            loge("Invalid component name: " + splitStr[0]);
                            continue;
                        }
                        String[] signals = splitStr[1].split(CARRIER_SIGNAL_DELIMITER);
                        for (String s : signals) {
                            if (!mCarrierSignalList.contains(s)) {
                                loge("Invalid signal name: " + s);
                                continue;
                            }
                            Set<ComponentName> componentList = newCachedWakeSignalConfigs.get(s);
                            if (componentList == null) {
                                componentList = new HashSet<>();
                                newCachedWakeSignalConfigs.put(s, componentList);
                            }
                            componentList.add(componentName);
                            if (VDBG) {
                                logv("Add config " + "{signal: " + s
                                        + " componentName: " + componentName + "}");
                            }
                        }
                    } else {
                        loge("invalid config format: " + config);
                    }
                }
            }
        }
        return newCachedWakeSignalConfigs;
    }

    /**
     * Check if there are registered carrier broadcast receivers to handle the passing intent
     */
    public boolean hasRegisteredReceivers(String action) {
        return mCachedWakeSignalConfigs.containsKey(action)
                || mCachedNoWakeSignalConfigs.containsKey(action);
    }

    /**
     * Broadcast the intents explicitly.
     * Some sanity check will be applied before broadcasting.
     * - for non-wakeup(runtime) receivers, make sure the intent is not declared in their manifests
     * and apply FLAG_EXCLUDE_STOPPED_PACKAGES to avoid wake-up
     * - for wakeup(manifest) receivers, make sure there are matched receivers with registered
     * intents.
     *
     * @param intent intent which signals carrier apps
     * @param receivers a list of component name for broadcast receivers.
     *                  Those receivers could either be statically declared in Manifest or
     *                  registered during run-time.
     * @param wakeup true indicate wakeup receivers otherwise non-wakeup receivers
     */
    private void broadcast(Intent intent, Set<ComponentName> receivers, boolean wakeup) {
        final PackageManager packageManager = mPhone.getContext().getPackageManager();
        for (ComponentName name : receivers) {
            Intent signal = new Intent(intent);
            signal.setComponent(name);

            if (wakeup && packageManager.queryBroadcastReceivers(signal,
                    PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                loge("Carrier signal receivers are configured but unavailable: "
                        + signal.getComponent());
                return;
            }
            if (!wakeup && !packageManager.queryBroadcastReceivers(signal,
                    PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                loge("Runtime signals shouldn't be configured in Manifest: "
                        + signal.getComponent());
                return;
            }

            signal.putExtra(PhoneConstants.SUBSCRIPTION_KEY, mPhone.getSubId());
            signal.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            if (!wakeup) signal.setFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);

            try {
                mPhone.getContext().sendBroadcast(signal);
                if (DBG) {
                    log("Sending signal " + signal.getAction() + ((signal.getComponent() != null)
                            ? " to the carrier signal receiver: " + signal.getComponent() : ""));
                }
            } catch (ActivityNotFoundException e) {
                loge("Send broadcast failed: " + e);
            }
        }
    }

    /**
     * Match the intent against cached tables to find a list of registered carrier signal
     * receivers and broadcast the intent.
     * @param intent broadcasting intent, it could belong to wakeup, non-wakeup signal list or both
     *
     */
    public void notifyCarrierSignalReceivers(Intent intent) {
        Set<ComponentName> receiverSet;

        synchronized (mCachedWakeSignalConfigs) {
            receiverSet = mCachedWakeSignalConfigs.get(intent.getAction());
            if (!ArrayUtils.isEmpty(receiverSet)) {
                broadcast(intent, receiverSet, WAKE);
            }
        }

        synchronized (mCachedNoWakeSignalConfigs) {
            receiverSet = mCachedNoWakeSignalConfigs.get(intent.getAction());
            if (!ArrayUtils.isEmpty(receiverSet)) {
                broadcast(intent, receiverSet, NO_WAKE);
            }
        }
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, "[" + mPhone.getPhoneId() + "]" + s);
    }

    private void loge(String s) {
        mErrorLocalLog.log(s);
        Rlog.e(LOG_TAG, "[" + mPhone.getPhoneId() + "]" + s);
    }

    private void logv(String s) {
        Rlog.v(LOG_TAG, "[" + mPhone.getPhoneId() + "]" + s);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        pw.println("mCachedWakeSignalConfigs:");
        ipw.increaseIndent();
        for (Map.Entry<String, Set<ComponentName>> entry : mCachedWakeSignalConfigs.entrySet()) {
            pw.println("signal: " + entry.getKey() + " componentName list: " + entry.getValue());
        }
        ipw.decreaseIndent();

        pw.println("mCachedNoWakeSignalConfigs:");
        ipw.increaseIndent();
        for (Map.Entry<String, Set<ComponentName>> entry : mCachedNoWakeSignalConfigs.entrySet()) {
            pw.println("signal: " + entry.getKey() + " componentName list: " + entry.getValue());
        }
        ipw.decreaseIndent();

        pw.println("mDefaultNetworkAvail: " + mDefaultNetworkAvail);

        pw.println("error log:");
        ipw.increaseIndent();
        mErrorLocalLog.dump(fd, pw, args);
        ipw.decreaseIndent();
    }
}

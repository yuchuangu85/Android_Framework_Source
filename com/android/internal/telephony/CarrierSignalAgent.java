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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
public class CarrierSignalAgent {

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

    /**
     * This is a map of intent action -> array list of component name of statically registered
     * carrier signal receivers(wakeup receivers).
     * Those intents are declared in the Manifest files, aiming to wakeup broadcast receivers.
     * Carrier apps should be careful when configuring the wake signal list to avoid unnecessary
     * wakeup.
     * @see CarrierConfigManager#KEY_CARRIER_APP_WAKE_SIGNAL_CONFIG_STRING_ARRAY
     */
    private final Map<String, List<ComponentName>> mCachedWakeSignalConfigs = new HashMap<>();

    /**
     * This is a map of intent action -> array list of component name of dynamically registered
     * carrier signal receivers(non-wakeup receivers). Those intents will not wake up the apps.
     * Note Carrier apps should avoid configuring no wake signals in there Manifest files.
     * @see CarrierConfigManager#KEY_CARRIER_APP_NO_WAKE_SIGNAL_CONFIG_STRING_ARRAY
     */
    private final Map<String, List<ComponentName>> mCachedNoWakeSignalConfigs = new HashMap<>();

    /**
     * This is a list of supported signals from CarrierSignalAgent
     */
    private final Set<String> mCarrierSignalList = new HashSet<>(Arrays.asList(
            TelephonyIntents.ACTION_CARRIER_SIGNAL_PCO_VALUE,
            TelephonyIntents.ACTION_CARRIER_SIGNAL_REDIRECTED,
            TelephonyIntents.ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED,
            TelephonyIntents.ACTION_CARRIER_SIGNAL_RESET));

    private final LocalLog mErrorLocalLog = new LocalLog(20);

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DBG) log("CarrierSignalAgent receiver action: " + action);
            if (action.equals(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)) {
                // notify carrier apps before cache get purged
                if (mPhone.getIccCard() != null
                        && IccCardConstants.State.ABSENT == mPhone.getIccCard().getState()) {
                    notifyCarrierSignalReceivers(
                            new Intent(TelephonyIntents.ACTION_CARRIER_SIGNAL_RESET));
                }
                loadCarrierConfig();
            }
        }
    };

    /** Constructor */
    public CarrierSignalAgent(Phone phone) {
        mPhone = phone;
        loadCarrierConfig();
        // reload configurations on CARRIER_CONFIG_CHANGED
        mPhone.getContext().registerReceiver(mReceiver,
                new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
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
                mCachedWakeSignalConfigs.clear();
                log("Loading carrier config: " + KEY_CARRIER_APP_WAKE_SIGNAL_CONFIG_STRING_ARRAY);
                parseAndCache(b.getStringArray(KEY_CARRIER_APP_WAKE_SIGNAL_CONFIG_STRING_ARRAY),
                        mCachedWakeSignalConfigs);
            }

            synchronized (mCachedNoWakeSignalConfigs) {
                mCachedNoWakeSignalConfigs.clear();
                log("Loading carrier config: "
                        + KEY_CARRIER_APP_NO_WAKE_SIGNAL_CONFIG_STRING_ARRAY);
                parseAndCache(b.getStringArray(KEY_CARRIER_APP_NO_WAKE_SIGNAL_CONFIG_STRING_ARRAY),
                        mCachedNoWakeSignalConfigs);
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
    private void parseAndCache(String[] configs,
                               Map<String, List<ComponentName>> cachedConfigs) {
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
                            List<ComponentName> componentList = cachedConfigs.get(s);
                            if (componentList == null) {
                                componentList = new ArrayList<>();
                                cachedConfigs.put(s, componentList);
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
    private void broadcast(Intent intent, List<ComponentName> receivers, boolean wakeup) {
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
        List<ComponentName> receiverList;

        synchronized (mCachedWakeSignalConfigs) {
            receiverList = mCachedWakeSignalConfigs.get(intent.getAction());
            if (!ArrayUtils.isEmpty(receiverList)) {
                broadcast(intent, receiverList, WAKE);
            }
        }

        synchronized (mCachedNoWakeSignalConfigs) {
            receiverList = mCachedNoWakeSignalConfigs.get(intent.getAction());
            if (!ArrayUtils.isEmpty(receiverList)) {
                broadcast(intent, receiverList, NO_WAKE);
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
        for (Map.Entry<String, List<ComponentName>> entry : mCachedWakeSignalConfigs.entrySet()) {
            pw.println("signal: " + entry.getKey() + " componentName list: " + entry.getValue());
        }
        ipw.decreaseIndent();

        pw.println("mCachedNoWakeSignalConfigs:");
        ipw.increaseIndent();
        for (Map.Entry<String, List<ComponentName>> entry : mCachedNoWakeSignalConfigs.entrySet()) {
            pw.println("signal: " + entry.getKey() + " componentName list: " + entry.getValue());
        }
        ipw.decreaseIndent();

        pw.println("error log:");
        ipw.increaseIndent();
        mErrorLocalLog.dump(fd, pw, args);
        ipw.decreaseIndent();
    }
}

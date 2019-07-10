/*
* Copyright (C) 2015 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.service.carrier.CarrierService;

import com.android.internal.telephony.IccCardConstants;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Manages long-lived bindings to carrier services
 * @hide
 */
public class CarrierServiceBindHelper {
    private static final String LOG_TAG = CarrierServiceBindHelper.class.getSimpleName();

    private Context mContext;
    private AppBinding[] mBindings;
    private final BroadcastReceiver mReceiver = new PackageChangedBroadcastReceiver();

    private static final int EVENT_BIND = 0;
    private static final int EVENT_UNBIND = 1;
    private static final int EVENT_BIND_TIMEOUT = 2;
    private static final int EVENT_PACKAGE_CHANGED = 3;

    private static final int BIND_TIMEOUT_MILLIS = 10000;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            String carrierPackageName;
            AppBinding binding;
            log("mHandler: " + msg.what);

            CarrierServiceConnection connection;
            switch (msg.what) {
                case EVENT_BIND:
                    binding = (AppBinding) msg.obj;
                    log("Binding to phoneId: " + binding.getPhoneId());
                    binding.bind();
                    break;
                case EVENT_BIND_TIMEOUT:
                    binding = (AppBinding) msg.obj;
                    log("Bind timeout for phoneId: " + binding.getPhoneId());
                    binding.unbind();
                    break;
                case EVENT_UNBIND:
                    binding = (AppBinding) msg.obj;
                    log("Unbinding for phoneId: " + binding.getPhoneId());
                    binding.unbind();
                    break;
                case EVENT_PACKAGE_CHANGED:
                    carrierPackageName = (String) msg.obj;
                    for (AppBinding appBinding : mBindings) {
                        if (carrierPackageName.equals(appBinding.getPackage())) {
                          log(carrierPackageName + " changed and corresponds to a phone. Rebinding.");
                          appBinding.bind();
                        }
                    }
                    break;
            }
        }
    };

    public CarrierServiceBindHelper(Context context) {
        mContext = context;

        int numPhones = TelephonyManager.from(context).getPhoneCount();
        mBindings = new AppBinding[numPhones];

        for (int phoneId = 0; phoneId < numPhones; phoneId++) {
            mBindings[phoneId] = new AppBinding(phoneId);
        }

        // Register for package updates. Update app or uninstall app update will have all 3 intents,
        // in the order or removed, added, replaced, all with extra_replace set to true.
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addDataScheme("package");
        context.registerReceiverAsUser(mReceiver, UserHandle.ALL, pkgFilter, null, null);
    }

    public void updateForPhoneId(int phoneId, String simState) {
        log("update binding for phoneId: " + phoneId + " simState: " + simState);
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            return;
        }
        // requires Java 7 for switch on string.
        switch (simState) {
            case IccCardConstants.INTENT_VALUE_ICC_ABSENT:
            case IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR:
            case IccCardConstants.INTENT_VALUE_ICC_UNKNOWN:
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_UNBIND, mBindings[phoneId]));
                break;
            case IccCardConstants.INTENT_VALUE_ICC_LOADED:
            case IccCardConstants.INTENT_VALUE_ICC_LOCKED:
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_BIND, mBindings[phoneId]));
                break;
        }
    }

    private class AppBinding {
        private int phoneId;
        private CarrierServiceConnection connection;
        private int bindCount;
        private long lastBindStartMillis;
        private int unbindCount;
        private long lastUnbindMillis;
        private String carrierPackage;

        public AppBinding(int phoneId) {
            this.phoneId = phoneId;
        }

        public int getPhoneId() {
            return phoneId;
        }

        public String getPackage() {
            return carrierPackage;
        }

        public void handleConnectionDown() {
            connection = null;
        }

        public boolean bind() {
            // Make sure there is no existing binding for this phone
            unbind();

            // Get the package name for the carrier app
            List<String> carrierPackageNames =
                TelephonyManager.from(mContext).getCarrierPackageNamesForIntentAndPhone(
                    new Intent(CarrierService.CARRIER_SERVICE_INTERFACE), phoneId
                );

            if (carrierPackageNames == null || carrierPackageNames.size() <= 0) {
                log("No carrier app for: " + phoneId);
                return false;
            }

            log("Found carrier app: " + carrierPackageNames);
            carrierPackage = carrierPackageNames.get(0);

            // Log debug information
            bindCount++;
            lastBindStartMillis = System.currentTimeMillis();

            // Look up the carrier service
            Intent carrierService = new Intent(CarrierService.CARRIER_SERVICE_INTERFACE);
            carrierService.setPackage(carrierPackage);

            ResolveInfo carrierResolveInfo = mContext.getPackageManager().resolveService(
                carrierService, PackageManager.GET_META_DATA);
            Bundle metadata = carrierResolveInfo.serviceInfo.metaData;

            // Only bind if the service wants it
            if (metadata == null ||
                !metadata.getBoolean("android.service.carrier.LONG_LIVED_BINDING", false)) {
                log("Carrier app does not want a long lived binding");
                return false;
            }

            log("Binding to " + carrierPackage + " for phone " + phoneId);
            connection = new CarrierServiceConnection(this);
            mHandler.sendMessageDelayed(
                mHandler.obtainMessage(EVENT_BIND_TIMEOUT, this),
                BIND_TIMEOUT_MILLIS);

            String error;
            try {
                if (mContext.bindService(carrierService, connection, Context.BIND_AUTO_CREATE)) {
                    return true;
                }

                error = "bindService returned false";
            } catch (SecurityException ex) {
                error = ex.getMessage();
            }

            log("Unable to bind to " + carrierPackage + " for phone " + phoneId +
                ". Error: " + error);
            return false;
        }

        public void unbind() {
            mHandler.removeMessages(EVENT_BIND_TIMEOUT, this);
            if (connection == null) {
                return;
            }

            // Log debug information
            unbindCount++;
            lastUnbindMillis = System.currentTimeMillis();

            // Actually unbind
            log("Unbinding from carrier app");
            mContext.unbindService(connection);
            connection = null;
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("Carrier app binding for phone " + phoneId);
            pw.println("  connection: " + connection);
            pw.println("  bindCount: " + bindCount);
            pw.println("  lastBindStartMillis: " + lastBindStartMillis);
            pw.println("  unbindCount: " + unbindCount);
            pw.println("  lastUnbindMillis: " + lastUnbindMillis);
            pw.println();
        }
    }

    private class CarrierServiceConnection implements ServiceConnection {
        private IBinder service;
        private AppBinding binding;

        public CarrierServiceConnection(AppBinding binding) {
            this.binding = binding;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            log("Connected to carrier app: " + name.flattenToString());
            mHandler.removeMessages(EVENT_BIND_TIMEOUT, binding);
            this.service = service;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            log("Disconnected from carrier app: " + name.flattenToString());
            this.service = null;
            this.binding.handleConnectionDown();
        }
    }

    private class PackageChangedBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("Receive action: " + action);
            switch (action) {
                case Intent.ACTION_PACKAGE_ADDED:
                case Intent.ACTION_PACKAGE_REMOVED:
                case Intent.ACTION_PACKAGE_REPLACED:
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    String packageName = mContext.getPackageManager().getNameForUid(uid);
                    if (packageName != null) {
                      // We don't have a phoneId for arg1.
                      mHandler.sendMessage(
                              mHandler.obtainMessage(EVENT_PACKAGE_CHANGED, packageName));
                    }
                    break;

            }
        }
    }

    private static void log(String message) {
        Log.d(LOG_TAG, message);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("CarrierServiceBindHelper:");
        for (AppBinding binding : mBindings) {
            binding.dump(fd, pw, args);
        }
    }
}

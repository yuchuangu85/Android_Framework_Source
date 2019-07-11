/*
 * Copyright 2018 The Android Open Source Project
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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.CarrierConfigManager;
import android.telephony.INetworkService;
import android.telephony.INetworkServiceCallback;
import android.telephony.NetworkRegistrationState;
import android.telephony.NetworkService;
import android.telephony.Rlog;

import java.util.Hashtable;
import java.util.Map;

/**
 * Class that serves as the layer between NetworkService and ServiceStateTracker. It helps binding,
 * sending request and registering for state change to NetworkService.
 */
public class NetworkRegistrationManager {
    private static final String TAG = NetworkRegistrationManager.class.getSimpleName();

    private final int mTransportType;

    private final Phone mPhone;

    private final CarrierConfigManager mCarrierConfigManager;

    // Registrants who listens registration state change callback from this class.
    private final RegistrantList mRegStateChangeRegistrants = new RegistrantList();

    private INetworkService.Stub mServiceBinder;

    private RegManagerDeathRecipient mDeathRecipient;

    public NetworkRegistrationManager(int transportType, Phone phone) {
        mTransportType = transportType;
        mPhone = phone;
        mCarrierConfigManager = (CarrierConfigManager) phone.getContext().getSystemService(
                Context.CARRIER_CONFIG_SERVICE);

        bindService();
    }

    public boolean isServiceConnected() {
        return (mServiceBinder != null) && (mServiceBinder.isBinderAlive());
    }

    public void unregisterForNetworkRegistrationStateChanged(Handler h) {
        mRegStateChangeRegistrants.remove(h);
    }

    public void registerForNetworkRegistrationStateChanged(Handler h, int what, Object obj) {
        logd("registerForNetworkRegistrationStateChanged");
        Registrant r = new Registrant(h, what, obj);
        mRegStateChangeRegistrants.addUnique(h, what, obj);
    }

    private final Map<NetworkRegStateCallback, Message> mCallbackTable = new Hashtable();

    public void getNetworkRegistrationState(int domain, Message onCompleteMessage) {
        if (onCompleteMessage == null) return;

        logd("getNetworkRegistrationState domain " + domain);
        if (!isServiceConnected()) {
            logd("service not connected.");
            onCompleteMessage.obj = new AsyncResult(onCompleteMessage.obj, null,
                    new IllegalStateException("Service not connected."));
            onCompleteMessage.sendToTarget();
            return;
        }

        NetworkRegStateCallback callback = new NetworkRegStateCallback();
        try {
            mCallbackTable.put(callback, onCompleteMessage);
            mServiceBinder.getNetworkRegistrationState(mPhone.getPhoneId(), domain, callback);
        } catch (RemoteException e) {
            Rlog.e(TAG, "getNetworkRegistrationState RemoteException " + e);
            mCallbackTable.remove(callback);
            onCompleteMessage.obj = new AsyncResult(onCompleteMessage.obj, null, e);
            onCompleteMessage.sendToTarget();
        }
    }

    private class RegManagerDeathRecipient implements IBinder.DeathRecipient {

        private final ComponentName mComponentName;

        RegManagerDeathRecipient(ComponentName name) {
            mComponentName = name;
        }

        @Override
        public void binderDied() {
            // TODO: try to restart the service.
            logd("NetworkService(" + mComponentName +  " transport type "
                    + mTransportType + ") died.");
        }
    }

    private class NetworkServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            logd("service connected.");
            mServiceBinder = (INetworkService.Stub) service;
            mDeathRecipient = new RegManagerDeathRecipient(name);
            try {
                mServiceBinder.linkToDeath(mDeathRecipient, 0);
                mServiceBinder.createNetworkServiceProvider(mPhone.getPhoneId());
                mServiceBinder.registerForNetworkRegistrationStateChanged(mPhone.getPhoneId(),
                        new NetworkRegStateCallback());
            } catch (RemoteException exception) {
                // Remote exception means that the binder already died.
                mDeathRecipient.binderDied();
                logd("RemoteException " + exception);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            logd("onServiceDisconnected " + name);
            if (mServiceBinder != null) {
                mServiceBinder.unlinkToDeath(mDeathRecipient, 0);
            }
        }
    }

    private class NetworkRegStateCallback extends INetworkServiceCallback.Stub {
        @Override
        public void onGetNetworkRegistrationStateComplete(
                int result, NetworkRegistrationState state) {
            logd("onGetNetworkRegistrationStateComplete result "
                    + result + " state " + state);
            Message onCompleteMessage = mCallbackTable.remove(this);
            if (onCompleteMessage != null) {
                onCompleteMessage.arg1 = result;
                onCompleteMessage.obj = new AsyncResult(onCompleteMessage.obj, state, null);
                onCompleteMessage.sendToTarget();
            } else {
                loge("onCompleteMessage is null");
            }
        }

        @Override
        public void onNetworkStateChanged() {
            logd("onNetworkStateChanged");
            mRegStateChangeRegistrants.notifyRegistrants();
        }
    }

    private boolean bindService() {
        Intent intent = new Intent(NetworkService.NETWORK_SERVICE_INTERFACE);
        intent.setPackage(getPackageName());
        try {
            // We bind this as a foreground service because it is operating directly on the SIM,
            // and we do not want it subjected to power-savings restrictions while doing so.
            return mPhone.getContext().bindService(intent, new NetworkServiceConnection(),
                    Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            loge("bindService failed " + e);
            return false;
        }
    }

    private String getPackageName() {
        String packageName;
        int resourceId;
        String carrierConfig;

        switch (mTransportType) {
            case TransportType.WWAN:
                resourceId = com.android.internal.R.string.config_wwan_network_service_package;
                carrierConfig = CarrierConfigManager
                        .KEY_CARRIER_NETWORK_SERVICE_WWAN_PACKAGE_OVERRIDE_STRING;
                break;
            case TransportType.WLAN:
                resourceId = com.android.internal.R.string.config_wlan_network_service_package;
                carrierConfig = CarrierConfigManager
                        .KEY_CARRIER_NETWORK_SERVICE_WLAN_PACKAGE_OVERRIDE_STRING;
                break;
            default:
                throw new IllegalStateException("Transport type not WWAN or WLAN. type="
                        + mTransportType);
        }

        // Read package name from resource overlay
        packageName = mPhone.getContext().getResources().getString(resourceId);

        PersistableBundle b = mCarrierConfigManager.getConfigForSubId(mPhone.getSubId());

        if (b != null) {
            // If carrier config overrides it, use the one from carrier config
            packageName = b.getString(carrierConfig, packageName);
        }

        logd("Binding to packageName " + packageName + " for transport type"
                + mTransportType);

        return packageName;
    }

    private static int logd(String msg) {
        return Rlog.d(TAG, msg);
    }

    private static int loge(String msg) {
        return Rlog.e(TAG, msg);
    }
}

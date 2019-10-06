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

package com.android.internal.telephony.dataconnection;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.data.ApnSetting;
import android.telephony.data.ApnSetting.ApnType;
import android.telephony.data.IQualifiedNetworksService;
import android.telephony.data.IQualifiedNetworksServiceCallback;
import android.telephony.data.QualifiedNetworksService;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.telephony.Phone;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Access network manager manages the qualified/available networks for mobile data connection.
 * It binds to the vendor's qualified networks service and actively monitors the qualified
 * networks changes.
 */
public class AccessNetworksManager extends Handler {
    private static final String TAG = AccessNetworksManager.class.getSimpleName();
    private static final boolean DBG = false;

    private static final int[] SUPPORTED_APN_TYPES = {
            ApnSetting.TYPE_DEFAULT,
            ApnSetting.TYPE_MMS,
            ApnSetting.TYPE_FOTA,
            ApnSetting.TYPE_IMS,
            ApnSetting.TYPE_CBS,
            ApnSetting.TYPE_SUPL,
            ApnSetting.TYPE_EMERGENCY
    };

    private static final int EVENT_BIND_QUALIFIED_NETWORKS_SERVICE = 1;

    private final Phone mPhone;

    private final CarrierConfigManager mCarrierConfigManager;

    private IQualifiedNetworksService mIQualifiedNetworksService;

    private AccessNetworksManagerDeathRecipient mDeathRecipient;

    private String mTargetBindingPackageName;

    private QualifiedNetworksServiceConnection mServiceConnection;

    // Available networks. Key is the APN type.
    private final SparseArray<int[]> mAvailableNetworks = new SparseArray<>();

    private final RegistrantList mQualifiedNetworksChangedRegistrants = new RegistrantList();

    private final BroadcastReceiver mConfigChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(action)
                    && mPhone.getPhoneId() == intent.getIntExtra(
                    CarrierConfigManager.EXTRA_SLOT_INDEX, 0)) {
                // We should wait for carrier config changed event because the target binding
                // package name can come from the carrier config. Note that we still get this event
                // even when SIM is absent.
                if (DBG) log("Carrier config changed. Try to bind qualified network service.");
                sendEmptyMessage(EVENT_BIND_QUALIFIED_NETWORKS_SERVICE);
            }
        }
    };

    /**
     * Represents qualified network types list on a specific APN type.
     */
    public static class QualifiedNetworks {
        public final @ApnType int apnType;
        // The qualified networks in preferred order. Each network is a AccessNetworkType.
        public final int[] qualifiedNetworks;
        public QualifiedNetworks(@ApnType int apnType, int[] qualifiedNetworks) {
            this.apnType = apnType;
            this.qualifiedNetworks = qualifiedNetworks;
        }

        @Override
        public String toString() {
            List<String> accessNetworkStrings = new ArrayList<>();
            for (int network : qualifiedNetworks) {
                accessNetworkStrings.add(AccessNetworkType.toString(network));
            }
            return "[QualifiedNetworks: apnType="
                    + ApnSetting.getApnTypeString(apnType)
                    + ", networks="
                    + Arrays.stream(qualifiedNetworks)
                    .mapToObj(type -> AccessNetworkType.toString(type))
                    .collect(Collectors.joining(","))
                    + "]";
        }
    }

    private class AccessNetworksManagerDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            // TODO: try to rebind the service.
            loge("QualifiedNetworksService(" + mTargetBindingPackageName + ") died.");
        }
    }

    private final class QualifiedNetworksServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DBG) log("onServiceConnected " + name);
            mIQualifiedNetworksService = IQualifiedNetworksService.Stub.asInterface(service);
            mDeathRecipient = new AccessNetworksManagerDeathRecipient();

            try {
                service.linkToDeath(mDeathRecipient, 0 /* flags */);
                mIQualifiedNetworksService.createNetworkAvailabilityProvider(mPhone.getPhoneId(),
                        new QualifiedNetworksServiceCallback());
            } catch (RemoteException e) {
                mDeathRecipient.binderDied();
                loge("Remote exception. " + e);
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DBG) log("onServiceDisconnected " + name);
            mIQualifiedNetworksService.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mTargetBindingPackageName = null;
        }
    }

    private final class QualifiedNetworksServiceCallback extends
            IQualifiedNetworksServiceCallback.Stub {
        @Override
        public void onQualifiedNetworkTypesChanged(int apnTypes, int[] qualifiedNetworkTypes) {
            log("onQualifiedNetworkTypesChanged. apnTypes = ["
                    + ApnSetting.getApnTypesStringFromBitmask(apnTypes)
                    + "], networks = [" + Arrays.stream(qualifiedNetworkTypes)
                    .mapToObj(i -> AccessNetworkType.toString(i)).collect(Collectors.joining(","))
                    + "]");
            List<QualifiedNetworks> qualifiedNetworksList = new ArrayList<>();
            for (int supportedApnType : SUPPORTED_APN_TYPES) {
                if ((apnTypes & supportedApnType) == supportedApnType) {
                    // TODO: Verify the preference from data settings manager to make sure the order
                    // of the networks do not violate users/carrier's preference.
                    if (mAvailableNetworks.get(supportedApnType) != null) {
                        if (Arrays.equals(mAvailableNetworks.get(supportedApnType),
                                qualifiedNetworkTypes)) {
                            log("Available networks for "
                                    + ApnSetting.getApnTypesStringFromBitmask(supportedApnType)
                                    + " not changed.");
                            continue;
                        }
                    }
                    mAvailableNetworks.put(supportedApnType, qualifiedNetworkTypes);
                    qualifiedNetworksList.add(new QualifiedNetworks(supportedApnType,
                            qualifiedNetworkTypes));
                }
            }

            if (!qualifiedNetworksList.isEmpty()) {
                mQualifiedNetworksChangedRegistrants.notifyResult(qualifiedNetworksList);
            }
        }
    }

    /**
     * Constructor
     *
     * @param phone The phone object
     */
    public AccessNetworksManager(Phone phone) {
        mPhone = phone;
        mCarrierConfigManager = (CarrierConfigManager) phone.getContext().getSystemService(
                Context.CARRIER_CONFIG_SERVICE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        phone.getContext().registerReceiverAsUser(mConfigChangedReceiver, UserHandle.ALL,
                intentFilter, null, null);
        sendEmptyMessage(EVENT_BIND_QUALIFIED_NETWORKS_SERVICE);
    }

    /**
     * Handle message events
     *
     * @param msg The message to handle
     */
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_BIND_QUALIFIED_NETWORKS_SERVICE:
                bindQualifiedNetworksService();
                break;
            default:
                loge("Unhandled event " + msg.what);
        }
    }

    /**
     * Find the qualified network service from configuration and binds to it. It reads the
     * configuration from carrier config if it exists. If not, read it from resources.
     */
    private void bindQualifiedNetworksService() {
        String packageName = getQualifiedNetworksServicePackageName();

        if (DBG) log("Qualified network service package = " + packageName);
        if (TextUtils.isEmpty(packageName)) {
            loge("Can't find the binding package");
            return;
        }

        if (TextUtils.equals(packageName, mTargetBindingPackageName)) {
            if (DBG) log("Service " + packageName + " already bound or being bound.");
            return;
        }

        if (mIQualifiedNetworksService != null
                && mIQualifiedNetworksService.asBinder().isBinderAlive()) {
            // Remove the network availability updater and then unbind the service.
            try {
                mIQualifiedNetworksService.removeNetworkAvailabilityProvider(mPhone.getPhoneId());
            } catch (RemoteException e) {
                loge("Cannot remove network availability updater. " + e);
            }

            mPhone.getContext().unbindService(mServiceConnection);
        }

        try {
            mServiceConnection = new QualifiedNetworksServiceConnection();
            log("bind to " + packageName);
            if (!mPhone.getContext().bindService(
                    new Intent(QualifiedNetworksService.QUALIFIED_NETWORKS_SERVICE_INTERFACE)
                            .setPackage(packageName),
                    mServiceConnection,
                    Context.BIND_AUTO_CREATE)) {
                loge("Cannot bind to the qualified networks service.");
                return;
            }
            mTargetBindingPackageName = packageName;
        } catch (Exception e) {
            loge("Cannot bind to the qualified networks service. Exception: " + e);
        }
    }

    /**
     * Get the qualified network service package.
     *
     * @return package name of the qualified networks service package. Return empty string when in
     * legacy mode (i.e. Dedicated IWLAN data/network service is not supported).
     */
    private String getQualifiedNetworksServicePackageName() {
        // Read package name from the resource
        String packageName = mPhone.getContext().getResources().getString(
                com.android.internal.R.string.config_qualified_networks_service_package);

        PersistableBundle b = mCarrierConfigManager.getConfigForSubId(mPhone.getSubId());

        if (b != null) {
            // If carrier config overrides it, use the one from carrier config
            String carrierConfigPackageName =  b.getString(CarrierConfigManager
                    .KEY_CARRIER_QUALIFIED_NETWORKS_SERVICE_PACKAGE_OVERRIDE_STRING);
            if (!TextUtils.isEmpty(carrierConfigPackageName)) {
                if (DBG) log("Found carrier config override " + carrierConfigPackageName);
                packageName = carrierConfigPackageName;
            }
        }

        return packageName;
    }


    private @NonNull List<QualifiedNetworks> getQualifiedNetworksList() {
        List<QualifiedNetworks> qualifiedNetworksList = new ArrayList<>();
        for (int i = 0; i < mAvailableNetworks.size(); i++) {
            qualifiedNetworksList.add(new QualifiedNetworks(mAvailableNetworks.keyAt(i),
                    mAvailableNetworks.valueAt(i)));
        }

        return qualifiedNetworksList;
    }

    /**
     * Register for qualified networks changed event.
     *
     * @param h The target to post the event message to.
     * @param what The event.
     */
    public void registerForQualifiedNetworksChanged(Handler h, int what) {
        if (h != null) {
            Registrant r = new Registrant(h, what, null);
            mQualifiedNetworksChangedRegistrants.add(r);

            // Notify for the first time if there is already something in the available network
            // list.
            if (mAvailableNetworks.size() != 0) {
                r.notifyResult(getQualifiedNetworksList());
            }
        }
    }

    /**
     * Unregister for qualified networks changed event.
     *
     * @param h The handler
     */
    public void unregisterForQualifiedNetworksChanged(Handler h) {
        if (h != null) {
            mQualifiedNetworksChangedRegistrants.remove(h);
        }
    }

    /**
     * Dump the state of transport manager
     *
     * @param fd File descriptor
     * @param pw Print writer
     * @param args Arguments
     */
    public void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {
        pw.println("AccessNetworksManager:");
        pw.increaseIndent();
        pw.println("Available networks:");
        pw.increaseIndent();

        for (int i = 0; i < mAvailableNetworks.size(); i++) {
            pw.println("APN type " + ApnSetting.getApnTypeString(mAvailableNetworks.keyAt(i))
                    + ": [" + Arrays.stream(mAvailableNetworks.valueAt(i))
                    .mapToObj(type -> AccessNetworkType.toString(type))
                    .collect(Collectors.joining(",")) + "]");
        }
        pw.decreaseIndent();
        pw.decreaseIndent();
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }

}

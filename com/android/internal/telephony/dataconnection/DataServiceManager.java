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

import static android.telephony.AccessNetworkConstants.TransportType.WLAN;
import static android.telephony.AccessNetworkConstants.TransportType.WWAN;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.LinkProperties;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.data.DataCallResponse;
import android.telephony.data.DataProfile;
import android.telephony.data.DataService;
import android.telephony.data.DataServiceCallback;
import android.telephony.data.IDataService;
import android.telephony.data.IDataServiceCallback;
import android.text.TextUtils;

import com.android.internal.telephony.Phone;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data service manager manages handling data requests and responses on data services (e.g.
 * Cellular data service, IWLAN data service).
 */
public class DataServiceManager {
    private static final String TAG = DataServiceManager.class.getSimpleName();
    private static final boolean DBG = false;

    static final String DATA_CALL_RESPONSE = "data_call_response";

    private final Phone mPhone;

    private final CarrierConfigManager mCarrierConfigManager;
    private final AppOpsManager mAppOps;
    private final IPackageManager mPackageManager;

    private final int mTransportType;

    private boolean mBound;

    private IDataService mIDataService;

    private DataServiceManagerDeathRecipient mDeathRecipient;

    private final RegistrantList mServiceBindingChangedRegistrants = new RegistrantList();

    private final Map<IBinder, Message> mMessageMap = new ConcurrentHashMap<>();

    private final RegistrantList mDataCallListChangedRegistrants = new RegistrantList();

    // not final because it is set by the onServiceConnected method
    private ComponentName mComponentName;

    private class DataServiceManagerDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            // TODO: try to rebind the service.
            loge("DataService(" + mComponentName +  " transport type " + mTransportType
                    + ") died.");
        }
    }

    private void grantPermissionsToService(String packageName) {
        final String[] pkgToGrant = {packageName};
        try {
            mPackageManager.grantDefaultPermissionsToEnabledTelephonyDataServices(
                    pkgToGrant, mPhone.getContext().getUserId());
            mAppOps.setMode(AppOpsManager.OP_MANAGE_IPSEC_TUNNELS, mPhone.getContext().getUserId(),
                    pkgToGrant[0], AppOpsManager.MODE_ALLOWED);
        } catch (RemoteException e) {
            loge("Binder to package manager died, permission grant for DataService failed.");
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Loop through all DataServices installed on the system and revoke permissions from any that
     * are not currently the WWAN or WLAN data service.
     */
    private void revokePermissionsFromUnusedDataServices() {
        // Except the current data services from having their permissions removed.
        Set<String> dataServices = getAllDataServicePackageNames();
        for (int transportType : new int[] {WWAN, WLAN}) {
            dataServices.remove(getDataServicePackageName(transportType));
        }

        try {
            String[] dataServicesArray = new String[dataServices.size()];
            dataServices.toArray(dataServicesArray);
            mPackageManager.revokeDefaultPermissionsFromDisabledTelephonyDataServices(
                    dataServicesArray, mPhone.getContext().getUserId());
            for (String pkg : dataServices) {
                mAppOps.setMode(AppOpsManager.OP_MANAGE_IPSEC_TUNNELS,
                        mPhone.getContext().getUserId(),
                        pkg, AppOpsManager.MODE_ERRORED);
            }
        } catch (RemoteException e) {
            loge("Binder to package manager died; failed to revoke DataService permissions.");
            throw e.rethrowAsRuntimeException();
        }
    }

    private final class CellularDataServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DBG) log("onServiceConnected");
            mComponentName = name;
            mIDataService = IDataService.Stub.asInterface(service);
            mDeathRecipient = new DataServiceManagerDeathRecipient();
            mBound = true;

            try {
                service.linkToDeath(mDeathRecipient, 0);
                mIDataService.createDataServiceProvider(mPhone.getPhoneId());
                mIDataService.registerForDataCallListChanged(mPhone.getPhoneId(),
                        new CellularDataServiceCallback());
            } catch (RemoteException e) {
                mDeathRecipient.binderDied();
                loge("Remote exception. " + e);
                return;
            }

            mServiceBindingChangedRegistrants.notifyResult(true);
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DBG) log("onServiceDisconnected");
            mIDataService.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mIDataService = null;
            mBound = false;
            mServiceBindingChangedRegistrants.notifyResult(false);
        }
    }

    private final class CellularDataServiceCallback extends IDataServiceCallback.Stub {
        @Override
        public void onSetupDataCallComplete(@DataServiceCallback.ResultCode int resultCode,
                                            DataCallResponse response) {
            if (DBG) {
                log("onSetupDataCallComplete. resultCode = " + resultCode + ", response = "
                        + response);
            }
            Message msg = mMessageMap.remove(asBinder());
            if (msg != null) {
                msg.getData().putParcelable(DATA_CALL_RESPONSE, response);
                sendCompleteMessage(msg, resultCode);
            } else {
                loge("Unable to find the message for setup call response.");
            }
        }

        @Override
        public void onDeactivateDataCallComplete(@DataServiceCallback.ResultCode int resultCode) {
            if (DBG) log("onDeactivateDataCallComplete. resultCode = " + resultCode);
            Message msg = mMessageMap.remove(asBinder());
            sendCompleteMessage(msg, resultCode);
        }

        @Override
        public void onSetInitialAttachApnComplete(@DataServiceCallback.ResultCode int resultCode) {
            if (DBG) log("onSetInitialAttachApnComplete. resultCode = " + resultCode);
            Message msg = mMessageMap.remove(asBinder());
            sendCompleteMessage(msg, resultCode);
        }

        @Override
        public void onSetDataProfileComplete(@DataServiceCallback.ResultCode int resultCode) {
            if (DBG) log("onSetDataProfileComplete. resultCode = " + resultCode);
            Message msg = mMessageMap.remove(asBinder());
            sendCompleteMessage(msg, resultCode);
        }

        @Override
        public void onGetDataCallListComplete(@DataServiceCallback.ResultCode int resultCode,
                                              List<DataCallResponse> dataCallList) {
            if (DBG) log("onGetDataCallListComplete. resultCode = " + resultCode);
            Message msg = mMessageMap.remove(asBinder());
            sendCompleteMessage(msg, resultCode);
        }

        @Override
        public void onDataCallListChanged(List<DataCallResponse> dataCallList) {
            mDataCallListChangedRegistrants.notifyRegistrants(
                    new AsyncResult(null, dataCallList, null));
        }
    }

    /**
     * Constructor
     *
     * @param phone The phone object
     * @param transportType The transport type. Must be a
     *        {@link AccessNetworkConstants.TransportType}.
     */
    public DataServiceManager(Phone phone, int transportType) {
        mPhone = phone;
        mTransportType = transportType;
        mBound = false;
        mCarrierConfigManager = (CarrierConfigManager) phone.getContext().getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        mPackageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        mAppOps = (AppOpsManager) phone.getContext().getSystemService(Context.APP_OPS_SERVICE);
        bindDataService();
    }

    private void bindDataService() {
        // Start by cleaning up all packages that *shouldn't* have permissions.
        revokePermissionsFromUnusedDataServices();

        String packageName = getDataServicePackageName();
        if (TextUtils.isEmpty(packageName)) {
            loge("Can't find the binding package");
            return;
        }
        // Then pre-emptively grant the permissions to the package we will bind.
        grantPermissionsToService(packageName);

        try {
            if (!mPhone.getContext().bindService(
                    new Intent(DataService.DATA_SERVICE_INTERFACE).setPackage(packageName),
                    new CellularDataServiceConnection(),
                    Context.BIND_AUTO_CREATE)) {
                loge("Cannot bind to the data service.");
            }
        } catch (Exception e) {
            loge("Cannot bind to the data service. Exception: " + e);
        }
    }

    @NonNull
    private Set<String> getAllDataServicePackageNames() {
        // Cowardly using the public PackageManager interface here.
        // Note: This matches only packages that were installed on the system image. If we ever
        // expand the permissions model to allow CarrierPrivileged packages, then this will need
        // to be updated.
        List<ResolveInfo> dataPackages =
                mPhone.getContext().getPackageManager().queryIntentServices(
                        new Intent(DataService.DATA_SERVICE_INTERFACE),
                                PackageManager.MATCH_SYSTEM_ONLY);
        HashSet<String> packageNames = new HashSet<>();
        for (ResolveInfo info : dataPackages) {
            if (info.serviceInfo == null) continue;
            packageNames.add(info.serviceInfo.packageName);
        }
        return packageNames;
    }

    /**
     * Get the data service package name for our current transport type.
     *
     * @return package name of the data service package for the the current transportType.
     */
    private String getDataServicePackageName() {
        return getDataServicePackageName(mTransportType);
    }

    /**
     * Get the data service package by transport type.
     *
     * When we bind to a DataService package, we need to revoke permissions from stale
     * packages; we need to exclude data packages for all transport types, so we need to
     * to be able to query by transport type.
     *
     * @param transportType either WWAN or WLAN
     * @return package name of the data service package for the specified transportType.
     */
    private String getDataServicePackageName(int transportType) {
        String packageName;
        int resourceId;
        String carrierConfig;

        switch (transportType) {
            case WWAN:
                resourceId = com.android.internal.R.string.config_wwan_data_service_package;
                carrierConfig = CarrierConfigManager
                        .KEY_CARRIER_DATA_SERVICE_WWAN_PACKAGE_OVERRIDE_STRING;
                break;
            case WLAN:
                resourceId = com.android.internal.R.string.config_wlan_data_service_package;
                carrierConfig = CarrierConfigManager
                        .KEY_CARRIER_DATA_SERVICE_WLAN_PACKAGE_OVERRIDE_STRING;
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

        return packageName;
    }

    private void sendCompleteMessage(Message msg, int code) {
        if (msg != null) {
            msg.arg1 = code;
            msg.sendToTarget();
        }
    }

    /**
     * Setup a data connection. The data service provider must implement this method to support
     * establishing a packet data connection. When completed or error, the service must invoke
     * the provided callback to notify the platform.
     *
     * @param accessNetworkType Access network type that the data call will be established on.
     *        Must be one of {@link AccessNetworkConstants.AccessNetworkType}.
     * @param dataProfile Data profile used for data call setup. See {@link DataProfile}
     * @param isRoaming True if the device is data roaming.
     * @param allowRoaming True if data roaming is allowed by the user.
     * @param reason The reason for data setup. Must be {@link DataService#REQUEST_REASON_NORMAL} or
     *        {@link DataService#REQUEST_REASON_HANDOVER}.
     * @param linkProperties If {@code reason} is {@link DataService#REQUEST_REASON_HANDOVER}, this
     *        is the link properties of the existing data connection, otherwise null.
     * @param onCompleteMessage The result message for this request. Null if the client does not
     *        care about the result.
     */
    public void setupDataCall(int accessNetworkType, DataProfile dataProfile, boolean isRoaming,
                              boolean allowRoaming, int reason, LinkProperties linkProperties,
                              Message onCompleteMessage) {
        if (DBG) log("setupDataCall");
        if (!mBound) {
            loge("Data service not bound.");
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
            return;
        }

        CellularDataServiceCallback callback = null;
        if (onCompleteMessage != null) {
            callback = new CellularDataServiceCallback();
            mMessageMap.put(callback.asBinder(), onCompleteMessage);
        }
        try {
            mIDataService.setupDataCall(mPhone.getPhoneId(), accessNetworkType, dataProfile,
                    isRoaming, allowRoaming, reason, linkProperties, callback);
        } catch (RemoteException e) {
            loge("Cannot invoke setupDataCall on data service.");
            if (callback != null) {
                mMessageMap.remove(callback.asBinder());
            }
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
        }
    }

    /**
     * Deactivate a data connection. The data service provider must implement this method to
     * support data connection tear down. When completed or error, the service must invoke the
     * provided callback to notify the platform.
     *
     * @param cid Call id returned in the callback of {@link #setupDataCall(int, DataProfile,
     *        boolean, boolean, int, LinkProperties, Message)}
     * @param reason The reason for data deactivation. Must be
     *        {@link DataService#REQUEST_REASON_NORMAL}, {@link DataService#REQUEST_REASON_SHUTDOWN}
     *        or {@link DataService#REQUEST_REASON_HANDOVER}.
     * @param onCompleteMessage The result message for this request. Null if the client does not
     *        care about the result.
     */
    public void deactivateDataCall(int cid, int reason, Message onCompleteMessage) {
        if (DBG) log("deactivateDataCall");
        if (!mBound) {
            loge("Data service not bound.");
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
            return;
        }

        CellularDataServiceCallback callback = null;
        if (onCompleteMessage != null) {
            callback = new CellularDataServiceCallback();
            mMessageMap.put(callback.asBinder(), onCompleteMessage);
        }
        try {
            mIDataService.deactivateDataCall(mPhone.getPhoneId(), cid, reason, callback);
        } catch (RemoteException e) {
            loge("Cannot invoke deactivateDataCall on data service.");
            if (callback != null) {
                mMessageMap.remove(callback.asBinder());
            }
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
        }
    }

    /**
     * Set an APN to initial attach network.
     *
     * @param dataProfile Data profile used for data call setup. See {@link DataProfile}.
     * @param isRoaming True if the device is data roaming.
     * @param onCompleteMessage The result message for this request. Null if the client does not
     *        care about the result.
     */
    public void setInitialAttachApn(DataProfile dataProfile, boolean isRoaming,
                                    Message onCompleteMessage) {
        if (DBG) log("setInitialAttachApn");
        if (!mBound) {
            loge("Data service not bound.");
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
            return;
        }

        CellularDataServiceCallback callback = null;
        if (onCompleteMessage != null) {
            callback = new CellularDataServiceCallback();
            mMessageMap.put(callback.asBinder(), onCompleteMessage);
        }
        try {
            mIDataService.setInitialAttachApn(mPhone.getPhoneId(), dataProfile, isRoaming,
                    callback);
        } catch (RemoteException e) {
            loge("Cannot invoke setInitialAttachApn on data service.");
            if (callback != null) {
                mMessageMap.remove(callback.asBinder());
            }
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
        }
    }

    /**
     * Send current carrier's data profiles to the data service for data call setup. This is
     * only for CDMA carrier that can change the profile through OTA. The data service should
     * always uses the latest data profile sent by the framework.
     *
     * @param dps A list of data profiles.
     * @param isRoaming True if the device is data roaming.
     * @param onCompleteMessage The result message for this request. Null if the client does not
     *        care about the result.
     */
    public void setDataProfile(List<DataProfile> dps, boolean isRoaming,
                               Message onCompleteMessage) {
        if (DBG) log("setDataProfile");
        if (!mBound) {
            loge("Data service not bound.");
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
            return;
        }

        CellularDataServiceCallback callback = null;
        if (onCompleteMessage != null) {
            callback = new CellularDataServiceCallback();
            mMessageMap.put(callback.asBinder(), onCompleteMessage);
        }
        try {
            mIDataService.setDataProfile(mPhone.getPhoneId(), dps, isRoaming, callback);
        } catch (RemoteException e) {
            loge("Cannot invoke setDataProfile on data service.");
            if (callback != null) {
                mMessageMap.remove(callback.asBinder());
            }
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
        }
    }

    /**
     * Get the active data call list.
     *
     * @param onCompleteMessage The result message for this request. Null if the client does not
     *        care about the result.
     */
    public void getDataCallList(Message onCompleteMessage) {
        if (DBG) log("getDataCallList");
        if (!mBound) {
            loge("Data service not bound.");
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
            return;
        }

        CellularDataServiceCallback callback = null;
        if (onCompleteMessage != null) {
            callback = new CellularDataServiceCallback();
            mMessageMap.put(callback.asBinder(), onCompleteMessage);
        }
        try {
            mIDataService.getDataCallList(mPhone.getPhoneId(), callback);
        } catch (RemoteException e) {
            loge("Cannot invoke getDataCallList on data service.");
            if (callback != null) {
                mMessageMap.remove(callback.asBinder());
            }
            sendCompleteMessage(onCompleteMessage, DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE);
        }
    }

    /**
     * Register for data call list changed event.
     *
     * @param h The target to post the event message to.
     * @param what The event.
     */
    public void registerForDataCallListChanged(Handler h, int what) {
        if (h != null) {
            mDataCallListChangedRegistrants.addUnique(h, what, null);
        }
    }

    /**
     * Unregister for data call list changed event.
     *
     * @param h The handler
     */
    public void unregisterForDataCallListChanged(Handler h) {
        if (h != null) {
            mDataCallListChangedRegistrants.remove(h);
        }
    }

    /**
     * Register for data service binding status changed event.
     *
     * @param h The target to post the event message to.
     * @param what The event.
     * @param obj The user object.
     */
    public void registerForServiceBindingChanged(Handler h, int what, Object obj) {
        if (h != null) {
            mServiceBindingChangedRegistrants.addUnique(h, what, obj);
        }

    }

    /**
     * Unregister for data service binding status changed event.
     *
     * @param h The handler
     */
    public void unregisterForServiceBindingChanged(Handler h) {
        if (h != null) {
            mServiceBindingChangedRegistrants.remove(h);
        }
    }

    /**
     * Get the transport type. Must be a {@link AccessNetworkConstants.TransportType}.
     *
     * @return
     */
    public int getTransportType() {
        return mTransportType;
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }

}

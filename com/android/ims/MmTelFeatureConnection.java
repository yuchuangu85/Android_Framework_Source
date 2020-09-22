/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.ims;

import android.annotation.NonNull;
import android.content.Context;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.telephony.ims.aidl.IImsSmsListener;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.util.Log;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsUt;
import com.android.telephony.Rlog;

/**
 * A container of the IImsServiceController binder, which implements all of the ImsFeatures that
 * the platform currently supports: MMTel
 */

public class MmTelFeatureConnection extends FeatureConnection {
    protected static final String TAG = "MmTelFeatureConnection";

    private class ImsRegistrationCallbackAdapter extends
            ImsCallbackAdapterManager<IImsRegistrationCallback> {

        public ImsRegistrationCallbackAdapter(Context context, Object lock) {
            super(context, lock, mSlotId);
        }

        @Override
        public void registerCallback(IImsRegistrationCallback localCallback) {
            IImsRegistration imsRegistration = getRegistration();
            if (imsRegistration != null) {
                try {
                    imsRegistration.addRegistrationCallback(localCallback);
                } catch (RemoteException e) {
                    throw new IllegalStateException("ImsRegistrationCallbackAdapter: MmTelFeature"
                            + " binder is dead.");
                }
            } else {
                Log.e(TAG + " [" + mSlotId + "]", "ImsRegistrationCallbackAdapter: ImsRegistration"
                        + " is null");
                throw new IllegalStateException("ImsRegistrationCallbackAdapter: MmTelFeature is"
                        + "not available!");
            }
        }

        @Override
        public void unregisterCallback(IImsRegistrationCallback localCallback) {
            IImsRegistration imsRegistration = getRegistration();
            if (imsRegistration != null) {
                try {
                    imsRegistration.removeRegistrationCallback(localCallback);
                } catch (RemoteException e) {
                    Log.w(TAG + " [" + mSlotId + "]", "ImsRegistrationCallbackAdapter -"
                            + " unregisterCallback: couldn't remove registration callback");
                }
            } else {
                Log.e(TAG + " [" + mSlotId + "]", "ImsRegistrationCallbackAdapter: ImsRegistration"
                        + " is null");
            }
        }
    }

    private class CapabilityCallbackManager extends ImsCallbackAdapterManager<IImsCapabilityCallback> {

        public CapabilityCallbackManager(Context context, Object lock) {
            super(context, lock, mSlotId);
        }

        @Override
        public void registerCallback(IImsCapabilityCallback localCallback) {
            IImsMmTelFeature binder;
            synchronized (mLock) {
                try {
                    checkServiceIsReady();
                    binder = getServiceInterface(mBinder);
                } catch (RemoteException e) {
                    throw new IllegalStateException("CapabilityCallbackManager - MmTelFeature"
                            + " binder is dead.");
                }
            }
            if (binder != null) {
                try {
                binder.addCapabilityCallback(localCallback);
                } catch (RemoteException e) {
                    throw new IllegalStateException(" CapabilityCallbackManager - MmTelFeature"
                            + " binder is null.");
                }
            } else {
                Log.w(TAG + " [" + mSlotId + "]", "CapabilityCallbackManager, register: Couldn't"
                        + " get binder");
                throw new IllegalStateException("CapabilityCallbackManager: MmTelFeature is"
                        + " not available!");
            }
        }

        @Override
        public void unregisterCallback(IImsCapabilityCallback localCallback) {
            IImsMmTelFeature binder;
            synchronized (mLock) {
                try {
                    checkServiceIsReady();
                    binder = getServiceInterface(mBinder);
                } catch (RemoteException e) {
                    // binder is null
                    Log.w(TAG + " [" + mSlotId + "]", "CapabilityCallbackManager, unregister:"
                            + " couldn't get binder.");
                    return;
                }
            }
            if (binder != null) {
                try {
                    binder.removeCapabilityCallback(localCallback);
                } catch (RemoteException e) {
                    Log.w(TAG + " [" + mSlotId + "]", "CapabilityCallbackManager, unregister:"
                            + " Binder is dead.");
                }
            } else {
                Log.w(TAG + " [" + mSlotId + "]", "CapabilityCallbackManager, unregister:"
                        + " binder is null.");
            }
        }
    }

    private class ProvisioningCallbackManager extends ImsCallbackAdapterManager<IImsConfigCallback> {
        public ProvisioningCallbackManager (Context context, Object lock) {
            super(context, lock, mSlotId);
        }

        @Override
        public void registerCallback(IImsConfigCallback localCallback) {
            IImsConfig binder = getConfigInterface();
            if (binder == null) {
                // Config interface is not currently available.
                Log.w(TAG + " [" + mSlotId + "]", "ProvisioningCallbackManager - couldn't register,"
                        + " binder is null.");
                throw new IllegalStateException("ImsConfig is not available!");
            }
            try {
                binder.addImsConfigCallback(localCallback);
            }catch (RemoteException e) {
                throw new IllegalStateException("ImsService is not available!");
            }
        }

        @Override
        public void unregisterCallback(IImsConfigCallback localCallback) {
            IImsConfig binder = getConfigInterface();
            if (binder == null) {
                Log.w(TAG + " [" + mSlotId + "]", "ProvisioningCallbackManager - couldn't"
                        + " unregister, binder is null.");
                return;
            }
            try {
                binder.removeImsConfigCallback(localCallback);
            } catch (RemoteException e) {
                Log.w(TAG + " [" + mSlotId + "]", "ProvisioningCallbackManager - couldn't"
                        + " unregister, binder is dead.");
            }
        }
    }

    // Updated by IImsServiceFeatureCallback when FEATURE_EMERGENCY_MMTEL is sent.
    private boolean mSupportsEmergencyCalling = false;

    // Cache the Registration and Config interfaces as long as the MmTel feature is connected. If
    // it becomes disconnected, invalidate.
    private IImsConfig mConfigBinder;
    private final ImsRegistrationCallbackAdapter mRegistrationCallbackManager;
    private final CapabilityCallbackManager mCapabilityCallbackManager;
    private final ProvisioningCallbackManager mProvisioningCallbackManager;

    public static @NonNull MmTelFeatureConnection create(Context context , int slotId) {
        MmTelFeatureConnection serviceProxy = new MmTelFeatureConnection(context, slotId);
        if (!ImsManager.isImsSupportedOnDevice(context)) {
            // Return empty service proxy in the case that IMS is not supported.
            sImsSupportedOnDevice = false;
            return serviceProxy;
        }

        TelephonyManager tm = serviceProxy.getTelephonyManager();
        if (tm == null) {
            Rlog.w(TAG + " [" + slotId + "]", "create: TelephonyManager is null!");
            // Binder can be unset in this case because it will be torn down/recreated as part of
            // a retry mechanism until the serviceProxy binder is set successfully.
            return serviceProxy;
        }

        IImsMmTelFeature binder = tm.getImsMmTelFeatureAndListen(slotId,
                serviceProxy.getListener());
        if (binder != null) {
            serviceProxy.setBinder(binder.asBinder());
            // Trigger the cache to be updated for feature status.
            serviceProxy.getFeatureState();
        } else {
            Rlog.w(TAG + " [" + slotId + "]", "create: binder is null!");
        }
        return serviceProxy;
    }

    public MmTelFeatureConnection(Context context, int slotId) {
        super(context, slotId);

        mRegistrationCallbackManager = new ImsRegistrationCallbackAdapter(context, mLock);
        mCapabilityCallbackManager = new CapabilityCallbackManager(context, mLock);
        mProvisioningCallbackManager = new ProvisioningCallbackManager(context, mLock);
    }

    @Override
    protected void onRemovedOrDied() {
        removeImsFeatureCallback();
        synchronized (mLock) {
            super.onRemovedOrDied();
            mRegistrationCallbackManager.close();
            mCapabilityCallbackManager.close();
            mProvisioningCallbackManager.close();
            mConfigBinder = null;
        }
    }

    private void removeImsFeatureCallback() {
        TelephonyManager tm = getTelephonyManager();
        if (tm != null) {
            tm.unregisterImsFeatureCallback(mSlotId, ImsFeature.FEATURE_MMTEL, getListener());
        }
    }

    private IImsConfig getConfig() {
        synchronized (mLock) {
            // null if cache is invalid;
            if (mConfigBinder != null) {
                return mConfigBinder;
            }
        }
        TelephonyManager tm = getTelephonyManager();
        IImsConfig configBinder = tm != null
                ? tm.getImsConfig(mSlotId, ImsFeature.FEATURE_MMTEL) : null;
        synchronized (mLock) {
            // mConfigBinder may have changed while we tried to get the config interface.
            if (mConfigBinder == null) {
                mConfigBinder = configBinder;
            }
        }
        return mConfigBinder;
    }

    @Override
    protected void handleImsFeatureCreatedCallback(int slotId, int feature) {
        // The feature has been enabled. This happens when the feature is first created and
        // may happen when the feature is re-enabled.
        synchronized (mLock) {
            if(mSlotId != slotId) {
                return;
            }
            switch (feature) {
                case ImsFeature.FEATURE_MMTEL: {
                    if (!mIsAvailable) {
                        Log.i(TAG + " [" + mSlotId + "]", "MmTel enabled");
                        mIsAvailable = true;
                    }
                    break;
                }
                case ImsFeature.FEATURE_EMERGENCY_MMTEL: {
                    mSupportsEmergencyCalling = true;
                    Log.i(TAG + " [" + mSlotId + "]", "Emergency calling enabled");
                    break;
                }
            }
        }
    }

    @Override
    protected void handleImsFeatureRemovedCallback(int slotId, int feature) {
        synchronized (mLock) {
            if (mSlotId != slotId) {
                return;
            }
            switch (feature) {
                case ImsFeature.FEATURE_MMTEL: {
                    Log.i(TAG + " [" + mSlotId + "]", "MmTel removed");
                    onRemovedOrDied();
                    break;
                }
                case ImsFeature.FEATURE_EMERGENCY_MMTEL: {
                    mSupportsEmergencyCalling = false;
                    Log.i(TAG + " [" + mSlotId + "]", "Emergency calling disabled");
                    break;
                }
            }
        }
    }

    @Override
    protected void handleImsStatusChangedCallback(int slotId, int feature, int status) {
        synchronized (mLock) {
            Log.i(TAG + " [" + mSlotId + "]", "imsStatusChanged: slot: " + slotId + " feature: "
                + ImsFeature.FEATURE_LOG_MAP.get(feature) +
                " status: " + ImsFeature.STATE_LOG_MAP.get(status));
            if (mSlotId == slotId && feature == ImsFeature.FEATURE_MMTEL) {
                mFeatureStateCached = status;
                if (mStatusCallback != null) {
                    mStatusCallback.notifyStateChanged();
                }
            }
        }
    }

    public boolean isEmergencyMmTelAvailable() {
        return mSupportsEmergencyCalling;
    }

    /**
     * Opens the connection to the {@link MmTelFeature} and establishes a listener back to the
     * framework. Calling this method multiple times will reset the listener attached to the
     * {@link MmTelFeature}.
     * @param listener A {@link MmTelFeature.Listener} that will be used by the {@link MmTelFeature}
     * to notify the framework of updates.
     */
    public void openConnection(MmTelFeature.Listener listener) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).setListener(listener);
        }
    }

    public void closeConnection() {
        mRegistrationCallbackManager.close();
        mCapabilityCallbackManager.close();
        mProvisioningCallbackManager.close();
        try {
            synchronized (mLock) {
                if (isBinderAlive()) {
                    getServiceInterface(mBinder).setListener(null);
                }
            }
        } catch (RemoteException e) {
            Log.w(TAG + " [" + mSlotId + "]", "closeConnection: couldn't remove listener!");
        }
    }

    public void addRegistrationCallback(IImsRegistrationCallback callback) {
        mRegistrationCallbackManager.addCallback(callback);
    }

    public void addRegistrationCallbackForSubscription(IImsRegistrationCallback callback,
            int subId) {
        mRegistrationCallbackManager.addCallbackForSubscription(callback , subId);
    }

    public void removeRegistrationCallback(IImsRegistrationCallback callback) {
        mRegistrationCallbackManager.removeCallback(callback);
    }

    public void removeRegistrationCallbackForSubscription(IImsRegistrationCallback callback,
            int subId) {
        mRegistrationCallbackManager.removeCallbackForSubscription(callback, subId);
    }

    public void addCapabilityCallback(IImsCapabilityCallback callback) {
        mCapabilityCallbackManager.addCallback(callback);
    }

    public void addCapabilityCallbackForSubscription(IImsCapabilityCallback callback,
            int subId) {
        mCapabilityCallbackManager.addCallbackForSubscription(callback, subId);
    }

    public void removeCapabilityCallback(IImsCapabilityCallback callback) {
        mCapabilityCallbackManager.removeCallback(callback);
    }

    public void removeCapabilityCallbackForSubscription(IImsCapabilityCallback callback,
            int subId) {
        mCapabilityCallbackManager.removeCallbackForSubscription(callback , subId);
    }

    public void addProvisioningCallbackForSubscription(IImsConfigCallback callback,
            int subId) {
        mProvisioningCallbackManager.addCallbackForSubscription(callback, subId);
    }

    public void removeProvisioningCallbackForSubscription(IImsConfigCallback callback,
            int subId) {
        mProvisioningCallbackManager.removeCallbackForSubscription(callback , subId);
    }

    public void changeEnabledCapabilities(CapabilityChangeRequest request,
            IImsCapabilityCallback callback) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).changeCapabilitiesConfiguration(request, callback);
        }
    }

    public void queryEnabledCapabilities(int capability, int radioTech,
            IImsCapabilityCallback callback) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).queryCapabilityConfiguration(capability, radioTech,
                    callback);
        }
    }

    public MmTelFeature.MmTelCapabilities queryCapabilityStatus() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return new MmTelFeature.MmTelCapabilities(
                    getServiceInterface(mBinder).queryCapabilityStatus());
        }
    }

    public ImsCallProfile createCallProfile(int callServiceType, int callType)
            throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).createCallProfile(callServiceType, callType);
        }
    }

    public IImsCallSession createCallSession(ImsCallProfile profile)
            throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).createCallSession(profile);
        }
    }

    public IImsUt getUtInterface() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).getUtInterface();
        }
    }

    public IImsConfig getConfigInterface() {
        return getConfig();
    }

    public IImsEcbm getEcbmInterface() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).getEcbmInterface();
        }
    }

    public void setUiTTYMode(int uiTtyMode, Message onComplete)
            throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).setUiTtyMode(uiTtyMode, onComplete);
        }
    }

    public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).getMultiEndpointInterface();
        }
    }

    public void sendSms(int token, int messageRef, String format, String smsc, boolean isRetry,
            byte[] pdu) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).sendSms(token, messageRef, format, smsc, isRetry,
                    pdu);
        }
    }

    public void acknowledgeSms(int token, int messageRef,
            @ImsSmsImplBase.SendStatusResult int result) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).acknowledgeSms(token, messageRef, result);
        }
    }

    public void acknowledgeSmsReport(int token, int messageRef,
            @ImsSmsImplBase.StatusReportResult int result) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).acknowledgeSmsReport(token, messageRef, result);
        }
    }

    public String getSmsFormat() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).getSmsFormat();
        }
    }

    public void onSmsReady() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).onSmsReady();
        }
    }

    public void setSmsListener(IImsSmsListener listener) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).setSmsListener(listener);
        }
    }

    public @MmTelFeature.ProcessCallResult int shouldProcessCall(boolean isEmergency,
            String[] numbers) throws RemoteException {
        if (isEmergency && !isEmergencyMmTelAvailable()) {
            // Don't query the ImsService if emergency calling is not available on the ImsService.
            Log.i(TAG + " [" + mSlotId + "]", "MmTel does not support emergency over IMS, fallback"
                    + " to CS.");
            return MmTelFeature.PROCESS_CALL_CSFB;
        }
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).shouldProcessCall(numbers);
        }
    }

    @Override
    protected Integer retrieveFeatureState() {
        if (mBinder != null) {
            try {
                return getServiceInterface(mBinder).getFeatureState();
            } catch (RemoteException e) {
                // Status check failed, don't update cache
            }
        }
        return null;
    }

    @Override
    protected IImsRegistration getRegistrationBinder() {
        TelephonyManager tm = getTelephonyManager();
        return  tm != null ? tm.getImsRegistration(mSlotId, ImsFeature.FEATURE_MMTEL) : null;
    }

    private IImsMmTelFeature getServiceInterface(IBinder b) {
        return IImsMmTelFeature.Stub.asInterface(b);
    }
}

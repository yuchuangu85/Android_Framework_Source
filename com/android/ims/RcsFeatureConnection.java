/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.telephony.ims.aidl.IRcsFeatureListener;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.ImsFeature;

import com.android.internal.annotations.VisibleForTesting;
import com.android.telephony.Rlog;

/**
 * A container of the IImsServiceController binder, which implements all of the RcsFeatures that
 * the platform currently supports: RCS
 */
public class RcsFeatureConnection extends FeatureConnection {
    private static final String TAG = "RcsFeatureConnection";

    public class AvailabilityCallbackManager extends
            ImsCallbackAdapterManager<IImsCapabilityCallback> {

        AvailabilityCallbackManager(Context context) {
            super(context, new Object() /* Lock object */, mSlotId);
        }

        @Override
        public void registerCallback(IImsCapabilityCallback localCallback) {
            try {
                addCapabilityCallback(localCallback);
            } catch (RemoteException e) {
                loge("Register capability callback error: " + e);
                throw new IllegalStateException(
                        " CapabilityCallbackManager: Register callback error");
            }
        }

        @Override
        public void unregisterCallback(IImsCapabilityCallback localCallback) {
            try {
                removeCapabilityCallback(localCallback);
            } catch (RemoteException e) {
                loge("Cannot remove capability callback: " + e);
            }
        }
    }

    private class RegistrationCallbackManager extends
            ImsCallbackAdapterManager<IImsRegistrationCallback> {

        public RegistrationCallbackManager(Context context) {
            super(context, new Object() /* Lock object */, mSlotId);
        }

        @Override
        public void registerCallback(IImsRegistrationCallback localCallback) {
            IImsRegistration imsRegistration = getRegistration();
            if (imsRegistration == null) {
                loge("Register IMS registration callback: ImsRegistration is null");
                throw new IllegalStateException("RegistrationCallbackAdapter: RcsFeature is"
                        + " not available!");
            }

            try {
                imsRegistration.addRegistrationCallback(localCallback);
            } catch (RemoteException e) {
                throw new IllegalStateException("RegistrationCallbackAdapter: RcsFeature"
                        + " binder is dead.");
            }
        }

        @Override
        public void unregisterCallback(IImsRegistrationCallback localCallback) {
            IImsRegistration imsRegistration = getRegistration();
            if (imsRegistration == null) {
                logi("Unregister IMS registration callback: ImsRegistration is null");
                return;
            }

            try {
                imsRegistration.removeRegistrationCallback(localCallback);
            } catch (RemoteException e) {
                loge("Cannot remove registration callback: " + e);
            }
        }
    }

    public static @NonNull RcsFeatureConnection create(Context context , int slotId,
            IFeatureUpdate callback) {

        RcsFeatureConnection serviceProxy = new RcsFeatureConnection(context, slotId, callback);

        if (!ImsManager.isImsSupportedOnDevice(context)) {
            // Return empty service proxy in the case that IMS is not supported.
            sImsSupportedOnDevice = false;
            Rlog.w(TAG, "create: IMS is not supported");
            return serviceProxy;
        }

        TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            Rlog.w(TAG, "create: TelephonyManager is null");
            return serviceProxy;
        }

        IImsRcsFeature binder = tm.getImsRcsFeatureAndListen(slotId, serviceProxy.getListener());
        if (binder != null) {
            Rlog.d(TAG, "create: set binder");
            serviceProxy.setBinder(binder.asBinder());
            // Trigger the cache to be updated for feature status.
            serviceProxy.getFeatureState();
        } else {
            Rlog.i(TAG, "create: binder is null! Slot Id: " + slotId);
        }
        return serviceProxy;
    }

    @VisibleForTesting
    public AvailabilityCallbackManager mAvailabilityCallbackManager;
    @VisibleForTesting
    public RegistrationCallbackManager mRegistrationCallbackManager;

    private RcsFeatureConnection(Context context, int slotId, IFeatureUpdate callback) {
        super(context, slotId);
        setStatusCallback(callback);
        mAvailabilityCallbackManager = new AvailabilityCallbackManager(mContext);
        mRegistrationCallbackManager = new RegistrationCallbackManager(mContext);
    }

    public void close() {
        removeRcsFeatureListener();
        mAvailabilityCallbackManager.close();
        mRegistrationCallbackManager.close();
    }

    @Override
    protected void onRemovedOrDied() {
        removeImsFeatureCallback();
        super.onRemovedOrDied();
        synchronized (mLock) {
            close();
        }
    }

    private void removeImsFeatureCallback() {
        TelephonyManager tm = getTelephonyManager();
        if (tm != null) {
            tm.unregisterImsFeatureCallback(mSlotId, ImsFeature.FEATURE_RCS, getListener());
        }
    }

    @Override
    @VisibleForTesting
    public void handleImsFeatureCreatedCallback(int slotId, int feature) {
        logi("IMS feature created: slotId= " + slotId + ", feature=" + feature);
        if (!isUpdateForThisFeatureAndSlot(slotId, feature)) {
            return;
        }
        synchronized(mLock) {
            if (!mIsAvailable) {
                logi("RCS enabled on slotId: " + slotId);
                mIsAvailable = true;
            }
        }
    }

    @Override
    @VisibleForTesting
    public void handleImsFeatureRemovedCallback(int slotId, int feature) {
        logi("IMS feature removed: slotId= " + slotId + ", feature=" + feature);
        if (!isUpdateForThisFeatureAndSlot(slotId, feature)) {
            return;
        }
        synchronized(mLock) {
            logi("Rcs UCE removed on slotId: " + slotId);
            onRemovedOrDied();
        }
    }

    @Override
    @VisibleForTesting
    public void handleImsStatusChangedCallback(int slotId, int feature, int status) {
        logi("IMS status changed: slotId=" + slotId + ", feature=" + feature + ", status="
                + status);
        if (!isUpdateForThisFeatureAndSlot(slotId, feature)) {
            return;
        }
        synchronized(mLock) {
            mFeatureStateCached = status;
        }
    }

    private boolean isUpdateForThisFeatureAndSlot(int slotId, int feature) {
        if (mSlotId == slotId && feature == ImsFeature.FEATURE_RCS) {
            return true;
        }
        return false;
    }

    public void setRcsFeatureListener(IRcsFeatureListener listener) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).setListener(listener);
        }
    }

    public void removeRcsFeatureListener() {
        try {
            setRcsFeatureListener(null);
        } catch (RemoteException e) {
            // If we are not still connected, there is no need to fail removing.
        }
    }

    public int queryCapabilityStatus() throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).queryCapabilityStatus();
        }
    }

    public void addCallbackForSubscription(int subId, IImsCapabilityCallback cb) {
        mAvailabilityCallbackManager.addCallbackForSubscription(cb, subId);
    }

    public void addCallbackForSubscription(int subId, IImsRegistrationCallback cb) {
        mRegistrationCallbackManager.addCallbackForSubscription(cb, subId);
    }

    public void addCallback(IImsRegistrationCallback cb) {
        mRegistrationCallbackManager.addCallback(cb);
    }

    public void removeCallbackForSubscription(int subId, IImsCapabilityCallback cb) {
        mAvailabilityCallbackManager.removeCallbackForSubscription(cb, subId);
    }

    public void removeCallbackForSubscription(int subId, IImsRegistrationCallback cb) {
        mRegistrationCallbackManager.removeCallbackForSubscription(cb, subId);
    }

    public void removeCallback(IImsRegistrationCallback cb) {
        mRegistrationCallbackManager.removeCallback(cb);
    }

    // Add callback to remote service
    private void addCapabilityCallback(IImsCapabilityCallback callback) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).addCapabilityCallback(callback);
        }
    }

    // Remove callback to remote service
    private void removeCapabilityCallback(IImsCapabilityCallback callback) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).removeCapabilityCallback(callback);
        }
    }

    public void queryCapabilityConfiguration(int capability, int radioTech,
            IImsCapabilityCallback c) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).queryCapabilityConfiguration(capability, radioTech, c);
        }
    }

    public void changeEnabledCapabilities(CapabilityChangeRequest request,
            IImsCapabilityCallback callback) throws RemoteException {
        synchronized (mLock) {
            checkServiceIsReady();
            getServiceInterface(mBinder).changeCapabilitiesConfiguration(request, callback);
        }
    }

    @Override
    @VisibleForTesting
    public Integer retrieveFeatureState() {
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
        return  tm != null ? tm.getImsRegistration(mSlotId, ImsFeature.FEATURE_RCS) : null;
    }

    @VisibleForTesting
    public IImsRcsFeature getServiceInterface(IBinder b) {
        return IImsRcsFeature.Stub.asInterface(b);
    }
    private void log(String s) {
        Rlog.d(TAG + " [" + mSlotId + "]", s);
    }

    private void logi(String s) {
        Rlog.i(TAG + " [" + mSlotId + "]", s);
    }

    private void loge(String s) {
        Rlog.e(TAG + " [" + mSlotId + "]", s);
    }
}

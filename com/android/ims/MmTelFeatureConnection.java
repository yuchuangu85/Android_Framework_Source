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
import android.annotation.Nullable;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.Rlog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
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
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsServiceFeatureCallback;
import com.android.ims.internal.IImsUt;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * A container of the IImsServiceController binder, which implements all of the ImsFeatures that
 * the platform currently supports: MMTel and RCS.
 * @hide
 */

public class MmTelFeatureConnection {
    protected static final String TAG = "MmTelFeatureConnection";

    // Manages callbacks to the associated MmTelFeature in mMmTelFeatureConnection.
    @VisibleForTesting
    public static abstract class CallbackAdapterManager<T extends IInterface> {
        private static final String TAG = "CallbackAdapterManager";

        private final Context mContext;
        private final Object mLock;
        // Map of sub id -> List<callbacks> for sub id linked callbacks.
        private final SparseArray<Set<T>> mCallbackSubscriptionMap = new SparseArray<>();
        // List of all active callbacks to ImsService
        private final RemoteCallbackList<T> mRemoteCallbacks = new RemoteCallbackList<>();
        @VisibleForTesting
        public SubscriptionManager.OnSubscriptionsChangedListener mSubChangedListener;

        public CallbackAdapterManager(Context context, Object lock) {
            mContext = context;
            mLock = lock;
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            // Must be created after Looper.prepare() is called, or else we will get an exception.
            mSubChangedListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    SubscriptionManager manager = mContext.getSystemService(
                            SubscriptionManager.class);
                    if (manager == null) {
                        Log.w(TAG, "onSubscriptionsChanged: could not find SubscriptionManager.");
                        return;
                    }
                    List<SubscriptionInfo> subInfos = manager.getActiveSubscriptionInfoList(false);
                    if (subInfos == null) {
                        subInfos = Collections.emptyList();
                    }
                    Set<Integer> newSubIds = subInfos.stream()
                            .map(SubscriptionInfo::getSubscriptionId)
                            .collect(Collectors.toSet());
                    synchronized (mLock) {
                        Set<Integer> storedSubIds = new ArraySet<>(mCallbackSubscriptionMap.size());
                        for (int keyIndex = 0; keyIndex < mCallbackSubscriptionMap.size();
                                keyIndex++) {
                            storedSubIds.add(mCallbackSubscriptionMap.keyAt(keyIndex));
                        }
                        // Get the set of sub ids that are in storedSubIds that are not in newSubIds.
                        // This is the set of sub ids that need to be removed.
                        storedSubIds.removeAll(newSubIds);
                        for (Integer subId : storedSubIds) {
                            removeCallbacksForSubscription(subId);
                        }
                    }
                }
            };

        }

        // Add a callback to the MmTelFeature associated with this manager (independent of the)
        // current subscription.
        public final void addCallback(T localCallback) {
            synchronized (mLock) {
                // Skip registering to callback subscription map here, because we are registering
                // for the slot, independent of subscription (deprecated behavior).
                // Throws a IllegalStateException if this registration fails.
                registerCallback(localCallback);
                Log.i(TAG, "Local callback added: " + localCallback);
                mRemoteCallbacks.register(localCallback);
            }
        }

        // Add a callback to be associated with a subscription. If that subscription is removed,
        // remove the callback and notify the callback that the subscription has been removed.
        public final void addCallbackForSubscription(T localCallback, int subId) {
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                return;
            }
            synchronized (mLock) {
                addCallback(localCallback);
                linkCallbackToSubscription(localCallback, subId);
            }
        }

        // Removes a callback associated with the MmTelFeature.
        public final void removeCallback(T localCallback) {
            Log.i(TAG, "Local callback removed: " + localCallback);
            synchronized (mLock) {
                if (mRemoteCallbacks.unregister(localCallback)) {
                    // Will only occur if we have record of this callback in mRemoteCallbacks.
                    unregisterCallback(localCallback);
                }
            }
        }

        // Remove an existing callback that has been linked to a subscription.
        public final void removeCallbackForSubscription(T localCallback, int subId) {
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                return;
            }
            synchronized (mLock) {
                removeCallback(localCallback);
                unlinkCallbackFromSubscription(localCallback, subId);
            }
        }

        // Links a callback to be tracked by a subscription. If it goes away, emove.
        private void linkCallbackToSubscription(T callback, int subId) {
            synchronized (mLock) {
                if (mCallbackSubscriptionMap.size() == 0) {
                    // we are about to add the first entry to the map, register for subscriptions
                    //changed listener.
                    registerForSubscriptionsChanged();
                }
                Set<T> callbacksPerSub = mCallbackSubscriptionMap.get(subId);
                if (callbacksPerSub == null) {
                    // the callback list has not been created yet for this subscription.
                    callbacksPerSub = new ArraySet<>();
                    mCallbackSubscriptionMap.put(subId, callbacksPerSub);
                }
                callbacksPerSub.add(callback);
            }
        }

        // Unlink the callback from the associated subscription.
        private void unlinkCallbackFromSubscription(T callback, int subId) {
            synchronized (mLock) {
                Set<T> callbacksPerSub = mCallbackSubscriptionMap.get(subId);
                if (callbacksPerSub != null) {
                    callbacksPerSub.remove(callback);
                    if (callbacksPerSub.isEmpty()) {
                        mCallbackSubscriptionMap.remove(subId);
                    }
                }
                if (mCallbackSubscriptionMap.size() == 0) {
                    unregisterForSubscriptionsChanged();
                }
            }
        }

        // Removes all of the callbacks that have been registered to the subscription specified.
        // This happens when Telephony sends an indication that the subscriptions have changed.
        private void removeCallbacksForSubscription(int subId) {
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                return;
            }
            synchronized (mLock) {
                Set<T> callbacksPerSub = mCallbackSubscriptionMap.get(subId);
                if (callbacksPerSub == null) {
                    // no callbacks registered for this subscription.
                    return;
                }
                // clear all registered callbacks in the subscription map for this subscription.
                mCallbackSubscriptionMap.remove(subId);
                for (T callback : callbacksPerSub) {
                    removeCallback(callback);
                }
                // If there are no more callbacks being tracked, remove subscriptions changed
                // listener.
                if (mCallbackSubscriptionMap.size() == 0) {
                    unregisterForSubscriptionsChanged();
                }
            }
        }

        // Clear the Subscription -> Callback map because the ImsService connection is no longer
        // current.
        private void clearCallbacksForAllSubscriptions() {
            synchronized (mLock) {
                List<Integer> keys = new ArrayList<>();
                for (int keyIndex = 0; keyIndex < mCallbackSubscriptionMap.size(); keyIndex++) {
                    keys.add(mCallbackSubscriptionMap.keyAt(keyIndex));
                }
                keys.forEach(this::removeCallbacksForSubscription);
            }
        }

        private void registerForSubscriptionsChanged() {
            SubscriptionManager manager = mContext.getSystemService(SubscriptionManager.class);
            if (manager != null) {
                manager.addOnSubscriptionsChangedListener(mSubChangedListener);
            } else {
                Log.w(TAG, "registerForSubscriptionsChanged: could not find SubscriptionManager.");
            }
        }

        private void unregisterForSubscriptionsChanged() {
            SubscriptionManager manager = mContext.getSystemService(SubscriptionManager.class);
            if (manager != null) {
            manager.removeOnSubscriptionsChangedListener(mSubChangedListener);
            } else {
                Log.w(TAG, "unregisterForSubscriptionsChanged: could not find"
                        + " SubscriptionManager.");
            }
        }

        // The ImsService these callbacks are registered to has become unavailable or crashed, or
        // the ImsResolver has switched to a new ImsService. In these cases, clean up all existing
        // callbacks.
        public final void close() {
            synchronized (mLock) {
                final int lastCallbackIndex = mRemoteCallbacks.getRegisteredCallbackCount() - 1;
                for(int ii = lastCallbackIndex; ii >= 0; ii --) {
                    T callbackItem = mRemoteCallbacks.getRegisteredCallbackItem(ii);
                    unregisterCallback(callbackItem);
                    mRemoteCallbacks.unregister(callbackItem);
                }
                clearCallbacksForAllSubscriptions();
                Log.i(TAG, "Closing connection and clearing callbacks");
            }
        }

        // A callback has been registered. Register that callback with the MmTelFeature.
        public abstract void registerCallback(T localCallback);

        // A callback has been removed, unregister that callback with the MmTelFeature.
        public abstract void unregisterCallback(T localCallback);
    }

    private class ImsRegistrationCallbackAdapter extends
            CallbackAdapterManager<IImsRegistrationCallback> {

        public ImsRegistrationCallbackAdapter(Context context, Object lock) {
            super(context, lock);
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
                Log.e(TAG, "ImsRegistrationCallbackAdapter: ImsRegistration is null");
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
                    Log.w(TAG, "ImsRegistrationCallbackAdapter - unregisterCallback: couldn't"
                            + " remove registration callback");
                }
            } else {
                Log.e(TAG, "ImsRegistrationCallbackAdapter: ImsRegistration is null");
            }
        }
    }

    private class CapabilityCallbackManager extends CallbackAdapterManager<IImsCapabilityCallback> {

        public CapabilityCallbackManager(Context context, Object lock) {
            super(context, lock);
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
                Log.w(TAG, "CapabilityCallbackManager, register: Couldn't get binder");
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
                    Log.w(TAG, "CapabilityCallbackManager, unregister: couldn't get binder.");
                    return;
                }
            }
            if (binder != null) {
                try {
                    binder.removeCapabilityCallback(localCallback);
                } catch (RemoteException e) {
                    Log.w(TAG, "CapabilityCallbackManager, unregister: Binder is dead.");
                }
            } else {
                Log.w(TAG, "CapabilityCallbackManager, unregister: binder is null.");
            }
        }
    }

    private class ProvisioningCallbackManager extends CallbackAdapterManager<IImsConfigCallback> {
        public ProvisioningCallbackManager (Context context, Object lock) {
            super(context, lock);
        }

        @Override
        public void registerCallback(IImsConfigCallback localCallback) {
            IImsConfig binder = getConfigInterface();
            if (binder == null) {
                // Config interface is not currently available.
                Log.w(TAG, "ProvisioningCallbackManager - couldn't register, binder is null.");
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
                Log.w(TAG, "ProvisioningCallbackManager - couldn't unregister, binder is null.");
                return;
            }
            try {
                binder.removeImsConfigCallback(localCallback);
            } catch (RemoteException e) {
                Log.w(TAG, "ProvisioningCallbackManager - couldn't unregister, binder is dead.");
            }
        }
    }

    protected final int mSlotId;
    protected IBinder mBinder;
    private Context mContext;
    private Executor mExecutor;

    private volatile boolean mIsAvailable = false;
    // ImsFeature Status from the ImsService. Cached.
    private Integer mFeatureStateCached = null;
    private IFeatureUpdate mStatusCallback;
    private final Object mLock = new Object();
    // Updated by IImsServiceFeatureCallback when FEATURE_EMERGENCY_MMTEL is sent.
    private boolean mSupportsEmergencyCalling = false;
    private static boolean sImsSupportedOnDevice = true;

    // Cache the Registration and Config interfaces as long as the MmTel feature is connected. If
    // it becomes disconnected, invalidate.
    private IImsRegistration mRegistrationBinder;
    private IImsConfig mConfigBinder;

    private final IBinder.DeathRecipient mDeathRecipient = () -> {
        Log.w(TAG, "DeathRecipient triggered, binder died.");
        if (mContext != null && Looper.getMainLooper() != null) {
            // Move this signal to the main thread, notifying ImsManager of the Binder
            // death on another thread may lead to deadlocks.
            mContext.getMainExecutor().execute(this::onRemovedOrDied);
            return;
        }
        // No choice - execute on the current Binder thread.
        onRemovedOrDied();
    };

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

        TelephonyManager tm  = getTelephonyManager(context);
        if (tm == null) {
            Rlog.w(TAG, "create: TelephonyManager is null!");
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
            Rlog.w(TAG, "create: binder is null! Slot Id: " + slotId);
        }
        return serviceProxy;
    }

    public static TelephonyManager getTelephonyManager(Context context) {
        return (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public interface IFeatureUpdate {
        /**
         * Called when the ImsFeature has changed its state. Use
         * {@link ImsFeature#getFeatureState()} to get the new state.
         */
        void notifyStateChanged();

        /**
         * Called when the ImsFeature has become unavailable due to the binder switching or app
         * crashing. A new ImsServiceProxy should be requested for that feature.
         */
        void notifyUnavailable();
    }

    private final IImsServiceFeatureCallback mListenerBinder =
            new IImsServiceFeatureCallback.Stub() {

        @Override
        public void imsFeatureCreated(int slotId, int feature) {
                mExecutor.execute(() -> {
                // The feature has been enabled. This happens when the feature is first created and
                // may happen when the feature is re-enabled.
                synchronized (mLock) {
                    if(mSlotId != slotId) {
                        return;
                    }
                    switch (feature) {
                        case ImsFeature.FEATURE_MMTEL: {
                            if (!mIsAvailable) {
                                Log.i(TAG, "MmTel enabled on slotId: " + slotId);
                                mIsAvailable = true;
                            }
                            break;
                        }
                        case ImsFeature.FEATURE_EMERGENCY_MMTEL: {
                            mSupportsEmergencyCalling = true;
                            Log.i(TAG, "Emergency calling enabled on slotId: " + slotId);
                            break;
                        }
                    }
                }
            });
        }

        @Override
        public void imsFeatureRemoved(int slotId, int feature) {
            mExecutor.execute(() -> {
                synchronized (mLock) {
                    if (mSlotId != slotId) {
                        return;
                    }
                    switch (feature) {
                        case ImsFeature.FEATURE_MMTEL: {
                            Log.i(TAG, "MmTel removed on slotId: " + slotId);
                            onRemovedOrDied();
                            break;
                        }
                        case ImsFeature.FEATURE_EMERGENCY_MMTEL: {
                            mSupportsEmergencyCalling = false;
                            Log.i(TAG, "Emergency calling disabled on slotId: " + slotId);
                            break;
                        }
                    }
                }
            });
        }

        @Override
        public void imsStatusChanged(int slotId, int feature, int status) {
            mExecutor.execute(() -> {
                synchronized (mLock) {
                    Log.i(TAG, "imsStatusChanged: slot: " + slotId + " feature: " + feature +
                            " status: " + status);
                    if (mSlotId == slotId && feature == ImsFeature.FEATURE_MMTEL) {
                        mFeatureStateCached = status;
                        if (mStatusCallback != null) {
                            mStatusCallback.notifyStateChanged();
                        }
                    }
                }
            });
        }
    };

    public MmTelFeatureConnection(Context context, int slotId) {
        mSlotId = slotId;
        mContext = context;
        // Callbacks should be scheduled on the main thread.
        if (context.getMainLooper() != null) {
            mExecutor = context.getMainExecutor();
        } else {
            // Fallback to the current thread.
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            mExecutor = new HandlerExecutor(new Handler(Looper.myLooper()));
        }
        mRegistrationCallbackManager = new ImsRegistrationCallbackAdapter(context, mLock);
        mCapabilityCallbackManager = new CapabilityCallbackManager(context, mLock);
        mProvisioningCallbackManager = new ProvisioningCallbackManager(context, mLock);
    }

    /**
     * Called when the MmTelFeature has either been removed by Telephony or crashed.
     */
    private void onRemovedOrDied() {
        synchronized (mLock) {
            mRegistrationCallbackManager.close();
            mCapabilityCallbackManager.close();
            mProvisioningCallbackManager.close();
            if (mIsAvailable) {
                mIsAvailable = false;
                // invalidate caches.
                mRegistrationBinder = null;
                mConfigBinder = null;
                if (mBinder != null) {
                    mBinder.unlinkToDeath(mDeathRecipient, 0);
                }
                if (mStatusCallback != null) {
                    mStatusCallback.notifyUnavailable();
                }
            }
        }
    }

    private @Nullable IImsRegistration getRegistration() {
        synchronized (mLock) {
            // null if cache is invalid;
            if (mRegistrationBinder != null) {
                return mRegistrationBinder;
            }
        }
        TelephonyManager tm = getTelephonyManager(mContext);
        // We don't want to synchronize on a binder call to another process.
        IImsRegistration regBinder = tm != null
                ? tm.getImsRegistration(mSlotId, ImsFeature.FEATURE_MMTEL) : null;
        synchronized (mLock) {
            // mRegistrationBinder may have changed while we tried to get the registration
            // interface.
            if (mRegistrationBinder == null) {
                mRegistrationBinder = regBinder;
            }
        }
        return mRegistrationBinder;
    }

    private IImsConfig getConfig() {
        synchronized (mLock) {
            // null if cache is invalid;
            if (mConfigBinder != null) {
                return mConfigBinder;
            }
        }
        TelephonyManager tm = getTelephonyManager(mContext);
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

    public boolean isEmergencyMmTelAvailable() {
        return mSupportsEmergencyCalling;
    }

    public IImsServiceFeatureCallback getListener() {
        return mListenerBinder;
    }

    public void setBinder(IBinder binder) {
        synchronized (mLock) {
            mBinder = binder;
            try {
                if (mBinder != null) {
                    mBinder.linkToDeath(mDeathRecipient, 0);
                }
            } catch (RemoteException e) {
                // No need to do anything if the binder is already dead.
            }
        }
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
            Log.w(TAG, "closeConnection: couldn't remove listener!");
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

    public @ImsRegistrationImplBase.ImsRegistrationTech int getRegistrationTech()
            throws RemoteException {
        IImsRegistration registration = getRegistration();
        if (registration != null) {
                return registration.getRegistrationTechnology();
        } else {
            return ImsRegistrationImplBase.REGISTRATION_TECH_NONE;
        }
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
            Log.i(TAG, "MmTel does not support emergency over IMS, fallback to CS.");
            return MmTelFeature.PROCESS_CALL_CSFB;
        }
        synchronized (mLock) {
            checkServiceIsReady();
            return getServiceInterface(mBinder).shouldProcessCall(numbers);
        }
    }

    /**
     * @return an integer describing the current Feature Status, defined in
     * {@link ImsFeature.ImsState}.
     */
    public int getFeatureState() {
        synchronized (mLock) {
            if (isBinderAlive() && mFeatureStateCached != null) {
                return mFeatureStateCached;
            }
        }
        // Don't synchronize on Binder call.
        Integer status = retrieveFeatureState();
        synchronized (mLock) {
            if (status == null) {
                return ImsFeature.STATE_UNAVAILABLE;
            }
            // Cache only non-null value for feature status.
            mFeatureStateCached = status;
        }
        Log.i(TAG, "getFeatureState - returning " + status);
        return status;
    }

    /**
     * Internal method used to retrieve the feature status from the corresponding ImsService.
     */
    private Integer retrieveFeatureState() {
        if (mBinder != null) {
            try {
                return getServiceInterface(mBinder).getFeatureState();
            } catch (RemoteException e) {
                // Status check failed, don't update cache
            }
        }
        return null;
    }

    /**
     * @param c Callback that will fire when the feature status has changed.
     */
    public void setStatusCallback(IFeatureUpdate c) {
        mStatusCallback = c;
    }

    /**
     * @return Returns true if the ImsService is ready to take commands, false otherwise. If this
     * method returns false, it doesn't mean that the Binder connection is not available (use
     * {@link #isBinderReady()} to check that), but that the ImsService is not accepting commands
     * at this time.
     *
     * For example, for DSDS devices, only one slot can be {@link ImsFeature#STATE_READY} to take
     * commands at a time, so the other slot must stay at {@link ImsFeature#STATE_UNAVAILABLE}.
     */
    public boolean isBinderReady() {
        return isBinderAlive() && getFeatureState() == ImsFeature.STATE_READY;
    }

    /**
     * @return false if the binder connection is no longer alive.
     */
    public boolean isBinderAlive() {
        return mIsAvailable && mBinder != null && mBinder.isBinderAlive();
    }

    private void checkServiceIsReady() throws RemoteException {
        if (!sImsSupportedOnDevice) {
            throw new RemoteException("IMS is not supported on this device.");
        }
        if (!isBinderReady()) {
            throw new RemoteException("ImsServiceProxy is not ready to accept commands.");
        }
    }

    private IImsMmTelFeature getServiceInterface(IBinder b) {
        return IImsMmTelFeature.Stub.asInterface(b);
    }
}

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
 * limitations under the License.
 */

package com.android.internal.telephony.ims;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.IPackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Pair;

import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.ims.internal.IImsServiceController;
import com.android.ims.internal.IImsServiceFeatureListener;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Manages the Binding lifecycle of one ImsService as well as the relevant ImsFeatures that the
 * ImsService will support.
 *
 * When the ImsService is first bound, {@link IImsServiceController#createImsFeature} will be
 * called
 * on each feature that the service supports. For each ImsFeature that is created,
 * {@link ImsServiceControllerCallbacks#imsServiceFeatureCreated} will be called to notify the
 * listener that the ImsService now supports that feature.
 *
 * When {@link #changeImsServiceFeatures} is called with a set of features that is different from
 * the original set, {@link IImsServiceController#createImsFeature} and
 * {@link IImsServiceController#removeImsFeature} will be called for each feature that is
 * created/removed.
 */
public class ImsServiceController {

    class ImsDeathRecipient implements IBinder.DeathRecipient {

        private ComponentName mComponentName;

        ImsDeathRecipient(ComponentName name) {
            mComponentName = name;
        }

        @Override
        public void binderDied() {
            Log.e(LOG_TAG, "ImsService(" + mComponentName + ") died. Restarting...");
            notifyAllFeaturesRemoved();
            cleanUpService();
            startDelayedRebindToService();
        }
    }

    class ImsServiceConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mIsBound = true;
                mIsBinding = false;
                grantPermissionsToService();
                Log.d(LOG_TAG, "ImsService(" + name + "): onServiceConnected with binder: "
                        + service);
                if (service != null) {
                    mImsDeathRecipient = new ImsDeathRecipient(name);
                    try {
                        service.linkToDeath(mImsDeathRecipient, 0);
                        mImsServiceControllerBinder = service;
                        mIImsServiceController = IImsServiceController.Stub.asInterface(service);
                        // create all associated features in the ImsService
                        for (Pair<Integer, Integer> i : mImsFeatures) {
                            addImsServiceFeature(i);
                        }
                    } catch (RemoteException e) {
                        mIsBound = false;
                        mIsBinding = false;
                        // Remote exception means that the binder already died.
                        if (mImsDeathRecipient != null) {
                            mImsDeathRecipient.binderDied();
                        }
                        Log.e(LOG_TAG, "ImsService(" + name + ") RemoteException:"
                                + e.getMessage());
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mIsBinding = false;
            }
            if (mIImsServiceController != null) {
                mImsServiceControllerBinder.unlinkToDeath(mImsDeathRecipient, 0);
            }
            notifyAllFeaturesRemoved();
            cleanUpService();
            Log.w(LOG_TAG, "ImsService(" + name + "): onServiceDisconnected. Rebinding...");
            startDelayedRebindToService();
        }
    }

    /**
     * Defines callbacks that are used by the ImsServiceController to notify when an ImsService
     * has created or removed a new feature as well as the associated ImsServiceController.
     */
    public interface ImsServiceControllerCallbacks {
        /**
         * Called by ImsServiceController when a new feature has been created.
         */
        void imsServiceFeatureCreated(int slotId, int feature, ImsServiceController controller);
        /**
         * Called by ImsServiceController when a new feature has been removed.
         */
        void imsServiceFeatureRemoved(int slotId, int feature, ImsServiceController controller);
    }

    /**
     * Returns the currently defined rebind retry timeout. Used for testing.
     */
    @VisibleForTesting
    public interface RebindRetry {
        /**
         * Return a long in ms indiciating how long the ImsServiceController should wait before
         * rebinding.
         */
        long getRetryTimeout();
    }

    private static final String LOG_TAG = "ImsServiceController";
    private static final int REBIND_RETRY_TIME = 5000;
    private final Context mContext;
    private final ComponentName mComponentName;
    private final Object mLock = new Object();
    private final HandlerThread mHandlerThread = new HandlerThread("ImsServiceControllerHandler");
    private final IPackageManager mPackageManager;
    private ImsServiceControllerCallbacks mCallbacks;
    private Handler mHandler;

    private boolean mIsBound = false;
    private boolean mIsBinding = false;
    // Set of a pair of slotId->feature
    private HashSet<Pair<Integer, Integer>> mImsFeatures;
    private IImsServiceController mIImsServiceController;
    // Easier for testing.
    private IBinder mImsServiceControllerBinder;
    private ImsServiceConnection mImsServiceConnection;
    private ImsDeathRecipient mImsDeathRecipient;
    private Set<IImsServiceFeatureListener> mImsStatusCallbacks = new HashSet<>();
    // Only added or removed, never accessed on purpose.
    private Set<ImsFeatureStatusCallback> mFeatureStatusCallbacks = new HashSet<>();

    /**
     * Container class for the IImsFeatureStatusCallback callback implementation. This class is
     * never used directly, but we need to keep track of the IImsFeatureStatusCallback
     * implementations explicitly.
     */
    private class ImsFeatureStatusCallback {
        private int mSlotId;
        private int mFeatureType;

        private final IImsFeatureStatusCallback mCallback = new IImsFeatureStatusCallback.Stub() {

            @Override
            public void notifyImsFeatureStatus(int featureStatus) throws RemoteException {
                Log.i(LOG_TAG, "notifyImsFeatureStatus: slot=" + mSlotId + ", feature="
                        + mFeatureType + ", status=" + featureStatus);
                sendImsFeatureStatusChanged(mSlotId, mFeatureType, featureStatus);
            }
        };

        ImsFeatureStatusCallback(int slotId, int featureType) {
            mSlotId = slotId;
            mFeatureType = featureType;
        }

        public IImsFeatureStatusCallback getCallback() {
            return mCallback;
        }
    }

    // Retry the bind to the ImsService that has died after mRebindRetry timeout.
    private Runnable mRestartImsServiceRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (mIsBound) {
                    return;
                }
                bind(mImsFeatures);
            }
        }
    };

    private RebindRetry mRebindRetry = () -> REBIND_RETRY_TIME;

    @VisibleForTesting
    public void setRebindRetryTime(RebindRetry retry) {
        mRebindRetry = retry;
    }

    @VisibleForTesting
    public Handler getHandler() {
        return mHandler;
    }

    public ImsServiceController(Context context, ComponentName componentName,
            ImsServiceControllerCallbacks callbacks) {
        mContext = context;
        mComponentName = componentName;
        mCallbacks = callbacks;
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mPackageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    }

    @VisibleForTesting
    // Creating a new HandlerThread and background handler for each test causes a segfault, so for
    // testing, use a handler supplied by the testing system.
    public ImsServiceController(Context context, ComponentName componentName,
            ImsServiceControllerCallbacks callbacks, Handler testHandler) {
        mContext = context;
        mComponentName = componentName;
        mCallbacks = callbacks;
        mHandler = testHandler;
        mPackageManager = null;
    }

    /**
     * Sends request to bind to ImsService designated by the {@ComponentName} with the feature set
     * imsFeatureSet
     *
     * @param imsFeatureSet a Set of Pairs that designate the slotId->featureId that need to be
     *                      created once the service is bound.
     * @return {@link true} if the service is in the process of being bound, {@link false} if it
     * has failed.
     */
    public boolean bind(HashSet<Pair<Integer, Integer>> imsFeatureSet) {
        synchronized (mLock) {
            // Remove pending rebind retry
            mHandler.removeCallbacks(mRestartImsServiceRunnable);
            if (!mIsBound && !mIsBinding) {
                mIsBinding = true;
                mImsFeatures = imsFeatureSet;
                Intent imsServiceIntent = new Intent(ImsResolver.SERVICE_INTERFACE).setComponent(
                        mComponentName);
                mImsServiceConnection = new ImsServiceConnection();
                int serviceFlags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
                        | Context.BIND_IMPORTANT;
                Log.i(LOG_TAG, "Binding ImsService:" + mComponentName);
                try {
                    return mContext.bindService(imsServiceIntent, mImsServiceConnection,
                            serviceFlags);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error binding (" + mComponentName + ") with exception: "
                            + e.getMessage());
                    return false;
                }
            } else {
                return false;
            }
        }
    }

    /**
     * Calls {@link IImsServiceController#removeImsFeature} on all features that the
     * ImsService supports and then unbinds the service.
     */
    public void unbind() throws RemoteException {
        synchronized (mLock) {
            // Remove pending rebind retry
            mHandler.removeCallbacks(mRestartImsServiceRunnable);
            if (mImsServiceConnection == null || mImsDeathRecipient == null) {
                return;
            }
            // Clean up all features
            changeImsServiceFeatures(new HashSet<>());
            removeImsServiceFeatureListener();
            mImsServiceControllerBinder.unlinkToDeath(mImsDeathRecipient, 0);
            Log.i(LOG_TAG, "Unbinding ImsService: " + mComponentName);
            mContext.unbindService(mImsServiceConnection);
            cleanUpService();
        }
    }

    /**
     * Finds the difference between the set of features that the ImsService has active and the new
     * set defined in newImsFeatures. For every feature that is added,
     * {@link IImsServiceController#createImsFeature} is called on the service. For every ImsFeature
     * that is removed, {@link IImsServiceController#removeImsFeature} is called.
     */
    public void changeImsServiceFeatures(HashSet<Pair<Integer, Integer>> newImsFeatures)
            throws RemoteException {
        synchronized (mLock) {
            if (mIsBound) {
                // add features to service.
                HashSet<Pair<Integer, Integer>> newFeatures = new HashSet<>(newImsFeatures);
                newFeatures.removeAll(mImsFeatures);
                for (Pair<Integer, Integer> i : newFeatures) {
                    addImsServiceFeature(i);
                }
                // remove old features
                HashSet<Pair<Integer, Integer>> oldFeatures = new HashSet<>(mImsFeatures);
                oldFeatures.removeAll(newImsFeatures);
                for (Pair<Integer, Integer> i : oldFeatures) {
                    removeImsServiceFeature(i);
                }
            }
            Log.i(LOG_TAG, "Features changed (" + mImsFeatures + "->" + newImsFeatures + ") for "
                    + "ImsService: " + mComponentName);
            mImsFeatures = newImsFeatures;
        }
    }

    @VisibleForTesting
    public IImsServiceController getImsServiceController() {
        return mIImsServiceController;
    }

    @VisibleForTesting
    public IBinder getImsServiceControllerBinder() {
        return mImsServiceControllerBinder;
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Add a callback to ImsManager that signals a new feature that the ImsServiceProxy can handle.
     */
    public void addImsServiceFeatureListener(IImsServiceFeatureListener callback) {
        synchronized (mLock) {
            mImsStatusCallbacks.add(callback);
        }
    }

    private void removeImsServiceFeatureListener() {
        synchronized (mLock) {
            mImsStatusCallbacks.clear();
        }
    }

    // Only add a new rebind if there are no pending rebinds waiting.
    private void startDelayedRebindToService() {
        if (!mHandler.hasCallbacks(mRestartImsServiceRunnable)) {
            mHandler.postDelayed(mRestartImsServiceRunnable, mRebindRetry.getRetryTimeout());
        }
    }

    // Grant runtime permissions to ImsService. PackageManager ensures that the ImsService is
    // system/signed before granting permissions.
    private void grantPermissionsToService() {
        Log.i(LOG_TAG, "Granting Runtime permissions to:" + getComponentName());
        String[] pkgToGrant = {mComponentName.getPackageName()};
        try {
            if (mPackageManager != null) {
                mPackageManager.grantDefaultPermissionsToEnabledImsServices(pkgToGrant,
                        mContext.getUserId());
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Unable to grant permissions, binder died.");
        }
    }

    private void sendImsFeatureCreatedCallback(int slot, int feature) {
        synchronized (mLock) {
            for (Iterator<IImsServiceFeatureListener> i = mImsStatusCallbacks.iterator();
                    i.hasNext(); ) {
                IImsServiceFeatureListener callbacks = i.next();
                try {
                    callbacks.imsFeatureCreated(slot, feature);
                } catch (RemoteException e) {
                    // binder died, remove callback.
                    Log.w(LOG_TAG, "sendImsFeatureCreatedCallback: Binder died, removing "
                            + "callback. Exception:" + e.getMessage());
                    i.remove();
                }
            }
        }
    }

    private void sendImsFeatureRemovedCallback(int slot, int feature) {
        synchronized (mLock) {
            for (Iterator<IImsServiceFeatureListener> i = mImsStatusCallbacks.iterator();
                    i.hasNext(); ) {
                IImsServiceFeatureListener callbacks = i.next();
                try {
                    callbacks.imsFeatureRemoved(slot, feature);
                } catch (RemoteException e) {
                    // binder died, remove callback.
                    Log.w(LOG_TAG, "sendImsFeatureRemovedCallback: Binder died, removing "
                            + "callback. Exception:" + e.getMessage());
                    i.remove();
                }
            }
        }
    }

    private void sendImsFeatureStatusChanged(int slot, int feature, int status) {
        synchronized (mLock) {
            for (Iterator<IImsServiceFeatureListener> i = mImsStatusCallbacks.iterator();
                    i.hasNext(); ) {
                IImsServiceFeatureListener callbacks = i.next();
                try {
                    callbacks.imsStatusChanged(slot, feature, status);
                } catch (RemoteException e) {
                    // binder died, remove callback.
                    Log.w(LOG_TAG, "sendImsFeatureStatusChanged: Binder died, removing "
                            + "callback. Exception:" + e.getMessage());
                    i.remove();
                }
            }
        }
    }

    // This method should only be called when synchronized on mLock
    private void addImsServiceFeature(Pair<Integer, Integer> featurePair) throws RemoteException {
        if (mIImsServiceController == null || mCallbacks == null) {
            Log.w(LOG_TAG, "addImsServiceFeature called with null values.");
            return;
        }
        ImsFeatureStatusCallback c = new ImsFeatureStatusCallback(featurePair.first,
                featurePair.second);
        mFeatureStatusCallbacks.add(c);
        mIImsServiceController.createImsFeature(featurePair.first, featurePair.second,
                c.getCallback());
        // Signal ImsResolver to change supported ImsFeatures for this ImsServiceController
        mCallbacks.imsServiceFeatureCreated(featurePair.first, featurePair.second, this);
        // Send callback to ImsServiceProxy to change supported ImsFeatures
        sendImsFeatureCreatedCallback(featurePair.first, featurePair.second);
    }

    // This method should only be called when synchronized on mLock
    private void removeImsServiceFeature(Pair<Integer, Integer> featurePair)
            throws RemoteException {
        if (mIImsServiceController == null || mCallbacks == null) {
            Log.w(LOG_TAG, "removeImsServiceFeature called with null values.");
            return;
        }
        ImsFeatureStatusCallback callbackToRemove = mFeatureStatusCallbacks.stream().filter(c ->
                c.mSlotId == featurePair.first && c.mFeatureType == featurePair.second)
                .findFirst().orElse(null);
        // Remove status callbacks from list.
        if (callbackToRemove != null) {
            mFeatureStatusCallbacks.remove(callbackToRemove);
        }
        mIImsServiceController.removeImsFeature(featurePair.first, featurePair.second,
                (callbackToRemove != null ? callbackToRemove.getCallback() : null));
        // Signal ImsResolver to change supported ImsFeatures for this ImsServiceController
        mCallbacks.imsServiceFeatureRemoved(featurePair.first, featurePair.second, this);
        // Send callback to ImsServiceProxy to change supported ImsFeatures
        // Ensure that ImsServiceProxy callback occurs after ImsResolver callback. If an
        // ImsManager requests the ImsService while it is being removed in ImsResolver, this
        // callback will clean it up after.
        sendImsFeatureRemovedCallback(featurePair.first, featurePair.second);
    }

    private void notifyAllFeaturesRemoved() {
        if (mCallbacks == null) {
            Log.w(LOG_TAG, "notifyAllFeaturesRemoved called with invalid callbacks.");
            return;
        }
        synchronized (mLock) {
            for (Pair<Integer, Integer> feature : mImsFeatures) {
                mCallbacks.imsServiceFeatureRemoved(feature.first, feature.second, this);
                sendImsFeatureRemovedCallback(feature.first, feature.second);
            }
        }
    }

    private void cleanUpService() {
        synchronized (mLock) {
            mImsDeathRecipient = null;
            mImsServiceConnection = null;
            mImsServiceControllerBinder = null;
            mIImsServiceController = null;
            mIsBound = false;
        }
    }
}

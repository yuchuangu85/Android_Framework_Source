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
import android.os.IInterface;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.ims.ImsService;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsServiceController;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.stub.ImsFeatureConfiguration;
import android.util.Log;

import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.ims.internal.IImsServiceFeatureCallback;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.ExponentialBackoff;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the Binding lifecycle of one ImsService as well as the relevant ImsFeatures that the
 * ImsService will support.
 *
 * When the ImsService is first bound, {@link ImsService#createMmTelFeature(int)} and
 * {@link ImsService#createRcsFeature(int)} will be called
 * on each feature that the service supports. For each ImsFeature that is created,
 * {@link ImsServiceControllerCallbacks#imsServiceFeatureCreated} will be called to notify the
 * listener that the ImsService now supports that feature.
 *
 * When {@link #changeImsServiceFeatures} is called with a set of features that is different from
 * the original set, create and {@link IImsServiceController#removeImsFeature} will be called for
 * each feature that is created/removed.
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
            synchronized (mLock) {
                mIsBinding = false;
                mIsBound = false;
            }
            notifyAllFeaturesRemoved();
            cleanUpService();
            startDelayedRebindToService();
        }
    }

    class ImsServiceConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBackoff.stop();
            synchronized (mLock) {
                mIsBound = true;
                mIsBinding = false;
                Log.d(LOG_TAG, "ImsService(" + name + "): onServiceConnected with binder: "
                        + service);
                if (service != null) {
                    mImsDeathRecipient = new ImsDeathRecipient(name);
                    try {
                        service.linkToDeath(mImsDeathRecipient, 0);
                        mImsServiceControllerBinder = service;
                        setServiceController(service);
                        notifyImsServiceReady();
                        // create all associated features in the ImsService
                        for (ImsFeatureConfiguration.FeatureSlotPair i : mImsFeatures) {
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
            cleanupConnection();
            Log.w(LOG_TAG, "ImsService(" + name + "): onServiceDisconnected. Waiting...");
            // Service disconnected, but we are still technically bound. Waiting for reconnect.
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (mLock) {
                mIsBinding = false;
                mIsBound = false;
            }
            cleanupConnection();
            Log.w(LOG_TAG, "ImsService(" + name + "): onBindingDied. Starting rebind...");
            startDelayedRebindToService();
        }

        private void cleanupConnection() {
            if (isServiceControllerAvailable()) {
                mImsServiceControllerBinder.unlinkToDeath(mImsDeathRecipient, 0);
            }
            notifyAllFeaturesRemoved();
            cleanUpService();
        }
    }

    private ImsService.Listener mFeatureChangedListener = new ImsService.Listener() {
        @Override
        public void onUpdateSupportedImsFeatures(ImsFeatureConfiguration c) {
            if (mCallbacks == null) {
                return;
            }
            mCallbacks.imsServiceFeaturesChanged(c, ImsServiceController.this);
        }
    };

    /**
     * Defines callbacks that are used by the ImsServiceController to notify when an ImsService
     * has created or removed a new feature as well as the associated ImsServiceController.
     */
    public interface ImsServiceControllerCallbacks {
        /**
         * Called by ImsServiceController when a new MMTEL or RCS feature has been created.
         */
        void imsServiceFeatureCreated(int slotId, int feature, ImsServiceController controller);
        /**
         * Called by ImsServiceController when a new MMTEL or RCS feature has been removed.
         */
        void imsServiceFeatureRemoved(int slotId, int feature, ImsServiceController controller);

        /**
         * Called by the ImsServiceController when the ImsService has notified the framework that
         * its features have changed.
         */
        void imsServiceFeaturesChanged(ImsFeatureConfiguration config,
                ImsServiceController controller);
    }

    /**
     * Returns the currently defined rebind retry timeout. Used for testing.
     */
    @VisibleForTesting
    public interface RebindRetry {
        /**
         * Returns a long in ms indicating how long the ImsServiceController should wait before
         * rebinding for the first time.
         */
        long getStartDelay();

        /**
         * Returns a long in ms indicating the maximum time the ImsServiceController should wait
         * before rebinding.
         */
        long getMaximumDelay();
    }

    private static final String LOG_TAG = "ImsServiceController";
    private static final int REBIND_START_DELAY_MS = 2 * 1000; // 2 seconds
    private static final int REBIND_MAXIMUM_DELAY_MS = 60 * 1000; // 1 minute
    private final ComponentName mComponentName;
    private final HandlerThread mHandlerThread = new HandlerThread("ImsServiceControllerHandler");
    private final IPackageManager mPackageManager;
    private ImsServiceControllerCallbacks mCallbacks;
    private ExponentialBackoff mBackoff;

    private boolean mIsBound = false;
    private boolean mIsBinding = false;
    // Set of a pair of slotId->feature
    private HashSet<ImsFeatureConfiguration.FeatureSlotPair> mImsFeatures;
    // Binder interfaces to the features set in mImsFeatures;
    private HashSet<ImsFeatureContainer> mImsFeatureBinders = new HashSet<>();
    private IImsServiceController mIImsServiceController;
    private IBinder mImsServiceControllerBinder;
    private ImsServiceConnection mImsServiceConnection;
    private ImsDeathRecipient mImsDeathRecipient;
    private Set<IImsServiceFeatureCallback> mImsStatusCallbacks = ConcurrentHashMap.newKeySet();
    // Only added or removed, never accessed on purpose.
    private Set<ImsFeatureStatusCallback> mFeatureStatusCallbacks = new HashSet<>();

    protected final Object mLock = new Object();
    protected final Context mContext;

    private class ImsFeatureContainer {
        public int slotId;
        public int featureType;
        private IInterface mBinder;

        ImsFeatureContainer(int slotId, int featureType, IInterface binder) {
            this.slotId = slotId;
            this.featureType = featureType;
            this.mBinder = binder;
        }

        // Casts the IInterface into the binder class we are looking for.
        public <T extends IInterface> T resolve(Class<T> className) {
            return className.cast(mBinder);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ImsFeatureContainer that = (ImsFeatureContainer) o;

            if (slotId != that.slotId) return false;
            if (featureType != that.featureType) return false;
            return mBinder != null ? mBinder.equals(that.mBinder) : that.mBinder == null;
        }

        @Override
        public int hashCode() {
            int result = slotId;
            result = 31 * result + featureType;
            result = 31 * result + (mBinder != null ? mBinder.hashCode() : 0);
            return result;
        }
    }

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

    private RebindRetry mRebindRetry = new RebindRetry() {
        @Override
        public long getStartDelay() {
            return REBIND_START_DELAY_MS;
        }

        @Override
        public long getMaximumDelay() {
            return REBIND_MAXIMUM_DELAY_MS;
        }
    };

    public ImsServiceController(Context context, ComponentName componentName,
            ImsServiceControllerCallbacks callbacks) {
        mContext = context;
        mComponentName = componentName;
        mCallbacks = callbacks;
        mHandlerThread.start();
        mBackoff = new ExponentialBackoff(
                mRebindRetry.getStartDelay(),
                mRebindRetry.getMaximumDelay(),
                2, /* multiplier */
                mHandlerThread.getLooper(),
                mRestartImsServiceRunnable);
        mPackageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    }

    @VisibleForTesting
    // Creating a new HandlerThread and background handler for each test causes a segfault, so for
    // testing, use a handler supplied by the testing system.
    public ImsServiceController(Context context, ComponentName componentName,
            ImsServiceControllerCallbacks callbacks, Handler handler, RebindRetry rebindRetry) {
        mContext = context;
        mComponentName = componentName;
        mCallbacks = callbacks;
        mBackoff = new ExponentialBackoff(
                rebindRetry.getStartDelay(),
                rebindRetry.getMaximumDelay(),
                2, /* multiplier */
                handler,
                mRestartImsServiceRunnable);
        mPackageManager = null;
    }

    /**
     * Sends request to bind to ImsService designated by the {@link ComponentName} with the feature
     * set imsFeatureSet.
     *
     * @param imsFeatureSet a Set of Pairs that designate the slotId->featureId that need to be
     *                      created once the service is bound.
     * @return {@link true} if the service is in the process of being bound, {@link false} if it
     * has failed.
     */
    public boolean bind(HashSet<ImsFeatureConfiguration.FeatureSlotPair> imsFeatureSet) {
        synchronized (mLock) {
            if (!mIsBound && !mIsBinding) {
                mIsBinding = true;
                mImsFeatures = imsFeatureSet;
                grantPermissionsToService();
                Intent imsServiceIntent = new Intent(getServiceInterface()).setComponent(
                        mComponentName);
                mImsServiceConnection = new ImsServiceConnection();
                int serviceFlags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
                        | Context.BIND_IMPORTANT;
                Log.i(LOG_TAG, "Binding ImsService:" + mComponentName);
                try {
                    boolean bindSucceeded = startBindToService(imsServiceIntent,
                            mImsServiceConnection, serviceFlags);
                    if (!bindSucceeded) {
                        mBackoff.notifyFailed();
                    }
                    return bindSucceeded;
                } catch (Exception e) {
                    mBackoff.notifyFailed();
                    Log.e(LOG_TAG, "Error binding (" + mComponentName + ") with exception: "
                            + e.getMessage() + ", rebinding in " + mBackoff.getCurrentDelay()
                            + " ms");
                    return false;
                }
            } else {
                return false;
            }
        }
    }

    /**
     * Starts the bind to the ImsService. Overridden by subclasses that need to access the service
     * in a different fashion.
     */
    protected boolean startBindToService(Intent intent, ImsServiceConnection connection,
            int flags) {
        return mContext.bindService(intent, connection, flags);
    }

    /**
     * Calls {@link IImsServiceController#removeImsFeature} on all features that the
     * ImsService supports and then unbinds the service.
     */
    public void unbind() throws RemoteException {
        synchronized (mLock) {
            mBackoff.stop();
            if (mImsServiceConnection == null || mImsDeathRecipient == null) {
                return;
            }
            // Clean up all features
            changeImsServiceFeatures(new HashSet<>());
            removeImsServiceFeatureCallbacks();
            mImsServiceControllerBinder.unlinkToDeath(mImsDeathRecipient, 0);
            Log.i(LOG_TAG, "Unbinding ImsService: " + mComponentName);
            mContext.unbindService(mImsServiceConnection);
            cleanUpService();
        }
    }

    /**
     * For every feature that is added, the service calls the associated create. For every
     * ImsFeature that is removed, {@link IImsServiceController#removeImsFeature} is called.
     */
    public void changeImsServiceFeatures(
            HashSet<ImsFeatureConfiguration.FeatureSlotPair> newImsFeatures)
            throws RemoteException {
        synchronized (mLock) {
            Log.i(LOG_TAG, "Features changed (" + mImsFeatures + "->" + newImsFeatures + ") for "
                    + "ImsService: " + mComponentName);
            HashSet<ImsFeatureConfiguration.FeatureSlotPair> oldImsFeatures =
                    new HashSet<>(mImsFeatures);
            // Set features first in case we lose binding and need to rebind later.
            mImsFeatures = newImsFeatures;
            if (mIsBound) {
                // add features to service.
                HashSet<ImsFeatureConfiguration.FeatureSlotPair> newFeatures =
                        new HashSet<>(mImsFeatures);
                newFeatures.removeAll(oldImsFeatures);
                for (ImsFeatureConfiguration.FeatureSlotPair i : newFeatures) {
                    addImsServiceFeature(i);
                }
                // remove old features
                HashSet<ImsFeatureConfiguration.FeatureSlotPair> oldFeatures =
                        new HashSet<>(oldImsFeatures);
                oldFeatures.removeAll(mImsFeatures);
                for (ImsFeatureConfiguration.FeatureSlotPair i : oldFeatures) {
                    removeImsServiceFeature(i);
                }
            }
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

    @VisibleForTesting
    public long getRebindDelay() {
        return mBackoff.getCurrentDelay();
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Add a callback to ImsManager that signals a new feature that the ImsServiceProxy can handle.
     */
    public void addImsServiceFeatureCallback(IImsServiceFeatureCallback callback) {
        mImsStatusCallbacks.add(callback);
        synchronized (mLock) {
            if (mImsFeatures == null || mImsFeatures.isEmpty()) {
                return;
            }
            // notify the new status callback of the features that are available.
            try {
                for (ImsFeatureConfiguration.FeatureSlotPair i : mImsFeatures) {
                    callback.imsFeatureCreated(i.slotId, i.featureType);
                }
            } catch (RemoteException e) {
                Log.w(LOG_TAG, "addImsServiceFeatureCallback: exception notifying callback");
            }
        }
    }

    public void enableIms(int slotId) {
        try {
            synchronized (mLock) {
                if (isServiceControllerAvailable()) {
                    mIImsServiceController.enableIms(slotId);
                }
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Couldn't enable IMS: " + e.getMessage());
        }
    }

    public void disableIms(int slotId) {
        try {
            synchronized (mLock) {
                if (isServiceControllerAvailable()) {
                    mIImsServiceController.disableIms(slotId);
                }
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Couldn't disable IMS: " + e.getMessage());
        }
    }

    /**
     * Return the {@Link MMTelFeature} binder on the slot associated with the slotId.
     * Used for normal calling.
     */
    public IImsMmTelFeature getMmTelFeature(int slotId) {
        synchronized (mLock) {
            ImsFeatureContainer f = getImsFeatureContainer(slotId, ImsFeature.FEATURE_MMTEL);
            if (f == null) {
                Log.w(LOG_TAG, "Requested null MMTelFeature on slot " + slotId);
                return null;
            }
            return f.resolve(IImsMmTelFeature.class);
        }
    }

    /**
     * Return the {@Link RcsFeature} binder on the slot associated with the slotId.
     */
    public IImsRcsFeature getRcsFeature(int slotId) {
        synchronized (mLock) {
            ImsFeatureContainer f = getImsFeatureContainer(slotId, ImsFeature.FEATURE_RCS);
            if (f == null) {
                Log.w(LOG_TAG, "Requested null RcsFeature on slot " + slotId);
                return null;
            }
            return f.resolve(IImsRcsFeature.class);
        }
    }

    /**
     * @return the IImsRegistration that corresponds to the slot id specified.
     */
    public IImsRegistration getRegistration(int slotId) throws RemoteException {
        synchronized (mLock) {
            return isServiceControllerAvailable()
                    ? mIImsServiceController.getRegistration(slotId) : null;
        }
    }

    /**
     * @return the IImsConfig that corresponds to the slot id specified.
     */
    public IImsConfig getConfig(int slotId) throws RemoteException {
        synchronized (mLock) {
            return isServiceControllerAvailable() ? mIImsServiceController.getConfig(slotId) : null;
        }
    }

    /**
     * notify the ImsService that the ImsService is ready for feature creation.
     */
    protected void notifyImsServiceReady() throws RemoteException {
        synchronized (mLock) {
            if (isServiceControllerAvailable()) {
                Log.d(LOG_TAG, "notifyImsServiceReady");
                mIImsServiceController.setListener(mFeatureChangedListener);
                mIImsServiceController.notifyImsServiceReadyForFeatureCreation();
            }
        }
    }

    protected String getServiceInterface() {
        return ImsService.SERVICE_INTERFACE;
    }

    /**
     * Sets the IImsServiceController instance. Overridden by compat layers to set compatibility
     * versions of this service controller.
     */
    protected void setServiceController(IBinder serviceController) {
        mIImsServiceController = IImsServiceController.Stub.asInterface(serviceController);
    }

    /**
     * @return true if the controller is currently bound.
     */
    public boolean isBound() {
        synchronized (mLock) {
            return mIsBound;
        }
    }

    /**
     * Check to see if the service controller is available, overridden for compat versions,
     * @return true if available, false otherwise;
     */
    protected boolean isServiceControllerAvailable() {
        return mIImsServiceController != null;
    }

    @VisibleForTesting
    public void removeImsServiceFeatureCallbacks() {
            mImsStatusCallbacks.clear();
    }

    // Only add a new rebind if there are no pending rebinds waiting.
    private void startDelayedRebindToService() {
        mBackoff.start();
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
        for (Iterator<IImsServiceFeatureCallback> i = mImsStatusCallbacks.iterator();
                i.hasNext(); ) {
            IImsServiceFeatureCallback callbacks = i.next();
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

    private void sendImsFeatureRemovedCallback(int slot, int feature) {
        for (Iterator<IImsServiceFeatureCallback> i = mImsStatusCallbacks.iterator();
                i.hasNext(); ) {
            IImsServiceFeatureCallback callbacks = i.next();
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

    private void sendImsFeatureStatusChanged(int slot, int feature, int status) {
        for (Iterator<IImsServiceFeatureCallback> i = mImsStatusCallbacks.iterator();
                i.hasNext(); ) {
            IImsServiceFeatureCallback callbacks = i.next();
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

    // This method should only be called when synchronized on mLock
    private void addImsServiceFeature(ImsFeatureConfiguration.FeatureSlotPair featurePair)
            throws RemoteException {
        if (!isServiceControllerAvailable() || mCallbacks == null) {
            Log.w(LOG_TAG, "addImsServiceFeature called with null values.");
            return;
        }
        if (featurePair.featureType != ImsFeature.FEATURE_EMERGENCY_MMTEL) {
            ImsFeatureStatusCallback c = new ImsFeatureStatusCallback(featurePair.slotId,
                    featurePair.featureType);
            mFeatureStatusCallbacks.add(c);
            IInterface f = createImsFeature(featurePair.slotId, featurePair.featureType,
                    c.getCallback());
            addImsFeatureBinder(featurePair.slotId, featurePair.featureType, f);
            // Signal ImsResolver to change supported ImsFeatures for this ImsServiceController
            mCallbacks.imsServiceFeatureCreated(featurePair.slotId, featurePair.featureType, this);
        } else {
            // Don't update ImsService for emergency MMTEL feature.
            Log.i(LOG_TAG, "supports emergency calling on slot " + featurePair.slotId);
        }
        // Send callback to ImsServiceProxy to change supported ImsFeatures including emergency
        // MMTEL state.
        sendImsFeatureCreatedCallback(featurePair.slotId, featurePair.featureType);
    }

    // This method should only be called when synchronized on mLock
    private void removeImsServiceFeature(ImsFeatureConfiguration.FeatureSlotPair featurePair)
            throws RemoteException {
        if (!isServiceControllerAvailable() || mCallbacks == null) {
            Log.w(LOG_TAG, "removeImsServiceFeature called with null values.");
            return;
        }
        if (featurePair.featureType != ImsFeature.FEATURE_EMERGENCY_MMTEL) {
            ImsFeatureStatusCallback callbackToRemove = mFeatureStatusCallbacks.stream().filter(c ->
                    c.mSlotId == featurePair.slotId && c.mFeatureType == featurePair.featureType)
                    .findFirst().orElse(null);
            // Remove status callbacks from list.
            if (callbackToRemove != null) {
                mFeatureStatusCallbacks.remove(callbackToRemove);
            }
            removeImsFeature(featurePair.slotId, featurePair.featureType,
                    (callbackToRemove != null ? callbackToRemove.getCallback() : null));
            removeImsFeatureBinder(featurePair.slotId, featurePair.featureType);
            // Signal ImsResolver to change supported ImsFeatures for this ImsServiceController
            mCallbacks.imsServiceFeatureRemoved(featurePair.slotId, featurePair.featureType, this);
        } else {
            // Don't update ImsService for emergency MMTEL feature.
            Log.i(LOG_TAG, "doesn't support emergency calling on slot " + featurePair.slotId);
        }
        // Send callback to ImsServiceProxy to change supported ImsFeatures
        // Ensure that ImsServiceProxy callback occurs after ImsResolver callback. If an
        // ImsManager requests the ImsService while it is being removed in ImsResolver, this
        // callback will clean it up after.
        sendImsFeatureRemovedCallback(featurePair.slotId, featurePair.featureType);
    }

    // This method should only be called when already synchronized on mLock.
    // overridden by compat layer to create features
    protected IInterface createImsFeature(int slotId, int featureType, IImsFeatureStatusCallback c)
            throws RemoteException {
        switch (featureType) {
            case ImsFeature.FEATURE_MMTEL: {
                return mIImsServiceController.createMmTelFeature(slotId, c);
            }
            case ImsFeature.FEATURE_RCS: {
                return mIImsServiceController.createRcsFeature(slotId, c);
            }
            default:
                return null;
        }
    }

    // overridden by compat layer to remove features
    protected void removeImsFeature(int slotId, int featureType, IImsFeatureStatusCallback c)
            throws RemoteException {
        mIImsServiceController.removeImsFeature(slotId, featureType, c);
    }

    // This method should only be called when synchronized on mLock
    private void addImsFeatureBinder(int slotId, int featureType, IInterface b) {
        mImsFeatureBinders.add(new ImsFeatureContainer(slotId, featureType, b));
    }

    // This method should only be called when synchronized on mLock
    private void removeImsFeatureBinder(int slotId, int featureType) {
        ImsFeatureContainer container = mImsFeatureBinders.stream()
                .filter(f-> (f.slotId == slotId && f.featureType == featureType))
                .findFirst().orElse(null);
        if (container != null) {
            mImsFeatureBinders.remove(container);
        }
    }

    private ImsFeatureContainer getImsFeatureContainer(int slotId, int featureType) {
        return mImsFeatureBinders.stream()
                .filter(f-> (f.slotId == slotId && f.featureType == featureType))
                .findFirst().orElse(null);
    }

    private void notifyAllFeaturesRemoved() {
        if (mCallbacks == null) {
            Log.w(LOG_TAG, "notifyAllFeaturesRemoved called with invalid callbacks.");
            return;
        }
        synchronized (mLock) {
            for (ImsFeatureConfiguration.FeatureSlotPair feature : mImsFeatures) {
                if (feature.featureType != ImsFeature.FEATURE_EMERGENCY_MMTEL) {
                    // don't update ImsServiceController for emergency MMTEL.
                    mCallbacks.imsServiceFeatureRemoved(feature.slotId, feature.featureType, this);
                }
                sendImsFeatureRemovedCallback(feature.slotId, feature.featureType);
            }
        }
    }

    private void cleanUpService() {
        synchronized (mLock) {
            mImsDeathRecipient = null;
            mImsServiceConnection = null;
            mImsServiceControllerBinder = null;
            setServiceController(null);
        }
    }
}

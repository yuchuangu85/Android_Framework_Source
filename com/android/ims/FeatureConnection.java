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

import android.annotation.Nullable;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import com.android.ims.internal.IImsServiceFeatureCallback;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.HandlerExecutor;

import java.util.concurrent.Executor;

/**
 * Base class of MmTelFeatureConnection and RcsFeatureConnection.
 */
public abstract class FeatureConnection {
    protected static final String TAG = "FeatureConnection";

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

    protected static boolean sImsSupportedOnDevice = true;

    protected final int mSlotId;
    protected Context mContext;
    protected IBinder mBinder;
    @VisibleForTesting
    public Executor mExecutor;

    // We are assuming the feature is available when started.
    protected volatile boolean mIsAvailable = true;
    // ImsFeature Status from the ImsService. Cached.
    protected Integer mFeatureStateCached = null;
    protected IFeatureUpdate mStatusCallback;
    protected IImsRegistration mRegistrationBinder;
    protected final Object mLock = new Object();

    public FeatureConnection(Context context, int slotId) {
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
    }

    protected TelephonyManager getTelephonyManager() {
        return (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Set the binder which type is IImsMmTelFeature or IImsRcsFeature to connect to MmTelFeature
     * or RcsFeature.
     */
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

    protected final IBinder.DeathRecipient mDeathRecipient = () -> {
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

    /**
     * Called when the MmTelFeature/RcsFeature has either been removed by Telephony or crashed.
     */
    protected void onRemovedOrDied() {
        synchronized (mLock) {
            if (mIsAvailable) {
                mIsAvailable = false;
                mRegistrationBinder = null;
                if (mBinder != null) {
                    mBinder.unlinkToDeath(mDeathRecipient, 0);
                }
                if (mStatusCallback != null) {
                    Log.d(TAG, "onRemovedOrDied: notifyUnavailable");
                    mStatusCallback.notifyUnavailable();
                    // Unlink because this FeatureConnection should no longer send callbacks.
                    mStatusCallback = null;
                }
            }
        }
    }

    /**
     * The listener for ImsManger and RcsFeatureManager to receive IMS feature status changed.
     * @param callback Callback that will fire when the feature status has changed.
     */
    public void setStatusCallback(IFeatureUpdate callback) {
        mStatusCallback = callback;
    }

    @VisibleForTesting
    public IImsServiceFeatureCallback getListener() {
        return mListenerBinder;
    }

    /**
     * The callback to receive ImsFeature status changed.
     */
    private final IImsServiceFeatureCallback mListenerBinder =
        new IImsServiceFeatureCallback.Stub() {
            @Override
            public void imsFeatureCreated(int slotId, int feature) {
                mExecutor.execute(() -> {
                    handleImsFeatureCreatedCallback(slotId, feature);
                });
            }
            @Override
            public void imsFeatureRemoved(int slotId, int feature) {
                mExecutor.execute(() -> {
                    handleImsFeatureRemovedCallback(slotId, feature);
                });
            }
            @Override
            public void imsStatusChanged(int slotId, int feature, int status) {
                mExecutor.execute(() -> {
                    handleImsStatusChangedCallback(slotId, feature, status);
                });
            }
        };

    public @ImsRegistrationImplBase.ImsRegistrationTech int getRegistrationTech()
            throws RemoteException {
        IImsRegistration registration = getRegistration();
        if (registration != null) {
            return registration.getRegistrationTechnology();
        } else {
            Log.w(TAG, "getRegistrationTech: ImsRegistration is null");
            return ImsRegistrationImplBase.REGISTRATION_TECH_NONE;
        }
    }

    public @Nullable IImsRegistration getRegistration() {
        synchronized (mLock) {
            // null if cache is invalid;
            if (mRegistrationBinder != null) {
                return mRegistrationBinder;
            }
        }
        // We don't want to synchronize on a binder call to another process.
        IImsRegistration regBinder = getRegistrationBinder();
        synchronized (mLock) {
            // mRegistrationBinder may have changed while we tried to get the registration
            // interface.
            if (mRegistrationBinder == null) {
                mRegistrationBinder = regBinder;
            }
        }
        return mRegistrationBinder;
    }

    @VisibleForTesting
    public void checkServiceIsReady() throws RemoteException {
        if (!sImsSupportedOnDevice) {
            throw new RemoteException("IMS is not supported on this device.");
        }
        if (!isBinderReady()) {
            throw new RemoteException("ImsServiceProxy is not ready to accept commands.");
        }
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
        Integer state = retrieveFeatureState();
        synchronized (mLock) {
            if (state == null) {
                return ImsFeature.STATE_UNAVAILABLE;
            }
            // Cache only non-null value for feature status.
            mFeatureStateCached = state;
        }
        Log.i(TAG + " [" + mSlotId + "]", "getFeatureState - returning "
                + ImsFeature.STATE_LOG_MAP.get(state));
        return state;
    }

    /**
     * An ImsFeature has been created for this FeatureConnection for the associated
     * {@link ImsFeature.FeatureType}.
     * @param slotId The slot ID associated with the event.
     * @param feature The {@link ImsFeature.FeatureType} associated with the event.
     */
    protected abstract void handleImsFeatureCreatedCallback(int slotId, int feature);

    /**
     * An ImsFeature has been removed for this FeatureConnection for the associated
     * {@link ImsFeature.FeatureType}.
     * @param slotId The slot ID associated with the event.
     * @param feature The {@link ImsFeature.FeatureType} associated with the event.
     */
    protected abstract void handleImsFeatureRemovedCallback(int slotId, int feature);

    /**
     * The status of an ImsFeature has changed for the associated {@link ImsFeature.FeatureType}.
     * @param slotId The slot ID associated with the event.
     * @param feature The {@link ImsFeature.FeatureType} associated with the event.
     * @param status The new {@link ImsFeature.ImsState} associated with the ImsFeature
     */
    protected abstract void handleImsStatusChangedCallback(int slotId, int feature, int status);

    /**
     * Internal method used to retrieve the feature status from the corresponding ImsService.
     */
    protected abstract Integer retrieveFeatureState();

    /**
     * @return The ImsRegistration instance associated with the FeatureConnection.
     */
    protected abstract IImsRegistration getRegistrationBinder();
}

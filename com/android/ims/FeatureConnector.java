/*
 * Copyright (c) 2019 The Android Open Source Project
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

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.feature.ImsFeature;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.HandlerExecutor;
import com.android.telephony.Rlog;

import java.util.concurrent.Executor;

/**
 * Helper class for managing a connection to the ImsFeature manager.
 */
public class FeatureConnector<T extends IFeatureConnector> extends Handler {
    private static final String TAG = "FeatureConnector";
    private static final boolean DBG = false;

    // Initial condition for ims connection retry.
    private static final int IMS_RETRY_STARTING_TIMEOUT_MS = 500; // ms

    // Ceiling bitshift amount for service query timeout, calculated as:
    // 2^mImsServiceRetryCount * IMS_RETRY_STARTING_TIMEOUT_MS, where
    // mImsServiceRetryCount ∊ [0, CEILING_SERVICE_RETRY_COUNT].
    private static final int CEILING_SERVICE_RETRY_COUNT = 6;

    public interface Listener<T> {
        /**
         * Get ImsFeature manager instance
         */
        T getFeatureManager();

        /**
         * ImsFeature manager is connected to the underlying IMS implementation.
         */
        void connectionReady(T manager) throws ImsException;

        /**
         * The underlying IMS implementation is unavailable and can not be used to communicate.
         */
        void connectionUnavailable();
    }

    public interface RetryTimeout {
        int get();
    }

    protected final int mPhoneId;
    protected final Context mContext;
    protected final Executor mExecutor;
    protected final Object mLock = new Object();
    protected final String mLogPrefix;

    @VisibleForTesting
    public Listener<T> mListener;

    // The IMS feature manager which interacts with ImsService
    @VisibleForTesting
    public T mManager;

    protected int mRetryCount = 0;

    @VisibleForTesting
    public RetryTimeout mRetryTimeout = () -> {
        synchronized (mLock) {
            int timeout = (1 << mRetryCount) * IMS_RETRY_STARTING_TIMEOUT_MS;
            if (mRetryCount <= CEILING_SERVICE_RETRY_COUNT) {
                mRetryCount++;
            }
            return timeout;
        }
    };

    public FeatureConnector(Context context, int phoneId, Listener<T> listener,
            String logPrefix) {
        mContext = context;
        mPhoneId = phoneId;
        mListener = listener;
        mExecutor = new HandlerExecutor(this);
        mLogPrefix = logPrefix;
    }

    @VisibleForTesting
    public FeatureConnector(Context context, int phoneId, Listener<T> listener,
            Executor executor, String logPrefix) {
        mContext = context;
        mPhoneId = phoneId;
        mListener= listener;
        mExecutor = executor;
        mLogPrefix = logPrefix;
    }

    @VisibleForTesting
    public FeatureConnector(Context context, int phoneId, Listener<T> listener,
            Executor executor, Looper looper) {
        super(looper);
        mContext = context;
        mPhoneId = phoneId;
        mListener= listener;
        mExecutor = executor;
        mLogPrefix = "?";
    }

    /**
     * Start the creation of a connection to the underlying ImsService implementation. When the
     * service is connected, {@link FeatureConnector.Listener#connectionReady(Object)} will be
     * called with an active instance.
     *
     * If this device does not support an ImsStack (i.e. doesn't support
     * {@link PackageManager#FEATURE_TELEPHONY_IMS} feature), this method will do nothing.
     */
    public void connect() {
        if (DBG) log("connect");
        if (!isSupported()) {
            logw("connect: not supported.");
            return;
        }
        mRetryCount = 0;

        // Send a message to connect to the Ims Service and open a connection through
        // getImsService().
        post(mGetServiceRunnable);
    }

    // Check if this ImsFeature is supported or not.
    private boolean isSupported() {
        return ImsManager.isImsSupportedOnDevice(mContext);
    }

    /**
     * Disconnect from the ImsService Implementation and clean up. When this is complete,
     * {@link FeatureConnector.Listener#connectionUnavailable()} will be called one last time.
     */
    public void disconnect() {
        if (DBG) log("disconnect");
        removeCallbacks(mGetServiceRunnable);
        synchronized (mLock) {
            if (mManager != null) {
                mManager.removeNotifyStatusChangedCallback(mNotifyStatusChangedCallback);
            }
        }
        notifyNotReady();
    }

    private final Runnable mGetServiceRunnable = () -> {
        try {
            createImsService();
        } catch (android.telephony.ims.ImsException e) {
            int errorCode = e.getCode();
            if (DBG) logw("Create IMS service error: " + errorCode);
            if (android.telephony.ims.ImsException.CODE_ERROR_UNSUPPORTED_OPERATION != errorCode) {
                // Retry when error is not CODE_ERROR_UNSUPPORTED_OPERATION
                retryGetImsService();
            }
        }
    };

    @VisibleForTesting
    public void createImsService() throws android.telephony.ims.ImsException {
        synchronized (mLock) {
            if (DBG) log("createImsService");
            mManager = mListener.getFeatureManager();
            // Adding to set, will be safe adding multiple times. If the ImsService is not
            // active yet, this method will throw an ImsException.
            mManager.addNotifyStatusChangedCallbackIfAvailable(mNotifyStatusChangedCallback);
        }
        // Wait for ImsService.STATE_READY to start listening for calls.
        // Call the callback right away for compatibility with older devices that do not use
        // states.
        mNotifyStatusChangedCallback.notifyStateChanged();
    }

    /**
     * Remove callback and re-running mGetServiceRunnable
     */
    public void retryGetImsService() {
        if (mManager != null) {
            // remove callback so we do not receive updates from old ImsServiceProxy when
            // switching between ImsServices.
            mManager.removeNotifyStatusChangedCallback(mNotifyStatusChangedCallback);
            //Leave mImsManager as null, then CallStateException will be thrown when dialing
            mManager = null;
        }

        // Exponential backoff during retry, limited to 32 seconds.
        removeCallbacks(mGetServiceRunnable);
        int timeout = mRetryTimeout.get();
        postDelayed(mGetServiceRunnable, timeout);
        if (DBG) log("retryGetImsService: unavailable, retrying in " + timeout + " ms");
    }

    // Callback fires when IMS Feature changes state
    public FeatureConnection.IFeatureUpdate mNotifyStatusChangedCallback =
            new FeatureConnection.IFeatureUpdate() {
                @Override
                public void notifyStateChanged() {
                    mExecutor.execute(() -> {
                        try {
                            int status = ImsFeature.STATE_UNAVAILABLE;
                            synchronized (mLock) {
                                if (mManager != null) {
                                    status = mManager.getImsServiceState();
                                }
                            }
                            switch (status) {
                                case ImsFeature.STATE_READY: {
                                    notifyReady();
                                    break;
                                }
                                case ImsFeature.STATE_INITIALIZING:
                                    // fall through
                                case ImsFeature.STATE_UNAVAILABLE: {
                                    notifyNotReady();
                                    break;
                                }
                                default: {
                                    logw("Unexpected State! " + status);
                                }
                            }
                        } catch (ImsException e) {
                            // Could not get the ImsService, retry!
                            notifyNotReady();
                            retryGetImsService();
                        }
                    });
                }

                @Override
                public void notifyUnavailable() {
                    mExecutor.execute(() -> {
                        notifyNotReady();
                        retryGetImsService();
                    });
                }
            };

    private void notifyReady() throws ImsException {
        T manager;
        synchronized (mLock) {
            manager = mManager;
        }
        try {
            if (DBG) log("notifyReady");
            mListener.connectionReady(manager);
        }
        catch (ImsException e) {
            if(DBG) log("notifyReady exception: " + e.getMessage());
            throw e;
        }
        // Only reset retry count if connectionReady does not generate an ImsException/
        synchronized (mLock) {
            mRetryCount = 0;
        }
    }

    protected void notifyNotReady() {
        if (DBG) log("notifyNotReady");
        mListener.connectionUnavailable();
    }

    private final void log(String message) {
        Rlog.d(TAG, "[" + mLogPrefix + ", " + mPhoneId + "] " + message);
    }

    private final void logw(String message) {
        Rlog.w(TAG, "[" + mLogPrefix + ", " + mPhoneId + "] " + message);
    }
}

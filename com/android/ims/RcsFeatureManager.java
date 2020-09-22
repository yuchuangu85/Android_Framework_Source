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
import android.net.Uri;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.telephony.ims.aidl.IRcsFeatureListener;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.RcsFeature;
import android.telephony.ims.feature.RcsFeature.RcsImsCapabilities;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.RcsCapabilityExchange;
import android.telephony.ims.stub.RcsPresenceExchangeImplBase;
import android.telephony.ims.stub.RcsSipOptionsImplBase;
import android.util.Log;

import com.android.ims.FeatureConnection.IFeatureUpdate;
import com.android.internal.annotations.VisibleForTesting;
import com.android.telephony.Rlog;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Encapsulates all logic related to the RcsFeature:
 * - Updating RcsFeature capabilities.
 * - Registering/Unregistering availability/registration callbacks.
 * - Querying Registration and Capability information.
 */
public class RcsFeatureManager implements IFeatureConnector {
    private static final String TAG = "RcsFeatureManager";
    private static boolean DBG = true;

    private static final int CAPABILITY_OPTIONS = RcsImsCapabilities.CAPABILITY_TYPE_OPTIONS_UCE;
    private static final int CAPABILITY_PRESENCE = RcsImsCapabilities.CAPABILITY_TYPE_PRESENCE_UCE;

    /**
     * Callbacks from the RcsFeature, which have an empty default implementation and can be
     * overridden for each Feature.
     */
    public static class RcsFeatureCallbacks {
        /** See {@link RcsCapabilityExchange#onCommandUpdate(int, int)} */
        void onCommandUpdate(int commandCode, int operationToken) {}

        /** See {@link RcsPresenceExchangeImplBase#onNetworkResponse(int, String, int)} */
        public void onNetworkResponse(int code, String reason, int operationToken) {}

        /** See {@link RcsPresenceExchangeImplBase#onCapabilityRequestResponse(List, int)} */
        public void onCapabilityRequestResponsePresence(List<RcsContactUceCapability> infos,
                int operationToken) {}

        /** See {@link RcsPresenceExchangeImplBase#onNotifyUpdateCapabilites(int)} */
        public void onNotifyUpdateCapabilities(int publishTriggerType) {}

        /** See {@link RcsPresenceExchangeImplBase#onUnpublish()} */
        public void onUnpublish() {}

        /**
         * See {@link RcsSipOptionsImplBase#onCapabilityRequestResponse(int,String,
         * RcsContactUceCapability, int)}
         */
        public void onCapabilityRequestResponseOptions(int code, String reason,
                RcsContactUceCapability info, int operationToken) {}

        /**
         * See {@link RcsSipOptionsImplBase#onRemoteCapabilityRequest(Uri, RcsContactUceCapability,
         * int)}
         */
        public void onRemoteCapabilityRequest(Uri contactUri, RcsContactUceCapability remoteInfo,
                int operationToken) {}
    }

    private final IRcsFeatureListener mRcsFeatureCallbackAdapter = new IRcsFeatureListener.Stub() {
        @Override
        public void onCommandUpdate(int commandCode, int operationToken) {
            mRcsFeatureCallbacks.forEach(listener-> listener.onCommandUpdate(commandCode,
                    operationToken));
        }

        @Override
        public void onNetworkResponse(int code, String reason, int operationToken) {
            mRcsFeatureCallbacks.forEach(listener-> listener.onNetworkResponse(code, reason,
                    operationToken));
        }

        @Override
        public void onCapabilityRequestResponsePresence(List<RcsContactUceCapability> infos,
                int operationToken) {
            mRcsFeatureCallbacks.forEach(listener-> listener.onCapabilityRequestResponsePresence(
                    infos, operationToken));
        }

        @Override
        public void onNotifyUpdateCapabilities(int publishTriggerType) {
            mRcsFeatureCallbacks.forEach(listener-> listener.onNotifyUpdateCapabilities(
                    publishTriggerType));
        }

        @Override
        public void onUnpublish() {
            mRcsFeatureCallbacks.forEach(listener-> listener.onUnpublish());
        }

        @Override
        public void onCapabilityRequestResponseOptions(int code, String reason,
                RcsContactUceCapability info, int operationToken) {
            mRcsFeatureCallbacks.forEach(listener -> listener.onCapabilityRequestResponseOptions(
                    code, reason, info, operationToken));
        }

        @Override
        public void onRemoteCapabilityRequest(Uri contactUri, RcsContactUceCapability remoteInfo,
                int operationToken) {
            mRcsFeatureCallbacks.forEach(listener -> listener.onRemoteCapabilityRequest(
                    contactUri, remoteInfo, operationToken));
        }
    };

    private final int mSlotId;
    private final Context mContext;
    @VisibleForTesting
    public final Set<IFeatureUpdate> mStatusCallbacks = new CopyOnWriteArraySet<>();
    private final Set<RcsFeatureCallbacks> mRcsFeatureCallbacks = new CopyOnWriteArraySet<>();

    @VisibleForTesting
    public RcsFeatureConnection mRcsFeatureConnection;

    public RcsFeatureManager(Context context, int slotId) {
        mContext = context;
        mSlotId = slotId;

        createImsService();
    }

    // Binds the IMS service to the RcsFeature instance.
    private void createImsService() {
        mRcsFeatureConnection = RcsFeatureConnection.create(mContext, mSlotId,
                new IFeatureUpdate() {
                    @Override
                    public void notifyStateChanged() {
                        mStatusCallbacks.forEach(
                            FeatureConnection.IFeatureUpdate::notifyStateChanged);
                    }
                    @Override
                    public void notifyUnavailable() {
                        logi("RcsFeature is unavailable");
                        mStatusCallbacks.forEach(
                            FeatureConnection.IFeatureUpdate::notifyUnavailable);
                    }
                });
    }

    /**
     * Opens a persistent connection to the RcsFeature. This must be called before the RcsFeature
     * can be used to communicate. Triggers a {@link RcsFeature#onFeatureReady()} call on the
     * service side.
     */
    public void openConnection() throws android.telephony.ims.ImsException {
        try {
            mRcsFeatureConnection.setRcsFeatureListener(mRcsFeatureCallbackAdapter);
        } catch (RemoteException e){
            throw new android.telephony.ims.ImsException("Service is not available.",
                    android.telephony.ims.ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Closes the persistent connection to the RcsFeature. This must be called when this manager
     * wishes to no longer be used to communicate with the RcsFeature.
     */
    public void releaseConnection() {
        try {
            mRcsFeatureConnection.setRcsFeatureListener(null);
        } catch (RemoteException e){
            // Connection may not be available at this point.
        }
        mStatusCallbacks.clear();
        mRcsFeatureConnection.close();
        mRcsFeatureCallbacks.clear();
    }

    /**
     * Adds a callback for {@link RcsFeatureCallbacks}.
     * Note: These callbacks will be sent on the binder thread used to notify the callback.
     */
    public void addFeatureListenerCallback(RcsFeatureCallbacks listener) {
        mRcsFeatureCallbacks.add(listener);
    }

    /**
     * Removes an existing {@link RcsFeatureCallbacks}.
     */
    public void removeFeatureListenerCallback(RcsFeatureCallbacks listener) {
        mRcsFeatureCallbacks.remove(listener);
    }

    /**
     * Update the capabilities for this RcsFeature.
     */
    public void updateCapabilities() throws android.telephony.ims.ImsException {
        boolean optionsSupport = isOptionsSupported();
        boolean presenceSupported = isPresenceSupported();

        logi("Update capabilities for slot " + mSlotId + ": options=" + optionsSupport
                + ", presence=" + presenceSupported);

        if (optionsSupport || presenceSupported) {
            CapabilityChangeRequest request = new CapabilityChangeRequest();
            if (optionsSupport) {
                addRcsUceCapability(request, CAPABILITY_OPTIONS);
            }
            if (presenceSupported) {
                addRcsUceCapability(request, CAPABILITY_PRESENCE);
            }
            sendCapabilityChangeRequest(request);
        } else {
            disableAllRcsUceCapabilities();
        }
    }

    /**
     * Add a {@link RegistrationManager.RegistrationCallback} callback that gets called when IMS
     * registration has changed for a specific subscription.
     */
    public void registerImsRegistrationCallback(int subId, IImsRegistrationCallback callback)
            throws android.telephony.ims.ImsException {
        try {
            mRcsFeatureConnection.addCallbackForSubscription(subId, callback);
        } catch (IllegalStateException e) {
            loge("registerImsRegistrationCallback error: ", e);
            throw new android.telephony.ims.ImsException("Can not register callback",
                    android.telephony.ims.ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Add a {@link RegistrationManager.RegistrationCallback} callback that gets called when IMS
     * registration has changed, independent of the subscription it is currently on.
     */
    public void registerImsRegistrationCallback(IImsRegistrationCallback callback)
            throws android.telephony.ims.ImsException {
        try {
            mRcsFeatureConnection.addCallback(callback);
        } catch (IllegalStateException e) {
            loge("registerImsRegistrationCallback error: ", e);
            throw new android.telephony.ims.ImsException("Can not register callback",
                    android.telephony.ims.ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Removes a previously registered {@link RegistrationManager.RegistrationCallback} callback
     * that is associated with a specific subscription.
     */
    public void unregisterImsRegistrationCallback(int subId, IImsRegistrationCallback callback) {
        mRcsFeatureConnection.removeCallbackForSubscription(subId, callback);
    }

    /**
     * Removes a previously registered {@link RegistrationManager.RegistrationCallback} callback
     * that was not associated with a subscription.
     */
    public void unregisterImsRegistrationCallback(IImsRegistrationCallback callback) {
        mRcsFeatureConnection.removeCallback(callback);
    }

    /**
     * Get the IMS RCS registration technology for this Phone,
     * defined in {@link ImsRegistrationImplBase}.
     */
    public void getImsRegistrationTech(Consumer<Integer> callback) {
        try {
            int tech = mRcsFeatureConnection.getRegistrationTech();
            callback.accept(tech);
        } catch (RemoteException e) {
            loge("getImsRegistrationTech error: ", e);
            callback.accept(ImsRegistrationImplBase.REGISTRATION_TECH_NONE);
        }
    }

    /**
     * Register an ImsCapabilityCallback with RCS service, which will provide RCS availability
     * updates.
     */
    public void registerRcsAvailabilityCallback(int subId, IImsCapabilityCallback callback)
            throws android.telephony.ims.ImsException {
        try {
            mRcsFeatureConnection.addCallbackForSubscription(subId, callback);
        } catch (IllegalStateException e) {
            loge("registerRcsAvailabilityCallback: ", e);
            throw new android.telephony.ims.ImsException("Can not register callback",
                    android.telephony.ims.ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Remove an registered ImsCapabilityCallback from RCS service.
     */
    public void unregisterRcsAvailabilityCallback(int subId, IImsCapabilityCallback callback) {
            mRcsFeatureConnection.removeCallbackForSubscription(subId, callback);
    }

    /**
     * Query for the specific capability.
     */
    public boolean isCapable(
            @RcsImsCapabilities.RcsImsCapabilityFlag int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech)
            throws android.telephony.ims.ImsException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> capableRef = new AtomicReference<>();

        IImsCapabilityCallback callback = new IImsCapabilityCallback.Stub() {
            @Override
            public void onQueryCapabilityConfiguration(
                    int resultCapability, int resultRadioTech, boolean enabled) {
                if ((capability != resultCapability) || (radioTech != resultRadioTech)) {
                    return;
                }
                if (DBG) log("capable result:capability=" + capability + ", enabled=" + enabled);
                capableRef.set(enabled);
                latch.countDown();
            }

            @Override
            public void onCapabilitiesStatusChanged(int config) {
                // Don't handle it
            }

            @Override
            public void onChangeCapabilityConfigurationError(int capability, int radioTech,
                    int reason) {
                // Don't handle it
            }
        };

        try {
            if (DBG) log("Query capability: " + capability + ", radioTech=" + radioTech);
            mRcsFeatureConnection.queryCapabilityConfiguration(capability, radioTech, callback);
            return awaitResult(latch, capableRef);
        } catch (RemoteException e) {
            loge("isCapable error: ", e);
            throw new android.telephony.ims.ImsException("Can not determine capabilities",
                    android.telephony.ims.ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    private static <T> T awaitResult(CountDownLatch latch, AtomicReference<T> resultRef) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return resultRef.get();
    }

    /**
     * Query the availability of an IMS RCS capability.
     */
    public boolean isAvailable(@RcsImsCapabilities.RcsImsCapabilityFlag int capability)
            throws android.telephony.ims.ImsException {
        try {
            int currentStatus = mRcsFeatureConnection.queryCapabilityStatus();
            return new RcsImsCapabilities(currentStatus).isCapable(capability);
        } catch (RemoteException e) {
            loge("isAvailable error: ", e);
            throw new android.telephony.ims.ImsException("Can not determine availability",
                    android.telephony.ims.ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Adds a callback for status changed events if the binder is already available. If it is not,
     * this method will throw an ImsException.
     */
    @Override
    public void addNotifyStatusChangedCallbackIfAvailable(FeatureConnection.IFeatureUpdate c)
            throws android.telephony.ims.ImsException {
        if (!mRcsFeatureConnection.isBinderAlive()) {
            throw new android.telephony.ims.ImsException("Can not connect to service.",
                    android.telephony.ims.ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
        if (c != null) {
            mStatusCallbacks.add(c);
        }
    }

    @Override
    public void removeNotifyStatusChangedCallback(FeatureConnection.IFeatureUpdate c) {
        if (c != null) {
            mStatusCallbacks.remove(c);
        }
    }

    /**
     * Add UCE capabilities with given type.
     * @param capability the specific RCS UCE capability wants to enable
     */
    public void addRcsUceCapability(CapabilityChangeRequest request,
            @RcsImsCapabilities.RcsImsCapabilityFlag int capability) {
        request.addCapabilitiesToEnableForTech(capability,
                ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        request.addCapabilitiesToEnableForTech(capability,
                ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
    }

    /**
     * Disable all of the UCE capabilities.
     */
    private void disableAllRcsUceCapabilities() throws android.telephony.ims.ImsException {
        final int techLte = ImsRegistrationImplBase.REGISTRATION_TECH_LTE;
        final int techIWlan = ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
        CapabilityChangeRequest request = new CapabilityChangeRequest();
        request.addCapabilitiesToDisableForTech(CAPABILITY_OPTIONS, techLte);
        request.addCapabilitiesToDisableForTech(CAPABILITY_OPTIONS, techIWlan);
        request.addCapabilitiesToDisableForTech(CAPABILITY_PRESENCE, techLte);
        request.addCapabilitiesToDisableForTech(CAPABILITY_PRESENCE, techIWlan);
        sendCapabilityChangeRequest(request);
    }

    private void sendCapabilityChangeRequest(CapabilityChangeRequest request)
            throws android.telephony.ims.ImsException {
        try {
            if (DBG) log("sendCapabilityChangeRequest: " + request);
            mRcsFeatureConnection.changeEnabledCapabilities(request, null);
        } catch (RemoteException e) {
            throw new android.telephony.ims.ImsException("Can not connect to service",
                    android.telephony.ims.ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    private boolean isOptionsSupported() {
        return isCapabilityTypeSupported(mContext, mSlotId, CAPABILITY_OPTIONS);
    }

    private boolean isPresenceSupported() {
        return isCapabilityTypeSupported(mContext, mSlotId, CAPABILITY_PRESENCE);
    }

    /*
     * Check if the given type of capability is supported.
     */
    private static boolean isCapabilityTypeSupported(
        Context context, int slotId, int capabilityType) {

        int subId = sSubscriptionManagerProxy.getSubId(slotId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            Log.e(TAG, "isCapabilityTypeSupported: Getting subIds is failure! slotId=" + slotId);
            return false;
        }

        CarrierConfigManager configManager =
            (CarrierConfigManager) context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            Log.e(TAG, "isCapabilityTypeSupported: CarrierConfigManager is null, " + slotId);
            return false;
        }

        PersistableBundle b = configManager.getConfigForSubId(subId);
        if (b == null) {
            Log.e(TAG, "isCapabilityTypeSupported: PersistableBundle is null, " + slotId);
            return false;
        }

        if (capabilityType == CAPABILITY_OPTIONS) {
            return b.getBoolean(CarrierConfigManager.KEY_USE_RCS_SIP_OPTIONS_BOOL, false);
        } else if (capabilityType == CAPABILITY_PRESENCE) {
            return b.getBoolean(CarrierConfigManager.KEY_USE_RCS_PRESENCE_BOOL, false);
        }
        return false;
    }

    @Override
    public int getImsServiceState() throws ImsException {
        return mRcsFeatureConnection.getFeatureState();
    }

    /**
     * Testing interface used to mock SubscriptionManager in testing
     * @hide
     */
    @VisibleForTesting
    public interface SubscriptionManagerProxy {
        /**
         * Mock-able interface for {@link SubscriptionManager#getSubId(int)} used for testing.
         */
        int getSubId(int slotId);
    }

    private static SubscriptionManagerProxy sSubscriptionManagerProxy
            = slotId -> {
                int[] subIds = SubscriptionManager.getSubId(slotId);
                if (subIds != null) {
                    return subIds[0];
                }
                return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            };

    /**
     * Testing function used to mock SubscriptionManager in testing
     * @hide
     */
    @VisibleForTesting
    public static void setSubscriptionManager(SubscriptionManagerProxy proxy) {
        sSubscriptionManagerProxy = proxy;
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

    private void loge(String s, Throwable t) {
        Rlog.e(TAG + " [" + mSlotId + "]", s, t);
    }
}

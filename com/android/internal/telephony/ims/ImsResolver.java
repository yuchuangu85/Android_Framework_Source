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

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.feature.ImsFeature;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.ims.internal.IImsServiceController;
import com.android.ims.internal.IImsServiceFeatureListener;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Creates a list of ImsServices that are available to bind to based on the Device configuration
 * overlay value "config_ims_package" and Carrier Configuration value
 * "config_ims_package_override_string".
 * These ImsServices are then bound to in the following order:
 *
 * 1. Carrier Config defined override value per SIM.
 * 2. Device overlay default value (including no SIM case).
 *
 * ImsManager can then retrieve the binding to the correct ImsService using
 * {@link #getImsServiceControllerAndListen} on a per-slot and per feature basis.
 */

public class ImsResolver implements ImsServiceController.ImsServiceControllerCallbacks {

    private static final String TAG = "ImsResolver";

    public static final String SERVICE_INTERFACE = "android.telephony.ims.ImsService";
    public static final String METADATA_EMERGENCY_MMTEL_FEATURE =
            "android.telephony.ims.EMERGENCY_MMTEL_FEATURE";
    public static final String METADATA_MMTEL_FEATURE = "android.telephony.ims.MMTEL_FEATURE";
    public static final String METADATA_RCS_FEATURE = "android.telephony.ims.RCS_FEATURE";

    // Based on updates from PackageManager
    private static final int HANDLER_ADD_PACKAGE = 0;
    // Based on updates from PackageManager
    private static final int HANDLER_REMOVE_PACKAGE = 1;
    // Based on updates from CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED
    private static final int HANDLER_CONFIG_CHANGED = 2;

    /**
     * Stores information about an ImsService, including the package name, class name, and features
     * that the service supports.
     */
    @VisibleForTesting
    public static class ImsServiceInfo {
        public ComponentName name;
        public Set<Integer> supportedFeatures;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ImsServiceInfo that = (ImsServiceInfo) o;

            if (name != null ? !name.equals(that.name) : that.name != null) return false;
            return supportedFeatures != null ? supportedFeatures.equals(that.supportedFeatures)
                    : that.supportedFeatures == null;

        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result + (supportedFeatures != null ? supportedFeatures.hashCode() : 0);
            return result;
        }
    }

    // Receives broadcasts from the system involving changes to the installed applications. If
    // an ImsService that we are configured to use is installed, we must bind to it.
    private BroadcastReceiver mAppChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final String packageName = intent.getData().getSchemeSpecificPart();
            switch (action) {
                case Intent.ACTION_PACKAGE_ADDED:
                    // intentional fall-through
                case Intent.ACTION_PACKAGE_CHANGED:
                    mHandler.obtainMessage(HANDLER_ADD_PACKAGE, packageName).sendToTarget();
                    break;
                case Intent.ACTION_PACKAGE_REMOVED:
                    mHandler.obtainMessage(HANDLER_REMOVE_PACKAGE, packageName).sendToTarget();
                    break;
                default:
                    return;
            }
        }
    };

    // Receives the broadcast that a new Carrier Config has been loaded in order to possibly
    // unbind from one service and bind to another.
    private BroadcastReceiver mConfigChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);

            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                Log.i(TAG, "Received SIM change for invalid sub id.");
                return;
            }

            Log.i(TAG, "Received Carrier Config Changed for SubId: " + subId);

            mHandler.obtainMessage(HANDLER_CONFIG_CHANGED, subId).sendToTarget();
        }
    };

    /**
     * Testing interface used to mock SubscriptionManager in testing
     */
    @VisibleForTesting
    public interface SubscriptionManagerProxy {
        /**
         * Mock-able interface for {@link SubscriptionManager#getSubId(int)} used for testing.
         */
        int getSubId(int slotId);
        /**
         * Mock-able interface for {@link SubscriptionManager#getSlotIndex(int)} used for testing.
         */
        int getSlotIndex(int subId);
    }

    private SubscriptionManagerProxy mSubscriptionManagerProxy = new SubscriptionManagerProxy() {
        @Override
        public int getSubId(int slotId) {
            int[] subIds = SubscriptionManager.getSubId(slotId);
            if (subIds != null) {
                // This is done in all other places getSubId is used.
                return subIds[0];
            }
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }

        @Override
        public int getSlotIndex(int subId) {
            return SubscriptionManager.getSlotIndex(subId);
        }
    };

    /**
     * Testing interface for injecting mock ImsServiceControllers.
     */
    @VisibleForTesting
    public interface ImsServiceControllerFactory {
        /**
         * Returns the ImsServiceController created usiing the context and componentName supplied.
         * Used for DI when testing.
         */
        ImsServiceController get(Context context, ComponentName componentName);
    }

    private ImsServiceControllerFactory mImsServiceControllerFactory = (context, componentName) ->
            new ImsServiceController(context, componentName, this);

    private final CarrierConfigManager mCarrierConfigManager;
    private final Context mContext;
    // Locks mBoundImsServicesByFeature only. Be careful to avoid deadlocks from
    // ImsServiceController callbacks.
    private final Object mBoundServicesLock = new Object();
    private final int mNumSlots;

    // Synchronize all messages on a handler to ensure that the cache includes the most recent
    // version of the installed ImsServices.
    private Handler mHandler = new Handler(Looper.getMainLooper(), (msg) -> {
        switch (msg.what) {
            case HANDLER_ADD_PACKAGE: {
                String packageName = (String) msg.obj;
                maybeAddedImsService(packageName);
                break;
            }
            case HANDLER_REMOVE_PACKAGE: {
                String packageName = (String) msg.obj;
                maybeRemovedImsService(packageName);
                break;
            }
            case HANDLER_CONFIG_CHANGED: {
                int subId = (Integer) msg.obj;
                maybeRebindService(subId);
                break;
            }
            default:
                return false;
        }
        return true;
    });

    // Package name of the default device service.
    private String mDeviceService;
    // Array index corresponds to slot Id associated with the service package name.
    private String[] mCarrierServices;
    // List index corresponds to Slot Id, Maps ImsFeature.FEATURE->bound ImsServiceController
    // Locked on mBoundServicesLock
    private List<SparseArray<ImsServiceController>> mBoundImsServicesByFeature;
    // not locked, only accessed on a handler thread.
    private Set<ImsServiceInfo> mInstalledServicesCache = new ArraySet<>();
    // not locked, only accessed on a handler thread.
    private Set<ImsServiceController> mActiveControllers = new ArraySet<>();

    public ImsResolver(Context context, String defaultImsPackageName, int numSlots) {
        mContext = context;
        mDeviceService = defaultImsPackageName;
        mNumSlots = numSlots;
        mCarrierConfigManager = (CarrierConfigManager) mContext.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        mCarrierServices = new String[numSlots];
        mBoundImsServicesByFeature = Stream.generate(SparseArray<ImsServiceController>::new)
                .limit(mNumSlots).collect(Collectors.toList());

        IntentFilter appChangedFilter = new IntentFilter();
        appChangedFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        appChangedFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        appChangedFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        appChangedFilter.addDataScheme("package");
        context.registerReceiverAsUser(mAppChangedReceiver, UserHandle.ALL, appChangedFilter, null,
                null);

        context.registerReceiver(mConfigChangedReceiver, new IntentFilter(
                CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
    }

    @VisibleForTesting
    public void setSubscriptionManagerProxy(SubscriptionManagerProxy proxy) {
        mSubscriptionManagerProxy = proxy;
    }

    @VisibleForTesting
    public void setImsServiceControllerFactory(ImsServiceControllerFactory factory) {
        mImsServiceControllerFactory = factory;
    }

    @VisibleForTesting
    public Handler getHandler() {
        return mHandler;
    }

    /**
     * Needs to be called after the constructor to first populate the cache and possibly bind to
     * ImsServices.
     */
    public void populateCacheAndStartBind() {
        Log.i(TAG, "Initializing cache and binding.");
        // Populates the CarrierConfig override package names for each slot
        mHandler.obtainMessage(HANDLER_CONFIG_CHANGED, -1).sendToTarget();
        // Starts first bind to the system.
        mHandler.obtainMessage(HANDLER_ADD_PACKAGE, null).sendToTarget();
    }

    /**
     * Returns the {@link IImsServiceController} that corresponds to the given slot Id and IMS
     * feature or {@link null} if the service is not available. If an ImsServiceController is
     * available, the {@link IImsServiceFeatureListener} callback is registered as a listener for
     * feature updates.
     * @param slotId The SIM slot that we are requesting the {@link IImsServiceController} for.
     * @param feature The IMS Feature we are requesting.
     * @param callback Listener that will send updates to ImsManager when there are updates to
     * ImsServiceController.
     * @return {@link IImsServiceController} interface for the feature specified or {@link null} if
     * it is unavailable.
     */
    public IImsServiceController getImsServiceControllerAndListen(int slotId, int feature,
            IImsServiceFeatureListener callback) {
        if (slotId < 0 || slotId >= mNumSlots || feature <= ImsFeature.INVALID
                || feature >= ImsFeature.MAX) {
            return null;
        }
        ImsServiceController controller;
        synchronized (mBoundServicesLock) {
            SparseArray<ImsServiceController> services = mBoundImsServicesByFeature.get(slotId);
            if (services == null) {
                return null;
            }
            controller = services.get(feature);
        }
        if (controller != null) {
            controller.addImsServiceFeatureListener(callback);
            return controller.getImsServiceController();
        }
        return null;
    }

    private void putImsController(int slotId, int feature, ImsServiceController controller) {
        if (slotId < 0 || slotId >= mNumSlots || feature <= ImsFeature.INVALID
                || feature >= ImsFeature.MAX) {
            Log.w(TAG, "putImsController received invalid parameters - slot: " + slotId
                    + ", feature: " + feature);
            return;
        }
        synchronized (mBoundServicesLock) {
            SparseArray<ImsServiceController> services = mBoundImsServicesByFeature.get(slotId);
            if (services == null) {
                services = new SparseArray<>();
                mBoundImsServicesByFeature.add(slotId, services);
            }
            Log.i(TAG, "ImsServiceController added on slot: " + slotId + " with feature: "
                    + feature + " using package: " + controller.getComponentName());
            services.put(feature, controller);
        }
    }

    private ImsServiceController removeImsController(int slotId, int feature) {
        if (slotId < 0 || slotId >= mNumSlots || feature <= ImsFeature.INVALID
                || feature >= ImsFeature.MAX) {
            Log.w(TAG, "removeImsController received invalid parameters - slot: " + slotId
                    + ", feature: " + feature);
            return null;
        }
        synchronized (mBoundServicesLock) {
            SparseArray<ImsServiceController> services = mBoundImsServicesByFeature.get(slotId);
            if (services == null) {
                return null;
            }
            ImsServiceController c = services.get(feature, null);
            if (c != null) {
                Log.i(TAG, "ImsServiceController removed on slot: " + slotId + " with feature: "
                        + feature + " using package: " + c.getComponentName());
                services.remove(feature);
            }
            return c;
        }
    }


    // Update the current cache with the new ImsService(s) if it has been added or update the
    // supported IMS features if they have changed.
    // Called from the handler ONLY
    private void maybeAddedImsService(String packageName) {
        Log.d(TAG, "maybeAddedImsService, packageName: " + packageName);
        List<ImsServiceInfo> infos = getImsServiceInfo(packageName);
        List<ImsServiceInfo> newlyAddedInfos = new ArrayList<>();
        for (ImsServiceInfo info : infos) {
            // Checking to see if the ComponentName is the same, so we can update the supported
            // features. Will only be one (if it exists), since it is a set.
            Optional<ImsServiceInfo> match = getInfoByComponentName(mInstalledServicesCache,
                    info.name);
            if (match.isPresent()) {
                // update features in the cache
                Log.i(TAG, "Updating features in cached ImsService: " + info.name);
                Log.d(TAG, "Updating features - Old features: " + match.get().supportedFeatures
                        + " new features: " + info.supportedFeatures);
                match.get().supportedFeatures = info.supportedFeatures;
                updateImsServiceFeatures(info);
            } else {
                Log.i(TAG, "Adding newly added ImsService to cache: " + info.name);
                mInstalledServicesCache.add(info);
                newlyAddedInfos.add(info);
            }
        }
        // Loop through the newly created ServiceInfos in a separate loop to make sure the cache
        // is fully updated.
        for (ImsServiceInfo info : newlyAddedInfos) {
            if (isActiveCarrierService(info)) {
                // New ImsService is registered to active carrier services and must be newly
                // bound.
                bindNewImsService(info);
                // Update existing device service features
                updateImsServiceFeatures(getImsServiceInfoFromCache(mDeviceService));
            } else if (isDeviceService(info)) {
                // New ImsService is registered as device default and must be newly bound.
                bindNewImsService(info);
            }
        }
    }

    // Remove the ImsService from the cache. At this point, the ImsService will have already been
    // killed.
    // Called from the handler ONLY
    private boolean maybeRemovedImsService(String packageName) {
        Optional<ImsServiceInfo> match = getInfoByPackageName(mInstalledServicesCache, packageName);
        if (match.isPresent()) {
            mInstalledServicesCache.remove(match.get());
            Log.i(TAG, "Removing ImsService: " + match.get().name);
            unbindImsService(match.get());
            updateImsServiceFeatures(getImsServiceInfoFromCache(mDeviceService));
            return true;
        }
        return false;
    }

    // Returns true if the CarrierConfig that has been loaded includes this ImsServiceInfo
    // package name.
    // Called from Handler ONLY
    private boolean isActiveCarrierService(ImsServiceInfo info) {
        for (int i = 0; i < mNumSlots; i++) {
            if (TextUtils.equals(mCarrierServices[i], info.name.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeviceService(ImsServiceInfo info) {
        return TextUtils.equals(mDeviceService, info.name.getPackageName());
    }

    private int getSlotForActiveCarrierService(ImsServiceInfo info) {
        for (int i = 0; i < mNumSlots; i++) {
            if (TextUtils.equals(mCarrierServices[i], info.name.getPackageName())) {
                return i;
            }
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private Optional<ImsServiceController> getControllerByServiceInfo(
            Set<ImsServiceController> searchSet, ImsServiceInfo matchValue) {
        return searchSet.stream()
                .filter(c -> Objects.equals(c.getComponentName(), matchValue.name)).findFirst();
    }

    private Optional<ImsServiceInfo> getInfoByPackageName(Set<ImsServiceInfo> searchSet,
            String matchValue) {
        return searchSet.stream()
                .filter((i) -> Objects.equals(i.name.getPackageName(), matchValue)).findFirst();
    }

    private Optional<ImsServiceInfo> getInfoByComponentName(Set<ImsServiceInfo> searchSet,
            ComponentName matchValue) {
        return searchSet.stream()
                .filter((i) -> Objects.equals(i.name, matchValue)).findFirst();
    }

    // Creates new features in active ImsServices and removes obsolete cached features. If
    // cachedInfo == null, then newInfo is assumed to be a new ImsService and will have all features
    // created.
    private void updateImsServiceFeatures(ImsServiceInfo newInfo) {
        if (newInfo == null) {
            return;
        }
        Optional<ImsServiceController> o = getControllerByServiceInfo(mActiveControllers,
                newInfo);
        if (o.isPresent()) {
            Log.i(TAG, "Updating features for ImsService: " + o.get().getComponentName());
            HashSet<Pair<Integer, Integer>> features = calculateFeaturesToCreate(newInfo);
            try {
                if (features.size() > 0) {
                    Log.d(TAG, "Updating Features - New Features: " + features);
                    o.get().changeImsServiceFeatures(features);

                    // If the carrier service features have changed, the device features will also
                    // need to be recalculated.
                    if (isActiveCarrierService(newInfo)
                            // Prevent infinite recursion from bad behavior
                            && !TextUtils.equals(newInfo.name.getPackageName(), mDeviceService)) {
                        Log.i(TAG, "Updating device default");
                        updateImsServiceFeatures(getImsServiceInfoFromCache(mDeviceService));
                    }
                } else {
                    Log.i(TAG, "Unbinding: features = 0 for ImsService: "
                            + o.get().getComponentName());
                    o.get().unbind();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "updateImsServiceFeatures: Remote Exception: " + e.getMessage());
            }
        }
    }

    // Bind to a new ImsService and wait for the service to be connected to create ImsFeatures.
    private void bindNewImsService(ImsServiceInfo info) {
        if (info == null) {
            return;
        }
        ImsServiceController controller = mImsServiceControllerFactory.get(mContext, info.name);
        HashSet<Pair<Integer, Integer>> features = calculateFeaturesToCreate(info);
        // Only bind if there are features that will be created by the service.
        if (features.size() > 0) {
            Log.i(TAG, "Binding ImsService: " + controller.getComponentName() + " with features: "
                    + features);
            controller.bind(features);
            mActiveControllers.add(controller);
        }
    }

    // Clean up and unbind from an ImsService
    private void unbindImsService(ImsServiceInfo info) {
        if (info == null) {
            return;
        }
        Optional<ImsServiceController> o = getControllerByServiceInfo(mActiveControllers, info);
        if (o.isPresent()) {
            // Calls imsServiceFeatureRemoved on all features in the controller
            try {
                Log.i(TAG, "Unbinding ImsService: " + o.get().getComponentName());
                o.get().unbind();
            } catch (RemoteException e) {
                Log.e(TAG, "unbindImsService: Remote Exception: " + e.getMessage());
            }
            mActiveControllers.remove(o.get());
        }
    }

    // Calculate which features an ImsServiceController will need. If it is the carrier specific
    // ImsServiceController, it will be granted all of the features it requests on the associated
    // slot. If it is the device ImsService, it will get all of the features not covered by the
    // carrier implementation.
    private HashSet<Pair<Integer, Integer>> calculateFeaturesToCreate(ImsServiceInfo info) {
        HashSet<Pair<Integer, Integer>> imsFeaturesBySlot = new HashSet<>();
        // Check if the info is a carrier service
        int slotId = getSlotForActiveCarrierService(info);
        if (slotId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            imsFeaturesBySlot.addAll(info.supportedFeatures.stream().map(
                    feature -> new Pair<>(slotId, feature)).collect(Collectors.toList()));
        } else if (isDeviceService(info)) {
            // For all slots that are not currently using a carrier ImsService, enable all features
            // for the device default.
            for (int i = 0; i < mNumSlots; i++) {
                final int currSlotId = i;
                ImsServiceInfo carrierImsInfo = getImsServiceInfoFromCache(mCarrierServices[i]);
                if (carrierImsInfo == null) {
                    // No Carrier override, add all features
                    imsFeaturesBySlot.addAll(info.supportedFeatures.stream().map(
                            feature -> new Pair<>(currSlotId, feature)).collect(
                            Collectors.toList()));
                } else {
                    // Add all features to the device service that are not currently covered by
                    // the carrier ImsService.
                    Set<Integer> deviceFeatures = new HashSet<>(info.supportedFeatures);
                    deviceFeatures.removeAll(carrierImsInfo.supportedFeatures);
                    imsFeaturesBySlot.addAll(deviceFeatures.stream().map(
                            feature -> new Pair<>(currSlotId, feature)).collect(
                            Collectors.toList()));
                }
            }
        }
        return imsFeaturesBySlot;
    }

    /**
     * Implementation of
     * {@link ImsServiceController.ImsServiceControllerCallbacks#imsServiceFeatureCreated}, which
     * removes the ImsServiceController from the mBoundImsServicesByFeature structure.
     */
    public void imsServiceFeatureCreated(int slotId, int feature, ImsServiceController controller) {
        putImsController(slotId, feature, controller);
    }

    /**
     * Implementation of
     * {@link ImsServiceController.ImsServiceControllerCallbacks#imsServiceFeatureRemoved}, which
     * removes the ImsServiceController from the mBoundImsServicesByFeature structure.
     */
    public void imsServiceFeatureRemoved(int slotId, int feature, ImsServiceController controller) {
        removeImsController(slotId, feature);
    }

    // Possibly rebind to another ImsService if currently installed ImsServices were changed or if
    // the SIM card has changed.
    // Called from the handler ONLY
    private void maybeRebindService(int subId) {
        if (subId <= SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            // not specified, replace package on all slots.
            for (int i = 0; i < mNumSlots; i++) {
                // get Sub id from Slot Id
                subId = mSubscriptionManagerProxy.getSubId(i);
                updateBoundCarrierServices(subId);
            }
        } else {
            updateBoundCarrierServices(subId);
        }

    }

    private void updateBoundCarrierServices(int subId) {
        int slotId = mSubscriptionManagerProxy.getSlotIndex(subId);
        String newPackageName = mCarrierConfigManager.getConfigForSubId(subId).getString(
                CarrierConfigManager.KEY_CONFIG_IMS_PACKAGE_OVERRIDE_STRING, null);
        if (slotId != SubscriptionManager.INVALID_SIM_SLOT_INDEX && slotId < mNumSlots) {
            String oldPackageName = mCarrierServices[slotId];
            mCarrierServices[slotId] = newPackageName;
            if (!TextUtils.equals(newPackageName, oldPackageName)) {
                Log.i(TAG, "Carrier Config updated, binding new ImsService");
                // Unbind old ImsService, not needed anymore
                // ImsService is retrieved from the cache. If the cache hasn't been populated yet,
                // the calls to unbind/bind will fail (intended during initial start up).
                unbindImsService(getImsServiceInfoFromCache(oldPackageName));
                bindNewImsService(getImsServiceInfoFromCache(newPackageName));
                // Recalculate the device ImsService features to reflect changes.
                updateImsServiceFeatures(getImsServiceInfoFromCache(mDeviceService));
            }
        }
    }

    /**
     * Returns the ImsServiceInfo that matches the provided packageName. Visible for testing
     * the ImsService caching functionality.
     */
    @VisibleForTesting
    public ImsServiceInfo getImsServiceInfoFromCache(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        Optional<ImsServiceInfo> infoFilter = getInfoByPackageName(mInstalledServicesCache,
                packageName);
        if (infoFilter.isPresent()) {
            return infoFilter.get();
        } else {
            return null;
        }
    }

    // Return the ImsServiceInfo specified for the package name. If the package name is null,
    // get all packages that support ImsServices.
    private List<ImsServiceInfo> getImsServiceInfo(String packageName) {
        List<ImsServiceInfo> infos = new ArrayList<>();

        Intent serviceIntent = new Intent(SERVICE_INTERFACE);
        serviceIntent.setPackage(packageName);

        PackageManager packageManager = mContext.getPackageManager();
        for (ResolveInfo entry : packageManager.queryIntentServicesAsUser(
                serviceIntent,
                PackageManager.GET_META_DATA,
                mContext.getUserId())) {
            ServiceInfo serviceInfo = entry.serviceInfo;

            if (serviceInfo != null) {
                ImsServiceInfo info = new ImsServiceInfo();
                info.name = new ComponentName(serviceInfo.packageName, serviceInfo.name);
                info.supportedFeatures = new HashSet<>(ImsFeature.MAX);
                // Add all supported features
                if (serviceInfo.metaData != null) {
                    if (serviceInfo.metaData.getBoolean(METADATA_EMERGENCY_MMTEL_FEATURE, false)) {
                        info.supportedFeatures.add(ImsFeature.EMERGENCY_MMTEL);
                    }
                    if (serviceInfo.metaData.getBoolean(METADATA_MMTEL_FEATURE, false)) {
                        info.supportedFeatures.add(ImsFeature.MMTEL);
                    }
                    if (serviceInfo.metaData.getBoolean(METADATA_RCS_FEATURE, false)) {
                        info.supportedFeatures.add(ImsFeature.RCS);
                    }
                }
                // Check manifest permission to be sure that the service declares the correct
                // permissions.
                if (TextUtils.equals(serviceInfo.permission,
                        Manifest.permission.BIND_IMS_SERVICE)) {
                    Log.d(TAG, "ImsService added to cache: " + info.name + " with features: "
                            + info.supportedFeatures);
                    infos.add(info);
                } else {
                    Log.w(TAG, "ImsService does not have BIND_IMS_SERVICE permission: "
                            + info.name);
                }
            }
        }
        return infos;
    }
}

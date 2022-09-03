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

package android.companion;

import static android.Manifest.permission.REQUEST_COMPANION_PROFILE_APP_STREAMING;
import static android.Manifest.permission.REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION;
import static android.Manifest.permission.REQUEST_COMPANION_PROFILE_COMPUTER;
import static android.Manifest.permission.REQUEST_COMPANION_PROFILE_WATCH;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.util.ExceptionUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * System level service for managing companion devices
 *
 * See <a href="{@docRoot}guide/topics/connectivity/companion-device-pairing">this guide</a>
 * for a usage example.
 *
 * <p>To obtain an instance call {@link Context#getSystemService}({@link
 * Context#COMPANION_DEVICE_SERVICE}) Then, call {@link #associate(AssociationRequest,
 * Callback, Handler)} to initiate the flow of associating current package with a
 * device selected by user.</p>
 *
 * @see CompanionDeviceManager#associate
 * @see AssociationRequest
 */
@SystemService(Context.COMPANION_DEVICE_SERVICE)
public final class CompanionDeviceManager {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "CompanionDeviceManager";

    /**
     * The result code to propagate back to the originating activity, indicates the association
     * dialog is explicitly declined by the users.
     *
     * @hide
     */
    public static final int RESULT_USER_REJECTED = 1;

    /**
     * The result code to propagate back to the originating activity, indicates the association
     * dialog is dismissed if there's no device found after 20 seconds.
     *
     * @hide
     */
    public static final int RESULT_DISCOVERY_TIMEOUT = 2;

    /**
     * The result code to propagate back to the originating activity, indicates the internal error
     * in CompanionDeviceManager.
     *
     * @hide
     */
    public static final int RESULT_INTERNAL_ERROR = 3;

    /**
     *  Requesting applications will receive the String in {@link Callback#onFailure} if the
     *  association dialog is explicitly declined by the users. e.g. press the Don't allow button.
     *
     * @hide
     */
    public static final String REASON_USER_REJECTED = "user_rejected";

    /**
     *  Requesting applications will receive the String in {@link Callback#onFailure} if there's
     *  no device found after 20 seconds.
     *
     * @hide
     */
    public static final String REASON_DISCOVERY_TIMEOUT = "discovery_timeout";

    /**
     *  Requesting applications will receive the String in {@link Callback#onFailure} if the
     *  association dialog is in-explicitly declined by the users. e.g. phone is locked, switch to
     *  another app or press outside the dialog.
     *
     * @hide
     */
    public static final String REASON_CANCELED = "canceled";


    /**
     * A device, returned in the activity result of the {@link IntentSender} received in
     * {@link Callback#onDeviceFound}
     *
     * Type is:
     * <ul>
     *     <li>for classic Bluetooth - {@link android.bluetooth.BluetoothDevice}</li>
     *     <li>for Bluetooth LE - {@link android.bluetooth.le.ScanResult}</li>
     *     <li>for WiFi - {@link android.net.wifi.ScanResult}</li>
     * </ul>
     *
     * @deprecated use {@link #EXTRA_ASSOCIATION} instead.
     */
    @Deprecated
    public static final String EXTRA_DEVICE = "android.companion.extra.DEVICE";

    /**
     * Extra field name for the {@link AssociationInfo} object, included into
     * {@link android.content.Intent} which application receive in
     * {@link Activity#onActivityResult(int, int, Intent)} after the application's
     * {@link AssociationRequest} was successfully processed and an association was created.
     */
    public static final String EXTRA_ASSOCIATION = "android.companion.extra.ASSOCIATION";

    /**
     * The package name of the companion device discovery component.
     *
     * @hide
     */
    public static final String COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME =
            "com.android.companiondevicemanager";

    /**
     * Callback for applications to receive updates about and the outcome of
     * {@link AssociationRequest} issued via {@code associate()} call.
     *
     * <p>
     * The {@link Callback#onAssociationPending(IntentSender)} is invoked after the
     * {@link AssociationRequest} has been checked by the Companion Device Manager Service and is
     * pending user's approval.
     *
     * The {@link IntentSender} received as an argument to
     * {@link Callback#onAssociationPending(IntentSender)} "encapsulates" an {@link Activity}
     * that has UI for the user to:
     * <ul>
     * <li>
     * choose the device to associate the application with (if multiple eligible devices are
     * available)
     * </li>
     * <li>confirm the association</li>
     * <li>
     * approve the privileges the application will be granted if the association is to be created
     * </li>
     * </ul>
     *
     * If the Companion Device Manager Service needs to scan for the devices, the {@link Activity}
     * will also display the status and the progress of the scan.
     *
     * Note that Companion Device Manager Service will only start the scanning after the
     * {@link Activity} was launched and became visible.
     *
     * Applications are expected to launch the UI using the received {@link IntentSender} via
     * {@link Activity#startIntentSenderForResult(IntentSender, int, Intent, int, int, int)}.
     * </p>
     *
     * <p>
     * Upon receiving user's confirmation Companion Device Manager Service will create an
     * association and will send an {@link AssociationInfo} object that represents the created
     * association back to the application both via
     * {@link Callback#onAssociationCreated(AssociationInfo)} and
     * via {@link Activity#setResult(int, Intent)}.
     * In the latter the {@code resultCode} will be set to {@link Activity#RESULT_OK} and the
     * {@code data} {@link Intent} will contain {@link AssociationInfo} extra named
     * {@link #EXTRA_ASSOCIATION}.
     * <pre>
     * <code>
     *   if (resultCode == Activity.RESULT_OK) {
     *     AssociationInfo associationInfo = data.getParcelableExtra(EXTRA_ASSOCIATION);
     *   }
     * </code>
     * </pre>
     * </p>
     *
     * <p>
     *  If the Companion Device Manager Service is not able to create an association, it will
     *  invoke {@link Callback#onFailure(CharSequence)}.
     *
     *  If this happened after the application has launched the UI (eg. the user chose to reject
     *  the association), the outcome will also be delivered to the applications via
     *  {@link Activity#setResult(int)} with the {@link Activity#RESULT_CANCELED}
     *  {@code resultCode}.
     * </p>
     *
     * <p>
     * Note that in some cases the Companion Device Manager Service may not need to collect
     * user's approval for creating an association. In such cases, this method will not be
     * invoked, and {@link #onAssociationCreated(AssociationInfo)} may be invoked right away.
     * </p>
     *
     * @see #associate(AssociationRequest, Executor, Callback)
     * @see #associate(AssociationRequest, Callback, Handler)
     * @see #EXTRA_ASSOCIATION
     */
    public abstract static class Callback {
        /**
         * @deprecated method was renamed to onAssociationPending() to provide better clarity; both
         * methods are functionally equivalent and only one needs to be overridden.
         *
         * @see #onAssociationPending(IntentSender)
         */
        @Deprecated
        public void onDeviceFound(@NonNull IntentSender intentSender) {}

        /**
         * Invoked when the association needs to approved by the user.
         *
         * Applications should launch the {@link Activity} "encapsulated" in {@code intentSender}
         * {@link IntentSender} object by calling
         * {@link Activity#startIntentSenderForResult(IntentSender, int, Intent, int, int, int)}.
         *
         * @param intentSender an {@link IntentSender} which applications should use to launch
         *                     the UI for the user to confirm the association.
         */
        public void onAssociationPending(@NonNull IntentSender intentSender) {
            onDeviceFound(intentSender);
        }

        /**
         * Invoked when the association is created.
         *
         * @param associationInfo contains details of the newly-established association.
         */
        public void onAssociationCreated(@NonNull AssociationInfo associationInfo) {}

        /**
         * Invoked if the association could not be created.
         *
         * @param error error message.
         */
        public abstract void onFailure(@Nullable CharSequence error);
    }

    private final ICompanionDeviceManager mService;
    private Context mContext;

    @GuardedBy("mListeners")
    private final ArrayList<OnAssociationsChangedListenerProxy> mListeners = new ArrayList<>();

    /** @hide */
    public CompanionDeviceManager(
            @Nullable ICompanionDeviceManager service, @NonNull Context context) {
        mService = service;
        mContext = context;
    }

    /**
     * Request to associate this app with a companion device.
     *
     * <p>Note that before creating establishing association the system may need to show UI to
     * collect user confirmation.</p>
     *
     * <p>If the app needs to be excluded from battery optimizations (run in the background)
     * or to have unrestricted data access (use data in the background) it should declare use of
     * {@link android.Manifest.permission#REQUEST_COMPANION_RUN_IN_BACKGROUND} and
     * {@link android.Manifest.permission#REQUEST_COMPANION_USE_DATA_IN_BACKGROUND} in its
     * AndroidManifest.xml respectively.
     * Note that these special capabilities have a negative effect on the device's battery and
     * user's data usage, therefore you should request them when absolutely necessary.</p>
     *
     * <p>Application can use {@link #getMyAssociations()} for retrieving the list of currently
     * {@link AssociationInfo} objects, that represent their existing associations.
     * Applications can also use {@link #disassociate(int)} to remove an association, and are
     * recommended to do when an association is no longer relevant to avoid unnecessary battery
     * and/or data drain resulting from special privileges that the association provides</p>
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     **
     * @param request A request object that describes details of the request.
     * @param callback The callback used to notify application when the association is created.
     * @param handler The handler which will be used to invoke the callback.
     *
     * @see AssociationRequest.Builder
     * @see #getMyAssociations()
     * @see #disassociate(int)
     * @see #associate(AssociationRequest, Executor, Callback)
     */
    @UserHandleAware
    @RequiresPermission(anyOf = {
            REQUEST_COMPANION_PROFILE_WATCH,
            REQUEST_COMPANION_PROFILE_COMPUTER,
            REQUEST_COMPANION_PROFILE_APP_STREAMING,
            REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION,
            }, conditional = true)
    public void associate(
            @NonNull AssociationRequest request,
            @NonNull Callback callback,
            @Nullable Handler handler) {
        if (!checkFeaturePresent()) return;
        Objects.requireNonNull(request, "Request cannot be null");
        Objects.requireNonNull(callback, "Callback cannot be null");
        handler = Handler.mainIfNull(handler);

        try {
            mService.associate(request, new AssociationRequestCallbackProxy(handler, callback),
                    mContext.getOpPackageName(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to associate this app with a companion device.
     *
     * <p>Note that before creating establishing association the system may need to show UI to
     * collect user confirmation.</p>
     *
     * <p>If the app needs to be excluded from battery optimizations (run in the background)
     * or to have unrestricted data access (use data in the background) it should declare use of
     * {@link android.Manifest.permission#REQUEST_COMPANION_RUN_IN_BACKGROUND} and
     * {@link android.Manifest.permission#REQUEST_COMPANION_USE_DATA_IN_BACKGROUND} in its
     * AndroidManifest.xml respectively.
     * Note that these special capabilities have a negative effect on the device's battery and
     * user's data usage, therefore you should request them when absolutely necessary.</p>
     *
     * <p>Application can use {@link #getMyAssociations()} for retrieving the list of currently
     * {@link AssociationInfo} objects, that represent their existing associations.
     * Applications can also use {@link #disassociate(int)} to remove an association, and are
     * recommended to do when an association is no longer relevant to avoid unnecessary battery
     * and/or data drain resulting from special privileges that the association provides</p>
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     **
     * @param request A request object that describes details of the request.
     * @param executor The executor which will be used to invoke the callback.
     * @param callback The callback used to notify application when the association is created.
     *
     * @see AssociationRequest.Builder
     * @see #getMyAssociations()
     * @see #disassociate(int)
     */
    @UserHandleAware
    @RequiresPermission(anyOf = {
            REQUEST_COMPANION_PROFILE_WATCH,
            REQUEST_COMPANION_PROFILE_COMPUTER,
            REQUEST_COMPANION_PROFILE_APP_STREAMING,
            REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION
            }, conditional = true)
    public void associate(
            @NonNull AssociationRequest request,
            @NonNull Executor executor,
            @NonNull Callback callback) {
        if (!checkFeaturePresent()) return;
        Objects.requireNonNull(request, "Request cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");
        Objects.requireNonNull(callback, "Callback cannot be null");

        try {
            mService.associate(request, new AssociationRequestCallbackProxy(executor, callback),
                    mContext.getOpPackageName(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @return a list of MAC addresses of devices that have been previously associated with the
     * current app are managed by CompanionDeviceManager (ie. does not include devices managed by
     * application itself even if they have a MAC address).
     *
     * @deprecated use {@link #getMyAssociations()}
     */
    @Deprecated
    @UserHandleAware
    @NonNull
    public List<String> getAssociations() {
        return CollectionUtils.mapNotNull(getMyAssociations(),
                a -> a.isSelfManaged() ? null : a.getDeviceMacAddressAsString());
    }

    /**
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @return a list of associations that have been previously associated with the current app.
     */
    @UserHandleAware
    @NonNull
    public List<AssociationInfo> getMyAssociations() {
        if (!checkFeaturePresent()) return Collections.emptyList();

        try {
            return mService.getAssociations(mContext.getOpPackageName(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the association between this app and the device with the given mac address.
     *
     * <p>Any privileges provided via being associated with a given device will be revoked</p>
     *
     * <p>Consider doing so when the
     * association is no longer relevant to avoid unnecessary battery and/or data drain resulting
     * from special privileges that the association provides</p>
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @param deviceMacAddress the MAC address of device to disassociate from this app
     *
     * @deprecated use {@link #disassociate(int)}
     */
    @UserHandleAware
    @Deprecated
    public void disassociate(@NonNull String deviceMacAddress) {
        if (!checkFeaturePresent()) return;

        try {
            mService.legacyDisassociate(deviceMacAddress, mContext.getOpPackageName(),
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove an association.
     *
     * <p>Any privileges provided via being associated with a given device will be revoked</p>
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @param associationId id of the association to be removed.
     *
     * @see #associate(AssociationRequest, Executor, Callback)
     * @see AssociationInfo#getId()
     */
    @UserHandleAware
    public void disassociate(int associationId) {
        if (!checkFeaturePresent()) return;

        try {
            mService.disassociate(associationId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request notification access for the given component.
     *
     * The given component must follow the protocol specified in {@link NotificationListenerService}
     *
     * Only components from the same {@link ComponentName#getPackageName package} as the calling app
     * are allowed.
     *
     * Your app must have an association with a device before calling this API
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     */
    @UserHandleAware
    public void requestNotificationAccess(ComponentName component) {
        if (!checkFeaturePresent()) {
            return;
        }
        try {
            IntentSender intentSender = mService
                    .requestNotificationAccess(component, mContext.getUserId())
                    .getIntentSender();
            mContext.startIntentSender(intentSender, null, 0, 0, 0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (IntentSender.SendIntentException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check whether the given component can access the notifications via a
     * {@link NotificationListenerService}
     *
     * Your app must have an association with a device before calling this API
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @param component the name of the component
     * @return whether the given component has the notification listener permission
     *
     * @deprecated Use
     * {@link NotificationManager#isNotificationListenerAccessGranted(ComponentName)} instead.
     */
    @Deprecated
    public boolean hasNotificationAccess(ComponentName component) {
        if (!checkFeaturePresent()) {
            return false;
        }
        try {
            return mService.hasNotificationAccess(component);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if a given package was {@link #associate associated} with a device with given
     * Wi-Fi MAC address for a given user.
     *
     * <p>This is a system API protected by the
     * {@link android.Manifest.permission#MANAGE_COMPANION_DEVICES} permission, that’s currently
     * called by the Android Wi-Fi stack to determine whether user consent is required to connect
     * to a Wi-Fi network. Devices that have been pre-registered as companion devices will not
     * require user consent to connect.</p>
     *
     * <p>Note if the caller has the
     * {@link android.Manifest.permission#COMPANION_APPROVE_WIFI_CONNECTIONS} permission, this
     * method will return true by default.</p>
     *
     * @param packageName the name of the package that has the association with the companion device
     * @param macAddress the Wi-Fi MAC address or BSSID of the companion device to check for
     * @param user the user handle that currently hosts the package being queried for a companion
     *             device association
     * @return whether a corresponding association record exists
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_COMPANION_DEVICES)
    public boolean isDeviceAssociatedForWifiConnection(
            @NonNull String packageName,
            @NonNull MacAddress macAddress,
            @NonNull UserHandle user) {
        if (!checkFeaturePresent()) return false;
        Objects.requireNonNull(packageName, "package name cannot be null");
        Objects.requireNonNull(macAddress, "mac address cannot be null");
        Objects.requireNonNull(user, "user cannot be null");
        try {
            return mService.isDeviceAssociatedForWifiConnection(
                    packageName, macAddress.toString(), user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all package-device {@link AssociationInfo}s for the current user.
     *
     * @return the associations list
     * @see #addOnAssociationsChangedListener(Executor, OnAssociationsChangedListener)
     * @see #removeOnAssociationsChangedListener(OnAssociationsChangedListener)
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.MANAGE_COMPANION_DEVICES)
    public @NonNull List<AssociationInfo> getAllAssociations() {
        if (!checkFeaturePresent()) return Collections.emptyList();
        try {
            return mService.getAllAssociationsForUser(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Listener for any changes to {@link AssociationInfo}.
     *
     * @hide
     */
    @SystemApi
    public interface OnAssociationsChangedListener {
        /**
         * Invoked when a change occurs to any of the associations for the user (including adding
         * new associations and removing existing associations).
         *
         * @param associations all existing associations for the user (after the change).
         */
        void onAssociationsChanged(@NonNull List<AssociationInfo> associations);
    }

    /**
     * Register listener for any changes to {@link AssociationInfo}.
     *
     * @see #getAllAssociations()
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.MANAGE_COMPANION_DEVICES)
    public void addOnAssociationsChangedListener(
            @NonNull Executor executor, @NonNull OnAssociationsChangedListener listener) {
        if (!checkFeaturePresent()) return;
        synchronized (mListeners) {
            final OnAssociationsChangedListenerProxy proxy = new OnAssociationsChangedListenerProxy(
                    executor, listener);
            try {
                mService.addOnAssociationsChangedListener(proxy, mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mListeners.add(proxy);
        }
    }

    /**
     * Unregister listener for any changes to {@link AssociationInfo}.
     *
     * @see #getAllAssociations()
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.MANAGE_COMPANION_DEVICES)
    public void removeOnAssociationsChangedListener(
            @NonNull OnAssociationsChangedListener listener) {
        if (!checkFeaturePresent()) return;
        synchronized (mListeners) {
            final Iterator<OnAssociationsChangedListenerProxy> iterator = mListeners.iterator();
            while (iterator.hasNext()) {
                final OnAssociationsChangedListenerProxy proxy = iterator.next();
                if (proxy.mListener == listener) {
                    try {
                        mService.removeOnAssociationsChangedListener(proxy, mContext.getUserId());
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Checks whether the bluetooth device represented by the mac address was recently associated
     * with the companion app. This allows these devices to skip the Bluetooth pairing dialog if
     * their pairing variant is {@link BluetoothDevice#PAIRING_VARIANT_CONSENT}.
     *
     * @param packageName the package name of the calling app
     * @param deviceMacAddress the bluetooth device's mac address
     * @param user the user handle that currently hosts the package being queried for a companion
     *             device association
     * @return true if it was recently associated and we can bypass the dialog, false otherwise
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_COMPANION_DEVICES)
    public boolean canPairWithoutPrompt(@NonNull String packageName,
            @NonNull String deviceMacAddress, @NonNull UserHandle user) {
        if (!checkFeaturePresent()) {
            return false;
        }
        Objects.requireNonNull(packageName, "package name cannot be null");
        Objects.requireNonNull(deviceMacAddress, "device mac address cannot be null");
        Objects.requireNonNull(user, "user handle cannot be null");
        try {
            return mService.canPairWithoutPrompt(packageName, deviceMacAddress,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register to receive callbacks whenever the associated device comes in and out of range.
     *
     * The provided device must be {@link #associate associated} with the calling app before
     * calling this method.
     *
     * Caller must implement a single {@link CompanionDeviceService} which will be bound to and
     * receive callbacks to {@link CompanionDeviceService#onDeviceAppeared} and
     * {@link CompanionDeviceService#onDeviceDisappeared}.
     * The app doesn't need to remain running in order to receive its callbacks.
     *
     * Calling app must declare uses-permission
     * {@link android.Manifest.permission#REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE}.
     *
     * Calling app must check for feature presence of
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} before calling this API.
     *
     * For Bluetooth LE devices this is based on scanning for device with the given address.
     * For Bluetooth classic devices this is triggered when the device connects/disconnects.
     * WiFi devices are not supported.
     *
     * If a Bluetooth LE device wants to use a rotating mac address, it is recommended to use
     * Resolvable Private Address, and ensure the device is bonded to the phone so that android OS
     * is able to resolve the address.
     *
     * @param deviceAddress a previously-associated companion device's address
     *
     * @throws DeviceNotAssociatedException if the given device was not previously associated
     * with this app.
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
    public void startObservingDevicePresence(@NonNull String deviceAddress)
            throws DeviceNotAssociatedException {
        if (!checkFeaturePresent()) {
            return;
        }
        Objects.requireNonNull(deviceAddress, "address cannot be null");
        try {
            mService.registerDevicePresenceListenerService(deviceAddress,
                    mContext.getOpPackageName(), mContext.getUserId());
        } catch (RemoteException e) {
            ExceptionUtils.propagateIfInstanceOf(e.getCause(), DeviceNotAssociatedException.class);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister for receiving callbacks whenever the associated device comes in and out of range.
     *
     * The provided device must be {@link #associate associated} with the calling app before
     * calling this method.
     *
     * Calling app must declare uses-permission
     * {@link android.Manifest.permission#REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE}.
     *
     * Calling app must check for feature presence of
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} before calling this API.
     *
     * @param deviceAddress a previously-associated companion device's address
     *
     * @throws DeviceNotAssociatedException if the given device was not previously associated
     * with this app.
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
    public void stopObservingDevicePresence(@NonNull String deviceAddress)
            throws DeviceNotAssociatedException {
        if (!checkFeaturePresent()) {
            return;
        }
        Objects.requireNonNull(deviceAddress, "address cannot be null");
        try {
            mService.unregisterDevicePresenceListenerService(deviceAddress,
                    mContext.getPackageName(), mContext.getUserId());
        } catch (RemoteException e) {
            ExceptionUtils.propagateIfInstanceOf(e.getCause(), DeviceNotAssociatedException.class);
        }
    }

    /**
     * Dispatch a message to system for processing.
     *
     * <p>Calling app must declare uses-permission
     * {@link android.Manifest.permission#DELIVER_COMPANION_MESSAGES}</p>
     *
     * @param messageId id of the message
     * @param associationId association id of the associated device where data is coming from
     * @param message message received from the associated device
     *
     * @throws DeviceNotAssociatedException if the given device was not previously associated with
     * this app
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DELIVER_COMPANION_MESSAGES)
    public void dispatchMessage(int messageId, int associationId, @NonNull byte[] message)
            throws DeviceNotAssociatedException {
        try {
            mService.dispatchMessage(messageId, associationId, message);
        } catch (RemoteException e) {
            ExceptionUtils.propagateIfInstanceOf(e.getCause(), DeviceNotAssociatedException.class);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Associates given device with given app for the given user directly, without UI prompt.
     *
     * @param packageName package name of the companion app
     * @param macAddress mac address of the device to associate
     * @param certificate The SHA256 digest of the companion app's signing certificate
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ASSOCIATE_COMPANION_DEVICES)
    public void associate(
            @NonNull String packageName,
            @NonNull MacAddress macAddress,
            @NonNull byte[] certificate) {
        if (!checkFeaturePresent()) {
            return;
        }
        Objects.requireNonNull(packageName, "package name cannot be null");
        Objects.requireNonNull(macAddress, "mac address cannot be null");

        UserHandle user = android.os.Process.myUserHandle();
        try {
            mService.createAssociation(
                    packageName, macAddress.toString(), user.getIdentifier(), certificate);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notify the system that the given self-managed association has just appeared.
     * This causes the system to bind to the companion app to keep it running until the association
     * is reported as disappeared
     *
     * <p>This API is only available for the companion apps that manage the connectivity by
     * themselves.</p>
     *
     * @param associationId the unique {@link AssociationInfo#getId ID} assigned to the Association
     * recorded by CompanionDeviceManager
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REQUEST_COMPANION_SELF_MANAGED)
    public void notifyDeviceAppeared(int associationId) {
        try {
            mService.notifyDeviceAppeared(associationId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notify the system that the given self-managed association has just disappeared.
     * This causes the system to unbind to the companion app.
     *
     * <p>This API is only available for the companion apps that manage the connectivity by
     * themselves.</p>
     *
     * @param associationId the unique {@link AssociationInfo#getId ID} assigned to the Association
     * recorded by CompanionDeviceManager

     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REQUEST_COMPANION_SELF_MANAGED)
    public void notifyDeviceDisappeared(int associationId) {
        try {
            mService.notifyDeviceDisappeared(associationId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private boolean checkFeaturePresent() {
        boolean featurePresent = mService != null;
        if (!featurePresent && DEBUG) {
            Log.d(LOG_TAG, "Feature " + PackageManager.FEATURE_COMPANION_DEVICE_SETUP
                    + " not available");
        }
        return featurePresent;
    }

    private static class AssociationRequestCallbackProxy extends IAssociationRequestCallback.Stub {
        private final Handler mHandler;
        private final Callback mCallback;
        private final Executor mExecutor;

        private AssociationRequestCallbackProxy(
                @NonNull Executor executor, @NonNull Callback callback) {
            mExecutor = executor;
            mHandler = null;
            mCallback = callback;
        }

        private AssociationRequestCallbackProxy(
                @NonNull Handler handler, @NonNull Callback callback) {
            mHandler = handler;
            mExecutor = null;
            mCallback = callback;
        }

        @Override
        public void onAssociationPending(@NonNull PendingIntent pi) {
            execute(mCallback::onAssociationPending, pi.getIntentSender());
        }

        @Override
        public void onAssociationCreated(@NonNull AssociationInfo association) {
            execute(mCallback::onAssociationCreated, association);
        }

        @Override
        public void onFailure(CharSequence error) throws RemoteException {
            execute(mCallback::onFailure, error);
        }

        private <T> void execute(Consumer<T> callback, T arg) {
            if (mExecutor != null) {
                mExecutor.execute(() -> callback.accept(arg));
            } else {
                mHandler.post(() -> callback.accept(arg));
            }
        }
    }

    private static class OnAssociationsChangedListenerProxy
            extends IOnAssociationsChangedListener.Stub {
        private final Executor mExecutor;
        private final OnAssociationsChangedListener mListener;

        private OnAssociationsChangedListenerProxy(Executor executor,
                OnAssociationsChangedListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onAssociationsChanged(@NonNull List<AssociationInfo> associations) {
            mExecutor.execute(() -> mListener.onAssociationsChanged(associations));
        }
    }
}

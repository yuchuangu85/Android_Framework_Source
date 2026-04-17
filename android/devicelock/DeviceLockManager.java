/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.devicelock;

import static com.android.devicelock.flags.Flags.FLAG_CLEAR_DEVICE_RESTRICTIONS;
import static com.android.devicelock.flags.Flags.FLAG_EXTRA_DEVICE_LOCK_VERSION;
import static com.android.devicelock.flags.Flags.FLAG_GET_ENROLLMENT_TYPE;
import static com.android.devicelock.flags.Flags.FLAG_NOTIFY_KIOSK_SETUP_FINISHED;

import android.Manifest.permission;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresFeature;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.text.TextUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Manager used to interact with the system device lock service.
 * The device lock feature is used by special applications ('kiosk apps', downloaded and installed
 * by the device lock solution) to lock and unlock a device.
 * A typical use case is a financed device, where the financing entity has the capability to lock
 * the device in case of a missed payment.
 * When a device is locked, only a limited set of interactions with the device is allowed (for
 * example, placing emergency calls).
 * <p>
 * Use {@link android.content.Context#getSystemService(java.lang.String)}
 * with {@link Context#DEVICE_LOCK_SERVICE} to create a {@link DeviceLockManager}.
 * </p>
 *
 */
@SystemService(Context.DEVICE_LOCK_SERVICE)
@RequiresFeature(PackageManager.FEATURE_DEVICE_LOCK)
public final class DeviceLockManager {
    private static final String TAG = "DeviceLockManager";
    private final IDeviceLockService mService;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "DEVICE_LOCK_ROLE_", value = {
        DEVICE_LOCK_ROLE_FINANCING,
    })
    public @interface DeviceLockRole {}

    /**
     * Constant representing a financed device role, returned by {@link #getKioskApps}.
     */
    public static final int DEVICE_LOCK_ROLE_FINANCING = 0;

    /**
     * Extra passed to the kiosk setup activity containing the version of
     * the Device Lock solution that started the activity.
     *
     * The kiosk setup activity can retrieve the version by calling
     * getIntent().getIntExtra(DeviceLockManager.EXTRA_DEVICE_LOCK_VERSION, 1)
     *
     * This is meant to be used by kiosk apps sharing the same setup
     * activity between the legacy Device Owner(DO) based DeviceLock
     * solution (version 1) and successive versions.
     */
    @FlaggedApi(FLAG_EXTRA_DEVICE_LOCK_VERSION)
    public static final String EXTRA_DEVICE_LOCK_VERSION =
            "android.devicelock.extra.DEVICE_LOCK_VERSION";

    /** @hide */
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ENROLLMENT_TYPE_", value = {
            ENROLLMENT_TYPE_NONE,
            ENROLLMENT_TYPE_FINANCE,
            ENROLLMENT_TYPE_SUBSIDY,
    })
    public @interface EnrollmentType {}

    /**
     * Device not enrolled in any program.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_GET_ENROLLMENT_TYPE)
    public static final int ENROLLMENT_TYPE_NONE = 0;

    /**
     * Device enrolled in the finance program.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_GET_ENROLLMENT_TYPE)
    public static final int ENROLLMENT_TYPE_FINANCE = 1;

    /**
     * Device enrolled in the subsidy program.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_GET_ENROLLMENT_TYPE)
    public static final int ENROLLMENT_TYPE_SUBSIDY = 2;

    /**
     * @hide
     */
    public DeviceLockManager(Context context, IDeviceLockService service) {
        mService = service;
    }

    /**
     * Return the underlying service interface.
     * This is used to implement private APIs between the Device Lock Controller and the
     * Device Lock System Service.
     *
     * @hide
     */
    @NonNull
    public IDeviceLockService getService() {
        return mService;
    }

    /**
     * Locks the device.
     *
     * <p>Exceptions that can be returned through the ParcelableException on the callback's
     * {@code onError} method:
     * <ul>
     *     <li>{@link SecurityException} if the caller is missing the
     *     {@link android.android.Manifest.permission.MANAGE_DEVICE_LOCK_STATE} permission.
     *     <li>{@link IllegalStateException} if the device has already been cleared or if
     *     policies could not be enforced for the lock state.
     *     <li>{@link java.util.concurrent.TimeoutException} if the response from the
     *     underlying binder call is not received within the specified duration
     *     (10 seconds).
     * </ul>
     *
     * @param executor the {@link Executor} on which to invoke the callback.
     * @param callback this returns either success or an exception.
     * @throws RuntimeException if there are binder communications errors
     */
    @RequiresPermission(permission.MANAGE_DEVICE_LOCK_STATE)
    public void lockDevice(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, Exception> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.lockDevice(
                    new IVoidResultCallback.Stub() {
                        @Override
                        public void onSuccess() {
                            executor.execute(() -> callback.onResult(/* result= */ null));
                        }

                        @Override
                        public void onError(ParcelableException parcelableException) {
                            callback.onError(parcelableException.getException());
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unlocks the device.
     *
     * <p>Exceptions that can be returned through the ParcelableException on the callback's
     * {@code onError} method:
     * <ul>
     *     <li>{@link SecurityException} if the caller is missing the
     *     {@link android.android.Manifest.permission.MANAGE_DEVICE_LOCK_STATE} permission.
     *     <li>{@link IllegalStateException} if the device has already been cleared or if
     *     policies could not be enforced for the unlock state.
     *     <li>{@link java.util.concurrent.TimeoutException} If the response from the
     *     underlying binder call is not received within the specified duration
     *     (10 seconds).
     * </ul>
     *
     * @param executor the {@link Executor} on which to invoke the callback.
     * @param callback this returns either success or an exception.
     * @throws RuntimeException if there are binder communications errors
     */
    @RequiresPermission(permission.MANAGE_DEVICE_LOCK_STATE)
    public void unlockDevice(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, Exception> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.unlockDevice(
                    new IVoidResultCallback.Stub() {
                        @Override
                        public void onSuccess() {
                            executor.execute(() -> callback.onResult(/* result= */ null));
                        }

                        @Override
                        public void onError(ParcelableException parcelableException) {
                            callback.onError(parcelableException.getException());
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the device is locked or not.
     *
     * <p>Exceptions that can be returned through the ParcelableException on the callback's
     * {@code onError} method:
     * <ul>
     *     <li>{@link SecurityException} if the caller is missing the
     *     {@link android.android.Manifest.permission.MANAGE_DEVICE_LOCK_STATE} permission.
     *     <li>{@link IllegalStateException} if called before setting the locked state of the device
     *     through {@link #lockDevice} or {@link #unlockDevice}.
     *     <li>{@link java.util.concurrent.TimeoutException} if the response from the
     *     underlying binder call is not received within the specified duration
     *     (10 seconds).
     * </ul>
     *
     * @param executor the {@link Executor} on which to invoke the callback.
     * @param callback this returns either the lock status or an exception.
     * @throws RuntimeException if there are binder communications errors
     */
    @RequiresPermission(permission.MANAGE_DEVICE_LOCK_STATE)
    public void isDeviceLocked(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.isDeviceLocked(
                    new IIsDeviceLockedCallback.Stub() {
                        @Override
                        public void onIsDeviceLocked(boolean locked) {
                            executor.execute(() -> callback.onResult(locked));
                        }

                        @Override
                        public void onError(ParcelableException parcelableException) {
                            executor.execute(() ->
                                    callback.onError(parcelableException.getException()));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clear device restrictions.
     *
     * <p>After a device determines that it's part of a program (e.g. financing) by checking in with
     * the device lock backend, it will go though a provisioning flow and install a kiosk app.
     *
     * <p>At this point, the device is "restricted" and the creditor kiosk app is able to lock
     * the device. For example, a creditor kiosk app in a financing use case may lock the device
     * (using {@link #lockDevice}) if payments are missed and unlock (using {@link #unlockDevice}))
     * once they are resumed.
     *
     * <p>The Device Lock solution will also put in place some additional restrictions when a device
     * is enrolled in the program, namely:
     *
     * <ul>
     *     <li>Disable debugging features
     *     ({@link android.os.UserManager#DISALLOW_DEBUGGING_FEATURES})
     *     <li>Disable installing from unknown sources
     *     ({@link android.os.UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES},
     *     when configured in the backend)
     *     <li>Disable outgoing calls
     *     ({@link android.os.UserManager#DISALLOW_OUTGOING_CALLS},
     *     when configured in the backend and the device is locked)
     * </ul>
     *
     * <p>Once the program is completed (e.g. the device has been fully paid off), the kiosk app
     * can use the {@link #clearDeviceRestrictions} API to lift the above restrictions.
     *
     * <p>At this point, the kiosk app has relinquished its ability to lock the device.
     *
     * <p>Exceptions that can be returned through the ParcelableException on the callback's
     * {@code onError} method:
     * <ul>
     *     <li>{@link SecurityException} if the caller is missing the
     *     {@link android.android.Manifest.permission.MANAGE_DEVICE_LOCK_STATE} permission.
     *     <li>{@link IllegalStateException} if the device has already been cleared or if
     *     policies could not be enforced for the clear state.
     *     <li>{@link java.util.concurrent.TimeoutException} if the response from the
     *     underlying binder call is not received within the specified duration
     *     (10 seconds).
     * </ul>
     *
     * @param executor the {@link Executor} on which to invoke the callback.
     * @param callback this returns either success or an exception.
     * @throws RuntimeException if there are binder communications errors
     */
    @RequiresPermission(permission.MANAGE_DEVICE_LOCK_STATE)
    @FlaggedApi(FLAG_CLEAR_DEVICE_RESTRICTIONS)
    public void clearDeviceRestrictions(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, Exception> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.clearDeviceRestrictions(
                    new IVoidResultCallback.Stub() {
                        @Override
                        public void onSuccess() {
                            executor.execute(() -> callback.onResult(/* result= */ null));
                        }

                        @Override
                        public void onError(ParcelableException parcelableException) {
                            callback.onError(parcelableException.getException());
                        }
                    }
            );
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the device id.
     *
     * <p>Exceptions that can be returned through the ParcelableException on the callback's
     * {@code onError} method:
     * <ul>
     *     <li>{@link SecurityException} if the caller is missing the
     *     {@link android.android.Manifest.permission.MANAGE_DEVICE_LOCK_STATE} permission.
     *     <li>{@link IllegalStateException} if no registered Device ID is found.
     *     <li>{@link java.util.concurrent.TimeoutException} if the response from the
     *     underlying binder call is not received within the specified duration
     *     (10 seconds).
     * </ul>
     *
     * @param executor the {@link Executor} on which to invoke the callback.
     * @param callback this returns either the {@link DeviceId} or an exception.
     * @throws RuntimeException if there are binder communications errors
     */
    @RequiresPermission(permission.MANAGE_DEVICE_LOCK_STATE)
    public void getDeviceId(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<DeviceId, Exception> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getDeviceId(
                    new IGetDeviceIdCallback.Stub() {
                        @Override
                        public void onDeviceIdReceived(int type, String id) {
                            if (TextUtils.isEmpty(id)) {
                                executor.execute(() -> {
                                    callback.onError(new Exception("Cannot get device id (empty)"));
                                });
                            } else {
                                executor.execute(() -> {
                                    callback.onResult(new DeviceId(type, id));
                                });
                            }
                        }

                        @Override
                        public void onError(ParcelableException parcelableException) {
                            callback.onError(parcelableException.getException());
                        }
                    }
            );
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the kiosk app roles and packages.
     *
     * @param executor the {@link Executor} on which to invoke the callback.
     * @param callback this returns either a {@link Map} of device roles/package names,
     *                 or an exception. The Integer in the map represent the device lock role
     *                 (at this moment, the only supported role is
     *                 {@value #DEVICE_LOCK_ROLE_FINANCING}. The String represents the package
     *                 name of the kiosk app for that role.
     * @throws RuntimeException if there are binder communications errors
     */
    @RequiresNoPermission
    public void getKioskApps(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Map<Integer, String>, Exception> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getKioskApps(
                    new IGetKioskAppsCallback.Stub() {
                        @Override
                        public void onKioskAppsReceived(Map kioskApps) {
                            executor.execute(() -> callback.onResult(kioskApps));
                        }

                        @Override
                        public void onError(ParcelableException parcelableException) {
                            callback.onError(parcelableException.getException());
                        }
                    }
            );
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the device lock solution enrollment type.
     *
     * <p>The enrollment type is returned asynchronously by the callback as an integer whose
     * value can be one of {@link ENROLLMENT_TYPE_NONE}, {@link ENROLLMENT_TYPE_FINANCE},
     * {@link ENROLLMENT_TYPE_SUBSIDY}.
     *
     * <p>Exceptions that can be returned through the ParcelableException on the callback's
     * {@code onError} method:
     * <ul>
     *     <li>{@link SecurityException} if the caller is missing the
     *     {@link android.android.Manifest.permission.GET_DEVICE_LOCK_ENROLLMENT_TYPE} permission.
     *     <li>{@link java.util.concurrent.TimeoutException} if the response from the
     *     underlying binder call is not received within the specified duration
     *     (10 seconds).
     * </ul>
     *
     * @param executor the {@link Executor} on which to invoke the callback.
     * @param callback returns either the enrollment type or an exception.
     * @throws RuntimeException if there are binder communications errors
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_GET_ENROLLMENT_TYPE)
    @RequiresPermission(permission.GET_DEVICE_LOCK_ENROLLMENT_TYPE)
    public void getEnrollmentType(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<@EnrollmentType Integer, Exception> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getEnrollmentType(
                    new IGetEnrollmentTypeCallback.Stub() {
                        @Override
                        public void onEnrollmentTypeReceived(@EnrollmentType int enrollmentType) {
                            executor.execute(() -> callback.onResult(enrollmentType));
                        }

                        @Override
                        public void onError(ParcelableException parcelableException) {
                            callback.onError(parcelableException.getException());
                        }
                    }
            );
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies DLC that kiosk set-up has finished and we no longer need to
     * lock to the set-up activity.
     *
     * This will close the set-up activity as specified through a callback and
     * then respect the current device lock state. If the device has never been
     * locked or unlocked by the kiosk, it will unlock the device. Invoking
     * {@link #lockDevice} or {@link #unlockDevice} will also result in the
     * device provision state moving from kiosk_provisioned to
     * provision_success.
     *
     * <p>Exceptions that can be returned through the ParcelableException on the callback's
     * {@code onError} method:
     * <ul>
     *     <li>{@link SecurityException} if the caller is missing the
     *     {@link android.android.Manifest.permission.MANAGE_DEVICE_LOCK_STATE} permission.
     *     <li>{@link IllegalStateException} if the device has already been cleared or if
     *     policies could not be enforced for the lock or unlock state.
     *     <li>{@link java.util.concurrent.TimeoutException} if the response from the
     *     underlying binder call is not received within the specified duration
     *     (10 seconds).
     * </ul>
     *
     * @param executor the {@link Executor} on which to invoke the callback.
     * @param callback this returns either success or an exception.
     * @throws RuntimeException if there are binder communications errors
     */
    @RequiresPermission(permission.MANAGE_DEVICE_LOCK_STATE)
    @FlaggedApi(FLAG_NOTIFY_KIOSK_SETUP_FINISHED)
    public void notifyKioskSetupFinished(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, Exception> callback){
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.notifyKioskSetupFinished(
                    new IVoidResultCallback.Stub() {
                        @Override
                        public void onSuccess() {
                            executor.execute(() -> callback.onResult(/* result= */ null));
                        }

                        @Override
                        public void onError(ParcelableException parcelableException) {
                            callback.onError(parcelableException.getException());
                        }
                    }
            );
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}

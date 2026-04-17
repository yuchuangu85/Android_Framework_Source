/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.security.authenticationpolicy;

import static android.Manifest.permission.MANAGE_SECURE_LOCK_DEVICE;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.companion.Flags.FLAG_SUPPORT_AI_AGENT;
import static android.hardware.biometrics.Flags.FLAG_IDENTITY_CHECK_WATCH;
import static android.Manifest.permission.TEST_BIOMETRIC;
import static android.security.Flags.FLAG_SECURE_LOCKDOWN;
import static android.security.Flags.FLAG_SECURE_LOCK_DEVICE;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.app.KeyguardManager;
import android.companion.DeviceId;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.proximity.IProximityResultCallback;
import android.util.Log;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * AuthenticationPolicyManager is a centralized interface for managing authentication related
 * policies on the device. This includes device locking capabilities to protect users in "at risk"
 * environments.
 *
 * AuthenticationPolicyManager is designed to protect Android users by integrating with apps and
 * key system components, such as the lock screen. It is not related to enterprise control surfaces
 * and does not offer additional administrative controls.
 *
 * <p>
 * To use this class, call {@link #enableSecureLockDevice} to enable secure lock on the device.
 * This will require the caller to have the
 * {@link android.Manifest.permission#MANAGE_SECURE_LOCK_DEVICE} permission.
 *
 * <p>
 * To disable secure lock on the device, call {@link #disableSecureLockDevice}. This will require
 * the caller to have the {@link android.Manifest.permission#MANAGE_SECURE_LOCK_DEVICE} permission.
 *
 * <p>
 * To check if the device meets the requirements to enable secure lock, call
 * {@link #getSecureLockDeviceAvailability}. This will require the caller to have the
 * {@link android.Manifest.permission#MANAGE_SECURE_LOCK_DEVICE} permission.
 *
 * <p>
 * To check if secure lock is already enabled on the device, call
 * {@link #isSecureLockDeviceEnabled}. This will require the caller to have the
 * {@link android.Manifest.permission#MANAGE_SECURE_LOCK_DEVICE} permission.
 *
 *
 * <p>
 * To listen for changes in the availability or enabled / disabled status of Secure Lock Device,
 * register a {@link SecureLockDeviceStatusListener} using
 * {@link #registerSecureLockDeviceStatusListener(Executor, SecureLockDeviceStatusListener)}.
 *
 * To unregister a previously registered listener, use
 * {@link #unregisterSecureLockDeviceStatusListener(SecureLockDeviceStatusListener)}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_SECURE_LOCKDOWN)
@SystemService(Context.AUTHENTICATION_POLICY_SERVICE)
public final class AuthenticationPolicyManager {
    private static final String TAG = "AuthenticationPolicyManager";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;


    @NonNull private final IAuthenticationPolicyService mAuthenticationPolicyService;
    @NonNull private final Context mContext;

    /**
     * Map to store registered client listeners and their corresponding AIDL stubs.
     */
    private final ConcurrentHashMap
            <SecureLockDeviceStatusListener, ISecureLockDeviceStatusListener.Stub>
            mSecureLockDeviceStatusListeners = new ConcurrentHashMap<>();

    /**
     * Success result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Secure lock device request successful.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int SUCCESS = 0;

    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Secure lock device request status unknown.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_UNKNOWN = 1;

    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Secure lock device is unsupported.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_UNSUPPORTED = 2;


    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Invalid secure lock device request params provided.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_INVALID_PARAMS = 3;


    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Secure lock device is unavailable because there are no biometrics enrolled on the device.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_NO_BIOMETRICS_ENROLLED = 4;

    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Secure lock device is unavailable because the device has no biometric hardware or the
     * biometric sensors do not meet
     * {@link android.hardware.biometrics.BiometricManager.Authenticators#BIOMETRIC_STRONG}
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_INSUFFICIENT_BIOMETRICS = 5;

    /**
     * Error result code for {@link #enableSecureLockDevice}.
     *
     * Secure lock is already enabled.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_ALREADY_ENABLED = 6;

    /**
     * Error result code for {@link #disableSecureLockDevice}
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_NOT_AUTHORIZED = 7;

    /**
     * Communicates the current status of a request to enable secure lock on the device.
     *
     * @hide
     */
    @IntDef(prefix = {"ENABLE_SECURE_LOCK_DEVICE_STATUS_"}, value = {
            SUCCESS,
            ERROR_UNKNOWN,
            ERROR_UNSUPPORTED,
            ERROR_INVALID_PARAMS,
            ERROR_NO_BIOMETRICS_ENROLLED,
            ERROR_INSUFFICIENT_BIOMETRICS,
            ERROR_ALREADY_ENABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnableSecureLockDeviceRequestStatus {}

    /**
     * Communicates the current status of a request to disable secure lock on the device.
     *
     * @hide
     */
    @IntDef(prefix = {"DISABLE_SECURE_LOCK_DEVICE_STATUS_"}, value = {
            SUCCESS,
            ERROR_UNKNOWN,
            ERROR_UNSUPPORTED,
            ERROR_INVALID_PARAMS,
            ERROR_NOT_AUTHORIZED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisableSecureLockDeviceRequestStatus {}

    /**
     * Communicates the current status of a request to check if the device meets the requirements
     * for secure lock device.
     *
     * @hide
     */
    @IntDef(prefix = {"GET_SECURE_LOCK_DEVICE_AVAILABILITY_STATUS_"}, value = {
            SUCCESS,
            ERROR_UNSUPPORTED,
            ERROR_NO_BIOMETRICS_ENROLLED,
            ERROR_INSUFFICIENT_BIOMETRICS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GetSecureLockDeviceAvailabilityRequestStatus {}

    /** @hide */
    public AuthenticationPolicyManager(@NonNull Context context,
            @NonNull IAuthenticationPolicyService authenticationPolicyService) {
        mContext = context;
        mAuthenticationPolicyService = authenticationPolicyService;
    }

    /**
     * Listener for updates to Secure Lock Device status. Clients can implement this interface
     * and register it using {@link #registerSecureLockDeviceStatusListener(Executor,
     * SecureLockDeviceStatusListener)} to receive callbacks when the status of secure lock
     * device changes.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCK_DEVICE)
    public interface SecureLockDeviceStatusListener {
        /**
         * Called when the enabled state of secure lock device changes.
         * @param enabled true if secure lock device is now enabled, false otherwise.
         */
        void onSecureLockDeviceEnabledStatusChanged(boolean enabled);

        /**
         * Called when the availability of secure lock device changes for the listening user.
         * @param available An int of type
         * {@link AuthenticationPolicyManager.GetSecureLockDeviceAvailabilityRequestStatus} that
         *                  indicates if the listening user has the necessary requirements to
         *                  enable secure lock device ({@link #SUCCESS} if the user can enable
         *                  secure lock device).
         */
        void onSecureLockDeviceAvailableStatusChanged(
                @GetSecureLockDeviceAvailabilityRequestStatus int available);
    }

    /**
     * Registers a listener for updates to Secure Lock Device status, including whether secure
     * lock device is currently enabled / disabled, and whether the calling user meets the
     * prerequisites to enable secure lock device. The listener is immediately called with the
     * current status upon registration.
     *
     * @param executor The executor on which the listener callbacks will be invoked.
     * @param listener The listener to register for notifications about updates to secure lock
     *                 device status.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCK_DEVICE)
    @RequiresPermission(MANAGE_SECURE_LOCK_DEVICE)
    public void registerSecureLockDeviceStatusListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SecureLockDeviceStatusListener listener
    ) {
        Objects.requireNonNull(executor, "Executor cannot be null");
        Objects.requireNonNull(listener, "Listener cannot be null");

        if (mSecureLockDeviceStatusListeners.containsKey(listener)) {
            if (DEBUG) {
                Log.d(TAG, "registerSecureLockDeviceStatusListener: listener already registered");
            }
            return;
        }

        ISecureLockDeviceStatusListener.Stub stub = new ISecureLockDeviceStatusListener.Stub() {
            @Override
            public void onSecureLockDeviceEnabledStatusChanged(boolean enabled) {
                if (!mSecureLockDeviceStatusListeners.containsKey(listener)) {
                    if (DEBUG) {
                        Log.d(TAG, "Listener " + listener + " no longer registered. Skipping "
                                + "onSecureLockDeviceEnabledStatusChanged(" + enabled + ")");
                    }
                    return;
                }
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() ->
                            listener.onSecureLockDeviceEnabledStatusChanged(enabled));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            @Override
            public void onSecureLockDeviceAvailableStatusChanged(
                    @GetSecureLockDeviceAvailabilityRequestStatus int available) {
                if (!mSecureLockDeviceStatusListeners.containsKey(listener)) {
                    if (DEBUG) {
                        Log.d(TAG, "Listener " + listener + " no longer registered. Skipping "
                                + "onSecureLockDeviceAvailableStatusChanged(" + available + ")");
                    }
                    return;
                }
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() ->
                            listener.onSecureLockDeviceAvailableStatusChanged(available));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        };

        mSecureLockDeviceStatusListeners.put(listener, stub);
        boolean serviceCallSuccessful = false;
        try {
            mAuthenticationPolicyService.registerSecureLockDeviceStatusListener(mContext.getUser(),
                    stub);
            serviceCallSuccessful = true;
            if (DEBUG) {
                Log.d(TAG, "Registered listener: " + listener);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            if (!serviceCallSuccessful) {
                mSecureLockDeviceStatusListeners.remove(listener);
                Log.w(TAG, "Failed to register listener " + listener + "with service, removing"
                        + " from local map.");
            }
        }
    }

    /**
     * Unregisters a previously registered listener for updates to Secure Lock Device status.
     *
     * @param listener The listener to unregister.
     * @throws IllegalArgumentException if the listener was not previously registered.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCK_DEVICE)
    @RequiresPermission(MANAGE_SECURE_LOCK_DEVICE)
    public void unregisterSecureLockDeviceStatusListener(
            @NonNull SecureLockDeviceStatusListener listener
    ) {
        Objects.requireNonNull(listener, "Listener cannot be null");
        ISecureLockDeviceStatusListener.Stub stub =
                mSecureLockDeviceStatusListeners.remove(listener);

        if (stub == null) {
            Log.d(TAG, "unregisterSecureLockDeviceStatusListener: listener not registered");
            return;
        }

        try {
            mAuthenticationPolicyService.unregisterSecureLockDeviceStatusListener(stub);
            if (DEBUG) {
                Log.d(TAG, "Unregistered listener: " + listener);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a privileged component to indicate if secure lock device is available for the
     * calling user.
     *
     * @return {@link GetSecureLockDeviceAvailabilityRequestStatus} int indicating whether secure
     * lock device is available for the calling user. This will return {@link #SUCCESS} if the
     * device meets all requirements to enable secure lock device,
     * {@link #ERROR_INSUFFICIENT_BIOMETRICS} if the device does not have a biometric sensor of
     * sufficient strength, {@link #ERROR_NO_BIOMETRICS_ENROLLED} if the user does not have
     * enrollments on the strong biometric sensor, or {@link #ERROR_UNSUPPORTED} if secure lock
     * device is otherwise unsupported.
     *
     * @hide
     */
    @GetSecureLockDeviceAvailabilityRequestStatus
    @RequiresPermission(MANAGE_SECURE_LOCK_DEVICE)
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCK_DEVICE)
    public int getSecureLockDeviceAvailability() {
        try {
            return mAuthenticationPolicyService.getSecureLockDeviceAvailability(mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Called by a privileged component to remotely enable secure lock on the device across all
     * users. This operation will first check {@link #getSecureLockDeviceAvailability()} to see if
     * the calling user meets the requirements to enable secure lock device, including a strong
     * biometric enrollment, and will return an error if not.
     *
     * Secure lock is an enhanced security state that restricts access to sensitive data (app
     * notifications, widgets, quick settings, assistant, etc), and locks the device under the
     * calling user's credentials with multi-factor authentication for device entry, such as
     * {@link android.hardware.biometrics.BiometricManager.Authenticators#DEVICE_CREDENTIAL} and
     * {@link android.hardware.biometrics.BiometricManager.Authenticators#BIOMETRIC_STRONG}.
     *
     * If secure lock is already enabled when this method is called, it will return
     * {@link #ERROR_ALREADY_ENABLED}.
     *
     * @param params {@link EnableSecureLockDeviceParams} for caller to supply params related to
     *                                                   the secure lock device request
     * @return {@link EnableSecureLockDeviceRequestStatus} int indicating the result of the secure
     * lock device request. This returns {@link #SUCCESS} if secure lock device is successfully
     * enabled, or an error code indicating more information about the failure otherwise.
     *
     * @hide
     */
    @EnableSecureLockDeviceRequestStatus
    @RequiresPermission(MANAGE_SECURE_LOCK_DEVICE)
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public int enableSecureLockDevice(@NonNull EnableSecureLockDeviceParams params) {
        try {
            return mAuthenticationPolicyService.enableSecureLockDevice(mContext.getUser(), params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a privileged component to disable secure lock on the device across all users. This
     * operation is restricted to the user that originally enabled the current secure lock device
     * state.
     *
     * If the calling user identity does not match the user that enabled secure lock device, it
     * will return {@link #ERROR_NOT_AUTHORIZED}
     *
     * If secure lock is already disabled when this method is called, it will return
     * {@link #SUCCESS}.
     *
     * @param params {@link DisableSecureLockDeviceParams} for caller to supply params related to
     *                                                    the secure lock device request
     * @return {@link DisableSecureLockDeviceRequestStatus} int indicating the result of the secure
     * lock device request
     *
     * @hide
     */
    @DisableSecureLockDeviceRequestStatus
    @RequiresPermission(MANAGE_SECURE_LOCK_DEVICE)
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public int disableSecureLockDevice(@NonNull DisableSecureLockDeviceParams params) {
        try {
            return mAuthenticationPolicyService.disableSecureLockDevice(mContext.getUser(), params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a privileged component to query if secure lock device is currently enabled.
     * @return true if secure lock device is enabled, false otherwise.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_SECURE_LOCK_DEVICE)
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCK_DEVICE)
    public boolean isSecureLockDeviceEnabled() {
        try {
            return mAuthenticationPolicyService.isSecureLockDeviceEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets test mode for Secure Lock Device. This allows tests to indicate that security features
     * that would interfere with testing (disabling ADB, USB) should be skipped.
     * @hide
     */
    @TestApi
    @RequiresPermission(TEST_BIOMETRIC)
    @FlaggedApi(FLAG_SECURE_LOCK_DEVICE)
    public void setSecureLockDeviceTestStatus(boolean isTestMode) {
        try {
            Slog.d(TAG, "#setTestModeForSecureLockDevice(isTestMode=" + isTestMode + ")");
            mAuthenticationPolicyService.setSecureLockDeviceTestStatus(isTestMode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This function will start watch ranging for Identity Check. We will remove specific
     * Identity Check implementation when this is generalized.
     *
     * @param resultCallback callback used to return the ranging result
     * @param handler handler to start the ranging request
     *
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @FlaggedApi(FLAG_IDENTITY_CHECK_WATCH)
    public void startWatchRangingForIdentityCheck(long authenticationRequestId,
            @NonNull IProximityResultCallback resultCallback, @NonNull Handler handler) {
        try {
            mAuthenticationPolicyService.startWatchRangingForIdentityCheck(
                    authenticationRequestId, new ProximityResultCallbackWrapper(
                            handler, resultCallback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Cancels watch ranging for the given authentication request id.
     *
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @FlaggedApi(FLAG_IDENTITY_CHECK_WATCH)
    public void cancelWatchRangingForRequestId(long authenticationRequestId) {
        try {
            mAuthenticationPolicyService.cancelWatchRangingForRequestId(authenticationRequestId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if a cross device agent is authorized for automation on this device when locked.
     *
     * When the companionDeviceId is null this method assumes the agent is running on the same
     * device and will return the same value as calling {@link KeyguardManager#isDeviceLocked()}.
     *
     * @param companionDeviceId DeviceId from the CompanionDeviceManager or null
     * @return true if authorized now
     *
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @UserHandleAware
    @FlaggedApi(FLAG_SUPPORT_AI_AGENT)
    public boolean isAgentAuthorized(@Nullable DeviceId companionDeviceId) {
        try {
            return mAuthenticationPolicyService.isAgentAuthorized(
                    mContext.getUser(), mContext.getDeviceId(), companionDeviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Per-user per-device version of {@link #isAgentAuthorized(DeviceId)}.
     *
     * @param userId user id
     * @param deviceId local device id
     * @param companionDeviceId remote DeviceId from the CompanionDeviceManager or null
     *
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @FlaggedApi(FLAG_SUPPORT_AI_AGENT)
    public boolean isAgentAuthorized(
            @UserIdInt int userId, int deviceId, @Nullable DeviceId companionDeviceId) {
        try {
            return mAuthenticationPolicyService.isAgentAuthorized(
                    UserHandle.of(userId), deviceId, companionDeviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if watch ranging is available. Returns value using IProximityResultCallback.
     *
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    @FlaggedApi(FLAG_IDENTITY_CHECK_WATCH)
    public void isWatchRangingAvailable(@NonNull IProximityResultCallback resultCallback) {
        try {
            mAuthenticationPolicyService.isWatchRangingAvailable(resultCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static final class ProximityResultCallbackWrapper
            extends IProximityResultCallback.Stub {
        private final Handler mHandler;
        private final IProximityResultCallback mProximityResultCallback;

        ProximityResultCallbackWrapper(Handler handler,
                IProximityResultCallback proximityResultCallback) {
            mHandler = handler;
            mProximityResultCallback = proximityResultCallback;
        }

        @Override
        public void onError(int error) {
            mHandler.post(() -> {
                final long id = Binder.clearCallingIdentity();
                try {
                    mProximityResultCallback.onError(error);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception thrown when proximity callback invoked " + e);
                } finally {
                    Binder.restoreCallingIdentity(id);
                }
            });
        }

        @Override
        public void onSuccess(int result) {
            mHandler.post(() -> {
                final long id = Binder.clearCallingIdentity();
                try {
                    mProximityResultCallback.onSuccess(result);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception thrown when proximity callback invoked " + e);
                } finally {
                    Binder.restoreCallingIdentity(id);
                }
            });
        }
    }
}

/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.TaskStackListener;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricFingerprintConstants.FingerprintAcquired;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.CallbackWithProbe;
import com.android.server.biometrics.log.Probe;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.SensorOverlays;
import com.android.server.biometrics.sensors.fingerprint.Udfps;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Fingerprint-specific authentication client supporting the
 * {@link android.hardware.biometrics.fingerprint.IFingerprint} AIDL interface.
 */
class FingerprintAuthenticationClient extends AuthenticationClient<AidlSession> implements
        Udfps, LockoutConsumer {
    private static final String TAG = "FingerprintAuthenticationClient";

    @NonNull private final LockoutCache mLockoutCache;
    @NonNull private final SensorOverlays mSensorOverlays;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProps;
    @NonNull private final CallbackWithProbe<Probe> mALSProbeCallback;

    @Nullable private ICancellationSignal mCancellationSignal;
    private boolean mIsPointerDown;

    FingerprintAuthenticationClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, int targetUserId, long operationId,
            boolean restricted, @NonNull String owner, int cookie, boolean requireConfirmation,
            int sensorId,
            @NonNull BiometricLogger biometricLogger, @NonNull BiometricContext biometricContext,
            boolean isStrongBiometric,
            @Nullable TaskStackListener taskStackListener, @NonNull LockoutCache lockoutCache,
            @Nullable IUdfpsOverlayController udfpsOverlayController,
            @Nullable ISidefpsController sidefpsController,
            boolean allowBackgroundAuthentication,
            @NonNull FingerprintSensorPropertiesInternal sensorProps) {
        super(context, lazyDaemon, token, listener, targetUserId, operationId, restricted, owner,
                cookie, requireConfirmation, sensorId,
                biometricLogger, biometricContext,
                isStrongBiometric, taskStackListener,
                lockoutCache, allowBackgroundAuthentication, true /* shouldVibrate */,
                false /* isKeyguardBypassEnabled */);
        setRequestId(requestId);
        mLockoutCache = lockoutCache;
        mSensorOverlays = new SensorOverlays(udfpsOverlayController, sidefpsController);
        mSensorProps = sensorProps;
        mALSProbeCallback = getLogger().createALSCallback(false /* startWithClient */);
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);

        if (mSensorProps.isAnyUdfpsType()) {
            // UDFPS requires user to touch before becoming "active"
            mState = STATE_STARTED_PAUSED;
        } else {
            mState = STATE_STARTED;
        }
    }

    @NonNull
    @Override
    protected ClientMonitorCallback wrapCallbackForStart(@NonNull ClientMonitorCallback callback) {
        return new ClientMonitorCompositeCallback(mALSProbeCallback,
                getBiometricContextUnsubscriber(), callback);
    }

    @Override
    protected void handleLifecycleAfterAuth(boolean authenticated) {
        if (authenticated) {
            mCallback.onClientFinished(this, true /* success */);
        }
    }

    @Override
    public boolean wasUserDetected() {
        // TODO: Update if it needs to be used for fingerprint, i.e. success/reject, error_timeout
        return false;
    }

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token) {
        super.onAuthenticated(identifier, authenticated, token);

        if (authenticated) {
            mState = STATE_STOPPED;
            mSensorOverlays.hide(getSensorId());
        } else {
            mState = STATE_STARTED_PAUSED_ATTEMPTED;
        }
    }

    @Override
    public void onAcquired(@FingerprintAcquired int acquiredInfo, int vendorCode) {
        // For UDFPS, notify SysUI with acquiredInfo, so that the illumination can be turned off
        // for most ACQUIRED messages. See BiometricFingerprintConstants#FingerprintAcquired
        mSensorOverlays.ifUdfps(controller -> controller.onAcquired(getSensorId(), acquiredInfo));
        super.onAcquired(acquiredInfo, vendorCode);
    }

    @Override
    public void onError(int errorCode, int vendorCode) {
        super.onError(errorCode, vendorCode);

        if (errorCode == BiometricFingerprintConstants.FINGERPRINT_ERROR_BAD_CALIBRATION) {
            BiometricNotificationUtils.showBadCalibrationNotification(getContext());
        }

        mSensorOverlays.hide(getSensorId());
    }

    @Override
    protected void startHalOperation() {
        mSensorOverlays.show(getSensorId(), getShowOverlayReason(), this);

        try {
            mCancellationSignal = doAuthenticate();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    0 /* vendorCode */);
            mSensorOverlays.hide(getSensorId());
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    private ICancellationSignal doAuthenticate() throws RemoteException {
        final AidlSession session = getFreshDaemon();

        if (session.hasContextMethods()) {
            final OperationContext opContext = getOperationContext();
            final ICancellationSignal cancel =  session.getSession().authenticateWithContext(
                    mOperationId, opContext);
            getBiometricContext().subscribe(opContext, ctx -> {
                try {
                    session.getSession().onContextChanged(ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify context changed", e);
                }
            });
            return cancel;
        } else {
            return session.getSession().authenticate(mOperationId);
        }
    }

    @Override
    protected void stopHalOperation() {
        mSensorOverlays.hide(getSensorId());
        unsubscribeBiometricContext();

        if (mCancellationSignal != null) {
            try {
                mCancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                onError(BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
                mCallback.onClientFinished(this, false /* success */);
            }
        } else {
            Slog.e(TAG, "cancellation signal was null");
        }
    }

    @Override
    public void onPointerDown(int x, int y, float minor, float major) {
        try {
            mIsPointerDown = true;
            mState = STATE_STARTED;
            mALSProbeCallback.getProbe().enable();

            final AidlSession session = getFreshDaemon();
            if (session.hasContextMethods()) {
                final PointerContext context = new PointerContext();
                context.pointerId = 0;
                context.x = x;
                context.y = y;
                context.minor = minor;
                context.major = major;
                context.isAod = getBiometricContext().isAod();
                session.getSession().onPointerDownWithContext(context);
            } else {
                session.getSession().onPointerDown(0 /* pointerId */, x, y, minor, major);
            }

            if (getListener() != null) {
                getListener().onUdfpsPointerDown(getSensorId());
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public void onPointerUp() {
        try {
            mIsPointerDown = false;
            mState = STATE_STARTED_PAUSED_ATTEMPTED;
            mALSProbeCallback.getProbe().disable();

            final AidlSession session = getFreshDaemon();
            if (session.hasContextMethods()) {
                final PointerContext context = new PointerContext();
                context.pointerId = 0;
                session.getSession().onPointerUpWithContext(context);
            } else {
                session.getSession().onPointerUp(0 /* pointerId */);
            }

            if (getListener() != null) {
                getListener().onUdfpsPointerUp(getSensorId());
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public boolean isPointerDown() {
        return mIsPointerDown;
    }

    @Override
    public void onUiReady() {
        try {
            getFreshDaemon().getSession().onUiReady();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public void onLockoutTimed(long durationMillis) {
        super.onLockoutTimed(durationMillis);
        mLockoutCache.setLockoutModeForUser(getTargetUserId(), LockoutTracker.LOCKOUT_TIMED);
        // Lockout metrics are logged as an error code.
        final int error = BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT;
        getLogger().logOnError(getContext(), getOperationContext(),
                error, 0 /* vendorCode */, getTargetUserId());

        try {
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }

        mSensorOverlays.hide(getSensorId());
        mCallback.onClientFinished(this, false /* success */);
    }

    @Override
    public void onLockoutPermanent() {
        super.onLockoutPermanent();
        mLockoutCache.setLockoutModeForUser(getTargetUserId(), LockoutTracker.LOCKOUT_PERMANENT);
        // Lockout metrics are logged as an error code.
        final int error = BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;
        getLogger().logOnError(getContext(), getOperationContext(),
                error, 0 /* vendorCode */, getTargetUserId());

        try {
            getListener().onError(getSensorId(), getCookie(), error, 0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }

        mSensorOverlays.hide(getSensorId());
        mCallback.onClientFinished(this, false /* success */);
    }
}

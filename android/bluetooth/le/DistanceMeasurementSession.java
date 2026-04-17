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

package android.bluetooth.le;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothUtils.executeFromBinder;

import static java.util.Objects.requireNonNull;

import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IDistanceMeasurement;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.content.AttributionSource;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * This class provides a way to control an active distance measurement session.
 *
 * <p>It also defines the required {@link DistanceMeasurementSession.Callback} that must be
 * implemented in order to be notified of distance measurement results and status events related to
 * the {@link DistanceMeasurementSession}.
 *
 * <p>To get an instance of {@link DistanceMeasurementSession}, first use {@link
 * DistanceMeasurementManager#startMeasurementSession(DistanceMeasurementParams, Executor,
 * DistanceMeasurementSession.Callback)} to request to start a session. Once the session is started,
 * a {@link DistanceMeasurementSession} object is provided through {@link
 * DistanceMeasurementSession.Callback#onStarted(DistanceMeasurementSession)}. If starting a session
 * fails, the failure is reported through {@link
 * DistanceMeasurementSession.Callback#onStartFail(int)} with the failure reason.
 */
@Hide
@SystemApi
public final class DistanceMeasurementSession {
    private static final String TAG = DistanceMeasurementSession.class.getSimpleName();

    private final IDistanceMeasurement mDistanceMeasurement;
    private final ParcelUuid mUuid;
    private final DistanceMeasurementParams mDistanceMeasurementParams;
    private final Executor mExecutor;
    private final Callback mCallback;
    private final AttributionSource mAttributionSource;

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL,
            })
    public @interface StopSessionReturnValues {}

    @Hide
    public DistanceMeasurementSession(
            IDistanceMeasurement distanceMeasurement,
            ParcelUuid uuid,
            DistanceMeasurementParams params,
            Executor executor,
            AttributionSource source,
            Callback callback) {
        mDistanceMeasurement = requireNonNull(distanceMeasurement);
        mDistanceMeasurementParams = requireNonNull(params);
        mExecutor = requireNonNull(executor);
        mCallback = requireNonNull(callback);
        mUuid = uuid;
        mAttributionSource = source;
    }

    /**
     * Stops actively ranging, {@link Callback#onStopped} will be invoked if this succeeds.
     *
     * @return whether successfully stop or not
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @StopSessionReturnValues int stopSession() {
        try {
            return mDistanceMeasurement.stopDistanceMeasurement(
                    mUuid,
                    mDistanceMeasurementParams.getDevice(),
                    mDistanceMeasurementParams.getMethodId(),
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
    }

    @Hide
    void onStarted() {
        executeFromBinder(mExecutor, () -> mCallback.onStarted(this));
    }

    @Hide
    void onStartFail(int reason) {
        executeFromBinder(mExecutor, () -> mCallback.onStartFail(reason));
    }

    @Hide
    void onStopped(int reason) {
        executeFromBinder(mExecutor, () -> mCallback.onStopped(this, reason));
    }

    @Hide
    void onResult(@NonNull BluetoothDevice device, @NonNull DistanceMeasurementResult result) {
        executeFromBinder(mExecutor, () -> mCallback.onResult(device, result));
    }

    /** Interface for receiving {@link DistanceMeasurementSession} events. */
    @Hide
    @SystemApi
    public interface Callback {
        @Hide
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                value = {
                    BluetoothStatusCodes.ERROR_UNKNOWN,
                    BluetoothStatusCodes.FEATURE_NOT_SUPPORTED,
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED,
                    BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST,
                    BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST,
                    BluetoothStatusCodes.REASON_REMOTE_REQUEST,
                    BluetoothStatusCodes.ERROR_TIMEOUT,
                    BluetoothStatusCodes.ERROR_NO_LE_CONNECTION,
                    BluetoothStatusCodes.ERROR_BAD_PARAMETERS,
                    BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL,
                })
        @interface Reason {}

        /**
         * Invoked when {@link DistanceMeasurementManager#startMeasurementSession(
         * DistanceMeasurementParams, Executor, DistanceMeasurementSession.Callback)} is successful.
         *
         * @param session the started {@link DistanceMeasurementSession}
         */
        @Hide
        @SystemApi
        void onStarted(@NonNull DistanceMeasurementSession session);

        /**
         * Invoked if {@link DistanceMeasurementManager#startMeasurementSession(
         * DistanceMeasurementParams, Executor, DistanceMeasurementSession.Callback)} fails.
         *
         * @param reason the failure reason
         */
        @Hide
        @SystemApi
        void onStartFail(@Reason int reason);

        /**
         * Invoked when a distance measurement session stopped.
         *
         * @param reason reason for the session stop
         */
        @Hide
        @SystemApi
        void onStopped(@NonNull DistanceMeasurementSession session, @Reason int reason);

        /**
         * Invoked when get distance measurement result.
         *
         * @param device remote device
         * @param result {@link DistanceMeasurementResult} for this device
         */
        @Hide
        @SystemApi
        void onResult(@NonNull BluetoothDevice device, @NonNull DistanceMeasurementResult result);
    }
}

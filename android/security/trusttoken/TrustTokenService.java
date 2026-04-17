/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.security.trusttoken;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.security.Flags;
import android.util.Log;
import android.util.Slog;

/**
 * A service that can fetch a set of trust tokens from a remote source and make them available to
 * the system.
 *
 * <p>To implement a trust token service, you must extend this class and implement the {@link
 * #onRequestTrustTokens(TrustTokenRequest, OutcomeReceiver)} method. The system will call this
 * method to communicate its current need for trust tokens. Your service is responsible for
 * satisfying these needs by fetching trust tokens and returning them back to the system by calling
 * the {@link OutcomeReceiver} you were given.
 *
 * <p>For transient errors, you should retry internally until timeout. For permanent errors, you
 * should immediately report the error to the system via the callback.
 *
 * <p>You must declare your service in the application's manifest file. The service must be
 * protected by the {@code android.permission.BIND_TRUST_TOKEN_SERVICE} permission and include an
 * intent filter for the {@link #SERVICE_INTERFACE} action. For example:
 *
 * <pre>{@code
 * <service
 *        android:name=".MyTrustTokenService"
 *        android:label="@string/trust_token_service_name"
 *        android:exported="true"
 *        android:permission="android.permission.BIND_TRUST_TOKEN_SERVICE">
 *    <intent-filter>
 *        <action android:name="android.security.trusttoken.TrustTokenService" />
 *    </intent-filter>
 * </service>
 * }</pre>
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_TALISMAN_SERVICE_API)
@SystemApi
public abstract class TrustTokenService extends Service {
    private static final String TAG = "TrustTokenService";

    /** The Intent action that a TrustTokenService must respond to. */
    public static final String SERVICE_INTERFACE = "android.security.trusttoken.TrustTokenService";

    private final ITrustTokenService.Stub mBinder =
            new ITrustTokenService.Stub() {
                @Override
                public void onRequestTrustTokens(
                        TrustTokenRequest request, ITrustTokenCallback callback) {
                    ICancellationSignal cancellationSignal = CancellationSignal.createTransport();
                    TrustTokenService.this.onRequestTrustTokens(
                            request,
                            CancellationSignal.fromTransport(cancellationSignal),
                            new OutcomeReceiver<>() {
                                @Override
                                public void onResult(@NonNull TrustTokenResponse response) {
                                    try {
                                        callback.onSuccess(response);
                                    } catch (RemoteException e) {
                                        Log.e(TAG, "Failed to call onSuccess", e);
                                    }
                                }

                                @Override
                                public void onError(@NonNull TrustTokenServiceException e) {
                                    try {
                                        callback.onFailure(e.getErrorCode());
                                    } catch (RemoteException re) {
                                        Log.e(TAG, "Failed to call onFailure", re);
                                    }
                                }
                            });
                    try {
                        callback.onRemoteCancellationSignal(cancellationSignal);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error when sending back the cancellation signal", e);
                    }
                }
            };

    @Nullable
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        if (intent != null && SERVICE_INTERFACE.equals(intent.getAction())) {
            return mBinder;
        }
        Log.w(TAG, "Unsupported action " + (intent == null ? "null" : intent.getAction()));
        return null;
    }

    /**
     * Validates the requestor device and then fetches trust tokens for the given request from the
     * remote server.
     *
     * <p>This method is called by the system when it needs trust tokens and/or root authority keys
     * and intermediate certificates, and the system should implement the callback to receive the
     * results or the error returned by the remote server.
     *
     * <p>If fetching trust tokens takes a long time, implementations should move the actual work to
     * a background thread to avoid blocking the main thread.
     *
     * @param request The request to fetch trust tokens and other data from the remote server.
     * @param cancellationSignal The cancellation signal to indicate if the request is cancelled.
     *     The service can set a callback on cancellation by {@link
     *     CancellationSignal#setOnCancelListener}. The service should invoke {@link
     *     OutcomeReceiver#onError} with {@link TrustTokenServiceException#ERROR_CANCELLED} instead
     *     of {@link OutcomeReceiver#onResult} upon cancellation.
     * @param callback The callback to invoke. Call {@link OutcomeReceiver#onResult} when the trust
     *     tokens and other data are available, or {@link OutcomeReceiver#onError} if a remote error
     *     occurred.
     */
    public abstract void onRequestTrustTokens(
            @NonNull TrustTokenRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<TrustTokenResponse, TrustTokenServiceException> callback);
}

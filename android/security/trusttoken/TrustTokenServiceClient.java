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
import android.annotation.TestApi;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.security.Flags;

/**
 * The client to TrustTokenService. This is only for CTS.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_TALISMAN_SERVICE_API)
@TestApi
public final class TrustTokenServiceClient {
    private final ITrustTokenService mService;

    public TrustTokenServiceClient(@NonNull IBinder binder) {
        mService = ITrustTokenService.Stub.asInterface(binder);
    }

    /**
     * Requests trust tokens from the TrustTokenService.
     *
     * @param request The {@link TrustTokenRequest} containing the details of the request.
     * @param callback The {@link OutcomeReceiver} to receive the {@link TrustTokenResponse} on
     *     success or a {@link TrustTokenServiceException} on failure.
     * @return A {@link CancellationSignal} that can be used to cancel the ongoing request.
     */
    @NonNull
    public CancellationSignal requestTrustTokens(
            @NonNull TrustTokenRequest request,
            @NonNull OutcomeReceiver<TrustTokenResponse, TrustTokenServiceException> callback) {
        try {
            var cancellation = new CancellationSignal();
            mService.onRequestTrustTokens(
                    request,
                    new ITrustTokenCallback.Stub() {
                        @Override
                        public void onSuccess(TrustTokenResponse response) {
                            callback.onResult(response);
                        }

                        @Override
                        public void onRemoteCancellationSignal(ICancellationSignal signal) {
                            cancellation.setRemote(signal);
                        }

                        @Override
                        public void onFailure(int code) {
                            callback.onError(new TrustTokenServiceException(code, ""));
                        }
                    });
            return cancellation;
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}

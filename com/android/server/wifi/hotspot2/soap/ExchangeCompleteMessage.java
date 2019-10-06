/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.hotspot2.soap;

import android.annotation.NonNull;
import android.util.Log;

import org.ksoap2.serialization.SoapObject;

/**
 * Represents the sppExchangeComplete message sent by the server.
 * For the details, refer to A.3.2 section in Hotspot2.0 rel2 specification.
 */
public class ExchangeCompleteMessage extends SppResponseMessage  {
    private static final String TAG = "PasspointExchangeCompleteMessage";

    private ExchangeCompleteMessage(@NonNull SoapObject response) throws IllegalArgumentException {
        super(response, MessageType.EXCHANGE_COMPLETE);
    }

    /**
     * create an instance of {@link ExchangeCompleteMessage}
     *
     * @param response SOAP response message received from server.
     * @return Instance of {@link ExchangeCompleteMessage}, {@code null} in any failure.
     */
    public static ExchangeCompleteMessage createInstance(@NonNull SoapObject response) {
        ExchangeCompleteMessage exchangeCompleteMessage;

        try {
            exchangeCompleteMessage = new ExchangeCompleteMessage(response);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "fails to create an Instance: " + e);
            return null;
        }
        return exchangeCompleteMessage;
    }
}

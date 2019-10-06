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

import org.ksoap2.serialization.SoapObject;

/**
 * Utility to parse the raw soap SPP (Subscription Provisioning Protocol) response message
 * sent by server and make the instance of {@link SppResponseMessage}
 */
public class SoapParser {
    /**
     * Get a SppResponseMessage from the original SOAP response.
     *
     * @param response original SOAP response sent by server
     * @return {@link SppResponseMessage}, or {@code null} in any failure
     */
    public static SppResponseMessage getResponse(@NonNull SoapObject response) {
        SppResponseMessage responseMessage;
        switch (response.getName()) {
            case "sppPostDevDataResponse":
                responseMessage = PostDevDataResponse.createInstance(response);
                break;
            case "sppExchangeComplete":
                responseMessage = ExchangeCompleteMessage.createInstance(response);
                break;
            default:
                responseMessage = null;
        }
        return responseMessage;
    }
}

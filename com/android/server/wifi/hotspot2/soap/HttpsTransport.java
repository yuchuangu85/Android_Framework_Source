/*
 * Copyright 2018 The Android Open Source Project
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
import android.net.Network;

import org.ksoap2.transport.HttpTransportSE;
import org.ksoap2.transport.ServiceConnection;

import java.io.IOException;
import java.net.URL;

/**
 * Https Transport Layer for SOAP message over the {@link HttpsServiceConnection}.
 */
public class HttpsTransport extends HttpTransportSE {
    private Network mNetwork;
    private URL mUrl;
    private ServiceConnection mServiceConnection;

    private HttpsTransport(@NonNull Network network, @NonNull URL url) {
        super(url.toString());
        mNetwork = network;
        mUrl = url;
    }

    /**
     * Create an instance of {@link HttpsTransport}.
     *
     * @param network instance of {@link Network} that indicates current connection.
     * @param url server url used for HTTPS connection.
     * @return instance of {@link HttpsTransport}
     */
    public static HttpsTransport createInstance(@NonNull Network network, @NonNull URL url) {
        return new HttpsTransport(network, url);
    }

    @Override
    public ServiceConnection getServiceConnection() throws IOException {
        if (mServiceConnection == null) {
            mServiceConnection = new HttpsServiceConnection(mNetwork, mUrl);
        }
        return mServiceConnection;
    }
}

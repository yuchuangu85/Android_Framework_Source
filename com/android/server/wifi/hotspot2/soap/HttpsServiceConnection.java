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

import android.net.Network;
import android.text.TextUtils;

import org.ksoap2.HeaderProperty;
import org.ksoap2.transport.ServiceConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * A wrapper class for {@link HttpsURLConnection} that requires {@link Network} to open the
 * https connection for SOAP message.
 */
public class HttpsServiceConnection implements ServiceConnection {
    // TODO(117906601): find an optimal value for a connection timeout
    public static final int DEFAULT_TIMEOUT_MS  = 5000; // 5 seconds
    private HttpsURLConnection mConnection;

    public HttpsServiceConnection(Network network, URL url) throws IOException {
        mConnection = (HttpsURLConnection) network.openConnection(url);
        mConnection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
        mConnection.setReadTimeout(DEFAULT_TIMEOUT_MS);
    }

    @Override
    public void connect() throws IOException {
        mConnection.connect();
    }

    @Override
    public void disconnect() {
        mConnection.disconnect();
    }

    @Override
    public List<HeaderProperty> getResponseProperties() {
        Map<String, List<String>> properties = mConnection.getHeaderFields();
        Set<String> keys = properties.keySet();
        List<HeaderProperty> retList = new ArrayList<>();

        keys.forEach(key -> {
            List<String> values = properties.get(key);
            values.forEach(value -> retList.add(new HeaderProperty(key, value)));
        });

        return retList;
    }

    @Override
    public int getResponseCode() throws IOException {
        return mConnection.getResponseCode();
    }

    @Override
    public void setRequestProperty(String propertyName, String value) {
        // Ignore any settings of "the Connection: close" as android network uses the keep alive
        // by default.
        if (!TextUtils.equals("Connection", propertyName) || !TextUtils.equals("close", value)) {
            mConnection.setRequestProperty(propertyName, value);
        }
    }

    @Override
    public void setRequestMethod(String requestMethodType) throws IOException {
        mConnection.setRequestMethod(requestMethodType);
    }

    @Override
    public void setFixedLengthStreamingMode(int contentLength) {
        mConnection.setFixedLengthStreamingMode(contentLength);
    }

    @Override
    public void setChunkedStreamingMode() {
        mConnection.setChunkedStreamingMode(0);
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        return mConnection.getOutputStream();
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return mConnection.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return mConnection.getErrorStream();
    }

    @Override
    public String getHost() {
        return mConnection.getURL().getHost();
    }

    @Override
    public int getPort() {
        return mConnection.getURL().getPort();
    }


    @Override
    public String getPath() {
        return mConnection.getURL().getPath();
    }

    /**
     * Wrapper function for {@link HttpsURLConnection#setSSLSocketFactory(SSLSocketFactory)}
     *
     * @param sslSocketFactory SSL Socket factory
     */
    public void setSSLSocketFactory(SSLSocketFactory sslSocketFactory) {
        mConnection.setSSLSocketFactory(sslSocketFactory);
    }
}

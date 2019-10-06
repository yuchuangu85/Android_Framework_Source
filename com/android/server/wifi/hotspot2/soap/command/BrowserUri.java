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

package com.android.server.wifi.hotspot2.soap.command;

import android.text.TextUtils;
import android.util.Log;

import org.ksoap2.serialization.PropertyInfo;

import java.util.Objects;

/**
 * Represents URI of LaunchBrowser command defined by SPP (Subscription Provisioning Protocol).
 */
public class BrowserUri implements SppCommand.SppCommandData {
    private static final String TAG = "PasspointBrowserUri";
    private final String mUri;

    private BrowserUri(PropertyInfo command) {
        mUri = command.getValue().toString();
    }

    /**
     * Create an instance of {@link BrowserUri}
     *
     * @param command command message embedded in SOAP sppPostDevDataResponse.
     * @return instance of {@link BrowserUri}, {@code null} in any failure.
     */
    public static BrowserUri createInstance(PropertyInfo command) {
        if (!TextUtils.equals(command.getName(), "launchBrowserToURI")) {
            Log.e(TAG, "received wrong command : " + ((command == null) ? "" : command.getName()));
            return null;
        }
        return new BrowserUri(command);
    }

    public String getUri() {
        return mUri;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) return true;
        if (!(thatObject instanceof BrowserUri)) return false;
        BrowserUri that = (BrowserUri) thatObject;
        return TextUtils.equals(mUri, that.mUri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUri);
    }

    @Override
    public String toString() {
        return "BrowserUri{mUri: " + mUri + "}";
    }
}

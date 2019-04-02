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

package com.android.internal.telephony.dataconnection;

import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.Rlog;

import java.util.ArrayList;
import java.util.List;

/**
 * This class represents the transport manager which manages available transports and
 * route requests to correct transport.
 */
public class TransportManager {
    private static final String TAG = TransportManager.class.getSimpleName();

    private List<Integer> mAvailableTransports = new ArrayList<>();

    public TransportManager() {
        // TODO: get transpot list from AccessNetworkManager.
        mAvailableTransports.add(TransportType.WWAN);
    }

    public List<Integer> getAvailableTransports() {
        return new ArrayList<>(mAvailableTransports);
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}

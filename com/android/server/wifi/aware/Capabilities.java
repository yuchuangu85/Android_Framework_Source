/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.aware;

import android.hardware.wifi.V1_0.NanCipherSuiteType;
import android.net.wifi.aware.Characteristics;
import android.os.Bundle;

/**
 * A container class for Aware (vendor) implementation capabilities (or
 * limitations). Filled-in by the firmware.
 */
public class Capabilities {
    public int maxConcurrentAwareClusters;
    public int maxPublishes;
    public int maxSubscribes;
    public int maxServiceNameLen;
    public int maxMatchFilterLen;
    public int maxTotalMatchFilterLen;
    public int maxServiceSpecificInfoLen;
    public int maxExtendedServiceSpecificInfoLen;
    public int maxNdiInterfaces;
    public int maxNdpSessions;
    public int maxAppInfoLen;
    public int maxQueuedTransmitMessages;
    public int maxSubscribeInterfaceAddresses;
    public int supportedCipherSuites;

    /**
     * Converts the internal capabilities to a parcelable & potentially app-facing
     * characteristics bundle. Only some of the information is exposed.
     */
    public Characteristics toPublicCharacteristics() {
        Bundle bundle = new Bundle();
        bundle.putInt(Characteristics.KEY_MAX_SERVICE_NAME_LENGTH, maxServiceNameLen);
        bundle.putInt(Characteristics.KEY_MAX_SERVICE_SPECIFIC_INFO_LENGTH,
                maxServiceSpecificInfoLen);
        bundle.putInt(Characteristics.KEY_MAX_MATCH_FILTER_LENGTH, maxMatchFilterLen);
        bundle.putInt(Characteristics.KEY_SUPPORTED_CIPHER_SUITES,
                toPublicCipherSuites(supportedCipherSuites));
        return new Characteristics(bundle);
    }

    private int toPublicCipherSuites(int nativeCipherSuites) {
        int publicCipherSuites = 0;

        if ((nativeCipherSuites & NanCipherSuiteType.SHARED_KEY_128_MASK) != 0) {
            publicCipherSuites |= Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128;
        }
        if ((nativeCipherSuites & NanCipherSuiteType.SHARED_KEY_256_MASK) != 0) {
            publicCipherSuites |= Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256;
        }

        return publicCipherSuites;
    }

    @Override
    public String toString() {
        return "Capabilities [maxConcurrentAwareClusters=" + maxConcurrentAwareClusters
                + ", maxPublishes=" + maxPublishes + ", maxSubscribes=" + maxSubscribes
                + ", maxServiceNameLen=" + maxServiceNameLen + ", maxMatchFilterLen="
                + maxMatchFilterLen + ", maxTotalMatchFilterLen=" + maxTotalMatchFilterLen
                + ", maxServiceSpecificInfoLen=" + maxServiceSpecificInfoLen
                + ", maxExtendedServiceSpecificInfoLen=" + maxExtendedServiceSpecificInfoLen
                + ", maxNdiInterfaces=" + maxNdiInterfaces + ", maxNdpSessions="
                + maxNdpSessions + ", maxAppInfoLen=" + maxAppInfoLen
                + ", maxQueuedTransmitMessages=" + maxQueuedTransmitMessages
                + ", maxSubscribeInterfaceAddresses=" + maxSubscribeInterfaceAddresses
                + ", supportedCipherSuites=" + supportedCipherSuites
                + "]";
    }
}

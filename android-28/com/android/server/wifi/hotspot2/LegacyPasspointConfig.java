/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import android.text.TextUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * Class representing the relevant configurations in the legacy Passpoint (N and older)
 * configuration file (/data/misc/wifi/PerProviderSubscription.conf).  Most of the configurations
 * (e.g. user credential) are saved elsewhere, the relevant configurations in this file are:
 * - FQDN
 * - Friendly Name
 * - Roaming Consortium
 * - Realm
 * - IMSI (for SIM credential)
 */
public class LegacyPasspointConfig {
    public String mFqdn;
    public String mFriendlyName;
    public long[] mRoamingConsortiumOis;
    public String mRealm;
    public String mImsi;

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof LegacyPasspointConfig)) {
            return false;
        }
        LegacyPasspointConfig that = (LegacyPasspointConfig) thatObject;
        return TextUtils.equals(mFqdn, that.mFqdn)
                && TextUtils.equals(mFriendlyName, that.mFriendlyName)
                && Arrays.equals(mRoamingConsortiumOis, that.mRoamingConsortiumOis)
                && TextUtils.equals(mRealm, that.mRealm)
                && TextUtils.equals(mImsi, that.mImsi);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFqdn, mFriendlyName, mRoamingConsortiumOis, mRealm, mImsi);
    }
}

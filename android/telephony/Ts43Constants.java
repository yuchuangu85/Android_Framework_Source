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

package android.telephony;

import androidx.annotation.StringDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Util class for TS43 constants to use in frameworks/base
 * Copied from {@link com.android.libraries.entitlement.utils.Ts43Constants}
 * @hide
 */
final class Ts43Constants {
    /** App ID unknown. For initialization only. */
    public static final String APP_UNKNOWN = "";

    /** App ID for Voice-Over-LTE entitlement. */
    public static final String APP_VOLTE = "ap2003";

    /** App ID for Voice-Over-WiFi entitlement. */
    public static final String APP_VOWIFI = "ap2004";

    /** App ID for SMS-Over-IP entitlement. */
    public static final String APP_SMSOIP = "ap2005";

    /** App ID for on device service activation (ODSA) for companion device. */
    public static final String APP_ODSA_COMPANION = "ap2006";

    /** App ID for on device service activation (ODSA) for primary device. */
    public static final String APP_ODSA_PRIMARY = "ap2009";

    /** App ID for data plan information entitlement. */
    public static final String APP_DATA_PLAN_BOOST = "ap2010";

    /** App ID for server initiated requests, entitlement and activation. */
    public static final String APP_ODSA_SERVER_INITIATED_REQUESTS = "ap2011";

    /** App ID for direct carrier billing. */
    public static final String APP_DIRECT_CARRIER_BILLING = "ap2012";

    /** App ID for private user identity. */
    public static final String APP_PRIVATE_USER_IDENTITY = "ap2013";

    /** App ID for phone number information. */
    public static final String APP_PHONE_NUMBER_INFORMATION = "ap2014";

    /** App ID for app authentication.
     * NOTE that Android will not support Operator Tokens or App Tokens, but some carriers still
     * want ap2015 even in temp-token use */
    public static final String APP_APPLICATION_AUTHENTICATION = "ap2015";

    /** App ID for satellite entitlement. */
    public static final String APP_SATELLITE_ENTITLEMENT = "ap2016";

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
            APP_UNKNOWN,
            APP_VOLTE,
            APP_VOWIFI,
            APP_SMSOIP,
            APP_ODSA_COMPANION,
            APP_ODSA_PRIMARY,
            APP_DATA_PLAN_BOOST,
            APP_ODSA_SERVER_INITIATED_REQUESTS,
            APP_DIRECT_CARRIER_BILLING,
            APP_PRIVATE_USER_IDENTITY,
            APP_PHONE_NUMBER_INFORMATION,
            APP_APPLICATION_AUTHENTICATION,
            APP_SATELLITE_ENTITLEMENT
    })
    public @interface AppId {
    }

    private Ts43Constants() {
    }
}


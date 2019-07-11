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

package com.android.server.wifi.hotspot2.anqp.eap;

/**
 * An Authentication parameter, part of the NAI Realm ANQP element, specified in
 * IEEE802.11-2012 section 8.4.4.10, table 8-188
 */
public abstract class AuthParam {
    public static final int PARAM_TYPE_EXPANDED_EAP_METHOD = 1;
    public static final int PARAM_TYPE_NON_EAP_INNER_AUTH_TYPE = 2;
    public static final int PARAM_TYPE_INNER_AUTH_EAP_METHOD_TYPE = 3;
    public static final int PARAM_TYPE_EXPANDED_INNER_EAP_METHOD = 4;
    public static final int PARAM_TYPE_CREDENTIAL_TYPE = 5;
    public static final int PARAM_TYPE_TUNNELED_EAP_METHOD_CREDENTIAL_TYPE = 6;
    public static final int PARAM_TYPE_VENDOR_SPECIFIC = 221;

    private final int mAuthTypeID;

    protected AuthParam(int authTypeID) {
        mAuthTypeID = authTypeID;
    }

    public int getAuthTypeID() {
        return mAuthTypeID;
    }
}

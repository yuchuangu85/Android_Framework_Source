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
package com.android.server.wifi.hotspot2;

/**
 * Match score for EAP credentials:
 * None means that there is a distinct mismatch, i.e. realm, method or parameter is defined
 * and mismatches that of the credential.
 * Indeterminate means that there is no ANQP information to match against.
 * Note: The numeric values given to the constants are used for preference comparison and
 * must be maintained accordingly.
 */
public abstract class AuthMatch {
    public static final int NONE = -1;
    public static final int INDETERMINATE = 0;
    public static final int REALM = 0x04;
    public static final int METHOD = 0x02;
    public static final int PARAM = 0x01;
    public static final int METHOD_PARAM = METHOD | PARAM;
    public static final int EXACT = REALM | METHOD | PARAM;

    public static String toString(int match) {
        if (match < 0) {
            return "None";
        }
        else if (match == 0) {
            return "Indeterminate";
        }

        StringBuilder sb = new StringBuilder();
        if ((match & REALM) != 0) {
            sb.append("Realm");
        }
        if ((match & METHOD) != 0) {
            sb.append("Method");
        }
        if ((match & PARAM) != 0) {
            sb.append("Param");
        }
        return sb.toString();
    }
}

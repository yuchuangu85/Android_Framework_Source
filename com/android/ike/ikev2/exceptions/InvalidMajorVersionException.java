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
package com.android.ike.ikev2.exceptions;

import com.android.ike.ikev2.message.IkeNotifyPayload;

/**
 * This exception is thrown when major version is higher than 2.
 *
 * <p>Include INVALID_MAJOR_VERSION Notify payload in an unencrypted response message containing
 * version number 2.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-2.5">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public final class InvalidMajorVersionException extends IkeException {
    public final byte receivedMajorVersion;

    /**
     * Construct a instance of InvalidMajorVersionException
     *
     * @param version the major version in received packet
     */
    public InvalidMajorVersionException(byte version) {
        super(IkeNotifyPayload.NOTIFY_TYPE_INVALID_MAJOR_VERSION);
        receivedMajorVersion = version;
    }
}

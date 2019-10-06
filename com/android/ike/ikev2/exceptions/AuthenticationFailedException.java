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
 * This exception is thrown when IKE authentication fails.
 *
 * <p>Contains an exception message detailing the failure cause.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-2.21.2">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public final class AuthenticationFailedException extends IkeException {
    /**
     * Construct a instance of AuthenticationFailedException.
     *
     * @param message the detail message.
     */
    public AuthenticationFailedException(String message) {
        super(IkeNotifyPayload.NOTIFY_TYPE_AUTHENTICATION_FAILED, message);
    }

    /**
     * Construct a instance of AuthenticationFailedExcepion.
     *
     * @param cause the cause.
     */
    public AuthenticationFailedException(Throwable cause) {
        super(IkeNotifyPayload.NOTIFY_TYPE_AUTHENTICATION_FAILED, cause);
    }
}

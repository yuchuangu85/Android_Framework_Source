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
 * IkeException is an abstract class that represents the common information for all IKE protocol
 * errors.
 *
 * <p>Each types of IKE error should implement its own subclass
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.10.1">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public abstract class IkeException extends Exception {
    @IkeNotifyPayload.NotifyType public final int errorCode;

    /**
     * Construct an instance of IkeException.
     *
     * @param code the protocol error code.
     */
    public IkeException(@IkeNotifyPayload.NotifyType int code) {
        super();
        errorCode = code;
    }

    /**
     * Construct an instance of IkeException with specified detail message.
     *
     * @param code the protocol error code.
     * @param message the detail message.
     */
    public IkeException(@IkeNotifyPayload.NotifyType int code, String message) {
        super(message);
        errorCode = code;
    }

    /**
     * Construct an instance of IkeException with specified cause.
     *
     * @param code the protocol error code.
     * @param cause the cause.
     */
    public IkeException(@IkeNotifyPayload.NotifyType int code, Throwable cause) {
        super(cause);
        errorCode = code;
    }
}

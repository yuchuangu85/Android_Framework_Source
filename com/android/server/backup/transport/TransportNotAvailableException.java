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
 * limitations under the License
 */

package com.android.server.backup.transport;

import android.util.AndroidException;

import com.android.internal.backup.IBackupTransport;

/**
 * Exception thrown when the {@link IBackupTransport} is not available. This happen when a {@link
 * TransportConnection} connection attempt fails. Check {@link
 * TransportConnection#connectAsync(TransportConnectionListener, String)} for when that happens.
 *
 * @see TransportConnection#connectAsync(TransportConnectionListener, String)
 */
public class TransportNotAvailableException extends AndroidException {
    TransportNotAvailableException() {
        super("Transport not available");
    }
}

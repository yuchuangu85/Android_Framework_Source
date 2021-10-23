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

package com.android.layoutlib.bridge.remote.server.adapters;

import com.android.ide.common.rendering.api.ILayoutLog;
import com.android.layout.remote.api.RemoteLayoutLog;
import com.android.tools.layoutlib.annotations.NotNull;

import java.rmi.RemoteException;

public class RemoteLayoutLogAdapter implements ILayoutLog {
    private final RemoteLayoutLog mLog;

    public RemoteLayoutLogAdapter(@NotNull RemoteLayoutLog log) {
        mLog = log;
    }

    @Override
    public void warning(String tag, String message, Object viewCookie, Object data) {
        try {
            mLog.warning(tag, message, viewCookie, null);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void fidelityWarning(String tag, String message, Throwable throwable, Object viewCookie,
            Object data) {
        try {
            mLog.fidelityWarning(tag, message, throwable, viewCookie, data);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void error(String tag, String message, Object viewCookie, Object data) {
        try {
            mLog.error(tag, message, viewCookie, null);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void error(String tag, String message, Throwable throwable, Object viewCookie,
            Object data) {
        try {
            mLog.error(tag, message, throwable, viewCookie, null);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void logAndroidFramework(int priority, String tag, String message) {
        try {
            mLog.logAndroidFramework(priority, tag, message);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}

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

package com.android.server.wifi;

import android.content.Context;

import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 *  Manager to handle API calls when wifi is disabled (other mode managers could be active, but this
 *  class triggers calls to the default implementations).
 */
public class DefaultModeManager implements ActiveModeManager {

    private static final String TAG = "WifiDefaultModeManager";

    private final Context mContext;

    /**
     * Start is not used in default mode.
     */
    @Override
    public void start() { };

    /**
     * Stop is not used in default mode.
     */
    @Override
    public void stop() { };

    @Override
    public boolean isStopping() {
        return false;
    }

    /**
     * No role specified in default mode.
     */
    @Override
    public @Role int getRole() {
        return ROLE_UNSPECIFIED;
    }

    /**
     * No role specified in default mode.
     */
    @Override
    public void setRole(@Role int role) {
        Preconditions.checkState(role == ROLE_UNSPECIFIED);
    }

    /**
     * Dump is not used in default mode.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) { }

    DefaultModeManager(Context context) {
        mContext = context;
    }
}

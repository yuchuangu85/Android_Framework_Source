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

package com.android.layoutlib.bridge.remote.client.adapters;

import com.android.ide.common.rendering.api.ActionBarCallback;
import com.android.ide.common.rendering.api.ActionBarCallback.HomeButtonStyle;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.layout.remote.api.RemoteActionBarCallback;
import com.android.tools.layoutlib.annotations.NotNull;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

class RemoteActionBarCallbackAdapter implements RemoteActionBarCallback {
    private final ActionBarCallback mDelegate;

    private RemoteActionBarCallbackAdapter(@NotNull ActionBarCallback delegate) {
        mDelegate = delegate;
    }

    public static RemoteActionBarCallback create(@NotNull ActionBarCallback delegate)
            throws RemoteException {
        return (RemoteActionBarCallback) UnicastRemoteObject.exportObject(
                new RemoteActionBarCallbackAdapter(delegate), 0);
    }

    @Override
    public List<ResourceReference> getMenuIds() {
        return mDelegate.getMenuIds();
    }

    @Override
    public boolean getSplitActionBarWhenNarrow() {
        return mDelegate.getSplitActionBarWhenNarrow();
    }

    @Override
    public int getNavigationMode() {
        return mDelegate.getNavigationMode();
    }

    @Override
    public String getSubTitle() {
        return mDelegate.getSubTitle();
    }

    @Override
    public HomeButtonStyle getHomeButtonStyle() {
        return mDelegate.getHomeButtonStyle();
    }

    @Override
    public boolean isOverflowPopupNeeded() {
        return mDelegate.isOverflowPopupNeeded();
    }
}

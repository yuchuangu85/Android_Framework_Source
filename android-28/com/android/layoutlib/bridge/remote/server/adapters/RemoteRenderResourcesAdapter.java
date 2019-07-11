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

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.layout.remote.api.RemoteRenderResources;
import com.android.resources.ResourceType;
import com.android.tools.layoutlib.annotations.NotNull;

import java.rmi.RemoteException;
import java.util.List;

public class RemoteRenderResourcesAdapter extends RenderResources {
    private final RemoteRenderResources mDelegate;

    public RemoteRenderResourcesAdapter(@NotNull RemoteRenderResources remoteRenderResources) {
        mDelegate = remoteRenderResources;
    }

    @Override
    public void setFrameworkResourceIdProvider(FrameworkResourceIdProvider provider) {
        // Ignored for remote operations.
    }

    @Override
    public void setLogger(LayoutLog logger) {
        // Ignored for remote operations.
    }

    @SuppressWarnings("deprecation")
    @Override
    public StyleResourceValue getCurrentTheme() {
        throw new UnsupportedOperationException();
    }

    @Override
    public StyleResourceValue getDefaultTheme() {
        try {
            return mDelegate.getDefaultTheme();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void applyStyle(StyleResourceValue theme, boolean useAsPrimary) {
        try {
            mDelegate.applyStyle(theme, useAsPrimary);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void clearStyles() {
        try {
            mDelegate.clearStyles();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<StyleResourceValue> getAllThemes() {
        try {
            return mDelegate.getAllThemes();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public StyleResourceValue getTheme(String name, boolean frameworkTheme) {
        try {
            return mDelegate.getTheme(name, frameworkTheme);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean themeIsParentOf(StyleResourceValue parentTheme, StyleResourceValue childTheme) {
        try {
            return mDelegate.themeIsParentOf(parentTheme, childTheme);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ResourceValue getFrameworkResource(ResourceType resourceType, String resourceName) {
        try {
            return mDelegate.getFrameworkResource(resourceType, resourceName);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ResourceValue getProjectResource(ResourceType resourceType, String resourceName) {
        try {
            return mDelegate.getProjectResource(resourceType, resourceName);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ResourceValue findItemInTheme(ResourceReference attr) {
        try {
            return mDelegate.findItemInTheme(attr);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ResourceValue findItemInStyle(StyleResourceValue style, ResourceReference attr) {
        try {
            return mDelegate.findItemInStyle(style, attr);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ResourceValue dereference(ResourceValue resourceValue) {
        try {
            return mDelegate.dereference(resourceValue);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns a resource by namespace, type and name. The returned resource is unresolved. */
    @Override
    public ResourceValue getUnresolvedResource(ResourceReference reference) {
        try {
            return mDelegate.getUnresolvedResource(reference);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public ResourceValue resolveValue(ResourceType type, String name, String value,
            boolean isFrameworkValue) {
        try {
            return mDelegate.resolveValue(type, name, value, isFrameworkValue);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ResourceValue resolveResValue(ResourceValue value) {
        try {
            return mDelegate.resolveValue(value);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public StyleResourceValue getParent(StyleResourceValue style) {
        try {
            return mDelegate.getParent(style);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

}

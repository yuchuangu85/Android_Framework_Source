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

import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.layout.remote.api.RemoteRenderResources;
import com.android.resources.ResourceType;
import com.android.tools.layoutlib.annotations.NotNull;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class RemoteRenderResourcesAdapter implements RemoteRenderResources {
    private final RenderResources mDelegate;

    private RemoteRenderResourcesAdapter(@NotNull RenderResources delegate) {
        mDelegate = delegate;
    }

    public static RemoteRenderResources create(@NotNull RenderResources resources)
            throws RemoteException {
        return (RemoteRenderResources) UnicastRemoteObject.exportObject(
                new RemoteRenderResourcesAdapter(resources), 0);
    }

    @Override
    public StyleResourceValue getDefaultTheme() {
        return mDelegate.getDefaultTheme();
    }

    @Override
    public void applyStyle(StyleResourceValue theme, boolean useAsPrimary) {
        mDelegate.applyStyle(theme, useAsPrimary);
    }

    @Override
    public void clearStyles() {
        mDelegate.clearStyles();
    }

    @Override
    public List<StyleResourceValue> getAllThemes() {
        return mDelegate.getAllThemes();
    }

    @Override
    public StyleResourceValue getTheme(String name, boolean frameworkTheme) {
        return mDelegate.getTheme(name, frameworkTheme);
    }

    @Override
    public boolean themeIsParentOf(StyleResourceValue parentTheme, StyleResourceValue childTheme) {
        return mDelegate.themeIsParentOf(parentTheme, childTheme);
    }

    @Override
    public ResourceValue getFrameworkResource(ResourceType resourceType, String resourceName) {
        return mDelegate.getFrameworkResource(resourceType, resourceName);
    }

    @Override
    public ResourceValue getProjectResource(ResourceType resourceType, String resourceName) {
        return mDelegate.getProjectResource(resourceType, resourceName);
    }

    @Override
    public ResourceValue findItemInTheme(ResourceReference attr) {
        return mDelegate.findItemInTheme(attr);
    }

    @Override
    public ResourceValue findItemInStyle(StyleResourceValue style, ResourceReference attr) {
        return mDelegate.findItemInStyle(style, attr);
    }

    @Override
    public ResourceValue resolveValue(ResourceValue value) {
        return mDelegate.resolveResValue(value);
    }

    @Override
    public ResourceValue resolveValue(ResourceType type, String name, String value,
            boolean isFrameworkValue) {
        return mDelegate.resolveValue(type, name, value, isFrameworkValue);
    }

    @Override
    public StyleResourceValue getParent(StyleResourceValue style) {
        return mDelegate.getParent(style);
    }

    @Override
    public StyleResourceValue getStyle(String styleName, boolean isFramework) {
        return mDelegate.getStyle(styleName, isFramework);
    }

    @Override
    public ResourceValue dereference(ResourceValue resourceValue) {
        return mDelegate.dereference(resourceValue);
    }

    @Override
    public ResourceValue getUnresolvedResource(ResourceReference reference) {
        return mDelegate.getUnresolvedResource(reference);
    }
}

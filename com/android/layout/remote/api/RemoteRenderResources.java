/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License") throws RemoteException;
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

package com.android.layout.remote.api;

import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.resources.ResourceType;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Remote version of the {@link RenderResources} class
 */
public interface RemoteRenderResources extends Remote {
    StyleResourceValue getDefaultTheme() throws RemoteException;

    void applyStyle(StyleResourceValue theme, boolean useAsPrimary) throws RemoteException;

    void clearStyles() throws RemoteException;

    List<StyleResourceValue> getAllThemes() throws RemoteException;


    StyleResourceValue getTheme(String name, boolean frameworkTheme) throws RemoteException;


    boolean themeIsParentOf(StyleResourceValue parentTheme, StyleResourceValue childTheme)
            throws RemoteException;

    ResourceValue getFrameworkResource(ResourceType resourceType, String resourceName)
            throws RemoteException;

    ResourceValue getProjectResource(ResourceType resourceType, String resourceName)
            throws RemoteException;


    ResourceValue findItemInTheme(ResourceReference attr) throws RemoteException;

    ResourceValue findItemInStyle(StyleResourceValue style, ResourceReference attr)
            throws RemoteException;

    ResourceValue resolveValue(ResourceValue value) throws RemoteException;

    ResourceValue resolveValue(ResourceType type, String name, String value,
            boolean isFrameworkValue) throws RemoteException;

    StyleResourceValue getParent(StyleResourceValue style) throws RemoteException;

    StyleResourceValue getStyle(String styleName, boolean isFramework) throws RemoteException;

    ResourceValue dereference(ResourceValue resourceValue) throws RemoteException;

    ResourceValue getUnresolvedResource(ResourceReference reference) throws RemoteException;
}

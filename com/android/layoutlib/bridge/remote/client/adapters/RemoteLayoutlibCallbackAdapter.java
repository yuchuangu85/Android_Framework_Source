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

import com.android.ide.common.rendering.api.AdapterBinding;
import com.android.ide.common.rendering.api.IProjectCallback.ViewAttribute;
import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.SessionParams.Key;
import com.android.layout.remote.api.RemoteActionBarCallback;
import com.android.layout.remote.api.RemoteILayoutPullParser;
import com.android.layout.remote.api.RemoteLayoutlibCallback;
import com.android.layout.remote.api.RemoteParserFactory;
import com.android.layout.remote.api.RemoteXmlPullParser;
import com.android.resources.ResourceType;
import com.android.tools.layoutlib.annotations.NotNull;
import com.android.tools.layoutlib.annotations.Nullable;
import com.android.util.Pair;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

public class RemoteLayoutlibCallbackAdapter implements RemoteLayoutlibCallback {
    private final LayoutlibCallback mDelegate;

    private RemoteLayoutlibCallbackAdapter(@NotNull LayoutlibCallback delegate) {
        mDelegate = delegate;
    }

    public static RemoteLayoutlibCallback create(@NotNull LayoutlibCallback delegate)
            throws RemoteException {
        return (RemoteLayoutlibCallback) UnicastRemoteObject.exportObject(
                new RemoteLayoutlibCallbackAdapter(delegate), 0);
    }

    @Override
    public boolean supports(int ideFeature) {
        return mDelegate.supports(ideFeature);
    }

    @Override
    public Object loadView(String name, Class[] constructorSignature, Object[] constructorArgs)
            throws Exception {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public String getNamespace() {
        return mDelegate.getNamespace();
    }

    @Override
    public RemoteResolveResult resolveResourceId(int id) {
        Pair<ResourceType, String> result = mDelegate.resolveResourceId(id);
        return result != null ? new RemoteResolveResult(result.getFirst(), result.getSecond()) :
                null;
    }

    @Override
    public String resolveResourceId(int[] id) {
        return mDelegate.resolveResourceId(id);
    }

    @Override
    public Integer getResourceId(ResourceType type, String name) {
        return mDelegate.getResourceId(type, name);
    }

    @Override
    public RemoteILayoutPullParser getParser(ResourceValue layoutResource) {
        try {
            return RemoteILayoutPullParserAdapter.create(mDelegate.getParser(layoutResource));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object getAdapterItemValue(ResourceReference adapterView, Object adapterCookie,
            ResourceReference itemRef, int fullPosition, int positionPerType,
            int fullParentPosition, int parentPositionPerType, ResourceReference viewRef,
            ViewAttribute viewAttribute, Object defaultValue) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public AdapterBinding getAdapterBinding(ResourceReference adapterViewRef, Object adapterCookie,
            Object viewObject) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public RemoteActionBarCallback getActionBarCallback() {
        try {
            return RemoteActionBarCallbackAdapter.create(mDelegate.getActionBarCallback());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T getFlag(Key<T> key) {
        return mDelegate.getFlag(key);
    }

    @Override
    public RemoteParserFactory getParserFactory() {
        try {
            return RemoteParserFactoryAdapter.create(mDelegate.getParserFactory());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    @Override
    public Path findClassPath(String name) {
        try {
            Class<?> clazz = mDelegate.findClass(name);
            URL url = clazz.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                return Paths.get(url.toURI());
            }
        } catch (ClassNotFoundException ignore) {
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    @Override
    public RemoteXmlPullParser getXmlFileParser(String fileName) {
        try {
            return RemoteXmlPullParserAdapter.create(mDelegate.getXmlFileParser(fileName));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}

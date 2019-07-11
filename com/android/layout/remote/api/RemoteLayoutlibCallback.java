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

package com.android.layout.remote.api;

import com.android.ide.common.rendering.api.AdapterBinding;
import com.android.ide.common.rendering.api.IProjectCallback.ViewAttribute;
import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.SessionParams.Key;
import com.android.resources.ResourceType;
import com.android.util.Pair;

import java.io.Serializable;
import java.nio.file.Path;
import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote version of the {@link LayoutlibCallback} class
 */
public interface RemoteLayoutlibCallback extends Remote {
    boolean supports(int ideFeature) throws RemoteException;

    Object loadView(String name, Class[] constructorSignature, Object[] constructorArgs)
            throws Exception, RemoteException;

    String getNamespace() throws RemoteException;

    RemoteResolveResult resolveResourceId(int id) throws RemoteException;

    String resolveResourceId(int[] id) throws RemoteException;

    Integer getResourceId(ResourceType type, String name) throws RemoteException;

    RemoteILayoutPullParser getParser(ResourceValue layoutResource) throws RemoteException;

    Object getAdapterItemValue(ResourceReference adapterView, Object adapterCookie,
            ResourceReference itemRef, int fullPosition, int positionPerType,
            int fullParentPosition, int parentPositionPerType, ResourceReference viewRef,
            ViewAttribute viewAttribute, Object defaultValue) throws RemoteException;

    AdapterBinding getAdapterBinding(ResourceReference adapterViewRef, Object adapterCookie,
            Object viewObject) throws RemoteException;

    RemoteActionBarCallback getActionBarCallback() throws RemoteException;

    <T> T getFlag(Key<T> key) throws RemoteException;

    RemoteParserFactory getParserFactory() throws RemoteException;

    Path findClassPath(String name) throws RemoteException;

    RemoteXmlPullParser getXmlFileParser(String fileName) throws RemoteException;

    class RemoteResolveResult implements Serializable {
        private ResourceType type;
        private String value;

        public RemoteResolveResult(ResourceType type, String value) {
            this.type = type;
            this.value = value;
        }

        public Pair<ResourceType, String> asPair() {
            return Pair.of(type, value);
        }
    }
}

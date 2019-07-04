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

import com.android.ide.common.rendering.api.ParserFactory;
import com.android.layout.remote.api.RemoteParserFactory;
import com.android.layout.remote.api.RemoteXmlPullParser;
import com.android.tools.layoutlib.annotations.NotNull;

import org.xmlpull.v1.XmlPullParserException;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

public class RemoteParserFactoryAdapter implements RemoteParserFactory {

    private final ParserFactory mDelegate;

    private RemoteParserFactoryAdapter(@NotNull ParserFactory delegate) {
        mDelegate = delegate;
    }

    public static RemoteParserFactory create(@NotNull ParserFactory factory)
            throws RemoteException {
        return (RemoteParserFactory) UnicastRemoteObject.exportObject(
                new RemoteParserFactoryAdapter(factory), 0);
    }

    @Override
    public RemoteXmlPullParser createParser(String debugName) throws RemoteException {
        try {
            return RemoteXmlPullParserAdapter.create(mDelegate.createParser(debugName));
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        }
    }
}

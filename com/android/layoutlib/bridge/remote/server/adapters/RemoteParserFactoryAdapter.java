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


import com.android.ide.common.rendering.api.ParserFactory;
import com.android.layout.remote.api.RemoteParserFactory;
import com.android.tools.layoutlib.annotations.NotNull;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.rmi.RemoteException;

class RemoteParserFactoryAdapter extends ParserFactory {
    private final RemoteParserFactory mDelegate;

    RemoteParserFactoryAdapter(@NotNull RemoteParserFactory remote) {
        mDelegate = remote;
    }

    @Override
    public XmlPullParser createParser(String debugName) throws XmlPullParserException {
        try {
            return new RemoteXmlPullParserAdapter(mDelegate.createParser(debugName));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}

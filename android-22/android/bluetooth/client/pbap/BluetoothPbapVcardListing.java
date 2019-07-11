/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.client.pbap;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

class BluetoothPbapVcardListing {

    private static final String TAG = "BluetoothPbapVcardListing";

    ArrayList<BluetoothPbapCard> mCards = new ArrayList<BluetoothPbapCard>();

    public BluetoothPbapVcardListing(InputStream in) throws IOException {
        parse(in);
    }

    private void parse(InputStream in) throws IOException {
        XmlPullParser parser = Xml.newPullParser();

        try {
            parser.setInput(in, "UTF-8");

            int eventType = parser.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {

                if (eventType == XmlPullParser.START_TAG && parser.getName().equals("card")) {
                    BluetoothPbapCard card = new BluetoothPbapCard(
                            parser.getAttributeValue(null, "handle"),
                            parser.getAttributeValue(null, "name"));
                    mCards.add(card);
                }

                eventType = parser.next();
            }
        } catch (XmlPullParserException e) {
            Log.e(TAG, "XML parser error when parsing XML", e);
        }
    }

    public ArrayList<BluetoothPbapCard> getList() {
        return mCards;
    }
}

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

package com.android.layoutlib.bridge.android;

import com.android.layoutlib.bridge.impl.RenderAction;

import android.os.LocaleList;

import java.util.Locale;

/**
 * This class provides an alternate implementation for {@code java.util.Locale#adjustLanguageCode}
 * which is not available in openJDK. It also overrides the getDefault method.
 *
 * The create tool re-writes references to the above mentioned method to this one. Hence it's
 * imperative that this class is not deleted unless the create tool is modified.
 */
@SuppressWarnings("UnusedDeclaration")
public class AndroidLocale {

    public static String adjustLanguageCode(String languageCode) {
        String adjusted = languageCode.toLowerCase(Locale.US);
        // Map new language codes to the obsolete language
        // codes so the correct resource bundles will be used.
        if (languageCode.equals("he")) {
            adjusted = "iw";
        } else if (languageCode.equals("id")) {
            adjusted = "in";
        } else if (languageCode.equals("yi")) {
            adjusted = "ji";
        }

        return adjusted;
    }

    public static Locale getDefault() {
        BridgeContext context = RenderAction.getCurrentContext();
        if (context != null) {
            LocaleList localeList = context.getConfiguration().getLocales();
            if (!localeList.isEmpty()) {
                return localeList.get(0);
            }
        }
        return Locale.getDefault();
    }
}

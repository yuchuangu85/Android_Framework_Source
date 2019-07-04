/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.databinding.tool;

import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;

/**
 * As data-binding finds lambda expressions, it creates classes that can wrap those callbacks
 * into methods that can be called into the ViewDataBinding classes.
 * <p>
 * The model keeps track of these wrappers and at the end data-binding generates all of them.
 * These are stripped from library projects and re-generated.
 */
public class CallbackWrapper {
    public static String SOURCE_ID = "sourceId";
    public static String ARG_PREFIX = "callbackArg_";
    public final ModelClass klass;
    public final ModelMethod method;
    public final String key;
    private static final String PACKAGE = "android.databinding.generated.callback";
    private static final String LISTENER_NAME = "Listener";
    private String mClassName;
    private String mListenerMethodName;
    private boolean mInitialized;

    public CallbackWrapper(ModelClass klass, ModelMethod method) {
        this.klass = klass;
        this.method = method;
        this.key = uniqueKey(klass, method);
    }

    public void prepare(String className, String listenerMethodName) {
        if (mInitialized) {
            L.e("trying to initialize listener wrapper twice.");
        }
        mInitialized = true;
        mClassName = className;
        mListenerMethodName = listenerMethodName;
    }

    public String getPackage() {
        return PACKAGE;
    }

    public String getClassName() {
        Preconditions.check(mInitialized, "Listener wrapper is not initialized yet.");
        return mClassName;
    }

    public String getListenerInterfaceName() {
        return LISTENER_NAME;
    }

    public String getListenerMethodName() {
        Preconditions.check(mInitialized, "Listener wrapper is not initialized yet.");
        return mListenerMethodName;
    }

    public static String uniqueKey(ModelClass klass, ModelMethod method) {
        String base = klass.getCanonicalName() + "#" + method.getName();
        for (ModelClass param : method.getParameterTypes()) {
            base += param + ",";
        }
        return base;
    }

    public String getCannonicalName() {
        return getPackage() + "." + getClassName();
    }

    public String getCannonicalListenerName() {
        return getPackage() + "." + getClassName() + "." + getListenerInterfaceName();
    }

    public String constructForIdentifier(int listenerId) {
        return "new " + getCannonicalName() + "(this, " + listenerId + ")";
    }

    public int getMinApi() {
        return Math.min(method.getMinApi(), klass.getMinApi());
    }
}

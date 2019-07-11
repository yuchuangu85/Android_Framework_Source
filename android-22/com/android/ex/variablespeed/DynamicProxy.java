/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.ex.variablespeed;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Contains a utility method for adapting a given interface against a real implementation.
 * <p>
 * This class is thead-safe.
 */
public class DynamicProxy {
    /**
     * Dynamically adapts a given interface against a delegate object.
     * <p>
     * For the given {@code clazz} object, which should be an interface, we return a new dynamic
     * proxy object implementing that interface, which will forward all method calls made on the
     * interface onto the delegate object.
     * <p>
     * In practice this means that you can make it appear as though {@code delegate} implements the
     * {@code clazz} interface, without this in practice being the case. As an example, if you
     * create an interface representing the {@link android.media.MediaPlayer}, you could pass this
     * interface in as the first argument, and a real {@link android.media.MediaPlayer} in as the
     * second argument, and now calls to the interface will be automatically sent on to the real
     * media player. The reason you may be interested in doing this in the first place is that this
     * allows you to test classes that have dependencies that are final or cannot be easily mocked.
     */
    // This is safe, because we know that proxy instance implements the interface.
    @SuppressWarnings("unchecked")
    public static <T> T dynamicProxy(Class<T> clazz, final Object delegate) {
        InvocationHandler invoke = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                try {
                    return delegate.getClass()
                            .getMethod(method.getName(), method.getParameterTypes())
                            .invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        };
        return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class<?>[] { clazz }, invoke);
    }
}

/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;
import android.view.WindowManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A helper class to handle operations on the optional window manager extensions.
 *
 * @hide
 */
public class WindowExtensionsHelper {
    private static final String TAG = "WindowExtensionsHelper";

    /**
     * Initializes Activity Embedding components for embedding rule overrides. This method should
     * only be invoked on devices supporting wm extensions.
     *
     * @return whether the initialization is successful.
     */
    public static boolean initEmbedding(@NonNull Context context) {
        try {
            final Object extensions = getExtensions(context);
            if (extensions == null) {
                Log.wtf(TAG,
                        "Embedding should never be initialized on devices without extensions!");
            }

            final Method aeInitMethod = extensions.getClass()
                    .getDeclaredMethod("getActivityEmbeddingComponent");
            aeInitMethod.invoke(extensions);
            return true;
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException
                 | ClassNotFoundException e) {
            Log.wtf(TAG, "Failed to initialize embedding!", e);
            return false;
        }
    }

    /** @return the wm extensions if present on the device. {@code null} if not present. */
    @Nullable
    private static Object getExtensions(@NonNull Context context) throws ClassNotFoundException,
            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (!WindowManager.hasWindowExtensionsEnabled()) {
            return null;
        }
        final Class<?> windowExtensionsProviderClass = Class.forName(
                "androidx.window.extensions.WindowExtensionsProvider",
                true /* initialize */, context.getClassLoader());
        final Method getWindowExtensionsMethod = windowExtensionsProviderClass
                .getDeclaredMethod("getWindowExtensions");
        return getWindowExtensionsMethod.invoke(null);
    }
}

/*
 * Copyright 2019 The Android Open Source Project
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
package androidx.camera.extensions.impl;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Used for initializing the extensions library.
 *
 * @since 1.1
 * @hide
 */
public class InitializerImpl {
    private InitializerImpl() {
    }

    private static final String TAG = "InitializerImpl";
    /**
     * An unknown error has occurred.
     * @hide
     */
    public static final int ERROR_UNKNOWN = 0;
    /**
     * Error reported if the application version of extensions is incompatible with the on device
     * library version.
     * @hide
     */
    public static final int ERROR_INITIALIZE_VERSION_INCOMPATIBLE = 1;
    private static Executor sExecutor = Executors.newSingleThreadExecutor();

    /**
     * Initializes the {@link Context}.
     *
     * <p>Before this call has been made no calls to the extensions library should be made except
     * for {@link ExtensionVersionImpl#checkApiVersion(String)}.
     *
     * @param version  The version of the extension used by the application.
     * @param context  The {@link Context} of the calling application.
     * @param executor The executor to run the callback on. If null then the callback will run on
     *                 any arbitrary executor.
     * @hide
     */
    public static void init(String version, Context context,
            OnExtensionsInitializedCallback callback, Executor executor) {
        Log.d(TAG, "initializing extensions");
        if (executor == null) {
            sExecutor.execute(callback::onSuccess);
        } else {
            executor.execute(callback::onSuccess);
        }
    }

    /**
     * Deinitializes the extensions to release resources.
     *
     * <p>After this call has been made no calls to the extensions library should be made except
     * for {@link ExtensionVersionImpl#checkApiVersion(String)}.
     *
     * @param executor The executor to run the callback on. If null then the callback will run on
     *                 any arbitrary executor.
     * @hide
     */
    public static void deinit(OnExtensionsDeinitializedCallback callback,
            Executor executor) {
        Log.d(TAG, "deinitializing extensions");
        if (executor == null) {
            sExecutor.execute(callback::onSuccess);
        } else {
            executor.execute(callback::onSuccess);
        }
    }

    /**
     * Callback that gets called when the library has finished initializing and is ready for used.
     * @hide
     */
    public interface OnExtensionsInitializedCallback {
        /** Called if the library successfully initializes. */
        void onSuccess();

        /**
         * Called if the library is unable to successfully initialize.
         *
         * @param error The reason for failing to initialize.
         */
        void onFailure(int error);
    }

    /**
     * Callback that gets called when the library has finished deinitialized.
     *
     * <p> Once this interface has been called then
     * {@link #init(String, Context, OnExtensionsInitializedCallback, Executor)} can be called
     * again regardless of whether or not the deinitialization has succeeded or failed.
     * @hide
     */
    public interface OnExtensionsDeinitializedCallback {
        /** Called if the library successfully deinitializes. */
        void onSuccess();

        /**
         * Called if the library encountered some error during the deinitialization.
         *
         * <p>Even if the library fails to deinitialize it is now valid for {@link
         * #init(String, Context, OnExtensionsInitializedCallback, Executor)} to be called
         * again.
         *
         * @param error The reason for failing to deinitialize.
         */
        void onFailure(int error);
    }
}

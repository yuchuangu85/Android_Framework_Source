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

package android.app.privatecompute;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.RemoteException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Provides a connection to a PCC service. */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
public class PccClient {
    private final IPccService mService;
    private final Context mContext;

    private PccClient(@NonNull Context context, @NonNull IBinder realBinder) {
        this.mService = IPccService.Stub.asInterface(realBinder);
        this.mContext = context;
    }

    /**
     * Factory method to create an instance of PccClient. This
     * should be called from onServiceConnected() with the IBinder received from the system
     *
     * <p>Example:
     * <pre><code>
     * private final ServiceConnection mConnection = new ServiceConnection() {
     *   {@literal @}Override
     *   public void onServiceConnected(ComponentName className, IBinder service) {
     *     mClient = PccClient.createInstance(MainActivity.this, service);
     *   }
     *
     *   {@literal @}Override
     *   public void onServiceDisconnected(ComponentName className) {
     *     mClient = null;
     *   }
     * };
     *
     * //...
     * bindService(intent, mConnection, flags);
     * </code></pre>
     *
     * @param binder  The IBinder object received from the service connection.
     * @param context The Context from the calling component.
     * @return A new instance of PccClient.
     */
    @NonNull
    public static PccClient createInstance(@NonNull Context context, @NonNull IBinder binder) {
        return new PccClient(context, binder);
    }

    /**
     * Sends a Bundle of data to the connected PCC service. The Bundle is sanitized to enforce
     * one-way data flow if the caller isn't allowed two-way communication with PCC components.
     *
     * @param data The Bundle of data to send.
     * @throws IllegalStateException    if sendData is called after the service has been killed by
     *                                  the system
     * @throws RuntimeException         if the PCC service is unavailable for any other reason.
     * @throws SecurityException        if the packageName from the passed context does not match
     *                                  the actual package name of the app.
     * @throws IllegalArgumentException if the Bundle contains unsafe data types and the caller
     *                                  isn't allowed two way communication with PCC components. To
     *                                  enforce a strictly one-way data flow, the {@code Bundle} is
     *                                  sanitized to prevent objects that can be used to establish a
     *                                  two-way communication channel like IBinder, Messenger, etc.
     *                                  <p>Allowed data types are:
     *                                  <ul>
     *                                    <li>Primitives and their arrays (e.g., int, char,
     *                                    boolean, String, byte[])
     *                                    <li>{@link android.os.PersistableBundle}
     *                                    <li>Read-only {@link android.os.ParcelFileDescriptor}
     *                                    <li>{@link android.os.SharedMemory} (must be read-only,
     *                                    or will be set to read-only)
     *                                    <li>{@link android.graphics.Bitmap}
     *                                    <li>Custom {@link android.os.Parcelable} objects must
     *                                    be serialized as a {@code byte[]}.
     *                                    <li>Nested {@code Bundle} objects, which are
     *                                    recursively sanitized. Throws {@code
     *                                    IllegalArgumentException} if the depth exceeds 100.
     *                                  </ul>
     */
    public void sendData(@NonNull Bundle data) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        IResultCallback callback = new IResultCallback.Stub() {
            @Override
            public void onSuccess() {
                future.complete(null);
            }

            @Override
            public void onFailure(@NonNull ParcelableException parcelableException) {
                future.completeExceptionally(parcelableException);
            }
        };

        try {
            mService.sendData(data, mContext.getPackageName(), callback);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

        try {
            future.get();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while waiting for the service response.", e);
        } catch (ExecutionException e) {
            ParcelableException parcelableException = (ParcelableException) e.getCause();
            parcelableException.maybeRethrow(IllegalArgumentException.class);
            parcelableException.maybeRethrow(IllegalStateException.class);
            throw new RuntimeException(parcelableException.getCause());
        }
    }
}

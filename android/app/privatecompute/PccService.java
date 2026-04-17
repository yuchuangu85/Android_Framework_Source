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
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

/**
 * Abstract base class for a PCC service that receives data from other components. Developers must
 * extend this class and implement its abstract methods to handle data ingress.
 */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
public abstract class PccService extends Service {

    protected PccService() {
    }

    private static final String TAG = PccService.class.getSimpleName();

    private final IPccService.Stub mBinder = new IPccService.Stub() {
        @Override
        public void sendData(@NonNull Bundle data, @NonNull String packageName,
                @Nullable IResultCallback callback) {
            try {
                int callingProcessUid = Binder.getCallingUid();

                // We trust the system UID because calls from external clients are proxied via
                // the system server, which validates the caller's identity before forwarding the
                // request.
                // If the call originates from the system server, it already is a trusted client.
                if (callingProcessUid != Process.SYSTEM_UID) {
                    String[] packages = getPackageManager().getPackagesForUid(callingProcessUid);
                    if (packages == null) {
                        if (callback != null) {
                            callback.onFailure(new ParcelableException(new SecurityException(
                                    "No known packages with the given UID: " + callingProcessUid)));
                        }
                        return;
                    }
                    List<String> packagesList = Arrays.asList(packages);

                    if (!packagesList.contains(packageName)) {
                        if (callback != null) {
                            callback.onFailure(new ParcelableException(new SecurityException(
                                    "Calling UID: " + callingProcessUid
                                            + " is not associated with package: " + packageName)));
                        }
                        return;
                    }
                }

                try {
                    onReceiveData(data, packageName);
                } finally {
                    if (callback != null) {
                        callback.onSuccess();
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to invoke " + IResultCallback.class.getSimpleName()
                        + " for client: " + packageName, e);
            }
        }
    };


    /**
     * Returns the communication channel to the service.
     *
     * @param intent The Intent that was used to bind to this service.
     * @return An IBinder through which clients can call back to the service. A system proxy binder
     * will be returned to the caller, if it is not executing in a PCC UID, or isn't a trusted
     * component.
     */
    @NonNull
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        return mBinder;
    }

    /**
     * This method is the data ingress point for a PCC service. When a client sends data
     * via the sendData api, the service will receive that data in this endpoint and can
     * process it.
     *
     * <p>The data Bundle is already sanitised by the system if sent from outside PCC
     * sandbox. This sanitization process makes sure that any object to establish 2 way
     * communication are not passed.
     *
     * <p>To enforce a strictly one-way data flow, if the caller isn't allowed two way communication
     * with PCC components, the {@code Bundle} is sanitized to prevent objects that can be used to
     * establish a two-way communication channel like IBinder, Messenger, etc.
     * <p>Allowed data types are:
     * <ul>
     *    <li>Primitives and their arrays (e.g., int, char, boolean, String, byte[])
     *    <li>{@link android.os.PersistableBundle}
     *    <li>Read-only {@link android.os.ParcelFileDescriptor}
     *    <li>{@link android.os.SharedMemory}. If it has write access, it will be silently
     *    restricted to read-only.
     *    <li>{@link android.graphics.Bitmap}
     *    <li>Custom {@link android.os.Parcelable} objects must be serialized as a
     *    {@code byte []}.
     *    <li>Nested {@code Bundle} objects, which are recursively sanitized up to a depth of 100.
     *  </ul>
     *
     * @param data        A Bundle containing the data sent by the client.
     * @param packageName The package name for the app that calls sendData.
     */
    public abstract void onReceiveData(@NonNull Bundle data, @NonNull String packageName);
}

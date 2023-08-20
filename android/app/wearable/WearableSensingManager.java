/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.wearable;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.ambientcontext.AmbientContextEvent;
import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.wearable.WearableSensingService;
import android.system.OsConstants;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Allows granted apps to manage the WearableSensingService.
 * Applications are responsible for managing the connection to Wearables. Applications can choose
 * to provide a data stream to the WearableSensingService to use for
 * computing {@link AmbientContextEvent}s. Applications can also optionally provide their own
 * defined data to power the detection of {@link AmbientContextEvent}s.
 * Methods on this class requires the caller to hold and be granted the
 * {@link Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE}.
 *
 * <p>The use of "Wearable" here is not the same as the Android Wear platform and should be treated
 * separately. </p>
 *
 * @hide
 */

@SystemApi
@SystemService(Context.WEARABLE_SENSING_SERVICE)
public class WearableSensingManager {
    /**
     * The bundle key for the service status query result, used in
     * {@code RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String STATUS_RESPONSE_BUNDLE_KEY =
            "android.app.wearable.WearableSensingStatusBundleKey";


    /**
     * An unknown status.
     */
    public static final int STATUS_UNKNOWN = 0;

    /**
     * The value of the status code that indicates success.
     */
    public static final int STATUS_SUCCESS = 1;

    /**
     * The value of the status code that indicates one or more of the
     * requested events are not supported.
     */
    public static final int STATUS_UNSUPPORTED = 2;

    /**
     * The value of the status code that indicates service not available.
     */
    public static final int STATUS_SERVICE_UNAVAILABLE = 3;

    /**
     * The value of the status code that there's no connection to the wearable.
     */
    public static final int STATUS_WEARABLE_UNAVAILABLE = 4;

    /**
     * The value of the status code that the app is not granted access.
     */
    public static final int STATUS_ACCESS_DENIED = 5;

    /** @hide */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_UNKNOWN,
            STATUS_SUCCESS,
            STATUS_UNSUPPORTED,
            STATUS_SERVICE_UNAVAILABLE,
            STATUS_WEARABLE_UNAVAILABLE,
            STATUS_ACCESS_DENIED
    }) public @interface StatusCode {}

    private final Context mContext;
    private final IWearableSensingManager mService;

    /**
     * {@hide}
     */
    public WearableSensingManager(Context context, IWearableSensingManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Provides a data stream to the WearableSensingService that's backed by the
     * parcelFileDescriptor, and sends the result to the {@link Consumer} right after the call.
     * This is used by applications that will also provide an implementation of
     * an isolated WearableSensingService. If the data stream was provided successfully
     * {@link WearableSensingManager#STATUS_SUCCESS} will be provided.
     *
     * @param parcelFileDescriptor The data stream to provide
     * @param executor Executor on which to run the consumer callback
     * @param statusConsumer A consumer that handles the status codes, which is returned
     *                 right after the call.
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void provideDataStream(
            @NonNull ParcelFileDescriptor parcelFileDescriptor,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            RemoteCallback callback = new RemoteCallback(result -> {
                int status = result.getInt(STATUS_RESPONSE_BUNDLE_KEY);
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> statusConsumer.accept(status));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            });
            mService.provideDataStream(parcelFileDescriptor, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets configuration and provides read-only data in a {@link PersistableBundle} that may be
     * used by the WearableSensingService, and sends the result to the {@link Consumer}
     * right after the call. It is dependent on the application to
     * define the type of data to provide. This is used by applications that will also
     * provide an implementation of an isolated WearableSensingService. If the data was
     * provided successfully {@link WearableSensingManager#STATUS_SUCCESS} will be povided.
     *
     * @param data Application configuration data to provide to the {@link WearableSensingService}.
     *             PersistableBundle does not allow any remotable objects or other contents
     *             that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to
     *                     provide to the {@link WearableSensingService}. Use this to provide the
     *                     sensing models data or other such data to the trusted process.
     *                     The sharedMemory must be read only and protected with
     *                     {@link OsConstants.PROT_READ}.
     *                     Other operations will be removed by the system.
     * @param executor Executor on which to run the consumer callback
     * @param statusConsumer A consumer that handles the status codes, which is returned
     *                     right after the call
     */
    @RequiresPermission(Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)
    public void provideData(
            @NonNull PersistableBundle data, @Nullable SharedMemory sharedMemory,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @StatusCode Consumer<Integer> statusConsumer) {
        try {
            RemoteCallback callback = new RemoteCallback(result -> {
                int status = result.getInt(STATUS_RESPONSE_BUNDLE_KEY);
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> statusConsumer.accept(status));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            });
            mService.provideData(data, sharedMemory, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}

/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.os.allowlist;

import static android.Manifest.permission.QUERY_ALLOWLIST;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.appfunctions.flags.Flags;
import android.content.Context;
import android.content.pm.SignedPackage;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides access to the system allowlist service. It gives system components the
 * ability to query an allowlist and to listen for changes to an allowlist.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
public final class AllowlistManager {

    /**
     *  An allowlist ID used exclusively for testing.
     *  @hide
     */
    @TestApi
    public static final int ALLOWLIST_ID_TEST = 1;
    /** An allowlist ID representing the AppFunctions allowlist */
    public static final int ALLOWLIST_ID_APP_FUNCTION = 2;
    /** An allowlist ID representing the Computer Control allowlist */
    public static final int ALLOWLIST_ID_COMPUTER_CONTROL = 3;

    /** @hide */
    @IntDef(prefix = { "ALLOWLIST_ID" }, value = {
            ALLOWLIST_ID_TEST,
            ALLOWLIST_ID_APP_FUNCTION,
            ALLOWLIST_ID_COMPUTER_CONTROL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AllowlistId {}

    /** A response code indicating the request was successful. */
    public static final int RESPONSE_STATUS_SUCCESS = 0;
    /**
     * A response code indicating the request was unsuccessful, because there was an unknown error.
     */
    public static final int RESPONSE_STATUS_ERROR_UNKNOWN = 1;
    /**
     * A response code indicating the request was unsuccessful, because the remote allowlist
     * provider service couldn't be reached or thrown an error.
     */
    public static final int RESPONSE_STATUS_ERROR_PROVIDER = 2;
    /**
     * A response code indicating the request was unsuccessful, because the request was invalid
     */
    public static final int RESPONSE_STATUS_ERROR_INVALID_REQUEST = 3;
    /**
     * A response code indicating the request was unsuccessful, because there was a network error
     * occurred.
     */
    public static final int RESPONSE_STATUS_ERROR_NETWORK = 4;


    /** @hide */
    public static final int[] ALLOWLIST_IDS = {
            ALLOWLIST_ID_TEST,
            ALLOWLIST_ID_APP_FUNCTION,
            ALLOWLIST_ID_COMPUTER_CONTROL
    };

    /** @hide */
    @IntDef(prefix = { "RESPONSE_STATUS " }, value = {
            RESPONSE_STATUS_SUCCESS,
            RESPONSE_STATUS_ERROR_PROVIDER,
            RESPONSE_STATUS_ERROR_INVALID_REQUEST,
            RESPONSE_STATUS_ERROR_NETWORK,
            RESPONSE_STATUS_ERROR_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResponseStatus {}

    /**
     * {@link AllowlistRequest} data bundle key. It specifies packages to query for.
     * The value should be a list of {@link SignedPackage}.
     */
    public static final String REQUEST_KEY_FILTER_PACKAGES =
            "android.allowlist.request.key.FILTER_PACKAGES";

    /**
     * {@link AllowlistRequest} data bundle key. Used by {@link #ALLOWLIST_ID_APP_FUNCTION}. It
     * specifies targets apps to query for. The value should be a list of {@link SignedPackage}
     * as target apps.
     */
    public static final String REQUEST_KEY_FILTER_TARGETS =
            "android.allowlist.request.key.FILTER_TARGETS";

    /**
     * {@link AllowlistRequest} data bundle key. It specifies whether to return allowlist info only
     * for installed packages.
     * The value should be a boolean.
     */
    public static final String REQUEST_KEY_INSTALLED_PACKAGES_ONLY =
            "android.allowlist.request.key.INSTALLED_PACKAGES_ONLY";

    /**
     * Key for the data in the request for the test provider to specify the response status
     *
     * @hide
     */
    @TestApi
    public static final String REQUEST_KEY_TEST_RESPONSE_STATUS =
            "android.allowlist.request.key.TEST_RESPONSE_STATUS";

    /**
     * {@link AllowlistResponse} data bundle key. When
     * {@link AllowlistManager#REQUEST_KEY_FILTER_PACKAGES} or
     * {@link AllowlistManager#REQUEST_KEY_INSTALLED_PACKAGES_ONLY} is provided in the request, the
     * response will contain a list of allowed packages. The value is a list of
     * {@link SignedPackage}
     */
    public static final String RESPONSE_KEY_ALLOWED_PACKAGES =
            "android.allowlist.response.key.ALLOWED_PACKAGES";

    /**
     * {@link AllowlistResponse} data bundle key. Used by {@link #ALLOWLIST_ID_APP_FUNCTION}. It
     * specifies allowed targets for each agent. The value is an instance of
     * {@link SignedPackageMultiMap}.
     */
    public static final String RESPONSE_KEY_ALLOWED_PACKAGE_MULTI_MAP =
            "android.allowlist.response.key.ALLOWED_PACKAGE_MULTI_MAP";

    /**
     * @hide
     */
    public static final String KEY_ALLOWLIST_RESPONSE = "allowlist_response";

    private final Context mContext;
    private final IAllowlistService mService;

    @GuardedBy("mRemoteListeners")
    private final ArrayMap<Consumer<AllowlistRequest>, IOnAllowlistChangedListener>
            mRemoteListeners = new ArrayMap<>();

    /**
     * @hide
     */
    public AllowlistManager(@NonNull Context context, @NonNull IAllowlistService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Sends a query to the allowlist, and gets the result back in a callback.
     *
     * @param request A request containing the query for the allowlist
     * @param executor The executor on which the callback will be run
     * @param responseConsumer A consumer to receive the query response from the allowlist
     *
     * @see AllowlistRequest
     * @see AllowlistResponse
     */
    @RequiresPermission(QUERY_ALLOWLIST)
    public void queryAllowlist(@NonNull AllowlistRequest request, @NonNull Executor executor,
            @NonNull Consumer<AllowlistResponse> responseConsumer) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(responseConsumer, "responseConsumer cannot be null");

        RemoteCallback remoteCallback = new RemoteCallback(result ->
                executor.execute(() -> {
                    if (result == null || !result.containsKey(KEY_ALLOWLIST_RESPONSE)) {
                        responseConsumer.accept(
                                new AllowlistResponse(RESPONSE_STATUS_ERROR_PROVIDER,
                                        Bundle.EMPTY));
                        return;
                    }
                    AllowlistResponse response = result.getParcelable(
                            KEY_ALLOWLIST_RESPONSE, AllowlistResponse.class);
                    responseConsumer.accept(response);
                }));

        try {
            mService.queryAllowlist(request, remoteCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add a listener for changes to a given allowlist.
     * <p>Note: A listener can be added with different allowlist requests to listen for different
     * allowlist changes.
     *
     * @param request Specify the changes to the allowlist to listen for
     * @param executor The executor on which the callback will be run
     * @param listener A listener for allowlist changes
     */
    @RequiresPermission(QUERY_ALLOWLIST)
    public void addOnAllowlistChangedListener(@NonNull AllowlistRequest request,
            @NonNull Executor executor, @NonNull Consumer<AllowlistRequest> listener) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(listener, "listener cannot be null");

        synchronized (mRemoteListeners) {
            IOnAllowlistChangedListener remoteListener = mRemoteListeners.get(listener);

            if (remoteListener == null) {
                remoteListener = new IOnAllowlistChangedListener.Stub() {
                    @Override
                    public void onAllowlistChanged(@NonNull AllowlistRequest request) {
                        final long token = Binder.clearCallingIdentity();
                        try {
                            executor.execute(() -> listener.accept(request));
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                };
                mRemoteListeners.put(listener, remoteListener);
            }

            try {
                mService.addOnAllowlistChangedListener(request, remoteListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Remove a previously registered listener for an allowlist
     * <p>Note: If a listener has been added with multiple allowlist requests, removing it will stop
     * listening for changes represented by all those allowlist requests.
     *
     * <p>If a listener has not been added, this method will be a no-op.
     *
     * @param listener The listener to remove
     */
    @RequiresPermission(QUERY_ALLOWLIST)
    public void removeOnAllowlistChangedListener(@NonNull Consumer<AllowlistRequest> listener) {
        Objects.requireNonNull(listener, "listener cannot be null");

        synchronized (mRemoteListeners) {
            IOnAllowlistChangedListener remoteListener = mRemoteListeners.remove(listener);
            if (remoteListener == null) {
                return;
            }
            try {
                mService.removeOnAllowlistChangedListener(remoteListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Enable or disable the test AllowlistProviderService. This no-op provider is only used for
     * CTS tests.
     * @hide
     */
    @TestApi
    @RequiresPermission(QUERY_ALLOWLIST)
    public void setTestProviderEnabled(boolean enabled) {
        try {
            mService.setTestProviderEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notify listeners currently registered with the test provider, filtered by the requests.
     * This method has no effect on other providers.
     *
     * @param requests The allowlist requests that are registered with listeners
     * @throws IllegalStateException If the test provider isn't currently enabled.
     * @hide
     */
    @TestApi
    @RequiresPermission(QUERY_ALLOWLIST)
    public void notifyAllowlistChangedListenersForTestProvider(
            @NonNull List<AllowlistRequest> requests) {
        try {
            mService.notifyAllowlistChangedListenersForTestProvider(requests);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}

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
package com.android.server.wallpapereffectsgeneration;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.service.wallpapereffectsgeneration.IWallpaperEffectsGenerationService;
import android.service.wallpapereffectsgeneration.WallpaperEffectsGenerationService;
import android.text.format.DateUtils;

import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;


/**
 * Proxy to the
 * {@link android.service.wallpapereffectsgeneration.WallpaperEffectsGenerationService}
 * implementation in another process.
 */
public class RemoteWallpaperEffectsGenerationService extends
        AbstractMultiplePendingRequestsRemoteService<RemoteWallpaperEffectsGenerationService,
                IWallpaperEffectsGenerationService> {

    private static final String TAG =
            RemoteWallpaperEffectsGenerationService.class.getSimpleName();

    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2 * DateUtils.SECOND_IN_MILLIS;

    private static final long TIMEOUT_IDLE_BIND_MILLIS = 120 * DateUtils.SECOND_IN_MILLIS;

    private final RemoteWallpaperEffectsGenerationServiceCallback mCallback;

    public RemoteWallpaperEffectsGenerationService(Context context,
            ComponentName componentName, int userId,
            RemoteWallpaperEffectsGenerationServiceCallback callback,
            boolean bindInstantServiceAllowed,
            boolean verbose) {
        super(context, WallpaperEffectsGenerationService.SERVICE_INTERFACE,
                componentName, userId, callback,
                context.getMainThreadHandler(),
                bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0,
                verbose, /* initialCapacity= */ 1);
        mCallback = callback;
    }

    @Override
    protected IWallpaperEffectsGenerationService getServiceInterface(IBinder service) {
        return IWallpaperEffectsGenerationService.Stub.asInterface(service);
    }

    @Override
    protected long getTimeoutIdleBindMillis() {
        return TIMEOUT_IDLE_BIND_MILLIS;
    }

    @Override
    protected long getRemoteRequestMillis() {
        return TIMEOUT_REMOTE_REQUEST_MILLIS;
    }

    /**
     * Schedules a request to bind to the remote service.
     */
    public void reconnect() {
        super.scheduleBind();
    }

    /**
     * Schedule async request on remote service.
     */
    public void scheduleOnResolvedService(
            @NonNull AsyncRequest<IWallpaperEffectsGenerationService> request) {
        scheduleAsyncRequest(request);
    }

    /**
     * Execute async request on remote service immediately instead of sending it to Handler queue.
     */
    public void executeOnResolvedService(
            @NonNull AsyncRequest<IWallpaperEffectsGenerationService> request) {
        executeAsyncRequest(request);
    }

    /**
     * Notifies server (WallpaperEffectsGenerationPerUserService) about unexpected events..
     */
    public interface RemoteWallpaperEffectsGenerationServiceCallback
            extends VultureCallback<RemoteWallpaperEffectsGenerationService> {
        /**
         * Notifies change in connected state of the remote service.
         */
        void onConnectedStateChanged(boolean connected);
    }

    @Override // from AbstractRemoteService
    protected void handleOnConnectedStateChanged(boolean connected) {
        if (mCallback != null) {
            mCallback.onConnectedStateChanged(connected);
        }
    }
}

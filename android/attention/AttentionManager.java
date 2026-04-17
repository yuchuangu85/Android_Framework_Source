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

package android.attention;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;

import com.android.input.flags.Flags;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * AttentionManager allows privileged system apps or services to set a listener to subscribe to the
 * user-activity events.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_ATTENTION_SERVICE_APIS)
@SystemService(Context.ATTENTION_SERVICE)
public final class AttentionManager {
    /**
     * An empty bit set of interaction types.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_ATTENTION_SERVICE_APIS)
    public static final int INTERACTION_TYPE_NONE  = InteractionState.INTERACTION_TYPE_NONE;

    /**
     * Key-press with any supporting device such as keyboard, gamepad, tv-remote etc.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_ATTENTION_SERVICE_APIS)
    public static final int INTERACTION_TYPE_KEY = InteractionState.INTERACTION_TYPE_KEY;

    /**
     * Hover with any supporting device such as mouse, touchpad etc.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_ATTENTION_SERVICE_APIS)
    public static final int INTERACTION_TYPE_HOVER = InteractionState.INTERACTION_TYPE_HOVER;

    /**
     * A touchscreen, mouse or touchpad gesture such as drag, fling, tap, click etc.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_ATTENTION_SERVICE_APIS)
    public static final int INTERACTION_TYPE_GESTURE = InteractionState.INTERACTION_TYPE_GESTURE;

    /**
     * A bit set of all available interaction types.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_ATTENTION_SERVICE_APIS)
    public static final int INTERACTION_TYPE_ALL = InteractionState.INTERACTION_TYPE_ALL;

    private final IAttentionManager mService;

    /**
     * @hide
     */
    public AttentionManager(@NonNull IAttentionManager service) {
        Objects.requireNonNull(service);
        mService = service;
    }

    /**
     * Set a listener that will be called for specified {@link InteractionType}. Only one listener
     * can be set per process.
     * <p/>
     * The {@code debounceTime} is also the delay after which device will be considered idle for
     * each interaction type. The listener will be called with type {@code INTERACTION_TYPE_NONE}
     * when the device becomes idle for all subscribed interaction types. An exception is that if
     * the interaction itself was longer than {@code debounceTime} then the listener will be called
     * immediately. The minimum allowed value for {@code debounceTime} is 500 ms.
     *
     * @param interactionTypes a bitmask of {@code INTERACTION_TYPE_*}
     * @param debounceTime     used to debounce end-activity notification for every
     *                         {@code INTERACTION_TYPE_*} independently.
     * @param executor         executor to execute the listener on.
     * @param listener         the listener to be set.
     * @throws IllegalArgumentException if called from process with an existing listener.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_ATTENTION_SERVICE_APIS)
    @RequiresPermission(android.Manifest.permission.ACCESS_ATTENTION_LISTENER)
    public void setInteractionListener(@InteractionType int interactionTypes,
            @NonNull Duration debounceTime, @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<InteractionInfo> listener) {
        Objects.requireNonNull(debounceTime);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        Preconditions.checkArgument(debounceTime.toMillis() >= 500,
                "debounceTime must be >= 500ms");

        try {
            mService.setListener(interactionTypes, debounceTime.toMillis(),
                    new IInteractionListener.Stub() {
                        @Override
                        public void onInteractionStateChanged(InteractionState interactionState) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() ->
                                        listener.accept(
                                                new InteractionInfo(
                                                        interactionState.interactionTypes,
                                                        interactionState.interactionTimeMillis)));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clear the listener that was previously set.
     * @throws IllegalArgumentException if no listener was set.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_ATTENTION_SERVICE_APIS)
    @RequiresPermission(android.Manifest.permission.ACCESS_ATTENTION_LISTENER)
    public void clearInteractionListener() {
        try {
            mService.clearListener();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "INTERACTION_TYPE_" }, value = {
            INTERACTION_TYPE_NONE,
            INTERACTION_TYPE_KEY,
            INTERACTION_TYPE_HOVER,
            INTERACTION_TYPE_GESTURE,
            INTERACTION_TYPE_ALL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InteractionType {}
}

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

package android.media;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.media.AudioManager.AudioMode;
import static android.media.audio.Flags.FLAG_FUSED_TELECOM_ROUTE_API;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.content.AttributionSource;
import android.media.audio.AudioModeSessionRequest;
import android.media.audio.DeviceIdentity;
import android.media.audio.IAudioModeSession;
import android.os.RemoteException;

import com.android.internal.util.ArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Manages audio routing for a single session corresponding to a mode owner.
 *
 * <p> While the session is active not {@link onPaused}, it acts as the owner of global volume/route
 * state, and can set an explicit audio routing preference. The owner of the session must respond to
 * events such as changes in available routes, external route preference changes, and the results of
 * route change requests.
 *
 * <p> The primary owned state is the AudioMode, set via {@link setMode}, and the requested route,
 * set via {@link setRequestedRoute}. Of particular note, the requested route can be 'overridden' by
 * the system via {@link onExternalRequestedRouteChanged}, however this override is fully serialized
 * against the route requested by the client. The requested route should only be set upon an
 * explicit user request, and as such, typically remains {@code null} to defer to the framework
 * routing logic.
 *
 * <p> Requests on this class are internally serialized, however, the results of the preferences are
 * asynchronously applied see {@link AudioModeSession.Callback#onRoutingResult} to handle the
 * results of the routing.
 *
 * <p> This API should be used in lieu of (and never in conjunction with) {@link
 * AudioManager#setMode}, {@link AudioManager#setCommunicationDevice}, and {@link
 * AudioManager#requestAudioFocus}
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
@FlaggedApi(FLAG_FUSED_TELECOM_ROUTE_API)
public final class AudioModeSession implements AutoCloseable {

    private final IAudioModeSession mSession;

    /**
     * Represents an audio route.
     */
    public static final class AudioRoute {
        private final IAudioModeSession.Route mRoute;

        /** @hide */
        /* package */ AudioRoute(@NonNull IAudioModeSession.Route route) {
            mRoute = route;
        }

        /**
         * Returns the primary {@link AudioDeviceAttributes} of {@link
         * AudioDeviceAttributes#ROLE_OUTPUT} associated with this route.
         */
        @NonNull
        public AudioDeviceAttributes getPrimaryDevice() {
            return deviceIdentityToAttributes(mRoute.output);
        }

        /**
         * Returns the input {@link AudioDeviceAttributes} of {@link
         * AudioDeviceAttributes#ROLE_INPUT} associated with this route if it is a split route.
         */
        @Nullable
        public AudioDeviceAttributes getInputDevice() {
            if (mRoute.input == null) {
                return null;
            }
            return deviceIdentityToAttributes(mRoute.input);
        }

        private static AudioDeviceAttributes deviceIdentityToAttributes(
                @NonNull DeviceIdentity device) {
            return new AudioDeviceAttributes(device.role, device.type, device.address);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AudioRoute r && mRoute.equals(r.mRoute);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mRoute);
        }

        @Override
        public String toString() {
            return mRoute.toString();
        }

        /* package */ IAudioModeSession.Route getRoute() {
            return mRoute;
        }

        /**
         * Builder for {@link AudioRoute}.
         */
        public static final class Builder {
            private final AudioDeviceAttributes mOutputDevice;
            private AudioDeviceAttributes mInputDevice;

            /**
             * Construct an AudioRoute.Builder with a primary {@link AudioDeviceAttributes} which
             * must have {@link AudioDeviceAttributes#ROLE_OUTPUT}.
             */
            public Builder(@NonNull AudioDeviceAttributes outputDevice) {
                mOutputDevice = Objects.requireNonNull(outputDevice);
                if (outputDevice.getRole() != AudioDeviceAttributes.ROLE_OUTPUT) {
                    throw new IllegalArgumentException("Output device must have ROLE_OUTPUT");
                }
            }

            /**
             * Sets the input {@link AudioDeviceAttributes} of {@link
             * AudioDeviceAttributes#ROLE_INPUT} for this route.
             */
            @NonNull
            public Builder setInputDevice(@Nullable AudioDeviceAttributes inputDevice) {
                if (inputDevice != null && inputDevice.getRole()
                        != AudioDeviceAttributes.ROLE_INPUT) {
                    throw new IllegalArgumentException("Input device must have ROLE_INPUT");
                }
                mInputDevice = inputDevice;
                return this;
            }

            /**
             * Builds the {@link AudioRoute}.
             */
            @NonNull
            public AudioRoute build() {
                var r = new IAudioModeSession.Route();
                r.output = new DeviceIdentity();
                r.output.role = AudioDeviceAttributes.ROLE_OUTPUT;
                r.output.type = mOutputDevice.getType();
                r.output.address = mOutputDevice.getAddress();
                if (mInputDevice != null) {
                    r.input = new DeviceIdentity();
                    r.input.role = AudioDeviceAttributes.ROLE_INPUT;
                    r.input.type = mInputDevice.getType();
                    r.input.address = mInputDevice.getAddress();
                }
                return new AudioRoute(r);
            }
        }
    }

    /**
     * Result status for {@link Callback#onRoutingResult}: The route change was
     * successful.
     */
    public static final int ROUTING_RESULT_SUCCESSFUL = 0;
    /** Result status for {@link Callback#onRoutingResult}: The route change failed. */
    public static final int ROUTING_RESULT_FAILED = 1;
    /**
     * Result status for {@link Callback#onRoutingResult}: The route change request
     * was pre-empted by another request.
     */
    public static final int ROUTING_RESULT_PREEMPTED = 2;
    /**
     * Result status for {@link Callback#onRoutingResult}: The route change request
     * timed out.
     */
    public static final int ROUTING_RESULT_TIMED_OUT = 3;

    /** @hide */
    @IntDef(prefix = { "ROUTING_RESULT_" }, value = {
            ROUTING_RESULT_SUCCESSFUL,
            ROUTING_RESULT_FAILED,
            ROUTING_RESULT_PREEMPTED,
            ROUTING_RESULT_TIMED_OUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RoutingResult {}


    /**
     * Configuration request for creating an {@link AudioModeSession}.
     */
    public static final class Request {
        private final AudioModeSessionRequest mParcelable;

        private Request(AudioModeSessionRequest parcelable) {
            mParcelable = parcelable;
        }

        /** @hide */
        public AudioModeSessionRequest getParcelable() {
            return mParcelable;
        }

        /**
         * @return the initial mode set for this request.
         */
        public @AudioMode int getInitialMode() {
            return mParcelable.mode;
        }

        /**
         * Returns whether hands-free use case is set for this request.
         * @return true if hands-free.
         */
        public boolean isDisplayActiveUseCase() {
            return mParcelable.isDisplayActiveUseCase;
        }

        /**
         * @return The {@link AttributionSource} representing the identity of the application
         * performing playback/recording, if set.
         */
        @Nullable
        public AttributionSource getClientAttribution() {
            if (mParcelable.clientAttribution == null) {
                return null;
            }
            return new AttributionSource(mParcelable.clientAttribution);
        }

        /**
         * @return The list of modes which will not have focus locked for this request.
         */
        @NonNull
        public List<Integer> getNoFocusModes() {
            if (mParcelable.noFocusModes == null) {
                return Collections.emptyList();
            }
            ArrayList<Integer> list = new ArrayList<>(mParcelable.noFocusModes.length);
            for (int mode : mParcelable.noFocusModes) {
                list.add(mode);
            }
            return list;
        }

        /**
         * Builder for {@link AudioModeSession.Request}.
         */
        public static final class Builder {
            private int mMode = AudioManager.MODE_NORMAL;
            private boolean mIsDisplayActiveUseCase = false;
            private AudioRoute mRequestedRoute;
            private List<Integer> mNoFocusModes = List.of(AudioManager.MODE_NORMAL);
            private AttributionSource mClientAttribution;

            /**
             * Sets the initial audio mode for the session.
             *
             * See {@link AudioModeSession#setMode}
             *
             * @param mode The desired session mode.
             * @return This builder.
             */
            @NonNull
            public Builder setInitialMode(@AudioMode int mode) {
                switch (mode) {
                    case AudioManager.MODE_NORMAL,
                    AudioManager.MODE_RINGTONE,
                    AudioManager.MODE_IN_CALL,
                    AudioManager.MODE_IN_COMMUNICATION,
                    AudioManager.MODE_CALL_SCREENING,
                    AudioManager.MODE_CALL_REDIRECT,
                    AudioManager.MODE_COMMUNICATION_REDIRECT,
                    AudioManager.MODE_ASSISTANT_CONVERSATION -> {}
                    default -> throw new IllegalArgumentException("Invalid mode:");
                }
                mMode = mode;
                return this;
            }

            /**
             * Sets whether this session represents a hands-free use case.
             *
             * @param isDisplayActiveUseCase {@code true} to prefer speakerphone.
             * @return This builder.
             */
            @NonNull
            public Builder setDisplayActiveUseCase(boolean isDisplayActiveUseCase) {
                mIsDisplayActiveUseCase = isDisplayActiveUseCase;
                return this;
            }

            /**
             * Sets the {@link AttributionSource} representing the identity of the application
             * performing playback/recording.
             *
             * @param clientAttribution The attribution source.
             * @return This builder.
             */
            @NonNull
            public Builder setClientAttribution(@Nullable AttributionSource clientAttribution) {
                mClientAttribution = clientAttribution;
                return this;
            }

            /**
             * Sets the modes for which audio focus is not held (in the locked state) on behalf of
             * the mode owner. By default, when the session is active, focus is held by the mode
             * owner.
             *
             * Defaults to {@link AudioManager#MODE_NORMAL}
             *
             * @param modes A list of modes, during which when the session is active, focus should
             * not be held for.
             * @return This builder.
             */
            @NonNull
            public Builder setNoFocusModes(@NonNull List<Integer> modes) {
                mNoFocusModes = Objects.requireNonNull(modes);
                return this;
            }

            /**
             * Builds the {@link AudioModeSession.Request}.
             *
             * @return A new {@link AudioModeSession.Request} instance.
             */
            @NonNull
            public Request build() {
                AudioModeSessionRequest request = new AudioModeSessionRequest();
                request.mode = mMode;
                request.isDisplayActiveUseCase = mIsDisplayActiveUseCase;
                if (mClientAttribution != null) {
                    request.clientAttribution = mClientAttribution.asState();
                }
                request.noFocusModes = ArrayUtils.convertToIntArray(mNoFocusModes);
                return new Request(request);
            }
        }
    }

    /**
     * Callback interface for session events.
     */
    public interface Callback {
        /**
         * Called when the set of available routable routes has changed.
         * This can be due to routes connecting/disconnecting, or changes in system audio policy.
         *
         * @param availableRoutes A list of {@link AudioRoute}s which are available to route via
         * {@link setRequestedRoute}.
         */
        void onAvailableRoutesChanged(@NonNull List<AudioRoute> availableRoutes);

        /**
         * Called when the system updates the selected route for this session
         * due to an external request or other system constraint.
         *
         * The post-condition of this callback is that the system's *intended* active route
         * for this session is now {@code newRoute}. The actual hardware switch is
         * confirmed asynchronously via {@link #onRoutingResult} with the provided
         * {@code requestId}.
         *
         * Idempotent with respect to {@link AudioModeSession#setRequestedRoute}.
         *
         * @param newRoute The new intended active {@link AudioRoute} for this session.
         * @param requestId A unique identifier for this system-initiated change request, referred
         *                  to in {@link #onRoutingResult}.
         */
        void onExternalRequestedRouteChanged(@Nullable AudioRoute newRoute, int requestId);

        /**
         * Called when this routing session is paused.
         * When paused, the session's routing and mode preferences will no longer be respected,
         * however, it's local state will be maintained.
         * The set of available routes will continue to be updated
         */
        void onPaused();

        /**
         * Called when this routing session is resumed after being paused.
         * Following this call, this session's routing/mode preference will be the driving
         * preference, including any updates to {@link setRequestedRoute} which occurred while
         * the session was paused.
         *
         * @param requestId The id corresponding to a {@link onRoutingResult}
         *                  representing the restoration of the preference represented by this
         *                  session.
         */
        void onResumed(int requestId);

        /**
         * Called when this routing session is no longer valid.
         * The preferences of the session are no longer applicable, and no callbacks will be
         * delivered.
         * See {@link AudioModeSession#close}.
         */
        void onClosed();

        /**
         * Reports the result of a request updating the preferred route,
         * confirming whether the change has been applied in the hardware. This can be in response
         * to a call to {@link AudioModeSession#setRequestedRoute}, a system-initiated
         * change notified via {@link #onExternalRequestedRouteChanged}, or a restoration notified
         * via {@link onResumed}.
         *
         * @param requestId The identifier matching the value provided by any message which updates
         *                  the preferred route.
         * @param route The route to which the change was attempted. This will always match the
         *              route parameter from the initiating call.
         * @param status The result status of the operation. One of
         *               {@link #ROUTING_RESULT_SUCCESSFUL}, {@link #ROUTING_RESULT_FAILED},
         *               {@link #ROUTING_RESULT_PREEMPTED}, or {@link #ROUTING_RESULT_TIMED_OUT}.
         */
        void onRoutingResult(int requestId, @Nullable AudioRoute route, @RoutingResult int status);
    }

    /** @hide */
    public AudioModeSession(@NonNull IAudioModeSession session) {
        mSession = session;
    }

    /**
     * Updates the audio mode associated with this session.
     * See {@link android.media.AudioManager#MODE_IN_CALL}.
     * The mode influences global routing and volume behavior. Only one mode is set at any given
     * time, driven by the state of this session, if it is not paused.
     *
     * @param mode The desired session mode.
     */
    public void setMode(@AudioMode int mode) {
        try {
            switch (mode) {
                case AudioManager.MODE_NORMAL,
                AudioManager.MODE_RINGTONE,
                AudioManager.MODE_IN_CALL,
                AudioManager.MODE_IN_COMMUNICATION,
                AudioManager.MODE_CALL_SCREENING,
                AudioManager.MODE_CALL_REDIRECT,
                AudioManager.MODE_COMMUNICATION_REDIRECT,
                AudioManager.MODE_ASSISTANT_CONVERSATION -> {}
                default -> throw new IllegalArgumentException("Invalid mode: " + mode);
            }
            mSession.setMode(mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates whether this session corresponds to a use-case where the user is viewing or
     * interacting with screen content, such as a video call, screen sharing, or co-watching.
     *
     * @param isDisplayActiveUseCase {@code true} to prefer speakerphone to earpiece, {@code false}
     *         otherwise.
     */
    public void setDisplayActiveUseCase(boolean isDisplayActiveUseCase) {
        try {
            mSession.setDisplayActiveUseCase(isDisplayActiveUseCase);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests to set the route for this session.
     * This is an asynchronous operation. The result, indicating whether the route
     * was successfully set in the hardware, will be reported via
     * {@link Callback#onRoutingResult} with the returned {@code requestId}.
     *
     * <p> This function should only be used for 'explicit' route requests (i.e. triggered by the
     * user) as this preference will override the internal strategy which considers contextual
     * factors and user preferences.
     *
     * @param route The desired {@link AudioRoute}. Should be one of the routes
     *              returned by {@link #getAvailableRoutes()}. {@code null} returns
     *              the routing to the system logic if an explicit preference no longer exists.
     * @return A unique requestId corresponding to the result callback.
     */
    public int setRequestedRoute(@Nullable AudioRoute route) {
        try {
            return mSession.setRequestedRoute(route == null ? null : route.getRoute());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request a client-initiated pause.
     *
     * Note: The session can be preemptively paused by the service at any time. As such, the only
     * post-condition of this method is that passing {@code true} will result in a paused
     * state.
     * {@link Callback.onResumed} will be delivered when the actual
     * status of the session (determined by the service) changes.
     * @param isPaused {@code true} to pause the session, {@code false} to resume.
     */
    public void setClientPaused(boolean isPaused) {
        try {
            mSession.setClientPaused(isPaused);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves a list of {@link AudioRoute} objects representing all audio routes
     * currently available for routing for this session.
     *
     * @return A non-null list of available routes.
     */
    @NonNull
    public List<AudioRoute> getAvailableRoutes() {
        try {
            var routes = mSession.getAvailableRoutes();
            List<AudioRoute> audioRoutes = new ArrayList<>(routes.size());
            for (var route : routes) {
                audioRoutes.add(new AudioRoute(route));
            }
            return audioRoutes;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Ends the session. The route preferences and mode associated with this session will be
     * invalidated, and calls will have no effect. No subsequent callbacks will be received after
     * {@link Callback.onClosed}. This method is idempotent.
     */
    public void close() {
        try {
            mSession.close();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}

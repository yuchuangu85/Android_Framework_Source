/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.hardware.lights;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import com.android.internal.util.Preconditions;
import com.android.server.lights.feature.flags.Flags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates a request to modify the state of multiple lights.
 */
public final class LightsRequest {
    /** Visible to {@link LightsManager.LightsSession}. */
    final Map<Light, LightState> mRequests = new HashMap<>();
    final List<Integer> mLightIds = new ArrayList<>();
    final List<LightState> mLightStates = new ArrayList<>();
    final MultiLightEffect mLightEffect;

    /**
     * Can only be constructed via {@link LightsRequest.Builder#build()}.
     */
    private LightsRequest(Map<Light, LightState> requests, MultiLightEffect effect) {
        mRequests.putAll(requests);
        List<Light> lights = new ArrayList<Light>(mRequests.keySet());
        for (int i = 0; i < lights.size(); i++) {
            final Light light = lights.get(i);
            mLightIds.add(i, light.getId());
            mLightStates.add(i, mRequests.get(light));
        }

        if (Flags.enableLightAnimations()) {
            mLightEffect = effect;
        } else {
            mLightEffect = null;
        }
    }

    /**
     * Get a list of Light as ids.
     *
     * @return List of light ids in the request.
     */
    public @NonNull List<Integer> getLights() {
        return mLightIds;
    }

    /**
     * Get a list of LightState. The states will be returned in same order as the light ids
     * returned by {@link #getLights()}.
     *
     * @return List of light states
     */
    public @NonNull List<LightState> getLightStates() {
        return mLightStates;
    }

    /**
     * Get a map of lights and states. The map will contain all the lights as keys and
     * the corresponding LightState requested as values.
     */
    public @NonNull Map<Light, LightState> getLightsAndStates() {
        return mRequests;
    }

    /**
     * Get the configured effect.
     *
     * @return configured effects in this request.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
    public @Nullable MultiLightEffect getEffect() {
        return mLightEffect;
    }

    /**
     * Builder for creating device light change requests.
     */
    public static final class Builder {
        private final Map<Light, LightState> mChanges = new HashMap<>();
        private MultiLightEffect mEffect;

        /**
         * Overrides the color and intensity of a given light.
         *
         * @param light the light to modify
         * @param state the desired color and intensity of the light
         */
        public @NonNull Builder addLight(@NonNull Light light, @NonNull LightState state) {
            Preconditions.checkNotNull(light);
            Preconditions.checkNotNull(state);

            if (Flags.enableLightAnimations()) {
                Preconditions.checkState(mEffect == null, "Request already contains an effect.");
            }

            mChanges.put(light, state);
            return this;
        }

        /**
         * Applies an effect to a set of lights configured in the effect object.
         * <p>
         * The system allows a maximum of one effect playing per session plus an additional slot for
         * a non-preemptive effect waiting to be played, which means applications must combine all
         * light effects in to a single {@link MultiLightEffect} to play animations with multiple
         * lights.
         * <p>
         * If another session with higher priority is currently using a light needed by this effect,
         * the effect will not play until the current session has control of all the lights it needs
         * for playback. In a similar way, if the effect is playing and this or another session with
         * higher priority requests a light needed by this effect, the effect will stop playing as
         * if it was preempted.
         *
         * @see Light#hasAnimationControl() to determine whether a light supports effects.
         * @param effect the light effect ot add to the request.
         */
        @FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
        public @NonNull Builder setEffect(@NonNull MultiLightEffect effect) {
            Preconditions.checkNotNull(effect);
            Preconditions.checkState(mChanges.size() == 0, "Request already has state changes");
            mEffect = effect;
            return this;
        }

        /**
         * Overrides the color and intensity of a given light.
         *
         * @param light the light to modify
         * @param state the desired color and intensity of the light
         * @deprecated Use {@link #addLight(Light, LightState)} instead.
         * @hide
         */
        @SystemApi
        @Deprecated
        public @NonNull Builder setLight(@NonNull Light light, @NonNull LightState state) {
            return addLight(light, state);
        }

        /**
         * Removes the override for the color and intensity of a given light.
         *
         * @param light the light to modify
         */
        public @NonNull Builder clearLight(@NonNull Light light) {
            Preconditions.checkNotNull(light);

            if (Flags.enableLightAnimations()) {
                Preconditions.checkState(mEffect == null, "Request already contains an effect.");
            }

            mChanges.put(light, null);
            return this;
        }

        /**
         * Create a LightsRequest object used to override lights on the device.
         *
         * <p>The generated {@link LightsRequest} should be used in
         * {@link LightsManager.LightsSession#requestLights(LightsRequest)}.
         */
        public @NonNull LightsRequest build() {
            if (!Flags.enableLightAnimations()) {
                return new LightsRequest(mChanges, null);
            }

            return new LightsRequest(mChanges, mEffect);
        }
    }
}

/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.libraries.entitlement.odsa;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.libraries.entitlement.EsimOdsaOperation;
import com.android.libraries.entitlement.EsimOdsaOperation.EntitlementProtocol;
import com.android.libraries.entitlement.EsimOdsaOperation.OdsaOperation;
import com.android.libraries.entitlement.utils.Ts43Constants;
import com.android.libraries.entitlement.utils.Ts43Constants.AppId;

import com.google.auto.value.AutoValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Acquire temporary token operation described in GSMA Service Entitlement Configuration section 6.
 */
public final class AcquireTemporaryTokenOperation {
    /**
     * Acquire temporary token request described in GSMA Service Entitlement Configuration section
     * 6.2.
     */
    @AutoValue
    public abstract static class AcquireTemporaryTokenRequest {
        /**
         * Returns the application id. Can only be {@link Ts43Constants#APP_ODSA_COMPANION}, {@link
         * Ts43Constants#APP_ODSA_PRIMARY}, or
         * {@link Ts43Constants#APP_ODSA_SERVER_INITIATED_REQUESTS}.
         */
        @NonNull
        @AppId
        public abstract String appId();

        /**
         * Returns the comma separated list of operation targets used with temporary token from
         * {@code AcquireTemporaryToken} operation. Used by HTTP parameter
         * {@code operation_targets}.
         */
        @NonNull
        @OdsaOperation
        @SuppressWarnings("AutoValueImmutableFields")
        public abstract List<String> operationTargets();

        /**
         * Returns the unique identifier of the companion device, like IMEI. Used by HTTP parameter
         * {@code companion_terminal_id}.
         */
        @NonNull
        public abstract String companionTerminalId();

        /**
         * Returns whether the subscription transfer is for cross-TS.43 platform. Used by HTTP
         * parameter {@code old_terminal_entitlement_protocol}.
         *
         * <p>This is an optional param for cross-platform.
         */
        @NonNull
        @EntitlementProtocol
        public abstract String targetTerminalEntitlementProtocol();

        /** Returns a new {@link Builder} object. */
        @NonNull
        public static Builder builder() {
            return new AutoValue_AcquireTemporaryTokenOperation_AcquireTemporaryTokenRequest
                    .Builder()
                    .setAppId(Ts43Constants.APP_UNKNOWN)
                    .setOperationTargets(Collections.emptyList())
                    .setCompanionTerminalId("")
                    .setTargetTerminalEntitlementProtocol(
                            EsimOdsaOperation.ENTITLEMENT_PROTOCOL_UNKNOWN);
        }

        /** Builder. */
        @AutoValue.Builder
        public abstract static class Builder {
            /**
             * Sets the application id.
             *
             * @param appId The application id. Can only be
             *              {@link Ts43Constants#APP_ODSA_COMPANION},
             *              {@link Ts43Constants#APP_ODSA_PRIMARY}, or
             *              {@link Ts43Constants#APP_ODSA_SERVER_INITIATED_REQUESTS}.
             * @return The builder.
             */
            @NonNull
            public abstract Builder setAppId(@NonNull @AppId String appId);

            /**
             * Sets the operation targets to be used with temporary token from {@code
             * AcquireTemporaryToken} operation. Used by HTTP parameter {@code operation_targets} if
             * set.
             *
             * @param operationTargets The operation targets to be used with temporary token from
             *                         {@code AcquireTemporaryToken} operation.
             * @return The builder.
             */
            @NonNull
            public abstract Builder setOperationTargets(
                    @NonNull @OdsaOperation List<String> operationTargets);

            /**
             * Sets the unique identifier of the companion device, like IMEI. Used by HTTP parameter
             * {@code companion_terminal_id} if set.
             *
             * <p>Used by companion device ODSA operation.
             *
             * @param companionTerminalId The unique identifier of the companion device.
             * @return The builder.
             */
            @NonNull
            public abstract Builder setCompanionTerminalId(@NonNull String companionTerminalId);

            /**
             * Sets the entitlement protocol of primary device. Used by HTTP parameter
             * {@code target_terminal_entitlement_protocol}.
             *
             * <p>This is an optional param for cross-platform.
             *
             * @param targetTerminalEntitlementProtocol The entitlement protocol of primary device.
             * @return The builder.
             */
            @NonNull
            public abstract Builder setTargetTerminalEntitlementProtocol(
                    @NonNull @EntitlementProtocol String targetTerminalEntitlementProtocol);

            abstract List<String> operationTargets();

            abstract AcquireTemporaryTokenRequest autoBuild();

            /** Returns the {@link AcquireTemporaryTokenRequest} object. */
            @NonNull
            public AcquireTemporaryTokenRequest build() {
                setOperationTargets(
                        Collections.unmodifiableList(new ArrayList<>(operationTargets())));
                return autoBuild();
            }
        }
    }

    /**
     * Acquire temporary token response described in GSMA Service Entitlement Configuration section
     * 6.5.7.
     */
    @AutoValue
    @AutoValue.CopyAnnotations
    @SuppressWarnings("AndroidJdkLibsChecker") // java.time.Instant
    public abstract static class AcquireTemporaryTokenResponse extends OdsaResponse {
        /** The temporary token used to establish trust between ECS and the client. */
        @NonNull
        public abstract String temporaryToken();

        /** The expiration time (UTC time) of the token. {@code null} if not available. */
        @AutoValue.CopyAnnotations
        @SuppressWarnings("AndroidJdkLibsChecker") // java.time.Instant

        @Nullable
        public abstract Instant temporaryTokenExpiry();

        /** The allowed ODSA operations requested using {@link #temporaryToken()}. */
        @NonNull
        @OdsaOperation
        @SuppressWarnings("AutoValueImmutableFields")
        public abstract List<String> operationTargets();

        /** Returns a new {@link AcquireTemporaryTokenRequest.Builder} object. */
        @NonNull
        public static Builder builder() {
            return new AutoValue_AcquireTemporaryTokenOperation_AcquireTemporaryTokenResponse
                    .Builder()
                    .setTemporaryToken("")
                    .setTemporaryTokenExpiry(Instant.EPOCH)
                    .setOperationTargets(Collections.emptyList());
        }

        /** Builder. */
        @AutoValue.Builder
        @AutoValue.CopyAnnotations
        @SuppressWarnings("AndroidJdkLibsChecker") // java.time.Instant
        public abstract static class Builder extends OdsaResponse.Builder {
            /**
             * Sets the temporary token.
             *
             * @param token The temporary token used to establish trust between ECS and the client.
             * @return The builder.
             */
            @NonNull
            public abstract Builder setTemporaryToken(@NonNull String token);

            /**
             * Sets the expiration time of the token.
             *
             * @param expiry The expiration time (UTC time) of the token.
             * @return The builder.
             */
            @AutoValue.CopyAnnotations
            @SuppressWarnings("AndroidJdkLibsChecker") // java.time.Instant
            @NonNull
            public abstract Builder setTemporaryTokenExpiry(@NonNull Instant expiry);

            /**
             * Sets the allowed ODSA operations requested using {@link #temporaryToken()}.
             *
             * @param operationTargets The allowed ODSA operations requested using {@link
             *                         #temporaryToken()}.
             * @return The builder.
             */
            @NonNull
            public abstract Builder setOperationTargets(
                    @NonNull @OdsaOperation List<String> operationTargets);

            abstract List<String> operationTargets();

            abstract AcquireTemporaryTokenResponse autoBuild();

            /** Returns the {@link AcquireTemporaryTokenResponse} object. */
            @NonNull
            public AcquireTemporaryTokenResponse build() {
                setOperationTargets(
                        Collections.unmodifiableList(new ArrayList<>(operationTargets())));
                return autoBuild();
            }
        }
    }

    private AcquireTemporaryTokenOperation() {
    }
}

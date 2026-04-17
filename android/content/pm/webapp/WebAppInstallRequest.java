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

package android.content.pm.webapp;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;

import com.android.webapp.flags.Flags;

import java.lang.annotation.Retention;
import java.util.concurrent.Executor;
import java.util.function.ObjIntConsumer;

/**
 * Encapsulates the parameters and callback for installing a Web App.
 *
 * <p>Instances of this class are created using the {@link Builder} and passed to {@link
 * WebAppManager#install(WebAppInstallRequest, Executor, ObjIntConsumer)}.
 *
 * @see WebAppManager
 */
@FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE_V2)
public class WebAppInstallRequest {
    /**
     * Result codes for the Web App installation.
     *
     * @hide
     */
    @IntDef({
        RESULT_UNKNOWN,
        RESULT_SUCCESS,
        RESULT_NETWORK_ERROR,
        RESULT_INTERNAL_ERROR,
        RESULT_PERMISSION_DENIED,
        RESULT_DUPLICATED_REQUEST,
        RESULT_INVALID_ARGUMENTS,
        RESULT_CANCELLED_BY_USER,
        RESULT_SECURITY_ERROR,
        RESULT_UNAVAILABLE
    })
    @Retention(SOURCE)
    public @interface ResultCode {}

    /** The installation completed successfully. */
    public static final int RESULT_SUCCESS = 0;

    /** The installation failed due to network connectivity issues or timeout. */
    public static final int RESULT_NETWORK_ERROR = 1;

    /** The installation failed due to an internal system error. */
    public static final int RESULT_INTERNAL_ERROR = 2;

    /**
     * The calling app does not have the necessary permission to install Web Apps.
     *
     * <p>The caller must be eligible to hold the {@link android.app.role.RoleManager#ROLE_BROWSER}
     * role.
     */
    public static final int RESULT_PERMISSION_DENIED = 3;

    /** An installation request for the same Web App is already in progress. */
    public static final int RESULT_DUPLICATED_REQUEST = 4;

    /** The provided arguments (e.g., manifest URL) are invalid. */
    public static final int RESULT_INVALID_ARGUMENTS = 5;

    /** The installation was cancelled by the user. */
    public static final int RESULT_CANCELLED_BY_USER = 6;

    /** The installation was blocked due to security check failures. */
    public static final int RESULT_SECURITY_ERROR = 7;

    /** The installation failed due to the service being unavailable. */
    public static final int RESULT_UNAVAILABLE = 8;

    /** The installation result is unknown. */
    public static final int RESULT_UNKNOWN = 9;

    private final CharSequence mTitle;
    private final String mManifestUrl;

    WebAppInstallRequest(@NonNull CharSequence title, @NonNull String manifestUrl) {
        this.mTitle = title;
        this.mManifestUrl = manifestUrl;
    }

    /** Builder for creating {@link WebAppInstallRequest} instances. */
    @FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE_V2)
    public static final class Builder {
        private final CharSequence mTitle;
        private final String mManifestUrl;

        /**
         * Creates a new Builder with the required PWA manifest URL.
         *
         * @param title The initial title of the app to display during the installation process.
         *     <p>Note: The final app name may differ as it is prioritized from the content of the
         *     web manifest.
         * @param manifestUrl The URL of the PWA manifest.
         */
        public Builder(@NonNull CharSequence title, @NonNull String manifestUrl) {
            this.mTitle = title;
            this.mManifestUrl = manifestUrl;
        }

        /** Builds the WebAppInstallRequest object. */
        public @NonNull WebAppInstallRequest build() {
            return new WebAppInstallRequest(mTitle, mManifestUrl);
        }
    }

    /** Returns the title provided for the installation request. */
    @NonNull
    public CharSequence getTitle() {
        return mTitle;
    }

    /** Returns the PWA manifest URL. */
    @NonNull
    public String getManifestUrl() {
        return mManifestUrl;
    }
}

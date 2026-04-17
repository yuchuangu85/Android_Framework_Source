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
import java.util.function.IntConsumer;

/**
 * Encapsulates the parameters and callback for querying the status of a Web App package.
 *
 * <p>Instances of this class are passed to {@link WebAppManager#query(WebAppQueryRequest, Executor,
 * IntConsumer)}.
 *
 * @see WebAppManager
 */
@FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE_V2)
public class WebAppQueryRequest {
    /**
     * Result codes for the Web App query.
     *
     * @hide
     */
    @IntDef({
        RESULT_UNKNOWN,
        RESULT_INSTALLED,
        RESULT_NOT_INSTALLED,
        RESULT_INTERNAL_ERROR,
        RESULT_PERMISSION_DENIED,
        RESULT_UNAVAILABLE
    })
    @Retention(SOURCE)
    public @interface ResultCode {}

    /**
     * The queried package is installed by {@link WebAppManager} and any of the following apply:
     *
     * <ul>
     *   <li>The calling app holds {@link android.Manifest.permission#QUERY_ALL_PACKAGES}
     *       permission.
     *   <li>The package was installed by the calling app via {@link WebAppManager}.
     * </ul>
     */
    public static final int RESULT_INSTALLED = 0;

    /**
     * The queried package is not installed by {@link WebAppManager}.
     *
     * <p>This result is returned if and only if:
     *
     * <ul>
     *   <li>The calling app holds {@link android.Manifest.permission#QUERY_ALL_PACKAGES}
     *       permission.
     *   <li>The package is not installed by {@link WebAppManager}.
     * </ul>
     */
    public static final int RESULT_NOT_INSTALLED = 1;

    /** The query failed due to an internal system error. */
    public static final int RESULT_INTERNAL_ERROR = 2;

    /**
     * The calling app does not have the necessary permissions to query this package.
     *
     * <p>This result is returned if any of the following apply:
     *
     * <ul>
     *   <li>The package is not visible to the calling app.
     *   <li>The package was not installed by the calling app.
     * </ul>
     */
    public static final int RESULT_PERMISSION_DENIED = 3;

    /** The query failed due to the service being unavailable. */
    public static final int RESULT_UNAVAILABLE = 4;

    /** The query result is unknown. */
    public static final int RESULT_UNKNOWN = 5;

    private final String mPackageName;

    /**
     * Creates a new {@link WebAppQueryRequest} with the target package name.
     *
     * @param packageName The name of the package to query.
     */
    public WebAppQueryRequest(@NonNull String packageName) {
        this.mPackageName = packageName;
    }

    /** Returns the package name being queried. */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }
}

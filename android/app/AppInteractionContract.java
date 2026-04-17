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

package android.app;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.appfunctions.flags.Flags;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.BaseColumns;

import com.android.internal.R;

import java.util.List;
import java.util.Objects;

/**
 * The contract between the App Interaction history provider and applications with read permission.
 * Contains definitions for the supported URIs and columns.
 *
 * @see AppInteractionAttribution
 */
@FlaggedApi(Flags.FLAG_ENABLE_APP_INTERACTION_API)
public final class AppInteractionContract implements BaseColumns {
    private AppInteractionContract() {}

    /**
     * This authority is used for querying from the App Interaction provider.
     *
     * @hide
     */
    public static final String AUTHORITY = "com.android.appinteraction.history";

    /**
     * The package name of the agent app.
     *
     * <p>Type: TEXT
     *
     * @hide
     */
    @SystemApi public static final String COLUMN_AGENT_PACKAGE_NAME = "agent_package_name";

    /**
     * The package name of the target app.
     *
     * <p>Type: TEXT
     *
     * @hide
     */
    @SystemApi public static final String COLUMN_TARGET_PACKAGE_NAME = "target_package_name";

    /**
     * The type of interaction. See {@link AppInteractionAttribution.InteractionType} for a list of
     * possible values.
     *
     * <p>The column is nullable. The caller should call {@link android.database.Cursor#isNull} to
     * check if the column value is null for that row.
     *
     * <p>Type: INTEGER (int)
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("IntentName")
    public static final String COLUMN_INTERACTION_TYPE = "interaction_type";

    /**
     * The custom interaction type, used when {@link AppInteractionAttribution#getInteractionType()}
     * is {@link AppInteractionAttribution#INTERACTION_TYPE_OTHER}.
     *
     * <p>The column is nullable. The caller should call {@link android.database.Cursor#isNull} to
     * check if the column value is null for that row.
     *
     * <p>Type: TEXT
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("IntentName")
    public static final String COLUMN_CUSTOM_INTERACTION_TYPE = "custom_interaction_type";

    /**
     * A URI linking to the original interaction context.
     *
     * <p>The column is nullable. The caller should call {@link android.database.Cursor#isNull} to
     * check if the column value is null for that row.
     *
     * <p>To launch this URI, the caller must construct an explicit {@link android.content.Intent}.
     * An implicit Intent is not sufficient and may not resolve to the correct component. The
     * required procedure is as follows:
     *
     * <ol>
     *   <li>Create an {@link android.content.Intent} with this URI as its data.
     *   <li>Call {@link android.content.Intent#setPackage(String)} on the Intent, providing the
     *       package name from {@link AppInteractionContract#COLUMN_AGENT_PACKAGE_NAME}.
     *   <li>Resolve the target activity by calling {@link
     *       android.content.pm.PackageManager#resolveActivity(Intent, int)}.
     *   <li>If the returned {@link android.content.pm.ResolveInfo} and its nested {@code
     *       activityInfo} are not null, create an explicit Intent.
     *   <li>Make the Intent explicit by calling {@link
     *       android.content.Intent#setComponent(android.content.ComponentName)}, creating the
     *       {@code ComponentName} from the {@code packageName} and {@code name} fields within the
     *       {@link android.content.pm.ResolveInfo#activityInfo}.
     *   <li>The resulting explicit Intent can now be used to start the activity.
     * </ol>
     *
     * <p>Type: TEXT
     *
     * @see AppInteractionAttribution.Builder#setInteractionUri
     * @hide
     */
    @SystemApi
    @SuppressLint("IntentName")
    public static final String COLUMN_INTERACTION_URI = "interaction_uri";

    /**
     * The timestamp (in milliseconds) when the interaction was started.
     *
     * <p>Type: INTEGER (long)
     *
     * @hide
     */
    @SystemApi public static final String COLUMN_ACCESS_TIME = "access_time";

    /**
     * Gets the {@code content://} style URI for the App Interaction history table for the user from
     * the context used to obtain the instance of this class.
     *
     * <p>To query the content provider using the returned URI, the calling application must hold
     * the {@link android.Manifest.permission#READ_APP_INTERACTION} permission.
     *
     * <p>To query for a user other than the current one, the caller must also hold the {@link
     * android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission.
     *
     * <p>Attempting to query the content provider with the returned URI without holding the
     * necessary permissions will result in a {@link java.lang.SecurityException}.
     *
     * @param user The target user.
     * @return The {@link Uri} for the App Interaction history table.
     * @hide
     */
    @SuppressLint("RequiresPermission") // Permission enforced in AppInteractionHistoryProvider
    @SystemApi
    @NonNull
    public static Uri getInteractionHistoryUriAsUser(@NonNull UserHandle user) {
        Objects.requireNonNull(user);

        // The verification of whether the user has access to the target user's URI is enforced
        // in the provider.
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath("user")
                .appendPath(Integer.toString(user.getIdentifier()))
                .build();
    }

    /**
     * Gets the configured list of package names that should be grouped as Device assistance.
     *
     * <p>The return value is only the configured list of package names, so that they might not
     * refer to actual installed system apps and the caller should perform its own validation
     * instead of assuming so.
     */
    @NonNull
    @SuppressWarnings("AndroidFrameworkClientSidePermissionCheck")
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS,
                Manifest.permission.EXECUTE_APP_FUNCTIONS_SYSTEM,
                Manifest.permission.READ_APP_INTERACTION
            })
    public static List<String> getDeviceAssistancePackageNames(@NonNull Context context) {
        if (context.checkSelfPermission(Manifest.permission.EXECUTE_APP_FUNCTIONS)
                        == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.READ_APP_INTERACTION)
                        == PackageManager.PERMISSION_GRANTED
                || (Flags.enableAppFunctionPermissionV2()
                        && context.checkSelfPermission(
                                        Manifest.permission.EXECUTE_APP_FUNCTIONS_SYSTEM)
                                == PackageManager.PERMISSION_GRANTED)) {
            final String[] deviceSettingPackages =
                    Objects.requireNonNull(context)
                            .getResources()
                            .getStringArray(R.array.config_appInteractionDeviceAssistancePackages);
            return List.of(deviceSettingPackages);
        } else {
            throw new SecurityException(
                    "Caller does not have permission to get device assistance package names.");
        }
    }
}

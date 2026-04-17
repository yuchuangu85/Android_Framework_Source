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

package android.health.connect;

import static android.Manifest.permission.BACKUP;
import static android.Manifest.permission.BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS;
import static android.Manifest.permission.PROVIDE_HEALTH_CONNECT_DEVICE_DATA;
import static android.Manifest.permission.RESTORE_HEALTH_CONNECT_DATA_AND_SETTINGS;
import static android.health.connect.Constants.DEFAULT_LONG;
import static android.health.connect.Constants.MAXIMUM_PAGE_SIZE;
import static android.health.connect.HealthPermissions.MANAGE_HEALTH_DATA_PERMISSION;
import static android.health.connect.HealthPermissions.MANAGE_HEALTH_PERMISSIONS;
import static android.health.connect.HealthPermissions.START_BACKUP_RESTORE_SETTINGS_PERMISSION;
import static android.health.connect.HealthPermissions.WRITE_MEDICAL_DATA;
import static android.health.connect.datatypes.RecordTypeSensitivity.SENSITIVE;

import static com.android.healthfitness.flags.Flags.FLAG_CLOUD_BACKUP_AND_RESTORE;
import static com.android.healthfitness.flags.Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API;
import static com.android.healthfitness.flags.Flags.FLAG_DEVICE_DATA_PROVIDERS_API;
import static com.android.healthfitness.flags.Flags.FLAG_IMMEDIATE_EXPORT;
import static com.android.healthfitness.flags.Flags.FLAG_LAUNCH_ONBOARDING_ACTIVITY;
import static com.android.healthfitness.flags.Flags.FLAG_MATCHMAKING;
import static com.android.healthfitness.flags.Flags.FLAG_ONBOARDING;
import static com.android.healthfitness.flags.Flags.FLAG_PERSONAL_HEALTH_RECORD;

import static java.util.stream.Collectors.toSet;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.WorkerThread;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.health.connect.accesslog.AccessLog;
import android.health.connect.accesslog.AccessLogsResponseParcel;
import android.health.connect.aidl.ActivityDatesRequestParcel;
import android.health.connect.aidl.ActivityDatesResponseParcel;
import android.health.connect.aidl.AggregateDataRequestParcel;
import android.health.connect.aidl.AggregateDataResponseParcel;
import android.health.connect.aidl.ApplicationInfoResponseParcel;
import android.health.connect.aidl.DeleteUsingFiltersRequestParcel;
import android.health.connect.aidl.GetPriorityResponseParcel;
import android.health.connect.aidl.HealthConnectExceptionParcel;
import android.health.connect.aidl.IAccessLogsResponseCallback;
import android.health.connect.aidl.IActivityDatesResponseCallback;
import android.health.connect.aidl.IAggregateRecordsResponseCallback;
import android.health.connect.aidl.IApplicationInfoResponseCallback;
import android.health.connect.aidl.ICanRestoreResponseCallback;
import android.health.connect.aidl.IChangeLogsResponseCallback;
import android.health.connect.aidl.IDataStagingFinishedCallback;
import android.health.connect.aidl.IDeviceDataSourceCapabilitiesCallback;
import android.health.connect.aidl.IEmptyResponseCallback;
import android.health.connect.aidl.IGetChangeLogTokenCallback;
import android.health.connect.aidl.IGetChangesForBackupResponseCallback;
import android.health.connect.aidl.IGetCurrentDeviceDataSourceCallback;
import android.health.connect.aidl.IGetDeviceDataSourceInfosCallback;
import android.health.connect.aidl.IGetDeviceDataSourcesCallback;
import android.health.connect.aidl.IGetHealthConnectDataStateCallback;
import android.health.connect.aidl.IGetHealthConnectMigrationUiStateCallback;
import android.health.connect.aidl.IGetHealthConnectOnboardingStateCallback;
import android.health.connect.aidl.IGetLatestMetadataForBackupResponseCallback;
import android.health.connect.aidl.IGetMatchingDataSourcesCallback;
import android.health.connect.aidl.IGetPriorityResponseCallback;
import android.health.connect.aidl.IHealthConnectService;
import android.health.connect.aidl.IInsertRecordsResponseCallback;
import android.health.connect.aidl.IIsMatchmakingPossibleCallback;
import android.health.connect.aidl.IMedicalDataSourceResponseCallback;
import android.health.connect.aidl.IMedicalDataSourcesResponseCallback;
import android.health.connect.aidl.IMedicalResourceListParcelResponseCallback;
import android.health.connect.aidl.IMedicalResourceTypeInfosCallback;
import android.health.connect.aidl.IMigrationCallback;
import android.health.connect.aidl.IReadMedicalResourcesResponseCallback;
import android.health.connect.aidl.IReadRecordsResponseCallback;
import android.health.connect.aidl.IRecordTypeInfoResponseCallback;
import android.health.connect.aidl.InsertRecordsResponseParcel;
import android.health.connect.aidl.MedicalResourceListParcel;
import android.health.connect.aidl.ReadRecordsResponseParcel;
import android.health.connect.aidl.RecordIdFiltersParcel;
import android.health.connect.aidl.RecordTypeInfoResponseParcel;
import android.health.connect.aidl.RecordsParcel;
import android.health.connect.aidl.UpdatePriorityRequestParcel;
import android.health.connect.aidl.UpsertMedicalResourceRequestsParcel;
import android.health.connect.backuprestore.BackupMetadata;
import android.health.connect.backuprestore.GetChangesForBackupResponse;
import android.health.connect.backuprestore.GetLatestMetadataForBackupResponse;
import android.health.connect.backuprestore.RestoreChange;
import android.health.connect.backuprestore.UpdateBackupAndRestoreSettingsRequest;
import android.health.connect.backuprestore.UpdateHealthConnectBackupStatusRequest;
import android.health.connect.backuprestore.UpdateHealthConnectRestoreStatusRequest;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.changelog.ChangeLogTokenResponse;
import android.health.connect.changelog.ChangeLogsRequest;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.datatypes.AggregationType;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.FhirResource;
import android.health.connect.datatypes.FhirVersion;
import android.health.connect.datatypes.Identifier;
import android.health.connect.datatypes.MedicalDataSource;
import android.health.connect.datatypes.MedicalResource;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.device.DeviceDataAdvertisement;
import android.health.connect.device.DeviceDataTypeAdvertisement;
import android.health.connect.exportimport.ExportImportDocumentProvider;
import android.health.connect.exportimport.IImportStatusCallback;
import android.health.connect.exportimport.IQueryDocumentProvidersCallback;
import android.health.connect.exportimport.IScheduledExportStatusCallback;
import android.health.connect.exportimport.ImportStatus;
import android.health.connect.exportimport.ScheduledExportSettings;
import android.health.connect.exportimport.ScheduledExportStatus;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.DataTypeDescriptor;
import android.health.connect.internal.datatypes.utils.DataTypeDescriptors;
import android.health.connect.internal.datatypes.utils.InternalExternalRecordConverter;
import android.health.connect.migration.HealthConnectMigrationUiState;
import android.health.connect.migration.MigrationEntity;
import android.health.connect.migration.MigrationEntityParcel;
import android.health.connect.migration.MigrationException;
import android.health.connect.restore.StageRemoteDataException;
import android.health.connect.restore.StageRemoteDataRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.healthfitness.flags.AconfigFlagHelper;
import com.android.healthfitness.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * This class provides APIs to interact with the centralized HealthConnect storage maintained by the
 * system.
 *
 * <p>HealthConnect is an offline, on-device storage that unifies data from multiple devices and
 * apps into an ecosystem featuring.
 *
 * <ul>
 *   <li>APIs to insert data of various types into the system.
 * </ul>
 *
 * <p>The basic unit of data in HealthConnect is represented as a {@link Record} object, which is
 * the base class for all the other data types such as {@link
 * android.health.connect.datatypes.StepsRecord}.
 */
@SystemService(Context.HEALTHCONNECT_SERVICE)
public class HealthConnectManager {
    /**
     * Used in conjunction with {@link android.content.Intent#ACTION_VIEW_PERMISSION_USAGE} to
     * launch UI to show an app’s health permission rationale/data policy.
     *
     * <p><b>Note:</b> Used by apps to define an intent filter in conjunction with {@link
     * android.content.Intent#ACTION_VIEW_PERMISSION_USAGE} that the HC UI can link out to.
     */
    // We use intent.category prefix to be compatible with HealthPermissions strings definitions.
    @SdkConstant(SdkConstant.SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_HEALTH_PERMISSIONS =
            "android.intent.category.HEALTH_PERMISSIONS";

    /**
     * Activity action: Launch UI to manage (e.g. grant/revoke) health permissions.
     *
     * <p>Shows a list of apps which request at least one permission of the Health permission group.
     *
     * <p>Input: {@link android.content.Intent#EXTRA_PACKAGE_NAME} string extra with the name of the
     * app requesting the action. Optional: Adding package name extras launches a UI to manager
     * (e.g. grant/revoke) for this app.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_HEALTH_PERMISSIONS =
            "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS";

    /**
     * Activity action: Launch UI to share the route associated with an exercise session.
     *
     * <p>Input: caller must provide `String` extra EXTRA_SESSION_ID
     *
     * <p>Result will be delivered via [Activity.onActivityResult] with `ExerciseRoute`
     * EXTRA_EXERCISE_ROUTE.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_EXERCISE_ROUTE =
            "android.health.connect.action.REQUEST_EXERCISE_ROUTE";

    /**
     * A string ID of a session to be used with {@link #ACTION_REQUEST_EXERCISE_ROUTE}.
     *
     * <p>This is used to specify route of which exercise session we want to request.
     */
    public static final String EXTRA_SESSION_ID = "android.health.connect.extra.SESSION_ID";

    /**
     * An exercise route requested via {@link #ACTION_REQUEST_EXERCISE_ROUTE}.
     *
     * <p>This is returned for a successful request to access a route associated with an exercise
     * session.
     */
    public static final String EXTRA_EXERCISE_ROUTE = "android.health.connect.extra.EXERCISE_ROUTE";

    /**
     * Returns the set of data types which are excluded from the output of
     * #getDeviceDataSourceCapabilities unless the caller holds the read permission for those data
     * types.
     */
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    @NonNull
    public static Set<Class<? extends Record>>
            getPermissionSensitiveDeviceDataSourceCapabilities() {
        return DataTypeDescriptors.getAllDataTypeDescriptors().stream()
                .filter(descriptor -> descriptor.getRecordTypeSensitivity() == SENSITIVE)
                .map(DataTypeDescriptor::getRecordClass)
                .collect(toSet());
    }

    /**
     * Activity action: Launch UI to show and manage (e.g. grant/revoke) health permissions.
     *
     * <p>Input: {@link android.content.Intent#EXTRA_PACKAGE_NAME} string extra with the name of the
     * app requesting the action must be present. An app can open only its own page.
     *
     * <p>Input: caller must provide `String[]` extra [EXTRA_PERMISSIONS]
     *
     * <p>Result will be delivered via [Activity.onActivityResult] with `String[]`
     * [EXTRA_PERMISSIONS] and `int[]` [EXTRA_PERMISSION_GRANT_RESULTS], similar to
     * [Activity.onRequestPermissionsResult]
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_HEALTH_PERMISSIONS =
            "android.health.connect.action.REQUEST_HEALTH_PERMISSIONS";

    /**
     * Activity action: Launch UI to health connect home settings screen.
     *
     * <p>shows a list of recent apps that accessed (e.g. read/write) health data and allows the
     * user to access health permissions and health data.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_HEALTH_HOME_SETTINGS =
            "android.health.connect.action.HEALTH_HOME_SETTINGS";

    /**
     * Activity action: Launch UI to show and manage (e.g. delete/export) health data.
     *
     * <p>shows a list of health data categories and actions to manage (e.g. delete/export) health
     * data.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_HEALTH_DATA =
            "android.health.connect.action.MANAGE_HEALTH_DATA";

    /**
     * Activity action: Display information regarding migration - e.g. asking the user to take some
     * action (e.g. update the system) so that migration can take place.
     *
     * <p><b>Note:</b> Callers of the migration APIs must handle this intent.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_MIGRATION_INFO =
            "android.health.connect.action.SHOW_MIGRATION_INFO";

    /**
     * Broadcast Action: Health Connect is ready to accept migrated data.
     *
     * <p class="note">This broadcast is explicitly sent to Health Connect migration aware
     * applications to prompt them to start/continue HC data migration. Migration aware applications
     * are those that both hold {@code android.permission.MIGRATE_HEALTH_CONNECT_DATA} and handle
     * {@code android.health.connect.action.SHOW_MIGRATION_INFO}.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     *
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_HEALTH_CONNECT_MIGRATION_READY =
            "android.health.connect.action.HEALTH_CONNECT_MIGRATION_READY";

    /**
     * Unknown download state considered to be the default download state.
     *
     * <p>See also {@link #updateDataDownloadState}
     *
     * @hide
     */
    @SystemApi public static final int DATA_DOWNLOAD_STATE_UNKNOWN = 0;

    /**
     * Indicates that the download has started.
     *
     * <p>See also {@link #updateDataDownloadState}
     *
     * @hide
     */
    @SystemApi public static final int DATA_DOWNLOAD_STARTED = 1;

    /**
     * Indicates that the download is being retried.
     *
     * <p>See also {@link #updateDataDownloadState}
     *
     * @hide
     */
    @SystemApi public static final int DATA_DOWNLOAD_RETRY = 2;

    /**
     * Indicates that the download has failed.
     *
     * <p>See also {@link #updateDataDownloadState}
     *
     * @hide
     */
    @SystemApi public static final int DATA_DOWNLOAD_FAILED = 3;

    /**
     * Indicates that the download has completed.
     *
     * <p>See also {@link HealthConnectManager#updateDataDownloadState}
     *
     * @hide
     */
    @SystemApi public static final int DATA_DOWNLOAD_COMPLETE = 4;

    /**
     * Activity action: Launch activity exported by client application that handles onboarding to
     * Health Connect.
     *
     * <p>Health Connect will invoke this intent whenever the user attempts to connect an app that
     * has exported an activity that responds to this intent. The launched activity is responsible
     * for making permission requests and any other prerequisites for connecting to Health Connect.
     *
     * <p class="note">Applications exporting an activity that is launched by this intent must also
     * guard it with {@link HealthPermissions#START_ONBOARDING} so that only the system can launch
     * it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    @FlaggedApi(FLAG_LAUNCH_ONBOARDING_ACTIVITY)
    public static final String ACTION_SHOW_ONBOARDING =
            "android.health.connect.action.SHOW_ONBOARDING";

    /**
     * Activity action: Launches the activity that shows the settings UI for changing Health Connect
     * backup and restore settings such as enabling/disabling.
     *
     * <p>Health Connect invokes this intent whenever the user clicks on the backup option in Health
     * Connect Settings.
     *
     * <p class="note">Applications exporting an activity that is launched by this intent must guard
     * it with {@link HealthPermissions#START_BACKUP_RESTORE_SETTINGS_PERMISSION} so that only the
     * system can launch it. Applications need to hold the {@link
     * android.Manifest.permission#BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS} or {@link
     * android.Manifest.permission#BACKUP} for the intent to be sent.
     *
     * <p class="note">Health Connect does not send the intent if:
     *
     * <ul>
     *   <li>There is no component available to handle the intent action
     *   <li>The component that can handle the action does not hold either of the required
     *       permissions
     *   <li>The handler activity is not guarded with the {@link
     *       HealthPermissions#START_BACKUP_RESTORE_SETTINGS_PERMISSION}
     * </ul>
     *
     * If more than one component can handle the intent, package manager default resolution rules
     * will be used to resolve the intent.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    @RequiresPermission(START_BACKUP_RESTORE_SETTINGS_PERMISSION)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_HEALTH_CONNECT_BACKUP_SETTINGS =
            "android.health.connect.action.SHOW_HEALTH_CONNECT_BACKUP_SETTINGS";

    /**
     * Activity action: Triggers the Health Connect restore flow from settings.
     *
     * <p>Health Connect invokes this intent to indicate that the receiver should initiate a restore
     * from a cloud backup after showing UI to get any missing settings from the user (e.g. account
     * information, a choice between available backups etc)
     *
     * <p class="note">Applications exporting an activity that is launched by this intent must guard
     * it with {@link HealthPermissions#START_BACKUP_RESTORE_SETTINGS_PERMISSION} so that only the
     * system can launch it. Applications need to hold the {@link
     * android.Manifest.permission#BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS} or {@link
     * android.Manifest.permission#BACKUP} for the intent to be sent.
     *
     * <p class="note">Health Connect does not send the intent if:
     *
     * <ul>
     *   <li>There is no component available to handle the intent action
     *   <li>The component that can handle the action does not hold either of the required
     *       permissions
     *   <li>The handler activity is not guarded with the {@link
     *       HealthPermissions#START_BACKUP_RESTORE_SETTINGS_PERMISSION}
     * </ul>
     *
     * If more than one component can handle the intent, package manager default resolution rules
     * will be used to resolve the intent.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    @RequiresPermission(START_BACKUP_RESTORE_SETTINGS_PERMISSION)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_RESTORE_SETTINGS_AND_TRIGGER_RESTORE =
            "android.health.connect.action.SHOW_RESTORE_SETTINGS_AND_TRIGGER_RESTORE";

    /**
     * Activity action: Launch UI to prompt user to connect more apps with Health Connect. Internal
     * use for Health Connect.
     *
     * <p>Health Connect will show a list of fitness apps which are compatible but not connected (no
     * read or write permissions granted).
     *
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SYNC_MORE_APPS =
            "android.health.connect.action.SYNC_MORE_APPS";

    /**
     * Activity action: Launch UI to show a list of apps that can read a specific record type.
     *
     * <p>Input: caller must provide a {@code String[]} extra {@link #EXTRA_RECORD_TYPES}.
     *
     * @see #createMatchmakingIntent(MatchmakingRequest)
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MATCHMAKING = "android.health.connect.action.MATCHMAKING";

    /**
     * Activity action: Launch UI to set up devices offered by a DDP. This intent will be launched
     * by Health Connect whenever a user wishes to set up a device advertised via {@link
     * #advertiseDeviceDataSources}. This offers the DDP an opportunity to provide custom
     * configuration and management of the device. This onboarding intent will be triggered when the
     * user has not previously enabled any data types for this device for the receiving device data
     * provider.
     *
     * <p class="note">Applications exporting an activity that is launched by this intent must also
     * guard it with {@link HealthPermissions#MANAGE_HEALTH_DATA_PERMISSION} so that only the system
     * can launch it.
     *
     * <p>Input: {@link #EXTRA_DEVICE_ID} string extra with the ID of the device.
     *
     * <p>Input: {@link #EXTRA_DEVICE_RECORD_TYPES} optional string arraylist extra. When present,
     * the user is requesting to manage the specified data types.
     *
     * <p>Input: {@link #EXTRA_DEVICE_SYMPTOM_TYPES} optional integer arraylist extra. When present,
     * the user is requesting to manage the specified symptom subtypes, e.g. {@link
     * android.health.connect.datatypes.SymptomRecord#SYMPTOM_TYPE_COUGH}.
     *
     * <p>Result: The activity should return one of the following result codes:
     *
     * <ul>
     *   <li>{@link #RESULT_DEVICE_ONBOARDING_ALLOWED}
     *   <li>{@link #RESULT_DEVICE_ONBOARDING_DENIED}
     *   <li>{@link #RESULT_DEVICE_ONBOARDING_ABORTED}
     * </ul>
     *
     * <p>Returning any other result code including {@link android.app.Activity#RESULT_OK} will be
     * treated as {@link #RESULT_DEVICE_ONBOARDING_ABORTED}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public static final String ACTION_SHOW_DEVICE_ONBOARDING =
            "android.health.connect.action.SHOW_DEVICE_ONBOARDING";

    /**
     * Activity result code: The user has granted access to at least one requested data type.
     *
     * <p>This result code is returned by the activity launched with {@link
     * #ACTION_SHOW_DEVICE_ONBOARDING}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public static final int RESULT_DEVICE_ONBOARDING_ALLOWED =
            android.app.Activity.RESULT_FIRST_USER;

    /**
     * Activity result code: The user has denied access to all requested data types.
     *
     * <p>This result code is returned by the activity launched with {@link
     * #ACTION_SHOW_DEVICE_ONBOARDING}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public static final int RESULT_DEVICE_ONBOARDING_DENIED =
            android.app.Activity.RESULT_FIRST_USER + 1;

    /**
     * Activity result code: The user has aborted the onboarding flow.
     *
     * <p>This result code is returned by the activity launched with {@link
     * #ACTION_SHOW_DEVICE_ONBOARDING}.
     *
     * <p>Note: this result code must be returned when the user attempts to navigate <i>back</i> out
     * of the activity. This requires overriding the default activity behavior which is to return
     * {@link android.app.Activity#RESULT_CANCELED} upon back navigation.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public static final int RESULT_DEVICE_ONBOARDING_ABORTED =
            android.app.Activity.RESULT_FIRST_USER + 2;

    /**
     * Activity action: Launch UI to manage devices offered by a DDP. This intent will be launched
     * by Health Connect whenever a user wishes to configure a device advertised via {@link
     * #advertiseDeviceDataSources}. This offers the DDP an opportunity to provide custom
     * configuration and management of the device. This management intent will be triggered when the
     * user has previously enabled at least one data type for this device for the receiving device
     * data provider.
     *
     * <p class="note">Applications exporting an activity that is launched by this intent must also
     * guard it with {@link HealthPermissions#MANAGE_HEALTH_DATA_PERMISSION} so that only the system
     * can launch it.
     *
     * <p>Input: {@link #EXTRA_DEVICE_ID} string extra with the ID of the device.
     *
     * <p>Input: {@link #EXTRA_DEVICE_RECORD_TYPES} optional string arraylist extra. When present,
     * the user is requesting to manage the specified data types.
     *
     * <p>Input: {@link #EXTRA_DEVICE_SYMPTOM_TYPES} optional integer arraylist extra. When present,
     * the user is requesting to manage the specified symptom subtypes, e.g. {@link
     * android.health.connect.datatypes.SymptomRecord#SYMPTOM_TYPE_COUGH}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public static final String ACTION_SHOW_DEVICE_MANAGEMENT =
            "android.health.connect.action.SHOW_DEVICE_MANAGEMENT";

    /**
     * A string array of record type canonical class names to be used with {@link
     * #ACTION_MATCHMAKING}.
     *
     * @see #createMatchmakingIntent(MatchmakingRequest)
     * @hide
     */
    public static final String EXTRA_RECORD_TYPES = "android.health.connect.extra.RECORD_TYPES";

    /**
     * A string array of packageNames to be included in matchmaking, to use with {@link
     * #ACTION_MATCHMAKING}.
     *
     * @see #createMatchmakingIntent(MatchmakingRequest)
     * @hide
     */
    public static final String EXTRA_INCLUDED_DATA_SOURCES =
            "android.health.connect.extra.INCLUDED_DATA_SOURCES";

    /**
     * A string array of packageNames to be excluded from matchmaking, to use with {@link
     * #ACTION_MATCHMAKING}.
     *
     * @see #createMatchmakingIntent(MatchmakingRequest)
     * @hide
     */
    public static final String EXTRA_EXCLUDED_DATA_SOURCES =
            "android.health.connect.extra.EXCLUDED_DATA_SOURCES";

    /**
     * A string ID of a device to be used with {@link #ACTION_SHOW_DEVICE_ONBOARDING} and {@link
     * #ACTION_SHOW_DEVICE_MANAGEMENT}.
     *
     * <p>This is the same device ID as was advertised by the device data provider, see {@link
     * #advertiseDeviceDataSources}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public static final String EXTRA_DEVICE_ID = "android.health.connect.extra.DEVICE_ID";

    /**
     * A string array of record type canonical class names to be used with {@link
     * #ACTION_SHOW_DEVICE_ONBOARDING} and {@link #ACTION_SHOW_DEVICE_MANAGEMENT}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public static final String EXTRA_DEVICE_RECORD_TYPES =
            "android.health.connect.extra.DEVICE_RECORD_TYPES";

    /**
     * An integer array of symptom types to be used with {@link #ACTION_SHOW_DEVICE_ONBOARDING} and
     * {@link #ACTION_SHOW_DEVICE_MANAGEMENT}, e.g. {@link
     * android.health.connect.datatypes.SymptomRecord#SYMPTOM_TYPE_COUGH}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public static final String EXTRA_DEVICE_SYMPTOM_TYPES =
            "android.health.connect.extra.DEVICE_SYMPTOM_TYPES";

    private static final String TAG = "HealthConnectManager";
    private static final String HEALTH_PERMISSION_PREFIX = "android.permission.health.";

    /**
     * Prefix that gets appended with a data type identifier integer to be used as a key for a
     * persisted state.
     *
     * @see #getDataTypePrefKey
     * @hide
     */
    private static final String TRACKING_PREFERENCE_PREFIX = "TRACKING_PREF_";

    @Nullable private static volatile Set<String> sHealthPermissions;

    private final Context mContext;
    private final IHealthConnectService mService;
    private final InternalExternalRecordConverter mInternalExternalRecordConverter;

    /** @hide */
    HealthConnectManager(@NonNull Context context, @NonNull IHealthConnectService service) {
        mContext = context;
        mService = service;
        mInternalExternalRecordConverter = InternalExternalRecordConverter.getInstance();
    }

    /**
     * Grant a runtime permission to an application which the application does not already have. The
     * permission must have been requested by the application. If the application is not allowed to
     * hold the permission, a {@link java.lang.SecurityException} is thrown. If the package or
     * permission is invalid, a {@link java.lang.IllegalArgumentException} is thrown.
     *
     * <p><b>Note:</b> This API sets {@code PackageManager.FLAG_PERMISSION_USER_SET}.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_PERMISSIONS)
    @UserHandleAware
    public void grantHealthPermission(@NonNull String packageName, @NonNull String permissionName) {
        try {
            mService.grantHealthPermission(packageName, permissionName, mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Revoke a health permission that was previously granted by {@link
     * #grantHealthPermission(String, String)} The permission must have been requested by the
     * application. If the application is not allowed to hold the permission, a {@link
     * java.lang.SecurityException} is thrown. If the package or permission is invalid, a {@link
     * java.lang.IllegalArgumentException} is thrown.
     *
     * <p><b>Note:</b> This API sets {@code PackageManager.FLAG_PERMISSION_USER_SET} or {@code
     * PackageManager.FLAG_PERMISSION_USER_FIXED} based on the number of revocations of a particular
     * permission for a package.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_PERMISSIONS)
    @UserHandleAware
    public void revokeHealthPermission(
            @NonNull String packageName, @NonNull String permissionName, @Nullable String reason) {
        try {
            mService.revokeHealthPermission(
                    packageName, permissionName, reason, mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Revokes all health permissions that were previously granted by {@link
     * #grantHealthPermission(String, String)} If the package is invalid, a {@link
     * java.lang.IllegalArgumentException} is thrown.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_PERMISSIONS)
    @UserHandleAware
    public void revokeAllHealthPermissions(@NonNull String packageName, @Nullable String reason) {
        try {
            mService.revokeAllHealthPermissions(packageName, reason, mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of health permissions that were previously granted by {@link
     * #grantHealthPermission(String, String)}.
     *
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_PERMISSIONS)
    @UserHandleAware
    public List<String> getGrantedHealthPermissions(@NonNull String packageName) {
        try {
            return mService.getGrantedHealthPermissions(packageName, mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns permission flags for the given package name and Health permissions.
     *
     * <p>This is equivalent to calling {@link PackageManager#getPermissionFlags(String, String,
     * UserHandle)} for each provided permission except it throws an exception for non-Health or
     * undeclared permissions. Flag masks listed in {@link PackageManager#MASK_PERMISSION_FLAGS_ALL}
     * can be used to check the flag values.
     *
     * <p>Returned flags for invalid, non-Health or undeclared permissions are equal to zero.
     *
     * @return a map which contains all requested permissions as keys and corresponding flags as
     *     values.
     * @throws IllegalArgumentException if the package doesn't exist, any of the permissions are not
     *     Health permissions or not declared by the app.
     * @throws NullPointerException if any of the arguments is {@code null}.
     * @throws SecurityException if the caller doesn't possess {@code
     *     android.permission.MANAGE_HEALTH_PERMISSIONS}.
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_PERMISSIONS)
    @UserHandleAware
    public Map<String, Integer> getHealthPermissionsFlags(
            @NonNull String packageName, @NonNull List<String> permissions) {
        try {
            return mService.getHealthPermissionsFlags(packageName, mContext.getUser(), permissions);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets/clears {@link PackageManager#FLAG_PERMISSION_USER_FIXED} for given health permissions.
     *
     * @param value whether to set or clear the flag, {@code true} means set, {@code false} - clear.
     * @throws IllegalArgumentException if the package doesn't exist, any of the permissions are not
     *     Health permissions or not declared by the app.
     * @throws NullPointerException if any of the arguments is {@code null}.
     * @throws SecurityException if the caller doesn't possess {@code
     *     android.permission.MANAGE_HEALTH_PERMISSIONS}.
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_PERMISSIONS)
    @UserHandleAware
    public void setHealthPermissionsUserFixedFlagValue(
            @NonNull String packageName, @NonNull List<String> permissions, boolean value) {
        try {
            mService.setHealthPermissionsUserFixedFlagValue(
                    packageName, mContext.getUser(), permissions, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the date from which an app have access to the historical health data. Returns null if
     * the package doesn't have historical access date.
     *
     * @hide
     */
    @RequiresPermission(HealthPermissions.MANAGE_HEALTH_PERMISSIONS)
    @UserHandleAware
    @Nullable
    public Instant getHealthDataHistoricalAccessStartDate(@NonNull String packageName) {
        try {
            long dateMilli =
                    mService.getHistoricalAccessStartDateInMilliseconds(
                            packageName, mContext.getUser());
            if (dateMilli == DEFAULT_LONG) {
                return null;
            } else {
                return Instant.ofEpochMilli(dateMilli);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Inserts {@code records} into the HealthConnect database. The records returned in {@link
     * InsertRecordsResponse} contains the unique IDs of the input records. The values are in same
     * order as {@code records}. In case of an error or a permission failure the HealthConnect
     * service, {@link OutcomeReceiver#onError} will be invoked with a {@link
     * HealthConnectException}.
     *
     * @param records list of records to be inserted.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws RuntimeException for internal errors
     */
    public void insertRecords(
            @NonNull List<Record> records,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<InsertRecordsResponse, HealthConnectException> callback) {
        Objects.requireNonNull(records);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            // Unset any set ids for insert. This is to prevent random string ids from creating
            // illegal argument exception.
            records.forEach((record) -> record.getMetadata().setId(""));
            List<RecordInternal<?>> recordInternals =
                    records.stream()
                            .map(
                                    record ->
                                            record.toRecordInternal()
                                                    .setPackageName(mContext.getPackageName()))
                            .collect(Collectors.toList());
            mService.insertRecords(
                    mContext.getAttributionSource(),
                    new RecordsParcel(recordInternals),
                    new IInsertRecordsResponseCallback.Stub() {
                        @Override
                        public void onResult(InsertRecordsResponseParcel parcel) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () ->
                                            callback.onResult(
                                                    new InsertRecordsResponse(
                                                            toExternalRecordsWithUuids(
                                                                    recordInternals,
                                                                    parcel.getUids()))));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get aggregations corresponding to {@code request}.
     *
     * @param <T> Result type of the aggregation.
     *     <p>Note:
     *     <p>This type is embedded in the {@link AggregationType} as {@link AggregationType} are
     *     typed in nature.
     *     <p>Only {@link AggregationType}s that are of same type T can be queried together
     * @param request request for different aggregation.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @see AggregateRecordsResponse#get
     */
    @SuppressWarnings("unchecked")
    public <T> void aggregate(
            @NonNull AggregateRecordsRequest<T> request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull
                    OutcomeReceiver<AggregateRecordsResponse<T>, HealthConnectException> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.aggregateRecords(
                    mContext.getAttributionSource(),
                    new AggregateDataRequestParcel(request),
                    new IAggregateRecordsResponseCallback.Stub() {
                        @Override
                        public void onResult(AggregateDataResponseParcel parcel) {
                            Binder.clearCallingIdentity();
                            try {
                                executor.execute(
                                        () ->
                                                callback.onResult(
                                                        (AggregateRecordsResponse<T>)
                                                                parcel.getAggregateDataResponse()));
                            } catch (Exception exception) {
                                callback.onError(
                                        new HealthConnectException(
                                                HealthConnectException.ERROR_INTERNAL));
                            }
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (ClassCastException classCastException) {
            returnError(
                    executor,
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(HealthConnectException.ERROR_INTERNAL)),
                    callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get aggregations corresponding to {@code request}. Use this API if results are to be grouped
     * by concrete intervals of time, for example 5 Hrs, 10 Hrs etc.
     *
     * @param <T> Result type of the aggregation.
     *     <p>Note:
     *     <p>This type is embedded in the {@link AggregationType} as {@link AggregationType} are
     *     typed in nature.
     *     <p>Only {@link AggregationType}s that are of same type T can be queried together
     * @param request request for different aggregation.
     * @param duration Duration on which to group by results
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @see HealthConnectManager#aggregateGroupByPeriod
     */
    @SuppressWarnings("unchecked")
    public <T> void aggregateGroupByDuration(
            @NonNull AggregateRecordsRequest<T> request,
            @NonNull Duration duration,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull
                    OutcomeReceiver<
                                    List<AggregateRecordsGroupedByDurationResponse<T>>,
                                    HealthConnectException>
                            callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(duration);
        if (duration.toMillis() < 1) {
            throw new IllegalArgumentException("Duration should be at least 1 millisecond");
        }
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.aggregateRecords(
                    mContext.getAttributionSource(),
                    new AggregateDataRequestParcel(request, duration),
                    new IAggregateRecordsResponseCallback.Stub() {
                        @Override
                        public void onResult(AggregateDataResponseParcel parcel) {
                            Binder.clearCallingIdentity();
                            List<AggregateRecordsGroupedByDurationResponse<T>> result =
                                    new ArrayList<>();
                            for (AggregateRecordsGroupedByDurationResponse<?>
                                    aggregateRecordsGroupedByDurationResponse :
                                            parcel.getAggregateDataResponseGroupedByDuration()) {
                                result.add(
                                        (AggregateRecordsGroupedByDurationResponse<T>)
                                                aggregateRecordsGroupedByDurationResponse);
                            }
                            executor.execute(() -> callback.onResult(result));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (ClassCastException classCastException) {
            returnError(
                    executor,
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(HealthConnectException.ERROR_INTERNAL)),
                    callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get aggregations corresponding to {@code request}. Use this API if results are to be grouped
     * by number of days. This API handles changes in {@link ZoneOffset} when computing the data on
     * a per-day basis.
     *
     * @param <T> Result type of the aggregation.
     *     <p>Note:
     *     <p>This type is embedded in the {@link AggregationType} as {@link AggregationType} are
     *     typed in nature.
     *     <p>Only {@link AggregationType}s that are of same type T can be queried together
     * @param request Request for different aggregation.
     * @param period Period on which to group by results
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @see AggregateRecordsGroupedByPeriodResponse#get
     * @see HealthConnectManager#aggregateGroupByDuration
     */
    @SuppressWarnings("unchecked")
    public <T> void aggregateGroupByPeriod(
            @NonNull AggregateRecordsRequest<T> request,
            @NonNull Period period,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull
                    OutcomeReceiver<
                                    List<AggregateRecordsGroupedByPeriodResponse<T>>,
                                    HealthConnectException>
                            callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(period);
        if (period == Period.ZERO) {
            throw new IllegalArgumentException("Period duration should be at least a day");
        }
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.aggregateRecords(
                    mContext.getAttributionSource(),
                    new AggregateDataRequestParcel(request, period),
                    new IAggregateRecordsResponseCallback.Stub() {
                        @Override
                        public void onResult(AggregateDataResponseParcel parcel) {
                            Binder.clearCallingIdentity();
                            List<AggregateRecordsGroupedByPeriodResponse<T>> result =
                                    new ArrayList<>();
                            for (AggregateRecordsGroupedByPeriodResponse<?>
                                    aggregateRecordsGroupedByPeriodResponse :
                                            parcel.getAggregateDataResponseGroupedByPeriod()) {
                                result.add(
                                        (AggregateRecordsGroupedByPeriodResponse<T>)
                                                aggregateRecordsGroupedByPeriodResponse);
                            }

                            executor.execute(() -> callback.onResult(result));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (ClassCastException classCastException) {
            returnError(
                    executor,
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(HealthConnectException.ERROR_INTERNAL)),
                    callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes records based on the {@link DeleteUsingFiltersRequest}. This is only to be used by
     * health connect controller APK(s). Ids that don't exist will be ignored.
     *
     * @param request Request based on which to perform delete operation
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_PERMISSIONS)
    public void deleteRecords(
            @NonNull DeleteUsingFiltersRequest request,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.deleteUsingFilters(
                    mContext.getAttributionSource(),
                    new DeleteUsingFiltersRequestParcel(request),
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException remoteException) {
            remoteException.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes records based on {@link RecordIdFilter}.
     *
     * <p>Deletions are performed in a transaction i.e. either all will be deleted or none
     *
     * @param recordIds recordIds on which to perform delete operation.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if {@code recordIds is empty}
     */
    public void deleteRecords(
            @NonNull List<RecordIdFilter> recordIds,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(recordIds);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        if (recordIds.isEmpty()) {
            throw new IllegalArgumentException("record ids can't be empty");
        }

        try {
            mService.deleteUsingFilters(
                    mContext.getAttributionSource(),
                    new DeleteUsingFiltersRequestParcel(
                            new RecordIdFiltersParcel(recordIds), mContext.getPackageName()),
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException remoteException) {
            remoteException.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes records based on the {@link TimeRangeFilter}.
     *
     * <p>Deletions are performed in a transaction i.e. either all will be deleted or none
     *
     * @param recordType recordType to perform delete operation on.
     * @param timeRangeFilter time filter based on which to delete the records.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     */
    public void deleteRecords(
            @NonNull Class<? extends Record> recordType,
            @NonNull TimeRangeFilter timeRangeFilter,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(recordType);
        Objects.requireNonNull(timeRangeFilter);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.deleteUsingFilters(
                    mContext.getAttributionSource(),
                    new DeleteUsingFiltersRequestParcel(
                            new DeleteUsingFiltersRequest.Builder()
                                    .addDataOrigin(
                                            new DataOrigin.Builder()
                                                    .setPackageName(mContext.getPackageName())
                                                    .build())
                                    .addRecordType(recordType)
                                    .setTimeRangeFilter(timeRangeFilter)
                                    .build()),
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException remoteException) {
            remoteException.rethrowFromSystemServer();
        }
    }

    /**
     * Get change logs post the time when {@code token} was generated.
     *
     * @param changeLogsRequest The token from {@link HealthConnectManager#getChangeLogToken}.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @see HealthConnectManager#getChangeLogToken
     */
    public void getChangeLogs(
            @NonNull ChangeLogsRequest changeLogsRequest,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<ChangeLogsResponse, HealthConnectException> callback) {
        Objects.requireNonNull(changeLogsRequest);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getChangeLogs(
                    mContext.getAttributionSource(),
                    changeLogsRequest,
                    new IChangeLogsResponseCallback.Stub() {
                        @Override
                        public void onResult(ChangeLogsResponse parcel) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(parcel));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (ClassCastException invalidArgumentException) {
            callback.onError(
                    new HealthConnectException(
                            HealthConnectException.ERROR_INVALID_ARGUMENT,
                            invalidArgumentException.getMessage()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get token for {HealthConnectManager#getChangeLogs}. Changelogs requested corresponding to
     * this token will be post the time this token was generated by the system all items that match
     * the given filters.
     *
     * <p>If the app doesn't have the necessary permissions to read data of any type requested here,
     * the request will throw an exception.
     *
     * <p>Tokens from this request are to be passed to {HealthConnectManager#getChangeLogs}
     *
     * @param request A request to get changelog token
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     */
    public void getChangeLogToken(
            @NonNull ChangeLogTokenRequest request,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<ChangeLogTokenResponse, HealthConnectException> callback) {
        try {
            mService.getChangeLogToken(
                    mContext.getAttributionSource(),
                    request,
                    new IGetChangeLogTokenCallback.Stub() {
                        @Override
                        public void onResult(ChangeLogTokenResponse parcel) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(parcel));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Fetch the data priority order of the contributing {@link DataOrigin} for {@code
     * dataCategory}.
     *
     * @param dataCategory {@link HealthDataCategory} for which to get the priority order
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void fetchDataOriginsPriorityOrder(
            @HealthDataCategory.Type int dataCategory,
            @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<FetchDataOriginsPriorityOrderResponse, HealthConnectException>
                            callback) {
        try {
            mService.getCurrentPriority(
                    mContext.getAttributionSource(),
                    dataCategory,
                    new IGetPriorityResponseCallback.Stub() {
                        @Override
                        public void onResult(GetPriorityResponseParcel response) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () -> callback.onResult(response.getPriorityResponse()));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the priority order of the apps as per {@code request}
     *
     * @param request new priority order update request
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void updateDataOriginPriorityOrder(
            @NonNull UpdateDataOriginPriorityOrderRequest request,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        try {
            mService.updatePriority(
                    mContext.getAttributionSource(),
                    new UpdatePriorityRequestParcel(request),
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves {@link RecordTypeInfoResponse} for each RecordType.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void queryAllRecordTypesInfo(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull
                    OutcomeReceiver<
                                    Map<Class<? extends Record>, RecordTypeInfoResponse>,
                                    HealthConnectException>
                            callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.queryAllRecordTypesInfo(
                    mContext.getAttributionSource(),
                    new IRecordTypeInfoResponseCallback.Stub() {
                        @Override
                        public void onResult(RecordTypeInfoResponseParcel parcel) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () -> callback.onResult(parcel.getRecordTypeInfoResponses()));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns currently set auto delete period for this user.
     *
     * <p>If you are calling this function for the first time after a user unlock, this might take
     * some time so consider calling this on a thread.
     *
     * @return Auto delete period in days, 0 is returned if auto delete period is not set.
     * @throws RuntimeException for internal errors
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    @IntRange(from = 0, to = 7300)
    public int getRecordRetentionPeriodInDays() {
        try {
            return mService.getRecordRetentionPeriodInDays(mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets auto delete period (for all the records to be automatically deleted) for this user.
     *
     * <p>Note: The max value of auto delete period can be 7300 i.e. ~20 years
     *
     * @param days Auto period to be set in days. Use 0 to unset this value.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws RuntimeException for internal errors
     * @throws IllegalArgumentException if {@code days} is not between 0 and 7300
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void setRecordRetentionPeriodInDays(
            @IntRange(from = 0, to = 7300) int days,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        if (days < 0 || days > 7300) {
            throw new IllegalArgumentException("days should be between " + 0 + " and " + 7300);
        }

        try {
            mService.setRecordRetentionPeriodInDays(
                    days,
                    mContext.getUser(),
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of access logs with package name and its access time for each record type.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void queryAccessLogs(
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<List<AccessLog>, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.queryAccessLogs(
                    mContext.getPackageName(),
                    new IAccessLogsResponseCallback.Stub() {
                        @Override
                        public void onResult(AccessLogsResponseParcel parcel) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(parcel.getAccessLogs()));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * API to read records based on {@link ReadRecordsRequestUsingFilters} or {@link
     * ReadRecordsRequestUsingIds}
     *
     * <p>Number of records returned by this API will depend based on below factors:
     *
     * <p>When an app with read permission allowed calls the API from background then it will be
     * able to read only its own inserted records and will not get records inserted by other apps.
     * This may be less than the total records present for the record type.
     *
     * <p>When an app with read permission allowed calls the API from foreground then it will be
     * able to read all records for the record type.
     *
     * <p>App with only write permission but no read permission allowed will be able to read only
     * its own inserted records both when in foreground or background.
     *
     * <p>An app without both read and write permissions will not be able to read any record and the
     * API will throw Security Exception.
     *
     * @param request Read request based on {@link ReadRecordsRequestUsingFilters} or {@link
     *     ReadRecordsRequestUsingIds}
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if request page size set is more than 5000 in {@link
     *     ReadRecordsRequestUsingFilters}
     * @throws SecurityException if app without read or write permission tries to read.
     */
    public <T extends Record> void readRecords(
            @NonNull ReadRecordsRequest<T> request,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        if (AconfigFlagHelper.isDeviceDataProvidersEnabled()) {
            if (request instanceof ReadRecordsRequestUsingFilters<?> filteredReq
                    && Objects.nonNull(filteredReq.getDeviceId())) {
                throw new IllegalArgumentException(
                        "Field validation failed: 'deviceId' is forbidden in this context. "
                                + "Use the dedicated readDeviceRecords API for this operation.");
            }
        }

        try {
            mService.readRecords(
                    mContext.getAttributionSource(),
                    request.toReadRecordsRequestParcel(),
                    getReadCallback(executor, callback));
        } catch (RemoteException remoteException) {
            remoteException.rethrowFromSystemServer();
        }
    }

    /**
     * Updates {@code records} into the HealthConnect database. In case of an error or a permission
     * failure the HealthConnect service, {@link OutcomeReceiver#onError} will be invoked with a
     * {@link HealthConnectException}.
     *
     * <p>In case the input record to be updated does not exist in the database or the caller is not
     * the owner of the record then {@link HealthConnectException#ERROR_INVALID_ARGUMENT} will be
     * thrown.
     *
     * @param records list of records to be updated.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if at least one of the records is missing both
     *     ClientRecordID and UUID.
     */
    public void updateRecords(
            @NonNull List<Record> records,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(records);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            List<RecordInternal<?>> recordInternals =
                    records.stream().map(Record::toRecordInternal).collect(Collectors.toList());
            verifyIds(recordInternals);

            mService.updateRecords(
                    mContext.getAttributionSource(),
                    new RecordsParcel(recordInternals),
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            Binder.clearCallingIdentity();
                            callback.onError(exception.getHealthConnectException());
                        }
                    });
        } catch (ArithmeticException
                | ClassCastException
                | IllegalArgumentException invalidArgumentException) {
            throw new IllegalArgumentException(invalidArgumentException);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information, represented by {@code ApplicationInfoResponse}, for all the packages
     * that have contributed to the health connect DB. If the application is does not have
     * permissions to query other packages, a {@link java.lang.SecurityException} is thrown.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void getContributorApplicationsInfo(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<ApplicationInfoResponse, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getContributorApplicationsInfo(
                    mContext.getAttributionSource(),
                    new IApplicationInfoResponseCallback.Stub() {
                        @Override
                        public void onResult(ApplicationInfoResponseParcel parcel) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () ->
                                            callback.onResult(
                                                    new ApplicationInfoResponse(
                                                            parcel.getAppInfoList())));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });

        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the list of all {@link DeviceDataSource}s.
     *
     * <p>The returned list includes all devices that have been advertised by device data providers,
     * filtered based on the caller's permissions. A device is included if the caller holds the READ
     * permission for at least one of the data types supported by the device. Other data types
     * supported by the device that the caller does not have permission for will nonetheless be
     * included.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     */
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public void getDeviceDataSources(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull
                    OutcomeReceiver<GetDeviceDataSourcesResponse, HealthConnectException>
                            callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getDeviceDataSources(
                    mContext.getAttributionSource(),
                    new IGetDeviceDataSourcesCallback.Stub() {
                        @Override
                        @RequiresNoPermission
                        public void onResult(GetDeviceDataSourcesResponse result) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(result));
                        }

                        @Override
                        @RequiresNoPermission
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the list of all device data sources and their provider info.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public void getDeviceDataSourceInfos(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<List<DeviceDataSourceInfo>, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getDeviceDataSourceInfos(
                    mContext.getAttributionSource(),
                    new IGetDeviceDataSourceInfosCallback.Stub() {
                        @Override
                        @RequiresNoPermission
                        public void onResult(List<DeviceDataSourceInfo> result) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(result));
                        }

                        @Override
                        @RequiresNoPermission
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the {@link DeviceDataSource} for the current device.
     *
     * <p>The caller must hold at least one Health Connect read permission in order to call this
     * method.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     */
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public void getCurrentDeviceDataSource(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<DeviceDataSource, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getCurrentDeviceDataSource(
                    mContext.getAttributionSource(),
                    new IGetCurrentDeviceDataSourceCallback.Stub() {
                        @Override
                        @RequiresNoPermission
                        public void onResult(DeviceDataSource result) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(result));
                        }

                        @Override
                        @RequiresNoPermission
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stages all HealthConnect remote data and returns any errors in a callback. Errors encountered
     * for all the files are shared in the provided callback. Any authorization / permissions
     * related error is reported to the callback with an empty file name.
     *
     * <p>The staged data will later be restored (integrated) into the existing Health Connect data.
     * Any existing data will not be affected by the staged data.
     *
     * <p>The file names passed should be the same as the ones on the original device that were
     * backed up or are being transferred directly.
     *
     * <p>If a file already exists in the staged data then it will be replaced. However, note that
     * staging data is a one time process. And if the staged data has already been processed then
     * any attempt to stage data again will be silently ignored.
     *
     * <p>The caller is responsible for closing the original file descriptors. The file descriptors
     * are duplicated and the originals may be closed by the application at any time after this API
     * returns.
     *
     * <p>The caller should update the data download states using {@link #updateDataDownloadState}
     * before calling this API.
     *
     * @param pfdsByFileName The map of file names and their {@link ParcelFileDescriptor}s.
     * @param executor The {@link Executor} on which to invoke the callback.
     * @param callback The callback which will receive the outcome of this call.
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.STAGE_HEALTH_CONNECT_REMOTE_DATA)
    public void stageAllHealthConnectRemoteData(
            @NonNull Map<String, ParcelFileDescriptor> pfdsByFileName,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, StageRemoteDataException> callback)
            throws NullPointerException {
        Objects.requireNonNull(pfdsByFileName);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.stageAllHealthConnectRemoteData(
                    new StageRemoteDataRequest(pfdsByFileName),
                    mContext.getUser(),
                    new IDataStagingFinishedCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(StageRemoteDataException stageRemoteDataException) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onError(stageRemoteDataException));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Copies all HealthConnect backup data in the passed FDs.
     *
     * <p>The shared data must later be sent for Backup to cloud or another device.
     *
     * <p>We are responsible for closing the original file descriptors. The caller must not close
     * the FD before that.
     *
     * @param pfdsByFileName The map of file names and their {@link ParcelFileDescriptor}s.
     * @hide
     */
    public void getAllDataForBackup(@NonNull Map<String, ParcelFileDescriptor> pfdsByFileName) {
        Objects.requireNonNull(pfdsByFileName);

        try {
            mService.getAllDataForBackup(
                    new StageRemoteDataRequest(pfdsByFileName), mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the names of all HealthConnect Backup files
     *
     * @hide
     */
    public Set<String> getAllBackupFileNames(boolean forDeviceToDevice) {
        try {
            return mService.getAllBackupFileNames(forDeviceToDevice).getFileNames();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes all previously staged HealthConnect data from the disk. For testing purposes only.
     *
     * <p>This deletes only the staged data leaving any other Health Connect data untouched.
     *
     * @hide
     */
    @TestApi
    @UserHandleAware
    public void deleteAllStagedRemoteData() throws NullPointerException {
        try {
            mService.deleteAllStagedRemoteData(mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allows setting lower rate limits in tests.
     *
     * @hide
     */
    @TestApi
    public void setLowerRateLimitsForTesting(boolean enabled) throws NullPointerException {
        try {
            mService.setLowerRateLimitsForTesting(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the download state of the Health Connect data.
     *
     * <p>The data should've been downloaded and the corresponding download states updated before
     * the app calls {@link #stageAllHealthConnectRemoteData}. Once {@link
     * #stageAllHealthConnectRemoteData} has been called the downloaded state becomes {@link
     * #DATA_DOWNLOAD_COMPLETE} and future attempts to update the download state are ignored.
     *
     * <p>The only valid order of state transition are:
     *
     * <ul>
     *   <li>{@link #DATA_DOWNLOAD_STARTED} to {@link #DATA_DOWNLOAD_COMPLETE}
     *   <li>{@link #DATA_DOWNLOAD_STARTED} to {@link #DATA_DOWNLOAD_RETRY} to {@link
     *       #DATA_DOWNLOAD_COMPLETE}
     *   <li>{@link #DATA_DOWNLOAD_STARTED} to {@link #DATA_DOWNLOAD_FAILED}
     *   <li>{@link #DATA_DOWNLOAD_STARTED} to {@link #DATA_DOWNLOAD_RETRY} to {@link
     *       #DATA_DOWNLOAD_FAILED}
     * </ul>
     *
     * <p>Note that it's okay if some states are missing in of the sequences above but the order has
     * to be one of the above.
     *
     * <p>Only one app will have the permission to call this API so it is assured that no one else
     * will be able to update this state.
     *
     * @param downloadState The download state which needs to be purely from {@link
     *     DataDownloadState}
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.STAGE_HEALTH_CONNECT_REMOTE_DATA)
    public void updateDataDownloadState(@DataDownloadState int downloadState) {
        try {
            mService.updateDataDownloadState(downloadState);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the data to be displayed in the Health Connect Backup and restore settings.
     *
     * <p>This API allows passing through various settings related to Health Connect backup and
     * restore. Values provided are persistent until explicitly overridden by a subsequent call to
     * this method. If a parameter is not set in the request, it indicates that the corresponding
     * existing value in the settings should not be overridden and will retain its current state.
     *
     * @param request The request object containing the settings to be updated.
     * @throws SecurityException If the caller does not have the required permissions.
     * @hide
     */
    // TODO: b/430529896 remove suppression when the linter is fixed
    @SuppressWarnings("MissingPermission")
    @UserHandleAware
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    @RequiresPermission(anyOf = {BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS, BACKUP})
    public void updateHealthConnectBackupAndRestoreSettings(
            @NonNull UpdateBackupAndRestoreSettingsRequest request) {
        try {
            mService.updateHealthConnectBackupAndRestoreSettings(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Asynchronously returns the current UI state of Health Connect as it goes through the
     * Data-Migration process. In case there was an error reading the data on the disk the error
     * will be returned in the callback.
     *
     * <p>See also {@link HealthConnectMigrationUiState} object describing the HealthConnect UI
     * state.
     *
     * @param executor The {@link Executor} on which to invoke the callback.
     * @param callback The callback which will receive the current {@link
     *     HealthConnectMigrationUiState} or the {@link HealthConnectException}.
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void getHealthConnectMigrationUiState(
            @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<HealthConnectMigrationUiState, HealthConnectException>
                            callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getHealthConnectMigrationUiState(
                    new IGetHealthConnectMigrationUiStateCallback.Stub() {
                        @Override
                        public void onResult(HealthConnectMigrationUiState migrationUiState) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(migrationUiState));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () -> callback.onError(exception.getHealthConnectException()));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Asynchronously returns the current state of the Health Connect data as it goes through the
     * Data-Restore and/or the Data-Migration process. In case there was an error reading the data
     * on the disk the error will be returned in the callback.
     *
     * <p>See also {@link HealthConnectDataState} object describing the HealthConnect state.
     *
     * @param executor The {@link Executor} on which to invoke the callback.
     * @param callback The callback which will receive the current {@link HealthConnectDataState} or
     *     the {@link HealthConnectException}.
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(
            anyOf = {
                MANAGE_HEALTH_DATA_PERMISSION,
                Manifest.permission.MIGRATE_HEALTH_CONNECT_DATA
            })
    public void getHealthConnectDataState(
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<HealthConnectDataState, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.getHealthConnectDataState(
                    new IGetHealthConnectDataStateCallback.Stub() {
                        @Override
                        public void onResult(HealthConnectDataState healthConnectDataState) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(healthConnectDataState));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () -> callback.onError(exception.getHealthConnectException()));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the backup status in Health Connect for the last backup attempt. This is used to
     * communicate statuses to the user, including the status of the current backup and when some
     * statuses last happened (e.g. last successful backup).
     *
     * @param request The request object containing the new status of a backup.
     * @throws SecurityException If the caller does not have the required permissions.
     * @hide
     */
    // TODO: b/430529896 remove suppression when the linter is fixed
    @SuppressWarnings("MissingPermission")
    @UserHandleAware
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    @RequiresPermission(anyOf = {BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS, BACKUP})
    public void updateHealthConnectBackupStatus(
            @NonNull UpdateHealthConnectBackupStatusRequest request) {
        try {
            mService.updateHealthConnectBackupStatus(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a set of record type classes that device data sources are capable of providing.
     *
     * <p>Use this method to avoid making unnecessary permission requests when reading device data
     * in case the data type is not provided by any device data sources.
     *
     * <p>This will filter out any permission sensitive data types the caller does not hold read
     * permissions for. Data types belonging to {@link
     * #getPermissionSensitiveDeviceDataSourceCapabilities} are excluded from the response unless
     * the caller holds read permissions for those data types.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     */
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public void getDeviceDataSourceCapabilities(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull
                    OutcomeReceiver<DeviceDataSourceCapabilities, HealthConnectException>
                            callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getDeviceDataSourceCapabilities(
                    mContext.getAttributionSource(),
                    new IDeviceDataSourceCapabilitiesCallback.Stub() {
                        @Override
                        @RequiresNoPermission
                        public void onResult(
                                android.health.connect.aidl.DeviceDataSourceCapabilities result) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () ->
                                            callback.onResult(
                                                    new DeviceDataSourceCapabilities(result)));
                        }

                        @Override
                        @RequiresNoPermission
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of unique dates for which the DB has at least one entry.
     *
     * @param recordTypes List of record types classes for which to get the activity dates.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws java.lang.IllegalArgumentException If the record types list is empty.
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void queryActivityDates(
            @NonNull List<Class<? extends Record>> recordTypes,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<List<LocalDate>, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Objects.requireNonNull(recordTypes);

        if (recordTypes.isEmpty()) {
            throw new IllegalArgumentException("Record types list can not be empty");
        }

        try {
            mService.getActivityDates(
                    new ActivityDatesRequestParcel(recordTypes),
                    new IActivityDatesResponseCallback.Stub() {
                        @Override
                        public void onResult(ActivityDatesResponseParcel parcel) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(parcel.getDates()));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });

        } catch (RemoteException exception) {
            exception.rethrowFromSystemServer();
        }
    }

    /**
     * Marks the start of the migration and block API calls.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MIGRATE_HEALTH_CONNECT_DATA)
    @SystemApi
    public void startMigration(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, MigrationException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.startMigration(
                    mContext.getPackageName(), wrapMigrationCallback(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Marks the end of the migration.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MIGRATE_HEALTH_CONNECT_DATA)
    @SystemApi
    public void finishMigration(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, MigrationException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.finishMigration(
                    mContext.getPackageName(), wrapMigrationCallback(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Writes data to the module database.
     *
     * @param entities List of {@link MigrationEntity} to migrate.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MIGRATE_HEALTH_CONNECT_DATA)
    @SystemApi
    public void writeMigrationData(
            @NonNull List<MigrationEntity> entities,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, MigrationException> callback) {

        Objects.requireNonNull(entities);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.writeMigrationData(
                    mContext.getPackageName(),
                    new MigrationEntityParcel(entities),
                    wrapMigrationCallback(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the minimum version on which the module will inform the migrator package of its
     * migration readiness.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MIGRATE_HEALTH_CONNECT_DATA)
    public void insertMinDataMigrationSdkExtensionVersion(
            int requiredSdkExtension,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, MigrationException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.insertMinDataMigrationSdkExtensionVersion(
                    mContext.getPackageName(),
                    requiredSdkExtension,
                    wrapMigrationCallback(executor, callback));

        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Configures the settings for the scheduled export of Health Connect data.
     *
     * @param settings Settings to use for the scheduled export. Use null to clear the settings.
     * @throws RuntimeException for internal errors
     * @hide
     */
    @WorkerThread
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void configureScheduledExport(@Nullable ScheduledExportSettings settings) {
        try {
            mService.configureScheduledExport(settings, mContext.getUser());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Queries the status of a scheduled export.
     *
     * @throws RuntimeException for internal errors
     * @hide
     */
    @WorkerThread
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void getScheduledExportStatus(
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<ScheduledExportStatus, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getScheduledExportStatus(
                    mContext.getUser(),
                    new IScheduledExportStatusCallback.Stub() {
                        @Override
                        public void onResult(ScheduledExportStatus status) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(status));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Queries the status of a data import.
     *
     * @throws RuntimeException for internal errors
     * @hide
     */
    @WorkerThread
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void getImportStatus(
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<ImportStatus, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getImportStatus(
                    mContext.getUser(),
                    new IImportStatusCallback.Stub() {
                        @Override
                        public void onResult(ImportStatus status) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(status));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Imports the given compressed database file.
     *
     * @throws RuntimeException for internal errors
     * @hide
     */
    @WorkerThread
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void runImport(
            @NonNull Uri file,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(file);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.runImport(
                    mContext.getUser(),
                    file,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Triggers an immediate export of health connect data.
     *
     * @throws RuntimeException for internal errors
     * @hide
     */
    @WorkerThread
    @FlaggedApi(FLAG_IMMEDIATE_EXPORT)
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void runImmediateExport(
            @NonNull Uri file,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(file);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.runImmediateExport(
                    file,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns currently set period between scheduled exports for this user.
     *
     * <p>If you are calling this function for the first time after a user unlock, this might take
     * some time so consider calling this on a thread.
     *
     * @return Period between scheduled exports in days, 0 is returned if period between scheduled
     *     exports is not set.
     * @throws RuntimeException for internal errors
     * @hide
     */
    @WorkerThread
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    @IntRange(from = 0, to = 30)
    public int getScheduledExportPeriodInDays() {
        try {
            return mService.getScheduledExportPeriodInDays(mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Queries the document providers available to be used for export/import.
     *
     * @throws RuntimeException for internal errors
     * @hide
     */
    @WorkerThread
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void queryDocumentProviders(
            @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<List<ExportImportDocumentProvider>, HealthConnectException>
                            callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.queryDocumentProviders(
                    mContext.getUser(),
                    new IQueryDocumentProvidersCallback.Stub() {
                        @Override
                        public void onResult(List<ExportImportDocumentProvider> providers) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(providers));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Record> IReadRecordsResponseCallback.Stub getReadCallback(
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> callback) {
        return new IReadRecordsResponseCallback.Stub() {
            @Override
            public void onResult(ReadRecordsResponseParcel parcel) {
                Binder.clearCallingIdentity();
                try {
                    List<T> externalRecords =
                            (List<T>)
                                    mInternalExternalRecordConverter.getExternalRecords(
                                            parcel.getRecordsParcel().getRecords());
                    executor.execute(
                            () ->
                                    callback.onResult(
                                            new ReadRecordsResponse<>(
                                                    externalRecords, parcel.getPageToken())));
                } catch (ClassCastException castException) {
                    HealthConnectException healthConnectException =
                            new HealthConnectException(
                                    HealthConnectException.ERROR_INTERNAL,
                                    castException.getMessage());
                    returnError(
                            executor,
                            new HealthConnectExceptionParcel(healthConnectException),
                            callback);
                }
            }

            @Override
            public void onError(HealthConnectExceptionParcel exception) {
                returnError(executor, exception, callback);
            }
        };
    }

    private List<Record> toExternalRecordsWithUuids(
            List<RecordInternal<?>> recordInternals, List<String> uuids) {
        int i = 0;
        List<Record> records = new ArrayList<>();

        for (RecordInternal<?> recordInternal : recordInternals) {
            recordInternal.setUuid(uuids.get(i++));
            records.add(recordInternal.toExternalRecord());
        }

        return records;
    }

    private static <RES, ERR extends Throwable> void returnResult(
            Executor executor, @Nullable RES result, OutcomeReceiver<RES, ERR> callback) {
        Binder.clearCallingIdentity();
        executor.execute(() -> callback.onResult(result));
    }

    private void returnError(
            Executor executor,
            HealthConnectExceptionParcel exception,
            OutcomeReceiver<?, HealthConnectException> callback) {
        Binder.clearCallingIdentity();
        executor.execute(() -> callback.onError(exception.getHealthConnectException()));
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        DATA_DOWNLOAD_STATE_UNKNOWN,
        DATA_DOWNLOAD_STARTED,
        DATA_DOWNLOAD_RETRY,
        DATA_DOWNLOAD_FAILED,
        DATA_DOWNLOAD_COMPLETE
    })
    public @interface DataDownloadState {}

    /**
     * Returns {@code true} if the given permission protects access to health connect data.
     *
     * @hide
     */
    @SystemApi
    public static boolean isHealthPermission(
            @NonNull Context context, @NonNull final String permission) {
        if (!permission.startsWith(HEALTH_PERMISSION_PREFIX)) {
            return false;
        }
        return getHealthPermissions(context).contains(permission);
    }

    /**
     * Returns an <b>immutable</b> set of health permissions defined within the module and belonging
     * to {@link android.health.connect.HealthPermissions#HEALTH_PERMISSION_GROUP}.
     *
     * <p><b>Note:</b> If we, for some reason, fail to retrieve these, we return an empty set rather
     * than crashing the device. This means the health permissions infra will be inactive.
     *
     * @hide
     */
    @NonNull
    @SystemApi
    public static Set<String> getHealthPermissions(@NonNull Context context) {
        if (sHealthPermissions != null) {
            return sHealthPermissions;
        }

        PackageInfo packageInfo;
        try {
            final PackageManager pm = context.getApplicationContext().getPackageManager();
            final PermissionGroupInfo permGroupInfo =
                    pm.getPermissionGroupInfo(
                            android.health.connect.HealthPermissions.HEALTH_PERMISSION_GROUP,
                            /* flags= */ 0);
            packageInfo =
                    pm.getPackageInfo(
                            permGroupInfo.packageName,
                            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS));
        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, "Health permission group or HC package not found", ex);
            Set<String> result = Collections.emptySet();
            sHealthPermissions = result;
            return result;
        }

        Set<String> permissions = new HashSet<>();
        for (PermissionInfo perm : packageInfo.permissions) {
            if (HealthPermissions.isValidHealthPermission(perm)) {
                permissions.add(perm.name);
            }
        }
        Set<String> result = Collections.unmodifiableSet(permissions);
        sHealthPermissions = result;
        return result;
    }

    /**
     * Allow tests to reset the cached Set of Health Connect permissions.
     *
     * @hide
     */
    @VisibleForTesting
    public static void resetHealthPermissionsCache() {
        // This method should only be used for testing. Having a global static cache of Health
        // Connect permissions is good for efficiency but can play havoc with test isolation.
        // Allow tests which modify the package manager to clear the cache.
        sHealthPermissions = null;
    }

    @NonNull
    private static IMigrationCallback wrapMigrationCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, MigrationException> callback) {
        return new IMigrationCallback.Stub() {
            @Override
            public void onSuccess() {
                Binder.clearCallingIdentity();
                executor.execute(() -> callback.onResult(null));
            }

            @Override
            public void onError(MigrationException exception) {
                Binder.clearCallingIdentity();
                executor.execute(() -> callback.onError(exception));
            }
        };
    }

    /**
     * Inserts or updates a list of {@link MedicalResource}s into the HealthConnect database using
     * {@link UpsertMedicalResourceRequest}.
     *
     * <p>For each {@link UpsertMedicalResourceRequest}, one {@link MedicalResource} will be
     * returned. The returned list of {@link MedicalResource}s will be in the same order as the
     * {@code requests}.
     *
     * <p>Note that a {@link MedicalDataSource} needs to be created using {@link
     * #createMedicalDataSource} before any {@link MedicalResource}s can be upserted for this
     * source.
     *
     * <p>Medical data is represented using the <a href="https://hl7.org/fhir/">Fast Healthcare
     * Interoperability Resources (FHIR)</a> standard. The FHIR resource provided in {@link
     * UpsertMedicalResourceRequest#getData()} is expected to be valid FHIR in JSON representation
     * for the specified {@link UpsertMedicalResourceRequest#getFhirVersion()} according to the <a
     * href="https://hl7.org/fhir/resourcelist.html">FHIR spec</a>. Structural validation checks
     * such as resource structure, field types and presence of required fields are performed, but
     * these checks may not cover all FHIR spec requirements and may change in future versions.
     *
     * <p>Data written to Health Connect should be for a single individual only. However, the API
     * allows for multiple Patient resources to be written to account for the possibility of
     * multiple Patient resources being present in one individual's medical record.
     *
     * <p>Each {@link UpsertMedicalResourceRequest} also has to meet the following requirements.
     *
     * <ul>
     *   <li>The FHIR resource contains an "id" and "resourceType" field.
     *   <li>The FHIR resource type is in our accepted list of resource types. See {@link
     *       FhirResource} for the accepted types.
     *   <li>The FHIR resource does not contain any "contained" resources.
     *   <li>The resource can be mapped to one of the READ_MEDICAL_DATA_ {@link HealthPermissions}
     *       categories.
     *   <li>The {@link UpsertMedicalResourceRequest#getDataSourceId()} is valid.
     *   <li>The {@link UpsertMedicalResourceRequest#getFhirVersion()} matches the {@link
     *       FhirVersion} of the {@link MedicalDataSource}.
     * </ul>
     *
     * <p>If any request contains invalid {@link MedicalDataSource} IDs, the API will throw an
     * {@link IllegalArgumentException}, and none of the {@code requests} will be upserted into the
     * HealthConnect database.
     *
     * <p>If any request is deemed invalid for any other reasons, the caller will receive an
     * exception with code {@link HealthConnectException#ERROR_INVALID_ARGUMENT} via {@code
     * callback.onError()}, and none of the {@code requests} will be upserted into the HealthConnect
     * database.
     *
     * <p>If data for any {@link UpsertMedicalResourceRequest} fails to be upserted, then no data
     * from any {@code requests} will be upserted into the database.
     *
     * <p>The uniqueness of each request is calculated comparing the combination of {@link
     * UpsertMedicalResourceRequest#getDataSourceId() data source id}, FHIR resource type and FHIR
     * resource ID extracted from the provided {@link UpsertMedicalResourceRequest#getData() data}.
     * If the above combination does not match with an existing one in Health Connect, then a new
     * {@link MedicalResource} is inserted, otherwise the existing one is updated.
     *
     * @param requests List of upsert requests.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if any {@code requests} contains invalid {@link
     *     MedicalDataSource} IDs.
     */
    // Suppress missing because API flagged out.
    // TODO: b/355156275 - remove suppression once API not flagged out.
    @SuppressWarnings({"MissingPermission"})
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    @RequiresPermission(WRITE_MEDICAL_DATA)
    public void upsertMedicalResources(
            @NonNull List<UpsertMedicalResourceRequest> requests,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<List<MedicalResource>, HealthConnectException> callback) {
        Objects.requireNonNull(requests);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        if (requests.isEmpty()) {
            returnResult(executor, List.of(), callback);
            return;
        }

        try {
            mService.upsertMedicalResourcesFromRequestsParcel(
                    mContext.getAttributionSource(),
                    new UpsertMedicalResourceRequestsParcel(requests),
                    new IMedicalResourceListParcelResponseCallback.Stub() {
                        @Override
                        public void onResult(MedicalResourceListParcel medicalResourceListParcel) {
                            returnResult(
                                    executor,
                                    medicalResourceListParcel.getMedicalResources(),
                                    callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reads {@link MedicalResource}s based on a list of {@link MedicalResourceId}s.
     *
     * <p>The number and order of medical resources returned by this API is not guaranteed. The
     * number will depend on the factors below:
     *
     * <ul>
     *   <li>If an empty list of {@code ids} is provided, an empty list will be returned.
     *   <li>If the size of {@code ids} is more than 5000, the API will throw an {@link
     *       IllegalArgumentException}.
     *   <li>If any ID in {@code ids} is invalid, the API will throw an {@link
     *       IllegalArgumentException}.
     *   <li>If any ID in {@code ids} does not exist, no medical resource will be returned for that
     *       ID.
     *   <li>Callers will only get medical resources they are permitted to get. See below.
     * </ul>
     *
     * Being permitted to read medical resources is dependent on the following logic, in priority
     * order, earlier statements take precedence.
     *
     * <ol>
     *   <li>A caller with the system permission can get any medical resources in the foreground or
     *       background.
     *   <li>A caller without any read or write permissions for health data will not be able to get
     *       any medical resources and receive an exception with code {@link
     *       HealthConnectException#ERROR_SECURITY} via {@code callback.onError()}, even for medical
     *       resources the caller has created.
     *   <li>Callers can get medical resources they have created, whether this method is called in
     *       the foreground or background. Note this only applies if the caller has at least one
     *       read or write permission for health data.
     *   <li>For any given medical resource, a caller can get that medical resource in the
     *       foreground if the caller has the corresponding read permission, or in the background if
     *       it also has {@link
     *       android.health.connect.HealthPermissions#READ_HEALTH_DATA_IN_BACKGROUND}.
     *   <li>In all other cases the caller is not permitted to get the given medical resource and it
     *       will not be returned.
     * </ol>
     *
     * <p>Each returned {@link MedicalResource} has passed the Health Connect FHIR validation checks
     * at write time, but is not guaranteed to meet all requirements of the <a
     * href="https://hl7.org/fhir/resourcelist.html">Fast Healthcare Interoperability Resources
     * (FHIR) spec</a>. If required, clients should perform their own checks on the data.
     *
     * @param ids Identifiers on which to perform read operation.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if the size of {@code ids} is more than 5000 or if any id is
     *     invalid.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public void readMedicalResources(
            @NonNull List<MedicalResourceId> ids,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<List<MedicalResource>, HealthConnectException> callback) {
        Objects.requireNonNull(ids);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        if (ids.isEmpty()) {
            returnResult(executor, List.of(), callback);
            return;
        }

        if (ids.size() > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "The number of requested IDs must be <= " + MAXIMUM_PAGE_SIZE);
        }

        try {
            mService.readMedicalResourcesByIds(
                    mContext.getAttributionSource(),
                    ids,
                    new IReadMedicalResourcesResponseCallback.Stub() {
                        @Override
                        public void onResult(ReadMedicalResourcesResponse response) {
                            returnResult(executor, response.getMedicalResources(), callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reads {@link MedicalResource}s based on {@link ReadMedicalResourcesInitialRequest} or {@link
     * ReadMedicalResourcesPageRequest}.
     *
     * <p>Being permitted to read medical resources is dependent on the following logic, in priority
     * order, earlier statements take precedence.
     *
     * <ol>
     *   <li>A caller with the system permission can get any medical resources in the foreground or
     *       background.
     *   <li>A caller without any read or write permissions for health data will not be able to get
     *       any medical resources and receive an exception with code {@link
     *       HealthConnectException#ERROR_SECURITY} via {@code callback.onError()}, even for medical
     *       resources the caller has created.
     *   <li>Callers can get medical resources they have created, whether this method is called in
     *       the foreground or background. Note this only applies if the caller has at least one
     *       read or write permission for health data.
     *   <li>For any given medical resource, a caller can get that medical resource in the
     *       foreground if the caller has the corresponding read permission, or in the background if
     *       it also has {@link
     *       android.health.connect.HealthPermissions#READ_HEALTH_DATA_IN_BACKGROUND}.
     *   <li>In all other cases the caller is not permitted to get the given medical resource and it
     *       will not be returned.
     * </ol>
     *
     * @param request The read request {@link ReadMedicalResourcesInitialRequest} or {@link
     *     ReadMedicalResourcesPageRequest}.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if {@code request} has set page size to be less than 1 or
     *     more than 5000; or if contains unsupported medical resource type or invalid {@link
     *     MedicalDataSource} IDs when using {@link ReadMedicalResourcesInitialRequest}.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public void readMedicalResources(
            @NonNull ReadMedicalResourcesRequest request,
            @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<ReadMedicalResourcesResponse, HealthConnectException>
                            callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.readMedicalResourcesByRequest(
                    mContext.getAttributionSource(),
                    request.toParcel(),
                    new IReadMedicalResourcesResponseCallback.Stub() {
                        @Override
                        public void onResult(ReadMedicalResourcesResponse response) {
                            returnResult(executor, response, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes {@link MedicalResource}s based on given filters in {@link
     * DeleteMedicalResourcesRequest}.
     *
     * <p>Regarding permissions:
     *
     * <ul>
     *   <li>Only apps with the system permission can delete data written by apps other than
     *       themselves.
     *   <li>Deletes are permitted in the foreground or background.
     * </ul>
     *
     * @param request The delete request.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if {@code request} contains unsupported medical resource
     *     types or invalid {@link MedicalDataSource} IDs.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    // Suppress missing because API flagged out. "RequiresPermission" is also needed because
    // @RequiresPermission generates javadoc for the flagged out permission.
    // TODO: b/355156275 - remove suppression once API not flagged out.
    @SuppressWarnings({"MissingPermission", "RequiresPermission"})
    @RequiresPermission(anyOf = {WRITE_MEDICAL_DATA, MANAGE_HEALTH_DATA_PERMISSION})
    public void deleteMedicalResources(
            @NonNull DeleteMedicalResourcesRequest request,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.deleteMedicalResourcesByRequest(
                    mContext.getAttributionSource(),
                    request,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            returnResult(executor, null, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes a list of {@link MedicalResource}s by the provided list of {@link
     * MedicalResourceId}s.
     *
     * <ul>
     *   <li>If any ID in {@code ids} is invalid, the API will throw an {@link
     *       IllegalArgumentException}, and nothing will be deleted.
     *   <li>If any ID in {@code ids} does not exist, that ID will be ignored, while deletion on
     *       other IDs will be performed.
     * </ul>
     *
     * <p>Regarding permissions:
     *
     * <ul>
     *   <li>Only apps with the system permission can delete data written by apps other than
     *       themselves.
     *   <li>Deletes are permitted in the foreground or background.
     * </ul>
     *
     * @param ids The ids to delete.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    // Suppress missing because API flagged out. "RequiresPermission" is also needed because
    // @RequiresPermission generates javadoc for the flagged out permission.
    // TODO: b/355156275 - remove suppression once API not flagged out.
    @SuppressWarnings({"MissingPermission", "RequiresPermission"})
    @RequiresPermission(anyOf = {WRITE_MEDICAL_DATA, MANAGE_HEALTH_DATA_PERMISSION})
    public void deleteMedicalResources(
            @NonNull List<MedicalResourceId> ids,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(ids);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        if (ids.isEmpty()) {
            returnResult(executor, null, callback);
            return;
        }

        try {
            mService.deleteMedicalResourcesByIds(
                    mContext.getAttributionSource(),
                    ids,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            returnResult(executor, null, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves information about all medical resource types and returns a list of {@link
     * MedicalResourceTypeInfo}.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void queryAllMedicalResourceTypeInfos(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull
                    OutcomeReceiver<List<MedicalResourceTypeInfo>, HealthConnectException>
                            callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.queryAllMedicalResourceTypeInfos(
                    new IMedicalResourceTypeInfosCallback.Stub() {
                        @Override
                        public void onResult(List<MedicalResourceTypeInfo> response) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(response));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a {@link MedicalDataSource} in HealthConnect based on the {@link
     * CreateMedicalDataSourceRequest} request values.
     *
     * <p>Medical data is represented using the <a href="https://hl7.org/fhir/">Fast Healthcare
     * Interoperability Resources (FHIR)</a> standard.
     *
     * <p>A {@link MedicalDataSource} needs to be created before any {@link MedicalResource}s for
     * that source can be inserted. Separate {@link MedicalDataSource}s should be created for
     * medical records coming from different sources (e.g. different FHIR endpoints, different
     * healthcare systems), unless the data has been reconciled and all records have a unique
     * combination of resource type and resource id.
     *
     * <p>The {@link CreateMedicalDataSourceRequest.Builder#setDisplayName display name} must be
     * unique per app, and {@link CreateMedicalDataSourceRequest.Builder#setFhirVersion} FHIR
     * version} must be a version supported by Health Connect, as documented on the {@link
     * FhirVersion}. See {@link CreateMedicalDataSourceRequest.Builder#setFhirBaseUri} for more
     * details on the FHIR base URI.
     *
     * @param request Creation request.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if {@code request} contains a FHIR base URI or display name
     *     exceeding the character limits, or an unsupported FHIR version.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    // Suppress missing because API flagged out.
    // TODO: b/355156275 - remove suppression once API not flagged out.
    @SuppressWarnings({"MissingPermission"})
    @RequiresPermission(WRITE_MEDICAL_DATA)
    public void createMedicalDataSource(
            @NonNull CreateMedicalDataSourceRequest request,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<MedicalDataSource, HealthConnectException> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.createMedicalDataSource(
                    mContext.getAttributionSource(),
                    request,
                    new IMedicalDataSourceResponseCallback.Stub() {
                        @Override
                        public void onResult(MedicalDataSource dataSource) {
                            returnResult(executor, dataSource, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@link MedicalDataSource}s for the provided list of IDs.
     *
     * <p>The number and order of medical data sources returned by this API is not guaranteed. The
     * number will depend on the factors below:
     *
     * <ul>
     *   <li>If an empty list of {@code ids} is provided, an empty list will be returned.
     *   <li>If any ID in {@code ids} is invalid, the caller will receive an exception with code
     *       {@link HealthConnectException#ERROR_INVALID_ARGUMENT} via {@code callback.onError()}.
     *   <li>If any ID in {@code ids} does not exist, no data source will be returned for that ID.
     *   <li>Callers will only get data sources they are permitted to get. See below.
     * </ul>
     *
     * <p>There is no specific read permission for getting data sources. Instead, permission to read
     * data sources is based on whether the caller has permission to read the data currently linked
     * to that data source. Being permitted to get data sources is dependent on the following logic,
     * in priority order, earlier statements take precedence.
     *
     * <ol>
     *   <li>A caller with the system permission can get any data source in the foreground or
     *       background.
     *   <li>A caller without any read or write permissions for health data will not be able to get
     *       any medical data sources and receive an exception with code {@link
     *       HealthConnectException#ERROR_SECURITY} via {@code callback.onError()}, even for data
     *       sources the caller has created.
     *   <li>Callers can get data sources they have created, whether this method is called in the
     *       foreground or background. Note this only applies if the caller has at least one read or
     *       write permission for health data.
     *   <li>For any given data source, a caller can get that data source in the foreground if the
     *       caller has permission to read any of the data linked to that data source. For clarity,
     *       the does not allow it to get an empty data source.
     *   <li>For any given data source, a caller can get that data source in the background if it
     *       has both permission to read any of the data linked to that data source, and {@link
     *       android.health.connect.HealthPermissions#READ_HEALTH_DATA_IN_BACKGROUND}.
     *   <li>In all other cases the caller is not permitted to get the given data source and it will
     *       not be returned.
     * </ol>
     *
     * @param ids Identifiers for data sources to get.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if the size of {@code ids} is more than 5000.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public void getMedicalDataSources(
            @NonNull List<String> ids,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<List<MedicalDataSource>, HealthConnectException> callback) {
        Objects.requireNonNull(ids);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        if (ids.isEmpty()) {
            returnResult(executor, List.of(), callback);
            return;
        }

        if (ids.size() > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "The number of requested IDs must be <= " + MAXIMUM_PAGE_SIZE);
        }

        try {
            mService.getMedicalDataSourcesByIds(
                    mContext.getAttributionSource(),
                    ids,
                    new IMedicalDataSourcesResponseCallback.Stub() {
                        @Override
                        public void onResult(List<MedicalDataSource> result) {
                            returnResult(executor, result, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the requested {@link MedicalDataSource}s.
     *
     * <p>Number of data sources returned by this API will depend based on below factors:
     *
     * <ul>
     *   <li>If an empty {@link GetMedicalDataSourcesRequest#getPackageNames() list of package
     *       names} is passed, all permitted data sources from all apps will be returned. See below.
     *   <li>If any package name in the {@link GetMedicalDataSourcesRequest#getPackageNames() list
     *       of package names} is invalid, the API will throw an {@link IllegalArgumentException}.
     *   <li>If a non-empty {@link GetMedicalDataSourcesRequest#getPackageNames() list of package
     *       names} is specified in the request, then only the permitted data sources created by
     *       those packages will be returned. See below.
     * </ul>
     *
     * <p>There is no specific read permission for getting data sources. Instead permission to read
     * data sources is based on whether the caller has permission to read the data currently linked
     * to that data source. Being permitted to get data sources is dependent on the following logic,
     * in priority order, earlier statements take precedence.
     *
     * <ol>
     *   <li>A caller with the system permission can get any data source in the foreground or
     *       background.
     *   <li>A caller without any read or write permissions for health data will not be able to get
     *       any medical data sources and receive an exception with code {@link
     *       HealthConnectException#ERROR_SECURITY} via {@code callback.onError()}, even for data
     *       sources the caller has created.
     *   <li>Callers can get data sources they have created, whether this method is called in the
     *       foreground or background. Note this only applies if the caller has at least one read or
     *       write permission for health data.
     *   <li>For any given data source, a caller can get that data source in the foreground if the
     *       caller has permission to read any of the data linked to that data source. For clarity,
     *       the does not allow it to get an empty data source.
     *   <li>For any given data source, a caller can get that data source in the background if it
     *       has both permission to read any of the data linked to that data source, and {@link
     *       android.health.connect.HealthPermissions#READ_HEALTH_DATA_IN_BACKGROUND}.
     *   <li>In all other cases the caller is not permitted to get the given data source and it will
     *       not be returned.
     * </ol>
     *
     * @param request the request for which data sources to return.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @throws IllegalArgumentException if {@code request} contains invalid package names.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public void getMedicalDataSources(
            @NonNull GetMedicalDataSourcesRequest request,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<List<MedicalDataSource>, HealthConnectException> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getMedicalDataSourcesByRequest(
                    mContext.getAttributionSource(),
                    request,
                    new IMedicalDataSourcesResponseCallback.Stub() {
                        @Override
                        public void onResult(List<MedicalDataSource> result) {
                            returnResult(executor, result, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes a {@link MedicalDataSource} and all data linked to it.
     *
     * <p>If the provided data source {@code id} is either invalid, or does not exist, or owned by
     * another apps, the caller will receive an exception with code {@link
     * HealthConnectException#ERROR_INVALID_ARGUMENT} via {@code callback.onError()}.
     *
     * <p>Regarding permissions:
     *
     * <ul>
     *   <li>Only apps with the system permission can delete data written by apps other than
     *       themselves.
     *   <li>Deletes are permitted in the foreground or background.
     * </ul>
     *
     * @param id The id of the data source to delete.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    // Suppress missing because API flagged out. "RequiresPermission" is also needed because
    // @RequiresPermission generates javadoc for the flagged out permission.
    // TODO: b/355156275 - remove suppression once API not flagged out.
    @SuppressWarnings({"MissingPermission", "RequiresPermission"})
    @RequiresPermission(anyOf = {WRITE_MEDICAL_DATA, MANAGE_HEALTH_DATA_PERMISSION})
    public void deleteMedicalDataSourceWithData(
            @NonNull String id,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {

        Objects.requireNonNull(id);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.deleteMedicalDataSourceWithData(
                    mContext.getAttributionSource(),
                    id,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            returnResult(executor, null, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the restore status in Health Connect for the last restore attempt. This is used to
     * communicate statuses to the user, including the status of the current rstore and when some
     * statuses last happened (e.g. last successful restore).
     *
     * @param request The request object containing the new status of a restore.
     * @throws SecurityException If the caller does not have the required permissions.
     * @hide
     */
    // TODO: b/430529896 remove suppression when the linter is fixed
    @SuppressWarnings("MissingPermission")
    @UserHandleAware
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    @RequiresPermission(anyOf = {BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS, BACKUP})
    public void updateHealthConnectRestoreStatus(
            @NonNull UpdateHealthConnectRestoreStatusRequest request) {
        try {
            mService.updateHealthConnectRestoreStatus(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of changes to be backed up based on a given change token.
     *
     * <p>The change token returned by the previous call should be passed in to resume the upload.
     * The caller should always store the previous change token returned in {@link
     * GetChangesForBackupResponse} and pass it into the next {@code getChangesForBackup} call.
     *
     * <p>A null change token means the caller is starting a fresh backup which starts from backing
     * up all data of the Health Connect data tables. After backing up all data tables, the API
     * switches to incremental backups using change logs.
     *
     * <p>If the change token is not found, it means that HealthConnect can no longer resume the
     * backup, and will respond with a {@link HealthConnectException#ERROR_INVALID_ARGUMENT}. In
     * this case the caller should restart the backup by calling {@code getChangesForBackup} with a
     * null change token.
     *
     * <p>If no changes are returned by the API, this means that the client has synced all changes
     * as of now. The caller should keep the change token returned in {@link
     * GetChangesForBackupResponse} to later resume the backup from this point.
     *
     * <p>The number of changes returned by this API is not guaranteed. The number will depend on
     * the factors below:
     *
     * <ul>
     *   <li>At the data table backup stage, up to 1000 changes will be returned per call. The last
     *       page may contain fewer than 1000 changes.
     *   <li>During incremental backups, data changes contained in up to 1000 change logs will be
     *       returned.
     * </ul>
     *
     * @param changeToken The token to resume the backup from.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the result of performing this operation.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE)
    @RequiresPermission(BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS)
    public void getChangesForBackup(
            @Nullable String changeToken,
            @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<GetChangesForBackupResponse, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.getChangesForBackup(
                    changeToken,
                    new IGetChangesForBackupResponseCallback.Stub() {
                        @Override
                        public void onResult(GetChangesForBackupResponse response) {
                            returnResult(executor, response, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all metadata to be backed up bundled as a single byte array.
     *
     * <p>This method returns a {@link BackupMetadata} object containing the user's Health Connect
     * metadata that should be backed up. The returned response is the latest snapshot of the Health
     * Connect metadata at the time of the call.
     *
     * <p>The caller should call this method at the end of each backup cycle to capture the latest
     * metadata, after calling {@link #getChangesForBackup} to fetch record changes. A backup cycle
     * is the process by which user data and metadata on a device are periodically saved to the
     * cloud. This ensures that the most up-to-date metadata snapshot are preserved in the backup.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the result of performing this operation.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE)
    @RequiresPermission(BACKUP_HEALTH_CONNECT_DATA_AND_SETTINGS)
    public void getLatestMetadataForBackup(
            @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<GetLatestMetadataForBackupResponse, HealthConnectException>
                            callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.getLatestMetadataForBackup(
                    new IGetLatestMetadataForBackupResponseCallback.Stub() {
                        @Override
                        public void onResult(GetLatestMetadataForBackupResponse response) {
                            returnResult(executor, response, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Restores the Health Connect metadata from a latest backup.
     *
     * <p>This method restores metadata previously backed up using {@link
     * #getLatestMetadataForBackup}. The provided {@code backupMetadata} object should contain the
     * metadata to be restored.
     *
     * <p>The callback will receive a {@link HealthConnectException} with {@code
     * HealthConnectException#ERROR_INVALID_ARGUMENT} if the provided {@code backupMetadata} can not
     * be parsed into valid metadata. The caller can retry with the same metadata once {@link
     * #canRestore} returns true. Other exceptions should not be retried.
     *
     * <p>The caller should call this method before calling {@link #restoreChanges}.
     *
     * @param backupMetadata The metadata to be restored.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the result of performing this operation.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE)
    @RequiresPermission(RESTORE_HEALTH_CONNECT_DATA_AND_SETTINGS)
    public void restoreLatestMetadata(
            @NonNull BackupMetadata backupMetadata,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(backupMetadata);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.restoreLatestMetadata(
                    backupMetadata,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            returnResult(executor, null, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the data can be restored based on the version which the data is serialized with.
     *
     * <p>If the data version is not yet supported by the Health Connect module, because the backup
     * was done on a newer version of Health Connect, this method will return false.
     *
     * <p>The caller should call this API before calling {@link #restoreLatestMetadata} or {@link
     * #restoreChanges}. If this method returns true, then the caller can continue with sending
     * either metadata or data to Health Connect for restore.
     *
     * @param dataVersion The version of the data to be restored.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the result of performing this operation.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE)
    @RequiresPermission(RESTORE_HEALTH_CONNECT_DATA_AND_SETTINGS)
    public void canRestore(
            int dataVersion,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, HealthConnectException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.canRestore(
                    dataVersion,
                    new ICanRestoreResponseCallback.Stub() {
                        @Override
                        public void onResult(boolean canRestore) {
                            returnResult(executor, canRestore, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Restores changes to Health Connect from a backup.
     *
     * <p>This method applies a list of changes previously backed up. Each {@link RestoreChange}
     * object represents an individual change, corresponding to the insertion or update of a record.
     * If a change is restored multiple times, the consecutive restores will be ignored by the
     * Health Connect module.
     *
     * <p>The caller should call {@link #restoreLatestMetadata} with the latest metadata it received
     * before calling this method. The caller should also send the changes in the order they were
     * received.
     *
     * <p>If any {@link RestoreChange} is not yet supported by the Health Connect module or can not
     * be parsed into valid record, {@link HealthConnectException#ERROR_INVALID_ARGUMENT} will be
     * thrown. The caller can retry with the same changes once {@link #canRestore} returns true.
     * Other exceptions should not be retried.
     *
     * @param restoreChanges The changes to be restored back to Health Connect.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the result of performing this operation.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE)
    @RequiresPermission(RESTORE_HEALTH_CONNECT_DATA_AND_SETTINGS)
    public void restoreChanges(
            @NonNull List<RestoreChange> restoreChanges,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(restoreChanges);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.restoreChanges(
                    restoreChanges,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            returnResult(executor, null, callback);
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current onboarding state of the Health Connect user. This API call triggers a
     * re-evaluation, so the returned state is up to date.
     *
     * <p>See also {@link HealthConnectOnboardingState} object describing the HealthConnect state.
     *
     * @param executor The {@link Executor} on which to invoke the callback.
     * @param callback The callback which will receive the current {@link
     *     HealthConnectOnboardingState} or the {@link HealthConnectException}.
     * @hide
     */
    @FlaggedApi(FLAG_ONBOARDING)
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    @UserHandleAware
    public void getHealthConnectOnboardingState(
            @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<HealthConnectOnboardingState, HealthConnectException>
                            callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.getHealthConnectOnboardingState(
                    new IGetHealthConnectOnboardingStateCallback.Stub() {
                        @Override
                        public void onResult(
                                HealthConnectOnboardingState healthConnectOnboardingState) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(healthConnectOnboardingState));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () -> callback.onError(exception.getHealthConnectException()));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if launching the intent returned by {@link
     * #createMatchmakingIntent(MatchmakingRequest)} with the same arguments will result in showing
     * at least one matching data source.
     *
     * <ul>
     *   <li>Returns {@code true} if the flow launched by the {@link Intent} from {@link
     *       #createMatchmakingIntent(MatchmakingRequest)} would display at least one matching data
     *       source, allowing the user to take action.
     *   <li>Returns {@code false} if the launched flow would immediately return {@link
     *       android.app.Activity#RESULT_CANCELED} because there are no relevant data sources to
     *       show.
     * </ul>
     *
     * @param request A non-null {@link MatchmakingRequest} that contains a set of {@link Record}
     *     classes. See description at {@link #createMatchmakingIntent(MatchmakingRequest)}.
     * @param executor A non-null {@link Executor} on which the {@code callback} will be invoked.
     * @param callback A non-null {@link OutcomeReceiver} to receive the result. The {@code
     *     onResult} method will be called with a boolean indicating if there are matching writing
     *     data sources to show. The {@code onError} method will be called if an error occurs.
     * @see #createMatchmakingIntent(MatchmakingRequest)
     */
    @FlaggedApi(FLAG_MATCHMAKING)
    public void isMatchmakingPossible(
            @NonNull MatchmakingRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<MatchmakingResponse, HealthConnectException> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.isMatchmakingPossible(
                    mContext.getAttributionSource(),
                    request,
                    new IIsMatchmakingPossibleCallback.Stub() {
                        @Override
                        @RequiresNoPermission
                        public void onResult(MatchmakingResponse response) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(response));
                        }

                        @Override
                        @RequiresNoPermission
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates an {@link Intent} to launch a Health Connect flow where users can:
     *
     * <ul>
     *   <li><b>Discover compatible data sources:</b> See other installed data sources that have not
     *       yet granted permissions to write some of the {@link Record} classes the calling app can
     *       read.
     *   <li><b>Grant missing permissions:</b> Easily grant these discovered data sources the
     *       necessary write permissions for health data record types that haven't been granted yet.
     *       Only {@link Record} types the calling package is permitted to read are being
     *       considered.
     * </ul>
     *
     * This flow helps users connect new data sources to Health Connect, by matching record types
     * readable by the calling package to appropriate writing data sources.
     *
     * <p><b>How to launch this Intent</b>This intent must be launched using the {@link
     * androidx.activity.result.ActivityResultLauncher} with an appropriate contract (e.g., {@link
     * androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult}), or the
     * {@link android.app.Activity#startActivityForResult(Intent, int)} API. It is not designed to
     * be used with a {@link android.app.Activity#startActivity(Intent)} call. The launched activity
     * may return a result code (e.g., {@link android.app.Activity#RESULT_OK} or {@link
     * android.app.Activity#RESULT_CANCELED}) indicating the user's interaction with the flow:
     *
     * <ul>
     *   <li>{@link android.app.Activity#RESULT_OK}: The user has successfully granted permissions
     *       to at least one application.
     *   <li>{@link android.app.Activity#RESULT_CANCELED}: The user has canceled the flow, either by
     *       closing the activity or by not granting any permissions. {@link
     *       android.app.Activity#RESULT_CANCELED} can also occur if the intent was launched in a
     *       discouraged way when no matching apps are available. This can be avoided by ensuring
     *       {@link #isMatchmakingPossible(MatchmakingRequest, Executor, OutcomeReceiver)} returns
     *       {@code true} before launching the intent.
     * </ul>
     *
     * <p>The launched flow will only show packages that have declared, but not yet been granted
     * write permissions (that have not been denied by the user twice) for at least one of the
     * relevant {@code recordTypes}, and for the relevant included/excluded data sources:
     *
     * <ul>
     *   <li><b>If {@code recordTypes} is not empty:</b> The launched screen will focus on data
     *       sources capable of writing data for at least one of the specified health {@code
     *       recordTypes}. The user can then grant write permissions to these discovered data
     *       sources specifically for these types. Record types the calling app does not have
     *       permission to read are considered ignored.
     *   <li><b>If {@code recordTypes} is empty:</b> The system first determines all health data
     *       record types for which the calling package has already been granted read permission.
     *       The launched flow will then display data sources capable of writing any of these record
     *       types, where the user can grant write permissions for these types.
     *   <li><b>If {@code includedDataSources} is not empty:</b> Only data sources whose package
     *       names are present in this set are considered for matchmaking. If a data source is an
     *       app, the calling app must have visibility of the package name (e.g. declared in the
     *       manifest inside {@code <queries>}). If a data source is a device, the calling app does
     *       not need to declare it in the manifest, but should identify available devices via
     *       {@link #getDeviceDataSources(Executor, OutcomeReceiver)}.
     *   <li><b>If {@code excludedDataSources} is not empty:</b> Data sources whose package names
     *       are present in this set are excluded from matchmaking. As with inclusion, app data
     *       sources must be visible to the calling app, while device data sources do not require
     *       manifest declaration.
     *   <li><b>If both are empty:</b> All compatible data sources are considered.
     * </ul>
     *
     * <p>Note: {@code includedDataSources} and {@code excludedDataSources} cannot both be non-empty
     * at the same time.
     *
     * @param request A {@link MatchmakingRequest} that contains:
     *     <ul>
     *       <li>A non-null set of {@link Record} classes. If non-empty, the flow focuses on these
     *           specific types. If empty, the flow focuses on types for which the calling package
     *           has permission to read.
     *       <li>A non-null set of {@link DataOrigin} to include in matchmaking. If non-empty, only
     *           specified data origins are considered. App data origins must be visible to the
     *           calling app (declared in {@code <queries>}). Device data origins (from {@link
     *           #getDeviceDataSources(Executor, OutcomeReceiver)}) do not require manifest
     *           declaration.
     *       <li>A non-null set of {@link DataOrigin} to exclude from matchmaking. If non-empty,
     *           specified data origins are EXCLUDED. App data origins must be visible to the
     *           calling app. Device data origins do not require manifest declaration.
     *     </ul>
     *
     * @return An {@link Intent} configured to show the flow for discovering and managing write
     *     permissions for matching data origins. This intent must be launched using {@link
     *     android.app.Activity#startActivityForResult(Intent, int)}.
     * @see #isMatchmakingPossible(MatchmakingRequest, Executor, OutcomeReceiver)
     */
    @FlaggedApi(FLAG_MATCHMAKING)
    @NonNull
    public Intent createMatchmakingIntent(@NonNull MatchmakingRequest request) {
        Objects.requireNonNull(request);
        Intent intent = new Intent(ACTION_MATCHMAKING);
        String[] recordTypeNames =
                request.getRecordTypes().stream()
                        .map(Class::getCanonicalName)
                        .distinct()
                        .toArray(String[]::new);
        intent.putExtra(EXTRA_RECORD_TYPES, recordTypeNames);
        if (Flags.deviceDataProvidersApi()) {
            String[] includedDataSources =
                    request.getIncludedDataSources().stream()
                            .map(DataOrigin::getPackageName)
                            .toArray(String[]::new);

            String[] excludedDataSources =
                    request.getExcludedDataSources().stream()
                            .map(DataOrigin::getPackageName)
                            .toArray(String[]::new);
            intent.putExtra(EXTRA_INCLUDED_DATA_SOURCES, includedDataSources);
            intent.putExtra(EXTRA_EXCLUDED_DATA_SOURCES, excludedDataSources);
        }
        return intent;
    }

    /**
     * Returns a map of package names to their associated permissions that should be displayed on
     * the screen launched by the intent from {@link #createMatchmakingIntent(MatchmakingRequest)}.
     * The data sources and their mapped permissions returned here are identical to what {@link
     * #isMatchmakingPossible(MatchmakingRequest, Executor, OutcomeReceiver)} identifies as matches.
     *
     * <p>The returned map will be empty if {@link #isMatchmakingPossible(MatchmakingRequest,
     * Executor, OutcomeReceiver)} would return {@code false} for the same parameters, and non-empty
     * if it would return {@code true}.
     *
     * @param request A non-null {@link MatchmakingRequest}.
     * @param executor The {@link Executor} on which to invoke the callback.
     * @param callback The callback which will receive the map of matching data sources to their
     *     matching permissions or the {@link HealthConnectException}.
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void getMatchingDataSources(
            @NonNull MatchmakingRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull
                    OutcomeReceiver<GetMatchingDataSourcesResponse, HealthConnectException>
                            callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.getMatchingDataSources(
                    mContext.getAttributionSource(),
                    request,
                    new IGetMatchingDataSourcesCallback.Stub() {
                        @Override
                        @RequiresNoPermission
                        public void onResult(GetMatchingDataSourcesResponse response) {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(response));
                        }

                        @Override
                        @RequiresNoPermission
                        public void onError(HealthConnectExceptionParcel exception) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () -> callback.onError(exception.getHealthConnectException()));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Records that a user has denied matchmaking for a given package.
     *
     * @param callingPackageName package name of the data source that initiated matchmaking.
     * @param deniedDataSources package name and permissions of the potentially matching data
     *     sources (apps and devices) that were denied.
     * @param executor The {@link Executor} on which to invoke the callback.
     * @param callback Callback to receive result of performing this operation.
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void recordMatchmakingDenial(
            @NonNull String callingPackageName,
            @NonNull Map<String, List<String>> deniedDataSources,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(callingPackageName);
        Objects.requireNonNull(deniedDataSources);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.recordMatchmakingDenial(
                    mContext.getAttributionSource(),
                    callingPackageName,
                    deniedDataSources,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables system/native tracking for the corresponding data type.
     *
     * <p>This allows the controller app to enable or disable native tracking.
     *
     * @throws RuntimeException for internal errors
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public void setTrackingEnabled(
            @NonNull Class<? extends Record> dataType,
            boolean enabled,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(dataType);
        try {
            mService.setTrackingEnabled(
                    // TODO(b/467338330): Send over RecordType (id) instead of preference string
                    getDataTypePrefKey(dataType),
                    enabled,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether system/native tracking for the corresponding data type is enabled.
     *
     * @throws RuntimeException for internal errors
     * @hide
     */
    @RequiresPermission(MANAGE_HEALTH_DATA_PERMISSION)
    public Map<Class<? extends Record>, Boolean> isTrackingEnabled(
            @NonNull List<Class<? extends Record>> dataTypes) {
        Objects.requireNonNull(dataTypes);
        try {
            Map<String, Class<? extends Record>> keyToDataTypeMap =
                    dataTypes.stream()
                            .collect(
                                    Collectors.toMap(
                                            // TODO(b/467338330): Send over RecordType (id) instead
                                            // of preference string
                                            HealthConnectManager::getDataTypePrefKey,
                                            dataType -> dataType));
            Map<String, Boolean> dataTypeTrackingStatusMap =
                    mService.isTrackingEnabled(new ArrayList<>(keyToDataTypeMap.keySet()));
            return dataTypeTrackingStatusMap.entrySet().stream()
                    .collect(
                            Collectors.toMap(
                                    entry -> keyToDataTypeMap.get(entry.getKey()),
                                    Map.Entry::getValue));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the user has enabled native tracking for {@code recordType} on the device
     * that Health Connect is currently running on.
     *
     * <p>Native tracking refers to the ability for Health Connect itself to provide device data of
     * the current device. While {@code false} is returned, users have explicitly disabled tracking
     * for that type and the current device stops contributing further data. Previously written
     * records remain unaffected by this status.
     *
     * <p>This check is a prerequisite for Device Data Providers to assume responsibility for
     * tracking a record type. A Device Data Provider must not populate device data using {@link
     * #insertDeviceRecords} or {@link #updateDeviceRecords} while users have explicitly disabled
     * tracking for that type.
     *
     * @param recordType the record type to query the tracking status for.
     * @return {@code true} if the user has enabled tracking for {@code recordType}.
     * @throws RuntimeException for internal errors
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.PROVIDE_HEALTH_CONNECT_DEVICE_DATA)
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public boolean hasUserEnabledTracking(@NonNull Class<? extends Record> recordType) {
        Objects.requireNonNull(recordType);
        try {
            // TODO(b/467338330): Send over RecordType (id) instead of preference string
            String dataTypePrefKey = getDataTypePrefKey(recordType);
            return mService.hasUserEnabledTracking(
                    mContext.getAttributionSource(), dataTypePrefKey);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the unique identifier of the device that Health Connect is currently running on.
     *
     * <p>To avoid persistent tracking, this identifier changes whenever the device reboots or the
     * user switches. However, this change does not affect data ownership. The system recognizes
     * that the new identifier belongs to the same device, ensuring that records inserted before a
     * reboot remain fully accessible using the current identifier.
     *
     * <p>This identifier can be used as the {@code deviceId} in {@link DeviceDataAdvertisement}
     * passed to {@link #advertiseDeviceDataSources} to represent the current device as a data
     * source. Subsequently, it can be used to read and manage data collected by the current device
     * (e.g. phone pedometer data) through dedicated device methods like {@link #readDeviceRecords}.
     *
     * @return unique identifier for the current device.
     * @throws RuntimeException for internal errors
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.PROVIDE_HEALTH_CONNECT_DEVICE_DATA)
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    @NonNull
    public String getCurrentDeviceId() {
        try {
            return mService.getCurrentDeviceId(mContext.getAttributionSource());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO(b/440056683): Add information on how the data here is displayed in the controller.
    /**
     * Notify Health Connect of devices that can provide data and the data types each of them can
     * provide.
     *
     * <p>A device data source refers to a specific device that can provide data for any data types.
     * A device data type source refers to a specific device + data type combination.
     *
     * <p>A {@link DeviceDataAdvertisement} should be provided for each device data source. Each
     * {@link DeviceDataAdvertisement} should contain a set of {@link DeviceDataTypeAdvertisement}s
     * representing each data type supported by the device and to be used as a device data type
     * source.
     *
     * <p>Reusing a {@code deviceId} across multiple {@link DeviceDataAdvertisement} with different
     * {@link Device.DeviceType} causes runtime exceptions. Reusing a {@code deviceId} across
     * multiple {@link DeviceDataAdvertisement} with the same {@link Device.DeviceType} merges them
     * to a single source in {@link #getDeviceDataSources}, where the latest advertisement
     * overwrites previous entries. This means that different devices using the same {@code
     * deviceId} when inserting records via {@link #insertDeviceRecords} attribute to the same
     * origin. While this allows grouping generic hardware, unique identifiers (such as MAC
     * addresses) are recommended to ensure distinct device attribution.
     *
     * <p>This method must be called before data can be written for the advertised device data type
     * source. This should be called as frequently as needed to accurately describe the current
     * devices and statuses of supported data types. Every advertisement must represent the latest
     * state of <b>all</b> device data sources and device data type sources. Every subsequent
     * advertisement will override the previous device data sources and device data type sources. If
     * a device data source or device data type source is omitted from a subsequent advertisement,
     * it will be deleted.
     *
     * @throws RuntimeException for internal errors
     * @hide
     */
    @SystemApi
    @RequiresPermission(PROVIDE_HEALTH_CONNECT_DEVICE_DATA)
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public void advertiseDeviceDataSources(
            @NonNull Set<DeviceDataAdvertisement> deviceDataAdvertisements,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(deviceDataAdvertisements);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.advertiseDeviceDataSources(
                    mContext.getAttributionSource(),
                    new ArrayList<>(deviceDataAdvertisements),
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Inserts {@code records} from a device data source into the Health Connect database.
     *
     * <p>Upon successful completion, {@link OutcomeReceiver#onResult} will be invoked for the
     * {@code callback}. The records returned in {@link InsertRecordsResponse} contain the unique
     * IDs of the input records. The values are in same order as {@code records}. In case of an
     * error or a permission failure in the Health Connect service, {@link OutcomeReceiver#onError}
     * will be invoked with a {@link HealthConnectException}.
     *
     * <p>The {@code deviceId} must match the one used in {@link DeviceDataAdvertisement} in the
     * latest call to {@link #advertiseDeviceDataSources}. A {@link Device} does not need to be
     * populated in the {@link Metadata} for a {@link Record} as it will automatically be populated
     * based on the {@link DeviceDataAdvertisement}.
     *
     * <p>If a native tracking capability on the device (such as steps) has been taken over, callers
     * must not insert records for that capability when {@link #hasUserEnabledTracking} returns
     * {@code false}.
     *
     * @param deviceId the identifier for the device that is the source of this data.
     * @param records list of records to be inserted.
     * @param executor executor on which to invoke the callback.
     * @param callback callback to receive the result of performing this operation.
     * @throws RuntimeException for internal errors
     * @hide
     */
    @SystemApi
    @RequiresPermission(PROVIDE_HEALTH_CONNECT_DEVICE_DATA)
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public void insertDeviceRecords(
            @NonNull String deviceId,
            @NonNull List<Record> records,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<InsertRecordsResponse, HealthConnectException> callback) {
        Objects.requireNonNull(records);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            List<RecordInternal<?>> recordInternals = new ArrayList<>(records.size());
            for (Record record : records) {
                // Unset any set ids for insert. This is to prevent random string ids from creating
                // illegal argument exception.
                record.getMetadata().setId("");
                recordInternals.add(
                        record.toRecordInternal().setPackageName(mContext.getPackageName()));
            }
            mService.insertDeviceRecords(
                    mContext.getAttributionSource(),
                    deviceId,
                    new RecordsParcel(recordInternals),
                    new IInsertRecordsResponseCallback.Stub() {
                        @Override
                        public void onResult(InsertRecordsResponseParcel parcel) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () ->
                                            callback.onResult(
                                                    new InsertRecordsResponse(
                                                            toExternalRecordsWithUuids(
                                                                    recordInternals,
                                                                    parcel.getUids()))));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            returnError(executor, exception, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates records previously inserted using {@link #insertDeviceRecords}.
     *
     * <p>Records are keyed by their unique identifier ({@link Metadata#getId} or {@link
     * Metadata#getClientRecordId}). The {@link Device} in record's {@link Metadata} is ignored and
     * not updated.
     *
     * <p>In case of an error or a permission failure in the Health Connect service, {@link
     * OutcomeReceiver#onError} will be invoked with a {@link HealthConnectException}.
     *
     * <p>In case the input record to be updated does not exist in the database or the caller is not
     * the owner of the record, {@link OutcomeReceiver#onError} will be invoked with {@link
     * HealthConnectException#ERROR_INVALID_ARGUMENT}.
     *
     * <p>If a native tracking capability on the device (such as steps) has been taken over, callers
     * must not update records for that capability when {@link #hasUserEnabledTracking} returns
     * {@code false}.
     *
     * @param deviceId the identifier for the device that is the source of this data.
     * @param records list of records to be updated.
     * @param executor executor on which to invoke the callback.
     * @param callback callback to receive the result of performing this operation.
     * @throws IllegalArgumentException if at least one of the records is missing both {@link
     *     Metadata#getId} and {@link Metadata#getClientRecordId}
     * @throws RuntimeException for internal errors
     * @hide
     */
    @SystemApi
    @RequiresPermission(PROVIDE_HEALTH_CONNECT_DEVICE_DATA)
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public void updateDeviceRecords(
            @NonNull String deviceId,
            @NonNull List<Record> records,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(records);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            List<RecordInternal<?>> recordInternals = new ArrayList<>(records.size());
            for (Record record : records) {
                recordInternals.add(record.toRecordInternal());
            }
            verifyIds(recordInternals);

            mService.updateDeviceRecords(
                    mContext.getAttributionSource(),
                    deviceId,
                    new RecordsParcel(recordInternals),
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () -> callback.onError(exception.getHealthConnectException()));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reads records previously inserted using {@link #insertDeviceRecords}.
     *
     * <p>This method is strictly scoped to records inserted by the calling device data provider.
     * Records inserted by other applications or device data providers are not accessible via this
     * API.
     *
     * <p>The behavior of the read operation depends on the type of {@link ReadRecordsRequest}
     * provided:
     *
     * <p>A request using {@link ReadRecordsRequestUsingIds} will filter device records using their
     * record IDs or the client IDs. IDs that the device data provider has not inserted will be
     * ignored.
     *
     * <p>A request using {@link ReadRecordsRequestUsingFilters} will filter device records with the
     * request's given {@code deviceId}. The {@code deviceId} must match the one used in {@link
     * DeviceDataAdvertisement} in the latest call to {@link #advertiseDeviceDataSources}. If {@code
     * deviceId} is not set, records from all devices advertised by the caller will be returned.
     *
     * @param <T> the type of {@link Record} being requested.
     * @param request the read request based on {@link ReadRecordsRequest}.
     * @param executor the {@link Executor} on which the {@code callback} will be invoked.
     * @param callback the callback to receive the {@link ReadRecordsResponse} on success or a
     *     {@link HealthConnectException} on failure.
     * @throws IllegalArgumentException if {@code request} contains {@link DataOrigin}s.
     * @throws RuntimeException for internal errors.
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    @RequiresPermission(PROVIDE_HEALTH_CONNECT_DEVICE_DATA)
    public <T extends Record> void readDeviceRecords(
            @NonNull ReadRecordsRequest<T> request,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        if (request instanceof ReadRecordsRequestUsingFilters<?> readRequest) {
            if (!readRequest.getDataOrigins().isEmpty()) {
                throw new IllegalArgumentException(
                        "DataOrigins must be empty, use device id instead.");
            }
        }
        try {
            mService.readDeviceRecords(
                    mContext.getAttributionSource(),
                    request.toReadRecordsRequestParcel(),
                    getReadCallback(executor, callback));
        } catch (RemoteException remoteException) {
            remoteException.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes records previously inserted using {@link #insertDeviceRecords}.
     *
     * <p>In case of an error or a permission failure in the Health Connect service, {@link
     * OutcomeReceiver#onError} will be invoked with a {@link HealthConnectException}.
     *
     * <p>Deletions are performed in a transaction i.e. either all will be deleted or none.
     *
     * @param deviceId the identifier for the device that is the source of this data.
     * @param recordType the type of record to be deleted.
     * @param timeRangeFilter the time range filter to delete records.
     * @param executor executor on which to invoke the callback.
     * @param callback callback to receive the result of performing this operation.
     * @throws RuntimeException for internal errors
     * @hide
     */
    @SystemApi
    @RequiresPermission(PROVIDE_HEALTH_CONNECT_DEVICE_DATA)
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public void deleteDeviceRecords(
            @NonNull String deviceId,
            @NonNull Class<? extends Record> recordType,
            @NonNull TimeRangeFilter timeRangeFilter,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(recordType);
        Objects.requireNonNull(timeRangeFilter);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.deleteDeviceRecords(
                    mContext.getAttributionSource(),
                    deviceId,
                    new DeleteUsingFiltersRequestParcel(
                            new DeleteUsingFiltersRequest.Builder()
                                    .addRecordType(recordType)
                                    .setTimeRangeFilter(timeRangeFilter)
                                    .build()),
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () -> callback.onError(exception.getHealthConnectException()));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes records previously inserted using {@link #insertDeviceRecords}.
     *
     * <p>In case of an error or a permission failure in the Health Connect service, {@link
     * OutcomeReceiver#onError} will be invoked with a {@link HealthConnectException}.
     *
     * <p>Deletions are performed in a transaction i.e. either all will be deleted or none.
     *
     * @param deviceId the identifier for the device that is the source of this data.
     * @param recordIds the list of record IDs to be deleted.
     * @param executor executor on which to invoke the callback.
     * @param callback callback to receive the result of performing this operation.
     * @throws RuntimeException for internal errors
     * @hide
     */
    @SystemApi
    @RequiresPermission(PROVIDE_HEALTH_CONNECT_DEVICE_DATA)
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public void deleteDeviceRecords(
            @NonNull String deviceId,
            @NonNull List<RecordIdFilter> recordIds,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, HealthConnectException> callback) {
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(recordIds);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        if (recordIds.isEmpty()) {
            throw new IllegalArgumentException("recordIds cannot be empty");
        }

        try {
            DeleteUsingFiltersRequestParcel parcel =
                    new DeleteUsingFiltersRequestParcel(
                            new RecordIdFiltersParcel(recordIds), mContext.getPackageName());
            // Overwrite the package name filters, as by default, RecordIdFiltersParcel will have
            // this set to the calling app itself. This would be incorrect, as the device has its
            // own package name that is different to the calling device data provider package.
            parcel.setPackageNameFilters(List.of());

            mService.deleteDeviceRecords(
                    mContext.getAttributionSource(),
                    deviceId,
                    parcel,
                    new IEmptyResponseCallback.Stub() {
                        @Override
                        public void onResult() {
                            Binder.clearCallingIdentity();
                            executor.execute(() -> callback.onResult(null));
                        }

                        @Override
                        public void onError(HealthConnectExceptionParcel exception) {
                            Binder.clearCallingIdentity();
                            executor.execute(
                                    () -> callback.onError(exception.getHealthConnectException()));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static String getDataTypePrefKey(@NonNull Class<? extends Record> dataType) {
        return TRACKING_PREFERENCE_PREFIX
                + dataType.getAnnotation(Identifier.class).recordIdentifier();
    }

    private void verifyIds(List<RecordInternal<?>> recordInternals) {
        for (RecordInternal<?> recordInternal : recordInternals) {
            if ((recordInternal.getClientRecordId() == null
                            || recordInternal.getClientRecordId().isEmpty())
                    && recordInternal.getUuid() == null) {
                throw new IllegalArgumentException(
                        "At least one of the records is missing either ClientRecordID"
                                + " or UUID. RecordType of the input: "
                                + recordInternal.getRecordType());
            }
        }
    }
}

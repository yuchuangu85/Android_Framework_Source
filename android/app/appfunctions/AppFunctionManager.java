/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.appfunctions;

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.MANAGE_APP_FUNCTION_ACCESS;
import static android.app.appfunctions.AppFunctionException.ERROR_DISABLED;
import static android.app.appfunctions.AppFunctionException.ERROR_SYSTEM_ERROR;
import static android.app.appfunctions.AppFunctionManagerHelper.buildCancellationSignal;
import static android.app.appfunctions.AppFunctionManagerHelper.executionExceptionToErrorCode;
import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_APP_FUNCTION_MANAGER;
import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2;
import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS;
import static android.permission.flags.Flags.FLAG_APP_FUNCTION_ACCESS_UI_ENABLED;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.app.Activity;
import android.app.Service;
import android.app.appfunctions.AppFunctionManagerHelper.AppFunctionNotFoundException;
import android.app.appsearch.AppSearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.SignedPackage;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.SystemClock;
import android.permission.flags.Flags;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Provides access to App Functions.
 *
 * <p>See {@link android.app.appfunctions} for a comprehensive overview of App Functions.
 */
@FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
@SystemService(Context.APP_FUNCTION_SERVICE)
public final class AppFunctionManager {
    private static final String TAG = "AppFunctionManager";

    /**
     * Activity action: Launches a UI that shows list of all agents and provides management of App
     * Function access of those agents.
     *
     * <p>Input: Nothing.
     *
     * <p>Output: Nothing.
     */
    @FlaggedApi(FLAG_APP_FUNCTION_ACCESS_UI_ENABLED)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_APP_FUNCTION_ACCESS =
            "android.app.appfunctions.action.MANAGE_APP_FUNCTION_ACCESS";

    /**
     * Activity action: Launches a UI that shows a list of all targets that the specified agent
     * package can access, and provides management of App Function access of those targets.
     *
     * <p>Input: {@link android.content.Intent#EXTRA_PACKAGE_NAME} specifies the package whose
     * access will be managed by the launched UI.
     *
     * <p>Output: Nothing.
     */
    @FlaggedApi(FLAG_APP_FUNCTION_ACCESS_UI_ENABLED)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_AGENT_APP_FUNCTION_ACCESS =
            "android.app.appfunctions.action.MANAGE_AGENT_APP_FUNCTION_ACCESS";

    /**
     * Activity action: Launches a UI that shows list of all agents for a specific target and
     * provides management of App Function access by those agents.
     *
     * <p>Input: {@link android.content.Intent#EXTRA_PACKAGE_NAME} specifies the package whose
     * access will be managed by the launched UI.
     *
     * <p>Output: Nothing.
     */
    @FlaggedApi(FLAG_APP_FUNCTION_ACCESS_UI_ENABLED)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_TARGET_APP_FUNCTION_ACCESS =
            "android.app.appfunctions.action.MANAGE_TARGET_APP_FUNCTION_ACCESS";

    /**
     * Activity action: Launches a UI for an agent to request App Function access of a target.
     *
     * <p>Input: {@link android.content.Intent#EXTRA_PACKAGE_NAME} specifies the package for which
     * the calling agent is requesting access of.
     *
     * <p>Output: Nothing.
     *
     * @hide
     */
    @FlaggedApi(FLAG_APP_FUNCTION_ACCESS_UI_ENABLED)
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_APP_FUNCTION_ACCESS =
            "android.app.appfunctions.action.REQUEST_APP_FUNCTION_ACCESS";

    /**
     * The default state of the app function.
     *
     * <p>To reset the enabled state to the default value, call {@link #setAppFunctionEnabled} with
     * this value.
     */
    public static final int APP_FUNCTION_STATE_DEFAULT = 0;

    /**
     * The app function is enabled.
     *
     * <p>To enable an app function, call {@link #setAppFunctionEnabled} with this value.
     */
    public static final int APP_FUNCTION_STATE_ENABLED = 1;

    /**
     * The app function is disabled.
     *
     * <p>To disable an app function, call {@link #setAppFunctionEnabled} with this value.
     */
    public static final int APP_FUNCTION_STATE_DISABLED = 2;

    /**
     * App Function access request state indicating that the access has been granted for a
     * particular agent and target
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public static final int ACCESS_REQUEST_STATE_GRANTED = 0;

    /**
     * App Function access request state indicating that the access has been denied for a particular
     * agent and target
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public static final int ACCESS_REQUEST_STATE_DENIED = 1;

    /**
     * App Function access request state indicating that the access is not able to be granted for a
     * particular agent and target, due to the agent not being granted the {@link
     * Manifest.permission#EXECUTE_APP_FUNCTIONS} permission, or the target not having an {@link
     * AppFunctionService}, or the agent not being in the device allowlist, or one or both apps not
     * being installed.
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public static final int ACCESS_REQUEST_STATE_UNREQUESTABLE = 2;

    /** @hide */
    @IntDef(
            prefix = {"ACCESS_REQUEST_STATE_"},
            value = {
                ACCESS_REQUEST_STATE_DENIED,
                ACCESS_REQUEST_STATE_GRANTED,
                ACCESS_REQUEST_STATE_UNREQUESTABLE
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface AppFunctionAccessState {}

    /**
     * A flag indicating the app function access state has been pregranted by the system
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @SystemApi
    public static final int ACCESS_FLAG_PREGRANTED = 1;

    /**
     * A flag indicating the app function access state has been granted as part of a system upgrade
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @SystemApi
    public static final int ACCESS_FLAG_UPGRADE_GRANTED = 1 << 1;

    /**
     * A flag indicating the user granted the app function access state through UI
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @SystemApi
    public static final int ACCESS_FLAG_USER_GRANTED = 1 << 2;

    /**
     * A flag indicating the app function access state has been denied by the user. If set,
     * overrides the {@link #ACCESS_FLAG_PREGRANTED} flag.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @SystemApi
    public static final int ACCESS_FLAG_USER_DENIED = 1 << 3;

    /**
     * A flag indicating the app function access is granted through a mechanism not tied to any
     * other flag (e.g. ADB)
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @SystemApi
    public static final int ACCESS_FLAG_OTHER_GRANTED = 1 << 4;

    /**
     * A flag indicating the app function access state has been denied by some other mechanism not
     * covered by another flag (e.g. ADB, self revoke)
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @SystemApi
    public static final int ACCESS_FLAG_OTHER_DENIED = 1 << 5;

    /**
     * All USER flags
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @TestApi
    public static final int ACCESS_FLAG_MASK_USER =
            ACCESS_FLAG_USER_GRANTED | ACCESS_FLAG_USER_DENIED;

    /**
     * All OTHER flags
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @TestApi
    public static final int ACCESS_FLAG_MASK_OTHER =
            ACCESS_FLAG_OTHER_GRANTED | ACCESS_FLAG_OTHER_DENIED;

    /**
     * All access flags
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @TestApi
    public static final int ACCESS_FLAG_MASK_ALL =
            ACCESS_FLAG_PREGRANTED
                    | ACCESS_FLAG_UPGRADE_GRANTED
                    | ACCESS_FLAG_OTHER_GRANTED
                    | ACCESS_FLAG_OTHER_DENIED
                    | ACCESS_FLAG_USER_GRANTED
                    | ACCESS_FLAG_USER_DENIED;

    @IntDef(
            prefix = {"ACCESS_FLAG_"},
            flag = true,
            value = {
                ACCESS_FLAG_PREGRANTED,
                ACCESS_FLAG_UPGRADE_GRANTED,
                ACCESS_FLAG_OTHER_GRANTED,
                ACCESS_FLAG_OTHER_DENIED,
                ACCESS_FLAG_USER_GRANTED,
                ACCESS_FLAG_USER_DENIED
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface AppFunctionAccessFlags {}

    private final IAppFunctionManager mService;
    private final Context mContext;

    private final ArrayMap<
                    OnAppFunctionAccessChangedListener, OnAppFunctionAccessChangeListenerDelegate>
            mListeners = new ArrayMap<>();

    /**
     * The enabled state of the app function.
     *
     * @hide
     */
    @IntDef(
            prefix = {"APP_FUNCTION_STATE_"},
            value = {
                APP_FUNCTION_STATE_DEFAULT,
                APP_FUNCTION_STATE_ENABLED,
                APP_FUNCTION_STATE_DISABLED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnabledState {}

    private final AppFunctionRegistry mRegistry;

    /**
     * Creates an instance.
     *
     * @param service An interface to the backing service.
     * @param context A {@link Context}.
     * @hide
     */
    public AppFunctionManager(IAppFunctionManager service, Context context) {
        mService = service;
        mContext = context;
        mRegistry = new AppFunctionRegistry(mContext);
    }

    /**
     * Executes the app function.
     *
     * <p>Applications can execute functions they define. To execute functions defined in another
     * component, the caller must have the {@link Manifest.permission#EXECUTE_APP_FUNCTIONS} or
     * {@link Manifest.permission#EXECUTE_APP_FUNCTIONS_SYSTEM} permissions.
     *
     * <p>A function can only be executed while its {@link AppFunctionState#isEnabled} is true.
     *
     * <p>See {@link AppFunctionException} for possible failures.
     *
     * @param request the request to execute the app function
     * @param executor the executor to run the callback
     * @param cancellationSignal the cancellation signal to cancel the execution.
     * @param callback the callback to receive the function execution result or error.
     */
    @FlaggedApi(FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS,
                Manifest.permission.EXECUTE_APP_FUNCTIONS_SYSTEM
            },
            conditional = true)
    @UserHandleAware
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        ExecuteAppFunctionAidlRequest aidlRequest =
                new ExecuteAppFunctionAidlRequest(
                        request,
                        mContext.getUser(),
                        mContext.getPackageName(),
                        /* requestTime= */ SystemClock.elapsedRealtime(),
                        /* requestWallTime= */ System.currentTimeMillis());

        try {
            ICancellationSignal cancellationTransport =
                    mService.executeAppFunction(
                            aidlRequest,
                            new IExecuteAppFunctionCallback.Stub() {
                                @Override
                                public void onSuccess(ExecuteAppFunctionResponse result) {
                                    try {
                                        executor.execute(
                                                () -> {
                                                    callback.onResult(result);
                                                });
                                    } catch (RuntimeException e) {
                                        // Ideally shouldn't happen since errors are wrapped into
                                        // the response, but we catch it here for additional safety.
                                        executor.execute(
                                                () ->
                                                        callback.onError(
                                                                new AppFunctionException(
                                                                        ERROR_SYSTEM_ERROR,
                                                                        e.getMessage())));
                                    }
                                }

                                @Override
                                public void onError(AppFunctionException exception) {
                                    executor.execute(() -> callback.onError(exception));
                                }
                            });
            if (cancellationTransport != null) {
                cancellationSignal.setRemote(cancellationTransport);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the runtime state of the specified app functions.
     *
     * <p>This includes runtime-changing properties such as whether the functions are currently
     * enabled or disabled. Functions that do not exist or are not visible to the calling
     * application will be silently omitted from the result list.
     *
     * <p>This method follows the same permission rules as {@link #searchAppFunctions}.
     *
     * <p>See {@link #getAppFunctionActivityStates} for retrieving the states of app functions
     * associated with a specific activity.
     *
     * <p>See {@link #searchAppFunctions} on how to retrieve the {@link AppFunctionMetadata} of app
     * functions.
     *
     * <p>See {@link #observeAppFunctions} for observing changes to app functions' {@link
     * AppFunctionMetadata} and {@link AppFunctionState}s.
     *
     * @param appFunctionNames The names of the app functions to request the state for.
     * @param executor The executor to run the callback.
     * @param callback The callback to receive the function state result.
     */
    @FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS,
                Manifest.permission.DISCOVER_APP_FUNCTIONS,
                Manifest.permission.EXECUTE_APP_FUNCTIONS_SYSTEM,
            },
            conditional = true)
    @UserHandleAware
    public void getAppFunctionStates(
            @NonNull List<AppFunctionName> appFunctionNames,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<List<AppFunctionState>, Exception> callback) {
        Objects.requireNonNull(appFunctionNames);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getAppFunctionStates(
                    appFunctionNames,
                    mContext.getPackageName(),
                    mContext.getUserId(),
                    new IGetAppFunctionStatesCallback.Stub() {
                        @Override
                        public void onSuccess(AppFunctionStateList states) {
                            executor.execute(
                                    () -> {
                                        callback.onResult(states.getList());
                                    });
                        }

                        @Override
                        public void onError(ParcelableException exception) {
                            executor.execute(
                                    () -> {
                                        if (exception.getCause() == null) {
                                            callback.onError(
                                                    new RuntimeException(
                                                            "Unknown remote failure."));
                                        } else {
                                            callback.onError(
                                                    new RuntimeException(exception.getCause()));
                                        }
                                    });
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the registered app functions for the specified activities.
     *
     * <p>Each {@link AppFunctionActivityState} contains the set of registered {@link
     * AppFunctionName}s associated with a requested {@link AppFunctionActivityId}.
     *
     * <p>Functions that do not exist or are not visible to the calling application will be silently
     * omitted from the result. Requested activities that have no registered functions will be
     * omitted from the result.
     *
     * <p>See {@link AppFunctionActivityId} for potential usages, including conversion from {@link
     * android.service.voice.VoiceInteractionSession.ActivityId}.
     *
     * <p>This method follows the same permission rules as {@link #searchAppFunctions}.
     *
     * <p>See {@link #getAppFunctionStates} for retrieving the runtime state of app functions based
     * on their names.
     *
     * <p>See {@link #searchAppFunctions} on how to retrieve the {@link AppFunctionMetadata} of app
     * functions.
     *
     * <p>See {@link #observeAppFunctions} for observing changes to app functions' {@link
     * AppFunctionMetadata} and {@link AppFunctionState}s.
     *
     * @param activityIds The set of activity IDs to retrieve function states for.
     * @param executor The executor to run the callback.
     * @param callback The callback to receive the list of activity states.
     * @see android.service.voice.VoiceInteractionSession#getAppFunctionActivityId
     */
    @FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS,
                Manifest.permission.DISCOVER_APP_FUNCTIONS,
                Manifest.permission.EXECUTE_APP_FUNCTIONS_SYSTEM,
            },
            conditional = true)
    @UserHandleAware
    public void getAppFunctionActivityStates(
            @NonNull Set<AppFunctionActivityId> activityIds,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<List<AppFunctionActivityState>, Exception> callback) {
        Objects.requireNonNull(activityIds);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.getAppFunctionActivityStates(
                    new ArrayList<>(activityIds),
                    mContext.getPackageName(),
                    mContext.getUserId(),
                    new IGetAppFunctionActivityStatesCallback.Stub() {
                        @Override
                        public void onSuccess(AppFunctionActivityStateList states) {
                            executor.execute(
                                    () -> {
                                        callback.onResult(states.getList());
                                    });
                        }

                        @Override
                        public void onError(ParcelableException exception) {
                            executor.execute(
                                    () -> {
                                        if (exception.getCause() == null) {
                                            callback.onError(
                                                    new RuntimeException(
                                                            "Unknown remote failure."));
                                        } else {
                                            callback.onError(
                                                    new RuntimeException(exception.getCause()));
                                        }
                                    });
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Searches app function {@link AppFunctionMetadata}s.
     *
     * <p>Note that the state is not guaranteed to be the latest, as metadata can change between
     * request and execute times when apps are updated.
     *
     * <p>The calling app can search for:
     *
     * <ul>
     *   <li>Functions in its own package (no permission required).
     *   <li>When holding the {@link Manifest.permission#EXECUTE_APP_FUNCTIONS} or {@link
     *       Manifest.permission#DISCOVER_APP_FUNCTIONS} or {@link
     *       Manifest.permission#EXECUTE_APP_FUNCTIONS_SYSTEM} permission - functions in other
     *       packages that it is allowed to query via {@link
     *       android.content.pm.PackageManager#canPackageQuery}.
     * </ul>
     *
     * <p>See {@link #getAppFunctionStates} and {@link #getAppFunctionActivityStates} on how to
     * retrieve the runtime state of app functions.
     *
     * <p>See {@link #observeAppFunctions} for observing changes to app functions' {@link
     * AppFunctionMetadata} and {@link AppFunctionState}s.
     *
     * @param searchSpec The spec of app functions to search for.
     * @param executor The executor to run the callback.
     * @param callback The callback to receive the search results.
     */
    @FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS,
                Manifest.permission.DISCOVER_APP_FUNCTIONS,
                Manifest.permission.EXECUTE_APP_FUNCTIONS_SYSTEM,
            },
            conditional = true)
    @UserHandleAware
    public void searchAppFunctions(
            @NonNull AppFunctionSearchSpec searchSpec,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<List<AppFunctionMetadata>, Exception> callback) {
        Objects.requireNonNull(searchSpec);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        AppFunctionAidlSearchSpec aidlSearchSpec =
                new AppFunctionAidlSearchSpec(
                        mContext.getPackageName(), searchSpec, mContext.getUserId());

        try {
            mService.searchAppFunctions(
                    aidlSearchSpec,
                    new ISearchAppFunctionsCallback.Stub() {
                        @Override
                        public void onSuccess(IAppFunctionSearchResults results) {
                            new SearchResultsProcessor(results, executor, callback).fetchNext();
                        }

                        @Override
                        public void onError(ParcelableException exception) {
                            executor.execute(
                                    () -> {
                                        if (exception.getCause() == null) {
                                            callback.onError(
                                                    new RuntimeException(
                                                            "Unknown remote failure."));
                                        } else {
                                            callback.onError(
                                                    new RuntimeException(exception.getCause()));
                                        }
                                    });
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers an observer to monitor changes to app functions within packages that the caller can
     * query.
     *
     * <p>The caller should retain a reference to the returned {@link AppFunctionObservation}, and
     * call {@link AppFunctionObservation#cancel} when observation is no longer required.
     *
     * <p>The callback is only triggered by changes after its registration. Any changes that occur
     * before the registration are not reported.
     *
     * <p>An example usage flow is:
     *
     * <ol>
     *   <li>Call {@link #observeAppFunctions}, to start monitoring app function changes, using the
     *       {@link AppFunctionObserver}.
     *   <li>Call {@link #searchAppFunctions} and {@link #getAppFunctionStates} to get the initial
     *       list of app functions and their states.
     *   <li>In {@link AppFunctionObserver#onAppFunctionMetadataChanged}, call {@link
     *       #searchAppFunctions} with a {@link AppFunctionSearchSpec} that matches the changed
     *       packages to get the updated metadata.
     *   <li>In {@link AppFunctionObserver#onAppFunctionStatesChanged}, call {@link
     *       #getAppFunctionStates} with the list of {@link AppFunctionName}s that matches the
     *       changed functions to get the updated states.
     *       <p>Note that this is guaranteed to trigger after {@link
     *       AppFunctionObserver#onAppFunctionMetadataChanged} for new functions or functions that
     *       also changed states, so there's no need to call {@link #getAppFunctionStates} in {@link
     *       AppFunctionObserver#onAppFunctionMetadataChanged}.
     * </ol>
     *
     * @param executor the executor to run the {@link AppFunctionObserver} callbacks.
     * @param appFunctionObserver the observer to receive updates to registered app functions.
     * @return An {@link AppFunctionObservation} used to cancel this observation.
     */
    @FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS,
                Manifest.permission.DISCOVER_APP_FUNCTIONS,
                Manifest.permission.EXECUTE_APP_FUNCTIONS_SYSTEM,
            },
            conditional = true)
    @UserHandleAware
    @NonNull
    public AppFunctionObservation observeAppFunctions(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AppFunctionObserver appFunctionObserver) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(appFunctionObserver);

        AppFunctionAidlSearchSpec aidlSearchSpec =
                new AppFunctionAidlSearchSpec(
                        mContext.getPackageName(),
                        new AppFunctionSearchSpec.Builder().build(),
                        mContext.getUserId());

        IObserveAppFunctionChangesCallback internalCallback =
                new IObserveAppFunctionChangesCallback.Stub() {
                    @Override
                    public void onPackagesChanged(List<String> packageNames) {
                        executor.execute(
                                () ->
                                        appFunctionObserver.onAppFunctionMetadataChanged(
                                                new ArraySet<>(packageNames)));
                    }

                    @Override
                    public void onAppFunctionStatesChanged(
                            List<AppFunctionName> changedFunctionNames) {
                        executor.execute(
                                () ->
                                        appFunctionObserver.onAppFunctionStatesChanged(
                                                new ArraySet<>(changedFunctionNames)));
                    }
                };

        final AppFunctionObservation observation =
                () -> {
                    try {
                        mService.unregisterAppFunctionObserver(
                                mContext.getPackageName(), mContext.getUser(), internalCallback);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                };

        try {
            mService.observeAppFunctions(aidlSearchSpec, internalCallback);
            return observation;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves whether an app function is enabled (allows execution).
     *
     * <p>This is equivalent to calling {@link #getAppFunctionStates} and checking the {@link
     * AppFunctionState#isEnabled} property of the result. Consider using {@link
     * #getAppFunctionStates} to get the full state of the app function.
     *
     * <p>If the operation fails, the callback's {@link OutcomeReceiver#onError} is called with
     * errors:
     *
     * <ul>
     *   <li>{@link IllegalArgumentException}, if the function is not found or the caller does not
     *       have access to it.
     * </ul>
     *
     * @param functionIdentifier the identifier of the app function to check (unique within the
     *     target package)
     * @param targetPackage the package name of the app function's owner
     * @param executor the executor to run the request
     * @param callback the callback to receive the function enabled check result
     * @see AppFunctionState#isEnabled
     */
    @FlaggedApi(FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS,
                Manifest.permission.DISCOVER_APP_FUNCTIONS,
                Manifest.permission.EXECUTE_APP_FUNCTIONS_SYSTEM,
            },
            conditional = true)
    public void isAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @NonNull String targetPackage,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        isAppFunctionEnabledInternal(functionIdentifier, targetPackage, executor, callback);
    }

    // TODO: b/483628788 - Explain XSD.
    /**
     * Registers a runtime implementation for an app function, that can be executed using {@link
     * #executeAppFunction}.
     *
     * <p>{@link #executeAppFunction} targeting an app function provided by this method will trigger
     * the {@link AppFunction#onExecute} method of the provided implementation, as long as the
     * process registering it is not frozen, and the {@link Context} registering it is not destroyed
     * (at which point the registration will be removed).
     *
     * <p>You must declare the app function in your {@code AndroidManifest.xml} using an
     * application-level {@code <property>} named {@code android.app.appfunctions}. See {@link
     * AppFunctionMetadata} for details on the XML schema ({@code your_app_functions.xml} in the
     * example below).
     *
     * <p><b>Example manifest declaration:</b>
     *
     * <pre>{@code
     * <application ...>
     *   <property
     *       android:name="android.app.appfunctions.v2"
     *       android:value="your_app_functions.xml" />
     *   ...
     * </application>
     * }</pre>
     *
     * <p>Function implementations can only be registered from {@link android.app.Activity} or
     * {@link android.app.Service} contexts. If registering from an {@link android.app.Activity},
     * strongly consider {@link AppFunctionMetadata#SCOPE_ACTIVITY} for your function definition.
     *
     * <p>The {@code functionIdentifier} must correspond to an app function declared in your app's
     * application-level XML assets. If the identifier is not found, this method will throw an
     * {@link IllegalArgumentException}. Attempting to register a duplicate function based on the
     * rules of {@link AppFunctionMetadata#getScope} will throw an {@link IllegalStateException}.
     *
     * <p>To register multiple functions at once, consider using {@link #registerAppFunctions} as a
     * more efficient alternative.
     *
     * <p>The system holds a strong reference to the provided {@link AppFunction} implementation as
     * long as it is registered. To prevent memory leaks and ensure the system is aware that the
     * function is no longer available, you must explicitly call {@link
     * AppFunctionRegistration#unregister} when the function is no longer relevant (e.g., in {@link
     * android.app.Activity#onStop} or before {@link android.app.Service#stopForeground}).
     *
     * @param functionIdentifier The unique identifier for the function, which must match an entry
     *     in the app's XML resource declarations.
     * @param executor The {@link Executor} on which the function will be invoked.
     * @param appFunction The {@link AppFunction} implementation to be executed when the function is
     *     triggered.
     * @return A {@link AppFunctionRegistration} object that can be used to unregister the function.
     * @throws IllegalStateException if a duplicate function is already registered (see {@link
     *     AppFunctionMetadata#getScope}), or if not called from {@link android.app.Activity} or
     *     {@link android.app.Service} contexts.
     * @throws IllegalArgumentException if the provided {@code functionIdentifier} is not declared
     *     in the app's application-level XML resources or if an activity-scoped function is
     *     registered from a non-Activity context.
     */
    @NonNull
    @FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    public AppFunctionRegistration registerAppFunction(
            @NonNull String functionIdentifier,
            @NonNull Executor executor,
            @NonNull AppFunction appFunction) {
        return mRegistry.register(
                List.of(new RegisterAppFunctionRequest(functionIdentifier, executor, appFunction)));
    }

    /**
     * Registers several {@link AppFunction} implementations at once, sharing a single lifecycle.
     *
     * <p>This is a more efficient alternative to calling {@link #registerAppFunction} multiple
     * times.
     *
     * <h3>Behavior and Lifecycle</h3>
     *
     * <p>Each function registered through this method follows the same execution and lifecycle
     * rules as those registered with {@link #registerAppFunction}.
     *
     * <h3>Batch Operation and Atomicity</h3>
     *
     * <p>The registration is atomic: either all functions in the provided list are registered
     * successfully, or none are. If any function in the list fails validation (e.g., its is already
     * registered or not declared in the manifest), this method will throw an exception, and no
     * functions from the batch will be registered. Each function in the request follows the scoping
     * rules declared in the app's XML resources.
     *
     * <p>A single {@link AppFunctionRegistration} object is returned, which can be used to
     * unregister the entire batch of functions with one call.
     *
     * @param requests A list of {@link RegisterAppFunctionRequest} objects, each specifying a
     *     function to be registered.
     * @return A single {@link AppFunctionRegistration} object that can be used to unregister all
     *     the functions in the batch with one call.
     * @throws IllegalStateException if any function in the {@code requests} list is already
     *     registered by this app.
     * @throws IllegalArgumentException if any {@link
     *     RegisterAppFunctionRequest#getFunctionIdentifier} is not declared in the app's
     *     application-level XML assets or the {@code requests} list is empty.
     */
    @NonNull
    @FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    public AppFunctionRegistration registerAppFunctions(
            @NonNull List<RegisterAppFunctionRequest> requests) {
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("No functions provided.");
        }
        return mRegistry.register(requests);
    }

    /**
     * Retrieves whether an app function owned by the calling package is enabled (allows execution).
     *
     * <p>Same as {@link #isAppFunctionEnabled(String, String, Executor, OutcomeReceiver)} but only
     * for checking functions owned by the calling package.
     *
     * <p>This is equivalent to calling {@link #getAppFunctionStates} and checking the {@link
     * AppFunctionState#isEnabled} property of the result. Consider using {@link
     * #getAppFunctionStates} to get the full state of the app function.
     *
     * @param functionIdentifier the identifier of the app function to check (unique within the
     *     target package)
     * @param executor the executor to run the request
     * @param callback the callback to receive the function enabled check result
     */
    public void isAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        isAppFunctionEnabledInternal(
                functionIdentifier, mContext.getPackageName(), executor, callback);
    }

    /**
     * Sets the enabled state of the app function owned by the calling package.
     *
     * <p><b>Important:</b> This method only applies to functions backed by an {@link
     * AppFunctionService}. It cannot be used to modify the state of functions registered at
     * runtime. For runtime-registered app functions, use {@link #registerAppFunction} to register
     * them and {@link AppFunctionRegistration#unregister} to unregister them instead.
     *
     * @param functionIdentifier the identifier of the app function to enable (unique within the
     *     calling package).
     * @param newEnabledState the new state of the app function
     * @param executor the executor to run the callback
     * @param callback the callback to receive the result of the function enablement. Can return
     *     {@link IllegalArgumentException} if the function is not found or the caller does not have
     *     access to it.
     * @throws IllegalArgumentException if the function is runtime-registered.
     */
    @UserHandleAware
    public void setAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @EnabledState int newEnabledState,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, Exception> callback) {
        Objects.requireNonNull(functionIdentifier);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        CallbackWrapper callbackWrapper = new CallbackWrapper(executor, callback);
        try {
            mService.setAppFunctionEnabled(
                    mContext.getPackageName(),
                    functionIdentifier,
                    mContext.getUser(),
                    newEnabledState,
                    callbackWrapper);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void isAppFunctionEnabledInternal(
            @NonNull String functionIdentifier,
            @NonNull String targetPackage,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        Objects.requireNonNull(functionIdentifier);
        Objects.requireNonNull(targetPackage);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        if (android.app.appfunctions.flags.Flags.enableDynamicAppFunctions()) {
            isAppFunctionEnabledInternal2(functionIdentifier, targetPackage, executor, callback);
            return;
        }

        AppSearchManager appSearchManager = mContext.getSystemService(AppSearchManager.class);
        if (appSearchManager == null) {
            callback.onError(new IllegalStateException("Failed to get AppSearchManager."));
            return;
        }

        // Wrap the callback to convert AppFunctionNotFoundException to IllegalArgumentException
        // to match the documentation.
        OutcomeReceiver<Boolean, Exception> callbackWithExceptionInterceptor =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(@NonNull Boolean result) {
                        callback.onResult(result);
                    }

                    @Override
                    public void onError(@NonNull Exception exception) {
                        if (exception instanceof AppFunctionNotFoundException) {
                            exception = new IllegalArgumentException(exception);
                        }
                        callback.onError(exception);
                    }
                };

        AppFunctionManagerHelper.isAppFunctionEnabled(
                functionIdentifier,
                targetPackage,
                appSearchManager,
                executor,
                callbackWithExceptionInterceptor);
    }

    private void isAppFunctionEnabledInternal2(
            @NonNull String functionIdentifier,
            @NonNull String targetPackage,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        Objects.requireNonNull(functionIdentifier);
        Objects.requireNonNull(targetPackage);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            mService.isAppFunctionEnabled(
                    mContext.getPackageName(),
                    targetPackage,
                    functionIdentifier,
                    mContext.getUser(),
                    new IIsAppFunctionEnabledCallback.Stub() {
                        @Override
                        public void onSuccess(boolean isEnabled) {
                            callback.onResult(isEnabled);
                        }

                        @Override
                        public void onError(ParcelableException exception) {
                            Throwable cause =
                                    (exception.getCause() == null)
                                            ? exception
                                            : exception.getCause();
                            if (cause instanceof AppFunctionNotFoundException) {
                                callback.onError(new IllegalArgumentException(cause));
                            } else {
                                callback.onError(new RuntimeException(cause));
                            }
                        }
                    });
        } catch (RemoteException re) {
            re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the given agent has access to app functions of the given target app, or if the
     * access is not {@link #getAccessRequestState(String) valid}. Requires the {@link
     * Manifest.permission#MANAGE_APP_FUNCTION_ACCESS} permission if the {@code agentPackageName} is
     * not the calling app.
     *
     * @param agentPackageName The package name of the agent
     * @param targetPackageName The package name of the target
     * @return The state of the access, one of {@link #ACCESS_REQUEST_STATE_GRANTED}, {@link
     *     #ACCESS_REQUEST_STATE_DENIED}, or {@link #ACCESS_REQUEST_STATE_UNREQUESTABLE}
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @RequiresPermission(value = MANAGE_APP_FUNCTION_ACCESS, conditional = true)
    @AppFunctionAccessState
    public int getAccessRequestState(
            @NonNull String agentPackageName, @NonNull String targetPackageName) {
        try {
            return mService.getAccessRequestState(
                    agentPackageName,
                    mContext.getUserId(),
                    targetPackageName,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the calling app has access to app functions of the given target app, for the
     * given users, or if the access is invalid (not able to be requested). An access is valid if:
     * 1. The agent (calling app) and target apps are both installed, and the agent has visibility
     * of the target. 2. The agent has the {@link Manifest.permission#EXECUTE_APP_FUNCTIONS}
     * permission granted. 3. The agent is allowlisted by the system. 4. The target has an
     * AppFunctionService.
     *
     * @param targetPackageName The package name of the target
     * @return The state of the access, one of {@link #ACCESS_REQUEST_STATE_GRANTED}, {@link
     *     #ACCESS_REQUEST_STATE_DENIED}, or {@link #ACCESS_REQUEST_STATE_UNREQUESTABLE}
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @AppFunctionAccessState
    public int getAccessRequestState(@NonNull String targetPackageName) {
        return getAccessRequestState(mContext.getOpPackageName(), targetPackageName);
    }

    /**
     * Get the access flags for a given agent and target. These flags include extra information
     * about the access state (whether it is pregranted, if the user has set state, etc.). Returns 0
     * if the access is not {@link #getAccessRequestState(String) valid}.
     *
     * @param agentPackageName The package name of the agent
     * @param targetPackageName The package name of the target
     * @return The flags for the given agent and target app, or 0 if the combination is not valid
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_APP_FUNCTION_ACCESS)
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    @AppFunctionAccessFlags
    public int getAccessFlags(@NonNull String agentPackageName, @NonNull String targetPackageName) {
        try {
            return mService.getAccessFlags(
                    agentPackageName,
                    mContext.getUserId(),
                    targetPackageName,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the access flags for the given agent and target. If the access is not {@link
     * #getAccessRequestState(String) valid}, this method is a no-op.
     *
     * @param agentPackageName The package name of the agent
     * @param targetPackageName The package name of the target
     * @param flagMask The mask determining which flag values will be changed
     * @param flags The flag values to be changed
     * @throws IllegalArgumentException if an invalid flag is specified, opposing flags (e.g.
     *     USER_GRANTED and USER_DENIED) are set together, or a flag with an opposite is set,
     *     without its opposite being explicitly cleared (via being included in the flag mask, but
     *     not the flag set).
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_APP_FUNCTION_ACCESS)
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public void updateAccessFlags(
            @NonNull String agentPackageName,
            @NonNull String targetPackageName,
            @AppFunctionAccessFlags int flagMask,
            @AppFunctionAccessFlags int flags) {
        try {
            mService.updateAccessFlags(
                    agentPackageName,
                    mContext.getUserId(),
                    targetPackageName,
                    mContext.getUserId(),
                    flagMask,
                    flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Revokes the App Function access for the calling app and the given target
     *
     * @param targetPackageName The app whose {@link AppFunctionService} the calling app should lose
     *     access to.
     */
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public void revokeSelfAccess(@NonNull String targetPackageName) {
        try {
            mService.revokeSelfAccess(targetPackageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all {@link #getAccessRequestState(String) valid} agents.
     *
     * @return A list of all valid agent package names
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_APP_FUNCTION_ACCESS)
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public @NonNull List<String> getValidAgents() {
        try {
            return mService.getValidAgents(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all {@link #getAccessRequestState(String) valid} target apps.
     *
     * @return A list of all target app package names in the current user that the agent can request
     *     access for
     * @hide
     */
    @SystemApi
    @RequiresPermission(MANAGE_APP_FUNCTION_ACCESS)
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public @NonNull List<String> getValidTargets() {
        try {
            return mService.getValidTargets(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates an intent which can be used to request App Function access for the given target app.
     *
     * <p>The intent MUST be used with {@link android.app.Activity#startActivityForResult}. The
     * result code of the activity will be {@link android.app.Activity#RESULT_OK} if the request was
     * granted, {@link android.app.Activity#RESULT_CANCELED} if not.
     *
     * @param targetPackageName The app access is being requested for.
     * @return The created intent.
     */
    @FlaggedApi(FLAG_APP_FUNCTION_ACCESS_UI_ENABLED)
    public @NonNull Intent createRequestAccessIntent(@NonNull String targetPackageName) {
        try {
            return mService.createRequestAccessIntent(targetPackageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current agent allowlist
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(MANAGE_APP_FUNCTION_ACCESS)
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public @NonNull List<SignedPackage> getAgentAllowlist() {
        return new ArrayList<>();
    }

    /**
     * Register an {@link OnAppFunctionAccessChangedListener} for changes to app function access. If
     * the listener is already registered, this method is a no-op. If the Context user is different
     * from the calling user, this method requires the {@link INTERACT_ACROSS_USERS_FULL}
     * permission.
     *
     * @param executor The executor to run the listener callbacks on
     * @param listener The listener to add
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(
            allOf = {MANAGE_APP_FUNCTION_ACCESS, INTERACT_ACROSS_USERS_FULL},
            conditional = true)
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public void addAccessChangedListener(
            @NonNull Executor executor, @NonNull OnAppFunctionAccessChangedListener listener) {
        synchronized (mListeners) {
            if (mListeners.containsKey(listener)) {
                return;
            }
            final OnAppFunctionAccessChangeListenerDelegate delegate =
                    new OnAppFunctionAccessChangeListenerDelegate(
                            listener, executor, mContext.getUserId());
            try {
                mService.addOnAccessChangedListener(delegate, mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mListeners.put(listener, delegate);
        }
    }

    /**
     * Remove an {@link OnAppFunctionAccessChangedListener}. If the Context user is different from
     * the calling user, this method requires the {@link INTERACT_ACROSS_USERS_FULL} permission.
     *
     * @param listener The listener to remove
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(
            allOf = {MANAGE_APP_FUNCTION_ACCESS, INTERACT_ACROSS_USERS_FULL},
            conditional = true)
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public void removeAccessChangedListener(@NonNull OnAppFunctionAccessChangedListener listener) {
        synchronized (mListeners) {
            if (!mListeners.containsKey(listener)) {
                return;
            }
            try {
                final OnAppFunctionAccessChangeListenerDelegate delegate = mListeners.get(listener);
                mService.removeOnAccessChangedListener(delegate, delegate.mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mListeners.remove(listener);
        }
    }

    /**
     * Unregisters all app functions that were registered through this {@code AppFunctionManager}
     * instance.
     *
     * <p>This method should be called to clean up all registrations when the associated
     * {@link Context} is destroyed.
     *
     * @param callerDescription A description of the caller, used for logging.
     *
     * @hide
     */
    @FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    public void unregisterAllAppFunctions(@NonNull String callerDescription) {
        Objects.requireNonNull(callerDescription);
        mRegistry.unregisterAllAppFunctions(callerDescription);
    }

    /**
     * Listener for changes to app function access for an agent.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_APP_FUNCTION_ACCESS_API_ENABLED)
    public interface OnAppFunctionAccessChangedListener {
        /**
         * Called when the app function access for an agent UID changes.
         *
         * @param agentUid The agent UID
         */
        void onAppFunctionAccessChanged(int agentUid);
    }

    private static final class OnAppFunctionAccessChangeListenerDelegate
            extends IOnAppFunctionAccessChangeListener.Stub {

        private final OnAppFunctionAccessChangedListener mListener;
        private final Executor mExecutor;
        private final int mUserId;

        private OnAppFunctionAccessChangeListenerDelegate(
                OnAppFunctionAccessChangedListener listener, Executor executor, int userId) {
            mListener = listener;
            mExecutor = executor;
            mUserId = userId;
        }

        @Override
        public void onAppFunctionAccessChanged(int agentUid) {
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mListener.onAppFunctionAccessChanged(agentUid));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    private class AppFunctionRegistry {
        private final Object mLock = new Object();

        private final IBinder mActivityToken;

        private final boolean mIsAllowedContext;

        @GuardedBy("mLock")
        private final ArrayMap<String, RegistrationRecord> mRegistrations = new ArrayMap<>();

        private final IAppFunctionExecutor.Stub mExecutor =
                new IAppFunctionExecutor.Stub() {
                    @Override
                    public void execute(
                            ExecuteAppFunctionRequest request,
                            ICancellationCallback cancellationCallback,
                            IExecuteAppFunctionCallback callback)
                            throws RemoteException {
                        RegistrationRecord registration;
                        synchronized (mLock) {
                            registration = mRegistrations.get(request.getFunctionIdentifier());
                        }
                        if (registration == null) {
                            callback.onError(
                                    new AppFunctionException(
                                            ERROR_DISABLED,
                                            "Function with id "
                                                    + request.getFunctionIdentifier()
                                                    + " is not registered"));
                        } else {
                            registration.onExecuteFunction(
                                    request,
                                    buildCancellationSignal(cancellationCallback),
                                    callback);
                        }
                    }
                };

        AppFunctionRegistry(Context context) {
            if (context instanceof Activity) {
                mIsAllowedContext = true;
                mActivityToken = context.getActivityToken();
            } else {
                mIsAllowedContext = (context instanceof Service);
                mActivityToken = null;
            }
        }

        AppFunctionRegistration register(List<RegisterAppFunctionRequest> requests) {
            if (!mIsAllowedContext) {
                throw new IllegalStateException(
                        "AppFunction registration is restricted to AppFunctionManager instances "
                                + "obtained from either an Activity or a Service context.");
            }

            // This lock is held during the IPC call to the system server. This is a deliberate
            // choice to synchronize registration requests from multiple threads within this
            // process. The server-side implementation is also synchronized, preventing deadlocks
            // and ensuring that registrations are handled atomically across the entire system.
            synchronized (mLock) {
                ArrayMap<String, RegistrationRecord> batchRegistration =
                        new ArrayMap<>(requests.size());
                ArrayList<String> functionIds = new ArrayList<>(requests.size());
                for (RegisterAppFunctionRequest request : requests) {
                    if (mRegistrations.containsKey(request.getFunctionIdentifier())) {
                        throw new IllegalStateException(
                                "Function id "
                                        + request.getFunctionIdentifier()
                                        + " is already registered");
                    }
                    if (batchRegistration.containsKey(request.getFunctionIdentifier())) {
                        throw new IllegalArgumentException(
                                "Function ids must be unique in the registration requests list.");
                    }
                    batchRegistration.put(
                            request.getFunctionIdentifier(), new RegistrationRecord(request));
                    functionIds.add(request.getFunctionIdentifier());
                }

                try {
                    mService.registerAppFunctions(
                            mContext.getPackageName(), functionIds, mExecutor, mActivityToken);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                mRegistrations.putAll(batchRegistration);
                return () -> unregister(batchRegistration);
            }
        }

        void unregister(@NonNull ArrayMap<String, RegistrationRecord> batchRegistrations) {
            synchronized (mLock) {
                List<String> functionIdsToUnregister = new ArrayList<>(batchRegistrations.size());
                for (Map.Entry<String, RegistrationRecord> entry : batchRegistrations.entrySet()) {
                    String functionId = entry.getKey();
                    if (mRegistrations.get(functionId) != entry.getValue()) {
                        continue;
                    }
                    functionIdsToUnregister.add(functionId);
                }
                try {
                    mService.unregisterAppFunctions(
                            mContext.getPackageName(),
                            functionIdsToUnregister,
                            mExecutor);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                mRegistrations.removeAll(functionIdsToUnregister);
            }
        }

        void unregisterAllAppFunctions(@NonNull String callerDescription) {
            synchronized (mLock) {
                if (mRegistrations.isEmpty()) {
                    return;
                }
                Slog.e(TAG, "Leaked AppFunction registrations detected from " + callerDescription
                                + ". Functions: [" + String.join(", ", mRegistrations.keySet())
                                + "]. Ensure AppFunctionRegistration.unregister() is called to"
                                + " prevent memory leaks.");
                try {
                    mService.unregisterAppFunctions(
                            mContext.getPackageName(),
                            new ArrayList<>(mRegistrations.keySet()),
                            mExecutor);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                mRegistrations.clear();
            }
        }

        private static class RegistrationRecord {
            private final AppFunction mAppFunction;
            private final Executor mExecutor;

            RegistrationRecord(RegisterAppFunctionRequest request) {
                mAppFunction = request.getAppFunction();
                mExecutor = request.getExecutor();
            }

            void onExecuteFunction(
                    ExecuteAppFunctionRequest request,
                    CancellationSignal cancelSignal,
                    IExecuteAppFunctionCallback callback) {
                SafeOneTimeExecuteAppFunctionCallback safeCallback =
                        new SafeOneTimeExecuteAppFunctionCallback(callback);
                mExecutor.execute(
                        () -> {
                            try {
                                mAppFunction.onExecuteAppFunction(
                                        request,
                                        cancelSignal,
                                        new OutcomeReceiver<>() {
                                            @Override
                                            public void onResult(
                                                    ExecuteAppFunctionResponse result) {
                                                safeCallback.onResult(result);
                                            }

                                            @Override
                                            public void onError(
                                                    @NonNull AppFunctionException exception) {
                                                safeCallback.onError(exception);
                                            }
                                        });
                            } catch (Exception ex) {
                                safeCallback.onError(
                                        new AppFunctionException(
                                                executionExceptionToErrorCode(ex),
                                                ex.getMessage()));
                            }
                        });
            }
        }
    }

    private static class CallbackWrapper extends ISetAppFunctionEnabledCallback.Stub {

        private final OutcomeReceiver<Void, Exception> mCallback;
        private final Executor mExecutor;

        CallbackWrapper(
                @NonNull Executor callbackExecutor,
                @NonNull OutcomeReceiver<Void, Exception> callback) {
            mCallback = callback;
            mExecutor = callbackExecutor;
        }

        @Override
        public void onSuccess() {
            mExecutor.execute(() -> mCallback.onResult(null));
        }

        @Override
        public void onError(@NonNull ParcelableException exception) {
            mExecutor.execute(
                    () -> {
                        if (IllegalArgumentException.class.isAssignableFrom(
                                exception.getCause().getClass())) {
                            mCallback.onError((IllegalArgumentException) exception.getCause());
                        } else if (SecurityException.class.isAssignableFrom(
                                exception.getCause().getClass())) {
                            mCallback.onError((SecurityException) exception.getCause());
                        } else {
                            mCallback.onError(exception);
                        }
                    });
        }
    }

    /**
     * A processor that consumes the asynchronous search result stream from the system service.
     *
     * <p>It buffers these results in memory until the stream is exhausted (signaled by an empty
     * page), at which point it delivers the complete list to the user's callback.
     */
    private static final class SearchResultsProcessor
            extends IAppFunctionSearchResultCallback.Stub {
        private final IAppFunctionSearchResults mSession;
        private final Executor mExecutor;
        private final OutcomeReceiver<List<AppFunctionMetadata>, Exception> mUserCallback;

        private final List<AppFunctionMetadata> mAccumulator = new ArrayList<>();

        SearchResultsProcessor(
                @NonNull IAppFunctionSearchResults session,
                @NonNull Executor executor,
                @NonNull OutcomeReceiver<List<AppFunctionMetadata>, Exception> callback) {
            mSession = Objects.requireNonNull(session);
            mExecutor = Objects.requireNonNull(executor);
            mUserCallback = Objects.requireNonNull(callback);
        }

        void fetchNext() {
            try {
                mSession.getNextPage(this);
            } catch (RemoteException e) {
                notifyError(e.rethrowFromSystemServer());
            }
        }

        @Override
        public void onResult(List<AppFunctionMetadata> result) {
            if (result.isEmpty()) {
                closeSession();
                final List<AppFunctionMetadata> resultCopy = new ArrayList<>(mAccumulator);
                mExecutor.execute(() -> mUserCallback.onResult(resultCopy));
            } else {
                mAccumulator.addAll(result);
                fetchNext();
            }
        }

        @Override
        public void onError(ParcelableException exception) {
            closeSession();
            if (exception.getCause() != null) {
                notifyError(new RuntimeException(exception.getCause()));
            } else {
                notifyError(new RuntimeException("Unknown failure during search"));
            }
        }

        private void notifyError(@NonNull Exception e) {
            Objects.requireNonNull(e);
            mExecutor.execute(() -> mUserCallback.onError(e));
        }

        private void closeSession() {
            try {
                mSession.close();
            } catch (RemoteException e) {
                Log.w(TAG, "Fail to close the search session", e);
            }
        }
    }
}

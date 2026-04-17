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

import static android.Manifest.permission.BIND_APP_FUNCTION_SERVICE;
import static android.app.appfunctions.AppFunctionManagerHelper.buildCancellationSignal;
import static android.app.appfunctions.AppFunctionManagerHelper.executionExceptionToErrorCode;
import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_APP_FUNCTION_MANAGER;
import static android.content.pm.PackageManager.PERMISSION_DENIED;

import android.annotation.FlaggedApi;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Service;
import android.app.appfunctions.flags.Flags;
import android.content.Context;
import android.content.Intent;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.util.Log;

// TODO: b/483628788 - Explain XSD and compatibility (v1).
/**
 * Extend this class to implement app functions, that can be executed by {@link
 * AppFunctionManager#executeAppFunction}.
 *
 * <p>{@link AppFunctionManager#executeAppFunction} targeting an app function provided by this
 * service will bind to this service (waking your app if necessary). A single {@link
 * AppFunctionService} implementation can implement multiple app functions, by examining the {@link
 * ExecuteAppFunctionRequest#getFunctionIdentifier} in the {@link #onExecuteFunction} method.
 *
 * <p>You must declare the service and its associated app functions in your {@code
 * AndroidManifest.xml}. The {@code <service>} manifest entry must:
 *
 * <ul>
 *   <li>Require the {@link BIND_APP_FUNCTION_SERVICE} permission to ensure that only the system can
 *       bind to the service.
 *   <li>Declare an {@code <intent-filter>} with the {@link #SERVICE_INTERFACE} action to indicate
 *       to the system that it provides app functions.
 *   <li>Reference an XML asset that defines the metadata for the functions provided by this service
 *       using a {@code <property>} named {@code android.app.appfunctions}. See {@link
 *       AppFunctionMetadata} for details on the XML schema.
 * </ul>
 *
 * <p><b>Example manifest declaration:</b>
 *
 * <pre>{@code
 * <service
 *     android:name=".YourAppFunctionService"
 *     android:permission="android.permission.BIND_APP_FUNCTION_SERVICE">
 *   <property
 *       android:name="android.app.appfunctions.v2"
 *       android:value="your_app_functions.xml" />
 *   <intent-filter>
 *     <action android:name="android.app.appfunctions.AppFunctionService" />
 *   </intent-filter>
 * </service>
 * }</pre>
 *
 * <p><b>Important:</b> Only one {@link AppFunctionService} implementation can be active in an app
 * at a time.
 */
@FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
public abstract class AppFunctionService extends Service {
    private static final String TAG = "AppFunctionService";

    /**
     * The {@link Intent} filter that must be declared as handled by the service.
     *
     * <p>To be supported, the service must also require the {@link BIND_APP_FUNCTION_SERVICE}
     * permission so that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    @NonNull
    public static final String SERVICE_INTERFACE = "android.app.appfunctions.AppFunctionService";

    /**
     * Functional interface to represent the execution logic of an app function.
     *
     * @hide
     */
    @FunctionalInterface
    public interface OnExecuteFunction {
        /**
         * Performs the semantic of executing the function specified by the provided request and
         * return the response through the provided callback.
         */
        void perform(
                @NonNull ExecuteAppFunctionRequest request,
                @NonNull String callingPackage,
                @NonNull SigningInfo callingPackageSigningInfo,
                @NonNull CancellationSignal cancellationSignal,
                @NonNull
                        OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> callback);
    }

    /** @hide */
    @NonNull
    public static Binder createBinder(
            @NonNull Context context, @NonNull OnExecuteFunction onExecuteFunction) {
        return new IAppFunctionService.Stub() {
            @Override
            public void executeAppFunction(
                    @NonNull ExecuteAppFunctionRequest request,
                    @NonNull String callingPackage,
                    @NonNull SigningInfo callingPackageSigningInfo,
                    @NonNull ICancellationCallback cancellationCallback,
                    @NonNull IExecuteAppFunctionCallback callback) {
                if (context.checkCallingPermission(BIND_APP_FUNCTION_SERVICE)
                        == PERMISSION_DENIED) {
                    throw new SecurityException("Can only be called by the system server.");
                }
                SafeOneTimeExecuteAppFunctionCallback safeCallback =
                        new SafeOneTimeExecuteAppFunctionCallback(callback);
                context.getMainExecutor()
                        .execute(
                                () -> {
                                    OutcomeReceiver<
                                                    ExecuteAppFunctionResponse,
                                                    AppFunctionException>
                                            outcomeReceiver =
                                                    new OutcomeReceiver<
                                                            ExecuteAppFunctionResponse,
                                                            AppFunctionException>() {
                                                        @Override
                                                        public void onResult(
                                                                ExecuteAppFunctionResponse result) {
                                                            safeCallback.onResult(result);
                                                        }

                                                        @Override
                                                        public void onError(
                                                                AppFunctionException exception) {
                                                            safeCallback.onError(exception);
                                                        }
                                                    };
                                    try {
                                        if (Flags.enableAppFunctionPermissionV2()) {
                                            onExecuteFunction.perform(
                                                    request,
                                                    /* callingPackage= */ "",
                                                    /* callingPackageSigningInfo= */ new SigningInfo(),
                                                    buildCancellationSignal(cancellationCallback),
                                                    outcomeReceiver);
                                        } else {
                                            onExecuteFunction.perform(
                                                    request,
                                                    callingPackage,
                                                    callingPackageSigningInfo,
                                                    buildCancellationSignal(cancellationCallback),
                                                    outcomeReceiver);
                                        }
                                    } catch (Exception ex) {
                                        // Apps should handle exceptions. But if they don't, report
                                        // the
                                        // error on behalf of them.
                                        Log.w(TAG, "Uncaught exception in AppFunctionService", ex);
                                        safeCallback.onError(
                                                new AppFunctionException(
                                                        executionExceptionToErrorCode(ex),
                                                        ex.getMessage()));
                                    }
                                });
            }
        };
    }

    private final Binder mBinder =
            createBinder(AppFunctionService.this, AppFunctionService.this::onExecuteFunction);

    @NonNull
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        return mBinder;
    }

    /**
     * Called by the system following an {@link AppFunctionManager#executeAppFunction} call that
     * targets an app function identifier referenced by the XML asset associated with this service.
     *
     * <p>You can determine which function to execute using {@link
     * ExecuteAppFunctionRequest#getFunctionIdentifier()}. This allows your service to route the
     * incoming request to the appropriate logic for handling the specific function. See {@link
     * ExecuteAppFunctionRequest} for how to retrieve the function's arguments.
     *
     * <p>This method is always triggered in the main thread. You should run heavy tasks on a worker
     * thread and dispatch the result with the given callback. You should always report back the
     * result using the callback, no matter if the execution was successful or not.
     *
     * <p>The implementation should try to respect the provided {@link CancellationSignal}, if
     * possible.
     *
     * @param request The function execution request.
     * @param callingPackage The package name of the app that is requesting the execution. It is
     *     strongly recommended that you do not alter your function’s behavior based on this value.
     *     Your function should behave consistently for all callers to ensure a predictable
     *     experience. Starting in {@link android.os.Build.VERSION_CODES#CINNAMON_BUN}, the value
     *     will always be an empty string.
     * @param callingPackageSigningInfo The signing information of the app that is requesting the
     *     execution. It is strongly recommended that you do not alter your function’s behavior
     *     based on this value. Your function should behave consistently for all callers to ensure a
     *     predictable experience. Starting in {@link android.os.Build.VERSION_CODES#CINNAMON_BUN},
     *     the value will always be an unknown signing info.
     * @param cancellationSignal A signal to cancel the execution.
     * @param callback A callback to report back the result or error.
     */
    @MainThread
    public abstract void onExecuteFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull String callingPackage,
            @NonNull SigningInfo callingPackageSigningInfo,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> callback);
}

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
package android.app.voiceinteraction;

import static android.permission.flags.Flags.FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED;

import android.Manifest;
import android.annotation.CheckResult;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.RemoteException;

import com.android.internal.app.IVoiceInteractionManagerService;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Service that provides information about and interacts with the current global voice interactor
 */
@FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
@SystemService(Context.VOICE_INTERACTION_MANAGER_SERVICE)
public final class VoiceInteractionManager {
    private final IVoiceInteractionManagerService mService;
    private final Context mContext;

    /**
     * Activity action: Launch UI to for an assistant to request access to screen context.
     *
     * <p>Output: Nothing.
     *
     * @hide
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_READ_SCREEN_CONTEXT =
            "android.app.voiceinteraction.action.REQUEST_READ_SCREEN_CONTEXT";

    /**
     * Read screen context access is granted.
     *
     * @see #getReadScreenContextRequestState()
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    public static final int READ_SCREEN_CONTEXT_REQUEST_STATE_GRANTED = 0;

    /**
     * Read screen context access isn't granted, but apps can request access. When the app request
     * access, user will be prompted with request dialog to grant or deny the request.
     *
     * @see #getReadScreenContextRequestState()
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    public static final int READ_SCREEN_CONTEXT_REQUEST_STATE_REQUESTABLE = 1;

    /**
     * Read screen context access is denied, and can't be requested with dialog by apps. Access
     * request will be automatically denied by the system, preventing the request dialog from being
     * displayed to the user.
     *
     * @see #getReadScreenContextRequestState()
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    public static final int READ_SCREEN_CONTEXT_REQUEST_STATE_UNREQUESTABLE = 2;


    /** @hide */
    @IntDef(prefix = { "ASSIST_STRUCTURE_REQUEST_STATE_" }, value = {
            READ_SCREEN_CONTEXT_REQUEST_STATE_GRANTED,
            READ_SCREEN_CONTEXT_REQUEST_STATE_REQUESTABLE,
            READ_SCREEN_CONTEXT_REQUEST_STATE_UNREQUESTABLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReadScreenContextRequestState {}

    /**
     * Creates an instance.
     *
     * @param service An interface to the backing service.
     * @param context A {@link Context}.
     * @hide
     */
    public VoiceInteractionManager(@NonNull IVoiceInteractionManagerService service,
            @NonNull Context context) {
        mService = service;
        mContext = context;
    }

    /**
     * Creates an intent which can be used to request access to screen context for current
     * assistant role holder. This intent MUST be used with
     * {@link android.app.Activity#startActivityForResult}. The result code of the activity will be
     * {@link android.app.Activity#RESULT_OK} if the request was granted,
     * {@link android.app.Activity#RESULT_CANCELED} if not. If the caller is not the current
     * default assistant, the request will be denied automatically.
     *
     * @return The created intent.
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    public @NonNull Intent createRequestReadScreenContextIntent() {
        Intent intent = new Intent(ACTION_REQUEST_READ_SCREEN_CONTEXT);
        intent.setPackage(mContext.getPackageManager().getPermissionControllerPackageName());
        return intent;
    }

    /**
     * Returns the read screen context request state for an app. This method provides a streamlined
     * mechanism for applications to determine whether read screen context access can be
     * requested (i.e. whether the user will be prompted with a request dialog).
     *
     * @return The current request state of the specified access, represented by one of the
     * following constants:
     * {@link #READ_SCREEN_CONTEXT_REQUEST_STATE_GRANTED},
     * {@link #READ_SCREEN_CONTEXT_REQUEST_STATE_REQUESTABLE},
     * or {@link #READ_SCREEN_CONTEXT_REQUEST_STATE_UNREQUESTABLE}
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    @CheckResult
    @UserHandleAware
    @ReadScreenContextRequestState
    public int getReadScreenContextRequestState() {
        try {
            return mService.getReadScreenContextRequestState(Process.myUid());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the read screen context request state for an app. This method provides a streamlined
     * mechanism for applications to determine whether read screen context access can be
     * requested (i.e. whether the user will be prompted with a request dialog).
     * <p>If the {@code uid} is different from the calling UID, this method requires the
     * {@link Manifest.permission#MANAGE_READ_SCREEN_CONTEXT_REQUEST} permission.
     * If the user of {@code uid} is different from the calling user, this method requires the
     * {@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission.
     *
     * @return The current request state of the specified access, represented by one of the
     * following constants:
     * {@link #READ_SCREEN_CONTEXT_REQUEST_STATE_GRANTED},
     * {@link #READ_SCREEN_CONTEXT_REQUEST_STATE_REQUESTABLE},
     * or {@link #READ_SCREEN_CONTEXT_REQUEST_STATE_UNREQUESTABLE}
     *
     * @hide
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    @RequiresPermission(allOf = {
            Manifest.permission.MANAGE_READ_SCREEN_CONTEXT_REQUEST,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL
    }, conditional = true)
    @SystemApi
    @CheckResult
    @ReadScreenContextRequestState
    public int getReadScreenContextRequestState(int uid) {
        try {
            return mService.getReadScreenContextRequestState(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the calling app can read screen context.
     *
     * @return {@code true} if the calling app is the current assistant and has access to screen
     * context , {@code false} otherwise.
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    @UserHandleAware
    public boolean canReadScreenContext() {
        final RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        if (!roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            return false;
        }

        final AppOpsManager appOpsManager = mContext.getSystemService(AppOpsManager.class);
        final int opMode = appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_READ_SCREEN_CONTEXT,
                Process.myUid(), mContext.getOpPackageName());

        return opMode == AppOpsManager.MODE_DEFAULT || opMode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Increments the current user denied count from user interactions with the read screen access
     * request dialog.
     * <p>This method always requires
     * {@link Manifest.permission#MANAGE_READ_SCREEN_CONTEXT_REQUEST}.
     * If the context user is different from the calling user, this method requires the
     * {@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission.
     *
     * @see #createRequestAssistStructureIntent()
     * @see #getReadScreenContextRequestState()
     *
     * @hide
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    @RequiresPermission(allOf = {
            Manifest.permission.MANAGE_READ_SCREEN_CONTEXT_REQUEST,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL
    }, conditional = true)
    @SystemApi
    @UserHandleAware
    public void incrementReadScreenContextRequestDeniedCount() {
        try {
            mService.incrementReadScreenContextRequestDeniedCountForUser(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears the current user denied count from user interactions with the read screen access
     * request dialog.
     * <p>This method always requires
     * {@link Manifest.permission#MANAGE_READ_SCREEN_CONTEXT_REQUEST}.
     * If the context user is different from the calling user, this method requires the
     * {@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission.
     *
     * @see #createRequestAssistStructureIntent()
     * @see #getReadScreenContextRequestState()
     *
     * @hide
     */
    @FlaggedApi(FLAG_ASSIST_SETTINGS_PRIVACY_IMPROVEMENTS_ENABLED)
    @RequiresPermission(allOf = {
            Manifest.permission.MANAGE_READ_SCREEN_CONTEXT_REQUEST,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL
    }, conditional = true)
    @SystemApi
    @UserHandleAware
    public void clearReadScreenContextRequestDeniedCount() {
        try {
            mService.clearReadScreenContextRequestDeniedCountForUser(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}

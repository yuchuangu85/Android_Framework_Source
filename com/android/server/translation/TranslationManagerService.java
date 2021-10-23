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

package com.android.server.translation;

import static android.Manifest.permission.MANAGE_UI_TRANSLATION;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.Context.TRANSLATION_MANAGER_SERVICE;
import static android.view.translation.TranslationManager.STATUS_SYNC_CALL_FAIL;
import static android.view.translation.TranslationManager.STATUS_SYNC_CALL_SUCCESS;

import static com.android.internal.util.SyncResultReceiver.bundleFor;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.translation.ITranslationManager;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationManager.UiTranslationState;
import android.view.translation.UiTranslationSpec;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.DumpUtils;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Entry point service for translation management.
 *
 * <p>This service provides the {@link ITranslationManager} implementation and keeps a list of
 * {@link TranslationManagerServiceImpl} per user; the real work is done by
 * {@link TranslationManagerServiceImpl} itself.
 */
public final class TranslationManagerService
        extends AbstractMasterSystemService<TranslationManagerService,
        TranslationManagerServiceImpl> {

    private static final String TAG = "TranslationManagerService";

    private static final int MAX_TEMP_SERVICE_SUBSTITUTION_DURATION_MS = 2 * 60_000; // 2 minutes

    public TranslationManagerService(Context context) {
        // TODO: Discuss the disallow policy
        super(context, new FrameworkResourcesServiceNameResolver(context,
                        com.android.internal.R.string.config_defaultTranslationService),
                /* disallowProperty */ null, PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
    }

    @Override
    protected TranslationManagerServiceImpl newServiceLocked(int resolvedUserId, boolean disabled) {
        return new TranslationManagerServiceImpl(this, mLock, resolvedUserId, disabled);
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_UI_TRANSLATION, TAG);
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_SUBSTITUTION_DURATION_MS;
    }

    @Override
    protected void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);
    }

    private void enforceCallerHasPermission(String permission) {
        final String msg = "Permission Denial from pid =" + Binder.getCallingPid() + ", uid="
                + Binder.getCallingUid() + " doesn't hold " + permission;
        getContext().enforceCallingPermission(permission, msg);
    }

    /** True if the currently set handler service is not overridden by the shell. */
    @GuardedBy("mLock")
    private boolean isDefaultServiceLocked(int userId) {
        final String defaultServiceName = mServiceNameResolver.getDefaultServiceName(userId);
        if (defaultServiceName == null) {
            return false;
        }

        final String currentServiceName = mServiceNameResolver.getServiceName(userId);
        return defaultServiceName.equals(currentServiceName);
    }

    /** True if the caller of the api is the same app which hosts the TranslationService. */
    @GuardedBy("mLock")
    private boolean isCalledByServiceAppLocked(int userId, @NonNull String methodName) {
        final int callingUid = Binder.getCallingUid();

        final String serviceName = mServiceNameResolver.getServiceName(userId);
        if (serviceName == null) {
            Slog.e(TAG, methodName + ": called by UID " + callingUid
                    + ", but there's no service set for user " + userId);
            return false;
        }

        final ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
        if (serviceComponent == null) {
            Slog.w(TAG, methodName + ": invalid service name: " + serviceName);
            return false;
        }

        final String servicePackageName = serviceComponent.getPackageName();
        final PackageManager pm = getContext().getPackageManager();
        final int serviceUid;
        try {
            serviceUid = pm.getPackageUidAsUser(servicePackageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, methodName + ": could not verify UID for " + serviceName);
            return false;
        }
        if (callingUid != serviceUid) {
            Slog.e(TAG, methodName + ": called by UID " + callingUid + ", but service UID is "
                    + serviceUid);
            return false;
        }
        return true;
    }

    final class TranslationManagerServiceStub extends ITranslationManager.Stub {

        @Override
        public void onTranslationCapabilitiesRequest(@TranslationSpec.DataFormat int sourceFormat,
                @TranslationSpec.DataFormat int targetFormat,
                ResultReceiver receiver, int userId)
                throws RemoteException {
            synchronized (mLock) {
                final TranslationManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null && (isDefaultServiceLocked(userId)
                        || isCalledByServiceAppLocked(userId, "getTranslationCapabilities"))) {
                    service.onTranslationCapabilitiesRequestLocked(sourceFormat, targetFormat,
                            receiver);
                } else {
                    Slog.v(TAG, "onGetTranslationCapabilitiesLocked(): no service for " + userId);
                    receiver.send(STATUS_SYNC_CALL_FAIL, null);
                }
            }
        }

        @Override
        public void registerTranslationCapabilityCallback(IRemoteCallback callback, int userId) {
            TranslationManagerServiceImpl service;
            synchronized (mLock) {
                service = getServiceForUserLocked(userId);
            }
            if (service != null) {
                service.registerTranslationCapabilityCallback(callback, Binder.getCallingUid());
            }
        }

        @Override
        public void unregisterTranslationCapabilityCallback(IRemoteCallback callback, int userId) {
            TranslationManagerServiceImpl service;
            synchronized (mLock) {
                service = getServiceForUserLocked(userId);
            }
            if (service != null) {
                service.unregisterTranslationCapabilityCallback(callback);
            }
        }

        @Override
        public void onSessionCreated(TranslationContext translationContext,
                int sessionId, IResultReceiver receiver, int userId) throws RemoteException {
            synchronized (mLock) {
                final TranslationManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null && (isDefaultServiceLocked(userId)
                        || isCalledByServiceAppLocked(userId, "onSessionCreated"))) {
                    service.onSessionCreatedLocked(translationContext, sessionId, receiver);
                } else {
                    Slog.v(TAG, "onSessionCreated(): no service for " + userId);
                    receiver.send(STATUS_SYNC_CALL_FAIL, null);
                }
            }
        }

        @Override
        public void updateUiTranslationState(@UiTranslationState int state,
                TranslationSpec sourceSpec, TranslationSpec targetSpec, List<AutofillId> viewIds,
                IBinder token, int taskId, UiTranslationSpec uiTranslationSpec, int userId) {
            enforceCallerHasPermission(MANAGE_UI_TRANSLATION);
            synchronized (mLock) {
                final TranslationManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null && (isDefaultServiceLocked(userId)
                        || isCalledByServiceAppLocked(userId, "updateUiTranslationState"))) {
                    service.updateUiTranslationStateLocked(state, sourceSpec, targetSpec, viewIds,
                            token, taskId, uiTranslationSpec);
                }
            }
        }

        @Override
        public void registerUiTranslationStateCallback(IRemoteCallback callback, int userId) {
            TranslationManagerServiceImpl service;
            synchronized (mLock) {
                service = getServiceForUserLocked(userId);
            }
            if (service != null) {
                service.registerUiTranslationStateCallback(callback, Binder.getCallingUid());
            }
        }

        @Override
        public void unregisterUiTranslationStateCallback(IRemoteCallback callback, int userId) {
            TranslationManagerServiceImpl service;
            synchronized (mLock) {
                service = getServiceForUserLocked(userId);
            }
            if (service != null) {
                service.unregisterUiTranslationStateCallback(callback);
            }
        }

        @Override
        public void onTranslationFinished(boolean activityDestroyed, IBinder token,
                ComponentName componentName, int userId) {
            TranslationManagerServiceImpl service;
            synchronized (mLock) {
                service = getServiceForUserLocked(userId);
                service.onTranslationFinishedLocked(activityDestroyed, token, componentName);
            }
        }

        @Override
        public void getServiceSettingsActivity(IResultReceiver result, int userId) {
            final TranslationManagerServiceImpl service;
            synchronized (mLock) {
                service = getServiceForUserLocked(userId);
            }
            if (service != null) {
                final ComponentName componentName = service.getServiceSettingsActivityLocked();
                if (componentName == null) {
                    try {
                        result.send(STATUS_SYNC_CALL_SUCCESS, null);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Unable to send getServiceSettingsActivity(): " + e);
                    }
                }
                final Intent intent = new Intent();
                intent.setComponent(componentName);
                final long identity = Binder.clearCallingIdentity();
                try {
                    final PendingIntent pendingIntent =
                            PendingIntent.getActivityAsUser(getContext(), 0, intent, FLAG_IMMUTABLE,
                                    null, new UserHandle(userId));
                    try {

                        result.send(STATUS_SYNC_CALL_SUCCESS, bundleFor(pendingIntent));
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Unable to send getServiceSettingsActivity(): " + e);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } else {
                try {
                    result.send(STATUS_SYNC_CALL_FAIL, null);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to send getServiceSettingsActivity(): " + e);
                }
            }
        }

        /**
         * Dump the service state into the given stream. You run "adb shell dumpsys translation".
        */
        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

            synchronized (mLock) {
                dumpLocked("", pw);
                final int userId = UserHandle.getCallingUserId();
                final TranslationManagerServiceImpl service = getServiceForUserLocked(userId);
                if (service != null) {
                    service.dumpLocked("  ", fd, pw);
                }
            }
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in,
                @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args,
                @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) throws RemoteException {
            new TranslationManagerServiceShellCommand(
                    TranslationManagerService.this).exec(this, in, out, err, args, callback,
                    resultReceiver);
        }
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(TRANSLATION_MANAGER_SERVICE,
                new TranslationManagerService.TranslationManagerServiceStub());
    }
}

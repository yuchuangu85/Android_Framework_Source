/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.restrictions;

import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.IDevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IRestrictionsManager;
import android.content.Intent;
import android.content.RestrictionsManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IUserManager;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;

/**
 * SystemService wrapper for the RestrictionsManager implementation. Publishes the
 * Context.RESTRICTIONS_SERVICE.
 */
public final class RestrictionsManagerService extends SystemService {

    static final String LOG_TAG = "RestrictionsManagerService";
    static final boolean DEBUG = false;

    private final RestrictionsManagerImpl mRestrictionsManagerImpl;

    public RestrictionsManagerService(Context context) {
        super(context);
        mRestrictionsManagerImpl = new RestrictionsManagerImpl(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.RESTRICTIONS_SERVICE, mRestrictionsManagerImpl);
    }

    class RestrictionsManagerImpl extends IRestrictionsManager.Stub {
        final Context mContext;
        private final IUserManager mUm;
        private final IDevicePolicyManager mDpm;
        private final DevicePolicyManagerInternal mDpmInternal;

        public RestrictionsManagerImpl(Context context) {
            mContext = context;
            mUm = (IUserManager) getBinderService(Context.USER_SERVICE);
            mDpm = (IDevicePolicyManager) getBinderService(Context.DEVICE_POLICY_SERVICE);
            mDpmInternal = getLocalService(DevicePolicyManagerInternal.class);
        }

        @Override
        @Deprecated
        public Bundle getApplicationRestrictions(String packageName) throws RemoteException {
            return mUm.getApplicationRestrictions(packageName);
        }

        @Override
        public List<Bundle> getApplicationRestrictionsPerAdminForUser(
                @UserIdInt int userId, String packageName) throws RemoteException {
            if (mDpmInternal != null) {
                return mDpmInternal.getApplicationRestrictionsPerAdminForUser(packageName, userId);
            }
            return new ArrayList<>();
        }

        @Override
        public boolean hasRestrictionsProvider() throws RemoteException {
            int userHandle = UserHandle.getCallingUserId();
            if (mDpm != null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    return mDpm.getRestrictionsProvider(userHandle) != null;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                return false;
            }
        }

        @Override
        public void requestPermission(final String packageName, final String requestType,
                final String requestId,
                final PersistableBundle requestData) throws RemoteException {
            if (DEBUG) {
                Log.i(LOG_TAG, "requestPermission");
            }
            int callingUid = Binder.getCallingUid();
            int userHandle = UserHandle.getUserId(callingUid);
            if (mDpm != null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    ComponentName restrictionsProvider =
                            mDpm.getRestrictionsProvider(userHandle);
                    // Check if there is a restrictions provider
                    if (restrictionsProvider == null) {
                        throw new IllegalStateException(
                            "Cannot request permission without a restrictions provider registered");
                    }
                    // Check that the packageName matches the caller.
                    enforceCallerMatchesPackage(callingUid, packageName, "Package name does not" +
                            " match caller ");
                    // Prepare and broadcast the intent to the provider
                    Intent intent = new Intent(RestrictionsManager.ACTION_REQUEST_PERMISSION);
                    intent.setComponent(restrictionsProvider);
                    intent.putExtra(RestrictionsManager.EXTRA_PACKAGE_NAME, packageName);
                    intent.putExtra(RestrictionsManager.EXTRA_REQUEST_TYPE, requestType);
                    intent.putExtra(RestrictionsManager.EXTRA_REQUEST_ID, requestId);
                    intent.putExtra(RestrictionsManager.EXTRA_REQUEST_BUNDLE, requestData);
                    mContext.sendBroadcastAsUser(intent, new UserHandle(userHandle));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public Intent createLocalApprovalIntent() throws RemoteException {
            if (DEBUG) {
                Log.i(LOG_TAG, "requestPermission");
            }
            final int userHandle = UserHandle.getCallingUserId();
            if (mDpm != null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    ComponentName restrictionsProvider =
                            mDpm.getRestrictionsProvider(userHandle);
                    // Check if there is a restrictions provider
                    if (restrictionsProvider == null) {
                        throw new IllegalStateException(
                            "Cannot request permission without a restrictions provider registered");
                    }
                    String providerPackageName = restrictionsProvider.getPackageName();
                    Intent intent = new Intent(RestrictionsManager.ACTION_REQUEST_LOCAL_APPROVAL);
                    intent.setPackage(providerPackageName);
                    ResolveInfo ri = AppGlobals.getPackageManager().resolveIntent(intent,
                            null /* resolvedType */, 0 /* flags */, userHandle);
                    if (ri != null && ri.activityInfo != null && ri.activityInfo.exported) {
                        intent.setComponent(new ComponentName(ri.activityInfo.packageName,
                                ri.activityInfo.name));
                        return intent;
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
            return null;
        }

        @Override
        public void notifyPermissionResponse(String packageName, PersistableBundle response)
                throws RemoteException {
            // Check caller
            int callingUid = Binder.getCallingUid();
            int userHandle = UserHandle.getUserId(callingUid);
            if (mDpm != null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    ComponentName permProvider = mDpm.getRestrictionsProvider(userHandle);
                    if (permProvider == null) {
                        throw new SecurityException("No restrictions provider registered for user");
                    }
                    enforceCallerMatchesPackage(callingUid, permProvider.getPackageName(),
                            "Restrictions provider does not match caller ");

                    // Post the response to target package
                    Intent responseIntent = new Intent(
                            RestrictionsManager.ACTION_PERMISSION_RESPONSE_RECEIVED);
                    responseIntent.setPackage(packageName);
                    responseIntent.putExtra(RestrictionsManager.EXTRA_RESPONSE_BUNDLE, response);
                    mContext.sendBroadcastAsUser(responseIntent, new UserHandle(userHandle));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        private void enforceCallerMatchesPackage(int callingUid, String packageName,
                String message) {
            try {
                String[] pkgs = AppGlobals.getPackageManager().getPackagesForUid(callingUid);
                if (pkgs != null) {
                    if (!ArrayUtils.contains(pkgs, packageName)) {
                        throw new SecurityException(message + callingUid);
                    }
                }
            } catch (RemoteException re) {
                // Shouldn't happen
            }
        }
    }
}

/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.telephony.ims;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Binder;

class RcsPermissions {
    static void checkReadPermissions(Context context, String callingPackage) {
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();

        context.enforcePermission(Manifest.permission.READ_SMS, pid, uid, null);

        checkOp(context, uid, callingPackage, AppOpsManager.OP_READ_SMS);
    }

    static void checkWritePermissions(Context context, String callingPackage) {
        int uid = Binder.getCallingUid();

        checkOp(context, uid, callingPackage, AppOpsManager.OP_WRITE_SMS);
    }

    /**
     * Notes the provided op, but throws even if the op mode is {@link AppOpsManager.MODE_IGNORED}.
     * <p>
     * {@link AppOpsManager.OP_WRITE_SMS} defaults to {@link AppOpsManager.MODE_IGNORED} to avoid
     * crashing applications written before the app op was introduced. Since this is a new API,
     * consumers should be aware of the permission requirements, and we should be safe to throw a
     * {@link SecurityException} instead of providing a dummy value (which could cause unexpected
     * application behavior and possible loss of user data). {@link AppOpsManager.OP_READ_SMS} is
     * not normally in {@link AppOpsManager.MODE_IGNORED}, but we maintain the same behavior for
     * consistency with handling of write permissions.
     */
    private static void checkOp(Context context, int uid, String callingPackage, int op) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);

        int mode = appOps.noteOp(op, uid, callingPackage);

        if (mode != AppOpsManager.MODE_ALLOWED) {
            throw new SecurityException(
                    AppOpsManager.opToName(op) + " not allowed for " + callingPackage);
        }
    }
}

/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.telephony;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;

import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A debug service that will dump telephony's state
 *
 * Currently this "Service" has a proxy in the phone app
 * com.android.phone.TelephonyDebugService which actually
 * invokes the dump method.
 */
public class DebugService {
    private static String TAG = "DebugService";
    private final Context mContext;

    /** Constructor */
    public DebugService(@NonNull Context context) {
        log("DebugService:");
        mContext = context;
    }

    /**
     * Dump the state of various objects, add calls to other objects as desired.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (args != null && args.length > 0) {
            switch (args[0]) {
                case "--saveatoms":
                    if (Build.IS_DEBUGGABLE) {
                        log("Saving atoms..");
                        PhoneFactory.getMetricsCollector().flushAtomsStorage();
                    }
                    return;
                case "--clearatoms":
                    if (Build.IS_DEBUGGABLE || mContext.checkCallingOrSelfPermission(
                            android.Manifest.permission.DUMP)
                            == PackageManager.PERMISSION_GRANTED) {
                        log("Clearing atoms..");
                        PhoneFactory.getMetricsCollector().clearAtomsStorage();

                        if (args.length > 1 && "--saveFileImmediately".equals(args[1])) {
                            log("Setting save-immediately mode to true after clearing.");
                            PhoneFactory.getMetricsCollector().setSaveFileImmediately(true);
                        } else {
                            log("Restoring default save-delay mode after clearing.");
                            PhoneFactory.getMetricsCollector().setSaveFileImmediately(false);
                        }
                    } else {
                        pw.println("ERROR: Failed to clear atom, does not have permission.");
                        logw("Clearing atoms.. failed, does not have permission");
                    }
                    return;
                case "--pullAtomsBase64":
                    if (Build.IS_DEBUGGABLE || mContext.checkCallingOrSelfPermission(
                            android.Manifest.permission.DUMP)
                            == PackageManager.PERMISSION_GRANTED) {
                        log("Pulling atoms..");
                        try {
                            // This method should be implemented to get the real atom proto
                            // from PersistAtomsStorage and serialize it to bytes.
                            byte[] atomProtoBytes =
                                    PhoneFactory.getMetricsCollector().getAtomsProtoBytes();

                            if (atomProtoBytes != null) {
                                // Encode byte array to Base64 String and print it.
                                String base64String = Base64.encodeToString(atomProtoBytes,
                                        Base64.NO_WRAP);
                                pw.println(base64String);
                            }

                            if (args.length > 1 && "--clearAtoms".equals(args[1])) {
                                log("clear atoms after pulling.");
                                PhoneFactory.getMetricsCollector().clearAtomsStorage();
                                PhoneFactory.getMetricsCollector().setSaveFileImmediately(true);
                            }
                        } catch (Exception e) {
                            Rlog.e(TAG, "Failed to get/encode atom data", e);
                        }
                    } else {
                        logw("Pulling atoms.. failed does not have permission");
                    }
                    return;
            }
        }
        log("Dump telephony.");
        PhoneFactory.dump(fd, pw, args);
    }

    private static void log(String s) {
        Rlog.d(TAG, "DebugService " + s);
    }

    private static void logw(String s) {
        Rlog.w(TAG, "DebugService " + s);
    }
}

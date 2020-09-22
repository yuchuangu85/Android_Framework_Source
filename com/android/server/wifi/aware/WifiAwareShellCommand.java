/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi.aware;

import android.os.BasicShellCommandHandler;
import android.os.Binder;
import android.text.TextUtils;
import android.util.Log;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Interprets and executes 'adb shell cmd wifiaware [args]'.
 */
public class WifiAwareShellCommand extends BasicShellCommandHandler {
    private static final String TAG = "WifiAwareShellCommand";

    private Map<String, DelegatedShellCommand> mDelegatedCommands = new HashMap<>();

    /**
     * Register an delegated command interpreter for the specified 'command'. Each class can
     * interpret and execute their own commands.
     */
    public void register(String command, DelegatedShellCommand shellCommand) {
        if (mDelegatedCommands.containsKey(command)) {
            Log.e(TAG, "register: overwriting existing command -- '" + command + "'");
        }

        mDelegatedCommands.put(command, shellCommand);
    }

    @Override
    public int onCommand(String cmd) {
        checkRootPermission();

        final PrintWriter pw = getErrPrintWriter();
        try {
            if ("reset".equals(cmd)) {
                for (DelegatedShellCommand dsc: mDelegatedCommands.values()) {
                    dsc.onReset();
                }
                return 0;
            } else {
                DelegatedShellCommand delegatedCmd = null;
                if (!TextUtils.isEmpty(cmd)) {
                    delegatedCmd = mDelegatedCommands.get(cmd);
                }

                if (delegatedCmd != null) {
                    return delegatedCmd.onCommand(this);
                } else {
                    return handleDefaultCommands(cmd);
                }
            }
        } catch (Exception e) {
            pw.println("Exception: " + e);
        }
        return -1;
    }

    private void checkRootPermission() {
        final int uid = Binder.getCallingUid();
        if (uid == 0) {
            // Root can do anything.
            return;
        }
        throw new SecurityException("Uid " + uid + " does not have access to wifiaware commands");
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();

        pw.println("Wi-Fi Aware (wifiaware) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  reset");
        pw.println("    Reset parameters to default values.");
        for (Map.Entry<String, DelegatedShellCommand> sce: mDelegatedCommands.entrySet()) {
            sce.getValue().onHelp(sce.getKey(), this);
        }
        pw.println();
    }

    /**
     * Interface that delegated command targets must implement. They are passed the parent shell
     * command (the real command interpreter) from which they can obtain arguments.
     */
    public interface DelegatedShellCommand {
        /**
         * Execute the specified command. Use the parent shell to obtain arguments. Note that the
         * first argument (which specified the delegated shell) has already been extracted.
         */
        int onCommand(BasicShellCommandHandler parentShell);

        /**
         * Reset all parameters to their default values.
         */
        void onReset();

        /**
         * Print out help for the delegated command. The name of the delegated command is passed
         * as a first argument as an assist (prevents hard-coding of that string in multiple
         * places).
         */
        void onHelp(String command, BasicShellCommandHandler parentShell);

    }
}

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

package com.android.server.wifi;

import android.app.AppGlobals;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Interprets and executes 'adb shell cmd wifi [args]'.
 *
 * To add new commands:
 * - onCommand: Add a case "<command>" execute. Return a 0
 *   if command executed successfully.
 * - onHelp: add a description string.
 *
 * If additional state objects are necessary add them to the
 * constructor.
 *
 * Permissions: currently root permission is required for all
 * commands. If the requirement needs to be relaxed then modify
 * the onCommand method to check for specific permissions on
 * individual commands.
 */
public class WifiShellCommand extends ShellCommand {
    private final ClientModeImpl mClientModeImpl;
    private final WifiLockManager mWifiLockManager;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final WifiConfigManager mWifiConfigManager;
    private final IPackageManager mPM;

    WifiShellCommand(WifiInjector wifiInjector) {
        mClientModeImpl = wifiInjector.getClientModeImpl();
        mWifiLockManager = wifiInjector.getWifiLockManager();
        mWifiNetworkSuggestionsManager = wifiInjector.getWifiNetworkSuggestionsManager();
        mWifiConfigManager = wifiInjector.getWifiConfigManager();
        mPM = AppGlobals.getPackageManager();
    }

    @Override
    public int onCommand(String cmd) {
        checkRootPermission();

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd != null ? cmd : "") {
                case "set-ipreach-disconnect": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'set-ipreach-disconnect' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    mClientModeImpl.setIpReachabilityDisconnectEnabled(enabled);
                    return 0;
                }
                case "get-ipreach-disconnect":
                    pw.println("IPREACH_DISCONNECT state is "
                            + mClientModeImpl.getIpReachabilityDisconnectEnabled());
                    return 0;
                case "set-poll-rssi-interval-msecs":
                    int newPollIntervalMsecs;
                    try {
                        newPollIntervalMsecs = Integer.parseInt(getNextArgRequired());
                    } catch (NumberFormatException e) {
                        pw.println(
                                "Invalid argument to 'set-poll-rssi-interval-msecs' "
                                        + "- must be a positive integer");
                        return -1;
                    }

                    if (newPollIntervalMsecs < 1) {
                        pw.println(
                                "Invalid argument to 'set-poll-rssi-interval-msecs' "
                                        + "- must be a positive integer");
                        return -1;
                    }

                    mClientModeImpl.setPollRssiIntervalMsecs(newPollIntervalMsecs);
                    return 0;
                case "get-poll-rssi-interval-msecs":
                    pw.println("ClientModeImpl.mPollRssiIntervalMsecs = "
                            + mClientModeImpl.getPollRssiIntervalMsecs());
                    return 0;
                case "force-hi-perf-mode": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-hi-perf-mode' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    if (!mWifiLockManager.forceHiPerfMode(enabled)) {
                        pw.println("Command execution failed");
                    }
                    return 0;
                }
                case "force-low-latency-mode": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-low-latency-mode' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    if (!mWifiLockManager.forceLowLatencyMode(enabled)) {
                        pw.println("Command execution failed");
                    }
                    return 0;
                }
                case "network-suggestions-set-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean approved;
                    String nextArg = getNextArgRequired();
                    if ("yes".equals(nextArg)) {
                        approved = true;
                    } else if ("no".equals(nextArg)) {
                        approved = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'network-suggestions-set-user-approved' "
                                        + "- must be 'yes' or 'no'");
                        return -1;
                    }
                    mWifiNetworkSuggestionsManager.setHasUserApprovedForApp(approved, packageName);
                    return 0;
                }
                case "network-suggestions-has-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean hasUserApproved =
                            mWifiNetworkSuggestionsManager.hasUserApprovedForApp(packageName);
                    pw.println(hasUserApproved ? "yes" : "no");
                    return 0;
                }
                case "network-requests-remove-user-approved-access-points": {
                    String packageName = getNextArgRequired();
                    mClientModeImpl.removeNetworkRequestUserApprovedAccessPointsForApp(packageName);
                    return 0;
                }
                case "clear-deleted-ephemeral-networks": {
                    mWifiConfigManager.clearDeletedEphemeralNetworks();
                    return 0;
                }
                case "send-link-probe": {
                    return sendLinkProbe(pw);
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Exception while executing WifiShellCommand: ");
            e.printStackTrace(pw);
        }
        return -1;
    }

    private int sendLinkProbe(PrintWriter pw) throws InterruptedException {
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        mClientModeImpl.probeLink(new WifiNative.SendMgmtFrameCallback() {
            @Override
            public void onAck(int elapsedTimeMs) {
                queue.offer("Link probe succeeded after " + elapsedTimeMs + " ms");
            }

            @Override
            public void onFailure(int reason) {
                queue.offer("Link probe failed with reason " + reason);
            }
        }, -1);

        // block until msg is received, or timed out
        String msg = queue.poll(WificondControl.SEND_MGMT_FRAME_TIMEOUT_MS + 1000,
                TimeUnit.MILLISECONDS);
        if (msg == null) {
            pw.println("Link probe timed out");
        } else {
            pw.println(msg);
        }
        return 0;
    }

    private void checkRootPermission() {
        final int uid = Binder.getCallingUid();
        if (uid == 0) {
            // Root can do anything.
            return;
        }
        throw new SecurityException("Uid " + uid + " does not have access to wifi commands");
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();

        pw.println("Wi-Fi (wifi) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set-ipreach-disconnect enabled|disabled");
        pw.println("    Sets whether CMD_IP_REACHABILITY_LOST events should trigger disconnects.");
        pw.println("  get-ipreach-disconnect");
        pw.println("    Gets setting of CMD_IP_REACHABILITY_LOST events triggering disconnects.");
        pw.println("  set-poll-rssi-interval-msecs <int>");
        pw.println("    Sets the interval between RSSI polls to <int> milliseconds.");
        pw.println("  get-poll-rssi-interval-msecs");
        pw.println("    Gets current interval between RSSI polls, in milliseconds.");
        pw.println("  force-hi-perf-mode enabled|disabled");
        pw.println("    Sets whether hi-perf mode is forced or left for normal operation.");
        pw.println("  force-low-latency-mode enabled|disabled");
        pw.println("    Sets whether low latency mode is forced or left for normal operation.");
        pw.println("  network-suggestions-set-user-approved <package name> yes|no");
        pw.println("    Sets whether network suggestions from the app is approved or not.");
        pw.println("  network-suggestions-has-user-approved <package name>");
        pw.println("    Queries whether network suggestions from the app is approved or not.");
        pw.println("  network-requests-remove-user-approved-access-points <package name>");
        pw.println("    Removes all user approved network requests for the app.");
        pw.println("  clear-deleted-ephemeral-networks");
        pw.println("    Clears the deleted ephemeral networks list.");
        pw.println("  send-link-probe");
        pw.println("    Manually triggers a link probe.");
        pw.println();
    }
}

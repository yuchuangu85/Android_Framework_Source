/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.commands.lowpan;

import android.net.LinkAddress;
import android.net.lowpan.ILowpanInterface;
import android.net.lowpan.LowpanBeaconInfo;
import android.net.lowpan.LowpanCredential;
import android.net.lowpan.LowpanEnergyScanResult;
import android.net.lowpan.LowpanException;
import android.net.lowpan.LowpanIdentity;
import android.net.lowpan.LowpanInterface;
import android.net.lowpan.LowpanManager;
import android.net.lowpan.LowpanProvision;
import android.net.lowpan.LowpanScanner;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.AndroidRuntimeException;
import com.android.internal.os.BaseCommand;
import com.android.internal.util.HexDump;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class LowpanCtl extends BaseCommand {
    private LowpanManager mLowpanManager;
    private LowpanInterface mLowpanInterface;
    private ILowpanInterface mILowpanInterface;
    private String mLowpanInterfaceName;

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        new LowpanCtl().run(args);
    }

    @Override
    public void onShowUsage(PrintStream out) {
        out.println(
                "usage: lowpanctl [options] [subcommand] [subcommand-options]\n"
                        + "options:\n"
                        + "       -I / --interface <iface-name> ..... Interface Name\n"
                        + "subcommands:\n"
                        + "       lowpanctl status\n"
                        + "       lowpanctl form\n"
                        + "       lowpanctl join\n"
                        + "       lowpanctl attach\n"
                        + "       lowpanctl leave\n"
                        + "       lowpanctl enable\n"
                        + "       lowpanctl disable\n"
                        + "       lowpanctl show-credential\n"
                        + "       lowpanctl scan\n"
                        + "       lowpanctl reset\n"
                        + "       lowpanctl list\n"
                        + "\n"
                        + "usage: lowpanctl [options] join/form/attach [network-name]\n"
                        + "subcommand-options:\n"
                        + "       --name <network-name> ............. Network Name\n"
                        + "       -p / --panid <panid> .............. PANID\n"
                        + "       -c / --channel <channel> .......... Channel Index\n"
                        + "       -x / --xpanid <xpanid> ............ XPANID\n"
                        + "       -k / --master-key <master-key> .... Master Key\n"
                        + "       --master-key-index <key-index> .... Key Index\n"
                        + "\n"
                        + "usage: lowpanctl [options] show-credential\n"
                        + "subcommand-options:\n"
                        + "       -r / --raw ........................ Print only key contents\n"
                        + "\n");
    }

    private class CommandErrorException extends AndroidRuntimeException {
        public CommandErrorException(String desc) {
            super(desc);
        }
    }

    private class ArgumentErrorException extends IllegalArgumentException {
        public ArgumentErrorException(String desc) {
            super(desc);
        }
    }

    private void throwCommandError(String desc) {
        throw new CommandErrorException(desc);
    }

    private void throwArgumentError(String desc) {
        throw new ArgumentErrorException(desc);
    }

    private LowpanManager getLowpanManager() {
        if (mLowpanManager == null) {
            mLowpanManager = LowpanManager.getManager();

            if (mLowpanManager == null) {
                System.err.println(NO_SYSTEM_ERROR_CODE);
                throwCommandError("Can't connect to LoWPAN service; is the service running?");
            }
        }
        return mLowpanManager;
    }

    private LowpanInterface getLowpanInterface() {
        if (mLowpanInterface == null) {
            if (mLowpanInterfaceName == null) {
                String interfaceArray[] = getLowpanManager().getInterfaceList();
                if (interfaceArray.length != 0) {
                    mLowpanInterfaceName = interfaceArray[0];
                } else {
                    throwCommandError("No LoWPAN interfaces are present");
                }
            }
            mLowpanInterface = getLowpanManager().getInterface(mLowpanInterfaceName);

            if (mLowpanInterface == null) {
                throwCommandError("Unknown LoWPAN interface \"" + mLowpanInterfaceName + "\"");
            }
        }
        return mLowpanInterface;
    }

    private ILowpanInterface getILowpanInterface() {
        if (mILowpanInterface == null) {
            mILowpanInterface = getLowpanInterface().getService();
        }
        return mILowpanInterface;
    }

    @Override
    public void onRun() throws Exception {
        try {
            String op;
            while ((op = nextArgRequired()) != null) {
                if (op.equals("-I") || op.equals("--interface")) {
                    mLowpanInterfaceName = nextArgRequired();
                } else if (op.equals("-h") || op.equals("--help") || op.equals("help")) {
                    onShowUsage(System.out);
                    break;
                } else if (op.startsWith("-")) {
                    throwArgumentError("Unrecognized argument \"" + op + "\"");
                } else if (op.equals("status") || op.equals("stat")) {
                    runStatus();
                    break;
                } else if (op.equals("scan") || op.equals("netscan") || op.equals("ns")) {
                    runNetScan();
                    break;
                } else if (op.equals("attach")) {
                    runAttach();
                    break;
                } else if (op.equals("enable")) {
                    runEnable();
                    break;
                } else if (op.equals("disable")) {
                    runDisable();
                    break;
                } else if (op.equals("show-credential")) {
                    runShowCredential();
                    break;
                } else if (op.equals("join")) {
                    runJoin();
                    break;
                } else if (op.equals("form")) {
                    runForm();
                    break;
                } else if (op.equals("leave")) {
                    runLeave();
                    break;
                } else if (op.equals("energyscan") || op.equals("energy") || op.equals("es")) {
                    runEnergyScan();
                    break;
                } else if (op.equals("list") || op.equals("ls")) {
                    runListInterfaces();
                    break;
                } else if (op.equals("reset")) {
                    runReset();
                    break;
                } else {
                    throwArgumentError("Unrecognized argument \"" + op + "\"");
                    break;
                }
            }
        } catch (ServiceSpecificException x) {
            System.out.println(
                    "ServiceSpecificException: " + x.errorCode + ": " + x.getLocalizedMessage());
            throw x;

        } catch (ArgumentErrorException x) {
            // Rethrow so we get syntax help.
            throw x;

        } catch (IllegalArgumentException x) {
            // This was an argument exception that wasn't an
            // argument error. We dump our stack trace immediately
            // because this might not be from a command line argument.
            x.printStackTrace(System.err);
            System.exit(1);

        } catch (CommandErrorException x) {
            // Command errors are normal errors that just
            // get printed out without any fanfare.
            System.out.println("error: " + x.getLocalizedMessage());
            System.exit(1);
        }
    }

    private void runReset() throws LowpanException {
        getLowpanInterface().reset();
    }

    private void runEnable() throws LowpanException {
        getLowpanInterface().setEnabled(true);
    }

    private void runDisable() throws LowpanException {
        getLowpanInterface().setEnabled(false);
    }

    private LowpanProvision getProvisionFromArgs(boolean credentialRequired) {
        LowpanProvision.Builder builder = new LowpanProvision.Builder();
        Map<String, Object> properties = new HashMap();
        LowpanIdentity.Builder identityBuilder = new LowpanIdentity.Builder();
        LowpanCredential credential = null;
        String arg;
        byte[] masterKey = null;
        int masterKeyIndex = 0;
        boolean hasName = false;

        while ((arg = nextArg()) != null) {
            if (arg.equals("--name")) {
                identityBuilder.setName(nextArgRequired());
                hasName = true;
            } else if (arg.equals("-p") || arg.equals("--panid")) {
                identityBuilder.setPanid(Integer.decode(nextArgRequired()));
            } else if (arg.equals("-c") || arg.equals("--channel")) {
                identityBuilder.setChannel(Integer.decode(nextArgRequired()));
            } else if (arg.equals("-x") || arg.equals("--xpanid")) {
                identityBuilder.setXpanid(HexDump.hexStringToByteArray(nextArgRequired()));
            } else if (arg.equals("-k") || arg.equals("--master-key")) {
                masterKey = HexDump.hexStringToByteArray(nextArgRequired());
            } else if (arg.equals("--master-key-index")) {
                masterKeyIndex = Integer.decode(nextArgRequired());
            } else if (arg.startsWith("-") || hasName) {
                throwArgumentError("Unrecognized argument \"" + arg + "\"");
            } else {
                // This is the network name
                identityBuilder.setName(arg);
                hasName = true;
            }
        }

        if (credential == null && masterKey != null) {
            if (masterKeyIndex == 0) {
                credential = LowpanCredential.createMasterKey(masterKey);
            } else {
                credential = LowpanCredential.createMasterKey(masterKey, masterKeyIndex);
            }
        }

        if (credential != null) {
            builder.setLowpanCredential(credential);
        } else if (credentialRequired) {
            throwArgumentError("No credential (like a master key) was specified!");
        }

        return builder.setLowpanIdentity(identityBuilder.build()).build();
    }

    private void runAttach() throws LowpanException {
        LowpanProvision provision = getProvisionFromArgs(true);

        System.out.println(
                "Attaching to " + provision.getLowpanIdentity() + " with provided credential");

        getLowpanInterface().attach(provision);

        System.out.println("Attached.");
    }

    private void runLeave() throws LowpanException {
        getLowpanInterface().leave();
    }

    private void runJoin() throws LowpanException {
        LowpanProvision provision = getProvisionFromArgs(true);

        System.out.println(
                "Joining " + provision.getLowpanIdentity() + " with provided credential");

        getLowpanInterface().join(provision);

        System.out.println("Joined.");
    }

    private void runForm() throws LowpanException {
        LowpanProvision provision = getProvisionFromArgs(false);

        if (provision.getLowpanCredential() != null) {
            System.out.println(
                    "Forming " + provision.getLowpanIdentity() + " with provided credential");
        } else {
            System.out.println("Forming " + provision.getLowpanIdentity());
        }

        getLowpanInterface().form(provision);

        System.out.println("Formed.");
    }

    private void runStatus() throws LowpanException, RemoteException {
        LowpanInterface iface = getLowpanInterface();
        StringBuffer sb = new StringBuffer();

        sb.append(iface.getName())
                .append("\t")
                .append(iface.getState());

        if (!iface.isEnabled()) {
            sb.append(" DISABLED");

        } else if (iface.getState() != LowpanInterface.STATE_FAULT) {
            sb.append(" (" + iface.getRole() + ")");

            if (iface.isUp()) {
                sb.append(" UP");
            }

            if (iface.isConnected()) {
                sb.append(" CONNECTED");
            }

            if (iface.isCommissioned()) {
                sb.append(" COMMISSIONED");

                LowpanIdentity identity = getLowpanInterface().getLowpanIdentity();

                if (identity != null) {
                    sb.append("\n\t").append(identity);
                }
            }

            if (iface.isUp()) {
                for (LinkAddress addr : iface.getLinkAddresses()) {
                    sb.append("\n\t").append(addr);
                }
            }
        }

        sb.append("\n");
        System.out.println(sb.toString());
    }

    private void runShowCredential() throws LowpanException, RemoteException {
        LowpanInterface iface = getLowpanInterface();
        boolean raw = false;
        String arg;
        while ((arg = nextArg()) != null) {
            if (arg.equals("--raw") || arg.equals("-r")) {
                raw = true;
            } else {
                throwArgumentError("Unrecognized argument \"" + arg + "\"");
            }
        }

        LowpanCredential credential = iface.getLowpanCredential();
        if (raw) {
            System.out.println(HexDump.toHexString(credential.getMasterKey()));
        } else {
            System.out.println(iface.getName() + "\t" + credential.toSensitiveString());
        }
    }

    private void runListInterfaces() {
        for (String name : getLowpanManager().getInterfaceList()) {
            System.out.println(name);
        }
    }

    private void runNetScan() throws LowpanException, InterruptedException {
        LowpanScanner scanner = getLowpanInterface().createScanner();
        String arg;

        while ((arg = nextArg()) != null) {
            if (arg.equals("-c") || arg.equals("--channel")) {
                scanner.addChannel(Integer.decode(nextArgRequired()));
            } else {
                throwArgumentError("Unrecognized argument \"" + arg + "\"");
            }
        }

        Semaphore semaphore = new Semaphore(1);

        scanner.setCallback(
                new LowpanScanner.Callback() {
                    @Override
                    public void onNetScanBeacon(LowpanBeaconInfo beacon) {
                        System.out.println(beacon.toString());
                    }

                    @Override
                    public void onScanFinished() {
                        semaphore.release();
                    }
                });

        semaphore.acquire();
        scanner.startNetScan();

        // Wait for our scan to complete.
        if (semaphore.tryAcquire(1, 60L, TimeUnit.SECONDS)) {
            semaphore.release();
        } else {
            throwCommandError("Timeout while waiting for scan to complete.");
        }
    }

    private void runEnergyScan() throws LowpanException, InterruptedException {
        LowpanScanner scanner = getLowpanInterface().createScanner();
        String arg;

        while ((arg = nextArg()) != null) {
            if (arg.equals("-c") || arg.equals("--channel")) {
                scanner.addChannel(Integer.decode(nextArgRequired()));
            } else {
                throwArgumentError("Unrecognized argument \"" + arg + "\"");
            }
        }

        Semaphore semaphore = new Semaphore(1);

        scanner.setCallback(
                new LowpanScanner.Callback() {
                    @Override
                    public void onEnergyScanResult(LowpanEnergyScanResult result) {
                        System.out.println(result.toString());
                    }

                    @Override
                    public void onScanFinished() {
                        semaphore.release();
                    }
                });

        semaphore.acquire();
        scanner.startEnergyScan();

        // Wait for our scan to complete.
        if (semaphore.tryAcquire(1, 60L, TimeUnit.SECONDS)) {
            semaphore.release();
        } else {
            throwCommandError("Timeout while waiting for scan to complete.");
        }
    }
}

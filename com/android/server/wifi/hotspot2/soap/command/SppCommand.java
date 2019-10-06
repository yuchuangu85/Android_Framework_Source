/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.hotspot2.soap.command;

import android.annotation.NonNull;
import android.util.Log;

import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Commands that the mobile device is being requested to execute, which is defined in SPP
 * (Subscription Provisioning Protocol).
 *
 * For the details, refer to A.3.2 of Hotspot 2.0 rel2 technical specification.
 */
public class SppCommand {
    private static final String TAG = "PasspointSppCommand";
    private int mSppCommandId;
    private int mExecCommandId = -1;
    private SppCommandData mCommandData;

    /**
     * Marker interface to indicate data used for a SPP(Subscription Provisioning Protocol) command
     */
    public interface SppCommandData {

    }

    /**
     * Commands embedded in sppPostDevDataResponse message for client to take an action.
     */
    public class CommandId {
        public static final int EXEC = 0;
        public static final int ADD_MO = 1;
        public static final int UPDATE_NODE = 2;
        public static final int NO_MO_UPDATE = 3;
    }

    private static final Map<String, Integer> sCommands = new HashMap<>();
    static {
        sCommands.put("exec", CommandId.EXEC);
        sCommands.put("addMO", CommandId.ADD_MO);
        sCommands.put("updateNode", CommandId.UPDATE_NODE);
        sCommands.put("noMOUpdate", CommandId.NO_MO_UPDATE);
    }

    /**
     * Execution types embedded in exec command for client to execute it.
     */
    public class ExecCommandId {
        public static final int BROWSER = 0;
        public static final int GET_CERT = 1;
        public static final int USE_CLIENT_CERT_TLS = 2;
        public static final int UPLOAD_MO = 3;
    }

    private static final Map<String, Integer> sExecs = new HashMap<>();
    static {
        sExecs.put("launchBrowserToURI", ExecCommandId.BROWSER);
        sExecs.put("getCertificate", ExecCommandId.GET_CERT);
        sExecs.put("useClientCertTLS", ExecCommandId.USE_CLIENT_CERT_TLS);
        sExecs.put("uploadMO", ExecCommandId.UPLOAD_MO);
    }

    private SppCommand(PropertyInfo soapResponse) throws IllegalArgumentException {
        if (!sCommands.containsKey(soapResponse.getName())) {
            throw new IllegalArgumentException("can't find the command: " + soapResponse.getName());
        }
        mSppCommandId = sCommands.get(soapResponse.getName());

        Log.i(TAG, "command name: " + soapResponse.getName());

        switch(mSppCommandId) {
            case CommandId.EXEC:
                /*
                 * Receipt of this element by a mobile device causes the following command
                 * to be executed.
                 */
                SoapObject subCommand = (SoapObject) soapResponse.getValue();
                if (subCommand.getPropertyCount() != 1) {
                    throw new IllegalArgumentException(
                            "more than one child element found for exec command: "
                                    + subCommand.getPropertyCount());
                }

                PropertyInfo commandInfo = new PropertyInfo();
                subCommand.getPropertyInfo(0, commandInfo);
                if (!sExecs.containsKey(commandInfo.getName())) {
                    throw new IllegalArgumentException(
                            "Unrecognized exec command: " + commandInfo.getName());
                }
                mExecCommandId = sExecs.get(commandInfo.getName());
                Log.i(TAG, "exec command: " +  commandInfo.getName());

                switch (mExecCommandId) {
                    case ExecCommandId.BROWSER:
                        /*
                         * When the mobile device receives this command, it launches its default
                         * browser to the URI contained in this element. The URI must use HTTPS as
                         * the protocol and must contain a FQDN.
                         */
                        mCommandData = BrowserUri.createInstance(commandInfo);
                        break;
                    case ExecCommandId.GET_CERT: //fall-through
                    case ExecCommandId.UPLOAD_MO: //fall-through
                    case ExecCommandId.USE_CLIENT_CERT_TLS: //fall-through
                        /*
                         * Command to mobile to re-negotiate the TLS connection using a client
                         * certificate of the accepted type or Issuer to authenticate with the
                         * Subscription server.
                         */
                    default:
                        mCommandData = null;
                        break;
                }
                break;
            case CommandId.ADD_MO:
                /*
                 * This command causes an management object in the mobile devices management tree
                 * at the specified location to be added.
                 * If there is already a management object at that location, the object is replaced.
                 */
                mCommandData = PpsMoData.createInstance(soapResponse);
                break;
            case CommandId.UPDATE_NODE:
                /*
                 * This command causes the update of an interior node and its child nodes (if any)
                 * at the location specified in the management tree URI attribute. The content of
                 * this element is the MO node XML.
                 */
                break;
            case CommandId.NO_MO_UPDATE:
                /*
                 * This response is used when there is no command to be executed nor update of
                 * any MO required.
                 */
                break;
            default:
                mExecCommandId = -1;
                mCommandData = null;
                break;
        }
    }

    /**
     * Create an instance of {@link SppCommand}
     *
     * @param soapResponse SOAP Response received from server.
     * @return instance of {@link SppCommand}
     */
    public static SppCommand createInstance(@NonNull PropertyInfo soapResponse) {
        SppCommand sppCommand;
        try {
            sppCommand = new SppCommand(soapResponse);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "fails to create an instance: " + e);
            return null;
        }
        return sppCommand;
    }

    public int getSppCommandId() {
        return mSppCommandId;
    }

    public int getExecCommandId() {
        return mExecCommandId;
    }

    public SppCommandData getCommandData() {
        return mCommandData;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) return true;
        if (!(thatObject instanceof SppCommand)) return false;
        SppCommand that = (SppCommand) thatObject;
        return (mSppCommandId == that.getSppCommandId())
                && (mExecCommandId == that.getExecCommandId())
                && Objects.equals(mCommandData, that.getCommandData());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSppCommandId, mExecCommandId, mCommandData);
    }

    @Override
    public String toString() {
        return "SppCommand{"
                + "mSppCommandId=" + mSppCommandId
                + ", mExecCommandId=" + mExecCommandId
                + ", mCommandData=" + mCommandData
                + "}";
    }
}

/*
 * Copyright (C) 2010-2016 The Android Open Source Project
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

package android.bluetooth;

import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.IBinder;

import com.android.bluetooth.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Public APIs for the Bluetooth Profiles.
 *
 * <p>Clients should call {@link BluetoothAdapter#getProfileProxy}, to get the Profile Proxy. Each
 * public profile implements this interface.
 */
public interface BluetoothProfile {

    /**
     * Extra for the connection state intents of the individual profiles.
     *
     * <p>This extra represents the current connection state of the profile of the Bluetooth device.
     */
    @SuppressLint("ActionValue")
    String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";

    /**
     * Extra for the connection state intents of the individual profiles.
     *
     * <p>This extra represents the previous connection state of the profile of the Bluetooth
     * device.
     */
    @SuppressLint("ActionValue")
    String EXTRA_PREVIOUS_STATE = "android.bluetooth.profile.extra.PREVIOUS_STATE";

    /**
     * Extra for the {@link BluetoothProfile} that the intent applies to.
     *
     * <p>This extra represents the Bluetooth profile that the intent applies to.
     */
    @SuppressLint("ActionValue")
    String EXTRA_PROFILE = "android.bluetooth.profile.extra.PROFILE";

    /** The profile is in disconnected state */
    int STATE_DISCONNECTED = 0;

    /** The profile is in connecting state */
    int STATE_CONNECTING = 1;

    /** The profile is in connected state */
    int STATE_CONNECTED = 2;

    /** The profile is in disconnecting state */
    int STATE_DISCONNECTING = 3;

    @Hide
    @IntDef({
        STATE_DISCONNECTED,
        STATE_CONNECTING,
        STATE_CONNECTED,
        STATE_DISCONNECTING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BtProfileState {}

    /** Headset and Handsfree profile */
    int HEADSET = 1;

    /** Advanced Audio Distribution Profile (A2DP) */
    int A2DP = 2;

    /**
     * Health Profile
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New apps should
     *     use Bluetooth Low Energy based solutions such as {@link BluetoothGatt}, {@link
     *     BluetoothAdapter#listenUsingL2capChannel()}, or {@link
     *     BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated int HEALTH = 3;

    /** Human Interface Device (HID) Host */
    @Hide @SystemApi int HID_HOST = 4;

    /** Personal Area Networking Profile (PAN) */
    @Hide @SystemApi int PAN = 5;

    /** Phone Book Access Profile (PBAP) */
    @Hide @SystemApi int PBAP = 6;

    /** Generic Attribute Profile (GATT) */
    int GATT = 7;

    /** Generic Attribute Profile (GATT) Server */
    int GATT_SERVER = 8;

    /** Message Access Profile (MAP) */
    @Hide @SystemApi int MAP = 9;

    /** SIM Access Profile (SAP) */
    int SAP = 10;

    /** Advanced Audio Distribution Profile (A2DP) Sink */
    @Hide @SystemApi int A2DP_SINK = 11;

    /** Audio/Video Remote Control Profile (AVRCP) Controller */
    @Hide @SystemApi int AVRCP_CONTROLLER = 12;

    /** Audio/Video Remote Control Profile (AVRCP) Target */
    @Hide int AVRCP = 13;

    /** Headset Client - HFP HF Role */
    @Hide @SystemApi int HEADSET_CLIENT = 16;

    /** Phone Book Access Profile (PBAP) Client */
    @Hide @SystemApi int PBAP_CLIENT = 17;

    /** Message Access Profile (MAP) Messaging Client Equipment (MCE) */
    @Hide @SystemApi int MAP_CLIENT = 18;

    /** Human Interface Device (HID) Device */
    int HID_DEVICE = 19;

    /** Object Push Profile (OPP) */
    @Hide @SystemApi int OPP = 20;

    /** Hearing Aid Device */
    int HEARING_AID = 21;

    /** LE Audio Device */
    int LE_AUDIO = 22;

    /** Volume Control Profile (VCP) */
    @Hide @SystemApi int VOLUME_CONTROL = 23;

    /** Media Control Profile (MCP) server */
    @Hide int MCP_SERVER = 24;

    /** Coordinated Set Identification Profile (CSIP) set coordinator */
    int CSIP_SET_COORDINATOR = 25;

    /** LE Audio Broadcast Source */
    @Hide @SystemApi int LE_AUDIO_BROADCAST = 26;

    /** Telephone Bearer Service (TBS) from Call Control Profile (CCP) */
    @Hide int LE_CALL_CONTROL = 27;

    /*
     * Hearing Access Profile (HAP) Client
     *
     */
    int HAP_CLIENT = 28;

    /** LE Audio Broadcast Assistant */
    @Hide @SystemApi int LE_AUDIO_BROADCAST_ASSISTANT = 29;

    /** Battery Service (BAS) */
    @Hide int BATTERY = 30;

    /** Gaming Audio Profile (GMAP) */
    @Hide int GMAP = 31;

    /** Voice Assistant Profile and Service */
    @Hide int VAP_SERVER = 32;

    /** Le Audio Server Profile */
    @FlaggedApi(Flags.FLAG_LEAUDIO_PERIPHERAL_FEATURE)
    @Hide
    @SystemApi
    int LE_AUDIO_PERIPHERAL = 33;

    /** Telephony and Media Audio Profile Service */
    @Hide int TMAP_SERVER = 34;

    /** Media Control Profile (MCP) Client */
    @Hide int MCP_CLIENT = 35;

    /** Volume Control Profile (VCP) Renderer */
    @Hide int VCP_RENDERER = 36;

    /**
     * Gets the maximum profile ID.
     *
     * <p>The value returned is dependent on feature flags.
     *
     * @return the maximum profile ID.
     */
    @Hide
    static int getMaxProfileId() {
        /* Return value dependent on feature flags */
        if (Flags.leaudioPeripheralVcpLinkAbstractionLayer()) {
            return VCP_RENDERER;
        }

        if (Flags.leaudioPeripheralMcpLinkAbstractionLayer()) {
            return MCP_CLIENT;
        }

        if (Flags.leaudioCentralizeTmap()) {
            return TMAP_SERVER;
        }

        if (Flags.leaudioPeripheralFeature()) {
            return LE_AUDIO_PERIPHERAL;
        }

        /* Max profile ID. This value should be updated whenever a new profile is added to match the
         * largest value assigned to a profile.
         */
        return VAP_SERVER;
    }

    /** Max profile ID. */
    @Hide int MAX_PROFILE_ID = getMaxProfileId();

    /**
     * Default priority for devices that we try to auto-connect to and allow incoming connections
     * for the profile
     */
    @Hide
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    int PRIORITY_AUTO_CONNECT = 1000;

    /**
     * Default priority for devices that allow incoming and outgoing connections for the profile
     *
     * @deprecated Replaced with {@link #CONNECTION_POLICY_ALLOWED}
     */
    @Hide @Deprecated @SystemApi int PRIORITY_ON = 100;

    /**
     * Default priority for devices that does not allow incoming connections and outgoing
     * connections for the profile.
     *
     * @deprecated Replaced with {@link #CONNECTION_POLICY_FORBIDDEN}
     */
    @Hide @Deprecated @SystemApi int PRIORITY_OFF = 0;

    /** Default priority when not set or when the device is unpaired */
    @Hide @UnsupportedAppUsage int PRIORITY_UNDEFINED = -1;

    @Hide
    @IntDef(
            prefix = "CONNECTION_POLICY_",
            value = {
                CONNECTION_POLICY_ALLOWED,
                CONNECTION_POLICY_FORBIDDEN,
                CONNECTION_POLICY_UNKNOWN
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConnectionPolicy {}

    /**
     * Default connection policy for devices that allow incoming and outgoing connections for the
     * profile
     */
    @Hide @SystemApi int CONNECTION_POLICY_ALLOWED = 100;

    /**
     * Default connection policy for devices that do not allow incoming or outgoing connections for
     * the profile.
     */
    @Hide @SystemApi int CONNECTION_POLICY_FORBIDDEN = 0;

    /** Default connection policy when not set or when the device is unpaired */
    @Hide @SystemApi int CONNECTION_POLICY_UNKNOWN = -1;

    /**
     * Get connected devices for this specific profile.
     *
     * <p>Return the set of devices which are in state {@link #STATE_CONNECTED}
     *
     * @return List of devices. The list will be empty on error.
     */
    List<BluetoothDevice> getConnectedDevices();

    /**
     * Get a list of devices that match any of the given connection states.
     *
     * <p>If none of the devices match any of the given states, an empty list will be returned.
     *
     * @param states Array of states. States can be one of {@link #STATE_CONNECTED}, {@link
     *     #STATE_CONNECTING}, {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING},
     * @return List of devices. The list will be empty on error.
     */
    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states);

    /**
     * Get the current connection state of the profile
     *
     * @param device Remote bluetooth device.
     * @return State of the profile connection. One of {@link #STATE_CONNECTED}, {@link
     *     #STATE_CONNECTING}, {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING}
     */
    @BtProfileState
    int getConnectionState(BluetoothDevice device);

    /**
     * Called by the BluetoothAdapter when the Bluetooth service is connected with a Binder instance
     * corresponding to the service associated with the profile
     */
    @Hide
    void onServiceConnected(IBinder service);

    /** Called by the BluetoothAdapter when the Bluetooth service connection has been lost */
    @Hide
    void onServiceDisconnected();

    /** Get the BluetoothAdapter that created this proxy */
    @Hide
    BluetoothAdapter getAdapter();

    /**
     * An interface for notifying BluetoothProfile IPC clients when they have been connected or
     * disconnected to the service.
     */
    public interface ServiceListener {
        /**
         * Called to notify the client when the proxy object has been connected to the service.
         *
         * @param profile - One of {@link #HEADSET} or {@link #A2DP}
         * @param proxy - One of {@link BluetoothHeadset} or {@link BluetoothA2dp}
         */
        @RequiresNoPermission
        void onServiceConnected(int profile, BluetoothProfile proxy);

        /**
         * Called to notify the client that this proxy object has been disconnected from the
         * service.
         *
         * @param profile - One of {@link #HEADSET} or {@link #A2DP}
         */
        @RequiresNoPermission
        void onServiceDisconnected(int profile);
    }

    /**
     * Convert an integer value of connection state into human readable string
     *
     * @param connectionState - One of {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     *     {@link #STATE_CONNECTED}, or {@link #STATE_DISCONNECTED}
     * @return a string representation of the connection state, STATE_UNKNOWN if the state is not
     *     defined
     */
    @Hide
    @SystemApi
    @NonNull
    @RequiresNoPermission
    static String getConnectionStateName(int connectionState) {
        return switch (connectionState) {
            case STATE_DISCONNECTED -> "STATE_DISCONNECTED";
            case STATE_CONNECTING -> "STATE_CONNECTING";
            case STATE_CONNECTED -> "STATE_CONNECTED";
            case STATE_DISCONNECTING -> "STATE_DISCONNECTING";
            default -> "STATE_UNKNOWN";
        };
    }

    /**
     * Convert an integer value of profile ID into human readable string
     *
     * @param profile profile ID
     * @return profile name as String, UNKNOWN_PROFILE if the profile ID is not defined.
     */
    @Hide
    @SystemApi
    @NonNull
    @RequiresNoPermission
    static String getProfileName(int profile) {
        if (Flags.leaudioPeripheralFeature()) {
            if (profile == LE_AUDIO_PERIPHERAL) return "LE_AUDIO_PERIPHERAL";
        }

        return switch (profile) {
            case HEADSET -> "HEADSET";
            case A2DP -> "A2DP";
            case HID_HOST -> "HID_HOST";
            case PAN -> "PAN";
            case PBAP -> "PBAP";
            case GATT -> "GATT";
            case GATT_SERVER -> "GATT_SERVER";
            case MAP -> "MAP";
            case SAP -> "SAP";
            case A2DP_SINK -> "A2DP_SINK";
            case AVRCP_CONTROLLER -> "AVRCP_CONTROLLER";
            case AVRCP -> "AVRCP";
            case HEADSET_CLIENT -> "HEADSET_CLIENT";
            case PBAP_CLIENT -> "PBAP_CLIENT";
            case MAP_CLIENT -> "MAP_CLIENT";
            case HID_DEVICE -> "HID_DEVICE";
            case OPP -> "OPP";
            case HEARING_AID -> "HEARING_AID";
            case LE_AUDIO -> "LE_AUDIO";
            case VOLUME_CONTROL -> "VOLUME_CONTROL";
            case MCP_SERVER -> "MCP_SERVER";
            case CSIP_SET_COORDINATOR -> "CSIP_SET_COORDINATOR";
            case LE_AUDIO_BROADCAST -> "LE_AUDIO_BROADCAST";
            case LE_CALL_CONTROL -> "LE_CALL_CONTROL";
            case HAP_CLIENT -> "HAP_CLIENT";
            case LE_AUDIO_BROADCAST_ASSISTANT -> "LE_AUDIO_BROADCAST_ASSISTANT";
            case BATTERY -> "BATTERY";
            case VAP_SERVER -> "VAP_SERVER";
            case TMAP_SERVER -> "TMAP_SERVER";
            case MCP_CLIENT -> "MCP_CLIENT";
            case VCP_RENDERER -> "VCP_RENDERER";
            default -> "UNKNOWN_PROFILE (" + profile + ")";
        };
    }

    @Hide
    static boolean isValidConnectionPolicy(int connectionPolicy) {
        return connectionPolicy == CONNECTION_POLICY_ALLOWED
                || connectionPolicy == CONNECTION_POLICY_FORBIDDEN;
    }
}

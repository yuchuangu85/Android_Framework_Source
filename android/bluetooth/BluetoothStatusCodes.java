/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.annotation.SystemApi;

import com.android.bluetooth.flags.Flags;

/**
 * A class with constants representing possible return values for Bluetooth APIs. General return
 * values occupy the range 0 to 199. Profile-specific return values occupy the range 200-999.
 * API-specific return values start at 1000. The exception to this is the "UNKNOWN" error code which
 * occupies the max integer value.
 */
public final class BluetoothStatusCodes {
    private BluetoothStatusCodes() {}

    /** Indicates that the API call was successful. */
    public static final int SUCCESS = 0;

    /** Error code indicating that Bluetooth is not enabled. */
    public static final int ERROR_BLUETOOTH_NOT_ENABLED = 1;

    /**
     * Error code indicating that the API call was initiated by neither the system nor the active
     * user.
     */
    public static final int ERROR_BLUETOOTH_NOT_ALLOWED = 2;

    /** Error code indicating that the Bluetooth Device specified is not bonded. */
    public static final int ERROR_DEVICE_NOT_BONDED = 3;

    /**
     * Error code indicating that the Bluetooth Device specified is not connected, but is bonded.
     */
    @Hide public static final int ERROR_DEVICE_NOT_CONNECTED = 4;

    /**
     * Error code indicating that the caller does not have the {@link
     * android.Manifest.permission#BLUETOOTH_CONNECT} permission.
     */
    public static final int ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION = 6;

    /**
     * Error code indicating that the caller does not have the {@link
     * android.Manifest.permission#BLUETOOTH_SCAN} permission.
     */
    @Hide public static final int ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION = 7;

    /**
     * Error code indicating that the profile service is not bound. You can bind a profile service
     * by calling {@link BluetoothAdapter#getProfileProxy(Context, BluetoothProfile.ServiceListener,
     * int)}.
     */
    public static final int ERROR_PROFILE_SERVICE_NOT_BOUND = 9;

    /** Indicates that the feature is supported. */
    public static final int FEATURE_SUPPORTED = 10;

    /** Indicates that the feature is not supported. */
    public static final int FEATURE_NOT_SUPPORTED = 11;

    /** Error code indicating that the device is not the active device for this profile. */
    @Hide @SystemApi public static final int ERROR_NOT_ACTIVE_DEVICE = 12;

    /** Error code indicating that there are no active devices for the profile. */
    @Hide @SystemApi public static final int ERROR_NO_ACTIVE_DEVICES = 13;

    /** Indicates that the Bluetooth profile is not connected to this device. */
    @Hide @SystemApi public static final int ERROR_PROFILE_NOT_CONNECTED = 14;

    /** Error code indicating that the requested operation timed out. */
    @Hide @SystemApi public static final int ERROR_TIMEOUT = 15;

    /** Indicates that some local application caused the event. */
    @Hide @SystemApi public static final int REASON_LOCAL_APP_REQUEST = 16;

    /** Indicate that this change was initiated by the Bluetooth implementation on this device */
    @Hide @SystemApi public static final int REASON_LOCAL_STACK_REQUEST = 17;

    /** Indicate that this change was initiated by the remote device. */
    @Hide @SystemApi public static final int REASON_REMOTE_REQUEST = 18;

    /**
     * Indicates that the local system policy caused the change, such as privacy policy, power
     * management policy, permission changes, and more.
     */
    @Hide @SystemApi public static final int REASON_SYSTEM_POLICY = 19;

    /**
     * Indicates that an underlying hardware incurred some error maybe try again later or toggle the
     * hardware state.
     */
    @Hide @SystemApi public static final int ERROR_HARDWARE_GENERIC = 20;

    /**
     * Indicates that the operation failed due to bad API input parameter that is not covered by
     * other more detailed error code
     */
    @Hide @SystemApi public static final int ERROR_BAD_PARAMETERS = 21;

    /** Indicate that there is not enough local resource to perform the requested operation */
    @Hide @SystemApi public static final int ERROR_LOCAL_NOT_ENOUGH_RESOURCES = 22;

    /**
     * Indicate that a remote device does not have enough resource to perform the requested
     * operation
     */
    @Hide @SystemApi public static final int ERROR_REMOTE_NOT_ENOUGH_RESOURCES = 23;

    /** Indicates that the remote rejected this operation for reasons not covered above */
    @Hide @SystemApi public static final int ERROR_REMOTE_OPERATION_REJECTED = 24;

    /**
     * Indicates that there is an underlying link error between the local and remote devices.
     *
     * <p>Maybe try again later or disconnect and retry.
     */
    @Hide @SystemApi public static final int ERROR_REMOTE_LINK_ERROR = 25;

    /**
     * A generic error code to indicate that the system is already in a target state that an API
     * tries to request.
     *
     * <p>For example, this error code will be delivered if someone tries to stop scanning when scan
     * has already stopped, or start scanning when scan has already started.
     */
    @Hide @SystemApi public static final int ERROR_ALREADY_IN_TARGET_STATE = 26;

    /**
     * Indicates that the requested operation is not supported by the remote device
     *
     * <p>Caller should stop trying this operation
     */
    @Hide @SystemApi public static final int ERROR_REMOTE_OPERATION_NOT_SUPPORTED = 27;

    /**
     * Indicates that the callback is not registered and therefore, this operation is not allowed.
     */
    @Hide @SystemApi public static final int ERROR_CALLBACK_NOT_REGISTERED = 28;

    /**
     * Indicates that there is another active request and therefore, this operation is not allowed.
     */
    @Hide @SystemApi public static final int ERROR_ANOTHER_ACTIVE_REQUEST = 29;

    /** Indicates that the feature status is not configured yet. */
    public static final int FEATURE_NOT_CONFIGURED = 30;

    /** A GATT writeCharacteristic request is not permitted on the remote device. */
    public static final int ERROR_GATT_WRITE_NOT_ALLOWED = 200;

    /** A GATT writeCharacteristic request is issued to a busy remote device. */
    public static final int ERROR_GATT_WRITE_REQUEST_BUSY = 201;

    /** Indicates that the operation is allowed. */
    @Hide @SystemApi public static final int ALLOWED = 400;

    /** Indicates that the operation is not allowed. */
    @Hide @SystemApi public static final int NOT_ALLOWED = 401;

    /**
     * If another application has already requested {@link OobData} then another fetch will be
     * disallowed until the callback is removed.
     */
    @Hide @SystemApi public static final int ERROR_ANOTHER_ACTIVE_OOB_REQUEST = 1000;

    /**
     * Indicates that the ACL disconnected due to an explicit request from the local device.
     *
     * <p>Example cause: This is a normal disconnect reason, e.g., user/app initiates disconnection.
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_LOCAL_REQUEST = 1100;

    /**
     * Indicates that the ACL disconnected due to an explicit request from the remote device.
     *
     * <p>Example cause: This is a normal disconnect reason, e.g., user/app initiates disconnection.
     *
     * <p>Example solution: The app can also prompt the user to check their remote device.
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_REMOTE_REQUEST = 1101;

    /**
     * Generic disconnect reason indicating the ACL disconnected due to an error on the local
     * device.
     *
     * <p>Example solution: Prompt the user to check their local device (e.g., phone, car headunit).
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_LOCAL = 1102;

    /**
     * Generic disconnect reason indicating the ACL disconnected due to an error on the remote
     * device.
     *
     * <p>Example solution: Prompt the user to check their remote device (e.g., headset, car
     * headunit, watch).
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_REMOTE = 1103;

    /**
     * Indicates that the ACL disconnected due to a timeout.
     *
     * <p>Example cause: remote device might be out of range.
     *
     * <p>Example solution: Prompt user to verify their remote device is on or in connection/pairing
     * mode.
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_TIMEOUT = 1104;

    /**
     * Indicates that the ACL disconnected due to link key issues.
     *
     * <p>Example cause: Devices are either unpaired or remote device is refusing our pairing
     * request.
     *
     * <p>Example solution: Prompt user to unpair and pair again.
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_SECURITY = 1105;

    /**
     * Indicates that the ACL disconnected due to the local device's system policy.
     *
     * <p>Example cause: privacy policy, power management policy, permissions, etc.
     *
     * <p>Example solution: Prompt the user to check settings, or check with their system
     * administrator (e.g. some corp-managed devices do not allow OPP connection).
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_SYSTEM_POLICY = 1106;

    /**
     * Indicates that the ACL disconnected due to resource constraints, either on the local device
     * or the remote device.
     *
     * <p>Example cause: controller is busy, memory limit reached, maximum number of connections
     * reached.
     *
     * <p>Example solution: The app should wait and try again. If still failing, prompt the user to
     * disconnect some devices, or toggle Bluetooth on the local and/or the remote device.
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED = 1107;

    /** Indicates that the ACL disconnected because another ACL connection already exists. */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_CONNECTION_ALREADY_EXISTS = 1108;

    /**
     * Indicates that the ACL disconnected due to incorrect parameters passed in from the app.
     *
     * <p>Example solution: Change parameters and try again. If error persists, the app can report
     * telemetry and/or log the error in a bugreport.
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_BAD_PARAMETERS = 1109;

    /**
     * Indicates that the ACL disconnected due to the adapter suspend feature.
     *
     * <p>Example cause: The system has put the adapter into a suspended state to save power.
     *
     * <p>Example solution: The system will handle reconnection when the suspend state is exited.
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_ADAPTER_SUSPEND = 1110;

    /**
     * Indicates that the ACL disconnected due to a user-initiated action.
     *
     * <p>Example cause: The user selects 'disconnect' from the device settings or quick settings
     * menu.
     */
    @FlaggedApi(Flags.FLAG_ADD_NEW_LOCAL_DISCONNECT_REASON)
    @Hide
    @SystemApi
    public static final int ERROR_DISCONNECT_REASON_USER_REQUEST = 1111;

    /**
     * Indicates that there is already one device for which SCO audio is connected or connecting.
     */
    @Hide @SystemApi public static final int ERROR_AUDIO_DEVICE_ALREADY_CONNECTED = 1116;

    /** Indicates that SCO audio was already not connected for this device. */
    @Hide @SystemApi public static final int ERROR_AUDIO_DEVICE_ALREADY_DISCONNECTED = 1117;

    /** Indicates that there audio route is currently blocked by the system. */
    @Hide @SystemApi public static final int ERROR_AUDIO_ROUTE_BLOCKED = 1118;

    /** Indicates that there is an active call preventing this operation from succeeding. */
    @Hide @SystemApi public static final int ERROR_CALL_ACTIVE = 1119;

    // LE audio related return codes reserved from 1200 to 1300

    /** Indicates that the broadcast ID cannot be found among existing Broadcast Sources. */
    @Hide @SystemApi public static final int ERROR_LE_BROADCAST_INVALID_BROADCAST_ID = 1200;

    /** Indicates that encryption code entered does not meet the specification requirement */
    @Hide @SystemApi public static final int ERROR_LE_BROADCAST_INVALID_CODE = 1201;

    /** Indicates that the source ID cannot be found in the given Broadcast sink device */
    @Hide @SystemApi public static final int ERROR_LE_BROADCAST_ASSISTANT_INVALID_SOURCE_ID = 1202;

    /**
     * Indicates that the same Broadcast Source is already added to the Broadcast Sink
     *
     * <p>Broadcast Source is identified by their advertising SID and broadcast ID
     */
    @Hide @SystemApi public static final int ERROR_LE_BROADCAST_ASSISTANT_DUPLICATE_ADDITION = 1203;

    /** Indicates that the program info in a {@link BluetoothLeAudioContentMetadata} is not valid */
    @Hide @SystemApi public static final int ERROR_LE_CONTENT_METADATA_INVALID_PROGRAM_INFO = 1204;

    /**
     * Indicates that the language code in a {@link BluetoothLeAudioContentMetadata} is not valid
     */
    @Hide @SystemApi public static final int ERROR_LE_CONTENT_METADATA_INVALID_LANGUAGE = 1205;

    /**
     * Indicates that operation failed due to other {@link BluetoothLeAudioContentMetadata} related
     * issues not covered by other reason codes.
     */
    @Hide @SystemApi public static final int ERROR_LE_CONTENT_METADATA_INVALID_OTHER = 1206;

    /** Indicates that provided group ID is invalid for the coordinated set */
    @Hide @SystemApi public static final int ERROR_CSIP_INVALID_GROUP_ID = 1207;

    /** Indicating that CSIP group locked failed due to group member being already locked. */
    @Hide @SystemApi public static final int ERROR_CSIP_GROUP_LOCKED_BY_OTHER = 1208;

    /** Indicating that CSIP device has been lost while being locked. */
    @Hide @SystemApi public static final int ERROR_CSIP_LOCKED_GROUP_MEMBER_LOST = 1209;

    /**
     * Indicates that the set preset name is too long.
     *
     * <p>Example solution: Try using shorter name.
     */
    @Hide @SystemApi public static final int ERROR_HAP_PRESET_NAME_TOO_LONG = 1210;

    /**
     * Indicates that provided preset index parameters is invalid
     *
     * <p>Example solution: Use preset index of a known existing preset.
     */
    @Hide @SystemApi public static final int ERROR_HAP_INVALID_PRESET_INDEX = 1211;

    /**
     * Indicates that LE connection is required but not exist or disconnected.
     *
     * <p>Example solution: create LE connection then retry again.
     */
    @Hide @SystemApi public static final int ERROR_NO_LE_CONNECTION = 1300;

    /** Indicates internal error of distance measurement, such as read RSSI data fail. */
    @Hide @SystemApi public static final int ERROR_DISTANCE_MEASUREMENT_INTERNAL = 1301;

    /** Profile service not found with SDP discovery. */
    @FlaggedApi(Flags.FLAG_A2DP_DISCONNECT_REASON_API)
    public static final int ERROR_SDP_DISCOVERY_FAILED = 1400;

    /** Profile stream connection failed. */
    @FlaggedApi(Flags.FLAG_A2DP_DISCONNECT_REASON_API)
    public static final int ERROR_STREAM_CONNECTION_FAILED = 1401;

    /** Profile connection failed due to role management related issues. */
    @FlaggedApi(Flags.FLAG_A2DP_DISCONNECT_REASON_API)
    public static final int ERROR_ROLE_SWITCH_FAILED = 1402;

    /** AVDTP discovery failed to find available SEPs on the peer device. */
    @FlaggedApi(Flags.FLAG_A2DP_DISCONNECT_REASON_API)
    public static final int ERROR_AVDTP_DISCOVERY_FAILED = 1403;

    /** Profile connection failed from insufficient resources, local or remote. */
    @FlaggedApi(Flags.FLAG_A2DP_DISCONNECT_REASON_API)
    public static final int ERROR_INSUFFICIENT_RESOURCES = 1404;

    /** Rfcomm connection failed. */
    @FlaggedApi(Flags.FLAG_A2DP_DISCONNECT_REASON_API)
    public static final int ERROR_RFCOMM_CONNECTION_FAILED = 1405;

    /**
     * Indicates that the RFCOMM listener could not be started due to the requested UUID already
     * being in use.
     */
    @Hide @SystemApi public static final int RFCOMM_LISTENER_START_FAILED_UUID_IN_USE = 2000;

    /**
     * Indicates that the operation could not be competed because the service record on which the
     * operation was requested on does not exist.
     */
    @Hide @SystemApi
    public static final int RFCOMM_LISTENER_OPERATION_FAILED_NO_MATCHING_SERVICE_RECORD = 2001;

    /**
     * Indicates that the operation could not be completed because the application requesting the
     * operation on the RFCOMM listener was not the one which registered it.
     */
    @Hide @SystemApi public static final int RFCOMM_LISTENER_OPERATION_FAILED_DIFFERENT_APP = 2002;

    /** Indicates that the creation of the underlying BluetoothServerSocket failed. */
    @Hide @SystemApi public static final int RFCOMM_LISTENER_FAILED_TO_CREATE_SERVER_SOCKET = 2003;

    /** Indicates that closing the underlying BluetoothServerSocket failed. */
    @Hide @SystemApi public static final int RFCOMM_LISTENER_FAILED_TO_CLOSE_SERVER_SOCKET = 2004;

    /** Indicates that there is no socket available to retrieve from the given listener. */
    @Hide @SystemApi public static final int RFCOMM_LISTENER_NO_SOCKET_AVAILABLE = 2005;

    /**
     * Error code indicating that this operation is not allowed because the remote device does not
     * support both BR/EDR audio and BLE Audio.
     */
    @Hide @SystemApi public static final int ERROR_NOT_DUAL_MODE_AUDIO_DEVICE = 3000;

    /** Indicates that an unknown error has occurred. */
    public static final int ERROR_UNKNOWN = Integer.MAX_VALUE;
}

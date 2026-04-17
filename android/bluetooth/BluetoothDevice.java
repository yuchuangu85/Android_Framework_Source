/*
 * Copyright (C) 2009 The Android Open Source Project
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

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.bluetooth.BluetoothUtils.callServiceIfEnabling;

import static java.util.Objects.requireNonNull;

import android.annotation.BroadcastBehavior;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.compat.CompatChanges;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresBluetoothLocationPermission;
import android.bluetooth.annotations.RequiresBluetoothScanPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothAdminPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.companion.AssociationRequest;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.IpcDataCache;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.flags.Flags;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Represents a remote Bluetooth device. A {@link BluetoothDevice} lets you create a connection with
 * the respective device or query information about it, such as the name, address, class, and
 * bonding state.
 *
 * <p>This class is really just a thin wrapper for a Bluetooth hardware address. Objects of this
 * class are immutable. Operations on this class are performed on the remote Bluetooth hardware
 * address, using the {@link BluetoothAdapter} that was used to create this {@link BluetoothDevice}.
 *
 * <p>To get a {@link BluetoothDevice}, use {@link BluetoothAdapter#getRemoteDevice(String)
 * BluetoothAdapter.getRemoteDevice(String)} to create one representing a device of a known MAC
 * address (which you can get through device discovery with {@link BluetoothAdapter}) or get one
 * from the set of bonded devices returned by {@link BluetoothAdapter#getBondedDevices()
 * BluetoothAdapter.getBondedDevices()}. You can then open a {@link BluetoothSocket} for
 * communication with the remote device, using {@link #createRfcommSocketToServiceRecord(UUID)} over
 * Bluetooth BR/EDR or using {@link #createL2capChannel(int)} over Bluetooth LE.
 *
 * <p><div class="special reference">
 *
 * <h3>Developer Guides</h3>
 *
 * <p>For more information about using Bluetooth, read the <a href=
 * "{@docRoot}guide/topics/connectivity/bluetooth.html">Bluetooth</a> developer guide. </div>
 *
 * @see BluetoothAdapter
 * @see BluetoothSocket
 */
public final class BluetoothDevice implements Parcelable, Attributable {
    private static final String TAG = BluetoothDevice.class.getSimpleName();

    private static final boolean DBG = Log.isLoggable("bluetooth", Log.DEBUG);

    /** Connection state bitmask disconnected bit as returned by getConnectionState. */
    @Hide public static final int CONNECTION_STATE_DISCONNECTED = 0;

    /** Connection state bitmask connected bit as returned by getConnectionState. */
    @Hide public static final int CONNECTION_STATE_CONNECTED = 1;

    /** Connection state bitmask encrypted BREDR bit as returned by getConnectionState. */
    @Hide public static final int CONNECTION_STATE_ENCRYPTED_BREDR = 2;

    /** Connection state bitmask encrypted LE bit as returned by getConnectionState. */
    @Hide public static final int CONNECTION_STATE_ENCRYPTED_LE = 4;

    /**
     * Sentinel error value for this class. Guaranteed to not equal any other integer constant in
     * this class. Provided as a convenience for functions that require a sentinel error value, for
     * example:
     *
     * <p><code>Intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
     * BluetoothDevice.ERROR)</code>
     */
    public static final int ERROR = Integer.MIN_VALUE;

    /**
     * Broadcast Action: Remote device discovered.
     *
     * <p>Sent when a remote device is found during discovery.
     *
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link #EXTRA_CLASS}. Can
     * contain the extra fields {@link #EXTRA_NAME} and/or {@link #EXTRA_RSSI} and/or {@link
     * #EXTRA_IS_COORDINATED_SET_MEMBER} if they are available.
     *
     * <p>From {@link Build.VERSION_CODES_FULL#BAKLAVA_1}, it contains the extra field {@link
     * #EXTRA_DISCOVERY_RESULT_TYPE}. Based on the discovery result type, it can contain extra
     * fields for UUIDs:
     *
     * <ul>
     *   <li>If {@link #EXTRA_DISCOVERY_RESULT_TYPE} is {@link #DEVICE_TYPE_CLASSIC}, it can contain
     *       {@link #EXTRA_UUID} for BR/EDR UUIDs.
     *   <li>If {@link #EXTRA_DISCOVERY_RESULT_TYPE} is {@link #DEVICE_TYPE_LE}, it can contain
     *       {@link #EXTRA_UUID_LE} for LE UUIDs.
     *   <li>If {@link #EXTRA_DISCOVERY_RESULT_TYPE} is {@link #DEVICE_TYPE_DUAL}, it can contain
     *       {@link #EXTRA_UUID} for BR/EDR UUIDs and {@link #EXTRA_UUID_LE} for LE UUIDs.
     * </ul>
     *
     * <p>{@link #EXTRA_UUID} includes BR/EDR UUIDs in the extended inquiry response (EIR). A {@code
     * null} or absent {@link #EXTRA_UUID} indicates EIR does not include Service Class UUID list.
     * An empty {@link #EXTRA_UUID} indicates EIR includes Service Class UUID filed, but the list is
     * empty.
     *
     * <p>{@link #EXTRA_UUID_LE} includes BLE UUIDs in the BLE advertising data (AD). Specifically,
     * it includes UUIDs present in SERVICE UUID data type and SERVICE DATA data type, as defined by
     * Bluetooth Core Specification Supplement (CSS). A {@code null} or absent {@link
     * #EXTRA_UUID_LE} indicates the AD does not include Service UUID or Service DATA. An empty
     * {@link #EXTRA_UUID} indicates AD includes Service UUID and/or Service DATA, but the list is
     * empty.
     */
    // TODO: Change API to not broadcast RSSI if not available (incoming connection)
    // TODO: Remove the discovery result / UUID part in the Javadoc if the flag is not enabled.
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothScanPermission
    @RequiresBluetoothLocationPermission
    @RequiresPermission(BLUETOOTH_SCAN)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_FOUND = "android.bluetooth.device.action.FOUND";

    /**
     * Broadcast Action: Bluetooth class of a remote device has changed.
     *
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link #EXTRA_CLASS}.
     *
     * @see BluetoothClass
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CLASS_CHANGED =
            "android.bluetooth.device.action.CLASS_CHANGED";

    /**
     * Broadcast Action: Indicates a low level (ACL) connection has been established with a remote
     * device.
     *
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link #EXTRA_TRANSPORT}.
     *
     * <p>ACL connections are managed automatically by the Android Bluetooth stack.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ACL_CONNECTED =
            "android.bluetooth.device.action.ACL_CONNECTED";

    /**
     * Broadcast Action: Indicates that a low level (ACL) disconnection has been requested for a
     * remote device, and it will soon be disconnected.
     *
     * <p>This is useful for graceful disconnection. Applications should use this intent as a hint
     * to immediately terminate higher level connections (RFCOMM, L2CAP, or profile connections) to
     * the remote device.
     *
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ACL_DISCONNECT_REQUESTED =
            "android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED";

    /**
     * Broadcast Action: Indicates a low level (ACL) disconnection from a remote device.
     *
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link #EXTRA_TRANSPORT}.
     *
     * <p>ACL connections are managed automatically by the Android Bluetooth stack.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ACL_DISCONNECTED =
            "android.bluetooth.device.action.ACL_DISCONNECTED";

    /**
     * Broadcast Action: Indicates the friendly name of a remote device has been retrieved for the
     * first time, or changed since the last retrieval.
     *
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link #EXTRA_NAME}.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NAME_CHANGED = "android.bluetooth.device.action.NAME_CHANGED";

    /**
     * Broadcast Action: Indicates the alias of a remote device has been changed.
     *
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}.
     */
    @SuppressLint("ActionValue")
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ALIAS_CHANGED =
            "android.bluetooth.device.action.ALIAS_CHANGED";

    /**
     * Broadcast Action: Indicates a change in the bond state of a remote device. For example, if a
     * device is bonded (paired).
     *
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE}, {@link #EXTRA_BOND_STATE}, and
     * {@link #EXTRA_PREVIOUS_BOND_STATE}. Also, from {@link
     * android.os.Build.VERSION_CODES#CINNAMON_BUN}, {@link #EXTRA_PAIRING_CONTEXT} will be
     * available. An extra field {@link #EXTRA_UNBOND_REASON} will be present, if the {@link
     * #EXTRA_BOND_STATE} is {@link #BOND_NONE}.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BOND_STATE_CHANGED =
            "android.bluetooth.device.action.BOND_STATE_CHANGED";

    /**
     * Broadcast Action: Indicates the battery level of a remote device has been retrieved for the
     * first time, or changed since the last retrieval
     *
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link #EXTRA_BATTERY_LEVEL}.
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SuppressLint("ActionValue")
    public static final String ACTION_BATTERY_LEVEL_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";

    /**
     * Broadcast Action: Indicates the audio buffer size should be switched between a low latency
     * buffer size and a higher and larger latency buffer size. Only registered receivers will
     * receive this intent.
     *
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link
     * #EXTRA_LOW_LATENCY_BUFFER_SIZE}.
     */
    @Hide
    @SuppressLint("ActionValue")
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_SWITCH_BUFFER_SIZE =
            "android.bluetooth.device.action.SWITCH_BUFFER_SIZE";

    /**
     * Broadcast Action: Indicates that previously bonded device couldn't provide keys to establish
     * encryption. This can have numerous reasons, i.e.:
     *
     * <ul>
     *   <li>remote was factory reset, or removed bond
     *   <li>spoofing attack, someone is impersonating remote device
     *   <li>in case of LE devices, very unlikely address collision
     * </ul>
     *
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}
     *
     * <p>From {@link Build.VERSION_CODES#BAKLAVA}, it can contain the extra field {@link
     * #EXTRA_BOND_LOSS_REASON} which contains the reason for the bond loss. The possible values
     * are: {@link #BOND_LOSS_REASON_BREDR_AUTH_FAILURE}, {@link
     * #BOND_LOSS_REASON_BREDR_INCOMING_PAIRING}, {@link #BOND_LOSS_REASON_LE_ENCRYPT_FAILURE},
     * {@link #BOND_LOSS_REASON_LE_INCOMING_PAIRING}.
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_CONNECT} permission. Before {@link
     * android.os.Build.VERSION_CODES#BAKLAVA} this method also required {@link
     * android.Manifest.permission#BLUETOOTH_PRIVILEGED}
     */
    @SuppressLint("ActionValue")
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(protectedBroadcast = true)
    public static final String ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING";

    /**
     * Broadcast Action: Indicates that encryption state changed
     *
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}
     *
     * <p>Always contains the extra field {@link #EXTRA_TRANSPORT}
     *
     * <p>Always contains the extra field {@link #EXTRA_ENCRYPTION_STATUS}
     *
     * <p>Always contains the extra field {@link #EXTRA_ENCRYPTION_ENABLED}
     *
     * <p>Always contains the extra field {@link #EXTRA_KEY_SIZE}
     *
     * <p>Always contains the extra field {@link #EXTRA_ENCRYPTION_ALGORITHM}
     */
    @SuppressLint("ActionValue")
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(protectedBroadcast = true)
    public static final String ACTION_ENCRYPTION_CHANGE =
            "android.bluetooth.device.action.ENCRYPTION_CHANGE";

    /**
     * Used as an Integer extra field in {@link #ACTION_BATTERY_LEVEL_CHANGED} intent. It contains
     * the most recently retrieved battery level information ranging from 0% to 100% for a remote
     * device, {@link #BATTERY_LEVEL_UNKNOWN} when the valid is unknown or there is an error, {@link
     * #BATTERY_LEVEL_BLUETOOTH_OFF} when the bluetooth is off
     */
    @Hide
    @SuppressLint("ActionValue")
    @SystemApi
    public static final String EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL";

    /** Used as the unknown value for {@link #EXTRA_BATTERY_LEVEL} and {@link #getBatteryLevel()} */
    @Hide @SystemApi public static final int BATTERY_LEVEL_UNKNOWN = -1;

    /** Used as an error value for {@link #getBatteryLevel()} to represent bluetooth is off */
    @Hide @SystemApi public static final int BATTERY_LEVEL_BLUETOOTH_OFF = -100;

    /**
     * Used as a Parcelable {@link BluetoothDevice} extra field in every intent broadcast by this
     * class. It contains the {@link BluetoothDevice} that the intent applies to.
     */
    public static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";

    /**
     * Used as a String extra field in {@link #ACTION_NAME_CHANGED} and {@link #ACTION_FOUND}
     * intents. It contains the friendly Bluetooth name.
     */
    public static final String EXTRA_NAME = "android.bluetooth.device.extra.NAME";

    /**
     * Used as an optional short extra field in {@link #ACTION_FOUND} intents. Contains the RSSI
     * value of the remote device as reported by the Bluetooth hardware.
     */
    public static final String EXTRA_RSSI = "android.bluetooth.device.extra.RSSI";

    /**
     * Used as a boolean extra field in {@link #ACTION_FOUND} intents. It contains the information
     * if device is discovered as member of a coordinated set or not. Pairing with device that
     * belongs to a set would trigger pairing with the rest of set members. See Bluetooth CSIP
     * specification for more details.
     */
    public static final String EXTRA_IS_COORDINATED_SET_MEMBER =
            "android.bluetooth.extra.IS_COORDINATED_SET_MEMBER";

    /**
     * Used as an integer field in {@link #ACTION_FOUND} intents. It contains the discovery type for
     * how the device is found:
     *
     * <ul>
     *   <li>If BR/EDR inquiry result is received, {@link #DEVICE_TYPE_CLASSIC}.
     *   <li>If BLE advertising data is received, {@link #DEVICE_TYPE_LE}.
     *   <li>If both are received, {@link #DEVICE_TYPE_DUAL}.
     * </ul>
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_DISCOVERY_RESULT_TYPE =
            "android.bluetooth.device.extra.DISCOVERY_RESULT_TYPE";

    /**
     * Used as a Parcelable {@link BluetoothClass} extra field in {@link #ACTION_FOUND} and {@link
     * #ACTION_CLASS_CHANGED} intents.
     */
    public static final String EXTRA_CLASS = "android.bluetooth.device.extra.CLASS";

    /**
     * Used as an int extra field in {@link #ACTION_BOND_STATE_CHANGED} intents. Contains the bond
     * state of the remote device.
     *
     * <p>Possible values are: {@link #BOND_NONE}, {@link #BOND_BONDING}, {@link #BOND_BONDED}.
     */
    public static final String EXTRA_BOND_STATE = "android.bluetooth.device.extra.BOND_STATE";

    /**
     * Used as an int extra field in {@link #ACTION_BOND_STATE_CHANGED} intents. Contains the
     * previous bond state of the remote device.
     *
     * <p>Possible values are: {@link #BOND_NONE}, {@link #BOND_BONDING}, {@link #BOND_BONDED}.
     */
    public static final String EXTRA_PREVIOUS_BOND_STATE =
            "android.bluetooth.device.extra.PREVIOUS_BOND_STATE";

    /** Used as a boolean extra field to indicate if audio buffer size is low latency or not */
    @Hide
    @SuppressLint("ActionValue")
    @SystemApi
    public static final String EXTRA_LOW_LATENCY_BUFFER_SIZE =
            "android.bluetooth.device.extra.LOW_LATENCY_BUFFER_SIZE";

    /**
     * Contains the reason for the bond loss.
     *
     * <ul>
     *   Possible values are:
     *   <li>{@link #BOND_LOSS_REASON_UNKNOWN}
     *   <li>{@link #BOND_LOSS_REASON_BREDR_AUTH_FAILURE}
     *   <li>{@link #BOND_LOSS_REASON_BREDR_INCOMING_PAIRING}
     *   <li>{@link #BOND_LOSS_REASON_LE_ENCRYPT_FAILURE}
     *   <li>{@link #BOND_LOSS_REASON_LE_INCOMING_PAIRING}
     * </ul>
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_BOND_LOSS_REASON =
            "android.bluetooth.device.extra.BOND_LOSS_REASON";

    /** Indicates the reason for the bond loss is unknown. */
    public static final int BOND_LOSS_REASON_UNKNOWN = 0;

    /** Indicates the reason for the bond loss is BREDR authentication failure. */
    public static final int BOND_LOSS_REASON_BREDR_AUTH_FAILURE = 1;

    /** Indicates the reason for the bond loss is BREDR pairing failure. */
    public static final int BOND_LOSS_REASON_BREDR_INCOMING_PAIRING = 2;

    /** Indicates the reason for the bond loss is LE encryption failure. */
    public static final int BOND_LOSS_REASON_LE_ENCRYPT_FAILURE = 3;

    /** Indicates the reason for the bond loss is LE pairing failure. */
    public static final int BOND_LOSS_REASON_LE_INCOMING_PAIRING = 4;

    /**
     * Indicates the remote device is not bonded (paired).
     *
     * <p>There is no shared link key with the remote device, so communication (if it is allowed at
     * all) will be unauthenticated and unencrypted.
     */
    public static final int BOND_NONE = 10;

    /** Indicates bonding (pairing) is in progress with the remote device. */
    public static final int BOND_BONDING = 11;

    /**
     * Indicates the remote device is bonded (paired).
     *
     * <p>A shared link keys exists locally for the remote device, so communication can be
     * authenticated and encrypted.
     *
     * <p><i>Being bonded (paired) with a remote device does not necessarily mean the device is
     * currently connected. It just means that the pending procedure was completed at some earlier
     * time, and the link key is still stored locally, ready to use on the next connection. </i>
     */
    public static final int BOND_BONDED = 12;

    /**
     * Used as an int extra field in {@link #ACTION_PAIRING_REQUEST} intents for unbond reason.
     * Possible value are : - {@link #UNBOND_REASON_AUTH_FAILED} - {@link
     * #UNBOND_REASON_AUTH_REJECTED} - {@link #UNBOND_REASON_AUTH_CANCELED} - {@link
     * #UNBOND_REASON_REMOTE_DEVICE_DOWN} - {@link #UNBOND_REASON_DISCOVERY_IN_PROGRESS} - {@link
     * #UNBOND_REASON_AUTH_TIMEOUT} - {@link #UNBOND_REASON_REPEATED_ATTEMPTS} - {@link
     * #UNBOND_REASON_REMOTE_AUTH_CANCELED} - {@link #UNBOND_REASON_REMOVED}
     *
     * <p>Note: Can be added as a hidden extra field for {@link #ACTION_BOND_STATE_CHANGED} when the
     * {@link #EXTRA_BOND_STATE} is {@link #BOND_NONE}
     */
    @Hide
    @SystemApi
    @SuppressLint("ActionValue")
    public static final String EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON";

    /** Use {@link EXTRA_UNBOND_REASON} instead */
    @Hide @UnsupportedAppUsage public static final String EXTRA_REASON = EXTRA_UNBOND_REASON;

    /**
     * Used as an int extra field in {@link #ACTION_PAIRING_REQUEST} intents to indicate pairing
     * method used. Possible values are: {@link #PAIRING_VARIANT_PIN}, {@link
     * #PAIRING_VARIANT_PASSKEY}, {@link #PAIRING_VARIANT_PASSKEY_CONFIRMATION}, {@link
     * #PAIRING_VARIANT_CONSENT}, {@link #PAIRING_VARIANT_DISPLAY_PASSKEY}, {@link
     * #PAIRING_VARIANT_DISPLAY_PIN}, {@link #PAIRING_VARIANT_OOB_CONSENT}, {@link
     * #PAIRING_VARIANT_PIN_16_DIGITS}.
     */
    public static final String EXTRA_PAIRING_VARIANT =
            "android.bluetooth.device.extra.PAIRING_VARIANT";

    /**
     * Used as an int extra field in {@link #ACTION_PAIRING_REQUEST} intents as the value of
     * passkey. The Bluetooth Passkey is a 6-digit numerical value represented as integer value in
     * the range 0x00000000 – 0x000F423F (000000 to 999999).
     */
    public static final String EXTRA_PAIRING_KEY = "android.bluetooth.device.extra.PAIRING_KEY";

    /**
     * Used as an int extra field in {@link #ACTION_PAIRING_REQUEST} intents as the location of
     * initiator. Possible value are: {@link #EXTRA_PAIRING_INITIATOR_FOREGROUND}, {@link
     * #EXTRA_PAIRING_INITIATOR_BACKGROUND},
     */
    @Hide
    @SystemApi
    @SuppressLint("ActionValue")
    public static final String EXTRA_PAIRING_INITIATOR =
            "android.bluetooth.device.extra.PAIRING_INITIATOR";

    /**
     * Used as an int extra field in {@link #ACTION_PAIRING_REQUEST} and {@link
     * #ACTION_BOND_STATE_CHANGED} intents which indicates the pairing context. Possible values for
     * {@link #ACTION_PAIRING_REQUEST} are: {@link #PAIRING_CONTEXT_USER_PARTICIPATION_REQUESTED},
     * {@link #PAIRING_CONTEXT_USER_APPROVAL_REQUESTED}, and {@link #PAIRING_CONTEXT_REPAIRING},
     * while the possible value for {@link #ACTION_BOND_STATE_CHANGED} is {@link
     * #PAIRING_CONTEXT_REPAIRING}.
     */
    @FlaggedApi(Flags.FLAG_AUTONOMOUS_REPAIRING_INITIATION)
    @SuppressLint("ActionValue")
    public static final String EXTRA_PAIRING_CONTEXT =
            "android.bluetooth.device.extra.PAIRING_CONTEXT";

    /**
     * Used as an int extra field in {@link #ACTION_PAIRING_REQUEST} intents which indicates the
     * pairing algorithm used. Possible values are: {@link #PAIRING_ALGORITHM_LE_LEGACY}, {@link
     * #PAIRING_ALGORITHM_BREDR_LEGACY}, {@link #PAIRING_ALGORITHM_BREDR_SSP}, and {@link
     * #PAIRING_ALGORITHM_SC}.
     */
    @FlaggedApi(Flags.FLAG_PROVIDE_PAIRING_ALGO)
    @SuppressLint("ActionValue")
    public static final String EXTRA_PAIRING_ALGORITHM =
            "android.bluetooth.device.extra.PAIRING_ALGORITHM";

    /**
     * Used as an int extra field in {@link #ACTION_ENCRYPTION_CHANGE} intents as the size of the
     * encryption key, in number of bytes. i.e. value of 16 means 16-byte, or 128 bit key size.
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_KEY_SIZE = "android.bluetooth.device.extra.KEY_SIZE";

    /**
     * Used as an int extra field in {@link #ACTION_ENCRYPTION_CHANGE} intents as the algorithm used
     * for encryption.
     *
     * <p>Possible values are: {@link #ENCRYPTION_ALGORITHM_NONE}, {@link #ENCRYPTION_ALGORITHM_E0},
     * {@link #ENCRYPTION_ALGORITHM_AES}, {@link #ENCRYPTION_ALGORITHM_UNKNOWN}.
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_ENCRYPTION_ALGORITHM =
            "android.bluetooth.device.extra.EXTRA_ENCRYPTION_ALGORITHM";

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"ENCRYPTION_ALGORITHM_"},
            value = {
                ENCRYPTION_ALGORITHM_NONE,
                ENCRYPTION_ALGORITHM_E0,
                ENCRYPTION_ALGORITHM_AES,
                ENCRYPTION_ALGORITHM_UNKNOWN
            })
    public @interface EncryptionAlgorithm {}

    /** Indicates that link was not encrypted using any algorithm */
    public static final int ENCRYPTION_ALGORITHM_NONE = 0;

    /** Indicates link was encrypted using E0 algorithm */
    public static final int ENCRYPTION_ALGORITHM_E0 = 1;

    /** Indicates link was encrypted using AES algorithm */
    public static final int ENCRYPTION_ALGORITHM_AES = 2;

    /** Indicates link was encrypted using unknown algorithm */
    public static final int ENCRYPTION_ALGORITHM_UNKNOWN = 3;

    /**
     * Used as an int extra field in {@link #ACTION_ENCRYPTION_CHANGE} intent. This is the status
     * value as returned from controller in "HCI Encryption Change event" i.e. value of 0 means
     * success.
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_ENCRYPTION_STATUS =
            "android.bluetooth.device.extra.ENCRYPTION_STATUS";

    /**
     * Used as a boolean extra field in {@link #ACTION_ENCRYPTION_CHANGE} intent. false mean
     * encryption is OFF, true means encryption is ON
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_ENCRYPTION_ENABLED =
            "android.bluetooth.device.extra.ENCRYPTION_ENABLED";

    /** Bluetooth pairing initiator, Foreground App */
    @Hide @SystemApi public static final int EXTRA_PAIRING_INITIATOR_FOREGROUND = 1;

    /** Bluetooth pairing initiator, Background */
    @Hide @SystemApi public static final int EXTRA_PAIRING_INITIATOR_BACKGROUND = 2;

    /** Bluetooth device type, Unknown */
    public static final int DEVICE_TYPE_UNKNOWN = 0;

    /** Bluetooth device type, Classic - BR/EDR devices */
    public static final int DEVICE_TYPE_CLASSIC = 1;

    /** Bluetooth device type, Low Energy - LE-only */
    public static final int DEVICE_TYPE_LE = 2;

    /** Bluetooth device type, Dual Mode - BR/EDR/LE */
    public static final int DEVICE_TYPE_DUAL = 3;

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"DEVICE_TYPE_"},
            value = {
                DEVICE_TYPE_CLASSIC,
                DEVICE_TYPE_LE,
                DEVICE_TYPE_DUAL,
                DEVICE_TYPE_UNKNOWN,
            })
    public @interface DeviceType {}

    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String ACTION_SDP_RECORD = "android.bluetooth.device.action.SDP_RECORD";

    @Hide
    @IntDef(
            prefix = "METADATA_",
            value = {
                METADATA_MANUFACTURER_NAME,
                METADATA_MODEL_NAME,
                METADATA_SOFTWARE_VERSION,
                METADATA_HARDWARE_VERSION,
                METADATA_COMPANION_APP,
                METADATA_MAIN_ICON,
                METADATA_IS_UNTETHERED_HEADSET,
                METADATA_UNTETHERED_LEFT_ICON,
                METADATA_UNTETHERED_RIGHT_ICON,
                METADATA_UNTETHERED_CASE_ICON,
                METADATA_UNTETHERED_LEFT_BATTERY,
                METADATA_UNTETHERED_RIGHT_BATTERY,
                METADATA_UNTETHERED_CASE_BATTERY,
                METADATA_UNTETHERED_LEFT_CHARGING,
                METADATA_UNTETHERED_RIGHT_CHARGING,
                METADATA_UNTETHERED_CASE_CHARGING,
                METADATA_ENHANCED_SETTINGS_UI_URI,
                METADATA_DEVICE_TYPE,
                METADATA_MAIN_BATTERY,
                METADATA_MAIN_CHARGING,
                METADATA_MAIN_LOW_BATTERY_THRESHOLD,
                METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                METADATA_SPATIAL_AUDIO,
                METADATA_FAST_PAIR_CUSTOMIZED_FIELDS,
                METADATA_LE_AUDIO,
                METADATA_GMCS_CCCD,
                METADATA_GTBS_CCCD,
                METADATA_EXCLUSIVE_MANAGER,
                METADATA_ZOOMED_IN_ICON,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MetadataKey {}

    /** Maximum length of a metadata entry, this is to avoid exploding Bluetooth disk usage */
    @Hide @SystemApi public static final int METADATA_MAX_LENGTH = 2048;

    /**
     * Manufacturer name of this Bluetooth device Data type should be {@link String} as {@link Byte}
     * array.
     */
    @Hide @SystemApi public static final int METADATA_MANUFACTURER_NAME = 0;

    /**
     * Model name of this Bluetooth device Data type should be {@link String} as {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_MODEL_NAME = 1;

    /**
     * Software version of this Bluetooth device Data type should be {@link String} as {@link Byte}
     * array.
     */
    @Hide @SystemApi public static final int METADATA_SOFTWARE_VERSION = 2;

    /**
     * Hardware version of this Bluetooth device Data type should be {@link String} as {@link Byte}
     * array.
     */
    @Hide @SystemApi public static final int METADATA_HARDWARE_VERSION = 3;

    /**
     * Package name of the companion app, if any Data type should be {@link String} as {@link Byte}
     * array.
     */
    @Hide @SystemApi public static final int METADATA_COMPANION_APP = 4;

    /** URI to the main icon shown on the settings UI Data type should be {@link Byte} array. */
    @Hide @SystemApi public static final int METADATA_MAIN_ICON = 5;

    /**
     * Whether this device is an untethered headset with left, right and case Data type should be
     * {@link String} as {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_IS_UNTETHERED_HEADSET = 6;

    /** URI to icon of the left headset Data type should be {@link Byte} array. */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_LEFT_ICON = 7;

    /** URI to icon of the right headset Data type should be {@link Byte} array. */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_RIGHT_ICON = 8;

    /** URI to icon of the headset charging case Data type should be {@link Byte} array. */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_CASE_ICON = 9;

    /**
     * Battery level of left headset Data type should be {@link String} 0-100 as {@link Byte} array,
     * otherwise as invalid.
     */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_LEFT_BATTERY = 10;

    /**
     * Battery level of right headset Data type should be {@link String} 0-100 as {@link Byte}
     * array, otherwise as invalid.
     */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_RIGHT_BATTERY = 11;

    /**
     * Battery level of the headset charging case Data type should be {@link String} 0-100 as {@link
     * Byte} array, otherwise as invalid.
     */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_CASE_BATTERY = 12;

    /**
     * Whether the left headset is charging Data type should be {@link String} as {@link Byte}
     * array.
     */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_LEFT_CHARGING = 13;

    /**
     * Whether the right headset is charging Data type should be {@link String} as {@link Byte}
     * array.
     */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_RIGHT_CHARGING = 14;

    /**
     * Whether the headset charging case is charging Data type should be {@link String} as {@link
     * Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_CASE_CHARGING = 15;

    /**
     * URI to the enhanced settings UI slice Data type should be {@link String} as {@link Byte}
     * array, null means the UI does not exist.
     */
    @Hide @SystemApi public static final int METADATA_ENHANCED_SETTINGS_UI_URI = 16;

    /**
     * Type of the Bluetooth device, must be within the list of BluetoothDevice.DEVICE_TYPE_* Data
     * type should be {@link String} as {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_DEVICE_TYPE = 17;

    /**
     * Battery level of the Bluetooth device, use when the Bluetooth device does not support HFP
     * battery indicator. Data type should be {@link String} as {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_MAIN_BATTERY = 18;

    /** Whether the device is charging. Data type should be {@link String} as {@link Byte} array. */
    @Hide @SystemApi public static final int METADATA_MAIN_CHARGING = 19;

    /**
     * The battery threshold of the Bluetooth device to show low battery icon. Data type should be
     * {@link String} as {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_MAIN_LOW_BATTERY_THRESHOLD = 20;

    /**
     * The battery threshold of the left headset to show low battery icon. Data type should be
     * {@link String} as {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD = 21;

    /**
     * The battery threshold of the right headset to show low battery icon. Data type should be
     * {@link String} as {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD = 22;

    /**
     * The battery threshold of the case to show low battery icon. Data type should be {@link
     * String} as {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD = 23;

    /** The metadata of the audio spatial data. Data type should be {@link Byte} array. */
    @Hide public static final int METADATA_SPATIAL_AUDIO = 24;

    /**
     * The metadata of the Fast Pair for any customized feature. Data type should be {@link Byte}
     * array.
     */
    @Hide public static final int METADATA_FAST_PAIR_CUSTOMIZED_FIELDS = 25;

    /**
     * The metadata of the Fast Pair for LE Audio capable devices. Data type should be {@link Byte}
     * array.
     */
    @Hide @SystemApi public static final int METADATA_LE_AUDIO = 26;

    /**
     * The UUIDs (16-bit) of registered to CCC characteristics from Media Control services. Data
     * type should be {@link Byte} array.
     */
    @Hide public static final int METADATA_GMCS_CCCD = 27;

    /**
     * The UUIDs (16-bit) of registered to CCC characteristics from Telephony Bearer service. Data
     * type should be {@link Byte} array.
     */
    @Hide public static final int METADATA_GTBS_CCCD = 28;

    /**
     * Specify the exclusive manager app for this BluetoothDevice.
     *
     * <p>If there's a manager app specified for this BluetoothDevice, and the app is currently
     * installed and enabled on the device, that manager app shall be responsible for providing the
     * BluetoothDevice management functionality (e.g. connect, disconnect, forget, etc.). Android
     * Settings app or Quick Settings System UI shall not provide any management functionality for
     * such BluetoothDevice.
     *
     * <p>Data type should be a {@link String} representation of the {@link ComponentName} (e.g.
     * "com.android.settings/.SettingsActivity") or the package name (e.g. "com.android.settings")
     * of the exclusive manager, provided as a {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_EXCLUSIVE_MANAGER = 29;

    /**
     * URI of zoomed in icon (i.e.
     * content://com.example.fileprovider/example_bluetooth_metadata/f1234model.png). Data type
     * should be {@link Byte} array.
     */
    @Hide @SystemApi public static final int METADATA_ZOOMED_IN_ICON = 30;

    // DO NOT UPDATE ADDITIONAL METADATA_FOO
    // Instead, look into adding proper setter/getter

    // Need to update this value after adding new Metadata
    private static final int METADATA_MAX_KEY = METADATA_ZOOMED_IN_ICON;

    /**
     * Device type which is used in METADATA_DEVICE_TYPE Indicates this Bluetooth device is a
     * standard Bluetooth accessory or not listed in METADATA_DEVICE_TYPE_*.
     */
    @Hide @SystemApi public static final String DEVICE_TYPE_DEFAULT = "Default";

    /**
     * Device type which is used in METADATA_DEVICE_TYPE Indicates this Bluetooth device is a watch.
     */
    @Hide @SystemApi public static final String DEVICE_TYPE_WATCH = "Watch";

    /**
     * Device type which is used in METADATA_DEVICE_TYPE Indicates this Bluetooth device is an
     * untethered headset.
     */
    @Hide @SystemApi
    public static final String DEVICE_TYPE_UNTETHERED_HEADSET = "Untethered Headset";

    /**
     * Device type which is used in METADATA_DEVICE_TYPE Indicates this Bluetooth device is a
     * stylus.
     */
    @Hide @SystemApi public static final String DEVICE_TYPE_STYLUS = "Stylus";

    /**
     * Device type which is used in METADATA_DEVICE_TYPE Indicates this Bluetooth device is a
     * speaker.
     */
    @Hide @SystemApi public static final String DEVICE_TYPE_SPEAKER = "Speaker";

    /**
     * Device type which is used in METADATA_DEVICE_TYPE Indicates this Bluetooth device is a
     * tethered headset.
     */
    @Hide @SystemApi public static final String DEVICE_TYPE_HEADSET = "Headset";

    /**
     * Device type which is used in METADATA_DEVICE_TYPE Indicates this Bluetooth device is a
     * Carkit.
     */
    @Hide @SystemApi public static final String DEVICE_TYPE_CARKIT = "Carkit";

    /**
     * Device type which is used in METADATA_DEVICE_TYPE Indicates this Bluetooth device is a
     * HearingAid.
     */
    @Hide @SystemApi public static final String DEVICE_TYPE_HEARING_AID = "HearingAid";

    /**
     * Broadcast Action: This intent is used to broadcast the {@link UUID} wrapped as a {@link
     * android.os.ParcelUuid} of the remote device after it has been fetched. This intent is sent
     * only when the UUIDs of the remote device are requested to be fetched using Service Discovery
     * Protocol
     *
     * <p>{@link #EXTRA_UUID} includes UUIDs from both BR/EDR and LE service discovery results. A
     * {@code null} {@link #EXTRA_UUID} indicates a timeout. An empty {@link #EXTRA_UUID} indicates
     * a successful discovery, but zero UUIDs are discovered.
     *
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}
     *
     * <p>Always contains the extra field {@link #EXTRA_UUID}
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_UUID = "android.bluetooth.device.action.UUID";

    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MAS_INSTANCE = "android.bluetooth.device.action.MAS_INSTANCE";

    /**
     * Broadcast Action: Indicates a failure to retrieve the name of a remote device.
     *
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}.
     */
    @Hide
    // TODO: is this actually useful?
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NAME_FAILED = "android.bluetooth.device.action.NAME_FAILED";

    /**
     * Broadcast Action: This intent is used to broadcast a PAIRING REQUEST.
     *
     * <p>It will contain the following extra fields:
     *
     * <ul>
     *   <li>{@link #EXTRA_DEVICE}
     *   <li>{@link #EXTRA_PAIRING_KEY}
     *   <li>{@link #EXTRA_PAIRING_CONTEXT}, post {@link
     *       android.os.Build.VERSION_CODES#CINNAMON_BUN} only
     *   <li>{@link #EXTRA_PAIRING_ALGORITHM}, post {@link
     *       android.os.Build.VERSION_CODES#CINNAMON_BUN} only
     * </ul>
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PAIRING_REQUEST =
            "android.bluetooth.device.action.PAIRING_REQUEST";

    /**
     * Starting from {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, the return value of
     * {@link BluetoothDevice#toString()} has changed to improve privacy.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static final long CHANGE_TO_STRING_REDACTED = 265103382L;

    /** Broadcast Action: This intent is used to broadcast PAIRING CANCEL */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SuppressLint("ActionValue")
    public static final String ACTION_PAIRING_CANCEL =
            "android.bluetooth.device.action.PAIRING_CANCEL";

    /**
     * Broadcast Action: This intent is used to broadcast CONNECTION ACCESS REQUEST
     *
     * <p>This action will trigger a prompt for the user to accept or deny giving the permission for
     * this device. Permissions can be specified with {@link #EXTRA_ACCESS_REQUEST_TYPE}.
     *
     * <p>The reply will be an {@link #ACTION_CONNECTION_ACCESS_REPLY} sent to the specified {@link
     * #EXTRA_PACKAGE_NAME} and {@link #EXTRA_CLASS_NAME}.
     *
     * <p>This action can be cancelled with {@link #ACTION_CONNECTION_ACCESS_CANCEL}.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SuppressLint("ActionValue")
    public static final String ACTION_CONNECTION_ACCESS_REQUEST =
            "android.bluetooth.device.action.CONNECTION_ACCESS_REQUEST";

    /**
     * Broadcast Action: This intent is used to broadcast CONNECTION ACCESS REPLY
     *
     * <p>This action is the reply from {@link #ACTION_CONNECTION_ACCESS_REQUEST} that is sent to
     * the specified {@link #EXTRA_PACKAGE_NAME} and {@link #EXTRA_CLASS_NAME}.
     *
     * <p>See the extra fields {@link #EXTRA_CONNECTION_ACCESS_RESULT} and {@link
     * #EXTRA_ALWAYS_ALLOWED} for possible results.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SuppressLint("ActionValue")
    public static final String ACTION_CONNECTION_ACCESS_REPLY =
            "android.bluetooth.device.action.CONNECTION_ACCESS_REPLY";

    /** Broadcast Action: This intent is used to broadcast CONNECTION ACCESS CANCEL */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SuppressLint("ActionValue")
    public static final String ACTION_CONNECTION_ACCESS_CANCEL =
            "android.bluetooth.device.action.CONNECTION_ACCESS_CANCEL";

    /**
     * Intent to broadcast silence mode changed. Always contains the extra field {@link
     * #EXTRA_DEVICE}
     */
    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_SILENCE_MODE_CHANGED =
            "android.bluetooth.device.action.SILENCE_MODE_CHANGED";

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REQUEST}.
     *
     * <p>Possible values are {@link #REQUEST_TYPE_PROFILE_CONNECTION}, {@link
     * #REQUEST_TYPE_PHONEBOOK_ACCESS}, {@link #REQUEST_TYPE_MESSAGE_ACCESS} and {@link
     * #REQUEST_TYPE_SIM_ACCESS}
     */
    @Hide
    @SystemApi
    @SuppressLint("ActionValue")
    public static final String EXTRA_ACCESS_REQUEST_TYPE =
            "android.bluetooth.device.extra.ACCESS_REQUEST_TYPE";

    @Hide @SystemApi public static final int REQUEST_TYPE_PROFILE_CONNECTION = 1;

    @Hide @SystemApi public static final int REQUEST_TYPE_PHONEBOOK_ACCESS = 2;

    @Hide @SystemApi public static final int REQUEST_TYPE_MESSAGE_ACCESS = 3;

    @Hide @SystemApi public static final int REQUEST_TYPE_SIM_ACCESS = 4;

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REQUEST} intents, Contains package
     * name to return reply intent to.
     */
    @Hide
    public static final String EXTRA_PACKAGE_NAME = "android.bluetooth.device.extra.PACKAGE_NAME";

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REQUEST} intents, Contains class
     * name to return reply intent to.
     */
    @Hide public static final String EXTRA_CLASS_NAME = "android.bluetooth.device.extra.CLASS_NAME";

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REPLY} intent.
     *
     * <p>Possible values are {@link #CONNECTION_ACCESS_YES} and {@link #CONNECTION_ACCESS_NO}.
     */
    @Hide
    @SystemApi
    @SuppressLint("ActionValue")
    public static final String EXTRA_CONNECTION_ACCESS_RESULT =
            "android.bluetooth.device.extra.CONNECTION_ACCESS_RESULT";

    @Hide @SystemApi public static final int CONNECTION_ACCESS_YES = 1;

    @Hide @SystemApi public static final int CONNECTION_ACCESS_NO = 2;

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REPLY} intents, Contains boolean
     * to indicate if the allowed response is once-for-all so that next request will be granted
     * without asking user again.
     */
    @Hide
    @SystemApi
    @SuppressLint("ActionValue")
    public static final String EXTRA_ALWAYS_ALLOWED =
            "android.bluetooth.device.extra.ALWAYS_ALLOWED";

    /** A bond attempt succeeded */
    @Hide public static final int BOND_SUCCESS = 0;

    /**
     * A bond attempt failed because pins did not match, or remote device did not respond to pin
     * request in time
     */
    @Hide @SystemApi public static final int UNBOND_REASON_AUTH_FAILED = 1;

    /** A bond attempt failed because the other side explicitly rejected bonding */
    @Hide @SystemApi public static final int UNBOND_REASON_AUTH_REJECTED = 2;

    /** A bond attempt failed because we canceled the bonding process */
    @Hide @SystemApi public static final int UNBOND_REASON_AUTH_CANCELED = 3;

    /** A bond attempt failed because we could not contact the remote device */
    @Hide @SystemApi public static final int UNBOND_REASON_REMOTE_DEVICE_DOWN = 4;

    /** A bond attempt failed because a discovery is in progress */
    @Hide @SystemApi public static final int UNBOND_REASON_DISCOVERY_IN_PROGRESS = 5;

    /** A bond attempt failed because of authentication timeout */
    @Hide @SystemApi public static final int UNBOND_REASON_AUTH_TIMEOUT = 6;

    /** A bond attempt failed because of repeated attempts */
    @Hide @SystemApi public static final int UNBOND_REASON_REPEATED_ATTEMPTS = 7;

    /** A bond attempt failed because we received an Authentication Cancel by remote end */
    @Hide @SystemApi public static final int UNBOND_REASON_REMOTE_AUTH_CANCELED = 8;

    /** An existing bond was explicitly revoked */
    @Hide @SystemApi public static final int UNBOND_REASON_REMOVED = 9;

    /** Indicates the pairing variant used. */
    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "PAIRING_VARIANT_",
            value = {
                PAIRING_VARIANT_PIN,
                PAIRING_VARIANT_PASSKEY,
                PAIRING_VARIANT_PASSKEY_CONFIRMATION,
                PAIRING_VARIANT_CONSENT,
                PAIRING_VARIANT_DISPLAY_PASSKEY,
                PAIRING_VARIANT_DISPLAY_PIN,
                PAIRING_VARIANT_OOB_CONSENT,
                PAIRING_VARIANT_PIN_16_DIGITS,
            })
    public @interface PairingVariant {}

    /** The user will be prompted to enter a pin or an app will enter a pin for user. */
    public static final int PAIRING_VARIANT_PIN = 0;

    /** The user will be prompted to enter a passkey */
    @Hide @SystemApi public static final int PAIRING_VARIANT_PASSKEY = 1;

    /**
     * The user will be prompted to confirm the passkey displayed on the screen or an app will
     * confirm the passkey for the user.
     */
    public static final int PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2;

    /** The user will be prompted to accept or deny the incoming pairing request */
    @Hide @SystemApi public static final int PAIRING_VARIANT_CONSENT = 3;

    /**
     * The user will be prompted to enter the passkey displayed on remote device This is used for
     * Bluetooth 2.1 pairing.
     */
    @Hide @SystemApi public static final int PAIRING_VARIANT_DISPLAY_PASSKEY = 4;

    /**
     * The user will be prompted to enter the PIN displayed on remote device. This is used for
     * Bluetooth 2.0 pairing.
     */
    @Hide @SystemApi public static final int PAIRING_VARIANT_DISPLAY_PIN = 5;

    /**
     * The user will be prompted to accept or deny the OOB pairing request. This is used for
     * Bluetooth 2.1 secure simple pairing.
     */
    @Hide @SystemApi public static final int PAIRING_VARIANT_OOB_CONSENT = 6;

    /**
     * The user will be prompted to enter a 16 digit pin or an app will enter a 16 digit pin for
     * user.
     */
    @Hide @SystemApi public static final int PAIRING_VARIANT_PIN_16_DIGITS = 7;

    /**
     * Signals a request for user participation to begin the LE Legacy pairing process. Accepting
     * this allows the Bluetooth stack to proceed toward the formal pairing association model;
     * rejecting or ignoring it results in immediate pairing failure. Note: This only authorizes the
     * process to start and does not constitute final pairing approval.
     */
    @FlaggedApi(Flags.FLAG_AUTONOMOUS_REPAIRING_INITIATION)
    public static final int PAIRING_CONTEXT_USER_PARTICIPATION_REQUESTED = 0;

    /**
     * Indicates that the pairing process is initiated, and user approval is requested to complete
     * the pairing process.
     */
    @FlaggedApi(Flags.FLAG_AUTONOMOUS_REPAIRING_INITIATION)
    public static final int PAIRING_CONTEXT_USER_APPROVAL_REQUESTED = 1;

    /**
     * Signals an autonomous, system-initiated re-pairing process. Acceptance replaces the existing
     * bond; rejection or ignoring preserves it. Failure results in immediate link disconnection and
     * an {@code ACTION_KEY_MISSING} intent broadcast.
     */
    @FlaggedApi(Flags.FLAG_AUTONOMOUS_REPAIRING_INITIATION)
    public static final int PAIRING_CONTEXT_REPAIRING = 2;

    /**
     * Represents a non-exhaustive list of known pairing algorithms. This list is subject to
     * expansion as future Bluetooth specifications introduce new pairing methods.
     */
    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "PAIRING_ALGORITHM_",
            value = {
                PAIRING_ALGORITHM_LE_LEGACY,
                PAIRING_ALGORITHM_BREDR_LEGACY,
                PAIRING_ALGORITHM_BREDR_SSP,
                PAIRING_ALGORITHM_SC,
            })
    @FlaggedApi(Flags.FLAG_PROVIDE_PAIRING_ALGO)
    public @interface PairingAlgorithm {}

    /**
     * Indicates usage of the LE Legacy pairing algorithm. Refer to Bluetooth Core Spec v6.2, Vol 1,
     * Part A, Section 5 for security requirements and procedure details.
     */
    @FlaggedApi(Flags.FLAG_PROVIDE_PAIRING_ALGO)
    public static final int PAIRING_ALGORITHM_LE_LEGACY = 0;

    /**
     * Indicates usage of the BR/EDR Legacy pairing algorithm. Refer to Bluetooth Core Spec v6.2,
     * Vol 1, Part A, Section 5 for security requirements and procedure details.
     */
    @FlaggedApi(Flags.FLAG_PROVIDE_PAIRING_ALGO)
    public static final int PAIRING_ALGORITHM_BREDR_LEGACY = 1;

    /**
     * Indicates usage of the BR/EDR Secure Simple Pairing (SSP) algorithm. Refer to Bluetooth Core
     * Spec v6.2, Vol 1, Part A, Section 5 for security requirements and procedure details.
     */
    @FlaggedApi(Flags.FLAG_PROVIDE_PAIRING_ALGO)
    public static final int PAIRING_ALGORITHM_BREDR_SSP = 2;

    /**
     * Indicates usage of the Secure Connections pairing algorithm, applicable to both BR/EDR and LE
     * transports. Refer to Bluetooth Core Spec v6.2, Vol 1, Part A, Section 5 for security
     * requirements and procedure details.
     */
    @FlaggedApi(Flags.FLAG_PROVIDE_PAIRING_ALGO)
    public static final int PAIRING_ALGORITHM_SC = 3;

    /**
     * Contains the {@link android.os.ParcelUuid}s of the remote device which is a parcelable
     * version of {@link UUID}.
     *
     * <p>An empty EXTRA_UUID indicates the UUIDs are discovered, but zero UUIDs were found.
     *
     * <p>A {@code null} or absent EXTRA_UUID indicates the system failed obtain the UUIDs.
     */
    public static final String EXTRA_UUID = "android.bluetooth.device.extra.UUID";

    /**
     * Contains the {@link android.os.ParcelUuid}s of the remote device which is a parcelable
     * version of {@link UUID}. Only services discovered for the LE transport is included here.
     *
     * <p>An empty EXTRA_UUID_LE indicates the UUIDs are discovered, but zero UUIDs were found.
     *
     * <p>A {@code null} or absent EXTRA_UUID_LE indicates the system failed obtain the UUIDs.
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_UUID_LE = "android.bluetooth.device.extra.UUID_LE";

    @Hide public static final String EXTRA_SDP_RECORD = "android.bluetooth.device.extra.SDP_RECORD";

    @Hide
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String EXTRA_SDP_SEARCH_STATUS =
            "android.bluetooth.device.extra.SDP_SEARCH_STATUS";

    @Hide
    @IntDef(
            prefix = "ACCESS_",
            value = {ACCESS_UNKNOWN, ACCESS_ALLOWED, ACCESS_REJECTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AccessPermission {}

    /**
     * For {@link #getPhonebookAccessPermission}, {@link #setPhonebookAccessPermission}, {@link
     * #getMessageAccessPermission} and {@link #setMessageAccessPermission}.
     */
    @Hide @SystemApi public static final int ACCESS_UNKNOWN = 0;

    /**
     * For {@link #getPhonebookAccessPermission}, {@link #setPhonebookAccessPermission}, {@link
     * #getMessageAccessPermission} and {@link #setMessageAccessPermission}.
     */
    @Hide @SystemApi public static final int ACCESS_ALLOWED = 1;

    /**
     * For {@link #getPhonebookAccessPermission}, {@link #setPhonebookAccessPermission}, {@link
     * #getMessageAccessPermission} and {@link #setMessageAccessPermission}.
     */
    @Hide @SystemApi public static final int ACCESS_REJECTED = 2;

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"TRANSPORT_"},
            value = {
                TRANSPORT_AUTO,
                TRANSPORT_BREDR,
                TRANSPORT_LE,
            })
    public @interface Transport {}

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"TRANSPORT_"},
            value = {TRANSPORT_BREDR, TRANSPORT_LE})
    public @interface SupportedTransport {}

    /** No preference of physical transport for GATT connections to remote dual-mode devices */
    public static final int TRANSPORT_AUTO = 0;

    /** Constant representing the BR/EDR transport. */
    public static final int TRANSPORT_BREDR = 1;

    /** Constant representing the Bluetooth Low Energy (BLE) Transport. */
    public static final int TRANSPORT_LE = 2;

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"PHY_LE_"},
            value = {
                PHY_LE_1M,
                PHY_LE_2M,
                PHY_LE_CODED,
                PHY_LE_HDT,
            })
    public @interface PhyType {}

    /**
     * Bluetooth LE 1M PHY. Used to refer to LE 1M Physical Channel for advertising, scanning or
     * connection.
     */
    public static final int PHY_LE_1M = 1;

    /**
     * Bluetooth LE 2M PHY. Used to refer to LE 2M Physical Channel for advertising, scanning or
     * connection.
     */
    public static final int PHY_LE_2M = 2;

    /**
     * Bluetooth LE Coded PHY. Used to refer to LE Coded Physical Channel for advertising, scanning
     * or connection.
     */
    public static final int PHY_LE_CODED = 3;

    /** Bluetooth LE HDT PHY. Used to refer to LE High Data Throughput Physical Channel. */
    @FlaggedApi(Flags.FLAG_LEAUDIO_OVER_HDT_PHY_API)
    public static final int PHY_LE_HDT = 5;

    /** Phy mask values. */
    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            flag = true,
            value = {PHY_LE_1M_MASK, PHY_LE_2M_MASK, PHY_LE_CODED_MASK, PHY_LE_HDT_MASK})
    public @interface PhyMask {}

    /**
     * Bluetooth LE 1M PHY mask. Used to specify LE 1M Physical Channel as one of many available
     * options in a bitmask.
     */
    public static final int PHY_LE_1M_MASK = 1 << 0;

    /**
     * Bluetooth LE 2M PHY mask. Used to specify LE 2M Physical Channel as one of many available
     * options in a bitmask.
     */
    public static final int PHY_LE_2M_MASK = 1 << 1;

    /**
     * Bluetooth LE Coded PHY mask. Used to specify LE Coded Physical Channel as one of many
     * available options in a bitmask.
     */
    public static final int PHY_LE_CODED_MASK = 1 << 2;

    /**
     * Bluetooth LE HDT PHY mask. Used to specify LE High Data Throughput Physical Channel as one of
     * many available options in a bitmask.
     */
    @FlaggedApi(Flags.FLAG_LEAUDIO_OVER_HDT_PHY_API)
    public static final int PHY_LE_HDT_MASK = 1 << 4;

    /** No preferred coding when transmitting on the LE Coded PHY. */
    public static final int PHY_OPTION_NO_PREFERRED = 0;

    /** Prefer the S=2 coding to be used when transmitting on the LE Coded PHY. */
    public static final int PHY_OPTION_S2 = 1;

    /** Prefer the S=8 coding to be used when transmitting on the LE Coded PHY. */
    public static final int PHY_OPTION_S8 = 2;

    @Hide
    public static final String EXTRA_MAS_INSTANCE = "android.bluetooth.device.extra.MAS_INSTANCE";

    /**
     * Used as an int extra field in {@link #ACTION_ACL_CONNECTED}, {@link #ACTION_ACL_DISCONNECTED}
     * and {@link #ACTION_ENCRYPTION_CHANGE} intents to indicate which transport is connected.
     * Possible values are: {@link #TRANSPORT_BREDR} and {@link #TRANSPORT_LE}.
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_TRANSPORT = "android.bluetooth.device.extra.TRANSPORT";

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"ADDRESS_TYPE_"},
            value = {
                ADDRESS_TYPE_PUBLIC,
                ADDRESS_TYPE_RANDOM,
                ADDRESS_TYPE_ANONYMOUS,
                ADDRESS_TYPE_UNKNOWN,
            })
    public @interface AddressType {}

    /** Hardware MAC Address of the device */
    public static final int ADDRESS_TYPE_PUBLIC = 0;

    /** Address is either resolvable, non-resolvable or static. */
    public static final int ADDRESS_TYPE_RANDOM = 1;

    /** Address type is unknown or unavailable */
    public static final int ADDRESS_TYPE_UNKNOWN = 0xFFFF;

    /** Address type used to indicate an anonymous advertisement. */
    public static final int ADDRESS_TYPE_ANONYMOUS = 0xFF;

    /**
     * On-head detection enabled state: Unknown. Indicates that the enabled state for on-head
     * detection is not known.
     */
    @Hide public static final int ON_HEAD_DETECTION_ENABLED_STATE_UNKNOWN = 0;

    /**
     * On-head detection enabled state: Enabled. Indicates that on-head detection is currently
     * enabled on the device.
     */
    @Hide public static final int ON_HEAD_DETECTION_ENABLED_STATE_ENABLED = 1;

    /**
     * On-head detection enabled state: Disabled. Indicates that on-head detection is currently
     * disabled on the device.
     */
    @Hide public static final int ON_HEAD_DETECTION_ENABLED_STATE_DISABLED = 2;

    /** On-head detection state: Unknown. Indicates that the current on-head state is not known. */
    @Hide public static final int ON_HEAD_DETECTION_STATE_UNKNOWN = 0;

    /**
     * On-head detection state: On Head. Indicates that the device is currently detected as being on
     * the user's head.
     */
    @Hide public static final int ON_HEAD_DETECTION_STATE_ON_HEAD = 1;

    /**
     * On-head detection state: Not On Head. Indicates that the device is currently detected as not
     * being on the user's head.
     */
    @Hide public static final int ON_HEAD_DETECTION_STATE_NOT_ON_HEAD = 2;

    /** Indicates default active audio device policy is applied to this device */
    @Hide @SystemApi public static final int ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT = 0;

    /**
     * Indicates all profiles active audio device policy is applied to this device
     *
     * <p>all profiles are active upon device connection
     */
    @Hide @SystemApi
    public static final int ACTIVE_AUDIO_DEVICE_POLICY_ALL_PROFILES_ACTIVE_UPON_CONNECTION = 1;

    /**
     * Indicates all profiles inactive audio device policy is applied to this device
     *
     * <p>all profiles are inactive upon device connection
     */
    @Hide @SystemApi
    public static final int ACTIVE_AUDIO_DEVICE_POLICY_ALL_PROFILES_INACTIVE_UPON_CONNECTION = 2;

    private static final String NULL_MAC_ADDRESS = "00:00:00:00:00:00";

    private final BluetoothAdapter mAdapter;
    private final String mAddress;
    private final @AddressType int mAddressType;

    private AttributionSource mAttributionSource;

    static IBluetooth getService() {
        return BluetoothAdapter.getDefaultAdapter().getBluetoothService();
    }

    private IBluetooth getServiceInternal() {
        return mAdapter.getBluetoothService();
    }

    private <R> R callServiceIfEnabled(
            BluetoothUtils.RemoteExceptionIgnoringFunction<IBluetooth, R> function,
            R defaultValue) {
        return BluetoothUtils.callServiceIfEnabled(
                mAdapter, this::getServiceInternal, function, defaultValue);
    }

    /**
     * Create a new BluetoothDevice. Bluetooth MAC address must be upper case, such as
     * "00:11:22:33:AA:BB", and is validated in this constructor.
     *
     * @param address valid Bluetooth MAC address
     * @param addressType valid address type
     * @throws IllegalArgumentException address or addressType is invalid
     */
    @Hide
    public BluetoothDevice(BluetoothAdapter adapter, String address, int addressType) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalArgumentException(address + " is not a valid Bluetooth address");
        }

        if (addressType != ADDRESS_TYPE_PUBLIC
                && addressType != ADDRESS_TYPE_RANDOM
                && addressType != ADDRESS_TYPE_ANONYMOUS) {
            throw new IllegalArgumentException(addressType + " is not a Bluetooth address type");
        }

        if (addressType == ADDRESS_TYPE_ANONYMOUS && !NULL_MAC_ADDRESS.equals(address)) {
            throw new IllegalArgumentException(
                    "Invalid address for anonymous address type: "
                            + BluetoothUtils.toAnonymizedAddress(address));
        }

        mAdapter = adapter;
        mAddress = address;
        mAddressType = addressType;
        mAttributionSource = AttributionSource.myAttributionSource();
    }

    /** see {@link #BluetoothDevice(BluetoothAdapter, String, int)} */
    @SuppressWarnings("unused") // Used by android/app/jni/com_android_bluetooth_le_audio.cpp
    private BluetoothDevice(String address, int addressType) {
        this(BluetoothAdapter.getDefaultAdapter(), address, addressType);
    }

    /**
     * Create a new BluetoothDevice. Bluetooth MAC address must be upper case, such as
     * "00:11:22:33:AA:BB", and is validated in this constructor.
     *
     * @param address valid Bluetooth MAC address
     * @throws RuntimeException Bluetooth is not available on this platform
     * @throws IllegalArgumentException address is invalid
     */
    @Hide
    @UnsupportedAppUsage
    /*package*/ BluetoothDevice(BluetoothAdapter adapter, String address) {
        this(adapter, address, ADDRESS_TYPE_PUBLIC);
    }

    // Constructor used via reflection by ShadowBluetoothDevice.newInstance
    @Hide
    @Deprecated
    @UnsupportedAppUsage
    /*package*/ BluetoothDevice(String address) {
        this(BluetoothAdapter.getDefaultAdapter(), address, ADDRESS_TYPE_PUBLIC);
    }

    /**
     * Create a new BluetoothDevice.
     *
     * @param in valid parcel
     * @throws RuntimeException Bluetooth is not available on this platform
     * @throws IllegalArgumentException address is invalid
     */
    @Hide
    @UnsupportedAppUsage
    /*package*/ BluetoothDevice(Parcel in) {
        this(BluetoothAdapter.getDefaultAdapter(), in.readString(), in.readInt());
    }

    @Hide
    @RequiresNoPermission
    public void setAttributionSource(@NonNull AttributionSource source) {
        mAttributionSource = source;
    }

    /**
     * Method should never be used anywhere. Only exception is from {@link Intent} Used to set the
     * device current attribution source
     *
     * @param source The associated {@link AttributionSource} for this device in this process
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public void prepareToEnterProcess(@NonNull AttributionSource source) {
        setAttributionSource(source);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof BluetoothDevice) {
            return mAddress.equals(((BluetoothDevice) o).getAddress());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mAddress.hashCode();
    }

    /**
     * Returns a string representation of this BluetoothDevice.
     *
     * <p>For apps targeting {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} (API level 34)
     * or higher, this returns the MAC address of the device redacted by replacing the hexadecimal
     * digits of leftmost 4 bytes (in big endian order) with "XX", e.g., "XX:XX:XX:XX:12:34". For
     * apps targeting earlier versions, the MAC address is returned without redaction.
     *
     * <p>Warning: The return value of {@link #toString()} may change in the future. It is intended
     * to be used in logging statements. Thus apps should never rely on the return value of {@link
     * #toString()} in their logic. Always use other appropriate APIs instead (e.g., use {@link
     * #getAddress()} to get the MAC address).
     *
     * @return string representation of this BluetoothDevice
     */
    @Override
    public String toString() {
        if (!CompatChanges.isChangeEnabled(CHANGE_TO_STRING_REDACTED)) {
            return mAddress;
        }
        return getAnonymizedAddress();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<BluetoothDevice> CREATOR =
            new Creator<>() {
                public BluetoothDevice createFromParcel(Parcel in) {
                    return new BluetoothDevice(in);
                }

                public BluetoothDevice[] newArray(int size) {
                    return new BluetoothDevice[size];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        BluetoothUtils.writeStringToParcel(out, mAddress);
        out.writeInt(mAddressType);
    }

    /**
     * Returns the hardware address of this BluetoothDevice.
     *
     * <p>For example, "00:11:22:AA:BB:CC".
     *
     * @return Bluetooth hardware address as string
     */
    @RequiresNoPermission
    public String getAddress() {
        return mAddress;
    }

    /**
     * @return Bluetooth address type
     */
    @RequiresNoPermission
    public @AddressType int getAddressType() {
        return mAddressType;
    }

    /**
     * Returns the anonymized hardware address of this BluetoothDevice. The first three octets will
     * be suppressed for anonymization.
     *
     * <p>For example, "XX:XX:XX:AA:BB:CC".
     *
     * @return Anonymized bluetooth hardware address as string
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @NonNull String getAnonymizedAddress() {
        return BluetoothUtils.toAnonymizedAddress(mAddress);
    }

    /**
     * Returns the identity address of this BluetoothDevice.
     *
     * <p>For example, "00:11:22:AA:BB:CC".
     *
     * @return Bluetooth identity address as a string
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @Nullable String getIdentityAddress() {
        return callServiceIfEnabled(s -> s.getIdentityAddress(mAddress, mAttributionSource), null);
    }

    /**
     * Returns the identity address and identity address type of this BluetoothDevice. An identity
     * address is a public or static random Bluetooth LE device address that serves as a unique
     * identifier.
     *
     * @return a {@link BluetoothAddress} containing identity address and identity address type. If
     *     Bluetooth is not enabled or identity address type is not available, it will return a
     *     {@link BluetoothAddress} containing {@link #ADDRESS_TYPE_UNKNOWN} device for the identity
     *     address type.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @NonNull BluetoothAddress getIdentityAddressWithType() {
        return callServiceIfEnabled(
                s -> s.getIdentityAddressWithType(mAddress, mAttributionSource),
                new BluetoothAddress(null, BluetoothDevice.ADDRESS_TYPE_UNKNOWN));
    }

    /**
     * Get the friendly Bluetooth name of the remote device.
     *
     * <p>The local adapter will automatically retrieve remote names when performing a device scan,
     * and will cache them. This method just returns the name for this device from the cache.
     *
     * @return the Bluetooth name, or null if there was a problem.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public String getName() {
        return callServiceIfEnabled(s -> trim(s.getRemoteName(this, mAttributionSource)), null);
    }

    private static @Nullable String trim(@Nullable String str) {
        if (str == null) return null;
        return str.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Get the Bluetooth device type of the remote device.
     *
     * @return the device type {@link #DEVICE_TYPE_CLASSIC}, {@link #DEVICE_TYPE_LE} {@link
     *     #DEVICE_TYPE_DUAL}. {@link #DEVICE_TYPE_UNKNOWN} if it's not available
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public int getType() {
        return callServiceIfEnabled(
                s -> s.getRemoteType(this, mAttributionSource), DEVICE_TYPE_UNKNOWN);
    }

    /**
     * Get the locally modifiable name (alias) of the remote Bluetooth device.
     *
     * @return the Bluetooth alias, the friendly device name if no alias, or null if there was a
     *     problem
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public @Nullable String getAlias() {
        return callServiceIfEnabled(
                s -> trimAlias(s.getRemoteAlias(this, mAttributionSource)), null);
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private @Nullable String trimAlias(@Nullable String alias) {
        if (alias == null) return getName();
        return trim(alias);
    }

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED,
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
                BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED
            })
    public @interface SetAliasReturnValues {}

    /**
     * Sets the locally modifiable name (alias) of the remote Bluetooth device. This method
     * overwrites the previously stored alias. The new alias is saved in local storage so that the
     * change is preserved over power cycles.
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_CONNECT} permission. Additionally, an app must either
     * have the {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} or be associated with the
     * Companion Device manager (see {@link android.companion.CompanionDeviceManager#associate(
     * AssociationRequest, android.companion.CompanionDeviceManager.Callback, Handler)})
     *
     * @param alias is the new locally modifiable name for the remote Bluetooth device which must be
     *     the empty string. If null, we clear the alias.
     * @return whether the alias was successfully changed
     * @throws IllegalArgumentException if the alias is the empty string
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    public @SetAliasReturnValues int setAlias(@Nullable String alias) {
        if (alias != null && alias.isEmpty()) {
            throw new IllegalArgumentException("alias cannot be the empty string");
        }
        if (DBG) log("setAlias(" + alias + ")");
        return callServiceIfEnabled(
                s -> s.setRemoteAlias(this, alias, mAttributionSource),
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
    }

    /**
     * Get the most recent identified battery level of this Bluetooth device
     *
     * @return Battery level in percents from 0 to 100, {@link #BATTERY_LEVEL_BLUETOOTH_OFF} if
     *     Bluetooth is disabled or {@link #BATTERY_LEVEL_UNKNOWN} if device is disconnected, or
     *     does not have any battery reporting service, or return value is invalid
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public @IntRange(from = -100, to = 100) int getBatteryLevel() {
        return callServiceIfEnabled(
                s -> s.getBatteryLevel(this, mAttributionSource), BATTERY_LEVEL_BLUETOOTH_OFF);
    }

    /**
     * Start the bonding (pairing) process with the remote device.
     *
     * <p>This is an asynchronous call, it will return immediately. Register for {@link
     * #ACTION_BOND_STATE_CHANGED} intents to be notified when the bonding process completes, and
     * its result.
     *
     * <p>Android system services will handle the necessary user interactions to confirm and
     * complete the bonding process.
     *
     * @return false on immediate error, true if bonding will begin
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    // FlaggedApi checker triggers on createBond(int), but this used to be a system API -- the new
    // check is weaker and should not pose any functional problems.
    @SuppressLint("FlaggedApi")
    public boolean createBond() {
        return createBond(TRANSPORT_AUTO);
    }

    /**
     * Start the bonding (pairing) process with the remote device using the specified transport.
     *
     * <p>This is an asynchronous call, it will return immediately. Register for {@link
     * #ACTION_BOND_STATE_CHANGED} intents to be notified when the bonding process completes, and
     * its result.
     *
     * <p>Android system services will handle the necessary user interactions to confirm and
     * complete the bonding process.
     *
     * @param transport The transport to use for the pairing procedure.
     * @return false on immediate error, true if bonding will begin
     * @throws IllegalArgumentException if an invalid transport was specified
     */
    @FlaggedApi(Flags.FLAG_APAIRING_26Q2_PERMISSION_IMPROVEMENTS)
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean createBond(@Transport int transport) {
        if (DBG) log("createBond()");
        if (NULL_MAC_ADDRESS.equals(mAddress)) {
            Log.e(TAG, "Unable to create bond, invalid NULL address");
            return false;
        }
        return callServiceIfEnabled(s -> s.createBond(this, transport, mAttributionSource), false);
    }

    /**
     * Start the bonding (pairing) process with the remote device using the Out Of Band mechanism.
     *
     * <p>This is an asynchronous call, it will return immediately. Register for {@link
     * #ACTION_BOND_STATE_CHANGED} intents to be notified when the bonding process completes, and
     * its result.
     *
     * <p>Android system services will handle the necessary user interactions to confirm and
     * complete the bonding process.
     *
     * <p>There are two possible versions of OOB Data. This data can come in as P192 or P256. This
     * is a reference to the cryptography used to generate the key. The caller may pass one or both.
     * If both types of data are passed, then the P256 data will be preferred, and thus used.
     *
     * @param transport - Transport to use
     * @param remoteP192Data - Out Of Band data (P192) or null
     * @param remoteP256Data - Out Of Band data (P256) or null
     * @return false on immediate error, true if bonding will begin
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean createBondOutOfBand(
            @Transport int transport,
            @Nullable OobData remoteP192Data,
            @Nullable OobData remoteP256Data) {
        if (DBG) log("createBondOutOfBand()");

        if (remoteP192Data == null && remoteP256Data == null) {
            throw new IllegalArgumentException(
                    "One or both arguments for the OOB data types are required to not be null. "
                        + " Please use createBond() instead if you do not have OOB data to pass.");
        }
        if (NULL_MAC_ADDRESS.equals(mAddress)) {
            Log.e(TAG, "Unable to create bond Out of Band, invalid NULL address");
            return false;
        }
        return callServiceIfEnabled(
                s ->
                        s.createBondOutOfBand(
                                this,
                                transport,
                                remoteP192Data,
                                remoteP256Data,
                                mAttributionSource),
                false);
    }

    /**
     * Gets whether bonding was initiated locally
     *
     * @return true if bonding is initiated locally, false otherwise
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean isBondingInitiatedLocally() {
        return callServiceIfEnabled(
                s -> s.isBondingInitiatedLocally(this, mAttributionSource), false);
    }

    /**
     * Cancel an in-progress bonding request started with {@link #createBond}.
     *
     * <p>BLUETOOTH_PRIVILEGED is enforced only if the calling app didn't initiate bonding.
     *
     * @return true on success, false on error
     */
    @FlaggedApi(Flags.FLAG_APAIRING_26Q2_PERMISSION_IMPROVEMENTS)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    public boolean cancelBondProcess() {
        Log.i(
                TAG,
                "cancelBondProcess() for"
                        + (" device=" + this)
                        + (" called by pid=" + Process.myPid())
                        + (" tid=" + Process.myTid()));
        return callServiceIfEnabled(s -> s.cancelBondProcess(this, mAttributionSource), false);
    }

    /**
     * Remove bond (pairing) with the remote device.
     *
     * <p>Delete the link key associated with the remote device, and immediately terminate
     * connections to that device that require authentication and encryption.
     *
     * <p>When the calling application targets API level 37 or higher, {@link
     * android.Manifest.permission#BLUETOOTH_PRIVILEGED} is required.
     *
     * @return true on success, false on error
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    public boolean removeBond() {
        Log.i(
                TAG,
                "removeBond() for"
                        + (" device=" + this)
                        + (" called by pid=" + Process.myPid())
                        + (" tid=" + Process.myTid()));
        return callServiceIfEnabled(s -> s.removeBond(this, mAttributionSource), false);
    }

    /**
     * There are several instances of IpcDataCache used in this class. BluetoothCache wraps up the
     * common code. All caches are created with a maximum of eight entries, and the key is in the
     * bluetooth module. The name is set to the api.
     */
    private static class BluetoothCache<Q, R> extends IpcDataCache<Q, R> {
        BluetoothCache(String api, IpcDataCache.QueryHandler query) {
            super(8, IpcDataCache.MODULE_BLUETOOTH, api, api, query);
        }
    }

    /**
     * Invalidate a bluetooth cache. This method is just a short-hand wrapper that enforces the
     * bluetooth module.
     */
    private static void invalidateCache(@NonNull String api) {
        IpcDataCache.invalidateCache(IpcDataCache.MODULE_BLUETOOTH, api);
    }

    private static final IpcDataCache.QueryHandler<
                    Pair<IBluetooth, Pair<AttributionSource, BluetoothDevice>>, Integer>
            sBluetoothBondQuery =
                    new IpcDataCache.QueryHandler<>() {
                        @RequiresLegacyBluetoothPermission
                        @RequiresBluetoothConnectPermission
                        @RequiresPermission(BLUETOOTH_CONNECT)
                        @Override
                        public Integer apply(
                                Pair<IBluetooth, Pair<AttributionSource, BluetoothDevice>>
                                        pairQuery) {
                            IBluetooth service = pairQuery.first;
                            AttributionSource source = pairQuery.second.first;
                            BluetoothDevice device = pairQuery.second.second;
                            try {
                                return service.getBondState(device, source);
                            } catch (RemoteException e) {
                                throw e.rethrowAsRuntimeException();
                            }
                        }
                    };

    private static final String GET_BOND_STATE_API = "BluetoothDevice_getBondState";

    private static final BluetoothCache<
                    Pair<IBluetooth, Pair<AttributionSource, BluetoothDevice>>, Integer>
            sBluetoothBondCache = new BluetoothCache<>(GET_BOND_STATE_API, sBluetoothBondQuery);

    @Hide
    @RequiresNoPermission
    public void disableBluetoothGetBondStateCache() {
        sBluetoothBondCache.disableForCurrentProcess();
    }

    @Hide
    @RequiresNoPermission
    public static void invalidateBluetoothGetBondStateCache() {
        invalidateCache(GET_BOND_STATE_API);
    }

    /**
     * Get the bond state of the remote device.
     *
     * <p>Possible values for the bond state are: {@link #BOND_NONE}, {@link #BOND_BONDING}, {@link
     * #BOND_BONDED}.
     *
     * @return the bond state
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public int getBondState() {
        final IBluetooth service = getServiceInternal();
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot get bond state");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else {
            try {
                return sBluetoothBondCache.query(
                        new Pair<>(service, new Pair<>(mAttributionSource, BluetoothDevice.this)));
            } catch (RuntimeException e) {
                if (!(e.getCause() instanceof RemoteException)) {
                    throw e;
                }
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return BOND_NONE;
    }

    /**
     * Checks whether this bluetooth device is associated with CDM and meets the criteria to skip
     * the bluetooth pairing dialog because it has been already consented by the CDM prompt.
     *
     * @return true if we can bond without the dialog, false otherwise
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean canBondWithoutDialog() {
        return callServiceIfEnabled(s -> s.canBondWithoutDialog(this, mAttributionSource), false);
    }

    /**
     * Gets the package name of the application that initiate bonding with this device
     *
     * @return package name of the application, or null of no application initiate bonding with this
     *     device
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @Nullable String getPackageNameOfBondingApplication() {
        return callServiceIfEnabled(
                s -> s.getPackageNameOfBondingApplication(this, mAttributionSource), null);
    }

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED,
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
                BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED
            })
    public @interface ConnectionReturnValues {}

    /**
     * Connects all user enabled and supported bluetooth profiles between the local and remote
     * device. If no profiles are user enabled (e.g. first connection), we connect all supported
     * profiles. If the device is not already connected, this will page the device before initiating
     * profile connections. Connection is asynchronous and you should listen to each profile's
     * broadcast intent ACTION_CONNECTION_STATE_CHANGED to verify whether connection was successful.
     * For example, to verify a2dp is connected, you would listen for {@link
     * BluetoothA2dp#ACTION_CONNECTION_STATE_CHANGED}
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_CONNECT} permission. Additionally, an app must either
     * have {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} permission or be associated
     * with the Companion Device manager (see {@link
     * android.companion.CompanionDeviceManager#associate( AssociationRequest,
     * android.companion.CompanionDeviceManager.Callback, Handler)}).
     *
     * @return whether the messages were successfully sent to try to connect all profiles
     * @throws IllegalArgumentException if the device address is invalid
     */
    @FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    public @ConnectionReturnValues int connect() {
        if (DBG) log("connect()");
        return callServiceIfEnabled(
                s -> s.connectAllEnabledProfiles(this, mAttributionSource),
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
    }

    /**
     * Disconnects all connected bluetooth profiles between the local and remote device.
     * Disconnection is asynchronous, so you should listen to each profile's broadcast intent
     * ACTION_CONNECTION_STATE_CHANGED to verify whether disconnection was successful. For example,
     * to verify a2dp is disconnected, you would listen for {@link
     * BluetoothA2dp#ACTION_CONNECTION_STATE_CHANGED}. Once all profiles have disconnected, the ACL
     * link should come down and {@link #ACTION_ACL_DISCONNECTED} should be broadcast.
     *
     * <p>In the rare event that one or more profiles fail to disconnect, call this method again to
     * send another request to disconnect each connected profile.
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_CONNECT} permission. Additionally, an app must either
     * have both {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} or be associated with the
     * Companion Device manager (see {@link android.companion.CompanionDeviceManager#associate(
     * AssociationRequest, android.companion.CompanionDeviceManager.Callback, Handler)}).
     *
     * @return whether the messages were successfully sent to try to disconnect all profiles
     * @throws IllegalArgumentException if the device address is invalid
     */
    @FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    public @ConnectionReturnValues int disconnect() {
        if (DBG) log("disconnect()");
        if (Flags.fixNoAclDisconnectedIntent()) {
            return callServiceIfEnabled(
                    s -> s.disconnectAllAcl(this, mAttributionSource),
                    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
        } else {
            return callServiceIfEnabled(
                    s -> s.disconnectAllEnabledProfiles(this, mAttributionSource),
                    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
        }
    }

    /**
     * Returns whether there is an open connection to this device.
     *
     * @return True if there is at least one open connection to this device.
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean isConnected() {
        return callServiceIfEnabled(
                s ->
                        s.getConnectionState(this, mAttributionSource)
                                != CONNECTION_STATE_DISCONNECTED,
                false);
    }

    /**
     * Returns the ACL connection handle associated with an open connection to this device on the
     * given transport.
     *
     * <p>This handle is a unique identifier for the connection while it remains active. Refer to
     * the Bluetooth Core Specification Version 5.4 Vol 4 Part E Section 5.3.1 Controller Handles
     * for details.
     *
     * @return the ACL handle, or {@link BluetoothDevice#ERROR} if no connection currently exists on
     *     the given transport.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public int getConnectionHandle(@Transport int transport) {
        return callServiceIfEnabled(
                s -> s.getConnectionHandle(this, transport, mAttributionSource),
                BluetoothDevice.ERROR);
    }

    /**
     * Returns whether there is an open connection to this device that has been encrypted.
     *
     * @return True if there is at least one encrypted connection to this device.
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean isEncrypted() {
        return callServiceIfEnabled(
                s -> s.getConnectionState(this, mAttributionSource) > CONNECTION_STATE_CONNECTED,
                false);
    }

    /**
     * Get the Bluetooth class of the remote device.
     *
     * @return Bluetooth class object, or null on error
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public BluetoothClass getBluetoothClass() {
        return callServiceIfEnabled(
                s -> intToClass(s.getRemoteClass(this, mAttributionSource)), null);
    }

    private static @Nullable BluetoothClass intToClass(int classInt) {
        if (classInt == BluetoothClass.ERROR) return null;
        return new BluetoothClass(classInt);
    }

    /**
     * Returns the supported features (UUIDs) of the remote device.
     *
     * <p>This method does not start a service discovery procedure to retrieve the UUIDs from the
     * remote device. Instead, the local cached copy of the service UUIDs are returned.
     *
     * <p>Use {@link #fetchUuidsWithSdp} if fresh UUIDs are desired.
     *
     * @return the supported features (UUIDs) of the remote device, or null on error
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public ParcelUuid[] getUuids() {
        // Exceptionally allow call to go through during TURNING_ON, as this method can be called in
        // response to loading the stored bonded devices.
        return callServiceIfEnabling(
                mAdapter,
                this::getServiceInternal,
                s -> parcelListToArray(s.getRemoteUuids(this, mAttributionSource)),
                null);
    }

    private static @Nullable ParcelUuid[] parcelListToArray(@Nullable List<ParcelUuid> uuids) {
        if (uuids == null) {
            return null;
        }
        return uuids.toArray(new ParcelUuid[uuids.size()]);
    }

    /**
     * Performs a service discovery on the remote device to get the UUIDs supported, allowing the
     * caller to specify the transport.
     *
     * <p>This API is asynchronous. When discovery completes, the {@link #ACTION_UUID} intent is
     * sent, containing the UUIDs supported by the remote device for the specified transport(s).
     * Cached UUIDs may be sent if an error occurs or if discovery is lengthy. Use {@link #getUuids}
     * to retrieve cached UUIDs without initiating discovery.
     *
     * <p>The implementation already handles invalid transport arguments by throwing
     * IllegalArgumentException and returns false when Bluetooth is disabled, covering the contract
     * verification points.
     *
     * @param transport The transport to use for discovery: {@link #TRANSPORT_BREDR} for Classic
     *     Bluetooth (SDP), {@link #TRANSPORT_LE} for Bluetooth Low Energy (GATT), or {@link
     *     #TRANSPORT_AUTO} to discover on all applicable transports (typically BR/EDR and LE for
     *     dual-mode devices). * @return False if the check fails, True if the process of initiating
     *     an ACL connection to the remote device was started or cached UUIDs will be broadcast with
     *     the specific transport.
     */
    @FlaggedApi(Flags.FLAG_EXPLICIT_UUID_TRANSPORT_API)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean fetchUuids(@Transport int transport) {
        if (DBG) log("fetchUuids(transport=" + transport);
        if (transport != TRANSPORT_AUTO
                && transport != TRANSPORT_BREDR
                && transport != TRANSPORT_LE) {
            throw new IllegalArgumentException("Invalid transport value: " + transport);
        }
        return callServiceIfEnabled(
                s -> s.fetchRemoteUuids(this, transport, mAttributionSource), false);
    }

    /**
     * Performs a service discovery on the remote device to get the UUIDs supported.
     *
     * <p>This API is asynchronous and {@link #ACTION_UUID} intent is sent, with the UUIDs supported
     * by the remote end. If there is an error in getting the SDP records or if the process takes a
     * long time, or the device is bonding and we have its UUIDs cached, {@link #ACTION_UUID} intent
     * is sent with the UUIDs that is currently present in the cache. Clients should use the {@link
     * #getUuids} to get UUIDs if service discovery is not to be performed. If there is an ongoing
     * bonding process, service discovery or device inquiry, the request will be queued.
     *
     * <p>To explicitly fetch UUIDs across all transports, use {@link #fetchUuids(int)} by calling
     * {@code fetchUuids(BluetoothDevice.TRANSPORT_AUTO)}.
     *
     * @return False if the check fails, True if the process of initiating an ACL connection to the
     *     remote device was started or cached UUIDs will be broadcast.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean fetchUuidsWithSdp() {
        return fetchUuidsWithSdp(TRANSPORT_AUTO);
    }

    /**
     * Perform a service discovery on the remote device to get the UUIDs supported with the specific
     * transport.
     *
     * <p>This API is asynchronous and {@link #ACTION_UUID} intent is sent, with the UUIDs supported
     * by the remote end. If there is an error in getting the SDP or GATT records or if the process
     * takes a long time, or the device is bonding and we have its UUIDs cached, {@link
     * #ACTION_UUID} intent is sent with the UUIDs that is currently present in the cache. Clients
     * should use the {@link #getUuids} to get UUIDs if service discovery is not to be performed. If
     * there is an ongoing bonding process, service discovery or device inquiry, the request will be
     * queued.
     *
     * <p>For more explicit control over the transport type used for UUID fetching, use {@link
     * #fetchUuids(int)}, specifying one of {@link BluetoothDevice#TRANSPORT_AUTO}, {@link
     * BluetoothDevice#TRANSPORT_BREDR}, or {@link BluetoothDevice#TRANSPORT_LE}.
     *
     * <p>Requires the {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} permission only when
     * {@code transport} is not {@code #TRANSPORT_AUTO}.
     *
     * <p>The {@link android.Manifest.permission#BLUETOOTH_CONNECT} permission is always enforced.
     *
     * @param transport - provide type of transport (e.g. LE or Classic).
     * @return False if the check fails, True if the process of initiating an ACL connection to the
     *     remote device was started or cached UUIDs will be broadcast with the specific transport.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    public boolean fetchUuidsWithSdp(@Transport int transport) {
        if (DBG) log("fetchUuidsWithSdp()");
        return callServiceIfEnabled(
                s -> s.fetchRemoteUuidsWithSdp(this, transport, mAttributionSource), false);
    }

    /**
     * Perform a service discovery on the remote device to get the SDP records associated with the
     * specified UUID.
     *
     * <p>This API is asynchronous and {@link #ACTION_SDP_RECORD} intent is sent, with the SDP
     * records found on the remote end. If there is an error in getting the SDP records or if the
     * process takes a long time, {@link #ACTION_SDP_RECORD} intent is sent with an status value in
     * {@link #EXTRA_SDP_SEARCH_STATUS} different from 0. Detailed status error codes can be found
     * by members of the Bluetooth package in the AbstractionLayer class.
     *
     * <p>The SDP record data will be stored in the intent as {@link #EXTRA_SDP_RECORD}. The object
     * type will match one of the SdpXxxRecord types, depending on the UUID searched for.
     *
     * @return False if the check fails, True if the process of initiating an ACL connection to the
     *     remote device was started.
     */
    @Hide
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean sdpSearch(ParcelUuid uuid) {
        if (DBG) log("sdpSearch()");
        return callServiceIfEnabled(s -> s.sdpSearch(this, uuid, mAttributionSource), false);
    }

    /**
     * Set the pin during pairing when the pairing method is {@link #PAIRING_VARIANT_PIN}
     *
     * <p>When the calling application targets API level 37 or higher, {@link
     * android.Manifest.permission#BLUETOOTH_PRIVILEGED} is required.
     *
     * @deprecated Only privileged apps should be setting the pin code. General use of this API can
     *     interfere with pairing or cause security problems. Use {@link #setPin(String)} instead.
     * @return true pin has been set false for error
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    @Deprecated
    @FlaggedApi(Flags.FLAG_APAIRING_26Q2_PERMISSION_IMPROVEMENTS)
    public boolean setPin(byte[] pin) {
        if (DBG) log("setPin()");
        return callServiceIfEnabled(
                s -> s.setPin(this, true, pin.length, pin, mAttributionSource), false);
    }

    /**
     * Set the pin during pairing when the pairing method is {@link #PAIRING_VARIANT_PIN}
     *
     * <p>When the calling application targets API level 37 or higher, {@link
     * android.Manifest.permission#BLUETOOTH_PRIVILEGED} is required.
     *
     * @return true pin has been set false for error
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    public boolean setPin(@NonNull String pin) {
        byte[] pinBytes = convertPinToBytes(pin);
        if (pinBytes == null) {
            return false;
        }
        return callServiceIfEnabled(
                s -> s.setPin(this, true, pinBytes.length, pinBytes, mAttributionSource), false);
    }

    /**
     * Confirm passkey for {@link #PAIRING_VARIANT_PASSKEY_CONFIRMATION} pairing.
     *
     * @return true confirmation has been sent out false for error
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean setPairingConfirmation(boolean confirm) {
        if (DBG) log("setPairingConfirmation()");
        return callServiceIfEnabled(
                s -> s.setPairingConfirmation(this, confirm, mAttributionSource), false);
    }

    boolean isBluetoothEnabled() {
        boolean ret = false;
        if (mAdapter != null && mAdapter.isEnabled()) {
            ret = true;
        }
        return ret;
    }

    /**
     * Gets whether the phonebook access is allowed for this bluetooth device
     *
     * @return Whether the phonebook access is allowed to this device. Can be {@link
     *     #ACCESS_UNKNOWN}, {@link #ACCESS_ALLOWED} or {@link #ACCESS_REJECTED}.
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public @AccessPermission int getPhonebookAccessPermission() {
        return callServiceIfEnabled(
                s -> s.getPhonebookAccessPermission(this, mAttributionSource), ACCESS_UNKNOWN);
    }

    /**
     * Sets whether the {@link BluetoothDevice} enters silence mode. Audio will not be routed to the
     * {@link BluetoothDevice} if set to {@code true}.
     *
     * <p>When the {@link BluetoothDevice} enters silence mode, and the {@link BluetoothDevice} is
     * an active device (for A2DP or HFP), the active device for that profile will be set to null.
     * If the {@link BluetoothDevice} exits silence mode while the A2DP or HFP active device is
     * null, the {@link BluetoothDevice} will be set as the active device for that profile. If the
     * {@link BluetoothDevice} is disconnected, it exits silence mode. If the {@link
     * BluetoothDevice} is set as the active device for A2DP or HFP, while silence mode is enabled,
     * then the device will exit silence mode. If the {@link BluetoothDevice} is in silence mode,
     * AVRCP position change event and HFP AG indicators will be disabled. If the {@link
     * BluetoothDevice} is not connected with A2DP or HFP, it cannot enter silence mode.
     *
     * @param silence true to enter silence mode, false to exit
     * @return true on success, false on error.
     * @throws IllegalStateException if Bluetooth is not turned ON.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean setSilenceMode(boolean silence) {
        if (DBG) log("setSilenceMode()");
        return callServiceIfEnabled(
                s -> s.setSilenceMode(this, silence, mAttributionSource), false);
    }

    /**
     * Check whether the {@link BluetoothDevice} is in silence mode
     *
     * @return true on device in silence mode, otherwise false.
     * @throws IllegalStateException if Bluetooth is not turned ON.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean isInSilenceMode() {
        return callServiceIfEnabled(s -> s.getSilenceMode(this, mAttributionSource), false);
    }

    /**
     * Sets whether the phonebook access is allowed to this device.
     *
     * @param value Can be {@link #ACCESS_UNKNOWN}, {@link #ACCESS_ALLOWED} or {@link
     *     #ACCESS_REJECTED}.
     * @return Whether the value has been successfully set.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean setPhonebookAccessPermission(@AccessPermission int value) {
        if (DBG) log("setPhonebookAccessPermission()");
        return callServiceIfEnabled(
                s -> s.setPhonebookAccessPermission(this, value, mAttributionSource), false);
    }

    /**
     * Gets whether message access is allowed to this bluetooth device
     *
     * @return Whether the message access is allowed to this device.
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public @AccessPermission int getMessageAccessPermission() {
        return callServiceIfEnabled(
                s -> s.getMessageAccessPermission(this, mAttributionSource), ACCESS_UNKNOWN);
    }

    /**
     * Sets whether the message access is allowed to this device.
     *
     * @param value Can be {@link #ACCESS_UNKNOWN} if the device is unbonded, {@link
     *     #ACCESS_ALLOWED} if the permission is being granted, or {@link #ACCESS_REJECTED} if the
     *     permission is not being granted.
     * @return Whether the value has been successfully set.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean setMessageAccessPermission(@AccessPermission int value) {
        // Validates param value is one of the accepted constants
        if (value != ACCESS_ALLOWED && value != ACCESS_REJECTED && value != ACCESS_UNKNOWN) {
            throw new IllegalArgumentException(value + "is not a valid AccessPermission value");
        }
        if (DBG) log("setMessageAccessPermission()");
        return callServiceIfEnabled(
                s -> s.setMessageAccessPermission(this, value, mAttributionSource), false);
    }

    /**
     * Gets whether sim access is allowed for this bluetooth device
     *
     * @return Whether the Sim access is allowed to this device.
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public @AccessPermission int getSimAccessPermission() {
        return callServiceIfEnabled(
                s -> s.getSimAccessPermission(this, mAttributionSource), ACCESS_UNKNOWN);
    }

    /**
     * Sets whether the Sim access is allowed to this device.
     *
     * @param value Can be {@link #ACCESS_UNKNOWN} if the device is unbonded, {@link
     *     #ACCESS_ALLOWED} if the permission is being granted, or {@link #ACCESS_REJECTED} if the
     *     permission is not being granted.
     * @return Whether the value has been successfully set.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean setSimAccessPermission(int value) {
        if (DBG) log("setSimAccessPermission()");
        return callServiceIfEnabled(
                s -> s.setSimAccessPermission(this, value, mAttributionSource), false);
    }

    /**
     * Create an RFCOMM {@link BluetoothSocket} ready to start a secure outgoing connection to this
     * remote device on given channel.
     *
     * <p>The remote device will be authenticated and communication on this socket will be
     * encrypted.
     *
     * <p>Use this socket only if an authenticated socket link is possible. Authentication refers to
     * the authentication of the link key to prevent person-in-the-middle type of attacks. For
     * example, for Bluetooth 2.1 devices, if any of the devices does not have an input and output
     * capability or just has the ability to display a numeric key, a secure socket connection is
     * not possible. For more details, refer to the Security Model section 5.2 (vol 3) of Bluetooth
     * Core Specification version 2.1 + EDR.
     *
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection.
     *
     * <p>Valid RFCOMM channels are in range 1 to 30.
     *
     * @param channel RFCOMM channel to connect to
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     *     permissions
     */
    @Hide
    @UnsupportedAppUsage
    @RequiresNoPermission
    public BluetoothSocket createRfcommSocket(int channel) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }
        if (channel != BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP
                && (channel < 1 || channel > BluetoothSocket.MAX_RFCOMM_CHANNEL)) {
            throw new IOException("Invalid RFCOMM channel: " + channel);
        }
        return new BluetoothSocket(
                mAdapter, this, BluetoothSocket.TYPE_RFCOMM, true, true, channel, null);
    }

    /**
     * Create an L2cap {@link BluetoothSocket} ready to start a secure outgoing connection to this
     * remote device on given channel.
     *
     * <p>The remote device will be authenticated and communication on this socket will be
     * encrypted.
     *
     * <p>Use this socket only if an authenticated socket link is possible. Authentication refers to
     * the authentication of the link key to prevent person-in-the-middle type of attacks. For
     * example, for Bluetooth 2.1 devices, if any of the devices does not have an input and output
     * capability or just has the ability to display a numeric key, a secure socket connection is
     * not possible. For more details, refer to the Security Model section 5.2 (vol 3) of Bluetooth
     * Core Specification version 2.1 + EDR.
     *
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection.
     *
     * <p>Valid L2CAP PSM channels are in range 1 to 2^16.
     *
     * @param channel L2cap PSM/channel to connect to
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     */
    @Hide
    @RequiresNoPermission
    public BluetoothSocket createL2capSocket(int channel) {
        return new BluetoothSocket(
                mAdapter, this, BluetoothSocket.TYPE_L2CAP, true, true, channel, null);
    }

    /**
     * Create an L2cap {@link BluetoothSocket} ready to start an insecure outgoing connection to
     * this remote device on given channel.
     *
     * <p>The remote device will be not authenticated and communication on this socket will not be
     * encrypted.
     *
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection.
     *
     * <p>Valid L2CAP PSM channels are in range 1 to 2^16.
     *
     * @param channel L2cap PSM/channel to connect to
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     */
    @Hide
    @RequiresNoPermission
    public BluetoothSocket createInsecureL2capSocket(int channel) {
        return new BluetoothSocket(
                mAdapter, this, BluetoothSocket.TYPE_L2CAP, false, false, channel, null);
    }

    /**
     * Create an RFCOMM {@link BluetoothSocket} ready to start a secure outgoing connection to this
     * remote device using SDP lookup of uuid.
     *
     * <p>This is designed to be used with {@link
     * BluetoothAdapter#listenUsingRfcommWithServiceRecord} for peer-peer Bluetooth applications.
     *
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection. This will also
     * perform an SDP lookup of the given uuid to determine which channel to connect to.
     *
     * <p>The remote device will be authenticated and communication on this socket will be
     * encrypted.
     *
     * <p>Use this socket only if an authenticated socket link is possible. Authentication refers to
     * the authentication of the link key to prevent person-in-the-middle type of attacks. For
     * example, for Bluetooth 2.1 devices, if any of the devices does not have an input and output
     * capability or just has the ability to display a numeric key, a secure socket connection is
     * not possible. In such a case, use {@link #createInsecureRfcommSocketToServiceRecord}. For
     * more details, refer to the Security Model section 5.2 (vol 3) of Bluetooth Core Specification
     * version 2.1 + EDR.
     *
     * <p>Hint: If you are connecting to a Bluetooth serial board then try using the well-known SPP
     * UUID 00001101-0000-1000-8000-00805F9B34FB. However if you are connecting to an Android peer
     * then please generate your own unique UUID.
     *
     * @param uuid service record uuid to lookup RFCOMM channel
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     *     permissions
     */
    @RequiresNoPermission
    public BluetoothSocket createRfcommSocketToServiceRecord(UUID uuid) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }

        return new BluetoothSocket(
                mAdapter, this, BluetoothSocket.TYPE_RFCOMM, true, true, -1, new ParcelUuid(uuid));
    }

    /**
     * Create an RFCOMM {@link BluetoothSocket} socket ready to start an insecure outgoing
     * connection to this remote device using SDP lookup of uuid.
     *
     * <p>The communication channel will not have an authenticated link key i.e. it will be subject
     * to person-in-the-middle attacks. For Bluetooth 2.1 devices, the link key will be encrypted,
     * as encryption is mandatory. For legacy devices (pre Bluetooth 2.1 devices) the link key will
     * be not be encrypted. Use {@link #createRfcommSocketToServiceRecord} if an encrypted and
     * authenticated communication channel is desired.
     *
     * <p>This is designed to be used with {@link
     * BluetoothAdapter#listenUsingInsecureRfcommWithServiceRecord} for peer-peer Bluetooth
     * applications.
     *
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection. This will also
     * perform an SDP lookup of the given uuid to determine which channel to connect to.
     *
     * <p>The remote device will be authenticated and communication on this socket will be
     * encrypted.
     *
     * <p>Hint: If you are connecting to a Bluetooth serial board then try using the well-known SPP
     * UUID 00001101-0000-1000-8000-00805F9B34FB. However if you are connecting to an Android peer
     * then please generate your own unique UUID.
     *
     * @param uuid service record uuid to lookup RFCOMM channel
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     *     permissions
     */
    @RequiresNoPermission
    public BluetoothSocket createInsecureRfcommSocketToServiceRecord(UUID uuid) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }
        return new BluetoothSocket(
                mAdapter,
                this,
                BluetoothSocket.TYPE_RFCOMM,
                false,
                false,
                -1,
                new ParcelUuid(uuid));
    }

    /**
     * Construct an insecure RFCOMM socket ready to start an outgoing connection. Call #connect on
     * the returned #BluetoothSocket to begin the connection. The remote device will not be
     * authenticated and communication on this socket will not be encrypted.
     *
     * @param channel remote channel
     * @return An RFCOMM BluetoothSocket
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     *     permissions.
     */
    @Hide
    @UnsupportedAppUsage(
            publicAlternatives = "Use {@link #createInsecureRfcommSocketToServiceRecord} instead.")
    @RequiresNoPermission
    public BluetoothSocket createInsecureRfcommSocket(int channel) throws IOException {
        // TODO replace usage to createInsecureRfcommSocketToServiceRecord
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }

        if (channel != BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP
                && (channel < 1 || channel > BluetoothSocket.MAX_RFCOMM_CHANNEL)) {
            throw new IOException("Invalid RFCOMM channel: " + channel);
        }
        return new BluetoothSocket(
                mAdapter, this, BluetoothSocket.TYPE_RFCOMM, false, false, channel, null);
    }

    /**
     * Construct a SCO socket ready to start an outgoing connection. Call #connect on the returned
     * #BluetoothSocket to begin the connection.
     *
     * @return a SCO BluetoothSocket
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     *     permissions.
     */
    @Hide
    @UnsupportedAppUsage
    @RequiresNoPermission
    public BluetoothSocket createScoSocket() throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }
        return new BluetoothSocket(mAdapter, this, BluetoothSocket.TYPE_SCO, true, true, -1, null);
    }

    /**
     * Check that a pin is valid and convert to byte array.
     *
     * <p>Bluetooth pin's are 1 to 16 bytes of UTF-8 characters.
     *
     * @param pin pin as java String
     * @return the pin code as a UTF-8 byte array, or null if it is an invalid Bluetooth pin.
     */
    @Hide
    @UnsupportedAppUsage
    @RequiresNoPermission
    public static byte[] convertPinToBytes(String pin) {
        if (pin == null) {
            return null;
        }
        byte[] pinBytes = pin.getBytes(StandardCharsets.UTF_8);
        if (pinBytes.length <= 0 || pinBytes.length > 16) {
            return null;
        }
        return pinBytes;
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client. The callback is
     * used to deliver results to Caller, such as connection status as well as any further GATT
     * client operations. The method returns a BluetoothGatt instance. You can use BluetoothGatt to
     * conduct GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     *     automatically connect as soon as the remote device becomes available (true).
     * @throws IllegalArgumentException if callback is null
     * @deprecated Use {@link #connectGatt(BluetoothGattConnectionSettings, Executor,
     *     BluetoothGattCallback)}.
     */
    @FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
    @Deprecated
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public BluetoothGatt connectGatt(
            Context context, boolean autoConnect, BluetoothGattCallback callback) {
        return (connectGatt(
                new BluetoothGattConnectionSettings.Builder()
                        .setAutoConnectEnabled(autoConnect)
                        .setTransport(TRANSPORT_AUTO)
                        .setAutomaticMtuEnabled(false)
                        .build(),
                new BluetoothUtils.SynchronousExecutor(),
                callback));
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client. The callback is
     * used to deliver results to Caller, such as connection status as well as any further GATT
     * client operations. The method returns a BluetoothGatt instance. You can use BluetoothGatt to
     * conduct GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     *     automatically connect as soon as the remote device becomes available (true).
     * @param transport preferred transport for GATT connections to remote dual-mode devices {@link
     *     BluetoothDevice#TRANSPORT_AUTO} or {@link BluetoothDevice#TRANSPORT_BREDR} or {@link
     *     BluetoothDevice#TRANSPORT_LE}
     * @throws IllegalArgumentException if callback is null
     * @deprecated Use {@link #connectGatt(BluetoothGattConnectionSettings, Executor,
     *     BluetoothGattCallback)}.
     */
    @FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
    @Deprecated
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public BluetoothGatt connectGatt(
            Context context, boolean autoConnect, BluetoothGattCallback callback, int transport) {
        return (connectGatt(
                new BluetoothGattConnectionSettings.Builder()
                        .setAutoConnectEnabled(autoConnect)
                        .setTransport(transport)
                        .setAutomaticMtuEnabled(false)
                        .build(),
                new BluetoothUtils.SynchronousExecutor(),
                callback));
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client. The callback is
     * used to deliver results to Caller, such as connection status as well as any further GATT
     * client operations. The method returns a BluetoothGatt instance. You can use BluetoothGatt to
     * conduct GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     *     automatically connect as soon as the remote device becomes available (true).
     * @param transport preferred transport for GATT connections to remote dual-mode devices {@link
     *     BluetoothDevice#TRANSPORT_AUTO} or {@link BluetoothDevice#TRANSPORT_BREDR} or {@link
     *     BluetoothDevice#TRANSPORT_LE}
     * @param phy preferred PHY for connections to remote LE device. Bitwise OR of any of {@link
     *     BluetoothDevice#PHY_LE_1M_MASK}, {@link BluetoothDevice#PHY_LE_2M_MASK}, and {@link
     *     BluetoothDevice#PHY_LE_CODED_MASK}. This option does not take effect if {@code
     *     autoConnect} is set to true.
     * @throws NullPointerException if callback is null
     * @deprecated Use {@link #connectGatt(BluetoothGattConnectionSettings, Executor,
     *     BluetoothGattCallback)}.
     */
    @FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
    @Deprecated
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public BluetoothGatt connectGatt(
            Context context,
            boolean autoConnect,
            BluetoothGattCallback callback,
            int transport,
            int phy) {
        return (connectGatt(
                new BluetoothGattConnectionSettings.Builder()
                        .setAutoConnectEnabled(autoConnect)
                        .setTransport(transport)
                        .setAutomaticMtuEnabled(false)
                        .build(),
                new BluetoothUtils.SynchronousExecutor(),
                callback));
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client. The callback is
     * used to deliver results to Caller, such as connection status as well as any further GATT
     * client operations. The method returns a BluetoothGatt instance. You can use BluetoothGatt to
     * conduct GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     *     automatically connect as soon as the remote device becomes available (true).
     * @param transport preferred transport for GATT connections to remote dual-mode devices {@link
     *     BluetoothDevice#TRANSPORT_AUTO} or {@link BluetoothDevice#TRANSPORT_BREDR} or {@link
     *     BluetoothDevice#TRANSPORT_LE}
     * @param phy preferred PHY for connections to remote LE device. Bitwise OR of any of {@link
     *     BluetoothDevice#PHY_LE_1M_MASK}, {@link BluetoothDevice#PHY_LE_2M_MASK}, an d{@link
     *     BluetoothDevice#PHY_LE_CODED_MASK}. This option does not take effect if {@code
     *     autoConnect} is set to true.
     * @param handler The handler to use for the callback. If {@code null}, callbacks will happen on
     *     an un-specified background thread.
     * @throws NullPointerException if callback is null
     * @deprecated Use {@link #connectGatt(BluetoothGattConnectionSettings, Executor,
     *     BluetoothGattCallback)}.
     */
    @FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
    @Deprecated
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public BluetoothGatt connectGatt(
            Context context,
            boolean autoConnect,
            BluetoothGattCallback callback,
            int transport,
            int phy,
            Handler handler) {
        return (connectGatt(
                new BluetoothGattConnectionSettings.Builder()
                        .setAutoConnectEnabled(autoConnect)
                        .setTransport(transport)
                        .setAutomaticMtuEnabled(false)
                        .build(),
                handler != null ? handler::post : new BluetoothUtils.SynchronousExecutor(),
                callback));
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client. The callback is
     * used to deliver results to Caller, such as connection status as well as any further GATT
     * client operations. The method returns a BluetoothGatt instance. You can use BluetoothGatt to
     * conduct GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     *     automatically connect as soon as the remote device becomes available (true).
     * @param transport preferred transport for GATT connections to remote dual-mode devices {@link
     *     BluetoothDevice#TRANSPORT_AUTO} or {@link BluetoothDevice#TRANSPORT_BREDR} or {@link
     *     BluetoothDevice#TRANSPORT_LE}
     * @param opportunistic Whether this GATT client is opportunistic. An opportunistic GATT client
     *     does not hold a GATT connection. It automatically disconnects when no other GATT
     *     connections are active for the remote device.
     * @param phy preferred PHY for connections to remote LE device. Bitwise OR of any of {@link
     *     BluetoothDevice#PHY_LE_1M_MASK}, {@link BluetoothDevice#PHY_LE_2M_MASK}, an d{@link
     *     BluetoothDevice#PHY_LE_CODED_MASK}. This option does not take effect if {@code
     *     autoConnect} is set to true.
     * @param executor The executor to use for the callback.
     * @return A BluetoothGatt instance. You can use BluetoothGatt to conduct GATT client
     *     operations.
     * @deprecated Use {@link #connectGatt(BluetoothGattConnectionSettings, Executor,
     *     BluetoothGattCallback)}.
     */
    @FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
    @Deprecated
    @Hide
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public BluetoothGatt connectGatt(
            Context context,
            boolean autoConnect,
            @NonNull BluetoothGattCallback callback,
            int transport,
            boolean opportunistic,
            int phy,
            @NonNull @CallbackExecutor Executor executor) {
        return (connectGatt(
                new BluetoothGattConnectionSettings.Builder()
                        .setAutoConnectEnabled(autoConnect)
                        .setTransport(transport)
                        .setOpportunisticEnabled(opportunistic)
                        .setAutomaticMtuEnabled(false)
                        .build(),
                executor,
                callback));
    }

    /**
     * Connect to the GATT Server hosted by the given device. Caller acts as a GATT client. {@link
     * BluetoothGattCallback} of {@link BluetoothGattConnectionSettings} will deliver results to the
     * Caller, such as connection updates and GATT client operation results. The method returns a
     * {@link BluetoothGatt} instance. The application can use BluetoothGatt to conduct GATT client
     * operations.
     *
     * @param gattConnectionSettings {@link BluetoothGattConnectionSettings} objects with required
     *     gatt settings for the GATT connection
     * @param executor The executor to use for the callback.
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @return A BluetoothGatt instance. You can use BluetoothGatt to conduct GATT client
     *     operations.
     * @throws NullPointerException if gattConnectionSettings, callback or executor is null.
     */
    @FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public @Nullable BluetoothGatt connectGatt(
            @NonNull BluetoothGattConnectionSettings gattConnectionSettings,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BluetoothGattCallback callback) {
        requireNonNull(gattConnectionSettings);
        requireNonNull(executor);
        requireNonNull(callback);

        // TODO(Bluetooth) check whether platform support BLE
        //     Do the check here or in GattServer?
        IBluetoothGatt iGatt = mAdapter.getBluetoothGatt();
        if (iGatt == null) {
            // BLE is not supported
            return null;
        } else if (NULL_MAC_ADDRESS.equals(mAddress)) {
            Log.e(TAG, "Unable to connect gatt, invalid address " + mAddress);
            return null;
        }
        BluetoothGatt gatt =
                new BluetoothGatt(
                        iGatt,
                        this,
                        mAttributionSource,
                        gattConnectionSettings,
                        callback,
                        executor);
        return gatt;
    }

    /**
     * Create a Bluetooth L2CAP Connection-oriented Channel (CoC) {@link BluetoothSocket} that can
     * be used to start a secure outgoing connection to the remote device with the same dynamic
     * protocol/service multiplexer (PSM) value. The supported Bluetooth transport is LE only.
     *
     * <p>This is designed to be used with {@link BluetoothAdapter#listenUsingL2capChannel()} for
     * peer-peer Bluetooth applications.
     *
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection.
     *
     * <p>Application using this API is responsible for obtaining PSM value from remote device.
     *
     * <p>The remote device will be authenticated and communication on this socket will be
     * encrypted.
     *
     * <p>Use this socket if an authenticated socket link is possible. Authentication refers to the
     * authentication of the link key to prevent person-in-the-middle type of attacks.
     *
     * @param psm dynamic PSM value from remote device
     * @return a CoC #BluetoothSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     *     permissions
     */
    @RequiresNoPermission
    public @NonNull BluetoothSocket createL2capChannel(int psm) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "createL2capChannel: Bluetooth is not enabled");
            throw new IOException();
        }
        if (DBG) Log.d(TAG, "createL2capChannel: psm=" + psm);
        return new BluetoothSocket(mAdapter, this, BluetoothSocket.TYPE_LE, true, true, psm, null);
    }

    /**
     * Create a Bluetooth L2CAP Connection-oriented Channel (CoC) {@link BluetoothSocket} that can
     * be used to start a secure outgoing connection to the remote device with the same dynamic
     * protocol/service multiplexer (PSM) value. The supported Bluetooth transport is LE only.
     *
     * <p>This is designed to be used with {@link
     * BluetoothAdapter#listenUsingInsecureL2capChannel()} for peer-peer Bluetooth applications.
     *
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection.
     *
     * <p>Application using this API is responsible for obtaining PSM value from remote device.
     *
     * <p>The communication channel may not have an authenticated link key, i.e. it may be subject
     * to person-in-the-middle attacks. Use {@link #createL2capChannel(int)} if an encrypted and
     * authenticated communication channel is possible.
     *
     * @param psm dynamic PSM value from remote device
     * @return a CoC #BluetoothSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     *     permissions
     */
    @RequiresNoPermission
    public @NonNull BluetoothSocket createInsecureL2capChannel(int psm) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "createInsecureL2capChannel: Bluetooth is not enabled");
            throw new IOException();
        }
        if (DBG) {
            Log.d(TAG, "createInsecureL2capChannel: psm=" + psm);
        }
        return new BluetoothSocket(
                mAdapter, this, BluetoothSocket.TYPE_LE, false, false, psm, null);
    }

    /**
     * Creates a client socket to connect to a remote Bluetooth server with the specified socket
     * settings {@link BluetoothSocketSettings} This API is used to connect to a remote server
     * hosted using {@link BluetoothAdapter#listenUsingSocketSettings}.
     *
     * <ul>
     *   <li>For `BluetoothSocket.TYPE_RFCOMM`: The RFCOMM UUID must be provided using {@link
     *       BluetoothSocketSettings#setRfcommUuid()}.
     *   <li>For `BluetoothSocket.TYPE_LE`: The L2cap protocol/service multiplexer (PSM) value must
     *       be provided using {@link BluetoothSocketSettings#setL2capPsm()}.
     * </ul>
     *
     * <p>Application using this API is responsible for obtaining protocol/service multiplexer (psm)
     * value from remote device.
     *
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection.
     *
     * @param settings Bluetooth socket settings {@link BluetoothSocketSettings}.
     * @return a {@link BluetoothSocket} ready for an outgoing connection.
     * @throws IllegalArgumentException if BluetoothSocket#TYPE_RFCOMM socket with no UUID is passed
     *     as input or if BluetoothSocket#TYPE_LE with invalid PSM is passed.
     * @throws IOException on error, for example Bluetooth not available.
     */
    @RequiresNoPermission
    public @NonNull BluetoothSocket createUsingSocketSettings(
            @NonNull BluetoothSocketSettings settings) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "createUsingSocketSettings: Bluetooth is not enabled");
            throw new IOException();
        }
        if (DBG) {
            Log.d(TAG, "createUsingSocketSettings: =" + settings.getL2capPsm());
        }
        ParcelUuid uuid = null;
        int psm = settings.getL2capPsm();
        if (settings.getSocketType() == BluetoothSocket.TYPE_RFCOMM) {
            if (settings.getRfcommUuid() == null) {
                throw new IllegalArgumentException("null uuid");
            }
            uuid = new ParcelUuid(settings.getRfcommUuid());
        } else if (settings.getSocketType() == BluetoothSocket.TYPE_LE) {
            if (psm < 128 || psm > 255) {
                throw new IllegalArgumentException("Invalid PSM/Channel value: " + psm);
            }
        }
        if (settings.getDataPath() == BluetoothSocketSettings.DATA_PATH_NO_OFFLOAD) {
            return new BluetoothSocket(
                    mAdapter,
                    this,
                    settings.getSocketType(),
                    settings.isAuthenticationRequired(),
                    settings.isEncryptionRequired(),
                    psm,
                    uuid);
        } else {
            return new BluetoothSocket(
                    mAdapter,
                    this,
                    settings.getSocketType(),
                    settings.isAuthenticationRequired(),
                    settings.isEncryptionRequired(),
                    psm,
                    uuid,
                    false,
                    false,
                    settings.getDataPath(),
                    settings.getSocketName(),
                    settings.getHubId(),
                    settings.getEndpointId(),
                    settings.getRequestedMaximumPacketSize());
        }
    }

    /**
     * Set a keyed metadata of this {@link BluetoothDevice} to a {@link String} value. Only bonded
     * devices's metadata will be persisted across Bluetooth restart. Metadata will be removed when
     * the device's bond state is moved to {@link #BOND_NONE}.
     *
     * @param key must be within the list of BluetoothDevice.METADATA_*
     * @param value a byte array data to set for key. Must be less than {@link
     *     BluetoothAdapter#METADATA_MAX_LENGTH} characters in length
     * @return true on success, false on error
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean setMetadata(@MetadataKey int key, @NonNull byte[] value) {
        if (DBG) log("setMetadata()");
        if (value.length > METADATA_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "value length is " + value.length + ", should not over " + METADATA_MAX_LENGTH);
        }
        return callServiceIfEnabled(
                s -> s.setMetadata(this, key, value, mAttributionSource), false);
    }

    /**
     * Get a keyed metadata for this {@link BluetoothDevice} as {@link String}
     *
     * @param key must be within the list of BluetoothDevice.METADATA_*
     * @return Metadata of the key as byte array, null on error or not found
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @Nullable byte[] getMetadata(@MetadataKey int key) {
        return callServiceIfEnabled(s -> s.getMetadata(this, key, mAttributionSource), null);
    }

    /**
     * Get the maximum metadata key ID.
     *
     * @return the last supported metadata key
     */
    @Hide
    public static @MetadataKey int getMaxMetadataKey() {
        return METADATA_MAX_KEY;
    }

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"FEATURE_"},
            value = {
                BluetoothStatusCodes.FEATURE_NOT_CONFIGURED,
                BluetoothStatusCodes.FEATURE_SUPPORTED,
                BluetoothStatusCodes.FEATURE_NOT_SUPPORTED,
            })
    public @interface AudioPolicyRemoteSupport {}

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED,
                BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED,
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
                BluetoothStatusCodes.ERROR_PROFILE_NOT_CONNECTED,
            })
    public @interface AudioPolicyReturnValues {}

    /**
     * Returns whether the audio policy feature is supported by both the local and the remote
     * device. This is configured during initiating the connection between the devices through one
     * of the transport protocols (e.g. HFP Vendor specific protocol). So if the API is invoked
     * before this initial configuration is completed, it returns {@link
     * BluetoothStatusCodes#FEATURE_NOT_CONFIGURED} to indicate the remote device has not yet
     * relayed this information. After the internal configuration, the support status will be set to
     * either {@link BluetoothStatusCodes#FEATURE_NOT_SUPPORTED} or {@link
     * BluetoothStatusCodes#FEATURE_SUPPORTED}. The rest of the APIs related to this feature in both
     * {@link BluetoothDevice} and {@link BluetoothSinkAudioPolicy} should be invoked only after
     * getting a {@link BluetoothStatusCodes#FEATURE_SUPPORTED} response from this API.
     *
     * <p>Note that this API is intended to be used by a client device to send these requests to the
     * server represented by this BluetoothDevice object.
     *
     * @return if call audio policy feature is supported by both local and remote device or not
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @AudioPolicyRemoteSupport int isRequestAudioPolicyAsSinkSupported() {
        return callServiceIfEnabled(
                s -> s.isRequestAudioPolicyAsSinkSupported(this, mAttributionSource),
                BluetoothStatusCodes.FEATURE_NOT_CONFIGURED);
    }

    /**
     * Sets call audio preferences and sends them to the remote device.
     *
     * <p>Note that the caller should check if the feature is supported by invoking {@link
     * BluetoothDevice#isRequestAudioPolicyAsSinkSupported} first.
     *
     * <p>This API will throw an exception if the feature is not supported but still invoked.
     *
     * <p>Note that this API is intended to be used by a client device to send these requests to the
     * server represented by this BluetoothDevice object.
     *
     * @param policies call audio policy preferences
     * @return whether audio policy was requested successfully or not
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @AudioPolicyReturnValues int requestAudioPolicyAsSink(
            @NonNull BluetoothSinkAudioPolicy policies) {
        if (DBG) log("requestAudioPolicyAsSink");
        return callServiceIfEnabled(
                s -> s.requestAudioPolicyAsSink(this, policies, mAttributionSource),
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
    }

    /**
     * Gets the call audio preferences for the remote device.
     *
     * <p>Note that the caller should check if the feature is supported by invoking {@link
     * BluetoothDevice#isRequestAudioPolicyAsSinkSupported} first.
     *
     * <p>This API will throw an exception if the feature is not supported but still invoked.
     *
     * <p>This API will return null if 1. The bluetooth service is not started yet, 2. It is invoked
     * for a device which is not bonded, or 3. The used transport, for example, HFP Client profile
     * is not enabled or connected yet.
     *
     * <p>Note that this API is intended to be used by a client device to send these requests to the
     * server represented by this BluetoothDevice object.
     *
     * @return call audio policy as {@link BluetoothSinkAudioPolicy} object
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @Nullable BluetoothSinkAudioPolicy getRequestedAudioPolicyAsSink() {
        return callServiceIfEnabled(
                s -> s.getRequestedAudioPolicyAsSink(this, mAttributionSource), null);
    }

    /**
     * Enable or disable audio low latency for this {@link BluetoothDevice}.
     *
     * @param allowed true if low latency is allowed, false if low latency is disallowed.
     * @return true if the value is successfully set, false if there is a error when setting the
     *     value.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean setLowLatencyAudioAllowed(boolean allowed) {
        if (DBG) log("setLowLatencyAudioAllowed(" + allowed + ")");
        return callServiceIfEnabled(
                s -> s.allowLowLatencyAudio(allowed, this, mAttributionSource), false);
    }

    @Hide
    @IntDef(
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED,
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
                BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SetActiveAudioDevicePolicyReturnValues {}

    /** Active audio device policy for this device */
    @Hide
    @IntDef(
            prefix = "ACTIVE_AUDIO_DEVICE_POLICY_",
            value = {
                ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT,
                ACTIVE_AUDIO_DEVICE_POLICY_ALL_PROFILES_ACTIVE_UPON_CONNECTION,
                ACTIVE_AUDIO_DEVICE_POLICY_ALL_PROFILES_INACTIVE_UPON_CONNECTION
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActiveAudioDevicePolicy {}

    /**
     * Set the active audio device policy for this {@link BluetoothDevice} to indicate what {@link
     * ActiveAudioDevicePolicy} is applied upon device connection.
     *
     * <p>This API allows application to set the audio device profiles active policy upon
     * connection, only bonded device's policy will be persisted across Bluetooth restart. Policy
     * setting will be removed when the device's bond state is moved to {@link #BOND_NONE}.
     *
     * @param activeAudioDevicePolicy is the active audio device policy to set for this device
     * @return whether the policy was set properly
     * @throws IllegalArgumentException if this BluetoothDevice object has an invalid address
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @SetActiveAudioDevicePolicyReturnValues int setActiveAudioDevicePolicy(
            @ActiveAudioDevicePolicy int activeAudioDevicePolicy) {
        if (DBG) log("setActiveAudioDevicePolicy(" + activeAudioDevicePolicy + ")");
        return callServiceIfEnabled(
                s ->
                        s.setActiveAudioDevicePolicy(
                                this, activeAudioDevicePolicy, mAttributionSource),
                ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT);
    }

    /**
     * Get the active audio device policy for this {@link BluetoothDevice}.
     *
     * @return active audio device policy of the device
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @ActiveAudioDevicePolicy int getActiveAudioDevicePolicy() {
        return callServiceIfEnabled(
                s -> s.getActiveAudioDevicePolicy(this, mAttributionSource),
                ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT);
    }

    @Hide
    @IntDef(
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
                BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SetMicrophonePreferredForCallsReturnValues {}

    /**
     * Sets whether this {@link BluetoothDevice} should be the preferred microphone for calls.
     *
     * <p>This API is for Bluetooth audio devices and only sets a preference. The caller is
     * responsible for changing the audio input routing to reflect the preference.
     *
     * @param enabled {@code true} to set the device as the preferred microphone for calls, {@code
     *     false} otherwise.
     * @return Whether the preferred microphone for calls was set properly.
     * @throws IllegalArgumentException if the {@link BluetoothDevice} object has an invalid
     *     address.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @SetMicrophonePreferredForCallsReturnValues int setMicrophonePreferredForCalls(
            boolean enabled) {
        return callServiceIfEnabled(
                s -> s.setMicrophonePreferredForCalls(this, enabled, mAttributionSource),
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
    }

    /**
     * Gets whether this {@link BluetoothDevice} should be the preferred microphone for calls.
     *
     * <p>This API returns the configured preference for whether this device should be the preferred
     * microphone for calls and return {@code true} by default in case of error. It does not reflect
     * the current audio routing.
     *
     * @return {@code true} if the device is the preferred microphone for calls, {@code false}
     *     otherwise.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean isMicrophonePreferredForCalls() {
        return callServiceIfEnabled(
                s -> s.isMicrophonePreferredForCalls(this, mAttributionSource), true);
    }

    @Hide
    @IntDef(
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED,
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
                BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SetOnHeadDetectionParamsReturnValues {}

    /**
     * Sets the on-head detection enabled state for this Bluetooth device.
     *
     * <p>This method is used to inform the Bluetooth system about the on-head detection enabled
     * state of the remote device.
     *
     * @param enabled True if the on-head detection is enabled, false if not enabled.
     * @return Whether the on-head detection enabled state was set properly.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @SetOnHeadDetectionParamsReturnValues int setOnHeadDetectionEnabled(boolean enabled) {
        int enabledState =
                enabled
                        ? ON_HEAD_DETECTION_ENABLED_STATE_ENABLED
                        : ON_HEAD_DETECTION_ENABLED_STATE_DISABLED;
        return callServiceIfEnabled(
                s -> s.setOnHeadDetectionEnabled(this, enabledState, mAttributionSource),
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
    }

    /**
     * Sets whether this Bluetooth device is on-head.
     *
     * <p>This method is used to inform the Bluetooth system about the on-head detection state of
     * the remote device.
     *
     * @param isOnHead True if the device is on-head, false if it is not on-head.
     * @return Whether the on head detection state was set properly.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @SetOnHeadDetectionParamsReturnValues int setOnHead(boolean isOnHead) {
        int state =
                isOnHead ? ON_HEAD_DETECTION_STATE_ON_HEAD : ON_HEAD_DETECTION_STATE_NOT_ON_HEAD;
        return callServiceIfEnabled(
                s -> s.setOnHead(this, state, mAttributionSource),
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED);
    }

    /**
     * Get the number of times {@link ACTION_KEY_MISSING} intent was thrown for this device since
     * the last successful encrypted connection
     *
     * @return number of times {@link ACTION_KEY_MISSING} intent was thrown for this device since
     *     the last successful encrypted connection
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public int getKeyMissingCount() {
        return callServiceIfEnabled(s -> s.getKeyMissingCount(this, mAttributionSource), -1);
    }

    /**
     * Get the encryption status of the connected device. This API will return the encryption state
     * (if connected). The encryption details are enclosed in a {@link EncryptionStatus} object,
     * which can be null if the device is not encrypted (or not connected)
     *
     * @param transport the transport to get the link status for.
     * @return the encryption status of the device, null if the device is not encrypted or not
     *     connected.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public @Nullable EncryptionStatus getEncryptionStatus(@SupportedTransport int transport) {
        if (transport != TRANSPORT_BREDR && transport != TRANSPORT_LE) {
            throw new IllegalArgumentException("Transport(" + transport + ") is not supported");
        }

        return callServiceIfEnabled(
                s ->
                        EncryptionStatus.fromParcel(
                                s.getEncryptionStatus(this, mAttributionSource, transport)),
                null);
    }

    /**
     * Returns whether there is an open connection to this device on the given transport.
     *
     * @return True if there is at least one open connection to this device.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean isConnected(@SupportedTransport int transport) {
        if (transport != TRANSPORT_BREDR && transport != TRANSPORT_LE) {
            throw new IllegalArgumentException("Transport(" + transport + ") is not supported");
        }

        return callServiceIfEnabled(s -> s.isConnected(this, mAttributionSource, transport), false);
    }

    /**
     * Returns the bond status of this device.
     *
     * @param transport the transport to get the bond status for.
     * @return The bond status of this device, or null if the device is not bonded.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_GET_BOND_STATUS)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public @Nullable BondStatus getBondStatus(@SupportedTransport int transport) {
        if (transport != TRANSPORT_BREDR && transport != TRANSPORT_LE) {
            throw new IllegalArgumentException("Transport(" + transport + ") is not supported");
        }

        return callServiceIfEnabled(
                s -> BondStatus.fromParcel(s.getBondStatus(this, mAttributionSource, transport)),
                null);
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    /** A data class for Bluetooth address and address type. */
    public static final class BluetoothAddress implements Parcelable {
        private final @Nullable String mAddress;
        private final @AddressType int mAddressType;

        public BluetoothAddress(@Nullable String address, @AddressType int addressType) {
            mAddress = address;
            mAddressType = addressType;
        }

        /**
         * Returns the address of this {@link BluetoothAddress}.
         *
         * <p>For example, "00:11:22:AA:BB:CC".
         *
         * @return Bluetooth address as string
         */
        @RequiresNoPermission
        public @Nullable String getAddress() {
            return mAddress;
        }

        /**
         * Returns the address type of this {@link BluetoothAddress}, one of {@link
         * #ADDRESS_TYPE_PUBLIC}, {@link #ADDRESS_TYPE_RANDOM}, or {@link #ADDRESS_TYPE_UNKNOWN}.
         *
         * @return Bluetooth address type
         */
        @AddressType
        @RequiresNoPermission
        public int getAddressType() {
            return mAddressType;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            BluetoothUtils.writeStringToParcel(out, mAddress);
            out.writeInt(mAddressType);
        }

        private BluetoothAddress(@NonNull Parcel in) {
            this(in.readString(), in.readInt());
        }

        /** {@link Parcelable.Creator} interface implementation. */
        public static final @NonNull Parcelable.Creator<BluetoothAddress> CREATOR =
                new Parcelable.Creator<BluetoothAddress>() {
                    public @NonNull BluetoothAddress createFromParcel(Parcel in) {
                        return new BluetoothAddress(in);
                    }

                    public @NonNull BluetoothAddress[] newArray(int size) {
                        return new BluetoothAddress[size];
                    }
                };
    }
}

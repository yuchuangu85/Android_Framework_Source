/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.bluetooth.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Optional;

/**
 * GATT offload session information. This class will be returned by {@link
 * BluetoothGatt#offloadCharacteristics() or @link BluetoothGattServer#offloadCharacteristics()}.
 */
@Hide
@FlaggedApi(Flags.FLAG_GATT_OFFLOAD_API)
@SystemApi
public final class GattOffloadSession implements AutoCloseable {

    private static final String TAG = GattOffloadSession.class.getSimpleName();

    /**
     * Constant representing an invalid offload session ID. This value indicates that a session ID
     * has not been assigned or is not valid.
     */
    public static final int OFFLOAD_SESSION_ID_UNKNOWN = 0;

    /**
     * Annotation to define the status of a GATT offload session.
     *
     * <p>This specifies the possible outcomes when attempting to offload or unoffload GATT
     * characteristics.
     */
    @Hide
    @IntDef(
            prefix = {"STATUS_"},
            value = {
                STATUS_SUCCESS,
                STATUS_INVALID_CONNECTION,
                STATUS_INSUFFICIENT_SECURITY,
                STATUS_DATABASE_OUT_OF_SYNC,
                STATUS_HAL_ERROR,
                STATUS_SERVICE_UNAVAILABLE,
                STATUS_CHARACTERISTIC_BUSY,
                STATUS_REQUEST_TIMEOUT,
                STATUS_ILLEGAL_PARAMETER,
                STATUS_DUPLICATE_SESSION,
                STATUS_FAILURE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {}

    /** Indicates that the operation was successful. */
    public static final int STATUS_SUCCESS = 0x00;

    /** Indicates that the BluetoothGatt connection is no longer valid. */
    public static final int STATUS_INVALID_CONNECTION = 0x01;

    /** Indicates that the current security state is insufficient for the operation. */
    public static final int STATUS_INSUFFICIENT_SECURITY = 0x05;

    /** Indicates that the local GATT database is out of sync with the remote device's database. */
    public static final int STATUS_DATABASE_OUT_OF_SYNC = 0x12;

    /** Indicates an error occurred in the Hardware Abstraction Layer. */
    public static final int STATUS_HAL_ERROR = 0x81;

    /** Indicates that the Bluetooth service is unavailable. */
    public static final int STATUS_SERVICE_UNAVAILABLE = 0x82;

    /** Indicates that the characteristic is currently in use. */
    public static final int STATUS_CHARACTERISTIC_BUSY = 0x84;

    /** Indicates that the operation timed out before a response was received. */
    public static final int STATUS_REQUEST_TIMEOUT = 0x85;

    /** Indicates that one or more parameters provided for the operation were illegal or invalid. */
    public static final int STATUS_ILLEGAL_PARAMETER = 0x87;

    /** Indicates that a session using same resource already exists. */
    public static final int STATUS_DUPLICATE_SESSION = 0x90;

    /** Indicates a generic failure not covered by other more specific status codes. */
    public static final int STATUS_FAILURE = 0x101;

    private final int mSessionId;
    private final BluetoothDevice mDevice;
    private final Optional<BluetoothGatt> mBluetoothGatt;
    private final Optional<BluetoothGattServer> mBluetoothGattServer;
    private final BluetoothGattService mGattService;
    private final List<BluetoothGattCharacteristic> mGattCharacteristics;
    private final long mEndpointId;
    private final long mHubId;

    @Hide
    public GattOffloadSession(
            int sessionId,
            @NonNull BluetoothDevice device,
            @Nullable BluetoothGatt gattInstance,
            @Nullable BluetoothGattServer gattServerInstance,
            @NonNull BluetoothGattService service,
            @NonNull List<BluetoothGattCharacteristic> characteristics,
            long endpointId,
            long hubId) {
        if (sessionId == OFFLOAD_SESSION_ID_UNKNOWN) {
            throw new IllegalArgumentException("Invalid session ID");
        }
        requireNonNull(device);
        requireNonNull(service);
        requireNonNull(characteristics);
        if (gattInstance == null && gattServerInstance == null) {
            throw new IllegalArgumentException("Both client and server are null");
        }
        if (gattInstance != null && gattServerInstance != null) {
            throw new IllegalArgumentException("Both client and server are configured");
        }
        mSessionId = sessionId;
        mDevice = device;
        mBluetoothGatt = Optional.ofNullable(gattInstance);
        mBluetoothGattServer = Optional.ofNullable(gattServerInstance);
        mGattService = service;
        mGattCharacteristics = List.copyOf(characteristics);
        mEndpointId = endpointId;
        mHubId = hubId;
    }

    /**
     * Returns the offload session ID assigned from host stack.
     *
     * @return The offloaded session ID
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Returns the GATT service associated with this offload session.
     *
     * @return The {@link BluetoothGattService} instance for this session
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @NonNull BluetoothGattService getGattService() {
        return mGattService;
    }

    /**
     * Returns the list of GATT characteristics included in this offload session.
     *
     * @return A list of the {@link BluetoothGattCharacteristic}s in this session.
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @NonNull List<BluetoothGattCharacteristic> getGattCharacteristics() {
        return mGattCharacteristics;
    }

    /**
     * Returns the ID of the endpoint within the hub associated with this offload session.
     *
     * @return The ID of the endpoint
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public long getEndpointId() {
        return mEndpointId;
    }

    /**
     * Returns the ID of the hub associated with this offload session.
     *
     * @return The ID of the hub
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public long getHubId() {
        return mHubId;
    }

    /**
     * Closes this GATT offload session and unloads all associated characteristics.
     *
     * <p>This method allows the application to revoke the delegation of handling specific
     * characteristics previously offloaded via {@link BluetoothGatt#offloadCharacteristics} or
     * {@link BluetoothGattServer#offloadCharacteristics}. Upon successful execution, the handling
     * of the characteristics reverts from the endpoint back to the host stack.
     *
     * <p>This session must be an active offload session that was successfully created by a prior
     * call to @link BluetoothGatt#offloadCharacteristics} or {@link
     * BluetoothGattServer#offloadCharacteristics}.
     *
     * <p>Note that calling this method only stops the offloading of characteristics and does not
     * impact the GATT connection itself. The GATT connection remains active unless explicitly
     * disconnected.
     *
     * <p>The operation is considered complete when {@link
     * BluetoothGattCallback#onCharacteristicsUnoffloaded} or {@link
     * BluetoothGattServerCallback#onCharacteristicsUnoffloaded} is invoked.
     *
     * @throws SecurityException if the caller does not have the necessary permissions.
     * @throws IllegalArgumentException if session is not valid
     */
    @Hide
    @Override
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void close() {
        if (getSessionId() == OFFLOAD_SESSION_ID_UNKNOWN) {
            Log.w(TAG, "Invalid session to close");
            return;
        }
        mBluetoothGatt.ifPresent(gatt -> gatt.unoffloadCharacteristics(this));
        mBluetoothGattServer.ifPresent(
                gattServer -> gattServer.unoffloadCharacteristics(mDevice, this));
    }

    @Override
    public String toString() {
        return "GattOffloadSession{sessionId= "
                + getSessionId()
                + ", device= "
                + mDevice
                + ", gattService= "
                + mGattService
                + ", gattCharacteristics= "
                + mGattCharacteristics
                + ", endpointId= "
                + mEndpointId
                + ", hubId= "
                + mHubId
                + ", identityHashCode= "
                + Integer.toHexString(System.identityHashCode(this))
                + "}";
    }

    @Hide
    public static final class InnerParcel implements Parcelable {
        private final int mSessionId;
        private final @Status int mStatus;

        public InnerParcel(int sessionId, @Status int status) {
            mSessionId = sessionId;
            mStatus = status;
        }

        @RequiresNoPermission
        /* package */ int getSessionId() {
            return mSessionId;
        }

        @RequiresNoPermission
        /* package */ int getStatus() {
            return mStatus;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(mSessionId);
            out.writeInt(mStatus);
        }

        public static final @NonNull Parcelable.Creator<InnerParcel> CREATOR =
                new Creator<InnerParcel>() {
                    @Override
                    public InnerParcel createFromParcel(Parcel in) {
                        return new InnerParcel(in.readInt(), in.readInt());
                    }

                    @Override
                    public InnerParcel[] newArray(int size) {
                        return new InnerParcel[size];
                    }
                };
    }
}

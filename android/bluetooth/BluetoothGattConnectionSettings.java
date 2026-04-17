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

import static android.bluetooth.BluetoothDevice.Transport;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;

import com.android.bluetooth.flags.Flags;

/**
 * Defines parameters for creating BluetoothGatt connection.
 *
 * <p>Used with {@link BluetoothDevice#connectGatt} to create a Gatt client connection.
 *
 * <p>{@link BluetoothDevice#connectGatt} ensures It applies the Gatt settings passed as part of
 * {@link BluetoothGattConnectionSettings}
 *
 * @see BluetoothDevice#connectGatt
 */
@FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
public final class BluetoothGattConnectionSettings {
    /**
     * Setting this to true, would enable the automatic connection to remote device when It is
     * available, false would trigger direction LE connection to remote device
     */
    private final boolean mAutoConnectEnabled;

    /** Determine if this GATT client connection is opportunistic or not */
    private final boolean mOpportunisticEnabled;

    /** Negotiate MTU at the beginning of the connection, using Android's default MTU value */
    private final boolean mAutomaticMtuEnabled;

    /** Transport to be used for GATT connection. */
    private final @Transport int mTransport;

    /** Returns true if auto connection enabled or false otherwise. */
    @RequiresNoPermission
    public boolean isAutoConnectEnabled() {
        return mAutoConnectEnabled;
    }

    /** Returns if the GATT connection is opportunistic or not. */
    @RequiresNoPermission
    public boolean isOpportunisticEnabled() {
        return mOpportunisticEnabled;
    }

    /** Returns the transport to be used for GATT connection. */
    @RequiresNoPermission
    public @Transport int getTransport() {
        return mTransport;
    }

    /**
     * Returns true if the automatic MTU exchange is enabled for this connection or false otherwise.
     */
    @RequiresNoPermission
    public boolean isAutomaticMtuEnabled() {
        return mAutomaticMtuEnabled;
    }

    /**
     * Returns a {@link String} that describes each BluetoothGattConnectionSettings parameter
     * current value.
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("BluetoothGattConnectionSettings{");
        builder.append(", mIsAutoConnectEnabled=")
                .append(mAutoConnectEnabled)
                .append(", mIsOpportunisticEnabled=")
                .append(mOpportunisticEnabled)
                .append(", mTransport=")
                .append(mTransport)
                .append(", mAutomaticMtuEnabled=")
                .append(mAutomaticMtuEnabled)
                .append("}");
        return builder.toString();
    }

    private BluetoothGattConnectionSettings(
            boolean isAutoConnectEnabled,
            boolean isOpportunisticEnabled,
            @Transport int transport,
            boolean automaticMtuEnabled) {
        mAutoConnectEnabled = isAutoConnectEnabled;
        mOpportunisticEnabled = isOpportunisticEnabled;
        mTransport = transport;
        mAutomaticMtuEnabled = automaticMtuEnabled;
    }

    /** Builder for {@link BluetoothGattConnectionSettings}. */
    public static final class Builder {
        private boolean mAutoConnectEnabled = false;
        private boolean mOpportunisticEnabled = false;
        private @Transport int mTransport = BluetoothDevice.TRANSPORT_LE;
        private boolean mAutomaticMtuEnabled = true;

        /** Creates a new Builder for {@link BluetoothGattConnectionSettings}. */
        public Builder() {}

        /**
         * Setting this to true will enable the automatic connection to remote device when It is
         * available. Setting it to False would trigger direct connect to remote device
         *
         * @param autoConnectEnabled true if auto connection is enabled, false otherwise.
         * @return This builder.
         */
        @NonNull
        @RequiresNoPermission
        public Builder setAutoConnectEnabled(boolean autoConnectEnabled) {
            mAutoConnectEnabled = autoConnectEnabled;
            return this;
        }

        /**
         * Sets whether this GATT client is opportunistic. An opportunistic GATT client does not
         * hold a GATT connection. It automatically disconnects when no other GATT connections are
         * active for the remote device
         *
         * @param opportunisticEnabled true if this connection is opportunistic, false otherwise.
         * @return This builder.
         */
        @NonNull
        @RequiresNoPermission
        public Builder setOpportunisticEnabled(boolean opportunisticEnabled) {
            mOpportunisticEnabled = opportunisticEnabled;
            return this;
        }

        /**
         * Sets the transport for this Gatt settings. preferred transport for GATT connections to
         * remote dual-mode devices.
         *
         * @return This builder.
         */
        @NonNull
        @RequiresNoPermission
        public Builder setTransport(@Transport int transport) {
            mTransport = transport;
            return this;
        }

        /**
         * Sets if the MTU (Maximum Transmission Unit) needs to be negotiated for given connection
         * or not. This is set to true by default so that MTU exchange happens after the connection.
         * Applications have to set it to false to disable the automatic negotiation. Setting this
         * to false does not prevent MTU negotiation if a client explicitly requests it using {@link
         * BluetoothGatt#requestMtu} or if it's triggered internally by other profiles.
         *
         * @param automaticMtuEnabled true if Default MTU setting needs to be applied on this
         *     connection, false otherwise.
         * @return This builder.
         */
        @NonNull
        @RequiresNoPermission
        public Builder setAutomaticMtuEnabled(boolean automaticMtuEnabled) {
            mAutomaticMtuEnabled = automaticMtuEnabled;
            return this;
        }

        /**
         * Builds a {@link BluetoothGattConnectionSettings} object.
         *
         * @return A new {@link BluetoothGattConnectionSettings} object with the configured
         *     parameters.
         * @throws IllegalArgumentException on invalid parameters
         */
        @NonNull
        @RequiresNoPermission
        public BluetoothGattConnectionSettings build() {
            return new BluetoothGattConnectionSettings(
                    mAutoConnectEnabled, mOpportunisticEnabled, mTransport, mAutomaticMtuEnabled);
        }
    }
}

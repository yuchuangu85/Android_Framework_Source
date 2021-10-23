/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.policy.BluetoothController.Callback;

import java.util.Collection;
import java.util.List;

public interface BluetoothController extends CallbackController<Callback>, Dumpable {
    boolean isBluetoothSupported();
    boolean isBluetoothEnabled();

    int getBluetoothState();

    boolean isBluetoothConnected();
    boolean isBluetoothConnecting();
    boolean isBluetoothAudioProfileOnly();
    boolean isBluetoothAudioActive();
    String getConnectedDeviceName();
    void setBluetoothEnabled(boolean enabled);
    Collection<CachedBluetoothDevice> getDevices();
    void connect(CachedBluetoothDevice device);
    void disconnect(CachedBluetoothDevice device);
    boolean canConfigBluetooth();

    int getMaxConnectionState(CachedBluetoothDevice device);
    int getBondState(CachedBluetoothDevice device);
    List<CachedBluetoothDevice> getConnectedDevices();

    public interface Callback {
        void onBluetoothStateChange(boolean enabled);
        void onBluetoothDevicesChanged();
    }
}

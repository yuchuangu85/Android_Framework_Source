/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

public final class BluetoothBroadcastUtils {

    /**
     * The fragment tag specified to FragmentManager for container activities to manage fragments.
     */
    public static final String TAG_FRAGMENT_QR_CODE_SCANNER = "qr_code_scanner_fragment";

    /**
     * Action for launching qr code scanner activity.
     */
    public static final String ACTION_BLUETOOTH_LE_AUDIO_QR_CODE_SCANNER =
            "android.settings.BLUETOOTH_LE_AUDIO_QR_CODE_SCANNER";

    /**
     * Extra for {@link android.bluetooth.BluetoothDevice}.
     */
    public static final String EXTRA_BLUETOOTH_DEVICE_SINK = "bluetooth_device_sink";

    /**
     * Extra for checking the {@link android.bluetooth.BluetoothLeBroadcastAssistant} should perform
     * this operation for all coordinated set members throughout one session or not.
     */
    public static final String EXTRA_BLUETOOTH_SINK_IS_GROUP = "bluetooth_sink_is_group";

    /**
     * Bluetooth scheme.
     */
    public static final String SCHEME_BT_BROADCAST_METADATA = "BT:";

    // BluetoothLeBroadcastMetadata
    static final String PREFIX_BT_ADDRESS_TYPE = "T:";
    static final String PREFIX_BT_DEVICE = "D:";
    static final String PREFIX_BT_ADVERTISING_SID = "AS:";
    static final String PREFIX_BT_BROADCAST_ID = "B:";
    static final String PREFIX_BT_SYNC_INTERVAL = "SI:";
    static final String PREFIX_BT_IS_ENCRYPTED = "E:";
    static final String PREFIX_BT_BROADCAST_CODE = "C:";
    static final String PREFIX_BT_PRESENTATION_DELAY = "D:";
    static final String PREFIX_BT_SUBGROUPS = "G:";
    static final String PREFIX_BT_ANDROID_VERSION = "V:";

    // BluetoothLeBroadcastSubgroup
    static final String PREFIX_BTSG_CODEC_ID = "CID:";
    static final String PREFIX_BTSG_CODEC_CONFIG = "CC:";
    static final String PREFIX_BTSG_AUDIO_CONTENT = "AC:";
    static final String PREFIX_BTSG_CHANNEL_PREF = "CP:";
    static final String PREFIX_BTSG_BROADCAST_CHANNEL = "BC:";

    // BluetoothLeAudioCodecConfigMetadata
    static final String PREFIX_BTCC_AUDIO_LOCATION = "AL:";
    static final String PREFIX_BTCC_RAW_METADATA = "CCRM:";

    // BluetoothLeAudioContentMetadata
    static final String PREFIX_BTAC_PROGRAM_INFO = "PI:";
    static final String PREFIX_BTAC_LANGUAGE = "L:";
    static final String PREFIX_BTAC_RAW_METADATA = "ACRM:";

    // BluetoothLeBroadcastChannel
    static final String PREFIX_BTBC_CHANNEL_INDEX = "CI:";
    static final String PREFIX_BTBC_CODEC_CONFIG = "BCCM:";

    static final String DELIMITER_QR_CODE = ";";
}

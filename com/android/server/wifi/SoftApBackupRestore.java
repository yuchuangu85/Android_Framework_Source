/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.net.MacAddress;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiMigration;
import android.util.BackupUtils;
import android.util.Log;

import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.util.SettingsMigrationDataHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Class used to backup/restore data using the SettingsBackupAgent.
 * There are 2 symmetric API's exposed here:
 * 1. retrieveBackupDataFromSoftApConfiguration: Retrieve the configuration data to be backed up.
 * 2. retrieveSoftApConfigurationFromBackupData: Restore the configuration using the provided data.
 * The byte stream to be backed up is versioned to migrate the data easily across
 * revisions.
 */
public class SoftApBackupRestore {
    private static final String TAG = "SoftApBackupRestore";

    /**
     * Current backup data version.
     */
    private static final int CURRENT_SAP_BACKUP_DATA_VERSION = 7;

    private static final int ETHER_ADDR_LEN = 6; // Byte array size of MacAddress

    private final Context mContext;
    private final SettingsMigrationDataHolder mSettingsMigrationDataHolder;

    public SoftApBackupRestore(Context context,
            SettingsMigrationDataHolder settingsMigrationDataHolder) {
        mContext = context;
        mSettingsMigrationDataHolder = settingsMigrationDataHolder;
    }

    /**
     * Retrieve a byte stream representing the data that needs to be backed up from the
     * provided softap configuration.
     *
     * @param config saved soft ap config that needs to be backed up.
     * @return Raw byte stream that needs to be backed up.
     */
    public byte[] retrieveBackupDataFromSoftApConfiguration(SoftApConfiguration config) {
        if (config == null) {
            Log.e(TAG, "Invalid configuration received");
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(baos);

            out.writeInt(CURRENT_SAP_BACKUP_DATA_VERSION);
            BackupUtils.writeString(out, config.getSsid());
            out.writeInt(config.getBand());
            out.writeInt(config.getChannel());
            BackupUtils.writeString(out, config.getPassphrase());
            out.writeInt(config.getSecurityType());
            out.writeBoolean(config.isHiddenSsid());
            out.writeInt(config.getMaxNumberOfClients());
            out.writeLong(config.getShutdownTimeoutMillis());
            out.writeBoolean(config.isClientControlByUserEnabled());
            writeMacAddressList(out, config.getBlockedClientList());
            writeMacAddressList(out, config.getAllowedClientList());
            out.writeBoolean(config.isAutoShutdownEnabled());
        } catch (IOException io) {
            Log.e(TAG, "Invalid configuration received, IOException " + io);
            return new byte[0];
        }
        return baos.toByteArray();
    }

    /**
     * Parse out the configurations from the back up data.
     *
     * @param data raw byte stream representing the data.
     * @return Soft ap config retrieved from the backed up data.
     */
    public SoftApConfiguration retrieveSoftApConfigurationFromBackupData(byte[] data) {
        if (data == null || data.length == 0) {
            Log.e(TAG, "Invalid backup data received");
            return null;
        }
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int version = in.readInt();
            if (version < 1 || version > CURRENT_SAP_BACKUP_DATA_VERSION) {
                throw new BackupUtils.BadVersionException("Unknown Backup Serialization Version");
            }

            if (version == 1) return null; // Version 1 is a bad dataset.

            configBuilder.setSsid(BackupUtils.readString(in));

            int band;
            if (version < 4) {
                band = ApConfigUtil.convertWifiConfigBandToSoftApConfigBand(in.readInt());
            } else {
                band = in.readInt();
            }
            int channel = in.readInt();

            if (channel == 0) {
                configBuilder.setBand(band);
            } else {
                configBuilder.setChannel(channel, band);
            }
            String passphrase = BackupUtils.readString(in);
            int securityType = in.readInt();
            if (version < 4 && securityType == WifiConfiguration.KeyMgmt.WPA2_PSK) {
                configBuilder.setPassphrase(passphrase, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
            } else if (version >= 4 && securityType != SoftApConfiguration.SECURITY_TYPE_OPEN) {
                configBuilder.setPassphrase(passphrase, securityType);
            }
            if (version >= 3) {
                configBuilder.setHiddenSsid(in.readBoolean());
            }
            if (version >= 5) {
                configBuilder.setMaxNumberOfClients(in.readInt());
                if (version >= 7) {
                    configBuilder.setShutdownTimeoutMillis(in.readLong());
                } else {
                    configBuilder.setShutdownTimeoutMillis(Long.valueOf(in.readInt()));
                }
                configBuilder.setClientControlByUserEnabled(in.readBoolean());
                int numberOfBlockedClient = in.readInt();
                List<MacAddress> blockedList = new ArrayList<>(
                        macAddressListFromByteArray(in, numberOfBlockedClient));
                int numberOfAllowedClient = in.readInt();
                List<MacAddress> allowedList = new ArrayList<>(
                        macAddressListFromByteArray(in, numberOfAllowedClient));
                configBuilder.setBlockedClientList(blockedList);
                configBuilder.setAllowedClientList(allowedList);
            }
            if (version >= 6) {
                configBuilder.setAutoShutdownEnabled(in.readBoolean());
            } else {
                // Migrate data out of settings.
                WifiMigration.SettingsMigrationData migrationData =
                        mSettingsMigrationDataHolder.retrieveData();
                if (migrationData == null) {
                    Log.e(TAG, "No migration data present");
                } else {
                    configBuilder.setAutoShutdownEnabled(migrationData.isSoftApTimeoutEnabled());
                }
            }
        } catch (IOException io) {
            Log.e(TAG, "Invalid backup data received, IOException: " + io);
            return null;
        } catch (BackupUtils.BadVersionException badVersion) {
            Log.e(TAG, "Invalid backup data received, BadVersionException: " + badVersion);
            return null;
        } catch (IllegalArgumentException ie) {
            Log.e(TAG, "Invalid backup data received, IllegalArgumentException " + ie);
            return null;
        }
        return configBuilder.build();
    }

    private void writeMacAddressList(DataOutputStream out, List<MacAddress> macList)
            throws IOException {
        out.writeInt(macList.size());
        Iterator<MacAddress> iterator = macList.iterator();
        while (iterator.hasNext()) {
            byte[] mac = iterator.next().toByteArray();
            out.write(mac, 0, ETHER_ADDR_LEN);
        }
    }

    private List<MacAddress> macAddressListFromByteArray(DataInputStream in, int numberOfClients)
            throws IOException {
        List<MacAddress> macList = new ArrayList<>();
        for (int i = 0; i < numberOfClients; i++) {
            byte[] mac = new byte[ETHER_ADDR_LEN];
            in.read(mac, 0, ETHER_ADDR_LEN);
            macList.add(MacAddress.fromBytes(mac));
        }
        return macList;
    }
}

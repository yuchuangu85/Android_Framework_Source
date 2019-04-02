package com.android.server.wifi;

import android.content.pm.UserInfo;
import android.net.wifi.WifiConfiguration;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigurationMap {
    private final Map<Integer, WifiConfiguration> mPerID = new HashMap<>();

    private final Map<Integer, WifiConfiguration> mPerIDForCurrentUser = new HashMap<>();
    private final Map<String, WifiConfiguration> mPerFQDNForCurrentUser = new HashMap<>();

    private final UserManager mUserManager;

    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    ConfigurationMap(UserManager userManager) {
        mUserManager = userManager;
    }

    // RW methods:
    public WifiConfiguration put(WifiConfiguration config) {
        final WifiConfiguration current = mPerID.put(config.networkId, config);
        if (WifiConfigurationUtil.isVisibleToAnyProfile(config,
                mUserManager.getProfiles(mCurrentUserId))) {
            mPerIDForCurrentUser.put(config.networkId, config);
            if (config.FQDN != null && config.FQDN.length() > 0) {
                mPerFQDNForCurrentUser.put(config.FQDN, config);
            }
        }
        return current;
    }

    public WifiConfiguration remove(int netID) {
        WifiConfiguration config = mPerID.remove(netID);
        if (config == null) {
            return null;
        }

        mPerIDForCurrentUser.remove(netID);
        Iterator<Map.Entry<String, WifiConfiguration>> entries =
                mPerFQDNForCurrentUser.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().networkId == netID) {
                entries.remove();
                break;
            }
        }
        return config;
    }

    public void clear() {
        mPerID.clear();
        mPerIDForCurrentUser.clear();
        mPerFQDNForCurrentUser.clear();
    }

    /**
     * Sets the new foreground user ID.
     *
     * @param userId the id of the new foreground user
     */
    public void setNewUser(int userId) {
        mCurrentUserId = userId;
    }

    // RO methods:
    public WifiConfiguration getForAllUsers(int netid) {
        return mPerID.get(netid);
    }

    public WifiConfiguration getForCurrentUser(int netid) {
        return mPerIDForCurrentUser.get(netid);
    }

    public int sizeForAllUsers() {
        return mPerID.size();
    }

    public int sizeForCurrentUser() {
        return mPerIDForCurrentUser.size();
    }

    public WifiConfiguration getByFQDNForCurrentUser(String fqdn) {
        return mPerFQDNForCurrentUser.get(fqdn);
    }

    public WifiConfiguration getByConfigKeyForCurrentUser(String key) {
        if (key == null) {
            return null;
        }
        for (WifiConfiguration config : mPerIDForCurrentUser.values()) {
            if (config.configKey().equals(key)) {
                return config;
            }
        }
        return null;
    }

    public Collection<WifiConfiguration> getEnabledNetworksForCurrentUser() {
        List<WifiConfiguration> list = new ArrayList<>();
        for (WifiConfiguration config : mPerIDForCurrentUser.values()) {
            if (config.status != WifiConfiguration.Status.DISABLED) {
                list.add(config);
            }
        }
        return list;
    }

    public WifiConfiguration getEphemeralForCurrentUser(String ssid) {
        for (WifiConfiguration config : mPerIDForCurrentUser.values()) {
            if (ssid.equals(config.SSID) && config.ephemeral) {
                return config;
            }
        }
        return null;
    }

    public Collection<WifiConfiguration> valuesForAllUsers() {
        return mPerID.values();
    }

    public Collection<WifiConfiguration> valuesForCurrentUser() {
        return mPerIDForCurrentUser.values();
    }
}

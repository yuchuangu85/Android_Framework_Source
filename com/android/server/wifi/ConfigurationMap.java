package com.android.server.wifi;

import android.net.wifi.WifiConfiguration;
import android.util.Log;

import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ConfigurationMap {
    private final Map<Integer, WifiConfiguration> mPerID = new HashMap<>();
    private final Map<Integer, WifiConfiguration> mPerConfigKey = new HashMap<>();
    private final Map<String, Integer> mPerFQDN = new HashMap<>();

    // RW methods:
    public WifiConfiguration put(int netid, WifiConfiguration config) {
        WifiConfiguration current = mPerID.put(netid, config);
        mPerConfigKey.put(config.configKey().hashCode(), config);   // This is ridiculous...
        if (config.FQDN != null && config.FQDN.length() > 0) {
            mPerFQDN.put(config.FQDN, netid);
        }
        return current;
    }

    public void populatePasspointData(Collection<HomeSP> homeSPs, WifiNative wifiNative) {
        mPerFQDN.clear();

        for (HomeSP homeSp : homeSPs) {
            String fqdn = homeSp.getFQDN();
            Log.d(WifiConfigStore.TAG, "Looking for " + fqdn);
            for (WifiConfiguration config : mPerID.values()) {
                Log.d(WifiConfigStore.TAG, "Testing " + config.SSID);

                String id_str = Utils.unquote(wifiNative.getNetworkVariable(
                        config.networkId, WifiConfigStore.idStringVarName));
                if (id_str != null && id_str.equals(fqdn) && config.enterpriseConfig != null) {
                    Log.d(WifiConfigStore.TAG, "Matched " + id_str + " with " + config.networkId);
                    config.FQDN = fqdn;
                    config.providerFriendlyName = homeSp.getFriendlyName();

                    HashSet<Long> roamingConsortiumIds = homeSp.getRoamingConsortiums();
                    config.roamingConsortiumIds = new long[roamingConsortiumIds.size()];
                    int i = 0;
                    for (long id : roamingConsortiumIds) {
                        config.roamingConsortiumIds[i] = id;
                        i++;
                    }
                    IMSIParameter imsiParameter = homeSp.getCredential().getImsi();
                    config.enterpriseConfig.setPlmn(
                            imsiParameter != null ? imsiParameter.toString() : null);
                    config.enterpriseConfig.setRealm(homeSp.getCredential().getRealm());
                    mPerFQDN.put(fqdn, config.networkId);
                }
            }
        }

        Log.d(WifiConfigStore.TAG, "loaded " + mPerFQDN.size() + " passpoint configs");
    }

    public WifiConfiguration remove(int netID) {
        WifiConfiguration config = mPerID.remove(netID);
        if (config == null) {
            return null;
        }
        mPerConfigKey.remove(config.configKey().hashCode());

        Iterator<Map.Entry<String, Integer>> entries = mPerFQDN.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() == netID) {
                entries.remove();
                break;
            }
        }
        return config;
    }

    public void clear() {
        mPerID.clear();
        mPerConfigKey.clear();
        mPerFQDN.clear();
    }

    // RO methods:
    public WifiConfiguration get(int netid) {
        return mPerID.get(netid);
    }

    public int size() {
        return mPerID.size();
    }

    public boolean isEmpty() {
        return mPerID.size() == 0;
    }

    public WifiConfiguration getByFQDN(String fqdn) {
        Integer id = mPerFQDN.get(fqdn);
        return id != null ? mPerID.get(id) : null;
    }

    public WifiConfiguration getByConfigKey(String key) {
        if (key == null) {
            return null;
        }
        for (WifiConfiguration config : mPerID.values()) {
            if (config.configKey().equals(key)) {
                return config;
            }
        }
        return null;
    }

    public WifiConfiguration getByConfigKeyID(int id) {
        return mPerConfigKey.get(id);
    }

    public Collection<WifiConfiguration> getEnabledNetworks() {
        List<WifiConfiguration> list = new ArrayList<>();
        for (WifiConfiguration config : mPerID.values()) {
            if (config.status != WifiConfiguration.Status.DISABLED) {
                list.add(config);
            }
        }
        return list;
    }

    public WifiConfiguration getEphemeral(String ssid) {
        for (WifiConfiguration config : mPerID.values()) {
            if (ssid.equals(config.SSID) && config.ephemeral) {
                return config;
            }
        }
        return null;
    }

    public Collection<WifiConfiguration> values() {
        return mPerID.values();
    }
}

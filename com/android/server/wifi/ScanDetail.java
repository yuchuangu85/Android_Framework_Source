package com.android.server.wifi;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiSsid;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.HSFriendlyNameElement;
import com.android.server.wifi.anqp.VenueNameElement;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.PasspointMatchInfo;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ScanDetail {
    private final ScanResult mScanResult;
    private volatile NetworkDetail mNetworkDetail;
    private final Map<HomeSP, PasspointMatch> mMatches;
    private long mSeen = 0;

    public ScanDetail(NetworkDetail networkDetail, WifiSsid wifiSsid, String BSSID,
                      String caps, int level, int frequency, long tsf) {
        mNetworkDetail = networkDetail;
        mScanResult = new ScanResult(wifiSsid, BSSID, caps, level, frequency, tsf );
        mSeen = System.currentTimeMillis();
        //mScanResult.seen = mSeen;
        mScanResult.channelWidth = networkDetail.getChannelWidth();
        mScanResult.centerFreq0 = networkDetail.getCenterfreq0();
        mScanResult.centerFreq1 = networkDetail.getCenterfreq1();
        if (networkDetail.is80211McResponderSupport())
            mScanResult.setFlag(ScanResult.FLAG_80211mc_RESPONDER);
        mMatches = null;
    }

    public ScanDetail(WifiSsid wifiSsid, String BSSID, String caps, int level, int frequency,
                      long tsf, long seen) {
        mNetworkDetail = null;
        mScanResult = new ScanResult(wifiSsid, BSSID, caps, level, frequency, tsf );
        mSeen = seen;
        //mScanResult.seen = mSeen;
        mScanResult.channelWidth = 0;
        mScanResult.centerFreq0 = 0;
        mScanResult.centerFreq1 = 0;
        mScanResult.flags = 0;
        mMatches = null;
    }

    private ScanDetail(ScanResult scanResult, NetworkDetail networkDetail,
                       Map<HomeSP, PasspointMatch> matches) {
        mScanResult = scanResult;
        mNetworkDetail = networkDetail;
        mMatches = matches;
    }

    public void updateResults(NetworkDetail networkDetail, int level, WifiSsid wssid, String ssid,
                              String flags, int freq, long tsf) {
        mScanResult.level = level;
        mScanResult.wifiSsid = wssid;
        // Keep existing API
        mScanResult.SSID = ssid;
        mScanResult.capabilities = flags;
        mScanResult.frequency = freq;
        mScanResult.timestamp = tsf;
        mSeen = System.currentTimeMillis();
        //mScanResult.seen = mSeen;
        mScanResult.channelWidth = networkDetail.getChannelWidth();
        mScanResult.centerFreq0 = networkDetail.getCenterfreq0();
        mScanResult.centerFreq1 = networkDetail.getCenterfreq1();
        if (networkDetail.is80211McResponderSupport())
            mScanResult.setFlag(ScanResult.FLAG_80211mc_RESPONDER);
        if (networkDetail.isInterworking())
            mScanResult.setFlag(ScanResult.FLAG_PASSPOINT_NETWORK);
    }

    public void propagateANQPInfo(Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
        if (anqpElements.isEmpty()) {
            return;
        }
        mNetworkDetail = mNetworkDetail.complete(anqpElements);
        HSFriendlyNameElement fne = (HSFriendlyNameElement)anqpElements.get(
                Constants.ANQPElementType.HSFriendlyName);
        // !!! Match with language
        if (fne != null && !fne.getNames().isEmpty()) {
            mScanResult.venueName = fne.getNames().get(0).getText();
        } else {
            VenueNameElement vne =
                    (((VenueNameElement)anqpElements.get(Constants.ANQPElementType.ANQPVenueName)));
            if (vne != null && !vne.getNames().isEmpty()) {
                mScanResult.venueName = vne.getNames().get(0).getText();
            }
        }
    }

    public ScanResult getScanResult() {
        return mScanResult;
    }

    public NetworkDetail getNetworkDetail() {
        return mNetworkDetail;
    }

    public String getSSID() {
        return mNetworkDetail == null ? mScanResult.SSID : mNetworkDetail.getSSID();
    }

    public String getBSSIDString() {
        return  mNetworkDetail == null ? mScanResult.BSSID : mNetworkDetail.getBSSIDString();
    }

    public String toKeyString() {
        NetworkDetail networkDetail = mNetworkDetail;
        if (networkDetail != null) {
            return networkDetail.toKeyString();
        }
        else {
            return String.format("'%s':%012x", mScanResult.BSSID, Utils.parseMac(mScanResult.BSSID));
        }
    }

    public long getSeen() {
        return mSeen;
    }

    public long setSeen() {
        mSeen = System.currentTimeMillis();
        mScanResult.seen = mSeen;
        return mSeen;
    }

    public List<PasspointMatchInfo> getMatchList() {
        if (mMatches == null || mMatches.isEmpty()) {
            return null;
        }

        List<PasspointMatchInfo> list = new ArrayList<>();
        for (Map.Entry<HomeSP, PasspointMatch> entry : mMatches.entrySet()) {
            new PasspointMatchInfo(entry.getValue(), this, entry.getKey());
        }
        return list;
    }

    @Override
    public String toString() {
        try {
            return String.format("'%s'/%012x",
                    mScanResult.SSID, Utils.parseMac(mScanResult.BSSID));
        }
        catch (IllegalArgumentException iae) {
            return String.format("'%s'/----", mScanResult.BSSID);
        }
    }
}

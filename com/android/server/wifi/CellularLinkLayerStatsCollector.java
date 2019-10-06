/*
 * Copyright 2019 The Android Open Source Project
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

import static android.telephony.TelephonyManager.NETWORK_TYPE_CDMA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0;
import static android.telephony.TelephonyManager.NETWORK_TYPE_GSM;
import static android.telephony.TelephonyManager.NETWORK_TYPE_LTE;
import static android.telephony.TelephonyManager.NETWORK_TYPE_NR;
import static android.telephony.TelephonyManager.NETWORK_TYPE_TD_SCDMA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_UMTS;
import static android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN;

import android.content.Context;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoTdscdma;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthTdscdma;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
/**
 * A class collecting the latest cellular link layer stats
 */
public class CellularLinkLayerStatsCollector {
    private static final String TAG = "CellStatsCollector";
    private static final boolean DBG = false;

    private Context mContext;
    private SubscriptionManager mSubManager = null;
    private TelephonyManager mCachedDefaultDataTelephonyManager = null;
    private int mCachedDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private CellInfo mLastPrimaryCellInfo = null;
    private int mLastDataNetworkType = NETWORK_TYPE_UNKNOWN;

    public CellularLinkLayerStatsCollector(Context context) {
        mContext = context;
    }

    /**
     * Get the latest DataNetworkType, SignalStrength, CellInfo and other information from
     * default data sim's TelephonyManager, parse the values of primary registered cell and return
     * them as an instance of CellularLinkLayerStats
     */
    public CellularLinkLayerStats update() {
        CellularLinkLayerStats cellStats = new CellularLinkLayerStats();

        retrieveDefaultDataTelephonyManager();
        if (mCachedDefaultDataTelephonyManager == null) {
            if (DBG) Log.v(TAG, cellStats.toString());
            return cellStats;
        }

        SignalStrength signalStrength = mCachedDefaultDataTelephonyManager.getSignalStrength();
        List<CellSignalStrength> cssList = null;
        if (signalStrength != null) cssList = signalStrength.getCellSignalStrengths();

        if (mCachedDefaultDataTelephonyManager.getDataNetworkType() == NETWORK_TYPE_UNKNOWN
                || cssList == null || cssList.size() == 0) {
            mLastPrimaryCellInfo = null;
            mLastDataNetworkType = NETWORK_TYPE_UNKNOWN;
            if (DBG) Log.v(TAG, cellStats.toString());
            return cellStats;
        }
        if (DBG) Log.v(TAG, "Cell Signal Strength List size = " + cssList.size());

        CellSignalStrength primaryCss = cssList.get(0);
        cellStats.setSignalStrengthDbm(primaryCss.getDbm());

        updateSignalStrengthDbAndNetworkTypeOfCellStats(primaryCss, cellStats);

        int networkType = cellStats.getDataNetworkType();
        CellInfo primaryCellInfo = getPrimaryCellInfo(mCachedDefaultDataTelephonyManager,
                networkType);
        boolean isSameRegisteredCell = getIsSameRegisteredCell(primaryCellInfo, networkType);
        cellStats.setIsSameRegisteredCell(isSameRegisteredCell);

        // Update for the next call
        mLastPrimaryCellInfo = primaryCellInfo;
        mLastDataNetworkType = networkType;

        if (DBG) Log.v(TAG, cellStats.toString());
        return cellStats;
    }

    private void retrieveDefaultDataTelephonyManager() {
        if (!initSubManager()) return;

        int defaultDataSubId = mSubManager.getDefaultDataSubscriptionId();
        if (DBG) Log.v(TAG, "default Data Sub ID = " + defaultDataSubId);
        if (defaultDataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            mCachedDefaultDataTelephonyManager = null;
            return;
        }

        if (defaultDataSubId != mCachedDefaultDataSubId
                || mCachedDefaultDataTelephonyManager == null) {
            mCachedDefaultDataSubId = defaultDataSubId;
            // TODO(b/132188983): Inject this using WifiInjector
            TelephonyManager defaultSubTelephonyManager = (TelephonyManager) mContext
                    .getSystemService(Context.TELEPHONY_SERVICE);
            if (defaultDataSubId == mSubManager.getDefaultSubscriptionId()) {
                mCachedDefaultDataTelephonyManager = defaultSubTelephonyManager;
            } else {
                mCachedDefaultDataTelephonyManager =  defaultSubTelephonyManager
                        .createForSubscriptionId(defaultDataSubId);
            }
        }
    }

    private boolean initSubManager() {
        if (mSubManager == null) {
            mSubManager = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        }
        return (mSubManager != null);
    }

    /**
      * Update dB value and network type base on CellSignalStrength subclass type.
      * It follows the same order as that in SignalStrength.getPrimary().
      * TODO: NR may move up in the future
      */
    private void updateSignalStrengthDbAndNetworkTypeOfCellStats(CellSignalStrength primaryCss,
            CellularLinkLayerStats cellStats) {
        if (primaryCss instanceof CellSignalStrengthLte) {
            CellSignalStrengthLte cssLte = (CellSignalStrengthLte) primaryCss;
            cellStats.setSignalStrengthDb(cssLte.getRsrq());
            cellStats.setDataNetworkType(NETWORK_TYPE_LTE);
        } else if (primaryCss instanceof CellSignalStrengthCdma) {
            CellSignalStrengthCdma cssCdma = (CellSignalStrengthCdma) primaryCss;
            int evdoSnr = cssCdma.getEvdoSnr();
            int cdmaEcio = cssCdma.getCdmaEcio();
            if (evdoSnr != SignalStrength.INVALID) {
                cellStats.setSignalStrengthDb(evdoSnr);
                cellStats.setDataNetworkType(NETWORK_TYPE_EVDO_0);
            } else {
                cellStats.setSignalStrengthDb(cdmaEcio);
                cellStats.setDataNetworkType(NETWORK_TYPE_CDMA);
            }
        } else if (primaryCss instanceof CellSignalStrengthTdscdma) {
            cellStats.setDataNetworkType(NETWORK_TYPE_TD_SCDMA);
        } else if (primaryCss instanceof CellSignalStrengthWcdma) {
            CellSignalStrengthWcdma cssWcdma = (CellSignalStrengthWcdma) primaryCss;
            cellStats.setSignalStrengthDb(cssWcdma.getEcNo());
            cellStats.setDataNetworkType(NETWORK_TYPE_UMTS);
        } else if (primaryCss instanceof CellSignalStrengthGsm) {
            cellStats.setDataNetworkType(NETWORK_TYPE_GSM);
        } else if (primaryCss instanceof CellSignalStrengthNr) {
            CellSignalStrengthNr cssNr = (CellSignalStrengthNr) primaryCss;
            cellStats.setSignalStrengthDb(cssNr.getCsiSinr());
            cellStats.setDataNetworkType(NETWORK_TYPE_NR);
        } else {
            Log.e(TAG, "invalid CellSignalStrength");
        }
    }

    private CellInfo getPrimaryCellInfo(TelephonyManager defaultDataTelephonyManager,
                int networkType) {
        List<CellInfo> cellInfoList = getRegisteredCellInfo(defaultDataTelephonyManager);
        int cilSize = cellInfoList.size();
        CellInfo primaryCellInfo = null;
        // CellInfo.getCellConnectionStatus() should tell if it is primary serving cell.
        // However, it currently always returns 0 (CONNECTION_NONE) for registered cells.
        // Therefore, the workaround of deriving primary serving cell is
        // to check if the registered cellInfo subclass type matches networkType
        for (int i = 0; i < cilSize; ++i) {
            CellInfo cellInfo = cellInfoList.get(i);
            if ((cellInfo instanceof CellInfoTdscdma && networkType == NETWORK_TYPE_TD_SCDMA)
                    || (cellInfo instanceof CellInfoCdma && (networkType == NETWORK_TYPE_CDMA
                    || networkType == NETWORK_TYPE_EVDO_0))
                    || (cellInfo instanceof CellInfoLte && networkType == NETWORK_TYPE_LTE)
                    || (cellInfo instanceof CellInfoWcdma && networkType == NETWORK_TYPE_UMTS)
                    || (cellInfo instanceof CellInfoGsm && networkType == NETWORK_TYPE_GSM)
                    || (cellInfo instanceof CellInfoNr && networkType == NETWORK_TYPE_NR)) {
                primaryCellInfo = cellInfo;
            }
        }
        return primaryCellInfo;
    }

    private boolean getIsSameRegisteredCell(CellInfo primaryCellInfo, int networkType) {
        boolean isSameRegisteredCell;
        if (primaryCellInfo != null && mLastPrimaryCellInfo != null) {
            isSameRegisteredCell = primaryCellInfo.getCellIdentity()
                .equals(mLastPrimaryCellInfo.getCellIdentity());
        } else if (primaryCellInfo == null && mLastPrimaryCellInfo == null) {
            // This is a workaround when it can't find primaryCellInfo for two consecutive times.
            isSameRegisteredCell = true;
        } else {
            // only one of them is null and it is a strong indication of primary cell change.
            isSameRegisteredCell = false;
        }

        if (mLastDataNetworkType == NETWORK_TYPE_UNKNOWN || mLastDataNetworkType != networkType) {
            isSameRegisteredCell = false;
        }
        return isSameRegisteredCell;
    }

    private List<CellInfo> getRegisteredCellInfo(TelephonyManager defaultDataTelephonyManager) {
        List<CellInfo> allList = defaultDataTelephonyManager.getAllCellInfo();
        List<CellInfo> cellInfoList = new ArrayList<>();
        for (CellInfo ci : allList) {
            if (ci.isRegistered()) cellInfoList.add(ci);
            if (DBG) Log.v(TAG, ci.toString());
        }
        return cellInfoList;
    }
}

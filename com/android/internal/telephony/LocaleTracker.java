/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.internal.telephony;

import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.LocalLog;

import com.android.internal.util.CollectionUtils;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The locale tracker keeps tracking the current locale of the phone.
 */
public class LocaleTracker extends Handler {
    private static final boolean DBG = true;
    private static final String TAG = LocaleTracker.class.getSimpleName();

    /** Event for getting cell info from the modem */
    private static final int EVENT_GET_CELL_INFO                = 1;

    /** Event for operator numeric update */
    private static final int EVENT_UPDATE_OPERATOR_NUMERIC      = 2;

    /** Event for service state changed */
    private static final int EVENT_SERVICE_STATE_CHANGED        = 3;

    // Todo: Read this from Settings.
    /** The minimum delay to get cell info from the modem */
    private static final long CELL_INFO_MIN_DELAY_MS = 2 * SECOND_IN_MILLIS;

    // Todo: Read this from Settings.
    /** The maximum delay to get cell info from the modem */
    private static final long CELL_INFO_MAX_DELAY_MS = 10 * MINUTE_IN_MILLIS;

    // Todo: Read this from Settings.
    /** The delay for periodically getting cell info from the modem */
    private static final long CELL_INFO_PERIODIC_POLLING_DELAY_MS = 10 * MINUTE_IN_MILLIS;

    private final Phone mPhone;

    /** SIM card state. Must be one of TelephonyManager.SIM_STATE_XXX */
    private int mSimState;

    /** Current serving PLMN's MCC/MNC */
    @Nullable
    private String mOperatorNumeric;

    /** Current cell tower information */
    @Nullable
    private List<CellInfo> mCellInfo;

    /** Count of invalid cell info we've got so far. Will reset once we get a successful one */
    private int mFailCellInfoCount;

    /** The ISO-3166 code of device's current country */
    @Nullable
    private String mCurrentCountryIso;

    /** Current service state. Must be one of ServiceState.STATE_XXX. */
    private int mLastServiceState = -1;

    private final LocalLog mLocalLog = new LocalLog(50);

    /** Broadcast receiver to get SIM card state changed event */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED.equals(intent.getAction())) {
                int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, 0);
                if (phoneId == mPhone.getPhoneId()) {
                    onSimCardStateChanged(intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE,
                            TelephonyManager.SIM_STATE_UNKNOWN));
                }
            }
        }
    };

    /**
     * Message handler
     *
     * @param msg The message
     */
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_GET_CELL_INFO:
                synchronized (this) {
                    getCellInfo();
                    updateLocale();
                }
                break;
            case EVENT_UPDATE_OPERATOR_NUMERIC:
                updateOperatorNumericSync((String) msg.obj);
                break;
            case EVENT_SERVICE_STATE_CHANGED:
                AsyncResult ar = (AsyncResult) msg.obj;
                onServiceStateChanged((ServiceState) ar.result);
                break;
            default:
                throw new IllegalStateException("Unexpected message arrives. msg = " + msg.what);
        }
    }

    /**
     * Constructor
     *
     * @param phone The phone object
     * @param looper The looper message handler
     */
    public LocaleTracker(Phone phone, Looper looper)  {
        super(looper);
        mPhone = phone;
        mSimState = TelephonyManager.SIM_STATE_UNKNOWN;

        final IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        mPhone.getContext().registerReceiver(mBroadcastReceiver, filter);

        mPhone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
    }

    /**
     * Get the device's current country.
     *
     * @return The device's current country. Empty string if the information is not available.
     */
    @NonNull
    public synchronized String getCurrentCountry() {
        return (mCurrentCountryIso != null) ? mCurrentCountryIso : "";
    }

    /**
     * Get the MCC from cell tower information.
     *
     * @return MCC in string format. Null if the information is not available.
     */
    @Nullable
    private String getMccFromCellInfo() {
        String selectedMcc = null;
        if (mCellInfo != null) {
            Map<String, Integer> countryCodeMap = new HashMap<>();
            int maxCount = 0;
            for (CellInfo cellInfo : mCellInfo) {
                String mcc = null;
                if (cellInfo instanceof CellInfoGsm) {
                    mcc = ((CellInfoGsm) cellInfo).getCellIdentity().getMccString();
                } else if (cellInfo instanceof CellInfoLte) {
                    mcc = ((CellInfoLte) cellInfo).getCellIdentity().getMccString();
                } else if (cellInfo instanceof CellInfoWcdma) {
                    mcc = ((CellInfoWcdma) cellInfo).getCellIdentity().getMccString();
                }
                if (mcc != null) {
                    int count = 1;
                    if (countryCodeMap.containsKey(mcc)) {
                        count = countryCodeMap.get(mcc) + 1;
                    }
                    countryCodeMap.put(mcc, count);
                    // This is unlikely, but if MCC from cell info looks different, we choose the
                    // MCC that occurs most.
                    if (count > maxCount) {
                        maxCount = count;
                        selectedMcc = mcc;
                    }
                }
            }
        }
        return selectedMcc;
    }

    /**
     * Called when SIM card state changed. Only when we absolutely know the SIM is absent, we get
     * cell info from the network. Other SIM states like NOT_READY might be just a transitioning
     * state.
     *
     * @param state SIM card state. Must be one of TelephonyManager.SIM_STATE_XXX.
     */
    private synchronized void onSimCardStateChanged(int state) {
        if (mSimState != state && state == TelephonyManager.SIM_STATE_ABSENT) {
            if (DBG) log("Sim absent. Get latest cell info from the modem.");
            getCellInfo();
            updateLocale();
        }
        mSimState = state;
    }

    /**
     * Called when service state changed.
     *
     * @param serviceState Service state
     */
    private void onServiceStateChanged(ServiceState serviceState) {
        int state = serviceState.getState();
        if (state != mLastServiceState) {
            if (state != ServiceState.STATE_POWER_OFF && TextUtils.isEmpty(mOperatorNumeric)) {
                // When the device is out of airplane mode or powered on, and network's MCC/MNC is
                // not available, we get cell info from the modem.
                String msg = "Service state " + ServiceState.rilServiceStateToString(state)
                        + ". Get cell info now.";
                if (DBG) log(msg);
                mLocalLog.log(msg);
                getCellInfo();
            } else if (state == ServiceState.STATE_POWER_OFF) {
                // Clear the cell info when the device is in airplane mode.
                if (mCellInfo != null) mCellInfo.clear();
                stopCellInfoRetry();
            }
            updateLocale();
            mLastServiceState = state;
        }
    }

    /**
     * Update MCC/MNC from network service state synchronously. Note if this is called from phone
     * process's main thread and if the update operation requires getting cell info from the modem,
     * the cached cell info will be used to determine the locale. If the cached cell info is not
     * acceptable, use {@link #updateOperatorNumericAsync(String)} instead.
     *
     * @param operatorNumeric MCC/MNC of the operator
     */
    public synchronized void updateOperatorNumericSync(String operatorNumeric) {
        // Check if the operator numeric changes.
        if (DBG) log("updateOperatorNumericSync. mcc/mnc=" + operatorNumeric);
        if (!Objects.equals(mOperatorNumeric, operatorNumeric)) {
            String msg = "Operator numeric changes to " + operatorNumeric;
            if (DBG) log(msg);
            mLocalLog.log(msg);
            mOperatorNumeric = operatorNumeric;

            // If the operator numeric becomes unavailable, we need to get the latest cell info so
            // that we can get MCC from it.
            if (TextUtils.isEmpty(mOperatorNumeric)) {
                if (DBG) {
                    log("Operator numeric unavailable. Get latest cell info from the modem.");
                }
                getCellInfo();
            } else {
                // If operator numeric is available, that means we camp on network. So we should
                // clear the cell info and stop cell info retry.
                if (mCellInfo != null) mCellInfo.clear();
                stopCellInfoRetry();
            }
            updateLocale();
        }
    }

    /**
     * Update MCC/MNC from network service state asynchronously. The update operation will run
     * in locale tracker's handler's thread, which can get cell info synchronously from service
     * state tracker. Note that the country code will not be available immediately after calling
     * this method.
     *
     * @param operatorNumeric MCC/MNC of the operator
     */
    public void updateOperatorNumericAsync(String operatorNumeric) {
        if (DBG) log("updateOperatorNumericAsync. mcc/mnc=" + operatorNumeric);
        sendMessage(obtainMessage(EVENT_UPDATE_OPERATOR_NUMERIC, operatorNumeric));
    }

    /**
     * Get the delay time to get cell info from modem. The delay time grows exponentially to prevent
     * battery draining.
     *
     * @param failCount Count of invalid cell info we've got so far.
     * @return The delay time for next get cell info
     */
    private long getCellInfoDelayTime(int failCount) {
        // Exponentially grow the delay time
        long delay = CELL_INFO_MIN_DELAY_MS * (long) Math.pow(2, failCount - 1);
        if (delay < CELL_INFO_MIN_DELAY_MS) {
            delay = CELL_INFO_MIN_DELAY_MS;
        } else if (delay > CELL_INFO_MAX_DELAY_MS) {
            delay = CELL_INFO_MAX_DELAY_MS;
        }
        return delay;
    }

    /**
     * Stop retrying getting cell info from the modem. It cancels any scheduled cell info retrieving
     * request.
     */
    private void stopCellInfoRetry() {
        mFailCellInfoCount = 0;
        removeMessages(EVENT_GET_CELL_INFO);
    }

    /**
     * Get cell info from the modem.
     */
    private void getCellInfo() {
        String msg;
        if (!mPhone.getServiceStateTracker().getDesiredPowerState()) {
            msg = "Radio is off. Stopped cell info retry. Cleared the previous cached cell info.";
            if (mCellInfo != null) mCellInfo.clear();
            if (DBG) log(msg);
            mLocalLog.log(msg);
            stopCellInfoRetry();
            return;
        }

        // Get all cell info. Passing null to use default worksource, which indicates the original
        // request is from telephony internally.
        mCellInfo = mPhone.getAllCellInfo(null);
        msg = "getCellInfo: cell info=" + mCellInfo;
        if (DBG) log(msg);
        mLocalLog.log(msg);
        if (CollectionUtils.isEmpty(mCellInfo)) {
            // If we can't get a valid cell info. Try it again later.
            long delay = getCellInfoDelayTime(++mFailCellInfoCount);
            if (DBG) log("Can't get cell info. Try again in " + delay / 1000 + " secs.");
            removeMessages(EVENT_GET_CELL_INFO);
            sendMessageDelayed(obtainMessage(EVENT_GET_CELL_INFO), delay);
        } else {
            // We successfully got cell info from the modem. We should stop cell info retry.
            stopCellInfoRetry();

            // Now we need to get the cell info from the modem periodically even if we already got
            // the cell info because the user can move.
            sendMessageDelayed(obtainMessage(EVENT_GET_CELL_INFO),
                    CELL_INFO_PERIODIC_POLLING_DELAY_MS);
        }
    }

    /**
     * Update the device's current locale
     */
    private void updateLocale() {
        // If MCC is available from network service state, use it first.
        String mcc = null;
        String countryIso = "";
        if (!TextUtils.isEmpty(mOperatorNumeric)) {
            try {
                mcc = mOperatorNumeric.substring(0, 3);
                countryIso = MccTable.countryCodeForMcc(Integer.parseInt(mcc));
            } catch (StringIndexOutOfBoundsException | NumberFormatException ex) {
                loge("updateLocale: Can't get country from operator numeric. mcc = "
                        + mcc + ". ex=" + ex);
            }
        }

        // If for any reason we can't get country from operator numeric, try to get it from cell
        // info.
        if (TextUtils.isEmpty(countryIso)) {
            mcc = getMccFromCellInfo();
            if (!TextUtils.isEmpty(mcc)) {
                try {
                    countryIso = MccTable.countryCodeForMcc(Integer.parseInt(mcc));
                } catch (NumberFormatException ex) {
                    loge("updateLocale: Can't get country from cell info. mcc = "
                            + mcc + ". ex=" + ex);
                }
            }
        }

        String msg = "updateLocale: mcc = " + mcc + ", country = " + countryIso;
        log(msg);
        mLocalLog.log(msg);
        if (!Objects.equals(countryIso, mCurrentCountryIso)) {
            msg = "updateLocale: Change the current country to " + countryIso;
            log(msg);
            mLocalLog.log(msg);
            mCurrentCountryIso = countryIso;

            TelephonyManager.setTelephonyProperty(mPhone.getPhoneId(),
                    TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, mCurrentCountryIso);

            // Set the country code for wifi. This sets allowed wifi channels based on the
            // country of the carrier we see. If we can't see any, reset to 0 so we don't
            // broadcast on forbidden channels.
            ((WifiManager) mPhone.getContext().getSystemService(Context.WIFI_SERVICE))
                    .setCountryCode(countryIso);
        }
    }

    private void log(String msg) {
        Rlog.d(TAG, msg);
    }

    private void loge(String msg) {
        Rlog.e(TAG, msg);
    }

    /**
     * Print the DeviceStateMonitor into the given stream.
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param pw A PrintWriter to which the dump is to be set.
     * @param args Additional arguments to the dump request.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        pw.println("LocaleTracker:");
        ipw.increaseIndent();
        ipw.println("mOperatorNumeric = " + mOperatorNumeric);
        ipw.println("mSimState = " + mSimState);
        ipw.println("mCellInfo = " + mCellInfo);
        ipw.println("mCurrentCountryIso = " + mCurrentCountryIso);
        ipw.println("mFailCellInfoCount = " + mFailCellInfoCount);
        ipw.println("Local logs:");
        ipw.increaseIndent();
        mLocalLog.dump(fd, ipw, args);
        ipw.decreaseIndent();
        ipw.decreaseIndent();
        ipw.flush();
    }
}

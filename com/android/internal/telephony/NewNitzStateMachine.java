/*
 * Copyright 2017 The Android Open Source Project
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

import android.content.Context;
import android.os.PowerManager;
import android.telephony.Rlog;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.TimestampedValue;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.TimeZoneLookupHelper.CountryResult;
import com.android.internal.telephony.TimeZoneLookupHelper.OffsetResult;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * {@hide}
 */
public final class NewNitzStateMachine implements NitzStateMachine {

    private static final String LOG_TAG = ServiceStateTracker.LOG_TAG;
    private static final boolean DBG = ServiceStateTracker.DBG;

    // Time detection state.

    /**
     * The last NITZ-sourced time considered sent to the time detector service. Used to rate-limit
     * calls to the time detector.
     */
    private TimestampedValue<Long> mSavedNitzTime;

    // Time Zone detection state.

    /** We always keep the last NITZ signal received in mLatestNitzSignal. */
    private TimestampedValue<NitzData> mLatestNitzSignal;

    /**
     * Records whether the device should have a country code available via
     * {@link DeviceState#getNetworkCountryIsoForPhone()}. Before this an NITZ signal
     * received is (almost always) not enough to determine time zone. On test networks the country
     * code should be available but can still be an empty string but this flag indicates that the
     * information available is unlikely to improve.
     */
    private boolean mGotCountryCode = false;

    /**
     * The last time zone ID that has been determined. It may not have been set as the device time
     * zone if automatic time zone detection is disabled but may later be used to set the time zone
     * if the user enables automatic time zone detection.
     */
    private String mSavedTimeZoneId;

    /**
     * Boolean is {@code true} if NITZ has been used to determine a time zone (which may not
     * ultimately have been used due to user settings). Cleared by {@link #handleNetworkAvailable()}
     * and {@link #handleNetworkCountryCodeUnavailable()}. The flag can be used when historic NITZ
     * data may no longer be valid. {@code false} indicates it is reasonable to try to set the time
     * zone using less reliable algorithms than NITZ-based detection such as by just using network
     * country code.
     */
    private boolean mNitzTimeZoneDetectionSuccessful = false;

    // Miscellaneous dependencies and helpers not related to detection state.
    private final LocalLog mTimeLog = new LocalLog(15);
    private final LocalLog mTimeZoneLog = new LocalLog(15);
    private final GsmCdmaPhone mPhone;
    private final DeviceState mDeviceState;
    private final NewTimeServiceHelper mTimeServiceHelper;
    private final TimeZoneLookupHelper mTimeZoneLookupHelper;
    /** Wake lock used while setting time of day. */
    private final PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "NitzStateMachine";

    public NewNitzStateMachine(GsmCdmaPhone phone) {
        this(phone,
                new NewTimeServiceHelper(phone.getContext()),
                new DeviceState(phone),
                new TimeZoneLookupHelper());
    }

    @VisibleForTesting
    public NewNitzStateMachine(GsmCdmaPhone phone, NewTimeServiceHelper timeServiceHelper,
            DeviceState deviceState, TimeZoneLookupHelper timeZoneLookupHelper) {
        mPhone = phone;

        Context context = phone.getContext();
        PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        mDeviceState = deviceState;
        mTimeZoneLookupHelper = timeZoneLookupHelper;
        mTimeServiceHelper = timeServiceHelper;
        mTimeServiceHelper.setListener(new NewTimeServiceHelper.Listener() {
            @Override
            public void onTimeZoneDetectionChange(boolean enabled) {
                if (enabled) {
                    handleAutoTimeZoneEnabled();
                }
            }
        });
    }

    @Override
    public void handleNetworkCountryCodeSet(boolean countryChanged) {
        boolean hadCountryCode = mGotCountryCode;
        mGotCountryCode = true;

        String isoCountryCode = mDeviceState.getNetworkCountryIsoForPhone();
        if (!TextUtils.isEmpty(isoCountryCode) && !mNitzTimeZoneDetectionSuccessful) {
            updateTimeZoneFromNetworkCountryCode(isoCountryCode);
        }

        if (mLatestNitzSignal != null && (countryChanged || !hadCountryCode)) {
            updateTimeZoneFromCountryAndNitz();
        }
    }

    private void updateTimeZoneFromCountryAndNitz() {
        String isoCountryCode = mDeviceState.getNetworkCountryIsoForPhone();
        TimestampedValue<NitzData> nitzSignal = mLatestNitzSignal;

        // TimeZone.getDefault() returns a default zone (GMT) even when time zone have never
        // been set which makes it difficult to tell if it's what the user / time zone detection
        // has chosen. isTimeZoneSettingInitialized() tells us whether the time zone of the
        // device has ever been explicit set by the user or code.
        final boolean isTimeZoneSettingInitialized =
                mTimeServiceHelper.isTimeZoneSettingInitialized();

        if (DBG) {
            Rlog.d(LOG_TAG, "updateTimeZoneFromCountryAndNitz:"
                    + " isTimeZoneSettingInitialized=" + isTimeZoneSettingInitialized
                    + " nitzSignal=" + nitzSignal
                    + " isoCountryCode=" + isoCountryCode);
        }

        try {
            NitzData nitzData = nitzSignal.getValue();

            String zoneId;
            if (nitzData.getEmulatorHostTimeZone() != null) {
                zoneId = nitzData.getEmulatorHostTimeZone().getID();
            } else if (!mGotCountryCode) {
                // We don't have a country code so we won't try to look up the time zone.
                zoneId = null;
            } else if (TextUtils.isEmpty(isoCountryCode)) {
                // We have a country code but it's empty. This is most likely because we're on a
                // test network that's using a bogus MCC (eg, "001"). Obtain a TimeZone based only
                // on the NITZ parameters: it's only going to be correct in a few cases but it
                // should at least have the correct offset.
                OffsetResult lookupResult = mTimeZoneLookupHelper.lookupByNitz(nitzData);
                String logMsg = "updateTimeZoneFromCountryAndNitz: lookupByNitz returned"
                        + " lookupResult=" + lookupResult;
                if (DBG) {
                    Rlog.d(LOG_TAG, logMsg);
                }
                // We log this in the time zone log because it has been a source of bugs.
                mTimeZoneLog.log(logMsg);

                zoneId = lookupResult != null ? lookupResult.zoneId : null;
            } else if (mLatestNitzSignal == null) {
                if (DBG) {
                    Rlog.d(LOG_TAG,
                            "updateTimeZoneFromCountryAndNitz: No cached NITZ data available,"
                                    + " not setting zone");
                }
                zoneId = null;
            } else if (isNitzSignalOffsetInfoBogus(nitzSignal, isoCountryCode)) {
                String logMsg = "updateTimeZoneFromCountryAndNitz: Received NITZ looks bogus, "
                        + " isoCountryCode=" + isoCountryCode
                        + " nitzSignal=" + nitzSignal;
                if (DBG) {
                    Rlog.d(LOG_TAG, logMsg);
                }
                // We log this in the time zone log because it has been a source of bugs.
                mTimeZoneLog.log(logMsg);

                zoneId = null;
            } else {
                OffsetResult lookupResult = mTimeZoneLookupHelper.lookupByNitzCountry(
                        nitzData, isoCountryCode);
                if (DBG) {
                    Rlog.d(LOG_TAG, "updateTimeZoneFromCountryAndNitz: using"
                            + " lookupByNitzCountry(nitzData, isoCountryCode),"
                            + " nitzData=" + nitzData
                            + " isoCountryCode=" + isoCountryCode
                            + " lookupResult=" + lookupResult);
                }
                zoneId = lookupResult != null ? lookupResult.zoneId : null;
            }

            // Log the action taken to the dedicated time zone log.
            final String tmpLog = "updateTimeZoneFromCountryAndNitz:"
                    + " isTimeZoneSettingInitialized=" + isTimeZoneSettingInitialized
                    + " isoCountryCode=" + isoCountryCode
                    + " nitzSignal=" + nitzSignal
                    + " zoneId=" + zoneId
                    + " isTimeZoneDetectionEnabled()="
                    + mTimeServiceHelper.isTimeZoneDetectionEnabled();
            mTimeZoneLog.log(tmpLog);

            // Set state as needed.
            if (zoneId != null) {
                if (DBG) {
                    Rlog.d(LOG_TAG, "updateTimeZoneFromCountryAndNitz: zoneId=" + zoneId);
                }
                if (mTimeServiceHelper.isTimeZoneDetectionEnabled()) {
                    setAndBroadcastNetworkSetTimeZone(zoneId);
                } else {
                    if (DBG) {
                        Rlog.d(LOG_TAG, "updateTimeZoneFromCountryAndNitz: skip changing zone"
                                + " as isTimeZoneDetectionEnabled() is false");
                    }
                }
                mSavedTimeZoneId = zoneId;
                mNitzTimeZoneDetectionSuccessful = true;
            } else {
                if (DBG) {
                    Rlog.d(LOG_TAG,
                            "updateTimeZoneFromCountryAndNitz: zoneId == null, do nothing");
                }
            }
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "updateTimeZoneFromCountryAndNitz: Processing NITZ data"
                    + " nitzSignal=" + nitzSignal
                    + " isoCountryCode=" + isoCountryCode
                    + " isTimeZoneSettingInitialized=" + isTimeZoneSettingInitialized
                    + " ex=" + ex);
        }
    }

    /**
     * Returns true if the NITZ signal is definitely bogus, assuming that the country is correct.
     */
    private boolean isNitzSignalOffsetInfoBogus(
            TimestampedValue<NitzData> nitzSignal, String isoCountryCode) {

        if (TextUtils.isEmpty(isoCountryCode)) {
            // We cannot say for sure.
            return false;
        }

        NitzData newNitzData = nitzSignal.getValue();
        boolean zeroOffsetNitz = newNitzData.getLocalOffsetMillis() == 0 && !newNitzData.isDst();
        return zeroOffsetNitz && !countryUsesUtc(isoCountryCode, nitzSignal);
    }

    private boolean countryUsesUtc(
            String isoCountryCode, TimestampedValue<NitzData> nitzSignal) {
        return mTimeZoneLookupHelper.countryUsesUtc(
                isoCountryCode,
                nitzSignal.getValue().getCurrentTimeInMillis());
    }

    @Override
    public void handleNetworkAvailable() {
        if (DBG) {
            Rlog.d(LOG_TAG, "handleNetworkAvailable: mNitzTimeZoneDetectionSuccessful="
                    + mNitzTimeZoneDetectionSuccessful
                    + ", Setting mNitzTimeZoneDetectionSuccessful=false");
        }
        mNitzTimeZoneDetectionSuccessful = false;
    }

    @Override
    public void handleNetworkCountryCodeUnavailable() {
        if (DBG) {
            Rlog.d(LOG_TAG, "handleNetworkCountryCodeUnavailable");
        }

        mGotCountryCode = false;
        mNitzTimeZoneDetectionSuccessful = false;
    }

    @Override
    public void handleNitzReceived(TimestampedValue<NitzData> nitzSignal) {
        // Always store the latest NITZ signal received.
        mLatestNitzSignal = nitzSignal;

        updateTimeZoneFromCountryAndNitz();
        updateTimeFromNitz();
    }

    private void updateTimeFromNitz() {
        TimestampedValue<NitzData> nitzSignal = mLatestNitzSignal;
        try {
            boolean ignoreNitz = mDeviceState.getIgnoreNitz();
            if (ignoreNitz) {
                if (DBG) {
                    Rlog.d(LOG_TAG, "updateTimeFromNitz: Not suggesting system clock because"
                            + " gsm.ignore-nitz is set");
                }
                return;
            }

            // Validate the nitzTimeSignal to reject obviously bogus elapsedRealtime values.
            try {
                // Acquire the wake lock as we are reading the elapsed realtime clock below.
                mWakeLock.acquire();

                long elapsedRealtime = mTimeServiceHelper.elapsedRealtime();
                long millisSinceNitzReceived =
                        elapsedRealtime - nitzSignal.getReferenceTimeMillis();
                if (millisSinceNitzReceived < 0 || millisSinceNitzReceived > Integer.MAX_VALUE) {
                    if (DBG) {
                        Rlog.d(LOG_TAG, "updateTimeFromNitz: not setting time, unexpected"
                                + " elapsedRealtime=" + elapsedRealtime
                                + " nitzSignal=" + nitzSignal);
                    }
                    return;
                }
            } finally {
                mWakeLock.release();
            }

            TimestampedValue<Long> newNitzTime = new TimestampedValue<>(
                    nitzSignal.getReferenceTimeMillis(),
                    nitzSignal.getValue().getCurrentTimeInMillis());

            // Perform rate limiting: a NITZ signal received too close to a previous
            // one will be disregarded unless there is a significant difference between the
            // UTC times they represent.
            if (mSavedNitzTime != null) {
                int nitzUpdateSpacing = mDeviceState.getNitzUpdateSpacingMillis();
                int nitzUpdateDiff = mDeviceState.getNitzUpdateDiffMillis();

                // Calculate the elapsed time between the new signal and the last signal.
                long elapsedRealtimeSinceLastSaved = newNitzTime.getReferenceTimeMillis()
                        - mSavedNitzTime.getReferenceTimeMillis();

                // Calculate the UTC difference between the time the two signals hold.
                long utcTimeDifferenceMillis =
                        newNitzTime.getValue() - mSavedNitzTime.getValue();

                // Ideally the difference between elapsedRealtimeSinceLastSaved and
                // utcTimeDifferenceMillis would be zero.
                long millisGained = utcTimeDifferenceMillis - elapsedRealtimeSinceLastSaved;

                if (elapsedRealtimeSinceLastSaved <= nitzUpdateSpacing
                        && Math.abs(millisGained) <= nitzUpdateDiff) {
                    if (DBG) {
                        Rlog.d(LOG_TAG, "updateTimeFromNitz: not setting time. NITZ signal is"
                                + " too similar to previous value received "
                                + " mSavedNitzTime=" + mSavedNitzTime
                                + ", nitzSignal=" + nitzSignal
                                + ", nitzUpdateSpacing=" + nitzUpdateSpacing
                                + ", nitzUpdateDiff=" + nitzUpdateDiff);
                    }
                    return;
                }
            }

            String logMsg = "updateTimeFromNitz: suggesting system clock update"
                    + " nitzSignal=" + nitzSignal
                    + ", newNitzTime=" + newNitzTime
                    + ", mSavedNitzTime= " + mSavedNitzTime;
            if (DBG) {
                Rlog.d(LOG_TAG, logMsg);
            }
            mTimeLog.log(logMsg);
            mTimeServiceHelper.suggestDeviceTime(newNitzTime);
            TelephonyMetrics.getInstance().writeNITZEvent(
                    mPhone.getPhoneId(), newNitzTime.getValue());

            // Save the last NITZ time signal that was suggested to enable rate limiting.
            mSavedNitzTime = newNitzTime;
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "updateTimeFromNitz: Processing NITZ data"
                    + " nitzSignal=" + nitzSignal
                    + " ex=" + ex);
        }
    }

    private void setAndBroadcastNetworkSetTimeZone(String zoneId) {
        if (DBG) {
            Rlog.d(LOG_TAG, "setAndBroadcastNetworkSetTimeZone: zoneId=" + zoneId);
        }
        mTimeServiceHelper.setDeviceTimeZone(zoneId);
        if (DBG) {
            Rlog.d(LOG_TAG,
                    "setAndBroadcastNetworkSetTimeZone: called setDeviceTimeZone()"
                            + " zoneId=" + zoneId);
        }
    }

    private void handleAutoTimeZoneEnabled() {
        String tmpLog = "handleAutoTimeZoneEnabled: Reverting to NITZ TimeZone:"
                + " mSavedTimeZoneId=" + mSavedTimeZoneId;
        if (DBG) {
            Rlog.d(LOG_TAG, tmpLog);
        }
        mTimeZoneLog.log(tmpLog);
        if (mSavedTimeZoneId != null) {
            setAndBroadcastNetworkSetTimeZone(mSavedTimeZoneId);
        }
    }

    @Override
    public void dumpState(PrintWriter pw) {
        // Time Detection State
        pw.println(" mSavedTime=" + mSavedNitzTime);

        // Time Zone Detection State
        pw.println(" mLatestNitzSignal=" + mLatestNitzSignal);
        pw.println(" mGotCountryCode=" + mGotCountryCode);
        pw.println(" mSavedTimeZoneId=" + mSavedTimeZoneId);
        pw.println(" mNitzTimeZoneDetectionSuccessful=" + mNitzTimeZoneDetectionSuccessful);

        // Miscellaneous
        pw.println(" mWakeLock=" + mWakeLock);
        pw.flush();
    }

    @Override
    public void dumpLogs(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        ipw.println(" Time Logs:");
        ipw.increaseIndent();
        mTimeLog.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println(" Time zone Logs:");
        ipw.increaseIndent();
        mTimeZoneLog.dump(fd, ipw, args);
        ipw.decreaseIndent();
    }

    /**
     * Update time zone by network country code, works well on countries which only have one time
     * zone or multiple zones with the same offset.
     *
     * @param iso Country code from network MCC
     */
    private void updateTimeZoneFromNetworkCountryCode(String iso) {
        CountryResult lookupResult = mTimeZoneLookupHelper.lookupByCountry(
                iso, mTimeServiceHelper.currentTimeMillis());
        if (lookupResult != null && lookupResult.allZonesHaveSameOffset) {
            String logMsg = "updateTimeZoneFromNetworkCountryCode: tz result found"
                    + " iso=" + iso
                    + " lookupResult=" + lookupResult;
            if (DBG) {
                Rlog.d(LOG_TAG, logMsg);
            }
            mTimeZoneLog.log(logMsg);
            String zoneId = lookupResult.zoneId;
            if (mTimeServiceHelper.isTimeZoneDetectionEnabled()) {
                setAndBroadcastNetworkSetTimeZone(zoneId);
            }
            mSavedTimeZoneId = zoneId;
        } else {
            if (DBG) {
                Rlog.d(LOG_TAG, "updateTimeZoneFromNetworkCountryCode: no good zone for"
                        + " iso=" + iso
                        + " lookupResult=" + lookupResult);
            }
        }
    }

    public boolean getNitzTimeZoneDetectionSuccessful() {
        return mNitzTimeZoneDetectionSuccessful;
    }

    @Override
    public NitzData getCachedNitzData() {
        return mLatestNitzSignal != null ? mLatestNitzSignal.getValue() : null;
    }

    @Override
    public String getSavedTimeZoneId() {
        return mSavedTimeZoneId;
    }

}

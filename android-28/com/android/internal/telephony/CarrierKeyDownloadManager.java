/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.preference.PreferenceManager.getDefaultSharedPreferences;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.app.AlarmManager;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.ImsiEncryptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.org.bouncycastle.util.io.pem.PemReader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Random;
import java.util.zip.GZIPInputStream;

/**
 * This class contains logic to get Certificates and keep them current.
 * The class will be instantiated by various Phone implementations.
 */
public class CarrierKeyDownloadManager {
    private static final String LOG_TAG = "CarrierKeyDownloadManager";

    private static final String MCC_MNC_PREF_TAG = "CARRIER_KEY_DM_MCC_MNC";

    private static final int DAY_IN_MILLIS = 24 * 3600 * 1000;

    // Create a window prior to the key expiration, during which the cert will be
    // downloaded. Defines the start date of that window. So if the key expires on
    // Dec  21st, the start of the renewal window will be Dec 1st.
    private static final int START_RENEWAL_WINDOW_DAYS = 21;

    // This will define the end date of the window.
    private static final int END_RENEWAL_WINDOW_DAYS = 7;



    /* Intent for downloading the public key */
    private static final String INTENT_KEY_RENEWAL_ALARM_PREFIX =
            "com.android.internal.telephony.carrier_key_download_alarm";

    @VisibleForTesting
    public int mKeyAvailability = 0;

    public static final String MNC = "MNC";
    public static final String MCC = "MCC";
    private static final String SEPARATOR = ":";

    private static final String JSON_CERTIFICATE = "certificate";
    // This is a hack to accommodate certain Carriers who insists on using the public-key
    // field to store the certificate. We'll just use which-ever is not null.
    private static final String JSON_CERTIFICATE_ALTERNATE = "public-key";
    private static final String JSON_TYPE = "key-type";
    private static final String JSON_IDENTIFIER = "key-identifier";
    private static final String JSON_CARRIER_KEYS = "carrier-keys";
    private static final String JSON_TYPE_VALUE_WLAN = "WLAN";
    private static final String JSON_TYPE_VALUE_EPDG = "EPDG";


    private static final int[] CARRIER_KEY_TYPES = {TelephonyManager.KEY_TYPE_EPDG,
            TelephonyManager.KEY_TYPE_WLAN};
    private static final int UNINITIALIZED_KEY_TYPE = -1;

    private final Phone mPhone;
    private final Context mContext;
    public final DownloadManager mDownloadManager;
    private String mURL;

    public CarrierKeyDownloadManager(Phone phone) {
        mPhone = phone;
        mContext = phone.getContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        filter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        filter.addAction(INTENT_KEY_RENEWAL_ALARM_PREFIX + mPhone.getPhoneId());
        filter.addAction(TelephonyIntents.ACTION_CARRIER_CERTIFICATE_DOWNLOAD);
        mContext.registerReceiver(mBroadcastReceiver, filter, null, phone);
        mDownloadManager = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int slotId = mPhone.getPhoneId();
            if (action.equals(INTENT_KEY_RENEWAL_ALARM_PREFIX + slotId)) {
                Log.d(LOG_TAG, "Handling key renewal alarm: " + action);
                handleAlarmOrConfigChange();
            } else if (action.equals(TelephonyIntents.ACTION_CARRIER_CERTIFICATE_DOWNLOAD)) {
                if (slotId == intent.getIntExtra(PhoneConstants.PHONE_KEY,
                        SubscriptionManager.INVALID_SIM_SLOT_INDEX)) {
                    Log.d(LOG_TAG, "Handling reset intent: " + action);
                    handleAlarmOrConfigChange();
                }
            } else if (action.equals(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)) {
                if (slotId == intent.getIntExtra(PhoneConstants.PHONE_KEY,
                        SubscriptionManager.INVALID_SIM_SLOT_INDEX)) {
                    Log.d(LOG_TAG, "Carrier Config changed: " + action);
                    handleAlarmOrConfigChange();
                }
            } else if (action.equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) {
                Log.d(LOG_TAG, "Download Complete");
                long carrierKeyDownloadIdentifier =
                        intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
                String mccMnc = getMccMncSetFromPref();
                if (isValidDownload(mccMnc)) {
                    onDownloadComplete(carrierKeyDownloadIdentifier, mccMnc);
                    onPostDownloadProcessing(carrierKeyDownloadIdentifier);
                }
            }
        }
    };

    private void onPostDownloadProcessing(long carrierKeyDownloadIdentifier) {
        resetRenewalAlarm();
        cleanupDownloadPreferences(carrierKeyDownloadIdentifier);
    }

    private void handleAlarmOrConfigChange() {
        if (carrierUsesKeys()) {
            if (areCarrierKeysAbsentOrExpiring()) {
                boolean downloadStartedSuccessfully = downloadKey();
                // if the download was attemped, but not started successfully, and if carriers uses
                // keys, we'll still want to renew the alarms, and try downloading the key a day
                // later.
                if (!downloadStartedSuccessfully) {
                    resetRenewalAlarm();
                }
            } else {
                return;
            }
        } else {
            // delete any existing alarms.
            cleanupRenewalAlarms();
        }
    }

    private void cleanupDownloadPreferences(long carrierKeyDownloadIdentifier) {
        Log.d(LOG_TAG, "Cleaning up download preferences: " + carrierKeyDownloadIdentifier);
        SharedPreferences.Editor editor = getDefaultSharedPreferences(mContext).edit();
        editor.remove(String.valueOf(carrierKeyDownloadIdentifier));
        editor.commit();
    }

    private void cleanupRenewalAlarms() {
        Log.d(LOG_TAG, "Cleaning up existing renewal alarms");
        int slotId = mPhone.getPhoneId();
        Intent intent = new Intent(INTENT_KEY_RENEWAL_ALARM_PREFIX + slotId);
        PendingIntent carrierKeyDownloadIntent = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager alarmManager =
                (AlarmManager) mContext.getSystemService(mContext.ALARM_SERVICE);
        alarmManager.cancel(carrierKeyDownloadIntent);
    }

    /**
     * this method returns the date to be used to decide on when to start downloading the key.
     * from the carrier.
     **/
    @VisibleForTesting
    public long getExpirationDate()  {
        long minExpirationDate = Long.MAX_VALUE;
        for (int key_type : CARRIER_KEY_TYPES) {
            if (!isKeyEnabled(key_type)) {
                continue;
            }
            ImsiEncryptionInfo imsiEncryptionInfo =
                    mPhone.getCarrierInfoForImsiEncryption(key_type);
            if (imsiEncryptionInfo != null && imsiEncryptionInfo.getExpirationTime() != null) {
                if (minExpirationDate > imsiEncryptionInfo.getExpirationTime().getTime()) {
                    minExpirationDate = imsiEncryptionInfo.getExpirationTime().getTime();
                }
            }
        }

        // if there are no keys, or expiration date is in the past, or within 7 days, then we
        // set the alarm to run in a day. Else, we'll set the alarm to run 7 days prior to
        // expiration.
        if (minExpirationDate == Long.MAX_VALUE || (minExpirationDate
                < System.currentTimeMillis() + END_RENEWAL_WINDOW_DAYS * DAY_IN_MILLIS)) {
            minExpirationDate = System.currentTimeMillis() + DAY_IN_MILLIS;
        } else {
            // We don't want all the phones to download the certs simultaneously, so
            // we pick a random time during the download window to avoid this situation.
            Random random = new Random();
            int max = START_RENEWAL_WINDOW_DAYS * DAY_IN_MILLIS;
            int min = END_RENEWAL_WINDOW_DAYS * DAY_IN_MILLIS;
            int randomTime = random.nextInt(max - min) + min;
            minExpirationDate = minExpirationDate - randomTime;
        }
        return minExpirationDate;
    }

    /**
     * this method resets the alarm. Starts by cleaning up the existing alarms.
     * We look at the earliest expiration date, and setup an alarms X days prior.
     * If the expiration date is in the past, we'll setup an alarm to run the next day. This
     * could happen if the download has failed.
     **/
    @VisibleForTesting
    public void resetRenewalAlarm() {
        cleanupRenewalAlarms();
        int slotId = mPhone.getPhoneId();
        long minExpirationDate = getExpirationDate();
        Log.d(LOG_TAG, "minExpirationDate: " + new Date(minExpirationDate));
        final AlarmManager alarmManager = (AlarmManager) mContext.getSystemService(
                Context.ALARM_SERVICE);
        Intent intent = new Intent(INTENT_KEY_RENEWAL_ALARM_PREFIX + slotId);
        PendingIntent carrierKeyDownloadIntent = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, minExpirationDate,
                carrierKeyDownloadIntent);
        Log.d(LOG_TAG, "setRenewelAlarm: action=" + intent.getAction() + " time="
                + new Date(minExpirationDate));
    }

    private String getMccMncSetFromPref() {
        // check if this is a download that we had created. We do this by checking if the
        // downloadId is stored in the shared prefs.
        int slotId = mPhone.getPhoneId();
        SharedPreferences preferences = getDefaultSharedPreferences(mContext);
        return preferences.getString(MCC_MNC_PREF_TAG + slotId, null);
    }

    /**
     * Returns the sim operator.
     **/
    @VisibleForTesting
    public String getSimOperator() {
        final TelephonyManager telephonyManager =
                (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        return telephonyManager.getSimOperator(mPhone.getSubId());
    }

    /**
     *  checks if the download was sent by this particular instance. We do this by including the
     *  slot id in the key. If no value is found, we know that the download was not for this
     *  instance of the phone.
     **/
    @VisibleForTesting
    public boolean isValidDownload(String mccMnc) {
        String mccCurrent = "";
        String mncCurrent = "";
        String mccSource = "";
        String mncSource = "";

        String simOperator = getSimOperator();
        if (TextUtils.isEmpty(simOperator) || TextUtils.isEmpty(mccMnc)) {
            Log.e(LOG_TAG, "simOperator or mcc/mnc is empty");
            return false;
        }

        String[] splitValue = mccMnc.split(SEPARATOR);
        mccSource = splitValue[0];
        mncSource = splitValue[1];
        Log.d(LOG_TAG, "values from sharedPrefs mcc, mnc: " + mccSource + "," + mncSource);

        mccCurrent = simOperator.substring(0, 3);
        mncCurrent = simOperator.substring(3);
        Log.d(LOG_TAG, "using values for mcc, mnc: " + mccCurrent + "," + mncCurrent);

        if (TextUtils.equals(mncSource, mncCurrent) &&  TextUtils.equals(mccSource, mccCurrent)) {
            return true;
        }
        return false;
    }

    /**
     * This method will try to parse the downloaded information, and persist it in the database.
     **/
    private void onDownloadComplete(long carrierKeyDownloadIdentifier, String mccMnc) {
        Log.d(LOG_TAG, "onDownloadComplete: " + carrierKeyDownloadIdentifier);
        String jsonStr;
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(carrierKeyDownloadIdentifier);
        Cursor cursor = mDownloadManager.query(query);
        InputStream source = null;

        if (cursor == null) {
            return;
        }
        if (cursor.moveToFirst()) {
            int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (DownloadManager.STATUS_SUCCESSFUL == cursor.getInt(columnIndex)) {
                try {
                    source = new FileInputStream(
                            mDownloadManager.openDownloadedFile(carrierKeyDownloadIdentifier)
                                    .getFileDescriptor());
                    jsonStr = convertToString(source);
                    parseJsonAndPersistKey(jsonStr, mccMnc);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error in download:" + carrierKeyDownloadIdentifier
                            + ". " + e);
                } finally {
                    mDownloadManager.remove(carrierKeyDownloadIdentifier);
                    try {
                        source.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            Log.d(LOG_TAG, "Completed downloading keys");
        }
        cursor.close();
        return;
    }

    /**
     * This method checks if the carrier requires key. We'll read the carrier config to make that
     * determination.
     * @return boolean returns true if carrier requires keys, else false.
     **/
    private boolean carrierUsesKeys() {
        CarrierConfigManager carrierConfigManager = (CarrierConfigManager)
                mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (carrierConfigManager == null) {
            return false;
        }
        int subId = mPhone.getSubId();
        PersistableBundle b = carrierConfigManager.getConfigForSubId(subId);
        if (b == null) {
            return false;
        }
        mKeyAvailability = b.getInt(CarrierConfigManager.IMSI_KEY_AVAILABILITY_INT);
        mURL = b.getString(CarrierConfigManager.IMSI_KEY_DOWNLOAD_URL_STRING);
        if (TextUtils.isEmpty(mURL) || mKeyAvailability == 0) {
            Log.d(LOG_TAG, "Carrier not enabled or invalid values");
            return false;
        }
        for (int key_type : CARRIER_KEY_TYPES) {
            if (isKeyEnabled(key_type)) {
                return true;
            }
        }
        return false;
    }

    private static String convertToString(InputStream is) {
        try {
            // The current implementation at certain Carriers has the data gzipped, which requires
            // us to unzip the contents. Longer term, we want to add a flag in carrier config which
            // determines if the data needs to be zipped or not.
            GZIPInputStream gunzip = new GZIPInputStream(is);
            BufferedReader reader = new BufferedReader(new InputStreamReader(gunzip, UTF_8));
            StringBuilder sb = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Converts the string into a json object to retreive the nodes. The Json should have 3 nodes,
     * including the Carrier public key, the key type and the key identifier. Once the nodes have
     * been extracted, they get persisted to the database. Sample:
     *      "carrier-keys": [ { "certificate": "",
     *                         "key-type": "WLAN",
     *                         "key-identifier": ""
     *                        } ]
     * @param jsonStr the json string.
     * @param mccMnc contains the mcc, mnc.
     */
    @VisibleForTesting
    public void parseJsonAndPersistKey(String jsonStr, String mccMnc) {
        if (TextUtils.isEmpty(jsonStr) || TextUtils.isEmpty(mccMnc)) {
            Log.e(LOG_TAG, "jsonStr or mcc, mnc: is empty");
            return;
        }
        PemReader reader = null;
        try {
            String mcc = "";
            String mnc = "";
            String[] splitValue = mccMnc.split(SEPARATOR);
            mcc = splitValue[0];
            mnc = splitValue[1];
            JSONObject jsonObj = new JSONObject(jsonStr);
            JSONArray keys = jsonObj.getJSONArray(JSON_CARRIER_KEYS);
            for (int i = 0; i < keys.length(); i++) {
                JSONObject key = keys.getJSONObject(i);
                // This is a hack to accommodate certain carriers who insist on using the public-key
                // field to store the certificate. We'll just use which-ever is not null.
                String cert = null;
                if (key.has(JSON_CERTIFICATE)) {
                    cert = key.getString(JSON_CERTIFICATE);
                } else {
                    cert = key.getString(JSON_CERTIFICATE_ALTERNATE);
                }
                String typeString = key.getString(JSON_TYPE);
                int type = UNINITIALIZED_KEY_TYPE;
                if (typeString.equals(JSON_TYPE_VALUE_WLAN)) {
                    type = TelephonyManager.KEY_TYPE_WLAN;
                } else if (typeString.equals(JSON_TYPE_VALUE_EPDG)) {
                    type = TelephonyManager.KEY_TYPE_EPDG;
                }
                String identifier = key.getString(JSON_IDENTIFIER);
                ByteArrayInputStream inStream = new ByteArrayInputStream(cert.getBytes());
                Reader fReader = new BufferedReader(new InputStreamReader(inStream));
                reader = new PemReader(fReader);
                Pair<PublicKey, Long> keyInfo =
                        getKeyInformation(reader.readPemObject().getContent());
                reader.close();
                savePublicKey(keyInfo.first, type, identifier, keyInfo.second, mcc, mnc);
            }
        } catch (final JSONException e) {
            Log.e(LOG_TAG, "Json parsing error: " + e.getMessage());
        } catch (final Exception e) {
            Log.e(LOG_TAG, "Exception getting certificate: " + e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (final Exception e) {
                Log.e(LOG_TAG, "Exception getting certificate: " + e);
            }
        }
    }

    /**
     * introspects the mKeyAvailability bitmask
     * @return true if the digit at position k is 1, else false.
     */
    @VisibleForTesting
    public boolean isKeyEnabled(int keyType) {
        //since keytype has values of 1, 2.... we need to subtract 1 from the keytype.
        int returnValue = (mKeyAvailability >> (keyType - 1)) & 1;
        return (returnValue == 1) ? true : false;
    }

    /**
     * Checks whether is the keys are absent or close to expiration. Returns true, if either of
     * those conditions are true.
     * @return boolean returns true when keys are absent or close to expiration, else false.
     */
    @VisibleForTesting
    public boolean areCarrierKeysAbsentOrExpiring() {
        for (int key_type : CARRIER_KEY_TYPES) {
            if (!isKeyEnabled(key_type)) {
                continue;
            }
            ImsiEncryptionInfo imsiEncryptionInfo =
                    mPhone.getCarrierInfoForImsiEncryption(key_type);
            if (imsiEncryptionInfo == null) {
                Log.d(LOG_TAG, "Key not found for: " + key_type);
                return true;
            }
            Date imsiDate = imsiEncryptionInfo.getExpirationTime();
            long timeToExpire = imsiDate.getTime() - System.currentTimeMillis();
            return (timeToExpire < START_RENEWAL_WINDOW_DAYS * DAY_IN_MILLIS) ? true : false;
        }
        return false;
    }

    private boolean downloadKey() {
        Log.d(LOG_TAG, "starting download from: " + mURL);
        String mcc = "";
        String mnc = "";
        String simOperator = getSimOperator();

        if (!TextUtils.isEmpty(simOperator)) {
            mcc = simOperator.substring(0, 3);
            mnc = simOperator.substring(3);
            Log.d(LOG_TAG, "using values for mcc, mnc: " + mcc + "," + mnc);
        } else {
            Log.e(LOG_TAG, "mcc, mnc: is empty");
            return false;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(mURL));
            request.setAllowedOverMetered(false);
            request.setVisibleInDownloadsUi(false);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
            Long carrierKeyDownloadRequestId = mDownloadManager.enqueue(request);
            SharedPreferences.Editor editor = getDefaultSharedPreferences(mContext).edit();

            String mccMnc = mcc + SEPARATOR + mnc;
            int slotId = mPhone.getPhoneId();
            Log.d(LOG_TAG, "storing values in sharedpref mcc, mnc, days: " + mcc + "," + mnc
                    + "," + carrierKeyDownloadRequestId);
            editor.putString(MCC_MNC_PREF_TAG + slotId, mccMnc);
            editor.commit();
        } catch (Exception e) {
            Log.e(LOG_TAG, "exception trying to dowload key from url: " + mURL);
            return false;
        }
        return true;
    }

    /**
     * Save the public key
     * @param certificate certificate that contains the public key.
     * @return Pair containing the Public Key and the expiration date.
     **/
    @VisibleForTesting
    public static Pair<PublicKey, Long> getKeyInformation(byte[] certificate) throws Exception {
        InputStream inStream = new ByteArrayInputStream(certificate);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(inStream);
        Pair<PublicKey, Long> keyInformation =
                new Pair(cert.getPublicKey(), cert.getNotAfter().getTime());
        return keyInformation;
    }

    /**
     * Save the public key
     * @param publicKey public key.
     * @param type key-type.
     * @param identifier which is an opaque string.
     * @param expirationDate expiration date of the key.
     * @param mcc
     * @param mnc
     **/
    @VisibleForTesting
    public void savePublicKey(PublicKey publicKey, int type, String identifier, long expirationDate,
                               String mcc, String mnc) {
        ImsiEncryptionInfo imsiEncryptionInfo = new ImsiEncryptionInfo(mcc, mnc, type, identifier,
                publicKey, new Date(expirationDate));
        mPhone.setCarrierInfoForImsiEncryption(imsiEncryptionInfo);
    }
}

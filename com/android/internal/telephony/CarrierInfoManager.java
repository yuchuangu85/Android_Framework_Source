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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.provider.Telephony;
import android.telephony.ImsiEncryptionInfo;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.Date;

/**
 * This class provides methods to retreive information from the CarrierKeyProvider.
 */
public class CarrierInfoManager {
    private static final String LOG_TAG = "CarrierInfoManager";

    /**
     * Returns Carrier specific information that will be used to encrypt the IMSI and IMPI.
     * @param keyType whether the key is being used for WLAN or ePDG.
     * @param mContext
     * @return ImsiEncryptionInfo which contains the information, including the public key, to be
     *         used for encryption.
     */
    public static ImsiEncryptionInfo getCarrierInfoForImsiEncryption(int keyType,
                                                                     Context mContext) {
        String mcc = "";
        String mnc = "";
        final TelephonyManager telephonyManager =
                (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        String networkOperator = telephonyManager.getNetworkOperator();
        if (!TextUtils.isEmpty(networkOperator)) {
            mcc = networkOperator.substring(0, 3);
            mnc = networkOperator.substring(3);
            Log.i(LOG_TAG, "using values for mnc, mcc: " + mnc + "," + mcc);
        } else {
            Log.e(LOG_TAG, "Invalid networkOperator: " + networkOperator);
            return null;
        }
        Cursor findCursor = null;
        try {
            // In the current design, MVNOs are not supported. If we decide to support them,
            // we'll need to add to this CL.
            ContentResolver mContentResolver = mContext.getContentResolver();
            String[] columns = {Telephony.CarrierColumns.PUBLIC_KEY,
                    Telephony.CarrierColumns.EXPIRATION_TIME,
                    Telephony.CarrierColumns.KEY_IDENTIFIER};
            findCursor = mContentResolver.query(Telephony.CarrierColumns.CONTENT_URI, columns,
                    "mcc=? and mnc=? and key_type=?",
                    new String[]{mcc, mnc, String.valueOf(keyType)}, null);
            if (findCursor == null || !findCursor.moveToFirst()) {
                Log.d(LOG_TAG, "No rows found for keyType: " + keyType);
                return null;
            }
            if (findCursor.getCount() > 1) {
                Log.e(LOG_TAG, "More than 1 row found for the keyType: " + keyType);
            }
            byte[] carrier_key = findCursor.getBlob(0);
            Date expirationTime = new Date(findCursor.getLong(1));
            String keyIdentifier = findCursor.getString(2);
            return new ImsiEncryptionInfo(mcc, mnc, keyType, keyIdentifier, carrier_key,
                    expirationTime);
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "Bad arguments:" + e);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Query failed:" + e);
        } finally {
            if (findCursor != null) {
                findCursor.close();
            }
        }
        return null;
    }

    /**
     * Inserts or update the Carrier Key in the database
     * @param imsiEncryptionInfo ImsiEncryptionInfo object.
     * @param mContext Context.
     */
    public static void updateOrInsertCarrierKey(ImsiEncryptionInfo imsiEncryptionInfo,
                                                Context mContext) {
        byte[] keyBytes = imsiEncryptionInfo.getPublicKey().getEncoded();
        ContentResolver mContentResolver = mContext.getContentResolver();
        // In the current design, MVNOs are not supported. If we decide to support them,
        // we'll need to add to this CL.
        ContentValues contentValues = new ContentValues();
        contentValues.put(Telephony.CarrierColumns.MCC, imsiEncryptionInfo.getMcc());
        contentValues.put(Telephony.CarrierColumns.MNC, imsiEncryptionInfo.getMnc());
        contentValues.put(Telephony.CarrierColumns.KEY_TYPE,
                imsiEncryptionInfo.getKeyType());
        contentValues.put(Telephony.CarrierColumns.KEY_IDENTIFIER,
                imsiEncryptionInfo.getKeyIdentifier());
        contentValues.put(Telephony.CarrierColumns.PUBLIC_KEY, keyBytes);
        contentValues.put(Telephony.CarrierColumns.EXPIRATION_TIME,
                imsiEncryptionInfo.getExpirationTime().getTime());
        try {
            Log.i(LOG_TAG, "Inserting imsiEncryptionInfo into db");
            mContentResolver.insert(Telephony.CarrierColumns.CONTENT_URI, contentValues);
        } catch (SQLiteConstraintException e) {
            Log.i(LOG_TAG, "Insert failed, updating imsiEncryptionInfo into db");
            ContentValues updatedValues = new ContentValues();
            updatedValues.put(Telephony.CarrierColumns.PUBLIC_KEY, keyBytes);
            updatedValues.put(Telephony.CarrierColumns.EXPIRATION_TIME,
                    imsiEncryptionInfo.getExpirationTime().getTime());
            updatedValues.put(Telephony.CarrierColumns.KEY_IDENTIFIER,
                    imsiEncryptionInfo.getKeyIdentifier());
            try {
                int nRows = mContentResolver.update(Telephony.CarrierColumns.CONTENT_URI,
                        updatedValues,
                        "mcc=? and mnc=? and key_type=?", new String[]{
                                imsiEncryptionInfo.getMcc(),
                                imsiEncryptionInfo.getMnc(),
                                String.valueOf(imsiEncryptionInfo.getKeyType())});
                if (nRows == 0) {
                    Log.d(LOG_TAG, "Error updating values:" + imsiEncryptionInfo);
                }
            } catch (Exception ex) {
                Log.d(LOG_TAG, "Error updating values:" + imsiEncryptionInfo + ex);
            }
        }  catch (Exception e) {
            Log.d(LOG_TAG, "Error inserting/updating values:" + imsiEncryptionInfo + e);
        }
    }

    /**
     * Sets the Carrier specific information that will be used to encrypt the IMSI and IMPI.
     * This includes the public key and the key identifier. This information will be stored in the
     * device keystore.
     * @param imsiEncryptionInfo which includes the Key Type, the Public Key
     *        {@link java.security.PublicKey} and the Key Identifier.
     *        The keyIdentifier Attribute value pair that helps a server locate
     *        the private key to decrypt the permanent identity.
     * @param mContext Context.
     */
    public static void setCarrierInfoForImsiEncryption(ImsiEncryptionInfo imsiEncryptionInfo,
                                                       Context mContext) {
        Log.i(LOG_TAG, "inserting carrier key: " + imsiEncryptionInfo);
        updateOrInsertCarrierKey(imsiEncryptionInfo, mContext);
        //todo send key to modem. Will be done in a subsequent CL.
    }
}
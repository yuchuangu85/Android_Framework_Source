/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;

import com.android.internal.telephony.util.NotificationChannelController;

/**
 * This contains Carrier specific logic based on the states/events
 * managed in ServiceStateTracker.
 * {@hide}
 */
public class CarrierServiceStateTracker extends Handler {
    private static final String LOG_TAG = "CSST";
    protected static final int CARRIER_EVENT_BASE = 100;
    protected static final int CARRIER_EVENT_VOICE_REGISTRATION = CARRIER_EVENT_BASE + 1;
    protected static final int CARRIER_EVENT_VOICE_DEREGISTRATION = CARRIER_EVENT_BASE + 2;
    protected static final int CARRIER_EVENT_DATA_REGISTRATION = CARRIER_EVENT_BASE + 3;
    protected static final int CARRIER_EVENT_DATA_DEREGISTRATION = CARRIER_EVENT_BASE + 4;
    private static final int SHOW_NOTIFICATION = 200;
    private static final int NOTIFICATION_ID = 1000;
    private static final int UNINITIALIZED_DELAY_VALUE = -1;
    private int mDelay = UNINITIALIZED_DELAY_VALUE;
    private Phone mPhone;
    private boolean mIsPhoneRegistered = false;
    private ServiceStateTracker mSST;

    public CarrierServiceStateTracker(Phone phone, ServiceStateTracker sst) {
        this.mPhone = phone;
        this.mSST = sst;
        phone.getContext().registerReceiver(mBroadcastReceiver, new IntentFilter(
                CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case CARRIER_EVENT_VOICE_REGISTRATION:
            case CARRIER_EVENT_DATA_REGISTRATION:
                mIsPhoneRegistered = true;
                handleConfigChanges();
                break;
            case CARRIER_EVENT_VOICE_DEREGISTRATION:
            case CARRIER_EVENT_DATA_DEREGISTRATION:
                if (isGlobalModeOrRadioOffOrAirplaneMode()) {
                    break;
                }
                mIsPhoneRegistered = false;
                handleConfigChanges();
                break;
            case SHOW_NOTIFICATION:
                sendNotification();
                break;
        }
    }

    /**
     * Returns true if the preferred network is set to 'Global' or the radio is off or in
     * Airplane Mode else returns false.
     */
    private boolean isGlobalModeOrRadioOffOrAirplaneMode() {
        Context context = mPhone.getContext();
        int preferredNetworkSetting = -1;
        int airplaneMode = -1;
        int subId = mPhone.getSubId();
        try {
            preferredNetworkSetting =
                    android.provider.Settings.Global.getInt(context.getContentResolver(),
                            android.provider.Settings.Global.PREFERRED_NETWORK_MODE + subId,
                            Phone.PREFERRED_NT_MODE);
            airplaneMode = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0);
        } catch (Exception e) {
            Rlog.e(LOG_TAG, "Unable to get PREFERRED_NETWORK_MODE.");
            return true;
        }
        return ((preferredNetworkSetting == RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA) ||
                !mSST.isRadioOn() || (airplaneMode != 0));
    }

    /**
     * Contains logic to decide when to create/cancel notifications.
     */
    private void handleConfigChanges() {

        if (mDelay == UNINITIALIZED_DELAY_VALUE) {
            cancelNotification();
            return;
        }
        // send a notification if the device is registerd to a network.
        if (mIsPhoneRegistered) {
            cancelNotification();
            Rlog.i(LOG_TAG, "canceling all notifications. ");
        } else {
            Message notificationMsg;
            notificationMsg = obtainMessage(SHOW_NOTIFICATION, null);
            Rlog.i(LOG_TAG, "starting timer for notifications. ");
            sendMessageDelayed(notificationMsg, mDelay);
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            CarrierConfigManager carrierConfigManager = (CarrierConfigManager)
                    context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
            PersistableBundle b = carrierConfigManager.getConfig();
            mDelay = b.getInt(CarrierConfigManager.KEY_PREF_NETWORK_NOTIFICATION_DELAY_INT);
            Rlog.i(LOG_TAG, "reading time to delay notification: " + mDelay);
            handleConfigChanges();
        }
    };

    /**
     * Post a notification to the NotificationManager for changing network type.
     */
    private void sendNotification() {
        Context context = mPhone.getContext();

        Rlog.i(LOG_TAG, "w/values: " + "," + mIsPhoneRegistered + "," + mDelay
                + "," + isGlobalModeOrRadioOffOrAirplaneMode() + "," + mSST.isRadioOn());

        // exit if the network preference is set to Global or if the phone is registered.
        if (isGlobalModeOrRadioOffOrAirplaneMode() || mIsPhoneRegistered) {
            return;
        }

        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);


        Intent notificationIntent = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
        PendingIntent settingsIntent = PendingIntent.getActivity(context, 0, notificationIntent,
                PendingIntent.FLAG_ONE_SHOT);

        CharSequence title =
                context.getText(com.android.internal.R.string.NetworkPreferenceSwitchTitle);
        CharSequence details =
                context.getText(com.android.internal.R.string.NetworkPreferenceSwitchSummary);


        Notification mNotification = new Notification.Builder(context)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setColor(context.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setContentText(details)
                .setContentIntent(settingsIntent)
                .setChannel(NotificationChannelController.CHANNEL_ID_ALERT)
                .build();

        notificationManager.notify(NOTIFICATION_ID, mNotification);
    }

    /**
     * Cancel notifications if a registration is pending or has been sent.
     */
    private void cancelNotification() {
        Context context = mPhone.getContext();
        mIsPhoneRegistered = true;
        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }
}
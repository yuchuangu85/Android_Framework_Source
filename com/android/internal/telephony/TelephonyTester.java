/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.telephony.Rlog;

import com.android.ims.ImsCall;
import com.android.ims.ImsConferenceState;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneCall;
import com.android.internal.telephony.test.TestConferenceEventPackageParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

/**
 * Telephony tester receives the following intents where {name} is the phone name
 *
 * adb shell am broadcast -a com.android.internal.telephony.{name}.action_detached
 * adb shell am broadcast -a com.android.internal.telephony.{name}.action_attached
 * adb shell am broadcast -a com.android.internal.telephony.TestConferenceEventPackage -e filename
 *      test_filename.xml
 */
public class TelephonyTester {
    private static final String LOG_TAG = "TelephonyTester";
    private static final boolean DBG = true;

    /**
     * Test-only intent used to send a test conference event package to the IMS framework.
     */
    private static final String ACTION_TEST_CONFERENCE_EVENT_PACKAGE =
            "com.android.internal.telephony.TestConferenceEventPackage";
    private static final String EXTRA_FILENAME = "filename";

    private PhoneBase mPhone;

    // The static intent receiver one for all instances and we assume this
    // is running on the same thread as Dcc.
    protected BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
            @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DBG) log("sIntentReceiver.onReceive: action=" + action);
            if (action.equals(mPhone.getActionDetached())) {
                log("simulate detaching");
                mPhone.getServiceStateTracker().mDetachedRegistrants.notifyRegistrants();
            } else if (action.equals(mPhone.getActionAttached())) {
                log("simulate attaching");
                mPhone.getServiceStateTracker().mAttachedRegistrants.notifyRegistrants();
            } else if (action.equals(ACTION_TEST_CONFERENCE_EVENT_PACKAGE)) {
                log("inject simulated conference event package");
                handleTestConferenceEventPackage(context, intent.getStringExtra(EXTRA_FILENAME));
            } else {
                if (DBG) log("onReceive: unknown action=" + action);
            }
        }
    };

    TelephonyTester(PhoneBase phone) {
        mPhone = phone;

        if (Build.IS_DEBUGGABLE) {
            IntentFilter filter = new IntentFilter();

            filter.addAction(mPhone.getActionDetached());
            log("register for intent action=" + mPhone.getActionDetached());

            filter.addAction(mPhone.getActionAttached());
            log("register for intent action=" + mPhone.getActionAttached());

            if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
                log("register for intent action=" + ACTION_TEST_CONFERENCE_EVENT_PACKAGE);
                filter.addAction(ACTION_TEST_CONFERENCE_EVENT_PACKAGE);
            }

            phone.getContext().registerReceiver(mIntentReceiver, filter, null, mPhone.getHandler());
        }
    }

    void dispose() {
        if (Build.IS_DEBUGGABLE) {
            mPhone.getContext().unregisterReceiver(mIntentReceiver);
        }
    }

    private static void log(String s) {
        Rlog.d(LOG_TAG, s);
    }

    /**
     * Handles request to send a test conference event package to the active Ims call.
     *
     * @see com.android.internal.telephony.test.TestConferenceEventPackageParser
     * @param context The context.
     * @param fileName The name of the test conference event package file to read.
     */
    private void handleTestConferenceEventPackage(Context context, String fileName) {
        // Attempt to get the active IMS call before parsing the test XML file.
        ImsPhone imsPhone = (ImsPhone) mPhone;
        if (imsPhone == null) {
            return;
        }

        ImsPhoneCall imsPhoneCall = imsPhone.getForegroundCall();
        if (imsPhoneCall == null) {
            return;
        }

        ImsCall imsCall = imsPhoneCall.getImsCall();
        if (imsCall == null) {
            return;
        }

        File packageFile = new File(context.getFilesDir(), fileName);
        final FileInputStream is;
        try {
            is = new FileInputStream(packageFile);
        } catch (FileNotFoundException ex) {
            log("Test conference event package file not found: " + packageFile.getAbsolutePath());
            return;
        }

        TestConferenceEventPackageParser parser = new TestConferenceEventPackageParser(is);
        ImsConferenceState imsConferenceState = parser.parse();
        if (imsConferenceState == null) {
            return;
        }

        imsCall.conferenceStateUpdated(imsConferenceState);
    }
}

/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.companion;

/**
 * @hide
 */
public final class CompanionResources {

    // Permission sets for association dialogs
    public static final int PERMISSION_NOTIFICATION_LISTENER_ACCESS = 0;
    public static final int PERMISSION_STORAGE = 1;
    public static final int PERMISSION_PHONE = 2;
    public static final int PERMISSION_SMS = 3;
    public static final int PERMISSION_CONTACTS = 4;
    public static final int PERMISSION_CALENDAR = 5;
    public static final int PERMISSION_NEARBY_DEVICES = 6;
    public static final int PERMISSION_MICROPHONE = 7;
    public static final int PERMISSION_CALL_LOGS = 8;
    // Notification Listener Access & POST_NOTIFICATION permission
    public static final int PERMISSION_NOTIFICATIONS = 9;
    public static final int PERMISSION_CHANGE_MEDIA_OUTPUT = 10;
    public static final int PERMISSION_POST_NOTIFICATIONS = 11;
    public static final int PERMISSION_CREATE_VIRTUAL_DEVICE = 12;
    public static final int PERMISSION_ADD_MIRROR_DISPLAY = 13;
    public static final int PERMISSION_ADD_TRUSTED_DISPLAY = 14;
    public static final int PERMISSION_SCHEDULE_EXACT_ALARM = 15;
    public static final int PERMISSION_BYPASS_DND = 16;
    public static final int PERMISSION_APP_STREAMING = 17;

    // Constants used by AssociationRequestProcessor and CompanionAssociationActivity
    public static final String EXTRA_APPLICATION_CALLBACK = "application_callback";
    public static final String EXTRA_ASSOCIATION_REQUEST = "association_request";
    public static final String EXTRA_RESULT_RECEIVER = "result_receiver";
    public static final String EXTRA_FORCE_CANCEL_CONFIRMATION = "cancel_confirmation";
    public static final int RESULT_CODE_ASSOCIATION_CREATED = 0;
    public static final String EXTRA_ASSOCIATION = "association";
    public static final int RESULT_CODE_ASSOCIATION_APPROVED = 0;
    public static final String EXTRA_MAC_ADDRESS = "mac_address";
}

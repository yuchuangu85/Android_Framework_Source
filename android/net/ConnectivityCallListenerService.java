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

package android.net;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Process;
import android.os.UserHandle;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.tethering.flags.Flags;

import java.util.Map;

/**
 * An {@link InCallService} that monitors the state of calls to influence system-level network
 * behavior based on call activity.
 *
 * <p>This service acts as a listener for call state changes (e.g., call added, call removed) and
 * can trigger actions within the connectivity subsystem. Its purpose is to bridge the gap between
 * the telecom framework and network policy management, allowing call status to inform network
 * preferences.
 *
 * <h3>Expected Telecom Callback Behavior</h3>
 * <p>As an {@link InCallService}, this service's functionality relies on the timing and
 * correctness of the {@link #onCallAdded(Call)} and {@link #onCallRemoved(Call)} callbacks
 * from the Telecom framework. This service expects the following behavior from Telecom:
 * <ul>
 *     <li><b>Call Termination:</b> This service relies on the Telecom framework
 *     to issue an {@link #onCallRemoved(Call)} event for any reason a call ends. This includes
 *     normal hangup, an application crash, or an application uninstall while a call is active.
 *     This service does not independently track application lifecycle events.</li>
 * </ul>
 *
 * @hide
 */
// All methods on this class are called on the same thread.
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@FlaggedApi(Flags.FLAG_ENABLE_INCALL_SERVICE_API)
public class ConnectivityCallListenerService extends InCallService {
    private static final String TAG = "ConnCallListenerSvc";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private PackageManager mPackageManager;
    private ConnectivityManager mConnectivityManager;
    private static final int APPLICATION_INFO_FLAGS = 0;
    private boolean mSupportOttNetworkSlicing;
    private TelecomManager mTelecomManager;

    // Caches the UID for each individual call
    private final Map<String, Integer> mCallUidMap = new ArrayMap<>();

    /**
     * Local constant to mirror the platform's {@link
     * PhoneAccount#CAPABILITY_OPT_OUT_OF_PREMIUM_NETWORK}
     *
     * This is a temporary measure because the platform API will not be public until the 26Q2
     * release. Using a local constant allows us to implement the opt-out feature in this
     * mainline module without creating a dependency on the yet-to-be-released public API.
     *
     * Once the platform API is public, this local constant should be removed and replaced with
     * the official {@link PhoneAccount#CAPABILITY_OPT_OUT_OF_PREMIUM_NETWORK}
     *
     * For more details, see b/447631226.
     */
     // TODO (b/468165661) : Replace local constant with platform Api once available
    private static final int CAPABILITY_OPT_OUT_OF_PREMIUM_NETWORK = 0x200000;

    @Override
    public void onCreate() {
        super.onCreate();
        if (DBG) Log.d(TAG, "onCreate() called");
        mPackageManager = getPackageManager();
        mConnectivityManager = getSystemService(ConnectivityManager.class);
        mTelecomManager = getSystemService(TelecomManager.class);
        mSupportOttNetworkSlicing = mConnectivityManager.isFeatureEnabled(
                ConnectivityManager.FEATURE_OTT_NETWORK_SLICING);
        if (DBG) Log.d(TAG, "ott network slicing status:" + mSupportOttNetworkSlicing);
    }

    @Override
    public void onCallAdded(@Nullable Call call) {
        super.onCallAdded(call);
        if (DBG) Log.d(TAG, "onCallAdded called");

        if (!mSupportOttNetworkSlicing) {
            if (DBG) Log.d(TAG, "ott network slicing feature is disabled");
            return;
        }
        handleOnCallAdded(call);
    }

    @Override
    public void onCallRemoved(@Nullable Call call) {
        super.onCallRemoved(call);
        if (DBG) Log.d(TAG, "onCallRemoved called");

        if (!mSupportOttNetworkSlicing) {
            if (DBG) Log.d(TAG, "ott network slicing feature is disabled");
            return;
        }

        if (call == null || call.getDetails() == null) {
            Log.w(TAG, "onCallRemoved: Ignoring call with null call or details.");
            return;
        }

        final String callId = call.getDetails().getId();
        final Integer uid = mCallUidMap.remove(callId);
        if (uid == null) {
            return;
        }

        // check if other calls for this UID are still active, if so avoid removing slicing for the
        // uid
        if (!mCallUidMap.containsValue(uid)) {
            Log.i(TAG, "Processing transactional OTT onCallRemoved for call ID with uid: "
                    + callId + " : " + uid);
            mConnectivityManager.onOttCallStateChanged(uid, false /*isAdd*/);
        }
    }

    /**
     * Extracts the UID from the call's PhoneAccountHandle for the correct user.
     */
    private int getUidFromCall(@NonNull PhoneAccountHandle accountHandle) {
        final String packageName = accountHandle.getComponentName().getPackageName();
        final UserHandle userHandle = accountHandle.getUserHandle();

        if (userHandle == null) {
            Log.w(TAG, "PhoneAccountHandle has null UserHandle for package: " + packageName);
            return Process.INVALID_UID;
        }

        try {
            // Use getApplicationInfoAsUser to get the info for the correct user.
            final ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(packageName,
                    APPLICATION_INFO_FLAGS, userHandle);
            return appInfo.uid;
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not find package info for user " + userHandle.getIdentifier()
                    + " and package " + packageName, e);
            return Process.INVALID_UID;
        }
    }

    /**
     * Determines if a call is eligible for network slicing.
     */
    private boolean isCallEligibleForSlicing(@NonNull Call.Details details,
            @NonNull PhoneAccountHandle handle) {

        if (!details.hasProperty(Call.Details.PROPERTY_IS_TRANSACTIONAL)) {
            Log.i(TAG, "non transactional ott call, ignore slicing");
            return false;
        }

        // The platform supports multiple PhoneAccounts per app (e.g., for multiple user logins
        // associated with the app). Slicing eligibility is evaluated per-call based on the specific
        // account handle associated with this call.
        final PhoneAccount phoneAccount = mTelecomManager.getPhoneAccount(handle);

        // Note: Modifying PhoneAccount properties after a call starts (e.g., via
        // re-registration) does not affect live calls. Eligibility is determined when
        // the call is first added to the service.
        if (phoneAccount != null &&
                phoneAccount.hasCapabilities(CAPABILITY_OPT_OUT_OF_PREMIUM_NETWORK)) {
            Log.i(TAG, "opt out of slicing set");
            return false;
        }

        if (details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED)) {
            return true;
        }

        return phoneAccount != null &&
                phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED);
    }

    /**
     * Handles call state onCallAdded updates from InCallService, filters for valid,
     * eligible OTT calls, and notifies the {@link com.android.server.ConnectivityService}.
     *
     * <p>This is the primary implementation of this method for OTT network slicing.
     * It listens for transactional OTT calls and notifies the
     * {@link com.android.server.ConnectivityService}, which then applies or revokes the
     * {@link NetworkCapabilities#NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS}
     * network slice for the corresponding application UID to ensure a higher Quality of Service.
     */
    private void handleOnCallAdded(@Nullable Call call) {
        if (call == null || call.getDetails() == null) {
            Log.w(TAG, "handleOnCallAdded: Ignoring call with null call or details.");
            return;
        }
        final Call.Details details = call.getDetails();

        final PhoneAccountHandle handle = details.getAccountHandle();
        if (handle == null) {
            Log.w(TAG, "handleOnCallAdded: Ignoring call with null PhoneAccountHandle.");
            return;
        }

        if (!isCallEligibleForSlicing(details, handle)) {
            if (DBG) Log.d(TAG, "handleOnCallAdded: slicing not allowed");
            return;
        }

        final int uid = getUidFromCall(handle);
        if (uid == Process.INVALID_UID) {
            Log.w(TAG, "handleOnCallAdded: Ignoring call with invalid UID for add.");
            return;
        }
        final String callId = details.getId();

        // Check if this is the first call for this UID
        boolean isFirstCallForUid = !mCallUidMap.containsValue(uid);

        mCallUidMap.put(callId, uid);

        // Avoid sending slicing request for the same uid, in case of multiple call with same uid
        if (isFirstCallForUid) {
            Log.i(TAG, "Processing transactional OTT onCallAdded for call ID with uid: "
                    + callId + " : " + uid);
            mConnectivityManager.onOttCallStateChanged(uid, true /*isAdd*/);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) Log.d(TAG, "onDestroy() called");
    }
}

/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.systemui.qs;

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.systemui.qs.dagger.QSFragmentModule.QS_SECURITY_FOOTER_VIEW;

import android.app.AlertDialog;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyEventLogger;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.FrameworkStatsLog;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.SecurityController;

import javax.inject.Inject;
import javax.inject.Named;

@QSScope
class QSSecurityFooter implements OnClickListener, DialogInterface.OnClickListener {
    protected static final String TAG = "QSSecurityFooter";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean DEBUG_FORCE_VISIBLE = false;

    private final View mRootView;
    private final TextView mFooterText;
    private final ImageView mPrimaryFooterIcon;
    private final Context mContext;
    private final Callback mCallback = new Callback();
    private final SecurityController mSecurityController;
    private final ActivityStarter mActivityStarter;
    private final Handler mMainHandler;
    private final UserTracker mUserTracker;

    private AlertDialog mDialog;
    private QSTileHost mHost;
    protected H mHandler;

    private boolean mIsVisible;
    private CharSequence mFooterTextContent = null;
    private int mFooterIconId;
    private Drawable mPrimaryFooterIconDrawable;

    @Inject
    QSSecurityFooter(@Named(QS_SECURITY_FOOTER_VIEW) View rootView,
            UserTracker userTracker, @Main Handler mainHandler, ActivityStarter activityStarter,
            SecurityController securityController, @Background Looper bgLooper) {
        mRootView = rootView;
        mRootView.setOnClickListener(this);
        mFooterText = mRootView.findViewById(R.id.footer_text);
        mPrimaryFooterIcon = mRootView.findViewById(R.id.primary_footer_icon);
        mFooterIconId = R.drawable.ic_info_outline;
        mContext = rootView.getContext();
        mMainHandler = mainHandler;
        mActivityStarter = activityStarter;
        mSecurityController = securityController;
        mHandler = new H(bgLooper);
        mUserTracker = userTracker;
    }

    public void setHostEnvironment(QSTileHost host) {
        mHost = host;
    }

    public void setListening(boolean listening) {
        if (listening) {
            mSecurityController.addCallback(mCallback);
            refreshState();
        } else {
            mSecurityController.removeCallback(mCallback);
        }
    }

    public void onConfigurationChanged() {
        FontSizeUtils.updateFontSize(mFooterText, R.dimen.qs_tile_text_size);

        Resources r = mContext.getResources();

        mFooterText.setMaxLines(r.getInteger(R.integer.qs_security_footer_maxLines));
        int padding = r.getDimensionPixelSize(R.dimen.qs_footer_padding);
        mRootView.setPaddingRelative(padding, padding, padding, padding);

        int bottomMargin = r.getDimensionPixelSize(R.dimen.qs_footers_margin_bottom);
        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) mRootView.getLayoutParams();
        lp.bottomMargin = bottomMargin;
        lp.width = r.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT
                ? MATCH_PARENT : WRAP_CONTENT;
        mRootView.setLayoutParams(lp);

        mRootView.setBackground(mContext.getDrawable(R.drawable.qs_security_footer_background));
    }

    public View getView() {
        return mRootView;
    }

    public boolean hasFooter() {
        return mRootView.getVisibility() != View.GONE;
    }

    @Override
    public void onClick(View v) {
        if (!hasFooter()) return;
        mHandler.sendEmptyMessage(H.CLICK);
    }

    private void handleClick() {
        showDeviceMonitoringDialog();
        DevicePolicyEventLogger
                .createEvent(FrameworkStatsLog.DEVICE_POLICY_EVENT__EVENT_ID__DO_USER_INFO_CLICKED)
                .write();
    }

    public void showDeviceMonitoringDialog() {
        createDialog();
    }

    public void refreshState() {
        mHandler.sendEmptyMessage(H.REFRESH_STATE);
    }

    private void handleRefreshState() {
        final boolean isDeviceManaged = mSecurityController.isDeviceManaged();
        final UserInfo currentUser = mUserTracker.getUserInfo();
        final boolean isDemoDevice = UserManager.isDeviceInDemoMode(mContext) && currentUser != null
                && currentUser.isDemo();
        final boolean hasWorkProfile = mSecurityController.hasWorkProfile();
        final boolean hasCACerts = mSecurityController.hasCACertInCurrentUser();
        final boolean hasCACertsInWorkProfile = mSecurityController.hasCACertInWorkProfile();
        final boolean isNetworkLoggingEnabled = mSecurityController.isNetworkLoggingEnabled();
        final String vpnName = mSecurityController.getPrimaryVpnName();
        final String vpnNameWorkProfile = mSecurityController.getWorkProfileVpnName();
        final CharSequence organizationName = mSecurityController.getDeviceOwnerOrganizationName();
        final CharSequence workProfileOrganizationName =
                mSecurityController.getWorkProfileOrganizationName();
        final boolean isProfileOwnerOfOrganizationOwnedDevice =
                mSecurityController.isProfileOwnerOfOrganizationOwnedDevice();
        final boolean isParentalControlsEnabled = mSecurityController.isParentalControlsEnabled();
        final boolean isWorkProfileOn = mSecurityController.isWorkProfileOn();
        final boolean hasDisclosableWorkProfilePolicy = hasCACertsInWorkProfile
                || vpnNameWorkProfile != null || (hasWorkProfile && isNetworkLoggingEnabled);
        // Update visibility of footer
        mIsVisible = (isDeviceManaged && !isDemoDevice)
                || hasCACerts
                || vpnName != null
                || isProfileOwnerOfOrganizationOwnedDevice
                || isParentalControlsEnabled
                || (hasDisclosableWorkProfilePolicy && isWorkProfileOn);
        // Update the view to be untappable if the device is an organization-owned device with a
        // managed profile and there is either:
        // a) no policy set which requires a privacy disclosure.
        // b) a specific work policy set but the work profile is turned off.
        if (mIsVisible && isProfileOwnerOfOrganizationOwnedDevice
                && (!hasDisclosableWorkProfilePolicy || !isWorkProfileOn)) {
            mRootView.setClickable(false);
            mRootView.findViewById(R.id.footer_icon).setVisibility(View.GONE);
        } else {
            mRootView.setClickable(true);
            mRootView.findViewById(R.id.footer_icon).setVisibility(View.VISIBLE);
        }
        // Update the string
        mFooterTextContent = getFooterText(isDeviceManaged, hasWorkProfile,
                hasCACerts, hasCACertsInWorkProfile, isNetworkLoggingEnabled, vpnName,
                vpnNameWorkProfile, organizationName, workProfileOrganizationName,
                isProfileOwnerOfOrganizationOwnedDevice, isParentalControlsEnabled,
                isWorkProfileOn);
        // Update the icon
        int footerIconId = R.drawable.ic_info_outline;
        if (vpnName != null || vpnNameWorkProfile != null) {
            if (mSecurityController.isVpnBranded()) {
                footerIconId = R.drawable.stat_sys_branded_vpn;
            } else {
                footerIconId = R.drawable.stat_sys_vpn_ic;
            }
        }
        if (mFooterIconId != footerIconId) {
            mFooterIconId = footerIconId;
        }

        // Update the primary icon
        if (isParentalControlsEnabled) {
            if (mPrimaryFooterIconDrawable == null) {
                DeviceAdminInfo info = mSecurityController.getDeviceAdminInfo();
                mPrimaryFooterIconDrawable = mSecurityController.getIcon(info);
            }
        } else {
            mPrimaryFooterIconDrawable = null;
        }
        mMainHandler.post(mUpdatePrimaryIcon);

        mMainHandler.post(mUpdateDisplayState);
    }

    protected CharSequence getFooterText(boolean isDeviceManaged, boolean hasWorkProfile,
            boolean hasCACerts, boolean hasCACertsInWorkProfile, boolean isNetworkLoggingEnabled,
            String vpnName, String vpnNameWorkProfile, CharSequence organizationName,
            CharSequence workProfileOrganizationName,
            boolean isProfileOwnerOfOrganizationOwnedDevice, boolean isParentalControlsEnabled,
            boolean isWorkProfileOn) {
        if (isParentalControlsEnabled) {
            return mContext.getString(R.string.quick_settings_disclosure_parental_controls);
        }
        if (isDeviceManaged || DEBUG_FORCE_VISIBLE) {
            if (hasCACerts || hasCACertsInWorkProfile || isNetworkLoggingEnabled) {
                if (organizationName == null) {
                    return mContext.getString(
                            R.string.quick_settings_disclosure_management_monitoring);
                }
                return mContext.getString(
                        R.string.quick_settings_disclosure_named_management_monitoring,
                        organizationName);
            }
            if (vpnName != null && vpnNameWorkProfile != null) {
                if (organizationName == null) {
                    return mContext.getString(R.string.quick_settings_disclosure_management_vpns);
                }
                return mContext.getString(R.string.quick_settings_disclosure_named_management_vpns,
                        organizationName);
            }
            if (vpnName != null || vpnNameWorkProfile != null) {
                if (organizationName == null) {
                    return mContext.getString(
                            R.string.quick_settings_disclosure_management_named_vpn,
                            vpnName != null ? vpnName : vpnNameWorkProfile);
                }
                return mContext.getString(
                        R.string.quick_settings_disclosure_named_management_named_vpn,
                        organizationName,
                        vpnName != null ? vpnName : vpnNameWorkProfile);
            }
            if (organizationName == null) {
                return mContext.getString(R.string.quick_settings_disclosure_management);
            }
            if (isFinancedDevice()) {
                return mContext.getString(
                        R.string.quick_settings_financed_disclosure_named_management,
                        organizationName);
            } else {
                return mContext.getString(R.string.quick_settings_disclosure_named_management,
                        organizationName);
            }
        } // end if(isDeviceManaged)
        if (hasCACertsInWorkProfile && isWorkProfileOn) {
            if (workProfileOrganizationName == null) {
                return mContext.getString(
                        R.string.quick_settings_disclosure_managed_profile_monitoring);
            }
            return mContext.getString(
                    R.string.quick_settings_disclosure_named_managed_profile_monitoring,
                    workProfileOrganizationName);
        }
        if (hasCACerts) {
            return mContext.getString(R.string.quick_settings_disclosure_monitoring);
        }
        if (vpnName != null && vpnNameWorkProfile != null) {
            return mContext.getString(R.string.quick_settings_disclosure_vpns);
        }
        if (vpnNameWorkProfile != null && isWorkProfileOn) {
            return mContext.getString(R.string.quick_settings_disclosure_managed_profile_named_vpn,
                    vpnNameWorkProfile);
        }
        if (vpnName != null) {
            if (hasWorkProfile) {
                return mContext.getString(
                        R.string.quick_settings_disclosure_personal_profile_named_vpn,
                        vpnName);
            }
            return mContext.getString(R.string.quick_settings_disclosure_named_vpn,
                    vpnName);
        }
        if (hasWorkProfile && isNetworkLoggingEnabled && isWorkProfileOn) {
            return mContext.getString(
                    R.string.quick_settings_disclosure_managed_profile_network_activity);
        }
        if (isProfileOwnerOfOrganizationOwnedDevice) {
            if (workProfileOrganizationName == null) {
                return mContext.getString(R.string.quick_settings_disclosure_management);
            }
            return mContext.getString(R.string.quick_settings_disclosure_named_management,
                    workProfileOrganizationName);
        }
        return null;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            final Intent intent = new Intent(Settings.ACTION_ENTERPRISE_PRIVACY_SETTINGS);
            mDialog.dismiss();
            // This dismisses the shade on opening the activity
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        }
    }

    private void createDialog() {
        mDialog = new SystemUIDialog(mContext, 0); // Use mContext theme
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setButton(DialogInterface.BUTTON_POSITIVE, getPositiveButton(), this);
        mDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getNegativeButton(), this);

        mDialog.setView(createDialogView());

        mDialog.show();
        mDialog.getWindow().setLayout(MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @VisibleForTesting
    View createDialogView() {
        if (mSecurityController.isParentalControlsEnabled()) {
            return createParentalControlsDialogView();
        }
        return createOrganizationDialogView();
    }

    private View createOrganizationDialogView() {
        final boolean isDeviceManaged = mSecurityController.isDeviceManaged();
        final boolean hasWorkProfile = mSecurityController.hasWorkProfile();
        final CharSequence deviceOwnerOrganization =
                mSecurityController.getDeviceOwnerOrganizationName();
        final boolean hasCACerts = mSecurityController.hasCACertInCurrentUser();
        final boolean hasCACertsInWorkProfile = mSecurityController.hasCACertInWorkProfile();
        final boolean isNetworkLoggingEnabled = mSecurityController.isNetworkLoggingEnabled();
        final String vpnName = mSecurityController.getPrimaryVpnName();
        final String vpnNameWorkProfile = mSecurityController.getWorkProfileVpnName();

        View dialogView = LayoutInflater.from(mContext)
                .inflate(R.layout.quick_settings_footer_dialog, null, false);

        // device management section
        TextView deviceManagementSubtitle =
                dialogView.findViewById(R.id.device_management_subtitle);
        deviceManagementSubtitle.setText(getManagementTitle(deviceOwnerOrganization));

        CharSequence managementMessage = getManagementMessage(isDeviceManaged,
                deviceOwnerOrganization);
        if (managementMessage == null) {
            dialogView.findViewById(R.id.device_management_disclosures).setVisibility(View.GONE);
        } else {
            dialogView.findViewById(R.id.device_management_disclosures).setVisibility(View.VISIBLE);
            TextView deviceManagementWarning =
                    (TextView) dialogView.findViewById(R.id.device_management_warning);
            deviceManagementWarning.setText(managementMessage);
            mDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getSettingsButton(), this);
        }

        // ca certificate section
        CharSequence caCertsMessage = getCaCertsMessage(isDeviceManaged, hasCACerts,
                hasCACertsInWorkProfile);
        if (caCertsMessage == null) {
            dialogView.findViewById(R.id.ca_certs_disclosures).setVisibility(View.GONE);
        } else {
            dialogView.findViewById(R.id.ca_certs_disclosures).setVisibility(View.VISIBLE);
            TextView caCertsWarning = (TextView) dialogView.findViewById(R.id.ca_certs_warning);
            caCertsWarning.setText(caCertsMessage);
            // Make "Open trusted credentials"-link clickable
            caCertsWarning.setMovementMethod(new LinkMovementMethod());
        }

        // network logging section
        CharSequence networkLoggingMessage = getNetworkLoggingMessage(isDeviceManaged,
                isNetworkLoggingEnabled);
        if (networkLoggingMessage == null) {
            dialogView.findViewById(R.id.network_logging_disclosures).setVisibility(View.GONE);
        } else {
            dialogView.findViewById(R.id.network_logging_disclosures).setVisibility(View.VISIBLE);
            TextView networkLoggingWarning =
                    (TextView) dialogView.findViewById(R.id.network_logging_warning);
            networkLoggingWarning.setText(networkLoggingMessage);
        }

        // vpn section
        CharSequence vpnMessage = getVpnMessage(isDeviceManaged, hasWorkProfile, vpnName,
                vpnNameWorkProfile);
        if (vpnMessage == null) {
            dialogView.findViewById(R.id.vpn_disclosures).setVisibility(View.GONE);
        } else {
            dialogView.findViewById(R.id.vpn_disclosures).setVisibility(View.VISIBLE);
            TextView vpnWarning = (TextView) dialogView.findViewById(R.id.vpn_warning);
            vpnWarning.setText(vpnMessage);
            // Make "Open VPN Settings"-link clickable
            vpnWarning.setMovementMethod(new LinkMovementMethod());
        }

        // Note: if a new section is added, should update configSubtitleVisibility to include
        // the handling of the subtitle
        configSubtitleVisibility(managementMessage != null,
                caCertsMessage != null,
                networkLoggingMessage != null,
                vpnMessage != null,
                dialogView);

        return dialogView;
    }

    private View createParentalControlsDialogView() {
        View dialogView = LayoutInflater.from(mContext)
                .inflate(R.layout.quick_settings_footer_dialog_parental_controls, null, false);

        DeviceAdminInfo info = mSecurityController.getDeviceAdminInfo();
        Drawable icon = mSecurityController.getIcon(info);
        if (icon != null) {
            ImageView imageView = (ImageView) dialogView.findViewById(R.id.parental_controls_icon);
            imageView.setImageDrawable(icon);
        }

        TextView parentalControlsTitle =
                (TextView) dialogView.findViewById(R.id.parental_controls_title);
        parentalControlsTitle.setText(mSecurityController.getLabel(info));

        return dialogView;
    }

    protected void configSubtitleVisibility(boolean showDeviceManagement, boolean showCaCerts,
            boolean showNetworkLogging, boolean showVpn, View dialogView) {
        // Device Management title should always been shown
        // When there is a Device Management message, all subtitles should be shown
        if (showDeviceManagement) {
            return;
        }
        // Hide the subtitle if there is only 1 message shown
        int mSectionCountExcludingDeviceMgt = 0;
        if (showCaCerts) { mSectionCountExcludingDeviceMgt++; }
        if (showNetworkLogging) { mSectionCountExcludingDeviceMgt++; }
        if (showVpn) { mSectionCountExcludingDeviceMgt++; }

        // No work needed if there is no sections or more than 1 section
        if (mSectionCountExcludingDeviceMgt != 1) {
            return;
        }
        if (showCaCerts) {
            dialogView.findViewById(R.id.ca_certs_subtitle).setVisibility(View.GONE);
        }
        if (showNetworkLogging) {
            dialogView.findViewById(R.id.network_logging_subtitle).setVisibility(View.GONE);
        }
        if (showVpn) {
            dialogView.findViewById(R.id.vpn_subtitle).setVisibility(View.GONE);
        }
    }

    @VisibleForTesting
    String getSettingsButton() {
        return mContext.getString(R.string.monitoring_button_view_policies);
    }

    private String getPositiveButton() {
        return mContext.getString(R.string.ok);
    }

    private String getNegativeButton() {
        if (mSecurityController.isParentalControlsEnabled()) {
            return mContext.getString(R.string.monitoring_button_view_controls);
        }
        return null;
    }

    protected CharSequence getManagementMessage(boolean isDeviceManaged,
            CharSequence organizationName) {
        if (!isDeviceManaged) {
            return null;
        }
        if (organizationName != null) {
            if (isFinancedDevice()) {
                return mContext.getString(R.string.monitoring_financed_description_named_management,
                        organizationName, organizationName);
            } else {
                return mContext.getString(
                        R.string.monitoring_description_named_management, organizationName);
            }
        }
        return mContext.getString(R.string.monitoring_description_management);
    }

    protected CharSequence getCaCertsMessage(boolean isDeviceManaged, boolean hasCACerts,
            boolean hasCACertsInWorkProfile) {
        if (!(hasCACerts || hasCACertsInWorkProfile)) return null;
        if (isDeviceManaged) {
            return mContext.getString(R.string.monitoring_description_management_ca_certificate);
        }
        if (hasCACertsInWorkProfile) {
            return mContext.getString(
                    R.string.monitoring_description_managed_profile_ca_certificate);
        }
        return mContext.getString(R.string.monitoring_description_ca_certificate);
    }

    protected CharSequence getNetworkLoggingMessage(boolean isDeviceManaged,
            boolean isNetworkLoggingEnabled) {
        if (!isNetworkLoggingEnabled) return null;
        if (isDeviceManaged) {
            return mContext.getString(R.string.monitoring_description_management_network_logging);
        } else {
            return mContext.getString(
                    R.string.monitoring_description_managed_profile_network_logging);
        }
    }

    protected CharSequence getVpnMessage(boolean isDeviceManaged, boolean hasWorkProfile,
            String vpnName, String vpnNameWorkProfile) {
        if (vpnName == null && vpnNameWorkProfile == null) return null;
        final SpannableStringBuilder message = new SpannableStringBuilder();
        if (isDeviceManaged) {
            if (vpnName != null && vpnNameWorkProfile != null) {
                message.append(mContext.getString(R.string.monitoring_description_two_named_vpns,
                        vpnName, vpnNameWorkProfile));
            } else {
                message.append(mContext.getString(R.string.monitoring_description_named_vpn,
                        vpnName != null ? vpnName : vpnNameWorkProfile));
            }
        } else {
            if (vpnName != null && vpnNameWorkProfile != null) {
                message.append(mContext.getString(R.string.monitoring_description_two_named_vpns,
                        vpnName, vpnNameWorkProfile));
            } else if (vpnNameWorkProfile != null) {
                message.append(mContext.getString(
                        R.string.monitoring_description_managed_profile_named_vpn,
                        vpnNameWorkProfile));
            } else if (hasWorkProfile) {
                message.append(mContext.getString(
                        R.string.monitoring_description_personal_profile_named_vpn, vpnName));
            } else {
                message.append(mContext.getString(R.string.monitoring_description_named_vpn,
                        vpnName));
            }
        }
        message.append(mContext.getString(R.string.monitoring_description_vpn_settings_separator));
        message.append(mContext.getString(R.string.monitoring_description_vpn_settings),
                new VpnSpan(), 0);
        return message;
    }

    @VisibleForTesting
    CharSequence getManagementTitle(CharSequence deviceOwnerOrganization) {
        if (deviceOwnerOrganization != null && isFinancedDevice()) {
            return mContext.getString(R.string.monitoring_title_financed_device,
                    deviceOwnerOrganization);
        } else {
            return mContext.getString(R.string.monitoring_title_device_owned);
        }
    }

    private boolean isFinancedDevice() {
        return mSecurityController.isDeviceManaged()
                && mSecurityController.getDeviceOwnerType(
                        mSecurityController.getDeviceOwnerComponentOnAnyUser())
                == DEVICE_OWNER_TYPE_FINANCED;
    }

    private final Runnable mUpdatePrimaryIcon = new Runnable() {
        @Override
        public void run() {
            if (mPrimaryFooterIconDrawable != null) {
                mPrimaryFooterIcon.setImageDrawable(mPrimaryFooterIconDrawable);
            } else {
                mPrimaryFooterIcon.setImageResource(mFooterIconId);
            }
        }
    };

    private final Runnable mUpdateDisplayState = new Runnable() {
        @Override
        public void run() {
            if (mFooterTextContent != null) {
                mFooterText.setText(mFooterTextContent);
            }
            mRootView.setVisibility(mIsVisible || DEBUG_FORCE_VISIBLE ? View.VISIBLE : View.GONE);
        }
    };

    private class Callback implements SecurityController.SecurityControllerCallback {
        @Override
        public void onStateChanged() {
            refreshState();
        }
    }

    private class H extends Handler {
        private static final int CLICK = 0;
        private static final int REFRESH_STATE = 1;

        private H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            String name = null;
            try {
                if (msg.what == REFRESH_STATE) {
                    name = "handleRefreshState";
                    handleRefreshState();
                } else if (msg.what == CLICK) {
                    name = "handleClick";
                    handleClick();
                }
            } catch (Throwable t) {
                final String error = "Error in " + name;
                Log.w(TAG, error, t);
                mHost.warn(error, t);
            }
        }
    }

    protected class VpnSpan extends ClickableSpan {
        @Override
        public void onClick(View widget) {
            final Intent intent = new Intent(Settings.ACTION_VPN_SETTINGS);
            mDialog.dismiss();
            // This dismisses the shade on opening the activity
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        }

        // for testing, to compare two CharSequences containing VpnSpans
        @Override
        public boolean equals(Object object) {
            return object instanceof VpnSpan;
        }

        @Override
        public int hashCode() {
            return 314159257; // prime
        }
    }
}

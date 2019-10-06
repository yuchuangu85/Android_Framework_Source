package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyManager.CODE_DEVICE_ADMIN_NOT_SUPPORTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.admin.DevicePolicyManager;
import android.app.admin.NetworkEvent;
import android.app.admin.PasswordMetrics;
import android.app.admin.StartInstallingUpdateCallback;
import android.app.admin.SystemUpdateInfo;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.StringParceledListSlice;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keystore.ParcelableKeyGenParameterSpec;
import android.telephony.data.ApnSetting;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A thin wrapper around {@link DevicePolicyManagerService} for granual enabling and disabling of
 * management functionality on Wear.
 */
public class ClockworkDevicePolicyManagerWrapperService extends BaseIDevicePolicyManager {

    private static final String NOT_SUPPORTED_MESSAGE = "The operation is not supported on Wear.";

    private DevicePolicyManagerService mDpmsDelegate;

    /**
     * If true, throw {@link UnsupportedOperationException} when unsupported setter methods are
     * called. Otherwise make the unsupported methods no-op.
     *
     * It should be normally set to false. Enable throwing of the exception when needed for debug
     * purposes.
     */
    private final boolean mThrowUnsupportedException;

    public ClockworkDevicePolicyManagerWrapperService(Context context) {
        this(context, false);
    }

    public ClockworkDevicePolicyManagerWrapperService(
            Context context, boolean throwUnsupportedException) {
        mDpmsDelegate = new DevicePolicyManagerService(new ClockworkInjector(context));

        if (Build.TYPE.equals("userdebug") || Build.TYPE.equals("eng")) {
            mThrowUnsupportedException = true;
        } else {
            mThrowUnsupportedException = throwUnsupportedException;
        }
    }

    static class ClockworkInjector extends DevicePolicyManagerService.Injector {

        ClockworkInjector(Context context) {
            super(context);
        }

        @Override
        public boolean hasFeature() {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        }
    }

    @Override
    void systemReady(int phase) {
        mDpmsDelegate.systemReady(phase);
    }

    @Override
    void handleStartUser(int userId) {
        mDpmsDelegate.handleStartUser(userId);
    }

    @Override
    void handleUnlockUser(int userId) {
        mDpmsDelegate.handleUnlockUser(userId);
    }

    @Override
    void handleStopUser(int userId) {
        mDpmsDelegate.handleStopUser(userId);
    }

    @Override
    public void setPasswordQuality(ComponentName who, int quality, boolean parent) {
        mDpmsDelegate.setPasswordQuality(who, quality, parent);
    }

    @Override
    public int getPasswordQuality(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordQuality(who, userHandle, parent);
    }

    @Override
    public void setPasswordMinimumLength(ComponentName who, int length, boolean parent) {
        mDpmsDelegate.setPasswordMinimumLength(who, length, parent);
    }

    @Override
    public int getPasswordMinimumLength(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordMinimumLength(who, userHandle, parent);
    }

    @Override
    public void setPasswordMinimumUpperCase(ComponentName who, int length, boolean parent) {
        mDpmsDelegate.setPasswordMinimumUpperCase(who, length, parent);
    }

    @Override
    public int getPasswordMinimumUpperCase(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordMinimumUpperCase(who, userHandle, parent);
    }

    @Override
    public void setPasswordMinimumLowerCase(ComponentName who, int length, boolean parent) {
        mDpmsDelegate.setPasswordMinimumLowerCase(who, length, parent);
    }

    @Override
    public int getPasswordMinimumLowerCase(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordMinimumLowerCase(who, userHandle, parent);
    }

    @Override
    public void setPasswordMinimumLetters(ComponentName who, int length, boolean parent) {
        mDpmsDelegate.setPasswordMinimumLetters(who, length, parent);
    }

    @Override
    public int getPasswordMinimumLetters(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordMinimumLetters(who, userHandle, parent);
    }

    @Override
    public void setPasswordMinimumNumeric(ComponentName who, int length, boolean parent) {
        mDpmsDelegate.setPasswordMinimumNumeric(who, length, parent);
    }

    @Override
    public int getPasswordMinimumNumeric(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordMinimumNumeric(who, userHandle, parent);
    }

    @Override
    public void setPasswordMinimumSymbols(ComponentName who, int length, boolean parent) {
        mDpmsDelegate.setPasswordMinimumSymbols(who, length, parent);
    }

    @Override
    public int getPasswordMinimumSymbols(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordMinimumSymbols(who, userHandle, parent);
    }

    @Override
    public void setPasswordMinimumNonLetter(ComponentName who, int length, boolean parent) {
        mDpmsDelegate.setPasswordMinimumNonLetter(who, length, parent);
    }

    @Override
    public int getPasswordMinimumNonLetter(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordMinimumNonLetter(who, userHandle, parent);
    }

    @Override
    public void setPasswordHistoryLength(ComponentName who, int length, boolean parent) {
        mDpmsDelegate.setPasswordHistoryLength(who, length, parent);
    }

    @Override
    public int getPasswordHistoryLength(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordHistoryLength(who, userHandle, parent);
    }

    @Override
    public void setPasswordExpirationTimeout(ComponentName who, long timeout, boolean parent) {
        mDpmsDelegate.setPasswordExpirationTimeout(who, timeout, parent);
    }

    @Override
    public long getPasswordExpirationTimeout(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordExpirationTimeout(who, userHandle, parent);
    }

    @Override
    public long getPasswordExpiration(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getPasswordExpiration(who, userHandle, parent);
    }

    @Override
    public boolean isActivePasswordSufficient(int userHandle, boolean parent) {
        return mDpmsDelegate.isActivePasswordSufficient(userHandle, parent);
    }

    @Override
    public boolean isUsingUnifiedPassword(ComponentName who) {
        return true;
    }

    @Override
    public boolean isProfileActivePasswordSufficientForParent(int userHandle) {
        return false;
    }

    @Override
    public int getPasswordComplexity() {
        return mDpmsDelegate.getPasswordComplexity();
    }

    @Override
    public int getCurrentFailedPasswordAttempts(int userHandle, boolean parent) {
        return mDpmsDelegate.getCurrentFailedPasswordAttempts(userHandle, parent);
    }

    @Override
    public int getProfileWithMinimumFailedPasswordsForWipe(int userHandle, boolean parent) {
        return UserHandle.USER_NULL;
    }

    @Override
    public void setMaximumFailedPasswordsForWipe(ComponentName who, int num, boolean parent) {
        mDpmsDelegate.setMaximumFailedPasswordsForWipe(who, num, parent);
    }

    @Override
    public int getMaximumFailedPasswordsForWipe(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getMaximumFailedPasswordsForWipe(who, userHandle, parent);
    }

    @Override
    public boolean resetPassword(String passwordOrNull, int flags) throws RemoteException {
        return mDpmsDelegate.resetPassword(passwordOrNull, flags);
    }

    @Override
    public void setMaximumTimeToLock(ComponentName who, long timeMs, boolean parent) {
        mDpmsDelegate.setMaximumTimeToLock(who, timeMs, parent);
    }

    @Override
    public long getMaximumTimeToLock(ComponentName who, int userHandle, boolean parent) {
        return mDpmsDelegate.getMaximumTimeToLock(who, userHandle, parent);
    }

    @Override
    public void setRequiredStrongAuthTimeout(ComponentName who, long timeoutMs, boolean parent) {
        mDpmsDelegate.setRequiredStrongAuthTimeout(who, timeoutMs, parent);
    }

    @Override
    public long getRequiredStrongAuthTimeout(ComponentName who, int userId, boolean parent) {
        return mDpmsDelegate.getRequiredStrongAuthTimeout(who, userId, parent);
    }

    @Override
    public void lockNow(int flags, boolean parent) {
        mDpmsDelegate.lockNow(flags, parent);
    }

    @Override
    public ComponentName setGlobalProxy(ComponentName who, String proxySpec, String exclusionList) {
        maybeThrowUnsupportedOperationException();
        return null;
    }

    @Override
    public ComponentName getGlobalProxyAdmin(int userHandle) {
        maybeThrowUnsupportedOperationException();
        return null;
    }

    @Override
    public void setRecommendedGlobalProxy(ComponentName who, ProxyInfo proxyInfo) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public int setStorageEncryption(ComponentName who, boolean encrypt) {
        maybeThrowUnsupportedOperationException();
        return DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED;
    }

    @Override
    public boolean getStorageEncryption(ComponentName who, int userHandle) {
        return false;
    }

    @Override
    public int getStorageEncryptionStatus(String callerPackage, int userHandle) {
        // Ok to return current status even though setting encryption is not supported in Wear.
        return mDpmsDelegate.getStorageEncryptionStatus(callerPackage, userHandle);
    }

    @Override
    public boolean requestBugreport(ComponentName who) {
        return mDpmsDelegate.requestBugreport(who);
    }

    @Override
    public void setCameraDisabled(ComponentName who, boolean disabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean getCameraDisabled(ComponentName who, int userHandle) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public void setScreenCaptureDisabled(ComponentName who, boolean disabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean getScreenCaptureDisabled(ComponentName who, int userHandle) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public void setKeyguardDisabledFeatures(ComponentName who, int which, boolean parent) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public int getKeyguardDisabledFeatures(ComponentName who, int userHandle, boolean parent) {
        return 0;
    }

    @Override
    public void setActiveAdmin(ComponentName adminReceiver, boolean refreshing, int userHandle) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean isAdminActive(ComponentName adminReceiver, int userHandle) {
        return false;
    }

    @Override
    public List<ComponentName> getActiveAdmins(int userHandle) {
        return null;
    }

    @Override
    public boolean packageHasActiveAdmins(String packageName, int userHandle) {
        return false;
    }

    @Override
    public void getRemoveWarning(ComponentName comp, RemoteCallback result, int userHandle) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void removeActiveAdmin(ComponentName adminReceiver, int userHandle) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void forceRemoveActiveAdmin(ComponentName adminReceiver, int userHandle) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean hasGrantedPolicy(ComponentName adminReceiver, int policyId, int userHandle) {
        return false;
    }

    @Override
    public void setActivePasswordState(PasswordMetrics metrics, int userHandle) {
        mDpmsDelegate.setActivePasswordState(metrics, userHandle);
    }

    @Override
    public void reportPasswordChanged(@UserIdInt int userId) {
        mDpmsDelegate.reportPasswordChanged(userId);
    }

    @Override
    public void reportFailedPasswordAttempt(int userHandle) {
        mDpmsDelegate.reportFailedPasswordAttempt(userHandle);
    }

    @Override
    public void reportSuccessfulPasswordAttempt(int userHandle) {
        mDpmsDelegate.reportSuccessfulPasswordAttempt(userHandle);
    }

    @Override
    public void reportFailedBiometricAttempt(int userHandle) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void reportSuccessfulBiometricAttempt(int userHandle) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void reportKeyguardDismissed(int userHandle) {
        mDpmsDelegate.reportKeyguardDismissed(userHandle);
    }

    @Override
    public void reportKeyguardSecured(int userHandle) {
        mDpmsDelegate.reportKeyguardSecured(userHandle);
    }

    @Override
    public boolean setDeviceOwner(ComponentName admin, String ownerName, int userId) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean hasDeviceOwner() {
        return false;
    }

    @Override
    public ComponentName getDeviceOwnerComponent(boolean callingUserOnly) {
        return null;
    }

    @Override
    public String getDeviceOwnerName() {
        return null;
    }

    @Override
    public void clearDeviceOwner(String packageName) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public int getDeviceOwnerUserId() {
        return UserHandle.USER_NULL;
    }

    @Override
    public boolean setProfileOwner(ComponentName who, String ownerName, int userHandle) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public ComponentName getProfileOwner(int userHandle) {
        return null;
    }

    @Override
    public ComponentName getProfileOwnerAsUser(int userHandle) {
        return null;
    }

    @Override
    public String getProfileOwnerName(int userHandle) {
        return null;
    }

    @Override
    public void setProfileEnabled(ComponentName who) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setProfileName(ComponentName who, String profileName) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void clearProfileOwner(ComponentName who) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean checkDeviceIdentifierAccess(String packageName, int pid, int uid) {
        return mDpmsDelegate.checkDeviceIdentifierAccess(packageName, pid, uid);
    }

    @Override
    public void grantDeviceIdsAccessToProfileOwner(ComponentName who, int userId) {
        mDpmsDelegate.grantDeviceIdsAccessToProfileOwner(who, userId);
    }

    @Override
    public boolean hasUserSetupCompleted() {
        return mDpmsDelegate.hasUserSetupCompleted();
    }

    @Override
    public void setDeviceOwnerLockScreenInfo(ComponentName who, CharSequence info) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public CharSequence getDeviceOwnerLockScreenInfo() {
        return null;
    }

    @Override
    public String[] setPackagesSuspended(
            ComponentName who, String callerPackage, String[] packageNames, boolean suspended) {
        maybeThrowUnsupportedOperationException();
        return packageNames;
    }

    @Override
    public boolean isPackageSuspended(ComponentName who, String callerPackage, String packageName) {
        return false;
    }

    @Override
    public boolean installCaCert(ComponentName admin, String callerPackage, byte[] certBuffer)
            throws RemoteException {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public void uninstallCaCerts(ComponentName admin, String callerPackage, String[] aliases) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void enforceCanManageCaCerts(ComponentName who, String callerPackage) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean approveCaCert(String alias, int userId, boolean appproval) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean isCaCertApproved(String alias, int userId) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean installKeyPair(ComponentName who, String callerPackage, byte[] privKey,
            byte[] cert, byte[] chain, String alias, boolean requestAccess,
            boolean isUserSelectable) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean removeKeyPair(ComponentName who, String callerPackage, String alias) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean generateKeyPair(ComponentName who, String callerPackage, String algorithm,
            ParcelableKeyGenParameterSpec keySpec, int idAttestationFlags,
            KeymasterCertificateChain attestationChain) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean setKeyPairCertificate(ComponentName who, String callerPackage, String alias,
            byte[] cert, byte[] chain, boolean isUserSelectable) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public void choosePrivateKeyAlias(int uid, Uri uri, String alias, IBinder response) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setDelegatedScopes(ComponentName who, String delegatePackage,
            List<String> scopes) throws SecurityException {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    @NonNull
    public List<String> getDelegatedScopes(ComponentName who, String delegatePackage)
            throws SecurityException {
        return Collections.EMPTY_LIST;
    }

    @NonNull
    public List<String> getDelegatePackages(ComponentName who, String scope)
            throws SecurityException {
        return Collections.EMPTY_LIST;
    }

    @Override
    public void setCertInstallerPackage(ComponentName who, String installerPackage) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public String getCertInstallerPackage(ComponentName who) {
        return null;
    }

    @Override
    public boolean setAlwaysOnVpnPackage(ComponentName admin, String vpnPackage, boolean lockdown,
            List<String> lockdownWhitelist) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public String getAlwaysOnVpnPackage(ComponentName admin) {
        return null;
    }

    @Override
    public boolean isAlwaysOnVpnLockdownEnabled(ComponentName who) {
        return false;
    }

    @Override
    public List<String> getAlwaysOnVpnLockdownWhitelist(ComponentName who) {
        return null;
    }

    @Override
    public void wipeDataWithReason(int flags, String wipeReasonForUser) {
        mDpmsDelegate.wipeDataWithReason(flags, wipeReasonForUser);
    }


    @Override
    public void addPersistentPreferredActivity(
            ComponentName who, IntentFilter filter, ComponentName activity) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void clearPackagePersistentPreferredActivities(ComponentName who, String packageName) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setDefaultSmsApplication(ComponentName admin, String packageName) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setApplicationRestrictions(ComponentName who, String callerPackage,
            String packageName, Bundle settings) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public Bundle getApplicationRestrictions(ComponentName who, String callerPackage,
            String packageName) {
        return null;
    }

    @Override
    public boolean setApplicationRestrictionsManagingPackage(
            ComponentName admin, String packageName) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public String getApplicationRestrictionsManagingPackage(ComponentName admin) {
        return null;
    }

    @Override
    public boolean isCallerApplicationRestrictionsManagingPackage(String callerPackage) {
        return false;
    }

    @Override
    public void setRestrictionsProvider(ComponentName who, ComponentName permissionProvider) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public ComponentName getRestrictionsProvider(int userHandle) {
        return null;
    }

    @Override
    public void setUserRestriction(ComponentName who, String key, boolean enabledFromThisOwner) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public Bundle getUserRestrictions(ComponentName who) {
        return null;
    }

    @Override
    public void addCrossProfileIntentFilter(ComponentName who, IntentFilter filter, int flags) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void clearCrossProfileIntentFilters(ComponentName who) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean setPermittedCrossProfileNotificationListeners(
            ComponentName who, List<String> packageList) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public List<String> getPermittedCrossProfileNotificationListeners(ComponentName who) {
        return null;
    }

    @Override
    public boolean isNotificationListenerServicePermitted(String packageName, int userId) {
        return true;
    }

    @Override
    public void setCrossProfileCallerIdDisabled(ComponentName who, boolean disabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean getCrossProfileCallerIdDisabled(ComponentName who) {
        return false;
    }

    @Override
    public boolean getCrossProfileCallerIdDisabledForUser(int userId) {
        return false;
    }

    @Override
    public void setCrossProfileContactsSearchDisabled(ComponentName who, boolean disabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean getCrossProfileContactsSearchDisabled(ComponentName who) {
        return false;
    }

    @Override
    public boolean getCrossProfileContactsSearchDisabledForUser(int userId) {
        return false;
    }

    @Override
    public boolean addCrossProfileWidgetProvider(ComponentName admin, String packageName) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean removeCrossProfileWidgetProvider(ComponentName admin, String packageName) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public List<String> getCrossProfileWidgetProviders(ComponentName admin) {
        return null;
    }

    @Override
    public void setCrossProfileCalendarPackages(ComponentName admin, List<String> packageNames) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public List<String> getCrossProfileCalendarPackages(ComponentName admin) {
        return Collections.emptyList();
    }

    @Override
    public boolean isPackageAllowedToAccessCalendarForUser(String packageName,
            int userHandle) {
        return false;
    }

    @Override
    public List<String> getCrossProfileCalendarPackagesForUser(int userHandle) {
        return Collections.emptyList();
    }

    @Override
    public boolean startViewCalendarEventInManagedProfile(String packageName, long eventId,
            long start, long end, boolean allDay, int flags) {
        return false;
    }

    @Override
    public boolean setPermittedAccessibilityServices(ComponentName who, List packageList) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public List getPermittedAccessibilityServices(ComponentName who) {
        return null;
    }

    @Override
    public List getPermittedAccessibilityServicesForUser(int userId) {
        return null;
    }

    @Override
    public boolean isAccessibilityServicePermittedByAdmin(
            ComponentName who, String packageName, int userHandle) {
        return true;
    }

    @Override
    public boolean setPermittedInputMethods(ComponentName who, List packageList) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public List getPermittedInputMethods(ComponentName who) {
        return null;
    }

    @Override
    public List getPermittedInputMethodsForCurrentUser() {
        return null;
    }

    @Override
    public boolean isInputMethodPermittedByAdmin(
            ComponentName who, String packageName, int userHandle) {
        return true;
    }

    @Override
    public boolean setApplicationHidden(ComponentName who, String callerPackage, String packageName,
            boolean hidden) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean isApplicationHidden(ComponentName who, String callerPackage,
            String packageName) {
        return false;
    }

    @Override
    public UserHandle createAndManageUser(ComponentName admin, String name,
            ComponentName profileOwner, PersistableBundle adminExtras, int flags) {
        maybeThrowUnsupportedOperationException();
        return null;
    }

    @Override
    public boolean removeUser(ComponentName who, UserHandle userHandle) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean switchUser(ComponentName who, UserHandle userHandle) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public int startUserInBackground(ComponentName who, UserHandle userHandle) {
        maybeThrowUnsupportedOperationException();
        return UserManager.USER_OPERATION_ERROR_UNKNOWN;
    }

    @Override
    public int stopUser(ComponentName who, UserHandle userHandle) {
        maybeThrowUnsupportedOperationException();
        return UserManager.USER_OPERATION_ERROR_UNKNOWN;
    }

    @Override
    public int logoutUser(ComponentName who) {
        maybeThrowUnsupportedOperationException();
        return UserManager.USER_OPERATION_ERROR_UNKNOWN;
    }

    @Override
    public List<UserHandle> getSecondaryUsers(ComponentName who) {
        return Collections.emptyList();
    }

    @Override
    public boolean isEphemeralUser(ComponentName who) {
        return false;
    }

    @Override
    public void enableSystemApp(ComponentName who, String callerPackage, String packageName) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public int enableSystemAppWithIntent(ComponentName who, String callerPackage, Intent intent) {
        maybeThrowUnsupportedOperationException();
        return 0;
    }

    @Override
    public boolean installExistingPackage(ComponentName who, String callerPackage,
            String packageName) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public void setAccountManagementDisabled(
            ComponentName who, String accountType, boolean disabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public String[] getAccountTypesWithManagementDisabled() {
        return null;
    }

    @Override
    public String[] getAccountTypesWithManagementDisabledAsUser(int userId) {
        return null;
    }

    @Override
    public void setLockTaskPackages(ComponentName who, String[] packages) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public String[] getLockTaskPackages(ComponentName who) {
        return new String[0];
    }

    @Override
    public boolean isLockTaskPermitted(String pkg) {
        return false;
    }

    public void setLockTaskFeatures(ComponentName admin, int flags) {
        maybeThrowUnsupportedOperationException();
    }

    public int getLockTaskFeatures(ComponentName admin) {
        return 0;
    }

    @Override
    public void setGlobalSetting(ComponentName who, String setting, String value) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setSystemSetting(ComponentName who, String setting, String value) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean setTime(ComponentName who, long millis) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean setTimeZone(ComponentName who, String timeZone) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public void setSecureSetting(ComponentName who, String setting, String value) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setMasterVolumeMuted(ComponentName who, boolean on) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean isMasterVolumeMuted(ComponentName who) {
        return false;
    }

    @Override
    public void notifyLockTaskModeChanged(boolean isEnabled, String pkg, int userHandle) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setUninstallBlocked(ComponentName who, String callerPackage, String packageName,
            boolean uninstallBlocked) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean isUninstallBlocked(ComponentName who, String packageName) {
        return false;
    }

    @Override
    public void startManagedQuickContact(String actualLookupKey, long actualContactId,
            boolean isContactIdIgnored, long actualDirectoryId, Intent originalIntent) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setBluetoothContactSharingDisabled(ComponentName who, boolean disabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean getBluetoothContactSharingDisabled(ComponentName who) {
        return false;
    }

    @Override
    public boolean getBluetoothContactSharingDisabledForUser(int userId) {
        return false;
    }

    @Override
    public void setTrustAgentConfiguration(ComponentName admin, ComponentName agent,
            PersistableBundle args, boolean parent) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public List<PersistableBundle> getTrustAgentConfiguration(ComponentName admin,
            ComponentName agent, int userHandle, boolean parent) {
        return new ArrayList<PersistableBundle>();
    }

    @Override
    public void setAutoTimeRequired(ComponentName who, boolean required) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean getAutoTimeRequired() {
        return false;
    }

    @Override
    public void setForceEphemeralUsers(ComponentName who, boolean forceEphemeralUsers) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean getForceEphemeralUsers(ComponentName who) {
        return false;
    }

    @Override
    public boolean isRemovingAdmin(ComponentName adminReceiver, int userHandle) {
        return false;
    }

    @Override
    public void setUserIcon(ComponentName who, Bitmap icon) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public Intent createAdminSupportIntent(String restriction) {
        return null;
    }

    @Override
    public void setSystemUpdatePolicy(ComponentName who, SystemUpdatePolicy policy) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public SystemUpdatePolicy getSystemUpdatePolicy() {
        return null;
    }

    @Override
    public void clearSystemUpdatePolicyFreezePeriodRecord() {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void installUpdateFromFile(ComponentName admin,
            ParcelFileDescriptor updateFileDescriptor, StartInstallingUpdateCallback listener) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean setKeyguardDisabled(ComponentName who, boolean disabled) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean setStatusBarDisabled(ComponentName who, boolean disabled) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean getDoNotAskCredentialsOnBoot() {
        return false;
    }

    @Override
    public void notifyPendingSystemUpdate(@Nullable SystemUpdateInfo info) {
        // We expect this to be called when an OTA is available; do not throw an Exception.
    }

    @Override
    public SystemUpdateInfo getPendingSystemUpdate(ComponentName admin) {
        maybeThrowUnsupportedOperationException();
        return null;
    }

    @Override
    public void setPermissionPolicy(ComponentName admin, String callerPackage, int policy)
            throws RemoteException {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public int getPermissionPolicy(ComponentName admin) throws RemoteException {
        return mDpmsDelegate.getPermissionPolicy(admin);
    }

    @Override
    public void setPermissionGrantState(ComponentName admin, String callerPackage,
            String packageName, String permission, int grantState, RemoteCallback cb)
                throws RemoteException {
        maybeThrowUnsupportedOperationException();
        return;
    }

    @Override
    public int getPermissionGrantState(ComponentName admin, String callerPackage,
            String packageName, String permission) throws RemoteException {
        return mDpmsDelegate.getPermissionGrantState(admin, callerPackage, packageName, permission);
    }

    @Override
    public boolean isProvisioningAllowed(String action, String packageName) {
        return false;
    }

    @Override
    public int checkProvisioningPreCondition(String action, String packageName) {
        return CODE_DEVICE_ADMIN_NOT_SUPPORTED;
    }

    @Override
    public void setKeepUninstalledPackages(
            ComponentName who, String callerPackage, List<String> packageList) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public List<String> getKeepUninstalledPackages(ComponentName who, String callerPackage) {
        return null;
    }

    @Override
    public boolean isManagedProfile(ComponentName admin) {
        return mDpmsDelegate.isManagedProfile(admin);
    }

    @Override
    public boolean isSystemOnlyUser(ComponentName admin) {
        return mDpmsDelegate.isSystemOnlyUser(admin);
    }

    @Override
    public String getWifiMacAddress(ComponentName admin) {
        return mDpmsDelegate.getWifiMacAddress(admin);
    }

    @Override
    public void reboot(ComponentName admin) {
        mDpmsDelegate.reboot(admin);
    }

    @Override
    public void setShortSupportMessage(ComponentName who, CharSequence message) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public CharSequence getShortSupportMessage(ComponentName who) {
        return null;
    }

    @Override
    public void setLongSupportMessage(ComponentName who, CharSequence message) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public CharSequence getLongSupportMessage(ComponentName who) {
        return null;
    }

    @Override
    public CharSequence getShortSupportMessageForUser(ComponentName who, int userHandle) {
        return null;
    }

    @Override
    public CharSequence getLongSupportMessageForUser(ComponentName who, int userHandle) {
        return null;
    }

    @Override
    public boolean isSeparateProfileChallengeAllowed(int userHandle) {
        return false;
    }

    @Override
    public void setOrganizationColor(ComponentName who, int color) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setOrganizationColorForUser(int color, int userId) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public int getOrganizationColor(ComponentName who) {
        return Color.parseColor("#00796B");
    }

    @Override
    public int getOrganizationColorForUser(int userHandle) {
        return Color.parseColor("#00796B");
    }

    @Override
    public void setOrganizationName(ComponentName who, CharSequence text) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public CharSequence getOrganizationName(ComponentName who) {
        return null;
    }

    @Override
    public CharSequence getDeviceOwnerOrganizationName() {
        return null;
    }

    @Override
    public CharSequence getOrganizationNameForUser(int userHandle) {
        return null;
    }

    @Override
    public List<String> setMeteredDataDisabledPackages(ComponentName admin, List<String> packageNames) {
        maybeThrowUnsupportedOperationException();
        return packageNames;
    }

    @Override
    public List<String> getMeteredDataDisabledPackages(ComponentName admin) {
        return new ArrayList<>();
    }

    @Override
    public boolean isMeteredDataDisabledPackageForUser(
            ComponentName admin, String packageName, int userId) {
        return false;
    }

    @Override
    public int getUserProvisioningState() {
        return DevicePolicyManager.STATE_USER_UNMANAGED;
    }

    @Override
    public void setUserProvisioningState(int newState, int userHandle) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setAffiliationIds(ComponentName admin, List<String> ids) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public List<String> getAffiliationIds(ComponentName admin) {
        return Collections.emptyList();
    }

    @Override
    public boolean isAffiliatedUser() {
        return false;
    }

    @Override
    public void setSecurityLoggingEnabled(ComponentName admin, boolean enabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean isSecurityLoggingEnabled(ComponentName admin) {
        return false;
    }

    @Override
    public ParceledListSlice retrieveSecurityLogs(ComponentName admin) {
        return null;
    }

    @Override
    public ParceledListSlice retrievePreRebootSecurityLogs(ComponentName admin) {
        return null;
    }

    @Override
    public long forceNetworkLogs() {
        maybeThrowUnsupportedOperationException();
        return 0;
    }

    @Override
    public long forceSecurityLogs() {
        maybeThrowUnsupportedOperationException();
        return 0;
    }

    @Override
    public boolean isUninstallInQueue(String packageName) {
        return false;
    }

    @Override
    public void uninstallPackageWithActiveAdmins(String packageName) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean isDeviceProvisioned() {
        return mDpmsDelegate.isDeviceProvisioned();
    }

    @Override
    public boolean isDeviceProvisioningConfigApplied() {
        return false;
    }

    @Override
    public void setDeviceProvisioningConfigApplied() {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void forceUpdateUserSetupComplete() {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setBackupServiceEnabled(ComponentName admin, boolean enabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean isBackupServiceEnabled(ComponentName admin) {
        return false;
    }

    @Override
    public boolean bindDeviceAdminServiceAsUser(
            @NonNull ComponentName admin, @NonNull IApplicationThread caller,
            @Nullable IBinder activtityToken, @NonNull Intent serviceIntent,
            @NonNull IServiceConnection connection, int flags, @UserIdInt int targetUserId) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public @NonNull List<UserHandle> getBindDeviceAdminTargetUsers(@NonNull ComponentName admin) {
        return Collections.emptyList();
    }

    @Override
    public void setNetworkLoggingEnabled(ComponentName admin, String packageName, boolean enabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean isNetworkLoggingEnabled(ComponentName admin, String packageName) {
        return false;
    }

    @Override
    public List<NetworkEvent> retrieveNetworkLogs(ComponentName admin, String packageName, long batchToken) {
        return null;
    }

    @Override
    public long getLastSecurityLogRetrievalTime() {
        return -1;
    }

    @Override
    public long getLastBugReportRequestTime() {
        return -1;
    }

    @Override
    public long getLastNetworkLogRetrievalTime() {
        return -1;
    }

    @Override
    public boolean setResetPasswordToken(ComponentName admin, byte[] token) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean clearResetPasswordToken(ComponentName admin) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean isResetPasswordTokenActive(ComponentName admin) {
        return false;
    }

    @Override
    public boolean resetPasswordWithToken(ComponentName admin, String passwordOrNull, byte[] token,
            int flags) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean isCurrentInputMethodSetByOwner() {
        return false;
    }

    @Override
    public StringParceledListSlice getOwnerInstalledCaCerts(@NonNull UserHandle user) {
        return new StringParceledListSlice(new ArrayList<>(new ArraySet<>()));
    }

    @Override
    public void clearApplicationUserData(ComponentName admin, String packageName,
            IPackageDataObserver callback) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public synchronized void setLogoutEnabled(ComponentName admin, boolean enabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean isLogoutEnabled() {
        return false;
    }

    @Override
    public List<String> getDisallowedSystemApps(ComponentName admin, int userId,
            String provisioningAction) throws RemoteException {
        return null;
    }

    @Override
    public void transferOwnership(ComponentName admin, ComponentName target, PersistableBundle bundle) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public PersistableBundle getTransferOwnershipBundle() {
        return null;
    }

    @Override
    public int addOverrideApn(ComponentName admin, ApnSetting apnSetting) {
        maybeThrowUnsupportedOperationException();
        return -1;
    }

    @Override
    public boolean updateOverrideApn(ComponentName admin, int apnId, ApnSetting apnSetting) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public boolean removeOverrideApn(ComponentName admin, int apnId) {
        maybeThrowUnsupportedOperationException();
        return false;
    }

    @Override
    public List<ApnSetting> getOverrideApns(ComponentName admin) {
        return Collections.emptyList();
    }

    @Override
    public void setOverrideApnsEnabled(ComponentName admin, boolean enabled) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public boolean isOverrideApnEnabled(ComponentName admin) {
        return false;
    }

    @Override
    public void setStartUserSessionMessage(
            ComponentName admin, CharSequence startUserSessionMessage) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public void setEndUserSessionMessage(ComponentName admin, CharSequence endUserSessionMessage) {
        maybeThrowUnsupportedOperationException();
    }

    @Override
    public String getStartUserSessionMessage(ComponentName admin) {
        return null;
    }

    @Override
    public String getEndUserSessionMessage(ComponentName admin) {
        return null;
    }

    @Override
    public int setGlobalPrivateDns(ComponentName who, int mode, String privateDnsHost) {
        return DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING;
    }

    @Override
    public int getGlobalPrivateDnsMode(ComponentName who) {
        return DevicePolicyManager.PRIVATE_DNS_MODE_UNKNOWN;
    }

    @Override
    public String getGlobalPrivateDnsHost(ComponentName who) {
        return null;
    }

    @Override
    public boolean isManagedKiosk() {
        return false;
    }

    @Override
    public boolean isUnattendedManagedKiosk() {
        return false;
    }
    private void maybeThrowUnsupportedOperationException() throws UnsupportedOperationException {
        if (mThrowUnsupportedException) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }
    }
}

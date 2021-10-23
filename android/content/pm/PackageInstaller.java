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

package android.content.pm;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.MODE_IGNORED;

import android.Manifest;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager.DeleteFlags;
import android.content.pm.PackageManager.InstallReason;
import android.content.pm.PackageManager.InstallScenario;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.FileBridge;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.ExceptionUtils;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Offers the ability to install, upgrade, and remove applications on the
 * device. This includes support for apps packaged either as a single
 * "monolithic" APK, or apps packaged as multiple "split" APKs.
 * <p>
 * An app is delivered for installation through a
 * {@link PackageInstaller.Session}, which any app can create. Once the session
 * is created, the installer can stream one or more APKs into place until it
 * decides to either commit or destroy the session. Committing may require user
 * intervention to complete the installation, unless the caller falls into one of the
 * following categories, in which case the installation will complete automatically.
 * <ul>
 * <li>the device owner
 * <li>the affiliated profile owner
 * </ul>
 * <p>
 * Sessions can install brand new apps, upgrade existing apps, or add new splits
 * into an existing app.
 * <p>
 * Apps packaged as multiple split APKs always consist of a single "base" APK
 * (with a {@code null} split name) and zero or more "split" APKs (with unique
 * split names). Any subset of these APKs can be installed together, as long as
 * the following constraints are met:
 * <ul>
 * <li>All APKs must have the exact same package name, version code, and signing
 * certificates.
 * <li>All APKs must have unique split names.
 * <li>All installations must contain a single base APK.
 * </ul>
 * <p>
 * The ApiDemos project contains examples of using this API:
 * <code>ApiDemos/src/com/example/android/apis/content/InstallApk*.java</code>.
 */
public class PackageInstaller {
    private static final String TAG = "PackageInstaller";

    /** {@hide} */
    public static final boolean ENABLE_REVOCABLE_FD =
            SystemProperties.getBoolean("fw.revocable_fd", false);

    /**
     * Activity Action: Show details about a particular install session. This
     * may surface actions such as pause, resume, or cancel.
     * <p>
     * This should always be scoped to the installer package that owns the
     * session. Clients should use {@link SessionInfo#createDetailsIntent()} to
     * build this intent correctly.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     * <p>
     * The session to show details for is defined in {@link #EXTRA_SESSION_ID}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SESSION_DETAILS = "android.content.pm.action.SESSION_DETAILS";

    /**
     * Broadcast Action: Explicit broadcast sent to the last known default launcher when a session
     * for a new install is committed. For managed profile, this is sent to the default launcher
     * of the primary profile.
     * <p>
     * The associated session is defined in {@link #EXTRA_SESSION} and the user for which this
     * session was created in {@link Intent#EXTRA_USER}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SESSION_COMMITTED =
            "android.content.pm.action.SESSION_COMMITTED";

    /**
     * Broadcast Action: Send information about a staged install session when its state is updated.
     * <p>
     * The associated session information is defined in {@link #EXTRA_SESSION}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SESSION_UPDATED =
            "android.content.pm.action.SESSION_UPDATED";

    /** {@hide} */
    public static final String ACTION_CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL";

    /**
     * An integer session ID that an operation is working with.
     *
     * @see Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_SESSION_ID = "android.content.pm.extra.SESSION_ID";

    /**
     * {@link SessionInfo} that an operation is working with.
     *
     * @see Intent#getParcelableExtra(String)
     */
    public static final String EXTRA_SESSION = "android.content.pm.extra.SESSION";

    /**
     * Package name that an operation is working with.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_PACKAGE_NAME = "android.content.pm.extra.PACKAGE_NAME";

    /**
     * Current status of an operation. Will be one of
     * {@link #STATUS_PENDING_USER_ACTION}, {@link #STATUS_SUCCESS},
     * {@link #STATUS_FAILURE}, {@link #STATUS_FAILURE_ABORTED},
     * {@link #STATUS_FAILURE_BLOCKED}, {@link #STATUS_FAILURE_CONFLICT},
     * {@link #STATUS_FAILURE_INCOMPATIBLE}, {@link #STATUS_FAILURE_INVALID}, or
     * {@link #STATUS_FAILURE_STORAGE}.
     * <p>
     * More information about a status may be available through additional
     * extras; see the individual status documentation for details.
     *
     * @see Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_STATUS = "android.content.pm.extra.STATUS";

    /**
     * Detailed string representation of the status, including raw details that
     * are useful for debugging.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_STATUS_MESSAGE = "android.content.pm.extra.STATUS_MESSAGE";

    /**
     * Another package name relevant to a status. This is typically the package
     * responsible for causing an operation failure.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String
            EXTRA_OTHER_PACKAGE_NAME = "android.content.pm.extra.OTHER_PACKAGE_NAME";

    /**
     * Storage path relevant to a status.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_STORAGE_PATH = "android.content.pm.extra.STORAGE_PATH";

    /** {@hide} */
    @Deprecated
    public static final String EXTRA_PACKAGE_NAMES = "android.content.pm.extra.PACKAGE_NAMES";

    /** {@hide} */
    public static final String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";
    /** {@hide} */
    public static final String EXTRA_LEGACY_BUNDLE = "android.content.pm.extra.LEGACY_BUNDLE";
    /** {@hide} */
    public static final String EXTRA_CALLBACK = "android.content.pm.extra.CALLBACK";

    /**
     * Type of DataLoader for this session. Will be one of
     * {@link #DATA_LOADER_TYPE_NONE}, {@link #DATA_LOADER_TYPE_STREAMING},
     * {@link #DATA_LOADER_TYPE_INCREMENTAL}.
     * <p>
     * See the individual types documentation for details.
     *
     * @see Intent#getIntExtra(String, int)
     * {@hide}
     */
    @SystemApi
    public static final String EXTRA_DATA_LOADER_TYPE = "android.content.pm.extra.DATA_LOADER_TYPE";

    /**
     * Streaming installation pending.
     * Caller should make sure DataLoader is able to prepare image and reinitiate the operation.
     *
     * @see #EXTRA_SESSION_ID
     * {@hide}
     */
    public static final int STATUS_PENDING_STREAMING = -2;

    /**
     * User action is currently required to proceed. You can launch the intent
     * activity described by {@link Intent#EXTRA_INTENT} to involve the user and
     * continue.
     * <p>
     * You may choose to immediately launch the intent if the user is actively
     * using your app. Otherwise, you should use a notification to guide the
     * user back into your app before launching.
     *
     * @see Intent#getParcelableExtra(String)
     */
    public static final int STATUS_PENDING_USER_ACTION = -1;

    /**
     * The operation succeeded.
     */
    public static final int STATUS_SUCCESS = 0;

    /**
     * The operation failed in a generic way. The system will always try to
     * provide a more specific failure reason, but in some rare cases this may
     * be delivered.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE = 1;

    /**
     * The operation failed because it was blocked. For example, a device policy
     * may be blocking the operation, a package verifier may have blocked the
     * operation, or the app may be required for core system operation.
     * <p>
     * The result may also contain {@link #EXTRA_OTHER_PACKAGE_NAME} with the
     * specific package blocking the install.
     *
     * @see #EXTRA_STATUS_MESSAGE
     * @see #EXTRA_OTHER_PACKAGE_NAME
     */
    public static final int STATUS_FAILURE_BLOCKED = 2;

    /**
     * The operation failed because it was actively aborted. For example, the
     * user actively declined requested permissions, or the session was
     * abandoned.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_ABORTED = 3;

    /**
     * The operation failed because one or more of the APKs was invalid. For
     * example, they might be malformed, corrupt, incorrectly signed,
     * mismatched, etc.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_INVALID = 4;

    /**
     * The operation failed because it conflicts (or is inconsistent with) with
     * another package already installed on the device. For example, an existing
     * permission, incompatible certificates, etc. The user may be able to
     * uninstall another app to fix the issue.
     * <p>
     * The result may also contain {@link #EXTRA_OTHER_PACKAGE_NAME} with the
     * specific package identified as the cause of the conflict.
     *
     * @see #EXTRA_STATUS_MESSAGE
     * @see #EXTRA_OTHER_PACKAGE_NAME
     */
    public static final int STATUS_FAILURE_CONFLICT = 5;

    /**
     * The operation failed because of storage issues. For example, the device
     * may be running low on space, or external media may be unavailable. The
     * user may be able to help free space or insert different external media.
     * <p>
     * The result may also contain {@link #EXTRA_STORAGE_PATH} with the path to
     * the storage device that caused the failure.
     *
     * @see #EXTRA_STATUS_MESSAGE
     * @see #EXTRA_STORAGE_PATH
     */
    public static final int STATUS_FAILURE_STORAGE = 6;

    /**
     * The operation failed because it is fundamentally incompatible with this
     * device. For example, the app may require a hardware feature that doesn't
     * exist, it may be missing native code for the ABIs supported by the
     * device, or it requires a newer SDK version, etc.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;

    /**
     * Default value, non-streaming installation session.
     *
     * @see #EXTRA_DATA_LOADER_TYPE
     * {@hide}
     */
    @SystemApi
    public static final int DATA_LOADER_TYPE_NONE = DataLoaderType.NONE;

    /**
     * Streaming installation using data loader.
     *
     * @see #EXTRA_DATA_LOADER_TYPE
     * {@hide}
     */
    @SystemApi
    public static final int DATA_LOADER_TYPE_STREAMING = DataLoaderType.STREAMING;

    /**
     * Streaming installation using Incremental FileSystem.
     *
     * @see #EXTRA_DATA_LOADER_TYPE
     * {@hide}
     */
    @SystemApi
    public static final int DATA_LOADER_TYPE_INCREMENTAL = DataLoaderType.INCREMENTAL;

    /**
     * Target location for the file in installation session is /data/app/<packageName>-<id>.
     * This is the intended location for APKs.
     * Requires permission to install packages.
     * {@hide}
     */
    @SystemApi
    public static final int LOCATION_DATA_APP = InstallationFileLocation.DATA_APP;

    /**
     * Target location for the file in installation session is
     * /data/media/<userid>/Android/obb/<packageName>. This is the intended location for OBBs.
     * {@hide}
     */
    @SystemApi
    public static final int LOCATION_MEDIA_OBB = InstallationFileLocation.MEDIA_OBB;

    /**
     * Target location for the file in installation session is
     * /data/media/<userid>/Android/data/<packageName>.
     * This is the intended location for application data.
     * Can only be used by an app itself running under specific user.
     * {@hide}
     */
    @SystemApi
    public static final int LOCATION_MEDIA_DATA = InstallationFileLocation.MEDIA_DATA;

    /** @hide */
    @IntDef(prefix = { "LOCATION_" }, value = {
            LOCATION_DATA_APP,
            LOCATION_MEDIA_OBB,
            LOCATION_MEDIA_DATA})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FileLocation{}

    private final IPackageInstaller mInstaller;
    private final int mUserId;
    private final String mInstallerPackageName;
    private final String mAttributionTag;

    private final ArrayList<SessionCallbackDelegate> mDelegates = new ArrayList<>();

    /** {@hide} */
    public PackageInstaller(IPackageInstaller installer,
            String installerPackageName, String installerAttributionTag, int userId) {
        mInstaller = installer;
        mInstallerPackageName = installerPackageName;
        mAttributionTag = installerAttributionTag;
        mUserId = userId;
    }

    /**
     * Create a new session using the given parameters, returning a unique ID
     * that represents the session. Once created, the session can be opened
     * multiple times across multiple device boots.
     * <p>
     * The system may automatically destroy sessions that have not been
     * finalized (either committed or abandoned) within a reasonable period of
     * time, typically on the order of a day.
     *
     * @throws IOException if parameters were unsatisfiable, such as lack of
     *             disk space or unavailable media.
     * @throws SecurityException when installation services are unavailable,
     *             such as when called from a restricted user.
     * @throws IllegalArgumentException when {@link SessionParams} is invalid.
     * @return positive, non-zero unique ID that represents the created session.
     *         This ID remains consistent across device reboots until the
     *         session is finalized. IDs are not reused during a given boot.
     */
    public int createSession(@NonNull SessionParams params) throws IOException {
        try {
            return mInstaller.createSession(params, mInstallerPackageName, mAttributionTag,
                    mUserId);
        } catch (RuntimeException e) {
            ExceptionUtils.maybeUnwrapIOException(e);
            throw e;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Open an existing session to actively perform work. To succeed, the caller
     * must be the owner of the install session.
     *
     * @throws IOException if parameters were unsatisfiable, such as lack of
     *             disk space or unavailable media.
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public @NonNull Session openSession(int sessionId) throws IOException {
        try {
            try {
                return new Session(mInstaller.openSession(sessionId));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } catch (RuntimeException e) {
            ExceptionUtils.maybeUnwrapIOException(e);
            throw e;
        }
    }

    /**
     * Update the icon representing the app being installed in a specific
     * session. This should be roughly
     * {@link ActivityManager#getLauncherLargeIconSize()} in both dimensions.
     *
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public void updateSessionAppIcon(int sessionId, @Nullable Bitmap appIcon) {
        try {
            mInstaller.updateSessionAppIcon(sessionId, appIcon);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update the label representing the app being installed in a specific
     * session.
     *
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public void updateSessionAppLabel(int sessionId, @Nullable CharSequence appLabel) {
        try {
            final String val = (appLabel != null) ? appLabel.toString() : null;
            mInstaller.updateSessionAppLabel(sessionId, val);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Completely abandon the given session, destroying all staged data and
     * rendering it invalid. Abandoned sessions will be reported to
     * {@link SessionCallback} listeners as failures. This is equivalent to
     * opening the session and calling {@link Session#abandon()}.
     *
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public void abandonSession(int sessionId) {
        try {
            mInstaller.abandonSession(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return details for a specific session. No special permissions are
     * required to retrieve these details.
     *
     * @return details for the requested session, or {@code null} if the session
     *         does not exist.
     */
    public @Nullable SessionInfo getSessionInfo(int sessionId) {
        try {
            return mInstaller.getSessionInfo(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all known install sessions, regardless of the installer.
     */
    public @NonNull List<SessionInfo> getAllSessions() {
        try {
            return mInstaller.getAllSessions(mUserId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all known install sessions owned by the calling app.
     */
    public @NonNull List<SessionInfo> getMySessions() {
        try {
            return mInstaller.getMySessions(mInstallerPackageName, mUserId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all staged install sessions.
     */
    public @NonNull List<SessionInfo> getStagedSessions() {
        try {
            // TODO: limit this to the mUserId?
            return mInstaller.getStagedSessions().getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns first active staged session, or {@code null} if there is none.
     *
     * <p>For more information on what sessions are considered active see
     * {@link SessionInfo#isStagedSessionActive()}.
     *
     * @deprecated Use {@link #getActiveStagedSessions} as there can be more than one active staged
     * session
     */
    @Deprecated
    public @Nullable SessionInfo getActiveStagedSession() {
        List<SessionInfo> activeSessions = getActiveStagedSessions();
        return activeSessions.isEmpty() ? null : activeSessions.get(0);
    }

    /**
     * Returns list of active staged sessions. Returns empty list if there is none.
     *
     * <p>For more information on what sessions are considered active see
     *      * {@link SessionInfo#isStagedSessionActive()}.
     */
    public @NonNull List<SessionInfo> getActiveStagedSessions() {
        final List<SessionInfo> activeStagedSessions = new ArrayList<>();
        final List<SessionInfo> stagedSessions = getStagedSessions();
        for (int i = 0; i < stagedSessions.size(); i++) {
            final SessionInfo sessionInfo = stagedSessions.get(i);
            if (sessionInfo.isStagedSessionActive()) {
                activeStagedSessions.add(sessionInfo);
            }
        }
        return activeStagedSessions;
    }

    /**
     * Uninstall the given package, removing it completely from the device. This
     * method is available to:
     * <ul>
     * <li>the current "installer of record" for the package
     * <li>the device owner
     * <li>the affiliated profile owner
     * </ul>
     *
     * @param packageName The package to uninstall.
     * @param statusReceiver Where to deliver the result.
     *
     * @see android.app.admin.DevicePolicyManager
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    public void uninstall(@NonNull String packageName, @NonNull IntentSender statusReceiver) {
        uninstall(packageName, 0 /*flags*/, statusReceiver);
    }

    /**
     * Uninstall the given package, removing it completely from the device. This
     * method is only available to the current "installer of record" for the
     * package.
     *
     * @param packageName The package to uninstall.
     * @param flags Flags for uninstall.
     * @param statusReceiver Where to deliver the result.
     *
     * @hide
     */
    public void uninstall(@NonNull String packageName, @DeleteFlags int flags,
            @NonNull IntentSender statusReceiver) {
        uninstall(new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                flags, statusReceiver);
    }

    /**
     * Uninstall the given package with a specific version code, removing it
     * completely from the device. If the version code of the package
     * does not match the one passed in the versioned package argument this
     * method is a no-op. Use {@link PackageManager#VERSION_CODE_HIGHEST} to
     * uninstall the latest version of the package.
     * <p>
     * This method is available to:
     * <ul>
     * <li>the current "installer of record" for the package
     * <li>the device owner
     * <li>the affiliated profile owner
     * </ul>
     *
     * @param versionedPackage The versioned package to uninstall.
     * @param statusReceiver Where to deliver the result.
     *
     * @see android.app.admin.DevicePolicyManager
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    public void uninstall(@NonNull VersionedPackage versionedPackage,
            @NonNull IntentSender statusReceiver) {
        uninstall(versionedPackage, 0 /*flags*/, statusReceiver);
    }

    /**
     * Uninstall the given package with a specific version code, removing it
     * completely from the device. This method is only available to the current
     * "installer of record" for the package. If the version code of the package
     * does not match the one passed in the versioned package argument this
     * method is a no-op. Use {@link PackageManager#VERSION_CODE_HIGHEST} to
     * uninstall the latest version of the package.
     *
     * @param versionedPackage The versioned package to uninstall.
     * @param flags Flags for uninstall.
     * @param statusReceiver Where to deliver the result.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    public void uninstall(@NonNull VersionedPackage versionedPackage, @DeleteFlags int flags,
            @NonNull IntentSender statusReceiver) {
        Objects.requireNonNull(versionedPackage, "versionedPackage cannot be null");
        try {
            mInstaller.uninstall(versionedPackage, mInstallerPackageName,
                    flags, statusReceiver, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Install the given package, which already exists on the device, for the user for which this
     * installer was created.
     *
     * <p>This will
     * {@link PackageInstaller.SessionParams#setWhitelistedRestrictedPermissions(Set) allowlist
     * all restricted permissions}.
     *
     * @param packageName The package to install.
     * @param installReason Reason for install.
     * @param statusReceiver Where to deliver the result.
     */
    @RequiresPermission(allOf = {
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.INSTALL_EXISTING_PACKAGES})
    public void installExistingPackage(@NonNull String packageName,
            @InstallReason int installReason,
            @Nullable IntentSender statusReceiver) {
        Objects.requireNonNull(packageName, "packageName cannot be null");
        try {
            mInstaller.installExistingPackage(packageName,
                    PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS, installReason,
                    statusReceiver, mUserId, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Uninstall the given package for the user for which this installer was created if the package
     * will still exist for other users on the device.
     *
     * @param packageName The package to install.
     * @param statusReceiver Where to deliver the result.
     */
    @RequiresPermission(Manifest.permission.DELETE_PACKAGES)
    public void uninstallExistingPackage(@NonNull String packageName,
            @Nullable IntentSender statusReceiver) {
        Objects.requireNonNull(packageName, "packageName cannot be null");
        try {
            mInstaller.uninstallExistingPackage(
                    new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                    mInstallerPackageName, statusReceiver, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INSTALL_PACKAGES)
    public void setPermissionsResult(int sessionId, boolean accepted) {
        try {
            mInstaller.setPermissionsResult(sessionId, accepted);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Events for observing session lifecycle.
     * <p>
     * A typical session lifecycle looks like this:
     * <ul>
     * <li>An installer creates a session to indicate pending app delivery. All
     * install details are available at this point.
     * <li>The installer opens the session to deliver APK data. Note that a
     * session may be opened and closed multiple times as network connectivity
     * changes. The installer may deliver periodic progress updates.
     * <li>The installer commits or abandons the session, resulting in the
     * session being finished.
     * </ul>
     */
    public static abstract class SessionCallback {
        /**
         * New session has been created. Details about the session can be
         * obtained from {@link PackageInstaller#getSessionInfo(int)}.
         */
        public abstract void onCreated(int sessionId);

        /**
         * Badging details for an existing session has changed. For example, the
         * app icon or label has been updated.
         */
        public abstract void onBadgingChanged(int sessionId);

        /**
         * Active state for session has been changed.
         * <p>
         * A session is considered active whenever there is ongoing forward
         * progress being made, such as the installer holding an open
         * {@link Session} instance while streaming data into place, or the
         * system optimizing code as the result of
         * {@link Session#commit(IntentSender)}.
         * <p>
         * If the installer closes the {@link Session} without committing, the
         * session is considered inactive until the installer opens the session
         * again.
         */
        public abstract void onActiveChanged(int sessionId, boolean active);

        /**
         * Progress for given session has been updated.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by
         * {@link PackageInstaller.Session#setStagingProgress(float)}, as the
         * system may carve out a portion of the overall progress to represent
         * its own internal installation work.
         */
        public abstract void onProgressChanged(int sessionId, float progress);

        /**
         * Session has completely finished, either with success or failure.
         */
        public abstract void onFinished(int sessionId, boolean success);
    }

    /** {@hide} */
    static class SessionCallbackDelegate extends IPackageInstallerCallback.Stub {
        private static final int MSG_SESSION_CREATED = 1;
        private static final int MSG_SESSION_BADGING_CHANGED = 2;
        private static final int MSG_SESSION_ACTIVE_CHANGED = 3;
        private static final int MSG_SESSION_PROGRESS_CHANGED = 4;
        private static final int MSG_SESSION_FINISHED = 5;

        final SessionCallback mCallback;
        final Executor mExecutor;

        SessionCallbackDelegate(SessionCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onSessionCreated(int sessionId) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onCreated, mCallback,
                    sessionId).recycleOnUse());
        }

        @Override
        public void onSessionBadgingChanged(int sessionId) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onBadgingChanged,
                    mCallback, sessionId).recycleOnUse());
        }

        @Override
        public void onSessionActiveChanged(int sessionId, boolean active) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onActiveChanged,
                    mCallback, sessionId, active).recycleOnUse());
        }

        @Override
        public void onSessionProgressChanged(int sessionId, float progress) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onProgressChanged,
                    mCallback, sessionId, progress).recycleOnUse());
        }

        @Override
        public void onSessionFinished(int sessionId, boolean success) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onFinished,
                    mCallback, sessionId, success).recycleOnUse());
        }
    }

    /** {@hide} */
    @Deprecated
    public void addSessionCallback(@NonNull SessionCallback callback) {
        registerSessionCallback(callback);
    }

    /**
     * Register to watch for session lifecycle events. No special permissions
     * are required to watch for these events.
     */
    public void registerSessionCallback(@NonNull SessionCallback callback) {
        registerSessionCallback(callback, new Handler());
    }

    /** {@hide} */
    @Deprecated
    public void addSessionCallback(@NonNull SessionCallback callback, @NonNull Handler handler) {
        registerSessionCallback(callback, handler);
    }

    /**
     * Register to watch for session lifecycle events. No special permissions
     * are required to watch for these events.
     *
     * @param handler to dispatch callback events through, otherwise uses
     *            calling thread.
     */
    public void registerSessionCallback(@NonNull SessionCallback callback, @NonNull Handler handler) {
        synchronized (mDelegates) {
            final SessionCallbackDelegate delegate = new SessionCallbackDelegate(callback,
                    new HandlerExecutor(handler));
            try {
                mInstaller.registerCallback(delegate, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mDelegates.add(delegate);
        }
    }

    /** {@hide} */
    @Deprecated
    public void removeSessionCallback(@NonNull SessionCallback callback) {
        unregisterSessionCallback(callback);
    }

    /**
     * Unregister a previously registered callback.
     */
    public void unregisterSessionCallback(@NonNull SessionCallback callback) {
        synchronized (mDelegates) {
            for (Iterator<SessionCallbackDelegate> i = mDelegates.iterator(); i.hasNext();) {
                final SessionCallbackDelegate delegate = i.next();
                if (delegate.mCallback == callback) {
                    try {
                        mInstaller.unregisterCallback(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    i.remove();
                }
            }
        }
    }

    /**
     * An installation that is being actively staged. For an install to succeed,
     * all existing and new packages must have identical package names, version
     * codes, and signing certificates.
     * <p>
     * A session may contain any number of split packages. If the application
     * does not yet exist, this session must include a base package.
     * <p>
     * If an APK included in this session is already defined by the existing
     * installation (for example, the same split name), the APK in this session
     * will replace the existing APK.
     * <p>
     * In such a case that multiple packages need to be committed simultaneously,
     * multiple sessions can be referenced by a single multi-package session.
     * This session is created with no package name and calling
     * {@link SessionParams#setMultiPackage()}. The individual session IDs can be
     * added with {@link #addChildSessionId(int)} and commit of the multi-package
     * session will result in all child sessions being committed atomically.
     */
    public static class Session implements Closeable {
        /** {@hide} */
        protected final IPackageInstallerSession mSession;

        /** {@hide} */
        public Session(IPackageInstallerSession session) {
            mSession = session;
        }

        /** {@hide} */
        @Deprecated
        public void setProgress(float progress) {
            setStagingProgress(progress);
        }

        /**
         * Set current progress of staging this session. Valid values are
         * anywhere between 0 and 1.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by {@link SessionCallback#onProgressChanged(int, float)}, as
         * the system may carve out a portion of the overall progress to
         * represent its own internal installation work.
         */
        public void setStagingProgress(float progress) {
            try {
                mSession.setClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /** {@hide} */
        @UnsupportedAppUsage
        public void addProgress(float progress) {
            try {
                mSession.addClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Open a stream to write an APK file into the session.
         * <p>
         * The returned stream will start writing data at the requested offset
         * in the underlying file, which can be used to resume a partially
         * written file. If a valid file length is specified, the system will
         * preallocate the underlying disk space to optimize placement on disk.
         * It's strongly recommended to provide a valid file length when known.
         * <p>
         * You can write data into the returned stream, optionally call
         * {@link #fsync(OutputStream)} as needed to ensure bytes have been
         * persisted to disk, and then close when finished. All streams must be
         * closed before calling {@link #commit(IntentSender)}.
         *
         * @param name arbitrary, unique name of your choosing to identify the
         *            APK being written. You can open a file again for
         *            additional writes (such as after a reboot) by using the
         *            same name. This name is only meaningful within the context
         *            of a single install session.
         * @param offsetBytes offset into the file to begin writing at, or 0 to
         *            start at the beginning of the file.
         * @param lengthBytes total size of the file being written, used to
         *            preallocate the underlying disk space, or -1 if unknown.
         *            The system may clear various caches as needed to allocate
         *            this space.
         * @throws IOException if trouble opening the file for writing, such as
         *             lack of disk space or unavailable media.
         * @throws SecurityException if called after the session has been
         *             sealed or abandoned
         */
        public @NonNull OutputStream openWrite(@NonNull String name, long offsetBytes,
                long lengthBytes) throws IOException {
            try {
                if (ENABLE_REVOCABLE_FD) {
                    return new ParcelFileDescriptor.AutoCloseOutputStream(
                            mSession.openWrite(name, offsetBytes, lengthBytes));
                } else {
                    final ParcelFileDescriptor clientSocket = mSession.openWrite(name,
                            offsetBytes, lengthBytes);
                    return new FileBridge.FileBridgeOutputStream(clientSocket);
                }
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /** {@hide} */
        public void write(@NonNull String name, long offsetBytes, long lengthBytes,
                @NonNull ParcelFileDescriptor fd) throws IOException {
            try {
                mSession.write(name, offsetBytes, lengthBytes, fd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Populate an APK file by creating a hard link to avoid the need to copy.
         * <p>
         * Note this API is used by RollbackManager only and can only be called from system_server.
         * {@code target} will be relabeled if link is created successfully. RollbackManager has
         * to delete {@code target} when the session is committed successfully to avoid SELinux
         * label conflicts.
         * <p>
         * Note No more bytes should be written to the file once the link is created successfully.
         *
         * @param target the path of the link target
         *
         * @hide
         */
        public void stageViaHardLink(String target) throws IOException {
            try {
                mSession.stageViaHardLink(target);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Ensure that any outstanding data for given stream has been committed
         * to disk. This is only valid for streams returned from
         * {@link #openWrite(String, long, long)}.
         */
        public void fsync(@NonNull OutputStream out) throws IOException {
            if (ENABLE_REVOCABLE_FD) {
                if (out instanceof ParcelFileDescriptor.AutoCloseOutputStream) {
                    try {
                        Os.fsync(((ParcelFileDescriptor.AutoCloseOutputStream) out).getFD());
                    } catch (ErrnoException e) {
                        throw e.rethrowAsIOException();
                    }
                } else {
                    throw new IllegalArgumentException("Unrecognized stream");
                }
            } else {
                if (out instanceof FileBridge.FileBridgeOutputStream) {
                    ((FileBridge.FileBridgeOutputStream) out).fsync();
                } else {
                    throw new IllegalArgumentException("Unrecognized stream");
                }
            }
        }

        /**
         * Return all APK names contained in this session.
         * <p>
         * This returns all names which have been previously written through
         * {@link #openWrite(String, long, long)} as part of this session.
         *
         * @throws SecurityException if called after the session has been
         *             committed or abandoned.
         */
        public @NonNull String[] getNames() throws IOException {
            try {
                return mSession.getNames();
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Open a stream to read an APK file from the session.
         * <p>
         * This is only valid for names which have been previously written
         * through {@link #openWrite(String, long, long)} as part of this
         * session. For example, this stream may be used to calculate a
         * {@link MessageDigest} of a written APK before committing.
         *
         * @throws SecurityException if called after the session has been
         *             committed or abandoned.
         */
        public @NonNull InputStream openRead(@NonNull String name) throws IOException {
            try {
                final ParcelFileDescriptor pfd = mSession.openRead(name);
                return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes a split.
         * <p>
         * Split removals occur prior to adding new APKs. If upgrading a feature
         * split, it is not expected nor desirable to remove the split prior to
         * upgrading.
         * <p>
         * When split removal is bundled with new APKs, the packageName must be
         * identical.
         */
        public void removeSplit(@NonNull String splitName) throws IOException {
            try {
                mSession.removeSplit(splitName);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return data loader params or null if the session is not using one.
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.USE_INSTALLER_V2)
        public @Nullable DataLoaderParams getDataLoaderParams() {
            try {
                DataLoaderParamsParcel data = mSession.getDataLoaderParams();
                if (data == null) {
                    return null;
                }
                return new DataLoaderParams(data);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Adds a file to session. On commit this file will be pulled from DataLoader {@code
         * android.service.dataloader.DataLoaderService.DataLoader}.
         *
         * @param location target location for the file. Possible values:
         *            {@link #LOCATION_DATA_APP},
         *            {@link #LOCATION_MEDIA_OBB},
         *            {@link #LOCATION_MEDIA_DATA}.
         * @param name arbitrary, unique name of your choosing to identify the
         *            APK being written. You can open a file again for
         *            additional writes (such as after a reboot) by using the
         *            same name. This name is only meaningful within the context
         *            of a single install session.
         * @param lengthBytes total size of the file being written.
         *            The system may clear various caches as needed to allocate
         *            this space.
         * @param metadata additional info use by DataLoader to pull data for the file.
         * @param signature additional file signature, e.g.
         *                  <a href="https://source.android.com/security/apksigning/v4.html">APK Signature Scheme v4</a>
         * @throws SecurityException if called after the session has been
         *             sealed or abandoned
         * @throws IllegalStateException if called for non-streaming session
         *
         * @see android.content.pm.InstallationFile
         *
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.USE_INSTALLER_V2)
        public void addFile(@FileLocation int location, @NonNull String name, long lengthBytes,
                @NonNull byte[] metadata, @Nullable byte[] signature) {
            try {
                mSession.addFile(location, name, lengthBytes, metadata, signature);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes a file.
         *
         * @param location target location for the file. Possible values:
         *            {@link #LOCATION_DATA_APP},
         *            {@link #LOCATION_MEDIA_OBB},
         *            {@link #LOCATION_MEDIA_DATA}.
         * @param name name of a file, e.g. split.
         * @throws SecurityException if called after the session has been
         *             sealed or abandoned
         * @throws IllegalStateException if called for non-DataLoader session
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.USE_INSTALLER_V2)
        public void removeFile(@FileLocation int location, @NonNull String name) {
            try {
                mSession.removeFile(location, name);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Sets installer-provided checksums for the APK file in session.
         *
         * @param name      previously written as part of this session.
         *                  {@link #openWrite}
         * @param checksums installer intends to make available via
         *                  {@link PackageManager#requestChecksums}.
         * @param signature DER PKCS#7 detached signature bytes over binary serialized checksums
         *                  to enable integrity checking for the checksums or null for no integrity
         *                  checking. {@link PackageManager#requestChecksums} will return
         *                  the certificate used to create signature.
         *                  Binary format for checksums:
         *                  <pre>{@code DataOutputStream dos;
         *                  dos.writeInt(checksum.getType());
         *                  dos.writeInt(checksum.getValue().length);
         *                  dos.write(checksum.getValue());}</pre>
         *                  If using <b>openssl cms</b>, make sure to specify -binary -nosmimecap.
         *                  @see <a href="https://www.openssl.org/docs/man1.0.2/man1/cms.html">openssl cms</a>
         * @throws SecurityException if called after the session has been
         *                           committed or abandoned.
         * @throws IllegalStateException if checksums for this file have already been added.
         * @deprecated  do not use installer-provided checksums,
         *              use platform-enforced checksums
         *              e.g. {@link Checksum#TYPE_WHOLE_MERKLE_ROOT_4K_SHA256}
         *              in {@link PackageManager#requestChecksums}.
         */
        @Deprecated
        public void setChecksums(@NonNull String name, @NonNull List<Checksum> checksums,
                @Nullable byte[] signature) throws IOException {
            Objects.requireNonNull(name);
            Objects.requireNonNull(checksums);

            try {
                mSession.setChecksums(name, checksums.toArray(new Checksum[checksums.size()]),
                        signature);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Attempt to commit everything staged in this session. This may require
         * user intervention, and so it may not happen immediately. The final
         * result of the commit will be reported through the given callback.
         * <p>
         * Once this method is called, the session is sealed and no additional mutations may be
         * performed on the session. In case of device reboot or data loader transient failure
         * before the session has been finalized, you may commit the session again.
         * <p>
         * If the installer is the device owner or the affiliated profile owner, there will be no
         * user intervention.
         *
         * @param statusReceiver Called when the state of the session changes. Intents
         *                       sent to this receiver contain {@link #EXTRA_STATUS}. Refer to the
         *                       individual status codes on how to handle them.
         *
         * @throws SecurityException if streams opened through
         *             {@link #openWrite(String, long, long)} are still open.
         *
         * @see android.app.admin.DevicePolicyManager
         */
        public void commit(@NonNull IntentSender statusReceiver) {
            try {
                mSession.commit(statusReceiver, false);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Attempt to commit a session that has been {@link #transfer(String) transferred}.
         *
         * <p>If the device reboots before the session has been finalized, you may commit the
         * session again.
         *
         * <p>The caller of this method is responsible to ensure the safety of the session. As the
         * session was created by another - usually less trusted - app, it is paramount that before
         * committing <u>all</u> public and system {@link SessionInfo properties of the session}
         * and <u>all</u> {@link #openRead(String) APKs} are verified by the caller. It might happen
         * that new properties are added to the session with a new API revision. In this case the
         * callers need to be updated.
         *
         * @param statusReceiver Called when the state of the session changes. Intents
         *                       sent to this receiver contain {@link #EXTRA_STATUS}. Refer to the
         *                       individual status codes on how to handle them.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.INSTALL_PACKAGES)
        public void commitTransferred(@NonNull IntentSender statusReceiver) {
            try {
                mSession.commit(statusReceiver, true);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Transfer the session to a new owner.
         * <p>
         * Only sessions that update the installing app can be transferred.
         * <p>
         * After the transfer to a package with a different uid all method calls on the session
         * will cause {@link SecurityException}s.
         * <p>
         * Once this method is called, the session is sealed and no additional mutations beside
         * committing it may be performed on the session.
         *
         * @param packageName The package of the new owner. Needs to hold the INSTALL_PACKAGES
         *                    permission.
         *
         * @throws PackageManager.NameNotFoundException if the new owner could not be found.
         * @throws SecurityException if called after the session has been committed or abandoned.
         * @throws IllegalStateException if streams opened through
         *                                  {@link #openWrite(String, long, long) are still open.
         * @throws IllegalArgumentException if {@code packageName} is invalid.
         */
        public void transfer(@NonNull String packageName)
                throws PackageManager.NameNotFoundException {
            Preconditions.checkArgument(!TextUtils.isEmpty(packageName));

            try {
                mSession.transfer(packageName);
            } catch (ParcelableException e) {
                e.maybeRethrow(PackageManager.NameNotFoundException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Release this session object. You can open the session again if it
         * hasn't been finalized.
         */
        @Override
        public void close() {
            try {
                mSession.close();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Completely abandon this session, destroying all staged data and
         * rendering it invalid. Abandoned sessions will be reported to
         * {@link SessionCallback} listeners as failures. This is equivalent to
         * {@link #abandonSession(int)}.
         * <p>If the parent is abandoned, all children will also be abandoned. Any written data
         * would be destroyed and the created {@link Session} information will be discarded.</p>
         */
        public void abandon() {
            try {
                mSession.abandon();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return {@code true} if this session will commit more than one package when it is
         * committed.
         */
        public boolean isMultiPackage() {
            try {
                return mSession.isMultiPackage();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return {@code true} if this session will be staged and applied at next reboot.
         */
        public boolean isStaged() {
            try {
                return mSession.isStaged();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return the session ID of the multi-package session that this belongs to or
         * {@link SessionInfo#INVALID_ID} if it does not belong to a multi-package session.
         */
        public int getParentSessionId() {
            try {
                return mSession.getParentSessionId();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return the set of session IDs that will be committed atomically when this session is
         * committed if this is a multi-package session or null if none exist.
         */
        @NonNull
        public int[] getChildSessionIds() {
            try {
                return mSession.getChildSessionIds();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Adds a session ID to the set of sessions that will be committed atomically
         * when this session is committed.
         *
         * <p>If the parent is staged or has rollback enabled, all children must have
         * the same properties.</p>
         * <p>If the parent is abandoned, all children will also be abandoned.</p>
         *
         * @param sessionId the session ID to add to this multi-package session.
         */
        public void addChildSessionId(int sessionId) {
            try {
                mSession.addChildSessionId(sessionId);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes a session ID from the set of sessions that will be committed
         * atomically when this session is committed.
         *
         * @param sessionId the session ID to remove from this multi-package session.
         */
        public void removeChildSessionId(int sessionId) {
            try {
                mSession.removeChildSessionId(sessionId);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Parameters for creating a new {@link PackageInstaller.Session}.
     */
    public static class SessionParams implements Parcelable {

        /** {@hide} */
        public static final int MODE_INVALID = -1;

        /**
         * Mode for an install session whose staged APKs should fully replace any
         * existing APKs for the target app.
         */
        public static final int MODE_FULL_INSTALL = 1;

        /**
         * Mode for an install session that should inherit any existing APKs for the
         * target app, unless they have been explicitly overridden (based on split
         * name) by the session. For example, this can be used to add one or more
         * split APKs to an existing installation.
         * <p>
         * If there are no existing APKs for the target app, this behaves like
         * {@link #MODE_FULL_INSTALL}.
         */
        public static final int MODE_INHERIT_EXISTING = 2;

        /**
         * Special constant to refer to all restricted permissions.
         */
        public static final @NonNull Set<String> RESTRICTED_PERMISSIONS_ALL = new ArraySet<>();

        /** {@hide} */
        public static final int UID_UNKNOWN = -1;

        /**
         * This value is derived from the maximum file name length. No package above this limit
         * can ever be successfully installed on the device.
         * @hide
         */
        public static final int MAX_PACKAGE_NAME_LENGTH = 255;

        /** @hide */
        @IntDef(prefix = {"USER_ACTION_"}, value = {
                USER_ACTION_UNSPECIFIED,
                USER_ACTION_REQUIRED,
                USER_ACTION_NOT_REQUIRED
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface UserActionRequirement {}

        /**
         * The installer did not call {@link SessionParams#setRequireUserAction(int)} to
         * specify whether user action should be required for the install.
         */
        public static final int USER_ACTION_UNSPECIFIED = 0;

        /**
         * The installer called {@link SessionParams#setRequireUserAction(int)} with
         * {@code true} to require user action for the install to complete.
         */
        public static final int USER_ACTION_REQUIRED = 1;

        /**
         * The installer called {@link SessionParams#setRequireUserAction(int)} with
         * {@code false} to request that user action not be required for this install.
         */
        public static final int USER_ACTION_NOT_REQUIRED = 2;

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int mode = MODE_INVALID;
        /** {@hide} */
        @UnsupportedAppUsage
        public int installFlags = PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
        /** {@hide} */
        public int installLocation = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
        /** {@hide} */
        public @InstallReason int installReason = PackageManager.INSTALL_REASON_UNKNOWN;
        /**
         * {@hide}
         *
         * This flag indicates which installation scenario best describes this session.  The system
         * may use this value when making decisions about how to handle the installation, such as
         * prioritizing system health or user experience.
         */
        public @InstallScenario int installScenario = PackageManager.INSTALL_SCENARIO_DEFAULT;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long sizeBytes = -1;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String appPackageName;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public Bitmap appIcon;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String appLabel;
        /** {@hide} */
        public long appIconLastModified = -1;
        /** {@hide} */
        public Uri originatingUri;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int originatingUid = UID_UNKNOWN;
        /** {@hide} */
        public Uri referrerUri;
        /** {@hide} */
        public String abiOverride;
        /** {@hide} */
        public String volumeUuid;
        /** {@hide} */
        public String[] grantedRuntimePermissions;
        /** {@hide} */
        public List<String> whitelistedRestrictedPermissions;
        /** {@hide} */
        public int autoRevokePermissionsMode = MODE_DEFAULT;
        /** {@hide} */
        public String installerPackageName;
        /** {@hide} */
        public boolean isMultiPackage;
        /** {@hide} */
        public boolean isStaged;
        /** {@hide} */
        public long requiredInstalledVersionCode = PackageManager.VERSION_CODE_HIGHEST;
        /** {@hide} */
        public DataLoaderParams dataLoaderParams;
        /** {@hide} */
        public int rollbackDataPolicy = PackageManager.RollbackDataPolicy.RESTORE;
        /** {@hide} */
        public boolean forceQueryableOverride;
        /** {@hide} */
        public int requireUserAction = USER_ACTION_UNSPECIFIED;

        /**
         * Construct parameters for a new package install session.
         *
         * @param mode one of {@link #MODE_FULL_INSTALL} or
         *            {@link #MODE_INHERIT_EXISTING} describing how the session
         *            should interact with an existing app.
         */
        public SessionParams(int mode) {
            this.mode = mode;
        }

        /** {@hide} */
        public SessionParams(Parcel source) {
            mode = source.readInt();
            installFlags = source.readInt();
            installLocation = source.readInt();
            installReason = source.readInt();
            installScenario = source.readInt();
            sizeBytes = source.readLong();
            appPackageName = source.readString();
            appIcon = source.readParcelable(null);
            appLabel = source.readString();
            originatingUri = source.readParcelable(null);
            originatingUid = source.readInt();
            referrerUri = source.readParcelable(null);
            abiOverride = source.readString();
            volumeUuid = source.readString();
            grantedRuntimePermissions = source.readStringArray();
            whitelistedRestrictedPermissions = source.createStringArrayList();
            autoRevokePermissionsMode = source.readInt();
            installerPackageName = source.readString();
            isMultiPackage = source.readBoolean();
            isStaged = source.readBoolean();
            forceQueryableOverride = source.readBoolean();
            requiredInstalledVersionCode = source.readLong();
            DataLoaderParamsParcel dataLoaderParamsParcel = source.readParcelable(
                    DataLoaderParamsParcel.class.getClassLoader());
            if (dataLoaderParamsParcel != null) {
                dataLoaderParams = new DataLoaderParams(dataLoaderParamsParcel);
            }
            rollbackDataPolicy = source.readInt();
            requireUserAction = source.readInt();
        }

        /** {@hide} */
        public SessionParams copy() {
            SessionParams ret = new SessionParams(mode);
            ret.installFlags = installFlags;
            ret.installLocation = installLocation;
            ret.installReason = installReason;
            ret.installScenario = installScenario;
            ret.sizeBytes = sizeBytes;
            ret.appPackageName = appPackageName;
            ret.appIcon = appIcon;  // not a copy.
            ret.appLabel = appLabel;
            ret.originatingUri = originatingUri;  // not a copy, but immutable.
            ret.originatingUid = originatingUid;
            ret.referrerUri = referrerUri;  // not a copy, but immutable.
            ret.abiOverride = abiOverride;
            ret.volumeUuid = volumeUuid;
            ret.grantedRuntimePermissions = grantedRuntimePermissions;
            ret.whitelistedRestrictedPermissions = whitelistedRestrictedPermissions;
            ret.autoRevokePermissionsMode = autoRevokePermissionsMode;
            ret.installerPackageName = installerPackageName;
            ret.isMultiPackage = isMultiPackage;
            ret.isStaged = isStaged;
            ret.forceQueryableOverride = forceQueryableOverride;
            ret.requiredInstalledVersionCode = requiredInstalledVersionCode;
            ret.dataLoaderParams = dataLoaderParams;
            ret.rollbackDataPolicy = rollbackDataPolicy;
            ret.requireUserAction = requireUserAction;
            return ret;
        }

        /**
         * Check if there are hidden options set.
         *
         * <p>Hidden options are those options that cannot be verified via public or system-api
         * methods on {@link SessionInfo}.
         *
         * @return {@code true} if any hidden option is set.
         *
         * @hide
         */
        public boolean areHiddenOptionsSet() {
            return (installFlags & (PackageManager.INSTALL_REQUEST_DOWNGRADE
                    | PackageManager.INSTALL_ALLOW_DOWNGRADE
                    | PackageManager.INSTALL_DONT_KILL_APP
                    | PackageManager.INSTALL_INSTANT_APP
                    | PackageManager.INSTALL_FULL_APP
                    | PackageManager.INSTALL_VIRTUAL_PRELOAD
                    | PackageManager.INSTALL_ALLOCATE_AGGRESSIVE)) != installFlags
                    || abiOverride != null || volumeUuid != null;
        }

        /**
         * Provide value of {@link PackageInfo#installLocation}, which may be used
         * to determine where the app will be staged. Defaults to
         * {@link PackageInfo#INSTALL_LOCATION_INTERNAL_ONLY}.
         */
        public void setInstallLocation(int installLocation) {
            this.installLocation = installLocation;
        }

        /**
         * Optionally indicate the total size (in bytes) of all APKs that will be
         * delivered in this session. The system may use this to ensure enough disk
         * space exists before proceeding, or to estimate container size for
         * installations living on external storage.
         *
         * @see PackageInfo#INSTALL_LOCATION_AUTO
         * @see PackageInfo#INSTALL_LOCATION_PREFER_EXTERNAL
         */
        public void setSize(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        /**
         * Optionally set the package name of the app being installed. It's strongly
         * recommended that you provide this value when known, so that observers can
         * communicate installing apps to users.
         * <p>
         * If the APKs staged in the session aren't consistent with this package
         * name, the install will fail. Regardless of this value, all APKs in the
         * app must have the same package name.
         */
        public void setAppPackageName(@Nullable String appPackageName) {
            this.appPackageName = appPackageName;
        }

        /**
         * Optionally set an icon representing the app being installed. This should
         * be roughly {@link ActivityManager#getLauncherLargeIconSize()} in both
         * dimensions.
         */
        public void setAppIcon(@Nullable Bitmap appIcon) {
            this.appIcon = appIcon;
        }

        /**
         * Optionally set a label representing the app being installed.
         *
         * This value will be trimmed to the first 1000 characters.
         */
        public void setAppLabel(@Nullable CharSequence appLabel) {
            this.appLabel = (appLabel != null) ? appLabel.toString() : null;
        }

        /**
         * Optionally set the URI where this package was downloaded from. This is
         * informational and may be used as a signal for anti-malware purposes.
         *
         * @see Intent#EXTRA_ORIGINATING_URI
         */
        public void setOriginatingUri(@Nullable Uri originatingUri) {
            this.originatingUri = originatingUri;
        }

        /**
         * Sets the UID that initiated the package installation. This is informational
         * and may be used as a signal for anti-malware purposes.
         */
        public void setOriginatingUid(int originatingUid) {
            this.originatingUid = originatingUid;
        }

        /**
         * Optionally set the URI that referred you to install this package. This is
         * informational and may be used as a signal for anti-malware purposes.
         *
         * @see Intent#EXTRA_REFERRER
         */
        public void setReferrerUri(@Nullable Uri referrerUri) {
            this.referrerUri = referrerUri;
        }

        /**
         * Sets which runtime permissions to be granted to the package at installation.
         *
         * @param permissions The permissions to grant or null to grant all runtime
         *     permissions.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS)
        public void setGrantedRuntimePermissions(String[] permissions) {
            installFlags |= PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS;
            this.grantedRuntimePermissions = permissions;
        }

        /**
         * Sets which restricted permissions to be allowlisted for the app. Allowlisting
         * is not granting the permissions, rather it allows the app to hold permissions
         * which are otherwise restricted. Allowlisting a non restricted permission has
         * no effect.
         *
         * <p> Permissions can be hard restricted which means that the app cannot hold
         * them or soft restricted where the app can hold the permission but in a weaker
         * form. Whether a permission is {@link PermissionInfo#FLAG_HARD_RESTRICTED hard
         * restricted} or {@link PermissionInfo#FLAG_SOFT_RESTRICTED soft restricted}
         * depends on the permission declaration. Allowlisting a hard restricted permission
         * allows the app to hold that permission and allowlisting a soft restricted
         * permission allows the app to hold the permission in its full, unrestricted form.
         *
         * <p> Permissions can also be immutably restricted which means that the allowlist
         * state of the permission can be determined only at install time and cannot be
         * changed on updated or at a later point via the package manager APIs.
         *
         * <p>Initially, all restricted permissions are allowlisted but you can change
         * which ones are allowlisted by calling this method or the corresponding ones
         * on the {@link PackageManager}. Only soft or hard restricted permissions on the current
         * Android version are supported and any invalid entries will be removed.
         *
         * @see PackageManager#addWhitelistedRestrictedPermission(String, String, int)
         * @see PackageManager#removeWhitelistedRestrictedPermission(String, String, int)
         */
        public void setWhitelistedRestrictedPermissions(@Nullable Set<String> permissions) {
            if (permissions == RESTRICTED_PERMISSIONS_ALL) {
                installFlags |= PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
                whitelistedRestrictedPermissions = null;
            } else {
                installFlags &= ~PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
                whitelistedRestrictedPermissions = (permissions != null)
                        ? new ArrayList<>(permissions) : null;
            }
        }

        /**
         * Sets whether permissions should be auto-revoked if this package is unused for an
         * extended periodd of time.
         *
         * It's disabled by default but generally the installer should enable it for most packages,
         * excluding only those where doing so might cause breakage that cannot be easily addressed
         * by simply re-requesting the permission(s).
         *
         * If user explicitly enabled or disabled it via settings, this call is ignored.
         *
         * @param shouldAutoRevoke whether permissions should be auto-revoked.
         *
         * @deprecated No longer used
         */
        @Deprecated
        public void setAutoRevokePermissionsMode(boolean shouldAutoRevoke) {
            autoRevokePermissionsMode = shouldAutoRevoke ? MODE_ALLOWED : MODE_IGNORED;
        }

        /**
         * Request that rollbacks be enabled or disabled for the given upgrade with rollback data
         * policy set to RESTORE.
         *
         * <p>If the parent session is staged or has rollback enabled, all children sessions
         * must have the same properties.
         *
         * @param enable set to {@code true} to enable, {@code false} to disable
         * @see SessionParams#setEnableRollback(boolean, int)
         * @hide
         */
        @SystemApi
        public void setEnableRollback(boolean enable) {
            if (enable) {
                installFlags |= PackageManager.INSTALL_ENABLE_ROLLBACK;
            } else {
                installFlags &= ~PackageManager.INSTALL_ENABLE_ROLLBACK;
            }
            rollbackDataPolicy = PackageManager.RollbackDataPolicy.RESTORE;
        }

        /**
         * Request that rollbacks be enabled or disabled for the given upgrade.
         *
         * <p>If the parent session is staged or has rollback enabled, all children sessions
         * must have the same properties.
         *
         * <p> For a multi-package install, this method must be called on each child session to
         * specify rollback data policies explicitly. Note each child session is allowed to have
         * different policies.
         *
         * @param enable set to {@code true} to enable, {@code false} to disable
         * @param dataPolicy the rollback data policy for this session
         * @hide
         */
        @SystemApi
        public void setEnableRollback(boolean enable,
                @PackageManager.RollbackDataPolicy int dataPolicy) {
            if (enable) {
                installFlags |= PackageManager.INSTALL_ENABLE_ROLLBACK;
            } else {
                installFlags &= ~PackageManager.INSTALL_ENABLE_ROLLBACK;
            }
            rollbackDataPolicy = dataPolicy;
        }


        /**
         * @deprecated use {@link #setRequestDowngrade(boolean)}.
         * {@hide}
         */
        @SystemApi
        @Deprecated
        public void setAllowDowngrade(boolean allowDowngrade) {
            setRequestDowngrade(allowDowngrade);
        }

        /** {@hide} */
        @SystemApi
        public void setRequestDowngrade(boolean requestDowngrade) {
            if (requestDowngrade) {
                installFlags |= PackageManager.INSTALL_REQUEST_DOWNGRADE;
            } else {
                installFlags &= ~PackageManager.INSTALL_REQUEST_DOWNGRADE;
            }
        }

        /**
         * Require the given version of the package be installed.
         * The install will only be allowed if the existing version code of
         * the package installed on the device matches the given version code.
         * Use {@link * PackageManager#VERSION_CODE_HIGHEST} to allow
         * installation regardless of the currently installed package version.
         *
         * @hide
         */
        public void setRequiredInstalledVersionCode(long versionCode) {
            requiredInstalledVersionCode = versionCode;
        }

        /** {@hide} */
        public void setInstallFlagsForcePermissionPrompt() {
            installFlags |= PackageManager.INSTALL_FORCE_PERMISSION_PROMPT;
        }

        /** {@hide} */
        @SystemApi
        public void setDontKillApp(boolean dontKillApp) {
            if (dontKillApp) {
                installFlags |= PackageManager.INSTALL_DONT_KILL_APP;
            } else {
                installFlags &= ~PackageManager.INSTALL_DONT_KILL_APP;
            }
        }

        /** {@hide} */
        @SystemApi
        public void setInstallAsInstantApp(boolean isInstantApp) {
            if (isInstantApp) {
                installFlags |= PackageManager.INSTALL_INSTANT_APP;
                installFlags &= ~PackageManager.INSTALL_FULL_APP;
            } else {
                installFlags &= ~PackageManager.INSTALL_INSTANT_APP;
                installFlags |= PackageManager.INSTALL_FULL_APP;
            }
        }

        /**
         * Sets the install as a virtual preload. Will only have effect when called
         * by the verifier.
         * {@hide}
         */
        @SystemApi
        public void setInstallAsVirtualPreload() {
            installFlags |= PackageManager.INSTALL_VIRTUAL_PRELOAD;
        }

        /**
         * Set the reason for installing this package.
         * <p>
         * The install reason should be a pre-defined integer. The behavior is
         * undefined if other values are used.
         *
         * @see PackageManager#INSTALL_REASON_UNKNOWN
         * @see PackageManager#INSTALL_REASON_POLICY
         * @see PackageManager#INSTALL_REASON_DEVICE_RESTORE
         * @see PackageManager#INSTALL_REASON_DEVICE_SETUP
         * @see PackageManager#INSTALL_REASON_USER
         */
        public void setInstallReason(@InstallReason int installReason) {
            this.installReason = installReason;
        }

        /** {@hide} */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.ALLOCATE_AGGRESSIVE)
        public void setAllocateAggressive(boolean allocateAggressive) {
            if (allocateAggressive) {
                installFlags |= PackageManager.INSTALL_ALLOCATE_AGGRESSIVE;
            } else {
                installFlags &= ~PackageManager.INSTALL_ALLOCATE_AGGRESSIVE;
            }
        }

        /**
         * @hide
         */
        @TestApi
        public void setInstallFlagAllowTest() {
            installFlags |= PackageManager.INSTALL_ALLOW_TEST;
        }

        /**
         * Set the installer package for the app.
         *
         * By default this is the app that created the {@link PackageInstaller} object.
         *
         * @param installerPackageName name of the installer package
         * {@hide}
         */
        @TestApi
        public void setInstallerPackageName(@Nullable String installerPackageName) {
            this.installerPackageName = installerPackageName;
        }

        /**
         * Set this session to be the parent of a multi-package install.
         *
         * A multi-package install session contains no APKs and only references other install
         * sessions via ID. When a multi-package session is committed, all of its children
         * are committed to the system in an atomic manner. If any children fail to install,
         * all of them do, including the multi-package session.
         */
        public void setMultiPackage() {
            this.isMultiPackage = true;
        }

        /**
         * Set this session to be staged to be installed at reboot.
         *
         * Staged sessions are scheduled to be installed at next reboot. Staged sessions can also be
         * multi-package. In that case, if any of the children sessions fail to install at reboot,
         * all the other children sessions are aborted as well.
         *
         * <p>If the parent session is staged or has rollback enabled, all children sessions
         * must have the same properties.
         *
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
        public void setStaged() {
            this.isStaged = true;
        }

        /**
         * Set this session to be installing an APEX package.
         *
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
        public void setInstallAsApex() {
            installFlags |= PackageManager.INSTALL_APEX;
        }

        /** @hide */
        public boolean getEnableRollback() {
            return (installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0;
        }

        /**
         * Set the data loader params for the session.
         * This also switches installation into data loading mode and disallow direct writes into
         * staging folder.
         *
         * @see android.service.dataloader.DataLoaderService.DataLoader
         *
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(allOf = {
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.USE_INSTALLER_V2})
        public void setDataLoaderParams(@NonNull DataLoaderParams dataLoaderParams) {
            this.dataLoaderParams = dataLoaderParams;
        }

        /**
         *
         * {@hide}
         */
        public void setForceQueryable() {
            this.forceQueryableOverride = true;
        }

        /**
         * Optionally indicate whether user action should be required when the session is
         * committed.
         * <p>
         * Defaults to {@link #USER_ACTION_UNSPECIFIED} unless otherwise set. When unspecified for
         * installers using the
         * {@link android.Manifest.permission#REQUEST_INSTALL_PACKAGES REQUEST_INSTALL_PACKAGES}
         * permission will behave as if set to {@link #USER_ACTION_REQUIRED}, and
         * {@link #USER_ACTION_NOT_REQUIRED} otherwise. When {@code requireUserAction} is set to
         * {@link #USER_ACTION_REQUIRED}, installers will receive a
         * {@link #STATUS_PENDING_USER_ACTION} callback once the session is committed, indicating
         * that user action is required for the install to proceed.
         * <p>
         * For installers that have been granted the
         * {@link android.Manifest.permission#REQUEST_INSTALL_PACKAGES REQUEST_INSTALL_PACKAGES}
         * permission, user action will not be required when all of the following conditions are
         * met:
         *
         * <ul>
         *     <li>{@code requireUserAction} is set to {@link #USER_ACTION_NOT_REQUIRED}.</li>
         *     <li>The app being installed targets {@link android.os.Build.VERSION_CODES#Q API 29}
         *     or higher.</li>
         *     <li>The installer is the {@link InstallSourceInfo#getInstallingPackageName()
         *     installer of record} of an existing version of the app (in other words, this install
         *     session is an app update) or the installer is updating itself.</li>
         *     <li>The installer declares the
         *     {@link android.Manifest.permission#UPDATE_PACKAGES_WITHOUT_USER_ACTION
         *     UPDATE_PACKAGES_WITHOUT_USER_ACTION} permission.</li>
         * </ul>
         * <p>
         * Note: The target API level requirement will advance in future Android versions.
         * Session owners should always be prepared to handle {@link #STATUS_PENDING_USER_ACTION}.
         *
         * @param requireUserAction whether user action should be required.
         */
        public void setRequireUserAction(
                @SessionParams.UserActionRequirement int requireUserAction) {
            if (requireUserAction != USER_ACTION_UNSPECIFIED
                    && requireUserAction != USER_ACTION_REQUIRED
                    && requireUserAction != USER_ACTION_NOT_REQUIRED) {
                throw new IllegalArgumentException("requireUserAction set as invalid value of "
                        + requireUserAction + ", but must be one of ["
                        + "USER_ACTION_UNSPECIFIED, USER_ACTION_REQUIRED, USER_ACTION_NOT_REQUIRED"
                        + "]");
            }
            this.requireUserAction = requireUserAction;
        }

        /**
         * Sets the install scenario for this session, which describes the expected user journey.
         */
        public void setInstallScenario(@InstallScenario int installScenario) {
            this.installScenario = installScenario;
        }

        /** {@hide} */
        public void dump(IndentingPrintWriter pw) {
            pw.printPair("mode", mode);
            pw.printHexPair("installFlags", installFlags);
            pw.printPair("installLocation", installLocation);
            pw.printPair("installReason", installReason);
            pw.printPair("installScenario", installScenario);
            pw.printPair("sizeBytes", sizeBytes);
            pw.printPair("appPackageName", appPackageName);
            pw.printPair("appIcon", (appIcon != null));
            pw.printPair("appLabel", appLabel);
            pw.printPair("originatingUri", originatingUri);
            pw.printPair("originatingUid", originatingUid);
            pw.printPair("referrerUri", referrerUri);
            pw.printPair("abiOverride", abiOverride);
            pw.printPair("volumeUuid", volumeUuid);
            pw.printPair("grantedRuntimePermissions", grantedRuntimePermissions);
            pw.printPair("whitelistedRestrictedPermissions", whitelistedRestrictedPermissions);
            pw.printPair("autoRevokePermissions", autoRevokePermissionsMode);
            pw.printPair("installerPackageName", installerPackageName);
            pw.printPair("isMultiPackage", isMultiPackage);
            pw.printPair("isStaged", isStaged);
            pw.printPair("forceQueryable", forceQueryableOverride);
            pw.printPair("requireUserAction", SessionInfo.userActionToString(requireUserAction));
            pw.printPair("requiredInstalledVersionCode", requiredInstalledVersionCode);
            pw.printPair("dataLoaderParams", dataLoaderParams);
            pw.printPair("rollbackDataPolicy", rollbackDataPolicy);
            pw.println();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mode);
            dest.writeInt(installFlags);
            dest.writeInt(installLocation);
            dest.writeInt(installReason);
            dest.writeInt(installScenario);
            dest.writeLong(sizeBytes);
            dest.writeString(appPackageName);
            dest.writeParcelable(appIcon, flags);
            dest.writeString(appLabel);
            dest.writeParcelable(originatingUri, flags);
            dest.writeInt(originatingUid);
            dest.writeParcelable(referrerUri, flags);
            dest.writeString(abiOverride);
            dest.writeString(volumeUuid);
            dest.writeStringArray(grantedRuntimePermissions);
            dest.writeStringList(whitelistedRestrictedPermissions);
            dest.writeInt(autoRevokePermissionsMode);
            dest.writeString(installerPackageName);
            dest.writeBoolean(isMultiPackage);
            dest.writeBoolean(isStaged);
            dest.writeBoolean(forceQueryableOverride);
            dest.writeLong(requiredInstalledVersionCode);
            if (dataLoaderParams != null) {
                dest.writeParcelable(dataLoaderParams.getData(), flags);
            } else {
                dest.writeParcelable(null, flags);
            }
            dest.writeInt(rollbackDataPolicy);
            dest.writeInt(requireUserAction);
        }

        public static final Parcelable.Creator<SessionParams>
                CREATOR = new Parcelable.Creator<SessionParams>() {
                    @Override
                    public SessionParams createFromParcel(Parcel p) {
                        return new SessionParams(p);
                    }

                    @Override
                    public SessionParams[] newArray(int size) {
                        return new SessionParams[size];
                    }
                };
    }

    /**
     * Details for an active install session.
     */
    public static class SessionInfo implements Parcelable {

        /**
         * A session ID that does not exist or is invalid.
         */
        public static final int INVALID_ID = -1;
        /** {@hide} */
        private static final int[] NO_SESSIONS = {};

        /** @hide */
        @IntDef(prefix = { "STAGED_SESSION_" }, value = {
                STAGED_SESSION_NO_ERROR,
                STAGED_SESSION_VERIFICATION_FAILED,
                STAGED_SESSION_ACTIVATION_FAILED,
                STAGED_SESSION_UNKNOWN,
                STAGED_SESSION_CONFLICT})
        @Retention(RetentionPolicy.SOURCE)
        public @interface StagedSessionErrorCode{}
        /**
         * Constant indicating that no error occurred during the preparation or the activation of
         * this staged session.
         */
        public static final int STAGED_SESSION_NO_ERROR = 0;

        /**
         * Constant indicating that an error occurred during the verification phase (pre-reboot) of
         * this staged session.
         */
        public static final int STAGED_SESSION_VERIFICATION_FAILED = 1;

        /**
         * Constant indicating that an error occurred during the activation phase (post-reboot) of
         * this staged session.
         */
        public static final int STAGED_SESSION_ACTIVATION_FAILED = 2;

        /**
         * Constant indicating that an unknown error occurred while processing this staged session.
         */
        public static final int STAGED_SESSION_UNKNOWN = 3;

        /**
         * Constant indicating that the session was in conflict with another staged session and had
         * to be sacrificed for resolution.
         */
        public static final int STAGED_SESSION_CONFLICT = 4;

        private static String userActionToString(int requireUserAction) {
            switch(requireUserAction) {
                case SessionParams.USER_ACTION_REQUIRED:
                    return "REQUIRED";
                case SessionParams.USER_ACTION_NOT_REQUIRED:
                    return "NOT_REQUIRED";
                default:
                    return "UNSPECIFIED";
            }
        }

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int sessionId;
        /** {@hide} */
        public int userId;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String installerPackageName;
        /** {@hide} */
        public String installerAttributionTag;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public String resolvedBaseCodePath;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public float progress;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public boolean sealed;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public boolean active;

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int mode;
        /** {@hide} */
        public @InstallReason int installReason;
        /** {@hide} */
        public @InstallScenario int installScenario;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public long sizeBytes;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String appPackageName;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public Bitmap appIcon;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public CharSequence appLabel;

        /** {@hide} */
        public int installLocation;
        /** {@hide} */
        public Uri originatingUri;
        /** {@hide} */
        public int originatingUid;
        /** {@hide} */
        public Uri referrerUri;
        /** {@hide} */
        public String[] grantedRuntimePermissions;
        /** {@hide}*/
        public List<String> whitelistedRestrictedPermissions;
        /** {@hide}*/
        public int autoRevokePermissionsMode = MODE_DEFAULT;
        /** {@hide} */
        public int installFlags;
        /** {@hide} */
        public boolean isMultiPackage;
        /** {@hide} */
        public boolean isStaged;
        /** {@hide} */
        public boolean forceQueryable;
        /** {@hide} */
        public int parentSessionId = INVALID_ID;
        /** {@hide} */
        public int[] childSessionIds = NO_SESSIONS;

        /** {@hide} */
        public boolean isStagedSessionApplied;
        /** {@hide} */
        public boolean isStagedSessionReady;
        /** {@hide} */
        public boolean isStagedSessionFailed;
        private int mStagedSessionErrorCode;
        private String mStagedSessionErrorMessage;

        /** {@hide} */
        public boolean isCommitted;

        /** {@hide} */
        public long createdMillis;

        /** {@hide} */
        public long updatedMillis;

        /** {@hide} */
        public int rollbackDataPolicy;

        /** {@hide} */
        public int requireUserAction;

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public SessionInfo() {
        }

        /** {@hide} */
        public SessionInfo(Parcel source) {
            sessionId = source.readInt();
            userId = source.readInt();
            installerPackageName = source.readString();
            installerAttributionTag = source.readString();
            resolvedBaseCodePath = source.readString();
            progress = source.readFloat();
            sealed = source.readInt() != 0;
            active = source.readInt() != 0;

            mode = source.readInt();
            installReason = source.readInt();
            installScenario = source.readInt();
            sizeBytes = source.readLong();
            appPackageName = source.readString();
            appIcon = source.readParcelable(null);
            appLabel = source.readString();

            installLocation = source.readInt();
            originatingUri = source.readParcelable(null);
            originatingUid = source.readInt();
            referrerUri = source.readParcelable(null);
            grantedRuntimePermissions = source.readStringArray();
            whitelistedRestrictedPermissions = source.createStringArrayList();
            autoRevokePermissionsMode = source.readInt();

            installFlags = source.readInt();
            isMultiPackage = source.readBoolean();
            isStaged = source.readBoolean();
            forceQueryable = source.readBoolean();
            parentSessionId = source.readInt();
            childSessionIds = source.createIntArray();
            if (childSessionIds == null) {
                childSessionIds = NO_SESSIONS;
            }
            isStagedSessionApplied = source.readBoolean();
            isStagedSessionReady = source.readBoolean();
            isStagedSessionFailed = source.readBoolean();
            mStagedSessionErrorCode = source.readInt();
            mStagedSessionErrorMessage = source.readString();
            isCommitted = source.readBoolean();
            rollbackDataPolicy = source.readInt();
            createdMillis = source.readLong();
            requireUserAction = source.readInt();
        }

        /**
         * Return the ID for this session.
         */
        public int getSessionId() {
            return sessionId;
        }

        /**
         * Return the user associated with this session.
         */
        public @NonNull UserHandle getUser() {
            return new UserHandle(userId);
        }

        /**
         * Return the package name of the app that owns this session.
         */
        public @Nullable String getInstallerPackageName() {
            return installerPackageName;
        }

        /**
         * @return {@link android.content.Context#getAttributionTag attribution tag} of the context
         * that created this session
         */
        public @Nullable String getInstallerAttributionTag() {
            return installerAttributionTag;
        }

        /**
         * Return current overall progress of this session, between 0 and 1.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by
         * {@link PackageInstaller.Session#setStagingProgress(float)}, as the
         * system may carve out a portion of the overall progress to represent
         * its own internal installation work.
         */
        public float getProgress() {
            return progress;
        }

        /**
         * Return if this session is currently active.
         * <p>
         * A session is considered active whenever there is ongoing forward
         * progress being made, such as the installer holding an open
         * {@link Session} instance while streaming data into place, or the
         * system optimizing code as the result of
         * {@link Session#commit(IntentSender)}.
         * <p>
         * If the installer closes the {@link Session} without committing, the
         * session is considered inactive until the installer opens the session
         * again.
         */
        public boolean isActive() {
            return active;
        }

        /**
         * Return if this session is sealed.
         * <p>
         * Once sealed, no further changes may be made to the session. A session
         * is sealed the moment {@link Session#commit(IntentSender)} is called.
         */
        public boolean isSealed() {
            return sealed;
        }

        /**
         * Return the reason for installing this package.
         *
         * @return The install reason.
         */
        public @InstallReason int getInstallReason() {
            return installReason;
        }

        /** {@hide} */
        @Deprecated
        public boolean isOpen() {
            return isActive();
        }

        /**
         * Return the package name this session is working with. May be {@code null}
         * if unknown.
         */
        public @Nullable String getAppPackageName() {
            return appPackageName;
        }

        /**
         * Return an icon representing the app being installed. May be {@code null}
         * if unavailable.
         */
        public @Nullable Bitmap getAppIcon() {
            if (appIcon == null) {
                // Icon may have been omitted for calls that return bulk session
                // lists, so try fetching the specific icon.
                try {
                    final SessionInfo info = AppGlobals.getPackageManager().getPackageInstaller()
                            .getSessionInfo(sessionId);
                    appIcon = (info != null) ? info.appIcon : null;
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return appIcon;
        }

        /**
         * Return a label representing the app being installed. May be {@code null}
         * if unavailable.
         */
        public @Nullable CharSequence getAppLabel() {
            return appLabel;
        }

        /**
         * Return an Intent that can be started to view details about this install
         * session. This may surface actions such as pause, resume, or cancel.
         * <p>
         * In some cases, a matching Activity may not exist, so ensure you safeguard
         * against this.
         *
         * @see PackageInstaller#ACTION_SESSION_DETAILS
         */
        public @Nullable Intent createDetailsIntent() {
            final Intent intent = new Intent(PackageInstaller.ACTION_SESSION_DETAILS);
            intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
            intent.setPackage(installerPackageName);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }

        /**
         * Get the mode of the session as set in the constructor of the {@link SessionParams}.
         *
         * @return One of {@link SessionParams#MODE_FULL_INSTALL}
         *         or {@link SessionParams#MODE_INHERIT_EXISTING}
         */
        public int getMode() {
            return mode;
        }

        /**
         * Get the value set in {@link SessionParams#setInstallLocation(int)}.
         */
        public int getInstallLocation() {
            return installLocation;
        }

        /**
         * Get the value as set in {@link SessionParams#setSize(long)}.
         *
         * <p>The value is a hint and does not have to match the actual size.
         */
        public long getSize() {
            return sizeBytes;
        }

        /**
         * Get the value set in {@link SessionParams#setOriginatingUri(Uri)}.
         * Note: This value will only be non-null for the owner of the session.
         */
        public @Nullable Uri getOriginatingUri() {
            return originatingUri;
        }

        /**
         * Get the value set in {@link SessionParams#setOriginatingUid(int)}.
         */
        public int getOriginatingUid() {
            return originatingUid;
        }

        /**
         * Get the value set in {@link SessionParams#setReferrerUri(Uri)}
         * Note: This value will only be non-null for the owner of the session.
         */
        public @Nullable Uri getReferrerUri() {
            return referrerUri;
        }

        /**
         * Get the value set in {@link SessionParams#setGrantedRuntimePermissions(String[])}.
         *
         * @hide
         */
        @SystemApi
        public @Nullable String[] getGrantedRuntimePermissions() {
            return grantedRuntimePermissions;
        }

        /**
         * Get the value set in {@link SessionParams#setWhitelistedRestrictedPermissions(Set)}.
         * Note that if all permissions are allowlisted this method returns {@link
         * SessionParams#RESTRICTED_PERMISSIONS_ALL}.
         *
         * @hide
         */
        @SystemApi
        public @NonNull Set<String> getWhitelistedRestrictedPermissions() {
            if ((installFlags & PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS) != 0) {
                return SessionParams.RESTRICTED_PERMISSIONS_ALL;
            }
            if (whitelistedRestrictedPermissions != null) {
                return new ArraySet<>(whitelistedRestrictedPermissions);
            }
            return Collections.emptySet();
        }

        /**
         * Get the status of whether permission auto-revocation should be allowed, ignored, or
         * deferred to manifest data.
         *
         * @see android.app.AppOpsManager#MODE_ALLOWED
         * @see android.app.AppOpsManager#MODE_IGNORED
         * @see android.app.AppOpsManager#MODE_DEFAULT
         *
         * @return the status of auto-revoke for this package
         *
         * @hide
         */
        @SystemApi
        public int getAutoRevokePermissionsMode() {
            return autoRevokePermissionsMode;
        }

        /**
         * Get the value set in {@link SessionParams#setAllowDowngrade(boolean)}.
         *
         * @deprecated use {@link #getRequestDowngrade()}.
         * @hide
         */
        @SystemApi
        @Deprecated
        public boolean getAllowDowngrade() {
            return getRequestDowngrade();
        }

        /**
         * Get the value set in {@link SessionParams#setRequestDowngrade(boolean)}.
         *
         * @hide
         */
        @SystemApi
        public boolean getRequestDowngrade() {
            return (installFlags & PackageManager.INSTALL_REQUEST_DOWNGRADE) != 0;
        }

        /**
         * Get the value set in {@link SessionParams#setDontKillApp(boolean)}.
         *
         * @hide
         */
        @SystemApi
        public boolean getDontKillApp() {
            return (installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0;
        }

        /**
         * Get if this session is to be installed as Instant Apps.
         *
         * @param isInstantApp an unused parameter and is ignored.
         * @return {@code true} if {@link SessionParams#setInstallAsInstantApp(boolean)} was called
         * with {@code true}; {@code false} if it was called with {@code false} or if it was not
         * called.
         *
         * @see #getInstallAsFullApp
         *
         * @hide
         */
        @SystemApi
        public boolean getInstallAsInstantApp(boolean isInstantApp) {
            return (installFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
        }

        /**
         * Get if this session is to be installed as full apps.
         *
         * @param isInstantApp an unused parameter and is ignored.
         * @return {@code true} if {@link SessionParams#setInstallAsInstantApp(boolean)} was called
         * with {@code false}; {code false} if it was called with {@code true} or if it was not
         * called.
         *
         * @see #getInstallAsInstantApp
         *
         * @hide
         */
        @SystemApi
        public boolean getInstallAsFullApp(boolean isInstantApp) {
            return (installFlags & PackageManager.INSTALL_FULL_APP) != 0;
        }

        /**
         * Get if {@link SessionParams#setInstallAsVirtualPreload()} was called.
         *
         * @hide
         */
        @SystemApi
        public boolean getInstallAsVirtualPreload() {
            return (installFlags & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0;
        }

        /**
         * Return whether rollback is enabled or disabled for the given upgrade.
         *
         * @hide
         */
        @SystemApi
        public boolean getEnableRollback() {
            return (installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0;
        }

        /**
         * Get the value set in {@link SessionParams#setAllocateAggressive(boolean)}.
         *
         * @hide
         */
        @SystemApi
        public boolean getAllocateAggressive() {
            return (installFlags & PackageManager.INSTALL_ALLOCATE_AGGRESSIVE) != 0;
        }


        /** {@hide} */
        @Deprecated
        public @Nullable Intent getDetailsIntent() {
            return createDetailsIntent();
        }

        /**
         * Returns true if this session is a multi-package session containing references to other
         * sessions.
         */
        public boolean isMultiPackage() {
            return isMultiPackage;
        }

        /**
         * Returns true if this session is a staged session.
         */
        public boolean isStaged() {
            return isStaged;
        }

        /**
         * Return the data policy associated with the rollback for the given upgrade.
         *
         * @hide
         */
        @SystemApi
        @PackageManager.RollbackDataPolicy
        public int getRollbackDataPolicy() {
            return rollbackDataPolicy;
        }

        /**
         * Returns true if this session is marked as forceQueryable
         * {@hide}
         */
        public boolean isForceQueryable() {
            return forceQueryable;
        }

        /**
         * Returns {@code true} if this session is an active staged session.
         *
         * We consider a session active if it has been committed and it is either pending
         * verification, or will be applied at next reboot.
         *
         * <p>Staged session is active iff:
         * <ul>
         *     <li>It is committed, i.e. {@link SessionInfo#isCommitted()} is {@code true}, and
         *     <li>it is not applied, i.e. {@link SessionInfo#isStagedSessionApplied()} is {@code
         *     false}, and
         *     <li>it is not failed, i.e. {@link SessionInfo#isStagedSessionFailed()} is
         *     {@code false}.
         * </ul>
         *
         * <p>In case of a multi-package session, reasoning above is applied to the parent session,
         * since that is the one that should have been {@link Session#commit committed}.
         */
        public boolean isStagedSessionActive() {
            return isStaged && isCommitted && !isStagedSessionApplied && !isStagedSessionFailed
                    && !hasParentSessionId();
        }

        /**
         * Returns the parent multi-package session ID if this session belongs to one,
         * {@link #INVALID_ID} otherwise.
         */
        public int getParentSessionId() {
            return parentSessionId;
        }

        /**
         * Returns true if session has a valid parent session, otherwise false.
         */
        public boolean hasParentSessionId() {
            return parentSessionId != INVALID_ID;
        }

        /**
         * Returns the set of session IDs that will be committed when this session is commited if
         * this session is a multi-package session.
         */
        @NonNull
        public int[] getChildSessionIds() {
            return childSessionIds;
        }

        private void checkSessionIsStaged() {
            if (!isStaged) {
                throw new IllegalStateException("Session is not marked as staged.");
            }
        }

        /**
         * Whether the staged session has been applied successfully, meaning that all of its
         * packages have been activated and no further action is required.
         * Only meaningful if {@code isStaged} is true.
         */
        public boolean isStagedSessionApplied() {
            checkSessionIsStaged();
            return isStagedSessionApplied;
        }

        /**
         * Whether the staged session is ready to be applied at next reboot. Only meaningful if
         * {@code isStaged} is true.
         */
        public boolean isStagedSessionReady() {
            checkSessionIsStaged();
            return isStagedSessionReady;
        }

        /**
         * Whether something went wrong and the staged session is declared as failed, meaning that
         * it will be ignored at next reboot. Only meaningful if {@code isStaged} is true.
         */
        public boolean isStagedSessionFailed() {
            checkSessionIsStaged();
            return isStagedSessionFailed;
        }

        /**
         * If something went wrong with a staged session, clients can check this error code to
         * understand which kind of failure happened. Only meaningful if {@code isStaged} is true.
         */
        public @StagedSessionErrorCode int getStagedSessionErrorCode() {
            checkSessionIsStaged();
            return mStagedSessionErrorCode;
        }

        /**
         * Text description of the error code returned by {@code getStagedSessionErrorCode}, or
         * empty string if no error was encountered.
         */
        public @NonNull String getStagedSessionErrorMessage() {
            checkSessionIsStaged();
            return mStagedSessionErrorMessage;
        }

        /** {@hide} */
        public void setStagedSessionErrorCode(@StagedSessionErrorCode int errorCode,
                                              String errorMessage) {
            mStagedSessionErrorCode = errorCode;
            mStagedSessionErrorMessage = errorMessage;
        }

        /**
         * Returns {@code true} if {@link Session#commit(IntentSender)}} was called for this
         * session.
         */
        public boolean isCommitted() {
            return isCommitted;
        }

        /**
         * The timestamp of the initial creation of the session.
         */
        public long getCreatedMillis() {
            return createdMillis;
        }

        /**
         * The timestamp of the last update that occurred to the session, including changing of
         * states in case of staged sessions.
         */
        @CurrentTimeMillisLong
        public long getUpdatedMillis() {
            return updatedMillis;
        }

        /**
         * Whether user action was required by the installer.
         *
         * <p>
         * Note: a return value of {@code USER_ACTION_NOT_REQUIRED} does not guarantee that the
         * install will not result in user action.
         *
         * @return {@link SessionParams#USER_ACTION_NOT_REQUIRED},
         *         {@link SessionParams#USER_ACTION_REQUIRED} or
         *         {@link SessionParams#USER_ACTION_UNSPECIFIED}
         */
        @SessionParams.UserActionRequirement
        public int getRequireUserAction() {
            return requireUserAction;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(sessionId);
            dest.writeInt(userId);
            dest.writeString(installerPackageName);
            dest.writeString(installerAttributionTag);
            dest.writeString(resolvedBaseCodePath);
            dest.writeFloat(progress);
            dest.writeInt(sealed ? 1 : 0);
            dest.writeInt(active ? 1 : 0);

            dest.writeInt(mode);
            dest.writeInt(installReason);
            dest.writeInt(installScenario);
            dest.writeLong(sizeBytes);
            dest.writeString(appPackageName);
            dest.writeParcelable(appIcon, flags);
            dest.writeString(appLabel != null ? appLabel.toString() : null);

            dest.writeInt(installLocation);
            dest.writeParcelable(originatingUri, flags);
            dest.writeInt(originatingUid);
            dest.writeParcelable(referrerUri, flags);
            dest.writeStringArray(grantedRuntimePermissions);
            dest.writeStringList(whitelistedRestrictedPermissions);
            dest.writeInt(autoRevokePermissionsMode);
            dest.writeInt(installFlags);
            dest.writeBoolean(isMultiPackage);
            dest.writeBoolean(isStaged);
            dest.writeBoolean(forceQueryable);
            dest.writeInt(parentSessionId);
            dest.writeIntArray(childSessionIds);
            dest.writeBoolean(isStagedSessionApplied);
            dest.writeBoolean(isStagedSessionReady);
            dest.writeBoolean(isStagedSessionFailed);
            dest.writeInt(mStagedSessionErrorCode);
            dest.writeString(mStagedSessionErrorMessage);
            dest.writeBoolean(isCommitted);
            dest.writeInt(rollbackDataPolicy);
            dest.writeLong(createdMillis);
            dest.writeInt(requireUserAction);
        }

        public static final Parcelable.Creator<SessionInfo>
                CREATOR = new Parcelable.Creator<SessionInfo>() {
                    @Override
                    public SessionInfo createFromParcel(Parcel p) {
                        return new SessionInfo(p);
                    }

                    @Override
                    public SessionInfo[] newArray(int size) {
                        return new SessionInfo[size];
                    }
                };
    }
}

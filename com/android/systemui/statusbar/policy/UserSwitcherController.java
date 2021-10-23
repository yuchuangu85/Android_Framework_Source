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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import static android.os.UserManager.SWITCHABILITY_STATUS_OK;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;
import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.IActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyCallback;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManagerGlobal;
import android.widget.BaseAdapter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.systemui.Dumpable;
import com.android.systemui.GuestResumeSessionReceiver;
import com.android.systemui.Prefs;
import com.android.systemui.Prefs.Key;
import com.android.systemui.R;
import com.android.systemui.SystemUISecondaryUserService;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.qs.QSUserSwitcherEvent;
import com.android.systemui.qs.tiles.UserDetailView;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.user.CreateUserActivity;
import com.android.systemui.util.settings.SecureSettings;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Keeps a list of all users on the device for user switching.
 */
@SysUISingleton
public class UserSwitcherController implements Dumpable {

    public static final float USER_SWITCH_ENABLED_ALPHA = 1.0f;
    public static final float USER_SWITCH_DISABLED_ALPHA = 0.38f;

    private static final String TAG = "UserSwitcherController";
    private static final boolean DEBUG = false;
    private static final String SIMPLE_USER_SWITCHER_GLOBAL_SETTING =
            "lockscreenSimpleUserSwitcher";
    private static final int PAUSE_REFRESH_USERS_TIMEOUT_MS = 3000;

    private static final String PERMISSION_SELF = "com.android.systemui.permission.SELF";

    protected final Context mContext;
    protected final UserTracker mUserTracker;
    protected final UserManager mUserManager;
    private final ContentObserver mSettingsObserver;
    private final ArrayList<WeakReference<BaseUserAdapter>> mAdapters = new ArrayList<>();
    @VisibleForTesting
    final GuestResumeSessionReceiver mGuestResumeSessionReceiver;
    private final KeyguardStateController mKeyguardStateController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final DevicePolicyManager mDevicePolicyManager;
    protected final Handler mHandler;
    private final ActivityStarter mActivityStarter;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final TelephonyListenerManager mTelephonyListenerManager;
    private final IActivityTaskManager mActivityTaskManager;

    private ArrayList<UserRecord> mUsers = new ArrayList<>();
    @VisibleForTesting
    AlertDialog mExitGuestDialog;
    @VisibleForTesting
    Dialog mAddUserDialog;
    private int mLastNonGuestUser = UserHandle.USER_SYSTEM;
    private boolean mResumeUserOnGuestLogout = true;
    private boolean mSimpleUserSwitcher;
    // When false, there won't be any visual affordance to add a new user from the keyguard even if
    // the user is unlocked
    private boolean mAddUsersFromLockScreen;
    @VisibleForTesting
    boolean mPauseRefreshUsers;
    private int mSecondaryUser = UserHandle.USER_NULL;
    private Intent mSecondaryUserServiceIntent;
    private SparseBooleanArray mForcePictureLoadForUserId = new SparseBooleanArray(2);
    private final UiEventLogger mUiEventLogger;
    public final DetailAdapter mUserDetailAdapter;
    private final Executor mBgExecutor;
    private final boolean mGuestUserAutoCreated;
    private final AtomicBoolean mGuestIsResetting;
    private final AtomicBoolean mGuestCreationScheduled;
    private FalsingManager mFalsingManager;

    @Inject
    public UserSwitcherController(Context context,
            UserManager userManager,
            UserTracker userTracker,
            KeyguardStateController keyguardStateController,
            DeviceProvisionedController deviceProvisionedController,
            DevicePolicyManager devicePolicyManager,
            @Main Handler handler,
            ActivityStarter activityStarter,
            BroadcastDispatcher broadcastDispatcher,
            UiEventLogger uiEventLogger,
            FalsingManager falsingManager,
            TelephonyListenerManager telephonyListenerManager,
            IActivityTaskManager activityTaskManager,
            UserDetailAdapter userDetailAdapter,
            SecureSettings secureSettings,
            @Background Executor bgExecutor) {
        mContext = context;
        mUserTracker = userTracker;
        mBroadcastDispatcher = broadcastDispatcher;
        mTelephonyListenerManager = telephonyListenerManager;
        mActivityTaskManager = activityTaskManager;
        mUiEventLogger = uiEventLogger;
        mFalsingManager = falsingManager;
        mGuestResumeSessionReceiver = new GuestResumeSessionReceiver(
                this, mUserTracker, mUiEventLogger, secureSettings);
        mUserDetailAdapter = userDetailAdapter;
        mBgExecutor = bgExecutor;
        if (!UserManager.isGuestUserEphemeral()) {
            mGuestResumeSessionReceiver.register(mBroadcastDispatcher);
        }
        mGuestUserAutoCreated = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_guestUserAutoCreated);
        mGuestIsResetting = new AtomicBoolean();
        mGuestCreationScheduled = new AtomicBoolean();
        mKeyguardStateController = keyguardStateController;
        mDeviceProvisionedController = deviceProvisionedController;
        mDevicePolicyManager = devicePolicyManager;
        mHandler = handler;
        mActivityStarter = activityStarter;
        mUserManager = userManager;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        mBroadcastDispatcher.registerReceiver(
                mReceiver, filter, null /* handler */, UserHandle.SYSTEM);

        mSimpleUserSwitcher = shouldUseSimpleUserSwitcher();

        mSecondaryUserServiceIntent = new Intent(context, SystemUISecondaryUserService.class);

        filter = new IntentFilter();
        mContext.registerReceiverAsUser(mReceiver, UserHandle.SYSTEM, filter,
                PERMISSION_SELF, null /* scheduler */);

        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mSimpleUserSwitcher = shouldUseSimpleUserSwitcher();
                mAddUsersFromLockScreen = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.ADD_USERS_WHEN_LOCKED, 0) != 0;
                refreshUsers(UserHandle.USER_NULL);
            };
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SIMPLE_USER_SWITCHER_GLOBAL_SETTING), true,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADD_USERS_WHEN_LOCKED), true,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                        Settings.Global.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED),
                true, mSettingsObserver);
        // Fetch initial values.
        mSettingsObserver.onChange(false);

        keyguardStateController.addCallback(mCallback);
        listenForCallState();

        refreshUsers(UserHandle.USER_NULL);
    }

    /**
     * Refreshes users from UserManager.
     *
     * The pictures are only loaded if they have not been loaded yet.
     *
     * @param forcePictureLoadForId forces the picture of the given user to be reloaded.
     */
    @SuppressWarnings("unchecked")
    private void refreshUsers(int forcePictureLoadForId) {
        if (DEBUG) Log.d(TAG, "refreshUsers(forcePictureLoadForId=" + forcePictureLoadForId+")");
        if (forcePictureLoadForId != UserHandle.USER_NULL) {
            mForcePictureLoadForUserId.put(forcePictureLoadForId, true);
        }

        if (mPauseRefreshUsers) {
            return;
        }

        boolean forceAllUsers = mForcePictureLoadForUserId.get(UserHandle.USER_ALL);
        SparseArray<Bitmap> bitmaps = new SparseArray<>(mUsers.size());
        final int N = mUsers.size();
        for (int i = 0; i < N; i++) {
            UserRecord r = mUsers.get(i);
            if (r == null || r.picture == null || r.info == null || forceAllUsers
                    || mForcePictureLoadForUserId.get(r.info.id)) {
                continue;
            }
            bitmaps.put(r.info.id, r.picture);
        }
        mForcePictureLoadForUserId.clear();

        final boolean addUsersWhenLocked = mAddUsersFromLockScreen;
        new AsyncTask<SparseArray<Bitmap>, Void, ArrayList<UserRecord>>() {
            @SuppressWarnings("unchecked")
            @Override
            protected ArrayList<UserRecord> doInBackground(SparseArray<Bitmap>... params) {
                final SparseArray<Bitmap> bitmaps = params[0];
                List<UserInfo> infos = mUserManager.getAliveUsers();
                if (infos == null) {
                    return null;
                }
                ArrayList<UserRecord> records = new ArrayList<>(infos.size());
                int currentId = mUserTracker.getUserId();
                // Check user switchability of the foreground user since SystemUI is running in
                // User 0
                boolean canSwitchUsers = mUserManager.getUserSwitchability(
                        UserHandle.of(mUserTracker.getUserId())) == SWITCHABILITY_STATUS_OK;
                UserInfo currentUserInfo = null;
                UserRecord guestRecord = null;

                for (UserInfo info : infos) {
                    boolean isCurrent = currentId == info.id;
                    if (isCurrent) {
                        currentUserInfo = info;
                    }
                    boolean switchToEnabled = canSwitchUsers || isCurrent;
                    if (info.isEnabled()) {
                        if (info.isGuest()) {
                            // Tapping guest icon triggers remove and a user switch therefore
                            // the icon shouldn't be enabled even if the user is current
                            guestRecord = new UserRecord(info, null /* picture */,
                                    true /* isGuest */, isCurrent, false /* isAddUser */,
                                    false /* isRestricted */, canSwitchUsers);
                        } else if (info.supportsSwitchToByUser()) {
                            Bitmap picture = bitmaps.get(info.id);
                            if (picture == null) {
                                picture = mUserManager.getUserIcon(info.id);

                                if (picture != null) {
                                    int avatarSize = mContext.getResources()
                                            .getDimensionPixelSize(R.dimen.max_avatar_size);
                                    picture = Bitmap.createScaledBitmap(
                                            picture, avatarSize, avatarSize, true);
                                }
                            }
                            records.add(new UserRecord(info, picture, false /* isGuest */,
                                    isCurrent, false /* isAddUser */, false /* isRestricted */,
                                    switchToEnabled));
                        }
                    }
                }
                if (records.size() > 1 || guestRecord != null) {
                    Prefs.putBoolean(mContext, Key.SEEN_MULTI_USER, true);
                }

                boolean systemCanCreateUsers = !mUserManager.hasBaseUserRestriction(
                                UserManager.DISALLOW_ADD_USER, UserHandle.SYSTEM);
                boolean currentUserCanCreateUsers = currentUserInfo != null
                        && (currentUserInfo.isAdmin()
                                || currentUserInfo.id == UserHandle.USER_SYSTEM)
                        && systemCanCreateUsers;
                boolean anyoneCanCreateUsers = systemCanCreateUsers && addUsersWhenLocked;
                boolean canCreateGuest = (currentUserCanCreateUsers || anyoneCanCreateUsers)
                        && guestRecord == null;
                boolean canCreateUser = (currentUserCanCreateUsers || anyoneCanCreateUsers)
                        && mUserManager.canAddMoreUsers();
                boolean createIsRestricted = !addUsersWhenLocked;

                if (guestRecord == null) {
                    if (mGuestUserAutoCreated) {
                        // If mGuestIsResetting=true, the switch should be disabled since
                        // we will just use it as an indicator for "Resetting guest...".
                        // Otherwise, default to canSwitchUsers.
                        boolean isSwitchToGuestEnabled =
                                !mGuestIsResetting.get() && canSwitchUsers;
                        guestRecord = new UserRecord(null /* info */, null /* picture */,
                                true /* isGuest */, false /* isCurrent */,
                                false /* isAddUser */, false /* isRestricted */,
                                isSwitchToGuestEnabled);
                        checkIfAddUserDisallowedByAdminOnly(guestRecord);
                        records.add(guestRecord);
                    } else if (canCreateGuest) {
                        guestRecord = new UserRecord(null /* info */, null /* picture */,
                                true /* isGuest */, false /* isCurrent */,
                                false /* isAddUser */, createIsRestricted, canSwitchUsers);
                        checkIfAddUserDisallowedByAdminOnly(guestRecord);
                        records.add(guestRecord);
                    }
                } else {
                    records.add(guestRecord);
                }

                if (canCreateUser) {
                    UserRecord addUserRecord = new UserRecord(null /* info */, null /* picture */,
                            false /* isGuest */, false /* isCurrent */, true /* isAddUser */,
                            createIsRestricted, canSwitchUsers);
                    checkIfAddUserDisallowedByAdminOnly(addUserRecord);
                    records.add(addUserRecord);
                }

                return records;
            }

            @Override
            protected void onPostExecute(ArrayList<UserRecord> userRecords) {
                if (userRecords != null) {
                    mUsers = userRecords;
                    notifyAdapters();
                }
            }
        }.execute((SparseArray) bitmaps);
    }

    private void pauseRefreshUsers() {
        if (!mPauseRefreshUsers) {
            mHandler.postDelayed(mUnpauseRefreshUsers, PAUSE_REFRESH_USERS_TIMEOUT_MS);
            mPauseRefreshUsers = true;
        }
    }

    private void notifyAdapters() {
        for (int i = mAdapters.size() - 1; i >= 0; i--) {
            BaseUserAdapter adapter = mAdapters.get(i).get();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            } else {
                mAdapters.remove(i);
            }
        }
    }

    public boolean isSimpleUserSwitcher() {
        return mSimpleUserSwitcher;
    }

    public boolean useFullscreenUserSwitcher() {
        // Use adb to override:
        // adb shell settings put system enable_fullscreen_user_switcher 0  # Turn it off.
        // adb shell settings put system enable_fullscreen_user_switcher 1  # Turn it on.
        // Restart SystemUI or adb reboot.
        final int DEFAULT = -1;
        final int overrideUseFullscreenUserSwitcher =
                whitelistIpcs(() -> Settings.System.getInt(mContext.getContentResolver(),
                        "enable_fullscreen_user_switcher", DEFAULT));
        if (overrideUseFullscreenUserSwitcher != DEFAULT) {
            return overrideUseFullscreenUserSwitcher != 0;
        }
        // Otherwise default to the build setting.
        return mContext.getResources().getBoolean(R.bool.config_enableFullscreenUserSwitcher);
    }

    public void setResumeUserOnGuestLogout(boolean resume) {
        mResumeUserOnGuestLogout = resume;
    }

    public void logoutCurrentUser() {
        int currentUser = mUserTracker.getUserId();
        if (currentUser != UserHandle.USER_SYSTEM) {
            pauseRefreshUsers();
            ActivityManager.logoutCurrentUser();
        }
    }

    public void removeUserId(int userId) {
        if (userId == UserHandle.USER_SYSTEM) {
            Log.w(TAG, "User " + userId + " could not removed.");
            return;
        }
        if (mUserTracker.getUserId() == userId) {
            switchToUserId(UserHandle.USER_SYSTEM);
        }
        if (mUserManager.removeUser(userId)) {
            refreshUsers(UserHandle.USER_NULL);
        }
    }

    @VisibleForTesting
    void onUserListItemClicked(UserRecord record) {
        int id;
        if (record.isGuest && record.info == null) {
            // No guest user. Create one.
            int guestId = createGuest();
            if (guestId == UserHandle.USER_NULL) {
                // This may happen if we haven't reloaded the user list yet.
                return;
            }
            mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_ADD);
            id = guestId;
        } else if (record.isAddUser) {
            showAddUserDialog();
            return;
        } else {
            id = record.info.id;
        }

        int currUserId = mUserTracker.getUserId();
        if (currUserId == id) {
            if (record.isGuest) {
                showExitGuestDialog(id);
            }
            return;
        }

        if (UserManager.isGuestUserEphemeral()) {
            // If switching from guest, we want to bring up the guest exit dialog instead of switching
            UserInfo currUserInfo = mUserManager.getUserInfo(currUserId);
            if (currUserInfo != null && currUserInfo.isGuest()) {
                showExitGuestDialog(currUserId, record.resolveId());
                return;
            }
        }

        switchToUserId(id);
    }

    protected void switchToUserId(int id) {
        try {
            pauseRefreshUsers();
            ActivityManager.getService().switchUser(id);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
    }

    protected void showExitGuestDialog(int id) {
        int newId = UserHandle.USER_SYSTEM;
        if (mResumeUserOnGuestLogout && mLastNonGuestUser != UserHandle.USER_SYSTEM) {
            UserInfo info = mUserManager.getUserInfo(mLastNonGuestUser);
            if (info != null && info.isEnabled() && info.supportsSwitchToByUser()) {
                newId = info.id;
            }
        }
        showExitGuestDialog(id, newId);
    }

    protected void showExitGuestDialog(int id, int targetId) {
        if (mExitGuestDialog != null && mExitGuestDialog.isShowing()) {
            mExitGuestDialog.cancel();
        }
        mExitGuestDialog = new ExitGuestDialog(mContext, id, targetId);
        mExitGuestDialog.show();
    }

    public void showAddUserDialog() {
        if (mAddUserDialog != null && mAddUserDialog.isShowing()) {
            mAddUserDialog.cancel();
        }
        mAddUserDialog = new AddUserDialog(mContext);
        mAddUserDialog.show();
    }

    private void listenForCallState() {
        mTelephonyListenerManager.addCallStateListener(mPhoneStateListener);
    }

    private final TelephonyCallback.CallStateListener mPhoneStateListener =
            new TelephonyCallback.CallStateListener() {
        private int mCallState;

        @Override
        public void onCallStateChanged(int state) {
            if (mCallState == state) return;
            if (DEBUG) Log.v(TAG, "Call state changed: " + state);
            mCallState = state;
            refreshUsers(UserHandle.USER_NULL);
        }
    };

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.v(TAG, "Broadcast: a=" + intent.getAction()
                       + " user=" + intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1));
            }

            boolean unpauseRefreshUsers = false;
            int forcePictureLoadForId = UserHandle.USER_NULL;

            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                if (mExitGuestDialog != null && mExitGuestDialog.isShowing()) {
                    mExitGuestDialog.cancel();
                    mExitGuestDialog = null;
                }

                final int currentId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                final UserInfo userInfo = mUserManager.getUserInfo(currentId);
                final int N = mUsers.size();
                for (int i = 0; i < N; i++) {
                    UserRecord record = mUsers.get(i);
                    if (record.info == null) continue;
                    boolean shouldBeCurrent = record.info.id == currentId;
                    if (record.isCurrent != shouldBeCurrent) {
                        mUsers.set(i, record.copyWithIsCurrent(shouldBeCurrent));
                    }
                    if (shouldBeCurrent && !record.isGuest) {
                        mLastNonGuestUser = record.info.id;
                    }
                    if ((userInfo == null || !userInfo.isAdmin()) && record.isRestricted) {
                        // Immediately remove restricted records in case the AsyncTask is too slow.
                        mUsers.remove(i);
                        i--;
                    }
                }
                notifyAdapters();

                // Disconnect from the old secondary user's service
                if (mSecondaryUser != UserHandle.USER_NULL) {
                    context.stopServiceAsUser(mSecondaryUserServiceIntent,
                            UserHandle.of(mSecondaryUser));
                    mSecondaryUser = UserHandle.USER_NULL;
                }
                // Connect to the new secondary user's service (purely to ensure that a persistent
                // SystemUI application is created for that user)
                if (userInfo != null && userInfo.id != UserHandle.USER_SYSTEM) {
                    context.startServiceAsUser(mSecondaryUserServiceIntent,
                            UserHandle.of(userInfo.id));
                    mSecondaryUser = userInfo.id;
                }
                unpauseRefreshUsers = true;
                if (mGuestUserAutoCreated) {
                    // Guest user must be scheduled for creation AFTER switching to the target user.
                    // This avoids lock contention which will produce UX bugs on the keyguard
                    // (b/193933686).
                    // TODO(b/191067027): Move guest user recreation to system_server
                    guaranteeGuestPresent();
                }
            } else if (Intent.ACTION_USER_INFO_CHANGED.equals(intent.getAction())) {
                forcePictureLoadForId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL);
            } else if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                // Unlocking the system user may require a refresh
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (userId != UserHandle.USER_SYSTEM) {
                    return;
                }
            }
            refreshUsers(forcePictureLoadForId);
            if (unpauseRefreshUsers) {
                mUnpauseRefreshUsers.run();
            }
        }
    };

    private final Runnable mUnpauseRefreshUsers = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(this);
            mPauseRefreshUsers = false;
            refreshUsers(UserHandle.USER_NULL);
        }
    };

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UserSwitcherController state:");
        pw.println("  mLastNonGuestUser=" + mLastNonGuestUser);
        pw.print("  mUsers.size="); pw.println(mUsers.size());
        for (int i = 0; i < mUsers.size(); i++) {
            final UserRecord u = mUsers.get(i);
            pw.print("    "); pw.println(u.toString());
        }
        pw.println("mSimpleUserSwitcher=" + mSimpleUserSwitcher);
        pw.println("mGuestUserAutoCreated=" + mGuestUserAutoCreated);
    }

    /** Returns the name of the current user of the phone. */
    public String getCurrentUserName() {
        if (mUsers.isEmpty()) return null;
        UserRecord item = mUsers.get(0);
        if (item == null || item.info == null) return null;
        if (item.isGuest) return mContext.getString(
                com.android.settingslib.R.string.guest_nickname);
        return item.info.name;
    }

    public void onDensityOrFontScaleChanged() {
        refreshUsers(UserHandle.USER_ALL);
    }

    @VisibleForTesting
    public void addAdapter(WeakReference<BaseUserAdapter> adapter) {
        mAdapters.add(adapter);
    }

    @VisibleForTesting
    public ArrayList<UserRecord> getUsers() {
        return mUsers;
    }

    /**
     * Removes guest user and switches to target user. The guest must be the current user and its id
     * must be {@code guestUserId}.
     *
     * <p>If {@code targetUserId} is {@link UserHandle.USER_NULL}, then create a new guest user in
     * the foreground, and immediately switch to it. This is used for wiping the current guest and
     * replacing it with a new one.
     *
     * <p>If {@code targetUserId} is specified, then remove the guest in the background while
     * switching to {@code targetUserId}.
     *
     * <p>If device is configured with {@link
     * com.android.internal.R.bool.config_guestUserAutoCreated}, then after guest user is removed, a
     * new one is created in the background. This has no effect if {@code targetUserId} is {@link
     * UserHandle.USER_NULL}.
     *
     * @param guestUserId id of the guest user to remove
     * @param targetUserId id of the user to switch to after guest is removed. If {@link
     * UserHandle.USER_NULL}, then switch immediately to the newly created guest user.
     */
    public void removeGuestUser(@UserIdInt int guestUserId, @UserIdInt int targetUserId) {
        UserInfo currentUser = mUserTracker.getUserInfo();
        if (currentUser.id != guestUserId) {
            Log.w(TAG, "User requesting to start a new session (" + guestUserId + ")"
                    + " is not current user (" + currentUser.id + ")");
            return;
        }
        if (!currentUser.isGuest()) {
            Log.w(TAG, "User requesting to start a new session (" + guestUserId + ")"
                    + " is not a guest");
            return;
        }

        boolean marked = mUserManager.markGuestForDeletion(currentUser.id);
        if (!marked) {
            Log.w(TAG, "Couldn't mark the guest for deletion for user " + guestUserId);
            return;
        }

        try {
            if (targetUserId == UserHandle.USER_NULL) {
                // Create a new guest in the foreground, and then immediately switch to it
                int newGuestId = createGuest();
                if (newGuestId == UserHandle.USER_NULL) {
                    Log.e(TAG, "Could not create new guest, switching back to system user");
                    switchToUserId(UserHandle.USER_SYSTEM);
                    mUserManager.removeUser(currentUser.id);
                    WindowManagerGlobal.getWindowManagerService().lockNow(/* options= */ null);
                    return;
                }
                switchToUserId(newGuestId);
                mUserManager.removeUser(currentUser.id);
            } else {
                if (mGuestUserAutoCreated) {
                    mGuestIsResetting.set(true);
                }
                switchToUserId(targetUserId);
                mUserManager.removeUser(currentUser.id);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't remove guest because ActivityManager or WindowManager is dead");
            return;
        }
    }

    private void scheduleGuestCreation() {
        if (!mGuestCreationScheduled.compareAndSet(false, true)) {
            return;
        }

        mBgExecutor.execute(() -> {
            int newGuestId = createGuest();
            mGuestCreationScheduled.set(false);
            mGuestIsResetting.set(false);
            if (newGuestId == UserHandle.USER_NULL) {
                Log.w(TAG, "Could not create new guest while exiting existing guest");
                // Refresh users so that we still display "Guest" if
                // config_guestUserAutoCreated=true
                refreshUsers(UserHandle.USER_NULL);
            }
        });

    }

    /**
     * Guarantee guest is present only if the device is provisioned. Otherwise, create a content
     * observer to wait until the device is provisioned, then schedule the guest creation.
     */
    public void schedulePostBootGuestCreation() {
        if (isDeviceAllowedToAddGuest()) {
            guaranteeGuestPresent();
        } else {
            mDeviceProvisionedController.addCallback(mGuaranteeGuestPresentAfterProvisioned);
        }
    }

    private boolean isDeviceAllowedToAddGuest() {
        return mDeviceProvisionedController.isDeviceProvisioned()
                && !mDevicePolicyManager.isDeviceManaged();
    }

    /**
     * If there is no guest on the device, schedule creation of a new guest user in the background.
     */
    private void guaranteeGuestPresent() {
        if (isDeviceAllowedToAddGuest() && mUserManager.findCurrentGuestUser() == null) {
            scheduleGuestCreation();
        }
    }

    /**
     * Creates a guest user and return its multi-user user ID.
     *
     * This method does not check if a guest already exists before it makes a call to
     * {@link UserManager} to create a new one.
     *
     * @return The multi-user user ID of the newly created guest user, or
     * {@link UserHandle.USER_NULL} if the guest couldn't be created.
     */
    public @UserIdInt int createGuest() {
        UserInfo guest;
        try {
            guest = mUserManager.createGuest(mContext,
                    mContext.getString(com.android.settingslib.R.string.guest_nickname));
        } catch (UserManager.UserOperationException e) {
            Log.e(TAG, "Couldn't create guest user", e);
            return UserHandle.USER_NULL;
        }
        if (guest == null) {
            Log.e(TAG, "Couldn't create guest, most likely because there already exists one");
            return UserHandle.USER_NULL;
        }
        return guest.id;
    }

    public static abstract class BaseUserAdapter extends BaseAdapter {

        final UserSwitcherController mController;
        private final KeyguardStateController mKeyguardStateController;

        protected BaseUserAdapter(UserSwitcherController controller) {
            mController = controller;
            mKeyguardStateController = controller.mKeyguardStateController;
            controller.addAdapter(new WeakReference<>(this));
        }

        protected ArrayList<UserRecord> getUsers() {
            return mController.getUsers();
        }

        public int getUserCount() {
            return countUsers(false);
        }

        @Override
        public int getCount() {
            return countUsers(true);
        }

        private int countUsers(boolean includeGuest) {
            boolean keyguardShowing = mKeyguardStateController.isShowing();
            final int userSize = getUsers().size();
            int count = 0;
            for (int i = 0; i < userSize; i++) {
                if (getUsers().get(i).isGuest && !includeGuest) {
                    continue;
                }
                if (getUsers().get(i).isRestricted && keyguardShowing) {
                    break;
                }
                count++;
            }
            return count;
        }

        @Override
        public UserRecord getItem(int position) {
            return getUsers().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        /**
         * It handles click events on user list items.
         */
        public void onUserListItemClicked(UserRecord record) {
            mController.onUserListItemClicked(record);
        }

        public String getName(Context context, UserRecord item) {
            if (item.isGuest) {
                if (item.isCurrent) {
                    return context.getString(mController.mGuestUserAutoCreated
                            ? com.android.settingslib.R.string.guest_reset_guest
                            : com.android.settingslib.R.string.guest_exit_guest);
                } else {
                    if (item.info != null) {
                        return context.getString(com.android.settingslib.R.string.guest_nickname);
                    } else {
                        if (mController.mGuestUserAutoCreated) {
                            // If mGuestIsResetting=true, we expect the guest user to be created
                            // shortly, so display a "Resetting guest..." as an indicator that we
                            // are busy. Otherwise, if mGuestIsResetting=false, we probably failed
                            // to create a guest at some point. In this case, always show guest
                            // nickname instead of "Add guest" to make it seem as though the device
                            // always has a guest ready for use.
                            return context.getString(
                                    mController.mGuestIsResetting.get()
                                            ? com.android.settingslib.R.string.guest_resetting
                                            : com.android.settingslib.R.string.guest_nickname);
                        } else {
                            return context.getString(
                                    com.android.settingslib.R.string.guest_new_guest);
                        }
                    }
                }
            } else if (item.isAddUser) {
                return context.getString(R.string.user_add_user);
            } else {
                return item.info.name;
            }
        }

        protected static ColorFilter getDisabledUserAvatarColorFilter() {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);   // 0 - grayscale
            return new ColorMatrixColorFilter(matrix);
        }

        protected static Drawable getIconDrawable(Context context, UserRecord item) {
            int iconRes;
            if (item.isAddUser) {
                iconRes = R.drawable.ic_add_circle;
            } else if (item.isGuest) {
                iconRes = R.drawable.ic_avatar_guest_user;
            } else {
                iconRes = R.drawable.ic_avatar_user;
            }

            return context.getDrawable(iconRes);
        }

        public void refresh() {
            mController.refreshUsers(UserHandle.USER_NULL);
        }
    }

    private void checkIfAddUserDisallowedByAdminOnly(UserRecord record) {
        EnforcedAdmin admin = RestrictedLockUtilsInternal.checkIfRestrictionEnforced(mContext,
                UserManager.DISALLOW_ADD_USER, mUserTracker.getUserId());
        if (admin != null && !RestrictedLockUtilsInternal.hasBaseUserRestriction(mContext,
                UserManager.DISALLOW_ADD_USER, mUserTracker.getUserId())) {
            record.isDisabledByAdmin = true;
            record.enforcedAdmin = admin;
        } else {
            record.isDisabledByAdmin = false;
            record.enforcedAdmin = null;
        }
    }

    private boolean shouldUseSimpleUserSwitcher() {
        int defaultSimpleUserSwitcher = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_expandLockScreenUserSwitcher) ? 1 : 0;
        return Settings.Global.getInt(mContext.getContentResolver(),
                SIMPLE_USER_SWITCHER_GLOBAL_SETTING, defaultSimpleUserSwitcher) != 0;
    }

    public void startActivity(Intent intent) {
        mActivityStarter.startActivity(intent, true);
    }

    public static final class UserRecord {
        public final UserInfo info;
        public final Bitmap picture;
        public final boolean isGuest;
        public final boolean isCurrent;
        public final boolean isAddUser;
        /** If true, the record is only visible to the owner and only when unlocked. */
        public final boolean isRestricted;
        public boolean isDisabledByAdmin;
        public EnforcedAdmin enforcedAdmin;
        public boolean isSwitchToEnabled;

        public UserRecord(UserInfo info, Bitmap picture, boolean isGuest, boolean isCurrent,
                boolean isAddUser, boolean isRestricted, boolean isSwitchToEnabled) {
            this.info = info;
            this.picture = picture;
            this.isGuest = isGuest;
            this.isCurrent = isCurrent;
            this.isAddUser = isAddUser;
            this.isRestricted = isRestricted;
            this.isSwitchToEnabled = isSwitchToEnabled;
        }

        public UserRecord copyWithIsCurrent(boolean _isCurrent) {
            return new UserRecord(info, picture, isGuest, _isCurrent, isAddUser, isRestricted,
                    isSwitchToEnabled);
        }

        public int resolveId() {
            if (isGuest || info == null) {
                return UserHandle.USER_NULL;
            }
            return info.id;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UserRecord(");
            if (info != null) {
                sb.append("name=\"").append(info.name).append("\" id=").append(info.id);
            } else {
                if (isGuest) {
                    sb.append("<add guest placeholder>");
                } else if (isAddUser) {
                    sb.append("<add user placeholder>");
                }
            }
            if (isGuest) sb.append(" <isGuest>");
            if (isAddUser) sb.append(" <isAddUser>");
            if (isCurrent) sb.append(" <isCurrent>");
            if (picture != null) sb.append(" <hasPicture>");
            if (isRestricted) sb.append(" <isRestricted>");
            if (isDisabledByAdmin) {
                sb.append(" <isDisabledByAdmin>");
                sb.append(" enforcedAdmin=").append(enforcedAdmin);
            }
            if (isSwitchToEnabled) {
                sb.append(" <isSwitchToEnabled>");
            }
            sb.append(')');
            return sb.toString();
        }
    }

    public static class UserDetailAdapter implements DetailAdapter {
        private final Intent USER_SETTINGS_INTENT = new Intent(Settings.ACTION_USER_SETTINGS);

        private final Context mContext;
        private final Provider<UserDetailView.Adapter> mUserDetailViewAdapterProvider;

        @Inject
        UserDetailAdapter(Context context,
                Provider<UserDetailView.Adapter> userDetailViewAdapterProvider) {
            mContext = context;
            mUserDetailViewAdapterProvider = userDetailViewAdapterProvider;
        }

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_user_title);
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            UserDetailView v;
            if (!(convertView instanceof UserDetailView)) {
                v = UserDetailView.inflate(context, parent, false);
                v.setAdapter(mUserDetailViewAdapterProvider.get());
            } else {
                v = (UserDetailView) convertView;
            }
            v.refreshAdapter();
            return v;
        }

        @Override
        public Intent getSettingsIntent() {
            return USER_SETTINGS_INTENT;
        }

        @Override
        public int getSettingsText() {
            return R.string.quick_settings_more_user_settings;
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public void setToggleState(boolean state) {
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.QS_USERDETAIL;
        }

        @Override
        public UiEventLogger.UiEventEnum openDetailEvent() {
            return QSUserSwitcherEvent.QS_USER_DETAIL_OPEN;
        }

        @Override
        public UiEventLogger.UiEventEnum closeDetailEvent() {
            return QSUserSwitcherEvent.QS_USER_DETAIL_CLOSE;
        }

        @Override
        public UiEventLogger.UiEventEnum moreSettingsEvent() {
            return QSUserSwitcherEvent.QS_USER_MORE_SETTINGS;
        }
    };

    private final KeyguardStateController.Callback mCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {

                    // When Keyguard is going away, we don't need to update our items immediately
                    // which
                    // helps making the transition faster.
                    if (!mKeyguardStateController.isShowing()) {
                        mHandler.post(UserSwitcherController.this::notifyAdapters);
                    } else {
                        notifyAdapters();
                    }
                }
            };

    private final DeviceProvisionedController.DeviceProvisionedListener
            mGuaranteeGuestPresentAfterProvisioned =
            new DeviceProvisionedController.DeviceProvisionedListener() {
                @Override
                public void onDeviceProvisionedChanged() {
                    if (isDeviceAllowedToAddGuest()) {
                        mBgExecutor.execute(
                                () -> mDeviceProvisionedController.removeCallback(
                                        mGuaranteeGuestPresentAfterProvisioned));
                        guaranteeGuestPresent();
                    }
                }
            };


    private final class ExitGuestDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        private final int mGuestId;
        private final int mTargetId;

        public ExitGuestDialog(Context context, int guestId, int targetId) {
            super(context);
            setTitle(mGuestUserAutoCreated
                    ? com.android.settingslib.R.string.guest_reset_guest_dialog_title
                    : R.string.guest_exit_guest_dialog_title);
            setMessage(context.getString(R.string.guest_exit_guest_dialog_message));
            setButton(DialogInterface.BUTTON_NEGATIVE,
                    context.getString(android.R.string.cancel), this);
            setButton(DialogInterface.BUTTON_POSITIVE,
                    context.getString(mGuestUserAutoCreated
                            ? com.android.settingslib.R.string.guest_reset_guest_confirm_button
                            : R.string.guest_exit_guest_dialog_remove), this);
            SystemUIDialog.setWindowOnTop(this);
            setCanceledOnTouchOutside(false);
            mGuestId = guestId;
            mTargetId = targetId;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            int penalty = which == BUTTON_NEGATIVE ? FalsingManager.NO_PENALTY
                    : FalsingManager.HIGH_PENALTY;
            if (mFalsingManager.isFalseTap(penalty)) {
                return;
            }
            if (which == BUTTON_NEGATIVE) {
                cancel();
            } else {
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_REMOVE);
                dismiss();
                removeGuestUser(mGuestId, mTargetId);
            }
        }
    }

    @VisibleForTesting
    final class AddUserDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        public AddUserDialog(Context context) {
            super(context);
            setTitle(R.string.user_add_user_title);
            setMessage(context.getString(R.string.user_add_user_message_short));
            setButton(DialogInterface.BUTTON_NEGATIVE,
                    context.getString(android.R.string.cancel), this);
            setButton(DialogInterface.BUTTON_POSITIVE,
                    context.getString(android.R.string.ok), this);
            SystemUIDialog.setWindowOnTop(this);
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            int penalty = which == BUTTON_NEGATIVE ? FalsingManager.NO_PENALTY
                    : FalsingManager.MODERATE_PENALTY;
            if (mFalsingManager.isFalseTap(penalty)) {
                return;
            }
            if (which == BUTTON_NEGATIVE) {
                cancel();
            } else {
                dismiss();
                if (ActivityManager.isUserAMonkey()) {
                    return;
                }
                Intent intent = CreateUserActivity.createIntentForStart(getContext());

                // There are some differences between ActivityStarter and ActivityTaskManager in
                // terms of how they start an activity. ActivityStarter hides the notification bar
                // before starting the activity to make sure nothing is in front of the new
                // activity. ActivityStarter also tries to unlock the device if it's locked.
                // When locked with PIN/pattern/password then it shows the prompt, if there are no
                // security steps then it dismisses the keyguard and then starts the activity.
                // ActivityTaskManager doesn't hide the notification bar or unlocks the device, but
                // it can start an activity on top of the locked screen.
                if (!mKeyguardStateController.isUnlocked()
                        && !mKeyguardStateController.canDismissLockScreen()) {
                    // Device is locked and can't be unlocked without a PIN/pattern/password so we
                    // need to use ActivityTaskManager to start the activity on top of the locked
                    // screen.
                    try {
                        mActivityTaskManager.startActivity(null,
                                mContext.getBasePackageName(), mContext.getAttributionTag(), intent,
                                intent.resolveTypeIfNeeded(mContext.getContentResolver()), null,
                                null, 0, 0, null, null);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                        Log.e(TAG, "Couldn't start create user activity", e);
                    }
                } else {
                    mActivityStarter.startActivity(intent, true);
                }
            }
        }
    }
}

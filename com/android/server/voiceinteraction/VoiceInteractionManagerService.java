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

package com.android.server.voiceinteraction;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.KeyphraseMetadata;
import android.hardware.soundtrigger.ModelParams;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModelParamRange;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.media.permission.IdentityContext;
import android.media.permission.PermissionUtil;
import android.media.permission.SafeCloseable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SharedMemory;
import android.os.ShellCallback;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionManagerInternal;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionServiceInfo;
import android.service.voice.VoiceInteractionSession;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceActionCheckCallback;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractionSoundTriggerSession;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import com.android.server.soundtrigger.SoundTriggerInternal;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * SystemService that publishes an IVoiceInteractionManagerService.
 */
public class VoiceInteractionManagerService extends SystemService {
    static final String TAG = "VoiceInteractionManager";
    static final boolean DEBUG = false;

    final Context mContext;
    final ContentResolver mResolver;
    final DatabaseHelper mDbHelper;
    final ActivityManagerInternal mAmInternal;
    final ActivityTaskManagerInternal mAtmInternal;
    final UserManagerInternal mUserManagerInternal;
    final ArrayMap<Integer, VoiceInteractionManagerServiceStub.SoundTriggerSession>
            mLoadedKeyphraseIds = new ArrayMap<>();
    ShortcutServiceInternal mShortcutServiceInternal;
    SoundTriggerInternal mSoundTriggerInternal;

    private final RemoteCallbackList<IVoiceInteractionSessionListener>
            mVoiceInteractionSessionListeners = new RemoteCallbackList<>();

    public VoiceInteractionManagerService(Context context) {
        super(context);
        mContext = context;
        mResolver = context.getContentResolver();
        mDbHelper = new DatabaseHelper(context);
        mServiceStub = new VoiceInteractionManagerServiceStub();
        mAmInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mAtmInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityTaskManagerInternal.class));
        mUserManagerInternal = Objects.requireNonNull(
                LocalServices.getService(UserManagerInternal.class));

        LegacyPermissionManagerInternal permissionManagerInternal = LocalServices.getService(
                LegacyPermissionManagerInternal.class);
        permissionManagerInternal.setVoiceInteractionPackagesProvider(
                new LegacyPermissionManagerInternal.PackagesProvider() {
            @Override
            public String[] getPackages(int userId) {
                mServiceStub.initForUser(userId);
                ComponentName interactor = mServiceStub.getCurInteractor(userId);
                if (interactor != null) {
                    return new String[] {interactor.getPackageName()};
                }
                return null;
            }
        });
    }

    @Override
    public void onStart() {
        publishBinderService(Context.VOICE_INTERACTION_MANAGER_SERVICE, mServiceStub);
        publishLocalService(VoiceInteractionManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            mShortcutServiceInternal = Objects.requireNonNull(
                    LocalServices.getService(ShortcutServiceInternal.class));
            mSoundTriggerInternal = LocalServices.getService(SoundTriggerInternal.class);
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            mServiceStub.systemRunning(isSafeMode());
        }
    }

    @Override
    public boolean isUserSupported(@NonNull TargetUser user) {
        return user.isFull();
    }

    private boolean isUserSupported(@NonNull UserInfo user) {
        return user.isFull();
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        if (DEBUG_USER) Slog.d(TAG, "onUserStarting(" + user + ")");

        mServiceStub.initForUser(user.getUserIdentifier());
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        if (DEBUG_USER) Slog.d(TAG, "onUserUnlocking(" + user + ")");

        mServiceStub.initForUser(user.getUserIdentifier());
        mServiceStub.switchImplementationIfNeeded(false);
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        if (DEBUG_USER) Slog.d(TAG, "onSwitchUser(" + from + " > " + to + ")");

        mServiceStub.switchUser(to.getUserIdentifier());
    }

    class LocalService extends VoiceInteractionManagerInternal {
        @Override
        public void startLocalVoiceInteraction(IBinder callingActivity, Bundle options) {
            if (DEBUG) {
                Slog.i(TAG, "startLocalVoiceInteraction " + callingActivity);
            }
            VoiceInteractionManagerService.this.mServiceStub.startLocalVoiceInteraction(
                    callingActivity, options);
        }

        @Override
        public boolean supportsLocalVoiceInteraction() {
            return VoiceInteractionManagerService.this.mServiceStub.supportsLocalVoiceInteraction();
        }

        @Override
        public void stopLocalVoiceInteraction(IBinder callingActivity) {
            if (DEBUG) {
                Slog.i(TAG, "stopLocalVoiceInteraction " + callingActivity);
            }
            VoiceInteractionManagerService.this.mServiceStub.stopLocalVoiceInteraction(
                    callingActivity);
        }

        @Override
        public boolean hasActiveSession(String packageName) {
            VoiceInteractionManagerServiceImpl impl =
                    VoiceInteractionManagerService.this.mServiceStub.mImpl;
            if (impl == null) {
                return false;
            }

            VoiceInteractionSessionConnection session =
                    impl.mActiveSession;
            if (session == null) {
                return false;
            }

            return TextUtils.equals(packageName, session.mSessionComponentName.getPackageName());
        }

        @Override
        public HotwordDetectionServiceIdentity getHotwordDetectionServiceIdentity() {
            // IMPORTANT: This is called when performing permission checks; do not lock!

            // TODO: Have AppOpsPolicy register a listener instead of calling in here everytime.
            // Then also remove the `volatile`s that were added with this method.

            VoiceInteractionManagerServiceImpl impl =
                    VoiceInteractionManagerService.this.mServiceStub.mImpl;
            if (impl == null) {
                return null;
            }
            HotwordDetectionConnection hotwordDetectionConnection =
                    impl.mHotwordDetectionConnection;
            if (hotwordDetectionConnection == null) {
                return null;
            }
            return hotwordDetectionConnection.mIdentity;
        }
    }

    // implementation entry point and binder service
    private final VoiceInteractionManagerServiceStub mServiceStub;

    class VoiceInteractionManagerServiceStub extends IVoiceInteractionManagerService.Stub {

        volatile VoiceInteractionManagerServiceImpl mImpl;

        private boolean mSafeMode;
        private int mCurUser;
        private boolean mCurUserSupported;

        @GuardedBy("this")
        private boolean mTemporarilyDisabled;

        private final boolean mEnableService;

        VoiceInteractionManagerServiceStub() {
            mEnableService = shouldEnableService(mContext);
            new RoleObserver(mContext.getMainExecutor());
        }

        @Override
        public @NonNull IVoiceInteractionSoundTriggerSession createSoundTriggerSessionAsOriginator(
                @NonNull Identity originatorIdentity, IBinder client) {
            Objects.requireNonNull(originatorIdentity);
            boolean forHotwordDetectionService;
            synchronized (VoiceInteractionManagerServiceStub.this) {
                enforceIsCurrentVoiceInteractionService();
                forHotwordDetectionService =
                        mImpl != null && mImpl.mHotwordDetectionConnection != null;
            }
            IVoiceInteractionSoundTriggerSession session;
            if (forHotwordDetectionService) {
                // Use our own identity and handle the permission checks ourselves. This allows
                // properly checking/noting against the voice interactor or hotword detection
                // service as needed.
                if (HotwordDetectionConnection.DEBUG) {
                    Slog.d(TAG, "Creating a SoundTriggerSession for a HotwordDetectionService");
                }
                originatorIdentity.uid = Binder.getCallingUid();
                originatorIdentity.pid = Binder.getCallingPid();
                session = new SoundTriggerSessionPermissionsDecorator(
                        createSoundTriggerSessionForSelfIdentity(client),
                        mContext,
                        originatorIdentity);
            } else {
                if (HotwordDetectionConnection.DEBUG) {
                    Slog.d(TAG, "Creating a SoundTriggerSession");
                }
                try (SafeCloseable ignored = PermissionUtil.establishIdentityDirect(
                        originatorIdentity)) {
                    session = new SoundTriggerSession(mSoundTriggerInternal.attach(client));
                }
            }
            return new SoundTriggerSessionBinderProxy(session);
        }

        private IVoiceInteractionSoundTriggerSession createSoundTriggerSessionForSelfIdentity(
                IBinder client) {
            Identity identity = new Identity();
            identity.uid = Process.myUid();
            identity.pid = Process.myPid();
            identity.packageName = ActivityThread.currentOpPackageName();
            return Binder.withCleanCallingIdentity(() -> {
                try (SafeCloseable ignored = IdentityContext.create(identity)) {
                    return new SoundTriggerSession(mSoundTriggerInternal.attach(client));
                }
            });
        }

        // TODO: VI Make sure the caller is the current user or profile
        void startLocalVoiceInteraction(final IBinder token, Bundle options) {
            if (mImpl == null) return;

            final long caller = Binder.clearCallingIdentity();
            try {
                mImpl.showSessionLocked(options,
                        VoiceInteractionSession.SHOW_SOURCE_ACTIVITY,
                        new IVoiceInteractionSessionShowCallback.Stub() {
                            @Override
                            public void onFailed() {
                            }

                            @Override
                            public void onShown() {
                                mAtmInternal.onLocalVoiceInteractionStarted(token,
                                        mImpl.mActiveSession.mSession,
                                        mImpl.mActiveSession.mInteractor);
                            }
                        },
                        token);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void stopLocalVoiceInteraction(IBinder callingActivity) {
            if (mImpl == null) return;

            final long caller = Binder.clearCallingIdentity();
            try {
                mImpl.finishLocked(callingActivity, true);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public boolean supportsLocalVoiceInteraction() {
            if (mImpl == null) return false;

            return mImpl.supportsLocalVoiceInteraction();
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                // The activity manager only throws security exceptions, so let's
                // log all others.
                if (!(e instanceof SecurityException)) {
                    Slog.wtf(TAG, "VoiceInteractionManagerService Crash", e);
                }
                throw e;
            }
        }

        public void initForUser(int userHandle) {
            final TimingsTraceAndSlog t;
            if (DEBUG_USER) {
                t = new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
                t.traceBegin("initForUser(" + userHandle + ")");
            } else {
                t = null;
            }
            initForUserNoTracing(userHandle);
            if (t != null) {
                t.traceEnd();
            }
        }

        private void initForUserNoTracing(@UserIdInt int userHandle) {
            if (DEBUG) Slog.d(TAG, "**************** initForUser user=" + userHandle);
            String curInteractorStr = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE, userHandle);
            ComponentName curRecognizer = getCurRecognizer(userHandle);
            VoiceInteractionServiceInfo curInteractorInfo = null;
            if (DEBUG) {
                Slog.d(TAG, "curInteractorStr=" + curInteractorStr
                        + " curRecognizer=" + curRecognizer
                        + " mEnableService=" + mEnableService
                        + " mTemporarilyDisabled=" + mTemporarilyDisabled);
            }
            if (curInteractorStr == null && curRecognizer != null && mEnableService) {
                // If there is no interactor setting, that means we are upgrading
                // from an older platform version.  If the current recognizer is not
                // set or matches the preferred recognizer, then we want to upgrade
                // the user to have the default voice interaction service enabled.
                // Note that we don't do this for low-RAM devices, since we aren't
                // supporting voice interaction services there.
                curInteractorInfo = findAvailInteractor(userHandle, curRecognizer.getPackageName());
                if (curInteractorInfo != null) {
                    // Looks good!  We'll apply this one.  To make it happen, we clear the
                    // recognizer so that we don't think we have anything set and will
                    // re-apply the settings.
                    if (DEBUG) Slog.d(TAG, "No set interactor, found avail: "
                            + curInteractorInfo.getServiceInfo().name);
                    curRecognizer = null;
                }
            }

            // If forceInteractorPackage exists, try to apply the interactor from this package if
            // possible and ignore the regular interactor setting.
            String forceInteractorPackage =
                    getForceVoiceInteractionServicePackage(mContext.getResources());
            if (forceInteractorPackage != null) {
                curInteractorInfo = findAvailInteractor(userHandle, forceInteractorPackage);
                if (curInteractorInfo != null) {
                    // We'll apply this one. Clear the recognizer and re-apply the settings.
                    curRecognizer = null;
                }
            }

            // If we are on a svelte device, make sure an interactor is not currently
            // enabled; if it is, turn it off.
            if (!mEnableService && curInteractorStr != null) {
                if (!TextUtils.isEmpty(curInteractorStr)) {
                    if (DEBUG) Slog.d(TAG, "Svelte device; disabling interactor");
                    setCurInteractor(null, userHandle);
                    curInteractorStr = "";
                }
            }

            if (curRecognizer != null) {
                // If we already have at least a recognizer, then we probably want to
                // leave things as they are...  unless something has disappeared.
                IPackageManager pm = AppGlobals.getPackageManager();
                ServiceInfo interactorInfo = null;
                ServiceInfo recognizerInfo = null;
                ComponentName curInteractor = !TextUtils.isEmpty(curInteractorStr)
                        ? ComponentName.unflattenFromString(curInteractorStr) : null;
                try {
                    recognizerInfo = pm.getServiceInfo(
                            curRecognizer,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                    | PackageManager.GET_META_DATA,
                            userHandle);
                    if (recognizerInfo != null) {
                        RecognitionServiceInfo rsi =
                                RecognitionServiceInfo.parseInfo(
                                        mContext.getPackageManager(), recognizerInfo);
                        if (!TextUtils.isEmpty(rsi.getParseError())) {
                            Log.w(TAG, "Parse error in getAvailableServices: "
                                    + rsi.getParseError());
                            // We still use the recognizer to preserve pre-existing behavior.
                        }
                        if (!rsi.isSelectableAsDefault()) {
                            if (DEBUG) {
                                Slog.d(TAG, "Found non selectableAsDefault recognizer as"
                                        + " default. Unsetting the default and looking for another"
                                        + " one.");
                            }
                            recognizerInfo = null;
                        }
                    }
                    if (curInteractor != null) {
                        interactorInfo = pm.getServiceInfo(curInteractor,
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
                    }
                } catch (RemoteException e) {
                }
                // If the apps for the currently set components still exist, then all is okay.
                if (recognizerInfo != null && (curInteractor == null || interactorInfo != null)) {
                    if (DEBUG) Slog.d(TAG, "Current interactor/recognizer okay, done!");
                    return;
                }
                if (DEBUG) Slog.d(TAG, "Bad recognizer (" + recognizerInfo + ") or interactor ("
                        + interactorInfo + ")");
            }

            // Initializing settings. Look for an interactor first, but only on non-svelte and only
            // if the user hasn't explicitly unset it.
            if (curInteractorInfo == null && mEnableService && !"".equals(curInteractorStr)) {
                curInteractorInfo = findAvailInteractor(userHandle, null);
            }

            if (curInteractorInfo != null) {
                // Eventually it will be an error to not specify this.
                setCurInteractor(new ComponentName(curInteractorInfo.getServiceInfo().packageName,
                        curInteractorInfo.getServiceInfo().name), userHandle);
            } else {
                // No voice interactor, so clear the setting.
                setCurInteractor(null, userHandle);
            }

            initRecognizer(userHandle);
        }

        public void initRecognizer(int userHandle) {
            ComponentName curRecognizer = findAvailRecognizer(null, userHandle);
            if (curRecognizer != null) {
                setCurRecognizer(curRecognizer, userHandle);
            }
        }

        private boolean shouldEnableService(Context context) {
            // VoiceInteractionService should not be enabled on devices that have not declared the
            // recognition feature (including low-ram devices where notLowRam="true" takes effect),
            // unless the device's configuration has explicitly set the config flag for a fixed
            // voice interaction service.
            if (getForceVoiceInteractionServicePackage(context.getResources()) != null) {
                return true;
            }
            return context.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_VOICE_RECOGNIZERS);
        }

        private String getForceVoiceInteractionServicePackage(Resources res) {
            String interactorPackage =
                    res.getString(com.android.internal.R.string.config_forceVoiceInteractionServicePackage);
            return TextUtils.isEmpty(interactorPackage) ? null : interactorPackage;
        }

        public void systemRunning(boolean safeMode) {
            mSafeMode = safeMode;

            mPackageMonitor.register(mContext, BackgroundThread.getHandler().getLooper(),
                    UserHandle.ALL, true);
            new SettingsObserver(UiThread.getHandler());

            synchronized (this) {
                setCurrentUserLocked(ActivityManager.getCurrentUser());
                switchImplementationIfNeededLocked(false);
            }
        }

        private void setCurrentUserLocked(@UserIdInt int userHandle) {
            mCurUser = userHandle;
            final UserInfo userInfo = mUserManagerInternal.getUserInfo(mCurUser);
            mCurUserSupported = isUserSupported(userInfo);
        }

        public void switchUser(@UserIdInt int userHandle) {
            FgThread.getHandler().post(() -> {
                synchronized (this) {
                    setCurrentUserLocked(userHandle);
                    switchImplementationIfNeededLocked(false);
                }
            });
        }

        void switchImplementationIfNeeded(boolean force) {
            synchronized (this) {
                switchImplementationIfNeededLocked(force);
            }
        }

        void switchImplementationIfNeededLocked(boolean force) {
            if (!mCurUserSupported) {
                if (DEBUG_USER) {
                    Slog.d(TAG, "switchImplementationIfNeeded(): skipping: force= " + force
                            + "mCurUserSupported=" + mCurUserSupported);
                }
                if (mImpl != null) {
                    mImpl.shutdownLocked();
                    setImplLocked(null);
                }
                return;
            }

            final TimingsTraceAndSlog t;
            if (DEBUG_USER) {
                t = new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
                t.traceBegin("switchImplementation(" + mCurUser + ")");
            } else {
                t = null;
            }
            switchImplementationIfNeededNoTracingLocked(force);
            if (t != null) {
                t.traceEnd();
            }
        }

        void switchImplementationIfNeededNoTracingLocked(boolean force) {
            if (!mSafeMode) {
                String curService = Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.VOICE_INTERACTION_SERVICE, mCurUser);
                ComponentName serviceComponent = null;
                ServiceInfo serviceInfo = null;
                if (curService != null && !curService.isEmpty()) {
                    try {
                        serviceComponent = ComponentName.unflattenFromString(curService);
                        serviceInfo = AppGlobals.getPackageManager()
                                .getServiceInfo(serviceComponent, 0, mCurUser);
                    } catch (RuntimeException | RemoteException e) {
                        Slog.wtf(TAG, "Bad voice interaction service name " + curService, e);
                        serviceComponent = null;
                        serviceInfo = null;
                    }
                }

                final boolean hasComponent = serviceComponent != null && serviceInfo != null;

                if (mUserManagerInternal.isUserUnlockingOrUnlocked(mCurUser)) {
                    if (hasComponent) {
                        mShortcutServiceInternal.setShortcutHostPackage(TAG,
                                serviceComponent.getPackageName(), mCurUser);
                        mAtmInternal.setAllowAppSwitches(TAG,
                                serviceInfo.applicationInfo.uid, mCurUser);
                    } else {
                        mShortcutServiceInternal.setShortcutHostPackage(TAG, null, mCurUser);
                        mAtmInternal.setAllowAppSwitches(TAG, -1, mCurUser);
                    }
                }

                if (force || mImpl == null || mImpl.mUser != mCurUser
                        || !mImpl.mComponent.equals(serviceComponent)) {
                    unloadAllKeyphraseModels();
                    if (mImpl != null) {
                        mImpl.shutdownLocked();
                    }
                    if (hasComponent) {
                        setImplLocked(new VoiceInteractionManagerServiceImpl(mContext,
                                UiThread.getHandler(), this, mCurUser, serviceComponent));
                        mImpl.startLocked();
                    } else {
                        setImplLocked(null);
                    }
                }
            }
        }

        private List<ResolveInfo> queryInteractorServices(
                @UserIdInt int user,
                @Nullable String packageName) {
            return mContext.getPackageManager().queryIntentServicesAsUser(
                    new Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(packageName),
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    user);
        }

        VoiceInteractionServiceInfo findAvailInteractor(
                @UserIdInt int user,
                @Nullable String packageName) {
            List<ResolveInfo> available = queryInteractorServices(user, packageName);
            int numAvailable = available.size();
            if (numAvailable == 0) {
                Slog.w(TAG, "no available voice interaction services found for user " + user);
                return null;
            }
            // Find first system package.  We never want to allow third party services to
            // be automatically selected, because those require approval of the user.
            VoiceInteractionServiceInfo foundInfo = null;
            for (int i = 0; i < numAvailable; i++) {
                ServiceInfo cur = available.get(i).serviceInfo;
                if ((cur.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    continue;
                }
                VoiceInteractionServiceInfo info =
                        new VoiceInteractionServiceInfo(mContext.getPackageManager(), cur);
                if (info.getParseError() != null) {
                    Slog.w(TAG,
                            "Bad interaction service " + cur.packageName + "/"
                                    + cur.name + ": " + info.getParseError());
                } else if (foundInfo == null) {
                    foundInfo = info;
                } else {
                    Slog.w(TAG, "More than one voice interaction service, "
                            + "picking first "
                            + new ComponentName(
                            foundInfo.getServiceInfo().packageName,
                            foundInfo.getServiceInfo().name)
                            + " over "
                            + new ComponentName(cur.packageName, cur.name));
                }
            }
            return foundInfo;
        }

        ComponentName getCurInteractor(int userHandle) {
            String curInteractor = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE, userHandle);
            if (TextUtils.isEmpty(curInteractor)) {
                return null;
            }
            if (DEBUG) Slog.d(TAG, "getCurInteractor curInteractor=" + curInteractor
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curInteractor);
        }

        void setCurInteractor(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE,
                    comp != null ? comp.flattenToShortString() : "", userHandle);
            if (DEBUG) Slog.d(TAG, "setCurInteractor comp=" + comp
                    + " user=" + userHandle);
        }

        ComponentName findAvailRecognizer(String prefPackage, int userHandle) {
            if (prefPackage == null) {
                prefPackage = getDefaultRecognizer();
            }

            List<RecognitionServiceInfo> available =
                    RecognitionServiceInfo.getAvailableServices(mContext, userHandle);
            if (available.size() == 0) {
                Slog.w(TAG, "no available voice recognition services found for user " + userHandle);
                return null;
            } else {
                List<RecognitionServiceInfo> nonSelectableAsDefault =
                        removeNonSelectableAsDefault(available);
                if (available.size() == 0) {
                    Slog.w(TAG, "No selectableAsDefault recognition services found for user "
                            + userHandle + ". Falling back to non selectableAsDefault ones.");
                    available = nonSelectableAsDefault;
                }
                int numAvailable = available.size();
                if (prefPackage != null) {
                    for (int i = 0; i < numAvailable; i++) {
                        ServiceInfo serviceInfo = available.get(i).getServiceInfo();
                        if (prefPackage.equals(serviceInfo.packageName)) {
                            return new ComponentName(serviceInfo.packageName, serviceInfo.name);
                        }
                    }
                }
                if (numAvailable > 1) {
                    Slog.w(TAG, "more than one voice recognition service found, picking first");
                }

                ServiceInfo serviceInfo = available.get(0).getServiceInfo();
                return new ComponentName(serviceInfo.packageName, serviceInfo.name);
            }
        }

        private List<RecognitionServiceInfo> removeNonSelectableAsDefault(
                List<RecognitionServiceInfo> services) {
            List<RecognitionServiceInfo> nonSelectableAsDefault = new ArrayList<>();
            for (int i = services.size() - 1; i >= 0; i--) {
                if (!services.get(i).isSelectableAsDefault()) {
                    nonSelectableAsDefault.add(services.remove(i));
                }
            }
            return nonSelectableAsDefault;
        }

        @Nullable
        public String getDefaultRecognizer() {
            String recognizer = mContext.getString(R.string.config_systemSpeechRecognizer);
            return TextUtils.isEmpty(recognizer) ? null : recognizer;
        }

        ComponentName getCurRecognizer(int userHandle) {
            String curRecognizer = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_RECOGNITION_SERVICE, userHandle);
            if (TextUtils.isEmpty(curRecognizer)) {
                return null;
            }
            if (DEBUG) Slog.d(TAG, "getCurRecognizer curRecognizer=" + curRecognizer
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curRecognizer);
        }

        void setCurRecognizer(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.VOICE_RECOGNITION_SERVICE,
                    comp != null ? comp.flattenToShortString() : "", userHandle);
            if (DEBUG) Slog.d(TAG, "setCurRecognizer comp=" + comp
                    + " user=" + userHandle);
        }

        ComponentName getCurAssistant(int userHandle) {
            String curAssistant = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.ASSISTANT, userHandle);
            if (TextUtils.isEmpty(curAssistant)) {
                return null;
            }
            if (DEBUG) Slog.d(TAG, "getCurAssistant curAssistant=" + curAssistant
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curAssistant);
        }

        void resetCurAssistant(int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.ASSISTANT, null, userHandle);
        }

        void forceRestartHotwordDetector() {
            mImpl.forceRestartHotwordDetector();
        }

        // Called by Shell command
        void setDebugHotwordLogging(boolean logging) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "setTemporaryLogging without running voice interaction service");
                    return;
                }
                mImpl.setDebugHotwordLoggingLocked(logging);
            }
        }

        @Override
        public void showSession(Bundle args, int flags) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.showSessionLocked(args, flags, null, null);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean deliverNewSession(IBinder token, IVoiceInteractionSession session,
                IVoiceInteractor interactor) {
            synchronized (this) {
                if (mImpl == null) {
                    throw new SecurityException(
                            "deliverNewSession without running voice interaction service");
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.deliverNewSessionLocked(token, session, interactor);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean showSessionFromSession(IBinder token, Bundle sessionArgs, int flags) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "showSessionFromSession without running voice interaction service");
                    return false;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.showSessionLocked(sessionArgs, flags, null, null);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean hideSessionFromSession(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "hideSessionFromSession without running voice interaction service");
                    return false;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.hideSessionLocked();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int startVoiceActivity(IBinder token, Intent intent, String resolvedType,
                String callingFeatureId) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "startVoiceActivity without running voice interaction service");
                    return ActivityManager.START_CANCELED;
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.startVoiceActivityLocked(callingFeatureId, callingPid, callingUid,
                            token, intent, resolvedType);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int startAssistantActivity(IBinder token, Intent intent, String resolvedType,
                String callingFeatureId) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "startAssistantActivity without running voice interaction service");
                    return ActivityManager.START_CANCELED;
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.startAssistantActivityLocked(callingFeatureId, callingPid,
                            callingUid, token, intent, resolvedType);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void requestDirectActions(@NonNull IBinder token, int taskId,
                @NonNull IBinder assistToken, @Nullable RemoteCallback cancellationCallback,
                @NonNull RemoteCallback resultCallback) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "requestDirectActions without running voice interaction service");
                    resultCallback.sendResult(null);
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.requestDirectActionsLocked(token, taskId, assistToken,
                            cancellationCallback, resultCallback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void performDirectAction(@NonNull IBinder token, @NonNull String actionId,
                @NonNull Bundle arguments, int taskId, IBinder assistToken,
                @Nullable RemoteCallback cancellationCallback,
                @NonNull RemoteCallback resultCallback) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "performDirectAction without running voice interaction service");
                    resultCallback.sendResult(null);
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.performDirectActionLocked(token, actionId, arguments, taskId,
                            assistToken, cancellationCallback, resultCallback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void setKeepAwake(IBinder token, boolean keepAwake) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "setKeepAwake without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.setKeepAwakeLocked(token, keepAwake);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void closeSystemDialogs(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "closeSystemDialogs without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.closeSystemDialogsLocked(token);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void finish(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "finish without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.finishLocked(token, false);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void setDisabledShowContext(int flags) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "setDisabledShowContext without running voice interaction service");
                    return;
                }
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.setDisabledShowContextLocked(callingUid, flags);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int getDisabledShowContext() {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "getDisabledShowContext without running voice interaction service");
                    return 0;
                }
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.getDisabledShowContextLocked(callingUid);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int getUserDisabledShowContext() {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG,
                            "getUserDisabledShowContext without running voice interaction service");
                    return 0;
                }
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.getUserDisabledShowContextLocked(callingUid);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void setDisabled(boolean disabled) {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                if (mTemporarilyDisabled == disabled) {
                    if (DEBUG) Slog.d(TAG, "setDisabled(): already " + disabled);
                    return;
                }
                mTemporarilyDisabled = disabled;
                if (mTemporarilyDisabled) {
                    Slog.i(TAG, "setDisabled(): temporarily disabling and hiding current session");
                    try {
                        hideCurrentSession();
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to call hideCurrentSession", e);
                    }
                } else {
                    Slog.i(TAG, "setDisabled(): re-enabling");
                }
            }
        }

        //----------------- Hotword Detection/Validation APIs --------------------------------//

        @Override
        public void updateState(
                @NonNull Identity voiceInteractorIdentity,
                @Nullable PersistableBundle options,
                @Nullable SharedMemory sharedMemory,
                IHotwordRecognitionStatusCallback callback) {
            enforceCallingPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION);
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "updateState without running voice interaction service");
                    return;
                }

                voiceInteractorIdentity.uid = Binder.getCallingUid();
                voiceInteractorIdentity.pid = Binder.getCallingPid();

                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.updateStateLocked(
                            voiceInteractorIdentity, options, sharedMemory, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void shutdownHotwordDetectionService() {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG,
                            "shutdownHotwordDetectionService without running voice"
                                    + " interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.shutdownHotwordDetectionServiceLocked();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void startListeningFromMic(
                AudioFormat audioFormat,
                IMicrophoneHotwordDetectionVoiceInteractionCallback callback)
                throws RemoteException {
            enforceCallingPermission(Manifest.permission.RECORD_AUDIO);
            enforceCallingPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD);
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "startListeningFromMic without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.startListeningFromMicLocked(audioFormat, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void startListeningFromExternalSource(
                ParcelFileDescriptor audioStream,
                AudioFormat audioFormat,
                PersistableBundle options,
                IMicrophoneHotwordDetectionVoiceInteractionCallback callback)
                throws RemoteException {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "startListeningFromExternalSource without running voice"
                            + " interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.startListeningFromExternalSourceLocked(
                            audioStream, audioFormat, options, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void stopListeningFromMic() throws RemoteException {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "stopListeningFromMic without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.stopListeningFromMicLocked();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void triggerHardwareRecognitionEventForTest(
                SoundTrigger.KeyphraseRecognitionEvent event,
                IHotwordRecognitionStatusCallback callback)
                throws RemoteException {
            enforceCallingPermission(Manifest.permission.RECORD_AUDIO);
            enforceCallingPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD);
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "triggerHardwareRecognitionEventForTest without running"
                            + " voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.triggerHardwareRecognitionEventForTestLocked(event, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }
        //----------------- Model management APIs --------------------------------//

        @Override
        public KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, String bcp47Locale) {
            enforceCallerAllowedToEnrollVoiceModel();

            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in getKeyphraseSoundModel");
            }

            final int callingUserId = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            try {
                return mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUserId, bcp47Locale);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int updateKeyphraseSoundModel(KeyphraseSoundModel model) {
            enforceCallerAllowedToEnrollVoiceModel();
            if (model == null) {
                throw new IllegalArgumentException("Model must not be null");
            }

            final long caller = Binder.clearCallingIdentity();
            try {
                if (mDbHelper.updateKeyphraseSoundModel(model)) {
                    synchronized (this) {
                        // Notify the voice interaction service of a change in sound models.
                        if (mImpl != null && mImpl.mService != null) {
                            mImpl.notifySoundModelsChangedLocked();
                        }
                    }
                    return SoundTriggerInternal.STATUS_OK;
                } else {
                    return SoundTriggerInternal.STATUS_ERROR;
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int deleteKeyphraseSoundModel(int keyphraseId, String bcp47Locale) {
            enforceCallerAllowedToEnrollVoiceModel();

            if (bcp47Locale == null) {
                throw new IllegalArgumentException(
                        "Illegal argument(s) in deleteKeyphraseSoundModel");
            }

            final int callingUserId = UserHandle.getCallingUserId();
            boolean deleted = false;
            final long caller = Binder.clearCallingIdentity();
            try {
                SoundTriggerSession session = mLoadedKeyphraseIds.get(keyphraseId);
                if (session != null) {
                    int unloadStatus = session.unloadKeyphraseModel(keyphraseId);
                    if (unloadStatus != SoundTriggerInternal.STATUS_OK) {
                        Slog.w(TAG, "Unable to unload keyphrase sound model:" + unloadStatus);
                    }
                }
                deleted = mDbHelper.deleteKeyphraseSoundModel(keyphraseId, callingUserId,
                        bcp47Locale);
                return deleted ? SoundTriggerInternal.STATUS_OK : SoundTriggerInternal.STATUS_ERROR;
            } finally {
                if (deleted) {
                    synchronized (this) {
                        // Notify the voice interaction service of a change in sound models.
                        if (mImpl != null && mImpl.mService != null) {
                            mImpl.notifySoundModelsChangedLocked();
                        }
                        mLoadedKeyphraseIds.remove(keyphraseId);
                    }
                }
                Binder.restoreCallingIdentity(caller);
            }
        }

        //----------------- SoundTrigger APIs --------------------------------//
        @Override
        public boolean isEnrolledForKeyphrase(int keyphraseId, String bcp47Locale) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();
            }

            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in isEnrolledForKeyphrase");
            }

            final int callingUserId = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            try {
                KeyphraseSoundModel model =
                        mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUserId, bcp47Locale);
                return model != null;
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Nullable
        public KeyphraseMetadata getEnrolledKeyphraseMetadata(String keyphrase,
                String bcp47Locale) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();
            }

            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in isEnrolledForKeyphrase");
            }

            final int callingUserId = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            try {
                KeyphraseSoundModel model =
                        mDbHelper.getKeyphraseSoundModel(keyphrase, callingUserId, bcp47Locale);
                if (model == null) {
                    return null;
                }

                for (SoundTrigger.Keyphrase phrase : model.getKeyphrases()) {
                    if (keyphrase.equals(phrase.getText())) {
                        ArraySet<Locale> locales = new ArraySet<>();
                        locales.add(phrase.getLocale());
                        return new KeyphraseMetadata(phrase.getId(), phrase.getText(), locales,
                                phrase.getRecognitionModes());
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }

            return null;
        }

        /**
         * Implementation of SoundTriggerSession. Does not implement {@link #asBinder()} as it's
         * intended to be wrapped by an {@link IVoiceInteractionSoundTriggerSession.Stub} object.
         */
        private class SoundTriggerSession implements IVoiceInteractionSoundTriggerSession {
            final SoundTriggerInternal.Session mSession;
            private IHotwordRecognitionStatusCallback mSessionExternalCallback;
            private IRecognitionStatusCallback mSessionInternalCallback;

            SoundTriggerSession(
                    SoundTriggerInternal.Session session) {
                mSession = session;
            }

            @Override
            public ModuleProperties getDspModuleProperties() {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();

                    final long caller = Binder.clearCallingIdentity();
                    try {
                        return mSession.getModuleProperties();
                    } finally {
                        Binder.restoreCallingIdentity(caller);
                    }
                }
            }

            @Override
            public int startRecognition(int keyphraseId, String bcp47Locale,
                    IHotwordRecognitionStatusCallback callback, RecognitionConfig recognitionConfig,
                    boolean runInBatterySaverMode) {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();

                    if (callback == null || recognitionConfig == null || bcp47Locale == null) {
                        throw new IllegalArgumentException("Illegal argument(s) in startRecognition");
                    }
                    if (runInBatterySaverMode) {
                        enforceCallingPermission(
                                Manifest.permission.SOUND_TRIGGER_RUN_IN_BATTERY_SAVER);
                    }
                }

                final int callingUserId = UserHandle.getCallingUserId();
                final long caller = Binder.clearCallingIdentity();
                try {
                    KeyphraseSoundModel soundModel =
                            mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUserId, bcp47Locale);
                    if (soundModel == null
                            || soundModel.getUuid() == null
                            || soundModel.getKeyphrases() == null) {
                        Slog.w(TAG, "No matching sound model found in startRecognition");
                        return SoundTriggerInternal.STATUS_ERROR;
                    } else {
                        // Regardless of the status of the start recognition, we need to make sure
                        // that we unload this model if needed later.
                        synchronized (VoiceInteractionManagerServiceStub.this) {
                            mLoadedKeyphraseIds.put(keyphraseId, this);
                            if (mSessionExternalCallback == null
                                    || mSessionInternalCallback == null
                                    || callback.asBinder() != mSessionExternalCallback.asBinder()) {
                                mSessionInternalCallback = createSoundTriggerCallbackLocked(
                                        callback);
                                mSessionExternalCallback = callback;
                            }
                        }
                        return mSession.startRecognition(keyphraseId, soundModel,
                                mSessionInternalCallback, recognitionConfig, runInBatterySaverMode);
                    }
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            public int stopRecognition(int keyphraseId,
                    IHotwordRecognitionStatusCallback callback) {
                final IRecognitionStatusCallback soundTriggerCallback;
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();
                    if (mSessionExternalCallback == null
                            || mSessionInternalCallback == null
                            || callback.asBinder() != mSessionExternalCallback.asBinder()) {
                        soundTriggerCallback = createSoundTriggerCallbackLocked(callback);
                        Slog.w(TAG, "stopRecognition() called with a different callback than"
                                + "startRecognition()");
                    } else {
                        soundTriggerCallback = mSessionInternalCallback;
                    }
                    mSessionExternalCallback = null;
                    mSessionInternalCallback = null;
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.stopRecognition(keyphraseId, soundTriggerCallback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            public int setParameter(int keyphraseId, @ModelParams int modelParam, int value) {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.setParameter(keyphraseId, modelParam, value);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            public int getParameter(int keyphraseId, @ModelParams int modelParam) {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.getParameter(keyphraseId, modelParam);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            @Nullable
            public ModelParamRange queryParameter(int keyphraseId, @ModelParams int modelParam) {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.queryParameter(keyphraseId, modelParam);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            public IBinder asBinder() {
                throw new UnsupportedOperationException(
                        "This object isn't intended to be used as a Binder.");
            }

            private int unloadKeyphraseModel(int keyphraseId) {
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.unloadKeyphraseModel(keyphraseId);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        private synchronized void unloadAllKeyphraseModels() {
            for (int i = 0; i < mLoadedKeyphraseIds.size(); i++) {
                int id = mLoadedKeyphraseIds.keyAt(i);
                SoundTriggerSession session = mLoadedKeyphraseIds.valueAt(i);
                int status = session.unloadKeyphraseModel(id);
                if (status != SoundTriggerInternal.STATUS_OK) {
                    Slog.w(TAG, "Failed to unload keyphrase " + id + ":" + status);
                }
            }
            mLoadedKeyphraseIds.clear();
        }

        @Override
        public ComponentName getActiveServiceComponentName() {
            synchronized (this) {
                return mImpl != null ? mImpl.mComponent : null;
            }
        }

        @Override
        public boolean showSessionForActiveService(Bundle args, int sourceFlags,
                IVoiceInteractionSessionShowCallback showCallback, IBinder activityToken) {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            if (DEBUG_USER) Slog.d(TAG, "showSessionForActiveService()");

            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "showSessionForActiveService without running voice interaction"
                            + "service");
                    return false;
                }
                if (mTemporarilyDisabled) {
                    Slog.i(TAG, "showSessionForActiveService(): ignored while temporarily "
                            + "disabled");
                    return false;
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.showSessionLocked(args,
                            sourceFlags
                                    | VoiceInteractionSession.SHOW_WITH_ASSIST
                                    | VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
                            showCallback, activityToken);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void hideCurrentSession() throws RemoteException {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);

            if (mImpl == null) {
                return;
            }
            final long caller = Binder.clearCallingIdentity();
            try {
                if (mImpl.mActiveSession != null && mImpl.mActiveSession.mSession != null) {
                    try {
                        mImpl.mActiveSession.mSession.closeSystemDialogs();
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to call closeSystemDialogs", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public void launchVoiceAssistFromKeyguard() {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "launchVoiceAssistFromKeyguard without running voice interaction"
                            + "service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.launchVoiceAssistFromKeyguard();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean isSessionRunning() {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                return mImpl != null && mImpl.mActiveSession != null;
            }
        }

        @Override
        public boolean activeServiceSupportsAssist() {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                return mImpl != null && mImpl.mInfo != null && mImpl.mInfo.getSupportsAssist();
            }
        }

        @Override
        public boolean activeServiceSupportsLaunchFromKeyguard() throws RemoteException {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                return mImpl != null && mImpl.mInfo != null
                        && mImpl.mInfo.getSupportsLaunchFromKeyguard();
            }
        }

        @Override
        public void onLockscreenShown() {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                if (mImpl == null) {
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    if (mImpl.mActiveSession != null && mImpl.mActiveSession.mSession != null) {
                        try {
                            mImpl.mActiveSession.mSession.onLockscreenShown();
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failed to call onLockscreenShown", e);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void registerVoiceInteractionSessionListener(
                IVoiceInteractionSessionListener listener) {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                mVoiceInteractionSessionListeners.register(listener);
            }
        }

        @Override
        public void getActiveServiceSupportedActions(List<String> voiceActions,
                IVoiceActionCheckCallback callback) {
            enforceCallingPermission(Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE);
            synchronized (this) {
                if (mImpl == null) {
                    try {
                        callback.onComplete(null);
                    } catch (RemoteException e) {
                    }
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.getActiveServiceSupportedActions(voiceActions, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        public void onSessionShown() {
            synchronized (this) {
                final int size = mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; ++i) {
                    final IVoiceInteractionSessionListener listener =
                            mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onVoiceSessionShown();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering voice interaction open event.", e);
                    }
                }
                mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        public void onSessionHidden() {
            synchronized (this) {
                final int size = mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; ++i) {
                    final IVoiceInteractionSessionListener listener =
                            mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onVoiceSessionHidden();

                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering voice interaction closed event.", e);
                    }
                }
                mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            synchronized (this) {
                pw.println("VOICE INTERACTION MANAGER (dumpsys voiceinteraction)");
                pw.println("  mEnableService: " + mEnableService);
                pw.println("  mTemporarilyDisabled: " + mTemporarilyDisabled);
                pw.println("  mCurUser: " + mCurUser);
                pw.println("  mCurUserSupported: " + mCurUserSupported);
                dumpSupportedUsers(pw, "  ");
                mDbHelper.dump(pw);
                if (mImpl == null) {
                    pw.println("  (No active implementation)");
                } else {
                    mImpl.dumpLocked(fd, pw, args);
                }
            }

            mSoundTriggerInternal.dump(fd, pw, args);
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new VoiceInteractionManagerServiceShellCommand(mServiceStub)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override
        public void setUiHints(Bundle hints) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                final int size = mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; ++i) {
                    final IVoiceInteractionSessionListener listener =
                            mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onSetUiHints(hints);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering UI hints.", e);
                    }
                }
                mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        private boolean isCallerHoldingPermission(String permission) {
            return mContext.checkCallingOrSelfPermission(permission)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private void enforceCallingPermission(String permission) {
            if (!isCallerHoldingPermission(permission)) {
                throw new SecurityException("Caller does not hold the permission " + permission);
            }
        }

        private void enforceIsCurrentVoiceInteractionService() {
            if (!isCallerCurrentVoiceInteractionService()) {
                throw new
                    SecurityException("Caller is not the current voice interaction service");
            }
        }

        private void enforceCallerAllowedToEnrollVoiceModel() {
            if (isCallerHoldingPermission(Manifest.permission.KEYPHRASE_ENROLLMENT_APPLICATION)) {
                return;
            }

            enforceCallingPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES);
            enforceIsCurrentVoiceInteractionService();
        }

        private boolean isCallerCurrentVoiceInteractionService() {
            return mImpl != null
                    && mImpl.mInfo.getServiceInfo().applicationInfo.uid == Binder.getCallingUid();
        }

        private void setImplLocked(VoiceInteractionManagerServiceImpl impl) {
            mImpl = impl;
            mAtmInternal.notifyActiveVoiceInteractionServiceChanged(
                    getActiveServiceComponentName());
        }

        private IRecognitionStatusCallback createSoundTriggerCallbackLocked(
                IHotwordRecognitionStatusCallback callback) {
            if (mImpl == null) {
                return null;
            }
            return mImpl.createSoundTriggerCallbackLocked(callback);
        }

        class RoleObserver implements OnRoleHoldersChangedListener {
            private PackageManager mPm = mContext.getPackageManager();
            private RoleManager mRm = mContext.getSystemService(RoleManager.class);

            RoleObserver(@NonNull @CallbackExecutor Executor executor) {
                mRm.addOnRoleHoldersChangedListenerAsUser(executor, this, UserHandle.ALL);
                // Sync only if assistant role has been initialized.
                if (mRm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    UserHandle currentUser = UserHandle.of(LocalServices.getService(
                            ActivityManagerInternal.class).getCurrentUserId());
                    onRoleHoldersChanged(RoleManager.ROLE_ASSISTANT, currentUser);
                }
            }

            /**
             * Convert the assistant-role holder into settings. The rest of the system uses the
             * settings.
             *
             * @param roleName the name of the role whose holders are changed
             * @param user the user for this role holder change
             */
            @Override
            public void onRoleHoldersChanged(@NonNull String roleName, @NonNull UserHandle user) {
                if (!roleName.equals(RoleManager.ROLE_ASSISTANT)) {
                    return;
                }

                List<String> roleHolders = mRm.getRoleHoldersAsUser(roleName, user);

                int userId = user.getIdentifier();
                if (roleHolders.isEmpty()) {
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            Settings.Secure.ASSISTANT, "", userId);
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            Settings.Secure.VOICE_INTERACTION_SERVICE, "", userId);
                } else {
                    // Assistant is singleton role
                    String pkg = roleHolders.get(0);

                    // Try to set role holder as VoiceInteractionService
                    for (ResolveInfo resolveInfo : queryInteractorServices(userId, pkg)) {
                        ServiceInfo serviceInfo = resolveInfo.serviceInfo;

                        VoiceInteractionServiceInfo voiceInteractionServiceInfo =
                                new VoiceInteractionServiceInfo(mPm, serviceInfo);
                        if (!voiceInteractionServiceInfo.getSupportsAssist()) {
                            continue;
                        }

                        String serviceComponentName = serviceInfo.getComponentName()
                                .flattenToShortString();
                        if (voiceInteractionServiceInfo.getRecognitionService() == null) {
                            Slog.e(TAG, "The RecognitionService must be set to avoid boot "
                                    + "loop on earlier platform version. Also make sure that this "
                                    + "is a valid RecognitionService when running on Android 11 "
                                    + "or earlier.");
                            serviceComponentName = "";
                        }

                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.ASSISTANT, serviceComponentName, userId);
                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.VOICE_INTERACTION_SERVICE, serviceComponentName,
                                userId);
                        return;
                    }

                    // If no service could be found try to set assist activity
                    final List<ResolveInfo> activities = mPm.queryIntentActivitiesAsUser(
                            new Intent(Intent.ACTION_ASSIST).setPackage(pkg),
                            PackageManager.MATCH_DEFAULT_ONLY
                                    | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);

                    for (ResolveInfo resolveInfo : activities) {
                        ActivityInfo activityInfo = resolveInfo.activityInfo;

                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.ASSISTANT,
                                activityInfo.getComponentName().flattenToShortString(), userId);
                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.VOICE_INTERACTION_SERVICE, "", userId);
                        return;
                    }
                }
            }
        }

        class SettingsObserver extends ContentObserver {
            SettingsObserver(Handler handler) {
                super(handler);
                ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(Settings.Secure.getUriFor(
                        Settings.Secure.VOICE_INTERACTION_SERVICE), false, this,
                        UserHandle.USER_ALL);
            }

            @Override public void onChange(boolean selfChange) {
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    switchImplementationIfNeededLocked(false);
                }
            }
        }

        private void resetServicesIfNoRecognitionService(ComponentName serviceComponent,
                int userHandle) {
            for (ResolveInfo resolveInfo : queryInteractorServices(userHandle,
                    serviceComponent.getPackageName())) {
                VoiceInteractionServiceInfo serviceInfo =
                        new VoiceInteractionServiceInfo(
                                mContext.getPackageManager(),
                                resolveInfo.serviceInfo);
                if (!serviceInfo.getSupportsAssist()) {
                    continue;
                }
                if (serviceInfo.getRecognitionService() == null) {
                    Slog.e(TAG, "The RecognitionService must be set to "
                            + "avoid boot loop on earlier platform version. "
                            + "Also make sure that this is a valid "
                            + "RecognitionService when running on Android 11 "
                            + "or earlier.");
                    setCurInteractor(null, userHandle);
                    resetCurAssistant(userHandle);
                }
            }
        }

        PackageMonitor mPackageMonitor = new PackageMonitor() {
            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
                if (DEBUG) Slog.d(TAG, "onHandleForceStop uid=" + uid + " doit=" + doit);

                int userHandle = UserHandle.getUserId(uid);
                ComponentName curInteractor = getCurInteractor(userHandle);
                ComponentName curRecognizer = getCurRecognizer(userHandle);
                boolean hitInt = false;
                boolean hitRec = false;
                for (String pkg : packages) {
                    if (curInteractor != null && pkg.equals(curInteractor.getPackageName())) {
                        hitInt = true;
                        break;
                    } else if (curRecognizer != null
                            && pkg.equals(curRecognizer.getPackageName())) {
                        hitRec = true;
                        break;
                    }
                }
                if (hitInt && doit) {
                    // The user is force stopping our current interactor.
                    // Clear the current settings and restore default state.
                    synchronized (VoiceInteractionManagerServiceStub.this) {
                        Slog.i(TAG, "Force stopping current voice interactor: "
                                + getCurInteractor(userHandle));
                        unloadAllKeyphraseModels();
                        if (mImpl != null) {
                            mImpl.shutdownLocked();
                            setImplLocked(null);
                        }

                        setCurInteractor(null, userHandle);
                        setCurRecognizer(null, userHandle);
                        resetCurAssistant(userHandle);
                        initForUser(userHandle);
                        switchImplementationIfNeededLocked(true);

                        Context context = getContext();
                        context.getSystemService(RoleManager.class).clearRoleHoldersAsUser(
                                RoleManager.ROLE_ASSISTANT, 0, UserHandle.of(userHandle),
                                context.getMainExecutor(), successful -> {
                                    if (!successful) {
                                        Slog.e(TAG,
                                                "Failed to clear default assistant for force stop");
                                    }
                                });
                    }
                } else if (hitRec && doit) {
                    // We are just force-stopping the current recognizer, which is not
                    // also the current interactor.
                    synchronized (VoiceInteractionManagerServiceStub.this) {
                        Slog.i(TAG, "Force stopping current voice recognizer: "
                                + getCurRecognizer(userHandle));
                        // TODO: Figure out why the interactor was being cleared and document it.
                        setCurInteractor(null, userHandle);
                        initRecognizer(userHandle);
                    }
                }
                return hitInt || hitRec;
            }

            @Override
            public void onHandleUserStop(Intent intent, int userHandle) {
            }

            @Override
            public void onPackageModified(@NonNull String pkgName) {
                // If the package modified is not in the current user, then don't bother making
                // any changes as we are going to do any initialization needed when we switch users.
                if (mCurUser != getChangingUserId()) {
                    return;
                }
                // Package getting updated will be handled by {@link #onSomePackagesChanged}.
                if (isPackageAppearing(pkgName) != PACKAGE_UNCHANGED) {
                    return;
                }
                if (getCurRecognizer(mCurUser) == null) {
                    initRecognizer(mCurUser);
                }
                final String curInteractorStr = Settings.Secure.getStringForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.VOICE_INTERACTION_SERVICE, mCurUser);
                final ComponentName curInteractor = getCurInteractor(mCurUser);
                // If there's no interactor and the user hasn't explicitly unset it, check if the
                // modified package offers one.
                if (curInteractor == null && !"".equals(curInteractorStr)) {
                    final VoiceInteractionServiceInfo availInteractorInfo
                            = findAvailInteractor(mCurUser, pkgName);
                    if (availInteractorInfo != null) {
                        final ComponentName availInteractor = new ComponentName(
                                availInteractorInfo.getServiceInfo().packageName,
                                availInteractorInfo.getServiceInfo().name);
                        setCurInteractor(availInteractor, mCurUser);
                    }
                } else {
                    if (didSomePackagesChange()) {
                        // Package is changed
                        if (curInteractor != null && pkgName.equals(
                                curInteractor.getPackageName())) {
                            switchImplementationIfNeeded(true);
                        }
                    } else {
                        // Only some components are changed
                        if (curInteractor != null
                                && isComponentModified(curInteractor.getClassName())) {
                            switchImplementationIfNeeded(true);
                        }
                    }
                }
            }

            @Override
            public void onSomePackagesChanged() {
                int userHandle = getChangingUserId();
                if (DEBUG) Slog.d(TAG, "onSomePackagesChanged user=" + userHandle);

                synchronized (VoiceInteractionManagerServiceStub.this) {
                    ComponentName curInteractor = getCurInteractor(userHandle);
                    ComponentName curRecognizer = getCurRecognizer(userHandle);
                    ComponentName curAssistant = getCurAssistant(userHandle);
                    if (curRecognizer == null) {
                        // Could a new recognizer appear when we don't have one pre-installed?
                        if (anyPackagesAppearing()) {
                            initRecognizer(userHandle);
                        }
                        return;
                    }

                    if (curInteractor != null) {
                        int change = isPackageDisappearing(curInteractor.getPackageName());
                        if (change == PACKAGE_PERMANENT_CHANGE) {
                            // The currently set interactor is permanently gone; fall back to
                            // the default config.
                            setCurInteractor(null, userHandle);
                            setCurRecognizer(null, userHandle);
                            resetCurAssistant(userHandle);
                            initForUser(userHandle);
                            return;
                        }

                        change = isPackageAppearing(curInteractor.getPackageName());
                        if (change != PACKAGE_UNCHANGED) {
                            resetServicesIfNoRecognitionService(curInteractor, userHandle);
                            // If current interactor is now appearing, for any reason, then
                            // restart our connection with it.
                            if (mImpl != null && curInteractor.getPackageName().equals(
                                    mImpl.mComponent.getPackageName())) {
                                switchImplementationIfNeededLocked(true);
                            }
                        }
                        return;
                    }

                    if (curAssistant != null) {
                        int change = isPackageDisappearing(curAssistant.getPackageName());
                        if (change == PACKAGE_PERMANENT_CHANGE) {
                            // If the currently set assistant is being removed, then we should
                            // reset back to the default state (which is probably that we prefer
                            // to have the default full voice interactor enabled).
                            setCurInteractor(null, userHandle);
                            setCurRecognizer(null, userHandle);
                            resetCurAssistant(userHandle);
                            initForUser(userHandle);
                            return;
                        }
                        change = isPackageAppearing(curAssistant.getPackageName());
                        if (change != PACKAGE_UNCHANGED) {
                            // It is possible to update Assistant without a voice interactor to one
                            // with a voice-interactor. We should make sure the recognition service
                            // is set to avoid boot loop.
                            resetServicesIfNoRecognitionService(curAssistant, userHandle);
                        }
                    }

                    // There is no interactor, so just deal with a simple recognizer.
                    int change = isPackageDisappearing(curRecognizer.getPackageName());
                    if (change == PACKAGE_PERMANENT_CHANGE
                            || change == PACKAGE_TEMPORARY_CHANGE) {
                        setCurRecognizer(findAvailRecognizer(null, userHandle), userHandle);

                    } else if (isPackageModified(curRecognizer.getPackageName())) {
                        setCurRecognizer(findAvailRecognizer(curRecognizer.getPackageName(),
                                userHandle), userHandle);
                    }
                }
            }
        };
    }
}

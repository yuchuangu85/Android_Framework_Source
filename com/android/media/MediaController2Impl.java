/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.media;

import static android.media.SessionCommand2.COMMAND_CODE_SET_VOLUME;
import static android.media.SessionCommand2.COMMAND_CODE_PLAYLIST_ADD_ITEM;
import static android.media.SessionCommand2.COMMAND_CODE_PLAYLIST_REMOVE_ITEM;
import static android.media.SessionCommand2.COMMAND_CODE_PLAYLIST_REPLACE_ITEM;
import static android.media.SessionCommand2.COMMAND_CODE_PLAYLIST_SET_LIST;
import static android.media.SessionCommand2.COMMAND_CODE_PLAYLIST_SET_LIST_METADATA;
import static android.media.SessionCommand2.COMMAND_CODE_PLAYLIST_SET_REPEAT_MODE;
import static android.media.SessionCommand2.COMMAND_CODE_PLAYLIST_SET_SHUFFLE_MODE;
import static android.media.SessionCommand2.COMMAND_CODE_SESSION_PLAY_FROM_MEDIA_ID;
import static android.media.SessionCommand2.COMMAND_CODE_SESSION_PLAY_FROM_SEARCH;
import static android.media.SessionCommand2.COMMAND_CODE_SESSION_PLAY_FROM_URI;
import static android.media.SessionCommand2.COMMAND_CODE_SESSION_PREPARE_FROM_MEDIA_ID;
import static android.media.SessionCommand2.COMMAND_CODE_SESSION_PREPARE_FROM_SEARCH;
import static android.media.SessionCommand2.COMMAND_CODE_SESSION_PREPARE_FROM_URI;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.media.MediaController2;
import android.media.MediaController2.ControllerCallback;
import android.media.MediaController2.PlaybackInfo;
import android.media.MediaItem2;
import android.media.MediaMetadata2;
import android.media.MediaPlaylistAgent.RepeatMode;
import android.media.MediaPlaylistAgent.ShuffleMode;
import android.media.SessionCommand2;
import android.media.MediaSession2.CommandButton;
import android.media.SessionCommandGroup2;
import android.media.MediaSessionService2;
import android.media.Rating2;
import android.media.SessionToken2;
import android.media.update.MediaController2Provider;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.support.annotation.GuardedBy;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class MediaController2Impl implements MediaController2Provider {
    private static final String TAG = "MediaController2";
    private static final boolean DEBUG = true; // TODO(jaewan): Change

    private final MediaController2 mInstance;
    private final Context mContext;
    private final Object mLock = new Object();

    private final MediaController2Stub mControllerStub;
    private final SessionToken2 mToken;
    private final ControllerCallback mCallback;
    private final Executor mCallbackExecutor;
    private final IBinder.DeathRecipient mDeathRecipient;

    @GuardedBy("mLock")
    private SessionServiceConnection mServiceConnection;
    @GuardedBy("mLock")
    private boolean mIsReleased;
    @GuardedBy("mLock")
    private List<MediaItem2> mPlaylist;
    @GuardedBy("mLock")
    private MediaMetadata2 mPlaylistMetadata;
    @GuardedBy("mLock")
    private @RepeatMode int mRepeatMode;
    @GuardedBy("mLock")
    private @ShuffleMode int mShuffleMode;
    @GuardedBy("mLock")
    private int mPlayerState;
    @GuardedBy("mLock")
    private long mPositionEventTimeMs;
    @GuardedBy("mLock")
    private long mPositionMs;
    @GuardedBy("mLock")
    private float mPlaybackSpeed;
    @GuardedBy("mLock")
    private long mBufferedPositionMs;
    @GuardedBy("mLock")
    private PlaybackInfo mPlaybackInfo;
    @GuardedBy("mLock")
    private PendingIntent mSessionActivity;
    @GuardedBy("mLock")
    private SessionCommandGroup2 mAllowedCommands;

    // Assignment should be used with the lock hold, but should be used without a lock to prevent
    // potential deadlock.
    // Postfix -Binder is added to explicitly show that it's potentially remote process call.
    // Technically -Interface is more correct, but it may misread that it's interface (vs class)
    // so let's keep this postfix until we find better postfix.
    @GuardedBy("mLock")
    private volatile IMediaSession2 mSessionBinder;

    // TODO(jaewan): Require session activeness changed listener, because controller can be
    //               available when the session's player is null.
    public MediaController2Impl(Context context, MediaController2 instance, SessionToken2 token,
            Executor executor, ControllerCallback callback) {
        mInstance = instance;
        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback shouldn't be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor shouldn't be null");
        }
        mContext = context;
        mControllerStub = new MediaController2Stub(this);
        mToken = token;
        mCallback = callback;
        mCallbackExecutor = executor;
        mDeathRecipient = () -> {
            mInstance.close();
        };

        mSessionBinder = null;
    }

    @Override
    public void initialize() {
        // TODO(jaewan): More sanity checks.
        if (mToken.getType() == SessionToken2.TYPE_SESSION) {
            // Session
            mServiceConnection = null;
            connectToSession(SessionToken2Impl.from(mToken).getSessionBinder());
        } else {
            // Session service
            if (Process.myUid() == Process.SYSTEM_UID) {
                // It's system server (MediaSessionService) that wants to monitor session.
                // Don't bind if able..
                IMediaSession2 binder = SessionToken2Impl.from(mToken).getSessionBinder();
                if (binder != null) {
                    // Use binder in the session token instead of bind by its own.
                    // Otherwise server will holds the binding to the service *forever* and service
                    // will never stop.
                    mServiceConnection = null;
                    connectToSession(SessionToken2Impl.from(mToken).getSessionBinder());
                    return;
                } else if (DEBUG) {
                    // Should happen only when system server wants to dispatch media key events to
                    // a dead service.
                    Log.d(TAG, "System server binds to a session service. Should unbind"
                            + " immediately after the use.");
                }
            }
            mServiceConnection = new SessionServiceConnection();
            connectToService();
        }
    }

    private void connectToService() {
        // Service. Needs to get fresh binder whenever connection is needed.
        SessionToken2Impl impl = SessionToken2Impl.from(mToken);
        final Intent intent = new Intent(MediaSessionService2.SERVICE_INTERFACE);
        intent.setClassName(mToken.getPackageName(), impl.getServiceName());

        // Use bindService() instead of startForegroundService() to start session service for three
        // reasons.
        // 1. Prevent session service owner's stopSelf() from destroying service.
        //    With the startForegroundService(), service's call of stopSelf() will trigger immediate
        //    onDestroy() calls on the main thread even when onConnect() is running in another
        //    thread.
        // 2. Minimize APIs for developers to take care about.
        //    With bindService(), developers only need to take care about Service.onBind()
        //    but Service.onStartCommand() should be also taken care about with the
        //    startForegroundService().
        // 3. Future support for UI-less playback
        //    If a service wants to keep running, it should be either foreground service or
        //    bounded service. But there had been request for the feature for system apps
        //    and using bindService() will be better fit with it.
        boolean result;
        if (Process.myUid() == Process.SYSTEM_UID) {
            // Use bindServiceAsUser() for binding from system service to avoid following warning.
            // ContextImpl: Calling a method in the system process without a qualified user
            result = mContext.bindServiceAsUser(intent, mServiceConnection, Context.BIND_AUTO_CREATE,
                    UserHandle.getUserHandleForUid(mToken.getUid()));
        } else {
            result = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }
        if (!result) {
            Log.w(TAG, "bind to " + mToken + " failed");
        } else if (DEBUG) {
            Log.d(TAG, "bind to " + mToken + " success");
        }
    }

    private void connectToSession(IMediaSession2 sessionBinder) {
        try {
            sessionBinder.connect(mControllerStub, mContext.getPackageName());
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call connection request. Framework will retry"
                    + " automatically");
        }
    }

    @Override
    public void close_impl() {
        if (DEBUG) {
            Log.d(TAG, "release from " + mToken);
        }
        final IMediaSession2 binder;
        synchronized (mLock) {
            if (mIsReleased) {
                // Prevent re-enterance from the ControllerCallback.onDisconnected()
                return;
            }
            mIsReleased = true;
            if (mServiceConnection != null) {
                mContext.unbindService(mServiceConnection);
                mServiceConnection = null;
            }
            binder = mSessionBinder;
            mSessionBinder = null;
            mControllerStub.destroy();
        }
        if (binder != null) {
            try {
                binder.asBinder().unlinkToDeath(mDeathRecipient, 0);
                binder.release(mControllerStub);
            } catch (RemoteException e) {
                // No-op.
            }
        }
        mCallbackExecutor.execute(() -> {
            mCallback.onDisconnected(mInstance);
        });
    }

    IMediaSession2 getSessionBinder() {
        return mSessionBinder;
    }

    MediaController2Stub getControllerStub() {
        return mControllerStub;
    }

    Executor getCallbackExecutor() {
        return mCallbackExecutor;
    }

    Context getContext() {
        return mContext;
    }

    MediaController2 getInstance() {
        return mInstance;
    }

    // Returns session binder if the controller can send the command.
    IMediaSession2 getSessionBinderIfAble(int commandCode) {
        synchronized (mLock) {
            if (!mAllowedCommands.hasCommand(commandCode)) {
                // Cannot send because isn't allowed to.
                Log.w(TAG, "Controller isn't allowed to call command, commandCode="
                        + commandCode);
                return null;
            }
        }
        // TODO(jaewan): Should we do this with the lock hold?
        final IMediaSession2 binder = mSessionBinder;
        if (binder == null) {
            // Cannot send because disconnected.
            Log.w(TAG, "Session is disconnected");
        }
        return binder;
    }

    // Returns session binder if the controller can send the command.
    IMediaSession2 getSessionBinderIfAble(SessionCommand2 command) {
        synchronized (mLock) {
            if (!mAllowedCommands.hasCommand(command)) {
                Log.w(TAG, "Controller isn't allowed to call command, command=" + command);
                return null;
            }
        }
        // TODO(jaewan): Should we do this with the lock hold?
        final IMediaSession2 binder = mSessionBinder;
        if (binder == null) {
            // Cannot send because disconnected.
            Log.w(TAG, "Session is disconnected");
        }
        return binder;
    }

    @Override
    public SessionToken2 getSessionToken_impl() {
        return mToken;
    }

    @Override
    public boolean isConnected_impl() {
        final IMediaSession2 binder = mSessionBinder;
        return binder != null;
    }

    @Override
    public void play_impl() {
        sendTransportControlCommand(SessionCommand2.COMMAND_CODE_PLAYBACK_PLAY);
    }

    @Override
    public void pause_impl() {
        sendTransportControlCommand(SessionCommand2.COMMAND_CODE_PLAYBACK_PAUSE);
    }

    @Override
    public void stop_impl() {
        sendTransportControlCommand(SessionCommand2.COMMAND_CODE_PLAYBACK_STOP);
    }

    @Override
    public void skipToPlaylistItem_impl(MediaItem2 item) {
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.skipToPlaylistItem(mControllerStub, item.toBundle());
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void skipToPreviousItem_impl() {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.skipToPreviousItem(mControllerStub);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void skipToNextItem_impl() {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.skipToNextItem(mControllerStub);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    private void sendTransportControlCommand(int commandCode) {
        sendTransportControlCommand(commandCode, null);
    }

    private void sendTransportControlCommand(int commandCode, Bundle args) {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.sendTransportControlCommand(mControllerStub, commandCode, args);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public PendingIntent getSessionActivity_impl() {
        return mSessionActivity;
    }

    @Override
    public void setVolumeTo_impl(int value, int flags) {
        // TODO(hdmoon): sanity check
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_SET_VOLUME);
        if (binder != null) {
            try {
                binder.setVolumeTo(mControllerStub, value, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void adjustVolume_impl(int direction, int flags) {
        // TODO(hdmoon): sanity check
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_SET_VOLUME);
        if (binder != null) {
            try {
                binder.adjustVolume(mControllerStub, direction, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void prepareFromUri_impl(Uri uri, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_SESSION_PREPARE_FROM_URI);
        if (uri == null) {
            throw new IllegalArgumentException("uri shouldn't be null");
        }
        if (binder != null) {
            try {
                binder.prepareFromUri(mControllerStub, uri, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void prepareFromSearch_impl(String query, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(
                COMMAND_CODE_SESSION_PREPARE_FROM_SEARCH);
        if (TextUtils.isEmpty(query)) {
            throw new IllegalArgumentException("query shouldn't be empty");
        }
        if (binder != null) {
            try {
                binder.prepareFromSearch(mControllerStub, query, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void prepareFromMediaId_impl(String mediaId, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(
                COMMAND_CODE_SESSION_PREPARE_FROM_MEDIA_ID);
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId shouldn't be null");
        }
        if (binder != null) {
            try {
                binder.prepareFromMediaId(mControllerStub, mediaId, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void playFromUri_impl(Uri uri, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_SESSION_PLAY_FROM_URI);
        if (uri == null) {
            throw new IllegalArgumentException("uri shouldn't be null");
        }
        if (binder != null) {
            try {
                binder.playFromUri(mControllerStub, uri, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void playFromSearch_impl(String query, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_SESSION_PLAY_FROM_SEARCH);
        if (TextUtils.isEmpty(query)) {
            throw new IllegalArgumentException("query shouldn't be empty");
        }
        if (binder != null) {
            try {
                binder.playFromSearch(mControllerStub, query, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void playFromMediaId_impl(String mediaId, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(
                COMMAND_CODE_SESSION_PLAY_FROM_MEDIA_ID);
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId shouldn't be null");
        }
        if (binder != null) {
            try {
                binder.playFromMediaId(mControllerStub, mediaId, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void setRating_impl(String mediaId, Rating2 rating) {
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId shouldn't be null");
        }
        if (rating == null) {
            throw new IllegalArgumentException("rating shouldn't be null");
        }

        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.setRating(mControllerStub, mediaId, rating.toBundle());
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void sendCustomCommand_impl(SessionCommand2 command, Bundle args, ResultReceiver cb) {
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        final IMediaSession2 binder = getSessionBinderIfAble(command);
        if (binder != null) {
            try {
                binder.sendCustomCommand(mControllerStub, command.toBundle(), args, cb);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public List<MediaItem2> getPlaylist_impl() {
        synchronized (mLock) {
            return mPlaylist;
        }
    }

    @Override
    public void setPlaylist_impl(List<MediaItem2> list, MediaMetadata2 metadata) {
        if (list == null) {
            throw new IllegalArgumentException("list shouldn't be null");
        }
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAYLIST_SET_LIST);
        if (binder != null) {
            List<Bundle> bundleList = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                bundleList.add(list.get(i).toBundle());
            }
            Bundle metadataBundle = (metadata == null) ? null : metadata.toBundle();
            try {
                binder.setPlaylist(mControllerStub, bundleList, metadataBundle);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public MediaMetadata2 getPlaylistMetadata_impl() {
        synchronized (mLock) {
            return mPlaylistMetadata;
        }
    }

    @Override
    public void updatePlaylistMetadata_impl(MediaMetadata2 metadata) {
        final IMediaSession2 binder = getSessionBinderIfAble(
                COMMAND_CODE_PLAYLIST_SET_LIST_METADATA);
        if (binder != null) {
            Bundle metadataBundle = (metadata == null) ? null : metadata.toBundle();
            try {
                binder.updatePlaylistMetadata(mControllerStub, metadataBundle);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void prepare_impl() {
        sendTransportControlCommand(SessionCommand2.COMMAND_CODE_PLAYBACK_PREPARE);
    }

    @Override
    public void fastForward_impl() {
        // TODO(jaewan): Implement this. Note that fast forward isn't a transport command anymore
        //sendTransportControlCommand(MediaSession2.COMMAND_CODE_SESSION_FAST_FORWARD);
    }

    @Override
    public void rewind_impl() {
        // TODO(jaewan): Implement this. Note that rewind isn't a transport command anymore
        //sendTransportControlCommand(MediaSession2.COMMAND_CODE_SESSION_REWIND);
    }

    @Override
    public void seekTo_impl(long pos) {
        if (pos < 0) {
            throw new IllegalArgumentException("position shouldn't be negative");
        }
        Bundle args = new Bundle();
        args.putLong(MediaSession2Stub.ARGUMENT_KEY_POSITION, pos);
        sendTransportControlCommand(SessionCommand2.COMMAND_CODE_PLAYBACK_SEEK_TO, args);
    }

    @Override
    public void addPlaylistItem_impl(int index, MediaItem2 item) {
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAYLIST_ADD_ITEM);
        if (binder != null) {
            try {
                binder.addPlaylistItem(mControllerStub, index, item.toBundle());
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void removePlaylistItem_impl(MediaItem2 item) {
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAYLIST_REMOVE_ITEM);
        if (binder != null) {
            try {
                binder.removePlaylistItem(mControllerStub, item.toBundle());
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void replacePlaylistItem_impl(int index, MediaItem2 item) {
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAYLIST_REPLACE_ITEM);
        if (binder != null) {
            try {
                binder.replacePlaylistItem(mControllerStub, index, item.toBundle());
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public int getShuffleMode_impl() {
        return mShuffleMode;
    }

    @Override
    public void setShuffleMode_impl(int shuffleMode) {
        final IMediaSession2 binder = getSessionBinderIfAble(
                COMMAND_CODE_PLAYLIST_SET_SHUFFLE_MODE);
        if (binder != null) {
            try {
                binder.setShuffleMode(mControllerStub, shuffleMode);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public int getRepeatMode_impl() {
        return mRepeatMode;
    }

    @Override
    public void setRepeatMode_impl(int repeatMode) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAYLIST_SET_REPEAT_MODE);
        if (binder != null) {
            try {
                binder.setRepeatMode(mControllerStub, repeatMode);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public PlaybackInfo getPlaybackInfo_impl() {
        synchronized (mLock) {
            return mPlaybackInfo;
        }
    }

    @Override
    public int getPlayerState_impl() {
        synchronized (mLock) {
            return mPlayerState;
        }
    }

    @Override
    public long getCurrentPosition_impl() {
        synchronized (mLock) {
            long timeDiff = System.currentTimeMillis() - mPositionEventTimeMs;
            long expectedPosition = mPositionMs + (long) (mPlaybackSpeed * timeDiff);
            return Math.max(0, expectedPosition);
        }
    }

    @Override
    public float getPlaybackSpeed_impl() {
        synchronized (mLock) {
            return mPlaybackSpeed;
        }
    }

    @Override
    public long getBufferedPosition_impl() {
        synchronized (mLock) {
            return mBufferedPositionMs;
        }
    }

    @Override
    public MediaItem2 getCurrentMediaItem_impl() {
        // TODO(jaewan): Implement
        return null;
    }

    void pushPlayerStateChanges(final int state) {
        synchronized (mLock) {
            mPlayerState = state;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onPlayerStateChanged(mInstance, state);
        });
    }

    // TODO(jaewan): Rename to seek completed
    void pushPositionChanges(final long eventTimeMs, final long positionMs) {
        synchronized (mLock) {
            mPositionEventTimeMs = eventTimeMs;
            mPositionMs = positionMs;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onSeekCompleted(mInstance, positionMs);
        });
    }

    void pushPlaybackSpeedChanges(final float speed) {
        synchronized (mLock) {
            mPlaybackSpeed = speed;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onPlaybackSpeedChanged(mInstance, speed);
        });
    }

    void pushBufferedPositionChanges(final long bufferedPositionMs) {
        synchronized (mLock) {
            mBufferedPositionMs = bufferedPositionMs;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            // TODO(jaewan): Fix this -- it's now buffered state
            //mCallback.onBufferedPositionChanged(mInstance, bufferedPositionMs);
        });
    }

    void pushPlaybackInfoChanges(final PlaybackInfo info) {
        synchronized (mLock) {
            mPlaybackInfo = info;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onPlaybackInfoChanged(mInstance, info);
        });
    }

    void pushPlaylistChanges(final List<MediaItem2> playlist, final MediaMetadata2 metadata) {
        synchronized (mLock) {
            mPlaylist = playlist;
            mPlaylistMetadata = metadata;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onPlaylistChanged(mInstance, playlist, metadata);
        });
    }

    void pushPlaylistMetadataChanges(MediaMetadata2 metadata) {
        synchronized (mLock) {
            mPlaylistMetadata = metadata;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onPlaylistMetadataChanged(mInstance, metadata);
        });
    }

    void pushShuffleModeChanges(int shuffleMode) {
        synchronized (mLock) {
            mShuffleMode = shuffleMode;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onShuffleModeChanged(mInstance, shuffleMode);
        });
    }

    void pushRepeatModeChanges(int repeatMode) {
        synchronized (mLock) {
            mRepeatMode = repeatMode;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onRepeatModeChanged(mInstance, repeatMode);
        });
    }

    void pushError(int errorCode, Bundle extras) {
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onError(mInstance, errorCode, extras);
        });
    }

    // Should be used without a lock to prevent potential deadlock.
    void onConnectedNotLocked(IMediaSession2 sessionBinder,
            final SessionCommandGroup2 allowedCommands,
            final int playerState,
            final long positionEventTimeMs,
            final long positionMs,
            final float playbackSpeed,
            final long bufferedPositionMs,
            final PlaybackInfo info,
            final int repeatMode,
            final int shuffleMode,
            final List<MediaItem2> playlist,
            final PendingIntent sessionActivity) {
        if (DEBUG) {
            Log.d(TAG, "onConnectedNotLocked sessionBinder=" + sessionBinder
                    + ", allowedCommands=" + allowedCommands);
        }
        boolean close = false;
        try {
            if (sessionBinder == null || allowedCommands == null) {
                // Connection rejected.
                close = true;
                return;
            }
            synchronized (mLock) {
                if (mIsReleased) {
                    return;
                }
                if (mSessionBinder != null) {
                    Log.e(TAG, "Cannot be notified about the connection result many times."
                            + " Probably a bug or malicious app.");
                    close = true;
                    return;
                }
                mAllowedCommands = allowedCommands;
                mPlayerState = playerState;
                mPositionEventTimeMs = positionEventTimeMs;
                mPositionMs = positionMs;
                mPlaybackSpeed = playbackSpeed;
                mBufferedPositionMs = bufferedPositionMs;
                mPlaybackInfo = info;
                mRepeatMode = repeatMode;
                mShuffleMode = shuffleMode;
                mPlaylist = playlist;
                mSessionActivity = sessionActivity;
                mSessionBinder = sessionBinder;
                try {
                    // Implementation for the local binder is no-op,
                    // so can be used without worrying about deadlock.
                    mSessionBinder.asBinder().linkToDeath(mDeathRecipient, 0);
                } catch (RemoteException e) {
                    if (DEBUG) {
                        Log.d(TAG, "Session died too early.", e);
                    }
                    close = true;
                    return;
                }
            }
            // TODO(jaewan): Keep commands to prevents illegal API calls.
            mCallbackExecutor.execute(() -> {
                // Note: We may trigger ControllerCallbacks with the initial values
                // But it's hard to define the order of the controller callbacks
                // Only notify about the
                mCallback.onConnected(mInstance, allowedCommands);
            });
        } finally {
            if (close) {
                // Trick to call release() without holding the lock, to prevent potential deadlock
                // with the developer's custom lock within the ControllerCallback.onDisconnected().
                mInstance.close();
            }
        }
    }

    void onCustomCommand(final SessionCommand2 command, final Bundle args,
            final ResultReceiver receiver) {
        if (DEBUG) {
            Log.d(TAG, "onCustomCommand cmd=" + command);
        }
        mCallbackExecutor.execute(() -> {
            // TODO(jaewan): Double check if the controller exists.
            mCallback.onCustomCommand(mInstance, command, args, receiver);
        });
    }

    void onAllowedCommandsChanged(final SessionCommandGroup2 commands) {
        mCallbackExecutor.execute(() -> {
            mCallback.onAllowedCommandsChanged(mInstance, commands);
        });
    }

    void onCustomLayoutChanged(final List<CommandButton> layout) {
        mCallbackExecutor.execute(() -> {
            mCallback.onCustomLayoutChanged(mInstance, layout);
        });
    }

    // This will be called on the main thread.
    private class SessionServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Note that it's always main-thread.
            if (DEBUG) {
                Log.d(TAG, "onServiceConnected " + name + " " + this);
            }
            // Sanity check
            if (!mToken.getPackageName().equals(name.getPackageName())) {
                Log.wtf(TAG, name + " was connected, but expected pkg="
                        + mToken.getPackageName() + " with id=" + mToken.getId());
                return;
            }
            final IMediaSession2 sessionBinder = IMediaSession2.Stub.asInterface(service);
            connectToSession(sessionBinder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Temporal lose of the binding because of the service crash. System will automatically
            // rebind, so just no-op.
            // TODO(jaewan): Really? Either disconnect cleanly or
            if (DEBUG) {
                Log.w(TAG, "Session service " + name + " is disconnected.");
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Permanent lose of the binding because of the service package update or removed.
            // This SessionServiceRecord will be removed accordingly, but forget session binder here
            // for sure.
            mInstance.close();
        }
    }

    public static final class PlaybackInfoImpl implements PlaybackInfoProvider {

        private static final String KEY_PLAYBACK_TYPE =
                "android.media.playbackinfo_impl.playback_type";
        private static final String KEY_CONTROL_TYPE =
                "android.media.playbackinfo_impl.control_type";
        private static final String KEY_MAX_VOLUME =
                "android.media.playbackinfo_impl.max_volume";
        private static final String KEY_CURRENT_VOLUME =
                "android.media.playbackinfo_impl.current_volume";
        private static final String KEY_AUDIO_ATTRIBUTES =
                "android.media.playbackinfo_impl.audio_attrs";

        private final PlaybackInfo mInstance;

        private final int mPlaybackType;
        private final int mControlType;
        private final int mMaxVolume;
        private final int mCurrentVolume;
        private final AudioAttributes mAudioAttrs;

        private PlaybackInfoImpl(int playbackType, AudioAttributes attrs, int controlType,
                int max, int current) {
            mPlaybackType = playbackType;
            mAudioAttrs = attrs;
            mControlType = controlType;
            mMaxVolume = max;
            mCurrentVolume = current;
            mInstance = new PlaybackInfo(this);
        }

        @Override
        public int getPlaybackType_impl() {
            return mPlaybackType;
        }

        @Override
        public AudioAttributes getAudioAttributes_impl() {
            return mAudioAttrs;
        }

        @Override
        public int getControlType_impl() {
            return mControlType;
        }

        @Override
        public int getMaxVolume_impl() {
            return mMaxVolume;
        }

        @Override
        public int getCurrentVolume_impl() {
            return mCurrentVolume;
        }

        PlaybackInfo getInstance() {
            return mInstance;
        }

        Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_PLAYBACK_TYPE, mPlaybackType);
            bundle.putInt(KEY_CONTROL_TYPE, mControlType);
            bundle.putInt(KEY_MAX_VOLUME, mMaxVolume);
            bundle.putInt(KEY_CURRENT_VOLUME, mCurrentVolume);
            bundle.putParcelable(KEY_AUDIO_ATTRIBUTES, mAudioAttrs);
            return bundle;
        }

        static PlaybackInfo createPlaybackInfo(int playbackType, AudioAttributes attrs,
                int controlType, int max, int current) {
            return new PlaybackInfoImpl(playbackType, attrs, controlType, max, current)
                    .getInstance();
        }

        static PlaybackInfo fromBundle(Bundle bundle) {
            if (bundle == null) {
                return null;
            }
            final int volumeType = bundle.getInt(KEY_PLAYBACK_TYPE);
            final int volumeControl = bundle.getInt(KEY_CONTROL_TYPE);
            final int maxVolume = bundle.getInt(KEY_MAX_VOLUME);
            final int currentVolume = bundle.getInt(KEY_CURRENT_VOLUME);
            final AudioAttributes attrs = bundle.getParcelable(KEY_AUDIO_ATTRIBUTES);

            return createPlaybackInfo(volumeType, attrs, volumeControl, maxVolume, currentVolume);
        }
    }
}

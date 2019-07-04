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

import static android.media.SessionCommand2.COMMAND_CODE_CUSTOM;
import static android.media.SessionToken2.TYPE_LIBRARY_SERVICE;
import static android.media.SessionToken2.TYPE_SESSION;
import static android.media.SessionToken2.TYPE_SESSION_SERVICE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.DataSourceDesc;
import android.media.MediaController2;
import android.media.MediaController2.PlaybackInfo;
import android.media.MediaItem2;
import android.media.MediaLibraryService2;
import android.media.MediaMetadata2;
import android.media.MediaPlayerBase;
import android.media.MediaPlayerBase.PlayerEventCallback;
import android.media.MediaPlayerBase.PlayerState;
import android.media.MediaPlaylistAgent;
import android.media.MediaPlaylistAgent.PlaylistEventCallback;
import android.media.MediaSession2;
import android.media.MediaSession2.Builder;
import android.media.SessionCommand2;
import android.media.MediaSession2.CommandButton;
import android.media.SessionCommandGroup2;
import android.media.MediaSession2.ControllerInfo;
import android.media.MediaSession2.OnDataSourceMissingHelper;
import android.media.MediaSession2.SessionCallback;
import android.media.MediaSessionService2;
import android.media.SessionToken2;
import android.media.VolumeProvider2;
import android.media.session.MediaSessionManager;
import android.media.update.MediaSession2Provider;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.Process;
import android.os.ResultReceiver;
import android.support.annotation.GuardedBy;
import android.text.TextUtils;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Executor;

public class MediaSession2Impl implements MediaSession2Provider {
    private static final String TAG = "MediaSession2";
    private static final boolean DEBUG = true;//Log.isLoggable(TAG, Log.DEBUG);

    private final Object mLock = new Object();

    private final MediaSession2 mInstance;
    private final Context mContext;
    private final String mId;
    private final Executor mCallbackExecutor;
    private final SessionCallback mCallback;
    private final MediaSession2Stub mSessionStub;
    private final SessionToken2 mSessionToken;
    private final AudioManager mAudioManager;
    private final PendingIntent mSessionActivity;
    private final PlayerEventCallback mPlayerEventCallback;
    private final PlaylistEventCallback mPlaylistEventCallback;

    // mPlayer is set to null when the session is closed, and we shouldn't throw an exception
    // nor leave log always for using mPlayer when it's null. Here's the reason.
    // When a MediaSession2 is closed, there could be a pended operation in the session callback
    // executor that may want to access the player. Here's the sample code snippet for that.
    //
    //   public void onFoo() {
    //     if (mPlayer == null) return; // first check
    //     mSessionCallbackExecutor.executor(() -> {
    //       // Error. Session may be closed and mPlayer can be null here.
    //       mPlayer.foo();
    //     });
    //   }
    //
    // By adding protective code, we can also protect APIs from being called after the close()
    //
    // TODO(jaewan): Should we put volatile here?
    @GuardedBy("mLock")
    private MediaPlayerBase mPlayer;
    @GuardedBy("mLock")
    private MediaPlaylistAgent mPlaylistAgent;
    @GuardedBy("mLock")
    private SessionPlaylistAgent mSessionPlaylistAgent;
    @GuardedBy("mLock")
    private VolumeProvider2 mVolumeProvider;
    @GuardedBy("mLock")
    private PlaybackInfo mPlaybackInfo;
    @GuardedBy("mLock")
    private OnDataSourceMissingHelper mDsmHelper;

    /**
     * Can be only called by the {@link Builder#build()}.
     * @param context
     * @param player
     * @param id
     * @param playlistAgent
     * @param volumeProvider
     * @param sessionActivity
     * @param callbackExecutor
     * @param callback
     */
    public MediaSession2Impl(Context context, MediaPlayerBase player, String id,
            MediaPlaylistAgent playlistAgent, VolumeProvider2 volumeProvider,
            PendingIntent sessionActivity,
            Executor callbackExecutor, SessionCallback callback) {
        // TODO(jaewan): Keep other params.
        mInstance = createInstance();

        // Argument checks are done by builder already.
        // Initialize finals first.
        mContext = context;
        mId = id;
        mCallback = callback;
        mCallbackExecutor = callbackExecutor;
        mSessionActivity = sessionActivity;
        mSessionStub = new MediaSession2Stub(this);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mPlayerEventCallback = new MyPlayerEventCallback(this);
        mPlaylistEventCallback = new MyPlaylistEventCallback(this);

        // Infer type from the id and package name.
        String libraryService = getServiceName(context, MediaLibraryService2.SERVICE_INTERFACE, id);
        String sessionService = getServiceName(context, MediaSessionService2.SERVICE_INTERFACE, id);
        if (sessionService != null && libraryService != null) {
            throw new IllegalArgumentException("Ambiguous session type. Multiple"
                    + " session services define the same id=" + id);
        } else if (libraryService != null) {
            mSessionToken = new SessionToken2Impl(Process.myUid(), TYPE_LIBRARY_SERVICE,
                    mContext.getPackageName(), libraryService, id, mSessionStub).getInstance();
        } else if (sessionService != null) {
            mSessionToken = new SessionToken2Impl(Process.myUid(), TYPE_SESSION_SERVICE,
                    mContext.getPackageName(), sessionService, id, mSessionStub).getInstance();
        } else {
            mSessionToken = new SessionToken2Impl(Process.myUid(), TYPE_SESSION,
                    mContext.getPackageName(), null, id, mSessionStub).getInstance();
        }

        updatePlayer(player, playlistAgent, volumeProvider);

        // Ask server for the sanity check, and starts
        // Sanity check for making session ID unique 'per package' cannot be done in here.
        // Server can only know if the package has another process and has another session with the
        // same id. Note that 'ID is unique per package' is important for controller to distinguish
        // a session in another package.
        MediaSessionManager manager =
                (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (!manager.createSession2(mSessionToken)) {
            throw new IllegalStateException("Session with the same id is already used by"
                    + " another process. Use MediaController2 instead.");
        }
    }

    MediaSession2 createInstance() {
        return new MediaSession2(this);
    }

    private static String getServiceName(Context context, String serviceAction, String id) {
        PackageManager manager = context.getPackageManager();
        Intent serviceIntent = new Intent(serviceAction);
        serviceIntent.setPackage(context.getPackageName());
        List<ResolveInfo> services = manager.queryIntentServices(serviceIntent,
                PackageManager.GET_META_DATA);
        String serviceName = null;
        if (services != null) {
            for (int i = 0; i < services.size(); i++) {
                String serviceId = SessionToken2Impl.getSessionId(services.get(i));
                if (serviceId != null && TextUtils.equals(id, serviceId)) {
                    if (services.get(i).serviceInfo == null) {
                        continue;
                    }
                    if (serviceName != null) {
                        throw new IllegalArgumentException("Ambiguous session type. Multiple"
                                + " session services define the same id=" + id);
                    }
                    serviceName = services.get(i).serviceInfo.name;
                }
            }
        }
        return serviceName;
    }

    @Override
    public void updatePlayer_impl(@NonNull MediaPlayerBase player, MediaPlaylistAgent playlistAgent,
            VolumeProvider2 volumeProvider) throws IllegalArgumentException {
        ensureCallingThread();
        if (player == null) {
            throw new IllegalArgumentException("player shouldn't be null");
        }
        updatePlayer(player, playlistAgent, volumeProvider);
    }

    private void updatePlayer(MediaPlayerBase player, MediaPlaylistAgent agent,
            VolumeProvider2 volumeProvider) {
        final MediaPlayerBase oldPlayer;
        final MediaPlaylistAgent oldAgent;
        final PlaybackInfo info = createPlaybackInfo(volumeProvider, player.getAudioAttributes());
        synchronized (mLock) {
            oldPlayer = mPlayer;
            oldAgent = mPlaylistAgent;
            mPlayer = player;
            if (agent == null) {
                mSessionPlaylistAgent = new SessionPlaylistAgent(this, mPlayer);
                if (mDsmHelper != null) {
                    mSessionPlaylistAgent.setOnDataSourceMissingHelper(mDsmHelper);
                }
                agent = mSessionPlaylistAgent;
            }
            mPlaylistAgent = agent;
            mVolumeProvider = volumeProvider;
            mPlaybackInfo = info;
        }
        if (player != oldPlayer) {
            player.registerPlayerEventCallback(mCallbackExecutor, mPlayerEventCallback);
            if (oldPlayer != null) {
                // Warning: Poorly implement player may ignore this
                oldPlayer.unregisterPlayerEventCallback(mPlayerEventCallback);
            }
        }
        if (agent != oldAgent) {
            agent.registerPlaylistEventCallback(mCallbackExecutor, mPlaylistEventCallback);
            if (oldAgent != null) {
                // Warning: Poorly implement player may ignore this
                oldAgent.unregisterPlaylistEventCallback(mPlaylistEventCallback);
            }
        }

        if (oldPlayer != null) {
            mSessionStub.notifyPlaybackInfoChanged(info);
            notifyPlayerUpdatedNotLocked(oldPlayer);
        }
        // TODO(jaewan): Repeat the same thing for the playlist agent.
    }

    private PlaybackInfo createPlaybackInfo(VolumeProvider2 volumeProvider, AudioAttributes attrs) {
        PlaybackInfo info;
        if (volumeProvider == null) {
            int stream;
            if (attrs == null) {
                stream = AudioManager.STREAM_MUSIC;
            } else {
                stream = attrs.getVolumeControlStream();
                if (stream == AudioManager.USE_DEFAULT_STREAM_TYPE) {
                    // It may happen if the AudioAttributes doesn't have usage.
                    // Change it to the STREAM_MUSIC because it's not supported by audio manager
                    // for querying volume level.
                    stream = AudioManager.STREAM_MUSIC;
                }
            }
            info = MediaController2Impl.PlaybackInfoImpl.createPlaybackInfo(
                    PlaybackInfo.PLAYBACK_TYPE_LOCAL,
                    attrs,
                    mAudioManager.isVolumeFixed()
                            ? VolumeProvider2.VOLUME_CONTROL_FIXED
                            : VolumeProvider2.VOLUME_CONTROL_ABSOLUTE,
                    mAudioManager.getStreamMaxVolume(stream),
                    mAudioManager.getStreamVolume(stream));
        } else {
            info = MediaController2Impl.PlaybackInfoImpl.createPlaybackInfo(
                    PlaybackInfo.PLAYBACK_TYPE_REMOTE /* ControlType */,
                    attrs,
                    volumeProvider.getControlType(),
                    volumeProvider.getMaxVolume(),
                    volumeProvider.getCurrentVolume());
        }
        return info;
    }

    @Override
    public void close_impl() {
        // Stop system service from listening this session first.
        MediaSessionManager manager =
                (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        manager.destroySession2(mSessionToken);

        if (mSessionStub != null) {
            if (DEBUG) {
                Log.d(TAG, "session is now unavailable, id=" + mId);
            }
            // Invalidate previously published session stub.
            mSessionStub.destroyNotLocked();
        }
        final MediaPlayerBase player;
        final MediaPlaylistAgent agent;
        synchronized (mLock) {
            player = mPlayer;
            mPlayer = null;
            agent = mPlaylistAgent;
            mPlaylistAgent = null;
            mSessionPlaylistAgent = null;
        }
        if (player != null) {
            player.unregisterPlayerEventCallback(mPlayerEventCallback);
        }
        if (agent != null) {
            agent.unregisterPlaylistEventCallback(mPlaylistEventCallback);
        }
    }

    @Override
    public MediaPlayerBase getPlayer_impl() {
        return getPlayer();
    }

    @Override
    public MediaPlaylistAgent getPlaylistAgent_impl() {
        return mPlaylistAgent;
    }

    @Override
    public VolumeProvider2 getVolumeProvider_impl() {
        return mVolumeProvider;
    }

    @Override
    public SessionToken2 getToken_impl() {
        return mSessionToken;
    }

    @Override
    public List<ControllerInfo> getConnectedControllers_impl() {
        return mSessionStub.getControllers();
    }

    @Override
    public void setAudioFocusRequest_impl(AudioFocusRequest afr) {
        // implement
    }

    @Override
    public void play_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.play();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void pause_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.pause();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void stop_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.reset();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void skipToPlaylistItem_impl(@NonNull MediaItem2 item) {
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.skipToPlaylistItem(item);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void skipToPreviousItem_impl() {
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.skipToPreviousItem();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void skipToNextItem_impl() {
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.skipToNextItem();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void setCustomLayout_impl(@NonNull ControllerInfo controller,
            @NonNull List<CommandButton> layout) {
        ensureCallingThread();
        if (controller == null) {
            throw new IllegalArgumentException("controller shouldn't be null");
        }
        if (layout == null) {
            throw new IllegalArgumentException("layout shouldn't be null");
        }
        mSessionStub.notifyCustomLayoutNotLocked(controller, layout);
    }

    //////////////////////////////////////////////////////////////////////////////////////
    // TODO(jaewan): Implement follows
    //////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void setAllowedCommands_impl(@NonNull ControllerInfo controller,
            @NonNull SessionCommandGroup2 commands) {
        if (controller == null) {
            throw new IllegalArgumentException("controller shouldn't be null");
        }
        if (commands == null) {
            throw new IllegalArgumentException("commands shouldn't be null");
        }
        mSessionStub.setAllowedCommands(controller, commands);
    }

    @Override
    public void sendCustomCommand_impl(@NonNull ControllerInfo controller,
            @NonNull SessionCommand2 command, Bundle args, ResultReceiver receiver) {
        if (controller == null) {
            throw new IllegalArgumentException("controller shouldn't be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        mSessionStub.sendCustomCommand(controller, command, args, receiver);
    }

    @Override
    public void sendCustomCommand_impl(@NonNull SessionCommand2 command, Bundle args) {
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        mSessionStub.sendCustomCommand(command, args);
    }

    @Override
    public void setPlaylist_impl(@NonNull List<MediaItem2> list, MediaMetadata2 metadata) {
        if (list == null) {
            throw new IllegalArgumentException("list shouldn't be null");
        }
        ensureCallingThread();
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.setPlaylist(list, metadata);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void updatePlaylistMetadata_impl(MediaMetadata2 metadata) {
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.updatePlaylistMetadata(metadata);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void addPlaylistItem_impl(int index, @NonNull MediaItem2 item) {
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.addPlaylistItem(index, item);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void removePlaylistItem_impl(@NonNull MediaItem2 item) {
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.removePlaylistItem(item);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void replacePlaylistItem_impl(int index, @NonNull MediaItem2 item) {
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.replacePlaylistItem(index, item);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public List<MediaItem2> getPlaylist_impl() {
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            return agent.getPlaylist();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return null;
    }

    @Override
    public MediaMetadata2 getPlaylistMetadata_impl() {
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            return agent.getPlaylistMetadata();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return null;
    }

    @Override
    public MediaItem2 getCurrentPlaylistItem_impl() {
        // TODO(jaewan): Implement
        return null;
    }

    @Override
    public int getRepeatMode_impl() {
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            return agent.getRepeatMode();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return MediaPlaylistAgent.REPEAT_MODE_NONE;
    }

    @Override
    public void setRepeatMode_impl(int repeatMode) {
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.setRepeatMode(repeatMode);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public int getShuffleMode_impl() {
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            return agent.getShuffleMode();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return MediaPlaylistAgent.SHUFFLE_MODE_NONE;
    }

    @Override
    public void setShuffleMode_impl(int shuffleMode) {
        final MediaPlaylistAgent agent = mPlaylistAgent;
        if (agent != null) {
            agent.setShuffleMode(shuffleMode);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void prepare_impl() {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.prepare();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public void seekTo_impl(long pos) {
        ensureCallingThread();
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            player.seekTo(pos);
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
    }

    @Override
    public @PlayerState int getPlayerState_impl() {
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            return mPlayer.getPlayerState();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return MediaPlayerBase.PLAYER_STATE_ERROR;
    }

    @Override
    public long getCurrentPosition_impl() {
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            return mPlayer.getCurrentPosition();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return MediaPlayerBase.UNKNOWN_TIME;
    }

    @Override
    public long getBufferedPosition_impl() {
        final MediaPlayerBase player = mPlayer;
        if (player != null) {
            return mPlayer.getBufferedPosition();
        } else if (DEBUG) {
            Log.d(TAG, "API calls after the close()", new IllegalStateException());
        }
        return MediaPlayerBase.UNKNOWN_TIME;
    }

    @Override
    public void notifyError_impl(int errorCode, Bundle extras) {
        mSessionStub.notifyError(errorCode, extras);
    }

    @Override
    public void setOnDataSourceMissingHelper_impl(@NonNull OnDataSourceMissingHelper helper) {
        if (helper == null) {
            throw new IllegalArgumentException("helper shouldn't be null");
        }
        synchronized (mLock) {
            mDsmHelper = helper;
            if (mSessionPlaylistAgent != null) {
                mSessionPlaylistAgent.setOnDataSourceMissingHelper(helper);
            }
        }
    }

    @Override
    public void clearOnDataSourceMissingHelper_impl() {
        synchronized (mLock) {
            mDsmHelper = null;
            if (mSessionPlaylistAgent != null) {
                mSessionPlaylistAgent.clearOnDataSourceMissingHelper();
            }
        }
    }

    ///////////////////////////////////////////////////
    // Protected or private methods
    ///////////////////////////////////////////////////

    // Enforces developers to call all the methods on the initially given thread
    // because calls from the MediaController2 will be run on the thread.
    // TODO(jaewan): Should we allow calls from the multiple thread?
    //               I prefer this way because allowing multiple thread may case tricky issue like
    //               b/63446360. If the {@link #setPlayer()} with {@code null} can be called from
    //               another thread, transport controls can be called after that.
    //               That's basically the developer's mistake, but they cannot understand what's
    //               happening behind until we tell them so.
    //               If enforcing callling thread doesn't look good, we can alternatively pick
    //               1. Allow calls from random threads for all methods.
    //               2. Allow calls from random threads for all methods, except for the
    //                  {@link #setPlayer()}.
    void ensureCallingThread() {
        // TODO(jaewan): Uncomment or remove
        /*
        if (mHandler.getLooper() != Looper.myLooper()) {
            throw new IllegalStateException("Run this on the given thread");
        }*/
    }

    private void notifyPlaylistChangedOnExecutor(MediaPlaylistAgent playlistAgent,
            List<MediaItem2> list, MediaMetadata2 metadata) {
        if (playlistAgent != mPlaylistAgent) {
            // Ignore calls from the old agent.
            return;
        }
        mCallback.onPlaylistChanged(mInstance, playlistAgent, list, metadata);
        mSessionStub.notifyPlaylistChangedNotLocked(list, metadata);
    }

    private void notifyPlaylistMetadataChangedOnExecutor(MediaPlaylistAgent playlistAgent,
            MediaMetadata2 metadata) {
        if (playlistAgent != mPlaylistAgent) {
            // Ignore calls from the old agent.
            return;
        }
        mCallback.onPlaylistMetadataChanged(mInstance, playlistAgent, metadata);
        mSessionStub.notifyPlaylistMetadataChangedNotLocked(metadata);
    }

    private void notifyRepeatModeChangedOnExecutor(MediaPlaylistAgent playlistAgent,
            int repeatMode) {
        if (playlistAgent != mPlaylistAgent) {
            // Ignore calls from the old agent.
            return;
        }
        mCallback.onRepeatModeChanged(mInstance, playlistAgent, repeatMode);
        mSessionStub.notifyRepeatModeChangedNotLocked(repeatMode);
    }

    private void notifyShuffleModeChangedOnExecutor(MediaPlaylistAgent playlistAgent,
            int shuffleMode) {
        if (playlistAgent != mPlaylistAgent) {
            // Ignore calls from the old agent.
            return;
        }
        mCallback.onShuffleModeChanged(mInstance, playlistAgent, shuffleMode);
        mSessionStub.notifyShuffleModeChangedNotLocked(shuffleMode);
    }

    private void notifyPlayerUpdatedNotLocked(MediaPlayerBase oldPlayer) {
        final MediaPlayerBase player = mPlayer;
        // TODO(jaewan): (Can be post-P) Find better way for player.getPlayerState() //
        //               In theory, Session.getXXX() may not be the same as Player.getXXX()
        //               and we should notify information of the session.getXXX() instead of
        //               player.getXXX()
        // Notify to controllers as well.
        final int state = player.getPlayerState();
        if (state != oldPlayer.getPlayerState()) {
            mSessionStub.notifyPlayerStateChangedNotLocked(state);
        }

        final long currentTimeMs = System.currentTimeMillis();
        final long position = player.getCurrentPosition();
        if (position != oldPlayer.getCurrentPosition()) {
            mSessionStub.notifyPositionChangedNotLocked(currentTimeMs, position);
        }

        final float speed = player.getPlaybackSpeed();
        if (speed != oldPlayer.getPlaybackSpeed()) {
            mSessionStub.notifyPlaybackSpeedChangedNotLocked(speed);
        }

        final long bufferedPosition = player.getBufferedPosition();
        if (bufferedPosition != oldPlayer.getBufferedPosition()) {
            mSessionStub.notifyBufferedPositionChangedNotLocked(bufferedPosition);
        }
    }

    Context getContext() {
        return mContext;
    }

    MediaSession2 getInstance() {
        return mInstance;
    }

    MediaPlayerBase getPlayer() {
        return mPlayer;
    }

    MediaPlaylistAgent getPlaylistAgent() {
        return mPlaylistAgent;
    }

    Executor getCallbackExecutor() {
        return mCallbackExecutor;
    }

    SessionCallback getCallback() {
        return mCallback;
    }

    MediaSession2Stub getSessionStub() {
        return mSessionStub;
    }

    VolumeProvider2 getVolumeProvider() {
        return mVolumeProvider;
    }

    PlaybackInfo getPlaybackInfo() {
        synchronized (mLock) {
            return mPlaybackInfo;
        }
    }

    PendingIntent getSessionActivity() {
        return mSessionActivity;
    }

    private static class MyPlayerEventCallback extends PlayerEventCallback {
        private final WeakReference<MediaSession2Impl> mSession;

        private MyPlayerEventCallback(MediaSession2Impl session) {
            mSession = new WeakReference<>(session);
        }

        @Override
        public void onCurrentDataSourceChanged(MediaPlayerBase mpb, DataSourceDesc dsd) {
            MediaSession2Impl session = getSession();
            if (session == null || dsd == null) {
                return;
            }
            session.getCallbackExecutor().execute(() -> {
                MediaItem2 item = getMediaItem(session, dsd);
                if (item == null) {
                    return;
                }
                session.getCallback().onCurrentMediaItemChanged(session.getInstance(), mpb, item);
                // TODO (jaewan): Notify controllers through appropriate callback. (b/74505936)
            });
        }

        @Override
        public void onMediaPrepared(MediaPlayerBase mpb, DataSourceDesc dsd) {
            MediaSession2Impl session = getSession();
            if (session == null || dsd == null) {
                return;
            }
            session.getCallbackExecutor().execute(() -> {
                MediaItem2 item = getMediaItem(session, dsd);
                if (item == null) {
                    return;
                }
                session.getCallback().onMediaPrepared(session.getInstance(), mpb, item);
                // TODO (jaewan): Notify controllers through appropriate callback. (b/74505936)
            });
        }

        @Override
        public void onPlayerStateChanged(MediaPlayerBase mpb, int state) {
            MediaSession2Impl session = getSession();
            if (session == null) {
                return;
            }
            session.getCallbackExecutor().execute(() -> {
                session.getCallback().onPlayerStateChanged(session.getInstance(), mpb, state);
                session.getSessionStub().notifyPlayerStateChangedNotLocked(state);
            });
        }

        @Override
        public void onBufferingStateChanged(MediaPlayerBase mpb, DataSourceDesc dsd, int state) {
            MediaSession2Impl session = getSession();
            if (session == null || dsd == null) {
                return;
            }
            session.getCallbackExecutor().execute(() -> {
                MediaItem2 item = getMediaItem(session, dsd);
                if (item == null) {
                    return;
                }
                session.getCallback().onBufferingStateChanged(
                        session.getInstance(), mpb, item, state);
                // TODO (jaewan): Notify controllers through appropriate callback. (b/74505936)
            });
        }

        private MediaSession2Impl getSession() {
            final MediaSession2Impl session = mSession.get();
            if (session == null && DEBUG) {
                Log.d(TAG, "Session is closed", new IllegalStateException());
            }
            return session;
        }

        private MediaItem2 getMediaItem(MediaSession2Impl session, DataSourceDesc dsd) {
            MediaPlaylistAgent agent = session.getPlaylistAgent();
            if (agent == null) {
                if (DEBUG) {
                    Log.d(TAG, "Session is closed", new IllegalStateException());
                }
                return null;
            }
            MediaItem2 item = agent.getMediaItem(dsd);
            if (item == null) {
                if (DEBUG) {
                    Log.d(TAG, "Could not find matching item for dsd=" + dsd,
                            new NoSuchElementException());
                }
            }
            return item;
        }
    }

    private static class MyPlaylistEventCallback extends PlaylistEventCallback {
        private final WeakReference<MediaSession2Impl> mSession;

        private MyPlaylistEventCallback(MediaSession2Impl session) {
            mSession = new WeakReference<>(session);
        }

        @Override
        public void onPlaylistChanged(MediaPlaylistAgent playlistAgent, List<MediaItem2> list,
                MediaMetadata2 metadata) {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.notifyPlaylistChangedOnExecutor(playlistAgent, list, metadata);
        }

        @Override
        public void onPlaylistMetadataChanged(MediaPlaylistAgent playlistAgent,
                MediaMetadata2 metadata) {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.notifyPlaylistMetadataChangedOnExecutor(playlistAgent, metadata);
        }

        @Override
        public void onRepeatModeChanged(MediaPlaylistAgent playlistAgent, int repeatMode) {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.notifyRepeatModeChangedOnExecutor(playlistAgent, repeatMode);
        }

        @Override
        public void onShuffleModeChanged(MediaPlaylistAgent playlistAgent, int shuffleMode) {
            final MediaSession2Impl session = mSession.get();
            if (session == null) {
                return;
            }
            session.notifyShuffleModeChangedOnExecutor(playlistAgent, shuffleMode);
        }
    }

    public static final class CommandImpl implements CommandProvider {
        private static final String KEY_COMMAND_CODE
                = "android.media.media_session2.command.command_code";
        private static final String KEY_COMMAND_CUSTOM_COMMAND
                = "android.media.media_session2.command.custom_command";
        private static final String KEY_COMMAND_EXTRAS
                = "android.media.media_session2.command.extras";

        private final SessionCommand2 mInstance;
        private final int mCommandCode;
        // Nonnull if it's custom command
        private final String mCustomCommand;
        private final Bundle mExtras;

        public CommandImpl(SessionCommand2 instance, int commandCode) {
            mInstance = instance;
            mCommandCode = commandCode;
            mCustomCommand = null;
            mExtras = null;
        }

        public CommandImpl(SessionCommand2 instance, @NonNull String action,
                @Nullable Bundle extras) {
            if (action == null) {
                throw new IllegalArgumentException("action shouldn't be null");
            }
            mInstance = instance;
            mCommandCode = COMMAND_CODE_CUSTOM;
            mCustomCommand = action;
            mExtras = extras;
        }

        @Override
        public int getCommandCode_impl() {
            return mCommandCode;
        }

        @Override
        public @Nullable String getCustomCommand_impl() {
            return mCustomCommand;
        }

        @Override
        public @Nullable Bundle getExtras_impl() {
            return mExtras;
        }

        /**
         * @return a new Bundle instance from the Command
         */
        @Override
        public Bundle toBundle_impl() {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_COMMAND_CODE, mCommandCode);
            bundle.putString(KEY_COMMAND_CUSTOM_COMMAND, mCustomCommand);
            bundle.putBundle(KEY_COMMAND_EXTRAS, mExtras);
            return bundle;
        }

        /**
         * @return a new Command instance from the Bundle
         */
        public static SessionCommand2 fromBundle_impl(@NonNull Bundle command) {
            if (command == null) {
                throw new IllegalArgumentException("command shouldn't be null");
            }
            int code = command.getInt(KEY_COMMAND_CODE);
            if (code != COMMAND_CODE_CUSTOM) {
                return new SessionCommand2(code);
            } else {
                String customCommand = command.getString(KEY_COMMAND_CUSTOM_COMMAND);
                if (customCommand == null) {
                    return null;
                }
                return new SessionCommand2(customCommand, command.getBundle(KEY_COMMAND_EXTRAS));
            }
        }

        @Override
        public boolean equals_impl(Object obj) {
            if (!(obj instanceof CommandImpl)) {
                return false;
            }
            CommandImpl other = (CommandImpl) obj;
            // TODO(jaewan): Compare Commands with the generated UUID, as we're doing for the MI2.
            return mCommandCode == other.mCommandCode
                    && TextUtils.equals(mCustomCommand, other.mCustomCommand);
        }

        @Override
        public int hashCode_impl() {
            final int prime = 31;
            return ((mCustomCommand != null)
                    ? mCustomCommand.hashCode() : 0) * prime + mCommandCode;
        }
    }

    /**
     * Represent set of {@link SessionCommand2}.
     */
    public static class CommandGroupImpl implements CommandGroupProvider {
        private static final String KEY_COMMANDS =
                "android.media.mediasession2.commandgroup.commands";

        // Prefix for all command codes
        private static final String PREFIX_COMMAND_CODE = "COMMAND_CODE_";

        // Prefix for command codes that will be sent directly to the MediaPlayerBase
        private static final String PREFIX_COMMAND_CODE_PLAYBACK = "COMMAND_CODE_PLAYBACK_";

        // Prefix for command codes that will be sent directly to the MediaPlaylistAgent
        private static final String PREFIX_COMMAND_CODE_PLAYLIST = "COMMAND_CODE_PLAYLIST_";

        private Set<SessionCommand2> mCommands = new HashSet<>();
        private final SessionCommandGroup2 mInstance;

        public CommandGroupImpl(SessionCommandGroup2 instance, Object other) {
            mInstance = instance;
            if (other != null && other instanceof CommandGroupImpl) {
                mCommands.addAll(((CommandGroupImpl) other).mCommands);
            }
        }

        public CommandGroupImpl() {
            mInstance = new SessionCommandGroup2(this);
        }

        @Override
        public void addCommand_impl(@NonNull SessionCommand2 command) {
            if (command == null) {
                throw new IllegalArgumentException("command shouldn't be null");
            }
            mCommands.add(command);
        }

        @Override
        public void addAllPredefinedCommands_impl() {
            addCommandsWithPrefix(PREFIX_COMMAND_CODE);
        }

        void addAllPlaybackCommands() {
            addCommandsWithPrefix(PREFIX_COMMAND_CODE_PLAYBACK);
        }

        void addAllPlaylistCommands() {
            addCommandsWithPrefix(PREFIX_COMMAND_CODE_PLAYLIST);
        }

        private void addCommandsWithPrefix(String prefix) {
            // TODO(jaewan): (Can be post-P): Don't use reflection for this purpose.
            final Field[] fields = MediaSession2.class.getFields();
            if (fields != null) {
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i].getName().startsWith(prefix)) {
                        try {
                            mCommands.add(new SessionCommand2(fields[i].getInt(null)));
                        } catch (IllegalAccessException e) {
                            Log.w(TAG, "Unexpected " + fields[i] + " in MediaSession2");
                        }
                    }
                }
            }
        }

        @Override
        public void removeCommand_impl(@NonNull SessionCommand2 command) {
            if (command == null) {
                throw new IllegalArgumentException("command shouldn't be null");
            }
            mCommands.remove(command);
        }

        @Override
        public boolean hasCommand_impl(@NonNull SessionCommand2 command) {
            if (command == null) {
                throw new IllegalArgumentException("command shouldn't be null");
            }
            return mCommands.contains(command);
        }

        @Override
        public boolean hasCommand_impl(int code) {
            if (code == COMMAND_CODE_CUSTOM) {
                throw new IllegalArgumentException("Use hasCommand(Command) for custom command");
            }
            for (SessionCommand2 command : mCommands) {
                if (command.getCommandCode() == code) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Set<SessionCommand2> getCommands_impl() {
            return getCommands();
        }

        public Set<SessionCommand2> getCommands() {
            return Collections.unmodifiableSet(mCommands);
        }

        /**
         * @return new bundle from the CommandGroup
         * @hide
         */
        @Override
        public Bundle toBundle_impl() {
            ArrayList<Bundle> list = new ArrayList<>();
            for (SessionCommand2 command : mCommands) {
                list.add(command.toBundle());
            }
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(KEY_COMMANDS, list);
            return bundle;
        }

        /**
         * @return new instance of CommandGroup from the bundle
         * @hide
         */
        public static @Nullable SessionCommandGroup2 fromBundle_impl(Bundle commands) {
            if (commands == null) {
                return null;
            }
            List<Parcelable> list = commands.getParcelableArrayList(KEY_COMMANDS);
            if (list == null) {
                return null;
            }
            SessionCommandGroup2 commandGroup = new SessionCommandGroup2();
            for (int i = 0; i < list.size(); i++) {
                Parcelable parcelable = list.get(i);
                if (!(parcelable instanceof Bundle)) {
                    continue;
                }
                Bundle commandBundle = (Bundle) parcelable;
                SessionCommand2 command = SessionCommand2.fromBundle(commandBundle);
                if (command != null) {
                    commandGroup.addCommand(command);
                }
            }
            return commandGroup;
        }
    }

    public static class ControllerInfoImpl implements ControllerInfoProvider {
        private final ControllerInfo mInstance;
        private final int mUid;
        private final String mPackageName;
        private final boolean mIsTrusted;
        private final IMediaController2 mControllerBinder;

        public ControllerInfoImpl(Context context, ControllerInfo instance, int uid,
                int pid, @NonNull String packageName, @NonNull IMediaController2 callback) {
            if (TextUtils.isEmpty(packageName)) {
                throw new IllegalArgumentException("packageName shouldn't be empty");
            }
            if (callback == null) {
                throw new IllegalArgumentException("callback shouldn't be null");
            }

            mInstance = instance;
            mUid = uid;
            mPackageName = packageName;
            mControllerBinder = callback;
            MediaSessionManager manager =
                  (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            // Ask server whether the controller is trusted.
            // App cannot know this because apps cannot query enabled notification listener for
            // another package, but system server can do.
            mIsTrusted = manager.isTrustedForMediaControl(
                    new MediaSessionManager.RemoteUserInfo(packageName, pid, uid));
        }

        @Override
        public String getPackageName_impl() {
            return mPackageName;
        }

        @Override
        public int getUid_impl() {
            return mUid;
        }

        @Override
        public boolean isTrusted_impl() {
            return mIsTrusted;
        }

        @Override
        public int hashCode_impl() {
            return mControllerBinder.hashCode();
        }

        @Override
        public boolean equals_impl(Object obj) {
            if (!(obj instanceof ControllerInfo)) {
                return false;
            }
            return equals(((ControllerInfo) obj).getProvider());
        }

        @Override
        public String toString_impl() {
            return "ControllerInfo {pkg=" + mPackageName + ", uid=" + mUid + ", trusted="
                    + mIsTrusted + "}";
        }

        @Override
        public int hashCode() {
            return mControllerBinder.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ControllerInfoImpl)) {
                return false;
            }
            ControllerInfoImpl other = (ControllerInfoImpl) obj;
            return mControllerBinder.asBinder().equals(other.mControllerBinder.asBinder());
        }

        ControllerInfo getInstance() {
            return mInstance;
        }

        IBinder getId() {
            return mControllerBinder.asBinder();
        }

        IMediaController2 getControllerBinder() {
            return mControllerBinder;
        }

        static ControllerInfoImpl from(ControllerInfo controller) {
            return (ControllerInfoImpl) controller.getProvider();
        }
    }

    public static class CommandButtonImpl implements CommandButtonProvider {
        private static final String KEY_COMMAND
                = "android.media.media_session2.command_button.command";
        private static final String KEY_ICON_RES_ID
                = "android.media.media_session2.command_button.icon_res_id";
        private static final String KEY_DISPLAY_NAME
                = "android.media.media_session2.command_button.display_name";
        private static final String KEY_EXTRAS
                = "android.media.media_session2.command_button.extras";
        private static final String KEY_ENABLED
                = "android.media.media_session2.command_button.enabled";

        private final CommandButton mInstance;
        private SessionCommand2 mCommand;
        private int mIconResId;
        private String mDisplayName;
        private Bundle mExtras;
        private boolean mEnabled;

        public CommandButtonImpl(@Nullable SessionCommand2 command, int iconResId,
                @Nullable String displayName, Bundle extras, boolean enabled) {
            mCommand = command;
            mIconResId = iconResId;
            mDisplayName = displayName;
            mExtras = extras;
            mEnabled = enabled;
            mInstance = new CommandButton(this);
        }

        @Override
        public @Nullable
        SessionCommand2 getCommand_impl() {
            return mCommand;
        }

        @Override
        public int getIconResId_impl() {
            return mIconResId;
        }

        @Override
        public @Nullable String getDisplayName_impl() {
            return mDisplayName;
        }

        @Override
        public @Nullable Bundle getExtras_impl() {
            return mExtras;
        }

        @Override
        public boolean isEnabled_impl() {
            return mEnabled;
        }

        @NonNull Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putBundle(KEY_COMMAND, mCommand.toBundle());
            bundle.putInt(KEY_ICON_RES_ID, mIconResId);
            bundle.putString(KEY_DISPLAY_NAME, mDisplayName);
            bundle.putBundle(KEY_EXTRAS, mExtras);
            bundle.putBoolean(KEY_ENABLED, mEnabled);
            return bundle;
        }

        static @Nullable CommandButton fromBundle(Bundle bundle) {
            if (bundle == null) {
                return null;
            }
            CommandButton.Builder builder = new CommandButton.Builder();
            builder.setCommand(SessionCommand2.fromBundle(bundle.getBundle(KEY_COMMAND)));
            builder.setIconResId(bundle.getInt(KEY_ICON_RES_ID, 0));
            builder.setDisplayName(bundle.getString(KEY_DISPLAY_NAME));
            builder.setExtras(bundle.getBundle(KEY_EXTRAS));
            builder.setEnabled(bundle.getBoolean(KEY_ENABLED));
            try {
                return builder.build();
            } catch (IllegalStateException e) {
                // Malformed or version mismatch. Return null for now.
                return null;
            }
        }

        /**
         * Builder for {@link CommandButton}.
         */
        public static class BuilderImpl implements CommandButtonProvider.BuilderProvider {
            private final CommandButton.Builder mInstance;
            private SessionCommand2 mCommand;
            private int mIconResId;
            private String mDisplayName;
            private Bundle mExtras;
            private boolean mEnabled;

            public BuilderImpl(CommandButton.Builder instance) {
                mInstance = instance;
                mEnabled = true;
            }

            @Override
            public CommandButton.Builder setCommand_impl(SessionCommand2 command) {
                mCommand = command;
                return mInstance;
            }

            @Override
            public CommandButton.Builder setIconResId_impl(int resId) {
                mIconResId = resId;
                return mInstance;
            }

            @Override
            public CommandButton.Builder setDisplayName_impl(String displayName) {
                mDisplayName = displayName;
                return mInstance;
            }

            @Override
            public CommandButton.Builder setEnabled_impl(boolean enabled) {
                mEnabled = enabled;
                return mInstance;
            }

            @Override
            public CommandButton.Builder setExtras_impl(Bundle extras) {
                mExtras = extras;
                return mInstance;
            }

            @Override
            public CommandButton build_impl() {
                if (mEnabled && mCommand == null) {
                    throw new IllegalStateException("Enabled button needs Command"
                            + " for controller to invoke the command");
                }
                if (mCommand != null && mCommand.getCommandCode() == COMMAND_CODE_CUSTOM
                        && (mIconResId == 0 || TextUtils.isEmpty(mDisplayName))) {
                    throw new IllegalStateException("Custom commands needs icon and"
                            + " and name to display");
                }
                return new CommandButtonImpl(mCommand, mIconResId, mDisplayName, mExtras, mEnabled)
                        .mInstance;
            }
        }
    }

    public static abstract class BuilderBaseImpl<T extends MediaSession2, C extends SessionCallback>
            implements BuilderBaseProvider<T, C> {
        final Context mContext;
        MediaPlayerBase mPlayer;
        String mId;
        Executor mCallbackExecutor;
        C mCallback;
        MediaPlaylistAgent mPlaylistAgent;
        VolumeProvider2 mVolumeProvider;
        PendingIntent mSessionActivity;

        /**
         * Constructor.
         *
         * @param context a context
         * @throws IllegalArgumentException if any parameter is null, or the player is a
         *      {@link MediaSession2} or {@link MediaController2}.
         */
        // TODO(jaewan): Also need executor
        public BuilderBaseImpl(@NonNull Context context) {
            if (context == null) {
                throw new IllegalArgumentException("context shouldn't be null");
            }
            mContext = context;
            // Ensure non-null
            mId = "";
        }

        @Override
        public void setPlayer_impl(@NonNull MediaPlayerBase player) {
            if (player == null) {
                throw new IllegalArgumentException("player shouldn't be null");
            }
            mPlayer = player;
        }

        @Override
        public void setPlaylistAgent_impl(@NonNull MediaPlaylistAgent playlistAgent) {
            if (playlistAgent == null) {
                throw new IllegalArgumentException("playlistAgent shouldn't be null");
            }
            mPlaylistAgent = playlistAgent;
        }

        @Override
        public void setVolumeProvider_impl(VolumeProvider2 volumeProvider) {
            mVolumeProvider = volumeProvider;
        }

        @Override
        public void setSessionActivity_impl(PendingIntent pi) {
            mSessionActivity = pi;
        }

        @Override
        public void setId_impl(@NonNull String id) {
            if (id == null) {
                throw new IllegalArgumentException("id shouldn't be null");
            }
            mId = id;
        }

        @Override
        public void setSessionCallback_impl(@NonNull Executor executor, @NonNull C callback) {
            if (executor == null) {
                throw new IllegalArgumentException("executor shouldn't be null");
            }
            if (callback == null) {
                throw new IllegalArgumentException("callback shouldn't be null");
            }
            mCallbackExecutor = executor;
            mCallback = callback;
        }

        @Override
        public abstract T build_impl();
    }

    public static class BuilderImpl extends BuilderBaseImpl<MediaSession2, SessionCallback> {
        public BuilderImpl(Context context, Builder instance) {
            super(context);
        }

        @Override
        public MediaSession2 build_impl() {
            if (mCallbackExecutor == null) {
                mCallbackExecutor = mContext.getMainExecutor();
            }
            if (mCallback == null) {
                mCallback = new SessionCallback() {};
            }
            return new MediaSession2Impl(mContext, mPlayer, mId, mPlaylistAgent,
                    mVolumeProvider, mSessionActivity, mCallbackExecutor, mCallback).getInstance();
        }
    }
}

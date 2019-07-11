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

import android.app.PendingIntent;
import android.content.Context;
import android.media.MediaController2;
import android.media.MediaItem2;
import android.media.MediaLibraryService2.LibraryRoot;
import android.media.MediaMetadata2;
import android.media.SessionCommand2;
import android.media.MediaSession2.CommandButton;
import android.media.SessionCommandGroup2;
import android.media.MediaSession2.ControllerInfo;
import android.media.Rating2;
import android.media.VolumeProvider2;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.media.MediaLibraryService2Impl.MediaLibrarySessionImpl;
import com.android.media.MediaSession2Impl.CommandButtonImpl;
import com.android.media.MediaSession2Impl.CommandGroupImpl;
import com.android.media.MediaSession2Impl.ControllerInfoImpl;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MediaSession2Stub extends IMediaSession2.Stub {

    static final String ARGUMENT_KEY_POSITION = "android.media.media_session2.key_position";
    static final String ARGUMENT_KEY_ITEM_INDEX = "android.media.media_session2.key_item_index";
    static final String ARGUMENT_KEY_PLAYLIST_PARAMS =
            "android.media.media_session2.key_playlist_params";

    private static final String TAG = "MediaSession2Stub";
    private static final boolean DEBUG = true; // TODO(jaewan): Rename.

    private static final SparseArray<SessionCommand2> sCommandsForOnCommandRequest =
            new SparseArray<>();

    private final Object mLock = new Object();
    private final WeakReference<MediaSession2Impl> mSession;

    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ControllerInfo> mControllers = new ArrayMap<>();
    @GuardedBy("mLock")
    private final Set<IBinder> mConnectingControllers = new HashSet<>();
    @GuardedBy("mLock")
    private final ArrayMap<ControllerInfo, SessionCommandGroup2> mAllowedCommandGroupMap =
            new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<ControllerInfo, Set<String>> mSubscriptions = new ArrayMap<>();

    public MediaSession2Stub(MediaSession2Impl session) {
        mSession = new WeakReference<>(session);

        synchronized (sCommandsForOnCommandRequest) {
            if (sCommandsForOnCommandRequest.size() == 0) {
                CommandGroupImpl group = new CommandGroupImpl();
                group.addAllPlaybackCommands();
                group.addAllPlaylistCommands();
                Set<SessionCommand2> commands = group.getCommands();
                for (SessionCommand2 command : commands) {
                    sCommandsForOnCommandRequest.append(command.getCommandCode(), command);
                }
            }
        }
    }

    public void destroyNotLocked() {
        final List<ControllerInfo> list;
        synchronized (mLock) {
            mSession.clear();
            list = getControllers();
            mControllers.clear();
        }
        for (int i = 0; i < list.size(); i++) {
            IMediaController2 controllerBinder =
                    ((ControllerInfoImpl) list.get(i).getProvider()).getControllerBinder();
            try {
                // Should be used without a lock hold to prevent potential deadlock.
                controllerBinder.onDisconnected();
            } catch (RemoteException e) {
                // Controller is gone. Should be fine because we're destroying.
            }
        }
    }

    private MediaSession2Impl getSession() {
        final MediaSession2Impl session = mSession.get();
        if (session == null && DEBUG) {
            Log.d(TAG, "Session is closed", new IllegalStateException());
        }
        return session;
    }

    private MediaLibrarySessionImpl getLibrarySession() throws IllegalStateException {
        final MediaSession2Impl session = getSession();
        if (!(session instanceof MediaLibrarySessionImpl)) {
            throw new RuntimeException("Session isn't a library session");
        }
        return (MediaLibrarySessionImpl) session;
    }

    // Get controller if the command from caller to session is able to be handled.
    private ControllerInfo getControllerIfAble(IMediaController2 caller) {
        synchronized (mLock) {
            final ControllerInfo controllerInfo = mControllers.get(caller.asBinder());
            if (controllerInfo == null && DEBUG) {
                Log.d(TAG, "Controller is disconnected", new IllegalStateException());
            }
            return controllerInfo;
        }
    }

    // Get controller if the command from caller to session is able to be handled.
    private ControllerInfo getControllerIfAble(IMediaController2 caller, int commandCode) {
        synchronized (mLock) {
            final ControllerInfo controllerInfo = getControllerIfAble(caller);
            if (controllerInfo == null) {
                return null;
            }
            SessionCommandGroup2 allowedCommands = mAllowedCommandGroupMap.get(controllerInfo);
            if (allowedCommands == null) {
                Log.w(TAG, "Controller with null allowed commands. Ignoring",
                        new IllegalStateException());
                return null;
            }
            if (!allowedCommands.hasCommand(commandCode)) {
                if (DEBUG) {
                    Log.d(TAG, "Controller isn't allowed for command " + commandCode);
                }
                return null;
            }
            return controllerInfo;
        }
    }

    // Get controller if the command from caller to session is able to be handled.
    private ControllerInfo getControllerIfAble(IMediaController2 caller, SessionCommand2 command) {
        synchronized (mLock) {
            final ControllerInfo controllerInfo = getControllerIfAble(caller);
            if (controllerInfo == null) {
                return null;
            }
            SessionCommandGroup2 allowedCommands = mAllowedCommandGroupMap.get(controllerInfo);
            if (allowedCommands == null) {
                Log.w(TAG, "Controller with null allowed commands. Ignoring",
                        new IllegalStateException());
                return null;
            }
            if (!allowedCommands.hasCommand(command)) {
                if (DEBUG) {
                    Log.d(TAG, "Controller isn't allowed for command " + command);
                }
                return null;
            }
            return controllerInfo;
        }
    }

    // Return binder if the session is able to send a command to the controller.
    private IMediaController2 getControllerBinderIfAble(ControllerInfo controller) {
        if (getSession() == null) {
            // getSession() already logged if session is closed.
            return null;
        }
        final ControllerInfoImpl impl = ControllerInfoImpl.from(controller);
        synchronized (mLock) {
            if (mControllers.get(impl.getId()) != null
                    || mConnectingControllers.contains(impl.getId())) {
                return impl.getControllerBinder();
            }
            if (DEBUG) {
                Log.d(TAG, controller + " isn't connected nor connecting",
                        new IllegalArgumentException());
            }
            return null;
        }
    }

    // Return binder if the session is able to send a command to the controller.
    private IMediaController2 getControllerBinderIfAble(ControllerInfo controller,
            int commandCode) {
        synchronized (mLock) {
            SessionCommandGroup2 allowedCommands = mAllowedCommandGroupMap.get(controller);
            if (allowedCommands == null) {
                Log.w(TAG, "Controller with null allowed commands. Ignoring");
                return null;
            }
            if (!allowedCommands.hasCommand(commandCode)) {
                if (DEBUG) {
                    Log.d(TAG, "Controller isn't allowed for command " + commandCode);
                }
                return null;
            }
            return getControllerBinderIfAble(controller);
        }
    }

    private void onCommand(@NonNull IMediaController2 caller, int commandCode,
            @NonNull SessionRunnable runnable) {
        final MediaSession2Impl session = getSession();
        final ControllerInfo controller = getControllerIfAble(caller, commandCode);
        if (session == null || controller == null) {
            return;
        }
        session.getCallbackExecutor().execute(() -> {
            if (getControllerIfAble(caller, commandCode) == null) {
                return;
            }
            SessionCommand2 command = sCommandsForOnCommandRequest.get(commandCode);
            if (command != null) {
                boolean accepted = session.getCallback().onCommandRequest(session.getInstance(),
                        controller, command);
                if (!accepted) {
                    // Don't run rejected command.
                    if (DEBUG) {
                        Log.d(TAG, "Command (code=" + commandCode + ") from "
                                + controller + " was rejected by " + session);
                    }
                    return;
                }
            }
            runnable.run(session, controller);
        });
    }

    private void onBrowserCommand(@NonNull IMediaController2 caller,
            @NonNull LibrarySessionRunnable runnable) {
        final MediaLibrarySessionImpl session = getLibrarySession();
        // TODO(jaewan): Consider command code
        final ControllerInfo controller = getControllerIfAble(caller);
        if (session == null || controller == null) {
            return;
        }
        session.getCallbackExecutor().execute(() -> {
            // TODO(jaewan): Consider command code
            if (getControllerIfAble(caller) == null) {
                return;
            }
            runnable.run(session, controller);
        });
    }


    private void notifyAll(int commandCode, @NonNull NotifyRunnable runnable) {
        List<ControllerInfo> controllers = getControllers();
        for (int i = 0; i < controllers.size(); i++) {
            notifyInternal(controllers.get(i),
                    getControllerBinderIfAble(controllers.get(i), commandCode), runnable);
        }
    }

    private void notifyAll(@NonNull NotifyRunnable runnable) {
        List<ControllerInfo> controllers = getControllers();
        for (int i = 0; i < controllers.size(); i++) {
            notifyInternal(controllers.get(i),
                    getControllerBinderIfAble(controllers.get(i)), runnable);
        }
    }

    private void notify(@NonNull ControllerInfo controller, @NonNull NotifyRunnable runnable) {
        notifyInternal(controller, getControllerBinderIfAble(controller), runnable);
    }

    private void notify(@NonNull ControllerInfo controller, int commandCode,
            @NonNull NotifyRunnable runnable) {
        notifyInternal(controller, getControllerBinderIfAble(controller, commandCode), runnable);
    }

    // Do not call this API directly. Use notify() instead.
    private void notifyInternal(@NonNull ControllerInfo controller,
            @NonNull IMediaController2 iController, @NonNull NotifyRunnable runnable) {
        if (controller == null || iController == null) {
            return;
        }
        try {
            runnable.run(controller, iController);
        } catch (DeadObjectException e) {
            if (DEBUG) {
                Log.d(TAG, controller.toString() + " is gone", e);
            }
            onControllerClosed(iController);
        } catch (RemoteException e) {
            // Currently it's TransactionTooLargeException or DeadSystemException.
            // We'd better to leave log for those cases because
            //   - TransactionTooLargeException means that we may need to fix our code.
            //     (e.g. add pagination or special way to deliver Bitmap)
            //   - DeadSystemException means that errors around it can be ignored.
            Log.w(TAG, "Exception in " + controller.toString(), e);
        }
    }

    private void onControllerClosed(IMediaController2 iController) {
        ControllerInfo controller;
        synchronized (mLock) {
            controller = mControllers.remove(iController.asBinder());
            if (DEBUG) {
                Log.d(TAG, "releasing " + controller);
            }
            mSubscriptions.remove(controller);
        }
        final MediaSession2Impl session = getSession();
        if (session == null || controller == null) {
            return;
        }
        session.getCallbackExecutor().execute(() -> {
            session.getCallback().onDisconnected(session.getInstance(), controller);
        });
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // AIDL methods for session overrides
    //////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public void connect(final IMediaController2 caller, final String callingPackage)
            throws RuntimeException {
        final MediaSession2Impl session = getSession();
        if (session == null) {
            return;
        }
        final Context context = session.getContext();
        final ControllerInfo controllerInfo = new ControllerInfo(context,
                Binder.getCallingUid(), Binder.getCallingPid(), callingPackage, caller);
        session.getCallbackExecutor().execute(() -> {
            if (getSession() == null) {
                return;
            }
            synchronized (mLock) {
                // Keep connecting controllers.
                // This helps sessions to call APIs in the onConnect() (e.g. setCustomLayout())
                // instead of pending them.
                mConnectingControllers.add(ControllerInfoImpl.from(controllerInfo).getId());
            }
            SessionCommandGroup2 allowedCommands = session.getCallback().onConnect(
                    session.getInstance(), controllerInfo);
            // Don't reject connection for the request from trusted app.
            // Otherwise server will fail to retrieve session's information to dispatch
            // media keys to.
            boolean accept = allowedCommands != null || controllerInfo.isTrusted();
            if (accept) {
                ControllerInfoImpl controllerImpl = ControllerInfoImpl.from(controllerInfo);
                if (DEBUG) {
                    Log.d(TAG, "Accepting connection, controllerInfo=" + controllerInfo
                            + " allowedCommands=" + allowedCommands);
                }
                if (allowedCommands == null) {
                    // For trusted apps, send non-null allowed commands to keep connection.
                    allowedCommands = new SessionCommandGroup2();
                }
                synchronized (mLock) {
                    mConnectingControllers.remove(controllerImpl.getId());
                    mControllers.put(controllerImpl.getId(),  controllerInfo);
                    mAllowedCommandGroupMap.put(controllerInfo, allowedCommands);
                }
                // If connection is accepted, notify the current state to the controller.
                // It's needed because we cannot call synchronous calls between session/controller.
                // Note: We're doing this after the onConnectionChanged(), but there's no guarantee
                //       that events here are notified after the onConnected() because
                //       IMediaController2 is oneway (i.e. async call) and Stub will
                //       use thread poll for incoming calls.
                final int playerState = session.getInstance().getPlayerState();
                final long positionEventTimeMs = System.currentTimeMillis();
                final long positionMs = session.getInstance().getCurrentPosition();
                final float playbackSpeed = session.getInstance().getPlaybackSpeed();
                final long bufferedPositionMs = session.getInstance().getBufferedPosition();
                final Bundle playbackInfoBundle = ((MediaController2Impl.PlaybackInfoImpl)
                        session.getPlaybackInfo().getProvider()).toBundle();
                final int repeatMode = session.getInstance().getRepeatMode();
                final int shuffleMode = session.getInstance().getShuffleMode();
                final PendingIntent sessionActivity = session.getSessionActivity();
                final List<MediaItem2> playlist =
                        allowedCommands.hasCommand(SessionCommand2.COMMAND_CODE_PLAYLIST_GET_LIST)
                                ? session.getInstance().getPlaylist() : null;
                final List<Bundle> playlistBundle;
                if (playlist != null) {
                    playlistBundle = new ArrayList<>();
                    // TODO(jaewan): Find a way to avoid concurrent modification exception.
                    for (int i = 0; i < playlist.size(); i++) {
                        final MediaItem2 item = playlist.get(i);
                        if (item != null) {
                            final Bundle itemBundle = item.toBundle();
                            if (itemBundle != null) {
                                playlistBundle.add(itemBundle);
                            }
                        }
                    }
                } else {
                    playlistBundle = null;
                }

                // Double check if session is still there, because close() can be called in another
                // thread.
                if (getSession() == null) {
                    return;
                }
                try {
                    caller.onConnected(MediaSession2Stub.this, allowedCommands.toBundle(),
                            playerState, positionEventTimeMs, positionMs, playbackSpeed,
                            bufferedPositionMs, playbackInfoBundle, repeatMode, shuffleMode,
                            playlistBundle, sessionActivity);
                } catch (RemoteException e) {
                    // Controller may be died prematurely.
                    // TODO(jaewan): Handle here.
                }
            } else {
                synchronized (mLock) {
                    mConnectingControllers.remove(ControllerInfoImpl.from(controllerInfo).getId());
                }
                if (DEBUG) {
                    Log.d(TAG, "Rejecting connection, controllerInfo=" + controllerInfo);
                }
                try {
                    caller.onDisconnected();
                } catch (RemoteException e) {
                    // Controller may be died prematurely.
                    // Not an issue because we'll ignore it anyway.
                }
            }
        });
    }

    @Override
    public void release(final IMediaController2 caller) throws RemoteException {
        onControllerClosed(caller);
    }

    @Override
    public void setVolumeTo(final IMediaController2 caller, final int value, final int flags)
            throws RuntimeException {
        onCommand(caller, SessionCommand2.COMMAND_CODE_SET_VOLUME,
                (session, controller) -> {
                    VolumeProvider2 volumeProvider = session.getVolumeProvider();
                    if (volumeProvider == null) {
                        // TODO(jaewan): Set local stream volume
                    } else {
                        volumeProvider.onSetVolumeTo(value);
                    }
                });
    }

    @Override
    public void adjustVolume(IMediaController2 caller, int direction, int flags)
            throws RuntimeException {
        onCommand(caller, SessionCommand2.COMMAND_CODE_SET_VOLUME,
                (session, controller) -> {
                    VolumeProvider2 volumeProvider = session.getVolumeProvider();
                    if (volumeProvider == null) {
                        // TODO(jaewan): Adjust local stream volume
                    } else {
                        volumeProvider.onAdjustVolume(direction);
                    }
                });
    }

    @Override
    public void sendTransportControlCommand(IMediaController2 caller,
            int commandCode, Bundle args) throws RuntimeException {
        onCommand(caller, commandCode, (session, controller) -> {
            switch (commandCode) {
                case SessionCommand2.COMMAND_CODE_PLAYBACK_PLAY:
                    session.getInstance().play();
                    break;
                case SessionCommand2.COMMAND_CODE_PLAYBACK_PAUSE:
                    session.getInstance().pause();
                    break;
                case SessionCommand2.COMMAND_CODE_PLAYBACK_STOP:
                    session.getInstance().stop();
                    break;
                case SessionCommand2.COMMAND_CODE_PLAYBACK_PREPARE:
                    session.getInstance().prepare();
                    break;
                case SessionCommand2.COMMAND_CODE_PLAYBACK_SEEK_TO:
                    session.getInstance().seekTo(args.getLong(ARGUMENT_KEY_POSITION));
                    break;
                default:
                    // TODO(jaewan): Resend unknown (new) commands through the custom command.
            }
        });
    }

    @Override
    public void sendCustomCommand(final IMediaController2 caller, final Bundle commandBundle,
            final Bundle args, final ResultReceiver receiver) {
        final MediaSession2Impl session = getSession();
        if (session == null) {
            return;
        }
        final SessionCommand2 command = SessionCommand2.fromBundle(commandBundle);
        if (command == null) {
            Log.w(TAG, "sendCustomCommand(): Ignoring null command from "
                    + getControllerIfAble(caller));
            return;
        }
        final ControllerInfo controller = getControllerIfAble(caller, command);
        if (controller == null) {
            return;
        }
        session.getCallbackExecutor().execute(() -> {
            if (getControllerIfAble(caller, command) == null) {
                return;
            }
            session.getCallback().onCustomCommand(session.getInstance(),
                    controller, command, args, receiver);
        });
    }

    @Override
    public void prepareFromUri(final IMediaController2 caller, final Uri uri,
            final Bundle extras) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_SESSION_PREPARE_FROM_URI,
                (session, controller) -> {
                    if (uri == null) {
                        Log.w(TAG, "prepareFromUri(): Ignoring null uri from " + controller);
                        return;
                    }
                    session.getCallback().onPrepareFromUri(session.getInstance(), controller, uri,
                            extras);
                });
    }

    @Override
    public void prepareFromSearch(final IMediaController2 caller, final String query,
            final Bundle extras) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_SESSION_PREPARE_FROM_SEARCH,
                (session, controller) -> {
                    if (TextUtils.isEmpty(query)) {
                        Log.w(TAG, "prepareFromSearch(): Ignoring empty query from " + controller);
                        return;
                    }
                    session.getCallback().onPrepareFromSearch(session.getInstance(),
                            controller, query, extras);
                });
    }

    @Override
    public void prepareFromMediaId(final IMediaController2 caller, final String mediaId,
            final Bundle extras) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_SESSION_PREPARE_FROM_MEDIA_ID,
                (session, controller) -> {
            if (mediaId == null) {
                Log.w(TAG, "prepareFromMediaId(): Ignoring null mediaId from " + controller);
                return;
            }
            session.getCallback().onPrepareFromMediaId(session.getInstance(),
                    controller, mediaId, extras);
        });
    }

    @Override
    public void playFromUri(final IMediaController2 caller, final Uri uri,
            final Bundle extras) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_SESSION_PLAY_FROM_URI,
                (session, controller) -> {
                    if (uri == null) {
                        Log.w(TAG, "playFromUri(): Ignoring null uri from " + controller);
                        return;
                    }
                    session.getCallback().onPlayFromUri(session.getInstance(), controller, uri,
                            extras);
                });
    }

    @Override
    public void playFromSearch(final IMediaController2 caller, final String query,
            final Bundle extras) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_SESSION_PLAY_FROM_SEARCH,
                (session, controller) -> {
                    if (TextUtils.isEmpty(query)) {
                        Log.w(TAG, "playFromSearch(): Ignoring empty query from " + controller);
                        return;
                    }
                    session.getCallback().onPlayFromSearch(session.getInstance(),
                            controller, query, extras);
                });
    }

    @Override
    public void playFromMediaId(final IMediaController2 caller, final String mediaId,
            final Bundle extras) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_SESSION_PLAY_FROM_MEDIA_ID,
                (session, controller) -> {
                    if (mediaId == null) {
                        Log.w(TAG, "playFromMediaId(): Ignoring null mediaId from " + controller);
                        return;
                    }
                    session.getCallback().onPlayFromMediaId(session.getInstance(), controller,
                            mediaId, extras);
                });
    }

    @Override
    public void setRating(final IMediaController2 caller, final String mediaId,
            final Bundle ratingBundle) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_SESSION_SET_RATING,
                (session, controller) -> {
                    if (mediaId == null) {
                        Log.w(TAG, "setRating(): Ignoring null mediaId from " + controller);
                        return;
                    }
                    if (ratingBundle == null) {
                        Log.w(TAG, "setRating(): Ignoring null ratingBundle from " + controller);
                        return;
                    }
                    Rating2 rating = Rating2.fromBundle(ratingBundle);
                    if (rating == null) {
                        if (ratingBundle == null) {
                            Log.w(TAG, "setRating(): Ignoring null rating from " + controller);
                            return;
                        }
                        return;
                    }
                    session.getCallback().onSetRating(session.getInstance(), controller, mediaId,
                            rating);
                });
    }

    @Override
    public void setPlaylist(final IMediaController2 caller, final List<Bundle> playlist,
            final Bundle metadata) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_SET_LIST, (session, controller) -> {
            if (playlist == null) {
                Log.w(TAG, "setPlaylist(): Ignoring null playlist from " + controller);
                return;
            }
            List<MediaItem2> list = new ArrayList<>();
            for (int i = 0; i < playlist.size(); i++) {
                // Recreates UUID in the playlist
                MediaItem2 item = MediaItem2Impl.fromBundle(playlist.get(i), null);
                if (item != null) {
                    list.add(item);
                }
            }
            session.getInstance().setPlaylist(list, MediaMetadata2.fromBundle(metadata));
        });
    }

    @Override
    public void updatePlaylistMetadata(final IMediaController2 caller, final Bundle metadata) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_SET_LIST_METADATA,
                (session, controller) -> {
            session.getInstance().updatePlaylistMetadata(MediaMetadata2.fromBundle(metadata));
        });
    }

    @Override
    public void addPlaylistItem(IMediaController2 caller, int index, Bundle mediaItem) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_ADD_ITEM,
                (session, controller) -> {
                    // Resets the UUID from the incoming media id, so controller may reuse a media
                    // item multiple times for addPlaylistItem.
                    session.getInstance().addPlaylistItem(index,
                            MediaItem2Impl.fromBundle(mediaItem, null));
                });
    }

    @Override
    public void removePlaylistItem(IMediaController2 caller, Bundle mediaItem) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_REMOVE_ITEM,
                (session, controller) -> {
            MediaItem2 item = MediaItem2.fromBundle(mediaItem);
            // Note: MediaItem2 has hidden UUID to identify it across the processes.
            session.getInstance().removePlaylistItem(item);
        });
    }

    @Override
    public void replacePlaylistItem(IMediaController2 caller, int index, Bundle mediaItem) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_REPLACE_ITEM,
                (session, controller) -> {
                    // Resets the UUID from the incoming media id, so controller may reuse a media
                    // item multiple times for replacePlaylistItem.
                    session.getInstance().replacePlaylistItem(index,
                            MediaItem2Impl.fromBundle(mediaItem, null));
                });
    }

    @Override
    public void skipToPlaylistItem(IMediaController2 caller, Bundle mediaItem) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_SKIP_TO_PLAYLIST_ITEM,
                (session, controller) -> {
                    if (mediaItem == null) {
                        Log.w(TAG, "skipToPlaylistItem(): Ignoring null mediaItem from "
                                + controller);
                    }
                    // Note: MediaItem2 has hidden UUID to identify it across the processes.
                    session.getInstance().skipToPlaylistItem(MediaItem2.fromBundle(mediaItem));
                });
    }

    @Override
    public void skipToPreviousItem(IMediaController2 caller) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_SKIP_PREV_ITEM,
                (session, controller) -> {
                    session.getInstance().skipToPreviousItem();
                });
    }

    @Override
    public void skipToNextItem(IMediaController2 caller) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_SKIP_NEXT_ITEM,
                (session, controller) -> {
                    session.getInstance().skipToNextItem();
                });
    }

    @Override
    public void setRepeatMode(IMediaController2 caller, int repeatMode) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_SET_REPEAT_MODE,
                (session, controller) -> {
                    session.getInstance().setRepeatMode(repeatMode);
                });
    }

    @Override
    public void setShuffleMode(IMediaController2 caller, int shuffleMode) {
        onCommand(caller, SessionCommand2.COMMAND_CODE_PLAYLIST_SET_SHUFFLE_MODE,
                (session, controller) -> {
                    session.getInstance().setShuffleMode(shuffleMode);
                });
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // AIDL methods for LibrarySession overrides
    //////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void getLibraryRoot(final IMediaController2 caller, final Bundle rootHints)
            throws RuntimeException {
        onBrowserCommand(caller, (session, controller) -> {
            final LibraryRoot root = session.getCallback().onGetLibraryRoot(session.getInstance(),
                    controller, rootHints);
            notify(controller, (unused, iController) -> {
                iController.onGetLibraryRootDone(rootHints,
                        root == null ? null : root.getRootId(),
                        root == null ? null : root.getExtras());
            });
        });
    }

    @Override
    public void getItem(final IMediaController2 caller, final String mediaId)
            throws RuntimeException {
        onBrowserCommand(caller, (session, controller) -> {
            if (mediaId == null) {
                if (DEBUG) {
                    Log.d(TAG, "mediaId shouldn't be null");
                }
                return;
            }
            final MediaItem2 result = session.getCallback().onGetItem(session.getInstance(),
                    controller, mediaId);
            notify(controller, (unused, iController) -> {
                iController.onGetItemDone(mediaId, result == null ? null : result.toBundle());
            });
        });
    }

    @Override
    public void getChildren(final IMediaController2 caller, final String parentId,
            final int page, final int pageSize, final Bundle extras) throws RuntimeException {
        onBrowserCommand(caller, (session, controller) -> {
            if (parentId == null) {
                if (DEBUG) {
                    Log.d(TAG, "parentId shouldn't be null");
                }
                return;
            }
            if (page < 1 || pageSize < 1) {
                if (DEBUG) {
                    Log.d(TAG, "Neither page nor pageSize should be less than 1");
                }
                return;
            }
            List<MediaItem2> result = session.getCallback().onGetChildren(session.getInstance(),
                    controller, parentId, page, pageSize, extras);
            if (result != null && result.size() > pageSize) {
                throw new IllegalArgumentException("onGetChildren() shouldn't return media items "
                        + "more than pageSize. result.size()=" + result.size() + " pageSize="
                        + pageSize);
            }
            final List<Bundle> bundleList;
            if (result != null) {
                bundleList = new ArrayList<>();
                for (MediaItem2 item : result) {
                    bundleList.add(item == null ? null : item.toBundle());
                }
            } else {
                bundleList = null;
            }
            notify(controller, (unused, iController) -> {
                iController.onGetChildrenDone(parentId, page, pageSize, bundleList, extras);
            });
        });
    }

    @Override
    public void search(IMediaController2 caller, String query, Bundle extras) {
        onBrowserCommand(caller, (session, controller) -> {
            if (TextUtils.isEmpty(query)) {
                Log.w(TAG, "search(): Ignoring empty query from " + controller);
                return;
            }
            session.getCallback().onSearch(session.getInstance(), controller, query, extras);
        });
    }

    @Override
    public void getSearchResult(final IMediaController2 caller, final String query,
            final int page, final int pageSize, final Bundle extras) {
        onBrowserCommand(caller, (session, controller) -> {
            if (TextUtils.isEmpty(query)) {
                Log.w(TAG, "getSearchResult(): Ignoring empty query from " + controller);
                return;
            }
            if (page < 1 || pageSize < 1) {
                Log.w(TAG, "getSearchResult(): Ignoring negative page / pageSize."
                        + " page=" + page + " pageSize=" + pageSize + " from " + controller);
                return;
            }
            List<MediaItem2> result = session.getCallback().onGetSearchResult(session.getInstance(),
                    controller, query, page, pageSize, extras);
            if (result != null && result.size() > pageSize) {
                throw new IllegalArgumentException("onGetSearchResult() shouldn't return media "
                        + "items more than pageSize. result.size()=" + result.size() + " pageSize="
                        + pageSize);
            }
            final List<Bundle> bundleList;
            if (result != null) {
                bundleList = new ArrayList<>();
                for (MediaItem2 item : result) {
                    bundleList.add(item == null ? null : item.toBundle());
                }
            } else {
                bundleList = null;
            }
            notify(controller, (unused, iController) -> {
                iController.onGetSearchResultDone(query, page, pageSize, bundleList, extras);
            });
        });
    }

    @Override
    public void subscribe(final IMediaController2 caller, final String parentId,
            final Bundle option) {
        onBrowserCommand(caller, (session, controller) -> {
            if (parentId == null) {
                Log.w(TAG, "subscribe(): Ignoring null parentId from " + controller);
                return;
            }
            session.getCallback().onSubscribe(session.getInstance(),
                    controller, parentId, option);
            synchronized (mLock) {
                Set<String> subscription = mSubscriptions.get(controller);
                if (subscription == null) {
                    subscription = new HashSet<>();
                    mSubscriptions.put(controller, subscription);
                }
                subscription.add(parentId);
            }
        });
    }

    @Override
    public void unsubscribe(final IMediaController2 caller, final String parentId) {
        onBrowserCommand(caller, (session, controller) -> {
            if (parentId == null) {
                Log.w(TAG, "unsubscribe(): Ignoring null parentId from " + controller);
                return;
            }
            session.getCallback().onUnsubscribe(session.getInstance(), controller, parentId);
            synchronized (mLock) {
                mSubscriptions.remove(controller);
            }
        });
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // APIs for MediaSession2Impl
    //////////////////////////////////////////////////////////////////////////////////////////////

    // TODO(jaewan): (Can be Post-P) Need a way to get controller with permissions
    public List<ControllerInfo> getControllers() {
        ArrayList<ControllerInfo> controllers = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mControllers.size(); i++) {
                controllers.add(mControllers.valueAt(i));
            }
        }
        return controllers;
    }

    // Should be used without a lock to prevent potential deadlock.
    public void notifyPlayerStateChangedNotLocked(int state) {
        notifyAll((controller, iController) -> {
            iController.onPlayerStateChanged(state);
        });
    }

    // TODO(jaewan): Rename
    public void notifyPositionChangedNotLocked(long eventTimeMs, long positionMs) {
        notifyAll((controller, iController) -> {
            iController.onPositionChanged(eventTimeMs, positionMs);
        });
    }

    public void notifyPlaybackSpeedChangedNotLocked(float speed) {
        notifyAll((controller, iController) -> {
            iController.onPlaybackSpeedChanged(speed);
        });
    }

    public void notifyBufferedPositionChangedNotLocked(long bufferedPositionMs) {
        notifyAll((controller, iController) -> {
            iController.onBufferedPositionChanged(bufferedPositionMs);
        });
    }

    public void notifyCustomLayoutNotLocked(ControllerInfo controller, List<CommandButton> layout) {
        notify(controller, (unused, iController) -> {
            List<Bundle> layoutBundles = new ArrayList<>();
            for (int i = 0; i < layout.size(); i++) {
                Bundle bundle = ((CommandButtonImpl) layout.get(i).getProvider()).toBundle();
                if (bundle != null) {
                    layoutBundles.add(bundle);
                }
            }
            iController.onCustomLayoutChanged(layoutBundles);
        });
    }

    public void notifyPlaylistChangedNotLocked(List<MediaItem2> playlist, MediaMetadata2 metadata) {
        final List<Bundle> bundleList;
        if (playlist != null) {
            bundleList = new ArrayList<>();
            for (int i = 0; i < playlist.size(); i++) {
                if (playlist.get(i) != null) {
                    Bundle bundle = playlist.get(i).toBundle();
                    if (bundle != null) {
                        bundleList.add(bundle);
                    }
                }
            }
        } else {
            bundleList = null;
        }
        final Bundle metadataBundle = (metadata == null) ? null : metadata.toBundle();
        notifyAll((controller, iController) -> {
            if (getControllerBinderIfAble(controller,
                    SessionCommand2.COMMAND_CODE_PLAYLIST_GET_LIST) != null) {
                iController.onPlaylistChanged(bundleList, metadataBundle);
            } else if (getControllerBinderIfAble(controller,
                    SessionCommand2.COMMAND_CODE_PLAYLIST_GET_LIST_METADATA) != null) {
                iController.onPlaylistMetadataChanged(metadataBundle);
            }
        });
    }

    public void notifyPlaylistMetadataChangedNotLocked(MediaMetadata2 metadata) {
        final Bundle metadataBundle = (metadata == null) ? null : metadata.toBundle();
        notifyAll(SessionCommand2.COMMAND_CODE_PLAYLIST_GET_LIST_METADATA,
                (unused, iController) -> {
                    iController.onPlaylistMetadataChanged(metadataBundle);
                });
    }

    public void notifyRepeatModeChangedNotLocked(int repeatMode) {
        notifyAll((unused, iController) -> {
            iController.onRepeatModeChanged(repeatMode);
        });
    }

    public void notifyShuffleModeChangedNotLocked(int shuffleMode) {
        notifyAll((unused, iController) -> {
            iController.onShuffleModeChanged(shuffleMode);
        });
    }

    public void notifyPlaybackInfoChanged(MediaController2.PlaybackInfo playbackInfo) {
        final Bundle playbackInfoBundle =
                ((MediaController2Impl.PlaybackInfoImpl) playbackInfo.getProvider()).toBundle();
        notifyAll((unused, iController) -> {
            iController.onPlaybackInfoChanged(playbackInfoBundle);
        });
    }

    public void setAllowedCommands(ControllerInfo controller, SessionCommandGroup2 commands) {
        synchronized (mLock) {
            mAllowedCommandGroupMap.put(controller, commands);
        }
        notify(controller, (unused, iController) -> {
            iController.onAllowedCommandsChanged(commands.toBundle());
        });
    }

    public void sendCustomCommand(ControllerInfo controller, SessionCommand2 command, Bundle args,
            ResultReceiver receiver) {
        if (receiver != null && controller == null) {
            throw new IllegalArgumentException("Controller shouldn't be null if result receiver is"
                    + " specified");
        }
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        notify(controller, (unused, iController) -> {
            Bundle commandBundle = command.toBundle();
            iController.onCustomCommand(commandBundle, args, null);
        });
    }

    public void sendCustomCommand(SessionCommand2 command, Bundle args) {
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        Bundle commandBundle = command.toBundle();
        notifyAll((unused, iController) -> {
            iController.onCustomCommand(commandBundle, args, null);
        });
    }

    public void notifyError(int errorCode, Bundle extras) {
        notifyAll((unused, iController) -> {
            iController.onError(errorCode, extras);
        });
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // APIs for MediaLibrarySessionImpl
    //////////////////////////////////////////////////////////////////////////////////////////////

    public void notifySearchResultChanged(ControllerInfo controller, String query, int itemCount,
            Bundle extras) {
        notify(controller, (unused, iController) -> {
            iController.onSearchResultChanged(query, itemCount, extras);
        });
    }

    public void notifyChildrenChangedNotLocked(ControllerInfo controller, String parentId,
            int itemCount, Bundle extras) {
        notify(controller, (unused, iController) -> {
            if (isSubscribed(controller, parentId)) {
                iController.onChildrenChanged(parentId, itemCount, extras);
            }
        });
    }

    public void notifyChildrenChangedNotLocked(String parentId, int itemCount, Bundle extras) {
        notifyAll((controller, iController) -> {
            if (isSubscribed(controller, parentId)) {
                iController.onChildrenChanged(parentId, itemCount, extras);
            }
        });
    }

    private boolean isSubscribed(ControllerInfo controller, String parentId) {
        synchronized (mLock) {
            Set<String> subscriptions = mSubscriptions.get(controller);
            if (subscriptions == null || !subscriptions.contains(parentId)) {
                return false;
            }
        }
        return true;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // Misc
    //////////////////////////////////////////////////////////////////////////////////////////////

    @FunctionalInterface
    private interface SessionRunnable {
        void run(final MediaSession2Impl session, final ControllerInfo controller);
    }

    @FunctionalInterface
    private interface LibrarySessionRunnable {
        void run(final MediaLibrarySessionImpl session, final ControllerInfo controller);
    }

    @FunctionalInterface
    private interface NotifyRunnable {
        void run(final ControllerInfo controller,
                final IMediaController2 iController) throws RemoteException;
    }
}

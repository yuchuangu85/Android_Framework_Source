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
import android.media.MediaMetadata2;
import android.media.SessionCommand2;
import android.media.MediaSession2.CommandButton;
import android.media.SessionCommandGroup2;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;

import com.android.media.MediaController2Impl.PlaybackInfoImpl;
import com.android.media.MediaSession2Impl.CommandButtonImpl;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class MediaController2Stub extends IMediaController2.Stub {
    private static final String TAG = "MediaController2Stub";
    private static final boolean DEBUG = true; // TODO(jaewan): Change

    private final WeakReference<MediaController2Impl> mController;

    MediaController2Stub(MediaController2Impl controller) {
        mController = new WeakReference<>(controller);
    }

    private MediaController2Impl getController() throws IllegalStateException {
        final MediaController2Impl controller = mController.get();
        if (controller == null) {
            throw new IllegalStateException("Controller is released");
        }
        return controller;
    }

    private MediaBrowser2Impl getBrowser() throws IllegalStateException {
        final MediaController2Impl controller = getController();
        if (controller instanceof MediaBrowser2Impl) {
            return (MediaBrowser2Impl) controller;
        }
        return null;
    }

    public void destroy() {
        mController.clear();
    }

    @Override
    public void onPlayerStateChanged(int state) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        controller.pushPlayerStateChanges(state);
    }

    @Override
    public void onPositionChanged(long eventTimeMs, long positionMs) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (eventTimeMs < 0) {
            Log.w(TAG, "onPositionChanged(): Ignoring negative eventTimeMs");
            return;
        }
        if (positionMs < 0) {
            Log.w(TAG, "onPositionChanged(): Ignoring negative positionMs");
            return;
        }
        controller.pushPositionChanges(eventTimeMs, positionMs);
    }

    @Override
    public void onPlaybackSpeedChanged(float speed) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        controller.pushPlaybackSpeedChanges(speed);
    }

    @Override
    public void onBufferedPositionChanged(long bufferedPositionMs) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (bufferedPositionMs < 0) {
            Log.w(TAG, "onBufferedPositionChanged(): Ignoring negative bufferedPositionMs");
            return;
        }
        controller.pushBufferedPositionChanges(bufferedPositionMs);
    }

    @Override
    public void onPlaylistChanged(List<Bundle> playlistBundle, Bundle metadataBundle) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (playlistBundle == null) {
            Log.w(TAG, "onPlaylistChanged(): Ignoring null playlist from " + controller);
            return;
        }
        List<MediaItem2> playlist = new ArrayList<>();
        for (Bundle bundle : playlistBundle) {
            MediaItem2 item = MediaItem2.fromBundle(bundle);
            if (item == null) {
                Log.w(TAG, "onPlaylistChanged(): Ignoring null item in playlist");
            } else {
                playlist.add(item);
            }
        }
        MediaMetadata2 metadata = MediaMetadata2.fromBundle(metadataBundle);
        controller.pushPlaylistChanges(playlist, metadata);
    }

    @Override
    public void onPlaylistMetadataChanged(Bundle metadataBundle) throws RuntimeException {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        MediaMetadata2 metadata = MediaMetadata2.fromBundle(metadataBundle);
        controller.pushPlaylistMetadataChanges(metadata);
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        controller.pushRepeatModeChanges(repeatMode);
    }

    @Override
    public void onPlaybackInfoChanged(Bundle playbackInfo) throws RuntimeException {
        if (DEBUG) {
            Log.d(TAG, "onPlaybackInfoChanged");
        }
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        MediaController2.PlaybackInfo info = PlaybackInfoImpl.fromBundle(playbackInfo);
        if (info == null) {
            Log.w(TAG, "onPlaybackInfoChanged(): Ignoring null playbackInfo");
            return;
        }
        controller.pushPlaybackInfoChanges(info);
    }

    @Override
    public void onShuffleModeChanged(int shuffleMode) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        controller.pushShuffleModeChanges(shuffleMode);
    }

    @Override
    public void onError(int errorCode, Bundle extras) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        controller.pushError(errorCode, extras);
    }

    @Override
    public void onConnected(IMediaSession2 sessionBinder, Bundle commandGroup,
            int playerState, long positionEventTimeMs, long positionMs, float playbackSpeed,
            long bufferedPositionMs, Bundle playbackInfo, int shuffleMode, int repeatMode,
            List<Bundle> itemBundleList, PendingIntent sessionActivity) {
        final MediaController2Impl controller = mController.get();
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "onConnected after MediaController2.close()");
            }
            return;
        }
        final Context context = controller.getContext();
        List<MediaItem2> itemList = null;
        if (itemBundleList != null) {
            itemList = new ArrayList<>();
            for (int i = 0; i < itemBundleList.size(); i++) {
                MediaItem2 item = MediaItem2.fromBundle(itemBundleList.get(i));
                if (item != null) {
                    itemList.add(item);
                }
            }
        }
        controller.onConnectedNotLocked(sessionBinder,
                SessionCommandGroup2.fromBundle(commandGroup),
                playerState, positionEventTimeMs, positionMs, playbackSpeed, bufferedPositionMs,
                PlaybackInfoImpl.fromBundle(playbackInfo), repeatMode, shuffleMode,
                itemList, sessionActivity);
    }

    @Override
    public void onDisconnected() {
        final MediaController2Impl controller = mController.get();
        if (controller == null) {
            if (DEBUG) {
                Log.d(TAG, "onDisconnected after MediaController2.close()");
            }
            return;
        }
        controller.getInstance().close();
    }

    @Override
    public void onCustomLayoutChanged(List<Bundle> commandButtonlist) {
        if (commandButtonlist == null) {
            Log.w(TAG, "onCustomLayoutChanged(): Ignoring null commandButtonlist");
            return;
        }
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (controller == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        List<CommandButton> layout = new ArrayList<>();
        for (int i = 0; i < commandButtonlist.size(); i++) {
            CommandButton button = CommandButtonImpl.fromBundle(commandButtonlist.get(i));
            if (button != null) {
                layout.add(button);
            }
        }
        controller.onCustomLayoutChanged(layout);
    }

    @Override
    public void onAllowedCommandsChanged(Bundle commandsBundle) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (controller == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        SessionCommandGroup2 commands = SessionCommandGroup2.fromBundle(commandsBundle);
        if (commands == null) {
            Log.w(TAG, "onAllowedCommandsChanged(): Ignoring null commands");
            return;
        }
        controller.onAllowedCommandsChanged(commands);
    }

    @Override
    public void onCustomCommand(Bundle commandBundle, Bundle args, ResultReceiver receiver) {
        final MediaController2Impl controller;
        try {
            controller = getController();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        SessionCommand2 command = SessionCommand2.fromBundle(commandBundle);
        if (command == null) {
            Log.w(TAG, "onCustomCommand(): Ignoring null command");
            return;
        }
        controller.onCustomCommand(command, args, receiver);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////
    // MediaBrowser specific
    ////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public void onGetLibraryRootDone(Bundle rootHints, String rootMediaId, Bundle rootExtra)
            throws RuntimeException {
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        browser.onGetLibraryRootDone(rootHints, rootMediaId, rootExtra);
    }


    @Override
    public void onGetItemDone(String mediaId, Bundle itemBundle) throws RuntimeException {
        if (mediaId == null) {
            Log.w(TAG, "onGetItemDone(): Ignoring null mediaId");
            return;
        }
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        browser.onGetItemDone(mediaId, MediaItem2.fromBundle(itemBundle));
    }

    @Override
    public void onGetChildrenDone(String parentId, int page, int pageSize,
            List<Bundle> itemBundleList, Bundle extras) throws RuntimeException {
        if (parentId == null) {
            Log.w(TAG, "onGetChildrenDone(): Ignoring null parentId");
            return;
        }
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }

        List<MediaItem2> result = null;
        if (itemBundleList != null) {
            result = new ArrayList<>();
            for (Bundle bundle : itemBundleList) {
                result.add(MediaItem2.fromBundle(bundle));
            }
        }
        browser.onGetChildrenDone(parentId, page, pageSize, result, extras);
    }

    @Override
    public void onSearchResultChanged(String query, int itemCount, Bundle extras)
            throws RuntimeException {
        if (TextUtils.isEmpty(query)) {
            Log.w(TAG, "onSearchResultChanged(): Ignoring empty query");
            return;
        }
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        browser.onSearchResultChanged(query, itemCount, extras);
    }

    @Override
    public void onGetSearchResultDone(String query, int page, int pageSize,
            List<Bundle> itemBundleList, Bundle extras) throws RuntimeException {
        if (TextUtils.isEmpty(query)) {
            Log.w(TAG, "onGetSearchResultDone(): Ignoring empty query");
            return;
        }
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }

        List<MediaItem2> result = null;
        if (itemBundleList != null) {
            result = new ArrayList<>();
            for (Bundle bundle : itemBundleList) {
                result.add(MediaItem2.fromBundle(bundle));
            }
        }
        browser.onGetSearchResultDone(query, page, pageSize, result, extras);
    }

    @Override
    public void onChildrenChanged(String parentId, int itemCount, Bundle extras) {
        if (parentId == null) {
            Log.w(TAG, "onChildrenChanged(): Ignoring null parentId");
            return;
        }
        final MediaBrowser2Impl browser;
        try {
            browser = getBrowser();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Don't fail silently here. Highly likely a bug");
            return;
        }
        if (browser == null) {
            // TODO(jaewan): Revisit here. Could be a bug
            return;
        }
        browser.onChildrenChanged(parentId, itemCount, extras);
    }
}

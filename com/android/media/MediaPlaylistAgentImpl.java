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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.DataSourceDesc;
import android.media.MediaItem2;
import android.media.MediaMetadata2;
import android.media.MediaPlaylistAgent;
import android.media.MediaPlaylistAgent.PlaylistEventCallback;
import android.media.update.MediaPlaylistAgentProvider;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.List;
import java.util.concurrent.Executor;

public class MediaPlaylistAgentImpl implements MediaPlaylistAgentProvider {
    private static final String TAG = "MediaPlaylistAgent";

    private final MediaPlaylistAgent mInstance;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<PlaylistEventCallback, Executor> mCallbacks = new ArrayMap<>();

    public MediaPlaylistAgentImpl(MediaPlaylistAgent instance) {
        mInstance = instance;
    }

    @Override
    final public void registerPlaylistEventCallback_impl(
            @NonNull @CallbackExecutor Executor executor, @NonNull PlaylistEventCallback callback) {
        if (executor == null) {
            throw new IllegalArgumentException("executor shouldn't be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback shouldn't be null");
        }

        synchronized (mLock) {
            if (mCallbacks.get(callback) != null) {
                Log.w(TAG, "callback is already added. Ignoring.");
                return;
            }
            mCallbacks.put(callback, executor);
        }
    }

    @Override
    final public void unregisterPlaylistEventCallback_impl(
            @NonNull PlaylistEventCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback shouldn't be null");
        }
        synchronized (mLock) {
            mCallbacks.remove(callback);
        }
    }

    @Override
    final public void notifyPlaylistChanged_impl() {
        ArrayMap<PlaylistEventCallback, Executor> callbacks = getCallbacks();
        List<MediaItem2> playlist= mInstance.getPlaylist();
        MediaMetadata2 metadata = mInstance.getPlaylistMetadata();
        for (int i = 0; i < callbacks.size(); i++) {
            final PlaylistEventCallback callback = callbacks.keyAt(i);
            final Executor executor = callbacks.valueAt(i);
            executor.execute(() -> callback.onPlaylistChanged(
                    mInstance, playlist, metadata));
        }
    }

    @Override
    final public void notifyPlaylistMetadataChanged_impl() {
        ArrayMap<PlaylistEventCallback, Executor> callbacks = getCallbacks();
        for (int i = 0; i < callbacks.size(); i++) {
            final PlaylistEventCallback callback = callbacks.keyAt(i);
            final Executor executor = callbacks.valueAt(i);
            executor.execute(() -> callback.onPlaylistMetadataChanged(
                    mInstance, mInstance.getPlaylistMetadata()));
        }
    }

    @Override
    final public void notifyShuffleModeChanged_impl() {
        ArrayMap<PlaylistEventCallback, Executor> callbacks = getCallbacks();
        for (int i = 0; i < callbacks.size(); i++) {
            final PlaylistEventCallback callback = callbacks.keyAt(i);
            final Executor executor = callbacks.valueAt(i);
            executor.execute(() -> callback.onShuffleModeChanged(
                    mInstance, mInstance.getShuffleMode()));
        }
    }

    @Override
    final public void notifyRepeatModeChanged_impl() {
        ArrayMap<PlaylistEventCallback, Executor> callbacks = getCallbacks();
        for (int i = 0; i < callbacks.size(); i++) {
            final PlaylistEventCallback callback = callbacks.keyAt(i);
            final Executor executor = callbacks.valueAt(i);
            executor.execute(() -> callback.onRepeatModeChanged(
                    mInstance, mInstance.getRepeatMode()));
        }
    }

    @Override
    public @Nullable List<MediaItem2> getPlaylist_impl() {
        // empty implementation
        return null;
    }

    @Override
    public void setPlaylist_impl(@NonNull List<MediaItem2> list,
            @Nullable MediaMetadata2 metadata) {
        // empty implementation
    }

    @Override
    public @Nullable MediaMetadata2 getPlaylistMetadata_impl() {
        // empty implementation
        return null;
    }

    @Override
    public void updatePlaylistMetadata_impl(@Nullable MediaMetadata2 metadata) {
        // empty implementation
    }

    @Override
    public void addPlaylistItem_impl(int index, @NonNull MediaItem2 item) {
        // empty implementation
    }

    @Override
    public void removePlaylistItem_impl(@NonNull MediaItem2 item) {
        // empty implementation
    }

    @Override
    public void replacePlaylistItem_impl(int index, @NonNull MediaItem2 item) {
        // empty implementation
    }

    @Override
    public void skipToPlaylistItem_impl(@NonNull MediaItem2 item) {
        // empty implementation
    }

    @Override
    public void skipToPreviousItem_impl() {
        // empty implementation
    }

    @Override
    public void skipToNextItem_impl() {
        // empty implementation
    }

    @Override
    public int getRepeatMode_impl() {
        return MediaPlaylistAgent.REPEAT_MODE_NONE;
    }

    @Override
    public void setRepeatMode_impl(int repeatMode) {
        // empty implementation
    }

    @Override
    public int getShuffleMode_impl() {
        // empty implementation
        return MediaPlaylistAgent.SHUFFLE_MODE_NONE;
    }

    @Override
    public void setShuffleMode_impl(int shuffleMode) {
        // empty implementation
    }

    @Override
    public @Nullable MediaItem2 getMediaItem_impl(@NonNull DataSourceDesc dsd) {
        if (dsd == null) {
            throw new IllegalArgumentException("dsd shouldn't be null");
        }
        List<MediaItem2> itemList = mInstance.getPlaylist();
        if (itemList == null) {
            return null;
        }
        for (int i = 0; i < itemList.size(); i++) {
            MediaItem2 item = itemList.get(i);
            if (item != null && item.getDataSourceDesc() == dsd) {
                return item;
            }
        }
        return null;
    }

    private ArrayMap<PlaylistEventCallback, Executor> getCallbacks() {
        ArrayMap<PlaylistEventCallback, Executor> callbacks = new ArrayMap<>();
        synchronized (mLock) {
            callbacks.putAll(mCallbacks);
        }
        return callbacks;
    }
}

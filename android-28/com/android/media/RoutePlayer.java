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

import android.content.Context;
import android.media.DataSourceDesc;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;

import com.android.support.mediarouter.media.MediaItemStatus;
import com.android.support.mediarouter.media.MediaRouter;
import com.android.support.mediarouter.media.MediaSessionStatus;
import com.android.support.mediarouter.media.RemotePlaybackClient;
import com.android.support.mediarouter.media.RemotePlaybackClient.ItemActionCallback;
import com.android.support.mediarouter.media.RemotePlaybackClient.SessionActionCallback;
import com.android.support.mediarouter.media.RemotePlaybackClient.StatusCallback;

import java.util.Map;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class RoutePlayer extends MediaSession.Callback {
    public static final long PLAYBACK_ACTIONS = PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;

    private RemotePlaybackClient mClient;
    private String mSessionId;
    private String mItemId;
    private PlayerEventCallback mCallback;

    private StatusCallback mStatusCallback = new StatusCallback() {
        @Override
        public void onItemStatusChanged(Bundle data,
                String sessionId, MediaSessionStatus sessionStatus,
                String itemId, MediaItemStatus itemStatus) {
            updateSessionStatus(sessionId, sessionStatus);
            updateItemStatus(itemId, itemStatus);
        }
    };

    public RoutePlayer(Context context, MediaRouter.RouteInfo route) {
        mClient = new RemotePlaybackClient(context, route);
        mClient.setStatusCallback(mStatusCallback);
        mClient.startSession(null, new SessionActionCallback() {
            @Override
            public void onResult(Bundle data,
                    String sessionId, MediaSessionStatus sessionStatus) {
                updateSessionStatus(sessionId, sessionStatus);
            }
        });
    }

    @Override
    public void onPlay() {
        mClient.resume(null, new SessionActionCallback() {
            @Override
            public void onResult(Bundle data,
                    String sessionId, MediaSessionStatus sessionStatus) {
                updateSessionStatus(sessionId, sessionStatus);
            }
        });
    }

    @Override
    public void onPause() {
        mClient.pause(null, new SessionActionCallback() {
            @Override
            public void onResult(Bundle data,
                    String sessionId, MediaSessionStatus sessionStatus) {
                updateSessionStatus(sessionId, sessionStatus);
            }
        });
    }

    @Override
    public void onSeekTo(long pos) {
        mClient.seek(mItemId, pos, null, new ItemActionCallback() {
            @Override
            public void onResult(Bundle data,
                    String sessionId, MediaSessionStatus sessionStatus,
                    String itemId, MediaItemStatus itemStatus) {
                updateSessionStatus(sessionId, sessionStatus);
                updateItemStatus(itemId, itemStatus);
            }
        });
    }

    @Override
    public void onStop() {
        mClient.stop(null, new SessionActionCallback() {
            @Override
            public void onResult(Bundle data,
                    String sessionId, MediaSessionStatus sessionStatus) {
                updateSessionStatus(sessionId, sessionStatus);
            }
        });
    }

    public void setPlayerEventCallback(PlayerEventCallback callback) {
        mCallback = callback;
    }

    public void openVideo(DataSourceDesc dsd) {
        mClient.play(dsd.getUri(), "video/mp4", null, 0, null, new ItemActionCallback() {
            @Override
            public void onResult(Bundle data,
                    String sessionId, MediaSessionStatus sessionStatus,
                    String itemId, MediaItemStatus itemStatus) {
                updateSessionStatus(sessionId, sessionStatus);
                updateItemStatus(itemId, itemStatus);
                playInternal(dsd.getUri());
            }
        });
    }

    public void release() {
        if (mClient != null) {
            mClient.release();
            mClient = null;
        }
        if (mCallback != null) {
            mCallback = null;
        }
    }

    private void playInternal(Uri uri) {
        mClient.play(uri, "video/mp4", null, 0, null, new ItemActionCallback() {
            @Override
            public void onResult(Bundle data,
                    String sessionId, MediaSessionStatus sessionStatus,
                    String itemId, MediaItemStatus itemStatus) {
                updateSessionStatus(sessionId, sessionStatus);
                updateItemStatus(itemId, itemStatus);
            }
        });
    }

    private void updateSessionStatus(String sessionId, MediaSessionStatus sessionStatus) {
        mSessionId = sessionId;
    }

    private void updateItemStatus(String itemId, MediaItemStatus itemStatus) {
        mItemId = itemId;
        if (itemStatus == null || mCallback == null) return;
        mCallback.onPlayerStateChanged(itemStatus);
    }

    public static abstract class PlayerEventCallback {
        public void onPlayerStateChanged(MediaItemStatus itemStatus) { }
    }
}

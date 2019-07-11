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

import static android.content.Context.NOTIFICATION_SERVICE;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayerBase;
import android.media.MediaPlayerBase.PlayerEventCallback;
import android.media.MediaSession2;
import android.media.MediaSessionService2;
import android.media.MediaSessionService2.MediaNotification;
import android.media.SessionToken2;
import android.media.SessionToken2.TokenType;
import android.media.update.MediaSessionService2Provider;
import android.os.IBinder;
import android.support.annotation.GuardedBy;
import android.util.Log;

// TODO(jaewan): Need a test for session service itself.
public class MediaSessionService2Impl implements MediaSessionService2Provider {

    private static final String TAG = "MPSessionService"; // to meet 23 char limit in Log tag
    private static final boolean DEBUG = true; // TODO(jaewan): Change this. (b/74094611)

    private final MediaSessionService2 mInstance;
    private final PlayerEventCallback mCallback = new SessionServiceEventCallback();

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private NotificationManager mNotificationManager;
    @GuardedBy("mLock")
    private Intent mStartSelfIntent;

    private boolean mIsRunningForeground;
    private MediaSession2 mSession;

    public MediaSessionService2Impl(MediaSessionService2 instance) {
        if (DEBUG) {
            Log.d(TAG, "MediaSessionService2Impl(" + instance + ")");
        }
        mInstance = instance;
    }

    @Override
    public MediaSession2 getSession_impl() {
        return getSession();
    }

    MediaSession2 getSession() {
        synchronized (mLock) {
            return mSession;
        }
    }

    @Override
    public MediaNotification onUpdateNotification_impl() {
        // Provide default notification UI later.
        return null;
    }

    @Override
    public void onCreate_impl() {
        mNotificationManager = (NotificationManager) mInstance.getSystemService(
                NOTIFICATION_SERVICE);
        mStartSelfIntent = new Intent(mInstance, mInstance.getClass());

        SessionToken2 token = new SessionToken2(mInstance, mInstance.getPackageName(),
                mInstance.getClass().getName());
        if (token.getType() != getSessionType()) {
            throw new RuntimeException("Expected session service, but was " + token.getType());
        }
        mSession = mInstance.onCreateSession(token.getId());
        if (mSession == null || !token.getId().equals(mSession.getToken().getId())) {
            throw new RuntimeException("Expected session with id " + token.getId()
                    + ", but got " + mSession);
        }
        // TODO(jaewan): Uncomment here.
        // mSession.registerPlayerEventCallback(mCallback, mSession.getExecutor());
    }

    @TokenType int getSessionType() {
        return SessionToken2.TYPE_SESSION_SERVICE;
    }

    public IBinder onBind_impl(Intent intent) {
        if (MediaSessionService2.SERVICE_INTERFACE.equals(intent.getAction())) {
            return ((MediaSession2Impl) mSession.getProvider()).getSessionStub().asBinder();
        }
        return null;
    }

    private void updateNotification(int playerState) {
        MediaNotification mediaNotification = mInstance.onUpdateNotification();
        if (mediaNotification == null) {
            return;
        }
        switch(playerState) {
            case MediaPlayerBase.PLAYER_STATE_PLAYING:
                if (!mIsRunningForeground) {
                    mIsRunningForeground = true;
                    mInstance.startForegroundService(mStartSelfIntent);
                    mInstance.startForeground(mediaNotification.getNotificationId(),
                            mediaNotification.getNotification());
                    return;
                }
                break;
            case MediaPlayerBase.PLAYER_STATE_IDLE:
            case MediaPlayerBase.PLAYER_STATE_ERROR:
                if (mIsRunningForeground) {
                    mIsRunningForeground = false;
                    mInstance.stopForeground(true);
                    return;
                }
                break;
        }
        mNotificationManager.notify(mediaNotification.getNotificationId(),
                mediaNotification.getNotification());
    }

    private class SessionServiceEventCallback extends PlayerEventCallback {
        @Override
        public void onPlayerStateChanged(MediaPlayerBase player, int state) {
            // TODO: Implement this
            return;
        }
    }

    public static class MediaNotificationImpl implements MediaNotificationProvider {
        private int mNotificationId;
        private Notification mNotification;

        public MediaNotificationImpl(MediaNotification instance, int notificationId,
                Notification notification) {
            if (notification == null) {
                throw new IllegalArgumentException("notification shouldn't be null");
            }
            mNotificationId = notificationId;
            mNotification = notification;
        }

        @Override
        public int getNotificationId_impl() {
            return mNotificationId;
        }

        @Override
        public Notification getNotification_impl() {
            return mNotification;
        }
    }
}

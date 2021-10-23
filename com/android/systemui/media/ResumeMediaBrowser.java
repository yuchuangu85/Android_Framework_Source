/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * Media browser for managing resumption in media controls
 */
public class ResumeMediaBrowser {

    /** Maximum number of controls to show on boot */
    public static final int MAX_RESUMPTION_CONTROLS = 5;

    /** Delimiter for saved component names */
    public static final String DELIMITER = ":";

    private static final String TAG = "ResumeMediaBrowser";
    private final Context mContext;
    @Nullable private final Callback mCallback;
    private MediaBrowserFactory mBrowserFactory;
    private MediaBrowser mMediaBrowser;
    private ComponentName mComponentName;

    /**
     * Initialize a new media browser
     * @param context the context
     * @param callback used to report media items found
     * @param componentName Component name of the MediaBrowserService this browser will connect to
     */
    public ResumeMediaBrowser(Context context, @Nullable Callback callback,
            ComponentName componentName, MediaBrowserFactory browserFactory) {
        mContext = context;
        mCallback = callback;
        mComponentName = componentName;
        mBrowserFactory = browserFactory;
    }

    /**
     * Connects to the MediaBrowserService and looks for valid media. If a media item is returned,
     * ResumeMediaBrowser.Callback#addTrack will be called with the MediaDescription.
     * ResumeMediaBrowser.Callback#onConnected and ResumeMediaBrowser.Callback#onError will also be
     * called when the initial connection is successful, or an error occurs.
     * Note that it is possible for the service to connect but for no playable tracks to be found.
     * ResumeMediaBrowser#disconnect will be called automatically with this function.
     */
    public void findRecentMedia() {
        Log.d(TAG, "Connecting to " + mComponentName);
        disconnect();
        Bundle rootHints = new Bundle();
        rootHints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        mMediaBrowser = mBrowserFactory.create(
                mComponentName,
                mConnectionCallback,
                rootHints);
        mMediaBrowser.connect();
    }

    private final MediaBrowser.SubscriptionCallback mSubscriptionCallback =
            new MediaBrowser.SubscriptionCallback() {
        @Override
        public void onChildrenLoaded(String parentId,
                List<MediaBrowser.MediaItem> children) {
            if (children.size() == 0) {
                Log.d(TAG, "No children found for " + mComponentName);
                if (mCallback != null) {
                    mCallback.onError();
                }
            } else {
                // We ask apps to return a playable item as the first child when sending
                // a request with EXTRA_RECENT; if they don't, no resume controls
                MediaBrowser.MediaItem child = children.get(0);
                MediaDescription desc = child.getDescription();
                if (child.isPlayable() && mMediaBrowser != null) {
                    if (mCallback != null) {
                        mCallback.addTrack(desc, mMediaBrowser.getServiceComponent(),
                                ResumeMediaBrowser.this);
                    }
                } else {
                    Log.d(TAG, "Child found but not playable for " + mComponentName);
                    if (mCallback != null) {
                        mCallback.onError();
                    }
                }
            }
            disconnect();
        }

        @Override
        public void onError(String parentId) {
            Log.d(TAG, "Subscribe error for " + mComponentName + ": " + parentId);
            if (mCallback != null) {
                mCallback.onError();
            }
            disconnect();
        }

        @Override
        public void onError(String parentId, Bundle options) {
            Log.d(TAG, "Subscribe error for " + mComponentName + ": " + parentId
                    + ", options: " + options);
            if (mCallback != null) {
                mCallback.onError();
            }
            disconnect();
        }
    };

    private final MediaBrowser.ConnectionCallback mConnectionCallback =
            new MediaBrowser.ConnectionCallback() {
        /**
         * Invoked after {@link MediaBrowser#connect()} when the request has successfully completed.
         * For resumption controls, apps are expected to return a playable media item as the first
         * child. If there are no children or it isn't playable it will be ignored.
         */
        @Override
        public void onConnected() {
            Log.d(TAG, "Service connected for " + mComponentName);
            if (mMediaBrowser != null && mMediaBrowser.isConnected()) {
                String root = mMediaBrowser.getRoot();
                if (!TextUtils.isEmpty(root)) {
                    if (mCallback != null) {
                        mCallback.onConnected();
                    }
                    if (mMediaBrowser != null) {
                        mMediaBrowser.subscribe(root, mSubscriptionCallback);
                    }
                    return;
                }
            }
            if (mCallback != null) {
                mCallback.onError();
            }
            disconnect();
        }

        /**
         * Invoked when the client is disconnected from the media browser.
         */
        @Override
        public void onConnectionSuspended() {
            Log.d(TAG, "Connection suspended for " + mComponentName);
            if (mCallback != null) {
                mCallback.onError();
            }
            disconnect();
        }

        /**
         * Invoked when the connection to the media browser failed.
         */
        @Override
        public void onConnectionFailed() {
            Log.d(TAG, "Connection failed for " + mComponentName);
            if (mCallback != null) {
                mCallback.onError();
            }
            disconnect();
        }
    };

    /**
     * Disconnect the media browser. This should be done after callbacks have completed to
     * disconnect from the media browser service.
     */
    protected void disconnect() {
        if (mMediaBrowser != null) {
            mMediaBrowser.disconnect();
        }
        mMediaBrowser = null;
    }

    /**
     * Connects to the MediaBrowserService and starts playback.
     * ResumeMediaBrowser.Callback#onError or ResumeMediaBrowser.Callback#onConnected will be called
     * depending on whether it was successful.
     * If the connection is successful, the listener should call ResumeMediaBrowser#disconnect after
     * getting a media update from the app
     */
    public void restart() {
        disconnect();
        Bundle rootHints = new Bundle();
        rootHints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        mMediaBrowser = mBrowserFactory.create(mComponentName,
                new MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        Log.d(TAG, "Connected for restart " + mMediaBrowser.isConnected());
                        if (mMediaBrowser == null || !mMediaBrowser.isConnected()) {
                            if (mCallback != null) {
                                mCallback.onError();
                            }
                            disconnect();
                            return;
                        }
                        MediaSession.Token token = mMediaBrowser.getSessionToken();
                        MediaController controller = createMediaController(token);
                        controller.getTransportControls();
                        controller.getTransportControls().prepare();
                        controller.getTransportControls().play();
                        if (mCallback != null) {
                            mCallback.onConnected();
                        }
                        // listener should disconnect after media player update
                    }

                    @Override
                    public void onConnectionFailed() {
                        if (mCallback != null) {
                            mCallback.onError();
                        }
                        disconnect();
                    }

                    @Override
                    public void onConnectionSuspended() {
                        if (mCallback != null) {
                            mCallback.onError();
                        }
                        disconnect();
                    }
                }, rootHints);
        mMediaBrowser.connect();
    }

    @VisibleForTesting
    protected MediaController createMediaController(MediaSession.Token token) {
        return new MediaController(mContext, token);
    }

    /**
     * Get the media session token
     * @return the token, or null if the MediaBrowser is null or disconnected
     */
    public MediaSession.Token getToken() {
        if (mMediaBrowser == null || !mMediaBrowser.isConnected()) {
            return null;
        }
        return mMediaBrowser.getSessionToken();
    }

    /**
     * Get an intent to launch the app associated with this browser service
     * @return
     */
    public PendingIntent getAppIntent() {
        PackageManager pm = mContext.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(mComponentName.getPackageName());
        return PendingIntent.getActivity(mContext, 0, launchIntent, PendingIntent.FLAG_MUTABLE_UNAUDITED);
    }

    /**
     * Used to test if SystemUI is allowed to connect to the given component as a MediaBrowser.
     * If it can connect, ResumeMediaBrowser.Callback#onConnected will be called. If valid media is
     * found, then ResumeMediaBrowser.Callback#addTrack will also be called. This allows for more
     * detailed logging if the service has issues. If it cannot connect, or cannot find valid media,
     * then ResumeMediaBrowser.Callback#onError will be called.
     * ResumeMediaBrowser#disconnect should be called after this to ensure the connection is closed.
     */
    public void testConnection() {
        disconnect();
        Bundle rootHints = new Bundle();
        rootHints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        mMediaBrowser = mBrowserFactory.create(
                mComponentName,
                mConnectionCallback,
                rootHints);
        mMediaBrowser.connect();
    }

    /**
     * Interface to handle results from ResumeMediaBrowser
     */
    public static class Callback {
        /**
         * Called when the browser has successfully connected to the service
         */
        public void onConnected() {
        }

        /**
         * Called when the browser encountered an error connecting to the service
         */
        public void onError() {
        }

        /**
         * Called when the browser finds a suitable track to add to the media carousel
         * @param track media info for the item
         * @param component component of the MediaBrowserService which returned this
         * @param browser reference to the browser
         */
        public void addTrack(MediaDescription track, ComponentName component,
                ResumeMediaBrowser browser) {
        }
    }
}

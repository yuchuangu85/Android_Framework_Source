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
import android.media.MediaLibraryService2;
import android.media.MediaLibraryService2.LibraryRoot;
import android.media.MediaLibraryService2.MediaLibrarySession;
import android.media.MediaLibraryService2.MediaLibrarySession.Builder;
import android.media.MediaLibraryService2.MediaLibrarySession.MediaLibrarySessionCallback;
import android.media.MediaPlayerBase;
import android.media.MediaPlaylistAgent;
import android.media.MediaSession2;
import android.media.MediaSession2.ControllerInfo;
import android.media.MediaSessionService2;
import android.media.SessionToken2;
import android.media.VolumeProvider2;
import android.media.update.MediaLibraryService2Provider;
import android.os.Bundle;
import android.text.TextUtils;

import com.android.media.MediaSession2Impl.BuilderBaseImpl;

import java.util.concurrent.Executor;

public class MediaLibraryService2Impl extends MediaSessionService2Impl implements
        MediaLibraryService2Provider {
    private final MediaSessionService2 mInstance;
    private MediaLibrarySession mLibrarySession;

    public MediaLibraryService2Impl(MediaLibraryService2 instance) {
        super(instance);
        mInstance = instance;
    }

    @Override
    public void onCreate_impl() {
        super.onCreate_impl();

        // Effectively final
        MediaSession2 session = getSession();
        if (!(session instanceof MediaLibrarySession)) {
            throw new RuntimeException("Expected MediaLibrarySession, but returned MediaSession2");
        }
        mLibrarySession = (MediaLibrarySession) getSession();
    }

    @Override
    int getSessionType() {
        return SessionToken2.TYPE_LIBRARY_SERVICE;
    }

    public static class MediaLibrarySessionImpl extends MediaSession2Impl
            implements MediaLibrarySessionProvider {
        public MediaLibrarySessionImpl(Context context,
                MediaPlayerBase player, String id, MediaPlaylistAgent playlistAgent,
                VolumeProvider2 volumeProvider,
                PendingIntent sessionActivity, Executor callbackExecutor,
                MediaLibrarySessionCallback callback) {
            super(context, player, id, playlistAgent, volumeProvider, sessionActivity,
                    callbackExecutor, callback);
            // Don't put any extra initialization here. Here's the reason.
            // System service will recognize this session inside of the super constructor and would
            // connect to this session assuming that initialization is finished. However, if any
            // initialization logic is here, calls from the server would fail.
            // see: MediaSession2Stub#connect()
        }

        @Override
        MediaLibrarySession createInstance() {
            return new MediaLibrarySession(this);
        }

        @Override
        MediaLibrarySession getInstance() {
            return (MediaLibrarySession) super.getInstance();
        }

        @Override
        MediaLibrarySessionCallback getCallback() {
            return (MediaLibrarySessionCallback) super.getCallback();
        }

        @Override
        public void notifyChildrenChanged_impl(ControllerInfo controller, String parentId,
                int itemCount, Bundle extras) {
            if (controller == null) {
                throw new IllegalArgumentException("controller shouldn't be null");
            }
            if (parentId == null) {
                throw new IllegalArgumentException("parentId shouldn't be null");
            }
            getSessionStub().notifyChildrenChangedNotLocked(controller, parentId, itemCount,
                    extras);
        }

        @Override
        public void notifyChildrenChanged_impl(String parentId, int itemCount, Bundle extras) {
            if (parentId == null) {
                throw new IllegalArgumentException("parentId shouldn't be null");
            }
            getSessionStub().notifyChildrenChangedNotLocked(parentId, itemCount, extras);
        }

        @Override
        public void notifySearchResultChanged_impl(ControllerInfo controller, String query,
                int itemCount, Bundle extras) {
            ensureCallingThread();
            if (controller == null) {
                throw new IllegalArgumentException("controller shouldn't be null");
            }
            if (TextUtils.isEmpty(query)) {
                throw new IllegalArgumentException("query shouldn't be empty");
            }
            getSessionStub().notifySearchResultChanged(controller, query, itemCount, extras);
        }
    }

    public static class BuilderImpl
            extends BuilderBaseImpl<MediaLibrarySession, MediaLibrarySessionCallback> {
        public BuilderImpl(MediaLibraryService2 service, Builder instance,
                Executor callbackExecutor, MediaLibrarySessionCallback callback) {
            super(service);
            setSessionCallback_impl(callbackExecutor, callback);
        }

        @Override
        public MediaLibrarySession build_impl() {
            return new MediaLibrarySessionImpl(mContext, mPlayer, mId, mPlaylistAgent,
                    mVolumeProvider, mSessionActivity, mCallbackExecutor, mCallback).getInstance();
        }
    }

    public static final class LibraryRootImpl implements LibraryRootProvider {
        private final LibraryRoot mInstance;
        private final String mRootId;
        private final Bundle mExtras;

        public LibraryRootImpl(LibraryRoot instance, String rootId, Bundle extras) {
            if (rootId == null) {
                throw new IllegalArgumentException("rootId shouldn't be null.");
            }
            mInstance = instance;
            mRootId = rootId;
            mExtras = extras;
        }

        @Override
        public String getRootId_impl() {
            return mRootId;
        }

        @Override
        public Bundle getExtras_impl() {
            return mExtras;
        }
    }
}

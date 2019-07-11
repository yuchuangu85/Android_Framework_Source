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
import android.media.MediaBrowser2;
import android.media.MediaBrowser2.BrowserCallback;
import android.media.MediaController2;
import android.media.MediaItem2;
import android.media.SessionToken2;
import android.media.update.MediaBrowser2Provider;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;
import java.util.concurrent.Executor;

public class MediaBrowser2Impl extends MediaController2Impl implements MediaBrowser2Provider {
    private final String TAG = "MediaBrowser2";
    private final boolean DEBUG = true; // TODO(jaewan): change.

    private final MediaBrowser2 mInstance;
    private final MediaBrowser2.BrowserCallback mCallback;

    public MediaBrowser2Impl(Context context, MediaBrowser2 instance, SessionToken2 token,
            Executor executor, BrowserCallback callback) {
        super(context, instance, token, executor, callback);
        mInstance = instance;
        mCallback = callback;
    }

    @Override MediaBrowser2 getInstance() {
        return (MediaBrowser2) super.getInstance();
    }

    @Override
    public void getLibraryRoot_impl(Bundle rootHints) {
        final IMediaSession2 binder = getSessionBinder();
        if (binder != null) {
            try {
                binder.getLibraryRoot(getControllerStub(), rootHints);
            } catch (RemoteException e) {
                // TODO(jaewan): Handle disconnect.
                if (DEBUG) {
                    Log.w(TAG, "Cannot connect to the service or the session is gone", e);
                }
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void subscribe_impl(String parentId, Bundle extras) {
        if (parentId == null) {
            throw new IllegalArgumentException("parentId shouldn't be null");
        }

        final IMediaSession2 binder = getSessionBinder();
        if (binder != null) {
            try {
                binder.subscribe(getControllerStub(), parentId, extras);
            } catch (RemoteException e) {
                // TODO(jaewan): Handle disconnect.
                if (DEBUG) {
                    Log.w(TAG, "Cannot connect to the service or the session is gone", e);
                }
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void unsubscribe_impl(String parentId) {
        if (parentId == null) {
            throw new IllegalArgumentException("parentId shouldn't be null");
        }

        final IMediaSession2 binder = getSessionBinder();
        if (binder != null) {
            try {
                binder.unsubscribe(getControllerStub(), parentId);
            } catch (RemoteException e) {
                // TODO(jaewan): Handle disconnect.
                if (DEBUG) {
                    Log.w(TAG, "Cannot connect to the service or the session is gone", e);
                }
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void getItem_impl(String mediaId) {
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId shouldn't be null");
        }

        final IMediaSession2 binder = getSessionBinder();
        if (binder != null) {
            try {
                binder.getItem(getControllerStub(), mediaId);
            } catch (RemoteException e) {
                // TODO(jaewan): Handle disconnect.
                if (DEBUG) {
                    Log.w(TAG, "Cannot connect to the service or the session is gone", e);
                }
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void getChildren_impl(String parentId, int page, int pageSize, Bundle extras) {
        if (parentId == null) {
            throw new IllegalArgumentException("parentId shouldn't be null");
        }
        if (page < 1 || pageSize < 1) {
            throw new IllegalArgumentException("Neither page nor pageSize should be less than 1");
        }

        final IMediaSession2 binder = getSessionBinder();
        if (binder != null) {
            try {
                binder.getChildren(getControllerStub(), parentId, page, pageSize, extras);
            } catch (RemoteException e) {
                // TODO(jaewan): Handle disconnect.
                if (DEBUG) {
                    Log.w(TAG, "Cannot connect to the service or the session is gone", e);
                }
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void search_impl(String query, Bundle extras) {
        if (TextUtils.isEmpty(query)) {
            throw new IllegalArgumentException("query shouldn't be empty");
        }
        final IMediaSession2 binder = getSessionBinder();
        if (binder != null) {
            try {
                binder.search(getControllerStub(), query, extras);
            } catch (RemoteException e) {
                // TODO(jaewan): Handle disconnect.
                if (DEBUG) {
                    Log.w(TAG, "Cannot connect to the service or the session is gone", e);
                }
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void getSearchResult_impl(String query, int page, int pageSize, Bundle extras) {
        if (TextUtils.isEmpty(query)) {
            throw new IllegalArgumentException("query shouldn't be empty");
        }
        if (page < 1 || pageSize < 1) {
            throw new IllegalArgumentException("Neither page nor pageSize should be less than 1");
        }
        final IMediaSession2 binder = getSessionBinder();
        if (binder != null) {
            try {
                binder.getSearchResult(getControllerStub(), query, page, pageSize, extras);
            } catch (RemoteException e) {
                // TODO(jaewan): Handle disconnect.
                if (DEBUG) {
                    Log.w(TAG, "Cannot connect to the service or the session is gone", e);
                }
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    public void onGetLibraryRootDone(
            final Bundle rootHints, final String rootMediaId, final Bundle rootExtra) {
        getCallbackExecutor().execute(() -> {
            mCallback.onGetLibraryRootDone(getInstance(), rootHints, rootMediaId, rootExtra);
        });
    }

    public void onGetItemDone(String mediaId, MediaItem2 item) {
        getCallbackExecutor().execute(() -> {
            mCallback.onGetItemDone(getInstance(), mediaId, item);
        });
    }

    public void onGetChildrenDone(String parentId, int page, int pageSize, List<MediaItem2> result,
            Bundle extras) {
        getCallbackExecutor().execute(() -> {
            mCallback.onGetChildrenDone(getInstance(), parentId, page, pageSize, result, extras);
        });
    }

    public void onSearchResultChanged(String query, int itemCount, Bundle extras) {
        getCallbackExecutor().execute(() -> {
            mCallback.onSearchResultChanged(getInstance(), query, itemCount, extras);
        });
    }

    public void onGetSearchResultDone(String query, int page, int pageSize, List<MediaItem2> result,
            Bundle extras) {
        getCallbackExecutor().execute(() -> {
            mCallback.onGetSearchResultDone(getInstance(), query, page, pageSize, result, extras);
        });
    }

    public void onChildrenChanged(final String parentId, int itemCount, final Bundle extras) {
        getCallbackExecutor().execute(() -> {
            mCallback.onChildrenChanged(getInstance(), parentId, itemCount, extras);
        });
    }
}

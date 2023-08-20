/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.dream;

import static com.android.systemui.flags.Flags.DREAM_MEDIA_COMPLICATION;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.CoreStartable;
import com.android.systemui.complication.DreamMediaEntryComplication;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.controls.models.player.MediaData;
import com.android.systemui.media.controls.models.recommendation.SmartspaceMediaData;
import com.android.systemui.media.controls.pipeline.MediaDataManager;

import javax.inject.Inject;

/**
 * {@link MediaDreamSentinel} is responsible for tracking media state and registering/unregistering
 * the media complication as appropriate
 */
public class MediaDreamSentinel implements CoreStartable {
    private static final String TAG = "MediaDreamSentinel";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final MediaDataManager.Listener mListener = new MediaDataManager.Listener() {
        private boolean mAdded;
        @Override
        public void onSmartspaceMediaDataRemoved(@NonNull String key, boolean immediately) {
        }

        @Override
        public void onMediaDataRemoved(@NonNull String key) {
            final boolean hasActiveMedia = mMediaDataManager.hasActiveMedia();
            if (DEBUG) {
                Log.d(TAG, "onMediaDataRemoved(" + key + "), mAdded=" + mAdded + ", hasActiveMedia="
                        + hasActiveMedia);
            }

            if (!mAdded) {
                return;
            }

            if (hasActiveMedia) {
                return;
            }

            mAdded = false;
            mDreamOverlayStateController.removeComplication(mMediaEntryComplication);
        }

        @Override
        public void onSmartspaceMediaDataLoaded(@NonNull String key,
                @NonNull SmartspaceMediaData data, boolean shouldPrioritize) {
        }

        @Override
        public void onMediaDataLoaded(@NonNull String key, @Nullable String oldKey,
                @NonNull MediaData data, boolean immediately, int receivedSmartspaceCardLatency,
                boolean isSsReactivated) {
            if (!mFeatureFlags.isEnabled(DREAM_MEDIA_COMPLICATION)) {
                return;
            }

            final boolean hasActiveMedia = mMediaDataManager.hasActiveMedia();
            if (DEBUG) {
                Log.d(TAG, "onMediaDataLoaded(" + key + "), mAdded=" + mAdded + ", hasActiveMedia="
                        + hasActiveMedia);
            }

            // Media data can become inactive without triggering onMediaDataRemoved.
            if (mAdded && !hasActiveMedia) {
                mAdded = false;
                mDreamOverlayStateController.removeComplication(mMediaEntryComplication);
                return;
            }

            if (mAdded) {
                return;
            }

            if (!hasActiveMedia) {
                return;
            }

            mAdded = true;
            mDreamOverlayStateController.addComplication(mMediaEntryComplication);
        }
    };

    private final MediaDataManager mMediaDataManager;
    private final DreamOverlayStateController mDreamOverlayStateController;
    private final DreamMediaEntryComplication mMediaEntryComplication;
    private final FeatureFlags mFeatureFlags;

    @Inject
    public MediaDreamSentinel(MediaDataManager mediaDataManager,
            DreamOverlayStateController dreamOverlayStateController,
            DreamMediaEntryComplication mediaEntryComplication,
            FeatureFlags featureFlags) {
        mMediaDataManager = mediaDataManager;
        mDreamOverlayStateController = dreamOverlayStateController;
        mMediaEntryComplication = mediaEntryComplication;
        mFeatureFlags = featureFlags;
    }

    @Override
    public void start() {
        mMediaDataManager.addListener(mListener);
    }
}

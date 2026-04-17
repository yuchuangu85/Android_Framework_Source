/*
 * Copyright 2025 The Android Open Source Project
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

package android.media.projection;

import android.annotation.FlaggedApi;
import android.os.RemoteException;

import com.android.media.projection.flags.Flags;

/**
 * Represents a media projection session where the application receiving an instance of this class
 * is sharing its own content.
 *
 * <p> The application must use this class to notify if the sharing session terminates from its side
 * (e.g. the shared content is not available anymore).
 */
@FlaggedApi(Flags.FLAG_APP_CONTENT_SHARING)
public class AppContentProjectionSession {

    private final IAppContentProjectionSession mSessionInternal;
    private final boolean mIsAudioRequested;

    /**
     * @hide
     */
    public AppContentProjectionSession(IAppContentProjectionSession sessionInternal,
            boolean isAudioRequested) {
        mSessionInternal = sessionInternal;
        mIsAudioRequested = isAudioRequested;
    }

    /**
     * Notify the system that the content shared is not available anymore and the session must be
     * stopped.
     */
    public void notifySessionStop() {
        try {
            mSessionInternal.notifySessionStop();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * If {@code true}, this means that the user has requested the audio to be shared along with the
     * app content.
     */
    public boolean isAudioRequested() {
        return mIsAudioRequested;
    }
}

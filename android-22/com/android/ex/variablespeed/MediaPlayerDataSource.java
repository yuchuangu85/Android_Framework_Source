/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.ex.variablespeed;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;

import java.io.IOException;

import javax.annotation.concurrent.Immutable;

/**
 * Encapsulates the data source for a media player.
 * <p>
 * Is used to make the setting of the data source for a
 * {@link android.media.MediaPlayer} easier, or the calling of the correct
 * {@link VariableSpeedNative} method done correctly. You should not use this class
 * directly, it is for the benefit of the {@link VariableSpeed} implementation.
 */
@Immutable
/*package*/ class MediaPlayerDataSource {
    private final Context mContext;
    private final Uri mUri;
    private final String mPath;

    public MediaPlayerDataSource(Context context, Uri intentUri) {
        mContext = context;
        mUri = intentUri;
        mPath = null;
    }

    public MediaPlayerDataSource(String path) {
        mContext = null;
        mUri = null;
        mPath = path;
    }

    public void setAsSourceFor(MediaPlayer mediaPlayer) throws IOException {
        if (mContext != null) {
            mediaPlayer.setDataSource(mContext, mUri);
        } else {
            mediaPlayer.setDataSource(mPath);
        }
    }

    public void playNative() throws IOException {
        if (mContext != null) {
            VariableSpeedNative.playFromContext(mContext, mUri);
        } else {
            VariableSpeedNative.playUri(mPath);
        }
    }
}

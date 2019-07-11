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

/**
 * Interface that supports a subset of the operations on {@link android.media.MediaPlayer}.
 *
 * <p>This subset is arbitrarily defined - at the moment it is the subset that the voicemail
 * playback requires.</p>
 *
 * <p>This interface exists to make alternate implementations to the standard media player
 * swappable, as well as making it much easier to test code that directly uses a media player.
 */
public interface MediaPlayerProxy {
    void setOnErrorListener(MediaPlayer.OnErrorListener listener);
    void setOnCompletionListener(MediaPlayer.OnCompletionListener listener);
    void release();
    void reset();
    void setDataSource(String path) throws IllegalStateException, IOException;
    void setDataSource(Context context, Uri intentUri) throws IllegalStateException, IOException;
    void prepare() throws IOException;
    int getDuration();
    void seekTo(int startPosition);
    void start();
    boolean isReadyToPlay();
    boolean isPlaying();
    int getCurrentPosition();
    void pause();
    void setAudioStreamType(int streamType);
}

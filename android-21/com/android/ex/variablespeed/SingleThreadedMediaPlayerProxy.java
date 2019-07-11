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
 * Simple wrapper around a {@link MediaPlayerProxy}, guaranteeing that every call made to the
 * MediaPlayerProxy is single-threaded.
 */
public class SingleThreadedMediaPlayerProxy implements MediaPlayerProxy {
    private final MediaPlayerProxy mDelegate;

    public SingleThreadedMediaPlayerProxy(MediaPlayerProxy delegate) {
        mDelegate = delegate;
    }

    @Override
    public synchronized void setOnErrorListener(MediaPlayer.OnErrorListener listener) {
        mDelegate.setOnErrorListener(listener);
    }

    @Override
    public synchronized void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
        mDelegate.setOnCompletionListener(listener);
    }

    @Override
    public synchronized void release() {
        mDelegate.release();
    }

    @Override
    public synchronized void reset() {
        mDelegate.reset();
    }

    @Override
    public synchronized void setDataSource(String path) throws IllegalStateException, IOException {
        mDelegate.setDataSource(path);
    }

    @Override
    public synchronized void setDataSource(Context context, Uri intentUri)
            throws IllegalStateException, IOException {
        mDelegate.setDataSource(context, intentUri);
    }

    @Override
    public synchronized void prepare() throws IOException {
        mDelegate.prepare();
    }

    @Override
    public synchronized int getDuration() {
        return mDelegate.getDuration();
    }

    @Override
    public synchronized void seekTo(int startPosition) {
        mDelegate.seekTo(startPosition);
    }

    @Override
    public synchronized void start() {
        mDelegate.start();
    }

    @Override
    public synchronized boolean isReadyToPlay() {
        return mDelegate.isReadyToPlay();
    }

    @Override
    public synchronized boolean isPlaying() {
        return mDelegate.isPlaying();
    }

    @Override
    public synchronized int getCurrentPosition() {
        return mDelegate.getCurrentPosition();
    }

    public void setVariableSpeed(float rate) {
        ((VariableSpeed) mDelegate).setVariableSpeed(rate);
    }

    @Override
    public synchronized void pause() {
        mDelegate.pause();
    }

    @Override
    public void setAudioStreamType(int streamType) {
        mDelegate.setAudioStreamType(streamType);
    }
}

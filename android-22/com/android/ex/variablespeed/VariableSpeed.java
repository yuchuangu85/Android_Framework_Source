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

import com.google.common.base.Preconditions;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * This class behaves in a similar fashion to the MediaPlayer, but by using
 * native code it is able to use variable-speed playback.
 * <p>
 * This class is thread-safe. It's not yet perfect though, see the unit tests
 * for details - there is insufficient testing for the concurrent logic. You are
 * probably best advised to use thread confinment until the unit tests are more
 * complete with regards to threading.
 * <p>
 * The easiest way to ensure that calls to this class are not made concurrently
 * (besides only ever accessing it from one thread) is to wrap it in a
 * {@link SingleThreadedMediaPlayerProxy}, designed just for this purpose.
 */
@ThreadSafe
public class VariableSpeed implements MediaPlayerProxy {
    private static final String TAG = "VariableSpeed";

    private final Executor mExecutor;
    private final Object lock = new Object();
    @GuardedBy("lock") private MediaPlayerDataSource mDataSource;
    @GuardedBy("lock") private boolean mIsPrepared;
    @GuardedBy("lock") private boolean mHasDuration;
    @GuardedBy("lock") private boolean mHasStartedPlayback;
    @GuardedBy("lock") private CountDownLatch mEngineInitializedLatch;
    @GuardedBy("lock") private CountDownLatch mPlaybackFinishedLatch;
    @GuardedBy("lock") private boolean mHasBeenReleased = true;
    @GuardedBy("lock") private boolean mIsReadyToReUse = true;
    @GuardedBy("lock") private boolean mSkipCompletionReport;
    @GuardedBy("lock") private int mStartPosition;
    @GuardedBy("lock") private float mCurrentPlaybackRate = 1.0f;
    @GuardedBy("lock") private int mDuration;
    @GuardedBy("lock") private MediaPlayer.OnCompletionListener mCompletionListener;
    @GuardedBy("lock") private int mAudioStreamType;

    private VariableSpeed(Executor executor) throws UnsupportedOperationException {
        Preconditions.checkNotNull(executor);
        mExecutor = executor;
        try {
            VariableSpeedNative.loadLibrary();
        } catch (UnsatisfiedLinkError e) {
            throw new UnsupportedOperationException("could not load library", e);
        } catch (SecurityException e) {
            throw new UnsupportedOperationException("could not load library", e);
        }
        reset();
    }

    public static MediaPlayerProxy createVariableSpeed(Executor executor)
            throws UnsupportedOperationException {
        return new SingleThreadedMediaPlayerProxy(new VariableSpeed(executor));
    }

    @Override
    public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
            mCompletionListener = listener;
        }
    }

    @Override
    public void setOnErrorListener(MediaPlayer.OnErrorListener listener) {
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
            // TODO: I haven't actually added any error listener code.
        }
    }

    @Override
    public void release() {
        synchronized (lock) {
            if (mHasBeenReleased) {
                return;
            }
            mHasBeenReleased = true;
        }
        stopCurrentPlayback();
        boolean requiresShutdown = false;
        synchronized (lock) {
            requiresShutdown = hasEngineBeenInitialized();
        }
        if (requiresShutdown) {
            VariableSpeedNative.shutdownEngine();
        }
        synchronized (lock) {
            mIsReadyToReUse = true;
        }
    }

    private boolean hasEngineBeenInitialized() {
        return mEngineInitializedLatch.getCount() <= 0;
    }

    private boolean hasPlaybackFinished() {
        return mPlaybackFinishedLatch.getCount() <= 0;
    }

    /**
     * Stops the current playback, returns once it has stopped.
     */
    private void stopCurrentPlayback() {
        boolean isPlaying;
        CountDownLatch engineInitializedLatch;
        CountDownLatch playbackFinishedLatch;
        synchronized (lock) {
            isPlaying = mHasStartedPlayback && !hasPlaybackFinished();
            engineInitializedLatch = mEngineInitializedLatch;
            playbackFinishedLatch = mPlaybackFinishedLatch;
            if (isPlaying) {
                mSkipCompletionReport = true;
            }
        }
        if (isPlaying) {
            waitForLatch(engineInitializedLatch);
            VariableSpeedNative.stopPlayback();
            waitForLatch(playbackFinishedLatch);
        }
    }

    private void waitForLatch(CountDownLatch latch) {
        try {
            boolean success = latch.await(1, TimeUnit.SECONDS);
            if (!success) {
                reportException(new TimeoutException("waited too long"));
            }
        } catch (InterruptedException e) {
            // Preserve the interrupt status, though this is unexpected.
            Thread.currentThread().interrupt();
            reportException(e);
        }
    }

    @Override
    public void setDataSource(Context context, Uri intentUri) {
        checkNotNull(context, "context");
        checkNotNull(intentUri, "intentUri");
        innerSetDataSource(new MediaPlayerDataSource(context, intentUri));
    }

    @Override
    public void setDataSource(String path) {
        checkNotNull(path, "path");
        innerSetDataSource(new MediaPlayerDataSource(path));
    }

    private void innerSetDataSource(MediaPlayerDataSource source) {
        checkNotNull(source, "source");
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
            check(mDataSource == null, "cannot setDataSource more than once");
            mDataSource = source;
        }
    }

    @Override
    public void reset() {
        boolean requiresRelease;
        synchronized (lock) {
            requiresRelease = !mHasBeenReleased;
        }
        if (requiresRelease) {
            release();
        }
        synchronized (lock) {
            check(mHasBeenReleased && mIsReadyToReUse, "to re-use, must call reset after release");
            mDataSource = null;
            mIsPrepared = false;
            mHasDuration = false;
            mHasStartedPlayback = false;
            mEngineInitializedLatch = new CountDownLatch(1);
            mPlaybackFinishedLatch = new CountDownLatch(1);
            mHasBeenReleased = false;
            mIsReadyToReUse = false;
            mSkipCompletionReport = false;
            mStartPosition = 0;
            mDuration = 0;
        }
    }

    @Override
    public void prepare() throws IOException {
        MediaPlayerDataSource dataSource;
        int audioStreamType;
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
            check(mDataSource != null, "must setDataSource before you prepare");
            check(!mIsPrepared, "cannot prepare more than once");
            mIsPrepared = true;
            dataSource = mDataSource;
            audioStreamType = mAudioStreamType;
        }
        // NYI This should become another executable that we can wait on.
        MediaPlayer mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(audioStreamType);
        dataSource.setAsSourceFor(mediaPlayer);
        mediaPlayer.prepare();
        synchronized (lock) {
            check(!mHasDuration, "can't have duration, this is impossible");
            mHasDuration = true;
            mDuration = mediaPlayer.getDuration();
        }
        mediaPlayer.release();
    }

    @Override
    public int getDuration() {
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
            check(mHasDuration, "you haven't called prepare, can't get the duration");
            return mDuration;
        }
    }

    @Override
    public void seekTo(int startPosition) {
        boolean currentlyPlaying;
        MediaPlayerDataSource dataSource;
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
            check(mHasDuration, "you can't seek until you have prepared");
            currentlyPlaying = mHasStartedPlayback && !hasPlaybackFinished();
            mStartPosition = Math.min(startPosition, mDuration);
            dataSource = mDataSource;
        }
        if (currentlyPlaying) {
            stopAndStartPlayingAgain(dataSource);
        }
    }

    private void stopAndStartPlayingAgain(MediaPlayerDataSource source) {
        stopCurrentPlayback();
        reset();
        innerSetDataSource(source);
        try {
            prepare();
        } catch (IOException e) {
            reportException(e);
            return;
        }
        start();
        return;
    }

    private void reportException(Exception e) {
        Log.e(TAG, "playback error:", e);
    }

    @Override
    public void start() {
        MediaPlayerDataSource restartWithThisDataSource = null;
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
            check(mIsPrepared, "must have prepared before you can start");
            if (!mHasStartedPlayback) {
                // Playback has not started. Start it.
                mHasStartedPlayback = true;
                EngineParameters engineParameters = new EngineParameters.Builder()
                        .initialRate(mCurrentPlaybackRate)
                        .startPositionMillis(mStartPosition)
                        .audioStreamType(mAudioStreamType)
                        .build();
                VariableSpeedNative.initializeEngine(engineParameters);
                VariableSpeedNative.startPlayback();
                mEngineInitializedLatch.countDown();
                mExecutor.execute(new PlaybackRunnable(mDataSource));
            } else {
                // Playback has already started. Restart it, without holding the
                // lock.
                restartWithThisDataSource = mDataSource;
            }
        }
        if (restartWithThisDataSource != null) {
            stopAndStartPlayingAgain(restartWithThisDataSource);
        }
    }

    /** A Runnable capable of driving the native audio playback methods. */
    private final class PlaybackRunnable implements Runnable {
        private final MediaPlayerDataSource mInnerSource;

        public PlaybackRunnable(MediaPlayerDataSource source) {
            mInnerSource = source;
        }

        @Override
        public void run() {
            try {
                mInnerSource.playNative();
            } catch (IOException e) {
                Log.e(TAG, "error playing audio", e);
            }
            MediaPlayer.OnCompletionListener completionListener;
            boolean skipThisCompletionReport;
            synchronized (lock) {
                completionListener = mCompletionListener;
                skipThisCompletionReport = mSkipCompletionReport;
                mPlaybackFinishedLatch.countDown();
            }
            if (!skipThisCompletionReport && completionListener != null) {
                completionListener.onCompletion(null);
            }
        }
    }

    @Override
    public boolean isReadyToPlay() {
        synchronized (lock) {
            return !mHasBeenReleased && mHasDuration;
        }
    }

    @Override
    public boolean isPlaying() {
        synchronized (lock) {
            return isReadyToPlay() && mHasStartedPlayback && !hasPlaybackFinished();
        }
    }

    @Override
    public int getCurrentPosition() {
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
            if (!mHasStartedPlayback) {
                return 0;
            }
            if (!hasEngineBeenInitialized()) {
                return 0;
            }
            if (!hasPlaybackFinished()) {
                return VariableSpeedNative.getCurrentPosition();
            }
            return mDuration;
        }
    }

    @Override
    public void pause() {
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
        }
        stopCurrentPlayback();
    }

    public void setVariableSpeed(float rate) {
        // TODO: are there situations in which the engine has been destroyed, so
        // that this will segfault?
        synchronized (lock) {
            check(!mHasBeenReleased, "has been released, reset before use");
            // TODO: This too is wrong, once we've started preparing the variable speed set
            // will not be enough.
            if (mHasStartedPlayback) {
                VariableSpeedNative.setVariableSpeed(rate);
            }
            mCurrentPlaybackRate = rate;
        }
    }

    private void check(boolean condition, String exception) {
        if (!condition) {
            throw new IllegalStateException(exception);
        }
    }

    private void checkNotNull(Object argument, String argumentName) {
        if (argument == null) {
            throw new IllegalArgumentException(argumentName + " must not be null");
        }
    }

    @Override
    public void setAudioStreamType(int audioStreamType) {
        synchronized (lock) {
            mAudioStreamType = audioStreamType;
        }
    }
}

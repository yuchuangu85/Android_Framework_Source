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

import com.google.common.io.Closeables;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.res.AssetManager;
import android.net.Uri;
import android.provider.VoicemailContract;
import android.test.InstrumentationTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Base test for checking implementations of {@link MediaPlayerProxy}.
 * <p>
 * The purpose behind this class is to collect tests that implementations of
 * MediaPlayerProxy should support.
 * <p>
 * This allows tests to show that the built-in {@link android.media.MediaPlayer} is performing
 * correctly with respect to the contract it provides, i.e. test my understanding of that contract.
 * <p>
 * It allows us to test the current {@link VariableSpeed} implementation, and make sure that this
 * too corresponds with the MediaPlayer implementation.
 * <p>
 * These tests cannot be run on their own - you must provide a concrete subclass of this test case -
 * and in that subclass you will provide an implementation of the abstract
 * {@link #createTestMediaPlayer()} method to construct the player you would like to test. Every
 * test will construct the player in {@link #setUp()} and release it in {@link #tearDown()}.
 */
public abstract class MediaPlayerProxyTestCase extends InstrumentationTestCase {
    private static final float ERROR_TOLERANCE_MILLIS = 1000f;

    /** The phone number to use when inserting test data into the content provider. */
    private static final String CONTACT_NUMBER = "01234567890";

    /**
     * A map from filename + mime type to the uri we can use to play from the content provider.
     * <p>
     * This is lazily filled in by the {@link #getTestContentUri(String, String)} method.
     * <p>
     * This map is keyed from the concatenation of filename and mime type with a "+" separator, it's
     * not perfect but it doesn't matter in this test code.
     */
    private final Map<String, Uri> mContentUriMap = new HashMap<String, Uri>();

    /** The system under test. */
    private MediaPlayerProxy mPlayer;

    private AwaitableCompletionListener mCompletionListener;
    private AwaitableErrorListener mErrorListener;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mPlayer = createTestMediaPlayer();
        mCompletionListener = new AwaitableCompletionListener();
        mErrorListener = new AwaitableErrorListener();
    }

    @Override
    protected void tearDown() throws Exception {
        mCompletionListener = null;
        mErrorListener = null;
        mPlayer.release();
        mPlayer = null;
        cleanupContentUriIfNecessary();
        super.tearDown();
    }

    public abstract MediaPlayerProxy createTestMediaPlayer() throws Exception;

    /** Annotation to indicate that test should throw an {@link IllegalStateException}. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ShouldThrowIllegalStateException {
    }

    @Override
    protected void runTest() throws Throwable {
        // Tests annotated with ShouldThrowIllegalStateException will fail if they don't.
        // Tests not annotated this way are run as normal.
        if (getClass().getMethod(getName()).isAnnotationPresent(
                ShouldThrowIllegalStateException.class)) {
            try {
                super.runTest();
                fail("Expected this method to throw an IllegalStateException, but it didn't");
            } catch (IllegalStateException e) {
                // Expected.
            }
        } else {
            super.runTest();
        }
    }

    public void testReleaseMultipleTimesHasNoEffect() throws Exception {
        mPlayer.release();
        mPlayer.release();
    }

    public void testResetOnNewlyCreatedObject() throws Exception {
        mPlayer.reset();
    }

    public void testSetDataSource() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
    }

    @ShouldThrowIllegalStateException
    public void testSetDataSourceTwice_ShouldFailWithIllegalState() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
    }

    @ShouldThrowIllegalStateException
    public void testSetDataSourceAfterRelease_ShouldFailWithIllegalState() throws Exception {
        mPlayer.release();
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
    }

    public void testPrepare() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
    }

    @ShouldThrowIllegalStateException
    public void testPrepareBeforeSetDataSource_ShouldFail() throws Exception {
        mPlayer.prepare();
    }

    @ShouldThrowIllegalStateException
    public void testPrepareTwice_ShouldFailWithIllegalState() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.prepare();
    }

    public void testStartThenImmediatelyRelease() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.start();
    }

    public void testPlayABitThenRelease() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.start();
        Thread.sleep(2000);
    }

    public void testPlayFully() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
    }

    public void testGetDuration() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        int duration = mPlayer.getDuration();
        assertTrue("duration was " + duration, duration > 0);
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        assertEquals(duration, mPlayer.getDuration());
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        assertEquals(duration, mPlayer.getDuration());
    }

    @ShouldThrowIllegalStateException
    public void testGetDurationAfterRelease_ShouldFail() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.release();
        mPlayer.getDuration();
    }

    @ShouldThrowIllegalStateException
    public void testGetPositionAfterRelease_ShouldFail() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.release();
        mPlayer.getCurrentPosition();
    }

    public void testGetCurrentPosition_ZeroBeforePlaybackBegins() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        assertEquals(0, mPlayer.getCurrentPosition());
        mPlayer.prepare();
        assertEquals(0, mPlayer.getCurrentPosition());
    }

    public void testGetCurrentPosition_DuringPlayback() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.start();
        Thread.sleep(2000);
        assertEquals(2000, mPlayer.getCurrentPosition(), ERROR_TOLERANCE_MILLIS);
    }

    public void testGetCurrentPosition_FinishedPlaying() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        assertEquals(mPlayer.getDuration(), mPlayer.getCurrentPosition(), ERROR_TOLERANCE_MILLIS);
    }

    public void testGetCurrentPosition_DuringPlaybackWithSeek() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.seekTo(1500);
        mPlayer.start();
        Thread.sleep(1500);
        assertEquals(3000, mPlayer.getCurrentPosition(), ERROR_TOLERANCE_MILLIS);
    }

    public void testSeekHalfWayBeforePlaying() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        assertTrue(mPlayer.getDuration() > 0);
        mPlayer.seekTo(mPlayer.getDuration() / 2);
        mPlayer.start();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
    }

    public void testHalfWaySeekWithStutteringAudio() throws Exception {
        // The audio contained in this file has a stutter if we seek to half way and play.
        // It shouldn't have.
        setDataSourceFromContentProvider(mPlayer, "fake_voicemail2.mp3", "audio/mp3");
        mPlayer.prepare();
        assertTrue(mPlayer.getDuration() > 0);
        mPlayer.seekTo(mPlayer.getDuration() / 2);
        mPlayer.start();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
    }

    public void testResetWithoutReleaseAndThenReUse() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.reset();
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.seekTo(mPlayer.getDuration() / 2);
        mPlayer.start();
        Thread.sleep(1000);
    }

    public void testResetAfterPlaybackThenReUse() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.prepare();
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        mPlayer.reset();
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.start();
        Thread.sleep(2000);
    }

    public void testResetDuringPlaybackThenReUse() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.start();
        Thread.sleep(2000);
        mPlayer.reset();
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.start();
        Thread.sleep(2000);
    }

    public void testFinishPlayingThenSeekToHalfWayThenPlayAgain() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        mPlayer.seekTo(mPlayer.getDuration() / 2);
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
    }

    public void testPause_DuringPlayback() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.start();
        assertTrue(mPlayer.isPlaying());
        Thread.sleep(2000);
        assertTrue(mPlayer.isPlaying());
        mPlayer.pause();
        assertFalse(mPlayer.isPlaying());
    }

    public void testPause_DoesNotInvokeCallback() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mPlayer.pause();
        Thread.sleep(200);
        mCompletionListener.assertNoMoreCallbacks();
    }

    public void testReset_DoesNotInvokeCallback() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mPlayer.reset();
        Thread.sleep(200);
        mCompletionListener.assertNoMoreCallbacks();
    }

    public void testPause_MultipleTimes() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.start();
        Thread.sleep(2000);
        mPlayer.pause();
        mPlayer.pause();
    }

    public void testDoubleStartWaitingForFinish() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
    }

    public void testTwoFastConsecutiveStarts() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        Thread.sleep(200);
        mCompletionListener.assertNoMoreCallbacks();
    }

    public void testThreeFastConsecutiveStarts() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mPlayer.start();
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        Thread.sleep(4000);
        mCompletionListener.assertNoMoreCallbacks();
    }

    public void testSeekDuringPlayback() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        Thread.sleep(2000);
        mPlayer.seekTo(0);
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        Thread.sleep(200);
        mCompletionListener.assertNoMoreCallbacks();
    }

    public void testPlaySingleChannelLowSampleRate3gppFile() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "count_and_test.3gpp", "audio/3gpp");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
    }

    public void testPlayTwoDifferentTypesWithSameMediaPlayer() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        mPlayer.reset();
        setDataSourceFromContentProvider(mPlayer, "count_and_test.3gpp", "audio/3gpp");
        mPlayer.prepare();
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
    }

    public void testIllegalPreparingDoesntFireErrorListener() throws Exception {
        mPlayer.setOnErrorListener(mErrorListener);
        try {
            mPlayer.prepare();
            fail("This should have thrown an IllegalStateException");
        } catch (IllegalStateException e) {
            // Good, expected.
        }
        mErrorListener.assertNoMoreCallbacks();
    }

    public void testSetDataSourceForMissingFile_ThrowsIOExceptionInPrepare() throws Exception {
        mPlayer.setOnErrorListener(mErrorListener);
        mPlayer.setDataSource("/this/file/does/not/exist/");
        try {
            mPlayer.prepare();
            fail("Should have thrown IOException");
        } catch (IOException e) {
            // Good, expected.
        }
        // Synchronous prepare does not report errors to the error listener.
        mErrorListener.assertNoMoreCallbacks();
    }

    public void testRepeatedlySeekingDuringPlayback() throws Exception {
        // Start playback then seek repeatedly during playback to the same point.
        // The real media player should play a stuttering audio, hopefully my player does too.
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        Thread.sleep(500);
        for (int i = 0; i < 40; ++i) {
            Thread.sleep(200);
            mPlayer.seekTo(2000);
        }
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
    }

    public void testRepeatedlySeekingDuringPlaybackRandomAndVeryFast() throws Exception {
        setDataSourceFromContentProvider(mPlayer, "quick_test_recording.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        Thread.sleep(500);
        for (int i = 0; i < 40; ++i) {
            Thread.sleep(250);
            mPlayer.seekTo(1500 + (int) (Math.random() * 1000));
        }
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
    }

    public void testSeekToEndThenPlayThenRateChangeCrash() throws Exception {
        // Unit test for this bug: http://b/5140693
        // This test proves that the bug is fixed.
        setDataSourceFromContentProvider(mPlayer, "fake_voicemail.mp3", "audio/mp3");
        mPlayer.prepare();
        mPlayer.seekTo(mPlayer.getDuration() - 1);
        mPlayer.setOnCompletionListener(mCompletionListener);
        mPlayer.start();
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        // Prior to the fix, this next line was causing a crash.
        // The reason behind this was due to our having seeked so close to the end of the file
        // that insufficient data was being read, and thus we weren't able to yet determine the
        // sample rate and number of channels, which was causing an assertion failure when trying
        // to create the time scaler.
        setVariableSpeedRateIfSupported(1.0f);
    }

    public void testVariableSpeedRateChangeAtDifferentTimes() throws Exception {
        // Just check that we can set the rate at any point during playback.
        setVariableSpeedRateIfSupported(1.05f);
        setDataSourceFromContentProvider(mPlayer, "fake_voicemail.mp3", "audio/mp3");
        setVariableSpeedRateIfSupported(1.10f);
        mPlayer.prepare();
        setVariableSpeedRateIfSupported(1.15f);
        mPlayer.seekTo(mPlayer.getDuration() / 2);
        setVariableSpeedRateIfSupported(1.20f);
        mPlayer.setOnCompletionListener(mCompletionListener);
        setVariableSpeedRateIfSupported(1.25f);
        mPlayer.start();
        setVariableSpeedRateIfSupported(1.30f);
        mCompletionListener.awaitOneCallback(10, TimeUnit.SECONDS);
        setVariableSpeedRateIfSupported(1.35f);
    }

    /**
     * If we have a variable speed media player proxy, set the variable speed rate.
     * <p>
     * If we don't have a variable speed media player proxy, this method will be a no-op.
     */
    private void setVariableSpeedRateIfSupported(float rate) {
        if (mPlayer instanceof SingleThreadedMediaPlayerProxy) {
            ((SingleThreadedMediaPlayerProxy) mPlayer).setVariableSpeed(rate);
        } else if (mPlayer instanceof VariableSpeed) {
            ((VariableSpeed) mPlayer).setVariableSpeed(rate);
        }
    }

    /**
     * Gets the {@link Uri} for the test audio content we should play.
     * <p>
     * If this is the first time we've called this method, for a given file type and mime type, then
     * we'll have to insert some data into the content provider so that we can play it.
     * <p>
     * This is not thread safe, but doesn't need to be because all unit tests are executed from a
     * single thread, sequentially.
     */
    private Uri getTestContentUri(String assetFilename, String assetMimeType) throws IOException {
        String key = keyFor(assetFilename, assetMimeType);
        if (mContentUriMap.containsKey(key)) {
            return mContentUriMap.get(key);
        }
        ContentValues values = new ContentValues();
        values.put(VoicemailContract.Voicemails.DATE, String.valueOf(System.currentTimeMillis()));
        values.put(VoicemailContract.Voicemails.NUMBER, CONTACT_NUMBER);
        values.put(VoicemailContract.Voicemails.MIME_TYPE, assetMimeType);
        String packageName = getInstrumentation().getTargetContext().getPackageName();
        Uri uri = getContentResolver().insert(
                VoicemailContract.Voicemails.buildSourceUri(packageName), values);
        AssetManager assets = getAssets();
        OutputStream outputStream = null;
        InputStream inputStream = null;
        try {
            inputStream = assets.open(assetFilename);
            outputStream = getContentResolver().openOutputStream(uri);
            copyBetweenStreams(inputStream, outputStream);
            mContentUriMap.put(key, uri);
            return uri;
        } finally {
            Closeables.closeQuietly(outputStream);
            Closeables.closeQuietly(inputStream);
        }
    }

    private String keyFor(String assetFilename, String assetMimeType) {
        return assetFilename + "+" + assetMimeType;
    }

    public void copyBetweenStreams(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }

    private void cleanupContentUriIfNecessary() {
        for (Uri uri : mContentUriMap.values()) {
            getContentResolver().delete(uri, null, null);
        }
        mContentUriMap.clear();
    }

    private void setDataSourceFromContentProvider(MediaPlayerProxy player, String assetFilename,
            String assetMimeType) throws IOException {
        player.setDataSource(getInstrumentation().getTargetContext(),
                getTestContentUri(assetFilename, assetMimeType));
    }

    private ContentResolver getContentResolver() {
        return getInstrumentation().getContext().getContentResolver();
    }

    private AssetManager getAssets() {
        return getInstrumentation().getContext().getAssets();
    }
}

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

import android.media.AudioManager;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Encapsulates the parameters required to configure the audio engine.
 * <p>
 * You should not need to use this class directly, it exists for the benefit of
 * this package and the classes contained therein.
 */
@Immutable
/*package*/ final class EngineParameters {
    private final int mTargetFrames;
    private final int mMaxPlayBufferCount;
    private final float mWindowDuration;
    private final float mWindowOverlapDuration;
    private final float mInitialRate;
    private final int mDecodeBufferInitialSize;
    private final int mDecodeBufferMaxSize;
    private final int mStartPositionMillis;
    private final int mAudioStreamType;

    public int getTargetFrames() {
        return mTargetFrames;
    }

    public int getMaxPlayBufferCount() {
        return mMaxPlayBufferCount;
    }

    public float getWindowDuration() {
        return mWindowDuration;
    }

    public float getWindowOverlapDuration() {
        return mWindowOverlapDuration;
    }

    public float getInitialRate() {
        return mInitialRate;
    }

    public int getDecodeBufferInitialSize() {
        return mDecodeBufferInitialSize;
    }

    public int getDecodeBufferMaxSize() {
        return mDecodeBufferMaxSize;
    }

    public int getStartPositionMillis() {
        return mStartPositionMillis;
    }

    public int getAudioStreamType() {
        return mAudioStreamType;
    }

    private EngineParameters(int targetFrames, int maxPlayBufferCount, float windowDuration,
            float windowOverlapDuration, float initialRate, int decodeBufferInitialSize,
            int decodeBufferMaxSize, int startPositionMillis, int audioStreamType) {
        mTargetFrames = targetFrames;
        mMaxPlayBufferCount = maxPlayBufferCount;
        mWindowDuration = windowDuration;
        mWindowOverlapDuration = windowOverlapDuration;
        mInitialRate = initialRate;
        mDecodeBufferInitialSize = decodeBufferInitialSize;
        mDecodeBufferMaxSize = decodeBufferMaxSize;
        mStartPositionMillis = startPositionMillis;
        mAudioStreamType = audioStreamType;
    }

    /**
     * We use the builder pattern to construct an {@link EngineParameters}
     * object.
     * <p>
     * This class is not thread safe, you should confine its use to one thread
     * or provide your own synchronization.
     */
    @NotThreadSafe
    public static class Builder {
        private int mTargetFrames = 1000;
        private int mMaxPlayBufferCount = 2;
        private float mWindowDuration = 0.08f;
        private float mWindowOverlapDuration = 0.008f;
        private float mInitialRate = 1.0f;
        private int mDecodeBufferInitialSize = 5 * 1024;
        private int mDecodeBufferMaxSize = 20 * 1024;
        private int mStartPositionMillis = 0;
        private int mAudioStreamType = AudioManager.STREAM_MUSIC;

        public EngineParameters build() {
            return new EngineParameters(mTargetFrames, mMaxPlayBufferCount,
                    mWindowDuration, mWindowOverlapDuration, mInitialRate,
                    mDecodeBufferInitialSize, mDecodeBufferMaxSize, mStartPositionMillis,
                    mAudioStreamType);
        }

        public Builder maxPlayBufferCount(int maxPlayBufferCount) {
            mMaxPlayBufferCount = maxPlayBufferCount;
            return this;
        }

        public Builder windowDuration(int windowDuration) {
            mWindowDuration = windowDuration;
            return this;
        }

        public Builder windowOverlapDuration(int windowOverlapDuration) {
            mWindowOverlapDuration = windowOverlapDuration;
            return this;
        }

        public Builder initialRate(float initialRate) {
            mInitialRate = initialRate;
            return this;
        }

        public Builder decodeBufferInitialSize(int decodeBufferInitialSize) {
            mDecodeBufferInitialSize = decodeBufferInitialSize;
            return this;
        }

        public Builder decodeBufferMaxSize(int decodeBufferMaxSize) {
            mDecodeBufferMaxSize = decodeBufferMaxSize;
            return this;
        }

        public Builder startPositionMillis(int startPositionMillis) {
            mStartPositionMillis = startPositionMillis;
            return this;
        }

        public Builder audioStreamType(int audioStreamType) {
            mAudioStreamType = audioStreamType;
            return this;
        }
    }
}

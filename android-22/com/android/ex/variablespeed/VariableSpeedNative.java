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

import com.android.common.io.MoreCloseables;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;

/**
 * Provides all the native calls through to the underlying audio library.
 * <p>
 * You should not use this class directly. Prefer to use the {@link VariableSpeed}
 * class instead.
 */
/*package*/ class VariableSpeedNative {
    /*package*/ static void loadLibrary() throws UnsatisfiedLinkError, SecurityException {
        System.loadLibrary("variablespeed");
    }

    /*package*/ static boolean playFromContext(Context context, Uri uri)
            throws FileNotFoundException {
        AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
        try {
            FileDescriptor fileDescriptor = afd.getFileDescriptor();
            Field descriptorField = fileDescriptor.getClass().getDeclaredField("descriptor");
            descriptorField.setAccessible(true);
            int fd = descriptorField.getInt(fileDescriptor);
            VariableSpeedNative.playFileDescriptor(fd, afd.getStartOffset(), afd.getLength());
            return true;
        } catch (SecurityException e) {
            // Fall through.
        } catch (NoSuchFieldException e) {
            // Fall through.
        } catch (IllegalArgumentException e) {
            // Fall through.
        } catch (IllegalAccessException e) {
            // Fall through.
        } finally {
            MoreCloseables.closeQuietly(afd);
        }
        return false;
    }

    /*package*/ static native void playUri(String uri);

    /*package*/ static native void playFileDescriptor(int fd, long offset, long length);

    /*package*/ static native void setVariableSpeed(float speed);

    /*package*/ static native void startPlayback();

    /*package*/ static native void stopPlayback();

    /*package*/ static native void shutdownEngine();

    /*package*/ static native int getCurrentPosition();

    /*package*/ static native int getTotalDuration();

    /*package*/ static void initializeEngine(EngineParameters params) {
        initializeEngine(params.getTargetFrames(),
                params.getWindowDuration(), params.getWindowOverlapDuration(),
                params.getMaxPlayBufferCount(), params.getInitialRate(),
                params.getDecodeBufferInitialSize(), params.getDecodeBufferMaxSize(),
                params.getStartPositionMillis(), params.getAudioStreamType());
    }

    private static native void initializeEngine(int targetFrames,
            float windowDuration, float windowOverlapDuration, int maxPlayBufferCount,
            float initialRate, int decodeBufferInitialSize, int decodeBufferMaxSize,
            int startPositionMillis, int audioStreamType);
}

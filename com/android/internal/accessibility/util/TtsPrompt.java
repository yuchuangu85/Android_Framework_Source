/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.internal.accessibility.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Slog;

import com.android.internal.util.function.pooled.PooledLambda;

import java.util.Locale;

/** Class to wrap TextToSpeech for shortcut dialog spoken feedback. */
public class TtsPrompt implements TextToSpeech.OnInitListener {
    private static final String TAG = "TtsPrompt";

    private static final int RETRY_MILLIS = 1000;

    private final Context mContext;
    private final Handler mHandler;
    private final FrameworkObjectProvider mFrameworkObjectProvider;
    // The text will be announced.
    private final CharSequence mText;

    private int mRetryCount = 3;
    private boolean mDismiss;
    private boolean mLanguageReady = false;
    private TextToSpeech mTts;

    public TtsPrompt(
            Context context, Handler handler, FrameworkObjectProvider provider, CharSequence text) {
        mContext = context;
        mHandler = handler;
        mFrameworkObjectProvider = provider;
        mText = text;
        mTts = mFrameworkObjectProvider.getTextToSpeech(mContext, this);
    }

    /** Releases the resources used by the TextToSpeech, when dialog dismiss. */
    public void dismiss() {
        mDismiss = true;
        mHandler.sendMessage(PooledLambda.obtainMessage(TextToSpeech::shutdown, mTts));
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Slog.d(TAG, "Tts init fail, status=" + Integer.toString(status));
            playNotificationTone();
            return;
        }
        initTextToSpeech();
        mHandler.sendMessage(PooledLambda.obtainMessage(TtsPrompt::waitForTtsReady, this));
    }

    private void initTextToSpeech() {
        if (mDismiss) return;

        // USAGE_ASSISTANCE_ACCESSIBILITY required to use accessibility audio stream
        AudioAttributes audioAttributes =
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .build();
        mTts.setAudioAttributes(audioAttributes);
    }

    private void play() {
        if (mDismiss) {
            return;
        }
        final int status = mTts.speak(mText, TextToSpeech.QUEUE_FLUSH, null, null);
        if (status != TextToSpeech.SUCCESS) {
            Slog.d(TAG, "Tts play fail");
            playNotificationTone();
        }
    }

    /**
     * Waiting for tts is ready to speak. Trying again if tts language pack is not available or tts
     * voice data is not installed yet.
     */
    private void waitForTtsReady() {
        if (mDismiss) {
            return;
        }
        if (!mLanguageReady) {
            final int status = mTts.setLanguage(Locale.getDefault());
            // True if language is available and TTS#loadVoice has called once
            // that trigger TTS service to start initialization.
            mLanguageReady =
                    status != TextToSpeech.LANG_MISSING_DATA
                            && status != TextToSpeech.LANG_NOT_SUPPORTED;
        }
        if (mLanguageReady) {
            final Voice voice = mTts.getVoice();
            final boolean voiceDataInstalled =
                    voice != null
                            && voice.getFeatures() != null
                            && !voice.getFeatures()
                                    .contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
            if (voiceDataInstalled) {
                mHandler.sendMessage(PooledLambda.obtainMessage(TtsPrompt::play, this));
                return;
            }
        }

        if (mRetryCount == 0) {
            Slog.d(TAG, "Tts not ready to speak.");
            playNotificationTone();
            return;
        }
        // Retry if TTS service not ready yet.
        mRetryCount -= 1;
        mHandler.sendMessageDelayed(
                PooledLambda.obtainMessage(TtsPrompt::waitForTtsReady, this), RETRY_MILLIS);
    }

    private void playNotificationTone() {
        Ringtone tone =
                mFrameworkObjectProvider.getDefaultAccessibilityNotificationRingtone(mContext);
        AccessibilityUtils.playNotificationTone(mContext, tone);
    }
}

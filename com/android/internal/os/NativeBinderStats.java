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

package com.android.internal.os;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Coordinates native binder stats collection. Propagates settings to the processes that use
 * libbinder for tracking stats.
 */
public class NativeBinderStats {
    private static final String TAG = "NativeBinderStats";

    static final boolean DEFAULT_ENABLED = false;
    static final int DEFAULT_PROCESS_SHARDING = 50;
    static final int DEFAULT_SPAM_SHARDING = 10;
    static final int DEFAULT_CALL_SHARDING = 20;
    static final int DEFAULT_SYSTEM_PROCESS_SHARDING = 10;
    static final int DEFAULT_SYSTEM_SPAM_SHARDING = 50;
    static final int DEFAULT_SYSTEM_CALL_SHARDING = 100;

    /** Whether native binder stats are enabled. */
    @VisibleForTesting public boolean mEnabled = DEFAULT_ENABLED;

    /**
     * The inverse probability that a given process will track binder stats. E.g. 100 means that
     * 1% of the processes will report stats. 0 is a special value that means no process will
     * report stats.
     */
    @VisibleForTesting public int mProcessSharding = DEFAULT_PROCESS_SHARDING;

    /**
     * The inverse probability that a given AIDL method will be selected for spam detection and
     * reporting (provided the containing process is selected for stats). 0 means no spam
     * tracking.
     */
    @VisibleForTesting public int mSpamSharding = DEFAULT_SPAM_SHARDING;

    /**
     * The inverse probability that a given AIDL method will be selected for call stats
     * (provided the containing process is selected for stats). 0 means no call stats.
     */
    @VisibleForTesting public int mCallSharding = DEFAULT_CALL_SHARDING;

    /** Like {@link #mProcessSharding} but for system_server. */
    @VisibleForTesting public int mSystemProcessSharding = DEFAULT_SYSTEM_PROCESS_SHARDING;

    /** Like {@link #mSpamSharding} but for system_server. */
    @VisibleForTesting public int mSystemSpamSharding = DEFAULT_SYSTEM_SPAM_SHARDING;

    /** Like {@link #mCallSharding} but for system_server. */
    @VisibleForTesting public int mSystemCallSharding = DEFAULT_SYSTEM_CALL_SHARDING;

    private final Context mContext;

    private final SettingsObserver mSettingsObserver;

    public NativeBinderStats(Context context) {
        mContext = context;
        mSettingsObserver = new SettingsObserver(context);
    }

    public void systemReady() {
        // Start observing settings.
        mSettingsObserver.register();
    }

    @VisibleForTesting
    public class SettingsObserver extends ContentObserver {
        private static final String KEY_ENABLED = "enabled";
        private static final String KEY_PROCESS_SHARDING = "process_sharding";
        private static final String KEY_SPAM_SHARDING = "spam_sharding";
        private static final String KEY_CALL_SHARDING = "call_sharding";
        private static final String KEY_SYSTEM_PROCESS_SHARDING = "system_process_sharding";
        private static final String KEY_SYSTEM_SPAM_SHARDING = "system_spam_sharding";
        private static final String KEY_SYSTEM_CALL_SHARDING = "system_call_sharding";

        private final Uri mUri = Settings.Global.getUriFor(Settings.Global.NATIVE_BINDER_STATS);
        private final KeyValueListParser mParser = new KeyValueListParser(',');
        private final Context mContext;

        SettingsObserver(Context context) {
            super(BackgroundThread.getHandler());
            mContext = context;
        }

        void register() {
            mContext.getContentResolver().registerContentObserver(mUri, false, this);
            // Trigger update so we get the initial state.
            onChange();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (mUri.equals(uri)) {
                onChange();
            }
        }

        void onChange() {
            try {
                mParser.setString(
                        Settings.Global.getString(
                                mContext.getContentResolver(),
                                Settings.Global.NATIVE_BINDER_STATS));
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad native binder stats settings", e);
            }

            mEnabled = mParser.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
            mProcessSharding = mParser.getInt(KEY_PROCESS_SHARDING, DEFAULT_PROCESS_SHARDING);
            mSpamSharding = mParser.getInt(KEY_SPAM_SHARDING, DEFAULT_SPAM_SHARDING);
            mCallSharding = mParser.getInt(KEY_CALL_SHARDING, DEFAULT_CALL_SHARDING);
            mSystemProcessSharding =
                    mParser.getInt(KEY_SYSTEM_PROCESS_SHARDING, DEFAULT_SYSTEM_PROCESS_SHARDING);
            mSystemSpamSharding =
                    mParser.getInt(KEY_SYSTEM_SPAM_SHARDING, DEFAULT_SYSTEM_SPAM_SHARDING);
            mSystemCallSharding =
                    mParser.getInt(KEY_SYSTEM_CALL_SHARDING, DEFAULT_SYSTEM_CALL_SHARDING);
            // TODO(b/407694522): If settings change, propagate to other processes.
            Slog.i(
                    TAG,
                    String.format(
                            "Native binder stats settings changed: %b, %d, %d, %d, %d,  %d, %d",
                            mEnabled,
                            mProcessSharding,
                            mSpamSharding,
                            mCallSharding,
                            mSystemProcessSharding,
                            mSystemSpamSharding,
                            mSystemCallSharding));
        }
    }

    @VisibleForTesting
    public SettingsObserver getSettingsObserverForTesting() {
        return mSettingsObserver;
    }
}

/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.util.Log;

import com.android.internal.annotations.Immutable;
import com.android.internal.annotations.VisibleForTesting;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Provides a WifiLog implementation which uses logd as the
 * logging backend.
 *
 * This class is trivially thread-safe, as instances are immutable.
 * Note, however, that LogMessage instances are _not_ thread-safe.
 */
@ThreadSafe
@Immutable
class LogcatLog implements WifiLog {
    private final String mTag;
    private static volatile boolean sVerboseLogging = false;
    private static final DummyLogMessage sDummyLogMessage = new DummyLogMessage();

    LogcatLog(String tag) {
        mTag = tag;
    }

    public static void enableVerboseLogging(int verboseMode) {
        if (verboseMode > 0) {
            sVerboseLogging = true;
        } else {
            sVerboseLogging = false;
        }
    }

    /* New-style methods */
    @Override
    public LogMessage err(String format) {
        return new RealLogMessage(Log.ERROR, mTag, format);
    }

    @Override
    public LogMessage warn(String format) {
        return new RealLogMessage(Log.WARN, mTag, format);
    }

    @Override
    public LogMessage info(String format) {
        return new RealLogMessage(Log.INFO, mTag, format);
    }

    @Override
    public LogMessage trace(String format) {
        if (sVerboseLogging) {
            return new RealLogMessage(Log.DEBUG, mTag, format,
                    getNameOfCallingMethod(0));
        } else {
            return sDummyLogMessage;
        }
    }

    @Override
    public LogMessage trace(String format, int numFramesToIgnore) {
        if (sVerboseLogging) {
            return new RealLogMessage(Log.DEBUG, mTag, format,
                    getNameOfCallingMethod(numFramesToIgnore));
        } else {
            return sDummyLogMessage;
        }
    }

    @Override
    public LogMessage dump(String format) {
        if (sVerboseLogging) {
            return new RealLogMessage(Log.VERBOSE, mTag, format);
        } else {
            return sDummyLogMessage;
        }
    }

    @Override
    public void eC(String msg) {
        Log.e(mTag, msg);
    }

    @Override
    public void wC(String msg) {
        Log.w(mTag, msg);
    }

    @Override
    public void iC(String msg) {
        Log.i(mTag, msg);
    }

    @Override
    public void tC(String msg) {
        Log.d(mTag, msg);
    }

    /* Legacy methods */
    @Override
    public void e(String msg) {
        Log.e(mTag, msg);
    }

    @Override
    public void w(String msg) {
        Log.w(mTag, msg);
    }

    @Override
    public void i(String msg) {
        Log.i(mTag, msg);
    }

    @Override
    public void d(String msg) {
        Log.d(mTag, msg);
    }

    @Override
    public void v(String msg) {
        Log.v(mTag, msg);
    }

    /* Internal details */
    private static class RealLogMessage implements WifiLog.LogMessage {
        private final int mLogLevel;
        private final String mTag;
        private final String mFormat;
        private final StringBuilder mStringBuilder;
        private int mNextFormatCharPos;

        RealLogMessage(int logLevel, String tag, String format) {
            this(logLevel, tag, format, null);
        }

        RealLogMessage(int logLevel, String tag, String format, String prefix) {
            mLogLevel = logLevel;
            mTag = tag;
            mFormat = format;
            mStringBuilder = new StringBuilder();
            mNextFormatCharPos = 0;
            if (prefix != null) {
                mStringBuilder.append(prefix).append(" ");
            }
        }

        @Override
        public WifiLog.LogMessage r(String value) {
            // Since the logcat back-end is just transitional, we don't attempt to tag sensitive
            // information in it.
            return c(value);
        }

        @Override
        public WifiLog.LogMessage c(String value) {
            copyUntilPlaceholder();
            if (mNextFormatCharPos < mFormat.length()) {
                mStringBuilder.append(value);
                ++mNextFormatCharPos;
            }
            return this;
        }

        @Override
        public WifiLog.LogMessage c(long value) {
            copyUntilPlaceholder();
            if (mNextFormatCharPos < mFormat.length()) {
                mStringBuilder.append(value);
                ++mNextFormatCharPos;
            }
            return this;
        }

        @Override
        public WifiLog.LogMessage c(char value) {
            copyUntilPlaceholder();
            if (mNextFormatCharPos < mFormat.length()) {
                mStringBuilder.append(value);
                ++mNextFormatCharPos;
            }
            return this;
        }

        @Override
        public WifiLog.LogMessage c(boolean value) {
            copyUntilPlaceholder();
            if (mNextFormatCharPos < mFormat.length()) {
                mStringBuilder.append(value);
                ++mNextFormatCharPos;
            }
            return this;
        }

        @Override
        public void flush() {
            if (mNextFormatCharPos < mFormat.length()) {
                mStringBuilder.append(mFormat, mNextFormatCharPos, mFormat.length());
            }
            Log.println(mLogLevel, mTag, mStringBuilder.toString());
        }

        @VisibleForTesting
        public String toString() {
            return mStringBuilder.toString();
        }

        private void copyUntilPlaceholder() {
            if (mNextFormatCharPos >= mFormat.length()) {
                return;
            }

            int placeholderPos = mFormat.indexOf(WifiLog.PLACEHOLDER, mNextFormatCharPos);
            if (placeholderPos == -1) {
                placeholderPos = mFormat.length();
            }

            mStringBuilder.append(mFormat, mNextFormatCharPos, placeholderPos);
            mNextFormatCharPos = placeholderPos;
        }
    }

    private static final String[] TRACE_FRAMES_TO_IGNORE = {
            "getNameOfCallingMethod()", "trace()"
    };
    private String getNameOfCallingMethod(int callerFramesToIgnore) {
        final int frameNumOfInterest = callerFramesToIgnore + TRACE_FRAMES_TO_IGNORE.length;
        // In some environments, it's much faster to get a stack trace from a Throwable
        // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6375302.
        //
        // While Dalvik optimizes the same-thread-stack-trace case,
        // Throwable_nativeGetStackTrace() is still simpler than
        // VMStack_getThreadStackTrace().
        //
        // Some crude benchmarking suggests that the cost of this approach is about
        // 50 usec. go/logcatlog-trace-benchmark
        StackTraceElement[] stackTrace = (new Throwable()).getStackTrace();
        try {
            return stackTrace[frameNumOfInterest].getMethodName();
        } catch (ArrayIndexOutOfBoundsException e) {
            return ("<unknown>");
        }
    }
}

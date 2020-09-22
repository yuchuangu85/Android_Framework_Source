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

import android.annotation.NonNull;

import com.google.errorprone.annotations.CompileTimeConstant;

import javax.annotation.CheckReturnValue;

/**
 * Provides an abstraction of logging back-ends.
 *
 * The abstraction is designed to
 * a) minimize the cost of disabled log messages,
 * b) allow callers to tag message parameters as containing sensitive
 *    information,
 * c) avoid the use of format codes, and
 * d) easily support additional data types.
 *
 * Implementations of WifiLog may or may not be thread-safe.
 * Implementations of LogMessage are expected _not_ to be thread-safe,
 * as LogMessage instances are not expected to be shared between threads.
 */
@SuppressWarnings("NonFinalCompileTimeConstant")  // See below.
public interface WifiLog {
    // Explanation of SuppressWarnings above:
    //
    // We use @CompileTimeConstant to verify that our callers do not stringify
    // arguments into the |format| parameter. And, by default, error-prone
    // requires that CompileTimeConstant parameters are declared final.
    //
    // However, declaring an interface parameter as final has no effect, since
    // classes implementing the interface are free to declare their parameters
    // as non-final. Moreover, to avoid such confusing situations (interface says
    // final, implementation does not), checkstyle rejects |final| qualification
    // of method parameters in interface methods.
    //
    // To avoid making empty promises. we override error-prone's default behavior,
    // and allow the CompileTimeConstant parameters to be non-final.

    char PLACEHOLDER = '%';

    // New-style API.
    /**
     * Allocate an error-level log message, which the caller will fill with
     * additional parameters according to |format|. After filling the message
     * with parameters, the caller must call flush(), to actually log the message.
     *
     * Error-level messages should be used when a malfunction has occurred,
     * and the malfunction is likely to cause an externally visible problem.
     * For example: we failed to initialize the Wifi interface.
     *
     * Typical usage is as follows:
     *     WifiDevice() {
     *         mLog = new LogcatLog("ModuleName");
     *     }
     *
     *     void start() {
     *         // ...
     *         mLog.err("error % while starting interface %").c(errNum).c(ifaceName).flush();
     *     }
     *
     *     void stop() {
     *         // ...
     *         mLog.err("error % while stopping interface %").c(errNum).c(ifaceName).flush();
     *     }
     */
    @CheckReturnValue
    @NonNull
    LogMessage err(@CompileTimeConstant @NonNull String format);

    /**
     * Like {@link #err(String) err()}, except that a warning-level message is
     * allocated.
     *
     * Warning-level messages should be used when a malfunction has occurred,
     * but the malfunction is _unlikely_ to cause an externally visible problem
     * on its own. For example: if we fail to start the debugging subsystem.
     */
    @CheckReturnValue
    @NonNull
    LogMessage warn(@CompileTimeConstant @NonNull String format);

    /**
     * Like {@link #err(String) err()}, except that a info-level message is
     * allocated.
     *
     * Info-level messages should be used to report progress or status messages
     * that help understand the program's external behavior. For example: we
     * might log an info message before initiating a Wifi association.
     */
    @CheckReturnValue
    @NonNull
    LogMessage info(@CompileTimeConstant @NonNull String format);

    /**
     * Like {@link #err(String) err()}, except:
     * - a trace-level message is allocated
     * - the log message is prefixed with the caller's name
     *
     * Trace-level messages should be used to report progress or status messages
     * that help understand the program's internal behavior. For example:
     * "invoked with verbose=%".
     */
    @CheckReturnValue
    @NonNull
    LogMessage trace(@CompileTimeConstant @NonNull String format);

    /**
     * Like {@link #trace(String) trace(String)}, except that, rather than logging
     * the immediate caller, the |numFramesToIgnore + 1|-th caller will be logged.
     *
     * E.g. if numFramesToIgnore == 1, then the caller's caller will be logged.
     *
     * Trace-level messages should be used to report progress or status messages
     * that help understand the program's internal behavior. For example:
     * "invoked with verbose=%".
     */
    @CheckReturnValue
    @NonNull
    LogMessage trace(@NonNull String format, int numFramesToIgnore);

    /**
     * Like {@link #err(String) err()}, except that a dump-level message is
     * allocated.
     *
     * Dump-level messages should be used to report detailed internal state.
     */
    @CheckReturnValue
    @NonNull
    LogMessage dump(@CompileTimeConstant @NonNull String format);

    /**
     * Log a warning using the default tag for this WifiLog instance. Mark
     * the message as 'clean' (i.e. _not_ containing any sensitive data).
     *
     * NOTE: this method should only be used for literal strings. For messages with
     * parameters, use err().
     *
     * @param msg the message to be logged
     */
    void eC(@CompileTimeConstant String msg);

    /**
     * Like {@link #eC(String)} eC()}, except that a warning-level message
     * is logged.
     */
    void wC(@CompileTimeConstant String msg);

    /**
     * Like {@link #eC(String)} eC()}, except that an info-level message
     * is logged.
     */
    void iC(@CompileTimeConstant String msg);

    /**
     * Like {@link #eC(String)} eC()}, except that a trace-level message
     * is logged.
     */
    void tC(@CompileTimeConstant String msg);

    /**
     * Note: dC() is deliberately omitted, as "dumping" is inherently at
     * odds with the intention that the caller pass in a literal string.
     */

    /**
     * Represents a single log message.
     *
     * Implementations are expected _not_ to be thread-safe.
     */
    interface LogMessage {
        /**
         * Replace the first available placeholder in this LogMessage's format
         * with the specified value. Mark the value as 'raw', to inform the
         * logging daemon that the value may contain sensitive data.
         *
         * @return |this|, to allow chaining of calls
         */
        @CheckReturnValue
        @NonNull
        LogMessage r(String value);

        /**
         * Like {@link #r(String) r()}, except that the value is marked
         * as 'clean', to inform the logging daemon that the value does _not_
         * contain sensitive data.
         */
        @CheckReturnValue
        @NonNull
        LogMessage c(String value);

        /**
         * Like {@link #c(String) c(String)}, except that the value is a long.
         */
        @CheckReturnValue
        @NonNull
        LogMessage c(long value);

        /**
         * Like {@link #c(String) c(String)}, except that the value is a char.
         */
        @CheckReturnValue
        @NonNull
        LogMessage c(char value);

        /**
         * Like {@link #c(String) c(String)}, except that the value is a boolean.
         */
        @CheckReturnValue
        @NonNull
        LogMessage c(boolean value);

        /**
         * Write this LogMessage to the logging daemon. Writing the
         * message is best effort. More specifically:
         * 1) The operation is non-blocking. If we’re unable to write
         *    the log message to the IPC channel, the message is
         *    dropped silently.
         * 2) If the number of |value|s provided exceeds the number of
         *    placeholders in the |format|, then extraneous |value|s
         *    are silently dropped.
         * 3) If the number of placeholders in the |format| exceeds
         *    the number of |value|s provided, the message is sent to
         *    the logging daemon without generating an Exception.
         * 4) If the total message length exceeds the logging
         *    protocol’s maximum message length, the message is
         *    silently truncated.
         */
        void flush();
    }

    // Legacy API.
    /**
     * Log an error using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     * TODO(b/30736737): Remove this method, once all code has migrated to alternatives.
     */
    void e(String msg);

    /**
     * Log a warning using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     * TODO(b/30736737): Remove this method, once all code has migrated to alternatives.
     */
    void w(String msg);

    /**
     * Log an informational message using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     * TODO(b/30736737): Remove this method, once all code has migrated to alternatives.
     */
    void i(String msg);

    /**
     * Log a debug message using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     * TODO(b/30736737): Remove this method, once all code has migrated to alternatives.
     */
    void d(String msg);

    /**
     * Log a verbose message using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     * TODO(b/30736737): Remove this method, once all code has migrated to alternatives.
     */
    void v(String msg);
}

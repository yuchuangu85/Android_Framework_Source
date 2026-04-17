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

package com.android.internal.protolog;

import android.annotation.IntDef;
import android.annotation.NonNull;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * JNI bridge to native ProtoLog implementation.
 *
 * @hide
 */
public final class ProtoLogNative {

    @IntDef({
            PROTO_LOG_LEVEL_UNDEFINED,
            PROTO_LOG_LEVEL_VERBOSE,
            PROTO_LOG_LEVEL_DEBUG,
            PROTO_LOG_LEVEL_INFO,
            PROTO_LOG_LEVEL_WARN,
            PROTO_LOG_LEVEL_ERROR,
            PROTO_LOG_LEVEL_WTF,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProtoLogLevel {
    }

    public static final int PROTO_LOG_LEVEL_UNDEFINED = 0;
    public static final int PROTO_LOG_LEVEL_VERBOSE = 1;
    public static final int PROTO_LOG_LEVEL_DEBUG = 2;
    public static final int PROTO_LOG_LEVEL_INFO = 3;
    public static final int PROTO_LOG_LEVEL_WARN = 4;
    public static final int PROTO_LOG_LEVEL_ERROR = 5;
    public static final int PROTO_LOG_LEVEL_WTF = 6;

    /**
     * Log a ProtoLog message.
     *
     * @param level   The log level.
     * @param tag     The log tag.
     * @param message The log message formatter string.
     * @param args    The log message arguments.
     */
    public static void log(@ProtoLogLevel int level, @NonNull String tag,
            @NonNull String message, @NonNull Object... args) {
        if (args.length > 32) {
            // The limit of 32 arguments comes from using a 64-bit long for the parameter mask,
            // a 2-bit type identifier for each argument (2 bits * 32 args = 64 bits).
            throw new IllegalArgumentException(
                    "ProtoLog does not support more than 32 arguments: " + message);
        }

        // NOTE: Ensure that nothing in this function call triggers a ProtoLog call otherwise we
        //       will reuse the same shared buffer causing tracing inconsistencies.
        ThreadBuffer buffer = sThreadBuffer.get();

        try {
            long[] primitiveArgs = buffer.mPrimitiveArgs;
            Object[] stringArgs = buffer.mStringArgs;

            long mask = 0;
            int argIdx = 0;

            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null) {
                    stringArgs[argIdx] = null;
                    primitiveArgs[argIdx] = 0L;
                    argIdx++;
                    continue;
                }

                switch (arg) {
                    case Integer x -> {
                        primitiveArgs[argIdx++] = x.longValue();
                        mask |= 0b01 << (i * 2);
                    }
                    case Long x -> {
                        primitiveArgs[argIdx++] = x;
                        mask |= 0b01 << (i * 2);
                    }
                    case Float x -> {
                        primitiveArgs[argIdx++] = Double.doubleToRawLongBits(x);
                        mask |= 0b10 << (i * 2);
                    }
                    case Double x -> {
                        primitiveArgs[argIdx++] = Double.doubleToRawLongBits(x);
                        mask |= 0b10 << (i * 2);
                    }
                    case Boolean x -> {
                        primitiveArgs[argIdx++] = x ? 1 : 0;
                        mask |= 0b11 << (i * 2);
                    }
                    case String x -> stringArgs[argIdx++] = x;
                    default -> stringArgs[argIdx++] = arg.toString();
                }
            }

            log(level, tag, message, mask, args.length, primitiveArgs, stringArgs);
        } finally {
            // We need to clear the string args to avoid memory leaks
            Object[] stringArgs = buffer.mStringArgs;
            for (int i = 0; i < args.length; i++) {
                stringArgs[i] = null;
            }
        }
    }

    /**
     * Log a ProtoLog message.
     *
     * @param level       The log level.
     * @param tag         The log tag.
     * @param messageHash The hash of the log message.
     * @param paramsMask  The mask of the log message arguments.
     * @param args        The log message arguments.
     */
    public static void log(@ProtoLogLevel int level, @NonNull String tag, long messageHash,
            long paramsMask, @NonNull Object... args) {
        if (args.length > 32) {
            // The limit of 32 arguments comes from using a 64-bit long for the parameter mask,
            // a 2-bit type identifier for each argument (2 bits * 32 args = 64 bits).
            throw new IllegalArgumentException(
                    "ProtoLog does not support more than 32 arguments. Hash: " + messageHash);
        }

        // NOTE: Ensure that nothing in this function call triggers a ProtoLog call otherwise we
        //       will reuse the same shared buffer causing tracing inconsistencies.
        ThreadBuffer buffer = sThreadBuffer.get();

        try {
            long[] primitiveArgs = buffer.mPrimitiveArgs;
            Object[] stringArgs = buffer.mStringArgs;

            int argIdx = 0;

            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null) {
                    stringArgs[argIdx] = null;
                    primitiveArgs[argIdx] = 0L;
                    argIdx++;
                    continue;
                }

                switch (arg) {
                    case Integer x -> primitiveArgs[argIdx++] = x.longValue();
                    case Long x -> primitiveArgs[argIdx++] = x;
                    case Float x -> primitiveArgs[argIdx++] = Double.doubleToRawLongBits(x);
                    case Double x -> primitiveArgs[argIdx++] = Double.doubleToRawLongBits(x);
                    case Boolean x -> primitiveArgs[argIdx++] = x ? 1 : 0;
                    case String x -> stringArgs[argIdx++] = x;
                    default -> stringArgs[argIdx++] = arg.toString();
                }
            }

            log(level, tag, messageHash, paramsMask, args.length, primitiveArgs, stringArgs);
        } finally {
            // We need to clear the string args to avoid memory leaks
            Object[] stringArgs = buffer.mStringArgs;
            for (int i = 0; i < args.length; i++) {
                stringArgs[i] = null;
            }
        }
    }

    private static final ThreadLocal<ThreadBuffer> sThreadBuffer =
            ThreadLocal.withInitial(ThreadBuffer::new);

    private static class ThreadBuffer {
        final long[] mPrimitiveArgs = new long[32];
        final Object[] mStringArgs = new Object[32];
    }

    /**
     * Initialize the native implementation.
     */
    @CriticalNative
    public static native void init();

    /**
     * Native log implementation. This is what will be called from Java.
     * The parameter types in args are defined by paramsMask.
     */
    @FastNative
    private static native void log(int level, @NonNull String group, @NonNull String message,
            long paramsMask, int argCount, long[] primitiveArgs, Object[] stringArgs);

    /**
     * Native log implementation. This is what will be called from Java.
     * The parameter types in args are defined by paramsMask.
     */
    @FastNative
    private static native void log(int level, @NonNull String group, long messageHash,
            long paramsMask, int argCount, long[] primitiveArgs, Object[] stringArgs);
}

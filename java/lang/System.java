/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright (C) 2008 The Android Open Source Project
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

package java.lang;

import android.system.ErrnoException;
import android.system.StructPasswd;
import android.system.StructUtsname;
import dalvik.system.VMRuntime;
import dalvik.system.VMStack;
import java.io.BufferedInputStream;
import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.Channel;
import java.nio.channels.spi.SelectorProvider;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import libcore.icu.ICU;
import libcore.io.Libcore;

/**
 * Provides access to system-related information and resources including
 * standard input and output. Enables clients to dynamically load native
 * libraries. All methods of this class are accessed in a static way and the
 * class itself can not be instantiated.
 *
 * @see Runtime
 */
public final class System {

    /**
     * Default input stream.
     */
    public static final InputStream in;

    /**
     * Default output stream.
     */
    public static final PrintStream out;

    /**
     * Default error output stream.
     */
    public static final PrintStream err;

    private static final String PATH_SEPARATOR = ":";
    private static final String LINE_SEPARATOR = "\n";
    private static final String FILE_SEPARATOR = "/";

    private static final Properties unchangeableSystemProperties;
    private static Properties systemProperties;

    /**
     * Dedicated lock for GC / Finalization logic.
     */
    private static final Object lock = new Object();

    /**
     * Whether or not we need to do a GC before running the finalizers.
     */
    private static boolean runGC;

    /**
     * If we just ran finalization, we might want to do a GC to free the finalized objects.
     * This lets us do gc/runFinlization/gc sequences but prevents back to back System.gc().
     */
    private static boolean justRanFinalization;

    static {
        err = new PrintStream(new FileOutputStream(FileDescriptor.err));
        out = new PrintStream(new FileOutputStream(FileDescriptor.out));
        in = new BufferedInputStream(new FileInputStream(FileDescriptor.in));
        unchangeableSystemProperties = initUnchangeableSystemProperties();
        systemProperties = createSystemProperties();

        addLegacyLocaleSystemProperties();
    }

    private static void addLegacyLocaleSystemProperties() {
        final String locale = getProperty("user.locale", "");
        if (!locale.isEmpty()) {
            Locale l = Locale.forLanguageTag(locale);
            setUnchangeableSystemProperty("user.language", l.getLanguage());
            setUnchangeableSystemProperty("user.region", l.getCountry());
            setUnchangeableSystemProperty("user.variant", l.getVariant());
        } else {
            // If "user.locale" isn't set we fall back to our old defaults of
            // language="en" and region="US" (if unset) and don't attempt to set it.
            // The Locale class will fall back to using user.language and
            // user.region if unset.
            final String language = getProperty("user.language", "");
            final String region = getProperty("user.region", "");

            if (language.isEmpty()) {
                setUnchangeableSystemProperty("user.language", "en");
            }

            if (region.isEmpty()) {
                setUnchangeableSystemProperty("user.region", "US");
            }
        }
    }

    /**
     * Sets the standard input stream to the given user defined input stream.
     *
     * @param newIn
     *            the user defined input stream to set as the standard input
     *            stream.
     */
    public static void setIn(InputStream newIn) {
        setFieldImpl("in", "Ljava/io/InputStream;", newIn);
    }

    /**
     * Sets the standard output stream to the given user defined output stream.
     *
     * @param newOut
     *            the user defined output stream to set as the standard output
     *            stream.
     */
    public static void setOut(PrintStream newOut) {
        setFieldImpl("out", "Ljava/io/PrintStream;", newOut);
    }

    /**
     * Sets the standard error output stream to the given user defined output
     * stream.
     *
     * @param newErr
     *            the user defined output stream to set as the standard error
     *            output stream.
     */
    public static void setErr(PrintStream newErr) {
        setFieldImpl("err", "Ljava/io/PrintStream;", newErr);
    }

    /**
     * Prevents this class from being instantiated.
     */
    private System() {
    }

    /**
     * Copies {@code length} elements from the array {@code src},
     * starting at offset {@code srcPos}, into the array {@code dst},
     * starting at offset {@code dstPos}.
     *
     * <p>The source and destination arrays can be the same array,
     * in which case copying is performed as if the source elements
     * are first copied into a temporary array and then into the
     * destination array.
     *
     * @param src
     *            the source array to copy the content.
     * @param srcPos
     *            the starting index of the content in {@code src}.
     * @param dst
     *            the destination array to copy the data into.
     * @param dstPos
     *            the starting index for the copied content in {@code dst}.
     * @param length
     *            the number of elements to be copied.
     */

    public static native void arraycopy(Object src, int srcPos,
        Object dst, int dstPos, int length);

    /**
     * The char array length threshold below which to use a Java
     * (non-native) version of arraycopy() instead of the native
     * version. See b/7103825.
     */
    private static final int ARRAYCOPY_SHORT_CHAR_ARRAY_THRESHOLD = 32;

    /**
     * The char[] specialized version of arraycopy().
     *
     * @hide internal use only
     */
    public static void arraycopy(char[] src, int srcPos, char[] dst, int dstPos, int length) {
        if (src == null) {
            throw new NullPointerException("src == null");
        }
        if (dst == null) {
            throw new NullPointerException("dst == null");
        }
        if (srcPos < 0 || dstPos < 0 || length < 0 ||
            srcPos > src.length - length || dstPos > dst.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                "src.length=" + src.length + " srcPos=" + srcPos +
                " dst.length=" + dst.length + " dstPos=" + dstPos + " length=" + length);
        }
        if (length <= ARRAYCOPY_SHORT_CHAR_ARRAY_THRESHOLD) {
            // Copy char by char for shorter arrays.
            if (src == dst && srcPos < dstPos && dstPos < srcPos + length) {
                // Copy backward (to avoid overwriting elements before
                // they are copied in case of an overlap on the same
                // array.)
                for (int i = length - 1; i >= 0; --i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            } else {
                // Copy forward.
                for (int i = 0; i < length; ++i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            }
        } else {
            // Call the native version for longer arrays.
            arraycopyCharUnchecked(src, srcPos, dst, dstPos, length);
        }
    }

    /**
     * The char[] specialized, unchecked, native version of
     * arraycopy(). This assumes error checking has been done.
     */
    private static native void arraycopyCharUnchecked(char[] src, int srcPos,
        char[] dst, int dstPos, int length);

    /**
     * The byte array length threshold below which to use a Java
     * (non-native) version of arraycopy() instead of the native
     * version. See b/7103825.
     */
    private static final int ARRAYCOPY_SHORT_BYTE_ARRAY_THRESHOLD = 32;

    /**
     * The byte[] specialized version of arraycopy().
     *
     * @hide internal use only
     */
    public static void arraycopy(byte[] src, int srcPos, byte[] dst, int dstPos, int length) {
        if (src == null) {
            throw new NullPointerException("src == null");
        }
        if (dst == null) {
            throw new NullPointerException("dst == null");
        }
        if (srcPos < 0 || dstPos < 0 || length < 0 ||
            srcPos > src.length - length || dstPos > dst.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                "src.length=" + src.length + " srcPos=" + srcPos +
                " dst.length=" + dst.length + " dstPos=" + dstPos + " length=" + length);
        }
        if (length <= ARRAYCOPY_SHORT_BYTE_ARRAY_THRESHOLD) {
            // Copy byte by byte for shorter arrays.
            if (src == dst && srcPos < dstPos && dstPos < srcPos + length) {
                // Copy backward (to avoid overwriting elements before
                // they are copied in case of an overlap on the same
                // array.)
                for (int i = length - 1; i >= 0; --i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            } else {
                // Copy forward.
                for (int i = 0; i < length; ++i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            }
        } else {
            // Call the native version for longer arrays.
            arraycopyByteUnchecked(src, srcPos, dst, dstPos, length);
        }
    }

    /**
     * The byte[] specialized, unchecked, native version of
     * arraycopy(). This assumes error checking has been done.
     */
    private static native void arraycopyByteUnchecked(byte[] src, int srcPos,
        byte[] dst, int dstPos, int length);

    /**
     * The short array length threshold below which to use a Java
     * (non-native) version of arraycopy() instead of the native
     * version. See b/7103825.
     */
    private static final int ARRAYCOPY_SHORT_SHORT_ARRAY_THRESHOLD = 32;

    /**
     * The short[] specialized version of arraycopy().
     *
     * @hide internal use only
     */
    public static void arraycopy(short[] src, int srcPos, short[] dst, int dstPos, int length) {
        if (src == null) {
            throw new NullPointerException("src == null");
        }
        if (dst == null) {
            throw new NullPointerException("dst == null");
        }
        if (srcPos < 0 || dstPos < 0 || length < 0 ||
            srcPos > src.length - length || dstPos > dst.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                "src.length=" + src.length + " srcPos=" + srcPos +
                " dst.length=" + dst.length + " dstPos=" + dstPos + " length=" + length);
        }
        if (length <= ARRAYCOPY_SHORT_SHORT_ARRAY_THRESHOLD) {
            // Copy short by short for shorter arrays.
            if (src == dst && srcPos < dstPos && dstPos < srcPos + length) {
                // Copy backward (to avoid overwriting elements before
                // they are copied in case of an overlap on the same
                // array.)
                for (int i = length - 1; i >= 0; --i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            } else {
                // Copy forward.
                for (int i = 0; i < length; ++i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            }
        } else {
            // Call the native version for longer arrays.
            arraycopyShortUnchecked(src, srcPos, dst, dstPos, length);
        }
    }

    /**
     * The short[] specialized, unchecked, native version of
     * arraycopy(). This assumes error checking has been done.
     */
    private static native void arraycopyShortUnchecked(short[] src, int srcPos,
        short[] dst, int dstPos, int length);

    /**
     * The short array length threshold below which to use a Java
     * (non-native) version of arraycopy() instead of the native
     * version. See b/7103825.
     */
    private static final int ARRAYCOPY_SHORT_INT_ARRAY_THRESHOLD = 32;

    /**
     * The int[] specialized version of arraycopy().
     *
     * @hide internal use only
     */
    public static void arraycopy(int[] src, int srcPos, int[] dst, int dstPos, int length) {
        if (src == null) {
            throw new NullPointerException("src == null");
        }
        if (dst == null) {
            throw new NullPointerException("dst == null");
        }
        if (srcPos < 0 || dstPos < 0 || length < 0 ||
            srcPos > src.length - length || dstPos > dst.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                "src.length=" + src.length + " srcPos=" + srcPos +
                " dst.length=" + dst.length + " dstPos=" + dstPos + " length=" + length);
        }
        if (length <= ARRAYCOPY_SHORT_INT_ARRAY_THRESHOLD) {
            // Copy int by int for shorter arrays.
            if (src == dst && srcPos < dstPos && dstPos < srcPos + length) {
                // Copy backward (to avoid overwriting elements before
                // they are copied in case of an overlap on the same
                // array.)
                for (int i = length - 1; i >= 0; --i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            } else {
                // Copy forward.
                for (int i = 0; i < length; ++i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            }
        } else {
            // Call the native version for longer arrays.
            arraycopyIntUnchecked(src, srcPos, dst, dstPos, length);
        }
    }

    /**
     * The int[] specialized, unchecked, native version of
     * arraycopy(). This assumes error checking has been done.
     */
    private static native void arraycopyIntUnchecked(int[] src, int srcPos,
        int[] dst, int dstPos, int length);

    /**
     * The short array length threshold below which to use a Java
     * (non-native) version of arraycopy() instead of the native
     * version. See b/7103825.
     */
    private static final int ARRAYCOPY_SHORT_LONG_ARRAY_THRESHOLD = 32;

    /**
     * The long[] specialized version of arraycopy().
     *
     * @hide internal use only
     */
    public static void arraycopy(long[] src, int srcPos, long[] dst, int dstPos, int length) {
        if (src == null) {
            throw new NullPointerException("src == null");
        }
        if (dst == null) {
            throw new NullPointerException("dst == null");
        }
        if (srcPos < 0 || dstPos < 0 || length < 0 ||
            srcPos > src.length - length || dstPos > dst.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                "src.length=" + src.length + " srcPos=" + srcPos +
                " dst.length=" + dst.length + " dstPos=" + dstPos + " length=" + length);
        }
        if (length <= ARRAYCOPY_SHORT_LONG_ARRAY_THRESHOLD) {
            // Copy long by long for shorter arrays.
            if (src == dst && srcPos < dstPos && dstPos < srcPos + length) {
                // Copy backward (to avoid overwriting elements before
                // they are copied in case of an overlap on the same
                // array.)
                for (int i = length - 1; i >= 0; --i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            } else {
                // Copy forward.
                for (int i = 0; i < length; ++i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            }
        } else {
            // Call the native version for longer arrays.
            arraycopyLongUnchecked(src, srcPos, dst, dstPos, length);
        }
    }

    /**
     * The long[] specialized, unchecked, native version of
     * arraycopy(). This assumes error checking has been done.
     */
    private static native void arraycopyLongUnchecked(long[] src, int srcPos,
        long[] dst, int dstPos, int length);

    /**
     * The short array length threshold below which to use a Java
     * (non-native) version of arraycopy() instead of the native
     * version. See b/7103825.
     */
    private static final int ARRAYCOPY_SHORT_FLOAT_ARRAY_THRESHOLD = 32;

    /**
     * The float[] specialized version of arraycopy().
     *
     * @hide internal use only
     */
    public static void arraycopy(float[] src, int srcPos, float[] dst, int dstPos, int length) {
        if (src == null) {
            throw new NullPointerException("src == null");
        }
        if (dst == null) {
            throw new NullPointerException("dst == null");
        }
        if (srcPos < 0 || dstPos < 0 || length < 0 ||
            srcPos > src.length - length || dstPos > dst.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                "src.length=" + src.length + " srcPos=" + srcPos +
                " dst.length=" + dst.length + " dstPos=" + dstPos + " length=" + length);
        }
        if (length <= ARRAYCOPY_SHORT_FLOAT_ARRAY_THRESHOLD) {
            // Copy float by float for shorter arrays.
            if (src == dst && srcPos < dstPos && dstPos < srcPos + length) {
                // Copy backward (to avoid overwriting elements before
                // they are copied in case of an overlap on the same
                // array.)
                for (int i = length - 1; i >= 0; --i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            } else {
                // Copy forward.
                for (int i = 0; i < length; ++i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            }
        } else {
            // Call the native version for floater arrays.
            arraycopyFloatUnchecked(src, srcPos, dst, dstPos, length);
        }
    }

    /**
     * The float[] specialized, unchecked, native version of
     * arraycopy(). This assumes error checking has been done.
     */
    private static native void arraycopyFloatUnchecked(float[] src, int srcPos,
        float[] dst, int dstPos, int length);

    /**
     * The short array length threshold below which to use a Java
     * (non-native) version of arraycopy() instead of the native
     * version. See b/7103825.
     */
    private static final int ARRAYCOPY_SHORT_DOUBLE_ARRAY_THRESHOLD = 32;

    /**
     * The double[] specialized version of arraycopy().
     *
     * @hide internal use only
     */
    public static void arraycopy(double[] src, int srcPos, double[] dst, int dstPos, int length) {
        if (src == null) {
            throw new NullPointerException("src == null");
        }
        if (dst == null) {
            throw new NullPointerException("dst == null");
        }
        if (srcPos < 0 || dstPos < 0 || length < 0 ||
            srcPos > src.length - length || dstPos > dst.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                "src.length=" + src.length + " srcPos=" + srcPos +
                " dst.length=" + dst.length + " dstPos=" + dstPos + " length=" + length);
        }
        if (length <= ARRAYCOPY_SHORT_DOUBLE_ARRAY_THRESHOLD) {
            // Copy double by double for shorter arrays.
            if (src == dst && srcPos < dstPos && dstPos < srcPos + length) {
                // Copy backward (to avoid overwriting elements before
                // they are copied in case of an overlap on the same
                // array.)
                for (int i = length - 1; i >= 0; --i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            } else {
                // Copy forward.
                for (int i = 0; i < length; ++i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            }
        } else {
            // Call the native version for floater arrays.
            arraycopyDoubleUnchecked(src, srcPos, dst, dstPos, length);
        }
    }

    /**
     * The double[] specialized, unchecked, native version of
     * arraycopy(). This assumes error checking has been done.
     */
    private static native void arraycopyDoubleUnchecked(double[] src, int srcPos,
        double[] dst, int dstPos, int length);

    /**
     * The short array length threshold below which to use a Java
     * (non-native) version of arraycopy() instead of the native
     * version. See b/7103825.
     */
    private static final int ARRAYCOPY_SHORT_BOOLEAN_ARRAY_THRESHOLD = 32;

    /**
     * The boolean[] specialized version of arraycopy().
     *
     * @hide internal use only
     */
    public static void arraycopy(boolean[] src, int srcPos, boolean[] dst, int dstPos, int length) {
        if (src == null) {
            throw new NullPointerException("src == null");
        }
        if (dst == null) {
            throw new NullPointerException("dst == null");
        }
        if (srcPos < 0 || dstPos < 0 || length < 0 ||
            srcPos > src.length - length || dstPos > dst.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                "src.length=" + src.length + " srcPos=" + srcPos +
                " dst.length=" + dst.length + " dstPos=" + dstPos + " length=" + length);
        }
        if (length <= ARRAYCOPY_SHORT_BOOLEAN_ARRAY_THRESHOLD) {
            // Copy boolean by boolean for shorter arrays.
            if (src == dst && srcPos < dstPos && dstPos < srcPos + length) {
                // Copy backward (to avoid overwriting elements before
                // they are copied in case of an overlap on the same
                // array.)
                for (int i = length - 1; i >= 0; --i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            } else {
                // Copy forward.
                for (int i = 0; i < length; ++i) {
                    dst[dstPos + i] = src[srcPos + i];
                }
            }
        } else {
            // Call the native version for floater arrays.
            arraycopyBooleanUnchecked(src, srcPos, dst, dstPos, length);
        }
    }

    /**
     * The boolean[] specialized, unchecked, native version of
     * arraycopy(). This assumes error checking has been done.
     */
    private static native void arraycopyBooleanUnchecked(boolean[] src, int srcPos,
        boolean[] dst, int dstPos, int length);

    /**
     * Returns the current time in milliseconds since January 1, 1970 00:00:00.0 UTC.
     *
     * <p>This method always returns UTC times, regardless of the system's time zone.
     * This is often called "Unix time" or "epoch time".
     * Use a {@link java.text.DateFormat} instance to format this time for display to a human.
     *
     * <p>This method shouldn't be used for measuring timeouts or
     * other elapsed time measurements, as changing the system time can affect
     * the results. Use {@link #nanoTime} for that.
     */
    public static native long currentTimeMillis();

    /**
     * Returns the current timestamp of the most precise timer available on the
     * local system, in nanoseconds. Equivalent to Linux's {@code CLOCK_MONOTONIC}.
     *
     * <p>This timestamp should only be used to measure a duration by comparing it
     * against another timestamp on the same device.
     * Values returned by this method do not have a defined correspondence to
     * wall clock times; the zero value is typically whenever the device last booted.
     * Use {@link #currentTimeMillis} if you want to know what time it is.
     */
    public static native long nanoTime();

    /**
     * Causes the VM to stop running and the program to exit with the given exit status.
     * If {@link #runFinalizersOnExit(boolean)} has been previously invoked with a
     * {@code true} argument, then all objects will be properly
     * garbage-collected and finalized first.
     */
    public static void exit(int code) {
        Runtime.getRuntime().exit(code);
    }

    /**
     * Indicates to the VM that it would be a good time to run the
     * garbage collector. Note that this is a hint only. There is no guarantee
     * that the garbage collector will actually be run.
     */
    public static void gc() {
        boolean shouldRunGC;
        synchronized(lock) {
            shouldRunGC = justRanFinalization;
            if (shouldRunGC) {
                justRanFinalization = false;
            } else {
                runGC = true;
            }
        }
        if (shouldRunGC) {
            Runtime.getRuntime().gc();
        }
    }

    /**
     * Returns the value of the environment variable with the given name, or null if no such
     * variable exists.
     */
    public static String getenv(String name) {
        if (name == null) {
            throw new NullPointerException("name == null");
        }
        return Libcore.os.getenv(name);
    }

    /**
     * Returns an unmodifiable map of all environment variables to their values.
     */
    public static Map<String, String> getenv() {
        Map<String, String> map = new HashMap<String, String>();
        for (String entry : Libcore.os.environ()) {
            int index = entry.indexOf('=');
            if (index != -1) {
                map.put(entry.substring(0, index), entry.substring(index + 1));
            }
        }
        return new SystemEnvironment(map);
    }

    /**
     * Returns the inherited channel from the creator of the current virtual
     * machine.
     *
     * @return the inherited {@link Channel} or {@code null} if none exists.
     * @throws IOException
     *             if an I/O error occurred.
     * @see SelectorProvider
     * @see SelectorProvider#inheritedChannel()
     */
    public static Channel inheritedChannel() throws IOException {
        return SelectorProvider.provider().inheritedChannel();
    }

    /**
     * Returns the system properties. Note that this is not a copy, so that
     * changes made to the returned Properties object will be reflected in
     * subsequent calls to getProperty and getProperties.
     *
     * @return the system properties.
     */
    public static Properties getProperties() {
        return systemProperties;
    }

    private static Properties initUnchangeableSystemProperties() {
        VMRuntime runtime = VMRuntime.getRuntime();
        Properties p = new Properties();

        String projectUrl = "http://www.android.com/";
        String projectName = "The Android Project";

        p.put("java.boot.class.path", runtime.bootClassPath());
        p.put("java.class.path", runtime.classPath());

        // None of these four are meaningful on Android, but these keys are guaranteed
        // to be present for System.getProperty. For java.class.version, we use the maximum
        // class file version that dx currently supports.
        p.put("java.class.version", "50.0");
        p.put("java.compiler", "");
        p.put("java.ext.dirs", "");
        p.put("java.version", "0");

        // TODO: does this make any sense? Should we just leave java.home unset?
        String javaHome = getenv("JAVA_HOME");
        if (javaHome == null) {
            javaHome = "/system";
        }
        p.put("java.home", javaHome);

        p.put("java.specification.name", "Dalvik Core Library");
        p.put("java.specification.vendor", projectName);
        p.put("java.specification.version", "0.9");

        p.put("java.vendor", projectName);
        p.put("java.vendor.url", projectUrl);
        p.put("java.vm.name", "Dalvik");
        p.put("java.vm.specification.name", "Dalvik Virtual Machine Specification");
        p.put("java.vm.specification.vendor", projectName);
        p.put("java.vm.specification.version", "0.9");
        p.put("java.vm.vendor", projectName);
        p.put("java.vm.version", runtime.vmVersion());

        p.put("java.runtime.name", "Android Runtime");
        p.put("java.runtime.version", "0.9");
        p.put("java.vm.vendor.url", projectUrl);

        p.put("file.encoding", "UTF-8");

        try {
            StructPasswd passwd = Libcore.os.getpwuid(Libcore.os.getuid());
            p.put("user.name", passwd.pw_name);
        } catch (ErrnoException exception) {
            throw new AssertionError(exception);
        }

        StructUtsname info = Libcore.os.uname();
        p.put("os.arch", info.machine);
        p.put("os.name", info.sysname);
        p.put("os.version", info.release);

        // Undocumented Android-only properties.
        p.put("android.icu.library.version", ICU.getIcuVersion());
        p.put("android.icu.unicode.version", ICU.getUnicodeVersion());
        p.put("android.icu.cldr.version", ICU.getCldrVersion());

        // Property override for ICU4J : this is the location of the ICU4C data. This
        // is prioritized over the properties in ICUConfig.properties. The issue with using
        // that is that it doesn't play well with jarjar and it needs complicated build rules
        // to change its default value.
        String icuDataPath = generateIcuDataPath();
        p.put("android.icu.impl.ICUBinary.dataPath", icuDataPath);

        parsePropertyAssignments(p, specialProperties());

        // Override built-in properties with settings from the command line.
        parsePropertyAssignments(p, runtime.properties());

        if (p.containsKey("file.separator")) {
            logE("Ignoring command line argument: -Dfile.separator");
        }

        if (p.containsKey("line.separator")) {
            logE("Ignoring command line argument: -Dline.separator");
        }

        if (p.containsKey("path.separator")) {
            logE("Ignoring command line argument: -Dpath.separator");
        }

        // We ignore values for "file.separator", "line.separator" and "path.separator" from
        // the command line. They're fixed on the operating systems we support.
        p.put("file.separator", FILE_SEPARATOR);
        p.put("line.separator", LINE_SEPARATOR);
        p.put("path.separator", PATH_SEPARATOR);

        return p;
    }

    /**
     * Inits an unchangeable system property with the given value.
     *
     * This is called from native code when the environment needs to change under native
     * bridge emulation.
     *
     * @hide also visible for tests.
     */
    public static void setUnchangeableSystemProperty(String name, String value) {
        checkPropertyName(name);
        unchangeableSystemProperties.put(name, value);
    }

    private static void setDefaultChangeableProperties(Properties p) {
        // On Android, each app gets its own temporary directory.
        // (See android.app.ActivityThread.) This is just a fallback default,
        // useful only on the host.
        p.put("java.io.tmpdir", "/tmp");

        // Android has always had an empty "user.home" (see docs for getProperty).
        // This is not useful for normal android apps which need to use android specific
        // APIs such as {@code Context.getFilesDir} and {@code Context.getCacheDir} but
        // we make it changeable for backward compatibility, so that they can change it
        // to a writeable location if required.
        p.put("user.home", "");
    }

    private static Properties createSystemProperties() {
        Properties p = new PropertiesWithNonOverrideableDefaults(unchangeableSystemProperties);
        setDefaultChangeableProperties(p);
        return p;
    }

    private static String generateIcuDataPath() {
        StringBuilder icuDataPathBuilder = new StringBuilder();
        // ICU should first look in ANDROID_DATA. This is used for (optional) timezone data.
        String dataIcuDataPath = getEnvironmentPath("ANDROID_DATA", "/misc/zoneinfo/current/icu");
        if (dataIcuDataPath != null) {
            icuDataPathBuilder.append(dataIcuDataPath);
        }

        // ICU should always look in ANDROID_ROOT.
        String systemIcuDataPath = getEnvironmentPath("ANDROID_ROOT", "/usr/icu");
        if (systemIcuDataPath != null) {
            if (icuDataPathBuilder.length() > 0) {
                icuDataPathBuilder.append(":");
            }
            icuDataPathBuilder.append(systemIcuDataPath);
        }
        return icuDataPathBuilder.toString();
    }

    /**
     * Creates a path by combining the value of an environment variable with a relative path.
     * Returns {@code null} if the environment variable is not set.
     */
    private static String getEnvironmentPath(String environmentVariable, String path) {
        String variable = getenv(environmentVariable);
        if (variable == null) {
            return null;
        }
        return variable + path;
    }

    /**
     * Returns an array of "key=value" strings containing information not otherwise
     * easily available, such as #defined library versions.
     */
    private static native String[] specialProperties();

    /**
     * Adds each element of 'assignments' to 'p', treating each element as an
     * assignment in the form "key=value".
     */
    private static void parsePropertyAssignments(Properties p, String[] assignments) {
        for (String assignment : assignments) {
            int split = assignment.indexOf('=');
            String key = assignment.substring(0, split);
            String value = assignment.substring(split + 1);
            p.put(key, value);
        }
    }

    /**
     * Returns the value of a particular system property or {@code null} if no
     * such property exists.
     *
     * <p>The following properties are always provided by the Dalvik VM:</p>
     * <p><table BORDER="1" WIDTH="100%" CELLPADDING="3" CELLSPACING="0" SUMMARY="">
     * <tr BGCOLOR="#CCCCFF" CLASS="TableHeadingColor">
     *     <td><b>Name</b></td>        <td><b>Meaning</b></td>                    <td><b>Example</b></td></tr>
     * <tr><td>file.separator</td>     <td>{@link java.io.File#separator}</td>    <td>{@code /}</td></tr>
     *
     * <tr><td>java.class.path</td>    <td>System class path</td>                 <td>{@code .}</td></tr>
     * <tr><td>java.class.version</td> <td>(Not useful on Android)</td>           <td>{@code 50.0}</td></tr>
     * <tr><td>java.compiler</td>      <td>(Not useful on Android)</td>           <td>Empty</td></tr>
     * <tr><td>java.ext.dirs</td>      <td>(Not useful on Android)</td>           <td>Empty</td></tr>
     * <tr><td>java.home</td>          <td>Location of the VM on the file system</td> <td>{@code /system}</td></tr>
     * <tr><td>java.io.tmpdir</td>     <td>See {@link java.io.File#createTempFile}</td> <td>{@code /sdcard}</td></tr>
     * <tr><td>java.library.path</td>  <td>Search path for JNI libraries</td>     <td>{@code /vendor/lib:/system/lib}</td></tr>
     * <tr><td>java.vendor</td>        <td>Human-readable VM vendor</td>          <td>{@code The Android Project}</td></tr>
     * <tr><td>java.vendor.url</td>    <td>URL for VM vendor's web site</td>      <td>{@code http://www.android.com/}</td></tr>
     * <tr><td>java.version</td>       <td>(Not useful on Android)</td>           <td>{@code 0}</td></tr>
     *
     * <tr><td>java.specification.version</td>    <td>VM libraries version</td>        <td>{@code 0.9}</td></tr>
     * <tr><td>java.specification.vendor</td>     <td>VM libraries vendor</td>         <td>{@code The Android Project}</td></tr>
     * <tr><td>java.specification.name</td>       <td>VM libraries name</td>           <td>{@code Dalvik Core Library}</td></tr>
     * <tr><td>java.vm.version</td>               <td>VM implementation version</td>   <td>{@code 1.2.0}</td></tr>
     * <tr><td>java.vm.vendor</td>                <td>VM implementation vendor</td>    <td>{@code The Android Project}</td></tr>
     * <tr><td>java.vm.name</td>                  <td>VM implementation name</td>      <td>{@code Dalvik}</td></tr>
     * <tr><td>java.vm.specification.version</td> <td>VM specification version</td>    <td>{@code 0.9}</td></tr>
     * <tr><td>java.vm.specification.vendor</td>  <td>VM specification vendor</td>     <td>{@code The Android Project}</td></tr>
     * <tr><td>java.vm.specification.name</td>    <td>VM specification name</td>       <td>{@code Dalvik Virtual Machine Specification}</td></tr>
     *
     * <tr><td>line.separator</td>     <td>The system line separator</td>         <td>{@code \n}</td></tr>
     *
     * <tr><td>os.arch</td>            <td>OS architecture</td>                   <td>{@code armv7l}</td></tr>
     * <tr><td>os.name</td>            <td>OS (kernel) name</td>                  <td>{@code Linux}</td></tr>
     * <tr><td>os.version</td>         <td>OS (kernel) version</td>               <td>{@code 2.6.32.9-g103d848}</td></tr>
     *
     * <tr><td>path.separator</td>     <td>See {@link java.io.File#pathSeparator}</td> <td>{@code :}</td></tr>
     *
     * <tr><td>user.dir</td>           <td>Base of non-absolute paths</td>        <td>{@code /}</td></tr>
     * <tr><td>user.home</td>          <td>(Not useful on Android)</td>           <td>Empty</td></tr>
     * <tr><td>user.name</td>          <td>(Not useful on Android)</td>           <td>Empty</td></tr>
     *
     * </table>
     *
     * <p> All of the above properties except for {@code user.home} and {@code java.io.tmpdir}
     * <b>cannot be modified</b>. Any attempt to change them will be a no-op.
     *
     * @param propertyName
     *            the name of the system property to look up.
     * @return the value of the specified system property or {@code null} if the
     *         property doesn't exist.
     */
    public static String getProperty(String propertyName) {
        return getProperty(propertyName, null);
    }

    /**
     * Returns the value of a particular system property. The {@code
     * defaultValue} will be returned if no such property has been found.
     */
    public static String getProperty(String name, String defaultValue) {
        checkPropertyName(name);
        return systemProperties.getProperty(name, defaultValue);
    }

    /**
     * Sets the value of a particular system property. Most system properties
     * are read only and cannot be cleared or modified. See {@link #getProperty} for a
     * list of such properties.
     *
     * @return the old value of the property or {@code null} if the property
     *         didn't exist.
     */
    public static String setProperty(String name, String value) {
        checkPropertyName(name);
        return (String) systemProperties.setProperty(name, value);
    }

    /**
     * Removes a specific system property. Most system properties
     * are read only and cannot be cleared or modified. See {@link #getProperty} for a
     * list of such properties.
     *
     * @return the property value or {@code null} if the property didn't exist.
     * @throws NullPointerException
     *             if the argument is {@code null}.
     * @throws IllegalArgumentException
     *             if the argument is empty.
     */
    public static String clearProperty(String name) {
        checkPropertyName(name);
        return (String) systemProperties.remove(name);
    }

    private static void checkPropertyName(String name) {
        if (name == null) {
            throw new NullPointerException("name == null");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name is empty");
        }
    }

    /**
     * Returns the {@link java.io.Console} associated with this VM, or null.
     * Not all VMs will have an associated console. A console is typically only
     * available for programs run from the command line.
     * @since 1.6
     */
    public static Console console() {
        return Console.getConsole();
    }

    /**
     * Returns null. Android does not use {@code SecurityManager}. This method
     * is only provided for source compatibility.
     *
     * @return null
     */
    public static SecurityManager getSecurityManager() {
        return null;
    }

    /**
     * Returns an integer hash code for the parameter. The hash code returned is
     * the same one that would be returned by the method {@code
     * java.lang.Object.hashCode()}, whether or not the object's class has
     * overridden hashCode(). The hash code for {@code null} is {@code 0}.
     *
     * @param anObject
     *            the object to calculate the hash code.
     * @return the hash code for the given object.
     * @see java.lang.Object#hashCode
     */
    public static native int identityHashCode(Object anObject);

    /**
     * Returns the system's line separator. On Android, this is {@code "\n"}. The value comes from
     * the value of the {@code line.separator} system property.
     *
     * <p>On Android versions before Lollipop the {@code line.separator} system property can be
     * modified but this method continues to return the original value. The system property cannot
     * be modified on later versions of Android.
     *
     * @since 1.7
     */
    public static String lineSeparator() {
        return LINE_SEPARATOR;
    }

    /**
     * See {@link Runtime#load}.
     */
    public static void load(String pathName) {
        Runtime.getRuntime().load(pathName, VMStack.getCallingClassLoader());
    }

    /**
     * See {@link Runtime#loadLibrary}.
     */
    public static void loadLibrary(String libName) {
        Runtime.getRuntime().loadLibrary(libName, VMStack.getCallingClassLoader());
    }

    /**
     * @hide internal use only
     */
    public static void logE(String message) {
        log('E', message, null);
    }

    /**
     * @hide internal use only
     */
    public static void logE(String message, Throwable th) {
        log('E', message, th);
    }

    /**
     * @hide internal use only
     */
    public static void logI(String message) {
        log('I', message, null);
    }

    /**
     * @hide internal use only
     */
    public static void logI(String message, Throwable th) {
        log('I', message, th);
    }

    /**
     * @hide internal use only
     */
    public static void logW(String message) {
        log('W', message, null);
    }

    /**
     * @hide internal use only
     */
    public static void logW(String message, Throwable th) {
        log('W', message, th);
    }

    private static native void log(char type, String message, Throwable th);

    /**
     * Provides a hint to the VM that it would be useful to attempt
     * to perform any outstanding object finalization.
     */
    public static void runFinalization() {
        boolean shouldRunGC;
        synchronized(lock) {
            shouldRunGC = runGC;
            runGC = false;
        }
        if (shouldRunGC) {
            Runtime.getRuntime().gc();
        }
        Runtime.getRuntime().runFinalization();
        synchronized(lock) {
            justRanFinalization = true;
        }
    }

    /**
     * Ensures that, when the VM is about to exit, all objects are
     * finalized. Note that all finalization which occurs when the system is
     * exiting is performed after all running threads have been terminated.
     *
     * @param flag
     *            the flag determines if finalization on exit is enabled.
     * @deprecated This method is unsafe.
     */
    @SuppressWarnings("deprecation")
    @Deprecated
    public static void runFinalizersOnExit(boolean flag) {
        Runtime.runFinalizersOnExit(flag);
    }

    /**
     * Attempts to set all system properties. Copies all properties from
     * {@code p} and discards system properties that are read only and cannot
     * be modified. See {@link #getProperty} for a list of such properties.
     */
    public static void setProperties(Properties p) {
        PropertiesWithNonOverrideableDefaults userProperties =
                new PropertiesWithNonOverrideableDefaults(unchangeableSystemProperties);
        if (p != null) {
            userProperties.putAll(p);
        } else {
            // setProperties(null) is documented to restore defaults.
            setDefaultChangeableProperties(userProperties);
        }

        systemProperties = userProperties;
    }

    /**
     * Throws {@code SecurityException}.
     *
     * <p>Security managers do <i>not</i> provide a secure environment for
     * executing untrusted code and are unsupported on Android. Untrusted code
     * cannot be safely isolated within a single VM on Android, so this method
     * <i>always</i> throws a {@code SecurityException}.
     *
     * @param sm a security manager
     * @throws SecurityException always
     */
    public static void setSecurityManager(SecurityManager sm) {
        if (sm != null) {
            throw new SecurityException();
        }
    }

    /**
     * Returns the platform specific file name format for the shared library
     * named by the argument. On Android, this would turn {@code "MyLibrary"} into
     * {@code "libMyLibrary.so"}.
     */
    public static String mapLibraryName(String nickname) {
        if (nickname == null) {
            throw new NullPointerException("nickname == null");
        }
        return "lib" + nickname + ".so";
    }

    /**
     * Used to set System.err, System.in, and System.out.
     */
    private static native void setFieldImpl(String field, String signature, Object stream);

    /**
     * A properties class that prohibits changes to any of the properties
     * contained in its defaults.
     */
    static final class PropertiesWithNonOverrideableDefaults extends Properties {
        PropertiesWithNonOverrideableDefaults(Properties defaults) {
            super(defaults);
        }

        @Override
        public Object put(Object key, Object value) {
            if (defaults.containsKey(key)) {
                logE("Ignoring attempt to set property \"" + key +
                        "\" to value \"" + value + "\".");
                return defaults.get(key);
            }

            return super.put(key, value);
        }

        @Override
        public Object remove(Object key) {
            if (defaults.containsKey(key)) {
                logE("Ignoring attempt to remove property \"" + key + "\".");
                return null;
            }

            return super.remove(key);
        }
    }

    /**
     * The unmodifiable environment variables map. System.getenv() specifies
     * that this map must throw when passed non-String keys.
     */
    static class SystemEnvironment extends AbstractMap<String, String> {
        private final Map<String, String> map;

        public SystemEnvironment(Map<String, String> map) {
            this.map = Collections.unmodifiableMap(map);
        }

        @Override public Set<Entry<String, String>> entrySet() {
            return map.entrySet();
        }

        @Override public String get(Object key) {
            return map.get(toNonNullString(key));
        }

        @Override public boolean containsKey(Object key) {
            return map.containsKey(toNonNullString(key));
        }

        @Override public boolean containsValue(Object value) {
            return map.containsValue(toNonNullString(value));
        }

        private String toNonNullString(Object o) {
            if (o == null) {
                throw new NullPointerException("o == null");
            }
            return (String) o;
        }
    }
}

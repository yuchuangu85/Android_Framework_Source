/*
 * Copyright (c) 2008, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.invoke;

import static java.lang.invoke.MethodHandleStatics.UNSAFE;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jdk.internal.vm.annotation.ForceInline;

// Android-changed: currently only 4 methods and the Holder class are needed, hence not importing
// entire file.
/**
 * The flavor of method handle which implements a constant reference
 * to a class member.
 * @author jrose
 */
class DirectMethodHandle {

    @ForceInline
    /*non-public*/
    static long fieldOffset(Object accessorObj) {
        // Note: We return a long because that is what Unsafe.getObject likes.
        // We store a plain int because it is more compact.
        // Android-changed: there is only MethodHandleImpl.
        // return ((Accessor)accessorObj).fieldOffset;
        return ((MethodHandleImpl) accessorObj).field.getOffset();
    }

    @ForceInline
    /*non-public*/
    static Object checkBase(Object obj) {
        // Note that the object's class has already been verified,
        // since the parameter type of the Accessor method handle
        // is either member.getDeclaringClass or a subclass.
        // This was verified in DirectMethodHandle.make.
        // Therefore, the only remaining check is for null.
        // Since this check is *not* guaranteed by Unsafe.getInt
        // and its siblings, we need to make an explicit one here.
        return Objects.requireNonNull(obj);
    }


    @ForceInline
    /*non-public*/
    static Object staticBase(Object accessorObj) {
        // Android-changed: there is only MethodHandleImpl.
        // return ((StaticAccessor)accessorObj).staticBase;
        return ((MethodHandleImpl) accessorObj).field.getDeclaringClass();
    }

    @ForceInline
    /*non-public*/
    static long staticOffset(Object accessorObj) {
        // Android-changed: there is only MethodHandleImpl.
        // return ((StaticAccessor)accessorObj).staticOffset;
        return ((MethodHandleImpl) accessorObj).field.getOffset();
    }

    // BEGIN Android-added: different mechanism to tie actual implementation to a MethodHandle.
    static Method getImplementation(String name, List<Class<?>> parameters) {
        return ACCESSOR_IMPLEMENTATIONS.get(new MethodKey(name, parameters));
    }

    private static final Map<MethodKey, Method> ACCESSOR_IMPLEMENTATIONS;

    static {
        UNSAFE.ensureClassInitialized(Holder.class);

        // 4 access kinds, 9 basic types and fields can be volatile or non-volatile.
        HashMap<MethodKey, Method> accessorMethods = HashMap.newHashMap(4 * 9 * 2);

        for (Method m : Holder.class.getDeclaredMethods()) {
            accessorMethods.put(
                    new MethodKey(m.getName(), Arrays.asList(m.getParameterTypes())), m);
        }

        ACCESSOR_IMPLEMENTATIONS = Collections.unmodifiableMap(accessorMethods);
    }

    private static final class MethodKey {
        private final String name;
        private final List<Class<?>> arguments;

        MethodKey(String name, List<Class<?>> arguments) {
            this.name = Objects.requireNonNull(name);
            this.arguments = Objects.requireNonNull(arguments);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + arguments.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof MethodKey methodKey) {
                return name.equals(methodKey.name) && arguments.equals(methodKey.arguments);
            }

            return false;
        }
    }
    // END Android-added: different mechanism to tie actual implementation to a MethodHandle.

    // Android-changed: upstream inserts implementation at the link time (straight to bytecode, w/o
    // compilation).
    // Do not change this class manually: check AccessorMethodHandlesGenerator.
    /* Placeholder class for DirectMethodHandles generated ahead of time */
    static final class Holder {
        static void putBoolean(Object base, boolean value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putBoolean(base, offset, value);
        }

        static void putBooleanVolatile(Object base, boolean value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putBooleanVolatile(base, offset, value);
        }

        static void putByte(Object base, byte value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putByte(base, offset, value);
        }

        static void putByteVolatile(Object base, byte value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putByteVolatile(base, offset, value);
        }

        static void putChar(Object base, char value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putChar(base, offset, value);
        }

        static void putCharVolatile(Object base, char value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putCharVolatile(base, offset, value);
        }

        static void putShort(Object base, short value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putShort(base, offset, value);
        }

        static void putShortVolatile(Object base, short value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putShortVolatile(base, offset, value);
        }

        static void putInt(Object base, int value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putInt(base, offset, value);
        }

        static void putIntVolatile(Object base, int value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putIntVolatile(base, offset, value);
        }

        static void putLong(Object base, long value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putLong(base, offset, value);
        }

        static void putLongVolatile(Object base, long value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putLongVolatile(base, offset, value);
        }

        static void putDouble(Object base, double value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putDouble(base, offset, value);
        }

        static void putDoubleVolatile(Object base, double value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putDoubleVolatile(base, offset, value);
        }

        static void putFloat(Object base, float value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putFloat(base, offset, value);
        }

        static void putFloatVolatile(Object base, float value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putFloatVolatile(base, offset, value);
        }

        static void putReference(Object base, Object value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putReference(base, offset, value);
        }

        static void putReferenceVolatile(Object base, Object value, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            UNSAFE.putReferenceVolatile(base, offset, value);
        }

        static boolean getBoolean(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getBoolean(base, offset);
        }

        static boolean getBooleanVolatile(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getBooleanVolatile(base, offset);
        }

        static byte getByte(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getByte(base, offset);
        }

        static byte getByteVolatile(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getByteVolatile(base, offset);
        }

        static char getChar(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getChar(base, offset);
        }

        static char getCharVolatile(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getCharVolatile(base, offset);
        }

        static short getShort(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getShort(base, offset);
        }

        static short getShortVolatile(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getShortVolatile(base, offset);
        }

        static int getInt(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getInt(base, offset);
        }

        static int getIntVolatile(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getIntVolatile(base, offset);
        }

        static long getLong(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getLong(base, offset);
        }

        static long getLongVolatile(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getLongVolatile(base, offset);
        }

        static double getDouble(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getDouble(base, offset);
        }

        static double getDoubleVolatile(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getDoubleVolatile(base, offset);
        }

        static float getFloat(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getFloat(base, offset);
        }

        static float getFloatVolatile(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getFloatVolatile(base, offset);
        }

        static Object getReference(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getReference(base, offset);
        }

        static Object getReferenceVolatile(Object base, MethodHandleImpl mh) {
            checkBase(base);
            long offset = fieldOffset(mh);
            return UNSAFE.getReferenceVolatile(base, offset);
        }

        static void putBoolean(boolean value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putBoolean(base, offset, value);
        }

        static void putBooleanVolatile(boolean value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putBooleanVolatile(base, offset, value);
        }

        static void putByte(byte value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putByte(base, offset, value);
        }

        static void putByteVolatile(byte value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putByteVolatile(base, offset, value);
        }

        static void putChar(char value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putChar(base, offset, value);
        }

        static void putCharVolatile(char value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putCharVolatile(base, offset, value);
        }

        static void putShort(short value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putShort(base, offset, value);
        }

        static void putShortVolatile(short value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putShortVolatile(base, offset, value);
        }

        static void putInt(int value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putInt(base, offset, value);
        }

        static void putIntVolatile(int value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putIntVolatile(base, offset, value);
        }

        static void putLong(long value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putLong(base, offset, value);
        }

        static void putLongVolatile(long value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putLongVolatile(base, offset, value);
        }

        static void putDouble(double value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putDouble(base, offset, value);
        }

        static void putDoubleVolatile(double value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putDoubleVolatile(base, offset, value);
        }

        static void putFloat(float value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putFloat(base, offset, value);
        }

        static void putFloatVolatile(float value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putFloatVolatile(base, offset, value);
        }

        static void putReference(Object value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putReference(base, offset, value);
        }

        static void putReferenceVolatile(Object value, MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            UNSAFE.putReferenceVolatile(base, offset, value);
        }

        static boolean getBoolean(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getBoolean(base, offset);
        }

        static boolean getBooleanVolatile(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getBooleanVolatile(base, offset);
        }

        static byte getByte(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getByte(base, offset);
        }

        static byte getByteVolatile(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getByteVolatile(base, offset);
        }

        static char getChar(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getChar(base, offset);
        }

        static char getCharVolatile(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getCharVolatile(base, offset);
        }

        static short getShort(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getShort(base, offset);
        }

        static short getShortVolatile(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getShortVolatile(base, offset);
        }

        static int getInt(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getInt(base, offset);
        }

        static int getIntVolatile(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getIntVolatile(base, offset);
        }

        static long getLong(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getLong(base, offset);
        }

        static long getLongVolatile(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getLongVolatile(base, offset);
        }

        static double getDouble(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getDouble(base, offset);
        }

        static double getDoubleVolatile(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getDoubleVolatile(base, offset);
        }

        static float getFloat(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getFloat(base, offset);
        }

        static float getFloatVolatile(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getFloatVolatile(base, offset);
        }

        static Object getReference(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getReference(base, offset);
        }

        static Object getReferenceVolatile(MethodHandleImpl mh) {
            Object base = staticBase(mh);
            long offset = staticOffset(mh);
            return UNSAFE.getReferenceVolatile(base, offset);
        }
    }
}

/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The Android Open Source
 * Project designates this particular file as subject to the "Classpath"
 * exception as provided by The Android Open Source Project in the LICENSE
 * file that accompanied this code.
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
 */

package java.lang.invoke;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

// Android-changed: Android specific implementation.
// The whole class was implemented from scratch for the Android runtime based
// on the specification of the MethodHandle class.
// The code does not originate from upstream OpenJDK.
/**
 * A method handle that's directly associated with an ArtField or an ArtMethod and
 * specifies no additional transformations.
 *
 * @hide
 */
public class MethodHandleImpl extends MethodHandle implements Cloneable {
    // TODO(b/297147201): create separate AccessorMethodHandle class and move target and field
    // into it.
    // Used by runtime only.
    private final long target;
    private Object targetClassOrMethodHandleInfo;
    Field field;

    MethodHandleImpl(long artFieldOrMethod, int handleKind, MethodType type) {
        super(artFieldOrMethod, handleKind, type);
        this.targetClassOrMethodHandleInfo = getMemberInternal().getDeclaringClass();
        this.target = 0;
    }

    MethodHandleImpl(Field field, int handleKind, MethodType type) {
        super(field.getArtField(), handleKind, type);
        // To make sure that we won't operate on uninitialized fields.
        // TODO (b/399619087): make initialization lazy.
        MethodHandleStatics.UNSAFE.ensureClassInitialized(field.getDeclaringClass());
        this.targetClassOrMethodHandleInfo = getMemberInternal().getDeclaringClass();
        this.field = field;
        this.target = resolveTarget(handleKind, field);
    }

    private static long resolveTarget(int handleKind, Field field) {
        StringBuilder name = new StringBuilder();

        if (handleKind == MethodHandle.SGET || handleKind == MethodHandle.IGET) {
            name.append("get");
        } else if (handleKind == MethodHandle.SPUT || handleKind == MethodHandle.IPUT) {
            name.append("put");
        } else {
            throw new AssertionError("Unexpected handleKind: " + handleKind);
        }

        Class<?> type = field.getType();

        if (type.isPrimitive()) {
            String fieldTypeName = type.getName();
            name.append(Character.toUpperCase(fieldTypeName.charAt(0)));
            name.append(fieldTypeName.substring(1));
        } else {
            name.append("Reference");
        }

        if (Modifier.isVolatile(field.getModifiers())) {
            name.append("Volatile");
        }

        List<Class<?>> signature = new ArrayList<>(3);
        if (!Modifier.isStatic(field.getModifiers())) {
            signature.add(Object.class);
        }
        if (handleKind == MethodHandle.SPUT || handleKind == MethodHandle.IPUT) {
            if (type.isPrimitive()) {
                signature.add(type);
            } else {
                signature.add(Object.class);
            }
        }
        signature.add(MethodHandleImpl.class);
        Method target = DirectMethodHandle.getImplementation(name.toString(), signature);
        if (target == null) {
            throw new InternalError("DirectMethodHandle$Holder is missing a method");
        }
        return target.getArtMethod();
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    MethodHandleInfo reveal() {
        if (!(targetClassOrMethodHandleInfo instanceof HandleInfo handleInfo)) {
            MethodHandleInfo info = new HandleInfo(getMemberInternal(), this);
            targetClassOrMethodHandleInfo = info;
            return info;
        }

        return handleInfo;
    }

    /**
     * Materialize a member from this method handle's ArtField or ArtMethod pointer.
     */
    public native Member getMemberInternal();

    /**
     * Implementation of {@code MethodHandleInfo} in terms of the handle being cracked
     * and its corresponding {@code java.lang.reflect.Member}.
     */
    static class HandleInfo implements MethodHandleInfo {
        private final Member member;
        private final MethodHandle handle;

        HandleInfo(Member member, MethodHandle handle) {
            this.member = member;
            this.handle = handle;
        }

        @Override
        public int getReferenceKind() {
            switch (handle.getHandleKind()) {
                case INVOKE_VIRTUAL: {
                    if (member.getDeclaringClass().isInterface()) {
                        return REF_invokeInterface;
                    } else {
                        return REF_invokeVirtual;
                    }
                }

                case INVOKE_DIRECT: {
                    if (member instanceof Constructor) {
                        return REF_newInvokeSpecial;
                    } else {
                        return REF_invokeSpecial;
                    }
                }

                case INVOKE_SUPER:
                    return MethodHandleInfo.REF_invokeSpecial;
                case INVOKE_STATIC:
                    return MethodHandleInfo.REF_invokeStatic;
                case IGET:
                    return MethodHandleInfo.REF_getField;
                case IPUT:
                    return MethodHandleInfo.REF_putField;
                case SGET:
                    return MethodHandleInfo.REF_getStatic;
                case SPUT:
                    return MethodHandleInfo.REF_putStatic;
                default:
                    throw new AssertionError("Unexpected handle kind: " + handle.getHandleKind());
            }
        }

        @Override
        public Class<?> getDeclaringClass() {
            return member.getDeclaringClass();
        }

        @Override
        public String getName() {
            if (member instanceof Constructor) {
                return "<init>";
            }

            return member.getName();
        }

        @Override
        public MethodType getMethodType() {
            // The "nominal" type of a cracked method handle is the same as the type
            // of the handle itself, except in the cases enumerated below.
            MethodType handleType = handle.type();

            boolean omitLeadingParam = false;

            // For constructs, the return type is always void.class, and not the type of
            // the object returned. We also need to omit the leading reference, which is
            // nominally the type of the object being constructed.
            if (member instanceof Constructor) {
                handleType = handleType.changeReturnType(void.class);
                omitLeadingParam = true;
            }

            // For instance field gets/puts and instance method gets/puts, we omit the
            // leading reference parameter to |this|.
            switch (handle.getHandleKind()) {
                case IGET:
                case IPUT:
                case INVOKE_INTERFACE:
                case INVOKE_DIRECT:
                case INVOKE_VIRTUAL:
                case INVOKE_SUPER:
                    omitLeadingParam = true;
            }

            return omitLeadingParam ? handleType.dropParameterTypes(0, 1) : handleType;
        }

        @Override
        public <T extends Member> T reflectAs(Class<T> expected, MethodHandles.Lookup lookup) {
            try {
                final Class declaringClass = member.getDeclaringClass();
                if (Modifier.isNative(getModifiers()) &&
                        (MethodHandle.class.isAssignableFrom(declaringClass)
                                || VarHandle.class.isAssignableFrom(declaringClass))) {
                    if (member instanceof Method) {
                        Method m = (Method) member;
                        if (m.isVarArgs()) {
                            // Signature-polymorphic methods should not be reflected as there
                            // is no support for invoking them via reflection.
                            //
                            // We've identified this method as signature-polymorphic due to
                            // its flags (var-args and native) and its class.
                            throw new IllegalArgumentException(
                                    "Reflecting signature polymorphic method");
                        }
                    }
                }
                lookup.checkAccess(
                        declaringClass, declaringClass, member.getModifiers(), member.getName());
            } catch (IllegalAccessException exception) {
                throw new IllegalArgumentException("Unable to access member.", exception);
            }

            return (T) member;
        }

        @Override
        public int getModifiers() {
            return member.getModifiers();
        }

        @Override
        public String toString() {
            return MethodHandleInfo.toString(
                    getReferenceKind(), getDeclaringClass(), getName(), getMethodType());
        }
    }
}

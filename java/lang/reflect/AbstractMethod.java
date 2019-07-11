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
 * Copyright (C) 2012 The Android Open Source Project
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

package java.lang.reflect;

import com.android.dex.Dex;
import java.lang.annotation.Annotation;
import java.util.List;
import libcore.reflect.AnnotationAccess;
import libcore.reflect.GenericSignatureParser;
import libcore.reflect.ListOfTypes;
import libcore.reflect.Types;

/**
 * This class represents an abstract method. Abstract methods are either methods or constructors.
 * @hide
 */
public abstract class AbstractMethod extends AccessibleObject {

    /**
     * Hidden to workaround b/16828157.
     * @hide
     */
    protected final ArtMethod artMethod;

    /**
     * Hidden to workaround b/16828157.
     * @hide
     */
    protected AbstractMethod(ArtMethod artMethod) {
        if (artMethod == null) {
            throw new NullPointerException("artMethod == null");
        }
        this.artMethod = artMethod;
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return super.getAnnotation(annotationClass);
    }

    /**
     * We insert native method stubs for abstract methods so we don't have to
     * check the access flags at the time of the method call.  This results in
     * "native abstract" methods, which can't exist.  If we see the "abstract"
     * flag set, clear the "native" flag.
     *
     * We also move the DECLARED_SYNCHRONIZED flag into the SYNCHRONIZED
     * position, because the callers of this function are trying to convey
     * the "traditional" meaning of the flags to their callers.
     */
    private static int fixMethodFlags(int flags) {
        if ((flags & Modifier.ABSTRACT) != 0) {
            flags &= ~Modifier.NATIVE;
        }
        flags &= ~Modifier.SYNCHRONIZED;
        int ACC_DECLARED_SYNCHRONIZED = 0x00020000;
        if ((flags & ACC_DECLARED_SYNCHRONIZED) != 0) {
            flags |= Modifier.SYNCHRONIZED;
        }
        return flags & 0xffff;  // mask out bits not used by Java
    }

    int getModifiers() {
        return fixMethodFlags(artMethod.getAccessFlags());
    }

    boolean isVarArgs() {
        return (artMethod.getAccessFlags() & Modifier.VARARGS) != 0;
    }

    boolean isBridge() {
        return (artMethod.getAccessFlags() & Modifier.BRIDGE) != 0;
    }

    boolean isSynthetic() {
        return (artMethod.getAccessFlags() & Modifier.SYNTHETIC) != 0;
    }

    /**
     * @hide
     */
    public final int getAccessFlags() {
        return artMethod.getAccessFlags();
    }

    /**
     * Returns the class that declares this constructor or method.
     */
    Class<?> getDeclaringClass() {
        return artMethod.getDeclaringClass();
    }

    /**
     * Returns the index of this method's ID in its dex file.
     *
     * @hide
     */
    public final int getDexMethodIndex() {
        return artMethod.getDexMethodIndex();
    }

    /**
     * Returns the name of the method or constructor represented by this
     * instance.
     *
     * @return the name of this method
     */
    abstract public String getName();

    /**
     * Returns an array of {@code Class} objects associated with the parameter types of this
     * abstract method. If the method was declared with no parameters, an
     * empty array will be returned.
     *
     * @return the parameter types
     */
    Class<?>[] getParameterTypes() {
        return artMethod.getParameterTypes();
    }

    /**
     * Returns true if {@code other} has the same declaring class, name,
     * parameters and return type as this method.
     */
    @Override public boolean equals(Object other) {
        if (!(other instanceof AbstractMethod)) {
            return false;
        }
        // exactly one instance of each member in this runtime
        return this.artMethod == ((AbstractMethod) other).artMethod;
    }

    String toGenericString() {
        return toGenericStringHelper();
    }

    Type[] getGenericParameterTypes() {
        return Types.getTypeArray(getMethodOrConstructorGenericInfo().genericParameterTypes, false);
    }

    Type[] getGenericExceptionTypes() {
        return Types.getTypeArray(getMethodOrConstructorGenericInfo().genericExceptionTypes, false);
    }

    @Override public Annotation[] getDeclaredAnnotations() {
        List<Annotation> result = AnnotationAccess.getDeclaredAnnotations(this);
        return result.toArray(new Annotation[result.size()]);
    }

    @Override public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            throw new NullPointerException("annotationType == null");
        }
        return AnnotationAccess.isDeclaredAnnotationPresent(this, annotationType);
    }

    public Annotation[] getAnnotations() {
        return super.getAnnotations();
    }

    /**
     * Returns an array of arrays that represent the annotations of the formal
     * parameters of this method. If there are no parameters on this method,
     * then an empty array is returned. If there are no annotations set, then
     * and array of empty arrays is returned.
     *
     * @return an array of arrays of {@code Annotation} instances
     */
    public abstract Annotation[][] getParameterAnnotations();

    /**
     * Returns the constructor's signature in non-printable form. This is called
     * (only) from IO native code and needed for deriving the serialVersionUID
     * of the class
     *
     * @return The constructor's signature.
     */
    @SuppressWarnings("unused")
    abstract String getSignature();

    static final class GenericInfo {
        final ListOfTypes genericExceptionTypes;
        final ListOfTypes genericParameterTypes;
        final Type genericReturnType;
        final TypeVariable<?>[] formalTypeParameters;

        GenericInfo(ListOfTypes exceptions, ListOfTypes parameters, Type ret,
                    TypeVariable<?>[] formal) {
            genericExceptionTypes = exceptions;
            genericParameterTypes = parameters;
            genericReturnType = ret;
            formalTypeParameters = formal;
        }
    }

    /**
     * Returns generic information associated with this method/constructor member.
     */
    final GenericInfo getMethodOrConstructorGenericInfo() {
        String signatureAttribute = AnnotationAccess.getSignature(this);
        Member member;
        Class<?>[] exceptionTypes;
        boolean method = this instanceof Method;
        if (method) {
            Method m = (Method) this;
            member = m;
            exceptionTypes = m.getExceptionTypes();
        } else {
            Constructor<?> c = (Constructor<?>) this;
            member = c;
            exceptionTypes = c.getExceptionTypes();
        }
        GenericSignatureParser parser =
            new GenericSignatureParser(member.getDeclaringClass().getClassLoader());
        if (method) {
            parser.parseForMethod((GenericDeclaration) this, signatureAttribute, exceptionTypes);
        } else {
            parser.parseForConstructor((GenericDeclaration) this,
                                       signatureAttribute,
                                       exceptionTypes);
        }
        return new GenericInfo(parser.exceptionTypes, parser.parameterTypes,
                               parser.returnType, parser.formalTypeParameters);
    }

    /**
     * Helper for Method and Constructor for toGenericString
     */
    final String toGenericStringHelper() {
        StringBuilder sb = new StringBuilder(80);
        GenericInfo info =  getMethodOrConstructorGenericInfo();
        int modifiers = ((Member)this).getModifiers();
        // append modifiers if any
        if (modifiers != 0) {
            sb.append(Modifier.toString(modifiers & ~Modifier.VARARGS)).append(' ');
        }
        // append type parameters
        if (info.formalTypeParameters != null && info.formalTypeParameters.length > 0) {
            sb.append('<');
            for (int i = 0; i < info.formalTypeParameters.length; i++) {
                Types.appendGenericType(sb, info.formalTypeParameters[i]);
                if (i < info.formalTypeParameters.length - 1) {
                    sb.append(",");
                }
            }
            sb.append("> ");
        }
        Class<?> declaringClass = ((Member) this).getDeclaringClass();
        if (this instanceof Constructor) {
            // append constructor name
            Types.appendTypeName(sb, declaringClass);
        } else {
            // append return type
            Types.appendGenericType(sb, Types.getType(info.genericReturnType));
            sb.append(' ');
            // append method name
            Types.appendTypeName(sb, declaringClass);
            sb.append(".").append(((Method) this).getName());
        }
        // append parameters
        sb.append('(');
        Types.appendArrayGenericType(sb, info.genericParameterTypes.getResolvedTypes());
        sb.append(')');
        // append exceptions if any
        Type[] genericExceptionTypeArray =
            Types.getTypeArray(info.genericExceptionTypes, false);
        if (genericExceptionTypeArray.length > 0) {
            sb.append(" throws ");
            Types.appendArrayGenericType(sb, genericExceptionTypeArray);
        }
        return sb.toString();
    }
}

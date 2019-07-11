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

package java.lang.reflect;

import com.android.dex.Dex;
import java.lang.annotation.Annotation;
import java.util.Comparator;
import java.util.List;
import libcore.reflect.AnnotationAccess;
import libcore.reflect.Types;

/**
 * This class represents a constructor. Information about the constructor can be
 * accessed, and the constructor can be invoked dynamically.
 *
 * @param <T> the class that declares this constructor
 */
public final class Constructor<T> extends AbstractMethod implements GenericDeclaration, Member {

    private static final Comparator<Method> ORDER_BY_SIGNATURE = null; // Unused; must match Method.

    /**
     * @hide
     */
    public Constructor(ArtMethod artMethod) {
        super(artMethod);
    }

    public Annotation[] getAnnotations() {
        return super.getAnnotations();
    }

    /**
     * Returns the modifiers for this constructor. The {@link Modifier} class
     * should be used to decode the result.
     */
    @Override public int getModifiers() {
        return super.getModifiers();
    }

    /**
     * Returns true if this constructor takes a variable number of arguments.
     */
    public boolean isVarArgs() {
        return super.isVarArgs();
    }

    /**
     * Returns true if this constructor is synthetic (artificially introduced by the compiler).
     */
    @Override public boolean isSynthetic() {
        return super.isSynthetic();
    }

    /**
     * Returns the name of this constructor.
     */
    @Override public String getName() {
        return getDeclaringClass().getName();
    }

    /**
     * Returns the class that declares this constructor.
     */
    @Override public Class<T> getDeclaringClass() {
        return (Class<T>) super.getDeclaringClass();
    }

    /**
     * Returns the exception types as an array of {@code Class} instances. If
     * this constructor has no declared exceptions, an empty array will be
     * returned.
     */
    public Class<?>[] getExceptionTypes() {
        // TODO: use dex cache to speed looking up class
        return AnnotationAccess.getExceptions(this);
    }

    /**
     * Returns an array of the {@code Class} objects associated with the
     * parameter types of this constructor. If the constructor was declared with
     * no parameters, an empty array will be returned.
     */
    @Override public Class<?>[] getParameterTypes() {
        return super.getParameterTypes();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Equivalent to {@code getDeclaringClass().getName().hashCode()}.
     */
    @Override public int hashCode() {
        return getDeclaringClass().getName().hashCode();
    }

    /**
     * Returns true if {@code other} has the same declaring class and parameters
     * as this constructor.
     */
    @Override public boolean equals(Object other) {
        return super.equals(other);
    }

    @Override public TypeVariable<Constructor<T>>[] getTypeParameters() {
        GenericInfo info = getMethodOrConstructorGenericInfo();
        return (TypeVariable<Constructor<T>>[]) info.formalTypeParameters.clone();
    }

    /**
     * Returns the string representation of the constructor's declaration,
     * including the type parameters.
     *
     * @return the string representation of the constructor's declaration
     */
    public String toGenericString() {
        return super.toGenericString();
    }

    /**
     * Returns the generic parameter types as an array of {@code Type}
     * instances, in declaration order. If this constructor has no generic
     * parameters, an empty array is returned.
     *
     * @return the parameter types
     *
     * @throws GenericSignatureFormatError
     *             if the generic constructor signature is invalid
     * @throws TypeNotPresentException
     *             if any parameter type points to a missing type
     * @throws MalformedParameterizedTypeException
     *             if any parameter type points to a type that cannot be
     *             instantiated for some reason
     */
    public Type[] getGenericParameterTypes() {
        return super.getGenericParameterTypes();
    }

    /**
     * Returns the exception types as an array of {@code Type} instances. If
     * this constructor has no declared exceptions, an empty array will be
     * returned.
     *
     * @return an array of generic exception types
     *
     * @throws GenericSignatureFormatError
     *             if the generic constructor signature is invalid
     * @throws TypeNotPresentException
     *             if any exception type points to a missing type
     * @throws MalformedParameterizedTypeException
     *             if any exception type points to a type that cannot be
     *             instantiated for some reason
     */
    public Type[] getGenericExceptionTypes() {
        return super.getGenericExceptionTypes();
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

    @Override public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        if (annotationType == null) {
            throw new NullPointerException("annotationType == null");
        }
        return AnnotationAccess.getDeclaredAnnotation(this, annotationType);
    }

    /**
     * Returns an array of arrays that represent the annotations of the formal
     * parameters of this constructor. If there are no parameters on this
     * constructor, then an empty array is returned. If there are no annotations
     * set, then an array of empty arrays is returned.
     *
     * @return an array of arrays of {@code Annotation} instances
     */
    public Annotation[][] getParameterAnnotations() {
        return artMethod.getParameterAnnotations();
    }

    /**
     * Returns the constructor's signature in non-printable form. This is called
     * (only) from IO native code and needed for deriving the serialVersionUID
     * of the class
     *
     * @return the constructor's signature
     */
    @SuppressWarnings("unused")
    String getSignature() {
        StringBuilder result = new StringBuilder();

        result.append('(');
        Class<?>[] parameterTypes = getParameterTypes();
        for (Class<?> parameterType : parameterTypes) {
            result.append(Types.getSignature(parameterType));
        }
        result.append(")V");

        return result.toString();
    }

    /**
     * Returns a new instance of the declaring class, initialized by dynamically
     * invoking the constructor represented by this {@code Constructor} object.
     * This reproduces the effect of {@code new declaringClass(arg1, arg2, ... ,
     * argN)} This method performs the following:
     * <ul>
     * <li>A new instance of the declaring class is created. If the declaring
     * class cannot be instantiated (i.e. abstract class, an interface, an array
     * type, or a primitive type) then an InstantiationException is thrown.</li>
     * <li>If this Constructor object is enforcing access control (see
     * {@link AccessibleObject}) and this constructor is not accessible from the
     * current context, an IllegalAccessException is thrown.</li>
     * <li>If the number of arguments passed and the number of parameters do not
     * match, an IllegalArgumentException is thrown.</li>
     * <li>For each argument passed:
     * <ul>
     * <li>If the corresponding parameter type is a primitive type, the argument
     * is unboxed. If the unboxing fails, an IllegalArgumentException is
     * thrown.</li>
     * <li>If the resulting argument cannot be converted to the parameter type
     * via a widening conversion, an IllegalArgumentException is thrown.</li>
     * </ul>
     * <li>The constructor represented by this {@code Constructor} object is
     * then invoked. If an exception is thrown during the invocation, it is
     * caught and wrapped in an InvocationTargetException. This exception is
     * then thrown. If the invocation completes normally, the newly initialized
     * object is returned.
     * </ul>
     *
     * @param args
     *            the arguments to the constructor
     *
     * @return the new, initialized, object
     *
     * @exception InstantiationException
     *                if the class cannot be instantiated
     * @exception IllegalAccessException
     *                if this constructor is not accessible
     * @exception IllegalArgumentException
     *                if an incorrect number of arguments are passed, or an
     *                argument could not be converted by a widening conversion
     * @exception InvocationTargetException
     *                if an exception was thrown by the invoked constructor
     *
     * @see AccessibleObject
     */
    public T newInstance(Object... args) throws InstantiationException,
            IllegalAccessException, IllegalArgumentException, InvocationTargetException {
      return newInstance(args, isAccessible());
    }

    /** @hide */
    public native T newInstance(Object[] args, boolean accessible) throws InstantiationException,
            IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    /**
     * Returns a string containing a concise, human-readable description of this
     * constructor. The format of the string is:
     *
     * <ol>
     *   <li>modifiers (if any)
     *   <li>declaring class name
     *   <li>'('
     *   <li>parameter types, separated by ',' (if any)
     *   <li>')'
     *   <li>'throws' plus exception types, separated by ',' (if any)
     * </ol>
     *
     * For example:
     * {@code public String(byte[],String) throws UnsupportedEncodingException}
     *
     * @return a printable representation for this constructor
     */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(Modifier.toString(getModifiers()));

        if (result.length() != 0) {
            result.append(' ');
        }
        result.append(getDeclaringClass().getName());
        result.append("(");
        Class<?>[] parameterTypes = getParameterTypes();
        result.append(Types.toString(parameterTypes));
        result.append(")");
        Class<?>[] exceptionTypes = getExceptionTypes();
        if (exceptionTypes.length > 0) {
            result.append(" throws ");
            result.append(Types.toString(exceptionTypes));
        }

        return result.toString();
    }
}

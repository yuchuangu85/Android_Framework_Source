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
 * This class represents a method. Information about the method can be accessed,
 * and the method can be invoked dynamically.
 */
public final class Method extends AbstractMethod implements GenericDeclaration, Member {

    /**
     * Orders methods by their name, parameters and return type.
     *
     * @hide
     */
    public static final Comparator<Method> ORDER_BY_SIGNATURE = new Comparator<Method>() {
        @Override public int compare(Method a, Method b) {
            if (a == b) {
                return 0;
            }
            int comparison = a.getName().compareTo(b.getName());
            if (comparison == 0) {
                comparison = a.compareParameters(b.getParameterTypes());
                if (comparison == 0) {
                    // This is necessary for methods that have covariant return types.
                    Class<?> aReturnType = a.getReturnType();
                    Class<?> bReturnType = b.getReturnType();
                    if (aReturnType == bReturnType) {
                        comparison = 0;
                    } else {
                        comparison = aReturnType.getName().compareTo(bReturnType.getName());
                    }
                }
            }
            return comparison;
        }
    };

    /**
     * @hide
     */
    private Method() {
    }

    public Annotation[] getAnnotations() {
        return super.getAnnotations();
    }

    /**
     * Returns the modifiers for this method. The {@link Modifier} class should
     * be used to decode the result.
     *
     * @return the modifiers for this method
     *
     * @see Modifier
     */
    @Override public int getModifiers() {
        return super.getModifiers();
    }

    /**
     * Indicates whether or not this method takes a variable number argument.
     *
     * @return {@code true} if a vararg is declared, {@code false} otherwise
     */
    public boolean isVarArgs() {
        return super.isVarArgs();
    }

    /**
     * Indicates whether or not this method is a bridge.
     *
     * @return {@code true} if this method is a bridge, {@code false} otherwise
     */
    public boolean isBridge() {
        return super.isBridge();

    }

    /**
     * Indicates whether or not this method is synthetic.
     *
     * @return {@code true} if this method is synthetic, {@code false} otherwise
     */
    @Override public boolean isSynthetic() {
        return super.isSynthetic();
    }

    /**
     * Returns the name of the method represented by this {@code Method}
     * instance.
     *
     * @return the name of this method
     */
    @Override public String getName() {
        Dex dex = declaringClassOfOverriddenMethod.getDex();
        int nameIndex = dex.nameIndexFromMethodIndex(dexMethodIndex);
        return declaringClassOfOverriddenMethod.getDexCacheString(dex, nameIndex);
    }

    /**
     * Returns the class that declares this method.
     */
    @Override public Class<?> getDeclaringClass() {
        return super.getDeclaringClass();
    }

    /**
     * Returns the exception types as an array of {@code Class} instances. If
     * this method has no declared exceptions, an empty array is returned.
     *
     * @return the declared exception classes
     */
    public Class<?>[] getExceptionTypes() {
        if (getDeclaringClass().isProxy()) {
            return getExceptionTypesNative();
        } else {
            // TODO: use dex cache to speed looking up class
            return AnnotationAccess.getExceptions(this);
        }
    }

    private native Class<?>[] getExceptionTypesNative();

    /**
     * Returns an array of {@code Class} objects associated with the parameter
     * types of this method. If the method was declared with no parameters, an
     * empty array will be returned.
     *
     * @return the parameter types
     */
    @Override public Class<?>[] getParameterTypes() {
        return super.getParameterTypes();
    }

    /**
     * Returns the {@code Class} associated with the return type of this
     * method.
     *
     * @return the return type
     */
    public Class<?> getReturnType() {
        Dex dex = declaringClassOfOverriddenMethod.getDex();
        int returnTypeIndex = dex.returnTypeIndexFromMethodIndex(dexMethodIndex);
        // Note, in the case of a Proxy the dex cache types are equal.
        return declaringClassOfOverriddenMethod.getDexCacheType(dex, returnTypeIndex);
    }


    /**
     * {@inheritDoc}
     *
     * <p>Equivalent to {@code getDeclaringClass().getName().hashCode() ^ getName().hashCode()}.
     */
    @Override public int hashCode() {
        return getDeclaringClass().getName().hashCode() ^ getName().hashCode();
    }

    /**
     * Returns true if {@code other} has the same declaring class, name,
     * parameters and return type as this method.
     */
    @Override public boolean equals(Object other) {
        return super.equals(other);
    }

    /**
     * Returns true if this and {@code method} have the same name and the same
     * parameters in the same order. Such methods can share implementation if
     * one method's return types is assignable to the other.
     *
     * @hide needed by Proxy
     */
    boolean equalNameAndParameters(Method m) {
        return getName().equals(m.getName()) && equalMethodParameters(m.getParameterTypes());
    }

    /**
     * Returns the string representation of the method's declaration, including
     * the type parameters.
     *
     * @return the string representation of this method
     */
    public String toGenericString() {
        return super.toGenericString();
    }

    @Override public TypeVariable<Method>[] getTypeParameters() {
        GenericInfo info = getMethodOrConstructorGenericInfo();
        return (TypeVariable<Method>[]) info.formalTypeParameters.clone();
    }

    /**
     * Returns the parameter types as an array of {@code Type} instances, in
     * declaration order. If this method has no parameters, an empty array is
     * returned.
     *
     * @return the parameter types
     *
     * @throws GenericSignatureFormatError
     *             if the generic method signature is invalid
     * @throws TypeNotPresentException
     *             if any parameter type points to a missing type
     * @throws MalformedParameterizedTypeException
     *             if any parameter type points to a type that cannot be
     *             instantiated for some reason
     */
    public Type[] getGenericParameterTypes() {
        return Types.getTypeArray(getMethodOrConstructorGenericInfo().genericParameterTypes, false);
    }

    @Override public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            throw new NullPointerException("annotationType == null");
        }
        return AnnotationAccess.isDeclaredAnnotationPresent(this, annotationType);
    }

    /**
     * Returns the exception types as an array of {@code Type} instances. If
     * this method has no declared exceptions, an empty array will be returned.
     *
     * @return an array of generic exception types
     *
     * @throws GenericSignatureFormatError
     *             if the generic method signature is invalid
     * @throws TypeNotPresentException
     *             if any exception type points to a missing type
     * @throws MalformedParameterizedTypeException
     *             if any exception type points to a type that cannot be
     *             instantiated for some reason
     */
    public Type[] getGenericExceptionTypes() {
        return Types.getTypeArray(getMethodOrConstructorGenericInfo().genericExceptionTypes, false);
    }

    /**
     * Returns the return type of this method as a {@code Type} instance.
     *
     * @return the return type of this method
     *
     * @throws GenericSignatureFormatError
     *             if the generic method signature is invalid
     * @throws TypeNotPresentException
     *             if the return type points to a missing type
     * @throws MalformedParameterizedTypeException
     *             if the return type points to a type that cannot be
     *             instantiated for some reason
     */
    public Type getGenericReturnType() {
        return Types.getType(getMethodOrConstructorGenericInfo().genericReturnType);
    }

    @Override public Annotation[] getDeclaredAnnotations() {
        List<Annotation> result = AnnotationAccess.getDeclaredAnnotations(this);
        return result.toArray(new Annotation[result.size()]);
    }

    @Override public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        if (annotationType == null) {
            throw new NullPointerException("annotationType == null");
        }
        return AnnotationAccess.getDeclaredAnnotation(this, annotationType);
    }

    /**
     * Returns an array of arrays that represent the annotations of the formal
     * parameters of this method. If there are no parameters on this method,
     * then an empty array is returned. If there are no annotations set, then
     * and array of empty arrays is returned.
     *
     * @return an array of arrays of {@code Annotation} instances
     */
    public Annotation[][] getParameterAnnotations() {
        return AnnotationAccess.getParameterAnnotations(
            declaringClassOfOverriddenMethod, dexMethodIndex);
    }

    /**
     * Returns the default value for the annotation member represented by this
     * method.
     *
     * @return the default value, or {@code null} if none
     *
     * @throws TypeNotPresentException
     *             if this annotation member is of type {@code Class} and no
     *             definition can be found
     */
    public Object getDefaultValue() {
        return AnnotationAccess.getDefaultValue(this);
    }

    /**
     * Returns the result of dynamically invoking this method. Equivalent to
     * {@code receiver.methodName(arg1, arg2, ... , argN)}.
     *
     * <p>If the method is static, the receiver argument is ignored (and may be null).
     *
     * <p>If the method takes no arguments, you can pass {@code (Object[]) null} instead of
     * allocating an empty array.
     *
     * <p>If you're calling a varargs method, you need to pass an {@code Object[]} for the
     * varargs parameter: that conversion is usually done in {@code javac}, not the VM, and
     * the reflection machinery does not do this for you. (It couldn't, because it would be
     * ambiguous.)
     *
     * <p>Reflective method invocation follows the usual process for method lookup.
     *
     * <p>If an exception is thrown during the invocation it is caught and
     * wrapped in an InvocationTargetException. This exception is then thrown.
     *
     * <p>If the invocation completes normally, the return value itself is
     * returned. If the method is declared to return a primitive type, the
     * return value is boxed. If the return type is void, null is returned.
     *
     * @param receiver
     *            the object on which to call this method (or null for static methods)
     * @param args
     *            the arguments to the method
     * @return the result
     *
     * @throws NullPointerException
     *             if {@code receiver == null} for a non-static method
     * @throws IllegalAccessException
     *             if this method is not accessible (see {@link AccessibleObject})
     * @throws IllegalArgumentException
     *             if the number of arguments doesn't match the number of parameters, the receiver
     *             is incompatible with the declaring class, or an argument could not be unboxed
     *             or converted by a widening conversion to the corresponding parameter type
     * @throws InvocationTargetException
     *             if an exception was thrown by the invoked method
     */
    public native Object invoke(Object receiver, Object... args)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    /**
     * Returns a string containing a concise, human-readable description of this
     * method. The format of the string is:
     *
     * <ol>
     *   <li>modifiers (if any)
     *   <li>return type or 'void'
     *   <li>declaring class name
     *   <li>'('
     *   <li>parameter types, separated by ',' (if any)
     *   <li>')'
     *   <li>'throws' plus exception types, separated by ',' (if any)
     * </ol>
     *
     * For example: {@code public native Object
     * java.lang.Method.invoke(Object,Object) throws
     * IllegalAccessException,IllegalArgumentException
     * ,InvocationTargetException}
     *
     * @return a printable representation for this method
     */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(
                Modifier.getDeclarationMethodModifiers(getModifiers()));

        if (result.length() != 0) {
            result.append(' ');
        }
        result.append(getReturnType().getName());
        result.append(' ');
        result.append(getDeclaringClass().getName());
        result.append('.');
        result.append(getName());
        result.append("(");
        Class<?>[] parameterTypes = getParameterTypes();
        result.append(Types.toString(parameterTypes));
        result.append(")");
        Class<?>[] exceptionTypes = getExceptionTypes();
        if (exceptionTypes.length != 0) {
            result.append(" throws ");
            result.append(Types.toString(exceptionTypes));
        }
        return result.toString();
    }

    /**
     * Returns the constructor's signature in non-printable form. This is called
     * (only) from IO native code and needed for deriving the serialVersionUID
     * of the class
     *
     * @return The constructor's signature.
     */
    @SuppressWarnings("unused")
    String getSignature() {
        StringBuilder result = new StringBuilder();

        result.append('(');
        Class<?>[] parameterTypes = getParameterTypes();
        for (Class<?> parameterType : parameterTypes) {
            result.append(Types.getSignature(parameterType));
        }
        result.append(')');
        result.append(Types.getSignature(getReturnType()));

        return result.toString();
    }
}

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
import libcore.reflect.GenericSignatureParser;
import libcore.reflect.Types;

/**
 * This class represents a field. Information about the field can be accessed,
 * and the field's value can be accessed dynamically.
 */
public final class Field extends AccessibleObject implements Member {

    /**
     * Orders fields by their name and declaring class.
     *
     * @hide
     */
    public static final Comparator<Field> ORDER_BY_NAME_AND_DECLARING_CLASS
            = new Comparator<Field>() {
        @Override public int compare(Field a, Field b) {
            if (a == b) {
                return 0;
            }
            int comparison = a.getName().compareTo(b.getName());
            if (comparison != 0) {
                return comparison;
            }
            Class<?> aType = a.getDeclaringClass();
            Class<?> bType = b.getDeclaringClass();
            if (aType == bType) {
                return 0;
            } else {
                return aType.getName().compareTo(bType.getName());
            }
        }
    };

    private final ArtField artField;

    /**
     * @hide
     */
    public Field(ArtField artField) {
        if (artField == null) {
            throw new NullPointerException("artField == null");
        }
        this.artField = artField;
    }

    /**
     * Returns the modifiers for this field. The {@link Modifier} class should
     * be used to decode the result.
     *
     * @return the modifiers for this field
     * @see Modifier
     */
    @Override public int getModifiers() {
        return artField.getAccessFlags() & 0xffff;  // mask out bits not used by Java
    }

    /**
     * Indicates whether or not this field is an enumeration constant.
     *
     * @return {@code true} if this field is an enumeration constant, {@code
     *         false} otherwise
     */
    public boolean isEnumConstant() {
        return (artField.getAccessFlags() & Modifier.ENUM) != 0;
    }

    /**
     * Indicates whether or not this field is synthetic.
     *
     * @return {@code true} if this field is synthetic, {@code false} otherwise
     */
    @Override public boolean isSynthetic() {
        return (artField.getAccessFlags() & Modifier.SYNTHETIC) != 0;
    }

    /**
     * Returns the name of this field.
     *
     * @return the name of this field
     */
    @Override public String getName() {
        return artField.getName();
    }

    @Override public Class<?> getDeclaringClass() {
        return artField.getDeclaringClass();
    }

    /**
     * Return the {@link Class} associated with the type of this field.
     *
     * @return the type of this field
     */
    public Class<?> getType() {
        return artField.getType();
    }

    /**
     * Returns the index of this field's ID in its dex file.
     *
     * @hide
     */
    public int getDexFieldIndex() {
        return artField.getDexFieldIndex();
    }

    /**
     * Returns the offset of the field within an instance, or for static fields, the class.
     *
     * @hide
     */
    public int getOffset() {
        return artField.getOffset();
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
     * Returns true if {@code other} has the same declaring class, name and type
     * as this field.
     */
    @Override public boolean equals(Object other) {
        if (!(other instanceof Field)) {
            return false;
        }
        // exactly one instance of each member in this runtime
        return this.artField == ((Field) other).artField;
    }

    /**
     * Returns the string representation of this field, including the field's
     * generic type.
     *
     * @return the string representation of this field
     */
    public String toGenericString() {
        StringBuilder sb = new StringBuilder(80);
        // append modifiers if any
        int modifier = getModifiers();
        if (modifier != 0) {
            sb.append(Modifier.toString(modifier)).append(' ');
        }
        // append generic type
        Types.appendGenericType(sb, getGenericType());
        sb.append(' ');
        // append full field name
        sb.append(getDeclaringClass().getName()).append('.').append(getName());
        return sb.toString();
    }

    /**
     * Returns the generic type of this field.
     *
     * @return the generic type
     * @throws GenericSignatureFormatError
     *             if the generic field signature is invalid
     * @throws TypeNotPresentException
     *             if the generic type points to a missing type
     * @throws MalformedParameterizedTypeException
     *             if the generic type points to a type that cannot be
     *             instantiated for some reason
     */
    public Type getGenericType() {
        String signatureAttribute = AnnotationAccess.getSignature(this);
        Class<?> declaringClass = getDeclaringClass();
        ClassLoader cl = declaringClass.getClassLoader();
        GenericSignatureParser parser = new GenericSignatureParser(cl);
        parser.parseForField(declaringClass, signatureAttribute);
        Type genericType = parser.fieldType;
        if (genericType == null) {
            genericType = getType();
        }
        return genericType;
    }

    /**
     * Returns the constructor's signature in non-printable form. This is called
     * (only) from IO native code and needed for deriving the serialVersionUID
     * of the class
     */
    @SuppressWarnings("unused")
    private String getSignature() {
        return Types.getSignature(getType());
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

    @Override public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            throw new NullPointerException("annotationType == null");
        }
        return AnnotationAccess.isDeclaredAnnotationPresent(this, annotationType);
    }

    /**
     * Returns the value of the field in the specified object. This reproduces
     * the effect of {@code object.fieldName}
     *
     * <p>If the type of this field is a primitive type, the field value is
     * automatically boxed.
     *
     * <p>If this field is static, the object argument is ignored.
     * Otherwise, if the object is null, a NullPointerException is thrown. If
     * the object is not an instance of the declaring class of the method, an
     * IllegalArgumentException is thrown.
     *
     * <p>If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * @param object
     *            the object to access
     * @return the field value, possibly boxed
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public Object get(Object object) throws IllegalAccessException, IllegalArgumentException {
        return get(object, isAccessible());
    }

    private native Object get(Object object, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Returns the value of the field in the specified object as a {@code
     * boolean}. This reproduces the effect of {@code object.fieldName}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * @param object
     *            the object to access
     * @return the field value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public boolean getBoolean(Object object) throws IllegalAccessException,
                                                    IllegalArgumentException {
        return getBoolean(object, isAccessible());
    }

    private native boolean getBoolean(Object object, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Returns the value of the field in the specified object as a {@code byte}.
     * This reproduces the effect of {@code object.fieldName}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * @param object
     *            the object to access
     * @return the field value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public byte getByte(Object object) throws IllegalAccessException, IllegalArgumentException {
        return getByte(object, isAccessible());
    }

    private native byte getByte(Object object, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Returns the value of the field in the specified object as a {@code char}.
     * This reproduces the effect of {@code object.fieldName}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * @param object
     *            the object to access
     * @return the field value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public char getChar(Object object) throws IllegalAccessException, IllegalArgumentException {
        return getChar(object, isAccessible());
    }

    private native char getChar(Object object, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Returns the value of the field in the specified object as a {@code
     * double}. This reproduces the effect of {@code object.fieldName}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * @param object
     *            the object to access
     * @return the field value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public double getDouble(Object object) throws IllegalAccessException, IllegalArgumentException {
        return getDouble(object, isAccessible());
    }

    private native double getDouble(Object object, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Returns the value of the field in the specified object as a {@code float}
     * . This reproduces the effect of {@code object.fieldName}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * @param object
     *            the object to access
     * @return the field value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public float getFloat(Object object) throws IllegalAccessException, IllegalArgumentException {
        return getFloat(object, isAccessible());
    }

    private native float getFloat(Object object, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Returns the value of the field in the specified object as an {@code int}.
     * This reproduces the effect of {@code object.fieldName}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * @param object
     *            the object to access
     * @return the field value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public int getInt(Object object) throws IllegalAccessException, IllegalArgumentException {
        return getInt(object, isAccessible());
    }

    private native int getInt(Object object, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Returns the value of the field in the specified object as a {@code long}.
     * This reproduces the effect of {@code object.fieldName}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * @param object
     *            the object to access
     * @return the field value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public long getLong(Object object) throws IllegalAccessException, IllegalArgumentException {
        return getLong(object, isAccessible());
    }

    private native long getLong(Object object, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Returns the value of the field in the specified object as a {@code short}
     * . This reproduces the effect of {@code object.fieldName}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * @param object
     *            the object to access
     * @return the field value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public short getShort(Object object) throws IllegalAccessException, IllegalArgumentException {
        return getShort(object, isAccessible());
    }

    private native short getShort(Object object, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Sets the value of the field in the specified object to the value. This
     * reproduces the effect of {@code object.fieldName = value}
     *
     * <p>If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     *
     * <p>If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     *
     * <p>If the field type is a primitive type, the value is automatically
     * unboxed. If the unboxing fails, an IllegalArgumentException is thrown. If
     * the value cannot be converted to the field type via a widening
     * conversion, an IllegalArgumentException is thrown.
     *
     * @param object
     *            the object to access
     * @param value
     *            the new value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public void set(Object object, Object value) throws IllegalAccessException,
                                                        IllegalArgumentException {
        set(object, value, isAccessible());
    }

    private native void set(Object object, Object value, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Sets the value of the field in the specified object to the {@code
     * boolean} value. This reproduces the effect of {@code object.fieldName =
     * value}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     * <p>
     * If the value cannot be converted to the field type via a widening
     * conversion, an IllegalArgumentException is thrown.
     *
     * @param object
     *            the object to access
     * @param value
     *            the new value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public void setBoolean(Object object, boolean value) throws IllegalAccessException,
                                                                IllegalArgumentException {
        setBoolean(object, value, isAccessible());
    }

    private native void setBoolean(Object object, boolean value, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Sets the value of the field in the specified object to the {@code byte}
     * value. This reproduces the effect of {@code object.fieldName = value}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     * <p>
     * If the value cannot be converted to the field type via a widening
     * conversion, an IllegalArgumentException is thrown.
     *
     * @param object
     *            the object to access
     * @param value
     *            the new value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public void setByte(Object object, byte value) throws IllegalAccessException,
                                                          IllegalArgumentException {
        setByte(object, value, isAccessible());
    }

    private native void setByte(Object object, byte value, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Sets the value of the field in the specified object to the {@code char}
     * value. This reproduces the effect of {@code object.fieldName = value}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     * <p>
     * If the value cannot be converted to the field type via a widening
     * conversion, an IllegalArgumentException is thrown.
     *
     * @param object
     *            the object to access
     * @param value
     *            the new value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public void setChar(Object object, char value) throws IllegalAccessException,
                                                          IllegalArgumentException {
        setChar(object, value, isAccessible());
    }

    private native void setChar(Object object, char value, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Sets the value of the field in the specified object to the {@code double}
     * value. This reproduces the effect of {@code object.fieldName = value}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     * <p>
     * If the value cannot be converted to the field type via a widening
     * conversion, an IllegalArgumentException is thrown.
     *
     * @param object
     *            the object to access
     * @param value
     *            the new value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public void setDouble(Object object, double value) throws IllegalAccessException,
                                                              IllegalArgumentException {
        setDouble(object, value, isAccessible());
    }

    private native void setDouble(Object object, double value, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Sets the value of the field in the specified object to the {@code float}
     * value. This reproduces the effect of {@code object.fieldName = value}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     * <p>
     * If the value cannot be converted to the field type via a widening
     * conversion, an IllegalArgumentException is thrown.
     *
     * @param object
     *            the object to access
     * @param value
     *            the new value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public void setFloat(Object object, float value) throws IllegalAccessException,
                                                            IllegalArgumentException {
        setFloat(object, value, isAccessible());
    }

    private native void setFloat(Object object, float value, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Set the value of the field in the specified object to the {@code int}
     * value. This reproduces the effect of {@code object.fieldName = value}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     * <p>
     * If the value cannot be converted to the field type via a widening
     * conversion, an IllegalArgumentException is thrown.
     *
     * @param object
     *            the object to access
     * @param value
     *            the new value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public void setInt(Object object, int value) throws IllegalAccessException,
                                                        IllegalArgumentException {
        setInt(object, value, isAccessible());
    }

    private native void setInt(Object object, int value, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Sets the value of the field in the specified object to the {@code long}
     * value. This reproduces the effect of {@code object.fieldName = value}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     * <p>
     * If the value cannot be converted to the field type via a widening
     * conversion, an IllegalArgumentException is thrown.
     *
     * @param object
     *            the object to access
     * @param value
     *            the new value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public void setLong(Object object, long value) throws IllegalAccessException,
                                                          IllegalArgumentException {
        setLong(object, value, isAccessible());
    }

    private native void setLong(Object object, long value, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Sets the value of the field in the specified object to the {@code short}
     * value. This reproduces the effect of {@code object.fieldName = value}
     * <p>
     * If this field is static, the object argument is ignored.
     * Otherwise, if the object is {@code null}, a NullPointerException is
     * thrown. If the object is not an instance of the declaring class of the
     * method, an IllegalArgumentException is thrown.
     * <p>
     * If this Field object is enforcing access control (see AccessibleObject)
     * and this field is not accessible from the current context, an
     * IllegalAccessException is thrown.
     * <p>
     * If the value cannot be converted to the field type via a widening
     * conversion, an IllegalArgumentException is thrown.
     *
     * @param object
     *            the object to access
     * @param value
     *            the new value
     * @throws NullPointerException
     *             if the object is {@code null} and the field is non-static
     * @throws IllegalArgumentException
     *             if the object is not compatible with the declaring class
     * @throws IllegalAccessException
     *             if this field is not accessible
     */
    public void setShort(Object object, short value) throws IllegalAccessException,
                                                            IllegalArgumentException {
        setShort(object, value, isAccessible());
    }

    private native void setShort(Object object, short value, boolean accessible)
            throws IllegalAccessException, IllegalArgumentException;

    /**
     * Returns a string containing a concise, human-readable description of this
     * field.
     * <p>
     * The format of the string is:
     * <ol>
     *   <li>modifiers (if any)
     *   <li>type
     *   <li>declaring class name
     *   <li>'.'
     *   <li>field name
     * </ol>
     * <p>
     * For example: {@code public static java.io.InputStream
     * java.lang.System.in}
     *
     * @return a printable representation for this field
     */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(Modifier.toString(getModifiers()));
        if (result.length() != 0) {
            result.append(' ');
        }
        Types.appendTypeName(result, getType());
        result.append(' ');
        result.append(getDeclaringClass().getName());
        result.append('.');
        result.append(getName());
        return result.toString();
    }
}

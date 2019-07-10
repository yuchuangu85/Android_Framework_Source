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
 * Copyright (C) 2006-2007 The Android Open Source Project
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

import com.android.dex.Dex;
import dalvik.system.VMStack;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import libcore.reflect.AnnotationAccess;
import libcore.reflect.GenericSignatureParser;
import libcore.reflect.InternalNames;
import libcore.reflect.Types;
import libcore.util.BasicLruCache;
import libcore.util.CollectionUtils;
import libcore.util.EmptyArray;
import libcore.util.SneakyThrow;

/**
 * The in-memory representation of a Java class. This representation serves as
 * the starting point for querying class-related information, a process usually
 * called "reflection". There are basically three types of {@code Class}
 * instances: those representing real classes and interfaces, those representing
 * primitive types, and those representing array classes.
 *
 * <h4>Class instances representing object types (classes or interfaces)</h4>
 * <p>
 * These represent an ordinary class or interface as found in the class
 * hierarchy. The name associated with these {@code Class} instances is simply
 * the fully qualified class name of the class or interface that it represents.
 * In addition to this human-readable name, each class is also associated by a
 * so-called <em>descriptor</em>, which is the letter "L", followed by the
 * class name and a semicolon (";"). The descriptor is what the runtime system
 * uses internally for identifying the class (for example in a DEX file).
 * </p>
 * <h4>Classes representing primitive types</h4>
 * <p>
 * These represent the standard Java primitive types and hence share their
 * names (for example "int" for the {@code int} primitive type). Although it is
 * not possible to create new instances based on these {@code Class} instances,
 * they are still useful for providing reflection information, and as the
 * component type of array classes. There is one {@code Class} instance for each
 * primitive type, and their descriptors are:
 * </p>
 * <ul>
 * <li>{@code B} representing the {@code byte} primitive type</li>
 * <li>{@code S} representing the {@code short} primitive type</li>
 * <li>{@code I} representing the {@code int} primitive type</li>
 * <li>{@code J} representing the {@code long} primitive type</li>
 * <li>{@code F} representing the {@code float} primitive type</li>
 * <li>{@code D} representing the {@code double} primitive type</li>
 * <li>{@code C} representing the {@code char} primitive type</li>
 * <li>{@code Z} representing the {@code boolean} primitive type</li>
 * <li>{@code V} representing void function return values</li>
 * </ul>
 * <p>
 * <h4>Classes representing array classes</h4>
 * <p>
 * These represent the classes of Java arrays. There is one such {@code Class}
 * instance per combination of array leaf component type and arity (number of
 * dimensions). In this case, the name associated with the {@code Class}
 * consists of one or more left square brackets (one per dimension in the array)
 * followed by the descriptor of the class representing the leaf component type,
 * which can be either an object type or a primitive type. The descriptor of a
 * {@code Class} representing an array type is the same as its name. Examples
 * of array class descriptors are:
 * </p>
 * <ul>
 * <li>{@code [I} representing the {@code int[]} type</li>
 * <li>{@code [Ljava/lang/String;} representing the {@code String[]} type</li>
 * <li>{@code [[[C} representing the {@code char[][][]} type (three dimensions!)</li>
 * </ul>
 */
public final class Class<T> implements Serializable, AnnotatedElement, GenericDeclaration, Type {

    private static final long serialVersionUID = 3206093459760846163L;

    /** defining class loader, or null for the "bootstrap" system loader. */
    private transient ClassLoader classLoader;

    /**
     * For array classes, the component class object for instanceof/checkcast (for String[][][],
     * this will be String[][]). null for non-array classes.
     */
    private transient Class<?> componentType;
    /**
     * DexCache of resolved constant pool entries. Will be null for certain runtime-generated classes
     * e.g. arrays and primitive classes.
     */
    private transient DexCache dexCache;

    /** Short-cut to dexCache.strings */
    private transient String[] dexCacheStrings;

    /**
     * The interface table (iftable_) contains pairs of a interface class and an array of the
     * interface methods. There is one pair per interface supported by this class.  That
     * means one pair for each interface we support directly, indirectly via superclass, or
     * indirectly via a superinterface.  This will be null if neither we nor our superclass
     * implement any interfaces.
     *
     * Why we need this: given "class Foo implements Face", declare "Face faceObj = new Foo()".
     * Invoke faceObj.blah(), where "blah" is part of the Face interface.  We can't easily use a
     * single vtable.
     *
     * For every interface a concrete class implements, we create an array of the concrete vtable_
     * methods for the methods in the interface.
     */
    private transient Object[] ifTable;

    /** Lazily computed name of this class; always prefer calling getName(). */
    private transient String name;

    /** The superclass, or null if this is java.lang.Object, an interface or primitive type. */
    private transient Class<? super T> superClass;

    /** If class verify fails, we must return same error on subsequent tries. */
    private transient Class<?> verifyErrorClass;

    /**
     * Virtual method table (vtable), for use by "invoke-virtual". The vtable from the superclass
     * is copied in, and virtual methods from our class either replace those from the super or are
     * appended. For abstract classes, methods may be created in the vtable that aren't in
     * virtual_ methods_ for miranda methods.
     */
    private transient Object vtable;

    /** access flags; low 16 bits are defined by VM spec */
    private transient int accessFlags;

    /** static, private, and &lt;init&gt; methods. */
    private transient long directMethods;

    /**
     * Instance fields. These describe the layout of the contents of an Object. Note that only the
     * fields directly declared by this class are listed in iFields; fields declared by a
     * superclass are listed in the superclass's Class.iFields.
     *
     * All instance fields that refer to objects are guaranteed to be at the beginning of the field
     * list.  {@link Class#numReferenceInstanceFields} specifies the number of reference fields.
     */
    private transient long iFields;

    /** Static fields */
    private transient long sFields;

    /** Virtual methods defined in this class; invoked through vtable. */
    private transient long virtualMethods;

    /**
     * Total size of the Class instance; used when allocating storage on GC heap.
     * See also {@link Class#objectSize}.
     */
    private transient int classSize;

    /**
     * tid used to check for recursive static initializer invocation.
     */
    private transient int clinitThreadId;

    /**
     * Class def index from dex file. An index of 65535 indicates that there is no class definition,
     * for example for an array type.
     * TODO: really 16bits as type indices are 16bit.
     */
    private transient int dexClassDefIndex;

    /**
     * Class type index from dex file, lazily computed. An index of 65535 indicates that the type
     * index isn't known. Volatile to avoid double-checked locking bugs.
     * TODO: really 16bits as type indices are 16bit.
     */
    private transient volatile int dexTypeIndex;

    /** Number of direct methods. */
    private transient int numDirectMethods;

    /** Number of instance fields. */
    private transient int numInstanceFields;

    /** Number of instance fields that are object references. */
    private transient int numReferenceInstanceFields;

    /** Number of static fields that are object references. */
    private transient int numReferenceStaticFields;

    /** Number of static fields. */
    private transient int numStaticFields;

    /** Number of virtual methods. */
    private transient int numVirtualMethods;

    /**
     * Total object size; used when allocating storage on GC heap. For interfaces and abstract
     * classes this will be zero. See also {@link Class#classSize}.
     */
    private transient int objectSize;

    /**
     * The lower 16 bits is the primitive type value, or 0 if not a primitive type; set for
     * generated primitive classes.
     */
    private transient int primitiveType;

    /** Bitmap of offsets of iFields. */
    private transient int referenceInstanceOffsets;

    /** State of class initialization */
    private transient int status;

    private Class() {
        // Prevent this class from being instantiated,
        // instances should be created by the runtime only.
    }

    /**
     * Returns a {@code Class} object which represents the class with
     * the given name. The name should be the name of a non-primitive
     * class, as described in the {@link Class class definition}.
     * Primitive types can not be found using this method; use {@code
     * int.class} or {@code Integer.TYPE} instead.
     *
     * <p>If the class has not yet been loaded, it is loaded and initialized
     * first. This is done through either the class loader of the calling class
     * or one of its parent class loaders. It is possible that a static initializer is run as
     * a result of this call.
     *
     * @throws ClassNotFoundException
     *             if the requested class cannot be found.
     * @throws LinkageError
     *             if an error occurs during linkage
     * @throws ExceptionInInitializerError
     *             if an exception occurs during static initialization of a
     *             class.
     */
    public static Class<?> forName(String className) throws ClassNotFoundException {
        return forName(className, true, VMStack.getCallingClassLoader());
    }

    /**
     * Returns a {@code Class} object which represents the class with
     * the given name. The name should be the name of a non-primitive
     * class, as described in the {@link Class class definition}.
     * Primitive types can not be found using this method; use {@code
     * int.class} or {@code Integer.TYPE} instead.
     *
     * <p>If the class has not yet been loaded, it is loaded first, using the given class loader.
     * If the class has not yet been initialized and {@code shouldInitialize} is true,
     * the class will be initialized.
     *
     * <p>If the provided {@code classLoader} is {@code null}, the bootstrap
     * class loader will be used to load the class.
     *
     * @throws ClassNotFoundException
     *             if the requested class cannot be found.
     * @throws LinkageError
     *             if an error occurs during linkage
     * @throws ExceptionInInitializerError
     *             if an exception occurs during static initialization of a
     *             class.
     */
    public static Class<?> forName(String className, boolean shouldInitialize,
            ClassLoader classLoader) throws ClassNotFoundException {

        if (classLoader == null) {
            classLoader = BootClassLoader.getInstance();
        }
        // Catch an Exception thrown by the underlying native code. It wraps
        // up everything inside a ClassNotFoundException, even if e.g. an
        // Error occurred during initialization. This as a workaround for
        // an ExceptionInInitializerError that's also wrapped. It is actually
        // expected to be thrown. Maybe the same goes for other errors.
        // Not wrapping up all the errors will break android though.
        Class<?> result;
        try {
            result = classForName(className, shouldInitialize, classLoader);
        } catch (ClassNotFoundException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LinkageError) {
                throw (LinkageError) cause;
            }
            throw e;
        }
        return result;
    }

    static native Class<?> classForName(String className, boolean shouldInitialize,
            ClassLoader classLoader) throws ClassNotFoundException;

    /**
     * Returns an array containing {@code Class} objects for all
     * public classes, interfaces, enums and annotations that are
     * members of this class and its superclasses. This does not
     * include classes of implemented interfaces.  If there are no
     * such class members or if this object represents a primitive
     * type then an array of length 0 is returned.
     */
    public Class<?>[] getClasses() {
        List<Class<?>> result = new ArrayList<Class<?>>();
        for (Class<?> c = this; c != null; c = c.superClass) {
            for (Class<?> member : c.getDeclaredClasses()) {
                if (Modifier.isPublic(member.getModifiers())) {
                    result.add(member);
                }
            }
        }
        return result.toArray(new Class[result.size()]);
    }

    @Override public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        return AnnotationAccess.getAnnotation(this, annotationType);
    }

    /**
     * Returns an array containing all the annotations of this class. If there are no annotations
     * then an empty array is returned.
     *
     * @see #getDeclaredAnnotations()
     */
    @Override public Annotation[] getAnnotations() {
        return AnnotationAccess.getAnnotations(this);
    }

    /**
     * Returns the canonical name of this class. If this class does not have a
     * canonical name as defined in the Java Language Specification, then the
     * method returns {@code null}.
     */
    public String getCanonicalName() {
        if (isLocalClass() || isAnonymousClass())
            return null;

        if (isArray()) {
            /*
             * The canonical name of an array type depends on the (existence of)
             * the component type's canonical name.
             */
            String name = getComponentType().getCanonicalName();
            if (name != null) {
                return name + "[]";
            }
        } else if (isMemberClass()) {
            /*
             * The canonical name of an inner class depends on the (existence
             * of) the declaring class' canonical name.
             */
            String name = getDeclaringClass().getCanonicalName();
            if (name != null) {
                return name + "." + getSimpleName();
            }
        } else {
            /*
             * The canonical name of a top-level class or primitive type is
             * equal to the fully qualified name.
             */
            return getName();
        }

        /*
         * Other classes don't have a canonical name.
         */
        return null;
    }

    /**
     * Returns the class loader which was used to load the class represented by
     * this {@code Class}. Implementations are free to return {@code null} for
     * classes that were loaded by the bootstrap class loader. The Android
     * reference implementation, though, always returns a reference to an actual
     * class loader.
     */
    public ClassLoader getClassLoader() {
        if (this.isPrimitive()) {
            return null;
        }

        final ClassLoader loader = classLoader;
        return loader == null ? BootClassLoader.getInstance() : loader;
    }

    /**
     * Returns a {@code Class} object which represents the component type if
     * this class represents an array type. Returns {@code null} if this class
     * does not represent an array type. The component type of an array type is
     * the type of the elements of the array.
     */
    public Class<?> getComponentType() {
      return componentType;
    }

    /**
     * Returns the dex file from which this class was loaded.
     *
     * @hide
     */
    public Dex getDex() {
        if (dexCache == null) {
            return null;
        }
        return dexCache.getDex();
    }

    /**
     * Returns a string from the dex cache, computing the string from the dex file if necessary.
     *
     * @hide
     */
    public String getDexCacheString(Dex dex, int dexStringIndex) {
        String s = dexCache.getResolvedString(dexStringIndex);
        if (s == null) {
            s = dex.strings().get(dexStringIndex).intern();
            dexCache.setResolvedString(dexStringIndex, s);
        }
        return s;
    }

    /**
     * Returns a resolved type from the dex cache, computing the type from the dex file if
     * necessary.
     *
     * @hide
     */
    public Class<?> getDexCacheType(Dex dex, int dexTypeIndex) {
        Class<?> resolvedType = dexCache.getResolvedType(dexTypeIndex);
        if (resolvedType == null) {
            int descriptorIndex = dex.typeIds().get(dexTypeIndex);
            String descriptor = getDexCacheString(dex, descriptorIndex);
            resolvedType = InternalNames.getClass(getClassLoader(), descriptor);
            dexCache.setResolvedType(dexTypeIndex, resolvedType);
        }
        return resolvedType;
    }

    /**
     * Returns a {@code Constructor} object which represents the public
     * constructor matching the given parameter types.
     * {@code (Class[]) null} is equivalent to the empty array.
     *
     * @throws NoSuchMethodException
     *             if the constructor cannot be found.
     * @see #getDeclaredConstructor(Class[])
     */
    public Constructor<T> getConstructor(Class<?>... parameterTypes) throws NoSuchMethodException {
        return getConstructor(parameterTypes, true);
    }

    /**
     * Returns a {@code Constructor} object which represents the constructor
     * matching the specified parameter types that is declared by the class
     * represented by this {@code Class}.
     * {@code (Class[]) null} is equivalent to the empty array.
     *
     * @throws NoSuchMethodException
     *             if the requested constructor cannot be found.
     * @see #getConstructor(Class[])
     */
    public Constructor<T> getDeclaredConstructor(Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return getConstructor(parameterTypes, false);
    }

    /**
     * Returns a constructor with the given parameters.
     *
     * @param publicOnly true to only return public constructores.
     * @param parameterTypes argument types to match the constructor's.
     */
    private Constructor<T> getConstructor(Class<?>[] parameterTypes, boolean publicOnly)
            throws NoSuchMethodException {
        if (parameterTypes == null) {
            parameterTypes = EmptyArray.CLASS;
        }
        for (Class<?> c : parameterTypes) {
            if (c == null) {
                throw new NoSuchMethodException("parameter type is null");
            }
        }
        Constructor<T> result = getDeclaredConstructorInternal(parameterTypes);
        if (result == null || publicOnly && !Modifier.isPublic(result.getAccessFlags())) {
            throw new NoSuchMethodException("<init> " + Arrays.toString(parameterTypes));
        }
        return result;
    }

    /**
     * Returns the constructor with the given parameters if it is defined by this class;
     * {@code null} otherwise. This may return a non-public member.
     *
     * @param args the types of the parameters to the constructor.
     */
    private native Constructor<T> getDeclaredConstructorInternal(Class<?>[] args);

    /**
     * Returns an array containing {@code Constructor} objects for all public
     * constructors for this {@code Class}. If there
     * are no public constructors or if this {@code Class} represents an array
     * class, a primitive type or void then an empty array is returned.
     *
     * @see #getDeclaredConstructors()
     */
    public Constructor<?>[] getConstructors() {
        return getDeclaredConstructorsInternal(true);
    }

    /**
     * Returns an array containing {@code Constructor} objects for all
     * constructors declared in the class represented by this {@code Class}. If
     * there are no constructors or if this {@code Class} represents an array
     * class, a primitive type or void then an empty array is returned.
     *
     * @see #getConstructors()
     */
    public Constructor<?>[] getDeclaredConstructors() {
        return getDeclaredConstructorsInternal(false);
    }

    private native Constructor<?>[] getDeclaredConstructorsInternal(boolean publicOnly);

    /**
     * Returns a {@code Method} object which represents the method matching the
     * specified name and parameter types that is declared by the class
     * represented by this {@code Class}.
     *
     * @param name
     *            the requested method's name.
     * @param parameterTypes
     *            the parameter types of the requested method.
     *            {@code (Class[]) null} is equivalent to the empty array.
     * @return the method described by {@code name} and {@code parameterTypes}.
     * @throws NoSuchMethodException
     *             if the requested constructor cannot be found.
     * @throws NullPointerException
     *             if {@code name} is {@code null}.
     * @see #getMethod(String, Class[])
     */
    public Method getDeclaredMethod(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return getMethod(name, parameterTypes, false);
    }

    /**
     * Returns a {@code Method} object which represents the public method with
     * the specified name and parameter types.
     * {@code (Class[]) null} is equivalent to the empty array.
     * This method first searches the
     * class C represented by this {@code Class}, then the superclasses of C and
     * finally the interfaces implemented by C and finally the superclasses of C
     * for a method with matching name.
     *
     * @throws NoSuchMethodException
     *             if the method cannot be found.
     * @see #getDeclaredMethod(String, Class[])
     */
    public Method getMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return getMethod(name, parameterTypes, true);
    }

    private Method getMethod(String name, Class<?>[] parameterTypes, boolean recursivePublicMethods)
            throws NoSuchMethodException {
        if (name == null) {
            throw new NullPointerException("name == null");
        }
        if (parameterTypes == null) {
            parameterTypes = EmptyArray.CLASS;
        }
        for (Class<?> c : parameterTypes) {
            if (c == null) {
                throw new NoSuchMethodException("parameter type is null");
            }
        }
        Method result = recursivePublicMethods ? getPublicMethodRecursive(name, parameterTypes)
                                               : getDeclaredMethodInternal(name, parameterTypes);
        // Fail if we didn't find the method or it was expected to be public.
        if (result == null ||
            (recursivePublicMethods && !Modifier.isPublic(result.getAccessFlags()))) {
            throw new NoSuchMethodException(name + " " + Arrays.toString(parameterTypes));
        }
        return result;
    }

    private Method getPublicMethodRecursive(String name, Class<?>[] parameterTypes) {
        // search superclasses
        for (Class<?> c = this; c != null; c = c.getSuperclass()) {
            Method result = c.getDeclaredMethodInternal(name, parameterTypes);
            if (result != null && Modifier.isPublic(result.getAccessFlags())) {
                return result;
            }
        }
        // search iftable which has a flattened and uniqued list of interfaces
        Object[] iftable = ifTable;
        if (iftable != null) {
            for (int i = 0; i < iftable.length; i += 2) {
                Class<?> ifc = (Class<?>) iftable[i];
                Method result = ifc.getPublicMethodRecursive(name, parameterTypes);
                if (result != null && Modifier.isPublic(result.getAccessFlags())) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Returns the method if it is defined by this class; {@code null} otherwise. This may return a
     * non-public member.
     *
     * @param name the method name
     * @param args the method's parameter types
     */
    private native Method getDeclaredMethodInternal(String name, Class<?>[] args);

    /**
     * Returns an array containing {@code Method} objects for all methods
     * declared in the class represented by this {@code Class}. If there are no
     * methods or if this {@code Class} represents an array class, a primitive
     * type or void then an empty array is returned.
     *
     * @see #getMethods()
     */
    public Method[] getDeclaredMethods() {
        Method[] result = getDeclaredMethodsUnchecked(false);
        for (Method m : result) {
            // Throw NoClassDefFoundError if types cannot be resolved.
            m.getReturnType();
            m.getParameterTypes();
        }
        return result;

    }

    /**
     * Populates a list of methods without performing any security or type
     * resolution checks first. If no methods exist, the list is not modified.
     *
     * @param publicOnly Whether to return only public methods.
     * @param methods A list to populate with declared methods.
     * @hide
     */
    public native Method[] getDeclaredMethodsUnchecked(boolean publicOnly);

    /**
     * Returns an array containing {@code Method} objects for all public methods
     * for the class C represented by this {@code Class}. Methods may be
     * declared in C, the interfaces it implements or in the superclasses of C.
     * The elements in the returned array are in no particular order.
     *
     * <p>If there are no public methods or if this {@code Class} represents a
     * primitive type or {@code void} then an empty array is returned.
     *
     * @see #getDeclaredMethods()
     */
    public Method[] getMethods() {
        List<Method> methods = new ArrayList<Method>();
        getPublicMethodsInternal(methods);
        /*
         * Remove duplicate methods defined by superclasses and
         * interfaces, preferring to keep methods declared by derived
         * types.
         */
        CollectionUtils.removeDuplicates(methods, Method.ORDER_BY_SIGNATURE);
        return methods.toArray(new Method[methods.size()]);
    }

    /**
     * Populates {@code result} with public methods defined by this class, its
     * superclasses, and all implemented interfaces, including overridden methods.
     */
    private void getPublicMethodsInternal(List<Method> result) {
        Collections.addAll(result, getDeclaredMethodsUnchecked(true));
        if (!isInterface()) {
            // Search superclasses, for interfaces don't search java.lang.Object.
            for (Class<?> c = superClass; c != null; c = c.superClass) {
                Collections.addAll(result, c.getDeclaredMethodsUnchecked(true));
            }
        }
        // Search iftable which has a flattened and uniqued list of interfaces.
        Object[] iftable = ifTable;
        if (iftable != null) {
            for (int i = 0; i < iftable.length; i += 2) {
                Class<?> ifc = (Class<?>) iftable[i];
                Collections.addAll(result, ifc.getDeclaredMethodsUnchecked(true));
            }
        }
    }

    /**
     * Returns the annotations that are directly defined on the class
     * represented by this {@code Class}. Annotations that are inherited are not
     * included in the result. If there are no annotations at all, an empty
     * array is returned.
     *
     * @see #getAnnotations()
     */
    @Override public Annotation[] getDeclaredAnnotations() {
        List<Annotation> result = AnnotationAccess.getDeclaredAnnotations(this);
        return result.toArray(new Annotation[result.size()]);
    }

    /**
     * Returns an array containing {@code Class} objects for all classes,
     * interfaces, enums and annotations that are members of this class.
     */
    public Class<?>[] getDeclaredClasses() {
        return AnnotationAccess.getMemberClasses(this);
    }

    /**
     * Returns a {@code Field} object for the field with the given name
     * which is declared in the class represented by this {@code Class}.
     *
     * @throws NoSuchFieldException if the requested field can not be found.
     * @see #getField(String)
     */
    public native Field getDeclaredField(String name) throws NoSuchFieldException;

    /**
     * Returns an array containing {@code Field} objects for all fields declared
     * in the class represented by this {@code Class}. If there are no fields or
     * if this {@code Class} represents an array class, a primitive type or void
     * then an empty array is returned.
     *
     * @see #getFields()
     */
    public native Field[] getDeclaredFields();

    /**
     * Populates a list of fields without performing any security or type
     * resolution checks first. If no fields exist, the list is not modified.
     *
     * @param publicOnly Whether to return only public fields.
     * @param fields A list to populate with declared fields.
     * @hide
     */
    public native Field[] getDeclaredFieldsUnchecked(boolean publicOnly);

    /**
     * Returns the field if it is defined by this class; {@code null} otherwise. This
     * may return a non-public member.
     */
    private native Field getDeclaredFieldInternal(String name);

    /**
     * Returns the subset of getDeclaredFields which are public.
     */
    private native Field[] getPublicDeclaredFields();

    /**
     * Returns the class that this class is a member of, or {@code null} if this
     * class is a top-level class, a primitive, an array, or defined within a
     * method or constructor.
     */
    public Class<?> getDeclaringClass() {
        if (AnnotationAccess.isAnonymousClass(this)) {
            return null;
        }
        return AnnotationAccess.getEnclosingClass(this);
    }

    /**
     * Returns the class enclosing this class. For most classes this is the same
     * as the {@link #getDeclaringClass() declaring class}. For classes defined
     * within a method or constructor (typically anonymous inner classes), this
     * is the declaring class of that member.
     */
    public Class<?> getEnclosingClass() {
        Class<?> declaringClass = getDeclaringClass();
        if (declaringClass != null) {
            return declaringClass;
        }
        AccessibleObject member = AnnotationAccess.getEnclosingMethodOrConstructor(this);
        if (member != null)  {
            return ((Member) member).getDeclaringClass();
        }
        return AnnotationAccess.getEnclosingClass(this);
    }

    /**
     * Returns the enclosing {@code Constructor} of this {@code Class}, if it is an
     * anonymous or local/automatic class; otherwise {@code null}.
     */
    public Constructor<?> getEnclosingConstructor() {
        if (classNameImpliesTopLevel()) {
            return null;
        }
        AccessibleObject result = AnnotationAccess.getEnclosingMethodOrConstructor(this);
        return result instanceof Constructor ? (Constructor<?>) result : null;
    }

    /**
     * Returns the enclosing {@code Method} of this {@code Class}, if it is an
     * anonymous or local/automatic class; otherwise {@code null}.
     */
    public Method getEnclosingMethod() {
        if (classNameImpliesTopLevel()) {
            return null;
        }
        AccessibleObject result = AnnotationAccess.getEnclosingMethodOrConstructor(this);
        return result instanceof Method ? (Method) result : null;
    }

    /**
     * Returns true if this class is definitely a top level class, or false if
     * a more expensive check like {@link #getEnclosingClass()} is necessary.
     *
     * <p>This is a hack that exploits an implementation detail of all Java
     * language compilers: generated names always contain "$". As it is possible
     * for a top level class to be named with a "$", a false result <strong>does
     * not</strong> indicate that this isn't a top-level class.
     */
    private boolean classNameImpliesTopLevel() {
        return !getName().contains("$");
    }

    /**
     * Returns the {@code enum} constants associated with this {@code Class}.
     * Returns {@code null} if this {@code Class} does not represent an {@code
     * enum} type.
     */
    @SuppressWarnings("unchecked") // we only cast after confirming that this class is an enum
    public T[] getEnumConstants() {
        if (!isEnum()) {
            return null;
        }
        return (T[]) Enum.getSharedConstants((Class) this).clone();
    }

    /**
     * Returns a {@code Field} object which represents the public field with the
     * given name. This method first searches the class C represented by
     * this {@code Class}, then the interfaces implemented by C and finally the
     * superclasses of C.
     *
     * @throws NoSuchFieldException
     *             if the field cannot be found.
     * @see #getDeclaredField(String)
     */
    public Field getField(String name) throws NoSuchFieldException {
        if (name == null) {
            throw new NullPointerException("name == null");
        }
        Field result = getPublicFieldRecursive(name);
        if (result == null) {
            throw new NoSuchFieldException(name);
        }
        return result;
    }

    private Field getPublicFieldRecursive(String name) {
        // search superclasses
        for (Class<?> c = this; c != null; c = c.superClass) {
            Field result = c.getDeclaredFieldInternal(name);
            if (result != null && (result.getModifiers() & Modifier.PUBLIC) != 0) {
                return result;
            }
        }

        // search iftable which has a flattened and uniqued list of interfaces
        if (ifTable != null) {
            for (int i = 0; i < ifTable.length; i += 2) {
                Field result = ((Class<?>) ifTable[i]).getPublicFieldRecursive(name);
                if (result != null && (result.getModifiers() & Modifier.PUBLIC) != 0) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * Returns an array containing {@code Field} objects for all public fields
     * for the class C represented by this {@code Class}. Fields may be declared
     * in C, the interfaces it implements or in the superclasses of C. The
     * elements in the returned array are in no particular order.
     *
     * <p>If there are no public fields or if this class represents an array class,
     * a primitive type or {@code void} then an empty array is returned.
     *
     * @see #getDeclaredFields()
     */
    public Field[] getFields() {
        List<Field> fields = new ArrayList<Field>();
        getPublicFieldsRecursive(fields);
        return fields.toArray(new Field[fields.size()]);
    }

    /**
     * Populates {@code result} with public fields defined by this class, its
     * superclasses, and all implemented interfaces.
     */
    private void getPublicFieldsRecursive(List<Field> result) {
        // search superclasses
        for (Class<?> c = this; c != null; c = c.superClass) {
            Collections.addAll(result, c.getPublicDeclaredFields());
        }

        // search iftable which has a flattened and uniqued list of interfaces
        Object[] iftable = ifTable;
        if (iftable != null) {
            for (int i = 0; i < iftable.length; i += 2) {
                Collections.addAll(result, ((Class<?>) iftable[i]).getPublicDeclaredFields());
            }
        }
    }

    /**
     * Returns the {@link Type}s of the interfaces that this {@code Class} directly
     * implements. If the {@code Class} represents a primitive type or {@code
     * void} then an empty array is returned.
     */
    public Type[] getGenericInterfaces() {
        Type[] result;
        synchronized (Caches.genericInterfaces) {
            result = Caches.genericInterfaces.get(this);
            if (result == null) {
                String annotationSignature = AnnotationAccess.getSignature(this);
                if (annotationSignature == null) {
                    result = getInterfaces();
                } else {
                    GenericSignatureParser parser = new GenericSignatureParser(getClassLoader());
                    parser.parseForClass(this, annotationSignature);
                    result = Types.getTypeArray(parser.interfaceTypes, false);
                }
                Caches.genericInterfaces.put(this, result);
            }
        }
        return (result.length == 0) ? result : result.clone();
    }

    /**
     * Returns the {@code Type} that represents the superclass of this {@code
     * class}.
     */
    public Type getGenericSuperclass() {
        Type genericSuperclass = getSuperclass();
        // This method is specified to return null for all cases where getSuperclass
        // returns null, i.e, for primitives, interfaces, void and java.lang.Object.
        if (genericSuperclass == null) {
            return null;
        }

        String annotationSignature = AnnotationAccess.getSignature(this);
        if (annotationSignature != null) {
            GenericSignatureParser parser = new GenericSignatureParser(getClassLoader());
            parser.parseForClass(this, annotationSignature);
            genericSuperclass = parser.superclassType;
        }
        return Types.getType(genericSuperclass);
    }

    /**
     * Returns an array of {@code Class} objects that match the interfaces
     * in the {@code implements} declaration of the class represented
     * by this {@code Class}. The order of the elements in the array is
     * identical to the order in the original class declaration. If the class
     * does not implement any interfaces, an empty array is returned.
     *
     * <p>This method only returns directly-implemented interfaces, and does not
     * include interfaces implemented by superclasses or superinterfaces of any
     * implemented interfaces.
     */
    public Class<?>[] getInterfaces() {
        if (isArray()) {
            return new Class<?>[] { Cloneable.class, Serializable.class };
        } else if (isProxy()) {
            return getProxyInterfaces();
        }
        Dex dex = getDex();
        if (dex == null) {
            return EmptyArray.CLASS;
        }
        short[] interfaces = dex.interfaceTypeIndicesFromClassDefIndex(dexClassDefIndex);
        Class<?>[] result = new Class<?>[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            result[i] = getDexCacheType(dex, interfaces[i]);
        }
        return result;
    }

    // Returns the interfaces that this proxy class directly implements.
    private native Class<?>[] getProxyInterfaces();

    /**
     * Returns an integer that represents the modifiers of the class represented
     * by this {@code Class}. The returned value is a combination of bits
     * defined by constants in the {@link Modifier} class.
     */
    public int getModifiers() {
        // Array classes inherit modifiers from their component types, but in the case of arrays
        // of an inner class, the class file may contain "fake" access flags because it's not valid
        // for a top-level class to private, say. The real access flags are stored in the InnerClass
        // attribute, so we need to make sure we drill down to the inner class: the accessFlags
        // field is not the value we want to return, and the synthesized array class does not itself
        // have an InnerClass attribute. https://code.google.com/p/android/issues/detail?id=56267
        if (isArray()) {
            int componentModifiers = getComponentType().getModifiers();
            if ((componentModifiers & Modifier.INTERFACE) != 0) {
                componentModifiers &= ~(Modifier.INTERFACE | Modifier.STATIC);
            }
            return Modifier.ABSTRACT | Modifier.FINAL | componentModifiers;
        }
        int JAVA_FLAGS_MASK = 0xffff;
        int modifiers = AnnotationAccess.getInnerClassFlags(this, accessFlags & JAVA_FLAGS_MASK);
        return modifiers & JAVA_FLAGS_MASK;
    }

    /**
     * Returns the name of the class represented by this {@code Class}. For a
     * description of the format which is used, see the class definition of
     * {@link Class}.
     */
    public String getName() {
        String result = name;
        return (result == null) ? (name = getNameNative()) : result;
    }

    private native String getNameNative();

    /**
     * Returns the simple name of the class represented by this {@code Class} as
     * defined in the source code. If there is no name (that is, the class is
     * anonymous) then an empty string is returned. If the receiver is an array
     * then the name of the underlying type with square braces appended (for
     * example {@code "Integer[]"}) is returned.
     *
     * @return the simple name of the class represented by this {@code Class}.
     */
    public String getSimpleName() {
        if (isArray()) {
            return getComponentType().getSimpleName() + "[]";
        }

        if (isAnonymousClass()) {
            return "";
        }

        if (isMemberClass() || isLocalClass()) {
            return getInnerClassName();
        }

        String name = getName();
        int dot = name.lastIndexOf('.');
        if (dot != -1) {
            return name.substring(dot + 1);
        }

        return name;
    }

    /**
     * Returns the simple name of a member or local class, or {@code null} otherwise.
     */
    private String getInnerClassName() {
        return AnnotationAccess.getInnerClassName(this);
    }

    /**
     * Returns {@code null}.
     */
    public ProtectionDomain getProtectionDomain() {
        return null;
    }

    /**
     * Returns the URL of the given resource, or {@code null} if the resource is not found.
     * The mapping between the resource name and the URL is managed by the class' class loader.
     *
     * @see ClassLoader
     */
    public URL getResource(String resourceName) {
        // Get absolute resource name, but without the leading slash
        if (resourceName.startsWith("/")) {
            resourceName = resourceName.substring(1);
        } else {
            String pkg = getName();
            int dot = pkg.lastIndexOf('.');
            if (dot != -1) {
                pkg = pkg.substring(0, dot).replace('.', '/');
            } else {
                pkg = "";
            }

            resourceName = pkg + "/" + resourceName;
        }

        // Delegate to proper class loader
        ClassLoader loader = getClassLoader();
        if (loader != null) {
            return loader.getResource(resourceName);
        } else {
            return ClassLoader.getSystemResource(resourceName);
        }
    }

    /**
     * Returns a read-only stream for the contents of the given resource, or {@code null} if the
     * resource is not found.
     * The mapping between the resource name and the stream is managed by the class' class loader.
     *
     * @see ClassLoader
     */
    public InputStream getResourceAsStream(String resourceName) {
        // Get absolute resource name, but without the leading slash
        if (resourceName.startsWith("/")) {
            resourceName = resourceName.substring(1);
        } else {
            String pkg = getName();
            int dot = pkg.lastIndexOf('.');
            if (dot != -1) {
                pkg = pkg.substring(0, dot).replace('.', '/');
            } else {
                pkg = "";
            }

            resourceName = pkg + "/" + resourceName;
        }

        // Delegate to proper class loader
        ClassLoader loader = getClassLoader();
        if (loader != null) {
            return loader.getResourceAsStream(resourceName);
        } else {
            return ClassLoader.getSystemResourceAsStream(resourceName);
        }
    }

    /**
     * Returns {@code null}. (On Android, a {@code ClassLoader} can load classes from multiple dex
     * files. All classes from any given dex file will have the same signers, but different dex
     * files may have different signers. This does not fit well with the original
     * {@code ClassLoader}-based model of {@code getSigners}.)
     */
    public Object[] getSigners() {
        // See http://code.google.com/p/android/issues/detail?id=1766.
        return null;
    }

    /**
     * Returns the {@code Class} object which represents the superclass of the
     * class represented by this {@code Class}. If this {@code Class} represents
     * the {@code Object} class, a primitive type, an interface or void then the
     * method returns {@code null}. If this {@code Class} represents an array
     * class then the {@code Object} class is returned.
     */
    public Class<? super T> getSuperclass() {
      // For interfaces superClass is Object (which agrees with the JNI spec)
      // but not with the expected behavior here.
      if (isInterface()) {
        return null;
      } else {
        return superClass;
      }
    }

    /**
     * Returns an array containing {@code TypeVariable} objects for type
     * variables declared by the generic class represented by this {@code
     * Class}. Returns an empty array if the class is not generic.
     */
    @SuppressWarnings("unchecked")
    @Override public synchronized TypeVariable<Class<T>>[] getTypeParameters() {
        String annotationSignature = AnnotationAccess.getSignature(this);
        if (annotationSignature == null) {
            return EmptyArray.TYPE_VARIABLE;
        }
        GenericSignatureParser parser = new GenericSignatureParser(getClassLoader());
        parser.parseForClass(this, annotationSignature);
        return parser.formalTypeParameters;
    }

    /**
     * Tests whether this {@code Class} represents an annotation class.
     */
    public boolean isAnnotation() {
        final int ACC_ANNOTATION = 0x2000;  // not public in reflect.Modifier
        return (accessFlags & ACC_ANNOTATION) != 0;
    }

    @Override public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return AnnotationAccess.isAnnotationPresent(this, annotationType);
    }

    /**
     * Tests whether the class represented by this {@code Class} is
     * anonymous.
     */
    public boolean isAnonymousClass() {
        return AnnotationAccess.isAnonymousClass(this);
    }

    /**
     * Tests whether the class represented by this {@code Class} is an array class.
     */
    public boolean isArray() {
        return getComponentType() != null;
    }

    /**
     * Is this a runtime created proxy class?
     *
     * @hide
     */
    public boolean isProxy() {
        return (accessFlags & 0x00040000) != 0;
    }

    /**
     * Can {@code c}  be assigned to this class? For example, String can be assigned to Object
     * (by an upcast), however, an Object cannot be assigned to a String as a potentially exception
     * throwing downcast would be necessary. Similarly for interfaces, a class that implements (or
     * an interface that extends) another can be assigned to its parent, but not vice-versa. All
     * Classes may assign to themselves. Classes for primitive types may not assign to each other.
     *
     * @param c the class to check.
     * @return {@code true} if {@code c} can be assigned to the class
     *         represented by this {@code Class}; {@code false} otherwise.
     * @throws NullPointerException if {@code c} is {@code null}.
     */
    public boolean isAssignableFrom(Class<?> c) {
        if (this == c) {
            return true;  // Can always assign to things of the same type.
        } else if (this == Object.class) {
            return !c.isPrimitive();  // Can assign any reference to java.lang.Object.
        } else if (isArray()) {
            return c.isArray() && componentType.isAssignableFrom(c.componentType);
        } else if (isInterface()) {
            // Search iftable which has a flattened and uniqued list of interfaces.
            Object[] iftable = c.ifTable;
            if (iftable != null) {
                for (int i = 0; i < iftable.length; i += 2) {
                    if (iftable[i] == this) {
                        return true;
                    }
                }
            }
            return false;
        } else {
            if (!c.isInterface()) {
                for (c = c.superClass; c != null; c = c.superClass) {
                    if (c == this) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Tests whether the class represented by this {@code Class} is an
     * {@code enum}.
     */
    public boolean isEnum() {
        return (getSuperclass() == Enum.class) && ((accessFlags & 0x4000) != 0);
    }

    /**
     * Tests whether the given object can be cast to the class
     * represented by this {@code Class}. This is the runtime version of the
     * {@code instanceof} operator.
     *
     * @return {@code true} if {@code object} can be cast to the type
     *         represented by this {@code Class}; {@code false} if {@code
     *         object} is {@code null} or cannot be cast.
     */
    public boolean isInstance(Object object) {
        if (object == null) {
            return false;
        }
        return isAssignableFrom(object.getClass());
    }

    /**
     * Tests whether this {@code Class} represents an interface.
     */
    public boolean isInterface() {
      return (accessFlags & Modifier.INTERFACE) != 0;
    }

    /**
     * Tests whether the class represented by this {@code Class} is defined
     * locally.
     */
    public boolean isLocalClass() {
        return !classNameImpliesTopLevel()
                && AnnotationAccess.getEnclosingMethodOrConstructor(this) != null
                && !isAnonymousClass();
    }

    /**
     * Tests whether the class represented by this {@code Class} is a member
     * class.
     */
    public boolean isMemberClass() {
        return getDeclaringClass() != null;
    }

    /**
     * Tests whether this {@code Class} represents a primitive type.
     */
    public boolean isPrimitive() {
      return (primitiveType & 0xFFFF) != 0;
    }

    /**
     * Tests whether this {@code Class} represents a synthetic type.
     */
    public boolean isSynthetic() {
        final int ACC_SYNTHETIC = 0x1000;   // not public in reflect.Modifier
        return (accessFlags & ACC_SYNTHETIC) != 0;
    }

    /**
     * Indicates whether this {@code Class} or its parents override finalize.
     *
     * @hide
     */
    public boolean isFinalizable() {
      final int ACC_CLASS_IS_FINALIZABLE = 0x80000000;  // not public in reflect.Modifier
      return (accessFlags & ACC_CLASS_IS_FINALIZABLE) != 0;
    }

    /**
     * Returns a new instance of the class represented by this {@code Class},
     * created by invoking the default (that is, zero-argument) constructor. If
     * there is no such constructor, or if the creation fails (either because of
     * a lack of available memory or because an exception is thrown by the
     * constructor), an {@code InstantiationException} is thrown. If the default
     * constructor exists but is not accessible from the context where this
     * method is invoked, an {@code IllegalAccessException} is thrown.
     *
     * @throws IllegalAccessException
     *             if the default constructor is not visible.
     * @throws InstantiationException
     *             if the instance cannot be created.
     */
    public native T newInstance() throws InstantiationException, IllegalAccessException;

    private boolean canAccess(Class<?> c) {
        if(Modifier.isPublic(c.accessFlags)) {
            return true;
        }
        return inSamePackage(c);
    }

    private boolean canAccessMember(Class<?> memberClass, int memberModifiers) {
        if (memberClass == this || Modifier.isPublic(memberModifiers)) {
            return true;
        }
        if (Modifier.isPrivate(memberModifiers)) {
            return false;
        }
        if (Modifier.isProtected(memberModifiers)) {
            for (Class<?> parent = this.superClass; parent != null; parent = parent.superClass) {
                if (parent == memberClass) {
                    return true;
                }
            }
        }
        return inSamePackage(memberClass);
    }

    private boolean inSamePackage(Class<?> c) {
        if (classLoader != c.classLoader) {
            return false;
        }
        String packageName1 = getPackageName$();
        String packageName2 = c.getPackageName$();
        if (packageName1 == null) {
            return packageName2 == null;
        } else if (packageName2 == null) {
            return false;
        } else {
            return packageName1.equals(packageName2);
        }
    }

    @Override
    public String toString() {
        if (isPrimitive()) {
            return getSimpleName();
        } else {
            return (isInterface() ? "interface " : "class ") + getName();
        }
    }

    /**
     * Returns the {@code Package} of which the class represented by this
     * {@code Class} is a member. Returns {@code null} if no {@code Package}
     * object was created by the class loader of the class.
     */
    public Package getPackage() {
        // TODO This might be a hack, but the runtime doesn't have the necessary info.
        ClassLoader loader = getClassLoader();
        if (loader != null) {
            String packageName = getPackageName$();
            return packageName != null ? loader.getPackage(packageName) : null;
        }
        return null;
    }

    /**
     * Returns the package name of this class. This returns {@code null} for classes in
     * the default package.
     *
     * @hide
     */
    public String getPackageName$() {
        String name = getName();
        int last = name.lastIndexOf('.');
        return last == -1 ? null : name.substring(0, last);
    }

    /**
     * Returns the assertion status for the class represented by this {@code
     * Class}. Assertion is enabled / disabled based on the class loader,
     * package or class default at runtime.
     */
    public boolean desiredAssertionStatus() {
      return false;
    }

    /**
     * Casts this {@code Class} to represent a subclass of the given class.
     * If successful, this {@code Class} is returned; otherwise a {@code
     * ClassCastException} is thrown.
     *
     * @throws ClassCastException
     *             if this {@code Class} cannot be cast to the given type.
     */
    @SuppressWarnings("unchecked")
    public <U> Class<? extends U> asSubclass(Class<U> c) {
        if (c.isAssignableFrom(this)) {
            return (Class<? extends U>)this;
        }
        String actualClassName = this.getName();
        String desiredClassName = c.getName();
        throw new ClassCastException(actualClassName + " cannot be cast to " + desiredClassName);
    }

    /**
     * Casts the given object to the type represented by this {@code Class}.
     * If the object is {@code null} then the result is also {@code null}.
     *
     * @throws ClassCastException
     *             if the object cannot be cast to the given type.
     */
    @SuppressWarnings("unchecked")
    public T cast(Object obj) {
        if (obj == null) {
            return null;
        } else if (this.isInstance(obj)) {
            return (T)obj;
        }
        String actualClassName = obj.getClass().getName();
        String desiredClassName = this.getName();
        throw new ClassCastException(actualClassName + " cannot be cast to " + desiredClassName);
    }

    /**
     * The class def of this class in its own Dex, or -1 if there is no class def.
     *
     * @hide
     */
    public int getDexClassDefIndex() {
        return (dexClassDefIndex == 65535) ? -1 : dexClassDefIndex;
    }

    /**
     * The type index of this class in its own Dex, or -1 if it is unknown. If a class is referenced
     * by multiple Dex files, it will have a different type index in each. Dex files support 65534
     * type indices, with 65535 representing no index.
     *
     * @hide
     */
    public int getDexTypeIndex() {
        int typeIndex = dexTypeIndex;
        if (typeIndex != 65535) {
            return typeIndex;
        }
        synchronized (this) {
            typeIndex = dexTypeIndex;
            if (typeIndex == 65535) {
                if (dexClassDefIndex >= 0) {
                    typeIndex = getDex().typeIndexFromClassDefIndex(dexClassDefIndex);
                } else {
                    typeIndex = getDex().findTypeIndex(InternalNames.getInternalName(this));
                    if (typeIndex < 0) {
                        typeIndex = -1;
                    }
                }
                dexTypeIndex = typeIndex;
            }
        }
        return typeIndex;
    }

    /**
     * The annotation directory offset of this class in its own Dex, or 0 if it
     * is unknown.
     *
     * TODO: 0 is a sentinel that means 'no annotations directory'; this should be -1 if unknown
     *
     * @hide
     */
    public int getDexAnnotationDirectoryOffset() {
        Dex dex = getDex();
        if (dex == null) {
            return 0;
        }
        int classDefIndex = getDexClassDefIndex();
        if (classDefIndex < 0) {
            return 0;
        }
        return dex.annotationDirectoryOffsetFromClassDefIndex(classDefIndex);
    }

    private static class Caches {
        /**
         * Cache to avoid frequent recalculation of generic interfaces, which is generally uncommon.
         * Sized sufficient to allow ConcurrentHashMapTest to run without recalculating its generic
         * interfaces (required to avoid time outs). Validated by running reflection heavy code
         * such as applications using Guice-like frameworks.
         */
        private static final BasicLruCache<Class, Type[]> genericInterfaces
            = new BasicLruCache<Class, Type[]>(8);
    }
}

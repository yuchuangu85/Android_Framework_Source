/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.lang.reflect;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import libcore.util.EmptyArray;

/**
 * {@code Proxy} defines methods for creating dynamic proxy classes and instances.
 * A proxy class implements a declared set of interfaces and delegates method
 * invocations to an {@code InvocationHandler}.
 *
 * @see InvocationHandler
 * @since 1.3
 */
public class Proxy implements Serializable {

    private static final long serialVersionUID = -2222568056686623797L;

    private static int nextClassNameIndex = 0;

    /**
     * Orders methods by their name, parameters, return type and inheritance relationship.
     *
     * @hide
     */
    private static final Comparator<Method> ORDER_BY_SIGNATURE_AND_SUBTYPE = new Comparator<Method>() {
        @Override public int compare(Method a, Method b) {
            int comparison = Method.ORDER_BY_SIGNATURE.compare(a, b);
            if (comparison != 0) {
                return comparison;
            }
            Class<?> aClass = a.getDeclaringClass();
            Class<?> bClass = b.getDeclaringClass();
            if (aClass == bClass) {
                return 0;
            } else if (aClass.isAssignableFrom(bClass)) {
                return 1;
            } else if (bClass.isAssignableFrom(aClass)) {
                return -1;
            } else {
                return 0;
            }
        }
    };

    /** The invocation handler on which the method calls are dispatched. */
    protected InvocationHandler h;

    @SuppressWarnings("unused")
    private Proxy() {
    }

    /**
     * Constructs a new {@code Proxy} instance with the specified invocation
     * handler.
     *
     * @param h
     *            the invocation handler for the newly created proxy
     */
    protected Proxy(InvocationHandler h) {
        this.h = h;
    }

    /**
     * Returns the dynamically built {@code Class} for the specified interfaces.
     * Creates a new {@code Class} when necessary. The order of the interfaces
     * is relevant. Invocations of this method with the same interfaces but
     * different order result in different generated classes. The interfaces
     * must be visible from the supplied class loader; no duplicates are
     * permitted. All non-public interfaces must be defined in the same package.
     *
     * @param loader
     *            the class loader that will define the proxy class
     * @param interfaces
     *            an array of {@code Class} objects, each one identifying an
     *            interface that will be implemented by the returned proxy
     *            class
     * @return a proxy class that implements all of the interfaces referred to
     *         in the contents of {@code interfaces}
     * @throws IllegalArgumentException
     *                if any of the interface restrictions are violated
     * @throws NullPointerException
     *                if either {@code interfaces} or any of its elements are
     *                {@code null}
     */
    public static Class<?> getProxyClass(ClassLoader loader, Class<?>... interfaces)
            throws IllegalArgumentException {
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }

        if (interfaces == null) {
            throw new NullPointerException("interfaces == null");
        }

        // Make a copy of the list early on because we're using the list as a
        // cache key and we don't want it changing under us.
        final List<Class<?>> interfaceList = new ArrayList<Class<?>>(interfaces.length);
        Collections.addAll(interfaceList, interfaces);

        // We use a HashSet *only* for detecting duplicates and null entries. We
        // can't use it as our cache key because we need to preserve the order in
        // which these interfaces were specified. (Different orders should define
        // different proxies.)
        final Set<Class<?>> interfaceSet = new HashSet<Class<?>>(interfaceList);
        if (interfaceSet.contains(null)) {
            throw new NullPointerException("interface list contains null: " + interfaceList);
        }

        if (interfaceSet.size() != interfaces.length) {
            throw new IllegalArgumentException("duplicate interface in list: " + interfaceList);
        }

        synchronized (loader.proxyCache) {
            Class<?> proxy = loader.proxyCache.get(interfaceList);
            if (proxy != null) {
                return proxy;
            }
        }

        String commonPackageName = null;
        for (Class<?> c : interfaces) {
            if (!c.isInterface()) {
                throw new IllegalArgumentException(c + " is not an interface");
            }
            if (!isVisibleToClassLoader(loader, c)) {
                throw new IllegalArgumentException(c + " is not visible from class loader");
            }
            if (!Modifier.isPublic(c.getModifiers())) {
                String packageName = c.getPackageName$();
                if (packageName == null) {
                    packageName = "";
                }
                if (commonPackageName != null && !commonPackageName.equals(packageName)) {
                    throw new IllegalArgumentException(
                            "non-public interfaces must be in the same package");
                }
                commonPackageName = packageName;
            }
        }

        List<Method> methods = getMethods(interfaces);
        Collections.sort(methods, ORDER_BY_SIGNATURE_AND_SUBTYPE);
        validateReturnTypes(methods);
        List<Class<?>[]> exceptions = deduplicateAndGetExceptions(methods);
        Method[] methodsArray = methods.toArray(new Method[methods.size()]);
        Class<?>[][] exceptionsArray = exceptions.toArray(new Class<?>[exceptions.size()][]);

        String baseName = commonPackageName != null && !commonPackageName.isEmpty()
                ? commonPackageName + ".$Proxy"
                : "$Proxy";

        Class<?> result;
        synchronized (loader.proxyCache) {
            result = loader.proxyCache.get(interfaceList);
            if (result == null) {
                String name = baseName + nextClassNameIndex++;
                result = generateProxy(name, interfaces, loader, methodsArray, exceptionsArray);
                loader.proxyCache.put(interfaceList, result);
            }
        }

        return result;
    }

    private static boolean isVisibleToClassLoader(ClassLoader loader, Class<?> c) {
        try {
            return loader == c.getClassLoader() || c == Class.forName(c.getName(), false, loader);
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /**
     * Returns an instance of the dynamically built class for the specified
     * interfaces. Method invocations on the returned instance are forwarded to
     * the specified invocation handler. The interfaces must be visible from the
     * supplied class loader; no duplicates are permitted. All non-public
     * interfaces must be defined in the same package.
     *
     * @param loader
     *            the class loader that will define the proxy class
     * @param interfaces
     *            an array of {@code Class} objects, each one identifying an
     *            interface that will be implemented by the returned proxy
     *            object
     * @param invocationHandler
     *            the invocation handler that handles the dispatched method
     *            invocations
     * @return a new proxy object that delegates to the handler {@code h}
     * @throws IllegalArgumentException
     *                if any of the interface restrictions are violated
     * @throws NullPointerException
     *                if the interfaces or any of its elements are null
     */
    public static Object newProxyInstance(ClassLoader loader, Class<?>[] interfaces,
                                          InvocationHandler invocationHandler)
            throws IllegalArgumentException {

        if (invocationHandler == null) {
            throw new NullPointerException("invocationHandler == null");
        }
        Exception cause;
        try {
            return getProxyClass(loader, interfaces)
                    .getConstructor(InvocationHandler.class)
                    .newInstance(invocationHandler);
        } catch (NoSuchMethodException e) {
            cause = e;
        } catch (IllegalAccessException e) {
            cause = e;
        } catch (InstantiationException e) {
            cause = e;
        } catch (InvocationTargetException e) {
            cause = e;
        }
        AssertionError error = new AssertionError();
        error.initCause(cause);
        throw error;
    }

    /**
     * Indicates whether or not the specified class is a dynamically generated
     * proxy class.
     *
     * @param cl
     *            the class
     * @return {@code true} if the class is a proxy class, {@code false}
     *         otherwise
     * @throws NullPointerException
     *                if the class is {@code null}
     */
    public static boolean isProxyClass(Class<?> cl) {
        return cl.isProxy();
    }

    /**
     * Returns the invocation handler of the specified proxy instance.
     *
     * @param proxy
     *            the proxy instance
     * @return the invocation handler of the specified proxy instance
     * @throws IllegalArgumentException
     *                if the supplied {@code proxy} is not a proxy object
     */
    public static InvocationHandler getInvocationHandler(Object proxy)
            throws IllegalArgumentException {
        // TODO: return false for subclasses of Proxy not created by generateProxy()
        if (!(proxy instanceof Proxy)) {
            throw new IllegalArgumentException("not a proxy instance");
        }
        return ((Proxy) proxy).h;
    }

    private static List<Method> getMethods(Class<?>[] interfaces) {
        List<Method> result = new ArrayList<Method>();
        try {
            result.add(Object.class.getMethod("equals", Object.class));
            result.add(Object.class.getMethod("hashCode", EmptyArray.CLASS));
            result.add(Object.class.getMethod("toString", EmptyArray.CLASS));
        } catch (NoSuchMethodException e) {
            throw new AssertionError();
        }

        getMethodsRecursive(interfaces, result);
        return result;
    }

    /**
     * Fills {@code proxiedMethods} with the methods of {@code interfaces} and
     * the interfaces they extend. May contain duplicates.
     */
    private static void getMethodsRecursive(Class<?>[] interfaces, List<Method> methods) {
        for (Class<?> i : interfaces) {
            getMethodsRecursive(i.getInterfaces(), methods);
            Collections.addAll(methods, i.getDeclaredMethods());
        }
    }

    /**
     * Throws if any two methods in {@code methods} have the same name and
     * parameters but incompatible return types.
     *
     * @param methods the methods to find exceptions for, ordered by name and
     *     signature.
     */
    private static void validateReturnTypes(List<Method> methods) {
        Method vs = null;
        for (Method method : methods) {
            if (vs == null || !vs.equalNameAndParameters(method)) {
                vs = method; // this has a different name or parameters
                continue;
            }
            Class<?> returnType = method.getReturnType();
            Class<?> vsReturnType = vs.getReturnType();
            if (returnType.isInterface() && vsReturnType.isInterface()) {
                // all interfaces are mutually compatible
            } else if (vsReturnType.isAssignableFrom(returnType)) {
                vs = method; // the new return type is a subtype; use it instead
            } else if (!returnType.isAssignableFrom(vsReturnType)) {
                throw new IllegalArgumentException("proxied interface methods have incompatible "
                        + "return types:\n  " + vs + "\n  " + method);
            }
        }
    }

    /**
     * Remove methods that have the same name, parameters and return type. This
     * computes the exceptions of each method; this is the intersection of the
     * exceptions of equivalent methods.
     *
     * @param methods the methods to find exceptions for, ordered by name and
     *     signature.
     */
    private static List<Class<?>[]> deduplicateAndGetExceptions(List<Method> methods) {
        List<Class<?>[]> exceptions = new ArrayList<Class<?>[]>(methods.size());

        for (int i = 0; i < methods.size(); ) {
            Method method = methods.get(i);
            Class<?>[] exceptionTypes = method.getExceptionTypes();

            if (i > 0 && Method.ORDER_BY_SIGNATURE.compare(method, methods.get(i - 1)) == 0) {
                exceptions.set(i - 1, intersectExceptions(exceptions.get(i - 1), exceptionTypes));
                methods.remove(i);
            } else {
                exceptions.add(exceptionTypes);
                i++;
            }
        }
        return exceptions;
    }

    /**
     * Returns the exceptions that are declared in both {@code aExceptions} and
     * {@code bExceptions}. If an exception type in one array is a subtype of an
     * exception from the other, the subtype is included in the intersection.
     */
    private static Class<?>[] intersectExceptions(Class<?>[] aExceptions, Class<?>[] bExceptions) {
        if (aExceptions.length == 0 || bExceptions.length == 0) {
            return EmptyArray.CLASS;
        }
        if (Arrays.equals(aExceptions, bExceptions)) {
            return aExceptions;
        }
        Set<Class<?>> intersection = new HashSet<Class<?>>();
        for (Class<?> a : aExceptions) {
            for (Class<?> b : bExceptions) {
                if (a.isAssignableFrom(b)) {
                    intersection.add(b);
                } else if (b.isAssignableFrom(a)) {
                    intersection.add(a);
                }
            }
        }
        return intersection.toArray(new Class<?>[intersection.size()]);
    }

    private static native Class<?> generateProxy(String name, Class<?>[] interfaces,
                                                 ClassLoader loader, Method[] methods,
                                                 Class<?>[][] exceptions);

    /*
     * The VM clones this method's descriptor when generating a proxy class.
     * There is no implementation.
     */
    private static native void constructorPrototype(InvocationHandler h);

    private static Object invoke(Proxy proxy, Method method, Object[] args) throws Throwable {
        InvocationHandler h = proxy.h;
        return h.invoke(proxy, method, args);
    }
}

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

import java.lang.annotation.Annotation;

/**
 * {@code AccessibleObject} is the superclass of all member reflection classes
 * (Field, Constructor, Method). AccessibleObject provides the ability to toggle
 * a flag controlling access checks for these objects. By default, accessing a
 * member (for example, setting a field or invoking a method) checks the
 * validity of the access (for example, invoking a private method from outside
 * the defining class is prohibited) and throws IllegalAccessException if the
 * operation is not permitted. If the accessible flag is set to true, these
 * checks are omitted. This allows privileged code, such as Java object
 * serialization, object inspectors, and debuggers to have complete access to
 * objects.
 *
 * @see Field
 * @see Constructor
 * @see Method
 */
public class AccessibleObject implements AnnotatedElement {
    protected AccessibleObject() {
    }

    /**
     * If true, object is accessible, bypassing normal access checks
     */
    private boolean flag = false;

    /**
     * Returns true if this object is accessible without access checks.
     */
    public boolean isAccessible() {
        return flag;
    }

    /**
     * Attempts to set the accessible flag. Setting this to true prevents {@code
     * IllegalAccessExceptions}.
     */
    public void setAccessible(boolean flag) {
        try {
          if (equals(Class.class.getDeclaredConstructor())) {
            throw new SecurityException("Can't make class constructor accessible");
          }
        } catch (NoSuchMethodException e) {
          throw new AssertionError("Couldn't find class constructor");
        }
        this.flag = flag;
     }

    /**
     * Attempts to set the accessible flag for all objects in {@code objects}.
     * Setting this to true prevents {@code IllegalAccessExceptions}.
     */
    public static void setAccessible(AccessibleObject[] objects, boolean flag) {
        for (AccessibleObject object : objects) {
            object.flag = flag;
        }
    }

    @Override public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        throw new UnsupportedOperationException();
    }

    @Override public Annotation[] getDeclaredAnnotations() {
        throw new UnsupportedOperationException();
    }

    @Override public Annotation[] getAnnotations() {
        // for all but Class, getAnnotations == getDeclaredAnnotations
        return getDeclaredAnnotations();
    }

    @Override public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
        throw new UnsupportedOperationException();
    }
}

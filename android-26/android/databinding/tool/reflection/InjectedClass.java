/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.databinding.tool.reflection;

import android.databinding.tool.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A class that can be used by ModelAnalyzer without any backing model. This is used
 * for ViewDataBinding subclasses that haven't been generated yet, but we still want
 * to resolve methods and fields for them.
 *
 * @see ModelAnalyzer#injectViewDataBinding(String, Map, Map)
 */
public class InjectedClass extends ModelClass {
    private final String mClassName;
    private final String mSuperClass;
    private final List<InjectedMethod> mMethods = new ArrayList<InjectedMethod>();
    private final List<InjectedField> mFields = new ArrayList<InjectedField>();

    public InjectedClass(String className, String superClass) {
        mClassName = className;
        mSuperClass = superClass;
    }

    public void addField(InjectedField field) {
        mFields.add(field);
    }

    public void addMethod(InjectedMethod method) {
        mMethods.add(method);
    }

    @Override
    public String toJavaCode() {
        return mClassName;
    }

    @Override
    public boolean isArray() {
        return false;
    }

    @Override
    public ModelClass getComponentType() {
        return null;
    }

    @Override
    public boolean isNullable() {
        return true;
    }

    @Override
    public boolean isPrimitive() {
        return false;
    }

    @Override
    public boolean isBoolean() {
        return false;
    }

    @Override
    public boolean isChar() {
        return false;
    }

    @Override
    public boolean isByte() {
        return false;
    }

    @Override
    public boolean isShort() {
        return false;
    }

    @Override
    public boolean isInt() {
        return false;
    }

    @Override
    public boolean isLong() {
        return false;
    }

    @Override
    public boolean isFloat() {
        return false;
    }

    @Override
    public boolean isDouble() {
        return false;
    }

    @Override
    public boolean isGeneric() {
        return false;
    }

    @Override
    public List<ModelClass> getTypeArguments() {
        return null;
    }

    @Override
    public boolean isTypeVar() {
        return false;
    }

    @Override
    public boolean isWildcard() {
        return false;
    }

    @Override
    public boolean isInterface() {
        return false;
    }

    @Override
    public boolean isVoid() {
        return false;
    }

    @Override
    public ModelClass unbox() {
        return this;
    }

    @Override
    public ModelClass box() {
        return this;
    }

    @Override
    public boolean isObservable() {
        return getSuperclass().isObservable();
    }

    @Override
    public boolean isAssignableFrom(ModelClass that) {
        ModelClass superClass = that;
        while (superClass != null && !superClass.isObject()) {
            if (superClass.toJavaCode().equals(mClassName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ModelClass getSuperclass() {
        return ModelAnalyzer.getInstance().findClass(mSuperClass, null);
    }

    @Override
    public ModelClass erasure() {
        return this;
    }

    @Override
    public String getJniDescription() {
        return TypeUtil.getInstance().getDescription(this);
    }

    @Override
    protected ModelField[] getDeclaredFields() {
        ModelClass superClass = getSuperclass();
        final ModelField[] superFields = superClass.getDeclaredFields();
        final int initialCount = superFields.length;
        final int fieldCount = initialCount + mFields.size();
        final ModelField[] fields = Arrays.copyOf(superFields, fieldCount);
        for (int i = 0; i < mFields.size(); i++) {
            fields[i + initialCount] = mFields.get(i);
        }
        return fields;
    }

    @Override
    protected ModelMethod[] getDeclaredMethods() {
        ModelClass superClass = getSuperclass();
        final ModelMethod[] superMethods = superClass.getDeclaredMethods();
        final int initialCount = superMethods.length;
        final int methodCount = initialCount + mMethods.size();
        final ModelMethod[] methods = Arrays.copyOf(superMethods, methodCount);
        for (int i = 0; i < mMethods.size(); i++) {
            methods[i + initialCount] = mMethods.get(i);
        }
        return methods;
    }

    @Override
    public String toString() {
        return "Injected Class: " + mClassName;
    }
}

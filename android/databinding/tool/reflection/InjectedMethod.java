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

import java.util.List;
import java.util.Map;

/**
 * A class that can be used by ModelAnalyzer without any backing model. This is used
 * for methods on ViewDataBinding subclasses that haven't been generated yet.
 *
 * @see ModelAnalyzer#injectViewDataBinding(String, Map, Map)
 */
public class InjectedMethod extends ModelMethod {
    private final InjectedClass mContainingClass;
    private final String mName;
    private final String mReturnTypeName;
    private final String[] mParameterTypeNames;
    private ModelClass[] mParameterTypes;
    private ModelClass mReturnType;
    private boolean mIsStatic;

    public InjectedMethod(InjectedClass containingClass, boolean isStatic, String name,
            String returnType, String... parameters) {
        mContainingClass = containingClass;
        mName = name;
        mIsStatic = isStatic;
        mReturnTypeName = returnType;
        mParameterTypeNames = parameters;
    }

    @Override
    public ModelClass getDeclaringClass() {
        return mContainingClass;
    }

    @Override
    public ModelClass[] getParameterTypes() {
        if (mParameterTypes == null) {
            if (mParameterTypeNames == null) {
                mParameterTypes = new ModelClass[0];
            } else {
                mParameterTypes = new ModelClass[mParameterTypeNames.length];
                ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
                for (int i = 0; i < mParameterTypeNames.length; i++) {
                    mParameterTypes[i] = modelAnalyzer.findClass(mParameterTypeNames[i], null);
                }
            }
        }
        return mParameterTypes;
    }

    @Override
    public String getName() {
        return mName;
    }

    @Override
    public ModelClass getReturnType(List<ModelClass> args) {
        if (mReturnType == null) {
            mReturnType = ModelAnalyzer.getInstance().findClass(mReturnTypeName, null);
        }
        return mReturnType;
    }

    @Override
    public boolean isVoid() {
        return getReturnType().isVoid();
    }

    @Override
    public boolean isPublic() {
        return true;
    }

    @Override
    public boolean isProtected() {
        return false;
    }

    @Override
    public boolean isStatic() {
        return mIsStatic;
    }

    @Override
    public boolean isAbstract() {
        return true;
    }

    @Override
    public boolean isBindable() {
        return false;
    }

    @Override
    public int getMinApi() {
        return 0;
    }

    @Override
    public String getJniDescription() {
        return TypeUtil.getInstance().getDescription(this);
    }

    @Override
    public boolean isVarArgs() {
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("public ");
        if (mIsStatic) {
            sb.append("static ");
        }
        sb.append(mReturnTypeName)
                .append(' ')
                .append(mName)
                .append("(");
        if (mParameterTypeNames != null) {
            for (int i = 0; i < mParameterTypeNames.length; i++) {
                if (i != 0) {
                    sb.append(", ");
                }
                sb.append(mParameterTypeNames[i]);
            }
        }
        sb.append(')');
        return sb.toString();
    }
}

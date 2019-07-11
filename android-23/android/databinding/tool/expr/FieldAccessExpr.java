/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.databinding.tool.expr;

import android.databinding.tool.processing.Scope;
import android.databinding.tool.reflection.Callable;
import android.databinding.tool.reflection.Callable.Type;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.util.L;

import java.util.ArrayList;
import java.util.List;

public class FieldAccessExpr extends Expr {
    String mName;
    Callable mGetter;
    final boolean mIsObservableField;
    private List<ModelMethod> mListenerMethods;
    private List<ModelMethod> mCalledMethods;
    private List<ModelClass> mListenerTypes;
    private List<ModelMethod> mPotentialListeners;

    FieldAccessExpr(Expr parent, String name) {
        super(parent);
        mName = name;
        mIsObservableField = false;
    }

    FieldAccessExpr(Expr parent, String name, boolean isObservableField) {
        super(parent);
        mName = name;
        mIsObservableField = isObservableField;
    }

    public Expr getChild() {
        return getChildren().get(0);
    }

    public Callable getGetter() {
        if (mGetter == null) {
            getResolvedType();
        }
        return mGetter;
    }

    public List<ModelMethod> getListenerMethods() {
        return mListenerMethods;
    }

    public List<ModelMethod> getCalledMethods() {
        return mCalledMethods;
    }

    public List<ModelClass> getListenerTypes() { return mListenerTypes; }

    public boolean isListener() {
        return mListenerMethods != null && !mListenerMethods.isEmpty();
    }

    public int getMinApi() {
        if (isListener()) {
            int minApi = 1;
            for (ModelClass listener : mListenerTypes) {
                int listenerApi = listener.getMinApi();
                minApi = Math.max(minApi, listenerApi);
            }
            return minApi;
        }
        return mGetter.getMinApi();
    }

    @Override
    public boolean isDynamic() {
        if (mGetter == null) {
            getResolvedType();
        }
        if (mGetter == null || mGetter.type == Type.METHOD) {
            return !isListener();
        }
        // if it is static final, gone
        if (getChild().isDynamic()) {
            // if owner is dynamic, then we can be dynamic unless we are static final
            return !mGetter.isStatic() || mGetter.isDynamic();
        }

        // if owner is NOT dynamic, we can be dynamic if an only if getter is dynamic
        return mGetter.isDynamic();
    }

    public boolean hasBindableAnnotations() {
        return mGetter.canBeInvalidated();
    }

    @Override
    public boolean resolveListeners(ModelClass listener) {
        if (mPotentialListeners == null) {
            return false;
        }

        List<ModelMethod> abstractMethods = listener.getAbstractMethods();
        int numberOfAbstractMethods = abstractMethods == null ? 0 : abstractMethods.size();
        if (numberOfAbstractMethods != 1) {
            if (mGetter == null) {
                L.e("Could not find accessor %s.%s and %s has %d abstract methods, so is" +
                                " not resolved as a listener",
                        getChild().getResolvedType().getCanonicalName(), mName,
                        listener.getCanonicalName(), numberOfAbstractMethods);
            }
            return false;
        }

        // See if we've already resolved this listener type
        if (mListenerMethods == null) {
            mListenerMethods = new ArrayList<ModelMethod>();
            mCalledMethods = new ArrayList<ModelMethod>();
            mListenerTypes = new ArrayList<ModelClass>();
        } else {
            for (ModelClass previousListeners : mListenerTypes) {
                if (previousListeners.equals(listener)) {
                    return false;
                }
            }
        }

        // Look for a signature matching the abstract method
        final ModelMethod listenerMethod = abstractMethods.get(0);
        final ModelClass[] listenerParameters = listenerMethod.getParameterTypes();
        for (ModelMethod method : mPotentialListeners) {
            if (acceptsParameters(method, listenerParameters)) {
                mListenerTypes.add(listener);
                mListenerMethods.add(listenerMethod);
                mCalledMethods.add(method);
                resetResolvedType();
                return true;
            }
        }

        if (mGetter == null) {
            L.e("Listener class %s with method %s did not match signature of any method %s.%s",
                    listener.getCanonicalName(), listenerMethod.getName(),
                    getChild().getResolvedType().getCanonicalName(), mName);
        }
        return false;
    }

    private boolean acceptsParameters(ModelMethod method, ModelClass[] listenerParameters) {
        ModelClass[] parameters = method.getParameterTypes();
        if (parameters.length != listenerParameters.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            if (!parameters[i].isAssignableFrom(listenerParameters[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected List<Dependency> constructDependencies() {
        final List<Dependency> dependencies = constructDynamicChildrenDependencies();
        for (Dependency dependency : dependencies) {
            if (dependency.getOther() == getChild()) {
                dependency.setMandatory(true);
            }
        }
        return dependencies;
    }

    @Override
    protected String computeUniqueKey() {
        if (mIsObservableField) {
            return join(mName, "..", super.computeUniqueKey());
        }
        return join(mName, ".", super.computeUniqueKey());
    }

    public String getName() {
        return mName;
    }

    @Override
    public void updateExpr(ModelAnalyzer modelAnalyzer) {
        try {
            Scope.enter(this);
            resolveType(modelAnalyzer);
            super.updateExpr(modelAnalyzer);
        } finally {
            Scope.exit();
        }
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        if (mGetter == null) {
            if (mPotentialListeners != null) {
                return modelAnalyzer.findClass(Object.class);
            }
            Expr child = getChild();
            child.getResolvedType();
            boolean isStatic = child instanceof StaticIdentifierExpr;
            ModelClass resolvedType = child.getResolvedType();
            L.d("resolving %s. Resolved class type: %s", this, resolvedType);

            mGetter = resolvedType.findGetterOrField(mName, isStatic);
            mPotentialListeners = resolvedType.findMethods(mName, isStatic);

            if (mGetter == null) {
                if (mPotentialListeners == null) {
                    L.e("Could not find accessor %s.%s", resolvedType.getCanonicalName(), mName);
                }
                return modelAnalyzer.findClass(Object.class);
            }

            if (mGetter.isStatic() && !isStatic) {
                // found a static method on an instance. register a new one
                child.getParents().remove(this);
                getChildren().remove(child);
                StaticIdentifierExpr staticId = getModel().staticIdentifierFor(resolvedType);
                getChildren().add(staticId);
                staticId.getParents().add(this);
                child = getChild(); // replace the child for the next if stmt
            }

            if (mGetter.resolvedType.isObservableField()) {
                // Make this the ".get()" and add an extra field access for the observable field
                child.getParents().remove(this);
                getChildren().remove(child);

                FieldAccessExpr observableField = getModel().observableField(child, mName);
                observableField.mGetter = mGetter;

                getChildren().add(observableField);
                observableField.getParents().add(this);
                mGetter = mGetter.resolvedType.findGetterOrField("get", false);
                mName = "";
            }
        }
        if (isListener()) {
            return modelAnalyzer.findClass(Object.class);
        }
        return mGetter.resolvedType;
    }

    @Override
    protected String asPackage() {
        String parentPackage = getChild().asPackage();
        return parentPackage == null ? null : parentPackage + "." + mName;
    }
}

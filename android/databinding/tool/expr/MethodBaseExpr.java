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

package android.databinding.tool.expr;

import android.databinding.tool.processing.Scope;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.solver.ExecutionPath;

import java.util.ArrayList;
import java.util.List;

public abstract class MethodBaseExpr extends Expr {
    String mName;

    MethodBaseExpr(Expr parent, String name) {
        super(parent);
        mName = name;
    }

    public Expr getTarget() {
        return getChildren().get(0);
    }

    @Override
    public List<ExecutionPath> toExecutionPath(List<ExecutionPath> paths) {
        final List<ExecutionPath> targetPaths = getTarget().toExecutionPath(paths);
        // after this, we need a null check.
        List<ExecutionPath> result = new ArrayList<ExecutionPath>();
        if (getTarget() instanceof StaticIdentifierExpr) {
            result.addAll(toExecutionPathInOrder(paths, getTarget()));
        } else {
            for (ExecutionPath path : targetPaths) {
                final ComparisonExpr cmp = getModel()
                        .comparison("!=", getTarget(), getModel().symbol("null", Object.class));
                path.addPath(cmp);
                final ExecutionPath subPath = path.addBranch(cmp, true);
                if (subPath != null) {
                    subPath.addPath(this);
                    result.add(subPath);
                }
            }
        }
        return result;
    }

    protected Expr resolveListenersAsMethodReference(ModelClass listener, Expr parent) {
        final Expr target = getTarget();
        final ModelClass childType = target.getResolvedType();
        if (listener == null) {
            throw new IllegalStateException(
                    String.format("Could not resolve %s as a listener.", this));
        }

        List<ModelMethod> abstractMethods = listener.getAbstractMethods();
        int numberOfAbstractMethods = abstractMethods == null ? 0 : abstractMethods.size();
        if (numberOfAbstractMethods != 1) {
            throw new IllegalStateException(String.format(
                    "Could not find accessor %s.%s and %s has %d abstract methods, so is" +
                            " not resolved as a listener",
                    childType.getCanonicalName(), mName,
                    listener.getCanonicalName(), numberOfAbstractMethods));
        }

        // Look for a signature matching the abstract method
        final ModelMethod listenerMethod = abstractMethods.get(0);
        final ModelClass[] listenerParameters = listenerMethod.getParameterTypes();
        boolean isStatic = getTarget() instanceof StaticIdentifierExpr;
        List<ModelMethod> methods = childType.findMethods(mName, isStatic);
        for (ModelMethod method : methods) {
            if (acceptsParameters(method, listenerParameters) &&
                    method.getReturnType(null).equals(listenerMethod.getReturnType(null))) {
                target.getParents().remove(this);
                resetResolvedType();
                // replace this with ListenerExpr in parent
                Expr listenerExpr = getModel().listenerExpr(getTarget(), mName, listener,
                        listenerMethod);
                if (parent != null) {
                    int index;
                    while ((index = parent.getChildren().indexOf(this)) != -1) {
                        parent.getChildren().set(index, listenerExpr);
                    }
                }
                if (getModel().mBindingExpressions.contains(this)) {
                    getModel().bindingExpr(listenerExpr);
                }
                getParents().remove(parent);
                if (getParents().isEmpty()) {
                    getModel().removeExpr(this);
                }
                return listenerExpr;
            }
        }

        throw new IllegalStateException(String.format(
                "Listener class %s with method %s did not match signature of any method %s",
                listener.getCanonicalName(), listenerMethod.getName(), this));
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
            if (dependency.getOther() == getTarget()) {
                dependency.setMandatory(true);
            }
        }
        return dependencies;
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
}

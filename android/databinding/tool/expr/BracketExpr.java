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

import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.solver.ExecutionPath;
import android.databinding.tool.writer.KCode;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

public class BracketExpr extends Expr {

    public enum BracketAccessor {
        ARRAY,
        LIST,
        MAP,
    }

    private BracketAccessor mAccessor;

    BracketExpr(Expr target, Expr arg) {
        super(target, arg);
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        ModelClass targetType = getTarget().getResolvedType();
        if (targetType.isArray()) {
            mAccessor = BracketAccessor.ARRAY;
        } else if (targetType.isList()) {
            mAccessor = BracketAccessor.LIST;
        } else if (targetType.isMap()) {
            mAccessor = BracketAccessor.MAP;
        } else {
            throw new IllegalArgumentException("Cannot determine variable type used in [] " +
                    "expression. Cast the value to List, Map, " +
                    "or array. Type detected: " + targetType.toJavaCode());
        }
        return targetType.getComponentType();
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
                Expr cmp = getModel().comparison("!=", getTarget(),
                        getModel().symbol("null", Object.class));
                path.addPath(cmp);
                final ExecutionPath subPath = path.addBranch(cmp, true);
                if (subPath != null) {
                    final List<ExecutionPath> argPath = getArg().toExecutionPath(subPath);
                    result.addAll(addJustMeToExecutionPath(argPath));
                }
            }
        }
        return result;
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

    protected String computeUniqueKey() {
        final String targetKey = getTarget().computeUniqueKey();
        return join(targetKey, "$", getArg().computeUniqueKey(), "$");
    }

    @Override
    public String getInvertibleError() {
        return null;
    }

    public Expr getTarget() {
        return getChildren().get(0);
    }

    public Expr getArg() {
        return getChildren().get(1);
    }

    public BracketAccessor getAccessor() {
        return mAccessor;
    }

    public boolean argCastsInteger() {
        return mAccessor != BracketAccessor.MAP && getArg().getResolvedType().isObject();
    }

    @Override
    protected KCode generateCode() {
        String cast = argCastsInteger() ? "(Integer) " : "";
        switch (getAccessor()) {
            case ARRAY: {
                return new KCode().
                        app("getFromArray(", getTarget().toCode()).
                        app(", ").
                        app(cast, getArg().toCode()).app(")");
            }
            case LIST: {
                ModelClass listType = ModelAnalyzer.getInstance().findClass(java.util.List.class).
                        erasure();
                ModelClass targetType = getTarget().getResolvedType().erasure();
                if (listType.isAssignableFrom(targetType)) {
                    return new KCode().
                            app("getFromList(", getTarget().toCode()).
                            app(", ").
                            app(cast, getArg().toCode()).
                            app(")");
                } else {
                    return new KCode().
                            app("", getTarget().toCode()).
                            app(".get(").
                            app(cast, getArg().toCode()).
                            app(")");
                }
            }
            case MAP:
                return new KCode().
                        app("", getTarget().toCode()).
                        app(".get(", getArg().toCode()).
                        app(")");
        }
        throw new IllegalStateException("Invalid BracketAccessor type");
    }

    @Override
    public Expr generateInverse(ExprModel model, Expr value, String bindingClassName) {
        Expr arg = getArg().cloneToModel(model);
        arg = argCastsInteger()
                ? model.castExpr("int", model.castExpr("Integer", arg))
                : arg;
        StaticIdentifierExpr viewDataBinding =
                model.staticIdentifier(ModelAnalyzer.VIEW_DATA_BINDING);
        viewDataBinding.setUserDefinedType(ModelAnalyzer.VIEW_DATA_BINDING);
        ModelClass targetType = getTarget().getResolvedType();
        if ((targetType.isList() || targetType.isMap()) &&
                value.getResolvedType().isPrimitive()) {
            ModelClass boxed = value.getResolvedType().box();
            value = model.castExpr(boxed.toJavaCode(), value);
        }
        List<Expr> args = Lists.newArrayList(getTarget().cloneToModel(model), arg, value);
        MethodCallExpr setter = model.methodCall(viewDataBinding, "setTo", args);
        setter.setAllowProtected();
        return setter;
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return model.bracketExpr(getTarget().cloneToModel(model), getArg().cloneToModel(model));
    }

    @Override
    public String toString() {
        return getTarget().toString() + '[' + getArg() + ']';
    }
}

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

import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.solver.ExecutionPath;
import android.databinding.tool.writer.KCode;

import java.util.ArrayList;
import java.util.List;

/**
 * This is used by inverse field access expressions to assign back to the field.
 * For example, <code>&commat;={a.b}</code> is inverted to @{code a.b = value;}
 */
public class FieldAssignmentExpr extends Expr {
    final String mName;

    public FieldAssignmentExpr(Expr target, String name, Expr value) {
        super(target, value);
        mName = name;
    }

    @Override
    protected String computeUniqueKey() {
        return join(getTarget().getUniqueKey(), mName, "=", getValueExpr().getUniqueKey());
    }

    public Expr getTarget() {
        return (FieldAccessExpr) getChildren().get(0);
    }

    public Expr getValueExpr() {
        return getChildren().get(1);
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        return modelAnalyzer.findClass(void.class);
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return constructDynamicChildrenDependencies();
    }

    @Override
    protected KCode generateCode() {
        return new KCode()
                .app("", getTarget().toCode())
                .app("." + mName + " = ", getValueExpr().toCode());
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return model.assignment(getTarget().cloneToModel(model), mName, getValueExpr());
    }

    @Override
    protected String getInvertibleError() {
        return "Assignment expressions are inverses of field access expressions.";
    }

    @Override
    public List<ExecutionPath> toExecutionPath(List<ExecutionPath> paths) {
        Expr child = getTarget();
        List<ExecutionPath> targetPaths = child.toExecutionPath(paths);

        // after this, we need a null check.
        List<ExecutionPath> result = new ArrayList<ExecutionPath>();
        if (child instanceof StaticIdentifierExpr) {
            result.addAll(toExecutionPathInOrder(paths, child));
        } else {
            for (ExecutionPath path : targetPaths) {
                final ComparisonExpr cmp = getModel()
                        .comparison("!=", child, getModel().symbol("null", Object.class));
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

    @Override
    public String toString() {
        return getTarget().toString() + '.' + mName + " = " + getValueExpr();
    }
}

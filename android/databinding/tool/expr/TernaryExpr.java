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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class TernaryExpr extends Expr {

    TernaryExpr(Expr pred, Expr ifTrue, Expr ifFalse) {
        super(pred, ifTrue, ifFalse);
    }

    public Expr getPred() {
        return getChildren().get(0);
    }

    public Expr getIfTrue() {
        return getChildren().get(1);
    }

    public Expr getIfFalse() {
        return getChildren().get(2);
    }

    @Override
    protected String computeUniqueKey() {
        return "?:" + super.computeUniqueKey();
    }

    @Override
    public String getInvertibleError() {
        if (getPred().isDynamic()) {
            return "The condition of a ternary operator must be constant: " +
                    getPred().toFullCode();
        }
        final String trueInvertible = getIfTrue().getInvertibleError();
        if (trueInvertible != null) {
            return trueInvertible;
        } else {
            return getIfFalse().getInvertibleError();
        }
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        final Expr ifTrue = getIfTrue();
        final Expr ifFalse = getIfFalse();
        if (isNullLiteral(ifTrue)) {
            return ifFalse.getResolvedType();
        } else if (isNullLiteral(ifFalse)) {
            return ifTrue.getResolvedType();
        }
        return modelAnalyzer.findCommonParentOf(getIfTrue().getResolvedType(),
                getIfFalse().getResolvedType());
    }

    private static boolean isNullLiteral(Expr expr) {
        final ModelClass type = expr.getResolvedType();
        return (type.isObject() && (expr instanceof SymbolExpr) &&
                "null".equals(((SymbolExpr) expr).getText()));
    }

    @Override
    protected List<Dependency> constructDependencies() {
        List<Dependency> deps = new ArrayList<Dependency>();
        Expr predExpr = getPred();
        final Dependency pred = new Dependency(this, predExpr);
        pred.setMandatory(true);
        deps.add(pred);

        Expr ifTrueExpr = getIfTrue();
        if (ifTrueExpr.isDynamic()) {
            deps.add(new Dependency(this, ifTrueExpr, predExpr, true));
        }
        Expr ifFalseExpr = getIfFalse();
        if (ifFalseExpr.isDynamic()) {
            deps.add(new Dependency(this, ifFalseExpr, predExpr, false));
        }
        return deps;
    }

    @Override
    public List<ExecutionPath> toExecutionPath(List<ExecutionPath> paths) {
        List<ExecutionPath> executionPaths = getPred().toExecutionPath(paths);
        // now optionally add others
        List<ExecutionPath> result = new ArrayList<ExecutionPath>();
        for (ExecutionPath path : executionPaths) {
            ExecutionPath ifTrue = path.addBranch(getPred(), true);
            if (ifTrue != null) {
                result.addAll(getIfTrue().toExecutionPath(ifTrue));
            }
            ExecutionPath ifFalse = path.addBranch(getPred(), false);
            if (ifFalse != null) {
                result.addAll(getIfFalse().toExecutionPath(ifFalse));
            }
        }
        return addJustMeToExecutionPath(result);
    }

    @Override
    protected BitSet getPredicateInvalidFlags() {
        return getPred().getInvalidFlags();
    }

    @Override
    protected KCode generateCode() {
        return new KCode()
                .app("(", getPred().toCode())
                .app(") ? (", getIfTrue().toCode())
                .app(") : (", getIfFalse().toCode())
                .app(")");
    }

    @Override
    public Expr generateInverse(ExprModel model, Expr value, String bindingClassName) {
        final Expr pred = getPred().cloneToModel(model);
        final Expr ifTrue = getIfTrue().generateInverse(model, value, bindingClassName);
        final Expr ifFalse = getIfFalse().generateInverse(model, value, bindingClassName);
        return model.ternary(pred, ifTrue, ifFalse);
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return model.ternary(getPred().cloneToModel(model), getIfTrue().cloneToModel(model),
                getIfFalse().cloneToModel(model));
    }

    @Override
    public boolean isConditional() {
        return true;
    }

    @Override
    public String toString() {
        return getPred().toString() + " ? " + getIfTrue() + " : " + getIfFalse();
    }
}

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
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.writer.KCode;

import com.google.common.collect.Lists;

import java.util.List;

public class MathExpr extends Expr {
    static final String DYNAMIC_UTIL = "android.databinding.DynamicUtil";
    final String mOp;

    MathExpr(Expr left, String op, Expr right) {
        super(left, right);
        mOp = op;
    }

    @Override
    protected String computeUniqueKey() {
        return join(getLeft().getUniqueKey(), mOp, getRight().getUniqueKey());
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        if ("+".equals(mOp)) {
            // TODO we need upper casting etc.
            if (getLeft().getResolvedType().isString()
                    || getRight().getResolvedType().isString()) {
                return modelAnalyzer.findClass(String.class);
            }
        }
        return modelAnalyzer.findCommonParentOf(getLeft().getResolvedType(),
                getRight().getResolvedType());
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return constructDynamicChildrenDependencies();
    }

    public Expr getLeft() {
        return getChildren().get(0);
    }

    public Expr getRight() {
        return getChildren().get(1);
    }

    @Override
    protected KCode generateCode() {
        return new KCode().app("(", getLeft().toCode())
                .app(") ")
                .app(mOp)
                .app(" (", getRight().toCode())
                .app(")");
    }

    @Override
    public String getInvertibleError() {
        if (mOp.equals("%")) {
            return "The modulus operator (%) is not supported in two-way binding.";
        }

        final Expr left = getLeft();
        final Expr right = getRight();
        if (left.isDynamic() == right.isDynamic()) {
            return "Two way binding with operator " + mOp +
                    " supports only a single dynamic expressions.";
        }
        Expr dyn = left.isDynamic() ? left : right;
        if (getResolvedType().isString()) {
            Expr constExpr = left.isDynamic() ? right : left;

            if (!(constExpr instanceof SymbolExpr) ||
                    !"\"\"".equals(((SymbolExpr) constExpr).getText())) {
                return "Two-way binding with string concatenation operator (+) only supports the" +
                        " empty string constant (`` or \"\")";
            }
            if (!dyn.getResolvedType().unbox().isPrimitive()) {
                return "Two-way binding with string concatenation operator (+) only supports " +
                        "primitives";
            }
        }
        return dyn.getInvertibleError();
    }

    @Override
    public Expr generateInverse(ExprModel model, Expr value, String bindingClassName) {
        final Expr left = getLeft();
        final Expr right = getRight();
        Preconditions.check(left.isDynamic() ^ right.isDynamic(), "Two-way binding of a math " +
                "operations requires A single dynamic expression. Neither or both sides are " +
                "dynamic: (%s) %s (%s)", left, mOp, right);
        final Expr constExpr = (left.isDynamic() ? right : left).cloneToModel(model);
        final Expr varExpr = left.isDynamic() ? left : right;
        final Expr newValue;
        switch (mOp.charAt(0)) {
            case '+': // const + x = value  => x = value - const
                if (getResolvedType().isString()) {
                    // just convert back to the primitive type
                    newValue = parseInverse(model, value, varExpr);
                } else {
                    newValue = model.math(value, "-", constExpr);
                }
                break;
            case '*': // const * x = value => x = value / const
                newValue = model.math(value, "/", constExpr);
                break;
            case '-':
                if (!left.isDynamic()) { // const - x = value => x = const - (value)
                    newValue = model.math(constExpr, "-", value);
                } else { // x - const = value => x = value + const)
                    newValue = model.math(value, "+", constExpr);
                }
                break;
            case '/':
                if (!left.isDynamic()) { // const / x = value => x = const / value
                    newValue = model.math(constExpr, "/", value);
                } else { // x / const = value => x = value * const
                    newValue = model.math(value, "*", constExpr);
                }
                break;
            default:
                throw new IllegalStateException("Invalid math operation is not invertible: " + mOp);
        }
        return varExpr.generateInverse(model, newValue, bindingClassName);
    }

    private Expr parseInverse(ExprModel model, Expr value, Expr prev) {
        IdentifierExpr dynamicUtil = model.staticIdentifier(DYNAMIC_UTIL);
        dynamicUtil.setUserDefinedType(DYNAMIC_UTIL);

        return model.methodCall(dynamicUtil, "parse", Lists.newArrayList(value, prev));
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return model.math(getLeft().cloneToModel(model), mOp, getRight().cloneToModel(model));
    }

    @Override
    public String toString() {
        return "(" + getLeft() + ") " + mOp + " (" + getRight() + ")";
    }
}

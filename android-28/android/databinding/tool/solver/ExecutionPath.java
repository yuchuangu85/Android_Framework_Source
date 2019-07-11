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
package android.databinding.tool.solver;

import android.databinding.tool.expr.Expr;
import android.databinding.tool.util.Preconditions;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents all possible outcomes of an expressions with its branching.
 */
public class ExecutionPath {
    @Nullable //null for root and branches
    private final Expr mExpr;
    @NotNull
    private List<ExecutionPath> mChildren = new ArrayList<ExecutionPath>();

    @Nullable
    private ExecutionBranch mTrueBranch;

    @Nullable
    private ExecutionBranch mFalseBranch;

    // values that we know due to branching
    private Map<Expr, Boolean> mKnownValues = new HashMap<Expr, Boolean>();

    // expressions that are available right now
    private Set<Expr> mScopeExpressions = new HashSet<Expr>();

    private final boolean mIsAlreadyEvaluated;

    public static ExecutionPath createRoot() {
        return new ExecutionPath(null, false);
    }

    private ExecutionPath(@Nullable Expr expr, boolean isAlreadyEvaluated) {
        mExpr = expr;
        mIsAlreadyEvaluated = isAlreadyEvaluated;
    }

    @Nullable
    public ExecutionPath addBranch(Expr pred, boolean expectedValue) {
        // TODO special predicates like Symbol(true, false)
        Preconditions.checkNull(expectedValue ? mTrueBranch : mFalseBranch,
                "Cannot add two " + expectedValue + "branches");
        final Boolean knownValue = mKnownValues.get(pred);
        if (knownValue != null) {
            // we know the result. cut the branch
            if (expectedValue == knownValue) {
                // just add as a path
                return addPath(null);
            } else {
                // drop path. this cannot happen
                return null;
            }
        } else {
            ExecutionPath path = createPath(null);
            ExecutionBranch edge = new ExecutionBranch(path, pred, expectedValue);
            path.mKnownValues.put(pred, expectedValue);
            if (expectedValue) {
                if (mFalseBranch != null) {
                    Preconditions.check(mFalseBranch.getConditional() == pred, "Cannot add"
                            + " branches w/ different conditionals.");
                }
                mTrueBranch = edge;
            } else {
                if (mTrueBranch != null) {
                    Preconditions.check(mTrueBranch.getConditional() == pred, "Cannot add"
                            + " branches w/ different conditionals.");
                }
                mFalseBranch = edge;
            }
            return path;
        }
    }

    private ExecutionPath createPath(@Nullable Expr expr) {
        ExecutionPath path = new ExecutionPath(expr, expr == null ||
                mScopeExpressions.contains(expr));
        // now pass down all values etc
        path.mKnownValues.putAll(mKnownValues);
        path.mScopeExpressions.addAll(mScopeExpressions);
        return path;
    }

    @NotNull
    public ExecutionPath addPath(@Nullable Expr expr) {
        Preconditions.checkNull(mFalseBranch, "Cannot add path after branches are set");
        Preconditions.checkNull(mTrueBranch, "Cannot add path after branches are set");
        final ExecutionPath path = createPath(expr);
        if (expr != null) {
            mScopeExpressions.add(expr);
            path.mScopeExpressions.add(expr);
        }
        mChildren.add(path);
        return path;
    }

    public void debug(StringBuilder builder, int offset) {
        offset(builder, offset);
        if (mExpr != null || !mIsAlreadyEvaluated) {
            builder.append("expr:").append(mExpr == null ? "root" : mExpr.getUniqueKey());
            builder.append(" isRead:").append(mIsAlreadyEvaluated);
        } else {
            builder.append("branch");
        }
        if (!mKnownValues.isEmpty()) {
            builder.append(" I know:");
            for (Map.Entry<Expr, Boolean> entry : mKnownValues.entrySet()) {
                builder.append(" ");
                builder.append(entry.getKey().getUniqueKey());
                builder.append(" is ").append(entry.getValue());
            }
        }
        for (ExecutionPath path : mChildren) {
            builder.append("\n");
            path.debug(builder, offset);
        }
        if (mTrueBranch != null) {
            debug(builder, mTrueBranch, offset);
        }
        if (mFalseBranch != null) {
            debug(builder, mFalseBranch, offset);
        }
    }

    @Nullable
    public Expr getExpr() {
        return mExpr;
    }

    @NotNull
    public List<ExecutionPath> getChildren() {
        return mChildren;
    }

    @Nullable
    public ExecutionBranch getTrueBranch() {
        return mTrueBranch;
    }

    @Nullable
    public ExecutionBranch getFalseBranch() {
        return mFalseBranch;
    }

    public boolean isAlreadyEvaluated() {
        return mIsAlreadyEvaluated;
    }

    private void debug(StringBuilder builder, ExecutionBranch branch, int offset) {
        builder.append("\n");
        offset(builder, offset);
        builder.append("if ")
                .append(branch.getConditional().getUniqueKey())
                .append(" is ").append(branch.getExpectedCondition()).append("\n");
        branch.getPath().debug(builder, offset + 1);
    }

    private void offset(StringBuilder builder, int offset) {
        for (int i = 0; i < offset; i++) {
            builder.append("  ");
        }
    }

    public Map<Expr, Boolean> getKnownValues() {
        return mKnownValues;
    }
}

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

import org.jetbrains.annotations.NotNull;

/**
 * Represents if statements in the execution.
 */
public class ExecutionBranch {

    @NotNull
    private Expr mConditional;

    private final boolean mExpectedCondition;

    @NotNull
    private final ExecutionPath mPath;

    public ExecutionBranch(@NotNull ExecutionPath path, @NotNull Expr conditional,
            boolean expectedCondition) {
        mConditional = conditional;
        mExpectedCondition = expectedCondition;
        mPath = path;
    }

    @NotNull
    public Expr getConditional() {
        return mConditional;
    }

    public boolean getExpectedCondition() {
        return mExpectedCondition;
    }

    @NotNull
    public ExecutionPath getPath() {
        return mPath;
    }
}

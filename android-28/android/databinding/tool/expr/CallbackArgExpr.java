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

import android.databinding.tool.CallbackWrapper;
import android.databinding.tool.processing.ErrorMessages;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.writer.KCode;

import java.util.Collections;
import java.util.List;

/**
 * This expressions that are used to reference arguments in callbacks.
 * <p
 * While the callback is being parsed, they get whatever the variable user defined in the lambda.
 * When the code is being generated, they get simple enumarated names so that multiple callbacks
 * can be handled in the same method.
 */
public class CallbackArgExpr extends IdentifierExpr {

    private int mArgIndex;

    private String mName;

    private ModelClass mClassFromCallback;

    public CallbackArgExpr(int argIndex, String name) {
        super(name);
        mArgIndex = argIndex;
        mName = name;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    public void setClassFromCallback(ModelClass modelClass) {
        mClassFromCallback = modelClass;
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        Preconditions
                .checkNotNull(mClassFromCallback, ErrorMessages.UNDEFINED_CALLBACK_ARGUMENT, mName);
        return mClassFromCallback;
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return Collections.emptyList();
    }

    @Override
    protected KCode generateCode() {
        return new KCode(CallbackWrapper.ARG_PREFIX + mArgIndex);
    }

    @Override
    protected String computeUniqueKey() {
        return CallbackWrapper.ARG_PREFIX + mArgIndex;
    }

    @Override
    public String getInvertibleError() {
        return "Callback arguments cannot be inverted";
    }

    public String getName() {
        return mName;
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return new CallbackArgExpr(mArgIndex, mName);
    }
}

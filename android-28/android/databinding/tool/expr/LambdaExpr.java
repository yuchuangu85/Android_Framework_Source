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
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.solver.ExecutionPath;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.writer.KCode;
import android.databinding.tool.writer.LayoutBinderWriterKt;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LambdaExpr extends Expr {
    private static AtomicInteger sIdCounter = new AtomicInteger();
    private final int mId = sIdCounter.incrementAndGet();
    private CallbackWrapper mCallbackWrapper;
    // set when Binding resolves the receiver
    private final CallbackExprModel mCallbackExprModel;
    private int mCallbackId;
    private ExecutionPath mExecutionPath;

    public LambdaExpr(Expr expr, CallbackExprModel callbackExprModel) {
        super(expr);
        mCallbackExprModel = callbackExprModel;
    }

    public Expr getExpr() {
        return getChildren().get(0);
    }

    public CallbackExprModel getCallbackExprModel() {
        return mCallbackExprModel;
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        Preconditions.checkNotNull(mCallbackWrapper, "Lambda expression must be resolved to its"
                + " setter first to get the type.");
        return mCallbackWrapper.klass;
    }

    @Override
    protected List<Dependency> constructDependencies() {
        return Collections.emptyList();
    }

    public CallbackWrapper getCallbackWrapper() {
        return mCallbackWrapper;
    }

    @Override
    public Expr resolveListeners(ModelClass valueType, Expr parent) {
        return this;
    }

    @Override
    protected String computeUniqueKey() {
        return "callback" + mId;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    protected KCode generateCode() {
        Preconditions
                .checkNotNull(mCallbackWrapper, "Cannot find the callback method for %s", this);
        KCode code = new KCode("");
        final int minApi = mCallbackWrapper.getMinApi();
        final String fieldName = LayoutBinderWriterKt.getFieldName(this);
        if (minApi > 1) {
            code.app("(getBuildSdkInt() < " + minApi + " ? null : ").app(fieldName).app(")");
        } else {
            code.app(fieldName);
        }
        return code;
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return model.lambdaExpr(getExpr().cloneToModel(model), (CallbackExprModel) model);
    }

    public String generateConstructor() {
        return getCallbackWrapper().constructForIdentifier(mCallbackId);
    }

    @Override
    public void markAsUsed() {
        super.markAsUsed();
    }

    @Override
    protected String getInvertibleError() {
        return "Lambda expressions cannot be inverted";
    }

    @Override
    public List<ExecutionPath> toExecutionPath(List<ExecutionPath> paths) {
        // i'm not involved.
        throw new UnsupportedOperationException("should not call toExecutionPath on a lambda"
                + " expression");
    }

    public final ExecutionPath getExecutionPath() {
        return mExecutionPath;
    }

    public int getCallbackId() {
        return mCallbackId;
    }

    public void setup(ModelClass klass, ModelMethod method, int callbackId) {
        mCallbackId = callbackId;
        mCallbackWrapper = getModel().callbackWrapper(klass, method);
        // now register the arguments as variables.
        final ModelClass[] parameterTypes = method.getParameterTypes();
        final List<CallbackArgExpr> args = mCallbackExprModel.getArguments();
        if (parameterTypes.length == args.size()) {
            for (int i = 0; i < parameterTypes.length; i++) {
                args.get(i).setClassFromCallback(parameterTypes[i]);
            }
        }
        // first convert to execution path because we may add additional expressions
        mExecutionPath = ExecutionPath.createRoot();
        getExpr().toExecutionPath(mExecutionPath);
        mCallbackExprModel.seal();
    }
}

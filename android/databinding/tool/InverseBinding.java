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

package android.databinding.tool;

import android.databinding.tool.expr.CallbackArgExpr;
import android.databinding.tool.expr.CallbackExprModel;
import android.databinding.tool.expr.Expr;
import android.databinding.tool.expr.ExprModel;
import android.databinding.tool.expr.FieldAccessExpr;
import android.databinding.tool.expr.IdentifierExpr;
import android.databinding.tool.processing.ErrorMessages;
import android.databinding.tool.processing.Scope;
import android.databinding.tool.processing.scopes.LocationScopeProvider;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.solver.ExecutionPath;
import android.databinding.tool.store.Location;
import android.databinding.tool.store.SetterStore;
import android.databinding.tool.store.SetterStore.BindingGetterCall;
import android.databinding.tool.store.SetterStore.BindingSetterCall;
import android.databinding.tool.util.L;

import java.util.ArrayList;
import java.util.List;

public class InverseBinding implements LocationScopeProvider {

    private final String mName;
    private final Expr mExpr;
    private final BindingTarget mTarget;
    private BindingGetterCall mGetterCall;
    private final ArrayList<FieldAccessExpr> mChainedExpressions = new ArrayList<FieldAccessExpr>();
    private final CallbackExprModel mCallbackExprModel;
    private final Expr mInverseExpr;
    private final CallbackArgExpr mVariableExpr;
    private final ExecutionPath mExecutionPath;

    public InverseBinding(BindingTarget target, String name, Expr expr, String bindingClassName) {
        mTarget = target;
        mName = name;
        mCallbackExprModel = new CallbackExprModel(expr.getModel());
        mExpr = expr.cloneToModel(mCallbackExprModel);
        setGetterCall(mExpr);
        mVariableExpr = mCallbackExprModel.callbackArg("callbackArg_0");
        ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
        ModelClass type = modelAnalyzer.findClass(getGetterCall().getGetterType(), null);
        mVariableExpr.setClassFromCallback(type);
        mVariableExpr.setUserDefinedType(getGetterCall().getGetterType());
        mInverseExpr =
                mExpr.generateInverse(mCallbackExprModel, mVariableExpr, bindingClassName);
        mExecutionPath = ExecutionPath.createRoot();
        mInverseExpr.toExecutionPath(mExecutionPath);
        mCallbackExprModel.seal();
    }

    public InverseBinding(BindingTarget target, String name, BindingGetterCall getterCall) {
        mTarget = target;
        mName = name;
        mExpr = null;
        mCallbackExprModel = null;
        mInverseExpr = null;
        mVariableExpr = null;
        mExecutionPath = null;
        setGetterCall(getterCall);
    }

    @Override
    public List<Location> provideScopeLocation() {
        if (mExpr != null) {
            return mExpr.getLocations();
        } else {
            return mChainedExpressions.get(0).getLocations();
        }
    }

    private void setGetterCall(BindingGetterCall getterCall) {
        mGetterCall = getterCall;
    }

    public void addChainedExpression(FieldAccessExpr expr) {
        mChainedExpressions.add(expr);
    }

    public boolean isOnBinder() {
        return mTarget.getResolvedType().isViewDataBinding();
    }

    private void setGetterCall(Expr expr) {
        try {
            Scope.enter(mTarget);
            Scope.enter(this);
            ModelClass viewType = mTarget.getResolvedType();
            final SetterStore setterStore = SetterStore.get(ModelAnalyzer.getInstance());
            final ModelClass resolvedType = expr == null ? null : expr.getResolvedType();
            mGetterCall = setterStore.getGetterCall(mName, viewType, resolvedType,
                    expr.getModel().getImports());
            if (mGetterCall == null) {
                L.e(ErrorMessages.CANNOT_FIND_GETTER_CALL, mName,
                        expr == null ? "Unknown" : mExpr.getResolvedType(),
                        mTarget.getResolvedType());
            }
        } finally {
            Scope.exit();
            Scope.exit();
        }
    }

    public SetterStore.BindingGetterCall getGetterCall() {
        return mGetterCall;
    }

    public BindingTarget getTarget() {
        return mTarget;
    }

    public Expr getExpr() {
        return mExpr;
    }

    public Expr getInverseExpr() {
        return mInverseExpr;
    }

    public IdentifierExpr getVariableExpr() {
        return mVariableExpr;
    }

    public ExecutionPath getExecutionPath() {
        return mExecutionPath;
    }

    public CallbackExprModel getCallbackExprModel() {
        return mCallbackExprModel;
    }

    public List<FieldAccessExpr> getChainedExpressions() {
        return mChainedExpressions;
    }

    public String getBindingAdapterInstanceClass() {
        return getGetterCall().getBindingAdapterInstanceClass();
    }

    /**
     * The min api level in which this binding should be executed.
     * <p>
     * This should be the minimum value among the dependencies of this binding.
     */
    public int getMinApi() {
        final BindingGetterCall getterCall = getGetterCall();
        return Math.max(getterCall.getMinApi(), getterCall.getEvent().getMinApi());
    }

    public BindingSetterCall getEventSetter() {
        final BindingGetterCall getterCall = getGetterCall();
        return getterCall.getEvent();
    }

    public String getName() {
        return mName;
    }

    public String getEventAttribute() {
        return getGetterCall().getEventAttribute();
    }

    public ExprModel getModel() {
        if (mExpr != null) {
            return mExpr.getModel();
        }
        return mChainedExpressions.get(0).getModel();
    }
}

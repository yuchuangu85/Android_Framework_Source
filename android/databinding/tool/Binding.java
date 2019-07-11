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

import android.databinding.tool.expr.Expr;
import android.databinding.tool.processing.ErrorMessages;
import android.databinding.tool.processing.Scope;
import android.databinding.tool.processing.scopes.LocationScopeProvider;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.store.Location;
import android.databinding.tool.store.SetterStore;
import android.databinding.tool.store.SetterStore.SetterCall;
import android.databinding.tool.util.L;
import android.databinding.tool.writer.CodeGenUtil;
import android.databinding.tool.writer.WriterPackage;

import java.util.List;

public class Binding implements LocationScopeProvider {

    private final String mName;
    private final Expr mExpr;
    private final BindingTarget mTarget;
    private SetterStore.SetterCall mSetterCall;

    public Binding(BindingTarget target, String name, Expr expr) {
        mTarget = target;
        mName = name;
        mExpr = expr;
    }

    @Override
    public List<Location> provideScopeLocation() {
        return mExpr.getLocations();
    }

    public void resolveListeners() {
        ModelClass listenerParameter = getListenerParameter();
        if (listenerParameter != null) {
            mExpr.resolveListeners(listenerParameter);
        }
    }

    private SetterStore.BindingSetterCall getSetterCall() {
        if (mSetterCall == null) {
            try {
                Scope.enter(getTarget());
                Scope.enter(this);
                resolveSetterCall();
                if (mSetterCall == null) {
                    L.e(ErrorMessages.CANNOT_FIND_SETTER_CALL, mName, mExpr.getResolvedType());
                }
            } finally {
                Scope.exit();
                Scope.exit();
            }
        }
        return mSetterCall;
    }

    private void resolveSetterCall() {
        ModelClass viewType = mTarget.getResolvedType();
        if (viewType != null && viewType.extendsViewStub()) {
            if (isListenerAttribute()) {
                ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
                ModelClass viewStubProxy = modelAnalyzer.
                        findClass("android.databinding.ViewStubProxy", null);
                mSetterCall = SetterStore.get(modelAnalyzer).getSetterCall(mName,
                        viewStubProxy, mExpr.getResolvedType(), mExpr.getModel().getImports());
            } else if (isViewStubAttribute()) {
                mSetterCall = new ViewStubDirectCall(mName, viewType, mExpr);
            } else {
                mSetterCall = new ViewStubSetterCall(mName);
            }
        } else {
            mSetterCall = SetterStore.get(ModelAnalyzer.getInstance()).getSetterCall(mName,
                    viewType, mExpr.getResolvedType(), mExpr.getModel().getImports());
        }
    }

    /**
     * Similar to getSetterCall, but assumes an Object parameter to find the best matching listener.
     */
    private ModelClass getListenerParameter() {
        ModelClass viewType = mTarget.getResolvedType();
        SetterCall setterCall;
        ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
        ModelClass objectParameter = modelAnalyzer.findClass(Object.class);
        if (viewType != null && viewType.extendsViewStub()) {
            if (isListenerAttribute()) {
                ModelClass viewStubProxy = modelAnalyzer.
                        findClass("android.databinding.ViewStubProxy", null);
                setterCall = SetterStore.get(modelAnalyzer).getSetterCall(mName,
                        viewStubProxy, objectParameter, mExpr.getModel().getImports());
            } else if (isViewStubAttribute()) {
                setterCall = SetterStore.get(ModelAnalyzer.getInstance()).getSetterCall(mName,
                        viewType, objectParameter, mExpr.getModel().getImports());
            } else {
                setterCall = new ViewStubSetterCall(mName);
            }
        } else {
            setterCall = SetterStore.get(ModelAnalyzer.getInstance()).getSetterCall(mName,
                    viewType, objectParameter, mExpr.getModel().getImports());
        }
        if (setterCall == null) {
            return null;
        }
        return setterCall.getParameterTypes()[0];
    }

    public BindingTarget getTarget() {
        return mTarget;
    }

    public String toJavaCode(String targetViewName, String bindingComponent) {
        final String currentValue = requiresOldValue()
                ? "this." + WriterPackage.getOldValueName(mExpr) : null;
        final String argCode = CodeGenUtil.Companion.toCode(getExpr(), false).generate();
        return getSetterCall().toJava(bindingComponent, targetViewName, currentValue, argCode);
    }

    public String getBindingAdapterInstanceClass() {
        return getSetterCall().getBindingAdapterInstanceClass();
    }

    public void setBindingAdapterCall(String method) {
        getSetterCall().setBindingAdapterCall(method);
    }

    public Expr[] getComponentExpressions() {
        return new Expr[] { mExpr };
    }

    public boolean requiresOldValue() {
        return getSetterCall().requiresOldValue();
    }

    /**
     * The min api level in which this binding should be executed.
     * <p>
     * This should be the minimum value among the dependencies of this binding. For now, we only
     * check the setter.
     */
    public int getMinApi() {
        return getSetterCall().getMinApi();
    }

    public String getName() {
        return mName;
    }

    public Expr getExpr() {
        return mExpr;
    }

    private boolean isViewStubAttribute() {
        return ("android:inflatedId".equals(mName) ||
                "android:layout".equals(mName) ||
                "android:visibility".equals(mName) ||
                "android:layoutInflater".equals(mName));
    }

    private boolean isListenerAttribute() {
        return ("android:onInflate".equals(mName) ||
                "android:onInflateListener".equals(mName));

    }

    private static class ViewStubSetterCall extends SetterCall {
        private final String mName;

        public ViewStubSetterCall(String name) {
            mName = name.substring(name.lastIndexOf(':') + 1);
        }

        @Override
        protected String toJavaInternal(String componentExpression, String viewExpression,
                String converted) {
            return "if (" + viewExpression + ".isInflated()) " + viewExpression +
                    ".getBinding().setVariable(BR." + mName + ", " + converted + ")";
        }

        @Override
        protected String toJavaInternal(String componentExpression, String viewExpression,
                String oldValue, String converted) {
            return null;
        }

        @Override
        public int getMinApi() {
            return 0;
        }

        @Override
        public boolean requiresOldValue() {
            return false;
        }

        @Override
        public ModelClass[] getParameterTypes() {
            return new ModelClass[] {
                    ModelAnalyzer.getInstance().findClass(Object.class)
            };
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return null;
        }

        @Override
        public void setBindingAdapterCall(String method) {
        }
    }

    private static class ViewStubDirectCall extends SetterCall {
        private final SetterCall mWrappedCall;

        public ViewStubDirectCall(String name, ModelClass viewType, Expr expr) {
            mWrappedCall = SetterStore.get(ModelAnalyzer.getInstance()).getSetterCall(name,
                    viewType, expr.getResolvedType(), expr.getModel().getImports());
            if (mWrappedCall == null) {
                L.e("Cannot find the setter for attribute '%s' on %s with parameter type %s.",
                        name, viewType, expr.getResolvedType());
            }
        }

        @Override
        protected String toJavaInternal(String componentExpression, String viewExpression,
                String converted) {
            return "if (!" + viewExpression + ".isInflated()) " +
                    mWrappedCall.toJava(componentExpression, viewExpression + ".getViewStub()",
                            null, converted);
        }

        @Override
        protected String toJavaInternal(String componentExpression, String viewExpression,
                String oldValue, String converted) {
            return null;
        }

        @Override
        public int getMinApi() {
            return 0;
        }

        @Override
        public boolean requiresOldValue() {
            return false;
        }

        @Override
        public ModelClass[] getParameterTypes() {
            return new ModelClass[] {
                    ModelAnalyzer.getInstance().findClass(Object.class)
            };
        }

        @Override
        public String getBindingAdapterInstanceClass() {
            return mWrappedCall.getBindingAdapterInstanceClass();
        }

        @Override
        public void setBindingAdapterCall(String method) {
            mWrappedCall.setBindingAdapterCall(method);
        }
    }
}

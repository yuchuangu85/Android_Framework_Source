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
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;
import android.databinding.tool.writer.KCode;

public class MethodReferenceExpr extends MethodBaseExpr {

    MethodReferenceExpr(Expr parent, String name) {
        super(parent, name);
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        return modelAnalyzer.findClass(Object.class);
    }

    @Override
    protected String computeUniqueKey() {
        return join(mName, "::", getTarget().getUniqueKey());
    }

    @Override
    public String getInvertibleError() {
        return "Listeners do not support two-way binding";
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public Expr resolveListeners(ModelClass listener, Expr parent) {
        try {
            return resolveListenersAsMethodReference(listener, parent);
        } catch (IllegalStateException e) {
            L.e("%s", e.getMessage());
            return this;
        }
    }

    @Override
    protected KCode generateCode() {
        // once we can deprecate using Field.access for callbacks, we can get rid of this since
        // it will be detected when resolve type is run.
        Preconditions.check(false, "Cannot generate code for unresolved method reference %s", this);
        return null;
    }

    @Override
    public Expr generateInverse(ExprModel model, Expr value, String bindingClassName) {
        Preconditions.check(false, "Method references do not have an inverse");
        return this;
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        final Expr clonedTarget = getTarget().cloneToModel(model);
        return model.methodReference(clonedTarget, mName);
    }

    @Override
    public String toString() {
        return getTarget().toString() + "::" + mName;
    }
}

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

import android.databinding.tool.ext.ExtKt;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.util.BrNameUtil;
import android.databinding.tool.util.L;

public class ObservableFieldExpr extends FieldAccessExpr {

    ObservableFieldExpr(Expr parent, String name) {
        super(parent, name);
    }

    @Override
    public Expr resolveListeners(ModelClass listener, Expr parent) {
        return this;  // ObservableFields aren't listeners
    }

    @Override
    protected String computeUniqueKey() {
        return join(mName, "..", getTarget().getUniqueKey());
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        if (mGetter == null) {
            Expr target = getTarget();
            target.getResolvedType();
            boolean isStatic = target instanceof StaticIdentifierExpr;
            ModelClass resolvedType = target.getResolvedType();
            L.d("resolving %s. Resolved class type: %s", this, resolvedType);

            mGetter = resolvedType.findGetterOrField(mName, isStatic);

            if (mGetter == null) {
                L.e("Could not find accessor %s.%s", resolvedType.getCanonicalName(), mName);
                return null;
            }

            if (mGetter.isStatic() && !isStatic) {
                // found a static method on an instance. register a new one
                replaceStaticIdentifier(resolvedType);
            }
            if (hasBindableAnnotations()) {
                mBrName = ExtKt.br(BrNameUtil.brKey(getGetter()));
            } else {
                mBrName = ExtKt.br(mName);
            }
        }
        return mGetter.resolvedType;
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        final Expr clonedTarget = getTarget().cloneToModel(model);
        return model.observableField(clonedTarget, mName);
    }

    @Override
    public String toString() {
        return getTarget().toString() + '.' + mName;
    }
}

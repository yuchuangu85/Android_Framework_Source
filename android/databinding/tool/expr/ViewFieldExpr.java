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

import android.databinding.tool.BindingTarget;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.writer.LayoutBinderWriterKt;

public class ViewFieldExpr extends BuiltInVariableExpr {
    private final BindingTarget mBindingTarget;

    ViewFieldExpr(BindingTarget bindingTarget) {
        super(LayoutBinderWriterKt.getFieldName(bindingTarget), bindingTarget.getInterfaceType(),
                LayoutBinderWriterKt.getFieldName(bindingTarget));
        mBindingTarget = bindingTarget;
    }

    @Override
    public String getInvertibleError() {
        return "View fields may not be the target of two-way binding";
    }

    public BindingTarget getBindingTarget() {
        return mBindingTarget;
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        final ModelClass type = modelAnalyzer.findClass(mBindingTarget.getInterfaceType(), null);
        if (type == null) {
            return modelAnalyzer.findClass(ModelAnalyzer.VIEW_DATA_BINDING, null);
        }
        return type;
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return model.viewFieldExpr(mBindingTarget);
    }
}

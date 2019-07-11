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

import android.databinding.tool.processing.ErrorMessages;
import android.databinding.tool.processing.Scope;
import android.databinding.tool.store.Location;
import android.databinding.tool.util.L;
import android.databinding.tool.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Callbacks are evaluated when event happens, not when execute pending is run. To separate their
 * expressions, we provide a separate model for them that extends the main model. This allows them
 * to introduce their own variables etc. without mixing them with other expressions.
 */
public class CallbackExprModel extends ExprModel {
    // used for imports and other stuff.
    final ExprModel mOriginal;
    final List<CallbackArgExpr> mArguments = new ArrayList<CallbackArgExpr>();
    public CallbackExprModel(ExprModel original) {
        mOriginal = original;
    }

    @Override
    public Map<String, String> getImports() {
        return mOriginal.getImports();
    }

    @Override
    public StaticIdentifierExpr addImport(String alias, String type, Location location) {
        return mOriginal.addImport(alias, type, location);
    }

    @Override
    public <T extends Expr> T register(T expr) {
        // locations are only synced to main model so we need to sync overselves here.
        setCurrentLocationInFile(mOriginal.getCurrentLocationInFile());
        setCurrentParserContext(mOriginal.getCurrentParserContext());
        return super.register(expr);
    }

    @Override
    public void seal() {
        // ensure all types are calculated
        for (Expr expr : mExprMap.values()) {
            expr.getResolvedType();
            expr.markAsUsedInCallback();
        }
        markSealed();
        // we do not resolve dependencies for these expression because they are resolved via
        // ExecutionPath and should not interfere with the main expr model's dependency graph.
    }

    @Override
    public IdentifierExpr identifier(String name) {
        CallbackArgExpr arg = findArgByName(name);
        if (arg != null) {
            return arg;
        }
        IdentifierExpr id = new IdentifierExpr(name);
        final Expr existing = mExprMap.get(id.getUniqueKey());
        if (existing == null) {
             // this is not a method variable reference. register it in the main model
            final IdentifierExpr identifier = mOriginal.identifier(name);
            mExprMap.put(identifier.getUniqueKey(), identifier);
            identifier.markAsUsedInCallback();
            return identifier;
        }
        return (IdentifierExpr) existing;
    }

    private CallbackArgExpr findArgByName(String name) {
        for (CallbackArgExpr arg : mArguments) {
            if (name.equals(arg.getName())) {
                return arg;
            }
        }
        return null;
    }

    public CallbackArgExpr callbackArg(String name) {
        Preconditions.checkNull(findArgByName(name),
                ErrorMessages.DUPLICATE_CALLBACK_ARGUMENT, name);
        final CallbackArgExpr id = new CallbackArgExpr(mArguments.size(), name);
        final CallbackArgExpr added = register(id);
        mArguments.add(added);

        try {
            Scope.enter(added);
            IdentifierExpr identifierWithSameName = mOriginal.findIdentifier(name);
            if (identifierWithSameName != null) {
                L.w(ErrorMessages.CALLBACK_VARIABLE_NAME_CLASH, name, name,
                        identifierWithSameName.getUserDefinedType());
            }
        } finally {
            Scope.exit();
        }
        return added;
    }

    public int getArgCount() {
        return mArguments.size();
    }

    public List<CallbackArgExpr> getArguments() {
        return mArguments;
    }
}

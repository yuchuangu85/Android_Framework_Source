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

import android.databinding.parser.BindingExpressionBaseVisitor;
import android.databinding.parser.BindingExpressionParser;
import android.databinding.parser.BindingExpressionParser.AndOrOpContext;
import android.databinding.parser.BindingExpressionParser.BinaryOpContext;
import android.databinding.parser.BindingExpressionParser.BitShiftOpContext;
import android.databinding.parser.BindingExpressionParser.InstanceOfOpContext;
import android.databinding.parser.BindingExpressionParser.UnaryOpContext;
import android.databinding.tool.expr.CallbackExprModel;
import android.databinding.tool.expr.Expr;
import android.databinding.tool.expr.ExprModel;
import android.databinding.tool.expr.StaticIdentifierExpr;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.util.Preconditions;

import com.android.annotations.NonNull;
import com.google.common.base.Objects;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

class ExpressionVisitor extends BindingExpressionBaseVisitor<Expr> {
    private ExprModel mModel;
    private ParseTreeListener mParseTreeListener;
    private ArrayDeque<ExprModel> mModelStack = new ArrayDeque<ExprModel>();
    private BindingTarget mTarget;

    ExpressionVisitor(ExprModel model) {
        mModel = model;
    }

    void setParseTreeListener(ParseTreeListener parseTreeListener) {
        mParseTreeListener = parseTreeListener;
    }

    public void setBindingTarget(BindingTarget bindingTarget) {
        mTarget = bindingTarget;
    }

    private void onEnter(ParserRuleContext context) {
        if (mParseTreeListener != null) {
            mParseTreeListener.enterEveryRule(context);
        }
    }

    private void onExit(ParserRuleContext context) {
        if (mParseTreeListener != null) {
            mParseTreeListener.exitEveryRule(context);
        }
    }

    private void pushModel(ExprModel model) {
        Preconditions.checkNotNull(mModel, "Cannot put empty model to stack");
        Preconditions.checkNotNull(model, "Cannot set null model");
        mModelStack.push(mModel);
        mModel = model;
    }

    private void popModel() {
        Preconditions.checkNotNull(mModel, "Cannot have empty mdoel stack");
        Preconditions.check(mModelStack.size() > 0, "Cannot have empty model stack");
        mModel = mModelStack.pop();
    }

    @Override
    public Expr visitRootLambda(@NonNull BindingExpressionParser.RootLambdaContext ctx) {
        try {
            onEnter(ctx);
            CallbackExprModel callbackModel = new CallbackExprModel(mModel);
            ExprModel prev = mModel;
            pushModel(callbackModel);
            final BindingExpressionParser.LambdaExpressionContext lambdaCtx = ctx
                    .lambdaExpression();
            lambdaCtx.args.accept(this);
            return prev.lambdaExpr(lambdaCtx.expression().accept(this), callbackModel);
        } finally {
            popModel();
            onExit(ctx);
        }
    }

    @Override
    public Expr visitSingleLambdaParameter(
            @NonNull BindingExpressionParser.SingleLambdaParameterContext ctx) {
        try {
            onEnter(ctx);
            Preconditions.check(mModel instanceof CallbackExprModel, "Lambdas can only be used in"
                    + " callbacks.");
            // just add it to the callback model as identifier
            ((CallbackExprModel) mModel).callbackArg(ctx.getText());
            return null;
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitLambdaParameterList(
            @NonNull BindingExpressionParser.LambdaParameterListContext ctx) {
        try {
            onEnter(ctx);
            Preconditions.check(mModel instanceof CallbackExprModel, "Lambdas can only be used in"
                    + " callbacks.");
            if (ctx.params != null) {
                for (ParseTree item : ctx.params.children) {
                    if (Objects.equal(item.getText(), ",")) {
                        continue;
                    }
                    // just add them to the callback model as identifiers
                    ((CallbackExprModel) mModel).callbackArg(item.getText());
                }
            }
            return null;
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitStringLiteral(@NonNull BindingExpressionParser.StringLiteralContext ctx) {
        try {
            onEnter(ctx);
            final String javaString;
            if (ctx.SingleQuoteString() != null) {
                String str = ctx.SingleQuoteString().getText();
                String contents = str.substring(1, str.length() - 1);
                contents = contents.replace("\"", "\\\"").replace("\\`", "`");
                javaString = '"' + contents + '"';
            } else {
                javaString = ctx.DoubleQuoteString().getText();
            }
            return mModel.symbol(javaString, String.class);
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitRootExpr(@NonNull BindingExpressionParser.RootExprContext ctx) {
        try {
            onEnter(ctx);
            // TODO handle defaults
            return mModel.bindingExpr(ctx.expression().accept(this));
        } catch (Exception e) {
            System.out.println("Error while parsing! " + ctx.getText());
            e.printStackTrace();
            throw new RuntimeException(e);
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitGrouping(@NonNull BindingExpressionParser.GroupingContext ctx) {
        try {
            onEnter(ctx);
            Preconditions.check(ctx.children.size() == 3, "Grouping expression should have"
                    + " 3 children. # of children: %d", ctx.children.size());
            return ctx.children.get(1).accept(this);
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitDotOp(@NonNull BindingExpressionParser.DotOpContext ctx) {
        try {
            onEnter(ctx);
            ModelAnalyzer analyzer = ModelAnalyzer.getInstance();
            ModelClass modelClass = analyzer.findClass(ctx.getText(), mModel.getImports());
            if (modelClass == null) {
                return mModel.field(ctx.expression().accept(this),
                        ctx.Identifier().getSymbol().getText());
            } else {
                String name = modelClass.toJavaCode();
                StaticIdentifierExpr expr = mModel.staticIdentifier(name);
                expr.setUserDefinedType(name);
                return expr;
            }
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitFunctionRef(@NonNull BindingExpressionParser.FunctionRefContext ctx) {
        try {
            onEnter(ctx);
            return mModel.methodReference(ctx.expression().accept(this),
                    ctx.Identifier().getSymbol().getText());
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitQuestionQuestionOp(
            @NonNull BindingExpressionParser.QuestionQuestionOpContext ctx) {
        try {
            onEnter(ctx);
            final Expr left = ctx.left.accept(this);
            return mModel.ternary(mModel.comparison("==", left, mModel.symbol("null", Object.class)),
                    ctx.right.accept(this), left);
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitTerminal(@NonNull TerminalNode node) {
        try {
            onEnter((ParserRuleContext) node.getParent());
            final int type = node.getSymbol().getType();
            Class classType;
            switch (type) {
                case BindingExpressionParser.IntegerLiteral:
                    classType = int.class;
                    break;
                case BindingExpressionParser.FloatingPointLiteral:
                    classType = float.class;
                    break;
                case BindingExpressionParser.BooleanLiteral:
                    classType = boolean.class;
                    break;
                case BindingExpressionParser.CharacterLiteral:
                    classType = char.class;
                    break;
                case BindingExpressionParser.SingleQuoteString:
                case BindingExpressionParser.DoubleQuoteString:
                    classType = String.class;
                    break;
                case BindingExpressionParser.NullLiteral:
                    classType = Object.class;
                    break;
                case BindingExpressionParser.VoidLiteral:
                    classType = void.class;
                    break;
                default:
                    throw new RuntimeException("cannot create expression from terminal node " +
                            node.toString());
            }
            return mModel.symbol(node.getText(), classType);
        } finally {
            onExit((ParserRuleContext) node.getParent());
        }
    }

    @Override
    public Expr visitComparisonOp(@NonNull BindingExpressionParser.ComparisonOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.comparison(ctx.op.getText(), ctx.left.accept(this), ctx.right.accept(this));
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitIdentifier(@NonNull BindingExpressionParser.IdentifierContext ctx) {
        try {
            onEnter(ctx);
            return mModel.identifier(ctx.getText());
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitTernaryOp(@NonNull BindingExpressionParser.TernaryOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.ternary(ctx.left.accept(this), ctx.iftrue.accept(this),
                    ctx.iffalse.accept(this));
        } finally {
            onExit(ctx);
        }

    }

    @Override
    public Expr visitMethodInvocation(
            @NonNull BindingExpressionParser.MethodInvocationContext ctx) {
        try {
            onEnter(ctx);
            List<Expr> args = new ArrayList<Expr>();
            if (ctx.args != null) {
                for (ParseTree item : ctx.args.children) {
                    if (Objects.equal(item.getText(), ",")) {
                        continue;
                    }
                    args.add(item.accept(this));
                }
            }
            return mModel.methodCall(ctx.target.accept(this),
                    ctx.Identifier().getText(), args);
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitMathOp(@NonNull BindingExpressionParser.MathOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.math(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitAndOrOp(@NonNull AndOrOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.logical(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitBinaryOp(@NonNull BinaryOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.math(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitBitShiftOp(@NonNull BitShiftOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.bitshift(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitInstanceOfOp(@NonNull InstanceOfOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.instanceOfOp(ctx.expression().accept(this), ctx.type().getText());
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitUnaryOp(@NonNull UnaryOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.unary(ctx.op.getText(), ctx.expression().accept(this));
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitResources(@NonNull BindingExpressionParser.ResourcesContext ctx) {
        try {
            onEnter(ctx);
            final List<Expr> args = new ArrayList<Expr>();
            if (ctx.resourceParameters() != null) {
                for (ParseTree item : ctx.resourceParameters().expressionList().children) {
                    if (Objects.equal(item.getText(), ",")) {
                        continue;
                    }
                    args.add(item.accept(this));
                }
            }
            final String resourceReference = ctx.ResourceReference().getText();
            final int colonIndex = resourceReference.indexOf(':');
            final int slashIndex = resourceReference.indexOf('/');
            final String packageName = colonIndex < 0 ? null :
                    resourceReference.substring(1, colonIndex).trim();
            final int startIndex = Math.max(1, colonIndex + 1);
            final String resourceType = resourceReference.substring(startIndex, slashIndex).trim();
            final String resourceName = resourceReference.substring(slashIndex + 1).trim();
            return mModel.resourceExpr(mTarget, packageName, resourceType, resourceName, args);
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitBracketOp(@NonNull BindingExpressionParser.BracketOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.bracketExpr(visit(ctx.expression(0)), visit(ctx.expression(1)));
        } finally {
            onExit(ctx);
        }
    }

    @Override
    public Expr visitCastOp(@NonNull BindingExpressionParser.CastOpContext ctx) {
        try {
            onEnter(ctx);
            return mModel.castExpr(ctx.type().getText(), visit(ctx.expression()));
        } finally {
            onExit(ctx);
        }
    }
}

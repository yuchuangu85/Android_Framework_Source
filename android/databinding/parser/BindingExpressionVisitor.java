// Generated from BindingExpression.g4 by ANTLR 4.5.3
package android.databinding.parser;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link BindingExpressionParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface BindingExpressionVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by the {@code RootExpr}
	 * labeled alternative in {@link BindingExpressionParser#bindingSyntax}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRootExpr(BindingExpressionParser.RootExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code RootLambda}
	 * labeled alternative in {@link BindingExpressionParser#bindingSyntax}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRootLambda(BindingExpressionParser.RootLambdaContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#defaults}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDefaults(BindingExpressionParser.DefaultsContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#constantValue}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstantValue(BindingExpressionParser.ConstantValueContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#lambdaExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLambdaExpression(BindingExpressionParser.LambdaExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code SingleLambdaParameter}
	 * labeled alternative in {@link BindingExpressionParser#lambdaParameters}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleLambdaParameter(BindingExpressionParser.SingleLambdaParameterContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LambdaParameterList}
	 * labeled alternative in {@link BindingExpressionParser#lambdaParameters}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLambdaParameterList(BindingExpressionParser.LambdaParameterListContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#inferredFormalParameterList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInferredFormalParameterList(BindingExpressionParser.InferredFormalParameterListContext ctx);
	/**
	 * Visit a parse tree produced by the {@code CastOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCastOp(BindingExpressionParser.CastOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ComparisonOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitComparisonOp(BindingExpressionParser.ComparisonOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code UnaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnaryOp(BindingExpressionParser.UnaryOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code BracketOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBracketOp(BindingExpressionParser.BracketOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Resource}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitResource(BindingExpressionParser.ResourceContext ctx);
	/**
	 * Visit a parse tree produced by the {@code QuestionQuestionOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQuestionQuestionOp(BindingExpressionParser.QuestionQuestionOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Grouping}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGrouping(BindingExpressionParser.GroupingContext ctx);
	/**
	 * Visit a parse tree produced by the {@code MethodInvocation}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMethodInvocation(BindingExpressionParser.MethodInvocationContext ctx);
	/**
	 * Visit a parse tree produced by the {@code BitShiftOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBitShiftOp(BindingExpressionParser.BitShiftOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code AndOrOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAndOrOp(BindingExpressionParser.AndOrOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code TernaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTernaryOp(BindingExpressionParser.TernaryOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Primary}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimary(BindingExpressionParser.PrimaryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code DotOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDotOp(BindingExpressionParser.DotOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code MathOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMathOp(BindingExpressionParser.MathOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code InstanceOfOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInstanceOfOp(BindingExpressionParser.InstanceOfOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code BinaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBinaryOp(BindingExpressionParser.BinaryOpContext ctx);
	/**
	 * Visit a parse tree produced by the {@code FunctionRef}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionRef(BindingExpressionParser.FunctionRefContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#classExtraction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassExtraction(BindingExpressionParser.ClassExtractionContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#expressionList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpressionList(BindingExpressionParser.ExpressionListContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLiteral(BindingExpressionParser.LiteralContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#identifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifier(BindingExpressionParser.IdentifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#javaLiteral}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJavaLiteral(BindingExpressionParser.JavaLiteralContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#stringLiteral}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStringLiteral(BindingExpressionParser.StringLiteralContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExplicitGenericInvocation(BindingExpressionParser.ExplicitGenericInvocationContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#typeArguments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeArguments(BindingExpressionParser.TypeArgumentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitType(BindingExpressionParser.TypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocationSuffix}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExplicitGenericInvocationSuffix(BindingExpressionParser.ExplicitGenericInvocationSuffixContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#arguments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArguments(BindingExpressionParser.ArgumentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#classOrInterfaceType}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassOrInterfaceType(BindingExpressionParser.ClassOrInterfaceTypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#primitiveType}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimitiveType(BindingExpressionParser.PrimitiveTypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#resources}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitResources(BindingExpressionParser.ResourcesContext ctx);
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#resourceParameters}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitResourceParameters(BindingExpressionParser.ResourceParametersContext ctx);
}
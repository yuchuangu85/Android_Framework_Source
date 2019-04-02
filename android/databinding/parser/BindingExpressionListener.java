// Generated from BindingExpression.g4 by ANTLR 4.5.3
package android.databinding.parser;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link BindingExpressionParser}.
 */
public interface BindingExpressionListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by the {@code RootExpr}
	 * labeled alternative in {@link BindingExpressionParser#bindingSyntax}.
	 * @param ctx the parse tree
	 */
	void enterRootExpr(BindingExpressionParser.RootExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code RootExpr}
	 * labeled alternative in {@link BindingExpressionParser#bindingSyntax}.
	 * @param ctx the parse tree
	 */
	void exitRootExpr(BindingExpressionParser.RootExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code RootLambda}
	 * labeled alternative in {@link BindingExpressionParser#bindingSyntax}.
	 * @param ctx the parse tree
	 */
	void enterRootLambda(BindingExpressionParser.RootLambdaContext ctx);
	/**
	 * Exit a parse tree produced by the {@code RootLambda}
	 * labeled alternative in {@link BindingExpressionParser#bindingSyntax}.
	 * @param ctx the parse tree
	 */
	void exitRootLambda(BindingExpressionParser.RootLambdaContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#defaults}.
	 * @param ctx the parse tree
	 */
	void enterDefaults(BindingExpressionParser.DefaultsContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#defaults}.
	 * @param ctx the parse tree
	 */
	void exitDefaults(BindingExpressionParser.DefaultsContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#constantValue}.
	 * @param ctx the parse tree
	 */
	void enterConstantValue(BindingExpressionParser.ConstantValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#constantValue}.
	 * @param ctx the parse tree
	 */
	void exitConstantValue(BindingExpressionParser.ConstantValueContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#lambdaExpression}.
	 * @param ctx the parse tree
	 */
	void enterLambdaExpression(BindingExpressionParser.LambdaExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#lambdaExpression}.
	 * @param ctx the parse tree
	 */
	void exitLambdaExpression(BindingExpressionParser.LambdaExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code SingleLambdaParameter}
	 * labeled alternative in {@link BindingExpressionParser#lambdaParameters}.
	 * @param ctx the parse tree
	 */
	void enterSingleLambdaParameter(BindingExpressionParser.SingleLambdaParameterContext ctx);
	/**
	 * Exit a parse tree produced by the {@code SingleLambdaParameter}
	 * labeled alternative in {@link BindingExpressionParser#lambdaParameters}.
	 * @param ctx the parse tree
	 */
	void exitSingleLambdaParameter(BindingExpressionParser.SingleLambdaParameterContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LambdaParameterList}
	 * labeled alternative in {@link BindingExpressionParser#lambdaParameters}.
	 * @param ctx the parse tree
	 */
	void enterLambdaParameterList(BindingExpressionParser.LambdaParameterListContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LambdaParameterList}
	 * labeled alternative in {@link BindingExpressionParser#lambdaParameters}.
	 * @param ctx the parse tree
	 */
	void exitLambdaParameterList(BindingExpressionParser.LambdaParameterListContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#inferredFormalParameterList}.
	 * @param ctx the parse tree
	 */
	void enterInferredFormalParameterList(BindingExpressionParser.InferredFormalParameterListContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#inferredFormalParameterList}.
	 * @param ctx the parse tree
	 */
	void exitInferredFormalParameterList(BindingExpressionParser.InferredFormalParameterListContext ctx);
	/**
	 * Enter a parse tree produced by the {@code CastOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterCastOp(BindingExpressionParser.CastOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code CastOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitCastOp(BindingExpressionParser.CastOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ComparisonOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterComparisonOp(BindingExpressionParser.ComparisonOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ComparisonOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitComparisonOp(BindingExpressionParser.ComparisonOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code UnaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterUnaryOp(BindingExpressionParser.UnaryOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code UnaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitUnaryOp(BindingExpressionParser.UnaryOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code BracketOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBracketOp(BindingExpressionParser.BracketOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BracketOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBracketOp(BindingExpressionParser.BracketOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code Resource}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterResource(BindingExpressionParser.ResourceContext ctx);
	/**
	 * Exit a parse tree produced by the {@code Resource}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitResource(BindingExpressionParser.ResourceContext ctx);
	/**
	 * Enter a parse tree produced by the {@code QuestionQuestionOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterQuestionQuestionOp(BindingExpressionParser.QuestionQuestionOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code QuestionQuestionOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitQuestionQuestionOp(BindingExpressionParser.QuestionQuestionOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code Grouping}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterGrouping(BindingExpressionParser.GroupingContext ctx);
	/**
	 * Exit a parse tree produced by the {@code Grouping}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitGrouping(BindingExpressionParser.GroupingContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MethodInvocation}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMethodInvocation(BindingExpressionParser.MethodInvocationContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MethodInvocation}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMethodInvocation(BindingExpressionParser.MethodInvocationContext ctx);
	/**
	 * Enter a parse tree produced by the {@code BitShiftOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBitShiftOp(BindingExpressionParser.BitShiftOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BitShiftOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBitShiftOp(BindingExpressionParser.BitShiftOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code AndOrOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAndOrOp(BindingExpressionParser.AndOrOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code AndOrOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAndOrOp(BindingExpressionParser.AndOrOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TernaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterTernaryOp(BindingExpressionParser.TernaryOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TernaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitTernaryOp(BindingExpressionParser.TernaryOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code Primary}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPrimary(BindingExpressionParser.PrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code Primary}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPrimary(BindingExpressionParser.PrimaryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code DotOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterDotOp(BindingExpressionParser.DotOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code DotOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitDotOp(BindingExpressionParser.DotOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MathOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMathOp(BindingExpressionParser.MathOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MathOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMathOp(BindingExpressionParser.MathOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code InstanceOfOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterInstanceOfOp(BindingExpressionParser.InstanceOfOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code InstanceOfOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitInstanceOfOp(BindingExpressionParser.InstanceOfOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code BinaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBinaryOp(BindingExpressionParser.BinaryOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BinaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBinaryOp(BindingExpressionParser.BinaryOpContext ctx);
	/**
	 * Enter a parse tree produced by the {@code FunctionRef}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterFunctionRef(BindingExpressionParser.FunctionRefContext ctx);
	/**
	 * Exit a parse tree produced by the {@code FunctionRef}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitFunctionRef(BindingExpressionParser.FunctionRefContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#classExtraction}.
	 * @param ctx the parse tree
	 */
	void enterClassExtraction(BindingExpressionParser.ClassExtractionContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#classExtraction}.
	 * @param ctx the parse tree
	 */
	void exitClassExtraction(BindingExpressionParser.ClassExtractionContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#expressionList}.
	 * @param ctx the parse tree
	 */
	void enterExpressionList(BindingExpressionParser.ExpressionListContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#expressionList}.
	 * @param ctx the parse tree
	 */
	void exitExpressionList(BindingExpressionParser.ExpressionListContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#literal}.
	 * @param ctx the parse tree
	 */
	void enterLiteral(BindingExpressionParser.LiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#literal}.
	 * @param ctx the parse tree
	 */
	void exitLiteral(BindingExpressionParser.LiteralContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#identifier}.
	 * @param ctx the parse tree
	 */
	void enterIdentifier(BindingExpressionParser.IdentifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#identifier}.
	 * @param ctx the parse tree
	 */
	void exitIdentifier(BindingExpressionParser.IdentifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#javaLiteral}.
	 * @param ctx the parse tree
	 */
	void enterJavaLiteral(BindingExpressionParser.JavaLiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#javaLiteral}.
	 * @param ctx the parse tree
	 */
	void exitJavaLiteral(BindingExpressionParser.JavaLiteralContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#stringLiteral}.
	 * @param ctx the parse tree
	 */
	void enterStringLiteral(BindingExpressionParser.StringLiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#stringLiteral}.
	 * @param ctx the parse tree
	 */
	void exitStringLiteral(BindingExpressionParser.StringLiteralContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocation}.
	 * @param ctx the parse tree
	 */
	void enterExplicitGenericInvocation(BindingExpressionParser.ExplicitGenericInvocationContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocation}.
	 * @param ctx the parse tree
	 */
	void exitExplicitGenericInvocation(BindingExpressionParser.ExplicitGenericInvocationContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#typeArguments}.
	 * @param ctx the parse tree
	 */
	void enterTypeArguments(BindingExpressionParser.TypeArgumentsContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#typeArguments}.
	 * @param ctx the parse tree
	 */
	void exitTypeArguments(BindingExpressionParser.TypeArgumentsContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#type}.
	 * @param ctx the parse tree
	 */
	void enterType(BindingExpressionParser.TypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#type}.
	 * @param ctx the parse tree
	 */
	void exitType(BindingExpressionParser.TypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocationSuffix}.
	 * @param ctx the parse tree
	 */
	void enterExplicitGenericInvocationSuffix(BindingExpressionParser.ExplicitGenericInvocationSuffixContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocationSuffix}.
	 * @param ctx the parse tree
	 */
	void exitExplicitGenericInvocationSuffix(BindingExpressionParser.ExplicitGenericInvocationSuffixContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#arguments}.
	 * @param ctx the parse tree
	 */
	void enterArguments(BindingExpressionParser.ArgumentsContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#arguments}.
	 * @param ctx the parse tree
	 */
	void exitArguments(BindingExpressionParser.ArgumentsContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#classOrInterfaceType}.
	 * @param ctx the parse tree
	 */
	void enterClassOrInterfaceType(BindingExpressionParser.ClassOrInterfaceTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#classOrInterfaceType}.
	 * @param ctx the parse tree
	 */
	void exitClassOrInterfaceType(BindingExpressionParser.ClassOrInterfaceTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#primitiveType}.
	 * @param ctx the parse tree
	 */
	void enterPrimitiveType(BindingExpressionParser.PrimitiveTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#primitiveType}.
	 * @param ctx the parse tree
	 */
	void exitPrimitiveType(BindingExpressionParser.PrimitiveTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#resources}.
	 * @param ctx the parse tree
	 */
	void enterResources(BindingExpressionParser.ResourcesContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#resources}.
	 * @param ctx the parse tree
	 */
	void exitResources(BindingExpressionParser.ResourcesContext ctx);
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#resourceParameters}.
	 * @param ctx the parse tree
	 */
	void enterResourceParameters(BindingExpressionParser.ResourceParametersContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#resourceParameters}.
	 * @param ctx the parse tree
	 */
	void exitResourceParameters(BindingExpressionParser.ResourceParametersContext ctx);
}
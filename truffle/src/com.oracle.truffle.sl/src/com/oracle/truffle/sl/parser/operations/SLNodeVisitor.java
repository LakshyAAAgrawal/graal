package com.oracle.truffle.sl.parser.operations;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.sl.SLLanguage;
import com.oracle.truffle.sl.nodes.SLExpressionNode;
import com.oracle.truffle.sl.nodes.SLRootNode;
import com.oracle.truffle.sl.nodes.SLStatementNode;
import com.oracle.truffle.sl.nodes.controlflow.SLBlockNode;
import com.oracle.truffle.sl.nodes.controlflow.SLBreakNode;
import com.oracle.truffle.sl.nodes.controlflow.SLContinueNode;
import com.oracle.truffle.sl.nodes.controlflow.SLDebuggerNode;
import com.oracle.truffle.sl.nodes.controlflow.SLFunctionBodyNode;
import com.oracle.truffle.sl.nodes.controlflow.SLIfNode;
import com.oracle.truffle.sl.nodes.controlflow.SLReturnNode;
import com.oracle.truffle.sl.nodes.controlflow.SLWhileNode;
import com.oracle.truffle.sl.nodes.expression.SLAddNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLBigIntegerLiteralNode;
import com.oracle.truffle.sl.nodes.expression.SLDivNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLEqualNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLFunctionLiteralNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLInvokeNode;
import com.oracle.truffle.sl.nodes.expression.SLLessOrEqualNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLLessThanNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLLogicalAndNode;
import com.oracle.truffle.sl.nodes.expression.SLLogicalNotNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLLogicalOrNode;
import com.oracle.truffle.sl.nodes.expression.SLLongLiteralNode;
import com.oracle.truffle.sl.nodes.expression.SLMulNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLParenExpressionNode;
import com.oracle.truffle.sl.nodes.expression.SLReadPropertyNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLStringLiteralNode;
import com.oracle.truffle.sl.nodes.expression.SLSubNodeGen;
import com.oracle.truffle.sl.nodes.expression.SLWritePropertyNodeGen;
import com.oracle.truffle.sl.nodes.local.SLReadArgumentNode;
import com.oracle.truffle.sl.nodes.local.SLReadLocalVariableNodeGen;
import com.oracle.truffle.sl.nodes.local.SLWriteLocalVariableNodeGen;
import com.oracle.truffle.sl.nodes.util.SLUnboxNodeGen;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.ArithmeticContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.BlockContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.Break_statementContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.Continue_statementContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.Debugger_statementContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.ExpressionContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.Expression_statementContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.FunctionContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.If_statementContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.Logic_factorContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.Logic_termContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.MemberAssignContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.MemberCallContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.MemberFieldContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.MemberIndexContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.Member_expressionContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.NameAccessContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.NumericLiteralContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.ParenExpressionContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.Return_statementContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.StatementContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.StringLiteralContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.TermContext;
import com.oracle.truffle.sl.parser.operations.SimpleLanguageOperationsParser.While_statementContext;

public class SLNodeVisitor extends SLBaseVisitor {

    public static Map<TruffleString, RootCallTarget> parseSL(SLLanguage language, Source source) {
        return parseSLImpl(source, new SLNodeVisitor(language, source));
    }

    private LexicalScope scope;
    private FrameDescriptor.Builder frameDescriptorBuilder;

    private SLStatementVisitor STATEMENT_VISITOR = new SLStatementVisitor();
    private SLExpressionVisitor EXPRESSION_VISITOR = new SLExpressionVisitor();
    private int loopDepth = 0;

    protected SLNodeVisitor(SLLanguage language, Source source) {
        super(language, source);
    }

    @Override
    public Void visitFunction(FunctionContext ctx) {

        Token nameToken = ctx.IDENTIFIER(0).getSymbol();

        TruffleString functionName = asTruffleString(nameToken, false);

        int functionStartPos = nameToken.getStartIndex();
        frameDescriptorBuilder = FrameDescriptor.newBuilder();
        List<SLStatementNode> methodNodes = new ArrayList<>();

        scope = new LexicalScope(scope);

        int parameterCount = ctx.IDENTIFIER().size() - 1;

        for (int i = 0; i < parameterCount; i++) {
            Token paramToken = ctx.IDENTIFIER(i + 1).getSymbol();

            final SLReadArgumentNode readArg = new SLReadArgumentNode(i);
            readArg.setSourceSection(paramToken.getStartIndex(), paramToken.getText().length());
            SLExpressionNode assignment = createAssignment(createString(paramToken, false), readArg, i);
            methodNodes.add(assignment);
        }

        SLStatementNode bodyNode = STATEMENT_VISITOR.visitBlock(ctx.body);

        scope = scope.parent;
        methodNodes.add(bodyNode);
        final int bodyEndPos = bodyNode.getSourceEndIndex();
        final SourceSection functionSrc = source.createSection(functionStartPos, bodyEndPos - functionStartPos);
        final SLStatementNode methodBlock = new SLBlockNode(methodNodes.toArray(new SLStatementNode[methodNodes.size()]));
        methodBlock.setSourceSection(functionStartPos, bodyEndPos - functionStartPos);

        assert scope == null : "Wrong scoping of blocks in parser";

        final SLFunctionBodyNode functionBodyNode = new SLFunctionBodyNode(methodBlock);
        functionBodyNode.setSourceSection(functionSrc.getCharIndex(), functionSrc.getCharLength());

        final SLRootNode rootNode = new SLRootNode(language, frameDescriptorBuilder.build(), functionBodyNode, functionSrc, functionName);
        functions.put(functionName, rootNode.getCallTarget());

        frameDescriptorBuilder = null;
        scope = null;

        return null;
    }

    private SLStringLiteralNode createString(Token name, boolean removeQuotes) {
        SLStringLiteralNode node = new SLStringLiteralNode(asTruffleString(name, removeQuotes));
        node.setSourceSection(name.getStartIndex(), name.getStopIndex() - name.getStartIndex() + 1);
        return node;
    }

    private class SLStatementVisitor extends SimpleLanguageOperationsBaseVisitor<SLStatementNode> {
        @Override
        public SLStatementNode visitBlock(BlockContext ctx) {
            scope = new LexicalScope(scope);

            int startPos = ctx.s.getStartIndex();
            int endPos = ctx.e.getStopIndex() + 1;

            List<SLStatementNode> bodyNodes = new ArrayList<>();

            for (StatementContext child : ctx.statement()) {
                bodyNodes.add(visitStatement(child));
            }

            scope = scope.parent;

            List<SLStatementNode> flattenedNodes = new ArrayList<>(bodyNodes.size());
            flattenBlocks(bodyNodes, flattenedNodes);
            int n = flattenedNodes.size();
            for (int i = 0; i < n; i++) {
                SLStatementNode statement = flattenedNodes.get(i);
                if (statement.hasSource() && !isHaltInCondition(statement)) {
                    statement.addStatementTag();
                }
            }
            SLBlockNode blockNode = new SLBlockNode(flattenedNodes.toArray(new SLStatementNode[flattenedNodes.size()]));
            blockNode.setSourceSection(startPos, endPos - startPos);
            return blockNode;
        }

        private void flattenBlocks(Iterable<? extends SLStatementNode> bodyNodes, List<SLStatementNode> flattenedNodes) {
            for (SLStatementNode n : bodyNodes) {
                if (n instanceof SLBlockNode) {
                    flattenBlocks(((SLBlockNode) n).getStatements(), flattenedNodes);
                } else {
                    flattenedNodes.add(n);
                }
            }
        }

        @Override
        public SLStatementNode visitDebugger_statement(Debugger_statementContext ctx) {
            final SLDebuggerNode debuggerNode = new SLDebuggerNode();
            srcFromToken(debuggerNode, ctx.d);
            return debuggerNode;
        }

        @Override
        public SLStatementNode visitBreak_statement(Break_statementContext ctx) {
            if (loopDepth == 0) {
                SemErr(ctx.b, "break used outside of loop");
            }
            final SLBreakNode breakNode = new SLBreakNode();
            srcFromToken(breakNode, ctx.b);
            return breakNode;
        }

        @Override
        public SLStatementNode visitContinue_statement(Continue_statementContext ctx) {
            if (loopDepth == 0) {
                SemErr(ctx.c, "continue used outside of loop");
            }
            final SLContinueNode continueNode = new SLContinueNode();
            srcFromToken(continueNode, ctx.c);
            return continueNode;
        }

        @Override
        public SLStatementNode visitWhile_statement(While_statementContext ctx) {
            SLExpressionNode conditionNode = EXPRESSION_VISITOR.visitExpression(ctx.condition);

            loopDepth++;
            SLStatementNode bodyNode = visitBlock(ctx.body);
            loopDepth--;

            conditionNode.addStatementTag();
            final int start = ctx.w.getStartIndex();
            final int end = bodyNode.getSourceEndIndex();
            final SLWhileNode whileNode = new SLWhileNode(conditionNode, bodyNode);
            whileNode.setSourceSection(start, end - start);
            return whileNode;
        }

        @Override
        public SLStatementNode visitIf_statement(If_statementContext ctx) {
            SLExpressionNode conditionNode = EXPRESSION_VISITOR.visitExpression(ctx.condition);
            SLStatementNode thenPartNode = visitBlock(ctx.then);
            SLStatementNode elsePartNode = ctx.alt == null ? null : visitBlock(ctx.alt);

            conditionNode.addStatementTag();
            final int start = ctx.i.getStartIndex();
            final int end = elsePartNode == null ? thenPartNode.getSourceEndIndex() : elsePartNode.getSourceEndIndex();
            final SLIfNode ifNode = new SLIfNode(conditionNode, thenPartNode, elsePartNode);
            ifNode.setSourceSection(start, end - start);
            return ifNode;
        }

        @Override
        public SLStatementNode visitReturn_statement(Return_statementContext ctx) {

            final SLExpressionNode valueNode = EXPRESSION_VISITOR.visitExpression(ctx.expression());

            final int start = ctx.r.getStartIndex();
            final int length = valueNode == null ? ctx.r.getText().length() : valueNode.getSourceEndIndex() - start;
            final SLReturnNode returnNode = new SLReturnNode(valueNode);
            returnNode.setSourceSection(start, length);
            return returnNode;
        }

        @Override
        public SLStatementNode visitStatement(StatementContext ctx) {
            return visit(ctx.getChild(0));
        }

        @Override
        public SLStatementNode visitExpression_statement(Expression_statementContext ctx) {
            return EXPRESSION_VISITOR.visitExpression(ctx.expression());
        }

        @Override
        public SLStatementNode visitChildren(RuleNode arg0) {
            throw new UnsupportedOperationException("node: " + arg0.getClass().getSimpleName());
        }
    }

    private class SLExpressionVisitor extends SimpleLanguageOperationsBaseVisitor<SLExpressionNode> {
        @Override
        public SLExpressionNode visitExpression(ExpressionContext ctx) {
            return createBinary(ctx.logic_term(), ctx.OP_OR());
        }

        @Override
        public SLExpressionNode visitLogic_term(Logic_termContext ctx) {
            return createBinary(ctx.logic_factor(), ctx.OP_AND());
        }

        @Override
        public SLExpressionNode visitLogic_factor(Logic_factorContext ctx) {
            return createBinary(ctx.arithmetic(), ctx.OP_COMPARE());
        }

        @Override
        public SLExpressionNode visitArithmetic(ArithmeticContext ctx) {
            return createBinary(ctx.term(), ctx.OP_ADD());
        }

        @Override
        public SLExpressionNode visitTerm(TermContext ctx) {
            return createBinary(ctx.factor(), ctx.OP_MUL());
        }

        private SLExpressionNode createBinary(List<? extends ParserRuleContext> children, TerminalNode op) {
            if (op == null) {
                assert children.size() == 1;
                return visit(children.get(0));
            } else {
                assert children.size() == 2;
                return createBinary(op.getSymbol(), visit(children.get(0)), visit(children.get(1)));
            }
        }

        private SLExpressionNode createBinary(List<? extends ParserRuleContext> children, List<TerminalNode> ops) {
            assert children.size() == ops.size() + 1;

            SLExpressionNode result = visit(children.get(0));

            for (int i = 0; i < ops.size(); i++) {
                result = createBinary(ops.get(i).getSymbol(), result, visit(children.get(i + 1)));
            }

            return result;
        }

        private SLExpressionNode createBinary(Token opToken, SLExpressionNode leftNode, SLExpressionNode rightNode) {
            final SLExpressionNode leftUnboxed = SLUnboxNodeGen.create(leftNode);
            final SLExpressionNode rightUnboxed = SLUnboxNodeGen.create(rightNode);

            final SLExpressionNode result;
            switch (opToken.getText()) {
                case "+":
                    result = SLAddNodeGen.create(leftUnboxed, rightUnboxed);
                    break;
                case "*":
                    result = SLMulNodeGen.create(leftUnboxed, rightUnboxed);
                    break;
                case "/":
                    result = SLDivNodeGen.create(leftUnboxed, rightUnboxed);
                    break;
                case "-":
                    result = SLSubNodeGen.create(leftUnboxed, rightUnboxed);
                    break;
                case "<":
                    result = SLLessThanNodeGen.create(leftUnboxed, rightUnboxed);
                    break;
                case "<=":
                    result = SLLessOrEqualNodeGen.create(leftUnboxed, rightUnboxed);
                    break;
                case ">":
                    result = SLLogicalNotNodeGen.create(SLLessOrEqualNodeGen.create(leftUnboxed, rightUnboxed));
                    break;
                case ">=":
                    result = SLLogicalNotNodeGen.create(SLLessThanNodeGen.create(leftUnboxed, rightUnboxed));
                    break;
                case "==":
                    result = SLEqualNodeGen.create(leftUnboxed, rightUnboxed);
                    break;
                case "!=":
                    result = SLLogicalNotNodeGen.create(SLEqualNodeGen.create(leftUnboxed, rightUnboxed));
                    break;
                case "&&":
                    result = new SLLogicalAndNode(leftUnboxed, rightUnboxed);
                    break;
                case "||":
                    result = new SLLogicalOrNode(leftUnboxed, rightUnboxed);
                    break;
                default:
                    throw new RuntimeException("unexpected operation: " + opToken.getText());
            }

            int start = leftNode.getSourceCharIndex();
            int length = rightNode.getSourceEndIndex() - start;
            result.setSourceSection(start, length);
            result.addExpressionTag();

            return result;
        }

        @Override
        public SLExpressionNode visitNameAccess(NameAccessContext ctx) {

            if (ctx.member_expression().isEmpty()) {
                return createRead(createString(ctx.IDENTIFIER().getSymbol(), false));
            }

            MemberExpressionVisitor visitor = new MemberExpressionVisitor(null, null,
                            createString(ctx.IDENTIFIER().getSymbol(), false));

            for (Member_expressionContext child : ctx.member_expression()) {
                visitor.visit(child);
            }

            return visitor.receiver;
        }

        @Override
        public SLExpressionNode visitStringLiteral(StringLiteralContext ctx) {
            return createString(ctx.STRING_LITERAL().getSymbol(), true);
        }

        @Override
        public SLExpressionNode visitNumericLiteral(NumericLiteralContext ctx) {
            Token literalToken = ctx.NUMERIC_LITERAL().getSymbol();
            SLExpressionNode result;
            try {
                /* Try if the literal is small enough to fit into a long value. */
                result = new SLLongLiteralNode(Long.parseLong(literalToken.getText()));
            } catch (NumberFormatException ex) {
                /* Overflow of long value, so fall back to BigInteger. */
                result = new SLBigIntegerLiteralNode(new BigInteger(literalToken.getText()));
            }
            srcFromToken(result, literalToken);
            result.addExpressionTag();
            return result;
        }

        @Override
        public SLExpressionNode visitParenExpression(ParenExpressionContext ctx) {

            SLExpressionNode expressionNode = visitExpression(ctx.expression());
            if (expressionNode == null) {
                return null;
            }

            int start = ctx.start.getStartIndex();
            int length = ctx.stop.getStopIndex() - start + 1;

            final SLParenExpressionNode result = new SLParenExpressionNode(expressionNode);
            result.setSourceSection(start, length);
            return result;
        }

    }

    private class MemberExpressionVisitor extends SimpleLanguageOperationsBaseVisitor<SLExpressionNode> {
        SLExpressionNode receiver;
        private SLExpressionNode assignmentReceiver;
        private SLExpressionNode assignmentName;

        MemberExpressionVisitor(SLExpressionNode r, SLExpressionNode assignmentReceiver, SLExpressionNode assignmentName) {
            this.receiver = r;
            this.assignmentReceiver = assignmentReceiver;
            this.assignmentName = assignmentName;
        }

        @Override
        public SLExpressionNode visitMemberCall(MemberCallContext ctx) {
            List<SLExpressionNode> parameters = new ArrayList<>();
            if (receiver == null) {
                receiver = createRead(assignmentName);
            }

            for (ExpressionContext child : ctx.expression()) {
                parameters.add(EXPRESSION_VISITOR.visitExpression(child));
            }

            final SLExpressionNode result = new SLInvokeNode(receiver, parameters.toArray(new SLExpressionNode[parameters.size()]));

            final int startPos = receiver.getSourceCharIndex();
            final int endPos = ctx.stop.getStopIndex() + 1;
            result.setSourceSection(startPos, endPos - startPos);
            result.addExpressionTag();

            assignmentReceiver = receiver;
            receiver = result;
            assignmentName = null;
            return result;
        }

        @Override
        public SLExpressionNode visitMemberAssign(MemberAssignContext ctx) {
            final SLExpressionNode result;
            if (assignmentName == null) {
                SemErr(ctx.expression().start, "invalid assignment target");
                result = null;
            } else if (assignmentReceiver == null) {
                SLExpressionNode valueNode = EXPRESSION_VISITOR.visitExpression(ctx.expression());
                result = createAssignment((SLStringLiteralNode) assignmentName, valueNode, null);
            } else {
                // create write property
                SLExpressionNode valueNode = EXPRESSION_VISITOR.visitExpression(ctx.expression());

                result = SLWritePropertyNodeGen.create(assignmentReceiver, assignmentName, valueNode);

                final int start = assignmentReceiver.getSourceCharIndex();
                final int length = valueNode.getSourceEndIndex() - start + 1;
                result.setSourceSection(start, length);
                result.addExpressionTag();
            }

            assignmentReceiver = receiver;
            receiver = result;
            assignmentName = null;

            return result;
        }

        @Override
        public SLExpressionNode visitMemberField(MemberFieldContext ctx) {
            if (receiver == null) {
                receiver = createRead(assignmentName);
            }

            SLExpressionNode nameNode = createString(ctx.IDENTIFIER().getSymbol(), false);
            assignmentName = nameNode;

            final SLExpressionNode result = SLReadPropertyNodeGen.create(receiver, nameNode);

            final int startPos = receiver.getSourceCharIndex();
            final int endPos = nameNode.getSourceEndIndex();
            result.setSourceSection(startPos, endPos - startPos);
            result.addExpressionTag();

            assignmentReceiver = receiver;
            receiver = result;

            return result;
        }

        @Override
        public SLExpressionNode visitMemberIndex(MemberIndexContext ctx) {
            if (receiver == null) {
                receiver = createRead(assignmentName);
            }

            SLExpressionNode nameNode = EXPRESSION_VISITOR.visitExpression(ctx.expression());
            assignmentName = nameNode;

            final SLExpressionNode result = SLReadPropertyNodeGen.create(receiver, nameNode);

            final int startPos = receiver.getSourceCharIndex();
            final int endPos = nameNode.getSourceEndIndex();
            result.setSourceSection(startPos, endPos - startPos);
            result.addExpressionTag();

            assignmentReceiver = receiver;
            receiver = result;

            return result;
        }

    }

    private SLExpressionNode createRead(SLExpressionNode nameTerm) {
        final TruffleString name = ((SLStringLiteralNode) nameTerm).executeGeneric(null);
        final SLExpressionNode result;
        final Integer frameSlot = scope.get(name);
        if (frameSlot != null) {
            result = SLReadLocalVariableNodeGen.create(frameSlot);
        } else {
            result = SLFunctionLiteralNodeGen.create(new SLStringLiteralNode(name));
        }
        result.setSourceSection(nameTerm.getSourceCharIndex(), nameTerm.getSourceLength());
        result.addExpressionTag();
        return result;
    }

    private SLExpressionNode createAssignment(SLStringLiteralNode assignmentName, SLExpressionNode valueNode, Integer index) {

        TruffleString name = assignmentName.executeGeneric(null);

        Integer frameSlot = scope.get(name);
        boolean newVariable = false;
        if (frameSlot == null) {
            frameSlot = frameDescriptorBuilder.addSlot(FrameSlotKind.Illegal, name, index);
            scope.names.put(name, frameSlot);
            newVariable = true;
        }
        SLExpressionNode result = SLWriteLocalVariableNodeGen.create(valueNode, frameSlot, assignmentName, newVariable);

        assert index != null || valueNode.hasSource();

        if (valueNode.hasSource()) {
            final int start = assignmentName.getSourceCharIndex();
            final int length = valueNode.getSourceEndIndex() - start;
            result.setSourceSection(start, length);
        }

        if (index == null) {
            result.addExpressionTag();
        }

        return result;
    }

    private static boolean isHaltInCondition(SLStatementNode statement) {
        return (statement instanceof SLIfNode) || (statement instanceof SLWhileNode);
    }

    private static void srcFromToken(SLStatementNode node, Token token) {
        node.setSourceSection(token.getStartIndex(), token.getText().length());
    }

}

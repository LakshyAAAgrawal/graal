/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.dsl.processor.operations.instructions;

import static com.oracle.truffle.dsl.processor.operations.OperationGeneratorUtils.combineBoxingBits;
import static com.oracle.truffle.dsl.processor.operations.OperationGeneratorUtils.createWriteOpcode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeMirror;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.TruffleTypes;
import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeMirror;
import com.oracle.truffle.dsl.processor.java.model.CodeVariableElement;
import com.oracle.truffle.dsl.processor.operations.Operation.BuilderVariables;
import com.oracle.truffle.dsl.processor.operations.OperationGeneratorUtils;
import com.oracle.truffle.dsl.processor.operations.OperationsContext;

public abstract class Instruction {

    public static class ExecutionVariables {
        public CodeVariableElement bc;
        public CodeVariableElement bci;
        public CodeVariableElement localFrame;
        public CodeVariableElement stackFrame;
        public CodeVariableElement sp;
        public CodeVariableElement consts;
        public CodeVariableElement children;
        public CodeVariableElement tracer;

        public FrameKind specializedKind;
    }

    public static class EmitArguments {
        public CodeTree[] constants;
        public CodeTree[] children;
        public CodeTree[] locals;
        public CodeTree[] localRuns;
        public CodeTree[] branchTargets;
        public CodeTree variadicCount;
        public CodeTree[] arguments;
        public CodeTree[] instruments;
    }

    protected final ProcessorContext context = ProcessorContext.getInstance();
    protected final TruffleTypes types = context.getTypes();

    protected final OperationsContext ctx;
    public final String name;
    public final int id;
    public final int numPushedValues;

    public final String internalName;

    // --------------------- arguments ------------------------

    private List<Object> constants = new ArrayList<>();
    private List<TypeMirror> constantTypes = new ArrayList<>();
    private List<Object> children = new ArrayList<>();
    private List<Object> locals = new ArrayList<>();
    private List<Object> localRuns = new ArrayList<>();
    private List<Object> arguments = new ArrayList<>();
    private List<Object> popIndexed = new ArrayList<>();
    private List<Object> popSimple = new ArrayList<>();
    private boolean isVariadic;
    private List<Object> branchTargets = new ArrayList<>();
    private List<Object> branchProfiles = new ArrayList<>();
    private List<Object> stateBits = new ArrayList<>();
    private List<Object> instruments = new ArrayList<>();

    public boolean isCommon;
    private boolean frozen;

    private static final String CONSTANT_OFFSET_SUFFIX = "_CONSTANT_OFFSET";
    private static final String CHILDREN_OFFSET_SUFFIX = "_CHILDREN_OFFSET";
    private static final String LOCALS_OFFSET_SUFFIX = "_LOCALS_OFFSET";
    private static final String LOCAL_RUNS_OFFSET_SUFFIX = "_LOCAL_RUNS_OFFSET";
    private static final String ARGUMENT_OFFSET_SUFFIX = "_ARGUMENT_OFFSET";
    private static final String POP_INDEXED_OFFSET_SUFFIX = "_POP_INDEXED_OFFSET";
    private static final String VARIADIC_OFFSET_SUFFIX = "_VARIADIC_OFFSET";
    private static final String BRANCH_TARGET_OFFSET_SUFFIX = "_BRANCH_TARGET_OFFSET";
    private static final String BRANCH_PROFILE_OFFSET_SUFFIX = "_BRANCH_PROFILE_OFFSET";
    private static final String STATE_BITS_OFFSET_SUFFIX = "_STATE_BITS_OFFSET";
    private static final String INSTRUMENT_OFFSET_SUFFIX = "_INSTRUMENT_OFFSET";
    private static final String LENGTH_SUFFIX = "_LENGTH";

    private int addInstructionArgument(List<Object> holder, Object marker) {
        int index = -1;
        if (marker != null) {
            index = holder.indexOf(marker);
        }

        if (index == -1) {
            if (frozen) {
                throw new AssertionError();
            }

            index = holder.size();
            holder.add(marker);
        }
        return index;
    }

    public int addConstant(Object marker, TypeMirror type) {
        int result = addInstructionArgument(constants, marker);
        if (result == constantTypes.size()) {
            constantTypes.add(type);
        }

        return result;
    }

    public int addChild(Object marker) {
        return addInstructionArgument(children, marker);
    }

    public int addLocal(Object marker) {
        return addInstructionArgument(locals, marker);
    }

    public int addLocalRun(Object marker) {
        return addInstructionArgument(localRuns, marker);
    }

    public int addArgument(Object marker) {
        return addInstructionArgument(arguments, marker);
    }

    public int addPopIndexed(Object marker) {
        if (!ctx.hasBoxingElimination()) {
            return addPopSimple(marker);
        }
        if (!popSimple.isEmpty()) {
            throw new AssertionError("cannot mix simple and indexed pops");
        }
        return addInstructionArgument(popIndexed, marker);
    }

    public int addPopSimple(Object marker) {
        if (!popIndexed.isEmpty()) {
            throw new AssertionError("cannot mix simple and indexed pops");
        }
        return addInstructionArgument(popSimple, marker);
    }

    public void setVariadic() {
        isVariadic = true;
    }

    public int addBranchTarget(Object marker) {
        return addInstructionArgument(branchTargets, marker);
    }

    public int addBranchProfile(Object marker) {
        return addInstructionArgument(branchProfiles, marker);
    }

    public int addStateBits(Object marker) {
        return addInstructionArgument(stateBits, marker);
    }

    public int addInstrument(Object marker) {
        return addInstructionArgument(instruments, marker);
    }

    private int getConstantsOffset() {
        return opcodeLength();
    }

    private int getChildrenOffset() {
        return getConstantsOffset() + (constants.isEmpty() ? 0 : 1);
    }

    private int getLocalsOffset() {
        return getChildrenOffset() + (children.isEmpty() ? 0 : 1);
    }

    private int getLocalRunsOffset() {
        return getLocalsOffset() + locals.size();
    }

    private int getArgumentsOffset() {
        return getLocalRunsOffset() + localRuns.size() * 2;
    }

    private int getPopIndexedOffset() {
        return getArgumentsOffset() + arguments.size();
    }

    private int getVariadicOffset() {
        return getPopIndexedOffset() + (popIndexed.size() + 1) / 2;
    }

    private int getBranchTargetsOffset() {
        return getVariadicOffset() + (isVariadic ? 1 : 0);
    }

    private int getBranchProfileOffset() {
        return getBranchTargetsOffset() + branchTargets.size();
    }

    private int getStateBitsOffset() {
        return getBranchProfileOffset() + (branchProfiles.isEmpty() ? 0 : 1);
    }

    public CodeTree createStateBitsOffset(int index) {
        return CodeTreeBuilder.singleString(internalName + STATE_BITS_OFFSET_SUFFIX + " + " + index);
    }

    private int getInstrumentsOffset() {
        return getStateBitsOffset() + stateBits.size();
    }

    public int length() {
        frozen = true;
        return getInstrumentsOffset() + instruments.size();
    }

    private CodeTree createIndirectIndex(ExecutionVariables vars, String suffix, int index) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        b.startCall("unsafeFromBytecode");
        b.variable(vars.bc);
        b.startGroup().variable(vars.bci).string(" + " + internalName + suffix).end();
        b.end();
        b.string(" + " + index);

        return b.build();
    }

    private CodeTree createDirectIndex(ExecutionVariables vars, String suffix, int index, boolean write) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        if (write) {
            b.startGroup();
            b.variable(vars.bc);
            b.string("[");
        } else {
            b.startCall("unsafeFromBytecode");
            b.variable(vars.bc);
        }
        b.startGroup().variable(vars.bci).string(" + " + internalName + suffix + " + " + index).end();

        if (write) {
            b.string("]").end();
        } else {
            b.end();
        }

        return b.build();
    }

    public CodeTree createConstantIndex(ExecutionVariables vars, int index) {
        return createIndirectIndex(vars, CONSTANT_OFFSET_SUFFIX, index);
    }

    public CodeTree createChildIndex(ExecutionVariables vars, int index) {
        return createIndirectIndex(vars, CHILDREN_OFFSET_SUFFIX, index);
    }

    public CodeTree createLocalIndex(ExecutionVariables vars, int index, boolean write) {
        return createDirectIndex(vars, LOCALS_OFFSET_SUFFIX, index, write);
    }

    public CodeTree createArgumentIndex(ExecutionVariables vars, int index, boolean write) {
        return createDirectIndex(vars, ARGUMENT_OFFSET_SUFFIX, index, write);
    }

    public CodeTree createPopIndexedIndex(ExecutionVariables vars, int index, boolean write) {
        if (!ctx.hasBoxingElimination()) {
            throw new AssertionError("there is no boxing elimination");
            // return CodeTreeBuilder.singleString("0 /* XXXXXXXXXXX */");
        }
        if (write) {
            throw new AssertionError("cannot write to indexed pops");
        }
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        b.startParantheses();
        if (index % 2 == 1) {
            b.startParantheses();
        }

        b.tree(createDirectIndex(vars, POP_INDEXED_OFFSET_SUFFIX, index / 2, false));

        if (index % 2 == 1) {
            b.string(" >> 8").end();
        }

        b.string(" & 0xff").end();

        return b.build();
    }

    public CodeTree createVariadicIndex(ExecutionVariables vars, boolean write) {
        return createDirectIndex(vars, VARIADIC_OFFSET_SUFFIX, 0, write);
    }

    public CodeTree createBranchTargetIndex(ExecutionVariables vars, int index, boolean write) {
        return createDirectIndex(vars, BRANCH_TARGET_OFFSET_SUFFIX, index, write);
    }

    public CodeTree createBranchProfileIndex(ExecutionVariables vars, int index, boolean write) {
        return createDirectIndex(vars, BRANCH_PROFILE_OFFSET_SUFFIX, index * 2, write);
    }

    public CodeTree createStateBitsIndex(ExecutionVariables vars, int index, boolean write) {
        return createDirectIndex(vars, STATE_BITS_OFFSET_SUFFIX, index, write);
    }

    public CodeTree createInstrument(ExecutionVariables vars, int index) {
        return createDirectIndex(vars, INSTRUMENT_OFFSET_SUFFIX, index, false);
    }

    public CodeTree createLength() {
        return CodeTreeBuilder.singleString(internalName + LENGTH_SUFFIX);
    }

    public boolean[] typedConstants() {
        boolean[] result = new boolean[constantTypes.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = constantTypes.get(i) != null;
        }

        return result;
    }

    public int numConstants() {
        return constants.size();
    }

    public int numLocals() {
        return locals.size();
    }

    public int numLocalRuns() {
        return localRuns.size();
    }

    public int numArguments() {
        return arguments.size();
    }

    public int numBranchTargets() {
        return branchTargets.size();
    }

    public int numInstruments() {
        return instruments.size();
    }

    public boolean splitOnBoxingElimination() {
        return false;
    }

    public boolean alwaysBoxed() {
        if (numPushedValues == 0) {
            return true;
        } else {
            throw new AssertionError(name);
        }
    }

    public List<FrameKind> getBoxingEliminationSplits() {
        return ctx.getBoxingKinds();
    }

    public boolean hasGeneric() {
        return false;
    }

    public CodeVariableElement opcodeIdField;

    public void setOpcodeIdField(CodeVariableElement opcodeIdField) {
        this.opcodeIdField = opcodeIdField;
    }

    Instruction(OperationsContext ctx, String name, int id, int numPushedValues) {
        this.ctx = ctx;
        this.name = name;
        this.id = id;
        this.internalName = OperationGeneratorUtils.toScreamCase(name);
        this.numPushedValues = numPushedValues;

        this.opcodeIdField = new CodeVariableElement(Set.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL), context.getType(short.class), "INSTR_" + internalName);
        opcodeIdField.createInitBuilder().string("" + id);
    }

    public int opcodeLength() {
        return 1;
    }

    @SuppressWarnings("unused")
    protected CodeTree createCustomEmitCode(BuilderVariables vars, EmitArguments args) {
        return null;
    }

    @SuppressWarnings("unused")
    protected CodeTree createCustomEmitCodeAfter(BuilderVariables vars, EmitArguments args) {
        return null;
    }

    public final CodeTree createEmitCode(BuilderVariables vars, EmitArguments args) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        b.tree(createCustomEmitCode(vars, args));

        CodeTree numPop;

        if (isVariadic) {
            numPop = CodeTreeBuilder.createBuilder().tree(args.variadicCount).string(" + " + popSimple.size()).build();
        } else {
            numPop = CodeTreeBuilder.singleString(popSimple.size() + popIndexed.size() + "");
        }

        if (popIndexed.size() > 0) {
            b.startAssign("int[] predecessorBcis");
        } else {
            b.startStatement();
        }

        b.startCall("doBeforeEmitInstruction");
        b.tree(numPop);
        b.string(numPushedValues == 0 ? "false" : "true");
        b.string(numPushedValues > 0 && !alwaysBoxed() ? "true" : "false");
        b.end(2);

        // emit opcode
        b.tree(createWriteOpcode(vars.bc, vars.bci, combineBoxingBits(ctx, this, 0)));

        if (!constants.isEmpty()) {
            b.startAssign("int constantsStart");
            b.startCall(vars.consts, "size").end();
            b.end();

            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getConstantsOffset() + "] = (short) constantsStart").end();

            for (int i = 0; i < constants.size(); i++) {
                CodeTree initCode = null;
                if (constants.get(i) != null) {
                    initCode = createConstantInitCode(vars, args, constants.get(i), i);
                }

                if (initCode == null && args.constants != null) {
                    initCode = args.constants[i];
                }

                if (initCode == null) {
                    initCode = CodeTreeBuilder.singleString("null");
                }

                b.startStatement().startCall(vars.consts, "add");
                b.tree(initCode);
                b.end(2);
            }
        }

        if (!children.isEmpty()) {
            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getChildrenOffset() + "] = (short) ");
            b.string("numChildNodes");
            b.end();

            b.statement("numChildNodes += " + children.size());
        }

        for (int i = 0; i < locals.size(); i++) {
            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getLocalsOffset() + " + " + i + "] = (short) ");
            b.startCall("getLocalIndex").tree(args.locals[i]).end();
            b.end();
        }

        for (int i = 0; i < localRuns.size(); i++) {
            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getLocalRunsOffset() + " + " + (i * 2) + "] = (short) ");
            b.startCall("getLocalRunStart").tree(args.localRuns[i]).end();
            b.end();

            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getLocalRunsOffset() + " + " + (i * 2 + 1) + "] = (short) ");
            b.startCall("getLocalRunLength").tree(args.localRuns[i]).end();
            b.end();
        }

        if (isVariadic) {
            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getVariadicOffset() + "] = (short) ");
            b.startParantheses().tree(args.variadicCount).end();
            b.end();
        } else {
            for (int i = 0; i < popIndexed.size(); i++) {
                b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getPopIndexedOffset() + " + " + (i / 2) + "] ");
                if (i % 2 == 1) {
                    b.string("|");
                }
                b.string("= (short) ((").variable(vars.bci).string(" - predecessorBcis[" + i + "] < 256 ? ").variable(vars.bci).string(" - predecessorBcis[" + i + "] : 0)");
                if (i % 2 == 1) {
                    b.string(" << 8");
                }
                b.string(")").end();
            }
        }

        for (int i = 0; i < branchTargets.size(); i++) {
            b.startStatement().startCall("labelFills", "add").startNew("LabelFill");
            b.startGroup().variable(vars.bci).string(" + " + getBranchTargetsOffset() + " + " + i).end();
            b.tree(args.branchTargets[i]);
            b.end(3);
        }

        if (!branchProfiles.isEmpty()) {
            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getBranchProfileOffset() + "] = (short) ");
            b.string("numConditionProfiles");
            b.end();

            b.statement("numConditionProfiles += " + branchProfiles.size() * 2);
        }

        for (int i = 0; i < stateBits.size(); i++) {
            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getStateBitsOffset() + " + " + i + "] = 0").end();
        }

        for (int i = 0; i < arguments.size(); i++) {
            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getArgumentsOffset() + " + " + i + "] = (short) (int) ");
            b.tree(args.arguments[i]);
            b.end();
        }

        for (int i = 0; i < instruments.size(); i++) {
            b.startStatement().variable(vars.bc).string("[").variable(vars.bci).string(" + " + getInstrumentsOffset() + " + " + i + "] = (short) (int) ");
            b.tree(args.instruments[i]);
            b.end();
        }

        // superinstructions

        final int maxHistory = 8;

        b.startAssign("instructionHistory[++instructionHistoryIndex % " + maxHistory + "]").variable(opcodeIdField).end();

        boolean elseIf = false;
        for (SuperInstruction si : ctx.getSuperInstructions()) {
            Instruction[] instrs = si.getInstructions();

            if (instrs[instrs.length - 1].id == id) {
                elseIf = b.startIf(elseIf);
                for (int i = 1; i < instrs.length; i++) {
                    if (i != 1) {
                        b.string(" && ");
                    }
                    b.string("instructionHistory[(instructionHistoryIndex - " + i + " + " + maxHistory + ") % 8] == ");
                    b.variable(instrs[instrs.length - 1 - i].opcodeIdField);
                }
                b.end().startBlock();

                b.startStatement().variable(vars.bc).string("[");
                b.variable(vars.bci);
                // skips the last since BCI is still not incremented for current instruction
                for (int i = 0; i < instrs.length - 1; i++) {
                    b.string(" - ").tree(instrs[i].createLength());
                }
                b.string("] = ").tree(OperationGeneratorUtils.combineBoxingBits(ctx, si, 0)).end();

                b.end();
            }
        }

        b.startAssign(vars.bci).variable(vars.bci).string(" + ").tree(createLength()).end();

        b.tree(createCustomEmitCodeAfter(vars, args));

        return b.build();
    }

    public abstract CodeTree createExecuteCode(ExecutionVariables vars);

    public CodeTree createExecuteUncachedCode(ExecutionVariables vars) {
        return createExecuteCode(vars);
    }

    public boolean neverInUncached() {
        return false;
    }

    @SuppressWarnings("unused")
    protected CodeTree createConstantInitCode(BuilderVariables vars, EmitArguments args, Object marker, int index) {
        return null;
    }

    private static void printList(StringBuilder sb, List<Object> holder, String name) {
        if (!holder.isEmpty()) {
            sb.append("  ").append(name).append(":\n");
            int index = 0;
            for (Object marker : holder) {
                sb.append(String.format("    [%2d] %s\n", index++, marker == null ? "<unnamed>" : marker));
            }
        }
    }

    public String dumpInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("\n");

        printList(sb, constants, "Constants");
        printList(sb, children, "Children");
        printList(sb, locals, "Locals");
        printList(sb, localRuns, "Local Runs");
        printList(sb, popIndexed, "Indexed Pops");
        printList(sb, popSimple, "Simple Pops");
        if (isVariadic) {
            sb.append("  Variadic\n");
        }
        if (numPushedValues > 0 && alwaysBoxed()) {
            sb.append("  Always Boxed\n");
        } else if (splitOnBoxingElimination()) {
            sb.append("  Split on Boxing Elimination\n");
        }
        sb.append("  Pushed Values: ").append(numPushedValues).append("\n");
        printList(sb, branchTargets, "Branch Targets");
        printList(sb, branchProfiles, "Branch Profiles");
        printList(sb, stateBits, "State Bitsets");

        return sb.toString();
    }

    @SuppressWarnings("unused")
    public CodeVariableElement boxingEliminationReplacement(FrameKind kind) {
        return null;
    }

    public abstract CodeTree createPrepareAOT(ExecutionVariables vars, CodeTree language, CodeTree root);

    public boolean isExplicitFlowControl() {
        return false;
    }

    public boolean isInstrumentationOnly() {
        return false;
    }

    public List<TypeMirror> getBuilderArgumentTypes() {
        ArrayList<TypeMirror> result = new ArrayList<>();

        for (TypeMirror mir : constantTypes) {
            if (mir != null) {
                result.add(mir);
            }
        }

        for (int i = 0; i < arguments.size(); i++) {
            result.add(context.getType(int.class));
        }

        for (int i = 0; i < branchTargets.size(); i++) {
            result.add(types.OperationLabel);
        }

        for (int i = 0; i < locals.size(); i++) {
            result.add(types.OperationLocal);
        }

        for (int i = 0; i < localRuns.size(); i++) {
            result.add(new CodeTypeMirror.ArrayCodeTypeMirror(types.OperationLocal));
        }

        return result;
    }

    public int numLocalReferences() {
        return 0;
    }

    public int numPopStatic() {
        return popIndexed.size() + popSimple.size();
    }

    private void createAddArgument(CodeTreeBuilder b, String kind, Runnable value) {
        b.startIndention().newLine();
        b.startNewArray((ArrayType) context.getType(Object[].class), null);
        b.staticReference(context.getDeclaredType("com.oracle.truffle.api.operation.introspection.Argument.ArgumentKind"), kind);
        b.startGroup();
        value.run();
        b.end(); // group
        b.end(); // array
        b.end();
    }

    public CodeTree createDumpCode(ExecutionVariables vars) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        b.startAssign("Object[] dec");
        b.startNewArray((ArrayType) context.getType(Object[].class), null);
        b.string("$bci");
        b.doubleQuote(name);
        b.string("Arrays.copyOfRange($bc, $bci, $bci + " + internalName + LENGTH_SUFFIX + ")");
        b.startNewArray((ArrayType) context.getType(Object[].class), null);
        for (int i = 0; i < constants.size(); i++) {
            int ci = i;
            createAddArgument(b, "CONSTANT", () -> {
                b.variable(vars.consts).string("[").tree(createConstantIndex(vars, ci)).string("]");
            });
        }

        for (int i = 0; i < locals.size(); i++) {
            int ci = i;
            createAddArgument(b, "LOCAL", () -> b.cast(context.getType(int.class)).tree(createLocalIndex(vars, ci, false)));
        }

        for (int i = 0; i < arguments.size(); i++) {
            int ci = i;
            createAddArgument(b, "ARGUMENT", () -> b.cast(context.getType(int.class)).tree(createArgumentIndex(vars, ci, false)));
        }

        for (int i = 0; i < popIndexed.size(); i++) {
            int ci = i;
            createAddArgument(b, "CHILD_OFFSET", () -> b.cast(context.getType(int.class)).tree(createPopIndexedIndex(vars, ci, false)));
        }

        if (isVariadic) {
            createAddArgument(b, "VARIADIC", () -> b.cast(context.getType(int.class)).tree(createVariadicIndex(vars, false)));
        }

        for (int i = 0; i < branchTargets.size(); i++) {
            int ci = i;
            createAddArgument(b, "BRANCH_OFFSET", () -> b.cast(context.getType(int.class)).tree(createBranchTargetIndex(vars, ci, false)));
        }

        for (int i = 0; i < instruments.size(); i++) {
            int ci = i;
            createAddArgument(b, "INSTRUMENT", () -> b.cast(context.getTypes().InstrumentTreeNode).string("$this.instruments[").tree(createInstrument(vars, ci)).string("]"));
        }

        b.end(3); // arg array, instr array, stmt

        return b.build();

    }

    private CodeVariableElement createConstant(String constantName, int value) {
        CodeVariableElement result = new CodeVariableElement(
                        Set.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL),
                        context.getType(int.class),
                        constantName);
        result.createInitBuilder().string("" + value);

        return result;
    }

    public List<CodeVariableElement> createInstructionFields() {
        List<CodeVariableElement> result = new ArrayList<>();
        result.add(opcodeIdField);
        if (!constants.isEmpty()) {
            result.add(createConstant(internalName + CONSTANT_OFFSET_SUFFIX, getConstantsOffset()));
        }
        if (!children.isEmpty()) {
            result.add(createConstant(internalName + CHILDREN_OFFSET_SUFFIX, getChildrenOffset()));
        }
        if (!localRuns.isEmpty()) {
            result.add(createConstant(internalName + LOCAL_RUNS_OFFSET_SUFFIX, getLocalRunsOffset()));
        }
        if (!arguments.isEmpty()) {
            result.add(createConstant(internalName + ARGUMENT_OFFSET_SUFFIX, getArgumentsOffset()));
        }
        if (!locals.isEmpty()) {
            result.add(createConstant(internalName + LOCALS_OFFSET_SUFFIX, getLocalsOffset()));
        }
        if (!popIndexed.isEmpty()) {
            result.add(createConstant(internalName + POP_INDEXED_OFFSET_SUFFIX, getPopIndexedOffset()));
        }
        if (isVariadic) {
            result.add(createConstant(internalName + VARIADIC_OFFSET_SUFFIX, getVariadicOffset()));
        }
        if (!branchTargets.isEmpty()) {
            result.add(createConstant(internalName + BRANCH_TARGET_OFFSET_SUFFIX, getBranchTargetsOffset()));
        }
        if (!branchProfiles.isEmpty()) {
            result.add(createConstant(internalName + BRANCH_PROFILE_OFFSET_SUFFIX, getBranchProfileOffset()));
        }
        if (!stateBits.isEmpty()) {
            result.add(createConstant(internalName + STATE_BITS_OFFSET_SUFFIX, getStateBitsOffset()));
        }
        if (!instruments.isEmpty()) {
            result.add(createConstant(internalName + INSTRUMENT_OFFSET_SUFFIX, getInstrumentsOffset()));
        }
        result.add(createConstant(internalName + LENGTH_SUFFIX, length()));

        return result;
    }

    public boolean isVariadic() {
        return isVariadic;
    }
}

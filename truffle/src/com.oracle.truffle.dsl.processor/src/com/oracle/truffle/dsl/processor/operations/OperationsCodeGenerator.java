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
package com.oracle.truffle.dsl.processor.operations;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

import com.oracle.truffle.dsl.processor.AnnotationProcessor;
import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.generator.CodeTypeElementFactory;
import com.oracle.truffle.dsl.processor.generator.GeneratorUtils;
import com.oracle.truffle.dsl.processor.java.ElementUtils;
import com.oracle.truffle.dsl.processor.java.model.CodeAnnotationMirror;
import com.oracle.truffle.dsl.processor.java.model.CodeExecutableElement;
import com.oracle.truffle.dsl.processor.java.model.CodeNames;
import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeElement;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeMirror.ArrayCodeTypeMirror;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeMirror.DeclaredCodeTypeMirror;
import com.oracle.truffle.dsl.processor.java.model.CodeVariableElement;
import com.oracle.truffle.dsl.processor.java.model.GeneratedTypeMirror;
import com.oracle.truffle.dsl.processor.operations.Operation.BuilderVariables;
import com.oracle.truffle.dsl.processor.operations.instructions.CustomInstruction;
import com.oracle.truffle.dsl.processor.operations.instructions.FrameKind;
import com.oracle.truffle.dsl.processor.operations.instructions.Instruction;

public class OperationsCodeGenerator extends CodeTypeElementFactory<OperationsData> {

    private ProcessorContext context;
    private OperationsData m;

    private static final Set<Modifier> MOD_FINAL = Set.of(Modifier.FINAL);
    private static final Set<Modifier> MOD_PUBLIC = Set.of(Modifier.PUBLIC);
    private static final Set<Modifier> MOD_PUBLIC_ABSTRACT = Set.of(Modifier.PUBLIC, Modifier.ABSTRACT);
    private static final Set<Modifier> MOD_PUBLIC_FINAL = Set.of(Modifier.PUBLIC, Modifier.FINAL);
    private static final Set<Modifier> MOD_PUBLIC_STATIC = Set.of(Modifier.PUBLIC, Modifier.STATIC);
    private static final Set<Modifier> MOD_PRIVATE = Set.of(Modifier.PRIVATE);
    private static final Set<Modifier> MOD_ABSTRACT = Set.of(Modifier.ABSTRACT);
    private static final Set<Modifier> MOD_PRIVATE_FINAL = Set.of(Modifier.PRIVATE, Modifier.FINAL);
    private static final Set<Modifier> MOD_PRIVATE_STATIC = Set.of(Modifier.PRIVATE, Modifier.STATIC);
    private static final Set<Modifier> MOD_PRIVATE_STATIC_ABSTRACT = Set.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.ABSTRACT);
    private static final Set<Modifier> MOD_PRIVATE_STATIC_FINAL = Set.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
    private static final Set<Modifier> MOD_PROTECTED = Set.of(Modifier.PROTECTED);
    private static final Set<Modifier> MOD_PROTECTED_STATIC = Set.of(Modifier.PROTECTED, Modifier.STATIC);
    private static final Set<Modifier> MOD_STATIC = Set.of(Modifier.STATIC);

    private OperationsBytecodeCodeGenerator bytecodeGenerator;

    private static final String OPERATION_NODES_IMPL_NAME = "OperationNodesImpl";
    private static final String OPERATION_BUILDER_IMPL_NAME = "Builder";
    private static final String BYTECODE_BASE_NAME = "BytecodeLoopBase";

    private static final int SER_CODE_CREATE_LABEL = -2;
    private static final int SER_CODE_CREATE_LOCAL = -3;
    private static final int SER_CODE_CREATE_OBJECT = -4;
    private static final int SER_CODE_END = -5;
    private static final int SER_CODE_METADATA = -6;

    private static final Class<?> DATA_INPUT_CLASS = ByteBuffer.class;
    private static final Class<?> DATA_INPUT_WRAP_CLASS = DataInput.class;
    private static final Class<?> DATA_OUTPUT_CLASS = DataOutput.class;

    private static final String DATA_READ_METHOD_PREFIX = "get";
    private static final String DATA_WRITE_METHOD_PREFIX = "write";

    private static final TypeMirror TYPE_UNSAFE = new GeneratedTypeMirror("sun.misc", "Unsafe");

    CodeTypeElement createOperationNodes() {
        CodeTypeElement typOperationNodes = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, OPERATION_NODES_IMPL_NAME,
                        generic(types.OperationNodes, m.getTemplateType().asType()));
        typOperationNodes.add(GeneratorUtils.createConstructorUsingFields(Set.of(), typOperationNodes));

        typOperationNodes.add(createOperationNodedsReparse());

        CodeExecutableElement mSetSources = new CodeExecutableElement(context.getType(void.class), "setSources");
        mSetSources.addParameter(new CodeVariableElement(arrayOf(types.Source), "sources"));
        mSetSources.createBuilder().statement("this.sources = sources");
        typOperationNodes.add(mSetSources);

        CodeExecutableElement mGetSources = new CodeExecutableElement(arrayOf(types.Source), "getSources");
        mGetSources.createBuilder().statement("return sources");
        typOperationNodes.add(mGetSources);

        CodeExecutableElement mSetNodes = new CodeExecutableElement(context.getType(void.class), "setNodes");
        mSetNodes.addParameter(new CodeVariableElement(arrayOf(m.getTemplateType().asType()), "nodes"));
        mSetNodes.createBuilder().statement("this.nodes = nodes");
        typOperationNodes.add(mSetNodes);

        for (String name : new String[]{"ensureSources", "ensureInstrumentation"}) {
            CodeExecutableElement m = CodeExecutableElement.clone(ElementUtils.findExecutableElement(types.OperationNodes, name));
            m.setSimpleName(CodeNames.of(name + "Accessor"));
            m.createBuilder().startStatement().startCall(name).variables(m.getParameters()).end(2);
            typOperationNodes.add(m);
        }

        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            typOperationNodes.add(createOperationNodesSerialize());
        }

        return typOperationNodes;
    }

    private CodeExecutableElement createOperationNodedsReparse() {
        CodeExecutableElement metReparse = GeneratorUtils.overrideImplement(types.OperationNodes, "reparseImpl");

        CodeTreeBuilder b = metReparse.createBuilder();

        b.statement(OPERATION_BUILDER_IMPL_NAME + " builder = new " + OPERATION_BUILDER_IMPL_NAME + "(this, true, config)");
        b.statement("((OperationParser) parse).parse(builder)");
        b.statement("builder.finish()");
        return metReparse;
    }

    private CodeExecutableElement createOperationNodesSerialize() {
        CodeExecutableElement met = GeneratorUtils.overrideImplement(types.OperationNodes, "serialize");

        CodeTreeBuilder b = met.createBuilder();

        b.statement(OPERATION_BUILDER_IMPL_NAME + " builder = new " + OPERATION_BUILDER_IMPL_NAME + "(null, false, config)");
        b.statement("builder.isSerializing = true");
        b.statement("builder.serBuffer = buffer");
        b.statement("builder.serCallback = callback");

        unwrapIOError(b, () -> {
            b.statement("((OperationParser) parse).parse(builder)");
        });

        b.statement("buffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) " + SER_CODE_END + ")");

        return met;
    }

    private CodeExecutableElement createDeserializeMethod() {
        CodeVariableElement parLanguage = new CodeVariableElement(types.TruffleLanguage, "language");
        CodeVariableElement parConfig = new CodeVariableElement(types.OperationConfig, "config");
        CodeVariableElement parBuffer = new CodeVariableElement(context.getType(DATA_INPUT_CLASS), "input");
        CodeVariableElement parCallback = new CodeVariableElement(context.getDeclaredType("com.oracle.truffle.api.operation.serialization.OperationDeserializer"), "callback");
        CodeExecutableElement met = new CodeExecutableElement(MOD_PUBLIC_STATIC, generic(types.OperationNodes, m.getTemplateType().asType()), "deserialize", parLanguage, parConfig, parBuffer,
                        parCallback);

        met.addThrownType(context.getType(IOException.class));

        CodeTreeBuilder b = met.createBuilder();

        b.startTryBlock();

        b.startReturn().startCall("create");
        b.variable(parConfig);
        b.string("b -> " + OPERATION_BUILDER_IMPL_NAME + ".deserializeParser(language, input, callback, b)");
        b.end(2);

        b.end().startCatchBlock(context.getType(IOError.class), "ex");

        b.startThrow().string("(IOException) ex.getCause()").end();

        b.end();

        return met;
    }

    private void unwrapIOError(CodeTreeBuilder b, Runnable inner) {
        b.startTryBlock();
        inner.run();
        b.end().startCatchBlock(context.getType(IOError.class), "ex");
        b.startThrow().string("(IOException) ex.getCause()").end();
        b.end();
    }

    private CodeExecutableElement createSerializeMethod(CodeTypeElement typBuilder, CodeTypeElement typBuilderImpl) {
        CodeVariableElement parConfig = new CodeVariableElement(types.OperationConfig, "config");
        CodeVariableElement parBuffer = new CodeVariableElement(context.getType(DATA_OUTPUT_CLASS), "buffer");
        CodeVariableElement parCallback = new CodeVariableElement(context.getDeclaredType("com.oracle.truffle.api.operation.serialization.OperationSerializer"), "callback");
        CodeVariableElement parParser = new CodeVariableElement(operationParser(typBuilder.asType()), "generator");
        CodeExecutableElement metCreate = new CodeExecutableElement(MOD_PUBLIC_STATIC, context.getType(void.class), "serialize");
        metCreate.addParameter(parConfig);
        metCreate.addParameter(parBuffer);
        metCreate.addParameter(parCallback);
        metCreate.addParameter(parParser);
        metCreate.addThrownType(context.getType(IOException.class));

        CodeTreeBuilder b = metCreate.getBuilder();

        b.startAssign(OPERATION_BUILDER_IMPL_NAME + " builder").startNew(typBuilderImpl.asType());
        // (
        b.string("null");
        b.string("false"); // isReparse
        b.variable(parConfig);
        // )
        b.end(2);

        b.statement("builder.isSerializing = true");
        b.statement("builder.serBuffer = buffer");
        b.statement("builder.serCallback = callback");

        unwrapIOError(b, () -> {
            b.startStatement().startCall("generator", "parse");
            b.string("builder");
            b.end(2);
        });

        b.statement("buffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) " + SER_CODE_END + ")");

        return metCreate;
    }

    private CodeExecutableElement createCreateMethod(CodeTypeElement typBuilder, CodeTypeElement typBuilderImpl) {
        CodeVariableElement parConfig = new CodeVariableElement(types.OperationConfig, "config");
        CodeVariableElement parParser = new CodeVariableElement(operationParser(typBuilder.asType()), "generator");
        CodeExecutableElement metCreate = new CodeExecutableElement(MOD_PUBLIC_STATIC, generic(types.OperationNodes, m.getTemplateType().asType()), "create");
        metCreate.addParameter(parConfig);
        metCreate.addParameter(parParser);

        CodeTreeBuilder b = metCreate.getBuilder();

        b.declaration("OperationNodesImpl", "nodes", "new OperationNodesImpl(generator)");
        b.startAssign(OPERATION_BUILDER_IMPL_NAME + " builder").startNew(typBuilderImpl.asType());
        // (
        b.string("nodes");
        b.string("false"); // isReparse
        b.variable(parConfig);
        // )
        b.end(2);

        b.startStatement().startCall("generator", "parse");
        b.string("builder");
        b.end(2);

        b.startStatement().startCall("builder", "finish").end(2);

        b.startReturn().string("nodes").end();

        return metCreate;
    }

    private static CodeVariableElement compFinal(CodeVariableElement el) {
        if (el.getType().getKind() == TypeKind.ARRAY) {
            GeneratorUtils.addCompilationFinalAnnotation(el, 1);
        } else {
            GeneratorUtils.addCompilationFinalAnnotation(el);
        }
        return el;
    }

    private static CodeVariableElement children(CodeVariableElement el) {
        el.addAnnotationMirror(new CodeAnnotationMirror(ProcessorContext.getInstance().getTypes().Node_Children));
        return el;
    }

    private CodeTypeElement createOperationSerNodeImpl() {
        CodeTypeElement typOperationNodeImpl = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "OperationSerNodeImpl", m.getTemplateType().asType());
        typOperationNodeImpl.add(compFinal(new CodeVariableElement(context.getType(int.class), "buildOrder")));
        typOperationNodeImpl.add(GeneratorUtils.createConstructorUsingFields(MOD_PRIVATE, typOperationNodeImpl, m.fdConstructor));

        for (String methodName : new String[]{"execute", "getSourceSectionAtBci", "materializeInstrumentTree"}) {
            CodeExecutableElement met = GeneratorUtils.overrideImplement(types.OperationRootNode, methodName);
            met.createBuilder().startThrow().startNew(context.getType(UnsupportedOperationException.class)).end(2);
            typOperationNodeImpl.add(met);
        }

        return typOperationNodeImpl;
    }

    private CodeVariableElement createSerializationContext() {
        DeclaredType typeContext = context.getDeclaredType("com.oracle.truffle.api.operation.serialization.OperationSerializer.SerializerContext");
        CodeVariableElement fld = new CodeVariableElement(MOD_PRIVATE, typeContext, "SER_CONTEXT");
        CodeTreeBuilder b = fld.createInitBuilder();

        b.startNew(typeContext).end().string(" ").startBlock();

        b.string("@Override").newLine();
        b.string("public void serializeOperationNode(" + DATA_OUTPUT_CLASS.getSimpleName() + " buffer, OperationRootNode node) throws IOException ").startBlock();

        b.statement("buffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) ((OperationSerNodeImpl) node).buildOrder)");

        b.end();

        b.end();

        return fld;
    }

    CodeTypeElement createOperationNodeImpl() {
        GeneratedTypeMirror typOperationNodes = new GeneratedTypeMirror("", OPERATION_NODES_IMPL_NAME);

        String simpleName = m.getTemplateType().getSimpleName().toString() + "Gen";
        CodeTypeElement typOperationNodeImpl = GeneratorUtils.createClass(m, null, MOD_PUBLIC_FINAL, simpleName, m.getTemplateType().asType());
        GeneratorUtils.addSuppressWarnings(context, typOperationNodeImpl, "unused", "cast", "unchecked", "hiding", "rawtypes", "static-method");

        m.getOperationsContext().outerType = typOperationNodeImpl;

        typOperationNodeImpl.getImplements().add(types.BytecodeOSRNode);

        CodeTypeElement typExceptionHandler = typOperationNodeImpl.add(createExceptionHandler());

        CodeTypeElement typBytecodeBase = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_ABSTRACT, BYTECODE_BASE_NAME, null);
        typOperationNodeImpl.add(createBytecodeBaseClass(typBytecodeBase, typOperationNodeImpl, typOperationNodes, typExceptionHandler));

        CodeExecutableElement genCtor = typOperationNodeImpl.add(GeneratorUtils.createSuperConstructor(m.getTemplateType(), m.fdConstructor));
        genCtor.setSimpleName(CodeNames.of(simpleName));
        genCtor.getModifiers().clear();
        genCtor.getModifiers().add(Modifier.PRIVATE);

        if (m.fdBuilderConstructor == null) {
            CodeExecutableElement genBuilderCtor = typOperationNodeImpl.add(new CodeExecutableElement(MOD_PRIVATE, null, simpleName));
            genBuilderCtor.addParameter(genCtor.getParameters().get(0));
            genBuilderCtor.addParameter(new CodeVariableElement(types.FrameDescriptor_Builder, "builder"));
            genBuilderCtor.renameArguments("language", "builder");

            CodeTreeBuilder b = genBuilderCtor.createBuilder();
            b.startStatement().startCall("this").string("language").string("builder.build()").end(2);
        } else {
            CodeExecutableElement genBuilderCtor = typOperationNodeImpl.add(GeneratorUtils.createSuperConstructor(m.getTemplateType(), m.fdBuilderConstructor));
            genBuilderCtor.setSimpleName(CodeNames.of(simpleName));
            genBuilderCtor.getModifiers().clear();
            genBuilderCtor.getModifiers().add(Modifier.PRIVATE);
        }

        boolean doHookTti = ElementFilter.fieldsIn(m.getTemplateType().getEnclosedElements()).stream().anyMatch(x -> x.getSimpleName().toString().equals("__magic_LogInvalidations"));

        if (doHookTti) {
            GeneratorUtils.setHookTransferToInterpreter(true);
            typOperationNodeImpl.add(createHookTransferToInterpreterAndInvalidate());
        }

        typOperationNodeImpl.add(new CodeVariableElement(MOD_PRIVATE_STATIC_FINAL, context.getDeclaredType("com.oracle.truffle.api.impl.UnsafeFrameAccess"), "UFA = UnsafeFrameAccess.lookup()"));

        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, typOperationNodes, "nodes")));
        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(short[].class), "_bc")));
        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(Object[].class), "_consts")));
        typOperationNodeImpl.add(children(new CodeVariableElement(MOD_PRIVATE, arrayOf(types.Node), "_children")));
        if (m.getOperationsContext().hasBoxingElimination()) {
            typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(byte[].class), "_localTags")));
        }
        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, arrayOf(typExceptionHandler.asType()), "_handlers")));
        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(int[].class), "_conditionProfiles")));
        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "_maxLocals")));
        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "_maxStack")));
        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(int[].class), "sourceInfo")));

        if (m.enableYield) {
            typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, arrayOf(new GeneratedTypeMirror("", "ContinuationRoot")), "yieldEntries")));
        }

        if (m.isTracing()) {
            typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(boolean[].class), "isBbStart")));
        }

        if (OperationGeneratorFlags.ENABLE_INSTRUMENTATION) {
            CodeVariableElement instrumentRoot = typOperationNodeImpl.add(new CodeVariableElement(MOD_PRIVATE, types.InstrumentRootNode, "instrumentRoot"));
            instrumentRoot.addAnnotationMirror(new CodeAnnotationMirror(types.Node_Child));
            instrumentRoot.createInitBuilder().startStaticCall(types.InstrumentRootNode, "create").end();
            typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, arrayOf(types.InstrumentTreeNode), "instruments")));
            typOperationNodeImpl.add(createNodeImplOnInstrumentReplace());
        }

        CodeVariableElement fldSwitchImpl = new CodeVariableElement(MOD_PRIVATE, typBytecodeBase.asType(), "switchImpl");
        GeneratorUtils.addCompilationFinalAnnotation(fldSwitchImpl);
        fldSwitchImpl.createInitBuilder().string("INITIAL_EXECUTE");
        typOperationNodeImpl.add(fldSwitchImpl);

        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "uncachedExecuteCount = 16")));

        typOperationNodeImpl.add(compFinal(new CodeVariableElement(MOD_PRIVATE, context.getType(Object.class), "_osrMetadata")));

        if (!m.getMetadatas().isEmpty()) {
            CodeExecutableElement staticInit = new CodeExecutableElement(MOD_STATIC, null, "<cinit>");
            typOperationNodeImpl.add(staticInit);

            CodeTreeBuilder initBuilder = staticInit.createBuilder();

            for (OperationMetadataData metadata : m.getMetadatas()) {
                String fieldName = "_metadata_" + metadata.getName();
                CodeVariableElement fldMetadata = new CodeVariableElement(MOD_PRIVATE, metadata.getType(), fieldName);

                typOperationNodeImpl.add(fldMetadata);

                initBuilder.startStatement().startCall("OperationRootNode.setMetadataAccessor");
                initBuilder.staticReference((VariableElement) metadata.getMessageElement());
                initBuilder.startGroup();
                // (
                initBuilder.string("n -> ");
                initBuilder.startParantheses().cast(typOperationNodeImpl.asType()).string("n").end().string("." + fieldName);
                // )
                initBuilder.end();
                initBuilder.end(2);
            }
        }

        String initialExecute = "UNCOMMON_EXECUTE";

        typOperationNodeImpl.add(new CodeVariableElement(MOD_PRIVATE_STATIC_FINAL, typBytecodeBase.asType(), "UNCOMMON_EXECUTE = new BytecodeNode()"));

        if (OperationGeneratorFlags.CREATE_COMMON_EXECUTE && m.isOptimized()) {
            typOperationNodeImpl.add(new CodeVariableElement(MOD_PRIVATE_STATIC_FINAL, typBytecodeBase.asType(), "COMMON_EXECUTE = new CommonBytecodeNode()"));
            initialExecute = "COMMON_EXECUTE";
        } else {
            typOperationNodeImpl.add(new CodeVariableElement(MOD_PRIVATE_STATIC_FINAL, typBytecodeBase.asType(), "COMMON_EXECUTE = UNCOMMON_EXECUTE"));
        }

        if (m.isGenerateUncached()) {
            typOperationNodeImpl.add(new CodeVariableElement(MOD_PRIVATE_STATIC_FINAL, typBytecodeBase.asType(), "UNCACHED_EXECUTE = new UncachedBytecodeNode()"));
            initialExecute = "UNCACHED_EXECUTE";
        }

        if (OperationGeneratorFlags.ENABLE_INSTRUMENTATION) {
            typOperationNodeImpl.add(new CodeVariableElement(MOD_PRIVATE_STATIC_FINAL, typBytecodeBase.asType(), "INSTRUMENTABLE_EXECUTE = new InstrumentableBytecodeNode()"));
        }

        typOperationNodeImpl.add(new CodeVariableElement(MOD_PRIVATE_STATIC_FINAL, typBytecodeBase.asType(), "INITIAL_EXECUTE = " + initialExecute));

        typOperationNodeImpl.add(createNodeImplExecuteAt());
        typOperationNodeImpl.add(createNodeImplGetSourceSection());
        typOperationNodeImpl.add(createNodeImplGetSourceSectionAtBci());
        typOperationNodeImpl.add(createSneakyThrow());

        CodeExecutableElement mExecute = createNodeExecute();
        typOperationNodeImpl.add(mExecute);

        CodeExecutableElement mDump = GeneratorUtils.overrideImplement(types.OperationIntrospection_Provider, "getIntrospectionData");
        typOperationNodeImpl.add(mDump);
        mDump.createBuilder().startReturn().startCall("switchImpl.getIntrospectionData").string("this, _bc, _handlers, _consts, nodes, sourceInfo").end(2);

        CodeExecutableElement mGetLockAccessor = new CodeExecutableElement(MOD_PRIVATE, context.getType(Lock.class), "getLockAccessor");
        typOperationNodeImpl.add(mGetLockAccessor);
        mGetLockAccessor.createBuilder().startReturn().startCall("getLock").end(2);

        CodeExecutableElement mInsertAccessor = new CodeExecutableElement(MOD_PRIVATE, null, "<T extends Node> T insertAccessor(T node) { // ");
        typOperationNodeImpl.add(mInsertAccessor);
        mInsertAccessor.createBuilder().startReturn().startCall("insert").string("node").end(2);

        CodeExecutableElement mExecuteOSR = GeneratorUtils.overrideImplement(types.BytecodeOSRNode, "executeOSR");
        typOperationNodeImpl.add(mExecuteOSR);
        if (m.enableYield) {
            mExecuteOSR.createBuilder().startReturn().startCall("executeAt").string("osrFrame, (VirtualFrame) interpreterState, target").end(2);
        } else {
            mExecuteOSR.createBuilder().startReturn().startCall("executeAt").string("osrFrame, target").end(2);
        }

        CodeExecutableElement mGetOSRMetadata = GeneratorUtils.overrideImplement(types.BytecodeOSRNode, "getOSRMetadata");
        typOperationNodeImpl.add(mGetOSRMetadata);
        mGetOSRMetadata.createBuilder().startReturn().string("_osrMetadata").end();

        CodeExecutableElement mSetOSRMetadata = GeneratorUtils.overrideImplement(types.BytecodeOSRNode, "setOSRMetadata");
        typOperationNodeImpl.add(mSetOSRMetadata);
        mSetOSRMetadata.createBuilder().startAssign("_osrMetadata").string("osrMetadata").end();

        typOperationNodeImpl.add(createNodeImplDeepCopy(typOperationNodeImpl));
        typOperationNodeImpl.add(createNodeImplCopy(typOperationNodeImpl));
        CodeTypeElement typOpNodesImpl = createOperationNodes();
        typOperationNodeImpl.add(typOpNodesImpl);

        CodeTypeElement builderBytecodeNodeType;
        CodeTypeElement builderCommonBytecodeNodeType;
        CodeTypeElement builderUncachedBytecodeNodeType;
        CodeTypeElement builderInstrBytecodeNodeType;

        bytecodeGenerator = new OperationsBytecodeCodeGenerator(typOperationNodeImpl, typBytecodeBase, typOperationNodeImpl, typExceptionHandler, m, false, false, false);
        builderBytecodeNodeType = bytecodeGenerator.createBuilderBytecodeNode();
        typOperationNodeImpl.add(builderBytecodeNodeType);

        if (m.isGenerateUncached()) {
            builderUncachedBytecodeNodeType = new OperationsBytecodeCodeGenerator(typOperationNodeImpl, typBytecodeBase, typOperationNodeImpl, typExceptionHandler, m, false,
                            true, false).createBuilderBytecodeNode();
            typOperationNodeImpl.add(builderUncachedBytecodeNodeType);
        } else {
            builderUncachedBytecodeNodeType = null;
        }

        if (OperationGeneratorFlags.CREATE_COMMON_EXECUTE && m.isOptimized()) {
            builderCommonBytecodeNodeType = new OperationsBytecodeCodeGenerator(typOperationNodeImpl, typBytecodeBase, typOperationNodeImpl, typExceptionHandler, m, false, false,
                            true).createBuilderBytecodeNode();
            typOperationNodeImpl.add(builderCommonBytecodeNodeType);
        } else {
            builderCommonBytecodeNodeType = null;
        }

        if (OperationGeneratorFlags.ENABLE_INSTRUMENTATION) {
            OperationsBytecodeCodeGenerator bcg = new OperationsBytecodeCodeGenerator(typOperationNodeImpl, typBytecodeBase, typOperationNodeImpl, typExceptionHandler, m, true, false, false);
            builderInstrBytecodeNodeType = bcg.createBuilderBytecodeNode();
            typOperationNodeImpl.add(builderInstrBytecodeNodeType);
        } else {
            builderInstrBytecodeNodeType = null;
        }

        typOperationNodeImpl.add(createMaterializeInstrumentTree());

        CodeTypeElement typBuilderImpl = createBuilderImpl(typOperationNodeImpl, typOpNodesImpl, typExceptionHandler);
        typOperationNodeImpl.add(typBuilderImpl);
        typOperationNodeImpl.add(createChangeInterpreter(typBytecodeBase));

        // instruction IDsx
        for (Instruction instr : m.getOperationsContext().instructions) {
            typOperationNodeImpl.addAll(instr.createInstructionFields());
        }

        typOperationNodeImpl.add(createSetResultUnboxed());
        typOperationNodeImpl.add(createLoadVariadicArguments());
        typOperationNodeImpl.add(createSetResultBoxedImpl());
        typOperationNodeImpl.add(createCounter());
        if (m.enableYield) {
            typOperationNodeImpl.add(createContinuationLocationImpl(typOperationNodeImpl));
            typOperationNodeImpl.add(createContinuationRoot(typOperationNodeImpl));
        }
        typOperationNodeImpl.add(createUnsafeFromBytecode());
        typOperationNodeImpl.add(createUnsafeWriteBytecode());
        typOperationNodeImpl.add(createConditionProfile());

        if (OperationGeneratorFlags.ENABLE_INSTRUMENTATION) {
            typOperationNodeImpl.add(createGetTreeProbeNode(typOperationNodeImpl.asType()));
        }

        typOperationNodeImpl.add(createCreateMethod(typBuilderImpl, typBuilderImpl));
        typOperationNodeImpl.add(createDeserializeMethod());
        typOperationNodeImpl.add(createSerializeMethod(typBuilderImpl, typBuilderImpl));

        if (doHookTti) {
            GeneratorUtils.setHookTransferToInterpreter(false);
        }

        return typOperationNodeImpl;
    }

    private CodeExecutableElement createNodeImplOnInstrumentReplace() {
        CodeExecutableElement met = GeneratorUtils.overrideImplement(types.OperationRootNode, "onInstrumentReplace");

        CodeTreeBuilder b = met.createBuilder();

        b.startFor().string("int i = 0; i < instruments.length; i++").end().startBlock();
        b.startIf().string("instruments[i] == old").end().startBlock();
        b.statement("instruments[i] = replacement");
        b.returnStatement();
        b.end();
        b.end();

        b.startAssert().string("false").end();

        return met;
    }

    private CodeExecutableElement createGetTreeProbeNode(TypeMirror thisType) {
        CodeExecutableElement met = new CodeExecutableElement(MOD_PRIVATE_STATIC, types.ProbeNode, "getProbeNodeImpl");
        met.addParameter(new CodeVariableElement(thisType, "$this"));
        met.addParameter(new CodeVariableElement(context.getType(int.class), "index"));
        CodeTreeBuilder b = met.createBuilder();

        b.statement("InstrumentTreeNode node = $this.instruments[index]");
        b.startIf().string("!(node").instanceOf(types.InstrumentableNode_WrapperNode).string(")").end().startBlock();
        b.returnNull();
        b.end();

        b.startReturn().string("(").cast(types.InstrumentableNode_WrapperNode).string(" node).getProbeNode()").end();

        return met;
    }

    private CodeExecutableElement createMaterializeInstrumentTree() {
        CodeExecutableElement met = GeneratorUtils.overrideImplement(types.OperationRootNode, "materializeInstrumentTree");
        CodeTreeBuilder b = met.createBuilder();

        if (OperationGeneratorFlags.ENABLE_INSTRUMENTATION) {
            b.statement("nodes.ensureInstrumentationAccessor()");
        }

        b.startReturn().string("instrumentRoot").end();
        return met;
    }

    private CodeExecutableElement createHookTransferToInterpreterAndInvalidate() {
        CodeExecutableElement el = new CodeExecutableElement(MOD_PRIVATE_STATIC, context.getType(void.class), "hook_transferToInterpreterAndInvalidate");
        el.addParameter(new CodeVariableElement(m.getOperationsContext().outerType.asType(), "$this"));

        CodeTreeBuilder b = el.createBuilder();

        TypeMirror swType = context.getType(StackWalker.class);

        b.startStatement().startStaticCall(types.CompilerDirectives, "transferToInterpreterAndInvalidate").end(2);

        for (VariableElement field : ElementFilter.fieldsIn(m.getTemplateType().getEnclosedElements())) {
            if (field.getSimpleName().toString().equals("__magic_CountInvalidations")) {
                b.startStatement().field("$this", field).string(" += 1").end();
            }
        }

        b.startIf().string("__magic_LogInvalidations").end().startBlock();
        b.startStatement().startCall("System.err", "printf");
        b.doubleQuote("[ INV ] %s%s%n");
        b.doubleQuote("");
        b.startStaticCall(swType, "getInstance().walk");
        b.string("s -> s.skip(1).findFirst().get().toStackTraceElement().toString()");
        b.end();
        b.end(2);
        b.end();

        return el;
    }

    private CodeExecutableElement createLoadVariadicArguments() {
        CodeExecutableElement el = new CodeExecutableElement(MOD_PRIVATE_STATIC, arrayOf(context.getType(Object.class)), "do_loadVariadicArguments");
        el.addParameter(new CodeVariableElement(types.VirtualFrame, "$frame"));
        el.addParameter(new CodeVariableElement(context.getType(int.class), "$sp"));
        el.addParameter(new CodeVariableElement(context.getType(int.class), "numVariadics"));
        el.addAnnotationMirror(new CodeAnnotationMirror(types.ExplodeLoop));

        CodeTreeBuilder b = el.createBuilder();
        b.tree(GeneratorUtils.createPartialEvaluationConstant("$sp"));
        b.tree(GeneratorUtils.createPartialEvaluationConstant("numVariadics"));
        b.declaration("Object[]", "result", "new Object[numVariadics]");

        b.startFor().string("int varIndex = 0; varIndex < numVariadics; varIndex++").end().startBlock();
        b.statement("result[varIndex] = UFA.unsafeUncheckedGetObject($frame, $sp - numVariadics + varIndex)");
        b.end();

        b.startReturn().string("result").end();

        return el;
    }

    private CodeTypeElement createContinuationRoot(CodeTypeElement typOperationNodeImpl) {
        CodeTypeElement cr = new CodeTypeElement(MOD_PRIVATE_STATIC_FINAL, ElementKind.CLASS, null, "ContinuationRoot");
        cr.setSuperClass(types.RootNode);

        cr.add(new CodeVariableElement(MOD_FINAL, typOperationNodeImpl.asType(), "root"));
        cr.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "target"));

        cr.add(GeneratorUtils.createConstructorUsingFields(Set.of(), cr,
                        // gets the (language, frameDescriptor) ctor
                        ElementFilter.constructorsIn(((TypeElement) types.RootNode.asElement()).getEnclosedElements()).stream().filter(x -> x.getParameters().size() == 2).findFirst().get()));

        CodeExecutableElement mExecute = cr.add(GeneratorUtils.overrideImplement(types.RootNode, "execute"));

        CodeTreeBuilder b = mExecute.getBuilder();

        b.statement("Object[] args = frame.getArguments()");
        b.startIf().string("args.length != 2").end().startBlock();
        b.tree(GeneratorUtils.createShouldNotReachHere("expected 2 arguments: (parentFrame, inputValue)"));
        b.end();

        b.declaration(types.MaterializedFrame, "parentFrame", "(MaterializedFrame) args[0]");
        b.declaration(context.getType(Object.class), "inputValue", "args[1]");

        b.startIf().string("parentFrame.getFrameDescriptor() != frame.getFrameDescriptor()").end().startBlock();
        b.tree(GeneratorUtils.createShouldNotReachHere("invalid continuation parent frame passed"));
        b.end();

        b.declaration("int", "sp", "((target >> 16) & 0xffff) + root._maxLocals");
        b.statement("parentFrame.copyTo(root._maxLocals, frame, root._maxLocals, sp - 1 - root._maxLocals)");
        b.statement("frame.setObject(sp - 1, inputValue)");

        b.statement("return root.executeAt(frame, parentFrame, (sp << 16) | (target & 0xffff))");

        return cr;
    }

    private CodeTypeElement createContinuationLocationImpl(CodeTypeElement typOperationNodeImpl) {
        DeclaredType superType = context.getDeclaredType("com.oracle.truffle.api.operation.ContinuationLocation");

        CodeTypeElement cli = new CodeTypeElement(MOD_PRIVATE_STATIC_FINAL, ElementKind.CLASS, null, "ContinuationLocationImpl");
        cli.setSuperClass(superType);

        cli.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "entry"));
        cli.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "target"));

        cli.add(GeneratorUtils.createConstructorUsingFields(Set.of(), cli));

        cli.add(compFinal(new CodeVariableElement(typOperationNodeImpl.asType(), "root")));

        CodeExecutableElement mGetRootNode = cli.add(GeneratorUtils.overrideImplement(superType, "getRootNode"));
        CodeTreeBuilder b = mGetRootNode.getBuilder();
        b.statement("ContinuationRoot node = root.yieldEntries[entry]");
        b.startIf().string("node == null").end().startBlock();
        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
        b.statement("node = new ContinuationRoot(root.getLanguage(), root.getFrameDescriptor(), root, target)");
        b.statement("root.yieldEntries[entry] = node");
        b.end();
        b.statement("return node");

        CodeExecutableElement mToString = cli.add(GeneratorUtils.override(context.getDeclaredType(Object.class), "toString"));
        b = mToString.getBuilder();
        b.statement("return String.format(\"ContinuationLocation [index=%d, sp=%s, bci=%04x]\", entry, target >> 16, target & 0xffff)");

        return cli;
    }

    private CodeExecutableElement createNodeExecute() {
        // todo: do not generate the prolog call if not implemented
        // todo: do not generate the epilog call and the try/catch if not implemented
        CodeExecutableElement mExecute = GeneratorUtils.overrideImplement(types.RootNode, "execute");
        CodeTreeBuilder b = mExecute.createBuilder();
        b.declaration("Object", "returnValue", "null");
        b.declaration("Throwable", "throwable", "null");
        b.statement("executeProlog(frame)");
        b.startTryBlock();

        b.startAssign("returnValue").startCall("executeAt");
        b.string("frame");
        if (m.enableYield) {
            b.string("frame");
        }
        b.string("_maxLocals << 16");
        b.end(2);

        b.startReturn().string("returnValue").end();
        b.end().startCatchBlock(context.getType(Throwable.class), "th");
        b.statement("throw sneakyThrow(throwable = th)");
        b.end().startFinallyBlock();
        b.startStatement().startCall("executeEpilog");
        b.string("frame");
        b.string("returnValue");
        b.string("throwable");
        b.end(2);
        b.end();
        return mExecute;
    }

    private CodeExecutableElement createUnsafeFromBytecode() {
        CodeExecutableElement met = new CodeExecutableElement(MOD_PRIVATE_STATIC, context.getType(short.class), "unsafeFromBytecode");
        met.addParameter(new CodeVariableElement(context.getType(short[].class), "bc"));
        met.addParameter(new CodeVariableElement(context.getType(int.class), "index"));

        CodeTreeBuilder b = met.createBuilder();
        b.startReturn();
        b.string("UFA.unsafeShortArrayRead(bc, index)");
        b.end();

        return met;
    }

    private CodeExecutableElement createUnsafeWriteBytecode() {
        CodeExecutableElement met = new CodeExecutableElement(MOD_PRIVATE_STATIC, context.getType(void.class), "unsafeWriteBytecode");
        met.addParameter(new CodeVariableElement(context.getType(short[].class), "bc"));
        met.addParameter(new CodeVariableElement(context.getType(int.class), "index"));
        met.addParameter(new CodeVariableElement(context.getType(short.class), "value"));

        CodeTreeBuilder b = met.createBuilder();
        b.statement("UFA.unsafeShortArrayWrite(bc, index, value)");
        b.end();

        return met;
    }

    private CodeExecutableElement createChangeInterpreter(CodeTypeElement loopBase) {
        CodeExecutableElement met = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "changeInterpreters");
        met.addParameter(new CodeVariableElement(loopBase.asType(), "impl"));

        CodeTreeBuilder b = met.createBuilder();

        // todo: everything here

        b.statement("this.switchImpl = impl");

        return met;
    }

    private CodeTree createNodeCopy(CodeTypeElement typOperationNodeImpl) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();
        b.startNew(typOperationNodeImpl.asType());
        b.startCall("getLanguage").end();
        b.startCall("getFrameDescriptor().copy").end();
        b.end();
        return b.build();
    }

    private CodeExecutableElement createNodeImplDeepCopy(CodeTypeElement typOperationNodeImpl) {
        CodeExecutableElement met = GeneratorUtils.overrideImplement(types.Node, "deepCopy");
        CodeTreeBuilder b = met.createBuilder();

        b.declaration(typOperationNodeImpl.asType(), "result", createNodeCopy(typOperationNodeImpl));

        b.statement("result.nodes = nodes");
        b.statement("result._bc = Arrays.copyOf(_bc, _bc.length)");
        b.statement("result._consts = Arrays.copyOf(_consts, _consts.length)");
        b.statement("result._children = Arrays.copyOf(_children, _children.length)");
        if (m.getOperationsContext().hasBoxingElimination()) {
            b.statement("result._localTags = Arrays.copyOf(_localTags, _localTags.length)");
        }
        if (m.isTracing()) {
            b.statement("result.isBbStart = isBbStart");
        }
        b.statement("result._handlers = _handlers");
        b.statement("result._conditionProfiles = Arrays.copyOf(_conditionProfiles, _conditionProfiles.length)");
        b.statement("result._maxLocals = _maxLocals");
        b.statement("result._maxStack = _maxStack");
        b.statement("result.sourceInfo = sourceInfo");

        for (OperationMetadataData metadata : m.getMetadatas()) {
            b.statement("result._metadata_" + metadata.getName() + " = _metadata_" + metadata.getName());
        }

        b.statement("return result");

        return met;
    }

    private CodeExecutableElement createSneakyThrow() {
        CodeExecutableElement met = new CodeExecutableElement(MOD_PRIVATE_STATIC, null, "<E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E { //");
        met.createBuilder().statement("throw (E) e");
        GeneratorUtils.addSuppressWarnings(context, met, "unchecked");

        return met;
    }

    private CodeExecutableElement createNodeImplCopy(CodeTypeElement typOperationNodeImpl) {
        CodeExecutableElement met = GeneratorUtils.overrideImplement(types.Node, "copy");
        CodeTreeBuilder b = met.createBuilder();

        b.declaration(typOperationNodeImpl.asType(), "result", createNodeCopy(typOperationNodeImpl));

        b.statement("result.nodes = nodes");
        b.statement("result._bc = _bc");
        b.statement("result._consts = _consts");
        b.statement("result._children = _children");
        if (m.getOperationsContext().hasBoxingElimination()) {
            b.statement("result._localTags = _localTags");
        }
        b.statement("result._handlers = _handlers");
        b.statement("result._conditionProfiles = _conditionProfiles");
        b.statement("result._maxLocals = _maxLocals");
        b.statement("result._maxStack = _maxStack");
        b.statement("result.sourceInfo = sourceInfo");

        for (OperationMetadataData metadata : m.getMetadatas()) {
            b.statement("result._metadata_" + metadata.getName() + " = _metadata_" + metadata.getName());
        }

        b.statement("return result");

        return met;
    }

    private CodeExecutableElement createNodeImplExecuteAt() {
        CodeExecutableElement mExecuteAt = new CodeExecutableElement(MOD_PRIVATE, context.getType(Object.class), "executeAt");
        if (m.enableYield) {
            mExecuteAt.addParameter(new CodeVariableElement(types.VirtualFrame, "stackFrame"));
            mExecuteAt.addParameter(new CodeVariableElement(types.VirtualFrame, "localFrame"));
        } else {
            mExecuteAt.addParameter(new CodeVariableElement(types.VirtualFrame, "frame"));
        }
        mExecuteAt.addParameter(new CodeVariableElement(context.getType(int.class), "storedLocation"));

        CodeTreeBuilder b = mExecuteAt.createBuilder();
        b.declaration("int", "result", "storedLocation");
        b.startWhile().string("true").end().startBlock();

        b.startAssign("result").startCall("switchImpl", "continueAt");
        b.string("this");
        if (m.enableYield) {
            b.string("stackFrame");
            b.string("localFrame");
        } else {
            b.string("frame");
        }
        b.string("_bc");
        b.string("result & 0xffff");
        b.string("(result >> 16) & 0xffff");
        b.string("_consts");
        b.string("_children");
        if (m.getOperationsContext().hasBoxingElimination()) {
            b.string("_localTags");
        }
        b.string("_handlers");
        b.string("_conditionProfiles");
        b.string("_maxLocals");
        b.end(2);

        b.startIf().string("(result & 0xffff) == 0xffff").end().startBlock();
        b.statement("break");
        b.end().startElseBlock();
        b.declaration(m.getOperationsContext().outerType.asType(), "$this", "this");
        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
        b.end();

        b.end();

        b.startReturn();
        b.string(m.enableYield ? "stackFrame" : "frame");
        b.string(".getObject((result >> 16) & 0xffff)").end();

        return mExecuteAt;
    }

    private static final int SOURCE_INFO_BCI_INDEX = 0;
    private static final int SOURCE_INFO_START = 1;
    private static final int SOURCE_INFO_LENGTH = 2;
    private static final int SOURCE_INFO_STRIDE = 3;

    private CodeExecutableElement createNodeImplGetSourceSection() {
        CodeExecutableElement met = GeneratorUtils.overrideImplement(types.Node, "getSourceSection");

        CodeTreeBuilder b = met.createBuilder();

        b.declaration("int[]", "sourceInfo", "this.sourceInfo");

        b.startIf().string("sourceInfo == null").end().startBlock().returnNull().end();

        b.declaration("int", "i", (CodeTree) null);

        b.startFor().string("i = 0; i < sourceInfo.length; i += " + SOURCE_INFO_STRIDE).end().startBlock();
        b.startIf().string("sourceInfo[i + " + SOURCE_INFO_START + "] >= 0").end().startBlock();
        b.statement("int sourceIndex = sourceInfo[i + " + SOURCE_INFO_BCI_INDEX + "] >> 16");
        b.statement("int sourceStart = sourceInfo[i + " + SOURCE_INFO_START + "]");
        b.statement("int sourceLength = sourceInfo[i + " + SOURCE_INFO_LENGTH + "]");
        b.startReturn().string("nodes.getSources()[sourceIndex].createSection(sourceStart, sourceLength)").end();
        b.end();
        b.end();

        b.returnNull();

        return met;
    }

    private CodeExecutableElement createNodeImplGetSourceSectionAtBci() {
        CodeExecutableElement met = GeneratorUtils.overrideImplement(types.OperationRootNode, "getSourceSectionAtBci");

        CodeTreeBuilder b = met.createBuilder();

        b.declaration("int[]", "sourceInfo", "this.sourceInfo");

        b.startIf().string("sourceInfo == null").end().startBlock().returnNull().end();

        b.declaration("int", "i", (CodeTree) null);

        b.startFor().string("i = 0; i < sourceInfo.length; i += " + SOURCE_INFO_STRIDE).end().startBlock();
        b.startIf().string("(sourceInfo[i + " + SOURCE_INFO_BCI_INDEX + "] & 0xffff) > bci").end().startBlock();
        b.statement("break");
        b.end();
        b.end();

        b.startIf().string("i == 0").end().startBlock();
        b.returnNull();
        b.end().startElseBlock();

        b.statement("i -= " + SOURCE_INFO_STRIDE);
        b.statement("int sourceIndex = sourceInfo[i + " + SOURCE_INFO_BCI_INDEX + "] >> 16");
        b.startIf().string("sourceIndex < 0").end().startBlock().returnNull().end();
        b.statement("int sourceStart = sourceInfo[i + " + SOURCE_INFO_START + "]");
        b.startIf().string("sourceStart < 0").end().startBlock().returnNull().end();
        b.statement("int sourceLength = sourceInfo[i + " + SOURCE_INFO_LENGTH + "]");
        b.startReturn().string("nodes.getSources()[sourceIndex].createSection(sourceStart, sourceLength)").end();

        b.end();

        return met;
    }

    private CodeTypeElement createBuilderImpl(CodeTypeElement typOperationNodeImpl, CodeTypeElement opNodesImpl, CodeTypeElement typExceptionHandler) {
        CodeTypeElement typBuilderImpl = GeneratorUtils.createClass(m, null, Set.of(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL), OPERATION_BUILDER_IMPL_NAME, types.OperationBuilder);
        typBuilderImpl.setEnclosingElement(typOperationNodeImpl);

        if (m.isTracing()) {
            String decisionsFilePath = m.getDecisionsFilePath();
            CodeExecutableElement mStaticInit = new CodeExecutableElement(MOD_STATIC, null, "<cinit>");
            typBuilderImpl.add(mStaticInit);

            CodeTreeBuilder b = mStaticInit.appendBuilder();

            b.startStatement().startStaticCall(types.ExecutionTracer, "initialize");

            b.typeLiteral(m.getTemplateType().asType());

            // destination path
            b.doubleQuote(decisionsFilePath);

            // instruction names
            b.startNewArray(new ArrayCodeTypeMirror(context.getType(String.class)), null);
            b.string("null");
            for (Instruction instr : m.getInstructions()) {
                b.doubleQuote(instr.name);
            }
            b.end();

            // specialization names

            b.startNewArray(new ArrayCodeTypeMirror(new ArrayCodeTypeMirror(context.getType(String.class))), null);
            b.string("null");
            for (Instruction instr : m.getInstructions()) {
                if (!(instr instanceof CustomInstruction)) {
                    b.string("null");
                    continue;
                }

                b.startNewArray(new ArrayCodeTypeMirror(context.getType(String.class)), null);
                CustomInstruction cinstr = (CustomInstruction) instr;
                for (String name : cinstr.getSpecializationNames()) {
                    b.doubleQuote(name);
                }
                b.end();
            }
            b.end();

            b.end(2);
        }

        CodeTypeElement opDataImpl = createOperationDataImpl();
        typBuilderImpl.add(opDataImpl);

        CodeTypeElement typFinallyTryContext = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "BuilderFinallyTryContext", null);

        CodeTypeElement typLabelData = createOperationLabelImpl(opDataImpl, typFinallyTryContext);
        typBuilderImpl.add(typLabelData);

        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            typBuilderImpl.add(createOperationSerLabelImpl());
        }

        CodeTypeElement typLocalData = createOperationLocalImpl(opDataImpl);
        typBuilderImpl.add(typLocalData);

        CodeTypeElement typLabelFill = typBuilderImpl.add(createLabelFill(typLabelData));

        CodeTypeElement typSourceBuilder = createSourceBuilder(typBuilderImpl);
        typBuilderImpl.add(typSourceBuilder);

        createFinallyTryContext(typFinallyTryContext, typExceptionHandler, typLabelFill, typLabelData);
        typBuilderImpl.add(typFinallyTryContext);

        m.getOperationsContext().labelType = typLabelData.asType();
        m.getOperationsContext().exceptionType = typExceptionHandler.asType();
        m.getOperationsContext().outerType = typOperationNodeImpl;

        typBuilderImpl.add(createBuilderImplCtor(opNodesImpl));

        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE_FINAL, opNodesImpl.asType(), "nodes"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE_FINAL, context.getType(boolean.class), "isReparse"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE_FINAL, context.getType(boolean.class), "withSource"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE_FINAL, context.getType(boolean.class), "withInstrumentation"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, typSourceBuilder.asType(), "sourceBuilder"));

        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(short[].class), "bc = new short[65535]"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "bci"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "curStack"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "maxStack"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "numLocals"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int[].class), "instructionHistory = new int[8]"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "instructionHistoryIndex = 0"));
        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "numLabels"));
        }
        if (m.enableYield) {
            typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "yieldCount"));
            typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, arrayOf(new GeneratedTypeMirror("", "ContinuationLocationImpl")), "yieldLocations = null"));
        }
        if (m.isTracing()) {
            typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(boolean[].class), "isBbStart = new boolean[65535]"));
        }
        if (OperationGeneratorFlags.ENABLE_INSTRUMENTATION) {
            typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, generic(ArrayList.class, types.InstrumentTreeNode),
                            "instrumentList = new ArrayList<>()"));
            typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, generic(ArrayList.class, generic(ArrayList.class, types.InstrumentTreeNode)),
                            "instrumentStack = new ArrayList<>()"));
        }

        CodeVariableElement fldConstPool = typBuilderImpl.add(
                        new CodeVariableElement(MOD_PRIVATE, new DeclaredCodeTypeMirror(context.getTypeElement(ArrayList.class), List.of(context.getType(Object.class))), "constPool"));
        fldConstPool.createInitBuilder().string("new ArrayList<>()");
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, opDataImpl.asType(), "operationData"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, new DeclaredCodeTypeMirror(context.getTypeElement(ArrayList.class), List.of(typLabelData.asType())), "labels = new ArrayList<>()"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, new DeclaredCodeTypeMirror(context.getTypeElement(ArrayList.class), List.of(typLabelFill.asType())), "labelFills = new ArrayList<>()"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "numChildNodes"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "numConditionProfiles"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, new DeclaredCodeTypeMirror(context.getTypeElement(ArrayList.class), List.of(typExceptionHandler.asType())),
                        "exceptionHandlers = new ArrayList<>()"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, typFinallyTryContext.asType(), "currentFinallyTry"));

        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "buildIndex"));

        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(boolean.class), "isSerializing"));
            typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(DATA_OUTPUT_CLASS), "serBuffer"));
            typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getDeclaredType("com.oracle.truffle.api.operation.serialization.OperationSerializer"), "serCallback"));
        }

        typBuilderImpl.add(createBuilderImplFinish());
        typBuilderImpl.add(createBuilderImplReset());

        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int[].class), "stackSourceBci = new int[1024]"));
        typBuilderImpl.add(createBuilderImplDoBeforeEmitInstruction());

        CodeTypeElement typBuilderState = typBuilderImpl.add(createBuilderState(typBuilderImpl, "bc", "bci", "curStack", "maxStack", "numLocals", "numLabels", "yieldCount", "yieldLocations",
                        "constPool", "operationData", "labels", "labelFills", "numChildNodes", "numConditionProfiles", "exceptionHandlers", "currentFinallyTry", "stackSourceBci", "sourceBuilder"));
        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE, typBuilderState.asType(), "parentData"));

        typBuilderImpl.add(createDoLeaveFinallyTry(opDataImpl));
        typBuilderImpl.add(createBuilderImplDoEmitLabel(typLabelData));
        typBuilderImpl.addAll(createBuilderImplCalculateLeaves(opDataImpl, typLabelData));

        typBuilderImpl.add(createBuilderImplCreateLocal(typBuilderImpl, typLocalData));
        typBuilderImpl.add(createBuilderImplCreateParentLocal(typBuilderImpl, typLocalData));
        typBuilderImpl.add(createBuilderImplCreateLabel(typBuilderImpl, typLabelData));
        typBuilderImpl.addAll(createBuilderImplGetLocalIndex(typLocalData));
        typBuilderImpl.add(createBuilderImplVerifyNesting(opDataImpl));
        typBuilderImpl.add(createBuilderImplPublish(typOperationNodeImpl));
        typBuilderImpl.add(createBuilderImplLabelPass(typFinallyTryContext));

        typBuilderImpl.add(createBuilderImplOperationNames());

        // operation IDs
        for (Operation op : m.getOperationsContext().operations) {
            CodeVariableElement fldId = new CodeVariableElement(MOD_PRIVATE_STATIC_FINAL, context.getType(int.class), "OP_" + OperationGeneratorUtils.toScreamCase(op.name));
            CodeTreeBuilder b = fldId.createInitBuilder();
            b.string("" + op.id);
            op.setIdConstantField(fldId);
            typBuilderImpl.add(fldId);
        }

        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            typBuilderImpl.add(createOperationSerNodeImpl());
            typBuilderImpl.add(createSerializationContext());
        }

        typBuilderImpl.add(new CodeVariableElement(MOD_PRIVATE_FINAL, new DeclaredCodeTypeMirror(context.getTypeElement(ArrayList.class), List.of(typOperationNodeImpl.asType())), "builtNodes"));

        CodeVariableElement fldOperationData = new CodeVariableElement(MOD_PRIVATE, opDataImpl.asType(), "operationData");

        CodeVariableElement fldBc = new CodeVariableElement(MOD_PRIVATE, arrayOf(context.getType(byte.class)), "bc");

        CodeVariableElement fldIndent = null;
        if (OperationGeneratorFlags.FLAG_NODE_AST_PRINTING) {
            fldIndent = new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "indent");
            typBuilderImpl.add(fldIndent);
        }

        CodeVariableElement fldBci = new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "bci");

        CodeVariableElement fldLastPush = new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "lastChildPush");
        typBuilderImpl.add(fldLastPush);

        BuilderVariables vars = new BuilderVariables();
        vars.bc = fldBc;
        vars.bci = fldBci;
        vars.operationData = fldOperationData;
        vars.lastChildPushCount = fldLastPush;
        vars.consts = fldConstPool;

        typBuilderImpl.add(createDoLeave(vars, opDataImpl));
        typBuilderImpl.add(createBeforeChild(vars));
        typBuilderImpl.add(createAfterChild(vars));

        for (Operation op : m.getOperations()) {
            List<TypeMirror> args = op.getBuilderArgumentTypes();
            CodeVariableElement[] params = new CodeVariableElement[args.size()];

            for (int i = 0; i < params.length; i++) {
                params[i] = new CodeVariableElement(args.get(i), "arg" + i);
            }

            if (op.children != 0) {
                typBuilderImpl.add(createBeginOperation(typBuilderImpl, vars, op));
                typBuilderImpl.add(createEndOperation(typBuilderImpl, vars, op));
            } else {
                typBuilderImpl.add(createEmitOperation(typBuilderImpl, vars, op));
            }
        }

        for (OperationMetadataData metadata : m.getMetadatas()) {
            CodeVariableElement fldMetadata = new CodeVariableElement(MOD_PRIVATE, metadata.getType(), "metadata_" + metadata.getName());
            typBuilderImpl.add(fldMetadata);
            typBuilderImpl.add(createSetMetadata(metadata, false));
        }

        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            typBuilderImpl.add(createBuilderImplDeserializeParser(typBuilderImpl));
        }

        return typBuilderImpl;
    }

    private CodeVariableElement createBuilderImplOperationNames() {
        CodeVariableElement el = new CodeVariableElement(MOD_PRIVATE_STATIC_FINAL, context.getType(String[].class), "OPERATION_NAMES");
        CodeTreeBuilder b = el.createInitBuilder();

        b.startNewArray((ArrayType) context.getType(String[].class), null);

        b.string("null");

        int idx = 1;
        for (Operation data : m.getOperations()) {
            if (idx != data.id) {
                throw new AssertionError();
            }
            b.doubleQuote(data.name);
            idx++;
        }

        b.end();

        return el;
    }

    private CodeTypeElement createBuilderState(CodeTypeElement typBuilder, String... fields) {
        CodeTypeElement typ = new CodeTypeElement(MOD_PRIVATE_FINAL, ElementKind.CLASS, null, "BuilderState");
        typ.add(new CodeVariableElement(typ.asType(), "parentData"));

        ArrayList<String> foundFields = new ArrayList<>();

        for (String s : fields) {
            for (VariableElement field : ElementFilter.fieldsIn(typBuilder.getEnclosedElements())) {
                String fn = field.getSimpleName().toString();
                if (fn.equals(s) || fn.startsWith(s + " ")) {
                    typ.add(CodeVariableElement.clone(field));
                    foundFields.add(s);
                    break;
                }
            }
        }

        foundFields.add("parentData");

        CodeExecutableElement ctor = typ.add(new CodeExecutableElement(null, "BuilderState"));
        ctor.addParameter(new CodeVariableElement(typBuilder.asType(), "p"));
        CodeTreeBuilder b = ctor.createBuilder();
        for (String s : foundFields) {
            b.statement(String.format("this.%s = p.%s", s, s));
        }

        return typ;
    }

    private CodeExecutableElement createBuilderImplDeserializeParser(CodeTypeElement typBuilder) {
        CodeExecutableElement met = new CodeExecutableElement(MOD_PRIVATE_STATIC, context.getType(void.class), "deserializeParser");
        met.addParameter(new CodeVariableElement(types.TruffleLanguage, "language"));
        met.addParameter(new CodeVariableElement(context.getType(DATA_INPUT_CLASS), "buffer"));
        met.addParameter(new CodeVariableElement(context.getDeclaredType("com.oracle.truffle.api.operation.serialization.OperationDeserializer"), "callback"));
        met.addParameter(new CodeVariableElement(typBuilder.asType(), "builder"));

        CodeTreeBuilder b = met.createBuilder();
        serializationWrapException(b, () -> {
            b.statement("ArrayList<Object> consts = new ArrayList<>()");
            b.statement("ArrayList<OperationLocal> locals = new ArrayList<>()");
            b.statement("ArrayList<OperationLabel> labels = new ArrayList<>()");
            b.statement("ArrayList<" + m.getTemplateType().getSimpleName() + "> builtNodes = new ArrayList<>()");

            b.statement("buffer.rewind()");
            b.declaration(context.getType(DATA_INPUT_WRAP_CLASS), "dataInput", "com.oracle.truffle.api.operation.serialization.SerializationUtils.createDataInput(buffer)");

            TypeMirror deserContext = context.getDeclaredType("com.oracle.truffle.api.operation.serialization.OperationDeserializer.DeserializerContext");

            b.startStatement();
            b.type(deserContext);
            b.string(" context = ").startNew(deserContext).end().startBlock();

            b.string("@Override").newLine();
            b.string("public " + m.getTemplateType().getSimpleName() + " deserializeOperationNode(").type(context.getType(DATA_INPUT_WRAP_CLASS)).string(" buffer) throws IOException ").startBlock();
            b.statement("return builtNodes.get(buffer.readInt())");
            b.end();

            b.end(2);

            b.startWhile().string("true").end().startBlock();
            b.startSwitch().string("buffer." + DATA_READ_METHOD_PREFIX + "Short()").end().startBlock();

            b.startCase().string("" + SER_CODE_CREATE_LABEL).end().startBlock();
            b.statement("labels.add(builder.createLabel())");
            b.statement("break");
            b.end();

            b.startCase().string("" + SER_CODE_CREATE_LOCAL).end().startBlock();
            b.statement("locals.add(builder.createLocal())");
            b.statement("break");
            b.end();

            b.startCase().string("" + SER_CODE_CREATE_OBJECT).end().startBlock();
            b.statement("consts.add(callback.deserialize(context, dataInput))");
            b.statement("break");
            b.end();

            b.startCase().string("" + SER_CODE_END).end().startBlock();
            b.returnStatement();
            b.end();

            if (!m.getMetadatas().isEmpty()) {
                b.startCase().string("" + SER_CODE_METADATA).end().startBlock();
                // todo: we only need a byte if < 255 metadata types
                b.startSwitch().string("buffer." + DATA_READ_METHOD_PREFIX + "Short()").end().startBlock();
                int i = 0;
                for (OperationMetadataData metadata : m.getMetadatas()) {
                    b.startCase().string("" + i).end().startCaseBlock();
                    b.startStatement().startCall("builder", "set" + metadata.getName());
                    b.startGroup().maybeCast(context.getType(Object.class), metadata.getType());
                    b.string("callback.deserialize(context, dataInput)");
                    b.end(3);
                    b.statement("break");
                    b.end();
                    i++;
                }
                b.end();

                b.statement("break");
                b.end();
            }

            for (Operation op : m.getOperations()) {

                // create begin/emit code
                b.startCase().variable(op.idConstantField).string(" << 1").end().startBlock();

                int i = 0;
                for (TypeMirror argType : op.getBuilderArgumentTypes()) {
                    // ARGUMENT DESERIALIZATION
                    if (ElementUtils.typeEquals(argType, types.TruffleLanguage)) {
                        b.declaration(types.TruffleLanguage, "arg" + i, "language");
                    } else if (ElementUtils.typeEquals(argType, types.OperationLocal)) {
                        b.statement("OperationLocal arg" + i + " = locals.get(buffer." + DATA_READ_METHOD_PREFIX + "Short())");
                    } else if (ElementUtils.typeEquals(argType, new ArrayCodeTypeMirror(types.OperationLocal))) {
                        b.statement("OperationLocal[] arg" + i + " = new OperationLocal[buffer." + DATA_READ_METHOD_PREFIX + "Short()]");
                        b.startFor().string("int i = 0; i < arg" + i + ".length; i++").end().startBlock();
                        // this can be optimized since they are consecutive
                        b.statement("arg" + i + "[i] = locals.get(buffer." + DATA_READ_METHOD_PREFIX + "Short());");
                        b.end();
                    } else if (ElementUtils.typeEquals(argType, types.OperationLabel)) {
                        b.statement("OperationLabel arg" + i + " = labels.get(buffer." + DATA_READ_METHOD_PREFIX + "Short())");
                    } else if (ElementUtils.typeEquals(argType, context.getType(int.class))) {
                        b.statement("int arg" + i + " = buffer." + DATA_READ_METHOD_PREFIX + "Int()");
                    } else if (ElementUtils.isObject(argType) || ElementUtils.typeEquals(argType, types.Source) || ElementUtils.typeEquals(argType, context.getType(Class.class))) {
                        b.startStatement().type(argType).string(" arg" + i + " = ").cast(argType).string("consts.get(buffer." + DATA_READ_METHOD_PREFIX + "Short())").end();
                    } else {
                        throw new UnsupportedOperationException("cannot deserialize: " + argType);
                    }
                    i++;
                }

                b.startStatement();
                if (op.children == 0) {
                    b.startCall("builder.emit" + op.name);
                } else {
                    b.startCall("builder.begin" + op.name);
                }

                for (int j = 0; j < i; j++) {
                    b.string("arg" + j);
                }

                b.end(2); // statement, call

                b.statement("break");

                b.end(); // case block

                if (op.children != 0) {
                    b.startCase().string("(").variable(op.idConstantField).string(" << 1) | 1").end().startBlock();

                    b.startStatement();
                    if (op.isRoot()) {
                        b.startCall("builtNodes.add");
                    }
                    b.string("builder.end" + op.name + "()");
                    if (op.isRoot()) {
                        b.end();
                    }
                    b.end();

                    b.statement("break");

                    b.end();
                }

            }

            b.end();
            b.end();
        });

        return met;
    }

    private CodeExecutableElement createConditionProfile() {
        CodeExecutableElement met = new CodeExecutableElement(MOD_PRIVATE_STATIC, context.getType(boolean.class), "do_profileCondition");
        met.addParameter(new CodeVariableElement(m.getOperationsContext().outerType.asType(), "$this"));
        met.addParameter(new CodeVariableElement(context.getType(boolean.class), "value"));
        met.addParameter(new CodeVariableElement(context.getType(int[].class), "profiles"));
        met.addParameter(new CodeVariableElement(context.getType(int.class), "index"));

        CodeTreeBuilder b = met.createBuilder();

        final int maxInt = 0x3FFFFFFF;

        b.declaration("int", "t", "UFA.unsafeIntArrayRead(profiles, index)");
        b.declaration("int", "f", "UFA.unsafeIntArrayRead(profiles, index + 1)");

        b.declaration("boolean", "val", "value");
        b.startIf().string("val").end().startBlock();

        b.startIf().string("t == 0").end().startBlock();
        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
        b.end();
        b.startIf().string("f == 0").end().startBlock();
        b.statement("val = true");
        b.end();
        b.startIf().tree(GeneratorUtils.createInInterpreter()).end().startBlock();
        b.startIf().string("t < " + maxInt).end().startBlock();
        b.statement("UFA.unsafeIntArrayWrite(profiles, index, t + 1)");
        b.end();
        b.end();

        b.end().startElseBlock();

        b.startIf().string("f == 0").end().startBlock();
        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
        b.end();
        b.startIf().string("t == 0").end().startBlock();
        b.statement("val = false");
        b.end();
        b.startIf().tree(GeneratorUtils.createInInterpreter()).end().startBlock();
        b.startIf().string("f < " + maxInt).end().startBlock();
        b.statement("UFA.unsafeIntArrayWrite(profiles, index + 1, f + 1)");
        b.end();
        b.end();

        b.end();

        b.startIf().tree(GeneratorUtils.createInInterpreter()).end().startBlock();
        b.statement("return val");
        b.end().startElseBlock();
        b.statement("int sum = t + f");
        b.statement("return CompilerDirectives.injectBranchProbability((double) t / (double) sum, val)");
        b.end();

        return met;
    }

    private List<CodeExecutableElement> createBuilderImplGetLocalIndex(CodeTypeElement typLocalData) {
        CodeExecutableElement mGetLocalIndex = new CodeExecutableElement(MOD_PRIVATE, context.getType(short.class), "getLocalIndex");
        mGetLocalIndex.addParameter(new CodeVariableElement(context.getType(Object.class), "value"));
        CodeTreeBuilder b = mGetLocalIndex.createBuilder();
        b.declaration(typLocalData.asType(), "local", "(" + typLocalData.getSimpleName() + ") value");
        b.startAssert().string("verifyNesting(local.owner, operationData) : \"local access not nested properly\"").end();
        b.startReturn().string("(short) local.id").end();

        CodeExecutableElement mGetLocalIndices = new CodeExecutableElement(MOD_PRIVATE, context.getType(int[].class), "getLocalIndices");
        mGetLocalIndices.addParameter(new CodeVariableElement(context.getType(Object.class), "value"));
        b = mGetLocalIndices.createBuilder();
        b.declaration(arrayOf(types.OperationLocal), "locals", "(OperationLocal[]) value");
        b.declaration("int[]", "result", "new int[locals.length]");
        b.startFor().string("int i = 0; i < locals.length; i++").end().startBlock();
        b.statement("result[i] = getLocalIndex(locals[i])");
        b.end();
        b.statement("return result");

        return List.of(mGetLocalIndex, mGetLocalIndices);
    }

    private CodeExecutableElement createBuilderImplVerifyNesting(CodeTypeElement opDataImpl) {
        CodeExecutableElement mDoEmitLabel = new CodeExecutableElement(MOD_PRIVATE, context.getType(boolean.class), "verifyNesting");
        mDoEmitLabel.addParameter(new CodeVariableElement(opDataImpl.asType(), "parent"));
        mDoEmitLabel.addParameter(new CodeVariableElement(opDataImpl.asType(), "child"));

        CodeTreeBuilder b = mDoEmitLabel.createBuilder();

        b.declaration(opDataImpl.asType(), "cur", "child");
        b.startWhile().string("cur.depth > parent.depth").end().startBlock();
        b.statement("cur = cur.parent");
        b.end();

        b.statement("return cur == parent");

        return mDoEmitLabel;
    }

    private CodeExecutableElement createBuilderImplDoEmitLabel(CodeTypeElement typLabelData) {
        CodeExecutableElement mDoEmitLabel = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "doEmitLabel");
        mDoEmitLabel.addParameter(new CodeVariableElement(types.OperationLabel, "label"));

        CodeTreeBuilder b = mDoEmitLabel.createBuilder();

        b.declaration(typLabelData.asType(), "lbl", "(" + typLabelData.getSimpleName() + ") label");

        b.startIf().string("lbl.hasValue").end().startBlock();
        b.startThrow().startNew(context.getType(UnsupportedOperationException.class)).doubleQuote("label already emitted").end(2);
        b.end();

        b.startIf().string("operationData != lbl.data").end().startBlock();
        b.startThrow().startNew(context.getType(UnsupportedOperationException.class)).doubleQuote("label must be created and emitted inside same opeartion").end(2);
        b.end();

        b.statement("lbl.hasValue = true");
        b.statement("lbl.targetBci = bci");

        if (m.isTracing()) {
            b.statement("isBbStart[bci] = true");
        }

        return mDoEmitLabel;
    }

    private List<CodeExecutableElement> createBuilderImplCalculateLeaves(CodeTypeElement opDataImpl, CodeTypeElement typLabelData) {
        CodeExecutableElement mCalculateLeaves = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "calculateLeaves");
        mCalculateLeaves.addParameter(new CodeVariableElement(opDataImpl.asType(), "fromData"));
        mCalculateLeaves.addParameter(new CodeVariableElement(opDataImpl.asType(), "toData"));

        CodeTreeBuilder b = mCalculateLeaves.createBuilder();

        b.startIf().string("toData != null && fromData.depth < toData.depth").end().startBlock();
        b.startThrow().startNew(context.getType(UnsupportedOperationException.class)).doubleQuote("illegal jump to deeper operation").end(2);
        b.end();

        b.startIf().string("fromData == toData").end().startBlock().returnStatement().end();

        b.declaration(opDataImpl.asType(), "cur", "fromData");

        b.startWhile().string("true").end().startBlock();

        b.statement("doLeaveOperation(cur)");
        b.statement("cur = cur.parent");

        b.startIf().string("toData == null && cur == null").end().startBlock();
        b.statement("break");
        b.end().startElseIf().string("toData != null && cur.depth <= toData.depth").end();
        b.statement("break");
        b.end();

        b.end();

        b.startIf().string("cur != toData").end();
        b.startThrow().startNew(context.getType(UnsupportedOperationException.class)).doubleQuote("illegal jump to non-parent operation").end(2);
        b.end();

        CodeExecutableElement oneArg = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "calculateLeaves");
        oneArg.addParameter(new CodeVariableElement(opDataImpl.asType(), "fromData"));
        oneArg.createBuilder().statement("calculateLeaves(fromData, (BuilderOperationData) null)");

        CodeExecutableElement labelArg = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "calculateLeaves");
        labelArg.addParameter(new CodeVariableElement(opDataImpl.asType(), "fromData"));
        labelArg.addParameter(new CodeVariableElement(context.getType(Object.class), "toLabel"));
        labelArg.createBuilder().statement("calculateLeaves(fromData, ((OperationLabelImpl) toLabel).data)");

        return List.of(mCalculateLeaves, oneArg, labelArg);
    }

    private CodeTypeElement createOperationLabelImpl(CodeTypeElement opDataImpl, CodeTypeElement typFTC) {
        CodeTypeElement typOperationLabel = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "OperationLabelImpl", types.OperationLabel);

        typOperationLabel.add(new CodeVariableElement(opDataImpl.asType(), "data"));
        typOperationLabel.add(new CodeVariableElement(typFTC.asType(), "finallyTry"));

        typOperationLabel.add(GeneratorUtils.createConstructorUsingFields(Set.of(), typOperationLabel));

        typOperationLabel.add(new CodeVariableElement(context.getType(int.class), "targetBci = 0"));
        typOperationLabel.add(new CodeVariableElement(context.getType(boolean.class), "hasValue = false"));

        CodeExecutableElement mBelongsTo = typOperationLabel.add(new CodeExecutableElement(context.getType(boolean.class), "belongsTo"));
        mBelongsTo.addParameter(new CodeVariableElement(typFTC.asType(), "context"));
        CodeTreeBuilder b = mBelongsTo.createBuilder();
        b.declaration(typFTC.asType(), "cur", "finallyTry");
        b.startWhile().string("cur != null").end().startBlock(); // {

        b.startIf().string("cur == context").end().startBlock().returnTrue().end();
        b.statement("cur = cur.prev");

        b.end(); // }

        b.returnFalse();

        return typOperationLabel;
    }

    private CodeTypeElement createOperationSerLabelImpl() {
        CodeTypeElement typOperationLabel = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "OperationSerLabelImpl", types.OperationLabel);

        typOperationLabel.add(new CodeVariableElement(context.getType(int.class), "id"));

        typOperationLabel.add(GeneratorUtils.createConstructorUsingFields(Set.of(), typOperationLabel));

        return typOperationLabel;
    }

    private CodeTypeElement createOperationLocalImpl(CodeTypeElement opDataImpl) {
        CodeTypeElement typLocalData = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "OperationLocalImpl", types.OperationLocal);

        typLocalData.add(new CodeVariableElement(MOD_FINAL, opDataImpl.asType(), "owner"));
        typLocalData.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "id"));

        typLocalData.add(GeneratorUtils.createConstructorUsingFields(Set.of(), typLocalData));

        return typLocalData;
    }

    private void serializationWrapException(CodeTreeBuilder b, Runnable r) {
        b.startTryBlock();
        r.run();
        b.end().startCatchBlock(context.getType(IOException.class), "ex");
        b.startThrow().startNew(context.getType(IOError.class)).string("ex").end(2);
        b.end();
    }

    @SuppressWarnings("static-method")
    private CodeExecutableElement createBuilderImplCreateLocal(CodeTypeElement typBuilder, CodeTypeElement typLocalData) {
        CodeExecutableElement mCreateLocal = new CodeExecutableElement(MOD_PUBLIC_FINAL, types.OperationLocal, "createLocal");
        CodeTreeBuilder b = mCreateLocal.createBuilder();

        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            b.startIf().string("isSerializing").end().startBlock();
            serializationWrapException(b, () -> {
                b.statement("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) " + SER_CODE_CREATE_LOCAL + ")");
                b.statement("return new OperationLocalImpl(null, numLocals++)");
            });
            b.end();
        }

        b.startReturn().startNew(typLocalData.asType()).string("operationData").string("numLocals++").end(2);
        return mCreateLocal;
    }

    @SuppressWarnings("static-method")
    private CodeExecutableElement createBuilderImplCreateParentLocal(CodeTypeElement typBuilder, CodeTypeElement typLocalData) {
        CodeExecutableElement mCreateLocal = new CodeExecutableElement(MOD_PRIVATE, typLocalData.asType(), "createParentLocal");
        mCreateLocal.createBuilder().startReturn().startNew(typLocalData.asType()).string("operationData.parent").string("numLocals++").end(2);
        return mCreateLocal;
    }

    @SuppressWarnings("static-method")
    private CodeExecutableElement createBuilderImplCreateLabel(CodeTypeElement typBuilder, CodeTypeElement typLabelData) {
        CodeExecutableElement mCreateLocal = new CodeExecutableElement(MOD_PUBLIC_FINAL, types.OperationLabel, "createLabel");

        CodeTreeBuilder b = mCreateLocal.createBuilder();

        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            b.startIf().string("isSerializing").end().startBlock();
            serializationWrapException(b, () -> {
                b.statement("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) " + SER_CODE_CREATE_LABEL + ")");
                b.statement("return new OperationSerLabelImpl(numLabels++)");
            });
            b.end();
        }

        b.startAssign("OperationLabelImpl label").startNew(typLabelData.asType()).string("operationData").string("currentFinallyTry").end(2);
        b.startStatement().startCall("labels", "add").string("label").end(2);
        b.startReturn().string("label").end();

        return mCreateLocal;
    }

    private CodeTypeElement createExceptionHandler() {
        CodeTypeElement typ = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "ExceptionHandler", null);

        typ.add(new CodeVariableElement(context.getType(int.class), "startBci"));
        typ.add(new CodeVariableElement(context.getType(int.class), "startStack"));
        typ.add(new CodeVariableElement(context.getType(int.class), "endBci"));
        typ.add(new CodeVariableElement(context.getType(int.class), "exceptionIndex"));
        typ.add(new CodeVariableElement(context.getType(int.class), "handlerBci"));

        typ.add(GeneratorUtils.createConstructorUsingFields(Set.of(), typ));

        typ.add(new CodeExecutableElement(null, typ.getSimpleName().toString()));

        CodeExecutableElement metOffset = typ.add(new CodeExecutableElement(typ.asType(), "offset"));
        metOffset.addParameter(new CodeVariableElement(context.getType(int.class), "offset"));
        metOffset.addParameter(new CodeVariableElement(context.getType(int.class), "stackOffset"));

        CodeTreeBuilder b = metOffset.createBuilder();
        b.startReturn().startNew(typ.asType());
        b.string("startBci + offset");
        b.string("startStack + stackOffset");
        b.string("endBci + offset");
        b.string("exceptionIndex");
        b.string("handlerBci + offset");
        b.end(2);

        CodeExecutableElement mToString = typ.add(GeneratorUtils.override(context.getDeclaredType(Object.class), "toString"));

        b = mToString.createBuilder();
        b.startReturn().startCall("String.format");
        b.doubleQuote("handler {start=%04x, end=%04x, stack=%d, local=%d, handler=%04x}");
        b.string("startBci").string("endBci").string("startStack").string("exceptionIndex").string("handlerBci");
        b.end(2);

        return typ;
    }

    // -------------------------------- source builder ----------------------------

    private CodeTypeElement createSourceBuilder(CodeTypeElement typBuilderImpl) {
        CodeTypeElement typSourceBuilder = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "SourceInfoBuilder", null);
        typSourceBuilder.setEnclosingElement(typBuilderImpl);

        typSourceBuilder.add(new CodeVariableElement(MOD_PRIVATE_FINAL, generic(ArrayList.class, types.Source), "sourceList"));

        typSourceBuilder.add(GeneratorUtils.createConstructorUsingFields(Set.of(), typSourceBuilder));

        CodeTypeElement typSourceData = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "SourceData", null);
        typSourceBuilder.add(typSourceData);
        typSourceData.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "start"));
        typSourceData.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "length"));
        typSourceData.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "sourceIndex"));
        typSourceData.add(GeneratorUtils.createConstructorUsingFields(Set.of(), typSourceData));

        typSourceBuilder.add(new CodeVariableElement(MOD_PRIVATE_FINAL, generic(ArrayList.class, context.getType(Integer.class)), "sourceStack = new ArrayList<>()"));
        typSourceBuilder.add(new CodeVariableElement(MOD_PRIVATE, context.getType(int.class), "currentSource = -1"));
        typSourceBuilder.add(new CodeVariableElement(MOD_PRIVATE_FINAL, generic(ArrayList.class, context.getType(Integer.class)), "bciList = new ArrayList<>()"));
        typSourceBuilder.add(new CodeVariableElement(MOD_PRIVATE_FINAL, generic(ArrayList.class, typSourceData.asType()), "sourceDataList = new ArrayList<>()"));
        typSourceBuilder.add(new CodeVariableElement(MOD_PRIVATE_FINAL, generic(ArrayList.class, typSourceData.asType()), "sourceDataStack = new ArrayList<>()"));

        typSourceBuilder.add(createSourceBuilderReset());
        typSourceBuilder.add(createSourceBuilderBeginSource());
        typSourceBuilder.add(createSourceBuilderEndSource());
        typSourceBuilder.add(createSourceBuilderBeginSourceSection());
        typSourceBuilder.add(createSourceBuilderEndSourceSection());
        typSourceBuilder.add(createSourceBuilderBuild());
        typSourceBuilder.add(createSourceBuilderBuildSource());

        return typSourceBuilder;
    }

    private CodeExecutableElement createSourceBuilderReset() {
        CodeExecutableElement mReset = new CodeExecutableElement(context.getType(void.class), "reset");

        CodeTreeBuilder b = mReset.createBuilder();
        b.statement("sourceStack.clear()");
        b.statement("sourceDataList.clear()");
        b.statement("sourceDataStack.clear()");
        b.statement("bciList.clear()");
        return mReset;
    }

    private CodeExecutableElement createSourceBuilderBeginSource() {
        CodeExecutableElement mBeginSource = new CodeExecutableElement(context.getType(void.class), "beginSource");
        mBeginSource.addParameter(new CodeVariableElement(context.getType(int.class), "bci"));
        mBeginSource.addParameter(new CodeVariableElement(types.Source, "src"));

        CodeTreeBuilder b = mBeginSource.createBuilder();
        b.statement("int idx = sourceList.indexOf(src)");

        b.startIf().string("idx == -1").end().startBlock();
        b.statement("idx = sourceList.size()");
        b.statement("sourceList.add(src)");
        b.end();

        b.statement("sourceStack.add(currentSource)");
        b.statement("currentSource = idx");
        b.statement("beginSourceSection(bci, -1, -1)");
        return mBeginSource;
    }

    private CodeExecutableElement createSourceBuilderEndSource() {
        CodeExecutableElement mEndSource = new CodeExecutableElement(context.getType(void.class), "endSource");
        mEndSource.addParameter(new CodeVariableElement(context.getType(int.class), "bci"));

        CodeTreeBuilder b = mEndSource.createBuilder();
        b.statement("endSourceSection(bci)");
        b.statement("currentSource = sourceStack.remove(sourceStack.size() - 1)");
        return mEndSource;
    }

    private CodeExecutableElement createSourceBuilderBeginSourceSection() {
        CodeExecutableElement mBeginSource = new CodeExecutableElement(context.getType(void.class), "beginSourceSection");
        mBeginSource.addParameter(new CodeVariableElement(context.getType(int.class), "bci"));
        mBeginSource.addParameter(new CodeVariableElement(context.getType(int.class), "start"));
        mBeginSource.addParameter(new CodeVariableElement(context.getType(int.class), "length"));

        CodeTreeBuilder b = mBeginSource.createBuilder();
        b.statement("SourceData data = new SourceData(start, length, currentSource)");
        b.statement("bciList.add(bci)");
        b.statement("sourceDataList.add(data)");
        b.statement("sourceDataStack.add(data)");
        return mBeginSource;
    }

    private CodeExecutableElement createSourceBuilderEndSourceSection() {
        CodeExecutableElement mEndSource = new CodeExecutableElement(context.getType(void.class), "endSourceSection");
        mEndSource.addParameter(new CodeVariableElement(context.getType(int.class), "bci"));

        CodeTreeBuilder b = mEndSource.createBuilder();
        b.statement("SourceData data = sourceDataStack.remove(sourceDataStack.size() - 1)");
        b.statement("SourceData prev");

        b.startIf().string("sourceDataStack.isEmpty()").end().startBlock();
        b.statement("prev = new SourceData(-1, -1, currentSource)");
        b.end().startElseBlock();
        b.statement("prev = sourceDataStack.get(sourceDataStack.size() - 1)");
        b.end();

        b.statement("bciList.add(bci)");
        b.statement("sourceDataList.add(prev)");
        return mEndSource;
    }

    private CodeExecutableElement createSourceBuilderBuildSource() {
        CodeExecutableElement mEndSource = new CodeExecutableElement(arrayOf(types.Source), "buildSource");

        CodeTreeBuilder b = mEndSource.createBuilder();
        b.statement("return sourceList.toArray(new Source[0])");

        return mEndSource;
    }

    private CodeExecutableElement createSourceBuilderBuild() {
        CodeExecutableElement mEndSource = new CodeExecutableElement(context.getType(int[].class), "build");

        CodeTreeBuilder b = mEndSource.createBuilder();

        b.startIf().string("!sourceStack.isEmpty()").end().startBlock();
        b.startThrow().startNew(context.getType(IllegalStateException.class)).doubleQuote("not all sources ended").end(2);
        b.end();

        b.startIf().string("!sourceDataStack.isEmpty()").end().startBlock();
        b.startThrow().startNew(context.getType(IllegalStateException.class)).doubleQuote("not all source sections ended").end(2);
        b.end();

        int sourceStride = 3;

        b.statement("int size = bciList.size()");

        b.statement("int[] resultArray = new int[size * " + sourceStride + "]");

        b.statement("int index = 0");
        b.statement("int lastBci = -1");
        b.statement("boolean isFirst = true");

        b.startFor().string("int i = 0; i < size; i++").end().startBlock();
        b.statement("SourceData data = sourceDataList.get(i)");
        b.statement("int curBci = bciList.get(i)");

        b.startIf().string("data.start == -1 && isFirst").end().startBlock().statement("continue").end();

        b.statement("isFirst = false");

        b.startIf().string("curBci == lastBci && index > 1").end().startBlock();
        b.statement("index -= " + sourceStride);
        b.end();

        b.statement("resultArray[index + 0] = curBci | (data.sourceIndex << 16)");
        b.statement("resultArray[index + 1] = data.start");
        b.statement("resultArray[index + 2] = data.length");

        b.statement("index += " + sourceStride);
        b.statement("lastBci = curBci");

        b.end();

        b.statement("return Arrays.copyOf(resultArray, index)");

        return mEndSource;
    }
    // ------------------------------ operadion data impl ------------------------

    private CodeTypeElement createOperationDataImpl() {
        CodeTypeElement opDataImpl = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "BuilderOperationData", null);
        opDataImpl.add(new CodeVariableElement(MOD_FINAL, opDataImpl.asType(), "parent"));
        opDataImpl.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "operationId"));
        opDataImpl.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "stackDepth"));

        CodeExecutableElement ctor = opDataImpl.add(GeneratorUtils.createConstructorUsingFields(MOD_PRIVATE, opDataImpl));
        ctor.addParameter(new CodeVariableElement(context.getType(int.class), "numAux"));
        ctor.addParameter(new CodeVariableElement(context.getType(boolean.class), "needsLeave"));
        ctor.addParameter(new CodeVariableElement(context.getType(Object.class), "...arguments"));

        CodeTreeBuilder b = ctor.appendBuilder();
        b.statement("this.depth = parent == null ? 0 : parent.depth + 1");
        b.statement("this.aux = numAux > 0 ? new Object[numAux] : null");
        b.statement("this.needsLeave = needsLeave || (parent != null && parent.needsLeave)");
        b.statement("this.arguments = arguments");

        opDataImpl.add(new CodeVariableElement(MOD_FINAL, context.getType(boolean.class), "needsLeave"));
        opDataImpl.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "depth"));
        opDataImpl.add(new CodeVariableElement(MOD_FINAL, context.getType(Object[].class), "arguments"));
        opDataImpl.add(new CodeVariableElement(MOD_FINAL, context.getType(Object[].class), "aux"));
        opDataImpl.add(new CodeVariableElement(context.getType(int.class), "numChildren"));

        return opDataImpl;
    }

    private CodeTypeElement createBytecodeBaseClass(CodeTypeElement baseClass, CodeTypeElement opNodeImpl, TypeMirror typOperationNodes, CodeTypeElement typExceptionHandler) {

        CodeExecutableElement loopMethod = new CodeExecutableElement(MOD_ABSTRACT, context.getType(int.class), "continueAt");
        loopMethod.addParameter(new CodeVariableElement(opNodeImpl.asType(), "$this"));
        if (m.enableYield) {
            loopMethod.addParameter(new CodeVariableElement(types.VirtualFrame, "$stackFrame"));
            loopMethod.addParameter(new CodeVariableElement(types.VirtualFrame, "$localFrame"));
        } else {
            loopMethod.addParameter(new CodeVariableElement(types.VirtualFrame, "$frame"));
        }
        loopMethod.addParameter(new CodeVariableElement(context.getType(short[].class), "$bc"));
        loopMethod.addParameter(new CodeVariableElement(context.getType(int.class), "$startBci"));
        loopMethod.addParameter(new CodeVariableElement(context.getType(int.class), "$startSp"));
        loopMethod.addParameter(new CodeVariableElement(context.getType(Object[].class), "$consts"));
        loopMethod.addParameter(new CodeVariableElement(new ArrayCodeTypeMirror(types.Node), "$children"));
        if (m.getOperationsContext().hasBoxingElimination()) {
            loopMethod.addParameter(new CodeVariableElement(context.getType(byte[].class), "$localTags"));
        }
        loopMethod.addParameter(new CodeVariableElement(new ArrayCodeTypeMirror(typExceptionHandler.asType()), "$handlers"));
        loopMethod.addParameter(new CodeVariableElement(context.getType(int[].class), "$conditionProfiles"));
        loopMethod.addParameter(new CodeVariableElement(context.getType(int.class), "maxLocals"));
        baseClass.add(loopMethod);

        CodeExecutableElement dumpMethod = new CodeExecutableElement(MOD_ABSTRACT, types.OperationIntrospection, "getIntrospectionData");
        dumpMethod.addParameter(new CodeVariableElement(opNodeImpl.asType(), "$this"));
        dumpMethod.addParameter(new CodeVariableElement(new ArrayCodeTypeMirror(context.getType(short.class)), "$bc"));
        dumpMethod.addParameter(new CodeVariableElement(new ArrayCodeTypeMirror(typExceptionHandler.asType()), "$handlers"));
        dumpMethod.addParameter(new CodeVariableElement(context.getType(Object[].class), "$consts"));
        dumpMethod.addParameter(new CodeVariableElement(typOperationNodes, "nodes"));
        dumpMethod.addParameter(new CodeVariableElement(context.getType(int[].class), "sourceInfo"));
        baseClass.add(dumpMethod);

        if (m.isGenerateAOT()) {
            CodeExecutableElement prepareAot = new CodeExecutableElement(MOD_ABSTRACT, context.getType(void.class), "prepareForAOT");
            prepareAot.addParameter(new CodeVariableElement(opNodeImpl.asType(), "$this"));
            prepareAot.addParameter(new CodeVariableElement(context.getType(short[].class), "$bc"));
            prepareAot.addParameter(new CodeVariableElement(context.getType(Object[].class), "$consts"));
            prepareAot.addParameter(new CodeVariableElement(new ArrayCodeTypeMirror(types.Node), "$children"));
            prepareAot.addParameter(new CodeVariableElement(types.TruffleLanguage, "language"));
            prepareAot.addParameter(new CodeVariableElement(types.RootNode, "root"));
            baseClass.add(prepareAot);
        }

        baseClass.add(createFormatConstant());
        baseClass.add(createExpectObject(m.getBoxingEliminatedTypes().size() > 0));
        baseClass.add(createSetResultBoxedImpl());
        for (TypeKind kind : m.getBoxingEliminatedTypes()) {
            FrameKind frameKind = FrameKind.valueOfPrimitive(kind);
            baseClass.add(createExpectPrimitive(frameKind));
            // baseClass.add(createStoreLocalCheck(frameKind));
        }

        return baseClass;
    }

    private CodeTypeElement createLabelFill(CodeTypeElement typLabelImpl) {
        CodeTypeElement typ = GeneratorUtils.createClass(m, null, MOD_PRIVATE_STATIC_FINAL, "LabelFill", null);
        typ.add(new CodeVariableElement(context.getType(int.class), "locationBci"));
        typ.add(new CodeVariableElement(typLabelImpl.asType(), "label"));

        typ.add(GeneratorUtils.createConstructorUsingFields(Set.of(), typ));

        CodeExecutableElement metOffset = typ.add(new CodeExecutableElement(typ.asType(), "offset"));
        metOffset.addParameter(new CodeVariableElement(context.getType(int.class), "offset"));

        metOffset.createBuilder().statement("return new LabelFill(offset + locationBci, label)");

        return typ;
    }

    private CodeTypeElement createFinallyTryContext(CodeTypeElement typFtc, CodeTypeElement typExceptionHandler, CodeTypeElement typLabelFill, CodeTypeElement typLabelImpl) {
        typFtc.add(new CodeVariableElement(MOD_FINAL, typFtc.asType(), "prev"));
        typFtc.add(new CodeVariableElement(MOD_FINAL, context.getType(short[].class), "bc"));
        typFtc.add(new CodeVariableElement(MOD_FINAL, generic(ArrayList.class, typExceptionHandler.asType()), "exceptionHandlers"));
        typFtc.add(new CodeVariableElement(MOD_FINAL, generic(ArrayList.class, typLabelFill.asType()), "labelFills"));
        typFtc.add(new CodeVariableElement(MOD_FINAL, generic(ArrayList.class, typLabelImpl.asType()), "labels"));
        typFtc.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "curStack"));
        typFtc.add(new CodeVariableElement(MOD_FINAL, context.getType(int.class), "maxStack"));

        typFtc.add(GeneratorUtils.createConstructorUsingFields(Set.of(), typFtc));

        typFtc.add(new CodeVariableElement(Set.of(), context.getType(short[].class), "handlerBc"));
        typFtc.add(new CodeVariableElement(Set.of(), generic(ArrayList.class, typExceptionHandler.asType()), "handlerHandlers"));
        typFtc.add(new CodeVariableElement(Set.of(), generic(ArrayList.class, typLabelFill.asType()), "handlerLabelFills = new ArrayList<>()"));
        typFtc.add(new CodeVariableElement(Set.of(), generic(ArrayList.class, context.getType(Integer.class)), "relocationOffsets = new ArrayList<>()"));
        typFtc.add(new CodeVariableElement(Set.of(), context.getType(int.class), "handlerMaxStack"));

        return typFtc;
    }

    private CodeExecutableElement createDoLeaveFinallyTry(CodeTypeElement typOperationData) {
        CodeExecutableElement mDoLeaveFinallyTry = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "doLeaveFinallyTry",
                        new CodeVariableElement(typOperationData.asType(), "opData"));

        CodeTreeBuilder b = mDoLeaveFinallyTry.createBuilder();
        b.statement("BuilderFinallyTryContext context = (BuilderFinallyTryContext) opData.aux[0]");

        b.startIf().string("context.handlerBc == null").end().startBlock().returnStatement().end();

        b.statement("System.arraycopy(context.handlerBc, 0, bc, bci, context.handlerBc.length)");

        b.startFor().string("int offset : context.relocationOffsets").end().startBlock();
        b.statement("short oldOffset = bc[bci + offset]");
        b.statement("bc[bci + offset] = (short) (oldOffset + bci)");
        b.end();

        b.startFor().string("ExceptionHandler handler : context.handlerHandlers").end().startBlock();
        b.statement("exceptionHandlers.add(handler.offset(bci, curStack))");
        b.end();

        b.startFor().string("LabelFill fill : context.handlerLabelFills").end().startBlock();
        b.statement("labelFills.add(fill.offset(bci))");
        b.end();

        b.startIf().string("maxStack < curStack + context.handlerMaxStack").end();
        b.statement("maxStack = curStack + context.handlerMaxStack");
        b.end();

        b.statement("bci += context.handlerBc.length");

        return mDoLeaveFinallyTry;
    }

    private CodeExecutableElement createSetMetadata(OperationMetadataData metadata, boolean isAbstract) {
        CodeVariableElement parValue = new CodeVariableElement(metadata.getType(), "value");
        CodeExecutableElement method = new CodeExecutableElement(
                        isAbstract ? MOD_PUBLIC_ABSTRACT : MOD_PUBLIC,
                        context.getType(void.class), "set" + metadata.getName(),
                        parValue);

        if (isAbstract) {
            return method;
        }

        CodeTreeBuilder b = method.createBuilder();

        b.startIf().string("isSerializing").end().startBlock();
        serializationWrapException(b, () -> {
            b.statement("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) " + SER_CODE_METADATA + ")");
            b.statement("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short(" + m.getMetadatas().indexOf(metadata) + ")");
            b.statement("serCallback.serialize(SER_CONTEXT, serBuffer, value)");
            b.returnStatement();
        });
        b.end();

        b.startAssign("metadata_" + metadata.getName()).variable(parValue).end();

        return method;
    }

    private CodeExecutableElement createEmitOperation(CodeTypeElement typBuilder, BuilderVariables vars, Operation op) {
        CodeVariableElement resVariable = op.getResultVariable();
        CodeExecutableElement metEmit = new CodeExecutableElement(MOD_PUBLIC_FINAL, resVariable == null ? context.getType(void.class) : resVariable.asType(), "emit" + op.name);

        createBeginArguments(op, metEmit);

        CodeTreeBuilder b = metEmit.getBuilder();

        createBeginOperationSerialize(vars, op, b);

        if (OperationGeneratorFlags.FLAG_NODE_AST_PRINTING) {
            b.statement("System.out.print(\"\\n\" + \" \".repeat(indent) + \"(" + op.name + ")\")");
        }

        String condition = op.conditionedOn();
        if (condition != null) {
            b.startIf().string(condition).end().startBlock();
        }

        if (op.isRealOperation()) {
            b.statement("doBeforeChild()");
        }

        boolean needsData = op.needsOperationData();

        if (needsData) {
            b.startAssign(vars.operationData);
            b.startNew("BuilderOperationData");

            b.variable(vars.operationData);
            b.variable(op.idConstantField);
            b.string("curStack");
            b.string("" + op.getNumAuxValues());
            b.string("false");

            if (metEmit.getParameters().size() == 1 && metEmit.getParameters().get(0).asType().getKind() == TypeKind.ARRAY) {
                b.startGroup().cast(context.getType(Object.class)).variable(metEmit.getParameters().get(0)).end();
            } else {
                for (VariableElement v : metEmit.getParameters()) {
                    b.variable(v);
                }
            }
            b.end(2);
        }

        b.tree(op.createBeginCode(vars));
        b.tree(op.createEndCode(vars));

        b.startAssign(vars.lastChildPushCount).tree(op.createPushCountCode(vars)).end();

        if (needsData) {
            b.startAssign(vars.operationData).variable(vars.operationData).string(".parent").end();
        }

        if (op.isRealOperation()) {
            b.statement("doAfterChild()");
        }

        if (condition != null) {
            b.end();
        }

        if (resVariable != null) {
            b.startReturn().variable(resVariable).end();
        }

        return metEmit;
    }

    private void createBeginArguments(Operation op, CodeExecutableElement metEmit) {
        int i = 0;
        for (TypeMirror mir : op.getBuilderArgumentTypes()) {
            metEmit.addParameter(new CodeVariableElement(mir, "arg" + i));
            i++;
        }
    }

    private CodeTypeElement createCounter() {
        CodeTypeElement typ = new CodeTypeElement(MOD_PRIVATE_STATIC_FINAL, ElementKind.CLASS, null, "Counter");
        typ.add(new CodeVariableElement(context.getType(int.class), "count"));

        return typ;
    }

    private CodeExecutableElement createEndOperation(CodeTypeElement typBuilder, BuilderVariables vars, Operation op) {
        CodeVariableElement resultVar = op.getResultVariable();
        CodeExecutableElement metEnd = new CodeExecutableElement(MOD_PUBLIC_FINAL, resultVar == null ? context.getType(void.class) : resultVar.getType(), "end" + op.name);
        GeneratorUtils.addSuppressWarnings(context, metEnd, "unused");

        // if (operationData.id != ID) throw;
        // << end >>

        // operationData = operationData.parent;

        // doAfterChild();
        CodeTreeBuilder b = metEnd.getBuilder();

        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            b.startIf().string("isSerializing").end().startBlock();
            serializationWrapException(b, () -> {
                b.startStatement().string("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) ((").variable(op.idConstantField).string(" << 1) | 1))").end();
            });
            if (op.isRoot()) {
                b.startReturn().string("publish(null)").end();
            } else {
                b.returnStatement();
            }
            b.end();
        }

        String condition = op.conditionedOn();
        if (condition != null) {
            b.startIf().string(condition).end().startBlock();
        }

        if (OperationGeneratorFlags.FLAG_NODE_AST_PRINTING) {
            b.statement("System.out.print(\")\")");
            b.statement("indent--");
        }

        b.startIf().string("operationData.operationId != ").variable(op.idConstantField).end();
        b.startBlock();
        b.startThrow().startNew(context.getType(IllegalStateException.class)).startGroup().doubleQuote("Mismatched begin/end, expected end").string(
                        " + OPERATION_NAMES[operationData.operationId] + ").doubleQuote(", got end" + op.name).end(3);
        b.end();

        vars.numChildren = new CodeVariableElement(context.getType(int.class), "numChildren");
        b.declaration("int", "numChildren", "operationData.numChildren");

        if (!op.isVariableChildren()) {
            b.startIf().string("numChildren != " + op.children).end();
            b.startBlock();
            b.startThrow().startNew(context.getType(IllegalStateException.class)).startGroup().doubleQuote(op.name + " expected " + op.children + " children, got ").string(" + numChildren").end(3);
            b.end();
        } else {
            b.startIf().string("numChildren < " + op.minimumChildren()).end();
            b.startBlock();
            b.startThrow().startNew(context.getType(IllegalStateException.class)).startGroup().doubleQuote(op.name + " expected at least " + op.minimumChildren() + " children, got ").string(
                            " + numChildren").end(3);
            b.end();
        }

        b.tree(op.createEndCode(vars));

        CodeTree lastPush = op.createPushCountCode(vars);
        if (lastPush != null) {
            b.startAssign(vars.lastChildPushCount).tree(lastPush).end();
        }

        if (op.needsOperationData()) {
            b.startAssign(vars.operationData).variable(vars.operationData).string(".parent").end();
        }

        if (op.isRealOperation() && !op.isRoot()) {
            b.statement("doAfterChild()");
        }

        if (condition != null) {
            b.end();
        }

        vars.numChildren = null;

        if (resultVar != null) {
            b.startReturn().variable(resultVar).end();
        }

        return metEnd;
    }

    private CodeExecutableElement createBeginOperation(CodeTypeElement typBuilder, BuilderVariables vars, Operation op) {
        CodeExecutableElement metBegin = new CodeExecutableElement(MOD_PUBLIC_FINAL, context.getType(void.class), "begin" + op.name);
        GeneratorUtils.addSuppressWarnings(context, metBegin, "unused");

        createBeginArguments(op, metBegin);

        // doBeforeChild();

        // operationData = new ...(operationData, ID, <x>, args...);

        // << begin >>

        CodeTreeBuilder b = metBegin.getBuilder();

        createBeginOperationSerialize(vars, op, b);

        String condition = op.conditionedOn();
        if (condition != null) {
            b.startIf().string(condition).end().startBlock();
        }

        if (OperationGeneratorFlags.FLAG_NODE_AST_PRINTING) {
            b.statement("System.out.print(\"\\n\" + \" \".repeat(indent) + \"(" + op.name + "\")");
            b.statement("indent++");
        }

        if (op.isRealOperation() && !op.isRoot()) {
            b.statement("doBeforeChild()");
        }

        if (op.needsOperationData()) {
            b.startAssign(vars.operationData).startNew("BuilderOperationData");

            b.variable(vars.operationData);
            b.variable(op.idConstantField);
            b.string("curStack");
            b.string("" + op.getNumAuxValues());
            b.string("" + op.hasLeaveCode());

            for (VariableElement el : metBegin.getParameters()) {
                b.startGroup().cast(context.getType(Object.class)).variable(el).end();
            }

            b.end(2);
        }

        b.tree(op.createBeginCode(vars));

        if (condition != null) {
            b.end();
        }

        return metBegin;
    }

    private void createBeginOperationSerialize(BuilderVariables vars, Operation op, CodeTreeBuilder b) {
        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            b.startIf().string("isSerializing").end().startBlock();

            serializationWrapException(b, () -> {
                ArrayList<String> after = new ArrayList<>();

                int i = 0;
                for (TypeMirror argType : op.getBuilderArgumentTypes()) {
                    // ARGUMENT SERIALIZATION
                    if (ElementUtils.typeEquals(argType, types.TruffleLanguage)) {
                        // nothing
                    } else if (ElementUtils.typeEquals(argType, types.OperationLocal)) {
                        after.add("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) ((OperationLocalImpl) arg" + i + ").id)");
                    } else if (ElementUtils.typeEquals(argType, new ArrayCodeTypeMirror(types.OperationLocal))) {
                        after.add("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) arg" + i + ".length)");
                        after.add("for (int i = 0; i < arg" + i + ".length; i++) { serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) ((OperationLocalImpl) arg" + i + "[i]).id); }");
                    } else if (ElementUtils.typeEquals(argType, types.OperationLabel)) {
                        after.add("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) ((OperationSerLabelImpl) arg" + i + ").id)");
                    } else if (ElementUtils.typeEquals(argType, context.getType(int.class))) {
                        after.add("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Int(arg" + i + ")");
                    } else if (ElementUtils.isObject(argType) || ElementUtils.typeEquals(argType, types.Source) || ElementUtils.typeEquals(argType, context.getType(Class.class))) {
                        String index = "arg" + i + "_index";
                        b.startAssign("int " + index).variable(vars.consts).startCall(".indexOf").string("arg" + i).end(2);
                        b.startIf().string(index + " == -1").end().startBlock();
                        b.startAssign(index).variable(vars.consts).startCall(".size").end(2);
                        b.startStatement().variable(vars.consts).startCall(".add").string("arg" + i).end(2);
                        b.statement("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) " + SER_CODE_CREATE_OBJECT + ")");
                        b.statement("serCallback.serialize(SER_CONTEXT, serBuffer, arg" + i + ")");
                        b.end();
                        after.add("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) arg" + i + "_index)");
                    } else {
                        throw new UnsupportedOperationException("cannot serialize: " + argType);
                    }
                    i++;
                }

                b.startStatement().string("serBuffer." + DATA_WRITE_METHOD_PREFIX + "Short((short) (").variable(op.idConstantField).string(" << 1))").end();

                after.forEach(b::statement);
            });

            b.returnStatement();
            b.end();
        }
    }

    private CodeExecutableElement createSetResultUnboxed() {
        CodeExecutableElement mDoSetResultUnboxed = new CodeExecutableElement(MOD_PRIVATE_STATIC, context.getType(void.class), "doSetResultBoxed");

        CodeVariableElement varBc = new CodeVariableElement(arrayOf(context.getType(short.class)), "bc");
        mDoSetResultUnboxed.addParameter(varBc);

        CodeVariableElement varStartBci = new CodeVariableElement(context.getType(int.class), "startBci");
        mDoSetResultUnboxed.addParameter(varStartBci);

        CodeVariableElement varBciOffset = new CodeVariableElement(context.getType(int.class), "bciOffset");
        mDoSetResultUnboxed.addParameter(varBciOffset);

        CodeVariableElement varTargetType = new CodeVariableElement(context.getType(int.class), "targetType");
        mDoSetResultUnboxed.addParameter(varTargetType);

        CodeTreeBuilder b = mDoSetResultUnboxed.createBuilder();

        b.startIf().variable(varBciOffset).string(" != 0").end().startBlock();
        // {
        b.startStatement().startCall("setResultBoxedImpl");
        b.variable(varBc);
        b.string("startBci - bciOffset");
        b.variable(varTargetType);
        b.end(2);
        // }
        b.end();
        return mDoSetResultUnboxed;
    }

    private CodeExecutableElement createBuilderImplPublish(CodeTypeElement typOperationNodeImpl) {
        CodeExecutableElement mPublish = new CodeExecutableElement(MOD_PRIVATE, m.getTemplateType().asType(), "publish");

        CodeVariableElement parLanguage = new CodeVariableElement(types.TruffleLanguage, "language");
        mPublish.addParameter(parLanguage);

        CodeTreeBuilder b = mPublish.createBuilder();

        if (OperationGeneratorFlags.ENABLE_SERIALIZATION) {
            b.startIf().string("isSerializing").end().startBlock();
            b.declaration(m.getTemplateType().getSimpleName().toString(), "result", "new OperationSerNodeImpl(null, FrameDescriptor.newBuilder().build(), buildIndex++)");
            b.statement("numLocals = 0");
            b.statement("numLabels = 0");
            b.statement("return result");
            b.end();
        }

        b.startIf().string("operationData.depth != 0").end().startBlock();
        b.startThrow().startNew(context.getType(UnsupportedOperationException.class)).doubleQuote("Not all operations closed").end(2);
        b.end();

        b.declaration(typOperationNodeImpl.asType(), "result", (CodeTree) null);

        b.startIf().string("!isReparse").end().startBlock();

        b.declaration(types.FrameDescriptor, ".Builder frameDescriptor", (CodeTree) null);
        b.startAssign("frameDescriptor").startStaticCall(types.FrameDescriptor, "newBuilder").string("numLocals + maxStack").end(2);
        b.startStatement().startCall("frameDescriptor.addSlots").string("numLocals + maxStack").staticReference(types.FrameSlotKind, "Illegal").end(2);

        b.startAssign("result").startNew(typOperationNodeImpl.asType()).variable(parLanguage).string("frameDescriptor").end(2);
        b.startAssign("result.nodes").string("nodes").end();

        b.statement("labelPass(null)");
        b.statement("result._bc = Arrays.copyOf(bc, bci)");
        b.statement("result._consts = constPool.toArray()");
        b.statement("result._children = new Node[numChildNodes]");
        if (m.getOperationsContext().hasBoxingElimination()) {
            b.statement("result._localTags = new byte[numLocals]");
        }
        if (m.isTracing()) {
            b.statement("result.isBbStart = Arrays.copyOf(isBbStart, bci)");
        }
        b.statement("result._handlers = exceptionHandlers.toArray(new ExceptionHandler[0])");
        b.statement("result._conditionProfiles = new int[numConditionProfiles]");
        b.statement("result._maxLocals = numLocals");
        b.statement("result._maxStack = maxStack");

        if (m.enableYield) {
            b.statement("result.yieldEntries = yieldCount > 0 ? new ContinuationRoot[yieldCount] : null");
            b.startFor().string("int i = 0; i < yieldCount; i++").end().startBlock();
            b.statement("yieldLocations[i].root = result");
            b.end();
        }

        b.startIf().string("sourceBuilder != null").end().startBlock();
        b.statement("result.sourceInfo = sourceBuilder.build()");
        b.end();

        for (OperationMetadataData metadata : m.getMetadatas()) {
            b.statement("result._metadata_" + metadata.getName() + " = metadata_" + metadata.getName());
        }

        b.statement("assert builtNodes.size() == buildIndex");
        b.statement("builtNodes.add(result)");

        b.end().startElseBlock();

        b.statement("result = builtNodes.get(buildIndex)");

        b.startIf().string("withSource && result.sourceInfo == null").end().startBlock();
        b.statement("result.sourceInfo = sourceBuilder.build()");
        b.end();

        b.end();

        b.startIf().string("withInstrumentation").end().startBlock();
        b.statement("result.changeInterpreters(INSTRUMENTABLE_EXECUTE)");
        b.statement("result.instruments = instrumentList.toArray(new InstrumentTreeNode[0])");
        b.end();

        b.startAssert().string("instrumentStack.size() == 1").end();
        b.statement("result.instrumentRoot.setChildren(instrumentStack.get(0).toArray(new InstrumentTreeNode[0]))");

        b.statement("buildIndex++");
        b.statement("reset(language)");

        b.statement("return result");

        return mPublish;
    }

    private CodeExecutableElement createBuilderImplLabelPass(CodeTypeElement typFtc) {
        CodeExecutableElement mPublish = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "labelPass");
        mPublish.addParameter(new CodeVariableElement(typFtc.asType(), "finallyTry"));

        CodeTreeBuilder b = mPublish.createBuilder();

        b.startFor().string("LabelFill fill : labelFills").end().startBlock(); // {
        b.startIf().string("finallyTry != null").end().startBlock(); // {
        b.startIf().string("fill.label.belongsTo(finallyTry)").end().startBlock(); // {

        b.statement("assert fill.label.hasValue : \"inner label should have been resolved by now\"");
        b.statement("finallyTry.relocationOffsets.add(fill.locationBci)");

        b.end().startElseBlock(); // } {

        b.statement("finallyTry.handlerLabelFills.add(fill)");

        b.end(); // }
        b.end(); // }

        b.statement("bc[fill.locationBci] = (short) fill.label.targetBci");

        b.end(); // }

        return mPublish;

    }

    private CodeExecutableElement createAfterChild(BuilderVariables vars) {
        CodeExecutableElement mAfterChild = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "doAfterChild");
        CodeTreeBuilder b = mAfterChild.getBuilder();
        GeneratorUtils.addSuppressWarnings(context, mAfterChild, "unused");

        CodeVariableElement varChildIndex = new CodeVariableElement(context.getType(int.class), "childIndex");
        b.declaration("int", varChildIndex.getName(), "operationData.numChildren++");

        vars.childIndex = varChildIndex;

        b.startSwitch().variable(vars.operationData).string(".operationId").end(2);
        b.startBlock();

        for (Operation parentOp : m.getOperations()) {

            CodeTree afterChild = parentOp.createAfterChildCode(vars);
            if (afterChild == null) {
                continue;
            }

            b.startCase().variable(parentOp.idConstantField).end();
            b.startBlock();

            b.tree(afterChild);

            b.statement("break");
            b.end();
        }

        b.end();

        vars.childIndex = null;
        return mAfterChild;
    }

    private CodeExecutableElement createBeforeChild(BuilderVariables vars) {
        CodeExecutableElement mBeforeChild = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "doBeforeChild");
        CodeTreeBuilder b = mBeforeChild.getBuilder();
        GeneratorUtils.addSuppressWarnings(context, mBeforeChild, "unused");

        CodeVariableElement varChildIndex = new CodeVariableElement(context.getType(int.class), "childIndex");
        b.declaration("int", varChildIndex.getName(), "operationData.numChildren");

        vars.childIndex = varChildIndex;

        b.startSwitch().variable(vars.operationData).string(".operationId").end(2);
        b.startBlock();

        for (Operation parentOp : m.getOperations()) {

            CodeTree afterChild = parentOp.createBeforeChildCode(vars);
            if (afterChild == null) {
                continue;
            }

            b.startCase().variable(parentOp.idConstantField).end();
            b.startBlock();

            b.tree(afterChild);

            b.statement("break");
            b.end();
        }

        b.end();

        vars.childIndex = null;

        return mBeforeChild;
    }

    private CodeExecutableElement createDoLeave(BuilderVariables vars, CodeTypeElement typOperationData) {
        CodeVariableElement parData = new CodeVariableElement(typOperationData.asType(), "data");
        CodeExecutableElement mDoLeave = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "doLeaveOperation", parData);

        CodeTreeBuilder b = mDoLeave.createBuilder();

        b.startSwitch().string("data.operationId").end();
        b.startBlock();

        CodeVariableElement oldOperationData = vars.operationData;

        vars.operationData = parData;

        for (Operation op : m.getOperations()) {
            CodeTree leaveCode = op.createLeaveCode(vars);
            if (leaveCode == null) {
                continue;
            }

            b.startCase().variable(op.idConstantField).end();
            b.startBlock();

            b.tree(leaveCode);
            b.statement("break");

            b.end();

        }

        vars.operationData = oldOperationData;

        b.end();

        return mDoLeave;
    }

    private static TypeMirror arrayOf(TypeMirror el) {
        return new ArrayCodeTypeMirror(el);
    }

    private static TypeMirror operationParser(TypeMirror el) {
        return new DeclaredCodeTypeMirror(ProcessorContext.getInstance().getTypeElement("com.oracle.truffle.api.operation.OperationParser"), List.of(el));
    }

    private static TypeMirror generic(TypeElement el, TypeMirror... args) {
        return new DeclaredCodeTypeMirror(el, List.of(args));
    }

    private static TypeMirror generic(DeclaredType el, TypeMirror... args) {
        return new DeclaredCodeTypeMirror((TypeElement) el.asElement(), List.of(args));
    }

    private static TypeMirror generic(Class<?> cls, TypeMirror... args) {
        return generic(ProcessorContext.getInstance().getTypeElement(cls), args);
    }

    @Override
    @SuppressWarnings("hiding")
    public List<CodeTypeElement> create(ProcessorContext context, AnnotationProcessor<?> processor, OperationsData m) {
        this.context = context;
        this.m = m;

        String simpleName = m.getTemplateType().getSimpleName() + "Gen";

        try {
            CodeTypeElement el = createOperationNodeImpl();
            OperationGeneratorUtils.checkAccessibility(el);
            return List.of(el);

        } catch (Throwable e) {
            CodeTypeElement el = GeneratorUtils.createClass(m, null, Set.of(), simpleName, null);
            CodeTreeBuilder b = el.createDocBuilder();

            StringWriter sb = new StringWriter();
            e.printStackTrace(new PrintWriter(sb));
            for (String line : sb.toString().split("\n")) {
                b.string("// " + line).newLine();
            }

            return List.of(el);
        }
    }

    // -------------------------- helper methods moved to generated code --------------------------

    private CodeExecutableElement createExpectObject(boolean hasAnyBoxingElimination) {
        CodeExecutableElement mExpectObject = new CodeExecutableElement(MOD_PROTECTED_STATIC, context.getType(Object.class), "expectObject");
        mExpectObject.addParameter(new CodeVariableElement(types.VirtualFrame, "frame"));
        mExpectObject.addParameter(new CodeVariableElement(context.getType(int.class), "slot"));

        if (hasAnyBoxingElimination) {
            mExpectObject.addParameter(new CodeVariableElement(context.getType(short[].class), "bc"));
            mExpectObject.addParameter(new CodeVariableElement(context.getType(int.class), "bci"));
            mExpectObject.addParameter(new CodeVariableElement(context.getType(int.class), "bciOffset"));
        }

        CodeTreeBuilder b = mExpectObject.createBuilder();

        if (hasAnyBoxingElimination) {
            b.startIf().string("bciOffset == 0 || UFA.unsafeIsObject(frame, slot)").end().startBlock();
        }

        b.startReturn().string("UFA.unsafeUncheckedGetObject(frame, slot)").end();

        if (hasAnyBoxingElimination) {
            b.end().startElseBlock(); // } else {

            b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
            b.startStatement().startCall("setResultBoxedImpl").string("bc").string("bci - bciOffset").string("0").end(2);
            b.startReturn().string("frame.getValue(slot)").end();

            b.end(); // }
        }

        return mExpectObject;
    }

    private CodeExecutableElement createExpectPrimitive(FrameKind kind) {
        CodeExecutableElement mExpectPrimitive = new CodeExecutableElement(MOD_PROTECTED_STATIC, kind.getType(), "expect" + kind.getFrameName());
        mExpectPrimitive.addParameter(new CodeVariableElement(types.VirtualFrame, "frame"));
        mExpectPrimitive.addParameter(new CodeVariableElement(context.getType(int.class), "slot"));
        mExpectPrimitive.addParameter(new CodeVariableElement(context.getType(short[].class), "bc"));
        mExpectPrimitive.addParameter(new CodeVariableElement(context.getType(int.class), "bci"));
        mExpectPrimitive.addParameter(new CodeVariableElement(context.getType(int.class), "bciOffset"));

        mExpectPrimitive.addThrownType(types.UnexpectedResultException);

        CodeTreeBuilder b = mExpectPrimitive.createBuilder();

        if (OperationGeneratorFlags.LOG_STACK_READS) {
            b.statement("System.err.println(\"[ SRD ] stack read @ \" + slot + \" : \" + frame.getValue(slot) + \" as \" + frame.getTag(slot))");
        }

        b.startIf().string("bciOffset == 0").end().startBlock();

        b.startAssign("Object value").startCall("UFA", "unsafeUncheckedGetObject");
        b.string("frame");
        b.string("slot");
        b.end(2);

        b.startIf().string("value instanceof " + kind.getTypeNameBoxed()).end().startBlock();
        b.startReturn().string("(", kind.getTypeName(), ") value").end();
        b.end().startElseBlock();
        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
        b.startThrow().startNew(types.UnexpectedResultException).string("value").end(2);
        b.end();

        b.end().startElseBlock();

        b.startSwitch().startCall("UFA", "unsafeGetTag").string("frame").string("slot").end(2).startBlock();

        b.startCase().string(kind.toOrdinal()).end().startCaseBlock();
        b.startReturn().startCall("UFA", "unsafeUncheckedGet" + kind.getFrameName()).string("frame").string("slot").end(2);
        b.end();

        b.startCase().string(FrameKind.OBJECT.toOrdinal()).end().startCaseBlock();
        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
        b.declaration("Object", "value", "UFA.unsafeUncheckedGetObject(frame, slot)");

        b.startIf().string("value instanceof " + kind.getTypeNameBoxed()).end().startBlock();
        b.startStatement().startCall("setResultBoxedImpl").string("bc").string("bci - bciOffset").string(kind.toOrdinal()).end(2);
        b.startReturn().string("(", kind.getTypeName(), ") value").end();
        b.end(); // if

        b.statement("break");
        b.end(); // case

        b.end(); // switch

        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());

        b.startStatement().startCall("setResultBoxedImpl").string("bc").string("bci - bciOffset").string("0").end(2);
        b.startThrow().startNew(types.UnexpectedResultException).string("frame.getValue(slot)").end(2);

        b.end(); // else

        return mExpectPrimitive;
    }

    private CodeExecutableElement createStoreLocalCheck(FrameKind kind) {
        CodeExecutableElement mStoreLocalCheck = new CodeExecutableElement(MOD_PROTECTED_STATIC, context.getType(boolean.class), "storeLocal" + kind.getFrameName() + "Check");

        mStoreLocalCheck.addParameter(new CodeVariableElement(types.VirtualFrame, "frame"));
        mStoreLocalCheck.addParameter(new CodeVariableElement(context.getType(int.class), "localSlot"));
        mStoreLocalCheck.addParameter(new CodeVariableElement(context.getType(int.class), "stackSlot"));

        CodeTreeBuilder b = mStoreLocalCheck.createBuilder();

        b.declaration(types.FrameDescriptor, "descriptor", "frame.getFrameDescriptor()");

        b.startIf().string("descriptor.getSlotKind(localSlot) == ").staticReference(types.FrameSlotKind, kind.getFrameName()).end().startBlock();
        b.startTryBlock();
        b.statement("frame.set" + kind.getFrameName() + "(localSlot, expect" + kind.getFrameName() + "(frame, stackSlot))");
        b.returnTrue();
        b.end().startCatchBlock(types.UnexpectedResultException, "ex").end();
        b.end();

        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
        b.statement("descriptor.setSlotKind(localSlot, FrameSlotKind.Object)");
        b.statement("frame.setObject(localSlot, frame.getValue(stackSlot))");
        b.returnFalse();

        return mStoreLocalCheck;
    }

    private CodeExecutableElement createFormatConstant() {
        CodeExecutableElement mFormatConstant = new CodeExecutableElement(MOD_PROTECTED_STATIC, context.getType(String.class), "formatConstant");

        mFormatConstant.addParameter(new CodeVariableElement(context.getType(Object.class), "obj"));

        CodeTreeBuilder b = mFormatConstant.createBuilder();

        b.startIf().string("obj == null").end().startBlock();
        b.startReturn().doubleQuote("null").end();
        b.end().startElseBlock();
        b.declaration("Object", "repr", "obj");

        b.startIf().string("obj instanceof Object[]").end().startBlock();
        b.startAssign("repr").startStaticCall(context.getType(Arrays.class), "deepToString").string("(Object[]) obj").end(2);
        b.end();

        b.startReturn().startStaticCall(context.getType(String.class), "format");
        b.doubleQuote("%s %s");
        b.string("obj.getClass().getSimpleName()");
        b.string("repr");
        b.end(2);

        b.end();

        return mFormatConstant;
    }

    private CodeExecutableElement createSetResultBoxedImpl() {
        CodeExecutableElement mSetResultBoxedImpl = new CodeExecutableElement(MOD_PROTECTED_STATIC, context.getType(void.class), "setResultBoxedImpl");
        mSetResultBoxedImpl.addParameter(new CodeVariableElement(context.getType(short[].class), "bc"));
        mSetResultBoxedImpl.addParameter(new CodeVariableElement(context.getType(int.class), "bci"));
        mSetResultBoxedImpl.addParameter(new CodeVariableElement(context.getType(int.class), "targetType"));

        CodeTreeBuilder b = mSetResultBoxedImpl.createBuilder();

        int mask = (0xffff << OperationGeneratorFlags.BOXING_ELIM_BITS) & 0xffff;
        b.statement("bc[bci] = (short) (targetType | (bc[bci] & " + String.format("0x%x", mask) + "))");

        return mSetResultBoxedImpl;
    }

    // ---------------------- builder static code -----------------

    private CodeExecutableElement createBuilderImplCtor(CodeTypeElement opNodesImpl) {
        CodeExecutableElement ctor = new CodeExecutableElement(MOD_PRIVATE, null, OPERATION_BUILDER_IMPL_NAME);
        ctor.addParameter(new CodeVariableElement(opNodesImpl.asType(), "nodes"));
        ctor.addParameter(new CodeVariableElement(context.getType(boolean.class), "isReparse"));
        ctor.addParameter(new CodeVariableElement(types.OperationConfig, "config"));

        CodeTreeBuilder b = ctor.createBuilder();

        b.statement("this.nodes = nodes");
        b.statement("this.isReparse = isReparse");
        b.statement("builtNodes = new ArrayList<>()");

        b.startIf().string("isReparse").end().startBlock();
        b.statement("builtNodes.addAll((java.util.Collection) nodes.getNodes())");
        b.end();

        b.statement("this.withSource = config.isWithSource()");
        b.statement("this.withInstrumentation = config.isWithInstrumentation()");

        b.statement("this.sourceBuilder = withSource ? new SourceInfoBuilder(new ArrayList<>()) : null");

        return ctor;
    }

    private CodeExecutableElement createBuilderImplFinish() {
        CodeExecutableElement mFinish = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "finish");

        CodeTreeBuilder b = mFinish.createBuilder();

        b.startIf().string("withSource").end().startBlock();
        b.statement("nodes.setSources(sourceBuilder.buildSource())");
        b.end();

        b.startIf().string("!isReparse").end().startBlock();
        b.statement("nodes.setNodes(builtNodes.toArray(new " + m.getTemplateType().getSimpleName() + "[0]))");
        b.end();

        return mFinish;
    }

    private CodeExecutableElement createBuilderImplReset() {
        CodeExecutableElement mReset = new CodeExecutableElement(MOD_PRIVATE, context.getType(void.class), "reset");
        mReset.addParameter(new CodeVariableElement(types.TruffleLanguage, "language"));

        CodeTreeBuilder b = mReset.createBuilder();

        b.statement("bci = 0");
        b.statement("curStack = 0");
        b.statement("maxStack = 0");
        b.statement("numLocals = 0");
        b.statement("constPool.clear()");
        b.statement("operationData = new BuilderOperationData(null, OP_ROOT, 0, 0, false, language)");
        b.statement("labelFills.clear()");
        b.statement("numChildNodes = 0");
        b.statement("numConditionProfiles = 0");
        b.statement("exceptionHandlers.clear()");
        b.statement("Arrays.fill(instructionHistory, -1)");
        b.statement("instructionHistoryIndex = 0");

        b.startIf().string("sourceBuilder != null").end().startBlock();
        b.statement("sourceBuilder = new SourceInfoBuilder(sourceBuilder.sourceList)");
        b.end();

        if (m.enableYield) {
            b.statement("yieldCount = 0");
        }

        if (OperationGeneratorFlags.ENABLE_INSTRUMENTATION) {
            b.statement("instrumentList.clear()");
            b.statement("instrumentStack.clear()");
            b.statement("instrumentStack.add(new ArrayList<>())");
        }

        for (OperationMetadataData metadata : m.getMetadatas()) {
            b.startAssign("metadata_" + metadata.getName()).string("null").end();
        }

        return mReset;
    }

    private CodeExecutableElement createBuilderImplDoBeforeEmitInstruction() {
        CodeExecutableElement mDoBeforeEmitInstruction = new CodeExecutableElement(MOD_PRIVATE, context.getType(int[].class), "doBeforeEmitInstruction");
        mDoBeforeEmitInstruction.addParameter(new CodeVariableElement(context.getType(int.class), "numPops"));
        mDoBeforeEmitInstruction.addParameter(new CodeVariableElement(context.getType(boolean.class), "pushValue"));
        mDoBeforeEmitInstruction.addParameter(new CodeVariableElement(context.getType(boolean.class), "doBoxing"));

        CodeTreeBuilder b = mDoBeforeEmitInstruction.createBuilder();

        b.statement("int[] result = new int[numPops]");

        b.startFor().string("int i = numPops - 1; i >= 0; i--").end().startBlock();
        b.statement("curStack--");
        b.statement("int predBci = stackSourceBci[curStack]");
        b.statement("result[i] = predBci");
        b.end();

        b.startIf().string("pushValue").end().startBlock();

        b.startIf().string("curStack >= stackSourceBci.length").end().startBlock();
        b.statement("stackSourceBci = Arrays.copyOf(stackSourceBci, stackSourceBci.length * 2)");
        b.end();

        b.statement("stackSourceBci[curStack] = doBoxing ? bci : -65535");
        b.statement("curStack++");
        b.startIf().string("curStack > maxStack").end().startBlock();
        b.statement("maxStack = curStack");
        b.end();

        b.end();

        b.startReturn().string("result").end();

        return mDoBeforeEmitInstruction;
    }
}

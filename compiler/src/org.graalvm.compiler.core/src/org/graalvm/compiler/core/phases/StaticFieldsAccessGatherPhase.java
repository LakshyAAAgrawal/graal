package org.graalvm.compiler.core.phases;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

import jdk.vm.ci.meta.JavaMethod;
import jdk.vm.ci.meta.ResolvedJavaField;
import org.graalvm.compiler.nodes.GraphState;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.java.AccessFieldNode;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.nativeimage.ImageSingletons;

public class StaticFieldsAccessGatherPhase extends BasePhase<CoreProviders> {

    public static class StaticFieldsAccessRecords {
        public final IdentityHashMap<JavaMethod, Set<ResolvedJavaField>> methodToFields = new IdentityHashMap<>();
        public final IdentityHashMap<ResolvedJavaField, Set<JavaMethod>> fieldToMethods = new IdentityHashMap<>();
        public final Set<ResolvedJavaField> accessedFields = Collections.newSetFromMap(new IdentityHashMap<>());

        public void recordAccess(JavaMethod method, ResolvedJavaField field) {
            methodToFields.computeIfAbsent(method, f -> Collections.newSetFromMap(new IdentityHashMap<>())).add(field);
            fieldToMethods.computeIfAbsent(field, m -> Collections.newSetFromMap(new IdentityHashMap<>())).add(method);
            accessedFields.add(field);
        }
    }

    private final StaticFieldsAccessRecords accesses;

    public StaticFieldsAccessGatherPhase() {
        accesses = ImageSingletons.lookup(StaticFieldsAccessRecords.class);
    }

    @Override
    public Optional<NotApplicable> canApply(GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    @Override
    protected void run(StructuredGraph graph, CoreProviders context) {
        for (AccessFieldNode node : graph.getNodes(AccessFieldNode.TYPE)) {
            ResolvedJavaField field = node.getField();
            if (field.isStatic()) {
                accesses.recordAccess(graph.asJavaMethod(), node.getField());
            }
        }
    }
}

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

public class NativeImageHeapGraphAccessPhase extends BasePhase<CoreProviders> {

    public static class NativeImageHeapAccessRecords {
        public final IdentityHashMap<JavaMethod, Set<ResolvedJavaField>> fieldAccesses
                = new IdentityHashMap<>();
        public void recordAccess(JavaMethod method, ResolvedJavaField field) {
            fieldAccesses.computeIfAbsent(method, f -> Collections.newSetFromMap(new IdentityHashMap<>())).add(field);
        }
    }

    NativeImageHeapAccessRecords accesses;

    public NativeImageHeapGraphAccessPhase() {
        accesses = ImageSingletons.lookup(NativeImageHeapAccessRecords.class);
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

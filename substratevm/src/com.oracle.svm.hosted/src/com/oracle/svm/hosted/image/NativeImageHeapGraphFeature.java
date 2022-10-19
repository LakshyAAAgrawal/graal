package com.oracle.svm.hosted.image;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl;
import org.graalvm.compiler.core.phases.NativeImageHeapGraphAccessPhase;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.nativeimage.ImageSingletons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@AutomaticallyRegisteredFeature
public class NativeImageHeapGraphFeature implements InternalFeature {

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        testGraph();
        testConnectedComponents();
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        ImageSingletons.add(NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords.class,
                        new NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords());
    }

    @Override
    public void afterHeapLayout(AfterHeapLayoutAccess a) {
        FeatureImpl.AfterHeapLayoutAccessImpl access = (FeatureImpl.AfterHeapLayoutAccessImpl) a;
        NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords accessRecords = ImageSingletons.lookup(NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords.class);
        NativeImageHeapGraph graph = new NativeImageHeapGraph(accessRecords, access.getHeap());
    }

    private void testGraph() {
        System.out.println("NativeImageHeapGraphFeature.testGraph");
        DirectedGraph<Integer> graph = new DirectedGraph<>();
        Integer a = 0;
        Integer b = 1;
        Integer c = 2;
        Integer d = 3;
        graph.connect(a, b);
        graph.connect(a, c);
        graph.connect(b, d);
        graph.connect(c, d);

        VMError.guarantee(graph.isRoot(a));
        VMError.guarantee(!graph.isRoot(b));
        VMError.guarantee(!graph.isRoot(c));
        VMError.guarantee(!graph.isRoot(d));
    }

    private void testConnectedComponents() {
        System.out.println("NativeImageHeapGraphFeature.testConnectedComponents");
        DirectedGraph<Integer> graph = new DirectedGraph<>();
        Integer a = 0;
        Integer b = 1;
        Integer c = 2;
        Integer d = 3;
        graph.connect(a, b);
        graph.connect(a, c);
        graph.connect(b, d);
        graph.connect(c, d);

        Integer e = 4;
        Integer f = 5;
        Integer g = 6;
        graph.connect(e, f);
        graph.addNode(g);
        List<ArrayList<Integer>> collections = graph.getRoots().stream()
                        .map(root -> DirectedGraph.DFSVisitor.create(graph).dfs(root))
                        .collect(Collectors.toList());
        VMError.guarantee(collections.size() == 3);
    }
}

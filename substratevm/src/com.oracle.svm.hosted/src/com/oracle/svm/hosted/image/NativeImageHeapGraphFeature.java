package com.oracle.svm.hosted.image;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl;
import org.graalvm.compiler.core.phases.StaticFieldsAccessGatherPhase;
import org.graalvm.nativeimage.ImageSingletons;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@AutomaticallyRegisteredFeature
public class NativeImageHeapGraphFeature implements InternalFeature {
    private NativeImageHeap heap;
    private StaticFieldsAccessGatherPhase.StaticFieldsAccessRecords accessRecords;

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        testGraph();
        testConnectedComponents();
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        ImageSingletons.add(StaticFieldsAccessGatherPhase.StaticFieldsAccessRecords.class,
                        new StaticFieldsAccessGatherPhase.StaticFieldsAccessRecords());
    }

    @Override
    public void afterHeapLayout(AfterHeapLayoutAccess a) {
        FeatureImpl.AfterHeapLayoutAccessImpl access = (FeatureImpl.AfterHeapLayoutAccessImpl) a;
        this.accessRecords = ImageSingletons.lookup(StaticFieldsAccessGatherPhase.StaticFieldsAccessRecords.class);
        this.heap = access.getHeap();
    }

    @Override
    public void afterImageWrite(AfterImageWriteAccess a) {
        NativeImageHeapGraph graph = new NativeImageHeapGraph(accessRecords, heap);
        printImageHeapPartitionsStatistics(System.out);
        graph.printStatistics(System.out, 32);
    }

    private void printImageHeapPartitionsStatistics(PrintStream out) {
        out.println("======== ImageHeap Partitions ==========");
        out.println("Using ImageHeapLayouter: " + heap.getLayouter().getClass());
        Arrays.stream(heap.getLayouter().getPartitions())
                .sorted((p1,p2) -> Long.compare(p2.getSize(), p1.getSize()))
                .map(p -> String.format("%d-%s", p.getSize(), p.getName()))
                .forEach(out::println);
    }

    private static void testGraph() {
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

    private static void testConnectedComponents() {
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

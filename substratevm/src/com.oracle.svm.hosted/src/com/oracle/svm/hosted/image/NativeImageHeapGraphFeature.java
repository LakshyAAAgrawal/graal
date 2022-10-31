package com.oracle.svm.hosted.image;

import com.oracle.graal.pointsto.reports.ReportUtils;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl;
import org.graalvm.compiler.core.phases.StaticFieldsAccessGatherPhase;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.nativeimage.ImageSingletons;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@AutomaticallyRegisteredFeature
public class NativeImageHeapGraphFeature implements InternalFeature {
    public static class Options {
        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<Integer> NativeImageHeapGraphNumOfComponents = new HostedOptionKey<>(16);

        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<String> NativeImageHeapGraphReasonFilterOut = new HostedOptionKey<>("");

        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<Float> NativeImageHeapGraphComponentMBSizeThreshold = new HostedOptionKey<>(0.0f);

        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<String> NativeImageHeapGraphObjectFilterOut = new HostedOptionKey<>("");
    }

    private AbstractImage image;
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
    public void beforeImageWrite(BeforeImageWriteAccess access) {
        this.image = ((FeatureImpl.BeforeImageWriteAccessImpl)access).getImage();
    }

    @Override
    public void afterImageWrite(AfterImageWriteAccess a) {
        FeatureImpl.AfterImageWriteAccessImpl access = (FeatureImpl.AfterImageWriteAccessImpl) a;
        NativeImageHeapGraph graph = new NativeImageHeapGraph(accessRecords, heap, this.image.getImageHeapSize());
        Path path = Path.of(SubstrateOptions.reportsPath(), "image_heap_connected_components_" + access.getImagePath().getFileName().toString() + ".txt");
        ReportUtils.report("Native image heap object graph info", path, graph::printComponentsReport);

        path = Path.of(SubstrateOptions.reportsPath(), "image_heap_connected_components_in_partitions_" + access.getImagePath().getFileName().toString() + ".txt");
        ReportUtils.report("Native image heap object distribution between partitions", path, graph::printComponentsImagePartitionHistogramReport);
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

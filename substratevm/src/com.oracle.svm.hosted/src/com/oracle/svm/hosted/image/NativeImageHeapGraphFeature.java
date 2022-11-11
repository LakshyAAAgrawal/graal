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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;

@AutomaticallyRegisteredFeature
public class NativeImageHeapGraphFeature implements InternalFeature {
    public static class Options {

        // TODO(mspasic): change to false before committing the final version
        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<Boolean> DumpNativeImageHeapReport = new HostedOptionKey<>(true);

        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<Integer> NativeImageHeapGraphNumOfComponents = new HostedOptionKey<>(16);

        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<String> NativeImageHeapGraphRootFilter = new HostedOptionKey<>("");

        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<Float> NativeImageHeapGraphComponentMBSizeThreshold = new HostedOptionKey<>(0.0f);
    }

    private AbstractImage image;
    private NativeImageHeap heap;

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return Options.DumpNativeImageHeapReport.getValue();
    }

    @Override
    public void afterHeapLayout(AfterHeapLayoutAccess a) {
        FeatureImpl.AfterHeapLayoutAccessImpl access = (FeatureImpl.AfterHeapLayoutAccessImpl) a;
        this.heap = access.getHeap();
    }

    @Override
    public void beforeImageWrite(BeforeImageWriteAccess access) {
        this.image = ((FeatureImpl.BeforeImageWriteAccessImpl)access).getImage();
    }

    @Override
    public void afterImageWrite(AfterImageWriteAccess a) {
        FeatureImpl.AfterImageWriteAccessImpl access = (FeatureImpl.AfterImageWriteAccessImpl) a;
        NativeImageHeapGraph graph = new NativeImageHeapGraph(heap, this.image.getImageHeapSize());
        {
            String reportName = "image_heap_connected_components_" + access.getImagePath().getFileName().toString() + ".txt";
            Path path = Path.of(SubstrateOptions.reportsPath(), reportName);
            ReportUtils.report(reportName, path, graph::printComponentsReport);
        }
        {
            String reportName = "image_heap_roots_" + access.getImagePath().getFileName().toString() + ".txt";
            Path path = Path.of(SubstrateOptions.reportsPath(), reportName);
            ReportUtils.report(reportName, path, graph::printRoots);
        }

        {
            String reportName = "image_heap_objects_" + access.getImagePath().getFileName().toString() + ".txt";
            Path path = Path.of(SubstrateOptions.reportsPath(), reportName);
            ReportUtils.report(reportName, path, graph::printObjectsReport);
        }
        {
            String reportName = "image_heap_connected_component_graph_" + access.getImagePath().getFileName().toString();
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "dot");
            ReportUtils.report(reportName, file.toPath(), graph::printConnectedComponentToGraphVizDotFormat);
        }
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

        NativeImageHeapGraph.CollectNLevels<Integer> objectCollectNLevels = new NativeImageHeapGraph.CollectNLevels<>(2);
        graph.bfs(a, objectCollectNLevels);
    }
}

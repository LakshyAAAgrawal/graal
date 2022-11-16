package com.oracle.svm.hosted.image;

import com.oracle.graal.pointsto.reports.ReportUtils;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionType;

import java.io.File;

@AutomaticallyRegisteredFeature
public class NativeImageHeapGraphFeature implements InternalFeature {
    public static class Options {

        // TODO(mspasic): change to false before committing the final version
        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<Boolean> DumpNativeImageHeapReport = new HostedOptionKey<>(true);

        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<Integer> NativeImageHeapGraphNumOfComponents = new HostedOptionKey<>(0);

        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<String> NativeImageHeapGraphRootFilter = new HostedOptionKey<>("");

        @Option(help = {}, type = OptionType.Debug)
        public static final HostedOptionKey<String> ImageHeapObjectTypeFilter = new HostedOptionKey<>("");

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
        testConnectedComponents();;
        testGraph();
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
            String reportName = "image_heap_connected_components_" + access.getImagePath().getFileName().toString();
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::printConnectedComponentsHistogramsAndEntryPoints);
        }
        {
            String reportName = "image_objects_report_" + access.getImagePath().getFileName().toString();
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::printObjectsReport);
        }

        {
            String reportName = "objects_dump_report_" + access.getImagePath().getFileName().toString();
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::dumpImageHeap);
        }

//        {
//            String reportName = "image_objects_reference_chain_strings_" + access.getImagePath().getFileName().toString();
//            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
//            ReportUtils.report(reportName, file.toPath(), graph::printReferenceChainStringReport);
//        }

        {
            String reportName = "image_heap_entry_points_" + access.getImagePath().getFileName().toString();
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::printEntryPointsReport);
        }

//        {
//            String reportName = "image_heap_reference_graph_" + access.getImagePath().getFileName().toString();
//            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "dot");
//            ReportUtils.report(reportName, file.toPath(), graph::printReferenceChainGraph);
//        }
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

    }
}

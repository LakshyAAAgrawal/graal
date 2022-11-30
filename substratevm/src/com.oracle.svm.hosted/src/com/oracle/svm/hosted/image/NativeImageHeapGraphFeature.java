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
        @Option(help = {}, type = OptionType.Debug) public static final HostedOptionKey<Boolean> NativeImageHeapReports = new HostedOptionKey<>(true);

        @Option(help = {}, type = OptionType.Debug) public static final HostedOptionKey<String> NativeImageHeapGraphRootFilter = new HostedOptionKey<>("");
    }

    private AbstractImage image;
    private NativeImageHeap heap;

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return Options.NativeImageHeapReports.getValue();
    }

    @Override
    public void afterHeapLayout(AfterHeapLayoutAccess a) {
        FeatureImpl.AfterHeapLayoutAccessImpl access = (FeatureImpl.AfterHeapLayoutAccessImpl) a;
        this.heap = access.getHeap();
    }

    @Override
    public void beforeImageWrite(BeforeImageWriteAccess access) {
        this.image = ((FeatureImpl.BeforeImageWriteAccessImpl) access).getImage();
    }

    @Override
    public void afterImageWrite(AfterImageWriteAccess a) {
        FeatureImpl.AfterImageWriteAccessImpl access = (FeatureImpl.AfterImageWriteAccessImpl) a;
        String imageName = access.getImagePath().getFileName().toString();
        NativeImageHeapGraph graph = new NativeImageHeapGraph(heap, access.getUniverse().getBigBang(), image, imageName);
        System.out.println("Writing reports...");
        long start = System.currentTimeMillis();
        {
            String reportName = "connected_components_histograms_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::printConnectedComponentsHistogramsAndEntryPoints);
        }
        {
            String reportName = "references_for_objects_image_heap_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::printObjectsAndReferencesForEachComponent);
        }
        {
            String reportName = "objects_in_components_image_heap_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::printObjectsForEachComponent);
        }
        {
            String reportName = "all_objects_image_heap_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::printAllImageHeapObjects);
        }
        {
            String reportName = "entry_points_image_heap_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::printMainEntryPointsReport);
        }
        {
            String reportName = "partitions_image_heap_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::printImagePartitionsReport);
        }
        {
            String reportName = "connected_component_sizes_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::dumpConnectedComponentSizes);
        }
        {
            String reportName = "dynamic_hubs_objects_dump_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::dumpDynamicHubObjects);
        }
        {
            String reportName = "image_code_info_objects_dump_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::dumpImageCodeInfoObjects);
        }
        {
            String reportName = "interned_strings_table_objects_dump_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::dumpInternedStringsTableObjects);
        }
        {
            String reportName = "interned_strings_table_values_dump_" + imageName;
            File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
            ReportUtils.report(reportName, file.toPath(), graph::dumpInternedStringsValues);
        }

        long end = System.currentTimeMillis();
        System.out.printf("Reports written in: %fs\n", (end - start) / 1000.0f);
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
        UndirectedGraph<Integer> graph = new UndirectedGraph<>();
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
        graph.connect(g, g);
    }
}

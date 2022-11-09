package com.oracle.svm.hosted.image;

import com.oracle.graal.pointsto.reports.ReportUtils;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.FeatureImpl;
import org.graalvm.compiler.core.phases.StaticFieldsAccessGatherPhase;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.nativeimage.ImageSingletons;

import java.nio.file.Path;

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

        Path ccReport = Path.of(SubstrateOptions.reportsPath(), "image_heap_connected_components_" + access.getImagePath().getFileName().toString() + ".txt");
        ReportUtils.report("Native image heap object graph info", ccReport, graph::printComponentsReport);

//        Path partitionReport = Path.of(SubstrateOptions.reportsPath(), "image_heap_connected_components_in_partitions_" + access.getImagePath().getFileName().toString() + ".txt");
//        ReportUtils.report("Native image heap object distribution between partitions", partitionReport, graph::printComponentsImagePartitionHistogramReport);

//        Path objectsReport = Path.of(SubstrateOptions.reportsPath(), "image_heap_objects_" + access.getImagePath().getFileName().toString() + ".txt");
//        NativeImageHeapGraphPrinter printer = new NativeImageHeapGraphPrinter(graph);
//        NativeImageHeapGraphPrinter.print(graph,SubstrateOptions.reportsPath(), "image_heap_tree" + access.getImagePath().getFileName().toString());
    }
}

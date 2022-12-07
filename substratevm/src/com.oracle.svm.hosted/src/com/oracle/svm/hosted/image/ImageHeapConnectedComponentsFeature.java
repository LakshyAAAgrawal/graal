package com.oracle.svm.hosted.image;

import com.oracle.graal.pointsto.reports.ReportUtils;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.FeatureImpl;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionType;

import java.io.File;
import java.io.PrintWriter;
import java.util.function.Consumer;

@AutomaticallyRegisteredFeature
public class ImageHeapConnectedComponentsFeature implements InternalFeature {
    public static class Options {
        @Option(help = {"Prints report about objects in the Image heap grouped into connected components by their references to other objects in Image heap"}, type = OptionType.Debug) public static final HostedOptionKey<Boolean> PrintImageHeapConnectedComponents = new HostedOptionKey<>(
                        false);
    }

    private AbstractImage image;
    private NativeImageHeap heap;

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return Options.PrintImageHeapConnectedComponents.getValue();
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
        ImageHeapConnectedComponentsPrinter printer = new ImageHeapConnectedComponentsPrinter(heap, access.getUniverse().getBigBang(), image, imageName);
        printReport("connected_components_" + imageName, printer::printConnectedComponents);
        printReport("objects_in_components_" + imageName, printer::printObjectsForEachComponent);
        printReport("access_points_for_connected_components_" + imageName, printer::printAccessPoints);
    }

    private static void printReport(String reportName, Consumer<PrintWriter> writer) {
        File file = ReportUtils.reportFile(SubstrateOptions.reportsPath(), reportName, "txt");
        ReportUtils.report(reportName, file.toPath(), writer);
    }
}
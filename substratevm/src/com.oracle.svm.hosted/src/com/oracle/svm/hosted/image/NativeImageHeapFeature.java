package com.oracle.svm.hosted.image;

import com.oracle.graal.pointsto.reports.ReportUtils;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;

import java.io.PrintWriter;
import java.nio.file.Path;

@AutomaticallyRegisteredFeature
public class NativeImageHeapFeature implements InternalFeature {

}

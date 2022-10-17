package com.oracle.svm.hosted.image;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.hosted.FeatureImpl;
import org.graalvm.compiler.core.phases.NativeImageHeapGraphAccessPhase;
import org.graalvm.nativeimage.ImageSingletons;

@AutomaticallyRegisteredFeature
public class NativeImageHeapGraphFeature implements InternalFeature {

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        ImageSingletons.add(NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords.class,
                new NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords());
    }

    @Override
    public void afterHeapLayout(AfterHeapLayoutAccess a) {
        FeatureImpl.AfterHeapLayoutAccessImpl access = (FeatureImpl.AfterHeapLayoutAccessImpl)a;
        NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords accessRecords
                = ImageSingletons.lookup(NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords.class);
        NativeImageHeapGraph graph = new NativeImageHeapGraph(accessRecords, access.getHeap());

    }
}

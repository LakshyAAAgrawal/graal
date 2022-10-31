package com.oracle.svm.hosted.image;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.core.phases.StaticFieldsAccessGatherPhase;

import com.oracle.svm.core.image.ImageHeapPartition;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;

public class NativeImageHeapGraph {
    private final StaticFieldsAccessGatherPhase.StaticFieldsAccessRecords accessRecords;
    private final Set<HostedField> hostedFieldsReasons;
    private final Set<String> stringReasons;
    private final NativeImageHeap heap;
    private final long totalHeapSizeInBytes;
    private final List<ConnectedComponentInfo> connectedComponents;

    public NativeImageHeapGraph(StaticFieldsAccessGatherPhase.StaticFieldsAccessRecords accessRecords, NativeImageHeap heap, long imageHeapSize) {
        this.heap = heap;
        this.accessRecords = accessRecords;
        this.totalHeapSizeInBytes = imageHeapSize;
        this.hostedFieldsReasons = Collections.newSetFromMap(new IdentityHashMap<>());
        this.stringReasons = Collections.newSetFromMap(new IdentityHashMap<>());
        DirectedGraph<ObjectInfo> graph = computeImageHeapObjectGraph(this.heap);
        this.connectedComponents = computeConnectedComponents(graph, this.heap);
    }

    private DirectedGraph<ObjectInfo> computeImageHeapObjectGraph(NativeImageHeap heap) {
        DirectedGraph<ObjectInfo> graph = new DirectedGraph<>();
        for (ObjectInfo objectInfo : heap.getObjects()) {
            connectChildToParentObjects(graph, objectInfo);
        }
        return graph;
    }

    private void connectChildToParentObjects(DirectedGraph<ObjectInfo> graph, ObjectInfo childObjectInfo) {
        for (Object reason : childObjectInfo.getAllReasons()) {
            if (reason instanceof ObjectInfo) {
                ObjectInfo parent = (ObjectInfo) reason;
                graph.connect(parent, childObjectInfo);
            } else if (reason instanceof String) {
                this.stringReasons.add((String) reason);
            } else if (reason instanceof HostedField) {
                this.hostedFieldsReasons.add((HostedField) reason);
            }
        }
    }

    private static List<ConnectedComponentInfo> computeConnectedComponents(DirectedGraph<ObjectInfo> graph, NativeImageHeap heap) {
        List<String> reasonFilterPatterns = Arrays.stream(
                NativeImageHeapGraphFeature.Options.NativeImageHeapGraphReasonFilterOut.getValue().split(","))
                .map(String::strip)
                .collect(Collectors.toList());

        List<String> objectFilterPatterns = Arrays.stream(
                NativeImageHeapGraphFeature.Options.NativeImageHeapGraphObjectFilterOut
                        .getValue()
                        .split(",")
                )
                .map(String::strip)
                .collect(Collectors.toList());
        // Compute connected components by running dfs visit from all the root nodes
        int limitNumOfComponents = NativeImageHeapGraphFeature.Options.NativeImageHeapGraphNumOfComponents.getValue();
        limitNumOfComponents = limitNumOfComponents > 0 ? Math.min(limitNumOfComponents, graph.getRoots().size()) : graph.getRoots().size();
        return graph.getRoots().stream()
                .parallel()
                .map(root -> DirectedGraph.DFSVisitor.create(graph).dfs(root))
                .map(root -> new ConnectedComponentInfo(root, heap))
                .filter(c -> c.getSizeInMB() >= NativeImageHeapGraphFeature.Options.NativeImageHeapGraphComponentMBSizeThreshold.getValue())
                .filter(c -> c.shouldReportThisComponent(objectFilterPatterns, reasonFilterPatterns))
                .sorted(Comparator.comparing(ConnectedComponentInfo::getSizeInBytes).reversed())
                .limit(limitNumOfComponents)
                .collect(Collectors.toList());
    }

    public void printComponentsReport(PrintWriter out) {
        out.println("============Native Image Heap Object Graph Stats============");
        out.printf("Total Heap Size: %.3fMB\n", MB(this.totalHeapSizeInBytes));
        out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());
        out.println();
        out.println("===========Connected components in NativeImageHeap===========");
        out.println("ObjectName | SizeMB | FractionOfTotalHeapSize | IdentityHashCode");
        for (ConnectedComponentInfo connectedComponent : connectedComponents) {
            float sizeInMb = connectedComponent.getSizeInMB();
            ObjectInfo rootObject = connectedComponent.getRoot();
            float componentFractionSizeOfTotalHeap = (float) connectedComponent.getSizeInBytes() / this.totalHeapSizeInBytes;
            out.printf("%s | %.4fMB | %.4f | %d\n", rootObject.getObjectClass().getName(),
                    sizeInMb, componentFractionSizeOfTotalHeap, rootObject.getIdentityHashCode());
            for (Object reason : rootObject.getAllReasons()) {
                if (reason instanceof String) {
                    out.printf("|--%s\n", reason);
                }
            }
            out.println();
        }
    }

    public void printComponentsImagePartitionHistogramReport(PrintWriter out) {
        out.println("==========ImagePartitionStatistics per component=============");
        out.println("CSp - Component size in Partition");
        out.println("PS - Partition size");
        out.println("F - Total space taken by component inside a partition");
        for (ConnectedComponentInfo connectedComponentInfo : connectedComponents) {
            out.printf("\n=========Object: %s - | %fMB | IdentityHashCode: %d=========\n",
                    connectedComponentInfo.getRoot().getObjectClass(),
                    connectedComponentInfo.getSizeInMB(),
                    connectedComponentInfo.getRoot().getIdentityHashCode());
            out.printf("%-20s %-26s\n", "Partition", "CSp/PS=F");
            for (Pair<ImageHeapPartition, Long> partitionInfo : connectedComponentInfo.getHistogram()) {
                ImageHeapPartition partition = partitionInfo.getLeft();
                long componentSizeInPartition = partitionInfo.getRight();
                out.printf("%-20s %.4fMB/%.4fMB=%.4f\n",
                        partition,
                        MB(componentSizeInPartition),
                        MB(partition.getSize()),
                        (double) componentSizeInPartition / partition.getSize());
            }
        }
    }
    private static float MB(long bytes) {
        return bytes / (1048576f);
    }

    private static class ConnectedComponentInfo {
        private final List<ObjectInfo> objects;
        private final long size;
        private final List<ImageHeapPartition> partitions;
        private final long[] componentSizeInPartition;

        public ConnectedComponentInfo(List<ObjectInfo> objects, NativeImageHeap heap) {
            this.objects = objects;
            this.size = computeComponentSize(objects);
            this.partitions = Arrays.asList(heap.getLayouter().getPartitions());
            this.componentSizeInPartition = new long[partitions.size()];
            for (ObjectInfo object : objects) {
                ImageHeapPartition partition = object.getPartition();
                int index = this.partitions.indexOf(partition);
                componentSizeInPartition[index] += object.getSize();
            }
        }

        private static long computeComponentSize(List<ObjectInfo> objects) {
            long totalSize = 0L;
            for (ObjectInfo o : objects) {
                totalSize += o.getSize();
            }
            return totalSize;
        }

        public long getSizeInBytes() {
            return size;
        }

        public float getSizeInMB() {
            return size / (1024.f * 1024.f);
        }

        public List<ObjectInfo> getObjects() {
            return objects;
        }

        public ObjectInfo getRoot() {
            return objects.get(0);
        }

        public boolean shouldReportThisComponent(List<String> objectFilterOutPatterns,
                                                 List<String> reasonFilterOutPatterns) {
            for (String filterOutPattern : objectFilterOutPatterns) {
                boolean shouldExcludeThisComponentFromReport =
                        getRoot().toString().contains(filterOutPattern);
                if (shouldExcludeThisComponentFromReport) {
                    return false;
                }
            }
            for (String filterOutPattern : reasonFilterOutPatterns) {
                boolean shouldExcludeThisComponentFromReport = getRoot().getAllReasons()
                        .stream()
                        .filter(r -> r instanceof String)
                        .map(Object::toString)
                        .anyMatch(r -> r.contains(filterOutPattern));
                if (shouldExcludeThisComponentFromReport) {
                    return false;
                }
            }
            return true;
        }
        public List<Pair<ImageHeapPartition, Long>> getHistogram() {
            return IntStream.range(0, partitions.size())
                    .mapToObj(i -> Pair.create(partitions.get(i), componentSizeInPartition[i]))
                    .collect(Collectors.toList());
        }
    }
}

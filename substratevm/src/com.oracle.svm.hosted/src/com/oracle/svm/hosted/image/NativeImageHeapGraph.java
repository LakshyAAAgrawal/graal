package com.oracle.svm.hosted.image;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.core.phases.StaticFieldsAccessGatherPhase;

import com.oracle.svm.core.image.ImageHeapPartition;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;

public class NativeImageHeapGraph {
    private final NativeImageHeap heap;
    private final long totalHeapSizeInBytes;
    private final List<ConnectedComponentInfo> connectedComponents;

    public NativeImageHeapGraph(StaticFieldsAccessGatherPhase.StaticFieldsAccessRecords accessRecords, NativeImageHeap heap, long imageHeapSize) {
        System.out.println("Constructing Native Image Heap Graph");
        long start = System.currentTimeMillis();
        this.heap = heap;
        this.totalHeapSizeInBytes = imageHeapSize;
        DirectedGraph<ObjectInfo> graph = computeImageHeapObjectGraph(this.heap);
        this.connectedComponents = computeConnectedComponents(graph, this.heap);
        long end = System.currentTimeMillis();
        System.out.printf("Elapsed seconds: %.4f", (end - start)/1000.0f);
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
            } else if (reason instanceof HostedField) {
            } else {
                VMError.shouldNotReachHere(String.format("ObjectInfo %s root not handled.",
                        reason.getClass().getSimpleName()));
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
//                .filter(c -> c.getSizeInMB() >= NativeImageHeapGraphFeature.Options.NativeImageHeapGraphComponentMBSizeThreshold.getValue())
//                .filter(c -> c.shouldReportThisComponent(objectFilterPatterns, reasonFilterPatterns))
                .sorted(Comparator.comparing(ConnectedComponentInfo::getSizeInBytes).reversed())
                .limit(limitNumOfComponents)
                .collect(Collectors.toList());
    }

    private static int getReasonRank(Object reason) {
        if (reason instanceof String) return 0;
        if (reason instanceof HostedField) return 1;
        if (reason instanceof ObjectInfo) return 2;

        VMError.shouldNotReachHere(String.format("Root %s not handled.", reason.getClass().getSimpleName()));
        return 0;
    }

    private static final Comparator<Object> REASON_COMPARATOR = (o1, o2) -> {
        if (o1 instanceof String && o2 instanceof String) {
            return String.CASE_INSENSITIVE_ORDER.compare((String) o1, (String) o2);
        }
        if (o1 instanceof HostedField && o2 instanceof HostedField) {
            HostedField h1 = (HostedField) o1;
            HostedField h2 = (HostedField) o2;
            return h1.getDeclaringClass().getName().compareTo(h2.getDeclaringClass().getName());
        }
        return getReasonRank(o1) - getReasonRank(o2);
    };

    private static long sumObjectSizes(List<ObjectInfo> objectInfos) {
        long sum = 0;
        for (ObjectInfo objectInfo : objectInfos) {
            sum += objectInfo.getSize();
        }
        return sum;
    }

    public void printComponentsReport(PrintWriter out) {
        out.println("============Native Image Heap Object Graph Report============");
        out.printf("Total Heap Size: %.3fMB\n", MB(this.totalHeapSizeInBytes));
        out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());
        out.printf("Total number of connected components in the heap: %d\n", this.connectedComponents.size());
        out.println();
        out.println("===========Connected components in the Native Image Heap===========");

        HashSet<String> uniqueReasons = new HashSet<>();
        for (int i = 0; i < connectedComponents.size(); i++) {
            int componentId = i + 1;
            ConnectedComponentInfo connectedComponent = connectedComponents.get(i);
            float sizeInMb = connectedComponent.getSizeInMB();
            float percentageOfTotalHeapSize = 100.0f * (float) connectedComponent.getSizeInBytes() / this.totalHeapSizeInBytes;
            out.printf("Component=%d | Size=%.4fMB | Percentage of total image heap size=%.4f%%\n", componentId, sizeInMb, percentageOfTotalHeapSize);

            out.printf("%12s %s\n", "Size", "Method");
           connectedComponent.getMethodAccessPoints()
                    .values()
                    .stream()
                    .sorted(Comparator.comparing((EntryPoint<String> e) -> e.getSizeInBytes()).reversed())
                    .forEach(o -> out.printf("%12d %s\n", o.getSizeInBytes(), o.getRoot()));

//           connectedComponent.getReasons().stream()
//                    .sorted(REASON_COMPARATOR)
//                    .map(this::formatReason)
//                    .forEach(out::println);

            HeapHistogram objectHistogram = new HeapHistogram(out);
            connectedComponent.getObjects().forEach(o -> objectHistogram.add(o, o.getSize()));
            objectHistogram.printHeadings("");
            objectHistogram.print();
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

    public void printObjectReferencesReport(PrintWriter out) {
        for (ObjectInfo info : heap.getObjects()) {
            out.printf("%s | %d\n", info.getObjectClass().getName(), info.getIdentityHashCode());
            for (Object reason : info.getAllReasons()) {
                out.printf("|--%s\n", formatReason(reason));
            }
        }
    }

    public String formatReason(Object reason) {
        if (reason instanceof String) {
            return reason.toString();
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("%s | %d", r.getObjectClass().getName(), r.getIdentityHashCode());
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return String.format("%s", r.getDeclaringClass().getName());
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return "Unhandled type in: NativeImageHeapGraph.formatReason([root]):179";
        }
    }

    private static float MB(long bytes) {
        return bytes / (1048576f);
    }

    private static class EntryPoint<Root> implements Comparable<EntryPoint<Root>> {
        private final Root root;
        private long sizeInBytes;

        public EntryPoint(Root root) {
            this.root = root;
        }

        public void addObject(ObjectInfo objectInfo) {
            this.sizeInBytes += objectInfo.getSize();
        }
        public Root getRoot() { return root; }
        public long getSizeInBytes() { return sizeInBytes; }

        @Override
        public int compareTo(EntryPoint<Root> o) {
            return Long.compare(this.sizeInBytes, o.sizeInBytes);
        }
    }

    private static class ConnectedComponentInfo {
        private final List<ObjectInfo> objects;
        private final long size;
        private final List<ImageHeapPartition> partitions;
        private final long[] componentSizeInPartition;
        private final IdentityHashMap<HostedField, EntryPoint<HostedField>> hostedFieldEntryPoints;
        private final IdentityHashMap<String, EntryPoint<String>> methodEntryPoints;
        private final Set<Object> reasons;

        public ConnectedComponentInfo(List<ObjectInfo> objects, NativeImageHeap heap) {
            this.objects = objects;
            this.size = computeComponentSize(objects);
            this.partitions = Arrays.asList(heap.getLayouter().getPartitions());
            this.componentSizeInPartition = new long[partitions.size()];
            this.hostedFieldEntryPoints = new IdentityHashMap<>();
            this.methodEntryPoints = new IdentityHashMap<>();
            this.reasons = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ObjectInfo object : objects) {
                this.reasons.addAll(object.getAllReasons());
                ImageHeapPartition partition = object.getPartition();
                int index = this.partitions.indexOf(partition);
                componentSizeInPartition[index] += object.getSize();
                for (Object reason : object.getAllReasons()) {
                    if (reason instanceof HostedField) {
                        HostedField field = (HostedField) reason;
                        this.hostedFieldEntryPoints.computeIfAbsent(field, EntryPoint::new).addObject(object);
                    } else if (reason instanceof String) {
                        this.methodEntryPoints.computeIfAbsent((String) reason, EntryPoint::new).addObject(object);
                    }
                }
            }
        }
        public Set<Object> getReasons() { return reasons; }

        public Map<HostedField, EntryPoint<HostedField>> getHostedFieldEntryPoints() {
            return hostedFieldEntryPoints;
        }

        public Map<String, EntryPoint<String>> getMethodAccessPoints() {
            return methodEntryPoints;
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

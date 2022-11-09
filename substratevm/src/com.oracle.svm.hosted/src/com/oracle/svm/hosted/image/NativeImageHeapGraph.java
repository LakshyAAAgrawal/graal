package com.oracle.svm.hosted.image;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.graalvm.collections.Pair;

import com.oracle.svm.core.image.ImageHeapPartition;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;

public class NativeImageHeapGraph {
    private final NativeImageHeap heap;
    private final long totalHeapSizeInBytes;
    private final List<ConnectedComponent> connectedComponents;
    private final DirectedGraph<ObjectInfo> objectGraph;

    public DirectedGraph<ObjectInfo> getObjectGraph() {
        return objectGraph;
    }

    public NativeImageHeapGraph(NativeImageHeap heap, long imageHeapSize) {
        System.out.print("Constructing Native Image Heap Graph: ... ");
        long start = System.currentTimeMillis();
        this.heap = heap;
        this.totalHeapSizeInBytes = imageHeapSize;
        this.objectGraph = computeImageHeapObjectGraph(this.heap);
        this.connectedComponents = computeConnectedComponents(objectGraph, this.heap);
        long end = System.currentTimeMillis();
        System.out.printf("Elapsed seconds: %.4f\n", (end - start) / 1000.0f);
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

    private static List<ConnectedComponent> computeConnectedComponents(DirectedGraph<ObjectInfo> graph, NativeImageHeap heap) {
        List<String> rootFilterPatterns = Arrays.stream(
                        NativeImageHeapGraphFeature.Options.NativeImageHeapGraphRootFilter.getValue().split(","))
                        .map(String::strip)
                        .collect(Collectors.toList());

        int limitNumOfComponents = NativeImageHeapGraphFeature.Options.NativeImageHeapGraphNumOfComponents.getValue();
        limitNumOfComponents = limitNumOfComponents > 0 ? Math.min(limitNumOfComponents, graph.getRoots().size()) : graph.getRoots().size();

        // Compute connected components by running dfs visit from all the root nodes
        return graph.getRoots().stream()
                        .parallel()
                        .map(root -> graph.dfs(root, new DirectedGraph.ListCollector<>()))
                        .map(visited -> new ConnectedComponent(((DirectedGraph.ListCollector<ObjectInfo>) visited).getNodes(), heap))
                        .filter(c -> c.shouldReportThisComponent(rootFilterPatterns))
                        .sorted(Comparator.comparing(ConnectedComponent::getSizeInBytes).reversed())
                        .limit(limitNumOfComponents)
                        .collect(Collectors.toList());
    }

    public void printComponentsReport(PrintWriter out) {
        out.println("============Native Image Heap Object Graph Report============");
        out.printf("Total Heap Size: %.3fMB\n", MB(this.totalHeapSizeInBytes));
        out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());
        out.printf("Total number of connected components in the heap: %d\n", this.connectedComponents.size());
        out.println();
        out.println("===========Connected components in the Native Image Heap===========");

        for (int i = 0; i < connectedComponents.size(); i++) {
            int componentId = i + 1;
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            float sizeInMb = connectedComponent.getSizeInMB();
            float percentageOfTotalHeapSize = 100.0f * (float) connectedComponent.getSizeInBytes() / this.totalHeapSizeInBytes;
            out.printf("Component=%d | Size=%.4fMB | Percentage of total image heap size=%.4f%%\n", componentId, sizeInMb, percentageOfTotalHeapSize);
            for (String e : connectedComponent.getEntryPoints()) {
                out.printf("|--%s\n", e);
            }

            HeapHistogram objectHistogram = new HeapHistogram(out);
            connectedComponent.getObjects().forEach(o -> objectHistogram.add(o, o.getSize()));
            objectHistogram.printHeadings("");
            objectHistogram.print();
            out.println();

            out.printf("Identity hash code - object class\n");
            for (ObjectInfo e : connectedComponent.getObjects()) {
                out.printf("%d - %s\n", e.getIdentityHashCode(), e.getObjectClass().getName());
                for (Object reason : e.getAllReasons()) {
                    out.print("--");
                    out.println(formatReason(reason));
                }
            }
        }
    }

    public void printObjectsReport(PrintWriter out) {
        out.println("Object in the native image heap");
        List<ObjectInfo> objects = heap.getObjects().stream().sorted(Comparator.comparingLong(ObjectInfo::getSize).reversed()).collect(Collectors.toList());
        for (ObjectInfo objectInfo : objects) {
            out.printf("%d - %s\n", objectInfo.getSize(), objectInfo.getClazz());
            if (objectInfo.getAllReasons().size() == 1 && objectInfo.getMainReason() instanceof ObjectInfo) {
                continue;
            }
            for (Object reason : objectInfo.getAllReasons()) {
                if (!(reason instanceof ObjectInfo)) {
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
        for (ConnectedComponent connectedComponentInfo : connectedComponents) {
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
            return String.format("Method: %s", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("ObjectInfo: %s | %d | %s", r.getObjectClass().getName(), r.getIdentityHashCode(), reason);
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return String.format("HostedField: %s", r.getDeclaringClass().getName());
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return "Unhandled type in: NativeImageHeapGraph.formatReason([root]):179";
        }
    }

    private static float MB(long bytes) {
        return bytes / (1048576f);
    }

    private final static class ConnectedComponent {
        private final List<ObjectInfo> objects;
        private final long size;
        private final List<ImageHeapPartition> partitions;
        private final long[] componentSizeInPartition;
        private final Set<Object> reasons;

        public ConnectedComponent(List<ObjectInfo> objects, NativeImageHeap heap) {
            this.objects = objects;
            this.size = computeComponentSize(objects);
            this.partitions = Arrays.asList(heap.getLayouter().getPartitions());
            this.componentSizeInPartition = new long[partitions.size()];
            this.reasons = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ObjectInfo object : objects) {
                ImageHeapPartition partition = object.getPartition();
                int index = this.partitions.indexOf(partition);
                componentSizeInPartition[index] += object.getSize();
            }
        }

        public Set<Object> getReasons() {
            return reasons;
        }

        public List<String> getEntryPoints() {
            SortedSet<String> methodEntryPoints = new TreeSet<>();
            SortedSet<String> hostedFieldsEntryPoints = new TreeSet<>();
            for (ObjectInfo object : objects) {
                for (Object reason : object.getAllReasons()) {
                    if (reason instanceof String) {
                        methodEntryPoints.add((String) reason);
                    } else if (reason instanceof HostedField) {
                        HostedField field = (HostedField) reason;
                        hostedFieldsEntryPoints.add(String.format("%s.%s of type %s", field.getDeclaringClass(), field.getName(), field.getType()));
                    }
                }
            }
            ArrayList<String> result = new ArrayList<>(methodEntryPoints.size() + hostedFieldsEntryPoints.size());
            result.addAll(methodEntryPoints);
            result.addAll(hostedFieldsEntryPoints);
            return result;
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

        public boolean shouldReportThisComponent(List<String> rootFilterPattern) {
            for (String pattern : rootFilterPattern) {
                boolean shouldReport = getRoot().getAllReasons()
                                .stream()
                                .filter(r -> r instanceof String)
                                .anyMatch(r -> ((String)r).contains(pattern));
                if (shouldReport) {
                    return true;
                }
            }
            return false;
        }

        public List<Pair<ImageHeapPartition, Long>> getHistogram() {
            return IntStream.range(0, partitions.size())
                            .mapToObj(i -> Pair.create(partitions.get(i), componentSizeInPartition[i]))
                            .collect(Collectors.toList());
        }
    }
}

package com.oracle.svm.hosted.image;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.oracle.svm.core.util.ConcurrentIdentityHashMap;
import jdk.vm.ci.meta.ResolvedJavaField;
import org.graalvm.collections.Pair;
import org.graalvm.compiler.core.phases.StaticFieldsAccessGatherPhase;

import com.oracle.svm.core.image.ImageHeapPartition;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;

import jdk.vm.ci.meta.JavaMethod;

public class NativeImageHeapGraph {
    private final DirectedGraph<ObjectInfo> graph = new DirectedGraph<>();
    private final StaticFieldsAccessGatherPhase.StaticFieldsAccessRecords accessRecords;
    private final Set<HostedField> hostedFields;
    private final NativeImageHeap heap;
    private long totalHeapSize = 0;

    private IdentityHashMap<JavaMethod, Set<Object>> objectAccesses = new IdentityHashMap<>();

    public NativeImageHeapGraph(StaticFieldsAccessGatherPhase.StaticFieldsAccessRecords accessRecords, NativeImageHeap heap) {
        this.heap = heap;
        this.accessRecords = accessRecords;
        this.totalHeapSize = this.heap.getObjects().stream().map(ObjectInfo::getSize).reduce(Long::sum).get();
        this.hostedFields = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ObjectInfo objectInfo : heap.getObjects()) {
            connectChildToParentObjects(objectInfo);
        }
    }

    private void recordAccess(JavaMethod method, Object heapObject) {
        objectAccesses.computeIfAbsent(method, m -> Collections.newSetFromMap(new IdentityHashMap<>())).add(heapObject);
    }

    private static List<ObjectInfo> getAllReferencesToObjectInHeap(ObjectInfo objectInfo) {
        return objectInfo.getReasons().stream()
                        .filter(r -> r instanceof ObjectInfo)
                        .map(r -> (ObjectInfo) r)
                        .collect(Collectors.toList());
    }

    private void connectChildToParentObjects(ObjectInfo childObjectInfo) {
        for (Object reason : childObjectInfo.getReasons()) {
            if (reason instanceof ObjectInfo) {
                ObjectInfo parent = (ObjectInfo) reason;
                graph.connect(parent, childObjectInfo);
            } else if (reason instanceof String) {

            } else if (reason instanceof HostedField) {
                this.hostedFields.add((HostedField) reason);
            }
        }
    }

    private Long computeComponentSize(List<ObjectInfo> objects) {
        long totalSize = 0L;
        for (ObjectInfo o : objects) {
            totalSize += o.getSize();
        }
        return totalSize;
    }

    private void dumpComponent(PrintStream out, ArrayList<ObjectInfo> objects) {
        System.out.println("ObjectClass(Size)");
        for (ObjectInfo object : objects) {
            out.printf("%s(%d)\n", object.getClazz(), object.getSize());
            object.getReasons().forEach(o -> out.printf("\t%s\n", o));
        }
    }

    private static float MB(long bytes) {
        return bytes / (1048576f);
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, Object> keyExtractor) {
        Map<Object, Boolean> map = new ConcurrentIdentityHashMap<>();
        return t -> map.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

    public void printStatistics(PrintWriter out) {
        out.println("============Native Image Heap Object Graph Stats============");

        out.printf("Total Heap Size: %.3fMB\n", MB(this.totalHeapSize));
        out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());

        int numOfComponents = NativeImageHeapGraphFeature.Options.NativeImageHeapGraphNumOfComponents.getValue();

        List<String> reasonFilterPatterns = Arrays.stream(NativeImageHeapGraphFeature.Options.NativeImageHeapGraphReasonFilterOut
                        .getValue()
                        .split(","))
                        .map(String::strip)
                        .collect(Collectors.toList());
        Predicate<String> reasonFilterOut = (c) -> reasonFilterPatterns.stream().noneMatch(c::contains);

        List<String> objectFilterPatterns = Arrays.stream(NativeImageHeapGraphFeature.Options.NativeImageHeapGraphObjectFilterOut
                        .getValue()
                        .split(","))
                        .map(String::strip)
                        .collect(Collectors.toList());
        Predicate<String> objectFilterOut = (c) -> objectFilterPatterns.stream().noneMatch(c::contains);
        // Compute connected components by running dfs visit from all the root nodes
        List<List<ObjectInfo>> components = graph.getRoots().stream().parallel()
                        .filter(distinctByKey(ObjectInfo::getMainReason))
                        .filter(o -> reasonFilterOut.test(o.getMainReason().toString()))
                        .map(root -> DirectedGraph.DFSVisitor.create(this.graph).dfs(root))
                        .filter(c -> objectFilterOut.test(c.get(0).toString()))
                        .collect(Collectors.toList());

        numOfComponents = numOfComponents > 0 ? Math.min(numOfComponents, components.size()) : components.size();
        // Compute each component size in bytes
        List<Long> componentsSizes = components.stream()
                        .map(this::computeComponentSize)
                        .collect(Collectors.toList());

        // Sort connected components by size
        List<Pair<Long, List<ObjectInfo>>> sortedComponents = IntStream.range(0, components.size())
                        .mapToObj(i -> Pair.create(componentsSizes.get(i), components.get(i)))
                        .sorted((a, b) -> b.getLeft().compareTo(a.getLeft()))
                        .collect(Collectors.toList());

        // Compute components size percentage relative to the total heap size
        List<Double> componentsSizesFraction = sortedComponents.stream()
                        .limit(numOfComponents)
                        .map(o -> o.getLeft().doubleValue() / this.totalHeapSize)
                        .collect(Collectors.toList());

        out.println();
        out.println("===========Connected components in NativeImageHeap===========");
        String format = "%-10s %-12s %s -> %s\n";
        out.printf(format,
                        "Size", "HeapFraction", "Reason", "RootObjectType");


        IntStream.range(0, numOfComponents)
                        .mapToObj(i -> String.format(format,
                                        String.format("%.4fMB", MB(sortedComponents.get(i).getLeft())),
                                        String.format("%.4f", componentsSizesFraction.get(i)),
                                        formatReason(sortedComponents.get(i).getRight().get(0).getMainReason()),
                                        sortedComponents.get(i).getRight().get(0).getObjectClass().getName()))
                        .forEach(out::print);

        ComponentImagePartitionInfo.printHistogramDescription(out);

        sortedComponents.stream()
                        .limit(numOfComponents)
                        .forEach(pair -> new ComponentImagePartitionInfo(this, pair.getRight())
                                        .printHistogram(out));

    }

    private String formatReason(Object reason) {
        if (reason == null) {
            return "";
        }
        if (reason instanceof HostedField) {
//            return this.accessRecords.getMethodsForField((HostedField)reason).stream()
//                    .map(JavaMethod::getName).collect(Collectors.toList()).toString();
            return ((HostedField)reason).getName();
        }
        return reason.toString();
    }

    private static class ComponentImagePartitionInfo {
        private final List<ImageHeapPartition> partitions;
        private final long[] componentSizeInPartition;
        private final List<ObjectInfo> component;
        private final NativeImageHeapGraph graph;

        public ComponentImagePartitionInfo(NativeImageHeapGraph graph, List<ObjectInfo> component) {
            this.graph = graph;
            this.partitions = List.of(graph.heap.getLayouter().getPartitions());
            this.componentSizeInPartition = new long[partitions.size()];
            this.component = component;
            for (ObjectInfo object : component) {
                ImageHeapPartition partition = object.getPartition();
                int index = this.partitions.indexOf(partition);
                componentSizeInPartition[index] += object.getSize();
            }
        }

        public static void printHistogramDescription(PrintWriter out) {
            out.println();
            out.println("==========ImagePartitionStatistics per component=============");
            out.println("CSp - Component size in Partition");
            out.println("PS - Partition size");
            out.println("F - Total space taken by component inside a partition");
        }

        public List<Pair<ImageHeapPartition, Long>> getHistogram() {
            return IntStream.range(0, partitions.size())
                            .mapToObj(i -> Pair.create(partitions.get(i), componentSizeInPartition[i]))
                            .collect(Collectors.toList());
        }

        public void printHistogram(PrintWriter out) {
            List<Pair<ImageHeapPartition, Long>> histogram = getHistogram();
            out.printf("\n=========Reason: %s=========\n", this.graph.formatReason(this.component.get(0).getMainReason()));
            out.printf("%-20s %-26s\n", "Partition", "CSp/PS=F");
            for (Pair<ImageHeapPartition, Long> partitionInfo : histogram) {
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
}

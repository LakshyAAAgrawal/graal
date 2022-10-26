package com.oracle.svm.hosted.image;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    public void printStatistics(PrintStream out, int numOfComponents) {
        out.println("============Native Image Heap Object Graph Stats============");
        out.printf("Total Heap Size: %d\n", this.totalHeapSize);
        out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());

        // Compute connected components by running dfs visit from all the root nodes
        List<ArrayList<ObjectInfo>> components = graph.getRoots().parallelStream()
                        .map(root -> DirectedGraph.DFSVisitor.create(this.graph).dfs(root))
                        .collect(Collectors.toList());
        numOfComponents = numOfComponents > 0 ? numOfComponents : components.size();

        // Compute each component size in bytes
        List<Long> componentsSizes = components.stream()
                        .map(this::computeComponentSize)
                        .collect(Collectors.toList());

        // Sort connected components by size
        List<Pair<Long, ArrayList<ObjectInfo>>> sortedComponents = IntStream.range(0, components.size())
                        .mapToObj(i -> Pair.create(componentsSizes.get(i), components.get(i)))
                        .sorted((a, b) -> b.getLeft().compareTo(a.getLeft()))
                        .collect(Collectors.toList());

        // Compute components size percentage relative to the total heap size
        List<Double> componentsSizesFraction = sortedComponents.stream()
                        .map(o -> o.getLeft().doubleValue() / this.totalHeapSize)
                        .collect(Collectors.toList());

        out.println();
        out.println("===========Connected components in NativeImageHeap===========");
        out.println("ComponentSizeInBytes(FractionOfTotalHeapSize)\tRootObjectType\tReason");
        IntStream.range(0, numOfComponents)
                        .mapToObj(i -> String.format("%d - %d(%f)\t%s\t%s",
                                        i,
                                        sortedComponents.get(i).getLeft(),
                                        componentsSizesFraction.get(i),
                                        sortedComponents.get(i).getRight().get(0).getObjectClass(),
                                        sortedComponents.get(i).getRight().get(0).getMainReason().toString()))
                        .forEach(out::println);

        out.println("==========ImagePartitionStatistics per component============");
        int numOfPartitions = this.heap.getLayouter().getPartitions().length;
        sortedComponents.forEach(pair ->
                new ComponentImagePartitionInfo(this.heap.getLayouter().getPartitions(), pair.getRight())
                        .printHistogram(out));
    }

    private static class ComponentImagePartitionInfo {
        private final List<ImageHeapPartition> partitions;
        private final long[] componentSizeInPartition;
        private final ArrayList<ObjectInfo> component;

        public ComponentImagePartitionInfo(ImageHeapPartition[] partitions, ArrayList<ObjectInfo> objects) {
            this.partitions = Arrays.asList(partitions);
            this.componentSizeInPartition = new long[partitions.length];
            this.component = objects;
            for (ObjectInfo object : objects) {
                ImageHeapPartition partition = object.getPartition();
                int index = this.partitions.indexOf(partition);
                componentSizeInPartition[index] += object.getSize();
            }
        }

        public List<Pair<ImageHeapPartition, Long>> getHistogram() {
            return IntStream.range(0, partitions.size())
                    .mapToObj(i -> Pair.create(partitions.get(i), componentSizeInPartition[i]))
                    .collect(Collectors.toList());
        }

        public void printHistogram(PrintStream out) {
            List<Pair<ImageHeapPartition, Long>> histogram = getHistogram();
            out.printf("=========%s=========", this.component.get(0).getMainReason());
            for (int i = 0; i < histogram.size(); i++) {
                Pair<ImageHeapPartition, Long> partitionInfo = histogram.get(i);
                ImageHeapPartition partition = partitionInfo.getLeft();
                long componentSizeInPartition = partitionInfo.getRight();
                out.printf("ImagePartition [%d] - %s - %d/%d (%f)\n",
                        i, partition,
                        componentSizeInPartition,
                        partition.getSize(),
                        (double) componentSizeInPartition / partition.getSize());
            }
        }
    }
}

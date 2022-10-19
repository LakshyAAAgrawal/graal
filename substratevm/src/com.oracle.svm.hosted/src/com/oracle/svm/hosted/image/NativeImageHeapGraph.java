package com.oracle.svm.hosted.image;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.ProgressReporter;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;
import jdk.vm.ci.meta.JavaMethod;
import org.graalvm.collections.Pair;
import org.graalvm.compiler.core.phases.NativeImageHeapGraphAccessPhase;

/*
 * Iterates through the NativeImageHeap objects and constructs a directed graph where each node in
 * the graph represents an Object and each edge represents a reference between objects. If object A
 * references an object B then in the graph there will be a node A that will have a neighbour node
 * B.
 * 
 * 
 */
public class NativeImageHeapGraph {
    private final DirectedGraph<ObjectInfo> graph = new DirectedGraph<>();

    private final NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords accessRecords;
    private IdentityHashMap<Object, Set<Object>> rootEntryPoints = new IdentityHashMap<>();
    private NativeImageHeap heap;
    private long totalHeapSize = 0;

    private IdentityHashMap<JavaMethod, Set<Object>> objectAccesses = new IdentityHashMap<>();

    public NativeImageHeapGraph(NativeImageHeapGraphAccessPhase.NativeImageHeapAccessRecords accessRecords, NativeImageHeap heap) {
        this.heap = heap;
        this.accessRecords = accessRecords;
        create();
    }

    public void recordAccess(JavaMethod method, Object heapObject) {
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
            }
// else if (reason instanceof String) {
// String reasonForConstantObject = (String) reason;
// graph.connect(reasonForConstantObject, child);
// } else if (reason instanceof HostedField) {
// HostedField staticField = (HostedField) reason;
// graph.connect(staticField, child);
// } else {
// VMError.shouldNotReachHere("No such reason handled");
// }
        }
    }

    private Long computeComponentSize(List<ObjectInfo> objects) {
        long totalSize = 0L;
        for (ObjectInfo o : objects) {
            totalSize += o.getSize();
        }
        return totalSize;
    }

    private void create() {
        this.totalHeapSize = this.heap.getObjects().stream().map(ObjectInfo::getSize).reduce(Long::sum).get();
        System.out.printf("Total Heap Size: %d\n", this.totalHeapSize);
        System.out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());
        for (ObjectInfo objectInfo : heap.getObjects()) { // typeof objectInfo.reason String,
                                                          // ObjectInfo, HostedField
            connectChildToParentObjects(objectInfo);
        }
        System.out.println("NativeImageHeapGraph.NativeImageHeapGraph([heap]):210");
        List<ArrayList<ObjectInfo>> components = graph.getRoots().parallelStream()
                        .map(root -> DirectedGraph.DFSVisitor.create(this.graph).dfs(root))
                        .collect(Collectors.toList());
        List<Long> componentsSizes = components.stream()
                        .map(this::computeComponentSize)
                        .collect(Collectors.toList());
        List<Pair<Long, ArrayList<ObjectInfo>>> sortedComponents = IntStream.range(0, components.size())
                        .mapToObj(i -> Pair.create(componentsSizes.get(i), components.get(i)))
                        .sorted((a, b) -> b.getLeft().compareTo(a.getLeft()))
                        .collect(Collectors.toList());
        System.out.println("NativeImageHeapGraph.NativeImageHeapGraph([heap]):222");
        int limitNumOfComponentsTo = 32;
        List<Double> componentsSizesFraction = sortedComponents.stream()
                        .map(o -> o.getLeft().doubleValue() / this.totalHeapSize)
                        .collect(Collectors.toList());
        IntStream.range(0, limitNumOfComponentsTo)
                        .forEach(i -> System.out.printf("%f\t%s\t%d\t%s\n", componentsSizesFraction.get(i), sortedComponents.get(i).getRight().get(0).getObjectClass(),
                                sortedComponents.get(i).getRight().size(),
                                sortedComponents.get(i).getRight().get(0).getMainReason().toString()));
        // components.get(0).forEach(System.out::println);
        System.out.println("NativeImageHeapGraph.NativeImageHeapGraph([heap]):211");
        System.out.println(graph.getNumberOfNodes());
        System.out.println(graph.getNumberOfEdges());
        System.out.println("NativeImageHeapGraph.NativeImageHeapGraph([heap]):215:end");
    }
}

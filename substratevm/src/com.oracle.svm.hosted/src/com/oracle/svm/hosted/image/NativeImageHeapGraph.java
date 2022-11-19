package com.oracle.svm.hosted.image;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.svm.hosted.Utils;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import org.graalvm.collections.Pair;

import com.oracle.svm.core.image.ImageHeapPartition;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;

public class NativeImageHeapGraph {
    private final NativeImageHeap heap;
    private final long totalHeapSizeInBytes;
    private final List<ConnectedComponent> connectedComponents;
    private final BigBang bb;

    private static AbstractGraph<ObjectInfo> getGraphInstance() {
        return new UndirectedGraph<>();
    }

    public NativeImageHeapGraph(NativeImageHeap heap, BigBang bigBang, AbstractImage image) {
        System.out.println("Constructing Native Image Heap Graph: ... ");
        long start = System.currentTimeMillis();
        this.heap = heap;
        this.totalHeapSizeInBytes = image.getImageHeapSize();
        this.bb = bigBang;
        this.connectedComponents = computeConnectedComponents(this.heap);
        long end = System.currentTimeMillis();
        System.out.printf("Computed in: %.4fs\n", (end - start) / 1000.0f);
    }

    private static Set<ObjectInfo> getHashSetInstance() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private List<ConnectedComponent> computeConnectedComponents(NativeImageHeap heap) {
        Set<ObjectInfo> allImageHeapObjects = getHashSetInstance();
        allImageHeapObjects.addAll(heap.getObjects());

        NativeImageHeap.PulledIn[] splitBy = {
                        NativeImageHeap.PulledIn.ByInternedStringsTable,
                        NativeImageHeap.PulledIn.ByDynamicHub,
                        NativeImageHeap.PulledIn.ByImageCodeInfo,
                        NativeImageHeap.PulledIn.ByMethod,
                        NativeImageHeap.PulledIn.ByHostedField
        };

        List<ConnectedComponent> connectedComponents = new ArrayList<>();
        for (NativeImageHeap.PulledIn whatsPulledIn : splitBy) {
            Set<ObjectInfo> objectInfos = removeObjectsBy(whatsPulledIn, allImageHeapObjects);
            if (whatsPulledIn != NativeImageHeap.PulledIn.ByDynamicHub && whatsPulledIn != NativeImageHeap.PulledIn.ByInternedStringsTable) {
                AbstractGraph<ObjectInfo> graph = constructGraph(objectInfos);
                connectedComponents.addAll(computeConnectedComponentsInGraph(graph, whatsPulledIn));
            }
        }

        return connectedComponents.stream()
                        .sorted(Comparator.comparing(ConnectedComponent::getSizeInBytes).reversed())
                        .collect(Collectors.toList());
    }

    private List<ConnectedComponent> computeConnectedComponentsInGraph(AbstractGraph<ObjectInfo> graph, NativeImageHeap.PulledIn whatsPulledIn) {
        ConnectedComponentsCollector collector = new ConnectedComponentsCollector(graph);
        for (ObjectInfo node : graph.getRoots()) {
            if (collector.isNotVisited(node)) {
                graph.dfs(node, collector);
            }
        }
        return collector.getListOfObjectsForEachComponent()
                        .stream()
                        .map(objectsForComponent -> new ConnectedComponent(objectsForComponent, this.heap))
                        .collect(Collectors.toList());
    }

    private static Set<ObjectInfo> removeObjectsBy(NativeImageHeap.PulledIn reason, Set<ObjectInfo> objects) {
        Set<ObjectInfo> result = getHashSetInstance();
        for (Iterator<ObjectInfo> iterator = objects.iterator(); iterator.hasNext();) {
            ObjectInfo o = iterator.next();
            if (o.isPulledInBy(reason)) {
                result.add(o);
                iterator.remove();
            }
        }
        return result;
    }

    private AbstractGraph<ObjectInfo> constructGraph(Set<ObjectInfo> objects) {
        AbstractGraph<ObjectInfo> graph = getGraphInstance();
        for (ObjectInfo objectInfo : objects) {
            graph.addNode(objectInfo);
            for (Object referencesToThisObject : objectInfo.getAllReasons()) {
                if (referencesToThisObject instanceof ObjectInfo && objects.contains(referencesToThisObject)) {
                    graph.connect((ObjectInfo) referencesToThisObject, objectInfo);
                }
            }
        }
        return graph;
    }

    public void printEntryPointsReport(PrintWriter out) {
        for (int i = 0, connectedComponentsSize = connectedComponents.size(); i < connectedComponentsSize; i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            TreeSet<String> entryPoints = new TreeSet<>();
            for (ObjectInfo objectInfo : connectedComponent.getObjects()) {
                for (Object reason : objectInfo.getAllReasons()) {
                    if (!(reason instanceof ObjectInfo)) {
                        entryPoints.add(formatReason(reason));
                    }
                }
            }
            for (String entryPoint : entryPoints) {
                out.printf("ComponentId=%d=%s\n", i, entryPoint);
            }
        }
    }

    public void printAllImageHeapObjects(PrintWriter out) {
        out.println("ObjectInfo(objects class, objects identity hash code, constant value, category");
        for (ObjectInfo info : this.heap.getObjects()) {
            out.println(formatObject(info, bb));
        }
    }

    public void printObjectsForEachComponent(PrintWriter out) {
        out.println("ConnectedComponentId=ObjectInfo(objects class, objects identity hash code, constant value, category");
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            for (ObjectInfo info : connectedComponent.getObjects()) {
                out.printf("ComponentId=%d=%s\n", i, formatObject(info, bb));
            }
        }
    }

    public void printObjectsAndReferencesForEachComponent(PrintWriter out) {
        out.println("ConnectedComponentId=ObjectInfo(objects class");
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            for (ObjectInfo objectInfo : connectedComponent.getObjects()) {
                out.printf("ComponentId=%d=%s <- ", i, formatObject(objectInfo, bb));
                for (Object reason : objectInfo.getAllReasons()) {
                    out.printf("%d=%s; ", i, formatObject(reason, bb));
                }
                out.println();
            }
            out.println();
        }
    }

    public void printConnectedComponentsHistogramsAndEntryPoints(PrintWriter out) {
        out.println("============Native Image Heap Object Graph Report============");
        out.printf("Total Heap Size: %s\n", Utils.bytesToHuman(totalHeapSizeInBytes));
        out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());
        out.printf("Number of connected components in the report %d\n", this.connectedComponents.size());
        out.println();
        out.println("===========Connected components in the Native Image Heap===========");
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            float percentageOfTotalHeapSize = 100.0f * (float) connectedComponent.getSizeInBytes() /
                            this.totalHeapSizeInBytes;
            HeapHistogram objectHistogram = new HeapHistogram(out);
            connectedComponent.getObjects().forEach(o -> objectHistogram.add(o, o.getSize()));
            objectHistogram.printHeadings(
                            String.format("ComponentId=%d | Size=%s | Percentage of total image heap size=%.4f%%", i,
                                    Utils.bytesToHuman(connectedComponent.getSizeInBytes()),
                                    percentageOfTotalHeapSize));
            objectHistogram.print();
            out.println();
        }
    }

    public void printImagePartitionsReport(PrintWriter out) {
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            List<Pair<ImageHeapPartition, Long>> imageHeapPartitionDistribution = connectedComponent.getImageHeapPartitionDistribution();
            float percentageOfTotalHeapSize = 100.0f * (float) connectedComponent.getSizeInBytes() / this.totalHeapSizeInBytes;
            out.printf("ComponentId=%d | Size=%s | Percentage of total image heap size=%f%%\n",
                    i,
                    Utils.bytesToHuman(connectedComponent.getSizeInBytes()),
                    percentageOfTotalHeapSize);

            out.printf("%-20s %-20s %s\n", "Partition", "Taken space", "Percentage of total heap size");
            for (Pair<ImageHeapPartition, Long> partition : imageHeapPartitionDistribution) {
                long partitionSize = partition.getLeft().getSize();
                long takenSpace = partition.getRight();
                float percentage = 100.0f * takenSpace / partitionSize;
                out.printf("%-20s %-20s %f%%\n", partition.getLeft().getName(), String.format("%s/%s", Utils.bytesToHuman(takenSpace), Utils.bytesToHuman(partitionSize)), percentage);
            }
            out.println();
        }
    }

    private static String formatReason(Object reason) {
        if (reason instanceof String) {
            return String.format("Method(%s)", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("ObjectInfo(class %s, %d, %s)", r.getObjectClass().getName(), r.getIdentityHashCode(), r.getPulledInBySetAsString());
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return r.format("HostedField(class %H { static %t %n; })");
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return null;
        }
    }

    private String formatObject(Object reason, BigBang bb) {
        if (reason instanceof String) {
            return String.format("Method(%s)", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("ObjectInfo(class %s, %d, %s, %s)", r.getObjectClass().getName(), r.getIdentityHashCode(), constantAsString(bb, r.getConstant()), r.getPulledInBySetAsString());
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return r.format("HostedField(class %H { static %t %n; })");
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return null;
        }
    }

    private static Object constantAsObject(BigBang bb, JavaConstant constant) {
        return bb.getSnippetReflectionProvider().asObject(Object.class, constant);
    }

    private static String escape(String str) {
        return str.replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\"\"");
    }

    private static String constantAsString(BigBang bb, JavaConstant constant) {
        Object object = constantAsObject(bb, constant);
        if (object instanceof String) {
            String str = (String) object;
            str = "\"" + escape(str) + "\"";
            return str;
        } else {
            return escape(JavaKind.Object.format(object));
        }
    }

    private final static class ListCollector<Node> implements AbstractGraph.NodeVisitor<Node> {
        private final List<Node> nodes = new ArrayList<>();

        @Override
        public void accept(AbstractGraph<Node> graph, AbstractGraph.VisitorState<Node> state) {
            nodes.add(state.currentNode);
        }

        public List<Node> getNodes() {
            return nodes;
        }
    }

    private final static class CollectNLevels<Node> implements AbstractGraph.NodeVisitor<Node> {
        private final ArrayList<Node> levels = new ArrayList<>();
        private final int firstNLevels;
        private boolean shouldTerminate = false;

        public CollectNLevels(int firstNLevels) {
            this.firstNLevels = firstNLevels;
        }

        ArrayList<Node> getNodes() {
            return levels;
        }

        @Override
        public void accept(AbstractGraph<Node> graph, DirectedGraph.VisitorState<Node> state) {
            if (state.level == firstNLevels) {
                shouldTerminate = true;
                return;
            }
            levels.add(state.currentNode);
        }

        @Override
        public boolean shouldTerminateVisit() {
            return shouldTerminate;
        }
    }

    private static final class DirectedGraphCollector implements AbstractGraph.NodeVisitor<ObjectInfo> {
        public final DirectedGraph<ObjectInfo> subgraph = new DirectedGraph<>();

        @Override
        public void accept(AbstractGraph<ObjectInfo> graph, DirectedGraph.VisitorState<ObjectInfo> state) {
            subgraph.connect(state.parentNode, state.currentNode);
        }
    }

    private static final class ConnectedComponentsCollector implements AbstractGraph.NodeVisitor<ObjectInfo> {
        private final AbstractGraph<ObjectInfo> graph;
        private final List<List<ObjectInfo>> connectedComponents = new ArrayList<>();
        private boolean[] visited;
        private int componentId = 0;

        public ConnectedComponentsCollector(AbstractGraph<ObjectInfo> graph) {
            this.visited = new boolean[graph.getNumberOfNodes()];
            this.graph = graph;
        }

        @Override
        public void onStart(AbstractGraph<ObjectInfo> graph) {
            connectedComponents.add(new ArrayList<>());
        }

        @Override
        public void accept(AbstractGraph<ObjectInfo> graph, AbstractGraph.VisitorState<ObjectInfo> state) {
            int nodeId = graph.getNodeId(state.currentNode);
            this.visited[nodeId] = true;
            connectedComponents.get(componentId).add(state.currentNode);
        }

        @Override
        public void onEnd(AbstractGraph<ObjectInfo> graph) {
            ++componentId;
        }

        @Override
        public boolean shouldVisit(ObjectInfo objectInfo) {
            return !this.visited[graph.getNodeId(objectInfo)];
        }

        public boolean isNotVisited(ObjectInfo info) {
            int id = graph.getNodeId(info);
            if (id == -1) {
                return false;
            }
            return !this.visited[id];
        }

        public List<List<ObjectInfo>> getListOfObjectsForEachComponent() {
            return connectedComponents;
        }
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
                if (index >= 0) // filler objects might not be added to any partition
                    componentSizeInPartition[index] += object.getSize();
            }
        }

        public Set<Object> getReasons() {
            return reasons;
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

        public List<ObjectInfo> getObjects() {
            return objects;
        }

        public ObjectInfo getRoot() {
            return objects.get(0);
        }

        public List<Pair<ImageHeapPartition, Long>> getImageHeapPartitionDistribution() {
            return IntStream.range(0, partitions.size())
                            .mapToObj(i -> Pair.create(partitions.get(i), componentSizeInPartition[i]))
                            .collect(Collectors.toList());
        }
    }
}
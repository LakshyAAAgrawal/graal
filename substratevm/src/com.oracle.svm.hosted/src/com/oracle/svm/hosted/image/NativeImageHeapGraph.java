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
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.DynamicHubCompanion;
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
    private List<ConnectedComponent> connectedComponents;
    private final BigBang bb;

    private static AbstractGraph<ObjectInfo> getGraphInstance() {
        return new UndirectedGraph<>();
    }

    public NativeImageHeapGraph(NativeImageHeap heap, long imageHeapSize) {
        System.out.println("Constructing Native Image Heap Graph: ... ");
        long start = System.currentTimeMillis();
        this.heap = heap;
        this.totalHeapSizeInBytes = imageHeapSize;
        this.bb = this.heap.getMetaAccess().getUniverse().getBigBang();
        this.connectedComponents = computeConnectedComponents(this.heap);
        long end = System.currentTimeMillis();
        System.out.printf("Elapsed seconds: %.4f\n", (end - start) / 1000.0f);
    }

    private static Set<ObjectInfo> getHashSetInstance() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
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

    private List<ConnectedComponent> computeConnectedComponentsInGraph(AbstractGraph<ObjectInfo> graph, NativeImageHeap.PulledIn whatsPulledIn) {
        ConnectedComponentsCollector collector = new ConnectedComponentsCollector(graph);
        for (ObjectInfo node : graph.getRoots()) {
            if (collector.isNotVisited(node)) {
                graph.dfs(node, collector);
            }
        }
        return collector.getListOfObjectsForEachComponent()
                        .stream()
                        .map(objectsForComponent -> new ConnectedComponent(objectsForComponent, this.heap, whatsPulledIn))
                        .collect(Collectors.toList());
    }

    private List<ConnectedComponent> computeConnectedComponents(NativeImageHeap heap) {
        Set<ObjectInfo> allImageHeapObjects = getHashSetInstance();
        allImageHeapObjects.addAll(heap.getObjects());

        NativeImageHeap.PulledIn[] splitBy = {
                        NativeImageHeap.PulledIn.ByInternedStringsTable,
                        NativeImageHeap.PulledIn.ByImageCodeInfo,
                        NativeImageHeap.PulledIn.ByDynamicHub,
                        NativeImageHeap.PulledIn.ByMethod,
                        NativeImageHeap.PulledIn.ByHostedField
        };

        List<ConnectedComponent> connectedComponents = new ArrayList<>();
        for (NativeImageHeap.PulledIn whatsPulledIn : splitBy) {
            Set<ObjectInfo> objectInfos = removeObjectsBy(whatsPulledIn, allImageHeapObjects);
            if (whatsPulledIn != NativeImageHeap.PulledIn.ByDynamicHub) {
                AbstractGraph<ObjectInfo> graph = constructGraph(objectInfos);
                connectedComponents.addAll(computeConnectedComponentsInGraph(graph, whatsPulledIn));
            }
        }

        return connectedComponents.stream()
                        .sorted(Comparator.comparing(ConnectedComponent::getSizeInBytes).reversed())
                        .collect(Collectors.toList());
    }

    public void printEntryPointsReport(PrintWriter out) {
        Set<String> entryPoints = new TreeSet<>();
        for (ObjectInfo objectInfo : this.heap.getObjects()) {
            for (Object reason : objectInfo.getAllReasons()) {
                if (!(reason instanceof ObjectInfo)) {
                    entryPoints.add(formatReason(reason));
                }
            }
        }
        entryPoints.forEach(out::println);
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
                            String.format("Component=%d | Size=%s | Percentage of total image heap size=%.4f%%", i,
                                            Utils.bytesToHuman(connectedComponent.getSizeInBytes()),
                                            percentageOfTotalHeapSize));
            objectHistogram.print();
            out.println();
        }
    }

    public static Object constantAsObject(BigBang bb, JavaConstant constant) {
        return bb.getSnippetReflectionProvider().asObject(Object.class, constant);
    }

    private static String escape(String str) {
        return str.replace("\n", "\\n").replace("\r", "\\r");
    }

    static String constantAsString(BigBang bb, JavaConstant constant) {
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

    public final static class CollectNLevels<Node> implements AbstractGraph.NodeVisitor<Node> {
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
        private PrintWriter out;

        public ConnectedComponentsCollector(AbstractGraph<ObjectInfo> graph) {
            this.visited = new boolean[graph.getNumberOfNodes()];
            this.graph = graph;
            this.out = out;
        }

        @Override
        public void onStart(AbstractGraph<ObjectInfo> graph) {
            connectedComponents.add(new ArrayList<>());
        }

        @Override
        public void accept(AbstractGraph<ObjectInfo> graph, AbstractGraph.VisitorState<ObjectInfo> state) {
            int nodeId = graph.getNodeId(state.currentNode);
// out.printf("%s -> %s\n", state.parentNode == null ? "null" : state.parentNode,
// state.currentNode);
            this.visited[nodeId] = true;
            connectedComponents.get(componentId).add(state.currentNode);
        }

        @Override
        public void onEnd(AbstractGraph<ObjectInfo> graph) {
            ++componentId;
        }

        @Override
        public boolean shouldVisit(ObjectInfo objectInfo) {
// if (suppressInternalObjects(objectInfo)) {
// return false;
// }
            return !this.visited[graph.getNodeId(objectInfo)];
        }

        public int getNumOfConnectedComponents() {
            return componentId;
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

    private static final Class<?>[] objectTypesToSuppress = {
                    DynamicHub.class,
                    DynamicHubCompanion.class,
                    com.oracle.svm.core.classinitialization.ClassInitializationInfo.class,
    };

    private static boolean isInternedStrings(Object info) {
        if (info instanceof ObjectInfo) {
            Object mainReason = ((ObjectInfo) info).getMainReason();
            return mainReason instanceof String && mainReason.toString().equals("internedStrings table");
        } else {
            return false;
        }
// for (Object reason : info.getAllReasons()) {
// if (reason instanceof String && reason.toString().equals("internedStrings table")) {
// return true;
// }
// }
    }

    private static boolean internalObject(ObjectInfo objectInfo) {
        for (Class<?> clazz : objectTypesToSuppress) {
            if (objectInfo.getObject().getClass().equals(clazz)) {
                return true;
            }
            if (objectInfo.getObject().getClass().toString().contains(clazz.getName())) {
                return true;
            }
        }

        if (isInternedStrings(objectInfo)) {
            return true;
        }

        return false;
    }

    private static String formatReason(Object reason) {
        if (reason instanceof String) {
            return String.format("Method(%s)", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("ObjectInfo(%s,%d,%s)", r.getObjectClass().getName(), r.getIdentityHashCode(), r.getPulledInBySetAsString());
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return r.format("HostedField(class %H { static %t %n; })");
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return "Unhandled type in: NativeImageHeapGraph.formatReason([root]):179";
        }
    }

    public void dumpImageHeapObjectsInfo(PrintWriter out) {
        out.println("ObjectInfo(objects class, objects identity hash code, constant value, category");
        for (ObjectInfo info : this.heap.getObjects()) {
            out.printf("ObjectInfo(%s,%d,%s,%s)\n", info.getObjectClass().getName(), info.getIdentityHashCode(), constantAsString(bb, info.getConstant()), info.getPulledInBySetAsString());
        }
    }

    public void printObjectInfosAndItsComponent(PrintWriter out) {
        out.println("ConnectedComponentId=ObjectInfo(objects class, objects identity hash code, constant value, category");
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            for (ObjectInfo info : connectedComponent.getObjects()) {
                out.printf("%d=ObjectInfo(%s,%d,%s,%s)\n", i, info.getObjectClass().getName(), info.getIdentityHashCode(), constantAsString(bb, info.getConstant()), info.getPulledInBySetAsString());
            }
        }
// for (ObjectInfo info : this.heap.getObjects()) {
// out.printf("%d=ObjectInfo(%s,%d,%s,%s)\n", 0, info.getObjectClass().getName(),
// info.getIdentityHashCode(), constantAsString(bb, info.getConstant()),
// info.getPulledInBySetAsString());
// }
    }

    public void printObjectInfosAndReferencesToObjectInfoForEachComponented(PrintWriter out) {
        out.println("ConnectedComponentId=ObjectInfo(objects class");
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            for (ObjectInfo objectInfo : connectedComponent.getObjects()) {
                out.printf("%d=%s <- ", i, formatObject(objectInfo, bb));
                for (Object reason : objectInfo.getAllReasons()) {
                    out.printf("%d=%s; ", i, formatObject(reason, bb));
                }
                out.println();
            }
            out.println();
        }
    }

    private String formatObject(Object reason, BigBang bb) {
        if (reason instanceof String) {
            return String.format("Method(%s)", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("ObjectInfo(%s, %d, %s)", r.getObjectClass().getName(), r.getIdentityHashCode(), constantAsString(bb, r.getConstant()));
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return r.format("HostedField(class %H { static %t %n; })");
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return "Unhandled type in: NativeImageHeapGraph.formatReason([root]):179";
        }
    }

    private final static class ConnectedComponent {
        private final List<ObjectInfo> objects;
        private final long size;
        private final List<ImageHeapPartition> partitions;
        private NativeImageHeap.PulledIn whatsPulledIn;
        private final long[] componentSizeInPartition;
        private final Set<Object> reasons;

        public ConnectedComponent(List<ObjectInfo> objects, NativeImageHeap heap, NativeImageHeap.PulledIn whatsPulledIn) {
            this.objects = objects;
            this.size = computeComponentSize(objects);
            this.partitions = Arrays.asList(heap.getLayouter().getPartitions());
            this.whatsPulledIn = whatsPulledIn;
            this.componentSizeInPartition = new long[partitions.size()];
            this.reasons = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ObjectInfo object : objects) {
                ImageHeapPartition partition = object.getPartition();
                int index = this.partitions.indexOf(partition);
                if (index >= 0) // filler objects might not be added to any partition
                    componentSizeInPartition[index] += object.getSize();
            }
        }

        public NativeImageHeap.PulledIn getWhatsPulledIn() {
            return whatsPulledIn;
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

        public boolean shouldReportThisComponent() {
            List<String> rootFilterPatterns = Arrays.stream(
                            NativeImageHeapGraphFeature.Options.NativeImageHeapGraphRootFilter.getValue().split(","))
                            .map(String::strip)
                            .collect(Collectors.toList());

            for (String pattern : rootFilterPatterns) {
                boolean shouldReport = getRoot().toString().contains(pattern);
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
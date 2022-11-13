package com.oracle.svm.hosted.image;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.DynamicHubCompanion;
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
    private final DirectedGraph<ObjectInfo> objectGraph;
    private final BigBang bb;

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
        this.bb = this.heap.getMetaAccess().getUniverse().getBigBang();
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

    private static void connectChildToParentObjects(DirectedGraph<ObjectInfo> graph, ObjectInfo childObjectInfo) {
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

    private final static class ListCollector<Node> implements DirectedGraph.NodeVisitor<Node> {
        private final List<Node> nodes = new ArrayList<>();

        @Override
        public void accept(DirectedGraph<Node> graph, DirectedGraph.VisitorState<Node> state) {
            nodes.add(state.currentNode);
        }

        public List<Node> getNodes() {
            return nodes;
        }
    }

    public final static class CollectNLevels<Node> implements DirectedGraph.NodeVisitor<Node> {
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
        public void accept(DirectedGraph<Node> graph, DirectedGraph.VisitorState<Node> state) {
            if (state.level == firstNLevels) {
                shouldTerminate = true;
                return;
            }
// if (levels.size() == state.level) {
// levels.add(new ArrayList<>());
// }
            levels.add(state.currentNode);
        }

        @Override
        public boolean shouldTerminateVisit() {
            return shouldTerminate;
        }
    }

    private static final class DirectedGraphCollector implements DirectedGraph.NodeVisitor<ObjectInfo> {
        public final DirectedGraph<ObjectInfo> subgraph = new DirectedGraph<>();

        @Override
        public void accept(DirectedGraph<ObjectInfo> graph, DirectedGraph.VisitorState<ObjectInfo> state) {
            subgraph.connect(state.parentNode, state.currentNode);
        }
    }

    private static List<ConnectedComponent> computeConnectedComponents(DirectedGraph<ObjectInfo> graph, NativeImageHeap heap) {
        int limitNumOfComponents = NativeImageHeapGraphFeature.Options.NativeImageHeapGraphNumOfComponents.getValue();
        limitNumOfComponents = limitNumOfComponents > 0 ? Math.min(limitNumOfComponents, graph.getRoots().size()) : graph.getRoots().size();
        // Compute connected components by running dfs visit from all the root nodes

        return graph.getRoots().stream()
                        .filter(NativeImageHeapGraph::mainReasonFilter)
                        .parallel()
                        .map(root -> graph.dfs(root, new ListCollector<>()))
                        .map(visited -> new ConnectedComponent(visited.getNodes(), heap))
                        .filter(ConnectedComponent::shouldReportThisComponent)
                        .sorted(Comparator.comparing(ConnectedComponent::getSizeInBytes).reversed())
                        .limit(limitNumOfComponents)
                        .collect(Collectors.toList());
    }

    private static boolean mainReasonFilter(ObjectInfo info) {
        String[] patterns = NativeImageHeapGraphFeature.Options.NativeImageHeapGraphRootFilter.getValue().split(",");
        // Object reason = info.getMainReason();
        for (Object reason : info.getAllReasons()) {
            boolean result = false;
            if (reason instanceof String) {
                String r = (String) reason;
                result = Arrays.stream(patterns).anyMatch(r::contains);
            } else if (reason instanceof HostedField) {
                HostedField r = (HostedField) reason;
                result = Arrays.stream(patterns).anyMatch(p -> r.getDeclaringClass().getName().contains(p));
            }
            if (result) {
                return true;
            }
        }
        return false;
    }

    public void printReferenceChainGraph(PrintWriter out) {
        DirectedGraph<ObjectInfo> graph = new DirectedGraph<>();
        for (ObjectInfo object : heap.getObjects().stream().filter(NativeImageHeapGraph::suppressInternalObjects).collect(Collectors.toList())) {
            List<ObjectInfo> referenceChain = object.upwardsReferenceChain();
            if (mainReasonFilter(referenceChain.get(referenceChain.size() - 1))) {
                ObjectInfo child = referenceChain.get(0);
                for (int i = 1; i < referenceChain.size(); ++i) {
                    ObjectInfo parent = referenceChain.get(i);
                    graph.connect(parent, child);
                    child = parent;
                }
            }
        }
        out.println("digraph {");
        graph.getLeaves().stream().map(o -> makeDownwardsReferenceChain(bb, o, NativeImageHeapGraph::suppressInternalObjects)).forEach(out::println);
        out.println("}");
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
            float percentageOfTotalHeapSize = 100.0f * (float) connectedComponent.getSizeInBytes() /
                    this.totalHeapSizeInBytes;
            out.printf("Component=%d | Size=%.4fMB | Percentage of total image heap size=%.4f%%\n", componentId, sizeInMb, percentageOfTotalHeapSize);

            connectedComponent.getRoot().getAllReasons().forEach(out::println);

            HeapHistogram objectHistogram = new HeapHistogram(out);
            connectedComponent.getObjects().forEach(o -> objectHistogram.add(o, o.getSize()));
            objectHistogram.printHeadings("");
            objectHistogram.print();
            out.println();
        }
    }

    private static final String[] suppressObjectsOfType = {
                    DynamicHub.class.toString(),
                    DynamicHubCompanion.class.toString(),
    };

    private static boolean suppressInternalObjects(ObjectInfo info) {
        String infoClazzName = info.getObject().getClass().toString();
        for (String clazzName : suppressObjectsOfType) {
            if (infoClazzName.contains(clazzName)) {
                return false;
            }
        }
        return true;
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
            str = escape(str);
            return str;
        } else {
            return escape(JavaKind.Object.format(object));
        }
    }

    private static String selectReason(ObjectInfo info) {
        String[] patterns = NativeImageHeapGraphFeature.Options.NativeImageHeapGraphRootFilter.getValue().split(",");
        for (Object reason : info.getAllReasons()) {
            if (reason instanceof String && Arrays.stream(patterns).anyMatch(p -> reason.toString().contains(p))) {
                return (String) reason;
            }
        }
        return "NoReasonToSelectFor: " + info;
    }

    private static String makeDownwardsReferenceChain(BigBang bb, ObjectInfo info, Predicate<ObjectInfo> objectFilter) {
        List<ObjectInfo> chain = info.upwardsReferenceChain();
        Collections.reverse(chain);
        StringBuilder result = new StringBuilder();
        result.append("\"");
        result.append(selectReason(chain.get(0)));
        result.append("\" -> ");
        for (int i = 0; i < chain.size(); i++) {
            ObjectInfo cur = chain.get(i);
            if (objectFilter == null || objectFilter.test(cur)) {
                result.append("\"");
                result.append(cur.getObject().getClass().getName())
                        .append("{")
                        .append(constantAsString(bb, cur.getConstant()).replace("\"", "\\\""))
                        .append("}");
                result.append("\"");
                if (i < chain.size() - 1)
                    result.append(" -> ");
            }
        }
        result.append(";");
        return result.toString();
    }

    private static String makeReferenceChainString(BigBang bb, ObjectInfo info, Predicate<ObjectInfo> objectFilter) {
        // TODO(mspasic): read value as java constant here and try to output it
        StringBuilder result = new StringBuilder();
        Object cur = info;
        ObjectInfo prev = null;
        while (cur instanceof ObjectInfo) {
            ObjectInfo curObjectInfo = (ObjectInfo) cur;
            if (objectFilter == null || objectFilter.test(curObjectInfo)) {
                result.append(curObjectInfo.getObject().getClass().getName())
                                .append("{")
                                .append(constantAsString(bb, curObjectInfo.getConstant())).append("} -> ");
            }
            prev = curObjectInfo;
            cur = curObjectInfo.getMainReason();
        }
        result.append(cur);
        return result.toString();
    }

    public void printObjectsReport(PrintWriter out) {
        String[] filters = NativeImageHeapGraphFeature.Options.ImageHeapObjectTypeFilter.getValue().split(",");
        String[] rootFilters = NativeImageHeapGraphFeature.Options.NativeImageHeapGraphRootFilter.getValue().split(",");
        List<ObjectInfo> objects = heap.getObjects().stream()
                        // TODO(mspasic): check if it's the graph first
                        // TODO(mspasic): this is wrong, I want to print the tree from object to
                        // root
                        .filter(o -> this.objectGraph.getNeighbours(o).size() == 1)
// .filter(o -> Arrays.stream(rootFilters).anyMatch(f -> o.getMainReason().toString().contains(f)))
                        .filter(o -> Arrays.stream(filters).anyMatch(f -> o.getObject().getClass().getName().contains(f)))
                        .filter(NativeImageHeapGraph::suppressInternalObjects)
                        .sorted(Comparator.comparingLong(ObjectInfo::getSize).reversed())
                        .collect(Collectors.toList());

        for (ObjectInfo objectInfo : objects) {
            out.println(makeReferenceChainString(bb, objectInfo, NativeImageHeapGraph::suppressInternalObjects));
            out.println(makeReferenceChainString(bb, objectInfo, null));
        }
    }

    public void printRoots(PrintWriter out) {
        for (ObjectInfo root : getObjectGraph().getRoots()) {
            out.println(makeReferenceChainString(bb, root, null));
        }
    }

    public void printConnectedComponentToGraphVizDotFormat(PrintWriter out) {
        out.println("digraph {");
        for (ConnectedComponent connectedComponent : connectedComponents) {
            for (ObjectInfo objectInfo : connectedComponent.getObjects()) {
                for (Object reason : objectInfo.getAllReasons()) {
                    out.printf("%s -> %d;\n", formatReasonForDotFile(reason), objectInfo.getIdentityHashCode());
                }
            }
        }
        out.println("}");
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

    private static String formatReasonForDotFile(Object reason) {
        if (reason instanceof String) {
            return String.format("\"%s\"", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return String.format("%d", r.getIdentityHashCode());
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return String.format("\"%s\"", r.getDeclaringClass().getName());
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return "Unhandled type in: NativeImageHeapGraph.formatReason([root]):179";
        }
    }

    private static String formatReason(Object reason) {
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

    private final String[] suppressed = {
    };

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
            SortedSet<String> objectInfos = new TreeSet<>();
            for (ObjectInfo object : objects) {
                for (Object reason : object.getAllReasons()) {
                    if (reason instanceof String) {
                        methodEntryPoints.add((String) reason);
                    } else if (reason instanceof HostedField) {
                        HostedField field = (HostedField) reason;
                        hostedFieldsEntryPoints.add(String.format("%s.%s of type %s", field.getDeclaringClass(), field.getName(), field.getType()));
                    } else if (reason instanceof ObjectInfo) {
                        objectInfos.add(reason.toString());
                    }
                }
            }
            ArrayList<String> result = new ArrayList<>(methodEntryPoints.size() + hostedFieldsEntryPoints.size());
            result.addAll(methodEntryPoints);
            result.addAll(hostedFieldsEntryPoints);
            result.addAll(objectInfos);
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
            return MB(size);
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
                boolean shouldReport = getRoot().getMainReason().toString().contains(pattern);
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

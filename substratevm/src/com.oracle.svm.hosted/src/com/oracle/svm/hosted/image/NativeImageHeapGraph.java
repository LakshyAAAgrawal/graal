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

import com.oracle.graal.pointsto.flow.SourceTypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;

/*
    Directed graph of Objects in the NativeImageHeap.
    Objects are enumerated as they are being added, starting from 0
 */
class DirectedGraph<Node> {
    class NodeData {
        public Set<Node> neighbours;
        public int nodeId = 0;

        public NodeData(int nodeId) {
            this.neighbours = Collections.newSetFromMap(new IdentityHashMap<>());
            this.nodeId = nodeId;
        }
    }

    protected final IdentityHashMap<Node, NodeData> nodes = new IdentityHashMap<>();
    protected final IdentityHashMap<Node, Set<Node>> parents = new IdentityHashMap<>();
    protected long numberOfEdges = 0;

    public NodeData addNode(Node a) {
        return nodes.computeIfAbsent(a, node -> new NodeData(nodes.size()));
    }

    public int inDegree(Node node) {
        Set<Node> parentNodes = parents.get(node);
        return parentNodes == null ? 0 : parentNodes.size();
    }

    public boolean hasParent(Node n) {
        return !parents.containsKey(n) || parents.get(n).isEmpty();
    }

    public int getNodeId(Node node) {
        NodeData nodeData = nodes.get(node);
        if (nodeData == null) {
            return -1;
        }
        return nodeData.nodeId;
    }

    public boolean connect(Node a, Node b) {
        if (a == null || b == null)
            return false;
        NodeData nodeData = addNode(a);
        numberOfEdges += nodeData.neighbours.add(b) ? 1 : 0;
        addNode(b);
        Set<Node> parentsOfNodeB = parents.computeIfAbsent(b, p -> Collections.newSetFromMap(new IdentityHashMap<>()));
        parentsOfNodeB.add(a);
        parents.computeIfAbsent(a, p -> Collections.newSetFromMap(new IdentityHashMap<>()));
        return true;
    }

    public Collection<Node> getNeighbours(Node a) {
        Set<Node> neighbours = nodes.get(a).neighbours;
        return neighbours != null ? neighbours : Collections.emptySet();
    }

    public int getNumberOfNodes() {
        return nodes.size();
    }

    public long getNumberOfEdges() {
        return numberOfEdges;
    }

    public Collection<Node> dfs(Node node, boolean[] visited, Consumer<Node> onVisit) {
        ArrayList<Node> path = new ArrayList<>();
        Stack<Node> stack = new Stack<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            Node currentNode = stack.pop();
            int currentNodeId = getNodeId(currentNode);
            if (visited[currentNodeId]) {
                continue;
            }
            onVisit.accept(currentNode);
            visited[currentNodeId] = true;
            path.add(currentNode);
            for (Node neighbour : getNeighbours(currentNode)) {
                if (!visited[getNodeId(neighbour)]) {
                    stack.push(neighbour);
                }
            }
        }
        return path;
    }

    public Collection<Collection<Node>> computeConnectedComponents() {
        ArrayList<Collection<Node>> components = new ArrayList<>();
        boolean[] visited = new boolean[nodes.size()];
        List<Node> traversalOrder = nodes.keySet().stream().filter(n -> !hasParent(n)).collect(Collectors.toList());
        for (Node node : traversalOrder) {
            Arrays.fill(visited, false);
            if (!visited[getNodeId(node)]) {
                components.add(dfs(node, visited, n -> {}));
            }
        }
        return components;
    }

    protected void dumpGraphBegin(PrintStream out) {
        out.println("digraph G0 {");
    }

    protected void dumpEdge(PrintStream out, long nodeIdFrom, long nodeIdTo) {
        out.printf("%d -> %d\n", nodeIdFrom, nodeIdTo);
    }

    protected void dumpGraphEnd(PrintStream out) {
        out.println("}");
    }

    public void dumpGraph(PrintStream out) {
        dumpGraphBegin(out);
        StringBuilder buffer = new StringBuilder();
        for (Map.Entry<Node, NodeData> nodeSetEntry : nodes.entrySet()) {
            Node root = nodeSetEntry.getKey();
            assert root != null;
            NodeData nodeData = nodeSetEntry.getValue();
            for (Node neighbour : nodeData.neighbours) {
                dumpEdge(out, getNodeId(root), getNodeId(neighbour));
            }
        }
        dumpGraphEnd(out);
    }
}

/*
 * Iterates through the NativeImageHeap objects and constructs a directed graph where each node in
 * the graph represents an Object and each edge represents a reference between objects. If object A
 * references an object B then in the graph there will be a node A that will have a neighbour node
 * B.
 * 
 * 
 */
public class NativeImageHeapGraph {
    private final DirectedGraph<Object> graph = new DirectedGraph<>();
    private IdentityHashMap<HostedMethod, Set<Object>> entryPointMethods = new IdentityHashMap<>();
    private ArrayList<Long> connectedComponentsSizes = new ArrayList<>();
    private final NativeImageHeap heap;
    private long totalHeapSize = 0;

    private static ArrayList<ObjectInfo> getAllReferencesToObjectInHeap(ObjectInfo objectInfo) {
        ArrayList<ObjectInfo> referencesInfo = new ArrayList<>();
        if (objectInfo.reason instanceof ObjectInfo) {
            referencesInfo.add((ObjectInfo) objectInfo.reason);
        }
        for (Object info : objectInfo.otherReasons) {
            if (info instanceof ObjectInfo) {
                referencesInfo.add((ObjectInfo) info);
            }
        }
        return referencesInfo;
    }

    private static IdentityHashMap<HostedMethod, Set<Object>> computeHeapEntryPointMethods(NativeImageHeap heap) {
        IdentityHashMap<HostedMethod, Set<Object>> result = new IdentityHashMap<>();
        Collection<HostedField> hostedFields = heap.getObjects().stream().filter(o -> o.reason instanceof HostedField).map(o -> (HostedField)o.reason).collect(Collectors.toList());
        for (HostedField hostedField : hostedFields) {
            try {
                AnalysisField wrapped = hostedField.getWrapped();
                if (wrapped != null) {

                }
            } catch (NullPointerException ignored) {

            }
        }
        return result;
    }

    private long computeComponentSize(Collection<Object> objects) {
        long sum = 0;
        for (Object object : objects) {
            ObjectInfo objectInfo = heap.getObjectInfo(object);
            sum += objectInfo.getSize();
        }
        return sum;
    }

    private void connectChildToParentObjects(ObjectInfo childObjectInfo) {
        ArrayList<ObjectInfo> referencesForObject = getAllReferencesToObjectInHeap(childObjectInfo);
        for (ObjectInfo parentObjectInfo : referencesForObject) {
            Object child = childObjectInfo.getObject();
            graph.connect(parentObjectInfo.getObject(), child);
        }
    }

    public NativeImageHeapGraph(NativeImageHeap heap) {
        this.heap = heap;
        this.totalHeapSize = this.heap.getObjects().stream().map(ObjectInfo::getSize).reduce(Long::sum).get();
        System.out.printf("Total Heap Size: %d\n", this.totalHeapSize);
        System.out.printf("Total number of objects in the heap: %d", this.heap.getObjects().size());
        System.out.println("NativeImageHeapGraph.NativeImageHeapGraph([heap]):205");
        for (ObjectInfo objectInfo : heap.getObjects()) { // typeof objectInfo.reason String,
                                                          // ObjectInfo, HostedField
            connectChildToParentObjects(objectInfo);
        }
        System.out.println("NativeImageHeapGraph.NativeImageHeapGraph([heap]):210");
        //this.entryPointMethods = computeHeapEntryPointMethods(heap);
        List<Long> componentsSizes = graph.computeConnectedComponents().stream().map(this::computeComponentSize).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        System.out.println("NativeImageHeapGraph.NativeImageHeapGraph([heap]):222");
        List<Double> componentsSizesFraction = componentsSizes.stream().map(o -> o.doubleValue() / this.totalHeapSize).collect(Collectors.toList());
        componentsSizesFraction.stream().limit(32).forEach(System.out::println);
        System.out.println("NativeImageHeapGraph.NativeImageHeapGraph([heap]):211");
        System.out.println(graph.getNumberOfNodes());
        System.out.println(graph.getNumberOfEdges());

        System.out.println("NativeImageHeapGraph.NativeImageHeapGraph([heap]):215:end");
    }
}

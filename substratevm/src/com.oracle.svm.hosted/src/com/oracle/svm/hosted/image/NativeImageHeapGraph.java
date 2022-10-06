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
import java.util.stream.Collectors;

import com.oracle.svm.core.JavaMemoryUtil;
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
        return true;
    }

    public Collection<Node> getNeighbours(Node a) {
        return nodes.get(a).neighbours;
    }

    public int getNumberOfNodes() {
        return nodes.size();
    }

    public long getNumberOfEdges() {
        return numberOfEdges;
    }

    public Collection<Node> dfs(Node node, boolean[] visited) {
        ArrayList<Node> path = new ArrayList<>();
        Stack<Node> stack = new Stack<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            Node currentNode = stack.pop();
            int currentNodeId = getNodeId(currentNode);
            if (visited[currentNodeId]) {
                continue;
            }
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
        List<Node> traversalOrder = nodes.keySet().stream()
                        .sorted((a, b) -> getNeighbours(b).size() - getNeighbours(a).size())
                        .collect(Collectors.toList());
        for (Node node : traversalOrder) {
            Arrays.fill(visited, false);
            if (!visited[getNodeId(node)]) {
                components.add(dfs(node, visited));
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
    private final IdentityHashMap<HostedMethod, Set<Object>> parentMethods = new IdentityHashMap<>();
    private final NativeImageHeap heap;

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

    private void computeRootAccesses() {

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
        for (ObjectInfo objectInfo : heap.getObjects()) { // typeof objectInfo.reason String,
                                                          // ObjectInfo, HostedField
            connectChildToParentObjects(objectInfo);
        }
        System.out.println(graph.getNumberOfNodes());
        System.out.println(graph.getNumberOfEdges());
    }
}

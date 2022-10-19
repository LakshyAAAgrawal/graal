package com.oracle.svm.hosted.image;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/*
    Directed graph of Objects in the NativeImageHeap.
    Objects are enumerated as they are being added, starting from 0
 */
public class DirectedGraph<Node> {
    class NodeData {
        public Set<Node> neighbours;
        public int nodeId = 0;

        public NodeData(int nodeId) {
            this.neighbours = Collections.newSetFromMap(new IdentityHashMap<>());
            this.nodeId = nodeId;
        }
    }

    protected final IdentityHashMap<Node, NodeData> nodes = new IdentityHashMap<>();
    protected final IdentityHashMap<Node, Boolean> isRoot = new IdentityHashMap<>();
    protected long numberOfEdges = 0;

    public NodeData addNode(Node a) {
        if (nodes.containsKey(a)) {
            return nodes.get(a);
        }
        isRoot.put(a, true);
        return nodes.computeIfAbsent(a, node -> new NodeData(nodes.size()));
    }

    public int getNodeId(Node node) {
        NodeData nodeData = nodes.get(node);
        if (nodeData == null) {
            return -1;
        }
        return nodeData.nodeId;
    }

    public boolean isRoot(Node node) {
        return isRoot.getOrDefault(node, false);
    }

    public boolean connect(Node a, Node b) {
        if (a == null || b == null)
            return false;
        NodeData nodeData = addNode(a);
        numberOfEdges += nodeData.neighbours.add(b) ? 1 : 0;
        addNode(b);
        isRoot.putIfAbsent(a, true);
        isRoot.put(b, false);
        return true;
    }

    public Set<Node> getNeighbours(Node a) {
        Set<Node> neighbours = nodes.get(a).neighbours;
        return neighbours != null ? neighbours : Collections.emptySet();
    }

    public int getNumberOfNodes() {
        return nodes.size();
    }

    public long getNumberOfEdges() {
        return numberOfEdges;
    }

    public Set<Node> getRoots() {
        Set<Node> roots = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<Node, Boolean> kv : isRoot.entrySet()) {
            if (kv.getValue()) {
                roots.add(kv.getKey());
            }
        }
        return roots;
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

    public static class DFSVisitor<Node> {
        private ArrayList<Node> path = new ArrayList<>();
        private DirectedGraph<Node> graph;

        public DFSVisitor(DirectedGraph<Node> graph) {
            this.graph = graph;
        }

        public static <T> DFSVisitor<T> create(DirectedGraph<T> graph) {
            return new DFSVisitor<>(graph);
        }

        public ArrayList<Node> dfs(Node start) {
            Stack<Node> stack = new Stack<>();
            boolean[] visited = new boolean[graph.getNumberOfNodes()];
            stack.add(start);
            while (!stack.isEmpty()) {
                Node currentNode = stack.pop();
                int currentNodeId = graph.getNodeId(currentNode);
                if (visited[currentNodeId]) {
                    continue;
                }
                visited[currentNodeId] = true;
                path.add(currentNode);
                for (Node neighbour : graph.getNeighbours(currentNode)) {
                    if (!visited[graph.getNodeId(neighbour)]) {
                        stack.push(neighbour);
                    }
                }
            }
            return path;
        }

        public ArrayList<Node> getVisitedNodes() {
            return path;
        }
    }
}

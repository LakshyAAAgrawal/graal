package com.oracle.svm.hosted.image;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

/*
    Directed graph of Objects in the NativeImageHeap.
    Objects are enumerated as they are being added, starting from 0
 */
public final class DirectedGraph<Node> {
    private class NodeData {
        public Set<Node> neighbours;
        public int nodeId = 0;

        public NodeData(int nodeId) {
            this.neighbours = Collections.newSetFromMap(new IdentityHashMap<>());
            this.nodeId = nodeId;
        }
    }

    private final IdentityHashMap<Node, NodeData> nodes = new IdentityHashMap<>();
    private final IdentityHashMap<Node, Boolean> isRoot = new IdentityHashMap<>();
    private long numberOfEdges = 0;

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

    private void dumpGraphBegin(PrintStream out) {
        out.println("digraph G0 {");
    }

    private void dumpEdge(PrintStream out, long nodeIdFrom, long nodeIdTo) {
        out.printf("%d -> %d\n", nodeIdFrom, nodeIdTo);
    }

    private void dumpGraphEnd(PrintStream out) {
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

    public NodeVisitor<Node> dfs(Node start, NodeVisitor<Node> nodeVisitor) {
        Stack<VisitorState<Node>> stack = new Stack<>();
        boolean[] visited = new boolean[getNumberOfNodes()];
        stack.add(new VisitorState<>(null, start, 0));
        while (!stack.isEmpty()) {
            VisitorState<Node> state = stack.pop();
            int currentNodeId = getNodeId(state.currentNode);
            if (visited[currentNodeId]) {
                continue;
            }
            visited[currentNodeId] = true;
            nodeVisitor.accept(this, state);
            if (nodeVisitor.shouldTerminateVisit()) {
                return nodeVisitor;
            }
            for (Node neighbour : getNeighbours(state.currentNode)) {
                if (!visited[getNodeId(neighbour)]) {
                    stack.push(new VisitorState<>(state.currentNode, neighbour, state.level + 1));
                }
            }
        }
        return nodeVisitor;
    }

    public NodeVisitor<Node> bfs(Node start, NodeVisitor<Node> nodeVisitor) {
        Queue<VisitorState<Node>> queue = new ArrayDeque<>();
        boolean[] visited = new boolean[getNumberOfNodes()];
        queue.add(new VisitorState<>(null, start, 0));
        while (!queue.isEmpty()) {
            VisitorState<Node> state = queue.poll();
            int currentNodeId = getNodeId(state.currentNode);
            if (visited[currentNodeId]) {
                continue;
            }
            visited[currentNodeId] = true;
            nodeVisitor.accept(this, state);
            if (nodeVisitor.shouldTerminateVisit()) {
                return nodeVisitor;
            }
            for (Node neighbour : getNeighbours(state.currentNode)) {
                if (!visited[getNodeId(neighbour)]) {
                    queue.offer(new VisitorState<>(state.currentNode, neighbour, state.level + 1));
                }
            }
        }
        return nodeVisitor;
    }

    interface NodeVisitor<Node> {
        void accept(DirectedGraph<Node> graph, VisitorState<Node> state);
        default boolean shouldTerminateVisit() { return false; }
    }

    public final static class VisitorState<Node> {
        public final Node parentNode;
        public final Node currentNode;
        public final int level;

        VisitorState(Node parent, Node current, int level) {
            this.parentNode = parent;
            this.currentNode = current;
            this.level = level;
        }
    }



}

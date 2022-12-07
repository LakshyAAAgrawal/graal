package com.oracle.svm.hosted.image;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

public abstract class AbstractGraph<Node> {
    protected final Map<Node, NodeData> nodes = hashMapInstance();
    protected <K,V> Map<K,V> hashMapInstance() {
        return new IdentityHashMap<>();
    }
    protected <K> Set<K> hashSetInstance() {
        return Collections.newSetFromMap(hashMapInstance());
    }
    protected long numberOfEdges = 0;

    public boolean inGraph(Node n) {
        return nodes.containsKey(n);
    }

    protected void doConnect(Map<Node, NodeData> nodeToData, Node from, Node to) {
        if (from == null || to == null)
            return;
        NodeData fromNodeData = addNode(nodeToData, from);
        addNode(nodeToData, to);
        boolean connectionExisted = !fromNodeData.getNeighbours().add(to);
        numberOfEdges += !connectionExisted ? 1 : 0;
    }

    public abstract void connect(Node a, Node b);

    public Set<Node> getRoots() {
        Set<Node> roots = hashSetInstance();
        for (Node node : nodes.keySet()) {
            if (isRoot(node))  {
                roots.add(node);
            }
        }
        return roots;
    }

    public void addNode(Node a) {
        addNode(nodes, a);
    }

    protected NodeData addNode(Map<Node, NodeData> nodeToData, Node a) {
        if (nodeToData.containsKey(a)) {
            return nodeToData.get(a);
        }
        return nodeToData.computeIfAbsent(a, node -> new NodeData(nodeToData.size()));
    }

    public Set<Node> getNodesSet() {
        return nodes.keySet();
    }

    public int getNodeId(Node node) {
        NodeData nodeData = nodes.get(node);
        if (nodeData == null) {
            return -1;
        }
        return nodeData.getNodeId();
    }


    public Set<Node> getNeighbours(Node a) {
        NodeData nodeData = nodes.get(a);
        if (nodeData == null) {
            return Collections.emptySet();
        }
        return nodeData.getNeighbours();
    }

    public abstract boolean isRoot(Node node);

    public int getNumberOfNodes() {
        return nodes.size();
    }

    public long getNumberOfEdges() {
        return numberOfEdges;
    }

    public <T extends NodeVisitor<Node>> T dfs(Node start, T nodeVisitor) {
        nodeVisitor.onStart();
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
            if (!nodeVisitor.shouldVisit(state.currentNode)) {
                continue;
            }
            nodeVisitor.accept(state);
            if (nodeVisitor.shouldTerminateVisit()) {
                break;
            }
            for (Node neighbour : getNeighbours(state.currentNode)) {
                if (!visited[getNodeId(neighbour)]) {
                    stack.push(new VisitorState<>(state.currentNode, neighbour, state.level + 1));
                }
            }
        }
        nodeVisitor.onEnd();
        return nodeVisitor;
    }

    interface NodeVisitor<Node> {
        void accept(VisitorState<Node> state);
        default void onStart() {}
        default void onEnd() {}
        default boolean shouldTerminateVisit() { return false; }
        @SuppressWarnings("unused")
        default boolean shouldVisit(Node node) { return true; }
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

    protected class NodeData {
        private final Set<Node> neighbours;
        private final int nodeId;

        public NodeData(int nodeId) {
            this.neighbours = hashSetInstance();
            this.nodeId = nodeId;
        }

        public Set<Node> getNeighbours() {
            return neighbours;
        }

        public int getNodeId() {
            return nodeId;
        }
    }
}

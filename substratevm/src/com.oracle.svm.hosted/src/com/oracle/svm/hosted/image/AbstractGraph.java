package com.oracle.svm.hosted.image;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

public abstract class AbstractGraph<Node> {

    protected final HashMap<Node, NodeData> nodes = new HashMap<>();

    protected long numberOfEdges = 0;

    protected void doConnect(Node from, Node to) {
        if (from == null || to == null)
            return;
        NodeData fromNodeData = addNode(from);
        addNode(to);
        boolean connectionExisted = !fromNodeData.getNeighbours().add(to);
        numberOfEdges += !connectionExisted ? 1 : 0;
    }

    public abstract void connect(Node a, Node b);

    public NodeData addNode(Node a) {
        if (nodes.containsKey(a)) {
            return nodes.get(a);
        }
        return nodes.computeIfAbsent(a, node -> new NodeData(nodes.size()));
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

    public List<Node> getLeaves() {
        List<Node> result = new ArrayList<>();
        for (Map.Entry<Node, NodeData> entry : this.nodes.entrySet())  {
            if (entry.getValue().getNeighbours().size() == 0) {
               result.add(entry.getKey());
            }
        }
        return result;
    }

    public int getNumberOfNodes() {
        return nodes.size();
    }

    public long getNumberOfEdges() {
        return numberOfEdges;
    }

    public <T extends NodeVisitor<Node>> T dfs(Node start, T nodeVisitor) {
        nodeVisitor.onStart(this);
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
        nodeVisitor.onEnd(this);
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
        void accept(AbstractGraph<Node> graph, VisitorState<Node> state);
        default void onStart(AbstractGraph<Node> graph) {}
        default void onEnd(AbstractGraph<Node> graph) {}
        default boolean shouldTerminateVisit() { return false; }
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
            this.neighbours = Collections.newSetFromMap(new HashMap<>());
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

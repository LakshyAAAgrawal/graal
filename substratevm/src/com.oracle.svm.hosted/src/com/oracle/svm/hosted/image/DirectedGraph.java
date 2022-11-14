package com.oracle.svm.hosted.image;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

public final class DirectedGraph<Node> extends AbstractGraph<Node> {
    final HashMap<Node, Boolean> nodeIsRoot = new HashMap<>();
    final HashMap<Node, Boolean> nodeIsLeaf = new HashMap<>();

    public void connect(Node a, Node b) {
        doConnect(a, b);
        nodeIsRoot.putIfAbsent(a, true);
        nodeIsRoot.put(b, false);
        nodeIsLeaf.putIfAbsent(b, true);
        nodeIsLeaf.put(a, false);
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
            for (Node neighbour : nodeData.getNeighbours()) {
                dumpEdge(out, getNodeId(root), getNodeId(neighbour));
            }
        }
        dumpGraphEnd(out);
    }

    public boolean isRoot(Node node) {
        return nodeIsRoot.getOrDefault(node, false);
    }
    public Set<Node> getRoots() {
        Set<Node> roots = Collections.newSetFromMap(new HashMap<>());
        for (Map.Entry<Node, Boolean> kv : nodeIsRoot.entrySet()) {
            if (kv.getValue()) {
                roots.add(kv.getKey());
            }
        }
        return roots;
    }
}

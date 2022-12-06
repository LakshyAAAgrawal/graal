package com.oracle.svm.hosted.image;

import java.util.Map;
import java.util.Set;

public final class DirectedGraph<Node> extends AbstractGraph<Node> {
    final Map<Node, Boolean> nodeIsRoot = hashMapInstance();
    final Map<Node, Boolean> nodeIsLeaf = hashMapInstance();
    final Map<Node, NodeData> parents = hashMapInstance();

    public void connect(Node a, Node b) {
        doConnect(nodes, a, b);
        doConnect(parents, b, a);
        nodeIsRoot.putIfAbsent(a, true);
        nodeIsRoot.put(b, false);
        nodeIsLeaf.putIfAbsent(b, true);
        nodeIsLeaf.put(a, false);
    }

    public boolean isRoot(Node node) {
        return nodeIsRoot.getOrDefault(node, false);
    }

    public Set<Node> getRoots() {
        Set<Node> roots = hashSetInstance();
        for (Map.Entry<Node, Boolean> kv : nodeIsRoot.entrySet()) {
            if (kv.getValue()) {
                roots.add(kv.getKey());
            }
        }
        return roots;
    }
}

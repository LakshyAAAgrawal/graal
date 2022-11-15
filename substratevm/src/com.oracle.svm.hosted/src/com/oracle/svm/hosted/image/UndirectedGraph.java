package com.oracle.svm.hosted.image;

public class UndirectedGraph<Node> extends AbstractGraph<Node> {
    @Override
    public void connect(Node a, Node b) {
        doConnect(nodes, a, b);
        doConnect(nodes, b, a);
    }

    @Override
    public boolean isRoot(Node node) {
        return nodes.containsKey(node);
    }
}

package com.oracle.svm.hosted.image;

public class UndirectedGraph<Node> extends AbstractGraph<Node> {
    @Override
    public void connect(Node a, Node b) {
        doConnect(a, b);
        doConnect(b, a);
    }
}

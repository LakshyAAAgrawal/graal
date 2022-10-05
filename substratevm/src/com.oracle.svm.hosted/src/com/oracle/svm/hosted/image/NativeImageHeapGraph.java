package com.oracle.svm.hosted.image;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;

abstract class AbstractGraph<Node> {
    protected final IdentityHashMap<Node, Set<Node>> adjList = new IdentityHashMap<>();
    protected long numberOfEdges = 0;
    public Set<Node> addNode(Node a) {
        return adjList.computeIfAbsent(a, node -> Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    public void connect(Node a, Node b) {
        Set<Node> adjNodes = addNode(a);
        numberOfEdges += adjNodes.add(b) ? 1 : 0;
        addNode(b);
    }
    public Collection<Node> getNeighbours(Node a) {
        return adjList.get(a);
    }
    public abstract Collection<Collection<Node>> computeConnectedComponents();
    public long getNumberOfNodes() {
        return adjList.size();
    }
    public long getNumberOfEdges() {
        return numberOfEdges;
    }

    protected abstract void dumpGraphBegin(PrintStream out);
    protected abstract void dumpEdge(PrintStream out, Node a, Node b);
    protected abstract void dumpGraphEnd(PrintStream out);

    public void dumpGraph(PrintStream out) {
        dumpGraphBegin(out);
        StringBuilder buffer = new StringBuilder();
        for (Map.Entry<Node, Set<Node>> nodeSetEntry : adjList.entrySet()) {
            Node root = nodeSetEntry.getKey();
            Set<Node> neighbours = nodeSetEntry.getValue();
            for (Node neighbour : neighbours) {
                dumpEdge(out, root, neighbour);
            }
        }
        dumpGraphEnd(out);
    }
}

class DirectedGraph<Node> extends AbstractGraph<Node> {
    @Override
    public Collection<Collection<Node>> computeConnectedComponents() {
        return null;
    }

    @Override
    protected void dumpGraphBegin(PrintStream out) {
        out.println("digraph G0 {");
    }

    @Override
    protected void dumpEdge(PrintStream out, Node a, Node b) {
        out.printf("%s -> %s\n", a.toString(), b.toString());
    }

    @Override
    protected void dumpGraphEnd(PrintStream out) {
        out.println("}");
    }
}

class UndirectedGraph<Node> extends AbstractGraph<Node> {
    @Override
    public Collection<Collection<Node>> computeConnectedComponents() {
        return null;
    }

    @Override
    protected void dumpGraphBegin(PrintStream out) {
        out.println("graph G0 {");
    }

    @Override
    protected void dumpEdge(PrintStream out, Node a, Node b) {
        out.printf("%s -- %s\n", a.toString(), b.toString());
    }

    @Override
    protected void dumpGraphEnd(PrintStream out) {
        out.println("}");
    }
}

public class NativeImageHeapGraph {
    private final AbstractGraph<Object> graph = new UndirectedGraph<>();
    private final HashMap<String, Set<Object>> methodToConstant = new HashMap<>();

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

    public NativeImageHeapGraph(NativeImageHeap heap) {
//        ArrayList<Pair<String, Object>> allReasons = new ArrayList<>();
        graph.connect("A", "B");
        graph.connect("B", "C");
        graph.connect("B", "D");
        graph.connect("C", "D");
        graph.connect("D", "A");
        graph.dumpGraph(System.out);
        for (ObjectInfo objectInfo : heap.getObjects()) {
            Object object = objectInfo.getObject();
            assert object != null;
            ArrayList<ObjectInfo> referencesForObject = getAllReferencesToObjectInHeap(objectInfo);
            referencesForObject.forEach(ref -> graph.connect(ref.getObject(), object));

//            if (objectInfo.reason instanceof String) { // root method name
//                graph.addNode(object);
//            } else if (objectInfo.reason instanceof ObjectInfo) {
//                ArrayList<ObjectInfo> referencesForObject = getAllReferencesToObjectInHeap(objectInfo);
//                referencesForObject.forEach(ref -> graph.connect(ref.getObject(), object));
//            } else if (objectInfo.reason instanceof HostedField) {
//                ArrayList<ObjectInfo> referencesForObject = getAllReferencesToObjectInHeap(objectInfo);
//            } else {
//                throw VMError.shouldNotReachHere("Unhandled reason of instance: " + objectInfo.reason.getClass().toString());
//            }
        }
        //allReasons.stream().sorted(Comparator.comparing(Pair::getLeft)).forEach((kv) -> System.out.printf("%s -> %s\n", kv.getLeft(), kv.getRight().getClass().toString()));
    }

}

package com.oracle.svm.hosted.image;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Set;

import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;

interface Graph<Node> {
    void addNode(Node a);
    void connect(Node a, Node b);
    Collection<Node> getNeighbours(Node a);
    Collection<Collection<Node>> computeConnectedComponents();
    long getNumberOfNodes();
    long getNumberOfEdges();
}

class DirectedGraph<Node> implements Graph<Node> {
    private final IdentityHashMap<Node, Set<Node>> adjList = new IdentityHashMap<>();
    long numberOfEdges = 0;

    @Override
    public void addNode(Node a) {
        adjList.computeIfAbsent(a, node -> Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @Override
    public void connect(Node a, Node b) {
        Set<Node> adjNodes = adjList.computeIfAbsent(a, node -> Collections.newSetFromMap(new IdentityHashMap<>()));
        numberOfEdges += adjNodes.add(b) ? 1 : 0;
        adjList.computeIfAbsent(b, node -> Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @Override
    public Collection<Node> getNeighbours(Node a) {
        return adjList.get(a);
    }

    @Override
    public Collection<Collection<Node>> computeConnectedComponents() {
        return null;
    }

    @Override
    public long getNumberOfNodes() {
        return adjList.size();
    }

    @Override
    public long getNumberOfEdges() {
        return numberOfEdges;
    }
}

class UndirectedGraph<Node> implements Graph<Node> {
    private final IdentityHashMap<Node, Set<Node>> adjList = new IdentityHashMap<>();
    long numberOfEdges = 0;

    @Override
    public void addNode(Node a) {
        adjList.computeIfAbsent(a, node -> Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @Override
    public void connect(Node a, Node b) {
        Set<Node> adjNodes = adjList.computeIfAbsent(a, node -> Collections.newSetFromMap(new IdentityHashMap<>()));
        numberOfEdges += adjNodes.add(b) ? 1 : 0;
        adjNodes = adjList.computeIfAbsent(b, node -> Collections.newSetFromMap(new IdentityHashMap<>()));
        adjNodes.add(a);
    }

    @Override
    public Collection<Node> getNeighbours(Node a) {
        return adjList.get(a);
    }

    @Override
    public Collection<Collection<Node>> computeConnectedComponents() {
        return null;
    }

    @Override
    public long getNumberOfNodes() {
        return 0;
    }

    @Override
    public long getNumberOfEdges() {
        return numberOfEdges;
    }
}

public class NativeImageHeapGraph {
    private final UndirectedGraph<Object> graph = new UndirectedGraph<>();
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

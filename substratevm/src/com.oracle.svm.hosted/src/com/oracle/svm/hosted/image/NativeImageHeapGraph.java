package com.oracle.svm.hosted.image;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;
import org.graalvm.collections.Pair;

public class NativeImageHeapGraph {
    private IdentityHashMap<Object, ArrayList<Object>> graph = new IdentityHashMap<>();
    private IdentityHashMap<Object, Object> parents = new IdentityHashMap<>();

    private void addEdges(List<Object> parents, Object child) {
        for (Object parent : parents) {
            if (!graph.containsKey(parent)) {
                graph.put(parent, new ArrayList<>());
            }
            graph.get(parent).add(child);
            this.parents.put(child, parent);
        }
    }

    private static ArrayList<Object> getAllParents(ObjectInfo info) {
        ArrayList<Object> parents = new ArrayList<>();
        parents.add(info.reason);
        parents.add(info.otherReasons);
        return parents;
    }

    public NativeImageHeapGraph(NativeImageHeap heap) {
        ArrayList<Pair<String, Object>> allReasons = new ArrayList<>();
        for (ObjectInfo objectInfo : heap.getObjects()) {
            Object object = objectInfo.getObject();
            assert object != null;
            if (objectInfo.reason instanceof String) { // root method name
                graph.put(object, new ArrayList<>());
                parents.put(object, null);
            } else if (objectInfo.reason instanceof ObjectInfo) {
                ArrayList<Object> parents = getAllParents(objectInfo);
                addEdges(parents, object);
            } else if (objectInfo.reason instanceof HostedField) {
                graph.put(object, new ArrayList<>());
                parents.put(object, null);
            } else {
                throw VMError.shouldNotReachHere("Unhandled reason of instance: " + objectInfo.reason.getClass().toString());
            }
        }
        computeParents();
        dumpStats();
        //allReasons.stream().sorted(Comparator.comparing(Pair::getLeft)).forEach((kv) -> System.out.printf("%s -> %s\n", kv.getLeft(), kv.getRight().getClass().toString()));
    }

    private void computeParents() {
        for (ArrayList<Object> children : graph.values()) {
            for (Object child : children) {
                parents.remove(child);
            }
        }
    }

    private void dumpStats() {
        System.out.println(parents.values().stream().filter(Objects::isNull).count());
    }
}

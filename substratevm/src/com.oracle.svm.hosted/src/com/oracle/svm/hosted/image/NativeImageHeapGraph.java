package com.oracle.svm.hosted.image;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;

public class NativeImageHeapGraph {
    private IdentityHashMap<Object, ArrayList<Object>> graph = new IdentityHashMap<>();

    private void addEdge(Object parent, Object child) {
        if (!graph.containsKey(parent)) {
            graph.put(parent, new ArrayList<>());
        }
        graph.get(parent).add(child);
    }

    private static ArrayList<Object> getAllParents(ObjectInfo info) {
        ArrayList<Object> parents = new ArrayList<>();
        parents.add(info.reason);
        parents.add(info.otherReasons);
        return parents;
    }

    public NativeImageHeapGraph(Collection<ObjectInfo> objects) {
        System.out.println("Making NativeImageHeapGraph");

        for (ObjectInfo objectInfo : objects) {
            Object object = objectInfo.getObject();
            assert object != null;
            if (objectInfo.reason instanceof String) {

            } else if (objectInfo.reason instanceof ObjectInfo) {

            } else if (objectInfo.reason instanceof HostedField) {

            } else {
                throw VMError.shouldNotReachHere("Unhandled reason of instance: " + objectInfo.reason.getClass().toString());
            }
        }
    }
}

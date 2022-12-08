/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.image;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Pair;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.svm.core.jdk.Resources;
import com.oracle.svm.core.jdk.resources.ResourceStorageEntry;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.Utils;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;

public class ImageHeapConnectedComponentsPrinter {
    private final NativeImageHeap heap;
    private final long totalHeapSizeInBytes;
    private final List<ConnectedComponent> connectedComponents;
    private final BigBang bb;
    private final String imageName;
    private final EnumMap<NativeImageHeap.ObjectReachability, GroupEntry> groups;

    private static class GroupEntry {
        final Set<ObjectInfo> objects;
        final long sizeInBytes;

        GroupEntry(Set<ObjectInfo> objects) {
            this.objects = objects;
            this.sizeInBytes = computeTotalSize(objects);
        }
    }

    public ImageHeapConnectedComponentsPrinter(NativeImageHeap heap, BigBang bigBang, AbstractImage image, String imageName) {
        this.heap = heap;
        this.imageName = imageName;
        this.totalHeapSizeInBytes = image.getImageHeapSize();
        this.bb = bigBang;
        this.groups = new EnumMap<>(NativeImageHeap.ObjectReachability.class);
        this.connectedComponents = computeConnectedComponents();
    }

    private static <T> Set<T> getHashSetInstance() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static boolean shouldIncludeObjectInTheReport(ObjectInfo objectInfo) {
        if (objectInfo.getMainReason().toString().equals("Filler object")) {
            return false;
        }
        return true;
    }

    private List<ConnectedComponent> computeConnectedComponents() {
        Set<ObjectInfo> allImageHeapObjects = getHashSetInstance();
        allImageHeapObjects.addAll(
                        heap.getObjects().stream()
                                        .filter(ImageHeapConnectedComponentsPrinter::shouldIncludeObjectInTheReport)
                                        .collect(Collectors.toList()));

        // The order matters.
        NativeImageHeap.ObjectReachability[] objectReachability = {
                        NativeImageHeap.ObjectReachability.Resources,
                        NativeImageHeap.ObjectReachability.InternedStringsTable,
                        NativeImageHeap.ObjectReachability.DynamicHubs,
                        NativeImageHeap.ObjectReachability.ImageCodeInfo,
                        NativeImageHeap.ObjectReachability.MethodOrStaticField
        };

        for (NativeImageHeap.ObjectReachability reachability : objectReachability) {
            Set<ObjectInfo> objects = removeObjectsBy(reachability, allImageHeapObjects, heap);
            groups.put(reachability, new GroupEntry(objects));
        }
        Graph<ObjectInfo> graph = constructGraph(groups.get(NativeImageHeap.ObjectReachability.MethodOrStaticField).objects);
        List<ConnectedComponent> result = new ArrayList<>(computeConnectedComponentsInGraph(graph));
        return result.stream()
                        .sorted(Comparator.comparing(ConnectedComponent::getSizeInBytes).reversed())
                        .collect(Collectors.toList());
    }

    private static List<ConnectedComponent> computeConnectedComponentsInGraph(Graph<ObjectInfo> graph) {
        ConnectedComponentsCollector collector = new ConnectedComponentsCollector(graph);
        for (ObjectInfo node : graph.getNodesSet()) {
            if (collector.isNotVisited(node)) {
                graph.dfs(node, collector);
            }
        }
        return collector.getListOfObjectsForEachComponent()
                        .stream()
                        .map(ConnectedComponent::new)
                        .collect(Collectors.toList());
    }

    private static Set<ObjectInfo> removeResources(Set<ObjectInfo> objects, NativeImageHeap heap) {
        Set<ObjectInfo> result = getHashSetInstance();
        EconomicMap<Pair<String, String>, ResourceStorageEntry> resources = Resources.singleton().resources();
        for (ResourceStorageEntry value : resources.getValues()) {
            for (byte[] arr : value.getData()) {
                ObjectInfo info = heap.getObjectInfo(arr);
                if (info != null) {
                    objects.remove(info);
                    result.add(info);
                }
            }
        }
        return result;
    }

    private static Set<ObjectInfo> removeObjectsBy(NativeImageHeap.ObjectReachability reason, Set<ObjectInfo> objects, NativeImageHeap heap) {
        if (reason == NativeImageHeap.ObjectReachability.Resources) {
            return removeResources(objects, heap);
        }
        Set<ObjectInfo> result = getHashSetInstance();
        if (reason == NativeImageHeap.ObjectReachability.InternedStringsTable) {
            for (ObjectInfo info : objects) {
                if (info.isInternedStringsTable()) {
                    result.add(info);
                    objects.remove(info);
                    return result;
                }
            }
        }
        for (Iterator<ObjectInfo> iterator = objects.iterator(); iterator.hasNext();) {
            ObjectInfo o = iterator.next();
            if (o.belongsTo(reason)) {
                result.add(o);
                iterator.remove();
            }
        }
        return result;
    }

    private static Graph<ObjectInfo> constructGraph(Set<ObjectInfo> objects) {
        Graph<ObjectInfo> graph = new Graph<>();
        for (ObjectInfo objectInfo : objects) {
            graph.addNode(objectInfo);
            for (Object referencesToThisObject : objectInfo.getAllReasons()) {
                if (referencesToThisObject instanceof ObjectInfo && objects.contains(referencesToThisObject)) {
                    graph.connect((ObjectInfo) referencesToThisObject, objectInfo);
                }
            }
        }
        return graph;
    }

    private static long computeTotalSize(Collection<ObjectInfo> objects) {
        long sum = 0;
        for (ObjectInfo object : objects) {
            sum += object.getSize();
        }
        return sum;
    }

    public void printAccessPoints(PrintWriter out) {
        TreeSet<String> entryPoints = new TreeSet<>();
        for (int i = 0, connectedComponentsSize = connectedComponents.size(); i < connectedComponentsSize; i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            getMethodAccesses(connectedComponent.getObjects()).forEach(h -> entryPoints.add(formatReason(h)));
            getHostedFieldsAccess(connectedComponent.getObjects()).forEach(h -> entryPoints.add(formatReason(h)));
            for (String entryPoint : entryPoints) {
                out.printf("ComponentId=%d=%s\n", i, entryPoint);
            }
            entryPoints.clear();
        }
    }

    public void printObjectsForEachComponent(PrintWriter out) {
        out.println("ConnectedComponentId=ObjectInfo(objects class, objects identity hash code, constant value, reason)");
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            for (ObjectInfo info : connectedComponent.getObjects()) {
                out.printf("ComponentId=%d=%s\n", i, formatObject(info));
            }
        }
        printObjectsInGroup(out, NativeImageHeap.ObjectReachability.DynamicHubs);
        printObjectsInGroup(out, NativeImageHeap.ObjectReachability.ImageCodeInfo);
        printObjectsInGroup(out, NativeImageHeap.ObjectReachability.Resources);
    }

    private void printObjectsInGroup(PrintWriter out, NativeImageHeap.ObjectReachability objectGroup) {
        for (ObjectInfo objectInfo : groups.get(objectGroup).objects) {
            out.printf("ObjectGroup=%s=%s\n", objectGroup, formatObject(objectInfo));
        }
    }

    public void printConnectedComponents(PrintWriter out) {
        String title = "Native image heap connected components report";
        out.println(fillHeading(title));
        out.println(fillHeading(imageName));
        out.printf("Total Heap Size: %s\n", Utils.bytesToHuman(totalHeapSizeInBytes));
        long imageCodeInfoSizeInBytes = groups.get(NativeImageHeap.ObjectReachability.ImageCodeInfo).sizeInBytes;
        long dynamicHubsSizeInBytes = groups.get(NativeImageHeap.ObjectReachability.DynamicHubs).sizeInBytes;
        long internedStringsSizeInBytes = groups.get(NativeImageHeap.ObjectReachability.InternedStringsTable).sizeInBytes;
        long resourcesSizeInBytes = groups.get(NativeImageHeap.ObjectReachability.Resources).sizeInBytes;
        long theRest = totalHeapSizeInBytes - dynamicHubsSizeInBytes - internedStringsSizeInBytes - imageCodeInfoSizeInBytes - resourcesSizeInBytes;
        out.printf("\tImage code info size: %s\n", Utils.bytesToHuman(imageCodeInfoSizeInBytes));
        out.printf("\tDynamic hubs size: %s\n", Utils.bytesToHuman(dynamicHubsSizeInBytes));
        out.printf("\tInterned strings table size: %s\n", Utils.bytesToHuman(internedStringsSizeInBytes));
        out.printf("\tResources byte arrays size: %s\n", Utils.bytesToHuman(resourcesSizeInBytes));
        out.printf("\tIn connected components report: %s\n", Utils.bytesToHuman(theRest));
        out.printf("Total number of objects in the heap: %d\n", this.heap.getObjects().size());
        out.printf("Number of connected components in the report %d", this.connectedComponents.size());
        for (int i = 0; i < connectedComponents.size(); i++) {
            ConnectedComponent connectedComponent = connectedComponents.get(i);
            float percentageOfTotalHeapSize = 100.0f * connectedComponent.getSizeInBytes() /
                            this.totalHeapSizeInBytes;
            HeapHistogram objectHistogram = new HeapHistogram(out);
            connectedComponent.getObjects().forEach(o -> objectHistogram.add(o, o.getSize()));
            String headingInfo = String.format("ComponentId=%d | Size=%s | Percentage of total image heap size=%.4f%%", i,
                            Utils.bytesToHuman(connectedComponent.getSizeInBytes()),
                            percentageOfTotalHeapSize);

            out.println();
            String fullHeading = fillHeading(headingInfo);
            objectHistogram.printHeadings(String.format("%s\n%s", "=".repeat(fullHeading.length()), fullHeading));
            objectHistogram.print();

            Collection<ObjectInfo> roots = connectedComponent.getObjects();
            Set<String> methods = getMethodAccesses(roots);
            Set<HostedField> staticFields = getHostedFieldsAccess(roots);

            int entryPointLimit = 10;
            if (!staticFields.isEmpty()) {
                out.printf("\nStatic fields accessing Component %d:\n", i);
                for (HostedField field : staticFields.stream().limit(entryPointLimit).collect(Collectors.toList())) {
                    out.printf("\t%s\n", field.format("%H#%n"));
                }
                if (staticFields.size() > entryPointLimit) {
                    out.printf("\t... %d more in the access_points report\n", staticFields.size() - entryPointLimit);
                }
            }
            if (!methods.isEmpty()) {
                out.printf("\nMethods accessing connected component %d:\n", i);
                for (String methodName : methods.stream().limit(entryPointLimit).collect(Collectors.toList())) {
                    out.printf("\t%s\n", formatMethodAsLink(methodName));
                }
                if (methods.size() > entryPointLimit) {
                    out.printf("\t... %d more in the access_points report\n", methods.size() - entryPointLimit);
                }
            }
        }

        NativeImageHeap.ObjectReachability[] headerGroups = {
                        NativeImageHeap.ObjectReachability.DynamicHubs,
                        NativeImageHeap.ObjectReachability.ImageCodeInfo,
                        NativeImageHeap.ObjectReachability.Resources
        };

        for (NativeImageHeap.ObjectReachability groupType : headerGroups) {
            HeapHistogram objectHistogram = new HeapHistogram(out);
            GroupEntry groupEntry = groups.get(groupType);
            groupEntry.objects.forEach(o -> objectHistogram.add(o, o.getSize()));
            float percentageOfTotalHeapSize = 100.0f * groupEntry.sizeInBytes / this.totalHeapSizeInBytes;
            String headingInfo = String.format("Group=%s | Size=%s | Percentage of total image heap size=%.4f%%", groupType,
                            Utils.bytesToHuman(groups.get(groupType).sizeInBytes),
                            percentageOfTotalHeapSize);
            out.println();
            String fullHeading = fillHeading(headingInfo);
            objectHistogram.printHeadings(String.format("%s\n%s", "=".repeat(fullHeading.length()), fullHeading));
            objectHistogram.print();
        }
    }

    private static final int HEADING_WIDTH = 140;

    private static String fillHeading(String title) {
        String fill = "=".repeat(Math.max(HEADING_WIDTH - title.length(), 8) / 2);
        return String.format("%s %s %s%s", fill, title, fill, title.length() % 2 == 0 ? "" : "=");
    }

    private static Set<String> getMethodAccesses(Collection<ObjectInfo> objects) {
        Set<String> methods = new TreeSet<>();
        for (ObjectInfo object : objects) {
            for (Object reason : object.getAllReasons()) {
                if (reason instanceof String) {
                    if (reason.equals("dataSection") || reason.equals("staticObjectFields") || reason.equals("staticPrimitiveFields")) {
                        continue;
                    }
                    methods.add((String) reason);
                }
            }
        }
        return methods;
    }

    private static String formatMethodAsLink(String method) {
        int lastDot = method.lastIndexOf(".");
        if (lastDot != -1) {
            return method.substring(0, lastDot) + '#' + method.substring(lastDot + 1);
        } else {
            return method;
        }
    }

    private static Set<HostedField> getHostedFieldsAccess(Collection<ObjectInfo> objects) {
        Set<HostedField> hostedFields = getHashSetInstance();
        for (ObjectInfo object : objects) {
            for (Object reason : object.getAllReasons()) {
                if (reason instanceof HostedField) {
                    hostedFields.add((HostedField) reason);
                }
            }
        }
        return hostedFields;
    }

    private static String formatReason(Object reason) {
        if (reason instanceof String) {
            return String.format("Method(%s)", reason);
        } else if (reason instanceof ObjectInfo) {
            ObjectInfo r = (ObjectInfo) reason;
            return r.toString();
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return r.format("StaticField(class %H { static %t %n; })");
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return null;
        }
    }

    private String formatObject(ObjectInfo objectInfo) {
        return String.format("ObjectInfo(class %s, %d, %s, %s)", objectInfo.getObject().getClass().getName(), objectInfo.getIdentityHashCode(), constantAsString(bb, objectInfo.getConstant()),
                        formatReason(objectInfo.getMainReason()));
    }

    private static Object constantAsObject(BigBang bb, JavaConstant constant) {
        return bb.getSnippetReflectionProvider().asObject(Object.class, constant);
    }

    private static String escape(String str) {
        return str.replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\"\"");
    }

    private static String constantAsString(BigBang bb, JavaConstant constant) {
        Object object = constantAsObject(bb, constant);
        if (object instanceof String) {
            String str = (String) object;
            str = "\"" + escape(str) + "\"";
            return str;
        } else {
            return escape(JavaKind.Object.format(object));
        }
    }

    private static final class ConnectedComponentsCollector implements Graph.NodeVisitor<ObjectInfo> {
        private final Graph<ObjectInfo> graph;
        private final List<List<ObjectInfo>> connectedComponents = new ArrayList<>();
        private final boolean[] visited;
        private int componentNum = 0;

        ConnectedComponentsCollector(Graph<ObjectInfo> graph) {
            this.visited = new boolean[graph.getNumberOfNodes()];
            this.graph = graph;
        }

        @Override
        public void onStart() {
            connectedComponents.add(new ArrayList<>());
        }

        @Override
        public void accept(Graph.VisitorState<ObjectInfo> state) {
            int nodeId = graph.getNodeId(state.currentNode);
            this.visited[nodeId] = true;
            connectedComponents.get(componentNum).add(state.currentNode);
        }

        @Override
        public void onEnd() {
            ++componentNum;
        }

        @Override
        public boolean shouldVisit(ObjectInfo objectInfo) {
            return !this.visited[graph.getNodeId(objectInfo)];
        }

        public boolean isNotVisited(ObjectInfo info) {
            int id = graph.getNodeId(info);
            if (id == -1) {
                return false;
            }
            return !this.visited[id];
        }

        public List<List<ObjectInfo>> getListOfObjectsForEachComponent() {
            return connectedComponents;
        }
    }

    private static final class ConnectedComponent {
        private final List<ObjectInfo> objects;
        private final long size;

        ConnectedComponent(List<ObjectInfo> objects) {
            this.objects = objects;
            this.size = computeComponentSize(objects);
        }

        private static long computeComponentSize(List<ObjectInfo> objects) {
            long totalSize = 0L;
            for (ObjectInfo o : objects) {
                totalSize += o.getSize();
            }
            return totalSize;
        }

        public long getSizeInBytes() {
            return size;
        }

        public List<ObjectInfo> getObjects() {
            return objects;
        }
    }
}

class Graph<Node> {
    protected final Map<Node, NodeData> nodes = hashMapInstance();

    protected <K, V> Map<K, V> hashMapInstance() {
        return new IdentityHashMap<>();
    }

    protected <K> Set<K> hashSetInstance() {
        return Collections.newSetFromMap(hashMapInstance());
    }

    private void doConnect(Node from, Node to) {
        if (from == null || to == null) {
            throw VMError.shouldNotReachHere("Trying to connect null");
        }
        NodeData fromNodeData = addNode(from);
        addNode(to);
        fromNodeData.getNeighbours().add(to);
    }

    void connect(Node a, Node b) {
        doConnect(a, b);
        doConnect(b, a);
    }

    NodeData addNode(Node a) {
        if (nodes.containsKey(a)) {
            return nodes.get(a);
        }
        return nodes.computeIfAbsent(a, node -> new NodeData(nodes.size()));
    }

    Set<Node> getNodesSet() {
        return nodes.keySet();
    }

    int getNodeId(Node node) {
        NodeData nodeData = nodes.get(node);
        if (nodeData == null) {
            return -1;
        }
        return nodeData.getNodeId();
    }

    Set<Node> getNeighbours(Node a) {
        NodeData nodeData = nodes.get(a);
        if (nodeData == null) {
            return Collections.emptySet();
        }
        return nodeData.getNeighbours();
    }

    int getNumberOfNodes() {
        return nodes.size();
    }

    <T extends NodeVisitor<Node>> T dfs(Node start, T nodeVisitor) {
        nodeVisitor.onStart();
        ArrayList<VisitorState<Node>> stack = new ArrayList<>();
        boolean[] visited = new boolean[getNumberOfNodes()];
        stack.add(new VisitorState<>(null, start, 0));
        while (!stack.isEmpty()) {
            VisitorState<Node> state = stack.remove(stack.size() - 1);
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
                    stack.add(new VisitorState<>(state.currentNode, neighbour, state.level + 1));
                }
            }
        }
        nodeVisitor.onEnd();
        return nodeVisitor;
    }

    interface NodeVisitor<Node> {
        void accept(VisitorState<Node> state);

        default void onStart() {
        }

        default void onEnd() {
        }

        default boolean shouldTerminateVisit() {
            return false;
        }

        @SuppressWarnings("unused")
        default boolean shouldVisit(Node node) {
            return true;
        }
    }

    static final class VisitorState<Node> {
        final Node parentNode;
        final Node currentNode;
        final int level;

        VisitorState(Node parent, Node current, int level) {
            this.parentNode = parent;
            this.currentNode = current;
            this.level = level;
        }
    }

    private final class NodeData {
        private final Set<Node> neighbours;
        private final int nodeId;

        private NodeData(int nodeId) {
            this.neighbours = hashSetInstance();
            this.nodeId = nodeId;
        }

        private Set<Node> getNeighbours() {
            return neighbours;
        }

        private int getNodeId() {
            return nodeId;
        }
    }
}

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
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.svm.core.jdk.Resources;
import com.oracle.svm.core.jdk.resources.ResourceStorageEntry;
import com.oracle.svm.hosted.Utils;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Pair;

import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedField;

public class ImageHeapConnectedComponentsPrinter {
    private final NativeImageHeap heap;
    private final long totalHeapSizeInBytes;
    private final List<ConnectedComponent> connectedComponents;
    private final BigBang bb;
    private final String imageName;

    private static class GroupEntry {
        public final Set<ObjectInfo> objects;
        public final long sizeInBytes;

        public GroupEntry(Set<ObjectInfo> objects) {
            this.objects = objects;
            this.sizeInBytes = computeTotalSize(objects);
        }
    }

    private final EnumMap<NativeImageHeap.ObjectGroupRoot, GroupEntry> groups;

    private static AbstractGraph<ObjectInfo> getGraphInstance() {
        return new UndirectedGraph<>();
    }

    public ImageHeapConnectedComponentsPrinter(NativeImageHeap heap, BigBang bigBang, AbstractImage image, String imageName) {
        this.heap = heap;
        this.imageName = imageName;
        this.totalHeapSizeInBytes = image.getImageHeapSize();
        this.bb = bigBang;
        this.groups = new EnumMap<>(NativeImageHeap.ObjectGroupRoot.class);
        this.connectedComponents = computeConnectedComponents(this.heap);
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

    private List<ConnectedComponent> computeConnectedComponents(NativeImageHeap heap) {
        Set<ObjectInfo> allImageHeapObjects = getHashSetInstance();
        allImageHeapObjects.addAll(
                        heap.getObjects().stream()
                                        .filter(ImageHeapConnectedComponentsPrinter::shouldIncludeObjectInTheReport)
                                        .collect(Collectors.toList()));

        // The order matters.
        NativeImageHeap.ObjectGroupRoot[] objectGroupRoots = {
                        NativeImageHeap.ObjectGroupRoot.Resources,
                        NativeImageHeap.ObjectGroupRoot.InternedStringsTable,
                        NativeImageHeap.ObjectGroupRoot.DynamicHubs,
                        NativeImageHeap.ObjectGroupRoot.ImageCodeInfo,
                        NativeImageHeap.ObjectGroupRoot.MethodOrHostedField
        };

        for (NativeImageHeap.ObjectGroupRoot objectGroupRoot : objectGroupRoots) {
            Set<ObjectInfo> objects = removeObjectsBy(objectGroupRoot, allImageHeapObjects, heap);
            groups.put(objectGroupRoot, new GroupEntry(objects));
        }
        AbstractGraph<ObjectInfo> graph = constructGraph(groups.get(NativeImageHeap.ObjectGroupRoot.MethodOrHostedField).objects);
        List<ConnectedComponent> connectedComponents = new ArrayList<>(computeConnectedComponentsInGraph(graph));
        return connectedComponents.stream()
                        .sorted(Comparator.comparing(ConnectedComponent::getSizeInBytes).reversed())
                        .collect(Collectors.toList());
    }

    private List<ConnectedComponent> computeConnectedComponentsInGraph(AbstractGraph<ObjectInfo> graph) {
        ConnectedComponentsCollector collector = new ConnectedComponentsCollector(graph);
        for (ObjectInfo node : graph.getRoots()) {
            if (collector.isNotVisited(node)) {
                graph.dfs(node, collector);
            }
        }
        return collector.getListOfObjectsForEachComponent()
                        .stream()
                        .map(objectsForComponent -> new ConnectedComponent(objectsForComponent, this.heap))
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

    private static Set<ObjectInfo> removeObjectsBy(NativeImageHeap.ObjectGroupRoot reason, Set<ObjectInfo> objects, NativeImageHeap heap) {
        if (reason == NativeImageHeap.ObjectGroupRoot.Resources) {
            return removeResources(objects, heap);
        }
        Set<ObjectInfo> result = getHashSetInstance();
        if (reason == NativeImageHeap.ObjectGroupRoot.InternedStringsTable) {
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

    private AbstractGraph<ObjectInfo> constructGraph(Set<ObjectInfo> objects) {
        AbstractGraph<ObjectInfo> graph = getGraphInstance();
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
                out.printf("ComponentId=%d=%s\n", i, formatObject(info, bb));
            }
        }
        printObjectsInGroup(out, NativeImageHeap.ObjectGroupRoot.DynamicHubs);
        printObjectsInGroup(out, NativeImageHeap.ObjectGroupRoot.ImageCodeInfo);
        printObjectsInGroup(out, NativeImageHeap.ObjectGroupRoot.Resources);
    }

    private void printObjectsInGroup(PrintWriter out, NativeImageHeap.ObjectGroupRoot objectGroup) {
        for (ObjectInfo objectInfo : groups.get(objectGroup).objects) {
            out.printf("ObjectGroup=%s=%s\n", objectGroup, formatObject(objectInfo, bb));
        }
    }

    public void printConnectedComponents(PrintWriter out) {
        String title = "Native image heap connected components report";
        out.println(fillHeading(title));
        out.println(fillHeading(imageName));
        out.printf("Total Heap Size: %s\n", Utils.bytesToHuman(totalHeapSizeInBytes));
        long imageCodeInfoSizeInBytes = groups.get(NativeImageHeap.ObjectGroupRoot.ImageCodeInfo).sizeInBytes;
        long dynamicHubsSizeInBytes = groups.get(NativeImageHeap.ObjectGroupRoot.DynamicHubs).sizeInBytes;
        long internedStringsSizeInBytes = groups.get(NativeImageHeap.ObjectGroupRoot.InternedStringsTable).sizeInBytes;
        long resourcesSizeInBytes = groups.get(NativeImageHeap.ObjectGroupRoot.Resources).sizeInBytes;
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
            if (connectedComponent.getObjects().get(0).belongsTo(NativeImageHeap.ObjectGroupRoot.ImageCodeInfo)) {
                continue;
            }
            float percentageOfTotalHeapSize = 100.0f * (float) connectedComponent.getSizeInBytes() /
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
                    out.printf("\t... %d more in the entry points report\n", staticFields.size() - entryPointLimit);
                }
            }
            if (!methods.isEmpty()) {
                out.printf("\nMethods accessing connected component %d:\n", i);
                for (String methodName : methods.stream().limit(entryPointLimit).collect(Collectors.toList())) {
                    out.printf("\t%s\n", formatMethodAsLink(methodName));
                }
                if (methods.size() > entryPointLimit) {
                    out.printf("\t... %d more in the access_points_* report\n", methods.size() - entryPointLimit);
                }
            }
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
                    if (reason.equals("dataSection") || reason.equals("staticObjectFields") || reason.equals("staticPrimitiveFields"))
                        continue;
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
//            return String.format("ObjectInfo(class %s, %d, %s)", r.getObject().getClass().getName(), r.getIdentityHashCode(), r);
        } else if (reason instanceof HostedField) {
            HostedField r = (HostedField) reason;
            return r.format("StaticField(class %H { static %t %n; })");
        } else {
            VMError.shouldNotReachHere("Unhandled type");
            return null;
        }
    }

    private String formatObject(ObjectInfo objectInfo, BigBang bb) {
        return String.format("ObjectInfo(class %s, %d, %s, %s)", objectInfo.getObject().getClass().getName(), objectInfo.getIdentityHashCode(), constantAsString(bb, objectInfo.getConstant()), formatReason(objectInfo.getMainReason()));
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

    private static final class ConnectedComponentsCollector implements AbstractGraph.NodeVisitor<ObjectInfo> {
        private final AbstractGraph<ObjectInfo> graph;
        private final List<List<ObjectInfo>> connectedComponents = new ArrayList<>();
        private final boolean[] visited;
        private int componentId = 0;

        public ConnectedComponentsCollector(AbstractGraph<ObjectInfo> graph) {
            this.visited = new boolean[graph.getNumberOfNodes()];
            this.graph = graph;
        }

        @Override
        public void onStart(AbstractGraph<ObjectInfo> graph) {
            connectedComponents.add(new ArrayList<>());
        }

        @Override
        public void accept(AbstractGraph<ObjectInfo> graph, AbstractGraph.VisitorState<ObjectInfo> state) {
            int nodeId = graph.getNodeId(state.currentNode);
            this.visited[nodeId] = true;
            connectedComponents.get(componentId).add(state.currentNode);
        }

        @Override
        public void onEnd(AbstractGraph<ObjectInfo> graph) {
            ++componentId;
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

    private final static class ConnectedComponent {
        private final List<ObjectInfo> objects;
        private final long size;
        private final Set<Object> reasons;

        public ConnectedComponent(List<ObjectInfo> objects, NativeImageHeap heap) {
            this.objects = objects;
            this.size = computeComponentSize(objects);
            this.reasons = Collections.newSetFromMap(new IdentityHashMap<>());
        }

        public Set<Object> getReasons() {
            return reasons;
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
package io.etclovg.codepilot.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class DagDecomposer {

    private static final Logger log = LoggerFactory.getLogger(DagDecomposer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DecomposeFunction decomposeFunc;
    private final AnalyzeFunction analyzeFunc;
    private final RoutingFunction routingFunc;

    private DagDecomposer(DagDecomposerBuilder builder) {
        this.decomposeFunc = builder.decomposeFunc;
        this.analyzeFunc = builder.analyzeFunc;
        this.routingFunc = builder.routingFunc;
    }

    public static DagDecomposerBuilder builder() {
        return new DagDecomposerBuilder();
    }

    public Dag decompose(TaskDescription task) {
        return decomposeFunc.decompose(task);
    }

    public DagMetrics analyze(Dag dag) {
        return analyzeFunc.analyze(dag);
    }

    public Topology route(DagMetrics metrics) {
        return routingFunc.route(metrics);
    }

    @FunctionalInterface
    public interface DecomposeFunction {
        Dag decompose(TaskDescription task);
    }

    @FunctionalInterface
    public interface AnalyzeFunction {
        DagMetrics analyze(Dag dag);
    }

    @FunctionalInterface
    public interface RoutingFunction {
        Topology route(DagMetrics metrics);
    }

    public enum Topology {
        PARALLEL,
        HIERARCHICAL,
        SEQUENTIAL,
        HYBRID
    }

    public record TaskDescription(String description) {}

    public record DagNode(String id, String description, Set<String> dependencies) {
        public DagNode {
            dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
        }
    }

    public record DagEdge(String from, String to) {}

    public record DagMetrics(int omega, int delta, double gamma) {}

    public static class Dag {
        private final List<DagNode> nodes;
        private final List<DagEdge> edges;

        public Dag(List<DagNode> nodes, List<DagEdge> edges) {
            this.nodes = new ArrayList<>(nodes);
            this.edges = new ArrayList<>(edges);
        }

        public static Dag fromJson(String json) {
            try {
                Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
                List<Map<String, Object>> nodeMaps = (List<Map<String, Object>>) map.getOrDefault("nodes", List.of());
                List<Map<String, Object>> edgeMaps = (List<Map<String, Object>>) map.getOrDefault("edges", List.of());

                List<DagNode> nodes = new ArrayList<>();
                for (Map<String, Object> nm : nodeMaps) {
                    String id = String.valueOf(nm.get("id"));
                    String desc = String.valueOf(nm.getOrDefault("description", ""));
                    List<String> depsList = (List<String>) nm.getOrDefault("dependencies", List.of());
                    Set<String> deps = new LinkedHashSet<>(depsList);
                    nodes.add(new DagNode(id, desc, deps));
                }

                List<DagEdge> edges = new ArrayList<>();
                for (Map<String, Object> em : edgeMaps) {
                    String from = String.valueOf(em.get("from"));
                    String to = String.valueOf(em.get("to"));
                    edges.add(new DagEdge(from, to));
                }

                return new Dag(nodes, edges);
            } catch (Exception e) {
                log.warn("Dag.fromJson failed, returning empty Dag: {}", e.getMessage());
                return new Dag(List.of(), List.of());
            }
        }

        public List<DagNode> nodes() {
            return Collections.unmodifiableList(nodes);
        }

        public List<DagEdge> edges() {
            return Collections.unmodifiableList(edges);
        }

        public int maxParallelWidth() {
            List<List<String>> groups = getParallelGroups();
            return groups.stream().mapToInt(List::size).max().orElse(0);
        }

        public int criticalPathLength() {
            Map<String, DagNode> nodeMap = new HashMap<>();
            for (DagNode n : nodes) nodeMap.put(n.id(), n);

            Map<String, Integer> depth = new HashMap<>();
            int maxLen = 0;
            for (String id : topologicalOrder()) {
                DagNode node = nodeMap.get(id);
                int depMax = 0;
                if (node != null) {
                    for (String dep : node.dependencies()) {
                        depMax = Math.max(depMax, depth.getOrDefault(dep, 0));
                    }
                }
                int len = depMax + 1;
                depth.put(id, len);
                maxLen = Math.max(maxLen, len);
            }
            return maxLen;
        }

        public double couplingDensity() {
            if (nodes.isEmpty()) return 0.0;
            int n = nodes.size();
            // Book formula: gamma = 2(E - N + 1) / ((N - 1)(N - 2))
            // Measures excess edges beyond minimum spanning tree (N-1),
            // normalized by max possible excess edges.
            if (n <= 2) return 0.0;
            int minEdges = n - 1;
            int excessEdges = Math.max(0, edges.size() - minEdges);
            int maxExcess = (n - 1) * (n - 2) / 2;
            if (maxExcess == 0) return 0.0;
            return (double) excessEdges / maxExcess;
        }

        public List<String> topologicalOrder() {
            Map<String, Integer> inDegree = new LinkedHashMap<>();
            Map<String, List<String>> adjacency = new LinkedHashMap<>();

            for (DagNode node : nodes) {
                inDegree.putIfAbsent(node.id(), 0);
                adjacency.putIfAbsent(node.id(), new ArrayList<>());
                for (String dep : node.dependencies()) {
                    adjacency.putIfAbsent(dep, new ArrayList<>());
                    adjacency.get(dep).add(node.id());
                    inDegree.merge(node.id(), 1, Integer::sum);
                }
            }

            for (DagEdge edge : edges) {
                adjacency.putIfAbsent(edge.from(), new ArrayList<>());
                adjacency.get(edge.from()).add(edge.to());
                inDegree.putIfAbsent(edge.to(), 0);
                inDegree.merge(edge.to(), 1, Integer::sum);
            }

            Queue<String> queue = new LinkedList<>();
            for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
                if (e.getValue() == 0) queue.add(e.getKey());
            }

            List<String> result = new ArrayList<>();
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                result.add(cur);
                for (String neighbor : adjacency.getOrDefault(cur, List.of())) {
                    int newDeg = inDegree.merge(neighbor, -1, Integer::sum);
                    if (newDeg == 0) queue.add(neighbor);
                }
            }

            return result;
        }

        private List<List<String>> getParallelGroups() {
            Map<String, DagNode> nodeMap = new HashMap<>();
            for (DagNode n : nodes) nodeMap.put(n.id(), n);

            Map<String, Integer> depth = new LinkedHashMap<>();
            for (String id : topologicalOrder()) {
                DagNode node = nodeMap.get(id);
                int depMax = 0;
                if (node != null) {
                    for (String dep : node.dependencies()) {
                        depMax = Math.max(depMax, depth.getOrDefault(dep, 0));
                    }
                }
                depth.put(id, depMax + 1);
            }

            int maxDepth = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<List<String>> groups = new ArrayList<>();
            for (int i = 1; i <= maxDepth; i++) {
                int level = i;
                List<String> group = depth.entrySet().stream()
                        .filter(e -> e.getValue() == level)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
                groups.add(group);
            }
            return groups;
        }
    }

    public static class DagDecomposerBuilder {
        private DecomposeFunction decomposeFunc;
        private AnalyzeFunction analyzeFunc;
        private RoutingFunction routingFunc;

        public DagDecomposerBuilder decomposeFunc(DecomposeFunction func) {
            this.decomposeFunc = func;
            return this;
        }

        public DagDecomposerBuilder analyzeFunc(AnalyzeFunction func) {
            this.analyzeFunc = func;
            return this;
        }

        public DagDecomposerBuilder routingFunc(RoutingFunction func) {
            this.routingFunc = func;
            return this;
        }

        public DagDecomposer build() {
            if (decomposeFunc == null) {
                decomposeFunc = task -> new Dag(List.of(), List.of());
            }
            if (analyzeFunc == null) {
                analyzeFunc = dag -> {
                    int omega = dag.maxParallelWidth();
                    int delta = dag.criticalPathLength();
                    double gamma = dag.couplingDensity();
                    return new DagMetrics(omega, delta, gamma);
                };
            }
            if (routingFunc == null) {
                routingFunc = metrics -> {
                    if (metrics.gamma() < 0.3) return Topology.PARALLEL;
                    if (metrics.gamma() <= 0.7) return Topology.HIERARCHICAL;
                    return Topology.SEQUENTIAL;
                };
            }
            return new DagDecomposer(this);
        }
    }
}

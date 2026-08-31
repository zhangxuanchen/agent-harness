package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具调用依赖分析器。
 * <p>对应书中 Ch05 §5.5 —— 工具调用的依赖关系分析。
 * <p>扫描 tool_call 参数中的引用标记，构建依赖图：
 * <ul>
 *   <li><b>引用识别</b>：识别参数中对前序工具结果的引用（如 {@code ${callId.field}}）</li>
 *   <li><b>依赖建图</b>：构建有向无环图（DAG），表示工具间的依赖关系</li>
 *   <li><b>拓扑排序</b>：对 DAG 进行拓扑排序，确定执行顺序</li>
 *   <li><b>分组优化</b>：将无依赖关系的工具分到同一组，支持并行执行</li>
 * </ul>
 */
@Component
public class ToolCallDependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ToolCallDependencyAnalyzer.class);

    /**
     * 引用模式前缀。
     */
    public static final String REFERENCE_PREFIX = "${";
    public static final String REFERENCE_SUFFIX = "}";

    /**
     * 依赖分析结果。
     */
    public record DependencyAnalysisResult(
            String analysisId,
            Map<String, Set<String>> dependencyGraph,
            List<String> topologicalOrder,
            List<List<String>> executionGroups,
            boolean hasCycle,
            List<String> cycleNodes,
            int maxDepth
    ) {}

    /**
     * 引用信息。
     */
    public record Reference(
            String sourceCallId,
            String referencedCallId,
            String fieldPath,
            String rawExpression
    ) {
        public static Reference of(String sourceCallId, String rawExpression) {
            // 解析 ${callId.field} 格式
            String inner = rawExpression.substring(REFERENCE_PREFIX.length(),
                    rawExpression.length() - REFERENCE_SUFFIX.length());
            String[] parts = inner.split("\\.", 2);
            String referencedCallId = parts[0];
            String fieldPath = parts.length > 1 ? parts[1] : "";
            return new Reference(sourceCallId, referencedCallId, fieldPath, rawExpression);
        }
    }

    /**
     * 分析工具调用参数中的依赖关系。
     *
     * @param callId  工具调用 ID
     * @param args    工具调用参数
     * @return 发现的引用列表
     */
    public List<Reference> analyzeReferences(String callId, Map<String, Object> args) {
        List<Reference> references = new ArrayList<>();

        if (args == null || args.isEmpty()) {
            return references;
        }

        // 遍历所有参数值，查找 ${...} 引用模式
        for (Object value : args.values()) {
            if (value instanceof String strValue) {
                references.addAll(extractReferences(callId, strValue));
            } else if (value instanceof List<?> listValue) {
                for (Object item : listValue) {
                    if (item instanceof String strItem) {
                        references.addAll(extractReferences(callId, strItem));
                    }
                }
            } else if (value instanceof Map<?, ?> mapValue) {
                for (Object nestedValue : mapValue.values()) {
                    if (nestedValue instanceof String strNested) {
                        references.addAll(extractReferences(callId, strNested));
                    }
                }
            }
        }

        if (!references.isEmpty()) {
            log.debug("[DependencyAnalyzer] 发现引用: callId={}, references={}", callId, references.size());
        }

        return references;
    }

    /**
     * 构建完整的依赖图。
     *
     * @param allArguments 所有工具调用的参数 Map（callId → arguments）
     * @return 依赖关系图（callId → Set<依赖的 callId>）
     */
    public Map<String, Set<String>> buildDependencyGraph(Map<String, Map<String, Object>> allArguments) {
        log.info("[DependencyAnalyzer] ========== 构建依赖图 ==========");
        log.info("[DependencyAnalyzer] 分析参数数量: count={}", allArguments.size());

        Map<String, Set<String>> graph = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, Object>> entry : allArguments.entrySet()) {
            String callId = entry.getKey();
            Map<String, Object> args = entry.getValue();

            // 确保每个 callId 都在图中
            graph.computeIfAbsent(callId, k -> new LinkedHashSet<>());

            List<Reference> references = analyzeReferences(callId, args);
            Set<String> dependencies = graph.computeIfAbsent(callId, k -> new LinkedHashSet<>());

            for (Reference ref : references) {
                // 只添加在参数列表中存在的依赖
                if (allArguments.containsKey(ref.referencedCallId())) {
                    dependencies.add(ref.referencedCallId());
                    log.debug("[DependencyAnalyzer] 依赖关系: {} -> {}", callId, ref.referencedCallId());
                } else {
                    log.warn("[DependencyAnalyzer] 引用的 callId 不存在: callId={}, referencedCallId={}",
                            callId, ref.referencedCallId());
                }
            }
        }

        // 日志统计
        long nodesWithDeps = graph.values().stream().filter(s -> !s.isEmpty()).count();
        long totalDeps = graph.values().stream().mapToLong(Set::size).sum();
        log.info("[DependencyAnalyzer] ========== 依赖图构建完成 ==========");
        log.info("[DependencyAnalyzer] 图统计: nodes={}, nodesWithDependencies={}, totalDependencies={}",
                graph.size(), nodesWithDeps, totalDeps);

        return graph;
    }

    /**
     * 拓扑排序（Kahn 算法）。
     *
     * @param graph 依赖图
     * @return 拓扑排序结果，若有环则返回部分结果
     */
    public List<String> topologicalSort(Map<String, Set<String>> graph) {
        log.info("[DependencyAnalyzer] ========== 开始拓扑排序 ==========");

        // 构建入度表和出边表
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> outEdges = new LinkedHashMap<>();

        for (String node : graph.keySet()) {
            inDegree.put(node, 0);
            outEdges.put(node, new ArrayList<>());
        }

        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            String node = entry.getKey();
            for (String dependency : entry.getValue()) {
                // node 依赖 dependency，所以 dependency → node
                inDegree.merge(node, 1, Integer::sum);
                outEdges.computeIfAbsent(dependency, k -> new ArrayList<>()).add(node);
            }
        }

        // Kahn 算法
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            sorted.add(node);

            List<String> neighbors = outEdges.getOrDefault(node, Collections.emptyList());
            for (String neighbor : neighbors) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        boolean hasCycle = sorted.size() < graph.size();
        if (hasCycle) {
            List<String> remaining = new ArrayList<>(graph.keySet());
            remaining.removeAll(sorted);
            log.warn("[DependencyAnalyzer] ========== 拓扑排序检测到环 ==========");
            log.warn("[DependencyAnalyzer] 环路节点: {}", remaining);
            log.warn("[DependencyAnalyzer] 已有序节点: {}/{}", sorted.size(), graph.size());
        } else {
            log.info("[DependencyAnalyzer] ========== 拓扑排序完成 ==========");
            log.info("[DependencyAnalyzer] 排序结果: nodeCount={}, order={}", sorted.size(), sorted);
        }

        return sorted;
    }

    /**
     * 拓扑分组——将可并行执行的工具分到同一组。
     *
     * @param graph 依赖图（callId → Set<依赖的 callId>）
     * @return 分组后的执行计划，每组内可并行执行
     */
    public List<List<String>> topologicalGroups(Map<String, Set<String>> graph) {
        log.info("[DependencyAnalyzer] ========== 开始拓扑分组 ==========");
        log.info("[DependencyAnalyzer] 输入节点数: count={}", graph.size());

        if (graph.isEmpty()) {
            return Collections.emptyList();
        }

        // 计算每个节点的深度（最长路径）
        Map<String, Integer> depthMap = new LinkedHashMap<>();
        List<String> sorted = topologicalSort(graph);

        for (String node : sorted) {
            Set<String> deps = graph.getOrDefault(node, Collections.emptySet());
            int maxDepth = 0;
            for (String dep : deps) {
                maxDepth = Math.max(maxDepth, depthMap.getOrDefault(dep, 0) + 1);
            }
            depthMap.put(node, maxDepth);
        }

        // 按深度分组
        Map<Integer, List<String>> groupsByDepth = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : depthMap.entrySet()) {
            groupsByDepth.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        // 排序并输出
        List<List<String>> groups = new ArrayList<>();
        for (int i = 0; i < groupsByDepth.size(); i++) {
            List<String> group = groupsByDepth.getOrDefault(i, Collections.emptyList());
            groups.add(new ArrayList<>(group));
        }

        int maxDepth = groups.size() - 1;
        int maxGroupSize = groups.stream().mapToInt(List::size).max().orElse(0);

        log.info("[DependencyAnalyzer] ========== 拓扑分组完成 ==========");
        log.info("[DependencyAnalyzer] 分组结果: totalGroups={}, maxDepth={}, maxGroupSize={}",
                groups.size(), maxDepth, maxGroupSize);

        for (int i = 0; i < groups.size(); i++) {
            log.info("[DependencyAnalyzer] 分组详情: group={}/{}, size={}, members={}",
                    i + 1, groups.size(), groups.get(i).size(), groups.get(i));
        }

        return groups;
    }

    /**
     * 完整的依赖分析流程。
     *
     * @param allArguments 所有工具调用的参数 Map
     * @return 完整分析结果
     */
    public DependencyAnalysisResult analyzeAll(Map<String, Map<String, Object>> allArguments) {
        String analysisId = "analysis-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("[DependencyAnalyzer] ========== 开始完整依赖分析 ==========");
        log.info("[DependencyAnalyzer] 分析 ID: analysisId={}, callCount={}", analysisId, allArguments.size());

        // 1. 构建依赖图
        Map<String, Set<String>> graph = buildDependencyGraph(allArguments);

        // 2. 拓扑排序
        List<String> topoOrder = topologicalSort(graph);

        // 3. 拓扑分组
        List<List<String>> groups = topologicalGroups(graph);

        // 4. 检测环路
        boolean hasCycle = topoOrder.size() < graph.size();
        List<String> cycleNodes = new ArrayList<>();
        if (hasCycle) {
            cycleNodes.addAll(graph.keySet());
            cycleNodes.removeAll(topoOrder);
        }

        // 5. 计算最大深度
        int maxDepth = groups.size();

        DependencyAnalysisResult result = new DependencyAnalysisResult(
                analysisId, graph, topoOrder, groups, hasCycle, cycleNodes, maxDepth
        );

        log.info("[DependencyAnalyzer] ========== 依赖分析完成 ==========");
        log.info("[DependencyAnalyzer] 分析摘要: analysisId={}, nodes={}, groups={}, hasCycle={}, maxDepth={}",
                analysisId, graph.size(), groups.size(), hasCycle, maxDepth);

        return result;
    }

    /**
     * 从参数字符串中提取引用。
     */
    private List<Reference> extractReferences(String callId, String value) {
        List<Reference> references = new ArrayList<>();
        if (value == null || !value.contains(REFERENCE_PREFIX)) {
            return references;
        }

        int startIdx = 0;
        while (startIdx < value.length()) {
            int refStart = value.indexOf(REFERENCE_PREFIX, startIdx);
            if (refStart == -1) break;

            int refEnd = value.indexOf(REFERENCE_SUFFIX, refStart + REFERENCE_PREFIX.length());
            if (refEnd == -1) {
                // 格式不完整，跳过
                log.warn("[DependencyAnalyzer] 引用格式不完整: callId={}, position={}", callId, refStart);
                break;
            }

            String rawExpr = value.substring(refStart, refEnd + REFERENCE_SUFFIX.length());
            try {
                Reference ref = Reference.of(callId, rawExpr);
                references.add(ref);
                log.debug("[DependencyAnalyzer] 解析引用: callId={}, expression={}, referencedCallId={}, fieldPath={}",
                        callId, rawExpr, ref.referencedCallId(), ref.fieldPath());
            } catch (Exception e) {
                log.warn("[DependencyAnalyzer] 引用解析失败: callId={}, expression={}, error={}",
                        callId, rawExpr, e.getMessage());
            }

            startIdx = refEnd + REFERENCE_SUFFIX.length();
        }

        return references;
    }
}

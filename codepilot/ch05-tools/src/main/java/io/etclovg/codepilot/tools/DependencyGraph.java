package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具调用依赖图。
 * <p>对应书中 Ch05 §5.5 —— 工具并行执行的依赖分析。
 * <p>构建工具调用之间的有向无环图（DAG），供 {@code ParallelToolExecutor}
 * 据此识别可并行执行的批次，并检测循环依赖。
 */
@Component
public class DependencyGraph {

    private static final Logger log = LoggerFactory.getLogger(DependencyGraph.class);

    private final Map<String, List<String>> adjacency = new HashMap<>();

    /**
     * 添加一条依赖边：{@code dependent} 依赖于 {@code dependency}。
     *
     * @param dependent   依赖方工具调用 ID
     * @param dependency  被依赖的工具调用 ID
     */
    public void addDependency(String dependent, String dependency) {
        adjacency.computeIfAbsent(dependent, k -> new ArrayList<>()).add(dependency);
        adjacency.computeIfAbsent(dependency, k -> new ArrayList<>());
        log.debug("添加依赖: {} -> {}", dependent, dependency);
    }

    /**
     * 获取所有已注册的节点。
     *
     * @return 节点集合
     */
    public Set<String> nodes() {
        return Collections.unmodifiableSet(adjacency.keySet());
    }

    /**
     * 获取指定节点的直接依赖。
     *
     * @param node 节点 ID
     * @return 依赖列表
     */
    public List<String> dependenciesOf(String node) {
        return Collections.unmodifiableList(adjacency.getOrDefault(node, List.of()));
    }

    /**
     * 拓扑排序。
     *
     * @return 拓扑顺序的节点列表；若存在环则返回空列表
     */
    public List<String> topologicalOrder() {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String node : adjacency.keySet()) {
            if (!visited.contains(node) && !dfs(node, visited, visiting, result)) {
                log.warn("检测到循环依赖，拓扑排序失败");
                return List.of();
            }
        }
        return result;
    }

    /**
     * 计算可并行执行的批次（同一批内无相互依赖）。
     *
     * @return 批次列表，每批为一组可并行节点
     */
    public List<List<String>> parallelBatches() {
        List<List<String>> batches = new ArrayList<>();
        Set<String> completed = new HashSet<>();
        Set<String> remaining = new HashSet<>(adjacency.keySet());

        while (!remaining.isEmpty()) {
            List<String> batch = new ArrayList<>();
            for (String node : remaining) {
                if (completed.containsAll(dependenciesOf(node))) {
                    batch.add(node);
                }
            }
            if (batch.isEmpty()) {
                log.warn("无法分批，疑似存在循环依赖");
                break;
            }
            completed.addAll(batch);
            remaining.removeAll(batch);
            batches.add(batch);
        }
        return batches;
    }

    private boolean dfs(String node, Set<String> visited, Set<String> visiting, List<String> result) {
        if (visiting.contains(node)) {
            return false;
        }
        if (visited.contains(node)) {
            return true;
        }
        visiting.add(node);
        for (String dep : adjacency.getOrDefault(node, List.of())) {
            if (!dfs(dep, visited, visiting, result)) {
                return false;
            }
        }
        visiting.remove(node);
        visited.add(node);
        result.add(node);
        return true;
    }
}

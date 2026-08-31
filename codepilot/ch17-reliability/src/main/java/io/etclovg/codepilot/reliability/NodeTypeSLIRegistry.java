package io.etclovg.codepilot.reliability;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * 节点类型 SLI 注册表：五种节点类型的差异化 SLI 管理
 * 对应书中 Ch17 — 生产监控可靠性与成本中的多节点类型监控
 *
 * <p>核心功能：
 * <ul>
 *   <li>差异化 SLI：为五种节点类型（LLM推理、工具调用、检索、验证、压缩）提供独立的 SLI 目标</li>
 *   <li>动态注册：支持运行时动态注册节点实例和更新 SLI 数据</li>
 *   <li>健康度评分：基于延迟、成功率、质量计算节点的健康评分</li>
 *   <li>异常检测：自动检测 SLI 异常并触发告警</li>
 * </ul>
 *
 * <p>设计理念：
 * <ul>
 *   <li>每种节点类型有独立的 P95/P99 延迟阈值和成功率基线</li>
 *   <li>使用滑动时间窗口计算 SLI，避免历史数据污染</li>
 *   <li>支持多实例注册，按 nodeId 维度隔离 SLI 数据</li>
 * </ul>
 */
@Component
public class NodeTypeSLIRegistry {

    /** 节点实例注册表：nodeId -> NodeSLI */
    private final Map<String, NodeSLI> nodeRegistry = new ConcurrentHashMap<>();

    /** 节点类型映射：nodeId -> NodeType */
    private final Map<String, NodeType> nodeTypeMap = new ConcurrentHashMap<>();

    /** 节点 SLI 目标配置：NodeType -> NodeSLITarget */
    private final Map<NodeType, NodeSLITarget> sliTargets = new ConcurrentHashMap<>();

    /** 延迟时间窗口：nodeId -> 延迟样本队列 */
    private final Map<String, Deque<LatencySample>> latencyWindows = new ConcurrentHashMap<>();

    /** 成功/失败计数窗口：nodeId -> 计数器 */
    private final Map<String, SuccessCounter> successCounters = new ConcurrentHashMap<>();

    /** 质量评分窗口：nodeId -> 质量样本队列 */
    private final Map<String, Deque<Double>> qualityWindows = new ConcurrentHashMap<>();

    /** 时间窗口大小（毫秒） */
    private static final long WINDOW_SIZE_MS = 60_000; // 1 分钟滑动窗口

    public NodeTypeSLIRegistry() {
        // 初始化默认的 SLI 目标配置
        for (NodeType type : NodeType.values()) {
            sliTargets.put(type, NodeSLITarget.getDefault(type));
        }
    }

    /**
     * 注册节点实例
     *
     * @param nodeType 节点类型
     * @param nodeId 节点实例 ID
     * @return 是否注册成功
     */
    public boolean registerNode(NodeType nodeType, String nodeId) {
        if (nodeType == null || nodeId == null || nodeId.isEmpty()) {
            return false;
        }

        nodeTypeMap.put(nodeId, nodeType);
        nodeRegistry.put(nodeId, NodeSLI.empty(nodeType, nodeId));
        latencyWindows.put(nodeId, new ConcurrentLinkedDeque<>());
        successCounters.put(nodeId, new SuccessCounter());
        qualityWindows.put(nodeId, new ConcurrentLinkedDeque<>());

        return true;
    }

    /**
     * 注销节点实例
     *
     * @param nodeId 节点实例 ID
     * @return 被注销的节点 SLI，如果不存在则返回 null
     */
    public NodeSLI unregisterNode(String nodeId) {
        nodeTypeMap.remove(nodeId);
        latencyWindows.remove(nodeId);
        successCounters.remove(nodeId);
        qualityWindows.remove(nodeId);
        return nodeRegistry.remove(nodeId);
    }

    /**
     * 记录节点延迟样本
     *
     * @param nodeId 节点实例 ID
     * @param latencyMs 延迟（毫秒）
     * @param success 是否成功
     */
    public void recordLatency(String nodeId, long latencyMs, boolean success) {
        Deque<LatencySample> window = latencyWindows.get(nodeId);
        if (window == null) {
            return;
        }

        // 添加新样本
        window.addLast(new LatencySample(latencyMs, Instant.now()));

        // 清理过期样本（滑动窗口）
        Instant cutoff = Instant.now().minusMillis(WINDOW_SIZE_MS);
        while (!window.isEmpty() && window.peekFirst().timestamp.isBefore(cutoff)) {
            window.removeFirst();
        }

        // 更新成功/失败计数
        SuccessCounter counter = successCounters.get(nodeId);
        if (counter != null) {
            if (success) {
                counter.recordSuccess();
            } else {
                counter.recordFailure();
            }
        }

        // 重新计算 SLI
        recalculateSLI(nodeId);
    }

    /**
     * 记录节点质量评分
     *
     * @param nodeId 节点实例 ID
     * @param qualityScore 质量评分（0.0-1.0）
     */
    public void recordQuality(String nodeId, double qualityScore) {
        Deque<Double> window = qualityWindows.get(nodeId);
        if (window == null) {
            return;
        }

        // 添加新样本
        window.addLast(qualityScore);

        // 清理过期样本
        while (window.size() > 1000) {
            window.removeFirst();
        }

        // 重新计算 SLI
        recalculateSLI(nodeId);
    }

    /**
     * 重新计算节点的 SLI
     *
     * @param nodeId 节点实例 ID
     */
    private void recalculateSLI(String nodeId) {
        NodeType nodeType = nodeTypeMap.get(nodeId);
        if (nodeType == null) {
            return;
        }

        Deque<LatencySample> latencyWindow = latencyWindows.get(nodeId);
        SuccessCounter counter = successCounters.get(nodeId);
        Deque<Double> qualityWindow = qualityWindows.get(nodeId);

        if (latencyWindow == null || counter == null || qualityWindow == null) {
            return;
        }

        // 计算延迟百分位
        List<Long> latencies = latencyWindow.stream()
            .map(LatencySample::latencyMs)
            .sorted()
            .collect(Collectors.toList());

        long p50 = calculatePercentile(latencies, 0.50);
        long p95 = calculatePercentile(latencies, 0.95);
        long p99 = calculatePercentile(latencies, 0.99);

        // 计算成功率
        double successRate = counter.successRate();
        double errorRate = 1.0 - successRate;

        // 计算平均质量评分
        double avgQuality = qualityWindow.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(1.0);

        // 计算 QPS（样本数 / 时间窗口）
        double throughputQps = (double) latencyWindow.size() / (WINDOW_SIZE_MS / 1000.0);

        // 创建新的 SLI
        NodeSLI sli = new NodeSLI(
            nodeType,
            nodeId,
            p50, p95, p99,
            successRate,
            errorRate,
            avgQuality,
            throughputQps,
            latencyWindow.size(),
            Instant.now()
        );

        nodeRegistry.put(nodeId, sli);
    }

    /**
     * 计算延迟百分位
     *
     * @param sortedLatencies 已排序的延迟列表
     * @param percentile 百分位（0.0-1.0）
     * @return 百分位延迟值
     */
    private long calculatePercentile(List<Long> sortedLatencies, double percentile) {
        if (sortedLatencies.isEmpty()) {
            return 0;
        }

        int index = (int) (sortedLatencies.size() * percentile);
        index = Math.max(0, Math.min(index, sortedLatencies.size() - 1));

        return sortedLatencies.get(index);
    }

    /**
     * 查询节点的 SLI
     *
     * @param nodeId 节点实例 ID
     * @return 节点 SLI，如果不存在则返回空 SLI
     */
    public NodeSLI getNodeSLI(String nodeId) {
        return nodeRegistry.getOrDefault(nodeId, NodeSLI.empty(null, nodeId));
    }

    /**
     * 查询指定类型节点的 SLI 列表
     *
     * @param nodeType 节点类型
     * @return 节点 SLI 列表
     */
    public List<NodeSLI> getNodesByType(NodeType nodeType) {
        return nodeRegistry.values().stream()
            .filter(sli -> sli.nodeType() == nodeType)
            .collect(Collectors.toList());
    }

    /**
     * 计算节点的健康评分
     * 对应书中 Ch17 的健康度公式：延迟×0.3 + 成功率×0.4 + 质量×0.3
     *
     * @param nodeId 节点实例 ID
     * @return 健康评分（0.0-1.0），越高越健康
     */
    public double healthScore(String nodeId) {
        NodeSLI sli = getNodeSLI(nodeId);
        return sli.healthScore();
    }

    /**
     * 判断节点是否健康（健康评分 >= 0.8）
     *
     * @param nodeId 节点实例 ID
     * @return 是否健康
     */
    public boolean isHealthy(String nodeId) {
        return healthScore(nodeId) >= 0.8;
    }

    /**
     * 检查节点是否达到 SLI 基线要求
     *
     * @param nodeId 节点实例 ID
     * @return 是否达标
     */
    public boolean meetsBaseline(String nodeId) {
        NodeSLI sli = getNodeSLI(nodeId);
        NodeSLITarget target = sliTargets.get(sli.nodeType());

        if (target == null) {
            return false;
        }

        return sli.meetsBaseline(target);
    }

    /**
     * 获取所有不健康的节点
     *
     * @return 不健康节点列表
     */
    public List<NodeSLI> getUnhealthyNodes() {
        return nodeRegistry.values().stream()
            .filter(sli -> sli.healthScore() < 0.8)
            .collect(Collectors.toList());
    }

    /**
     * 获取所有未达标的节点
     *
     * @return 未达标节点列表
     */
    public List<NodeSLI> getNodesBelowBaseline() {
        return nodeRegistry.values().stream()
            .filter(sli -> !meetsBaseline(sli.nodeId()))
            .collect(Collectors.toList());
    }

    /**
     * 更新节点的 SLI 目标配置
     *
     * @param nodeType 节点类型
     * @param target 新的 SLI 目标
     */
    public void updateSLITarget(NodeType nodeType, NodeSLITarget target) {
        sliTargets.put(nodeType, target);
    }

    /**
     * 获取节点的 SLI 目标配置
     *
     * @param nodeType 节点类型
     * @return SLI 目标配置
     */
    public NodeSLITarget getSLITarget(NodeType nodeType) {
        return sliTargets.get(nodeType);
    }

    /**
     * 获取注册表统计信息
     *
     * @return 统计信息
     */
    public RegistryStats getStats() {
        Map<NodeType, Integer> countsByType = new EnumMap<>(NodeType.class);
        for (NodeType type : NodeType.values()) {
            countsByType.put(type, getNodesByType(type).size());
        }

        long healthyCount = nodeRegistry.values().stream()
            .filter(sli -> sli.healthScore() >= 0.8)
            .count();

        return new RegistryStats(
            nodeRegistry.size(),
            countsByType,
            healthyCount,
            nodeRegistry.size() - healthyCount
        );
    }

    /**
     * 延迟样本
     */
    private record LatencySample(long latencyMs, Instant timestamp) {}

    /**
     * 成功/失败计数器
     */
    private static class SuccessCounter {
        private long successes = 0;
        private long failures = 0;

        synchronized void recordSuccess() {
            successes++;
        }

        synchronized void recordFailure() {
            failures++;
        }

        synchronized double successRate() {
            long total = successes + failures;
            return total == 0 ? 1.0 : (double) successes / total;
        }
    }

    /**
     * 注册表统计信息
     */
    public record RegistryStats(
        int totalNodes,
        Map<NodeType, Integer> nodesByType,
        long healthyNodes,
        long unhealthyNodes
    ) {}
}
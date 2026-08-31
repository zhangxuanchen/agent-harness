package io.etclovg.codepilot.reliability;

import java.time.Instant;

/**
 * 节点 SLI (Service Level Indicator) 数据模型
 * 对应书中 Ch17 — 生产监控可靠性与成本中的 SLI 指标体系
 *
 * <p>SLI 是衡量服务质量的定量指标，包括：
 * <ul>
 *   <li>延迟指标：P50/P95/P99 延迟百分位</li>
 *   <li>可用性指标：成功率和错误率</li>
 *   <li>质量指标：输出质量评分（如 relevance score）</li>
 *   <li>吞吐指标：QPS 和并发数</li>
 * </ul>
 */
public record NodeSLI(
    /** 节点类型 */
    NodeType nodeType,

    /** 节点实例 ID */
    String nodeId,

    /** P50 延迟（毫秒） */
    long p50LatencyMs,

    /** P95 延迟（毫秒） */
    long p95LatencyMs,

    /** P99 延迟（毫秒） */
    long p99LatencyMs,

    /** 成功率（0.0-1.0） */
    double successRate,

    /** 错误率（0.0-1.0） */
    double errorRate,

    /** 质量评分（0.0-1.0） */
    double qualityScore,

    /** 吞吐量（QPS） */
    double throughputQps,

    /** 样本数量 */
    long sampleCount,

    /** 时间戳 */
    Instant timestamp
) {
    /**
     * 计算综合健康评分
     * 对应书中 Ch17 的健康度计算公式：延迟×0.3 + 成功率×0.4 + 质量×0.3
     *
     * @return 健康评分（0.0-1.0），越高越健康
     */
    public double healthScore() {
        // 延迟得分：将延迟映射到 0-1 范围，延迟越低得分越高
        // 基准：P95 延迟 1000ms 为 1.0 分，线性递减
        double latencyScore = Math.max(0.0, Math.min(1.0, 1.0 - (p95LatencyMs - 100.0) / 900.0));

        // 成功率得分：直接使用成功率
        double availabilityScore = successRate;

        // 质量得分：直接使用质量评分
        double quality = qualityScore;

        // 加权平均：延迟 30%，成功率 40%，质量 30%
        return latencyScore * 0.3 + availabilityScore * 0.4 + quality * 0.3;
    }

    /**
     * 判断是否达到 SLI 基线要求
     *
     * @param target 目标 SLI 配置
     * @return 是否达标
     */
    public boolean meetsBaseline(NodeSLITarget target) {
        return p95LatencyMs <= target.p95LatencyThresholdMs()
            && successRate >= target.successRateBaseline()
            && qualityScore >= target.qualityBaseline();
    }

    /**
     * 创建默认的空 SLI
     */
    public static NodeSLI empty(NodeType nodeType, String nodeId) {
        return new NodeSLI(
            nodeType, nodeId,
            0, 0, 0,
            1.0, 0.0, 1.0,
            0.0, 0,
            Instant.now()
        );
    }
}
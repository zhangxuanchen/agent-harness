package io.etclovg.codepilot.reliability;

/**
 * 节点 SLI 目标配置
 * 对应书中 Ch17 — 生产监控可靠性与成本中的 SLO 目标设定
 *
 * <p>定义每种节点类型的 SLI 目标基线，用于：
 * <ul>
 *   <li>健康度评估：判断节点是否达标</li>
 *   <li>告警触发：当 SLI 低于目标时触发告警</li>
 *   <li>容量规划：基于目标进行资源分配</li>
 * </ul>
 */
public record NodeSLITarget(
    /** 节点类型 */
    NodeType nodeType,

    /** P95 延迟阈值（毫秒） */
    long p95LatencyThresholdMs,

    /** P99 延迟阈值（毫秒） */
    long p99LatencyThresholdMs,

    /** 成功率基线（0.0-1.0） */
    double successRateBaseline,

    /** 质量基线（0.0-1.0） */
    double qualityBaseline,

    /** 吞吐量目标（QPS） */
    double throughputTarget
) {
    /**
     * 获取默认的 SLI 目标配置
     * 根据书中 Ch17 的生产经验设定合理的默认值
     */
    public static NodeSLITarget getDefault(NodeType nodeType) {
        return switch (nodeType) {
            case LLM_INFERENCE -> new NodeSLITarget(
                nodeType,
                3000,  // P95: 3s（LLM 推理延迟较高）
                5000,  // P99: 5s
                0.99,  // 成功率 99%
                0.85,  // 质量 85%
                10.0   // QPS 目标
            );
            case TOOL_INVOCATION -> new NodeSLITarget(
                nodeType,
                2000,  // P95: 2s
                3000,  // P99: 3s
                0.995, // 成功率 99.5%
                0.90,  // 质量 90%
                50.0   // QPS 目标
            );
            case RETRIEVAL -> new NodeSLITarget(
                nodeType,
                500,   // P95: 500ms（检索需要快速响应）
                1000,  // P99: 1s
                0.999, // 成功率 99.9%
                0.80,  // 质量 80%
                100.0  // QPS 目标
            );
            case VALIDATION -> new NodeSLITarget(
                nodeType,
                100,   // P95: 100ms（验证需要低延迟）
                200,   // P99: 200ms
                0.999, // 成功率 99.9%
                0.95,  // 质量 95%
                200.0  // QPS 目标
            );
            case COMPRESSION -> new NodeSLITarget(
                nodeType,
                200,   // P95: 200ms
                500,   // P99: 500ms
                0.999, // 成功率 99.9%
                0.85,  // 质量 85%
                100.0  // QPS 目标
            );
        };
    }
}
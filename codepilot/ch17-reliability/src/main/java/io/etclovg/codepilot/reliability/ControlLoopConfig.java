package io.etclovg.codepilot.reliability;

import java.time.Duration;

/**
 * 控制回路配置：定义控制回路的参数和策略
 * 对应书中 Ch17 — 生产监控可靠性与成本中的控制回路配置
 *
 * <p>控制回路配置包括：
 * <ul>
 *   <li>回路层级：快/中/慢回路</li>
 *   <li>采样周期：数据采集频率</li>
 *   <li>决策策略：自动决策规则</li>
 *   <li>阈值配置：触发条件阈值</li>
 * </ul>
 */
public record ControlLoopConfig(
    /** 回路层级 */
    LoopTier tier,

    /** 采样周期 */
    Duration samplingPeriod,

    /** 决策周期 */
    Duration decisionPeriod,

    /** 自动执行标志 */
    boolean autoExecute,

    /** 熔断阈值（成功率） */
    double circuitThreshold,

    /** 恢复阈值（成功率） */
    double recoveryThreshold,

    /** Canary 流量比例（中回路专用） */
    double canaryTrafficRatio,

    /** 优化目标（慢回路专用） */
    OptimizationGoal optimizationGoal
) {
    /**
     * 优化目标枚举
     */
    public enum OptimizationGoal {
        /** 成本优化：降低运营成本 */
        COST,

        /** 延迟优化：降低响应延迟 */
        LATENCY,

        /** 可用性优化：提高服务可用性 */
        AVAILABILITY,

        /** 均衡优化：成本-延迟-可用性均衡 */
        BALANCED
    }

    /**
     * 获取默认的控制回路配置
     */
    public static ControlLoopConfig getDefault(LoopTier tier) {
        return switch (tier) {
            case FAST -> new ControlLoopConfig(
                tier,
                Duration.ofMillis(100),     // 100ms 采样周期
                Duration.ofSeconds(1),      // 1s 决策周期
                true,                       // 自动执行
                0.95,                       // 成功率 < 95% 熔断
                0.99,                       // 成功率 > 99% 恢复
                0.0,                        // 不涉及 Canary
                OptimizationGoal.AVAILABILITY
            );
            case MEDIUM -> new ControlLoopConfig(
                tier,
                Duration.ofSeconds(10),     // 10s 采样周期
                Duration.ofMinutes(1),      // 1min 决策周期
                true,                       // 自动执行
                0.90,                       // 成功率 < 90% 熔断
                0.95,                       // 成功率 > 95% 恢复
                0.05,                       // 5% Canary 流量
                OptimizationGoal.BALANCED
            );
            case SLOW -> new ControlLoopConfig(
                tier,
                Duration.ofMinutes(10),     // 10min 采样周期
                Duration.ofHours(24),       // 24h 决策周期
                false,                      // 人工审核
                0.85,                       // 成功率 < 85% 熔断
                0.90,                       // 成功率 > 90% 恢复
                0.0,                        // 不涉及 Canary
                OptimizationGoal.COST
            );
        };
    }
}
package io.etclovg.codepilot.reliability;

import java.time.Instant;

/**
 * 控制回路状态：跟踪控制回路的执行状态
 * 对应书中 Ch17 — 生产监控可靠性与成本中的控制回路状态管理
 *
 * <p>控制回路状态包括：
 * <ul>
 *   <li>执行状态：运行中、暂停、已完成</li>
 *   <li>健康状态：正常、降级、熔断</li>
 *   <li>决策历史：最近执行的决策</li>
 *   <li>指标摘要：采样周期内的指标统计</li>
 * </ul>
 */
public record LoopState(
    /** 回路层级 */
    LoopTier tier,

    /** 执行状态 */
    ExecutionState executionState,

    /** 健康状态 */
    HealthState healthState,

    /** 最近决策 */
    LoopDecision lastDecision,

    /** 采样计数 */
    long sampleCount,

    /** 平均成功率 */
    double avgSuccessRate,

    /** 平均延迟（毫秒） */
    long avgLatencyMs,

    /** 最后更新时间 */
    Instant lastUpdatedAt,

    /** 熔断触发次数 */
    int circuitTriggerCount,

    /** 恢复次数 */
    int recoveryCount
) {
    /**
     * 执行状态枚举
     */
    public enum ExecutionState {
        /** 运行中：回路正在执行 */
        RUNNING,

        /** 暂停：回路暂停执行 */
        PAUSED,

        /** 已完成：回路执行完成 */
        COMPLETED,

        /** 错误：回路执行出错 */
        ERROR
    }

    /**
     * 健康状态枚举
     */
    public enum HealthState {
        /** 正常：系统运行正常 */
        NORMAL,

        /** 降级：系统处于降级状态 */
        DEGRADED,

        /** 熔断：系统触发熔断 */
        CIRCUITED
    }

    /**
     * 创建初始状态
     */
    public static LoopState initial(LoopTier tier) {
        return new LoopState(
            tier,
            ExecutionState.RUNNING,
            HealthState.NORMAL,
            null,
            0,
            1.0,
            0,
            Instant.now(),
            0,
            0
        );
    }

    /**
     * 记录样本
     */
    public LoopState recordSample(boolean success, long latencyMs) {
        // 计算新的平均成功率（使用移动平均）
        double newAvgSuccessRate = (avgSuccessRate * sampleCount + (success ? 1.0 : 0.0)) / (sampleCount + 1);

        // 计算新的平均延迟（使用移动平均）
        long newAvgLatencyMs = (avgLatencyMs * sampleCount + latencyMs) / (sampleCount + 1);

        return new LoopState(
            tier,
            executionState,
            healthState,
            lastDecision,
            sampleCount + 1,
            newAvgSuccessRate,
            newAvgLatencyMs,
            Instant.now(),
            circuitTriggerCount,
            recoveryCount
        );
    }

    /**
     * 应用决策
     */
    public LoopState applyDecision(LoopDecision decision) {
        HealthState newHealthState = healthState;
        int newCircuitTriggerCount = circuitTriggerCount;
        int newRecoveryCount = recoveryCount;

        switch (decision.action()) {
            case CIRCUIT_OPEN -> {
                newHealthState = HealthState.CIRCUITED;
                newCircuitTriggerCount++;
            }
            case CIRCUIT_CLOSE -> {
                newHealthState = HealthState.NORMAL;
                newRecoveryCount++;
            }
            case DEGRADATION -> newHealthState = HealthState.DEGRADED;
            case RECOVERY -> newHealthState = HealthState.NORMAL;
            default -> {}
        }

        return new LoopState(
            tier,
            executionState,
            newHealthState,
            decision,
            sampleCount,
            avgSuccessRate,
            avgLatencyMs,
            Instant.now(),
            newCircuitTriggerCount,
            newRecoveryCount
        );
    }

    /**
     * 暂停回路
     */
    public LoopState pause() {
        return new LoopState(
            tier,
            ExecutionState.PAUSED,
            healthState,
            lastDecision,
            sampleCount,
            avgSuccessRate,
            avgLatencyMs,
            Instant.now(),
            circuitTriggerCount,
            recoveryCount
        );
    }

    /**
     * 恢复回路
     */
    public LoopState resume() {
        return new LoopState(
            tier,
            ExecutionState.RUNNING,
            healthState,
            lastDecision,
            sampleCount,
            avgSuccessRate,
            avgLatencyMs,
            Instant.now(),
            circuitTriggerCount,
            recoveryCount
        );
    }
}
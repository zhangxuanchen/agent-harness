package io.etclovg.codepilot.reliability;

import java.time.Instant;

/**
 * 预算状态：跟踪预算消费和熔断状态
 * 对应书中 Ch17 — 生产监控可靠性与成本中的预算状态管理
 *
 * <p>预算状态包括：
 * <ul>
 *   <li>已消费金额：累计消费金额</li>
 *   <li>熔断状态：正常、软熔断、硬熔断</li>
 *   <li>降级状态：是否已降级</li>
 *   <li>恢复时间：熔断触发时间，用于恢复检测</li>
 * </ul>
 */
public record BudgetState(
    /** 预算层级 */
    BudgetTier tier,

    /** 实体 ID */
    String entityId,

    /** 已消费金额（美元） */
    double consumedUsd,

    /** 预算上限（美元） */
    double budgetLimitUsd,

    /** 当前状态 */
    CircuitState circuitState,

    /** 降级策略 */
    BudgetConfig.DegradationStrategy currentStrategy,

    /** 熔断触发时间 */
    Instant circuitTriggeredAt,

    /** 最后更新时间 */
    Instant lastUpdatedAt,

    /** 请求计数 */
    long requestCount,

    /** 被拒绝计数 */
    long rejectedCount
) {
    /**
     * 熔断器状态
     */
    public enum CircuitState {
        /** 正常：预算充足，正常服务 */
        NORMAL,

        /** 软熔断：预算接近上限，触发告警 */
        SOFT_CIRCUIT,

        /** 硬熔断：预算耗尽，强制切断 */
        HARD_CIRCUIT
    }

    /**
     * 计算预算使用率
     */
    public double usageRatio() {
        return budgetLimitUsd == 0 ? 0 : consumedUsd / budgetLimitUsd;
    }

    /**
     * 计算剩余预算
     */
    public double remainingBudget() {
        return Math.max(0, budgetLimitUsd - consumedUsd);
    }

    /**
     * 判断是否可以恢复（使用率低于恢复阈值）
     */
    public boolean canRecover(double recoveryThreshold) {
        return usageRatio() < recoveryThreshold && circuitState != CircuitState.NORMAL;
    }

    /**
     * 创建初始状态
     */
    public static BudgetState initial(BudgetConfig config) {
        return new BudgetState(
            config.tier(),
            config.entityId(),
            0.0,
            config.budgetLimitUsd(),
            CircuitState.NORMAL,
            BudgetConfig.DegradationStrategy.REJECT,
            null,
            Instant.now(),
            0,
            0
        );
    }

    /**
     * 消费预算
     */
    public BudgetState consume(double amount) {
        return new BudgetState(
            tier,
            entityId,
            consumedUsd + amount,
            budgetLimitUsd,
            circuitState,
            currentStrategy,
            circuitTriggeredAt,
            Instant.now(),
            requestCount + 1,
            rejectedCount
        );
    }

    /**
     * 触发软熔断
     */
    public BudgetState triggerSoftCircuit(BudgetConfig.DegradationStrategy strategy) {
        return new BudgetState(
            tier,
            entityId,
            consumedUsd,
            budgetLimitUsd,
            CircuitState.SOFT_CIRCUIT,
            strategy,
            Instant.now(),
            Instant.now(),
            requestCount,
            rejectedCount
        );
    }

    /**
     * 触发硬熔断
     */
    public BudgetState triggerHardCircuit(BudgetConfig.DegradationStrategy strategy) {
        return new BudgetState(
            tier,
            entityId,
            consumedUsd,
            budgetLimitUsd,
            CircuitState.HARD_CIRCUIT,
            strategy,
            Instant.now(),
            Instant.now(),
            requestCount,
            rejectedCount + 1
        );
    }

    /**
     * 恢复正常
     */
    public BudgetState recover() {
        return new BudgetState(
            tier,
            entityId,
            consumedUsd,
            budgetLimitUsd,
            CircuitState.NORMAL,
            BudgetConfig.DegradationStrategy.REJECT,
            null,
            Instant.now(),
            requestCount,
            rejectedCount
        );
    }
}
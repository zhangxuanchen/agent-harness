package io.etclovg.codepilot.reliability;

import java.time.Duration;

/**
 * 预算配置：定义预算上限和熔断策略
 * 对应书中 Ch17 — 生产监控可靠性与成本中的预算策略配置
 *
 * <p>预算配置包含：
 * <ul>
 *   <li>预算上限：绝对金额或 token 数量</li>
 *   <li>熔断阈值：软熔断（告警）和硬熔断（强制切断）</li>
 *   <li>降级策略：超预算后的应对措施</li>
 *   <li>恢复策略：预算恢复后的自动升级</li>
 * </ul>
 */
public record BudgetConfig(
    /** 预算层级 */
    BudgetTier tier,

    /** 预算实体 ID（组织 ID、用户 ID、任务 ID、节点 ID） */
    String entityId,

    /** 预算上限（美元） */
    double budgetLimitUsd,

    /** 软熔断阈值（告警，相对预算上限的比例） */
    double softThresholdRatio,

    /** 硬熔断阈值（强制切断，相对预算上限的比例） */
    double hardThresholdRatio,

    /** 降级策略 */
    DegradationStrategy degradationStrategy,

    /** 预算周期 */
    Duration budgetPeriod,

    /** 是否启用自动恢复 */
    boolean autoRecoveryEnabled,

    /** 恢复阈值（预算使用率低于此值时自动恢复） */
    double recoveryThresholdRatio
) {
    /**
     * 降级策略枚举
     */
    public enum DegradationStrategy {
        /** 直接拒绝：立即停止服务 */
        REJECT,

        /** 模型降级：切换到更便宜的模型 */
        MODEL_DOWNGRADE,

        /** 队列排队：请求进入等待队列 */
        QUEUE,

        /** 缓存优先：优先使用缓存结果 */
        CACHE_FIRST
    }

    /**
     * 获取默认的预算配置
     */
    public static BudgetConfig getDefault(BudgetTier tier, String entityId) {
        return switch (tier) {
            case ORGANIZATION -> new BudgetConfig(
                tier, entityId,
                10000.0, // $10,000 组织月预算
                0.90,    // 90% 软熔断
                0.95,    // 95% 硬熔断
                DegradationStrategy.REJECT,
                Duration.ofDays(30),
                true,
                0.70     // 70% 以下自动恢复
            );
            case USER -> new BudgetConfig(
                tier, entityId,
                1000.0,  // $1,000 用户月预算
                0.85,    // 85% 软熔断
                0.90,    // 90% 硬熔断
                DegradationStrategy.MODEL_DOWNGRADE,
                Duration.ofDays(30),
                true,
                0.60     // 60% 以下自动恢复
            );
            case TASK -> new BudgetConfig(
                tier, entityId,
                100.0,   // $100 任务预算
                0.80,    // 80% 软熔断
                0.85,    // 85% 硬熔断
                DegradationStrategy.QUEUE,
                Duration.ofHours(24),
                true,
                0.50     // 50% 以下自动恢复
            );
            case NODE -> new BudgetConfig(
                tier, entityId,
                10.0,    // $10 节点预算
                0.75,    // 75% 软熔断
                0.80,    // 80% 硬熔断
                DegradationStrategy.CACHE_FIRST,
                Duration.ofHours(1),
                true,
                0.40     // 40% 以下自动恢复
            );
        };
    }

    /**
     * 计算软熔断阈值金额
     */
    public double softThresholdUsd() {
        return budgetLimitUsd * softThresholdRatio;
    }

    /**
     * 计算硬熔断阈值金额
     */
    public double hardThresholdUsd() {
        return budgetLimitUsd * hardThresholdRatio;
    }

    /**
     * 计算恢复阈值金额
     */
    public double recoveryThresholdUsd() {
        return budgetLimitUsd * recoveryThresholdRatio;
    }
}
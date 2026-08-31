package io.etclovg.codepilot.reliability;

import java.time.Instant;
import java.util.Map;

/**
 * 控制回路决策：定义控制回路的决策结果
 * 对应书中 Ch17 — 生产监控可靠性与成本中的决策逻辑
 *
 * <p>控制回路决策包括：
 * <ul>
 *   <li>决策动作：执行的具体动作（熔断、恢复、降级等）</li>
 *   <li>触发原因：决策触发的依据（SLI 超标、预算超限等）</li>
 *   <li>执行参数：决策执行所需的参数</li>
 *   <li>决策时间：决策产生的时间</li>
 * </ul>
 */
public record LoopDecision(
    /** 回路层级 */
    LoopTier tier,

    /** 决策动作 */
    DecisionAction action,

    /** 触发原因 */
    String triggerReason,

    /** 执行参数 */
    Map<String, Object> parameters,

    /** 决策时间 */
    Instant decisionTime,

    /** 置信度（0.0-1.0） */
    double confidence,

    /** 影响范围（节点 ID 列表） */
    String[] affectedNodes
) {
    /**
     * 决策动作枚举
     */
    public enum DecisionAction {
        /** 熔断开启：触发熔断 */
        CIRCUIT_OPEN,

        /** 熔断关闭：关闭熔断 */
        CIRCUIT_CLOSE,

        /** 降级：执行降级策略 */
        DEGRADATION,

        /** 升级：恢复正常或升级服务 */
        UPGRADE,

        /** 恢复：从降级或熔断状态恢复 */
        RECOVERY,

        /** Canary 扩大：扩大 Canary 流量比例 */
        CANARY_EXPAND,

        /** Canary 收缩：收缩 Canary 流量比例 */
        CANARY_SHRINK,

        /** 优化调整：调整优化参数 */
        OPTIMIZE,

        /** 无操作：无需执行任何操作 */
        NOOP
    }

    /**
     * 创建无操作决策
     */
    public static LoopDecision noop(LoopTier tier) {
        return new LoopDecision(
            tier,
            DecisionAction.NOOP,
            "No action needed",
            Map.of(),
            Instant.now(),
            1.0,
            new String[0]
        );
    }

    /**
     * 创建熔断决策
     */
    public static LoopDecision circuitOpen(LoopTier tier, String reason, String[] affectedNodes) {
        return new LoopDecision(
            tier,
            DecisionAction.CIRCUIT_OPEN,
            reason,
            Map.of("immediate", true),
            Instant.now(),
            0.95,
            affectedNodes
        );
    }

    /**
     * 创建恢复决策
     */
    public static LoopDecision recovery(LoopTier tier, String reason, String[] affectedNodes) {
        return new LoopDecision(
            tier,
            DecisionAction.RECOVERY,
            reason,
            Map.of("gradual", true),
            Instant.now(),
            0.90,
            affectedNodes
        );
    }

    /**
     * 创建降级决策
     */
    public static LoopDecision degradation(LoopTier tier, String reason, String strategy, String[] affectedNodes) {
        return new LoopDecision(
            tier,
            DecisionAction.DEGRADATION,
            reason,
            Map.of("strategy", strategy),
            Instant.now(),
            0.85,
            affectedNodes
        );
    }
}
package io.etclovg.codepilot.future;

import java.time.Instant;
import java.util.List;

/**
 * 改进结果记录。
 * <p>对应书中 Ch19 §19.5 —— 自我改进的执行结果。
 * <p>描述一次自我改进尝试应用的变更、带来的指标变化与是否回滚，
 * 供自我改进闭环评估成效。
 *
 * @param proposalId 对应的提案 ID
 * @param applied     是否已应用
 * @param changes     应用变更列表
 * @param metricDelta 指标变化
 * @param rolledBack  是否已回滚
 * @param completedAt 完成时间
 */
public record ImprovementResult(
        String proposalId,
        boolean applied,
        List<String> changes,
        double metricDelta,
        boolean rolledBack,
        Instant completedAt
) {

    /**
     * 构造成功应用结果。
     *
     * @param proposalId  提案 ID
     * @param changes     变更列表
     * @param metricDelta 指标变化
     * @return 改进结果
     */
    public static ImprovementResult applied(String proposalId, List<String> changes, double metricDelta) {
        return new ImprovementResult(proposalId, true, changes, metricDelta, false, Instant.now());
    }

    /**
     * 构造已回滚结果。
     *
     * @param proposalId 提案 ID
     * @param reason     回滚原因
     * @return 改进结果
     */
    public static ImprovementResult rolledBack(String proposalId, String reason) {
        return new ImprovementResult(proposalId, true, List.of(), -1.0, true, Instant.now());
    }

    /**
     * 是否为正向改进。
     *
     * @return 指标变化大于 0 返回 true
     */
    public boolean isPositive() {
        return applied && !rolledBack && metricDelta > 0;
    }
}

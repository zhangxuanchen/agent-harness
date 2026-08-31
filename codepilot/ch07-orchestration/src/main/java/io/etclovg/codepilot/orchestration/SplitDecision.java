package io.etclovg.codepilot.orchestration;

import java.util.Map;

/**
 * 拆分决策记录。
 * <p>对应书中 Ch07 §7.5 —— 将复杂任务拆分为子任务的决策记录。
 *
 * @param decisionId   决策 ID
 * @param taskId       原始任务 ID
 * @param strategy     拆分策略
 * @param subTasks     子任务列表
 * @param rationale    决策理由
 * @param timestamp    时间戳
 */
public record SplitDecision(
        String decisionId,
        String taskId,
        String strategy,
        java.util.List<Map<String, Object>> subTasks,
        String rationale,
        long timestamp
) {
    public int getSubTaskCount() {
        return subTasks != null ? subTasks.size() : 0;
    }
}
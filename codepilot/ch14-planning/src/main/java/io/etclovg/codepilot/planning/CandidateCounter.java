package io.etclovg.codepilot.planning;

import org.springframework.stereotype.Component;

/**
 * 候选方案计数器。
 * <p>对应书中 Ch14 §14.2.2 —— 估算任务可能产生的候选方案数量。
 * 用于 ReasoningRouter 路由决策：≥3 候选 → ToT 多方案探索。
 */
@Component
public class CandidateCounter {

    /**
     * 预估候选方案数。
     *
     * @param taskDescription 任务描述
     * @return 候选方案数
     */
    public int estimateCandidates(String taskDescription) {
        if (taskDescription == null) {
            return 1;
        }
        String lower = taskDescription.toLowerCase();
        if (lower.contains("比较") || lower.contains("对比") || lower.contains("选")
            || lower.contains("compare") || lower.contains("choose") || lower.contains("select")) {
            return 3;
        }
        return 1;
    }
}

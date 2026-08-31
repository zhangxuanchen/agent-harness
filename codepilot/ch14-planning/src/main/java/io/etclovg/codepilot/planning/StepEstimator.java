package io.etclovg.codepilot.planning;

import org.springframework.stereotype.Component;

/**
 * 步数估算器。
 * <p>对应书中 Ch14 §14.2.2 —— 估算任务所需的推理步数。
 * 用于 ReasoningRouter 路由决策：≤5 步 → ReAct，6-12 步 → Plan-Execute，>12 步 → ToT。
 */
@Component
public class StepEstimator {

    /**
     * 预估任务所需步数。
     *
     * @param taskDescription 任务描述
     * @return 预估步数
     */
    public int estimateSteps(String taskDescription) {
        if (taskDescription == null) {
            return 3;
        }
        int length = taskDescription.length();
        // 桩实现：按任务长度估算
        if (length < 50) return 3;
        if (length < 150) return 6;
        if (length < 300) return 10;
        return 15;
    }
}

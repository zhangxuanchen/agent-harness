package io.etclovg.codepilot.orchestration;

import java.time.Duration;
import java.util.List;

/**
 * 任务画像记录。
 * <p>对应书中 Ch07 §7.3 —— 编排策略选择的任务画像数据。
 * <p>描述单个任务的复杂度、预计步数、工具依赖与历史成功率，
 * 供编排器在 ReAct/Pipe-ReAct/流水线等模式间选择最合适的执行策略。
 *
 * @param taskId           任务 ID
 * @param complexity       复杂度评分（0-1）
 * @param estimatedSteps   预计步数
 * @param requiredTools    所需工具列表
 * @param historicalSuccessRate 历史成功率（0-1）
 * @param estimatedDuration 预计耗时
 */
public record TaskProfile(
        String taskId,
        double complexity,
        int estimatedSteps,
        List<String> requiredTools,
        double historicalSuccessRate,
        Duration estimatedDuration
) {

    /**
     * 构造带默认字段的任务画像。
     *
     * @param taskId 任务 ID
     * @return 默认画像
     */
    public static TaskProfile defaultProfile(String taskId) {
        return new TaskProfile(taskId, 0.5, 5, List.of(), 0.0, Duration.ofMinutes(2));
    }

    /**
     * 是否为高复杂度任务。
     *
     * @return 复杂度大于等于 0.7 返回 true
     */
    public boolean isHighComplexity() {
        return complexity >= 0.7;
    }

    /**
     * 是否建议人工接管。
     * <p>历史成功率低于 0.3 且复杂度高时建议人工接管。
     *
     * @return 建议人工返回 true
     */
    public boolean shouldEscalateToHuman() {
        return historicalSuccessRate < 0.3 && isHighComplexity();
    }
}

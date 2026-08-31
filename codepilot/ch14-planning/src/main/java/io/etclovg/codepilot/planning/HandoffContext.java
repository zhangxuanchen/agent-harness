package io.etclovg.codepilot.planning;

import java.util.List;

/**
 * 人机交接上下文。
 * <p>对应书中 Ch14 §14.4.3 —— Agent 交接给人类时的结构化信息传输。
 *
 * <p>与章节代码对齐：5 字段 record + 内部 Choice record。
 */
public record HandoffContext(
    String taskSummary,
    List<String> completedSteps,
    String stuckAt,
    String errorDetail,
    List<Choice> choices
) {
    /**
     * 用户可选的操作建议。
     */
    public record Choice(String label, String action, String buttonText) {}
}

package io.etclovg.codepilot.foundation;

/**
 * 失败状态枚举：描述 Agent 在执行过程中可能陷入的失败状态
 * 对应书中 Ch01 §1.3 —— Agent 失败模式分类
 */
public enum FailureState {

    HALLUCINATED_TOOL("幻觉工具", "调用了不存在的工具或传入了不合理参数",
            FaultPattern.HALLUCINATED_CALL),
    INFINITE_LOOP("无限循环", "在几个动作间反复切换，无法收敛",
            FaultPattern.LOOP_DEATH),
    GOAL_DRIFT("目标漂移", "逐渐偏离原始任务目标",
            FaultPattern.DRIFT),
    CONTEXT_OVERFLOW("上下文溢出", "上下文超出预算限制导致关键信息丢失",
            FaultPattern.CONTEXT_DECAY),
    UNEXPECTED_BEHAVIOR("涌现行为", "出现设计者完全未预料的行为",
            FaultPattern.EMERGENT),
    TOOL_UNAVAILABLE("工具不可用", "所需工具执行失败或不可达",
            FaultPattern.HALLUCINATED_CALL),
    TIMEOUT("超时", "执行时间超出预定限制",
            FaultPattern.LOOP_DEATH),
    VALIDATION_FAILED("验证失败", "输出未通过质量验证",
            FaultPattern.DRIFT);

    private final String displayName;
    private final String description;
    private final FaultPattern faultPattern;

    FailureState(String displayName, String description, FaultPattern faultPattern) {
        this.displayName = displayName;
        this.description = description;
        this.faultPattern = faultPattern;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public FaultPattern getFaultPattern() { return faultPattern; }
}
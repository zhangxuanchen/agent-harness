package io.etclovg.codepilot.foundation;

/**
 * Agent 五种故障模式枚举
 * 对应书中 Ch3 §3.1.2 的故障分类
 */
public enum FaultPattern {
    HALLUCINATED_CALL("幻觉调用", 0.31f, "调用不存在的函数/填不合理参数/凭空捏造结果", "T层: 工具校验"),
    LOOP_DEATH("循环卡死", 0.24f, "在几个动作间反复切换，无法收敛", "L层: 编排控制"),
    DRIFT("目标漂移", 0.18f, "逐渐偏离原始任务目标", "C层: 上下文记忆 + 目标锚定"),
    CONTEXT_DECAY("上下文腐烂", 0.15f, "上下文被历史信息填满，忘记关键约束", "C层: 上下文压缩 + 预算管理"),
    EMERGENT("涌现行为", 0.12f, "出现设计者完全未预料的行为", "G层: 治理 + 行为审计");

    private final String name;
    private final float ratio;
    private final String description;
    private final String defenseLayer;

    FaultPattern(String name, float ratio, String description, String defenseLayer) {
        this.name = name;
        this.ratio = ratio;
        this.description = description;
        this.defenseLayer = defenseLayer;
    }

    public String getName() { return name; }
    public float getRatio() { return ratio; }
    public String getDescription() { return description; }
    public String getDefenseLayer() { return defenseLayer; }
}
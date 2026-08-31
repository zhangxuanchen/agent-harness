package io.etclovg.codepilot.foundation;

/**
 * Agent 自主度五级分类（PDA 矩阵）
 * L0 = 纯工具, L1 = 自动化, L2 = 监督下自主, L3 = 半自主, L4 = 全自主
 */
public enum AgentAutonomyLevel {
    L0_TOOL("纯工具", "仅执行预定义命令，无决策能力"),
    L1_AUTOMATION("自动化", "单步自动化，无反馈闭环"),
    L2_Supervised("监督下自主", "PDA 闭环，需人类确认关键决策"),
    L3_SemiAutonomous("半自主", "自主 PDA 闭环 + 策略切换 + 异常上报"),
    L4_FullyAutonomous("全自主", "完全自主决策，仅上报不请求确认");

    private final String label;
    private final String description;

    AgentAutonomyLevel(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }
}
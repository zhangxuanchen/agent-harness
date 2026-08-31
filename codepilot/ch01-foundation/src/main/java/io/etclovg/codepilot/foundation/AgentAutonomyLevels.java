package io.etclovg.codepilot.foundation;

/*
 * ⚠️ SKELETON ONLY: This module is a structural placeholder for the chapter's architecture.
 * Not runnable. It exists to show the class structure and method signatures described in the book.
 * For production implementation, refer to the corresponding chapters in the book and the
 * runnable modules in ch04/ch05/ch06/ch07/ch15/ch16/ch17.
 */

/**
 * Agent 自主度五级分类（L0-L4）。
 *
 * <p>对应书中 Ch1 §1.2 KP 1.2.1 "Agent vs 工作流"——基于 PDA（Perceive-Decide-Act）
 * 判定矩阵的自主度分级，从固定流程（L0）到完全自主（L4）。
 *
 * <p>分级标准：
 * <ul>
 *   <li>L0 脚本执行：无感知、无决策、无自主——纯工作流</li>
 *   <li>L1 条件触发：有感知（读输入）、无决策（固定 if-else）、无自主</li>
 *   <li>L2 单步自主：有感知、单步决策（选工具）、无自主（不决定下一步做什么）</li>
 *   <li>L3 多步自主：有感知、多步决策（规划+执行）、部分自主（在目标约束内自主）</li>
 *   <li>L4 完全自主：有感知、全自主决策（自定目标+自规划+自执行）</li>
 * </ul>
 *
 * <p>判定方法：使用 {@link #isAgentLevel(AutonomyLevel)} 判断是否达到 Agent 门槛（L2 及以上）。
 */
public class AgentAutonomyLevels {

    /** Agent 自主度五级分类 */
    public enum AutonomyLevel {
        L0_SCRIPTED("脚本执行", "固定流程，无感知无决策无自主", false, false, false, false),
        L1_TRIGGERED("条件触发", "有感知（读输入），无决策（固定 if-else），无自主", true, false, false, false),
        L2_SINGLE_STEP("单步自主", "有感知，单步决策（选工具），不决定下一步", true, true, false, false),
        L3_MULTI_STEP("多步自主", "有感知，多步决策（规划+执行），目标约束内自主", true, true, true, false),
        L4_FULLY_AUTONOMOUS("完全自主", "有感知，全自主决策（自定目标+自规划+自执行）", true, true, true, true);

        private final String cnName, description;
        private final boolean hasPerception;  // 感知
        private final boolean hasDecision;    // 决策
        private final boolean hasMultiStep;   // 多步规划
        private final boolean isSelfDirected; // 自定目标

        AutonomyLevel(String cn, String desc, boolean perceive, boolean decide,
                      boolean multiStep, boolean selfDirected) {
            this.cnName = cn; this.description = desc;
            this.hasPerception = perceive; this.hasDecision = decide;
            this.hasMultiStep = multiStep; this.isSelfDirected = selfDirected;
        }

        public String getCnName() { return cnName; }
        public String getDescription() { return description; }
        public boolean hasPerception() { return hasPerception; }
        public boolean hasDecision() { return hasDecision; }
        public boolean hasMultiStep() { return hasMultiStep; }
        public boolean isSelfDirected() { return isSelfDirected; }
    }

    /** 判断是否达到 Agent 门槛——L2 及以上为 Agent（有感知 + 有单步决策） */
    public static boolean isAgentLevel(AutonomyLevel level) {
        return level.ordinal() >= AutonomyLevel.L2_SINGLE_STEP.ordinal();
    }

    /** 根据 PDA 判定矩阵四维度推断自主度等级 */
    public static AutonomyLevel classify(boolean perceives, boolean decides,
                                          boolean multiStep, boolean selfDirected) {
        if (selfDirected) return AutonomyLevel.L4_FULLY_AUTONOMOUS;
        if (multiStep) return AutonomyLevel.L3_MULTI_STEP;
        if (decides) return AutonomyLevel.L2_SINGLE_STEP;
        if (perceives) return AutonomyLevel.L1_TRIGGERED;
        return AutonomyLevel.L0_SCRIPTED;
    }

    /** 打印自主度分级表 */
    public static void main(String[] args) {
        System.out.println("=== Agent 自主度五级分类 ===");
        for (AutonomyLevel level : AutonomyLevel.values()) {
            System.out.printf("  %s %-12s | 感知:%s 决策:%s 多步:%s 自主:%s | %s | Agent:%s%n",
                level.name(), level.getCnName(),
                level.hasPerception() ? "✓" : "✗",
                level.hasDecision() ? "✓" : "✗",
                level.hasMultiStep() ? "✓" : "✗",
                level.isSelfDirected() ? "✓" : "✗",
                level.getDescription(),
                isAgentLevel(level) ? "是" : "否"
            );
        }
    }
}

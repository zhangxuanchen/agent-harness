package io.etclovg.codepilot.foundation;

/**
 * Agent 自主度等级枚举。
 * <p>对应书中 Ch01 §1.2 —— PDA（Perceive-Decide-Act）循环下的自主度分级。
 * <p>不同等级决定了 Agent 在闭环中是否需要人类确认、是否具备策略切换与异常上报能力。
 * <ul>
 *   <li>{@link #MANUAL}        纯人工，无自主决策</li>
 *   <li>{@link #ASSISTED}      辅助执行，每步需人类确认</li>
 *   <li>{@link #SUPERVISED}    监督下自主，关键决策需人类确认</li>
 *   <li>{@link #AUTONOMOUS}    自主执行，仅异常上报</li>
 *   <li>{@link #FULLY_AUTONOMOUS} 全自主，长期运行无需人工介入</li>
 * </ul>
 */
public enum AutonomyLevel {

    /** 纯人工：无自主决策，仅作为工具被调用 */
    MANUAL("纯人工", 0, "无自主决策，仅作为工具被调用"),
    /** 辅助执行：单步自动化，每步需人类确认 */
    ASSISTED("辅助执行", 1, "单步自动化，每步需人类确认"),
    /** 监督下自主：PDA 闭环，关键决策需人类确认 */
    SUPERVISED("监督下自主", 2, "PDA 闭环，关键决策需人类确认"),
    /** 自主执行：自主 PDA 闭环 + 策略切换 + 异常上报 */
    AUTONOMOUS("自主执行", 3, "自主 PDA 闭环 + 策略切换 + 异常上报"),
    /** 全自主：完全自主决策，仅上报不请求确认 */
    FULLY_AUTONOMOUS("全自主", 4, "完全自主决策，仅上报不请求确认");

    private final String label;
    private final int level;
    private final String description;

    AutonomyLevel(String label, int level, String description) {
        this.label = label;
        this.level = level;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public int getLevel() {
        return level;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 判断当前等级是否不低于给定等级。
     *
     * @param other 另一等级
     * @return 若当前等级数值大于等于 other 则返回 true
     */
    public boolean isAtLeast(AutonomyLevel other) {
        return this.level >= other.level;
    }

    /**
     * 是否需要人工确认关键决策。
     *
     * @return 监督及以下等级返回 true
     */
    public boolean requiresHumanConfirmation() {
        return this.level <= SUPERVISED.level;
    }
}

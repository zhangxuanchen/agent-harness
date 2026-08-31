package io.etclovg.codepilot.reliability;

/**
 * 预算层级枚举：定义四层硬预算架构
 * 对应书中 Ch17 — 生产监控可靠性与成本中的预算控制层级
 *
 * <p>四层预算架构从上到下为：
 * <ol>
 *   <li>组织级（ORG）：全局预算上限，最粗粒度控制</li>
 *   <li>用户级（USER）：单用户预算上限，防止单用户占用过多资源</li>
 *   <li>任务级（TASK）：单任务预算上限，防止失控任务浪费资源</li>
 *   <li>节点级（NODE）：单节点预算上限，最细粒度控制</li>
 * </ol>
 *
 * <p>层级越高，控制粒度越粗，熔断阈值越宽松
 */
public enum BudgetTier {
    /** 组织级：全局预算控制 */
    ORGANIZATION("组织级", 4, 0.90, 0.95),

    /** 用户级：单用户预算控制 */
    USER("用户级", 3, 0.85, 0.90),

    /** 任务级：单任务预算控制 */
    TASK("任务级", 2, 0.80, 0.85),

    /** 节点级：单节点预算控制 */
    NODE("节点级", 1, 0.75, 0.80);

    private final String displayName;
    private final int level; // 层级优先级（数字越大优先级越高）
    private final double softThreshold; // 软熔断阈值（告警）
    private final double hardThreshold; // 硬熔断阈值（强制切断）

    BudgetTier(String displayName, int level, double softThreshold, double hardThreshold) {
        this.displayName = displayName;
        this.level = level;
        this.softThreshold = softThreshold;
        this.hardThreshold = hardThreshold;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getLevel() {
        return level;
    }

    public double getSoftThreshold() {
        return softThreshold;
    }

    public double getHardThreshold() {
        return hardThreshold;
    }
}
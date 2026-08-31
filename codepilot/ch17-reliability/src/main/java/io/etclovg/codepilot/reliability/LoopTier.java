package io.etclovg.codepilot.reliability;

/**
 * 控制回路层级枚举：定义三级控制回路
 * 对应书中 Ch17 — 生产监控可靠性与成本中的控制回路理论
 *
 * <p>三级控制回路从快到慢为：
 * <ol>
 *   <li>快回路（FAST）：毫秒级，节点级熔断和实时反馈</li>
 *   <li>中回路（MEDIUM）：分钟级，Canary 分析和灰度发布</li>
 *   <li>慢回路（SLOW）：天级，飞轮优化和全局调优</li>
 * </ol>
 *
 * <p>控制回路理论（参考控制理论）：
 * <ul>
 *   <li>快回路：响应速度最快，但控制范围最小（单节点）</li>
 *   <li>中回路：响应速度中等，控制范围中等（多节点/服务）</li>
 *   <li>慢回路：响应速度最慢，但控制范围最大（全局优化）</li>
 * </ul>
 */
public enum LoopTier {
    /** 快回路：毫秒级，节点级熔断 */
    FAST("快回路", 10, 100, "节点级熔断"),

    /** 中回路：分钟级，Canary 分析 */
    MEDIUM("中回路", 60, 1000, "Canary分析"),

    /** 慢回路：天级，飞轮优化 */
    SLOW("慢回路", 3600, 10000, "飞轮优化");

    private final String displayName;
    private final int periodSeconds; // 回路周期（秒）
    private final int maxSamples; // 最大样本数
    private final String description;

    LoopTier(String displayName, int periodSeconds, int maxSamples, String description) {
        this.displayName = displayName;
        this.periodSeconds = periodSeconds;
        this.maxSamples = maxSamples;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getPeriodSeconds() {
        return periodSeconds;
    }

    public int getMaxSamples() {
        return maxSamples;
    }

    public String getDescription() {
        return description;
    }
}
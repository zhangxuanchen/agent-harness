package io.etclovg.codepilot.mlops;

import java.util.List;

/**
 * 差距分析记录。
 * <p>对应书中 Ch16 §16.10 —— 当前能力与目标能力间的差距分析。
 * <p>描述某个维度的当前值、目标值与差距，供路线图评估使用。
 *
 * @param dimension 维度名称
 * @param current   当前值
 * @param target    目标值
 * @param gap       差距（target - current）
 * @param priority  优先级
 */
public record GapAnalysis(
        String dimension,
        double current,
        double target,
        double gap,
        Priority priority
) {

    /**
     * 构造差距分析并自动计算 gap。
     *
     * @param dimension 维度
     * @param current   当前值
     * @param target    目标值
     * @param priority  优先级
     * @return 差距分析
     */
    public static GapAnalysis of(String dimension, double current, double target, Priority priority) {
        return new GapAnalysis(dimension, current, target, target - current, priority);
    }

    /**
     * 是否存在显著差距。
     *
     * @param threshold 阈值
     * @return 差距大于阈值返回 true
     */
    public boolean hasSignificantGap(double threshold) {
        return Math.abs(gap) > threshold;
    }

    /**
     * 据当前/目标阶段与未满足的就绪项，生成聚合差距分析。
     *
     * @param current 当前阶段
     * @param target  目标阶段
     * @param unmet   未满足的就绪项
     * @return 聚合差距分析
     */
    public static GapAnalysis analyze(Stage current, Stage target, List<ReadinessItem> unmet) {
        double cur = current == null ? 0 : current.ordinal();
        double tgt = target == null ? 0 : target.ordinal();
        Priority p = unmet.size() > 5 ? Priority.CRITICAL
                : unmet.size() > 2 ? Priority.HIGH : Priority.MEDIUM;
        return new GapAnalysis("stage-readiness", cur, tgt, tgt - cur, p);
    }

    /**
     * 估算迈向下一阶段所需周数。
     * <p>对应书中 §16.6 {@code gap.getEstimatedWeeks()}——按未满足项数量与优先级粗略估算：
     * 每个未满足项约 2 周，CRITICAL/HIGH 优先级再加权（投入更大）。返回值为教学性估算，
     * 生产实现应结合团队规模与历史交付速率校准。
     *
     * @return 估算周数
     */
    public int getEstimatedWeeks() {
        double base = Math.max(1.0, Math.abs(gap)) * 2; // 每单位差距约 2 周
        double weight = switch (priority) {
            case CRITICAL -> 2.0;
            case HIGH -> 1.5;
            case MEDIUM -> 1.0;
            case LOW -> 0.5;
        };
        return (int) Math.ceil(base * weight);
    }

    /**
     * 优先级。
     */
    public enum Priority {
        /** 低 */
        LOW,
        /** 中 */
        MEDIUM,
        /** 高 */
        HIGH,
        /** 紧急 */
        CRITICAL
    }
}

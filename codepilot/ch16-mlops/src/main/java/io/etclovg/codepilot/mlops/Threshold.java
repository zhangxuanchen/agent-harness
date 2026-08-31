package io.etclovg.codepilot.mlops;

import java.util.List;

/**
 * 退化阈值。对应书中 Ch16 §16.3.1。
 * <p>三档静态工厂（severe/moderate/subtle）对应 Canary 不同层级的判定严格度；
 * {@link #evaluate(CanaryMetrics)} 据此判定当前指标是否构成降级。
 *
 * @param level    阈值数值（如误差率 0.30）
 * @param severity 严重度档位
 */
public record Threshold(double level, Severity severity) {

    public enum Severity { SUBTLE, MODERATE, SEVERE }

    public static Threshold severe(double level) { return new Threshold(level, Severity.SEVERE); }
    public static Threshold moderate(double level) { return new Threshold(level, Severity.MODERATE); }
    public static Threshold subtle(double level) { return new Threshold(level, Severity.SUBTLE); }

    /** 据阈值判定指标是否构成降级。 */
    public DegradationResult evaluate(CanaryMetrics metrics) {
        boolean degraded = metrics.errorRate() > level;
        if (!degraded) return DegradationResult.none();
        return DegradationResult.of("throttle", List.of("high-cost-tools"), "误差率 " + metrics.errorRate() + " 超阈值 " + level);
    }
}

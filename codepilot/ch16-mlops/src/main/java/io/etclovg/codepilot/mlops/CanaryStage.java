package io.etclovg.codepilot.mlops;

import java.time.Duration;

/**
 * 金丝雀阶段。对应书中 Ch16 §16.3。
 * <p>描述一个 Canary 阶段的流量比例、观察时长与退化判定器。
 * 四阶段渐进式部署 {@code 5%→20%→50%→100%} 各阶段由本类实例描述，
 * 由 {@link CanaryController#startCanary} 顺序执行。
 *
 * <p>退化判定器 {@link Evaluator} 为函数式接口，按阶段阈值差异化判定：
 * 5% 阶段用灾难阈值 30pp 快速回滚，20% 阶段用 5pp，50% 阶段用 3pp 配合
 * {@link ProportionZTest} 双比例 z 检验。
 */
public class CanaryStage {

    /**
     * 阶段退化判定器：给定指标快照，返回是否降级及处置。
     * <p>函数式接口，使 {@code new CanaryStage(0.05, Duration.ofHours(1),
     * metrics -> evaluateStage(metrics, 0.30))} 可直接以 lambda 构造。
     */
    @FunctionalInterface
    public interface Evaluator {
        DegradationResult evaluate(CanaryMetrics metrics);
    }

    private final double trafficPercent;
    private final Duration duration;
    private final Evaluator threshold;

    /**
     * @param trafficPercent 流量占比（0-1，如 0.05 表示 5%）
     * @param duration       观察时长
     * @param threshold      退化判定器（按阶段阈值差异化判定）
     */
    public CanaryStage(double trafficPercent, Duration duration, Evaluator threshold) {
        this.trafficPercent = trafficPercent;
        this.duration = duration;
        this.threshold = threshold;
    }

    /** 流量占比（0-1）。 */
    public double getTrafficPercent() {
        return trafficPercent;
    }

    /** 观察时长。 */
    public Duration getDuration() {
        return duration;
    }

    /** 退化判定器（书中 {@code stage.getThreshold().evaluate(metrics)} 调用）。 */
    public Evaluator getThreshold() {
        return threshold;
    }
}

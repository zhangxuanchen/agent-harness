package io.etclovg.codepilot.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * LLM Judge 漂移检测器。
 * <p>对应书中 Ch09 §9.4 —— LLM-as-Judge 的漂移监控。
 * <p>持续收集 Judge 评分序列，通过滑动窗口统计均值与方差变化，
 * 在 Judge 评分基线发生显著偏移时告警，触发重新校准或人工介入。
 */
@Component
public class JudgeDriftDetector {

    private static final Logger log = LoggerFactory.getLogger(JudgeDriftDetector.class);

    private final int windowSize;
    private final double driftThreshold;
    private final Deque<Double> history;
    private double baselineMean = Double.NaN;
    private double baselineStd = Double.NaN;

    public JudgeDriftDetector() {
        this(50, 0.15);
    }

    public JudgeDriftDetector(int windowSize, double driftThreshold) {
        this.windowSize = windowSize;
        this.driftThreshold = driftThreshold;
        this.history = new ArrayDeque<>(windowSize);
    }

    /**
     * 记录一次 Judge 评分。
     *
     * @param score 评分（0-1）
     * @return 本次记录后的漂移检测结果
     */
    public DriftResult record(double score) {
        history.addLast(score);
        while (history.size() > windowSize) {
            history.removeFirst();
        }
        if (history.size() < windowSize) {
            return DriftResult.insufficient(history.size(), windowSize);
        }

        double[] stats = computeStats();
        double mean = stats[0];
        double std = stats[1];

        if (Double.isNaN(baselineMean)) {
            baselineMean = mean;
            baselineStd = std;
            log.debug("初始化 Judge 基线: mean={}, std={}", mean, std);
            return DriftResult.stable(mean, std);
        }

        double shift = Math.abs(mean - baselineMean);
        if (shift > driftThreshold) {
            log.warn("检测到 Judge 漂移: shift={}, baseline={}, current={}", shift, baselineMean, mean);
            return DriftResult.drifted(baselineMean, mean, shift);
        }
        return DriftResult.stable(mean, std);
    }

    /**
     * 重置基线为当前窗口统计值。
     */
    public void recalibrate() {
        double[] stats = computeStats();
        baselineMean = stats[0];
        baselineStd = stats[1];
        log.info("重置 Judge 基线: mean={}, std={}", baselineMean, baselineStd);
    }

    /**
     * 获取历史评分快照。
     *
     * @return 评分列表
     */
    public List<Double> snapshot() {
        return List.copyOf(history);
    }

    private double[] computeStats() {
        double sum = 0;
        for (double v : history) {
            sum += v;
        }
        double mean = sum / history.size();
        double variance = 0;
        for (double v : history) {
            variance += (v - mean) * (v - mean);
        }
        variance /= history.size();
        return new double[]{mean, Math.sqrt(variance)};
    }

    /**
     * 漂移检测结果记录。
     *
     * @param drifted       是否漂移
     * @param baselineMean  基线均值
     * @param currentMean   当前均值
     * @param shift         偏移量
     * @param samples       样本数
     * @param required      所需样本数
     * @param detectedAt    检测时间
     */
    public record DriftResult(
            boolean drifted,
            double baselineMean,
            double currentMean,
            double shift,
            int samples,
            int required,
            Instant detectedAt
    ) {
        public static DriftResult stable(double mean, double std) {
            return new DriftResult(false, mean, mean, 0.0, 0, 0, Instant.now());
        }

        public static DriftResult drifted(double baseline, double current, double shift) {
            return new DriftResult(true, baseline, current, shift, 0, 0, Instant.now());
        }

        public static DriftResult insufficient(int samples, int required) {
            return new DriftResult(false, Double.NaN, Double.NaN, 0.0, samples, required, Instant.now());
        }
    }
}

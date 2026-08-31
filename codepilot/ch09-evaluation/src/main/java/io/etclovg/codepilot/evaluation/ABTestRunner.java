package io.etclovg.codepilot.evaluation;

import org.apache.commons.math3.distribution.TDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * A/B 测试统计检验运行器。
 *
 * <p>收集新旧版本的配对样本，运行配对 t 检验，计算效应量（Cohen's d），
 * 根据双重判据（p < 0.05 + Cohen's d > 0.2）给出合并/回退建议。
 *
 * <p>对应书中 Ch9 §9.5.2 — A/B 测试的统计陷阱与双重判据。
 *
 * <h3>双重判据逻辑</h3>
 * <ul>
 *   <li>p < 0.05：统计显著性——差异不太可能是偶然</li>
 *   <li>Cohen's d > 0.2：实际意义——差异足够大，值得切换</li>
 *   <li>仅满足其一不合并：防止两类错误</li>
 * </ul>
 *
 * <h3>决策输出</h3>
 * <ul>
 *   <li>MERGE_NEW：新版本显著优于旧版本，建议合并</li>
 *   <li>REVERT_OLD：新版本显著差于旧版本，建议回退</li>
 *   <li>NO_DECISION：差异不显著，继续观察</li>
 *   <li>INCONCLUSIVE：样本量不足或方差异常</li>
 * </ul>
 */
@Component
public class ABTestRunner {

    private static final Logger log = LoggerFactory.getLogger(ABTestRunner.class);

    private static final int MIN_SAMPLES = 100;
    private static final double P_VALUE_THRESHOLD = 0.05;
    private static final double COHENS_D_THRESHOLD = 0.2;
    private static final int MAX_SAMPLES_FOR_OUTLIER = 500;

    private final List<PairedSample> samples = new ArrayList<>();

    public ABTestRunner() {
        log.info("[ABTestRunner] A/B 测试运行器初始化完成，最小样本量={}", MIN_SAMPLES);
    }

    /**
     * 添加配对样本。
     */
    public void addSample(String caseId, double oldScore, double newScore,
                          String taskType, long timestamp) {
        samples.add(new PairedSample(caseId, oldScore, newScore, taskType, timestamp));
    }

    /**
     * 运行配对 t 检验并返回决策建议。
     */
    public ABTestDecision evaluate() {
        return evaluate(samples);
    }

    /**
     * 运行配对 t 检验并返回决策建议（指定样本集）。
     */
    public ABTestDecision evaluate(List<PairedSample> inputSamples) {
        if (inputSamples.isEmpty()) {
            return ABTestDecision.inconclusive("无样本数据");
        }

        if (inputSamples.size() < MIN_SAMPLES) {
            return ABTestDecision.inconclusive(
                    String.format("样本量不足：需要 ≥ %d，当前 %d",
                            MIN_SAMPLES, inputSamples.size()));
        }

        List<Double> diffs = new ArrayList<>();
        for (PairedSample s : inputSamples) {
            diffs.add(s.newScore() - s.oldScore());
        }

        // 计算统计量
        double meanDiff = calculateMean(diffs);
        double stdDiff = calculateStdDev(diffs);

        log.info("[ABTestRunner] 配对 t 检验: n={} meanDiff={:.4f} stdDiff={:.4f}",
                diffs.size(), meanDiff, stdDiff);

        if (stdDiff == 0) {
            return ABTestDecision.inconclusive("标准差为 0，所有样本差异相同");
        }

        // t 统计量
        double tStatistic = meanDiff / (stdDiff / Math.sqrt(diffs.size()));

        // Cohen's d（效应量）
        double cohensD = meanDiff / stdDiff;

        // 计算 p 值
        double pValue;
        try {
            TDistribution tDist = new TDistribution(diffs.size() - 1);
            pValue = 1.0 - tDist.cumulativeProbability(Math.abs(tStatistic));
        } catch (Exception e) {
            log.error("[ABTestRunner] p 值计算失败: {}", e.getMessage());
            return ABTestDecision.inconclusive("t 分布计算异常: " + e.getMessage());
        }

        log.info("[ABTestRunner] 统计结果: t={:.4f} p={:.4f} d={:.4f}",
                tStatistic, pValue, cohensD);

        // 双重判据决策
        if (pValue < P_VALUE_THRESHOLD && cohensD > COHENS_D_THRESHOLD) {
            log.info("[ABTestRunner] ✅ 新版本显著优于旧版本 → 建议合并 (p={:.4f}, d={:.4f})",
                    pValue, cohensD);
            return ABTestDecision.mergeNew(
                    String.format("新版本显著优于旧版本 (p=%.4f, d=%.4f)", pValue, cohensD),
                    pValue, cohensD, meanDiff);
        } else if (pValue < P_VALUE_THRESHOLD && cohensD < -COHENS_D_THRESHOLD) {
            log.warn("[ABTestRunner] ❌ 新版本显著差于旧版本 → 建议回退 (p={:.4f}, d={:.4f})",
                    pValue, cohensD);
            return ABTestDecision.revertOld(
                    String.format("新版本显著差于旧版本 (p=%.4f, d=%.4f)", pValue, cohensD),
                    pValue, cohensD, meanDiff);
        } else {
            log.info("[ABTestRunner] ➖ 差异不显著 (p={:.4f}, d={:.4f}) → 继续观察",
                    pValue, cohensD);
            return ABTestDecision.noDecision(
                    String.format("差异不显著 (p=%.4f, d=%.4f)，继续收集样本", pValue, cohensD),
                    pValue, cohensD, meanDiff);
        }
    }

    /**
     * 按任务类型分段分析。
     */
    public Map<String, ABTestDecision> evaluateByTaskType() {
        Map<String, List<PairedSample>> byType = new HashMap<>();
        for (PairedSample s : samples) {
            byType.computeIfAbsent(s.taskType(), k -> new ArrayList<>()).add(s);
        }

        Map<String, ABTestDecision> results = new LinkedHashMap<>();
        for (Map.Entry<String, List<PairedSample>> entry : byType.entrySet()) {
            ABTestDecision decision = evaluate(entry.getValue());
            results.put(entry.getKey(), decision);
            log.info("[ABTestRunner] 任务类型={} → {}", entry.getKey(), decision.reason());
        }
        return results;
    }

    /**
     * 检测并移除离群点后重新评估。
     */
    public ABTestDecision evaluateWithOutlierRemoval() {
        if (samples.size() < MIN_SAMPLES) {
            return evaluate();
        }

        List<Double> diffs = new ArrayList<>();
        for (PairedSample s : samples) {
            diffs.add(s.newScore() - s.oldScore());
        }

        double q1 = percentile(diffs, 0.25);
        double q3 = percentile(diffs, 0.75);
        double iqr = q3 - q1;
        double lowerBound = q1 - 1.5 * iqr;
        double upperBound = q3 + 1.5 * iqr;

        List<PairedSample> filtered = new ArrayList<>();
        int removedCount = 0;
        for (PairedSample s : samples) {
            double diff = s.newScore() - s.oldScore();
            if (diff >= lowerBound && diff <= upperBound) {
                filtered.add(s);
            } else {
                removedCount++;
            }
        }

        log.info("[ABTestRunner] 离群点检测: 移除 {} 个样本 (IQR 方法)，剩余 {} 个",
                removedCount, filtered.size());

        if (filtered.size() >= MIN_SAMPLES) {
            return evaluate(filtered);
        } else {
            log.warn("[ABTestRunner] 移除离群点后样本不足 ({})，返回全量评估", filtered.size());
            return evaluate();
        }
    }

    /**
     * 检查样本量是否足够。
     */
    public boolean hasEnoughSamples() {
        return samples.size() >= MIN_SAMPLES;
    }

    /**
     * 预估达到统计显著所需的样本量。
     */
    public int estimateRequiredSamples(double expectedCohensD) {
        if (Math.abs(expectedCohensD) < 0.1) {
            return 500;
        }
        // 简化公式：N ≈ 8 / d² (基于 Cohen's power analysis)
        int n = (int) Math.ceil(8.0 / (expectedCohensD * expectedCohensD));
        return Math.max(n, MIN_SAMPLES);
    }

    // ========== 统计辅助方法 ==========

    private double calculateMean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double calculateStdDev(List<Double> values) {
        double mean = calculateMean(values);
        double sumSq = 0;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
        }
        return values.size() > 1 ? Math.sqrt(sumSq / (values.size() - 1)) : 0;
    }

    private double percentile(List<Double> sorted, double p) {
        List<Double> copy = new ArrayList<>(sorted);
        Collections.sort(copy);
        int idx = (int) Math.ceil(p * copy.size()) - 1;
        return copy.get(Math.max(0, Math.min(idx, copy.size() - 1)));
    }

    // ========== 数据类 ==========

    /**
     * 配对样本：新旧版本在同一 case 上的评分。
     */
    public record PairedSample(
            String caseId, double oldScore, double newScore,
            String taskType, long timestamp
    ) {}

    /**
     * A/B 测试决策结果。
     */
    public record ABTestDecision(
            Decision decision, String reason,
            double pValue, double cohensD, double meanDiff
    ) {
        public enum Decision {
            MERGE_NEW,      // 合并新版本
            REVERT_OLD,     // 回退旧版本
            NO_DECISION,    // 继续观察
            INCONCLUSIVE    // 样本不足/异常
        }

        public static ABTestDecision mergeNew(String reason, double p, double d, double diff) {
            return new ABTestDecision(Decision.MERGE_NEW, reason, p, d, diff);
        }

        public static ABTestDecision revertOld(String reason, double p, double d, double diff) {
            return new ABTestDecision(Decision.REVERT_OLD, reason, p, d, diff);
        }

        public static ABTestDecision noDecision(String reason, double p, double d, double diff) {
            return new ABTestDecision(Decision.NO_DECISION, reason, p, d, diff);
        }

        public static ABTestDecision inconclusive(String reason) {
            return new ABTestDecision(Decision.INCONCLUSIVE, reason, Double.NaN, Double.NaN, 0);
        }
    }
}

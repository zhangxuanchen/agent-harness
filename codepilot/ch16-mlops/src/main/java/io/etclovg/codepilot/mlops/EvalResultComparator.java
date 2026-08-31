package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 评估结果比较器。
 * <p>对应书中 Ch16 §16.9 —— 新旧版本评估结果的对比。
 * <p>对比两个版本的 {@link EvalResult}，识别回归、提升与持平的指标，
 * 供发布门禁决策是否放行。
 */
@Component
public class EvalResultComparator {

    private static final Logger log = LoggerFactory.getLogger(EvalResultComparator.class);

    /**
     * 比较两个评估结果。
     *
     * @param baseline 基线结果
     * @param candidate 候选结果
     * @return 对比结论
     */
    public String compare(EvalResult baseline, EvalResult candidate) {
        if (baseline == null || candidate == null) {
            return "INCOMPARABLE";
        }
        double delta = candidate.accuracy() - baseline.accuracy();
        if (delta > 0.01) {
            log.info("评估提升: delta={}", delta);
            return "IMPROVED";
        }
        if (delta < -0.01) {
            log.warn("评估回归: delta={}", delta);
            return "REGRESSED";
        }
        return "NEUTRAL";
    }

    /**
     * 成功率退化（正值表示候选较基线退化）。
     */
    public double compareSuccessRate(EvalResult baseline, EvalResult candidate) {
        return baseline.accuracy() - candidate.accuracy();
    }

    /**
     * 错误率上升（正值表示候选错误率升高）。以失败用例占比近似。
     */
    public double compareErrorRate(EvalResult baseline, EvalResult candidate) {
        double bRate = baseline.failedCases().size();
        double cRate = candidate.failedCases().size();
        return cRate - bRate;
    }

    /**
     * 延迟退化（正值表示候选延迟升高）。
     */
    public double compareLatency(EvalResult baseline, EvalResult candidate) {
        double base = baseline.latencyP95Ms();
        return base == 0 ? 0 : (candidate.latencyP95Ms() - base) / base;
    }
}

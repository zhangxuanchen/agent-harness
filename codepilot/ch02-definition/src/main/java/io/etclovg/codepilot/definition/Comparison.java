package io.etclovg.codepilot.definition;

import java.util.List;
import java.util.Map;

/**
 * 比较结果。
 * <p>对应书中 Ch02 §2.1 —— 对比不同版本/方案的评估结果。
 *
 * @param baselineScore  基线得分
 * @param currentScore   当前得分
 * @param delta          变化量
 * @param dimensionScores 各维度得分
 * @param verdict        判定结论
 */
public record Comparison(
        double baselineScore,
        double currentScore,
        double delta,
        Map<String, Double> dimensionScores,
        String verdict
) {
    public boolean isImproved() {
        return delta > 0;
    }

    public boolean isRegression() {
        return delta < 0;
    }
}
package io.etclovg.codepilot.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 衰减诊断结果。
 * <p>封装上下文腐烂诊断的完整结果，包含探针匹配分数、衰减等级等。
 *
 * <p>页面参考：Ch6 §6.4.1 上下文腐烂诊断
 */
public record DecayDiagnosticResult(
        double totalDecayScore,
        DecayLevel decayLevel,
        List<String> retainedConstraints,
        List<String> lostConstraints,
        List<String> retainedFacts,
        List<String> lostFacts,
        List<ProbeResult> probeResults,
        List<String> recommendations,
        Instant diagnosticTime,
        int step,
        Map<String, Double> metrics
) {

    /**
     * 判断是否需要告警。
     */
    public boolean needsAlert(double threshold) {
        return totalDecayScore > threshold;
    }

    /** 兼容别名——与 record 字段 totalDecayScore 的自动方法并存 */
    public double decayScore() {
        return totalDecayScore;
    }

    public String generateAlertMessage() {
        return String.format("上下文衰减告警: 衰减度=%.2f, 等级=%s, 丢失约束=%d, 丢失事实=%d",
                totalDecayScore, decayLevel.getDisplayName(),
                lostConstraints.size(), lostFacts.size());
    }

    public enum DecayLevel {
        HEALTHY(0.0, 0.3),
        MILD(0.3, 0.5),
        MODERATE(0.5, 0.7),
        SEVERE(0.7, 1.0);

        private final double min;
        private final double max;

        DecayLevel(double min, double max) {
            this.min = min;
            this.max = max;
        }

        public static DecayLevel fromScore(double score) {
            for (DecayLevel level : values()) {
                if (score >= level.min && score < level.max) {
                    return level;
                }
            }
            return SEVERE;
        }

        public String getDisplayName() {
            return name();
        }
    }

    public record ProbeResult(
            String probeId,
            String type,
            String question,
            String expectedAnswer,
            String actualResponse,
            double matchScore,
            boolean matched,
            int responseCount
    ) {}
}
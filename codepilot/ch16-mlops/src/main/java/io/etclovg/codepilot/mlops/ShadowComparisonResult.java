package io.etclovg.codepilot.mlops;

/**
 * 影子流量比较结果记录。
 * <p>对应书中 Ch16 §16.5.1 —— 影子流量对比的结果结构。
 * <p>描述候选版本与线上版本在成功率、幻觉率、响应质量三方面的差异，
 * 供发布门禁、告警与 {@link FallbackDecider} 回退决策引用。
 *
 * @param comparable         是否可比较（样本量足够达到 z 检验正态近似阈值）
 * @param consistency        总体输出一致率（0-1，语义相似判定）
 * @param successDelta       成功率差值：p̂_candidate − p̂_stable（负值=候选退化）
 * @param hallucinationDelta 幻觉率差值：ĥ_candidate − ĥ_stable（正值=候选退化）
 * @param qualityDelta       响应质量分差值（0-100 分，负值=候选退化）
 * @param diffCount          差异数量（样本级差异个数）
 * @param summary            结论摘要
 */
public record ShadowComparisonResult(
        boolean comparable,
        double consistency,
        double successDelta,
        double hallucinationDelta,
        double qualityDelta,
        int diffCount,
        String summary
) {

    /**
     * 构造不可比较结果（样本不足或数据异常）。
     */
    public static ShadowComparisonResult incomparable() {
        return new ShadowComparisonResult(false, 0.0, 0.0, 0.0, 0.0, 0, "样本不可比较");
    }

    /**
     * 构造无差异的健康结果（全通过）。
     */
    public static ShadowComparisonResult healthy() {
        return new ShadowComparisonResult(true, 0.98, 0.0, 0.0, 0.5, 1,
                "影子对比通过，候选版本无显著退化");
    }

    /**
     * 是否到达放行一致性阈值。
     *
     * @param threshold 一致率阈值（0-1）
     * @return 达到返回 true
     */
    public boolean meetsThreshold(double threshold) {
        return comparable && consistency >= threshold;
    }

    /**
     * 是否统计显著退化。
     * <p>判定规则：成功率降低超过 threshold 或幻觉率增加超过 2×threshold
     * （幻觉权重更重——幻觉是安全风险）。
     *
     * @param successDegradationThreshold 成功率退化阈值（如 0.03 = 3 个百分点）
     * @return 退化统计显著返回 true
     */
    public boolean isSignificantlyDegraded(double successDegradationThreshold) {
        if (!comparable) return false;
        return successDelta < -successDegradationThreshold
                || hallucinationDelta > 2 * successDegradationThreshold;
    }

    /**
     * 退化原因说明（供日志与告警使用）。
     */
    public String reason() {
        if (!comparable) return "样本不足，无法判定";
        StringBuilder sb = new StringBuilder();
        if (successDelta < 0)
            sb.append(String.format("成功率降 %.1fpp; ", -successDelta * 100));
        if (hallucinationDelta > 0)
            sb.append(String.format("幻觉率升 %.1fpp; ", hallucinationDelta * 100));
        if (qualityDelta < 0)
            sb.append(String.format("质量分降 %.1f; ", -qualityDelta));
        return sb.length() == 0 ? "未检测到退化" : sb.toString();
    }
}

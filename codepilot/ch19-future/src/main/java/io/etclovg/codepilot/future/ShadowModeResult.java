package io.etclovg.codepilot.future;

/**
 * 影子模式评估结果记录。
 * <p>对应书中 Ch19 §19.5 —— 影子模式评估的结果结构。
 * <p>描述影子模式运行的一致性得分与结论，供自我改进决策是否上线。
 *
 * @param comparable 是否可比较
 * @param score      一致性得分（0-1）
 * @param summary    结论摘要
 */
public record ShadowModeResult(
        boolean comparable,
        double score,
        String summary
) {

    /**
     * 构造不可比较结果。
     *
     * @return 不可比较结果
     */
    public static ShadowModeResult incomparable() {
        return new ShadowModeResult(false, 0.0, "样本不可比较");
    }

    /**
     * 是否达到上线阈值。
     *
     * @param threshold 阈值
     * @return 达到返回 true
     */
    public boolean meetsThreshold(double threshold) {
        return comparable && score >= threshold;
    }
}

package io.etclovg.codepilot.planning;

/**
 * 三级回退结果。
 * <p>对应书中 Ch14 §14.4.2 —— Plan A 失败后的降级方案执行结果。
 *
 * <p>与章节代码对齐：3 参数构造器 {@code new FallbackResult(tier, script, confidence)}。
 */
public record FallbackResult(String tier, String script, double confidence) {

    /**
     * 构造 Plan B 或 Plan C 的执行结果。
     *
     * @param tier       回退层级（Plan B / Plan C）
     * @param script     执行脚本或操作指南
     * @param confidence 置信度
     */
    public static FallbackResult of(String tier, String script, double confidence) {
        return new FallbackResult(tier, script, confidence);
    }
}

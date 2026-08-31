package io.etclovg.codepilot.mlops;

/**
 * 降级结果记录。
 * <p>对应书中 Ch16 §16.6 —— 服务降级决策的结果表示。
 * <p>描述降级后的服务模式、关闭的能力与对用户的影响，
 * 供编排层与告警系统引用。
 *
 * @param degraded        是否已降级
 * @param degradedMode    降级模式名称
 * @param disabledFeatures 被关闭的能力列表
 * @param userImpact      对用户的影响描述
 * @param reason          降级原因
 */
public record DegradationResult(
        boolean degraded,
        String degradedMode,
        java.util.List<String> disabledFeatures,
        String userImpact,
        String reason
) {

    /**
     * 构造未降级结果。
     *
     * @return 未降级结果
     */
    public static DegradationResult none() {
        return new DegradationResult(false, "full", java.util.List.of(), "无影响", "正常运行");
    }

    /**
     * 构造降级结果。
     *
     * @param mode     降级模式
     * @param features 关闭能力
     * @param reason   原因
     * @return 降级结果
     */
    public static DegradationResult of(String mode, java.util.List<String> features, String reason) {
        return new DegradationResult(true, mode, features, "部分功能受限", reason);
    }

    /** 是否已降级（书中 §16.3 {@code result.isDegraded()} 调用）。 */
    public boolean isDegraded() {
        return degraded;
    }

    /** 降级详情/原因（书中 §16.3 {@code result.getDetails()} 调用）。 */
    public String getDetails() {
        return reason;
    }
}

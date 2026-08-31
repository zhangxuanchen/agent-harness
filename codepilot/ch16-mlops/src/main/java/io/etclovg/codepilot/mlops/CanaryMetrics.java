package io.etclovg.codepilot.mlops;

import java.time.Instant;

/**
 * 金丝雀部署指标记录。
 * <p>对应书中 Ch16 §16.3.1 —— 金丝雀发布期间的实时指标快照。
 * <p>描述某一时刻金丝雀分流的双版本指标，供 {@link CanaryController}
 * 据此决定推进、保持或回滚。
 *
 * <p>双版本指标用于 {@link ProportionZTest} 双比例 z 检验：
 * successBaseline/totalBaseline 为稳定版本的成功次数与样本量，
 * successCandidate/totalCandidate 为候选版本的对应值。
 *
 * @param stageId          金丝雀阶段 ID
 * @param trafficPercent   流量占比（0-100）
 * @param errorRate        错误率（0-1）
 * @param latencyP95Ms     P95 延迟（毫秒）
 * @param successRate      候选版本成功率（0-1，用于快速阈值判定）
 * @param successBaseline  稳定版本成功次数（x₁）
 * @param totalBaseline    稳定版本样本总量（n₁）
 * @param successCandidate 候选版本成功次数（x₂）
 * @param totalCandidate   候选版本样本总量（n₂）
 * @param sampledAt        采样时间
 */
public record CanaryMetrics(
        String stageId,
        int trafficPercent,
        double errorRate,
        double latencyP95Ms,
        double successRate,
        int successBaseline,
        int totalBaseline,
        int successCandidate,
        int totalCandidate,
        Instant sampledAt
) {

    /**
     * 构造健康指标快照（双版本指标一致，无退化）。
     *
     * @param stageId 阶段 ID
     * @param traffic 流量占比
     * @return 健康快照
     */
    public static CanaryMetrics healthy(String stageId, int traffic) {
        return new CanaryMetrics(stageId, traffic, 0.01, 120.0, 0.99,
                85, 100, 85, 100, Instant.now());
    }

    /**
     * 是否触发回滚阈值。
     *
     * @param maxErrorRate 最大错误率
     * @param maxLatencyMs 最大延迟
     * @return 超阈返回 true
     */
    public boolean shouldRollback(double maxErrorRate, double maxLatencyMs) {
        return errorRate > maxErrorRate || latencyP95Ms > maxLatencyMs;
    }
}

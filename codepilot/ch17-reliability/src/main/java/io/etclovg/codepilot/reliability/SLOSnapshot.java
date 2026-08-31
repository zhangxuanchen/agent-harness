package io.etclovg.codepilot.reliability;

import java.time.Instant;

/**
 * SLO 快照记录：SLO 指标的时间点快照
 * 对应书中 Ch17 §17.1 —— SLO 监控与度量
 */
public record SLOSnapshot(
        String serviceId,
        double sloTarget,
        double currentSuccessRate,
        double errorBudgetRemaining,
        long totalRequests,
        long errorCount,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        SLOStatus status,
        Instant timestamp
) {
    public boolean isSLOMet() {
        return currentSuccessRate >= sloTarget;
    }

    public boolean isErrorBudgetDepleted() {
        return errorBudgetRemaining <= 0;
    }

    public double getErrorBudgetConsumedPercent() {
        return (1.0 - errorBudgetRemaining) * 100;
    }

    public enum SLOStatus {
        MET, AT_RISK, BREACHED
    }
}
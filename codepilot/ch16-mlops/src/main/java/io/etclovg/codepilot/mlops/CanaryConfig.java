package io.etclovg.codepilot.mlops;

import java.util.List;

/**
 * Canary 配置：金丝雀发布的配置参数
 * 对应书中 Ch16 §16.3 —— 金丝雀部署策略
 */
public record CanaryConfig(
        String deploymentId,
        String canaryVersion,
        int trafficPercent,
        List<String> eligibleUsers,
        List<String> eligibleGroups,
        double healthCheckThreshold,
        int rollbackOnFailureCount,
        boolean autoPromoteEnabled,
        int autoPromoteMinutes
) {
    public CanaryConfig {
        if (trafficPercent < 0 || trafficPercent > 100) {
            throw new IllegalArgumentException("流量百分比必须在 0-100 之间: " + trafficPercent);
        }
        if (healthCheckThreshold < 0 || healthCheckThreshold > 1) {
            throw new IllegalArgumentException("健康检查阈值必须在 0-1 之间: " + healthCheckThreshold);
        }
    }

    /**
     * 从候选模型版本构造 Canary 配置。
     * <p>对应书中 §16.5 {@code new CanaryConfig(newVersion)}——模型更新通过影子流量验证后，
     * 以候选版本为 Canary 版本启动渐进式部署（5%→20%→50%→100%）。
     *
     * @param version 候选模型版本
     */
    public CanaryConfig(ModelVersion version) {
        this(version == null ? "unknown" : version.getId(),
                version == null ? "unknown" : version.getId(),
                5, // 起始流量 5%（首阶段由 CanaryController 接管）
                List.of(), List.of(),
                0.95, 3, false, 60);
    }

    public boolean isUserEligible(String userId, String userGroup) {
        if (eligibleUsers != null && !eligibleUsers.isEmpty()) {
            return eligibleUsers.contains(userId);
        }
        if (eligibleGroups != null && !eligibleGroups.isEmpty()) {
            return eligibleGroups.contains(userGroup);
        }
        return true;
    }

    public boolean shouldRollback(int failureCount) {
        return failureCount >= rollbackOnFailureCount;
    }

    /** 金丝雀标识（书中 §16.3 {@code config.getCanaryId()} 调用，等价于 deploymentId）。 */
    public String getCanaryId() {
        return deploymentId();
    }

    public static CanaryConfig defaultConfig(String deploymentId, String canaryVersion) {
        return new CanaryConfig(
                deploymentId, canaryVersion, 10,
                List.of(), List.of(),
                0.95, 3, false, 60
        );
    }
}
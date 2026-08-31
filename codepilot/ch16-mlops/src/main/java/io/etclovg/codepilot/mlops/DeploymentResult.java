package io.etclovg.codepilot.mlops;

import java.time.Instant;

/**
 * 部署结果记录：记录 Agent 部署操作的结果
 * 对应书中 Ch16 §16.2 —— Agent 部署管理
 */
public record DeploymentResult(
        String deploymentId,
        String version,
        DeploymentStatus status,
        String environment,
        int replicas,
        String message,
        Instant deployedAt,
        Instant completedAt
) {
    public boolean isSuccessful() {
        return status == DeploymentStatus.SUCCESS;
    }

    public boolean isFailed() {
        return status == DeploymentStatus.FAILED;
    }

    public long durationMs() {
        if (deployedAt != null && completedAt != null) {
            return java.time.Duration.between(deployedAt, completedAt).toMillis();
        }
        return 0;
    }

    public enum DeploymentStatus {
        PENDING, DEPLOYING, SUCCESS, FAILED, ROLLED_BACK, CANARY
    }
}
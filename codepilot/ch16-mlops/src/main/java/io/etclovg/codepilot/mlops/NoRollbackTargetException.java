package io.etclovg.codepilot.mlops;

/**
 * 无回滚目标异常：当没有可回滚的版本时抛出
 * 对应书中 Ch16 §16.3 —— 回滚机制
 */
public class NoRollbackTargetException extends RuntimeException {

    private final String deploymentId;
    private final String reason;

    public NoRollbackTargetException(String deploymentId) {
        super("部署无可用回滚目标: " + deploymentId);
        this.deploymentId = deploymentId;
        this.reason = "无历史版本可回滚";
    }

    public NoRollbackTargetException(String deploymentId, String reason) {
        super("部署无可用回滚目标: " + deploymentId + ", 原因: " + reason);
        this.deploymentId = deploymentId;
        this.reason = reason;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public String getReason() {
        return reason;
    }
}
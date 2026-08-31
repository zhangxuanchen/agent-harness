package io.etclovg.codepilot.behavior;

import java.util.Map;

/**
 * 验证失败事件。
 * <p>对应书中 Ch03 §3.4 —— 当验证不通过时由 VerificationMiddleware 发布的事件。
 *
 * @param sessionId 会话 ID
 * @param stepIndex 步骤序号
 * @param score     验证评分（0.0-1.0）
 * @param taskId    任务 ID（兼容旧 API）
 * @param stepId    步骤 ID（兼容旧 API）
 * @param reason    失败原因（兼容旧 API）
 * @param details   详细信息
 * @param timestamp 时间戳
 */
public record VerificationFailedEvent(
        String sessionId,
        int stepIndex,
        double score,
        String taskId,
        String stepId,
        String reason,
        Map<String, Object> details,
        long timestamp
) {
    /** 书中使用的 3 参数构造器 */
    public VerificationFailedEvent(String sessionId, int stepIndex, double score) {
        this(sessionId, stepIndex, score, sessionId, String.valueOf(stepIndex),
             "验证评分 " + score + " 低于阈值", Map.of(), System.currentTimeMillis());
    }

    /** 兼容旧 API 的工厂方法 */
    public static VerificationFailedEvent of(String taskId, String stepId, String reason) {
        return new VerificationFailedEvent(taskId, 0, 0.0, taskId, stepId, reason, Map.of(), System.currentTimeMillis());
    }

    // 兼容旧 API 的访问器
    public String getSessionId() { return sessionId; }
    public int getStepIndex() { return stepIndex; }
    public double getScore() { return score; }
}

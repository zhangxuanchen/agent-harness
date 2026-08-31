package io.etclovg.codepilot.mlops;

import java.time.Instant;
import java.util.List;

/**
 * 事件上下文记录。
 * <p>对应书中 Ch16 §16.4.1 —— 生产事件发生时的上下文快照，五步复盘的输入。
 * <p>记录事件发生时的影响范围、相关变更与指标异常，供 {@code IncidentPostmortemTemplate}
 * 与 {@link TimelineReconstructor} 重建事件时间线。
 *
 * @param incidentId         事件 ID
 * @param severity           严重度
 * @param affectedServices   受影响服务列表
 * @param relatedChanges     相关变更列表
 * @param detectedAt         检测时间
 * @param summary            摘要
 * @param timeWindow         事件时间窗口（供 {@link TimelineReconstructor#reconstruct} 截取日志）
 * @param affectedSessions   受影响会话列表（供 {@link HardeningDeployer#deploy} 重放加固）
 * @param errorDecisionPoint Agent 做出错误决策的具体步骤（供 {@link HarnessLayerDiagnostic#diagnose} 向上追溯）
 * @param impact             业务影响描述（供 {@link DefectRegistry#register} 登记严重度）
 */
public record IncidentContext(
        String incidentId,
        String severity,
        List<String> affectedServices,
        List<String> relatedChanges,
        Instant detectedAt,
        String summary,
        String timeWindow,
        List<String> affectedSessions,
        String errorDecisionPoint,
        String impact
) {

    /** 简便构造（仅指定 ID + 严重度，其余字段取默认）。 */
    public static IncidentContext of(String incidentId, String severity) {
        return new IncidentContext(incidentId, severity, List.of(), List.of(),
                Instant.now(), "", null, List.of(), null, "");
    }

    /** 是否为严重事件（SEV1/SEV2）。 */
    public boolean isSevere() {
        return "SEV1".equalsIgnoreCase(severity) || "SEV2".equalsIgnoreCase(severity);
    }

    /** 事件时间窗口（书中 §16.4.1 {@code incident.getTimeWindow()} 调用）。 */
    public String getTimeWindow() {
        return timeWindow;
    }

    /** 受影响会话列表（书中 {@code incident.getAffectedSessions()} 调用）。 */
    public List<String> getAffectedSessions() {
        return affectedSessions;
    }

    /** Agent 错误决策点（书中 {@code incident.getErrorDecisionPoint()} 调用）。 */
    public String getErrorDecisionPoint() {
        return errorDecisionPoint;
    }

    /** 业务影响（书中 {@code incident.getImpact()} 调用）。 */
    public String getImpact() {
        return impact;
    }
}

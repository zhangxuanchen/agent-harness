package io.etclovg.codepilot.mlops;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 事后复盘报告：可变构建器，由 {@link IncidentPostmortemTemplate#execute} 逐步填充。
 * 对应书中 Ch16 §16.4.1 —— 五步事故复盘的产出容器。
 *
 * <p>设计为<b>可变类</b>（非不可变 record）：五步复盘每一步产出一个子结果
 * （时间线 / 根因 / 缺陷工单 / 回归用例 / 部署结果），逐步 {@code set} 进同一报告对象，
 * 最终作为完整复盘记录返回。这与书中 {@code execute()} 的
 * {@code report.setTimeline(...)} / {@code report.setRootCause(...)} 调用模式一致。
 *
 * <p>嵌套三个值类型：
 * <ul>
 *   <li>{@link Timeline} —— 重建的事件序列 + 错误场景描述</li>
 *   <li>{@link RootCause} —— 根因归因到的 Harness 层 + 描述</li>
 *   <li>{@link DefectTicket} —— 缺陷工单 ID + 修复方案</li>
 * </ul>
 */
public class PostmortemReport {

    private final String reportId;
    private final String incidentId;
    private final IncidentContext incident;
    private final Instant createdAt;

    private Timeline timeline;
    private RootCause rootCause;
    private DefectTicket defectTicket;
    private List<EvalCase> newEvalCases = List.of();
    private DeploymentResult deploymentResult;

    /** 由 {@link IncidentPostmortemTemplate#execute} 构造，绑定事故上下文。 */
    public PostmortemReport(IncidentContext incident) {
        this.reportId = "pm-" + UUID.randomUUID().toString().substring(0, 8);
        this.incident = incident;
        this.incidentId = incident == null ? null : incident.incidentId();
        this.createdAt = Instant.now();
    }

    public String getReportId() { return reportId; }
    public String getIncidentId() { return incidentId; }
    public IncidentContext getIncident() { return incident; }
    public Instant getCreatedAt() { return createdAt; }

    public Timeline getTimeline() { return timeline; }
    public void setTimeline(Timeline timeline) { this.timeline = timeline; }

    public RootCause getRootCause() { return rootCause; }
    public void setRootCause(RootCause rootCause) { this.rootCause = rootCause; }

    public DefectTicket getDefectTicket() { return defectTicket; }
    public void setDefectTicket(DefectTicket defectTicket) { this.defectTicket = defectTicket; }

    public List<EvalCase> getNewEvalCases() { return newEvalCases; }
    public void setNewEvalCases(List<EvalCase> newEvalCases) { this.newEvalCases = newEvalCases; }

    public DeploymentResult getDeploymentResult() { return deploymentResult; }
    public void setDeploymentResult(DeploymentResult deploymentResult) { this.deploymentResult = deploymentResult; }

    /**
     * 时间线：重建的事件序列 + 错误场景描述。
     * <p>由 {@link TimelineReconstructor#reconstruct} 产出，供 Step 2 层级诊断
     * 与 Step 4 评估用例生成引用。
     */
    public record Timeline(List<TimelineReconstructor.TimelineEvent> events, String errorScenario) {
        /** 错误场景描述（供 {@link EvalCaseGenerator#generateFrom} 转化为回归用例）。 */
        public String getErrorScenario() {
            return errorScenario;
        }
    }

    /**
     * 根因：归因到的 Harness 层 + 具体缺陷描述。
     * <p>由 {@link HarnessLayerDiagnostic#diagnose} 产出。
     */
    public record RootCause(HarnessLayer layer, String description) {
        public HarnessLayer getLayer() {
            return layer;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 缺陷工单：登记到 {@link DefectRegistry} 后的工单 ID + 修复方案。
     * <p>由 {@link DefectRegistry#register(HarnessLayer, String, String)} 产出。
     */
    public record DefectTicket(String id, String fix) {
        public String getId() {
            return id;
        }

        public String getFix() {
            return fix;
        }
    }
}

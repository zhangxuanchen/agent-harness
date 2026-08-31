package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 事故事后复盘模板引擎。对应书中 Ch16 §16.4.1 —— 五步事故复盘。
 * <p>引导工程师按固定流程完成事故分析，每一步都有产出：
 * <ol>
 *   <li>时间线还原 —— {@link TimelineReconstructor#reconstruct}</li>
 *   <li>层级定位 —— {@link HarnessLayerDiagnostic#diagnose}（E→C→T→L→V→O→G 逐层排除）</li>
 *   <li>缺陷登记 —— {@link DefectRegistry#register}</li>
 *   <li>回归覆盖 —— {@link EvalCaseGenerator#generateFrom} + {@link EvalRegistry#addAll}</li>
 *   <li>加固部署 —— {@link HardeningDeployer#deploy}</li>
 * </ol>
 *
 * <p>复盘产出不是报告文档，而是可自动执行的回归测试和防护规则——这是
 * "48 小时反馈闭环"的核心载体：生产故障 → 回归用例 → CI 验证 → 重新部署。
 */
@Service
public class IncidentPostmortemTemplate {

    private static final Logger log = LoggerFactory.getLogger(IncidentPostmortemTemplate.class);

    private final TimelineReconstructor timelineReconstructor;
    private final HarnessLayerDiagnostic layerDiagnostic;
    private final DefectRegistry defectRegistry;
    private final EvalCaseGenerator evalCaseGenerator;
    private final HardeningDeployer hardeningDeployer;
    private final EvalRegistry evalRegistry;

    public IncidentPostmortemTemplate(TimelineReconstructor timelineReconstructor,
                                      HarnessLayerDiagnostic layerDiagnostic,
                                      DefectRegistry defectRegistry,
                                      EvalCaseGenerator evalCaseGenerator,
                                      HardeningDeployer hardeningDeployer,
                                      EvalRegistry evalRegistry) {
        this.timelineReconstructor = timelineReconstructor;
        this.layerDiagnostic = layerDiagnostic;
        this.defectRegistry = defectRegistry;
        this.evalCaseGenerator = evalCaseGenerator;
        this.hardeningDeployer = hardeningDeployer;
        this.evalRegistry = evalRegistry;
    }

    /**
     * 执行五步事故复盘，返回逐步填充的复盘报告。
     *
     * @param incident 事件上下文
     * @return 复盘报告（含时间线、根因、缺陷工单、回归用例、部署结果）
     */
    public PostmortemReport execute(IncidentContext incident) {
        log.info("启动五步复盘: incident={}", incident.incidentId());
        PostmortemReport report = new PostmortemReport(incident);

        // Step 1: 时间线还原
        report.setTimeline(
                timelineReconstructor.reconstruct(
                        incident.getTimeWindow(),
                        incident.getAffectedSessions()
                )
        );

        // Step 2: 层级定位
        report.setRootCause(
                layerDiagnostic.diagnose(
                        report.getTimeline(),
                        incident.getErrorDecisionPoint()
                )
        );

        // Step 3: 缺陷登记
        report.setDefectTicket(
                defectRegistry.register(
                        report.getRootCause().getLayer(),
                        report.getRootCause().getDescription(),
                        incident.getImpact()
                )
        );

        // Step 4: 回归覆盖
        List<EvalCase> newCases = evalCaseGenerator.generateFrom(
                report.getTimeline().getErrorScenario(),
                report.getRootCause()
        );
        evalRegistry.addAll(newCases);
        report.setNewEvalCases(newCases);

        // Step 5: 加固部署
        report.setDeploymentResult(
                hardeningDeployer.deploy(
                        report.getDefectTicket().getFix(),
                        newCases
                )
        );

        log.info("复盘完成: incident={}, rootLayer={}, newCases={}",
                incident.incidentId(), report.getRootCause().getLayer(), newCases.size());
        return report;
    }
}

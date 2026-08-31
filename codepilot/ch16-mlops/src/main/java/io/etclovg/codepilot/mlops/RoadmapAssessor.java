package io.etclovg.codepilot.mlops;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线图评估器。
 * <p>对应书中 Ch16 §16.6 —— 从 Demo 到生产的四阶段成熟度路线图评估。
 *
 * <p>根据七个 Harness 层（E-C-T-L-V-O-G）的建设成熟度判定团队当前所处阶段
 * （Demo / 试点 / 规模 / 演进），结合就绪检查清单列出距下一阶段的未满足条件，
 * 经差距分析生成优先级排序的投入建议。各阶段必备层与判定阈值见书中 §16.6.1。
 */
@Component
public class RoadmapAssessor {

    private final StageEvaluator evaluator;
    private final ReadinessChecklist checklist;

    public RoadmapAssessor(StageEvaluator evaluator, ReadinessChecklist checklist) {
        this.evaluator = evaluator;
        this.checklist = checklist;
    }

    /**
     * 评估项目当前所处阶段与迈向下一阶段的建议。
     * <p>对应书中 §16.6 {@code RoadmapAssessor.assess(projectId)}。
     *
     * @param projectId 项目 ID
     * @return 路线图评估报告
     */
    public RoadmapReport assess(String projectId) {
        // 评估各 Harness 层的建设成熟度
        Map<HarnessLayer, Integer> maturity = new HashMap<>();
        for (HarnessLayer layer : HarnessLayer.values()) {
            maturity.put(layer, evaluator.evaluate(layer, projectId));
        }

        // 判断当前阶段
        Stage currentStage = determineStage(maturity);
        Stage targetStage = currentStage.next();

        // 检查进入下一阶段的条件
        List<ReadinessItem> readiness = checklist.check(currentStage, targetStage, projectId);
        List<ReadinessItem> unmet = readiness.stream()
                .filter(r -> !r.isMet())
                .toList();

        // 分析差距并生成行动建议
        GapAnalysis gap = GapAnalysis.analyze(currentStage, targetStage, unmet);
        List<ActionItem> actions = TransitionMiddleware.recommend(gap, priorityOf(unmet));

        return RoadmapReport.builder()
                .currentStage(currentStage)
                .targetStage(targetStage)
                .maturityScores(maturity)
                .unmetConditions(unmet)
                .recommendedActions(actions)
                .estimatedTimeToNextStage(gap.getEstimatedWeeks())
                .build();
    }

    /**
     * 据各层成熟度判定当前阶段。
     * <p>判定规则（书中 §16.6.1）：
     * <ul>
     *   <li>T 或 L &lt; 50 → Demo（工具 + 编排未就绪）</li>
     *   <li>E/V/G 未达阈值 → 试点（评估 + 校验 + 治理未就绪）</li>
     *   <li>O 或 C &lt; 50 → 规模（可观测 + 成本未就绪）</li>
     *   <li>否则 → 演进</li>
     * </ul>
     */
    private Stage determineStage(Map<HarnessLayer, Integer> maturity) {
        int t = maturity.get(HarnessLayer.T);
        int l = maturity.get(HarnessLayer.L);
        int e = maturity.get(HarnessLayer.E);
        int v = maturity.get(HarnessLayer.V);
        int g = maturity.get(HarnessLayer.G);
        int o = maturity.get(HarnessLayer.O);
        int c = maturity.get(HarnessLayer.C);

        // 阶段判定规则
        if (t < 50 || l < 50) return Stage.DEMO;
        if (e < 60 || v < 50 || g < 40) return Stage.PILOT;
        if (o < 50 || c < 50) return Stage.SCALE;
        return Stage.EVOLVE;
    }

    /**
     * 据未满足项数量推导优先级标签。
     *
     * @param unmet 未满足的就绪项
     * @return 优先级标签（"CRITICAL" / "HIGH" / "MEDIUM"）
     */
    private String priorityOf(List<ReadinessItem> unmet) {
        if (unmet.size() > 5) return "CRITICAL";
        if (unmet.size() > 2) return "HIGH";
        return "MEDIUM";
    }
}

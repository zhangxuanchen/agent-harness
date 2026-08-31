package io.etclovg.codepilot.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 拆分决策器（配套仓库教学实现，非框架内置）。
 * <p>对应书中 Ch07 §7.2.1 —— 基于四个维度打分判断是否需要拆分为多 Agent。
 * <p>四个维度及"高"阈值：
 * <ul>
 *   <li>专业跨度：≥2 个不同领域 → 高</li>
 *   <li>步骤独立性：≥3 个可并行步骤 → 高</li>
 *   <li>上下文需求：单步 > 10K tokens → 高</li>
 *   <li>状态耦合：< 0.4（少量共享）→ 高（耦合低才适合拆）</li>
 * </ul>
 * <p>打分规则：0 项高 = 单 Agent 足够，1 项高 = 可考虑拆分，≥2 项高 = 强烈建议拆分。
 */
public class AgentSplitDecider {

    private static final int HETERO_THRESHOLD = 2;
    private static final int PARALLEL_STEP_THRESHOLD = 3;
    private static final int STEP_TOKEN_THRESHOLD = 10_000;
    private static final double COUPLING_THRESHOLD = 0.4;

    /**
     * 评估任务是否需要拆分为多 Agent。
     *
     * @param task 任务画像
     * @return 拆分决策（STRONG_SPLIT / CONSIDER_SPLIT / SINGLE_AGENT）
     */
    public SplitDecision evaluateSplit(TaskProfile task) {
        boolean highHeterogeneity = countDistinctDomains(task) >= HETERO_THRESHOLD;
        boolean highStepIndependence = countParallelSteps(task) >= PARALLEL_STEP_THRESHOLD;
        boolean highContextDemand = estimateStepTokens(task) > STEP_TOKEN_THRESHOLD;
        boolean lowCoupling = computeCouplingScore(task) < COUPLING_THRESHOLD;

        int highCount = countTrue(highHeterogeneity, highStepIndependence,
                                   highContextDemand, lowCoupling);

        if (highCount >= 2) {
            return buildSplitDecision(task, "STRONG_SPLIT", highCount);
        } else if (highCount == 1) {
            return buildSplitDecision(task, "CONSIDER_SPLIT", highCount);
        }
        return new SplitDecision("sd-single", task.taskId(), "SINGLE_AGENT",
            List.of(Map.of("id", task.taskId())),
            "四项全低，单 Agent 足够", System.currentTimeMillis());
    }

    private SplitDecision buildSplitDecision(TaskProfile task, String strategy, int highCount) {
        int subTaskCount = Math.max(2, countDistinctDomains(task));
        List<Map<String, Object>> subTasks = new ArrayList<>();
        for (int i = 0; i < subTaskCount; i++) {
            subTasks.add(Map.of("id", "sub-" + i, "domain", "domain-" + i));
        }
        return new SplitDecision("sd-" + UUID.randomUUID().toString().substring(0, 8),
            task.taskId(), strategy, subTasks,
            highCount + " 项高，" + strategy, System.currentTimeMillis());
    }

    private int countTrue(boolean... flags) {
        int c = 0;
        for (boolean f : flags) if (f) c++;
        return c;
    }

    // 以下方法完整实现（基于 AST 依赖分析 + 工具调用历史统计）见配套仓库
    private int countDistinctDomains(TaskProfile task) { return 0; }
    private int countParallelSteps(TaskProfile task) { return 0; }
    private int estimateStepTokens(TaskProfile task) { return 0; }
    private double computeCouplingScore(TaskProfile task) { return 0.0; }
}

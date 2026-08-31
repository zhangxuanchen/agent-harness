package io.etclovg.codepilot.definition;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 评估驱动开发（EDD）方法论。
 * <p>对应书中 Ch02 §2.1 —— Eval-Driven Development 方法论的核心抽象。
 * <p>通过持续评估驱动 Agent Harness 的迭代优化。
 */
@Component
public class EvalDrivenDevelopment {

    private final List<EvaluationCycle> cycles = new ArrayList<>();

    /**
     * 评估周期记录。
     */
    public record EvaluationCycle(
            String cycleId,
            String goal,
            String testSet,
            double score,
            List<String> findings,
            long timestamp
    ) {}

    /**
     * 执行一个评估周期。
     */
    public EvaluationCycle runCycle(String goal, String testSet) {
        EvaluationCycle cycle = new EvaluationCycle(
                UUID.randomUUID().toString().substring(0, 8),
                goal, testSet, 0.0, List.of(), System.currentTimeMillis()
        );
        cycles.add(cycle);
        return cycle;
    }

    /**
     * 获取评估历史。
     */
    public List<EvaluationCycle> getCycles() {
        return Collections.unmodifiableList(cycles);
    }

    /**
     * 获取最新得分。
     */
    public OptionalDouble getLatestScore() {
        return cycles.stream().mapToDouble(EvaluationCycle::score).findFirst();
    }

    /**
     * 对比两个评估周期的得分变化。
     *
     * @param baseline 变更前的基线评估周期
     * @param current  变更后的当前评估周期
     * @return 对比结果（含 delta 和 verdict）
     */
    public Comparison compare(EvaluationCycle baseline, EvaluationCycle current) {
        double delta = current.score() - baseline.score();
        String verdict = delta < -0.05 ? "BLOCKED" : (delta > 0 ? "PASS" : "NEUTRAL");
        return new Comparison(baseline.score(), current.score(), delta, Map.of(), verdict);
    }

    /**
     * 评估一次变更的影响——对比变更前后的评估周期，生成变更报告。
     *
     * <p>EDD 核心方法：每次代码/prompt/配置变更后，对比变更前后的评估分数，
     * 若 delta < -0.05（故障超 5%）则标记为 HIGH 风险，需人工复核。
     *
     * @param baseline     变更前的基线评估周期
     * @param current      变更后的当前评估周期
     * @param description  变更描述
     * @param filesChanged 变更涉及的文件列表
     * @return 变更报告（含风险等级和变更类型）
     */
    public ChangeReport evaluateChange(EvaluationCycle baseline, EvaluationCycle current,
                                       String description, List<String> filesChanged) {
        double delta = current.score() - baseline.score();
        String changeType = delta > 0 ? "IMPROVEMENT" : (delta < -0.05 ? "REGRESSION" : "NEUTRAL");
        String riskLevel = delta < -0.05 ? "HIGH" : "LOW";
        return new ChangeReport(
                UUID.randomUUID().toString().substring(0, 8),
                changeType, description, filesChanged, riskLevel, System.currentTimeMillis()
        );
    }
}
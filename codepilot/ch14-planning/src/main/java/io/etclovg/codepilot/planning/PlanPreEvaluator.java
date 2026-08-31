package io.etclovg.codepilot.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 计划预评估器（V 层）。
 * <p>对应书中 Ch14 §14.5 —— 低成本计划预验证，在执行前花 <1% 预算验证计划质量。
 * 通过三维评分（完整性 40% + 可操作性 30% + 依赖合理性 30%）与快速 dry-run 检查，
 * 避免"执行完才发现计划不可行"的浪费。
 *
 * <p>与章节代码对齐：流式 builder 模式，支持 .evaluatorModel / .dimensions /
 * .passThreshold / .onReject / .build()。
 */
@Component
public class PlanPreEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PlanPreEvaluator.class);

    private String evaluatorModel;
    private List<EvalDimension> dimensions;
    private double passThreshold = 0.7;
    private BiConsumer<String, String> onRejectFn;
    private Function<String, Boolean> feasibilityCheck;

    public Builder builder() {
        return new Builder(this);
    }

    public static class Builder {
        private final PlanPreEvaluator evaluator;

        Builder(PlanPreEvaluator evaluator) {
            this.evaluator = evaluator;
        }

        public Builder evaluatorModel(String model) {
            evaluator.evaluatorModel = model;
            return this;
        }

        public Builder dimensions(List<EvalDimension> dims) {
            evaluator.dimensions = dims;
            return this;
        }

        public Builder passThreshold(double threshold) {
            evaluator.passThreshold = threshold;
            return this;
        }

        public Builder onReject(BiConsumer<String, String> fn) {
            evaluator.onRejectFn = fn;
            return this;
        }

        public PlanPreEvaluator build() {
            return evaluator;
        }
    }

    /**
     * 三维预评估。
     *
     * @param plan 计划内容
     * @return 预评估结果
     */
    public PreEvaluationResult evaluate(String plan) {
        double score = computeScore(plan);
        boolean passed = score >= passThreshold;

        if (!passed && onRejectFn != null) {
            onRejectFn.accept(plan, String.valueOf(score));
        }

        return new PreEvaluationResult(passed, score, evaluatorModel != null ? evaluatorModel : "default");
    }

    private double computeScore(String plan) {
        if (dimensions == null || dimensions.isEmpty()) {
            // 默认三维等权
            return plan != null && plan.length() > 10 ? 0.8 : 0.3;
        }
        // 桩实现：按维度权重计算加权分
        double totalWeight = dimensions.stream().mapToDouble(EvalDimension::getWeight).sum();
        double weightedScore = dimensions.stream()
            .mapToDouble(d -> {
                // 桩实现：简化为按计划长度评分
                double dimScore = plan != null && plan.length() > 20 ? 0.85 : 0.4;
                return dimScore * d.getWeight();
            }).sum();
        return totalWeight > 0 ? weightedScore / totalWeight : 0.5;
    }

    /**
     * 预评估结果。
     */
    public record PreEvaluationResult(boolean passed, double score, String modelUsed) {}
}

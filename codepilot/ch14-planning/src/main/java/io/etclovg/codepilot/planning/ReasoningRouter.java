package io.etclovg.codepilot.planning;

import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 推理路由器（L 层）。
 * <p>对应书中 Ch14 §14.2 —— 根据任务特征自动选择推理范式。
 * 通过可预测性分析、预估步数、候选方案数三个特征，路由到 ReWOO/ReAct/ToT/Plan-Execute。
 *
 * <p>与章节代码对齐：流式 builder 模式，支持 {@code .analyze(task -> ...)} 和 {@code .build()}。
 */
@Component
public class ReasoningRouter {

    /**
     * 推理范式枚举。
     */
    public enum Paradigm {
        REWOO,      // 工具返回可预测，2 次 LLM 调用
        REACT,      // 简单任务，≤5 步
        PLAN_EXECUTE, // 中长任务，6-12 步，避免上下文膨胀
        TOT         // 多方案比较，>12 步 + ≥3 候选
    }

    private Function<TaskContext, Paradigm> analyzeFn;
    private CostEstimator costEstimator;
    private Object dynamicUpgrader; // DynamicUpgrader 占位（独立 Bean 注入）

    /**
     * 流式 builder。
     */
    public Builder builder() {
        return new Builder(this);
    }

    /**
     * 内部 builder 类。
     */
    public static class Builder {
        private final ReasoningRouter router;

        Builder(ReasoningRouter router) {
            this.router = router;
        }

        /**
         * 设置分析函数（任务特征 → 范式决策）。
         */
        public Builder analyze(Function<TaskContext, Paradigm> fn) {
            router.analyzeFn = fn;
            return this;
        }

        /**
         * 注入成本估算器。
         */
        public Builder costEstimator(CostEstimator estimator) {
            router.costEstimator = estimator;
            return this;
        }

        /**
         * 注入动态升级器（占位，实际由 Spring 容器注入）。
         */
        public Builder dynamicUpgrader(Object upgrader) {
            router.dynamicUpgrader = upgrader;
            return this;
        }

        public ReasoningRouter build() {
            return router;
        }
    }

    /**
     * 任务上下文（供 analyze 函数判断）。
     */
    public record TaskContext(boolean predictable, int steps, int candidates, String task) {}

    /**
     * 路由决策。
     */
    public Paradigm route(TaskContext task) {
        if (analyzeFn != null) {
            return analyzeFn.apply(task);
        }
        // 默认规则：与章节 §14.2.1 一致
        if (task.predictable()) return Paradigm.REWOO;
        if (task.steps() <= 5) return Paradigm.REACT;
        if (task.steps() > 12 && task.candidates() >= 3) return Paradigm.TOT;
        return Paradigm.PLAN_EXECUTE;
    }

    /**
     * 获取成本估算器。
     */
    public CostEstimator getCostEstimator() {
        return costEstimator;
    }
}

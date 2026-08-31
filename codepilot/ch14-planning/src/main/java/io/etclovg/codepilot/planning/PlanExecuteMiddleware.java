package io.etclovg.codepilot.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Plan-Execute 中间件（L 层）。
 * <p>对应书中 Ch14 §14.1 KP 14.1.2 —— Plan-Execute 范式实现。
 * Plan（1 次 LLM 生成完整计划）→ Execute（N 次独立执行，不累积上下文）。
 *
 * <p>成本特征：执行阶段每步只接收子任务描述 + 上游摘要，无 O(N²) 上下文膨胀。
 * 适合 6-12 步的中长任务。
 *
 * <p>与章节代码对齐：流式 builder 模式，支持 .plan / .execute / .maxSteps / .build()。
 */
@Component
public class PlanExecuteMiddleware {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteMiddleware.class);

    private Object agent;
    private Function<String, ExecutionPlan> planFn;                    // Plan 阶段：1 次 LLM
    private BiFunction<ExecutionPlan.Step, String, String> executeFn;  // Execute 阶段：每步独立
    private int maxSteps = 20;

    public Builder builder() {
        return new Builder(this);
    }

    public static class Builder {
        private final PlanExecuteMiddleware middleware;

        Builder(PlanExecuteMiddleware middleware) {
            this.middleware = middleware;
        }

        public Builder agent(Object agent) {
            middleware.agent = agent;
            return this;
        }

        public Builder plan(Function<String, ExecutionPlan> fn) {
            middleware.planFn = fn;
            return this;
        }

        public Builder execute(BiFunction<ExecutionPlan.Step, String, String> fn) {
            middleware.executeFn = fn;
            return this;
        }

        public Builder maxSteps(int steps) {
            middleware.maxSteps = steps;
            return this;
        }

        public PlanExecuteMiddleware build() {
            return middleware;
        }
    }

    /**
     * 执行计划 DTO（Plan 阶段输出）。
     */
    public record ExecutionPlan(List<Step> steps) {
        public record Step(String description, String upstreamSummaryHint) {}
    }

    /**
     * 执行 Plan-Execute 流水线。
     *
     * @param task 任务描述
     * @return 最终结果
     */
    public String execute(String task) {
        // 阶段 1: Plan — 1 次 LLM 调用，生成完整执行计划
        ExecutionPlan plan = planFn.apply(task);
        if (plan == null || plan.steps().isEmpty()) {
            log.warn("Plan-Execute Plan 阶段返回空计划");
            return "Plan 阶段失败：未生成有效计划";
        }
        log.info("Plan 阶段完成，共 {} 步", plan.steps().size());

        // 阶段 2: Execute — N 次独立执行，不累积上下文
        String upstreamSummary = "";  // 上游摘要（非完整历史）
        StringBuilder finalResult = new StringBuilder();

        for (int i = 0; i < Math.min(plan.steps().size(), maxSteps); i++) {
            ExecutionPlan.Step step = plan.steps().get(i);

            // 每步只接收：子任务描述 + 上游摘要（非全部历史）
            // 这是 Plan-Execute 避免上下文膨胀的关键
            String result = executeFn.apply(step, upstreamSummary);
            log.debug("Execute step {}/{}: {}", i + 1, plan.steps().size(), result);

            // 更新上游摘要（只保留摘要，不保留完整结果——压缩上下文）
            upstreamSummary = "Step " + (i + 1) + " 完成: " + truncate(result, 200);
            finalResult.append(result).append("\n");
        }

        return finalResult.toString();
    }

    /**
     * 截断为指定长度的摘要。
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}

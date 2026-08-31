package io.etclovg.codepilot.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * ReWOO 中间件（L 层）。
 * <p>对应书中 Ch14 §14.1 —— ReWOO 范式实现，三阶段执行：
 * Plan（1 次 LLM 调用生成完整推理计划 + 工具标注）→
 * Execute（批量并行工具调用，0 次 LLM）→
 * Synthesize（1 次 LLM 合成最终答案）。
 *
 * <p>与章节代码对齐：流式 builder 模式，支持 .planPhase / .executePhase / .synthesizePhase /
 * .predictabilityGate / .onRejectFallback / .build()。
 */
@Component
public class ReWOOMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ReWOOMiddleware.class);

    private Object agent;
    private Object toolRegistry;
    private Function<String, Object> planPhaseFn;
    private BiFunction<Object, Object, List<Object>> executePhaseFn;
    private BiFunction<Object, List<Object>, String> synthesizePhaseFn;
    private Function<String, Boolean> predictabilityGateFn;
    private ReasoningRouter.Paradigm fallbackParadigm;

    /**
     * 推理计划 DTO（Plan 阶段输出）。
     */
    public record ReasoningPlan(List<Step> steps) {
        public record Step(String toolName, Map<String, Object> params) {}
    }

    public Builder builder() {
        return new Builder(this);
    }

    public static class Builder {
        private final ReWOOMiddleware middleware;

        Builder(ReWOOMiddleware middleware) {
            this.middleware = middleware;
        }

        public Builder agent(Object agent) {
            middleware.agent = agent;
            return this;
        }

        public Builder toolRegistry(Object toolRegistry) {
            middleware.toolRegistry = toolRegistry;
            return this;
        }

        public Builder planPhase(Function<String, Object> fn) {
            middleware.planPhaseFn = fn;
            return this;
        }

        public Builder executePhase(BiFunction<Object, Object, List<Object>> fn) {
            middleware.executePhaseFn = fn;
            return this;
        }

        public Builder synthesizePhase(BiFunction<Object, List<Object>, String> fn) {
            middleware.synthesizePhaseFn = fn;
            return this;
        }

        public Builder predictabilityGate(Function<String, Boolean> fn) {
            middleware.predictabilityGateFn = fn;
            return this;
        }

        public Builder onRejectFallback(ReasoningRouter.Paradigm paradigm) {
            middleware.fallbackParadigm = paradigm;
            return this;
        }

        public ReWOOMiddleware build() {
            return middleware;
        }
    }

    /**
     * 判断任务是否适合 ReWOO。
     */
    public boolean supports(String taskDescription) {
        if (predictabilityGateFn != null) {
            return Boolean.TRUE.equals(predictabilityGateFn.apply(taskDescription));
        }
        // 默认逻辑：批量或确定性操作
        return taskDescription != null
            && (taskDescription.toLowerCase().contains("批量") || taskDescription.toLowerCase().contains("batch"));
    }

    /**
     * 执行 ReWOO 三阶段流水线。
     *
     * @param task 任务描述
     * @return 合成后的最终答案
     */
    public String execute(String task) {
        // 阶段 1: Plan — 1 次 LLM 调用
        Object plan = planPhaseFn != null ? planPhaseFn.apply(task) : null;
        if (plan == null) {
            log.warn("ReWOO Plan 阶段返回 null, fallback to {}", fallbackParadigm);
            return null;
        }

        // 阶段 2: Execute — 批量工具调用（0 次 LLM）
        List<Object> results = executePhaseFn != null
            ? executePhaseFn.apply(plan, toolRegistry)
            : List.of();

        // 阶段 3: Synthesize — 1 次 LLM 合成
        return synthesizePhaseFn != null
            ? synthesizePhaseFn.apply(plan, results)
            : "ReWOO 执行完成（桩实现，无合成逻辑）";
    }
}

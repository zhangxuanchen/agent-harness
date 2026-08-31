package io.etclovg.codepilot.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * ReAct 中间件（L 层）。
 * <p>对应书中 Ch14 §14.1 KP 14.1.1 —— ReAct 范式实现。
 * Thought → Action → Observation 循环，累积上下文，适合 ≤5 步的简单任务。
 *
 * <p>成本特征：O(N²) 上下文膨胀——每步将全部历史重新发送给模型。
 * 超过 5 步建议升级为 Plan-Execute（见 KP 14.2.2 动态升级）。
 *
 * <p>与章节代码对齐：流式 builder 模式，支持 .think / .observe / .maxSteps /
 * .repetitionWindow / .build()。
 */
@Component
public class ReActMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ReActMiddleware.class);

    private Object agent;
    private Function<String, ReActStep> thinkFn;         // Thought + Action 生成
    private BiFunction<String, String, String> observeFn;  // Action → Observation
    private int maxSteps = 10;
    private int repetitionWindow = 3;                      // 重复检测窗口

    public Builder builder() {
        return new Builder(this);
    }

    public static class Builder {
        private final ReActMiddleware middleware;

        Builder(ReActMiddleware middleware) {
            this.middleware = middleware;
        }

        public Builder agent(Object agent) {
            middleware.agent = agent;
            return this;
        }

        public Builder think(Function<String, ReActStep> fn) {
            middleware.thinkFn = fn;
            return this;
        }

        public Builder observe(BiFunction<String, String, String> fn) {
            middleware.observeFn = fn;
            return this;
        }

        public Builder maxSteps(int steps) {
            middleware.maxSteps = steps;
            return this;
        }

        public Builder repetitionWindow(int window) {
            middleware.repetitionWindow = window;
            return this;
        }

        public ReActMiddleware build() {
            return middleware;
        }
    }

    /**
     * ReAct 单步结果：Thought + Action。
     */
    public record ReActStep(String thought, String action, String toolName) {}

    /**
     * 执行 ReAct 循环。
     *
     * @param task 任务描述
     * @return 最终答案
     */
    public String execute(String task) {
        StringBuilder context = new StringBuilder(task);
        List<String> recentActions = new ArrayList<>();  // 重复检测窗口

        for (int step = 1; step <= maxSteps; step++) {
            // Thought + Action（1 次 LLM 调用，累积全部历史 → O(N²) 上下文膨胀）
            ReActStep reactStep = thinkFn.apply(context.toString());
            log.debug("Step {}: thought={}, action={}", step, reactStep.thought(), reactStep.action());

            // 收敛检测：重复 Action（滑动窗口）
            if (detectRepetition(recentActions, reactStep.action())) {
                log.warn("Step {}: 检测到重复 Action，提前终止", step);
                return "ReAct 检测到循环，已终止。最后思考: " + reactStep.thought();
            }
            recentActions.add(reactStep.action());
            if (recentActions.size() > repetitionWindow) {
                recentActions.remove(0);
            }

            // Observation（工具调用，0 次 LLM）
            String observation = observeFn.apply(reactStep.action(), reactStep.toolName());

            // 累积上下文（Context Inflation 的根源——每步将全部历史重新发送）
            context.append("\n[Step ").append(step).append("]")
                   .append("\nThought: ").append(reactStep.thought())
                   .append("\nAction: ").append(reactStep.action())
                   .append("\nObservation: ").append(observation);

            // 终止判断：Action 为 "finish" 时返回最终答案
            if ("finish".equalsIgnoreCase(reactStep.toolName())) {
                return observation;  // 最终答案
            }
        }
        log.warn("ReAct 达到最大步数 {}，强制终止", maxSteps);
        return "ReAct 达到最大步数限制，未完成。当前上下文: " + context;
    }

    /**
     * 重复检测：滑动窗口内同一 Action 出现 ≥2 次。
     */
    private boolean detectRepetition(List<String> recent, String action) {
        long count = recent.stream().filter(a -> a.equals(action)).count();
        return count >= 2;
    }
}

package io.etclovg.codepilot.orchestration;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * L 层 · 循环检测 Advisor。
 *
 * <p>检测 Agent 是否陷入工具调用循环（如 A→B→A→B→...），
 * 发现后根据策略终止循环或切换到备用策略。
 * 对应书中 Ch7 §7.5 循环检测与自愈策略。
 *
 * <p><b>检测算法</b>：滑动窗口 + 模式匹配
 * <ul>
 *   <li>窗口大小 = N（默认 8），检测最近 N 次调用中是否有重复模式</li>
 *   <li>模式长度 = 2~4（检测 A→B, A→B→C, A→B→C→D 等循环）</li>
 *   <li>当检测到至少 2 次相同模式重复时，判定为循环</li>
 * </ul>
 *
 * <p><b>应对策略</b>：
 * <ul>
 *   <li>{@code TERMINATE}：直接终止，返回部分结果</li>
 *   <li>{@code BREAK_PROMPT}：注入"你已陷入循环"的提示，强制换个思路</li>
 *   <li>{@code SWITCH_STRATEGY}：切换到备用策略（如从贪心切换到广度优先）</li>
 * </ul>
 *
 * <p><b>页面参考</b>：Ch7 §7.5 循环检测与自愈策略
 */
@Component
public class LoopDetectionAdvisor extends AbstractLayerMiddleware {

    /** 应对策略 */
    public enum Strategy { TERMINATE, BREAK_PROMPT, SWITCH_STRATEGY }

    /** 任务ID → 工具调用历史窗口 */
    private final Map<String, Deque<String>> callWindows = new ConcurrentHashMap<>();
    /** 任务ID → 该任务已检测到的循环次数 */
    private final Map<String, Integer> loopCounts = new ConcurrentHashMap<>();

    /** 滑动窗口大小 */
    private int windowSize = 8;
    /** 最小模式长度 */
    private int minPatternLength = 2;
    /** 最大模式长度 */
    private int maxPatternLength = 4;
    /** 模式重复次数阈值 */
    private int patternRepeatThreshold = 2;
    /** 最大循环容忍次数（超过后即使 BREAK_PROMPT 也强制跳转到 TERMINATE） */
    private int maxLoopTolerance = 3;
    /** 默认策略 */
    private Strategy defaultStrategy = Strategy.BREAK_PROMPT;

    public LoopDetectionAdvisor() {
        super(Layer.L, "LoopDetectionAdvisor-L");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String taskId = context.getOrDefault("task.id", "default").toString();
        String toolName = context.getOrDefault("tool.name", "unknown").toString();

        // 记录本次工具调用
        Deque<String> window = callWindows.computeIfAbsent(taskId,
                k -> new ArrayDeque<>());
        window.addLast(toolName);

        // 维持窗口大小
        while (window.size() > windowSize) {
            window.removeFirst();
        }

        // 检测循环
        LoopResult result = detectLoop(window);
        if (result.detected) {
            int loopCount = loopCounts.merge(taskId, 1, Integer::sum);
            log.warn("[L层·循环检测] 任务 {} 检测到循环 {} (第{}次): {} 重复{}次",
                    taskId, result.pattern, loopCount,
                    result.pattern, result.repetitions);

            // 超过容忍上限 → 强制终止
            if (loopCount > maxLoopTolerance) {
                log.warn("[L层·循环检测] 任务 {} 循环次数({})超过容忍上限({}) → TERMINATE",
                        taskId, loopCount, maxLoopTolerance);
                return buildLoopResponse(rc, input, next, taskId, Strategy.TERMINATE,
                        "超过循环容忍上限", result.pattern);
            }

            // 根据策略处理
            return handleLoop(taskId, result, rc, input, next);
        }

        return next.apply(input);
    }

    /**
     * 检测滑动窗口中的循环模式。
     *
     * @param window 工具调用历史窗口
     * @return 循环检测结果
     */
    LoopResult detectLoop(Deque<String> window) {
        if (window.size() < minPatternLength * patternRepeatThreshold) {
            return LoopResult.NOT_FOUND;
        }

        List<String> history = new ArrayList<>(window);

        // 从最短模式开始检测——优先发现最紧凑的循环
        for (int patternLen = minPatternLength; patternLen <= Math.min(maxPatternLength, history.size() / 2); patternLen++) {
            // 取窗口末尾的 patternLen 作为候选模式
            List<String> candidatePattern = history.subList(history.size() - patternLen, history.size());

            // 在历史中查找相同模式的重复次数
            int repetitions = countRepetitions(history, candidatePattern);

            if (repetitions >= patternRepeatThreshold) {
                return new LoopResult(true, candidatePattern, repetitions);
            }
        }

        return LoopResult.NOT_FOUND;
    }

    /**
     * 计算候选模式在历史中的连续重复次数。
     */
    private int countRepetitions(List<String> history, List<String> pattern) {
        int count = 0;
        int idx = history.size() - pattern.size();

        while (idx >= 0) {
            boolean match = true;
            for (int j = 0; j < pattern.size(); j++) {
                if (!Objects.equals(history.get(idx + j), pattern.get(j))) {
                    match = false;
                    break;
                }
            }
            if (!match) break;
            count++;
            idx -= pattern.size();
        }

        return count;
    }

    /**
     * 处理检测到的循环。
     */
    private Flux<AgentEvent> handleLoop(String taskId, LoopResult result,
                                        RuntimeContext rc, AgentInput input,
                                        Function<AgentInput, Flux<AgentEvent>> next) {
        return switch (defaultStrategy) {
            case TERMINATE -> buildLoopResponse(rc, input, next, taskId, Strategy.TERMINATE,
                    "检测到循环: " + result.pattern, result.pattern);

            case BREAK_PROMPT -> {
                rc.put("loop.detected", true);
                rc.put("loop.pattern", result.pattern.toString());
                rc.put("loop.break_prompt",
                        "⚠️ 检测到你已重复执行以下工具序列 "
                                + result.repetitions + " 次: "
                                + result.pattern
                                + "。请立即停止此循环，尝试完全不同的方法或工具组合。");
                yield next.apply(input);
            }

            case SWITCH_STRATEGY -> {
                rc.put("loop.detected", true);
                rc.put("loop.switch_strategy", true);
                rc.put("loop.switch_hint", getAlternativeStrategy(result.pattern));
                yield next.apply(input);
            }
        };
    }

    /**
     * 根据循环模式生成备选策略提示。
     */
    private String getAlternativeStrategy(List<String> pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append("你已陷入工具调用循环: ").append(pattern).append("。建议:\n");
        sb.append("1. 尝试使用不同的工具（如果可用）\n");
        sb.append("2. 将问题分解为更小的子问题\n");
        sb.append("3. 请求用户提供更多上下文信息\n");
        sb.append("4. 检查之前的工具调用结果是否有误判");
        return sb.toString();
    }

    private Flux<AgentEvent> buildLoopResponse(RuntimeContext rc, AgentInput input,
                                               Function<AgentInput, Flux<AgentEvent>> next,
                                               String taskId, Strategy strategy,
                                               String reason, List<String> pattern) {
        Map<String, Object> meta = Map.of(
                "loop.detected", true,
                "loop.strategy", strategy.name(),
                "loop.reason", reason,
                "loop.pattern", pattern.toString(),
                "loop.task_id", taskId
        );

        // 构建一个包含循环检测状态的上下文
        rc.put("loop.status", meta);
        rc.put("loop.detected", true);
        rc.put("error.message", "Loop detected: " + reason);

        // 添加上下文并转发
        return next.apply(input);
    }

    // ==================== 配置方法 ====================

    public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
    public void setMinPatternLength(int minPatternLength) { this.minPatternLength = minPatternLength; }
    public void setMaxPatternLength(int maxPatternLength) { this.maxPatternLength = maxPatternLength; }
    public void setPatternRepeatThreshold(int patternRepeatThreshold) { this.patternRepeatThreshold = patternRepeatThreshold; }
    public void setMaxLoopTolerance(int maxLoopTolerance) { this.maxLoopTolerance = maxLoopTolerance; }
    public void setDefaultStrategy(Strategy defaultStrategy) { this.defaultStrategy = defaultStrategy; }

    // ==================== 内部类型 ====================

    record LoopResult(boolean detected, List<String> pattern, int repetitions) {
        static final LoopResult NOT_FOUND = new LoopResult(false, List.of(), 0);
    }
}

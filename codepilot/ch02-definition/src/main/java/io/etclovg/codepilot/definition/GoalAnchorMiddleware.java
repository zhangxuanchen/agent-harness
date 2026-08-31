package io.etclovg.codepilot.definition;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 目标锚定中间件。
 * <p>对应书中 Ch02 §2.7 —— 确保 Agent 的每一步行动都围绕初始目标。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段，
 * 提供两层目标漂移防护（与 ch01 {@code AgentLoopFailureModes.detectGoalDrift()} 保持一致）：
 * <ul>
 *   <li>目标锚定：从 {@link RuntimeContext} 读取原始目标并打标，确保上下文中目标可见</li>
 *   <li>漂移检测：检查当前决策是否偏离原始目标，偏离时短路终止</li>
 * </ul>
 */
@Component
public class GoalAnchorMiddleware extends AbstractLayerMiddleware {

    public GoalAnchorMiddleware() {
        super(Layer.L, "GoalAnchor");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String goal = rc.getExtra().getOrDefault("goal", "").toString();

        // ① 目标锚定：确保上下文中目标可见
        if (!goal.isEmpty()) {
            log.info("[GoalAnchor] 锚定目标: {}", goal);
            rc.put("goal.anchored", true);
        }

        // ② 漂移检测：当前决策是否偏离原始目标（与 ch01 detectGoalDrift 一致）
        String currentDecision = rc.getExtra().getOrDefault("current.decision", "").toString();
        if (!goal.isEmpty() && !currentDecision.isEmpty()) {
            if (detectGoalDrift(currentDecision, goal)) {
                log.warn("[GoalAnchor] 目标漂移拦截: 决策[{}]偏离原始目标[{}]", currentDecision, goal);
                rc.put("goal.drifted", true);
                rc.put("fault.type", "GOAL_DRIFT");
                return Flux.empty();
            }
        }

        return next.apply(input);
    }

    /**
     * 检测目标漂移：当前决策是否包含原始目标的关键词。
     * 与 ch01 AgentLoopFailureModes.detectGoalDrift() 逻辑一致。
     */
    private boolean detectGoalDrift(String currentDecision, String originalGoal) {
        String goalKeyword = originalGoal.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").toLowerCase();
        return !currentDecision.toLowerCase().contains(goalKeyword);
    }
}

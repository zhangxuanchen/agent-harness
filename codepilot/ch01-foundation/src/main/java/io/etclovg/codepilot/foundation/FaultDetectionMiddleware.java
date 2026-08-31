package io.etclovg.codepilot.foundation;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.function.Function;

/**
 * PDA 闭环故障检测中间件。
 * <p>对应书中 Ch01 §1.3 —— 将感知-决策-行动循环与三种故障模式的实时检测集成。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware}，在生命周期三阶段织入检测：
 * <ul>
 *   <li>{@code onActing} 前 → 检测幻觉行动（工具名不在 T 层白名单 → 拦截）</li>
 *   <li>{@code onReasoning} → 检测循环卡死（同一工具连续调用 ≥ 3 次 → 拦截）</li>
 *   <li>{@code onReasoning} → 检测目标漂移（决策偏离原始目标 → 拦截）</li>
 * </ul>
 */
@Component
public class FaultDetectionMiddleware extends AbstractLayerMiddleware {

    private final AgentLoopFailureModes failureModes;

    /** T 层工具白名单——仅允许声明过的工具通过 */
    private static final Set<String> TOOL_WHITELIST = Set.of(
            "search", "calculate", "fetch", "write", "verify"
    );

    public FaultDetectionMiddleware(AgentLoopFailureModes failureModes) {
        super(Layer.L, "fault-detection");
        this.failureModes = failureModes;
    }

    /** Acting 前：检测幻觉行动——工具名不在 T 层白名单 → 拦截 */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc,
                                     ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String toolName = rc.getExtra().getOrDefault("tool.name", "").toString();
        if (failureModes.detectHallucinatedAction(toolName, TOOL_WHITELIST)) {
            log.warn("[FaultDetection] 幻觉行动拦截: 工具[{}]不在白名单, 防御: {}",
                    toolName, AgentLoopFailureModes.FailureMode.HALLUCINATED_ACTION.getDefense());
            rc.put("fault.detected", true);
            rc.put("fault.type", "HALLUCINATED_ACTION");
            return Flux.empty();
        }
        return next.apply(input);
    }

    /** Reasoning 阶段：检测循环卡死和目标漂移 */
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext rc,
                                        ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        // 循环卡死检测：记录上一步工具调用并检查
        String lastTool = (String) rc.getExtra().get("last_tool");
        if (lastTool != null) {
            failureModes.recordCall(lastTool);
            if (failureModes.detectLoopDeath(3)) {
                log.warn("[FaultDetection] 循环卡死拦截: 工具[{}]连续调用超限", lastTool);
                rc.put("fault.detected", true);
                rc.put("fault.type", "LOOP_DEATH");
                return Flux.empty();
            }
        }

        // 目标漂移检测
        String originalGoal = (String) rc.getExtra().get("original_goal");
        String currentDecision = rc.getExtra().getOrDefault("current.decision", "").toString();
        if (failureModes.detectGoalDrift(currentDecision)) {
            log.warn("[FaultDetection] 目标漂移拦截: 决策[{}]偏离原始目标[{}]", currentDecision, originalGoal);
            rc.put("fault.detected", true);
            rc.put("fault.type", "GOAL_DRIFT");
            return Flux.empty();
        }

        return next.apply(input);
    }
}
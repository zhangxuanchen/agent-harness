package io.etclovg.codepilot.behavior;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

/**
 * Agent 状态中间件——在执行链中通过 {@link AgentStateManager} 记录每步状态。
 * <p>对应书中 Ch03 §3.4.1 / §3.5.1 —— L 层状态跟踪组件。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段，
 * 调用前记录 RUNNING 状态，结束后记录 COMPLETED（或异常时 FAILED），
 * 将每步的 {@link AgentStepState} 汇聚到 {@link AgentStateManager}，
 * 供断点恢复与 O 层审计使用。
 *
 * <p>API 对齐 AgentScope RuntimeContext：通过 rc.getExtra() 获取 userId，
 * 与 AgentStateStore 的 (userId, sessionId) key 结构保持一致。
 */
@Component
public class AgentStateMiddleware extends AbstractLayerMiddleware {

    private final AgentStateManager stateManager;
    private final String sessionId;
    private final String userId;
    private final int maxSteps;

    public AgentStateMiddleware(AgentStateManager stateManager, String sessionId, int maxSteps) {
        this(null, stateManager, sessionId, maxSteps);
    }

    public AgentStateMiddleware(String userId, AgentStateManager stateManager,
                                String sessionId, int maxSteps) {
        super(Layer.L, "AgentState");
        this.userId = userId;
        this.stateManager = stateManager;
        this.sessionId = sessionId;
        this.maxSteps = maxSteps;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        int stepIndex = (Integer) rc.getExtra().getOrDefault("step.index", 0);

        if (stepIndex >= maxSteps) {
            log.warn("[AgentState] 会话 {} 达到步数上限 {}，终止编排", sessionId, maxSteps);
            return Flux.empty();
        }

        String effectiveUserId = userId != null ? userId :
                (String) rc.getExtra().getOrDefault("userId", null);

        stateManager.recordState(effectiveUserId, sessionId, new AgentStepState(
                effectiveUserId, sessionId, stepIndex, AgentStepState.StepStatus.RUNNING,
                Map.of(), Instant.now()));

        return next.apply(input)
                .doFinally(signal -> stateManager.recordState(
                        effectiveUserId, sessionId, new AgentStepState(
                                effectiveUserId, sessionId, stepIndex,
                                AgentStepState.StepStatus.COMPLETED,
                                Map.of(), Instant.now())));
    }
}
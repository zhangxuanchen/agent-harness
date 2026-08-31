package io.etclovg.codepilot.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ReasoningInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 上下文预算中间件。
 * <p>对应书中 Ch06 §C 层 —— 在每次调用前检查上下文预算。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onReasoning} 阶段，
 * 在模型推理前读取上下文利用率。
 */
@Component
public class ContextBudgetMiddleware extends AbstractLayerMiddleware {

    private final ContextBudgetManager budgetManager;

    public ContextBudgetMiddleware(ContextBudgetManager budgetManager) {
        super(Layer.C, "ContextBudget");
        this.budgetManager = budgetManager;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext rc, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        String agentId = rc.getExtra().getOrDefault("agent.id", "default").toString();
        double utilization = budgetManager.getUtilizationRate(agentId);
        log.info("[ContextBudget] 上下文利用率: {}%", String.format("%.1f", utilization * 100));
        return next.apply(input);
    }
}

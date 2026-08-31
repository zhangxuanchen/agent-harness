package io.etclovg.codepilot.behavior;

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
 * 成本感知编排中间件。
 * <p>对应书中 Ch03 §3.4 —— 在编排过程中动态调整策略以控制成本。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段，
 * 在每次调用前检查 {@link TokenCostTracker} 中的累计成本，超过阈值时通过
 * {@code RuntimeContext} 传递 {@code model.downgrade} 信号，由下游中间件读取。
 *
 * <p>这是 O 层（成本信号）→ L 层（模型降级）的跨层协作示例。
 */
@Component
public class CostAwareOrchestrationMiddleware extends AbstractLayerMiddleware {

    private final TokenCostTracker costTracker;  // O 层
    private final double costThreshold;

    public CostAwareOrchestrationMiddleware() {
        super(Layer.L, "CostAwareOrchestration");
        this.costTracker = new TokenCostTracker();
        this.costThreshold = 50.0; // 默认 $50 阈值
    }

    public CostAwareOrchestrationMiddleware(TokenCostTracker costTracker, double costThreshold) {
        super(Layer.L, "CostAwareOrchestration");
        this.costTracker = costTracker;
        this.costThreshold = costThreshold;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        // O 层提供状态：当前会话累计成本
        String sessionId = rc.getExtra().getOrDefault("session.id", "default").toString();
        double currentCost = costTracker.getCost(sessionId);

        // L 层做决策：超过阈值降级模型
        if (currentCost > costThreshold) {
            log.warn("[CostAware] 会话 {} 成本 ${} 超过阈值 ${}，触发模型降级",
                sessionId, currentCost, costThreshold);
            // 📌 跨层依赖标注：L 层的模型选择依赖 O 层的成本信号
            rc.put(LayerSignalKey.L_MODEL_DOWNGRADE.name(), "qwen-turbo");
        } else {
            log.info("[CostAware] 会话 {} 当前成本 ${}", sessionId, currentCost);
        }
        return next.apply(input);
    }

    public void setCostThreshold(double threshold) {
        // costThreshold is final; use constructor injection to change threshold
    }
}

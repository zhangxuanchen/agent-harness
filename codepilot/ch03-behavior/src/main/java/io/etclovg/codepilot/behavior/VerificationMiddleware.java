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
 * 验证中间件。
 * <p>对应书中 Ch03 §3.4 —— 在执行完成后验证结果质量，通过事件总线发布验证失败事件。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段，
 * 在下游完成后执行验证：若评分低于阈值，通过 {@link EventBus} 发布
 * {@link VerificationFailedEvent}，由下游 RetryOrchestrationMiddleware 订阅并触发重试。
 */
@Component
public class VerificationMiddleware extends AbstractLayerMiddleware {

    private final EventBus eventBus;
    private static final double PASS_THRESHOLD = 0.8;

    public VerificationMiddleware() {
        super(Layer.V, "Verification");
        this.eventBus = new EventBus();
    }

    public VerificationMiddleware(EventBus eventBus) {
        super(Layer.V, "Verification");
        this.eventBus = eventBus;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnComplete(() -> {
                    double score = evaluateAgentOutput(rc);
                    if (score < PASS_THRESHOLD) {
                        eventBus.publish(new VerificationFailedEvent(
                            rc.getExtra().getOrDefault("session.id", "default").toString(),
                            (Integer) rc.getExtra().getOrDefault("step.index", 0),
                            score));
                    }
                });
    }

    /** 简化版评分：根据上下文中的标记做启发式判断（教学示意） */
    @SuppressWarnings("unchecked")
    private double evaluateAgentOutput(RuntimeContext rc) {
        // 教学示意：实际场景应接入 LLM-as-Judge 或规则评分
        Object flag = rc.getExtra().get("verification.score");
        if (flag instanceof Number n) {
            return n.doubleValue();
        }
        return 1.0; // 默认通过
    }
}

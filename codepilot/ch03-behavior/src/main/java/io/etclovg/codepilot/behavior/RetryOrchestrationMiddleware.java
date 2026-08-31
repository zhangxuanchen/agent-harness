package io.etclovg.codepilot.behavior;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.util.function.Function;

/**
 * 重试编排中间件。
 * <p>对应书中 Ch03 §3.4.4 —— 事件驱动重试与响应式重试的结合。
 *
 * <p>提供两种重试模式：
 * <ul>
 *   <li><b>响应式重试（默认）</b>：通过 {@link Flux#retryWhen(Retry)} 在每次调用时自动重试</li>
 *   <li><b>事件驱动重试</b>：通过 {@link Subscribe @Subscribe} 订阅 {@link VerificationFailedEvent}，
 *       由 V 层验证失败事件触发重试，支持跨层解耦</li>
 * </ul>
 */
@Component
public class RetryOrchestrationMiddleware extends AbstractLayerMiddleware {

    private int maxRetries = 3;

    public RetryOrchestrationMiddleware() {
        super(Layer.L, "RetryOrchestration");
    }

    // ---- 响应式重试（默认行为） ----

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .retryWhen(Retry.max(maxRetries)
                        .doAfterRetry(sig -> log.warn("[RetryOrchestration] 第 {}/{} 次重试",
                                sig.totalRetries() + 1, maxRetries)));
    }

    // ---- 事件驱动重试（V 层 → L 层跨层解耦） ----

    /**
     * 订阅 V 层的验证失败事件，触发跨层重试。
     * <p>对应书中 KP 3.4.4 耦合治理——事件总线模式。
     * <p>V 层通过 {@link EventBus} 发布 {@link VerificationFailedEvent}，
     * L 层通过此方法订阅并触发重试，两者互不引用。
     */
    @Subscribe
    public void onVerificationFailed(VerificationFailedEvent event) {
        if (shouldRetry(event.getStepIndex(), event.getScore())) {
            triggerRetry(event.getSessionId());
        }
    }

    /**
     * 判断是否应重试：评分低于阈值且未超过最大重试次数。
     */
    private boolean shouldRetry(int stepIndex, double score) {
        return score < 0.8 && stepIndex < maxRetries;
    }

    /**
     * 触发重试：将重试指令写入 RuntimeContext，由下游 Middleware 读取。
     */
    private void triggerRetry(String sessionId) {
        log.warn("[RetryOrchestration] 会话 {} 验证失败，触发重试", sessionId);
        // 实际实现中通过 RuntimeContext 传递重试信号
    }

    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
}

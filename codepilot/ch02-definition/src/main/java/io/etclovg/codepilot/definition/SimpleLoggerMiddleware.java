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
 * 日志记录中间件。
 * <p>对应书中 Ch02 §2.2 —— 记录每次 Agent 调用的请求和响应。
 * <p>最基础的 Middleware，用于调试和审计。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段，
 * 包裹完整 reply 流程并在结束时记录耗时。
 */
@Component
public class SimpleLoggerMiddleware extends AbstractLayerMiddleware {

    public SimpleLoggerMiddleware() {
        super(Layer.L, "SimpleLogger");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        log.info("[SimpleLogger] === 请求开始 ===");
        log.info("[SimpleLogger] 上下文: {}", rc.getExtra().keySet());

        long startTime = System.currentTimeMillis();
        return next.apply(input)
                .doFinally(signal -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[SimpleLogger] === 请求完成 (耗时: {}ms) ===", elapsed);
                });
    }
}

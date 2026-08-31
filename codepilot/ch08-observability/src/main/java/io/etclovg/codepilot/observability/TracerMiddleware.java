package io.etclovg.codepilot.observability;

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
 * 追踪器中间件。
 * <p>对应书中 Ch08 §O 层 —— 为每次调用分配追踪 ID 并记录完整链路。
 */
@Component("tracer")
public class TracerMiddleware extends AbstractLayerMiddleware {

    public TracerMiddleware() {
        super(Layer.O, "Tracer");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String traceId = "trace-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        log.info("[Tracer] 开始追踪: traceId={}", traceId);

        rc.put("trace.id", traceId);

        return next.apply(input);
    }
}

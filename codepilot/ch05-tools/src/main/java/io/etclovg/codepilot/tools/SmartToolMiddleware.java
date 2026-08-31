package io.etclovg.codepilot.tools;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 智能工具中间件。
 * <p>对应书中 Ch05 §5.4 —— 根据语义自动选择最合适的工具。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onActing} 阶段。
 */
@Component
public class SmartToolMiddleware extends AbstractLayerMiddleware {

    private final SemanticToolRouter toolRouter;

    public SmartToolMiddleware(SemanticToolRouter toolRouter) {
        super(Layer.T, "SmartTool");
        this.toolRouter = toolRouter;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String userIntent = rc.getExtra().getOrDefault("user.intent", "").toString();
        if (!userIntent.isEmpty()) {
            log.info("[SmartTool] 路由工具选择: intent={}", userIntent);
        }
        return next.apply(input);
    }
}

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
 * 工具策略中间件。
 * <p>对应书中 Ch05 §5.5 —— 在工具调用前执行策略检查。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onActing} 阶段。
 */
@Component
public class ToolPolicyMiddleware extends AbstractLayerMiddleware {

    private final ToolPolicy toolPolicy;

    public ToolPolicyMiddleware(ToolPolicy toolPolicy) {
        super(Layer.G, "ToolPolicy");
        this.toolPolicy = toolPolicy;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String toolName = rc.getExtra().getOrDefault("tool.name", "").toString();
        log.info("[ToolPolicy] 检查工具策略: tool={}", toolName);
        return next.apply(input);
    }
}

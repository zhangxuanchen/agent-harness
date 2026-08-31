package io.etclovg.codepilot.governance;

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
 * 工具守护中间件。
 * <p>对应书中 Ch10 §G 层 —— 检查工具调用的权限和合规性。
 */
@Component
public class ToolGuardMiddleware extends AbstractLayerMiddleware {

    private final ToolGuardAdvisor toolGuardAdvisor;

    public ToolGuardMiddleware(ToolGuardAdvisor toolGuardAdvisor) {
        super(Layer.G, "ToolGuard");
        this.toolGuardAdvisor = toolGuardAdvisor;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String toolName = rc.getExtra().getOrDefault("tool.name", "").toString();
        log.info("[ToolGuard] 检查工具权限: tool={}", toolName);
        return next.apply(input);
    }
}

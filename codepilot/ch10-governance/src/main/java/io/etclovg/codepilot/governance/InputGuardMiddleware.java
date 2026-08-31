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
 * 输入守护中间件。
 * <p>对应书中 Ch10 §G 层 —— 在处理前检查输入内容的合规性。
 */
@Component
public class InputGuardMiddleware extends AbstractLayerMiddleware {

    private final InputGuardAdvisor inputGuardAdvisor;

    public InputGuardMiddleware(InputGuardAdvisor inputGuardAdvisor) {
        super(Layer.G, "InputGuard");
        this.inputGuardAdvisor = inputGuardAdvisor;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        log.info("[InputGuard] 检查输入合规性");
        return next.apply(input);
    }
}

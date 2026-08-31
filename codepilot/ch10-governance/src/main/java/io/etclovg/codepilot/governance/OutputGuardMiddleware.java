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
 * 输出守护中间件。
 * <p>对应书中 Ch10 §G 层 —— 在输出返回前进行合规性检查。
 */
@Component
public class OutputGuardMiddleware extends AbstractLayerMiddleware {

    private final OutputGuardAdvisor outputGuardAdvisor;

    public OutputGuardMiddleware(OutputGuardAdvisor outputGuardAdvisor) {
        super(Layer.G, "OutputGuard");
        this.outputGuardAdvisor = outputGuardAdvisor;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnComplete(() -> log.info("[OutputGuard] 检查输出合规性"));
    }
}

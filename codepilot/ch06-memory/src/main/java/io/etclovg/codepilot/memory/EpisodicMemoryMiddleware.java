package io.etclovg.codepilot.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ReasoningInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 情景记忆中间件。
 * <p>对应书中 Ch06 §C 层 —— 从情景记忆中检索相关历史。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onReasoning} 阶段。
 */
@Component("episodicMemory")
public class EpisodicMemoryMiddleware extends AbstractLayerMiddleware {

    private final EpisodicKnowledgeStore knowledgeStore;

    public EpisodicMemoryMiddleware(EpisodicKnowledgeStore knowledgeStore) {
        super(Layer.C, "EpisodicMemory");
        this.knowledgeStore = knowledgeStore;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext rc, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        log.info("[EpisodicMemory] 检索情景记忆");
        return next.apply(input);
    }
}

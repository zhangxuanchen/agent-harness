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
 * 消息聊天记忆中间件（tools 层版本）。
 * <p>对应书中 Ch05 §5.6 —— 在工具调用场景中维护上下文记忆。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onActing} 阶段。
 */
@Component("toolsMessageChatMemoryMiddleware")
public class MessageChatMemoryMiddleware extends AbstractLayerMiddleware {

    public MessageChatMemoryMiddleware() {
        super(Layer.C, "ToolsChatMemory");
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        log.info("[ToolsChatMemory] 工具调用记忆处理");
        return next.apply(input);
    }
}

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
 * 消息聊天记忆中间件。
 * <p>对应书中 Ch02 §2.3 —— 在 Agent 调用链中维护对话历史上下文。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段，
 * 在调用前将会话历史注入 {@link RuntimeContext}。
 */
@Component("workingMemory")
public class MessageChatMemoryMiddleware extends AbstractLayerMiddleware {

    private final InMemoryChatMemory memory;

    public MessageChatMemoryMiddleware(InMemoryChatMemory memory) {
        super(Layer.C, "MessageChatMemory");
        this.memory = memory;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String sessionId = rc.getExtra().getOrDefault("session.id", "default").toString();
        log.info("[ChatMemory] 加载会话历史: sessionId={}", sessionId);

        rc.put("chat.history", memory.getHistory(sessionId));

        return next.apply(input);
    }
}

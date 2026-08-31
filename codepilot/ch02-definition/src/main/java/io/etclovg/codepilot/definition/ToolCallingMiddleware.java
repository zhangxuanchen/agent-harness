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
 * 工具调用中间件。
 * <p>对应书中 Ch02 §2.4 —— 处理 Agent 的工具调用逻辑。
 * <p>拦截并执行工具调用，将结果注入回对话上下文。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段。
 */
@Component("toolCalling")
public class ToolCallingMiddleware extends AbstractLayerMiddleware {

    public ToolCallingMiddleware() {
        super(Layer.T, "ToolCalling");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        log.info("[ToolCalling] 检查工具调用需求");

        String toolName = rc.getExtra().getOrDefault("tool.name", "").toString();
        if (!toolName.isEmpty()) {
            log.info("[ToolCalling] 执行工具: {}", toolName);
            rc.put("tool.result", "Tool " + toolName + " executed successfully");
        }

        return next.apply(input);
    }
}

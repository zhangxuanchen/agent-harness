package io.etclovg.codepilot.definition;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.function.Function;

/**
 * 工具调用验证中间件。
 * <p>对应书中 Ch02 §2.4.1 —— 验证工具调用的合法性和安全性。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段，
 * 非法工具调用时通过 {@link RuntimeContext} 打标并短路返回空事件流。
 *
 * <p>与 ch01 {@code AgentLoopFailureModes.detectHallucinatedAction()} 保持一致，
 * 使用相同的 T 层工具白名单。
 */
@Component
public class ToolCallValidationMiddleware extends AbstractLayerMiddleware {

    /** T 层工具白名单——与 ch01 AgentLoopFailureModes 保持一致 */
    private static final Set<String> TOOL_WHITELIST = Set.of(
            "search", "calculate", "fetch", "write", "verify"
    );

    public ToolCallValidationMiddleware() {
        super(Layer.T, "ToolCallValidation");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String toolName = rc.getExtra().getOrDefault("tool.name", "").toString();

        if (!toolName.isEmpty() && !isToolAllowed(toolName)) {
            log.warn("[ToolValidation] 工具调用被阻止: {} 不在白名单 {}", toolName, TOOL_WHITELIST);
            rc.put("tool.blocked", true);
            rc.put("tool.name", toolName);
            return Flux.empty();
        }

        return next.apply(input);
    }

    private boolean isToolAllowed(String toolName) {
        return TOOL_WHITELIST.contains(toolName);
    }
}

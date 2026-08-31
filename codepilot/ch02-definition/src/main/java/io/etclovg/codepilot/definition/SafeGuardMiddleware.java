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
 * 安全守护中间件。
 * <p>对应书中 Ch02 §2.5 —— 在 Agent 响应前进行安全检查。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段，
 * 命中阻断关键词时通过 {@link RuntimeContext} 打标并短路返回空事件流。
 */
@Component
public class SafeGuardMiddleware extends AbstractLayerMiddleware {

    private final RefusalGuard refusalGuard;

    public SafeGuardMiddleware(RefusalGuard refusalGuard) {
        super(Layer.G, "SafeGuard");
        this.refusalGuard = refusalGuard;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        log.info("[SafeGuard] 执行安全检查");

        if (refusalGuard.shouldRefuse(rc)) {
            log.warn("[SafeGuard] 请求被拒绝: 违反安全策略");
            rc.put("safety.blocked", true);
            rc.put("safety.reason", "REFUSED");
            return Flux.empty();
        }

        return next.apply(input);
    }
}

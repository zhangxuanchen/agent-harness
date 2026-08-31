package io.etclovg.codepilot.sandbox;

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
 * E 层 · Docker 沙箱中间件。
 * <p>在每次工具调用前注入沙箱隔离逻辑。对应书中 Ch04 §4.2.2。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onActing} 阶段，
 * 包裹工具执行并在 {@link RuntimeContext} 中注入沙箱配置。
 */
@Component
public class SandboxAdvisor extends AbstractLayerMiddleware {

    private final SandboxConfig config;

    public SandboxAdvisor(SandboxConfig config) {
        super(Layer.E, "SandboxAdvisor-E");
        this.config = config;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        log.info("[E层沙箱] 工具调用前检查——隔离模式: {}, 超时: {}s",
                config.getNetworkMode(), config.getTimeoutSeconds());

        rc.put("sandbox.image", config.getImage());
        rc.put("sandbox.cpu", config.getCpuLimit());
        rc.put("sandbox.memory_mb", config.getMemoryLimitMb());
        rc.put("sandbox.timeout_s", config.getTimeoutSeconds());
        rc.put("sandbox.network", config.getNetworkMode());

        return next.apply(input)
                .doFinally(signal -> log.info("[E层沙箱] 工具调用完成——清理沙箱环境"));
    }
}

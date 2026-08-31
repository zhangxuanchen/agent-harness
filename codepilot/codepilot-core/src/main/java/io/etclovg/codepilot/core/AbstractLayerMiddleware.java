package io.etclovg.codepilot.core;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * ETCLOVG 七层中间件共享基类。
 *
 * <p>基于 AgentScope 2.0 {@link MiddlewareBase} 五阶段洋葱模型，为全书所有
 * {@code *Middleware} 组件提供统一的层标识、命名与日志能力。书中原有的 Spring AI
 * {@code CallAdvisor}（{@code adviseCall(request, chain)} + {@code chain.nextCall(request)}）
 * 全部迁移为这里的五阶段中间件——每个阶段接收一个 {@code Function<Input, Flux<AgentEvent>> next}，
 * 调用 {@code next.apply(input)} 即等价于原来的 {@code chain.nextCall(request)}。
 *
 * <p>五阶段与 ETCLOVG 注入点的对应关系：
 * <ul>
 *   <li>{@code onAgent} —— 包裹完整 reply 流程（L 层编排、O 层端到端追踪）</li>
 *   <li>{@code onReasoning} —— 包裹模型推理（C 层上下文预算压缩、O 层模型调用日志）</li>
 *   <li>{@code onActing} —— 包裹工具执行（T 层策略治理、G 层输入/审计防护）</li>
 *   <li>{@code onModelCall} —— 包裹底层模型 API 调用（O 层成本归因、燃烧率）</li>
 *   <li>{@code onSystemPrompt} —— 改写系统提示（C 层记忆/人格注入）</li>
 * </ul>
 *
 * <p>子类按需覆写某一阶段；未覆写的阶段默认直接放行到下一环节。
 */
public abstract class AbstractLayerMiddleware implements MiddlewareBase {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final Layer layer;
    protected final String name;

    protected AbstractLayerMiddleware(Layer layer, String name) {
        this.layer = layer;
        this.name = name;
    }

    /** 该中间件归属的 ETCLOVG 层。 */
    public Layer getLayer() {
        return layer;
    }

    /** 中间件名称（用于可观测性与日志）。 */
    public String getMiddlewareName() {
        return name;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext rc, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext rc, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext rc, String prompt) {
        return Mono.just(prompt);
    }
}

package io.etclovg.codepilot.definition;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 循环检测中间件。
 * <p>对应书中 Ch02 §2.6 —— 检测并阻止 Agent 陷入无限循环。
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onAgent} 阶段，
 * 提供两层循环卡死检测（与 ch01 {@code AgentLoopFailureModes} 保持一致）：
 * <ul>
 *   <li>步数上限：按 sessionId 累计步骤数，超限时短路终止（默认 20 步）</li>
 *   <li>工具级去重：同一工具连续调用超过阈值时短路终止（默认 3 次）</li>
 * </ul>
 */
@Component
public class LoopDetectionMiddleware extends AbstractLayerMiddleware {

    private final Map<String, AtomicInteger> stepCounters = new ConcurrentHashMap<>();
    private final Map<String, String> lastToolBySession = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> consecutiveCounters = new ConcurrentHashMap<>();
    private int maxSteps = 20;
    private int maxRepeatCount = 3;

    public LoopDetectionMiddleware() {
        super(Layer.L, "LoopDetection");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String sessionId = rc.getExtra().getOrDefault("session.id", "default").toString();
        AtomicInteger counter = stepCounters.computeIfAbsent(sessionId, k -> new AtomicInteger(0));

        int currentStep = counter.incrementAndGet();
        log.info("[LoopDetection] 步骤 {} (max={})", currentStep, maxSteps);

        // 检测 1：步数上限
        if (currentStep > maxSteps) {
            log.warn("[LoopDetection] 达到最大步骤数，强制终止");
            rc.put("loop.terminated", true);
            rc.put("loop.reason", "MAX_STEPS");
            return Flux.empty();
        }

        // 检测 2：工具级连续调用去重（与 ch01 MAX_REPEAT_COUNT=3 一致）
        String currentTool = rc.getExtra().getOrDefault("tool.name", "").toString();
        if (!currentTool.isEmpty()) {
            String lastTool = lastToolBySession.get(sessionId);
            AtomicInteger consecCounter = consecutiveCounters.computeIfAbsent(sessionId, k -> new AtomicInteger(0));

            if (currentTool.equals(lastTool)) {
                int consecutive = consecCounter.incrementAndGet();
                if (consecutive >= maxRepeatCount) {
                    log.warn("[LoopDetection] 工具[{}]连续调用 {} 次，判定循环卡死", currentTool, consecutive);
                    rc.put("loop.terminated", true);
                    rc.put("loop.reason", "REPEAT_TOOL:" + currentTool);
                    return Flux.empty();
                }
            } else {
                consecCounter.set(0);
            }
            lastToolBySession.put(sessionId, currentTool);
        }

        return next.apply(input)
                .doOnComplete(() -> log.info("[LoopDetection] 步骤完成: {}", currentStep));
    }

    public void resetSession(String sessionId) {
        stepCounters.remove(sessionId);
        lastToolBySession.remove(sessionId);
        consecutiveCounters.remove(sessionId);
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public void setMaxRepeatCount(int maxRepeatCount) {
        this.maxRepeatCount = maxRepeatCount;
    }
}

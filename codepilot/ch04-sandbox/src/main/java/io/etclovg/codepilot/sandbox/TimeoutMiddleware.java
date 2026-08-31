package io.etclovg.codepilot.sandbox;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 超时与熔断中间件——单步超时 + 全局超时 + 熔断器三态状态机。
 * <p>对应书中 Ch04 §4.4.4 —— 超时与熔断：如何防止 $47K 级别的失控执行。
 *
 * <h2>三层防护</h2>
 * <ol>
 *   <li><b>单步超时</b>：单次工具调用的时间上限（如 30s）。
 *       用 {@link Flux#timeout(Duration)} 实现——上游在 stepTimeout 内未发出下一个元素即触发 TimeoutException</li>
 *   <li><b>全局超时</b>：整个任务从开始到现在的总时间上限（如 5min）。
 *       用 {@link Flux#takeUntilOther} + {@link Mono#delay} 实现——到点硬终止整个 Flux</li>
 *   <li><b>熔断器三态</b>：连续失败达阈值后 Open 状态直接拒绝请求，等恢复时间后 Half-Open 探测一次。
 *       三态：Closed（正常）→ Open（拒绝）→ Half-Open（探测）→ Closed（恢复）</li>
 * </ol>
 *
 * <h2>熔断器三态状态机</h2>
 * <pre>
 *   ┌─────────┐  连续失败 ≥ threshold  ┌─────────┐
 *   │ CLOSED  │ ─────────────────────→ │  OPEN   │
 *   │ (正常)  │                         │ (拒绝)  │
 *   └─────────┘                         └────┬────┘
 *        ↑                                   │ 等 recovery 时间
 *        │                                   ↓
 *        │                              ┌──────────┐
 *        └── 探测成功 ───────────────── │ HALF_OPEN│
 *                                       │ (探测1次) │── 探测失败 ──→ 回到 OPEN
 *                                       └──────────┘
 * </pre>
 */
@Component
public class TimeoutMiddleware extends AbstractLayerMiddleware {

    // ===== 超时参数 =====
    private Duration globalTimeout = Duration.ofMinutes(5);
    private Duration stepTimeout = Duration.ofSeconds(30);

    // ===== 熔断参数 =====
    private int circuitBreakerThreshold = 3;
    private Duration circuitBreakerRecovery = Duration.ofSeconds(60);

    // ===== 运行时状态（按 sessionId 隔离，线程安全） =====
    private final Map<String, Instant> taskStartTimes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
    private final Map<String, Instant> circuitOpenUntil = new ConcurrentHashMap<>();

    public TimeoutMiddleware() {
        super(Layer.E, "Timeout");
    }

    public TimeoutMiddleware(Duration globalTimeout, Duration stepTimeout) {
        super(Layer.E, "Timeout");
        this.globalTimeout = globalTimeout;
        this.stepTimeout = stepTimeout;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String sessionId = rc.getSessionId();

        // ① 熔断状态机检查
        CircuitState state = circuitStates.getOrDefault(sessionId, CircuitState.CLOSED);
        switch (state) {
            case OPEN -> {
                Instant openUntil = circuitOpenUntil.get(sessionId);
                if (Instant.now().isBefore(openUntil)) {
                    // OPEN：还在恢复期内，直接拒绝
                    Duration waitTime = Duration.between(Instant.now(), openUntil);
                    log.warn("[CircuitBreaker] sessionId={} OPEN，距恢复 {}s", sessionId, waitTime.getSeconds());
                    return Flux.error(new SandboxTimeoutException("CIRCUIT_BREAKER_OPEN",
                        "熔断中（OPEN），距恢复还有 " + waitTime.getSeconds() + "s"));
                } else {
                    // 恢复期到了 → OPEN 转 HALF_OPEN，放行这一次探测请求
                    circuitStates.put(sessionId, CircuitState.HALF_OPEN);
                    log.info("[CircuitBreaker] sessionId={} OPEN → HALF_OPEN，放行探测请求", sessionId);
                }
            }
            case HALF_OPEN -> {
                // 已有探测请求在执行，拒绝其他并发请求
                log.warn("[CircuitBreaker] sessionId={} HALF_OPEN，已有探测在执行", sessionId);
                return Flux.error(new SandboxTimeoutException("CIRCUIT_BREAKER_HALF_OPEN",
                    "半开状态（HALF_OPEN），已有探测请求在执行，请等待结果"));
            }
            case CLOSED -> {
                // 正常状态，放行
            }
        }

        // ② 记录任务开始时间（第一次调用时记录，后续步骤不覆盖）
        Instant taskStart = taskStartTimes.computeIfAbsent(sessionId, k -> Instant.now());

        // ③ 全局超时检查
        Duration elapsed = Duration.between(taskStart, Instant.now());
        if (elapsed.compareTo(globalTimeout) >= 0) {
            taskStartTimes.remove(sessionId);
            return Flux.error(new SandboxTimeoutException("GLOBAL_TIMEOUT",
                "任务总耗时 " + elapsed.getSeconds() + "s，超过全局上限 " + globalTimeout.getSeconds() + "s"));
        }

        // ④ 计算剩余全局时间
        Duration remaining = globalTimeout.minus(elapsed);
        Duration effectiveStepTimeout = remaining.compareTo(stepTimeout) < 0 ? remaining : stepTimeout;

        // ⑤ 执行：单步超时 + 全局超时双保险
        return next.apply(input)
                .timeout(effectiveStepTimeout)
                .takeUntilOther(
                    Mono.delay(remaining).then(Mono.error(
                        new SandboxTimeoutException("GLOBAL_TIMEOUT", "全局超时 " + globalTimeout.getSeconds() + "s")))
                )
                .doOnComplete(() -> onSuccess(sessionId))
                .doOnError(e -> onFailure(sessionId))
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        log.warn("[Timeout] sessionId={} 被全局超时取消", sessionId);
                    }
                });
    }

    /** 成功：重置失败计数，HALF_OPEN → CLOSED */
    private void onSuccess(String sessionId) {
        CircuitState prev = circuitStates.get(sessionId);
        consecutiveFailures.remove(sessionId);
        circuitOpenUntil.remove(sessionId);
        circuitStates.put(sessionId, CircuitState.CLOSED);
        taskStartTimes.remove(sessionId);
        if (prev == CircuitState.HALF_OPEN) {
            log.info("[CircuitBreaker] sessionId={} HALF_OPEN → CLOSED（探测成功）", sessionId);
        }
    }

    /** 失败：增加连续失败计数，CLOSED → OPEN 或 HALF_OPEN → OPEN */
    private void onFailure(String sessionId) {
        CircuitState prev = circuitStates.getOrDefault(sessionId, CircuitState.CLOSED);

        if (prev == CircuitState.HALF_OPEN) {
            // HALF_OPEN 探测失败 → 直接回到 OPEN，重置恢复时间
            circuitStates.put(sessionId, CircuitState.OPEN);
            circuitOpenUntil.put(sessionId, Instant.now().plus(circuitBreakerRecovery));
            log.warn("[CircuitBreaker] sessionId={} HALF_OPEN → OPEN（探测失败），恢复时间 {}s",
                sessionId, circuitBreakerRecovery.getSeconds());
            return;
        }

        // CLOSED 状态下累计失败次数
        int failures = consecutiveFailures
            .computeIfAbsent(sessionId, k -> new AtomicInteger(0))
            .incrementAndGet();

        if (failures >= circuitBreakerThreshold) {
            // 达到阈值 → CLOSED → OPEN
            circuitStates.put(sessionId, CircuitState.OPEN);
            circuitOpenUntil.put(sessionId, Instant.now().plus(circuitBreakerRecovery));
            log.warn("[CircuitBreaker] sessionId={} CLOSED → OPEN（连续失败 {}/{}），恢复时间 {}s",
                sessionId, failures, circuitBreakerThreshold, circuitBreakerRecovery.getSeconds());
        } else {
            log.warn("[CircuitBreaker] sessionId={} 连续失败 {}/{}", sessionId, failures, circuitBreakerThreshold);
        }
    }

    // ===== Getter / Setter =====

    public Duration getGlobalTimeout() { return globalTimeout; }
    public Duration getStepTimeout() { return stepTimeout; }
    public int getCircuitBreakerThreshold() { return circuitBreakerThreshold; }
    public Duration getCircuitBreakerRecovery() { return circuitBreakerRecovery; }

    public void setGlobalTimeout(Duration d) { this.globalTimeout = d; }
    public void setStepTimeout(Duration d) { this.stepTimeout = d; }
    public void setCircuitBreakerThreshold(int t) { this.circuitBreakerThreshold = t; }
    public void setCircuitBreakerRecovery(Duration d) { this.circuitBreakerRecovery = d; }

    /**
     * 熔断器三态。
     * <ul>
     *   <li>{@link #CLOSED} — 正常状态，所有请求放行</li>
     *   <li>{@link #OPEN} — 熔断状态，所有请求直接拒绝，等待 recovery 时间后转 HALF_OPEN</li>
     *   <li>{@link #HALF_OPEN} — 半开状态，只允许一个探测请求通过，成功则 CLOSED，失败则 OPEN</li>
     * </ul>
     */
    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 超时与熔断异常——区分类型，便于上层处理。
     */
    public static class SandboxTimeoutException extends RuntimeException {
        private final String code;

        public SandboxTimeoutException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() { return code; }
    }
}

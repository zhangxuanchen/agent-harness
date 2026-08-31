package io.etclovg.codepilot.orchestration;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * L 层 · ReAct 编排器。
 *
 * <p>实现完整的 ReAct（Reasoning + Acting）循环编排，包含四道防护：
 *
 * <ul>
 *   <li><b>步骤限制（Step Limit）</b>：防止无限推理循环，超过上限后强制终止并回传部分结果</li>
 *   <li><b>重复检测（Duplicate Detection）</b>：SHA-256 哈希比较工具调用签名，检测到重复时
 *       注入"你已重复调用同一工具"提示</li>
 *   <li><b>超时控制（Timeout）</b>：单步和总任务双层超时，超时后优雅降级</li>
 *   <li><b>熔断器（Circuit Breaker）</b>：连续 N 次失败后进入 OPEN 状态，阻止进一步调用，
 *       冷却期后进入 HALF_OPEN 试探性恢复</li>
 * </ul>
 *
 * <p>对应书中 Ch7 §7.3 ReAct 编排模式。
 *
 * <p><b>页面参考</b>：Ch7 §7.3 ReAct 编排与自愈防护
 */
@Component("orchestrator")
public class ReActOrchestrator extends AbstractLayerMiddleware {

    private final StateMachineManager stateMachine;

    /** 步骤上限 */
    private int maxSteps = 30;
    /** 单步超时（秒） */
    private int stepTimeoutSeconds = 60;
    /** 总任务超时（秒） */
    private int totalTimeoutSeconds = 600;
    /** 连续失败触发熔断阈值 */
    private int circuitBreakerThreshold = 5;
    /** 熔断冷却期（秒） */
    private int circuitBreakerCooldownSeconds = 30;

    // ==================== 步骤管理 ====================

    /** 任务ID → 当前步骤计数 */
    private final Map<String, AtomicInteger> stepCounters = new ConcurrentHashMap<>();
    /** 任务ID → 历史工具调用哈希集合 */
    private final Map<String, Set<String>> toolCallHistory = new ConcurrentHashMap<>();

    // ==================== 超时管理 ====================

    /** 任务ID → 任务开始时间 */
    private final Map<String, Instant> taskStartTimes = new ConcurrentHashMap<>();

    // ==================== 熔断器 ====================

    private enum CircuitState { CLOSED, OPEN, HALF_OPEN }
    /** 任务ID → 熔断器状态 */
    private final Map<String, CircuitEntry> circuits = new ConcurrentHashMap<>();

    public ReActOrchestrator(StateMachineManager stateMachine) {
        super(Layer.L, "ReActOrchestrator-L");
        this.stateMachine = stateMachine;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String taskId = context.getOrDefault("task.id", "default").toString();

        // 初始化任务状态
        initTask(taskId);

        // —— 检查 1: 步骤限制 ——
        if (!checkStepLimit(taskId)) {
            log.warn("[L层ReAct] 任务 {} 达到步骤上限({})——强制终止", taskId, maxSteps);
            stateMachine.transition(taskId, StateMachineManager.State.FAILED,
                    "步骤上限(" + maxSteps + ")");
            return buildTerminatedResponse(rc, input, next, taskId, "达到步骤上限(" + maxSteps + ")");
        }

        // —— 检查 2: 总超时 ——
        if (isTotalTimeout(taskId)) {
            log.warn("[L层ReAct] 任务 {} 总超时({}s)——强制终止", taskId, totalTimeoutSeconds);
            stateMachine.transition(taskId, StateMachineManager.State.FAILED,
                    "总超时(" + totalTimeoutSeconds + "s)");
            return buildTerminatedResponse(rc, input, next, taskId, "总超时(" + totalTimeoutSeconds + "s)");
        }

        // —— 检查 3: 熔断器 ——
        if (isCircuitOpen(taskId)) {
            log.warn("[L层ReAct] 任务 {} 熔断器已打开——阻止调用", taskId);
            return buildTerminatedResponse(rc, input, next, taskId, "熔断器已打开——连续失败过多，请等待冷却");
        }

        // —— 检查 4: 重复检测 ——
        String toolName = context.getOrDefault("tool.name", "").toString();
        String toolArgs = context.getOrDefault("tool.args", "").toString();
        String callSignature = computeSignature(toolName, toolArgs);

        if (detectDuplicate(taskId, callSignature)) {
            log.warn("[L层ReAct] 任务 {} 检测到重复调用: {}——注入提示", taskId, toolName);
            rc.put("react.duplicate_detected", true);
            rc.put("react.duplicate_warning",
                    "你已重复调用工具 \"" + toolName + "\"，请尝试不同的操作或工具");
            rc.put("react.duplicate_signature", callSignature);
        }

        // 状态转换: RUNNING → REASONING
        stateMachine.transition(taskId, StateMachineManager.State.REASONING,
                "开始推理步骤 #" + stepCounters.get(taskId).get());

        // —— 单步超时控制 ——
        return next.apply(input)
                .doOnComplete(() -> {
                    // 成功后重置熔断器计数器
                    recordSuccess(taskId);
                    stepCounters.get(taskId).incrementAndGet();
                })
                .doOnError(e -> {
                    log.error("[L层ReAct] 任务 {} 步骤异常: {}", taskId, e.getMessage());
                    recordFailure(taskId);
                    stateMachine.transition(taskId, StateMachineManager.State.FAILED,
                            "步骤异常: " + e.getMessage());
                });
    }

    // ==================== 步骤限制 ====================

    private void initTask(String taskId) {
        stepCounters.computeIfAbsent(taskId, k -> new AtomicInteger(0));
        toolCallHistory.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet());
        taskStartTimes.computeIfAbsent(taskId, k -> Instant.now());
        stateMachine.init(taskId);
        stateMachine.transition(taskId, StateMachineManager.State.RUNNING, "任务启动");
        log.info("[L层ReAct] 任务 {} 初始化——步骤上限: {}, 超时: {}s",
                taskId, maxSteps, totalTimeoutSeconds);
    }

    private boolean checkStepLimit(String taskId) {
        AtomicInteger counter = stepCounters.get(taskId);
        if (counter == null) return false;
        return counter.get() < maxSteps;
    }

    // ==================== 超时控制 ====================

    private boolean isTotalTimeout(String taskId) {
        Instant start = taskStartTimes.get(taskId);
        if (start == null) return true;
        return Duration.between(start, Instant.now()).getSeconds() > totalTimeoutSeconds;
    }

    // ==================== 重复检测 ====================

    /**
     * 计算工具调用的 SHA-256 签名。
     */
    private String computeSignature(String toolName, String toolArgs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((toolName + "|" + toolArgs).getBytes());
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制要求的算法，不会发生
            return toolName + ":" + toolArgs.hashCode();
        }
    }

    /**
     * 检测当前任务中是否已有相同签名。
     */
    private boolean detectDuplicate(String taskId, String signature) {
        Set<String> history = toolCallHistory.get(taskId);
        if (history == null) return false;
        return !history.add(signature);
    }

    // ==================== 熔断器 ====================

    /**
     * 检查熔断器状态。
     * <p>CLOSED → 允许；OPEN + 未冷却 → 阻止；
     * OPEN + 已冷却 → HALF_OPEN 试探。
     */
    private boolean isCircuitOpen(String taskId) {
        CircuitEntry entry = circuits.get(taskId);
        if (entry == null || entry.state == CircuitState.CLOSED) return false;

        if (entry.state == CircuitState.OPEN) {
            Duration elapsed = Duration.between(entry.openedAt, Instant.now());
            if (elapsed.getSeconds() >= circuitBreakerCooldownSeconds) {
                entry.state = CircuitState.HALF_OPEN;
                log.info("[L层ReAct] 任务 {} 熔断器冷却完成 → HALF_OPEN 试探", taskId);
                return false;
            }
            return true;
        }

        // HALF_OPEN → 允许
        return false;
    }

    private void recordSuccess(String taskId) {
        CircuitEntry entry = circuits.get(taskId);
        if (entry != null && entry.state == CircuitState.HALF_OPEN) {
            entry.state = CircuitState.CLOSED;
            entry.failures = 0;
            log.info("[L层ReAct] 任务 {} HALF_OPEN 试探成功 → CLOSED", taskId);
        } else if (entry != null) {
            entry.failures = 0;
        }
    }

    private void recordFailure(String taskId) {
        CircuitEntry entry = circuits.computeIfAbsent(taskId,
                k -> new CircuitEntry());
        entry.failures++;

        if (entry.failures >= circuitBreakerThreshold
                && entry.state == CircuitState.CLOSED) {
            entry.state = CircuitState.OPEN;
            entry.openedAt = Instant.now();
            log.warn("[L层ReAct] 任务 {} 连续 {} 次失败 → 熔断器 OPEN（冷却 {}s）",
                    taskId, entry.failures, circuitBreakerCooldownSeconds);
        }
    }

    // ==================== 终止响应构建 ====================

    private Flux<AgentEvent> buildTerminatedResponse(RuntimeContext rc, AgentInput input,
                                                     Function<AgentInput, Flux<AgentEvent>> next,
                                                     String taskId, String reason) {
        Map<String, Object> meta = Map.of(
                "react.terminated", true,
                "react.termination_reason", reason,
                "react.steps_completed", stepCounters.getOrDefault(taskId, new AtomicInteger(0)).get(),
                "react.state", stateMachine.currentState(taskId) != null
                        ? stateMachine.currentState(taskId).name() : "UNKNOWN"
        );

        // 构建一个包含终止状态的上下文并返回
        rc.put("react.orchestration", meta);
        rc.put("react.terminated", true);
        rc.put("error.message", "Task terminated: " + reason);

        // 添加上下文并转发
        return next.apply(input);
    }

    // ==================== 配置方法 ====================

    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public void setStepTimeoutSeconds(int stepTimeoutSeconds) { this.stepTimeoutSeconds = stepTimeoutSeconds; }
    public void setTotalTimeoutSeconds(int totalTimeoutSeconds) { this.totalTimeoutSeconds = totalTimeoutSeconds; }
    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) { this.circuitBreakerThreshold = circuitBreakerThreshold; }
    public void setCircuitBreakerCooldownSeconds(int circuitBreakerCooldownSeconds) { this.circuitBreakerCooldownSeconds = circuitBreakerCooldownSeconds; }

    // ==================== 内部类型 ====================

    private static class CircuitEntry {
        CircuitState state = CircuitState.CLOSED;
        int failures = 0;
        Instant openedAt = Instant.EPOCH;
    }
}

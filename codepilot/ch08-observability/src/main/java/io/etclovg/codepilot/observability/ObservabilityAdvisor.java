package io.etclovg.codepilot.observability;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * O 层 · 可观测性总控 Advisor。
 *
 * <p>作为 O 层的核心组件，实现"Harness 即假设"概念——每个 Advisor 都是可观测的假设。
 * 对应书中 Ch8 §8.1 三层指标体系与可观测性总控设计。
 *
 * <p><b>三层指标体系</b>：
 * <ul>
 *   <li><b>调用级指标（Call-level）</b>：单次 LLM 调用的 token 消耗、延迟、成功率</li>
 *   <li><b>任务级指标（Task-level）</b>：任务维度的聚合指标，如任务完成率、平均延迟</li>
 *   <li><b>业务级指标（Business-level）</b>：业务维度指标，如成本效率、用户满意度</li>
 * </ul>
 *
 * <p><b>核心职责</b>：
 * <ul>
 *   <li>在模型调用前后注入观测点</li>
 *   <li>收集 token 消耗、延迟、成功率等核心指标</li>
 *   <li>集成 Micrometer 导出 Prometheus/Grafana 指标</li>
 *   <li>为其他 O 层组件提供统一数据源</li>
 * </ul>
 *
 * <p><b>与书中内容对应</b>：
 * <ul>
 *   <li>§8.1.1 三层指标体系设计</li>
 *   <li>§8.1.2 Harness 即假设理念</li>
 *   <li>§8.1.3 可观测性总控架构</li>
 * </ul>
 *
 * <p><b>页面参考</b>：Ch8 §8.1 可观测性总控设计
 */
@Component
public class ObservabilityAdvisor extends AbstractLayerMiddleware {

    private final MeterRegistry meterRegistry;
    private final CostTracker costTracker;
    private final BurnRateCalculator burnRateCalculator;

    // ==================== 调用级指标 ====================

    /** 调用成功计数器 */
    private final Counter callSuccessCounter;
    /** 调用失败计数器 */
    private final Counter callFailureCounter;
    /** 调用延迟计时器 */
    private final Timer callLatencyTimer;

    // ==================== 任务级指标 ====================

    /** 任务ID → 调用次数 */
    private final Map<String, AtomicLong> taskCallCounts = new ConcurrentHashMap<>();
    /** 任务ID → 成功次数 */
    private final Map<String, AtomicLong> taskSuccessCounts = new ConcurrentHashMap<>();
    /** 任务ID → 累计延迟 */
    private final Map<String, AtomicLong> taskLatencyAccumulator = new ConcurrentHashMap<>();
    /** 任务ID → 累计输入 token */
    private final Map<String, AtomicLong> taskInputTokens = new ConcurrentHashMap<>();
    /** 任务ID → 累计输出 token */
    private final Map<String, AtomicLong> taskOutputTokens = new ConcurrentHashMap<>();

    // ==================== 业务级指标 ====================

    /** 全局累计调用次数 */
    private final AtomicLong globalCallCount = new AtomicLong(0);
    /** 全局累计成功次数 */
    private final AtomicLong globalSuccessCount = new AtomicLong(0);
    /** 全局累计失败次数 */
    private final AtomicLong globalFailureCount = new AtomicLong(0);

    /**
     * 构造函数，初始化 Micrometer 指标。
     */
    public ObservabilityAdvisor(MeterRegistry meterRegistry,
                                CostTracker costTracker,
                                BurnRateCalculator burnRateCalculator) {
        super(Layer.O, "Observability");
        this.meterRegistry = meterRegistry;
        this.costTracker = costTracker;
        this.burnRateCalculator = burnRateCalculator;

        // 初始化 Micrometer 指标
        this.callSuccessCounter = Counter.builder("codepilot.llm.calls")
                .tag("status", "success")
                .description("LLM call success count")
                .register(meterRegistry);

        this.callFailureCounter = Counter.builder("codepilot.llm.calls")
                .tag("status", "failure")
                .description("LLM call failure count")
                .register(meterRegistry);

        this.callLatencyTimer = Timer.builder("codepilot.llm.latency")
                .description("LLM call latency")
                .register(meterRegistry);

        // 注册业务级 Gauge 指标
        Gauge.builder("codepilot.global.calls.total", globalCallCount, AtomicLong::get)
                .description("Total LLM calls")
                .register(meterRegistry);

        Gauge.builder("codepilot.global.calls.success", globalSuccessCount, AtomicLong::get)
                .description("Successful LLM calls")
                .register(meterRegistry);

        Gauge.builder("codepilot.global.calls.failure", globalFailureCount, AtomicLong::get)
                .description("Failed LLM calls")
                .register(meterRegistry);

        log.info("[O层·观测] ObservabilityAdvisor 已初始化，集成 Micrometer 指标导出");
    }

    /**
     * 核心拦截方法：在模型调用前后注入观测点。
     *
     * <p>对应书中 Ch8 §8.1.2 "Harness 即假设"——每次调用都是对假设的验证。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String taskId = extractTaskId(context);
        String userId = extractUserId(context);
        String sessionId = extractSessionId(context);
        String modelName = extractModelName(context);

        // 记录调用开始
        Instant startTime = Instant.now();
        long callNumber = globalCallCount.incrementAndGet();

        log.debug("[O层·观测] 调用开始 #{}, taskId={}, model={}",
                callNumber, taskId, modelName);

        return next.apply(input)
                .doOnComplete(() -> {
                    // 提取 token 使用情况
                    extractAndRecordUsage(rc, taskId, userId, sessionId, modelName);

                    // 记录成功指标
                    recordSuccess(taskId);

                    // 计算延迟
                    long latencyMs = Duration.between(startTime, Instant.now()).toMillis();

                    // 记录延迟指标
                    recordLatency(taskId, latencyMs);

                    // 记录调用完成日志
                    logCallCompletion(callNumber, taskId, true, latencyMs, null);
                })
                .doOnError(e -> {
                    // 记录失败指标
                    recordFailure(taskId, e);

                    // 计算延迟
                    long latencyMs = Duration.between(startTime, Instant.now()).toMillis();

                    // 记录延迟指标
                    recordLatency(taskId, latencyMs);

                    // 记录调用完成日志
                    logCallCompletion(callNumber, taskId, false, latencyMs, e);
                });
    }

    // ==================== 指标记录方法 ====================

    /**
     * 提取并记录 token 使用情况。
     *
     * <p>从 RuntimeContext 的 extra 中提取 Usage 元数据，
     * 记录到 CostTracker 并导出 Micrometer 指标。
     *
     * <p>注意：AgentScope 2.0 中，Usage 信息由底层模型调用填充到 RuntimeContext，
     * 我们通过 extra map 传递 Usage 信息。
     */
    private void extractAndRecordUsage(RuntimeContext rc, String taskId,
                                       String userId, String sessionId, String modelName) {
        try {
            Map<String, Object> responseContext = rc.getExtra();

            // 从 context 中提取 Usage 信息（由底层填充）
            Long inputTokens = getLongFromContext(responseContext, "usage.prompt_tokens");
            Long outputTokens = getLongFromContext(responseContext, "usage.generation_tokens");

            if (inputTokens == null) inputTokens = 0L;
            if (outputTokens == null) outputTokens = 0L;

            // 记录到任务级指标
            taskInputTokens.computeIfAbsent(taskId, k -> new AtomicLong(0))
                    .addAndGet(inputTokens);
            taskOutputTokens.computeIfAbsent(taskId, k -> new AtomicLong(0))
                    .addAndGet(outputTokens);

            // 记录到 CostTracker（五标签成本归因）
            costTracker.recordModelUsage(userId, sessionId, taskId,
                    modelName, inputTokens, outputTokens);

            // 记录到 BurnRateCalculator（消耗率监控）
            // burnRateCalculator.recordCost(taskId, cost); // 需要计算成本

            // 导出 Micrometer 指标
            meterRegistry.counter("codepilot.llm.tokens.input",
                    "task", taskId, "model", modelName).increment(inputTokens);
            meterRegistry.counter("codepilot.llm.tokens.output",
                    "task", taskId, "model", modelName).increment(outputTokens);

            log.debug("[O层·观测] Token 使用: task={}, in={}, out={}",
                    taskId, inputTokens, outputTokens);
        } catch (Exception e) {
            log.warn("[O层·观测] 提取 Usage 失败: {}", e.getMessage());
        }
    }

    /**
     * 从 context 中安全地提取 Long 值。
     */
    private Long getLongFromContext(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 记录成功调用。
     */
    private void recordSuccess(String taskId) {
        callSuccessCounter.increment();
        globalSuccessCount.incrementAndGet();
        taskSuccessCounts.computeIfAbsent(taskId, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 记录失败调用。
     */
    private void recordFailure(String taskId, Throwable error) {
        callFailureCounter.increment();
        globalFailureCount.incrementAndGet();
        log.error("[O层·观测] 调用失败: taskId={}, error={}", taskId, error.getMessage());
    }

    /**
     * 记录延迟指标。
     */
    private void recordLatency(String taskId, long latencyMs) {
        callLatencyTimer.record(java.time.Duration.ofMillis(latencyMs));
        taskLatencyAccumulator.computeIfAbsent(taskId, k -> new AtomicLong(0))
                .addAndGet(latencyMs);
        taskCallCounts.computeIfAbsent(taskId, k -> new AtomicLong(0)).incrementAndGet();
    }

    // ==================== 查询方法 ====================

    /**
     * 获取任务级成功率。
     *
     * @param taskId 任务ID
     * @return 成功率（0.0 ~ 1.0）
     */
    public double getTaskSuccessRate(String taskId) {
        AtomicLong successCount = taskSuccessCounts.get(taskId);
        AtomicLong callCount = taskCallCounts.get(taskId);

        if (successCount == null || callCount == null || callCount.get() == 0) {
            return 0.0;
        }

        return (double) successCount.get() / callCount.get();
    }

    /**
     * 获取任务级平均延迟（毫秒）。
     *
     * @param taskId 任务ID
     * @return 平均延迟（毫秒）
     */
    public double getTaskAverageLatency(String taskId) {
        AtomicLong latencySum = taskLatencyAccumulator.get(taskId);
        AtomicLong callCount = taskCallCounts.get(taskId);

        if (latencySum == null || callCount == null || callCount.get() == 0) {
            return 0.0;
        }

        return (double) latencySum.get() / callCount.get();
    }

    /**
     * 获取全局成功率。
     *
     * @return 全局成功率（0.0 ~ 1.0）
     */
    public double getGlobalSuccessRate() {
        long total = globalCallCount.get();
        if (total == 0) return 0.0;
        return (double) globalSuccessCount.get() / total;
    }

    /**
     * 获取任务级指标快照。
     *
     * @param taskId 任务ID
     * @return 任务级指标快照
     */
    public TaskMetrics getTaskMetrics(String taskId) {
        return new TaskMetrics(
                taskId,
                taskCallCounts.getOrDefault(taskId, new AtomicLong(0)).get(),
                taskSuccessCounts.getOrDefault(taskId, new AtomicLong(0)).get(),
                taskInputTokens.getOrDefault(taskId, new AtomicLong(0)).get(),
                taskOutputTokens.getOrDefault(taskId, new AtomicLong(0)).get(),
                getTaskAverageLatency(taskId),
                getTaskSuccessRate(taskId)
        );
    }

    /**
     * 获取业务级指标快照。
     *
     * @return 业务级指标快照
     */
    public BusinessMetrics getBusinessMetrics() {
        return new BusinessMetrics(
                globalCallCount.get(),
                globalSuccessCount.get(),
                globalFailureCount.get(),
                getGlobalSuccessRate(),
                costTracker.getTotalCost(),
                burnRateCalculator.getSnapshot()
        );
    }

    // ==================== 辅助方法 ====================

    private String extractTaskId(Map<String, Object> context) {
        return context.getOrDefault("task.id", "unknown").toString();
    }

    private String extractUserId(Map<String, Object> context) {
        return context.getOrDefault("user.id", "anonymous").toString();
    }

    private String extractSessionId(Map<String, Object> context) {
        return context.getOrDefault("session.id", "default").toString();
    }

    private String extractModelName(Map<String, Object> context) {
        return context.getOrDefault("model.name", "unknown").toString();
    }

    private void logCallCompletion(long callNumber, String taskId,
                                   boolean success, long latencyMs, Throwable error) {
        if (success) {
            log.debug("[O层·观测] 调用完成 #{}, taskId={}, latency={}ms, status=SUCCESS",
                    callNumber, taskId, latencyMs);
        } else {
            log.error("[O层·观测] 调用失败 #{}, taskId={}, latency={}ms, status=FAIL, error={}",
                    callNumber, taskId, latencyMs, error != null ? error.getMessage() : "unknown");
        }
    }

    // ==================== 指标数据结构 ====================

    /**
     * 任务级指标快照。
     */
    public record TaskMetrics(
            String taskId,
            long callCount,
            long successCount,
            long inputTokens,
            long outputTokens,
            double avgLatencyMs,
            double successRate
    ) {
        @Override
        public String toString() {
            return String.format("TaskMetrics[id=%s, calls=%d, success=%.1f%%, " +
                            "tokens=%d/%d, latency=%.1fms]",
                    taskId, callCount, successRate * 100,
                    inputTokens, outputTokens, avgLatencyMs);
        }
    }

    /**
     * 业务级指标快照。
     */
    public record BusinessMetrics(
            long totalCalls,
            long successCalls,
            long failedCalls,
            double globalSuccessRate,
            java.math.BigDecimal totalCost,
            BurnRateCalculator.Snapshot burnRateSnapshot
    ) {
        @Override
        public String toString() {
            return String.format("BusinessMetrics[calls=%d, success=%.1f%%, cost=$%.4f, burnRate=%s]",
                    totalCalls, globalSuccessRate * 100, totalCost, burnRateSnapshot);
        }
    }
}

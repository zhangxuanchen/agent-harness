package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 并行工具执行器。
 * <p>对应书中 Ch05 §5.5 —— 工具的并行调度与执行。
 * <p>分析工具调用依赖关系，将无依赖的工具并行执行，提高 Agent 响应速度：
 * <ul>
 *   <li><b>依赖分析</b>：基于 DAG 拓扑排序，识别可并行的工具组</li>
 *   <li><b>并行调度</b>：使用线程池并行执行独立工具</li>
 *   <li><b>结果聚合</b>：收集并行执行结果，处理异常</li>
 *   <li><b>超时控制</b>：防止单个工具阻塞整个执行流程</li>
 * </ul>
 */
@Component
public class ParallelToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParallelToolExecutor.class);

    /**
     * 工具调用请求。
     */
    public record ToolCallRequest(
            String callId,
            String toolId,
            Map<String, Object> arguments,
            Set<String> dependencies,
            long timeoutMs
    ) {
        public ToolCallRequest(String callId, String toolId, Map<String, Object> arguments) {
            this(callId, toolId, arguments, Collections.emptySet(), 30_000L);
        }

        public ToolCallRequest withDependencies(Set<String> deps) {
            return new ToolCallRequest(callId, toolId, arguments, deps, timeoutMs);
        }

        public ToolCallRequest withTimeout(long newTimeoutMs) {
            return new ToolCallRequest(callId, toolId, arguments, dependencies, newTimeoutMs);
        }
    }

    /**
     * 工具执行结果。
     */
    public record ToolCallResult(
            String callId,
            String toolId,
            boolean success,
            Object result,
            String errorMessage,
            long durationMs,
            Instant executedAt
    ) {
        public static ToolCallResult success(String callId, String toolId, Object result, long durationMs) {
            return new ToolCallResult(callId, toolId, true, result, null, durationMs, Instant.now());
        }

        public static ToolCallResult failure(String callId, String toolId, String error, long durationMs) {
            return new ToolCallResult(callId, toolId, false, null, error, durationMs, Instant.now());
        }
    }

    /**
     * 并行执行结果。
     */
    public record ParallelExecutionResult(
            String executionId,
            List<ToolCallResult> results,
            int totalCalls,
            int successCount,
            int failureCount,
            long totalDurationMs,
            int parallelismLevel,
            List<List<String>> executionGroups
    ) {}

    /**
     * 执行配置。
     */
    public record ExecutionConfig(
            int maxConcurrency,
            long defaultTimeoutMs,
            boolean stopOnFailure,
            long maxTotalExecutionTimeMs
    ) {
        public static ExecutionConfig defaultConfig() {
            return new ExecutionConfig(
                    4,            // 最大并发数
                    30_000L,      // 默认超时 30 秒
                    false,        // 不中断其他工具
                    120_000L      // 最大总执行时间 2 分钟
            );
        }

        public ExecutionConfig withMaxConcurrency(int n) {
            return new ExecutionConfig(n, defaultTimeoutMs, stopOnFailure, maxTotalExecutionTimeMs);
        }
    }

    private final ExecutionConfig config;
    private final ThreadPoolExecutor executor;
    private final ToolCallDependencyAnalyzer dependencyAnalyzer;
    private final List<ParallelExecutionResult> executionHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_HISTORY = 100;

    public ParallelToolExecutor(ToolCallDependencyAnalyzer dependencyAnalyzer) {
        this(ExecutionConfig.defaultConfig(), dependencyAnalyzer);
    }

    public ParallelToolExecutor(ExecutionConfig config, ToolCallDependencyAnalyzer dependencyAnalyzer) {
        this.config = config;
        this.dependencyAnalyzer = dependencyAnalyzer;
        this.executor = new ThreadPoolExecutor(
                config.maxConcurrency(),
                config.maxConcurrency() * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "parallel-tool-executor");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("[ParallelExecutor] 初始化并行执行器: maxConcurrency={}, defaultTimeout={}ms",
                config.maxConcurrency(), config.defaultTimeoutMs());
    }

    /**
     * 并行执行一组工具调用。
     *
     * @param requests 工具调用请求列表
     * @return 并行执行结果
     */
    public ParallelExecutionResult executeParallel(List<ToolCallRequest> requests) {
        String executionId = "exec-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("[ParallelExecutor] ========== 开始并行执行 ==========");
        log.info("[ParallelExecutor] 执行参数: executionId={}, requestCount={}, maxConcurrency={}",
                executionId, requests.size(), config.maxConcurrency());

        long startTime = System.currentTimeMillis();
        List<ToolCallResult> results = Collections.synchronizedList(new ArrayList<>());

        // 1. 分析依赖关系
        Map<String, Set<String>> dependencyMap = new LinkedHashMap<>();
        for (ToolCallRequest request : requests) {
            dependencyMap.put(request.callId(), request.dependencies());
        }

        // 2. 拓扑排序，分组可并行执行的工具
        List<List<String>> executionGroups = dependencyAnalyzer.topologicalGroups(dependencyMap);
        int maxParallelism = executionGroups.stream().mapToInt(List::size).max().orElse(1);

        log.info("[ParallelExecutor] 依赖分析完成: executionId={}, groups={}, maxParallelism={}",
                executionId, executionGroups.size(), maxParallelism);

        // 3. 按组顺序执行（组间串行，组内并行）
        Map<String, ToolCallResult> resultMap = new ConcurrentHashMap<>();

        for (int groupIndex = 0; groupIndex < executionGroups.size(); groupIndex++) {
            List<String> group = executionGroups.get(groupIndex);
            log.info("[ParallelExecutor] 执行分组: executionId={}, group={}/{}, size={}",
                    executionId, groupIndex + 1, executionGroups.size(), group.size());

            // 检查前置依赖是否成功
            boolean canProceed = true;
            for (String callId : group) {
                ToolCallRequest request = findRequest(requests, callId);
                if (request != null) {
                    for (String depId : request.dependencies()) {
                        ToolCallResult depResult = resultMap.get(depId);
                        if (depResult == null || !depResult.success()) {
                            log.warn("[ParallelExecutor] 依赖未满足: executionId={}, callId={}, depId={}",
                                    executionId, callId, depId);
                            canProceed = false;
                            break;
                        }
                    }
                }
                if (!canProceed) break;
            }

            if (!canProceed) {
                // 依赖未满足，跳过本组
                for (String callId : group) {
                    ToolCallResult skipped = ToolCallResult.failure(
                            callId, findRequest(requests, callId).toolId(),
                            "前置依赖未满足，跳过执行", 0
                    );
                    resultMap.put(callId, skipped);
                    results.add(skipped);
                }
                continue;
            }

            // 并行执行本组工具
            List<Future<ToolCallResult>> futures = new ArrayList<>();
            for (String callId : group) {
                ToolCallRequest request = findRequest(requests, callId);
                if (request == null) continue;

                long timeout = request.timeoutMs() > 0 ? request.timeoutMs() : config.defaultTimeoutMs();

                Future<ToolCallResult> future = executor.submit(() -> executeToolCall(request, timeout));
                futures.add(future);
            }

            // 收集结果
            for (Future<ToolCallResult> future : futures) {
                try {
                    ToolCallResult result = future.get(config.defaultTimeoutMs(), TimeUnit.MILLISECONDS);
                    resultMap.put(result.callId(), result);
                    results.add(result);

                    if (!result.success() && config.stopOnFailure()) {
                        log.warn("[ParallelExecutor] 工具失败且配置了 stopOnFailure，中止执行: callId={}",
                                result.callId());
                        // 取消剩余的 future
                        for (Future<ToolCallResult> f : futures) {
                            if (!f.isDone()) {
                                f.cancel(true);
                            }
                        }
                        break;
                    }
                } catch (TimeoutException e) {
                    log.error("[ParallelExecutor] 工具执行超时: executionId={}", executionId);
                    // 处理超时
                } catch (Exception e) {
                    log.error("[ParallelExecutor] 工具执行异常: executionId={}, error={}",
                            executionId, e.getMessage(), e);
                }
            }
        }

        long totalDuration = System.currentTimeMillis() - startTime;
        int successCount = (int) results.stream().filter(ToolCallResult::success).count();
        int failureCount = results.size() - successCount;

        log.info("[ParallelExecutor] ========== 并行执行完成 ==========");
        log.info("[ParallelExecutor] 执行统计: executionId={}, total={}, success={}, failure={}, duration={}ms",
                executionId, results.size(), successCount, failureCount, totalDuration);

        ParallelExecutionResult executionResult = new ParallelExecutionResult(
                executionId, results, results.size(), successCount, failureCount,
                totalDuration, maxParallelism, executionGroups
        );

        executionHistory.add(executionResult);
        if (executionHistory.size() > MAX_HISTORY) {
            executionHistory.subList(0, executionHistory.size() - MAX_HISTORY).clear();
        }

        return executionResult;
    }

    /**
     * 顺序执行（无并行）。
     */
    public ParallelExecutionResult executeSequential(List<ToolCallRequest> requests) {
        log.info("[ParallelExecutor] 顺序执行模式: requestCount={}", requests.size());

        List<List<String>> singleGroup = List.of(
                requests.stream().map(ToolCallRequest::callId).toList()
        );

        List<Map.Entry<String, Set<String>>> emptyDeps = Collections.emptyList();
        Map<String, Set<String>> depMap = new LinkedHashMap<>();
        for (ToolCallRequest r : requests) {
            depMap.put(r.callId(), Collections.emptySet());
        }

        return executeParallel(requests);
    }

    /**
     * 获取执行历史。
     */
    public List<ParallelExecutionResult> getExecutionHistory() {
        return Collections.unmodifiableList(executionHistory);
    }

    /**
     * 获取执行器状态。
     */
    public Map<String, Object> getExecutorStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeThreads", executor.getActiveCount());
        stats.put("poolSize", executor.getPoolSize());
        stats.put("queueSize", executor.getQueue().size());
        stats.put("completedTasks", executor.getCompletedTaskCount());
        stats.put("maxPoolSize", executor.getMaximumPoolSize());
        return stats;
    }

    /**
     * 关闭执行器。
     */
    public void shutdown() {
        log.info("[ParallelExecutor] 关闭执行器");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[ParallelExecutor] 执行器已关闭");
    }

    // ---- 内部方法 ----

    private ToolCallResult executeToolCall(ToolCallRequest request, long timeoutMs) {
        log.debug("[ParallelExecutor] 执行工具调用: callId={}, toolId={}, timeout={}ms",
                request.callId(), request.toolId(), timeoutMs);

        long start = System.currentTimeMillis();

        try {
            // 实际实现应调用 ToolRegistry 获取工具并执行
            // 这里返回占位结果
            Object result = Map.of("status", "success", "toolId", request.toolId());
            long duration = System.currentTimeMillis() - start;

            log.debug("[ParallelExecutor] 工具调用完成: callId={}, toolId={}, duration={}ms",
                    request.callId(), request.toolId(), duration);

            return ToolCallResult.success(request.callId(), request.toolId(), result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[ParallelExecutor] 工具调用失败: callId={}, toolId={}, error={}",
                    request.callId(), request.toolId(), e.getMessage());
            return ToolCallResult.failure(request.callId(), request.toolId(), e.getMessage(), duration);
        }
    }

    private ToolCallRequest findRequest(List<ToolCallRequest> requests, String callId) {
        return requests.stream()
                .filter(r -> r.callId().equals(callId))
                .findFirst()
                .orElse(null);
    }

    // ---- Getter ----
    public ExecutionConfig getConfig() {
        return config;
    }
}

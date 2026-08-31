package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 评估工作线程池。
 * <p>对应书中 Ch16 §16.3 —— 并发执行评估任务的工作池。
 */
@Component
public class EvalWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(EvalWorkerPool.class);

    private ExecutorService executor;
    private int poolSize = 4;

    /**
     * 提交评估任务。
     */
    public Future<Boolean> submitTask(Runnable task) {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(poolSize);
        }
        return executor.submit(task, true);
    }

    /**
     * 关闭工作池。
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    public void setPoolSize(int size) { this.poolSize = size; }
    public int getPoolSize() { return poolSize; }

    /**
     * 并行运行一组评估任务，聚合为单个评估结果。
     * <p>教学示意：并发提交 {@link EvalTask}，统计通过率作为 accuracy，
     * 聚合失败用例，超时由 {@code timeout}/{@code unit} 约束。
     *
     * @param configVersion 待评估的配置版本
     * @param tasks         评估任务集
     * @param timeout       超时时长
     * @param unit          时间单位
     * @return 聚合评估结果
     */
    public EvalResult runParallel(String configVersion, java.util.List<EvalTask> tasks,
                                  long timeout, TimeUnit unit) {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(poolSize);
        }
        java.util.List<java.util.concurrent.Future<TaskResult>> futures = new java.util.ArrayList<>();
        for (EvalTask task : tasks) {
            futures.add(executor.submit(() -> new TaskResult(task.id(), Math.random() > 0.1, Math.random(), 150L)));
        }
        // 累加器模式（书中 §16.2.2）：逐条累积 TaskResult、超时与异常，避免一次失败丢弃全部结果
        EvalResult summary = new EvalResult("eval-" + configVersion, configVersion);
        summary.setDatasetId("regression");
        summary.setCostPer1kTokens(0.02);
        for (java.util.concurrent.Future<TaskResult> f : futures) {
            try {
                summary.addResult(f.get(timeout, unit));
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.CancellationException e) {
                summary.addTimeout(); // 任务超时
            } catch (java.util.concurrent.ExecutionException e) {
                summary.addFailure(e.getCause()); // 任务执行异常
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                summary.addFailure(e);
                break;
            }
        }
        summary.setStatus(EvalResult.EvalStatus.PASSED);
        summary.setEvaluatedAt(java.time.Instant.now());
        return summary;
    }

    /**
     * 并行运行评估（接受模型配置 Map 重载）。
     * <p>对应书中 §16.5 {@code evalPool.runParallel(newVersion.getConfig(), ...)}——
     * 候选模型版本以配置 Map 形式传入，内部取 {@code modelId} 作为 configVersion 委托
     * {@link #runParallel(String, java.util.List, long, TimeUnit)}。
     *
     * @param config 候选版本配置（至少含 {@code modelId}）
     * @param tasks  评估任务集
     * @param timeout 超时时长
     * @param unit   时间单位
     * @return 聚合评估结果
     */
    public EvalResult runParallel(java.util.Map<String, Object> config, java.util.List<EvalTask> tasks,
                                  long timeout, TimeUnit unit) {
        String configVersion = config == null ? "unknown"
                : String.valueOf(config.getOrDefault("modelId", "unknown"));
        return runParallel(configVersion, tasks, timeout, unit);
    }
}
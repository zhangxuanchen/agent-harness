package io.etclovg.codepilot.mlops;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 评估结果：可变累加器，累积并行评估任务的执行结果。
 * <p>对应书中 Ch16 §16.2.2 —— {@code EvalWorkerPool.runParallel} 的汇总容器。
 *
 * <p>设计为<b>可变累加器</b>（非不可变 record）：并行评估中逐条累积
 * {@link TaskResult}、超时与失败计数，最终由调用方读取派生指标
 * （{@link #accuracy()} / {@link #latencyP95Ms()} / {@link #failedCases()}），
 * 或由 {@link EvalGate} 对比基线后写入相对退化度（{@link #degradation()}）。
 *
 * <p>兼容两条调用路径，解决书内对 {@code EvalResult} 的两种用法不一致（P2）：
 * <ul>
 *   <li><b>累加路径</b>：{@code new EvalResult()} → {@link #addResult} /
 *       {@link #addTimeout} / {@link #addFailure} → {@link #degradation()}
 *       （书中 §16.2.2 EvalWorkerPool.runParallel）</li>
 *   <li><b>对比路径</b>：{@link #accuracy()} / {@link #latencyP95Ms()} /
 *       {@link #failedCases()} 供 {@link EvalResultComparator} 与 {@link EvalGate}
 *       做三指标退化对比（成功率/错误率/延迟）</li>
 * </ul>
 *
 * <p>退化度 {@link #degradation()} 默认 0，由 {@link EvalGate} 在对比基线后通过
 * {@link #setDegradation(double)} 写入；{@code ModelUpdateManager} 据此判定
 * 回归是否超过阈值（书中 §16.2 {@code evalResult.degradation() > 0.03}）。
 */
public class EvalResult {

    /** 评估状态。 */
    public enum EvalStatus {
        PENDING, RUNNING, PASSED, FAILED, REVIEW
    }

    private final List<TaskResult> results = new ArrayList<>();
    private int timeouts;
    private int failures;
    private double degradation;
    private String evalId;
    private String version;
    private String datasetId;
    private double costPer1kTokens;
    private EvalStatus status = EvalStatus.PENDING;
    private Instant evaluatedAt;

    /** 无参构造：累加器模式入口（书中 §16.2.2）。 */
    public EvalResult() {
    }

    /** 便捷构造：指定 evalId / version，后续通过累加器方法填充。 */
    public EvalResult(String evalId, String version) {
        this.evalId = evalId;
        this.version = version;
    }

    // ==================== 累加器 API（书中 §16.2.2 EvalWorkerPool.runParallel） ====================

    /** 累加一条任务结果。 */
    public void addResult(TaskResult result) {
        results.add(result);
    }

    /** 累计一次任务超时。 */
    public void addTimeout() {
        timeouts++;
    }

    /** 累计一次任务失败（异常）。 */
    public void addFailure(Throwable cause) {
        failures++;
    }

    // ==================== 退化度（由 EvalGate 对比基线后写入） ====================

    /** 相对基线的退化度（正值表示退化，如 0.03 表示 3 个百分点）。 */
    public double degradation() {
        return degradation;
    }

    /** 由 {@link EvalGate} 在三指标对比后写入退化度。 */
    public void setDegradation(double degradation) {
        this.degradation = degradation;
    }

    // ==================== 派生指标（供 EvalResultComparator / EvalGate 三指标对比） ====================

    /** 成功率 = 通过任务数 / 总任务数。 */
    public double accuracy() {
        if (results.isEmpty()) {
            return 0.0;
        }
        long passed = results.stream().filter(TaskResult::passed).count();
        return (double) passed / results.size();
    }

    /** P95 延迟（毫秒），按任务延迟升序取第 95 百分位。 */
    public double latencyP95Ms() {
        if (results.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = results.stream().map(TaskResult::latencyMs).sorted().toList();
        int idx = (int) Math.ceil(sorted.size() * 0.95) - 1;
        idx = Math.max(0, idx);
        return sorted.get(idx);
    }

    /** 失败用例 ID 列表（供 {@link EvalResultComparator#compareErrorRate} 统计错误率上升）。 */
    public List<String> failedCases() {
        return results.stream().filter(r -> !r.passed()).map(TaskResult::taskId).toList();
    }

    /** 超时任务数。 */
    public int timeouts() {
        return timeouts;
    }

    /** 异常失败任务数。 */
    public int failures() {
        return failures;
    }

    /** 累积的任务总数。 */
    public int totalTasks() {
        return results.size();
    }

    // ==================== 元信息 ====================

    public String evalId() { return evalId; }
    public void setEvalId(String evalId) { this.evalId = evalId; }
    public String version() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String datasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public double costPer1kTokens() { return costPer1kTokens; }
    public void setCostPer1kTokens(double costPer1kTokens) { this.costPer1kTokens = costPer1kTokens; }
    public EvalStatus status() { return status; }
    public void setStatus(EvalStatus status) { this.status = status; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Instant evaluatedAt) { this.evaluatedAt = evaluatedAt; }

    /** 是否通过：成功率与 P95 延迟同时满足阈值。 */
    public boolean isPassing(double accuracyThreshold, double latencyThresholdMs) {
        return accuracy() >= accuracyThreshold && latencyP95Ms() <= latencyThresholdMs;
    }

    /** 成功率是否达标。 */
    public boolean isAccurate(double threshold) {
        return accuracy() >= threshold;
    }

    /** P95 延迟是否达标。 */
    public boolean isFast(double thresholdMs) {
        return latencyP95Ms() <= thresholdMs;
    }
}

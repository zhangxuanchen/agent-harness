package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 评估门禁。对应书中 Ch16 §16.1.1。
 * <p>并行运行回归任务集，对比基线/候选版本的三指标退化（成功率/错误率/延迟），
 * 任一指标退化 &gt; 10% 返回硬阻断。返回顶层 {@link GateResult}（四态）。
 */
@Component
public class EvalGate {

    private static final Logger log = LoggerFactory.getLogger(EvalGate.class);

    private final EvalWorkerPool workerPool;
    private final EvalResultComparator comparator;

    public EvalGate(EvalWorkerPool workerPool, EvalResultComparator comparator) {
        this.workerPool = workerPool;
        this.comparator = comparator;
    }

    /**
     * 执行评估门禁检查。
     *
     * @param baseline  基线版本
     * @param candidate 候选版本
     * @return 门禁结果（PASS / HARD_FAIL）
     */
    public GateResult check(String baseline, String candidate) {
        List<EvalTask> tasks = EvalRegistry.loadRegressionSuite(100);

        EvalResult baselineResult = workerPool.runParallel(baseline, tasks, 30, java.util.concurrent.TimeUnit.MINUTES);
        EvalResult candidateResult = workerPool.runParallel(candidate, tasks, 30, java.util.concurrent.TimeUnit.MINUTES);

        double successDeg = comparator.compareSuccessRate(baselineResult, candidateResult);
        double errorInc = comparator.compareErrorRate(baselineResult, candidateResult);
        double latencyDeg = comparator.compareLatency(baselineResult, candidateResult);

        if (successDeg > 0.10 || errorInc > 0.10 || latencyDeg > 0.10) {
            return GateResult.fail("Eval",
                    String.format("successDeg=%.1f%%, errorInc=%.1f%%, latencyDeg=%.1f%%",
                            successDeg * 100, errorInc * 100, latencyDeg * 100));
        }
        return GateResult.pass("Eval", 1.0 - successDeg);
    }
}

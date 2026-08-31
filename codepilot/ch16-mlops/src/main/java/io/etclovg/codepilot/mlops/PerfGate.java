package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 性能门禁。
 * <p>对应书中 Ch16 §16.3 —— 发布前的性能指标门禁。
 * <p>检查候选版本的延迟、吞吐与资源占用是否满足阈值，
 * 不满足则阻断发布。
 */
@Component
public class PerfGate {

    private static final Logger log = LoggerFactory.getLogger(PerfGate.class);

    /**
     * 执行性能门禁检查。
     *
     * @param latencyP95Ms P95 延迟
     * @param maxLatencyMs 最大允许延迟
     * @return 门禁结果
     */
    public GateResult check(double latencyP95Ms, double maxLatencyMs) {
        if (latencyP95Ms <= maxLatencyMs) {
            log.info("性能门禁通过: latency={}, max={}", latencyP95Ms, maxLatencyMs);
            return GateResult.pass("Perf", 1.0 - latencyP95Ms / maxLatencyMs);
        }
        log.warn("性能门禁失败: latency={}, max={}", latencyP95Ms, maxLatencyMs);
        return GateResult.fail("Perf", "P95 延迟超限: " + latencyP95Ms + " > " + maxLatencyMs);
    }

    /**
     * 性能门禁（版本对比重载）：从 OTel Span 提取候选版本 P95 延迟与 token 成本，
     * 与基线版本对比，P95 退化 &gt; 2× 或成本 &gt; 1.5× 则硬阻断。
     * <p>对应书中 §16.1.1 {@code GatePipelineOrchestrator} 调用签名
     * {@code perfGate.check(baselineVersion, candidateVersion)}。
     *
     * @param baseline  基线版本
     * @param candidate 候选版本
     * @return 门禁结果（PASS / HARD_FAIL）
     */
    public GateResult check(String baseline, String candidate) {
        log.info("性能门禁（版本对比）: baseline={}, candidate={}", baseline, candidate);
        // 教学桩：生产实现从 OTel Span 查询 P95 延迟与 token 成本
        double baselineP95 = 150.0;   // 桩：基线 P95 延迟
        double candidateP95 = 180.0;  // 桩：候选 P95 延迟
        double baselineCost = 0.02;   // 桩：基线 token 成本
        double candidateCost = 0.025; // 桩：候选 token 成本

        double latencyDeg = baselineP95 == 0 ? 0 : (candidateP95 - baselineP95) / baselineP95;
        double costRatio = baselineCost == 0 ? 1.0 : candidateCost / baselineCost;

        // 门禁阈值：P95 延迟退化 > 10%（远未达 2× 容忍上限）或成本 > 1.5× 即阻断
        if (latencyDeg > 0.10) {
            return GateResult.fail("Perf",
                    String.format("P95 延迟退化 %.1f%%（%.0f→%.0fms）", latencyDeg * 100, baselineP95, candidateP95));
        }
        if (costRatio > 1.5) {
            return GateResult.fail("Perf",
                    String.format("token 成本超限 %.2f×（%.4f→%.4f）", costRatio, baselineCost, candidateCost));
        }
        return GateResult.pass("Perf", 1.0 - latencyDeg);
    }
}

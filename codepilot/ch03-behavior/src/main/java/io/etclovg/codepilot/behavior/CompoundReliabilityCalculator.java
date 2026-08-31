package io.etclovg.codepilot.behavior;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 组合可靠性计算器。
 * <p>对应书中 Ch03 §3.2 —— 计算多组件组合的整体可靠性，量化"换模型 vs 补工程"的 ROI。
 * <p>核心公式：复合可靠性 = p^n（n 步串联系统的端到端成功率）。
 */
@Component
public class CompoundReliabilityCalculator {

    /** 复合可靠性：p^n */
    public static double compoundReliability(double stepReliability, int steps) {
        return Math.pow(stepReliability, steps);
    }

    /** Harness 多层加固：每层提升 gainPerLayer 的效果 */
    public static double harnessReinforcement(double baseReliability, int layers, double gainPerLayer, int steps) {
        double reinforced = Math.min(1.0, baseReliability + layers * gainPerLayer);
        return Math.pow(reinforced, steps);
    }

    /** 模型升级效果：单步提升 upgradeP 后的端到端变化 */
    public static double modelUpgradeEffect(double baseP, double upgradeP, int steps) {
        return Math.pow(baseP + upgradeP, steps) - Math.pow(baseP, steps);
    }

    /** 成本失控预警：每步 token 增长的隐形乘数 */
    public record CostEscalationRecord(
        int step, long cumulativeTokens, double costUSD, String warningLevel
    ) {}

    public static List<CostEscalationRecord> simulateCostEscalation(
            long initialTokens, double growthRatePerStep, double tokenPricePerMToken, int maxSteps) {
        List<CostEscalationRecord> records = new ArrayList<>();
        long cumulative = 0;
        double cost = 0;
        for (int step = 1; step <= maxSteps; step++) {
            long stepTokens = (long) (initialTokens * Math.pow(growthRatePerStep, step - 1));
            cumulative += stepTokens;
            cost = (cumulative / 1_000_000.0) * tokenPricePerMToken;
            String warning = cost > 1000 ? "CRITICAL" : cost > 100 ? "HIGH" : "NORMAL";
            records.add(new CostEscalationRecord(step, cumulative, cost, warning));
        }
        return records;
    }

    // ---- 以下为原有 API（保留以兼容已有测试） ----

    /** 计算串联系统可靠性。 */
    public double seriesReliability(double... componentReliabilities) {
        double result = 1.0;
        for (double r : componentReliabilities) {
            result *= Math.max(0, Math.min(1, r));
        }
        return result;
    }

    /** 计算并联系统可靠性。 */
    public double parallelReliability(double... componentReliabilities) {
        double failureProb = 1.0;
        for (double r : componentReliabilities) {
            failureProb *= (1.0 - Math.max(0, Math.min(1, r)));
        }
        return 1.0 - failureProb;
    }

    /** 计算混合系统可靠性（串并联组合）。 */
    public double mixedReliability(double[] seriesComponents, double[] parallelComponents) {
        double seriesR = seriesReliability(seriesComponents);
        double parallelR = parallelReliability(parallelComponents);
        return seriesR * parallelR;
    }
}

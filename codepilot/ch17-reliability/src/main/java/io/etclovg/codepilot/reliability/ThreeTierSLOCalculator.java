package io.etclovg.codepilot.reliability;

import org.springframework.stereotype.Component;

/**
 * 三层 SLO 计算器。
 * <p>对应书中 Ch17 §17.2 —— 计算三层 SLO（服务水平目标）。
 */
@Component
public class ThreeTierSLOCalculator {

    /**
     * SLO 层级。
     */
    public enum SLOTier {
        L1_AVAILABILITY,    // 可用性 SLO
        L2_PERFORMANCE,     // 性能 SLO
        L3_EFFICIENCY       // 效率 SLO
    }

    /**
     * SLO 计算结果。
     */
    public record SLOResult(
            SLOTier tier,
            double target,
            double actual,
            boolean met,
            double errorBudget
    ) {}

    /**
     * 计算指定层级的 SLO。
     */
    public SLOResult calculate(SLOTier tier, double target, double actual) {
        boolean met = actual >= target;
        double errorBudget = met ? (actual - target) : 0;
        return new SLOResult(tier, target, actual, met, errorBudget);
    }
}
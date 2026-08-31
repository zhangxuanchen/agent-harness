package io.etclovg.codepilot.future;

import org.springframework.stereotype.Component;

/**
 * Harness 投资转移分析。
 * <p>对应书中 Ch19 §19.2 —— 分析从模型开发到 Harness 工程的投资重心转移。
 */
@Component
public class HarnessInvestmentShift {

    /**
     * 投资分配。
     */
    public record InvestmentAllocation(
            double modelDevPercent,
            double harnessEngPercent,
            double dataEngPercent,
            double opsPercent
    ) {}

    /**
     * 分析投资转移趋势。
     */
    public InvestmentAllocation analyzeShift() {
        return new InvestmentAllocation(0.3, 0.35, 0.2, 0.15);
    }
}
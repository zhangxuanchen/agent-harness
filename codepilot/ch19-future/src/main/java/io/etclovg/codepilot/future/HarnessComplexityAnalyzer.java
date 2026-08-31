package io.etclovg.codepilot.future;

import org.springframework.stereotype.Component;

/**
 * Harness 复杂度分析器。
 * <p>对应书中 Ch19 §19.1 —— 分析 Harness 架构的复杂度。
 */
@Component
public class HarnessComplexityAnalyzer {

    /**
     * 复杂度维度。
     */
    public record ComplexityMetrics(
            int layerCount,
            int advisorCount,
            int middlewareCount,
            double cyclomaticComplexity,
            double cognitiveComplexity
    ) {}

    /**
     * 分析 Harness 复杂度。
     */
    public ComplexityMetrics analyze(int layers, int advisors, int middlewares) {
        return new ComplexityMetrics(layers, advisors, middlewares, 0.0, 0.0);
    }
}
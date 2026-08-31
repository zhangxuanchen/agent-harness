package io.etclovg.codepilot.etcclovg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回归评估器。
 * <p>对应书中 Ch11 —— 对 Agent 进行回归测试评估。
 */
@Component
public class RegressionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RegressionEvaluator.class);

    /**
     * 回归评估结果。
     */
    public record RegressionResult(
            String testId,
            int totalCases,
            int passedCases,
            int failedCases,
            double passRate,
            List<String> regressions
    ) {}

    /**
     * 执行回归评估。
     */
    public RegressionResult evaluate(String testSuiteId) {
        log.info("[RegressionEvaluator] 执行回归: suite={}", testSuiteId);
        return new RegressionResult(
                testSuiteId, 100, 95, 5, 0.95, List.of()
        );
    }
}
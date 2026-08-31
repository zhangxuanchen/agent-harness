package io.etclovg.codepilot.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 任务成本估算器。
 * <p>对应书中 Ch14 §14.2 —— 规划阶段的成本预估。
 * <p>在执行计划前根据预计步数、工具调用次数与模型层级估算 Token 成本，
 * 供 {@link ReasoningRouter} 与预算守卫决策是否放行。
 */
@Component
public class CostEstimator {

    private static final Logger log = LoggerFactory.getLogger(CostEstimator.class);

    private static final double COST_PER_STEP = 0.02;

    /**
     * 估算总成本。
     *
     * @param estimatedSteps 预计步数
     * @param toolCalls      预计工具调用次数
     * @return 估算成本（美元）
     */
    public double estimate(int estimatedSteps, int toolCalls) {
        double cost = estimatedSteps * COST_PER_STEP + toolCalls * 0.005;
        log.debug("估算成本: steps={}, toolCalls={}, cost=${}", estimatedSteps, toolCalls, cost);
        return cost;
    }

    /**
     * 估算预计耗时。
     *
     * @param estimatedSteps 预计步数
     * @return 预计耗时
     */
    public Duration estimateDuration(int estimatedSteps) {
        return Duration.ofSeconds(estimatedSteps * 3L);
    }
}

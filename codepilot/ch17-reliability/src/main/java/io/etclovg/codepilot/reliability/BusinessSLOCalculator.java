package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 业务 SLO 计算器。
 * <p>对应书中 Ch17 §17.4 —— 业务视角的 SLO 计算。
 * <p>基于业务指标（订单完成率、客户满意度等）计算业务 SLO 达成率，
 * 区别于技术与功能 SLO。
 */
@Component
public class BusinessSLOCalculator {

    private static final Logger log = LoggerFactory.getLogger(BusinessSLOCalculator.class);

    /**
     * 计算业务 SLO 达成率。
     *
     * @param metrics 业务指标快照
     * @param target  目标值
     * @return 达成率（0-1）
     */
    public double calculate(Map<String, Double> metrics, double target) {
        if (metrics == null || metrics.isEmpty() || target <= 0) {
            return 0.0;
        }
        double orderRate = metrics.getOrDefault("orderCompletionRate", 0.0);
        double achievement = orderRate / target;
        log.debug("业务 SLO 计算: orderRate={}, target={}, achievement={}", orderRate, target, achievement);
        return Math.min(1.0, achievement);
    }
}

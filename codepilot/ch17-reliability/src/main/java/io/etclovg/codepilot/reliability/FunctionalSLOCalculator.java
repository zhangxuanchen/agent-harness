package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 功能 SLO 计算器。
 * <p>对应书中 Ch17 §17.4 —— 功能视角的 SLO 计算。
 * <p>基于功能指标（工具调用成功率、任务完成率等）计算功能 SLO 达成率，
 * 介于技术 SLO 与业务 SLO 之间。
 */
@Component
public class FunctionalSLOCalculator {

    private static final Logger log = LoggerFactory.getLogger(FunctionalSLOCalculator.class);

    /**
     * 计算功能 SLO 达成率。
     *
     * @param metrics 功能指标快照
     * @param target  目标值
     * @return 达成率（0-1）
     */
    public double calculate(Map<String, Double> metrics, double target) {
        if (metrics == null || metrics.isEmpty() || target <= 0) {
            return 0.0;
        }
        double successRate = metrics.getOrDefault("toolCallSuccessRate", 0.0);
        double achievement = successRate / target;
        log.debug("功能 SLO 计算: successRate={}, target={}, achievement={}", successRate, target, achievement);
        return Math.min(1.0, achievement);
    }
}

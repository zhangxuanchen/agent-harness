package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 技术 SLO 计算器。
 * <p>对应书中 Ch17 §17.4 —— 技术视角的 SLO 计算。
 * <p>基于技术指标（可用性、延迟、错误率）计算技术 SLO 达成率，
 * 是最底层的 SLO 视角。
 */
@Component
public class TechnicalSLOCalculator {

    private static final Logger log = LoggerFactory.getLogger(TechnicalSLOCalculator.class);

    /**
     * 计算技术 SLO 达成率。
     *
     * @param metrics 技术指标快照
     * @param target  目标值
     * @return 达成率（0-1）
     */
    public double calculate(Map<String, Double> metrics, double target) {
        if (metrics == null || metrics.isEmpty() || target <= 0) {
            return 0.0;
        }
        double availability = metrics.getOrDefault("availability", 0.0);
        double achievement = availability / target;
        log.debug("技术 SLO 计算: availability={}, target={}, achievement={}", availability, target, achievement);
        return Math.min(1.0, achievement);
    }
}

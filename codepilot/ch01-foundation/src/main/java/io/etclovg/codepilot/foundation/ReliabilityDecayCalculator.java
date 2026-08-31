package io.etclovg.codepilot.foundation;

import org.springframework.stereotype.Component;

/**
 * 可靠性衰减计算器。
 * <p>对应书中 Ch01 §1.3 —— Agent 可靠性随步骤数衰减的数学模型。
 * <p>模拟故障率随时间/步骤增长的曲线，用于预测 Agent 在长会话中的可靠性。
 */
@Component
public class ReliabilityDecayCalculator {

    /**
     * 衰减模型类型。
     */
    public enum DecayModel {
        EXPONENTIAL,
        POWER_LAW,
        LOGARITHMIC,
        PIECEWISE
    }

    /**
     * 计算在给定步骤数下的可靠性。
     *
     * @param baseReliability 基础可靠性 (0-1)
     * @param steps           当前步骤数
     * @param model           衰减模型
     * @param params          模型参数
     * @return 预测可靠性 (0-1)
     */
    public double calculateReliability(double baseReliability, int steps,
                                       DecayModel model, double... params) {
        return switch (model) {
            case EXPONENTIAL -> baseReliability * Math.exp(-params[0] * steps);
            case POWER_LAW -> baseReliability / Math.pow(steps + 1, params[0]);
            case LOGARITHMIC -> baseReliability - params[0] * Math.log(steps + 1);
            case PIECEWISE -> calculatePiecewise(baseReliability, steps, params);
        };
    }

    private double calculatePiecewise(double base, int steps, double[] params) {
        double threshold = params.length > 0 ? params[0] : 10.0;
        double rate1 = params.length > 1 ? params[1] : 0.01;
        double rate2 = params.length > 2 ? params[2] : 0.05;
        if (steps <= threshold) {
            return base * Math.exp(-rate1 * steps);
        } else {
            double atThreshold = base * Math.exp(-rate1 * threshold);
            return atThreshold * Math.exp(-rate2 * (steps - threshold));
        }
    }
}
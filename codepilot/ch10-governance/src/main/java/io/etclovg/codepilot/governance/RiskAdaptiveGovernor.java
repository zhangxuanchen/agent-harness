package io.etclovg.codepilot.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 风险自适应治理器。
 * <p>对应书中 Ch10 §10.5 —— 根据实时风险等级动态调整治理策略。
 * <p>根据输入/输出/工具调用的风险评分，动态升降审批阈值、
 * 启用更严格的守卫或触发人工介入，实现治理强度与风险相匹配。
 */
@Component
public class RiskAdaptiveGovernor {

    private static final Logger log = LoggerFactory.getLogger(RiskAdaptiveGovernor.class);

    private volatile RiskTier currentTier = RiskTier.NORMAL;

    /**
     * 根据风险评分更新当前治理等级。
     *
     * @param riskScore 风险评分（0-1）
     * @return 调整后的治理等级
     */
    public RiskTier adjust(double riskScore) {
        RiskTier newTier = RiskTier.fromScore(riskScore);
        if (newTier != currentTier) {
            log.info("治理等级切换: {} -> {} (riskScore={})", currentTier, newTier, riskScore);
            currentTier = newTier;
        }
        return currentTier;
    }

    /**
     * 获取当前治理等级。
     *
     * @return 当前等级
     */
    public RiskTier currentTier() {
        return currentTier;
    }

    /**
     * 根据当前等级返回治理策略参数。
     *
     * @return 策略参数
     */
    public Map<String, Object> currentPolicy() {
        return Map.of(
                "tier", currentTier.name(),
                "approvalRequired", currentTier.approvalRequired,
                "maxStepsPerTurn", currentTier.maxStepsPerTurn,
                "auditLevel", currentTier.auditLevel
        );
    }

    /**
     * 风险治理等级。
     */
    public enum RiskTier {
        /** 常规：低风险，常规审计 */
        NORMAL("常规", false, 20, "basic"),
        /** 提升：中风险，加强审计 */
        ELEVATED("提升", false, 10, "enhanced"),
        /** 高危：高风险，需审批 */
        HIGH("高危", true, 5, "strict"),
        /** 紧急：严重风险，立即冻结 */
        CRITICAL("紧急", true, 1, "lockdown");

        private final String label;
        private final boolean approvalRequired;
        private final int maxStepsPerTurn;
        private final String auditLevel;

        RiskTier(String label, boolean approvalRequired, int maxStepsPerTurn, String auditLevel) {
            this.label = label;
            this.approvalRequired = approvalRequired;
            this.maxStepsPerTurn = maxStepsPerTurn;
            this.auditLevel = auditLevel;
        }

        /**
         * 根据风险评分推断等级。
         *
         * @param score 风险评分（0-1）
         * @return 治理等级
         */
        public static RiskTier fromScore(double score) {
            if (score >= 0.9) return CRITICAL;
            if (score >= 0.7) return HIGH;
            if (score >= 0.4) return ELEVATED;
            return NORMAL;
        }

        public String getLabel() {
            return label;
        }
    }
}

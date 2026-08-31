package io.etclovg.codepilot.future;

import java.time.Instant;
import java.util.List;

/**
 * 自我改进提案记录。
 * <p>对应书中 Ch19 §19.5 —— Agent 自我改进的提案结构。
 * <p>描述一项改进建议的目标、变更内容、预期收益与风险，
 * 供自我改进闭环评估、应用或拒绝。
 *
 * @param proposalId    提案 ID
 * @param target        改进目标
 * @param changes       变更内容列表
 * @param expectedGain  预期收益
 * @param riskLevel     风险等级
 * @param status        提案状态
 * @param proposedAt    提出时间
 */
public record SelfImprovementProposal(
        String proposalId,
        String target,
        List<String> changes,
        double expectedGain,
        String riskLevel,
        Status status,
        Instant proposedAt
) {

    /**
     * 构造带默认字段的提案。
     *
     * @param target  目标
     * @param changes 变更
     * @return 提案
     */
    public static SelfImprovementProposal of(String target, List<String> changes) {
        return new SelfImprovementProposal("prop-" + System.currentTimeMillis(),
                target, changes, 0.0, "LOW", Status.PENDING, Instant.now());
    }

    /**
     * 是否值得应用。
     *
     * @return 预期收益为正且风险不高返回 true
     */
    public boolean isWorthApplying() {
        return expectedGain > 0 && !"CRITICAL".equalsIgnoreCase(riskLevel);
    }

    /**
     * 提案状态。
     */
    public enum Status {
        /** 待评估 */
        PENDING,
        /** 已批准 */
        APPROVED,
        /** 已应用 */
        APPLIED,
        /** 已拒绝 */
        REJECTED,
        /** 已回滚 */
        ROLLED_BACK
    }
}

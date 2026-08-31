package io.etclovg.codepilot.mlops;

import java.time.Instant;

/**
 * 门禁检查结果记录。对应书中 Ch16 §16.1.1。
 * <p>统一描述一次门禁检查（评估/性能/策略/人工）的判定状态、得分与原因，
 * 供 {@code GatePipelineOrchestrator} 串联各门禁决策。
 *
 * <p>四态判定（{@link GateStatus}）取代二元 boolean，以表达 Agent 门禁的
 * "硬阻断 / 告警不阻断 / 自动放行" 三种非通过语义——这是概率性验证流水线
 * 区别于确定性编译流水线的核心。
 *
 * @param gateName   门禁名称
 * @param passed     是否通过（CONDITIONAL_FAIL/PASS_AUTO 视语义而定）
 * @param score      得分（0-1）
 * @param message    结果描述
 * @param checkedAt  检查时间
 * @param status     四态判定
 */
public record GateResult(
        String gateName,
        boolean passed,
        double score,
        String message,
        Instant checkedAt,
        GateStatus status
) {

    /** 通过（保留旧签名，向后兼容 PerfGate/PolicyGate）。 */
    public static GateResult pass(String gateName, double score) {
        return new GateResult(gateName, true, score, "门禁通过", Instant.now(), GateStatus.PASS);
    }

    /** 硬阻断（保留旧签名，向后兼容）。 */
    public static GateResult fail(String gateName, String message) {
        return new GateResult(gateName, false, 0.0, message, Instant.now(), GateStatus.HARD_FAIL);
    }

    /** 告警但不阻断：指标临界，记录但允许流水线继续。 */
    public static GateResult conditionalFail(String gateName, String message) {
        return new GateResult(gateName, false, 0.0, message, Instant.now(), GateStatus.CONDITIONAL_FAIL);
    }

    /** 低风险自动放行（无需人工审批）。 */
    public static GateResult autoPass(String gateName) {
        return new GateResult(gateName, true, 1.0, "低风险自动放行", Instant.now(), GateStatus.PASS_AUTO);
    }

    /** 是否硬阻断——只有 HARD_FAIL 阻断流水线；CONDITIONAL_FAIL 仅告警。 */
    public boolean isFailed() {
        return status == GateStatus.HARD_FAIL;
    }

    /** 追加细节，返回新实例（record 不可变）。 */
    public GateResult withDetails(String details) {
        return new GateResult(gateName, passed, score, message + " | " + details, checkedAt, status);
    }
}

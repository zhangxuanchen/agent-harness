package io.etclovg.codepilot.future;

import java.time.Instant;
import java.util.Map;

/**
 * 审计轨迹记录。
 * <p>对应书中 Ch19 §19.4 —— 自主 Agent 的可审计轨迹。
 * <p>记录关键决策的发起者、动作、上下文与结果，支撑自主系统的可追溯性
 * 与合规审计。
 *
 * @param traceId      轨迹 ID
 * @param actor        发起者
 * @param action       动作
 * @param context      决策上下文
 * @param outcome      结果
 * @param timestamp    时间戳
 */
public record AuditTrail(
        String traceId,
        String actor,
        String action,
        Map<String, Object> context,
        String outcome,
        Instant timestamp
) {

    /**
     * 构造带默认字段的审计轨迹。
     *
     * @param actor  发起者
     * @param action 动作
     * @return 审计轨迹
     */
    public static AuditTrail of(String actor, String action) {
        return new AuditTrail("trace-" + System.currentTimeMillis(), actor, action, Map.of(), "ok", Instant.now());
    }
}

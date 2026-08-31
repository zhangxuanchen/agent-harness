package io.etclovg.codepilot.mlops;

/**
 * 门禁判定状态。对应书中 Ch16 §16.1.1。
 * <p>四态而非二元 boolean，是 Agent 概率性门禁与确定性编译门禁的关键差异：
 * <ul>
 *   <li>{@link #PASS} —— 通过</li>
 *   <li>{@link #CONDITIONAL_FAIL} —— 告警但不阻断（指标临界，需人工知晓但可继续）</li>
 *   <li>{@link #HARD_FAIL} —— 硬阻断（退化超标，立即短路）</li>
 *   <li>{@link #PASS_AUTO} —— 低风险自动放行（无需人工审批）</li>
 * </ul>
 */
public enum GateStatus {
    PASS, CONDITIONAL_FAIL, HARD_FAIL, PASS_AUTO
}

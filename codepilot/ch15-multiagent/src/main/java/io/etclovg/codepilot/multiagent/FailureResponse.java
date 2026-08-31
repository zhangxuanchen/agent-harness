package io.etclovg.codepilot.multiagent;

/**
 * 失败响应：预防触发器 + 恢复动作 + 证据来源。对应书中 Ch15 §15.5.2 · 概念示例。
 * <p>{@code evidence} 区分两类依据：
 * <ul>
 *   <li>{@link Evidence#PAPER} —— MAST 论文已证明的预防/干预手段（有学术背书）</li>
 *   <li>{@link Evidence#ENGINEERING} —— 工程补充（论文未覆盖，多为恢复策略），
 *       应在自有执行记录上验证后再信赖</li>
 * </ul>
 */
public record FailureResponse(String prevention, String recovery, Evidence evidence) {

    public enum Evidence { PAPER, ENGINEERING }
}

package io.etclovg.codepilot.mlops;

/**
 * 行动建议项。对应书中 Ch16 §16.6.1。
 * <p>差距分析转化为的具体下一步行动，含动作、优先级与说明。
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 *
 * @param action   行动描述
 * @param priority 优先级
 * @param detail   说明
 */
public record ActionItem(String action, String priority, String detail) {
}

package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.4：带 HintBlock 标签的定向消息——
 * target 标签声明接收方角色，消息总线据此路由而非广播。
 */
public record HintMessage(String targetRole, Object payload) {
}

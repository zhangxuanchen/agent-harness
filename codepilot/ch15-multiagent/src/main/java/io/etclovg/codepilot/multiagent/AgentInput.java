package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.2：Agent 推理的输入。
 */
public record AgentInput(String taskId, String prompt) {
}

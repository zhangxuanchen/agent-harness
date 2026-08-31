package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.5：共享记忆的端到端权限校验器（记忆层，
 * 与 §15.3.2 的 ToolPermissionGate 工具权限闸门职责不同）。
 * <p>写入时校验 Agent 是否有权写该作用域；检索时按 caller 作用域过滤。
 */
public interface PermissionChecker {
    boolean canWrite(String writerAgentId, MemoryScope scope);
    boolean canRead(MemoryScope callerScope, MemoryScope recordScope);
}

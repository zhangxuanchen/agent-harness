package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.5：待写入的记忆条目——
 * 携带作用域标签、写入者、来源上下文。
 */
public interface MemoryEntry {
    MemoryScope scope();
    String writerAgentId();
    Object sourceContext();
}

package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.2：Reviewer 的只读工具集（Reviewer 无写权限）。
 */
public interface ToolRegistry {
    /** 工具集是否为只读（Reviewer/Explorer 为 true，Developer 为 false） */
    boolean isReadOnly();
}

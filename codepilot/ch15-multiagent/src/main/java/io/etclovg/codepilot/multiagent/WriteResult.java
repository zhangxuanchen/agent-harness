package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.5：记忆写入结果。
 */
public record WriteResult(boolean accepted, String id, String reason) {
    public static WriteResult ok(String id) {
        return new WriteResult(true, id, null);
    }
    public static WriteResult rejected(String reason) {
        return new WriteResult(false, null, reason);
    }
}

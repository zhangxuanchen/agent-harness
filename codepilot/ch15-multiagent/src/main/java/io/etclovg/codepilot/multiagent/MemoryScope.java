package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.5：记忆作用域标签——
 * 强制 user_id / agent_id / session_id / app_id 四元组齐全（多租户隔离在记忆层的落地）。
 */
public record MemoryScope(String userId, String agentId, String sessionId, String appId) {
    /** 四元组是否齐全，缺一即拒绝写入 */
    public boolean isComplete() {
        return userId != null && !userId.isBlank()
            && agentId != null && !agentId.isBlank()
            && sessionId != null && !sessionId.isBlank()
            && appId != null && !appId.isBlank();
    }
}

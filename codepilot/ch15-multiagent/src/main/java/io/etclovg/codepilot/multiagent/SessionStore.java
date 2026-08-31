package io.etclovg.codepilot.multiagent;

import java.util.Optional;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.2：Session 持久化后端（Redis / DB）。
 */
public interface SessionStore {
    Optional<AgentSession> findById(String sessionId);
    AgentSession save(AgentSession session);
}

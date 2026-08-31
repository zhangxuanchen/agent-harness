package io.etclovg.codepilot.multiagent;

import org.springframework.stereotype.Component;

/**
 * Session 管理器——按 sessionId 获取或创建 Session。对应书中 Ch15 §15.2 · 概念示例。
 * <p>AgentSession 通过 SessionManager 按 sessionId 获取/创建。
 */
@Component
public class SessionManager {
    private final SessionStore store;  // Redis / DB 持久化

    public SessionManager(SessionStore store) {
        this.store = store;
    }

    public AgentSession getOrCreate(String sessionId, String userId) {
        return store.findById(sessionId)
            .orElseGet(() -> {
                AgentSession session = new AgentSession(sessionId, userId);
                return store.save(session);
            });
    }
}

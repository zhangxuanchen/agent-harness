package io.etclovg.codepilot.multiagent;

import java.util.List;
import java.util.Map;

/**
 * 状态容器——每个用户/会话一个实例。对应书中 Ch15 §15.2 · 概念示例。
 * <p>持历史 / 压缩 / memory，状态独立隔离。
 */
public class AgentSession {
    private final String sessionId;         // 用户级唯一 ID
    private final String userId;
    private List<Message> history;          // 会话历史
    private String compressedContext;       // 压缩后的上下文
    private Map<String, Object> memory;     // 长期记忆引用

    public AgentSession(String sessionId, String userId) {
        this.sessionId = sessionId;
        this.userId = userId;
    }

    public List<Message> getHistory() { return history; }
    public String getCompressedContext() { return compressedContext; }

    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
}

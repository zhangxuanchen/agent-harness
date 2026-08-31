package io.etclovg.codepilot.definition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存聊天记忆。
 * <p>对应书中 Ch02 §2.3 —— 基于内存的会话历史存储实现。
 */
@Component
public class InMemoryChatMemory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryChatMemory.class);

    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();
    private int maxMessagesPerSession = 100;

    /**
     * 聊天消息记录。
     */
    public record ChatMessage(
            String role,
            String content,
            long timestamp
    ) {}

    /**
     * 添加一条消息到会话。
     */
    public void addMessage(String sessionId, String role, String content) {
        List<ChatMessage> history = sessions.computeIfAbsent(sessionId, k ->
                Collections.synchronizedList(new ArrayList<>()));
        history.add(new ChatMessage(role, content, System.currentTimeMillis()));

        if (history.size() > maxMessagesPerSession) {
            history.subList(0, history.size() - maxMessagesPerSession).clear();
        }

        log.debug("[ChatMemory] 添加消息: sessionId={}, role={}, total={}",
                sessionId, role, history.size());
    }

    /**
     * 获取会话历史。
     */
    public List<ChatMessage> getHistory(String sessionId) {
        return sessions.getOrDefault(sessionId, List.of());
    }

    /**
     * 清除会话。
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("[ChatMemory] 清除会话: sessionId={}", sessionId);
    }

    public void setMaxMessagesPerSession(int max) {
        this.maxMessagesPerSession = max;
    }
}
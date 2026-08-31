package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * 会话上下文表示。
 * <p>对应书中 Ch06 §6.2 —— 单次会话的上下文状态容器。
 * <p>维护会话标识、目标、历史消息、工作记忆条目与累计 Token 用量，
 * 作为记忆层与编排层之间传递上下文的标准载体。
 */
@Component
public class SessionContext {

    private static final Logger log = LoggerFactory.getLogger(SessionContext.class);

    private final String sessionId;
    private String goal;
    private final List<String> messages = new ArrayList<>();
    private final Map<String, Object> workingMemory = new LinkedHashMap<>();
    private final Instant createdAt;
    private volatile long totalTokens;

    public SessionContext() {
        this("session-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public SessionContext(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        log.debug("创建会话上下文: session={}", sessionId);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    /**
     * 追加一条消息。
     *
     * @param message 消息文本
     */
    public void appendMessage(String message) {
        messages.add(message);
    }

    /**
     * 获取消息历史的不可变视图。
     *
     * @return 消息列表
     */
    public List<String> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 写入工作记忆条目。
     *
     * @param key   键
     * @param value 值
     */
    public void putWorkingMemory(String key, Object value) {
        workingMemory.put(key, value);
    }

    /**
     * 读取工作记忆条目。
     *
     * @param key 键
     * @return 值
     */
    public Optional<Object> getWorkingMemory(String key) {
        return Optional.ofNullable(workingMemory.get(key));
    }

    /**
     * 获取工作记忆的不可变视图。
     *
     * @return 工作记忆 Map
     */
    public Map<String, Object> getWorkingMemory() {
        return Collections.unmodifiableMap(workingMemory);
    }

    /**
     * 累加 Token 用量。
     *
     * @param tokens Token 数
     */
    public void addTokens(long tokens) {
        this.totalTokens += tokens;
    }
}

package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 黑板模式。
 * <p>对应书中 Ch07 §7.4 —— 共享工作记忆区，用于多 Agent 间的数据交换。
 */
@Component
public class Blackboard {

    private final Map<String, Object> board = new ConcurrentHashMap<>();
    private final List<ChangeEvent> changeLog = Collections.synchronizedList(new ArrayList<>());

    /**
     * 变更事件。
     */
    public record ChangeEvent(
            String key,
            Object oldValue,
            Object newValue,
            long timestamp
    ) {}

    /**
     * 写入黑板。
     */
    public void write(String key, Object value) {
        Object old = board.put(key, value);
        changeLog.add(new ChangeEvent(key, old, value, System.currentTimeMillis()));
    }

    /**
     * 从黑板读取。
     */
    @SuppressWarnings("unchecked")
    public <T> T read(String key) {
        return (T) board.get(key);
    }

    /**
     * 检查是否存在。
     */
    public boolean contains(String key) {
        return board.containsKey(key);
    }

    /**
     * 获取所有键。
     */
    public Set<String> keys() {
        return Collections.unmodifiableSet(board.keySet());
    }

    /**
     * 获取变更日志。
     */
    public List<ChangeEvent> getChangeLog() {
        return Collections.unmodifiableList(changeLog);
    }

    /**
     * 清空黑板。
     */
    public void clear() {
        board.clear();
        changeLog.clear();
    }
}
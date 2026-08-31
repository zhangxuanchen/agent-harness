package io.etclovg.codepilot.behavior;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存中 Agent 状态仓库——{@link AgentStateManager} 的进程内实现。
 * <p>对应书中 Ch03 §3.4.1 —— L 层统一状态协议的默认存储后端。
 *
 * <p>用 {@code Map<(userId, sessionId), List<AgentStepState>>} 汇聚各层上报的单步状态快照，
 * key 结构对齐 AgentScope 2.0.0 AgentStateStore 的 (userId, sessionId) 命名。
 * 支持 {@link #recordState} 增量记录、{@link #getLatestState} 断点恢复查询、
 * {@link #getFullTrace} 完整轨迹审计。当前为进程内实现，生产环境可替换为
 * 基于 AgentScope AgentStateStore 的持久化后端，或对接 Flink/Spark 的 Checkpoint 机制。
 */
@Component
public class InMemoryAgentStateRepository implements AgentStateManager {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAgentStateRepository.class);

    /** 复合 key：(userId, sessionId)，对齐 AgentScope AgentStateStore 的存储key结构 */
    private record SessionKey(String userId, String sessionId) {
        static SessionKey of(String userId, String sessionId) {
            return new SessionKey(Objects.requireNonNullElse(userId, "anonymous"), sessionId);
        }
    }

    private final Map<SessionKey, List<AgentStepState>> store = new ConcurrentHashMap<>();

    @Override
    public void recordState(String userId, String sessionId, AgentStepState state) {
        SessionKey key = SessionKey.of(userId, sessionId);
        store.computeIfAbsent(key, k -> new ArrayList<>()).add(state);
        log.debug("[StateRepo] 记录状态: userId={}, sessionId={}, step={}, status={}",
                userId, sessionId, state.stepIndex(), state.status());
    }

    @Override
    public Optional<AgentStepState> getLatestState(String userId, String sessionId) {
        SessionKey key = SessionKey.of(userId, sessionId);
        List<AgentStepState> trace = store.get(key);
        if (trace == null || trace.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(trace.get(trace.size() - 1));
    }

    @Override
    public List<AgentStepState> getFullTrace(String userId, String sessionId) {
        SessionKey key = SessionKey.of(userId, sessionId);
        return List.copyOf(store.getOrDefault(key, List.of()));
    }

    /**
     * 清除指定会话的状态轨迹。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     */
    public void clear(String userId, String sessionId) {
        SessionKey key = SessionKey.of(userId, sessionId);
        store.remove(key);
    }
}
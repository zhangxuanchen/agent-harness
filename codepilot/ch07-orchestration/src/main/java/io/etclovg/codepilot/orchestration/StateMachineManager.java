package io.etclovg.codepilot.orchestration;

import io.etclovg.codepilot.core.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * L 层 · Agent 生命周期状态机。
 *
 * <p>管理 Agent 的完整生命周期状态转换，对应书中 Ch7 §7.2。
 * 状态机保证 Agent 始终处于合法状态，非法转换被拒绝并记录。
 *
 * <p><b>状态定义</b>：
 * <pre>
 * IDLE → RUNNING → REASONING ⇄ TOOL_CALL
 *                       ↓           ↓
 *                  COMPLETED    FAILED
 * </pre>
 *
 * <p><b>页面参考</b>：Ch7 §7.2 Agent 生命周期状态机
 */
@Component
public class StateMachineManager {

    private static final Logger log = LoggerFactory.getLogger(StateMachineManager.class);

    /**
     * Agent 状态枚举。
     */
    public enum State {
        /** 初始空闲——等待任务分配 */
        IDLE,
        /** 运行中——已接收任务，正在执行 */
        RUNNING,
        /** 推理中——模型正在生成思考/回复 */
        REASONING,
        /** 工具调用——正在执行工具调用 */
        TOOL_CALL,
        /** 已完成——任务正常终止 */
        COMPLETED,
        /** 失败——任务异常终止 */
        FAILED
    }

    /** 状态转换表：from → 允许的 to 集合 */
    private static final Map<State, Set<State>> TRANSITIONS = Map.of(
            State.IDLE,      EnumSet.of(State.RUNNING),
            State.RUNNING,   EnumSet.of(State.REASONING, State.FAILED, State.COMPLETED),
            State.REASONING, EnumSet.of(State.TOOL_CALL, State.COMPLETED, State.FAILED),
            State.TOOL_CALL, EnumSet.of(State.REASONING, State.FAILED, State.COMPLETED),
            State.COMPLETED, EnumSet.noneOf(State.class),
            State.FAILED,    EnumSet.noneOf(State.class)
    );

    /** 任务ID → Agent 状态 */
    private final Map<String, Entry> stateMap = new ConcurrentHashMap<>();

    /**
     * 初始化 Agent 状态为新任务。
     */
    public void init(String taskId) {
        Entry prev = stateMap.put(taskId, new Entry(State.IDLE, State.IDLE, Instant.now()));
        if (prev != null) {
            log.warn("[L层状态机] 任务 {} 被覆盖——前状态: {}", taskId, prev.current);
        }
        log.info("[L层状态机] 任务 {} 初始化 → IDLE", taskId);
    }

    /**
     * 执行状态转换。非法转换返回 false 并记录警告。
     *
     * @param taskId  任务标识
     * @param to      目标状态
     * @param reason  转换原因（用于日志/审计）
     * @return 转换是否成功
     */
    public boolean transition(String taskId, State to, String reason) {
        Entry entry = stateMap.computeIfAbsent(taskId,
                k -> new Entry(State.IDLE, State.IDLE, Instant.now()));

        Set<State> allowed = TRANSITIONS.get(entry.current);
        if (allowed == null || !allowed.contains(to)) {
            log.warn("[L层状态机] 非法转换拒绝: {} {}→{} ({}), 允许: {}",
                    taskId, entry.current, to, reason, allowed);
            return false;
        }

        State from = entry.current;
        entry.previous = from;
        entry.current = to;
        entry.lastTransition = Instant.now();
        entry.transitionCount++;
        entry.lastReason = reason;

        log.info("[L层状态机] {} {}→{} ({}) [第{}次转换]",
                taskId, from, to, reason, entry.transitionCount);
        return true;
    }

    /**
     * 获取任务当前状态。
     */
    public State currentState(String taskId) {
        Entry entry = stateMap.get(taskId);
        return entry != null ? entry.current : null;
    }

    /**
     * 获取任务状态详情。
     */
    public Entry getEntry(String taskId) {
        return stateMap.get(taskId);
    }

    /**
     * 清理已完成/失败任务的状态。
     */
    public void cleanup(String taskId) {
        Entry removed = stateMap.remove(taskId);
        if (removed != null) {
            log.debug("[L层状态机] 任务 {} 状态已清理（终态: {}）", taskId, removed.current);
        }
    }

    /**
     * 获取当前活跃任务数（未达到终态）。
     */
    public int activeCount() {
        return (int) stateMap.values().stream()
                .filter(e -> e.current != State.COMPLETED && e.current != State.FAILED)
                .count();
    }

    /**
     * 状态条目——记录完整的状态转换历史。
     */
    public static class Entry {
        public volatile State current;
        public volatile State previous;
        public volatile Instant lastTransition;
        public volatile int transitionCount;
        public volatile String lastReason;

        Entry(State current, State previous, Instant lastTransition) {
            this.current = current;
            this.previous = previous;
            this.lastTransition = lastTransition;
            this.transitionCount = 0;
            this.lastReason = "init";
        }
    }
}

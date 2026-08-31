package io.etclovg.codepilot.observability;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 分支式回放引擎——从 EventLog 选定 fork 点后并行对比原始路径与分支路径。
 * <p>
 * 对应书中 Ch8 §8.5.2 交互式调试：分支式回放验证修复假设。
 * <p>
 * 核心流程：
 * 1. 从 EventLogRecorder 读取完整事件流
 * 2. 选定 fork 点（按事件序号或时间戳）
 * 3. 在 fork 点注入修改（如替换工具返回、修改系统提示）
 * 4. 从 fork 点开始重放，使用原始模型+原始 temperature
 * 5. 对比原始路径与分支路径的结果差异
 *
 * <p>注意：完整实现需要与 Agent 执行引擎深度集成，
 * 此处展示核心 API 设计和骨架实现。
 */
@Component
public class ReplayEngine {

    private static final Logger log = LoggerFactory.getLogger(ReplayEngine.class);

    private final EventLogRecorder eventLog;
    private final Agent agent;

    public ReplayEngine(EventLogRecorder eventLog, Agent agent) {
        this.eventLog = eventLog;
        this.agent = agent;
    }

    /**
     * 反事实回放：在 forkEventId 处注入 modifications，重放后续步骤。
     *
     * @param sessionId     原始会话 ID
     * @param forkEventId   fork 点事件 ID
     * @param modifications 注入到 fork 点的上下文修改
     * @return 回放结果，包含路径对比差异
     */
    public ReplayResult replayWithFork(String sessionId, String forkEventId,
                                       Map<String, String> modifications) {
        List<EventLogRecorder.EventRecord> events = eventLog.replaySession(sessionId);
        int forkIndex = findEventIndex(events, forkEventId);

        if (forkIndex < 0) {
            return new ReplayResult(false, "Fork point not found: " + forkEventId, List.of());
        }

        List<EventLogRecorder.EventRecord> prefix = events.subList(0, forkIndex);
        List<EventLogRecorder.EventRecord> suffix = events.subList(forkIndex, events.size());

        // 用 Map 重建 fork 点上下文（避免直接构造 RuntimeContext）
        Map<String, Object> forkContext = reconstructContextMap(prefix);
        applyModifications(forkContext, modifications);

        List<EventLogRecorder.EventRecord> replayedEvents = new ArrayList<>(prefix);
        try {
            replayFromContext(forkContext, suffix, replayedEvents);
        } catch (Exception e) {
            log.error("[ReplayEngine] Replay failed: {}", e.getMessage());
            return new ReplayResult(false, "Replay failed: " + e.getMessage(), replayedEvents);
        }

        DiffResult diff = comparePaths(events, replayedEvents, forkIndex);
        log.info("[ReplayEngine] Replay completed: diffs={}", diff.diffCount());
        return new ReplayResult(true, "Replay completed", replayedEvents, diff);
    }

    private int findEventIndex(List<EventLogRecorder.EventRecord> events, String eventId) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).eventId().equals(eventId)) return i;
        }
        return -1;
    }

    private Map<String, Object> reconstructContextMap(List<EventLogRecorder.EventRecord> prefix) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        for (EventLogRecorder.EventRecord record : prefix) {
            ctx.put("replayed.step." + record.eventId(), record.content());
        }
        return ctx;
    }

    private void applyModifications(Map<String, Object> ctx, Map<String, String> modifications) {
        ctx.putAll(modifications);
        log.info("[ReplayEngine] Injected {} modifications at fork point", modifications.size());
    }

    private void replayFromContext(Map<String, Object> ctx,
                                   List<EventLogRecorder.EventRecord> remaining,
                                   List<EventLogRecorder.EventRecord> output) throws Exception {
        for (EventLogRecorder.EventRecord originalEvent : remaining) {
            // 骨架实现：实际应调用 Agent 执行引擎重放
            output.add(originalEvent);
        }
    }

    private DiffResult comparePaths(List<EventLogRecorder.EventRecord> original,
                                    List<EventLogRecorder.EventRecord> replayed,
                                    int forkIndex) {
        List<String> diffs = new ArrayList<>();
        int compareLen = Math.min(original.size(), replayed.size());
        for (int i = forkIndex; i < compareLen; i++) {
            String origContent = original.get(i).content();
            String replayContent = replayed.get(i).content();
            if (origContent == null && replayContent == null) continue;
            if (origContent != null && !origContent.equals(replayContent)) {
                diffs.add(String.format("Step %d: [%s] vs [%s]",
                        i, truncate(origContent, 50), truncate(replayContent, 50)));
            }
        }
        return new DiffResult(diffs.size(), diffs);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ========== 结果记录 ==========

    public record ReplayResult(
            boolean success, String message,
            List<EventLogRecorder.EventRecord> replayedEvents,
            DiffResult diff
    ) {
        public ReplayResult(boolean success, String message,
                            List<EventLogRecorder.EventRecord> replayedEvents) {
            this(success, message, replayedEvents, new DiffResult(0, List.of()));
        }
    }

    public record DiffResult(int diffCount, List<String> diffDetails) {}
}

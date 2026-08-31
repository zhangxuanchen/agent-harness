package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 事件时间线重建器。
 * <p>对应书中 Ch16 §16.7 —— 生产事件的时间线重建。
 * <p>根据 {@link IncidentContext} 与日志/指标事件，按时间顺序重建事件演进过程，
 * 供事件复盘使用。
 */
@Component
public class TimelineReconstructor {

    private static final Logger log = LoggerFactory.getLogger(TimelineReconstructor.class);

    private final List<TimelineEvent> events = Collections.synchronizedList(new ArrayList<>());

    /**
     * 追加一个时间线事件。
     *
     * @param timestamp 时间戳
     * @param type      事件类型
     * @param description 描述
     */
    public void append(Instant timestamp, String type, String description) {
        events.add(new TimelineEvent(timestamp, type, description));
        log.debug("追加时间线事件: type={}, desc={}", type, description);
    }

    /**
     * 重建有序时间线。
     *
     * @return 按时间排序的事件列表
     */
    public List<TimelineEvent> reconstruct() {
        return events.stream()
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();
    }

    /**
     * 据时间窗口与受影响会话重建时间线（书中 §16.4.1 Step 1 调用）。
     *
     * @param timeWindow       事件时间窗口
     * @param affectedSessions 受影响会话列表
     * @return 重建的时间线（含错误场景描述）
     */
    public PostmortemReport.Timeline reconstruct(String timeWindow, java.util.List<String> affectedSessions) {
        log.info("重建时间线: window={}, sessions={}", timeWindow,
                affectedSessions == null ? 0 : affectedSessions.size());
        // 教学桩：生产实现从 OTel traces + Agent 执行日志按时间窗口提取事件序列
        String errorScenario = (affectedSessions == null || affectedSessions.isEmpty())
                ? "unknown" : "session-" + affectedSessions.get(0);
        return new PostmortemReport.Timeline(
                events.stream().sorted((a, b) -> a.timestamp().compareTo(b.timestamp())).toList(),
                errorScenario
        );
    }

    /**
     * 时间线事件。
     *
     * @param timestamp   时间戳
     * @param type        事件类型
     * @param description 描述
     */
    public record TimelineEvent(Instant timestamp, String type, String description) {
    }
}

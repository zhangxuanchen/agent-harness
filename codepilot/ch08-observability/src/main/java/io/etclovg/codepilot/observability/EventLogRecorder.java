package io.etclovg.codepilot.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 完整事件日志记录器。
 * <p>对应书中 Ch08 §8.5 —— 时间旅行调试。
 */
@Component
public class EventLogRecorder {

    private final Path logDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public EventLogRecorder() {
        this.logDir = Paths.get("logs/events");
        try {
            Files.createDirectories(logDir);
        } catch (Exception e) {
            // ignore
        }
    }

    public void recordEvent(String sessionId, AgentEvent event) {
        try {
            EventRecord record = new EventRecord(
                java.util.UUID.randomUUID().toString().substring(0, 8),
                event.getClass().getSimpleName(),
                System.currentTimeMillis(),
                extractContent(event),
                Map.of("sessionId", sessionId)
            );
            Path logFile = logDir.resolve(sessionId + ".jsonl");
            try (FileWriter writer = new FileWriter(logFile.toFile(), true)) {
                writer.write(mapper.writeValueAsString(record) + "\n");
            }
        } catch (Exception e) {
            // ignore
        }
    }

    public List<EventRecord> replaySession(String sessionId) {
        try {
            Path logFile = logDir.resolve(sessionId + ".jsonl");
            if (!Files.exists(logFile)) return List.of();
            return Files.lines(logFile)
                .map(line -> {
                    try {
                        return mapper.readValue(line, EventRecord.class);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractContent(AgentEvent event) {
        return event != null ? event.toString() : "";
    }

    public record EventRecord(
        String eventId, String eventType, long timestamp,
        String content, Map<String, String> metadata
    ) {}
}
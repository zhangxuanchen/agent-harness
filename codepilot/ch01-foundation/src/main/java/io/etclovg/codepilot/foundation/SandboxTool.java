package io.etclovg.codepilot.foundation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱工具：在隔离环境中执行代码片段
 * 对应书中 Ch01 §1.3 —— Agent 执行环境控制
 */
@Component
public class SandboxTool {

    private static final Logger log = LoggerFactory.getLogger(SandboxTool.class);

    private final Map<String, ExecutionSession> sessions = new ConcurrentHashMap<>();
    private final SandboxConfig config;

    public SandboxTool(SandboxConfig config) {
        this.config = config;
    }

    public ExecutionResult execute(String code, String language) {
        return execute(code, language, config.defaultTimeoutMs());
    }

    public ExecutionResult execute(String code, String language, long timeoutMs) {
        log.info("[SandboxTool] 开始执行: language={}, codeLength={}, timeout={}ms",
                language, code != null ? code.length() : 0, timeoutMs);

        String sessionId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
        Instant startTime = Instant.now();

        try {
            validateCode(code, language);
            ExecutionResult result = doExecute(code, language, timeoutMs);
            long durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();

            ExecutionSession session = new ExecutionSession(
                    sessionId, code, language, result, durationMs,
                    ExecutionStatus.COMPLETED, startTime
            );
            sessions.put(sessionId, session);

            log.info("[SandboxTool] 执行完成: sessionId={}, status={}, duration={}ms",
                    sessionId, result.status(), durationMs);
            return result;

        } catch (SandboxException e) {
            long durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();
            ExecutionResult result = new ExecutionResult(
                    sessionId, ExecutionStatus.FAILED, null, e.getMessage(), durationMs
            );
            ExecutionSession session = new ExecutionSession(
                    sessionId, code, language, result, durationMs,
                    ExecutionStatus.FAILED, startTime
            );
            sessions.put(sessionId, session);

            log.warn("[SandboxTool] 执行失败: sessionId={}, error={}", sessionId, e.getMessage());
            return result;
        }
    }

    private void validateCode(String code, String language) {
        if (code == null || code.isBlank()) {
            throw new SandboxException("代码不能为空");
        }
        if (code.length() > config.maxCodeLength()) {
            throw new SandboxException("代码长度超出限制: " + code.length() + " > " + config.maxCodeLength());
        }
        List<String> allowedLanguages = config.allowedLanguages();
        if (!allowedLanguages.contains(language)) {
            throw new SandboxException("不支持的语言: " + language + "，允许: " + allowedLanguages);
        }
        for (String pattern : config.blockedPatterns()) {
            if (code.contains(pattern)) {
                throw new SandboxException("代码包含禁止模式: " + pattern);
            }
        }
    }

    private ExecutionResult doExecute(String code, String language, long timeoutMs) {
        String sessionId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);

        if ("python".equalsIgnoreCase(language)) {
            return new ExecutionResult(sessionId, ExecutionStatus.COMPLETED,
                    "模拟 Python 执行结果:\n" + code, null, 50L);
        } else if ("java".equalsIgnoreCase(language)) {
            return new ExecutionResult(sessionId, ExecutionStatus.COMPLETED,
                    "模拟 Java 执行结果:\n" + code, null, 30L);
        } else if ("shell".equalsIgnoreCase(language)) {
            return new ExecutionResult(sessionId, ExecutionStatus.COMPLETED,
                    "模拟 Shell 执行结果:\n" + code, null, 10L);
        }

        return new ExecutionResult(sessionId, ExecutionStatus.COMPLETED,
                "执行完成", null, 20L);
    }

    public Optional<ExecutionSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public List<ExecutionSession> getRecentSessions(int limit) {
        return sessions.values().stream()
                .sorted(Comparator.comparing(ExecutionSession::startTime).reversed())
                .limit(limit)
                .toList();
    }

    public void clearSessions() {
        int size = sessions.size();
        sessions.clear();
        log.info("[SandboxTool] 会话已清除: count={}", size);
    }

    public record SandboxConfig(
            long defaultTimeoutMs,
            int maxCodeLength,
            List<String> allowedLanguages,
            List<String> blockedPatterns
    ) {
        public static SandboxConfig defaultConfig() {
            return new SandboxConfig(
                    30_000L, 50_000,
                    List.of("python", "java", "shell", "javascript"),
                    List.of("Runtime.getRuntime", "ProcessBuilder", "System.exit", "rm -rf /")
            );
        }
    }

    public record ExecutionResult(
            String sessionId, ExecutionStatus status,
            String output, String error, long durationMs
    ) {}

    public record ExecutionSession(
            String sessionId, String code, String language,
            ExecutionResult result, long durationMs,
            ExecutionStatus status, Instant startTime
    ) {}

    public enum ExecutionStatus {
        COMPLETED, FAILED, TIMEOUT, CANCELLED
    }

    public static class SandboxException extends RuntimeException {
        public SandboxException(String message) {
            super(message);
        }
    }
}
package io.etclovg.codepilot.casestudy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱中间件：案例研究中的沙箱执行环境集成
 * 对应书中 Ch18 —— Agent 执行环境实战
 */
@Component
public class SandboxMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SandboxMiddleware.class);

    private final Map<String, SandboxSession> sessions = new ConcurrentHashMap<>();
    private final SandboxPolicy policy;

    public SandboxMiddleware() {
        this.policy = SandboxPolicy.defaultPolicy();
    }

    public SandboxMiddleware(SandboxPolicy policy) {
        this.policy = policy;
    }

    public SandboxResult executeInSandbox(String code, String language) {
        return executeInSandbox(code, language, policy.defaultTimeoutMs());
    }

    public SandboxResult executeInSandbox(String code, String language, long timeoutMs) {
        log.info("[SandboxMiddleware] 开始沙箱执行: language={}, codeLength={}",
                language, code != null ? code.length() : 0);

        String sessionId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
        Instant startTime = Instant.now();

        if (!policy.isLanguageAllowed(language)) {
            return new SandboxResult(sessionId, SandboxStatus.REJECTED,
                    null, "语言 " + language + " 不在允许列表中", 0);
        }

        if (code != null && code.length() > policy.maxCodeLength()) {
            return new SandboxResult(sessionId, SandboxStatus.REJECTED,
                    null, "代码长度超出限制", 0);
        }

        try {
            String output = simulateExecution(code, language);
            long durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();

            SandboxSession session = new SandboxSession(
                    sessionId, code, language, SandboxStatus.COMPLETED,
                    output, durationMs, startTime
            );
            sessions.put(sessionId, session);

            log.info("[SandboxMiddleware] 执行完成: sessionId={}, duration={}ms",
                    sessionId, durationMs);

            return new SandboxResult(sessionId, SandboxStatus.COMPLETED,
                    output, null, durationMs);

        } catch (Exception e) {
            long durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();
            return new SandboxResult(sessionId, SandboxStatus.FAILED,
                    null, e.getMessage(), durationMs);
        }
    }

    private String simulateExecution(String code, String language) {
        return "[" + language + "] 模拟执行:\n" + code;
    }

    public Optional<SandboxSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public List<SandboxSession> getRecentSessions(int limit) {
        return sessions.values().stream()
                .sorted(Comparator.comparing(SandboxSession::startTime).reversed())
                .limit(limit)
                .toList();
    }

    public SandboxPolicy getPolicy() {
        return policy;
    }

    public record SandboxPolicy(
            long defaultTimeoutMs,
            int maxCodeLength,
            Set<String> allowedLanguages
    ) {
        public static SandboxPolicy defaultPolicy() {
            return new SandboxPolicy(30_000L, 50_000,
                    Set.of("python", "java", "shell", "javascript"));
        }

        public boolean isLanguageAllowed(String language) {
            return allowedLanguages.contains(language);
        }
    }

    public record SandboxResult(
            String sessionId, SandboxStatus status,
            String output, String error, long durationMs
    ) {}

    public record SandboxSession(
            String sessionId, String code, String language,
            SandboxStatus status, String output,
            long durationMs, Instant startTime
    ) {}

    public enum SandboxStatus {
        COMPLETED, FAILED, REJECTED, TIMEOUT
    }
}
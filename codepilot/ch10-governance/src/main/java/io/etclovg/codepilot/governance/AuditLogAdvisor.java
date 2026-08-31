package io.etclovg.codepilot.governance;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * G 层 · 审计日志 Advisor — WORM 追加式审计与 Merkle 树完整性。
 *
 * <p>实施不可篡改的审计日志记录，通过 Merkle 树确保日志完整性。
 * 日志遵循 WORM（Write Once, Read Many）原则，仅支持追加。
 * 对应书中 Ch10 §10.6–§10.7。
 *
 * <h3>核心属性</h3>
 * <ul>
 *   <li><b>WORM</b> — 每笔记录根据前一笔哈希链接，形成防篡改链</li>
 *   <li><b>Merkle 树</b> — 定期构建 Merkle 树，根哈希可用于验证</li>
 *   <li><b>字段齐全</b> — 会话 ID、时间戳、输入、工具调用、输出</li>
 *   <li><b>隐私保护</b> — PII 凭据自动脱敏</li>
 * </ul>
 */
@Component
public class AuditLogAdvisor extends AbstractLayerMiddleware {

    private final CopyOnWriteArrayList<AuditEntry> auditLog = new CopyOnWriteArrayList<>();
    private volatile String lastMerkleRoot;
    private final List<String> merkleRootHistory = new ArrayList<>();
    private static final int MERKLE_BATCH_SIZE = 64;
    private boolean detailedLogging = false;

    public AuditLogAdvisor() {
        super(Layer.G, "AuditLog");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String sessionId = context.getOrDefault("session.id", "unknown").toString();
        Instant startTime = Instant.now();

        String userInput = sanitize(context.getOrDefault("user.input", "").toString());
        String toolName = context.getOrDefault("tool.name", "").toString();
        int entryIndex = auditLog.size();

        // 执行调用链，完成后构建审计条目
        return next.apply(input).doOnComplete(() -> {
            // 构建审计条目
            String output = sanitize(rc.getExtra().getOrDefault("model.last_response", "").toString());

            Instant endTime = Instant.now();
            long latencyMs = Duration.between(startTime, endTime).toMillis();

            AuditEntry entry = new AuditEntry(entryIndex, sessionId, startTime, endTime, latencyMs,
                    userInput, toolName.isBlank() ? null : toolName, output,
                    computePrevHash(auditLog.isEmpty() ? null : auditLog.get(auditLog.size() - 1)));

            auditLog.add(entry);

            if (auditLog.size() % MERKLE_BATCH_SIZE == 0) {
                buildMerkleTree();
            }

            if (detailedLogging) {
                log.info("[G层-审计] #{} session={} latency={}ms input={}chars output={}chars",
                        entryIndex, sessionId, latencyMs, userInput.length(), output.length());
            }

            rc.put("audit.entryId", entry.index());
            rc.put("audit.merkleRoot", lastMerkleRoot);
        });
    }

    // ========== Merkle 树完整性 ==========

    void buildMerkleTree() {
        if (auditLog.isEmpty()) return;

        List<String> leaves = new ArrayList<>();
        for (AuditEntry entry : auditLog) {
            leaves.add(computeEntryHash(entry));
        }

        int powerSize = 1;
        while (powerSize < leaves.size()) powerSize <<= 1;
        while (leaves.size() < powerSize) {
            leaves.add(leaves.get(leaves.size() - 1));
        }

        List<String> currentLevel = new ArrayList<>(leaves);
        while (currentLevel.size() > 1) {
            List<String> parentLevel = new ArrayList<>();
            for (int i = 0; i < currentLevel.size(); i += 2) {
                parentLevel.add(hashSHA256(currentLevel.get(i) + currentLevel.get(i + 1)));
            }
            currentLevel = parentLevel;
        }

        this.lastMerkleRoot = currentLevel.get(0);
        merkleRootHistory.add(lastMerkleRoot);

        if (merkleRootHistory.size() > 1000) {
            merkleRootHistory.subList(0, 100).clear();
        }

        log.debug("[G层-审计] Merkle 树已构建: {} 条记录 → 根哈希={}", auditLog.size(), lastMerkleRoot);
    }

    private String computeEntryHash(AuditEntry entry) {
        String data = String.join("|",
                String.valueOf(entry.index()), entry.sessionId(), entry.startTime().toString(),
                String.valueOf(entry.latencyMs()), entry.userInput(),
                entry.toolName() != null ? entry.toolName() : "", entry.output(), entry.prevHash());
        return hashSHA256(data);
    }

    private String computePrevHash(AuditEntry prevEntry) {
        if (prevEntry == null) return "0".repeat(64);
        return computeEntryHash(prevEntry);
    }

    private String hashSHA256(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String sanitize(String input) {
        if (input == null || input.isBlank()) return "";
        input = input.replaceAll("sk-[a-zA-Z0-9]{32,}", "[API_KEY_REDACTED]");
        input = input.replaceAll("Bearer\\s+[A-Za-z0-9_\\-.]{20,}", "Bearer [TOKEN_REDACTED]");
        input = input.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z]{2,}", "[EMAIL_REDACTED]");
        input = input.replaceAll("\\b\\d{16}\\b", "[CC_REDACTED]");
        return input;
    }

    // ========== 完整性验证 ==========

    public IntegrityReport verifyIntegrity() {
        if (auditLog.isEmpty()) {
            return new IntegrityReport(true, 0, null, lastMerkleRoot);
        }

        String prevHash = "0".repeat(64);
        for (int i = 0; i < auditLog.size(); i++) {
            AuditEntry entry = auditLog.get(i);
            if (!entry.prevHash().equals(prevHash)) {
                log.error("[G层-审计] 完整性破坏: 条目 #{} prevHash 不匹配", i);
                return new IntegrityReport(false, i,
                        "条目 #" + i + " 哈希链断裂", lastMerkleRoot);
            }
            prevHash = computeEntryHash(entry);
        }

        // 重建 Merkle 树对比
        List<AuditEntry> snapshot = new ArrayList<>(auditLog);
        List<String> leaves = new ArrayList<>();
        for (AuditEntry entry : snapshot) leaves.add(computeEntryHash(entry));

        int powerSize = 1;
        while (powerSize < leaves.size()) powerSize <<= 1;
        while (leaves.size() < powerSize) leaves.add(leaves.get(leaves.size() - 1));

        List<String> currentLevel = new ArrayList<>(leaves);
        while (currentLevel.size() > 1) {
            List<String> parentLevel = new ArrayList<>();
            for (int i = 0; i < currentLevel.size(); i += 2) {
                parentLevel.add(hashSHA256(currentLevel.get(i) + currentLevel.get(i + 1)));
            }
            currentLevel = parentLevel;
        }

        String computedRoot = currentLevel.get(0);
        if (lastMerkleRoot != null && !computedRoot.equals(lastMerkleRoot)) {
            return new IntegrityReport(false, snapshot.size(), "Merkle 根哈希不匹配", lastMerkleRoot);
        }

        return new IntegrityReport(true, snapshot.size(), null, computedRoot);
    }

    // ========== 查询接口 ==========

    public int entryCount() { return auditLog.size(); }

    public List<AuditEntry> recentEntries(int limit) {
        int start = Math.max(0, auditLog.size() - limit);
        return new ArrayList<>(auditLog.subList(start, auditLog.size()));
    }

    public List<AuditEntry> findBySession(String sessionId) {
        return auditLog.stream().filter(e -> e.sessionId().equals(sessionId)).toList();
    }

    public String getMerkleRoot() { return lastMerkleRoot; }

    public List<String> getMerkleRootHistory() { return new ArrayList<>(merkleRootHistory); }

    public void forceBuildMerkleTree() { buildMerkleTree(); }

    public void setDetailedLogging(boolean enabled) { this.detailedLogging = enabled; }

    // ========== 数据记录 ==========

    public record AuditEntry(int index, String sessionId, Instant startTime, Instant endTime,
                              long latencyMs, String userInput, String toolName,
                              String output, String prevHash) {}

    public record IntegrityReport(boolean intact, int entryCount, String errorDetail, String merkleRoot) {
        public String summary() {
            return intact
                    ? "审计日志完整 — " + entryCount + " 条记录"
                    : "审计日志完整性受损 — " + errorDetail;
        }
    }
}

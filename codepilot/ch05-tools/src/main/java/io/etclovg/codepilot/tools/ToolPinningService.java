package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * G 层 · 工具描述固定服务。
 * <p>安装时对工具描述（name + description + params schema）做 SHA-256 哈希固定，
 * 运行时每次调用前验证哈希。描述变更 → ���警 + 阻止执行。
 * 对应书中 Ch5 §5.6.2 — Full-Schema Poisoning (FSP) 防御。
 *
 * <p><b>安全原理</b>：内容完整性不可伪造——任何描述修改都会改变哈希值<br>
 * <b>触发策略</b>：哈希不匹配 → WARN_AND_BLOCK（告警 + 拒绝执行）<br>
 * <b>攻击向量</b>：FSP 攻击可将恶意指令嵌入 name/description/params/defaults 等所有字段
 * [^13]<br>
 * <b>底层</b>：SHA-256（JDK MessageDigest），密码学安全级别 128 bits 抗碰撞
 */
@Component
public class ToolPinningService {

    private static final Logger log = LoggerFactory.getLogger(ToolPinningService.class);

    /** 已固定的工具哈希表：toolName → PinRecord */
    private final Map<String, PinRecord> pinnedTools = new ConcurrentHashMap<>();

    /** 哈希算法 */
    private static final String HASH_ALGORITHM = "SHA-256";

    // —— 配置默认值 ——
    private boolean pinOnRegistration = true;
    private boolean verifyBeforeInvocation = true;
    private HashMismatchPolicy onHashMismatch = HashMismatchPolicy.WARN_AND_BLOCK;

    /**
     * 哈希不匹配时的处理策略。
     * <p>WARN_ONLY：仅日志告警，仍放行（适合开发环境和灰度迁移期）
     * <br>WARN_AND_BLOCK：告警并阻止执行（生产环境）
     */
    public enum HashMismatchPolicy {
        WARN_ONLY, WARN_AND_BLOCK
    }

    /**
     * 在工具注册时计算并固定描述哈希。
     *
     * @param toolName      工具名称
     * @param description   工具描述文本
     * @param paramSchema   参数 schema（JSON 字符串）
     * @return SHA-256 哈希值（64 位十六进制）
     */
    public String pin(String toolName, String description, String paramSchema) {
        String content = buildContent(toolName, description, paramSchema);
        String hash = sha256(content);

        PinRecord record = new PinRecord(toolName, hash, Instant.now(), content);
        pinnedTools.put(toolName, record);

        int len = hash.length();
        log.info("[工具固定] \"{}\" 已固定 SHA-256: {}{}",
                toolName, hash.substring(0, 12), hash.substring(len - 8));
        return hash;
    }

    /**
     * 在工具注册时自动固定（如果开启了 {@code pinOnRegistration}）。
     */
    public String autoPin(String toolName, String description, String paramSchema) {
        if (pinOnRegistration) {
            return pin(toolName, description, paramSchema);
        }
        return null;
    }

    /**
     * 验证工具描述是否与固定时一致。
     * <p>重新计算当前描述的哈希，与注册时存储的哈希对比。
     * 任何描述的修改（包括拼写修正、参数调整、恶意注入）都会导致哈希不匹配。
     *
     * @param toolName      工具名称
     * @param description   当前完整描述
     * @param paramSchema   当前参数 schema
     * @return 验证结果——valid=true 表示哈希一致
     */
    public VerificationResult verify(String toolName, String description, String paramSchema) {
        PinRecord pinned = pinnedTools.get(toolName);
        if (pinned == null) {
            log.warn("[工具固定] \"{}\" 未被固定——跳过验证，建议启用 pinOnRegistration", toolName);
            return new VerificationResult(true, null,
                    "工具 \"" + toolName + "\" 未被固定——未执行 FSP 防护");
        }

        String currentContent = buildContent(toolName, description, paramSchema);
        String currentHash = sha256(currentContent);

        if (currentHash.equals(pinned.hash)) {
            log.debug("[工具固定] \"{}\" 哈希一致 ✓", toolName);
            return new VerificationResult(true, null, null);
        }

        // —— 哈希不匹配 ——
        String diff = computeDiff(pinned.content, currentContent);
        log.error("[工具固定] \"{}\" 哈希不匹配——可能遭受 Full-Schema Poisoning 攻击\n"
                        + "  注册时: {}{}\n"
                        + "  当前值: {}{}\n"
                        + "  差异: {}",
                toolName,
                pinned.hash.substring(0, 8), pinned.hash.substring(56),
                currentHash.substring(0, 8), currentHash.substring(56),
                diff);

        return new VerificationResult(false, diff,
                "工具 \"" + toolName + "\" 的描述已被修改——已触发 FSP 防护");
    }

    /**
     * 调用前验证——如果 {@code verifyBeforeInvocation=true} 则在工具调用前自动执行。
     * <p>哈希不匹配时根据 {@code onHashMismatch} 策略处理。
     *
     * @param toolName      工具名称
     * @param description   当前描述
     * @param paramSchema   当前参数 schema
     * @return true=通过验证（可调用），false=被阻止
     */
    public boolean verifyBeforeCall(String toolName, String description, String paramSchema) {
        if (!verifyBeforeInvocation) {
            return true;
        }

        VerificationResult result = verify(toolName, description, paramSchema);
        if (!result.valid) {
            switch (onHashMismatch) {
                case WARN_ONLY:
                    log.warn("[工具固定] WARN_ONLY 模式：工具 \"{}\" 描述已变更但仍放行", toolName);
                    return true;
                case WARN_AND_BLOCK:
                    log.error("[工具固定] WARN_AND_BLOCK 模式：工具 \"{}\" 已被阻止执行", toolName);
                    return false;
            }
        }
        return true;
    }

    /**
     * 解除工具的固定（工具退役或手动更新时调用）。
     */
    public void unpin(String toolName) {
        PinRecord removed = pinnedTools.remove(toolName);
        if (removed != null) {
            log.info("[工具固定] \"{}\" 已解除固定", toolName);
        }
    }

    /**
     * 重新固定工具——当工具描述经过审批更新后，需要重新计算哈希。
     */
    public String repin(String toolName, String description, String paramSchema) {
        unpin(toolName);
        return pin(toolName, description, paramSchema);
    }

    /**
     * 获取已固定工具数量。
     */
    public int pinnedCount() {
        return pinnedTools.size();
    }

    // —— 内部方法 ——

    /**
     * 构建用于哈希的规范化内容——连接 name + description + params。
     */
    private String buildContent(String toolName, String description, String paramSchema) {
        StringBuilder sb = new StringBuilder();
        sb.append("name:").append(toolName).append('\n');
        sb.append("description:").append(description != null ? description.strip() : "").append('\n');
        sb.append("params:").append(paramSchema != null ? paramSchema.strip() : "");
        return sb.toString();
    }

    /**
     * SHA-256 哈希——使用 JDK 内置 MessageDigest。
     */
    String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用——JDK 环境异常", e);
        }
    }

    /**
     * 计算两个内容字符串的差异摘要。
     */
    private String computeDiff(String original, String current) {
        if (original == null || current == null) return "内容为 null";
        if (original.equals(current)) return "内容一致";

        // 长度变化
        if (original.length() != current.length()) {
            return String.format("长度变化: %d → %d (%+d 字符)",
                    original.length(), current.length(),
                    current.length() - original.length());
        }

        // 定位第一个差异位置
        int minLen = Math.min(original.length(), current.length());
        int diffPos = 0;
        for (int i = 0; i < minLen; i++) {
            if (original.charAt(i) != current.charAt(i)) {
                diffPos = i;
                break;
            }
        }

        int contextStart = Math.max(0, diffPos - 15);
        int contextEnd = Math.min(Math.min(original.length(), current.length()), diffPos + 30);
        return String.format("位置 %d: 原=\"%s\" → 新=\"%s\"",
                diffPos,
                original.substring(contextStart, diffPos) + "[" + original.charAt(diffPos) + "]"
                        + (diffPos + 1 < contextEnd ? original.substring(diffPos + 1, contextEnd) : ""),
                current.substring(contextStart, diffPos) + "[" + current.charAt(diffPos) + "]"
                        + (diffPos + 1 < contextEnd ? current.substring(diffPos + 1, contextEnd) : ""));
    }

    // —— getters/setters ——
    public boolean isPinOnRegistration() { return pinOnRegistration; }
    public void setPinOnRegistration(boolean pinOnRegistration) { this.pinOnRegistration = pinOnRegistration; }
    public boolean isVerifyBeforeInvocation() { return verifyBeforeInvocation; }
    public void setVerifyBeforeInvocation(boolean verifyBeforeInvocation) { this.verifyBeforeInvocation = verifyBeforeInvocation; }
    public HashMismatchPolicy getOnHashMismatch() { return onHashMismatch; }
    public void setOnHashMismatch(HashMismatchPolicy onHashMismatch) { this.onHashMismatch = onHashMismatch; }

    /** 固定的工具记录 */
    record PinRecord(String toolName, String hash, Instant pinnedAt, String content) {}

    /** 哈希验证结果 */
    public record VerificationResult(boolean valid, String diff, String message) {}
}

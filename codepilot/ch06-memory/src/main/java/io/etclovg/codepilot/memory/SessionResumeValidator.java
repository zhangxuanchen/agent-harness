package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session Resume 过期校验器。
 * <p>对应书中 KP 6.6.3 —— Session Resume 过期检测。
 * <p>配套仓库教学实现，非 AgentScope 内置。
 *
 * <p>当从 resume 文件恢复会话时，按三个条件递进检测 resume 是否仍然有效：
 * <ol>
 *   <li><b>时间有效期</b>——resume 保留 7 天，超期返回 {@link ResumeStatus#EXPIRED}（直接归档）</li>
 *   <li><b>代码变更检测</b>——对比 resume 中记录的文件 checksum 与当前 checksum，
 *       不匹配返回 {@link ResumeStatus#STALE}（提示但不阻止）</li>
 *   <li><b>话题漂移检测</b>——用关键词重叠率计算 newInput 与 resume 中最后目标的相似度，
 *       低于 0.5 返回 {@link ResumeStatus#TOPIC_SHIFTED}（提示并默认不恢复）</li>
 * </ol>
 *
 * <p>resume 内容采用简单的行式文本格式，识别以下字段：
 * <ul>
 *   <li>{@code timestamp: 2024-01-01T00:00:00Z}（ISO-8601 时间戳）</li>
 *   <li>{@code goal: ...} 或 {@code last_goal: ...}（最后目标）</li>
 *   <li>{@code src/Main.java: a1b2c3d4}（文件路径: hex checksum）</li>
 * </ul>
 */
@Component
public class SessionResumeValidator {

    private static final Logger log = LoggerFactory.getLogger(SessionResumeValidator.class);

    /** resume 保留有效期（7 天） */
    private static final Duration RESUME_TTL = Duration.ofDays(7);

    /** 话题漂移判定阈值——低于该值视为话题已漂移 */
    private static final double TOPIC_SHIFT_THRESHOLD = 0.5;

    /** 解析 resume 中的时间戳行 */
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("(?i)^timestamp\\s*[:：]\\s*(.+)$");

    /** 解析 resume 中的最后目标行 */
    private static final Pattern GOAL_PATTERN =
            Pattern.compile("(?i)^(last_?goal|goal)\\s*[:：]\\s*(.+)$");

    /** 解析 resume 中的 checksum 行（键需为文件路径形态，值需为 ≥4 位 hex，避免误判） */
    private static final Pattern CHECKSUM_PATTERN =
            Pattern.compile("^\\s*(\\S*[./\\\\]\\S*)\\s*[:：]\\s*([a-fA-F0-9]{4,})\\s*$");

    /**
     * 校验 resume 是否仍然有效。
     *
     * <p>三个条件递进检测：
     * <ul>
     *   <li>EXPIRED——直接归档（短路返回）</li>
     *   <li>STALE——提示但不阻止（继续检测话题漂移）</li>
     *   <li>TOPIC_SHIFTED——提示并默认不恢复（优先于 STALE 返回）</li>
     * </ul>
     *
     * @param resumeContent resume 文件内容
     * @param newInput      本次用户的新输入
     * @param fileChecksums 当前文件的 checksum 映射（文件路径 → checksum）
     * @return 校验结果
     */
    public ResumeValidationResult validate(String resumeContent,
                                           String newInput,
                                           Map<String, String> fileChecksums) {
        log.debug("校验 session resume: resumeLength={}, inputLength={}",
                resumeContent == null ? 0 : resumeContent.length(),
                newInput == null ? 0 : newInput.length());

        Instant resumeTimestamp = parseTimestamp(resumeContent);
        String lastGoal = parseLastGoal(resumeContent);
        Map<String, String> resumeChecksums = parseChecksums(resumeContent);

        // 条件一：时间有效期——超期直接归档（短路返回）
        if (resumeTimestamp == null || isExpired(resumeTimestamp)) {
            String msg = "resume 已过期（超过 " + RESUME_TTL.toDays() + " 天），建议直接归档";
            log.warn(msg);
            return new ResumeValidationResult(ResumeStatus.EXPIRED, msg, 0.0);
        }

        // 计算话题相似度（条件三所需，提前计算以便结果始终携带）
        double similarity = computeKeywordOverlap(newInput, lastGoal);

        // 条件二：代码变更检测——提示但不阻止，记录后继续检测话题漂移
        boolean stale = hasChecksumMismatch(resumeChecksums, fileChecksums);

        // 条件三：话题漂移检测——提示并默认不恢复（优先于 STALE 返回）
        if (similarity < TOPIC_SHIFT_THRESHOLD) {
            String msg = String.format(
                    "话题已漂移（相似度=%.2f < %.2f），默认不恢复 resume",
                    similarity, TOPIC_SHIFT_THRESHOLD);
            log.warn(msg);
            return new ResumeValidationResult(ResumeStatus.TOPIC_SHIFTED, msg, similarity);
        }

        if (stale) {
            String msg = "检测到代码文件变更（checksum 不匹配），resume 可能过期，建议谨慎恢复";
            log.warn(msg);
            return new ResumeValidationResult(ResumeStatus.STALE, msg, similarity);
        }

        String msg = String.format("resume 有效（相似度=%.2f），可恢复", similarity);
        log.debug(msg);
        return new ResumeValidationResult(ResumeStatus.VALID, msg, similarity);
    }

    /** 判断 resume 是否超过 7 天有效期。 */
    private boolean isExpired(Instant resumeTimestamp) {
        return Duration.between(resumeTimestamp, Instant.now()).compareTo(RESUME_TTL) > 0;
    }

    /** 解析 resume 中的时间戳。 */
    private Instant parseTimestamp(String resumeContent) {
        if (resumeContent == null) {
            return null;
        }
        for (String line : resumeContent.split("\\r?\\n")) {
            Matcher m = TIMESTAMP_PATTERN.matcher(line);
            if (m.matches()) {
                try {
                    return Instant.parse(m.group(1).trim());
                } catch (Exception e) {
                    log.warn("无法解析 resume 时间戳: {}", m.group(1));
                    return null;
                }
            }
        }
        return null;
    }

    /** 解析 resume 中的最后目标。 */
    private String parseLastGoal(String resumeContent) {
        if (resumeContent == null) {
            return null;
        }
        for (String line : resumeContent.split("\\r?\\n")) {
            Matcher m = GOAL_PATTERN.matcher(line);
            if (m.matches()) {
                return m.group(2).trim();
            }
        }
        return null;
    }

    /** 解析 resume 中的 checksum 映射。 */
    private Map<String, String> parseChecksums(String resumeContent) {
        Map<String, String> checksums = new HashMap<>();
        if (resumeContent == null) {
            return checksums;
        }
        for (String line : resumeContent.split("\\r?\\n")) {
            Matcher m = CHECKSUM_PATTERN.matcher(line);
            if (m.matches()) {
                checksums.put(m.group(1).trim(), m.group(2).trim().toLowerCase());
            }
        }
        return checksums;
    }

    /** 对比 resume 与当前 checksum，检测代码变更。 */
    private boolean hasChecksumMismatch(Map<String, String> resumeChecksums,
                                        Map<String, String> currentChecksums) {
        if (resumeChecksums.isEmpty()) {
            // resume 未记录 checksum，无法判定，视为未变更
            return false;
        }
        if (currentChecksums == null || currentChecksums.isEmpty()) {
            log.debug("当前 checksum 为空，无法比对，视为 stale");
            return true;
        }
        for (Map.Entry<String, String> entry : resumeChecksums.entrySet()) {
            String path = entry.getKey();
            String expected = entry.getValue();
            String actual = currentChecksums.get(path);
            if (actual == null) {
                log.debug("文件缺失: {}", path);
                return true;
            }
            if (!expected.equalsIgnoreCase(actual.trim())) {
                log.debug("checksum 不匹配: {}", path);
                return true;
            }
        }
        return false;
    }

    /**
     * 计算关键词重叠率（overlap coefficient = |A ∩ B| / min(|A|, |B|)）。
     * <p>对中文采用字符二元组（bigram）近似分词，对英文按非字母数字切分并小写化。
     *
     * @return 相似度 [0.0, 1.0]；任一为空返回 0.0
     */
    private double computeKeywordOverlap(String newInput, String lastGoal) {
        Set<String> inputTokens = tokenize(newInput);
        Set<String> goalTokens = tokenize(lastGoal);
        if (inputTokens.isEmpty() || goalTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(inputTokens);
        intersection.retainAll(goalTokens);
        int minSize = Math.min(inputTokens.size(), goalTokens.size());
        return minSize == 0 ? 0.0 : (double) intersection.size() / minSize;
    }

    /**
     * 文本分词：提取关键词集合。
     * <p>中文连续字符拆为单字与相邻二元组，英文/数字按非字母数字切分为单词并小写化。
     */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isTokenChar(c)) {
                run.append(c);
            } else {
                flushRun(run, tokens);
                run.setLength(0);
            }
        }
        flushRun(run, tokens);
        return tokens;
    }

    /** 将一个连续 token 片段加入集合（中文补二元组，英文小写化）。 */
    private void flushRun(StringBuilder run, Set<String> tokens) {
        if (run.length() == 0) {
            return;
        }
        String s = run.toString();
        if (isCjk(s.charAt(0))) {
            // 中文：补全单字 + 相邻二元组
            for (int i = 0; i < s.length(); i++) {
                tokens.add(String.valueOf(s.charAt(i)));
                if (i + 1 < s.length()) {
                    tokens.add(s.substring(i, i + 2));
                }
            }
        } else {
            tokens.add(s.toLowerCase());
        }
    }

    /** 判断字符是否为 token 组成字符（CJK 或字母数字或下划线/连字符）。 */
    private boolean isTokenChar(char c) {
        return isCjk(c)
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-';
    }

    /** 判断字符是否为 CJK 统一表意文字。 */
    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /** Resume 校验状态。 */
    public enum ResumeStatus {
        /** 有效，可恢复 */
        VALID,
        /** 已过期（超 7 天），直接归档 */
        EXPIRED,
        /** 代码已变更，提示但不阻止 */
        STALE,
        /** 话题已漂移，提示并默认不恢复 */
        TOPIC_SHIFTED
    }

    /** Resume 校验结果。 */
    public record ResumeValidationResult(
            ResumeStatus status,
            String message,
            double similarityScore
    ) {}
}

package io.etclovg.codepilot.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * C 层 · 上下文压缩器。
 *
 * <p>实现 Ch6 §6.5.1 所述的结构化压缩策略——按信息类型分级压缩，而非通用语义摘要。
 * 所有方案在 Artifact Trail（文件跟踪）上得分都很低（最高 2.45/5.0）——
 * 因此本实现用专用结构化字段替代通用摘要，保留数字常量。
 *
 * <h3>压缩输出格式</h3>
 * <pre>{@code
 * {
 *   "goal": "任务目标",
 *   "status": "当前进度",
 *   "key_findings": ["发现1", "发现2"],
 *   "next_plan": "下一步计划",
 *   "numeric_preserves": { "ports": [8080, 5432], "paths": ["/src/main/App.java"] }
 * }
 * }</pre>
 *
 * <p><b>页面参考</b>：Ch6 §6.5.1 摘要策略 / §6.7.2 信息保真度评测<br>
 * <b>原理</b>：Probe-based evaluation > ROUGE——关键事实（文件路径）丢失在 ROUGE 中不可见但任务失败。
 *
 * <p>已从 Spring AI {@code Message}/{@code MessageType} 迁移为 AgentScope {@link Msg}/{@link MsgRole}。
 */
@Component("compactor")
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** 压缩策略 */
    public enum Strategy {
        /** 提取式：选择最重要几轮保留原文，其余丢弃 */
        EXTRACTIVE,
        /** 生成式：生成完整摘要替代原文 */
        ABSTRACTIVE,
        /** 混合式：事实性保留原文，推理性生成摘要 */
        HYBRID,
        /** 结构化：目标+状态+发现+计划（本实现默认策略） */
        STRUCTURED
    }

    private Strategy defaultStrategy = Strategy.STRUCTURED;
    private boolean preserveNumerics = true;

    // ==================== 核心压缩方法 ====================

    /**
     * 压缩消息历史。
     *
     * @param messages 待压缩的消息
     * @param trigger  压缩触发原因
     * @return 压缩结果
     */
    public CompactionResult compact(List<Msg> messages, CompactionTrigger trigger) {
        log.info("[C层·压缩] 触发: {}, 消息数: {}", trigger.name(), messages.size());

        return switch (defaultStrategy) {
            case STRUCTURED -> compactStructured(messages, trigger);
            case EXTRACTIVE  -> compactExtractive(messages, trigger);
            case ABSTRACTIVE -> compactAbstractive(messages, trigger);
            case HYBRID      -> compactHybrid(messages, trigger);
        };
    }

    /** 结构化压缩——核心策略（§6.5.1） */
    private CompactionResult compactStructured(List<Msg> messages, CompactionTrigger trigger) {
        StructuredCompaction sc = new StructuredCompaction();

        // 1. 提取任务目标（最近的 user message 中包含目标的）
        sc.goal = extractGoal(messages);

        // 2. 提取当前状态（最近一轮的 assistant/tool 分析）
        sc.status = extractStatus(messages);

        // 3. 提取关键发现（工具返回中的关键信息）
        sc.keyFindings = extractKeyFindings(messages);

        // 4. 推断下一步计划（最近的 assistant 推理）
        sc.nextPlan = extractNextPlan(messages);

        // 5. 保留数字常量（文件路径、IP、端口、行号）
        if (preserveNumerics) {
            sc.numericPreserves = extractNumericValues(messages);
        }

        // 6. 统计修改过的文件（Artifact Trail——所有方案的最短板）
        sc.modifiedFiles = extractModifiedFiles(messages);

        // 构建结构化摘要文本
        String summary = formatStructuredSummary(sc);
        int tokenEstimate = ContextBudgetAdvisor.defaultEstimator().estimate(summary);

        log.info("[C层·压缩] 结构化压缩完成: {} 轮 → {} tokens 摘要 (发现 {} 个关键项, " +
                "保留 {} 个数字常量, 修改 {} 个文件)",
                messages.size(), tokenEstimate,
                sc.keyFindings.size(), sc.numericPreserves.size(), sc.modifiedFiles.size());

        return new CompactionResult(
                messages.size(),
                tokenEstimate,
                summary,
                sc.modifiedFiles,
                trigger
        );
    }

    /** 提取式压缩——保留最重要的几轮原文（事实性信息） */
    private CompactionResult compactExtractive(List<Msg> messages, CompactionTrigger trigger) {
        // 保留包含数字常量、错误信息、文件路径的轮次
        List<Msg> important = messages.stream()
                .filter(m -> ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(m.getTextContent()).find())
                .toList();

        String summary = important.stream()
                .map(m -> String.format("[%s] %s", m.getRole(), truncate(m.getTextContent(), 500)))
                .collect(Collectors.joining("\n"));

        int tokens = ContextBudgetAdvisor.defaultEstimator().estimate(summary);
        return new CompactionResult(messages.size(), tokens, summary,
                extractModifiedFiles(important), trigger);
    }

    /** 生成式压缩（简化版——生产环境需调用 LLM 生成摘要） */
    private CompactionResult compactAbstractive(List<Msg> messages, CompactionTrigger trigger) {
        // 简化实现：提取每个 message 的关键句子
        String summary = messages.stream()
                .map(m -> {
                    String text = m.getTextContent();
                    // 取前 200 字符作为摘要代理
                    return text != null && text.length() > 200 ? text.substring(0, 200) + "..." : text;
                })
                .collect(Collectors.joining("\n---\n"));

        int tokens = ContextBudgetAdvisor.defaultEstimator().estimate(summary);
        return new CompactionResult(messages.size(), tokens, summary,
                extractModifiedFiles(messages), trigger);
    }

    /** 混合式压缩——事实性保留原文，推理性生成摘要 */
    private CompactionResult compactHybrid(List<Msg> messages, CompactionTrigger trigger) {
        // 分离事实性（含数字常量）vs 推理性消息
        List<Msg> factual = messages.stream()
                .filter(m -> m.getTextContent() != null && ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(m.getTextContent()).find())
                .toList();
        List<Msg> reasoning = messages.stream()
                .filter(m -> m.getTextContent() != null && !ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(m.getTextContent()).find())
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== 事实性信息（保留原文） ===\n");
        factual.forEach(m -> sb.append("[").append(m.getRole()).append("] ")
                .append(truncate(m.getTextContent(), 300)).append("\n"));

        sb.append("=== 推理过程（摘要） ===\n");
        reasoning.forEach(m -> sb.append("[").append(m.getRole()).append("] ")
                .append(truncate(m.getTextContent(), 100)).append("\n"));

        String summary = sb.toString();
        int tokens = ContextBudgetAdvisor.defaultEstimator().estimate(summary);
        return new CompactionResult(messages.size(), tokens, summary,
                extractModifiedFiles(messages), trigger);
    }

    // ==================== 信息提取方法 ====================

    /** 提取任务目标——来自 early user message */
    private String extractGoal(List<Msg> messages) {
        return messages.stream()
                .filter(m -> m.getRole() == MsgRole.USER)
                .findFirst()
                .map(m -> truncate(m.getTextContent(), 300))
                .orElse("(未找到明确目标)");
    }

    /** 提取当前状态——来自最近的 assistant 消息 */
    private String extractStatus(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m.getRole() == MsgRole.ASSISTANT
                    && !m.getTextContent().contains("tool_calls")) {
                return truncate(m.getTextContent(), 200);
            }
        }
        return "(进行中)";
    }

    /** 提取关键发现——工具返回中的关键信息 */
    private List<String> extractKeyFindings(List<Msg> messages) {
        List<String> findings = new ArrayList<>();

        for (Msg m : messages) {
            // 工具执行结果
            MsgRole msgRole = m.getRole();
            if (msgRole == MsgRole.TOOL) {
                String text = m.getTextContent();
                if (text == null) continue;
                // 错误信息
                if (containsError(text)) {
                    findings.add("错误: " + extractErrorLine(text));
                }
                // 成功结果中的关键数据（包含数字常量的行）
                for (String line : text.split("\n")) {
                    if (ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(line).find()) {
                        findings.add("数据: " + line.trim());
                    }
                }
            }
        }

        // 去重并限制数量
        return findings.stream().distinct().limit(10).toList();
    }

    /** 推断下一步计划——最近的 assistant 推理 */
    private String extractNextPlan(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m.getRole() == MsgRole.ASSISTANT) {
                String text = m.getTextContent().toLowerCase();
                // 查找包含计划意图的句子
                String[] planMarkers = {"next", "下一步", "接下来", "planned", "will", "打算"};
                for (String marker : planMarkers) {
                    int idx = text.indexOf(marker);
                    if (idx >= 0) {
                        int start = Math.max(0, idx - 20);
                        int end = Math.min(text.length(), idx + 200);
                        return m.getTextContent().substring(start, end).trim();
                    }
                }
            }
        }
        return "(需根据当前状态决策)";
    }

    /** 提取数字常量——IP 地址、端口号、文件路径、行号 */
    private Map<String, List<String>> extractNumericValues(List<Msg> messages) {
        Map<String, List<String>> preserves = new LinkedHashMap<>();

        Set<String> ips = new LinkedHashSet<>();
        Set<String> ports = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        Set<String> lineNumbers = new LinkedHashSet<>();

        for (Msg m : messages) {
            Matcher matcher = ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(m.getTextContent());
            while (matcher.find()) {
                String ip = matcher.group(1);   // IPv4
                String num = matcher.group(2);  // port / line number
                String path = matcher.group(3); // file path

                if (ip != null && !ip.startsWith("0.")) ips.add(ip);
                if (num != null) {
                    int n = Integer.parseInt(num);
                    if (n > 0 && n < 65536) ports.add(num);
                }
                if (path != null && path.contains("/")) paths.add(path);
            }
            // 行号模式: filename:123 或 line 456
            Matcher lineMatcher = java.util.regex.Pattern.compile(
                    "(?:line|行)\\s*(\\d+)|\\b(\\w+\\.\\w+):(\\d+)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(m.getTextContent());
            while (lineMatcher.find()) {
                String ln = lineMatcher.group(1) != null ? lineMatcher.group(1) : lineMatcher.group(3);
                if (ln != null) lineNumbers.add(ln);
            }
        }

        if (!ips.isEmpty()) preserves.put("ips", new ArrayList<>(ips));
        if (!ports.isEmpty()) preserves.put("ports", new ArrayList<>(ports).subList(0,
                Math.min(ports.size(), 10)));
        if (!paths.isEmpty()) preserves.put("paths", new ArrayList<>(paths));
        if (!lineNumbers.isEmpty()) preserves.put("line_numbers", new ArrayList<>(lineNumbers));

        return preserves;
    }

    /** 提取修改过的文件列表（Artifact Trail） */
    private List<String> extractModifiedFiles(List<Msg> messages) {
        Set<String> files = new LinkedHashSet<>();

        // 文件编辑模式
        for (Msg m : messages) {
            String text = m.getTextContent();
            // editFile/readFile/{file: ...} 模式
            Matcher matcher = java.util.regex.Pattern.compile(
                    "\"(/[^\"]+\\.\\w+)\"|(?:file|path)[:=]\\s*([^\\s,}]+)"
            ).matcher(text);
            while (matcher.find()) {
                String f = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (f != null && f.contains("/") && f.contains(".")) files.add(f);
            }
        }

        return new ArrayList<>(files);
    }

    // ==================== 格式化 ====================

    /** 生成结构化压缩文本 */
    private String formatStructuredSummary(StructuredCompaction sc) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 任务目标\n").append(sc.goal).append("\n\n");
        sb.append("## 当前状态\n").append(sc.status).append("\n\n");

        if (!sc.keyFindings.isEmpty()) {
            sb.append("## 关键发现\n");
            sc.keyFindings.forEach(f -> sb.append("- ").append(f).append("\n"));
            sb.append("\n");
        }

        sb.append("## 下一步计划\n").append(sc.nextPlan).append("\n\n");

        if (!sc.modifiedFiles.isEmpty()) {
            sb.append("## 修改的文件\n");
            sc.modifiedFiles.forEach(f -> sb.append("- ").append(f).append("\n"));
            sb.append("\n");
        }

        if (!sc.numericPreserves.isEmpty()) {
            sb.append("## 关键数值常量\n");
            sc.numericPreserves.forEach((key, vals) ->
                    sb.append("- ").append(key).append(": ")
                            .append(String.join(", ", vals)).append("\n"));
        }

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private boolean containsError(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("error") || lower.contains("exception")
                || lower.contains("failed") || lower.contains("错误")
                || lower.contains("异常") || lower.contains("失败");
    }

    private String extractErrorLine(String text) {
        for (String line : text.split("\n")) {
            if (containsError(line)) return line.trim();
        }
        return truncate(text, 200);
    }

    // ==================== 配置 ====================

    public void setDefaultStrategy(Strategy strategy) {
        this.defaultStrategy = strategy;
    }

    public void setPreserveNumerics(boolean preserveNumerics) {
        this.preserveNumerics = preserveNumerics;
    }

    public Strategy getDefaultStrategy() {
        return defaultStrategy;
    }

    // ==================== 内部类型 ====================

    /** 结构化压缩字段容器 */
    private static class StructuredCompaction {
        String goal;
        String status;
        List<String> keyFindings = List.of();
        String nextPlan;
        Map<String, List<String>> numericPreserves = Map.of();
        List<String> modifiedFiles = List.of();
    }

    /** 压缩触发原因 */
    public enum CompactionTrigger {
        /** 上下文预算超限 */
        OVER_BUDGET,
        /** 定期压缩（每 5-10 步） */
        PERIODIC,
        /** 腐烂阳性——召回率 < 阈值 */
        DECAY_DETECTED,
        /** 手动触发 */
        MANUAL,
        /** 会话即将结束 */
        SESSION_ENDING
    }

    /** 压缩结果 */
    public record CompactionResult(
            int originalTurnCount,
            int compactedTokens,
            String compactedSummary,
            List<String> modifiedFiles,
            CompactionTrigger trigger
    ) {}
}

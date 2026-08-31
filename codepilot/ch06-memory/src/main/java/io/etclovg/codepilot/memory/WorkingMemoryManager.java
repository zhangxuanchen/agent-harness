package io.etclovg.codepilot.memory;

import io.etclovg.codepilot.core.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * C 层 · 工作记忆管理器。
 *
 * <p>实现 Ch6 §6.2.1 定义的「工作记忆」管理——最近 3-5 轮原文对话，
 * 对应人脑前额叶——容量有限但访问延迟接近零。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li><b>添加轮次</b>：{@link #addEntry(WorkingMemoryEntry)} 添加对话轮次</li>
 *   <li><b>滑动窗口</b>：超过 N 轮自动压缩早期轮次（N 默认为 15）</li>
 *   <li><b>关键信息保留</b>：提取数字常量、错误信息、用户目标等关键信息</li>
 *   <li><b>查询 API</b>：按重要性、时间范围、关键词等维度查询</li>
 *   <li><b>清理机制</b>：手动清理、自动清理（超时）、压缩清理</li>
 * </ul>
 *
 * <h3>协作关系</h3>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │           C层记忆管理系统            │
 * │  ┌─────────────┐  ┌──────────┐  ┌─────────┐ │
 * │  │工作记忆(本类)│  │情景记忆  │  │语义记忆 │ │
 * │  └─────────────┘  └──────────┘  └─────────┘ │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>滑动窗口压缩策略</h3>
 * <p>当工作记忆轮次超过 {@link #compactionThreshold} 时，触发自动压缩：
 * <ol>
 *   <li>最近 {@link #recentFullTurns} 轮保持原文（默认 5 轮）</li>
 *   <li>中间轮次生成结构化摘要（通过 {@link ContextCompactor}）</li>
 *   <li>提取关键数字常量保留到摘要中</li>
 * </ol>
 *
 * <p><b>页面参考</b>：Ch6 §6.2.1 工作记忆 / §6.3.2 滑动窗口机制 / §6.5.1 压缩策略
 *
 * @see WorkingMemoryEntry
 * @see ContextCompactor
 */
@Component
public class WorkingMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryManager.class);

    // ==================== 配置参数 ====================

    /** 最近 N 轮保留原文（默认 5 轮） */
    private int recentFullTurns = 5;

    /** 压缩触发阈值（默认 15 轮） */
    private int compactionThreshold = 15;

    /** 活跃上下文 token 上限（默认 12,000） */
    private int maxActiveTokens = 12_000;

    /** 自动清理超时时间（默认 30 分钟） */
    private Duration autoCleanupTimeout = Duration.ofMinutes(30);

    // ==================== 核心数据结构 ====================

    /** 工作记忆窗口：使用并发安全的双端队列 */
    private final Deque<WorkingMemoryEntry> workingWindow = new ConcurrentLinkedDeque<>();

    /** 中间压缩摘要缓存（用于早期轮次的压缩摘要） */
    private final AtomicReference<String> middleSummary = new AtomicReference<>("");

    /** 轮次计数器（用于生成 turnNumber） */
    private final AtomicInteger turnCounter = new AtomicInteger(0);

    /** 关键信息追踪：提取的关键约束、数字常量等 */
    private final Map<String, List<String>> keyInfoTracker = new LinkedHashMap<>();

    /** 最后活跃时间戳（用于自动清理） */
    private volatile Instant lastActiveTime = Instant.now();

    /** 统计信息 */
    private final AtomicInteger totalAddedCount = new AtomicInteger(0);
    private final AtomicInteger totalCompactionCount = new AtomicInteger(0);

    /** 对 ContextCompactor 的引用（可选，用于压缩） */
    private ContextCompactor compactor;

    // ==================== 构造函数 ====================

    public WorkingMemoryManager() {
        this(null);
    }

    public WorkingMemoryManager(ContextCompactor compactor) {
        this.compactor = compactor;
        log.info("[C层·工作记忆] WorkingMemoryManager 初始化完成: recentFullTurns={}, " +
                "compactionThreshold={}, maxActiveTokens={}",
                recentFullTurns, compactionThreshold, maxActiveTokens);
    }

    // ==================== 核心添加方法 ====================

    /**
     * 添加一个工作记忆条目。
     *
     * <p>实现 Ch6 §6.2.1 所述的滑动窗口机制：
     * <ol>
     *   <li>添加条目到工作窗口末尾</li>
     *   <li>更新轮次计数器</li>
     *   <li>提取关键信息并追踪</li>
     *   <li>检查是否需要触发压缩</li>
     *   <li>更新最后活跃时间</li>
     * </ol>
     *
     * @param entry 工作记忆条目
     */
    public void addEntry(WorkingMemoryEntry entry) {
        if (entry == null) {
            log.warn("[C层·工作记忆] 尝试添加 null 条目，已忽略");
            return;
        }

        // 添加到工作窗口
        workingWindow.addLast(entry);
        totalAddedCount.incrementAndGet();

        // 提取关键信息
        extractAndTrackKeyInfo(entry);

        // 检查是否需要压缩
        if (workingWindow.size() > compactionThreshold) {
            triggerCompaction();
        }

        // 更新最后活跃时间
        lastActiveTime = Instant.now();

        log.debug("[C层·工作记忆] 添加条目: role={}, turn={}, importance={}, 窗口大小={}",
                entry.role(), entry.turnNumber(), entry.importance(), workingWindow.size());
    }

    /**
     * 添加一轮对话的便捷方法。
     *
     * @param role 角色（user/assistant/tool）
     * @param content 内容
     * @param metadata 元数据
     */
    public void addTurn(String role, String content, Map<String, Object> metadata) {
        int turnNumber = turnCounter.incrementAndGet();
        WorkingMemoryEntry entry = WorkingMemoryEntry.of(role, content, turnNumber, metadata);
        addEntry(entry);
    }

    /**
     * 添加一轮对话的便捷方法（无元数据）。
     *
     * @param role 角色
     * @param content 内容
     */
    public void addTurn(String role, String content) {
        addTurn(role, content, Map.of());
    }

    // ==================== 滑动窗口压缩 ====================

    /**
     * 触发工作记忆压缩。
     *
     * <p>实现 Ch6 §6.5.1 的结构化压缩策略：
     * <ol>
     *   <li>保留最近 N 轮原文（{@link #recentFullTurns}）</li>
     *   <li>将早期轮次压缩为结构化摘要</li>
     *   <li>关键信息（数字常量、错误信息）提取到摘要中</li>
     * </ol>
     */
    private void triggerCompaction() {
        int totalTurns = workingWindow.size();
        int fullEnd = Math.max(0, totalTurns - recentFullTurns);

        if (fullEnd <= 0) {
            log.debug("[C层·工作记忆] 无需压缩，当前轮次 {} <= recentFullTurns {}",
                    totalTurns, recentFullTurns);
            return;
        }

        log.info("[C层·工作记忆] 触发压缩: 总轮次={}, 保留最近={} 轮原文, 压缩前 {} 轮",
                totalTurns, recentFullTurns, fullEnd);

        // 提取需要压缩的早期轮次
        List<WorkingMemoryEntry> toCompress = new ArrayList<>();
        Iterator<WorkingMemoryEntry> iterator = workingWindow.iterator();
        int idx = 0;
        while (iterator.hasNext() && idx < fullEnd) {
            toCompress.add(iterator.next());
            idx++;
        }

        // 生成结构化摘要
        String summary = buildStructuredSummary(toCompress);
        middleSummary.set(summary);

        // 移除已压缩的早期轮次
        for (int i = 0; i < toCompress.size(); i++) {
            workingWindow.removeFirst();
        }

        totalCompactionCount.incrementAndGet();

        log.info("[C层·工作记忆] 压缩完成: {} 轮 → {} 字符摘要, 剩余窗口大小={}",
                toCompress.size(), summary.length(), workingWindow.size());
    }

    /**
     * 构建结构化摘要。
     *
     * <p>包含：
     * <ul>
     *   <li>已完成子目标（用户消息提取）</li>
     *   <li>关键发现（错误信息、工具结果）</li>
     *   <li>关键数字常量（文件路径、端口、IP等）</li>
     * </ul>
     */
    private String buildStructuredSummary(List<WorkingMemoryEntry> entries) {
        StringBuilder sb = new StringBuilder();

        // 1. 提取子目标（用户消息）
        List<String> goals = entries.stream()
                .filter(WorkingMemoryEntry::isUserMessage)
                .map(e -> truncate(e.content(), 200))
                .distinct()
                .limit(5)
                .toList();

        if (!goals.isEmpty()) {
            sb.append("== 已完成子目标 ==\n");
            goals.forEach(g -> sb.append("  - ").append(g).append("\n"));
            sb.append("\n");
        }

        // 2. 提取关键发现（错误、重要结果）
        List<String> findings = new ArrayList<>();
        for (WorkingMemoryEntry e : entries) {
            if (e.containsError()) {
                findings.add("错误: " + truncate(e.content(), 150));
            } else if (e.containsNumericPreserve()) {
                findings.add("关键数据: " + extractNumericSnippet(e.content()));
            }
        }

        if (!findings.isEmpty()) {
            sb.append("== 关键发现 ==\n");
            findings.stream().distinct().limit(10).forEach(f -> sb.append("  - ").append(f).append("\n"));
            sb.append("\n");
        }

        // 3. 提取关键数字常量
        Map<String, List<String>> numerics = extractAllNumericValues(entries);
        if (!numerics.isEmpty()) {
            sb.append("== 关键数值常量 ==\n");
            numerics.forEach((key, vals) -> {
                sb.append("  ").append(key).append(": ")
                        .append(String.join(", ", vals.stream().distinct().limit(5).toList()))
                        .append("\n");
            });
            sb.append("\n");
        }

        sb.append("(以上为压缩摘要，详细信息见情景记忆)");
        return sb.toString();
    }

    // ==================== 查询方法 ====================

    /**
     * 获取活跃工作上下文。
     *
     * <p>返回格式：中间压缩摘要（如有） + 最近 N 轮原文。
     *
     * @return 工作上下文字符串
     */
    public String getActiveContext() {
        StringBuilder ctx = new StringBuilder();

        // 1. 中间摘要（如有）
        String summary = middleSummary.get();
        if (!summary.isEmpty()) {
            ctx.append("=== 早期轮次摘要 ===\n").append(summary).append("\n\n");
        }

        // 2. 最近 N 轮原文
        ctx.append("=== 最近轮次 ===\n");
        int idx = 0;
        for (WorkingMemoryEntry entry : workingWindow) {
            ctx.append(String.format("[%d/%s] %s%n",
                    entry.turnNumber(), entry.role(), entry.content()));
            idx++;
        }

        return ctx.toString();
    }

    /**
     * 获取最近 N 轮工作记忆条目。
     *
     * @param n 轮次数量（-1 表示全部）
     * @return 工作记忆条目列表
     */
    public List<WorkingMemoryEntry> getRecentEntries(int n) {
        List<WorkingMemoryEntry> all = new ArrayList<>(workingWindow);
        if (n < 0 || n >= all.size()) {
            return all;
        }
        return all.subList(all.size() - n, all.size());
    }

    /**
     * 获取所有工作记忆条目。
     */
    public List<WorkingMemoryEntry> getAllEntries() {
        return getRecentEntries(-1);
    }

    /**
     * 按重要性查询工作记忆条目。
     *
     * @param minImportance 最小重要性阈值
     * @return 过滤后的条目列表
     */
    public List<WorkingMemoryEntry> getEntriesByImportance(double minImportance) {
        return workingWindow.stream()
                .filter(e -> e.importance() >= minImportance)
                .collect(Collectors.toList());
    }

    /**
     * 按时间范围查询工作记忆条目。
     *
     * @param start 起始时间
     * @param end 结束时间
     * @return 过滤后的条目列表
     */
    public List<WorkingMemoryEntry> getEntriesByTimeRange(Instant start, Instant end) {
        return workingWindow.stream()
                .filter(e -> !e.timestamp().isBefore(start) && !e.timestamp().isAfter(end))
                .collect(Collectors.toList());
    }

    /**
     * 按关键词查询工作记忆条目。
     *
     * @param keyword 关键词
     * @return 匹配的条目列表
     */
    public List<WorkingMemoryEntry> searchEntries(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return List.of();
        }

        String lower = keyword.toLowerCase();
        return workingWindow.stream()
                .filter(e -> e.content() != null && e.content().toLowerCase().contains(lower))
                .collect(Collectors.toList());
    }

    /**
     * 获取关键信息追踪器的内容。
     *
     * @return 关键信息映射
     */
    public Map<String, List<String>> getKeyInfoTracker() {
        return new LinkedHashMap<>(keyInfoTracker);
    }

    /**
     * 获取当前工作记忆窗口大小。
     */
    public int getWindowSize() {
        return workingWindow.size();
    }

    /**
     * 估算当前工作记忆的 token 数量。
     */
    public int estimateTotalTokens() {
        int total = workingWindow.stream()
                .mapToInt(WorkingMemoryEntry::estimateTokens)
                .sum();
        total += ContextBudgetAdvisor.defaultEstimator().estimate(middleSummary.get());
        return total;
    }

    /**
     * 获取中间压缩摘要。
     */
    public String getMiddleSummary() {
        return middleSummary.get();
    }

    // ==================== 清理方法 ====================

    /**
     * 清空所有工作记忆。
     */
    public void clear() {
        int previousSize = workingWindow.size();
        workingWindow.clear();
        middleSummary.set("");
        keyInfoTracker.clear();
        turnCounter.set(0);

        log.info("[C层·工作记忆] 清空工作记忆: 清除 {} 轮对话", previousSize);
    }

    /**
     * 清理超过指定时间的未活跃工作记忆。
     *
     * @param timeout 超时时间
     */
    public void cleanupInactive(Duration timeout) {
        if (Duration.between(lastActiveTime, Instant.now()).compareTo(timeout) > 0) {
            log.info("[C层·工作记忆] 自动清理未活跃工作记忆: 超时={}", timeout);
            clear();
        }
    }

    /**
     * 清理低重要性的工作记忆条目。
     *
     * @param minImportance 最小重要性阈值
     */
    public void cleanupLowImportance(double minImportance) {
        int previousSize = workingWindow.size();
        workingWindow.removeIf(e -> e.importance() < minImportance);
        int newSize = workingWindow.size();

        if (previousSize != newSize) {
            log.info("[C层·工作记忆] 清理低重要性条目: 移除 {} 个，剩余 {} 个",
                    previousSize - newSize, newSize);
        }
    }

    // ==================== 统计信息 ====================

    /**
     * 获取统计信息。
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("windowSize", workingWindow.size());
        stats.put("totalAddedCount", totalAddedCount.get());
        stats.put("totalCompactionCount", totalCompactionCount.get());
        stats.put("currentTurnNumber", turnCounter.get());
        stats.put("estimatedTokens", estimateTotalTokens());
        stats.put("lastActiveTime", lastActiveTime.toString());
        stats.put("hasMiddleSummary", !middleSummary.get().isEmpty());
        stats.put("keyInfoCount", keyInfoTracker.values().stream().mapToInt(List::size).sum());
        return stats;
    }

    // ==================== 配置方法 ====================

    public void setRecentFullTurns(int recentFullTurns) {
        this.recentFullTurns = recentFullTurns;
    }

    public void setCompactionThreshold(int compactionThreshold) {
        this.compactionThreshold = compactionThreshold;
    }

    public void setMaxActiveTokens(int maxActiveTokens) {
        this.maxActiveTokens = maxActiveTokens;
    }

    public void setAutoCleanupTimeout(Duration autoCleanupTimeout) {
        this.autoCleanupTimeout = autoCleanupTimeout;
    }

    public void setCompactor(ContextCompactor compactor) {
        this.compactor = compactor;
    }

    // ==================== 辅助方法 ====================

    /**
     * 提取并追踪关键信息。
     */
    private void extractAndTrackKeyInfo(WorkingMemoryEntry entry) {
        if (entry.content() == null) return;

        // 提取数字常量
        java.util.regex.Matcher matcher = ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(entry.content());
        while (matcher.find()) {
            String ip = matcher.group(1);
            String num = matcher.group(2);
            String path = matcher.group(3);

            if (ip != null && !ip.startsWith("0.")) {
                keyInfoTracker.computeIfAbsent("ips", k -> new ArrayList<>()).add(ip);
            }
            if (num != null) {
                keyInfoTracker.computeIfAbsent("numbers", k -> new ArrayList<>()).add(num);
            }
            if (path != null) {
                keyInfoTracker.computeIfAbsent("paths", k -> new ArrayList<>()).add(path);
            }
        }

        // 提取用户目标（user 消息）
        if (entry.isUserMessage()) {
            keyInfoTracker.computeIfAbsent("goals", k -> new ArrayList<>())
                    .add(truncate(entry.content(), 100));
        }

        // 追踪错误信息
        if (entry.containsError()) {
            keyInfoTracker.computeIfAbsent("errors", k -> new ArrayList<>())
                    .add(truncate(entry.content(), 100));
        }
    }

    /**
     * 提取所有条目中的数字常量。
     */
    private Map<String, List<String>> extractAllNumericValues(List<WorkingMemoryEntry> entries) {
        Map<String, List<String>> result = new LinkedHashMap<>();

        for (WorkingMemoryEntry entry : entries) {
            if (entry.content() == null) continue;

            java.util.regex.Matcher matcher = ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(entry.content());
            while (matcher.find()) {
                String ip = matcher.group(1);
                String num = matcher.group(2);
                String path = matcher.group(3);

                if (ip != null && !ip.startsWith("0.")) {
                    result.computeIfAbsent("ips", k -> new ArrayList<>()).add(ip);
                }
                if (num != null) {
                    result.computeIfAbsent("numbers", k -> new ArrayList<>()).add(num);
                }
                if (path != null) {
                    result.computeIfAbsent("paths", k -> new ArrayList<>()).add(path);
                }
            }
        }

        return result;
    }

    /**
     * 提取包含数字常量的片段。
     */
    private String extractNumericSnippet(String content) {
        if (content == null) return "";

        // 找到包含数字常量的行
        for (String line : content.split("\n")) {
            if (ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(line).find()) {
                return truncate(line, 100);
            }
        }
        return truncate(content, 100);
    }

    /**
     * 截断字符串。
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    @Override
    public String toString() {
        return String.format("WorkingMemoryManager{windowSize=%d, turns=%d, tokens=%d}",
                workingWindow.size(), turnCounter.get(), estimateTotalTokens());
    }
}
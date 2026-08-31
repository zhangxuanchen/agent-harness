package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 知识库 · 情景知识存储。
 * <p>对应书中 Ch06 §6.4 — 知识库系统 / §6.2.2 情景记忆。
 * <p>提供结构化情景知识的持久化存储与检索：
 * <ul>
 *   <li><b>知识条目管理</b>：增删改查，带版本号和变更审计</li>
 *   <li><b>分类与标签</b>：支持知识分类（概念/实体/关系/事件/规则）和标签多维度检索</li>
 *   <li><b>时效性管理</b>：支持过期标记、新鲜度评估、自动归档</li>
 *   <li><b>一致性追踪</b>：详细日志埋点记录每次变更，便于数据一致性排查</li>
 *   <li><b>批量操作</b>：支持批量导入、同步、清理操作</li>
 * </ul>
 *
 * <h3>知识分类体系</h3>
 * <pre>
 * ┌──────────────┬───────────────────────────────────────┐
 * │ 分类          │ 说明                                  │
 * ├──────────────┼───────────────────────────────────────┤
 * │ CONCEPT      │ 概念性知识（"什么是X"）                  │
 * │ ENTITY       │ 实体知识（"X的属性"）                   │
 * │ RELATION     │ 关系知识（"X和Y的关系"）                 │
 * │ EVENT        │ 事件知识（"发生了什么"）                 │
 * │ RULE         │ 规则知识（"应该/不应该做什么"）            │
 * │ DECISION     │ 决策知识（"选择了A而非B的原因"）          │
 * │ PREFERENCE   │ 偏好知识（"用户喜欢/不喜欢"）              │
 * └──────────────┴───────────────────────────────────────┘
 * </pre>
 *
 * <h3>变更审计日志</h3>
 * <p>每次对知识条目的增删改操作都会记录完整日志：
 * <pre>{@code
 * [EpisodicKnowledge] ========== 知识条目更新 ==========
 * [EpisodicKnowledge] 条目ID: k-abc123, 标题: Tesla公司, 分类: ENTITY
 * [EpisodicKnowledge] 变更类型: UPDATE, 操作人: system
 * [EpisodicKnowledge] 版本: v1 → v2, 变更字段: [description, tags]
 * [EpisodicKnowledge] ========== 更新完成 ==========
 * }</pre>
 */
@Component
public class EpisodicKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(EpisodicKnowledgeStore.class);

    /** 知识条目存储 */
    private final Map<String, KnowledgeEntry> knowledgeStore = new ConcurrentHashMap<>();

    /** 分类索引：分类 → 条目ID列表 */
    private final Map<KnowledgeCategory, List<String>> categoryIndex = new ConcurrentHashMap<>();

    /** 标签索引：标签 → 条目ID列表 */
    private final Map<String, List<String>> tagIndex = new ConcurrentHashMap<>();

    /** 操作审计日志 */
    private final List<ChangeEvent> auditLog = Collections.synchronizedList(new ArrayList<>());

    /** 存储配置 */
    private final StoreConfig config;

    /** 条目持久化根目录（默认 ~/.codepilot/projects/<sanitized-git-root>/memory/），
     *  用于 MEMORY.md 索引和 Markdown + YAML Frontmatter 文件落盘。
     *  教学实现默认使用基于用户 home 的默认路径。 */
    private final Path memoryRoot;

    /** 全局版本计数器 */
    private int globalVersionCounter = 0;

    /** 总条目计数器 */
    private int totalEntryCounter = 0;

    public EpisodicKnowledgeStore() {
        this(new StoreConfig(30, 1000, 0.3, true));
    }

    public EpisodicKnowledgeStore(StoreConfig config) {
        this(config, defaultMemoryRoot());
    }

    public EpisodicKnowledgeStore(StoreConfig config, Path memoryRoot) {
        this.config = config;
        this.memoryRoot = memoryRoot;
        try {
            Files.createDirectories(memoryRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建情景记忆根目录: " + memoryRoot, e);
        }
        log.info("[EpisodicKnowledge] ========== 情景知识库初始化 ==========");
        log.info("[EpisodicKnowledge] 配置: maxAgeDays={}, maxEntries={}, decayThreshold={}, autoArchive={}",
                config.maxAgeDays(), config.maxEntries(), config.decayThreshold(), config.autoArchive());
        log.info("[EpisodicKnowledge] 根目录: {}", memoryRoot);
        log.info("[EpisodicKnowledge] ========== 初始化完成 ==========");
    }

    /** 返回默认记忆根：~/.codepilot/projects/default/memory/ */
    private static Path defaultMemoryRoot() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".codepilot", "projects", "default", "memory");
    }

    /** 暴露给 MemoryIndexManager / 其他组件使用。 */
    public Path getMemoryRoot() {
        return memoryRoot;
    }

    // ==================== 核心数据类型 ====================

    /**
     * 知识分类枚举。
     */
    public enum KnowledgeCategory {
        /** 概念知识 */
        CONCEPT,
        /** 实体知识 */
        ENTITY,
        /** 关系知识 */
        RELATION,
        /** 事件知识 */
        EVENT,
        /** 规则知识 */
        RULE,
        /** 决策知识 */
        DECISION,
        /** 偏好知识 */
        PREFERENCE
    }

    /**
     * 知识条目。
     */
    public record KnowledgeEntry(
            /** 唯一标识 */
            String entryId,
            /** 标题/标题 */
            String title,
            /** 详细内容 */
            String content,
            /** 知识分类 */
            KnowledgeCategory category,
            /** 标签列表 */
            List<String> tags,
            /** 可信度 [0.0-1.0] */
            double confidence,
            /** 来源 */
            String source,
            /** 创建时间 */
            Instant createdAt,
            /** 更新时间 */
            Instant updatedAt,
            /** 过期时间（null表示永不过期） */
            Instant expiresAt,
            /** 版本号 */
            int version,
            /** 条目元数据 */
            Map<String, Object> metadata,
            /** 关联的知识条目ID */
            List<String> relatedEntryIds,
            /** 是否过期 */
            boolean archived
    ) {}

    /**
     * 变更事件（审计日志）。
     */
    public record ChangeEvent(
            /** 事件ID */
            String eventId,
            /** 条目ID */
            String entryId,
            /** 变更类型 */
            ChangeType changeType,
            /** 操作人 */
            String operator,
            /** 变更前快照 */
            String beforeSnapshot,
            /** 变更后快照 */
            String afterSnapshot,
            /** 变更字段列表 */
            List<String> changedFields,
            /** 变更时间 */
            Instant timestamp,
            /** 影响的版本号 */
            int affectedVersion
    ) {
        public enum ChangeType {
            CREATE, UPDATE, DELETE, ARCHIVE, RESTORE, BATCH_IMPORT
        }
    }

    /**
     * 存储配置。
     */
    public record StoreConfig(
            /** 知识最大存活天数（超过则过期） */
            int maxAgeDays,
            /** 最大条目数 */
            int maxEntries,
            /** 新鲜度衰减阈值 [0.0-1.0] */
            double decayThreshold,
            /** 是否自动归档过期条目 */
            boolean autoArchive
    ) {}

    // ==================== CRUD 操作 ====================

    /**
     * 创建知识条目。
     */
    public KnowledgeEntry createEntry(String title, String content,
                                      KnowledgeCategory category, List<String> tags,
                                      double confidence, String source) {
        return createEntry(title, content, category, tags, confidence, source, Map.of());
    }

    /**
     * 创建知识条目（含元数据）。
     */
    public KnowledgeEntry createEntry(String title, String content,
                                      KnowledgeCategory category, List<String> tags,
                                      double confidence, String source,
                                      Map<String, Object> metadata) {
        log.info("[EpisodicKnowledge] ========== 创建知识条目 ==========");
        log.info("[EpisodicKnowledge] 条目参数: title={}, category={}, tagCount={}, confidence={}",
                title, category, tags != null ? tags.size() : 0, confidence);

        String entryId = "k-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        totalEntryCounter++;

        KnowledgeEntry entry = new KnowledgeEntry(
                entryId, title, content, category,
                tags != null ? List.copyOf(tags) : List.of(),
                confidence, source,
                now, now, null, 1,
                metadata != null ? Map.copyOf(metadata) : Map.of(),
                List.of(), false
        );

        knowledgeStore.put(entryId, entry);
        updateIndexes(entry);

        recordChange(entryId, ChangeEvent.ChangeType.CREATE, "system",
                null, entrySnapshot(entry), List.of(), 1);

        log.info("[EpisodicKnowledge] ========== 知识条目创建完成 ==========");
        log.info("[EpisodicKnowledge] 条目详情: entryId={}, version={}, totalEntries={}",
                entryId, 1, totalEntryCounter);

        return entry;
    }

    /**
     * 读取知识条目。
     */
    public Optional<KnowledgeEntry> getEntry(String entryId) {
        KnowledgeEntry entry = knowledgeStore.get(entryId);
        if (entry != null && entry.archived()) {
            log.debug("[EpisodicKnowledge] 读取已归档条目: entryId={}", entryId);
        }
        return Optional.ofNullable(entry);
    }

    /**
     * 更新知识条目。
     * <p>只更新非 null 的字段，未指定的字段保持原值。
     */
    public KnowledgeEntry updateEntry(String entryId, Map<String, Object> updates) {
        log.info("[EpisodicKnowledge] ========== 更新知识条目 ==========");
        log.info("[EpisodicKnowledge] 条目参数: entryId={}, updateFields={}",
                entryId, updates.keySet());

        KnowledgeEntry existing = knowledgeStore.get(entryId);
        if (existing == null) {
            log.warn("[EpisodicKnowledge] 更新失败：条目不存在, entryId={}", entryId);
            throw new IllegalArgumentException("知识条目不存在: " + entryId);
        }

        int oldVersion = existing.version();
        Instant now = Instant.now();

        // 应用变更
        String newTitle = (String) updates.getOrDefault("title", existing.title());
        String newContent = (String) updates.getOrDefault("content", existing.content());
        List<String> newTags = updates.containsKey("tags")
                ? List.copyOf((List<String>) updates.get("tags"))
                : existing.tags();
        double newConfidence = updates.containsKey("confidence")
                ? ((Number) updates.get("confidence")).doubleValue()
                : existing.confidence();
        Map<String, Object> newMetadata = updates.containsKey("metadata")
                ? Map.copyOf((Map<String, Object>) updates.get("metadata"))
                : existing.metadata();

        // 计算变更字段
        List<String> changedFields = new ArrayList<>();
        if (!newTitle.equals(existing.title())) changedFields.add("title");
        if (!newContent.equals(existing.content())) changedFields.add("content");
        if (!newTags.equals(existing.tags())) changedFields.add("tags");
        if (Math.abs(newConfidence - existing.confidence()) > 0.001) changedFields.add("confidence");
        if (!newMetadata.equals(existing.metadata())) changedFields.add("metadata");

        KnowledgeEntry updated = new KnowledgeEntry(
                entryId, newTitle, newContent, existing.category(),
                newTags, newConfidence, existing.source(),
                existing.createdAt(), now, existing.expiresAt(),
                oldVersion + 1, newMetadata, existing.relatedEntryIds(), false
        );

        knowledgeStore.put(entryId, updated);
        updateIndexes(updated);

        recordChange(entryId, ChangeEvent.ChangeType.UPDATE, "system",
                entrySnapshot(existing), entrySnapshot(updated),
                changedFields, oldVersion + 1);

        log.info("[EpisodicKnowledge] ========== 知识条目更新完成 ==========");
        log.info("[EpisodicKnowledge] 变更详情: entryId={}, v{}→v{}, changedFields={}",
                entryId, oldVersion, oldVersion + 1, changedFields);

        return updated;
    }

    /**
     * 删除知识条目。
     */
    public boolean deleteEntry(String entryId) {
        log.info("[EpisodicKnowledge] ========== 删除知识条目 ==========");
        log.info("[EpisodicKnowledge] 条目参数: entryId={}", entryId);

        KnowledgeEntry removed = knowledgeStore.remove(entryId);
        if (removed == null) {
            log.warn("[EpisodicKnowledge] 删除失败：条目不存在, entryId={}", entryId);
            return false;
        }

        removeFromIndexes(entryId, removed);

        recordChange(entryId, ChangeEvent.ChangeType.DELETE, "system",
                entrySnapshot(removed), null, List.of(), removed.version());

        log.info("[EpisodicKnowledge] ========== 知识条目删除完成 ==========");
        log.info("[EpisodicKnowledge] 条目详情: entryId={}, title={}, remainingEntries={}",
                entryId, removed.title(), knowledgeStore.size());

        return true;
    }

    /**
     * 归档知识条目（软删除）。
     */
    public KnowledgeEntry archiveEntry(String entryId) {
        log.info("[EpisodicKnowledge] ========== 归档知识条目 ==========");
        log.info("[EpisodicKnowledge] 条目参数: entryId={}", entryId);

        KnowledgeEntry existing = knowledgeStore.get(entryId);
        if (existing == null) {
            log.warn("[EpisodicKnowledge] 归档失败：条目不存在, entryId={}", entryId);
            throw new IllegalArgumentException("知识条目不存在: " + entryId);
        }

        KnowledgeEntry archived = new KnowledgeEntry(
                entryId, existing.title(), existing.content(), existing.category(),
                existing.tags(), existing.confidence(), existing.source(),
                existing.createdAt(), Instant.now(), existing.expiresAt(),
                existing.version(), existing.metadata(), existing.relatedEntryIds(),
                true
        );

        knowledgeStore.put(entryId, archived);

        recordChange(entryId, ChangeEvent.ChangeType.ARCHIVE, "system",
                entrySnapshot(existing), entrySnapshot(archived),
                List.of("archived"), existing.version());

        log.info("[EpisodicKnowledge] ========== 知识条目归档完成 ==========");
        return archived;
    }

    /**
     * 恢复已归档条目。
     */
    public KnowledgeEntry restoreEntry(String entryId) {
        KnowledgeEntry existing = knowledgeStore.get(entryId);
        if (existing == null || !existing.archived()) {
            throw new IllegalArgumentException("归档条目不存在: " + entryId);
        }

        KnowledgeEntry restored = new KnowledgeEntry(
                entryId, existing.title(), existing.content(), existing.category(),
                existing.tags(), existing.confidence(), existing.source(),
                existing.createdAt(), Instant.now(), existing.expiresAt(),
                existing.version(), existing.metadata(), existing.relatedEntryIds(),
                false
        );

        knowledgeStore.put(entryId, restored);
        updateIndexes(restored);

        recordChange(entryId, ChangeEvent.ChangeType.RESTORE, "system",
                entrySnapshot(existing), entrySnapshot(restored),
                List.of("archived"), existing.version());

        log.info("[EpisodicKnowledge] 知识条目恢复完成: entryId={}", entryId);
        return restored;
    }

    // ==================== 检索与查询 ====================

    /**
     * 按分类检索知识条目。
     */
    public List<KnowledgeEntry> findByCategory(KnowledgeCategory category) {
        List<String> ids = categoryIndex.getOrDefault(category, List.of());
        return ids.stream()
                .map(knowledgeStore::get)
                .filter(Objects::nonNull)
                .filter(e -> !e.archived())
                .collect(Collectors.toList());
    }

    /**
     * 按标签检索知识条目。
     */
    public List<KnowledgeEntry> findByTag(String tag) {
        List<String> ids = tagIndex.getOrDefault(tag.toLowerCase(), List.of());
        return ids.stream()
                .map(knowledgeStore::get)
                .filter(Objects::nonNull)
                .filter(e -> !e.archived())
                .collect(Collectors.toList());
    }

    /**
     * 关键词搜索。
     */
    public List<KnowledgeEntry> search(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();

        String lower = keyword.toLowerCase();
        return knowledgeStore.values().stream()
                .filter(e -> !e.archived())
                .filter(e -> e.title().toLowerCase().contains(lower)
                        || e.content().toLowerCase().contains(lower)
                        || e.tags().stream().anyMatch(t -> t.toLowerCase().contains(lower)))
                .sorted(Comparator.comparingDouble(KnowledgeEntry::confidence).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 组合条件检索。
     */
    public List<KnowledgeEntry> query(KnowledgeCategory category, List<String> tags,
                                       String keyword, double minConfidence) {
        return knowledgeStore.values().stream()
                .filter(e -> !e.archived())
                .filter(e -> category == null || e.category() == category)
                .filter(e -> tags == null || tags.isEmpty() ||
                        tags.stream().allMatch(t -> e.tags().contains(t.toLowerCase())))
                .filter(e -> keyword == null || keyword.isBlank() ||
                        e.title().toLowerCase().contains(keyword.toLowerCase()) ||
                        e.content().toLowerCase().contains(keyword.toLowerCase()))
                .filter(e -> e.confidence() >= minConfidence)
                .sorted(Comparator.comparingDouble(KnowledgeEntry::confidence).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 获取活跃条目（非归档、未过期）。
     */
    public List<KnowledgeEntry> getActiveEntries() {
        Instant now = Instant.now();
        return knowledgeStore.values().stream()
                .filter(e -> !e.archived())
                .filter(e -> e.expiresAt() == null || e.expiresAt().isAfter(now))
                .collect(Collectors.toList());
    }

    /**
     * 获取已过期条目。
     */
    public List<KnowledgeEntry> getExpiredEntries() {
        Instant now = Instant.now();
        return knowledgeStore.values().stream()
                .filter(e -> !e.archived())
                .filter(e -> e.expiresAt() != null && e.expiresAt().isBefore(now))
                .collect(Collectors.toList());
    }

    /**
     * 获取已归档条目。
     */
    public List<KnowledgeEntry> getArchivedEntries() {
        return knowledgeStore.values().stream()
                .filter(KnowledgeEntry::archived)
                .collect(Collectors.toList());
    }

    // ==================== 新鲜度管理 ====================

    /**
     * 计算条目的新鲜度分数 [0.0-1.0]。
     */
    public double computeFreshness(KnowledgeEntry entry) {
        if (entry.expiresAt() != null) {
            long remainingMs = entry.expiresAt().toEpochMilli() - Instant.now().toEpochMilli();
            if (remainingMs <= 0) return 0.0;
            long totalMs = entry.expiresAt().toEpochMilli() - entry.createdAt().toEpochMilli();
            return totalMs > 0 ? (double) remainingMs / totalMs : 0.5;
        }

        // 无过期时间：基于更新时间衰减
        long daysSinceUpdate = (Instant.now().toEpochMilli() - entry.updatedAt().toEpochMilli()) / 86_400_000L;
        double decayFactor = Math.exp(-0.05 * daysSinceUpdate);
        return Math.max(0.0, Math.min(1.0, decayFactor));
    }

    /**
     * 批量评估新鲜度低于阈值的条目。
     */
    public List<KnowledgeEntry> findStaleEntries() {
        double threshold = config.decayThreshold();
        return knowledgeStore.values().stream()
                .filter(e -> !e.archived())
                .filter(e -> computeFreshness(e) < threshold)
                .collect(Collectors.toList());
    }

    /**
     * 自动归档过期条目。
     */
    public int autoArchiveExpired() {
        List<KnowledgeEntry> expired = getExpiredEntries();
        int count = 0;
        for (KnowledgeEntry entry : expired) {
            archiveEntry(entry.entryId());
            count++;
        }
        log.info("[EpisodicKnowledge] 自动归档过期条目: {} 条", count);
        return count;
    }

    // ==================== 审计日志 ====================

    /**
     * 获取条目变更历史。
     */
    public List<ChangeEvent> getChangeHistory(String entryId) {
        return auditLog.stream()
                .filter(e -> e.entryId().equals(entryId))
                .collect(Collectors.toList());
    }

    /**
     * 获取最近变更事件。
     */
    public List<ChangeEvent> getRecentChanges(int limit) {
        int start = Math.max(0, auditLog.size() - limit);
        return new ArrayList<>(auditLog.subList(start, auditLog.size()));
    }

    /**
     * 获取审计日志大小。
     */
    public int getAuditLogSize() {
        return auditLog.size();
    }

    // ==================== 统计与监控 ====================

    /**
     * 获取存储统计。
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalEntries", knowledgeStore.size());
        stats.put("activeEntries", getActiveEntries().size());
        stats.put("archivedEntries", getArchivedEntries().size());
        stats.put("expiredEntries", getExpiredEntries().size());
        stats.put("categories", categoryIndex.keySet().size());
        stats.put("totalTags", tagIndex.size());
        stats.put("auditLogSize", auditLog.size());
        stats.put("globalVersion", globalVersionCounter);

        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (KnowledgeCategory cat : KnowledgeCategory.values()) {
            categoryCounts.put(cat.name(), categoryIndex.getOrDefault(cat, List.of()).size());
        }
        stats.put("categoryDistribution", categoryCounts);

        return stats;
    }

    // ==================== 批量操作 ====================

    /**
     * 批量导入知识条目。
     */
    public List<KnowledgeEntry> batchImport(List<Map<String, Object>> entries) {
        log.info("[EpisodicKnowledge] ========== 批量导入知识条目 ==========");
        log.info("[EpisodicKnowledge] 导入参数: entryCount={}", entries.size());

        List<KnowledgeEntry> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (Map<String, Object> entryData : entries) {
            try {
                String title = (String) entryData.get("title");
                String content = (String) entryData.get("content");
                KnowledgeCategory category = (KnowledgeCategory) entryData.getOrDefault(
                        "category", KnowledgeCategory.CONCEPT);
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) entryData.getOrDefault("tags", List.of());
                double confidence = entryData.containsKey("confidence")
                        ? ((Number) entryData.get("confidence")).doubleValue() : 0.8;
                String source = (String) entryData.getOrDefault("source", "batch_import");

                KnowledgeEntry entry = createEntry(title, content, category, tags, confidence, source);
                results.add(entry);
                successCount++;
            } catch (Exception e) {
                log.error("[EpisodicKnowledge] 批量导入失败: entryData={}, error={}",
                        entryData, e.getMessage());
                failCount++;
            }
        }

        log.info("[EpisodicKnowledge] ========== 批量导入完成 ==========");
        log.info("[EpisodicKnowledge] 结果统计: total={}, success={}, fail={}",
                entries.size(), successCount, failCount);

        return results;
    }

    /**
     * 清理所有条目（危险操作）。
     */
    public int clearAll() {
        int count = knowledgeStore.size();
        knowledgeStore.clear();
        categoryIndex.clear();
        tagIndex.clear();

        log.warn("[EpisodicKnowledge] 清空所有知识条目: count={}", count);
        return count;
    }

    // ==================== 内部方法 ====================

    /**
     * 更新索引。
     */
    private void updateIndexes(KnowledgeEntry entry) {
        // 分类索引
        categoryIndex.computeIfAbsent(entry.category(), k ->
                Collections.synchronizedList(new ArrayList<>())).add(entry.entryId());

        // 标签索引
        for (String tag : entry.tags()) {
            tagIndex.computeIfAbsent(tag.toLowerCase(), k ->
                    Collections.synchronizedList(new ArrayList<>())).add(entry.entryId());
        }
    }

    /**
     * 从索引中移除。
     */
    private void removeFromIndexes(String entryId, KnowledgeEntry entry) {
        List<String> catIds = categoryIndex.get(entry.category());
        if (catIds != null) catIds.remove(entryId);

        for (String tag : entry.tags()) {
            List<String> tagIds = tagIndex.get(tag.toLowerCase());
            if (tagIds != null) tagIds.remove(entryId);
        }
    }

    /**
     * 记录变更事件。
     */
    private void recordChange(String entryId, ChangeEvent.ChangeType type,
                              String operator, String beforeSnapshot,
                              String afterSnapshot, List<String> changedFields,
                              int affectedVersion) {
        String eventId = "evt-" + UUID.randomUUID().toString().substring(0, 8);
        ChangeEvent event = new ChangeEvent(
                eventId, entryId, type, operator,
                beforeSnapshot, afterSnapshot,
                changedFields, Instant.now(), affectedVersion
        );
        auditLog.add(event);

        if (auditLog.size() > 10000) {
            log.warn("[EpisodicKnowledge] 审计日志过大，裁剪至最近 5000 条");
            List<ChangeEvent> trimmed = new ArrayList<>(auditLog.subList(
                    auditLog.size() - 5000, auditLog.size()));
            auditLog.clear();
            auditLog.addAll(trimmed);
        }

        log.debug("[EpisodicKnowledge] 审计事件: type={}, entryId={}, version={}",
                type, entryId, affectedVersion);
    }

    /**
     * 条目快照（用于变更审计）。
     */
    private String entrySnapshot(KnowledgeEntry entry) {
        if (entry == null) return null;
        return String.format("{id='%s', title='%s', cat=%s, conf=%.2f, v=%d, archived=%s}",
                entry.entryId(), truncate(entry.title(), 30),
                entry.category(), entry.confidence(),
                entry.version(), entry.archived());
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}

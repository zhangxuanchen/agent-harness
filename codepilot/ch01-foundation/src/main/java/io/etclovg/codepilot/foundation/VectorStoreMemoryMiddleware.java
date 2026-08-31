package io.etclovg.codepilot.foundation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量存储记忆中间件：通过向量相似度检索历史执行记忆
 * 对应书中 Ch01 §1.3 —— Agent 记忆与上下文
 */
@Component
public class VectorStoreMemoryMiddleware {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreMemoryMiddleware.class);

    private final Map<String, MemoryEntry> memoryStore = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final double similarityThreshold;

    public VectorStoreMemoryMiddleware() {
        this(10_000, 0.75);
    }

    public VectorStoreMemoryMiddleware(int maxEntries, double similarityThreshold) {
        this.maxEntries = maxEntries;
        this.similarityThreshold = similarityThreshold;
    }

    public String remember(String task, String result, List<Double> embedding) {
        String id = "mem-" + UUID.randomUUID().toString().substring(0, 8);
        MemoryEntry entry = new MemoryEntry(
                id, task, result, embedding,
                Instant.now(), System.currentTimeMillis()
        );
        memoryStore.put(id, entry);

        if (memoryStore.size() > maxEntries) {
            evictOldest();
        }

        log.debug("[VectorStoreMemory] 存储记忆: id={}, task={}", id, task);
        return id;
    }

    public List<MemoryEntry> retrieve(String query, List<Double> queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("[VectorStoreMemory] 查询向量为空，返回空结果");
            return List.of();
        }

        List<ScoredEntry> scored = new ArrayList<>();
        for (MemoryEntry entry : memoryStore.values()) {
            double similarity = cosineSimilarity(queryEmbedding, entry.embedding());
            if (similarity >= similarityThreshold) {
                scored.add(new ScoredEntry(entry, similarity));
            }
        }

        scored.sort(Comparator.comparing(ScoredEntry::similarity).reversed());
        List<MemoryEntry> results = scored.stream()
                .limit(topK)
                .map(ScoredEntry::entry)
                .toList();

        log.info("[VectorStoreMemory] 检索完成: query={}, results={}, threshold={}",
                query, results.size(), similarityThreshold);
        return results;
    }

    public Optional<MemoryEntry> getMemory(String id) {
        return Optional.ofNullable(memoryStore.get(id));
    }

    public int getMemoryCount() {
        return memoryStore.size();
    }

    public void clear() {
        int size = memoryStore.size();
        memoryStore.clear();
        log.info("[VectorStoreMemory] 记忆已清除: count={}", size);
    }

    private void evictOldest() {
        String oldestId = memoryStore.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().timestampMs()))
                .map(Map.Entry::getKey)
                .orElse(null);
        if (oldestId != null) {
            memoryStore.remove(oldestId);
            log.debug("[VectorStoreMemory] 淘汰最旧记忆: id={}", oldestId);
        }
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        int dim = Math.min(a.size(), b.size());
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < dim; i++) {
            dotProduct += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }

        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public record MemoryEntry(
            String id, String task, String result,
            List<Double> embedding, Instant createdAt, long timestampMs
    ) {}

    private record ScoredEntry(MemoryEntry entry, double similarity) {}
}
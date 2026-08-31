package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 时间加权检索。
 * <p>对应书中 Ch06 §6.3 —— 根据时间远近加权的记忆检索策略。
 * <p>weight = relevance_score × exp(-ageMs / halfLifeMs)。
 * 半衰期 halfLifeMs 默认 30 天（约对应 λ=ln2/30≈0.023 每日衰减，接近书中
 * "λ 默认 0.03"的量级）。
 */
@Component
public class TemporalWeightedRetrieval {

    private static final Logger log = LoggerFactory.getLogger(TemporalWeightedRetrieval.class);

    /** 过期偏好比新偏好权重低一个数量级时的半衰期：默认 30 天（毫秒）。 */
    public static final long DEFAULT_HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000;

    /**
     * 加权检索结果。
     */
    public record WeightedResult(
            String id,                   // 原 MemoryEntry.id，便于下游去重
            String content,
            double temporalWeight,       // exp(-ageMs/halfLifeMs) ∈ (0,1]
            double relevanceScore,       // 原始相关性
            double finalScore,           // relevanceScore * temporalWeight
            long ageMs
    ) implements Comparable<WeightedResult> {
        @Override
        public int compareTo(WeightedResult o) {
            return Double.compare(o.finalScore, this.finalScore); // 降序
        }
    }

    /**
     * 执行时间加权检索。
     * <p>对每条 MemoryEntry 按 ageMs 计算时间衰减权重，再乘以 relevanceScore 得到
     * finalScore，降序返回 Top-K。
     *
     * @param query   查询（当前为空壳检索：相关性分数由上游 VectorStore 等计算后写入 MemoryEntry）
     * @param entries 候选条目（上游已做相关性估算，提供 relevanceScore）
     * @param topK    返回数量上限
     * @return 加权排序后的 Top-K 结果
     */
    public List<WeightedResult> retrieve(String query, List<MemoryEntry> entries, int topK) {
        log.info("[TemporalWeighted] 加权检索: entries={}, topK={}", entries == null ? 0 : entries.size(), topK);
        if (entries == null || entries.isEmpty() || topK <= 0) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<WeightedResult> all = new ArrayList<>(entries.size());
        for (MemoryEntry e : entries) {
            long age = Math.max(0L, now - e.timestamp());
            double temporal = calculateTemporalWeight(age, DEFAULT_HALF_LIFE_MS);
            double finalScore = e.relevanceScore() * temporal;
            all.add(new WeightedResult(e.id(), e.content(), temporal, e.relevanceScore(), finalScore, age));
        }
        Collections.sort(all);
        int limit = Math.min(topK, all.size());
        List<WeightedResult> top = all.subList(0, limit);
        log.debug("[TemporalWeighted] Top-{} 最小 finalScore: {}",
                limit, top.isEmpty() ? "N/A" : String.format(Locale.ROOT, "%.4f", top.get(limit - 1).finalScore()));
        return top;
    }

    /**
     * 对 KP 6.2.2 EpisodicMemoryMiddleware 的快捷入口：向量相似度先查好，再走时间衰减。
     * <p>本类不直接依赖 VectorStore，生产链路是：上游（EpisodicKnowledgeStore + VectorStore）
     * 把 relevance-sorted 候选构造成 MemoryEntry，再调用本类的 retrieve() 加权。
     * 这里的快捷入口返回 WeightedResult（向上兼容），供其他组件快速串联。
     */
    public List<WeightedResult> search(String query, int topK) {
        // 教学实现空壳：真实链路是 EpisodicKnowledgeStore.vectorStore → 返回 relevance-sorted 候选
        // → 转 MemoryEntry → 本类 retrieve() 加权。此处留接口，具体实现时按记忆后端替换。
        log.debug("[TemporalWeighted.search] 快捷入口: query={}, topK={}", query, topK);
        return List.of();
    }

    /**
     * 记忆条目（上游传入的候选）。relevanceScore 由向量/关键词检索阶段估算，timestamp 是
     * 记忆创建/最后更新时间，用于计算 ageMs。
     */
    public record MemoryEntry(
            String id,
            String content,
            long timestamp,
            double relevanceScore
    ) {}

    /**
     * 计算时间衰减权重。weight = exp(-ageMs / halfLifeMs)。
     */
    public double calculateTemporalWeight(long ageMs, double halfLifeMs) {
        if (halfLifeMs <= 0) return 1.0;
        return Math.exp(-ageMs / halfLifeMs);
    }
}
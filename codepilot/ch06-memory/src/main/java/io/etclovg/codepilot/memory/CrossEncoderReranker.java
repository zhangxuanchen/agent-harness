package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 Cross-Encoder 的重排器。
 * <p>对应书中 Ch06 §6.5 —— 检索结果的重排序优化。
 * <p>对召回的候选文档使用 Cross-Encoder 进行 query-doc 联合编码打分，
 * 提升最终注入上下文的相关性。当前为占位实现（按原 relevance 排序），
 * 生产环境应接入真实的 BAAI/bge-reranker-base 等 Cross-Encoder 模型。
 */
@Component
public class CrossEncoderReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);

    /**
     * 对 MemoryRetrievalResult 候选重排。
     *
     * @param query    查询文本
     * @param results  召回结果
     * @param topK     保留数量
     * @return 重排后的结果
     */
    public List<MemoryRetrievalResult> rerank(String query, List<MemoryRetrievalResult> results, int topK) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<MemoryRetrievalResult> copy = new ArrayList<>(results);
        copy.sort(Comparator.comparingDouble(MemoryRetrievalResult::score).reversed());
        int limit = Math.min(topK, copy.size());
        log.debug("重排检索结果: query={}, input={}, output={}", query, results.size(), limit);
        return copy.subList(0, limit);
    }

    /**
     * 对 InternalRAGService.RetrievedChunk 候选重排。
     * <p>KP 6.5.2 内部 RAG 管线中 Hybrid Search 输出是 RetrievedChunk，Cross-Encoder
     * 与 TemporalWeightedRetrieval（输入是 MemoryRetrievalResult）的输入类型不同。
     * 本重载在不破坏 MemoryRetrievalResult 接口的前提下，桥接到 InternalRAGService 管线。
     *
     * @param query      查询文本
     * @param candidates 候选 chunk（通常是 Hybrid Search 的 Top-50）
     * @param topK       保留数量（通常 20）
     * @return 重排结果，数量不超过 topK
     */
    public List<InternalRAGService.RetrievedChunk> rerankChunks(String query,
                                                                 List<InternalRAGService.RetrievedChunk> candidates,
                                                                 int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // P2-C 教学实现：按 relevanceScore 近似排序（Cross-Encoder 还没加载时的降级行为）。
        // 生产替换为：model.score(query, chunk.content()) 联合编码打分。
        List<InternalRAGService.RetrievedChunk> copy = new ArrayList<>(candidates);
        copy.sort(Comparator.comparingDouble(InternalRAGService.RetrievedChunk::relevanceScore).reversed());
        int limit = Math.min(topK, copy.size());
        log.debug("重排 RetrievedChunk: query={}, input={}, output={}", query, candidates.size(), limit);
        return copy.subList(0, limit);
    }
}

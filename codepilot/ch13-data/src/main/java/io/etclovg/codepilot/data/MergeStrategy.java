package io.etclovg.codepilot.data;

/**
 * 多后端检索结果合并策略。
 * <p>对应书中 Ch13 §13.1 —— 三层知识路由中多后端结果的合并去重。
 * <p>{@link MultiBackendRouter} 在收集各后端（向量 RAG / GraphRAG / SQL）
 * 的结果后，按本策略合并为统一结果集。
 */
public enum MergeStrategy {
    /** 按文档 ID 去重合并 */
    DEDUP_BY_ID,
    /** 按分数排序后合并 */
    RANK_MERGE,
    /** 首个非空后端结果胜出 */
    FIRST_WINS
}

package io.etclovg.codepilot.memory;

/**
 * 记忆检索结果。
 * <p>封装知识库/向量存储的检索结果，包含相关性分数等元数据。
 * 供 {@link CrossEncoderReranker} 等检索组件使用。
 */
public record MemoryRetrievalResult(
        String id,
        String content,
        double score,
        String source
) {
    public double score() {
        return score;
    }
}
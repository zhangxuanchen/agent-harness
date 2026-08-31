package io.etclovg.codepilot.memory;

import java.util.List;

/**
 * 嵌入模型接口。
 * <p>对应书中 Ch06 §6.5 —— 语义向量存储的嵌入生成抽象。
 * <p>屏蔽具体嵌入服务（OpenAI、本地模型等）的差异，
 * 供 {@link VectorStore} 与 {@link CrossEncoderReranker} 调用。
 */
public interface EmbeddingModel {

    /**
     * 获取模型名称。
     *
     * @return 模型名称
     */
    String modelName();

    /**
     * 获取向量维度。
     *
     * @return 维度
     */
    int dimension();

    /**
     * 对单段文本生成嵌入向量。
     *
     * @param text 输入文本
     * @return 嵌入向量
     */
    List<Double> embed(String text);

    /**
     * 批量生成嵌入向量。
     *
     * @param texts 输入文本列表
     * @return 嵌入向量列表
     */
    List<List<Double>> embedBatch(List<String> texts);
}

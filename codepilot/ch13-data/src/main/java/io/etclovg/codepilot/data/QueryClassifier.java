package io.etclovg.codepilot.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量查询分类器。
 * <p>对应书中 Ch13 §13.1 —— 三层知识路由入口的意图分类器。
 * <p>在检索入口用单次 LLM 推理（延迟 &lt; 50ms）判断查询意图，输出三类标签：
 * <ul>
 *   <li>{@code SEMANTIC} —— 语义搜索 → 向量 RAG</li>
 *   <li>{@code RELATIONAL} —— 关系推理 → GraphRAG</li>
 *   <li>{@code STRUCTURED} —— 精确过滤 → SQL</li>
 * </ul>
 * 供 {@link MultiBackendRouter} 据此路由到对应检索引擎。
 *
 * <p>提供两档输出：
 * <ul>
 *   <li>{@link #classify(String)} —— 单标签（argmax），供硬路由使用</li>
 *   <li>{@link #predict(String)} —— 置信度分布，供软路由（多引擎并行 + RRF 融合）使用</li>
 * </ul>
 */
@Component
public class QueryClassifier {

    private static final Logger log = LoggerFactory.getLogger(QueryClassifier.class);

    /** 语义搜索标签 */
    public static final String SEMANTIC = "SEMANTIC";
    /** 关系推理标签 */
    public static final String RELATIONAL = "RELATIONAL";
    /** 精确查询标签 */
    public static final String STRUCTURED = "STRUCTURED";

    /**
     * 分类查询意图（单标签）。
     * <p>基于 {@link #predict(String)} 的置信度分布取 argmax。
     *
     * @param query 用户查询文本
     * @return {@link #SEMANTIC} / {@link #RELATIONAL} / {@link #STRUCTURED}
     */
    public String classify(String query) {
        Map<String, Double> scores = predict(query);
        return scores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(SEMANTIC);
    }

    /**
     * 输出三类标签的置信度分布，供软路由（多引擎并行检索 + RRF 融合）使用。
     * <p>概念示例：桩实现返回默认分布（SEMANTIC 主导）。实际实现可用
     * Embedding 最近邻或蒸馏小模型（FastText/BERT-tiny）产出真实分布。
     *
     * @param query 用户查询文本
     * @return 标签 → 置信度（三类之和不必归一，供阈值过滤）
     */
    public Map<String, Double> predict(String query) {
        // 桩实现：默认 SEMANTIC 主导。实际应基于 Embedding 最近邻或小模型分类器。
        log.debug("[QueryClassifier] 预测分布（桩实现默认 SEMANTIC 主导）: query={}", query);
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put(SEMANTIC, 0.6);
        scores.put(STRUCTURED, 0.35);
        scores.put(RELATIONAL, 0.05);
        return scores;
    }
}

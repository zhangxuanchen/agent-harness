package io.etclovg.codepilot.tools;

import java.util.Map;

/**
 * 搜索结果记录。
 * <p>对应书中 Ch05 §5.3 —— 通用搜索工具的返回结构。
 * <p>统一封装 Web 搜索、知识库检索等工具的结果条目，
 * 供语义工具路由器与编排层引用。
 *
 * @param title    结果标题
 * @param url      结果链接
 * @param snippet  摘要
 * @param source   结果来源（如 web、kb、code）
 * @param score    相关性得分（0-1）
 * @param metadata 附加元数据
 */
public record SearchResult(
        String title,
        String url,
        String snippet,
        String source,
        double score,
        Map<String, Object> metadata
) {

    /**
     * 构造带默认得量的搜索结果。
     *
     * @param title   标题
     * @param url     链接
     * @param snippet 摘要
     * @return 搜索结果
     */
    public static SearchResult of(String title, String url, String snippet) {
        return new SearchResult(title, url, snippet, "web", 0.0, Map.of());
    }

    /**
     * 是否为高相关结果。
     *
     * @param threshold 阈值
     * @return 得分大于等于阈值返回 true
     */
    public boolean isRelevant(double threshold) {
        return score >= threshold;
    }
}

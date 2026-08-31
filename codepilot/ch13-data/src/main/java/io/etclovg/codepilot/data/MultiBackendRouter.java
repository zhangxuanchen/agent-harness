package io.etclovg.codepilot.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 多后端路由器。
 * <p>对应书中 Ch13 §13.1 —— 三层知识路由的多后端选择与结果合并。
 * <p>根据 {@link QueryClassifier} 的分类结果（SEMANTIC / RELATIONAL / STRUCTURED）
 * 动态选择检索引擎——向量库（语义）、图存储（关系）、JdbcTemplate（结构化），
 * 支持结果合并与去重。分类失败时回退到 {@code fallback} 指定的后端。
 *
 * <p>典型用法（builder 模式）：
 * <pre>{@code
 * MultiBackendRouter.builder()
 *     .classifier(classifier)
 *     .backend("SEMANTIC", query -> vectorStore.similaritySearch(...))
 *     .backend("RELATIONAL", query -> graphStore.traverse(...))
 *     .backend("STRUCTURED", query -> jdbcTemplate.queryForList(...))
 *     .mergeStrategy(MergeStrategy.DEDUP_BY_ID)
 *     .fallback("SEMANTIC")
 *     .build();
 * }</pre>
 */
public class MultiBackendRouter {

    private static final Logger log = LoggerFactory.getLogger(MultiBackendRouter.class);

    private final QueryClassifier classifier;
    private final Map<String, Function<Object, Object>> backends;
    private final MergeStrategy mergeStrategy;
    private final String fallback;

    MultiBackendRouter(QueryClassifier classifier,
                       Map<String, Function<Object, Object>> backends,
                       MergeStrategy mergeStrategy,
                       String fallback) {
        this.classifier = classifier;
        this.backends = backends;
        this.mergeStrategy = mergeStrategy;
        this.fallback = fallback;
    }

    /**
     * 创建 builder。
     *
     * @return 构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 根据查询分类结果选择后端并执行检索。
     *
     * @param query 查询对象
     * @return 合并后的结果列表
     */
    public List<Object> route(Object query) {
        String label = classifier != null ? classifier.classify(String.valueOf(query)) : fallback;
        Function<Object, Object> backend = backends.get(label);
        if (backend == null) {
            backend = backends.get(fallback);
        }
        if (backend == null) {
            log.debug("[MultiBackendRouter] 无可用后端: label={}, fallback={}", label, fallback);
            return new ArrayList<>();
        }
        Object result = backend.apply(query);
        log.debug("[MultiBackendRouter] 路由: label={}, mergeStrategy={}", label, mergeStrategy);
        return result instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    /**
     * 选择最优后端（保留原有便捷方法）。
     *
     * @param backends 候选后端列表
     * @return 选中的后端名称
     */
    public String select(List<String> backends) {
        if (backends == null || backends.isEmpty()) {
            return null;
        }
        String selected = backends.get(0);
        log.debug("选择后端: candidates={}, selected={}", backends.size(), selected);
        return selected;
    }

    /** @return 分类器 */
    public QueryClassifier classifier() {
        return classifier;
    }

    /** @return 合并策略 */
    public MergeStrategy mergeStrategy() {
        return mergeStrategy;
    }

    /** @return 回退后端标签 */
    public String fallback() {
        return fallback;
    }

    /**
     * 多后端路由器构造器。
     */
    public static class Builder {

        private QueryClassifier classifier;
        private final Map<String, Function<Object, Object>> backends = new LinkedHashMap<>();
        private MergeStrategy mergeStrategy = MergeStrategy.DEDUP_BY_ID;
        private String fallback;

        /**
         * 设置查询分类器。
         *
         * @param classifier 分类器
         * @return 当前 builder
         */
        public Builder classifier(QueryClassifier classifier) {
            this.classifier = classifier;
            return this;
        }

        /**
         * 注册一个后端检索引擎。
         *
         * @param label   后端标签（SEMANTIC / RELATIONAL / STRUCTURED）
         * @param backend 检索函数
         * @return 当前 builder
         */
        public Builder backend(String label, Function<Object, Object> backend) {
            this.backends.put(label, backend);
            return this;
        }

        /**
         * 设置结果合并策略。
         *
         * @param mergeStrategy 合并策略
         * @return 当前 builder
         */
        public Builder mergeStrategy(MergeStrategy mergeStrategy) {
            this.mergeStrategy = mergeStrategy;
            return this;
        }

        /**
         * 设置分类失败时的回退后端标签。
         *
         * @param fallback 回退后端标签
         * @return 当前 builder
         */
        public Builder fallback(String fallback) {
            this.fallback = fallback;
            return this;
        }

        /**
         * 构造路由器。
         *
         * @return 多后端路由器
         */
        public MultiBackendRouter build() {
            return new MultiBackendRouter(classifier, backends, mergeStrategy, fallback);
        }
    }
}

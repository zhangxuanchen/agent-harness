package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * T 层 · 语义工具路由器。
 * <p>实现"路由预选 + 模型精选"两级选择的第一级——路由预选。
 * 将工具描述向量化，通过语义相似度检索 Top-K 个候选工具，K 从 100+ 降至 5-10。
 * 对应书中 Ch5 §5.4.2 — 语义路由预选。
 *
 * <p><b>效果</b>：741 工具场景下 Token 消耗从 127K 降至 1K（-99.1%）[^3]<br>
 * <b>准确率</b>：207 工具 64%→94%（+47%），417 工具 20%→94%（+370%）<br>
 * <b>原理</b>：将"检索"和"推理"分离——Embedding 做 O(log N) 召回，LLM 仅在 K=5-10 中精选
 */
@Component
public class SemanticToolRouter {

    private static final Logger log = LoggerFactory.getLogger(SemanticToolRouter.class);

    /** 工具向量存储——生产环境替换为 PGVector/Chroma/Redis 等向量数据库 */
    private final Map<String, ToolDescriptor> toolStore = new ConcurrentHashMap<>();

    /** 默认 Top-K */
    private int defaultTopK = 5;

    /** 向量维度 */
    private static final int VECTOR_DIM = 8;

    /**
     * 路由预选：从全量工具中检索 Top-K 个语义最相关的候选。
     * <p>生产环境使用 EmbeddingModel 做真实向量检索。
     * 本实现为展示两级选择架构的模式代码——返回工具名称列表。
     *
     * @param query            用户查询文本
     * @param allTools         全量工具描述映射（name → description）
     * @param topK             返回候选数量（1-10）
     * @return Top-K 工具名称列表，按语义相似度降序
     */
    public List<String> route(String query, Map<String, String> allTools, int topK) {
        int k = Math.clamp(topK, 1, 10);

        if (allTools == null || allTools.isEmpty()) {
            log.warn("[T层路由] 工具列表为空");
            return List.of();
        }

        if (allTools.size() <= k) {
            log.info("[T层路由] 工具数({}) ≤ Top-K({})——跳过路由，直接返回全量",
                    allTools.size(), k);
            List<String> all = new ArrayList<>(allTools.keySet());
            Collections.sort(all);
            return all;
        }

        long startTime = System.currentTimeMillis();

        // 语义相似度计算
        // 生产环境：EmbeddingModel.embed(query) → VectorStore.similaritySearch(topK)
        List<Map.Entry<String, Double>> scored = allTools.entrySet().stream()
                .map(e -> {
                    double score = semanticSimilarity(query, e.getKey(), e.getValue());
                    return Map.entry(e.getKey(), score);
                })
                .filter(e -> e.getValue() > 0.0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        List<String> topKResults = scored.stream()
                .limit(k)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        long elapsed = System.currentTimeMillis() - startTime;

        double tokenSavingsPct = (1.0 - (double) topKResults.size() / allTools.size()) * 100;

        log.info("[T层路由] 从 {} 个工具中路由选出 Top-{}(耗时 {}ms)——Token 节省 ≈ {:.1f}%",
                allTools.size(), topKResults.size(), elapsed, tokenSavingsPct);

        // Top-5 恰好对应每个工具 ~300-500 tokens，总计 ~1,500-2,500 tokens
        // vs 全量 741 工具 ~127K tokens
        return topKResults;
    }

    /**
     * 仅基于名称和描述文本的路由——无额外元数据。
     */
    public List<String> route(String query, Map<String, String> allTools) {
        return route(query, allTools, defaultTopK);
    }

    /**
     * 语义相似度计算。
     * <p>生产环境替换为 EmbeddingModel + 向量数据库。
     * 当前使用 TF-IDF 风格的 Jaccard + containment 作为近似。
     */
    double semanticSimilarity(String query, String toolName, String toolDescription) {
        if (query == null || toolDescription == null) return 0.0;

        String queryLower = query.toLowerCase();
        String descLower = toolDescription.toLowerCase();
        String nameLower = toolName.toLowerCase();

        // 名称匹配加分
        double nameScore = 0.0;
        String[] nameParts = nameLower.split("[-_]");
        int nameMatchCount = 0;
        for (String part : nameParts) {
            if (part.length() >= 2 && queryLower.contains(part)) {
                nameMatchCount++;
            }
        }
        if (nameParts.length > 0) {
            nameScore = (double) nameMatchCount / nameParts.length;
        }

        // 描述关键词重叠（Jaccard 相似度）
        Set<String> queryTokens = tokenize(queryLower);
        Set<String> descTokens = tokenize(descLower);

        if (queryTokens.isEmpty() || descTokens.isEmpty()) {
            return nameScore * 0.5; // 仅名称分数
        }

        Set<String> intersection = new HashSet<>(queryTokens);
        intersection.retainAll(descTokens);

        double jaccard = (double) intersection.size() /
                (queryTokens.size() + descTokens.size() - intersection.size());

        // 查询词在描述中的覆盖率
        double containment = (double) intersection.size() / queryTokens.size();

        // 综合分数：Jaccard 40% + Containment 40% + Name 20%
        return jaccard * 0.4 + containment * 0.4 + nameScore * 0.2;
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.split("[\\s.,;:!?()\\[\\]{}'\"\\-_/=+@#*\\\\|]+"))
                .map(String::strip)
                .filter(t -> t.length() >= 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 注册单个工具描述向量。
     * <p>生产环境：EmbeddingModel.embed(description) → VectorStore.add()
     */
    public void registerTool(String name, String description) {
        double[] embedding = generateEmbedding(name + " " + description);
        toolStore.put(name, new ToolDescriptor(name, description, embedding));
        log.debug("[T层路由] 工具已注册: {}", name);
    }

    /**
     * 批量注册工具。
     */
    public void registerTools(Map<String, String> tools) {
        tools.forEach(this::registerTool);
        log.info("[T层路由] 批量注册 {} 个工具", tools.size());
    }

    /**
     * 移除工具。
     */
    public void unregisterTool(String name) {
        toolStore.remove(name);
        log.debug("[T层路由] 工具已移除: {}", name);
    }

    /**
     * 批量移除工具。
     */
    public void unregisterTools(Collection<String> names) {
        names.forEach(this::unregisterTool);
    }

    /**
     * 获取当前已注册工具数量。
     */
    public int size() {
        return toolStore.size();
    }

    /**
     * 生成向量——生产环境替换为 EmbeddingModel.embed()。
     * <p>当前实现为简化的 hash embedding（8 维），仅用于模式演示。
     * 生产环境应使用 text-embedding-3-small（1536 维）等。
     */
    private double[] generateEmbedding(String text) {
        double[] vec = new double[VECTOR_DIM];
        for (int i = 0; i < VECTOR_DIM; i++) {
            vec[i] = Math.sin(text.hashCode() * (i + 1) * 0.01) * 0.5 + 0.5;
        }
        // 归一化
        double norm = 0.0;
        for (double v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < VECTOR_DIM; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    /** 工具描述内部记录 */
    record ToolDescriptor(String name, String description, double[] embedding) {}

    // getters/setters for configuration
    public int getDefaultTopK() { return defaultTopK; }
    public void setDefaultTopK(int defaultTopK) { this.defaultTopK = Math.clamp(defaultTopK, 1, 10); }
}

package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 * ⚠️ PRODUCTION WARNING: This VectorStore uses mock string-hash "embeddings" (not real vector embeddings).
 * For production use, you MUST:
 *   1. Replace the hash-based embedding with a real embedding API (e.g., text-embedding-3-small, text-embedding-4-large)
 *   2. Replace the in-memory Map storage with a production vector database (e.g., Pinecone, Milvus, pgvector, Weaviate)
 *   3. Implement proper ANN (Approximate Nearest Neighbor) indexing for scale
 *
 * Example production setup:
 *   EmbeddingModel embedding = new EmbeddingModel("text-embedding-3-small");
 *   VectorStore store = PineconeVectorStore.builder()
 *       .embeddingModel(embedding)
 *       .index("agent-knowledge")
 *       .build();
 */

/**
 * 向量存储管理器。
 * <p>对应书中 Ch06 §6.5 —— 语义向量存储与相似度检索。
 * <p>支持文档嵌入、向量索引和相似度搜索：
 * <ul>
 *   <li><b>文档嵌入</b>：将文本转换为向量嵌入（模拟嵌入服务）</li>
 *   <li><b>向量存储</b>：持久化向量及其元数据</li>
 *   <li><b>相似度检索</b>：基于余弦相似度的最近邻搜索</li>
 *   <li><b>增量更新</b>：支持文档的增删改和索引重建</li>
 * </ul>
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    /**
     * 嵌入向量。
     */
    public record Embedding(
            String embeddingId,
            String documentId,
            List<Double> vector,
            int dimension,
            String model,
            double norm,
            Instant createdAt
    ) {}

    /**
     * 向量文档。
     */
    public record VectorDocument(
            String documentId,
            String content,
            String contentType,
            Map<String, Object> metadata,
            Embedding embedding,
            Instant indexedAt,
            Instant updatedAt
    ) {}

    /**
     * 搜索结果。
     */
    public record SearchResult(
            String documentId,
            String content,
            double similarity,
            Map<String, Object> metadata,
            int rank
    ) {}

    /**
     * 存储配置。
     */
    public record StoreConfig(
            int vectorDimension,
            String embeddingModel,
            double similarityThreshold,
            int topKResults,
            boolean enableIncrementalUpdate
    ) {
        public static StoreConfig defaultConfig() {
            return new StoreConfig(
                    1536,           // 默认 1536 维（text-embedding-3-small）
                    "text-embedding-3-small",
                    0.6,            // 相似度阈值
                    10,             // Top-K 结果
                    true
            );
        }
    }

    private final StoreConfig config;

    /** 文档存储 */
    private final Map<String, VectorDocument> documents = new ConcurrentHashMap<>();

    /** 向量索引：documentId → embedding */
    private final Map<String, Embedding> vectorIndex = new ConcurrentHashMap<>();

    /** 文档 ID → 反向引用 */
    private final Map<String, List<String>> contentIndex = new ConcurrentHashMap<>();

    /** 最近一次搜索结果（调试用） */
    private volatile List<SearchResult> lastSearchResults;

    public VectorStore() {
        this(StoreConfig.defaultConfig());
    }

    public VectorStore(StoreConfig config) {
        this.config = config;
        log.info("[VectorStore] ========== 向量存储初始化 ==========");
        log.info("[VectorStore] 配置参数: dimension={}, model={}, threshold={}, topK={}",
                config.vectorDimension(), config.embeddingModel(),
                config.similarityThreshold(), config.topKResults());
        log.info("[VectorStore] ========== 初始化完成 ==========");
    }

    /**
     * 存储文档并生成嵌入。
     */
    public VectorDocument storeDocument(String documentId, String content,
                                        String contentType, Map<String, Object> metadata) {
        log.info("[VectorStore] ========== 存储文档 ==========");
        log.info("[VectorStore] 文档参数: documentId={}, contentType={}, contentLength={}",
                documentId, contentType, content != null ? content.length() : 0);

        if (documentId == null || documentId.isBlank()) {
            log.error("[VectorStore] 存储失败: 文档ID为空");
            throw new IllegalArgumentException("文档ID不能为空");
        }
        if (content == null || content.isBlank()) {
            log.error("[VectorStore] 存储失败: 内容为空, documentId={}", documentId);
            throw new IllegalArgumentException("内容不能为空");
        }

        // 检查文档是否已存在
        boolean isNew = !documents.containsKey(documentId);
        if (!isNew) {
            log.warn("[VectorStore] 文档已存在，将被覆盖: documentId={}, oldContentLength={}",
                    documentId, documents.get(documentId).content().length());
        }

        // 生成嵌入向量
        Embedding embedding = generateEmbedding(documentId, content);

        // 构建文档
        Instant now = Instant.now();
        VectorDocument document = new VectorDocument(
                documentId, content, contentType != null ? contentType : "text/plain",
                metadata != null ? Map.copyOf(metadata) : Map.of(),
                embedding,
                isNew ? now : documents.get(documentId).indexedAt(),
                now
        );

        documents.put(documentId, document);
        vectorIndex.put(documentId, embedding);

        // 关键词索引
        indexContentKeywords(documentId, content);

        log.info("[VectorStore] ========== 文档存储完成 ==========");
        log.info("[VectorStore] 存储详情: documentId={}, embeddingId={}, vectorDimension={}, totalDocuments={}",
                documentId, embedding.embeddingId(), embedding.dimension(), documents.size());

        return document;
    }

    /**
     * 批量存储文档。
     */
    public List<VectorDocument> storeDocuments(List<DocumentInput> inputs) {
        log.info("[VectorStore] ========== 批量存储文档 ==========");
        log.info("[VectorStore] 批量数量: count={}", inputs.size());

        List<VectorDocument> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (DocumentInput input : inputs) {
            try {
                VectorDocument doc = storeDocument(
                        input.documentId(), input.content(),
                        input.contentType(), input.metadata()
                );
                results.add(doc);
                successCount++;
            } catch (Exception e) {
                log.error("[VectorStore] 批量存储异常: documentId={}, error={}",
                        input.documentId(), e.getMessage());
                failCount++;
            }
        }

        log.info("[VectorStore] ========== 批量存储完成 ==========");
        log.info("[VectorStore] 结果统计: total={}, success={}, fail={}",
                inputs.size(), successCount, failCount);

        return results;
    }

    /**
     * 删除文档。
     */
    public boolean deleteDocument(String documentId) {
        log.info("[VectorStore] 删除文档: documentId={}", documentId);

        if (documentId == null || documentId.isBlank()) {
            log.warn("[VectorStore] 文档ID为空，删除失败");
            return false;
        }

        VectorDocument removed = documents.remove(documentId);
        if (removed == null) {
            log.warn("[VectorStore] 文档不存在，删除失败: documentId={}", documentId);
            return false;
        }

        vectorIndex.remove(documentId);
        contentIndex.remove(documentId);

        log.info("[VectorStore] 文档删除完成: documentId={}, remainingDocuments={}", documentId, documents.size());
        return true;
    }

    /**
     * 相似度搜索。
     */
    public List<SearchResult> search(String query, int topK) {
        log.info("[VectorStore] ========== 开始向量搜索 ==========");
        log.info("[VectorStore] 搜索参数: queryLength={}, topK={}, threshold={}",
                query != null ? query.length() : 0, topK, config.similarityThreshold());

        if (query == null || query.isBlank()) {
            log.warn("[VectorStore] 搜索查询为空");
            return Collections.emptyList();
        }

        if (vectorIndex.isEmpty()) {
            log.warn("[VectorStore] 向量索引为空，无结果");
            return Collections.emptyList();
        }

        // 生成查询向量
        Embedding queryEmbedding = generateEmbedding("query-" + UUID.randomUUID().toString().substring(0, 8), query);

        // 计算余弦相似度
        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, Embedding> entry : vectorIndex.entrySet()) {
            String docId = entry.getKey();
            Embedding docEmbedding = entry.getValue();

            double similarity = cosineSimilarity(queryEmbedding.vector(), docEmbedding.vector());

            if (similarity >= config.similarityThreshold()) {
                VectorDocument doc = documents.get(docId);
                if (doc != null) {
                    results.add(new SearchResult(
                            docId, doc.content(), similarity, doc.metadata(), 0
                    ));
                }
            }
        }

        // 按相似度排序
        results.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));

        // 取 Top-K
        int limit = Math.min(topK, results.size());
        List<SearchResult> topResults = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            topResults.add(new SearchResult(
                    results.get(i).documentId(),
                    results.get(i).content(),
                    results.get(i).similarity(),
                    results.get(i).metadata(),
                    i + 1
            ));
        }

        lastSearchResults = topResults;

        log.info("[VectorStore] ========== 向量搜索完成 ==========");
        log.info("[VectorStore] 搜索结果: totalIndexed={}, aboveThreshold={}, returned={}, maxSimilarity={}",
                vectorIndex.size(), results.size(), topResults.size(),
                topResults.isEmpty() ? 0 : topResults.get(0).similarity());

        if (!topResults.isEmpty()) {
            for (SearchResult result : topResults) {
                log.debug("[VectorStore] 搜索命中: rank={}, docId={}, similarity={}, contentPreview={}",
                        result.rank(), result.documentId(), result.similarity(),
                        result.content().length() > 80 ? result.content().substring(0, 80) + "..." : result.content());
            }
        }

        return topResults;
    }

    /**
     * 关键词 + 向量混合搜索。
     */
    public List<SearchResult> hybridSearch(String query, int topK, double keywordWeight) {
        log.info("[VectorStore] ========== 开始混合搜索 ==========");
        log.info("[VectorStore] 混合搜索参数: queryLength={}, topK={}, keywordWeight={}",
                query != null ? query.length() : 0, topK, keywordWeight);

        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // 1. 向量搜索
        List<SearchResult> vectorResults = search(query, topK * 2);
        Map<String, Double> vectorScores = new HashMap<>();
        for (SearchResult r : vectorResults) {
            vectorScores.put(r.documentId(), r.similarity());
        }

        // 2. 关键词搜索
        List<SearchResult> keywordResults = keywordSearch(query, topK * 2);
        Map<String, Double> keywordScores = new HashMap<>();
        for (SearchResult r : keywordResults) {
            keywordScores.put(r.documentId(), r.similarity());
        }

        // 3. 融合排序
        Set<String> allDocIds = new LinkedHashSet<>();
        allDocIds.addAll(vectorScores.keySet());
        allDocIds.addAll(keywordScores.keySet());

        List<SearchResult> fusedResults = new ArrayList<>();
        for (String docId : allDocIds) {
            double vectorScore = vectorScores.getOrDefault(docId, 0.0);
            double keywordScore = keywordScores.getOrDefault(docId, 0.0);

            // 加权融合
            double fusedScore = keywordWeight * keywordScore + (1 - keywordWeight) * vectorScore;

            if (fusedScore >= config.similarityThreshold()) {
                VectorDocument doc = documents.get(docId);
                if (doc != null) {
                    fusedResults.add(new SearchResult(
                            docId, doc.content(), fusedScore, doc.metadata(), 0
                    ));
                }
            }
        }

        fusedResults.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));

        int limit = Math.min(topK, fusedResults.size());
        List<SearchResult> topResults = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            topResults.add(new SearchResult(
                    fusedResults.get(i).documentId(),
                    fusedResults.get(i).content(),
                    fusedResults.get(i).similarity(),
                    fusedResults.get(i).metadata(),
                    i + 1
            ));
        }

        log.info("[VectorStore] ========== 混合搜索完成 ==========");
        log.info("[VectorStore] 融合结果: vectorHits={}, keywordHits={}, fused={}",
                vectorResults.size(), keywordResults.size(), topResults.size());

        return topResults;
    }

    /**
     * 关键词搜索。
     */
    public List<SearchResult> keywordSearch(String query, int topK) {
        log.debug("[VectorStore] 关键词搜索: query={}, topK={}", query, topK);

        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String lowerQuery = query.toLowerCase();
        List<SearchResult> results = new ArrayList<>();

        for (VectorDocument doc : documents.values()) {
            String lowerContent = doc.content().toLowerCase();

            // 计算关键词匹配度
            int matchCount = 0;
            String[] queryWords = lowerQuery.split("[\\s,，。？！?！.。]+");
            for (String word : queryWords) {
                if (word.length() >= 2 && lowerContent.contains(word)) {
                    matchCount++;
                }
            }

            double score = queryWords.length > 0
                    ? (double) matchCount / queryWords.length
                    : 0;

            if (score > 0) {
                results.add(new SearchResult(
                        doc.documentId(), doc.content(), score, doc.metadata(), 0
                ));
            }
        }

        results.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));

        int limit = Math.min(topK, results.size());
        List<SearchResult> topResults = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            topResults.add(new SearchResult(
                    results.get(i).documentId(), results.get(i).content(),
                    results.get(i).similarity(), results.get(i).metadata(), i + 1
            ));
        }

        return topResults;
    }

    /**
     * 根据 ID 获取文档。
     */
    public Optional<VectorDocument> getDocument(String documentId) {
        Optional<VectorDocument> result = Optional.ofNullable(documents.get(documentId));
        if (result.isPresent()) {
            log.debug("[VectorStore] 获取文档成功: documentId={}, contentLength={}",
                    documentId, result.get().content().length());
        } else {
            log.warn("[VectorStore] 文档不存在: documentId={}, availableIds={}", documentId, documents.keySet());
        }
        return result;
    }

    /**
     * 获取存储统计。
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDocuments", documents.size());
        stats.put("totalVectors", vectorIndex.size());
        stats.put("vectorDimension", config.vectorDimension());
        stats.put("embeddingModel", config.embeddingModel());

        // 内容类型分布
        Map<String, Integer> typeDistribution = new LinkedHashMap<>();
        for (VectorDocument doc : documents.values()) {
            typeDistribution.merge(doc.contentType(), 1, Integer::sum);
        }
        stats.put("contentTypes", typeDistribution);

        // 平均文档长度
        double avgLength = documents.values().stream()
                .mapToInt(d -> d.content().length())
                .average()
                .orElse(0);
        stats.put("avgDocumentLength", avgLength);

        return stats;
    }

    /**
     * 清除所有数据。
     */
    public void clear() {
        log.info("[VectorStore] 清除所有数据: documents={}", documents.size());
        documents.clear();
        vectorIndex.clear();
        contentIndex.clear();
        log.info("[VectorStore] 清除完成");
    }

    // ---- 内部方法 ----

    /**
     * 模拟嵌入生成。
     * <p>实际实现应调用嵌入模型 API。
     */
    private Embedding generateEmbedding(String docId, String content) {
        int dim = config.vectorDimension();
        double[] vector = new double[dim];

        // 内容感知的嵌入生成：基于关键词分布确定向量方向
        // 核心思路：共享关键词的文档在相同维度上有高权重 → 余弦相似度高
        String lowerContent = content.toLowerCase();
        String[] words = lowerContent.split("[\\s,，。？！?！.。;；:：\\n\\r\\t]+");

        // 关键词频率统计
        Map<String, Integer> wordFreq = new HashMap<>();
        for (String word : words) {
            if (word.length() >= 2) {
                wordFreq.merge(word, 1, Integer::sum);
            }
        }

        // 基于关键词分布构建向量
        // 每个关键词映射到固定的维度段（通过哈希确保确定性）
        // 共享关键词 → 相同维度有高权重 → 高余弦相似度
        for (Map.Entry<String, Integer> entry : wordFreq.entrySet()) {
            String word = entry.getKey();
            int freq = entry.getValue();
            // 哈希到 [0, dim/2) 的范围，保证关键词集中在前半维度
            int wordHash = Math.abs(word.hashCode());
            int dimIndex = wordHash % (dim / 2);
            // 在相邻维度也设置权重（提高信号强度）
            vector[dimIndex] += freq * 10.0;
            vector[(dimIndex + 1) % (dim / 2)] += freq * 5.0;
            vector[(dimIndex + 7) % (dim / 2)] += freq * 3.0;
        }

        // 添加非常小的随机噪声（避免完全稀疏向量）
        Random random = new Random(content.hashCode());
        for (int i = 0; i < dim; i++) {
            vector[i] += random.nextGaussian() * 0.01;
        }

        // 归一化
        double norm = 0;
        for (int i = 0; i < dim; i++) {
            norm += vector[i] * vector[i];
        }
        norm = Math.sqrt(norm);

        List<Double> vectorList = new ArrayList<>(dim);
        for (int i = 0; i < dim; i++) {
            vectorList.add(norm == 0 ? 0 : vector[i] / norm);
        }

        return new Embedding(
                "emb-" + UUID.randomUUID().toString().substring(0, 8),
                docId, vectorList, dim, config.embeddingModel(), norm, Instant.now()
        );
    }

    /**
     * 计算余弦相似度。
     */
    private double cosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        if (vectorA.size() != vectorB.size()) {
            log.warn("[VectorStore] 向量维度不匹配: dimA={}, dimB={}", vectorA.size(), vectorB.size());
            return 0.0;
        }

        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < vectorA.size(); i++) {
            dotProduct += vectorA.get(i) * vectorB.get(i);
            normA += vectorA.get(i) * vectorA.get(i);
            normB += vectorB.get(i) * vectorB.get(i);
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0 : dotProduct / denominator;
    }

    /**
     * 索引内容关键词。
     */
    private void indexContentKeywords(String documentId, String content) {
        Set<String> keywords = new LinkedHashSet<>();
        String[] words = content.toLowerCase().split("[\\s,，。？！?！.。;；:：\\n\\r]+");
        for (String word : words) {
            if (word.length() >= 2) {
                keywords.add(word);
            }
        }
        contentIndex.put(documentId, new ArrayList<>(keywords));
    }

    /**
     * 文档输入记录。
     */
    public record DocumentInput(
            String documentId,
            String content,
            String contentType,
            Map<String, Object> metadata
    ) {
        public DocumentInput(String id, String content) {
            this(id, content, "text/plain", Map.of());
        }
    }

    public StoreConfig getConfig() {
        return config;
    }
}

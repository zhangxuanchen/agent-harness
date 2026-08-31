package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内部 RAG 服务。
 * <p>对应书中 Ch06 §6.5.2 内部 RAG —— C 层记忆系统的检索增强入口。
 * 完整管线：
 * <pre>
 *  用户 query
 *    │
 *    ▼
 *  ① Hybrid Search: VectorStore.hybridSearch (语义 + BM25 关键词，Top-50)
 *    │
 *    ▼
 *  ② Rerank: CrossEncoderReranker.rerankChunks (Top-50 → Top-20)
 *    │
 *    ▼
 *  ③ alreadySurfaced 去重 + 最近工具入门文档过滤
 *    │
 *    ▼
 *  ④ MemoryCandidateSelector 小模型 Side Query（宁缺毋滥，最多 5 条）
 *    │
 *    ▼
 *  ⑤ 返回 selected + 注入到上下文
 * </pre>
 */
@Service
public class InternalRAGService {

    private static final Logger log = LoggerFactory.getLogger(InternalRAGService.class);

    private final VectorStore vectorStore;
    private final KeywordSearchIndex keywordIndex;
    private final CrossEncoderReranker reranker;
    private final Optional<MemoryCandidateSelector> selector;

    /** 已展示的 chunk id 集合：避免跨轮重复注入相同记忆占名额 */
    private final Set<String> alreadySurfaced = ConcurrentHashMap.newKeySet();

    /** 关键词入门文档识别关键字 */
    private static final List<String> TOOL_DOC_MARKERS = List.of(
            "使用说明", "入门", "快速开始", "API 文档", "API文档",
            "how to use", "getting started", "api reference", "quick start");

    /** 未装配 MemoryCandidateSelector（教学骨架）时的回退构造。 */
    public InternalRAGService(VectorStore vectorStore, KeywordSearchIndex keywordIndex,
                               CrossEncoderReranker reranker) {
        this(vectorStore, keywordIndex, reranker, null);
    }

    public InternalRAGService(VectorStore vectorStore, KeywordSearchIndex keywordIndex,
                               CrossEncoderReranker reranker, MemoryCandidateSelector selector) {
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
        this.reranker = reranker;
        this.selector = Optional.ofNullable(selector);
    }

    /**
     * 检索结果块。对应书中"召回候选"数据结构。
     */
    public record RetrievedChunk(
            String id,
            String content,
            double relevanceScore,
            Map<String, Object> metadata
    ) {}

    /**
     * 内部 RAG 三参数主入口。和书中 KP 6.5.2 签名完全一致。
     *
     * @param query       本轮用户 query
     * @param topK        最终输出数量上限（通常 5）
     * @param recentTools 本轮最近 10 条已使用工具名（用于过滤入门文档型记忆）
     */
    public List<RetrievedChunk> retrieve(String query, int topK,
                                          List<String> recentTools) {
        log.info("[InternalRAG] 检索开始: query={}, topK={}", truncate(query, 60), topK);

        // 1. Hybrid Search: VectorStore.hybridSearch (语义 + BM25 关键词，0.5 等权重) Top-50
        List<RetrievedChunk> hybrid = hybridSearch(query, 50);
        log.debug("[InternalRAG] Hybrid Search 召回: {}", hybrid.size());

        // 2. Rerank: CrossEncoder 精排 Top-20
        List<RetrievedChunk> reranked = reranker.rerankChunks(query, hybrid, 20);
        log.debug("[InternalRAG] Rerank 后: {}", reranked.size());

        // 3. 去重 + 最近工具入门文档过滤
        List<RetrievedChunk> filtered = reranked.stream()
                .filter(c -> !alreadySurfaced.contains(c.id()))   // alreadySurfaced 去重
                .filter(c -> !isToolReferenceDoc(c, recentTools))  // 入门文档过滤
                .toList();
        log.debug("[InternalRAG] 过滤后: {}", filtered.size());

        // 4. MemoryCandidateSelector: manifest + 小模型 Side Query（宁缺毋滥，最多 topK）
        List<RetrievedChunk> selected;
        if (selector.isPresent() && !filtered.isEmpty()) {
            List<Map<String, Object>> manifest = filtered.stream()
                    .limit(20)
                    .map(c -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", c.id());
                        m.put("type", c.metadata().getOrDefault("contentType", "unknown"));
                        m.put("description", c.metadata().getOrDefault("description",
                                truncate(c.content(), 150)));
                        return m;
                    }).toList();
            List<String> selectedIds = selector.get().select(query, manifest, topK);
            Map<String, RetrievedChunk> byId = filtered.stream()
                    .collect(Collectors.toMap(RetrievedChunk::id, c -> c, (a, b) -> a));
            selected = selectedIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        } else {
            // 降级：没装配 selector，按 Top-`topK` 取 reranked head
            selected = filtered.stream().limit(topK).toList();
        }

        selected.forEach(c -> alreadySurfaced.add(c.id()));
        log.info("[InternalRAG] 最终选中: {}", selected.size());
        return selected;
    }

    /** 便捷入口（KP 6.5.2 下方 buildAugmentedContext 仍使用的两参数版本） */
    public List<RetrievedChunk> retrieve(String query, int topK) {
        return retrieve(query, topK, List.of());
    }

    /**
     * 生成注入到 LLM 的增强上下文文本。
     * <p>KP 6.5.2 内部 RAG 管线最后一步：把 Selected Chunk 格式化成多行
     * 记忆内容文本，和 query 一起喂给主模型。
     */
    public String buildAugmentedContext(String query, int topK) {
        List<RetrievedChunk> chunks = retrieve(query, topK);
        if (chunks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("### 相关记忆片段\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            sb.append(String.format(Locale.ROOT, "[%d] (id=%s score=%.3f) %s%n",
                    i + 1, c.id(), c.relevanceScore(), c.content()));
        }
        return sb.toString();
    }

    /**
     * Hybrid Search：VectorStore.hybridSearch 语义召回 + KeywordSearchIndex 关键词召回
     * 合并去重。输出按 relevanceScore 降序。
     * <p>keywordWeight 取 0.5，向量语义和关键词权重相等。
     */
    List<RetrievedChunk> hybridSearch(String query, int topK) {
        List<VectorStore.SearchResult> dense = vectorStore.hybridSearch(query, topK, 0.5);
        // 关键词分支：KeywordSearchIndex 返回文档 id，再从 VectorStore 取正文和 metadata 拼接
        List<String> kwIds = keywordIndex.search(query, topK);
        Map<String, VectorStore.VectorDocument> docsById = vectorStoreBatchFetch(kwIds);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        List<RetrievedChunk> all = new ArrayList<>();
        for (VectorStore.SearchResult r : dense) {
            if (!seen.add(r.documentId())) continue;
            all.add(toChunk(r.documentId(), r.content(), r.similarity(), r.metadata()));
        }
        for (String id : kwIds) {
            if (!seen.add(id)) continue;
            VectorStore.VectorDocument d = docsById.get(id);
            if (d == null) continue;
            double score = d.metadata() == null ? 0.2 :
                    Optional.ofNullable(d.metadata().get("bm25_score"))
                            .map(n -> ((Number) n).doubleValue()).orElse(0.2);
            all.add(toChunk(id, d.content(), score, d.metadata()));
        }
        all.sort(Comparator.comparingDouble(RetrievedChunk::relevanceScore).reversed());
        int limit = Math.min(topK, all.size());
        return limit <= 0 ? List.of() : all.subList(0, limit);
    }

    private Map<String, VectorStore.VectorDocument> vectorStoreBatchFetch(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<String, VectorStore.VectorDocument> out = new HashMap<>();
        for (String id : ids) {
            vectorStore.getDocument(id).ifPresent(d -> out.put(id, d));
        }
        return out;
    }

    /** 入门文档型记忆过滤：命中 "API 文档 / 入门" 等关键词且当前已在使用该工具，不重复注入。 */
    private boolean isToolReferenceDoc(RetrievedChunk chunk, List<String> recentTools) {
        if (recentTools == null || recentTools.isEmpty()) return false;
        Object ct = chunk.metadata().get("contentType");
        Object desc = chunk.metadata().get("description");
        if ("reference".equals(ct)) return true;
        String content = chunk.content() + " " + (desc == null ? "" : desc);
        String lower = content.toLowerCase(Locale.ROOT);
        boolean isIntroDoc = TOOL_DOC_MARKERS.stream().anyMatch(m -> lower.contains(m.toLowerCase(Locale.ROOT)));
        // 如果是"警告/坑点"类记忆，即使是工具文档也不能过滤掉。只过滤纯入门。
        boolean isWarning = lower.contains("注意") || lower.contains("warning")
                || lower.contains("坑") || lower.contains("caveat");
        return isIntroDoc && !isWarning;
    }

    private static RetrievedChunk toChunk(String id, String content, double score, Map<String, Object> meta) {
        return new RetrievedChunk(id, content, score, meta == null ? Map.of() : meta);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

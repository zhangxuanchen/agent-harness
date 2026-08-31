package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于关键词的搜索索引。
 * <p>对应书中 Ch06 §6.4 —— 混合检索中的关键词召回分支。
 * <p>构建倒排索引，支持 BM25 风格的关键词匹配，
 * 与 {@link VectorStore} 的语义检索融合以提升召回质量。
 */
@Component
public class KeywordSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchIndex.class);

    private final Map<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();
    private final Map<String, String> documents = new ConcurrentHashMap<>();

    /**
     * 索引文档。
     *
     * @param documentId 文档 ID
     * @param content    文档内容
     */
    public void index(String documentId, String content) {
        if (documentId == null || content == null) {
            return;
        }
        documents.put(documentId, content);
        for (String token : tokenize(content)) {
            invertedIndex.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(documentId);
        }
        log.debug("索引文档: id={}, tokens={}", documentId, tokenize(content).size());
    }

    /**
     * 关键词搜索。
     *
     * @param query 查询文本
     * @param topK  返回数量
     * @return 命中文档 ID 列表
     */
    public List<String> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, Integer> docScores = new HashMap<>();
        for (String token : tokenize(query)) {
            Set<String> docs = invertedIndex.get(token);
            if (docs == null) {
                continue;
            }
            for (String docId : docs) {
                docScores.merge(docId, 1, Integer::sum);
            }
        }
        return docScores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 移除文档索引。
     *
     * @param documentId 文档 ID
     */
    public void remove(String documentId) {
        documents.remove(documentId);
        invertedIndex.values().forEach(set -> set.remove(documentId));
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        String[] words = text.toLowerCase().split("[\\s,，。？！?！.。;；:：\\n\\r\\t]+");
        for (String word : words) {
            if (word.length() >= 2) {
                tokens.add(word);
            }
        }
        return tokens;
    }
}

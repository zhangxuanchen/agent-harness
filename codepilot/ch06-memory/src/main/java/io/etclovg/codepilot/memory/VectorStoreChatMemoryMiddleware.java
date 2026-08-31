package io.etclovg.codepilot.memory;

import org.springframework.stereotype.Component;

/**
 * 向量存储聊天记忆 Middleware。
 * <p>对应书中 Ch06 C 层 —— 向量记忆中间件，基于向量数据库的长期记忆。
 * <p>将对话历史向量化存储，支持语义相似度检索。
 */
@Component
public class VectorStoreChatMemoryMiddleware {

    private final VectorStore vectorStore;

    public VectorStoreChatMemoryMiddleware(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 存储对话到向量记忆。
     */
    public void store(String sessionId, String userMessage, String assistantResponse) {
        String combined = userMessage + "\n" + assistantResponse;
        vectorStore.storeDocument(
            "msg-" + sessionId + "-" + System.currentTimeMillis(),
            combined,
            "text/plain",
            java.util.Map.of("sessionId", sessionId)
        );
    }

    /**
     * 从向量记忆中检索相关上下文。
     */
    public String retrieve(String query, int topK) {
        var results = vectorStore.search(query, topK);
        return results.stream()
            .map(r -> r.content())
            .reduce("", (a, b) -> a + "\n" + b);
    }
}

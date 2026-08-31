package io.etclovg.codepilot.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 上下文预算与记忆压缩
 * 对应书中 Ch06 §C 层 — 上下文与记忆
 *
 * <p>上下文窗口经济学：
 * Agent 的每一步都包含之前所有步骤的累积上下文，
 * 形成等差数列求和：第 N 步的输入量是第 1 步的 O(N) 倍。
 * 不加控制则最终超过模型上下文上限导致截断或成本失控。
 */
@Component
public class ContextBudgetManager {

    private final int maxContextTokens;
    private final Map<String, Integer> contextUsageByAgent = new LinkedHashMap<>();

    public ContextBudgetManager(@Value("${codepilot.memory.budget.max-total-tokens:128000}") int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    /**
     * 计算当前上下文使用率
     */
    public double getUtilizationRate(String agentId) {
        int used = contextUsageByAgent.getOrDefault(agentId, 0);
        return (double) used / maxContextTokens;
    }

    /**
     * 上下文压缩：按重要性排序，丢弃低优先级内容
     */
    public List<String> compressContext(List<String> messages, double keepRatio) {
        int keepCount = (int) (messages.size() * keepRatio);
        if (keepCount >= messages.size()) {
            return new ArrayList<>(messages);
        }

        List<String> compressed = new ArrayList<>();
        compressed.add("[上下文压缩] 保留最近 " + keepCount + " 条消息");
        compressed.addAll(messages.subList(messages.size() - keepCount, messages.size()));
        return compressed;
    }

    /**
     * 关键信息提取：从历史对话中提取核心记忆
     */
    public Map<String, Object> extractKeyMemories(List<String> conversation) {
        Map<String, Object> memories = new LinkedHashMap<>();
        List<String> decisions = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        List<String> userPreferences = new ArrayList<>();

        for (String msg : conversation) {
            if (msg.contains("决定") || msg.contains("decision")) {
                decisions.add(msg);
            } else if (msg.contains("用户") || msg.contains("user")) {
                userPreferences.add(msg);
            }
        }

        memories.put("decisions", decisions);
        memories.put("user_preferences", userPreferences);
        memories.put("conversation_length", conversation.size());

        return memories;
    }

    /**
     * O(N²) 上下文膨胀预估
     */
    public long estimateContextAtStep(int step, long initialTokens) {
        return initialTokens * (long) step * (step + 1) / 2;
    }

    /**
     * 丢失中间问题缓解：采用滑动窗口+关键信息摘要
     */
    public List<String> applySlidingWindow(List<String> messages, int windowSize) {
        if (messages.size() <= windowSize) {
            return new ArrayList<>(messages);
        }

        List<String> result = new ArrayList<>();
        result.add("[摘要] 早期对话摘要: " + messages.get(0));

        int startIdx = messages.size() - windowSize + 1;
        result.addAll(messages.subList(startIdx, messages.size()));

        return result;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }
}

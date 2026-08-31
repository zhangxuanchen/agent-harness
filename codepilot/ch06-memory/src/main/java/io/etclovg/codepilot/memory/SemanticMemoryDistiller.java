package io.etclovg.codepilot.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 语义记忆蒸馏器。
 * <p>对应书中 Ch06 §6.2.3 语义记忆 —— 从情景记忆中周期性提取高频模式，沉淀为通用语义知识。
 *
 * <p>蒸馏流程：
 * <pre>
 *  情景记忆条目 → [证据统计（离线可跑，默认）→ evidence >= 3 归为同一 pattern]
 *             → [可选：便宜小模型（如 Gemini Flash）做文本润色]
 *             → 双存储（向量库 + VectorStore 关键词索引）
 * </pre>
 */
@Component
public class SemanticMemoryDistiller {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryDistiller.class);

    /** 证据统计法：两个条目描述共享的"有效关键词"数阈值，达到视为同一 pattern 的证据 */
    static final int MIN_SHARED_KEYWORDS = 3;

    /** 形成一个语义记忆所需的证据条数（与书中 prompt 规则一致：evidence_count >= 3） */
    static final int MIN_EVIDENCE = 3;

    /** 蒸馏 prompt 模板：小模型读取情景记忆清单，输出结构化语义记忆条目。 */
    private static final String DISTILL_PROMPT = """
            你是语义记忆蒸馏器。下面是过去一周 Agent 与用户的对话关键事件清单。
            请从中找出**反复出现 3 次以上**的模式，提炼为通用的语义记忆条目。

            每条语义记忆输出 JSON：
            {
              "pattern": "用户偏好小方法（extract method）",
              "evidence_count": 23,
              "first_seen": "2026-07-15",
              "last_seen": "2026-08-10",
              "type": "user_preference",
              "description": "用户在 23 次对话中要求将长方法拆分为多个小方法"
            }

            规则：
            - 只输出 evidence_count >= 3 的模式，单次出现的视为偶然，不蒸馏
            - 描述用具体语言，不要"用户喜欢 X"这种空泛表述
            - 如果没有发现高频模式，返回空数组 []

            情景记忆清单：
            %s
            """;

    private final Optional<ChatClient> chatClient;
    private final VectorStore vectorStore;
    private final EpisodicKnowledgeStore episodicStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 小模型未装配时的降级构造：只能跑 evidenceBasedDistill */
    public SemanticMemoryDistiller(VectorStore vectorStore, EpisodicKnowledgeStore episodicStore) {
        this(null, vectorStore, episodicStore);
    }

    /** 生产构造：传入已配置好的便宜小模型 ChatClient */
    public SemanticMemoryDistiller(ChatClient chatClient, VectorStore vectorStore,
                                    EpisodicKnowledgeStore episodicStore) {
        this.chatClient = Optional.ofNullable(chatClient);
        this.vectorStore = vectorStore;
        this.episodicStore = episodicStore;
    }

    /** 蒸馏结果（KP 6.2.3 定义的 4 字段签名，和书中保持一致）。 */
    public record DistillationResult(int inputCount, int outputCount, double compressionRatio,
                                     List<SemanticMemoryEntry> entries) {}
    public record SemanticMemoryEntry(String pattern, int evidenceCount, String firstSeen,
                                       String lastSeen, String type, String description) {}

    /**
     * 对书中 KP 6.2.3 定义的签名：输入情景记忆条目，输出蒸馏结果 + 双存储。
     *
     * @param conversations 源情景记忆条目列表（来自 EpisodicKnowledgeStore / MemoryFlushManager 沉淀）
     */
    public DistillationResult distill(List<EpisodicKnowledgeStore.KnowledgeEntry> conversations) {
        log.info("[SemanticDistiller] 蒸馏开始: input={}", conversations == null ? 0 : conversations.size());
        int inputCount = conversations == null ? 0 : conversations.size();
        if (inputCount < 10) {
            log.info("[SemanticDistiller] 样本不足 10 条，跳过蒸馏");
            return new DistillationResult(inputCount, 0, 0.0, List.of());
        }

        // 1. 关键词证据统计（默认路径，不调 LLM）
        List<SemanticMemoryEntry> entries = evidenceBasedDistill(conversations).stream()
                .filter(e -> e.evidenceCount() >= MIN_EVIDENCE).collect(Collectors.toList());

        // 2. 可选：若配置了便宜小模型，把情景清单喂给小模型做一次文本润色/补漏
        if (chatClient.isPresent() && !conversations.isEmpty()) {
            String memoryList = conversations.stream()
                    .map(e -> "- [" + e.category() + "] " + e.content())
                    .limit(500)
                    .collect(Collectors.joining("\n"));
            List<SemanticMemoryEntry> fromLlm = distillWithLlm(memoryList);
            fromLlm = fromLlm.stream().filter(e -> e.evidenceCount() >= MIN_EVIDENCE).toList();
            if (!fromLlm.isEmpty()) {
                entries = fromLlm;
            }
        }

        // 3. 双存储：向量语义检索 + VectorStore 关键词索引（已内置 storeDocument 的 hash map + 倒排）
        for (SemanticMemoryEntry entry : entries) {
            double confidence = entry.evidenceCount() >= 10 ? 0.9 : 0.6;
            String docId = "sm-" + Math.abs(entry.pattern().hashCode());
            Map<String, Object> meta = new HashMap<>();
            meta.put("pattern", entry.pattern());
            meta.put("evidence_count", entry.evidenceCount());
            meta.put("confidence", confidence);
            meta.put("first_seen", entry.firstSeen());
            meta.put("last_seen", entry.lastSeen());
            meta.put("type", entry.type());
            vectorStore.storeDocument(docId, entry.description(), "semantic_memory", meta);
        }

        double ratio = inputCount > 0 ? (double) entries.size() / inputCount : 0.0;
        log.info("[SemanticDistiller] 蒸馏完成: input={}, output={}, ratio={}",
                inputCount, entries.size(), String.format(Locale.ROOT, "%.2f", ratio));
        return new DistillationResult(inputCount, entries.size(), ratio, entries);
    }

    /**
     * 基于共享关键词证据的离线蒸馏（不调 LLM）。
     * <p>算法：按 category 分组，组内两两比较描述的共享词数，>= MIN_SHARED_KEYWORDS 归为同一
     * cluster，cluster 大小即 evidence_count。按 evidence_count >= MIN_EVIDENCE 过滤，
     * 输出 SemanticMemoryEntry（first_seen/last_seen 取 cluster 的最早/最晚日期）。
     */
    List<SemanticMemoryEntry> evidenceBasedDistill(List<EpisodicKnowledgeStore.KnowledgeEntry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.systemDefault());

        // 1. 按 category 分组
        Map<EpisodicKnowledgeStore.KnowledgeCategory, List<EpisodicKnowledgeStore.KnowledgeEntry>> byCat =
                entries.stream().collect(Collectors.groupingBy(
                        EpisodicKnowledgeStore.KnowledgeEntry::category));

        List<SemanticMemoryEntry> result = new ArrayList<>();
        for (var kv : byCat.entrySet()) {
            List<EpisodicKnowledgeStore.KnowledgeEntry> group = kv.getValue();
            // 2. 每组构建关键词集合，便于两两比较
            List<Set<String>> kws = group.stream()
                    .map(e -> tokenize(e.content() + " " + e.title()))
                    .collect(Collectors.toList());
            boolean[] used = new boolean[group.size()];
            for (int i = 0; i < group.size(); i++) {
                if (used[i]) continue;
                List<EpisodicKnowledgeStore.KnowledgeEntry> cluster = new ArrayList<>();
                cluster.add(group.get(i));
                used[i] = true;
                Set<String> mergedKw = new HashSet<>(kws.get(i));
                for (int j = i + 1; j < group.size(); j++) {
                    if (used[j]) continue;
                    Set<String> shared = new HashSet<>(mergedKw);
                    shared.retainAll(kws.get(j));
                    if (shared.size() >= MIN_SHARED_KEYWORDS) {
                        cluster.add(group.get(j));
                        used[j] = true;
                        mergedKw.addAll(kws.get(j));
                    }
                }
                if (cluster.size() >= MIN_EVIDENCE) {
                    EpisodicKnowledgeStore.KnowledgeEntry longest = cluster.stream()
                            .max(Comparator.comparingInt(e -> e.content().length()))
                            .orElse(cluster.get(0));
                    String first = dateFmt.format(cluster.stream()
                            .map(EpisodicKnowledgeStore.KnowledgeEntry::createdAt)
                            .min(Comparator.naturalOrder()).orElse(longest.createdAt()));
                    String last = dateFmt.format(cluster.stream()
                            .map(EpisodicKnowledgeStore.KnowledgeEntry::updatedAt)
                            .max(Comparator.naturalOrder()).orElse(longest.updatedAt()));
                    String pattern = longest.title().isEmpty()
                            ? longest.content().substring(0, Math.min(24, longest.content().length())) + "…"
                            : longest.title();
                    String type = longest.category().name().toLowerCase(Locale.ROOT);
                    result.add(new SemanticMemoryEntry(pattern, cluster.size(), first, last, type,
                            longest.content()));
                }
            }
        }
        result.sort(Comparator.comparingInt(SemanticMemoryEntry::evidenceCount).reversed());
        return result;
    }

    /** 轻量分词：按标点、空白切，去掉 1-2 字符噪声，统一小写。 */
    private Set<String> tokenize(String text) {
        if (text == null) return Set.of();
        String[] parts = text.toLowerCase(Locale.ROOT)
                .split("[\\s,.;:!?()\\[\\]{}\"'<>/\\\\\\-+*=&#@$%`~，。；：！？（）【】《》、/\\n\\t\\r]+");
        Set<String> out = new HashSet<>();
        for (String p : parts) {
            if (p.length() >= 2) out.add(p);
        }
        return out;
    }

    /** LLM 版蒸馏（生产开启）。调用 ChatClient.prompt() + ChatOptions 控制输出格式。 */
    private List<SemanticMemoryEntry> distillWithLlm(String memoryList) {
        String prompt = String.format(DISTILL_PROMPT, memoryList);
        try {
            ChatClient client = chatClient.orElseThrow();
            String content = client.prompt()
                    .user(prompt)
                    .options(ChatOptions.builder()
                            .model("gemini-2.5-flash")
                            .maxTokens(2048)
                            .build())
                    .call()
                    .content();
            return parseEntries(content);
        } catch (Exception e) {
            log.warn("[SemanticDistiller] 小模型蒸馏失败，保留 evidence-based 结果: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 周期蒸馏入口（默认关闭）。生产在 application.yml 设置：
     * <pre>
     * codepilot:
     *   semantic:
     *     distill:
     *       cron: "0 0 2 ? * MON"   # 每周一凌晨 2 点
     * </pre>
     */
    @Scheduled(cron = "${codepilot.semantic.distill.cron:-}")
    public void scheduledDistill() {
        List<EpisodicKnowledgeStore.KnowledgeEntry> entries = episodicStore == null
                ? List.of() : episodicStore.getActiveEntries();
        DistillationResult r = distill(entries);
        log.info("[SemanticDistiller] 周期蒸馏完成: input={}, output={}",
                r.inputCount(), r.outputCount());
    }

    /** JSON 反序列化：把 {"selected_ids":[...]} 或条目数组转成 Java 对象列表。 */
    private List<SemanticMemoryEntry> parseEntries(String json) {
        try {
            String trimmed = json.trim();
            int start = trimmed.indexOf('[');
            int end = trimmed.lastIndexOf(']');
            if (start < 0 || end <= start) return List.of();
            String arr = trimmed.substring(start, end + 1);
            List<Map<String, Object>> list = objectMapper.readValue(arr,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<SemanticMemoryEntry> out = new ArrayList<>();
            for (Map<String, Object> m : list) {
                String pattern = String.valueOf(m.getOrDefault("pattern", ""));
                int evidence = ((Number) m.getOrDefault("evidence_count", 0)).intValue();
                String first = String.valueOf(m.getOrDefault("first_seen", ""));
                String last = String.valueOf(m.getOrDefault("last_seen", ""));
                String type = String.valueOf(m.getOrDefault("type", ""));
                String desc = String.valueOf(m.getOrDefault("description", ""));
                out.add(new SemanticMemoryEntry(pattern, evidence, first, last, type, desc));
            }
            return out;
        } catch (Exception e) {
            log.warn("[SemanticDistiller] 解析蒸馏输出失败: {}", e.getMessage());
            return List.of();
        }
    }
}

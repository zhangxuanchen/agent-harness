package io.etclovg.codepilot.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 配套仓库教学骨架（对应书中 KP 6.5.2），非 AgentScope 框架内置。
 * <p>
 * 内部 RAG 的 Selector 阶段：拿到 Top-20 的 manifest（filename + description + type），
 * 用便宜小模型做一次 side query，挑选"明确有用"的最多 5 条记忆，宁缺毋滥。
 * <p>
 * 若 Spring AI ChatClient 未配置（未指定 model provider），则自动降级为"按 relevance 取 Top-5"的
 * 启发式选择，保证在最小依赖下也能跑通。
 */
@Component
public class MemoryCandidateSelector {

    private static final Logger log = LoggerFactory.getLogger(MemoryCandidateSelector.class);

    /** 单个 manifest 条目上限（description 截断长度），避免 manifest 过长 */
    static final int MAX_DESC_CHARS_PER_LINE = 150;

    /** Selector 单次选择输出上限 */
    static final int MAX_SELECTED = 5;

    private final Optional<ChatClient> chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 未装配 ChatClient bean 时使用的降级构造 */
    public MemoryCandidateSelector() {
        this(null);
    }

    /** 生产装配：传入 ChatClient（默认小模型），在 pom.xml 声明对应的 spring-ai-*-starter 即可。 */
    public MemoryCandidateSelector(ChatClient chatClient) {
        this.chatClient = Optional.ofNullable(chatClient);
    }

    /**
     * 从候选 manifest 中选出 "明确有用" 的条目。
     *
     * @param query       用户本轮 query
     * @param manifest    manifest 列表（size 通常是 Top-20）。每个 map 至少包含
     *                    id / description / type 三个键
     * @param maxReturned 最大返回条数，通常为 MAX_SELECTED=5
     * @return 被选中条目的 id 列表（保证不超过 maxReturned，可能为空）
     */
    public List<String> select(String query, List<Map<String, Object>> manifest, int maxReturned) {
        if (manifest == null || manifest.isEmpty()) return List.of();
        int max = Math.min(maxReturned, MAX_SELECTED);

        // 降级路径：ChatClient 未配置 → 按候选出现顺序（假设上游已按相关性排序）取 Top-max
        if (chatClient.isEmpty()) {
            log.debug("[MemorySelector] ChatClient 未配置，fallback 按 Top-{} 选择", max);
            return manifest.stream().limit(max)
                    .map(m -> String.valueOf(m.getOrDefault("id", "")))
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        String manifestText = buildManifestText(manifest);
        List<String> selected = selectWithLlm(query, manifestText, max);
        // 防御：模型可能输出 manifest 之外的 id，做一次交集过滤
        Set<String> validIds = manifest.stream()
                .map(m -> String.valueOf(m.getOrDefault("id", "")))
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        selected = selected.stream().filter(validIds::contains).limit(max).toList();
        log.info("[MemorySelector] 候选={}, 选中={}", manifest.size(), selected.size());
        return selected;
    }

    private String buildManifestText(List<Map<String, Object>> manifest) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < manifest.size(); i++) {
            Map<String, Object> m = manifest.get(i);
            String id = String.valueOf(m.getOrDefault("id", ""));
            String type = String.valueOf(m.getOrDefault("type", "unknown"));
            String desc = String.valueOf(m.getOrDefault("description", ""));
            if (desc.length() > MAX_DESC_CHARS_PER_LINE) {
                desc = desc.substring(0, MAX_DESC_CHARS_PER_LINE) + "…";
            }
            sb.append(String.format(Locale.ROOT, "%d. id=%s  type=%s  description=%s%n",
                    i + 1, id, type, desc.replace("\n", " ")));
        }
        return sb.toString();
    }

    private List<String> selectWithLlm(String query, String manifestText, int max) {
        String system = """
                你是记忆候选 Selector。任务：从下面的记忆 manifest 中，挑出最能帮助用户回答
                本轮 query 的最多 %d 条记忆。

                规则（务必遵守）：
                - 只挑"明确有用"的，不确定就不选，宁缺毋滥。
                - 输出严格 JSON：{"selected_ids": ["id1", "id2", ...]}
                - 选中数量不得超过 %d。
                - 如果 manifest 里没有有用的内容，返回 {"selected_ids": []}。
                """.formatted(max, max);

        String user = "当前用户 query:\n%s\n\n记忆 manifest:\n%s".formatted(query, manifestText);

        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(user)
            ));
            String content = chatClient.orElseThrow()
                    .prompt(prompt)
                    .call()
                    .content();
            // 解析 JSON
            String trimmed = content.trim();
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = trimmed.substring(start, end + 1);
                Map<String, Object> map = objectMapper.readValue(json,
                        new TypeReference<Map<String, Object>>() {});
                Object selected = map.get("selected_ids");
                if (selected instanceof List<?> list) {
                    return list.stream().map(String::valueOf).toList();
                }
            }
        } catch (Exception e) {
            // optional=true 语义：失败降级空列表，不影响主流程
            log.warn("[MemorySelector] Selector 调用失败，fallback 空列表: {}", e.getMessage());
        }
        return List.of();
    }
}

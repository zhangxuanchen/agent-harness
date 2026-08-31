package io.etclovg.codepilot.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Token 优化器：通过 Prompt 压缩、去冗余、缓存友好化三管齐下减少 token 消耗。
 *
 * <p>对应书中 Ch12 §12.1 KP 12.1.2 + §12.4 KP 12.4.1：
 * <ul>
 *   <li>KP 12.1.2：上下文 O(N²) 膨胀 → 输入 token 占 70-85% 总费用。
 *       TokenOptimizer 是 System Prompt 层面的一阶优化：
 *       optimizeSystemPrompt 去除冗余修饰词（~节省 5-10% 输入 token），
 *       长期运行在 Ch11 ModelSelectionTriangle 预算模型中累计节省显著。</li>
 *   <li>KP 12.4.1：Prefix Caching 的配套预处理。stablePrefix 越短，
 *       1,024-token 分块的 checkpoint 数量越少（Anthropic 上限 4 个），
 *       前缀中因动态内容导致缓存失效的概率越低。
 *       optimizeSystemPrompt 生成的紧凑 System Prompt 是 Prefix Caching 的"饲料"。</li>
 * </ul>
 *
 * <p>行业通用近似（estimateTokens）：1 token ≈ 4 UTF-8 bytes。中英混合场景
 * 误差约 15%；要更精确请接入 tiktoken/JTokkit 等 tokenizer SDK，
 * 本类保留 estimateTokens 为轻量估算，生产可覆写此方法。
 */
@Component
public class TokenOptimizer {

    private static final Logger log = LoggerFactory.getLogger(TokenOptimizer.class);

    /** KP 12.1.2 识别出的"对模型性能无贡献但拉长提示词"的冗余修饰词（中英文混合）。 */
    private static final Pattern FILLER_PATTERN = Pattern.compile(
            "\\b(highly|completely|totally|absolutely|fully|extremely|very|strictly|really|truly)\\b" +
            "|非常|十分|完全|绝对|彻底|极其|真的",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * 两阶段优化：去冗余修饰 + 压缩多空白 → 紧凑 System Prompt。
     *
     * <p>工程纪律（KP 12.4.1）：若启用 Prefix Caching，
     * <b>务必在设置 stablePrefix 之前</b>调用本方法，否则浪费 checkpoint 槽位。
     */
    public String optimizeSystemPrompt(String prompt) {
        if (prompt == null || prompt.isEmpty()) return prompt;
        String out = FILLER_PATTERN.matcher(prompt).replaceAll("");
        out = out.replaceAll("[ \\t]+", " ")         // 去多余空白（保留换行作为语义边界）
                 .replaceAll("(?m)^[ \\t]+", "")     // 去行首空白
                 .replaceAll("(?m)[ \\t]+$", "")     // 去行尾空白
                 .replaceAll("\n{3,}", "\n\n");      // 去 3 个及以上空行
        int before = estimateTokens(prompt);
        int after  = estimateTokens(out);
        double pct  = before == 0 ? 0.0 : 100.0 * (before - after) / before;
        log.info("[TokenOptimizer §12.1.2/§12.4.1] System Prompt：{} tokens → {} tokens，节省 {:.1f}%",
                before, after, pct);
        return out;
    }

    /**
     * 超长提示压缩（超阈值 → 分句保留前 N 个有效句 + 尾部补省略标记）。
     * 对应 KP 12.1.2 ReAct 循环 O(N²) 溢出时的应急截断：
     * 用户消息 / 工具调用链过长时，由 ch06 ContextCompactor 在语义压缩之前
     * 先用本方法做粗粒度裁剪，避免直接进入 LLM 超窗口报错。
     */
    public String compressPrompt(String prompt, int maxTokens) {
        if (prompt == null || prompt.isEmpty()) return prompt;
        if (estimateTokens(prompt) <= maxTokens) return prompt;
        // 按中英文常见句结束符分句；取前 M 句直到不超过 maxTokens * 4 字节
        String[] sentences = prompt.split("(?<=[。.!?！？])");
        List<String> kept = new ArrayList<>();
        int budget = maxTokens * 4;
        int used = 0;
        for (String s : sentences) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            int sz = trimmed.getBytes(StandardCharsets.UTF_8).length;
            if (used + sz > budget && !kept.isEmpty()) break;
            kept.add(trimmed);
            used += sz;
        }
        String result = String.join("", kept);
        // KP 12.1.2 纪律：压缩必须显式告知模型上下文已被裁剪，防止它基于"完整上下文"的错觉做推理
        return result + "\n\n[注意：以上内容为压缩后的摘要，完整上下文见 sessionId 工作记忆条目（ch06情景记忆检索）]";
    }

    /**
     * 行业通用近似估算：1 token ≈ 4 UTF-8 bytes。中英混合场景误差约 ±15%。
     * <p>如果需要严格准确（计费/预算预警场景），请改为调用底层 tokenizer（如 jtokkit）。
     */
    public int estimateTokens(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length / 4;
    }
}

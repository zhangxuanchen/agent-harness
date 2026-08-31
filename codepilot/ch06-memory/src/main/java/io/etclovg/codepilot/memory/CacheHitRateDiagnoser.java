package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 缓存命中率诊断器。
 * 配套仓库教学实现，非 AgentScope 内置。
 *
 * 对应书中 KP 6.3.2 "KV-cache 怎么设计稳定前缀"的缓存击穿诊断环节。
 * KV-cache 的本质是复用前缀 token，一旦系统提示中混入了动态内容（时间戳、随机数），
 * 稳定前缀就会被污染，每轮请求缓存全部作废，成本直接翻倍。
 */
@Component
public class CacheHitRateDiagnoser {

    private static final Logger log = LoggerFactory.getLogger(CacheHitRateDiagnoser.class);

    /**
     * 污染稳定前缀的典型动态关键词。
     * 这些词出现在系统提示中意味着每一次请求前缀都会变化。
     */
    private static final Set<String> DYNAMIC_KEYWORDS = new HashSet<>(List.of(
            "current_time", "Date.now()", "System.currentTimeMillis()",
            "timestamp", "random", "Math.random()", "UUID.randomUUID",
            "new Date()", "LocalDateTime.now()", "Instant.now()",
            "$(date)", "${time}", "时间戳", "当前时间", "日期"
    ));

    /**
     * 诊断缓存是否被污染。
     * 对比最近 N 次请求的系统提示，如果开头相同部分越来越短，
     * 或者系统提示中包含动态关键词，说明缓存正在被击穿。
     *
     * @param systemPrompt  当前请求的系统提示
     * @param recentPrompts 最近 N 次请求的系统提示（用于对比前缀稳定性）
     * @return 诊断结果，包含命中率估算、污染源、修复建议
     */
    public CacheDiagnosisResult diagnose(String systemPrompt, List<String> recentPrompts) {
        List<String> pollutionSources = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        // 检查 1：系统提示是否包含动态关键词
        for (String kw : DYNAMIC_KEYWORDS) {
            if (systemPrompt != null && systemPrompt.contains(kw)) {
                pollutionSources.add("系统提示含动态关键词: " + kw);
                recommendations.add("移除系统提示中的 '" + kw + "'，改为在 user message 中注入");
            }
        }

        // 检查 2：最近请求的公共前缀长度是否稳定
        double estimatedHitRate = 1.0;
        if (recentPrompts != null && recentPrompts.size() >= 2) {
            int commonPrefix = commonPrefixLength(recentPrompts);
            int avgLength = recentPrompts.stream().mapToInt(String::length).sum() / recentPrompts.size();
            estimatedHitRate = avgLength == 0 ? 0.0 : (double) commonPrefix / avgLength;

            if (estimatedHitRate < 0.5) {
                pollutionSources.add("最近请求公共前缀仅 " + (int) (estimatedHitRate * 100) + "%，缓存不稳定");
                recommendations.add("将系统提示拆成'静态段 + 动态段'，静态段放在最前面保持不变");
            }
        }

        // 检查 3：系统提示是否包含每次都变的 session_id 等
        if (systemPrompt != null && systemPrompt.matches(".*session_?id[=:][a-zA-Z0-9]{6,}.*")) {
            pollutionSources.add("系统提示含 session_id，每次会话都会变化");
            recommendations.add("把 session_id 移到工具历史区或 user message 末尾，不要放在系统提示里");
        }

        if (pollutionSources.isEmpty()) {
            recommendations.add("当前无明显污染，持续监控前缀公共长度");
        }

        log.info("[CacheDiagnose] 命中率估算 {}.0%，污染源 {} 个，建议 {} 条",
                (int) (estimatedHitRate * 100), pollutionSources.size(), recommendations.size());

        return new CacheDiagnosisResult(estimatedHitRate, pollutionSources, recommendations);
    }

    /**
     * 返回缓存击穿排查清单。
     * 一线工程师在缓存命中率异常下降时，按此清单逐项检查即可定位根因。
     */
    public List<String> generateChecklist() {
        return List.of(
                "1. 系统提示里是否有 current_time / Date.now() / timestamp 等动态字段",
                "2. 工具定义区是否在每次请求时重新排序或动态增删",
                "3. session_id / request_id 是不是被拼进了系统提示首部",
                "4. CLAUDE.md / MEMORY.md 是否在会话过程中频繁修改导致前缀变化",
                "5. 分桶压缩的边界位置是否跨过了 cache breakpoint（Anthropic）"
        );
    }

    /**
     * 计算多个字符串的最长公共前缀长度。
     * 公共前缀越长，KV-cache 复用率越高。
     */
    private int commonPrefixLength(List<String> strs) {
        if (strs.isEmpty()) return 0;
        String first = strs.get(0);
        int max = first.length();
        for (int i = 1; i < strs.size(); i++) {
            String s = strs.get(i);
            int j = 0;
            while (j < max && j < s.length() && first.charAt(j) == s.charAt(j)) {
                j++;
            }
            max = j;
            if (max == 0) break;
        }
        return max;
    }

    /**
     * 缓存诊断结果。
     * @param hitRateEstimate   命中率估算（0~1）
     * @param pollutionSources  污染源列表（定位什么内容破坏了稳定前缀）
     * @param recommendations   修复建议清单
     */
    public record CacheDiagnosisResult(
            double hitRateEstimate,
            List<String> pollutionSources,
            List<String> recommendations
    ) {}
}

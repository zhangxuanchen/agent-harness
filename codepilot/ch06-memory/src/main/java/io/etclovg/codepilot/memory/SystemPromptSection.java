package io.etclovg.codepilot.memory;

import java.util.List;

/**
 * 系统提示片段。
 * <p>表示系统提示中的一个可组合片段，支持稳定性分类与动态内容检测。
 * 供 {@link CacheAwareSystemPromptBuilder} 构建 KV-cache 友好的系统提示使用。
 *
 * <p>页面参考：Ch6 §6.3.1 KV-cache 与系统提示工程
 */
public record SystemPromptSection(
        String name,
        String content,
        int priority,
        SectionType type,
        boolean isStable,
        boolean isDynamic
) {

    public boolean containsDynamicContent() {
        return type == SectionType.DYNAMIC || isDynamic;
    }

    public ValidationResult validate() {
        if (content == null || content.isEmpty()) {
            return new ValidationResult(false, "内容为空");
        }
        return new ValidationResult(true, null);
    }

    public int estimateTokens() {
        return content == null ? 0 : (int) Math.ceil(content.length() / 4.0);
    }

    public static SystemPromptSection tools(String toolDefinitions) {
        return new SystemPromptSection("tools", toolDefinitions, 10, SectionType.DYNAMIC, false, true);
    }

    public static SystemPromptSection behaviorRules(String rules) {
        return new SystemPromptSection("behavior", rules, 20, SectionType.STABLE, true, false);
    }

    public enum SectionType {
        STABLE,       // 稳定前缀，适合 KV-cache
        SEMI_STABLE,  // 会话级别常量
        DYNAMIC       // 每次变化
    }

    public record ValidationResult(boolean isValid, String errorMessage) {}
}
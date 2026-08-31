package io.etclovg.codepilot.memory;

import java.util.regex.Pattern;

/**
 * 上下文预算辅助工具。
 * <p>提供共享的正则模式和 token 估算工具，供 {@link WorkingMemoryManager} 和
 * {@link WorkingMemoryEntry} 等 C 层组件使用。
 *
 * <p>页面参考：Ch6 §6.2.1 工作记忆 / §6.5.1 压缩策略
 */
public class ContextBudgetAdvisor {

    /** 数字常量保留正则（文件路径、端口、IP、配置等） */
    public static final Pattern NUMERIC_PRESERVE = Pattern.compile(
            "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|:\\d{2,5}|\\b\\d{4,}\\b)");

    private ContextBudgetAdvisor() {
    }

    /**
     * 默认 token 估算器：按字符数估算（约 4 字符 / token）。
     */
    public static TokenCountEstimator defaultEstimator() {
        return text -> text == null || text.isEmpty() ? 0 : (int) Math.ceil(text.length() / 4.0);
    }

    @FunctionalInterface
    public interface TokenCountEstimator {
        int estimate(String text);
    }
}
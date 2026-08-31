package io.etclovg.codepilot.memory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * C 层 · 工作记忆条目。
 *
 * <p>对应 Ch6 §6.2.1 工作记忆的定义——容量有限但访问延迟接近零的短期记忆。
 * 每个条目代表工作记忆中的一轮对话或状态快照。
 *
 * <h3>工作记忆条目的关键属性</h3>
 * <ul>
 *   <li><b>id</b>: 唯一标识符，用于追踪和去重</li>
 *   <li><b>role</b>: 角色（user/assistant/tool）</li>
 *   <li><b>content</b>: 原文内容</li>
 *   <li><b>timestamp</b>: 时间戳，用于时间衰减和排序</li>
 *   <li><b>importance</b>: 重要性评分 [0.0-1.0]，用于压缩决策</li>
 *   <li><b>turnNumber</b>: 轮次编号，用于滑动窗口定位</li>
 *   <li><b>metadata</b>: 元数据（如 tool_name、is_error 等）</li>
 * </ul>
 *
 * <p><b>页面参考</b>：Ch6 §6.2.1 工作记忆 / §6.3.2 滑动窗口机制
 *
 * @see WorkingMemoryManager
 * @see MemoryManager.WorkingTurn
 */
public record WorkingMemoryEntry(
        /** 唯一标识符 */
        String id,

        /** 角色：user / assistant / tool */
        String role,

        /** 原文内容 */
        String content,

        /** 时间戳 */
        Instant timestamp,

        /** 重要性评分 [0.0-1.0] */
        double importance,

        /** 轮次编号（从 1 开始） */
        int turnNumber,

        /** 元数据（可扩展） */
        Map<String, Object> metadata
) {
    /**
     * 创建一个基础的工作记忆条目。
     *
     * @param role 角色
     * @param content 内容
     * @param turnNumber 轮次编号
     * @return 工作记忆条目
     */
    public static WorkingMemoryEntry of(String role, String content, int turnNumber) {
        return new WorkingMemoryEntry(
                UUID.randomUUID().toString(),
                role,
                content,
                Instant.now(),
                calculateDefaultImportance(role, content),
                turnNumber,
                Map.of()
        );
    }

    /**
     * 创建带有元数据的工作记忆条目。
     *
     * @param role 角色
     * @param content 内容
     * @param turnNumber 轮次编号
     * @param metadata 元数据
     * @return 工作记忆条目
     */
    public static WorkingMemoryEntry of(String role, String content, int turnNumber,
                                        Map<String, Object> metadata) {
        return new WorkingMemoryEntry(
                UUID.randomUUID().toString(),
                role,
                content,
                Instant.now(),
                calculateDefaultImportance(role, content),
                turnNumber,
                metadata != null ? metadata : Map.of()
        );
    }

    /**
     * 创建带有自定义重要性的工作记忆条目。
     *
     * @param role 角色
     * @param content 内容
     * @param turnNumber 轮次编号
     * @param importance 重要性评分
     * @return 工作记忆条目
     */
    public static WorkingMemoryEntry of(String role, String content, int turnNumber,
                                        double importance) {
        return new WorkingMemoryEntry(
                UUID.randomUUID().toString(),
                role,
                content,
                Instant.now(),
                importance,
                turnNumber,
                Map.of()
        );
    }

    /**
     * 计算默认重要性评分。
     * <p>规则：
     * <ul>
     *   <li>user 消息：默认较高（0.8）——包含用户意图</li>
     *   <li>包含错误的工具结果：高重要性（0.9）——需要重试或处理</li>
     *   <li>包含数字常量（文件路径、端口）：较高（0.7）——关键事实</li>
     *   <li>普通 assistant 推理：中等（0.5）</li>
     * </ul>
     */
    private static double calculateDefaultImportance(String role, String content) {
        if (content == null) return 0.3;

        // User 消息优先级高
        if ("user".equals(role)) return 0.8;

        // 包含错误或关键信息
        String lower = content.toLowerCase();
        if (lower.contains("error") || lower.contains("exception") ||
            lower.contains("failed") || lower.contains("错误")) {
            return 0.9;
        }

        // 包含数字常量（文件路径、端口、IP等）
        if (ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(content).find()) {
            return 0.7;
        }

        // 默认
        return "assistant".equals(role) ? 0.5 : 0.4;
    }

    /**
     * 判断是否为用户消息。
     */
    public boolean isUserMessage() {
        return "user".equals(role);
    }

    /**
     * 判断是否为助手消息。
     */
    public boolean isAssistantMessage() {
        return "assistant".equals(role);
    }

    /**
     * 判断是否为工具消息。
     */
    public boolean isToolMessage() {
        return "tool".equals(role) || "tool_result".equals(role);
    }

    /**
     * 判断是否包含错误。
     */
    public boolean containsError() {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return lower.contains("error") || lower.contains("exception") ||
               lower.contains("failed") || lower.contains("错误") ||
               lower.contains("异常") || lower.contains("失败");
    }

    /**
     * 判断是否包含关键数字常量（文件路径、端口、IP 等）。
     */
    public boolean containsNumericPreserve() {
        return content != null && ContextBudgetAdvisor.NUMERIC_PRESERVE.matcher(content).find();
    }

    /**
     * 获取元数据中的指定值。
     *
     * @param key 键名
     * @return 值（不存在则返回 null）
     */
    public Object getMetadata(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    /**
     * 获取元数据中的字符串值。
     *
     * @param key 键名
     * @return 字符串值（不存在则返回 null）
     */
    public String getMetadataAsString(String key) {
        Object value = getMetadata(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 估算内容的 token 数量。
     *
     * @return 估算的 token 数量
     */
    public int estimateTokens() {
        return ContextBudgetAdvisor.defaultEstimator().estimate(content);
    }
}
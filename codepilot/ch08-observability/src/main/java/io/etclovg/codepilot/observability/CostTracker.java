package io.etclovg.codepilot.observability;

import io.etclovg.codepilot.core.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * O 层 · 多维成本追踪器。
 *
 * <p>实现五标签（User / Session / Task / Model / Tool）精细粒度成本归因，
 * 对应书中 Ch8 §8.3 成本可观测性设计。
 *
 * <p><b>五标签模型</b>：
 * <pre>
 * User    — 哪个用户消耗了资源
 * Session — 哪个会话消耗了资源
 * Task    — 哪个任务消耗了资源
 * Model   — 哪个模型消耗了资源
 * Tool    — 哪个工具消耗了资源
 * </pre>
 *
 * <p>每个标签维护独立的累计用量和费用，支持多维度聚合查询。
 * 定价基于模型/工具的实际费率（外部定价表注入）。
 *
 * <p><b>页面参考</b>：Ch8 §8.3 多维成本追踪
 */
@Component
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    // ==================== 五标签追踪结构 ====================

    /** 标签值 → 用量聚合 */
    private final Map<Label, Map<String, UsageEntry>> usage = new ConcurrentHashMap<>();

    /** 模型名 → 每百万 token 单价（美元） */
    private final Map<String, ModelPricing> modelPricing = new ConcurrentHashMap<>();
    /** 工具名 → 每次调用单价（美元） */
    private final Map<String, BigDecimal> toolPricing = new ConcurrentHashMap<>();

    /** 默认模型定价（未匹配模型的 fallback） */
    private static final ModelPricing DEFAULT_PRICING = new ModelPricing(
            BigDecimal.valueOf(0.002), BigDecimal.valueOf(0.006)
    );

    public CostTracker() {
        // 初始化五标签存储
        for (Label label : Label.values()) {
            usage.put(label, new ConcurrentHashMap<>());
        }
        // 预置常见模型定价
        modelPricing.put("gpt-4o", new ModelPricing(
                BigDecimal.valueOf(0.005), BigDecimal.valueOf(0.015)));
        modelPricing.put("gpt-4o-mini", new ModelPricing(
                BigDecimal.valueOf(0.00015), BigDecimal.valueOf(0.0006)));
        modelPricing.put("gpt-4-turbo", new ModelPricing(
                BigDecimal.valueOf(0.01), BigDecimal.valueOf(0.03)));
        modelPricing.put("claude-3.5-sonnet", new ModelPricing(
                BigDecimal.valueOf(0.003), BigDecimal.valueOf(0.015)));
        modelPricing.put("deepseek-v3", new ModelPricing(
                BigDecimal.valueOf(0.00027), BigDecimal.valueOf(0.0011)));
    }

    /**
     * 记录一次模型调用消耗。
     *
     * @param userId       用户标识
     * @param sessionId    会话标识
     * @param taskId       任务标识
     * @param modelName    模型名称
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    public void recordModelUsage(String userId, String sessionId, String taskId,
                                  String modelName, long inputTokens, long outputTokens) {
        ModelPricing pricing = modelPricing.getOrDefault(modelName, DEFAULT_PRICING);
        BigDecimal inputCost = pricing.inputPrice()
                .multiply(BigDecimal.valueOf(inputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
        BigDecimal outputCost = pricing.outputPrice()
                .multiply(BigDecimal.valueOf(outputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
        BigDecimal totalCost = inputCost.add(outputCost);

        // 累加到各标签
        if (userId != null) addUsage(Label.USER, userId, inputTokens, outputTokens, inputCost.add(outputCost));
        if (sessionId != null) addUsage(Label.SESSION, sessionId, inputTokens, outputTokens, inputCost.add(outputCost));
        if (taskId != null) addUsage(Label.TASK, taskId, inputTokens, outputTokens, inputCost.add(outputCost));
        if (modelName != null) addUsage(Label.MODEL, modelName, inputTokens, outputTokens, inputCost.add(outputCost));

        log.debug("[O层·成本] 模型调用: model={}, tokens_in={}, tokens_out={}, cost=${}",
                modelName, inputTokens, outputTokens, totalCost);
    }

    /**
     * 记录一次工具调用消耗。
     *
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @param taskId    任务标识
     * @param toolName  工具名称
     * @param callCount 调用次数
     */
    public void recordToolUsage(String userId, String sessionId, String taskId,
                                 String toolName, int callCount) {
        BigDecimal unitPrice = toolPricing.getOrDefault(toolName, BigDecimal.ZERO);
        BigDecimal totalCost = unitPrice.multiply(BigDecimal.valueOf(callCount));

        if (userId != null) addUsage(Label.USER, userId, 0, 0, totalCost);
        if (sessionId != null) addUsage(Label.SESSION, sessionId, 0, 0, totalCost);
        if (taskId != null) addUsage(Label.TASK, taskId, 0, 0, totalCost);
        if (toolName != null) addUsage(Label.TOOL, toolName, 0, 0, totalCost);

        log.debug("[O层·成本] 工具调用: tool={}, calls={}, cost=${}", toolName, callCount, totalCost);
    }

    /**
     * 获取指定标签下的用量详情。
     */
    public UsageEntry getUsage(Label label, String key) {
        Map<String, UsageEntry> map = usage.get(label);
        return map != null ? map.get(key) : null;
    }

    /**
     * 获取指定标签下所有用量（用于聚合报表）。
     */
    public Map<String, UsageEntry> getAllUsage(Label label) {
        Map<String, UsageEntry> map = usage.get(label);
        return map != null ? Map.copyOf(map) : Map.of();
    }

    /**
     * 获取全局累计总费用。
     */
    public BigDecimal getTotalCost() {
        Map<String, UsageEntry> taskUsage = usage.get(Label.TASK);
        if (taskUsage == null) return BigDecimal.ZERO;

        return taskUsage.values().stream()
                .map(UsageEntry::totalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 获取指定标签下的汇总费用。
     */
    public BigDecimal getCostByLabel(Label label, String key) {
        UsageEntry entry = getUsage(label, key);
        return entry != null ? entry.totalCost() : BigDecimal.ZERO;
    }

    /**
     * 获取指定用户的总费用。
     */
    public BigDecimal getUserCost(String userId) {
        return getCostByLabel(Label.USER, userId);
    }

    /**
     * 获取指定会话的总费用。
     */
    public BigDecimal getSessionCost(String sessionId) {
        return getCostByLabel(Label.SESSION, sessionId);
    }

    /**
     * 获取指定任务的总费用。
     */
    public BigDecimal getTaskCost(String taskId) {
        return getCostByLabel(Label.TASK, taskId);
    }

    /**
     * 注册模型的定价信息。
     */
    public void registerModelPricing(String modelName, BigDecimal inputPricePerMillion,
                                      BigDecimal outputPricePerMillion) {
        modelPricing.put(modelName, new ModelPricing(inputPricePerMillion, outputPricePerMillion));
        log.info("[O层·成本] 模型定价已注册: {} (输入: ${}/M, 输出: ${}/M)",
                modelName, inputPricePerMillion, outputPricePerMillion);
    }

    /**
     * 注册工具的定价信息。
     */
    public void registerToolPricing(String toolName, BigDecimal pricePerCall) {
        toolPricing.put(toolName, pricePerCall);
        log.info("[O层·成本] 工具定价已注册: {} (${}/次)", toolName, pricePerCall);
    }

    /**
     * 重置所有追踪数据。
     */
    public void reset() {
        log.info("[O层·成本] 所有成本追踪数据已重置");
        for (Map<String, UsageEntry> map : usage.values()) {
            map.clear();
        }
    }

    // ==================== 内部方法 ====================

    private void addUsage(Label label, String key, long inputTokens, long outputTokens,
                           BigDecimal cost) {
        usage.get(label).merge(key,
                new UsageEntry(inputTokens, outputTokens, cost),
                (existing, incoming) -> new UsageEntry(
                        existing.inputTokens + incoming.inputTokens,
                        existing.outputTokens + incoming.outputTokens,
                        existing.totalCost.add(incoming.totalCost)
                ));
    }

    // ==================== 内部类型 ====================

    /** 五标签枚举 */
    public enum Label {
        USER, SESSION, TASK, MODEL, TOOL
    }

    /** 用量条目——累计 token 与费用 */
    public record UsageEntry(long inputTokens, long outputTokens, BigDecimal totalCost) {
        public long totalTokens() { return inputTokens + outputTokens; }

        @Override
        public String toString() {
            return String.format("tokens_in=%d, tokens_out=%d, cost=$%.6f",
                    inputTokens, outputTokens, totalCost);
        }
    }

    /** 模型定价：输入/输出 每百万 token 价格 */
    record ModelPricing(BigDecimal inputPrice, BigDecimal outputPrice) {}
}

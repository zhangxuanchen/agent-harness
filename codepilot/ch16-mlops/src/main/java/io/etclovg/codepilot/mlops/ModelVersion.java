package io.etclovg.codepilot.mlops;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 模型版本记录。对应书中 Ch16 §16.5.1。
 *
 * <p>描述模型注册表中的一个具体模型版本，由 {@link ModelRegistryClient}
 * 从模型提供方 API 拉取后提交给 {@link ModelUpdateManager} 处理。
 * 字段覆盖模型选择的关键决策维度：性能等级、上下文窗口、成本、
 * 发布时间（决定是否有时间做评估）。
 *
 * @param modelId       模型唯一 ID（如 "claude-sonnet-4.6"）
 * @param baseModel     基础模型名（如 "claude-sonnet"）
 * @param version       具体版本号/标签（如 "4.6" 或 "20250601"）
 * @param displayName   显示名称
 * @param contextWindow 上下文窗口大小（tokens）
 * @param inputCostUsdPerM  输入单价（USD / 1M tokens）
 * @param outputCostUsdPerM 输出单价（USD / 1M tokens）
 * @param releaseDate   模型发布时间
 * @param sunsetDate    模型下线时间（旧版本通常在新版本上线后 90 天）
 * @param capabilities  能力标签列表（如 "coding", "reasoning", "tool-use"）
 * @param config        其他配置（RPC endpoint、认证模式、最大并发等）
 */
public record ModelVersion(
        String modelId,
        String baseModel,
        String version,
        String displayName,
        int contextWindow,
        double inputCostUsdPerM,
        double outputCostUsdPerM,
        Instant releaseDate,
        Instant sunsetDate,
        List<String> capabilities,
        Map<String, Object> config
) {
    /**
     * 是否有固定的 sunset 时间窗口过期（即旧版本即将下线）。
     */
    public boolean isSunsetApproaching() {
        if (sunsetDate == null) return false;
        long daysToSunset = java.time.Duration.between(Instant.now(), sunsetDate).toDays();
        return daysToSunset > 0 && daysToSunset <= 30;
    }

    /**
     * 版本标识（书中 §16.5 {@code newVersion.getId()} 调用，等价于 modelId）。
     */
    public String getId() {
        return modelId;
    }

    /**
     * 模型配置（书中 §16.5 {@code newVersion.getConfig()} 调用，等价于 {@link #config()}）。
     */
    public Map<String, Object> getConfig() {
        return config;
    }
}

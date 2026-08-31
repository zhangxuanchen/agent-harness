package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱复用策略。
 * <p>对应书中 Ch04 §4.4.2 —— 根据风险级别决定沙箱复用方式。
 * <p>复用策略的核心逻辑：
 * <ul>
 *   <li>高风险任务：不复用，每次创建新实例</li>
 *   <li>中风险任务：可复用但需清理状态（快照重置）</li>
 *   <li>低风险任务：可直接复用，无需清理</li>
 * </ul>
 * <p>风险级别使用 {@link RiskLevel} 枚举（顶层共享，与 {@link TaskType} 复用），
 * 对应书中 §4.4.2 的 RiskLevel { LOW, MEDIUM, HIGH } 三级模型；
 * 本代码库扩展为四级（增加 CRITICAL），HIGH/CRITICAL 均不允许复用。
 */
@Component
public class SandboxReusePolicy {

    private static final Logger log = LoggerFactory.getLogger(SandboxReusePolicy.class);

    /**
     * 复用决策结果。
     * <p>对应书中 §4.4.2 的 ReuseDecision——描述对某个空闲沙箱实例是否允许被新任务复用，
     * 以及复用方式（直接复用 / 清理后复用 / 销毁 / 创建新实例）。
     *
     * @param canReuse 是否允许复用
     * @param action   复用动作类型
     * @param reason   决策原因描述
     */
    public record ReuseDecision(
            boolean canReuse,
            ReuseAction action,
            String reason
    ) {
        /** 复用动作枚举——对应书中 reuseDirectly / reuseAfterReset / createNew 三种语义 */
        public enum ReuseAction {
            /** 直接复用（对应书中 ReuseDecision.reuseDirectly） */
            REUSE_DIRECT,
            /** 清理后复用（对应书中 ReuseDecision.reuseAfterReset） */
            REUSE_AFTER_CLEANUP,
            /** 不允许复用，销毁旧实例 */
            DESTROY,
            /** 创建新实例（对应书中 ReuseDecision.createNew） */
            CREATE_NEW
        }

        /** 工厂方法：直接复用（对齐书中 ReuseDecision.reuseDirectly） */
        public static ReuseDecision reuseDirectly() {
            return new ReuseDecision(true, ReuseAction.REUSE_DIRECT, "直接复用");
        }

        /** 工厂方法：清理后复用（对齐书中 ReuseDecision.reuseAfterReset） */
        public static ReuseDecision reuseAfterReset() {
            return new ReuseDecision(true, ReuseAction.REUSE_AFTER_CLEANUP, "快照重置后复用");
        }

        /** 工厂方法：创建新实例（对齐书中 ReuseDecision.createNew） */
        public static ReuseDecision createNew() {
            return new ReuseDecision(false, ReuseAction.CREATE_NEW, "创建新实例");
        }

        /** 工厂方法：销毁旧实例 */
        public static ReuseDecision destroy(String reason) {
            return new ReuseDecision(false, ReuseAction.DESTROY, reason);
        }
    }

    /**
     * 风险级别配置——描述每个 {@link RiskLevel} 对应的复用参数。
     *
     * @param allowReuse     是否允许复用
     * @param requireCleanup 复用前是否需要清理（快照重置）
     * @param maxReuseCount  最大复用次数
     * @param maxReuseTimeMs 最大复用时长（毫秒）
     */
    public record RiskLevelConfig(
            boolean allowReuse,
            boolean requireCleanup,
            int maxReuseCount,
            long maxReuseTimeMs
    ) {
        public static RiskLevelConfig highRisk() {
            return new RiskLevelConfig(false, false, 0, 0);
        }
        public static RiskLevelConfig mediumRisk() {
            return new RiskLevelConfig(true, true, 10, java.util.concurrent.TimeUnit.MINUTES.toMillis(30));
        }
        public static RiskLevelConfig lowRisk() {
            return new RiskLevelConfig(true, false, 50, java.util.concurrent.TimeUnit.HOURS.toMillis(4));
        }
    }

    /** 任务隔离记录——用于追踪哪些任务使用过某个沙箱 */
    private final Map<String, List<String>> taskHistory = new ConcurrentHashMap<>();

    /** 按 {@link RiskLevel} 的配置映射（替代原先的 String 键） */
    private final Map<RiskLevel, RiskLevelConfig> riskConfigs = new ConcurrentHashMap<>();

    public SandboxReusePolicy() {
        // 默认配置：LOW → 直接复用，MEDIUM → 清理后复用，HIGH/CRITICAL → 不允许复用
        riskConfigs.put(RiskLevel.LOW, RiskLevelConfig.lowRisk());
        riskConfigs.put(RiskLevel.MEDIUM, RiskLevelConfig.mediumRisk());
        riskConfigs.put(RiskLevel.HIGH, RiskLevelConfig.highRisk());
        riskConfigs.put(RiskLevel.CRITICAL, RiskLevelConfig.highRisk());
    }

    /**
     * 检查沙箱实例是否可以复用（{@link RiskLevel} 枚举版，推荐使用）。
     *
     * @param instance  沙箱实例
     * @param taskId    新任务 ID
     * @param riskLevel 风险等级
     * @return 是否可以复用
     */
    public boolean canReuse(SandboxPool.SandboxInstance instance, String taskId, RiskLevel riskLevel) {
        ReuseDecision decision = evaluateReuse(instance, taskId, riskLevel);
        log.debug("复用决策: sandboxId={}, taskId={}, decision={}, reason={}",
                instance.id(), taskId, decision.action(), decision.reason());
        return decision.canReuse();
    }

    /**
     * 检查沙箱实例是否可以复用（String 版，向后兼容）。
     *
     * @param instance  沙箱实例
     * @param taskId    新任务 ID
     * @param riskLevel 风险等级字符串（low/medium/high/critical）
     * @return 是否可以复用
     * @deprecated 优先使用 {@link #canReuse(SandboxPool.SandboxInstance, String, RiskLevel)}
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public boolean canReuse(SandboxPool.SandboxInstance instance, String taskId, String riskLevel) {
        return canReuse(instance, taskId, RiskLevel.fromString(riskLevel));
    }

    /**
     * 评估复用决策（{@link RiskLevel} 枚举版，推荐使用）。
     * <p>对应书中 §4.4.2 的 evaluate 方法——根据风险级别返回复用决策。
     *
     * @param instance  沙箱实例
     * @param taskId    新任务 ID
     * @param riskLevel 风险等级
     * @return 复用决策
     */
    public ReuseDecision evaluateReuse(SandboxPool.SandboxInstance instance, String taskId, RiskLevel riskLevel) {
        RiskLevelConfig config = riskConfigs.getOrDefault(riskLevel, RiskLevelConfig.mediumRisk());

        // 1. 高风险任务永远不允许复用（对应书中 case HIGH -> createNew）
        if (!config.allowReuse()) {
            return ReuseDecision.destroy("高风险任务不允许复用");
        }

        // 2. 检查最大复用次数
        if (instance.useCount() >= config.maxReuseCount()) {
            return ReuseDecision.destroy(
                    "超过最大复用次数: " + instance.useCount() + "/" + config.maxReuseCount());
        }

        // 3. 检查最大复用时间
        if (config.maxReuseTimeMs() > 0) {
            long lifetime = Instant.now().toEpochMilli() - instance.createdAt().toEpochMilli();
            if (lifetime > config.maxReuseTimeMs()) {
                return ReuseDecision.destroy(
                        "超过最大复用时间: " + lifetime + "ms/" + config.maxReuseTimeMs() + "ms");
            }
        }

        // 4. 检查任务隔离——如果新任务与之前使用过该沙箱的任务有冲突
        List<String> previousTasks = taskHistory.getOrDefault(instance.id(), Collections.emptyList());
        if (previousTasks.contains(taskId)) {
            log.debug("同一任务复用沙箱: sandboxId={}, taskId={}", instance.id(), taskId);
        }

        // 5. 安全级别检查——高安全级别的 Profile 不允许跨任务复用
        if (instance.profile().getSecurityLevel() == SandboxProfile.SecurityLevel.HIGH
                || instance.profile().getSecurityLevel() == SandboxProfile.SecurityLevel.CRITICAL) {
            if (!previousTasks.isEmpty() && !previousTasks.contains(taskId)) {
                return ReuseDecision.destroy("高安全级别沙箱不允许跨任务复用");
            }
        }

        // 6. 决定复用方式（对应书中 case LOW -> reuseDirectly, case MEDIUM -> reuseAfterReset）
        if (config.requireCleanup()) {
            return ReuseDecision.reuseAfterReset();
        } else {
            return ReuseDecision.reuseDirectly();
        }
    }

    /**
     * 评估复用决策（String 版，向后兼容）。
     *
     * @param instance  沙箱实例
     * @param taskId    新任务 ID
     * @param riskLevel 风险等级字符串
     * @return 复用决策
     * @deprecated 优先使用 {@link #evaluateReuse(SandboxPool.SandboxInstance, String, RiskLevel)}
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public ReuseDecision evaluateReuse(SandboxPool.SandboxInstance instance, String taskId, String riskLevel) {
        return evaluateReuse(instance, taskId, RiskLevel.fromString(riskLevel));
    }

    /**
     * 记录任务使用沙箱的历史。
     */
    public void recordTaskUsage(String sandboxId, String taskId) {
        taskHistory.computeIfAbsent(sandboxId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(taskId);
    }

    /**
     * 获取沙箱的任务历史。
     */
    public List<String> getTaskHistory(String sandboxId) {
        return Collections.unmodifiableList(taskHistory.getOrDefault(sandboxId, Collections.emptyList()));
    }

    /**
     * 清除沙箱的任务历史。
     */
    public void clearTaskHistory(String sandboxId) {
        taskHistory.remove(sandboxId);
    }

    /**
     * 更新风险等级配置（{@link RiskLevel} 枚举版）。
     */
    public void updateRiskConfig(RiskLevel riskLevel, RiskLevelConfig config) {
        riskConfigs.put(riskLevel, config);
        log.info("更新风险等级配置: riskLevel={}, allowReuse={}, maxReuseCount={}",
                riskLevel, config.allowReuse(), config.maxReuseCount());
    }

    /**
     * 更新风险等级配置（String 版，向后兼容）。
     *
     * @deprecated 优先使用 {@link #updateRiskConfig(RiskLevel, RiskLevelConfig)}
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public void updateRiskConfig(String riskLevel, RiskLevelConfig config) {
        updateRiskConfig(RiskLevel.fromString(riskLevel), config);
    }

    /**
     * 获取风险等级配置（{@link RiskLevel} 枚举版）。
     */
    public RiskLevelConfig getRiskConfig(RiskLevel riskLevel) {
        return riskConfigs.getOrDefault(riskLevel, RiskLevelConfig.mediumRisk());
    }

    /**
     * 获取风险等级配置（String 版，向后兼容）。
     *
     * @deprecated 优先使用 {@link #getRiskConfig(RiskLevel)}
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public RiskLevelConfig getRiskConfig(String riskLevel) {
        return getRiskConfig(RiskLevel.fromString(riskLevel));
    }

    /**
     * 获取策略统计信息。
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trackedSandboxes", taskHistory.size());
        stats.put("totalTaskUsages", taskHistory.values().stream().mapToInt(List::size).sum());

        Map<String, Object> riskConfigSummary = new LinkedHashMap<>();
        for (Map.Entry<RiskLevel, RiskLevelConfig> entry : riskConfigs.entrySet()) {
            Map<String, Object> configInfo = new LinkedHashMap<>();
            configInfo.put("allowReuse", entry.getValue().allowReuse());
            configInfo.put("requireCleanup", entry.getValue().requireCleanup());
            configInfo.put("maxReuseCount", entry.getValue().maxReuseCount());
            riskConfigSummary.put(entry.getKey().name(), configInfo);
        }
        stats.put("riskConfigs", riskConfigSummary);

        return stats;
    }
}

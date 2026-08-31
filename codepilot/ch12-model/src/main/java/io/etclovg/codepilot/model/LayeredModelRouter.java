package io.etclovg.codepilot.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 分层模型路由器。
 * <p>对应书中 Ch12 §12.3 KP 12.3.1 — 三层模型路由（L1 规则 / L2 分类 / L3 级联升级）。
 *
 * <p>三层分工（与 KP 12.3.1 Mermaid 架构图一致）：
 * <ol>
 *   <li><b>L1 规则路由</b>（微秒级，覆盖 60-70%）：基于 taskType 的关键词映射表
 *       {@link #l1Rules}，命中即直接返回对应 ModelTier，无需 LLM 调用。</li>
 *   <li><b>L2 分类路由</b>（毫秒级，覆盖 20-30%）：L1 未命中时，基于
 *       {@code contextTokens} 上下文长度和 {@code complexity} 复杂度评分，
 *       在 LIGHT / STANDARD / POWER 三层模型间决策。</li>
 *   <li><b>L3 级联升级</b>（百毫秒级，< 10%）：当 L1/L2 路由结果的下游
 *       置信度（V 层 EmbeddedValidationAdvisor 或模型自身 logprob）低于
 *       {@link #l3UpgradeThreshold} 时，升级到更强一级 ModelTier 重试。</li>
 * </ol>
 *
 * <p>本类遵循 Ch11 约定：使用 setter 模式（不使用 fluent builder），
 * {@code @Configuration} 层通过 setter 组装 l1Rules 和阈值，
 * 核心 {@link #route(String, int, double)} 方法按 L1→L2 顺序返回 ModelTier。
 */
@Component
public class LayeredModelRouter {

    private static final Logger log = LoggerFactory.getLogger(LayeredModelRouter.class);

    /**
     * 模型层级枚举 — L2 分类路由的离散输出，L3 级联升级的档位。
     *
     * <p>{@code costPer1k} 仅用于快速估算；真实计费以 ch08 的 CostAttributionMiddleware
     * 按模型 API 返回的 prompt_tokens / completion_tokens 统计为准。
     */
    public enum ModelTier {
        /** 轻量层：低延迟低成本 — Haiku / Flash / qwen-mini */
        LIGHT("lightweight", 0.10),
        /** 标准层：通用平衡 — Sonnet / GPT-5.4 Mini / qwen-plus */
        STANDARD("standard", 0.30),
        /** 强能力层：高质量高成本 — Opus / GPT-5 / qwen-max */
        POWER("powerful", 1.00);

        private final String id;
        private final double costPer1k;

        ModelTier(String id, double costPer1k) {
            this.id = id;
            this.costPer1k = costPer1k;
        }

        public String getId() { return id; }
        public double getCostPer1k() { return costPer1k; }
    }

    /** L1 规则表：taskType（小写）→ ModelTier — setter 可配置。 */
    private Map<String, ModelTier> l1Rules = new HashMap<>();

    /** L3 升级阈值（0-1，默认 0.6）— 置信度 < 该值 → 升级到更强 tier。 */
    private double l3UpgradeThreshold = 0.6;

    /**
     * L2 复杂度阈值：complexity >= L2_POWER_THRESHOLD → POWER；
     * complexity >= L2_STANDARD_THRESHOLD → STANDARD；否则 LIGHT。
     * contextTokens > 16k → POWER；> 4k → STANDARD（与复杂度逻辑取 OR）。
     */
    private static final double L2_POWER_THRESHOLD = 0.7;
    private static final double L2_STANDARD_THRESHOLD = 0.4;
    private static final int L2_POWER_TOKEN_THRESHOLD = 16_000;
    private static final int L2_STANDARD_TOKEN_THRESHOLD = 4_000;

    public LayeredModelRouter() {
        // 默认 L1 规则表（最小可运行示例；生产用 setter 注入业务自定义表）
        l1Rules.put("translation", ModelTier.LIGHT);
        l1Rules.put("summarization", ModelTier.LIGHT);
        l1Rules.put("faq", ModelTier.LIGHT);
        l1Rules.put("format", ModelTier.LIGHT);
        l1Rules.put("code_review", ModelTier.STANDARD);
        l1Rules.put("coding", ModelTier.STANDARD);
        l1Rules.put("analysis", ModelTier.STANDARD);
        l1Rules.put("legal", ModelTier.POWER);
        l1Rules.put("reasoning", ModelTier.POWER);
    }

    /**
     * 三层路由主入口：按 L1 → L2 顺序返回 ModelTier。
     *
     * <p>L3 级联升级不在这里执行，而是由 L 层编排循环的后续 step 根据
     * {@link #shouldEscalate(double)} 结果（或 V 层 validation.correction/retry 信号）
     * 再调用 {@link #upgradeTier(ModelTier)} 升级。
     *
     * @param taskType      任务类型标签（L1 规则路由匹配用，大小写不敏感）
     * @param contextTokens 当前上下文 token 数（L2 分类路由用）
     * @param complexity    预评估复杂度评分 0-1（L2 分类路由用）
     * @return 推荐的 ModelTier（L1 命中优先，否则 L2 分类）
     */
    public ModelTier route(String taskType, int contextTokens, double complexity) {
        // ── L1 规则路由（微秒级） ──
        if (taskType != null && !taskType.isBlank()) {
            ModelTier l1Match = l1Rules.get(taskType.toLowerCase());
            if (l1Match != null) {
                log.debug("[L1 规则路由] taskType={} → tier={}", taskType, l1Match);
                return l1Match;
            }
        }

        // ── L2 分类路由（毫秒级，纯算术无 LLM 调用） ──
        ModelTier tier;
        if (complexity >= L2_POWER_THRESHOLD || contextTokens > L2_POWER_TOKEN_THRESHOLD) {
            tier = ModelTier.POWER;
        } else if (complexity >= L2_STANDARD_THRESHOLD || contextTokens > L2_STANDARD_TOKEN_THRESHOLD) {
            tier = ModelTier.STANDARD;
        } else {
            tier = ModelTier.LIGHT;
        }
        log.debug("[L2 分类路由] tokens={}, complexity={} → tier={}", contextTokens, complexity, tier);
        return tier;
    }

    /**
     * L3 级联升级判断：当前 step 的置信度评分低于阈值时触发升级。
     *
     * <p>调用时机：V 层 EmbeddedValidationAdvisor 返回
     * {@code validation.correction=true} 或 {@code validation.retry=true}
     * 信号后，编排层用当前 step 的 {@code validation.qualityScore/100.0} 作为
     * confidence 调用本方法，true 则升级 tier 重试。
     */
    public boolean shouldEscalate(double confidence) {
        return confidence < l3UpgradeThreshold;
    }

    /**
     * L3 升级档位：LIGHT → STANDARD → POWER；POWER 已到顶返回自身（不再升级）。
     */
    public ModelTier upgradeTier(ModelTier current) {
        return switch (current) {
            case LIGHT -> ModelTier.STANDARD;
            case STANDARD -> ModelTier.POWER;
            case POWER -> ModelTier.POWER;
        };
    }

    // ═══════ setter 模式配置（@Configuration 层注入） ═══════

    public void setL1Rules(Map<String, ModelTier> l1Rules) {
        Map<String, ModelTier> lower = new HashMap<>();
        l1Rules.forEach((k, v) -> lower.put(k.toLowerCase(), v));
        this.l1Rules = lower;
    }

    public void setL3UpgradeThreshold(double threshold) {
        this.l3UpgradeThreshold = threshold;
    }

    public Map<String, ModelTier> getL1Rules() { return Map.copyOf(l1Rules); }
    public double getL3UpgradeThreshold() { return l3UpgradeThreshold; }
}

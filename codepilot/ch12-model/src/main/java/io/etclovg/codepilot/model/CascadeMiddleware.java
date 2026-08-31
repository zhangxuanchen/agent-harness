package io.etclovg.codepilot.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型级联判定器。
 *
 * <p>对应书中 Ch12 §12.3 —— KP 12.3.1（L3 级联升级） + KP 12.3.2（三级降级链的升级方向判定）：
 * <ul>
 *   <li><b>升级方向（Escalation）</b>：当 L1/L2 路由到的模型输出置信度不足时，
 *       调用 {@link #shouldEscalate(double)} 判断是否需要升级到更强一档 ModelTier 重试——
 *       与 {@link LayeredModelRouter#shouldEscalate(double)} 的职责一致，只是
 *       {@code LayeredModelRouter} 负责"路由 + 升级阈值配置"，本类负责"纯置信度判定 + 日志埋点"，
 *       在 L 层编排循环内部由 ReActOrchestrator 显式调用。</li>
 *   <li><b>降级方向（Fallback）</b>：当模型 API 不可用或超时，调用方向"更弱模型/规则兜底"
 *       的切换由 {@link ModelFallbackService} 管理。两个类职责互补：
 *       {@code CascadeMiddleware} 管"向上升级（为了质量）"，
 *       {@code ModelFallbackService} 管"向下降级（为了可用性）"。</li>
 * </ul>
 *
 * <p>本类遵循 Ch11 约定：使用 setter 模式（不使用 fluent builder），
 * {@code @Configuration} 层通过 {@link #setL3UpgradeThreshold(double)} 注入业务阈值，
 * 与 {@link LayeredModelRouter#setL3UpgradeThreshold(double)} 通常配置为同一值，
 * 保证升级判定在路由和编排层保持一致。
 *
 * <p>调用时机（L 层编排循环内部）：V 层 EmbeddedValidationAdvisor 返回
 * {@code validation.correction=true} 或 {@code validation.retry=true} 信号后，
 * 编排层提取 {@code validation.qualityScore / 100.0} 作为 confidence，
 * 调用 {@link #shouldEscalate(double)} 判断是否需要升级 tier 重试——置信度低于阈值则升级，
 * 并调用 {@link LayeredModelRouter#upgradeTier(LayeredModelRouter.ModelTier)} 推到下一档位。
 */
@Component
public class CascadeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(CascadeMiddleware.class);

    /**
     * L3 级联升级阈值（0-1，默认 0.6）。
     * <p>与 {@link LayeredModelRouter#l3UpgradeThreshold} 语义相同：
     * confidence < 该值 → 升级到更强 model tier 重试。
     * <p>生产建议：与 {@code LayeredModelRouter.setL3UpgradeThreshold(...)} 传入同一配置值，
     * 避免路由层和编排层的升级判定出现分裂。
     */
    private double l3UpgradeThreshold = 0.6;

    /**
     * 判断是否需要升级到下一级模型（单参数版本 —— 与 {@link LayeredModelRouter#shouldEscalate(double)} 签名一致）。
     *
     * <p>内部使用 {@link #l3UpgradeThreshold} 作为阈值。业务侧统一通过 setter 注入一次即可，
     * 无需每次调用时传入。
     *
     * @param confidence 当前 step 的置信度（0-1），通常来自 V 层 {@code validation.qualityScore / 100.0}
     * @return 需要升级返回 true（confidence < 阈值），否则返回 false
     */
    public boolean shouldEscalate(double confidence) {
        return shouldEscalate(confidence, l3UpgradeThreshold);
    }

    /**
     * 判断是否需要升级到下一级模型（双参数版本 —— 灵活指定阈值，用于 A/B 实验或单任务特判）。
     *
     * @param confidence 当前模型置信度（0-1），例如 V 层 qualityScore 归一化值或模型 logprob
     * @param threshold  本次调用的临时升级阈值（0-1）；日常业务请使用单参数版本以保持策略一致
     * @return 需要升级返回 true（confidence < 阈值），否则返回 false
     */
    public boolean shouldEscalate(double confidence, double threshold) {
        boolean escalate = confidence < threshold;
        if (escalate) {
            log.debug("[L3 级联升级] 触发：confidence={} < 阈值={} —— 升级到更强一档 model tier",
                    String.format("%.3f", confidence), String.format("%.3f", threshold));
        }
        return escalate;
    }

    // ═══════ setter 模式配置（@Configuration 层注入，与 LayeredModelRouter 保持同值） ═══════

    public void setL3UpgradeThreshold(double threshold) {
        log.info("[CascadeMiddleware] 更新 L3 升级阈值：{} → {}",
                String.format("%.3f", this.l3UpgradeThreshold), String.format("%.3f", threshold));
        this.l3UpgradeThreshold = threshold;
    }

    public double getL3UpgradeThreshold() { return l3UpgradeThreshold; }
}

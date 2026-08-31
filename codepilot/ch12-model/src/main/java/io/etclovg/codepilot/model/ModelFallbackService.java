package io.etclovg.codepilot.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型降级服务：主模型失败时，按「同级备选 → 功能降级 → 规则兜底」三级链依次尝试。
 *
 * <p>对应书中 Ch12 §12.3 KP 12.3.2 — 优雅降级链，与分布式系统 Circuit Breaker
 * 设计原理一致（故障隔离 + 优雅故障，防止级联故障）。
 *
 * <h3>三级降级链（与 KP 12.3.2 文本描述一一对应）</h3>
 * <ol>
 *   <li><b>L1 同级备选（sameTierBackups）</b>：能力相近的模型互相备份，
 *       如 Sonnet ↔ GPT-5.4 Mini ↔ qwen-plus；用户感知零差异，不改变任务复杂度。</li>
 *   <li><b>L2 功能降级（lowerTierFallbacks）</b>：切换到能力较弱的模型，
 *       配合 {@link #complexityReducer} 策略降低任务复杂度（例如："代码重构"
 *       降级为"格式化+静态检查"，翻译降级为要点摘要等）。</li>
 *   <li><b>L3 规则兜底（staticFallback）</b>：完全绕过 LLM，根据 taskType
 *       返回预设的结构化降级响应——典型如"翻译服务暂时不可用，请稍后重试"、
 *       "FAQ 请参考帮助文档 https://..."。保证无论 LLM 是否可用，系统总有响应。</li>
 * </ol>
 *
 * <p>与 ch12-model 的 {@link CascadeMiddleware} 关系：CascadeMiddleware 负责
 * <b>何时需要升级/降级</b>（置信度判定），本类负责<b>降级的具体执行</b>
 * （按三级链尝试 ModelExecutor 并带回退）。两者分工明确，不强耦合。
 *
 * <p>本类遵循 Ch11 setter 模式约定：三级降级链均通过 setter 配置，
 * {@code @Configuration} 层在 {@code @Bean} 方法中按业务需求注入。
 */
@Service
public class ModelFallbackService {

    private static final Logger log = LoggerFactory.getLogger(ModelFallbackService.class);

    /** L1 同级备选模型 ID 列表（按优先级从左到右尝试）。 */
    private List<String> sameTierBackups = new ArrayList<>(List.of("sonnet-4.6", "gpt-5.4-mini"));
    /** L2 功能降级模型 ID 列表（所有 L1 失败后按优先级尝试）。 */
    private List<String> lowerTierFallbacks = new ArrayList<>(List.of("haiku-4.5", "gemini-2.5-flash", "qwen-mini"));
    /** L3 规则兜底响应：taskType → 预设响应文案。 */
    private Map<String, String> staticFallback = new HashMap<>(Map.of(
            "FAQ", "请参考帮助文档: https://docs.example.com",
            "translation", "翻译服务暂时不可用，请稍后重试",
            "coding", "代码服务暂时不可用，已记录请求 ID"
    ));

    /** 连续失败 N 次后触发熔断器（默认 5）。 */
    private int failureThreshold = 5;
    /** 熔断器半开状态时，至少 N 次成功才恢复（默认 2）。 */
    private int halfOpenProbeCount = 2;

    /** 熔断器状态。 */
    private enum CircuitState { CLOSED, OPEN, HALF_OPEN }
    private CircuitState circuitState = CircuitState.CLOSED;
    private int consecutiveFailures = 0;
    private int halfOpenSuccesses = 0;

    /**
     * 复杂度归约策略：L2 功能降级时，把原始 task 简化为弱模型可处理的子任务。
     *
     * <p>默认实现：返回原始 task，仅在日志中记录降级标记。生产应通过
     * {@link #setComplexityReducer(ComplexityReducer)} 注入业务特定策略，
     * 例如：将"代码重构"重写为"代码格式化 + 语法检查"两个子任务。
     */
    @FunctionalInterface
    public interface ComplexityReducer {
        /** 接受原始任务描述，返回降级后的简化任务描述。 */
        String simplify(String originalTask);
    }
    private ComplexityReducer complexityReducer = String::valueOf;

    /** 执行器函数接口 — 业务方传入如何用某模型 id 跑 task。 */
    @FunctionalInterface
    public interface ModelExecutor {
        String execute(String modelId, String task) throws Exception;
    }

    /** 三级降级执行结果。 */
    public record FallbackResult(
            String usedModelId,   // 实际成功使用的模型；L3 兜底时为 "STATIC_FALLBACK"
            String result,        // 返回内容（成功或失败文案）
            boolean success,      // L1/L2/L3 任一级成功 = true；全部失败 = false
            int tierUsed          // 使用了第几级：1=L1 同级, 2=L2 功能降级, 3=L3 兜底, 0=全部失败
    ) {}

    /**
     * 带三级降级链的任务执行主入口。
     *
     * <p>执行顺序：L1 sameTierBackups → L2 lowerTierFallbacks（调用 complexityReducer）
     * → L3 staticFallback；任何一级成功即返回，不再往下走。
     *
     * @param taskType 任务类型（用于匹配 L3 staticFallback key；可为 null）
     * @param task     任务原始描述
     * @param executor 业务传入的"用某模型 id 跑 task"的函数（如封装模型 SDK 调用）
     * @return {@link FallbackResult} 记录使用了哪一级、哪个模型、结果内容
     */
    public FallbackResult executeWithFallback(String taskType, String task, ModelExecutor executor) {
        // 熔断器：OPEN → 直接跳 L3 兜底，避免浪费 token 和延迟
        if (circuitState == CircuitState.OPEN) {
            log.warn("[熔断器 OPEN] 跳过 L1/L2，直接 L3 兜底");
            return l3StaticFallback(taskType, "熔断器打开，跳过所有模型调用");
        }

        Exception lastException = null;

        // ── L1：同级备选 ──
        for (String modelId : sameTierBackups) {
            try {
                log.info("[L1 同级备选] 尝试模型: {}", modelId);
                String result = executor.execute(modelId, task);
                onSuccess();
                return new FallbackResult(modelId, result, true, 1);
            } catch (Exception e) {
                lastException = e;
                onFailure();
                log.warn("[L1 同级备选] 模型 {} 失败: {}", modelId, e.getMessage());
            }
        }

        // ── L2：功能降级（简化任务 + 弱模型） ──
        String simplifiedTask = complexityReducer.simplify(task);
        for (String modelId : lowerTierFallbacks) {
            try {
                log.info("[L2 功能降级] 尝试模型: {}, 简化任务长度: {}", modelId, simplifiedTask.length());
                String result = executor.execute(modelId, simplifiedTask);
                onSuccess();
                return new FallbackResult(modelId, result, true, 2);
            } catch (Exception e) {
                lastException = e;
                onFailure();
                log.warn("[L2 功能降级] 模型 {} 失败: {}", modelId, e.getMessage());
            }
        }

        // ── L3：规则兜底 ──
        log.warn("[L3 规则兜底] 所有模型调用均失败，返回静态响应。taskType={}", taskType);
        return l3StaticFallback(taskType, lastException != null ? lastException.getMessage() : "所有模型均失败");
    }

    private FallbackResult l3StaticFallback(String taskType, String trace) {
        String msg = staticFallback.getOrDefault(taskType, staticFallback.getOrDefault(null,
                "服务暂时不可用，请稍后重试。请求 ID: " + System.nanoTime()));
        onFailure(); // L3 视为系统级失败，仍计入连续失败计数（触发运维告警）
        return new FallbackResult("STATIC_FALLBACK", msg + "（trace: " + trace + "）",
                !msg.isBlank(), 3);
    }

    // ═══════ 熔断器内部状态机 ═══════

    private void onSuccess() {
        consecutiveFailures = 0;
        if (circuitState == CircuitState.HALF_OPEN) {
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= halfOpenProbeCount) {
                circuitState = CircuitState.CLOSED;
                halfOpenSuccesses = 0;
                log.info("[熔断器] HALF_OPEN 连续 {} 次成功 → CLOSED 恢复", halfOpenProbeCount);
            }
        }
    }

    private void onFailure() {
        consecutiveFailures++;
        halfOpenSuccesses = 0;
        if (circuitState == CircuitState.CLOSED && consecutiveFailures >= failureThreshold) {
            circuitState = CircuitState.OPEN;
            log.error("[熔断器] 连续失败 {} 次 → OPEN。下次请求直接跳 L3", consecutiveFailures);
        } else if (circuitState == CircuitState.HALF_OPEN) {
            circuitState = CircuitState.OPEN;
            log.warn("[熔断器] HALF_OPEN 探针失败 → 回退 OPEN");
        }
    }

    // ═══════ setter 模式配置（@Configuration 层注入） ═══════

    public void setSameTierBackups(List<String> backups) { this.sameTierBackups = new ArrayList<>(backups); }
    public void setLowerTierFallbacks(List<String> fallbacks) { this.lowerTierFallbacks = new ArrayList<>(fallbacks); }
    public void setStaticFallback(Map<String, String> fallback) { this.staticFallback = new HashMap<>(fallback); }
    public void setFailureThreshold(int threshold) { this.failureThreshold = threshold; }
    public void setHalfOpenProbeCount(int count) { this.halfOpenProbeCount = count; }
    public void setComplexityReducer(ComplexityReducer reducer) { this.complexityReducer = reducer; }

    public List<String> getSameTierBackups() { return Collections.unmodifiableList(sameTierBackups); }
    public List<String> getLowerTierFallbacks() { return Collections.unmodifiableList(lowerTierFallbacks); }
    public Map<String, String> getStaticFallback() { return Map.copyOf(staticFallback); }
    public int getFailureThreshold() { return failureThreshold; }
    public int getHalfOpenProbeCount() { return halfOpenProbeCount; }
    public String getCircuitState() { return circuitState.name(); }
}

package io.etclovg.codepilot.model;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 成本追踪中间件（ch12 跨模型版本，与 ch08 CostAttributionMiddleware 协同）。
 *
 * <p>对应书中 Ch12 §12.3 KP 12.3.1 + KP 12.4.1：
 *   <ul>
 *     <li>KP 12.3.1：按 {@code model.tier} 拆分成本（LIGHT/STANDARD/POWER 三档），
 *         帮助管理者判断 RouteLLM 14%→95% 的成本节省是否真的兑现（ROI 回算）。</li>
 *     <li>KP 12.4.1：按 {@code cache_hit / cache_miss} 拆分成本，
 *         与 PrefixCacheMonitorMiddleware 命中统计相乘，计算 Prefix Caching
 *         实际节省的真金白银（DeepSeek 56.3% 命中率 → 每月节省金额的可观测落点）。</li>
 *   </ul>
 *
 * <p>与 ch08 CostAttributionMiddleware（@Component("costTracker")）的关系：
 * ch08 成本归因在全局 Middleware 链最末端做 Span 级别聚合；ch12 本类
 * 在链尾追加按 {@code model.tier / model.name / cache_hit} 三个维度做拆分，
 * 两者是互补而非替换——ch08 管"花了多少钱"，ch12 管"钱花在了哪一档模型、缓存有没有帮我省"。
 */
@Component
public class CostTrackerMiddleware extends AbstractLayerMiddleware {

    protected static final Logger log = LoggerFactory.getLogger(CostTrackerMiddleware.class);

    // 全局累计（AgentScope 单例 Bean 级别，线程安全 AtomicLong/ConcurrentHashMap）
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final AtomicLong totalCacheHitTokens = new AtomicLong();
    private volatile double totalCost = 0.0;

    /** model.name → 累计成本（用于 KP 12.3.1 各模型 ROI 拆分） */
    private final Map<String, Double> costByModel = new ConcurrentHashMap<>();
    /** model.tier → 累计 token（LIGHT/STANDARD/POWER 三档汇总） */
    private final Map<String, Long> inputTokensByTier = new ConcurrentHashMap<>();
    private final Map<String, Long> outputTokensByTier = new ConcurrentHashMap<>();

    public CostTrackerMiddleware() {
        super(Layer.O, "Ch12CostTracker");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String tier = String.valueOf(rc.getExtra().getOrDefault("model.tier", "STANDARD"));
        rc.put("cost.tracker.start_ns", System.nanoTime());
        rc.put("cost.tracker.tier", tier);
        return next.apply(input).doOnComplete(() -> {
            long latency = System.nanoTime() - (Long) rc.getExtra()
                    .getOrDefault("cost.tracker.start_ns", 0L);
            log.info("[Ch12CostTracker] 完成：tier={}, 总输入 tokens={}, 总成本=${:.4f}",
                    tier, totalInputTokens.get(), totalCost);
        });
    }

    /**
     * onModelCall 钩子：每次底层模型 SDK 调用后把 prompt/completion/cache 读 token
     * 拆到对应维度；ch08 CostAttributionMiddleware 写过的值会在这里再聚合一层。
     */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext rc, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        String modelName = rc.getExtra().getOrDefault("model.name", "unknown").toString();
        String tier = rc.getExtra().getOrDefault("model.tier", "STANDARD").toString();
        double inputPricePerM = toInputPricePerM(modelName);
        double outputPricePerM = toOutputPricePerM(modelName);
        double cacheDiscount = PrefixCacheMonitorMiddleware.CACHE_PRICE_DISCOUNT;
        return next.apply(input).doOnNext(event -> {
            // ch08 CostAttributionMiddleware 写入到 rc 的临时 token 读出来（如果下游写了）
            Object prompt = rc.getExtra().get("attribution.prompt_tokens");
            Object completion = rc.getExtra().get("attribution.completion_tokens");
            Object cacheRead = rc.getExtra().get("attribution.cache_read_tokens");
            long pTokens = prompt instanceof Number n ? n.longValue() : 0;
            long cTokens = completion instanceof Number n ? n.longValue() : 0;
            long chTokens = cacheRead instanceof Number n ? n.longValue() : 0;

            if (pTokens == 0 && cTokens == 0) return; // no data yet, skip (还在流式)

            long nonCacheInput = Math.max(0, pTokens - chTokens);
            double stepCost = (nonCacheInput * inputPricePerM
                             + chTokens * inputPricePerM * cacheDiscount
                             + cTokens * outputPricePerM) / 1_000_000.0;
            recordTokens(pTokens, cTokens, stepCost, chTokens, modelName, tier);
        });
    }

    private synchronized void recordTokens(long input, long output, double cost,
                                           long cacheHit, String modelName, String tier) {
        totalInputTokens.addAndGet(input);
        totalOutputTokens.addAndGet(output);
        totalCacheHitTokens.addAndGet(cacheHit);
        totalCost += cost;
        costByModel.merge(modelName, cost, Double::sum);
        inputTokensByTier.merge(tier, input, Long::sum);
        outputTokensByTier.merge(tier, output, Long::sum);
    }

    // ═══════ 对外统计 API（供 KP 12.3.1 路由 ROI / KP 12.4.1 缓存节省金额 报表使用） ═══════

    public long getTotalInputTokens() { return totalInputTokens.get(); }
    public long getTotalOutputTokens() { return totalOutputTokens.get(); }
    public long getTotalCacheHitTokens() { return totalCacheHitTokens.get(); }
    public synchronized double getTotalCost() { return totalCost; }
    public Map<String, Double> getCostByModel() { return new LinkedHashMap<>(costByModel); }
    public Map<String, Long> getInputTokensByTier() { return new LinkedHashMap<>(inputTokensByTier); }
    public Map<String, Long> getOutputTokensByTier() { return new LinkedHashMap<>(outputTokensByTier); }

    /** 估算 Prefix Caching 节省的美元（KP 12.4.1 DeepSeek 56.3% 命中率 × $3/M input 场景）。 */
    public double estimatedCacheSavedUsd(double inputPricePerM) {
        double savedPerHit = inputPricePerM * (1.0 - PrefixCacheMonitorMiddleware.CACHE_PRICE_DISCOUNT);
        return totalCacheHitTokens.get() * savedPerHit / 1_000_000.0;
    }

    // ═══════ 内置模型价目表（与 Ch11 P3 已修复 Sonnet 4.6 $3/15 等保持一致） ═══════
    private static double toInputPricePerM(String modelName) {
        return switch (modelName) {
            case "opus-4.6", "opus", "gpt-5" -> 5.0;
            case "sonnet-4.6", "sonnet", "gpt-5.4-mini", "qwen-plus" -> 3.0;
            case "haiku-4.5", "haiku", "gemini-2.5-flash", "qwen-mini", "deepseek-v3" -> 0.30;
            default -> 3.0;
        };
    }
    private static double toOutputPricePerM(String modelName) {
        return switch (modelName) {
            case "opus-4.6", "opus", "gpt-5" -> 25.0;
            case "sonnet-4.6", "sonnet", "gpt-5.4-mini", "qwen-plus" -> 15.0;
            case "haiku-4.5", "haiku", "gemini-2.5-flash", "qwen-mini", "deepseek-v3" -> 1.5;
            default -> 15.0;
        };
    }
}

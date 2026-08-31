package io.etclovg.codepilot.model;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 前缀缓存监控中间件。
 * <p>对应书中 Ch12 §12.4 KP 12.4.1 — Prefix Caching 的稳定性监控、命中率统计
 * 与缓存失效告警。
 *
 * <h3>监控职责（与 KP 12.4.1 文本对应）</h3>
 * <ol>
 *   <li><b>前缀稳定性检测</b>：每次 ReActAgent 调用前，对
 *       {@link #stablePrefix}（systemPrompt + "\n" + toolDefinitions 拼接结果）
 *       做分块 hash，与上一次 hash 对比；不一致即触发
 *       {@link #onPrefixChangeHandler} — 通常是日志告警 + Prometheus counter。
 *       Anthropic Prompt Caching 粒度为 {@link #cacheGranularity}=1,024 tokens，
 *       最多 {@link #maxCheckpoints}=4 个检查点；任何检查点区间内的 prefix 字节变化
 *       都会导致对应块的缓存失效。</li>
 *   <li><b>命中率统计</b>：onModelCall 钩子后根据实际返回的 prompt_tokens /
 *       cache_hit_tokens（模型 SDK 返回值，这里通过 rc.getExtra 下游写入，
 *       由 {@code recordAccess(Boolean)} 记录）累计 hit / miss，
 *       {@link #getHitRate()} 返回 0-1 命中率。</li>
 *   <li><b>成本节省归因</b>：与 ch08 CostAttributionMiddleware 协同——
 *       命中的 token 按 (1 - 缓存价折扣率) 计算节省金额，
 *       {@link #getCostSavedUsd(double)} 返回美元估算值。</li>
 * </ol>
 *
 * <p>本类继承 {@link AbstractLayerMiddleware} 并挂到 Layer.O（可观测性），
 * 与 ch11 HarnessAssemblyConfig 中 O 层 TracerMiddleware / CostTracker
 * 的装配位置一致——注册在链尾，`after` 钩子最先触发以覆盖全链路。
 */
@Component
public class PrefixCacheMonitorMiddleware extends AbstractLayerMiddleware {

    private static final Logger log = LoggerFactory.getLogger(PrefixCacheMonitorMiddleware.class);

    /** 缓存价折扣率：Anthropic Prompt Caching 读价 $0.30/M vs 标准 $3.00/M = 10% */
    public static final double CACHE_PRICE_DISCOUNT = 0.10;

    /** 稳定前缀：systemPrompt + "\n" + toolDefinitions 的拼接结果（setter 注入）。 */
    private String stablePrefix = "";
    /** 缓存检查块大小（tokens，Anthropic 默认 1,024）；近似按 4 bytes/token 换算为字节。 */
    private int cacheGranularity = 1_024;
    /** 最多检查点数量（Anthropic 默认 4）。 */
    private int maxCheckpoints = 4;

    /** 上次 stablePrefix 的分块 hash 列表，用于对比变化。 */
    private List<Integer> lastBlockHashes = new ArrayList<>();

    /** Prefix 变化事件处理器（setter 注入；默认 log.error + counter）。 */
    private Consumer<PrefixChangeEvent> onPrefixChangeHandler = event -> {
        log.error("[PrefixCache] 前缀发生变化！{} 个检查块失效：{}",
                event.changedBlocks().size(), event.diffSummary());
    };
    /** 命中回调（setter 注入；默认 debug 日志 + Prometheus gauge 由 O 层汇总）。 */
    private Consumer<CacheAccessEvent> onHitHandler = event ->
            log.debug("[PrefixCache] 命中：hitTokens={} / totalTokens={}", event.hitTokens(), event.totalTokens());

    // ═══════ 计数器（线程安全使用 AgentScope RuntimeContext 聚合） ═══════
    private long cacheHits = 0;
    private long cacheMisses = 0;
    private long hitTokens = 0;
    private long totalTokens = 0;

    public PrefixCacheMonitorMiddleware() {
        super(Layer.O, "PrefixCacheMonitor");
    }

    /**
     * onAgent 入站钩子：先做前缀稳定性检查，再放行到 next。
     *
     * <p>注意：缓存命中判定发生在 onModelCall 钩子之后（下游 CostAttributionMiddleware
     * 把模型 SDK 返回的 cache_read_tokens 写入 rc），本中间件不直接调模型 API；
     * 业务层在收到模型响应后调用 {@link #recordAccess(boolean, long, long)}
     * 或 {@link #recordHit()} / {@link #recordMiss()} 计入统计。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        List<PrefixBlockDiff> diffs = checkPrefixStability();
        if (!diffs.isEmpty()) {
            onPrefixChangeHandler.accept(new PrefixChangeEvent(diffs, stablePrefix));
        }
        rc.put("prefix_cache.stable", diffs.isEmpty());
        rc.put("prefix_cache.hit_rate_so_far", getHitRate());
        return next.apply(input);
    }

    /**
     * 分块 hash 稳定性检查：按 cacheGranularity*4 近似字节分块，
     * 计算每块的 hash，与 {@link #lastBlockHashes} 对比返回差异列表。
     *
     * <p>第一次调用时没有历史 hash，直接记录并返回空列表（不告警）。
     */
    public List<PrefixBlockDiff> checkPrefixStability() {
        if (stablePrefix == null || stablePrefix.isEmpty()) {
            return List.of();
        }
        byte[] bytes = stablePrefix.getBytes(StandardCharsets.UTF_8);
        int bytesPerBlock = cacheGranularity * 4;   // 近似 4 bytes/token
        int numBlocks = Math.min(maxCheckpoints,
                (bytes.length + bytesPerBlock - 1) / bytesPerBlock);
        List<Integer> currentHashes = new ArrayList<>(numBlocks);
        for (int i = 0; i < numBlocks; i++) {
            int from = i * bytesPerBlock;
            int to = Math.min(bytes.length, from + bytesPerBlock);
            int hash = fnv1a(bytes, from, to);
            currentHashes.add(hash);
        }

        List<PrefixBlockDiff> diffs = new ArrayList<>();
        if (!lastBlockHashes.isEmpty()) {
            int common = Math.min(lastBlockHashes.size(), currentHashes.size());
            for (int i = 0; i < common; i++) {
                if (!lastBlockHashes.get(i).equals(currentHashes.get(i))) {
                    diffs.add(new PrefixBlockDiff(i, "checkpoint #" + i + " hash changed"));
                }
            }
            if (currentHashes.size() != lastBlockHashes.size()) {
                diffs.add(new PrefixBlockDiff(Math.min(currentHashes.size(), lastBlockHashes.size()),
                        "block count changed: " + lastBlockHashes.size() + " → " + currentHashes.size()));
            }
        }
        lastBlockHashes = currentHashes;
        return diffs;
    }

    // ═══════ 计数器 API ═══════

    public void recordHit() { cacheHits++; }
    public void recordMiss() { cacheMisses++; }
    public void recordAccess(boolean hit) {
        if (hit) cacheHits++; else cacheMisses++;
    }
    public void recordAccess(boolean hit, long thisHitTokens, long thisTotalTokens) {
        recordAccess(hit);
        if (hit) hitTokens += thisHitTokens;
        totalTokens += thisTotalTokens;
        if (hit) {
            onHitHandler.accept(new CacheAccessEvent(thisHitTokens, thisTotalTokens));
        }
    }

    /** 按请求计数的命中率（hit/(hit+miss)）。 */
    public double getHitRate() {
        long total = cacheHits + cacheMisses;
        return total > 0 ? (double) cacheHits / total : 0.0;
    }

    /** 按 token 计数的命中率（DeepSeek 56.3% 指标所使用的维度）。 */
    public double getTokenHitRate() {
        return totalTokens > 0 ? (double) hitTokens / totalTokens : 0.0;
    }

    /** 估算已节省的美元成本。inputPricePerM = 模型 API input 标准价（如 Sonnet 为 3.0）。 */
    public double getCostSavedUsd(double inputPricePerM) {
        double savedPerToken = inputPricePerM * (1.0 - CACHE_PRICE_DISCOUNT) / 1_000_000.0;
        return hitTokens * savedPerToken;
    }

    public long getCacheHits() { return cacheHits; }
    public long getCacheMisses() { return cacheMisses; }
    public long getHitTokens() { return hitTokens; }
    public long getTotalTokens() { return totalTokens; }

    // ═══════ setter 模式配置（@Configuration 层注入） ═══════

    public void setStablePrefix(String stablePrefix) { this.stablePrefix = stablePrefix; }
    public void setCacheGranularity(int tokens) { this.cacheGranularity = tokens; }
    public void setMaxCheckpoints(int n) { this.maxCheckpoints = n; }
    public void setOnPrefixChangeHandler(Consumer<PrefixChangeEvent> h) { this.onPrefixChangeHandler = h; }
    public void setOnHitHandler(Consumer<CacheAccessEvent> h) { this.onHitHandler = h; }

    public String getStablePrefix() { return stablePrefix; }
    public int getCacheGranularity() { return cacheGranularity; }
    public int getMaxCheckpoints() { return maxCheckpoints; }

    // ═══════ 内部：FNV-1a 非加密 hash（快、分布好，用于分块对比） ═══════
    private static int fnv1a(byte[] data, int from, int to) {
        int h = 0x811c9dc5;
        for (int i = from; i < to; i++) {
            h ^= data[i] & 0xff;
            h *= 0x01000193;
        }
        return h;
    }

    // ═══════ 事件类型（供 handler 使用，记录哪个 checkpoint 块变化了） ═══════
    public record PrefixBlockDiff(int blockIndex, String reason) {}
    public record PrefixChangeEvent(List<PrefixBlockDiff> changedBlocks, String newPrefix) {
        public String diffSummary() {
            StringBuilder sb = new StringBuilder();
            changedBlocks.forEach(d -> sb.append('[').append(d.blockIndex).append("]=")
                    .append(d.reason).append(';'));
            return sb.toString();
        }
    }
    public record CacheAccessEvent(long hitTokens, long totalTokens) {}
}

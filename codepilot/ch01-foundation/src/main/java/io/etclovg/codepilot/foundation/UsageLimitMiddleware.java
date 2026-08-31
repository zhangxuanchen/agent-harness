package io.etclovg.codepilot.foundation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 使用限制中间件：管理 Agent 资源配额使用
 * 对应书中 Ch01 §1.3 —— Agent 成本控制
 */
@Component
public class UsageLimitMiddleware {

    private static final Logger log = LoggerFactory.getLogger(UsageLimitMiddleware.class);

    private final long tokenBudget;
    private final long costBudget;
    private final long dailyCallLimit;

    private final Map<String, UsageRecord> usageByTask = new ConcurrentHashMap<>();
    private final AtomicLong totalTokensUsed = new AtomicLong(0);
    private final AtomicLong totalCostUsed = new AtomicLong(0);
    private final AtomicLong totalCalls = new AtomicLong(0);

    public UsageLimitMiddleware() {
        this(1_000_000L, 100L, 10_000L);
    }

    public UsageLimitMiddleware(long tokenBudget, long costBudget, long dailyCallLimit) {
        this.tokenBudget = tokenBudget;
        this.costBudget = costBudget;
        this.dailyCallLimit = dailyCallLimit;
    }

    public UsageCheckResult check(String taskId) {
        long currentTokens = totalTokensUsed.get();
        long currentCost = totalCostUsed.get();
        long currentCalls = totalCalls.get();

        boolean tokenOk = currentTokens < tokenBudget;
        boolean costOk = currentCost < costBudget;
        boolean callOk = currentCalls < dailyCallLimit;

        if (!tokenOk) {
            log.warn("[UsageLimitMiddleware] Token 预算已耗尽: used={}, budget={}",
                    currentTokens, tokenBudget);
        }
        if (!costOk) {
            log.warn("[UsageLimitMiddleware] 成本预算已耗尽: used={}, budget={}",
                    currentCost, costBudget);
        }
        if (!callOk) {
            log.warn("[UsageLimitMiddleware] 调用次数已达上限: used={}, limit={}",
                    currentCalls, dailyCallLimit);
        }

        return new UsageCheckResult(
                tokenOk && costOk && callOk,
                tokenOk, costOk, callOk,
                currentTokens, tokenBudget,
                currentCost, costBudget,
                currentCalls, dailyCallLimit
        );
    }

    public void recordUsage(String taskId, long tokens, double cost) {
        totalTokensUsed.addAndGet(tokens);
        totalCostUsed.addAndGet((long) (cost * 100));
        totalCalls.incrementAndGet();

        usageByTask.computeIfAbsent(taskId, k -> new UsageRecord())
                .addUsage(tokens, cost);

        log.debug("[UsageLimitMiddleware] 记录使用: task={}, tokens={}, cost=${}",
                taskId, tokens, cost);
    }

    public UsageSnapshot getSnapshot() {
        return new UsageSnapshot(
                totalTokensUsed.get(), tokenBudget,
                totalCostUsed.get() / 100.0, costBudget,
                totalCalls.get(), dailyCallLimit
        );
    }

    public void reset() {
        totalTokensUsed.set(0);
        totalCostUsed.set(0);
        totalCalls.set(0);
        usageByTask.clear();
        log.info("[UsageLimitMiddleware] 使用数据已重置");
    }

    public record UsageCheckResult(
            boolean allowed,
            boolean tokenOk, boolean costOk, boolean callOk,
            long tokensUsed, long tokenBudget,
            double costUsed, double costBudget,
            long callsUsed, long callLimit
    ) {
        public double tokenUsagePercent() {
            return tokenBudget > 0 ? (double) tokensUsed / tokenBudget * 100 : 0;
        }

        public double costUsagePercent() {
            return costBudget > 0 ? costUsed / costBudget * 100 : 0;
        }
    }

    public record UsageSnapshot(
            long tokensUsed, long tokenBudget,
            double costUsed, double costBudget,
            long callsUsed, long callLimit
    ) {}

    private static class UsageRecord {
        long tokens;
        double cost;

        void addUsage(long tokens, double cost) {
            this.tokens += tokens;
            this.cost += cost;
        }
    }
}
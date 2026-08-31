package io.etclovg.codepilot.future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预算中间件：下一代预算管理与自适应调整
 * 对应书中 Ch19 —— 未来展望：自适应 Agent 资源管理
 */
@Component
public class BudgetMiddleware {

    private static final Logger log = LoggerFactory.getLogger(BudgetMiddleware.class);

    private final Map<String, BudgetState> budgets = new ConcurrentHashMap<>();

    public BudgetState createBudget(String agentId, BigDecimal totalBudget,
                                     BigDecimal dailyLimit, BudgetPeriod period) {
        BudgetState state = new BudgetState(
                agentId, totalBudget, dailyLimit, period,
                BigDecimal.ZERO, BigDecimal.ZERO, 0,
                Instant.now(), BudgetStatus.ACTIVE
        );
        budgets.put(agentId, state);
        log.info("[BudgetMiddleware] 创建预算: agent={}, total=${}, daily=${}, period={}",
                agentId, totalBudget, dailyLimit, period);
        return state;
    }

    public BudgetCheckResult checkBudget(String agentId, BigDecimal estimatedCost) {
        BudgetState state = budgets.get(agentId);
        if (state == null) {
            return new BudgetCheckResult(agentId, false, "预算不存在", BigDecimal.ZERO);
        }

        BigDecimal remaining = state.totalBudget().subtract(state.usedBudget());
        boolean sufficient = remaining.compareTo(estimatedCost) >= 0;

        BigDecimal dailyRemaining = state.dailyLimit().subtract(state.dailyUsed());
        boolean dailyOk = dailyRemaining.compareTo(estimatedCost) >= 0;

        if (!sufficient) {
            log.warn("[BudgetMiddleware] 预算不足: agent={}, remaining=${}, estimated=${}",
                    agentId, remaining, estimatedCost);
        }
        if (!dailyOk) {
            log.warn("[BudgetMiddleware] 日预算不足: agent={}, dailyRemaining=${}, estimated=${}",
                    agentId, dailyRemaining, estimatedCost);
        }

        boolean ok = sufficient && dailyOk;
        return new BudgetCheckResult(agentId, ok,
                ok ? "预算充足" : "预算不足",
                remaining.setScale(4, RoundingMode.HALF_UP));
    }

    public BudgetState recordSpend(String agentId, BigDecimal cost) {
        BudgetState state = budgets.get(agentId);
        if (state == null) return null;

        BudgetState updated = new BudgetState(
                agentId, state.totalBudget(), state.dailyLimit(), state.period(),
                state.usedBudget().add(cost),
                state.dailyUsed().add(cost),
                state.transactionCount() + 1,
                Instant.now(), state.status()
        );
        budgets.put(agentId, updated);

        log.debug("[BudgetMiddleware] 记录支出: agent={}, cost=${}, remaining=${}",
                agentId, cost,
                updated.totalBudget().subtract(updated.usedBudget()));

        return updated;
    }

    public BudgetState getBudget(String agentId) {
        return budgets.get(agentId);
    }

    public List<BudgetState> getAllBudgets() {
        return List.copyOf(budgets.values());
    }

    public BudgetState resetDaily(String agentId) {
        BudgetState state = budgets.get(agentId);
        if (state == null) return null;

        BudgetState updated = new BudgetState(
                agentId, state.totalBudget(), state.dailyLimit(), state.period(),
                state.usedBudget(), BigDecimal.ZERO,
                state.transactionCount(), Instant.now(), state.status()
        );
        budgets.put(agentId, updated);
        log.info("[BudgetMiddleware] 日预算重置: agent={}", agentId);
        return updated;
    }

    public record BudgetState(
            String agentId, BigDecimal totalBudget,
            BigDecimal dailyLimit, BudgetPeriod period,
            BigDecimal usedBudget, BigDecimal dailyUsed,
            int transactionCount, Instant lastUpdated,
            BudgetStatus status
    ) {}

    public record BudgetCheckResult(
            String agentId, boolean allowed,
            String message, BigDecimal remainingBudget
    ) {}

    public enum BudgetPeriod {
        DAILY, WEEKLY, MONTHLY, TOTAL
    }

    public enum BudgetStatus {
        ACTIVE, EXHAUSTED, FROZEN
    }
}
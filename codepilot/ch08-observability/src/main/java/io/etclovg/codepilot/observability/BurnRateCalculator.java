package io.etclovg.codepilot.observability;

import io.etclovg.codepilot.core.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O 层 · 消耗率计算器。
 *
 * <p>实时计算 API 成本的消耗速率（burn rate），
 * 当消耗速率超过预算阈值时触发自动降级或熔断。
 * 对应书中 Ch8 §8.4 消耗率监控与自动降级。
 *
 * <p><b>Burn Rate 算法</b>：
 * <pre>
 *   burnRate = cost_in_last_N_minutes / N_minutes     (每分钟消耗速率)
 *
 *   预算耗尽时间 = remaining_budget / burnRate         (按当前速率还能用多久)
 *
 *   告警分级:
 *     burnRate > budget_per_minute × 1.5  → WARN
 *     burnRate > budget_per_minute × 2.0  → DEGRADE (自动降级)
 *     burnRate > budget_per_minute × 3.0  → FUSE    (熔断)
 * </pre>
 *
 * <p><b>页面参考</b>：Ch8 §8.4 消耗率监控与自动降级
 */
@Component
public class BurnRateCalculator {

    private static final Logger log = LoggerFactory.getLogger(BurnRateCalculator.class);

    /** 告警级别 */
    public enum AlertLevel { NORMAL, WARN, DEGRADE, FUSE }

    private final CostTracker costTracker;

    /** 滑动窗口大小（分钟） */
    private int windowMinutes = 5;
    /** 每个计算周期的预算上限（美元/分钟） */
    private BigDecimal budgetPerMinute = BigDecimal.valueOf(0.10);
    /** 总预算（美元） */
    private BigDecimal totalBudget = BigDecimal.valueOf(100.00);
    /** 已消费总额 */
    private BigDecimal consumedBudget = BigDecimal.ZERO;

    /** 任务ID → 滑动窗口费用记录 */
    private final Map<String, Deque<TimedCost>> costWindows = new ConcurrentHashMap<>();

    public BurnRateCalculator(CostTracker costTracker) {
        this.costTracker = costTracker;
    }

    /**
     * 计算指定任务的当前消耗速率。
     *
     * @param taskId 任务ID
     * @return 每分钟消耗速率（美元/分钟）
     */
    public BigDecimal calculateBurnRate(String taskId) {
        Deque<TimedCost> window = costWindows.get(taskId);
        if (window == null || window.isEmpty()) return BigDecimal.ZERO;

        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofMinutes(windowMinutes));

        // 清理过期记录
        while (!window.isEmpty() && window.peekFirst().timestamp().isBefore(windowStart)) {
            window.removeFirst();
        }

        if (window.isEmpty()) return BigDecimal.ZERO;

        // 计算窗口内的总费用
        BigDecimal windowCost = window.stream()
                .map(TimedCost::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 计算实际窗口时长（分钟）
        Instant oldest = window.peekFirst().timestamp();
        double actualMinutes = Math.max(0.1,
                Duration.between(oldest, now).toMillis() / 60_000.0);

        return windowCost.divide(BigDecimal.valueOf(actualMinutes), 6, RoundingMode.HALF_UP);
    }

    /**
     * 记录一次费用发生。
     *
     * @param taskId 任务ID
     * @param cost   本次费用
     */
    public void recordCost(String taskId, BigDecimal cost) {
        Deque<TimedCost> window = costWindows.computeIfAbsent(taskId,
                k -> new ArrayDeque<>());
        window.addLast(new TimedCost(Instant.now(), cost));

        // 更新总消耗
        consumedBudget = consumedBudget.add(cost);

        log.debug("[O层·消耗率] 任务 {} 记录费用: ${}", taskId, cost);
    }

    /**
     * 获取消耗率告警级别。
     *
     * @param taskId 任务ID
     * @return 告警级别
     */
    public AlertLevel getAlertLevel(String taskId) {
        BigDecimal burnRate = calculateBurnRate(taskId);

        if (burnRate.compareTo(budgetPerMinute.multiply(BigDecimal.valueOf(3.0))) > 0) {
            return AlertLevel.FUSE;
        }
        if (burnRate.compareTo(budgetPerMinute.multiply(BigDecimal.valueOf(2.0))) > 0) {
            return AlertLevel.DEGRADE;
        }
        if (burnRate.compareTo(budgetPerMinute.multiply(BigDecimal.valueOf(1.5))) > 0) {
            return AlertLevel.WARN;
        }
        return AlertLevel.NORMAL;
    }

    /**
     * 检查并执行自动降级/熔断。
     *
     * @param taskId 任务ID
     * @return 是否需要降级/熔断处理
     */
    public DegradeResult checkAndDegrade(String taskId) {
        AlertLevel level = getAlertLevel(taskId);
        BigDecimal burnRate = calculateBurnRate(taskId);
        BigDecimal remaining = totalBudget.subtract(consumedBudget);

        Duration timeToExhaust = burnRate.compareTo(BigDecimal.ZERO) > 0
                ? Duration.ofMinutes(remaining.divide(burnRate, 0, RoundingMode.DOWN).longValue())
                : Duration.ofDays(365);

        return switch (level) {
            case FUSE -> {
                log.error("[O层·消耗率] 任务 {} 触发熔断! burnRate=${}/min (budget=${}/min, "
                        + "remaining=${}, 预计{}分钟耗尽)",
                        taskId, burnRate, budgetPerMinute, remaining,
                        timeToExhaust.toMinutes());
                yield new DegradeResult(true, "FUSE",
                        "消耗率超过预算3倍，已触发熔断。请检查调用逻辑或联系管理员。");
            }
            case DEGRADE -> {
                log.warn("[O层·消耗率] 任务 {} 触发自动降级! burnRate=${}/min", taskId, burnRate);
                yield new DegradeResult(true, "DEGRADE",
                        "消耗率超过预算2倍，已自动降级为更经济的模型。");
            }
            case WARN -> {
                log.warn("[O层·消耗率] 任务 {} 消耗率告警: burnRate=${}/min", taskId, burnRate);
                yield new DegradeResult(false, "WARN",
                        "消耗率偏高（超预算1.5倍），请注意监控。");
            }
            default -> new DegradeResult(false, "NORMAL", "消耗率正常。");
        };
    }

    /**
     * 获取预算消耗进度百分比。
     */
    public double getBudgetConsumedPercent() {
        if (totalBudget.compareTo(BigDecimal.ZERO) <= 0) return 0.0;
        return consumedBudget.divide(totalBudget, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    /**
     * 获取预算剩余金额。
     */
    public BigDecimal getRemainingBudget() {
        return totalBudget.subtract(consumedBudget);
    }

    /**
     * 获取全局快照（可用于监控面板）。
     */
    public Snapshot getSnapshot() {
        BigDecimal totalBurnRate = BigDecimal.ZERO;
        for (String taskId : costWindows.keySet()) {
            totalBurnRate = totalBurnRate.add(calculateBurnRate(taskId));
        }

        return new Snapshot(
                consumedBudget,
                totalBudget,
                totalBudget.subtract(consumedBudget),
                totalBurnRate,
                getBudgetConsumedPercent(),
                Instant.now()
        );
    }

    /**
     * 重置消耗率计算器。
     */
    public void reset() {
        costWindows.clear();
        consumedBudget = BigDecimal.ZERO;
        log.info("[O层·消耗率] 计算器已重置");
    }

    // ==================== 配置方法 ====================

    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public void setBudgetPerMinute(BigDecimal budgetPerMinute) { this.budgetPerMinute = budgetPerMinute; }
    public void setTotalBudget(BigDecimal totalBudget) { this.totalBudget = totalBudget; }

    // ==================== 内部类型 ====================

    /** 带时间戳的费用记录 */
    record TimedCost(Instant timestamp, BigDecimal cost) {}

    /** 降级决策结果 */
    public record DegradeResult(boolean shouldDegrade, String level, String reason) {}

    /** 消耗率全局快照 */
    public record Snapshot(BigDecimal consumed, BigDecimal budget, BigDecimal remaining,
                           BigDecimal burnRate, double percentUsed, Instant timestamp) {
        @Override
        public String toString() {
            return String.format("Budget: $%.4f/$%.4f (%.1f%%), Burn: $%.6f/min, Remaining: $%.4f",
                    consumed, budget, percentUsed, burnRate, remaining);
        }
    }
}

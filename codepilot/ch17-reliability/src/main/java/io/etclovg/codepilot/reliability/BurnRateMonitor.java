package io.etclovg.codepilot.reliability;

import io.etclovg.codepilot.observability.BurnRateCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产监控层 · 燃烧率巡检器。
 *
 * <p>BurnRateMonitor 在 ch08 {@link BurnRateCalculator}（消耗率计算 + 自动降级决策）
 * 之上，增加"定时巡检所有活跃任务"与"仪表盘快照"两项能力，是 §17.2.1 燃烧率异常检测
 * 在 ch17 的落地入口。
 *
 * <p>{@link BurnRateCalculator} 本身定义在 codepilot/ch08-observability 包中，
 * ch17 直接复用；本类仅负责按月预算配置它、注册活跃任务、周期性触发降级判定，
 * 并对外暴露仪表盘快照。对应书中 Ch17 §17.2.1 燃烧率异常检测。
 */
@Component
public class BurnRateMonitor {

    private static final Logger log = LoggerFactory.getLogger(BurnRateMonitor.class);

    private final BurnRateCalculator burnRateCalc;

    /** 活跃任务集合——onCostIncurred 注册，任务结束时由 onTaskCompleted 移除 */
    private final Set<String> activeTaskIds = ConcurrentHashMap.newKeySet();

    public BurnRateMonitor(BurnRateCalculator burnRateCalc) {
        this.burnRateCalc = burnRateCalc;
        // budgetPerMinute = 月预算 / 月分钟数；例如月 $10,000 → ≈$0.23/min
        burnRateCalc.setTotalBudget(BigDecimal.valueOf(10_000));
        burnRateCalc.setBudgetPerMinute(BigDecimal.valueOf(0.23));
        burnRateCalc.setWindowMinutes(5); // 5 分钟滑动窗口
    }

    /** 每次 LLM/工具调用结束时记录费用，驱动滑动窗口重算 */
    public void onCostIncurred(String taskId, BigDecimal cost) {
        activeTaskIds.add(taskId);
        burnRateCalc.recordCost(taskId, cost);
    }

    /** 任务结束后从活跃集合移除，停止巡检 */
    public void onTaskCompleted(String taskId) {
        activeTaskIds.remove(taskId);
    }

    /**
     * 定时巡检：对所有活跃任务执行燃烧率检查与自动降级。
     * <ul>
     *   <li>FUSE(3.0×) → 硬切断，停止该任务所有 LLM 调用</li>
     *   <li>DEGRADE(2.0×) → 自动降级（切弱模型 + 减步数）</li>
     * </ul>
     */
    @Scheduled(fixedDelay = 60_000) // 每 1 分钟
    public void monitorActiveTasks() {
        for (String taskId : activeTaskIds) {
            BurnRateCalculator.DegradeResult result = burnRateCalc.checkAndDegrade(taskId);
            if (result.shouldDegrade()) {
                log.warn("任务 {} 触发{}: {}", taskId, result.level(), result.reason());
            }
        }
    }

    /** 仪表盘快照：已消耗 / 总预算 / 剩余 / burnRate / 使用百分比 */
    public BurnRateCalculator.Snapshot getDashboardSnapshot() {
        return burnRateCalc.getSnapshot();
    }
}

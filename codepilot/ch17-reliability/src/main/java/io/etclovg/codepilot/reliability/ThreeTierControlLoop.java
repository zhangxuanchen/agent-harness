package io.etclovg.codepilot.reliability;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 三级控制回路协调器：快回路→中回路→慢回路
 * 对应书中 Ch17 — 生产监控可靠性与成本中的控制回路理论
 *
 * <p>三级控制回路架构：
 * <ul>
 *   <li>快回路（FAST）：毫秒级，节点级熔断和实时反馈</li>
 *   <li>中回路（MEDIUM）：分钟级，Canary 分析和灰度发布</li>
 *   <li>慢回路（SLOW）：天级，飞轮优化和全局调优</li>
 * </ul>
 *
 * <p>核心功能：
 * <ul>
 *   <li>协调三级回路：提供 coordinate() 方法协调三级回路的决策</li>
 *   <li>自动自愈：快回路自动熔断和恢复，无需人工介入</li>
 *   <li>降级恢复检测：持续监控降级状态，自动尝试恢复</li>
 *   <li>决策冲突解决：当多级回路决策冲突时，按照优先级解决</li>
 * </ul>
 *
 * <p>设计理念（参考控制理论）：
 * <ul>
 *   <li>快回路：响应速度最快，但控制范围最小（单节点），优先级最高</li>
 *   <li>中回路：响应速度中等，控制范围中等（多节点），优先级中等</li>
 *   <li>慢回路：响应速度最慢，但控制范围最大（全局优化），优先级最低</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 1. 初始化控制回路
 * controlLoop.initialize(LoopTier.FAST, config);
 * controlLoop.initialize(LoopTier.MEDIUM, config);
 * controlLoop.initialize(LoopTier.SLOW, config);
 *
 * // 2. 协调三级回路
 * CoordinationResult result = controlLoop.coordinate("node-123");
 *
 * // 3. 根据协调结果采取行动
 * if (result.shouldTakeAction()) {
 *     applyAction(result.decision());
 * }
 * }</pre>
 */
@Component
public class ThreeTierControlLoop {

    /** 控制回路配置：LoopTier -> ControlLoopConfig */
    private final Map<LoopTier, ControlLoopConfig> loopConfigs = new ConcurrentHashMap<>();

    /** 控制回路状态：LoopTier -> LoopState */
    private final Map<LoopTier, LoopState> loopStates = new ConcurrentHashMap<>();

    /** 节点 SLI 注册表（依赖注入） */
    private final NodeTypeSLIRegistry sliRegistry;

    /** 预算执行器（依赖注入） */
    private final FourLayerBudgetEnforcer budgetEnforcer;

    /** 决策历史：LoopTier -> 决策列表 */
    private final Map<LoopTier, List<LoopDecision>> decisionHistory = new ConcurrentHashMap<>();

    /** 定时执行器：用于周期性检查 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    /** Canary 流量分配：nodeId -> traffic ratio */
    private final Map<String, Double> canaryTraffic = new ConcurrentHashMap<>();

    public ThreeTierControlLoop(NodeTypeSLIRegistry sliRegistry, FourLayerBudgetEnforcer budgetEnforcer) {
        this.sliRegistry = sliRegistry;
        this.budgetEnforcer = budgetEnforcer;

        // 初始化三级控制回路
        for (LoopTier tier : LoopTier.values()) {
            loopConfigs.put(tier, ControlLoopConfig.getDefault(tier));
            loopStates.put(tier, LoopState.initial(tier));
            decisionHistory.put(tier, Collections.synchronizedList(new ArrayList<>()));
        }

        // 启动定时检查任务
        startPeriodicChecks();
    }

    /**
     * 初始化控制回路
     *
     * @param tier 回路层级
     * @param config 回路配置
     */
    public void initialize(LoopTier tier, ControlLoopConfig config) {
        loopConfigs.put(tier, config);
        loopStates.put(tier, LoopState.initial(tier));
    }

    /**
     * 协调三级控制回路
     * 对应书中 Ch17 的多级控制回路协调逻辑
     *
     * @param nodeId 节点 ID
     * @return 协调结果
     */
    public CoordinationResult coordinate(String nodeId) {
        // 1. 获取三级回路的决策
        LoopDecision fastDecision = executeFastLoop(nodeId);
        LoopDecision mediumDecision = executeMediumLoop(nodeId);
        LoopDecision slowDecision = executeSlowLoop(nodeId);

        // 2. 决策优先级：快回路 > 中回路 > 慢回路
        // 如果快回路决策是熔断，立即执行
        if (fastDecision.action() == LoopDecision.DecisionAction.CIRCUIT_OPEN) {
            return new CoordinationResult(
                true,
                fastDecision,
                "Fast loop circuit triggered: " + fastDecision.triggerReason(),
                Instant.now()
            );
        }

        // 如果中回路决策是 Canary 调整，执行
        if (mediumDecision.action() == LoopDecision.DecisionAction.CANARY_EXPAND
            || mediumDecision.action() == LoopDecision.DecisionAction.CANARY_SHRINK) {
            return new CoordinationResult(
                true,
                mediumDecision,
                "Medium loop canary adjustment: " + mediumDecision.triggerReason(),
                Instant.now()
            );
        }

        // 如果慢回路决策是优化调整，执行
        if (slowDecision.action() == LoopDecision.DecisionAction.OPTIMIZE) {
            return new CoordinationResult(
                true,
                slowDecision,
                "Slow loop optimization: " + slowDecision.triggerReason(),
                Instant.now()
            );
        }

        // 3. 无冲突，返回正常状态
        return new CoordinationResult(
            false,
            LoopDecision.noop(LoopTier.FAST),
            "All loops are normal",
            Instant.now()
        );
    }

    /**
     * 执行快回路（毫秒级，节点级熔断）
     * 对应书中 Ch17 的快回路逻辑
     *
     * @param nodeId 节点 ID
     * @return 决策结果
     */
    private LoopDecision executeFastLoop(String nodeId) {
        LoopTier tier = LoopTier.FAST;
        ControlLoopConfig config = loopConfigs.get(tier);
        LoopState state = loopStates.get(tier);

        // 1. 获取节点的 SLI
        NodeSLI sli = sliRegistry.getNodeSLI(nodeId);

        // 2. 检查是否需要熔断
        if (sli.successRate() < config.circuitThreshold()) {
            // 触发熔断
            LoopDecision decision = LoopDecision.circuitOpen(
                tier,
                String.format("Success rate %.2f%% below threshold %.2f%%",
                    sli.successRate() * 100, config.circuitThreshold() * 100),
                new String[]{nodeId}
            );

            // 记录决策历史
            recordDecision(tier, decision);

            // 更新状态
            loopStates.put(tier, state.applyDecision(decision));

            return decision;
        }

        // 3. 检查是否需要恢复
        if (state.healthState() == LoopState.HealthState.CIRCUITED
            && sli.successRate() >= config.recoveryThreshold()) {
            // 触发恢复
            LoopDecision decision = LoopDecision.recovery(
                tier,
                String.format("Success rate %.2f%% above recovery threshold %.2f%%",
                    sli.successRate() * 100, config.recoveryThreshold() * 100),
                new String[]{nodeId}
            );

            // 记录决策历史
            recordDecision(tier, decision);

            // 更新状态
            loopStates.put(tier, state.applyDecision(decision));

            return decision;
        }

        // 4. 正常状态
        return LoopDecision.noop(tier);
    }

    /**
     * 执行中回路（分钟级，Canary 分析）
     * 对应书中 Ch17 的中回路逻辑
     *
     * @param nodeId 节点 ID
     * @return 决策结果
     */
    private LoopDecision executeMediumLoop(String nodeId) {
        LoopTier tier = LoopTier.MEDIUM;
        ControlLoopConfig config = loopConfigs.get(tier);
        LoopState state = loopStates.get(tier);

        // 1. 检查是否需要扩大 Canary 流量
        if (state.healthState() == LoopState.HealthState.NORMAL) {
            // 获取节点的 SLI
            NodeSLI sli = sliRegistry.getNodeSLI(nodeId);

            // 如果 SLI 健康，逐步扩大 Canary 流量
            if (sli.healthScore() > 0.9) {
                double currentRatio = canaryTraffic.getOrDefault(nodeId, 0.0);
                double newRatio = Math.min(1.0, currentRatio + 0.1);

                if (newRatio > currentRatio) {
                    canaryTraffic.put(nodeId, newRatio);

                    LoopDecision decision = new LoopDecision(
                        tier,
                        LoopDecision.DecisionAction.CANARY_EXPAND,
                        String.format("Canary traffic expanded to %.1f%%", newRatio * 100),
                        Map.of("ratio", newRatio),
                        Instant.now(),
                        0.85,
                        new String[]{nodeId}
                    );

                    recordDecision(tier, decision);
                    return decision;
                }
            }
        }

        // 2. 检查是否需要收缩 Canary 流量
        if (state.healthState() == LoopState.HealthState.DEGRADED) {
            double currentRatio = canaryTraffic.getOrDefault(nodeId, 0.0);
            double newRatio = Math.max(0.0, currentRatio - 0.1);

            if (newRatio < currentRatio) {
                canaryTraffic.put(nodeId, newRatio);

                LoopDecision decision = new LoopDecision(
                    tier,
                    LoopDecision.DecisionAction.CANARY_SHRINK,
                    String.format("Canary traffic shrunk to %.1f%%", newRatio * 100),
                    Map.of("ratio", newRatio),
                    Instant.now(),
                    0.90,
                    new String[]{nodeId}
                );

                recordDecision(tier, decision);
                return decision;
            }
        }

        // 3. 正常状态
        return LoopDecision.noop(tier);
    }

    /**
     * 执行慢回路（天级，飞轮优化）
     * 对应书中 Ch17 的慢回路逻辑
     *
     * @param nodeId 节点 ID
     * @return 决策结果
     */
    private LoopDecision executeSlowLoop(String nodeId) {
        LoopTier tier = LoopTier.SLOW;
        ControlLoopConfig config = loopConfigs.get(tier);
        LoopState state = loopStates.get(tier);

        // 1. 检查是否需要优化
        // 慢回路通常需要人工审核，除非配置了自动执行
        if (!config.autoExecute()) {
            return LoopDecision.noop(tier);
        }

        // 2. 获取节点的 SLI 和预算状态
        NodeSLI sli = sliRegistry.getNodeSLI(nodeId);
        BudgetState budgetState = budgetEnforcer.getBudgetState(BudgetTier.NODE, nodeId);

        // 3. 根据优化目标生成决策
        switch (config.optimizationGoal()) {
            case COST -> {
                // 成本优化：如果成本过高，触发降级
                if (budgetState != null && budgetState.usageRatio() > 0.8) {
                    LoopDecision decision = LoopDecision.degradation(
                        tier,
                        "Cost optimization: budget usage above 80%",
                        BudgetConfig.DegradationStrategy.MODEL_DOWNGRADE.name(),
                        new String[]{nodeId}
                    );

                    recordDecision(tier, decision);
                    return decision;
                }
            }
            case LATENCY -> {
                // 延迟优化：如果延迟过高，触发优化
                if (sli.p95LatencyMs() > 3000) {
                    LoopDecision decision = new LoopDecision(
                        tier,
                        LoopDecision.DecisionAction.OPTIMIZE,
                        "Latency optimization: P95 latency above 3s",
                        Map.of("target_latency_ms", 1000),
                        Instant.now(),
                        0.80,
                        new String[]{nodeId}
                    );

                    recordDecision(tier, decision);
                    return decision;
                }
            }
            case AVAILABILITY -> {
                // 可用性优化：如果成功率过低，触发熔断
                if (sli.successRate() < config.circuitThreshold()) {
                    LoopDecision decision = LoopDecision.circuitOpen(
                        tier,
                        "Availability optimization: success rate below threshold",
                        new String[]{nodeId}
                    );

                    recordDecision(tier, decision);
                    return decision;
                }
            }
            case BALANCED -> {
                // 均衡优化：综合考虑多个指标
                double healthScore = sli.healthScore();
                if (healthScore < 0.7) {
                    LoopDecision decision = LoopDecision.degradation(
                        tier,
                        String.format("Balanced optimization: health score %.2f below 0.7", healthScore),
                        BudgetConfig.DegradationStrategy.MODEL_DOWNGRADE.name(),
                        new String[]{nodeId}
                    );

                    recordDecision(tier, decision);
                    return decision;
                }
            }
        }

        // 4. 正常状态
        return LoopDecision.noop(tier);
    }

    /**
     * 启动定时检查任务
     */
    private void startPeriodicChecks() {
        // 快回路：每 1 秒检查一次
        scheduler.scheduleAtFixedRate(
            () -> executePeriodicCheck(LoopTier.FAST),
            0, 1, TimeUnit.SECONDS
        );

        // 中回路：每 1 分钟检查一次
        scheduler.scheduleAtFixedRate(
            () -> executePeriodicCheck(LoopTier.MEDIUM),
            0, 1, TimeUnit.MINUTES
        );

        // 慢回路：每 1 小时检查一次
        scheduler.scheduleAtFixedRate(
            () -> executePeriodicCheck(LoopTier.SLOW),
            0, 1, TimeUnit.HOURS
        );
    }

    /**
     * 执行周期性检查
     *
     * @param tier 回路层级
     */
    private void executePeriodicCheck(LoopTier tier) {
        // 1. 检查所有不健康的节点
        List<NodeSLI> unhealthyNodes = sliRegistry.getUnhealthyNodes();

        // 2. 对每个不健康的节点执行控制回路
        for (NodeSLI sli : unhealthyNodes) {
            coordinate(sli.nodeId());
        }

        // 3. 检查预算恢复
        budgetEnforcer.checkAndRecoverAll();
    }

    /**
     * 记录决策历史
     *
     * @param tier 回路层级
     * @param decision 决策
     */
    private void recordDecision(LoopTier tier, LoopDecision decision) {
        List<LoopDecision> history = decisionHistory.get(tier);
        history.add(decision);

        // 保留最近 100 条决策
        if (history.size() > 100) {
            history.remove(0);
        }
    }

    /**
     * 获取控制回路状态
     *
     * @param tier 回路层级
     * @return 回路状态
     */
    public LoopState getLoopState(LoopTier tier) {
        return loopStates.get(tier);
    }

    /**
     * 获取决策历史
     *
     * @param tier 回路层级
     * @param limit 限制数量
     * @return 决策列表
     */
    public List<LoopDecision> getDecisionHistory(LoopTier tier, int limit) {
        List<LoopDecision> history = decisionHistory.get(tier);
        int size = history.size();
        if (size <= limit) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(size - limit, size));
    }

    /**
     * 获取 Canary 流量分配
     *
     * @param nodeId 节点 ID
     * @return Canary 流量比例
     */
    public double getCanaryTrafficRatio(String nodeId) {
        return canaryTraffic.getOrDefault(nodeId, 0.0);
    }

    /**
     * 手动暂停控制回路
     *
     * @param tier 回路层级
     */
    public void pauseLoop(LoopTier tier) {
        LoopState state = loopStates.get(tier);
        loopStates.put(tier, state.pause());
    }

    /**
     * 手动恢复控制回路
     *
     * @param tier 回路层级
     */
    public void resumeLoop(LoopTier tier) {
        LoopState state = loopStates.get(tier);
        loopStates.put(tier, state.resume());
    }

    /**
     * 关闭控制回路（释放资源）
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 协调结果
     */
    public record CoordinationResult(
        /** 是否需要采取行动 */
        boolean shouldTakeAction,

        /** 决策 */
        LoopDecision decision,

        /** 消息 */
        String message,

        /** 时间戳 */
        Instant timestamp
    ) {}
}
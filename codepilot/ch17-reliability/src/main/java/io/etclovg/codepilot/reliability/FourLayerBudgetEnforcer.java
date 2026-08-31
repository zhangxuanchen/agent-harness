package io.etclovg.codepilot.reliability;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 四层硬预算执行器：组织级→用户级→任务级→节点级
 * 对应书中 Ch17 — 生产监控可靠性与成本中的预算控制系统
 *
 * <p>核心功能：
 * <ul>
 *   <li>四层预算控制：组织级、用户级、任务级、节点级</li>
 *   <li>独立熔断策略：每层有独立的熔断阈值和降级策略</li>
 *   <li>硬切断执行：不是告警，而是自动执行降级或切断</li>
 *   <li>自动恢复检测：预算恢复后自动升级到正常状态</li>
 * </ul>
 *
 * <p>设计理念：
 * <ul>
 *   <li>层级越高，熔断阈值越宽松，给下游更多缓冲空间</li>
 *   <li>硬切断优先级：节点级 > 任务级 > 用户级 > 组织级</li>
 *   <li>每层独立决策，避免单点故障</li>
 *   <li>支持预算恢复后的自动升级，实现自适应控制</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 1. 初始化预算配置
 * enforcer.configureBudget(BudgetTier.ORGANIZATION, "org-001", config);
 * enforcer.configureBudget(BudgetTier.USER, "user-123", config);
 * enforcer.configureBudget(BudgetTier.TASK, "task-456", config);
 * enforcer.configureBudget(BudgetTier.NODE, "node-789", config);
 *
 * // 2. 检查并记录消费
 * EnforcementResult result = enforcer.checkAndRecord(
 *     Arrays.asList("org-001", "user-123", "task-456", "node-789"),
 *     0.05  // $0.05
 * );
 *
 * // 3. 根据结果采取行动
 * if (result.shouldEnforce()) {
 *     // 执行降级或切断
 *     applyDegradation(result.strategy());
 * }
 * }</pre>
 */
@Component
public class FourLayerBudgetEnforcer {

    /** 预算配置表：tier -> entityId -> BudgetConfig */
    private final Map<BudgetTier, Map<String, BudgetConfig>> budgetConfigs = new ConcurrentHashMap<>();

    /** 预算状态表：tier -> entityId -> BudgetState */
    private final Map<BudgetTier, Map<String, BudgetState>> budgetStates = new ConcurrentHashMap<>();

    /** 熔断历史记录：用于审计和分析 */
    private final List<CircuitEvent> circuitHistory = Collections.synchronizedList(new ArrayList<>());

    public FourLayerBudgetEnforcer() {
        // 初始化四层预算状态表
        for (BudgetTier tier : BudgetTier.values()) {
            budgetConfigs.put(tier, new ConcurrentHashMap<>());
            budgetStates.put(tier, new ConcurrentHashMap<>());
        }
    }

    /**
     * 配置预算
     *
     * @param tier 预算层级
     * @param entityId 实体 ID
     * @param config 预算配置
     */
    public void configureBudget(BudgetTier tier, String entityId, BudgetConfig config) {
        budgetConfigs.get(tier).put(entityId, config);
        budgetStates.get(tier).put(entityId, BudgetState.initial(config));
    }

    /**
     * 使用默认配置配置预算
     *
     * @param tier 预算层级
     * @param entityId 实体 ID
     */
    public void configureBudget(BudgetTier tier, String entityId) {
        BudgetConfig config = BudgetConfig.getDefault(tier, entityId);
        configureBudget(tier, entityId, config);
    }

    /**
     * 检查并记录消费
     * 对应书中 Ch17 的预算检查逻辑
     *
     * @param entityIds 实体 ID 列表（按层级从高到低：orgId, userId, taskId, nodeId）
     * @param amount 消费金额（美元）
     * @return 执行结果
     */
    public EnforcementResult checkAndRecord(List<String> entityIds, double amount) {
        if (entityIds == null || entityIds.size() != 4) {
            return new EnforcementResult(
                false,
                BudgetTier.ORGANIZATION,
                "Invalid entity IDs",
                BudgetConfig.DegradationStrategy.REJECT,
                BudgetState.CircuitState.NORMAL,
                Instant.now()
            );
        }

        String orgId = entityIds.get(0);
        String userId = entityIds.get(1);
        String taskId = entityIds.get(2);
        String nodeId = entityIds.get(3);

        // 按层级从低到高检查预算状态（节点级 -> 任务级 -> 用户级 -> 组织级）
        // 低层级优先检查，实现快速熔断
        List<BudgetCheck> checks = Arrays.asList(
            new BudgetCheck(BudgetTier.NODE, nodeId),
            new BudgetCheck(BudgetTier.TASK, taskId),
            new BudgetCheck(BudgetTier.USER, userId),
            new BudgetCheck(BudgetTier.ORGANIZATION, orgId)
        );

        // 1. 先检查是否需要熔断（不记录消费）
        for (BudgetCheck check : checks) {
            EnforcementResult result = checkBudgetState(check.tier, check.entityId);
            if (result.shouldEnforce()) {
                return result;
            }
        }

        // 2. 检查通过，记录消费
        for (BudgetCheck check : checks) {
            recordConsumption(check.tier, check.entityId, amount);
        }

        // 3. 再次检查是否触发熔断（记录后可能触发）
        for (BudgetCheck check : checks) {
            EnforcementResult result = checkBudgetState(check.tier, check.entityId);
            if (result.shouldEnforce()) {
                // 触发熔断，记录事件
                recordCircuitEvent(check.tier, check.entityId, result);
                return result;
            }
        }

        // 4. 所有层级检查通过，返回正常结果
        return new EnforcementResult(
            false,
            BudgetTier.NODE,
            "Budget check passed",
            BudgetConfig.DegradationStrategy.REJECT,
            BudgetState.CircuitState.NORMAL,
            Instant.now()
        );
    }

    /**
     * 检查预算状态
     *
     * @param tier 预算层级
     * @param entityId 实体 ID
     * @return 执行结果
     */
    private EnforcementResult checkBudgetState(BudgetTier tier, String entityId) {
        BudgetConfig config = budgetConfigs.get(tier).get(entityId);
        BudgetState state = budgetStates.get(tier).get(entityId);

        if (config == null || state == null) {
            // 未配置预算，默认放行
            return new EnforcementResult(
                false,
                tier,
                "No budget configured",
                BudgetConfig.DegradationStrategy.REJECT,
                BudgetState.CircuitState.NORMAL,
                Instant.now()
            );
        }

        if (state.circuitState() == BudgetState.CircuitState.HARD_CIRCUIT) {
            return new EnforcementResult(
                true,
                tier,
                "Hard circuit already triggered",
                config.degradationStrategy(),
                BudgetState.CircuitState.HARD_CIRCUIT,
                Instant.now()
            );
        }

        if (state.circuitState() == BudgetState.CircuitState.SOFT_CIRCUIT) {
            return new EnforcementResult(
                true,
                tier,
                "Soft circuit already triggered",
                config.degradationStrategy(),
                BudgetState.CircuitState.SOFT_CIRCUIT,
                Instant.now()
            );
        }

        double usageRatio = state.usageRatio();

        if (usageRatio >= config.hardThresholdRatio()) {
            return new EnforcementResult(
                true,
                tier,
                String.format("Hard circuit triggered: usage %.2f%% >= threshold %.2f%%",
                    usageRatio * 100, config.hardThresholdRatio() * 100),
                config.degradationStrategy(),
                BudgetState.CircuitState.HARD_CIRCUIT,
                Instant.now()
            );
        }

        if (usageRatio >= config.softThresholdRatio()) {
            return new EnforcementResult(
                true,
                tier,
                String.format("Soft circuit triggered: usage %.2f%% >= threshold %.2f%%",
                    usageRatio * 100, config.softThresholdRatio() * 100),
                config.degradationStrategy(),
                BudgetState.CircuitState.SOFT_CIRCUIT,
                Instant.now()
            );
        }

        return new EnforcementResult(
            false,
            tier,
            "Budget check passed",
            config.degradationStrategy(),
            BudgetState.CircuitState.NORMAL,
            Instant.now()
        );
    }

    /**
     * 记录消费
     *
     * @param tier 预算层级
     * @param entityId 实体 ID
     * @param amount 消费金额
     */
    private void recordConsumption(BudgetTier tier, String entityId, double amount) {
        Map<String, BudgetState> states = budgetStates.get(tier);
        BudgetState state = states.get(entityId);

        if (state != null) {
            BudgetState newState = state.consume(amount);
            states.put(entityId, newState);

            // 检查是否需要触发熔断
            BudgetConfig config = budgetConfigs.get(tier).get(entityId);
            if (config != null) {
                if (newState.usageRatio() >= config.hardThresholdRatio()) {
                    BudgetState circuitState = newState.triggerHardCircuit(config.degradationStrategy());
                    states.put(entityId, circuitState);
                } else if (newState.usageRatio() >= config.softThresholdRatio()) {
                    BudgetState circuitState = newState.triggerSoftCircuit(config.degradationStrategy());
                    states.put(entityId, circuitState);
                }
            }
        }
    }

    /**
     * 执行硬切断
     * 对应书中 Ch17 的硬切断执行逻辑
     *
     * @param tier 预算层级
     * @param entityId 实体 ID
     * @return 执行结果
     */
    public EnforcementResult enforce(BudgetTier tier, String entityId) {
        BudgetConfig config = budgetConfigs.get(tier).get(entityId);
        BudgetState state = budgetStates.get(tier).get(entityId);

        if (config == null || state == null) {
            return new EnforcementResult(
                false,
                tier,
                "No budget configured",
                BudgetConfig.DegradationStrategy.REJECT,
                BudgetState.CircuitState.NORMAL,
                Instant.now()
            );
        }

        // 强制触发硬熔断
        BudgetState circuitState = state.triggerHardCircuit(config.degradationStrategy());
        budgetStates.get(tier).put(entityId, circuitState);

        // 记录熔断事件
        EnforcementResult result = new EnforcementResult(
            true,
            tier,
            "Enforced hard circuit",
            config.degradationStrategy(),
            BudgetState.CircuitState.HARD_CIRCUIT,
            Instant.now()
        );
        recordCircuitEvent(tier, entityId, result);

        return result;
    }

    /**
     * 检查并恢复预算状态
     * 对应书中 Ch17 的自动恢复检测逻辑
     *
     * @param tier 预算层级
     * @param entityId 实体 ID
     * @return 是否恢复成功
     */
    public boolean checkAndRecover(BudgetTier tier, String entityId) {
        BudgetConfig config = budgetConfigs.get(tier).get(entityId);
        BudgetState state = budgetStates.get(tier).get(entityId);

        if (config == null || state == null) {
            return false;
        }

        // 检查是否可以恢复
        if (state.canRecover(config.recoveryThresholdRatio())) {
            BudgetState recoveredState = state.recover();
            budgetStates.get(tier).put(entityId, recoveredState);

            // 记录恢复事件
            circuitHistory.add(new CircuitEvent(
                tier,
                entityId,
                "RECOVERED",
                "Budget recovered: usage " + String.format("%.2f%%", state.usageRatio() * 100),
                Instant.now()
            ));

            return true;
        }

        return false;
    }

    /**
     * 批量检查并恢复所有预算状态
     */
    public void checkAndRecoverAll() {
        for (BudgetTier tier : BudgetTier.values()) {
            Map<String, BudgetState> states = budgetStates.get(tier);
            for (String entityId : states.keySet()) {
                checkAndRecover(tier, entityId);
            }
        }
    }

    /**
     * 获取预算状态
     *
     * @param tier 预算层级
     * @param entityId 实体 ID
     * @return 预算状态
     */
    public BudgetState getBudgetState(BudgetTier tier, String entityId) {
        return budgetStates.get(tier).getOrDefault(entityId, null);
    }

    /**
     * 获取预算配置
     *
     * @param tier 预算层级
     * @param entityId 实体 ID
     * @return 预算配置
     */
    public BudgetConfig getBudgetConfig(BudgetTier tier, String entityId) {
        return budgetConfigs.get(tier).getOrDefault(entityId, null);
    }

    /**
     * 重置预算状态（清零消费）
     *
     * @param tier 预算层级
     * @param entityId 实体 ID
     */
    public void resetBudget(BudgetTier tier, String entityId) {
        BudgetConfig config = budgetConfigs.get(tier).get(entityId);
        if (config != null) {
            budgetStates.get(tier).put(entityId, BudgetState.initial(config));
        }
    }

    /**
     * 获取所有熔断状态的预算
     *
     * @return 熔断预算列表
     */
    public List<BudgetState> getCircuitedBudgets() {
        List<BudgetState> result = new ArrayList<>();
        for (BudgetTier tier : BudgetTier.values()) {
            Map<String, BudgetState> states = budgetStates.get(tier);
            for (BudgetState state : states.values()) {
                if (state.circuitState() != BudgetState.CircuitState.NORMAL) {
                    result.add(state);
                }
            }
        }
        return result;
    }

    /**
     * 获取熔断历史记录
     *
     * @param limit 限制数量
     * @return 熔断事件列表
     */
    public List<CircuitEvent> getCircuitHistory(int limit) {
        int size = circuitHistory.size();
        if (size <= limit) {
            return new ArrayList<>(circuitHistory);
        }
        return new ArrayList<>(circuitHistory.subList(size - limit, size));
    }

    /**
     * 记录熔断事件
     */
    private void recordCircuitEvent(BudgetTier tier, String entityId, EnforcementResult result) {
        circuitHistory.add(new CircuitEvent(
            tier,
            entityId,
            result.circuitState().name(),
            result.message(),
            Instant.now()
        ));
    }

    /**
     * 预算检查项
     */
    private record BudgetCheck(BudgetTier tier, String entityId) {}

    /**
     * 执行结果
     */
    public record EnforcementResult(
        /** 是否需要执行熔断 */
        boolean shouldEnforce,

        /** 触发的预算层级 */
        BudgetTier triggeredTier,

        /** 消息 */
        String message,

        /** 建议的降级策略 */
        BudgetConfig.DegradationStrategy strategy,

        /** 熔断状态 */
        BudgetState.CircuitState circuitState,

        /** 时间戳 */
        Instant timestamp
    ) {}

    /**
     * 熔断事件
     */
    public record CircuitEvent(
        /** 预算层级 */
        BudgetTier tier,

        /** 实体 ID */
        String entityId,

        /** 事件类型 */
        String eventType,

        /** 消息 */
        String message,

        /** 时间戳 */
        Instant timestamp
    ) {}
}
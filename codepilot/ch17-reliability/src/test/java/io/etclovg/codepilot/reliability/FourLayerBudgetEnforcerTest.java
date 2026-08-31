package io.etclovg.codepilot.reliability;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FourLayerBudgetEnforcer 单元测试")
class FourLayerBudgetEnforcerTest {

    private FourLayerBudgetEnforcer enforcer;

    @BeforeEach
    void setUp() {
        enforcer = new FourLayerBudgetEnforcer();
    }

    private BudgetConfig createConfig(BudgetTier tier, String entityId,
                                      double budgetLimit, double softThreshold,
                                      double hardThreshold, double recoveryThreshold) {
        return new BudgetConfig(
                tier, entityId, budgetLimit,
                softThreshold, hardThreshold,
                BudgetConfig.DegradationStrategy.REJECT,
                Duration.ofDays(30),
                true,
                recoveryThreshold
        );
    }

    private List<String> buildEntityIds(String orgId, String userId, String taskId, String nodeId) {
        return List.of(orgId, userId, taskId, nodeId);
    }

    private void configureDefaults() {
        enforcer.configureBudget(BudgetTier.NODE, "node-1");
        enforcer.configureBudget(BudgetTier.TASK, "task-1");
        enforcer.configureBudget(BudgetTier.USER, "user-1");
        enforcer.configureBudget(BudgetTier.ORGANIZATION, "org-1");
    }

    private void configureWithCustomNode(double limit, double soft, double hard, double recovery) {
        enforcer.configureBudget(BudgetTier.NODE, "node-1",
                createConfig(BudgetTier.NODE, "node-1", limit, soft, hard, recovery));
        enforcer.configureBudget(BudgetTier.TASK, "task-1");
        enforcer.configureBudget(BudgetTier.USER, "user-1");
        enforcer.configureBudget(BudgetTier.ORGANIZATION, "org-1");
    }

    // ==================== 预算配置 ====================

    @Nested
    @DisplayName("预算配置 configureBudget")
    class ConfigureBudgetTests {

        @Test
        @DisplayName("配置预算后可查询")
        void configureBudget_stored() {
            BudgetConfig config = createConfig(BudgetTier.NODE, "node-1", 10.0, 0.75, 0.80, 0.50);
            enforcer.configureBudget(BudgetTier.NODE, "node-1", config);

            BudgetConfig retrieved = enforcer.getBudgetConfig(BudgetTier.NODE, "node-1");
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.budgetLimitUsd()).isEqualTo(10.0);
        }

        @Test
        @DisplayName("配置预算后状态为初始 NORMAL")
        void configureBudget_initialState() {
            enforcer.configureBudget(BudgetTier.NODE, "node-1");

            BudgetState state = enforcer.getBudgetState(BudgetTier.NODE, "node-1");
            assertThat(state).isNotNull();
            assertThat(state.circuitState()).isEqualTo(BudgetState.CircuitState.NORMAL);
            assertThat(state.consumedUsd()).isEqualTo(0.0);
        }
    }

    // ==================== 预算检查 ====================

    @Nested
    @DisplayName("预算检查 checkAndRecord")
    class CheckAndRecordTests {

        @Test
        @DisplayName("预算内消费通过并记录")
        void withinBudget_passesAndRecords() {
            configureDefaults();

            FourLayerBudgetEnforcer.EnforcementResult result = enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );

            assertThat(result.shouldEnforce()).isFalse();

            BudgetState nodeState = enforcer.getBudgetState(BudgetTier.NODE, "node-1");
            assertThat(nodeState.consumedUsd()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("超过软阈值触发软熔断")
        void exceedSoftThreshold_triggersSoftCircuit() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            FourLayerBudgetEnforcer.EnforcementResult first = enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );
            assertThat(first.shouldEnforce()).isFalse();

            FourLayerBudgetEnforcer.EnforcementResult second = enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    2.6
            );
            assertThat(second.shouldEnforce()).isTrue();
            assertThat(second.circuitState()).isEqualTo(BudgetState.CircuitState.SOFT_CIRCUIT);
        }

        @Test
        @DisplayName("超过硬阈值触发硬熔断")
        void exceedHardThreshold_triggersHardCircuit() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );

            FourLayerBudgetEnforcer.EnforcementResult second = enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    3.5
            );
            assertThat(second.shouldEnforce()).isTrue();
            assertThat(second.circuitState()).isEqualTo(BudgetState.CircuitState.HARD_CIRCUIT);
        }

        @Test
        @DisplayName("未配置的实体默认放行")
        void unconfiguredEntity_defaultsToPass() {
            FourLayerBudgetEnforcer.EnforcementResult result = enforcer.checkAndRecord(
                    buildEntityIds("org-x", "user-x", "task-x", "node-x"),
                    100.0
            );

            assertThat(result.shouldEnforce()).isFalse();
        }

        @Test
        @DisplayName("预算检查不通过时消费被阻止")
        void budgetExceeded_blocksFurther() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );
            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    3.5
            );

            FourLayerBudgetEnforcer.EnforcementResult third = enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    1.0
            );
            assertThat(third.shouldEnforce()).isTrue();
        }

        @Test
        @DisplayName("无效的实体 ID 列表被拒绝")
        void invalidEntityIds_rejected() {
            FourLayerBudgetEnforcer.EnforcementResult result = enforcer.checkAndRecord(
                    List.of("only-three", "user", "task"),
                    5.0
            );

            assertThat(result.shouldEnforce()).isFalse();
            assertThat(result.message()).isEqualTo("Invalid entity IDs");
        }
    }

    // ==================== 强制熔断 ====================

    @Nested
    @DisplayName("强制熔断 enforce")
    class EnforceTests {

        @Test
        @DisplayName("enforce 立即触发硬熔断")
        void enforce_triggersHardCircuit() {
            enforcer.configureBudget(BudgetTier.NODE, "node-1");

            FourLayerBudgetEnforcer.EnforcementResult result = enforcer.enforce(BudgetTier.NODE, "node-1");

            assertThat(result.shouldEnforce()).isTrue();
            assertThat(result.circuitState()).isEqualTo(BudgetState.CircuitState.HARD_CIRCUIT);
            assertThat(result.message()).isEqualTo("Enforced hard circuit");

            BudgetState state = enforcer.getBudgetState(BudgetTier.NODE, "node-1");
            assertThat(state.circuitState()).isEqualTo(BudgetState.CircuitState.HARD_CIRCUIT);
        }

        @Test
        @DisplayName("enforce 对未配置实体返回放行")
        void enforce_unconfiguredEntity_passes() {
            FourLayerBudgetEnforcer.EnforcementResult result = enforcer.enforce(BudgetTier.NODE, "node-x");

            assertThat(result.shouldEnforce()).isFalse();
            assertThat(result.message()).isEqualTo("No budget configured");
        }

        @Test
        @DisplayName("enforce 后 checkAndRecord 被阻止")
        void enforce_blocksSubsequentChecks() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.enforce(BudgetTier.NODE, "node-1");

            FourLayerBudgetEnforcer.EnforcementResult result = enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    1.0
            );
            assertThat(result.shouldEnforce()).isTrue();
            assertThat(result.circuitState()).isEqualTo(BudgetState.CircuitState.HARD_CIRCUIT);
        }
    }

    // ==================== 恢复检测 ====================

    @Nested
    @DisplayName("恢复检测 checkAndRecover")
    class RecoverTests {

        @Test
        @DisplayName("软熔断后使用率低于恢复阈值时可恢复")
        void softCircuit_withinRecoveryThreshold_recovers() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.90);

            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );
            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    2.6
            );

            BudgetState state = enforcer.getBudgetState(BudgetTier.NODE, "node-1");
            assertThat(state.circuitState()).isEqualTo(BudgetState.CircuitState.SOFT_CIRCUIT);
            assertThat(state.usageRatio()).isEqualTo(0.76);

            boolean recovered = enforcer.checkAndRecover(BudgetTier.NODE, "node-1");
            assertThat(recovered).isTrue();

            BudgetState recoveredState = enforcer.getBudgetState(BudgetTier.NODE, "node-1");
            assertThat(recoveredState.circuitState()).isEqualTo(BudgetState.CircuitState.NORMAL);
        }

        @Test
        @DisplayName("硬熔断后使用率高于恢复阈值时不可恢复")
        void hardCircuit_aboveRecoveryThreshold_notRecovered() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );
            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    3.5
            );

            boolean recovered = enforcer.checkAndRecover(BudgetTier.NODE, "node-1");
            assertThat(recovered).isFalse();
        }

        @Test
        @DisplayName("未配置实体恢复返回 false")
        void unconfiguredEntity_recover_returnsFalse() {
            boolean recovered = enforcer.checkAndRecover(BudgetTier.NODE, "node-x");
            assertThat(recovered).isFalse();
        }

        @Test
        @DisplayName("NORMAL 状态下不需要恢复")
        void normalState_recover_returnsFalse() {
            enforcer.configureBudget(BudgetTier.NODE, "node-1",
                    createConfig(BudgetTier.NODE, "node-1", 10.0, 0.75, 0.80, 0.90));

            boolean recovered = enforcer.checkAndRecover(BudgetTier.NODE, "node-1");
            assertThat(recovered).isFalse();
        }
    }

    // ==================== 重置 ====================

    @Nested
    @DisplayName("预算重置 resetBudget")
    class ResetTests {

        @Test
        @DisplayName("重置后消费清零，状态恢复 NORMAL")
        void resetBudget_clearsConsumption() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );

            BudgetState before = enforcer.getBudgetState(BudgetTier.NODE, "node-1");
            assertThat(before.consumedUsd()).isEqualTo(5.0);

            enforcer.resetBudget(BudgetTier.NODE, "node-1");

            BudgetState after = enforcer.getBudgetState(BudgetTier.NODE, "node-1");
            assertThat(after.consumedUsd()).isEqualTo(0.0);
            assertThat(after.circuitState()).isEqualTo(BudgetState.CircuitState.NORMAL);
        }

        @Test
        @DisplayName("重置后可再次消费")
        void resetBudget_allowsFurtherConsumption() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.enforce(BudgetTier.NODE, "node-1");

            enforcer.resetBudget(BudgetTier.NODE, "node-1");

            FourLayerBudgetEnforcer.EnforcementResult result = enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    3.0
            );
            assertThat(result.shouldEnforce()).isFalse();
        }
    }

    // ==================== 熔断历史 ====================

    @Nested
    @DisplayName("熔断历史 getCircuitHistory")
    class CircuitHistoryTests {

        @Test
        @DisplayName("软熔断触发时记录事件")
        void softCircuit_recordsEvent() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );
            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    2.6
            );

            List<FourLayerBudgetEnforcer.CircuitEvent> history = enforcer.getCircuitHistory(10);
            assertThat(history).isNotEmpty();
            assertThat(history).anyMatch(e -> e.tier() == BudgetTier.NODE
                    && e.entityId().equals("node-1")
                    && e.eventType().equals("SOFT_CIRCUIT"));
        }

        @Test
        @DisplayName("硬熔断触发时记录事件")
        void hardCircuit_recordsEvent() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );
            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    3.5
            );

            List<FourLayerBudgetEnforcer.CircuitEvent> history = enforcer.getCircuitHistory(10);
            assertThat(history).anyMatch(e -> e.eventType().equals("HARD_CIRCUIT"));
        }

        @Test
        @DisplayName("enforce 触发时记录事件")
        void enforce_recordsEvent() {
            enforcer.configureBudget(BudgetTier.NODE, "node-1");

            enforcer.enforce(BudgetTier.NODE, "node-1");

            List<FourLayerBudgetEnforcer.CircuitEvent> history = enforcer.getCircuitHistory(10);
            assertThat(history).anyMatch(e -> e.eventType().equals("HARD_CIRCUIT")
                    && e.tier() == BudgetTier.NODE);
        }

        @Test
        @DisplayName("恢复时记录事件")
        void recover_recordsEvent() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.90);

            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );
            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    2.6
            );

            enforcer.checkAndRecover(BudgetTier.NODE, "node-1");

            List<FourLayerBudgetEnforcer.CircuitEvent> history = enforcer.getCircuitHistory(10);
            assertThat(history).anyMatch(e -> e.eventType().equals("RECOVERED"));
        }

        @Test
        @DisplayName("历史记录可按限制数量获取")
        void history_withLimit() {
            enforcer.configureBudget(BudgetTier.NODE, "node-1");
            enforcer.configureBudget(BudgetTier.TASK, "task-1");
            enforcer.configureBudget(BudgetTier.USER, "user-1");

            enforcer.enforce(BudgetTier.NODE, "node-1");
            enforcer.enforce(BudgetTier.TASK, "task-1");
            enforcer.enforce(BudgetTier.USER, "user-1");

            List<FourLayerBudgetEnforcer.CircuitEvent> limited = enforcer.getCircuitHistory(2);
            assertThat(limited).hasSize(2);

            List<FourLayerBudgetEnforcer.CircuitEvent> all = enforcer.getCircuitHistory(10);
            assertThat(all).hasSize(3);
        }
    }

    // ==================== 熔断预算查询 ====================

    @Nested
    @DisplayName("熔断预算查询 getCircuitedBudgets")
    class CircuitedBudgetsTests {

        @Test
        @DisplayName("无熔断时返回空列表")
        void noCircuits_emptyList() {
            enforcer.configureBudget(BudgetTier.NODE, "node-1");

            List<BudgetState> circuited = enforcer.getCircuitedBudgets();
            assertThat(circuited).isEmpty();
        }

        @Test
        @DisplayName("有熔断时返回对应状态")
        void hasCircuits_returnsCircuitedStates() {
            enforcer.configureBudget(BudgetTier.NODE, "node-1");
            enforcer.configureBudget(BudgetTier.TASK, "task-1");

            enforcer.enforce(BudgetTier.NODE, "node-1");

            List<BudgetState> circuited = enforcer.getCircuitedBudgets();
            assertThat(circuited).hasSize(1);
            assertThat(circuited.get(0).circuitState()).isEqualTo(BudgetState.CircuitState.HARD_CIRCUIT);
        }

        @Test
        @DisplayName("重置后熔断预算被清除")
        void reset_clearsCircuitedBudget() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.enforce(BudgetTier.NODE, "node-1");
            assertThat(enforcer.getCircuitedBudgets()).hasSize(1);

            enforcer.resetBudget(BudgetTier.NODE, "node-1");
            assertThat(enforcer.getCircuitedBudgets()).isEmpty();
        }
    }

    // ==================== 多层联动 ====================

    @Nested
    @DisplayName("多层预算联动")
    class MultiTierTests {

        @Test
        @DisplayName("低层级熔断优先阻止（节点级）")
        void lowTierCircuit_blocksBeforeHigher() {
            configureWithCustomNode(10.0, 0.75, 0.80, 0.50);

            enforcer.enforce(BudgetTier.NODE, "node-1");

            FourLayerBudgetEnforcer.EnforcementResult result = enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    1.0
            );
            assertThat(result.shouldEnforce()).isTrue();
            assertThat(result.triggeredTier()).isEqualTo(BudgetTier.NODE);
        }

        @Test
        @DisplayName("任务级预算超限时节点级正常也被阻止")
        void taskLevelExceeded_blocksDespiteNormalNode() {
            enforcer.configureBudget(BudgetTier.NODE, "node-1",
                    createConfig(BudgetTier.NODE, "node-1", 10.0, 0.85, 0.90, 0.50));
            enforcer.configureBudget(BudgetTier.TASK, "task-1",
                    createConfig(BudgetTier.TASK, "task-1", 10.0, 0.60, 0.70, 0.50));
            enforcer.configureBudget(BudgetTier.USER, "user-1");
            enforcer.configureBudget(BudgetTier.ORGANIZATION, "org-1");

            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    5.0
            );
            enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    3.0
            );

            FourLayerBudgetEnforcer.EnforcementResult result = enforcer.checkAndRecord(
                    buildEntityIds("org-1", "user-1", "task-1", "node-1"),
                    1.0
            );
            assertThat(result.shouldEnforce()).isTrue();
            assertThat(result.triggeredTier()).isEqualTo(BudgetTier.TASK);
        }
    }
}
package io.etclovg.codepilot.reliability;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Chapter-17 四层预算执行器 + AgentScope @Tool 注解集成测试。
 *
 * <p>替代原有的"表演性验证测试"，真正调用 AgentScope {@link Toolkit} API 验证 @Tool 注解扫描，
 * 并直接测试 {@link FourLayerBudgetEnforcer} 的四层预算检查与熔断行为。
 *
 * <p>验证内容：
 * <ol>
 *   <li>AgentScope {@code @Tool} 注解（{@code io.agentscope.core.tool.Tool}）被 Toolkit 正确扫描</li>
 *   <li>非 @Tool 方法不被误扫描（切面选择性）</li>
 *   <li>{@link FourLayerBudgetEnforcer#checkAndRecord} 四层预算扣减正确</li>
 *   <li>节点级预算超限触发软/硬熔断</li>
 *   <li>预算未配置时放行，避免破坏未配置工具的调用</li>
 * </ol>
 */
@DisplayName("Ch17 四层预算 + AgentScope @Tool 集成测试")
class Ch17BudgetEnforcementIntegrationTest {

    // ============================================================
    // @Tool 注解扫描验证（使用真实 AgentScope Toolkit API）
    // ============================================================
    @Nested
    @DisplayName("AgentScope @Tool 注解扫描")
    class ToolAnnotationScanning {

        @Test
        @DisplayName("Toolkit.registerTool(SampleToolService) 扫描到 3 个 @Tool 方法")
        void toolkitScans_agentScopeToolAnnotatedMethods() {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new SampleToolService());

            Set<String> toolNames = toolkit.getToolNames();

            assertThat(toolNames)
                    .containsExactlyInAnyOrder("getOrderHistory", "refundOrder", "escalateToHuman");
        }

        @Test
        @DisplayName("非 @Tool 的 helper 方法 logAccess 不被 Toolkit 扫描")
        void helperMethod_notScannedByToolkit() {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new SampleToolService());

            Set<String> toolNames = toolkit.getToolNames();

            assertThat(toolNames).doesNotContain("logAccess");
        }

        @Test
        @DisplayName("SampleToolService 的 @Tool 注解来自 io.agentscope.core.tool（非 Spring AI）")
        void toolAnnotation_isAgentScopePackage() {
            long count = Stream.of(SampleToolService.class.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(io.agentscope.core.tool.Tool.class))
                    .count();

            assertThat(count).isEqualTo(3);
        }

        @Test
        @DisplayName("SampleToolService 不含任何 Spring AI 注解")
        void noSpringAiAnnotations() {
            long count = Stream.of(SampleToolService.class.getDeclaredMethods())
                    .filter(m -> Arrays.stream(m.getAnnotations())
                            .anyMatch(a -> a.annotationType().getName().startsWith("org.springframework.ai")))
                    .count();

            assertThat(count).isZero();
        }
    }

    // ============================================================
    // 四层预算执行器行为验证
    // ============================================================
    @Nested
    @DisplayName("FourLayerBudgetEnforcer 预算检查与熔断")
    class BudgetEnforcementBehavior {

        private FourLayerBudgetEnforcer enforcer;

        @BeforeEach
        void setUp() {
            enforcer = new FourLayerBudgetEnforcer();
        }

        @Test
        @DisplayName("单次工具调用 → 节点级预算正确扣减 $0.05")
        void singleToolCall_recordsNodeTierConsumption() {
            configureFourTierBudgets(enforcer, 1000.0);
            List<String> entityIds = fourTierEntityIds();

            var result = enforcer.checkAndRecord(entityIds, 0.05);

            assertThat(result.shouldEnforce()).isFalse();
            assertThat(result.message()).contains("passed");

            BudgetState state = enforcer.getBudgetState(BudgetTier.NODE, "node-001");
            assertThat(state).isNotNull();
            assertThat(state.consumedUsd()).isCloseTo(0.05, within(1e-9));
        }

        @Test
        @DisplayName("连续调用触发节点级熔断（SOFT 或 HARD）")
        void repeatedCalls_triggerCircuitBreaker() {
            configureFourTierBudgets(enforcer, 1000.0);
            List<String> ids = fourTierEntityIds();

            FourLayerBudgetEnforcer.EnforcementResult lastResult = null;
            for (int i = 0; i < 20; i++) {
                lastResult = enforcer.checkAndRecord(ids, 50.0);
            }

            assertThat(lastResult).isNotNull();
            assertThat(lastResult.shouldEnforce()).isTrue();
            assertThat(lastResult.circuitState())
                    .isIn(BudgetState.CircuitState.SOFT_CIRCUIT, BudgetState.CircuitState.HARD_CIRCUIT);
        }

        @Test
        @DisplayName("预算未配置时 → 放行，不破坏未配置工具的调用")
        void noBudgetConfigured_passesThrough() {
            var result = enforcer.checkAndRecord(fourTierEntityIds(), 10.0);

            assertThat(result.shouldEnforce()).isFalse();
            assertThat(result.circuitState()).isEqualTo(BudgetState.CircuitState.NORMAL);
        }

        @Test
        @DisplayName("实体 ID 列表长度不为 4 → 返回 Invalid")
        void invalidEntityIdCount_returnsInvalid() {
            var result = enforcer.checkAndRecord(List.of("only-one-id"), 10.0);

            assertThat(result.shouldEnforce()).isFalse();
            assertThat(result.message()).contains("Invalid entity IDs");
        }

        @Test
        @DisplayName("熔断后再次调用 → shouldEnforce=true，切断后续调用")
        void afterCircuit_subsequentCallsAreBlocked() {
            configureFourTierBudgets(enforcer, 10.0);
            List<String> ids = fourTierEntityIds();

            // 第一次：$9.5 → 触发 SOFT_CIRCUIT
            var r1 = enforcer.checkAndRecord(ids, 9.5);
            assertThat(r1.shouldEnforce()).isTrue();

            // 后续调用：保持熔断
            var r2 = enforcer.checkAndRecord(ids, 1.0);
            var r3 = enforcer.checkAndRecord(ids, 1.0);

            assertThat(r2.shouldEnforce()).isTrue();
            assertThat(r3.shouldEnforce()).isTrue();
            assertThat(r3.circuitState())
                    .isIn(BudgetState.CircuitState.SOFT_CIRCUIT, BudgetState.CircuitState.HARD_CIRCUIT);
        }

        @Test
        @DisplayName("enforce(BudgetTier, String) 方法签名存在且返回 EnforcementResult")
        void enforceMethodSignature_exists() throws NoSuchMethodException {
            Method m = FourLayerBudgetEnforcer.class.getMethod(
                    "enforce", BudgetTier.class, String.class);

            assertThat(m).isNotNull();
            assertThat(m.getReturnType()).isEqualTo(FourLayerBudgetEnforcer.EnforcementResult.class);
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private static void configureFourTierBudgets(FourLayerBudgetEnforcer enforcer, double nodeBudget) {
        BudgetTier[] tiers = {
            BudgetTier.ORGANIZATION, BudgetTier.USER,
            BudgetTier.TASK, BudgetTier.NODE
        };
        double[] budgets = {nodeBudget * 10000, nodeBudget * 100, nodeBudget * 10, nodeBudget};
        String[] ids = {"org-001", "user-001", "task-001", "node-001"};
        BudgetConfig.DegradationStrategy[] strategies = {
            BudgetConfig.DegradationStrategy.REJECT,
            BudgetConfig.DegradationStrategy.MODEL_DOWNGRADE,
            BudgetConfig.DegradationStrategy.QUEUE,
            BudgetConfig.DegradationStrategy.CACHE_FIRST
        };
        Duration[] periods = {
            Duration.ofDays(30), Duration.ofDays(30),
            Duration.ofHours(24), Duration.ofHours(1)
        };
        double[] softRatios = {0.90, 0.85, 0.80, 0.80};
        double[] hardRatios = {0.95, 0.90, 0.85, 1.0};
        double[] recoveryRatios = {0.70, 0.60, 0.50, 0.40};

        for (int i = 0; i < tiers.length; i++) {
            BudgetConfig cfg = new BudgetConfig(
                tiers[i], ids[i],
                budgets[i],
                softRatios[i],
                hardRatios[i],
                strategies[i],
                periods[i],
                true,
                recoveryRatios[i]
            );
            enforcer.configureBudget(tiers[i], ids[i], cfg);
        }
    }

    private static List<String> fourTierEntityIds() {
        return Arrays.asList("org-001", "user-001", "task-001", "node-001");
    }

    /**
     * 示例工具服务——使用 AgentScope {@code @Tool} 注解（非 Spring AI）。
     * 包含 3 个 @Tool 方法 + 1 个 helper 方法（不应被切面拦截）。
     */
    static class SampleToolService {

        @io.agentscope.core.tool.Tool(description = "查询客户订单历史")
        public String getOrderHistory(String customerId, int days) {
            return "最近 " + days + " 天 " + customerId + " 的订单记录";
        }

        @io.agentscope.core.tool.Tool(description = "为客户退款")
        public String refundOrder(String orderId) {
            return "订单 " + orderId + " 退款中";
        }

        @io.agentscope.core.tool.Tool(description = "转人工客服")
        public String escalateToHuman(String customerId, String reason) {
            return "CUST " + customerId + " 转人工，原因：" + reason;
        }

        /** 非 @Tool 方法，用于内部辅助，不应被 Toolkit 扫描或切面拦截 */
        public void logAccess(String userId) {
            // do nothing
        }
    }
}

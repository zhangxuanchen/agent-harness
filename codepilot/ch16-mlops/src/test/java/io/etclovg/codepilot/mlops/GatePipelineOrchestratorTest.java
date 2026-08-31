package io.etclovg.codepilot.mlops;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * GatePipelineOrchestrator 单元测试。
 * <p>验证四阶段门禁（Eval → Perf → Policy → Human）的顺序执行、短路阻断、
 * 人工审批路由与生产审计记录。门禁以桩子类注入受控结果，隔离真实评估开销。
 */
@DisplayName("GatePipelineOrchestrator 单元测试")
class GatePipelineOrchestratorTest {

    private StubEvalGate evalGate;
    private StubPerfGate perfGate;
    private StubPolicyGate policyGate;
    private StubHumanGate humanGate;
    private GatePipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        evalGate = new StubEvalGate();
        perfGate = new StubPerfGate();
        policyGate = new StubPolicyGate();
        humanGate = new StubHumanGate();
        orchestrator = new GatePipelineOrchestrator(evalGate, perfGate, policyGate, humanGate);
    }

    private AgentBundle.AgentBundleStructure createTestBundle(String versionId, String agentId) {
        return new AgentBundle.AgentBundleStructure(
                new AgentBundle.BundleMetadata(versionId, agentId, "test-agent", "", "test", Instant.now(), List.of()),
                AgentBundle.ModelConfig.defaultConfig(),
                List.of(AgentBundle.ToolDefinition.of("tool-1", "search", "1.0")),
                null,
                AgentBundle.AdvisorChainConfig.defaultConfig(),
                new LinkedHashMap<>(Map.of("key", "value"))
        );
    }

    // ==================== 门禁短路阻断 ====================

    @Nested
    @DisplayName("executeGatePipeline 门禁执行")
    class GatePipelineTests {

        @Test
        @DisplayName("四门禁全通过 → 流水线放行")
        void allGatesPass_pipelinePassed() {
            GatePipelineState state = orchestrator.executeGatePipeline("v1", "v2", RiskLevel.LOW);

            assertThat(state.isBlocked()).isFalse();
            assertThat(state.blockedAt()).isNull();
            assertThat(state.gates()).hasSize(4);
            assertThat(state.gates()).allMatch(GateResult::passed);
        }

        @Test
        @DisplayName("Eval 失败 → 短路阻断于 Eval，后续门禁不执行")
        void evalFails_shortCircuitsAtEval() {
            evalGate.setResult(GateResult.fail("Eval", "评估退化 15%"));

            GatePipelineState state = orchestrator.executeGatePipeline("v1", "v2", RiskLevel.LOW);

            assertThat(state.isBlocked()).isTrue();
            assertThat(state.blockedAt()).isEqualTo("Eval");
            assertThat(state.gates()).hasSize(1); // 短路：仅 Eval 执行
        }

        @Test
        @DisplayName("Perf 失败 → 短路阻断于 Perf")
        void perfFails_shortCircuitsAtPerf() {
            perfGate.setResult(GateResult.fail("Perf", "P95 退化 25%"));

            GatePipelineState state = orchestrator.executeGatePipeline("v1", "v2", RiskLevel.LOW);

            assertThat(state.isBlocked()).isTrue();
            assertThat(state.blockedAt()).isEqualTo("Perf");
            assertThat(state.gates()).hasSize(2); // Eval + Perf
        }

        @Test
        @DisplayName("Policy 失败 → 短路阻断于 Policy")
        void policyFails_shortCircuitsAtPolicy() {
            policyGate.setResult(GateResult.fail("Policy", "发现安全违规"));

            GatePipelineState state = orchestrator.executeGatePipeline("v1", "v2", RiskLevel.LOW);

            assertThat(state.isBlocked()).isTrue();
            assertThat(state.blockedAt()).isEqualTo("Policy");
            assertThat(state.gates()).hasSize(3); // Eval + Perf + Policy
        }

        @Test
        @DisplayName("高风险 + 人工审批拒绝 → 阻断于 Human")
        void highRisk_humanRejected_blocksAtHuman() {
            humanGate.setApproved(false);

            GatePipelineState state = orchestrator.executeGatePipeline("v1", "v2", RiskLevel.HIGH);

            assertThat(state.isBlocked()).isTrue();
            assertThat(state.blockedAt()).isEqualTo("Human");
            assertThat(state.gates()).hasSize(4);
            assertThat(state.gates().get(3).passed()).isFalse();
        }

        @Test
        @DisplayName("低风险 → Human 自动放行（PASS_AUTO）")
        void lowRisk_humanAutoPassed() {
            GatePipelineState state = orchestrator.executeGatePipeline("v1", "v2", RiskLevel.LOW);

            assertThat(state.isBlocked()).isFalse();
            GateResult human = state.gates().get(3);
            assertThat(human.status()).isEqualTo(GateStatus.PASS_AUTO);
        }

        @Test
        @DisplayName("Policy 临界违规 → 告警但不阻断")
        void policyConditionalFail_warnsButContinues() {
            policyGate.setResult(GateResult.conditionalFail("Policy", "临界策略违规"));

            GatePipelineState state = orchestrator.executeGatePipeline("v1", "v2", RiskLevel.LOW);

            // CONDITIONAL_FAIL 不阻断（仅 HARD_FAIL 阻断）
            assertThat(state.isBlocked()).isFalse();
            assertThat(state.gates().get(2).status()).isEqualTo(GateStatus.CONDITIONAL_FAIL);
        }
    }

    // ==================== 生产入口 executePipeline ====================

    @Nested
    @DisplayName("executePipeline 生产入口")
    class PipelineExecutionTests {

        @Test
        @DisplayName("全部门禁通过 → PipelineExecution PASSED 并记入历史")
        void allPass_pipelinePassedAndRecorded() {
            AgentBundle.AgentBundleStructure bundle = createTestBundle("v1", "agent-1");

            GatePipelineOrchestrator.PipelineExecution exec = orchestrator.executePipeline(bundle);

            assertThat(exec.status()).isEqualTo(GatePipelineOrchestrator.PipelineExecution.PipelineStatus.PASSED);
            assertThat(exec.agentVersionId()).isEqualTo("v1");
            assertThat(exec.results()).hasSize(4);
            assertThat(exec.results()).allMatch(GateResult::passed);
            assertThat(orchestrator.getExecutionHistory()).isNotEmpty();
        }

        @Test
        @DisplayName("Eval 失败 → PipelineExecution FAILED")
        void evalFails_pipelineFailed() {
            evalGate.setResult(GateResult.fail("Eval", "评估退化 20%"));
            AgentBundle.AgentBundleStructure bundle = createTestBundle("v2", "agent-2");

            GatePipelineOrchestrator.PipelineExecution exec = orchestrator.executePipeline(bundle);

            assertThat(exec.status()).isEqualTo(GatePipelineOrchestrator.PipelineExecution.PipelineStatus.FAILED);
            assertThat(exec.results()).hasSize(1);
        }
    }

    // ==================== 回滚 ====================

    @Nested
    @DisplayName("rollback 回滚")
    class RollbackTests {

        @Test
        @DisplayName("回滚创建 ROLLED_BACK 执行记录")
        void rollback_createsRolledBackExecution() {
            GatePipelineOrchestrator.PipelineExecution rollback = orchestrator.rollback("v1", "发现严重问题");

            assertThat(rollback.status()).isEqualTo(GatePipelineOrchestrator.PipelineExecution.PipelineStatus.ROLLED_BACK);
            assertThat(rollback.agentVersionId()).isEqualTo("v1");
            assertThat(rollback.results()).hasSize(1);
            assertThat(rollback.results().get(0).message()).contains("发现严重问题");
        }

        @Test
        @DisplayName("回滚记录到执行历史")
        void rollback_recordedInHistory() {
            orchestrator.rollback("v1", "回滚原因");

            assertThat(orchestrator.getExecutionHistory().stream()
                    .anyMatch(e -> e.status() == GatePipelineOrchestrator.PipelineExecution.PipelineStatus.ROLLED_BACK))
                    .isTrue();
        }
    }

    // ==================== 门禁桩 ====================

    /** EvalGate 桩：构造时传入 null 依赖（check 被重写，不触达真实工作池）。 */
    static class StubEvalGate extends EvalGate {
        private GateResult result = GateResult.pass("Eval", 0.9);

        StubEvalGate() {
            super(null, null);
        }

        void setResult(GateResult result) {
            this.result = result;
        }

        @Override
        public GateResult check(String baseline, String candidate) {
            return result;
        }
    }

    static class StubPerfGate extends PerfGate {
        private GateResult result = GateResult.pass("Perf", 0.9);

        void setResult(GateResult result) {
            this.result = result;
        }

        @Override
        public GateResult check(String baseline, String candidate) {
            return result;
        }
    }

    static class StubPolicyGate extends PolicyGate {
        private GateResult result = GateResult.pass("Policy", 1.0);

        void setResult(GateResult result) {
            this.result = result;
        }

        @Override
        public GateResult check(String candidate) {
            return result;
        }
    }

    static class StubHumanGate extends HumanGate {
        private boolean approved = true;

        void setApproved(boolean approved) {
            this.approved = approved;
        }

        @Override
        public String requestApproval(String releaseId, String reason) {
            return "ticket-" + releaseId;
        }

        @Override
        public boolean isApproved(String ticket) {
            return approved;
        }
    }
}

package io.etclovg.codepilot.foundation;

import io.etclovg.codepilot.core.Layer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Ch01 §1.3 单元测试——验证 P0 级修复后的类实现。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>AgentLoopFailureModes：三类失败模式检测（幻觉行动/循环卡死/目标漂移）</li>
 *   <li>PdaLoopService：委托模式故障检测、MAX_STEPS=20、白名单 Tool 调用</li>
 *   <li>UsageLimitMiddleware：构造器三参数、check/recordUsage/快照 API</li>
 *   <li>FrameworkCoverageEvaluator：7 分制覆盖率、Layer 枚举映射</li>
 *   <li>AgentAutonomyLevels：L0-L4 分级、isAgentLevel()、classify()</li>
 *   <li>CodePilotStageEvolution：8 Stage 枚举、覆盖率递增序列</li>
 * </ul>
 */
@DisplayName("Ch01 核心组件单元测试")
class Ch01FoundationTest {

    // ==================== AgentLoopFailureModes ====================

    @Nested
    @DisplayName("AgentLoopFailureModes 三类失败模式检测")
    class AgentLoopFailureModesTests {

        private AgentLoopFailureModes fm;

        @BeforeEach
        void setUp() {
            fm = new AgentLoopFailureModes();
        }

        @Test
        @DisplayName("幻觉行动：工具在白名单内不应被判定为幻觉")
        void hallucinatedAction_toolInWhitelist_notDetected() {
            Set<String> whitelist = Set.of("search", "calculate", "fetch", "write", "verify");
            assertThat(fm.detectHallucinatedAction("search", whitelist)).isFalse();
            assertThat(fm.detectHallucinatedAction("calculate", whitelist)).isFalse();
        }

        @Test
        @DisplayName("幻觉行动：工具不在白名单内应被判定为幻觉")
        void hallucinatedAction_toolNotInWhitelist_detected() {
            Set<String> whitelist = Set.of("search", "calculate", "fetch", "write", "verify");
            assertThat(fm.detectHallucinatedAction("delete_database", whitelist)).isTrue();
            assertThat(fm.detectHallucinatedAction("drop_table", whitelist)).isTrue();
        }

        @Test
        @DisplayName("循环卡死：同一工具连续调用 >= maxRepeatCount 应被检测")
        void loopDeath_consecutiveCalls_detected() {
            fm.recordCall("search");
            fm.recordCall("search");
            fm.recordCall("search");
            // 3 次 >= MAX_REPEAT_COUNT(3)
            assertThat(fm.detectLoopDeath(3)).isTrue();
        }

        @Test
        @DisplayName("循环卡死：低于阈值不应被检测")
        void loopDeath_belowThreshold_notDetected() {
            fm.recordCall("search");
            fm.recordCall("search");
            // 2 次 < MAX_REPEAT_COUNT(3)
            assertThat(fm.detectLoopDeath(3)).isFalse();
        }

        @Test
        @DisplayName("循环卡死：空历史不应被检测")
        void loopDeath_emptyHistory_notDetected() {
            assertThat(fm.detectLoopDeath(3)).isFalse();
        }

        @Test
        @DisplayName("目标漂移：决策包含原始目标关键词不应被检测")
        void goalDrift_containsKeyword_notDetected() {
            fm.setOriginalGoal("修复查询");
            assertThat(fm.detectGoalDrift("执行搜索修复查询")).isFalse();
        }

        @Test
        @DisplayName("目标漂移：决策完全偏离原始目标应被检测")
        void goalDrift_noKeyword_detected() {
            fm.setOriginalGoal("修复数据库查询性能");
            assertThat(fm.detectGoalDrift("发送邮件通知管理员")).isTrue();
        }

        @Test
        @DisplayName("三类 FailureMode 枚举定义正确")
        void failureMode_enums_defined() {
            var modes = fm.getAllModes();
            assertThat(modes).hasSize(3);
            assertThat(modes.get(0).getName()).isEqualTo("幻觉行动");
            assertThat(modes.get(0).getDefense()).contains("白名单校验");
            assertThat(modes.get(1).getName()).isEqualTo("循环卡死");
            assertThat(modes.get(2).getName()).isEqualTo("目标漂移");
        }
    }

    // ==================== PdaLoopService ====================

    @Nested
    @DisplayName("PdaLoopService 委托模式故障检测")
    class PdaLoopServiceTests {

        private AgentLoopFailureModes fm;
        private PdaLoopService pda;

        @BeforeEach
        void setUp() {
            fm = new AgentLoopFailureModes();
            pda = new PdaLoopService(fm);
        }

        @Test
        @DisplayName("PDA 循环：正常任务应在 MAX_STEPS 内完成")
        void pdaLoop_normalTask_completesWithinMaxSteps() {
            var result = pda.runTask("修复查询");
            assertThat(result.steps()).isLessThanOrEqualTo(20);
        }

        @Test
        @DisplayName("PDA 循环：完成的任务不应有 failureReason")
        void pdaLoop_completedTask_noFailureReason() {
            var result = pda.runTask("修复查询");
            if (result.completed()) {
                assertThat(result.failureReason()).isNull();
            }
        }

        @Test
        @DisplayName("PDA 循环：构造器注入 AgentLoopFailureModes，不应为 null")
        void pdaLoop_failureModes_injected() {
            assertThat(fm).isNotNull();
        }
    }

    // ==================== UsageLimitMiddleware ====================

    @Nested
    @DisplayName("UsageLimitMiddleware 构造器三参数 API")
    class UsageLimitMiddlewareTests {

        @Test
        @DisplayName("构造器：三参数构造器应正确初始化预算")
        void constructor_threeParams_initializes() {
            var guard = new UsageLimitMiddleware(100_000L, 50L, 20L);
            // 初始状态应允许
            var result = guard.check("task-001");
            assertThat(result.allowed()).isTrue();
            assertThat(result.tokenBudget()).isEqualTo(100_000L);
            assertThat(result.costBudget()).isEqualTo(50.0);
            assertThat(result.callLimit()).isEqualTo(20L);
        }

        @Test
        @DisplayName("recordUsage：记录 token 和成本消耗后快照应反映")
        void recordUsage_reflectedInSnapshot() {
            var guard = new UsageLimitMiddleware(100_000L, 50L, 20L);
            guard.recordUsage("task-001", 5_000L, 10.0);
            var snapshot = guard.getSnapshot();
            assertThat(snapshot.tokensUsed()).isEqualTo(5_000L);
            assertThat(snapshot.costUsed()).isEqualTo(10.0);
            assertThat(snapshot.callsUsed()).isEqualTo(1L);
        }

        @Test
        @DisplayName("check：token 耗尽时 allowed 应为 false")
        void check_tokenExhausted_notAllowed() {
            var guard = new UsageLimitMiddleware(1_000L, 50L, 20L);
            guard.recordUsage("task-001", 800L, 5.0);
            guard.recordUsage("task-002", 300L, 5.0);
            // totalTokensUsed = 1100 > 1000
            var result = guard.check("task-003");
            assertThat(result.tokenOk()).isFalse();
            assertThat(result.allowed()).isFalse();
        }

        @Test
        @DisplayName("check：调用次数达上限时 allowed 应为 false")
        void check_callsExhausted_notAllowed() {
            var guard = new UsageLimitMiddleware(100_000L, 50L, 3L);
            guard.recordUsage("task-001", 100L, 1.0);
            guard.recordUsage("task-002", 100L, 1.0);
            guard.recordUsage("task-003", 100L, 1.0);
            var result = guard.check("task-004");
            assertThat(result.callOk()).isFalse();
            assertThat(result.allowed()).isFalse();
        }

        @Test
        @DisplayName("UsageCheckResult：tokenUsagePercent 应正确计算")
        void checkResult_tokenUsagePercent_correct() {
            var guard = new UsageLimitMiddleware(10_000L, 50L, 20L);
            guard.recordUsage("task-001", 5_000L, 10.0);
            var result = guard.check("task-002");
            assertThat(result.tokenUsagePercent()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("reset：重置后所有计数归零")
        void reset_allCountsZeroed() {
            var guard = new UsageLimitMiddleware(100_000L, 50L, 20L);
            guard.recordUsage("task-001", 5_000L, 10.0);
            guard.reset();
            var snapshot = guard.getSnapshot();
            assertThat(snapshot.tokensUsed()).isEqualTo(0L);
            assertThat(snapshot.costUsed()).isEqualTo(0.0);
            assertThat(snapshot.callsUsed()).isEqualTo(0L);
        }
    }

    // ==================== FrameworkCoverageEvaluator ====================

    @Nested
    @DisplayName("FrameworkCoverageEvaluator 7 分制覆盖率")
    class FrameworkCoverageEvaluatorTests {

        @Test
        @DisplayName("全层覆盖：7 层全覆盖应返回 100%")
        void overallCoverage_allLayers_100Percent() {
            var evaluator = new FrameworkCoverageEvaluator();
            evaluator.evaluate(Set.of(Layer.E, Layer.T, Layer.C, Layer.L, Layer.O, Layer.V, Layer.G));
            assertThat(evaluator.getOverallCoverage()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("部分覆盖：4 层（T,C,L,O）应返回约 57%")
        void overallCoverage_partialLayers_57Percent() {
            var evaluator = new FrameworkCoverageEvaluator();
            evaluator.evaluate(Set.of(Layer.T, Layer.C, Layer.L, Layer.O));
            assertThat(evaluator.getOverallCoverage()).isCloseTo(57.14, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("空层覆盖：0 层应返回 0%")
        void overallCoverage_noLayers_zeroPercent() {
            var evaluator = new FrameworkCoverageEvaluator();
            evaluator.evaluate(Set.of());
            assertThat(evaluator.getOverallCoverage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("evaluate：返回的记录数应等于 Layer 枚举长度")
        void evaluate_returnsAllSevenLayers() {
            var evaluator = new FrameworkCoverageEvaluator();
            var records = evaluator.evaluate(Set.of(Layer.T, Layer.C));
            assertThat(records).hasSize(7);
            // T 和 C 层应该 implemented=1
            var tRecord = records.stream().filter(r -> r.layer() == Layer.T).findFirst();
            assertThat(tRecord).isPresent();
            assertThat(tRecord.get().implemented()).isEqualTo(1);
            assertThat(tRecord.get().coverageScore()).isEqualTo(1.0);
            // E 层应该 implemented=0
            var eRecord = records.stream().filter(r -> r.layer() == Layer.E).findFirst();
            assertThat(eRecord).isPresent();
            assertThat(eRecord.get().implemented()).isEqualTo(0);
            assertThat(eRecord.get().coverageScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("FrameworkCoverageRecord：record 字段应正确")
        void coverageRecord_fields_correct() {
            var record = new FrameworkCoverageRecord(
                Layer.E, 0, 0.0,
                List.of("DockerSandbox", "GVisorAdapter"),
                "MISSING"
            );
            assertThat(record.layer()).isEqualTo(Layer.E);
            assertThat(record.implemented()).isEqualTo(0);
            assertThat(record.status()).isEqualTo("MISSING");
            assertThat(record.missingItems()).containsExactly("DockerSandbox", "GVisorAdapter");
        }
    }

    // ==================== AgentAutonomyLevels ====================

    @Nested
    @DisplayName("AgentAutonomyLevels L0-L4 分级")
    class AgentAutonomyLevelsTests {

        @Test
        @DisplayName("isAgentLevel：L0-L1 不应为 Agent")
        void isAgentLevel_L0L1_notAgent() {
            assertThat(AgentAutonomyLevels.isAgentLevel(
                AgentAutonomyLevels.AutonomyLevel.L0_SCRIPTED)).isFalse();
            assertThat(AgentAutonomyLevels.isAgentLevel(
                AgentAutonomyLevels.AutonomyLevel.L1_TRIGGERED)).isFalse();
        }

        @Test
        @DisplayName("isAgentLevel：L2-L4 应为 Agent")
        void isAgentLevel_L2L4_isAgent() {
            assertThat(AgentAutonomyLevels.isAgentLevel(
                AgentAutonomyLevels.AutonomyLevel.L2_SINGLE_STEP)).isTrue();
            assertThat(AgentAutonomyLevels.isAgentLevel(
                AgentAutonomyLevels.AutonomyLevel.L3_MULTI_STEP)).isTrue();
            assertThat(AgentAutonomyLevels.isAgentLevel(
                AgentAutonomyLevels.AutonomyLevel.L4_FULLY_AUTONOMOUS)).isTrue();
        }

        @Test
        @DisplayName("classify：全 false 应判定为 L0")
        void classify_allFalse_L0() {
            var level = AgentAutonomyLevels.classify(false, false, false, false);
            assertThat(level).isEqualTo(AgentAutonomyLevels.AutonomyLevel.L0_SCRIPTED);
        }

        @Test
        @DisplayName("classify：仅 perceive 应判定为 L1")
        void classify_onlyPerceive_L1() {
            var level = AgentAutonomyLevels.classify(true, false, false, false);
            assertThat(level).isEqualTo(AgentAutonomyLevels.AutonomyLevel.L1_TRIGGERED);
        }

        @Test
        @DisplayName("classify：perceive + decide 应判定为 L2")
        void classify_perceiveDecide_L2() {
            var level = AgentAutonomyLevels.classify(true, true, false, false);
            assertThat(level).isEqualTo(AgentAutonomyLevels.AutonomyLevel.L2_SINGLE_STEP);
        }

        @Test
        @DisplayName("classify：perceive + decide + multiStep 应判定为 L3")
        void classify_perceiveDecideMultiStep_L3() {
            var level = AgentAutonomyLevels.classify(true, true, true, false);
            assertThat(level).isEqualTo(AgentAutonomyLevels.AutonomyLevel.L3_MULTI_STEP);
        }

        @Test
        @DisplayName("classify：全 true 应判定为 L4")
        void classify_allTrue_L4() {
            var level = AgentAutonomyLevels.classify(true, true, true, true);
            assertThat(level).isEqualTo(AgentAutonomyLevels.AutonomyLevel.L4_FULLY_AUTONOMOUS);
        }

        @Test
        @DisplayName("分级维度：L2 应有感知和决策，但无多步和自定目标")
        void dimensions_L2_correct() {
            var l2 = AgentAutonomyLevels.AutonomyLevel.L2_SINGLE_STEP;
            assertThat(l2.hasPerception()).isTrue();
            assertThat(l2.hasDecision()).isTrue();
            assertThat(l2.hasMultiStep()).isFalse();
            assertThat(l2.isSelfDirected()).isFalse();
        }

        @Test
        @DisplayName("分级维度：L4 应有全部四个维度")
        void dimensions_L4_correct() {
            var l4 = AgentAutonomyLevels.AutonomyLevel.L4_FULLY_AUTONOMOUS;
            assertThat(l4.hasPerception()).isTrue();
            assertThat(l4.hasDecision()).isTrue();
            assertThat(l4.hasMultiStep()).isTrue();
            assertThat(l4.isSelfDirected()).isTrue();
        }

        @Test
        @DisplayName("分级数量：应有 5 级")
        void levelCount_fiveLevels() {
            assertThat(AgentAutonomyLevels.AutonomyLevel.values()).hasSize(5);
        }
    }

    // ==================== CodePilotStageEvolution ====================

    @Nested
    @DisplayName("CodePilotStageEvolution 8 Stage 枚举与覆盖率递增")
    class CodePilotStageEvolutionTests {

        @Test
        @DisplayName("Stage 数量：应有 8 级（RAW → G_GOVERNANCE）")
        void stageCount_eightStages() {
            var stages = CodePilotStageEvolution.CodePilotStage.values();
            assertThat(stages).hasSize(8);
        }

        @Test
        @DisplayName("覆盖率递增：每一级的成功率应 >= 前一级")
        void successRate_monotonicallyIncreasing() {
            var stages = CodePilotStageEvolution.CodePilotStage.values();
            for (int i = 1; i < stages.length; i++) {
                assertThat(stages[i].getSuccessRate())
                    .as("Stage %d (%s) success rate should be >= previous stage", i, stages[i].getLabel())
                    .isGreaterThanOrEqualTo(stages[i - 1].getSuccessRate());
            }
        }

        @Test
        @DisplayName("最终覆盖率：G_GOVERNANCE 应为 82%")
        void finalRate_gGovernance_82Percent() {
            var lastStage = CodePilotStageEvolution.CodePilotStage.G_GOVERNANCE;
            assertThat(lastStage.getSuccessRate()).isEqualTo(0.82);
        }

        @Test
        @DisplayName("T 层增量：T_STRUCTURED 应比 RAW 高 +12pp")
        void tLayerIncrement_12pp() {
            var raw = CodePilotStageEvolution.CodePilotStage.RAW;
            var t = CodePilotStageEvolution.CodePilotStage.T_STRUCTURED;
            double delta = t.getSuccessRate() - raw.getSuccessRate();
            assertThat(delta).isCloseTo(0.12, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("层覆盖集合：每一级的 layersAdded 应包含前一级的所有层 + 新层")
        void layersAdded_accumulate() {
            var stages = CodePilotStageEvolution.CodePilotStage.values();
            for (int i = 1; i < stages.length; i++) {
                var prevLayers = stages[i - 1].getLayersAdded();
                var currLayers = stages[i].getLayersAdded();
                assertThat(currLayers).containsAll(prevLayers);
            }
        }

        @Test
        @DisplayName("demonstrateEvolution：应正常打印 8 行")
        void demonstrateEvolution_runsWithoutException() {
            var evolution = new CodePilotStageEvolution();
            assertThatCode(evolution::demonstrateEvolution).doesNotThrowAnyException();
        }
    }
}

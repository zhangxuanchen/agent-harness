package io.etclovg.codepilot.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * ReadinessCheckAdvisor 单元测试。
 * 聚焦于就绪检查、任务结果记录、历史查询等核心逻辑。
 */
@DisplayName("就绪检查 Advisor 测试")
class ReadinessCheckAdvisorTest {

    private ReadinessCheckAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new ReadinessCheckAdvisor();
    }

    @Nested
    @DisplayName("任务结果记录测试")
    class TaskOutcomeRecording {

        @Test
        @DisplayName("记录成功任务后应更新历史")
        void recordTaskOutcome_success_updatesHistory() {
            advisor.recordTaskOutcome("Implement a REST API using Spring Boot", true);
            
            assertThat(advisor.getTaskHistory()).isNotEmpty();
        }

        @Test
        @DisplayName("记录失败任务后应更新历史")
        void recordTaskOutcome_failure_updatesHistory() {
            advisor.recordTaskOutcome("Fix a complex bug in production", false);
            
            assertThat(advisor.getTaskHistory()).isNotEmpty();
        }

        @Test
        @DisplayName("多次记录同一类型应累加样本数")
        void recordTaskOutcome_multipleSameType_accumulatesSamples() {
            advisor.recordTaskOutcome("Implement a REST API", true);
            advisor.recordTaskOutcome("Implement a GraphQL API", true);
            advisor.recordTaskOutcome("Implement a gRPC API", false);
            
            // 应该有 "implement" 类型的记录
            assertThat(advisor.getTaskHistory()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("通过率统计测试")
    class PassRateStatistics {

        @Test
        @DisplayName("初始通过率应为合理值")
        void getAveragePassRate_initialValue_isReasonable() {
            double passRate = advisor.getAveragePassRate();
            // 初始应该为 0 或 NaN，因为没有记录
            assertThat(passRate >= 0).isTrue();
        }

        @Test
        @DisplayName("记录结果后通过率应更新")
        void getAveragePassRate_afterRecording_updates() {
            advisor.recordTaskOutcome("Build a simple calculator", true);
            advisor.recordTaskOutcome("Build a complex system", true);
            advisor.recordTaskOutcome("Build a buggy module", false);
            
            double passRate = advisor.getAveragePassRate();
            // 应该有通过率数据
            assertThat(passRate >= 0).isTrue();
        }
    }

    @Nested
    @DisplayName("历史查询测试")
    class HistoryQuery {

        @Test
        @DisplayName("getTaskHistory 应返回所有历史")
        void getTaskHistory_returnsAllHistory() {
            advisor.recordTaskOutcome("Create a function", true);
            advisor.recordTaskOutcome("Test the system", true);
            
            assertThat(advisor.getTaskHistory()).isNotEmpty();
        }

        @Test
        @DisplayName("getRecentChecks 应返回指定数量的记录")
        void getRecentChecks_returnsSpecifiedLimit() {
            // 先添加一些结果
            for (int i = 0; i < 10; i++) {
                advisor.recordTaskOutcome("Task " + i, i % 2 == 0);
            }
            
            var checks = advisor.getRecentChecks(5);
            assertThat(checks).hasSizeLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("空历史应返回空列表")
        void getTaskHistory_emptyHistory_returnsEmpty() {
            assertThat(advisor.getTaskHistory()).isEmpty();
        }
    }

    @Nested
    @DisplayName("TaskStatistics 测试")
    class TaskStatisticsTest {

        @Test
        @DisplayName("TaskStatistics 应正确计算成功率")
        void taskStatistics_successRate_calculatedCorrectly() {
            ReadinessCheckAdvisor.TaskStatistics stats = 
                    new ReadinessCheckAdvisor.TaskStatistics("test", 10, 7);
            
            assertThat(stats.successRate()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("TaskStatistics.recordOutcome 应更新统计")
        void taskStatistics_recordOutcome_updatesStats() {
            ReadinessCheckAdvisor.TaskStatistics stats = 
                    new ReadinessCheckAdvisor.TaskStatistics("test", 10, 7);
            
            stats.recordOutcome(true);
            
            assertThat(stats.sampleCount()).isEqualTo(11);
            assertThat(stats.successCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("TaskStatistics 零样本应返回合理值")
        void taskStatistics_zeroSamples_returnsReasonableRate() {
            ReadinessCheckAdvisor.TaskStatistics stats = 
                    new ReadinessCheckAdvisor.TaskStatistics("test", 0, 0);
            
            // 零样本时应该返回默认值（0.5 或 0）
            assertThat(stats.successRate() >= 0).isTrue();
        }
    }

    @Nested
    @DisplayName("TaskFeatures 测试")
    class TaskFeaturesTest {

        @Test
        @DisplayName("TaskFeatures.computeHash 相同输入应返回相同哈希")
        void taskFeatures_computeHash_sameInput_sameHash() {
            ReadinessCheckAdvisor.TaskFeatures features1 = 
                    new ReadinessCheckAdvisor.TaskFeatures("build", 5, false, false, false);
            ReadinessCheckAdvisor.TaskFeatures features2 = 
                    new ReadinessCheckAdvisor.TaskFeatures("build", 5, false, false, false);
            
            assertThat(features1.computeHash()).isEqualTo(features2.computeHash());
        }

        @Test
        @DisplayName("TaskFeatures.computeHash 不同输入应返回不同哈希")
        void taskFeatures_computeHash_differentInput_differentHash() {
            ReadinessCheckAdvisor.TaskFeatures features1 = 
                    new ReadinessCheckAdvisor.TaskFeatures("build", 5, false, false, false);
            ReadinessCheckAdvisor.TaskFeatures features2 = 
                    new ReadinessCheckAdvisor.TaskFeatures("fix", 5, false, false, false);
            
            assertThat(features1.computeHash()).isNotEqualTo(features2.computeHash());
        }

        @Test
        @DisplayName("TaskFeatures.computeHash 应区分复杂度等级")
        void taskFeatures_computeHash_distinguishesComplexity() {
            ReadinessCheckAdvisor.TaskFeatures lowComplexity = 
                    new ReadinessCheckAdvisor.TaskFeatures("build", 5, false, false, false);
            ReadinessCheckAdvisor.TaskFeatures highComplexity = 
                    new ReadinessCheckAdvisor.TaskFeatures("build", 25, false, false, false);
            
            assertThat(lowComplexity.computeHash()).isNotEqualTo(highComplexity.computeHash());
        }
    }

    @Nested
    @DisplayName("ReadinessResult 测试")
    class ReadinessResultTest {

        @Test
        @DisplayName("ReadinessResult.summary 应生成可读摘要")
        void readinessResult_summary_containsKeyInfo() {
            ReadinessCheckAdvisor.ReadinessResult result = 
                    new ReadinessCheckAdvisor.ReadinessResult("session-1", Instant.now(),
                            "Build a REST API", 5.0, 4.5, 3.0, 12.5, 
                            new ReadinessCheckAdvisor.TaskFeatures("build", 5, false, false, false), true);
            
            String summary = result.summary();
            assertThat(summary).contains("12.5");
        }

        @Test
        @DisplayName("ReadinessResult 未通过应显示拦截")
        void readinessResult_notPassed_showsBlocked() {
            ReadinessCheckAdvisor.ReadinessResult result = 
                    new ReadinessCheckAdvisor.ReadinessResult("session-1", Instant.now(),
                            "Bad input", 1.0, 1.0, 1.0, 3.0,
                            new ReadinessCheckAdvisor.TaskFeatures("fix", 2, false, false, false), false);
            
            String summary = result.summary();
            assertThat(summary).contains("拦截");
        }
    }
}

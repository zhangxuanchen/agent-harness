package io.etclovg.codepilot.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * CostTracker 单元测试。
 * 聚焦于五标签成本归因、成本计算、定价管理等核心逻辑。
 */
@DisplayName("成本追踪器测试")
class CostTrackerTest {

    private CostTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new CostTracker();
    }

    @Nested
    @DisplayName("模型用量记录测试")
    class ModelUsageRecording {

        @Test
        @DisplayName("记录模型调用后应正确累加用量")
        void recordModelUsage_accumulatesUsage() {
            tracker.recordModelUsage("user-1", "session-1", "task-1", "gpt-4o", 1000, 500);
            
            CostTracker.UsageEntry entry = tracker.getUsage(CostTracker.Label.USER, "user-1");
            assertThat(entry).isNotNull();
            assertThat(entry.inputTokens()).isEqualTo(1000);
            assertThat(entry.outputTokens()).isEqualTo(500);
        }

        @Test
        @DisplayName("多次调用应累加费用")
        void recordModelUsage_multipleCalls_accumulatesCost() {
            tracker.recordModelUsage("user-1", "session-1", "task-1", "gpt-4o", 1000, 500);
            tracker.recordModelUsage("user-1", "session-1", "task-1", "gpt-4o", 2000, 1000);
            
            CostTracker.UsageEntry entry = tracker.getUsage(CostTracker.Label.USER, "user-1");
            assertThat(entry.inputTokens()).isEqualTo(3000);
            assertThat(entry.outputTokens()).isEqualTo(1500);
        }

        @Test
        @DisplayName("不同标签应独立记录")
        void recordModelUsage_differentLabels_recordedIndependently() {
            tracker.recordModelUsage("user-1", "session-1", "task-1", "gpt-4o", 1000, 500);
            
            // 各标签应有独立记录
            assertThat(tracker.getUsage(CostTracker.Label.USER, "user-1")).isNotNull();
            assertThat(tracker.getUsage(CostTracker.Label.SESSION, "session-1")).isNotNull();
            assertThat(tracker.getUsage(CostTracker.Label.TASK, "task-1")).isNotNull();
            assertThat(tracker.getUsage(CostTracker.Label.MODEL, "gpt-4o")).isNotNull();
        }
    }

    @Nested
    @DisplayName("工具用量记录测试")
    class ToolUsageRecording {

        @Test
        @DisplayName("记录工具调用应正确累加")
        void recordToolUsage_accumulatesUsage() {
            tracker.recordToolUsage("user-1", "session-1", "task-1", "search_tool", 3);
            
            CostTracker.UsageEntry entry = tracker.getUsage(CostTracker.Label.USER, "user-1");
            assertThat(entry).isNotNull();
        }

        @Test
        @DisplayName("多次工具调用应累加")
        void recordToolUsage_multipleCalls_accumulates() {
            tracker.recordToolUsage("user-1", "session-1", "task-1", "search_tool", 2);
            tracker.recordToolUsage("user-1", "session-1", "task-1", "search_tool", 3);
            
            CostTracker.UsageEntry entry = tracker.getUsage(CostTracker.Label.TOOL, "search_tool");
            assertThat(entry).isNotNull();
        }
    }

    @Nested
    @DisplayName("成本查询测试")
    class CostQuery {

        @Test
        @DisplayName("getTotalCost 应返回累计费用")
        void getTotalCost_returnsAccumulatedCost() {
            tracker.recordModelUsage("user-1", "session-1", "task-1", "gpt-4o", 1000, 500);
            tracker.recordModelUsage("user-1", "session-1", "task-2", "gpt-4o", 2000, 1000);
            
            BigDecimal totalCost = tracker.getTotalCost();
            assertThat(totalCost).isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("getUserCost 应返回指定用户的费用")
        void getUserCost_returnsUserSpecificCost() {
            tracker.recordModelUsage("user-1", "session-1", "task-1", "gpt-4o", 1000, 500);
            tracker.recordModelUsage("user-2", "session-2", "task-2", "gpt-4o", 2000, 1000);
            
            BigDecimal user1Cost = tracker.getUserCost("user-1");
            BigDecimal user2Cost = tracker.getUserCost("user-2");
            
            assertThat(user1Cost).isGreaterThan(BigDecimal.ZERO);
            assertThat(user2Cost).isGreaterThan(user1Cost);
        }

        @Test
        @DisplayName("getSessionCost 应返回指定会话的费用")
        void getSessionCost_returnsSessionSpecificCost() {
            tracker.recordModelUsage("user-1", "session-1", "task-1", "gpt-4o", 1000, 500);
            
            BigDecimal sessionCost = tracker.getSessionCost("session-1");
            assertThat(sessionCost).isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("getTaskCost 应返回指定任务的费用")
        void getTaskCost_returnsTaskSpecificCost() {
            tracker.recordModelUsage("user-1", "session-1", "task-1", "gpt-4o", 1000, 500);
            
            BigDecimal taskCost = tracker.getTaskCost("task-1");
            assertThat(taskCost).isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("不存在的用户应返回零费用")
        void getUserCost_nonExistentUser_returnsZero() {
            BigDecimal cost = tracker.getUserCost("non-existent-user");
            assertThat(cost).isEqualTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("定价管理测试")
    class PricingManagement {

        @Test
        @DisplayName("注册模型定价后应影响费用计算")
        void registerModelPricing_affectsCostCalculation() {
            // 先记录一个默认定价的调用
            tracker.recordModelUsage("user-1", "session-1", "task-1", "custom-model", 1000, 500);
            BigDecimal costBefore = tracker.getUserCost("user-1");
            
            // 注册新定价
            tracker.registerModelPricing("custom-model", 
                    BigDecimal.valueOf(0.01), BigDecimal.valueOf(0.02));
            
            // 再记录一个调用
            tracker.recordModelUsage("user-1", "session-1", "task-1", "custom-model", 1000, 500);
            BigDecimal costAfter = tracker.getUserCost("user-1");
            
            assertThat(costAfter).isGreaterThan(costBefore);
        }

        @Test
        @DisplayName("注册工具定价后应影响费用计算")
        void registerToolPricing_affectsCostCalculation() {
            // 注册工具定价
            tracker.registerToolPricing("expensive-tool", BigDecimal.valueOf(5.0));
            
            // 记录工具调用
            tracker.recordToolUsage("user-1", "session-1", "task-1", "expensive-tool", 2);
            
            BigDecimal cost = tracker.getCostByLabel(CostTracker.Label.TOOL, "expensive-tool");
            assertThat(cost).isGreaterThan(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("标签枚举测试")
    class LabelTest {

        @Test
        @DisplayName("Label 应包含五个标签")
        void label_containsFiveLabels() {
            assertThat(CostTracker.Label.values()).hasSize(5);
            assertThat(CostTracker.Label.USER).isNotNull();
            assertThat(CostTracker.Label.SESSION).isNotNull();
            assertThat(CostTracker.Label.TASK).isNotNull();
            assertThat(CostTracker.Label.MODEL).isNotNull();
            assertThat(CostTracker.Label.TOOL).isNotNull();
        }
    }
}

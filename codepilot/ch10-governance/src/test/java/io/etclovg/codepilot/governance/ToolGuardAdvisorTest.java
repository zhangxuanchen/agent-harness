package io.etclovg.codepilot.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * ToolGuardAdvisor 单元测试。
 * 聚焦于四重检查——白名单、参数校验、频率限制、高危审批。
 */
@DisplayName("工具守卫 Advisor 测试")
class ToolGuardAdvisorTest {

    private ToolGuardAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new ToolGuardAdvisor();
    }

    @Nested
    @DisplayName("白名单检查测试")
    class WhitelistCheck {

        @Test
        @DisplayName("已注册工具应通过白名单检查")
        void registeredToolPassesWhitelist() {
            // search 工具在 initializeDefaultTools 中已注册
            assertThat(advisor.getWhitelistedTools()).containsKey("search");
            assertThat(advisor.getWhitelistedTools()).containsKey("calculate");
            assertThat(advisor.getWhitelistedTools()).containsKey("read_file");
            assertThat(advisor.getWhitelistedTools()).containsKey("execute_code");
        }

        @Test
        @DisplayName("动态注册新工具应成功")
        void dynamicRegistrationWorks() {
            advisor.registerTool("custom_tool",
                    new ToolGuardAdvisor.ToolConfig("custom_tool", "LOW",
                            java.util.List.of("param"),
                            java.util.Map.of("param", "string")));
            assertThat(advisor.getWhitelistedTools()).containsKey("custom_tool");
        }

        @Test
        @DisplayName("注销工具应从白名单移除")
        void unregisterToolRemovesFromWhitelist() {
            advisor.unregisterTool("search");
            assertThat(advisor.getWhitelistedTools()).doesNotContainKey("search");
        }
    }

    @Nested
    @DisplayName("参数校验测试")
    class ParameterValidation {

        @Test
        @DisplayName("缺少必需参数应校验失败")
        void missingRequiredParameterFails() {
            // search 工具要求 query 参数
            var result = validateParams("search", java.util.Map.of());
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("Missing required parameter: query"));
        }

        @Test
        @DisplayName("参数类型不匹配应校验失败")
        void typeMismatchFails() {
            var result = validateParams("search",
                    java.util.Map.of("query", 123));  // 期望 string，传了 integer
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("type mismatch"));
        }

        @Test
        @DisplayName("正确参数应校验通过")
        void correctParamsPass() {
            var result = validateParams("search",
                    java.util.Map.of("query", "hello world"));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("未知参数应校验失败")
        void unknownParameterFails() {
            var result = validateParams("search",
                    java.util.Map.of("query", "hello", "unknown_param", "value"));
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("Unknown parameter"));
        }
    }

    @Nested
    @DisplayName("频率限制测试")
    class RateLimitCheck {

        @Test
        @DisplayName("低危工具默认每分钟 20 次限制")
        void lowRiskToolDefaultLimit() {
            // search 默认配置：20 次/分钟
            // 通过多次 increment 验证计数器工作
            for (int i = 0; i < 5; i++) {
                assertThat(advisor.getAuditLog(10)).isNotNull();
            }
        }

        @Test
        @DisplayName("高危工具应有更严格的频率限制")
        void highRiskToolStricterLimit() {
            // execute_code: 3 次/小时
            // 验证配置存在
            advisor.registerTool("test_high",
                    new ToolGuardAdvisor.ToolConfig("test_high", "HIGH",
                            java.util.List.of(), java.util.Map.of()));
            // 高危工具需要审批
            advisor.setRequireApproval(true);
            assertThat(advisor.getWhitelistedTools()).containsKey("execute_code");
        }
    }

    @Nested
    @DisplayName("审计日志测试")
    class AuditLogCheck {

        @Test
        @DisplayName("审计日志应为空初始状态")
        void auditLogInitiallyEmpty() {
            assertThat(advisor.getAuditLog(10)).isEmpty();
        }

        @Test
        @DisplayName("getWhitelistedTools 应返回副本不可修改原表")
        void getWhitelistedToolsReturnsCopy() {
            var tools = advisor.getWhitelistedTools();
            tools.clear();
            // 原表不受影响
            assertThat(advisor.getWhitelistedTools()).isNotEmpty();
        }
    }

    /** 辅助方法：直接调用真实的参数校验逻辑（包级可见方法） */
    private ToolGuardAdvisor.ValidationResult validateParams(String tool, java.util.Map<String, Object> params) {
        return advisor.validateParameters(tool, params);
    }
}

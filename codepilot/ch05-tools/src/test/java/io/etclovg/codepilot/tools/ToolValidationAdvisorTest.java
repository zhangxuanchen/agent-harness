package io.etclovg.codepilot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * ToolValidationAdvisor 单元测试。
 * 聚焦于工具描述验证、参数验证等核心逻辑。
 */
@DisplayName("工具验证 Advisor 测试")
class ToolValidationAdvisorTest {

    private ToolValidationAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new ToolValidationAdvisor();
    }

    @Nested
    @DisplayName("ACID 五要素验证测试")
    class DescriptionValidation {

        @Test
        @DisplayName("完整五要素应通过验证")
        void validateDescription_allElementsPresent_passes() {
            String completeDescription = "【做什么】执行 Python 代码 | 【什么时候用】需要计算时 | " +
                    "【参数说明】code: 代码字符串 | 【返回值说明】执行结果 | 【注意事项】注意安全";
            
            boolean result = advisor.validateDescriptionElements("test-tool", completeDescription);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("缺少要素应返回 false")
        void validateDescription_missingElements_fails() {
            String incompleteDescription = "【做什么】执行代码 | 【参数说明】code: 代码";
            
            boolean result = advisor.validateDescriptionElements("test-tool", incompleteDescription);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("空描述应返回 false")
        void validateDescription_emptyDescription_fails() {
            boolean result = advisor.validateDescriptionElements("test-tool", "");
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("null 描述应返回 false")
        void validateDescription_nullDescription_fails() {
            boolean result = advisor.validateDescriptionElements("test-tool", null);
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("工具注册验证测试")
    class RegistrationValidation {

        @Test
        @DisplayName("完整验证 - 有效工具应通过")
        void validateToolForRegistration_validTool_passes() {
            String completeDescription = "【做什么】执行代码 | 【什么时候用】需要时 | " +
                    "【参数说明】code: string | 【返回值说明】result | 【注意事项】安全";
            List<String> params = Arrays.asList("code", "timeout");
            
            ToolValidationAdvisor.ValidationResult result = advisor.validateToolForRegistration(
                    "python-exec", completeDescription, params);
            
            assertThat(result.passed()).isTrue();
        }

        @Test
        @DisplayName("完整验证 - 缺少要素应失败")
        void validateToolForRegistration_missingElements_fails() {
            String incompleteDescription = "【做什么】执行代码";
            
            ToolValidationAdvisor.ValidationResult result = advisor.validateToolForRegistration(
                    "python-exec", incompleteDescription, Arrays.asList("code"));
            
            assertThat(result.passed()).isFalse();
            assertThat(result.missingElements()).isNotEmpty();
        }

        @Test
        @DisplayName("完整验证 - 参数超过限制应标记为不通过")
        void validateToolForRegistration_tooManyParams_fails() {
            String completeDescription = "【做什么】执行代码 | 【什么时候用】需要时 | " +
                    "【参数说明】code: string | 【返回值说明】result | 【注意事项】安全";
            List<String> manyParams = Arrays.asList("p1", "p2", "p3", "p4", "p5", "p6");
            
            ToolValidationAdvisor.ValidationResult result = advisor.validateToolForRegistration(
                    "python-exec", completeDescription, manyParams);
            
            assertThat(result.passed()).isFalse();
            assertThat(result.paramsWithinLimit()).isFalse();
        }

        @Test
        @DisplayName("完整验证 - 包含模糊词汇应标记")
        void validateToolForRegistration_vagueTerms_detected() {
            String descriptionWithVague = "【做什么】执行一些相关的代码最近 | 【什么时候用】需要时 | " +
                    "【参数说明】code: string | 【返回值说明】result | 【注意事项】安全";
            
            ToolValidationAdvisor.ValidationResult result = advisor.validateToolForRegistration(
                    "python-exec", descriptionWithVague, Arrays.asList("code"));
            
            assertThat(result.vagueTerms()).isNotEmpty();
        }

        @Test
        @DisplayName("完整验证 - null 描述应处理")
        void validateToolForRegistration_nullDescription_handled() {
            ToolValidationAdvisor.ValidationResult result = advisor.validateToolForRegistration(
                    "test-tool", null, null);
            
            assertThat(result).isNotNull();
            assertThat(result.passed()).isFalse();
        }
    }

    @Nested
    @DisplayName("验证结果测试")
    class ValidationResultTest {

        @Test
        @DisplayName("ValidationResult 应正确存储验证信息")
        void validationResult_storesCorrectInfo() {
            ToolValidationAdvisor.ValidationResult result = advisor.validateToolForRegistration(
                    "my-tool", "【做什么】test | 【什么时候用】test | 【参数说明】test | 【返回值说明】test | 【注意事项】test",
                    Arrays.asList("param1"));
            
            assertThat(result.toolName()).isEqualTo("my-tool");
            assertThat(result.elementsFound()).isEqualTo(5);
            assertThat(result.totalElements()).isEqualTo(5);
        }
    }
}

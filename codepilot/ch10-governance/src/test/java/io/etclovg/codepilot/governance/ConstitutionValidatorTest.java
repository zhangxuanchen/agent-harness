package io.etclovg.codepilot.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * ConstitutionValidator 单元测试。
 * 聚焦于规则评估引擎——scope/action 二维模型、规则匹配、违规拦截。
 */
@DisplayName("章程验证器测试")
class ConstitutionValidatorTest {

    private ConstitutionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ConstitutionValidator();
    }

    @Nested
    @DisplayName("默认章程加载测试")
    class DefaultConstitution {

        @Test
        @DisplayName("默认章程应加载至少 5 条规则")
        void defaultConstitutionLoadsRules() {
            assertThat(validator.ruleCount()).isGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("默认章程应包含数据外泄防护规则")
        void defaultConstitutionHasDataExfiltrationRule() {
            // 触发 no-data-exfiltration 规则——输出包含 API_KEY
            var violations = validator.evaluateRules(
                    "The API_KEY is sk-abcdefghijklmnopqrstuvwxyz123456",
                    ConstitutionValidator.RuleScope.OUTPUT,
                    java.util.Map.of());
            assertThat(violations).isNotEmpty();
            assertThat(violations.get(0).ruleId()).isEqualTo("no-data-exfiltration");
        }
    }

    @Nested
    @DisplayName("规则匹配测试")
    class RuleMatching {

        @Test
        @DisplayName("人身攻击输入应被匹配并返回 HIGH 严重度")
        void personalAttackInputMatches() {
            var violations = validator.evaluateRules(
                    "you are an idiot assistant",
                    ConstitutionValidator.RuleScope.INPUT,
                    java.util.Map.of());
            assertThat(violations).isNotEmpty();
            assertThat(violations.get(0).ruleId()).isEqualTo("no-personal-attacks");
            assertThat(violations.get(0).severity()).isEqualTo(ConstitutionValidator.RuleSeverity.HIGH);
            assertThat(violations.get(0).action()).isEqualTo(ConstitutionValidator.RuleAction.BLOCK);
        }

        @Test
        @DisplayName("URL 数量异常应触发 no-malicious-urls 规则")
        void excessiveUrlsTriggerRule() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append("https://example").append(i).append(".com ");
            }
            var violations = validator.evaluateRules(
                    sb.toString(),
                    ConstitutionValidator.RuleScope.INPUT,
                    java.util.Map.of());
            assertThat(violations).isNotEmpty();
            assertThat(violations.get(0).ruleId()).isEqualTo("no-malicious-urls");
        }

        @Test
        @DisplayName("正常内容不应触发任何规则")
        void normalContentNoViolation() {
            var violations = validator.evaluateRules(
                    "What is the weather today?",
                    ConstitutionValidator.RuleScope.INPUT,
                    java.util.Map.of());
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("输出超长应触发 WARN 而非 BLOCK")
        void outputTooLongTriggersWarn() {
            String longOutput = "a".repeat(60000);
            var violations = validator.evaluateRules(
                    longOutput,
                    ConstitutionValidator.RuleScope.OUTPUT,
                    java.util.Map.of());
            assertThat(violations).isNotEmpty();
            assertThat(violations.get(0).ruleId()).isEqualTo("max-output-length");
            assertThat(violations.get(0).action()).isEqualTo(ConstitutionValidator.RuleAction.WARN);
        }
    }

    @Nested
    @DisplayName("scope 作用域测试")
    class ScopeFiltering {

        @Test
        @DisplayName("OUTPUT scope 规则不应匹配 INPUT scope 检查")
        void outputRuleDoesNotMatchInputScope() {
            // no-illegal-advice 规则 scope = [OUTPUT]，用 INPUT scope 检查不应触发
            var violations = validator.evaluateRules(
                    "how to do something",
                    ConstitutionValidator.RuleScope.INPUT,
                    java.util.Map.of());
            // no-illegal-advice 是 OUTPUT only，INPUT scope 不会触发它
            boolean hasIllegalAdvice = violations.stream()
                    .anyMatch(v -> v.ruleId().equals("no-illegal-advice"));
            assertThat(hasIllegalAdvice).isFalse();
        }
    }

    @Test
    @DisplayName("违规计数器应正确累加")
    void violationCountersAccumulate() {
        validator.evaluateRules("you are an idiot assistant",
                ConstitutionValidator.RuleScope.INPUT, java.util.Map.of());
        validator.evaluateRules("you are an idiot assistant",
                ConstitutionValidator.RuleScope.INPUT, java.util.Map.of());

        var stats = validator.getViolationStats();
        assertThat(stats).containsKey("no-personal-attacks");
        assertThat(stats.get("no-personal-attacks")).isGreaterThanOrEqualTo(2L);
    }
}

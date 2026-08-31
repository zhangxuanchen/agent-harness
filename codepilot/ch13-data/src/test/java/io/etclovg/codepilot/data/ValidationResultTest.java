package io.etclovg.codepilot.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidationResult 单元测试。
 * 验证 pass()/fail()/passed() 行为。
 */
@DisplayName("ValidationResult 测试")
class ValidationResultTest {

    @Test
    @DisplayName("pass() 返回通过结果且 passed() 为 true")
    void passReturnsPassedResult() {
        ValidationResult vr = ValidationResult.pass();
        assertThat(vr.passed()).isTrue();
        assertThat(vr.valid()).isTrue();
    }

    @Test
    @DisplayName("pass(validator) 携带校验器名称")
    void passWithValidator() {
        ValidationResult vr = ValidationResult.pass("ast-scanner");
        assertThat(vr.passed()).isTrue();
        assertThat(vr.validator()).isEqualTo("ast-scanner");
    }

    @Test
    @DisplayName("fail(message) 返回失败结果且 passed() 为 false")
    void failWithMessageReturnsFailedResult() {
        ValidationResult vr = ValidationResult.fail("危险模式命中");
        assertThat(vr.passed()).isFalse();
        assertThat(vr.valid()).isFalse();
        assertThat(vr.errorCount()).isEqualTo(1);
        assertThat(vr.errors()).contains("危险模式命中");
    }

    @Test
    @DisplayName("fail(validator, errors) 携带校验器名称与错误列表")
    void failWithValidatorAndErrors() {
        ValidationResult vr = ValidationResult.fail("safety", java.util.List.of("e1", "e2"));
        assertThat(vr.passed()).isFalse();
        assertThat(vr.validator()).isEqualTo("safety");
        assertThat(vr.errorCount()).isEqualTo(2);
    }
}

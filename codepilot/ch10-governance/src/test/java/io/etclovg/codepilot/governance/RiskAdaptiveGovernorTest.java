package io.etclovg.codepilot.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * RiskAdaptiveGovernor 单元测试。
 * 验证风险评分 → 治理等级映射、等级切换、策略参数返回。
 */
@DisplayName("风险自适应治理器测试")
class RiskAdaptiveGovernorTest {

    private RiskAdaptiveGovernor governor;

    @BeforeEach
    void setUp() {
        governor = new RiskAdaptiveGovernor();
    }

    @Nested
    @DisplayName("风险等级映射测试")
    class RiskTierMapping {

        @Test
        @DisplayName("低风险分数（<0.4）应映射为 NORMAL 等级")
        void lowRiskMapsToNormal() {
            assertThat(governor.adjust(0.1)).isEqualTo(RiskAdaptiveGovernor.RiskTier.NORMAL);
            assertThat(governor.adjust(0.3)).isEqualTo(RiskAdaptiveGovernor.RiskTier.NORMAL);
        }

        @Test
        @DisplayName("中风险分数（0.4-0.7）应映射为 ELEVATED 等级")
        void mediumRiskMapsToElevated() {
            assertThat(governor.adjust(0.4)).isEqualTo(RiskAdaptiveGovernor.RiskTier.ELEVATED);
            assertThat(governor.adjust(0.6)).isEqualTo(RiskAdaptiveGovernor.RiskTier.ELEVATED);
        }

        @Test
        @DisplayName("高风险分数（0.7-0.9）应映射为 HIGH 等级")
        void highRiskMapsToHigh() {
            assertThat(governor.adjust(0.7)).isEqualTo(RiskAdaptiveGovernor.RiskTier.HIGH);
            assertThat(governor.adjust(0.85)).isEqualTo(RiskAdaptiveGovernor.RiskTier.HIGH);
        }

        @Test
        @DisplayName("严重风险分数（≥0.9）应映射为 CRITICAL 等级")
        void criticalRiskMapsToCritical() {
            assertThat(governor.adjust(0.9)).isEqualTo(RiskAdaptiveGovernor.RiskTier.CRITICAL);
            assertThat(governor.adjust(1.0)).isEqualTo(RiskAdaptiveGovernor.RiskTier.CRITICAL);
        }
    }

    @Nested
    @DisplayName("等级切换测试")
    class TierSwitching {

        @Test
        @DisplayName("初始等级应为 NORMAL")
        void initialTierIsNormal() {
            assertThat(governor.currentTier()).isEqualTo(RiskAdaptiveGovernor.RiskTier.NORMAL);
        }

        @Test
        @DisplayName("相同等级不应触发切换日志")
        void sameTierNoSwitch() {
            governor.adjust(0.2);
            RiskAdaptiveGovernor.RiskTier tier1 = governor.currentTier();
            governor.adjust(0.3);
            assertThat(governor.currentTier()).isEqualTo(tier1);
        }

        @Test
        @DisplayName("等级升级应正确切换")
        void tierUpgradeSwitches() {
            governor.adjust(0.2);  // NORMAL
            assertThat(governor.currentTier()).isEqualTo(RiskAdaptiveGovernor.RiskTier.NORMAL);

            governor.adjust(0.8);  // HIGH
            assertThat(governor.currentTier()).isEqualTo(RiskAdaptiveGovernor.RiskTier.HIGH);

            governor.adjust(0.95); // CRITICAL
            assertThat(governor.currentTier()).isEqualTo(RiskAdaptiveGovernor.RiskTier.CRITICAL);
        }

        @Test
        @DisplayName("等级降级应正确切换")
        void tierDowngradeSwitches() {
            governor.adjust(0.95); // CRITICAL
            assertThat(governor.currentTier()).isEqualTo(RiskAdaptiveGovernor.RiskTier.CRITICAL);

            governor.adjust(0.1);  // NORMAL
            assertThat(governor.currentTier()).isEqualTo(RiskAdaptiveGovernor.RiskTier.NORMAL);
        }
    }

    @Nested
    @DisplayName("策略参数测试")
    class PolicyParameters {

        @Test
        @DisplayName("NORMAL 等级不应要求审批")
        void normalTierNoApprovalRequired() {
            governor.adjust(0.1);
            var policy = governor.currentPolicy();
            assertThat(policy.get("tier")).isEqualTo("NORMAL");
            assertThat(policy.get("approvalRequired")).isEqualTo(false);
        }

        @Test
        @DisplayName("CRITICAL 等级应要求审批")
        void criticalTierApprovalRequired() {
            governor.adjust(0.95);
            var policy = governor.currentPolicy();
            assertThat(policy.get("tier")).isEqualTo("CRITICAL");
            assertThat(policy.get("approvalRequired")).isEqualTo(true);
        }

        @Test
        @DisplayName("CRITICAL 等级每轮最大步数应为 1（lockdown）")
        void criticalTierLockdownSteps() {
            governor.adjust(0.95);
            var policy = governor.currentPolicy();
            assertThat(policy.get("maxStepsPerTurn")).isEqualTo(1);
            assertThat(policy.get("auditLevel")).isEqualTo("lockdown");
        }

        @Test
        @DisplayName("NORMAL 等级每轮最大步数应为 20")
        void normalTierMaxSteps() {
            governor.adjust(0.1);
            var policy = governor.currentPolicy();
            assertThat(policy.get("maxStepsPerTurn")).isEqualTo(20);
        }
    }
}

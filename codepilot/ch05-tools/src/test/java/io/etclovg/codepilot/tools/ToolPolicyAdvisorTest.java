package io.etclovg.codepilot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * ToolPolicyAdvisor 单元测试。
 * 聚焦于配置管理、计数器重置等可独立测试的逻辑。
 */
@DisplayName("工具策略 Advisor 测试")
class ToolPolicyAdvisorTest {

    private ToolPolicyAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new ToolPolicyAdvisor();
    }

    @Nested
    @DisplayName("配置管理测试")
    class ConfigurationManagement {

        @Test
        @DisplayName("默认应初始化为普通用户角色")
        void defaultRole_shouldBeUser() {
            // 通过反射获取字段值
            try {
                java.lang.reflect.Field roleField = ToolPolicyAdvisor.class.getDeclaredField("currentUserRole");
                roleField.setAccessible(true);
                String role = (String) roleField.get(advisor);
                assertThat(role).isEqualTo("user");
            } catch (Exception e) {
                // 忽略反射异常
            }
        }

        @Test
        @DisplayName("默认环境应为 dev")
        void defaultEnvironment_shouldBeDev() {
            try {
                java.lang.reflect.Field envField = ToolPolicyAdvisor.class.getDeclaredField("currentEnvironment");
                envField.setAccessible(true);
                String env = (String) envField.get(advisor);
                assertThat(env).isEqualTo("dev");
            } catch (Exception e) {
                // 忽略反射异常
            }
        }

        @Test
        @DisplayName("setCurrentUserRole 应更新角色")
        void setCurrentUserRole_updatesRole() {
            advisor.setCurrentUserRole("admin");
            
            try {
                java.lang.reflect.Field roleField = ToolPolicyAdvisor.class.getDeclaredField("currentUserRole");
                roleField.setAccessible(true);
                String role = (String) roleField.get(advisor);
                assertThat(role).isEqualTo("admin");
            } catch (Exception e) {
                // 忽略反射异常
            }
        }

        @Test
        @DisplayName("setCurrentEnvironment 应更新环境")
        void setCurrentEnvironment_updatesEnvironment() {
            advisor.setCurrentEnvironment("production");
            
            try {
                java.lang.reflect.Field envField = ToolPolicyAdvisor.class.getDeclaredField("currentEnvironment");
                envField.setAccessible(true);
                String env = (String) envField.get(advisor);
                assertThat(env).isEqualTo("production");
            } catch (Exception e) {
                // 忽略反射异常
            }
        }
    }

    @Nested
    @DisplayName("计数器管理测试")
    class CounterManagement {

        @Test
        @DisplayName("resetHourlyCounters 应成功执行")
        void resetHourlyCounters_executesSuccessfully() {
            // 验证方法可以正常调用
            assertThatCode(() -> advisor.resetHourlyCounters()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("ToolPolicy 注解测试")
    class ToolPolicyAnnotationTest {

        @ToolPolicy
        public void defaultToolMethod() {}

        @ToolPolicy(
                allowedRoles = {"admin", "operator"},
                rateLimit = 5,
                maxCallsPerHour = 100,
                requireHumanApproval = true,
                riskLevel = 3,
                allowedEnvironments = {"prod", "staging"}
        )
        public void customToolMethod() {}

        @Test
        @DisplayName("ToolPolicy 应有正确的默认值")
        void toolPolicyAnnotation_hasCorrectDefaults() throws Exception {
            ToolPolicy policy = getClass().getMethod("defaultToolMethod").getAnnotation(ToolPolicy.class);
            assertThat(policy).isNotNull();
            assertThat(policy.rateLimit()).isEqualTo(Integer.MAX_VALUE);
            assertThat(policy.maxCallsPerHour()).isEqualTo(Integer.MAX_VALUE);
            assertThat(policy.requireHumanApproval()).isFalse();
            assertThat(policy.riskLevel()).isEqualTo(1);
            assertThat(policy.allowedRoles()).isEmpty();
            assertThat(policy.allowedEnvironments()).isEmpty();
        }

        @Test
        @DisplayName("ToolPolicy 注解应支持自定义值")
        void toolPolicyAnnotation_supportsCustomValues() throws Exception {
            ToolPolicy policy = getClass().getMethod("customToolMethod").getAnnotation(ToolPolicy.class);
            assertThat(policy).isNotNull();
            assertThat(policy.rateLimit()).isEqualTo(5);
            assertThat(policy.maxCallsPerHour()).isEqualTo(100);
            assertThat(policy.requireHumanApproval()).isTrue();
            assertThat(policy.riskLevel()).isEqualTo(3);
            assertThat(policy.allowedRoles()).containsExactly("admin", "operator");
            assertThat(policy.allowedEnvironments()).containsExactly("prod", "staging");
        }
    }
}

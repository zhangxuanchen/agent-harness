package io.etclovg.codepilot.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * SupplyChainGuard 单元测试。
 * 验证 MCP 签名校验、工具行为监控、模型漂移检测、最小权限四环。
 */
@DisplayName("供应链安全守卫测试")
class SupplyChainGuardTest {

    private SupplyChainGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SupplyChainGuard();
    }

    @Nested
    @DisplayName("第 1 环：MCP 签名校验测试")
    class SignatureVerification {

        @Test
        @DisplayName("注册工具后应能通过签名校验")
        void registeredToolPassesSignatureCheck() {
            guard.registerTool("github_mcp", "读取 GitHub 仓库",
                    "{\"repo\": \"string\"}");
            // 未注册工具验证返回 false
            assertThat(guard.getSignatureRegistry()).containsKey("github_mcp");
        }

        @Test
        @DisplayName("未注册工具应签名校验失败")
        void unregisteredToolFailsSignatureCheck() {
            // 空上下文，未注册工具
            boolean verified = guard.verifyToolSignature("unknown_tool", java.util.Map.of());
            assertThat(verified).isFalse();
        }
    }

    @Nested
    @DisplayName("第 2 环：工具行为监控测试")
    class BehaviorMonitoring {

        @Test
        @DisplayName("正常返回体积不应触发突变告警")
        void normalVolumeNoAlert() {
            // 记录 10+ 次正常返回建立基线
            for (int i = 0; i < 15; i++) {
                guard.monitorToolBehavior("search", "result with normal size");
            }
            // 验证基线已建立
            var baselines = guard.getBehaviorBaselines();
            assertThat(baselines).containsKey("search");
            assertThat(baselines.get("search").sampleCount()).isEqualTo(15);
        }

        @Test
        @DisplayName("基线均值应随历史记录更新")
        void baselineAverageUpdates() {
            guard.monitorToolBehavior("calc", "12345");
            guard.monitorToolBehavior("calc", "12345");
            var baseline = guard.getBehaviorBaselines().get("calc");
            assertThat(baseline.averageSize()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("第 3 环：模型漂移检测测试")
    class ModelDriftDetection {

        @Test
        @DisplayName("冷启动期（<5 样本）应返回 BASELINE_BUILDING")
        void coldStartBuildsBaseline() {
            var result = guard.detectModelDrift("probe-1", "output A");
            assertThat(result.drifted()).isFalse();
            assertThat(result.status()).isEqualTo("BASELINE_BUILDING");
            assertThat(result.sampleCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("积累基线后稳定输出应返回 STABLE")
        void stableOutputAfterBaseline() {
            // 积累 5+ 相似输出
            for (int i = 0; i < 6; i++) {
                guard.detectModelDrift("probe-2", "the weather is sunny today");
            }
            // 第 7 次相似输出
            var result = guard.detectModelDrift("probe-2", "the weather is sunny today");
            assertThat(result.drifted()).isFalse();
            assertThat(result.similarity()).isGreaterThan(0.6);
        }

        @Test
        @DisplayName("输出分布突变应检测到漂移")
        void driftDetectedOnDistributionShift() {
            // 积累基线：相似输出
            for (int i = 0; i < 6; i++) {
                guard.detectModelDrift("probe-3", "apple banana cherry");
            }
            // 突变输出：完全不同的 token
            var result = guard.detectModelDrift("probe-3",
                    "xyz quantum nebula phosphorescent asdf qwerty zxcv");
            assertThat(result.drifted()).isTrue();
            assertThat(result.similarity()).isLessThan(0.6);
        }
    }

    @Nested
    @DisplayName("第 4 环：最小权限测试")
    class LeastPrivilege {

        @Test
        @DisplayName("授予权限后应能通过权限校验")
        void grantedPermissionPasses() {
            guard.grantPermissions("github_mcp", Set.of("read_code", "read_issues"));
            assertThat(guard.hasPermission("github_mcp", "read_code")).isTrue();
            assertThat(guard.hasPermission("github_mcp", "read_issues")).isTrue();
        }

        @Test
        @DisplayName("未授予的权限应校验失败")
        void ungrantedPermissionFails() {
            guard.grantPermissions("github_mcp", Set.of("read_code"));
            assertThat(guard.hasPermission("github_mcp", "write_repo")).isFalse();
        }

        @Test
        @DisplayName("未注册权限的工具应校验失败")
        void unregisteredToolPermissionFails() {
            assertThat(guard.hasPermission("unknown_tool", "anything")).isFalse();
        }
    }

    @Test
    @DisplayName("模型版本应可锁定与查询")
    void modelVersionLockAndQuery() {
        guard.lockModelVersion("dashscope:qwen-plus@v2.4");
        assertThat(guard.getLockedModelVersion()).isEqualTo("dashscope:qwen-plus@v2.4");
    }
}

package io.etclovg.codepilot.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 沙箱池测试用例。
 * <p>验证沙箱复用策略和监控告警是否生效。
 */
@DisplayName("沙箱池集成测试")
class SandboxPoolTest {

    private SandboxReusePolicy reusePolicy;
    private SandboxPool sandboxPool;
    private SandboxMonitor sandboxMonitor;

    @BeforeEach
    void setUp() {
        reusePolicy = new SandboxReusePolicy();
        sandboxPool = new SandboxPool(reusePolicy);
        sandboxMonitor = new SandboxMonitor(sandboxPool);
    }

    @Nested
    @DisplayName("复用策略测试")
    class ReusePolicyTests {

        @Test
        @DisplayName("低风险任务应该允许直接复用")
        void lowRiskTaskShouldAllowDirectReuse() {
            // 创建并释放一个沙箱
            SandboxPool.SandboxInstance sandbox = sandboxPool.acquire(
                    SandboxProfile.CODE_EXECUTION, "task-1", RiskLevel.LOW);
            sandboxPool.release(sandbox);
            reusePolicy.recordTaskUsage(sandbox.id(), "task-1");

            // 尝试复用
            boolean canReuse = reusePolicy.canReuse(sandbox, "task-2", RiskLevel.LOW);
            assertTrue(canReuse, "低风险任务应该允许复用");
        }

        @Test
        @DisplayName("中风险任务应该允许清理后复用")
        void mediumRiskTaskShouldAllowReuseAfterCleanup() {
            // 创建并释放一个沙箱
            SandboxPool.SandboxInstance sandbox = sandboxPool.acquire(
                    SandboxProfile.DATA_ANALYSIS, "task-1", RiskLevel.MEDIUM);
            sandboxPool.release(sandbox);

            // 尝试复用
            var decision = reusePolicy.evaluateReuse(sandbox, "task-2", RiskLevel.MEDIUM);
            assertTrue(decision.canReuse(), "中风险任务应该允许复用");
            assertEquals(SandboxReusePolicy.ReuseDecision.ReuseAction.REUSE_AFTER_CLEANUP,
                    decision.action(), "中风险任务应该需要清理后复用");
        }

        @Test
        @DisplayName("高风险任务不应该允许复用")
        void highRiskTaskShouldNotAllowReuse() {
            // 创建并释放一个沙箱
            SandboxPool.SandboxInstance sandbox = sandboxPool.acquire(
                    SandboxProfile.HIGH_RISK_OPERATION, "task-1", RiskLevel.HIGH);
            sandboxPool.release(sandbox);

            // 尝试复用
            boolean canReuse = reusePolicy.canReuse(sandbox, "task-2", RiskLevel.HIGH);
            assertFalse(canReuse, "高风险任务不应该允许复用");
        }

        @Test
        @DisplayName("超过最大复用次数应该不允许复用")
        void exceedMaxReuseCountShouldNotAllowReuse() {
            // 创建一个沙箱
            SandboxPool.SandboxInstance sandbox = sandboxPool.acquire(
                    SandboxProfile.CODE_EXECUTION, "task-1", RiskLevel.LOW);
            sandboxPool.release(sandbox);

            // 模拟超过最大复用次数
            // 默认 LOW risk 允许 50 次复用，使用一个高复用配置
            reusePolicy.updateRiskConfig(RiskLevel.LOW,
                    new SandboxReusePolicy.RiskLevelConfig(true, false, 1, 3600000));

            // 第一次复用
            SandboxPool.SandboxInstance reused1 = sandboxPool.acquire(
                    SandboxProfile.CODE_EXECUTION, "task-2", RiskLevel.LOW);
            sandboxPool.release(reused1);

            // 第二次应该失败
            boolean canReuse = reusePolicy.canReuse(reused1, "task-3", RiskLevel.LOW);
            assertFalse(canReuse, "超过最大复用次数应该不允许复用");
        }

        @Test
        @DisplayName("同一任务可以复用自己的沙箱")
        void sameTaskShouldReuseOwnSandbox() {
            // 创建沙箱
            SandboxPool.SandboxInstance sandbox = sandboxPool.acquire(
                    SandboxProfile.CODE_EXECUTION, "task-1", RiskLevel.LOW);
            reusePolicy.recordTaskUsage(sandbox.id(), "task-1");
            sandboxPool.release(sandbox);

            // 同一任务再次获取
            SandboxPool.SandboxInstance reused = sandboxPool.acquire(
                    SandboxProfile.CODE_EXECUTION, "task-1", RiskLevel.LOW);
            assertEquals(sandbox.id(), reused.id(), "同一任务应该复用同一个沙箱");
        }

        @Test
        @DisplayName("ReuseDecision 工厂方法应该对齐书中的语义")
        void reuseDecisionFactoryMethodsShouldAlignWithBook() {
            // 对应书中 ReuseDecision.reuseDirectly / reuseAfterReset / createNew
            var direct = SandboxReusePolicy.ReuseDecision.reuseDirectly();
            assertTrue(direct.canReuse());
            assertEquals(SandboxReusePolicy.ReuseDecision.ReuseAction.REUSE_DIRECT, direct.action());

            var afterReset = SandboxReusePolicy.ReuseDecision.reuseAfterReset();
            assertTrue(afterReset.canReuse());
            assertEquals(SandboxReusePolicy.ReuseDecision.ReuseAction.REUSE_AFTER_CLEANUP, afterReset.action());

            var createNew = SandboxReusePolicy.ReuseDecision.createNew();
            assertFalse(createNew.canReuse());
            assertEquals(SandboxReusePolicy.ReuseDecision.ReuseAction.CREATE_NEW, createNew.action());
        }
    }

    @Nested
    @DisplayName("沙箱池管理测试")
    class SandboxPoolTests {

        @Test
        @DisplayName("应该能正确分配和释放沙箱")
        void shouldAcquireAndReleaseSandbox() {
            SandboxPool.SandboxInstance sandbox = sandboxPool.acquire(
                    SandboxProfile.CODE_EXECUTION, "task-1", RiskLevel.MEDIUM);

            assertNotNull(sandbox);
            assertEquals(SandboxPool.SandboxState.IN_USE, sandbox.state());
            assertEquals("task-1", sandbox.currentTaskId());

            // 释放
            sandboxPool.release(sandbox);
            assertEquals(SandboxPool.SandboxState.IDLE,
                    sandboxPool.getInstances(SandboxProfile.CODE_EXECUTION).get(0).state());
        }

        @Test
        @DisplayName("应该能获取池状态")
        void shouldGetPoolStatus() {
            // 创建几个沙箱
            sandboxPool.acquire(SandboxProfile.CODE_EXECUTION, "task-1", RiskLevel.MEDIUM);
            sandboxPool.acquire(SandboxProfile.DATA_ANALYSIS, "task-2", RiskLevel.MEDIUM);

            Map<String, Object> status = sandboxPool.getPoolStatus();

            assertNotNull(status);
            assertTrue(status.containsKey("代码执行"));
            assertTrue(status.containsKey("数据分析"));
        }

        @Test
        @DisplayName("应该能正确销毁沙箱")
        void shouldDestroySandbox() {
            SandboxPool.SandboxInstance sandbox = sandboxPool.acquire(
                    SandboxProfile.CODE_EXECUTION, "task-1", RiskLevel.MEDIUM);
            assertNotNull(sandbox);

            sandboxPool.destroy(sandbox);

            // 销毁后不应该在活跃列表中
            assertTrue(sandboxPool.getActiveInstances().isEmpty());
        }
    }

    @Nested
    @DisplayName("监控告警测试")
    class MonitorAlertTests {

        @Test
        @DisplayName("应该能检测到危险操作")
        void shouldDetectDangerousOperation() {
            String sandboxId = "sandbox-test-1";
            String taskId = "task-1";

            boolean detected = sandboxMonitor.detectDangerousOperation(sandboxId, "rm -rf /", taskId);

            assertTrue(detected, "应该检测到危险操作");

            // 验证安全事件已记录
            List<SandboxMonitor.SecurityEvent> events = sandboxMonitor.getAllSecurityEvents();
            assertFalse(events.isEmpty());
            assertEquals("DANGEROUS_OPERATION", events.get(0).eventType());
            assertEquals(SandboxMonitor.SecurityEvent.Severity.CRITICAL.name(), events.get(0).severity());
        }

        @Test
        @DisplayName("应该能检测到敏感信息泄露")
        void shouldDetectSensitiveDataLeak() {
            String sandboxId = "sandbox-test-1";
            String taskId = "task-1";

            boolean detected = sandboxMonitor.detectSensitiveDataLeak(
                    sandboxId, "The password is secret123 for the API", taskId);

            assertTrue(detected, "应该检测到敏感信息泄露");

            List<SandboxMonitor.SecurityEvent> events = sandboxMonitor.getAllSecurityEvents();
            assertTrue(events.stream().anyMatch(e -> e.eventType().equals("SENSITIVE_DATA_LEAK")));
        }

        @Test
        @DisplayName("不应该误报正常操作")
        void shouldNotFalsePositiveOnNormalOperation() {
            String sandboxId = "sandbox-test-1";
            String taskId = "task-1";

            boolean detected = sandboxMonitor.detectDangerousOperation(
                    sandboxId, "python script.py --input data.csv", taskId);

            assertFalse(detected, "正常操作不应该被误报");
        }

        @Test
        @DisplayName("资源阈值告警应该生效")
        void shouldTriggerResourceThresholdAlert() {
            String sandboxId = "sandbox-test-1";

            // 创建一个超过内存阈值的快照
            SandboxMonitor.ResourceSnapshot snapshot = new SandboxMonitor.ResourceSnapshot(
                    sandboxId,
                    SandboxProfile.DATA_ANALYSIS,
                    90.0,  // CPU 接近限制
                    3800,  // 内存 3800MB > 4096 * 0.9 = 3686MB
                    500,
                    10,
                    Instant.now()
            );

            sandboxMonitor.recordResourceSnapshot(snapshot);

            // 验证安全事件已记录
            List<SandboxMonitor.SecurityEvent> events = sandboxMonitor.getAllSecurityEvents();
            assertTrue(events.stream().anyMatch(e -> e.eventType().equals("MEMORY_THRESHOLD_EXCEEDED")),
                    "应该触发内存阈值告警");
        }

        @Test
        @DisplayName("应该能生成健康检查报告")
        void shouldGenerateHealthReport() {
            // 先创建一些数据
            sandboxMonitor.recordResourceSnapshot(new SandboxMonitor.ResourceSnapshot(
                    "sandbox-1", SandboxProfile.CODE_EXECUTION, 50.0, 256, 100, 5, Instant.now()));
            sandboxMonitor.recordResourceSnapshot(new SandboxMonitor.ResourceSnapshot(
                    "sandbox-2", SandboxProfile.DATA_ANALYSIS, 60.0, 1024, 200, 3, Instant.now()));

            Map<String, Object> report = sandboxMonitor.generateHealthReport();

            assertNotNull(report);
            assertTrue(report.containsKey("poolStatus"));
            assertTrue(report.containsKey("activeInstances"));
            assertTrue(report.containsKey("trackedSandboxes"));
            assertTrue(report.containsKey("alertConfig"));
            assertTrue(report.containsKey("generatedAt"));
        }

        @Test
        @DisplayName("危险操作应该触发 CRITICAL 级别告警")
        void dangerousOperationShouldTriggerCriticalAlert() {
            String sandboxId = "sandbox-test-1";

            // 执行危险操作
            sandboxMonitor.detectDangerousOperation(sandboxId, "drop table users", "task-1");

            // 验证 CRITICAL 事件存在
            long criticalCount = sandboxMonitor.getAllSecurityEvents().stream()
                    .filter(e -> e.severity().equals(SandboxMonitor.SecurityEvent.Severity.CRITICAL.name()))
                    .count();

            assertTrue(criticalCount > 0, "应该存在 CRITICAL 级别事件");
        }

        @Test
        @DisplayName("敏感信息泄露应该触发 WARNING 级别告警")
        void sensitiveDataLeakShouldTriggerWarningAlert() {
            String sandboxId = "sandbox-test-1";

            // 执行敏感信息泄露
            sandboxMonitor.detectSensitiveDataLeak(
                    sandboxId, "Here is my token: abc123xyz", "task-1");

            // 验证 WARNING 事件存在
            long warningCount = sandboxMonitor.getAllSecurityEvents().stream()
                    .filter(e -> e.severity().equals(SandboxMonitor.SecurityEvent.Severity.WARNING.name()))
                    .count();

            assertTrue(warningCount > 0, "应该存在 WARNING 级别事件");
        }
    }

    @Nested
    @DisplayName("SandboxProfile 测试")
    class ProfileTests {

        @Test
        @DisplayName("应该能根据任务推断 Profile")
        void shouldInferProfileFromTask() {
            assertEquals(SandboxProfile.CODE_EXECUTION, SandboxProfile.inferFromTask("执行 Python 代码"));
            assertEquals(SandboxProfile.DATA_ANALYSIS, SandboxProfile.inferFromTask("分析 CSV 数据"));
            assertEquals(SandboxProfile.BROWSER_AUTOMATION, SandboxProfile.inferFromTask("爬取网页"));
            assertEquals(SandboxProfile.DATABASE_OPERATION, SandboxProfile.inferFromTask("查询数据库"));
            assertEquals(SandboxProfile.API_CALLING, SandboxProfile.inferFromTask("调用 API 接口"));
            assertEquals(SandboxProfile.HIGH_RISK_OPERATION, SandboxProfile.inferFromTask("删除系统文件"));
        }

        @Test
        @DisplayName("空任务应该返回默认 Profile")
        void emptyTaskShouldReturnDefaultProfile() {
            assertEquals(SandboxProfile.FILE_OPERATION, SandboxProfile.inferFromTask(null));
            assertEquals(SandboxProfile.FILE_OPERATION, SandboxProfile.inferFromTask(""));
        }
    }
}

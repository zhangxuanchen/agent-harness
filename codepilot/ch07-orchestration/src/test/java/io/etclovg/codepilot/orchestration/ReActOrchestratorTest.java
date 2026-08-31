package io.etclovg.codepilot.orchestration;

import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ReActOrchestrator 单元测试")
class ReActOrchestratorTest {

    private StateMachineManager stateMachine;
    private ReActOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        stateMachine = new StateMachineManager();
        orchestrator = new ReActOrchestrator(stateMachine);
    }

    private void initTask(String taskId) throws Exception {
        Method initMethod = ReActOrchestrator.class.getDeclaredMethod("initTask", String.class);
        initMethod.setAccessible(true);
        initMethod.invoke(orchestrator, taskId);
    }

    private boolean checkStepLimit(String taskId) throws Exception {
        Method m = ReActOrchestrator.class.getDeclaredMethod("checkStepLimit", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(orchestrator, taskId);
    }

    private boolean isTotalTimeout(String taskId) throws Exception {
        Method m = ReActOrchestrator.class.getDeclaredMethod("isTotalTimeout", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(orchestrator, taskId);
    }

    private boolean detectDuplicate(String taskId, String signature) throws Exception {
        Method m = ReActOrchestrator.class.getDeclaredMethod("detectDuplicate", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(orchestrator, taskId, signature);
    }

    private String computeSignature(String toolName, String toolArgs) throws Exception {
        Method m = ReActOrchestrator.class.getDeclaredMethod("computeSignature", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(orchestrator, toolName, toolArgs);
    }

    private boolean isCircuitOpen(String taskId) throws Exception {
        Method m = ReActOrchestrator.class.getDeclaredMethod("isCircuitOpen", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(orchestrator, taskId);
    }

    private void recordSuccess(String taskId) throws Exception {
        Method m = ReActOrchestrator.class.getDeclaredMethod("recordSuccess", String.class);
        m.setAccessible(true);
        m.invoke(orchestrator, taskId);
    }

    private void recordFailure(String taskId) throws Exception {
        Method m = ReActOrchestrator.class.getDeclaredMethod("recordFailure", String.class);
        m.setAccessible(true);
        m.invoke(orchestrator, taskId);
    }

    @SuppressWarnings("unchecked")
    private <T> T getPrivateField(String fieldName) throws Exception {
        Field f = ReActOrchestrator.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (T) f.get(orchestrator);
    }

    private void setPrivateField(String fieldName, Object value) throws Exception {
        Field f = ReActOrchestrator.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(orchestrator, value);
    }

    // ==================== 步骤限制 ====================

    @Nested
    @DisplayName("步骤限制 checkStepLimit")
    class StepLimitTests {

        @Test
        @DisplayName("计数器小于 maxSteps 时通过")
        void counterLessThanMax_passes() throws Exception {
            orchestrator.setMaxSteps(5);
            initTask("task-1");

            setPrivateField("stepCounters", new ConcurrentHashMap<>());
            Map<String, AtomicInteger> counters = getPrivateField("stepCounters");
            counters.put("task-1", new AtomicInteger(3));

            assertThat(checkStepLimit("task-1")).isTrue();
        }

        @Test
        @DisplayName("计数器等于 maxSteps 时被阻止")
        void counterEqualsMax_blocks() throws Exception {
            orchestrator.setMaxSteps(5);
            initTask("task-1");

            Map<String, AtomicInteger> counters = getPrivateField("stepCounters");
            counters.put("task-1", new AtomicInteger(5));

            assertThat(checkStepLimit("task-1")).isFalse();
        }

        @Test
        @DisplayName("计数器超过 maxSteps 时被阻止")
        void counterExceedsMax_blocks() throws Exception {
            orchestrator.setMaxSteps(3);
            initTask("task-1");

            Map<String, AtomicInteger> counters = getPrivateField("stepCounters");
            counters.put("task-1", new AtomicInteger(10));

            assertThat(checkStepLimit("task-1")).isFalse();
        }

        @Test
        @DisplayName("未初始化的任务返回 false")
        void uninitializedTask_returnsFalse() throws Exception {
            assertThat(checkStepLimit("nonexistent")).isFalse();
        }

        @Test
        @DisplayName("设置 maxSteps 后行为改变")
        void changingMaxSteps_changesBehavior() throws Exception {
            orchestrator.setMaxSteps(2);
            initTask("task-1");

            Map<String, AtomicInteger> counters = getPrivateField("stepCounters");
            counters.put("task-1", new AtomicInteger(2));

            assertThat(checkStepLimit("task-1")).isFalse();

            orchestrator.setMaxSteps(10);
            assertThat(checkStepLimit("task-1")).isTrue();
        }
    }

    // ==================== 超时控制 ====================

    @Nested
    @DisplayName("超时控制 isTotalTimeout")
    class TimeoutTests {

        @Test
        @DisplayName("未超时时通过")
        void withinTimeout_passes() throws Exception {
            orchestrator.setTotalTimeoutSeconds(3600);
            initTask("task-1");

            assertThat(isTotalTimeout("task-1")).isFalse();
        }

        @Test
        @DisplayName("超时时被阻止")
        void expiredTimeout_blocks() throws Exception {
            orchestrator.setTotalTimeoutSeconds(-1);
            initTask("task-1");

            assertThat(isTotalTimeout("task-1")).isTrue();
        }

        @Test
        @DisplayName("未初始化的任务视为超时")
        void uninitializedTask_returnsTrue() throws Exception {
            assertThat(isTotalTimeout("nonexistent")).isTrue();
        }
    }

    // ==================== 重复检测 ====================

    @Nested
    @DisplayName("重复检测 detectDuplicate")
    class DuplicateDetectionTests {

        @Test
        @DisplayName("首次调用不视为重复")
        void firstCall_notDuplicate() throws Exception {
            initTask("task-1");

            assertThat(detectDuplicate("task-1", "sig-A")).isFalse();
        }

        @Test
        @DisplayName("相同签名视为重复")
        void sameSignature_isDuplicate() throws Exception {
            initTask("task-1");
            detectDuplicate("task-1", "sig-A");

            assertThat(detectDuplicate("task-1", "sig-A")).isTrue();
        }

        @Test
        @DisplayName("不同签名不视为重复")
        void differentSignatures_notDuplicate() throws Exception {
            initTask("task-1");
            detectDuplicate("task-1", "sig-A");

            assertThat(detectDuplicate("task-1", "sig-B")).isFalse();
        }

        @Test
        @DisplayName("多个签名各自独立判定")
        void multipleSignatures_independent() throws Exception {
            initTask("task-1");
            detectDuplicate("task-1", "sig-A");
            detectDuplicate("task-1", "sig-B");
            detectDuplicate("task-1", "sig-C");

            assertThat(detectDuplicate("task-1", "sig-A")).isTrue();
            assertThat(detectDuplicate("task-1", "sig-B")).isTrue();
            assertThat(detectDuplicate("task-1", "sig-C")).isTrue();
            assertThat(detectDuplicate("task-1", "sig-D")).isFalse();
        }
    }

    // ==================== 签名计算 ====================

    @Nested
    @DisplayName("签名计算 computeSignature")
    class ComputeSignatureTests {

        @Test
        @DisplayName("相同输入产生相同哈希")
        void sameInput_sameHash() throws Exception {
            String hash1 = computeSignature("search", "{query:test}");
            String hash2 = computeSignature("search", "{query:test}");

            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("不同输入产生不同哈希")
        void differentInput_differentHash() throws Exception {
            String hash1 = computeSignature("search", "{query:test}");
            String hash2 = computeSignature("search", "{query:other}");

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("工具名不同产生不同哈希")
        void differentToolName_differentHash() throws Exception {
            String hash1 = computeSignature("search", "args");
            String hash2 = computeSignature("calculator", "args");

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("产生非空 Base64 编码字符串")
        void producesNonEmptyHash() throws Exception {
            String hash = computeSignature("tool", "args");

            assertThat(hash).isNotEmpty();
            assertThat(hash).isNotBlank();
        }
    }

    // ==================== 熔断器 ====================

    @Nested
    @DisplayName("熔断器 isCircuitOpen")
    class CircuitBreakerTests {

        @Test
        @DisplayName("CLOSED 状态允许通过")
        void closedState_allows() throws Exception {
            orchestrator.setCircuitBreakerThreshold(5);
            orchestrator.setCircuitBreakerCooldownSeconds(30);
            initTask("task-1");

            recordFailure("task-1");
            recordSuccess("task-1");

            assertThat(isCircuitOpen("task-1")).isFalse();
        }

        @Test
        @DisplayName("OPEN 状态阻止调用")
        void openState_blocks() throws Exception {
            orchestrator.setCircuitBreakerThreshold(1);
            orchestrator.setCircuitBreakerCooldownSeconds(3600);
            initTask("task-1");

            recordFailure("task-1");

            assertThat(isCircuitOpen("task-1")).isTrue();
        }

        @Test
        @DisplayName("OPEN + 冷却完成 → HALF_OPEN 允许通过")
        void openWithCooldown_allowsHalfOpen() throws Exception {
            orchestrator.setCircuitBreakerThreshold(1);
            orchestrator.setCircuitBreakerCooldownSeconds(3600);
            initTask("task-1");

            recordFailure("task-1");
            assertThat(isCircuitOpen("task-1")).isTrue();

            orchestrator.setCircuitBreakerCooldownSeconds(0);
            assertThat(isCircuitOpen("task-1")).isFalse();
        }

        @Test
        @DisplayName("HALF_OPEN 状态成功后 → CLOSED")
        void halfOpenSuccess_closesCircuit() throws Exception {
            orchestrator.setCircuitBreakerThreshold(1);
            orchestrator.setCircuitBreakerCooldownSeconds(0);
            initTask("task-1");

            recordFailure("task-1");
            isCircuitOpen("task-1");

            recordSuccess("task-1");

            assertThat(isCircuitOpen("task-1")).isFalse();

            recordFailure("task-1");
            assertThat(isCircuitOpen("task-1")).isFalse();
        }
    }

    @Nested
    @DisplayName("熔断器记录 recordSuccess / recordFailure")
    class CircuitRecordTests {

        @Test
        @DisplayName("连续失败 4 次仍为 CLOSED（阈值 5）")
        void fourFailures_staysClosed() throws Exception {
            orchestrator.setCircuitBreakerThreshold(5);
            orchestrator.setCircuitBreakerCooldownSeconds(3600);
            initTask("task-1");

            for (int i = 0; i < 4; i++) {
                recordFailure("task-1");
            }

            assertThat(isCircuitOpen("task-1")).isFalse();
        }

        @Test
        @DisplayName("第 5 次失败触发 OPEN")
        void fifthFailure_opensCircuit() throws Exception {
            orchestrator.setCircuitBreakerThreshold(5);
            orchestrator.setCircuitBreakerCooldownSeconds(3600);
            initTask("task-1");

            for (int i = 0; i < 5; i++) {
                recordFailure("task-1");
            }

            assertThat(isCircuitOpen("task-1")).isTrue();
        }

        @Test
        @DisplayName("recordSuccess 重置失败计数")
        void success_resetsFailureCount() throws Exception {
            orchestrator.setCircuitBreakerThreshold(3);
            orchestrator.setCircuitBreakerCooldownSeconds(3600);
            initTask("task-1");

            recordFailure("task-1");
            recordFailure("task-1");

            recordSuccess("task-1");

            recordFailure("task-1");

            assertThat(isCircuitOpen("task-1")).isFalse();
        }

        @Test
        @DisplayName("HALF_OPEN 状态下 recordSuccess → CLOSED")
        void halfOpenSuccess_closesCircuit() throws Exception {
            orchestrator.setCircuitBreakerThreshold(1);
            orchestrator.setCircuitBreakerCooldownSeconds(0);
            initTask("task-1");

            recordFailure("task-1");
            isCircuitOpen("task-1");
            isCircuitOpen("task-1");

            recordSuccess("task-1");

            assertThat(isCircuitOpen("task-1")).isFalse();

            orchestrator.setCircuitBreakerCooldownSeconds(3600);
            recordFailure("task-1");
            assertThat(isCircuitOpen("task-1")).isTrue();
        }
    }

    // ==================== 公共配置 ====================

    @Nested
    @DisplayName("公共配置方法")
    class ConfigTests {

        @Test
        @DisplayName("setMaxSteps 正确更新")
        void setMaxSteps_updatesBehavior() throws Exception {
            orchestrator.setMaxSteps(100);

            Field f = ReActOrchestrator.class.getDeclaredField("maxSteps");
            f.setAccessible(true);
            assertThat(f.getInt(orchestrator)).isEqualTo(100);
        }

        @Test
        @DisplayName("setCircuitBreakerThreshold 正确更新")
        void setCircuitBreakerThreshold_updates() throws Exception {
            orchestrator.setCircuitBreakerThreshold(3);

            Field f = ReActOrchestrator.class.getDeclaredField("circuitBreakerThreshold");
            f.setAccessible(true);
            assertThat(f.getInt(orchestrator)).isEqualTo(3);
        }

        @Test
        @DisplayName("setTotalTimeoutSeconds 正确更新")
        void setTotalTimeoutSeconds_updates() throws Exception {
            orchestrator.setTotalTimeoutSeconds(1200);

            Field f = ReActOrchestrator.class.getDeclaredField("totalTimeoutSeconds");
            f.setAccessible(true);
            assertThat(f.getInt(orchestrator)).isEqualTo(1200);
        }

        @Test
        @DisplayName("setCircuitBreakerCooldownSeconds 正确更新")
        void setCircuitBreakerCooldownSeconds_updates() throws Exception {
            orchestrator.setCircuitBreakerCooldownSeconds(60);

            Field f = ReActOrchestrator.class.getDeclaredField("circuitBreakerCooldownSeconds");
            f.setAccessible(true);
            assertThat(f.getInt(orchestrator)).isEqualTo(60);
        }

        @Test
        @DisplayName("setStepTimeoutSeconds 正确更新")
        void setStepTimeoutSeconds_updates() throws Exception {
            orchestrator.setStepTimeoutSeconds(120);

            Field f = ReActOrchestrator.class.getDeclaredField("stepTimeoutSeconds");
            f.setAccessible(true);
            assertThat(f.getInt(orchestrator)).isEqualTo(120);
        }
    }
}
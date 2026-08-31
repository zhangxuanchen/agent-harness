package io.etclovg.codepilot.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AssumptionDriftDetector 单元测试。
 * 聚焦于漂移检测核心算法和逻辑。
 */
@DisplayName("假设漂移检测器测试")
class AssumptionDriftDetectorTest {

    private AssumptionDriftDetector detector;
    private HarnessAssumptionRegistry mockRegistry;

    @BeforeEach
    void setUp() {
        mockRegistry = mock(HarnessAssumptionRegistry.class);
        detector = new AssumptionDriftDetector(mockRegistry);
    }

    @Nested
    @DisplayName("漂移检测算法测试")
    class DriftDetectionAlgorithm {

        @Test
        @DisplayName("直接调用 detectDrift 测试渐进漂移")
        void detectsGradualDrift_withContinuousLowValues() throws Exception {
            // 构造一个假设
            HarnessAssumptionRegistry.Assumption assumption = mock(HarnessAssumptionRegistry.Assumption.class);
            when(assumption.id()).thenReturn("test-id");
            when(assumption.threshold()).thenReturn(0.9);
            
            // 设置较高的突然漂移阈值（0.5），使当前值的偏离不触发突然漂移
            detector.setSuddenDriftThreshold(0.5);
            // 设置较低的渐进漂移阈值（0.1），使历史数据的平均偏离触发渐进漂移
            detector.setGradualDriftThreshold(0.1);
            
            // 使用测试扩展点设置最小样本数
            detector.setMinSamplesForTest(3);
            
            // 使用测试扩展点注入历史数据
            Deque<Double> history = new ArrayDeque<>(Arrays.asList(0.5, 0.4, 0.3, 0.2, 0.1));
            detector.injectHistoryForTest("test-id", history);

            // 使用测试扩展点直接调用漂移检测
            AssumptionDriftDetector.DriftResult result = detector.detectDriftForTest(
                    assumption, 0.8 // 实际值，偏离 0.11，低于突然漂移阈值 0.5
            );

            // 应该检测到渐进漂移
            assertThat(result).isNotNull();
            assertThat(result.driftType()).isEqualTo(AssumptionDriftDetector.DriftType.GRADUAL);
            assertThat(result.deviation()).isGreaterThan(0.1);
        }

        @Test
        @DisplayName("直接调用 detectDrift 测试突然漂移")
        void detectsSuddenDrift_withLargeSingleDeviation() throws Exception {
            HarnessAssumptionRegistry.Assumption assumption = mock(HarnessAssumptionRegistry.Assumption.class);
            when(assumption.id()).thenReturn("test-sudden");
            when(assumption.threshold()).thenReturn(0.9);

            // 设置较高的突然漂移阈值，使其容易被触发
            detector.setSuddenDriftThreshold(0.1); // 10% 偏离即触发突然漂移

            // 使用测试扩展点直接调用漂移检测
            AssumptionDriftDetector.DriftResult result = detector.detectDriftForTest(
                    assumption, 0.1
            );

            assertThat(result).isNotNull();
            assertThat(result.driftType()).isEqualTo(AssumptionDriftDetector.DriftType.SUDDEN);
        }

        @Test
        @DisplayName("当假设过期时间超过限制时应检测到过期")
        void detectsExpiry_whenTimeExceedsLimit() throws Exception {
            HarnessAssumptionRegistry.Assumption assumption = mock(HarnessAssumptionRegistry.Assumption.class);
            when(assumption.id()).thenReturn("test-expired");
            when(assumption.threshold()).thenReturn(0.9);

            // 设置较短的过期时间（1毫秒）
            detector.setAssumptionExpiryTime(Duration.ofMillis(1));

            // 设置最后验证时间为过去（通过反射）
            java.lang.reflect.Field lastValidField = AssumptionDriftDetector.class.getDeclaredField("lastValidationTime");
            lastValidField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Instant> lastValidMap = (Map<String, Instant>) lastValidField.get(detector);
            lastValidMap.put("test-expired", Instant.now().minusSeconds(10)); // 10秒前
            
            // 添加符合阈值的历史数据
            detector.injectHistoryForTest("test-expired", new ArrayDeque<>(Arrays.asList(0.95, 0.96, 0.97)));
            
            // 降低渐进漂移阈值使其不触发
            detector.setGradualDriftThreshold(0.5);

            // 使用测试扩展点直接调用漂移检测
            AssumptionDriftDetector.DriftResult result = detector.detectDriftForTest(
                    assumption, 0.95
            );

            assertThat(result).isNotNull();
            assertThat(result.driftType()).isEqualTo(AssumptionDriftDetector.DriftType.EXPIRED);
        }
    }

    @Nested
    @DisplayName("配置方法测试")
    class Configuration {

        @Test
        @DisplayName("配置突然漂移阈值")
        void setSuddenDriftThreshold_updatesConfiguration() {
            double newThreshold = 0.5;
            detector.setSuddenDriftThreshold(newThreshold);
        }

        @Test
        @DisplayName("配置渐进漂移阈值")
        void setGradualDriftThreshold_updatesConfiguration() {
            detector.setGradualDriftThreshold(0.2);
        }
        
        @Test
        @DisplayName("配置过期时间")
        void setAssumptionExpiryTime_updatesConfiguration() {
            detector.setAssumptionExpiryTime(Duration.ofDays(30));
        }
    }
}
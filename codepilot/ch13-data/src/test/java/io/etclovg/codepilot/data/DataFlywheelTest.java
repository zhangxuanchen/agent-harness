package io.etclovg.codepilot.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * DataFlywheel 单元测试。
 * 验证 iterate() 不抛异常（样本不足时直接返回）。
 */
@DisplayName("DataFlywheel 测试")
class DataFlywheelTest {

    @Test
    @DisplayName("iterate() 在样本不足时不抛异常")
    void iterateDoesNotThrow() {
        ErrorCollector collector = new ErrorCollector();
        DataCleaner cleaner = new DataCleaner();
        ModelTrainer trainer = new ModelTrainer() { };  // 全默认方法接口
        DeploymentManager deployer = new DeploymentManager();
        MetricsEvaluator evaluator = new MetricsEvaluator();

        DataFlywheel flywheel = new DataFlywheel(collector, cleaner, trainer, deployer, evaluator);

        // collect 桩实现返回空列表，samples.size() < 50 直接返回
        assertThatCode(() -> flywheel.iterate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("保留 executeCycle 便捷方法可用")
    void executeCycleRemainsAvailable() {
        DataFlywheel flywheel = new DataFlywheel(
                new ErrorCollector(), new DataCleaner(), new ModelTrainer() { },
                new DeploymentManager(), new MetricsEvaluator());

        DataFlywheel.FlywheelCycle cycle = flywheel.executeCycle("data");
        assertThat(cycle).isNotNull();
        assertThat(flywheel.getCycleCount()).isEqualTo(1);
    }
}

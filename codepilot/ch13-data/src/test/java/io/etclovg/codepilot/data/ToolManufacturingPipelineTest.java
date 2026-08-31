package io.etclovg.codepilot.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolManufacturingPipeline 单元测试。
 * 验证当 findFrequent 返回空列表时 discoverAndManufacture 返回 null。
 */
@DisplayName("ToolManufacturingPipeline 测试")
class ToolManufacturingPipelineTest {

    @Test
    @DisplayName("findFrequent 为空时 discoverAndManufacture 返回 null")
    void discoverAndManufactureReturnsNullWhenNoPatterns() {
        OperationTracker tracker = new OperationTracker();
        SafetyValidator validator = new SafetyValidator();
        ApprovalGateway gateway = new ApprovalGateway();
        ToolRegistry registry = candidate -> { /* no-op stub */ };

        // agent 桩实现中 findFrequent 返回空列表，agent 永不调用，传 null 即可
        ToolManufacturingPipeline pipeline = new ToolManufacturingPipeline(
                tracker, null, validator, gateway, registry);

        ToolCandidate result = pipeline.discoverAndManufacture("order-domain");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("保留 manufacture 便捷方法可用")
    void manufactureRemainsAvailable() {
        ToolManufacturingPipeline pipeline = new ToolManufacturingPipeline(
                new OperationTracker(), null, new SafetyValidator(),
                new ApprovalGateway(), candidate -> { });

        ToolManufacturingPipeline.ManufacturingResult mr =
                pipeline.manufacture("source", "spec");
        assertThat(mr).isNotNull();
        assertThat(mr.success()).isTrue();
    }
}

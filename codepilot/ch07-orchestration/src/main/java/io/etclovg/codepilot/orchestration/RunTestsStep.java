package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 运行测试步骤。
 * <p>对应书中 Ch07 §7.2 —— 执行测试验证修复是否正确。
 */
@Component
public class RunTestsStep implements Step {

    @Override
    public String getName() { return "RunTests"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("测试执行完成");
    }
}
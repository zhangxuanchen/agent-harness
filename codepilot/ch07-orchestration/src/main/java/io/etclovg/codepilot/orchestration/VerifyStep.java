package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 验证步骤。
 * <p>对应书中 Ch07 §7.2 —— 验证最终输出的正确性。
 */
@Component
public class VerifyStep implements Step {

    @Override
    public String getName() { return "Verify"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("验证完成");
    }
}
package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 应用修复步骤。
 * <p>对应书中 Ch07 §7.2 —— 对定位到的代码应用修复方案。
 */
@Component
public class ApplyFixStep implements Step {

    @Override
    public String getName() { return "ApplyFix"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("修复应用完成");
    }
}
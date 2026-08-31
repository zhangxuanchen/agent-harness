package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 收集步骤。
 * <p>对应书中 Ch07 §7.2 —— 收集执行过程中的数据和指标。
 */
@Component
public class CollectStep implements Step {

    @Override
    public String getName() { return "Collect"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("数据收集完成");
    }
}
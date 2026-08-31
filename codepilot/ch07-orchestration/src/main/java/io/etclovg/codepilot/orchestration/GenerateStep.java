package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 生成步骤。
 * <p>对应书中 Ch07 §7.2 —— 生成最终输出内容。
 */
@Component
public class GenerateStep implements Step {

    @Override
    public String getName() { return "Generate"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("内容生成完成");
    }
}
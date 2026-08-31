package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 创建 PR 步骤。
 * <p>对应书中 Ch07 §7.2 —— 自动创建 Pull Request。
 */
@Component
public class CreatePRStep implements Step {

    @Override
    public String getName() { return "CreatePR"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("PR 创建完成");
    }
}
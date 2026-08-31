package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 生成评论步骤。
 * <p>对应书中 Ch07 §7.2 —— 为代码变更生成 AI 评论/说明。
 */
@Component
public class GenerateCommentStep implements Step {

    @Override
    public String getName() { return "GenerateComment"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("评论生成完成");
    }
}
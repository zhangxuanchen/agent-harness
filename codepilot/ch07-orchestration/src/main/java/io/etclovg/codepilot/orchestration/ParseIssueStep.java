package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 解析问题步骤。
 * <p>对应书中 Ch07 §7.2 —— 解析用户提出的问题/需求。
 */
@Component
public class ParseIssueStep implements Step {

    @Override
    public String getName() { return "ParseIssue"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("问题解析完成");
    }
}
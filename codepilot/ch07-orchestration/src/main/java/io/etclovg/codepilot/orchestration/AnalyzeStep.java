package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 分析步骤。
 * <p>对应书中 Ch07 §7.2 —— 分析收集到的数据。
 */
@Component
public class AnalyzeStep implements Step {

    @Override
    public String getName() { return "Analyze"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("数据分析完成");
    }
}
package io.etclovg.codepilot.mlops;

import java.util.List;
import java.util.Map;

/**
 * 评估用例。对应书中 Ch16 §16.4.1。
 *
 * <p>描述一个可独立运行的 Agent 评估用例——由 KP 16.4.1 Step 4
 * 「回归覆盖」产出的复现用例，被 {@link EvalCaseGenerator} 从事故场景
 * 生成后注入 {@link EvalRegistry}，进入后续版本的 Eval Gate 回归套件。
 *
 * @param evalCaseId   用例唯一 ID
 * @param scenarioName 场景名称（对应事故类型）
 * @param inputs       用例输入（包含原始用户查询、上下文状态、工具调用链）
 * @param expectedOutcomes 预期结果标签（如"成功率=1""幻觉标记=0""使用工具=ticket_close"）
 * @param tags         标签（如 "regression", "incident-20250915", "task-type=refund"）
 * @param difficulty   难度分级（1-5，数值越高越难）
 */
public record EvalCase(
        String evalCaseId,
        String scenarioName,
        Map<String, Object> inputs,
        List<String> expectedOutcomes,
        List<String> tags,
        int difficulty
) {
    /**
     * 创建简单回归用例（仅指定 ID、输入、预期）。
     */
    public static EvalCase simple(String id, Map<String, Object> inputs,
                                   List<String> expectedOutcomes) {
        return new EvalCase(id, "regression-simple", inputs,
                expectedOutcomes, List.of("regression"), 1);
    }
}

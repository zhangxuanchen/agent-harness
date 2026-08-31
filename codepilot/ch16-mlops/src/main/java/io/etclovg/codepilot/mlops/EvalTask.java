package io.etclovg.codepilot.mlops;

/**
 * 评估任务（单条回归用例）。对应书中 Ch16 §16.2.1。
 * <p>AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 *
 * @param id       任务 ID
 * @param prompt   输入提示
 * @param expected 期望输出/验收标准
 */
public record EvalTask(String id, String prompt, Object expected) {
}

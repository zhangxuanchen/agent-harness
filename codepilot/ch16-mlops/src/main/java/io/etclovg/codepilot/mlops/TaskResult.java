package io.etclovg.codepilot.mlops;

/**
 * 单个评估任务的执行结果。对应书中 Ch16 §16.2.1。
 * <p>AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 *
 * @param taskId 任务 ID
 * @param passed 是否通过
 * @param score  得分（0-1）
 * @param latencyMs 延迟（毫秒）
 */
public record TaskResult(String taskId, boolean passed, double score, long latencyMs) {
}

package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.5.2：多 Agent 协作任务描述——
 * 用于 MAST 失败检测的必填字段（目标/约束/验收标准/Schema）+ 终止条件声明。
 */
public record AgentTask(String description, Object expectedOutputSchema,
                        String successCriteria, boolean terminationCondition) {
}

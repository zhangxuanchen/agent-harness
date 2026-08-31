package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.2：Agent 推理的输出，附带置信度评分和关键决策依据。
 * <p>注意：ConsistencyVerifier 内另有同名嵌套类型 AgentOutput，
 * 两者概念一致但物理独立，均用于概念示例。
 */
public record AgentOutput(String payload, double confidence, String rationale) {
}

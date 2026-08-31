package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.4：A2A 能力描述（A2A 要素 2）——
 * 声明"我能干什么"：输入 Schema、输出 Schema、工具集、SLA。
 */
public record CapabilityDesc(String inputSchema, String outputSchema,
                             java.util.Set<String> tools, String sla) {
}

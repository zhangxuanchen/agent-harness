package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.4：A2A 服务注册中心（A2A 要素 1）。
 */
public interface AgentRegistry {
    void register(String agentId, String role, CapabilityDesc capability);
}

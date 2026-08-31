package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.4：在线 Agent 实例的引用，可定向投递消息。
 */
public interface AgentRef {
    String agentId();
    void deliver(HintMessage message);
}

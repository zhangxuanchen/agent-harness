package io.etclovg.codepilot.multiagent;

import java.util.List;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.2：池化的模型 client，无状态可跨 Session 复用。
 */
public interface LlmClient {
    AgentOutput call(String systemPrompt, String compressedContext,
                     List<Message> history, AgentInput input);
}

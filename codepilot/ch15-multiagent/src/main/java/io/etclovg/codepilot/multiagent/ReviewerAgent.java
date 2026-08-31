package io.etclovg.codepilot.multiagent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 无状态推理句柄——Agent 本身不持任何会话状态。对应书中 Ch15 §15.2 · 概念示例。
 * <p>持 llm / tools / skills / interceptors，可跨 N 个 Session 复用，模型 client 池化，配置共享。
 * 生产部署时 Agent 作为 @Component 单例注入。
 */
@Component
public class ReviewerAgent {
    private final LlmClient llm;           // 池化的模型 client
    private final ToolRegistry tools;       // 只读工具集（Reviewer 无写权限）
    private final String systemPrompt;      // 角色定义

    public ReviewerAgent(LlmClient llm, ToolRegistry tools, String systemPrompt) {
        this.llm = llm;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
    }

    // 注意：不持有任何 sessionId / history / memory 字段
    // 同一个 ReviewerAgent 实例可同时服务 100 万个 Session

    public AgentOutput invoke(AgentSession session, AgentInput input) {
        // 从 session 取历史，而非从 this 取
        List<Message> history = session.getHistory();
        String compressed = session.getCompressedContext();
        // 推理时把 session 的历史 + 当前 input 一起送入 LLM
        return llm.call(systemPrompt, compressed, history, input);
    }
}

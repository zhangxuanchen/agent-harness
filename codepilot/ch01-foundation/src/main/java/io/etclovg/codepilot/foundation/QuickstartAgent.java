package io.etclovg.codepilot.foundation;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 快速入门 Agent。
 * <p>对应书中 Ch01 §1.3 —— 最简可运行 Agent 示例，用于快速验证环境配置。
 * <p>基于 AgentScope {@link ReActAgent}，封装最简调用入口。模型通过 ModelRegistry
 * 字符串解析（{@code "dashscope:qwen-plus"} 自动读取 {@code DASHSCOPE_API_KEY}）。
 */
@Component
public class QuickstartAgent {

    private static final Logger log = LoggerFactory.getLogger(QuickstartAgent.class);
    private final ReActAgent agent;

    public QuickstartAgent() {
        this.agent = ReActAgent.builder()
                .name("quickstart-agent")
                .sysPrompt("你是一个有帮助的助手。")
                .model("dashscope:qwen-plus")
                .build();
        log.info("[QuickstartAgent] 初始化完成");
    }

    /**
     * 发送消息并获取响应。
     *
     * @param message 用户消息
     * @return AI 响应文本
     */
    public String respond(String message) {
        log.info("[QuickstartAgent] 收到消息: {}", message);
        RuntimeContext rc = RuntimeContext.builder()
                .sessionId("quickstart-session")
                .userId("demo")
                .build();
        // ⚠️ .block() 仅在示例入口方法中使用；生产代码应保持响应式不阻塞
        Msg response = agent.call(message, rc).block();
        return response == null ? "" : response.getTextContent();
    }
}

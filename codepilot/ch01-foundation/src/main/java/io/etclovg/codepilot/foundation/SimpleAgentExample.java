package io.etclovg.codepilot.foundation;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 最简 Agent 示例：感知→决策→行动闭环。
 * <p>对应书中 Ch2 §2.1.2 的 PDA 循环实现。
 * <p>基于 AgentScope {@link ReActAgent}，模型通过 ModelRegistry 字符串解析
 * （{@code "dashscope:qwen-plus"} 自动读取 {@code DASHSCOPE_API_KEY}）。
 */
@Component
public class SimpleAgentExample {

    private static final Logger log = LoggerFactory.getLogger(SimpleAgentExample.class);

    private final ReActAgent agent;

    public SimpleAgentExample() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MyTools());
        this.agent = ReActAgent.builder()
                .name("simple-agent")
                .sysPrompt("你是一个有帮助的助手。")
                .model("dashscope:qwen-plus")
                .toolkit(toolkit)
                .build();
        log.info("[SimpleAgentExample] 初始化完成");
    }

    public String chat(String message) {
        RuntimeContext rc = RuntimeContext.builder()
                .sessionId("simple-session")
                .userId("demo")
                .build();
        // ⚠️ .block() 仅在示例入口方法中使用；生产代码应保持响应式不阻塞
        Msg response = agent.call(message, rc).block();
        return response == null ? "" : response.getTextContent();
    }
}

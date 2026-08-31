package io.etclovg.codepilot.casestudy;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CodePilot 全链路 Harness 案例研究。
 * <p>对应书中 Ch18 —— 展示七层 Harness 的完整集成。
 */
@Component
public class CodePilotFullHarness {

    private static final Logger log = LoggerFactory.getLogger(CodePilotFullHarness.class);

    private final ReActAgent agent;

    public CodePilotFullHarness() {
        this.agent = ReActAgent.builder()
                .name("codepilot-full")
                .sysPrompt("你是 CodePilot 全链路 Harness，负责协调七层 ETCLOVG 能力完成端到端编码任务。")
                .model("dashscope:qwen-plus")
                .build();
        log.info("[CodePilotFullHarness] 全链路 Harness 初始化完成");
    }

    /**
     * 执行完整的 Harness 流程。
     */
    public HarnessResult execute(String userRequest) {
        log.info("[CodePilotFullHarness] 执行全链路: request={}", userRequest);
        RuntimeContext rc = RuntimeContext.builder()
                .sessionId("full")
                .build();
        // ⚠️ .block() 仅在示例入口方法中使用；生产代码应保持响应式不阻塞
        Msg response = agent.call(userRequest, rc).block();
        String content = response == null ? "" : response.getTextContent();
        return new HarnessResult("SUCCESS", content, Map.of());
    }

    /**
     * Harness 执行结果。
     */
    public record HarnessResult(
            String status,
            String response,
            java.util.Map<String, Object> metadata
    ) {}
}
